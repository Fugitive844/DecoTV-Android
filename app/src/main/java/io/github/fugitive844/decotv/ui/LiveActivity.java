package io.github.fugitive844.decotv.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.NetLog;
import io.github.fugitive844.decotv.net.Relay;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播播放：默认用直播源 UA 直连；智能模式失败后改走服务端直播代理。
 * 同名频道合并后有多个源：起播失败按设置自动换到下一个没试过的源，也可以点「换源」手动选。
 */
@OptIn(markerClass = UnstableApi.class)
public class LiveActivity extends AppCompatActivity {
    private static final long STARTUP_TIMEOUT_MS = 15000;
    // 频道列表可能上千条，不走 Intent，打开前由列表页交给这里
    private static LiveSource pendingSource;
    private static List<Channel> pendingChannels = new ArrayList<>();
    private static int pendingIndex;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LiveSource source;
    private List<Channel> channels;
    private ExoPlayer player;
    private PlayerView view;
    private RecyclerView list;
    private LinearLayoutManager listLayout;
    private RowAdapter<Channel> adapter;
    private TextView status;
    private Media current;
    private int index;
    private int sourceIndex;
    private final java.util.Set<Integer> triedSources = new java.util.HashSet<>();
    private MaterialButton switchButton;
    private boolean ready;
    private volatile String playState = "loading";
    private PlayerGestures.Frame playerBox;
    private TextView route;
    private BufferSpeed speed;
    private PlayerLayout layout;
    private PlayerGestures gestures;
    // 全屏时点屏幕弹出：左侧频道列表，右侧当前频道的源
    private LinearLayout channelPanel, sourcePanel;
    private LinearLayoutManager channelOverlayLayout;
    private OverlayAdapter channelOverlayAdapter, sourceOverlayAdapter;
    private boolean overlayShown;

    /** 播放状态，供联调测试读取：loading、ready:direct|relay:主机、failed。 */
    public String playbackState() { return playState; }

    static void open(Context c, LiveSource source, List<Channel> channels, int index) {
        prepare(source, channels, index);
        c.startActivity(new Intent(c, LiveActivity.class));
    }

    public static void prepare(LiveSource source, List<Channel> channels, int index) {
        pendingSource = source;
        pendingChannels = new ArrayList<>(channels);
        pendingIndex = index;
    }

    private long startedAt;
    private final Runnable startupTimeout = new Runnable() {
        @Override public void run() {
            if (ready) return;
            // 超时但缓冲网速还够：说明还在正常下载，继续等，过几秒再看
            if (speed.keepWaiting(android.os.SystemClock.elapsedRealtime() - startedAt)) {
                status.setText(channels.get(index).name + sourceLabel() + " · 加载较慢（" + BufferSpeed.format(speed.bytesPerSecond()) + "），继续等待…");
                handler.postDelayed(this, BufferSpeed.RECHECK_MS);
                return;
            }
            onFailed(new PlaybackException("起播超时", null, PlaybackException.ERROR_CODE_TIMEOUT));
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        source = pendingSource;
        channels = pendingChannels;
        if (source == null || channels.isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = Ui.column(this, 0);
        view = new PlayerView(this);
        Ui.stylePlayer(view);
        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        // 全屏按钮进入全屏；手机上转动屏幕同样会切换，平板横屏为左右布局
        view.setFullscreenButtonClickListener(ignored -> layout.toggle());
        // 齿轮按钮改为换源，全屏时不必退出即可切换（直播用不到倍速）
        view.findViewById(androidx.media3.ui.R.id.exo_settings).setOnClickListener(v -> chooseSource());
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (layout != null) layout.setControlsVisible(visibility == android.view.View.VISIBLE);
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (layout.fullscreen()) {
                    layout.set(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
        playerBox = new PlayerGestures.Frame(this);
        playerBox.addView(view, new android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        route = Ui.routeBadge(this);
        playerBox.addView(route, Ui.badgeParams(this));
        speed = new BufferSpeed(this);
        playerBox.addView(speed.view(), speed.params(this));
        status = Ui.text(this, "", 13, false);
        int pad = Ui.dp(this, 8);
        status.setPadding(pad, pad, pad, pad);
        LinearLayout bar = Ui.row(this);
        bar.addView(status, Ui.weight(1));
        switchButton = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        switchButton.setText("换源");
        switchButton.setOnClickListener(v -> chooseSource());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMarginEnd(pad);
        bar.addView(switchButton, bp);
        root.addView(bar);
        int playingColor = com.google.android.material.color.MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary);
        adapter = new RowAdapter<Channel>(48, 30, (h, c) -> {
            // 行会被复用，先记下默认颜色，正在播放的频道用主题色标出
            if (h.title.getTag() == null) h.title.setTag(h.title.getTextColors());
            boolean playing = c == channels.get(index);
            if (playing) h.title.setTextColor(playingColor);
            else h.title.setTextColor((android.content.res.ColorStateList) h.title.getTag());
            h.title.setText(playing ? "▶ " + c.name : c.name);
            int n = c.sources().size();
            h.subtitle.setText(n > 1 ? c.group + " · " + n + " 个源" : c.group);
            h.image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            DecoApp.get().images.logo(h.image, c.logo, source.key);
        }).onClick((c, i) -> play(i));
        adapter.set(channels);
        list = new RecyclerView(this);
        listLayout = new LinearLayoutManager(this);
        list.setLayoutManager(listLayout);
        list.setAdapter(adapter);
        root.addView(list, Ui.fill(1));
        android.util.TypedValue bg = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, bg, true);
        root.setBackgroundColor(bg.data);
        layout = new PlayerLayout(this, playerBox, root);
        buildOverlay();
        layout.setOnChange(this::updateOverlay);
        setContentView(layout.view());
        gestures = new PlayerGestures(this, playerBox, new PlayerGestures.Host() {
            @Override public boolean seekable() { return false; }
            @Override public long position() { return 0; }
            @Override public long duration() { return 0; }
            @Override public void seekTo(long ms) { }
            @Override public boolean playing() { return player.isPlaying(); }
            @Override public void togglePlay() { if (player.isPlaying()) player.pause(); else player.play(); }
            @Override public void holdSpeed(boolean on) { }
        });
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                speed.update(playbackState == Player.STATE_BUFFERING);
                if (playbackState == Player.STATE_READY && !ready && current != null) {
                    ready = true;
                    handler.removeCallbacks(startupTimeout);
                    NetLog.use(Relay.Item.LIVE_STREAM, current.relayed, current.url);
                    playState = "ready:" + (current.relayed ? "relay" : "direct") + ":" + MediaUrls.host(current.url);
                    Ui.setRoute(route, current.relayed);
                    status.setText(channels.get(index).name + sourceLabel() + " · " + (current.relayed ? "经服务端中转" : "直连 " + MediaUrls.host(current.url)));
                }
            }

            @Override public void onPlayerError(@NonNull PlaybackException error) { onFailed(error); }
        });
        play(Math.max(0, Math.min(pendingIndex, channels.size() - 1)));
    }

    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        layout.onConfigurationChanged(config);
    }

    /** 当前是否全屏，供联调测试读取。 */
    public boolean isFullscreen() { return layout != null && layout.fullscreen(); }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && layout.fullscreen()) Ui.immersive(this, true);
    }

    private Media target(String url, boolean relay) {
        Media m = new Media();
        m.hls = MediaUrls.isHls(url);
        if (relay) {
            m.url = Relay.liveProxy(DecoApp.get().api.server(), url, source.key, m.hls);
            m.relayed = true;
        } else {
            m.url = url;
            m.userAgent = source.userAgent();
        }
        return m;
    }

    private void play(int i) {
        int previous = index;
        index = i;
        adapter.notifyItemChanged(previous);
        adapter.notifyItemChanged(i);
        revealCurrent();
        if (channelOverlayAdapter != null) {
            channelOverlayAdapter.select(i);
            if (overlayShown) revealOverlay();
        }
        Channel c = channels.get(i);
        setTitle(c.name);
        triedSources.clear();
        int n = c.sources().size();
        switchButton.setText(n > 1 ? "换源 (" + n + ")" : "换源");
        switchButton.setEnabled(n > 1);
        playSource(0);
    }

    /** 频道列表定位到正在播放的频道：不在可见范围内（包括刚打开时）就把它滚到列表顶部。 */
    private void revealCurrent() {
        int first = listLayout.findFirstCompletelyVisibleItemPosition();
        int last = listLayout.findLastCompletelyVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || index < first || index > last) listLayout.scrollToPositionWithOffset(index, 0);
    }

    /** 列表中第一个完整可见的频道位置，供联调测试读取。 */
    public int firstVisibleChannel() { return listLayout.findFirstCompletelyVisibleItemPosition(); }

    private void playSource(int s) {
        sourceIndex = s;
        triedSources.add(s);
        syncSourceOverlay();
        start(target(channels.get(index).sources().get(s), DecoApp.get().prefs.mode(Relay.Item.LIVE_STREAM) == Relay.Mode.RELAY));
    }

    private void buildOverlay() {
        channelOverlayAdapter = new OverlayAdapter(i -> channels.get(i).name, i -> {
            view.showController();
            if (i != index) play(i);
        });
        channelOverlayAdapter.count = channels.size();
        RecyclerView channelList = new RecyclerView(this);
        channelOverlayLayout = new LinearLayoutManager(this);
        channelList.setLayoutManager(channelOverlayLayout);
        channelList.setAdapter(channelOverlayAdapter);
        channelPanel = overlayPanel("频道", channelList);
        sourceOverlayAdapter = new OverlayAdapter(i -> "源 " + (i + 1) + " · " + MediaUrls.host(channels.get(index).sources().get(i)), i -> {
            view.showController();
            if (i != sourceIndex || !ready) playSource(i);
        });
        RecyclerView sourceList = new RecyclerView(this);
        sourceList.setLayoutManager(new LinearLayoutManager(this));
        sourceList.setAdapter(sourceOverlayAdapter);
        sourcePanel = overlayPanel("换源", sourceList);
        playerBox.addView(channelPanel, new android.widget.FrameLayout.LayoutParams(0, 0));
        playerBox.addView(sourcePanel, new android.widget.FrameLayout.LayoutParams(0, 0));
        playerBox.exempt(channelPanel);
        playerBox.exempt(sourcePanel);
        // 播放区尺寸变化（旋转、进出全屏）后重新计算浮层大小
        playerBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::updateOverlay);
        });
    }

    private LinearLayout overlayPanel(String title, RecyclerView body) {
        LinearLayout panel = Ui.column(this, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setColor(0xB3000000);
        panel.setBackground(bg);
        panel.setClickable(true);
        TextView head = Ui.text(this, title, 14, true);
        head.setTextColor(0xCCFFFFFF);
        int pad = Ui.dp(this, 12);
        head.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 6));
        panel.addView(head);
        // 浏览列表时保持控制栏显示，免得滑着滑着浮层消失
        body.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                view.showController();
                return false;
            }
        });
        panel.addView(body, Ui.fill(1));
        panel.setVisibility(android.view.View.GONE);
        return panel;
    }

    /** 全屏且控制栏显示时才显示浮层；只有一个源时不显示换源面板。 */
    private void updateOverlay() {
        if (channelPanel == null || channels == null || channels.isEmpty()) return;
        boolean show = layout.fullscreen() && layout.controlsVisible();
        int w = playerBox.getWidth();
        if (show && w > 0) {
            int top = Ui.dp(this, 44), bottom = Ui.dp(this, 84), edge = Ui.dp(this, 12);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    Math.min(Ui.dp(this, 260), (int) (w * 0.28f)), android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.START);
            lp.setMargins(edge, top, 0, bottom);
            channelPanel.setLayoutParams(lp);
            // 手机全屏右侧中间有旋转按钮，换源面板让开它
            int right = PlayerLayout.tablet(this) ? edge : Ui.dp(this, 76);
            android.widget.FrameLayout.LayoutParams rp = new android.widget.FrameLayout.LayoutParams(
                    Math.min(Ui.dp(this, 200), (int) (w * 0.22f)), android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.END);
            rp.setMargins(0, top, right, bottom);
            sourcePanel.setLayoutParams(rp);
        }
        channelPanel.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        sourcePanel.setVisibility(show && channels.get(index).sources().size() > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (show && !overlayShown) revealOverlay();
        overlayShown = show;
    }

    /** 浮层频道列表把正在播放的频道滚到靠上位置。 */
    private void revealOverlay() {
        int first = channelOverlayLayout.findFirstCompletelyVisibleItemPosition();
        int last = channelOverlayLayout.findLastCompletelyVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || index < first || index > last) channelOverlayLayout.scrollToPositionWithOffset(index, Ui.dp(this, 48));
    }

    private void syncSourceOverlay() {
        if (sourceOverlayAdapter == null) return;
        sourceOverlayAdapter.count = channels.get(index).sources().size();
        sourceOverlayAdapter.selected = sourceIndex;
        sourceOverlayAdapter.notifyDataSetChanged();
        updateOverlay();
    }

    /** 全屏浮层（左侧频道列表）是否正在显示，供联调测试读取。 */
    public boolean overlayVisible() { return channelPanel != null && channelPanel.getVisibility() == android.view.View.VISIBLE; }

    /** 显示或隐藏控制栏，模拟点屏幕，供联调测试调用。 */
    public void showControlsForTest(boolean on) { if (on) view.showController(); else view.hideController(); }

    /** 全屏换源面板是否正在显示，供联调测试读取。 */
    public boolean sourceOverlayVisible() { return sourcePanel != null && sourcePanel.getVisibility() == android.view.View.VISIBLE; }

    /** 全屏浮层里的文字列表：白字，当前项加底色并加粗。 */
    private static final class OverlayAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        interface Label { String of(int i); }
        interface Click { void on(int i); }

        private final Label label;
        private final Click click;
        int count, selected;

        OverlayAdapter(Label label, Click click) {
            this.label = label;
            this.click = click;
        }

        void select(int i) {
            int previous = selected;
            selected = i;
            notifyItemChanged(previous);
            notifyItemChanged(i);
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int type) {
            TextView t = Ui.text(parent.getContext(), "", 15, false);
            t.setTextColor(0xFFFFFFFF);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int h = Ui.dp(parent.getContext(), 12), v = Ui.dp(parent.getContext(), 10);
            t.setPadding(h, v, h, v);
            t.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(t) { };
            t.setOnClickListener(x -> {
                int p = holder.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) click.on(p);
            });
            return holder;
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int i) {
            TextView t = (TextView) holder.itemView;
            boolean on = i == selected;
            t.setText(on ? "▶ " + label.of(i) : label.of(i));
            t.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            t.setBackgroundColor(on ? 0x33FFFFFF : 0);
        }

        @Override public int getItemCount() { return count; }
    }

    /** 多源时显示「源 2/5」。 */
    private String sourceLabel() {
        int n = channels.get(index).sources().size();
        return n > 1 ? " · 源 " + (sourceIndex + 1) + "/" + n : "";
    }

    /** 当前播放的是第几个源（从 0 起），供联调测试读取。 */
    public int sourceIndex() { return sourceIndex; }

    private void chooseSource() {
        List<String> urls = channels.get(index).sources();
        if (urls.size() <= 1) {
            Ui.toast(this, "该频道只有一个源");
            return;
        }
        String[] names = new String[urls.size()];
        for (int i = 0; i < names.length; i++) names[i] = "源 " + (i + 1) + " · " + MediaUrls.host(urls.get(i));
        new MaterialAlertDialogBuilder(this).setTitle(channels.get(index).name + " · 换源")
                .setSingleChoiceItems(names, sourceIndex, (d, which) -> {
                    d.dismiss();
                    if (which != sourceIndex || !ready) playSource(which);
                })
                .setNegativeButton("取消", null).show();
    }

    /** 下一个还没试过的源，从当前源往后循环查找；没有则返回 -1。 */
    private int nextUntriedSource() {
        int n = channels.get(index).sources().size();
        for (int k = 1; k < n; k++) {
            int s = (sourceIndex + k) % n;
            if (!triedSources.contains(s)) return s;
        }
        return -1;
    }

    private void start(Media media) {
        current = media;
        ready = false;
        playState = "loading";
        Ui.setRoute(route, null);
        status.setText(channels.get(index).name + sourceLabel() + " · " + (media.relayed ? "经服务端中转加载…" : "正在直连 " + MediaUrls.host(media.url) + " …"));
        player.setMediaSource(media.source());
        player.prepare();
        player.play();
        handler.removeCallbacks(startupTimeout);
        startedAt = android.os.SystemClock.elapsedRealtime();
        handler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS);
    }

    private void onFailed(PlaybackException error) {
        handler.removeCallbacks(startupTimeout);
        if (current == null || isFinishing() || isDestroyed()) return;
        NetLog.fail(Relay.Item.LIVE_STREAM, current.relayed, current.url, error);
        if (!current.relayed && DecoApp.get().prefs.mode(Relay.Item.LIVE_STREAM) == Relay.Mode.SMART) {
            status.setText("直连失败，正在改用服务端中转…");
            start(target(channels.get(index).sources().get(sourceIndex), true));
            return;
        }
        int next = DecoApp.get().prefs.autoSwitchSource() ? nextUntriedSource() : -1;
        if (next >= 0) {
            Ui.toast(this, "源 " + (sourceIndex + 1) + " 无法播放，已自动切换到源 " + (next + 1));
            playSource(next);
            return;
        }
        playState = "failed";
        boolean more = channels.get(index).sources().size() > 1;
        status.setText(channels.get(index).name + sourceLabel() + " · 播放失败：" + error.getErrorCodeName() + (more ? "，可点「换源」" : ""));
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (player != null && current != null) player.play();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (speed != null) speed.stop();
        if (gestures != null) gestures.release();
        if (player != null) player.release();
    }
}
