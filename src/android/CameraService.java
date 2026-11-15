package dev.relaycam.android;

import android.app.*;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.*;
import android.media.*;
import android.util.Range;
import android.view.Surface;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import android.os.*;

public final class CameraService extends Service {
    private static final String AVC = "video/avc";
    private static final int PORT = 47477;
    private static final int MAX_PACKET = 8 * 1024 * 1024;
    static final String STOP = "dev.relaycam.android.STOP";
    static final String CHANNEL = "relaycam-camera";
    static CameraService instance;
    static MainActivity visibleActivity;
    static CameraService.SessionSeed pendingStart;
    Session running;
    String status = "Waiting for the Windows app to connect.";
    boolean videoActive;
    private final Handler main = new Handler(Looper.getMainLooper());

    static final class SessionSeed {
        final String camera;
        final MainActivity.Mode mode;
        final int bitrate, exposure, flicker;
        final boolean lowLight;
        SessionSeed(String camera, MainActivity.Mode mode, int bitrate, int exposure, int flicker, boolean lowLight) {
            this.camera = camera;
            this.mode = mode;
            this.bitrate = bitrate;
            this.exposure = exposure;
            this.flicker = flicker;
            this.lowLight = lowLight;
        }
    }

    static SharedPreferences trustedDevices(Context context) {
        return context.getSharedPreferences("trusted-pcs", Context.MODE_PRIVATE);
    }

    private static String tokenId(byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopCamera("Camera stopped.");
            return START_NOT_STICKY;
        }
        if (running != null) return START_NOT_STICKY;
        SessionSeed seed = pendingStart;
        pendingStart = null;
        if (seed == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(1, notification());
            instance = this;
            running = new Session(seed.camera, seed.mode, seed.bitrate);
            running.exposureCompensation = seed.exposure;
            running.antiBanding = seed.flicker;
            running.dimRoom = seed.lowLight;
            running.start();
            if (visibleActivity != null) visibleActivity.showSession();
        } catch (RuntimeException e) {
            stopCamera("Camera service could not start: " + e.getMessage());
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Camera streaming", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent show = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
            new Intent(this, CameraService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ?
            new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.relaycam_status).setContentTitle("RelayCam camera")
            .setContentText("Ready to stream or streaming to your PC")
            .setContentIntent(show).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop camera", stop).build()).build();
    }

    void stopCamera(String reason) {
        Session session = running;
        running = null;
        videoActive = false;
        if (session != null) session.close();
        if (instance == this) instance = null;
        if (visibleActivity != null) visibleActivity.showStopped(reason);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (running != null) stopCamera("Camera stopped.");
        super.onDestroy();
    }

    void runOnUiThread(Runnable action) { main.post(action); }
    final class Session {
        volatile String cameraId;
        private String nextCamera;
        private boolean openingCamera;
        private volatile boolean lensReady;
        private MainActivity.Mode nextMode;
        volatile MainActivity.Mode mode;
        final int requestedBitrate;
        int activeBitrate;
        AlertDialog pairingDialog;
        volatile ArrayBlockingQueue<Integer> pending;
        final HandlerThread worker = new HandlerThread("RelayCam capture");
        final ArrayBlockingQueue<VideoPacket> packets = new ArrayBlockingQueue<>(6);
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

        Session(String cameraId, MainActivity.Mode mode, int requestedBitrate) {
            this.cameraId = cameraId;
            this.mode = mode;
            this.requestedBitrate = requestedBitrate;
        }

        void start() {
            worker.start();
            handler = new Handler(worker.getLooper());
            new Thread(this::serve, "RelayCam network").start();
        }

        void message(String text) {
            runOnUiThread(() -> { if (running == this) { status = text; if (visibleActivity != null) visibleActivity.showStatus(text); } });
        }

        void fail(String text) {
            runOnUiThread(() -> { if (running == this) stopCamera(text); });
        }

        void close() {
            closed = true;
            if (pairingDialog != null) pairingDialog.dismiss();
            ArrayBlockingQueue<Integer> decision = pending;
            if (decision != null) decision.offer(0);
            closeQuietly(listener);
            closeQuietly(peer);
            if (handler == null) { worker.quitSafely(); return; }
            handler.post(() -> {
                handler.removeCallbacks(applyLighting);
                if (capture != null) capture.close();
                if (camera != null) camera.close();
                MediaCodec previous = encoder;
                encoder = null;
                try { if (previous != null) previous.release(); } catch (RuntimeException ignored) {}
                if (surface != null) surface.release();
                if (openingCamera) handler.postDelayed(worker::quitSafely, 1500);
                else worker.quitSafely();
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
                Range<Integer> limits = encoder.getCodecInfo().getCapabilitiesForType(AVC)
                        .getVideoCapabilities().getBitrateRange();
                activeBitrate = limits.clamp(requestedBitrate);
                format.setInteger(MediaFormat.KEY_BIT_RATE, activeBitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps);
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, mode.fps);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                encoder.setCallback(new MediaCodec.Callback() {
                    @Override public void onInputBufferAvailable(MediaCodec codec, int index) {}
                    @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                        if (closed || codec != encoder) return;
                        try {
                            if (info.size <= 0 || info.size > MAX_PACKET) return;
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
                            if (awaitingKey) runOnUiThread(() -> {
                                if (running == Session.this && streaming && !videoActive) {
                                    videoActive = true;
                                    if (visibleActivity != null) visibleActivity.setVideoActive(true);
                                }
                            });
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
                            VideoPacket packet = new VideoPacket(mode, picture);
                            if (!packets.offer(packet)) {
                                packets.clear();
                                awaitingKey = !key;
                                if (key) packets.offer(packet);
                                else requestKey();
                            }
                        } catch (RuntimeException e) {
                            fail("Video encoder failed: " + e.getMessage());
                        } finally {
                            if (!closed && codec == encoder) {
                                try { codec.releaseOutputBuffer(index, false); }
                                catch (IllegalStateException e) { fail("The video encoder stopped unexpectedly."); }
                            }
                        }
                    }
                    @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                        if (closed || codec != encoder) return;
                        config.clear();
                        for (String name : new String[]{"csd-0", "csd-1"}) {
                            ByteBuffer data = format.getByteBuffer(name);
                            if (data != null) config.add(annexB(data));
                        }
                    }
                    @Override public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                        if (!closed && codec == encoder) fail("This mode could not start. Try a lower resolution or frame rate.");
                    }
                }, handler);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = encoder.createInputSurface();
                encoder.start();
                openLens();
            } catch (IOException | CameraAccessException | RuntimeException e) {
                fail("This mode could not start: " + e.getMessage());
            }
        }

        boolean canSwitch() { return lensReady && !closed; }

        void changeCamera(String id, MainActivity.Mode lensMode, int exposure, int flicker, boolean lowLight) {
            handler.post(() -> {
                if (closed || id.equals(cameraId) || openingCamera || nextCamera != null) return;
                nextCamera = id;
                nextMode = lensMode;
                lensReady = false;
                packets.clear();
                config.clear();
                resetPicture = true;
                exposureCompensation = exposure;
                antiBanding = flicker;
                dimRoom = lowLight;
                if (capture != null) capture.close();
                capture = null;
                captureRequest = null;
                MediaCodec oldEncoder = encoder;
                encoder = null;
                if (oldEncoder != null) {
                    try { oldEncoder.release(); } catch (RuntimeException ignored) {}
                }
                if (surface != null) { surface.release(); surface = null; }
                if (camera != null) camera.close();
                else reopenLens();
                message("Switching camera…");
            });
        }

        private void reopenLens() {
            if (closed || nextCamera == null) return;
            cameraId = nextCamera;
            nextCamera = null;
            mode = nextMode;
            nextMode = null;
            openCamera();
        }

        @SuppressWarnings("MissingPermission")
        private void openLens() throws CameraAccessException {
            openingCamera = true;
            getSystemService(CameraManager.class).openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice device) {
                        openingCamera = false;
                        if (closed) { device.close(); worker.quitSafely(); return; }
                        camera = device;
                        configureCamera();
                    }
                    @Override public void onClosed(CameraDevice device) {
                        if (camera == device) {
                            camera = null;
                            reopenLens();
                        }
                    }
                    @Override public void onDisconnected(CameraDevice device) {
                        device.close();
                        if (closed) worker.quitSafely();
                        else if (nextCamera == null) fail("Android disconnected the camera.");
                    }
                    @Override public void onError(CameraDevice device, int error) {
                        device.close();
                        if (closed) worker.quitSafely();
                        else if (nextCamera == null) fail("Android could not open the camera (error " + error + ").");
                    }
                }, handler);
        }

        @SuppressWarnings("deprecation")
        void configureCamera() {
            try {
                CameraDevice opening = camera;
                opening.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        if (closed || nextCamera != null || camera != opening) { session.close(); return; }
                        capture = session;
                        try {
                            captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            captureRequest.addTarget(surface);
                            defaultAntiBanding = captureRequest.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE);
                            submitLighting();
                            requestKey();
                            lensReady = true;
                            runOnUiThread(() -> {
                                if (running == Session.this && visibleActivity != null) visibleActivity.showSession();
                            });
                        } catch (CameraAccessException | RuntimeException e) {
                            fail("Camera could not stream this mode: " + e.getMessage());
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        if (!closed && nextCamera == null && camera == opening) fail("Camera rejected this mode. Try a lower resolution or frame rate.");
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
                        Math.round(encodedFrames * 1000.0 / elapsed) + " fps encoded · " +
                        String.format(Locale.ROOT, "%.1f Mbps target", activeBitrate / 1_000_000.0));
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
                        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), StreamWriter.BUFFER_BYTES));
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
                        byte[] request = new byte[65];
                        in.readFully(request);
                        if (request[0] < 0 || request[0] > 2) continue;
                        SharedPreferences trusted = trustedDevices(CameraService.this);
                        String id = tokenId(Arrays.copyOfRange(request, 1, 33));
                        String credential = trusted.getString(id, null);
                        byte[] stored = credential == null ? null : android.util.Base64.decode(credential, android.util.Base64.NO_WRAP);
                        boolean resumed = request[0] != 0 && stored != null &&
                            java.security.MessageDigest.isEqual(Arrays.copyOfRange(request, 33, 65),
                                Pairing.proof(stored, result.trust, "client"));
                        int approval = resumed ? 2 : request[0] == 2 ? 0 : approve(result.code, socket, in, request[0] == 1);
                        if (closed) return;
                        if (approval == 3) {
                            String newId = tokenId(Pairing.commit(result.trust));
                            if (trusted.getAll().size() >= 16)
                                approval = 1;
                            else if (!trusted.edit().putString(newId, tokenId(result.trust)).commit())
                                approval = 1;
                        }
                        out.writeByte(approval);
                        if (resumed) out.write(Pairing.proof(stored, result.trust, "server"));
                        out.flush();
                        if (approval == 0) continue;
                        StreamWriter writer = new StreamWriter(out, result.videoKey);
                        MainActivity.Mode sentMode = mode;
                        byte[] header = modePacket(sentMode, false);
                        writer.write(header);
                        writer.flush();
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
                            VideoPacket frame = packets.poll(8, TimeUnit.SECONDS);
                            if (frame == null || frame.data.length > MAX_PACKET) break;
                            long deadline = SystemClock.elapsedRealtime() + 8;
                            int written = 0;
                            if (!sameMode(sentMode, frame.mode)) {
                                sentMode = frame.mode;
                                written += writer.write(modePacket(sentMode, true));
                            }
                            written += writer.write(frame.data);
                            while (!closed && written < StreamWriter.BUFFER_BYTES) {
                                long remaining = deadline - SystemClock.elapsedRealtime();
                                if (remaining <= 0) break;
                                frame = packets.poll(remaining, TimeUnit.MILLISECONDS);
                                if (frame == null) break;
                                if (frame.data.length > MAX_PACKET) break;
                                if (!sameMode(sentMode, frame.mode)) {
                                    sentMode = frame.mode;
                                    written += writer.write(modePacket(sentMode, true));
                                }
                                written += writer.write(frame.data);
                            }
                            if (!closed) writer.flush();
                        }
                    } catch (IOException e) {
                        if (closed) return;
                    } finally {
                        peer = null;
                        streaming = false;
                        packets.clear();
                        if (!closed) runOnUiThread(() -> {
                            if (running == this) {
                                videoActive = false;
                                status = "Waiting for the Windows app to connect.";
                                if (visibleActivity != null) {
                                    visibleActivity.setVideoActive(false);
                                    visibleActivity.showStatus(status);
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                if (!closed) fail("Could not start streaming: " + e.getMessage());
            }
        }

        void dismissPairingPrompt() {
            ArrayBlockingQueue<Integer> decision = pending;
            if (decision != null) decision.offer(0);
            if (pairingDialog != null) { pairingDialog.dismiss(); pairingDialog = null; }
        }

        int approve(String code, Socket socket, DataInputStream in, boolean canRemember)
                throws InterruptedException, IOException {
            String computerIp = socket.getInetAddress().getHostAddress();
            ArrayBlockingQueue<Integer> decision = new ArrayBlockingQueue<>(1);
            pending = decision;
            runOnUiThread(() -> {
                if (closed || running != this || pending != decision || visibleActivity == null) {
                    decision.offer(0);
                    return;
                }
                pairingDialog = new AlertDialog.Builder(visibleActivity)
                        .setTitle("Pair with this PC?")
                        .setMessage("Code  " + code + "\nPC  " + computerIp
                                + "\n\nTap Allow only if Windows shows the same code.")
                        .setPositiveButton("Allow once", (dialog, which) -> decision.offer(1))
                        .setNeutralButton(canRemember ? "Allow and remember" : null,
                            (dialog, which) -> decision.offer(3))
                        .setNegativeButton("Decline", (dialog, which) -> decision.offer(0))
                        .setOnCancelListener(dialog -> decision.offer(0))
                        .show();
            });
            try {
                socket.setSoTimeout(200);
                long deadline = SystemClock.elapsedRealtime() + Pairing.APPROVAL_SECONDS * 1000L;
                while (!closed && SystemClock.elapsedRealtime() < deadline) {
                    Integer answer = decision.poll();
                    if (answer != null) return answer;
                    try {
                        if (in.read() < 0) throw new EOFException("The PC cancelled pairing");
                        throw new IOException("Unexpected data during pairing");
                    } catch (SocketTimeoutException waiting) {
                        // Check the approval prompt again between socket timeouts.
                    }
                }
                return 0;
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


    private static final class VideoPacket {
        final MainActivity.Mode mode;
        final byte[] data;
        VideoPacket(MainActivity.Mode mode, byte[] data) { this.mode = mode; this.data = data; }
    }

    private static boolean sameMode(MainActivity.Mode first, MainActivity.Mode second) {
        return first.fps == second.fps && first.size.equals(second.size);
    }

    private static byte[] modePacket(MainActivity.Mode mode, boolean change) {
        ByteBuffer packet = ByteBuffer.allocate(change ? 16 : 12);
        if (change) packet.putInt(0x52434D43);
        return packet.putInt(mode.size.getWidth()).putInt(mode.size.getHeight()).putInt(mode.fps).array();
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
