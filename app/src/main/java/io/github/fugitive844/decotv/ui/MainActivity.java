package io.github.fugitive844.decotv.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import io.github.fugitive844.decotv.R;
import io.github.fugitive844.decotv.update.AppUpdater;

import java.util.ArrayDeque;

/**
 * 底部导航五个页：首页、搜索、片库（电影 / 剧集 / 动漫 / 综艺 / 源浏览器）、直播、我的。
 * 播放记录、收藏、搜索记录、离线缓存、设置收在「我的」里，以二级页面打开：标题栏有返回，系统返回键也回到上一层。
 */
public class MainActivity extends AppCompatActivity {
    interface Page {
        View view();
        void show();
        default void destroy() { }
    }

    static final int HOME = 0, SEARCH = 1, LIBRARY = 2, LIVE = 3, MINE = 4;
    private static final String[] TITLES = {"首页", "搜索", "片库", "直播", "我的"};
    private static final int[] ICONS = {R.drawable.ic_nav_home, R.drawable.ic_nav_search, R.drawable.ic_nav_library,
            R.drawable.ic_nav_live, R.drawable.ic_nav_mine};

    private static final class Pushed {
        final Page page;
        final String title;

        Pushed(Page page, String title) {
            this.page = page;
            this.title = title;
        }
    }

    private final Page[] pages = new Page[5];
    private final ArrayDeque<Pushed> stack = new ArrayDeque<>();
    private FrameLayout container;
    private LinearLayout topBar;
    private android.widget.TextView topTitle;
    private BottomNavigationView nav;
    private int current = -1;
    private OnBackPressedCallback back;

    private AppUpdater updater;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Style.systemBars(this);
        updater = new AppUpdater(this);
        LinearLayout root = Ui.column(this, 0);
        root.setBackgroundColor(Style.bg(this));
        // 二级页面的标题栏：返回 + 标题；一级页面自己画标题
        topBar = Ui.row(this);
        topBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        android.widget.ImageView up = Style.iconButton(this, R.drawable.ic_back, "返回", 0, Style.text(this));
        up.setOnClickListener(v -> pop());
        topBar.addView(up);
        topTitle = Style.single(Style.text(this, "", 20, 700, Style.text(this)));
        topBar.addView(topTitle, Ui.weight(1));
        topBar.setVisibility(View.GONE);
        root.addView(topBar);
        container = new FrameLayout(this);
        root.addView(container, Ui.fill(1));
        View divider = new View(this);
        divider.setBackgroundColor(Style.line(this));
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));
        nav = new BottomNavigationView(this);
        Menu menu = nav.getMenu();
        for (int i = 0; i < TITLES.length; i++) menu.add(Menu.NONE, i, i, TITLES[i]).setIcon(ICONS[i]);
        nav.setLabelVisibilityMode(BottomNavigationView.LABEL_VISIBILITY_LABELED);
        nav.setBackgroundColor(Style.color(this, R.color.deco_nav));
        nav.setElevation(0);
        int[][] checked = {{android.R.attr.state_checked}, {}};
        android.content.res.ColorStateList tint = new android.content.res.ColorStateList(checked, new int[]{Style.accent(this), Style.text2(this)});
        nav.setItemIconTintList(tint);
        nav.setItemTextColor(tint);
        nav.setItemActiveIndicatorColor(android.content.res.ColorStateList.valueOf(Style.accentSoft(this)));
        nav.setOnItemSelectedListener(item -> {
            select(item.getItemId());
            return true;
        });
        // 再次点当前标签：有二级页面就回到这个标签的首层。启动时默认选中的第一项也会走这里，所以未显示过的要照常打开
        nav.setOnItemReselectedListener(item -> {
            if (!stack.isEmpty() || current != item.getItemId()) select(item.getItemId());
        });
        root.addView(nav);
        setContentView(root);
        back = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { pop(); }
        };
        getOnBackPressedDispatcher().addCallback(this, back);
        nav.setSelectedItemId(state == null ? HOME : state.getInt("page", HOME));
        // 在设置里切换深浅模式会重建界面，回到设置页
        if (state != null && state.getBoolean("settings")) push(new SettingsPage(this), "设置");
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", current);
        out.putBoolean("settings", !stack.isEmpty() && stack.peek().page instanceof SettingsPage);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!stack.isEmpty()) stack.peek().page.show();
        else if (current >= 0 && current != SEARCH) pages[current].show();
        updater.resume();
        updater.check(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        updater.close();
        clearStack();
        for (Page p : pages) if (p != null) p.destroy();
    }

    AppUpdater updater() { return updater; }

    private Page page(int index) {
        if (pages[index] == null) {
            switch (index) {
                case HOME: pages[index] = new HomePage(this); break;
                case SEARCH: pages[index] = new SearchPage(this); break;
                case LIBRARY: pages[index] = new LibraryPage(this); break;
                case LIVE: pages[index] = new LivePage(this); break;
                default: pages[index] = new MinePage(this); break;
            }
        }
        return pages[index];
    }

    private void clearStack() {
        while (!stack.isEmpty()) stack.pop().page.destroy();
    }

    private void select(int index) {
        clearStack();
        current = index;
        render(page(index), TITLES[index]);
        page(index).show();
    }

    private void render(Page page, String title) {
        setTitle(title);
        topTitle.setText(title);
        topBar.setVisibility(stack.isEmpty() ? View.GONE : View.VISIBLE);
        back.setEnabled(!stack.isEmpty());
        container.removeAllViews();
        container.addView(page.view(), Ui.match());
    }

    /** 在当前标签里打开二级页面。 */
    void push(Page page, String title) {
        stack.push(new Pushed(page, title));
        render(page, title);
        page.show();
    }

    private void pop() {
        if (stack.isEmpty()) return;
        stack.pop().page.destroy();
        if (stack.isEmpty()) {
            render(page(current), TITLES[current]);
            page(current).show();
        } else {
            render(stack.peek().page, stack.peek().title);
            stack.peek().page.show();
        }
    }

    void selectTab(int tab) { nav.setSelectedItemId(tab); }

    /** 从搜索记录或「找不到播放源」跳到搜索页并立即搜索。 */
    void searchFor(String keyword) {
        nav.setSelectedItemId(SEARCH);
        ((SearchPage) page(SEARCH)).search(keyword);
    }

    /** 首页各栏的「更多」：打开片库的对应分类。 */
    void openLibrary(int tab) {
        nav.setSelectedItemId(LIBRARY);
        ((LibraryPage) page(LIBRARY)).select(tab);
    }

    // ---------- 供联调测试读取 ----------

    /** 首页各栏条数：继续观看、热门电影、热门剧集、新番放送、热门综艺。 */
    public int[] homeCounts() { return ((HomePage) page(HOME)).counts(); }

    public void openLibraryForTest(int tab) { openLibrary(tab); }

    /** 片库当前标签已加载的条数。 */
    public int libraryCount() { return ((LibraryPage) page(LIBRARY)).count(); }

    public String librarySourceName() { return ((LibraryPage) page(LIBRARY)).sourceName(); }

    public int sourceCountForTest() { return ((LibraryPage) page(LIBRARY)).sourceCount(); }

    public void selectSourceForTest(int i) { ((LibraryPage) page(LIBRARY)).selectSource(i); }

    public void openMineForTest() { nav.setSelectedItemId(MINE); }

    public int stackDepth() { return stack.size(); }

    public void searchForTest(String keyword) { searchFor(keyword); }

    /** 是否正显示「正在打开」的等待框。 */
    public boolean openingBusy() { return Opening.busy(); }

    public void openSettingsForTest() { push(new SettingsPage(this), "设置"); }

    public void openLiveForTest() { nav.setSelectedItemId(LIVE); }

    /** 直播页状态，供联调测试读取：状态行文字、频道数、是否正在拉取频道列表。 */
    public String liveStatus() { return ((LivePage) page(LIVE)).statusText(); }

    public int liveChannelCount() { return ((LivePage) page(LIVE)).channelCount(); }

    public boolean liveRefreshing() { return ((LivePage) page(LIVE)).refreshing(); }

    public void liveRefreshForTest() { ((LivePage) page(LIVE)).refreshForTest(); }
}
