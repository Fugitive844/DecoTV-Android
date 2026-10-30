package io.github.fugitive844.decotv.danmaku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 弹幕层：叠在画面上、控制栏下面，不接收触摸。
 * 每帧按播放器的媒体时间计算位置，所以暂停时弹幕停住、倍速时跟着变快，拖动进度后从新位置重新开始。
 * 滚动弹幕以固定的像素速度从右向左移动（长短弹幕同速，不会追尾）；顶部 / 底部弹幕居中停留 4 秒。
 */
public final class DanmakuView extends View implements Choreographer.FrameCallback {
    /** 媒体时间来源；未就绪时返回负数。 */
    public interface Clock { long positionMs(); }

    static final long FIXED_MS = 4000;
    private static final long STALE_MS = 1000, JUMP_MS = 1500;

    private static final class Active {
        Danmaku d;
        float width;
        int lane;
        long start;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Active> active = new ArrayList<>();
    private final Random random = new Random();
    private List<Danmaku> all = Collections.emptyList(), shown = Collections.emptyList();
    private DanmakuSettings settings;
    private Clock clock;
    private long offsetMs, lastT = Long.MIN_VALUE;
    private int next;
    private boolean running;
    private float lineHeight, ascent;
    private long[] scrollStart = new long[0], topUntil = new long[0], bottomUntil = new long[0];
    private float[] scrollWidth = new float[0];

    public DanmakuView(Context c) {
        super(c);
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        stroke.setTypeface(Typeface.DEFAULT_BOLD);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xFF000000);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setClock(Clock clock) { this.clock = clock; }

    public void setSettings(DanmakuSettings s) {
        settings = s;
        rebuild();
    }

    public void setData(List<Danmaku> items) {
        all = items == null ? Collections.<Danmaku>emptyList() : items;
        rebuild();
    }

    public void setOffset(long ms) {
        offsetMs = ms;
        reset();
    }

    public int count() { return all.size(); }

    /** 设置或数据变化后重新过滤，并从当前时间重新排布。 */
    private void rebuild() {
        if (settings == null) return;
        List<String> keywords = Danmaku.keywords(settings.keywords);
        List<Danmaku> list = new ArrayList<>(all.size());
        for (Danmaku d : all) if (d.allowed(settings.blocked, keywords)) list.add(d);
        shown = list;
        float density = getResources().getDisplayMetrics().scaledDensity;
        float px = DanmakuSettings.SIZES[settings.size] * density;
        // 竖屏小窗里字号缩小一些，避免挡住太多画面
        if (getHeight() > 0 && getHeight() < 420 * getResources().getDisplayMetrics().density) px *= 0.85f;
        fill.setTextSize(px);
        stroke.setTextSize(px);
        stroke.setStrokeWidth(Math.max(1.5f, px / 12f));
        Paint.FontMetrics fm = fill.getFontMetrics();
        ascent = -fm.ascent;
        lineHeight = (fm.descent - fm.ascent) * 1.15f;
        int alpha = Math.round(255 * Math.max(0.1f, Math.min(1f, settings.opacity)));
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
        setVisibility(settings.enabled ? VISIBLE : INVISIBLE);
        reset();
    }

    private void reset() {
        active.clear();
        lastT = Long.MIN_VALUE;
        int lanes = lanes();
        scrollStart = new long[lanes];
        scrollWidth = new float[lanes];
        topUntil = new long[lanes];
        bottomUntil = new long[lanes];
        java.util.Arrays.fill(scrollStart, Long.MIN_VALUE);
        invalidate();
    }

    int lanes() {
        if (settings == null || lineHeight <= 0 || getHeight() <= 0) return 1;
        return Math.max(1, (int) (getHeight() * DanmakuSettings.AREAS[settings.area] / lineHeight));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuild();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        Choreographer.getInstance().postFrameCallback(this);
        if (settings == null || !settings.enabled || clock == null || getWidth() == 0) return;
        long pos = clock.positionMs();
        if (pos < 0) return;
        long t = pos + offsetMs;
        if (t == lastT) return;
        // 往回拖或跳过一大段：清屏，从新位置开始
        if (lastT == Long.MIN_VALUE || t < lastT - 300 || t > lastT + JUMP_MS) {
            active.clear();
            java.util.Arrays.fill(scrollStart, Long.MIN_VALUE);
            java.util.Arrays.fill(topUntil, 0);
            java.util.Arrays.fill(bottomUntil, 0);
            next = lowerBound(shown, t - STALE_MS);
        }
        lastT = t;
        float speed = speed();
        for (Iterator<Active> it = active.iterator(); it.hasNext(); ) {
            Active a = it.next();
            boolean gone = a.d.mode == Danmaku.SCROLL ? (t - a.start) * speed > getWidth() + a.width : t - a.start > FIXED_MS;
            if (gone || t < a.start) it.remove();
        }
        int limit = DanmakuSettings.LIMITS[settings.limit];
        while (next < shown.size() && shown.get(next).timeMs <= t) {
            Danmaku d = shown.get(next++);
            if (d.timeMs < t - STALE_MS) continue;
            if (limit > 0 && active.size() >= limit) continue;
            place(d, t, speed);
        }
        invalidate();
    }

    private float speed() {
        return getWidth() / (DanmakuSettings.CROSS_SECONDS[settings.speed] * 1000f);
    }

    private void place(Danmaku d, long t, float speed) {
        float width = fill.measureText(d.text);
        float gap = lineHeight;
        int lane;
        if (d.mode == Danmaku.SCROLL) lane = pickScroll(t, scrollStart, scrollWidth, speed, gap);
        else lane = pickFixed(t, d.mode == Danmaku.TOP ? topUntil : bottomUntil);
        if (lane < 0) {
            if (settings.antiOverlap) return;
            lane = random.nextInt(scrollStart.length);
        }
        Active a = new Active();
        a.d = d;
        a.width = width;
        a.lane = lane;
        a.start = t;
        if (d.mode == Danmaku.SCROLL) {
            scrollStart[lane] = t;
            scrollWidth[lane] = width;
        } else {
            (d.mode == Danmaku.TOP ? topUntil : bottomUntil)[lane] = t + FIXED_MS;
        }
        active.add(a);
    }

    /** 滚动弹幕选行：上一条已完全进入屏幕并留出间距的最上面一行；没有返回 -1。 */
    static int pickScroll(long t, long[] starts, float[] widths, float speed, float gap) {
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] == Long.MIN_VALUE || (t - starts[i]) * speed >= widths[i] + gap) return i;
        }
        return -1;
    }

    /** 顶部 / 底部弹幕选行：已经到期的第一行；没有返回 -1。 */
    static int pickFixed(long t, long[] until) {
        for (int i = 0; i < until.length; i++) if (until[i] <= t) return i;
        return -1;
    }

    static int lowerBound(List<Danmaku> list, long t) {
        int lo = 0, hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).timeMs < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (settings == null || !settings.enabled || active.isEmpty() || lastT == Long.MIN_VALUE) return;
        float speed = speed();
        for (Active a : active) {
            float x, y;
            if (a.d.mode == Danmaku.SCROLL) {
                x = getWidth() - (lastT - a.start) * speed;
                y = a.lane * lineHeight + ascent;
            } else {
                x = (getWidth() - a.width) / 2f;
                y = a.d.mode == Danmaku.TOP ? a.lane * lineHeight + ascent : getHeight() - (a.lane + 1) * lineHeight + ascent;
            }
            canvas.drawText(a.d.text, x, y, stroke);
            fill.setColor(a.d.color);
            fill.setAlpha(stroke.getAlpha());
            canvas.drawText(a.d.text, x, y, fill);
        }
    }
}
