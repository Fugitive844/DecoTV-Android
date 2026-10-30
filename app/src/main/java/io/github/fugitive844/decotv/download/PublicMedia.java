package io.github.fugitive844.decotv.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** 原格式 / MP4 保存到系统「影片/DecoTV」，相册和其他播放器都能看到。 */
final class PublicMedia {
    static final String FOLDER = "DecoTV";

    private PublicMedia() {}

    static String mime(String container) {
        switch (container.toLowerCase(Locale.ROOT)) {
            case "ts": return "video/mp2t";
            case "flv": return "video/x-flv";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            case "avi": return "video/x-msvideo";
            default: return "video/mp4";
        }
    }

    /** 复制到公共媒体库，返回 content:// 地址（Android 10 及以上）或文件路径。 */
    static String save(Context context, File source, String name, String container) throws IOException {
        String display = name + "." + container;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, display);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime(container));
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) throw new IOException("无法写入系统影片目录");
            try (OutputStream out = resolver.openOutputStream(uri); InputStream in = new FileInputStream(source)) {
                if (out == null) throw new IOException("无法写入系统影片目录");
                copy(in, out);
            } catch (IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri.toString();
        }
        // Android 8、9：需要存储权限，由调用方先申请
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 " + dir);
        File target = new File(dir, display);
        for (int i = 1; target.exists(); i++) target = new File(dir, name + "_" + i + "." + container);
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(context, new String[]{target.getPath()}, new String[]{mime(container)}, null);
        return target.getPath();
    }

    static void delete(Context context, String output) {
        if (output.startsWith("content://")) {
            try {
                context.getContentResolver().delete(Uri.parse(output), null, null);
            } catch (RuntimeException ignored) {
                // 用户已在相册里删除
            }
        } else if (!output.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(output).delete();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
