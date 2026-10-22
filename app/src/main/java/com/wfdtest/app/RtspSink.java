package com.wfdtest.app;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * A minimal Wi-Fi Display (Miracast) sink RTSP control server, following the WFA WFD spec's
 * M1-M7 handshake (verified against Microsoft's published MS-WFDPE message examples). Runs on
 * the control port we advertised via WifiP2pWfdInfo (7236). Once M7 (PLAY) succeeds, the source
 * starts sending the RTP/MPEG2-TS stream over UDP to the port we declare here (19000).
 */
class RtspSink {

    static final int CONTROL_PORT = 7236;
    static final int RTP_PORT = 19000;
    // H264_profile/H264_level are bitmasks, not single values - "01"/"01" only set bit0 of each
    // (CBP - baseline profile - at level 3.1 only), the lowest-quality combination there is. That
    // forces the SOURCE's encoder into baseline profile (no B-frames, CAVLC-only entropy coding,
    // much less efficient compression than High Profile at the same bitrate), which is exactly
    // what produces visible blockiness under motion even with a perfect, loss-free radio link.
    // Fixed by offering two separate codec tuples - CBP and CHP (constrained high profile) - with
    // the level bitmask broadened to 0x1f (every WFD-defined level, 3.1 through 4.2) so the source
    // picks whatever it supports best instead of being told "only exactly 4.2 counts". Confirmed
    // this TV's own hardware decoder (c2.mtk.avc.decoder, queried live via MediaCodecList) supports
    // AVC up to level 5.2 and 4096x2304 @ up to 240 Mbps - nowhere near the bottleneck; WFD's own
    // level 4.2 ceiling is what we're bumping against, not this receiver's hardware. CEA/VESA/HH
    // bitmaps copied from miraclecast's own verified-working
    // res/sinkctl.protocol-extension.example rather than invented, since those bitmaps have real
    // reserved-bit constraints a naive FFFFFFFF can violate.
    private static final String VIDEO_FORMATS =
            "00 00 01 1f 0001bdeb 051557ff 00000fff 10 0000 001f 11 0780 0438"
                    + ", 02 1f 0001bdeb 155557ff 00000fff 10 0000 001f 11 0780 0438";
    private static final String AUDIO_CODECS = "LPCM 00000003 00";

    private final Consumer<String> log;
    private ServerSocket server;
    private volatile boolean running;
    private int cseq = 1;
    private final UibcSender uibc;
    private volatile OutputStream controlOut;
    private volatile String controlUrl;
    private volatile Runnable onBeforeSetup;
    private volatile Runnable onEstablished;
    private volatile Runnable onEnded;
    private volatile java.util.function.BooleanSupplier authorizer;

    /** Asked on the session thread, right after the source's first message and before anything is
     * answered, whether this connection may go on. May block (it waits for the person at the TV). */
    void setAuthorizer(java.util.function.BooleanSupplier authorizer) {
        this.authorizer = authorizer;
    }

    RtspSink(Consumer<String> log) {
        this.log = log;
        this.uibc = new UibcSender(log);
    }

    /** Lets WfdService track the session lifecycle. onBeforeSetup runs synchronously on this thread
     * right before M6 SETUP is sent, so the RTP port is already open when the source starts sending
     * (Google's AOSP WifiDisplaySink likewise connected its RTP session before sending SETUP) -
     * the very first packets carry the SPS/PPS and keyframe the decoder needs. onEstablished fires
     * once M7/PLAY succeeds; onEnded when the control socket closes for any reason. */
    void setSessionCallbacks(Runnable onBeforeSetup, Runnable onEstablished, Runnable onEnded) {
        this.onBeforeSetup = onBeforeSetup;
        this.onEstablished = onEstablished;
        this.onEnded = onEnded;
    }

    /** Lets the app send remote-button key events once a UIBC channel is up. */
    UibcSender uibc() {
        return uibc;
    }

    void start() {
        running = true;
        uibc.listen();
        new Thread(this::acceptLoop, "rtsp-sink").start();
    }

    /** Takes over an already-connected socket (e.g. from a /24 knock-scan that found the peer) and
     * runs the same RTSP sink session logic on it, with no extra connect. */
    void useSocket(Socket s) {
        new Thread(() -> session(s), "rtsp-session-found").start();
    }

    /** Standard WFD recovery: ask the source for a fresh IDR frame right now, instead of the
     * picture staying corrupted (blocky "ruido") until the source's next spontaneous one. A real
     * sink does exactly this on detected packet loss - it's the single biggest thing separating a
     * basic sink from a robust one. Sent as its own RTSP request over the same control connection. */
    void requestIdr() {
        OutputStream o = controlOut;
        String u = controlUrl;
        if (o == null || u == null) return;
        try {
            String body = "wfd_idr_request\r\n";
            send(o, "SET_PARAMETER " + u + " RTSP/1.0\r\nCSeq: " + (cseq++)
                    + "\r\nContent-Type: text/parameters\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body);
        } catch (IOException e) {
            log.accept("requestIdr failed: " + e);
        }
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket(CONTROL_PORT);
            log.accept("RTSP sink listening on " + CONTROL_PORT);
            while (running) {
                Socket s = server.accept();
                log.accept("RTSP: source connected from " + s.getInetAddress());
                new Thread(() -> session(s), "rtsp-session").start();
            }
        } catch (IOException e) {
            if (running) log.accept("RTSP accept failed: " + e);
        }
    }

    private void session(Socket s) {
        try (Socket sock = s;
             InputStream rawIn = new BufferedInputStream(sock.getInputStream());
             OutputStream out = sock.getOutputStream()) {

            // M1: source asks OPTIONS, we answer with the methods we support.
            RtspMsg m1 = RtspMsg.read(rawIn);
            if (m1 == null) {
                log.accept("peer closed the socket before sending anything (M1 never arrived)");
                return;
            }
            log.accept("M1 <- " + m1.method + " " + m1.uri);
            java.util.function.BooleanSupplier auth = authorizer;
            if (auth != null && !auth.getAsBoolean()) {
                send(out, "RTSP/1.0 403 Forbidden\r\nCSeq: " + m1.cseq + "\r\n\r\n");
                log.accept("connection refused");
                return;
            }
            send(out,"RTSP/1.0 200 OK\r\nCSeq: " + m1.cseq
                    + "\r\nPublic: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n");

            // M2: we ask OPTIONS back, source answers (role reversal, same TCP connection).
            send(out, "OPTIONS * RTSP/1.0\r\nCSeq: " + (cseq++) + "\r\nRequire: org.wfa.wfd1.0\r\n\r\n");
            RtspMsg m2r = RtspMsg.read(rawIn);
            log.accept("M2 -> OPTIONS, got " + m2r.statusLine);

            // M3: source asks GET_PARAMETER (which capabilities it wants); we answer with ours.
            // Samsung's DeX adds its own wfd_sec_* queries (seen live in the phone's own log:
            // getparams=[wfd_sec_dex_support, wfd_sec_dex_mouse_support, wfd_sec_tv_ble_mac]) on top
            // of the standard WFA ones - answer those too, or DeX has no reason to proceed past M3.
            RtspMsg m3 = RtspMsg.read(rawIn);
            if (m3 == null) {
                log.accept("peer closed the socket after M2 (M3 never arrived)");
                return;
            }
            log.accept("M3 <- GET_PARAMETER, wants: " + m3.body.replace("\r\n", " | "));
            StringBuilder b = new StringBuilder();
            b.append("wfd_video_formats: ").append(VIDEO_FORMATS).append("\r\n");
            b.append("wfd_audio_codecs: ").append(AUDIO_CODECS).append("\r\n");
            b.append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast ").append(RTP_PORT).append(" 0 mode=play\r\n");
            b.append("wfd_content_protection: none\r\n");
            if (m3.body.contains("wfd_sec_dex_support")) b.append("wfd_sec_dex_support: 1\r\n");
            if (m3.body.contains("wfd_sec_dex_mouse_support")) b.append("wfd_sec_dex_mouse_support: 1\r\n");
            if (m3.body.contains("wfd_sec_tv_ble_mac")) b.append("wfd_sec_tv_ble_mac: none\r\n");
            // UIBC (remote-as-keyboard): we have no port of our own, matching the pattern already
            // seen with RTSP itself - we are the one who dials out, once the source gives us its port.
            // (Only one wfd_uibc_capability line is ever sent - duplicating it with "none" first
            // confused nothing visibly, but is not valid WFD and better not to risk it.)
            if (m3.body.contains("wfd_uibc_capability")) {
                // The source only honours the input types listed here, and echoes the ones it chose in
                // M4. Advertising just "Keyboard" is why the phone ignored the mouse wheel and zoom.
                b.append("wfd_uibc_capability: input_category_list=GENERIC; "
                        + "generic_cap_list=Keyboard, Mouse, SingleTouch, MultiTouch; "
                        + "hidc_cap_list=none; port=").append(UibcSender.PORT).append("\r\n");
            } else {
                b.append("wfd_uibc_capability: none\r\n");
            }
            String body = b.toString();
            send(out, "RTSP/1.0 200 OK\r\nCSeq: " + m3.cseq + "\r\nContent-Type: text/parameters\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body);

            // M4: source sets the chosen format, presentation URL and its RTP ports; we just ack.
            RtspMsg m4 = RtspMsg.read(rawIn);
            if (m4 == null) {
                log.accept("peer closed the socket after M3 (M4 never arrived)");
                return;
            }
            log.accept("M4 <- SET_PARAMETER: " + m4.body.replace("\r\n", " | "));
            send(out, "RTSP/1.0 200 OK\r\nCSeq: " + m4.cseq + "\r\n\r\n");
            Integer uibcPort = extractUibcPort(m4.body);
            if (uibcPort != null) {
                log.accept("source's UIBC port: " + uibcPort);
                uibc.connect(sock.getInetAddress().getHostAddress(), uibcPort);
            }

            // M5: source triggers us to SETUP; we read the stream URL out of M4's presentation_URL.
            RtspMsg m5 = RtspMsg.read(rawIn);
            if (m5 == null) {
                log.accept("peer closed the socket after M4 (M5 never arrived)");
                return;
            }
            log.accept("M5 <- " + m5.body.replace("\r\n", " | "));
            send(out, "RTSP/1.0 200 OK\r\nCSeq: " + m5.cseq + "\r\n\r\n");

            String url = extractPresentationUrl(m4.body);
            if (url == null) url = "rtsp://" + sock.getInetAddress().getHostAddress() + "/wfd1.0/streamid=0";
            log.accept("using stream url: " + url);

            // M6: we SETUP the stream we were triggered to set up.
            if (onBeforeSetup != null) onBeforeSetup.run();
            send(out, "SETUP " + url + " RTSP/1.0\r\nCSeq: " + (cseq++)
                    + "\r\nTransport: RTP/AVP/UDP;unicast;client_port=" + RTP_PORT + "\r\n\r\n");
            RtspMsg setupR = RtspMsg.read(rawIn);
            if (setupR == null) {
                log.accept("peer closed the socket after our SETUP (no reply)");
                return;
            }
            String session = setupR.header("Session");
            log.accept("M6 -> SETUP, status=" + setupR.statusLine + " session=" + session);

            // M7: we PLAY it.
            send(out, "PLAY " + url + " RTSP/1.0\r\nCSeq: " + (cseq++)
                    + (session != null ? "\r\nSession: " + session : "") + "\r\n\r\n");
            RtspMsg playR = RtspMsg.read(rawIn);
            if (playR == null) {
                log.accept("peer closed the socket after our PLAY (no reply)");
                return;
            }
            log.accept("M7 -> PLAY, status=" + playR.statusLine);
            log.accept("handshake complete; waiting for RTP on " + RTP_PORT);
            controlOut = out;
            controlUrl = url;
            if (onEstablished != null) onEstablished.run();

            // Keep the control connection open: closing it tears the session down. From here on
            // requestIdr() can also write on this same socket (e.g. a SET_PARAMETER), so what comes
            // back next may be ITS reply (a response, "RTSP/1.0 ...") rather than a new request from
            // the source - only auto-ACK an actual incoming request, never a response to our own.
            while (running && !sock.isClosed()) {
                RtspMsg next = RtspMsg.read(rawIn);
                if (next == null) break;
                log.accept("<- " + (next.method != null ? next.method : next.statusLine));
                if (next.method == null) continue; // reply to something we sent
                if (next.cseq > 0) {
                    send(out, "RTSP/1.0 200 OK\r\nCSeq: " + next.cseq + "\r\n\r\n");
                }
                // The source ends or pauses the session either by sending TEARDOWN itself, or by
                // asking us to via an M5-style SET_PARAMETER "wfd_trigger_method: <METHOD>" - in
                // which case the sink is the one that must send the request (WFD Tech Spec 6.4.5).
                if (next.method.equalsIgnoreCase("TEARDOWN")) {
                    log.accept("source sent TEARDOWN; closing session");
                    break;
                }
                String trigger = triggerMethod(next.body);
                if (trigger != null) {
                    String sessionHeader = session != null ? "\r\nSession: " + session : "";
                    send(out, trigger + " " + url + " RTSP/1.0\r\nCSeq: " + (cseq++) + sessionHeader + "\r\n\r\n");
                    log.accept("source triggered " + trigger + "; sent it");
                    if (trigger.equals("TEARDOWN")) break;
                }
            }
        } catch (Exception e) {
            log.accept("RTSP session ended: " + e);
        } finally {
            if (controlOut != null) {
                controlOut = null;
                controlUrl = null;
                if (onEnded != null) onEnded.run();
            }
        }
    }

    /** PAUSE, PLAY or TEARDOWN out of "wfd_trigger_method: X" in a SET_PARAMETER body, else null. */
    private static String triggerMethod(String body) {
        for (String line : body.split("\r\n")) {
            if (line.startsWith("wfd_trigger_method")) {
                String[] parts = line.split(":", 2);
                if (parts.length < 2) continue;
                String method = parts[1].trim().toUpperCase(java.util.Locale.ROOT);
                if (method.equals("PAUSE") || method.equals("PLAY") || method.equals("TEARDOWN")) return method;
            }
        }
        return null;
    }

    private static Integer extractUibcPort(String body) {
        for (String line : body.split("\r\n")) {
            if (line.contains("wfd_uibc_capability")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("port=(\\d+)").matcher(line);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        return null;
    }

    private static String extractPresentationUrl(String body) {
        for (String line : body.split("\r\n")) {
            if (line.startsWith("wfd_presentation_URL")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String[] urls = parts[1].trim().split("\\s+");
                    if (urls.length > 0) return urls[0];
                }
            }
        }
        return null;
    }

    private static void send(OutputStream out, String msg) throws IOException {
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** One parsed RTSP message: a request (method+uri) or a response (statusLine), plus headers/body. */
    static class RtspMsg {
        String method, uri, statusLine;
        int cseq;
        String body = "";
        java.util.Map<String, String> headers = new java.util.HashMap<>();

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        static RtspMsg read(InputStream in) throws IOException {
            String first = readLine(in);
            if (first == null) return null;
            RtspMsg m = new RtspMsg();
            if (first.startsWith("RTSP/1.0")) {
                m.statusLine = first;
            } else {
                String[] p = first.split(" ");
                m.method = p.length > 0 ? p[0] : "";
                m.uri = p.length > 1 ? p[1] : "";
            }
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String name = line.substring(0, c).trim();
                    String value = line.substring(c + 1).trim();
                    m.headers.put(name.toLowerCase(java.util.Locale.ROOT), value);
                    if (name.equalsIgnoreCase("CSeq")) {
                        try {
                            m.cseq = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (name.equalsIgnoreCase("Content-Length")) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(buf, off, contentLength - off);
                    if (n < 0) break;
                    off += n;
                }
                m.body = new String(buf, 0, off, StandardCharsets.UTF_8);
            }
            return m;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            boolean any = false;
            while ((c = in.read()) >= 0) {
                any = true;
                if (c == '\n') {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') sb.setLength(sb.length() - 1);
                    return sb.toString();
                }
                sb.append((char) c);
            }
            return any ? sb.toString() : null;
        }
    }
}
