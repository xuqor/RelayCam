#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

using VideoLevelTable = std::array<uint8_t, 256>;

constexpr VideoLevelTable limitedVideoLevels(int span) {
    VideoLevelTable levels{};
    for (int value = 0; value < 256; ++value)
        levels[value] = static_cast<uint8_t>(16 + (value * span + 127) / 255);
    return levels;
}

inline constexpr auto limitedLuma = limitedVideoLevels(219);
inline constexpr auto limitedChroma = limitedVideoLevels(224);

inline void copyVideoPlane(uint8_t* dest, const uint8_t* source, std::ptrdiff_t stride,
        int width, int height, bool flip, const VideoLevelTable* levels = nullptr) {
    for (int y = 0; y < height; ++y) {
        const auto* row = source + y * stride;
        auto* out = dest + y * width;
        if (levels) {
            if (flip) {
                for (int x = 0; x < width; ++x) out[x] = (*levels)[row[width - 1 - x]];
            } else {
                for (int x = 0; x < width; ++x) out[x] = (*levels)[row[x]];
            }
        } else if (flip) std::reverse_copy(row, row + width, out);
        else std::memcpy(out, row, width);
    }
}
