package io.github.fugitive844.decotv.net;

import io.github.fugitive844.decotv.model.SearchRanking;
import io.github.fugitive844.decotv.model.SourceSite;
import io.github.fugitive844.decotv.model.Video;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 本机搜索：从服务端取得源列表和过滤规则后，由手机直接请求各视频源。
 * 过程与服务端 searchFromApi 相同：每个源先取第一页，再按 pagecount 取后面的页。
 * 智能模式下，直连失败的源改经 /api/proxy/cms 获取，其余的源不受影响。
 */
final class LocalSearch {
    private static final int DIRECT_TIMEOUT_SECONDS = 5;
    private static final int PLAN_TTL_MINUTES = 5, RESULT_TTL_MINUTES = 10, MAX_CACHED_RESULTS = 200;
    private static final int DEFAULT_MAX_PAGES = 5;
    private static final long CANCEL_POLL_MS = 100;
    /** 视频源可能屏蔽非浏览器请求，与服务端搜索用同样的请求头。 */
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    /** 连不上的源要等到超时才让出线程，并发太低时会拖慢其余的源。 */
    private static final ExecutorService SOURCES = Executors.newFixedThreadPool(32);
    private static final ExecutorService PAGES = Executors.newFixedThreadPool(16);

    /** 这次搜索要用的源和过滤规则；sites 为 null 表示服务端太旧，只能由服务端搜索。 */
    private static final class Plan {
        final String owner;
        final long expiresAt;
        final List<SourceSite> sites;
        final List<String> blockedTypes;
        final int maxPages;

        Plan(String owner, List<SourceSite> sites, List<String> blockedTypes, int maxPages) {
            this.owner = owner;
            this.expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(PLAN_TTL_MINUTES);
            this.sites = sites;
            this.blockedTypes = blockedTypes;
            this.maxPages = maxPages;
        }
    }

    private static final class CachedResult {
        final long expiresAt;
        final List<Video> videos;

        CachedResult(List<Video> videos) {
            this.expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(RESULT_TTL_MINUTES);
            this.videos = videos;
        }
    }

    /** 一次搜索的取消状态。 */
    private static final class Run {
        final Set<Call> calls = ConcurrentHashMap.newKeySet();
        volatile boolean cancelled;
        volatile ApiClient.AuthException auth;

        void cancel() {
            cancelled = true;
            for (Call call : calls) call.cancel();
        }
    }

    private final ApiClient api;
    private final OkHttpClient direct;
    private final Map<String, CachedResult> cache = Collections.synchronizedMap(new LinkedHashMap<String, CachedResult>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) { return size() > MAX_CACHED_RESULTS; }
    });
    private Plan plan;

    LocalSearch(ApiClient api) {
        this.api = api;
        direct = api.http().newBuilder().callTimeout(DIRECT_TIMEOUT_SECONDS, TimeUnit.SECONDS).build();
    }

    /** 服务端是否支持本机搜索。结果缓存几分钟，账号、服务器或访问秘钥变化时重新获取。 */
    boolean available(String owner) throws IOException {
        return plan(owner).sites != null;
    }

    private synchronized Plan plan(String owner) throws IOException {
        if (plan != null && plan.owner.equals(owner) && plan.expiresAt > System.currentTimeMillis()) return plan;
        JSONObject config = api.serverConfig();
        JSONArray words = config.optJSONArray("AdultTypeWords");
        List<String> blocked = new ArrayList<>();
        for (int i = 0; words != null && i < words.length(); i++) blocked.add(words.optString(i));
        plan = new Plan(owner, words == null ? null : api.sourceSites(), blocked, config.optInt("SearchMaxPage", DEFAULT_MAX_PAGES));
        return plan;
    }

    /** 依次回调每个源的结果。全部源都失败时抛出错误；被取消时返回 false。 */
    boolean run(String owner, List<String> queries, String onlySource, Relay.Mode mode, ApiClient.SearchListener listener,
                java.util.function.Consumer<Runnable> started) throws IOException {
        Plan current = plan(owner);
        List<SourceSite> sites = new ArrayList<>();
        for (SourceSite site : current.sites) {
            if (onlySource == null || onlySource.equals(site.key)) sites.add(site);
        }
        Run run = new Run();
        started.accept(run::cancel);
        int total = sites.size();
        CountDownLatch latch = new CountDownLatch(total);
        int[] done = {0, 0};
        Object lock = new Object();
        IOException[] lastError = {null};
        for (SourceSite site : sites) {
            SOURCES.execute(() -> {
                List<Video> batch = new ArrayList<>();
                try {
                    if (!run.cancelled) batch = searchSite(run, current, site, queries, mode);
                } catch (ApiClient.AuthException e) {
                    run.auth = e;
                } catch (IOException e) {
                    synchronized (lock) {
                        done[1]++;
                        lastError[0] = e;
                    }
                } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                    // 第三方数据什么样都有可能，一个源出问题只算这个源失败，不能让 App 退出
                    NetLog.fail(Relay.Item.SEARCH, false, site.api, e);
                    synchronized (lock) {
                        done[1]++;
                        lastError[0] = new IOException(site.name + " 的数据无法处理", e);
                    }
                }
                try {
                    synchronized (lock) {
                        done[0]++;
                        if (!run.cancelled) listener.onResults(SearchRanking.rank(batch, queries.get(0)), done[0], total);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            while (!latch.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (run.cancelled) return false;
            }
        } catch (InterruptedException e) {
            run.cancel();
            Thread.currentThread().interrupt();
            return false;
        }
        if (run.cancelled) return false;
        if (run.auth != null) throw run.auth;
        if (total > 0 && done[1] == total) throw new IOException("所有视频源都无法连接：" + lastError[0].getMessage());
        return true;
    }

    private List<Video> searchSite(Run run, Plan plan, SourceSite site, List<String> queries, Relay.Mode mode) throws IOException {
        Map<String, Video> merged = new LinkedHashMap<>();
        IOException error = null;
        boolean anyOk = false;
        for (String query : queries) {
            try {
                for (Video v : searchQuery(run, plan, site, query, mode)) {
                    if (!merged.containsKey(v.id)) merged.put(v.id, v);
                }
                anyOk = true;
            } catch (ApiClient.AuthException e) {
                throw e;
            } catch (IOException e) {
                error = e;
            }
        }
        if (!anyOk && error != null) throw error;
        return blockedRemoved(new ArrayList<>(merged.values()), plan.blockedTypes);
    }

    private List<Video> searchQuery(Run run, Plan plan, SourceSite site, String query, Relay.Mode mode) throws IOException {
        String cacheKey = site.key + "|" + site.api + "|" + plan.maxPages + "|" + query;
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.videos;
        boolean[] relay = {false};
        JSONObject first = fetch(run, site.searchUrl(query, 1), mode, relay);
        List<Video> videos = playable(first, site);
        boolean complete = true;
        int pages = Math.min(first.optInt("pagecount", 1), plan.maxPages);
        List<Future<JSONObject>> rest = new ArrayList<>();
        for (int page = 2; page <= pages; page++) {
            String url = site.searchUrl(query, page);
            rest.add(PAGES.submit(() -> fetch(run, url, mode, relay)));
        }
        for (Future<JSONObject> future : rest) {
            try {
                videos.addAll(playable(future.get(), site));
            } catch (ExecutionException e) {
                // 后面的页失败时保留已有结果，只是不写入缓存
                if (e.getCause() instanceof ApiClient.AuthException) throw (ApiClient.AuthException) e.getCause();
                complete = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("搜索已中断");
            }
        }
        if (complete && !videos.isEmpty()) cache.put(cacheKey, new CachedResult(Collections.unmodifiableList(videos)));
        return videos;
    }

    /** relay 记住这个源已经改走中转，后面的页不必再等一次直连超时。 */
    private JSONObject fetch(Run run, String url, Relay.Mode mode, boolean[] relay) throws IOException {
        if (run.cancelled) throw new IOException("搜索已取消");
        if (!relay[0]) {
            try {
                JSONObject o = fetchDirect(run, url);
                NetLog.use(Relay.Item.SEARCH, false, url);
                return o;
            } catch (IOException e) {
                NetLog.fail(Relay.Item.SEARCH, false, url, e);
                if (mode != Relay.Mode.SMART || run.cancelled) throw e;
                relay[0] = true;
            }
        }
        try {
            JSONObject o = api.cms(url);
            NetLog.use(Relay.Item.SEARCH, true, url);
            return o;
        } catch (IOException e) {
            NetLog.fail(Relay.Item.SEARCH, true, url, e);
            throw e;
        }
    }

    private JSONObject fetchDirect(Run run, String url) throws IOException {
        Request request = new Request.Builder().url(url).header("User-Agent", BROWSER_UA).header("Accept", "application/json").get().build();
        Call call = direct.newCall(request);
        run.calls.add(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            return parsePage(response.body().string());
        } finally {
            run.calls.remove(call);
        }
    }

    static JSONObject parsePage(String body) throws IOException {
        String text = body.trim();
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("该源返回的不是有效数据");
        }
    }

    /** 与服务端相同：只留有标题、有 id、至少有一集可播放地址的条目。 */
    static List<Video> playable(JSONObject page, SourceSite site) {
        List<Video> out = new ArrayList<>();
        JSONArray list = page.optJSONArray("list");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            Video v = Video.fromCms(item, site.key, site.name);
            if (!v.id.isEmpty() && !v.title.isEmpty() && !v.episodes.isEmpty()) out.add(v);
        }
        return out;
    }

    /** 与服务端相同：分类名包含过滤关键词的条目不显示。 */
    static List<Video> blockedRemoved(List<Video> videos, List<String> blockedTypes) {
        if (blockedTypes.isEmpty()) return videos;
        List<Video> out = new ArrayList<>();
        for (Video v : videos) {
            boolean blocked = false;
            for (String word : blockedTypes) {
                if (!word.isEmpty() && v.typeName.contains(word)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) out.add(v);
        }
        return out;
    }
}
