#include "shared.hpp"
#include "dshow/source/output-filter.hpp"
#include <atomic>
#include <algorithm>
#include <new>
#include <thread>
#include <vector>

using DShow::VideoFormat;

class FrameReader {
    HANDLE mapping = nullptr;
    HANDLE mutex = nullptr;
    const SharedFrame* frame = nullptr;
    std::vector<uint8_t> bytes;
    uint64_t lastUpdated = 0;
    uint32_t sequence = 0;
    int width = 0, height = 0;

public:
    FrameReader() { bytes.reserve(maxPixels * 3 / 2); }

    ~FrameReader() {
        if (frame) UnmapViewOfFile(frame);
        if (mapping) CloseHandle(mapping);
        if (mutex) CloseHandle(mutex);
    }

    VideoMode read() {
        if (!mutex) mutex = OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE, frameLock);
        if (!mapping) mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, frameMap);
        if (!frame && mapping) frame = static_cast<const SharedFrame*>(
            MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, sizeof(SharedFrame)));
        int fps = 0;
        if (frame && mutex && acquire(mutex, 2)) {
            if (frame->updated && GetTickCount64() - frame->updated < 1600 &&
                    knownMode(frame->width, frame->height, frame->fps)) {
                fps = frame->fps;
                if (frame->updated != lastUpdated || frame->sequence != sequence) {
                    width = frame->width;
                    height = frame->height;
                    bytes.resize(width * height * 3 / 2);
                    std::memcpy(bytes.data(), frame->i420, bytes.size());
                    lastUpdated = frame->updated;
                    sequence = frame->sequence;
                }
            } else {
                lastUpdated = 0;
            }
            ReleaseMutex(mutex);
        }
        return {width, height, fps};
    }

    static void scale(const uint8_t* from, int sw, int sh, uint8_t* to,
            int stride, int step, int tw, int th) {
        if (sw == tw && sh == th && step == 1 && stride == tw) {
            std::memcpy(to, from, sw * sh);
            return;
        }
        for (int y = 0; y < th; ++y) {
            auto* input = from + (y * sh / th) * sw;
            auto* output = to + y * stride;
            for (int x = 0; x < tw; ++x) output[x * step] = input[x * sw / tw];
        }
    }

    void render(uint8_t* dst, int w, int h, VideoFormat format) {
        read();
        bool live = lastUpdated && GetTickCount64() - lastUpdated < 1600;
        const int pixels = w * h;
        if (format == VideoFormat::YUY2) {
            for (int y = 0; y < h; ++y) {
                int sy = live ? y * height / h : 0;
                for (int x = 0; x < w; x += 2) {
                    auto* out = dst + (y * w + x) * 2;
                    if (!live) {
                        out[0] = out[2] = 16;
                        out[1] = out[3] = 128;
                        continue;
                    }
                    int sourcePixels = width * height;
                    int uv = (sy / 2) * (width / 2) + (x / 2) * (width / 2) / (w / 2);
                    out[0] = bytes[sy * width + x * width / w];
                    out[1] = bytes[sourcePixels + uv];
                    out[2] = bytes[sy * width + (x + 1) * width / w];
                    out[3] = bytes[sourcePixels * 5 / 4 + uv];
                }
            }
            return;
        }
        if (!live) {
            std::memset(dst, 16, pixels);
            std::memset(dst + pixels, 128, pixels / 2);
            return;
        }

        const int sourcePixels = width * height;
        scale(bytes.data(), width, height, dst, w, 1, w, h);
        const bool interleaved = format == VideoFormat::NV12;
        const int stride = interleaved ? w : w / 2;
        const int step = interleaved ? 2 : 1;
        scale(bytes.data() + sourcePixels, width / 2, height / 2,
            dst + pixels, stride, step, w / 2, h / 2);
        scale(bytes.data() + sourcePixels * 5 / 4, width / 2, height / 2,
            dst + pixels + (interleaved ? 1 : pixels / 4), stride, step, w / 2, h / 2);
    }
};

class Camera final : public DShow::OutputFilter {
    FrameReader frames;
    HANDLE stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    std::thread worker;
    std::atomic<bool> active{false};

    void capture() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        HANDLE timer = CreateWaitableTimerExW(nullptr, nullptr, 2, TIMER_ALL_ACCESS);
        if (!timer) timer = CreateWaitableTimerW(nullptr, FALSE, nullptr);
        HANDLE events[] = {stopEvent, timer};
        LARGE_INTEGER frequency{}, start{}, now{};
        QueryPerformanceFrequency(&frequency);
        QueryPerformanceCounter(&start);
        LONGLONG timestamp = 0;
        bool resetClock = true;
        while (WaitForSingleObject(stopEvent, 0) == WAIT_TIMEOUT) {
            if (!active.load()) {
                resetClock = true;
                WaitForSingleObject(stopEvent, 5);
                continue;
            }
            if (resetClock) {
                QueryPerformanceCounter(&start);
                timestamp = 0;
                resetClock = false;
            }
            LONGLONG interval = GetInterval();
            uint8_t* data = nullptr;
            if (LockSampleData(&data)) {
                frames.render(data, GetCX(), GetCY(), GetVideoFormat());
                UnlockSampleData(timestamp, timestamp + interval);
            }
            QueryPerformanceCounter(&now);
            LONGLONG elapsed = (now.QuadPart - start.QuadPart) * 10000000 / frequency.QuadPart;
            timestamp = std::max(timestamp + interval, elapsed);
            LARGE_INTEGER wait{};
            wait.QuadPart = -std::max<LONGLONG>(timestamp - elapsed, 1);
            if (timer && SetWaitableTimer(timer, &wait, 0, nullptr, nullptr, FALSE))
                WaitForMultipleObjects(2, events, FALSE, INFINITE);
            else
                WaitForSingleObject(stopEvent, static_cast<DWORD>((-wait.QuadPart + 9999) / 10000));
        }
        if (timer) CloseHandle(timer);
        CoUninitialize();
    }

public:
    Camera() {
        VideoMode preferred = frames.read();
        if (!preferred.fps) preferred = modes[0];
        for (auto format : {VideoFormat::NV12, VideoFormat::I420, VideoFormat::YUY2}) {
            AddVideoFormat(format, preferred.width, preferred.height, 10000000LL / preferred.fps);
            for (const auto& mode : modes) {
                if (mode.width == preferred.width && mode.height == preferred.height && mode.fps == preferred.fps)
                    continue;
                AddVideoFormat(format, mode.width, mode.height, 10000000LL / mode.fps);
            }
        }
        SetVideoFormat(VideoFormat::NV12, preferred.width, preferred.height, 10000000LL / preferred.fps);
    }

    ~Camera() override {
        Stop();
        if (stopEvent) CloseHandle(stopEvent);
    }

    const wchar_t* FilterName() const override { return L"RelayCam"; }
    STDMETHODIMP GetClassID(CLSID* clsid) override {
        if (!clsid) return E_POINTER;
        *clsid = cameraId;
        return S_OK;
    }

    STDMETHODIMP Pause() override {
        HRESULT result = OutputFilter::Pause();
        if (FAILED(result)) return result;
        if (!stopEvent) return E_OUTOFMEMORY;
        active = true;
        if (!worker.joinable()) {
            ResetEvent(stopEvent);
            try { worker = std::thread(&Camera::capture, this); }
            catch (...) { active = false; return E_OUTOFMEMORY; }
        }
        return S_OK;
    }

    STDMETHODIMP Run(REFERENCE_TIME start) override {
        if (!worker.joinable()) {
            HRESULT result = Pause();
            if (FAILED(result)) return result;
        }
        return OutputFilter::Run(start);
    }

    STDMETHODIMP Stop() override {
        active = false;
        if (stopEvent) SetEvent(stopEvent);
        ComPtr<IPin> downstream;
        if (GetPin()->ConnectedTo(&downstream) == S_OK) downstream->BeginFlush();
        if (worker.joinable()) worker.join();
        if (downstream) downstream->EndFlush();
        return OutputFilter::Stop();
    }
};

class CameraFactory final : public IClassFactory {
    LONG references = 1;
public:
    STDMETHODIMP QueryInterface(REFIID id, void** output) override {
        if (!output) return E_POINTER;
        *output = nullptr;
        if (id != IID_IUnknown && id != IID_IClassFactory) return E_NOINTERFACE;
        *output = static_cast<IClassFactory*>(this);
        AddRef();
        return S_OK;
    }

    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&references); }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG count = InterlockedDecrement(&references);
        if (!count) delete this;
        return count;
    }
    STDMETHODIMP LockServer(BOOL) override { return S_OK; }

    STDMETHODIMP CreateInstance(IUnknown* outer, REFIID id, void** output) override {
        if (!output) return E_POINTER;
        *output = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        try {
            auto* camera = new Camera();
            camera->AddRef();
            HRESULT result = camera->QueryInterface(id, output);
            camera->Release();
            return result;
        } catch (...) {
            return E_OUTOFMEMORY;
        }
    }
};

static HINSTANCE module = nullptr;

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, void*) {
    if (reason == DLL_PROCESS_ATTACH) module = instance;
    return TRUE;
}

extern "C" __declspec(dllexport) HRESULT WINAPI DllCanUnloadNow() { return S_FALSE; }

extern "C" __declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID clsid, REFIID id, void** out) {
    if (!out) return E_POINTER;
    *out = nullptr;
    if (clsid != cameraId) return CLASS_E_CLASSNOTAVAILABLE;
    auto* factory = new (std::nothrow) CameraFactory();
    if (!factory) return E_OUTOFMEMORY;
    HRESULT hr = factory->QueryInterface(id, out);
    factory->Release();
    return hr;
}

extern "C" __declspec(dllexport) HRESULT WINAPI RelayCamRegister(const wchar_t* name, BOOL install) {
    wchar_t guid[40]{};
    StringFromGUID2(cameraId, guid, 40);
    std::wstring key = std::wstring(L"Software\\Classes\\CLSID\\") + guid;
    ComPtr<IFilterMapper2> mapper;
    HRESULT hr = CoCreateInstance(CLSID_FilterMapper2, nullptr, CLSCTX_INPROC_SERVER,
        IID_IFilterMapper2, reinterpret_cast<void**>(&mapper));
    if (FAILED(hr)) return hr;
    if (!install) {
        const HRESULT removed = mapper->UnregisterFilter(&CLSID_VideoInputDeviceCategory, nullptr, cameraId);
        const LONG error = RegDeleteTreeW(HKEY_LOCAL_MACHINE, key.c_str());
        if (error != ERROR_SUCCESS && error != ERROR_FILE_NOT_FOUND) return HRESULT_FROM_WIN32(error);
        return removed == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND) || removed == MK_E_NOOBJECT ||
            removed == VFW_E_NOT_FOUND ?
            S_OK : removed;
    }

    wchar_t dll[MAX_PATH]{};
    DWORD length = GetModuleFileNameW(module, dll, MAX_PATH);
    if (!length || length >= MAX_PATH) return E_FAIL;
    key += L"\\InprocServer32";
    HKEY entry{};
    LONG error = RegCreateKeyExW(HKEY_LOCAL_MACHINE, key.c_str(), 0, nullptr, 0,
            KEY_SET_VALUE, nullptr, &entry, nullptr);
    if (error != ERROR_SUCCESS) return HRESULT_FROM_WIN32(error);
    error = RegSetValueExW(entry, nullptr, 0, REG_SZ, reinterpret_cast<BYTE*>(dll),
        (length + 1) * sizeof(wchar_t));
    if (error == ERROR_SUCCESS) {
        const wchar_t both[] = L"Both";
        error = RegSetValueExW(entry, L"ThreadingModel", 0, REG_SZ,
            reinterpret_cast<const BYTE*>(both), sizeof(both));
    }
    RegCloseKey(entry);
    if (error != ERROR_SUCCESS) return HRESULT_FROM_WIN32(error);

    static constexpr GUID i420 = {0x30323449,0,0x0010,{0x80,0,0,0xaa,0,0x38,0x9b,0x71}};
    const REGPINTYPES formats[] = {
        {&MEDIATYPE_Video, &MEDIASUBTYPE_NV12}, {&MEDIATYPE_Video, &i420},
        {&MEDIATYPE_Video, &MEDIASUBTYPE_YUY2}
    };
    const REGFILTERPINS2 pin = {REG_PINFLAG_B_OUTPUT, 1, 3, formats, 0, nullptr, &PIN_CATEGORY_CAPTURE};
    REGFILTER2 filter{};
    filter.dwVersion = 2;
    filter.dwMerit = MERIT_DO_NOT_USE;
    filter.cPins2 = 1;
    filter.rgPins2 = &pin;
    return mapper->RegisterFilter(cameraId, name, nullptr, &CLSID_VideoInputDeviceCategory, nullptr, &filter);
}

extern "C" __declspec(dllexport) HRESULT WINAPI RelayCamInstall(const wchar_t* name) {
    return RelayCamRegister(name, TRUE);
}

extern "C" __declspec(dllexport) HRESULT WINAPI RelayCamRemove(const wchar_t*) {
    return RelayCamRegister(nullptr, FALSE);
}
