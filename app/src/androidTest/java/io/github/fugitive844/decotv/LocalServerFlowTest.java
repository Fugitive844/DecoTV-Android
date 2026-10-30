package io.github.fugitive844.decotv;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.PlayerActivity;
import io.github.fugitive844.decotv.ui.LoginActivity;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 对本机测试服务端的端到端流程。账号经 -e 参数传入，缺参数时跳过：
 * -e liveBase http://10.0.2.2:13300 -e liveUser ... -e livePassword ...
 */
@RunWith(AndroidJUnit4.class)
public class LocalServerFlowTest {
    private String base, user, password;
    private DecoApp app;

    @Before public void setup() {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        base = args.getString("liveBase");
        user = args.getString("liveUser");
        password = args.getString("livePassword");
        app = ApplicationProvider.getApplicationContext();
        app.prefs.logout();
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.DIRECT);
    }

    private static void waitFor(String what, BooleanSupplier ready) throws Exception {
        long end = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < end) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(300);
        }
        throw new AssertionError("等待超时：" + what);
    }

    private static boolean safe(BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 找到文字（或无障碍描述）匹配且可用的按钮并点击，返回是否点到。播放页的操作栏是图标 + 文字，描述随状态变化。 */
    private static boolean clickButton(ActivityScenario<PlayerActivity> s, String text) {
        java.util.concurrent.atomic.AtomicBoolean clicked = new java.util.concurrent.atomic.AtomicBoolean();
        s.onActivity(a -> {
            android.view.View b = findButton(a.getWindow().getDecorView(), text);
            if (b != null && b.isEnabled() && b.isShown()) clicked.set(b.performClick());
        });
        return clicked.get();
    }

    private static android.view.View findButton(android.view.View v, String text) {
        if (v instanceof MaterialButton && text.contentEquals(((MaterialButton) v).getText())) return v;
        if (v.isClickable() && v.getContentDescription() != null && text.contentEquals(v.getContentDescription())) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = findButton(g.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void login() throws Exception {
        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            onView(withHint("服务器地址")).perform(replaceText(base));
            onView(withHint("用户名")).perform(replaceText(user));
            onView(withHint("密码")).perform(replaceText(password), closeSoftKeyboard());
            onView(allOf(withText("登录"), isAssignableFrom(MaterialButton.class))).perform(click());
            waitFor("登录", () -> app.prefs.loggedIn());
            assertEquals(Relay.normalizeServer(base), app.prefs.server());
        }
    }

    @Test public void loginSearchHistoryFavoriteAndSettings() throws Exception {
        login();
        String keyword = "庆余年";
        app.api.deleteSearchHistory(keyword);
        try (ActivityScenario<LoginActivity> ignored = ActivityScenario.launch(LoginActivity.class)) {
            // 已登录时直接进入主界面（首页），切到「搜索」
            onView(allOf(withText("搜索"), isDescendantOfA(isAssignableFrom(com.google.android.material.bottomnavigation.BottomNavigationView.class)), isDisplayed())).perform(click());
            onView(withHint("搜索影视")).perform(replaceText(keyword), closeSoftKeyboard());
            onView(allOf(withText("搜索"), isAssignableFrom(MaterialButton.class))).perform(click());
            waitFor("写入搜索记录", () -> safe(() -> {
                try {
                    return app.api.searchHistory().contains(keyword);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            // 设置收在「我的」里
            onView(allOf(withText("我的"), isDescendantOfA(isAssignableFrom(com.google.android.material.bottomnavigation.BottomNavigationView.class)), isDisplayed())).perform(click());
            onView(allOf(withText("设置"), isDisplayed())).perform(click());
            // 中转策略在设置的二级页面里
            onView(withContentDescription("中转策略")).perform(scrollTo(), click());
            onView(withContentDescription("点播视频 智能")).perform(click());
            assertEquals(Relay.Mode.SMART, app.prefs.mode(Relay.Item.VOD));
            onView(withContentDescription("点播视频 不中转")).perform(click());
            assertEquals(Relay.Mode.DIRECT, app.prefs.mode(Relay.Item.VOD));
        }

        List<Video> results = app.api.search(keyword);
        assertFalse("本地测试服务端应能搜到结果", results.isEmpty());
        Video video = results.get(0);
        app.api.deleteFavorite(video.key());
        Intent intent = PlayerActivity.intent(app, video, 0, 0);
        try (ActivityScenario<PlayerActivity> s = ActivityScenario.launch(intent)) {
            // 播放中画面持续重绘，Espresso 等不到空闲，直接在主线程找按钮点击
            waitFor("收藏按钮可用", () -> clickButton(s, "收藏"));
            waitFor("加入收藏", () -> safe(() -> {
                try {
                    return app.api.isFavorite(video.key());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            waitFor("按钮切换为已收藏并点击取消", () -> clickButton(s, "已收藏"));
            waitFor("取消收藏", () -> safe(() -> {
                try {
                    return !app.api.isFavorite(video.key());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertTrue(app.prefs.loggedIn());
    }
}
