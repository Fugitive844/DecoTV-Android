package io.github.fugitive844.decotv.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.net.Tasks;

/** 播放记录、收藏与搜索记录；三个标签占满一行等宽排列，「清空」在下一行，窄屏上文字也不会被截断。 */
final class RecordsPage implements MainActivity.Page {
    static final int PLAYS = 0, FAVORITES = 1, KEYWORDS = 2;
    private static final String[] HINTS = {"点按续播，长按删除", "点按播放，长按取消收藏", "点按重新搜索，长按删除"};
    private static final String[] EMPTY = {"还没有播放记录", "还没有收藏，可在播放页点「收藏」", "还没有搜索记录"};
    private static final String[] CLEAR = {"清空全部播放记录？", "清空全部收藏？", "清空全部搜索记录？"};

    private final MainActivity activity;
    private final LinearLayout root;
    private final SwipeRefreshLayout refresh;
    private final RecyclerView list;
    private final TextView empty, hint;
    private final MaterialButton clear;
    private final RowAdapter<Record> plays, favorites;
    private final RowAdapter<String> keywords;
    private int showing = PLAYS;

    RecordsPage(MainActivity activity, int initialTab) {
        this.activity = activity;
        showing = initialTab;
        root = Ui.column(activity, 8);
        MaterialButtonToggleGroup toggle = new MaterialButtonToggleGroup(activity);
        toggle.setSingleSelection(true);
        toggle.setSelectionRequired(true);
        String[] names = {"播放记录", "收藏", "搜索记录"};
        int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            MaterialButton b = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            ids[i] = View.generateViewId();
            b.setId(ids[i]);
            b.setText(names[i]);
            b.setSingleLine(true);
            b.setPadding(0, b.getPaddingTop(), 0, b.getPaddingBottom());
            toggle.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        toggle.check(ids[showing]);
        toggle.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) showing = i;
            load();
        });
        root.addView(toggle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout bar = Ui.row(activity);
        hint = Ui.text(activity, HINTS[showing], 12, false);
        hint.setAlpha(0.6f);
        bar.addView(hint, Ui.weight(1));
        clear = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
        clear.setText("清空");
        clear.setOnClickListener(v -> confirmClear());
        bar.addView(clear);
        root.addView(bar);
        empty = Ui.empty(activity, "");
        root.addView(empty);
        plays = new RowAdapter<Record>(60, 84, (h, r) -> {
            h.title.setText(r.title);
            String progress = r.totalTime > 0 ? Ui.clock(r.playTime) + " / " + Ui.clock(r.totalTime) : Ui.clock(r.playTime);
            h.subtitle.setText(r.sourceName + " · 第 " + r.index + " / " + r.totalEpisodes + " 集 · " + progress);
            DecoApp.get().images.cover(h.image, r.cover);
        }).onClick((r, i) -> PlayerActivity.open(activity, r)).onLongClick((r, i) -> confirm("删除「" + r.title + "」的播放记录？",
                () -> Tasks.run(() -> { DecoApp.get().api.deletePlayRecord(r.key); return true; }, ok -> load(), e -> Ui.fail(activity, e))));
        favorites = new RowAdapter<Record>(60, 84, (h, r) -> {
            h.title.setText(r.title);
            h.subtitle.setText(r.sourceName + (r.year.isEmpty() ? "" : " · " + r.year) + " · " + r.totalEpisodes + " 集");
            DecoApp.get().images.cover(h.image, r.cover);
        }).onClick((r, i) -> PlayerActivity.open(activity, r)).onLongClick((r, i) -> confirm("取消收藏「" + r.title + "」？",
                () -> Tasks.run(() -> { DecoApp.get().api.deleteFavorite(r.key); return true; }, ok -> load(), e -> Ui.fail(activity, e))));
        keywords = new RowAdapter<String>(0, 0, (h, k) -> {
            h.title.setText(k);
            h.subtitle.setVisibility(View.GONE);
        }).onClick((k, i) -> activity.searchFor(k)).onLongClick((k, i) -> Tasks.run(() -> {
            DecoApp.get().api.deleteSearchHistory(k);
            return true;
        }, ok -> load(), e -> Ui.fail(activity, e)));
        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        refresh = new SwipeRefreshLayout(activity);
        refresh.addView(list);
        refresh.setOnRefreshListener(this::load);
        root.addView(refresh, Ui.fill(1));
    }

    @Override public View view() { return root; }
    @Override public void show() { load(); }

    private void load() {
        int tab = showing;
        hint.setText(HINTS[tab]);
        refresh.setRefreshing(true);
        if (tab == PLAYS) {
            list.setAdapter(plays);
            Tasks.run(() -> DecoApp.get().api.playRecords(), items -> {
                if (showing != tab) return;
                plays.set(items);
                done(items.isEmpty());
            }, this::failed);
        } else if (tab == FAVORITES) {
            list.setAdapter(favorites);
            Tasks.run(() -> DecoApp.get().api.favorites(), items -> {
                if (showing != tab) return;
                favorites.set(items);
                done(items.isEmpty());
            }, this::failed);
        } else {
            list.setAdapter(keywords);
            Tasks.run(() -> DecoApp.get().api.searchHistory(), items -> {
                if (showing != tab) return;
                keywords.set(items);
                done(items.isEmpty());
            }, this::failed);
        }
    }

    private void done(boolean isEmpty) {
        refresh.setRefreshing(false);
        empty.setText(EMPTY[showing]);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        clear.setEnabled(!isEmpty);
    }

    private void failed(Exception e) {
        refresh.setRefreshing(false);
        Ui.fail(activity, e);
    }

    private void confirmClear() {
        int tab = showing;
        confirm(CLEAR[tab], () -> Tasks.run(() -> {
            if (tab == PLAYS) DecoApp.get().api.deletePlayRecord(null);
            else if (tab == FAVORITES) DecoApp.get().api.deleteFavorite(null);
            else DecoApp.get().api.deleteSearchHistory(null);
            return true;
        }, ok -> load(), e -> Ui.fail(activity, e)));
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(activity).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> yes.run()).show();
    }
}
