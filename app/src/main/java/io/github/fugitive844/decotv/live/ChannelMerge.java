package io.github.fugitive844.decotv.live;

import io.github.fugitive844.decotv.model.Channel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 把直播列表里同一分组下同名的频道合并成一个，各自的地址作为该频道的多个播放源，保持首次出现的顺序。 */
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
        return new ArrayList<>(merged.values());
    }
}
