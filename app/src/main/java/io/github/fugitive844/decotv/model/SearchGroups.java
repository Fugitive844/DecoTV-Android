package io.github.fugitive844.decotv.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 搜索结果聚合，与网页端一致：只保留与关键词相关的标题，按「去空格标题 + 年份 + 电影/剧集」分组，保持出现顺序。 */
public final class SearchGroups {
    public static final class Group {
        public final String title, year;
        public final boolean movie;
        public final List<Video> items = new ArrayList<>();

        Group(String title, String year, boolean movie) {
            this.title = title;
            this.year = year;
            this.movie = movie;
        }

        public String poster() {
            for (Video v : items) if (!v.poster.isEmpty()) return v.poster;
            return "";
        }

        public List<String> posters() {
            List<String> out = new ArrayList<>();
            for (Video v : items) if (!v.poster.isEmpty()) out.add(v.poster);
            return out;
        }

        public int maxEpisodes() {
            int max = 0;
            for (Video v : items) max = Math.max(max, v.episodes.size());
            return max;
        }

        public String sourceNames(int limit) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < items.size() && i < limit; i++) {
                if (i > 0) out.append("、");
                out.append(items.get(i).sourceName);
            }
            if (items.size() > limit) out.append(" 等");
            return out.toString();
        }
    }

    private SearchGroups() {}

    /** 标题包含关键词，或按顺序包含关键词的每个字（都忽略空格与大小写）。 */
    public static boolean relevant(String title, String query) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String tn = t.replaceAll("\\s+", ""), qn = q.replaceAll("\\s+", "");
        if (t.isEmpty()) return false;
        if (t.contains(q) || tn.contains(qn)) return true;
        int j = 0;
        for (int i = 0; i < tn.length() && j < qn.length(); i++) {
            if (tn.charAt(i) == qn.charAt(j)) j++;
        }
        return j == qn.length();
    }

    public static List<Group> group(List<Video> results, String query) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (Video v : results) {
            if (!relevant(v.title, query)) continue;
            boolean movie = v.episodes.size() == 1;
            String key = v.title.replace(" ", "") + "-" + (v.year.isEmpty() ? "unknown" : v.year) + "-" + (movie ? "movie" : "tv");
            Group g = map.get(key);
            if (g == null) {
                g = new Group(v.title, v.year, movie);
                map.put(key, g);
            }
            g.items.add(v);
        }
        return new ArrayList<>(map.values());
    }
}
