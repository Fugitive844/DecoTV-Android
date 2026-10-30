package io.github.fugitive844.decotv.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * 常见播放器手势：左半屏上下滑调亮度、右半屏上下滑调音量、左右滑快进快退（每档 5 秒）、
 * 长按右半屏临时 2 倍速、双击暂停/继续。单击仍交给 PlayerView 显示或隐藏控制栏。
 * 亮度只改当前窗口，不改系统设置。
 */
final class PlayerGestures {
    interface Host {
        /** 点播可拖动进度；直播返回 false，只保留亮度和音量。 */
        boolean seekable();
        long position();
        long duration();
        void seekTo(long ms);
        boolean playing();
        void togglePlay();
        /** 长按右半屏开始 / 结束临时 2 倍速；不支持时忽略。 */
        void holdSpeed(boolean on);
    }

    private static final int NONE = 0, SEEK = 1, BRIGHTNESS = 2, VOLUME = 3;
    private static final long SEEK_STEP_MS = 5000;

    private final Activity activity;
    private final Host host;
    private final View target;
    private final TextView indicator;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GestureDetector detector;
    private final int slop;
    private int mode = NONE;
    private float downX, downY, startBrightness;
    private int startVolume;
    private long startPosition, seekTarget = -1;
    private boolean holding, ignored;
    private final Runnable hide = () -> PlayerGestures.this.indicatorView().setVisibility(View.GONE);

    /**
     * 播放区容器：先把触摸交给手势判断，一旦认定是滑动或长按就拦截，控制栏等子 View 收到取消；
     * 单击仍交给 PlayerView 显示或隐藏控制栏，控制栏上的按钮照常可点。
     */
    static final class Frame extends FrameLayout {
        private PlayerGestures gestures;
        private final java.util.List<View> exempt = new java.util.ArrayList<>();
        private boolean skip;

        Frame(android.content.Context c) { super(c); }

        /** 在这些子 View（如全屏浮层列表）上起手的触摸不当作手势，交给子 View 自己滚动和点击。 */
        void exempt(View child) { exempt.add(child); }

        private boolean onExempt(MotionEvent e) {
            for (View v : exempt) {
                if (v.getVisibility() == VISIBLE && e.getX() >= v.getLeft() && e.getX() < v.getRight()
                        && e.getY() >= v.getTop() && e.getY() < v.getBottom()) return true;
            }
            return false;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) skip = onExempt(e);
            return !skip && gestures != null && gestures.handle(e);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!skip && gestures != null) gestures.handle(e);
            return true;
        }
    }

    PlayerGestures(Activity activity, Frame box, Host host) {
        this.activity = activity;
        this.host = host;
        this.target = box;
        box.gestures = this;
        audio = activity.getSystemService(AudioManager.class);
        slop = ViewConfiguration.get(activity).getScaledTouchSlop() * 2;
        indicator = Ui.text(activity, "", 16, true);
        indicator.setTextColor(0xFFFFFFFF);
        indicator.setGravity(Gravity.CENTER);
        int h = Ui.dp(activity, 16), v = Ui.dp(activity, 10);
        indicator.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(activity, 12));
        bg.setColor(0xAA202020);
        indicator.setBackground(bg);
        indicator.setVisibility(View.GONE);
        box.addView(indicator, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        detector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(@NonNull MotionEvent e) { return true; }

            @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (ignored) return false;
                host.togglePlay();
                show(host.playing() ? "继续播放" : "已暂停", true);
                return true;
            }

            @Override public void onLongPress(@NonNull MotionEvent e) {
                if (ignored || mode != NONE || e.getX() < target.getWidth() / 2f || !host.playing()) return;
                holding = true;
                host.holdSpeed(true);
                show("2 倍速播放中 ▶▶", false);
            }
        });
    }

    private View indicatorView() { return indicator; }

    /** 处理一个触摸事件，返回是否已进入手势（需要拦截）。 */
    private boolean handle(MotionEvent e) {
        detector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                mode = NONE;
                // 贴近上下边缘起手多半是系统手势（下拉通知栏、返回桌面），不处理
                int edge = Ui.dp(activity, 24);
                ignored = downY < edge || downY > target.getHeight() - edge;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (ignored || holding) return holding;
                return onMove(e.getX() - downX, e.getY() - downY);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean consumed = mode != NONE || holding;
                if (holding) {
                    holding = false;
                    host.holdSpeed(false);
                }
                if (mode == SEEK && seekTarget >= 0) host.seekTo(seekTarget);
                mode = NONE;
                seekTarget = -1;
                if (consumed) hideLater();
                return consumed;
            default:
                return mode != NONE;
        }
    }

    private boolean onMove(float dx, float dy) {
        int width = Math.max(1, target.getWidth()), height = Math.max(1, target.getHeight());
        if (mode == NONE) {
            if (Math.abs(dx) < slop && Math.abs(dy) < slop) return false;
            // 长按后再移动不算滑动手势
            if (holding) return true;
            if (Math.abs(dx) > Math.abs(dy)) {
                if (!host.seekable() || host.duration() <= 0) return false;
                mode = SEEK;
                startPosition = host.position();
            } else if (downX < width / 2f) {
                mode = BRIGHTNESS;
                startBrightness = currentBrightness();
            } else {
                mode = VOLUME;
                startVolume = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
            if (target.getParent() != null) target.getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (mode == SEEK) {
            // 每滑过 4% 画面宽度算一档 5 秒，刚越过判定距离就是 ±5 秒
            int steps = Math.max(1, Math.round(Math.abs(dx) / (width * 0.04f)));
            long delta = (dx > 0 ? 1 : -1) * steps * SEEK_STEP_MS;
            long duration = host.duration();
            seekTarget = Math.max(0, Math.min(duration - 1000, startPosition + delta));
            long shown = seekTarget - startPosition;
            show((shown >= 0 ? "快进 " : "快退 ") + Math.abs(shown) / 1000 + " 秒\n" + clock(seekTarget) + " / " + clock(duration), false);
        } else if (mode == BRIGHTNESS) {
            float value = Math.max(0.01f, Math.min(1f, startBrightness - dy / (height * 0.8f)));
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.screenBrightness = value;
            activity.getWindow().setAttributes(lp);
            show("亮度 " + Math.round(value * 100) + "%", false);
        } else if (mode == VOLUME && audio != null) {
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int value = Math.max(0, Math.min(max, startVolume + Math.round(-dy / (height * 0.8f) * max)));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
            show("音量 " + Math.round(value * 100f / max) + "%", false);
        }
        return true;
    }

    private float currentBrightness() {
        float window = activity.getWindow().getAttributes().screenBrightness;
        if (window >= 0) return window;
        try {
            return Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    private void show(String text, boolean autoHide) {
        handler.removeCallbacks(hide);
        indicator.setText(text);
        indicator.setVisibility(View.VISIBLE);
        if (autoHide) hideLater();
    }

    private void hideLater() {
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, 600);
    }

    void release() { handler.removeCallbacksAndMessages(null); }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }
}
