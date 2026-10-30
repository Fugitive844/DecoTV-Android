package io.github.fugitive844.decotv.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import androidx.recyclerview.widget.RecyclerView;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.live.ChannelMerge;
import io.github.fugitive844.decotv.live.LiveCache;
import io.github.fugitive844.decotv.live.LiveChannels;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.net.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 直播源选择、分类标签与频道列表；同一分组下的同名频道合并为一个多源频道。
 * 直播源列表每次进入都向服务端拉取，拉取失败用本地缓存；各源的频道列表先显示缓存，
 * 缓存超过 5 小时才在后台静默重新拉取，也可以点「刷新」手动拉取；拉取失败继续用缓存。
 * 拉取频道列表是否经服务端中转，按设置里「直播列表」的中转策略。
 * 只有用户选过的源才会拉取和缓存频道列表，不预取其他源，节省流量和存储。
 */
final class LivePage implements MainActivity.Page {
    private final MainActivity activity;
    private final LinearLayout root;
    private final Spinner sources;
    private final EditText filter;
    private final ProgressBar progress;
    private final TextView status;
    private final com.google.android.material.button.MaterialButton refresh;
    /** 正在拉取频道列表的那次加载（generation），-1 表示没有。 */
    private int refreshingToken = -1;
    private int rawCount;
    private boolean channelsRelayed;
    private final ChipGroup groups;
    /** 当前选中的分类，null 为全部。 */
    private String group;
    private RowAdapter<Channel> adapter;
    private final List<LiveSource> sourceList = new ArrayList<>();
    private List<Channel> channels = new ArrayList<>();
    private LiveSource selected;
    private int generation;
    private final LiveCache cache;
    private boolean sourcesLoading, channelsRefreshing;
    /** 当前频道列表的获取时间，用于判断是否需要静默刷新。 */
    private long channelsAt;

    LivePage(MainActivity activity) {
        this.activity = activity;
        cache = new LiveCache(activity.getFilesDir());
        root = Ui.column(activity, 0);
        root.setBackgroundColor(Style.bg(activity));
        LinearLayout head = Ui.row(activity);
        head.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), 0);
        head.addView(Style.pageTitle(activity, "直播"), Ui.weight(1));
        refresh = new com.google.android.material.button.MaterialButton(activity);
        Style.tonalButton(refresh);
        refresh.setMinHeight(Ui.dp(activity, 38));
        refresh.setIconResource(io.github.fugitive844.decotv.R.drawable.ic_refresh);
        refresh.setIconTint(android.content.res.ColorStateList.valueOf(Style.text(activity)));
        refresh.setIconSize(Ui.dp(activity, 16));
        refresh.setText("刷新");
        refresh.setContentDescription("刷新频道列表");
        refresh.setVisibility(View.GONE);
        refresh.setOnClickListener(v -> { if (selected != null) fetchChannels(selected, generation, false); });
        head.addView(refresh);
        root.addView(head);
        // 直播源：胶囊样式的下拉框
        sources = new Spinner(activity);
        sources.setBackground(Style.round(activity, Style.surface2(activity), 21));
        sources.setPopupBackgroundDrawable(Style.round(activity, Style.surface(activity), 14));
        sources.setPadding(Ui.dp(activity, 6), 0, Ui.dp(activity, 6), 0);
        root.addView(sources, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 42)) {{
            setMargins(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 16), 0);
        }});
        status = Style.text(activity, "", 12, 400, Style.text3(activity));
        status.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 8), Ui.dp(activity, 16), 0);
        root.addView(status);
        filter = new EditText(activity);
        // 先设好样式再监听：setSingleLine 会触发一次文字变化
        root.addView(Style.searchBox(activity, filter, "筛选频道或分组"), Style.margins(activity, 16, 10, 16, 0));
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        groups = new ChipGroup(activity);
        groups.setSingleLine(true);
        groups.setSingleSelection(true);
        groups.setSelectionRequired(true);
        groups.setChipSpacingHorizontal(Ui.dp(activity, 8));
        android.widget.HorizontalScrollView groupScroll = new android.widget.HorizontalScrollView(activity);
        groupScroll.setHorizontalScrollBarEnabled(false);
        groupScroll.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 4), Ui.dp(activity, 16), 0);
        groupScroll.setClipToPadding(false);
        groupScroll.addView(groups);
        root.addView(groupScroll);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Style.accent(activity)));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 4)) {{
            setMargins(Ui.dp(activity, 16), Ui.dp(activity, 6), Ui.dp(activity, 16), 0);
        }});
        adapter = new RowAdapter<Channel>(56, 40, (h, c) -> {
            h.title.setText(c.name);
            h.image.setBackground(Style.round(activity, Style.surface2(activity), 10));
            int n = c.sources().size();
            h.subtitle.setText(n > 1 ? c.group + " · " + n + " 个源" : c.group);
            h.image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            DecoApp.get().images.logo(h.image, c.logo, selected == null ? "" : selected.key);
        }).onClick((c, i) -> {
            if (selected != null) LiveActivity.open(activity, selected, this.adapter.items(), i);
        });
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(adapter);
        root.addView(list, Ui.fill(1));
        sources.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= sourceList.size()) return;
                LiveSource s = sourceList.get(position);
                if (selected != null && s.key.equals(selected.key) && s.url.equals(selected.url)) selected = s;
                else loadChannels(s);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    @Override public View view() { return root; }

    String statusText() { return String.valueOf(status.getText()); }
    int channelCount() { return channels.size(); }
    boolean refreshing() { return refreshingToken >= 0; }
    void refreshForTest() { refresh.performClick(); }

    @Override public void show() {
        if (sourceList.isEmpty()) {
            Tasks.run(cache::sources, cached -> {
                if (sourceList.isEmpty() && !cached.isEmpty()) setSources(cached);
                refreshSources();
            }, e -> refreshSources());
        } else {
            refreshSources();
            refreshChannelsIfStale();
            if (channelsAt > 0) renderStatus();
        }
    }

    /** 向服务端拉取直播源列表；成功则替换缓存（已不存在的源连同频道缓存一起删掉），失败则继续用缓存。 */
    private void refreshSources() {
        if (sourcesLoading) return;
        sourcesLoading = true;
        if (sourceList.isEmpty()) progress.setVisibility(View.VISIBLE);
        Tasks.run(() -> {
            List<LiveSource> list = DecoApp.get().api.liveSources();
            cache.saveSources(list);
            return list;
        }, list -> {
            sourcesLoading = false;
            if (selected == null) progress.setVisibility(View.GONE);
            setSources(list);
            if (list.isEmpty()) status.setText("服务端没有可用的直播源");
        }, e -> {
            sourcesLoading = false;
            if (e instanceof ApiClient.AuthException) {
                Ui.fail(activity, e);
                return;
            }
            if (!sourceList.isEmpty()) return;
            progress.setVisibility(View.GONE);
            status.setText("加载直播源失败：" + e.getMessage());
        });
    }

    /** 更新下拉框；列表没变就不动，变了尽量保持当前选中的源。 */
    private void setSources(List<LiveSource> list) {
        if (sameSources(list)) return;
        sourceList.clear();
        sourceList.addAll(list);
        List<String> names = new ArrayList<>();
        int keep = -1;
        for (int i = 0; i < list.size(); i++) {
            names.add(list.get(i).name);
            if (selected != null && list.get(i).key.equals(selected.key)) keep = i;
        }
        ArrayAdapter<String> names2 = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, names) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) { return style(super.getView(position, convert, parent), true); }
            @Override public View getDropDownView(int position, View convert, android.view.ViewGroup parent) { return style(super.getDropDownView(position, convert, parent), false); }

            private View style(View v, boolean closed) {
                if (v instanceof TextView) {
                    TextView t = (TextView) v;
                    t.setTextColor(Style.text(activity));
                    t.setTextSize(14);
                    if (closed) {
                        t.setText("直播源：" + t.getText());
                        t.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, io.github.fugitive844.decotv.R.drawable.ic_expand, 0);
                        t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Style.text2(activity)));
                        t.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 6), 0);
                    }
                }
                return v;
            }
        };
        sources.setAdapter(names2);
        if (keep >= 0) sources.setSelection(keep, false);
        if (list.isEmpty()) {
            selected = null;
            ++generation;
            channels = new ArrayList<>();
            groups.removeAllViews();
            adapter.set(channels);
        }
    }

    private boolean sameSources(List<LiveSource> list) {
        if (list.size() != sourceList.size()) return false;
        for (int i = 0; i < list.size(); i++) {
            LiveSource a = list.get(i), b = sourceList.get(i);
            if (!a.key.equals(b.key) || !a.name.equals(b.name) || !a.url.equals(b.url) || !a.ua.equals(b.ua)) return false;
        }
        return true;
    }

    private void loadChannels(LiveSource source) {
        selected = source;
        int token = ++generation;
        channels = new ArrayList<>();
        group = null;
        groups.removeAllViews();
        adapter.set(channels);
        channelsAt = 0;
        refresh.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText("正在加载「" + source.name + "」…");
        Tasks.run(() -> cache.channels(source.key), entry -> {
            if (token != generation) return;
            if (entry == null) {
                fetchChannels(source, token, false);
                return;
            }
            progress.setVisibility(View.GONE);
            showChannels(entry.channels, entry.relayed, entry.savedAt);
            if (entry.stale(source)) fetchChannels(source, token, true);
        }, e -> {
            if (token == generation) fetchChannels(source, token, false);
        });
    }

    /** 回到直播页时，当前源的频道列表超过 5 小时就静默刷新。 */
    private void refreshChannelsIfStale() {
        if (selected != null && channelsAt > 0 && System.currentTimeMillis() - channelsAt > LiveCache.CHANNELS_MAX_AGE_MS) {
            fetchChannels(selected, generation, true);
        }
    }

    /**
     * 拉取频道列表并写入缓存，中转与否按设置里「直播列表」的策略。
     * silent：后台静默刷新，失败不提示；手动刷新失败时提示一下，列表保持缓存不变。
     */
    private void fetchChannels(LiveSource source, int token, boolean silent) {
        if (refreshingToken == token) return;
        refreshingToken = token;
        boolean hadList = channelsAt > 0;
        updateRefreshButton();
        Relay.Mode mode = DecoApp.get().prefs.mode(Relay.Item.LIVE_LIST);
        Tasks.run(() -> {
            LiveChannels result = LiveChannels.load(DecoApp.get().api, source, mode);
            cache.saveChannels(source, result.channels, result.relayed);
            return result;
        }, result -> {
            if (token != generation) return;
            refreshingToken = -1;
            progress.setVisibility(View.GONE);
            showChannels(result.channels, result.relayed, System.currentTimeMillis());
            if (!silent && hadList) Ui.toast(activity, "频道列表已刷新");
        }, e -> {
            if (token != generation) return;
            refreshingToken = -1;
            updateRefreshButton();
            if (e instanceof ApiClient.AuthException) Ui.fail(activity, e);
            if (silent) return;
            progress.setVisibility(View.GONE);
            if (channelsAt > 0) Ui.toast(activity, "刷新失败，继续使用缓存：" + e.getMessage());
            else status.setText("加载失败：" + e.getMessage());
        });
    }

    private void updateRefreshButton() {
        boolean busy = refreshingToken >= 0 && refreshingToken == generation;
        refresh.setEnabled(!busy);
        refresh.setText(busy ? "刷新中…" : "刷新");
        refresh.setVisibility(selected != null && (channelsAt > 0 || !busy) ? View.VISIBLE : View.GONE);
    }

    /** 显示频道列表；刷新时保留当前的分类和筛选。 */
    private void showChannels(List<Channel> raw, boolean relayed, long savedAt) {
        channelsAt = savedAt;
        rawCount = raw.size();
        channelsRelayed = relayed;
        channels = ChannelMerge.merge(raw);
        if (group != null) {
            boolean found = false;
            for (Channel c : channels) if (c.group.equals(group)) { found = true; break; }
            if (!found) group = null;
        }
        renderStatus();
        updateRefreshButton();
        showGroups();
        applyFilter();
    }

    /** 频道数与上次刷新时间，例如「610 个频道（同名合并自 1619 条）/ 上次刷新 10-29 17:30（5 分钟前）· 直连获取」。 */
    private void renderStatus() {
        String merged = channels.size() < rawCount ? "（同名合并自 " + rawCount + " 条）" : "";
        long minutes = (System.currentTimeMillis() - channelsAt) / 60000;
        String ago = minutes < 1 ? "刚刚" : minutes < 60 ? minutes + " 分钟前" : minutes < 48 * 60 ? minutes / 60 + " 小时前" : minutes / (24 * 60) + " 天前";
        String at = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new java.util.Date(channelsAt));
        status.setText(String.format(Locale.ROOT, "%d 个频道%s\n上次刷新 %s（%s）· %s", channels.size(), merged, at, ago,
                channelsRelayed ? "经服务端中转" : "直连获取"));
    }

    /** 与网页端一样按分组显示分类标签：「全部」加各分组及频道数，顺序同列表。 */
    private void showGroups() {
        groups.removeAllViews();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Channel c : channels) counts.merge(c.group, 1, Integer::sum);
        if (counts.size() <= 1) return;
        addChip("全部 (" + channels.size() + ")", null);
        for (Map.Entry<String, Integer> e : counts.entrySet()) addChip(e.getKey() + " (" + e.getValue() + ")", e.getKey());
    }

    private void addChip(String label, String key) {
        Chip chip = new Chip(activity, null, com.google.android.material.R.attr.chipStyle);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setEnsureMinTouchTargetSize(false);
        Style.chip(chip);
        chip.setId(View.generateViewId());
        chip.setContentDescription("分类 " + label);
        groups.addView(chip);
        if (java.util.Objects.equals(key, group)) groups.check(chip.getId());
        chip.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) return;
            group = key;
            applyFilter();
        });
    }

    private void applyFilter() {
        String q = String.valueOf(filter.getText()).trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() && group == null) {
            adapter.set(channels);
            return;
        }
        List<Channel> shown = new ArrayList<>();
        for (Channel c : channels) {
            if (group != null && !group.equals(c.group)) continue;
            if (q.isEmpty() || c.name.toLowerCase(Locale.ROOT).contains(q) || c.group.toLowerCase(Locale.ROOT).contains(q)) shown.add(c);
        }
        adapter.set(shown);
    }
}
