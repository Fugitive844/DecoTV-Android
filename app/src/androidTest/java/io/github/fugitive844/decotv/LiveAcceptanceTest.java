package io.github.fugitive844.decotv;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.Espresso;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.live.ChannelMerge;
import io.github.fugitive844.decotv.live.LiveCache;
import io.github.fugitive844.decotv.live.LiveChannels;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.LiveActivity;
import io.github.fugitive844.decotv.ui.MainActivity;
import io.github.fugitive844.decotv.ui.PlayerActivity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1.4.0 之后待测清单的验收：播放页频道列表定位当前频道、齿轮按钮换源、直播列表本地缓存与自动修复。
 */
@RunWith(AndroidJUnit4.class)
public class LiveAcceptanceTest {
    private static final String TAG = "DecoTest";
    private DecoApp app;
    private File cacheDir;

    @Before public void setup() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.DIRECT);
        cacheDir = new File(app.getFilesDir(), "live-cache");
    }

    @After public void teardown() {
        ApiClient.testBlockThirdParty = false;
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.DIRECT);
    }

    private interface Check { boolean ok() throws Exception; }

    private static void waitFor(String what, long ms, Check c) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return;
            Thread.sleep(300);
        }
        throw new AssertionError("超时：" + what);
    }

    private static void wipe(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) wipe(c);
        f.delete();
    }

    /** 打开直播页，等状态行满足条件，返回状态文字和频道数。 */
    private String openLive(String what, long ms, java.util.function.Predicate<String> done, AtomicInteger count) throws Exception {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(MainActivity::openLiveForTest);
            AtomicReference<String> status = new AtomicReference<>("");
            waitFor(what, ms, () -> {
                s.onActivity(a -> {
                    status.set(a.liveStatus());
                    count.set(a.liveChannelCount());
                });
                return done.test(status.get());
            });
            Log.i(TAG, what + " -> " + status.get().replace('\n', ' ') + " channels=" + count.get());
            return status.get();
        }
    }

    // ---------- 1. 播放页频道列表定位当前频道 ----------

    @Test public void playerListRevealsCurrentChannel() throws Exception {
        LiveSource source = app.api.liveSources().get(0);
        List<Channel> channels = ChannelMerge.merge(LiveChannels.load(app.api, source, Relay.Mode.DIRECT).channels);
        int index = Math.min(100, channels.size() - 30);
        assertTrue("频道太少", index > 10);
        LiveActivity.prepare(source, channels, index);
        try (ActivityScenario<LiveActivity> s = ActivityScenario.launch(LiveActivity.class)) {
            AtomicInteger first = new AtomicInteger(-1);
            waitFor("列表定位", 10_000, () -> {
                s.onActivity(a -> first.set(a.firstVisibleChannel()));
                return first.get() == index;
            });
            Log.i(TAG, "reveal index=" + index + " first=" + first.get());
        }
    }

    // ---------- 2. 齿轮按钮换源 ----------

    @Test public void liveGearOpensSourcePicker() throws Exception {
        LiveSource source = app.api.liveSources().get(0);
        List<Channel> channels = ChannelMerge.merge(LiveChannels.load(app.api, source, Relay.Mode.DIRECT).channels);
        int index = -1;
        for (int i = 0; i < channels.size() && index < 0; i++) if (channels.get(i).sources().size() > 1) index = i;
        assertTrue("没有多源频道", index >= 0);
        LiveActivity.prepare(source, channels, index);
        String name = channels.get(index).name;
        try (ActivityScenario<LiveActivity> s = ActivityScenario.launch(LiveActivity.class)) {
            s.onActivity(a -> a.findViewById(androidx.media3.ui.R.id.exo_settings).performClick());
            onView(withText(name + " · 换源")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(containsString("源 2 · "))).inRoot(isDialog()).check(matches(isDisplayed()));
            Espresso.pressBack();
        }
    }

    @Test public void fullscreenTapShowsChannelAndSourcePanels() throws Exception {
        LiveSource source = app.api.liveSources().get(0);
        List<Channel> channels = ChannelMerge.merge(LiveChannels.load(app.api, source, Relay.Mode.DIRECT).channels);
        int index = -1;
        for (int i = 0; i < channels.size() && index < 0; i++) if (channels.get(i).sources().size() > 1) index = i;
        assertTrue("没有多源频道", index >= 0);
        LiveActivity.prepare(source, channels, index);
        try (ActivityScenario<LiveActivity> s = ActivityScenario.launch(LiveActivity.class)) {
            AtomicBoolean shown = new AtomicBoolean();
            s.onActivity(a -> {
                a.findViewById(androidx.media3.ui.R.id.exo_fullscreen).performClick();
                a.showControlsForTest(true);
            });
            Thread.sleep(1500);
            s.onActivity(a -> shown.set(a.isFullscreen() && a.overlayVisible() && a.sourceOverlayVisible()));
            assertTrue("全屏点屏幕后应显示频道列表和换源面板", shown.get());
            onView(withText(org.hamcrest.Matchers.startsWith("源 2 · "))).check(matches(isDisplayed())).perform(androidx.test.espresso.action.ViewActions.click());
            Thread.sleep(500);
            AtomicInteger picked = new AtomicInteger(-1);
            s.onActivity(a -> picked.set(a.sourceIndex()));
            assertEquals("点换源面板应切到源 2", 1, picked.get());
            onView(org.hamcrest.Matchers.allOf(withText("▶ " + channels.get(index).name), isDisplayed())).check(matches(isDisplayed()));
            s.onActivity(a -> a.showControlsForTest(false));
            Thread.sleep(800);
            s.onActivity(a -> shown.set(a.overlayVisible()));
            assertTrue("控制栏隐藏后浮层应隐藏", !shown.get());
        }
    }

    @Test public void vodGearOffersSourceAndSpeed() throws Exception {
        Video video = null;
        for (String q : new String[]{"狂飙", "繁花", "庆余年"}) {
            for (Video v : app.api.search(q)) if (!v.episodes.isEmpty()) { video = v; break; }
            if (video != null) break;
        }
        assertNotNull("没有可打开的影片", video);
        try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(PlayerActivity.intent(app, video, 0, 0))) {
            Thread.sleep(1500);
            s.onActivity(a -> a.findViewById(androidx.media3.ui.R.id.exo_settings).performClick());
            onView(withText(containsString("换源（"))).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(containsString("倍速（"))).inRoot(isDialog()).check(matches(isDisplayed()));
            Espresso.pressBack();
        }
    }

    // ---------- 3. 直播列表本地缓存 ----------

    @Test public void liveListCacheSurvivesOfflineAndRepairsItself() throws Exception {
        AtomicInteger count = new AtomicInteger();
        // 无缓存：正常加载并写入缓存
        wipe(cacheDir);
        String fresh = openLive("首次加载", 90_000, st -> st.contains("上次刷新"), count);
        int online = count.get();
        assertTrue(online > 0);
        assertTrue("应显示直连获取", fresh.contains("直连获取"));
        File[] files = cacheDir.listFiles((d, n) -> n.startsWith("channels-"));
        assertTrue("只应缓存打开过的一个源", files != null && files.length == 1);
        assertTrue(new File(cacheDir, "sources.json").isFile());

        // 第三方直连全部失败：频道列表从缓存显示
        ApiClient.testBlockThirdParty = true;
        openLive("断开直连后从缓存显示", 20_000, st -> st.contains("上次刷新"), count);
        assertEquals(online, count.get());

        // 手动刷新失败：列表保持缓存
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(MainActivity::openLiveForTest);
            AtomicInteger n = new AtomicInteger();
            waitFor("缓存显示", 20_000, () -> {
                s.onActivity(a -> n.set(a.liveChannelCount()));
                return n.get() > 0;
            });
            s.onActivity(MainActivity::liveRefreshForTest);
            AtomicBoolean busy = new AtomicBoolean(true);
            waitFor("手动刷新结束", 60_000, () -> {
                s.onActivity(a -> busy.set(a.liveRefreshing()));
                return !busy.get();
            });
            s.onActivity(a -> n.set(a.liveChannelCount()));
            assertEquals("刷新失败应保留缓存", online, n.get());
        }

        // 写坏所有缓存文件：断网时不崩溃、提示失败；恢复后自动重建缓存
        for (File f : cacheDir.listFiles()) Files.write(f.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        String broken = openLive("缓存损坏且直连失败", 60_000, st -> st.startsWith("加载失败"), count);
        assertTrue(broken.startsWith("加载失败"));
        ApiClient.testBlockThirdParty = false;
        openLive("缓存损坏后恢复", 90_000, st -> st.contains("上次刷新"), count);
        assertEquals(online, count.get());
        LiveCache cache = new LiveCache(app.getFilesDir());
        assertTrue("源列表缓存应已重建", !cache.sources().isEmpty());
        assertNotNull("频道缓存应已重建", cache.channels(cache.sources().get(0).key));

        // 全中转：手动刷新后显示经服务端中转
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.RELAY);
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(MainActivity::openLiveForTest);
            AtomicInteger n = new AtomicInteger();
            waitFor("缓存显示", 20_000, () -> {
                s.onActivity(a -> n.set(a.liveChannelCount()));
                return n.get() > 0;
            });
            s.onActivity(MainActivity::liveRefreshForTest);
            AtomicReference<String> st = new AtomicReference<>("");
            waitFor("中转刷新", 90_000, () -> {
                s.onActivity(a -> st.set(a.liveStatus()));
                return st.get().contains("经服务端中转");
            });
            Log.i(TAG, "relay refresh -> " + st.get().replace('\n', ' '));
        }
    }
}
