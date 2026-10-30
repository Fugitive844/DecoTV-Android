package io.github.fugitive844.decotv.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.download.DownloadService;
import io.github.fugitive844.decotv.download.DownloadStore;
import io.github.fugitive844.decotv.download.DownloadTask;
import io.github.fugitive844.decotv.download.Stream;
import io.github.fugitive844.decotv.model.Video;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 底部导航「缓存」：按影片分组列出离线缓存。
 * 已完成的点按离线播放；没完成的 m3u8 边存边播（已下载的部分读本地），其他格式先在线播放，下载在后台继续。
 */
final class DownloadsPage implements MainActivity.Page {
    /** 列表的一行：影片组头或一集。 */
    private static final class Row {
        final List<DownloadTask> group;
        final DownloadTask task;

        Row(List<DownloadTask> group, DownloadTask task) {
            this.group = group;
            this.task = task;
        }
    }

    private final MainActivity activity;
    private final LinearLayout root;
    private final TextView empty, summary;
    private final MaterialButton pauseAll, clear;
    private final RowAdapter<Row> adapter;
    private final DownloadStore.Listener listener = this::load;
    private boolean listening;

    DownloadsPage(MainActivity activity) {
        this.activity = activity;
        root = Ui.column(activity, 8);
        LinearLayout bar = Ui.row(activity);
        summary = Ui.text(activity, "", 12, false);
        summary.setAlpha(0.65f);
        bar.addView(summary, Ui.weight(1));
        pauseAll = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
        pauseAll.setOnClickListener(v -> toggleAll());
        bar.addView(pauseAll);
        clear = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
        clear.setText("清空");
        clear.setOnClickListener(v -> confirm("删除全部离线缓存及文件？", () -> {
            for (DownloadTask t : DecoApp.get().downloads.all()) DownloadService.delete(activity, t);
            load();
        }));
        bar.addView(clear);
        root.addView(bar);
        empty = Ui.empty(activity, "还没有离线缓存\n在播放页点「缓存」，选好剧集和格式就会出现在这里");
        root.addView(empty);
        adapter = new RowAdapter<Row>(60, 84, (h, row) -> {
            int pad = Ui.dp(activity, 10);
            if (row.task == null) {
                DownloadTask first = row.group.get(0);
                h.image.setVisibility(View.VISIBLE);
                h.itemView.setPadding(pad, pad, pad, pad / 2);
                h.title.setText(first.title);
                h.title.setTextSize(16);
                int done = 0;
                long bytes = 0;
                for (DownloadTask t : row.group) {
                    if (t.state == DownloadTask.State.DONE) done++;
                    bytes += t.bytes;
                }
                h.subtitle.setText(first.sourceName + " · 已完成 " + done + " / " + row.group.size() + " 集" + size(bytes));
                DecoApp.get().images.cover(h.image, first.cover);
            } else {
                DownloadTask t = row.task;
                h.image.setVisibility(View.GONE);
                h.itemView.setPadding(Ui.dp(activity, 24), pad / 2, pad, pad / 2);
                h.title.setText(t.episodeTitle);
                h.title.setTextSize(15);
                h.subtitle.setText(status(t));
            }
        }).onClick((row, i) -> {
            if (row.task != null) open(row.task);
            else open(firstPlayable(row.group));
        }).onLongClick((row, i) -> {
            if (row.task != null) menu(row.task);
            else confirm("删除「" + row.group.get(0).title + "」的全部缓存及文件？", () -> {
                for (DownloadTask t : row.group) DownloadService.delete(activity, t);
                load();
            });
        });
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        // 进度每秒刷新，关掉闪烁动画
        list.setItemAnimator(null);
        root.addView(list, Ui.fill(1));
    }

    @Override public View view() {
        if (!listening) {
            DecoApp.get().downloads.listen(listener);
            listening = true;
        }
        return root;
    }

    @Override public void show() { load(); }

    @Override public void destroy() { DecoApp.get().downloads.unlisten(listener); }

    private static String size(long bytes) {
        if (bytes <= 0) return "";
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, " · %.2f GB", bytes / (double) (1L << 30))
                : String.format(Locale.ROOT, " · %.1f MB", bytes / 1048576.0);
    }

    private String status(DownloadTask t) {
        String fmt = t.format.label + (t.relayed ? " · 中转" : "");
        int percent = Math.round(t.progress() * 100);
        boolean stream = Stream.available(activity, t);
        switch (t.state) {
            case DONE: return "已完成 · " + fmt + (t.container.isEmpty() ? "" : " · " + t.container) + size(t.bytes)
                    + (t.error.isEmpty() ? "" : "\n" + t.error);
            case DOWNLOADING: return "下载中 " + percent + "% · " + fmt + size(t.bytes) + (stream ? " · 点按边存边播" : " · 点按在线播放");
            case CONVERTING: return "正在转换 MP4… · " + fmt + (stream ? " · 点按边存边播" : "");
            case PAUSED: return "已暂停 " + percent + "% · " + fmt + " · 长按继续";
            case FAILED: return "失败：" + t.error + " · 长按重试";
            default: return "等待中 · " + fmt;
        }
    }

    private static DownloadTask firstPlayable(List<DownloadTask> group) {
        for (DownloadTask t : group) if (t.state == DownloadTask.State.DONE) return t;
        return group.get(0);
    }

    private void open(DownloadTask t) {
        if (t.state == DownloadTask.State.DONE) {
            OfflinePlayerActivity.open(activity, t);
            return;
        }
        resume(t);
        if (Stream.available(activity, t)) {
            OfflinePlayerActivity.open(activity, t);
        } else {
            // 还没拿到 m3u8 分片列表，或是 MP4 等单文件（索引常在文件末尾，边下边读不可靠）：先在线播放
            Ui.toast(activity, "这一集还不能边存边播，先在线播放，缓存在后台继续");
            playOnline(t);
        }
    }

    private void playOnline(DownloadTask t) {
        try {
            Video v = Video.fromJson(new JSONObject(t.videoJson));
            PlayerActivity.open(activity, v, Math.min(t.episode, Math.max(0, v.episodes.size() - 1)), 0);
        } catch (Exception e) {
            Ui.toast(activity, "缓存里没有影片信息，无法在线播放");
        }
    }

    private static boolean active(DownloadTask t) {
        return t.state == DownloadTask.State.DOWNLOADING || t.state == DownloadTask.State.CONVERTING || t.state == DownloadTask.State.QUEUED;
    }

    /** 暂停、失败的任务重新排队。 */
    private void resume(DownloadTask t) {
        if (t.state != DownloadTask.State.PAUSED && t.state != DownloadTask.State.FAILED) return;
        t.state = DownloadTask.State.QUEUED;
        t.error = "";
        DecoApp.get().downloads.update(t);
        DownloadService.start(activity);
    }

    private void menu(DownloadTask t) {
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (t.state == DownloadTask.State.DONE) {
            names.add("离线播放");
            actions.add(() -> OfflinePlayerActivity.open(activity, t));
        } else if (active(t)) {
            names.add("暂停");
            actions.add(() -> DownloadService.pause(activity, t.id));
        } else {
            names.add(t.state == DownloadTask.State.FAILED ? "重试" : "继续");
            actions.add(() -> resume(t));
        }
        names.add("在线播放");
        actions.add(() -> playOnline(t));
        names.add("删除");
        actions.add(() -> confirm("删除「" + t.title + " " + t.episodeTitle + "」的缓存及文件？", () -> {
            DownloadService.delete(activity, t);
            load();
        }));
        new MaterialAlertDialogBuilder(activity).setTitle(t.title + " · " + t.episodeTitle)
                .setItems(names.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
    }

    private void toggleAll() {
        List<DownloadTask> all = DecoApp.get().downloads.all();
        boolean anyActive = false;
        for (DownloadTask t : all) anyActive |= active(t);
        for (DownloadTask t : all) {
            if (anyActive && active(t)) DownloadService.pause(activity, t.id);
            else if (!anyActive) resume(t);
        }
    }

    private void load() {
        List<DownloadTask> all = DecoApp.get().downloads.all();
        Map<String, List<DownloadTask>> groups = new LinkedHashMap<>();
        // 最近加入的影片排前面，组内按集数
        List<DownloadTask> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        for (DownloadTask t : sorted) {
            List<DownloadTask> g = groups.get(t.videoKey);
            if (g == null) groups.put(t.videoKey, g = new ArrayList<>());
            g.add(t);
        }
        List<Row> rows = new ArrayList<>();
        long bytes = 0;
        int done = 0;
        boolean anyActive = false, anyPaused = false;
        for (List<DownloadTask> g : groups.values()) {
            g.sort((a, b) -> Integer.compare(a.episode, b.episode));
            rows.add(new Row(g, null));
            for (DownloadTask t : g) {
                rows.add(new Row(g, t));
                bytes += t.bytes;
                if (t.state == DownloadTask.State.DONE) done++;
                anyActive |= active(t);
                anyPaused |= t.state == DownloadTask.State.PAUSED || t.state == DownloadTask.State.FAILED;
            }
        }
        adapter.set(rows);
        empty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        summary.setText(all.isEmpty() ? "" : groups.size() + " 部 · " + all.size() + " 集 · 已完成 " + done + size(bytes));
        pauseAll.setText(anyActive ? "全部暂停" : "全部继续");
        pauseAll.setVisibility(anyActive || anyPaused ? View.VISIBLE : View.GONE);
        clear.setEnabled(!all.isEmpty());
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(activity).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> yes.run()).show();
    }
}
