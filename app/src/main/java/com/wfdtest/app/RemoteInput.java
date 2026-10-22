package com.wfdtest.app;

import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.function.Consumer;

/**
 * Turns the TV remote into a mouse + keyboard for the phone's desktop (Samsung DeX), over UIBC.
 * One fixed key map, no modes and no hidden state.
 *
 * STATUS (v1.0): EXPERIMENTAL. Tested live against Chrome on DeX by comparing screenshots, the only
 * input confirmed to reach and act on the phone is a printable-key event whose code is plain ASCII
 * (Space, 0x20, scrolls a page down). Pointer moves, clicks, wheel, zoom and the special-key codes
 * used below (arrows, Ctrl, Alt, Esc as USB-HID usages) produced no visible effect; the phone's
 * accepted code table for those keys is still unknown. See REQUIREMENTS.md.
 *
 *
 *   Arrows            move the pointer (holding accelerates)
 *   OK                left click (hold = drag)
 *   Back              go back (Alt+Left); held for half a second = Esc
 *   Green / Yellow    scroll up / down  (Rewind / Fast-forward do the same)
 *   Red / Blue        zoom out / in     (Ctrl + mouse wheel, the desktop convention)
 *
 * Coordinates are in pixels of the phone's own screen (1920x1080).
 */
final class RemoteInput {

    interface Output {
        void pointer(int type, int x, int y);
        void wheel(boolean down, int notches);
        void keyDown(int hid);
        void keyUp(int hid);
        void key(int hid);
    }

    interface Feedback {
        void pointerAt(int x, int y, boolean pressed);
        void say(String text);
    }

    static final int SOURCE_WIDTH = 1920, SOURCE_HEIGHT = 1080;
    private static final int KEY_SPACE = 0x20;

    private static final long SCROLL_INTERVAL_MS = 100;
    private static final long ZOOM_INTERVAL_MS = 250;
    private static final int BACK_HELD_FOR_ESC = 10; // key repeats, about half a second

    private final Output out;
    private final Feedback feedback;
    private final Consumer<String> log;

    private int x = SOURCE_WIDTH / 2, y = SOURCE_HEIGHT / 2;
    private boolean pressed;
    private long lastScrollMs, lastZoomMs;

    RemoteInput(Output out, Feedback feedback, Consumer<String> log) {
        this.out = out;
        this.feedback = feedback;
        this.log = log;
    }

    /** Returns true if the key was used. */
    boolean onKeyDown(KeyEvent e) {
        int repeat = e.getRepeatCount();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0, repeat); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0, repeat); return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (repeat == 0) {
                    pressed = true;
                    log.accept("click down at " + x + "," + y);
                    out.pointer(UibcSender.TOUCH_DOWN, x, y);
                    feedback.pointerAt(x, y, true);
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (repeat == 0) {
                    log.accept("back (Alt+Left)");
                    out.keyDown(UibcSender.KEY_LEFT_ALT);
                    out.key(UibcSender.KEY_LEFT_ARROW);
                    out.keyUp(UibcSender.KEY_LEFT_ALT);
                    feedback.say("Atrás");
                } else if (repeat == BACK_HELD_FOR_ESC) {
                    log.accept("Esc");
                    out.key(UibcSender.KEY_ESCAPE);
                    feedback.say("Esc");
                }
                return true;

            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                scroll(false);
                return true;
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                scroll(true);
                return true;

            case KeyEvent.KEYCODE_PROG_RED:
                zoom(false);
                return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
                zoom(true);
                return true;
        }
        return false;
    }

    boolean onKeyUp(KeyEvent e) {
        int code = e.getKeyCode();
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && pressed) {
            pressed = false;
            log.accept("click up at " + x + "," + y);
            out.pointer(UibcSender.TOUCH_UP, x, y);
            feedback.pointerAt(x, y, false);
            return true;
        }
        return false;
    }

    private void move(int dx, int dy, int repeat) {
        int step = 8 + Math.min(repeat, 40) * 3;
        x = Math.max(0, Math.min(SOURCE_WIDTH - 1, x + dx * step));
        y = Math.max(0, Math.min(SOURCE_HEIGHT - 1, y + dy * step));
        out.pointer(UibcSender.TOUCH_MOVE, x, y);
        feedback.pointerAt(x, y, pressed);
    }

    // A held button repeats many times a second; without a rate limit one long press scrolled the
    // whole page.
    private void scroll(boolean down) {
        long now = SystemClock.uptimeMillis();
        if (now - lastScrollMs < SCROLL_INTERVAL_MS) return;
        lastScrollMs = now;
        log.accept("scroll " + (down ? "down" : "up"));
        if (down) out.key(KEY_SPACE); // the one key confirmed to work: browsers scroll a page on Space
        else out.wheel(false, 1);
        feedback.say(down ? "Scroll abajo" : "Scroll arriba");
    }

    private void zoom(boolean in) {
        long now = SystemClock.uptimeMillis();
        if (now - lastZoomMs < ZOOM_INTERVAL_MS) return;
        lastZoomMs = now;
        log.accept("zoom " + (in ? "in" : "out"));
        out.keyDown(UibcSender.KEY_LEFT_CTRL);
        out.wheel(!in, 1); // wheel up = zoom in
        out.keyUp(UibcSender.KEY_LEFT_CTRL);
        feedback.say(in ? "Zoom +" : "Zoom −");
    }
}
