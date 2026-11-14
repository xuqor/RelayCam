package dev.relaycam.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MainActivity extends Activity {
    private static final String AVC = "video/avc";
    private static final int PORT = 47477;
    private static final int MAX_PACKET = 8 * 1024 * 1024;
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
    private Session running;

    private static final class Mode {
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
        setContentView(scroll);
        label(panel, "RelayCam", 30);
        address = label(panel, "Connect both devices to the same Wi-Fi.", 16);
        label(panel, "Camera", 14);
        cameras = new Spinner(this);
        panel.addView(cameras, row());
        label(panel, "Resolution and frame rate", 14);
        profiles = new Spinner(this);
        panel.addView(profiles, row());
        action = new Button(this);
        action.setAllCaps(false);
        action.setText("Start camera");
        action.setEnabled(false);
        panel.addView(action, row());
        status = label(panel, "The camera runs while this screen is open.", 15);
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
            if (running != null) {
                stop("Camera stopped.");
            } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            } else {
                start();
            }
        });

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
                loadModes(cameraIds.get(position));
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
        Mode mode = (Mode) profiles.getSelectedItem();
        lowLight.setEnabled(mode != null && mode.lowLightRange != null);
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
        else status.setText("The camera runs while this screen is open.");
    }

    private static boolean hardwareEncoder(MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= 29) return codec.isHardwareAccelerated();
        String name = codec.getName().toLowerCase(Locale.ROOT);
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") &&
                !name.startsWith("c2.google.") && !name.startsWith("omx.ffmpeg.");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
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

    private void start() {
        if (running != null || profiles.getSelectedItemPosition() < 0) return;
        String ip = localAddresses();
        if (ip.isEmpty()) {
            status.setText("Join the same local Wi-Fi as your PC first.");
            return;
        }
        running = new Session(cameraIds.get(cameras.getSelectedItemPosition()),
                modes.get(profiles.getSelectedItemPosition()));
        address.setText("Phone IP  " + ip);
        status.setText("Enter the phone IP in RelayCam on Windows, then approve the connection here.");
        cameras.setEnabled(false);
        profiles.setEnabled(false);
        action.setText("Stop camera");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        running.start();
        refreshLighting();
    }

    private void stop(String reason) {
        Session session = running;
        running = null;
        if (session != null) session.close();
        address.setText("Connect both devices to the same Wi-Fi.");
        status.setText(reason);
        cameras.setEnabled(true);
        profiles.setEnabled(true);
        action.setText("Start camera");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onStop() {
        super.onStop();
        if (running != null) stop("Camera stopped while the app was in the background.");
    }

    private final class Session {
        final String cameraId;
        final Mode mode;
        AlertDialog pairingDialog;
        volatile ArrayBlockingQueue<Boolean> pending;
        final HandlerThread worker = new HandlerThread("RelayCam capture");
        final ArrayBlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(6);
        volatile boolean closed;
        volatile boolean streaming;
        volatile boolean resetPicture;
        volatile Socket peer;
        volatile ServerSocket listener;
        Handler handler;
        MediaCodec encoder;
        Surface surface;
        CameraDevice camera;
        CameraCaptureSession capture;
        CaptureRequest.Builder captureRequest;
        int exposureCompensation;
        int antiBanding = -1;
        Integer defaultAntiBanding;
        boolean dimRoom;
        final ArrayList<byte[]> config = new ArrayList<>();
        boolean awaitingKey = true;
        long lastRateUpdate;
        int encodedFrames;

        Session(String cameraId, Mode mode) {
            this.cameraId = cameraId;
            this.mode = mode;
        }

        void start() {
            worker.start();
            handler = new Handler(worker.getLooper());
            new Thread(this::serve, "RelayCam network").start();
        }

        void message(String text) {
            runOnUiThread(() -> { if (running == this) status.setText(text); });
        }

        void fail(String text) {
            runOnUiThread(() -> { if (running == this) stop(text); });
        }

        void close() {
            closed = true;
            if (pairingDialog != null) pairingDialog.dismiss();
            ArrayBlockingQueue<Boolean> decision = pending;
            if (decision != null) decision.offer(false);
            closeQuietly(listener);
            closeQuietly(peer);
            handler.post(() -> {
                if (capture != null) capture.close();
                if (camera != null) camera.close();
                if (encoder != null) encoder.release();
                if (surface != null) surface.release();
                worker.quitSafely();
            });
        }

        @SuppressWarnings("MissingPermission")
        void openCamera() {
            if (closed) return;
            try {
                int w = mode.size.getWidth(), h = mode.size.getHeight();
                encoder = MediaCodec.createByCodecName(mode.codec);
                MediaFormat format = MediaFormat.createVideoFormat(AVC, w, h);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                int bitrate = Math.min(36_000_000, w * h * mode.fps / 12);
                Range<Integer> limits = encoder.getCodecInfo().getCapabilitiesForType(AVC)
                        .getVideoCapabilities().getBitrateRange();
                format.setInteger(MediaFormat.KEY_BIT_RATE, limits.clamp(bitrate));
                format.setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps);
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, mode.fps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                encoder.setCallback(new MediaCodec.Callback() {
                    @Override public void onInputBufferAvailable(MediaCodec codec, int index) {}
                    @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                        try {
                            if (closed || info.size <= 0 || info.size > MAX_PACKET) return;
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
                            if (resetPicture) {
                                resetPicture = false;
                                awaitingKey = true;
                                lastRateUpdate = 0;
                                encodedFrames = 0;
                            }
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (!streaming) return;
                            updateEncoderRate();
                            if (awaitingKey && !key) return;
                            awaitingKey = false;
                            ByteBuffer bytes = codec.getOutputBuffer(index);
                            if (bytes == null) return;
                            ((java.nio.Buffer) bytes).position(info.offset);
                            ((java.nio.Buffer) bytes).limit(info.offset + info.size);
                            byte[] picture = annexB(bytes);
                            if (key && !config.isEmpty()) {
                                ByteArrayOutputStream combined = new ByteArrayOutputStream();
                                for (byte[] part : config) combined.write(part, 0, part.length);
                                combined.write(picture, 0, picture.length);
                                picture = combined.toByteArray();
                            }
                            if (!packets.offer(picture)) {
                                packets.clear();
                                awaitingKey = !key;
                                if (key) packets.offer(picture);
                                else requestKey();
                            }
                        } catch (RuntimeException e) {
                            fail("Video encoder failed: " + e.getMessage());
                        } finally {
                            codec.releaseOutputBuffer(index, false);
                        }
                    }
                    @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                        config.clear();
                        for (String name : new String[]{"csd-0", "csd-1"}) {
                            ByteBuffer data = format.getByteBuffer(name);
                            if (data != null) config.add(annexB(data));
                        }
                    }
                    @Override public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                        fail("This mode could not start. Try a lower resolution or frame rate.");
                    }
                }, handler);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = encoder.createInputSurface();
                encoder.start();
                getSystemService(CameraManager.class).openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        if (closed) { device.close(); return; }
                        camera = device;
                        configureCamera();
                    }
                    @Override public void onDisconnected(CameraDevice device) {
                        device.close();
                        fail("Android disconnected the camera.");
                    }
                    @Override public void onError(CameraDevice device, int error) {
                        device.close();
                        fail("Android could not open the camera (error " + error + ").");
                    }
                }, handler);
            } catch (IOException | CameraAccessException | RuntimeException e) {
                fail("This mode could not start: " + e.getMessage());
            }
        }

        @SuppressWarnings("deprecation")
        void configureCamera() {
            try {
                camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        if (closed) { session.close(); return; }
                        capture = session;
                        try {
                            captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            captureRequest.addTarget(surface);
                            defaultAntiBanding = captureRequest.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE);
                            submitLighting();
                        } catch (CameraAccessException | RuntimeException e) {
                            fail("Camera could not stream this mode: " + e.getMessage());
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        fail("Camera rejected this mode. Try a lower resolution or frame rate.");
                    }
                }, handler);
            } catch (CameraAccessException | RuntimeException e) {
                fail("Camera could not create a capture session: " + e.getMessage());
            }
        }

        void updateLighting(int compensation, int flicker, boolean lowLightEnabled) {
            handler.post(() -> {
                exposureCompensation = compensation;
                antiBanding = flicker;
                dimRoom = lowLightEnabled;
                handler.removeCallbacks(applyLighting);
                handler.postDelayed(applyLighting, 80);
            });
        }

        private final Runnable applyLighting = () -> {
            try {
                submitLighting();
            } catch (CameraAccessException | RuntimeException e) {
                message("The camera rejected this lighting setting.");
            }
        };

        void submitLighting() throws CameraAccessException {
            if (closed || captureRequest == null || capture == null) return;
            Range<Integer> range = dimRoom && mode.lowLightRange != null ? mode.lowLightRange : mode.range;
            captureRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureRequest.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);
            captureRequest.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antiBanding < 0 ? defaultAntiBanding : Integer.valueOf(antiBanding));
            capture.setRepeatingRequest(captureRequest.build(), null, handler);
        }

        void updateEncoderRate() {
            long now = SystemClock.elapsedRealtime();
            if (lastRateUpdate == 0) lastRateUpdate = now;
            ++encodedFrames;
            long elapsed = now - lastRateUpdate;
            if (elapsed >= 1000) {
                message("Connected · " + mode + " target · " +
                        Math.round(encodedFrames * 1000.0 / elapsed) + " fps encoded");
                lastRateUpdate = now;
                encodedFrames = 0;
            }
        }

        void requestKey() {
            if (encoder == null || closed) return;
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            try { encoder.setParameters(params); }
            catch (RuntimeException ignored) {}
        }

        void serve() {
            try (ServerSocket server = new ServerSocket()) {
                listener = server;
                if (closed) return;
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 1);
                boolean started = false;
                while (!closed) {
                    try (Socket socket = server.accept()) {
                        peer = socket;
                        if (closed) return;
                        socket.setSoTimeout(3500);
                        socket.setTcpNoDelay(true);
                        socket.setSendBufferSize(128 * 1024);
                        DataInputStream in = new DataInputStream(socket.getInputStream());
                        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                        Pairing pairing = new Pairing();
                        byte[] serverPublic = pairing.publicKey();
                        out.writeInt(Pairing.MAGIC);
                        out.write(Pairing.commit(serverPublic));
                        out.flush();

                        byte[] clientPublic = new byte[Pairing.PUBLIC_SIZE];
                        in.readFully(clientPublic);
                        Pairing.Result result;
                        try {
                            result = pairing.finish(clientPublic, serverPublic);
                        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                            continue;
                        }
                        out.write(serverPublic);
                        out.flush();
                        boolean allowed = approve(result.code, socket, in);
                        if (closed) return;
                        out.writeByte(allowed ? 1 : 0);
                        out.flush();
                        if (!allowed) continue;
                        SecretKeySpec videoKey = result.videoKey;
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        long counter = 0;
                        byte[] header = ByteBuffer.allocate(12).putInt(mode.size.getWidth())
                                .putInt(mode.size.getHeight()).putInt(mode.fps).array();
                        sendPacket(out, cipher, videoKey, counter++, header);
                        packets.clear();
                        resetPicture = true;
                        streaming = true;
                        if (!started) {
                            started = true;
                            handler.post(this::openCamera);
                        } else {
                            handler.post(this::requestKey);
                        }
                        message("Connected · " + mode);
                        while (!closed) {
                            byte[] frame = packets.poll(8, TimeUnit.SECONDS);
                            if (frame == null || frame.length > MAX_PACKET) break;
                            sendPacket(out, cipher, videoKey, counter++, frame);
                        }
                    } catch (IOException e) {
                        if (closed) return;
                    } finally {
                        peer = null;
                        streaming = false;
                        packets.clear();
                        if (!closed) message("Waiting for the Windows app to connect.");
                    }
                }
            } catch (Exception e) {
                if (!closed) fail("Could not start streaming: " + e.getMessage());
            }
        }

        boolean approve(String code, Socket socket, DataInputStream in)
                throws InterruptedException, IOException {
            String computerIp = socket.getInetAddress().getHostAddress();
            ArrayBlockingQueue<Boolean> decision = new ArrayBlockingQueue<>(1);
            pending = decision;
            runOnUiThread(() -> {
                if (closed || running != this || pending != decision) {
                    decision.offer(false);
                    return;
                }
                pairingDialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Pair with this PC?")
                        .setMessage("Code  " + code + "\nPC  " + computerIp
                                + "\n\nTap Allow only if Windows shows the same code.")
                        .setPositiveButton("Allow", (dialog, which) -> decision.offer(true))
                        .setNegativeButton("Decline", (dialog, which) -> decision.offer(false))
                        .setOnCancelListener(dialog -> decision.offer(false))
                        .show();
            });
            try {
                socket.setSoTimeout(200);
                long deadline = SystemClock.elapsedRealtime() + Pairing.APPROVAL_SECONDS * 1000L;
                while (!closed && SystemClock.elapsedRealtime() < deadline) {
                    Boolean answer = decision.poll();
                    if (answer != null) return answer;
                    try {
                        if (in.read() < 0) throw new EOFException("The PC cancelled pairing");
                        throw new IOException("Unexpected data during pairing");
                    } catch (SocketTimeoutException waiting) {
                        // Check the approval prompt again between socket timeouts.
                    }
                }
                return false;
            } finally {
                pending = null;
                runOnUiThread(() -> {
                    if (pairingDialog != null) {
                        pairingDialog.dismiss();
                        pairingDialog = null;
                    }
                });
            }
        }
    }

    private static void sendPacket(DataOutputStream out, Cipher cipher, SecretKeySpec key,
                                   long counter, byte[] packet) throws Exception {
        byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array();
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(packet);
        out.writeInt(encrypted.length);
        out.write(encrypted);
        out.flush();
    }

    private static byte[] annexB(ByteBuffer bytes) {
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        if (raw.length >= 4 && raw[0] == 0 && raw[1] == 0 &&
                (raw[2] == 1 || (raw[2] == 0 && raw[3] == 1))) return raw;
        ByteBuffer lengths = ByteBuffer.wrap(raw);
        ByteArrayOutputStream converted = new ByteArrayOutputStream(raw.length);
        byte[] start = {0, 0, 0, 1};
        while (lengths.remaining() >= 4) {
            int size = lengths.getInt();
            if (size <= 0 || size > lengths.remaining()) break;
            converted.write(start, 0, start.length);
            converted.write(raw, lengths.position(), size);
            lengths.position(lengths.position() + size);
        }
        if (!lengths.hasRemaining() && converted.size() > 0) return converted.toByteArray();
        converted.reset();
        converted.write(start, 0, start.length);
        converted.write(raw, 0, raw.length);
        return converted.toByteArray();
    }

    private static void closeQuietly(Closeable object) {
        if (object == null) return;
        try { object.close(); }
        catch (IOException ignored) {}
    }
}
