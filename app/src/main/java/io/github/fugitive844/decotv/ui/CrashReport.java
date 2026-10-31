package io.github.fugitive844.decotv.ui;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

import androidx.appcompat.app.AlertDialog;

import io.github.fugitive844.decotv.BuildConfig;
import io.github.fugitive844.decotv.DecoApp;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 记录上次异常退出的原因，下次打开时显示，用户不连电脑也能把原因发给开发者。 */
public final class CrashReport {
    private static final int MAX_LENGTH = 6000;

    private CrashReport() {}

    private static File file(Context context) { return new File(context.getFilesDir(), "last-crash.txt"); }

    /** App 启动时调用：未捕获的异常先写入文件，再交给系统原来的处理。 */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (PrintWriter out = new PrintWriter(file(context), "UTF-8")) {
                out.println("线程：" + thread.getName());
                error.printStackTrace(out);
            } catch (Exception ignored) {
                // 记录失败不影响原来的崩溃处理
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** 系统记录的退出原因（Android 11 起），能覆盖原生崩溃和被系统杀掉这些捕获不到的情况。 */
    private static String systemReason(Context context) {
        if (Build.VERSION.SDK_INT < 30) return "";
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 1);
        if (exits.isEmpty()) return "";
        ApplicationExitInfo exit = exits.get(0);
        switch (exit.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
            case ApplicationExitInfo.REASON_SIGNALED:
                break;
            default:
                return "";
        }
        if (exit.getTimestamp() <= DecoApp.get().prefs.lastExitSeen()) return "";
        DecoApp.get().prefs.setLastExitSeen(exit.getTimestamp());
        return "系统记录：reason=" + exit.getReason() + " status=" + exit.getStatus() + " pss=" + exit.getPss() / 1024 + "MB rss="
                + exit.getRss() / 1024 + "MB " + exit.getDescription() + "\n";
    }

    /** 有未显示的异常退出记录时弹窗，可复制全文。 */
    static void showIfAny(Context activity) {
        String stack = "";
        File file = file(activity);
        try {
            if (file.exists()) stack = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 读不到就只显示系统记录
        }
        file.delete();
        String reason;
        try {
            reason = systemReason(activity);
        } catch (RuntimeException e) {
            reason = "";
        }
        if (stack.isEmpty() && reason.isEmpty()) return;
        String text = "版本 " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n" + reason + stack;
        String shown = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        new AlertDialog.Builder(activity).setTitle("上次异常退出").setMessage(shown)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制", (d, w) -> {
                    activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("DecoTV 异常", shown));
                    Ui.toast(activity, "已复制，发给开发者即可");
                }).show();
    }
}
