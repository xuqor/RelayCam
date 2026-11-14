#include <windows.h>
#include <string>

int WINAPI wWinMain(HINSTANCE, HINSTANCE, LPWSTR arguments, int) {
    wchar_t path[MAX_PATH]{};
    DWORD size = GetModuleFileNameW(nullptr, path, MAX_PATH);
    if (size == 0 || size >= MAX_PATH) return E_FAIL;
    std::wstring dll(path);
    dll.erase(dll.find_last_of(L'\\') + 1);
    dll += L"RelayCamFilter32.dll";
    HMODULE module = LoadLibraryExW(dll.c_str(), nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!module) return HRESULT_FROM_WIN32(GetLastError());
    using Register = HRESULT(WINAPI*)(const wchar_t*);
    bool remove = *arguments == L'-';
    const wchar_t* name = *arguments == L'+' ? arguments + 1 : arguments;
    Register function = reinterpret_cast<Register>(GetProcAddress(module,
        remove ? "RelayCamRemove" : "RelayCamInstall"));
    if (!function) return E_NOINTERFACE;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(hr)) return hr;
    hr = function(name);
    CoUninitialize();
    return hr;
}
