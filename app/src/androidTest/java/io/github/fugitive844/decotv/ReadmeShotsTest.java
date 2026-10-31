package io.github.fugitive844.decotv;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;

import io.github.fugitive844.decotv.live.ChannelMerge;
import io.github.fugitive844.decotv.live.LiveChannels;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.LiveActivity;
import io.github.fugitive844.decotv.ui.MainActivity;
import io.github.fugitive844.decotv.ui.PlayerActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** 生成 README 的运行截图，保存在 App 外部目录的 readme-shots 下。 */
@RunWith(AndroidJUnit4.class)
public class ReadmeShotsTest {
    private DecoApp app;

    @Before public void setup() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
        // 部分图片站直连不稳定，截图时封面走中转，避免空白
        app.prefs.setMode(Relay.Item.IMAGE, Relay.Mode.RELAY);
    }

    private void shot(String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (OutputStream out = new FileOutputStream(new File(app.getExternalFilesDir("readme-shots"), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void theme(String mode) {
        app.prefs.setThemeMode(mode);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(app.prefs::applyThemeMode);
    }

    private static void tab(String name) {
        onView(allOf(withText(name), isDescendantOfA(isAssignableFrom(com.google.android.material.bottomnavigation.BottomNavigationView.class)), isDisplayed()))
                .perform(click());
    }

    @Test public void mainScreens() throws Exception {
        theme("dark");
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(15000);
            shot("home");
            tab("搜索");
            onView(withHint("搜索影视")).perform(replaceText("繁花"), closeSoftKeyboard());
            onView(allOf(withText("搜索"), isAssignableFrom(MaterialButton.class))).perform(click());
            Thread.sleep(25000);
            shot("search");
            tab("片库");
            Thread.sleep(12000);
            shot("library");
            tab("直播");
            Thread.sleep(20000);
            onView(withHint("筛选频道或分组")).perform(replaceText("CCTV"), closeSoftKeyboard());
            Thread.sleep(3000);
            shot("live");
        }
        theme("light");
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(15000);
            shot("home-light");
        }
        theme("dark");
    }

    @Test public void playing() throws Exception {
        theme("dark");
        List<Video> results = app.api.search("繁花");
        Video video = results.get(0);
        for (Video v : results) {
            // 部分片源的画面里嵌有广告，截图固定用没有广告的源
            if (v.title.equals("繁花") && v.sourceName.contains("魔都") && v.episodes.size() > 1 && MediaUrls.isHls(v.episodes.get(0))) {
                video = v;
                break;
            }
        }
        Intent intent = PlayerActivity.intent(app, video, 0, 0).putExtra("search_title", "繁花");
        try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(intent)) {
            AtomicReference<String> state = new AtomicReference<>("loading");
            long end = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < end && !state.get().startsWith("ready")) {
                s.onActivity(a -> state.set(a.playbackState()));
                Thread.sleep(500);
            }
            // 片源画面里常嵌有广告，只在片头的备案画面截图
            Thread.sleep(3000);
            shot("player");
        }
    }

    /** 电视直播：优先央视频道，换到第一个能起播的。 */
    @Test public void livePlaying() throws Exception {
        theme("dark");
        for (LiveSource source : app.api.liveSources()) {
            List<Channel> channels;
            try {
                channels = ChannelMerge.merge(LiveChannels.load(app.api, source, Relay.Mode.DIRECT).channels);
            } catch (Exception e) {
                continue;
            }
            int tried = 0;
            for (int i = 0; i < channels.size() && tried < 8; i++) {
                if (!channels.get(i).name.toUpperCase(Locale.ROOT).contains("CCTV")) continue;
                tried++;
                LiveActivity.prepare(source, channels, i);
                try (ActivityScenario<LiveActivity> s = ActivityScenario.launch(LiveActivity.class)) {
                    AtomicReference<String> state = new AtomicReference<>("loading");
                    long end = System.currentTimeMillis() + 30000;
                    while (System.currentTimeMillis() < end && state.get().equals("loading")) {
                        s.onActivity(a -> state.set(a.playbackState()));
                        Thread.sleep(500);
                    }
                    if (!state.get().startsWith("ready")) continue;
                    Thread.sleep(8000);
                    shot("live-play");
                    return;
                }
            }
        }
        assertTrue("没有能起播的直播频道", false);
    }
}
