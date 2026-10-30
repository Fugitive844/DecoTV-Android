package io.github.fugitive844.decotv.download;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.IOException;

/** 播放器读取 .dtv 加密文件：边读边解密，支持拖动进度。 */
@OptIn(markerClass = UnstableApi.class)
public final class VaultDataSource extends BaseDataSource {
    public static final class Factory implements DataSource.Factory {
        @NonNull @Override public DataSource createDataSource() { return new VaultDataSource(); }
    }

    private Vault.Reader reader;
    private Uri uri;
    private long remaining;

    VaultDataSource() { super(false); }

    @Override public long open(@NonNull DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        reader = new Vault.Reader(new File(spec.uri.getPath()));
        long length = reader.length();
        if (spec.position > length) throw new IOException("位置超出文件长度");
        reader.seek(spec.position);
        remaining = spec.length == C.LENGTH_UNSET ? length - spec.position : Math.min(spec.length, length - spec.position);
        transferStarted(spec);
        return remaining;
    }

    @Override public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int n = reader.read(buffer, offset, (int) Math.min(length, remaining));
        if (n < 0) return C.RESULT_END_OF_INPUT;
        remaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable @Override public Uri getUri() { return uri; }

    @Override public void close() throws IOException {
        uri = null;
        if (reader != null) {
            try {
                reader.close();
            } finally {
                reader = null;
                transferEnded();
            }
        }
    }
}
