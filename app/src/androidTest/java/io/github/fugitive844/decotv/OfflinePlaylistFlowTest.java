package io.github.fugitive844.decotv;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.download.CoverCache;
import io.github.fugitive844.decotv.download.DownloadService;
import io.github.fugitive844.decotv.download.DownloadTask;
import io.github.fugitive844.decotv.download.Vault;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.LiveActivity;
import io.github.fugitive844.decotv.ui.OfflinePlayerActivity;
import io.github.fugitive844.decotv.ui.PlayerActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/** 真实播放器 + 本地服务端播放记录；媒体使用生成的测试图案，避免第三方片源波动。 */
@RunWith(AndroidJUnit4.class)
public class OfflinePlaylistFlowTest {
    private DecoApp app;
    private File media;
    private MockWebServer mediaServer;
    private final List<DownloadTask> tasks = new ArrayList<>();
    private String prefix;

    @Before public void setup() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        Bundle args = InstrumentationRegistry.getArguments();
        assertTrue("需要现有本地测试服务端", args.containsKey("liveBase"));
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.DIRECT);
        prefix = "playlist-test-" + UUID.randomUUID();
        media = new File(app.getCacheDir(), prefix + ".mp4");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("offline-playlist.mp4")) {
            Files.copy(in, media.toPath());
        }
        byte[] bytes = Files.readAllBytes(media.toPath());
        mediaServer = new MockWebServer();
        mediaServer.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "video/mp4").setBody(new Buffer().write(bytes));
            }
        });
        mediaServer.start();
    }

    @After public void cleanup() throws Exception {
        // 只删除本测试创建的任务和服务端记录。
        for (DownloadTask t : tasks) DownloadService.delete(app, t);
        Thread.sleep(600);
        for (DownloadTask t : tasks) app.api.deletePlayRecord(t.videoKey);
        if (mediaServer != null) mediaServer.shutdown();
        if (media != null) Files.deleteIfExists(media.toPath());
    }

    private DownloadTask add(String name, int episode, int total, long created, DownloadTask.Format format) throws Exception {
        Video v = video(name, total);
        DownloadTask t = new DownloadTask();
        t.id = UUID.randomUUID().toString();
        t.videoKey = v.key(); t.title = v.title; t.sourceName = v.sourceName;
        t.videoJson = v.toJson().toString(); t.episode = episode;
        t.episodeTitle = v.episodeTitles.get(episode); t.createdAt = created;
        t.state = DownloadTask.State.DONE; t.format = format; t.container = "mp4";
        File directory = CoverCache.file(app, t).getParentFile();
        directory.mkdirs();
        File file = new File(directory, t.id + (format == DownloadTask.Format.ENCRYPTED ? ".dtv" : ".mp4"));
        if (format == DownloadTask.Format.ENCRYPTED) Vault.encrypt(media, file);
        else Files.copy(media.toPath(), file.toPath());
        t.output = file.getPath();
        tasks.add(t); app.downloads.add(t);
        Bitmap cover = Bitmap.createBitmap(60, 84, Bitmap.Config.ARGB_8888);
        cover.eraseColor(0xFF16785C + episode * 256);
        CoverCache.save(app, t, cover);
        JSONArray danmus = new JSONArray();
        for (int i = 0; i <= episode; i++) danmus.put(new JSONObject().put("time", i).put("text", name + i));
        File danmaku = DownloadTask.danmakuFile(app, t.id);
        danmaku.getParentFile().mkdirs();
        Files.write(danmaku.toPath(), new JSONObject().put("danmus", danmus).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return t;
    }

    private Video video(String name, int total) {
        Video v = new Video();
        v.source = "playlist-test"; v.id = prefix + name; v.title = name; v.sourceName = "本地测试";
        v.year = "2026";
        for (int i = 0; i < total; i++) {
            v.episodes.add(mediaServer.url("/sample.mp4").toString());
            v.episodeTitles.add(total == 1 ? "正片" : "第" + (i + 1) + "集");
        }
        return v;
    }

    private static <A extends Activity> void await(ActivityScenario<A> scene, Predicate<A> check) throws Exception {
        AtomicBoolean ok = new AtomicBoolean();
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            scene.onActivity(a -> ok.set(check.test(a)));
            if (ok.get()) return;
            Thread.sleep(100);
        }
        fail("等待播放器状态超时");
    }

    private static PlayerView player(View root) {
        if (root instanceof PlayerView) return (PlayerView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                PlayerView found = player(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView text(View root, String match) {
        if (root instanceof TextView && ((TextView) root).getText().toString().equals(match)) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void ready(ActivityScenario<OfflinePlayerActivity> scene, DownloadTask expected) throws Exception {
        await(scene, a -> a.currentCacheId().equals(expected.id) && a.playbackState().equals("ready"));
        scene.onActivity(a -> { a.pauseForTest(); assertEquals(expected.title, a.getTitle().toString()); });
    }

    private static void next(ActivityScenario<OfflinePlayerActivity> scene, boolean forward) {
        scene.onActivity(a -> {
            PlayerView pv = player(a.getWindow().getDecorView());
            pv.showController();
            View button = pv.findViewById(forward ? androidx.media3.ui.R.id.exo_next : androidx.media3.ui.R.id.exo_prev);
            assertTrue("首尾切换按钮应可用", button.isEnabled());
            button.performClick();
        });
    }

    private static void half(Activity a) {
        PlayerView pv = player(a.getWindow().getDecorView());
        View box = (View) pv.getParent();
        View root = (View) box.getParent();
        assertTrue("播放器应占可用高度一半，实际 " + box.getHeight() + "/" + root.getHeight(),
                root.getHeight() > 0 && Math.abs(2 * box.getHeight() - root.getHeight()) <= 2);
    }

    private void shot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir("evidence"), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test public void mixedQueueWrapsManualSelectionAndRecordsFollowMovie() throws Exception {
        long now = System.currentTimeMillis();
        DownloadTask a = add("电影甲", 0, 1, now, DownloadTask.Format.ORIGINAL);
        DownloadTask b = add("电影乙", 0, 1, now + 1, DownloadTask.Format.ENCRYPTED);
        DownloadTask c = add("电影丙", 0, 1, now + 2, DownloadTask.Format.ORIGINAL);
        DownloadTask ep2 = add("测试剧", 1, 2, now + 3, DownloadTask.Format.ENCRYPTED);
        DownloadTask ep1 = add("测试剧", 0, 2, now + 4, DownloadTask.Format.ENCRYPTED);
        try (ActivityScenario<OfflinePlayerActivity> scene = ActivityScenario.launch(OfflinePlayerActivity.intent(app, ep1.id))) {
            ready(scene, ep1);
            scene.onActivity(x -> { half(x); assertEquals(5, x.cacheCount()); });
            shot("offline-half-list");
            next(scene, false); ready(scene, a);
            next(scene, true); ready(scene, ep1);
            next(scene, true); ready(scene, ep2);
            await(scene, x -> x.danmakuInfo().contains("count=2"));
            next(scene, true); ready(scene, c);
            await(scene, x -> x.danmakuInfo().contains("count=1"));
            scene.onActivity(x -> x.seekForTest(4000));
            await(scene, x -> x.positionMs() >= 3900);
            next(scene, true); ready(scene, b);
            long end = System.currentTimeMillis() + 10_000;
            Record record = null;
            while (System.currentTimeMillis() < end && record == null) {
                for (Record r : app.api.playRecords()) if (r.key.equals(c.videoKey)) record = r;
                if (record == null) Thread.sleep(200);
            }
            assertNotNull("跨影片切换应保存到正确影片", record);
            assertEquals("电影丙", record.title); assertEquals(1, record.index); assertEquals(1, record.totalEpisodes);
            assertTrue(record.playTime >= 3);
            scene.onActivity(x -> text(x.getWindow().getDecorView(), "缓存列表").performClick());
            onView(withText("测试剧 · 第1集 · 加密（仅本 App）")).inRoot(isDialog()).perform(click());
            ready(scene, ep1);
            // 当前缓存删除后切到剩余缓存；删除其它项不改变当前播放。
            app.downloads.remove(a.id);
            await(scene, x -> x.cacheCount() == 4 && x.currentCacheId().equals(ep1.id));
            app.downloads.remove(ep1.id);
            ready(scene, ep2);
            scene.onActivity(x -> {
                player(x.getWindow().getDecorView()).showController();
                x.findViewById(androidx.media3.ui.R.id.exo_fullscreen).performClick();
            });
            await(scene, x -> x.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            scene.onActivity(x -> x.findViewById(androidx.media3.ui.R.id.exo_settings).performClick());
            onView(withText("缓存列表")).inRoot(isDialog()).perform(click());
            shot("offline-fullscreen-picker");
            onView(withText("电影乙 · 正片 · 加密（仅本 App）")).inRoot(isDialog()).perform(click());
            ready(scene, b);
            scene.onActivity(x -> x.getOnBackPressedDispatcher().onBackPressed());
            await(scene, x -> x.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT);
            scene.onActivity(OfflinePlaylistFlowTest::half);
        }
    }

    @Test public void endingLoopsAndNewCachesJoinWithoutChangingCurrent() throws Exception {
        long now = System.currentTimeMillis();
        DownloadTask first = add("首部电影", 0, 1, now + 1, DownloadTask.Format.ORIGINAL);
        DownloadTask last = add("末部电影", 0, 1, now, DownloadTask.Format.ENCRYPTED);
        try (ActivityScenario<OfflinePlayerActivity> scene = ActivityScenario.launch(OfflinePlayerActivity.intent(app, last.id))) {
            ready(scene, last);
            scene.onActivity(x -> {
                x.seekForTest(x.durationMs() - 100);
                player(x.getWindow().getDecorView()).getPlayer().play();
            });
            ready(scene, first);
            DownloadTask added = add("新增电影", 0, 1, now + 2, DownloadTask.Format.ENCRYPTED);
            await(scene, x -> x.cacheCount() == 3 && x.currentCacheId().equals(first.id));
            next(scene, false); ready(scene, added);
            app.downloads.remove(first.id); app.downloads.remove(last.id);
            await(scene, x -> x.cacheCount() == 1);
            next(scene, true); ready(scene, added);
            next(scene, false); ready(scene, added);
        }
    }

    @Test public void vodAndLiveAlsoUseHalfHeight() throws Exception {
        Video v = video("布局验证", 1);
        try (ActivityScenario<PlayerActivity> scene = ActivityScenario.launch(PlayerActivity.intent(app, v, 0, 0))) {
            await(scene, x -> x.playbackState().startsWith("ready:"));
            scene.onActivity(OfflinePlaylistFlowTest::half);
            scene.onActivity(x -> {
                player(x.getWindow().getDecorView()).getPlayer().pause();
                player(x.getWindow().getDecorView()).showController();
            });
            onView(withContentDescription("播放倍速")).perform(click());
            onView(withText("1.5x")).inRoot(isDialog()).perform(click());
            scene.onActivity(x -> assertEquals(1.5f, player(x.getWindow().getDecorView()).getPlayer().getPlaybackParameters().speed, 0.001f));
            shot("vod-half");
        }
        Channel channel = new Channel(); channel.name = "本地测试频道"; channel.url = v.episodes.get(0);
        LiveSource source = new LiveSource(); source.key = "test"; source.name = "本地测试";
        LiveActivity.prepare(source, Arrays.asList(channel), 0);
        try (ActivityScenario<LiveActivity> scene = ActivityScenario.launch(LiveActivity.class)) {
            await(scene, x -> x.playbackState().startsWith("ready:"));
            scene.onActivity(OfflinePlaylistFlowTest::half);
            shot("live-half");
        }
        app.api.deletePlayRecord(v.key());
    }

    @Test public void downloadServiceSavesEncryptedVideoAndCoverTogether() throws Exception {
        Bitmap cover = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
        cover.eraseColor(0xFF226699);
        java.io.ByteArrayOutputStream image = new java.io.ByteArrayOutputStream();
        cover.compress(Bitmap.CompressFormat.PNG, 100, image);
        byte[] mediaBytes = Files.readAllBytes(media.toPath());
        mediaServer.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                boolean poster = request.getPath().startsWith("/cover");
                return new MockResponse().setHeader("Content-Type", poster ? "image/png" : "video/mp4")
                        .setBody(new Buffer().write(poster ? image.toByteArray() : mediaBytes));
            }
        });
        Video v = video("封面缓存联调", 1);
        DownloadTask task = new DownloadTask();
        task.id = UUID.randomUUID().toString(); task.videoKey = v.key(); task.title = v.title;
        task.videoJson = v.toJson().toString(); task.episodeTitle = "正片";
        task.episodeUrl = v.episodes.get(0); task.cover = mediaServer.url("/cover.png").toString();
        task.format = DownloadTask.Format.ENCRYPTED; task.createdAt = System.currentTimeMillis();
        tasks.add(task); app.downloads.add(task);
        DownloadService.start(app);
        long end = System.currentTimeMillis() + 120_000;
        DownloadTask done = null;
        while (System.currentTimeMillis() < end) {
            done = app.downloads.get(task.id);
            if (done.state == DownloadTask.State.DONE || done.state == DownloadTask.State.FAILED) break;
            Thread.sleep(200);
        }
        assertEquals("下载服务应完成缓存：" + done.error, DownloadTask.State.DONE, done.state);
        assertTrue(new File(done.output).isFile());
        assertTrue(CoverCache.file(app, done).isFile());
        assertEquals(cover.getPixel(0, 0), CoverCache.read(app, done).getPixel(0, 0));
        assertNull(android.graphics.BitmapFactory.decodeFile(CoverCache.file(app, done).getPath()));
        mediaServer.shutdown();
        mediaServer = null;
        try (ActivityScenario<OfflinePlayerActivity> scene = ActivityScenario.launch(OfflinePlayerActivity.intent(app, done.id))) {
            ready(scene, done);
            scene.onActivity(OfflinePlaylistFlowTest::half);
        }
    }

    private static void touch(ActivityScenario<OfflinePlayerActivity> scene, long down, int action, float x) {
        scene.onActivity(a -> {
            View box = (View) player(a.getWindow().getDecorView()).getParent();
            android.view.MotionEvent event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
                    action, box.getWidth() * x, box.getHeight() * 0.35f, 0);
            box.dispatchTouchEvent(event);
            event.recycle();
        });
    }

    private static void assertSpeed(ActivityScenario<OfflinePlayerActivity> scene, float speed) {
        scene.onActivity(a -> assertEquals(speed,
                player(a.getWindow().getDecorView()).getPlayer().getPlaybackParameters().speed, 0.001f));
    }

    @Test public void horizontalSwipesCancelHoldSpeedAndAlwaysRestoreOriginalSpeed() throws Exception {
        DownloadTask task = add("手势回归", 0, 1, System.currentTimeMillis(), DownloadTask.Format.ENCRYPTED);
        try (ActivityScenario<OfflinePlayerActivity> scene = ActivityScenario.launch(OfflinePlayerActivity.intent(app, task.id))) {
            ready(scene, task);
            scene.onActivity(a -> {
                androidx.media3.common.Player p = player(a.getWindow().getDecorView()).getPlayer();
                p.setPlaybackSpeed(1.25f);
                p.seekTo(15000);
                p.play();
            });
            await(scene, a -> player(a.getWindow().getDecorView()).getPlayer().isPlaying());
            long delay = android.view.ViewConfiguration.getLongPressTimeout() + 200;
            long down = android.os.SystemClock.uptimeMillis();
            touch(scene, down, android.view.MotionEvent.ACTION_DOWN, 0.85f);
            touch(scene, down, android.view.MotionEvent.ACTION_MOVE, 0.65f);
            Thread.sleep(delay);
            assertSpeed(scene, 1.25f);
            touch(scene, down, android.view.MotionEvent.ACTION_UP, 0.65f);
            assertSpeed(scene, 1.25f);
            await(scene, a -> player(a.getWindow().getDecorView()).getPlayer().isPlaying());

            // 先静止长按，再转成左右滑动，必须立即退出倍速并允许快进。
            down = android.os.SystemClock.uptimeMillis();
            touch(scene, down, android.view.MotionEvent.ACTION_DOWN, 0.7f);
            Thread.sleep(delay);
            assertSpeed(scene, 2f);
            touch(scene, down, android.view.MotionEvent.ACTION_MOVE, 0.85f);
            assertSpeed(scene, 1.25f);
            touch(scene, down, android.view.MotionEvent.ACTION_UP, 0.85f);
            assertSpeed(scene, 1.25f);
            await(scene, a -> player(a.getWindow().getDecorView()).getPlayer().isPlaying());

            // 取消触摸恢复原速，不残留长按识别。
            down = android.os.SystemClock.uptimeMillis();
            touch(scene, down, android.view.MotionEvent.ACTION_DOWN, 0.7f);
            Thread.sleep(delay);
            assertSpeed(scene, 2f);
            touch(scene, down, android.view.MotionEvent.ACTION_CANCEL, 0.7f);
            assertSpeed(scene, 1.25f);

            // 时间轴等子控件接管拖动后，不能再触发播放器的长按计时器。
            down = android.os.SystemClock.uptimeMillis();
            touch(scene, down, android.view.MotionEvent.ACTION_DOWN, 0.7f);
            scene.onActivity(a -> a.findViewById(androidx.media3.ui.R.id.exo_progress)
                    .getParent().requestDisallowInterceptTouchEvent(true));
            Thread.sleep(delay);
            assertSpeed(scene, 1.25f);
            touch(scene, down, android.view.MotionEvent.ACTION_UP, 0.7f);
            Thread.sleep(delay);
            assertSpeed(scene, 1.25f);

            // 普通长按后松手也必须恢复，不能把 2x 记成用户选择的倍速。
            scene.onActivity(a -> {
                player(a.getWindow().getDecorView()).getPlayer().seekTo(5000);
                player(a.getWindow().getDecorView()).getPlayer().play();
            });
            await(scene, a -> player(a.getWindow().getDecorView()).getPlayer().isPlaying());
            down = android.os.SystemClock.uptimeMillis();
            touch(scene, down, android.view.MotionEvent.ACTION_DOWN, 0.7f);
            Thread.sleep(delay);
            assertSpeed(scene, 2f);
            touch(scene, down, android.view.MotionEvent.ACTION_UP, 0.7f);
            assertSpeed(scene, 1.25f);
            scene.onActivity(a -> {
                a.pauseForTest();
                player(a.getWindow().getDecorView()).showController();
            });
            onView(withContentDescription("播放倍速")).perform(click());
            onView(withText("1.5x")).inRoot(isDialog()).perform(click());
            assertSpeed(scene, 1.5f);
            shot("offline-speed-shortcut");
            scene.onActivity(a -> a.findViewById(androidx.media3.ui.R.id.exo_fullscreen).performClick());
            await(scene, a -> a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            onView(withContentDescription("播放倍速")).perform(click());
            onView(withText("1x")).inRoot(isDialog()).perform(click());
            assertSpeed(scene, 1f);
            shot("offline-speed-fullscreen");
        }
    }
}
