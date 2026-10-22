package com.wfdtest.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * Starts the Shizuku server without anybody opening Shizuku. Shizuku's own non-root start is just
 * "run libshizuku.so as the shell user" (its Start screen shows exactly that command), and the TV
 * already runs adbd on TCP 5555, so this app connects to it on localhost as an ordinary ADB client
 * and runs that command itself. The first connection makes the TV ask once whether to trust this
 * app's ADB key ("Siempre permitir"); after that it is fully automatic, including after a reboot.
 */
final class ShizukuStarter {

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int ADBD_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 60_000; // time for the person to accept the key prompt

    private ShizukuStarter() {
    }

    /** Returns what the starter printed. Throws with a message meant to be shown to the user. */
    static String start(Context context) throws Exception {
        ApplicationInfo shizuku;
        try {
            shizuku = context.getPackageManager().getApplicationInfo(SHIZUKU_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Shizuku no está instalado en la tele.");
        }
        String command = shizuku.nativeLibraryDir + "/libshizuku.so";

        File privateKey = new File(context.getFilesDir(), "adbkey");
        File publicKey = new File(context.getFilesDir(), "adbkey.pub");
        if (!privateKey.exists() || !publicKey.exists()) AdbKeyPair.generate(privateKey, publicKey);
        AdbKeyPair keys = AdbKeyPair.read(privateKey, publicKey);

        try (Dadb adb = Dadb.create("127.0.0.1", ADBD_PORT, keys, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)) {
            return adb.shell(command).getAllOutput();
        } catch (java.net.ConnectException e) {
            throw new IllegalStateException("La depuración por red de la tele no está activa (puerto "
                    + ADBD_PORT + "). Actívala en Opciones de desarrollador.");
        }
    }
}
