package com.bjunicom.iptv.network;

import android.text.TextUtils;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 组播地址、单播代理地址与北京联通时移回看地址解析器
 * 支持:
 * 1. 路由器 udpxy 模式: rtp://239.3.1.129:8008 -> http://192.168.2.1:8012/rtp/239.3.1.129:8008
 * 2. 本地内置组播转单播网关模式: rtp://239.3.1.129:8008 -> http://127.0.0.1:<port>/rtp/239.3.1.129:8008
 * 3. 原生 RTP/UDP 组播直连模式: rtp://239.3.1.129:8008
 * 4. 北京联通 IPTV 时移回看模式 (参考 zzzz0317/beijing-unicom-iptv-playlist):
 *    - ts2hls 模式: http://61.135.88.136/TVOD/.../*.smil/index.m3u8?fmt=ts2hls&playseek=yyyyMMddHHmmss-yyyyMMddHHmmss
 *    - rtp2httpd HTTP 代理 RTSP 模式: http://<proxy>/rtsp/61.135.88.136/TVOD/.../*.smil?playseek=yyyyMMddHHmmss-yyyyMMddHHmmss
 *    - 原生 RTSP 时移模式: rtsp://61.135.88.136/TVOD/.../*.smil?playseek=yyyyMMddHHmmss-yyyyMMddHHmmss
 */
public final class StreamUrlResolver {

    public static final String DEFAULT_CATCHUP_PARAM = "playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}";
    public static final long DEFAULT_TIMESHIFT_SPAN_MS = 4L * 3600L * 1000L; // 默认每次请求 4 小时窗口

    private StreamUrlResolver() {
    }

    private static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static String resolvePlaybackUrl(IptvChannel channel, IptvSettings settings, int localProxyPort) {
        if (channel == null || isEmpty(channel.getRawUrl())) {
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
        if (isEmpty(rawUrl) || isEmpty(udpxyAddr)) {
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

    /**
     * 将北京联通 RTSP 源地址转换为 ts2hls 格式（参考 zzzz0317/beijing-unicom-iptv-playlist generator.py convert_rtsp_ts2hls）：
     * rtsp://61.135.88.136/TVOD/.../xxx.smil?playseek=...
     * -> http://61.135.88.136/TVOD/.../xxx.smil/index.m3u8?fmt=ts2hls&playseek=...
     */
    public static String convertRtspToTs2Hls(String rtspOrHttpUrl, String httpProxyBase) {
        if (isEmpty(rtspOrHttpUrl)) {
            return "";
        }
        String url = rtspOrHttpUrl.trim();
        if (url.contains("/index.m3u8")) {
            return url;
        }
        String query = "";
        int qIdx = url.indexOf('?');
        String base = qIdx >= 0 ? url.substring(0, qIdx) : url;
        if (qIdx >= 0 && qIdx + 1 < url.length()) {
            query = url.substring(qIdx + 1);
        }

        // 若是 http://<proxy>/rtsp/61.135.88.136/TVOD/.../xxx.smil 代理地址，还原出主机路径
        int rtspPathIdx = base.indexOf("/rtsp/");
        String hostAndPath;
        if (base.regionMatches(true, 0, "rtsp://", 0, 7)) {
            hostAndPath = base.substring(7);
        } else if (rtspPathIdx > 0) {
            hostAndPath = base.substring(rtspPathIdx + "/rtsp/".length());
        } else if (base.regionMatches(true, 0, "http://", 0, 7)) {
            hostAndPath = base.substring(7);
        } else if (base.regionMatches(true, 0, "https://", 0, 8)) {
            hostAndPath = base.substring(8);
        } else {
            hostAndPath = base;
        }

        if (!hostAndPath.endsWith(".smil")) {
            return url;
        }

        String prefix;
        if (!isEmpty(httpProxyBase)) {
            String cleanProxy = httpProxyBase.trim()
                    .replace("http://", "")
                    .replace("https://", "")
                    .replaceAll("/+$", "");
            if (!cleanProxy.endsWith("/http")) {
                cleanProxy = cleanProxy + "/http";
            }
            prefix = "http://" + cleanProxy + "/" + hostAndPath + "/index.m3u8?fmt=ts2hls";
        } else {
            prefix = "http://" + hostAndPath + "/index.m3u8?fmt=ts2hls";
        }

        if (!isEmpty(query)) {
            return prefix + "&" + query;
        }
        return prefix;
    }

    /**
     * 将北京联通 RTSP 源地址转换为 rtp2httpd HTTP 代理格式（参考 generator.py convert_http_proxy）：
     * rtsp://61.135.88.136/TVOD/.../xxx.smil?playseek=...
     * -> http://192.168.2.1:8012/rtsp/61.135.88.136/TVOD/.../xxx.smil?playseek=...
     */
    public static String convertRtspToHttpProxy(String rtspUrl, String proxyAddr) {
        if (isEmpty(rtspUrl) || isEmpty(proxyAddr)) {
            return rtspUrl;
        }
        String url = rtspUrl.trim();
        String cleanServer = proxyAddr.trim()
                .replace("http://", "")
                .replace("https://", "")
                .replaceAll("/+$", "");
        if (url.regionMatches(true, 0, "rtsp://", 0, 7)) {
            return "http://" + cleanServer + "/rtsp/" + url.substring(7);
        }
        return replacePlaceholderProxyHost(url, cleanServer);
    }

    /**
     * 替换 zzzz0317 播放列表中预设的占位代理域名 (iptv.local:8080 / udpxy.local:8080) 为用户配置的 udpxy/rtp2httpd 地址
     */
    public static String replacePlaceholderProxyHost(String url, String proxyAddr) {
        if (isEmpty(url) || isEmpty(proxyAddr)) {
            return url;
        }
        String cleanServer = proxyAddr.trim()
                .replace("http://", "")
                .replace("https://", "")
                .replaceAll("/+$", "");
        return url.replace("iptv.local:8080", cleanServer)
                .replace("udpxy.local:8080", cleanServer)
                .replace("iptv.local", cleanServer)
                .replace("udpxy.local", cleanServer);
    }

    /**
     * 构建频道的时移源模板（未填充具体起止时间）
     */
    public static String buildCatchupTemplate(IptvChannel channel) {
        if (channel == null) {
            return "";
        }
        String catchupSource = channel.getCatchupSource();
        String catchupType = channel.getCatchupType();
        String rawUrl = channel.getRawUrl() != null ? channel.getRawUrl().trim() : "";

        if (!isEmpty(catchupSource)) {
            String src = catchupSource.trim();
            if ("append".equalsIgnoreCase(catchupType) || src.startsWith("?") || src.startsWith("&")) {
                if (isEmpty(rawUrl)) {
                    return "";
                }
                String base = rawUrl.replace("/PLTV/", "/TVOD/");
                String cleanParam = src.startsWith("?") || src.startsWith("&") ? src.substring(1) : src;
                return base + (base.contains("?") ? "&" : "?") + cleanParam;
            }
            if (!src.contains("playseek=") && !src.contains("${(b)") && !src.contains("{utc:")) {
                return src + (src.contains("?") ? "&" : "?") + DEFAULT_CATCHUP_PARAM;
            }
            return src;
        }

        if (rawUrl.contains("/PLTV/") || rawUrl.contains("/TVOD/")) {
            String tvodUrl = rawUrl.replace("/PLTV/", "/TVOD/");
            return tvodUrl + (tvodUrl.contains("?") ? "&" : "?") + DEFAULT_CATCHUP_PARAM;
        }

        return "";
    }

    /**
     * 解析并生成指定时间区间的北京联通/IPTV 时移回看播放地址：
     * - 默认优先使用无需外部 RTSP 代理即可由 ExoPlayer HlsMediaSource 直接播放的 ts2hls 模式
     *   (http://61.135.88.136/TVOD/.../*.smil/index.m3u8?fmt=ts2hls&playseek=yyyyMMddHHmmss-yyyyMMddHHmmss)
     * - 同时支持填充 ${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}、UTC、Kodi {utc:YmdHMS} 及时间戳占位符
     */
    public static String resolveTimeshiftUrl(IptvChannel channel,
                                             IptvSettings settings,
                                             long startTimeEpochMs,
                                             long endTimeEpochMs) {
        return resolveTimeshiftUrl(channel, settings, startTimeEpochMs, endTimeEpochMs, true);
    }

    public static String resolveTimeshiftUrl(IptvChannel channel,
                                             IptvSettings settings,
                                             long startTimeEpochMs,
                                             long endTimeEpochMs,
                                             boolean preferTs2Hls) {
        String template = buildCatchupTemplate(channel);
        if (isEmpty(template)) {
            return "";
        }

        long safeStart = Math.max(0L, startTimeEpochMs);
        long safeEnd = Math.max(safeStart + 60_000L, endTimeEpochMs);

        String filled = fillPlayseekTemplate(template, safeStart, safeEnd);
        String proxyAddr = settings != null ? settings.getUdpxyAddress() : "";
        if (!isEmpty(proxyAddr)) {
            filled = replacePlaceholderProxyHost(filled, proxyAddr);
        }

        if (preferTs2Hls) {
            if (filled.regionMatches(true, 0, "rtsp://", 0, 7)
                    && filled.contains(".smil")
                    && !filled.contains("/index.m3u8")) {
                return convertRtspToTs2Hls(filled, null);
            }
        } else if (filled.regionMatches(true, 0, "rtsp://", 0, 7)
                && settings != null
                && settings.getStreamMode() == IptvSettings.STREAM_MODE_UDPXY
                && !isEmpty(proxyAddr)) {
            return convertRtspToHttpProxy(filled, proxyAddr);
        }

        return filled;
    }

    /**
     * 填充 APTV / zzzz0317 / Kodi 标准时移时间占位符：
     * - ${(b)yyyyMMddHHmmss} / ${(e)yyyyMMddHHmmss} (北京时间 Asia/Shanghai)
     * - ${(b)yyyyMMddHHmmss:utc} / ${(e)yyyyMMddHHmmss:utc} (UTC)
     * - {utc:YmdHMS} / {utcend:YmdHMS} (Kodi 格式)
     * - ${(b)timestamp} / ${(e)timestamp} (秒级 Unix 时间戳)
     */
    public static String fillPlayseekTemplate(String template, long startTimeEpochMs, long endTimeEpochMs) {
        if (isEmpty(template)) {
            return "";
        }
        SimpleDateFormat bjFmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        bjFmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String bBj = bjFmt.format(new Date(startTimeEpochMs));
        String eBj = bjFmt.format(new Date(endTimeEpochMs));

        SimpleDateFormat utcFmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        utcFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String bUtc = utcFmt.format(new Date(startTimeEpochMs));
        String eUtc = utcFmt.format(new Date(endTimeEpochMs));

        String bTs = String.valueOf(startTimeEpochMs / 1000L);
        String eTs = String.valueOf(endTimeEpochMs / 1000L);

        return template
                .replace("${(b)yyyyMMddHHmmss:utc}", bUtc)
                .replace("${(e)yyyyMMddHHmmss:utc}", eUtc)
                .replace("${(b)yyyyMMddHHmmss}", bBj)
                .replace("${(e)yyyyMMddHHmmss}", eBj)
                .replace("{utc:YmdHMS}", bUtc)
                .replace("{utcend:YmdHMS}", eUtc)
                .replace("${(b)timestamp}", bTs)
                .replace("${(e)timestamp}", eTs);
    }
}

