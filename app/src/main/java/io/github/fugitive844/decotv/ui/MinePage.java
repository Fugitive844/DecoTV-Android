package io.github.fugitive844.decotv.ui;

import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import io.github.fugitive844.decotv.BuildConfig;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.R;
import io.github.fugitive844.decotv.data.Prefs;
import io.github.fugitive844.decotv.download.DownloadTask;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.Tasks;

import org.json.JSONObject;

/**
 * 底部导航「我的」：账号信息，以及播放记录、收藏、搜索记录、离线缓存、访问秘钥、设置、修改密码、退出登录。
 * 前几项在本页内以二级页面打开（标题栏可返回）。修改密码的显示条件与网页端一致。
 */
final class MinePage implements MainActivity.Page {
    private final MainActivity activity;
    private final ScrollView root;
    private final TextView account, avatar, roleBadge, downloads;
    private final View accessKeyRow, changePassword, passwordDivider;
    private String role = "";

    MinePage(MainActivity activity) {
        this.activity = activity;
        LinearLayout body = Ui.column(activity, 0);
        body.setBackgroundColor(Style.bg(activity));
        body.setPadding(0, 0, 0, Ui.dp(activity, 24));

        // 账号：头像、用户名、身份、服务器，右侧进设置
        LinearLayout head = Ui.row(activity);
        head.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 16), 0);
        avatar = Style.text(activity, "", 24, 900, Style.onAccent(activity));
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Style.round(activity, Style.accent(activity), 30));
        head.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(activity, 60), Ui.dp(activity, 60)));
        LinearLayout who = Ui.column(activity, 0);
        who.setPadding(Ui.dp(activity, 14), 0, Ui.dp(activity, 8), 0);
        LinearLayout nameRow = Ui.row(activity);
        account = Style.single(Style.text(activity, "", 20, 700, Style.text(activity)));
        nameRow.addView(account);
        roleBadge = Style.badge(activity, "", Style.accentSoft(activity), Style.accent(activity));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Ui.dp(activity, 8);
        nameRow.addView(roleBadge, bp);
        who.addView(nameRow);
        TextView server = Style.single(Style.text(activity, DecoApp.get().prefs.server(), 12, 400, Style.text3(activity)));
        server.setPadding(0, Ui.dp(activity, 4), 0, 0);
        who.addView(server);
        head.addView(who, Ui.weight(1));
        View gear = Style.iconButton(activity, R.drawable.ic_settings, "打开设置", Style.surface2(activity), Style.text(activity));
        gear.setOnClickListener(v -> openSettings());
        head.addView(gear);
        body.addView(head);

        // 三个常用入口
        LinearLayout tiles = Ui.row(activity);
        tiles.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 22), Ui.dp(activity, 16), 0);
        tiles.addView(tile(R.drawable.ic_history, 0xFF5AA9FF, "播放记录",
                v -> activity.push(new RecordsPage(activity, RecordsPage.PLAYS), "播放记录")), tileParams(true));
        tiles.addView(tile(R.drawable.ic_heart_filled, 0xFFFF6B8B, "收藏",
                v -> activity.push(new RecordsPage(activity, RecordsPage.FAVORITES), "收藏")), tileParams(true));
        FrameLayout cache = tile(R.drawable.ic_download, Style.accent(activity), "离线缓存", v -> activity.push(new DownloadsPage(activity), "离线缓存"));
        downloads = Style.badge(activity, "", Style.accent(activity), Style.onAccent(activity));
        downloads.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        dp.setMargins(0, Ui.dp(activity, 10), Ui.dp(activity, 10), 0);
        cache.addView(downloads, dp);
        tiles.addView(cache, tileParams(false));
        body.addView(tiles);

        LinearLayout card = Style.card(activity);
        card.addView(Style.listRow(activity, R.drawable.ic_nav_search, "搜索记录", "",
                v -> activity.push(new RecordsPage(activity, RecordsPage.KEYWORDS), "搜索记录")));
        card.addView(Style.divider(activity));
        accessKeyRow = Style.listRow(activity, R.drawable.ic_key, "访问秘钥", "", v -> showAccessKey());
        card.addView(accessKeyRow);
        card.addView(Style.divider(activity));
        card.addView(Style.listRow(activity, R.drawable.ic_settings, "设置", "外观 · 播放 · 缓存", v -> openSettings()));
        passwordDivider = Style.divider(activity);
        passwordDivider.setVisibility(View.GONE);
        card.addView(passwordDivider);
        changePassword = Style.listRow(activity, R.drawable.ic_lock, "修改密码", "", v -> showChangePassword());
        changePassword.setVisibility(View.GONE);
        card.addView(changePassword);
        card.addView(Style.divider(activity));
        card.addView(Style.listRow(activity, R.drawable.ic_update, "检查更新", "版本 " + BuildConfig.VERSION_NAME, v -> activity.updater().check(true)));
        body.addView(card, Style.margins(activity, 16, 22, 16, 0));

        LinearLayout out = Style.card(activity);
        TextView logout = Style.text(activity, "退出登录", 15, 500, Style.danger(activity));
        logout.setGravity(Gravity.CENTER);
        logout.setMinHeight(Ui.dp(activity, 52));
        logout.setBackground(Style.pressable(activity, 0, 0));
        logout.setContentDescription("退出登录");
        logout.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity).setMessage("退出当前账号？")
                .setNegativeButton("取消", null).setPositiveButton("退出", (d, w) -> logout(null)).show());
        out.addView(logout);
        body.addView(out, Style.margins(activity, 16, 12, 16, 0));

        TextView version = Style.text(activity, "版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", 12, 400, Style.text3(activity));
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, Ui.dp(activity, 18), 0, 0);
        body.addView(version);
        root = new ScrollView(activity);
        root.setBackgroundColor(Style.bg(activity));
        root.addView(body);
    }

    private void openSettings() { activity.push(new SettingsPage(activity), "设置"); }

    /** 「我的」顶部的入口方块：彩色图标在左上，名称在左下。 */
    private FrameLayout tile(int icon, int tint, String title, View.OnClickListener l) {
        FrameLayout box = new FrameLayout(activity);
        box.setBackground(Style.pressable(activity, Style.surface(activity), 16));
        box.setOnClickListener(l);
        box.setContentDescription(title);
        LinearLayout inner = Ui.column(activity, 12);
        inner.addView(Style.icon(activity, icon, tint, 24));
        View space = new View(activity);
        inner.addView(space, new LinearLayout.LayoutParams(1, 0, 1));
        inner.addView(Style.text(activity, title, 14, 500, Style.text(activity)));
        box.addView(inner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 88)));
        return box;
    }

    private LinearLayout.LayoutParams tileParams(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(activity, 88), 1);
        if (gap) lp.rightMargin = Ui.dp(activity, 10);
        return lp;
    }

    @Override public View view() { return root; }

    @Override public void show() {
        Prefs prefs = DecoApp.get().prefs;
        role = prefs.role();
        String roleName = "owner".equals(role) ? "站长" : "admin".equals(role) ? "管理员" : "user".equals(role) ? "用户" : "";
        String name = prefs.username();
        account.setText(name);
        avatar.setText(name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT));
        roleBadge.setText(roleName);
        roleBadge.setVisibility(roleName.isEmpty() ? View.GONE : View.VISIBLE);
        int active = 0;
        for (DownloadTask t : DecoApp.get().downloads.all()) if (t.state != DownloadTask.State.DONE) active++;
        downloads.setText(String.valueOf(active));
        downloads.setContentDescription(active + " 个未完成");
        downloads.setVisibility(active > 0 ? View.VISIBLE : View.GONE);
        refreshAccessKeyState();
        // 与网页端一致：数据库存储、非公开模式、不是站长时才能修改密码
        Tasks.run(() -> DecoApp.get().api.serverConfig(), (JSONObject cfg) -> {
            int v = canChangePassword(cfg.optString("StorageType", "localstorage"), cfg.optString("AuthMode", "password"), role) ? View.VISIBLE : View.GONE;
            changePassword.setVisibility(v);
            passwordDivider.setVisibility(v);
        }, e -> { });
    }

    static boolean canChangePassword(String storageType, String authMode, String role) {
        return !"localstorage".equals(storageType) && !"public".equals(authMode) && !"owner".equals(role);
    }

    private void showChangePassword() {
        LinearLayout box = Ui.column(activity, 20);
        TextInputLayout newLayout = new TextInputLayout(activity);
        newLayout.setHint("新密码");
        TextInputEditText next = Ui.input(newLayout, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(newLayout);
        TextInputLayout confirmLayout = new TextInputLayout(activity);
        confirmLayout.setHint("确认新密码");
        TextInputEditText confirm = Ui.input(confirmLayout, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(confirmLayout);
        TextView hint = Ui.text(activity, "修改密码后需要重新登录", 12, false);
        hint.setAlpha(0.6f);
        box.addView(hint);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("修改密码").setView(box)
                .setNegativeButton("取消", null).setPositiveButton("确认修改", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = String.valueOf(next.getText()), b = String.valueOf(confirm.getText());
            confirmLayout.setError(null);
            newLayout.setError(null);
            if (a.isEmpty()) {
                newLayout.setError("新密码不得为空");
                return;
            }
            if (!a.equals(b)) {
                confirmLayout.setError("两次输入的密码不一致");
                return;
            }
            v.setEnabled(false);
            Tasks.run(() -> {
                DecoApp.get().api.changePassword(a);
                return true;
            }, ok -> {
                dialog.dismiss();
                logout("密码已修改，请用新密码重新登录");
            }, e -> {
                v.setEnabled(true);
                newLayout.setError(e.getMessage());
            });
        }));
        dialog.show();
    }

    /** 右侧显示秘钥状态：未填写 / 已填写。 */
    private void refreshAccessKeyState() {
        Style.rowValue(accessKeyRow, DecoApp.get().prefs.accessKey().isEmpty() ? "未填写" : "已填写");
    }

    private void showAccessKey() {
        LinearLayout box = Ui.column(activity, 20);
        TextInputLayout layout = new TextInputLayout(activity);
        layout.setHint("访问秘钥");
        TextInputEditText input = Ui.input(layout, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(DecoApp.get().prefs.accessKey());
        box.addView(layout);
        TextView hint = Ui.text(activity, ApiClient.ACCESS_KEY_HINT, 12, false);
        hint.setAlpha(0.6f);
        box.addView(hint);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("访问秘钥").setView(box)
                .setNegativeButton("取消", null).setNeutralButton("清除", (d, w) -> {
                    DecoApp.get().prefs.setAccessKey("");
                    refreshAccessKeyState();
                    Ui.toast(activity, "已清除访问秘钥");
                }).setPositiveButton("保存", null).create();
        // 不校验，直接保存
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = String.valueOf(input.getText()).trim();
            layout.setError(null);
            if (key.isEmpty()) {
                layout.setError("请填写访问秘钥，或点「清除」");
                return;
            }
            DecoApp.get().prefs.setAccessKey(key);
            dialog.dismiss();
            refreshAccessKeyState();
            Ui.toast(activity, "访问秘钥已保存");
        }));
        dialog.show();
    }

    private void logout(String notice) {
        Tasks.run(() -> { DecoApp.get().api.logout(); return true; }, ok -> { }, e -> { });
        DecoApp.get().prefs.logout();
        if (notice != null) Ui.toast(activity, notice);
        activity.startActivity(new Intent(activity, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
