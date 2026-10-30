package io.github.fugitive844.decotv.net;

import android.util.Log;

/** 记录每类资源实际走的路径与主机，便于确认直连没有经过服务端。 */
public final class NetLog {
    public static final String TAG = "DecoNet";
    private NetLog() {}

    public static void use(Relay.Item item, boolean relayed, String url) {
        Log.i(TAG, "item=" + item.key + " path=" + (relayed ? "relay" : "direct") + " host=" + MediaUrls.host(url));
    }

    public static void fail(Relay.Item item, boolean relayed, String url, Throwable error) {
        Log.w(TAG, "item=" + item.key + " path=" + (relayed ? "relay" : "direct") + " host=" + MediaUrls.host(url)
                + " failed=" + (error == null ? "" : error.getClass().getSimpleName() + ":" + error.getMessage()));
    }
}
