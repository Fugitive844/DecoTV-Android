package io.github.fugitive844.decotv;

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
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.OfflinePlayerActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 离线缓存联调：同一集分别缓存为加密、MP4、原格式，完成后逐个离线播放；多个任务按并发数同时缓存。 */
@RunWith(AndroidJUnit4.class)
public class OfflineFlowTest {
    private static final String TAG = "DecoTest";
    private DecoApp app;

    @Before public void setup() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
        Relay.Mode mode = Relay.Mode.of(args.getString("mode", "direct"));
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, mode);
        for (DownloadTask t : app.downloads.all()) DownloadService.delete(app, t);
    }

    private DownloadTask task(Video v, DownloadTask.Format format) { return task(v, format, 0); }

    private DownloadTask task(Video v, DownloadTask.Format format, int episode) {
        DownloadTask t = new DownloadTask();
        t.id = UUID.randomUUID().toString();
        t.videoKey = v.key();
        t.title = v.title;
        t.sourceName = v.sourceName;
        t.episode = episode;
        t.episodeTitle = v.episodeTitles.get(episode);
        t.episodeUrl = v.episodes.get(episode);
        t.videoJson = v.toJson().toString();
        t.format = format;
        t.createdAt = System.currentTimeMillis();
        return t;
    }

    private DownloadTask await(String id, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        DownloadTask t = null;
        while (System.currentTimeMillis() < end) {
            t = app.downloads.get(id);
            if (t != null && (t.state == DownloadTask.State.DONE || t.state == DownloadTask.State.FAILED)) return t;
            Thread.sleep(2000);
        }
        return t;
    }

    private String playOffline(String id) throws Exception {
        try (ActivityScenario<OfflinePlayerActivity> s = ActivityScenario.launch(OfflinePlayerActivity.intent(app, id))) {
            AtomicReference<String> state = new AtomicReference<>("loading");
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end && state.get().equals("loading")) {
                s.onActivity(a -> state.set(a.playbackState()));
                Thread.sleep(300);
            }
            return state.get();
        }
    }

    @Test public void cacheInEveryFormatAndPlayOffline() throws Exception {
        List<Video> candidates = new ArrayList<>();
        for (Video v : app.api.search("love")) {
            // 选短的动画剧集，缓存快
            if (v.title.contains("四格") && !v.episodes.isEmpty() && MediaUrls.isHls(v.episodes.get(0))) candidates.add(v);
        }
        assertTrue("应有可缓存的短剧集", !candidates.isEmpty());
        Video picked = null;
        DownloadTask encrypted = null;
        for (Video v : candidates.subList(0, Math.min(4, candidates.size()))) {
            DownloadTask t = task(v, DownloadTask.Format.ENCRYPTED);
            app.downloads.add(t);
            DownloadService.start(app);
            DownloadTask done = await(t.id, 8 * 60_000);
            Log.i(TAG, "offline encrypted " + v.sourceName + " " + v.title + " -> " + (done == null ? "null" : done.state + " " + done.error));
            if (done != null && done.state == DownloadTask.State.DONE) {
                picked = v;
                encrypted = done;
                break;
            }
            DownloadService.delete(app, t);
        }
        assertNotNull("候选剧集都无法缓存", picked);
        assertTrue("加密文件应存在", new File(encrypted.output).length() > 100_000);
        Log.i(TAG, "encrypted bytes=" + new File(encrypted.output).length() + " container=" + encrypted.container + " relayed=" + encrypted.relayed);
        String state = playOffline(encrypted.id);
        Log.i(TAG, "play encrypted -> " + state);
        assertTrue("加密缓存应能离线播放：" + state, state.equals("ready"));

        for (DownloadTask.Format format : new DownloadTask.Format[]{DownloadTask.Format.MP4, DownloadTask.Format.ORIGINAL}) {
            DownloadTask t = task(picked, format);
            app.downloads.add(t);
            DownloadService.start(app);
            DownloadTask done = await(t.id, 10 * 60_000);
            Log.i(TAG, "offline " + format + " -> " + (done == null ? "null" : done.state + " " + done.container + " " + done.output + " " + done.error));
            assertNotNull(done);
            assertTrue(format + " 应缓存成功：" + done.error, done.state == DownloadTask.State.DONE);
            String played = playOffline(done.id);
            Log.i(TAG, "play " + format + " -> " + played);
            assertTrue(format + " 缓存应能离线播放：" + played, played.equals("ready"));
        }
    }

    private int downloading(List<DownloadTask> tasks) {
        int n = 0;
        for (DownloadTask t : tasks) {
            DownloadTask now = app.downloads.get(t.id);
            if (now != null && (now.state == DownloadTask.State.DOWNLOADING || now.state == DownloadTask.State.CONVERTING)) n++;
        }
        return n;
    }

    @Test public void parallelTasksRunTogether() throws Exception {
        Video picked = null;
        for (Video v : app.api.search("love")) {
            if (v.title.contains("四格") && v.episodes.size() >= 3 && MediaUrls.isHls(v.episodes.get(0))) {
                picked = v;
                break;
            }
        }
        Assume.assumeTrue("需要至少 3 集的短剧集", picked != null);
        app.prefs.setDownloadParallel(2);
        try {
            List<DownloadTask> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                DownloadTask t = task(picked, DownloadTask.Format.ENCRYPTED, i);
                t.createdAt += i;
                app.downloads.add(t);
                tasks.add(t);
            }
            DownloadService.start(app);
            int max = 0;
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end && max < 2) {
                max = Math.max(max, downloading(tasks));
                Thread.sleep(200);
            }
            Log.i(TAG, "parallel limit=2 observed=" + max);
            assertTrue("应有 2 个任务同时缓存，实际 " + max, max == 2);
            // 暂停其中一个正在下载的任务，另一个照常进行，排队的任务补上
            DownloadTask paused = null;
            for (DownloadTask t : tasks) {
                if (DownloadService.running(t.id)) {
                    paused = t;
                    break;
                }
            }
            assertNotNull(paused);
            DownloadService.pause(app, paused.id);
            long pauseEnd = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < pauseEnd && app.downloads.get(paused.id).state != DownloadTask.State.PAUSED) Thread.sleep(200);
            Log.i(TAG, "paused one -> " + app.downloads.get(paused.id).state + " running=" + downloading(tasks));
            assertTrue("暂停的任务应为 PAUSED", app.downloads.get(paused.id).state == DownloadTask.State.PAUSED);
            int peak = 0;
            for (DownloadTask t : tasks) {
                if (t == paused) continue;
                DownloadTask done = await(t.id, 8 * 60_000);
                peak = Math.max(peak, downloading(tasks));
                Log.i(TAG, "parallel episode " + t.episode + " -> " + (done == null ? "null" : done.state + " " + done.error));
                assertTrue("其余任务应缓存成功", done != null && done.state == DownloadTask.State.DONE);
            }
            assertTrue("并发不应超过设置值", peak <= 2);
            // 继续暂停的任务，能接着完成
            DownloadTask resume = app.downloads.get(paused.id);
            resume.state = DownloadTask.State.QUEUED;
            app.downloads.update(resume);
            DownloadService.start(app);
            DownloadTask done = await(paused.id, 8 * 60_000);
            Log.i(TAG, "resumed episode " + paused.episode + " -> " + (done == null ? "null" : done.state + " " + done.error));
            assertTrue("暂停后继续应能完成", done != null && done.state == DownloadTask.State.DONE);
        } finally {
            app.prefs.setDownloadParallel(DownloadService.DEFAULT_PARALLEL);
        }
    }
}
