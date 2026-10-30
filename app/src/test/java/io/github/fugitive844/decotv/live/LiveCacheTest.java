package io.github.fugitive844.decotv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

public class LiveCacheTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static LiveSource source(String key, String url) {
        LiveSource s = new LiveSource();
        s.key = key;
        s.name = "源 " + key;
        s.url = url;
        return s;
    }

    private static Channel channel(String name, String url) {
        Channel c = new Channel();
        c.name = name;
        c.group = "央视频道";
        c.url = url;
        return c;
    }

    @Test public void keepsChannelsUntilSourceDisappears() throws Exception {
        LiveCache cache = new LiveCache(tmp.getRoot());
        LiveSource a = source("a/1", "http://a.m3u"), b = source("b", "http://b.m3u");
        cache.saveSources(Arrays.asList(a, b));
        cache.saveChannels(a, Arrays.asList(channel("CCTV-1", "http://1"), channel("CCTV-1", "http://2")), false);
        cache.saveChannels(b, Collections.singletonList(channel("CCTV-2", "http://3")), true);

        assertEquals(2, cache.sources().size());
        LiveCache.Entry entry = cache.channels("a/1");
        assertNotNull(entry);
        assertEquals(2, entry.channels.size());
        assertEquals("http://2", entry.channels.get(1).url);
        assertFalse(entry.stale(a));
        assertTrue("源地址变了要重新拉取", entry.stale(source("a/1", "http://new.m3u")));
        assertTrue(cache.channels("b").relayed);

        cache.saveSources(Collections.singletonList(b));
        assertNull("服务端已不存在的源，频道缓存应删除", cache.channels("a/1"));
        assertNotNull(cache.channels("b"));
        assertEquals("b", cache.sources().get(0).key);
    }

    @Test public void brokenFilesAreDeletedAndRewritten() throws Exception {
        LiveCache cache = new LiveCache(tmp.getRoot());
        LiveSource a = source("a", "http://a.m3u");
        cache.saveSources(Collections.singletonList(a));
        java.io.File channels = cache.channelsFile("a");
        java.io.File sources = new java.io.File(channels.getParentFile(), "sources.json");

        // 写坏的 JSON
        java.nio.file.Files.write(channels.toPath(), "{\"v\":1,\"channels\":[{\"url\":".getBytes("UTF-8"));
        assertNull(cache.channels("a"));
        assertFalse("坏文件应被删除", channels.exists());
        // 旧版本格式
        java.nio.file.Files.write(channels.toPath(), "{\"channels\":[{\"url\":\"http://1\"}]}".getBytes("UTF-8"));
        assertNull(cache.channels("a"));
        assertFalse(channels.exists());
        // 结构不对
        java.nio.file.Files.write(sources.toPath(), "{\"v\":1,\"sources\":\"oops\"}".getBytes("UTF-8"));
        assertTrue(cache.sources().isEmpty());
        assertFalse(sources.exists());
        // 同名目录占住了文件位置
        assertTrue(channels.mkdirs());
        assertNull(cache.channels("a"));
        cache.saveChannels(a, Collections.singletonList(channel("CCTV-1", "http://1")), false);
        assertEquals(1, cache.channels("a").channels.size());
        // 缓存目录本身被一个文件占住
        java.io.File dir = channels.getParentFile();
        LiveCache.discard(dir);
        java.nio.file.Files.write(dir.toPath(), new byte[]{1, 2, 3});
        assertTrue(cache.sources().isEmpty());
        assertNull(cache.channels("a"));
        cache.saveSources(Collections.singletonList(a));
        assertEquals(1, cache.sources().size());
    }

    @Test public void futureTimestampIsStale() throws Exception {
        LiveCache cache = new LiveCache(tmp.getRoot());
        LiveSource a = source("a", "http://a.m3u");
        cache.saveChannels(a, Collections.singletonList(channel("CCTV-1", "http://1")), false);
        java.io.File f = cache.channelsFile("a");
        String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        org.json.JSONObject o = new org.json.JSONObject(text).put("savedAt", System.currentTimeMillis() + 86400000L);
        java.nio.file.Files.write(f.toPath(), o.toString().getBytes("UTF-8"));
        assertTrue("保存时间在将来要视为过期", cache.channels("a").stale(a));
    }
}
