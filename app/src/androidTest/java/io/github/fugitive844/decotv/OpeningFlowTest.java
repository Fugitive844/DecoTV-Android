package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.MainActivity;
import io.github.fugitive844.decotv.ui.PlayerActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 联调：点开影片时的等待框——连点只打开一次、可取消且取消后不再进入、搜索页同样防连点；设置页有版权信息。 */
@RunWith(AndroidJUnit4.class)
public class OpeningFlowTest {
    private static final String TAG = "DecoTest";
    private Bundle args;

    @Before public void setup() throws Exception {
        args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        DecoApp app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
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

    @Test public void libraryTapTwiceOpensOnceAndCancelStops() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<Integer> n = new AtomicReference<>(0);
            s.onActivity(a -> a.openLibraryForTest(0));
            waitFor("片库电影", 60_000, () -> {
                s.onActivity(a -> n.set(a.libraryCount()));
                return n.get() > 0;
            });

            // 1. 取消：点开后立刻按返回键，等待框消失，之后不应进入播放页
            Instrumentation.ActivityMonitor monitor = inst.addMonitor(PlayerActivity.class.getName(), null, false);
            AtomicBoolean busy = new AtomicBoolean();
            s.onActivity(a -> clickItem(a, true, 0));
            s.onActivity(a -> busy.set(a.openingBusy()));
            assertTrue("点击后应显示等待框", busy.get());
            Thread.sleep(300);
            shot("opening-dialog");
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            inst.waitForIdleSync();
            s.onActivity(a -> busy.set(a.openingBusy()));
            assertTrue("返回键应取消等待框", !busy.get());
            Activity late = inst.waitForMonitorWithTimeout(monitor, 15_000);
            assertEquals("取消后不应再进入播放页", null, late);
            Log.i(TAG, "opening: cancel ok");

            // 2. 连点三次：只出现一个等待框，只打开一个播放页。
            // 本地测试源不一定有同名资源，找不到时会转到搜索页，那就回片库换下一部再试。
            Activity player = null;
            for (int i = 1; i <= 7 && player == null; i++) {
                int index = i;
                s.onActivity(a -> a.openLibraryForTest(0));
                inst.waitForIdleSync();
                Instrumentation.ActivityMonitor m = inst.addMonitor(PlayerActivity.class.getName(), null, false);
                s.onActivity(a -> {
                    clickItem(a, true, index);
                    clickItem(a, true, index);
                    clickItem(a, true, index + 1);
                });
                waitFor("等待框结束", 40_000, () -> {
                    s.onActivity(a -> busy.set(a.openingBusy()));
                    return !busy.get();
                });
                player = inst.waitForMonitorWithTimeout(m, 15_000);
                Log.i(TAG, "opening: library item " + index + " -> " + (player == null ? "no match" : "player"));
            }
            assertTrue("应打开播放页", player instanceof PlayerActivity);
            assertNoSecondPlayer(inst, player);
            Log.i(TAG, "opening: library triple tap opened once");
            player.finish();
        }
    }

    /** 第一个播放页出现后，再等几秒确认没有因为连点又打开第二个。 */
    private static void assertNoSecondPlayer(Instrumentation inst, Activity first) {
        Instrumentation.ActivityMonitor again = inst.addMonitor(PlayerActivity.class.getName(), null, false);
        Activity second = inst.waitForMonitorWithTimeout(again, 5_000);
        if (second != null) Log.i(TAG, "opening: second player " + second + " task=" + second.getTaskId() + " finishing=" + second.isFinishing()
                + " title=" + second.getIntent().getStringExtra("search_title"));
        assertTrue("连点只应打开一个播放页", second == null || second == first);
    }

    @Test public void searchTapTwiceOpensOnce() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a -> a.searchForTest("庆余年"));
            AtomicReference<Integer> n = new AtomicReference<>(0);
            waitFor("搜索结果", 90_000, () -> {
                s.onActivity(a -> {
                    RecyclerView rv = find(a.getWindow().getDecorView(), false);
                    n.set(rv == null || rv.getAdapter() == null ? 0 : rv.getAdapter().getItemCount());
                });
                return n.get() > 0;
            });
            Thread.sleep(3000);
            Instrumentation.ActivityMonitor monitor = inst.addMonitor(PlayerActivity.class.getName(), null, false);
            AtomicBoolean busy = new AtomicBoolean();
            s.onActivity(a -> {
                clickItem(a, false, 0);
                busy.set(a.openingBusy());
                clickItem(a, false, 0);
                clickItem(a, false, 0);
            });
            assertTrue("搜索结果点击后应显示等待框", busy.get());
            Activity player = inst.waitForMonitorWithTimeout(monitor, 60_000);
            assertTrue("应打开播放页", player instanceof PlayerActivity);
            Log.i(TAG, "opening: first player " + player + " task=" + player.getTaskId());
            assertNoSecondPlayer(inst, player);
            Log.i(TAG, "opening: search triple tap opened once");
            player.finish();
        }
    }

    @Test public void settingsShowsCopyright() throws Exception {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(MainActivity::openSettingsForTest);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicBoolean found = new AtomicBoolean();
            s.onActivity(a -> found.set(hasText(a.getWindow().getDecorView(), "开发者：" + BuildConfig.DEVELOPER)));
            assertTrue("设置页应显示开发者信息", found.get());
            s.onActivity(a -> scrollToBottom(a.getWindow().getDecorView()));
            Thread.sleep(1500);
            shot("settings-about");
            Log.i(TAG, "opening: copyright ok");
        }
    }

    @Test public void settingsSubPagesForRelayAndAutoSwitch() throws Exception {
        io.github.fugitive844.decotv.data.Prefs prefs = DecoApp.get().prefs;
        int oldKb = prefs.switchMinSpeedKb();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(MainActivity::openSettingsForTest);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicBoolean flat = new AtomicBoolean();
            s.onActivity(a -> flat.set(hasText(a.getWindow().getDecorView(), "播放失败自动换源")));
            assertTrue("设置页不应再平铺自动换源开关", !flat.get());
            shot("settings-main");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("自动换源"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("播放失败自动换源"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("自动换源网速条件 100 KB/s"))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            assertEquals(100, prefs.switchMinSpeedKb());
            shot("settings-autoswitch");
            androidx.test.espresso.Espresso.pressBack();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicBoolean summary = new AtomicBoolean();
            s.onActivity(a -> summary.set(hasText(a.getWindow().getDecorView(), "开启 · 低于 100 KB/s")));
            assertTrue("返回后摘要应更新", summary.get());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("中转策略"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(org.hamcrest.Matchers.containsString("默认全部直连")))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            shot("settings-relay");
            Log.i(TAG, "opening: settings sub pages ok");
        } finally {
            prefs.setSwitchMinSpeedKb(oldKb);
        }
    }

    /** 截图存到 App 外部文件目录，可用 adb pull 取回人工查看。 */
    private static void shot(String name) {
        android.graphics.Bitmap b = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        java.io.File dir = ApplicationProvider.getApplicationContext().getExternalFilesDir("shots");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name + ".png"))) {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            Log.i(TAG, "screenshot " + name + " saved");
        } catch (Exception e) {
            Log.w(TAG, "screenshot " + name + " failed: " + e);
        }
    }

    private static void scrollToBottom(View v) {
        if (v instanceof android.widget.ScrollView) ((android.widget.ScrollView) v).fullScroll(View.FOCUS_DOWN);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) scrollToBottom(((ViewGroup) v).getChildAt(i));
    }

    private static void clickItem(Activity a, boolean grid, int position) {
        RecyclerView rv = find(a.getWindow().getDecorView(), grid);
        rv.findViewHolderForAdapterPosition(position).itemView.performClick();
    }

    private static RecyclerView find(View v, boolean grid) {
        if (v instanceof RecyclerView && v.isShown()) {
            RecyclerView.LayoutManager lm = ((RecyclerView) v).getLayoutManager();
            boolean isGrid = lm instanceof GridLayoutManager;
            boolean vertical = lm instanceof LinearLayoutManager && ((LinearLayoutManager) lm).getOrientation() == RecyclerView.VERTICAL;
            if (grid ? isGrid : (!isGrid && vertical)) return (RecyclerView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                RecyclerView r = find(g.getChildAt(i), grid);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean hasText(View v, String text) {
        if (v instanceof TextView && String.valueOf(((TextView) v).getText()).contains(text)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasText(g.getChildAt(i), text)) return true;
        }
        return false;
    }
}
