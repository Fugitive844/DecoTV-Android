package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;

import io.github.fugitive844.decotv.live.M3uParser;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.model.Video;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ModelTest {
    @Test public void m3uParsesAttributesAndNames() {
        String text = "#EXTM3U x-tvg-url=\"e.xml\"\r\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" tvg-logo=\"http://l/1.png\" group-title=\"央视\",CCTV-1 综合\n"
                + "http://s/1.m3u8\n"
                + "#EXTVLCOPT:http-user-agent=x\n"
                + "#EXTINF:-1 tvg-name=\"Only, Name\",\n"
                + "http://s/2.flv\n"
                + "http://orphan\n";
        List<Channel> list = M3uParser.parse("src", text);
        assertEquals(2, list.size());
        Channel a = list.get(0);
        assertEquals("cctv1", a.tvgId);
        assertEquals("CCTV-1 综合", a.name);
        assertEquals("央视", a.group);
        assertEquals("http://l/1.png", a.logo);
        assertEquals("src-0", a.id);
        assertEquals("Only, Name", list.get(1).name);
        assertEquals("默认分组", list.get(1).group);
        assertEquals("http://s/2.flv", list.get(1).url);
    }

    @Test public void videoRoundTrip() throws Exception {
        JSONObject o = new JSONObject("{\"id\":\"9\",\"title\":\"T\",\"source\":\"s\",\"source_name\":\"S\",\"year\":\"2024\","
                + "\"episodes\":[\"a.m3u8\",\"b.m3u8\"],\"episodes_titles\":[\"第01集\"]}");
        Video v = Video.fromJson(o);
        assertEquals("s+9", v.key());
        assertEquals("第01集", v.episodeTitles.get(0));
        assertEquals("第2集", v.episodeTitles.get(1));
        Video again = Video.fromJson(v.toJson());
        assertEquals(v.episodes, again.episodes);
    }

    @Test public void recordsSortByLatestAndSplitKey() throws Exception {
        JSONObject map = new JSONObject("{\"a+1\":{\"title\":\"A\",\"save_time\":1,\"index\":2},"
                + "\"b+x+2\":{\"title\":\"B\",\"save_time\":5}}");
        List<Record> list = Record.listFrom(map);
        assertEquals("B", list.get(0).title);
        assertEquals("b", list.get(0).source());
        assertEquals("x+2", list.get(0).id());
        assertEquals(2, list.get(1).index);
        assertEquals("vod", list.get(1).favoriteJson().getString("origin"));
    }

    private static Video video(String title, String year, int episodes, String source) {
        Video v = new Video();
        v.title = title;
        v.year = year;
        v.source = source;
        v.sourceName = source;
        for (int i = 0; i < episodes; i++) v.episodes.add("e" + i + ".m3u8");
        return v;
    }

    @Test public void searchGroupsByTitleYearAndType() {
        List<Video> results = java.util.Arrays.asList(
                video("繁花", "2023", 30, "a"), video("繁 花", "2023", 30, "b"), video("繁花", "2023", 1, "c"),
                video("繁花", "2022", 30, "d"), video("完全无关", "2023", 1, "e"), video("繁华花园", "2020", 1, "f"));
        List<io.github.fugitive844.decotv.model.SearchGroups.Group> groups = io.github.fugitive844.decotv.model.SearchGroups.group(results, "繁花");
        assertEquals(4, groups.size());
        assertEquals(2, groups.get(0).items.size());
        assertEquals("a、b", groups.get(0).sourceNames(3));
        assertEquals(true, groups.get(1).movie);
        assertEquals("繁华花园", groups.get(3).title);
    }

    @Test public void liveSourceDefaultsUserAgent() throws Exception {
        assertEquals(LiveSource.DEFAULT_UA, LiveSource.fromJson(new JSONObject("{\"ua\":\" \"}")).userAgent());
        assertEquals("X/1", LiveSource.fromJson(new JSONObject("{\"ua\":\"X/1\"}")).userAgent());
    }
}
