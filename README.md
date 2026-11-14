# RelayCam 0.2.3

RelayCam is a free, local Wi-Fi phone webcam. The Android app sends H.264
video over an encrypted connection. The Windows app decodes it and serves it
to desktop apps through a DirectShow virtual camera with a configurable name.

## Setup

Use RelayCam 0.2.3 on both devices for the lighting improvements. Install the APK over the previous version.

1. Open RelayCam.exe on x64 Windows 10 or 11. Enter the name you want to see in your camera apps and click **Apply camera name**. Approve the Windows administrator prompt to register 32-bit and 64-bit camera filters.
2. Install RelayCam.apk on Android 6.0 or later and allow camera access. Connect both devices to the same local network. Choose a camera and capture mode, then tap **Start camera**.
3. Enter the displayed IPv4 address in RelayCam on Windows and click **Connect phone**. Compare the six-digit code shown on both screens. Tap **Allow** on Android if they match. Windows remembers the last IP after a successful connection. Keep RelayCam open on both devices.
4. Reopen Discord or another desktop app that accepts DirectShow cameras. In Discord, check **User Settings → Voice & Video → Camera** and select the name you set.
5. To unregister the camera, close any app that is using it and click **Remove camera** in RelayCam.

Android lists the supported combinations from its camera and H.264 encoder.
These are 1280×720, 1920×1080, 2048×1080, 2560×1440, and 3840×2160 at nominal
30 or 60 fps, where advertised. Delivery depends on exposure, encoding,
decoding and the local network. The receiving app can choose a lower format
and frame rate. A 720p/30 fps stream is a sensible starting point on older PCs.
Apps that require Media Foundation cameras exclusively may not discover RelayCam.
The Windows build is unsigned. This release streams video only.

## Check the camera orientation

Open **Preview outgoing video** in the Windows app. Point the rear camera at
some written text. If it reads backward in RelayCam, enable **Flip outgoing
video left to right**. The change applies while streaming and is remembered
on the PC. The flip applies to any selected phone camera. If text reads
normally in RelayCam and appears reversed in a calling app's self-view,
leave the outgoing feed as shown in RelayCam.

## Check video performance

Use 720p at 30 fps first on older PCs. Try 480p at 30 fps if 720p is slow
and the phone offers that mode. Android displays encoded FPS. Windows displays
received and published FPS, where published means frames delivered to the
virtual camera buffer. Calling apps can select their own stream rate. The PC
preview adapts to the window size up to 1280×720 and reads the newest frame.

## Lighting

Android applies exposure compensation while streaming on lenses that support it.
Use **Reset exposure** to return to 0 EV. **Anti-flicker** defaults to Auto
when available. Choose 50 Hz or 60 Hz to match local mains frequency if indoor
lighting produces rolling bands or pulses. **Low light** uses a wider exposure
frame-rate range where the camera offers one, and can reduce actual FPS.
Windows normalizes full-range H.264 levels for the outgoing feed and preview.

## Rebuild

From the extracted RelayCam folder on Linux, install GNU make, NASM, both
MinGW-w64 POSIX C/C++ toolchains, Java 17 or later, and Android SDK platform
35 and build tools 35.0.0. Run:

```sh
bash build-windows.sh
ANDROID_HOME=/path/to/android-sdk bash build-android.sh
```

The Windows build extracts and compiles the included FFmpeg release, builds
both filter architectures, and embeds them into the EXE. For a local Android
build, the script creates a development signing key on the first run and
uses that key for subsequent builds. Output is under `out/`.
