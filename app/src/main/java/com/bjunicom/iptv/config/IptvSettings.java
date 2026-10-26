package com.bjunicom.iptv.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 播放器与北京 IPTV (联通 / 移动 / 电信) 网络转发配置管理
 */
public class IptvSettings {
    private static final String PREF_NAME = "bj_unicom_iptv_prefs";

    // IPTV 运营商节目源类型
    public static final int ISP_BEIJING_UNICOM = 0;         // 北京联通 IPTV (rtp://239.3.1.x:port)
    public static final int ISP_BEIJING_MOBILE = 1;         // 北京移动 IPTV (http://otttv.bj.chinamobile.com/.../index.m3u8)
    public static final int ISP_BEIJING_TELECOM = 2;        // 北京电信 IPTV (rtp://225.1.8.x:port)
    public static final int ISP_ALL_MERGED = 3;             // 北京三网聚合 (联通 + 移动 + 电信)
    public static final int ISP_CUSTOM_M3U = 4;             // 用户上传的自定义 M3U 播放列表

    // 流播放传输模式
    public static final int STREAM_MODE_UDPXY = 0;          // 路由器/软路由 udpxy 组播转单播 (如 http://192.168.2.1:8012/rtp/...)
    public static final int STREAM_MODE_LOCAL_PROXY = 1;    // 内置 Android 本地组播转 HTTP 代理 (127.0.0.1)
    public static final int STREAM_MODE_DIRECT_RTP = 2;     // ijkplayer 直接播放 rtp://239.3.1.x:port

    // 播放器内核模式
    public static final int PLAYER_IJK = 0;                 // IjkMediaPlayer (支持 MPEG-TS/RTP/HLS/HEVC 4K)
    public static final int PLAYER_ANDROID_MEDIA = 1;       // AndroidMediaPlayer
    public static final int PLAYER_UTV = 2;                 // UTVPlayer (基于 ExoPlayer + ProgressiveMediaSource + LoadControl)

    private static final String KEY_ISP_PROVIDER = "isp_provider";
    private static final String KEY_CUSTOM_M3U_NAME = "custom_m3u_name";
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
    private static final String KEY_SCAN_SUBNET = "scan_subnet";
    private static final String KEY_SCAN_START_HOST = "scan_start_host";
    private static final String KEY_SCAN_END_HOST = "scan_end_host";
    private static final String KEY_SCAN_TIMEOUT_MS = "scan_timeout_ms";
    private static final String KEY_FAVORITES = "favorite_urls";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_APP_CHANNEL = "app_channel";
    private static final String KEY_PENDING_REPORT_ID = "pending_report_id";
    private static final String KEY_LAST_SUCCESS_REPORT_ID = "last_success_report_id";
    private static final String KEY_USER_ID = "user_id";

    public static final String DEFAULT_APP_CHANNEL = "landing_page_a";

    private final SharedPreferences prefs;

    public IptvSettings(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getIspProvider() {
        return prefs.getInt(KEY_ISP_PROVIDER, ISP_BEIJING_UNICOM);
    }

    public void setIspProvider(int ispProvider) {
        if (ispProvider < ISP_BEIJING_UNICOM || ispProvider > ISP_CUSTOM_M3U) {
            ispProvider = ISP_BEIJING_UNICOM;
        }
        prefs.edit().putInt(KEY_ISP_PROVIDER, ispProvider).apply();
    }

    public String getCustomM3uName() {
        String name = prefs.getString(KEY_CUSTOM_M3U_NAME, "");
        return TextUtils.isEmpty(name) ? "custom.m3u" : name.trim();
    }

    public void setCustomM3uName(String fileName) {
        prefs.edit().putString(KEY_CUSTOM_M3U_NAME, fileName != null ? fileName.trim() : "custom.m3u").apply();
    }

    public String getIspDisplayName() {
        if (getIspProvider() == ISP_CUSTOM_M3U) {
            return "自定义 M3U (" + getCustomM3uName() + ")";
        }
        return getIspDisplayName(getIspProvider());
    }

    public static String getIspDisplayName(int ispProvider) {
        switch (ispProvider) {
            case ISP_BEIJING_MOBILE:
                return "北京移动 IPTV";
            case ISP_BEIJING_TELECOM:
                return "北京电信 IPTV";
            case ISP_ALL_MERGED:
                return "北京三网聚合";
            case ISP_CUSTOM_M3U:
                return "自定义 M3U";
            case ISP_BEIJING_UNICOM:
            default:
                return "北京联通 IPTV";
        }
    }

    public String getIspShortName() {
        return getIspShortName(getIspProvider());
    }

    public static String getIspShortName(int ispProvider) {
        switch (ispProvider) {
            case ISP_BEIJING_MOBILE:
                return "北京移动";
            case ISP_BEIJING_TELECOM:
                return "北京电信";
            case ISP_ALL_MERGED:
                return "三网聚合";
            case ISP_CUSTOM_M3U:
                return "自定义源";
            case ISP_BEIJING_UNICOM:
            default:
                return "北京联通";
        }
    }

    public static String getDefaultScanSubnetForIsp(int ispProvider) {
        if (ispProvider == ISP_BEIJING_TELECOM) {
            return "225.1.8";
        }
        return "239.3.1";
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
        int type = prefs.getInt(KEY_PLAYER_TYPE, PLAYER_IJK);
        if (type == PLAYER_ANDROID_MEDIA || type == PLAYER_UTV) {
            return type;
        }
        return PLAYER_IJK;
    }

    public void setPlayerType(int playerType) {
        prefs.edit().putInt(KEY_PLAYER_TYPE, playerType).apply();
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
        int isp = getIspProvider();
        if (isp == ISP_BEIJING_UNICOM) {
            return prefs.getString(KEY_LAST_CHANNEL_URL, "rtp://239.3.1.129:8008");
        }
        String key = KEY_LAST_CHANNEL_URL + "_" + isp;
        String defaultUrl = (isp == ISP_BEIJING_TELECOM)
                ? "rtp://225.1.8.1:8008"
                : "";
        return prefs.getString(key, defaultUrl);
    }

    public void setLastChannelUrl(String url) {
        int isp = getIspProvider();
        if (isp == ISP_BEIJING_UNICOM) {
            prefs.edit().putString(KEY_LAST_CHANNEL_URL, url).apply();
        } else {
            prefs.edit().putString(KEY_LAST_CHANNEL_URL + "_" + isp, url).apply();
        }
    }

    public String getScanSubnet() {
        String defaultSubnet = getDefaultScanSubnetForIsp(getIspProvider());
        String saved = prefs.getString(KEY_SCAN_SUBNET, "");
        return TextUtils.isEmpty(saved) ? defaultSubnet : saved;
    }

    public void setScanSubnet(String subnet) {
        String defaultSubnet = getDefaultScanSubnetForIsp(getIspProvider());
        prefs.edit().putString(KEY_SCAN_SUBNET, subnet != null ? subnet.trim() : defaultSubnet).apply();
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

    public String getPlayerTypeReportName() {
        return getPlayerTypeReportName(getPlayerType());
    }

    public static String getPlayerTypeReportName(int playerType) {
        switch (playerType) {
            case PLAYER_UTV:
                return "ExoPlayer";
            case PLAYER_ANDROID_MEDIA:
                return "MediaPlayer";
            case PLAYER_IJK:
            default:
                return "IjkPlayer";
        }
    }

    public String getCarrierReportName() {
        return getCarrierReportName(getIspProvider());
    }

    public static String getCarrierReportName(int ispProvider) {
        switch (ispProvider) {
            case ISP_BEIJING_TELECOM:
                return "China Telecom";
            case ISP_BEIJING_MOBILE:
                return "China Mobile";
            case ISP_BEIJING_UNICOM:
            default:
                return "China Unicom";
        }
    }

    public String getSavedDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, "");
    }

    public void setSavedDeviceId(String deviceId) {
        if (!TextUtils.isEmpty(deviceId)) {
            prefs.edit().putString(KEY_DEVICE_ID, deviceId.trim()).apply();
        }
    }

    public String getAppChannel() {
        String channel = prefs.getString(KEY_APP_CHANNEL, DEFAULT_APP_CHANNEL);
        return TextUtils.isEmpty(channel) ? DEFAULT_APP_CHANNEL : channel.trim();
    }

    public void setAppChannel(String channel) {
        prefs.edit().putString(KEY_APP_CHANNEL,
                TextUtils.isEmpty(channel) ? DEFAULT_APP_CHANNEL : channel.trim()).apply();
    }

    public String getUserId() {
        String uid = prefs.getString(KEY_USER_ID, null);
        return TextUtils.isEmpty(uid) ? null : uid.trim();
    }

    public void setUserId(String userId) {
        if (TextUtils.isEmpty(userId)) {
            prefs.edit().remove(KEY_USER_ID).apply();
        } else {
            prefs.edit().putString(KEY_USER_ID, userId.trim()).apply();
        }
    }

    public String getPendingReportId() {
        return prefs.getString(KEY_PENDING_REPORT_ID, "");
    }

    public void setPendingReportId(String reportId) {
        if (TextUtils.isEmpty(reportId)) {
            prefs.edit().remove(KEY_PENDING_REPORT_ID).apply();
        } else {
            prefs.edit().putString(KEY_PENDING_REPORT_ID, reportId.trim()).apply();
        }
    }

    public String getLastSuccessReportId() {
        return prefs.getString(KEY_LAST_SUCCESS_REPORT_ID, "");
    }

    public void setLastSuccessReportId(String reportId) {
        prefs.edit().putString(KEY_LAST_SUCCESS_REPORT_ID, reportId != null ? reportId.trim() : "").apply();
    }
}
