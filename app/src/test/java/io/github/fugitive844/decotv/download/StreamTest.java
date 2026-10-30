package io.github.fugitive844.decotv.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StreamTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static Fetcher.Segment seg(String url, String key, double d, boolean disc) {
        Fetcher.Segment s = new Fetcher.Segment();
        s.url = url;
        s.keyUrl = key;
        s.iv = Fetcher.hex16("0x0102");
        s.duration = d;
        s.discontinuity = disc;
        return s;
    }

    @Test public void indexRoundTripsAndBuildsLocalPlaylist() throws Exception {
        File work = tmp.newFolder();
        List<Fetcher.Segment> segs = Arrays.asList(seg("https://a/1.ts", "https://a/k", 9.5, false), seg("https://a/2.ts", null, 4, true));
        Stream.writeIndex(work, "https://ref/", "UA", "https://a/init.mp4", segs);
        Stream.Index x = Stream.readIndex(work);
        assertEquals("https://a/k", x.keys[0]);
        assertEquals(null, x.keys[1]);
        assertArrayEquals(Fetcher.hex16("0102"), x.ivs[0]);
        String m3u8 = Stream.playlist("abc", x);
        assertTrue(m3u8.startsWith("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:10\n"));
        assertTrue(m3u8.contains("#EXT-X-MAP:URI=\"dtvseg://abc/init\"\n"));
        assertTrue(m3u8.contains("#EXTINF:9.500,\ndtvseg://abc/0\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000,\ndtvseg://abc/1\n"));
        assertTrue(m3u8.endsWith("#EXT-X-ENDLIST\n"));
        assertFalse(m3u8.contains("EXT-X-KEY"));
    }

    @Test public void schedulerPrefersPlayheadThenFillsGaps() {
        Set<Integer> have = new HashSet<>(Arrays.asList(5, 6));
        Stream.Scheduler s = new Stream.Scheduler("t1", 10, have::contains);
        assertEquals(0, s.next());
        Stream.playhead("t1", 4);
        List<Integer> order = new ArrayList<>();
        int i;
        while ((i = s.next()) >= 0) order.add(i);
        // 4 之后已有 5、6，跳过；补完后面再回头补 1~3
        assertEquals(Arrays.asList(4, 7, 8, 9, 1, 2, 3), order);
    }

    @Test public void layoutPlaylistUsesByteRanges() throws Exception {
        Fetcher.Result r = new Fetcher.Result();
        r.initLength = 100;
        r.lengths = new long[]{1000, 500};
        r.durations = new double[]{6, 4.5};
        r.discontinuities = new boolean[]{false, true};
        File f = new File(tmp.newFolder(), "x.layout.json");
        Stream.writeLayout(f, r);
        String m3u8 = Stream.layoutPlaylist(f, "file:///d/x.dtv");
        assertTrue(m3u8.contains("#EXT-X-MAP:URI=\"file:///d/x.dtv\",BYTERANGE=\"100@0\"\n"));
        assertTrue(m3u8.contains("#EXTINF:6.000,\n#EXT-X-BYTERANGE:1000@100\nfile:///d/x.dtv\n#EXT-X-DISCONTINUITY\n#EXTINF:4.500,\n#EXT-X-BYTERANGE:500@1100\n"));
        assertEquals(null, Stream.layoutPlaylist(new File(f.getParent(), "none.json"), "file:///d/x.dtv"));
    }

    @Test public void saveSegmentDoesNotOverwrite() throws Exception {
        File work = tmp.newFolder();
        assertTrue(Fetcher.saveSegment(work, 3, new byte[]{1, 2}));
        assertFalse(Fetcher.saveSegment(work, 3, new byte[]{9}));
        assertEquals(2, Fetcher.segFile(work, 3).length());
        Fetcher.deleteSegments(work);
        assertFalse(Fetcher.segFile(work, 3).exists());
    }
}
