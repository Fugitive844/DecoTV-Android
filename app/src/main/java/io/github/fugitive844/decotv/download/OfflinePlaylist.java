package io.github.fugitive844.decotv.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 全部可播放缓存：沿用缓存页的影片顺序，同一影片内按集数排列。 */
public final class OfflinePlaylist {
    private static final Pattern EPISODE_NUMBER = Pattern.compile("^(?:第|ep|e)?\\s*(\\d{1,6})\\s*(?:集|话|話)?$");

    private OfflinePlaylist() {}

    /** 一部影片的可播放缓存，分集已排好序。 */
    public static final class Title {
        public final String key;
        public final List<DownloadTask> tasks = new ArrayList<>();

        Title(String key) { this.key = key; }

        public DownloadTask first() { return tasks.get(0); }
    }

    public static List<DownloadTask> build(List<DownloadTask> tasks, Predicate<DownloadTask> playable) {
        List<DownloadTask> sorted = new ArrayList<>(tasks);
        sorted.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        Map<String, List<DownloadTask>> groups = new LinkedHashMap<>();
        for (DownloadTask t : sorted) groups.computeIfAbsent(t.videoKey, key -> new ArrayList<>()).add(t);
        List<DownloadTask> result = new ArrayList<>();
        for (List<DownloadTask> group : groups.values()) {
            sortEpisodes(group);
            for (DownloadTask t : group) if (playable.test(t)) result.add(t);
        }
        return result;
    }

    /** 全部标题都带集号时按集号排（有的片源是倒序列表），否则按片源原来的分集顺序。 */
    public static void sortEpisodes(List<DownloadTask> group) {
        Comparator<DownloadTask> byIndex = Comparator.comparingInt(t -> t.episode);
        boolean numbered = true;
        for (DownloadTask t : group) numbered &= episodeNumber(t.episodeTitle) >= 0;
        group.sort(numbered ? Comparator.<DownloadTask>comparingInt(t -> episodeNumber(t.episodeTitle)).thenComparing(byIndex) : byIndex);
    }

    private static int episodeNumber(String title) {
        Matcher m = EPISODE_NUMBER.matcher(title == null ? "" : title.trim().toLowerCase(Locale.ROOT));
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 选集按钮的文字：标题带集号时只显示真实集号，缓存不全也不重新编号。 */
    public static String episodeLabel(DownloadTask t) {
        int number = episodeNumber(t.episodeTitle);
        if (number >= 0) return String.valueOf(number);
        String title = t.episodeTitle == null ? "" : t.episodeTitle.trim();
        return title.isEmpty() ? String.valueOf(t.episode + 1) : title;
    }

    /** 把队列按影片分组，保持队列顺序。 */
    public static List<Title> titles(List<DownloadTask> queue) {
        Map<String, Title> groups = new LinkedHashMap<>();
        for (DownloadTask t : queue) groups.computeIfAbsent(t.videoKey, Title::new).tasks.add(t);
        return new ArrayList<>(groups.values());
    }

    public static List<DownloadTask> sameTitle(List<DownloadTask> queue, String videoKey) {
        for (Title title : titles(queue)) if (title.key.equals(videoKey)) return title.tasks;
        return Collections.emptyList();
    }

    public static int indexOfTitle(List<Title> titles, String videoKey) {
        for (int i = 0; i < titles.size(); i++) if (titles.get(i).key.equals(videoKey)) return i;
        return -1;
    }

    public static int indexOf(List<DownloadTask> tasks, String id) {
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).id.equals(id)) return i;
        return -1;
    }

    /** 空列表没有下一项；只有一项时循环回自身。 */
    public static int step(int position, int delta, int size) {
        return size == 0 ? -1 : Math.floorMod(position + delta, size);
    }
}
