package io.github.fugitive844.decotv.download;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * 边存边播（m3u8）：缓存开始后，分片列表写在工作目录的 stream.json 里，
 * 播放器用它生成本地播放列表 dtvseg://任务id/index.m3u8，已下载的分片读本地，没下载的由播放器自己下载并存入缓存。
 * 下载线程优先下载播放位置之后的分片；播放器还在读时，分片留到播放器退出后再删。
 */
public final class Stream {
    public static final String SCHEME = "dtvseg";
    static final String INDEX = "stream.json";

    private static final ConcurrentHashMap<String, Integer> HEADS = new ConcurrentHashMap<>(), USERS = new ConcurrentHashMap<>();

    private Stream() {}

    public static File workDir(Context c, String id) {
        File base = c.getExternalFilesDir("downloads");
        if (base == null) base = new File(c.getFilesDir(), "work");
        return new File(base, id);
    }

    /** 这一集是否可以边存边播：还没完成，且已经拿到了 m3u8 分片列表。 */
    public static boolean available(Context c, DownloadTask t) {
        return t.state != DownloadTask.State.DONE && new File(workDir(c, t.id), INDEX).exists();
    }

    public static void acquire(String id) { USERS.merge(id, 1, Integer::sum); }

    /** 播放器不再读这一集；任务已完成（或已删除）时顺便清理留下的分片。 */
    public static void release(Context c, String id) {
        USERS.computeIfPresent(id, (k, v) -> v > 1 ? v - 1 : null);
        if (inUse(id)) return;
        HEADS.remove(id);
        DownloadTask t = io.github.fugitive844.decotv.DecoApp.get().downloads.get(id);
        if (t == null || t.state == DownloadTask.State.DONE) DownloadService.deleteTree(workDir(c, id));
    }

    static boolean inUse(String id) { return id != null && USERS.containsKey(id); }

    /** 播放器正在读第 index 片，下载线程从这里往后补。 */
    static void playhead(String id, int index) { HEADS.put(id, index); }

    static int playhead(String id) {
        Integer i = id == null ? null : HEADS.get(id);
        return i == null ? -1 : i;
    }

    /** 下载调度：每次取一个还没下载、也没有线程在下载的分片，优先播放位置之后的。 */
    static final class Scheduler {
        private final String id;
        private final boolean[] claimed;
        private final IntPredicate have;

        Scheduler(String id, int count, IntPredicate have) {
            this.id = id;
            this.have = have;
            claimed = new boolean[count];
        }

        synchronized int next() {
            int head = playhead(id);
            if (head >= 0 && head < claimed.length) {
                int i = scan(head);
                if (i >= 0) return i;
            }
            return scan(0);
        }

        private int scan(int from) {
            for (int i = from; i < claimed.length; i++) {
                if (claimed[i]) continue;
                claimed[i] = true;
                if (!have.test(i)) return i;
            }
            return -1;
        }
    }

    // ---------- 分片列表 ----------

    static final class Index {
        String referer = "", userAgent = "", init = "";
        String[] urls, keys;
        byte[][] ivs;
        double[] durations;
        boolean[] discontinuities;
    }

    static void writeIndex(File work, String referer, String userAgent, String init, List<Fetcher.Segment> segments) throws IOException {
        try {
            JSONArray arr = new JSONArray();
            for (Fetcher.Segment s : segments) {
                arr.put(new JSONArray().put(s.url).put(s.keyUrl == null ? "" : s.keyUrl).put(hex(s.iv)).put(s.duration).put(s.discontinuity));
            }
            JSONObject o = new JSONObject().put("referer", referer).put("ua", userAgent).put("init", init == null ? "" : init).put("segments", arr);
            File part = new File(work, INDEX + ".part");
            java.nio.file.Files.write(part.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            if (!part.renameTo(new File(work, INDEX))) throw new IOException("写入分片列表失败");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static Index readIndex(File work) throws IOException {
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(work, INDEX).toPath()), StandardCharsets.UTF_8));
            Index x = new Index();
            x.referer = o.optString("referer", "");
            x.userAgent = o.optString("ua", "");
            x.init = o.optString("init", "");
            JSONArray arr = o.getJSONArray("segments");
            int n = arr.length();
            x.urls = new String[n];
            x.keys = new String[n];
            x.ivs = new byte[n][];
            x.durations = new double[n];
            x.discontinuities = new boolean[n];
            for (int i = 0; i < n; i++) {
                JSONArray s = arr.getJSONArray(i);
                x.urls[i] = s.getString(0);
                String k = s.optString(1, "");
                x.keys[i] = k.isEmpty() ? null : k;
                x.ivs[i] = Fetcher.hex16(s.optString(2, "0"));
                x.durations[i] = s.optDouble(3, 10);
                x.discontinuities[i] = s.optBoolean(4, false);
            }
            return x;
        } catch (JSONException e) {
            throw new IOException("分片列表损坏", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format(Locale.ROOT, "%02x", v));
        return sb.toString();
    }

    // ---------- 缓存完成后的字节范围播放列表 ----------

    /**
     * 拼接成单个 TS 的缓存里常有时间戳不连续（插播广告等），直接按文件播放时时长会算错、无法拖动。
     * 所以记下每个分片在文件中的位置，本 App 播放时生成带 EXT-X-BYTERANGE 的播放列表。
     */
    public static File layoutFile(Context c, String id) { return new File(new File(c.getFilesDir(), "offline"), id + ".layout.json"); }

    static void writeLayout(File f, Fetcher.Result r) throws IOException {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < r.lengths.length; i++) arr.put(new JSONArray().put(r.lengths[i]).put(r.durations[i]).put(r.discontinuities[i]));
            File parent = f.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            java.nio.file.Files.write(f.toPath(), new JSONObject().put("init", r.initLength).put("segments", arr).toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** 由布局生成播放列表，分片都指向同一个文件的不同字节范围；没有布局返回 null。 */
    public static String layoutPlaylist(File layout, String fileUri) {
        if (!layout.exists()) return null;
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(layout.toPath()), StandardCharsets.UTF_8));
            JSONArray arr = o.getJSONArray("segments");
            long init = o.optLong("init", 0), offset = init;
            double max = 1;
            for (int i = 0; i < arr.length(); i++) max = Math.max(max, arr.getJSONArray(i).getDouble(1));
            StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(init > 0 ? 6 : 4)
                    .append("\n#EXT-X-TARGETDURATION:").append((int) Math.ceil(max))
                    .append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
            if (init > 0) b.append("#EXT-X-MAP:URI=\"").append(fileUri).append("\",BYTERANGE=\"").append(init).append("@0\"\n");
            for (int i = 0; i < arr.length(); i++) {
                JSONArray s = arr.getJSONArray(i);
                long len = s.getLong(0);
                if (s.optBoolean(2) && i > 0) b.append("#EXT-X-DISCONTINUITY\n");
                b.append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n#EXT-X-BYTERANGE:%d@%d\n", s.getDouble(1), len, offset)).append(fileUri).append('\n');
                offset += len;
            }
            return b.append("#EXT-X-ENDLIST\n").toString();
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** 本地播放列表的地址。 */
    public static String uri(String id) { return SCHEME + "://" + id + "/index.m3u8"; }

    /** 生成本地 m3u8：分片指向 dtvseg://id/序号，已解密，所以不带 EXT-X-KEY。 */
    static String playlist(String id, Index x) {
        double max = 1;
        for (double d : x.durations) max = Math.max(max, d);
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(x.init.isEmpty() ? 3 : 6)
                .append("\n#EXT-X-TARGETDURATION:").append((int) Math.ceil(max))
                .append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        if (!x.init.isEmpty()) b.append("#EXT-X-MAP:URI=\"").append(SCHEME).append("://").append(id).append("/init\"\n");
        for (int i = 0; i < x.urls.length; i++) {
            if (x.discontinuities[i] && i > 0) b.append("#EXT-X-DISCONTINUITY\n");
            b.append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n", x.durations[i]));
            b.append(SCHEME).append("://").append(id).append('/').append(i).append('\n');
        }
        return b.append("#EXT-X-ENDLIST\n").toString();
    }
}
