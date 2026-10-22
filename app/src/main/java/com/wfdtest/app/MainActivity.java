package com.wfdtest.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * Thin UI shell: all the actual Miracast/DeX sink logic (P2P listening, RTSP, UIBC, Shizuku)
 * lives in WfdService, which keeps running whether or not this Activity is on screen. This
 * Activity's only two jobs are showing the decoded video on a SurfaceView and forwarding the TV
 * remote's D-pad to the service as UIBC key events.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1;
    private static final int REQ_BACKGROUND_LOCATION = 2;

    private TextView setupPanel;
    private SurfaceView surfaceView;
    private WfdService service;
    private boolean bound = false;
    private boolean askedLocation, askedBackgroundLocation;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            updateSetupPanel();
            handler.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((WfdService.LocalBinder) binder).getService();
            bound = true;
            service.setApprovalListener(approvalListener);
            attachSurfaceIfReady();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // The action bar ("WFD Test" header) was taking a real strip off the top of the video -
        // no AppCompat here, so hide it directly, plus go edge-to-edge/fullscreen.
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pointerView = new PointerView(this);
        root.addView(pointerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setupPanel = new TextView(this);
        setupPanel.setTextSize(24);
        setupPanel.setTextColor(0xFFFFFFFF);
        setupPanel.setBackgroundColor(0xE6101820);
        setupPanel.setPadding(64, 48, 64, 48);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = android.view.Gravity.CENTER;
        root.addView(setupPanel, panelParams);

        setContentView(root);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                attachSurfaceIfReady();
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (bound && service != null) service.setSurface(null);
            }
        });

        Intent serviceIntent = new Intent(this, WfdService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, connection, 0);
    }

    private android.app.AlertDialog approvalDialog;

    private final WfdService.ApprovalListener approvalListener = new WfdService.ApprovalListener() {
        @Override
        public void ask(String deviceName, java.util.function.IntConsumer answer) {
            runOnUiThread(() -> {
                dismissApprovalDialog();
                approvalDialog = new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("¿Permitir la conexión?")
                        .setMessage("\"" + deviceName + "\" quiere mostrar su pantalla en esta tele.")
                        .setPositiveButton("Aceptar", (d, w) -> answer.accept(1))
                        .setNeutralButton("Aceptar siempre", (d, w) -> answer.accept(2))
                        .setNegativeButton("Rechazar", (d, w) -> answer.accept(0))
                        .setOnCancelListener(d -> answer.accept(0))
                        .show();
            });
        }

        @Override
        public void cancel() {
            runOnUiThread(MainActivity.this::dismissApprovalDialog);
        }
    };

    private void dismissApprovalDialog() {
        if (approvalDialog != null) {
            approvalDialog.dismiss();
            approvalDialog = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestMissingPermissions();
        handler.post(statusUpdater);
        if (bound && service != null) service.setApprovalListener(approvalListener);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusUpdater);
        // With the app off screen nobody can answer, so unknown devices are refused instead.
        if (bound && service != null) service.setApprovalListener(null);
        dismissApprovalDialog();
        super.onPause();
    }

    private void confirmForgetTrusted() {
        if (!bound || service == null) return;
        int n = service.trustedCount();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Dispositivos de confianza")
                .setMessage(n == 0 ? "No hay ningún dispositivo guardado."
                        : "Hay " + n + " dispositivo(s) que se conectan sin preguntar. ¿Olvidarlos todos?")
                .setPositiveButton(n == 0 ? "Cerrar" : "Olvidar todos", (d, w) -> {
                    if (n > 0) service.forgetTrusted();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Standard runtime-permission flow. On API 30+ background location must be requested on its
     * own, after foreground location, and each request is made once (no nagging loop on denial). */
    private void requestMissingPermissions() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (askedLocation) return;
            askedLocation = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        } else if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            if (askedBackgroundLocation) return;
            askedBackgroundLocation = true;
            requestPermissions(new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQ_BACKGROUND_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (bound && service != null) service.retryStartup();
        requestMissingPermissions();
    }

    private static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean shizukuAllowed() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shows what is still missing, in plain Spanish, until a stream is actually on screen. */
    private void updateSetupPanel() {
        WfdService.State state = bound && service != null ? service.getState() : null;
        if (state == WfdService.State.STREAMING) {
            setupPanel.setVisibility(View.GONE);
            if (!legendShown) {
                legendShown = true;
                pointerView.say(LEGEND, 12000);
            }
            return;
        }
        legendShown = false;
        boolean location = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        boolean running = shizukuRunning();
        boolean allowed = running && shizukuAllowed();

        StringBuilder text = new StringBuilder("Receptor Miracast / Samsung DeX\n\n");
        text.append(location ? "[OK] " : "[ ] ").append("Permiso de ubicación (lo necesita Wi-Fi Direct)\n");
        text.append(background ? "[OK] " : "[ ] ")
                .append("Ubicación \"todo el tiempo\" (si no, deja de verse cuando la tele se apaga)\n");
        text.append(running ? "[OK] " : "[ ] ").append("Shizuku en marcha\n");
        text.append(allowed ? "[OK] " : "[ ] ").append("Shizuku autorizado para esta app\n\n");

        if (!location || !background) {
            text.append("Acepta el aviso de permisos que aparece en pantalla. Si no sale, ve a "
                    + "Ajustes > Apps > Receptor DeX > Permisos > Ubicación > \"Permitir siempre\".");
        } else if (!running) {
            String status = bound && service != null ? service.shizukuStartStatus() : "";
            text.append("Esta app arranca Shizuku sola (también tras reiniciar la tele). ");
            text.append(status.isEmpty() ? "Un momento..." : status);
            text.append("\nSolo la primera vez, la tele preguntará \"¿Permitir depuración por USB?\": "
                    + "marca \"Permitir siempre desde este ordenador\" y pulsa \"Permitir\" "
                    + "(cuidado: el foco cae en \"Cancelar\"). Si no lo ves, pulsa Atrás.");
        } else if (!allowed) {
            text.append("Acepta el aviso de Shizuku para autorizar esta app.");
        } else {
            text.append("Todo listo. En el móvil abre Samsung DeX y elige \"Philips OLED718\".\n"
                    + "Cada móvil nuevo tendrá que ser aceptado aquí, con esta app abierta.\n"
                    + "Tecla Menú del mando: gestionar los dispositivos de confianza.\n"
                    + "Mando durante la sesión: pulsa Info para ver los botones.");
        }
        setupPanel.setText(text);
        setupPanel.setVisibility(View.VISIBLE);
    }

    private void attachSurfaceIfReady() {
        if (bound && service != null && surfaceView.getHolder().getSurface().isValid()) {
            Surface surface = surfaceView.getHolder().getSurface();
            service.setSurface(surface);
        }
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    private static final String LEGEND =
            "Mando (EXPERIMENTAL: puede que el móvil no responda)\n"
            + "Flechas: puntero      OK: clic      Atrás: volver\n"
            + "Verde / Amarillo: scroll arriba / abajo\n"
            + "Rojo / Azul: zoom menos / más\n"
            + "Info: ver esta ayuda";

    private boolean legendShown;
    private PointerView pointerView;
    private RemoteInput remote;

    private RemoteInput remote() {
        if (remote == null) {
            remote = new RemoteInput(new RemoteInput.Output() {
                @Override public void pointer(int type, int x, int y) { service.sendPointer(type, x, y); }
                @Override public void wheel(boolean down, int notches) { service.sendScroll(down, notches); }
                @Override public void keyDown(int hid) { service.sendKeyDown(hid); }
                @Override public void keyUp(int hid) { service.sendKeyUp(hid); }
                @Override public void key(int hid) { service.sendKey(hid); }
            }, new RemoteInput.Feedback() {
                @Override public void pointerAt(int x, int y, boolean pressed) { pointerView.show(x, y, pressed); }
                @Override public void say(String text) { pointerView.say(text, 1500); }
            }, s -> android.util.Log.i("RemoteInput", s));
        }
        return remote;
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            confirmForgetTrusted();
            return true;
        }
        if (bound && service != null && service.uibcReady()) {
            if (keyCode == android.view.KeyEvent.KEYCODE_INFO) {
                pointerView.say(LEGEND, 12000);
                return true;
            }
            if (remote().onKeyDown(event)) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (bound && service != null && remote().onKeyUp(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /** Draws the remote's pointer as a ring that fades after a moment, plus short messages, so it
     * is clear the remote is being heard even when the phone draws nothing itself. */
    private final class PointerView extends View {
        private final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int x, y;
        private boolean pressed, ringVisible;
        private String text = "";
        private final Runnable hideRing = () -> { ringVisible = false; invalidate(); };
        private final Runnable clearText = () -> { text = ""; invalidate(); };

        PointerView(android.content.Context c) {
            super(c);
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(5);
            textPaint.setTextSize(30);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setShadowLayer(8, 0, 0, 0xFF000000);
            textPaint.setTextAlign(android.graphics.Paint.Align.RIGHT);
        }

        void show(int sourceX, int sourceY, boolean isPressed) {
            x = sourceX;
            y = sourceY;
            pressed = isPressed;
            ringVisible = true;
            invalidate();
            removeCallbacks(hideRing);
            postDelayed(hideRing, 2000);
        }

        void say(String message, long millis) {
            text = message;
            invalidate();
            removeCallbacks(clearText);
            postDelayed(clearText, millis);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (!text.isEmpty()) {
                String[] lines = text.split("\n");
                float lineHeight = 42;
                float base = getHeight() - 50 - (lines.length - 1) * lineHeight;
                for (int i = 0; i < lines.length; i++) {
                    canvas.drawText(lines[i], getWidth() - 40, base + i * lineHeight, textPaint);
                }
            }
            if (!ringVisible) return;
            float sx = x * getWidth() / (float) RemoteInput.SOURCE_WIDTH;
            float sy = y * getHeight() / (float) RemoteInput.SOURCE_HEIGHT;
            ring.setColor(pressed ? 0xFFFF5252 : 0xFF7DBEFF);
            canvas.drawCircle(sx, sy, pressed ? 14 : 22, ring);
        }
    }
}
