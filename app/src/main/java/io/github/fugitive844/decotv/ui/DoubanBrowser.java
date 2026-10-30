package io.github.fugitive844.decotv.ui;

import android.view.View;
import android.widget.TextView;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.model.Poster;
import io.github.fugitive844.decotv.net.Tasks;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 片库里的电影 / 剧集 / 动漫 / 综艺，筛选项、取值、默认值和请求方式都与网页端 /douban?type= 相同：
 * 选「全部」时用多级筛选（类型、地区、年代、平台、排序）调 recommends，其余调豆瓣榜单 categories；
 * 动漫的「每日放送」取 Bangumi 当天（可切换星期）的番。每页 25 条，滑到底加载下一页。
 */
final class DoubanBrowser {
    static final String MOVIE = "movie", TV = "tv", ANIME = "anime", SHOW = "show";
    private static final int PAGE = 25;

    // ---------- 网页端 DoubanSelector / MultiLevelSelector 的选项 ----------
    private static final String[] MOVIE_PRIMARY_LABELS = {"全部", "热门电影", "最新电影", "豆瓣高分", "冷门佳片"};
    private static final String[] MOVIE_PRIMARY = {"全部", "热门", "最新", "豆瓣高分", "冷门佳片"};
    private static final String[] MOVIE_REGION = {"全部", "华语", "欧美", "韩国", "日本"};
    private static final String[] TV_PRIMARY = {"全部", "最近热门"};
    private static final String[] TV_TYPE_LABELS = {"全部", "国产", "欧美", "日本", "韩国", "动漫", "纪录片"};
    private static final String[] TV_TYPE = {"tv", "tv_domestic", "tv_american", "tv_japanese", "tv_korean", "tv_animation", "tv_documentary"};
    private static final String[] SHOW_TYPE_LABELS = {"全部", "国内", "国外"};
    private static final String[] SHOW_TYPE = {"show", "show_domestic", "show_foreign"};
    private static final String[] ANIME_PRIMARY = {"每日放送", "番剧", "剧场版"};
    private static final String[] WEEK_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] WEEK = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    static final String[] GENRE_MOVIE = {"全部", "喜剧", "爱情", "动作", "科幻", "悬疑", "犯罪", "惊悚", "冒险", "音乐", "历史", "奇幻", "恐怖", "战争",
            "传记", "歌舞", "武侠", "情色", "灾难", "西部", "纪录片", "短片"};
    static final String[] GENRE_TV = {"全部", "喜剧", "爱情", "悬疑", "武侠", "古装", "家庭", "犯罪", "科幻", "恐怖", "历史", "战争", "动作", "冒险",
            "传记", "剧情", "奇幻", "惊悚", "灾难", "歌舞", "音乐"};
    static final String[] GENRE_SHOW = {"全部", "真人秀", "脱口秀", "音乐", "歌舞"};
    static final String[] LABEL_ANIME_TV = {"全部", "黑色幽默", "历史", "歌舞", "励志", "恶搞", "治愈", "运动", "后宫", "情色", "国漫", "人性", "悬疑",
            "恋爱", "魔幻", "科幻"};
    static final String[] LABEL_ANIME_MOVIE = {"全部", "定格动画", "传记", "美国动画", "爱情", "黑色幽默", "歌舞", "儿童", "二次元", "动物", "青春",
            "历史", "励志", "恶搞", "治愈", "运动", "后宫", "情色", "人性", "悬疑", "恋爱", "魔幻", "科幻"};
    static final String[] REGION_MOVIE = {"全部", "华语", "欧美", "韩国", "日本", "中国大陆", "美国", "中国香港", "中国台湾", "英国", "法国", "德国",
            "意大利", "西班牙", "印度", "泰国", "俄罗斯", "加拿大", "澳大利亚", "爱尔兰", "瑞典", "巴西", "丹麦"};
    static final String[] REGION_TV = {"全部", "华语", "欧美", "国外", "韩国", "日本", "中国大陆", "中国香港", "美国", "英国", "泰国", "中国台湾",
            "意大利", "法国", "德国", "西班牙", "俄罗斯", "瑞典", "巴西", "丹麦", "印度", "加拿大", "爱尔兰", "澳大利亚"};
    static final String[] YEARS = {"全部", "2020年代", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2010年代", "2000年代", "90年代",
            "80年代", "70年代", "60年代", "更早"};
    static final String[] PLATFORMS = {"全部", "腾讯视频", "爱奇艺", "优酷", "湖南卫视", "Netflix", "HBO", "BBC", "NHK", "CBS", "NBC", "tvN"};
    private static final String[] SORT = {"T", "U", "R", "S"};

    private final MainActivity activity;
    private final String type;
    private final GridPane pane;
    private Filters.Row primary, secondary, week, genre, region, year, platform, sort;
    private int page, generation;
    private boolean loading, more = true;

    DoubanBrowser(MainActivity activity, String type) {
        this.activity = activity;
        this.type = type;
        pane = new GridPane(activity, this::loadMore);
        TextView from = Style.text(activity, ANIME.equals(type) ? "来自 Bangumi 番组计划的精选内容" : "来自豆瓣的精选内容", 11, 400, Style.text3(activity));
        from.setPadding(0, 0, 0, Ui.dp(activity, 4));
        pane.header.addView(from);
        buildFilters();
        reload();
    }

    View view() { return pane.view; }

    private Filters.Row add(Filters.Row row) {
        pane.header.addView(row.view);
        return row;
    }

    private void buildFilters() {
        Filters.Change reload = v -> reload();
        Filters.Change primaryChanged = v -> {
            updateRows();
            reload();
        };
        switch (type) {
            case MOVIE:
                primary = add(new Filters.Row(activity, "分类", MOVIE_PRIMARY_LABELS, MOVIE_PRIMARY, "热门", primaryChanged));
                secondary = add(Filters.same(activity, "地区", MOVIE_REGION, "全部", reload));
                genre = add(Filters.same(activity, "类型", GENRE_MOVIE, "全部", reload));
                region = add(Filters.same(activity, "地区", REGION_MOVIE, "全部", reload));
                break;
            case TV:
                primary = add(Filters.same(activity, "分类", TV_PRIMARY, "最近热门", primaryChanged));
                secondary = add(new Filters.Row(activity, "类型", TV_TYPE_LABELS, TV_TYPE, "tv", reload));
                genre = add(Filters.same(activity, "类型", GENRE_TV, "全部", reload));
                region = add(Filters.same(activity, "地区", REGION_TV, "全部", reload));
                break;
            case SHOW:
                primary = add(Filters.same(activity, "分类", TV_PRIMARY, "最近热门", primaryChanged));
                secondary = add(new Filters.Row(activity, "类型", SHOW_TYPE_LABELS, SHOW_TYPE, "show", reload));
                genre = add(Filters.same(activity, "类型", GENRE_SHOW, "全部", reload));
                region = add(Filters.same(activity, "地区", REGION_TV, "全部", reload));
                break;
            default:
                primary = add(Filters.same(activity, "分类", ANIME_PRIMARY, "每日放送", v -> {
                    // 番剧和剧场版的类型、地区选项不同，切换时重建多级筛选
                    rebuildAnimeFilters();
                    updateRows();
                    reload();
                }));
                String today = WEEK[(Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7];
                week = add(new Filters.Row(activity, "星期", WEEK_LABELS, WEEK, today, reload));
                week.highlight(today, Style.accent(activity));
                rebuildAnimeFilters();
                break;
        }
        if (!ANIME.equals(type)) {
            year = add(Filters.same(activity, "年代", YEARS, "全部", reload));
            if (!MOVIE.equals(type)) platform = add(Filters.same(activity, "平台", PLATFORMS, "全部", reload));
            sort = add(new Filters.Row(activity, "排序", sortLabels(), SORT, "T", reload));
        }
        updateRows();
    }

    private String[] sortLabels() {
        boolean series = TV.equals(type) || SHOW.equals(type) || (ANIME.equals(type) && "番剧".equals(primary.value()));
        return new String[]{"综合排序", "近期热度", series ? "首播时间" : "首映时间", "高分优先"};
    }

    private void rebuildAnimeFilters() {
        for (Filters.Row r : new Filters.Row[]{genre, region, year, platform, sort}) if (r != null) pane.header.removeView(r.view);
        Filters.Change reload = v -> reload();
        boolean series = "番剧".equals(primary.value());
        genre = add(Filters.same(activity, "类型", series ? LABEL_ANIME_TV : LABEL_ANIME_MOVIE, "全部", reload));
        region = add(Filters.same(activity, "地区", series ? REGION_TV : REGION_MOVIE, "全部", reload));
        year = add(Filters.same(activity, "年代", YEARS, "全部", reload));
        platform = series ? add(Filters.same(activity, "平台", PLATFORMS, "全部", reload)) : null;
        sort = add(new Filters.Row(activity, "排序", sortLabels(), SORT, "T", reload));
    }

    /** 与网页端相同的显隐规则：选「全部」（以及动漫的番剧 / 剧场版）时显示多级筛选，否则显示次级分类。 */
    private void updateRows() {
        boolean multi = multiLevel();
        if (secondary != null) secondary.setVisible(!multi);
        if (week != null) week.setVisible("每日放送".equals(primary.value()));
        for (Filters.Row r : new Filters.Row[]{genre, region, year, platform, sort}) if (r != null) r.setVisible(multi);
    }

    private boolean multiLevel() {
        if (ANIME.equals(type)) return !"每日放送".equals(primary.value());
        return "全部".equals(primary.value());
    }

    private static String blank(Filters.Row r) {
        if (r == null || "全部".equals(r.value())) return "";
        return r.value();
    }

    private void reload() {
        generation++;
        page = 0;
        more = true;
        loading = false;
        pane.reset();
        loadMore();
    }

    private void loadMore() {
        if (loading || !more) return;
        loading = true;
        int token = generation, start = page * PAGE;
        pane.status("加载中…");
        String stype = MOVIE.equals(type) ? "movie" : "";
        Tasks.run(() -> fetch(start, stype), items -> {
            if (token != generation) return;
            loading = false;
            page++;
            List<Poster> all = new java.util.ArrayList<>(pane.adapter.items());
            Poster.appendDistinct(all, items);
            // 网页端最多显示 540 条
            more = !items.isEmpty() && all.size() < 540 && !"每日放送".equals(primary.value());
            pane.adapter.set(all);
            pane.status(all.isEmpty() ? "暂无内容" : more ? "" : "没有更多了");
        }, e -> {
            if (token != generation) return;
            loading = false;
            pane.status("加载失败：" + e.getMessage() + "\n换个条件或稍后再试");
        });
    }

    private List<Poster> fetch(int start, String stype) throws Exception {
        io.github.fugitive844.decotv.net.ApiClient api = DecoApp.get().api;
        String p = primary.value();
        if (ANIME.equals(type)) {
            if ("每日放送".equals(p)) return Poster.listFromBangumi(api.bangumiCalendar(), week.value());
            boolean series = "番剧".equals(p);
            Map<String, String> f = new LinkedHashMap<>();
            f.put("category", "动画");
            f.put("format", series ? "电视剧" : "");
            f.put("label", blank(genre));
            f.put("region", blank(region));
            f.put("year", blank(year));
            f.put("platform", blank(platform));
            f.put("sort", sort.value());
            return Poster.listFromDouban(api.doubanRecommends(series ? "tv" : "movie", f, PAGE, start), series ? "tv" : "movie");
        }
        if (multiLevel()) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("category", blank(genre));
            f.put("format", SHOW.equals(type) ? "综艺" : TV.equals(type) ? "电视剧" : "");
            f.put("label", "");
            f.put("region", blank(region));
            f.put("year", blank(year));
            f.put("platform", blank(platform));
            f.put("sort", sort.value());
            return Poster.listFromDouban(api.doubanRecommends(MOVIE.equals(type) ? "movie" : "tv", f, PAGE, start), stype);
        }
        if (MOVIE.equals(type)) return Poster.listFromDouban(api.doubanCategories("movie", p, secondary.value(), PAGE, start), stype);
        return Poster.listFromDouban(api.doubanCategories("tv", type, secondary.value(), PAGE, start), stype);
    }

    /** 已加载条数，供联调测试读取。 */
    int count() { return pane.adapter.getItemCount(); }
}
