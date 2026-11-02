package io.github.fugitive844.decotv.live;

import io.github.fugitive844.decotv.model.Channel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 同一分组下同名频道合并，频道顺序不变；播放源按 IP 在前、域名在后排列。 */
public final class ChannelMerge {
    private ChannelMerge() {}

    /** 忽略大小写、空白、连字符和下划线，「CCTV-1」与「cctv 1」视为同一频道。 */
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]+", "");
    }

    public static List<Channel> merge(List<Channel> channels) {
        Map<String, Channel> merged = new LinkedHashMap<>();
        for (Channel c : channels) {
            if (c.url.isEmpty()) continue;
            String k = key(c.name);
            k = k.isEmpty() ? "#" + c.url : c.group + "\n" + k;
            Channel first = merged.get(k);
            if (first == null) {
                Channel m = new Channel();
                m.id = c.id;
                m.tvgId = c.tvgId;
                m.name = c.name;
                m.logo = c.logo;
                m.group = c.group;
                m.url = c.url;
                m.urls.add(c.url);
                merged.put(k, m);
                continue;
            }
            if (!first.urls.contains(c.url)) first.urls.add(c.url);
            if (first.logo.isEmpty()) first.logo = c.logo;
            if (first.tvgId.isEmpty()) first.tvgId = c.tvgId;
        }
        for (Channel channel : merged.values()) {
            // 稳定排序：同类地址保持订阅中的顺序，首选源与换源列表使用同一顺序。
            channel.urls.sort(java.util.Comparator.comparingInt(url -> ipSource(url) ? 0 : 1));
            channel.url = channel.urls.get(0);
        }
        return new ArrayList<>(merged.values());
    }

    /** 只检查 URL 主机，不做 DNS 解析，兼容 IPv4、IPv6 和带端口的地址。 */
    private static boolean ipSource(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) return false;
            if (host.contains(":")) return true;
            if (!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
            for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) return false;
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
