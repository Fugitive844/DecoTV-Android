package io.github.fugitive844.decotv.net;

import io.github.fugitive844.decotv.BuildConfig;
import io.github.fugitive844.decotv.data.Prefs;
import io.github.fugitive844.decotv.live.M3uParser;
import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.model.Record;
import io.github.fugitive844.decotv.model.Video;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * DecoTV 服务端接口。全部是阻塞调用，由 {@link Tasks} 放到后台线程执行。
 * 登录 cookie 只发给当前服务器，直连第三方资源时不会带上。
 */
public final class ApiClient {
    /** 填写访问秘钥处统一的提示文案（不提具体内容类型）。 */
    public static final String ACCESS_KEY_HINT = "需要搜索更多精彩内容，请联系站长获取秘钥";

    /** UA 必须含 okhttp：服务端据此返回原始集数地址，而不是包装成服务端代理地址。 */
    public static final String USER_AGENT = "DecoTV-Android/1.0 okhttp/4.12.0";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public static final class AuthException extends IOException {
        public AuthException() { super("登录已失效，请重新登录"); }
    }

    public static final class Resolved {
        public String resolvedUrl = "", referer = "", proxyUrl = "", mediaType = "";
    }

    /** 仅 debug 包的联调测试使用：模拟第三方全部不可直连，验证智能模式的回退。 */
    public static volatile boolean testBlockThirdParty;

    private final Prefs prefs;
    private final OkHttpClient http;

    public ApiClient(Prefs prefs) {
        this.prefs = prefs;
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    if (BuildConfig.DEBUG && testBlockThirdParty && !isServer(request.url())) {
                        throw new IOException("测试：已屏蔽第三方直连");
                    }
                    Request.Builder builder = request.newBuilder();
                    if (request.header("User-Agent") == null) builder.header("User-Agent", USER_AGENT);
                    boolean server = isServer(request.url());
                    String cookie = prefs.cookie();
                    if (!cookie.isEmpty() && server) builder.header("Cookie", "auth=" + cookie);
                    // 访问秘钥只发给当前服务器；请求已显式带 ak（如校验接口）时不覆盖
                    String accessKey = prefs.accessKey();
                    if (!accessKey.isEmpty() && server && request.url().queryParameter("ak") == null)
                        builder.url(request.url().newBuilder().addQueryParameter("ak", accessKey).build());
                    return chain.proceed(builder.build());
                })
                .build();
    }

    public OkHttpClient http() { return http; }
    public String server() { return prefs.server(); }

    boolean isServer(HttpUrl url) {
        HttpUrl server = HttpUrl.parse(prefs.server());
        return server != null && server.host().equalsIgnoreCase(url.host()) && server.port() == url.port();
    }

    // ---------- 通用 ----------

    private String send(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.code() == 401) throw new AuthException();
            if (!response.isSuccessful()) throw new IOException(errorText(response.code(), body));
            return body;
        }
    }

    private static String errorText(int code, String body) {
        try {
            String error = new JSONObject(body).optString("error");
            if (!error.isEmpty()) return error;
        } catch (JSONException ignored) {
            // 非 JSON 错误体
        }
        return "HTTP " + code;
    }

    private String get(String path) throws IOException {
        return send(new Request.Builder().url(prefs.server() + path).get().build());
    }

    private String post(String path, JSONObject body) throws IOException {
        return send(new Request.Builder().url(prefs.server() + path).post(RequestBody.create(body.toString(), JSON)).build());
    }

    private String delete(String path) throws IOException {
        return send(new Request.Builder().url(prefs.server() + path).delete().build());
    }

    private static JSONObject obj(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("服务端返回格式错误");
        }
    }

    // ---------- 首页与片库 ----------

    private String getQuery(String path, java.util.Map<String, String> query) throws IOException {
        HttpUrl base = HttpUrl.parse(prefs.server() + path);
        if (base == null) throw new IOException("服务器地址无效");
        HttpUrl.Builder url = base.newBuilder();
        for (java.util.Map.Entry<String, String> e : query.entrySet()) url.addQueryParameter(e.getKey(), e.getValue());
        return send(new Request.Builder().url(url.build()).get().build());
    }

    private static JSONObject doubanResult(String body) throws IOException {
        JSONObject o = obj(body);
        if (o.optInt("code", 200) != 200) throw new IOException(o.optString("message", "获取豆瓣数据失败"));
        return o;
    }

    /** 豆瓣榜单（最近热门等），与网页端 getDoubanCategories 相同。 */
    public JSONObject doubanCategories(String kind, String category, String type, int limit, int start) throws IOException {
        java.util.Map<String, String> q = new java.util.LinkedHashMap<>();
        q.put("kind", kind);
        q.put("category", category);
        q.put("type", type);
        q.put("limit", String.valueOf(limit));
        q.put("start", String.valueOf(start));
        return doubanResult(getQuery("/api/douban/categories", q));
    }

    /** 豆瓣筛选（分类选「全部」及动漫的番剧 / 剧场版），参数与网页端 getDoubanRecommends 相同，空值也照传。 */
    public JSONObject doubanRecommends(String kind, java.util.Map<String, String> filters, int limit, int start) throws IOException {
        java.util.Map<String, String> q = new java.util.LinkedHashMap<>();
        q.put("kind", kind);
        q.put("limit", String.valueOf(limit));
        q.put("start", String.valueOf(start));
        for (String k : new String[]{"category", "format", "label", "region", "year", "platform", "sort"}) {
            String v = filters.get(k);
            q.put(k, v == null ? "" : v);
        }
        return doubanResult(getQuery("/api/douban/recommends", q));
    }

    /** Bangumi 每日放送：与网页端一样直接请求公开接口，不经服务端。 */
    public JSONArray bangumiCalendar() throws IOException {
        try (Response r = http.newCall(new Request.Builder().url("https://api.bgm.tv/calendar").get().build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new IOException("Bangumi 请求失败：HTTP " + r.code());
            return new JSONArray(r.body().string());
        } catch (JSONException e) {
            throw new IOException("Bangumi 返回格式错误");
        }
    }

    /** 源浏览器的源列表；服务端开启成人内容过滤时隐藏成人源，与网页端一致。 */
    public List<io.github.fugitive844.decotv.model.SourceSite> sourceSites() throws IOException {
        boolean hideAdult = serverConfig().optBoolean("AdultFilterEnabled", false);
        try {
            return io.github.fugitive844.decotv.model.SourceSite.listFrom(new JSONArray(get("/api/search/resources")), hideAdult);
        } catch (JSONException e) {
            throw new IOException("服务端返回格式错误");
        }
    }

    /** 经服务端 /api/proxy/cms 取某个源的原始 CMS 数据。源本身拒绝访问时把它的原话作为错误。 */
    public JSONObject cms(String url) throws IOException {
        String body = getQuery("/api/proxy/cms", java.util.Collections.singletonMap("url", url));
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            String text = body.trim();
            throw new IOException(text.isEmpty() || text.length() > 80 ? "该源返回的不是有效数据" : "该源返回：" + text);
        }
    }

    /** 无需登录的站点配置：StorageType、AuthMode、EnableRegistration、AdultFilterEnabled 等。 */
    public JSONObject serverConfig() throws IOException {
        return obj(send(new Request.Builder().url(prefs.server() + "/api/server-config").get().build()));
    }

    /** 与网页端相同：只需新密码，成功后需要重新登录。 */
    public void changePassword(String newPassword) throws IOException {
        try {
            post("/api/change-password", new JSONObject().put("newPassword", newPassword));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ---------- 弹幕 ----------

    /** 弹幕接口与网页端相同；出错时服务端仍返回 JSON，取其中的 message 作为错误信息。 */
    private JSONObject danmakuCall(String path, java.util.Map<String, String> query) throws IOException {
        HttpUrl base = HttpUrl.parse(prefs.server() + path);
        if (base == null) throw new IOException("服务器地址无效");
        HttpUrl.Builder url = base.newBuilder();
        for (java.util.Map.Entry<String, String> e : query.entrySet()) url.addQueryParameter(e.getKey(), e.getValue());
        try (Response response = http.newCall(new Request.Builder().url(url.build()).get().build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            JSONObject o;
            try {
                o = new JSONObject(body);
            } catch (JSONException e) {
                throw new IOException("弹幕服务返回格式错误（HTTP " + response.code() + "）");
            }
            if (!response.isSuccessful() || o.optInt("code", 200) != 200) {
                String message = o.optString("message", "");
                throw new IOException(message.isEmpty() ? "弹幕请求失败：HTTP " + response.code() : message);
            }
            return o;
        }
    }

    /**
     * 取一集的弹幕：按标题、年份、集数（从 1 开始）自动匹配，或用手动匹配的 episodeId。
     * 与网页端一致，自动匹配结果为空时强制刷新再试一次。返回原始 JSON，方便离线缓存。
     */
    public JSONObject danmaku(String title, String year, int episode, long episodeId, String animeTitle, String episodeTitle) throws IOException {
        java.util.Map<String, String> q = new java.util.LinkedHashMap<>();
        q.put("title", title);
        if (year != null && year.matches("\\d{4}")) q.put("year", year);
        q.put("episode", String.valueOf(Math.max(1, episode)));
        if (episodeId > 0) {
            q.put("episode_id", String.valueOf(episodeId));
            if (animeTitle != null && !animeTitle.isEmpty()) q.put("anime_title", animeTitle);
            if (episodeTitle != null && !episodeTitle.isEmpty()) q.put("episode_title", episodeTitle);
        }
        JSONObject o = danmakuCall("/api/danmu-external", q);
        if (episodeId <= 0 && o.optInt("count", 0) == 0) {
            q.put("force", "1");
            o = danmakuCall("/api/danmu-external", q);
        }
        return o;
    }

    /** 手动匹配：按关键词搜索弹幕库里的剧集。 */
    public JSONObject danmakuSearch(String keyword) throws IOException {
        return danmakuCall("/api/danmu-external/search", java.util.Collections.singletonMap("keyword", keyword));
    }

    // ---------- 登录 ----------

    /** 登录成功返回 auth cookie 的值（已是 URL 编码）。 */
    public String login(String server, String username, String password) throws IOException {
        String base = Relay.normalizeServer(server);
        JSONObject body = new JSONObject();
        try {
            body.put("username", username).put("password", password);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Request request = new Request.Builder().url(base + "/api/login").post(RequestBody.create(body.toString(), JSON)).build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IOException(errorText(response.code(), text));
            for (String header : response.headers("Set-Cookie")) {
                if (header.startsWith("auth=")) {
                    int end = header.indexOf(';');
                    String value = header.substring(5, end < 0 ? header.length() : end);
                    if (!value.isEmpty()) return value;
                }
            }
            throw new IOException("服务端未返回登录凭据");
        }
    }

    // ---------- 注册（与网页端 /register 相同：图形验证码 + 用户名密码） ----------

    /** 服务端是否开放注册：后台开启注册且不是 localstorage 单用户模式。 */
    public boolean registrationEnabled(String server) throws IOException {
        String base = Relay.normalizeServer(server);
        JSONObject o = obj(send(new Request.Builder().url(base + "/api/server-config").get().build()));
        return o.optBoolean("EnableRegistration") && !"localstorage".equals(o.optString("StorageType", "localstorage"));
    }

    public static final class Captcha {
        public final String svg, sessionId;

        Captcha(String svg, String sessionId) {
            this.svg = svg;
            this.sessionId = sessionId;
        }
    }

    public Captcha captcha(String server) throws IOException {
        String base = Relay.normalizeServer(server);
        JSONObject o = obj(send(new Request.Builder().url(base + "/api/register?action=captcha").get().build()));
        return new Captcha(o.optString("svg"), o.optString("sessionId"));
    }

    public void register(String server, String username, String password, String captcha, String sessionId) throws IOException {
        String base = Relay.normalizeServer(server);
        JSONObject body = new JSONObject();
        try {
            body.put("username", username).put("password", password).put("confirmPassword", password)
                    .put("captcha", captcha).put("sessionId", sessionId);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        send(new Request.Builder().url(base + "/api/register").post(RequestBody.create(body.toString(), JSON)).build());
    }

    public void logout() {
        try {
            send(new Request.Builder().url(prefs.server() + "/api/logout").post(RequestBody.create("{}", JSON)).build());
        } catch (IOException ignored) {
            // 本地清除即可
        }
    }

    // ---------- 搜索与详情 ----------

    public List<Video> search(String query) throws IOException {
        JSONArray results = obj(get("/api/search?q=" + Relay.enc(query))).optJSONArray("results");
        List<Video> list = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o != null) list.add(Video.fromJson(o));
        }
        return list;
    }

    public interface SearchListener {
        /** 每个源返回一批结果时回调（后台线程）。 */
        void onResults(List<Video> batch, int completedSources, int totalSources);
    }

    /**
     * 流式搜索（SSE /api/search/ws）：每个视频源返回就回调一批，不必等全部源完成。
     * 调用方可通过 {@link okhttp3.Call#cancel()} 中止；返回 null 表示被中止。
     */
    public okhttp3.Call searchStream(String query, SearchListener listener, java.util.function.Consumer<okhttp3.Call> started) throws IOException {
        Request request = new Request.Builder().url(prefs.server() + "/api/search/ws?q=" + Relay.enc(query))
                .header("Accept", "text/event-stream").get().build();
        OkHttpClient streaming = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build();
        okhttp3.Call call = streaming.newCall(request);
        started.accept(call);
        try (Response response = call.execute()) {
            if (response.code() == 401) throw new AuthException();
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            okio.BufferedSource source = response.body().source();
            int total = 0, completed = 0;
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (!line.startsWith("data:")) continue;
                JSONObject event;
                try {
                    event = new JSONObject(line.substring(5).trim());
                } catch (JSONException e) {
                    continue;
                }
                String type = event.optString("type");
                if (type.equals("start")) {
                    total = event.optInt("totalSources");
                } else if (type.equals("source_result") || type.equals("source_error")) {
                    completed++;
                    List<Video> batch = new ArrayList<>();
                    JSONArray results = event.optJSONArray("results");
                    for (int i = 0; results != null && i < results.length(); i++) {
                        JSONObject o = results.optJSONObject(i);
                        if (o != null) batch.add(Video.fromJson(o));
                    }
                    listener.onResults(batch, completed, total);
                } else if (type.equals("complete")) {
                    break;
                }
            }
        } catch (IOException e) {
            if (call.isCanceled()) return null;
            throw e;
        }
        return call;
    }

    public Video detail(String source, String id) throws IOException {
        return Video.fromJson(obj(get("/api/detail?source=" + Relay.enc(source) + "&id=" + Relay.enc(id))));
    }

    /** 让服务端解析网页型集数地址；同时拿到带签名的备用中转地址。 */
    public Resolved resolve(String url, String source) throws IOException {
        JSONObject o = obj(get("/api/playback/resolve?browser=1&url=" + Relay.enc(url) + "&source=" + Relay.enc(source)));
        Resolved r = new Resolved();
        r.resolvedUrl = o.optString("resolvedUrl", o.optString("playbackUrl"));
        r.referer = o.optString("referer");
        r.mediaType = o.optString("mediaType");
        r.proxyUrl = Relay.absolute(prefs.server(), o.optString("proxyUrl"));
        return r;
    }

    // ---------- 播放记录、搜索记录、收藏 ----------

    public List<Record> playRecords() throws IOException { return Record.listFrom(obj(get("/api/playrecords"))); }

    public void savePlayRecord(Record r) throws IOException {
        post("/api/playrecords", wrap("record", r.key, r.playRecordJson()));
    }

    public void deletePlayRecord(String key) throws IOException {
        delete("/api/playrecords" + (key == null ? "" : "?key=" + Relay.enc(key)));
    }

    public List<String> searchHistory() throws IOException {
        JSONArray array;
        try {
            array = new JSONArray(get("/api/searchhistory"));
        } catch (JSONException e) {
            throw new IOException("服务端返回格式错误");
        }
        List<String> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) list.add(array.optString(i));
        return list;
    }

    public void addSearchHistory(String keyword) throws IOException {
        try {
            post("/api/searchhistory", new JSONObject().put("keyword", keyword));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public void deleteSearchHistory(String keyword) throws IOException {
        delete("/api/searchhistory" + (keyword == null ? "" : "?keyword=" + Relay.enc(keyword)));
    }

    public List<Record> favorites() throws IOException { return Record.listFrom(obj(get("/api/favorites"))); }

    public boolean isFavorite(String key) throws IOException {
        String text = get("/api/favorites?key=" + Relay.enc(key)).trim();
        return !text.isEmpty() && !text.equals("null");
    }

    public void saveFavorite(Record r) throws IOException { post("/api/favorites", wrap("favorite", r.key, r.favoriteJson())); }

    public void deleteFavorite(String key) throws IOException {
        delete("/api/favorites" + (key == null ? "" : "?key=" + Relay.enc(key)));
    }

    private static JSONObject wrap(String field, String key, JSONObject value) throws IOException {
        try {
            return new JSONObject().put("key", key).put(field, value);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ---------- 直播 ----------

    public List<LiveSource> liveSources() throws IOException {
        JSONArray data = obj(get("/api/live/sources")).optJSONArray("data");
        List<LiveSource> list = new ArrayList<>();
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o != null) list.add(LiveSource.fromJson(o));
        }
        return list;
    }

    /** 中转：服务端下载并解析 m3u。 */
    public List<Channel> liveChannelsByServer(String sourceKey) throws IOException {
        JSONArray data = obj(get("/api/live/channels?source=" + Relay.enc(sourceKey))).optJSONArray("data");
        List<Channel> list = new ArrayList<>();
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o != null) list.add(Channel.fromJson(o));
        }
        return list;
    }

    /** 直连：App 用直播源 UA 直接下载 m3u，本地解析。 */
    public List<Channel> liveChannelsDirect(LiveSource source) throws IOException {
        Request request = new Request.Builder().url(source.url).header("User-Agent", source.userAgent()).get().build();
        String text = send(request);
        List<Channel> list = M3uParser.parse(source.key, text);
        if (list.isEmpty()) throw new IOException("频道列表为空或格式不正确");
        return list;
    }
}
