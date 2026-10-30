package io.github.fugitive844.decotv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.view.View;
import android.os.Bundle;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.live.LiveChannels;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.LiveActivity;
import io.github.fugitive844.decotv.ui.PlayerActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** 点播默认全屏、可退出；直播可进入全屏，返回键先退出全屏。 */
@RunWith(AndroidJUnit4.class)
public class FullscreenTest {
    private DecoApp app;

    @Before public void setup() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.DIRECT);
    }

    private void shot(String name) throws Exception {
        Thread.sleep(1500);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (OutputStream out = new FileOutputStream(new File(app.getExternalFilesDir("evidence"), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static <A extends android.app.Activity> boolean waitFor(ActivityScenario<A> s, Predicate<A> check) throws Exception {
        AtomicBoolean ok = new AtomicBoolean();
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            s.onActivity(a -> ok.set(check.test(a)));
            if (ok.get()) return true;
            Thread.sleep(300);
        }
        return false;
    }

    /** 播放中画面持续重绘，Espresso 等不到空闲，这里直接在主线程点控制栏的全屏按钮。 */
    private static <A extends android.app.Activity> void tapFullscreenButton(ActivityScenario<A> s) throws Exception {
        s.onActivity(a -> {
            androidx.media3.ui.PlayerView pv = findPlayerView(a.getWindow().getDecorView());
            pv.showController();
        });
        Thread.sleep(500);
        s.onActivity(a -> {
            View button = a.findViewById(androidx.media3.ui.R.id.exo_fullscreen);
            assertTrue("应有全屏按钮", button != null && button.isShown());
            button.performClick();
        });
    }

    private static <A extends androidx.activity.ComponentActivity> void back(ActivityScenario<A> s) {
        s.onActivity(a -> a.getOnBackPressedDispatcher().onBackPressed());
    }

    private static androidx.media3.ui.PlayerView findPlayerView(View v) {
        if (v instanceof androidx.media3.ui.PlayerView) return (androidx.media3.ui.PlayerView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                androidx.media3.ui.PlayerView found = findPlayerView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void vodStartsInlineAndUserEntersFullscreen() throws Exception {
        Video video = null;
        for (Video v : app.api.search("庆余年")) {
            if (v.episodes.size() > 1 && MediaUrls.isHls(v.episodes.get(0))) { video = v; break; }
        }
        Assume.assumeTrue(video != null);
        try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(PlayerActivity.intent(app, video, 0, 0))) {
            assertTrue("点播应能起播", waitFor(s, a -> a.playbackState().startsWith("ready:")));
            s.onActivity(a -> assertFalse("点播默认是竖屏小窗", a.isFullscreen()));
            tapFullscreenButton(s);
            assertTrue("点全屏按钮应进入全屏", waitFor(s, PlayerActivity::isFullscreen));
            shot("9-vod-fullscreen");
            back(s);
            assertTrue("返回键应先退出全屏", waitFor(s, a -> !a.isFullscreen() && !a.isFinishing()));
            shot("10-vod-inline");
        }
    }

    @Test public void liveEntersFullscreenAndBackExits() throws Exception {
        LiveSource source = app.api.liveSources().get(0);
        LiveChannels loaded = LiveChannels.load(app.api, source, Relay.Mode.DIRECT);
        LiveActivity.prepare(source, loaded.channels, 1);
        try (ActivityScenario<LiveActivity> s = ActivityScenario.launch(LiveActivity.class)) {
            assertTrue("直播应能起播", waitFor(s, a -> a.playbackState().startsWith("ready:")));
            s.onActivity(a -> assertFalse("竖屏打开时不是全屏", a.isFullscreen()));
            tapFullscreenButton(s);
            assertTrue("点全屏按钮应进入全屏", waitFor(s, LiveActivity::isFullscreen));
            shot("11-live-fullscreen");
            back(s);
            assertTrue("返回键应先退出全屏", waitFor(s, a -> !a.isFullscreen() && !a.isFinishing()));
            shot("12-live-portrait");
        }
    }
}
