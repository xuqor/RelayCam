#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/{ffmpeg,wide,narrow} out
if [[ ! -d build/ffmpeg-7.1.3 ]]; then
  tar -xf ffmpeg-7.1.3.tar.xz -C build
fi
(
  cd build/ffmpeg
  ../ffmpeg-7.1.3/configure --target-os=mingw32 --arch=x86_64 \
    --cross-prefix=x86_64-w64-mingw32- --cc=x86_64-w64-mingw32-gcc-posix \
    --disable-programs --disable-doc --disable-network --disable-autodetect \
    --disable-everything --disable-avdevice --disable-swresample --disable-swscale \
    --disable-avfilter --disable-avformat --enable-decoder=h264 --enable-w32threads \
    --enable-static --disable-shared
  make -j"$(nproc)" libavcodec/libavcodec.a libavutil/libavutil.a
)
for target in x86_64 i686; do
  folder=wide
  if [[ "$target" == i686 ]]; then folder=narrow; fi
  "$target"-w64-mingw32-g++-posix -std=c++17 -O2 -s -shared -static \
    -DUNICODE -D_UNICODE -DNOMINMAX -ffunction-sections -fdata-sections \
    -Wl,--gc-sections,--kill-at -o "build/$folder/RelayCamFilter.dll" \
    src/windows/camera.cpp src/windows/dshow/source/*.cpp \
    -lole32 -loleaut32 -lstrmiids -lksuser -lwinmm -lwmcodecdspuuid -lsetupapi -lcfgmgr32 -luuid
done
i686-w64-mingw32-g++-posix -std=c++17 -O2 -s -municode -mwindows -static \
  -DUNICODE -D_UNICODE -o build/narrow/RelayCamRegister32.exe src/windows/register32.cpp -lole32
x86_64-w64-mingw32-windres --preprocessor x86_64-w64-mingw32-gcc-posix \
  --preprocessor-arg=-E --preprocessor-arg=-xc-header --preprocessor-arg=-DRC_INVOKED \
  -i src/windows/relaycam.rc -o build/relaycam-res.o
x86_64-w64-mingw32-g++-posix -std=c++17 -O2 -s -municode -mwindows -static \
  -DUNICODE -D_UNICODE -DNOMINMAX -D_WIN32_WINNT=0x0A00 \
  -Ibuild/ffmpeg-7.1.3 -Ibuild/ffmpeg -o out/RelayCam.exe src/windows/app.cpp \
  src/windows/network.cpp build/relaycam-res.o build/ffmpeg/libavcodec/libavcodec.a \
  build/ffmpeg/libavutil/libavutil.a -lws2_32 -lbcrypt -lcomctl32 -lshell32 \
  -lole32 -luuid -lgdi32 -lpsapi -lcrypt32 -lm
