package io.github.fugitive844.decotv.net;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import io.github.fugitive844.decotv.data.Prefs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/** 封面与台标加载：按中转策略依次尝试直连和服务端中转，结果缓存在内存。 */
public final class ImageLoader {
    private static final int MAX_COVER_TRIES = 4;
    private final ApiClient api;
    private final Prefs prefs;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    public ImageLoader(ApiClient api, Prefs prefs) {
        this.api = api;
        this.prefs = prefs;
    }

    public void cover(ImageView view, String url) { load(view, url, Relay.Item.IMAGE, null); }

    /** 同一部片有多个封面地址时依次尝试，避开连不上的图片站。 */
    public void covers(ImageView view, List<String> rawUrls) {
        List<String> urls = new ArrayList<>();
        for (String raw : rawUrls) {
            String url = Relay.absolute(api.server(), raw == null ? "" : raw.trim());
            if (!url.isEmpty() && !urls.contains(url) && urls.size() < MAX_COVER_TRIES) urls.add(url);
        }
        String key = urls.isEmpty() ? "" : urls.get(0);
        view.setTag(key);
        view.setImageDrawable(null);
        if (key.isEmpty()) return;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        String server = api.server();
        Tasks.run(() -> {
            IOException last = null;
            for (String url : urls) {
                boolean ownServer = url.startsWith(server + "/");
                try {
                    return fetch(Relay.Item.IMAGE, ownServer ? List.of(url) : Relay.candidates(prefs.mode(Relay.Item.IMAGE), url, Relay.imageProxy(server, url)), url);
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last;
        }, bitmap -> {
            cache.put(key, bitmap);
            if (key.equals(view.getTag())) view.setImageBitmap(bitmap);
        }, error -> { /* 图片失败只留空白 */ });
    }

    public void logo(ImageView view, String url, String liveSource) { load(view, url, Relay.Item.LOGO, liveSource); }

    private void load(ImageView view, String rawUrl, Relay.Item item, String liveSource) {
        String url = Relay.absolute(api.server(), rawUrl == null ? "" : rawUrl.trim());
        view.setTag(url);
        view.setImageDrawable(null);
        if (url.isEmpty()) return;
        Bitmap hit = cache.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        String server = api.server();
        boolean ownServer = url.startsWith(server + "/");
        String relay = ownServer ? null : item == Relay.Item.LOGO ? Relay.logoProxy(server, url, liveSource) : Relay.imageProxy(server, url);
        List<String> candidates = ownServer ? List.of(url) : Relay.candidates(prefs.mode(item), url, relay);
        Tasks.run(() -> fetch(item, candidates, url), bitmap -> {
            if (bitmap != null) cache.put(url, bitmap);
            if (url.equals(view.getTag()) && bitmap != null) view.setImageBitmap(bitmap);
        }, error -> { /* 图片失败只留空白 */ });
    }

    private Bitmap fetch(Relay.Item item, List<String> candidates, String original) throws IOException {
        IOException last = null;
        for (String candidate : candidates) {
            boolean relayed = !candidate.equals(original);
            try {
                Request.Builder builder = new Request.Builder().url(candidate);
                String referer = relayed ? null : MediaUrls.imageReferer(candidate);
                if (referer != null) builder.header("Referer", referer);
                try (Response response = api.http().newCall(builder.build()).execute()) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
                    byte[] bytes = response.body().bytes();
                    Bitmap bitmap = decode(bytes);
                    if (bitmap == null) throw new IOException("不是有效图片");
                    NetLog.use(item, relayed, candidate);
                    return bitmap;
                }
            } catch (IOException | IllegalArgumentException e) {
                NetLog.fail(item, relayed, candidate, e);
                last = e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        throw last == null ? new IOException("没有可用地址") : last;
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        // 按长边缩小：超长的图也不会解码成过大的位图
        while (bounds.outWidth / (sample * 2) >= 320 || bounds.outHeight / (sample * 2) >= 320) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
