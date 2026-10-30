package io.github.fugitive844.decotv.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.model.Poster;

import java.util.ArrayList;
import java.util.List;

/** 海报卡片：2:3 封面，左上角评分、右下角备注，下面标题和年份。首页横向一栏与片库网格共用。 */
final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
    interface Click { void on(Poster p); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title, meta, rate, badge;

        Holder(View root, ImageView image, TextView title, TextView meta, TextView rate, TextView badge) {
            super(root);
            this.image = image;
            this.title = title;
            this.meta = meta;
            this.rate = rate;
            this.badge = badge;
        }
    }

    private final List<Poster> items = new ArrayList<>();
    /** 横向一栏时的卡片宽度（dp）；0 表示网格中撑满一格。 */
    private final int widthDp;
    private final Click click;

    PosterAdapter(int widthDp, Click click) {
        this.widthDp = widthDp;
        this.click = click;
    }

    void set(List<Poster> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    List<Poster> items() { return items; }

    /** 网格列数：手机竖屏 3 列，更宽的屏幕每 120dp 一列。 */
    static GridLayoutManager grid(Context c) {
        int widthDp = c.getResources().getConfiguration().screenWidthDp;
        return new GridLayoutManager(c, Math.max(3, widthDp / 120));
    }

    /** 海报上的评分角标：深色半透明底、琥珀色字，压在图片上，深浅模式都不变。 */
    static TextView rateTag(Context c) {
        TextView t = Style.badge(c, "", 0xB80A0C10, 0xFFFFB020);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int m = Ui.dp(c, 6);
        lp.setMargins(m, m, m, m);
        t.setLayoutParams(lp);
        return t;
    }

    /** 海报底部的备注条：黑色渐变上的白字，如「更新至 12 集」「HD」。 */
    static TextView bottomTag(Context c) {
        TextView t = Ui.text(c, "", 11, false);
        t.setTextColor(0xFFE6E9EF);
        t.setGravity(Gravity.END);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 5));
        t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xA6000000}));
        t.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return t;
    }

    /** 圆角封面框，高度按宽度的比例（numerator / denominator）。 */
    static FrameLayout coverFrame(Context c, int numerator, int denominator, float radiusDp) {
        FrameLayout frame = new FrameLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * numerator / denominator, MeasureSpec.EXACTLY));
            }
        };
        frame.setBackground(Style.round(c, Style.placeholder(c), radiusDp));
        frame.setClipToOutline(true);
        return frame;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        LinearLayout root = Ui.column(c, 0);
        int pad = Ui.dp(c, 6);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Style.pressable(c, 0, 14));
        int width = widthDp > 0 ? Ui.dp(c, widthDp + 12) : ViewGroup.LayoutParams.MATCH_PARENT;
        root.setLayoutParams(new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout frame = coverFrame(c, 3, 2, 12);
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView badge = bottomTag(c);
        frame.addView(badge);
        TextView rate = rateTag(c);
        frame.addView(rate);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Style.single(Style.text(c, "", 13, 500, Style.text(c)));
        title.setPadding(0, Ui.dp(c, 7), 0, 0);
        root.addView(title);
        TextView meta = Style.single(Style.text(c, "", 11, 400, Style.text3(c)));
        meta.setPadding(0, Ui.dp(c, 1), 0, 0);
        root.addView(meta);
        return new Holder(root, image, title, meta, rate, badge);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        Poster p = items.get(position);
        h.title.setText(p.title);
        String meta = p.year;
        if (!p.sourceName.isEmpty()) meta = meta.isEmpty() ? p.sourceName : meta + " · " + p.sourceName;
        h.meta.setText(meta);
        h.meta.setVisibility(meta.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        h.rate.setText(p.rate.isEmpty() || p.rate.startsWith("0") ? "" : "★ " + p.rate);
        h.rate.setVisibility(h.rate.getText().length() == 0 ? View.GONE : View.VISIBLE);
        h.badge.setText(p.remarks);
        h.badge.setVisibility(p.remarks.isEmpty() ? View.GONE : View.VISIBLE);
        h.itemView.setContentDescription(p.title);
        DecoApp.get().images.cover(h.image, p.poster);
        h.itemView.setOnClickListener(v -> click.on(p));
    }

    @Override public int getItemCount() { return items.size(); }
}
