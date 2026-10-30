package io.github.fugitive844.decotv.model;

import org.json.JSONObject;

public final class LiveSource {
    public static final String DEFAULT_UA = "AptvPlayer/1.4.10";
    public String key = "", name = "", url = "", ua = "";

    public static LiveSource fromJson(JSONObject o) {
        LiveSource s = new LiveSource();
        s.key = o.optString("key");
        s.name = o.optString("name");
        s.url = o.optString("url");
        s.ua = o.optString("ua");
        return s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key).put("name", name).put("url", url).put("ua", ua);
        } catch (org.json.JSONException ignored) {
            // 键值都是字符串，不会出错
        }
        return o;
    }

    /** 与服务端一致：直播源未配置 UA 时使用默认播放器 UA。 */
    public String userAgent() { return ua == null || ua.trim().isEmpty() ? DEFAULT_UA : ua.trim(); }
}
