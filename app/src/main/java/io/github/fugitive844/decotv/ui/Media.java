package io.github.fugitive844.decotv.ui;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.net.ApiClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

/** 一个播放目标：地址、是否 HLS、请求头，以及是否经服务端中转。 */
final class Media {
    String url = "", referer = "", userAgent = ApiClient.USER_AGENT;
    boolean hls, relayed;

    /** 播放器累计收到的字节数，缓冲时据此算实时网速。 */
    private static final java.util.concurrent.atomic.AtomicLong RECEIVED = new java.util.concurrent.atomic.AtomicLong();

    static long received() { return RECEIVED.get(); }

    @OptIn(markerClass = UnstableApi.class)
    private static final TransferListener COUNTER = new TransferListener() {
        @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) { }
        @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) { }
        @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int bytes) {
            if (network) RECEIVED.addAndGet(bytes);
        }
        @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) { }
    };

    @OptIn(markerClass = UnstableApi.class)
    MediaSource source() {
        OkHttpDataSource.Factory factory = new OkHttpDataSource.Factory(DecoApp.get().api.http()).setUserAgent(userAgent)
                .setTransferListener(COUNTER);
        Map<String, String> headers = new HashMap<>();
        if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
        factory.setDefaultRequestProperties(headers);
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        return new DefaultMediaSourceFactory(factory).createMediaSource(item.build());
    }

    /** 地址没有扩展名可判断时，读开头几百字节确认是否为 m3u8。只在后台线程调用。 */
    static boolean sniffHls(String url, String userAgent, String referer) {
        Request.Builder builder = new Request.Builder().url(url).header("Range", "bytes=0-511").header("User-Agent", userAgent);
        if (referer != null && !referer.isEmpty()) builder.header("Referer", referer);
        try (Response response = DecoApp.get().api.http().newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            String type = response.header("Content-Type", "");
            if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("mpegurl")) return true;
            okio.BufferedSource body = response.body().source();
            body.request(512);
            byte[] head = body.getBuffer().readByteArray(Math.min(512, body.getBuffer().size()));
            return new String(head, java.nio.charset.StandardCharsets.UTF_8).trim().startsWith("#EXTM3U");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
