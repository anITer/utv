package com.bjunicom.iptv.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 北京联通 IPTV 频道实体模型
 * 对应 bj-unicom-iptv.m3u 与 check_iptv_m3u.sh 检测结果字段
 */
public class IptvChannel implements Serializable {
    private static final long serialVersionUID = 1L;

    private int index;                      // 列表序号 (counter)
    private int channelNumber;              // 遥控器台号 (如 CCTV-1综合[1][高清] 中的 1)
    private String rawExtInfLine;           // 原始 #EXTINF 行
    private String rawTitle;                // 原始标题 (如 CCTV-1综合[1][高清])
    private String displayName;             // 净化后的显示名称 (如 CCTV-1综合)
    private String tvgId;                   // EPG tvg-id
    private String tvgName;                 // EPG tvg-name
    private String tvgLogo;                 // 台标 URL
    private String groupTitleRaw;           // 原始分组字符串 (如 "[高清]北京;[高清]卫视;4K")
    private List<String> groups;            // 拆分后的分组列表
    private String rawUrl;                  // 原始组播地址 (如 rtp://239.3.1.129:8008)
    private String multicastIp;             // 组播 IP (如 239.3.1.129)
    private int multicastPort;              // 组播端口 (如 8008)
    private String resolutionTag;           // 分辨率标识: "4K", "高清", "标清", "其它", "错误"

    // check_iptv_m3u.sh 探测结果字段
    private String videoTrackInfo;          // 如 "#1 h264, 1920x1080,25fps"
    private String audioTrackInfo;          // 如 "#1 mp2,2ch,48khz"
    private String screenshotPath;          // 截图路径
    private Boolean connected;              // 连接flag: null(未检测), true(可用), false(不可用)
    private String actualResolutionFlag;    // 实测分辨率flag: "高清", "4K", "标清", "其它", "错误"
    private boolean resolutionMismatch;     // 频道名分辨率与实测分辨率是否不一致
    private long latencyMs;                 // 探测响应耗时 (ms)
    private boolean discoveredByScan;       // 是否由网段动态扫描新发现的组播地址
    private boolean favorite;               // 是否收藏
    private String ispName;                 // 所属运营商标识 (如 "北京联通", "北京移动", "北京电信")

    public IptvChannel() {
        this.groups = new ArrayList<>();
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getChannelNumber() {
        return channelNumber;
    }

    public void setChannelNumber(int channelNumber) {
        this.channelNumber = channelNumber;
    }

    public String getRawExtInfLine() {
        return rawExtInfLine;
    }

    public void setRawExtInfLine(String rawExtInfLine) {
        this.rawExtInfLine = rawExtInfLine;
    }

    public String getRawTitle() {
        return rawTitle;
    }

    public void setRawTitle(String rawTitle) {
        this.rawTitle = rawTitle;
    }

    public String getDisplayName() {
        return !isEmpty(displayName) ? displayName : rawTitle;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTvgId() {
        return tvgId;
    }

    public void setTvgId(String tvgId) {
        this.tvgId = tvgId;
    }

    public String getTvgName() {
        return tvgName;
    }

    public void setTvgName(String tvgName) {
        this.tvgName = tvgName;
    }

    public String getTvgLogo() {
        return tvgLogo;
    }

    public void setTvgLogo(String tvgLogo) {
        this.tvgLogo = tvgLogo;
    }

    public String getGroupTitleRaw() {
        return groupTitleRaw;
    }

    public void setGroupTitleRaw(String groupTitleRaw) {
        this.groupTitleRaw = groupTitleRaw;
    }

    public List<String> getGroups() {
        return groups != null ? groups : Collections.emptyList();
    }

    public void setGroups(List<String> groups) {
        this.groups = groups;
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public void setRawUrl(String rawUrl) {
        this.rawUrl = rawUrl;
    }

    public String getMulticastIp() {
        return multicastIp;
    }

    public void setMulticastIp(String multicastIp) {
        this.multicastIp = multicastIp;
    }

    public int getMulticastPort() {
        return multicastPort;
    }

    public void setMulticastPort(int multicastPort) {
        this.multicastPort = multicastPort;
    }

    public String getResolutionTag() {
        if (!isEmpty(actualResolutionFlag) && !"错误".equals(actualResolutionFlag)) {
            return actualResolutionFlag;
        }
        return !isEmpty(resolutionTag) ? resolutionTag : "标清";
    }

    public String getNameResolutionTag() {
        return !isEmpty(resolutionTag) ? resolutionTag : "标清";
    }

    public void setResolutionTag(String resolutionTag) {
        this.resolutionTag = resolutionTag;
    }

    public String getVideoTrackInfo() {
        return videoTrackInfo;
    }

    public void setVideoTrackInfo(String videoTrackInfo) {
        this.videoTrackInfo = videoTrackInfo;
    }

    public String getAudioTrackInfo() {
        return audioTrackInfo;
    }

    public void setAudioTrackInfo(String audioTrackInfo) {
        this.audioTrackInfo = audioTrackInfo;
    }

    public String getScreenshotPath() {
        return screenshotPath;
    }

    public void setScreenshotPath(String screenshotPath) {
        this.screenshotPath = screenshotPath;
    }

    public Boolean getConnected() {
        return connected;
    }

    public void setConnected(Boolean connected) {
        this.connected = connected;
    }

    public String getActualResolutionFlag() {
        return actualResolutionFlag;
    }

    public void setActualResolutionFlag(String actualResolutionFlag) {
        this.actualResolutionFlag = actualResolutionFlag;
    }

    public boolean isResolutionMismatch() {
        return resolutionMismatch;
    }

    public void setResolutionMismatch(boolean resolutionMismatch) {
        this.resolutionMismatch = resolutionMismatch;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public boolean isDiscoveredByScan() {
        return discoveredByScan;
    }

    public void setDiscoveredByScan(boolean discoveredByScan) {
        this.discoveredByScan = discoveredByScan;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public String getIspName() {
        return ispName;
    }

    public void setIspName(String ispName) {
        this.ispName = ispName;
    }

    public boolean isMulticastStream() {
        if (!isEmpty(multicastIp) && multicastPort > 0) {
            return true;
        }
        return rawUrl != null && (rawUrl.startsWith("rtp://") || rawUrl.startsWith("udp://"));
    }

    public boolean isHlsStream() {
        if (isEmpty(rawUrl)) {
            return false;
        }
        String lower = rawUrl.toLowerCase();
        return lower.contains(".m3u8");
    }

    public String getMulticastKey() {
        if (!isEmpty(multicastIp) && multicastPort > 0) {
            return multicastIp + ":" + multicastPort;
        }
        return rawUrl != null ? rawUrl : "";
    }

    public String getChannelNumberDisplay() {
        int num = channelNumber > 0 ? channelNumber : index;
        return String.format("%03d", num);
    }

    /**
     * 按 check_iptv_m3u.sh 规则生成更新分辨率标签后的频道标题
     */
    public String buildUpdatedChannelTitle() {
        String base = rawTitle != null ? rawTitle : displayName;
        if (base == null) {
            base = "Unknown_Channel_" + index;
        }
        String res = getResolutionTag();
        String cleaned = base.replace("[高清]", "")
                .replace("[4K]", "")
                .replace("[超清]", "")
                .replace("[标清]", "")
                .replace("[其它]", "")
                .trim();
        if (!"错误".equals(res) && !isEmpty(res)) {
            return cleaned + "[" + res + "]";
        }
        return base;
    }

    /**
     * 生成 M3U 的 #EXTINF 行
     */
    public String toExtInfLine() {
        String updatedTitle = buildUpdatedChannelTitle();
        if (!isEmpty(rawExtInfLine) && rawExtInfLine.contains(",")) {
            int commaIdx = rawExtInfLine.indexOf(',');
            return rawExtInfLine.substring(0, commaIdx + 1) + updatedTitle;
        }
        String groupStr = !isEmpty(groupTitleRaw) ? groupTitleRaw : "北京IPTV";
        return String.format("#EXTINF:-1 tvg-id=\"%s\" tvg-name=\"%s\" tvg-logo=\"%s\" group-title=\"%s\",%s",
                tvgId != null ? tvgId : "",
                tvgName != null ? tvgName : "",
                tvgLogo != null ? tvgLogo : "",
                groupStr,
                updatedTitle);
    }

    /**
     * 创建当前频道实体的深拷贝，避免扫描过程中直接原地修改仓库中的默认频道对象
     */
    public IptvChannel copy() {
        IptvChannel c = new IptvChannel();
        c.index = this.index;
        c.channelNumber = this.channelNumber;
        c.rawExtInfLine = this.rawExtInfLine;
        c.rawTitle = this.rawTitle;
        c.displayName = this.displayName;
        c.tvgId = this.tvgId;
        c.tvgName = this.tvgName;
        c.tvgLogo = this.tvgLogo;
        c.groupTitleRaw = this.groupTitleRaw;
        c.groups = this.groups != null ? new ArrayList<>(this.groups) : new ArrayList<>();
        c.rawUrl = this.rawUrl;
        c.multicastIp = this.multicastIp;
        c.multicastPort = this.multicastPort;
        c.resolutionTag = this.resolutionTag;
        c.videoTrackInfo = this.videoTrackInfo;
        c.audioTrackInfo = this.audioTrackInfo;
        c.screenshotPath = this.screenshotPath;
        c.connected = this.connected;
        c.actualResolutionFlag = this.actualResolutionFlag;
        c.resolutionMismatch = this.resolutionMismatch;
        c.latencyMs = this.latencyMs;
        c.discoveredByScan = this.discoveredByScan;
        c.favorite = this.favorite;
        c.ispName = this.ispName;
        return c;
    }

    private static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }
}
