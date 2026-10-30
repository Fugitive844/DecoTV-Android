package io.github.fugitive844.decotv.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.AppBarLayout;

/**
 * 片库每个标签的骨架：上面是筛选区（往上滑时收起、往下滑时出现），下面是海报网格；
 * 滑到底时回调加载下一页。
 */
final class GridPane {
    interface More { void load(); }

    final CoordinatorLayout view;
    final LinearLayout header;
    final PosterAdapter adapter;
    final RecyclerView list;
    private final TextView status;
    private final AppBarLayout bar;

    GridPane(MainActivity activity, More more) {
        view = new CoordinatorLayout(activity);
        view.setBackgroundColor(Style.bg(activity));
        bar = new AppBarLayout(activity);
        bar.setBackgroundColor(Style.bg(activity));
        bar.setElevation(0);
        bar.setStateListAnimator(null);
        // 筛选区放在圆角卡片里
        FrameLayout wrap = new FrameLayout(activity);
        wrap.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 6), Ui.dp(activity, 16), Ui.dp(activity, 8));
        header = Ui.column(activity, 0);
        header.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 10));
        header.setBackground(Style.round(activity, Style.surface(activity), 16));
        wrap.addView(header);
        AppBarLayout.LayoutParams hp = new AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setScrollFlags(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL | AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS);
        bar.addView(wrap, hp);
        view.addView(bar, new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(activity);
        adapter = new PosterAdapter(0, p -> TitleOpener.open(activity, p));
        list = new RecyclerView(activity);
        GridLayoutManager grid = PosterAdapter.grid(activity);
        list.setLayoutManager(grid);
        list.setAdapter(adapter);
        int pad = Ui.dp(activity, 10);
        list.setPadding(pad, 0, pad, Ui.dp(activity, 24));
        list.setClipToPadding(false);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && grid.findLastVisibleItemPosition() >= adapter.getItemCount() - grid.getSpanCount() * 2) more.load();
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = Style.text(activity, "", 13, 400, Style.text3(activity));
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sp.bottomMargin = Ui.dp(activity, 4);
        body.addView(status, sp);
        CoordinatorLayout.LayoutParams bp = new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        bp.setBehavior(new AppBarLayout.ScrollingViewBehavior());
        view.addView(body, bp);
    }

    /** 底部状态文字（加载中 / 没有更多 / 失败原因）；空字符串隐藏。列表为空时显示在中间。 */
    void status(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) status.getLayoutParams();
        lp.gravity = adapter.getItemCount() == 0 ? Gravity.CENTER : Gravity.BOTTOM;
        status.setLayoutParams(lp);
    }

    /** 换了筛选条件：清空并回到顶部，筛选区展开。 */
    void reset() {
        adapter.set(java.util.Collections.emptyList());
        list.scrollToPosition(0);
        bar.setExpanded(true, false);
    }
}
