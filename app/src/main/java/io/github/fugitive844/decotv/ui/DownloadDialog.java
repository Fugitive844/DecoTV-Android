package io.github.fugitive844.decotv.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.download.DownloadService;
import io.github.fugitive844.decotv.download.DownloadTask;
import io.github.fugitive844.decotv.model.Video;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 播放页「缓存」：先选集（电影跳过），再选保存格式；每次都可以重新选格式。 */
final class DownloadDialog {
    private static final String[] FORMAT_TEXT = {
            "加密（仅本 App）\n其他应用无法读取，卸载后删除",
            "MP4\n存到「影片/DecoTV」，其他播放器可打开；必要时自动转码",
            "原格式\n存到「影片/DecoTV」，最快，多为 TS 格式",
    };
    private static final DownloadTask.Format[] FORMATS = {DownloadTask.Format.ENCRYPTED, DownloadTask.Format.MP4, DownloadTask.Format.ORIGINAL};

    private DownloadDialog() {}

    static void show(AppCompatActivity activity, Video video, int current) {
        if (video.episodes.size() <= 1) {
            chooseFormat(activity, video, java.util.Collections.singletonList(0));
            return;
        }
        int n = video.episodes.size();
        String[] names = new String[n];
        boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) names[i] = video.episodeTitles.get(i);
        checked[current] = true;
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("选择要缓存的剧集")
                .setMultiChoiceItems(names, checked, (d, which, on) -> checked[which] = on)
                .setNeutralButton("全选", null)
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", (d, w) -> {
                    List<Integer> picked = new ArrayList<>();
                    for (int i = 0; i < n; i++) if (checked[i]) picked.add(i);
                    if (picked.isEmpty()) Ui.toast(activity, "没有选择剧集");
                    else chooseFormat(activity, video, picked);
                }).create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean all = true;
            for (boolean c : checked) all &= c;
            for (int i = 0; i < n; i++) {
                checked[i] = !all;
                dialog.getListView().setItemChecked(i, !all);
            }
        }));
        dialog.show();
    }

    private static void chooseFormat(AppCompatActivity activity, Video video, List<Integer> episodes) {
        DownloadTask.Format last = DownloadTask.Format.of(DecoApp.get().prefs.downloadFormat());
        int[] choice = {0};
        for (int i = 0; i < FORMATS.length; i++) if (FORMATS[i] == last) choice[0] = i;
        new MaterialAlertDialogBuilder(activity).setTitle("保存格式（" + episodes.size() + " 集）")
                .setSingleChoiceItems(FORMAT_TEXT, choice[0], (d, which) -> choice[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始缓存", (d, w) -> {
                    DownloadTask.Format format = FORMATS[choice[0]];
                    DecoApp.get().prefs.setDownloadFormat(format.key);
                    if (format != DownloadTask.Format.ENCRYPTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                            && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 41);
                        Ui.toast(activity, "需要存储权限才能保存到影片目录，授权后请再点一次缓存");
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        // 只用于显示下载进度通知；拒绝也不影响缓存
                        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
                    }
                    enqueue(activity, video, episodes, format);
                }).show();
    }

    private static void enqueue(AppCompatActivity activity, Video video, List<Integer> episodes, DownloadTask.Format format) {
        int added = 0;
        for (int i : episodes) {
            DownloadTask t = new DownloadTask();
            t.id = UUID.randomUUID().toString();
            t.videoKey = video.key();
            t.title = video.title;
            t.sourceName = video.sourceName;
            t.cover = video.poster;
            t.episode = i;
            t.episodeTitle = video.episodeTitles.get(i);
            t.episodeUrl = video.episodes.get(i);
            t.videoJson = video.toJson().toString();
            t.format = format;
            t.createdAt = System.currentTimeMillis();
            if (DecoApp.get().downloads.add(t)) added++;
        }
        DownloadService.start(activity);
        Ui.toast(activity, added > 0 ? "已加入缓存 " + added + " 集，可在底部「缓存」查看，点按即可边存边播" : "这些剧集已在缓存列表中");
    }
}
