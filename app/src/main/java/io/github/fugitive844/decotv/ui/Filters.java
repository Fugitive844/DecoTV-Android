package io.github.fugitive844.decotv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

/**
 * 片库的筛选行，与网页端一致：左边行名（分类 / 地区 / 类型…），右边一排胶囊单选按钮，放不下时横向滑动。
 */
final class Filters {
    interface Change { void on(String value); }

    /** 一行筛选。value 是传给接口的值，label 是显示文字。 */
    static final class Row {
        final LinearLayout view;
        private final ChipGroup group;
        private final String[] values;
        private final Chip[] chips;
        private String value;

        Row(Context c, String name, String[] labels, String[] values, String selected, Change change) {
            this.values = values;
            view = Ui.row(c);
            view.setGravity(Gravity.CENTER_VERTICAL);
            TextView head = Style.text(c, name, 12, 500, Style.text3(c));
            view.addView(head, new LinearLayout.LayoutParams(Ui.dp(c, 36), LinearLayout.LayoutParams.WRAP_CONTENT));
            group = new ChipGroup(c);
            group.setSingleLine(true);
            group.setSingleSelection(true);
            group.setSelectionRequired(true);
            group.setChipSpacingHorizontal(Ui.dp(c, 6));
            chips = new Chip[labels.length];
            for (int i = 0; i < labels.length; i++) {
                Chip chip = new Chip(c);
                chip.setId(View.generateViewId());
                chip.setText(labels[i]);
                chip.setCheckable(true);
                chip.setCheckedIconVisible(false);
                chip.setEnsureMinTouchTargetSize(false);
                Style.chip(chip);
                chip.setChipMinHeight(Ui.dp(c, 30));
                chips[i] = chip;
                group.addView(chip);
            }
            value = selected;
            check(selected);
            group.setOnCheckedStateChangeListener((g, ids) -> {
                if (ids.isEmpty()) return;
                for (int i = 0; i < chips.length; i++) {
                    if (chips[i].getId() == ids.get(0) && !values[i].equals(value)) {
                        value = values[i];
                        change.on(value);
                    }
                }
            });
            HorizontalScrollView scroll = new HorizontalScrollView(c);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(group);
            view.addView(scroll, Ui.weight(1));
        }

        private void check(String v) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(v)) group.check(chips[i].getId());
        }

        String value() { return value; }

        /** 突出某一项（动漫的「今天」标绿色）。 */
        void highlight(String v, int color) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(v)) chips[i].setTextColor(color);
        }

        void setVisible(boolean on) { view.setVisibility(on ? View.VISIBLE : View.GONE); }
    }

    /** label 与 value 相同时的简写。 */
    static Row same(Context c, String name, String[] labels, String selected, Change change) {
        return new Row(c, name, labels, labels, selected, change);
    }

    private Filters() {}
}
