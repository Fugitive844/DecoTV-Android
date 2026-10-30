package io.github.fugitive844.decotv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.fugitive844.decotv.update.UpdateRelease;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

public class UpdateReleaseTest {
    private static final String ORIGIN = "https://zealot.example.com";
    private static final String PKG = "io.github.fugitive844.decotv";
    private static final String SHA = "947d3f0291bfcf12a21692b73c1043739cdb8b8cacf5bef3260ff1aafbe7a087";

    private static JSONObject release(long code, String url, String sha, String... extra) throws Exception {
        JSONArray fields = new JSONArray().put(new JSONObject().put("name", "versionCode").put("value", String.valueOf(code)));
        if (sha != null) fields.put(new JSONObject().put("name", "SHA256").put("value", sha));
        for (int i = 0; i + 1 < extra.length; i += 2) fields.put(new JSONObject().put("name", extra[i]).put("value", extra[i + 1]));
        return new JSONObject().put("bundle_id", PKG).put("build_version", String.valueOf(code)).put("release_version", "1.0." + code)
                .put("install_url", url).put("size", 1024).put("custom_fields", fields).put("text_changelog", "说明");
    }

    private static JSONObject response(JSONObject... items) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject item : items) list.put(item);
        return new JSONObject().put("releases", list);
    }

    @Test public void picksHighestNewerRelease() throws Exception {
        UpdateRelease r = UpdateRelease.latest(response(release(2, ORIGIN + "/download/releases/1", SHA),
                release(3, ORIGIN + "/download/releases/2", SHA)), ORIGIN, PKG, 1);
        assertEquals(3, r.code);
        assertEquals("1.0.3", r.name);
        assertEquals(UpdateRelease.Mode.NORMAL, r.mode);
    }

    @Test public void noUpdateWhenInstalledIsLatest() throws Exception {
        assertNull(UpdateRelease.latest(response(release(2, ORIGIN + "/d", SHA)), ORIGIN, PKG, 2));
    }

    @Test(expected = IOException.class) public void rejectsForeignDownloadHost() throws Exception {
        UpdateRelease.latest(response(release(2, "https://evil.example.com/a.apk", SHA)), ORIGIN, PKG, 1);
    }

    @Test(expected = IOException.class) public void requiresSha256() throws Exception {
        UpdateRelease.latest(response(release(2, ORIGIN + "/d", null)), ORIGIN, PKG, 1);
    }

    @Test public void policyModes() throws Exception {
        JSONObject item = release(5, ORIGIN + "/d", SHA, "minimumSupportedVersionCode", "3", "minimumRecommendedVersionCode", "4");
        assertEquals(UpdateRelease.Mode.REQUIRED, UpdateRelease.latest(response(item), ORIGIN, PKG, 2).mode);
        assertEquals(UpdateRelease.Mode.RECOMMENDED, UpdateRelease.latest(response(item), ORIGIN, PKG, 3).mode);
        assertEquals(UpdateRelease.Mode.NORMAL, UpdateRelease.latest(response(item), ORIGIN, PKG, 4).mode);
    }

    @Test public void safeUrlOnlyAllowsSameHttpsHost() {
        assertTrue(UpdateRelease.safeUrl(ORIGIN + "/download/releases/1", ORIGIN));
        assertFalse(UpdateRelease.safeUrl("http://zealot.example.com/x", ORIGIN));
        assertFalse(UpdateRelease.safeUrl("https://zealot.example.com:8443/x", ORIGIN));
        assertFalse(UpdateRelease.safeUrl("https://user@zealot.example.com/x", ORIGIN));
    }
}
