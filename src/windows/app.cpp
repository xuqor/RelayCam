#include "network.hpp"
#include <objbase.h>
#include <shellapi.h>
#include <shlobj.h>
#include <commctrl.h>
#include <algorithm>
#include "preview_frame.hpp"
#include <cwctype>
#include <thread>
#include <vector>

namespace {
constexpr int connectId = 101;
constexpr int installId = 102;
constexpr int removeId = 103;
constexpr int nameId = 104;
constexpr int addressId = 105;
constexpr int flipId = 106;
constexpr int previewId = 107;
constexpr UINT_PTR previewTimerId = 1;

HINSTANCE instance;
HWND nameEdit, addressEdit, statusLabel, connectButton, installButton, removeButton, flipButton, preview;
HFONT uiFont, titleFont;
HBRUSH background;
HANDLE frameMapping = nullptr, frameMutex = nullptr, singleInstance = nullptr;
SharedFrame* frames = nullptr;
Connection connection;
std::thread worker;
bool stopping = false;
UINT dpi = 96;

int px(int value) { return MulDiv(value, dpi, 96); }

std::wstring installDirectory() {
    PWSTR path = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_ProgramFiles, 0, nullptr, &path))) return {};
    std::wstring directory(path);
    CoTaskMemFree(path);
    return directory + L"\\RelayCam";
}

std::wstring cameraName() {
    wchar_t result[81]{};
    DWORD bytes = sizeof(result);
    LONG hr = RegGetValueW(HKEY_LOCAL_MACHINE, registryPath, L"CameraName",
        RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY, nullptr, result, &bytes);
    return hr == ERROR_SUCCESS && result[0] ? result : L"RelayCam";
}

bool validName(const std::wstring& name) {
    return !name.empty() && name.size() <= 80 && !iswspace(name.front()) && !iswspace(name.back()) &&
        std::none_of(name.begin(), name.end(), [](wchar_t c) { return iswcntrl(c) || c == L'"'; });
}

HRESULT resourceToFile(const wchar_t* file, int id) {
    HRSRC resource = FindResourceW(instance, MAKEINTRESOURCEW(id), RT_RCDATA);
    if (!resource) return HRESULT_FROM_WIN32(GetLastError());
    DWORD size = SizeofResource(instance, resource);
    const void* data = LockResource(LoadResource(instance, resource));
    if (!data || !size) return E_FAIL;

    HANDLE output = CreateFileW(file, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
        FILE_ATTRIBUTE_NORMAL, nullptr);
    if (output == INVALID_HANDLE_VALUE) return HRESULT_FROM_WIN32(GetLastError());
    DWORD written = 0;
    BOOL ok = WriteFile(output, data, size, &written, nullptr);
    CloseHandle(output);
    if (!ok || written != size) return HRESULT_FROM_WIN32(ERROR_WRITE_FAULT);
    return S_OK;
}

HRESULT runRegister32(const std::wstring& helper, const std::wstring& name, bool remove) {
    std::wstring command = L"\"" + helper + L"\" " + (remove ? L"-" : L"+" + name);
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process{};
    if (!CreateProcessW(helper.c_str(), command.data(), nullptr, nullptr, FALSE, 0,
            nullptr, nullptr, &startup, &process)) return HRESULT_FROM_WIN32(GetLastError());
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD result = E_FAIL;
    GetExitCodeProcess(process.hProcess, &result);
    CloseHandle(process.hProcess);
    CloseHandle(process.hThread);
    return static_cast<HRESULT>(result);
}

HRESULT registerCamera(const std::wstring& name, bool remove) {
    std::wstring dir = installDirectory();
    if (dir.empty()) return E_FAIL;
    if (!remove && !validName(name)) return E_INVALIDARG;
    if (!remove && !CreateDirectoryW(dir.c_str(), nullptr) && GetLastError() != ERROR_ALREADY_EXISTS)
        return HRESULT_FROM_WIN32(GetLastError());

    std::wstring dll64 = dir + L"\\RelayCamFilter64.dll";
    std::wstring dll32 = dir + L"\\RelayCamFilter32.dll";
    std::wstring helper = dir + L"\\RelayCamRegister32.exe";
    auto extractIfMissing = [](const std::wstring& path, int id) {
        return GetFileAttributesW(path.c_str()) == INVALID_FILE_ATTRIBUTES ?
            resourceToFile(path.c_str(), id) : S_OK;
    };

    if (!remove) {
        HRESULT result = extractIfMissing(dll64, 201);
        if (SUCCEEDED(result)) result = extractIfMissing(dll32, 202);
        if (SUCCEEDED(result)) result = extractIfMissing(helper, 203);
        if (FAILED(result)) return result;
    } else if (GetFileAttributesW(dir.c_str()) == INVALID_FILE_ATTRIBUTES) {
        return S_OK;
    }

    HMODULE module = LoadLibraryExW(dll64.c_str(), nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!module) return HRESULT_FROM_WIN32(GetLastError());
    using Register = HRESULT (WINAPI*)(const wchar_t*);
    auto apply = reinterpret_cast<Register>(GetProcAddress(module,
        remove ? "RelayCamRemove" : "RelayCamInstall"));
    if (!apply) { FreeLibrary(module); return E_NOINTERFACE; }
    bool previouslyRegistered = false;
    DWORD previousSize = 0;
    previouslyRegistered = RegGetValueW(HKEY_LOCAL_MACHINE, registryPath, L"CameraName",
        RRF_RT_REG_SZ, nullptr, nullptr, &previousSize) == ERROR_SUCCESS;
    std::wstring previous = cameraName();
    HRESULT result = apply(name.c_str());
    if (SUCCEEDED(result) || remove) {
        HRESULT result32 = runRegister32(helper, name, remove);
        if (SUCCEEDED(result)) result = result32;
    }
    if (!remove && FAILED(result)) {
        auto restore = reinterpret_cast<Register>(GetProcAddress(module,
            previouslyRegistered ? "RelayCamInstall" : "RelayCamRemove"));
        if (restore) restore(previous.c_str());
        runRegister32(helper, previous, !previouslyRegistered);
    }
    FreeLibrary(module);
    if (FAILED(result)) return result;

    if (remove) {
        RegDeleteTreeW(HKEY_LOCAL_MACHINE, registryPath);
        DeleteFileW(dll64.c_str());
        DeleteFileW(dll32.c_str());
        DeleteFileW(helper.c_str());
        RemoveDirectoryW(dir.c_str());
        return S_OK;
    }

    HKEY key = nullptr;
    LONG error = RegCreateKeyExW(HKEY_LOCAL_MACHINE, registryPath, 0, nullptr, 0,
        KEY_SET_VALUE, nullptr, &key, nullptr);
    if (error != ERROR_SUCCESS) return HRESULT_FROM_WIN32(error);
    error = RegSetValueExW(key, L"CameraName", 0, REG_SZ, reinterpret_cast<const BYTE*>(name.c_str()),
        static_cast<DWORD>((name.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(key);
    return error == ERROR_SUCCESS ? S_OK : HRESULT_FROM_WIN32(error);
}

void status(const wchar_t* text) { SetWindowTextW(statusLabel, text); }

std::wstring getText(HWND item) {
    int length = GetWindowTextLengthW(item);
    std::wstring text(length + 1, L'\0');
    GetWindowTextW(item, text.data(), length + 1);
    text.resize(length);
    return text;
}

void changeCamera(HWND window, bool remove) {
    auto requested = getText(nameEdit);
    if (!remove && !validName(requested)) {
        status(L"Enter a camera name of 1–80 characters, with no quotes or leading/trailing spaces.");
        return;
    }
    wchar_t self[MAX_PATH]{};
    if (!GetModuleFileNameW(nullptr, self, MAX_PATH)) return;
    std::wstring arguments = remove ? L"--remove" : L"--register \"" + requested + L"\"";
    // The raw argument is interpreted after the prefix. Escape final backslashes for the quoted name.
    if (!remove) {
        size_t slashes = 0;
        for (auto i = requested.rbegin(); i != requested.rend() && *i == L'\\'; ++i) ++slashes;
        arguments.insert(arguments.size() - 1, slashes, L'\\');
    }
    SHELLEXECUTEINFOW shell{};
    shell.cbSize = sizeof(shell);
    shell.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI;
    shell.hwnd = window;
    shell.lpVerb = L"runas";
    shell.lpFile = self;
    shell.lpParameters = arguments.c_str();
    shell.nShow = SW_HIDE;
    if (!ShellExecuteExW(&shell)) {
        status(GetLastError() == ERROR_CANCELLED ? L"Camera setup cancelled." : L"Could not start camera setup.");
        return;
    }
    WaitForSingleObject(shell.hProcess, INFINITE);
    DWORD result = E_FAIL;
    GetExitCodeProcess(shell.hProcess, &result);
    CloseHandle(shell.hProcess);
    if (FAILED(static_cast<HRESULT>(result))) {
        wchar_t message[180]{};
        swprintf(message, 180, L"Camera setup failed (0x%08lx). Close apps using the camera and retry.", result);
        status(message);
    } else {
        status(remove ? L"Virtual camera removed." :
            L"Camera name applied. Reopen Discord or your other camera app.");
        if (remove) SetWindowTextW(nameEdit, L"RelayCam");
    }
}

HWND control(HWND window, const wchar_t* kind, const wchar_t* text, DWORD style, DWORD ext,
        int x, int y, int width, int height, int id = 0) {
    HWND result = CreateWindowExW(ext, kind, text, WS_CHILD | WS_VISIBLE | style,
        px(x), px(y), px(width), px(height), window, reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),
        instance, nullptr);
    SendMessageW(result, WM_SETFONT, reinterpret_cast<WPARAM>(uiFont), TRUE);
    return result;
}

PreviewFrame previewImage;
std::vector<uint8_t> previewYuv;
PreviewSize previewSize{};
int previewSourceW = 0, previewSourceH = 0;
uint32_t previewSequence = 0;
uint64_t previewUpdated = 0;

void paintPreview(HWND window) {
    PAINTSTRUCT paint{};
    HDC dc = BeginPaint(window, &paint);
    RECT area{};
    GetClientRect(window, &area);

    bool newFrame = false;
    if (frames && previewYuv.size() && acquire(frameMutex, 1)) {
        if (frames->updated && knownMode(frames->width, frames->height, frames->fps)) {
            if (frames->updated != previewUpdated || frames->sequence != previewSequence) {
                previewSourceW = frames->width;
                previewSourceH = frames->height;
                previewUpdated = frames->updated;
                previewSequence = frames->sequence;
                std::memcpy(previewYuv.data(), frames->i420, previewSourceW * previewSourceH * 3 / 2);
                newFrame = true;
            }
        } else {
            previewUpdated = 0;
        }
        ReleaseMutex(frameMutex);
    }

    if (previewUpdated && GetTickCount64() - previewUpdated < 1600) {
        const auto output = fitPreview(previewSourceW, previewSourceH,
            std::min<LONG>(area.right, 1280), std::min<LONG>(area.bottom, 720));
        if (output.width && output.height) {
            if (newFrame || output.width != previewSize.width || output.height != previewSize.height) {
                previewImage.render(previewYuv.data(), previewSourceW, previewSourceH, output);
                previewSize = output;
            }
            const int drawW = std::min<int>(area.right, area.bottom * previewSourceW / previewSourceH);
            const int drawH = std::min<int>(area.bottom, area.right * previewSourceH / previewSourceW);
            const int left = (area.right - drawW) / 2, top = (area.bottom - drawH) / 2;
            HBRUSH black = static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
            const RECT bars[] = {{0, 0, area.right, top}, {0, top + drawH, area.right, area.bottom},
                {0, top, left, top + drawH}, {left + drawW, top, area.right, top + drawH}};
            for (const auto& bar : bars) FillRect(dc, &bar, black);
            BITMAPINFO bitmap{};
            bitmap.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
            bitmap.bmiHeader.biWidth = output.width;
            bitmap.bmiHeader.biHeight = -output.height;
            bitmap.bmiHeader.biPlanes = 1;
            bitmap.bmiHeader.biBitCount = 32;
            StretchDIBits(dc, left, top, drawW, drawH, 0, 0, output.width, output.height,
                previewImage.pixels.data(), &bitmap, DIB_RGB_COLORS, SRCCOPY);
        }
    } else {
        FillRect(dc, &area, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
        SetTextColor(dc, RGB(230, 230, 230));
        SetBkMode(dc, TRANSPARENT);
        DrawTextW(dc, L"Waiting for video…", -1, &area, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }
    EndPaint(window, &paint);
}

LRESULT CALLBACK previewProc(HWND window, UINT msg, WPARAM w, LPARAM l) {
    switch (msg) {
        case WM_CREATE: SetTimer(window, previewTimerId, 16, nullptr); return 0;
        case WM_TIMER:
            if (!IsIconic(window)) InvalidateRect(window, nullptr, FALSE);
            return 0;
        case WM_SIZE: InvalidateRect(window, nullptr, FALSE); return 0;
        case WM_ERASEBKGND: return 1;
        case WM_PAINT: paintPreview(window); return 0;
        case WM_DESTROY:
            KillTimer(window, previewTimerId);
            preview = nullptr;
            previewUpdated = 0;
            previewSize = {};
            std::vector<uint8_t>().swap(previewYuv);
            previewImage = {};
            return 0;
    }
    return DefWindowProcW(window, msg, w, l);
}

void showPreview(HWND owner) {
    if (preview) {
        if (IsIconic(preview)) ShowWindow(preview, SW_RESTORE);
        SetForegroundWindow(preview);
        return;
    }
    try { previewYuv.resize(maxPixels * 3 / 2); }
    catch (...) { status(L"Could not allocate the video preview."); return; }
    preview = CreateWindowW(L"RelayCamPreview", L"RelayCam — outgoing video", WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT, px(800), px(495), owner, nullptr, instance, nullptr);
    if (preview) ShowWindow(preview, SW_SHOWNORMAL);
    else std::vector<uint8_t>().swap(previewYuv);
}

void configureWindow(HWND window) {
    uiFont = CreateFontW(-px(17), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
    titleFont = CreateFontW(-px(36), 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
    background = CreateSolidBrush(RGB(245,247,252));
    HWND title = control(window, L"STATIC", L"RelayCam", 0, 0, 26, 20, 440, 50);
    SendMessageW(title, WM_SETFONT, reinterpret_cast<WPARAM>(titleFont), TRUE);

    control(window, L"STATIC", L"Virtual camera name", 0, 0, 28, 79, 450, 24);
    nameEdit = control(window, L"EDIT", cameraName().c_str(), ES_AUTOHSCROLL | WS_TABSTOP,
        WS_EX_CLIENTEDGE, 28, 105, 464, 32, nameId);
    SendMessageW(nameEdit, EM_LIMITTEXT, 80, 0);
    installButton = control(window, L"BUTTON", L"Apply camera name", BS_PUSHBUTTON | WS_TABSTOP,
        0, 28, 147, 174, 34, installId);
    removeButton = control(window, L"BUTTON", L"Remove camera", BS_PUSHBUTTON | WS_TABSTOP,
        0, 210, 147, 148, 34, removeId);
    control(window, L"STATIC", L"Start RelayCam on your phone on the same Wi-Fi.", 0, 0, 28, 207, 464, 25);
    control(window, L"STATIC", L"Phone IP", 0, 0, 28, 247, 166, 23);
    wchar_t lastAddress[16]{};
    DWORD length = sizeof(lastAddress);
    RegGetValueW(HKEY_CURRENT_USER, registryPath, L"LastPhoneIp", RRF_RT_REG_SZ,
        nullptr, lastAddress, &length);
    addressEdit = control(window, L"EDIT", lastAddress, ES_AUTOHSCROLL | WS_TABSTOP,
        WS_EX_CLIENTEDGE, 28, 273, 169, 34, addressId);
    SendMessageW(addressEdit, EM_LIMITTEXT, 15, 0);
    SendMessageW(addressEdit, EM_SETCUEBANNER, FALSE, reinterpret_cast<LPARAM>(L"192.168.1.24"));
    connectButton = control(window, L"BUTTON", L"Connect phone", BS_DEFPUSHBUTTON | WS_TABSTOP,
        0, 28, 319, 174, 37, connectId);
    control(window, L"BUTTON", L"Preview outgoing video", BS_PUSHBUTTON | WS_TABSTOP,
        0, 214, 319, 215, 37, previewId);
    flipButton = control(window, L"BUTTON", L"Flip outgoing video left to right", BS_AUTOCHECKBOX | WS_TABSTOP,
        0, 214, 273, 278, 34, flipId);
    DWORD flipped = 0;
    DWORD flipBytes = sizeof(flipped);
    if (RegGetValueW(HKEY_CURRENT_USER, registryPath, L"FlipHorizontal", RRF_RT_REG_DWORD,
            nullptr, &flipped, &flipBytes) == ERROR_SUCCESS) connection.flipHorizontal = flipped != 0;
    SendMessageW(flipButton, BM_SETCHECK, connection.flipHorizontal ? BST_CHECKED : BST_UNCHECKED, 0);
    statusLabel = control(window, L"STATIC", L"Apply a camera name once to install the virtual camera.",
        0, 0, 28, 373, 464, 48);
}

void toggleConnection(HWND window) {
    if (worker.joinable()) {
        connection.cancel = true;
        stopping = true;
        EnableWindow(connectButton, FALSE);
        status(L"Disconnecting…");
        return;
    }
    auto address = getText(addressEdit);
    if (address.empty()) {
        status(L"Enter the phone’s IPv4 address.");
        return;
    }

    if (!frames) {
        frameMutex = CreateMutexW(nullptr, FALSE, frameLock);
        frameMapping = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
            sizeof(SharedFrame), frameMap);
        if (frameMapping) frames = static_cast<SharedFrame*>(MapViewOfFile(frameMapping,
            FILE_MAP_WRITE, 0, 0, sizeof(SharedFrame)));
        if (!frames || !frameMutex) {
            if (frames) UnmapViewOfFile(frames);
            if (frameMapping) CloseHandle(frameMapping);
            if (frameMutex) CloseHandle(frameMutex);
            frames = nullptr;
            frameMapping = frameMutex = nullptr;
            status(L"Could not open the virtual camera’s frame buffer.");
            return;
        }
    }

    connection.cancel = false;
    stopping = false;
    try {
        worker = std::thread(&Connection::run, &connection, address, frames, frameMutex, window);
    } catch (...) {
        status(L"Could not start the connection.");
        return;
    }
    EnableWindow(addressEdit, FALSE);
    SetWindowTextW(connectButton, L"Disconnect");
    status(L"Connecting to the phone…");
}

void streamMessage(WPARAM state, LPARAM detail) {
    if (state == AwaitingApproval) {
        if (!stopping) {
            wchar_t text[170]{};
            swprintf(text, 170, L"Pairing code %06lu. If your phone shows the same code, tap Allow there.",
                static_cast<unsigned long>(detail));
            status(text);
        }
        return;
    }
    if (state == Approved) {
        if (!stopping) status(L"Approved. Waiting for video…");
        return;
    }
    if (state == StreamRate) {
        if (!stopping) {
            wchar_t text[180]{};
            swprintf(text, 180, L"Live · %lup · %lu fps received / %lu fps output",
                static_cast<unsigned long>(detail >> 16),
                static_cast<unsigned long>((detail >> 8) & 255), static_cast<unsigned long>(detail & 255));
            status(text);
        }
        return;
    }
    if (state == Connected) {
        if (!stopping) {
            wchar_t text[170]{};
            swprintf(text, 170, L"Phone connected · %lldp at up to %lld fps. Select the virtual camera in your app.",
                    static_cast<long long>(detail >> 8), static_cast<long long>(detail & 255));
            status(text);
            auto address = getText(addressEdit);
            RegSetKeyValueW(HKEY_CURRENT_USER, registryPath, L"LastPhoneIp", REG_SZ,
                address.c_str(), static_cast<DWORD>((address.size() + 1) * sizeof(wchar_t)));
        }
        return;
    }
    if (worker.joinable()) worker.join();
    EnableWindow(connectButton, TRUE);
    EnableWindow(addressEdit, TRUE);
    SetWindowTextW(connectButton, L"Connect phone");
    switch (state) {
        case Cancelled: status(L"Disconnected."); break;
        case InvalidAddress: status(L"Enter the phone’s IPv4 address as shown on Android."); break;
        case Unreachable: status(L"Could not reach the phone. Check Wi-Fi and start the Android app."); break;
        case PairingDeclined: status(L"Pairing was declined or timed out. Click Connect phone to try again."); break;
        case OldAndroid: status(L"Update the Android app to RelayCam 0.2 to use tap-to-pair."); break;
        case InvalidStream: status(L"The incoming stream could not be authenticated or read."); break;
        case DecoderError: status(L"The video decoder could not continue. Try a lower camera mode."); break;
        case CryptoError: status(L"Windows could not initialize the encrypted connection."); break;
        default: status(L"The phone disconnected. Tap Connect phone to retry."); break;
    }
}

LRESULT CALLBACK windowProc(HWND window, UINT msg, WPARAM w, LPARAM l) {
    switch (msg) {
        case WM_CREATE: configureWindow(window); return 0;
        case WM_CTLCOLORSTATIC:
            SetTextColor(reinterpret_cast<HDC>(w), RGB(23,30,48));
            SetBkMode(reinterpret_cast<HDC>(w), TRANSPARENT);
            return reinterpret_cast<LRESULT>(background);
        case WM_ERASEBKGND: {
            RECT area{};
            GetClientRect(window, &area);
            FillRect(reinterpret_cast<HDC>(w), &area, background);
            return 1;
        }
        case WM_COMMAND:
            switch (LOWORD(w)) {
                case connectId: toggleConnection(window); return 0;
                case installId: changeCamera(window, false); return 0;
                case removeId: changeCamera(window, true); return 0;
                case previewId: showPreview(window); return 0;
                case flipId: {
                    DWORD flipped = SendMessageW(flipButton, BM_GETCHECK, 0, 0) == BST_CHECKED;
                    connection.flipHorizontal = flipped != 0;
                    RegSetKeyValueW(HKEY_CURRENT_USER, registryPath, L"FlipHorizontal", REG_DWORD,
                        &flipped, sizeof(flipped));
                    return 0;
                }
            }
            break;
        case statusMessage: streamMessage(w, l); return 0;
        case WM_DESTROY:
            if (preview) DestroyWindow(preview);
            connection.cancel = true;
            if (worker.joinable()) worker.join();
            if (frames) UnmapViewOfFile(frames);
            if (frameMapping) CloseHandle(frameMapping);
            if (frameMutex) CloseHandle(frameMutex);
            if (uiFont) DeleteObject(uiFont);
            if (titleFont) DeleteObject(titleFont);
            if (background) DeleteObject(background);
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(window, msg, w, l);
}
}

int WINAPI wWinMain(HINSTANCE app, HINSTANCE, LPWSTR, int show) {
    instance = app;
    HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(com)) return com;
    int count = 0;
    LPWSTR* args = CommandLineToArgvW(GetCommandLineW(), &count);
    if (args && count >= 2) {
        std::wstring action = args[1];
        if (action == L"--register" || action == L"--remove") {
            HRESULT hr = registerCamera(count == 3 ? args[2] : L"", action == L"--remove");
            LocalFree(args);
            CoUninitialize();
            return hr;
        }
    }
    if (args) LocalFree(args);

    singleInstance = CreateMutexW(nullptr, FALSE, L"Local\\RelayCam.App.1");
    if (!singleInstance || GetLastError() == ERROR_ALREADY_EXISTS) {
        if (singleInstance) CloseHandle(singleInstance);
        MessageBoxW(nullptr, L"RelayCam is already open or could not start.", L"RelayCam", MB_ICONINFORMATION);
        CoUninitialize();
        return 1;
    }

    InitCommonControls();
    dpi = GetDpiForSystem();
    const wchar_t kind[] = L"RelayCamMain";
    WNDCLASSW klass{};
    klass.hInstance = app;
    klass.lpfnWndProc = windowProc;
    klass.lpszClassName = kind;
    klass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    klass.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    RegisterClassW(&klass);
    klass.lpfnWndProc = previewProc;
    klass.lpszClassName = L"RelayCamPreview";
    RegisterClassW(&klass);
    const DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    RECT size{0, 0, px(520), px(435)};
    AdjustWindowRect(&size, style, FALSE);
    HWND window = CreateWindowW(kind, L"RelayCam", style, CW_USEDEFAULT, CW_USEDEFAULT,
        size.right - size.left, size.bottom - size.top, nullptr, nullptr, app, nullptr);
    if (window) {
        ShowWindow(window, show);
        MSG message{};
        while (GetMessageW(&message, nullptr, 0, 0) > 0) {
            if (!IsDialogMessageW(window, &message)) {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
    }
    CloseHandle(singleInstance);
    CoUninitialize();
    return window ? 0 : 1;
}
