package io.github.fugitive844.decotv.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.model.Poster;
import io.github.fugitive844.decotv.model.SourceSite;
import io.github.fugitive844.decotv.net.Tasks;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 源浏览器，与网页端 /source-browser 相同：选一个视频源，按它自己的分类翻看片单（经服务端 /api/proxy/cms）。
 * 比网页端多一个「在本源中搜索」：用苹果 CMS 的 wd 参数按关键词搜这个源。
 */
final class SourceBrowser {
    private final MainActivity activity;
    private final GridPane pane;
    private final MaterialButton picker;
    private final LinearLayout categoryBox;
    private final EditText keyword;
    private final List<SourceSite> sites = new ArrayList<>();
    private SourceSite site;
    private Filters.Row categories;
    private String category = "", query = "";
    private int page, generation;
    private boolean loading, more;
    /** 加载源列表时的访问秘钥；秘钥变了，可浏览的源可能不同，需要重建。 */
    private final String accessKey = DecoApp.get().prefs.accessKey();

    SourceBrowser(MainActivity activity) {
        this.activity = activity;
        pane = new GridPane(activity, this::loadMore);
        TextView from = Style.text(activity, "按视频源自己的分类浏览片单", 11, 400, Style.text3(activity));
        pane.header.addView(from);
        picker = new MaterialButton(activity);
        Style.tonalButton(picker);
        picker.setIconResource(io.github.fugitive844.decotv.R.drawable.ic_expand);
        picker.setIconGravity(MaterialButton.ICON_GRAVITY_END);
        picker.setIconTint(android.content.res.ColorStateList.valueOf(Style.text2(activity)));
        picker.setText("正在加载视频源…");
        picker.setEnabled(false);
        picker.setOnClickListener(v -> chooseSite());
        pane.header.addView(picker, Style.margins(activity, 0, 8, 6, 8));
        LinearLayout searchRow = Ui.row(activity);
        keyword = new EditText(activity);
        keyword.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        keyword.setOnEditorActionListener((v, id, e) -> {
            search();
            return true;
        });
        searchRow.addView(Style.searchBox(activity, keyword, "在本源中搜索"), Ui.weight(1));
        MaterialButton go = new MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle);
        go.setText("搜索");
        go.setTextColor(Style.accent(activity));
        go.setOnClickListener(v -> search());
        searchRow.addView(go);
        pane.header.addView(searchRow, Style.margins(activity, 0, 0, 0, 6));
        categoryBox = Ui.column(activity, 0);
        pane.header.addView(categoryBox);
        loadSites();
    }

    View view() { return pane.view; }

    boolean staleAccessKey() { return !accessKey.equals(DecoApp.get().prefs.accessKey()); }

    private void loadSites() {
        Tasks.run(() -> DecoApp.get().api.sourceSites(), list -> {
            sites.clear();
            sites.addAll(list);
            picker.setEnabled(!sites.isEmpty());
            if (sites.isEmpty()) {
                picker.setText("没有可浏览的视频源");
                return;
            }
            String last = DecoApp.get().prefs.browseSource();
            SourceSite pick = sites.get(0);
            for (SourceSite s : sites) if (s.key.equals(last)) pick = s;
            select(pick);
        }, e -> {
            picker.setText("加载视频源失败，点此重试");
            picker.setEnabled(true);
            picker.setOnClickListener(v -> {
                picker.setOnClickListener(x -> chooseSite());
                loadSites();
            });
            Ui.fail(activity, e);
        });
    }

    /** 源很多（上百个），用可筛选的列表对话框选择，与网页端「按源名称 / 标识筛选」一致。 */
    private void chooseSite() {
        if (sites.isEmpty()) return;
        LinearLayout box = Ui.column(activity, 16);
        EditText filter = new EditText(activity);
        filter.setHint("按源名称 / 标识筛选");
        filter.setSingleLine(true);
        box.addView(filter);
        List<SourceSite> shown = new ArrayList<>(sites);
        ArrayAdapter<String> names = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, new ArrayList<>());
        ListView list = new ListView(activity);
        list.setAdapter(names);
        box.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 360)));
        Runnable refresh = () -> {
            String k = String.valueOf(filter.getText()).trim().toLowerCase(Locale.ROOT);
            shown.clear();
            names.clear();
            for (SourceSite s : sites) {
                if (k.isEmpty() || s.name.toLowerCase(Locale.ROOT).contains(k) || s.key.toLowerCase(Locale.ROOT).contains(k)
                        || s.api.toLowerCase(Locale.ROOT).contains(k)) {
                    shown.add(s);
                    names.add((site != null && s.key.equals(site.key) ? "✓ " : "") + s.name);
                }
            }
            names.notifyDataSetChanged();
        };
        refresh.run();
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("选择视频源（" + sites.size() + " 个）").setView(box)
                .setNegativeButton("取消", null).create();
        list.setOnItemClickListener((parent, v, position, id) -> {
            dialog.dismiss();
            select(shown.get(position));
        });
        dialog.show();
    }

    private void select(SourceSite s) {
        site = s;
        DecoApp.get().prefs.setBrowseSource(s.key);
        picker.setText("视频源：" + s.name + "  ▾");
        query = "";
        keyword.setText("");
        category = "";
        categoryBox.removeAllViews();
        categories = null;
        int token = ++generation;
        pane.reset();
        pane.status("正在加载分类…");
        Tasks.run(() -> SourceSite.categoriesFrom(DecoApp.get().api.cms(s.url("ac=class"))), list -> {
            if (token != generation) return;
            String[] labels = new String[list.size() + 1], values = new String[list.size() + 1];
            labels[0] = "全部";
            values[0] = "";
            for (int i = 0; i < list.size(); i++) {
                labels[i + 1] = list.get(i).name;
                values[i + 1] = list.get(i).id;
            }
            categories = new Filters.Row(activity, "分类", labels, values, "", v -> {
                category = v;
                query = "";
                keyword.setText("");
                reload();
            });
            categoryBox.addView(categories.view);
            reload();
        }, e -> {
            if (token != generation) return;
            // 拿不到分类也照样可以看「全部」
            pane.status("");
            reload();
        });
    }

    private void search() {
        query = String.valueOf(keyword.getText()).trim();
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(keyword.getWindowToken(), 0);
        reload();
    }

    private void reload() {
        if (site == null) return;
        generation++;
        page = 0;
        more = true;
        loading = false;
        pane.reset();
        loadMore();
    }

    private void loadMore() {
        if (loading || !more || site == null) return;
        loading = true;
        int token = generation, next = page + 1;
        SourceSite s = site;
        StringBuilder q = new StringBuilder("ac=videolist&pg=").append(next);
        if (!query.isEmpty()) {
            try {
                q.append("&wd=").append(java.net.URLEncoder.encode(query, "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) {
                // UTF-8 一定可用
            }
        } else if (!category.isEmpty()) {
            q.append("&t=").append(category);
        }
        pane.status("加载中…");
        Tasks.run(() -> {
            JSONObject o = DecoApp.get().api.cms(s.url(q.toString()));
            return new Object[]{o, Poster.listFromCms(o, s.key, s.name)};
        }, pair -> {
            if (token != generation) return;
            loading = false;
            page = next;
            JSONObject o = (JSONObject) pair[0];
            @SuppressWarnings("unchecked") List<Poster> items = (List<Poster>) pair[1];
            List<Poster> all = new ArrayList<>(pane.adapter.items());
            Poster.appendDistinct(all, items);
            more = all.size() < 540 && SourceSite.hasMore(o, next, items.size(), all.size()) && !items.isEmpty();
            pane.adapter.set(all);
            String empty = !query.isEmpty() ? "这个源里没有找到「" + query + "」" : "这个分类暂无内容";
            pane.status(all.isEmpty() ? empty : more ? "" : "没有更多了");
        }, e -> {
            if (token != generation) return;
            loading = false;
            pane.status("加载失败：" + e.getMessage());
        });
    }

    /** 已加载条数，供联调测试读取。 */
    int count() { return pane.adapter.getItemCount(); }

    String siteName() { return site == null ? "" : site.name; }

    int siteCount() { return sites.size(); }

    void selectIndex(int i) { if (i >= 0 && i < sites.size()) select(sites.get(i)); }
}
