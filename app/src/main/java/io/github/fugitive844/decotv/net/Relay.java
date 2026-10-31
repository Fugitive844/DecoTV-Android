package io.github.fugitive844.decotv.net;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** 中转策略：第三方资源默认直连，只有用户在设置中打开时才经服务端中转；搜索默认智能，直连失败的源改走中转。 */
public final class Relay {
    private Relay() {}

    public enum Mode {
        DIRECT("direct", "不中转"), RELAY("relay", "全中转"), SMART("smart", "智能");
        public final String key, label;
        Mode(String key, String label) { this.key = key; this.label = label; }

        public static Mode of(String key) {
            for (Mode mode : values()) if (mode.key.equals(key)) return mode;
            return DIRECT;
        }
    }

    public enum Item {
        SEARCH("search", "视频搜索", "手机直接请求各视频源；全中转由服务端搜索；智能时直连失败的源经服务端获取", Mode.SMART),
        IMAGE("image", "封面图片", "直连图片站；中转走 /api/image-proxy"),
        VOD("vod", "点播视频", "直连视频源；中转走服务端 m3u8 代理（含去广告）"),
        LIVE_LIST("live_list", "直播频道列表", "直接下载 m3u；中转由服务端解析"),
        LIVE_STREAM("live_stream", "直播流", "直连直播流；中转走 /api/proxy/m3u8"),
        LOGO("logo", "台标", "直连台标；中转走 /api/proxy/logo");
        public final String key, label, hint;
        public final Mode defaultMode;
        Item(String key, String label, String hint) { this(key, label, hint, Mode.DIRECT); }
        Item(String key, String label, String hint, Mode defaultMode) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.defaultMode = defaultMode;
        }
    }

    /** 按策略给出依次尝试的地址：不中转只有直连，全中转只有中转，智能先直连后中转。 */
    public static List<String> candidates(Mode mode, String direct, String relay) {
        List<String> list = new ArrayList<>();
        if (mode != Mode.RELAY && notEmpty(direct)) list.add(direct);
        if (mode != Mode.DIRECT && notEmpty(relay)) list.add(relay);
        return list;
    }

    public static String normalizeServer(String server) {
        String value = server == null ? "" : server.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.isEmpty() && !value.matches("(?i)^https?://.*")) value = "https://" + value;
        return value;
    }

    /** 服务端返回的相对地址补全为绝对地址。 */
    public static String absolute(String server, String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return server + url;
        return url;
    }

    public static String imageProxy(String server, String url) {
        return server + "/api/image-proxy?url=" + enc(url);
    }

    public static String logoProxy(String server, String url, String source) {
        return server + "/api/proxy/logo?url=" + enc(url) + "&decotv-source=" + enc(source);
    }

    public static String liveProxy(String server, String url, String source, boolean hls) {
        return server + (hls ? "/api/proxy/m3u8?url=" : "/api/proxy/stream?url=") + enc(url) + "&decotv-source=" + enc(source);
    }

    public static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean notEmpty(String value) { return value != null && !value.isEmpty(); }
}
