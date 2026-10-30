package io.github.fugitive844.decotv.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 豆瓣 / Bangumi 条目按标题找播放源，与网页端 /play?title=&year=&stype= 的筛选一致：
 * 去空格后同名、年份（前 4 位数字）相同、电影只要 1 集 / 剧集要多集。年份对不上时放宽年份再找一次。
 */
public final class TitleMatch {
    private static final Pattern YEAR = Pattern.compile("\\d{4}");

    private TitleMatch() {}

    static String norm(String s) { return s == null ? "" : s.replace(" ", "").toLowerCase(Locale.ROOT); }

    static String year(String s) {
        Matcher m = YEAR.matcher(s == null ? "" : s);
        return m.find() ? m.group() : "";
    }

    public static List<Video> filter(List<Video> results, String title, String wantYear, String stype) {
        List<Video> exact = new ArrayList<>(), anyYear = new ArrayList<>();
        String t = norm(title), y = year(wantYear);
        for (Video v : results) {
            if (v.episodes.isEmpty() || !norm(v.title).equals(t)) continue;
            if ("movie".equals(stype) && v.episodes.size() != 1) continue;
            if ("tv".equals(stype) && v.episodes.size() <= 1) continue;
            anyYear.add(v);
            if (y.isEmpty() || y.equals(year(v.year))) exact.add(v);
        }
        return exact.isEmpty() ? anyYear : exact;
    }
}
