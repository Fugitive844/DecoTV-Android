package io.github.fugitive844.decotv.ui;

import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.model.SearchGroups;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 搜索页：流式返回结果；默认与网页端一样按同名同年同类型聚合，可切回逐条显示。 */
final class SearchPage implements MainActivity.Page {
    private final MainActivity activity;
    private final LinearLayout root;
    private final EditText input;
    private final ProgressBar progress;
    private final TextView empty, status;
    private final RecyclerView list;
    private final RowAdapter<Video> all;
    private final RowAdapter<SearchGroups.Group> groups;
    private final List<Video> results = new ArrayList<>();
    private boolean aggregate;
    private int generation;
    private volatile Runnable running;
    private static final long RENDER_INTERVAL_MS = 300;
    private boolean renderPending;
    private int progressDone, progressTotal, finishedGeneration;
    private String lastQuery = "";

    SearchPage(MainActivity activity) {
        this.activity = activity;
        aggregate = DecoApp.get().prefs.aggregateSearch();
        root = Ui.column(activity, 0);
        root.setBackgroundColor(Style.bg(activity));
        LinearLayout bar = Ui.row(activity);
        bar.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 8), 0);
        input = new EditText(activity);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                search(String.valueOf(input.getText()));
                return true;
            }
            return false;
        });
        bar.addView(Style.searchBox(activity, input, "搜索影视"), Ui.weight(1));
        MaterialButton go = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
        go.setText("搜索");
        go.setTextSize(15);
        go.setTextColor(Style.accent(activity));
        go.setOnClickListener(v -> search(String.valueOf(input.getText())));
        bar.addView(go);
        root.addView(bar);
        // 流式搜索的进度：细横条
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Style.accent(activity)));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 4)) {{
            setMargins(Ui.dp(activity, 16), Ui.dp(activity, 10), Ui.dp(activity, 16), 0);
        }});
        LinearLayout info = Ui.row(activity);
        info.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 4), Ui.dp(activity, 12), Ui.dp(activity, 4));
        status = Style.text(activity, "", 12, 400, Style.text2(activity));
        info.addView(status, Ui.weight(1));
        MaterialSwitch toggle = new MaterialSwitch(activity);
        toggle.setText("聚合");
        toggle.setTextColor(Style.text2(activity));
        toggle.setChecked(aggregate);
        toggle.setOnCheckedChangeListener((b, checked) -> {
            aggregate = checked;
            DecoApp.get().prefs.setAggregateSearch(checked);
            render();
            aggregateChanged(checked);
        });
        info.addView(toggle);
        root.addView(info);
        empty = Ui.empty(activity, "");
        empty.setTextColor(Style.text2(activity));
        root.addView(empty);
        history = Ui.column(activity, 0);
        history.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8), Ui.dp(activity, 16), Ui.dp(activity, 16));
        LinearLayout historyBar = Ui.row(activity);
        historyBar.addView(Style.text(activity, "搜索历史", 17, 700, Style.text(activity)), Ui.weight(1));
        MaterialButton clear = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
        clear.setText("清空");
        clear.setTextColor(Style.text3(activity));
        clear.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(activity).setMessage("清空全部搜索历史？")
                .setNegativeButton("取消", null).setPositiveButton("确定", (d, w) -> Tasks.run(() -> {
                    DecoApp.get().api.deleteSearchHistory(null);
                    return true;
                }, ok -> loadHistory(), e -> Ui.fail(activity, e))).show());
        historyBar.addView(clear);
        history.addView(historyBar);
        chips = new com.google.android.material.chip.ChipGroup(activity);
        history.addView(chips);
        TextView historyHint = Style.text(activity, "点按搜索，长按删除", 12, 400, Style.text3(activity));
        history.addView(historyHint);
        history.setVisibility(View.GONE);
        android.widget.ScrollView historyScroll = new android.widget.ScrollView(activity);
        historyScroll.addView(history);
        root.addView(historyScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // 清空输入框时回到搜索历史
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (s.toString().trim().isEmpty() && !results.isEmpty()) {
                    generation++;
                    Runnable cancel = running;
                    if (cancel != null) cancel.run();
                    results.clear();
                    render();
                    progress.setVisibility(View.GONE);
                    status.setText("");
                    empty.setVisibility(View.GONE);
                    updateHistory();
                }
            }
        });
        all = new RowAdapter<Video>(72, 102, (h, v) -> {
            h.title.setText(v.title);
            String meta = v.sourceName + (v.year.isEmpty() ? "" : " · " + v.year) + " · " + v.episodes.size() + " 集"
                    + (v.remarks.isEmpty() ? "" : " · " + v.remarks);
            h.subtitle.setText(meta);
            DecoApp.get().images.cover(h.image, v.poster);
        }).cards().onClick((v, i) -> PlayerActivity.open(activity, v, lastQuery));
        groups = new RowAdapter<SearchGroups.Group>(72, 102, (h, g) -> {
            h.title.setText(g.title);
            String meta = (g.year.isEmpty() ? "" : g.year + " · ") + (g.movie ? "电影" : g.maxEpisodes() + " 集") + " · "
                    + g.items.size() + " 个源\n" + g.sourceNames(3);
            h.subtitle.setText(meta);
            DecoApp.get().images.covers(h.image, g.posters());
        }).cards().onClick((g, i) -> PlayerActivity.open(activity, g.items.get(0), lastQuery));
        list = new RecyclerView(activity);
        list.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 16));
        list.setClipToPadding(false);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(aggregate ? groups : all);
        root.addView(list, Ui.fill(1));
    }

    @Override public View view() { return root; }
    @Override public void show() { loadHistory(); }

    private final LinearLayout history;
    private final com.google.android.material.chip.ChipGroup chips;

    /** 与网页端一致：没有结果时显示搜索历史，点按填入并搜索，长按删除单条。 */
    private void loadHistory() {
        Tasks.run(() -> DecoApp.get().api.searchHistory(), items -> {
            chips.removeAllViews();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String raw : items) {
                String k = raw.trim();
                if (k.isEmpty() || !seen.add(k.toLowerCase(Locale.ROOT))) continue;
                com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(activity);
                chip.setText(k);
                Style.chip(chip);
                chip.setOnClickListener(v -> search(k));
                chip.setOnLongClickListener(v -> {
                    Tasks.run(() -> { DecoApp.get().api.deleteSearchHistory(k); return true; }, ok -> loadHistory(), e -> Ui.fail(activity, e));
                    return true;
                });
                chips.addView(chip);
            }
            updateHistory();
        }, e -> { });
    }

    private void updateHistory() {
        boolean idle = results.isEmpty() && progress.getVisibility() != View.VISIBLE;
        history.setVisibility(idle && chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** 关掉聚合后显示全部结果，「没有同名影片」的提示随之隐藏。 */
    private void aggregateChanged(boolean on) {
        if (!results.isEmpty()) empty.setVisibility(on && SearchGroups.group(results, lastQuery).isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void render() {
        if (aggregate) {
            List<SearchGroups.Group> grouped = SearchGroups.group(results, lastQuery);
            if (list.getAdapter() != groups) list.setAdapter(groups);
            groups.set(grouped);
        } else {
            if (list.getAdapter() != all) list.setAdapter(all);
            all.set(results);
        }
    }

    private String summary(boolean done, int finished, int total) {
        String count = aggregate ? String.format(Locale.ROOT, "%d 部 · %d 条结果", SearchGroups.group(results, lastQuery).size(), results.size())
                : String.format(Locale.ROOT, "%d 条结果", results.size());
        return done ? "共 " + count : String.format(Locale.ROOT, "已返回 %d / %d 个源 · %s", finished, total, count);
    }

    /** 本机搜索一次可能有上千条结果、几十个源接连返回：合并刷新，避免每个源都在主线程重新分组。 */
    private void scheduleRender(int token) {
        if (renderPending) return;
        renderPending = true;
        root.postDelayed(() -> {
            renderPending = false;
            if (token != generation || token == finishedGeneration) return;
            render();
            status.setText(summary(false, progressDone, progressTotal));
        }, RENDER_INTERVAL_MS);
    }

    void search(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) return;
        if (!query.equals(String.valueOf(input.getText()))) input.setText(query);
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        int token = ++generation;
        lastQuery = query;
        Runnable previous = running;
        if (previous != null) previous.run();
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        status.setText("正在搜索「" + query + "」…");
        results.clear();
        render();
        history.setVisibility(View.GONE);
        int[] sources = {0, 0};
        // 搜索记录与审计由服务端记录，失败不影响搜索本身
        Tasks.run(() -> { DecoApp.get().api.addSearchHistory(query); return true; }, ok -> loadHistory(), error -> { });
        Tasks.run(() -> {
            boolean finished = DecoApp.get().api.searchStream(query, (batch, done, total) -> {
                sources[0] = done;
                sources[1] = total;
                Tasks.post(() -> {
                    if (token != generation) return;
                    results.addAll(batch);
                    progressDone = done;
                    progressTotal = total;
                    scheduleRender(token);
                });
            }, c -> running = c);
            // 服务端在结果命中缓存时可能提前结束流，丢掉部分源；此时改用一次性搜索补全
            if (finished && sources[1] > 0 && sources[0] < sources[1]) return DecoApp.get().api.search(query);
            return null;
        }, complete -> {
            if (token != generation) return;
            finishedGeneration = token;
            if (complete != null) {
                results.clear();
                results.addAll(complete);
            }
            render();
            progress.setVisibility(View.GONE);
            status.setText(summary(true, 0, 0));
            // 聚合只显示同名的影片；一部都没有时提示可以关掉聚合看全部结果，免得以为出错
            boolean noGroups = aggregate && !results.isEmpty() && SearchGroups.group(results, query).isEmpty();
            empty.setText(noGroups ? "没有与「" + query + "」同名的影片\n关闭「聚合」可查看全部 " + results.size() + " 条结果"
                    : "没有找到「" + query + "」");
            empty.setVisibility(results.isEmpty() || noGroups ? View.VISIBLE : View.GONE);
            loadHistory();
        }, error -> {
            if (token != generation) return;
            finishedGeneration = token;
            progress.setVisibility(View.GONE);
            status.setText("");
            updateHistory();
            Ui.fail(activity, error);
        });
    }
}
