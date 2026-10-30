package io.github.fugitive844.decotv.ui;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 播放页的布局与全屏：
 * 手机竖屏为上下布局（播放区至少半屏）；转到横屏或点全屏按钮进入沉浸式全屏，竖版视频全屏时保持竖屏，
 * 全屏中右侧有旋转按钮可在横竖屏之间切换。平板（最短边 ≥ 600dp）不强制方向，横屏时左右布局，全屏只铺满画面。
 */
final class PlayerLayout {
    private final AppCompatActivity activity;
    private final LinearLayout root;
    private final FrameLayout box;
    private final View details;
    private final TextView rotate;
    private View left;
    private Runnable onChange;
    private boolean fullscreen, portraitVideo, portraitFullscreen, controls = true;

    PlayerLayout(AppCompatActivity activity, FrameLayout box, View details) {
        this.activity = activity;
        this.box = box;
        this.details = details;
        root = new LinearLayout(activity);
        root.setBackgroundColor(0xFF000000);
        root.addView(box);
        root.addView(details);
        rotate = circle(activity, "⟳", "旋转屏幕");
        rotate.setOnClickListener(v -> rotate());
        FrameLayout.LayoutParams lp = sideParams(activity, Gravity.END);
        box.addView(rotate, lp);
        apply();
    }

    /** 播放区两侧的圆形按钮，放在中线上方，避开控制栏中间那排播放 / 上下集按钮。 */
    static TextView circle(android.content.Context c, String text, String description) {
        TextView b = Ui.text(c, text, 20, true);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        b.setBackground(bg);
        b.setTranslationY(-Ui.dp(c, 96));
        return b;
    }

    static FrameLayout.LayoutParams sideParams(android.content.Context c, int side) {
        int size = Ui.dp(c, 44);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, side | Gravity.CENTER_VERTICAL);
        if (side == Gravity.END) lp.rightMargin = Ui.dp(c, 20);
        else lp.leftMargin = Ui.dp(c, 20);
        return lp;
    }

    /** 左侧按钮（如弹幕开关）：小窗和全屏都显示，跟随控制栏显示和隐藏。 */
    void addLeftButton(View button) {
        left = button;
        box.addView(button, sideParams(activity, Gravity.START));
        updateRotate();
    }

    View view() { return root; }

    /** 全屏或控制栏显示状态变化后回调，供播放页同步浮层。 */
    void setOnChange(Runnable r) { onChange = r; }

    boolean controlsVisible() { return controls; }

    boolean fullscreen() { return fullscreen; }

    static boolean tablet(android.content.Context c) { return c.getResources().getConfiguration().smallestScreenWidthDp >= 600; }

    /** 视频尺寸已知后调用：竖版视频（高明显大于宽）全屏时默认竖屏。 */
    void setVideoSize(int width, int height, float pixelRatio) {
        if (width <= 0 || height <= 0) return;
        boolean portrait = height > width * pixelRatio * 1.05f;
        if (portrait == portraitVideo) return;
        portraitVideo = portrait;
        if (fullscreen && !tablet(activity)) {
            portraitFullscreen = portrait;
            orient();
        }
    }

    void toggle() { set(!fullscreen); }

    void set(boolean on) {
        fullscreen = on;
        if (!tablet(activity)) {
            portraitFullscreen = on && portraitVideo;
            orient();
        }
        apply();
    }

    /** 全屏中切换横竖屏。 */
    void rotate() {
        if (!fullscreen) return;
        portraitFullscreen = !portraitFullscreen;
        orient();
    }

    private void orient() {
        int o;
        if (!fullscreen) o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        else o = portraitFullscreen ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        activity.setRequestedOrientation(o);
    }

    /** 控制栏显示时才显示旋转按钮，避免遮挡画面。 */
    void setControlsVisible(boolean visible) {
        controls = visible;
        updateRotate();
    }

    void onConfigurationChanged(Configuration config) {
        // 手机未锁定方向时转到横屏，等同点全屏
        if (!tablet(activity) && !fullscreen && config.orientation == Configuration.ORIENTATION_LANDSCAPE) fullscreen = true;
        // 转进来的横屏全屏，再转回竖屏时退出
        if (!tablet(activity) && fullscreen && !portraitFullscreen && config.orientation == Configuration.ORIENTATION_PORTRAIT
                && activity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) fullscreen = false;
        apply();
    }

    private void apply() {
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean side = !fullscreen && landscape && tablet(activity);
        root.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp, detailLp;
        if (fullscreen) {
            boxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            detailLp = new LinearLayout.LayoutParams(0, 0);
        } else if (side) {
            boxLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 62);
            detailLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 38);
        } else {
            boxLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.inlinePlayerHeight(activity));
            detailLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        }
        box.setLayoutParams(boxLp);
        details.setLayoutParams(detailLp);
        details.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        updateRotate();
        Ui.immersive(activity, fullscreen);
    }

    private void updateRotate() {
        rotate.setVisibility(fullscreen && controls && !tablet(activity) ? View.VISIBLE : View.GONE);
        if (left != null) left.setVisibility(controls ? View.VISIBLE : View.GONE);
        if (onChange != null) onChange.run();
    }
}
