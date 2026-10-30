package io.github.fugitive844.decotv.danmaku;

import android.content.Context;
import android.content.SharedPreferences;

/** 弹幕显示设置，本机保存，在线与离线播放共用。 */
public final class DanmakuSettings {
    /** 速度档位对应滚动弹幕横穿一个屏幕宽度所需的秒数，与常见播放器的「慢 → 快」一致。 */
    static final float[] CROSS_SECONDS = {12f, 10f, 8f, 6.5f, 5f};
    public static final String[] SPEED_LABELS = {"很慢", "慢", "正常", "快", "很快"};
    public static final float[] AREAS = {0.25f, 0.5f, 0.75f, 1f};
    public static final String[] AREA_LABELS = {"1/4 屏", "半屏", "3/4 屏", "全屏"};
    public static final int[] SIZES = {12, 14, 16, 19, 22};
    public static final String[] SIZE_LABELS = {"很小", "小", "标准", "大", "很大"};
    public static final int[] LIMITS = {0, 30, 60, 100};
    public static final String[] LIMIT_LABELS = {"不限", "30 条", "60 条", "100 条"};

    private final SharedPreferences sp;

    public boolean enabled, antiOverlap;
    public float opacity;
    public int speed, area, size, limit;
    public final boolean[] blocked = new boolean[3];
    public String keywords;

    public DanmakuSettings(Context c) {
        sp = c.getSharedPreferences("danmaku", Context.MODE_PRIVATE);
        enabled = sp.getBoolean("enabled", true);
        antiOverlap = sp.getBoolean("anti_overlap", true);
        opacity = sp.getFloat("opacity", 0.85f);
        speed = clamp(sp.getInt("speed", 2), CROSS_SECONDS.length);
        area = clamp(sp.getInt("area", 1), AREAS.length);
        size = clamp(sp.getInt("size", 2), SIZES.length);
        limit = clamp(sp.getInt("limit", 0), LIMITS.length);
        for (int i = 0; i < 3; i++) blocked[i] = sp.getBoolean("block_" + i, false);
        keywords = sp.getString("keywords", "");
    }

    private static int clamp(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }

    public void save() {
        SharedPreferences.Editor e = sp.edit().putBoolean("enabled", enabled).putBoolean("anti_overlap", antiOverlap)
                .putFloat("opacity", opacity).putInt("speed", speed).putInt("area", area).putInt("size", size)
                .putInt("limit", limit).putString("keywords", keywords);
        for (int i = 0; i < 3; i++) e.putBoolean("block_" + i, blocked[i]);
        e.apply();
    }

    /** 手动匹配：按「标题|年份」记住，换源后仍沿用。值是 {@link ManualMatch#toJson()}。 */
    public String manualMatch(String key) { return sp.getString("match_" + key, ""); }

    public void setManualMatch(String key, String json) {
        if (json == null || json.isEmpty()) sp.edit().remove("match_" + key).apply();
        else sp.edit().putString("match_" + key, json).apply();
    }
}
