package com.wfdtest.app;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * Sends UIBC (User Input Back Channel) generic key events to the WFD source (the phone), over the
 * TCP port it gives us in its M4 SET_PARAMETER (wfd_uibc_capability: ...;port=<n>). Packet framing
 * verified against a real, working sink implementation (albfan/miraclecast, src/uibc/
 * miracle-uibcctl.c): a 4-byte UIBC header (generic category = 0x00 0x00), a 2-byte total length,
 * then the Generic Input Body (1-byte type ID, 2-byte body length, then the body). The exact
 * encoding of the two key-code slots inside a GENERIC_KEY_DOWN/UP body is not independently
 * verified; this uses standard USB HID keyboard usage IDs, the most broadly-documented convention.
 */
class UibcSender {

    private static final int GENERIC_KEY_DOWN = 3;
    private static final int GENERIC_KEY_UP = 4;

    // USB HID keyboard/keypad usage IDs (USB HID Usage Tables, well-established and stable).
    static final int KEY_RIGHT_ARROW = 0x4F;
    static final int KEY_LEFT_ARROW = 0x50;
    static final int KEY_DOWN_ARROW = 0x51;
    static final int KEY_UP_ARROW = 0x52;
    static final int KEY_ENTER = 0x28;
    static final int KEY_ESCAPE = 0x29;
    static final int KEY_LEFT_CTRL = 0xE0;
    static final int KEY_LEFT_ALT = 0xE2;

    void sendKeyDown(int keycode) {
        send(buildKeyPacket(GENERIC_KEY_DOWN, keycode));
    }

    void sendKeyUp(int keycode) {
        send(buildKeyPacket(GENERIC_KEY_UP, keycode));
    }

    static final int PORT = 19001;

    private final Consumer<String> log;
    private Socket socket;
    private OutputStream out;
    private ServerSocket server;

    UibcSender(Consumer<String> log) {
        this.log = log;
    }

    /** M4 never gave us a port to dial - matching the pattern already seen with RTSP, Samsung's
     * DeX instead expects US to listen and the source to connect to US. Open that server once, at
     * startup, and advertise PORT in our M3 response's wfd_uibc_capability instead of "none". */
    void listen() {
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                log.accept("UIBC listening on " + PORT);
                while (true) {
                    Socket s = server.accept();
                    socket = s;
                    out = s.getOutputStream();
                    log.accept("UIBC: source connected from " + s.getInetAddress());
                }
            } catch (Exception e) {
                log.accept("UIBC listen ended: " + e);
            }
        }, "uibc-listen").start();
    }

    /** Kept in case a future negotiation actually does hand us a real port to dial, unlike so far. */
    void connect(String ip, int port) {
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 3000);
                socket = s;
                out = s.getOutputStream();
                log.accept("UIBC connected to " + ip + ":" + port);
            } catch (Exception e) {
                log.accept("UIBC connect failed: " + e);
            }
        }, "uibc-connect").start();
    }

    boolean isReady() {
        return out != null;
    }

    void sendKey(int keycode) {
        send(buildKeyPacket(GENERIC_KEY_DOWN, keycode));
        send(buildKeyPacket(GENERIC_KEY_UP, keycode));
    }

    // Generic input type IDs (WFD spec; same order as miraclecast's miracle-uibcctl.h enum).
    static final int TOUCH_DOWN = 0;  // also "left mouse button down"
    static final int TOUCH_UP = 1;
    static final int TOUCH_MOVE = 2;
    private static final int VERTICAL_SCROLL = 6;

    /** One pointer event at (x, y), in pixels of the source's own screen. Layout from
     * miraclecast's getUIBCGenericTouchPacket: 4-byte UIBC header, typeId(1), length(2), number of
     * pointers(1), then per pointer id(1) x(2) y(2); the whole message padded to an even length. */
    void sendPointer(int typeId, int x, int y) {
        sendPointers(typeId, new int[][] {{0, x, y}});
    }

    /** Same message for several fingers at once: each entry is {pointer id, x, y}. */
    void sendPointers(int typeId, int[][] pointers) {
        int genericLen = pointers.length * 5 + 1;
        int total = genericLen + 7;
        if (total % 2 != 0) total++;
        byte[] b = new byte[total];
        b[2] = (byte) (total >> 8);
        b[3] = (byte) total;
        b[4] = (byte) typeId;
        b[5] = (byte) (genericLen >> 8);
        b[6] = (byte) genericLen;
        b[7] = (byte) pointers.length;
        for (int i = 0; i < pointers.length; i++) {
            int o = 8 + i * 5;
            b[o] = (byte) pointers[i][0];
            b[o + 1] = (byte) (pointers[i][1] >> 8);
            b[o + 2] = (byte) pointers[i][1];
            b[o + 3] = (byte) (pointers[i][2] >> 8);
            b[o + 4] = (byte) pointers[i][2];
        }
        send(b);
    }

    /** Mouse-wheel scroll, in notches. Scroll word: unit(2 bits, 01 = mouse notch), direction(1 bit),
     * amount(13 bits) - the same layout miraclecast documents for its scroll packets. */
    void sendScroll(boolean down, int notches) {
        int word = (1 << 14) | ((down ? 1 : 0) << 13) | (notches & 0x1FFF);
        byte[] b = new byte[9];
        b[2] = 0;
        b[3] = 9;
        b[4] = (byte) VERTICAL_SCROLL;
        b[5] = 0;
        b[6] = 2;
        b[7] = (byte) (word >> 8);
        b[8] = (byte) word;
        send(b);
    }

    // Callers are the UI thread (remote key presses), and Android forbids network writes there
    // (NetworkOnMainThreadException). Until now that exception was caught below and set out = null,
    // which switched the channel off for good after the first key press - so no remote key ever
    // reached the phone. One dedicated thread keeps packets in order and off the UI thread.
    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "uibc-send"));

    private void send(byte[] packet) {
        sender.execute(() -> {
            OutputStream o = out;
            if (o == null) return;
            try {
                o.write(packet);
                o.flush();
            } catch (Exception e) {
                log.accept("UIBC send failed: " + e);
                if (out == o) out = null;
            }
        });
    }

    /** Generic Input Body for a key event: typeId(1) + length(2) + reserved(1) + keycode1(2) + keycode2(2). */
    private static byte[] buildKeyPacket(int typeId, int keycode) {
        int genericLen = 5; // reserved(1) + keycode1(2) + keycode2(2)
        int uibcBodyLen = genericLen + 7; // 4-byte UIBC header + 1 typeId + 2 length = 7
        byte[] b = new byte[uibcBodyLen];
        b[0] = 0x00; // UIBC header: version/T/reserved
        b[1] = 0x00; // UIBC header: input category = GENERIC (0)
        b[2] = (byte) ((uibcBodyLen >> 8) & 0xFF);
        b[3] = (byte) (uibcBodyLen & 0xFF);
        b[4] = (byte) typeId;
        b[5] = (byte) ((genericLen >> 8) & 0xFF);
        b[6] = (byte) (genericLen & 0xFF);
        b[7] = 0x00; // reserved
        b[8] = (byte) ((keycode >> 8) & 0xFF);
        b[9] = (byte) (keycode & 0xFF);
        b[10] = 0x00; // key code 2 (unused)
        b[11] = 0x00;
        return b;
    }
}
