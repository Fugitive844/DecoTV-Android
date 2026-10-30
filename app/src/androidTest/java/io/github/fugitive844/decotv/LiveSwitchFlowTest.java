package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.live.ChannelMerge;
import io.github.fugitive844.decotv.live.LiveChannels;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.LiveActivity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 直播同名频道合并后的多源播放：正常起播，以及直连全部失败时按「自动换源」开关换源或停下。 */
@RunWith(AndroidJUnit4.class)
public class LiveSwitchFlowTest {
    private static final String TAG = "DecoTest";
    private DecoApp app;
    private LiveSource source;
    private List<Channel> channels;
    private int index = -1;

    @Before public void setup() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        Assume.assumeTrue(args.containsKey("liveBase"));
        app = ApplicationProvider.getApplicationContext();
        String base = args.getString("liveBase");
        String cookie = app.api.login(base, args.getString("liveUser"), args.getString("livePassword"));
        app.prefs.saveLogin(base, args.getString("liveUser"), cookie);
        for (Relay.Item item : Relay.Item.values()) app.prefs.setMode(item, Relay.Mode.DIRECT);
        for (LiveSource s : app.api.liveSources()) {
            List<Channel> merged;
            try {
                List<Channel> raw = LiveChannels.load(app.api, s, Relay.Mode.DIRECT).channels;
                merged = ChannelMerge.merge(raw);
                Log.i(TAG, "live " + s.key + " raw=" + raw.size() + " merged=" + merged.size());
            } catch (Exception e) {
                continue;
            }
            for (int i = 0; i < merged.size(); i++) {
                int n = merged.get(i).sources().size();
                if (n >= 2 && n <= 6) {
                    source = s;
                    channels = merged;
                    index = i;
                    return;
                }
            }
        }
        throw new AssertionError("没有找到包含多个源的频道");
    }

    @After public void teardown() {
        ApiClient.testBlockThirdParty = false;
        app.prefs.setAutoSwitchSource(true);
    }

    private String play(AtomicInteger sourceIndex) throws Exception {
        LiveActivity.prepare(source, channels, index);
        try (ActivityScenario<LiveActivity> s = ActivityScenario.launch(LiveActivity.class)) {
            AtomicReference<String> state = new AtomicReference<>("loading");
            long end = System.currentTimeMillis() + 120000;
            while (System.currentTimeMillis() < end && state.get().equals("loading")) {
                Thread.sleep(500);
                s.onActivity(a -> {
                    state.set(a.playbackState());
                    sourceIndex.set(a.sourceIndex());
                });
            }
            return state.get();
        }
    }

    @Test public void mergedChannelPlays() throws Exception {
        AtomicInteger used = new AtomicInteger();
        String state = play(used);
        Log.i(TAG, "merged " + channels.get(index).name + " sources=" + channels.get(index).sources().size() + " -> " + state + " source=" + used.get());
        assertTrue("多源频道应能起播：" + state, state.startsWith("ready:"));
    }

    @Test public void autoSwitchTriesEverySource() throws Exception {
        app.prefs.setAutoSwitchSource(true);
        ApiClient.testBlockThirdParty = true;
        AtomicInteger used = new AtomicInteger();
        String state = play(used);
        int n = channels.get(index).sources().size();
        Log.i(TAG, "auto on blocked -> " + state + " source=" + used.get() + "/" + n);
        assertEquals("failed", state);
        assertEquals("自动换源应试到最后一个源", n - 1, used.get());
    }

    @Test public void manualModeStopsOnFirstFailure() throws Exception {
        app.prefs.setAutoSwitchSource(false);
        ApiClient.testBlockThirdParty = true;
        AtomicInteger used = new AtomicInteger();
        String state = play(used);
        Log.i(TAG, "auto off blocked -> " + state + " source=" + used.get());
        assertEquals("failed", state);
        assertEquals("关闭自动换源后应停在第一个源", 0, used.get());
    }
}
