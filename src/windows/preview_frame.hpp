#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

struct PreviewSize {
    int width, height;
};

inline PreviewSize fitPreview(int width, int height, int boundW, int boundH) {
    if (width < 1 || height < 1 || boundW < 1 || boundH < 1) return {};
    boundW = std::min(width, boundW);
    boundH = std::min(height, boundH);
    if (static_cast<int64_t>(boundW) * height <= static_cast<int64_t>(boundH) * width)
        return {boundW, std::max(1, boundW * height / width)};
    return {std::max(1, boundH * width / height), boundH};
}

class PreviewFrame {
    int width = 0, height = 0;
    PreviewSize size{};
    std::vector<int> columns;

public:
    std::vector<uint32_t> pixels;

    void render(const uint8_t* yuv, int w, int h, PreviewSize output) {
        if (width != w || height != h || size.width != output.width || size.height != output.height) {
            width = w;
            height = h;
            size = output;
            columns.resize(size.width);
            for (int col = 0; col < size.width; ++col) columns[col] = col * w / size.width;
            pixels.resize(size.width * size.height);
        }

        const uint8_t* uPlane = yuv + w * h;
        const uint8_t* vPlane = uPlane + w * h / 4;
        auto channel = [](int value) {
            return static_cast<uint32_t>(std::clamp(value >> 8, 0, 255));
        };
        for (int row = 0; row < size.height; ++row) {
            int y = row * h / size.height;
            const auto* yRow = yuv + y * w;
            const auto* uRow = uPlane + (y / 2) * (w / 2);
            const auto* vRow = vPlane + (y / 2) * (w / 2);
            auto* out = pixels.data() + row * size.width;
            for (int col = 0; col < size.width; ++col) {
                const int x = columns[col];
                const int luma = 298 * (yRow[x] - 16);
                const int u = uRow[x / 2] - 128, v = vRow[x / 2] - 128;
                out[col] = channel(luma + 516 * u + 128) |
                    channel(luma - 100 * u - 208 * v + 128) << 8 |
                    channel(luma + 409 * v + 128) << 16;
            }
        }
    }
};
