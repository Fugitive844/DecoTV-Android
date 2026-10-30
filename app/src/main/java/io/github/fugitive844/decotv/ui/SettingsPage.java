package io.github.fugitive844.decotv.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import io.github.fugitive844.decotv.BuildConfig;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.data.Prefs;
import io.github.fugitive844.decotv.download.DownloadService;
import io.github.fugitive844.decotv.net.Relay;

/** 外观、播放与缓存设置、账户与关于；中转策略和自动换源在二级页面里。 */
final class SettingsPage implements MainActivity.Page {
    private static final String[] THEME_LABELS = {"跟随系统", "浅色", "深色"};

    private final ScrollView root;
    private final LinearLayout relayRow, switchRow;

    SettingsPage(MainActivity activity) {
        Prefs prefs = DecoApp.get().prefs;
        LinearLayout body = Ui.column(activity, 0);
        body.setBackgroundColor(Style.bg(activity));
        body.setPadding(0, 0, 0, Ui.dp(activity, 24));

        // 外观：三张小预览图，切换后界面立即按新模式重建
        body.addView(Style.caption(activity, "外观"));
        LinearLayout themes = Ui.row(activity);
        themes.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14));
        themes.setBackground(Style.round(activity, Style.surface(activity), 16));
        renderThemes(activity, themes);
        body.addView(themes, Style.margins(activity, 16, 0, 16, 0));
        body.addView(hint(activity, "跟随系统时随手机的深色模式自动切换。播放画面始终为深色。"));

        // 播放
        body.addView(Style.caption(activity, "播放"));
        LinearLayout play = Style.card(activity);
        LinearLayout speedBox = Ui.column(activity, 0);
        speedBox.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 12));
        speedBox.addView(Style.text(activity, "默认倍速", 15, 400, Style.text(activity)));
        android.widget.HorizontalScrollView speedScroll = new android.widget.HorizontalScrollView(activity);
        speedScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout speeds = Ui.row(activity);
        speeds.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 8));
        float currentSpeed = prefs.playbackSpeed();
        java.util.List<TextView> speedPills = new java.util.ArrayList<>();
        for (float s : PlayerActivity.SPEEDS) {
            TextView b = Style.pill(activity, PlayerActivity.speedLabel(s), Math.abs(s - currentSpeed) < 0.01f);
            b.setMinWidth(Ui.dp(activity, 52));
            b.setContentDescription("默认倍速 " + PlayerActivity.speedLabel(s));
            b.setOnClickListener(v -> {
                prefs.setPlaybackSpeed(s);
                for (TextView p : speedPills) Style.pill(p, p == v);
            });
            speedPills.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 34));
            lp.rightMargin = Ui.dp(activity, 6);
            speeds.addView(b, lp);
        }
        speedScroll.addView(speeds);
        speedBox.addView(speedScroll);
        speedBox.addView(Style.text(activity, "每次打开点播时使用；播放中可临时调整，长按画面右半边临时 2 倍速。", 12, 400, Style.text3(activity)));
        play.addView(speedBox);
        play.addView(Style.divider(activity));
        // 中转策略和自动换源选项较多，放到二级页面，免得设置页太长
        relayRow = Style.listRow(activity, 0, "中转策略", "", v -> activity.push(new RelayPage(activity), "中转策略"));
        play.addView(relayRow);
        play.addView(Style.divider(activity));
        switchRow = Style.listRow(activity, 0, "自动换源", "", v -> activity.push(new AutoSwitchPage(activity), "自动换源"));
        play.addView(switchRow);
        body.addView(play, Style.margins(activity, 16, 0, 16, 0));

        // 离线缓存
        body.addView(Style.caption(activity, "离线缓存"));
        LinearLayout cache = Style.card(activity);
        cache.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 12));
        int parallel = Math.max(1, Math.min(DownloadService.MAX_PARALLEL, prefs.downloadParallel()));
        LinearLayout parallelRow = Ui.row(activity);
        parallelRow.addView(Style.text(activity, "同时缓存数", 15, 400, Style.text(activity)), Ui.weight(1));
        TextView parallelTitle = Style.text(activity, String.valueOf(parallel), 15, 700, Style.accent(activity));
        parallelRow.addView(parallelTitle);
        cache.addView(parallelRow);
        Slider slider = new Slider(activity);
        slider.setValueFrom(1);
        slider.setValueTo(DownloadService.MAX_PARALLEL);
        slider.setStepSize(1);
        slider.setValue(parallel);
        slider.setTickVisible(false);
        slider.setContentDescription("离线缓存并发数");
        slider.addOnChangeListener((s, value, fromUser) -> parallelTitle.setText(String.valueOf(Math.round(value))));
        slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(Slider s) { }
            @Override public void onStopTrackingTouch(Slider s) {
                prefs.setDownloadParallel(Math.round(s.getValue()));
                DownloadService.parallelChanged(activity);
            }
        });
        cache.addView(slider);
        cache.addView(Style.text(activity, "默认 " + DownloadService.DEFAULT_PARALLEL + "，最多 " + DownloadService.MAX_PARALLEL
                + "。数值越大占用的网络越多，部分资源站可能限速或拒绝连接。", 12, 400, Style.text3(activity)));
        body.addView(cache, Style.margins(activity, 16, 0, 16, 0));

        // 账户与关于
        body.addView(Style.caption(activity, "账户"));
        LinearLayout account = Style.card(activity);
        account.addView(Style.listRow(activity, 0, "服务器", prefs.server(), null));
        account.addView(Style.divider(activity));
        account.addView(Style.listRow(activity, 0, "用户", prefs.username(), null));
        body.addView(account, Style.margins(activity, 16, 0, 16, 0));
        body.addView(Style.caption(activity, "关于"));
        LinearLayout about = Style.card(activity);
        about.addView(Style.listRow(activity, 0, "检查更新", "版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                v -> activity.updater().check(true)));
        body.addView(about, Style.margins(activity, 16, 0, 16, 0));
        TextView copyright = Ui.copyright(activity);
        copyright.setTextColor(Style.text3(activity));
        copyright.setAlpha(1f);
        copyright.setPadding(0, Ui.dp(activity, 20), 0, 0);
        body.addView(copyright);
        root = new ScrollView(activity);
        root.setBackgroundColor(Style.bg(activity));
        root.addView(body);
    }

    /**
     * 外观选项。深浅真的变了时 AppCompat 会重建界面（MainActivity 会回到设置页）；
     * 没变时（如系统本来就是浅色，从「跟随系统」改成「浅色」）只刷新选中状态。
     */
    private static void renderThemes(MainActivity activity, LinearLayout themes) {
        Prefs prefs = DecoApp.get().prefs;
        themes.removeAllViews();
        String mode = prefs.themeMode();
        for (int i = 0; i < Prefs.THEME_MODES.length; i++) {
            String m = Prefs.THEME_MODES[i];
            View option = themeOption(activity, i, m.equals(mode));
            option.setOnClickListener(v -> {
                if (m.equals(prefs.themeMode())) return;
                prefs.setThemeMode(m);
                prefs.applyThemeMode();
                if (!activity.isFinishing()) renderThemes(activity, themes);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i < Prefs.THEME_MODES.length - 1) lp.rightMargin = Ui.dp(activity, 10);
            themes.addView(option, lp);
        }
    }

    private static View gap(MainActivity activity) {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(activity, 12)));
        return v;
    }

    private static TextView hint(MainActivity activity, String text) {
        TextView t = Style.text(activity, text, 12, 400, Style.text3(activity));
        t.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8), Ui.dp(activity, 20), 0);
        return t;
    }

    /** 外观选项：上面一张深 / 浅色的迷你界面预览，下面单选圆点和名称。 */
    private static View themeOption(MainActivity activity, int index, boolean selected) {
        LinearLayout box = Ui.column(activity, 0);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setContentDescription("外观 " + THEME_LABELS[index]);
        LinearLayout preview = Ui.row(activity);
        preview.setBackground(selected ? Style.stroke(activity, 0, Style.accent(activity), 2.5f, 12)
                : Style.stroke(activity, 0, Style.line(activity), 1, 12));
        int pad = Ui.dp(activity, selected ? 3 : 2);
        preview.setPadding(pad, pad, pad, pad);
        // 跟随系统：左深右浅
        if (index == 0) {
            preview.addView(mini(activity, true, 1), new LinearLayout.LayoutParams(0, Ui.dp(activity, 100), 1));
            preview.addView(mini(activity, false, 2), new LinearLayout.LayoutParams(0, Ui.dp(activity, 100), 1));
        } else {
            preview.addView(mini(activity, index == 2, 0), new LinearLayout.LayoutParams(0, Ui.dp(activity, 100), 1));
        }
        box.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout label = Ui.row(activity);
        label.setGravity(android.view.Gravity.CENTER);
        label.setPadding(0, Ui.dp(activity, 8), 0, 0);
        View radio = new View(activity);
        radio.setBackground(selected ? Style.stroke(activity, 0, Style.accent(activity), 5, 8) : Style.stroke(activity, 0, Style.text4(activity), 1.5f, 8));
        label.addView(radio, new LinearLayout.LayoutParams(Ui.dp(activity, 16), Ui.dp(activity, 16)));
        TextView name = Style.text(activity, THEME_LABELS[index], 13, selected ? 700 : 400, selected ? Style.text(activity) : Style.text2(activity));
        name.setPadding(Ui.dp(activity, 6), 0, 0, 0);
        label.addView(name);
        box.addView(label);
        box.setBackground(Style.pressable(activity, 0, 12));
        return box;
    }

    /** 迷你界面：一块卡片两条文字线。corner：0 四角圆，1 只圆左侧，2 只圆右侧。 */
    private static View mini(MainActivity activity, boolean dark, int corner) {
        LinearLayout v = Ui.column(activity, 8);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(dark ? 0xFF0A0C10 : 0xFFF6F7F9);
        float r = Ui.dp(activity, 10);
        bg.setCornerRadii(corner == 1 ? new float[]{r, r, 0, 0, 0, 0, r, r} : corner == 2 ? new float[]{0, 0, r, r, r, r, 0, 0}
                : new float[]{r, r, r, r, r, r, r, r});
        v.setBackground(bg);
        int card = dark ? 0xFF262B36 : 0xFFE2E5EA, line = dark ? 0xFF4A5263 : 0xFFC3C8D1;
        View block = new View(activity);
        block.setBackground(Style.round(activity, card, 5));
        v.addView(block, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 30)));
        boolean half = corner != 0;
        for (int w : half ? new int[]{26, 16} : new int[]{56, 36}) {
            View l = new View(activity);
            l.setBackground(Style.round(activity, line, 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(activity, w), Ui.dp(activity, 6));
            lp.topMargin = Ui.dp(activity, 6);
            v.addView(l, lp);
        }
        return v;
    }

    private static void summary(LinearLayout row, String text) { Style.rowValue(row, text); }

    @Override public View view() { return root; }

    /** 从二级页面返回时刷新摘要。 */
    @Override public void show() {
        Prefs prefs = DecoApp.get().prefs;
        int relayed = 0;
        for (Relay.Item item : Relay.Item.values()) if (prefs.mode(item) != Relay.Mode.DIRECT) relayed++;
        summary(relayRow, relayed == 0 ? "全部直连" : relayed + " 项中转/智能");
        int kb = prefs.switchMinSpeedKb();
        summary(switchRow, !prefs.autoSwitchSource() ? "已关闭" : kb == 0 ? "开启 · 不看网速" : "开启 · 低于 " + kb + " KB/s");
    }

    /** 二级页面：逐项中转策略。 */
    static final class RelayPage implements MainActivity.Page {
        private final ScrollView root;

        RelayPage(MainActivity activity) {
            Prefs prefs = DecoApp.get().prefs;
            LinearLayout body = Ui.column(activity, 16);
            TextView intro = Ui.text(activity, "默认全部直连，不占用服务器带宽。全中转：始终经服务端获取。智能：直连失败时自动改走服务端中转。搜索始终经服务端。", 13, false);
            intro.setAlpha(0.7f);
            body.addView(intro);
            for (Relay.Item item : Relay.Item.values()) {
                body.addView(gap(activity));
                body.addView(Ui.text(activity, item.label, 15, true));
                TextView hint = Ui.text(activity, item.hint, 12, false);
                hint.setAlpha(0.6f);
                body.addView(hint);
                MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(activity);
                group.setSingleSelection(true);
                group.setSelectionRequired(true);
                Relay.Mode current = prefs.mode(item);
                for (Relay.Mode mode : Relay.Mode.values()) {
                    MaterialButton b = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                    b.setId(View.generateViewId());
                    b.setText(mode.label);
                    b.setContentDescription(item.label + " " + mode.label);
                    group.addView(b);
                    if (mode == current) group.check(b.getId());
                    b.addOnCheckedChangeListener((button, checked) -> { if (checked) prefs.setMode(item, mode); });
                }
                body.addView(group);
            }
            root = new ScrollView(activity);
            root.addView(body);
        }

        @Override public View view() { return root; }
        @Override public void show() { }
    }

    /** 二级页面：自动换源开关与网速条件，点播和直播共用。 */
    static final class AutoSwitchPage implements MainActivity.Page {
        private final ScrollView root;

        AutoSwitchPage(MainActivity activity) {
            Prefs prefs = DecoApp.get().prefs;
            LinearLayout body = Ui.column(activity, 16);
            MaterialSwitch autoSwitch = new MaterialSwitch(activity);
            autoSwitch.setText("播放失败自动换源");
            autoSwitch.setTextSize(18);
            autoSwitch.setChecked(prefs.autoSwitchSource());
            body.addView(autoSwitch);
            TextView autoHint = Ui.text(activity, "点播和直播共用。开启时当前源起播失败会自动换到下一个源；关闭后只提示失败，由你在「换源」里自己选择。", 12, false);
            autoHint.setAlpha(0.6f);
            body.addView(autoHint);
            body.addView(gap(activity));
            LinearLayout speedBox = Ui.column(activity, 0);
            speedBox.addView(Ui.text(activity, "网速条件", 18, true));
            TextView minHint = Ui.text(activity, "起播超时（直播 15 秒，点播直连 12 秒 / 中转 25 秒）后，缓冲网速仍不低于这里的值就继续等待，最多等 60 秒；"
                    + "超时且网速低于它才自动换源。选 0 KB/s 表示不要这个条件，超时即换源。播放器直接报错时不看网速，立即换源。", 12, false);
            minHint.setAlpha(0.6f);
            speedBox.addView(minHint);
            android.widget.HorizontalScrollView minScroll = new android.widget.HorizontalScrollView(activity);
            MaterialButtonToggleGroup mins = new MaterialButtonToggleGroup(activity);
            mins.setSingleSelection(true);
            mins.setSelectionRequired(true);
            int currentMin = prefs.switchMinSpeedKb();
            for (int kb : Prefs.SWITCH_SPEEDS_KB) {
                MaterialButton b = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                b.setId(View.generateViewId());
                b.setText(kb + " KB/s");
                b.setContentDescription("自动换源网速条件 " + (kb == 0 ? "0 KB/s 不看网速" : kb + " KB/s"));
                mins.addView(b);
                if (kb == currentMin) mins.check(b.getId());
                b.addOnCheckedChangeListener((button, checked) -> { if (checked) prefs.setSwitchMinSpeedKb(kb); });
            }
            minScroll.addView(mins);
            speedBox.addView(minScroll);
            body.addView(speedBox);
            // 关闭自动换源时网速条件不起作用，置灰
            speedBox.setAlpha(prefs.autoSwitchSource() ? 1f : 0.4f);
            autoSwitch.setOnCheckedChangeListener((b, on) -> {
                prefs.setAutoSwitchSource(on);
                speedBox.setAlpha(on ? 1f : 0.4f);
            });
            root = new ScrollView(activity);
            root.addView(body);
        }

        @Override public View view() { return root; }
        @Override public void show() { }
    }
}
