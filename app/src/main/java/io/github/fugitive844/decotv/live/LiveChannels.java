package io.github.fugitive844.decotv.live;

import io.github.fugitive844.decotv.model.Channel;
import io.github.fugitive844.decotv.model.LiveSource;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.NetLog;
import io.github.fugitive844.decotv.net.Relay;

import java.io.IOException;
import java.util.List;

/** 按中转策略获取频道列表：直连下载 m3u，或由服务端解析。只在后台线程调用。 */
public final class LiveChannels {
    public final List<Channel> channels;
    public final boolean relayed;

    private LiveChannels(List<Channel> channels, boolean relayed) {
        this.channels = channels;
        this.relayed = relayed;
    }

    public static LiveChannels load(ApiClient api, LiveSource source, Relay.Mode mode) throws IOException {
        IOException last = null;
        if (mode != Relay.Mode.RELAY) {
            try {
                List<Channel> list = api.liveChannelsDirect(source);
                NetLog.use(Relay.Item.LIVE_LIST, false, source.url);
                return new LiveChannels(list, false);
            } catch (IOException e) {
                NetLog.fail(Relay.Item.LIVE_LIST, false, source.url, e);
                if (e instanceof ApiClient.AuthException) throw e;
                last = e;
            }
        }
        if (mode != Relay.Mode.DIRECT) {
            List<Channel> list = api.liveChannelsByServer(source.key);
            NetLog.use(Relay.Item.LIVE_LIST, true, api.server());
            return new LiveChannels(list, true);
        }
        throw last;
    }
}
