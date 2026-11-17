package dev.relaycam.android;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class CaptureModes {
    static final Size[] SIZES = {new Size(1280, 720), new Size(1920, 1080),
        new Size(2048, 1080), new Size(2560, 1440), new Size(3840, 2160), new Size(640, 480)};
    private static final String AVC = "video/avc";

    static List<MainActivity.Mode> forCamera(CameraCharacteristics camera) {
        ArrayList<MainActivity.Mode> modes = new ArrayList<>();
        StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return modes;
        Range<Integer>[] ranges = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        Size[] codecSizes = map.getOutputSizes(MediaCodec.class);
        Size[] privateSizes = map.getOutputSizes(ImageFormat.PRIVATE);
        Size[] fastSizes = map.getHighSpeedVideoSizes();
        for (Size size : SIZES) {
            boolean direct = contains(codecSizes, size);
            boolean privateOutput = contains(privateSizes, size);
            long duration = direct ? map.getOutputMinFrameDuration(MediaCodec.class, size) :
                privateOutput ? map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size) : Long.MAX_VALUE;
            for (int fps : new int[]{30, 60}) {
                if (size.getHeight() == 480 && fps == 60) continue;
                Range<Integer> regular = null, dim = null;
                if ((direct || privateOutput) && duration <= 1_020_000_000.0 / fps && ranges != null) {
                    for (Range<Integer> range : ranges) {
                        boolean capped = Build.VERSION.SDK_INT >= 29 && range.contains(fps);
                        if (range.getUpper() != fps && !capped) continue;
                        if (regular == null || range.getUpper() < regular.getUpper() ||
                                (range.getUpper().equals(regular.getUpper()) && range.getLower() > regular.getLower()))
                            regular = range;
                        if (range.getUpper() == fps && range.getLower() >= 15 &&
                                (dim == null || range.getLower() < dim.getLower())) dim = range;
                    }
                }
                Range<Integer> highSpeed = null;
                if (regular == null && fps == 60 && Build.VERSION.SDK_INT >= 29 && contains(fastSizes, size)) {
                    for (Range<Integer> range : map.getHighSpeedVideoFpsRangesFor(size)) {
                        int rate = range.getUpper();
                        if (!range.getLower().equals(rate) || rate < fps || rate % fps != 0) continue;
                        if (highSpeed == null || rate < highSpeed.getUpper()) highSpeed = range;
                    }
                }
                Range<Integer> selected = regular != null ? regular : highSpeed;
                if (selected == null) continue;
                String codec = selectEncoder(codecs, size, fps, highSpeed != null);
                if (codec != null) modes.add(new MainActivity.Mode(size, fps, selected,
                    selected.equals(dim) ? null : dim, codec, highSpeed != null));
            }
        }
        return modes;
    }

    private static boolean contains(Size[] sizes, Size wanted) {
        return sizes != null && Arrays.asList(sizes).contains(wanted);
    }

    private static String selectEncoder(MediaCodecInfo[] codecs, Size size, int fps, boolean hardwareOnly) {
        String software = null;
        for (MediaCodecInfo codec : codecs) {
            if (!codec.isEncoder()) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(AVC);
                boolean surface = false;
                for (int color : caps.colorFormats)
                    if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                if (!surface || !caps.getVideoCapabilities().areSizeAndRateSupported(
                        size.getWidth(), size.getHeight(), fps)) continue;
                if (isHardware(codec)) return codec.getName();
                if (!hardwareOnly && software == null) software = codec.getName();
            } catch (IllegalArgumentException ignored) {}
        }
        return software;
    }

    private static boolean isHardware(MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= 29) return codec.isHardwareAccelerated();
        String name = codec.getName().toLowerCase(Locale.ROOT);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
                !name.startsWith("c2.google.") && !name.startsWith("omx.ffmpeg.");
    }
}
