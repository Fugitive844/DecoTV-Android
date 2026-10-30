package io.github.fugitive844.decotv.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 对应服务端 SearchResult。 */
public final class Video {
    public String id = "", title = "", poster = "", source = "", sourceName = "", year = "", desc = "", typeName = "", remarks = "";
    public final List<String> episodes = new ArrayList<>();
    public final List<String> episodeTitles = new ArrayList<>();

    public static Video fromJson(JSONObject o) {
        Video v = new Video();
        v.id = o.optString("id");
        v.title = o.optString("title");
        v.poster = o.optString("poster");
        v.source = o.optString("source");
        v.sourceName = o.optString("source_name");
        v.year = o.optString("year");
        v.desc = o.optString("desc");
        v.typeName = o.optString("type_name");
        v.remarks = o.optString("remarks");
        JSONArray eps = o.optJSONArray("episodes");
        JSONArray titles = o.optJSONArray("episodes_titles");
        for (int i = 0; eps != null && i < eps.length(); i++) {
            v.episodes.add(eps.optString(i));
            String t = titles != null && i < titles.length() ? titles.optString(i) : "";
            v.episodeTitles.add(t.isEmpty() ? "第" + (i + 1) + "集" : t);
        }
        return v;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject().put("id", id).put("title", title).put("poster", poster).put("source", source)
                    .put("source_name", sourceName).put("year", year).put("desc", desc).put("type_name", typeName)
                    .put("remarks", remarks);
            o.put("episodes", new JSONArray(episodes)).put("episodes_titles", new JSONArray(episodeTitles));
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public String key() { return source + "+" + id; }

    /**
     * 解析苹果 CMS 的 vod_play_url，与服务端 parseVodPlayUrl 相同：按 $$$ 分成多组播放线路，
     * 每组按 # 分集、$ 分隔集名和地址，只保留 http(s) 地址，取集数最多的一组。
     */
    public void parsePlayUrl(String value) {
        episodes.clear();
        episodeTitles.clear();
        if (value == null || value.isEmpty()) return;
        List<String> bestUrls = new ArrayList<>(), bestTitles = new ArrayList<>();
        for (String group : value.split("\\$\\$\\$")) {
            List<String> urls = new ArrayList<>(), titles = new ArrayList<>();
            for (String entry : group.split("#")) {
                int sep = entry.indexOf('$');
                if (sep < 0) continue;
                String url = entry.substring(sep + 1).trim();
                if (!url.matches("(?i)^https?://.*")) continue;
                titles.add(entry.substring(0, sep).trim());
                urls.add(url);
            }
            if (urls.size() > bestUrls.size()) {
                bestUrls = urls;
                bestTitles = titles;
            }
        }
        episodes.addAll(bestUrls);
        episodeTitles.addAll(bestTitles);
    }
}
