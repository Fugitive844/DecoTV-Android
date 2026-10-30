package io.github.fugitive844.decotv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.chip.Chip;
import io.github.fugitive844.decotv.R;

/**
 * 界面样式：颜色取 values / values-night 的 deco_* 语义色，深浅模式自动切换；
 * 这里集中放圆角卡片、胶囊标签、图标按钮、分组列表行等，各页面共用。
 */
final class Style {
    private Style() {}

    static int bg(Context c) { return color(c, R.color.deco_bg); }
    static int surface(Context c) { return color(c, R.color.deco_surface); }
    static int surface2(Context c) { return color(c, R.color.deco_surface2); }
    static int line(Context c) { return color(c, R.color.deco_line); }
    static int text(Context c) { return color(c, R.color.deco_text); }
    static int text2(Context c) { return color(c, R.color.deco_text2); }
    static int text3(Context c) { return color(c, R.color.deco_text3); }
    static int text4(Context c) { return color(c, R.color.deco_text4); }
    static int accent(Context c) { return color(c, R.color.deco_accent); }
    static int onAccent(Context c) { return color(c, R.color.deco_on_accent); }
    static int accentSoft(Context c) { return color(c, R.color.deco_accent_soft); }
    static int star(Context c) { return color(c, R.color.deco_star); }
    static int danger(Context c) { return color(c, R.color.deco_danger); }

    static int color(Context c, int res) { return ContextCompat.getColor(c, res); }

    /** 状态栏与导航栏跟随页面底色，浅色模式用深色图标。 */
    static void systemBars(Activity a) {
        boolean light = a.getResources().getBoolean(R.bool.deco_light_bars);
        a.getWindow().setStatusBarColor(bg(a));
        a.getWindow().setNavigationBarColor(color(a, R.color.deco_nav));
        androidx.core.view.WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(a.getWindow(), a.getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Ui.dp(c, radiusDp));
        return d;
    }

    static GradientDrawable stroke(Context c, int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable d = round(c, fill, radiusDp);
        d.setStroke(Math.max(1, Ui.dp(c, strokeDp)), strokeColor);
        return d;
    }

    /** 带点按水波纹的圆角背景。 */
    static Drawable pressable(Context c, int color, float radiusDp) {
        GradientDrawable mask = round(c, 0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(0x22888888), round(c, color, radiusDp), mask);
    }

    /** 加粗程度：Android 9 起支持 900 这样的字重，旧系统退回粗体。 */
    static void weight(TextView t, int weight) {
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        else t.setTypeface(weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    static TextView text(Context c, String value, float sp, int weight, int color) {
        TextView t = Ui.text(c, value, sp, false);
        weight(t, weight);
        t.setTextColor(color);
        return t;
    }

    static TextView single(TextView t) {
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** 一级页面的大标题，如「片库」「直播」。 */
    static TextView pageTitle(Context c, String value) {
        TextView t = text(c, value, 26, 900, text(c));
        return t;
    }

    /** 首页等栏目的标题行：左边标题，右边可选的「更多 ›」。 */
    static LinearLayout sectionHead(Context c, String title, String more, String moreDescription, View.OnClickListener onMore) {
        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.dp(c, 16), Ui.dp(c, 24), Ui.dp(c, 8), Ui.dp(c, 10));
        head.addView(text(c, title, 18, 700, text(c)), Ui.weight(1));
        if (more != null) {
            TextView m = text(c, more, 13, 400, text2(c));
            int p = Ui.dp(c, 8);
            m.setPadding(p, p, p, p);
            m.setContentDescription(moreDescription);
            m.setOnClickListener(onMore);
            m.setBackground(pressable(c, 0, 12));
            head.addView(m);
        }
        return head;
    }

    /** 圆形图标按钮，44dp 点击区域。 */
    static ImageView iconButton(Context c, int icon, String description, int fill, int tint) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        int p = Ui.dp(c, 11);
        b.setPadding(p, p, p, p);
        b.setContentDescription(description);
        b.setBackground(pressable(c, fill, 22));
        int size = Ui.dp(c, 44);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return b;
    }

    static ImageView icon(Context c, int icon, int tint, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(icon);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, sizeDp), Ui.dp(c, sizeDp)));
        return v;
    }

    /** 小角标，如评分、更新集数、「直连」。 */
    static TextView badge(Context c, String value, int fill, int color) {
        TextView t = text(c, value, 11, 700, color);
        int h = Ui.dp(c, 6), v = Ui.dp(c, 2);
        t.setPadding(h, v, h, v);
        t.setBackground(round(c, fill, 6));
        single(t);
        return t;
    }

    /** 胶囊按钮（筛选、分组、倍速等）：选中为强调色淡底，未选为控件底。 */
    static void pill(TextView t, boolean selected) {
        Context c = t.getContext();
        t.setTextColor(selected ? accent(c) : text2(c));
        weight(t, selected ? 700 : 400);
        t.setBackground(pressable(c, selected ? accentSoft(c) : surface2(c), 16));
    }

    static TextView pill(Context c, String value, boolean selected) {
        TextView t = Ui.text(c, value, 13, false);
        int h = Ui.dp(c, 14), v = Ui.dp(c, 7);
        t.setPadding(h, v, h, v);
        t.setGravity(Gravity.CENTER);
        single(t);
        pill(t, selected);
        return t;
    }

    /** Material 胶囊（ChipGroup 单选用）：去掉描边，配色同 {@link #pill}。 */
    static void chip(Chip chip) {
        Context c = chip.getContext();
        int[][] states = {{android.R.attr.state_checked}, {}};
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{accentSoft(c), surface2(c)}));
        chip.setTextColor(new ColorStateList(states, new int[]{accent(c), text2(c)}));
        chip.setChipStrokeWidth(0);
        chip.setChipCornerRadius(Ui.dp(c, 16));
        chip.setRippleColor(ColorStateList.valueOf(0x22888888));
    }

    /** 圆角搜索框：左边放大镜，底为控件色。 */
    static LinearLayout searchBox(Context c, EditText input, String hint) {
        LinearLayout box = Ui.row(c);
        box.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
        box.setBackground(round(c, surface2(c), 23));
        box.addView(icon(c, R.drawable.ic_nav_search, text3(c), 18));
        input.setHint(hint);
        input.setHintTextColor(text3(c));
        input.setTextColor(text(c));
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setBackground(null);
        input.setPadding(Ui.dp(c, 10), 0, 0, 0);
        box.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1));
        return box;
    }

    /** 圆角卡片容器（分组列表、设置项）。 */
    static LinearLayout card(Context c) {
        LinearLayout card = Ui.column(c, 0);
        card.setBackground(round(c, surface(c), 16));
        card.setClipToOutline(true);
        return card;
    }

    static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(c, l), Ui.dp(c, t), Ui.dp(c, r), Ui.dp(c, b));
        return lp;
    }

    /** 卡片上方的小标题，如设置页的「外观」「播放」。 */
    static TextView caption(Context c, String value) {
        TextView t = text(c, value, 13, 500, text3(c));
        t.setPadding(Ui.dp(c, 20), Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 8));
        return t;
    }

    /**
     * 分组列表的一行：可选图标、标题、右侧摘要、「›」。返回的行里 tag 存右侧摘要 TextView，用 {@link #rowValue} 改。
     */
    static LinearLayout listRow(Context c, int icon, String title, String value, View.OnClickListener l) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 56));
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 6));
        row.setBackground(pressable(c, 0, 0));
        row.setContentDescription(title);
        if (l != null) row.setOnClickListener(l);
        if (icon != 0) {
            ImageView i = icon(c, icon, text2(c), 20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32));
            lp.rightMargin = Ui.dp(c, 12);
            i.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
            i.setBackground(round(c, surface2(c), 10));
            row.addView(i, lp);
        }
        row.addView(text(c, title, 15, 400, text(c)), Ui.weight(1));
        TextView right = text(c, value == null ? "" : value, 13, 400, text3(c));
        single(right);
        row.addView(right);
        row.setTag(right);
        if (l != null) {
            ImageView chevron = icon(c, R.drawable.ic_chevron_right, text4(c), 18);
            ((LinearLayout.LayoutParams) chevron.getLayoutParams()).leftMargin = Ui.dp(c, 4);
            row.addView(chevron);
        }
        return row;
    }

    static void rowValue(View row, String value) { ((TextView) row.getTag()).setText(value); }

    /** 分组卡片里行与行之间的分隔线。 */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(line(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(c, 0.5f)));
        lp.leftMargin = Ui.dp(c, 14);
        v.setLayoutParams(lp);
        return v;
    }

    /** 主按钮（强调色实心胶囊）。 */
    static void primaryButton(com.google.android.material.button.MaterialButton b) {
        Context c = b.getContext();
        b.setBackgroundTintList(ColorStateList.valueOf(accent(c)));
        b.setTextColor(onAccent(c));
        b.setCornerRadius(Ui.dp(c, 24));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(Ui.dp(c, 48));
        weight(b, 700);
    }

    /** 次按钮（控件底、主文字色）。 */
    static void tonalButton(com.google.android.material.button.MaterialButton b) {
        Context c = b.getContext();
        b.setBackgroundTintList(ColorStateList.valueOf(surface2(c)));
        b.setTextColor(text(c));
        b.setStrokeWidth(0);
        b.setCornerRadius(Ui.dp(c, 24));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(Ui.dp(c, 44));
    }

    /** 细进度条（继续观看）。 */
    static final class Progress extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private float fraction;

        Progress(Context c) {
            super(c);
            paint.setColor(accent(c));
            setBackgroundColor(0x40FFFFFF);
        }

        void set(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            canvas.drawRect(0, 0, getWidth() * fraction, getHeight(), paint);
        }
    }

    /** 渐变遮罩：从透明过渡到页面底色，用于大图底部。 */
    static GradientDrawable fade(Context c, boolean toBottom) {
        int base = bg(c) & 0x00FFFFFF;
        int[] colors = {base, base | 0xD9000000, bg(c)};
        return new GradientDrawable(toBottom ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.BOTTOM_TOP, colors);
    }

    /** 海报占位底色。 */
    static int placeholder(Context c) { return surface2(c); }
}
