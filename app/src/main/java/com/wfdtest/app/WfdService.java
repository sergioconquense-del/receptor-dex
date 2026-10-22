package com.wfdtest.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import rikka.shizuku.Shizuku;

/**
 * Owns everything that must survive the Activity being paused/killed by the TV (user hits Home,
 * opens another app, etc.): the P2P listening state, the Shizuku-privileged sink advertisement,
 * the RTSP control server and the UIBC channel. The Activity only supplies the Surface to render
 * into and a place to show log lines; losing the Activity drops the picture but not the session.
 */
public class WfdService extends Service {

    enum State { IDLE, LISTENING, CONNECTING, STREAMING }

    private static final String CHANNEL_ID = "wfd_sink";
    private static final int NOTIFICATION_ID = 1;
    private static final int SHIZUKU_REQUEST_CODE = 9001;

    private final IBinder binder = new LocalBinder();

    class LocalBinder extends Binder {
        WfdService getService() {
            return WfdService.this;
        }
    }

    private Handler handler;

    private WifiP2pManager mgr;
    private WifiP2pManager.Channel ch;
    private RtspSink rtsp;
    private StreamSession session; // main thread only
    private Surface surface;       // main thread only
    private RtpReceiver pendingReceiver; // opened before SETUP, handed to the session after PLAY
    private long lastIdrRequestMs;
    private IWfdPrivileged privileged;
    private volatile boolean dialedOut = false;
    private volatile boolean scanning = false;
    private volatile State state = State.IDLE;

    private boolean userServiceBound;

    // --- starting Shizuku by ourselves -------------------------------------------------------
    private volatile String shizukuStartStatus = "";
    private volatile boolean shizukuStarting;

    String shizukuStartStatus() {
        return shizukuStartStatus;
    }

    /** While Shizuku is not running, try to start it (through ShizukuStarter) every 15 seconds. */
    private final Runnable shizukuWatchdog = new Runnable() {
        @Override public void run() {
            boolean running;
            try {
                running = Shizuku.pingBinder();
            } catch (Throwable t) {
                running = false;
            }
            if (running) {
                shizukuStartStatus = "";
            } else if (!shizukuStarting) {
                shizukuStarting = true;
                new Thread(() -> {
                    try {
                        shizukuStartStatus = "Arrancando Shizuku...";
                        line("starting Shizuku");
                        String out = ShizukuStarter.start(WfdService.this);
                        line("Shizuku starter: " + out.trim().replace("\n", " | "));
                        shizukuStartStatus = "";
                    } catch (Throwable t) {
                        String why = t instanceof IllegalStateException ? t.getMessage()
                                : "Todavía no se ha podido arrancar Shizuku (" + t.getClass().getSimpleName()
                                + "). Lo reintenta cada 15 s.";
                        shizukuStartStatus = why;
                        line("Shizuku start failed: " + t);
                    } finally {
                        shizukuStarting = false;
                    }
                }, "shizuku-start").start();
            }
            handler.postDelayed(this, 15_000);
        }
    };

    // --- who may connect -------------------------------------------------------------------
    // A device is let in if it was accepted "always" before, or if somebody at the TV accepts it
    // now. The question can only be asked while the app is on screen: with the app closed, a
    // device that is not already trusted is refused.

    interface ApprovalListener {
        /** answer: 0 = refuse, 1 = accept this time, 2 = accept and remember this device. */
        void ask(String deviceName, java.util.function.IntConsumer answer);
        void cancel();
    }

    private static final String PREFS = "trusted_devices";
    private static final long APPROVAL_TIMEOUT_SECONDS = 30;
    private volatile ApprovalListener approvalListener;
    private volatile android.net.wifi.p2p.WifiP2pGroup lastGroup;

    void setApprovalListener(ApprovalListener l) {
        approvalListener = l;
    }

    int trustedCount() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getAll().size();
    }

    void forgetTrusted() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
        line("trusted devices forgotten");
    }

    /** Runs on the RTSP session thread and blocks until the person at the TV has answered. */
    private boolean authorizePeer() {
        String id = null;
        String name = "Un dispositivo";
        android.net.wifi.p2p.WifiP2pGroup g = lastGroup;
        if (g != null) {
            for (android.net.wifi.p2p.WifiP2pDevice d : g.getClientList()) {
                id = d.deviceAddress;
                if (d.deviceName != null && !d.deviceName.isEmpty()) name = d.deviceName;
                break;
            }
        }
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (id != null && prefs.contains(id)) {
            line("trusted device connecting: " + name);
            return true;
        }
        ApprovalListener l = approvalListener;
        if (l == null) {
            line("refused " + name + ": the app is not open on the TV and the device is not trusted");
            dropGroup();
            return false;
        }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        int[] answer = {0};
        l.ask(name, a -> {
            answer[0] = a;
            done.countDown();
        });
        try {
            if (!done.await(APPROVAL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) l.cancel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        line(name + (answer[0] == 0 ? " refused" : answer[0] == 2 ? " accepted and remembered" : " accepted once"));
        if (answer[0] == 2 && id != null) prefs.edit().putString(id, name).apply();
        if (answer[0] == 0) dropGroup();
        return answer[0] != 0;
    }

    /** Ends the Wi-Fi Direct link so a refused device is really disconnected, not just ignored. */
    private void dropGroup() {
        handler.post(() -> {
            if (mgr != null && ch != null) mgr.removeGroup(ch, listener("removeGroup"));
        });
    }

    // Shizuku may start after this service does (e.g. the TV just booted and Shizuku was started
    // by hand or by its boot option): the sticky variant also fires immediately if it is already up.
    private final Shizuku.OnBinderReceivedListener shizukuBinderListener = () -> {
        line("Shizuku available");
        handler.post(this::setupShizuku);
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                    line("Shizuku permission granted");
                    bindPrivilegedService();
                } else {
                    line("Shizuku permission denied");
                }
            };

    private final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName("com.wfdtest.app", WfdUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("privileged")
                    .debuggable(false)
                    .version(1);

    private final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            if (b != null && b.pingBinder()) {
                privileged = IWfdPrivileged.Stub.asInterface(b);
                line("Shizuku service connected; advertising sink via privileged process");
                try {
                    boolean ok = privileged.advertiseSink("Philips OLED718", RtspSink.CONTROL_PORT);
                    line("advertiseSink -> " + ok);
                } catch (Exception e) {
                    line("advertiseSink call failed: " + e);
                }
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            privileged = null;
            userServiceBound = false;
            line("Shizuku service disconnected");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Arrancando..."));
        Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if Android kills this process under memory pressure, it restarts the
        // service (onCreate runs again, re-advertising the sink) without the Activity needing to
        // notice or relaunch anything itself.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        Shizuku.removeBinderReceivedListener(shizukuBinderListener);
        handler.removeCallbacks(shizukuWatchdog);
        try {
            if (privileged != null) Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true);
        } catch (Throwable ignored) {
        }
        stopSession();
        if (rtsp != null) rtsp.stop();
        super.onDestroy();
    }

    // --- Activity-facing API ---------------------------------------------------------------

    State getState() {
        return state;
    }

    /** Called on the main thread by the Activity once its SurfaceView is ready, and with null when
     * it goes away. The stream keeps running either way (audio included); only the picture has
     * nowhere to go while there is no surface. */
    void setSurface(Surface surface) {
        this.surface = surface;
        line(surface != null ? "surface attached" : "surface detached; stream keeps running");
        if (session != null) {
            session.setSurface(surface);
            if (surface != null) requestIdr();
        }
    }

    void sendKey(Integer hidKeycode) {
        if (rtsp != null && rtsp.uibc().isReady()) rtsp.uibc().sendKey(hidKeycode);
    }

    void sendPointer(int typeId, int x, int y) {
        if (rtsp != null && rtsp.uibc().isReady()) rtsp.uibc().sendPointer(typeId, x, y);
    }

    void sendKeyDown(int hid) {
        if (rtsp != null && rtsp.uibc().isReady()) rtsp.uibc().sendKeyDown(hid);
    }

    void sendKeyUp(int hid) {
        if (rtsp != null && rtsp.uibc().isReady()) rtsp.uibc().sendKeyUp(hid);
    }

    void sendScroll(boolean down, int notches) {
        if (rtsp != null && rtsp.uibc().isReady()) rtsp.uibc().sendScroll(down, notches);
    }

    boolean uibcReady() {
        return rtsp != null && rtsp.uibc().isReady();
    }

    // --- internals ---------------------------------------------------------------------------

    private void line(String s) {
        android.util.Log.i("WfdService", s);
    }

    private void setState(State s) {
        if (state == s) return;
        line("state: " + state + " -> " + s);
        state = s;
        String text;
        switch (s) {
            case LISTENING: text = "Esperando conexión..."; break;
            case CONNECTING: text = "Conectando..."; break;
            case STREAMING: text = "Transmitiendo"; break;
            default: text = "Inactivo";
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Receptor Miracast/DeX", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Receptor Miracast/DeX")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    private void start() {
        line("uid=" + Process.myUid() + " pkg=" + getPackageName()
                + " targetSdk=" + getApplicationInfo().targetSdkVersion);
        mgr = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (mgr == null) {
            line("no WifiP2pManager");
            return;
        }
        ch = mgr.initialize(this, Looper.getMainLooper(), null);
        line("channel: " + (ch != null));

        rtsp = new RtspSink(this::line);
        rtsp.setSessionCallbacks(
                this::openRtpPort,
                () -> handler.post(() -> {
                    setState(State.STREAMING);
                    startSession();
                }),
                () -> {
                    line("control session ended; resetting to listening");
                    resetToListening();
                });
        rtsp.setAuthorizer(this::authorizePeer);
        rtsp.start();
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
        handler.postDelayed(shizukuWatchdog, 3000);
        setState(State.LISTENING);

        mgr.startListening(ch, listener("startListening"));
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                mgr.startListening(ch, listener("startListening (renew)"));
                handler.postDelayed(this, 90000);
            }
        }, 90000);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                requestGroupInfo();
                handler.postDelayed(this, 1500);
            }
        }, 1500);
    }

    /** Required no-arg constructor (Shizuku falls back to this on older API levels). */
    private void setupShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                line("Shizuku not running (start it on the TV, or via adb once per reboot)");
                return;
            }
        } catch (Throwable t) {
            line("Shizuku not available: " + t);
            return;
        }
        if (Shizuku.isPreV11()) {
            line("Shizuku pre-v11, unsupported here");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindPrivilegedService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            line("Shizuku permission permanently denied by user");
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        }
    }

    private void bindPrivilegedService() {
        if (userServiceBound) return;
        userServiceBound = true;
        Shizuku.bindUserService(userServiceArgs, userServiceConnection);
    }

    /** Called by the Activity once the user has granted a permission it asked for: the first
     * startListening() ran without location access and failed, so run the startup steps again. */
    void retryStartup() {
        handler.post(() -> {
            if (mgr != null && ch != null) mgr.startListening(ch, listener("startListening (retry)"));
            setupShizuku();
        });
    }

    /** Called once the control socket for a session closes, for any reason (TEARDOWN, the source
     * walking away, a dropped P2P group). Drops any video surface state and goes back to
     * listening/re-scanning so the next connection attempt (from the same or another phone) is
     * handled without needing the Activity, or the whole process, to be relaunched. */
    private void resetToListening() {
        dialedOut = false;
        scanning = false;
        RtpReceiver orphan;
        synchronized (this) {
            orphan = pendingReceiver;
            pendingReceiver = null;
        }
        if (orphan != null) orphan.close();
        handler.post(this::stopSession);
        setState(State.LISTENING);
    }

    /** RTSP thread, right before M6 SETUP: the port must already be listening when the source
     * starts sending, or the first SPS/PPS and keyframe are lost. */
    private void openRtpPort() {
        RtpReceiver old;
        RtpReceiver opened = null;
        try {
            opened = new RtpReceiver(RtspSink.RTP_PORT, this::line, this::requestIdr);
            line("RTP port " + RtspSink.RTP_PORT + " open");
        } catch (Exception e) {
            line("could not open RTP port: " + e);
        }
        synchronized (this) {
            old = pendingReceiver;
            pendingReceiver = opened;
        }
        if (old != null) old.close();
    }

    /** Main thread, after M7 PLAY: hands the already-receiving port to a new player. */
    private void startSession() {
        stopSession();
        RtpReceiver receiver;
        synchronized (this) {
            receiver = pendingReceiver;
            pendingReceiver = null;
        }
        if (receiver == null) {
            line("no RTP port open; cannot start stream session");
            return;
        }
        session = new StreamSession(this, receiver, surface, this::line, this::requestIdr);
        line("stream session started");
        handler.postDelayed(statsReporter, 3000);
    }

    /** Main thread. */
    private void stopSession() {
        handler.removeCallbacks(statsReporter);
        if (session != null) {
            session.release();
            session = null;
            line("stream session stopped");
        }
    }

    /** Any thread. At most one IDR request per second; the RTSP write never runs on the caller. */
    private void requestIdr() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now - lastIdrRequestMs < 1000) return;
            lastIdrRequestMs = now;
        }
        RtspSink r = rtsp;
        if (r != null) new Thread(r::requestIdr, "wfd-idr").start();
    }

    private final Runnable statsReporter = new Runnable() {
        @Override public void run() {
            if (session == null) return;
            line(session.stats());
            handler.postDelayed(this, 3000);
        }
    };

    private void requestGroupInfo() {
        mgr.requestGroupInfo(ch, group -> lastGroup = group);
        mgr.requestConnectionInfo(ch, info -> {
            line("connectionInfo: " + info);
            if (!info.groupFormed) {
                dialedOut = false;
                if (state == State.CONNECTING || state == State.STREAMING) resetToListening();
            }
            // Retry the scan each poll (not just once): the first pass can land before the
            // phone's DHCP lease (and so its RTSP listener) is actually ready.
            if (info.groupFormed && !dialedOut && !scanning) {
                scanning = true;
                setState(State.CONNECTING);
                new Thread(this::findPeerAndConnect, "find-peer").start();
            }
        });
    }

    /** DeX's phone-side RTSP server listens on ITS OWN p2p IP, not ours. /proc/net/arp is access-
     * denied to a normal app on this Android version (confirmed live), and WifiP2pDevice carries no
     * IP - so knock on the whole /24 in parallel (short timeout each) and hand the live socket that
     * answers straight to RtspSink, with no second connect. */
    private void findPeerAndConnect() {
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 90; i <= 99; i++) order.add(i);
        for (int i = 2; i <= 254; i++) if (i < 90 || i > 99) order.add(i);

        java.net.Socket found = null;
        for (int batchStart = 0; batchStart < order.size() && found == null; batchStart += 8) {
            java.util.List<Integer> batch = order.subList(batchStart, Math.min(batchStart + 8, order.size()));
            final java.net.Socket[] slot = new java.net.Socket[1];
            java.util.List<Thread> threads = new java.util.ArrayList<>();
            for (int i : batch) {
                final String candidate = "192.168.49." + i;
                Thread t = new Thread(() -> {
                    try {
                        java.net.Socket s = new java.net.Socket();
                        s.connect(new java.net.InetSocketAddress(candidate, RtspSink.CONTROL_PORT), 250);
                        synchronized (slot) {
                            if (slot[0] == null) {
                                slot[0] = s;
                                line("found peer at " + candidate);
                            } else {
                                s.close();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                try {
                    t.join(400);
                } catch (InterruptedException ignored) {
                }
            }
            if (slot[0] != null) found = slot[0];
        }

        if (found != null) {
            dialedOut = true;
            rtsp.useSocket(found);
        } else {
            line("no peer answered on " + RtspSink.CONTROL_PORT + " across the /24; will retry");
        }
        scanning = false;
    }

    private WifiP2pManager.ActionListener listener(String name) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                line(name + " -> SUCCESS");
            }
            @Override public void onFailure(int reason) {
                line(name + " -> FAILED reason=" + reason);
            }
        };
    }
}
