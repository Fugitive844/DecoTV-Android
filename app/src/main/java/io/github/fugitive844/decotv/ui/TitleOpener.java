package io.github.fugitive844.decotv.ui;

import android.app.Activity;

import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.model.Poster;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.model.TitleMatch;
import io.github.fugitive844.decotv.model.Video;
import io.github.fugitive844.decotv.net.Tasks;

import java.util.List;

/** 点开海报：源浏览器条目直接打开该源；豆瓣 / Bangumi 条目先按标题找播放源，找不到就转到搜索页。 */
final class TitleOpener {
    private TitleOpener() {}

    static void open(Activity activity, Poster p) {
        if (p.tag instanceof Record) {
            PlayerActivity.open(activity, (Record) p.tag);
            return;
        }
        if (p.tag instanceof Video) {
            PlayerActivity.open(activity, (Video) p.tag, p.title);
            return;
        }
        if (!p.source.isEmpty()) {
            PlayerActivity.openById(activity, p.source, p.id, p.title, p.title, null);
            return;
        }
        Opening o = Opening.begin(activity, "正在查找「" + p.title + "」的播放源…");
        if (o == null) return;
        Tasks.run(() -> TitleMatch.filter(DecoApp.get().api.search(p.title), p.title, p.year, p.stype), o.then((List<Video> found) -> {
            if (!found.isEmpty()) {
                PlayerActivity.open(o, found.get(0), p.title);
                return;
            }
            o.end(false);
            if (activity instanceof MainActivity) {
                Ui.toast(activity, "没有找到同名的播放源，已为你搜索「" + p.title + "」");
                ((MainActivity) activity).searchFor(p.title);
            } else {
                Ui.toast(activity, "没有找到「" + p.title + "」的播放源");
            }
        }), o.orElse(e -> {
            o.end(false);
            Ui.fail(activity, e);
        }));
    }
}
