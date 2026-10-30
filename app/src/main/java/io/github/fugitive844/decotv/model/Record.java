package io.github.fugitive844.decotv.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 播放记录与收藏共用：服务端以 "source+id" 为键。 */
public final class Record {
    public String key = "", title = "", sourceName = "", cover = "", year = "", searchTitle = "", origin = "";
    public int index, totalEpisodes;
    public long playTime, totalTime, saveTime;

    public String source() {
        int cut = key.indexOf('+');
        return cut < 0 ? key : key.substring(0, cut);
    }

    public String id() {
        int cut = key.indexOf('+');
        return cut < 0 ? "" : key.substring(cut + 1);
    }

    public static Record fromJson(String key, JSONObject o) {
        Record r = new Record();
        r.key = key;
        r.title = o.optString("title");
        r.sourceName = o.optString("source_name");
        r.cover = o.optString("cover");
        r.year = o.optString("year");
        r.searchTitle = o.optString("search_title");
        r.origin = o.optString("origin");
        r.index = o.optInt("index", 1);
        r.totalEpisodes = o.optInt("total_episodes");
        r.playTime = o.optLong("play_time");
        r.totalTime = o.optLong("total_time");
        r.saveTime = o.optLong("save_time");
        return r;
    }

    /** 服务端返回 {key: record}，按最近保存时间倒序。 */
    public static List<Record> listFrom(JSONObject map) {
        List<Record> list = new ArrayList<>();
        if (map == null) return list;
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject o = map.optJSONObject(key);
            if (o != null) list.add(fromJson(key, o));
        }
        list.sort((a, b) -> Long.compare(b.saveTime, a.saveTime));
        return list;
    }

    public JSONObject playRecordJson() {
        try {
            return new JSONObject().put("title", title).put("source_name", sourceName).put("cover", cover).put("year", year)
                    .put("index", index).put("total_episodes", totalEpisodes).put("play_time", playTime)
                    .put("total_time", totalTime).put("save_time", saveTime).put("search_title", searchTitle);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public JSONObject favoriteJson() {
        try {
            return new JSONObject().put("title", title).put("source_name", sourceName).put("cover", cover).put("year", year)
                    .put("total_episodes", totalEpisodes).put("save_time", saveTime).put("search_title", searchTitle)
                    .put("origin", "vod");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Record of(Video v) {
        Record r = new Record();
        r.key = v.key();
        r.title = v.title;
        r.sourceName = v.sourceName;
        r.cover = v.poster;
        r.year = v.year;
        r.searchTitle = v.title;
        r.totalEpisodes = v.episodes.size();
        r.saveTime = System.currentTimeMillis();
        return r;
    }
}
