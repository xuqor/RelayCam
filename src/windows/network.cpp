#include "network.hpp"
#include "frame_orientation.hpp"
#include <bcrypt.h>
#include <dpapi.h>
#include <ws2tcpip.h>
#include <algorithm>
#include <array>
#include <memory>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/pixfmt.h>
}

namespace {
constexpr DWORD maxPacket = 8 * 1024 * 1024 + 16;

struct NetworkRuntime {
    WSADATA data{};
    bool ready = WSAStartup(MAKEWORD(2, 2), &data) == 0;
    ~NetworkRuntime() { if (ready) WSACleanup(); }
};

struct Peer {
    SOCKET socket = INVALID_SOCKET;
    ~Peer() { if (socket != INVALID_SOCKET) closesocket(socket); }
};

using PublicKey = std::array<uint8_t, 64>;
using Digest = std::array<uint8_t, 32>;

bool sha256(const uint8_t* data, ULONG size, Digest& result, const Digest* key = nullptr) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, key ? BCRYPT_ALG_HANDLE_HMAC_FLAG : 0) < 0) return false;
    bool ok = BCryptCreateHash(algorithm, &hash, nullptr, 0,
            key ? const_cast<PUCHAR>(key->data()) : nullptr, key ? key->size() : 0, 0) >= 0 &&
        BCryptHashData(hash, const_cast<PUCHAR>(data), size, 0) >= 0 &&
        BCryptFinishHash(hash, result.data(), result.size(), 0) >= 0;
    if (hash) BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(algorithm, 0);
    return ok;
}

constexpr wchar_t trustRegistry[] = L"Software\\RelayCam\\TrustedPhones";

bool loadTrustedPhone(const std::wstring& host, Digest& token) {
    std::array<BYTE, 1024> storage{};
    DWORD bytes = storage.size();
    if (RegGetValueW(HKEY_CURRENT_USER, trustRegistry, host.c_str(), RRF_RT_REG_BINARY,
            nullptr, storage.data(), &bytes) != ERROR_SUCCESS) return false;
    DATA_BLOB input{bytes, storage.data()}, output{};
    if (!CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr,
            CRYPTPROTECT_UI_FORBIDDEN, &output)) return false;
    bool valid = output.cbData == token.size();
    if (valid) std::memcpy(token.data(), output.pbData, token.size());
    SecureZeroMemory(output.pbData, output.cbData);
    LocalFree(output.pbData);
    return valid;
}

bool saveTrustedPhone(const std::wstring& host, Digest& token) {
    DATA_BLOB input{static_cast<DWORD>(token.size()), token.data()}, output{};
    if (!CryptProtectData(&input, L"RelayCam trusted phone", nullptr, nullptr, nullptr,
            CRYPTPROTECT_UI_FORBIDDEN, &output)) return false;
    LONG result = RegSetKeyValueW(HKEY_CURRENT_USER, trustRegistry, host.c_str(), REG_BINARY,
        output.pbData, output.cbData);
    LocalFree(output.pbData);
    return result == ERROR_SUCCESS;
}

bool pairingProof(const Digest& key, const Digest& session, const char* role, Digest& proof) {
    std::array<uint8_t, 6 + 32> material{};
    auto next = std::copy_n(role, 6, material.begin());
    std::copy(session.begin(), session.end(), next);
    return sha256(material.data(), material.size(), proof, &key);
}

bool matchesProof(const Digest& a, const Digest& b) {
    uint8_t difference = 0;
    for (size_t i = 0; i < a.size(); ++i) difference |= a[i] ^ b[i];
    return difference == 0;
}

struct Pairing {
    struct Blob {
        BCRYPT_ECCKEY_BLOB header{BCRYPT_ECDH_PUBLIC_P256_MAGIC, 32};
        PublicKey coordinates{};
    };
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_KEY_HANDLE local = nullptr;
    BCRYPT_KEY_HANDLE remote = nullptr;
    BCRYPT_SECRET_HANDLE agreement = nullptr;
    PublicKey clientPublic{};

    ~Pairing() {
        if (agreement) BCryptDestroySecret(agreement);
        if (remote) BCryptDestroyKey(remote);
        if (local) BCryptDestroyKey(local);
        if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
    }

    bool generate() {
        Blob output;
        ULONG actual = 0;
        if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_ECDH_P256_ALGORITHM, nullptr, 0) < 0 ||
            BCryptGenerateKeyPair(algorithm, &local, 256, 0) < 0 ||
            BCryptFinalizeKeyPair(local, 0) < 0 ||
            BCryptExportKey(local, nullptr, BCRYPT_ECCPUBLIC_BLOB, reinterpret_cast<PUCHAR>(&output),
                sizeof(output), &actual, 0) < 0 || actual != sizeof(output)) return false;
        clientPublic = output.coordinates;
        return true;
    }

    bool finish(const PublicKey& serverPublic, Digest& display, Digest& videoKey, Digest& trustKey) {
        Blob input;
        input.coordinates = serverPublic;
        Digest secret{};
        ULONG actual = 0;
        bool ok = BCryptImportKeyPair(algorithm, nullptr, BCRYPT_ECCPUBLIC_BLOB, &remote,
                reinterpret_cast<PUCHAR>(&input), sizeof(input), 0) >= 0 &&
            BCryptSecretAgreement(local, remote, &agreement, 0) >= 0 &&
            BCryptDeriveKey(agreement, BCRYPT_KDF_RAW_SECRET, nullptr, secret.data(),
                secret.size(), &actual, 0) >= 0 && actual == secret.size();
        if (ok) {
            // CNG returns the raw ECDH secret in little-endian order.
            std::reverse(secret.begin(), secret.end());
            ok = derive("RelayCam2 display", secret, serverPublic, display) &&
                derive("RelayCam2 video", secret, serverPublic, videoKey) &&
                derive("RelayCam3 trust", secret, serverPublic, trustKey);
        }
        SecureZeroMemory(secret.data(), secret.size());
        return ok;
    }

    bool derive(const char* label, const Digest& secret, const PublicKey& serverPublic, Digest& out) {
        const size_t length = std::strlen(label);
        std::array<uint8_t, 17 + 32 + 64 + 64> material{};
        if (length > 17) return false;
        auto next = std::copy_n(label, length, material.begin());
        next = std::copy(secret.begin(), secret.end(), next);
        next = std::copy(serverPublic.begin(), serverPublic.end(), next);
        next = std::copy(clientPublic.begin(), clientPublic.end(), next);
        bool ok = sha256(material.data(), static_cast<ULONG>(next - material.begin()), out);
        SecureZeroMemory(material.data(), material.size());
        return ok;
    }
};

struct Cipher {
    BCRYPT_ALG_HANDLE aes = nullptr;
    BCRYPT_KEY_HANDLE key = nullptr;
    std::vector<uint8_t> keyObject;
    ~Cipher() {
        if (key) BCryptDestroyKey(key);
        if (aes) BCryptCloseAlgorithmProvider(aes, 0);
    }

    bool setup(Digest& secret) {
        ULONG objectSize = 0, received = 0;
        if (BCryptOpenAlgorithmProvider(&aes, BCRYPT_AES_ALGORITHM, nullptr, 0) < 0 ||
            BCryptSetProperty(aes, BCRYPT_CHAINING_MODE,
                reinterpret_cast<PUCHAR>(const_cast<wchar_t*>(BCRYPT_CHAIN_MODE_GCM)),
                sizeof(BCRYPT_CHAIN_MODE_GCM), 0) < 0 ||
            BCryptGetProperty(aes, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objectSize),
                sizeof(objectSize), &received, 0) < 0) return false;
        keyObject.resize(objectSize);
        bool ok = BCryptGenerateSymmetricKey(aes, &key, keyObject.data(), objectSize,
            secret.data(), secret.size(), 0) >= 0;
        return ok;
    }

    bool decrypt(uint64_t number, std::vector<uint8_t>& packet, uint8_t* output) {
        uint8_t nonce[12]{};
        for (int i = 11; i >= 4; --i) {
            nonce[i] = static_cast<uint8_t>(number);
            number >>= 8;
        }
        ULONG length = static_cast<ULONG>(packet.size() - 16);
        BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO info{};
        BCRYPT_INIT_AUTH_MODE_INFO(info);
        info.pbNonce = nonce;
        info.cbNonce = sizeof(nonce);
        info.pbTag = packet.data() + length;
        info.cbTag = 16;
        ULONG actual = 0;
        return BCryptDecrypt(key, packet.data(), length, &info, nullptr, 0,
            output, length, &actual, 0) >= 0 && actual == length;
    }
};

struct Decoder {
    AVCodecContext* context = nullptr;
    AVFrame* picture = nullptr;
    AVPacket* packet = nullptr;
    ~Decoder() {
        av_packet_free(&packet);
        av_frame_free(&picture);
        avcodec_free_context(&context);
    }

    bool open() {
        const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        if (!codec) return false;
        context = avcodec_alloc_context3(codec);
        picture = av_frame_alloc();
        packet = av_packet_alloc();
        if (!context || !picture || !packet) return false;
        context->thread_count = 3;
        // Hardware phone encoders commonly emit a single slice, so parallelize across frames.
        context->thread_type = FF_THREAD_FRAME;
        context->max_pixels = 4096 * 2304;
        return avcodec_open2(context, codec, nullptr) >= 0;
    }

    bool frame(SharedFrame* frames, HANDLE mutex, VideoMode mode, bool flip, unsigned& published) {
        if (picture->width != mode.width || picture->height != mode.height ||
            (picture->format != AV_PIX_FMT_YUV420P && picture->format != AV_PIX_FMT_YUVJ420P)) return false;
        // The virtual camera and preview both interpret the shared YUV as limited range.
        const bool fullRange = picture->color_range == AVCOL_RANGE_JPEG ||
            (picture->color_range == AVCOL_RANGE_UNSPECIFIED && picture->format == AV_PIX_FMT_YUVJ420P);
        if (!acquire(mutex, 12)) return true;
        int w = mode.width, h = mode.height;
        uint8_t* dest = frames->i420;
        for (int plane = 0; plane < 3; ++plane) {
            const VideoLevelTable* levels = fullRange ? (plane == 0 ? &limitedLuma : &limitedChroma) : nullptr;
            copyVideoPlane(dest, picture->data[plane], picture->linesize[plane], w, h, flip, levels);
            dest += w * h;
            if (plane == 0) { w /= 2; h /= 2; }
        }
        frames->width = mode.width;
        frames->height = mode.height;
        frames->fps = mode.fps;
        frames->updated = GetTickCount64();
        ++frames->sequence;
        ++published;
        ReleaseMutex(mutex);
        return true;
    }

    bool push(SharedFrame* frames, HANDLE mutex, VideoMode mode, bool flip, unsigned& decoded, unsigned& published) {
        int status = avcodec_send_packet(context, packet);
        av_packet_unref(packet);
        if (status < 0) return status == AVERROR_INVALIDDATA;
        while ((status = avcodec_receive_frame(context, picture)) >= 0) {
            if (!frame(frames, mutex, mode, flip, published)) return false;
            ++decoded;
            av_frame_unref(picture);
        }
        return status == AVERROR(EAGAIN) || status == AVERROR_INVALIDDATA;
    }
};

bool waitConnect(SOCKET socket, const sockaddr_in& address, std::atomic<bool>& cancel) {
    u_long nonBlocking = 1;
    if (ioctlsocket(socket, FIONBIO, &nonBlocking) != 0) return false;
    if (connect(socket, reinterpret_cast<const sockaddr*>(&address), sizeof(address)) != 0) {
        if (WSAGetLastError() != WSAEWOULDBLOCK) return false;
        for (int attempt = 0; attempt < 32; ++attempt) {
            if (cancel.load()) return false;
            WSAPOLLFD poll{socket, POLLWRNORM, 0};
            int rc = WSAPoll(&poll, 1, 125);
            if (rc < 0) return false;
            if (rc > 0) {
                int error = 0, size = sizeof(error);
                if (getsockopt(socket, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&error), &size) != 0 ||
                    error != 0 || !(poll.revents & POLLWRNORM)) return false;
                break;
            }
            if (attempt == 31) return false;
        }
    }
    u_long blocking = 0;
    const DWORD timeout = 400;
    return ioctlsocket(socket, FIONBIO, &blocking) == 0 &&
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout)) == 0 &&
        setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout)) == 0;
}

bool socketCopy(SOCKET socket, uint8_t* output, size_t length, bool sendData,
        std::atomic<bool>& cancel, ULONGLONG timeout = 7000) {
    ULONGLONG deadline = GetTickCount64() + timeout;
    while (length && !cancel.load()) {
        int count = sendData ? send(socket, reinterpret_cast<char*>(output), static_cast<int>(length), 0) :
            recv(socket, reinterpret_cast<char*>(output), static_cast<int>(length), 0);
        if (count < 0) {
            if (WSAGetLastError() == WSAETIMEDOUT && GetTickCount64() < deadline) continue;
            return false;
        }
        if (count == 0 || GetTickCount64() >= deadline) return false;
        length -= count;
        output += count;
    }
    return length == 0;
}

uint32_t bigInt(const uint8_t* data) {
    return (static_cast<uint32_t>(data[0]) << 24) | (static_cast<uint32_t>(data[1]) << 16) |
        (static_cast<uint32_t>(data[2]) << 8) | data[3];
}

StreamStatus receive(const std::wstring& host, SharedFrame* frames, HANDLE mutex,
        HWND window, Connection& connection) {
    auto& cancel = connection.cancel;
    NetworkRuntime network;
    if (!network.ready) return Unreachable;
    sockaddr_in phone{};
    phone.sin_family = AF_INET;
    phone.sin_port = htons(47477);
    if (InetPtonW(AF_INET, host.c_str(), &phone.sin_addr) != 1) return InvalidAddress;
    Peer peer;
    peer.socket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (peer.socket == INVALID_SOCKET || !waitConnect(peer.socket, phone, cancel)) return Unreachable;
    auto read = [&](uint8_t* data, size_t count, ULONGLONG timeout = 7000) {
        return socketCopy(peer.socket, data, count, false, cancel, timeout);
    };

    uint8_t magic[4]{};
    if (!read(magic, sizeof(magic))) return InvalidStream;
    if (bigInt(magic) == 0x52434D31) return OldAndroid;
    bool v3 = bigInt(magic) == 0x52434D33;
    if (!v3 && bigInt(magic) != 0x52434D32) return InvalidStream;
    if (!v3 && connection.trustedOnly) return TrustRequired;
    Digest commitment{};
    if (!read(commitment.data(), commitment.size())) return InvalidStream;

    Pairing pairing;
    if (!pairing.generate()) return CryptoError;
    if (!socketCopy(peer.socket, pairing.clientPublic.data(), pairing.clientPublic.size(), true, cancel))
        return Disconnected;
    PublicKey serverPublic{};
    Digest actualCommitment{};
    if (!read(serverPublic.data(), serverPublic.size()) ||
        !sha256(serverPublic.data(), serverPublic.size(), actualCommitment) ||
        commitment != actualCommitment) return InvalidStream;

    Digest display{}, videoKey{}, trustKey{};
    Cipher cipher;
    bool ready = pairing.finish(serverPublic, display, videoKey, trustKey) && cipher.setup(videoKey);
    SecureZeroMemory(videoKey.data(), videoKey.size());
    if (!ready) return CryptoError;
    Digest saved{};
    bool hasSaved = false;
    if (v3) {
        std::array<uint8_t, 65> request{};
        request[0] = connection.trustedOnly ? 2 : connection.remember ? 1 : 0;
        hasSaved = connection.remember && loadTrustedPhone(host, saved);
        if (connection.trustedOnly && !hasSaved) return TrustRequired;
        if (hasSaved) {
            Digest id{}, proof{};
            if (!sha256(saved.data(), saved.size(), id) || !pairingProof(saved, trustKey, "client", proof))
                return CryptoError;
            std::copy(id.begin(), id.end(), request.begin() + 1);
            std::copy(proof.begin(), proof.end(), request.begin() + 33);
        }
        if (!socketCopy(peer.socket, request.data(), request.size(), true, cancel)) return Disconnected;
    }
    if (!connection.trustedOnly)
        PostMessageW(window, statusMessage, AwaitingApproval, bigInt(display.data()) % 1000000);
    uint8_t accepted = 0;
    if (!read(&accepted, 1, connection.trustedOnly ? 2500 : 45000))
        return connection.trustedOnly ? TrustRequired : Disconnected;
    if (accepted == 2 && v3 && hasSaved) {
        Digest serverProof{}, expected{};
        if (!read(serverProof.data(), serverProof.size()) ||
                !pairingProof(saved, trustKey, "server", expected) || !matchesProof(serverProof, expected))
            return TrustRequired;
    } else if (connection.trustedOnly) {
        return TrustRequired;
    } else if (accepted == 0) {
        return PairingDeclined;
    } else if (accepted != 1 && !(v3 && accepted == 3 && connection.remember)) {
        return InvalidStream;
    }
    SecureZeroMemory(saved.data(), saved.size());
    PostMessageW(window, statusMessage, Approved, 0);

    uint8_t size[4]{};
    std::vector<uint8_t> encrypted;
    if (!read(size, sizeof(size)) || bigInt(size) != 28) return InvalidStream;
    encrypted.resize(28);
    uint8_t header[12]{};
    if (!read(encrypted.data(), encrypted.size())) return Disconnected;
    if (!cipher.decrypt(0, encrypted, header)) return InvalidStream;
    VideoMode mode{static_cast<int>(bigInt(header)), static_cast<int>(bigInt(header + 4)),
        static_cast<int>(bigInt(header + 8))};
    if (!knownMode(mode.width, mode.height, mode.fps)) return InvalidStream;
    if (accepted == 3) saveTrustedPhone(host, trustKey);
    SecureZeroMemory(trustKey.data(), trustKey.size());

    Decoder decoder;
    if (!decoder.open()) return DecoderError;
    unsigned decoded = 0, published = 0, received = 0;
    ULONGLONG rateStarted = GetTickCount64();
    ULONGLONG deadline = GetTickCount64() + 14000;
    for (uint64_t n = 1; !cancel.load(); ++n) {
        if (!read(size, sizeof(size))) return Disconnected;
        uint32_t length = bigInt(size);
        if (length <= 16 || length > maxPacket) return InvalidStream;
        encrypted.resize(length);
        if (!read(encrypted.data(), length)) return Disconnected;
        if (av_new_packet(decoder.packet, length - 16) < 0) return DecoderError;
        if (!cipher.decrypt(n, encrypted, decoder.packet->data)) return InvalidStream;
        if (v3 && decoder.packet->size == 16 && bigInt(decoder.packet->data) == 0x52434D43) {
            const auto* change = decoder.packet->data + 4;
            VideoMode requested{static_cast<int>(bigInt(change)), static_cast<int>(bigInt(change + 4)),
                static_cast<int>(bigInt(change + 8))};
            if (!knownMode(requested.width, requested.height, requested.fps)) return InvalidStream;
            av_packet_unref(decoder.packet);
            avcodec_flush_buffers(decoder.context);
            mode = requested;
            deadline = GetTickCount64() + 7000;
            continue;
        }
        ++received;
        const unsigned before = decoded;
        if (!decoder.push(frames, mutex, mode, connection.flipHorizontal.load(), decoded, published)) return DecoderError;
        if (decoded && !before) {
            PostMessageW(window, statusMessage, Connected,
                static_cast<LPARAM>((mode.height << 8) | mode.fps));
        }
        if (decoded == before && GetTickCount64() > deadline) return DecoderError;
        if (decoded != before) deadline = GetTickCount64() + 7000;
        ULONGLONG now = GetTickCount64();
        if (decoded && now - rateStarted >= 1000) {
            auto fps = [elapsed = now - rateStarted](unsigned count) {
                return std::min<ULONGLONG>((1000ULL * count + elapsed / 2) / elapsed, 255);
            };
            PostMessageW(window, statusMessage, StreamRate,
                static_cast<LPARAM>((mode.height << 16) | (fps(received) << 8) | fps(published)));
            rateStarted = now;
            received = published = 0;
        }
    }
    return Cancelled;
}
}

bool hasTrustedPhone(const std::wstring& host) {
    Digest token{};
    bool found = loadTrustedPhone(host, token);
    SecureZeroMemory(token.data(), token.size());
    return found;
}

void forgetTrustedPhone(const std::wstring& host) {
    RegDeleteKeyValueW(HKEY_CURRENT_USER, trustRegistry, host.c_str());
}

void Connection::run(const std::wstring& host, SharedFrame* frames,
        HANDLE mutex, HWND window) {
    StreamStatus status = DecoderError;
    try { status = receive(host, frames, mutex, window, *this); }
    catch (...) { status = DecoderError; }
    if (frames && acquire(mutex, 100)) {
        frames->updated = 0;
        ReleaseMutex(mutex);
    }
    PostMessageW(window, statusMessage, cancel.load() ? Cancelled : status, 0);
}
