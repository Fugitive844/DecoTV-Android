package io.github.fugitive844.decotv.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 把一集下载成本地单个文件：
 * HLS 取最高码率，逐个下载分片（4 路并发、可断点续传），AES-128 加密的分片就地解密，最后按顺序拼接；
 * fMP4 分片拼成 .mp4，TS 分片拼成 .ts。直链文件用 Range 断点续传。
 */
final class Fetcher {
    interface Progress {
        void segments(int done, int total, long bytes);
        void bytes(long done, long total);
    }

    static final class Result {
        File file;
        String container;
        /** HLS 拼接后的布局：init 长度与每个分片的长度、时长、是否不连续，供本 App 按字节范围播放。 */
        long initLength;
        long[] lengths;
        double[] durations;
        boolean[] discontinuities;
    }

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    private final OkHttpClient http;
    private final String userAgent, referer;
    volatile boolean cancelled;
    /** 缓存任务 id：调度器据此优先下载播放位置之后的分片。 */
    String streamId = "";

    Fetcher(OkHttpClient http, String userAgent, String referer) {
        this.http = http;
        this.userAgent = userAgent;
        this.referer = referer == null ? "" : referer;
    }

    private Request.Builder request(String url) {
        Request.Builder b = new Request.Builder().url(url).header("User-Agent", userAgent);
        if (!referer.isEmpty()) b.header("Referer", referer);
        return b;
    }

    private String text(String url) throws IOException {
        try (Response r = http.newCall(request(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code() + "：" + host(url));
            return r.body().string();
        }
    }

    private byte[] bytes(String url) throws IOException {
        try (Response r = http.newCall(request(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code() + "：" + host(url));
            return r.body().bytes();
        }
    }

    private static String host(String url) {
        HttpUrl u = HttpUrl.parse(url);
        return u == null ? url : u.host();
    }

    private static String resolve(String base, String ref) {
        HttpUrl b = HttpUrl.parse(base);
        HttpUrl r = b == null ? null : b.resolve(ref.trim());
        return r == null ? ref.trim() : r.toString();
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> map = new HashMap<>();
        Matcher m = ATTR.matcher(line.substring(line.indexOf(':') + 1));
        while (m.find()) {
            String v = m.group(2);
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
            map.put(m.group(1), v);
        }
        return map;
    }

    static final class Segment {
        String url, keyUrl;
        byte[] iv;
        double duration;
        boolean discontinuity;
    }

    /** 地址看不出类型时，读开头几百字节判断是否为 m3u8。 */
    boolean sniffHls(String url) {
        try (Response r = http.newCall(request(url).header("Range", "bytes=0-511").build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return false;
            String type = r.header("Content-Type", "");
            if (type != null && type.toLowerCase(Locale.ROOT).contains("mpegurl")) return true;
            okio.BufferedSource body = r.body().source();
            body.request(512);
            byte[] head = body.getBuffer().readByteArray(Math.min(512, body.getBuffer().size()));
            return new String(head, java.nio.charset.StandardCharsets.UTF_8).trim().startsWith("#EXTM3U");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ---------- HLS ----------

    Result hls(String url, File work, Progress progress) throws IOException {
        String playlist = text(url);
        String mediaUrl = url;
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            // 主播放列表：选码率最高的一路
            String best = null;
            long bestRate = -1;
            String[] lines = playlist.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                long rate = 0;
                try {
                    rate = Long.parseLong(attrs(lines[i]).getOrDefault("BANDWIDTH", "0").trim());
                } catch (NumberFormatException ignored) {
                    // 没有码率时按出现顺序
                }
                for (int j = i + 1; j < lines.length; j++) {
                    String next = lines[j].trim();
                    if (next.isEmpty() || next.startsWith("#")) continue;
                    if (rate > bestRate) {
                        bestRate = rate;
                        best = resolve(url, next);
                    }
                    break;
                }
            }
            if (best == null) throw new IOException("播放列表里没有可用的清晰度");
            mediaUrl = best;
            playlist = text(mediaUrl);
        }
        List<Segment> segments = new ArrayList<>();
        String initUrl = null, keyUrl = null;
        byte[] keyIv = null;
        long sequence = 0;
        double duration = 0;
        boolean discontinuity = false;
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    sequence = Long.parseLong(line.substring(22).trim());
                } catch (NumberFormatException ignored) {
                    sequence = 0;
                }
            } else if (line.startsWith("#EXTINF:")) {
                try {
                    duration = Double.parseDouble(line.substring(8).split(",")[0].trim());
                } catch (NumberFormatException ignored) {
                    duration = 0;
                }
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                discontinuity = true;
            } else if (line.startsWith("#EXT-X-KEY")) {
                Map<String, String> a = attrs(line);
                String method = a.getOrDefault("METHOD", "NONE");
                if ("NONE".equals(method)) {
                    keyUrl = null;
                } else if ("AES-128".equals(method)) {
                    keyUrl = resolve(mediaUrl, a.getOrDefault("URI", ""));
                    String iv = a.get("IV");
                    keyIv = iv == null ? null : hex16(iv);
                } else {
                    throw new IOException("不支持的分片加密方式：" + method);
                }
            } else if (line.startsWith("#EXT-X-MAP")) {
                initUrl = resolve(mediaUrl, attrs(line).getOrDefault("URI", ""));
            } else if (!line.startsWith("#")) {
                Segment s = new Segment();
                s.url = resolve(mediaUrl, line);
                s.keyUrl = keyUrl;
                s.iv = keyIv != null ? keyIv : hex16(BigInteger.valueOf(sequence).toString(16));
                s.duration = duration > 0 ? duration : 10;
                s.discontinuity = discontinuity;
                segments.add(s);
                sequence++;
                duration = 0;
                discontinuity = false;
            }
        }
        if (segments.isEmpty()) throw new IOException("播放列表里没有分片");
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        // 分片列表写成 stream.json，播放器据此边存边播
        Stream.writeIndex(work, referer, userAgent, initUrl, segments);
        Map<String, byte[]> keys = new HashMap<>();
        int count = segments.size();
        long[] total = {0};
        int existing = 0;
        for (int i = 0; i < count; i++) {
            File f = segFile(work, i);
            if (f.exists()) {
                existing++;
                total[0] += f.length();
            }
        }
        AtomicInteger done = new AtomicInteger(existing);
        progress.segments(done.get(), count, total[0]);
        Stream.Scheduler scheduler = new Stream.Scheduler(streamId, count, i -> segFile(work, i).exists());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                jobs.add(pool.submit(() -> {
                    int index;
                    while ((index = scheduler.next()) >= 0) {
                        if (cancelled) throw new IOException("已暂停");
                        Segment s = segments.get(index);
                        byte[] data = segment(s.url, s.keyUrl, s.iv, keys);
                        // 播放器可能已经自己取了这一片，此时不重复计数
                        if (!saveSegment(work, index, data)) continue;
                        synchronized (total) {
                            total[0] += data.length;
                            progress.segments(done.incrementAndGet(), count, total[0]);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                try {
                    job.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    cancelled = true;
                    Throwable cause = e.getCause();
                    throw cause instanceof IOException ? (IOException) cause : new IOException(String.valueOf(cause), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("已中断");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        long bytes = 0;
        for (int i = 0; i < count; i++) {
            File f = segFile(work, i);
            if (!f.exists()) throw new IOException("分片 " + (i + 1) + " 缺失");
            bytes += f.length();
        }
        progress.segments(count, count, bytes);
        String initRef = initUrl;
        File initFile = new File(work, "init.bin");
        byte[] init = initRef == null ? null
                : initFile.exists() ? java.nio.file.Files.readAllBytes(initFile.toPath()) : withRetry(() -> bytes(initRef));
        boolean fmp4 = init != null || startsWithBox(segFile(work, 0));
        Result result = new Result();
        result.container = fmp4 ? "mp4" : "ts";
        result.file = new File(work, "joined." + result.container);
        result.initLength = init == null ? 0 : init.length;
        result.lengths = new long[count];
        result.durations = new double[count];
        result.discontinuities = new boolean[count];
        for (int i = 0; i < count; i++) {
            result.lengths[i] = segFile(work, i).length();
            result.durations[i] = segments.get(i).duration;
            result.discontinuities[i] = segments.get(i).discontinuity;
        }
        try (OutputStream o = new FileOutputStream(result.file)) {
            if (init != null) o.write(init);
            byte[] buf = new byte[256 * 1024];
            for (int i = 0; i < count; i++) {
                try (InputStream in = new FileInputStream(segFile(work, i))) {
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
        // 正在边存边播时分片留到播放器退出后再删
        if (!Stream.inUse(streamId)) deleteSegments(work);
        return result;
    }

    /** 删除分片与 stream.json（拼接完成后不再需要）。 */
    static void deleteSegments(File work) {
        //noinspection ResultOfMethodCallIgnored
        new File(work, Stream.INDEX).delete();
        File[] files = work.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            //noinspection ResultOfMethodCallIgnored
            if (n.startsWith("seg_") || n.equals("init.bin")) f.delete();
        }
    }

    /** 下载并按需解密一个分片；播放器边存边播时也用它。 */
    byte[] segment(String url, String keyUrl, byte[] iv, Map<String, byte[]> keys) throws IOException {
        byte[] data = withRetry(() -> bytes(url));
        if (keyUrl == null) return data;
        byte[] key;
        synchronized (keys) {
            key = keys.get(keyUrl);
            if (key == null) {
                key = withRetry(() -> bytes(keyUrl));
                keys.put(keyUrl, key);
            }
        }
        return decrypt(data, key, iv);
    }

    /** 不解密地下载一个地址（fMP4 的 init 分片）。 */
    byte[] plain(String url) throws IOException { return withRetry(() -> bytes(url)); }

    /** 先写临时文件再改名；目标已存在（另一方先写好了）返回 false。 */
    static boolean saveSegment(File work, int index, byte[] data) throws IOException {
        File target = segFile(work, index);
        if (target.exists()) return false;
        File part = new File(work, String.format(Locale.ROOT, "seg_%05d.%d.part", index, Thread.currentThread().getId()));
        try (OutputStream o = new FileOutputStream(part)) {
            o.write(data);
        }
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        if (!part.renameTo(target)) throw new IOException("写入分片失败");
        return true;
    }

    static File segFile(File work, int i) { return new File(work, String.format(Locale.ROOT, "seg_%05d.bin", i)); }

    private static boolean startsWithBox(File f) {
        byte[] head = new byte[8];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(head) < 8) return false;
        } catch (IOException e) {
            return false;
        }
        String type = new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return type.equals("ftyp") || type.equals("moof") || type.equals("styp");
    }

    static byte[] hex16(String hex) {
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        byte[] out = new byte[16];
        byte[] v = new BigInteger(h.isEmpty() ? "0" : h, 16).toByteArray();
        int copy = Math.min(16, v.length);
        System.arraycopy(v, v.length - copy, out, 16 - copy, copy);
        return out;
    }

    private static byte[] decrypt(byte[] data, byte[] key, byte[] iv) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("分片解密失败", e);
        }
    }

    private interface Call<T> { T run() throws IOException; }

    private <T> T withRetry(Call<T> call) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            if (cancelled) throw new IOException("已暂停");
            try {
                return call.run();
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(1000L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("已中断");
                }
            }
        }
        throw last;
    }

    // ---------- 直链文件 ----------

    Result file(String url, File work, Progress progress) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        File part = new File(work, "file.part");
        long have = part.exists() ? part.length() : 0;
        Request.Builder b = request(url);
        if (have > 0) b.header("Range", "bytes=" + have + "-");
        try (Response r = http.newCall(b.build()).execute()) {
            ResponseBody body = r.body();
            if (!r.isSuccessful() || body == null) throw new IOException("HTTP " + r.code() + "：" + host(url));
            boolean resumed = r.code() == 206;
            if (!resumed) have = 0;
            long total = body.contentLength() < 0 ? -1 : body.contentLength() + have;
            String container = containerOf(url, r.header("Content-Type", ""));
            try (InputStream in = body.byteStream(); OutputStream o = new FileOutputStream(part, resumed)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                long done = have, last = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new IOException("已暂停");
                    o.write(buf, 0, n);
                    done += n;
                    if (done - last > 512 * 1024) {
                        progress.bytes(done, total);
                        last = done;
                    }
                }
                progress.bytes(done, total);
            }
            Result result = new Result();
            result.container = container;
            result.file = new File(work, "joined." + container);
            if (!part.renameTo(result.file)) throw new IOException("保存文件失败");
            return result;
        }
    }

    private static String containerOf(String url, String type) {
        String path = url.toLowerCase(Locale.ROOT);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        for (String ext : new String[]{"mp4", "mkv", "flv", "webm", "mov", "avi", "ts", "m4v"}) {
            if (path.endsWith("." + ext)) return ext;
        }
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("x-flv")) return "flv";
        if (t.contains("matroska")) return "mkv";
        if (t.contains("webm")) return "webm";
        if (t.contains("mp2t")) return "ts";
        return "mp4";
    }
}
