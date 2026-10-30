package io.github.fugitive844.decotv.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 海报卡片：豆瓣 / Bangumi 条目，或源浏览器里某个源的影片。
 * 豆瓣和 Bangumi 的条目点开时按标题查找播放源（与网页端 /play?title=&year=&stype= 一致）；
 * 源浏览器的条目带 source 和 id，直接打开该源的这部影片。
 */
public final class Poster {
    /** 点开时的类型提示：movie 只找单集，tv 只找多集，空表示不限。 */
    /** 首页「继续观看」的卡片附带原播放记录，点开时按记录续播。 */
    public transient Object tag;
    public String id = "", title = "", poster = "", rate = "", year = "", stype = "", source = "", sourceName = "", remarks = "";

    /** 豆瓣接口 /api/douban* 的 list。 */
    public static List<Poster> listFromDouban(JSONObject o, String stype) {
        List<Poster> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("list");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null || x.optString("title", "").isEmpty()) continue;
            Poster p = new Poster();
            p.id = x.optString("id", "");
            p.title = x.optString("title", "").trim();
            p.poster = x.optString("poster", "");
            p.rate = x.optString("rate", "");
            p.year = x.optString("year", "");
            p.stype = stype;
            out.add(p);
        }
        return out;
    }

    /** Bangumi 每日放送 https://api.bgm.tv/calendar 中某个星期（en：Mon…Sun）的条目。 */
    public static List<Poster> listFromBangumi(JSONArray calendar, String weekday) {
        List<Poster> out = new ArrayList<>();
        if (calendar == null) return out;
        for (int i = 0; i < calendar.length(); i++) {
            JSONObject day = calendar.optJSONObject(i);
            JSONObject w = day == null ? null : day.optJSONObject("weekday");
            if (w == null || !weekday.equals(w.optString("en"))) continue;
            JSONArray items = day.optJSONArray("items");
            if (items == null) break;
            for (int j = 0; j < items.length(); j++) {
                JSONObject x = items.optJSONObject(j);
                if (x == null || x.optLong("id", 0) <= 0) continue;
                Poster p = new Poster();
                p.id = String.valueOf(x.optLong("id"));
                String cn = x.optString("name_cn", "").trim();
                p.title = cn.isEmpty() ? x.optString("name", "").trim() : cn;
                if (p.title.isEmpty()) continue;
                JSONObject img = x.optJSONObject("images");
                if (img != null) {
                    for (String k : new String[]{"large", "common", "medium", "small", "grid"}) {
                        String u = img.optString(k, "");
                        if (!u.isEmpty()) {
                            p.poster = u.startsWith("http://") ? "https://" + u.substring(7) : u;
                            break;
                        }
                    }
                }
                JSONObject rating = x.optJSONObject("rating");
                double score = rating == null ? 0 : rating.optDouble("score", 0);
                p.rate = score > 0 ? String.format(java.util.Locale.ROOT, "%.1f", score) : "";
                String air = x.optString("air_date", "");
                p.year = air.length() >= 4 ? air.substring(0, 4) : "";
                out.add(p);
            }
            break;
        }
        return out;
    }

    /** 苹果 CMS 的 ?ac=videolist 返回的 list。 */
    public static List<Poster> listFromCms(JSONObject o, String source, String sourceName) {
        List<Poster> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("list");
        if (arr == null) {
            JSONObject data = o.optJSONObject("data");
            arr = data == null ? null : data.optJSONArray("list");
        }
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null) continue;
            Poster p = new Poster();
            p.id = x.optString("vod_id", "");
            p.title = x.optString("vod_name", "").trim();
            if (p.id.isEmpty() || p.title.isEmpty()) continue;
            p.poster = x.optString("vod_pic", "");
            p.year = x.optString("vod_year", "");
            p.remarks = x.optString("vod_remarks", "");
            p.source = source;
            p.sourceName = sourceName;
            // 片单里通常已带分集地址，点开时直接播放，不用再搜索或取详情
            Video v = new Video();
            v.id = p.id;
            v.title = p.title;
            v.poster = p.poster;
            v.source = source;
            v.sourceName = sourceName;
            String year = x.optString("vod_year", "");
            v.year = year.matches(".*\\d{4}.*") ? year.replaceAll(".*?(\\d{4}).*", "$1") : "";
            v.desc = x.optString("vod_content", "");
            v.typeName = x.optString("type_name", "");
            v.remarks = p.remarks;
            v.parsePlayUrl(x.optString("vod_play_url", ""));
            if (!v.episodes.isEmpty()) p.tag = v;
            out.add(p);
        }
        return out;
    }

    /** 分页合并时按 id 去重（源浏览器）或按标题 + 年份去重（豆瓣）。 */
    public static void appendDistinct(List<Poster> into, List<Poster> more) {
        Set<String> seen = new LinkedHashSet<>();
        for (Poster p : into) seen.add(p.key());
        for (Poster p : more) if (seen.add(p.key())) into.add(p);
    }

    String key() { return source.isEmpty() ? "d:" + id + ":" + title + ":" + year : source + "+" + id; }
}
