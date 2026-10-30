package io.github.fugitive844.decotv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.net.ApiClient;

import java.util.Locale;

/** 纯代码构建界面用的小工具。 */
final class Ui {
    private Ui() {}

    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, String value, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout column(Context c, int padDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padDp);
        l.setPadding(p, p, p, p);
        return l;
    }

    /**
     * 表单输入框。先设单行再设输入类型：setSingleLine 会替换掉密码遮罩，顺序反了密码会明文显示。
     * 密码框带「显示 / 隐藏」按钮。
     */
    static com.google.android.material.textfield.TextInputEditText input(com.google.android.material.textfield.TextInputLayout layout, int type) {
        com.google.android.material.textfield.TextInputEditText edit = new com.google.android.material.textfield.TextInputEditText(layout.getContext());
        edit.setSingleLine(true);
        edit.setInputType(type);
        boolean secret = (type & android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
        if (secret) edit.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        layout.addView(edit);
        if (secret) layout.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        return edit;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    static LinearLayout.LayoutParams fill(float w) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, w);
    }

    static TextView empty(Context c, String value) {
        TextView t = text(c, value, 14, false);
        t.setGravity(Gravity.CENTER);
        int p = dp(c, 24);
        t.setPadding(p, p, p, p);
        t.setVisibility(View.GONE);
        return t;
    }

    /** 开发者与版权信息，登录页与设置页共用。 */
    static TextView copyright(Context c) {
        int year = Math.max(2026, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
        TextView t = text(c, "开发者：" + io.github.fugitive844.decotv.BuildConfig.DEVELOPER + "\n© " + (year > 2026 ? "2026-" + year : "2026") + " " + io.github.fugitive844.decotv.BuildConfig.COPYRIGHT_OWNER + " 版权所有", 12, false);
        t.setGravity(Gravity.CENTER);
        t.setAlpha(0.5f);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    static void toast(Context c, String message) { Toast.makeText(c, message, Toast.LENGTH_SHORT).show(); }

    /** 统一处理失败：登录失效时回到登录页。 */
    static void fail(Activity activity, Exception error) {
        if (error instanceof ApiClient.AuthException) {
            DecoApp.get().prefs.logout();
            toast(activity, error.getMessage());
            Intent intent = new Intent(activity, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
            return;
        }
        String message = error.getMessage();
        toast(activity, message == null || message.isEmpty() ? "网络错误：" + error.getClass().getSimpleName() : message);
    }

    /** 沉浸式全屏：隐藏状态栏与导航栏，从边缘滑动可临时呼出；刘海屏铺满到短边。 */
    static void immersive(Activity activity, boolean on) {
        android.view.Window window = activity.getWindow();
        androidx.core.view.WindowInsetsControllerCompat bars =
                androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView());
        if (on) {
            bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            bars.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = on ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(attrs);
        }
    }

    /** 播放画面右上角的半透明角标，像台标一样常驻，标明当前是直连还是经服务器中转。 */
    static TextView routeBadge(Context c) {
        TextView badge = text(c, "", 12, true);
        badge.setTextColor(0xFFFFFFFF);
        int h = dp(c, 8), v = dp(c, 3);
        badge.setPadding(h, v, h, v);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(c, 10));
        bg.setColor(0x55000000);
        bg.setStroke(dp(c, 1), 0x66FFFFFF);
        badge.setBackground(bg);
        badge.setAlpha(0.7f);
        badge.setVisibility(View.GONE);
        return badge;
    }

    /** relayed 为 null 表示尚未起播，隐藏角标。 */
    static void setRoute(TextView badge, Boolean relayed) {
        if (relayed == null) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setText(relayed ? "中转" : "直连");
        ((android.graphics.drawable.GradientDrawable) badge.getBackground()).setStroke(dp(badge.getContext(), 1),
                relayed ? 0x99FFB74D : 0x9981C784);
        badge.setVisibility(View.VISIBLE);
    }

    static android.widget.FrameLayout.LayoutParams badgeParams(Context c) {
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = dp(c, 12);
        lp.rightMargin = dp(c, 12);
        return lp;
    }

    /** 播放控制栏的进度条：薄荷绿已播放、白色拖动点；画面始终是深色，不随深浅模式变。 */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static void stylePlayer(androidx.media3.ui.PlayerView view) {
        View bar = view.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (bar instanceof androidx.media3.ui.DefaultTimeBar) {
            androidx.media3.ui.DefaultTimeBar t = (androidx.media3.ui.DefaultTimeBar) bar;
            t.setPlayedColor(0xFF3DDB9A);
            t.setScrubberColor(0xFFFFFFFF);
            t.setBufferedColor(0x66FFFFFF);
            t.setUnplayedColor(0x33FFFFFF);
        }
    }

    /** 竖屏小窗播放器高度：16:9，下方留给简介、选集和换源。 */
    static int inlinePlayerHeight(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.min(m.widthPixels, m.heightPixels) * 9 / 16;
    }

    static String clock(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }
}
