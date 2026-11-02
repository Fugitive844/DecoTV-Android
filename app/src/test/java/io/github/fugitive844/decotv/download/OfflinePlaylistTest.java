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

    @Test public void singleItemLoopsAndEmptyQueueHasNoTarget() {
        assertEquals(0, OfflinePlaylist.step(0, -1, 1));
        assertEquals(0, OfflinePlaylist.step(0, 1, 1));
        assertEquals(-1, OfflinePlaylist.step(0, 1, 0));
    }
}
