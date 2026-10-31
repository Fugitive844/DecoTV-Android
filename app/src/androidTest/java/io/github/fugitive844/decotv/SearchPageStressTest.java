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

import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;

import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.MainActivity;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** 宽泛关键词的结果很多：搜索页要能撑到全部源返回，且内存不能失控。-e query 指定关键词。 */
@RunWith(AndroidJUnit4.class)
public class SearchPageStressTest {
    private static String memory() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024) + " / " + r.maxMemory() / (1024 * 1024) + " MB";
    }

    @Test public void broadQuerySurvives() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        DecoApp app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        app.prefs.saveLogin(base, args.getString("liveUser"), app.api.login(base, args.getString("liveUser"), args.getString("livePassword")));
        app.prefs.setMode(Relay.Item.SEARCH, Relay.Mode.of(args.getString("mode", "direct")));
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            onView(allOf(withText("搜索"), isDescendantOfA(isAssignableFrom(com.google.android.material.bottomnavigation.BottomNavigationView.class)), isDisplayed())).perform(click());
            onView(withHint("搜索影视")).perform(replaceText(args.getString("query", "你好")), closeSoftKeyboard());
            onView(allOf(withText("搜索"), isAssignableFrom(MaterialButton.class))).perform(click());
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 90000) {
                Thread.sleep(2000);
                Log.i("DecoTest", (System.currentTimeMillis() - start) / 1000 + "s 内存 " + memory());
            }
        }
    }
}
