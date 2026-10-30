package io.github.fugitive844.decotv.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.fugitive844.decotv.net.Tasks;

/**
 * 打开影片前的查找过程：转圈遮罩挡住其他操作，可点「取消」或返回键放弃，超时自动放弃。
 * 同一时间只允许一个；放弃后的查找结果直接丢弃，不会再进入播放页。
 * 成功跳转后短时间内再点也忽略，避免连点打开多个播放页。
 */
final class Opening {
    private static final long TIMEOUT_MS = 30000, COOLDOWN_MS = 1500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Opening active;
    private static long lastLaunch;

    final Activity activity;
    private final AlertDialog dialog;
    private final TextView message;
    private final Runnable timeout = () -> abandon("查找播放源超时，请稍后重试");
    private boolean over;
    private final long started = SystemClock.elapsedRealtime();

    /** 已有打开过程或刚刚跳转过时返回 null，调用方直接忽略这次点击。 */
    static Opening begin(Activity activity, String text) {
        if (active != null || SystemClock.elapsedRealtime() - lastLaunch < COOLDOWN_MS) return null;
        if (activity.isFinishing()) return null;
        active = new Opening(activity, text);
        return active;
    }

    /** 不需要等待、直接跳转的入口也走同一个防连点判断。 */
    static boolean launchNow() {
        if (active != null || SystemClock.elapsedRealtime() - lastLaunch < COOLDOWN_MS) return false;
        lastLaunch = SystemClock.elapsedRealtime();
        return true;
    }

    /** 测试用：当前是否有等待框。 */
    static boolean busy() { return active != null; }

    private Opening(Activity activity, String text) {
        this.activity = activity;
        LinearLayout row = Ui.row(activity);
        int p = Ui.dp(activity, 24);
        row.setPadding(p, p, p, Ui.dp(activity, 8));
        row.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        row.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 36)));
        message = Ui.text(activity, text, 15, false);
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.leftMargin = Ui.dp(activity, 16);
        row.addView(message, lp);
        dialog = new MaterialAlertDialogBuilder(activity).setView(row)
                .setNegativeButton("取消", (d, w) -> abandon(null))
                .setOnCancelListener(d -> abandon(null))
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        MAIN.postDelayed(timeout, TIMEOUT_MS);
    }

    void message(String text) { if (!over) message.setText(text); }

    /** 包装中间步骤的回调：已放弃或页面已关闭时丢弃结果。 */
    <T> Tasks.Done<T> then(Tasks.Done<T> done) {
        return value -> { if (alive()) done.ok(value); };
    }

    Tasks.Fail orElse(Tasks.Fail fail) {
        return error -> { if (alive()) fail.fail(error); };
    }

    /** 结束等待（成功跳转或查找无果）。 */
    void end(boolean launched) {
        if (over) return;
        over = true;
        android.util.Log.i("DecoOpen", "end launched=" + launched + " after " + (SystemClock.elapsedRealtime() - started) + "ms");
        if (launched) lastLaunch = SystemClock.elapsedRealtime();
        close();
    }

    private boolean alive() {
        if (!over && (activity.isFinishing() || activity.isDestroyed())) {
            over = true;
            close();
        }
        return !over;
    }

    private void abandon(String reason) {
        if (over) return;
        over = true;
        android.util.Log.i("DecoOpen", "abandon " + (reason == null ? "by user" : reason));
        close();
        if (reason != null && !activity.isFinishing()) Ui.toast(activity, reason);
    }

    private void close() {
        MAIN.removeCallbacks(timeout);
        if (active == this) active = null;
        if (dialog.isShowing() && !activity.isDestroyed()) dialog.dismiss();
    }
}
