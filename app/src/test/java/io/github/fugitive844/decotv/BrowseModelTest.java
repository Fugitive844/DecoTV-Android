package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.fugitive844.decotv.data.Prefs;
import io.github.fugitive844.decotv.model.Poster;
import io.github.fugitive844.decotv.model.SourceSite;
import io.github.fugitive844.decotv.model.TitleMatch;
import io.github.fugitive844.decotv.model.Video;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class BrowseModelTest {
    @Test public void doubanListParses() throws Exception {
        List<Poster> list = Poster.listFromDouban(new JSONObject("{\"code\":200,\"list\":[{\"id\":\"1\",\"title\":\" 繁花 \",\"poster\":\"https://img9.doubanio.com/p.jpg\",\"rate\":\"8.7\",\"year\":\"2023\"},{\"id\":\"2\",\"title\":\"\"}]}"), "movie");
        assertEquals(1, list.size());
        assertEquals("繁花", list.get(0).title);
        assertEquals("movie", list.get(0).stype);
        assertEquals("8.7", list.get(0).rate);
    }

    @Test public void bangumiPicksWeekdayAndHttpsImage() throws Exception {
        JSONArray cal = new JSONArray("[{\"weekday\":{\"en\":\"Mon\"},\"items\":[{\"id\":5,\"name\":\"A\",\"name_cn\":\"甲\",\"images\":{\"large\":\"http://lain.bgm.tv/a.jpg\"},"
                + "\"rating\":{\"score\":7.25},\"air_date\":\"2026-10-01\"},{\"id\":0,\"name\":\"bad\"}]},"
                + "{\"weekday\":{\"en\":\"Tue\"},\"items\":[{\"id\":6,\"name\":\"B\",\"name_cn\":\"\"}]}]");
        List<Poster> mon = Poster.listFromBangumi(cal, "Mon");
        assertEquals(1, mon.size());
        assertEquals("甲", mon.get(0).title);
        assertEquals("https://lain.bgm.tv/a.jpg", mon.get(0).poster);
        assertEquals("7.3", mon.get(0).rate);
        assertEquals("2026", mon.get(0).year);
        assertEquals("B", Poster.listFromBangumi(cal, "Tue").get(0).title);
        assertTrue(Poster.listFromBangumi(cal, "Sun").isEmpty());
    }

    @Test public void cmsCategoriesAcceptVariousShapes() throws Exception {
        assertEquals(2, SourceSite.categoriesFrom(new JSONObject("{\"class\":[{\"type_id\":1,\"type_name\":\"电影\"},{\"type_id\":\"2\",\"type_name\":\"剧集\"}],\"list\":[{\"vod_id\":9}]}")).size());
        List<SourceSite.Category> nested = SourceSite.categoriesFrom(new JSONObject("{\"data\":{\"typelist\":[{\"type_id\":3,\"type_name\":\"动漫\"}]}}"));
        assertEquals("3", nested.get(0).id);
        assertTrue(SourceSite.categoriesFrom(new JSONObject("{\"list\":[{\"vod_id\":1}]}")).isEmpty());
    }

    @Test public void cmsListAndPaging() throws Exception {
        JSONObject o = new JSONObject("{\"page\":1,\"pagecount\":3,\"total\":\"45\",\"list\":[{\"vod_id\":1,\"vod_name\":\"甲\",\"vod_remarks\":\"更新至10集\"},{\"vod_id\":1,\"vod_name\":\"甲\"},{\"vod_name\":\"无id\"}]}");
        List<Poster> list = Poster.listFromCms(o, "src", "某源");
        assertEquals(2, list.size());
        List<Poster> merged = new java.util.ArrayList<>();
        Poster.appendDistinct(merged, list);
        assertEquals(1, merged.size());
        assertEquals("src", merged.get(0).source);
        assertTrue(SourceSite.hasMore(o, 1, 2, 20));
        assertFalse(SourceSite.hasMore(o, 3, 2, 45));
        assertTrue(SourceSite.hasMore(new JSONObject("{\"pagecount\":2}"), 1, 20, 20));
        assertFalse(SourceSite.hasMore(new JSONObject("{}"), 1, 5, 5));
        SourceSite s = new SourceSite();
        s.api = "http://x/api.php/provide/vod";
        assertEquals("http://x/api.php/provide/vod/?ac=class", s.url("ac=class"));
        s.api = "http://x/vod/";
        assertEquals("http://x/vod/?ac=class", s.url("ac=class"));
    }

    @Test public void cmsItemCarriesPlayableVideo() throws Exception {
        JSONObject o = new JSONObject("{\"list\":[{\"vod_id\":7,\"vod_name\":\"甲\",\"vod_year\":\"2026年\",\"vod_play_url\":"
                + "\"第01集$https://a/1.html#第02集$https://a/2.html$$$第01集$https://b/1.m3u8#第02集$https://b/2.m3u8#第03集$https://b/3.m3u8#坏的$ftp://x\"},"
                + "{\"vod_id\":8,\"vod_name\":\"乙\"}]}");
        List<Poster> list = Poster.listFromCms(o, "s", "源");
        Video v = (Video) list.get(0).tag;
        assertEquals(3, v.episodes.size());
        assertEquals("https://b/2.m3u8", v.episodes.get(1));
        assertEquals("第03集", v.episodeTitles.get(2));
        assertEquals("2026", v.year);
        assertEquals("s+7", v.key());
        // 没有分集地址的条目点开时再按源和 id 查
        assertEquals(null, list.get(1).tag);
    }

    @Test public void sourceSitesHideAdultWhenFiltered() throws Exception {
        JSONArray arr = new JSONArray("[{\"key\":\"a\",\"name\":\"A\",\"api\":\"http://a\"},{\"key\":\"b\",\"name\":\"B\",\"api\":\"http://b\",\"is_adult\":true},"
                + "{\"key\":\"c\",\"api\":\"http://c\",\"disabled\":true}]");
        assertEquals(1, SourceSite.listFrom(arr, true).size());
        assertEquals(2, SourceSite.listFrom(arr, false).size());
    }

    @Test public void roleFromCookie() {
        assertEquals("user", Prefs.roleOf("%7B%22role%22%3A%22user%22%2C%22username%22%3A%22a%22%7D"));
        assertEquals("owner", Prefs.roleOf("%257B%2522role%2522%253A%2522owner%2522%257D"));
        assertEquals("", Prefs.roleOf("garbage"));
        assertEquals("", Prefs.roleOf(""));
    }

    private static Video video(String title, String year, int episodes) {
        Video v = new Video();
        v.title = title;
        v.year = year;
        for (int i = 0; i < episodes; i++) v.episodes.add("http://e/" + i);
        return v;
    }

    @Test public void titleMatchFollowsWebRules() {
        List<Video> results = Arrays.asList(video("繁 花", "2023", 30), video("繁花", "2023", 1), video("繁花", "2020", 30), video("繁花外传", "2023", 30));
        List<Video> tv = TitleMatch.filter(results, "繁花", "2023", "tv");
        assertEquals(1, tv.size());
        assertEquals(30, tv.get(0).episodes.size());
        assertEquals("2023", tv.get(0).year);
        assertEquals(1, TitleMatch.filter(results, "繁花", "2023", "movie").size());
        // 年份对不上时放宽年份
        assertEquals(2, TitleMatch.filter(results, "繁花", "1999", "tv").size());
        assertEquals(3, TitleMatch.filter(results, "繁花", "", "").size());
    }
}
