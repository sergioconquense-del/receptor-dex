package com.wfdtest.app;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import java.util.function.Consumer;

/**
 * One WFD media session: RTP in, picture and sound out. Mirrors the media half of Google's AOSP
 * sink (RTPSink -> TunnelRenderer -> platform player): RtpReceiver orders the RTP packets,
 * RtpTsDataSource exposes them as one TS byte stream, and ExoPlayer (the public successor of that
 * platform player) demuxes, decodes, syncs audio/video and times every frame.
 *
 * The RtpReceiver is opened before RTSP SETUP (see RtspSink) and owned by this session afterwards.
 * Must be created, used and released on the main thread (ExoPlayer's application looper).
 */
final class StreamSession {

    // Google's sink started playback as soon as the first bytes were queued (TunnelRenderer:
    // "mTotalBytesQueued > 0"), anchored to real time. ExoPlayer's defaults are for files (seconds
    // of buffer); these make it start immediately and never hold more than a fraction of a second.
    private static final int MIN_BUFFER_MS = 100;
    private static final int MAX_BUFFER_MS = 500;
    private static final int BUFFER_FOR_PLAYBACK_MS = 0;
    private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 0;

    private final RtpReceiver receiver;
    private final ExoPlayer player;

    StreamSession(Context context, RtpReceiver receiver, Surface surface, Consumer<String> log,
                  Runnable requestIdr) {
        this.receiver = receiver;
        player = new ExoPlayer.Builder(context)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS,
                                BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build())
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                log.accept("player state: " + stateName(playbackState));
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                for (Tracks.Group group : tracks.getGroups()) {
                    for (int i = 0; i < group.length; i++) {
                        Format f = group.getTrackFormat(i);
                        log.accept("track: " + f.sampleMimeType + " " + f.width + "x" + f.height
                                + " " + f.sampleRate + "Hz ch=" + f.channelCount
                                + " supported=" + group.isTrackSupported(i));
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                log.accept("video size: " + videoSize.width + "x" + videoSize.height);
            }

            @Override
            public void onRenderedFirstFrame() {
                log.accept("first frame rendered");
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                log.accept("player error, restarting: " + error.getErrorCodeName() + " " + error);
                player.prepare();
                requestIdr.run();
            }
        });
        if (surface != null) player.setVideoSurface(surface);

        ExtractorsFactory extractors = () -> new Extractor[] {
                new TsExtractor(TsExtractor.MODE_SINGLE_PMT, new TimestampAdjuster(0),
                        new WfdTsPayloadReaderFactory())
        };
        MediaSource source = new ProgressiveMediaSource.Factory(
                () -> new RtpTsDataSource(receiver), extractors)
                .createMediaSource(MediaItem.fromUri(Uri.parse("rtp://wfd-sink")));
        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    void setSurface(Surface surface) {
        if (surface != null) player.setVideoSurface(surface);
        else player.clearVideoSurface();
    }

    String stats() {
        StringBuilder b = new StringBuilder(receiver.stats());
        DecoderCounters video = player.getVideoDecoderCounters();
        if (video != null) {
            video.ensureUpdated();
            b.append(" | video: rendered=").append(video.renderedOutputBufferCount)
                    .append(" dropped=").append(video.droppedBufferCount)
                    .append(" skipped=").append(video.skippedOutputBufferCount);
        }
        b.append(" | buffered=").append(player.getTotalBufferedDuration()).append("ms");
        return b.toString();
    }

    void release() {
        receiver.close();
        player.release();
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return String.valueOf(state);
        }
    }
}
