package io.github.fugitive844.decotv.ui;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

/** 底部导航「片库」：电影、剧集、动漫、综艺、源浏览器，对应网页端顶部导航的同名入口。 */
final class LibraryPage implements MainActivity.Page {
    static final int MOVIE = 0, TV = 1, ANIME = 2, SHOW = 3, SOURCES = 4;
    private static final String[] TITLES = {"电影", "剧集", "动漫", "综艺", "源浏览器"};
    private static final String[] TYPES = {DoubanBrowser.MOVIE, DoubanBrowser.TV, DoubanBrowser.ANIME, DoubanBrowser.SHOW};

    private final MainActivity activity;
    private final LinearLayout root;
    private final TabLayout tabs;
    private final FrameLayout body;
    private final DoubanBrowser[] douban = new DoubanBrowser[TYPES.length];
    private SourceBrowser sources;
    private int current = -1;

    LibraryPage(MainActivity activity) {
        this.activity = activity;
        root = Ui.column(activity, 0);
        root.setBackgroundColor(Style.bg(activity));
        LinearLayout head = Ui.row(activity);
        head.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 12), 0);
        head.addView(Style.pageTitle(activity, "片库"), Ui.weight(1));
        View search = Style.iconButton(activity, io.github.fugitive844.decotv.R.drawable.ic_nav_search, "搜索", Style.surface2(activity), Style.text(activity));
        search.setOnClickListener(v -> activity.selectTab(MainActivity.SEARCH));
        head.addView(search);
        root.addView(head);
        // 一级分类：选中项大号粗体，下面一条短强调色横条
        tabs = new TabLayout(activity);
        tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabs.setTabGravity(TabLayout.GRAVITY_START);
        tabs.setBackgroundColor(Style.bg(activity));
        tabs.setSelectedTabIndicatorColor(Style.accent(activity));
        tabs.setTabIndicatorFullWidth(false);
        tabs.setSelectedTabIndicator(Style.round(activity, Style.accent(activity), 2));
        tabs.setTabRippleColor(null);
        tabs.setPadding(Ui.dp(activity, 4), 0, 0, 0);
        for (String t : TITLES) {
            TextView label = Style.text(activity, t, 16, 400, Style.text3(activity));
            tabs.addTab(tabs.newTab().setCustomView(label).setContentDescription(t));
        }
        styleTab(tabs.getTabAt(0), true);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                styleTab(tab, true);
                show(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { styleTab(tab, false); }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        root.addView(tabs);
        body = new FrameLayout(activity);
        root.addView(body, Ui.fill(1));
        show(MOVIE);
    }

    private void styleTab(TabLayout.Tab tab, boolean selected) {
        if (tab == null || !(tab.getCustomView() instanceof TextView)) return;
        TextView t = (TextView) tab.getCustomView();
        t.setTextSize(selected ? 19 : 16);
        Style.weight(t, selected ? 900 : 400);
        t.setTextColor(selected ? Style.text(activity) : Style.text3(activity));
    }

    @Override public View view() { return root; }
    @Override public void show() {
        // 访问秘钥改了之后，源浏览器重新拉取源列表
        if (current == SOURCES && sources != null && sources.staleAccessKey()) {
            current = -1;
            show(SOURCES);
        }
    }

    void select(int tab) {
        TabLayout.Tab t = tabs.getTabAt(tab);
        if (t != null) t.select();
        show(tab);
    }

    /** 每个标签只在第一次打开时创建并加载，切回来保留筛选和滚动位置。 */
    private void show(int tab) {
        if (tab == current) return;
        current = tab;
        body.removeAllViews();
        View v;
        if (tab == SOURCES) {
            if (sources == null || sources.staleAccessKey()) sources = new SourceBrowser(activity);
            v = sources.view();
        } else {
            if (douban[tab] == null) douban[tab] = new DoubanBrowser(activity, TYPES[tab]);
            v = douban[tab].view();
        }
        body.addView(v, Ui.match());
    }

    /** 当前标签已加载的条数，供联调测试读取。 */
    int count() {
        if (current == SOURCES) return sources == null ? 0 : sources.count();
        return current < 0 || douban[current] == null ? 0 : douban[current].count();
    }

    String sourceName() { return sources == null ? "" : sources.siteName(); }

    int sourceCount() { return sources == null ? 0 : sources.siteCount(); }

    void selectSource(int i) { if (sources != null) sources.selectIndex(i); }
}
