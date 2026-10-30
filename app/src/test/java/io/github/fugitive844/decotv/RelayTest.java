package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.fugitive844.decotv.net.MediaUrls;
import io.github.fugitive844.decotv.net.Relay;

import org.junit.Test;

import java.util.List;

public class RelayTest {
    @Test public void unknownModeFallsBackToDirect() {
        assertEquals(Relay.Mode.DIRECT, Relay.Mode.of(null));
        assertEquals(Relay.Mode.DIRECT, Relay.Mode.of("bogus"));
        assertEquals(Relay.Mode.SMART, Relay.Mode.of("smart"));
    }

    @Test public void candidatesFollowMode() {
        assertEquals(List.of("d"), Relay.candidates(Relay.Mode.DIRECT, "d", "r"));
        assertEquals(List.of("r"), Relay.candidates(Relay.Mode.RELAY, "d", "r"));
        assertEquals(List.of("d", "r"), Relay.candidates(Relay.Mode.SMART, "d", "r"));
        assertEquals(List.of("d"), Relay.candidates(Relay.Mode.SMART, "d", null));
    }

    @Test public void serverAddressIsNormalized() {
        assertEquals("https://tv.example.com", Relay.normalizeServer(" tv.example.com/ "));
        assertEquals("http://10.0.2.2:13300", Relay.normalizeServer("http://10.0.2.2:13300//"));
    }

    @Test public void relativeUrlsBecomeAbsolute() {
        assertEquals("https://s.example/api/x?a=1", Relay.absolute("https://s.example", "/api/x?a=1"));
        assertEquals("https://cdn.example/a.jpg", Relay.absolute("https://s.example", "//cdn.example/a.jpg"));
        assertEquals("http://cdn.example/a.jpg", Relay.absolute("https://s.example", "http://cdn.example/a.jpg"));
    }

    @Test public void proxyUrlsEncodeTarget() {
        assertEquals("https://s/api/image-proxy?url=http%3A%2F%2Fa.b%2Fc.jpg%3Fx%3D1",
                Relay.imageProxy("https://s", "http://a.b/c.jpg?x=1"));
        assertEquals("https://s/api/proxy/m3u8?url=http%3A%2F%2Fl%2Fa.m3u8&decotv-source=k1",
                Relay.liveProxy("https://s", "http://l/a.m3u8", "k1", true));
        assertEquals("https://s/api/proxy/stream?url=http%3A%2F%2Fl%2Fa.flv&decotv-source=k1",
                Relay.liveProxy("https://s", "http://l/a.flv", "k1", false));
    }

    @Test public void mediaDetection() {
        assertTrue(MediaUrls.isHls("https://a/b/index.m3u8?t=1"));
        assertTrue(MediaUrls.looksLikeMedia("http://a/v.MP4"));
        assertFalse(MediaUrls.looksLikeMedia("https://vip.example.com/share/90ccff10"));
        assertFalse(MediaUrls.isHls("https://a/b.mp4#x.m3u8"));
        assertEquals("a.b", MediaUrls.host("http://a.b:81/x y.m3u8"));
        assertEquals("https://movie.douban.com/", MediaUrls.imageReferer("https://img1.doubanio.com/a.jpg"));
    }
}
