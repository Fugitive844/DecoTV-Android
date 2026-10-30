package io.github.fugitive844.decotv.danmaku;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 一条弹幕。数据与网页端相同，来自服务端 /api/danmu-external：time 秒、mode 0 滚动 / 1 顶部 / 2 底部。 */
public final class Danmaku {
    public static final int SCROLL = 0, TOP = 1, BOTTOM = 2;

    public final long timeMs;
    public final String text;
    public final int color, mode;

    public Danmaku(long timeMs, String text, int color, int mode) {
        this.timeMs = timeMs;
        this.text = text;
        this.color = color;
        this.mode = mode;
    }

    /** 服务端匹配到的剧集，界面上显示「匹配：xx · 第 x 集」。 */
    public static final class Match {
        public final String animeTitle, episodeTitle;
        public final long episodeId;

        Match(String animeTitle, String episodeTitle, long episodeId) {
            this.animeTitle = animeTitle;
            this.episodeTitle = episodeTitle;
            this.episodeId = episodeId;
        }
    }

    /** 一次加载的结果。 */
    public static final class Result {
        public final List<Danmaku> items;
        public final Match match;

        Result(List<Danmaku> items, Match match) {
            this.items = items;
            this.match = match;
        }
    }

    static int parseColor(String s) {
        if (s == null) return 0xFFFFFFFF;
        String hex = s.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return 0xFFFFFFFF;
        try {
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    /** 解析 /api/danmu-external 的响应，按时间排序。 */
    public static Result parse(JSONObject o) {
        List<Danmaku> list = new ArrayList<>();
        JSONArray arr = o.optJSONArray("danmus");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                String text = d.optString("text", "").trim();
                double time = d.optDouble("time", -1);
                if (text.isEmpty() || !(time >= 0)) continue;
                int mode = d.optInt("mode", SCROLL);
                if (mode != TOP && mode != BOTTOM) mode = SCROLL;
                list.add(new Danmaku(Math.round(time * 1000), text, parseColor(d.optString("color", "")), mode));
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        JSONObject m = o.optJSONObject("match");
        Match match = m == null ? null : new Match(m.optString("animeTitle", ""), m.optString("episodeTitle", ""), m.optLong("episodeId", 0));
        return new Result(list, match);
    }

    /** 屏蔽词：每行或逗号分隔一个，忽略大小写包含匹配。 */
    public static List<String> keywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String k : raw.split("[,，\\n]")) {
            String t = k.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 按类型屏蔽与屏蔽词判断是否显示。 */
    public boolean allowed(boolean[] blockedModes, List<String> keywords) {
        if (blockedModes[mode]) return false;
        if (keywords.isEmpty()) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String k : keywords) if (lower.contains(k)) return false;
        return true;
    }
}
