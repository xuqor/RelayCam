#pragma once
#include <winsock2.h>
#include "shared.hpp"
#include <atomic>
#include <string>

struct Connection {
    std::atomic<bool> cancel{false};
    std::atomic<bool> flipHorizontal{false};
    void run(const std::wstring& host, SharedFrame* frames, HANDLE mutex, HWND window);
};

inline constexpr UINT statusMessage = WM_APP + 1;
enum StreamStatus {
    InvalidAddress = 1, Unreachable, PairingDeclined, InvalidStream,
    AwaitingApproval, Approved, OldAndroid,
    Connected, StreamRate, Disconnected, DecoderError, CryptoError, Cancelled,
};
