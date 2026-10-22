package com.wfdtest.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Looper;
import android.util.Log;

/**
 * Runs as a Shizuku UserService: a process with uid 2000 (shell), the identity that holds
 * CONFIGURE_WIFI_DISPLAY. The permission itself is checked by calling UID, but WifiP2pManager also
 * checks that the caller's package name belongs to that uid - hence the ShellContext wrapper below.
 */
public class WfdUserService extends IWfdPrivileged.Stub {

    private static final String TAG = "WfdUserService";
    private final Context context;

    /** Confirmed necessary again here (same finding as the app's very first probes, back when it
     * ran as a raw app_process): WifiP2pManager.initialize() checks that the calling package
     * genuinely belongs to the calling uid, and uid 2000's real package is com.android.shell, not
     * whatever this app is named - without this, WifiP2pService logs "Package com.wfdtest.app
     * does not belong to 2000" and silently refuses to set up the P2P interface. */
    private static class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public String getAttributionTag() { return null; }

        @Override public android.content.AttributionSource getAttributionSource() {
            return android.content.AttributionSource.myAttributionSource();
        }
    }

    /** Required no-arg constructor (Shizuku falls back to this on older API levels). */
    public WfdUserService() {
        this.context = null;
        Log.i(TAG, "constructor (no context)");
    }

    /** Preferred constructor, Shizuku API v13+: context is created via createPackageContextAsUser,
     * already running with this process's own (shell) identity. (Would need @Keep if minification
     * were ever enabled, since R8 can't see this constructor is invoked via reflection.) */
    public WfdUserService(Context context) {
        this.context = context != null ? new ShellContext(context) : null;
        Log.i(TAG, "constructor with context: " + context);
    }

    @Override
    public void destroy() {
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    @Override
    public boolean advertiseSink(String deviceName, int controlPort) {
        if (context == null) {
            Log.e(TAG, "no context available (pre-v13 Shizuku?)");
            return false;
        }
        try {
            Looper.prepare(); // ActionListener callbacks need a Looper on this thread
            WifiP2pManager mgr = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (mgr == null) {
                Log.e(TAG, "no WifiP2pManager");
                return false;
            }
            WifiP2pManager.Channel ch = mgr.initialize(context, Looper.myLooper(), null);
            if (ch == null) {
                Log.e(TAG, "channel init failed");
                return false;
            }

            WifiP2pWfdInfo wfd = new WifiP2pWfdInfo();
            wfd.setEnabled(true);
            wfd.setDeviceType(1); // WFD_SOURCE_OR_PRIMARY_SINK
            wfd.setSessionAvailable(true);
            wfd.setControlPort(controlPort);
            wfd.setMaxThroughput(50);

            WifiP2pManager.ActionListener noop = new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    Log.i(TAG, "action success");
                }
                @Override public void onFailure(int reason) {
                    Log.w(TAG, "action failed, reason=" + reason);
                }
            };
            mgr.setWfdInfo(ch, wfd, noop);
            try {
                java.lang.reflect.Method setName = WifiP2pManager.class.getMethod(
                        "setDeviceName", WifiP2pManager.Channel.class, String.class,
                        WifiP2pManager.ActionListener.class);
                setName.invoke(mgr, ch, deviceName, noop);
            } catch (Throwable t) {
                Log.w(TAG, "setDeviceName not available: " + t);
            }

            try {
                java.lang.reflect.Method m = WifiP2pManager.class.getMethod("setMiracastMode", int.class);
                m.invoke(mgr, 2); // sink
            } catch (Throwable t) {
                Log.w(TAG, "setMiracastMode not available: " + t);
            }

            Log.i(TAG, "advertiseSink done: " + deviceName + " port=" + controlPort);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "advertiseSink failed", t);
            return false;
        }
    }
}
