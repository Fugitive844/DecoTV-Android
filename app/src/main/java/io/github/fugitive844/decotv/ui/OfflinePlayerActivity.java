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
 * 播放离线缓存：与在线点播相同的小窗 / 全屏、手势、倍速，上一集 / 下一集在已缓存的剧集之间切换。
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
    /** 正在边存边播的任务 id，没有为 null。 */
    private String streaming;
    private final DownloadStore.Listener storeListener = this::onStoreChanged;
    private MaterialButton speedButton;
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
        for (DownloadTask t : DecoApp.get().downloads.all()) {
            if (t.videoKey.equals(start.videoKey) && (t.state == DownloadTask.State.DONE || t.id.equals(start.id) || Stream.available(this, t))) {
                episodes.add(t);
            }
        }
        episodes.sort((a, b) -> Integer.compare(a.episode, b.episode));
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).id.equals(start.id)) position = i;
        try {
            video = Video.fromJson(new JSONObject(start.videoJson));
        } catch (Exception e) {
            video = null;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        box = new PlayerGestures.Frame(this);
        view = new PlayerView(this);
        Ui.stylePlayer(view);
        player = new ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        view.setPlayer(controls(player));
        view.setFullscreenButtonClickListener(ignored -> layout.toggle());
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
        actions.addView(button("上一集", v -> step(-1)));
        actions.addView(button("下一集", v -> step(1)));
        speedButton = button(PlayerActivity.speedLabel(DecoApp.get().prefs.playbackSpeed()), v -> chooseSpeed());
        actions.addView(speedButton);
        actions.addView(button("弹幕", v -> danmaku.showPanel()));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(actions);
        details.addView(scroll);
        details.addView(Ui.text(this, "已缓存的剧集", 15, true));
        list = new RowAdapter<DownloadTask>(0, 0, (h, t) -> {
            boolean playing = episodes.indexOf(t) == position;
            h.title.setText((playing ? "▶ " : "") + t.episodeTitle);
            h.subtitle.setText(t.state == DownloadTask.State.DONE ? t.format.label + " · " + t.container
                    : "缓存中 " + Math.round(t.progress() * 100) + "% · 可边存边播");
        }).onClick((t, i) -> play(i, 0));
        list.set(episodes);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(list);
        details.addView(rv, Ui.fill(1));

        layout = new PlayerLayout(this, box, details);
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
                    save();
                    if (position + 1 < episodes.size()) play(position + 1, 0);
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

    /** 边存边播的这一集缓存完成后，在当前进度切到最终文件；进度刷新列表。 */
    private void onStoreChanged() {
        if (episodes.isEmpty()) return;
        for (int i = 0; i < episodes.size(); i++) {
            DownloadTask fresh = DecoApp.get().downloads.get(episodes.get(i).id);
            if (fresh != null) episodes.set(i, fresh);
        }
        list.notifyDataSetChanged();
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
                if (c == COMMAND_SEEK_TO_NEXT || c == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) return position + 1 < episodes.size();
                if (c == COMMAND_SEEK_TO_PREVIOUS || c == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) return position > 0;
                return super.isCommandAvailable(c);
            }

            @NonNull @Override public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .addIf(COMMAND_SEEK_TO_NEXT, position + 1 < episodes.size()).addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, position + 1 < episodes.size())
                        .addIf(COMMAND_SEEK_TO_PREVIOUS, position > 0).addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, position > 0).build();
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

    private void step(int delta) {
        int next = position + delta;
        if (next < 0 || next >= episodes.size()) {
            Ui.toast(this, delta > 0 ? "已经是最后一集缓存" : "已经是第一集缓存");
            return;
        }
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
        position = i;
        DownloadTask t = episodes.get(i);
        setTitle(t.title);
        title.setText(t.title + " · " + t.episodeTitle);
        String previous = streaming;
        streaming = t.state == DownloadTask.State.DONE ? null : t.id;
        if (streaming != null && !Stream.available(this, t)) {
            streaming = previous;
            playState = "failed:not-streamable";
            status.setText("这一集还不能边存边播");
            return;
        }
        if (streaming != null && !streaming.equals(previous)) Stream.acquire(streaming);
        status.setText(streaming != null ? streamStatus(t) : "离线播放 · " + t.format.label + " · " + t.container);
        badge.setText(streaming != null ? "边存边播" : "离线");
        list.notifyDataSetChanged();
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
        if (player == null || video == null || episodes.isEmpty()) return;
        long pos = player.getCurrentPosition() / 1000, dur = Math.max(0, player.getDuration()) / 1000;
        if (pos < 1) return;
        Record r = Record.of(video);
        r.index = episodes.get(position).episode + 1;
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
