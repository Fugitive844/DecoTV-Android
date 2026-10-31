package io.github.fugitive844.decotv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.R;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.NetLog;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.net.Tasks;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 点播页：与网页端一致，上方小窗播放，下方是简介、选集与换源；全屏由用户点按钮或转动手机进入。
 * 默认直连视频源；智能模式在直连出错或起播超时后改用服务端中转，并从原进度续播。
 * 每 5 秒以及暂停、退出、切集、换源时上报播放记录。
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {
    private static final String EXTRA_VIDEO = "video", EXTRA_INDEX = "index", EXTRA_START = "start";
    private static final long STARTUP_TIMEOUT_MS = 12000, RELAY_STARTUP_TIMEOUT_MS = 25000, SAVE_INTERVAL_MS = 5000;
    private static final int EPISODES_PER_PAGE = 50;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Video video;
    private String searchTitle;
    private ExoPlayer player;
    private PlayerView view;
    private PlayerGestures.Frame playerBox;
    private View details;
    private TextView title, meta, status, desc;
    private Action favButton, speedButton;
    private PlayerLayout layout;
    private PlayerGestures gestures;
    private LinearLayout pages;
    private RecyclerView episodeList, sourceList;
    private EpisodeAdapter episodes;
    private RowAdapter<Video> sources;
    private TextView sourceHint;
    private final List<Video> alternatives = new ArrayList<>();
    private int index, page, attempt;
    private Media current;
    private ApiClient.Resolved resolved;
    private boolean ready, favorite;
    private volatile String playState = "loading";
    private TextView route;
    private BufferSpeed speed;
    private DanmakuController danmaku;

    /** 播放状态，供联调测试读取：loading、ready:direct|relay:主机、failed。 */
    public String playbackState() { return playState; }

    /** 当前是否全屏，供联调测试读取。 */
    public boolean isFullscreen() { return layout != null && layout.fullscreen(); }

    /** 当前播放位置与播放器状态，供联调测试读取。 */
    /** 弹幕状态与条数，供联调测试读取。 */
    public String danmakuInfo() { return danmaku == null ? "none" : danmaku.state() + " count=" + danmaku.count(); }

    public String positionInfo() { return player == null ? "none" : player.getCurrentPosition() + "ms state=" + player.getPlaybackState(); }

    static void open(Context c, Video v, int index, long startSeconds) {
        if (Opening.launchNow()) c.startActivity(intent(c, v, index, startSeconds));
    }

    public static Intent intent(Context c, Video v, int index, long startSeconds) {
        return new Intent(c, PlayerActivity.class).putExtra(EXTRA_VIDEO, v.toJson().toString())
                .putExtra(EXTRA_INDEX, index).putExtra(EXTRA_START, startSeconds);
    }

    /** 从搜索结果进入：与网页端一致，若已有播放记录则从记录的集数与进度续播。 */
    static void open(Activity activity, Video v, String searchTitle) {
        Opening o = Opening.begin(activity, "正在打开「" + v.title + "」…");
        if (o != null) open(o, v, searchTitle);
    }

    /** 在已有的等待框里继续：查播放记录后跳转。 */
    static void open(Opening o, Video v, String searchTitle) {
        o.message("正在打开「" + v.title + "」…");
        Tasks.run(() -> findRecord(v.key()), o.then(r -> start(o, v, r, searchTitle)), o.orElse(e -> start(o, v, null, searchTitle)));
    }

    /**
     * 从播放记录或收藏进入：与网页端一致，先按搜索标题搜索并取同源同 id 的结果，找不到再取详情，
     * 然后按播放记录续播。部分源（如 csp_ 开头的）详情接口不返回集数，只能靠搜索结果。
     */
    static void open(Activity activity, Record entry) {
        openById(activity, entry.source(), entry.id(), entry.title, entry.searchTitle, entry.playTime > 0 ? entry : null);
    }

    /** 已知源和 id（播放记录、收藏、源浏览器）：与网页端 /play?source=&id=&title= 相同。 */
    static void openById(Activity activity, String source, String id, String title, String searchTitle, Record known) {
        Opening o = Opening.begin(activity, "正在打开「" + title + "」…");
        if (o == null) return;
        String key = source + "+" + id;
        String query = searchTitle == null || searchTitle.isEmpty() ? title : searchTitle;
        Tasks.run(() -> {
            Video v = null;
            try {
                for (Video x : DecoApp.get().api.searchSource(query, source)) {
                    if (x.key().equals(key) && !x.episodes.isEmpty()) {
                        v = x;
                        break;
                    }
                }
            } catch (ApiClient.AuthException e) {
                throw e;
            } catch (java.io.IOException e) {
                // 搜索失败时退回详情接口
            }
            if (v == null) v = DecoApp.get().api.detail(source, id);
            if (v.title.isEmpty()) v.title = title;
            Record r = known != null ? known : findRecord(key);
            return new Object[]{v, r};
        }, o.then(pair -> start(o, (Video) pair[0], (Record) pair[1], query)), o.orElse(e -> {
            o.end(false);
            Ui.fail(activity, e);
        }));
    }

    private static Record findRecord(String key) throws java.io.IOException {
        for (Record r : DecoApp.get().api.playRecords()) if (r.key.equals(key)) return r;
        return null;
    }

    private static void start(Opening o, Video v, Record r, String searchTitle) {
        Activity activity = o.activity;
        if (v.episodes.isEmpty()) {
            o.end(false);
            Ui.toast(activity, "该影片没有可播放的剧集");
            return;
        }
        o.end(true);
        int i = r == null ? 0 : Math.max(0, Math.min(v.episodes.size() - 1, r.index - 1));
        long at = r == null ? 0 : r.playTime;
        activity.startActivity(intent(activity, v, i, at).putExtra("search_title", searchTitle));
    }

    private final Runnable saver = new Runnable() {
        @Override public void run() {
            save();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private long startedAt;
    private final Runnable startupTimeout = new Runnable() {
        @Override public void run() {
            if (ready) return;
            // 超时但缓冲网速还够：说明还在正常下载，继续等，过几秒再看
            if (speed.keepWaiting(android.os.SystemClock.elapsedRealtime() - startedAt)) {
                status.setText("加载较慢（" + BufferSpeed.format(speed.bytesPerSecond()) + "），继续等待…");
                handler.postDelayed(this, BufferSpeed.RECHECK_MS);
                return;
            }
            onPlaybackFailed(new PlaybackException("起播超时", null, PlaybackException.ERROR_CODE_TIMEOUT));
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            video = Video.fromJson(new JSONObject(getIntent().getStringExtra(EXTRA_VIDEO)));
        } catch (JSONException | NullPointerException e) {
            finish();
            return;
        }
        searchTitle = getIntent().getStringExtra("search_title");
        if (searchTitle == null || searchTitle.isEmpty()) searchTitle = video.title;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerBox = new PlayerGestures.Frame(this);
        view = new PlayerView(this);
        Ui.stylePlayer(view);
        player = new ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        view.setPlayer(episodeControls(player));
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setFullscreenButtonClickListener(ignored -> layout.toggle());
        // 齿轮按钮提供换源和倍速，全屏时不必退出即可切换
        view.findViewById(androidx.media3.ui.R.id.exo_settings).setOnClickListener(v -> playerMenu());
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (layout != null) layout.setControlsVisible(visibility == View.VISIBLE);
        });
        playerBox.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        route = Ui.routeBadge(this);
        playerBox.addView(route, Ui.badgeParams(this));
        speed = new BufferSpeed(this);
        playerBox.addView(speed.view(), speed.params(this));
        details = buildDetails();
        layout = new PlayerLayout(this, playerBox, details);
        setContentView(layout.view());
        danmaku = new DanmakuController(this, view, layout, player);
        gestures = new PlayerGestures(this, playerBox, new PlayerGestures.Host() {
            @Override public boolean seekable() { return ready && player.isCurrentMediaItemSeekable(); }
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
            @Override public void onPlaybackStateChanged(int playbackState) {
                speed.update(playbackState == Player.STATE_BUFFERING);
                if (playbackState == Player.STATE_READY && !ready && current != null) {
                    ready = true;
                    handler.removeCallbacks(startupTimeout);
                    // 与网页端一致：续播点离片尾不足 2 秒时退到片尾前 5 秒
                    long duration = player.getDuration();
                    if (duration > 10000 && player.getCurrentPosition() >= duration - 2000) player.seekTo(duration - 5000);
                    NetLog.use(Relay.Item.VOD, current.relayed, current.url);
                    playState = "ready:" + (current.relayed ? "relay" : "direct") + ":" + MediaUrls.host(current.url);
                    Ui.setRoute(route, current.relayed);
                    status.setText(current.relayed ? "经服务端中转播放" : "直连播放：" + MediaUrls.host(current.url));
                } else if (playbackState == Player.STATE_ENDED) {
                    save();
                    if (index + 1 < video.episodes.size()) play(index + 1, 0);
                }
            }

            @Override public void onPlayerError(@NonNull PlaybackException error) { onPlaybackFailed(error); }

            @Override public void onPlaybackParametersChanged(@NonNull androidx.media3.common.PlaybackParameters params) {
                // 长按临时 2 倍速不改按钮；其余倍速在本页内跨集、跨源保留，下次打开恢复为设置里的默认倍速
                if (!holding) speedButton.setValue(speedLabel(params.speed));
            }

            @Override public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize size) {
                layout.setVideoSize(size.width, size.height, size.pixelWidthHeightRatio);
            }
        });
        player.setPlaybackSpeed(DecoApp.get().prefs.playbackSpeed());
        showVideo();
        play(getIntent().getIntExtra(EXTRA_INDEX, 0), getIntent().getLongExtra(EXTRA_START, 0) * 1000);
        loadFavorite();
        loadAlternatives();
    }

    // ---------- 界面 ----------

    private View buildDetails() {
        LinearLayout box = Ui.column(this, 0);
        box.setBackgroundColor(Style.bg(this));
        LinearLayout info = Ui.column(this, 0);
        info.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), 0);
        title = Style.text(this, "", 20, 700, Style.text(this));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);
        meta = Style.single(Style.text(this, "", 13, 400, Style.text2(this)));
        meta.setPadding(0, Ui.dp(this, 5), 0, 0);
        info.addView(meta);
        status = Style.single(Style.text(this, "", 12, 400, Style.text3(this)));
        status.setPadding(0, Ui.dp(this, 3), 0, 0);
        info.addView(status);
        desc = Style.text(this, "", 13, 400, Style.text2(this));
        desc.setLineSpacing(Ui.dp(this, 3), 1f);
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        desc.setPadding(0, Ui.dp(this, 8), 0, 0);
        desc.setOnClickListener(v -> desc.setMaxLines(desc.getMaxLines() == 2 ? 200 : 2));
        info.addView(desc);
        box.addView(info);

        // 操作栏：图标 + 文字，等分一行；上一集 / 下一集在播放控制栏里也有
        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 4));
        favButton = new Action(R.drawable.ic_heart, "收藏", v -> toggleFavorite());
        favButton.setEnabled(false);
        actions.addView(favButton, Ui.weight(1));
        actions.addView(new Action(R.drawable.ic_download, "缓存", v -> DownloadDialog.show(this, video, index)), Ui.weight(1));
        actions.addView(new Action(R.drawable.ic_danmaku, "弹幕", v -> danmaku.showPanel()), Ui.weight(1));
        speedButton = new Action(0, "倍速", v -> chooseSpeed());
        speedButton.setValue(speedLabel(DecoApp.get().prefs.playbackSpeed()));
        speedButton.setContentDescription("播放倍速");
        actions.addView(speedButton, Ui.weight(1));
        actions.addView(new Action(R.drawable.ic_skip_prev, "上一集", v -> step(-1)), Ui.weight(1));
        actions.addView(new Action(R.drawable.ic_skip_next, "下一集", v -> step(1)), Ui.weight(1));
        box.addView(actions);

        // 选集 / 换源两个标签
        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 6));
        TextView episodeTab = tab("选集"), sourceTab = tab("换源");
        tabs.addView(episodeTab);
        tabs.addView(sourceTab);
        tabCount = Style.single(Style.text(this, "", 12, 400, Style.text3(this)));
        tabCount.setGravity(android.view.Gravity.END);
        tabs.addView(tabCount, Ui.weight(1));
        box.addView(tabs);

        HorizontalScrollView pageScroll = new HorizontalScrollView(this);
        pageScroll.setHorizontalScrollBarEnabled(false);
        pages = Ui.row(this);
        pages.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 6));
        pageScroll.addView(pages);
        box.addView(pageScroll);
        episodes = new EpisodeAdapter();
        episodeList = new RecyclerView(this);
        int columns = Math.max(5, getResources().getConfiguration().screenWidthDp / 72);
        episodeList.setLayoutManager(new GridLayoutManager(this, columns));
        episodeList.setAdapter(episodes);
        episodeList.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        episodeList.setClipToPadding(false);
        box.addView(episodeList, Ui.fill(1));

        sourceHint = Style.text(this, "正在查找其他播放源…", 13, 400, Style.text2(this));
        sourceHint.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), 0);
        sourceHint.setVisibility(View.GONE);
        box.addView(sourceHint);
        sources = new RowAdapter<Video>(0, 0, (h, v) -> {
            boolean selected = v.key().equals(video.key());
            h.title.setText(v.sourceName);
            h.title.setTextColor(selected ? Style.accent(this) : Style.text(this));
            h.subtitle.setText((selected ? "当前播放 · " : "") + v.episodes.size() + " 集" + (v.year.isEmpty() ? "" : " · " + v.year)
                    + (v.remarks.isEmpty() ? "" : " · " + v.remarks));
            h.itemView.setBackground(selected ? Style.stroke(this, Style.accentSoft(this), Style.accent(this), 1.5f, 14)
                    : Style.pressable(this, Style.surface(this), 14));
        }).cards().onClick((v, pos) -> switchSource(v));
        sourceList = new RecyclerView(this);
        sourceList.setLayoutManager(new LinearLayoutManager(this));
        sourceList.setAdapter(sources);
        sourceList.setVisibility(View.GONE);
        sourceList.setPadding(0, 0, 0, Ui.dp(this, 16));
        sourceList.setClipToPadding(false);
        box.addView(sourceList, Ui.fill(1));

        Runnable[] select = new Runnable[2];
        select[0] = () -> {
            styleTab(episodeTab, true);
            styleTab(sourceTab, false);
            pageScroll.setVisibility(View.VISIBLE);
            episodeList.setVisibility(View.VISIBLE);
            sourceList.setVisibility(View.GONE);
            sourceHint.setVisibility(View.GONE);
            showingSources = false;
            updateTabCount();
        };
        select[1] = () -> {
            styleTab(episodeTab, false);
            styleTab(sourceTab, true);
            pageScroll.setVisibility(View.GONE);
            episodeList.setVisibility(View.GONE);
            sourceList.setVisibility(View.VISIBLE);
            sourceHint.setVisibility(alternatives.size() > 1 ? View.GONE : View.VISIBLE);
            showingSources = true;
            updateTabCount();
        };
        episodeTab.setOnClickListener(v -> select[0].run());
        sourceTab.setOnClickListener(v -> select[1].run());
        // 与网页端一致：只有一集（电影）时隐藏选集，直接显示换源
        if (video.episodes.size() <= 1) {
            episodeTab.setVisibility(View.GONE);
            select[1].run();
        } else {
            select[0].run();
        }
        return box;
    }

    private TextView tabCount;
    private boolean showingSources;

    private void updateTabCount() {
        if (tabCount == null) return;
        tabCount.setText(showingSources ? (alternativesLoaded ? "找到 " + alternatives.size() + " 个" : "查找中…") : "共 " + video.episodes.size() + " 集");
    }

    private TextView tab(String text) {
        TextView t = Style.text(this, text, 17, 700, Style.text(this));
        t.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6));
        return t;
    }

    private void styleTab(TextView t, boolean selected) {
        t.setTextSize(selected ? 18 : 16);
        Style.weight(t, selected ? 700 : 400);
        t.setTextColor(selected ? Style.text(this) : Style.text3(this));
    }

    /** 操作栏的一个按钮：上面圆角图标（或倍速文字），下面名称。 */
    private final class Action extends LinearLayout {
        private final android.widget.ImageView icon;
        private final TextView value, label;

        Action(int iconRes, String text, View.OnClickListener l) {
            super(PlayerActivity.this);
            setOrientation(VERTICAL);
            setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            setPadding(0, Ui.dp(getContext(), 4), 0, Ui.dp(getContext(), 4));
            setBackground(Style.pressable(getContext(), 0, 14));
            setOnClickListener(l);
            setContentDescription(text);
            android.widget.FrameLayout well = new android.widget.FrameLayout(getContext());
            well.setBackground(Style.round(getContext(), Style.surface2(getContext()), 14));
            icon = Style.icon(getContext(), iconRes == 0 ? R.drawable.ic_speed : iconRes, Style.text(getContext()), 22);
            well.addView(icon, new android.widget.FrameLayout.LayoutParams(Ui.dp(getContext(), 22), Ui.dp(getContext(), 22), android.view.Gravity.CENTER));
            value = Style.text(getContext(), "", 13, 700, Style.text(getContext()));
            value.setGravity(android.view.Gravity.CENTER);
            value.setVisibility(GONE);
            well.addView(value, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            addView(well, new LayoutParams(Ui.dp(getContext(), 44), Ui.dp(getContext(), 44)));
            label = Style.single(Style.text(getContext(), text, 12, 400, Style.text2(getContext())));
            label.setGravity(android.view.Gravity.CENTER);
            label.setPadding(0, Ui.dp(getContext(), 6), 0, 0);
            addView(label);
        }

        /** 图标位置改成文字，如倍速「1.5x」。 */
        void setValue(String text) {
            value.setText(text);
            value.setVisibility(VISIBLE);
            icon.setVisibility(GONE);
        }

        void setLabel(String text) {
            label.setText(text);
            setContentDescription(text);
        }

        void setActive(boolean on, int iconRes) {
            icon.setImageResource(iconRes);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(on ? Style.accent(getContext()) : Style.text(getContext())));
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.5f);
        }
    }

    /** 选集方格：当前集强调色实心，看过的集变淡。 */
    private final class EpisodeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Integer> items = new ArrayList<>();

        void set(List<Integer> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            TextView t = Style.single(Style.text(parent.getContext(), "", 15, 500, Style.text(parent.getContext())));
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(Ui.dp(parent.getContext(), 4), 0, Ui.dp(parent.getContext(), 4), 0);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, Ui.dp(parent.getContext(), 44));
            int m = Ui.dp(parent.getContext(), 4);
            lp.setMargins(m, m, m, m);
            t.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(t) { };
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            int i = items.get(position);
            TextView t = (TextView) h.itemView;
            t.setText(episodeLabel(i));
            boolean current = i == index;
            t.setTextColor(current ? Style.onAccent(PlayerActivity.this) : i < index ? Style.text3(PlayerActivity.this) : Style.text(PlayerActivity.this));
            Style.weight(t, current ? 700 : 500);
            t.setBackground(Style.pressable(PlayerActivity.this, current ? Style.accent(PlayerActivity.this) : Style.surface2(PlayerActivity.this), 10));
            t.setContentDescription("第 " + (i + 1) + " 集");
            t.setOnClickListener(v -> {
                if (i != index) {
                    save();
                    play(i, 0);
                }
            });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final java.util.regex.Pattern EPISODE_NUMBER = java.util.regex.Pattern.compile("^(?:第)?(\\d+)(?:集|话)$");

    /** 选集按钮：标题形如「第12集」时只显示数字，否则显示原标题。 */
    private String episodeLabel(int i) {
        String t = video.episodeTitles.get(i).trim();
        java.util.regex.Matcher m = EPISODE_NUMBER.matcher(t);
        if (m.matches()) return String.valueOf(Integer.parseInt(m.group(1)));
        return t.isEmpty() ? String.valueOf(i + 1) : t;
    }

    private void showVideo() {
        setTitle(video.title);
        meta.setText(video.sourceName + (video.year.isEmpty() ? "" : " · " + video.year)
                + (video.typeName.isEmpty() ? "" : " · " + video.typeName) + " · 共 " + video.episodes.size() + " 集");
        String text = video.desc.replaceAll("<[^>]+>", "").trim();
        desc.setText(text);
        desc.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < video.episodes.size(); i++) all.add(i);
        episodeItems = all;
        renderPages();
        sources.notifyDataSetChanged();
    }

    private List<Integer> episodeItems = new ArrayList<>();

    /** 集数多时按每页 50 集分组，与网页端一致。 */
    private void renderPages() {
        pages.removeAllViews();
        int count = (episodeItems.size() + EPISODES_PER_PAGE - 1) / EPISODES_PER_PAGE;
        page = Math.min(page, Math.max(0, count - 1));
        if (count > 1) {
            for (int p = 0; p < count; p++) {
                int from = p * EPISODES_PER_PAGE + 1, to = Math.min(episodeItems.size(), (p + 1) * EPISODES_PER_PAGE);
                int target = p;
                TextView b = Style.pill(this, String.format(Locale.ROOT, "%d-%d", from, to), p == page);
                b.setOnClickListener(v -> {
                    page = target;
                    renderPages();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 32));
                lp.rightMargin = Ui.dp(this, 8);
                pages.addView(b, lp);
            }
        }
        int start = page * EPISODES_PER_PAGE;
        episodes.set(episodeItems.subList(start, Math.min(episodeItems.size(), start + EPISODES_PER_PAGE)));
    }

    private boolean holding;
    private float speedBeforeHold = 1f;

    /** 控制栏的上一个 / 下一个按钮对应上一集 / 下一集，而不是播放列表。 */
    private Player episodeControls(ExoPlayer exo) {
        return new androidx.media3.common.ForwardingPlayer(exo) {
            private boolean episodeCommand(int command) {
                return command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_PREVIOUS
                        || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;
            }

            @Override public boolean isCommandAvailable(int command) {
                if (command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) return index + 1 < video.episodes.size();
                if (command == COMMAND_SEEK_TO_PREVIOUS || command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) return index > 0;
                return super.isCommandAvailable(command);
            }

            @NonNull @Override public Commands getAvailableCommands() {
                Commands.Builder b = super.getAvailableCommands().buildUpon();
                for (int c : new int[]{COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM}) {
                    if (episodeCommand(c)) b.addIf(c, isCommandAvailable(c));
                }
                return b.build();
            }

            @Override public boolean hasNextMediaItem() { return index + 1 < video.episodes.size(); }
            @Override public boolean hasPreviousMediaItem() { return index > 0; }
            @Override public void seekToNext() { step(1); }
            @Override public void seekToNextMediaItem() { step(1); }
            @Override public void seekToPrevious() { step(-1); }
            @Override public void seekToPreviousMediaItem() { step(-1); }
        };
    }

    static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

    static String speedLabel(float s) {
        return (s == Math.round(s) ? String.format(Locale.ROOT, "%.0f", s) : String.format(Locale.ROOT, "%s", s)) + "x";
    }

    private void playerMenu() {
        String[] items = {"换源（" + video.sourceName + "）", "倍速（" + speedLabel(player.getPlaybackParameters().speed) + "）"};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setItems(items, (d, which) -> { if (which == 0) chooseSourceDialog(); else chooseSpeed(); }).show();
    }

    /** 与页面下方「换源」列表相同的源，以对话框列出，供全屏时使用。 */
    private void chooseSourceDialog() {
        if (!alternativesLoaded) {
            Ui.toast(this, "正在查找其他播放源…");
            return;
        }
        if (alternatives.size() <= 1) {
            Ui.toast(this, "没有找到其他播放源");
            return;
        }
        String[] names = new String[alternatives.size()];
        int checked = -1;
        for (int i = 0; i < names.length; i++) {
            Video v = alternatives.get(i);
            names[i] = v.sourceName + " · " + v.episodes.size() + " 集";
            if (v.key().equals(video.key())) checked = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("换源")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    d.dismiss();
                    switchSource(alternatives.get(which));
                })
                .setNegativeButton("取消", null).show();
    }

    private void chooseSpeed() {
        String[] labels = new String[SPEEDS.length];
        int checked = -1;
        float now = player.getPlaybackParameters().speed;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = speedLabel(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - now) < 0.01f) checked = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("播放倍速")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    player.setPlaybackSpeed(SPEEDS[which]);
                    d.dismiss();
                }).show();
    }

    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        layout.onConfigurationChanged(config);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 弹窗或切回应用后系统栏可能重新出现，重新隐藏
        if (hasFocus && layout.fullscreen()) Ui.immersive(this, true);
    }

    // ---------- 收藏 ----------

    private void loadFavorite() {
        favButton.setEnabled(false);
        String key = video.key();
        Tasks.run(() -> DecoApp.get().api.isFavorite(key), fav -> {
            if (!key.equals(video.key())) return;
            favorite = fav;
            favButton.setEnabled(true);
            showFavorite();
        }, e -> favButton.setEnabled(true));
    }

    private void showFavorite() {
        favButton.setLabel(favorite ? "已收藏" : "收藏");
        favButton.setActive(favorite, favorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
    }

    private void toggleFavorite() {
        favButton.setEnabled(false);
        boolean next = !favorite;
        Record r = Record.of(video);
        r.searchTitle = searchTitle;
        Tasks.run(() -> {
            if (next) DecoApp.get().api.saveFavorite(r);
            else DecoApp.get().api.deleteFavorite(r.key);
            return next;
        }, fav -> {
            favorite = fav;
            favButton.setEnabled(true);
            showFavorite();
            Ui.toast(this, favorite ? "已加入收藏" : "已取消收藏");
        }, e -> {
            favButton.setEnabled(true);
            Ui.fail(this, e);
        });
    }

    // ---------- 换源 ----------

    private static String norm(String s) { return s == null ? "" : s.replace(" ", "").toLowerCase(Locale.ROOT); }

    private static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile("\\d{4}");

    private static String year(String s) {
        java.util.regex.Matcher m = YEAR.matcher(s == null ? "" : s);
        return m.find() ? m.group() : "";
    }

    /** 与网页端相同：按标题搜索，保留去空格后同名、年份（前 4 位数字）一致、电影/剧集类型一致的结果。 */
    private void loadAlternatives() {
        String query = searchTitle;
        Tasks.run(() -> DecoApp.get().api.search(query), results -> {
            alternativesLoaded = true;
            alternatives.clear();
            boolean series = video.episodes.size() > 1;
            String wantYear = year(video.year);
            for (Video v : results) {
                if (!norm(v.title).equals(norm(video.title))) continue;
                if (!wantYear.isEmpty() && !wantYear.equals(year(v.year))) continue;
                if (series != (v.episodes.size() > 1)) continue;
                alternatives.add(v);
            }
            // 与网页端默认排序一致：当前源排第一，其余保持搜索顺序
            alternatives.removeIf(v -> v.key().equals(video.key()));
            alternatives.add(0, video);
            sources.set(alternatives);
            sourceHint.setText(alternatives.size() <= 1 ? "没有找到其他播放源" : "");
            sourceHint.setVisibility(sourceList.getVisibility() == View.VISIBLE && alternatives.size() <= 1 ? View.VISIBLE : View.GONE);
            updateTabCount();
            Runnable giveUp = pendingFailure;
            pendingFailure = null;
            if (giveUp != null && !failoverToNextSource()) giveUp.run();
        }, e -> {
            alternativesLoaded = true;
            sourceHint.setText("查找播放源失败：" + e.getMessage());
            Runnable giveUp = pendingFailure;
            pendingFailure = null;
            if (giveUp != null) giveUp.run();
            if (e instanceof ApiClient.AuthException) Ui.fail(this, e);
        });
    }

    private void switchSource(Video next) { switchSource(next, "已切换到 " + next.sourceName); }

    /** 与网页端一致：集数在新源范围内则保持集数和进度，否则从第 1 集开始；旧源的播放记录删除，改记到新源下。 */
    private void switchSource(Video next, String notice) {
        if (next.key().equals(video.key())) return;
        if (next.episodes.isEmpty()) {
            Ui.toast(this, "该源没有可播放的剧集");
            return;
        }
        boolean keepEpisode = index < next.episodes.size();
        long position = keepEpisode ? Math.max(0, player.getCurrentPosition()) : 0;
        if (position < 1000) position = 0;
        String oldKey = video.key();
        Tasks.run(() -> { DecoApp.get().api.deletePlayRecord(oldKey); return true; }, ok -> { }, e -> { });
        video = next;
        tried.add(next.key());
        int target = keepEpisode ? index : 0;
        page = target / EPISODES_PER_PAGE;
        showVideo();
        loadFavorite();
        Ui.toast(this, notice);
        play(target, position);
    }

    private final java.util.Set<String> tried = new java.util.HashSet<>();

    /** 当前源起播失败时，与网页端一样自动切到下一个还没试过的源。返回是否已切换。 */
    private boolean failoverToNextSource() {
        tried.add(video.key());
        for (Video v : alternatives) {
            if (!tried.contains(v.key()) && !v.episodes.isEmpty()) {
                switchSource(v, "已自动切换到 " + v.sourceName);
                return true;
            }
        }
        return false;
    }

    private boolean alternativesLoaded;
    private Runnable pendingFailure;

    /**
     * 当前源起播失败：有没试过的源就换；其他源还在查找时先等查找结果再决定，
     * 避免直连很快失败、搜索还没回来时直接判定失败。返回 false 表示应立即报失败。
     */
    private boolean failoverOrWait(Runnable giveUp) {
        if (!DecoApp.get().prefs.autoSwitchSource()) return false;
        if (failoverToNextSource()) return true;
        if (alternativesLoaded) return false;
        pendingFailure = giveUp;
        status.setText("当前源无法播放，正在查找其他播放源…");
        return true;
    }

    // ---------- 播放 ----------

    private void step(int delta) {
        int next = index + delta;
        if (next < 0 || next >= video.episodes.size()) {
            Ui.toast(this, delta > 0 ? "已经是最后一集" : "已经是第一集");
            return;
        }
        save();
        play(next, 0);
    }

    private void play(int episode, long startMs) {
        index = episode;
        int token = ++attempt;
        pendingFailure = null;
        resolved = null;
        current = null;
        ready = false;
        playState = "loading";
        handler.removeCallbacks(startupTimeout);
        title.setText(video.title + " · " + video.episodeTitles.get(episode));
        status.setText("正在准备播放…");
        if (episode / EPISODES_PER_PAGE != page) {
            page = episode / EPISODES_PER_PAGE;
            renderPages();
        } else {
            episodes.notifyDataSetChanged();
        }
        danmaku.load(video.title, video.year, episode, null);
        String raw = video.episodes.get(episode);
        Relay.Mode mode = DecoApp.get().prefs.mode(Relay.Item.VOD);
        Tasks.run(() -> prepare(raw, mode), media -> {
            if (token == attempt) start(media, startMs);
        }, error -> {
            if (token != attempt) return;
            if (mode == Relay.Mode.SMART && !(error instanceof ApiClient.AuthException)) {
                fallbackToRelay(raw, startMs, token);
            } else {
                Runnable fail = () -> {
                    playState = "failed";
                    status.setText("无法播放：" + error.getMessage());
                    Ui.fail(this, error);
                };
                if (error instanceof ApiClient.AuthException || !failoverOrWait(fail)) fail.run();
            }
        });
    }

    /** 后台线程：按策略得到首个播放目标。网页型集数地址需要先由服务端解析出真实媒体地址。 */
    private Media prepare(String raw, Relay.Mode mode) throws Exception {
        boolean media = MediaUrls.looksLikeMedia(raw);
        ApiClient.Resolved r = null;
        if (mode == Relay.Mode.RELAY || !media) {
            r = DecoApp.get().api.resolve(raw, video.source);
            resolved = r;
        }
        if (mode == Relay.Mode.RELAY) return relayTarget(r);
        Media m = new Media();
        m.url = r != null && !r.resolvedUrl.isEmpty() ? r.resolvedUrl : raw;
        m.referer = r == null ? "" : r.referer;
        m.hls = (r != null && "hls".equals(r.mediaType)) || MediaUrls.isHls(m.url);
        if (!m.hls && !MediaUrls.looksLikeMedia(m.url)) m.hls = Media.sniffHls(m.url, m.userAgent, m.referer);
        return m;
    }

    private static Media relayTarget(ApiClient.Resolved r) throws Exception {
        if (r == null || r.proxyUrl.isEmpty()) throw new Exception("服务端未提供中转地址");
        Media m = new Media();
        m.url = r.proxyUrl;
        m.relayed = true;
        m.hls = r.proxyUrl.contains("/api/proxy/m3u8-filter");
        return m;
    }

    private void start(Media media, long startMs) {
        current = media;
        ready = false;
        Ui.setRoute(route, null);
        status.setText(media.relayed ? "正在经服务端中转加载…" : "正在直连 " + MediaUrls.host(media.url) + " …");
        player.setMediaSource(media.source());
        player.prepare();
        if (startMs > 0) player.seekTo(startMs);
        player.play();
        handler.removeCallbacks(startupTimeout);
        startedAt = android.os.SystemClock.elapsedRealtime();
        // 中转要经服务端拉流和去广告，起播比直连慢，给更长的等待时间
        handler.postDelayed(startupTimeout, media.relayed ? RELAY_STARTUP_TIMEOUT_MS : STARTUP_TIMEOUT_MS);
        handler.removeCallbacks(saver);
        handler.postDelayed(saver, SAVE_INTERVAL_MS);
    }

    private void onPlaybackFailed(PlaybackException error) {
        handler.removeCallbacks(startupTimeout);
        // 释放播放器时可能还会回调一次错误，页面已结束就不再处理
        if (current == null || isFinishing() || isDestroyed()) return;
        NetLog.fail(Relay.Item.VOD, current.relayed, current.url, error);
        long position = Math.max(0, player.getCurrentPosition());
        if (!current.relayed && DecoApp.get().prefs.mode(Relay.Item.VOD) == Relay.Mode.SMART) {
            fallbackToRelay(video.episodes.get(index), position, attempt);
            return;
        }
        boolean relayed = current.relayed;
        Runnable fail = () -> {
            playState = "failed";
            status.setText("播放失败：" + error.getErrorCodeName() + "，可尝试「换源」");
            Ui.toast(this, relayed ? "中转播放失败，可尝试换源" : "直连播放失败，可换源，或在设置中把点播视频改为「智能」");
        };
        if (ready || !failoverOrWait(fail)) fail.run();
    }

    private void fallbackToRelay(String raw, long positionMs, int token) {
        status.setText("直连失败，正在改用服务端中转…");
        ApiClient.Resolved known = resolved;
        Tasks.run(() -> relayTarget(known != null ? known : DecoApp.get().api.resolve(raw, video.source)), media -> {
            if (token == attempt) start(media, positionMs);
        }, error -> {
            if (token != attempt) return;
            Runnable fail = () -> {
                playState = "failed";
                status.setText("中转也失败：" + error.getMessage());
                Ui.fail(this, error);
            };
            if (error instanceof ApiClient.AuthException || !failoverOrWait(fail)) fail.run();
        });
    }

    private void save() {
        if (player == null || !ready) return;
        long position = player.getCurrentPosition() / 1000, duration = Math.max(0, player.getDuration()) / 1000;
        if (position < 1) return;
        Record r = Record.of(video);
        r.index = index + 1;
        r.playTime = position;
        r.totalTime = duration;
        r.searchTitle = searchTitle;
        Tasks.run(() -> { DecoApp.get().api.savePlayRecord(r); return true; }, ok -> { }, e -> {
            if (e instanceof ApiClient.AuthException) Ui.fail(this, e);
        });
    }

    @Override protected void onPause() {
        super.onPause();
        save();
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (speed != null) speed.stop();
        if (gestures != null) gestures.release();
        if (player != null) player.release();
    }
}
