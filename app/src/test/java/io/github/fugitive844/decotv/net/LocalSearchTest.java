package io.github.fugitive844.decotv.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.fugitive844.decotv.model.SearchRanking;
import io.github.fugitive844.decotv.model.SourceSite;
import io.github.fugitive844.decotv.model.Video;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LocalSearchTest {
    private static Video video(String title, String year) {
        Video v = new Video();
        v.id = title;
        v.title = title;
        v.year = year;
        return v;
    }

    private static List<String> titles(List<Video> videos) {
        List<String> out = new ArrayList<>();
        for (Video v : videos) out.add(v.title);
        return out;
    }

    private static SourceSite site(String api) {
        SourceSite s = new SourceSite();
        s.key = "a";
        s.name = "源A";
        s.api = api;
        return s;
    }

    @Test public void searchUrlMatchesServerFormat() {
        assertEquals("https://x.test/api.php/provide/vod?ac=videolist&wd=%E4%BD%A0", site("https://x.test/api.php/provide/vod").searchUrl("你", 1));
        assertEquals("https://x.test/v?ac=videolist&wd=a&pg=3", site("https://x.test/v").searchUrl("a", 3));
        assertEquals("https://x.test/v?k=1&ac=videolist&wd=a", site("https://x.test/v?k=1").searchUrl("a", 1));
    }

    @Test public void rankingFollowsServerRules() {
        List<Video> ranked = SearchRanking.rank(Arrays.asList(video("长安的荔枝花絮", ""), video("荔枝", ""), video("荔枝园", ""),
                video("毫不相关", ""), video("荔 枝", "1990")), "荔枝");
        assertEquals(Arrays.asList("荔 枝", "荔枝", "荔枝园", "长安的荔枝花絮"), titles(ranked));
    }

    @Test public void rankingKeepsAllWhenNothingMatches() {
        assertEquals(1, SearchRanking.rank(Collections.singletonList(video("甲", "")), "乙").size());
    }

    @Test public void newerYearsScoreHigher() {
        assertEquals(110, SearchRanking.score(video("荔枝", "2026"), "荔枝", 2026), 0);
        assertEquals(105, SearchRanking.score(video("荔枝", "2021"), "荔枝", 2026), 0);
        assertEquals(100, SearchRanking.score(video("荔枝", "1990"), "荔枝", 2026), 0);
    }

    @Test public void pageKeepsOnlyPlayableItems() throws Exception {
        JSONArray list = new JSONArray()
                .put(new JSONObject().put("vod_id", 1).put("vod_name", " 甲   乙 ").put("vod_play_url", "第1集$https://x.test/1.m3u8")
                        .put("vod_year", "2024年").put("vod_content", "<p>简介</p>").put("type_name", "动作片"))
                .put(new JSONObject().put("vod_id", 2).put("vod_name", "无地址").put("vod_play_url", "第1集$ftp://x"))
                .put(new JSONObject().put("vod_name", "无 id").put("vod_play_url", "1$https://x.test/2.m3u8"));
        List<Video> videos = LocalSearch.playable(new JSONObject().put("list", list), site("https://x.test/v"));
        assertEquals(1, videos.size());
        Video v = videos.get(0);
        assertEquals("1", v.id);
        assertEquals("甲 乙", v.title);
        assertEquals("2024", v.year);
        assertEquals("简介", v.desc);
        assertEquals("a", v.source);
        assertEquals("源A", v.sourceName);
    }

    @Test public void blockedTypesAreRemoved() {
        Video kept = video("甲", ""), blocked = video("乙", "");
        kept.typeName = "动作片";
        blocked.typeName = "某敏感分类";
        List<Video> videos = Arrays.asList(kept, blocked);
        assertEquals(Arrays.asList("甲"), titles(LocalSearch.blockedRemoved(videos, Collections.singletonList("敏感"))));
        assertEquals(2, LocalSearch.blockedRemoved(videos, Collections.emptyList()).size());
    }

    @Test public void pageParsingToleratesBomAndRejectsHtml() throws Exception {
        assertTrue(LocalSearch.parsePage("﻿{\"list\":[]}").has("list"));
        try {
            LocalSearch.parsePage("<html>forbidden</html>");
            throw new AssertionError("应当报错");
        } catch (IOException expected) {
            assertEquals("该源返回的不是有效数据", expected.getMessage());
        }
    }

    @Test public void searchIsSmartByDefault() {
        assertEquals(Relay.Mode.SMART, Relay.Item.SEARCH.defaultMode);
        assertEquals(Relay.Mode.DIRECT, Relay.Item.VOD.defaultMode);
    }
}
