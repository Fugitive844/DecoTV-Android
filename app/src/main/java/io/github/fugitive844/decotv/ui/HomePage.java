package io.github.fugitive844.decotv.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.R;
import io.github.fugitive844.decotv.model.Poster;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.net.Tasks;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 首页，与网页端相同的几栏：继续观看、热门电影、热门剧集、新番放送（Bangumi 当天的番，可切换星期）、热门综艺。
 * 顶部大图轮播取热门剧集前几部，热门剧集同时以带序号的热榜显示；每栏横向滑动，「更多」跳到片库对应分类；下拉刷新。
 */
final class HomePage implements MainActivity.Page {
    static final String[] WEEKDAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] DAY_LABELS = {"日", "一", "二", "三", "四", "五", "六"};
    private static final int HERO_COUNT = 5;

    private interface Load { List<Poster> run() throws Exception; }
    private interface Show { void on(List<Poster> items); }

    /** 一栏：标题行 + 横向列表 + 加载提示。 */
    private final class Section {
        final LinearLayout view;
        final TextView hint;
        final Load load;
        final Show show;
        int count;

        Section(String title, int libraryTab, RecyclerView.Adapter<?> adapter, Load load, Show show, View extra) {
            this.load = load;
            this.show = show;
            view = Ui.column(activity, 0);
            View.OnClickListener more = libraryTab == RECORDS ? v -> activity.push(new RecordsPage(activity, RecordsPage.PLAYS), "播放记录")
                    : libraryTab >= 0 ? v -> activity.openLibrary(libraryTab) : null;
            view.addView(Style.sectionHead(activity, title, more == null ? null : libraryTab == RECORDS ? "全部 ›" : "更多 ›", title + " 更多", more));
            if (extra != null) view.addView(extra);
            hint = Style.text(activity, "加载中…", 13, 400, Style.text3(activity));
            hint.setPadding(Ui.dp(activity, 16), 0, 0, Ui.dp(activity, 8));
            view.addView(hint);
            view.addView(row(adapter));
        }

        void refresh() {
            if (count == 0) {
                hint.setText("加载中…");
                hint.setVisibility(View.VISIBLE);
            }
            Tasks.run(load::run, items -> {
                count = items.size();
                show.on(items);
                hint.setText("暂无内容");
                hint.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                // 没有播放记录时整栏「继续观看」不显示
                if (this == continueWatching) view.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                finished();
            }, e -> {
                if (count == 0) {
                    hint.setText("加载失败：" + e.getMessage());
                    hint.setVisibility(View.VISIBLE);
                }
                finished();
            });
        }
    }

    private static final int RECORDS = -2;

    private final MainActivity activity;
    private final SwipeRefreshLayout refresh;
    private final List<Section> sections = new ArrayList<>();
    private final HeroAdapter hero = new HeroAdapter();
    private final LinearLayout dots;
    private final LinearLayout dayRow;
    private Section continueWatching, anime;
    private String weekday = WEEKDAYS[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1];
    private int pending;
    private long loadedAt;

    HomePage(MainActivity activity) {
        this.activity = activity;
        LinearLayout body = Ui.column(activity, 0);
        body.setPadding(0, 0, 0, Ui.dp(activity, 24));

        // 顶部大图轮播
        FrameLayout heroBox = new FrameLayout(activity);
        heroBox.setBackgroundColor(Style.placeholder(activity));
        RecyclerView pager = new RecyclerView(activity);
        LinearLayoutManager pagerLayout = new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false);
        pager.setLayoutManager(pagerLayout);
        pager.setAdapter(hero);
        new PagerSnapHelper().attachToRecyclerView(pager);
        heroBox.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View topFade = new View(activity);
        int base = Style.bg(activity) & 0x00FFFFFF;
        topFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{base | 0xCC000000, base}));
        heroBox.addView(topFade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 130), Gravity.TOP));
        heroBox.addView(topBar());
        dots = Ui.row(activity);
        dots.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        dp.bottomMargin = Ui.dp(activity, 10);
        heroBox.addView(dots, dp);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) renderDots(pagerLayout.findFirstCompletelyVisibleItemPosition());
            }
        });
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        body.addView(heroBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(width * 6 / 5, Ui.dp(activity, 520))));

        continueWatching = new Section("继续观看", RECORDS, null, () -> {
            List<Poster> out = new ArrayList<>();
            for (Record r : DecoApp.get().api.playRecords()) {
                Poster p = new Poster();
                p.id = r.id();
                p.title = r.title;
                p.poster = r.cover;
                p.year = r.totalEpisodes > 1 ? "第 " + r.index + " / " + r.totalEpisodes + " 集" : Ui.clock(r.playTime) + " / " + Ui.clock(r.totalTime);
                p.sourceName = r.sourceName;
                p.tag = r;
                out.add(p);
                if (out.size() >= 24) break;
            }
            return out;
        }, items -> continueAdapter.set(items), null);
        sections.add(continueWatching);

        PosterAdapter movies = new PosterAdapter(112, p -> TitleOpener.open(activity, p));
        sections.add(new Section("热门电影", LibraryPage.MOVIE, movies,
                () -> Poster.listFromDouban(DecoApp.get().api.doubanCategories("movie", "热门", "全部", 20, 0), "movie"), movies::set, null));

        RankAdapter rank = new RankAdapter();
        sections.add(new Section("剧集热榜", LibraryPage.TV, rank,
                () -> Poster.listFromDouban(DecoApp.get().api.doubanCategories("tv", "tv", "tv", 20, 0), ""), items -> {
                    rank.set(items.subList(0, Math.min(10, items.size())));
                    hero.set(items.subList(0, Math.min(HERO_COUNT, items.size())));
                    pager.scrollToPosition(0);
                    renderDots(0);
                }, null));

        PosterAdapter animeList = new PosterAdapter(112, p -> TitleOpener.open(activity, p));
        dayRow = Ui.row(activity);
        dayRow.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), Ui.dp(activity, 6));
        anime = new Section("新番放送", LibraryPage.ANIME, animeList,
                () -> Poster.listFromBangumi(DecoApp.get().api.bangumiCalendar(), weekday), animeList::set, dayRow);
        sections.add(anime);
        renderDays();

        PosterAdapter shows = new PosterAdapter(112, p -> TitleOpener.open(activity, p));
        sections.add(new Section("热门综艺", LibraryPage.SHOW, shows,
                () -> Poster.listFromDouban(DecoApp.get().api.doubanCategories("tv", "show", "show", 20, 0), ""), shows::set, null));

        for (Section s : sections) body.addView(s.view);
        NestedScrollView scroll = new NestedScrollView(activity);
        scroll.setBackgroundColor(Style.bg(activity));
        scroll.addView(body);
        refresh = new SwipeRefreshLayout(activity);
        refresh.setColorSchemeColors(Style.accent(activity));
        refresh.setProgressBackgroundColorSchemeColor(Style.surface(activity));
        refresh.addView(scroll);
        refresh.setOnRefreshListener(this::load);
    }

    /** 横向列表；继续观看用横版卡片，其余由调用方给出适配器。 */
    private RecyclerView row(RecyclerView.Adapter<?> adapter) {
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        list.setAdapter(adapter != null ? adapter : (continueAdapter = new ContinueAdapter()));
        list.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);
        list.setClipToPadding(false);
        list.setNestedScrollingEnabled(false);
        return list;
    }

    private ContinueAdapter continueAdapter;

    /** 大图上方：标志、搜索、播放记录，以及分类快捷入口。 */
    private View topBar() {
        LinearLayout box = Ui.column(activity, 0);
        box.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 10), Ui.dp(activity, 12), 0);
        LinearLayout bar = Ui.row(activity);
        TextView logo = Style.text(activity, "", 22, 900, Style.text(activity));
        android.text.SpannableString s = new android.text.SpannableString("DecoTV");
        s.setSpan(new android.text.style.ForegroundColorSpan(Style.accent(activity)), 4, 6, 0);
        logo.setText(s);
        bar.addView(logo, Ui.weight(1));
        int glass = Style.surface(activity) & 0x00FFFFFF | 0x99000000;
        View search = Style.iconButton(activity, R.drawable.ic_nav_search, "搜索", glass, Style.text(activity));
        search.setOnClickListener(v -> activity.selectTab(MainActivity.SEARCH));
        bar.addView(search);
        View history = Style.iconButton(activity, R.drawable.ic_history, "播放记录", glass, Style.text(activity));
        ((LinearLayout.LayoutParams) history.getLayoutParams()).leftMargin = Ui.dp(activity, 8);
        history.setOnClickListener(v -> activity.push(new RecordsPage(activity, RecordsPage.PLAYS), "播放记录"));
        bar.addView(history);
        box.addView(bar);
        LinearLayout channels = Ui.row(activity);
        channels.setPadding(0, Ui.dp(activity, 10), 0, 0);
        String[] labels = {"推荐", "电影", "剧集", "动漫", "综艺"};
        int[] tabs = {-1, LibraryPage.MOVIE, LibraryPage.TV, LibraryPage.ANIME, LibraryPage.SHOW};
        for (int i = 0; i < labels.length; i++) {
            TextView t = Style.text(activity, labels[i], 14, i == 0 ? 700 : 400, i == 0 ? Style.bg(activity) : Style.text(activity));
            int h = Ui.dp(activity, 14), v = Ui.dp(activity, 6);
            t.setPadding(h, v, h, v);
            t.setBackground(Style.pressable(activity, i == 0 ? Style.text(activity) : glass, 16));
            int tab = tabs[i];
            if (tab >= 0) t.setOnClickListener(x -> activity.openLibrary(tab));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(activity, 8);
            channels.addView(t, lp);
        }
        box.addView(channels);
        return box;
    }

    private void renderDots(int selected) {
        dots.removeAllViews();
        int n = hero.getItemCount();
        if (n <= 1) return;
        for (int i = 0; i < n; i++) {
            View d = new View(activity);
            boolean on = i == Math.max(0, selected);
            d.setBackground(Style.round(activity, on ? Style.text(activity) : Style.text4(activity), 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(activity, on ? 18 : 5), Ui.dp(activity, 4));
            lp.setMargins(Ui.dp(activity, 3), 0, Ui.dp(activity, 3), 0);
            dots.addView(d, lp);
        }
    }

    /** 新番的星期切换：今天在最前，其余按周一到周日。 */
    private void renderDays() {
        dayRow.removeAllViews();
        String today = WEEKDAYS[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1];
        int[] order = {1, 2, 3, 4, 5, 6, 0};
        List<Integer> days = new ArrayList<>();
        for (int i = 0; i < WEEKDAYS.length; i++) if (WEEKDAYS[i].equals(today)) days.add(i);
        for (int i : order) if (!WEEKDAYS[i].equals(today)) days.add(i);
        for (int i : days) {
            String key = WEEKDAYS[i];
            TextView t = Style.pill(activity, key.equals(today) ? "今天" : DAY_LABELS[i], key.equals(weekday));
            t.setPadding(0, 0, 0, 0);
            t.setContentDescription("星期" + DAY_LABELS[i]);
            t.setOnClickListener(v -> {
                if (key.equals(weekday)) return;
                weekday = key;
                renderDays();
                anime.count = 0;
                pending++;
                anime.refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(activity, 32), 1);
            lp.rightMargin = Ui.dp(activity, 6);
            dayRow.addView(t, lp);
        }
    }

    @Override public View view() { return refresh; }

    /** 回到首页时刷新「继续观看」；其余几栏 10 分钟内不重复请求，下拉可强制刷新。 */
    @Override public void show() {
        if (loadedAt == 0 || System.currentTimeMillis() - loadedAt > 10 * 60_000) {
            load();
        } else {
            pending++;
            continueWatching.refresh();
        }
    }

    private void load() {
        loadedAt = System.currentTimeMillis();
        pending = sections.size();
        for (Section s : sections) s.refresh();
    }

    private void finished() {
        if (--pending <= 0) refresh.setRefreshing(false);
    }

    /** 各栏已加载的条数，供联调测试读取：继续观看、热门电影、热门剧集、新番放送、热门综艺。 */
    int[] counts() {
        int[] out = new int[sections.size()];
        for (int i = 0; i < out.length; i++) out[i] = sections.get(i).count;
        return out;
    }

    // ---------- 各栏的卡片 ----------

    /** 大图：海报铺满，底部渐隐到页面底色，叠标题、评分和「立即播放」。 */
    private final class HeroAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Poster> items = new ArrayList<>();

        void set(List<Poster> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout root = new FrameLayout(c);
            root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View fade = new View(c);
            fade.setBackground(Style.fade(c, true));
            root.addView(fade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 300), Gravity.BOTTOM));
            LinearLayout info = Ui.column(c, 0);
            info.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), Ui.dp(c, 30));
            TextView tag = Style.badge(c, "", Style.accent(c), Style.onAccent(c));
            info.addView(tag, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView title = Style.text(c, "", 32, 900, Style.text(c));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 4));
            info.addView(title);
            TextView meta = Style.single(Style.text(c, "", 13, 400, Style.text2(c)));
            info.addView(meta);
            MaterialButton play = new MaterialButton(c);
            play.setText("立即播放");
            play.setIconResource(R.drawable.ic_play);
            play.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            play.setTextSize(15);
            Style.primaryButton(play);
            play.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Style.text(c)));
            play.setTextColor(Style.bg(c));
            play.setIconTint(android.content.res.ColorStateList.valueOf(Style.bg(c)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 180), Ui.dp(c, 46));
            lp.topMargin = Ui.dp(c, 14);
            info.addView(play, lp);
            root.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            RecyclerView.ViewHolder h = new RecyclerView.ViewHolder(root) { };
            root.setTag(new View[]{image, tag, title, meta, play});
            return h;
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Poster p = items.get(position);
            View[] v = (View[]) h.itemView.getTag();
            DecoApp.get().images.cover((ImageView) v[0], p.poster);
            ((TextView) v[1]).setText("剧集热榜 No." + (position + 1));
            ((TextView) v[2]).setText(p.title);
            StringBuilder meta = new StringBuilder();
            if (!p.rate.isEmpty() && !p.rate.startsWith("0")) meta.append("★ ").append(p.rate);
            if (!p.year.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(p.year);
            if (!p.remarks.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(p.remarks);
            android.text.SpannableString s = new android.text.SpannableString(meta);
            if (meta.indexOf("★") == 0) {
                int end = meta.indexOf("  ") < 0 ? meta.length() : meta.indexOf("  ");
                s.setSpan(new android.text.style.ForegroundColorSpan(Style.star(h.itemView.getContext())), 0, end, 0);
            }
            ((TextView) v[3]).setText(s);
            View.OnClickListener open = x -> TitleOpener.open(activity, p);
            v[4].setOnClickListener(open);
            h.itemView.setOnClickListener(open);
            h.itemView.setContentDescription(p.title);
        }

        @Override public int getItemCount() { return items.size(); }
    }

    /** 继续观看：横版卡片，封面居中裁切，底部进度条。 */
    private final class ContinueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Poster> items = new ArrayList<>();

        void set(List<Poster> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            LinearLayout root = Ui.column(c, 0);
            int pad = Ui.dp(c, 6);
            root.setPadding(pad, pad, pad, pad);
            root.setBackground(Style.pressable(c, 0, 14));
            root.setLayoutParams(new RecyclerView.LayoutParams(Ui.dp(c, 232), ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout frame = PosterAdapter.coverFrame(c, 9, 16, 12);
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView play = Style.icon(c, R.drawable.ic_play, 0xFFFFFFFF, 40);
            play.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
            play.setBackground(Style.round(c, 0x73000000, 20));
            frame.addView(play, new FrameLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40), Gravity.CENTER));
            TextView source = Style.badge(c, "", 0x8C000000, 0xFFE6E9EF);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            sp.setMargins(pad, pad, pad, pad);
            frame.addView(source, sp);
            Style.Progress progress = new Style.Progress(c);
            frame.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 3), Gravity.BOTTOM));
            root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView title = Style.single(Style.text(c, "", 14, 500, Style.text(c)));
            title.setPadding(0, Ui.dp(c, 8), 0, 0);
            root.addView(title);
            TextView sub = Style.single(Style.text(c, "", 12, 400, Style.text3(c)));
            root.addView(sub);
            root.setTag(new View[]{image, source, progress, title, sub});
            return new RecyclerView.ViewHolder(root) { };
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Poster p = items.get(position);
            Record r = (Record) p.tag;
            View[] v = (View[]) h.itemView.getTag();
            DecoApp.get().images.cover((ImageView) v[0], p.poster);
            ((TextView) v[1]).setText(p.sourceName);
            v[1].setVisibility(p.sourceName.isEmpty() ? View.GONE : View.VISIBLE);
            ((Style.Progress) v[2]).set(r != null && r.totalTime > 0 ? (float) r.playTime / r.totalTime : 0f);
            ((TextView) v[3]).setText(p.title);
            ((TextView) v[4]).setText(p.year);
            h.itemView.setContentDescription(p.title);
            h.itemView.setOnClickListener(x -> TitleOpener.open(activity, p));
        }

        @Override public int getItemCount() { return items.size(); }
    }

    /** 剧集热榜：大号描边序号 + 海报。 */
    private final class RankAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Poster> items = new ArrayList<>();

        void set(List<Poster> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout root = new FrameLayout(c);
            root.setLayoutParams(new RecyclerView.LayoutParams(Ui.dp(c, 158), Ui.dp(c, 172)));
            // 空心大数字：只画描边
            TextView number = new TextView(c);
            number.getPaint().setStyle(android.graphics.Paint.Style.STROKE);
            number.getPaint().setStrokeWidth(Ui.dp(c, 2));
            number.setTextColor(Style.text4(c));
            number.setTextSize(100);
            Style.weight(number, 900);
            number.setIncludeFontPadding(false);
            root.addView(number, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
            FrameLayout cover = PosterAdapter.coverFrame(c, 3, 2, 10);
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView title = PosterAdapter.bottomTag(c);
            title.setGravity(Gravity.START);
            cover.addView(title);
            TextView rate = PosterAdapter.rateTag(c);
            cover.addView(rate);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.dp(c, 106), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            cp.rightMargin = Ui.dp(c, 6);
            cp.bottomMargin = Ui.dp(c, 6);
            root.addView(cover, cp);
            root.setTag(new View[]{number, image, title, rate});
            return new RecyclerView.ViewHolder(root) { };
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Poster p = items.get(position);
            View[] v = (View[]) h.itemView.getTag();
            ((TextView) v[0]).setText(String.valueOf(position + 1));
            DecoApp.get().images.cover((ImageView) v[1], p.poster);
            ((TextView) v[2]).setText(p.title);
            boolean rated = !p.rate.isEmpty() && !p.rate.startsWith("0");
            ((TextView) v[3]).setText(rated ? "★ " + p.rate : "");
            v[3].setVisibility(rated ? View.VISIBLE : View.GONE);
            h.itemView.setContentDescription("第 " + (position + 1) + " 名 " + p.title);
            h.itemView.setOnClickListener(x -> TitleOpener.open(activity, p));
        }

        @Override public int getItemCount() { return items.size(); }
    }
}
