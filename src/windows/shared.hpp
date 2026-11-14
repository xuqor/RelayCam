#pragma once
#include <windows.h>
#include <cstdint>
#include <cstring>

inline constexpr wchar_t frameMap[] = L"Local\\RelayCam.Video.1";
inline constexpr wchar_t frameLock[] = L"Local\\RelayCam.VideoLock.1";
inline constexpr wchar_t registryPath[] = L"Software\\RelayCam";
inline constexpr GUID cameraId = {0x24d9602a,0xd365,0x4830,{0xaf,0x84,0x3f,0xd7,0x11,0x8a,0x67,0x4e}};
inline constexpr int maxPixels = 3840 * 2160;

struct VideoMode { int width, height, fps; };

inline constexpr VideoMode modes[] = {
    {1280,720,30}, {1280,720,60}, {1920,1080,30}, {1920,1080,60},
    {2048,1080,30}, {2048,1080,60}, {2560,1440,30}, {2560,1440,60},
    {3840,2160,30}, {3840,2160,60}, {640,480,30}, {640,360,30},
};

inline bool knownMode(int w, int h, int fps) {
    for (const auto& mode : modes) {
        if (mode.width == w && mode.height == h && mode.fps == fps) return true;
    }
    return false;
}

struct SharedFrame {
    uint32_t width, height, fps, sequence;
    uint64_t updated;
    uint8_t i420[maxPixels * 3 / 2];
};

inline bool acquire(HANDLE mutex, DWORD timeout) {
    DWORD result = WaitForSingleObject(mutex, timeout);
    return result == WAIT_OBJECT_0 || result == WAIT_ABANDONED;
}
