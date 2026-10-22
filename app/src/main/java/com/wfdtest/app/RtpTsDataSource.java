package com.wfdtest.app;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

/**
 * Media3 DataSource over the in-order TS byte stream RtpReceiver produces - the role
 * TunnelRenderer's StreamSource played in Google's AOSP sink when it fed the platform player.
 * Unbounded and unseekable, like Media3's own UdpDataSource.
 */
final class RtpTsDataSource extends BaseDataSource {

    private final RtpReceiver receiver;
    private Uri uri;
    private byte[] chunk;
    private int chunkOffset;
    private boolean opened;

    RtpTsDataSource(RtpReceiver receiver) {
        super(/* isNetwork= */ true);
        this.receiver = receiver;
    }

    @Override
    public long open(DataSpec dataSpec) {
        uri = dataSpec.uri;
        transferInitializing(dataSpec);
        opened = true;
        transferStarted(dataSpec);
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (chunk == null || chunkOffset == chunk.length) {
            chunk = receiver.next();
            chunkOffset = 0;
            if (chunk == null) return C.RESULT_END_OF_INPUT;
        }
        int n = Math.min(length, chunk.length - chunkOffset);
        System.arraycopy(chunk, chunkOffset, buffer, offset, n);
        chunkOffset += n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        uri = null;
        chunk = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
