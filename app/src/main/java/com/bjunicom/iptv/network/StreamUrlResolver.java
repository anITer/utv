package com.bjunicom.iptv.network;

import android.text.TextUtils;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;

/**
 * 组播地址与单播代理地址解析器
 * 支持:
 * 1. 路由器 udpxy 模式: rtp://239.3.1.129:8008 -> http://192.168.2.1:8012/rtp/239.3.1.129:8008
 * 2. 本地内置组播转单播网关模式: rtp://239.3.1.129:8008 -> http://127.0.0.1:<port>/rtp/239.3.1.129:8008
 * 3. 原生 RTP/UDP 组播直连模式: rtp://239.3.1.129:8008
 */
public final class StreamUrlResolver {

    private StreamUrlResolver() {
    }

    public static String resolvePlaybackUrl(IptvChannel channel, IptvSettings settings, int localProxyPort) {
        if (channel == null || TextUtils.isEmpty(channel.getRawUrl())) {
            return "";
        }
        String rawUrl = channel.getRawUrl().trim();
        int mode = settings.getStreamMode();

        if (mode == IptvSettings.STREAM_MODE_UDPXY) {
            return toUdpxyUrl(rawUrl, settings.getUdpxyAddress());
        } else if (localProxyPort > 0
                && (mode == IptvSettings.STREAM_MODE_LOCAL_PROXY || mode == IptvSettings.STREAM_MODE_DIRECT_RTP)) {
            // 注意：ijkplayer 0.8.8 的 libijkffmpeg.so 未编译 ff_rtp_protocol (rtp:// 协议)，
            // 且原生 udp:// 无法绑定无默认网关的 IPTV 专网网卡 (如 eth0 10.10.10.2) 及剥离 12 字节 RTP 头。
            // 因此 RTP 组播直连模式与本地组播代理模式统一通过本地组播引擎直接加入网卡 IGMP 组播组并解封装播放。
            return toUdpxyUrl(rawUrl, "127.0.0.1:" + localProxyPort);
        } else {
            return rawUrl;
        }
    }

    public static String toUdpxyUrl(String rawUrl, String udpxyAddr) {
        if (TextUtils.isEmpty(rawUrl) || TextUtils.isEmpty(udpxyAddr)) {
            return rawUrl;
        }
        String cleanServer = udpxyAddr.trim()
                .replace("http://", "")
                .replace("https://", "")
                .replaceAll("/+$", "");

        if (rawUrl.startsWith("rtp://")) {
            String target = rawUrl.substring("rtp://".length()).replace("@", "");
            return "http://" + cleanServer + "/rtp/" + target;
        } else if (rawUrl.startsWith("udp://")) {
            String target = rawUrl.substring("udp://".length()).replace("@", "");
            return "http://" + cleanServer + "/udp/" + target;
        }
        return rawUrl;
    }
}
