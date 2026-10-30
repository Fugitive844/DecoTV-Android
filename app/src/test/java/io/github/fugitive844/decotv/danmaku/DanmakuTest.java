package io.github.fugitive844.decotv.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class DanmakuTest {
    @Test public void parsesServerResponseSortedWithModesAndColors() throws Exception {
        JSONObject o = new JSONObject("{\"code\":200,\"count\":4,\"danmus\":["
                + "{\"time\":12.5,\"text\":\" 后 \",\"color\":\"#ff0000\",\"mode\":1},"
                + "{\"time\":1.25,\"text\":\"先\",\"mode\":2},"
                + "{\"time\":3,\"text\":\"\"},"
                + "{\"time\":5,\"text\":\"怪模式\",\"mode\":7,\"color\":\"bad\"}],"
                + "\"match\":{\"animeTitle\":\"庆余年\",\"episodeTitle\":\"第1集\",\"episodeId\":10146}}");
        Danmaku.Result r = Danmaku.parse(o);
        assertEquals(3, r.items.size());
        assertEquals(1250, r.items.get(0).timeMs);
        assertEquals(Danmaku.BOTTOM, r.items.get(0).mode);
        assertEquals(0xFFFFFFFF, r.items.get(0).color);
        assertEquals(Danmaku.SCROLL, r.items.get(1).mode);
        assertEquals("后", r.items.get(2).text);
        assertEquals(0xFFFF0000, r.items.get(2).color);
        assertNotNull(r.match);
        assertEquals(10146, r.match.episodeId);
        assertNull(Danmaku.parse(new JSONObject("{\"danmus\":[]}")).match);
    }

    @Test public void filtersByModeAndKeyword() {
        List<String> keywords = Danmaku.keywords("剧透， AAA\n\n广告");
        assertEquals(Arrays.asList("剧透", "aaa", "广告"), keywords);
        boolean[] none = new boolean[3], noTop = {false, true, false};
        assertTrue(new Danmaku(0, "好看", 0, Danmaku.SCROLL).allowed(none, keywords));
        assertFalse(new Danmaku(0, "前方剧透", 0, Danmaku.SCROLL).allowed(none, keywords));
        assertFalse(new Danmaku(0, "xaaAx", 0, Danmaku.SCROLL).allowed(none, keywords));
        assertFalse(new Danmaku(0, "顶部", 0, Danmaku.TOP).allowed(noTop, keywords));
    }

    @Test public void scrollLanesWaitUntilPreviousFullyEntered() {
        long[] starts = {Long.MIN_VALUE, Long.MIN_VALUE};
        float[] widths = new float[2];
        float speed = 0.1f, gap = 10;
        assertEquals(0, DanmakuView.pickScroll(0, starts, widths, speed, gap));
        starts[0] = 0;
        widths[0] = 90;
        // 第一行的弹幕需要 (90+10)/0.1 = 1000ms 才完全进入
        assertEquals(1, DanmakuView.pickScroll(500, starts, widths, speed, gap));
        starts[1] = 500;
        widths[1] = 40;
        assertEquals(-1, DanmakuView.pickScroll(900, starts, widths, speed, gap));
        assertEquals(0, DanmakuView.pickScroll(1000, starts, widths, speed, gap));
    }

    @Test public void fixedLanesFreeAfterExpiry() {
        long[] until = {5000, 0};
        assertEquals(1, DanmakuView.pickFixed(100, until));
        until[1] = 6000;
        assertEquals(-1, DanmakuView.pickFixed(4999, until));
        assertEquals(0, DanmakuView.pickFixed(5000, until));
    }

    @Test public void lowerBoundFindsFirstAtOrAfter() {
        List<Danmaku> list = Arrays.asList(new Danmaku(100, "a", 0, 0), new Danmaku(200, "b", 0, 0), new Danmaku(200, "c", 0, 0));
        assertEquals(0, DanmakuView.lowerBound(list, 0));
        assertEquals(1, DanmakuView.lowerBound(list, 150));
        assertEquals(1, DanmakuView.lowerBound(list, 200));
        assertEquals(3, DanmakuView.lowerBound(list, 201));
    }

    @Test public void manualMatchFollowsEpisodeOffset() throws Exception {
        List<ManualMatch.Anime> animes = ManualMatch.parseSearch(new JSONObject("{\"animes\":["
                + "{\"animeId\":7,\"animeTitle\":\"某剧\",\"typeDescription\":\"电视剧\",\"episodes\":["
                + "{\"episodeId\":71,\"episodeTitle\":\"预告\"},{\"episodeId\":72,\"episodeTitle\":\"第1集\"},{\"episodeId\":73,\"episodeTitle\":\"第2集\"}]},"
                + "{\"animeId\":8,\"animeTitle\":\"空\",\"episodes\":[]}]}"));
        assertEquals(1, animes.size());
        ManualMatch m = new ManualMatch();
        m.anime = animes.get(0);
        m.delta = 1;
        ManualMatch back = ManualMatch.fromJson(m.toJson());
        assertNotNull(back);
        assertEquals(1, back.target(0));
        assertEquals(73L, (long) back.anime.episodeIds.get(back.target(1)));
        assertEquals(-1, back.target(2));
        assertNull(ManualMatch.fromJson("not json"));
    }
}
