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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
 * 播放离线缓存：与在线点播相同的小窗 / 全屏、手势、倍速。「选集」只列当前影片的分集，「全部缓存」按影片列出，
 * 与直播的频道 / 换源一样在全屏时也直接显示。控制栏的上一个 / 下一个和播完续播按连播范围（全部缓存或仅本片）循环。
 * 加密缓存边读边解密；没缓存完的 m3u8 边存边播，缓存完成后在当前进度无缝切到最终文件。
 * 进度在有网络时照常同步到播放记录。
 */
@OptIn(markerClass = UnstableApi.class)
public class OfflinePlayerActivity extends AppCompatActivity {
    private static final String EXTRA_ID = "id";
    private static final long SAVE_INTERVAL_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DownloadTask> queue = new ArrayList<>();
    private ExoPlayer player;
    private PlayerView view;
    private PlayerGestures.Frame box;
    private PlayerLayout layout;
    private PlayerGestures gestures;
    private TextView title, meta, status, badge;
    private final List<OfflinePlaylist.Title> titles = new ArrayList<>();
    private TextView episodeTab, titleTab, tabCount, prevTitle, nextTitle, scopeButton;
    private RecyclerView episodeList, titleList, panelEpisodeList, panelTitleList;
    private EpisodeAdapter episodeAdapter, panelEpisodeAdapter;
    private RowAdapter<OfflinePlaylist.Title> titleAdapter, panelTitleAdapter;
    private GridLayoutManager panelEpisodeLayout;
    private LinearLayout titlePanel, episodePanel;
    private TextView titlePanelHead, episodePanelHead, panelScopeButton;
    private boolean showingEpisodes, overlayShown, titleOnly;
    private int overlayWidth, overlayHeight;
    private DownloadTask currentTask;
    /** 正在边存边播的任务 id，没有为 null。 */
    private String streaming;
    private final DownloadStore.Listener storeListener = this::onStoreChanged;
    private TextView speedButton;
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
    public int cacheCount() { return queue.size(); }
    public int titleCount() { return titles.size(); }
    public boolean titleOnlyForTest() { return titleOnly; }

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
        queue.addAll(playableTasks());
        titles.addAll(OfflinePlaylist.titles(queue));
        titleOnly = DecoApp.get().prefs.offlineTitleOnly();
        showingEpisodes = OfflinePlaylist.sameTitle(queue, start.videoKey).size() > 1;
        position = OfflinePlaylist.indexOf(queue, start.id);
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
        // 选集和缓存列表直接显示在页面和全屏浮层上，倍速在右上角，齿轮只剩弹幕设置。
        view.findViewById(androidx.media3.ui.R.id.exo_settings).setOnClickListener(v -> danmaku.showPanel());
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

        View details = buildDetails();

        layout = new PlayerLayout(this, box, details);
        layout.setLeftButtonAboveList(true);
        speedButton = layout.addSpeedButton(DecoApp.get().prefs.playbackSpeed(), this::chooseSpeed, badge);
        buildOverlay();
        layout.setOnChange(this::updateOverlay);
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
                setStatus("播放失败：" + error.getMessage());
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
        queue.clear();
        queue.addAll(playableTasks());
        titles.clear();
        titles.addAll(OfflinePlaylist.titles(queue));
        if (queue.isEmpty()) {
            save();
            player.stop();
            Ui.toast(this, "没有可播放的缓存");
            finish();
            return;
        }
        position = OfflinePlaylist.indexOf(queue, currentTask.id);
        if (position < 0) {
            save();
            // 当前集被删：仅本片时留在本片，否则接着播队列里的下一项。
            List<DownloadTask> same = OfflinePlaylist.sameTitle(queue, currentTask.videoKey);
            int next = Math.min(oldPosition, queue.size() - 1);
            if (titleOnly && !same.isEmpty() && !queue.get(next).videoKey.equals(currentTask.videoKey))
                next = OfflinePlaylist.indexOf(queue, same.get(0).id);
            play(next, 0);
            return;
        }
        refreshLists(false);
        DownloadTask t = queue.get(position);
        if (streaming != null && streaming.equals(t.id)) {
            if (t.state == DownloadTask.State.DONE) {
                long at = player.getCurrentPosition();
                android.util.Log.i("DecoStream", "switch to file at=" + at);
                boolean wasPlaying = player.getPlayWhenReady();
                play(position, at);
                if (!wasPlaying) player.pause();
                Ui.toast(this, "缓存完成，已切换到本地文件");
            } else {
                setStatus(streamStatus(t));
            }
        }
    }

    private static String streamStatus(DownloadTask t) {
        return "边存边播 · 已缓存 " + Math.round(t.progress() * 100) + "% · 没缓存到的部分在线加载并存入缓存";
    }

    // ---------- 小窗下方 ----------

    private View buildDetails() {
        LinearLayout box = Ui.column(this, 0);
        box.setBackgroundColor(Style.bg(this));
        LinearLayout info = Ui.column(this, 0);
        info.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);
        title = Style.single(Style.text(this, "", 18, 700, Style.text(this)));
        info.addView(title);
        meta = Style.single(Style.text(this, "", 13, 400, Style.text2(this)));
        meta.setPadding(0, Ui.dp(this, 3), 0, 0);
        info.addView(meta);
        status = Style.text(this, "", 12, 400, Style.text3(this));
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(0, Ui.dp(this, 3), 0, 0);
        status.setVisibility(View.GONE);
        info.addView(status);
        box.addView(info);

        // 跳到其他影片和连播范围；上一集 / 下一集在播放控制栏里
        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), 0);
        prevTitle = action("上一部", "上一部缓存", v -> stepTitle(-1));
        nextTitle = action("下一部", "下一部缓存", v -> stepTitle(1));
        scopeButton = action("", "", v -> toggleScope());
        actions.addView(prevTitle);
        actions.addView(nextTitle);
        actions.addView(scopeButton);
        actions.addView(action("弹幕", "弹幕设置", v -> danmaku.showPanel()));
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(actions);
        box.addView(scroll);

        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        episodeTab = tab("选集");
        titleTab = tab("全部缓存");
        episodeTab.setOnClickListener(v -> { showTab(true); revealLists(); });
        titleTab.setOnClickListener(v -> { showTab(false); revealLists(); });
        tabs.addView(episodeTab);
        tabs.addView(titleTab);
        tabCount = Style.single(Style.text(this, "", 12, 400, Style.text3(this)));
        tabCount.setGravity(android.view.Gravity.END);
        tabs.addView(tabCount, Ui.weight(1));
        box.addView(tabs);

        episodeAdapter = new EpisodeAdapter();
        episodeList = new RecyclerView(this);
        episodeList.setContentDescription("本片选集");
        episodeList.setLayoutManager(new GridLayoutManager(this, Math.max(5, getResources().getConfiguration().screenWidthDp / 72)));
        episodeList.setAdapter(episodeAdapter);
        episodeList.setItemAnimator(null);
        episodeList.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        episodeList.setClipToPadding(false);
        box.addView(episodeList, Ui.fill(1));

        titleAdapter = new RowAdapter<OfflinePlaylist.Title>(36, 50, (h, t) -> {
            boolean selected = isCurrent(t);
            DecoApp.get().images.offlineCover(h.image, t.first());
            Style.single(h.title);
            h.title.setText(t.first().title);
            h.title.setTextColor(selected ? Style.accent(this) : Style.text(this));
            h.subtitle.setText(titleSummary(t, selected));
            h.itemView.setSelected(selected);
            h.itemView.setBackground(selected ? Style.stroke(this, Style.accentSoft(this), Style.accent(this), 1.5f, 14)
                    : Style.pressable(this, Style.surface(this), 14));
        }).cards().onClick((t, i) -> selectTitle(t));
        titleList = new RecyclerView(this);
        titleList.setContentDescription("全部缓存列表");
        titleList.setLayoutManager(new LinearLayoutManager(this));
        titleList.setAdapter(titleAdapter);
        titleList.setItemAnimator(null);
        titleList.setPadding(0, 0, 0, Ui.dp(this, 12));
        titleList.setClipToPadding(false);
        box.addView(titleList, Ui.fill(1));
        return box;
    }

    private TextView action(String text, String description, View.OnClickListener l) {
        TextView t = Style.pill(this, text, false);
        t.setContentDescription(description);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        lp.rightMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView tab(String text) {
        TextView t = Style.text(this, text, 16, 700, Style.text(this));
        t.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6));
        return t;
    }

    private void showTab(boolean episodes) {
        showingEpisodes = episodes;
        styleTab(episodeTab, episodes);
        styleTab(titleTab, !episodes);
        episodeList.setVisibility(episodes ? View.VISIBLE : View.GONE);
        titleList.setVisibility(episodes ? View.GONE : View.VISIBLE);
        updateTabCount();
    }

    private void styleTab(TextView t, boolean selected) {
        t.setTextSize(selected ? 17 : 15);
        Style.weight(t, selected ? 700 : 400);
        t.setTextColor(selected ? Style.text(this) : Style.text3(this));
    }

    private void updateTabCount() {
        List<DownloadTask> same = currentEpisodes();
        if (showingEpisodes) {
            int total = currentTask == null || video == null ? 0 : video.episodes.size();
            tabCount.setText(total > same.size() ? "已缓存 " + same.size() + " / " + total + " 集" : "共 " + same.size() + " 集");
        } else {
            tabCount.setText(titles.size() + " 部 · " + queue.size() + " 个缓存");
        }
    }

    private List<DownloadTask> currentEpisodes() {
        return currentTask == null ? new ArrayList<>() : OfflinePlaylist.sameTitle(queue, currentTask.videoKey);
    }

    private boolean isCurrent(OfflinePlaylist.Title t) { return currentTask != null && t.key.equals(currentTask.videoKey); }

    private String titleSummary(OfflinePlaylist.Title t, boolean selected) {
        DownloadTask first = t.first();
        String count = t.tasks.size() > 1 ? "已缓存 " + t.tasks.size() + " 集" : first.format.label;
        return (selected ? "正在播放 · " : "") + count + (first.sourceName == null || first.sourceName.isEmpty() ? "" : " · " + first.sourceName);
    }

    /** 选集方格：与点播页相同，当前集强调色实心，之前的集变淡；只列已缓存的分集，文字是真实集号。 */
    private final class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final boolean overlay;
        private final List<DownloadTask> items = new ArrayList<>();

        EpisodeAdapter() { this(false); }

        EpisodeAdapter(boolean overlay) { this.overlay = overlay; }

        void set(List<DownloadTask> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            TextView t = Style.single(Style.text(parent.getContext(), "", overlay ? 14 : 15, 500, Style.text(parent.getContext())));
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(Ui.dp(parent.getContext(), 4), 0, Ui.dp(parent.getContext(), 4), 0);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, Ui.dp(parent.getContext(), overlay ? 38 : 44));
            int m = Ui.dp(parent.getContext(), overlay ? 3 : 4);
            lp.setMargins(m, m, m, m);
            t.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(t) { };
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int i) {
            DownloadTask task = items.get(i);
            TextView t = (TextView) h.itemView;
            t.setText(OfflinePlaylist.episodeLabel(task));
            boolean current = currentTask != null && task.id.equals(currentTask.id);
            int currentIndex = currentTask == null ? -1 : OfflinePlaylist.indexOf(items, currentTask.id);
            int normal = overlay ? 0xFFFFFFFF : Style.text(OfflinePlayerActivity.this);
            int watched = overlay ? 0x99FFFFFF : Style.text3(OfflinePlayerActivity.this);
            t.setTextColor(current ? Style.onAccent(OfflinePlayerActivity.this) : i < currentIndex ? watched : normal);
            Style.weight(t, current ? 700 : 500);
            int fill = overlay ? 0x26FFFFFF : Style.surface2(OfflinePlayerActivity.this);
            t.setBackground(Style.pressable(OfflinePlayerActivity.this, current ? Style.accent(OfflinePlayerActivity.this) : fill, 10));
            t.setSelected(current);
            t.setContentDescription(task.episodeTitle);
            t.setOnClickListener(v -> {
                if (overlay) view.showController();
                selectCache(task.id);
            });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private Player controls(ExoPlayer exo) {
        return new ForwardingPlayer(exo) {
            @Override public boolean isCommandAvailable(int c) {
                if (c == COMMAND_SEEK_TO_NEXT || c == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                        || c == COMMAND_SEEK_TO_PREVIOUS || c == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) return !queue.isEmpty();
                return super.isCommandAvailable(c);
            }

            @NonNull @Override public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .addIf(COMMAND_SEEK_TO_NEXT, !queue.isEmpty()).addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, !queue.isEmpty())
                        .addIf(COMMAND_SEEK_TO_PREVIOUS, !queue.isEmpty()).addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, !queue.isEmpty()).build();
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

    // ---------- 全屏浮层 ----------

    /** 与直播全屏的频道 / 换源一样：控制栏显示时，左边全部缓存、右边本片选集，直接点选。 */
    private void buildOverlay() {
        panelTitleAdapter = new RowAdapter<OfflinePlaylist.Title>(30, 42, (h, t) -> {
            boolean selected = isCurrent(t);
            DecoApp.get().images.offlineCover(h.image, t.first());
            Style.single(h.title);
            h.title.setText(t.first().title);
            h.title.setTextColor(0xFFFFFFFF);
            h.title.setTextSize(14);
            Style.weight(h.title, selected ? 700 : 500);
            h.subtitle.setText(t.tasks.size() > 1 ? t.tasks.size() + " 集" : t.first().format.label);
            h.subtitle.setTextColor(0xB3FFFFFF);
            Style.single(h.subtitle);
            int p = Ui.dp(this, 8);
            h.itemView.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 6));
            h.itemView.setBackground(Style.pressable(this, selected ? 0x33FFFFFF : 0, 10));
            h.itemView.setSelected(selected);
        }).onClick((t, i) -> {
            view.showController();
            selectTitle(t);
        });
        panelTitleList = panelList("全屏缓存列表");
        panelTitleList.setLayoutManager(new LinearLayoutManager(this));
        panelTitleList.setAdapter(panelTitleAdapter);
        titlePanelHead = panelHead();
        titlePanel = panel(titlePanelHead, null, panelTitleList);

        panelEpisodeAdapter = new EpisodeAdapter(true);
        panelEpisodeList = panelList("全屏选集");
        panelEpisodeLayout = new GridLayoutManager(this, 3);
        panelEpisodeList.setLayoutManager(panelEpisodeLayout);
        panelEpisodeList.setAdapter(panelEpisodeAdapter);
        int gridPad = Ui.dp(this, 5);
        panelEpisodeList.setPadding(gridPad, 0, gridPad, gridPad);
        panelEpisodeList.setClipToPadding(false);
        episodePanelHead = panelHead();
        panelScopeButton = Style.single(Ui.text(this, "", 12, false));
        panelScopeButton.setGravity(android.view.Gravity.CENTER);
        panelScopeButton.setMinHeight(Ui.dp(this, 32));
        panelScopeButton.setOnClickListener(v -> {
            view.showController();
            toggleScope();
        });
        episodePanel = panel(episodePanelHead, panelScopeButton, panelEpisodeList);

        box.addView(titlePanel, new android.widget.FrameLayout.LayoutParams(0, 0));
        box.addView(episodePanel, new android.widget.FrameLayout.LayoutParams(0, 0));
        box.exempt(titlePanel);
        box.exempt(episodePanel);
        box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(this::updateOverlay);
        });
    }

    private RecyclerView panelList(String description) {
        RecyclerView items = new RecyclerView(this);
        items.setContentDescription(description);
        items.setItemAnimator(null);
        // 浏览列表时保持控制栏显示，免得滑着滑着浮层消失
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
        return items;
    }

    private TextView panelHead() {
        TextView head = Style.single(Ui.text(this, "", 14, true));
        head.setTextColor(0xCCFFFFFF);
        head.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 6));
        return head;
    }

    private LinearLayout panel(TextView head, View extra, RecyclerView body) {
        LinearLayout panel = Ui.column(this, 0);
        panel.setBackground(Style.round(this, 0xB3000000, 12));
        panel.setClickable(true);
        panel.addView(head);
        if (extra != null) {
            LinearLayout.LayoutParams lp = Style.margins(this, 8, 0, 8, 6);
            panel.addView(extra, lp);
        }
        panel.addView(body, Ui.fill(1));
        panel.setVisibility(View.GONE);
        return panel;
    }

    /** 全屏且控制栏显示时才显示；横屏分列两侧避开中间的播放按钮，竖屏并排放在播放按钮下方。 */
    private void updateOverlay() {
        if (titlePanel == null) return;
        boolean show = layout.fullscreen() && layout.controlsVisible() && !queue.isEmpty();
        boolean episodes = show && currentEpisodes().size() > 1;
        int width = box.getWidth(), height = box.getHeight();
        boolean resized = overlayWidth != width || overlayHeight != height;
        overlayWidth = width;
        overlayHeight = height;
        if (show && width > 0 && height > 0) {
            int edge = Ui.dp(this, 12);
            android.widget.FrameLayout.LayoutParams left, right;
            if (height > width) {
                int top = height / 2 + Ui.dp(this, 48), bottom = Ui.dp(this, 104);
                int panelHeight = Math.max(0, height - top - bottom);
                int half = (width - edge * 3) / 2;
                left = new android.widget.FrameLayout.LayoutParams(episodes ? half : width - edge * 2, panelHeight, android.view.Gravity.TOP | android.view.Gravity.START);
                left.setMargins(edge, top, 0, 0);
                right = new android.widget.FrameLayout.LayoutParams(half, panelHeight, android.view.Gravity.TOP | android.view.Gravity.END);
                right.setMargins(0, top, edge, 0);
            } else {
                // 中间一排播放按钮（含点击区域）约 300dp 宽；手机右上方有旋转按钮，选集面板从它下方开始。
                int controls = Ui.dp(this, 156), top = Ui.dp(this, 64), bottom = Ui.dp(this, 84);
                int rightTop = PlayerLayout.tablet(this) ? top : height / 2 - Ui.dp(this, 68);
                left = new android.widget.FrameLayout.LayoutParams(Math.min(Ui.dp(this, 260), width / 2 - controls - edge),
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.START);
                left.setMargins(edge, top, 0, bottom);
                right = new android.widget.FrameLayout.LayoutParams(Math.min(Ui.dp(this, 260), width / 2 - controls - edge),
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.END);
                right.setMargins(0, rightTop, edge, bottom);
            }
            titlePanel.setLayoutParams(left);
            episodePanel.setLayoutParams(right);
            panelEpisodeLayout.setSpanCount(Math.max(3, (right.width - Ui.dp(this, 10)) / Ui.dp(this, 48)));
        }
        titlePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        episodePanel.setVisibility(episodes ? View.VISIBLE : View.GONE);
        if (show && (!overlayShown || resized)) revealLists();
        overlayShown = show;
    }

    /** 选集、缓存列表把当前项滚到可见范围。 */
    private void revealLists() {
        if (currentTask == null) return;
        int episode = OfflinePlaylist.indexOf(currentEpisodes(), currentTask.id);
        int movie = OfflinePlaylist.indexOfTitle(titles, currentTask.videoKey);
        if (episode >= 0) {
            episodeList.scrollToPosition(episode);
            if (panelEpisodeList != null) panelEpisodeList.scrollToPosition(episode);
        }
        if (movie >= 0) {
            titleList.scrollToPosition(movie);
            if (panelTitleList != null) panelTitleList.scrollToPosition(movie);
        }
    }

    /** 播放项或缓存变化后同步所有列表和按钮；moved 表示换了片，要把新的当前项滚出来。 */
    private void refreshLists(boolean moved) {
        List<DownloadTask> same = currentEpisodes();
        boolean series = same.size() > 1;
        episodeAdapter.set(same);
        titleAdapter.set(titles);
        episodeTab.setVisibility(series ? View.VISIBLE : View.GONE);
        if (!series) showingEpisodes = false;
        boolean manyTitles = titles.size() > 1;
        prevTitle.setEnabled(manyTitles);
        nextTitle.setEnabled(manyTitles);
        prevTitle.setAlpha(manyTitles ? 1f : 0.5f);
        nextTitle.setAlpha(manyTitles ? 1f : 0.5f);
        String scope = titleOnly ? "连播：仅本片" : "连播：全部缓存";
        scopeButton.setText(scope);
        scopeButton.setContentDescription(scope);
        Style.pill(scopeButton, titleOnly);
        scopeButton.setVisibility(series ? View.VISIBLE : View.GONE);
        showTab(showingEpisodes);
        if (panelTitleAdapter != null) {
            panelTitleAdapter.set(titles);
            panelEpisodeAdapter.set(same);
            titlePanelHead.setText("全部缓存 · " + titles.size() + " 部");
            episodePanelHead.setText("选集 · " + (OfflinePlaylist.indexOf(same, currentTask.id) + 1) + "/" + same.size());
            panelScopeButton.setText(scope);
            panelScopeButton.setContentDescription(scope);
            panelScopeButton.setTextColor(titleOnly ? 0xFF06140E : 0xFFFFFFFF);
            panelScopeButton.setBackground(Style.pressable(this, titleOnly ? 0xFF3DDB9A : 0x26FFFFFF, 8));
            updateOverlay();
        }
        if (moved) revealLists();
    }

    // ---------- 切换 ----------

    private void toggleScope() {
        titleOnly = !titleOnly;
        DecoApp.get().prefs.setOfflineTitleOnly(titleOnly);
        refreshLists(false);
    }

    private void selectCache(String id) {
        int next = OfflinePlaylist.indexOf(queue, id);
        if (next < 0 || (currentTask != null && currentTask.id.equals(id))) return;
        save();
        play(next, 0);
    }

    /** 点影片：已在播放就不打断，否则从它最前面的已缓存分集开始。 */
    private void selectTitle(OfflinePlaylist.Title t) {
        if (!isCurrent(t)) selectCache(t.first().id);
    }

    /** 上一部 / 下一部：跳到其他影片，首尾循环。 */
    private void stepTitle(int delta) {
        int next = OfflinePlaylist.step(OfflinePlaylist.indexOfTitle(titles, currentTask.videoKey), delta, titles.size());
        if (next >= 0) selectTitle(titles.get(next));
    }

    /** 上一个 / 下一个和播完续播：按连播范围循环；全部缓存时到片尾接着切到其他影片。 */
    private void step(int delta) {
        List<DownloadTask> same = currentEpisodes();
        List<DownloadTask> scope = titleOnly && same.size() > 1 ? same : queue;
        int next = OfflinePlaylist.step(OfflinePlaylist.indexOf(scope, currentTask.id), delta, scope.size());
        if (next < 0) return;
        save();
        play(OfflinePlaylist.indexOf(queue, scope.get(next).id), 0);
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

    private static String joinMeta(DownloadTask t) {
        StringBuilder b = new StringBuilder(t.episodeTitle == null ? "" : t.episodeTitle);
        if (t.sourceName != null && !t.sourceName.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(t.sourceName);
        if (t.state == DownloadTask.State.DONE) b.append(b.length() > 0 ? " · " : "").append(t.format.label);
        return b.toString();
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void play(int i, long startMs) {
        DownloadTask t = queue.get(i);
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
        title.setText(t.title);
        meta.setText(joinMeta(t));
        streaming = t.state == DownloadTask.State.DONE ? null : t.id;
        if (streaming != null && !streaming.equals(previous)) Stream.acquire(streaming);
        setStatus(streaming != null ? streamStatus(t) : "");
        badge.setText(streaming != null ? "边存边播" : "离线");
        refreshLists(true);
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
