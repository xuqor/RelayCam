# RelayCam Android 0.3.2 / Windows 0.3.1

RelayCam is a free, local Wi-Fi phone webcam. The Android app sends H.264
video over an encrypted connection. The Windows app decodes it and serves it
to desktop apps through a DirectShow virtual camera with a configurable name.

## Setup

Use version 0.3.0 or later on both devices for trusted connections and live camera switching.
Android 0.3.x uses a different signing key from 0.2.x. If Android rejects an update from an older
build, uninstall the old app first, then install this APK and pair again.

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

## Resolution and frame rate

Choose the camera, then the resolution and either **30 fps** or **60 fps**.
Unavailable resolutions remain visible, and unsupported frame-rate buttons
are disabled. 4K UHD requires a 3840 × 2160 camera output and an H.264 encoder
that supports the selected frame rate. On compatible Android 10+ devices,
RelayCam can use a supported high-speed camera session and cap the encoder
input at 60 fps when the regular camera session cannot reach 60. Use the
live FPS readout to check actual throughput.

## Trusted devices and background streaming

On Windows, check **Remember and auto-connect** before pairing. Verify the
six-digit code and choose **Allow and remember** on Android. Windows then
reconnects to a trusted phone at its saved IP whenever RelayCam is running
on both devices. If the phone IP changes, enter the new IP and pair again.
**Forget phone** removes the credential for the IP shown on Windows.
**Forget trusted PCs** on Android revokes the phone's saved approvals.

Minimize the Windows app to the notification area. Double-click the tray
icon to reopen it, or right-click it to exit.

Start the camera with RelayCam visible on Android. It can keep streaming
while minimized, with a foreground notification that includes **Stop camera**.
Use **Switch front / rear** during a stream to change lenses. RelayCam keeps
the current mode where supported and can fall back to 720p or 480p at 30 fps.
The Android interface rotates with the phone. This controls the interface
orientation separately from the video.

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

## Battery and bitrate

Android dims the RelayCam window after five seconds of live video. Tap the
screen to restore brightness. It dims again after five seconds without a
touch. Stopping video or leaving the app restores the previous setting.

Choose **Video bitrate** before tapping **Start camera**. Auto targets
4 Mbps at 720p/30 and scales with the capture mode. The phone's encoder
may clamp the request to its supported range.

The phone batches encrypted frames in a 128 KiB output buffer, with a short
eight-millisecond batching window. The frame protocol is unchanged.

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
