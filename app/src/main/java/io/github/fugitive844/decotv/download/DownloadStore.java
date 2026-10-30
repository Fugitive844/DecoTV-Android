package io.github.fugitive844.decotv.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 缓存任务列表，存为 files/downloads/tasks.json。所有方法线程安全，变化在主线程通知。 */
public final class DownloadStore {
    public interface Listener { void onChanged(); }

    private final File file;
    private final List<DownloadTask> tasks = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    public DownloadStore(Context context) {
        File dir = new File(context.getFilesDir(), "downloads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        file = new File(dir, "tasks.json");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                DownloadTask t = DownloadTask.fromJson(array.getJSONObject(i));
                // 上次进程被杀时正在进行的任务，重新排队
                if (t.state == DownloadTask.State.DOWNLOADING || t.state == DownloadTask.State.CONVERTING) t.state = DownloadTask.State.QUEUED;
                tasks.add(t);
            }
        } catch (IOException | JSONException e) {
            // 文件损坏时从空列表开始
        }
    }

    private void persist() {
        JSONArray array = new JSONArray();
        for (DownloadTask t : tasks) array.put(t.toJson());
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
    }

    public synchronized List<DownloadTask> all() {
        List<DownloadTask> copy = new ArrayList<>();
        for (DownloadTask t : tasks) copy.add(DownloadTask.fromJson(t.toJson()));
        return copy;
    }

    public synchronized DownloadTask get(String id) {
        for (DownloadTask t : tasks) if (t.id.equals(id)) return DownloadTask.fromJson(t.toJson());
        return null;
    }

    /** 同一集同一格式已在列表里就不重复添加，返回是否新增。 */
    public synchronized boolean add(DownloadTask task) {
        for (DownloadTask t : tasks) {
            if (t.videoKey.equals(task.videoKey) && t.episode == task.episode && t.format == task.format) {
                if (t.state == DownloadTask.State.FAILED || t.state == DownloadTask.State.PAUSED) {
                    t.state = DownloadTask.State.QUEUED;
                    t.error = "";
                    changed();
                }
                return false;
            }
        }
        tasks.add(0, task);
        changed();
        return true;
    }

    public synchronized void update(DownloadTask task) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id.equals(task.id)) {
                tasks.set(i, DownloadTask.fromJson(task.toJson()));
                changed();
                return;
            }
        }
    }

    public synchronized void remove(String id) {
        tasks.removeIf(t -> t.id.equals(id));
        changed();
    }

    /** 下一个排队中的任务，跳过 running 里已在执行的。 */
    public synchronized DownloadTask nextQueued(java.util.Set<String> running) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            DownloadTask t = tasks.get(i);
            if (t.state == DownloadTask.State.QUEUED && !running.contains(t.id)) return DownloadTask.fromJson(t.toJson());
        }
        return null;
    }

    private void changed() {
        persist();
        main.post(() -> { for (Listener l : listeners) l.onChanged(); });
    }

    public void listen(Listener l) { listeners.add(l); }

    public void unlisten(Listener l) { listeners.remove(l); }
}
