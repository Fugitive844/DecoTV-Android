package io.github.fugitive844.decotv.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.R;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.NetLog;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.ui.MainActivity;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前台服务按设置的并发数同时执行多个缓存任务：解析地址 → 下载 → 按所选格式保存。
 * 与点播一致按「点播视频」的中转策略：默认直连第三方；全中转走服务端（含去广告）；智能在直连失败时改走中转。
 */
public class DownloadService extends Service {
    private static final String CHANNEL = "downloads";
    private static final int NOTIFY_ID = 7101;
    private static final String ACTION_PAUSE = "pause", EXTRA_ID = "id";

    /** 同时进行的任务数：设置里可调，默认 5、最多 30；每个 HLS 任务内部另有 4 个线程下载分片。 */
    public static final int DEFAULT_PARALLEL = 5, MAX_PARALLEL = 30;

    /** 正在执行的一个任务：下载器和暂停标记各管各的。 */
    private static final class Job {
        final DownloadTask task;
        volatile Fetcher fetcher;
        volatile boolean pauseRequested;

        Job(DownloadTask task) { this.task = task; }
    }

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    /** 转 MP4 占用硬件编解码器，多个任务同时转换容易失败，一次只转一个。 */
    private static final Object CONVERT_LOCK = new Object();
    private final Object wake = new Object();
    private Thread dispatcher;
    private volatile int lastStartId;

    public static void start(Context c) {
        ContextCompat.startForegroundService(c, new Intent(c, DownloadService.class));
    }

    /** 调大并发数后立即补上排队的任务。 */
    public static void parallelChanged(Context c) {
        if (!JOBS.isEmpty()) start(c);
    }

    /** 暂停正在进行的任务；排队中的任务直接改为暂停。 */
    public static void pause(Context c, String id) {
        DownloadStore store = DecoApp.get().downloads;
        Job job = JOBS.get(id);
        if (job != null) {
            job.pauseRequested = true;
            Fetcher f = job.fetcher;
            if (f != null) f.cancelled = true;
        } else {
            DownloadTask t = store.get(id);
            if (t != null && t.state == DownloadTask.State.QUEUED) {
                t.state = DownloadTask.State.PAUSED;
                store.update(t);
            }
        }
    }

    public static boolean running(String id) { return JOBS.containsKey(id); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "离线缓存", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceCompat.startForeground(this, NOTIFY_ID, notification("准备缓存…", 0, true),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        synchronized (wake) {
            lastStartId = startId;
            if (dispatcher == null) {
                dispatcher = new Thread(this::loop, "DecoTV-download");
                dispatcher.start();
            } else {
                wake.notifyAll();
            }
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String text, int percent, boolean indeterminate) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("DecoTV 离线缓存")
                .setContentText(text).setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open)
                .setProgress(100, percent, indeterminate).build();
    }

    /** 只有一个任务时显示它的详情；多个任务同时进行时显示数量和总进度。 */
    private void progressNote(String text, int percent, boolean indeterminate) {
        int n = JOBS.size();
        if (n > 1) {
            float sum = 0;
            for (Job j : JOBS.values()) sum += j.task.progress();
            percent = Math.round(sum / n * 100);
            text = String.format(Locale.ROOT, "正在同时缓存 %d 项 · 总进度 %d%%", n, percent);
            indeterminate = false;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_ID, notification(text, percent, indeterminate));
    }

    /** 调度：按设置的并发数从队列取任务，各开一个线程执行；全部结束后停止服务。 */
    private void loop() {
        DownloadStore store = DecoApp.get().downloads;
        // 边存边播时留下的分片：播放器已退出、任务已完成的，在这里补删
        for (DownloadTask t : store.all()) {
            if (t.state == DownloadTask.State.DONE && !Stream.inUse(t.id) && workDir(t).exists()) deleteTree(workDir(t));
        }
        synchronized (wake) {
            while (true) {
                int limit = Math.max(1, Math.min(MAX_PARALLEL, DecoApp.get().prefs.downloadParallel()));
                DownloadTask next = JOBS.size() < limit ? store.nextQueued(JOBS.keySet()) : null;
                if (next != null) {
                    Job job = new Job(next);
                    JOBS.put(next.id, job);
                    new Thread(() -> execute(job), "DecoTV-download-" + next.id).start();
                    continue;
                }
                if (JOBS.isEmpty()) break;
                try {
                    wake.wait(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            // 结束前又来了新的启动请求时 stopSelfResult 不会停掉服务，由新请求重新开调度线程
            dispatcher = null;
            if (stopSelfResult(lastStartId)) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        }
    }

    private void execute(Job job) {
        DownloadTask task = job.task;
        try {
            run(job);
            task.state = DownloadTask.State.DONE;
        } catch (Exception e) {
            if (job.pauseRequested) {
                task.state = DownloadTask.State.PAUSED;
                task.error = "";
            } else {
                task.state = DownloadTask.State.FAILED;
                task.error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } finally {
            JOBS.remove(task.id);
        }
        DecoApp.get().downloads.update(task);
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    private File workDir(DownloadTask t) { return Stream.workDir(this, t.id); }

    static File vaultFile(Context c, DownloadTask t) {
        File dir = new File(c.getFilesDir(), "offline");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, t.id + ".dtv");
    }

    private void run(Job job) throws Exception {
        DownloadTask task = job.task;
        DownloadStore store = DecoApp.get().downloads;
        ApiClient api = DecoApp.get().api;
        task.state = DownloadTask.State.DOWNLOADING;
        task.error = "";
        store.update(task);
        String label = task.title + " " + task.episodeTitle;
        progressNote("正在缓存 " + label, 0, true);
        Relay.Mode mode = DecoApp.get().prefs.mode(Relay.Item.VOD);
        File work = workDir(task);
        Fetcher.Result result;
        try {
            result = fetch(job, api, mode == Relay.Mode.RELAY, work, label);
        } catch (IOException e) {
            if (job.pauseRequested || mode != Relay.Mode.SMART) throw e;
            NetLog.fail(Relay.Item.VOD, false, task.episodeUrl, e);
            result = fetch(job, api, true, work, label);
        }
        task.container = result.container;
        File toSave = result.file;
        if (task.format == DownloadTask.Format.MP4 && !(result.container.equals("mp4") && task.totalSegments == 0)) {
            // HLS 拼接出的 TS / fMP4、FLV 等转成标准 MP4
            task.state = DownloadTask.State.CONVERTING;
            store.update(task);
            File mp4 = new File(work, "converted.mp4");
            try {
                synchronized (CONVERT_LOCK) {
                    if (job.pauseRequested) throw new IOException("已暂停");
                    Converter.toMp4(this, result.file, mp4, p -> progressNote("正在转换 MP4 " + label, p, false), () -> job.pauseRequested);
                }
                toSave = mp4;
                task.container = "mp4";
            } catch (IOException e) {
                if (job.pauseRequested) throw e;
                // 转换失败时按原格式保存，不丢已下载的内容
                task.error = "转 MP4 失败，已按原格式（" + result.container + "）保存：" + e.getMessage();
            }
        }
        // 没转成 MP4 的 HLS 缓存记下分片布局，本 App 播放时时长和拖动才准确
        File layout = Stream.layoutFile(this, task.id);
        if (result.lengths != null && toSave == result.file) Stream.writeLayout(layout, result);
        else //noinspection ResultOfMethodCallIgnored
            layout.delete();
        if (task.format == DownloadTask.Format.ENCRYPTED) {
            progressNote("正在加密保存 " + label, 100, true);
            File out = vaultFile(this, task);
            Vault.encrypt(toSave, out);
            task.output = out.getPath();
        } else {
            progressNote("正在保存到相册 " + label, 100, true);
            task.output = PublicMedia.save(this, toSave, task.fileName(), task.container);
        }
        // 正在边存边播：分片留给播放器，播放器退出时清理
        if (!Stream.inUse(task.id)) deleteTree(work);
        saveDanmaku(task, api);
    }

    /** 顺便缓存这一集的弹幕，失败不影响视频缓存，离线播放时没有文件会再尝试在线加载。 */
    private void saveDanmaku(DownloadTask task, ApiClient api) {
        File file = DownloadTask.danmakuFile(this, task.id);
        try {
            String year = "";
            try {
                year = new org.json.JSONObject(task.videoJson).optString("year", "");
            } catch (org.json.JSONException ignored) {
                // 旧任务可能没有影片信息
            }
            org.json.JSONObject o = api.danmaku(task.title, year, task.episode + 1, 0, "", "");
            if (o.optInt("count", 0) == 0) return;
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            java.nio.file.Files.write(file.toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w("DecoTV-danmaku", "cache failed: " + e.getMessage());
        }
    }

    private Fetcher.Result fetch(Job job, ApiClient api, boolean relay, File work, String label) throws Exception {
        DownloadTask task = job.task;
        String raw = task.episodeUrl;
        String url = raw, referer = "", userAgent = ApiClient.USER_AGENT;
        boolean hls;
        String source = task.videoKey.contains("+") ? task.videoKey.substring(0, task.videoKey.indexOf('+')) : "";
        if (relay) {
            ApiClient.Resolved r = api.resolve(raw, source);
            if (r.proxyUrl.isEmpty()) throw new IOException("服务端未提供中转地址");
            url = r.proxyUrl;
            hls = r.proxyUrl.contains("/api/proxy/m3u8-filter");
        } else {
            if (!MediaUrls.looksLikeMedia(raw)) {
                ApiClient.Resolved r = api.resolve(raw, source);
                if (!r.resolvedUrl.isEmpty()) url = r.resolvedUrl;
                referer = r.referer;
                hls = "hls".equals(r.mediaType) || MediaUrls.isHls(url);
            } else {
                hls = MediaUrls.isHls(url);
            }
        }
        task.relayed = relay;
        Fetcher fetcher = new Fetcher(api.http(), userAgent, referer);
        fetcher.streamId = task.id;
        job.fetcher = fetcher;
        if (job.pauseRequested) throw new IOException("已暂停");
        if (!hls && !MediaUrls.looksLikeMedia(url)) hls = fetcher.sniffHls(url);
        DownloadStore store = DecoApp.get().downloads;
        long[] lastUpdate = {0};
        Fetcher.Progress progress = new Fetcher.Progress() {
            @Override public void segments(int done, int total, long bytes) {
                task.doneSegments = done;
                task.totalSegments = total;
                task.bytes = bytes;
                tick();
            }

            @Override public void bytes(long done, long total) {
                task.bytes = done;
                task.totalBytes = total;
                tick();
            }

            private void tick() {
                long now = SystemClock.elapsedRealtime();
                if (now - lastUpdate[0] < 1000) return;
                lastUpdate[0] = now;
                store.update(task);
                progressNote(String.format(Locale.ROOT, "正在缓存 %s · %d%%", label, Math.round(task.progress() * 100)),
                        Math.round(task.progress() * 100), false);
            }
        };
        NetLog.use(Relay.Item.VOD, relay, url);
        return hls ? fetcher.hls(url, work, progress) : fetcher.file(url, work, progress);
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 删除任务及其文件（加密文件、相册里的文件、未完成的临时文件）。 */
    public static void delete(Context c, DownloadTask t) {
        if (running(t.id)) pause(c, t.id);
        if (t.format == DownloadTask.Format.ENCRYPTED) {
            //noinspection ResultOfMethodCallIgnored
            vaultFile(c, t).delete();
        } else if (t.state == DownloadTask.State.DONE) {
            PublicMedia.delete(c, t.output);
        }
        //noinspection ResultOfMethodCallIgnored
        DownloadTask.danmakuFile(c, t.id).delete();
        //noinspection ResultOfMethodCallIgnored
        Stream.layoutFile(c, t.id).delete();
        File base = c.getExternalFilesDir("downloads");
        if (base != null) deleteTree(new File(base, t.id));
        deleteTree(new File(new File(c.getFilesDir(), "work"), t.id));
        DecoApp.get().downloads.remove(t.id);
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
