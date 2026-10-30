package io.github.fugitive844.decotv.download;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.net.ApiClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 边存边播的数据源：dtvseg://任务id/index.m3u8 返回本地播放列表；
 * dtvseg://任务id/序号 优先读已下载的分片，没有就自己下载、解密并存入缓存，下载线程随后会跳过这一片。
 */
@OptIn(markerClass = UnstableApi.class)
public final class SegmentDataSource extends BaseDataSource {
    private static final String TAG = "DecoStream";

    public static final class Factory implements DataSource.Factory {
        private final Context context;
        private final String id;

        public Factory(Context context, String id) {
            this.context = context.getApplicationContext();
            this.id = id;
        }

        @NonNull @Override public DataSource createDataSource() { return new SegmentDataSource(context, id); }
    }

    private final Context context;
    private final String id;
    private final Map<String, byte[]> keys = new HashMap<>();
    private Stream.Index index;
    private Fetcher fetcher;
    private Uri uri;
    private byte[] data;
    private int offset, remaining;

    SegmentDataSource(Context context, String id) {
        super(true);
        this.context = context;
        this.id = id;
    }

    private Stream.Index index(File work) throws IOException {
        if (index == null) index = Stream.readIndex(work);
        if (fetcher == null) {
            String ua = index.userAgent.isEmpty() ? ApiClient.USER_AGENT : index.userAgent;
            fetcher = new Fetcher(DecoApp.get().api.http(), ua, index.referer);
        }
        return index;
    }

    @Override public long open(@NonNull DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        File work = Stream.workDir(context, id);
        String path = spec.uri.getPath() == null ? "" : spec.uri.getPath().replaceFirst("^/", "");
        Stream.Index x = index(work);
        if (path.equals("index.m3u8")) {
            data = Stream.playlist(id, x).getBytes(StandardCharsets.UTF_8);
        } else if (path.equals("init")) {
            File f = new File(work, "init.bin");
            if (f.exists()) {
                data = java.nio.file.Files.readAllBytes(f.toPath());
            } else {
                data = fetcher.plain(x.init);
                java.nio.file.Files.write(f.toPath(), data);
            }
        } else {
            int i;
            try {
                i = Integer.parseInt(path);
            } catch (NumberFormatException e) {
                throw new IOException("无效的分片地址：" + spec.uri);
            }
            if (i < 0 || i >= x.urls.length) throw new IOException("分片序号超出范围：" + i);
            Stream.playhead(id, i);
            File f = Fetcher.segFile(work, i);
            if (f.exists()) {
                data = java.nio.file.Files.readAllBytes(f.toPath());
                Log.i(TAG, "segment=" + i + " from=local");
            } else {
                data = fetcher.segment(x.urls[i], x.keys[i], x.ivs[i], keys);
                // 存进缓存，下载线程会跳过这一片；工作目录已被清理（任务删除）时只播放不保存
                if (work.isDirectory()) Fetcher.saveSegment(work, i, data);
                Log.i(TAG, "segment=" + i + " from=network");
            }
        }
        if (spec.position > data.length) throw new IOException("位置超出范围");
        offset = (int) spec.position;
        int left = data.length - offset;
        remaining = spec.length == C.LENGTH_UNSET ? left : (int) Math.min(spec.length, left);
        transferStarted(spec);
        return remaining;
    }

    @Override public int read(@NonNull byte[] buffer, int off, int length) {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int n = Math.min(length, remaining);
        System.arraycopy(data, offset, buffer, off, n);
        offset += n;
        remaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable @Override public Uri getUri() { return uri; }

    @Override public void close() {
        if (uri != null) {
            uri = null;
            data = null;
            transferEnded();
        }
    }
}
