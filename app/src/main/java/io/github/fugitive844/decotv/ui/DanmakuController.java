package io.github.fugitive844.decotv.ui;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.danmaku.Danmaku;
import io.github.fugitive844.decotv.danmaku.DanmakuSettings;
import io.github.fugitive844.decotv.danmaku.DanmakuView;
import io.github.fugitive844.decotv.danmaku.ManualMatch;
import io.github.fugitive844.decotv.net.Tasks;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 点播和离线播放共用的弹幕：数据与网页端同源（服务端 /api/danmu-external），
 * 画面左侧「弹」按钮点按开关、长按打开设置；设置面板里还有重新加载、手动匹配和时间偏移。
 * 离线缓存的剧集优先读缓存时一并保存的弹幕文件。服务端的弹幕服务只提供读取，不支持发送弹幕。
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class DanmakuController {
    private final AppCompatActivity activity;
    private final DanmakuSettings settings;
    private final DanmakuView view;
    private final TextView toggle;
    private String title = "", year = "", loadedKey = "";
    private int episode;
    private File offline;
    private int token;
    private long offsetMs;
    private String state = "未加载";
    private Danmaku.Match match;

    DanmakuController(AppCompatActivity activity, PlayerView playerView, PlayerLayout layout, Player player) {
        this.activity = activity;
        settings = new DanmakuSettings(activity);
        view = new DanmakuView(activity);
        view.setClock(() -> {
            int s = player.getPlaybackState();
            return s == Player.STATE_READY || s == Player.STATE_BUFFERING ? player.getCurrentPosition() : -1;
        });
        view.setSettings(settings);
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay != null) overlay.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        toggle = PlayerLayout.circle(activity, "弹", "");
        toggle.setOnClickListener(v -> {
            settings.enabled = !settings.enabled;
            apply();
            Ui.toast(activity, settings.enabled ? "弹幕已开启" : "弹幕已关闭");
        });
        toggle.setOnLongClickListener(v -> {
            showPanel();
            return true;
        });
        layout.addLeftButton(toggle);
        updateToggle();
    }

    /** 当前状态文字，供联调测试读取。 */
    String state() { return state; }

    int count() { return view.count(); }

    private String matchKey() { return title.replace(" ", "").toLowerCase(Locale.ROOT) + "|" + year; }

    /**
     * 加载一集的弹幕（episode 从 0 开始）。offlineFile 不为空时优先读该文件，
     * 没有文件就在线加载并写入该文件。同一部同一集重复调用（如换源）不会重新加载。
     */
    void load(String title, String year, int episode, File offlineFile) {
        this.title = title == null ? "" : title.trim();
        this.year = year == null ? "" : year;
        this.episode = episode;
        offline = offlineFile;
        String key = matchKey() + "|" + episode + "|" + settings.manualMatch(matchKey());
        if (key.equals(loadedKey) && view.count() > 0) return;
        loadedKey = key;
        fetch(false);
    }

    private void fetch(boolean userAction) {
        int t = ++token;
        view.setData(null);
        match = null;
        state = "正在加载…";
        File file = offline;
        ManualMatch manual = ManualMatch.fromJson(settings.manualMatch(matchKey()));
        String title = this.title, year = this.year;
        int episode = this.episode;
        Tasks.run(() -> {
            if (!userAction && file != null && file.exists()) return new JSONObject(read(file));
            long id = 0;
            String animeTitle = "", episodeTitle = "";
            if (manual != null) {
                int i = manual.target(episode);
                if (i >= 0) {
                    id = manual.anime.episodeIds.get(i);
                    animeTitle = manual.anime.title;
                    episodeTitle = manual.anime.episodeTitles.get(i);
                }
            }
            JSONObject o = DecoApp.get().api.danmaku(title, year, episode + 1, id, animeTitle, episodeTitle);
            if (file != null && o.optInt("count", 0) > 0) write(file, o.toString());
            return o;
        }, o -> {
            if (t != token) return;
            Danmaku.Result r = Danmaku.parse(o);
            view.setData(r.items);
            match = r.match;
            state = r.items.isEmpty() ? "没有找到弹幕" : "已加载 " + r.items.size() + " 条";
            if (userAction) Ui.toast(activity, "弹幕：" + state);
        }, e -> {
            if (t != token) return;
            state = "加载失败：" + e.getMessage();
            if (userAction) Ui.toast(activity, "弹幕" + state);
        });
    }

    static String read(File f) throws java.io.IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(File f, String text) throws java.io.IOException {
        File parent = f.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File part = new File(f.getPath() + ".part");
        java.nio.file.Files.write(part.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!part.renameTo(f)) throw new java.io.IOException("写入弹幕文件失败");
    }

    private void apply() {
        settings.save();
        view.setSettings(settings);
        updateToggle();
    }

    private void updateToggle() {
        toggle.setAlpha(settings.enabled ? 1f : 0.45f);
        toggle.setContentDescription(settings.enabled ? "弹幕：已开启，长按设置" : "弹幕：已关闭，长按设置");
    }

    // ---------- 设置面板 ----------

    void showPanel() {
        LinearLayout box = Ui.column(activity, 20);
        TextView info = Ui.text(activity, describe(), 13, false);
        info.setAlpha(0.75f);
        box.addView(info);

        MaterialSwitch on = new MaterialSwitch(activity);
        on.setText("显示弹幕");
        on.setChecked(settings.enabled);
        on.setOnCheckedChangeListener((b, c) -> {
            settings.enabled = c;
            apply();
        });
        box.addView(on);

        box.addView(slider("不透明度", 10, 100, 10, Math.round(settings.opacity * 100), v -> v + "%", v -> {
            settings.opacity = v / 100f;
            apply();
        }));
        box.addView(slider("字号", 0, DanmakuSettings.SIZES.length - 1, 1, settings.size, v -> DanmakuSettings.SIZE_LABELS[v], v -> {
            settings.size = v;
            apply();
        }));
        box.addView(slider("速度", 0, DanmakuSettings.SPEED_LABELS.length - 1, 1, settings.speed, v -> DanmakuSettings.SPEED_LABELS[v], v -> {
            settings.speed = v;
            apply();
        }));
        box.addView(slider("显示区域", 0, DanmakuSettings.AREAS.length - 1, 1, settings.area, v -> DanmakuSettings.AREA_LABELS[v], v -> {
            settings.area = v;
            apply();
        }));
        box.addView(slider("同屏数量", 0, DanmakuSettings.LIMITS.length - 1, 1, settings.limit, v -> DanmakuSettings.LIMIT_LABELS[v], v -> {
            settings.limit = v;
            apply();
        }));

        box.addView(label("屏蔽类型"));
        ChipGroup modes = new ChipGroup(activity);
        String[] names = {"滚动", "顶部", "底部"};
        for (int i = 0; i < 3; i++) {
            int mode = i;
            Chip chip = new Chip(activity);
            chip.setText(names[i]);
            chip.setCheckable(true);
            chip.setChecked(settings.blocked[i]);
            chip.setOnCheckedChangeListener((b, c) -> {
                settings.blocked[mode] = c;
                apply();
            });
            modes.addView(chip);
        }
        box.addView(modes);

        MaterialSwitch overlap = new MaterialSwitch(activity);
        overlap.setText("防重叠（放不下时丢弃）");
        overlap.setChecked(settings.antiOverlap);
        overlap.setOnCheckedChangeListener((b, c) -> {
            settings.antiOverlap = c;
            apply();
        });
        box.addView(overlap);

        box.addView(label("时间偏移（弹幕比画面早就往后调）"));
        LinearLayout offsetRow = Ui.row(activity);
        TextView offsetText = Ui.text(activity, offsetLabel(), 14, false);
        offsetRow.addView(small("-0.5 秒", v -> { offset(-500); offsetText.setText(offsetLabel()); }));
        offsetRow.addView(offsetText, Ui.weight(1));
        offsetText.setGravity(android.view.Gravity.CENTER);
        offsetRow.addView(small("+0.5 秒", v -> { offset(500); offsetText.setText(offsetLabel()); }));
        offsetRow.addView(small("归零", v -> { offset(-offsetMs); offsetText.setText(offsetLabel()); }));
        box.addView(offsetRow);

        MaterialButton words = small("屏蔽词" + (Danmaku.keywords(settings.keywords).isEmpty() ? "" : "（" + Danmaku.keywords(settings.keywords).size() + "）"), null);
        words.setOnClickListener(v -> editKeywords(words));
        box.addView(words);

        LinearLayout actions = Ui.row(activity);
        actions.addView(small("重新加载", v -> fetch(true)));
        actions.addView(small("手动匹配", v -> manualMatch()));
        if (!settings.manualMatch(matchKey()).isEmpty()) {
            actions.addView(small("取消手动匹配", v -> {
                settings.setManualMatch(matchKey(), "");
                loadedKey = "";
                load(title, year, episode, offline);
                Ui.toast(activity, "已恢复自动匹配");
            }));
        }
        android.widget.HorizontalScrollView actionScroll = new android.widget.HorizontalScrollView(activity);
        actionScroll.addView(actions);
        box.addView(actionScroll);
        TextView hint = Ui.text(activity, "弹幕来自服务端弹幕服务，只能观看，不支持发送。", 12, false);
        hint.setAlpha(0.55f);
        box.addView(hint);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        new MaterialAlertDialogBuilder(activity).setTitle("弹幕").setView(scroll).setPositiveButton("完成", null).show();
    }

    private String describe() {
        StringBuilder b = new StringBuilder("弹幕：" + state);
        if (match != null && !match.animeTitle.isEmpty()) b.append("\n匹配：").append(match.animeTitle).append(" · ").append(match.episodeTitle);
        if (!settings.manualMatch(matchKey()).isEmpty()) b.append("（手动匹配）");
        return b.toString();
    }

    private String offsetLabel() {
        return offsetMs == 0 ? "不偏移" : String.format(Locale.ROOT, "%s%.1f 秒", offsetMs > 0 ? "延后 " : "提前 ", Math.abs(offsetMs) / 1000f);
    }

    /** 正数表示弹幕延后出现：显示时间 = 弹幕时间 + 偏移，所以对齐时钟要减去偏移。 */
    private void offset(long delta) {
        offsetMs += delta;
        view.setOffset(-offsetMs);
    }

    private TextView label(String text) {
        TextView t = Ui.text(activity, text, 14, true);
        t.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 4));
        return t;
    }

    private MaterialButton small(String text, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(activity, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private interface Label { String of(int v); }

    private interface Change { void on(int v); }

    private View slider(String name, int from, int to, int step, int value, Label label, Change change) {
        LinearLayout box = Ui.column(activity, 0);
        TextView head = label(name + "：" + label.of(value));
        box.addView(head);
        Slider s = new Slider(activity);
        s.setValueFrom(from);
        s.setValueTo(to);
        s.setStepSize(step);
        s.setValue(Math.max(from, Math.min(to, value - (value - from) % step)));
        s.setLabelFormatter(v -> label.of(Math.round(v)));
        s.setContentDescription(name);
        s.addOnChangeListener((sl, v, fromUser) -> {
            head.setText(name + "：" + label.of(Math.round(v)));
            change.on(Math.round(v));
        });
        box.addView(s);
        return box;
    }

    private void editKeywords(MaterialButton button) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setText(settings.keywords);
        input.setHint("每行一个，或用逗号分隔");
        FrameLayout wrap = new FrameLayout(activity);
        int pad = Ui.dp(activity, 20);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);
        new MaterialAlertDialogBuilder(activity).setTitle("屏蔽词").setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    settings.keywords = String.valueOf(input.getText()).trim();
                    apply();
                    int n = Danmaku.keywords(settings.keywords).size();
                    button.setText("屏蔽词" + (n == 0 ? "" : "（" + n + "）"));
                }).show();
    }

    // ---------- 手动匹配 ----------

    private void manualMatch() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(title);
        FrameLayout wrap = new FrameLayout(activity);
        int pad = Ui.dp(activity, 20);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);
        new MaterialAlertDialogBuilder(activity).setTitle("搜索弹幕剧集").setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", (d, w) -> {
                    String keyword = String.valueOf(input.getText()).trim();
                    if (keyword.isEmpty()) return;
                    Ui.toast(activity, "正在搜索弹幕剧集…");
                    Tasks.run(() -> ManualMatch.parseSearch(DecoApp.get().api.danmakuSearch(keyword)), this::chooseAnime,
                            e -> Ui.toast(activity, "搜索失败：" + e.getMessage()));
                }).show();
    }

    private void chooseAnime(List<ManualMatch.Anime> animes) {
        if (animes.isEmpty()) {
            Ui.toast(activity, "没有找到弹幕剧集，换个关键词试试");
            return;
        }
        String[] names = new String[animes.size()];
        for (int i = 0; i < names.length; i++) {
            ManualMatch.Anime a = animes.get(i);
            names[i] = a.title + (a.type.isEmpty() ? "" : "（" + a.type + "）") + " · " + a.episodeIds.size() + " 集";
        }
        new MaterialAlertDialogBuilder(activity).setTitle("选择剧集").setItems(names, (d, which) -> chooseEpisode(animes.get(which))).show();
    }

    private void chooseEpisode(ManualMatch.Anime anime) {
        String[] names = anime.episodeTitles.toArray(new String[0]);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("当前这一集对应")
                .setItems(names, (d, which) -> {
                    ManualMatch m = new ManualMatch();
                    m.anime = anime;
                    m.delta = which - episode;
                    settings.setManualMatch(matchKey(), m.toJson());
                    fetchManual();
                }).create();
        dialog.show();
        if (episode < names.length) dialog.getListView().setSelection(episode);
    }

    /** 手动匹配后强制在线加载（离线文件随之更新）。 */
    private void fetchManual() {
        loadedKey = matchKey() + "|" + episode + "|" + settings.manualMatch(matchKey());
        fetch(true);
    }
}
