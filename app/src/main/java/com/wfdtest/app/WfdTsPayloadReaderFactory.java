package com.wfdtest.app;

import android.util.SparseArray;

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.PesReader;
import androidx.media3.extractor.ts.TsPayloadReader;

/**
 * Media3's default TS payload readers (H.264, AAC, ...) plus WFD LPCM on stream_type 0x83 - the
 * mapping Google's AOSP ATSParser used (STREAMTYPE_LPCM_AC3 -> ElementaryStreamQueue PCM_AUDIO).
 */
final class WfdTsPayloadReaderFactory implements TsPayloadReader.Factory {

    private static final int STREAM_TYPE_WFD_LPCM = 0x83;

    // DeX's H.264 carries no Access Unit Delimiters; without FLAG_DETECT_ACCESS_UNITS Media3's
    // H264Reader only ends a sample on an AUD and never outputs one (confirmed live: tracks found,
    // zero samples buffered). FLAG_ALLOW_NON_IDR_KEYFRAMES lets playback start on an I-slice too.
    private final DefaultTsPayloadReaderFactory delegate = new DefaultTsPayloadReaderFactory(
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES);

    @Override
    public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
        return delegate.createInitialPayloadReaders();
    }

    @Override
    public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
        if (streamType == STREAM_TYPE_WFD_LPCM) return new PesReader(new LpcmReader());
        return delegate.createPayloadReader(streamType, esInfo);
    }
}
