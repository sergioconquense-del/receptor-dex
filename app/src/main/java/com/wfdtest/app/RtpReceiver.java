package com.wfdtest.app;

import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Receives the WFD media stream (RFC 3550 RTP carrying RFC 2250 MPEG-TS) and hands out the TS
 * payloads strictly in sequence order. The ordering policy is a port of Google's own AOSP Miracast
 * sink (android-4.2 frameworks/av/media/libstagefright/wifi-display/sink/TunnelRenderer.cpp,
 * dequeueBuffer): packets are kept sorted by extended sequence number, duplicates and anything
 * older than what was already handed out are dropped, and a missing packet is waited for at most
 * 50ms before it is given up on and skipped.
 *
 * Google's sink asked for a retransmission (RTCP NACK) inside that window; this sink never
 * negotiated an RTCP port (wfd_client_rtp_ports "... 19000 0 ..."), so a skipped gap instead asks
 * the source for a fresh IDR over RTSP via onLoss.
 */
final class RtpReceiver {

    private static final long MAX_WAIT_FOR_MISSING_US = 50_000; // TunnelRenderer: 50000ll
    private static final int TS_PACKET_SIZE = 188;
    private static final int MAX_QUEUED_PACKETS = 2000;

    private final DatagramSocket socket;
    private final Consumer<String> log;
    private final Runnable onLoss;
    private final Thread thread;

    private final TreeMap<Long, byte[]> packets = new TreeMap<>();
    private long highestExtSeq = -1;
    private long lastDequeuedExtSeq = -1;
    private long firstFailedAttemptUs = -1;
    private boolean closed;

    private long received, dropped, lost, malformed;

    RtpReceiver(int port, Consumer<String> log, Runnable onLoss) throws SocketException {
        this.log = log;
        this.onLoss = onLoss;
        socket = new DatagramSocket(port);
        socket.setReceiveBufferSize(4 * 1024 * 1024);
        thread = new Thread(this::receiveLoop, "wfd-rtp-rx");
        thread.start();
    }

    void close() {
        synchronized (this) {
            closed = true;
            packets.clear();
            notifyAll();
        }
        socket.close();
    }

    synchronized String stats() {
        return "rtp: received=" + received + " dropped(dup/late)=" + dropped + " lost=" + lost
                + " malformed=" + malformed + " queued=" + packets.size();
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (true) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
            } catch (Exception e) {
                synchronized (this) {
                    if (!closed) log.accept("rtp receive ended: " + e);
                }
                return;
            }
            queuePacket(buf, p.getLength());
        }
    }

    /** RFC 3550 fixed header: V=2, CSRC list, optional extension, optional padding. */
    private void queuePacket(byte[] buf, int len) {
        if (len < 12 || (buf[0] & 0xC0) != 0x80) {
            countMalformed();
            return;
        }
        int csrcCount = buf[0] & 0x0F;
        int offset = 12 + 4 * csrcCount;
        if ((buf[0] & 0x10) != 0) {
            if (offset + 4 > len) {
                countMalformed();
                return;
            }
            offset += 4 + 4 * (((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF));
        }
        int end = len;
        if ((buf[0] & 0x20) != 0) end -= buf[len - 1] & 0xFF;
        int size = end - offset;
        if (size <= 0 || size % TS_PACKET_SIZE != 0) { // TunnelRenderer CHECKs whole TS packets
            countMalformed();
            return;
        }
        int seq = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        byte[] payload = new byte[size];
        System.arraycopy(buf, offset, payload, 0, size);

        synchronized (this) {
            if (closed) return;
            received++;
            long extSeq = extend(seq);
            if (extSeq <= lastDequeuedExtSeq || packets.containsKey(extSeq)) {
                dropped++;
                return;
            }
            packets.put(extSeq, payload);
            if (packets.size() > MAX_QUEUED_PACKETS) { // nobody is reading: keep only the newest
                packets.pollFirstEntry();
                dropped++;
            }
            notifyAll();
        }
    }

    /** 16-bit RTP sequence number -> extended (wrap-aware) number, relative to the highest seen. */
    private long extend(int seq) {
        if (highestExtSeq < 0) {
            highestExtSeq = seq;
            return seq;
        }
        long ext = (highestExtSeq & ~0xFFFFL) | seq;
        if (ext < highestExtSeq - 0x8000) ext += 0x10000;
        else if (ext > highestExtSeq + 0x8000) ext -= 0x10000;
        if (ext > highestExtSeq) highestExtSeq = ext;
        return ext;
    }

    /**
     * Next TS payload in sequence order, blocking until one is available. Returns null once
     * closed. Same decision logic as TunnelRenderer::dequeueBuffer.
     */
    byte[] next() throws InterruptedIOException {
        boolean skipped = false;
        byte[] result;
        synchronized (this) {
            while (true) {
                if (closed) return null;
                if (packets.isEmpty()) {
                    waitFor(0);
                    continue;
                }
                long firstSeq = packets.firstKey();
                if (lastDequeuedExtSeq < 0 || firstSeq == lastDequeuedExtSeq + 1) {
                    break;
                }
                long nowUs = System.nanoTime() / 1000;
                if (firstFailedAttemptUs < 0) firstFailedAttemptUs = nowUs;
                long remainingUs = firstFailedAttemptUs + MAX_WAIT_FOR_MISSING_US - nowUs;
                if (remainingUs > 0) {
                    waitFor(Math.max(1, remainingUs / 1000));
                    continue;
                }
                lost += firstSeq - lastDequeuedExtSeq - 1;
                skipped = true;
                break;
            }
            java.util.Map.Entry<Long, byte[]> first = packets.pollFirstEntry();
            lastDequeuedExtSeq = first.getKey();
            firstFailedAttemptUs = -1;
            result = first.getValue();
        }
        if (skipped) onLoss.run();
        return result;
    }

    private void waitFor(long ms) throws InterruptedIOException {
        try {
            wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    private synchronized void countMalformed() {
        malformed++;
    }
}
