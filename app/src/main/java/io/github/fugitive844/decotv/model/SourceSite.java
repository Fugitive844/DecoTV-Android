package io.github.fugitive844.decotv.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 源浏览器的一个视频源（/api/search/resources），以及它的分类与分页（经 /api/proxy/cms 取原始 CMS 数据）。 */
public final class SourceSite {
    public String key = "", name = "", api = "";
    public boolean adult;

    public static final class Category {
        public final String id, name;

        Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static List<SourceSite> listFrom(JSONArray arr, boolean hideAdult) {
        List<SourceSite> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optBoolean("disabled", false)) continue;
            SourceSite s = new SourceSite();
            s.key = o.optString("key", "");
            s.name = o.optString("name", s.key);
            s.api = o.optString("api", "");
            s.adult = o.optBoolean("is_adult", false);
            if (s.key.isEmpty() || s.api.isEmpty() || (hideAdult && s.adult)) continue;
            out.add(s);
        }
        return out;
    }

    /** CMS 接口地址加查询参数：与网页端相同，以 / 结尾时直接拼 ?，否则拼 /?。 */
    public String url(String query) {
        return (api.endsWith("/") ? api : api + "/") + "?" + query;
    }

    private static final String[] CLASS_KEYS = {"class", "classes", "class_list", "classlist", "list", "typelist"};

    /** 分类：兼容各家 CMS 的不同字段名（class / classes / typelist…，也可能套在 data 里）。 */
    public static List<Category> categoriesFrom(JSONObject o) {
        List<Category> out = new ArrayList<>();
        JSONArray arr = null;
        for (JSONObject scope : new JSONObject[]{o, o.optJSONObject("data")}) {
            if (scope == null) continue;
            for (String k : CLASS_KEYS) {
                JSONArray a = scope.optJSONArray(k);
                if (a != null && a.length() > 0 && a.optJSONObject(0) != null && a.optJSONObject(0).has("type_name")) {
                    arr = a;
                    break;
                }
            }
            if (arr != null) break;
        }
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("type_id", "").trim(), name = c.optString("type_name", "").trim();
            if (!id.isEmpty() && !name.isEmpty()) out.add(new Category(id, name));
        }
        return out;
    }

    /** 是否还有下一页：与网页端相同，优先看 total，其次 pagecount，最后看这一页是否装满。 */
    public static boolean hasMore(JSONObject o, int page, int fetched, int merged) {
        long total = o.optLong("total", -1);
        if (total > 0) return merged < total;
        int pages = o.optInt("pagecount", -1);
        if (pages > 0) return page < pages;
        int limit = o.optInt("limit", o.optInt("page_size", 20));
        return fetched > 0 && fetched >= limit;
    }
}
