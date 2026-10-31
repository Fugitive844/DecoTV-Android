package io.github.fugitive844.decotv.model;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** 搜索结果相关性排序，规则与服务端 search-ranking.ts 相同。 */
public final class SearchRanking {
    private SearchRanking() {}

    private static int levenshtein(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int next = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), diagonal + cost);
                diagonal = row[j];
                row[j] = next;
            }
        }
        return row[b.length()];
    }

    private static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1 : 1 - (double) levenshtein(a, b) / max;
    }

    private static boolean containsInOrder(String title, String keyword) {
        int k = 0;
        for (int i = 0; i < title.length() && k < keyword.length(); i++) {
            if (title.charAt(i) == keyword.charAt(k)) k++;
        }
        return k == keyword.length();
    }

    private static int year(Video v) {
        try {
            return Integer.parseInt(v.year.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static double score(Video v, String query, int currentYear) {
        String title = v.title.trim(), keyword = query.trim();
        if (title.isEmpty() || keyword.isEmpty()) return 0;
        String titleNoSpace = title.replaceAll("\\s+", ""), keywordNoSpace = keyword.replaceAll("\\s+", "");
        double score = 0;
        if (titleNoSpace.equals(keywordNoSpace)) score = 100;
        else if (titleNoSpace.startsWith(keywordNoSpace)) score = 80;
        else if (titleNoSpace.contains(keywordNoSpace)) score = 60;
        else if (containsInOrder(titleNoSpace, keywordNoSpace)) score = 20 + similarity(titleNoSpace, keywordNoSpace) * 20;
        int year = year(v);
        if (year > 0) {
            int diff = currentYear - year;
            if (diff >= 0) {
                if (diff <= 5) score += 10 - diff;
                else if (diff <= 10) score += 5;
                else if (diff <= 20) score += 2;
            }
        }
        if (v.doubanId > 0) score += 5;
        return Math.min(score, 110);
    }

    private static final class Scored {
        final Video video;
        final double score;

        Scored(Video video, double score) {
            this.video = video;
            this.score = score;
        }
    }

    /** 按分数、年份、标题排序；有相关结果时去掉 0 分的，全部 0 分时都保留。 */
    public static List<Video> rank(List<Video> videos, String query) {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        List<Scored> scored = new ArrayList<>();
        for (Video v : videos) {
            if (!v.title.isEmpty()) scored.add(new Scored(v, score(v, query, currentYear)));
        }
        Collections.sort(scored, (a, b) -> {
            if (a.score != b.score) return Double.compare(b.score, a.score);
            int yearA = year(a.video), yearB = year(b.video);
            if (yearA != yearB) return Integer.compare(yearB, yearA);
            return a.video.title.compareTo(b.video.title);
        });
        List<Video> relevant = new ArrayList<>(), all = new ArrayList<>();
        for (Scored s : scored) {
            all.add(s.video);
            if (s.score > 0) relevant.add(s.video);
        }
        return relevant.isEmpty() ? all : relevant;
    }
}
