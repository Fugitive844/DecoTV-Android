package io.github.fugitive844.decotv.live;

import static org.junit.Assert.assertEquals;

import io.github.fugitive844.decotv.model.Channel;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ChannelMergeTest {
    private static Channel ch(String name, String group, String url, String logo) {
        Channel c = new Channel();
        c.name = name;
        c.group = group;
        c.url = url;
        c.logo = logo;
        return c;
    }

    @Test public void mergesSameNameInSameGroupKeepingOrder() {
        List<Channel> merged = ChannelMerge.merge(Arrays.asList(
                ch("CCTV-1", "央视频道", "http://a/1", ""),
                ch("CCTV-2", "央视频道", "http://a/2", "logo2"),
                ch("cctv 1", "央视频道", "http://b/1", "logo1"),
                ch("CCTV-1", "央视频道", "http://a/1", ""),
                ch("CCTV-1", "4K", "http://c/1", "")));
        assertEquals(3, merged.size());
        Channel first = merged.get(0);
        assertEquals("CCTV-1", first.name);
        assertEquals(Arrays.asList("http://a/1", "http://b/1"), first.sources());
        assertEquals("http://a/1", first.url);
        assertEquals("logo1", first.logo);
        assertEquals("CCTV-2", merged.get(1).name);
        assertEquals("4K", merged.get(2).group);
    }

    @Test public void unmergedChannelHasItsOwnUrlAsOnlySource() {
        assertEquals(Arrays.asList("http://x"), ch("A", "g", "http://x", "").sources());
    }
}
