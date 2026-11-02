package io.github.fugitive844.decotv.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.net.ImageLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 每个缓存任务的本地封面；加密任务只落盘密文，显示时在内存解密。 */
public final class CoverCache {
    private static final Object[] LOCKS = new Object[32];
    private static final Map<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    private CoverCache() {}

    public static File file(Context context, DownloadTask task) {
        return new File(new File(context.getFilesDir(), "offline"), task.id
                + (task.format == DownloadTask.Format.ENCRYPTED ? ".cover.dtv" : ".cover.img"));
    }

    /** 返回本地封面；缺失时补存，兼容升级前的缓存任务。须在后台线程调用。 */
    public static Bitmap load(Context context, DownloadTask task, ImageLoader images) throws IOException {
        synchronized (LOCKS[(task.id.hashCode() & Integer.MAX_VALUE) % LOCKS.length]) {
            File target = file(context, task);
            if (target.isFile()) {
                try {
                    return read(context, task);
                } catch (IOException e) {
                    // 损坏文件允许在有网络时重新获取。
                    Files.deleteIfExists(target.toPath());
                }
            }
            if (task.cover.isEmpty()) return null;
            long now = SystemClock.elapsedRealtime();
            if (now < RETRY_AFTER.getOrDefault(task.id, 0L)) return null;
            // 下载进度每秒刷新列表，离线时避免反复请求同一张封面。
            RETRY_AFTER.put(task.id, now + 60_000);
            Bitmap bitmap = images.fetchCover(task.cover);
            if (DecoApp.get().downloads.get(task.id) == null) return null;
            save(context, task, bitmap);
            // 下载中删除任务时，不能留下迟到的封面文件。
            if (DecoApp.get().downloads.get(task.id) == null) {
                delete(context, task);
                return null;
            }
            RETRY_AFTER.remove(task.id);
            return bitmap;
        }
    }

    public static void save(Context context, DownloadTask task, Bitmap bitmap) throws IOException {
        File target = file(context, task);
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建封面缓存目录");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IOException("封面编码失败");
        File temp = new File(target.getPath() + ".tmp");
        try {
            if (task.format == DownloadTask.Format.ENCRYPTED) {
                Vault.encrypt(new ByteArrayInputStream(bytes.toByteArray()), temp);
            } else {
                Files.write(temp.toPath(), bytes.toByteArray());
            }
            Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    public static Bitmap read(Context context, DownloadTask task) throws IOException {
        File target = file(context, task);
        byte[] bytes;
        if (task.format == DownloadTask.Format.ENCRYPTED) {
            try (Vault.Reader reader = new Vault.Reader(target)) {
                long length = reader.length();
                if (length <= 0 || length > 10 * 1024 * 1024) throw new IOException("封面大小无效");
                bytes = new byte[(int) length];
                reader.seek(0);
                int offset = 0;
                while (offset < bytes.length) {
                    int n = reader.read(bytes, offset, bytes.length - offset);
                    if (n <= 0) throw new IOException("封面不完整");
                    offset += n;
                }
            }
        } else {
            if (target.length() > 10 * 1024 * 1024) throw new IOException("封面大小无效");
            bytes = Files.readAllBytes(target.toPath());
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new IOException("封面损坏");
        return bitmap;
    }

    public static void delete(Context context, DownloadTask task) {
        File target = file(context, task);
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        RETRY_AFTER.remove(task.id);
    }
}
