package io.github.fugitive844.decotv;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

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

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 联调：首页各栏、片库各分类与源浏览器能加载并打开播放；修改密码（用注册的测试账号，改完改回）。 */
@RunWith(AndroidJUnit4.class)
public class BrowseFlowTest {
    private static final String TAG = "DecoTest";
    private DecoApp app;
    private Bundle args;

    @Before public void setup() throws Exception {
        args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        login(args.getString("liveUser"), args.getString("livePassword"));
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.DIRECT);
    }

    private void login(String user, String password) throws Exception {
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, user, app.api.login(base, user, password));
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

    @Test public void homeLibraryAndSourceBrowser() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<int[]> counts = new AtomicReference<>(new int[5]);
            waitFor("首页四栏", 60_000, () -> {
                s.onActivity(a -> counts.set(a.homeCounts()));
                int[] c = counts.get();
                return c[1] > 0 && c[2] > 0 && c[3] > 0 && c[4] > 0;
            });
            Log.i(TAG, "home counts " + Arrays.toString(counts.get()));

            AtomicInteger n = new AtomicInteger();
            String[] names = {"电影", "剧集", "动漫", "综艺"};
            for (int tab = 0; tab < 4; tab++) {
                int t = tab;
                s.onActivity(a -> a.openLibraryForTest(t));
                waitFor("片库 " + names[tab], 60_000, () -> {
                    s.onActivity(a -> n.set(a.libraryCount()));
                    return n.get() > 0;
                });
                Log.i(TAG, "library " + names[tab] + " count=" + n.get());
            }

            s.onActivity(a -> a.openLibraryForTest(4));
            waitFor("源列表", 60_000, () -> {
                s.onActivity(a -> n.set(a.sourceCountForTest()));
                return n.get() > 0;
            });
            int sites = n.get();
            boolean browsed = false;
            for (int i = 0; i < Math.min(8, sites) && !browsed; i++) {
                int index = i;
                s.onActivity(a -> a.selectSourceForTest(index));
                long end = System.currentTimeMillis() + 20_000;
                while (System.currentTimeMillis() < end) {
                    s.onActivity(a -> n.set(a.libraryCount()));
                    if (n.get() > 0) {
                        browsed = true;
                        break;
                    }
                    Thread.sleep(500);
                }
                AtomicReference<String> name = new AtomicReference<>("");
                s.onActivity(a -> name.set(a.librarySourceName()));
                Log.i(TAG, "source browser " + name.get() + " count=" + n.get());
            }
            assertTrue("前 8 个源里应至少有一个能浏览", browsed);

            // 点开源浏览器里的第一部，应能起播
            Instrumentation.ActivityMonitor monitor = inst.addMonitor(PlayerActivity.class.getName(), null, false);
            s.onActivity(a -> {
                androidx.recyclerview.widget.RecyclerView rv = findGrid(a.getWindow().getDecorView());
                rv.findViewHolderForAdapterPosition(0).itemView.performClick();
            });
            Activity player = inst.waitForMonitorWithTimeout(monitor, 60_000);
            assertTrue("应打开播放页", player instanceof PlayerActivity);
            AtomicReference<String> state = new AtomicReference<>("loading");
            waitFor("起播", 60_000, () -> {
                inst.runOnMainSync(() -> state.set(((PlayerActivity) player).playbackState()));
                return !state.get().equals("loading");
            });
            Log.i(TAG, "source browser play -> " + state.get());
            assertTrue("源浏览器条目应能播放：" + state.get(), state.get().startsWith("ready"));
            player.finish();
        }
    }

    private static androidx.recyclerview.widget.RecyclerView findGrid(android.view.View v) {
        if (v instanceof androidx.recyclerview.widget.RecyclerView && v.isShown()
                && ((androidx.recyclerview.widget.RecyclerView) v).getLayoutManager() instanceof androidx.recyclerview.widget.GridLayoutManager) {
            return (androidx.recyclerview.widget.RecyclerView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                androidx.recyclerview.widget.RecyclerView r = findGrid(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    @Test public void changePasswordAndRestore() throws Exception {
        String user = args.getString("regUser"), original = args.getString("regPassword");
        Assume.assumeTrue(user != null && original != null);
        login(user, original);
        String temp = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        app.api.changePassword(temp);
        try {
            login(user, temp);
            Log.i(TAG, "change password: login with new password ok");
        } finally {
            // 无论上面是否成功都改回原密码（登录 cookie 按用户名签名，改密码后仍有效）
            app.api.changePassword(original);
        }
        login(user, original);
        Log.i(TAG, "change password: restored");
        login(args.getString("liveUser"), args.getString("livePassword"));
    }
}
