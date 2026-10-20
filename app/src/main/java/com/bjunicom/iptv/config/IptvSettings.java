package com.bjunicom.iptv.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 播放器与北京联通 IPTV 网络转发配置管理
 */
public class IptvSettings {
    private static final String PREF_NAME = "bj_unicom_iptv_prefs";

    // 流播放传输模式
    public static final int STREAM_MODE_UDPXY = 0;          // 路由器/软路由 udpxy 组播转单播 (如 http://192.168.2.1:8012/rtp/...)
    public static final int STREAM_MODE_LOCAL_PROXY = 1;    // 内置 Android 本地组播转 HTTP 代理 (127.0.0.1)
    public static final int STREAM_MODE_DIRECT_RTP = 2;     // ijkplayer 直接播放 rtp://239.3.1.x:port

    // 播放器内核模式 (参考 ijkplayer Settings)
    public static final int PLAYER_IJK = 0;                 // IjkMediaPlayer (推荐，支持 MPEG-TS/RTP/HEVC 4K)
    public static final int PLAYER_ANDROID_MEDIA = 1;       // AndroidMediaPlayer
    public static final int PLAYER_IJK_EXO = 2;             // IjkExoMediaPlayer

    private static final String KEY_STREAM_MODE = "stream_mode";
    private static final String KEY_UDPXY_ADDR = "udpxy_addr";
    private static final String KEY_PLAYER_TYPE = "player_type";
    private static final String KEY_MEDIACODEC_ENABLED = "mediacodec_enabled";
    private static final String KEY_MEDIACODEC_HEVC = "mediacodec_hevc";
    private static final String KEY_OPENSLES_ENABLED = "opensles_enabled";
    private static final String KEY_RENDER_VIEW_TYPE = "render_view_type"; // 0: SurfaceView, 1: TextureView
    private static final String KEY_ASPECT_RATIO = "aspect_ratio";
    private static final String KEY_INCLUDE_HISTORICAL = "include_historical_channels";
    private static final String KEY_ONLY_SHOW_AVAILABLE = "only_show_available";
    private static final String KEY_LAST_CHANNEL_URL = "last_channel_url";
    private static final String KEY_HEVC_FALLBACK_CHANNELS = "hevc_fallback_channels";
    private static final String KEY_SCAN_SUBNET = "scan_subnet";
    private static final String KEY_SCAN_START_HOST = "scan_start_host";
    private static final String KEY_SCAN_END_HOST = "scan_end_host";
    private static final String KEY_SCAN_TIMEOUT_MS = "scan_timeout_ms";
    private static final String KEY_FAVORITES = "favorite_urls";

    private final SharedPreferences prefs;

    public IptvSettings(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getStreamMode() {
        return prefs.getInt(KEY_STREAM_MODE, STREAM_MODE_UDPXY);
    }

    public void setStreamMode(int mode) {
        prefs.edit().putInt(KEY_STREAM_MODE, mode).apply();
    }

    /**
     * 默认采用 wuwentao/bj-unicom-iptv 文档中的 192.168.2.1:8012
     */
    public String getUdpxyAddress() {
        String addr = prefs.getString(KEY_UDPXY_ADDR, "192.168.2.1:8012");
        return TextUtils.isEmpty(addr) ? "192.168.2.1:8012" : addr.trim();
    }

    public void setUdpxyAddress(String addr) {
        prefs.edit().putString(KEY_UDPXY_ADDR, addr != null ? addr.trim() : "").apply();
    }

    public int getPlayerType() {
        return prefs.getInt(KEY_PLAYER_TYPE, PLAYER_IJK);
    }

    public void setPlayerType(int playerType) {
        prefs.edit().putInt(KEY_PLAYER_TYPE, playerType).apply();
    }

    /**
     * 按频道记忆的 4K HEVC 硬解失败列表（IJK 无法硬解的频道直接走系统播放器内核）。
     * IJK 播放成功后由 MainActivity 自动移除，优先保证 IJK 最高帧率。
     */
    public Set<String> getHevcFallbackChannels() {
        return prefs.getStringSet(KEY_HEVC_FALLBACK_CHANNELS, Collections.emptySet());
    }

    public boolean isHevcFallbackChannel(String url) {
        return url != null && getHevcFallbackChannels().contains(url);
    }

    public void addHevcFallbackChannel(String url) {
        if (url == null) {
            return;
        }
        Set<String> updated = new HashSet<>(getHevcFallbackChannels());
        if (updated.add(url)) {
            prefs.edit().putStringSet(KEY_HEVC_FALLBACK_CHANNELS, updated).apply();
        }
    }

    public void removeHevcFallbackChannel(String url) {
        if (url == null) {
            return;
        }
        Set<String> updated = new HashSet<>(getHevcFallbackChannels());
        if (updated.remove(url)) {
            prefs.edit().putStringSet(KEY_HEVC_FALLBACK_CHANNELS, updated).apply();
        }
    }

    public boolean isMediaCodecEnabled() {
        return prefs.getBoolean(KEY_MEDIACODEC_ENABLED, true);
    }

    public void setMediaCodecEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MEDIACODEC_ENABLED, enabled).apply();
    }

    public boolean isMediaCodecHevcEnabled() {
        return prefs.getBoolean(KEY_MEDIACODEC_HEVC, true);
    }

    public void setMediaCodecHevcEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MEDIACODEC_HEVC, enabled).apply();
    }

    public boolean isOpenSlEsEnabled() {
        return prefs.getBoolean(KEY_OPENSLES_ENABLED, false);
    }

    public void setOpenSlEsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_OPENSLES_ENABLED, enabled).apply();
    }

    public int getRenderViewType() {
        return prefs.getInt(KEY_RENDER_VIEW_TYPE, 0); // 默认 SurfaceRenderView 对 4K/TV 性能最佳
    }

    public void setRenderViewType(int type) {
        prefs.edit().putInt(KEY_RENDER_VIEW_TYPE, type).apply();
    }

    public int getAspectRatio() {
        return prefs.getInt(KEY_ASPECT_RATIO, 0);
    }

    public void setAspectRatio(int aspectRatio) {
        prefs.edit().putInt(KEY_ASPECT_RATIO, aspectRatio).apply();
    }

    /**
     * 是否包含仓库全部 215 个频道（默认开启，完整覆盖项目中的所有频道）
     */
    public boolean isIncludeAllRepoChannels() {
        return prefs.getBoolean(KEY_INCLUDE_HISTORICAL, true);
    }

    public void setIncludeAllRepoChannels(boolean include) {
        prefs.edit().putBoolean(KEY_INCLUDE_HISTORICAL, include).apply();
    }

    public boolean isOnlyShowAvailable() {
        return prefs.getBoolean(KEY_ONLY_SHOW_AVAILABLE, false);
    }

    public void setOnlyShowAvailable(boolean onlyAvailable) {
        prefs.edit().putBoolean(KEY_ONLY_SHOW_AVAILABLE, onlyAvailable).apply();
    }

    public String getLastChannelUrl() {
        return prefs.getString(KEY_LAST_CHANNEL_URL, "rtp://239.3.1.129:8008");
    }

    public void setLastChannelUrl(String url) {
        prefs.edit().putString(KEY_LAST_CHANNEL_URL, url).apply();
    }

    public String getScanSubnet() {
        return prefs.getString(KEY_SCAN_SUBNET, "239.3.1");
    }

    public void setScanSubnet(String subnet) {
        prefs.edit().putString(KEY_SCAN_SUBNET, subnet != null ? subnet.trim() : "239.3.1").apply();
    }

    public int getScanStartHost() {
        return prefs.getInt(KEY_SCAN_START_HOST, 1);
    }

    public void setScanStartHost(int start) {
        prefs.edit().putInt(KEY_SCAN_START_HOST, Math.max(1, Math.min(254, start))).apply();
    }

    public int getScanEndHost() {
        return prefs.getInt(KEY_SCAN_END_HOST, 254);
    }

    public void setScanEndHost(int end) {
        prefs.edit().putInt(KEY_SCAN_END_HOST, Math.max(1, Math.min(254, end))).apply();
    }

    public int getScanTimeoutMs() {
        return prefs.getInt(KEY_SCAN_TIMEOUT_MS, 1800);
    }

    public void setScanTimeoutMs(int timeoutMs) {
        prefs.edit().putInt(KEY_SCAN_TIMEOUT_MS, Math.max(300, Math.min(15000, timeoutMs))).apply();
    }

    public java.util.Set<String> getFavoriteUrls() {
        return new java.util.HashSet<>(prefs.getStringSet(KEY_FAVORITES, new java.util.HashSet<>()));
    }

    public void toggleFavorite(String rawUrl) {
        java.util.Set<String> set = getFavoriteUrls();
        if (set.contains(rawUrl)) {
            set.remove(rawUrl);
        } else {
            set.add(rawUrl);
        }
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply();
    }
}
