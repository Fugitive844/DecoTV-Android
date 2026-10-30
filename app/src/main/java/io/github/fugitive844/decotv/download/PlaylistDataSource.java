package io.github.fugitive844.decotv.download;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 缓存文件的字节范围播放：PLAYLIST_URI 返回内存里的播放列表，其余地址交给原来的数据源（加密文件或普通文件）。 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaylistDataSource implements DataSource {
    public static final String PLAYLIST_URI = "dtvlocal://playlist/index.m3u8";

    public static final class Factory implements DataSource.Factory {
        private final byte[] playlist;
        private final DataSource.Factory upstream;

        public Factory(String playlist, DataSource.Factory upstream) {
            this.playlist = playlist.getBytes(StandardCharsets.UTF_8);
            this.upstream = upstream;
        }

        @NonNull @Override public DataSource createDataSource() { return new PlaylistDataSource(playlist, upstream.createDataSource()); }
    }

    private final byte[] playlist;
    private final DataSource upstream;
    private boolean local;
    private Uri uri;
    private int offset, remaining;

    PlaylistDataSource(byte[] playlist, DataSource upstream) {
        this.playlist = playlist;
        this.upstream = upstream;
    }

    @Override public void addTransferListener(@NonNull TransferListener listener) { upstream.addTransferListener(listener); }

    @Override public long open(@NonNull DataSpec spec) throws IOException {
        uri = spec.uri;
        local = PLAYLIST_URI.equals(spec.uri.toString());
        if (!local) return upstream.open(spec);
        offset = (int) Math.min(spec.position, playlist.length);
        int left = playlist.length - offset;
        remaining = spec.length == C.LENGTH_UNSET ? left : (int) Math.min(spec.length, left);
        return remaining;
    }

    @Override public int read(@NonNull byte[] buffer, int off, int length) throws IOException {
        if (!local) return upstream.read(buffer, off, length);
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int n = Math.min(length, remaining);
        System.arraycopy(playlist, offset, buffer, off, n);
        offset += n;
        remaining -= n;
        return n;
    }

    @Nullable @Override public Uri getUri() { return local ? uri : upstream.getUri(); }

    @NonNull @Override public Map<String, List<String>> getResponseHeaders() {
        return local ? Collections.emptyMap() : upstream.getResponseHeaders();
    }

    @Override public void close() throws IOException {
        if (local) {
            local = false;
            uri = null;
        } else {
            upstream.close();
        }
    }
}
