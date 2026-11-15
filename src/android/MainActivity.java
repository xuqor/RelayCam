package dev.relaycam.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final String AVC = "video/avc";
    private final ArrayList<Mode> modes = new ArrayList<>();
    private final ArrayList<String> cameraIds = new ArrayList<>();
    private Spinner cameras;
    private Spinner profiles;
    private TextView address;
    private TextView status;
    private TextView exposureLabel;
    private SeekBar exposure;
    private Spinner antiFlicker;
    private CheckBox lowLight;
    private int exposureFloor;
    private double exposureStep;
    private final ArrayList<Integer> flickerModes = new ArrayList<>();
    private boolean loadingLighting;
    private Button action;
    private CameraService.Session running;
    private boolean starting;
    private Button switchCamera;
    private Button forgetPcs;
    private final ArrayList<Integer> cameraFacing = new ArrayList<>();
    private Spinner bitrate;
    private final Handler brightnessHandler = new Handler(Looper.getMainLooper());
    private static final long DIM_DELAY_MS = 5000;
    private float originalBrightness;
    private boolean screenDimmed;
    private boolean videoActive;
    private boolean wakeTouch;
    private final Runnable dimScreen = () -> {
        if (!videoActive || running == null || screenDimmed || !hasWindowFocus()) return;
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        originalBrightness = attributes.screenBrightness;
        attributes.screenBrightness = 0.01f;
        getWindow().setAttributes(attributes);
        screenDimmed = true;
    };

    static final class Mode {
        final Size size;
        final int fps;
        final Range<Integer> range, lowLightRange;
        final String codec;

        Mode(Size size, int fps, Range<Integer> range, Range<Integer> lowLightRange, String codec) {
            this.size = size;
            this.fps = fps;
            this.range = range;
            this.lowLightRange = lowLightRange;
            this.codec = codec;
        }

        @Override public String toString() {
            String label = size.getWidth() == 2048 ? "2K" :
                    (size.getHeight() == 1440 ? "1440p (QHD)" :
                    (size.getHeight() == 2160 ? "4K" : size.getHeight() + "p"));
            return label + "  ·  " + fps + " fps";
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getDecorView().getWindowInsetsController() != null) {
            getWindow().getDecorView().getWindowInsetsController().hide(WindowInsets.Type.statusBars());
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(36), dp(28), dp(36), dp(28));
        panel.setBackgroundColor(Color.rgb(20, 23, 30));
        scroll.addView(panel);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() |
                    WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(scroll);
        label(panel, "RelayCam", 30);
        address = label(panel, "Connect both devices to the same Wi-Fi.", 16);
        label(panel, "Camera", 14);
        cameras = new Spinner(this);
        panel.addView(cameras, row());
        switchCamera = new Button(this);
        switchCamera.setAllCaps(false);
        switchCamera.setText("Switch front / rear");
        switchCamera.setVisibility(View.GONE);
        panel.addView(switchCamera, row());
        switchCamera.setOnClickListener(v -> switchLens());
        label(panel, "Resolution and frame rate", 14);
        profiles = new Spinner(this);
        panel.addView(profiles, row());
        label(panel, "Video bitrate", 14);
        bitrate = new Spinner(this);
        panel.addView(bitrate, row());
        bitrate.setEnabled(false);
        action = new Button(this);
        action.setAllCaps(false);
        action.setText("Start camera");
        action.setEnabled(false);
        panel.addView(action, row());
        status = label(panel, "Streaming can continue in the background.", 15);
        exposureLabel = label(panel, "Exposure", 14);
        exposure = new SeekBar(this);
        panel.addView(exposure, row());
        Button resetExposure = new Button(this);
        resetExposure.setAllCaps(false);
        resetExposure.setText("Reset exposure");
        panel.addView(resetExposure, row());
        resetExposure.setOnClickListener(v -> exposure.setProgress(-exposureFloor));
        exposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                updateExposureLabel();
                refreshLighting();
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        label(panel, "Anti-flicker", 14);
        antiFlicker = new Spinner(this);
        panel.addView(antiFlicker, row());
        antiFlicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshLighting();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        lowLight = new CheckBox(this);
        lowLight.setText("Low light (allows a lower frame rate)");
        panel.addView(lowLight, row());
        lowLight.setOnCheckedChangeListener((button, checked) -> refreshLighting());
        exposure.setEnabled(false);
        antiFlicker.setEnabled(false);
        lowLight.setEnabled(false);
        action.setOnClickListener(v -> {
            if (starting) return;
            if (running != null) {
                CameraService service = CameraService.instance;
                if (service != null) service.stopCamera("Camera stopped.");
            } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            } else {
                start();
            }
        });

        forgetPcs = new Button(this);
        forgetPcs.setAllCaps(false);
        forgetPcs.setText("Forget trusted PCs");
        panel.addView(forgetPcs, row());
        forgetPcs.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
            .setMessage("Require approval the next time a PC connects?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget PCs", (dialog, which) -> {
                CameraService.trustedDevices(this).edit().clear().apply();
                android.widget.Toast.makeText(this, "Trusted PCs forgotten", android.widget.Toast.LENGTH_SHORT).show();
            }).show());

        ArrayList<String> names = new ArrayList<>();
        try {
            CameraManager manager = getSystemService(CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                String name = facing == null ? "Camera" :
                        (facing == CameraCharacteristics.LENS_FACING_BACK ? "Rear camera" :
                        (facing == CameraCharacteristics.LENS_FACING_FRONT ? "Front camera" : "External camera"));
                names.add(name + " (" + id + ")");
                cameraIds.add(id);
                cameraFacing.add(facing == null ? -1 : facing);
            }
        } catch (CameraAccessException e) {
            status.setText("Could not list cameras: " + e.getMessage());
        }

        cameras.setAdapter(adapter(names));
        profiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateLowLightAvailability();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { updateLowLightAvailability(); }
        });
        cameras.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (running == null) loadModes(cameraIds.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private <T> ArrayAdapter<T> adapter(List<T> list) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(-1, dp(44));
    }

    private TextView label(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(Color.rgb(233, 236, 245));
        text.setTextSize(size);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        parent.addView(text, p);
        return text;
    }

    private void loadLighting(CameraCharacteristics camera) {
        loadingLighting = true;
        Range<Integer> range = camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        android.util.Rational step = camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        exposureFloor = range == null ? 0 : range.getLower();
        exposureStep = step == null ? 0 : step.doubleValue();
        exposure.setMax(range == null ? 0 : range.getUpper() - exposureFloor);
        exposure.setProgress(-exposureFloor);
        exposure.setEnabled(exposure.getMax() > 0);
        updateExposureLabel();

        flickerModes.clear();
        flickerModes.add(-1);
        ArrayList<String> labels = new ArrayList<>();
        labels.add("Camera default");
        int[] supported = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES);
        if (supported != null) {
            for (int mode : new int[]{CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ}) {
                for (int value : supported) {
                    if (value != mode) continue;
                    flickerModes.add(mode);
                    labels.add(mode == CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO ? "Auto" :
                            mode == CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ ? "50 Hz" : "60 Hz");
                    break;
                }
            }
        }
        antiFlicker.setAdapter(adapter(labels));
        int auto = flickerModes.indexOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        antiFlicker.setSelection(Math.max(0, auto));
        antiFlicker.setEnabled(flickerModes.size() > 1);
        lowLight.setChecked(false);
        loadingLighting = false;
    }

    private void updateExposureLabel() {
        exposureLabel.setText(exposure.getMax() == 0 ? "Exposure compensation unavailable" :
                String.format(Locale.ROOT, "Exposure %+.1f EV", exposureValue() * exposureStep));
    }

    private int exposureValue() {
        return exposure.getProgress() + exposureFloor;
    }

    private int flickerValue() {
        int i = antiFlicker.getSelectedItemPosition();
        return i >= 0 && i < flickerModes.size() ? flickerModes.get(i) : -1;
    }

    private void refreshLighting() {
        if (!loadingLighting && running != null)
            running.updateLighting(exposureValue(), flickerValue(), lowLight.isChecked());
    }

    private void updateLowLightAvailability() {
        Mode mode = running == null ? (Mode) profiles.getSelectedItem() : running.mode;
        lowLight.setEnabled(mode != null && mode.lowLightRange != null);
        updateBitrateChoices(mode);
    }

    private static int automaticBitrate(Mode mode) {
        // 4 Mbps at 720p30, scaled for resolution and frame rate.
        return (int) Math.min(36_000_000L,
                4_000_000L * mode.size.getWidth() * mode.size.getHeight() * mode.fps / (1280L * 720 * 30));
    }

    private void updateBitrateChoices(Mode mode) {
        ArrayList<String> labels = new ArrayList<>();
        labels.add(mode == null ? "Auto" : String.format(Locale.ROOT, "Auto (%.1f Mbps)",
                automaticBitrate(mode) / 1_000_000.0));
        for (int value : BITRATE_OPTIONS_MBPS) labels.add(value + " Mbps");
        int previous = Math.max(0, bitrate.getSelectedItemPosition());
        bitrate.setAdapter(adapter(labels));
        bitrate.setSelection(previous);
        bitrate.setEnabled(mode != null && running == null);
    }

    private static final int[] BITRATE_OPTIONS_MBPS = {1, 2, 4, 6, 8, 12, 20, 30, 36};

    private void scheduleDim() {
        brightnessHandler.removeCallbacks(dimScreen);
        if (videoActive && hasWindowFocus()) brightnessHandler.postDelayed(dimScreen, DIM_DELAY_MS);
    }

    private void restoreBrightness() {
        brightnessHandler.removeCallbacks(dimScreen);
        if (!screenDimmed) return;
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = originalBrightness;
        getWindow().setAttributes(attributes);
        screenDimmed = false;
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            wakeTouch = screenDimmed;
            restoreBrightness();
            scheduleDim();
        }
        if (wakeTouch) {
            if (action == android.view.MotionEvent.ACTION_UP ||
                    action == android.view.MotionEvent.ACTION_CANCEL) wakeTouch = false;
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) scheduleDim();
        else restoreBrightness();
    }

    private void loadModes(String cameraId) {
        modes.clear();
        try {
            CameraCharacteristics camera = getSystemService(CameraManager.class)
                    .getCameraCharacteristics(cameraId);
            loadLighting(camera);
            StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Range<Integer>[] ranges = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            if (map != null && ranges != null && map.getOutputSizes(MediaCodec.class) != null) {
                List<Size> supported = Arrays.asList(map.getOutputSizes(MediaCodec.class));
                for (Size size : new Size[]{new Size(1280,720), new Size(1920,1080),
                        new Size(2048,1080), new Size(2560,1440), new Size(3840,2160), new Size(640,480)}) {
                    if (!supported.contains(size)) continue;
                    long minFrameTime = map.getOutputMinFrameDuration(MediaCodec.class, size);
                    for (int fps : (size.getHeight() == 480 ? new int[]{30} : new int[]{30, 60})) {
                        if (minFrameTime > 0 && minFrameTime > 1_000_000_000.0 / fps * 1.015) continue;
                        Range<Integer> preferred = null;
                        Range<Integer> darker = null;
                        for (Range<Integer> range : ranges) {
                            if (range.getUpper() == fps &&
                                    (preferred == null || range.getLower() > preferred.getLower())) {
                                preferred = range;
                            }
                            if (range.getUpper() == fps && range.getLower() >= 15 &&
                                    (darker == null || range.getLower() < darker.getLower())) darker = range;
                        }
                        if (preferred == null) continue;
                        if (preferred.equals(darker)) darker = null;
                        String selectedEncoder = null;
                        for (MediaCodecInfo codec : codecs) {
                            if (!codec.isEncoder()) continue;
                            try {
                                MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(AVC);
                                boolean surface = false;
                                for (int format : caps.colorFormats) {
                                    if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                                }
                                if (surface && caps.getVideoCapabilities().areSizeAndRateSupported(
                                        size.getWidth(), size.getHeight(), fps)) {
                                    if (selectedEncoder == null) selectedEncoder = codec.getName();
                                    if (hardwareEncoder(codec)) {
                                        selectedEncoder = codec.getName();
                                        break;
                                    }
                                }
                            } catch (IllegalArgumentException ignored) {}
                        }
                        if (selectedEncoder != null) modes.add(new Mode(size, fps, preferred, darker, selectedEncoder));
                    }
                }
            }
        } catch (CameraAccessException e) {
            status.setText("Camera unavailable: " + e.getMessage());
        }
        profiles.setAdapter(adapter(new ArrayList<>(modes)));
        updateLowLightAvailability();
        action.setEnabled(!modes.isEmpty());
        if (modes.isEmpty()) status.setText("This camera exposes none of the requested H.264 modes.");
        else status.setText("Streaming can continue in the background.");
    }

    private static boolean hardwareEncoder(MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= 29) return codec.isHardwareAccelerated();
        String name = codec.getName().toLowerCase(Locale.ROOT);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
                !name.startsWith("c2.google.") && !name.startsWith("omx.ffmpeg.");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 2) {
            start();
        } else if (requestCode == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (hasWindowFocus()) start();
            else status.setText("Camera allowed. Tap Start camera.");
        } else {
            status.setText("Allow camera access to start streaming.");
        }
    }

    private static String localAddresses() {
        ArrayList<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> ips = iface.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) {
                        addresses.add(ip.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {}
        return android.text.TextUtils.join(" / ", addresses);
    }

    private void switchLens() {
        if (running == null || !running.canSwitch()) return;
        int current = cameraIds.indexOf(running.cameraId);
        if (current < 0) return;
        int target = cameraFacing.get(current) == CameraCharacteristics.LENS_FACING_FRONT ?
                CameraCharacteristics.LENS_FACING_BACK : CameraCharacteristics.LENS_FACING_FRONT;
        int index = cameraFacing.indexOf(target);
        if (index < 0) return;
        String id = cameraIds.get(index);
        try {
            CameraCharacteristics lens = getSystemService(CameraManager.class).getCameraCharacteristics(id);
            StreamConfigurationMap map = lens.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(MediaCodec.class);
            Mode mode = running.mode;
            Range<Integer>[] ranges = lens.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Mode replacement = null;
            for (Size size : new Size[]{mode.size, new Size(1280, 720), new Size(640, 480)}) {
                if (replacement != null || sizes == null || !Arrays.asList(sizes).contains(size) || ranges == null)
                    continue;
                int fps = size.equals(mode.size) && replacement == null ? mode.fps : 30;
                if (fps == 60) {
                    boolean supports60 = false;
                    for (Range<Integer> range : ranges) if (range.getUpper() == 60) supports60 = true;
                    if (!supports60) fps = 30;
                }
                long min = map.getOutputMinFrameDuration(MediaCodec.class, size);
                if (min > 1_015_000_000.0 / fps) {
                    fps = 30;
                    if (min > 1_015_000_000.0 / fps) continue;
                }
                Range<Integer> candidate = null, alternate = null;
                for (Range<Integer> range : ranges) {
                    if (range.getUpper() != fps) continue;
                    if (candidate == null || candidate.getLower() < range.getLower()) candidate = range;
                    if (range.getLower() >= 15 && (alternate == null || alternate.getLower() > range.getLower()))
                        alternate = range;
                }
                if (candidate != null)
                    replacement = new Mode(size, fps, candidate, candidate.equals(alternate) ? null : alternate, mode.codec);
            }
            if (replacement == null) {
                showStatus("No compatible streaming mode found for the other camera.");
                return;
            }
            loadLighting(lens);
            updateLowLightAvailability();
            switchCamera.setEnabled(false);
            running.changeCamera(id, replacement, exposureValue(), flickerValue(), lowLight.isChecked());
            cameras.setSelection(index);
        } catch (CameraAccessException | RuntimeException e) {
            showStatus("Could not switch cameras: " + e.getMessage());
        }
    }

    private void start() {
        if (starting || running != null || CameraService.pendingStart != null ||
                profiles.getSelectedItemPosition() < 0) return;
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("asked-notifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked-notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        String ip = localAddresses();
        if (ip.isEmpty()) {
            status.setText("Join the same local Wi-Fi as your PC first.");
            return;
        }
        Mode mode = modes.get(profiles.getSelectedItemPosition());
        int choice = bitrate.getSelectedItemPosition();
        int requestedBitrate = choice <= 0 ? automaticBitrate(mode) :
                BITRATE_OPTIONS_MBPS[choice - 1] * 1_000_000;
        CameraService.pendingStart = new CameraService.SessionSeed(
                cameraIds.get(cameras.getSelectedItemPosition()), mode, requestedBitrate,
                exposureValue(), flickerValue(), lowLight.isChecked());
        starting = true;
        action.setEnabled(false);
        Intent service = new Intent(this, CameraService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (RuntimeException e) {
            CameraService.pendingStart = null;
            showStopped("Unable to start the camera: " + e.getMessage());
        }
    }

    void showSession() {
        CameraService service = CameraService.instance;
        if (service == null || service.running == null) return;
        running = service.running;
        int camera = cameraIds.indexOf(running.cameraId);
        if (camera >= 0) cameras.setSelection(camera);
        switchCamera.setEnabled(service.running.canSwitch());
        switchCamera.setVisibility(cameraFacing.contains(CameraCharacteristics.LENS_FACING_FRONT) &&
                cameraFacing.contains(CameraCharacteristics.LENS_FACING_BACK) ? View.VISIBLE : View.GONE);
        starting = false;
        address.setText("Phone IP  " + localAddresses());
        showStatus(service.status);
        cameras.setEnabled(false);
        profiles.setEnabled(false);
        bitrate.setEnabled(false);
        action.setEnabled(true);
        action.setText("Stop camera");
        lowLight.setEnabled(running.mode.lowLightRange != null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVideoActive(service.videoActive);
    }

    void showStopped(String reason) {
        running = null;
        switchCamera.setVisibility(View.GONE);
        int selectedCamera = cameras.getSelectedItemPosition();
        if (selectedCamera >= 0) loadModes(cameraIds.get(selectedCamera));
        starting = false;
        setVideoActive(false);
        address.setText("Connect both devices to the same Wi-Fi.");
        showStatus(reason);
        cameras.setEnabled(true);
        profiles.setEnabled(true);
        bitrate.setEnabled(profiles.getSelectedItem() != null);
        action.setEnabled(!modes.isEmpty());
        action.setText("Start camera");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void showStatus(String text) { status.setText(text); }

    void setVideoActive(boolean active) {
        videoActive = active;
        if (active) scheduleDim(); else restoreBrightness();
    }

    @Override protected void onStart() {
        super.onStart();
        CameraService.visibleActivity = this;
        if (CameraService.instance != null) showSession();
        else if (running != null) showStopped("Camera stopped.");
    }

    @Override protected void onStop() {
        if (CameraService.visibleActivity == this) CameraService.visibleActivity = null;
        if (running != null) running.dismissPairingPrompt();
        setVideoActive(false);
        super.onStop();
    }
}
