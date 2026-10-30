package io.github.fugitive844.decotv.live;

import io.github.fugitive844.decotv.model.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 m3u 直播列表，字段与服务端 /api/live/channels 保持一致。 */
public final class M3uParser {
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private M3uParser() {}

    public static List<Channel> parse(String sourceKey, String text) {
        List<Channel> list = new ArrayList<>();
        if (text == null) return list;
        String[] lines = text.replace("\r", "").split("\n");
        Channel pending = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                pending = new Channel();
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    switch (m.group(1).toLowerCase(java.util.Locale.ROOT)) {
                        case "tvg-id": pending.tvgId = m.group(2); break;
                        case "tvg-logo": pending.logo = m.group(2); break;
                        case "group-title": pending.group = m.group(2); break;
                        case "tvg-name": if (pending.name.isEmpty()) pending.name = m.group(2); break;
                        default: break;
                    }
                }
                int comma = lastCommaOutsideQuotes(line);
                String title = comma >= 0 ? line.substring(comma + 1).trim() : "";
                if (!title.isEmpty()) pending.name = title;
            } else if (!line.startsWith("#")) {
                if (pending == null) continue;
                pending.url = line;
                if (pending.name.isEmpty()) pending.name = "频道 " + (list.size() + 1);
                if (pending.group.isEmpty()) pending.group = "默认分组";
                pending.id = sourceKey + "-" + list.size();
                list.add(pending);
                pending = null;
            }
        }
        return list;
    }

    private static int lastCommaOutsideQuotes(String line) {
        boolean quoted = false;
        int found = -1;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) found = i;
        }
        return found;
    }
}
