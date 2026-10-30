package io.github.fugitive844.decotv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.download.DownloadService;
import io.github.fugitive844.decotv.download.DownloadTask;
import io.github.fugitive844.decotv.download.Stream;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.OfflinePlayerActivity;
import io.github.fugitive844.decotv.ui.PlayerActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 联调：在线播放加载弹幕；m3u8 缓存未完成时边存边播、往后拖动、缓存完成后切到最终文件。 */
@RunWith(AndroidJUnit4.class)
public class StreamDanmakuFlowTest {
    private static final String TAG = "DecoTest";
    private DecoApp app;

    @Before public void setup() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.of(args.getString("mode", "direct")));
        for (DownloadTask t : app.downloads.all()) DownloadService.delete(app, t);
    }

    private static Video firstHls(Iterable<Video> results, String titlePart) {
        for (Video v : results) {
            if (v.title.contains(titlePart) && !v.episodes.isEmpty() && MediaUrls.isHls(v.episodes.get(0))) return v;
        }
        return null;
    }

    private DownloadTask task(Video v) {
        DownloadTask t = new DownloadTask();
        t.id = UUID.randomUUID().toString();
        t.videoKey = v.key();
        t.title = v.title;
        t.sourceName = v.sourceName;
        t.episodeTitle = v.episodeTitles.get(0);
        t.episodeUrl = v.episodes.get(0);
        t.videoJson = v.toJson().toString();
        t.format = DownloadTask.Format.ENCRYPTED;
        t.createdAt = System.currentTimeMillis();
        return t;
    }

    private interface Check { boolean ok() throws Exception; }

    private static void waitFor(String what, long ms, Check c) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return;
            Thread.sleep(500);
        }
        throw new AssertionError("超时：" + what);
    }

    @Test public void onlineDanmakuLoads() throws Exception {
        Video v = null;
        for (Video x : app.api.search("庆余年")) {
            if (x.title.equals("庆余年") && x.episodes.size() > 1) {
                v = x;
                break;
            }
        }
        assertNotNull("应有庆余年", v);
        try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(PlayerActivity.intent(app, v, 0, 0))) {
            AtomicReference<String> info = new AtomicReference<>("");
            waitFor("弹幕加载", 60_000, () -> {
                s.onActivity(a -> info.set(a.danmakuInfo()));
                return info.get().startsWith("已加载") || info.get().startsWith("加载失败") || info.get().startsWith("没有");
            });
            Log.i(TAG, "danmaku online " + v.sourceName + " -> " + info.get());
            assertTrue("应加载到弹幕：" + info.get(), info.get().startsWith("已加载"));
        }
    }

    @Test public void streamWhileCachingThenSwitchToFile() throws Exception {
        Video v = firstHls(app.api.search("love"), "四格");
        assertNotNull("应有可缓存的短剧集", v);
        DownloadTask t = task(v);
        app.downloads.add(t);
        DownloadService.start(app);
        waitFor("拿到分片列表", 120_000, () -> Stream.available(app, app.downloads.get(t.id)));
        try (ActivityScenario<OfflinePlayerActivity> s = ActivityScenario.launch(OfflinePlayerActivity.intent(app, t.id))) {
            AtomicReference<String> state = new AtomicReference<>("");
            AtomicBoolean streaming = new AtomicBoolean();
            AtomicLong pos = new AtomicLong();
            waitFor("边存边播起播", 60_000, () -> {
                s.onActivity(a -> {
                    state.set(a.playbackState());
                    streaming.set(a.isStreaming());
                });
                return state.get().equals("ready") || state.get().startsWith("failed");
            });
            DownloadTask now = app.downloads.get(t.id);
            Log.i(TAG, "stream start state=" + state.get() + " streaming=" + streaming.get() + " task=" + now.state + " " + Math.round(now.progress() * 100) + "%");
            assertTrue("应能边存边播：" + state.get(), state.get().equals("ready"));
            // 往后拖到 70% 处，那里的分片多半还没缓存，由播放器自己取
            AtomicLong duration = new AtomicLong();
            s.onActivity(a -> duration.set(a.durationMs()));
            long target = duration.get() * 7 / 10;
            s.onActivity(a -> a.seekForTest(target));
            long seekAt = System.currentTimeMillis();
            assertTrue("时长应已知", duration.get() > 30_000);
            waitFor("拖动后继续播放", 30_000, () -> {
                s.onActivity(a -> pos.set(a.positionMs()));
                return pos.get() > target + 1000;
            });
            Log.i(TAG, "stream duration=" + duration.get() + " resumed after seek in " + (System.currentTimeMillis() - seekAt) + "ms pos=" + pos.get());
            // 暂停，等缓存完成时验证在原进度切到最终文件
            s.onActivity(a -> a.pauseForTest());
            long paused = target;
            s.onActivity(a -> pos.set(a.positionMs()));
            DownloadTask done = null;
            long end = System.currentTimeMillis() + 8 * 60_000;
            while (System.currentTimeMillis() < end) {
                done = app.downloads.get(t.id);
                if (done.state == DownloadTask.State.DONE || done.state == DownloadTask.State.FAILED) break;
                Thread.sleep(2000);
            }
            assertTrue("缓存应完成：" + done.error, done.state == DownloadTask.State.DONE);
            waitFor("切到最终文件", 20_000, () -> {
                s.onActivity(a -> {
                    streaming.set(a.isStreaming());
                    state.set(a.playbackState());
                    pos.set(a.positionMs());
                });
                return !streaming.get() && state.get().equals("ready");
            });
            Log.i(TAG, "switched to file pos=" + pos.get() + " danmakuFile=" + DownloadTask.danmakuFile(app, t.id).exists());
            assertFalse(streaming.get());
            assertTrue("切换后进度应连续：" + pos.get(), pos.get() > paused);
        }
        Thread.sleep(1000);
        assertFalse("播放器退出后应清理分片", Stream.workDir(app, t.id).exists());
    }
}
