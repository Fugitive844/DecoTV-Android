package io.github.fugitive844.decotv.download;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class OfflinePlaylistTest {
    private static DownloadTask task(String id, String movie, int episode, long created) {
        DownloadTask t = new DownloadTask();
        t.id = id;
        t.videoKey = movie;
        t.episode = episode;
        t.createdAt = created;
        t.state = DownloadTask.State.DONE;
        return t;
    }

    @Test public void threeMoviesAndSeriesStayGroupedAndWrapBothWays() {
        List<DownloadTask> queue = OfflinePlaylist.build(Arrays.asList(
                task("a", "movie-a", 0, 100), task("b", "movie-b", 0, 200),
                task("ep3", "series", 2, 250), task("c", "movie-c", 0, 300),
                task("ep1", "series", 0, 400)), t -> true);
        assertEquals(Arrays.asList("ep1", "ep3", "c", "b", "a"),
                queue.stream().map(t -> t.id).collect(Collectors.toList()));
        assertEquals("c", queue.get(OfflinePlaylist.step(1, 1, queue.size())).id);
        assertEquals("ep1", queue.get(OfflinePlaylist.step(4, 1, queue.size())).id);
        assertEquals("a", queue.get(OfflinePlaylist.step(0, -1, queue.size())).id);
    }

    @Test public void unreadyTasksAreSkippedWithoutChangingGroupOrder() {
        DownloadTask queued = task("queued", "series", 1, 500);
        queued.state = DownloadTask.State.QUEUED;
        DownloadTask streaming = task("stream", "series", 2, 200);
        streaming.state = DownloadTask.State.DOWNLOADING;
        List<DownloadTask> queue = OfflinePlaylist.build(Arrays.asList(
                task("movie", "movie", 0, 300), queued, streaming,
                task("ep1", "series", 0, 100)), t -> t.state == DownloadTask.State.DONE || t.id.equals("stream"));
        assertEquals(Arrays.asList("ep1", "stream", "movie"),
                queue.stream().map(t -> t.id).collect(Collectors.toList()));
        assertEquals(1, OfflinePlaylist.indexOf(queue, "stream"));
        assertEquals(-1, OfflinePlaylist.indexOf(queue, "queued"));
    }

    private static DownloadTask titled(String id, int episode, String title, long created) {
        DownloadTask t = task(id, "series", episode, created);
        t.episodeTitle = title;
        return t;
    }

    @Test public void thirtyEpisodesCachedOutOfOrderSortByRealNumberAndKeepLabels() {
        List<DownloadTask> shuffled = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) shuffled.add(titled("ep" + (i + 1), i, "第" + (i + 1) + "集", 1000 + i));
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));
        shuffled.removeIf(t -> t.id.equals("ep2"));
        List<DownloadTask> queue = OfflinePlaylist.build(shuffled, t -> true);
        assertEquals(29, queue.size());
        assertEquals("1", OfflinePlaylist.episodeLabel(queue.get(0)));
        assertEquals("缺第 2 集时不能重新编号", "3", OfflinePlaylist.episodeLabel(queue.get(1)));
        assertEquals("30", OfflinePlaylist.episodeLabel(queue.get(28)));
    }

    @Test public void reversedSourcesSortByNumberAndNamedEpisodesKeepSourceOrder() {
        List<DownloadTask> reversed = OfflinePlaylist.build(Arrays.asList(
                titled("e3", 0, "第3集", 1), titled("e2", 1, "第2集", 2), titled("e1", 2, "EP01", 3)), t -> true);
        assertEquals(Arrays.asList("e1", "e2", "e3"), reversed.stream().map(t -> t.id).collect(Collectors.toList()));
        List<DownloadTask> named = OfflinePlaylist.build(Arrays.asList(
                titled("b", 1, "下", 1), titled("a", 0, "上", 2), titled("c", 2, "第3集", 3)), t -> true);
        assertEquals(Arrays.asList("a", "b", "c"), named.stream().map(t -> t.id).collect(Collectors.toList()));
        assertEquals("上", OfflinePlaylist.episodeLabel(named.get(0)));
    }

    @Test public void titlesGroupQueueAndSameTitleStaysInsideOneVideo() {
        List<DownloadTask> queue = OfflinePlaylist.build(Arrays.asList(
                task("a", "movie-a", 0, 100), task("ep2", "series", 1, 300), task("ep1", "series", 0, 200)), t -> true);
        List<OfflinePlaylist.Title> titles = OfflinePlaylist.titles(queue);
        assertEquals(2, titles.size());
        assertEquals("ep1", titles.get(0).first().id);
        assertEquals(1, OfflinePlaylist.indexOfTitle(titles, "movie-a"));
        assertEquals(Arrays.asList("ep1", "ep2"),
                OfflinePlaylist.sameTitle(queue, "series").stream().map(t -> t.id).collect(Collectors.toList()));
        assertEquals(0, OfflinePlaylist.sameTitle(queue, "gone").size());
    }

    @Test public void singleItemLoopsAndEmptyQueueHasNoTarget() {
        assertEquals(0, OfflinePlaylist.step(0, -1, 1));
        assertEquals(0, OfflinePlaylist.step(0, 1, 1));
        assertEquals(-1, OfflinePlaylist.step(0, 1, 0));
    }
}
