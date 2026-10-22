package com.wfdtest.app;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.TsPayloadReader;

import java.io.ByteArrayOutputStream;

/**
 * WFD LPCM audio (TS stream_type 0x83), which Media3 does not support. Port of Google's AOSP
 * android-4.2 media/libstagefright/mpeg2ts/ESQueue.cpp dequeueAccessUnitPCMAudio(): each PES
 * payload is a 4-byte header - 0xA0, number of access units, a reserved byte, then
 * quantization(2) / sampling frequency(3) / channels(3) - followed by numAUs * 80 stereo frames of
 * 16-bit big-endian samples, converted to host order (ntohs) before output.
 */
final class LpcmReader implements ElementaryStreamReader {

    private static final int FRAMES_PER_AU = 80; // ESQueue: kFramesPerAU

    private final ByteArrayOutputStream pes = new ByteArrayOutputStream();
    private TrackOutput output;
    private String formatId;
    private int sampleRate = -1;
    private int channelCount = -1;
    private long pesTimeUs = C.TIME_UNSET;
    private long nextTimeUs = C.TIME_UNSET;

    @Override
    public void seek() {
        pes.reset();
        nextTimeUs = C.TIME_UNSET;
    }

    @Override
    public void createTracks(ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
        idGenerator.generateNewId();
        formatId = idGenerator.getFormatId();
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
    }

    @Override
    public void packetStarted(long pesTimeUs, int flags) {
        pes.reset();
        this.pesTimeUs = pesTimeUs;
    }

    @Override
    public void consume(ParsableByteArray data) {
        int n = data.bytesLeft();
        pes.write(data.getData(), data.getPosition(), n);
        data.skipBytes(n);
    }

    @Override
    public void packetFinished(boolean isEndOfInput) {
        byte[] d = pes.toByteArray();
        pes.reset();
        if (d.length < 4 || (d[0] & 0xFF) != 0xA0) return;
        int numAUs = d[1] & 0xFF;
        int frequencyCode = (d[3] >> 3) & 0x07;
        int channelCode = d[3] & 0x07;
        int rate = frequencyCode == 1 ? 44100 : frequencyCode == 2 ? 48000 : -1;
        if (rate < 0) return;
        int channels = channelCode + 1; // ESQueue: code 1 == stereo
        int frameSize = channels * 2;
        int size = Math.min(numAUs * FRAMES_PER_AU * frameSize, d.length - 4);
        size -= size % frameSize;
        if (size <= 0) return;

        if (rate != sampleRate || channels != channelCount) {
            sampleRate = rate;
            channelCount = channels;
            output.format(new Format.Builder()
                    .setId(formatId)
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setSampleRate(rate)
                    .setChannelCount(channels)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build());
        }

        byte[] samples = new byte[size];
        for (int i = 0; i < size; i += 2) { // ntohs: big-endian -> little-endian
            samples[i] = d[4 + i + 1];
            samples[i + 1] = d[4 + i];
        }
        long timeUs = pesTimeUs != C.TIME_UNSET ? pesTimeUs
                : nextTimeUs != C.TIME_UNSET ? nextTimeUs : 0;
        output.sampleData(new ParsableByteArray(samples), size);
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null);
        nextTimeUs = timeUs + (size / frameSize) * 1_000_000L / rate;
    }
}
