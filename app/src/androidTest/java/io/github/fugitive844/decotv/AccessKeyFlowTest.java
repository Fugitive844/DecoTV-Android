package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputLayout;
import io.github.fugitive844.decotv.ui.LoginActivity;
import io.github.fugitive844.decotv.ui.MainActivity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 联调：访问秘钥。需要本地服务端已关闭「成人内容过滤」，并通过参数 accessKey 传入一个已启用的秘钥。
 * 覆盖：请求统一带 ak、校验接口、源浏览器随秘钥刷新、登录页填写秘钥、「我的」页状态。
 */
@RunWith(AndroidJUnit4.class)
public class AccessKeyFlowTest {
    private static final String TAG = "DecoTest";
    private static final String WRONG = "DKAAAAAAAAAAAAAAAAAAAA";
    private DecoApp app;
    private Bundle args;
    private String base, key;

    @Before public void setup() throws Exception {
        args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase") && args.containsKey("accessKey"));
        app = ApplicationProvider.getApplicationContext();
        base = args.getString("liveBase");
        key = args.getString("accessKey");
        app.prefs.setAccessKey("");
        login();
    }

    @After public void tearDown() {
        if (app != null) app.prefs.setAccessKey("");
    }

    private void login() throws Exception {
        String user = args.getString("liveUser");
        app.prefs.saveLogin(base, user, app.api.login(base, user, args.getString("livePassword")));
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

    @Test public void requestsCarryKey() throws Exception {
        assertTrue("未填秘钥时应过滤", app.api.serverConfig().optBoolean("AdultFilterEnabled"));
        int without = app.api.sourceSites().size();
        app.prefs.setAccessKey(WRONG);
        assertTrue("错误秘钥时应过滤", app.api.serverConfig().optBoolean("AdultFilterEnabled"));
        app.prefs.setAccessKey(key);
        assertFalse("有效秘钥时不过滤", app.api.serverConfig().optBoolean("AdultFilterEnabled"));
        int with = app.api.sourceSites().size();
        Log.i(TAG, "source sites without=" + without + " with=" + with);
        assertTrue("带秘钥后可浏览的源应更多", with > without);
    }

    @Test public void sourceBrowserReloadsAfterKeyChange() throws Exception {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            AtomicInteger n = new AtomicInteger();
            s.onActivity(a -> a.openLibraryForTest(4));
            waitFor("源列表（无秘钥）", 60_000, () -> {
                s.onActivity(a -> n.set(a.sourceCountForTest()));
                return n.get() > 0;
            });
            int without = n.get();
            app.prefs.setAccessKey(key);
            s.onActivity(a -> {
                a.openMineForTest();
                a.openLibraryForTest(4);
            });
            waitFor("源列表（有秘钥）重新加载", 60_000, () -> {
                s.onActivity(a -> n.set(a.sourceCountForTest()));
                return n.get() > without;
            });
            Log.i(TAG, "browser sources without=" + without + " with=" + n.get());
        }
    }

    @Test public void loginWithKeyAndMineState() throws Exception {
        app.prefs.logout();
        try (ActivityScenario<LoginActivity> s = ActivityScenario.launch(LoginActivity.class)) {
            s.onActivity(a -> {
                setField(a, "用户名", args.getString("liveUser"));
                setField(a, "密码", args.getString("livePassword"));
                setField(a, "访问秘钥（选填）", WRONG);
                assertTrue("应显示统一提示文案", findText(a.getWindow().getDecorView(), "请联系站长获取秘钥"));
                clickButton(a, "登录");
            });
            Thread.sleep(5_000);
            assertFalse("错误秘钥不应登录", app.prefs.loggedIn());
            s.onActivity(a -> {
                setField(a, "访问秘钥（选填）", key);
                clickButton(a, "登录");
            });
            waitFor("填写有效秘钥后登录", 30_000, () -> app.prefs.loggedIn());
            assertEquals(key, app.prefs.accessKey());
        }
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(MainActivity::openMineForTest);
            AtomicBoolean shown = new AtomicBoolean();
            waitFor("「我的」页显示秘钥已生效", 30_000, () -> {
                s.onActivity(a -> shown.set(findText(a.getWindow().getDecorView(), "已生效")));
                return shown.get();
            });
        }
    }

    private static void setField(Activity a, String hint, String text) {
        TextInputLayout layout = findLayout(a.getWindow().getDecorView(), hint);
        if (layout == null || layout.getEditText() == null) throw new AssertionError("找不到输入框：" + hint);
        layout.getEditText().setText(text);
    }

    private static TextInputLayout findLayout(View v, String hint) {
        if (v instanceof TextInputLayout && ((TextInputLayout) v).getHint() != null
                && hint.contentEquals(((TextInputLayout) v).getHint())) return (TextInputLayout) v;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                TextInputLayout found = findLayout(((ViewGroup) v).getChildAt(i), hint);
                if (found != null) return found;
            }
        return null;
    }

    private static boolean findText(View v, String part) {
        if (v instanceof TextView && v.isShown() && String.valueOf(((TextView) v).getText()).contains(part)) return true;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                if (findText(((ViewGroup) v).getChildAt(i), part)) return true;
        return false;
    }

    private static void clickButton(Activity a, String text) {
        View b = findButton(a.getWindow().getDecorView(), text);
        if (b == null) throw new AssertionError("找不到按钮：" + text);
        b.performClick();
    }

    private static View findButton(View v, String text) {
        if (v instanceof android.widget.Button && text.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View found = findButton(((ViewGroup) v).getChildAt(i), text);
                if (found != null) return found;
            }
        return null;
    }
}
