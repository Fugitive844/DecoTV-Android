package io.github.fugitive844.decotv.danmaku;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 手动匹配到的弹幕剧集。记下整部剧的分集列表和与本地集数的差值，
 * 切到下一集时自动用对应的弹幕分集，与网页端「手动匹配」后连续观看的效果一致。
 */
public final class ManualMatch {
    public static final class Anime {
        public long id;
        public String title = "", type = "";
        public final List<Long> episodeIds = new ArrayList<>();
        public final List<String> episodeTitles = new ArrayList<>();
    }

    public Anime anime;
    /** 弹幕分集序号 − 本地集序号。 */
    public int delta;

    /** 本地第 episode 集（从 0 开始）对应的弹幕分集序号，超出范围返回 -1。 */
    public int target(int episode) {
        int i = episode + delta;
        return anime != null && i >= 0 && i < anime.episodeIds.size() ? i : -1;
    }

    /** 解析 /api/danmu-external/search 的 animes。 */
    public static List<Anime> parseSearch(JSONObject o) {
        List<Anime> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("animes");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            Anime a = anime(arr.optJSONObject(i));
            if (a != null && !a.episodeIds.isEmpty()) out.add(a);
        }
        return out;
    }

    private static Anime anime(JSONObject o) {
        if (o == null) return null;
        Anime a = new Anime();
        a.id = o.optLong("animeId", 0);
        a.title = o.optString("animeTitle", "");
        a.type = o.optString("typeDescription", o.optString("type", ""));
        JSONArray eps = o.optJSONArray("episodes");
        if (eps != null) {
            for (int j = 0; j < eps.length(); j++) {
                JSONObject e = eps.optJSONObject(j);
                if (e == null || e.optLong("episodeId", 0) <= 0) continue;
                a.episodeIds.add(e.optLong("episodeId"));
                a.episodeTitles.add(e.optString("episodeTitle", "第" + (j + 1) + "集"));
            }
        }
        return a;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject().put("animeId", anime.id).put("animeTitle", anime.title).put("type", anime.type).put("delta", delta);
            JSONArray eps = new JSONArray();
            for (int i = 0; i < anime.episodeIds.size(); i++) {
                eps.put(new JSONObject().put("episodeId", anime.episodeIds.get(i)).put("episodeTitle", anime.episodeTitles.get(i)));
            }
            return o.put("episodes", eps).toString();
        } catch (JSONException e) {
            return "";
        }
    }

    public static ManualMatch fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            ManualMatch m = new ManualMatch();
            m.anime = anime(o);
            m.delta = o.optInt("delta", 0);
            return m.anime == null || m.anime.episodeIds.isEmpty() ? null : m;
        } catch (JSONException e) {
            return null;
        }
    }
}
