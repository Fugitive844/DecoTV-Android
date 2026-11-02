package io.github.fugitive844.decotv.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.download.DownloadStore;
import io.github.fugitive844.decotv.download.DownloadTask;
import io.github.fugitive844.decotv.download.OfflinePlaylist;
import io.github.fugitive844.decotv.download.PlaylistDataSource;
import io.github.fugitive844.decotv.download.SegmentDataSource;
import io.github.fugitive844.decotv.download.Stream;
import io.github.fugitive844.decotv.download.VaultDataSource;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.Tasks;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放离线缓存：与在线点播相同的小窗 / 全屏、手势、倍速，所有可播放缓存按影片分组、首尾循环切换。
 * 加密缓存边读边解密；没缓存完的 m3u8 边存边播，缓存完成后在当前进度无缝切到最终文件。
 * 进度在有网络时照常同步到播放记录。
 */
@OptIn(markerClass = UnstableApi.class)
public class OfflinePlayerActivity extends AppCompatActivity {
    private static final String EXTRA_ID = "id";
    private static final long SAVE_INTERVAL_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DownloadTask> episodes = new ArrayList<>();
    private ExoPlayer player;
    private PlayerView view;
    private PlayerGestures.Frame box;
    private PlayerLayout layout;
    private PlayerGestures gestures;
    private TextView title, status, badge;
    private TextView listTitle;
    private LinearLayoutManager listLayout;
    private LinearLayout cachePanel;
    private TextView cachePanelTitle;
    private RowAdapter<DownloadTask> cacheOverlay;
    private LinearLayoutManager cacheOverlayLayout;
    private boolean overlayShown;
    private int overlayWidth, overlayHeight;
    private String overlayCurrentId = "";
    private DownloadTask currentTask;
    /** 正在边存边播的任务 id，没有为 null。 */
    private String streaming;
    private final DownloadStore.Listener storeListener = this::onStoreChanged;
    private TextView speedButton;
    private RowAdapter<DownloadTask> list;
    private DanmakuController danmaku;
    private Video video;
    private int position;
    private boolean holding;
    private float speedBeforeHold = 1f;

    private volatile String playState = "loading";

    /** 播放状态，供联调测试读取：loading、ready、failed:原因。 */
    public String playbackState() { return playState; }

    /** 是否正在边存边播，供联调测试读取。 */
    public boolean isStreaming() { return streaming != null; }

    /** 当前播放位置（毫秒），供联调测试读取。 */
    public long positionMs() { return player == null ? -1 : player.getCurrentPosition(); }

    public long durationMs() { return player == null ? -1 : player.getDuration(); }

    /** 当前缓存和列表大小，供联调测试读取。 */
    public String currentCacheId() { return currentTask == null ? "" : currentTask.id; }
    public int cacheCount() { return episodes.size(); }

    /** 测试用：跳到指定位置、暂停。 */
    public void seekForTest(long ms) { player.seekTo(ms); }

    public void pauseForTest() { player.pause(); }

    /** 弹幕状态与条数，供联调测试读取。 */
    public String danmakuInfo() { return danmaku == null ? "none" : danmaku.state() + " count=" + danmaku.count(); }

    static void open(Context c, DownloadTask t) { c.startActivity(intent(c, t.id)); }

    public static Intent intent(Context c, String id) { return new Intent(c, OfflinePlayerActivity.class).putExtra(EXTRA_ID, id); }

    private final Runnable saver = new Runnable() {
        @Override public void run() {
            save();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DownloadTask start = DecoApp.get().downloads.get(getIntent().getStringExtra(EXTRA_ID));
        if (start == null) {
            finish();
            return;
        }
        episodes.addAll(playableTasks());
        position = OfflinePlaylist.indexOf(episodes, start.id);
        if (position < 0) {
            Ui.toast(this, "该缓存暂时无法播放");
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        box = new PlayerGestures.Frame(this);
        view = new PlayerView(this);
        Ui.stylePlayer(view);
        player = new ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        view.setPlayer(controls(player));
        view.setFullscreenButtonClickListener(ignored -> layout.toggle());
        // 全屏时也可以手动选择缓存，倍速从右上角快捷按钮进入。
        view.findViewById(androidx.media3.ui.R.id.exo_settings).setOnClickListener(v -> playerMenu());
        view.findViewById(androidx.media3.ui.R.id.exo_prev).setContentDescription("上一个缓存");
        view.findViewById(androidx.media3.ui.R.id.exo_next).setContentDescription("下一个缓存");
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) v -> {
            if (layout != null) layout.setControlsVisible(v == View.VISIBLE);
        });
        box.addView(view);
        badge = Ui.routeBadge(this);
        box.addView(badge, Ui.badgeParams(this));
        badge.setText("离线");
        badge.setVisibility(View.VISIBLE);

        LinearLayout details = Ui.column(this, 12);
        android.util.TypedValue bg = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, bg, true);
        details.setBackgroundColor(bg.data);
        title = Ui.text(this, "", 18, true);
        details.addView(title);
        status = Ui.text(this, "", 12, false);
        status.setAlpha(0.6f);
        details.addView(status);
        LinearLayout actions = Ui.row(this);
        actions.addView(button("上一个缓存", v -> step(-1)));
        actions.addView(button("下一个缓存", v -> step(1)));
        actions.addView(button("缓存列表", v -> chooseCache()));
        actions.addView(button("弹幕", v -> danmaku.showPanel()));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(actions);
        details.addView(scroll);
        listTitle = Ui.text(this, "", 15, true);
        details.addView(listTitle);
        list = new RowAdapter<DownloadTask>(42, 58, (h, t) -> {
            DecoApp.get().images.offlineCover(h.image, t);
            boolean playing = currentTask != null && t.id.equals(currentTask.id);
            h.title.setText((playing ? "▶ " : "") + cacheLabel(t));
            h.title.setTextColor(playing ? com.google.android.material.color.MaterialColors.getColor(h.title,
                    androidx.appcompat.R.attr.colorPrimary) : Style.text(this));
            h.subtitle.setText(t.sourceName + " · " + (t.state == DownloadTask.State.DONE ? t.format.label + " · " + t.container
                    : "缓存中 " + Math.round(t.progress() * 100) + "% · 可边存边播"));
        }).onClick((t, i) -> selectCache(t.id));
        list.set(episodes);
        RecyclerView rv = new RecyclerView(this);
        listLayout = new LinearLayoutManager(this);
        rv.setLayoutManager(listLayout);
        rv.setAdapter(list);
        details.addView(rv, Ui.fill(1));

        layout = new PlayerLayout(this, box, details);
        layout.setLeftButtonAboveList(true);
        speedButton = layout.addSpeedButton(DecoApp.get().prefs.playbackSpeed(), this::chooseSpeed, badge);
        buildCacheOverlay();
        layout.setOnChange(this::updateCacheOverlay);
        setContentView(layout.view());
        danmaku = new DanmakuController(this, view, layout, player);
        gestures = new PlayerGestures(this, box, new PlayerGestures.Host() {
            @Override public boolean seekable() { return player.isCurrentMediaItemSeekable(); }
            @Override public long position() { return player.getCurrentPosition(); }
            @Override public long duration() { return Math.max(0, player.getDuration()); }
            @Override public void seekTo(long ms) { player.seekTo(ms); }
            @Override public boolean playing() { return player.isPlaying(); }
            @Override public void togglePlay() { if (player.isPlaying()) player.pause(); else player.play(); }
            @Override public void holdSpeed(boolean on) {
                if (on) {
                    speedBeforeHold = player.getPlaybackParameters().speed;
                    holding = true;
                    player.setPlaybackSpeed(2f);
                } else {
                    player.setPlaybackSpeed(speedBeforeHold);
                    holding = false;
                }
            }
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
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) {
                    playState = "ready";
                    android.util.Log.i("DecoStream", "ready streaming=" + streaming + " pos=" + player.getCurrentPosition()
                            + " dur=" + player.getDuration() + " seekable=" + player.isCurrentMediaItemSeekable());
                }
                if (s == Player.STATE_ENDED) {
                    step(1);
                }
            }

            @Override public void onPlayerError(@NonNull PlaybackException error) {
                playState = "failed:" + error.getErrorCodeName();
                status.setText("播放失败：" + error.getMessage());
            }

            @Override public void onPlaybackParametersChanged(@NonNull androidx.media3.common.PlaybackParameters params) {
                if (!holding) speedButton.setText(PlayerActivity.speedLabel(params.speed));
            }

            @Override public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize size) {
                layout.setVideoSize(size.width, size.height, size.pixelWidthHeightRatio);
            }
        });
        player.setPlaybackSpeed(DecoApp.get().prefs.playbackSpeed());
        DecoApp.get().downloads.listen(storeListener);
        play(position, 0);
    }

    private List<DownloadTask> playableTasks() {
        return OfflinePlaylist.build(DecoApp.get().downloads.all(),
                t -> t.state == DownloadTask.State.DONE || Stream.available(this, t));
    }

    /** 同步新增、删除和缓存进度，按任务 id 保持当前播放项。 */
    private void onStoreChanged() {
        if (currentTask == null || isFinishing()) return;
        int oldPosition = position;
        episodes.clear();
        episodes.addAll(playableTasks());
        list.set(episodes);
        if (episodes.isEmpty()) {
            save();
            player.stop();
            Ui.toast(this, "没有可播放的缓存");
            finish();
            return;
        }
        position = OfflinePlaylist.indexOf(episodes, currentTask.id);
        if (position < 0) {
            save();
            play(Math.min(oldPosition, episodes.size() - 1), 0);
            return;
        }
        updateListTitle();
        DownloadTask t = episodes.get(position);
        if (streaming != null && streaming.equals(t.id)) {
            if (t.state == DownloadTask.State.DONE) {
                long at = player.getCurrentPosition();
                android.util.Log.i("DecoStream", "switch to file at=" + at);
                boolean wasPlaying = player.getPlayWhenReady();
                play(position, at);
                if (!wasPlaying) player.pause();
                Ui.toast(this, "缓存完成，已切换到本地文件");
            } else {
                status.setText(streamStatus(t));
            }
        }
    }

    private static String streamStatus(DownloadTask t) {
        return "边存边播 · 已缓存 " + Math.round(t.progress() * 100) + "% · 没缓存到的部分在线加载并存入缓存";
    }

    private MaterialButton button(String text, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private Player controls(ExoPlayer exo) {
        return new ForwardingPlayer(exo) {
            @Override public boolean isCommandAvailable(int c) {
                if (c == COMMAND_SEEK_TO_NEXT || c == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                        || c == COMMAND_SEEK_TO_PREVIOUS || c == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) return !episodes.isEmpty();
                return super.isCommandAvailable(c);
            }

            @NonNull @Override public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .addIf(COMMAND_SEEK_TO_NEXT, !episodes.isEmpty()).addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, !episodes.isEmpty())
                        .addIf(COMMAND_SEEK_TO_PREVIOUS, !episodes.isEmpty()).addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, !episodes.isEmpty()).build();
            }

            @Override public void seekToNext() { step(1); }
            @Override public void seekToNextMediaItem() { step(1); }
            @Override public void seekToPrevious() { step(-1); }
            @Override public void seekToPreviousMediaItem() { step(-1); }
        };
    }

    private void chooseSpeed() {
        String[] labels = new String[PlayerActivity.SPEEDS.length];
        int checked = -1;
        float now = player.getPlaybackParameters().speed;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = PlayerActivity.speedLabel(PlayerActivity.SPEEDS[i]);
            if (Math.abs(PlayerActivity.SPEEDS[i] - now) < 0.01f) checked = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("播放倍速")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    player.setPlaybackSpeed(PlayerActivity.SPEEDS[which]);
                    d.dismiss();
                }).show();
    }

    private static String cacheLabel(DownloadTask t) { return t.title + " · " + t.episodeTitle; }

    /** 与直播频道列表一样：直接在全屏画面上点选缓存，不必先打开设置菜单。 */
    private void buildCacheOverlay() {
        cacheOverlay = new RowAdapter<DownloadTask>(0, 0, (h, t) -> {
            boolean selected = currentTask != null && currentTask.id.equals(t.id);
            h.title.setText((selected ? "▶ " : "") + cacheLabel(t));
            h.title.setTextColor(selected ? 0xFF81E6BC : 0xFFFFFFFF);
            h.subtitle.setText(t.format.label + (t.state == DownloadTask.State.DONE ? "" : " · 边存边播"));
            h.subtitle.setTextColor(0xCCFFFFFF);
            h.itemView.setBackgroundColor(selected ? 0x335AD9A5 : 0);
            h.itemView.setSelected(selected);
        }).onClick((t, i) -> {
            view.showController();
            selectCache(t.id);
        });
        RecyclerView items = new RecyclerView(this);
        items.setContentDescription("全屏缓存列表");
        cacheOverlayLayout = new LinearLayoutManager(this);
        items.setLayoutManager(cacheOverlayLayout);
        items.setAdapter(cacheOverlay);
        items.setItemAnimator(null);
        items.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (overlayShown && (r - l != or - ol || b - t != ob - ot))
                v.post(this::revealCacheOverlay);
        });
        items.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent event) {
                view.showController();
                return false;
            }
        });
        items.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) view.showController();
            }
        });
        cachePanel = Ui.column(this, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setColor(0xB3000000);
        cachePanel.setBackground(bg);
        cachePanel.setClickable(true);
        cachePanelTitle = Ui.text(this, "缓存列表", 14, true);
        cachePanelTitle.setTextColor(0xCCFFFFFF);
        cachePanelTitle.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 6));
        cachePanel.addView(cachePanelTitle);
        cachePanel.addView(items, Ui.fill(1));
        cachePanel.setVisibility(View.GONE);
        box.addView(cachePanel, new android.widget.FrameLayout.LayoutParams(0, 0));
        box.exempt(cachePanel);
        box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::updateCacheOverlay);
        });
    }

    private void updateCacheOverlay() {
        if (cachePanel == null) return;
        boolean show = layout.fullscreen() && layout.controlsVisible() && !episodes.isEmpty();
        int width = box.getWidth(), height = box.getHeight();
        boolean resized = overlayWidth != width || overlayHeight != height;
        overlayWidth = width;
        overlayHeight = height;
        if (show && width > 0 && height > 0) {
            int edge = Ui.dp(this, 12), top = Ui.dp(this, 64), bottom = Ui.dp(this, 84);
            boolean portrait = height > width;
            // 竖屏全屏时列表只占左上区域，为中间的播放/上下项按钮留出空间。
            int panelWidth = Math.min(Ui.dp(this, 260), (int) (width * (portrait ? 0.65f : 0.28f)));
            int panelHeight = portrait ? Math.max(0, height / 2 - top - Ui.dp(this, 64))
                    : android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    panelWidth, panelHeight, android.view.Gravity.TOP | android.view.Gravity.START);
            lp.setMargins(edge, top, 0, portrait ? 0 : bottom);
            cachePanel.setLayoutParams(lp);
        }
        cachePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && (!overlayShown || resized)) revealCacheOverlay();
        overlayShown = show;
    }

    private void revealCacheOverlay() {
        int first = cacheOverlayLayout.findFirstCompletelyVisibleItemPosition();
        int last = cacheOverlayLayout.findLastCompletelyVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || position < first || position > last)
            cacheOverlayLayout.scrollToPositionWithOffset(position, 0);
    }

    private void updateListTitle() {
        listTitle.setText("缓存列表 · " + (position + 1) + "/" + episodes.size() + " · 循环播放");
        if (cacheOverlay != null) {
            cachePanelTitle.setText("缓存列表 · " + (position + 1) + "/" + episodes.size());
            cacheOverlay.set(episodes);
            String id = currentTask == null ? "" : currentTask.id;
            boolean changed = !id.equals(overlayCurrentId);
            overlayCurrentId = id;
            updateCacheOverlay();
            if (changed && overlayShown) revealCacheOverlay();
        }
    }

    private void playerMenu() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("播放设置")
                .setItems(new String[]{"缓存列表", "弹幕设置"}, (d, which) -> {
                    if (which == 0) chooseCache();
                    else danmaku.showPanel();
                }).show();
    }

    private void chooseCache() {
        // 对话框使用快照，下载期间列表变化也不会选错任务。
        List<DownloadTask> choices = new ArrayList<>(episodes);
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) {
            DownloadTask t = choices.get(i);
            labels[i] = cacheLabel(t) + " · " + t.format.label;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("缓存列表")
                .setSingleChoiceItems(labels, OfflinePlaylist.indexOf(choices, currentTask.id), (d, which) -> {
                    selectCache(choices.get(which).id);
                    d.dismiss();
                }).show();
    }

    private void selectCache(String id) {
        int next = OfflinePlaylist.indexOf(episodes, id);
        if (next < 0 || (currentTask != null && currentTask.id.equals(id))) return;
        save();
        play(next, 0);
    }

    private void step(int delta) {
        int next = OfflinePlaylist.step(position, delta, episodes.size());
        if (next < 0) return;
        save();
        play(next, 0);
    }

    private MediaSource source(DownloadTask t) {
        if (t.state != DownloadTask.State.DONE) {
            return new HlsMediaSource.Factory(new SegmentDataSource.Factory(this, t.id))
                    .createMediaSource(new MediaItem.Builder().setUri(Stream.uri(t.id)).setMimeType(MimeTypes.APPLICATION_M3U8).build());
        }
        boolean encrypted = t.format == DownloadTask.Format.ENCRYPTED;
        Uri uri = encrypted || !t.output.startsWith("content://") ? Uri.fromFile(new File(t.output)) : Uri.parse(t.output);
        androidx.media3.datasource.DataSource.Factory files = encrypted ? new VaultDataSource.Factory() : new DefaultDataSource.Factory(this);
        // HLS 拼成的文件按分片的字节范围播放，时间戳不连续时时长和拖动也准确
        String playlist = Stream.layoutPlaylist(Stream.layoutFile(this, t.id), uri.toString());
        if (playlist != null) {
            return new HlsMediaSource.Factory(new PlaylistDataSource.Factory(playlist, files))
                    .createMediaSource(new MediaItem.Builder().setUri(PlaylistDataSource.PLAYLIST_URI).setMimeType(MimeTypes.APPLICATION_M3U8).build());
        }
        return new ProgressiveMediaSource.Factory(files).createMediaSource(MediaItem.fromUri(uri));
    }

    private void play(int i, long startMs) {
        DownloadTask t = episodes.get(i);
        String previous = streaming;
        if (t.state != DownloadTask.State.DONE && !Stream.available(this, t)) {
            Ui.toast(this, "该缓存暂时无法播放");
            onStoreChanged();
            return;
        }
        position = i;
        currentTask = t;
        // 跨影片切换时，播放记录与弹幕必须跟随新的影片元数据。
        try {
            video = Video.fromJson(new JSONObject(t.videoJson));
        } catch (Exception e) {
            video = null;
        }
        setTitle(t.title);
        title.setText(cacheLabel(t));
        streaming = t.state == DownloadTask.State.DONE ? null : t.id;
        if (streaming != null && !streaming.equals(previous)) Stream.acquire(streaming);
        status.setText(streaming != null ? streamStatus(t) : "离线播放 · " + t.format.label + " · " + t.container);
        badge.setText(streaming != null ? "边存边播" : "离线");
        list.notifyDataSetChanged();
        updateListTitle();
        int first = listLayout.findFirstCompletelyVisibleItemPosition();
        int last = listLayout.findLastCompletelyVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || position < first || position > last) listLayout.scrollToPositionWithOffset(position, 0);
        playState = "loading";
        danmaku.load(t.title, video == null ? "" : video.year, t.episode, DownloadTask.danmakuFile(this, t.id));
        player.setMediaSource(source(t));
        player.prepare();
        if (startMs > 0) player.seekTo(startMs);
        player.play();
        // 换到别的集或已切到最终文件，旧的分片可以清理了
        if (previous != null && !previous.equals(streaming)) Stream.release(this, previous);
        handler.removeCallbacks(saver);
        handler.postDelayed(saver, SAVE_INTERVAL_MS);
    }

    /** 有网络时同步播放进度；离线时静默失败。 */
    private void save() {
        if (player == null || video == null || currentTask == null) return;
        long pos = player.getCurrentPosition() / 1000, dur = Math.max(0, player.getDuration()) / 1000;
        if (pos < 1) return;
        Record r = Record.of(video);
        r.index = currentTask.episode + 1;
        r.playTime = pos;
        r.totalTime = dur;
        r.searchTitle = video.title;
        Tasks.run(() -> { DecoApp.get().api.savePlayRecord(r); return true; }, ok -> { }, e -> { });
    }

    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        layout.onConfigurationChanged(config);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && layout != null && layout.fullscreen()) Ui.immersive(this, true);
    }

    @Override protected void onPause() {
        super.onPause();
        if (gestures != null) gestures.cancel();
        save();
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (gestures != null) gestures.release();
        DecoApp.get().downloads.unlisten(storeListener);
        if (player != null) player.release();
        if (streaming != null) Stream.release(this, streaming);
    }
}
