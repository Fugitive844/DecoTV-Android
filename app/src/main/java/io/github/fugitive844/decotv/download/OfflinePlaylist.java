package io.github.fugitive844.decotv.download;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 全部可播放缓存：沿用缓存页的影片顺序，同一影片内按集数排列。 */
public final class OfflinePlaylist {
    private OfflinePlaylist() {}

    public static List<DownloadTask> build(List<DownloadTask> tasks, Predicate<DownloadTask> playable) {
        List<DownloadTask> sorted = new ArrayList<>(tasks);
        sorted.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        Map<String, List<DownloadTask>> groups = new LinkedHashMap<>();
        for (DownloadTask t : sorted) groups.computeIfAbsent(t.videoKey, key -> new ArrayList<>()).add(t);
        List<DownloadTask> result = new ArrayList<>();
        for (List<DownloadTask> group : groups.values()) {
            group.sort((a, b) -> Integer.compare(a.episode, b.episode));
            for (DownloadTask t : group) if (playable.test(t)) result.add(t);
        }
        return result;
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
