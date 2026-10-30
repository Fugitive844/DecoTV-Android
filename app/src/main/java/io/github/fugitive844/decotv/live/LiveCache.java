package io.github.fugitive844.decotv.live;

import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 直播源列表和各源频道列表的本地缓存，存在应用私有目录，一直保留；
 * 只有服务端返回的直播源列表里没有了某个源，才删掉它的频道缓存。只在后台线程调用。
 * 频道缓存只为用户实际打开过的源写入（由直播页决定），不会为所有源预先缓存。
 * <p>
 * 缓存只是加速手段，任何读写问题都不能影响使用：所有方法都不抛异常；
 * 文件损坏、格式不对、版本不符或过大时，删掉该文件当作没有缓存，随后从服务端重新拉取并重写。
 */
public final class LiveCache {
    /** 频道列表超过这个时长后，打开时在后台静默重新拉取。 */
    public static final long CHANNELS_MAX_AGE_MS = 5 * 60 * 60 * 1000L;
    /** 文件格式版本，格式变了就递增，旧文件会被当作损坏删除。 */
    static final int VERSION = 1;
    /** 单个缓存文件的上限，超过视为异常，避免读入时内存不足。 */
    static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    /** 缓存的频道列表（未合并的原始列表）。 */
    public static final class Entry {
        public final List<Channel> channels;
        public final long savedAt;
        public final String url;
        public final boolean relayed;

        Entry(List<Channel> channels, long savedAt, String url, boolean relayed) {
            this.channels = channels;
            this.savedAt = savedAt;
            this.url = url;
            this.relayed = relayed;
        }

        /** 超过 5 小时、源地址已改，或保存时间在将来（手机时间被调过），都需要重新拉取。 */
        public boolean stale(LiveSource source) {
            long age = System.currentTimeMillis() - savedAt;
            return !url.equals(source.url) || age < 0 || age > CHANNELS_MAX_AGE_MS;
        }
    }

    private final File dir;

    public LiveCache(File filesDir) {
        dir = new File(filesDir, "live-cache");
    }

    public synchronized List<LiveSource> sources() {
        File f = new File(dir, "sources.json");
        try {
            JSONObject o = readObject(f);
            if (o == null) return new ArrayList<>();
            JSONArray a = o.getJSONArray("sources");
            List<LiveSource> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject item = a.optJSONObject(i);
                if (item == null) continue;
                LiveSource s = LiveSource.fromJson(item);
                if (!s.key.isEmpty() && !s.url.isEmpty()) list.add(s);
            }
            return list;
        } catch (Throwable e) {
            discard(f);
            return new ArrayList<>();
        }
    }

    /** 保存最新的直播源列表，并删掉已不存在的源的频道缓存。 */
    public synchronized void saveSources(List<LiveSource> list) {
        try {
            JSONArray a = new JSONArray();
            Set<String> keep = new HashSet<>();
            for (LiveSource s : list) {
                a.put(s.toJson());
                keep.add(channelsFile(s.key).getName());
            }
            write(new File(dir, "sources.json"), new JSONObject().put("v", VERSION).put("sources", a).toString());
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                // 不认识的文件和写到一半的临时文件也一并清掉
                if (name.equals("sources.json") || keep.contains(name)) continue;
                discard(f);
            }
        } catch (Throwable ignored) {
            // 写缓存失败不影响使用，下次再写
        }
    }

    public synchronized Entry channels(String key) {
        File f = channelsFile(key);
        try {
            JSONObject o = readObject(f);
            if (o == null) return null;
            JSONArray a = o.getJSONArray("channels");
            List<Channel> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject item = a.optJSONObject(i);
                if (item == null) continue;
                Channel c = Channel.fromJson(item);
                if (!c.url.isEmpty()) list.add(c);
            }
            if (list.isEmpty()) {
                discard(f);
                return null;
            }
            return new Entry(list, o.optLong("savedAt"), o.optString("url"), o.optBoolean("relayed"));
        } catch (Throwable e) {
            discard(f);
            return null;
        }
    }

    public synchronized void saveChannels(LiveSource source, List<Channel> channels, boolean relayed) {
        try {
            JSONArray a = new JSONArray();
            for (Channel c : channels) a.put(c.toJson());
            JSONObject o = new JSONObject().put("v", VERSION).put("url", source.url)
                    .put("savedAt", System.currentTimeMillis()).put("relayed", relayed).put("channels", a);
            write(channelsFile(source.key), o.toString());
        } catch (Throwable ignored) {
            // 同上
        }
    }

    File channelsFile(String key) {
        StringBuilder safe = new StringBuilder("channels-");
        for (char ch : key.toCharArray()) safe.append(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' ? ch : '_');
        return new File(dir, safe.append('-').append(Integer.toHexString(key.hashCode())).append(".json").toString());
    }

    /** 读取并校验一个缓存文件；不存在返回 null，内容有问题就删掉并返回 null。 */
    private JSONObject readObject(File f) throws IOException {
        if (!f.isFile()) {
            if (f.exists()) discard(f);
            return null;
        }
        if (f.length() > MAX_FILE_BYTES) {
            discard(f);
            return null;
        }
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (o.optInt("v") != VERSION) {
                discard(f);
                return null;
            }
            return o;
        } catch (org.json.JSONException e) {
            discard(f);
            return null;
        }
    }

    /** 删除坏掉的缓存文件；是目录（异常情况）也整个删掉。 */
    static void discard(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) discard(c);
        }
        f.delete();
    }

    /** 先写临时文件再改名，避免写到一半被杀进程留下坏文件。缓存目录被同名文件占住时先删掉它。 */
    private void write(File f, String text) throws IOException {
        if (dir.exists() && !dir.isDirectory()) discard(dir);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        if (f.isDirectory()) discard(f);
        File tmp = new File(dir, f.getName() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) discard(tmp);
        }
    }
}
