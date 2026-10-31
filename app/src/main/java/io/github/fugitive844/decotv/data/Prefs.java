package io.github.fugitive844.decotv.data;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.fugitive844.decotv.BuildConfig;
import io.github.fugitive844.decotv.net.Relay;

/** 本机保存的服务器地址、登录 cookie 与各项中转策略。密码不落盘。 */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences("deco", Context.MODE_PRIVATE);
    }

    public String server() { return Relay.normalizeServer(sp.getString("server", BuildConfig.DEFAULT_SERVER)); }
    public String username() { return sp.getString("username", ""); }
    public String cookie() { return sp.getString("cookie", ""); }
    public boolean loggedIn() { return !cookie().isEmpty(); }

    public void saveLogin(String server, String username, String cookie) {
        sp.edit().putString("server", Relay.normalizeServer(server)).putString("username", username)
                .putString("cookie", cookie).apply();
    }

    public void logout() { sp.edit().remove("cookie").apply(); }

    /** 访问秘钥：按设备保存，退出登录不清除；请求本服务器时作为 URL 参数 ak 带上。 */
    public String accessKey() { return sp.getString("access_key", ""); }

    public void setAccessKey(String key) { sp.edit().putString("access_key", key == null ? "" : key.trim()).apply(); }

    /** 当前账号的角色（owner 站长、admin 管理员、user 用户），取自登录 cookie 里的 JSON，与网页端一致。 */
    public String role() { return roleOf(cookie()); }

    /** cookie 值是 URL 编码的 JSON，可能被编码了两次。 */
    public static String roleOf(String cookie) {
        String s = cookie == null ? "" : cookie.trim();
        try {
            for (int i = 0; i < 2 && !s.startsWith("{"); i++) s = java.net.URLDecoder.decode(s, "UTF-8");
            return new org.json.JSONObject(s).optString("role", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 搜索结果是否聚合显示，默认开启，与网页端一致。 */
    public boolean aggregateSearch() { return sp.getBoolean("aggregate_search", true); }

    public void setAggregateSearch(boolean on) { sp.edit().putBoolean("aggregate_search", on).apply(); }

    /** 外观：system 跟随系统（默认）、light 浅色、dark 深色。 */
    public static final String[] THEME_MODES = {"system", "light", "dark"};

    public String themeMode() { return sp.getString("theme_mode", "system"); }

    public void setThemeMode(String mode) { sp.edit().putString("theme_mode", mode).apply(); }

    /** 按外观设置切换深浅模式；已打开的界面由 AppCompat 自动重建。 */
    public void applyThemeMode() {
        String m = themeMode();
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode("light".equals(m) ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                : "dark".equals(m) ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** 已提示过的系统退出记录时间，避免同一次异常退出重复弹窗。 */
    public long lastExitSeen() { return sp.getLong("last_exit_seen", 0); }

    public void setLastExitSeen(long time) { sp.edit().putLong("last_exit_seen", time).apply(); }

    /** 上次选择的缓存格式，下次弹窗默认选中。 */
    public String downloadFormat() { return sp.getString("download_format", "encrypted"); }

    public void setDownloadFormat(String key) { sp.edit().putString("download_format", key).apply(); }

    /** 离线缓存同时进行的任务数。 */
    public int downloadParallel() { return sp.getInt("download_parallel", io.github.fugitive844.decotv.download.DownloadService.DEFAULT_PARALLEL); }

    public void setDownloadParallel(int n) { sp.edit().putInt("download_parallel", n).apply(); }

    /** 源浏览器上次选的源。 */
    public String browseSource() { return sp.getString("browse_source", ""); }

    public void setBrowseSource(String key) { sp.edit().putString("browse_source", key).apply(); }

    /** 播放失败时是否自动换到下一个源，点播与直播共用，默认开启。 */
    public boolean autoSwitchSource() { return sp.getBoolean("auto_switch_source", true); }

    public void setAutoSwitchSource(boolean on) { sp.edit().putBoolean("auto_switch_source", on).apply(); }

    /** 自动换源的网速条件：起播超时时缓冲网速不低于这个值（KB/s）就继续等，0 表示不看网速。 */
    public static final int[] SWITCH_SPEEDS_KB = {0, 20, 50, 100, 200, 500};

    public int switchMinSpeedKb() { return Math.max(0, sp.getInt("switch_min_speed_kb", 50)); }

    public void setSwitchMinSpeedKb(int kb) { sp.edit().putInt("switch_min_speed_kb", kb).apply(); }

    public float playbackSpeed() {
        float speed = sp.getFloat("playback_speed", 1f);
        return speed >= 0.25f && speed <= 4f ? speed : 1f;
    }

    public void setPlaybackSpeed(float speed) { sp.edit().putFloat("playback_speed", speed).apply(); }

    /**
     * 只执行一次的设置迁移，App 启动时调用。
     * 1.4.2：安装后首次启动把「直播列表」改为全中转一次（直连 GitHub 等列表地址经常很慢），之后完全按用户的设置。
     */
    public void applyOneTimeMigrations() {
        if (!sp.getBoolean("migrated_live_list_relay_1_4_2", false)) {
            sp.edit().putString("relay_" + Relay.Item.LIVE_LIST.key, Relay.Mode.RELAY.key)
                    .putBoolean("migrated_live_list_relay_1_4_2", true).apply();
        }
    }

    public Relay.Mode mode(Relay.Item item) {
        return Relay.Mode.of(sp.getString("relay_" + item.key, Relay.Mode.DIRECT.key));
    }

    public void setMode(Relay.Item item, Relay.Mode mode) {
        sp.edit().putString("relay_" + item.key, mode.key).apply();
    }
}
