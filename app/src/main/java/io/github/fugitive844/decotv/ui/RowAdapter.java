package io.github.fugitive844.decotv.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 一行：可选的图片 + 标题 + 副标题。 */
final class RowAdapter<T> extends RecyclerView.Adapter<RowAdapter.Holder> {
    interface Binder<T> { void bind(Holder holder, T item); }
    interface Click<T> { void on(T item, int position); }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title, subtitle;

        Holder(View root, ImageView image, TextView title, TextView subtitle) {
            super(root);
            this.image = image;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private final List<T> items = new ArrayList<>();
    private final int imageWidthDp, imageHeightDp;
    private final Binder<T> binder;
    private Click<T> click, longClick;

    RowAdapter(int imageWidthDp, int imageHeightDp, Binder<T> binder) {
        this.imageWidthDp = imageWidthDp;
        this.imageHeightDp = imageHeightDp;
        this.binder = binder;
    }

    private boolean cards;

    /** 每行画成独立的圆角卡片（搜索结果、播放源），默认是平铺的列表行。 */
    RowAdapter<T> cards() { this.cards = true; return this; }

    RowAdapter<T> onClick(Click<T> click) { this.click = click; return this; }
    RowAdapter<T> onLongClick(Click<T> click) { this.longClick = click; return this; }

    void set(List<T> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    List<T> items() { return items; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        LinearLayout root = Ui.row(c);
        int pad = Ui.dp(c, cards ? 10 : 16);
        root.setPadding(pad, Ui.dp(c, cards ? 10 : 8), pad, Ui.dp(c, cards ? 10 : 8));
        RecyclerView.LayoutParams rp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (cards) rp.setMargins(Ui.dp(c, 16), Ui.dp(c, 5), Ui.dp(c, 16), Ui.dp(c, 5));
        root.setLayoutParams(rp);
        root.setBackground(Style.pressable(c, cards ? Style.surface(c) : 0, cards ? 16 : 0));
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (imageWidthDp > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, imageWidthDp), Ui.dp(c, imageHeightDp));
            lp.rightMargin = Ui.dp(c, 12);
            image.setBackground(Style.round(c, Style.placeholder(c), 10));
            image.setClipToOutline(true);
            root.addView(image, lp);
        }
        LinearLayout texts = Ui.column(c, 0);
        TextView title = Style.text(c, "", 15, 700, Style.text(c));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView subtitle = Style.text(c, "", 12, 400, Style.text2(c));
        subtitle.setLineSpacing(Ui.dp(c, 2), 1f);
        subtitle.setPadding(0, Ui.dp(c, 3), 0, 0);
        texts.addView(title);
        texts.addView(subtitle);
        root.addView(texts, Ui.weight(1));
        return new Holder(root, image, title, subtitle);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        T item = items.get(position);
        binder.bind(holder, item);
        holder.itemView.setOnClickListener(v -> { if (click != null) click.on(item, holder.getBindingAdapterPosition()); });
        holder.itemView.setOnLongClickListener(v -> {
            if (longClick == null) return false;
            longClick.on(item, holder.getBindingAdapterPosition());
            return true;
        });
    }

    @Override public int getItemCount() { return items.size(); }
}
