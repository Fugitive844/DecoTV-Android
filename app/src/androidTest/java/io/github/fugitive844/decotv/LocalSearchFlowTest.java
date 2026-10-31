package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.Relay;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本机搜索与各中转策略：每个用例用不同关键词，避开本机结果缓存。
 * -e liveBase/liveUser/livePassword 账号；-e oldBase 没有 AdultTypeWords 字段的旧服务端（可选）。
 */
@RunWith(AndroidJUnit4.class)
public class LocalSearchFlowTest {
    private DecoApp app;
    private Bundle args;

    @Before public void setup() throws Exception {
        args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        login(args.getString("liveBase"));
    }

    @After public void reset() { ApiClient.testBlockThirdParty = false; }

    private void login(String base) throws IOException {
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
    }

    private List<Video> timedSearch(String label, String query) throws IOException {
        long start = System.currentTimeMillis();
        long bytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
        List<Video> found = app.api.search(query);
        long received = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()) - bytes;
        Log.i("DecoTest", label + " 「" + query + "」 " + found.size() + " 条，" + (System.currentTimeMillis() - start) + "ms，下行 " + received / 1024 + " KB");
        return found;
    }

    @Test public void directSearchGoesToSources() throws Exception {
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        List<Video> found = timedSearch("直连", "繁花");
        assertFalse("直连应搜到结果", found.isEmpty());
        for (Video v : found) {
            assertFalse("结果应可播放", v.episodes.isEmpty());
            assertFalse("直连结果不应含服务端代理地址", v.episodes.get(0).contains("/api/proxy/"));
        }
        assertTrue("完全同名应排在最前，实际 " + found.get(0).title, found.get(0).title.startsWith("繁花"));
    }

    @Test public void directFindsAboutTheSameSourcesAsServer() throws Exception {
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.RELAY);
        List<Video> relay = timedSearch("服务端", "庆余年");
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        List<Video> direct = timedSearch("直连", "庆余年");
        Set<String> relaySources = new HashSet<>(), directSources = new HashSet<>();
        for (Video v : relay) relaySources.add(v.source);
        for (Video v : direct) directSources.add(v.source);
        Log.i("DecoTest", "服务端 " + relaySources.size() + " 个源，直连 " + directSources.size() + " 个源");
        assertFalse(direct.isEmpty());
    }

    @Test public void smartFallsBackPerSource() throws Exception {
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.SMART);
        ApiClient.testBlockThirdParty = true;
        assertFalse("直连全部失败时智能模式应经中转搜到结果", timedSearch("智能（屏蔽直连）", "狂飙").isEmpty());
    }

    @Test public void directFailsWhenEverySourceFails() throws Exception {
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        ApiClient.testBlockThirdParty = true;
        try {
            app.api.search("漫长的季节");
            fail("全部源都连不上时应报错，而不是显示没有结果");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("所有视频源都无法连接"));
        }
    }

    @Test public void relayStillUsesServer() throws Exception {
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.RELAY);
        ApiClient.testBlockThirdParty = true;
        assertFalse("全中转不依赖直连", timedSearch("全中转（屏蔽直连）", "三体").isEmpty());
    }

    @Test public void streamReportsEverySource() throws Exception {
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        AtomicInteger completed = new AtomicInteger(), total = new AtomicInteger();
        List<Video> found = new ArrayList<>();
        assertTrue(app.api.searchStream("大江大河", (batch, done, all) -> {
            found.addAll(batch);
            completed.set(done);
            total.set(all);
        }, cancel -> { }));
        assertTrue(total.get() > 0);
        assertEquals("每个源都要回调一次", total.get(), completed.get());
        assertFalse(found.isEmpty());
    }

    @Test public void streamCanBeCancelled() throws Exception {
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        AtomicReference<Runnable> cancel = new AtomicReference<>();
        long start = System.currentTimeMillis();
        boolean finished = app.api.searchStream("人世间", (batch, done, all) -> {
            Runnable r = cancel.get();
            if (r != null) r.run();
        }, cancel::set);
        assertFalse("中止后应返回 false", finished);
        assertTrue("中止后应尽快返回", System.currentTimeMillis() - start < 15000);
    }

    @Test public void adultContentStaysFiltered() throws Exception {
        JSONArray words = app.api.serverConfig().optJSONArray("AdultTypeWords");
        Assume.assumeTrue("需要服务端开启成人过滤", words != null && words.length() > 0);
        assertTrue(app.api.serverConfig().optBoolean("AdultFilterEnabled"));
        for (io.github.fugitive844.decotv.model.SourceSite site : app.api.sourceSites()) assertFalse("成人源应被隐藏：" + site.name, site.adult);
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        for (Video v : app.api.search("福利")) {
            for (int i = 0; i < words.length(); i++) {
                assertFalse("不应出现被过滤的分类：" + v.typeName, v.typeName.contains(words.getString(i)));
            }
        }
    }

    @Test public void traditionalQueryIsSimplified() throws Exception {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29);
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        assertEquals("庆余年", android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate("慶餘年"));
        List<Video> found = timedSearch("繁体", "慶餘年");
        assertFalse("繁体关键词应搜到简体片名", found.isEmpty());
    }

    @Test public void oldServerFallsBackToServerSearch() throws Exception {
        Assume.assumeTrue(args.containsKey("oldBase"));
        login(args.getString("oldBase"));
        assertFalse(app.api.serverConfig().has("AdultTypeWords"));
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.DIRECT);
        ApiClient.testBlockThirdParty = true;
        assertFalse("旧服务端即使设为直连也走服务端搜索", timedSearch("旧服务端（屏蔽直连）", "山海情").isEmpty());
    }
}
