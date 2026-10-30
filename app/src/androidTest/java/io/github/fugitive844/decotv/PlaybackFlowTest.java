package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.live.LiveChannels;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.LiveActivity;
import io.github.fugitive844.decotv.ui.PlayerActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对本机测试服务端的实际播放联调。参数：
 * -e liveBase/liveUser/livePassword 账号；-e mode direct|relay|smart 全部项目的中转策略；
 * -e expect direct|relay 期望实际走的路径（智能模式配合屏蔽第三方的系统代理时应为 relay）。
 */
@RunWith(AndroidJUnit4.class)
public class PlaybackFlowTest {
    private static final String TAG = "DecoTest";
    private DecoApp app;
    private String expect;

    @Before public void setup() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        String cookie = app.api.login(base, args.getString("liveUser"), args.getString("livePassword"));
        app.prefs.saveLogin(base, args.getString("liveUser"), cookie);
        Relay.Mode mode = Relay.Mode.of(args.getString("mode", "direct"));
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, mode);
        expect = args.getString("expect", mode == Relay.Mode.RELAY ? "relay" : "direct");
        io.github.fugitive844.decotv.net.ApiClient.testBlockThirdParty = "1".equals(args.getString("blockThirdParty"));
    }

    @org.junit.After public void teardown() {
        io.github.fugitive844.decotv.net.ApiClient.testBlockThirdParty = false;
    }

    private static String waitState(ActivityScenario<?> scenario, java.util.function.Function<Object, String> read, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        AtomicReference<String> state = new AtomicReference<>("loading");
        while (System.currentTimeMillis() < end) {
            scenario.onActivity(a -> state.set(read.apply(a)));
            if (!state.get().equals("loading")) return state.get();
            Thread.sleep(500);
        }
        return "timeout";
    }

    @Test public void vodPlaysSavesRecordAndResumes() throws Exception {
        List<Video> candidates = new ArrayList<>();
        for (String q : new String[]{"庆余年", "狂飙", "繁花"}) {
            for (Video v : app.api.search(q)) {
                if (!v.episodes.isEmpty() && MediaUrls.isHls(v.episodes.get(0))) candidates.add(v);
            }
            if (candidates.size() >= 8) break;
        }
        assertTrue("应有可测试的 m3u8 影片", !candidates.isEmpty());
        Video played = null;
        for (Video v : candidates.subList(0, Math.min(8, candidates.size()))) {
            app.api.deletePlayRecord(v.key());
            try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(PlayerActivity.intent(app, v, 0, 0))) {
                String state = waitState(s, a -> ((PlayerActivity) a).playbackState(), 45000);
                Log.i(TAG, "vod " + v.sourceName + " " + v.title + " -> " + state);
                if (!state.startsWith("ready:")) continue;
                assertEquals("点播实际路径", expect, state.split(":")[1]);
                if (expect.equals("direct")) assertTrue("直连不应经过服务端", !state.endsWith(":10.0.2.2"));
                Thread.sleep(13000);
                s.onActivity(a -> Log.i(TAG, "position " + a.positionInfo()));
                played = v;
            }
            break;
        }
        assertNotNull("候选影片均无法播放", played);
        Record saved = find(app.api.playRecords(), played.key());
        assertNotNull("应写入播放记录", saved);
        assertTrue("播放进度应大于 0", saved.playTime > 0);
        assertEquals(1, saved.index);
        Log.i(TAG, "record " + saved.key + " play_time=" + saved.playTime + " total=" + saved.totalTime);

        long resumeAt = Math.max(60, saved.totalTime > 200 ? 120 : saved.playTime + 5);
        try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(PlayerActivity.intent(app, played, 0, resumeAt))) {
            String state = waitState(s, a -> ((PlayerActivity) a).playbackState(), 45000);
            assertTrue("续播应能起播：" + state, state.startsWith("ready:"));
            Thread.sleep(13000);
        }
        Record resumed = find(app.api.playRecords(), played.key());
        assertNotNull(resumed);
        Log.i(TAG, "resume from " + resumeAt + " -> play_time=" + resumed.playTime);
        assertTrue("续播进度应从指定位置继续", resumed.playTime >= resumeAt);
    }

    @Test public void liveListAndStreamFollowPolicy() throws Exception {
        Relay.Mode mode = app.prefs.mode(Relay.Item.LIVE_LIST);
        List<LiveSource> sources = app.api.liveSources();
        assertTrue(!sources.isEmpty());
        int triedSources = 0;
        for (LiveSource source : sources) {
            if (triedSources++ >= 12) break;
            LiveChannels loaded;
            try {
                loaded = LiveChannels.load(app.api, source, mode);
            } catch (Exception e) {
                Log.i(TAG, "live list " + source.key + " failed: " + e.getMessage());
                continue;
            }
            Log.i(TAG, "live list " + source.key + " channels=" + loaded.channels.size() + " relayed=" + loaded.relayed);
            assertEquals("频道列表实际路径", expect.equals("relay"), loaded.relayed);
            List<Channel> channels = loaded.channels;
            for (int i = 0; i < Math.min(6, channels.size()); i++) {
                LiveActivity.prepare(source, channels, i);
                try (ActivityScenario<LiveActivity> s = ActivityScenario.launch(LiveActivity.class)) {
                    String state = waitState(s, a -> ((LiveActivity) a).playbackState(), 35000);
                    Log.i(TAG, "live " + source.key + " " + channels.get(i).name + " -> " + state);
                    if (!state.startsWith("ready:")) continue;
                    assertEquals("直播流实际路径", expect, state.split(":")[1]);
                    Thread.sleep(5000);
                    return;
                }
            }
        }
        throw new AssertionError("前 12 个直播源中没有可播放的频道");
    }

    private static Record find(List<Record> list, String key) {
        for (Record r : list) if (r.key.equals(key)) return r;
        return null;
    }
}
