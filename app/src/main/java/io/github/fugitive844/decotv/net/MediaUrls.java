package io.github.fugitive844.decotv.net;

import java.util.Locale;

/** 判断地址是否已经是可直接播放的媒体，以及是否按 HLS 处理。 */
public final class MediaUrls {
    private MediaUrls() {}

    private static String path(String url) {
        String value = url == null ? "" : url;
        int cut = value.indexOf('?');
        if (cut >= 0) value = value.substring(0, cut);
        cut = value.indexOf('#');
        if (cut >= 0) value = value.substring(0, cut);
        return value.toLowerCase(Locale.ROOT);
    }

    public static boolean isHls(String url) {
        String p = path(url);
        return p.endsWith(".m3u8") || p.endsWith(".m3u") || p.contains(".m3u8/");
    }

    public static boolean looksLikeMedia(String url) {
        String p = path(url);
        if (isHls(url)) return true;
        for (String ext : new String[]{".mp4", ".flv", ".mkv", ".m4v", ".mov", ".ts", ".webm", ".avi"}) {
            if (p.endsWith(ext)) return true;
        }
        return false;
    }

    public static String host(String url) {
        try {
            String host = java.net.URI.create(url.replace(" ", "%20")).getHost();
            return host == null ? "?" : host;
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** 豆瓣图片要求豆瓣来源的 Referer，直连时补上。 */
    public static String imageReferer(String url) {
        String p = path(url);
        return p.contains("doubanio.com") ? "https://movie.douban.com/" : null;
    }
}
