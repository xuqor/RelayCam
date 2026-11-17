package dev.relaycam.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFF15151B;
    private static final int CARD = 0xFF202029;
    private static final int BORDER = 0xFF383744;
    private static final int TEXT = 0xFFF4F1FB;
    private static final int MUTED = 0xFFC0BCCC;
    private static final int ACCENT = 0xFFC9B9FF;
    private final ArrayList<Mode> modes = new ArrayList<>();
    private final ArrayList<String> cameraIds = new ArrayList<>();
    private Spinner cameras;
    private Spinner profiles;
    private Button at30, at60;
    private TextView modeHint;
    private int preferredFps = 30;
    private Mode selectedMode;
    private boolean allControlsReady;
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
        final boolean highSpeed;

        Mode(Size size, int fps, Range<Integer> range, Range<Integer> lowLightRange, String codec, boolean highSpeed) {
            this.size = size;
            this.fps = fps;
            this.range = range;
            this.lowLightRange = lowLightRange;
            this.codec = codec;
            this.highSpeed = highSpeed;
        }

        @Override public String toString() {
            return resolutionName(size) + " · " + fps + " fps";
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getDecorView().getWindowInsetsController() != null) {
            getWindow().getDecorView().getWindowInsetsController().hide(WindowInsets.Type.statusBars());
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(18), dp(20), dp(18), dp(24));
        scroll.addView(page);
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        int usableWidth = getResources().getDisplayMetrics().widthPixels - dp(36);
        page.addView(stack, new LinearLayout.LayoutParams(Math.min(dp(560), usableWidth), -2));
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() |
                    WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                int width = view.getWidth() - bars.left - bars.right - dp(36);
                if (width > 0) updatePageWidth(stack, width);
                return insets;
            });
        }
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            updatePageWidth(stack, right - left - view.getPaddingLeft() - view.getPaddingRight() - dp(36)));
        setContentView(scroll);
        TextView heading = label(stack, "RelayCam", 32);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));

        LinearLayout connection = card(stack);
        address = label(connection, "Connect both devices to the same Wi-Fi.", 15);
        status = label(connection, "Ready to connect", 14);
        status.setTextColor(MUTED);
        action = new Button(this);
        action.setText("Start camera");
        action.setEnabled(false);
        styleButton(action, true);
        connection.addView(action, row());
        switchCamera = new Button(this);
        switchCamera.setText("Switch front / rear");
        styleButton(switchCamera, false);
        switchCamera.setVisibility(View.GONE);
        connection.addView(switchCamera, row());
        switchCamera.setOnClickListener(v -> switchLens());

        LinearLayout capture = card(stack);
        section(capture, "Capture");
        field(capture, "Camera");
        cameras = new Spinner(this);
        capture.addView(cameras, row());
        field(capture, "Resolution");
        profiles = new Spinner(this);
        capture.addView(profiles, row());
        field(capture, "Frame rate");
        LinearLayout rates = new LinearLayout(this);
        at30 = rateButton(rates, 30);
        at60 = rateButton(rates, 60);
        capture.addView(rates, row());
        modeHint = label(capture, "", 13);
        modeHint.setTextColor(MUTED);
        field(capture, "Video bitrate");
        bitrate = new Spinner(this);
        capture.addView(bitrate, row());
        bitrate.setEnabled(false);

        LinearLayout advanced = card(stack);
        Button settings = new Button(this);
        settings.setText("Show lighting and pairing");
        styleButton(settings, false);
        advanced.addView(settings, row());
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        advanced.addView(panel);
        settings.setOnClickListener(v -> {
            boolean open = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(open ? View.VISIBLE : View.GONE);
            settings.setText(open ? "Hide lighting and pairing" : "Show lighting and pairing");
        });
        exposureLabel = label(panel, "Exposure", 14);
        exposure = new SeekBar(this);
        panel.addView(exposure, row());
        Button resetExposure = new Button(this);
        styleButton(resetExposure, false);
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
        styleButton(forgetPcs, false);
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

        allControlsReady = true;
        cameras.setAdapter(adapter(names));
        int rear = cameraFacing.indexOf(CameraCharacteristics.LENS_FACING_BACK);
        if (rear >= 0) cameras.setSelection(rear);
        profiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateMode();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { updateMode(); }
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(6);
        return params;
    }

    private TextView label(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(TEXT);
        text.setTextSize(size);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        parent.addView(text, p);
        return text;
    }

    private void updatePageWidth(LinearLayout stack, int available) {
        if (available <= 0) return;
        int width = Math.min(dp(560), available);
        if (stack.getLayoutParams().width != width) {
            stack.getLayoutParams().width = width;
            stack.requestLayout();
        }
    }

    private android.graphics.drawable.GradientDrawable surface(int color, boolean bordered) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(14));
        if (bordered) drawable.setStroke(dp(1), BORDER);
        return drawable;
    }

    private void styleButton(Button button, boolean prominent) {
        int normal = prominent ? ACCENT : CARD;
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, surface(BORDER, false));
        background.addState(new int[]{android.R.attr.state_selected}, surface(ACCENT, false));
        background.addState(new int[]{}, surface(normal, !prominent));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x30766C8E), background, null));
        button.setTextColor(new android.content.res.ColorStateList(
            new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_selected}, {}},
            new int[]{MUTED, BACKGROUND, prominent ? BACKGROUND : TEXT}));
        button.setAllCaps(false);
        button.setTextSize(14);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(14), dp(16), dp(10));
        group.setBackground(surface(CARD, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(group, params);
        return group;
    }

    private void section(LinearLayout parent, String title) {
        label(parent, title, 19).setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
    }

    private void field(LinearLayout parent, String title) {
        label(parent, title, 13).setTextColor(MUTED);
    }

    private Button rateButton(LinearLayout parent, int fps) {
        Button button = new Button(this);
        button.setText(fps + " fps");
        styleButton(button, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (fps == 30) params.rightMargin = dp(8);
        parent.addView(button, params);
        button.setOnClickListener(v -> { preferredFps = fps; updateMode(); });
        return button;
    }

    private static String resolutionName(Size size) {
        if (size.getWidth() == 3840) return "4K UHD";
        if (size.getWidth() == 2048) return "2K";
        return size.getHeight() + "p";
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
        Mode mode = running == null ? selectedMode : running.mode;
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
            CameraCharacteristics camera = getSystemService(CameraManager.class).getCameraCharacteristics(cameraId);
            loadLighting(camera);
            modes.addAll(CaptureModes.forCamera(camera));
        } catch (CameraAccessException | RuntimeException e) {
            showStatus("Camera unavailable: " + e.getMessage());
        }
        ArrayList<String> labels = new ArrayList<>();
        for (Size size : CaptureModes.SIZES) {
            boolean available = findMode(size, 30) != null || findMode(size, 60) != null;
            labels.add(resolutionName(size) + " · " + size.getWidth() + " × " + size.getHeight() +
                (available ? "" : " · unavailable"));
        }

        int position = profiles.getSelectedItemPosition();
        if (position < 0 && !modes.isEmpty()) position = Arrays.asList(CaptureModes.SIZES).indexOf(modes.get(0).size);
        profiles.setAdapter(adapter(labels));
        profiles.setSelection(Math.max(0, position));
        updateMode();
    }

    private Mode findMode(Size size, int fps) {
        for (Mode mode : modes)
            if (mode.size.equals(size) && mode.fps == fps) return mode;
        return null;
    }

    private void updateMode() {
        if (!allControlsReady || running != null) return;
        int index = profiles.getSelectedItemPosition();
        Size size = index >= 0 ? CaptureModes.SIZES[index] : null;
        Mode normal = findMode(size, 30);
        Mode fast = findMode(size, 60);
        selectedMode = fast != null && (preferredFps == 60 || normal == null) ? fast : normal;
        at30.setEnabled(normal != null);
        at60.setEnabled(fast != null);
        at30.setSelected(selectedMode != null && selectedMode.fps == 30);
        at60.setSelected(selectedMode != null && selectedMode.fps == 60);
        action.setEnabled(selectedMode != null && !starting);
        if (selectedMode == null) showModeHint("This lens and H.264 encoder do not offer this resolution.");
        else if (fast == null) showModeHint("60 fps is unavailable at this resolution with this camera.");
        else if (selectedMode.highSpeed) showModeHint("60 fps uses the phone’s high-speed camera mode.");
        else showModeHint("");
        updateLowLightAvailability();
    }

    private void showModeHint(String message) {
        modeHint.setText(message);
        modeHint.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
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
            Mode mode = running.mode;
            List<Mode> available = CaptureModes.forCamera(lens);
            Mode replacement = null;
            for (Size size : new Size[]{mode.size, new Size(1280, 720), new Size(640, 480)}) {
                for (Mode candidate : available) {
                    if (!candidate.size.equals(size)) continue;
                    if (size.equals(mode.size) && candidate.fps == mode.fps) {
                        replacement = candidate;
                        break;
                    }
                    if (replacement == null && candidate.fps == 30) replacement = candidate;
                }
                if (replacement != null) break;
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
                selectedMode == null) return;
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
        Mode mode = selectedMode;
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
        at30.setEnabled(false);
        at60.setEnabled(false);
        showModeHint("Streaming " + running.mode);
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
        updateMode();
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
