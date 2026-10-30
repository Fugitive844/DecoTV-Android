package io.github.fugitive844.decotv;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.PlayerActivity;
import io.github.fugitive844.decotv.ui.MainActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 各主要页面截图留证，并确认默认直连下搜索结果的封面能加载出来。 */
@RunWith(AndroidJUnit4.class)
public class ScreensTest {
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
        Thread.sleep(800);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File dir = app.getExternalFilesDir("evidence");
        try (OutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** 只统计列表（RecyclerView）里已显示出来的图片，排除导航栏图标。 */
    private static int loadedImages(View view, boolean inList) {
        if (view instanceof ImageView) return inList && ((ImageView) view).getDrawable() != null && view.isShown() ? 1 : 0;
        int n = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            boolean list = inList || view instanceof androidx.recyclerview.widget.RecyclerView;
            for (int i = 0; i < group.getChildCount(); i++) n += loadedImages(group.getChildAt(i), list);
        }
        return n;
    }

    private static int loadedImages(View view) { return loadedImages(view, false); }

    private static <A extends Activity> int waitImages(ActivityScenario<A> s, int atLeast) throws Exception {
        AtomicInteger count = new AtomicInteger();
        long end = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < end) {
            s.onActivity(a -> count.set(loadedImages(a.getWindow().getDecorView())));
            if (count.get() >= atLeast) break;
            Thread.sleep(500);
        }
        return count.get();
    }

    /** 点底部导航的某一项。 */
    private static void tab(String name) {
        onView(allOf(withText(name), isDescendantOfA(isAssignableFrom(com.google.android.material.bottomnavigation.BottomNavigationView.class)), isDisplayed()))
                .perform(click());
    }

    @Test public void captureScreens() throws Exception {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            int home = waitImages(s, 6);
            shot("0-home");
            assertTrue("首页海报应能加载，实际 " + home, home >= 6);
            tab("搜索");
            onView(withHint("搜索影视")).perform(replaceText("繁花"), closeSoftKeyboard());
            onView(allOf(withText("搜索"), isAssignableFrom(MaterialButton.class))).perform(click());
            int covers = waitImages(s, 3);
            shot("1-search");
            assertTrue("默认直连应加载出封面，实际 " + covers, covers >= 3);
            tab("片库");
            int library = waitImages(s, 6);
            shot("2-library");
            assertTrue("片库海报应能加载，实际 " + library, library >= 3);
            tab("我的");
            shot("3-mine");
            onView(allOf(withText("播放记录"), isDisplayed())).perform(click());
            int recordCovers = waitImages(s, 2);
            shot("3-records");
            assertTrue("播放记录封面应能加载，实际 " + recordCovers, recordCovers >= 1);
            pressBack();
            onView(allOf(withText("收藏"), isDisplayed())).perform(click());
            shot("3-favorites");
            pressBack();
            tab("直播");
            int logos = waitImages(s, 3);
            shot("4-live");
            assertTrue("直播台标应能加载，实际 " + logos, logos >= 1);
            tab("我的");
            onView(allOf(withText("设置"), isDisplayed())).perform(click());
            shot("5-settings");
        }
        List<Video> results = app.api.search("繁花");
        Video video = results.get(0);
        for (Video v : results) if (v.episodes.size() > 1 && io.github.fugitive844.decotv.net.MediaUrls.isHls(v.episodes.get(0))) { video = v; break; }
        Intent intent = PlayerActivity.intent(app, video, 0, 0).putExtra("search_title", "繁花");
        try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(intent)) {
            long end = System.currentTimeMillis() + 30000;
            java.util.concurrent.atomic.AtomicReference<String> state = new java.util.concurrent.atomic.AtomicReference<>("loading");
            while (System.currentTimeMillis() < end && !state.get().startsWith("ready")) {
                s.onActivity(a -> state.set(a.playbackState()));
                Thread.sleep(500);
            }
            shot("6-play-episodes");
            onView(allOf(withText("换源"), isDisplayed())).perform(click());
            Thread.sleep(4000);
            shot("7-play-sources");
            s.onActivity(a -> assertTrue("播放页默认是竖屏小窗", !a.isFullscreen()));
        }
    }
}
