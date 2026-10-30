package io.github.fugitive844.decotv.model;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class Channel {
    public String id = "", tvgId = "", name = "", logo = "", group = "", url = "";
    /** 同名频道合并后的全部播放地址，第一个即 url；未合并时只有 url 一个。 */
    public final List<String> urls = new ArrayList<>();

    public static Channel fromJson(JSONObject o) {
        Channel c = new Channel();
        c.id = o.optString("id");
        c.tvgId = o.optString("tvgId");
        c.name = o.optString("name");
        c.logo = o.optString("logo");
        c.group = o.optString("group");
        c.url = o.optString("url");
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("tvgId", tvgId).put("name", name).put("logo", logo).put("group", group).put("url", url);
        } catch (org.json.JSONException ignored) {
            // 键值都是字符串，不会出错
        }
        return o;
    }

    /** 播放地址列表，至少包含 url。 */
    public List<String> sources() {
        if (urls.isEmpty() && !url.isEmpty()) urls.add(url);
        return urls;
    }
}
