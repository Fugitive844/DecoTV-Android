package io.github.fugitive844.decotv.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/** 加载和中途卡顿缓冲时，在转圈下方显示播放器的实时下载网速；进入播放后隐藏。 */
final class BufferSpeed {
    private final TextView label;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBytes, lastTime, lastSpeed;
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long bytes = rxBytes(), now = SystemClock.elapsedRealtime();
            long elapsed = Math.max(1, now - lastTime);
            lastSpeed = Math.max(0, (bytes - lastBytes) * 1000 / elapsed);
            label.setText("缓冲中 " + format(lastSpeed));
            lastBytes = bytes;
            lastTime = now;
            handler.postDelayed(this, 1000);
        }
    };

    BufferSpeed(Context c) {
        label = Ui.text(c, "缓冲中…", 13, false);
        label.setTextColor(0xFFFFFFFF);
        int h = Ui.dp(c, 8), v = Ui.dp(c, 3);
        label.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(c, 10));
        bg.setColor(0x99303030);
        label.setBackground(bg);
        label.setVisibility(View.GONE);
    }

    View view() { return label; }

    FrameLayout.LayoutParams params(Context c) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.topMargin = Ui.dp(c, 72);
        return lp;
    }

    /** 根据播放器状态显示或隐藏：只有缓冲中才显示。 */
    void update(boolean buffering) {
        if (buffering) start();
        else stop();
    }

    void start() {
        if (running) return;
        running = true;
        lastBytes = rxBytes();
        lastTime = SystemClock.elapsedRealtime();
        lastSpeed = 0;
        label.setText("缓冲中…");
        label.setVisibility(View.VISIBLE);
        handler.postDelayed(tick, 1000);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
        label.setVisibility(View.GONE);
    }

    /** 最近一秒的缓冲网速（字节/秒）；没在缓冲时为 0。 */
    long bytesPerSecond() { return running ? lastSpeed : 0; }

    /**
     * 起播超时后是否还要继续等：设置了网速条件、当前网速不低于它、且总等待没超过上限。
     * 超时和网速低两个条件都满足才算失败（才会自动换源）。
     */
    boolean keepWaiting(long waitedMs) {
        int min = io.github.fugitive844.decotv.DecoApp.get().prefs.switchMinSpeedKb();
        return min > 0 && waitedMs < MAX_WAIT_MS && bytesPerSecond() >= min * 1024L;
    }

    /** 网速一直够但始终播不起来时，最多等这么久再判失败，避免无限转圈。 */
    static final long MAX_WAIT_MS = 60000, RECHECK_MS = 3000;

    private static long rxBytes() { return Media.received(); }

    static String format(long bytesPerSecond) {
        if (bytesPerSecond < 0) bytesPerSecond = 0;
        if (bytesPerSecond >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576.0);
        return String.format(Locale.ROOT, "%d KB/s", bytesPerSecond / 1024);
    }
}
