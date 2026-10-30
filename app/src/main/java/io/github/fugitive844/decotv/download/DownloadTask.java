package io.github.fugitive844.decotv.download;

import org.json.JSONException;
import org.json.JSONObject;

/** 一集的离线缓存任务。状态与进度持久化在本机，App 重启后可继续。 */
public final class DownloadTask {
    /** 保存格式：原格式与 MP4 存到系统「影片/DecoTV」，其他播放器可打开；加密格式只存在本 App 私有目录，只能在本 App 内播放。 */
    public enum Format {
        ORIGINAL("original", "原格式"), MP4("mp4", "MP4"), ENCRYPTED("encrypted", "加密（仅本 App）");

        public final String key, label;

        Format(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public static Format of(String key) {
            for (Format f : values()) if (f.key.equals(key)) return f;
            return ENCRYPTED;
        }
    }

    public enum State { QUEUED, DOWNLOADING, CONVERTING, DONE, FAILED, PAUSED }

    public String id = "";
    /** 影片 source+id、标题、来源名、封面，以及第几集（从 0 开始）和集名。 */
    public String videoKey = "", title = "", sourceName = "", cover = "", episodeTitle = "", episodeUrl = "", videoJson = "";
    public int episode;
    public Format format = Format.ENCRYPTED;
    public State state = State.QUEUED;
    public String error = "";
    /** 完成后：加密文件的本地路径，或公共媒体库中的 content:// 地址。 */
    public String output = "";
    /** 实际保存的扩展名（如 ts、mp4），原格式时用于说明。 */
    public String container = "";
    public long bytes, totalBytes, createdAt;
    public int doneSegments, totalSegments;
    /** 下载走的是直连还是服务端中转。 */
    public boolean relayed;

    /** 缓存时一并保存的弹幕（服务端原始 JSON），离线播放时读取。 */
    public static java.io.File danmakuFile(android.content.Context c, String id) {
        return new java.io.File(new java.io.File(c.getFilesDir(), "offline"), id + ".danmaku.json");
    }

    public float progress() {
        if (state == State.DONE) return 1f;
        if (totalSegments > 0) return Math.min(1f, doneSegments / (float) totalSegments);
        if (totalBytes > 0) return Math.min(1f, bytes / (float) totalBytes);
        return 0f;
    }

    public String fileName() {
        String base = (title + " " + episodeTitle).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("videoKey", videoKey).put("title", title).put("sourceName", sourceName).put("cover", cover)
                    .put("episodeTitle", episodeTitle).put("episodeUrl", episodeUrl).put("videoJson", videoJson).put("episode", episode)
                    .put("format", format.key).put("state", state.name()).put("error", error).put("output", output)
                    .put("container", container).put("bytes", bytes).put("totalBytes", totalBytes).put("createdAt", createdAt)
                    .put("doneSegments", doneSegments).put("totalSegments", totalSegments).put("relayed", relayed);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static DownloadTask fromJson(JSONObject o) {
        DownloadTask t = new DownloadTask();
        t.id = o.optString("id");
        t.videoKey = o.optString("videoKey");
        t.title = o.optString("title");
        t.sourceName = o.optString("sourceName");
        t.cover = o.optString("cover");
        t.episodeTitle = o.optString("episodeTitle");
        t.episodeUrl = o.optString("episodeUrl");
        t.videoJson = o.optString("videoJson");
        t.episode = o.optInt("episode");
        t.format = Format.of(o.optString("format"));
        try {
            t.state = State.valueOf(o.optString("state", "QUEUED"));
        } catch (IllegalArgumentException e) {
            t.state = State.QUEUED;
        }
        t.error = o.optString("error");
        t.output = o.optString("output");
        t.container = o.optString("container");
        t.bytes = o.optLong("bytes");
        t.totalBytes = o.optLong("totalBytes");
        t.createdAt = o.optLong("createdAt");
        t.doneSegments = o.optInt("doneSegments");
        t.totalSegments = o.optInt("totalSegments");
        t.relayed = o.optBoolean("relayed");
        return t;
    }
}
