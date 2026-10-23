package com.bjunicom.iptv.parser;

import com.bjunicom.iptv.model.IptvChannel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 北京联通 IPTV M3U 播放列表解析、内容校验与生成器
 * 完整支持 https://github.com/wuwentao/bj-unicom-iptv/ 的 bj-unicom-iptv.m3u 格式、check_iptv_m3u.sh 规范
 * 以及用户自定义上传的 M3U / 扩展播放列表解析与内容合法性检测
 */
public final class M3uParser {

    public static final String DEFAULT_EPG_HEADER = "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"";

    private static final Pattern ATTR_TVG_ID = Pattern.compile("tvg-id=\"([^\"]*)\"");
    private static final Pattern ATTR_TVG_NAME = Pattern.compile("tvg-name=\"([^\"]*)\"");
    private static final Pattern ATTR_TVG_LOGO = Pattern.compile("tvg-logo=\"([^\"]*)\"");
    private static final Pattern ATTR_GROUP_TITLE = Pattern.compile("group-title=\"([^\"]*)\"");
    private static final Pattern ATTR_GROUP_TITLE_UNCLOSED = Pattern.compile("group-title=\"([^\",]*)");
    private static final Pattern ATTR_EPG_URL = Pattern.compile("(?:x-tvg-url|url-tvg)=\"([^\"]*)\"");
    private static final Pattern BRACKET_NUMBER = Pattern.compile("\\[(\\d{1,4})\\]");
    private static final Pattern MULTICAST_ADDR = Pattern.compile("(2(?:2[4-9]|3\\d)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{1,5})");
    private static final Pattern STREAM_SCHEME_PATTERN = Pattern.compile("^(?i)(rtp|udp|http|https|rtsp|rtmp|mms|igmp)://([^\\s/]+)(/.*)?$");

    /**
     * 自定义 M3U 文件内容解析与检测报告
     */
    public static class M3uInspectionReport {
        public String sourceName = "custom.m3u";
        public boolean hasExtM3uHeader = false;
        public String epgUrl = "";
        public boolean binaryOrCorrupted = false;
        public int totalLines = 0;
        public int extInfCount = 0;
        public int validChannelCount = 0;
        public int invalidEntryCount = 0;
        public int duplicateUrlCount = 0;
        public int multicastCount = 0;
        public int unicastCount = 0;
        public int hlsCount = 0;
        public int hd4kCount = 0;
        public int hdCount = 0;
        public int sdCount = 0;
        public int probedCount = 0;
        public int reachableCount = 0;
        public int unreachableCount = 0;
        public final List<IptvChannel> channels = new ArrayList<>();
        public final Set<String> groups = new LinkedHashSet<>();
        public final Set<String> multicastSubnets = new LinkedHashSet<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean isValid() {
            return !binaryOrCorrupted && validChannelCount > 0;
        }

        public String toSummaryText() {
            StringBuilder sb = new StringBuilder();
            sb.append("【文件解析与内容检测报告】\n");
            sb.append("• 文件名称: ").append(isEmpty(sourceName) ? "自定义内容" : sourceName).append("\n");
            sb.append("• 检测结论: ").append(isValid()
                    ? (warnings.isEmpty() ? "✅ 格式标准，内容校验通过" : "⚠️ 解析成功 (含 " + warnings.size() + " 项格式提示)")
                    : "❌ 内容校验未通过 (无有效频道或格式损坏)").append("\n");
            sb.append("• 文件头部: ").append(hasExtM3uHeader ? "标准 #EXTM3U" : "非标准 (缺少 #EXTM3U 头)");
            if (!isEmpty(epgUrl)) {
                sb.append(" | EPG: ").append(epgUrl);
            }
            sb.append("\n");
            sb.append(String.format(Locale.US, "• 条目统计: 有效频道 %d 个 | #EXTINF %d 条 | 异常/跳过 %d 条 | 重复地址 %d 条\n",
                    validChannelCount, extInfCount, invalidEntryCount, duplicateUrlCount));
            sb.append(String.format(Locale.US, "• 协议分布: 组播流(RTP/UDP) %d 个 | 单播流(HTTP/RTMP/RTSP) %d 个 (含 HLS .m3u8 %d 个)\n",
                    multicastCount, unicastCount, hlsCount));
            sb.append(String.format(Locale.US, "• 画质分布: 4K超清 %d 个 | 高清 %d 个 | 标清 %d 个\n",
                    hd4kCount, hdCount, sdCount));
            if (!multicastSubnets.isEmpty()) {
                sb.append("• 组播网段: ").append( joinSet(multicastSubnets, 6)).append("\n");
            }
            if (!groups.isEmpty()) {
                sb.append("• 频道分组 (").append(groups.size()).append("个): ").append(joinSet(groups, 8)).append("\n");
            }
            if (probedCount > 0) {
                sb.append(String.format(Locale.US, "• 流连通性实测: 已测 %d / %d | ● 可用 %d 个 | ○ 不可用 %d 个\n",
                        probedCount, validChannelCount, reachableCount, unreachableCount));
            }
            if (!warnings.isEmpty()) {
                sb.append("• 检测详情与告警:\n");
                int maxWarn = Math.min(warnings.size(), 6);
                for (int i = 0; i < maxWarn; i++) {
                    sb.append("   - ").append(warnings.get(i)).append("\n");
                }
                if (warnings.size() > maxWarn) {
                    sb.append("   - ... 其余 ").append(warnings.size() - maxWarn).append(" 条告警已省略\n");
                }
            }
            return sb.toString().trim();
        }

        private String joinSet(Set<String> set, int limit) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String item : set) {
                if (count > 0) {
                    sb.append(", ");
                }
                if (count >= limit) {
                    sb.append("...");
                    break;
                }
                sb.append(item);
                count++;
            }
            return sb.toString();
        }
    }

    private M3uParser() {
    }

    public static M3uInspectionReport inspectAndParse(InputStream inputStream, String sourceName) throws IOException {
        if (inputStream == null) {
            return inspectAndParse("", sourceName);
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        char[] buf = new char[4096];
        int read;
        while ((read = reader.read(buf)) != -1) {
            sb.append(buf, 0, read);
        }
        return inspectAndParse(sb.toString(), sourceName);
    }

    public static M3uInspectionReport inspectAndParse(String m3uContent, String sourceName) {
        M3uInspectionReport report = new M3uInspectionReport();
        if (!isEmpty(sourceName)) {
            report.sourceName = sourceName.trim();
        }
        if (isEmpty(m3uContent) || m3uContent.trim().isEmpty()) {
            report.warnings.add("文件内容为空，未检测到任何频道数据");
            return report;
        }
        if (m3uContent.indexOf('\0') >= 0) {
            report.binaryOrCorrupted = true;
            report.warnings.add("检测到二进制控制字符 (\\0)，请确认上传的是 UTF-8 编码的 M3U 文本文件");
            return report;
        }

        Set<String> seenUrls = new LinkedHashSet<>();
        String currentExtInf = null;
        int currentExtInfLineNum = 0;
        String currentGenreGroup = null;
        int counter = 1;
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(m3uContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.replace("\uFEFF", "").trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                report.totalLines++;

                String upper = trimmed.toUpperCase(Locale.ROOT);
                if (upper.startsWith("#EXTM3U")) {
                    report.hasExtM3uHeader = true;
                    String epg = extractAttr(ATTR_EPG_URL, trimmed);
                    if (!isEmpty(epg)) {
                        report.epgUrl = epg;
                    }
                    continue;
                }

                if (upper.startsWith("#EXTINF")) {
                    if (currentExtInf != null) {
                        report.invalidEntryCount++;
                        report.warnings.add("第 " + currentExtInfLineNum + " 行: #EXTINF 缺少对应的流媒体播放地址");
                    }
                    report.extInfCount++;
                    currentExtInf = trimmed;
                    currentExtInfLineNum = lineNumber;
                    continue;
                }

                if (upper.startsWith("#EXTGRP:")) {
                    String grp = trimmed.substring("#EXTGRP:".length()).trim();
                    if (!grp.isEmpty()) {
                        currentGenreGroup = grp;
                    }
                    continue;
                }

                if (trimmed.startsWith("#")) {
                    continue;
                }

                // 非注释行：处理流媒体地址或 "频道名,URL" 格式
                if (currentExtInf != null) {
                    String validationErr = validateStreamUrl(trimmed);
                    if (validationErr == null) {
                        IptvChannel ch = buildChannel(counter, currentExtInf, trimmed);
                        if (ch != null) {
                            if (currentGenreGroup != null && (ch.getGroups().isEmpty()
                                    || (ch.getGroups().size() == 1 && "北京IPTV".equals(ch.getGroups().get(0))))) {
                                List<String> gList = new ArrayList<>();
                                gList.add(currentGenreGroup);
                                ch.setGroups(gList);
                            }
                            recordValidChannel(report, ch, seenUrls, lineNumber);
                            counter++;
                        } else {
                            report.invalidEntryCount++;
                        }
                    } else {
                        report.invalidEntryCount++;
                        report.warnings.add("第 " + lineNumber + " 行: " + validationErr + " (" + shortenText(trimmed, 48) + ")");
                    }
                    currentExtInf = null;
                } else if (trimmed.contains(",")) {
                    int commaIdx = trimmed.indexOf(',');
                    String leftTitle = trimmed.substring(0, commaIdx).trim();
                    String rightUrl = trimmed.substring(commaIdx + 1).trim();
                    if ("#genre#".equalsIgnoreCase(rightUrl)) {
                        currentGenreGroup = leftTitle.isEmpty() ? "自定义分组" : leftTitle;
                    } else {
                        String validationErr = validateStreamUrl(rightUrl);
                        if (validationErr == null && !leftTitle.isEmpty()) {
                            String grp = !isEmpty(currentGenreGroup) ? currentGenreGroup : "自定义频道";
                            String syntheticExtInf = "#EXTINF:-1 group-title=\"" + grp + "\"," + leftTitle;
                            IptvChannel ch = buildChannel(counter, syntheticExtInf, rightUrl);
                            if (ch != null) {
                                recordValidChannel(report, ch, seenUrls, lineNumber);
                                counter++;
                            }
                        } else {
                            report.invalidEntryCount++;
                            report.warnings.add("第 " + lineNumber + " 行: 无法解析的频道条目 (" + shortenText(trimmed, 48) + ")");
                        }
                    }
                } else {
                    String validationErr = validateStreamUrl(trimmed);
                    if (validationErr == null) {
                        String grp = !isEmpty(currentGenreGroup) ? currentGenreGroup : "自定义频道";
                        String syntheticExtInf = "#EXTINF:-1 group-title=\"" + grp + "\",自定义频道_" + counter;
                        IptvChannel ch = buildChannel(counter, syntheticExtInf, trimmed);
                        if (ch != null) {
                            report.warnings.add("第 " + lineNumber + " 行: 裸流地址缺少 #EXTINF 频道名称，已自动命名为 " + ch.getDisplayName());
                            recordValidChannel(report, ch, seenUrls, lineNumber);
                            counter++;
                        }
                    } else {
                        report.invalidEntryCount++;
                        report.warnings.add("第 " + lineNumber + " 行: 无法识别的内容或非法地址 (" + shortenText(trimmed, 48) + ")");
                    }
                }
            }
        } catch (IOException ignored) {
        }

        if (currentExtInf != null) {
            report.invalidEntryCount++;
            report.warnings.add("第 " + currentExtInfLineNum + " 行: 文件末尾 #EXTINF 缺少对应的播放地址");
        }
        if (!report.hasExtM3uHeader && report.validChannelCount > 0) {
            report.warnings.add("文件缺少标准 #EXTM3U 头部声明（已通过兼容模式解析）");
        }
        if (report.validChannelCount == 0 && !report.binaryOrCorrupted) {
            report.warnings.add("未检测到任何有效的 IPTV 频道，请检查文件是否为合法的 M3U 播放列表");
        }

        return report;
    }

    private static void recordValidChannel(M3uInspectionReport report,
                                           IptvChannel ch,
                                           Set<String> seenUrls,
                                           int lineNumber) {
        String url = ch.getRawUrl();
        if (seenUrls.contains(url)) {
            report.duplicateUrlCount++;
            report.warnings.add("第 " + lineNumber + " 行: 检测到重复的流地址 " + shortenText(url, 42));
        } else {
            seenUrls.add(url);
        }

        report.channels.add(ch);
        report.validChannelCount++;

        if (ch.isMulticastStream()) {
            report.multicastCount++;
            String mip = ch.getMulticastIp();
            if (!isEmpty(mip)) {
                int lastDot = mip.lastIndexOf('.');
                if (lastDot > 0) {
                    report.multicastSubnets.add(mip.substring(0, lastDot));
                }
            }
        } else {
            report.unicastCount++;
            if (ch.isHlsStream()) {
                report.hlsCount++;
            }
        }

        String res = ch.getResolutionTag();
        if ("4K".equals(res)) {
            report.hd4kCount++;
        } else if ("高清".equals(res)) {
            report.hdCount++;
        } else {
            report.sdCount++;
        }

        for (String g : ch.getGroups()) {
            if (!isEmpty(g)) {
                report.groups.add(g);
            }
        }
    }

    /**
     * 校验流媒体地址合法性，合法返回 null，非法返回具体原因
     */
    public static String validateStreamUrl(String url) {
        if (isEmpty(url)) {
            return "播放地址为空";
        }
        String trimmed = url.trim();
        Matcher matcher = STREAM_SCHEME_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return "不支持的流媒体协议或地址格式错误";
        }
        String scheme = matcher.group(1).toLowerCase(Locale.ROOT);
        String authority = matcher.group(2);
        if (authority == null || authority.isEmpty()) {
            return "流地址缺少主机或组播地址";
        }

        if ("rtp".equals(scheme) || "udp".equals(scheme) || "igmp".equals(scheme)) {
            String hostPort = authority.startsWith("@") ? authority.substring(1) : authority;
            int colonIdx = hostPort.lastIndexOf(':');
            if (colonIdx <= 0 || colonIdx >= hostPort.length() - 1) {
                return "组播地址缺少端口号 (格式应为 rtp://IP:Port)";
            }
            String host = hostPort.substring(0, colonIdx);
            String portStr = hostPort.substring(colonIdx + 1);
            try {
                int port = Integer.parseInt(portStr);
                if (port < 1 || port > 65535) {
                    return "组播端口号超出范围 (1-65535): " + port;
                }
            } catch (NumberFormatException e) {
                return "组播端口号非法: " + portStr;
            }
            String[] octets = host.split("\\.");
            if (octets.length != 4) {
                return "组播 IPv4 地址格式错误: " + host;
            }
            try {
                int first = Integer.parseInt(octets[0]);
                for (String octet : octets) {
                    int val = Integer.parseInt(octet);
                    if (val < 0 || val > 255) {
                        return "组播 IP 地址段超出范围 (0-255): " + host;
                    }
                }
                if (first < 224 || first > 239) {
                    return "非标准多播地址段 (应为 224.0.0.0~239.255.255.255): " + host;
                }
            } catch (NumberFormatException e) {
                return "组播 IP 地址格式错误: " + host;
            }
        }
        return null;
    }

    private static String shortenText(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }

    public static List<IptvChannel> parse(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        return parseReader(reader);
    }

    public static List<IptvChannel> parseString(String m3uContent) {
        if (isEmpty(m3uContent)) {
            return new ArrayList<>();
        }
        try {
            return parseReader(new BufferedReader(new StringReader(m3uContent)));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<IptvChannel> parseReader(BufferedReader reader) throws IOException {
        List<IptvChannel> channels = new ArrayList<>();
        String line;
        String currentExtInf = null;
        int counter = 1;

        while ((line = reader.readLine()) != null) {
            // 与 check_iptv_m3u.sh 一致，去除首尾空白及 BOM
            String trimmed = line.replace("\uFEFF", "").trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#EXTINF")) {
                currentExtInf = trimmed;
            } else if (!trimmed.startsWith("#") && currentExtInf != null) {
                IptvChannel channel = buildChannel(counter, currentExtInf, trimmed);
                if (channel != null) {
                    channels.add(channel);
                    counter++;
                }
                currentExtInf = null;
            }
        }
        return channels;
    }

    public static IptvChannel buildChannel(int index, String extInfLine, String urlLine) {
        if (isEmpty(urlLine)) {
            return null;
        }
        IptvChannel channel = new IptvChannel();
        channel.setIndex(index);
        channel.setRawExtInfLine(extInfLine);
        channel.setRawUrl(urlLine.trim());

        // 提取属性
        channel.setTvgId(extractAttr(ATTR_TVG_ID, extInfLine));
        channel.setTvgName(extractAttr(ATTR_TVG_NAME, extInfLine));
        channel.setTvgLogo(extractAttr(ATTR_TVG_LOGO, extInfLine));
        String groupRaw = extractAttr(ATTR_GROUP_TITLE, extInfLine);
        if (isEmpty(groupRaw)) {
            groupRaw = extractAttr(ATTR_GROUP_TITLE_UNCLOSED, extInfLine);
        }
        channel.setGroupTitleRaw(groupRaw);

        // 提取逗号后的频道名
        String rawTitle;
        int commaIndex = extInfLine.lastIndexOf(',');
        if (commaIndex >= 0 && commaIndex < extInfLine.length() - 1) {
            rawTitle = extInfLine.substring(commaIndex + 1).trim();
        } else {
            rawTitle = "Unknown_Channel_" + index;
        }
        channel.setRawTitle(rawTitle);

        // 提取分辨率标识: [4K], [超清], [高清], [标清]
        String resTag = "标清";
        if (rawTitle.contains("[4K]")
                || rawTitle.contains("[超清]")
                || rawTitle.toUpperCase().contains("4K")
                || (groupRaw != null && groupRaw.contains("4K"))) {
            resTag = "4K";
        } else if (rawTitle.contains("[高清]")
                || rawTitle.endsWith("HD")
                || (groupRaw != null && groupRaw.contains("[高清]"))) {
            resTag = "高清";
        } else if (rawTitle.contains("[标清]") || (groupRaw != null && groupRaw.contains("[标清]"))) {
            resTag = "标清";
        }
        channel.setResolutionTag(resTag);

        // 提取遥控器频道号，如 CCTV-1综合[1][高清] -> 1
        Matcher numMatcher = BRACKET_NUMBER.matcher(rawTitle);
        if (numMatcher.find()) {
            try {
                channel.setChannelNumber(Integer.parseInt(numMatcher.group(1)));
            } catch (NumberFormatException ignored) {
                channel.setChannelNumber(index);
            }
        } else {
            channel.setChannelNumber(index);
        }

        // 生成干净的频道展示名 (移除 [1], [高清], [4K], [超清], [标清] 等后缀)
        String cleanName = rawTitle
                .replaceAll("\\[\\d{1,4}\\]", "")
                .replace("[高清]", "")
                .replace("[4K]", "")
                .replace("[超清]", "")
                .replace("[标清]", "")
                .replace("[其它]", "")
                .trim();
        channel.setDisplayName(cleanName.isEmpty() ? rawTitle : cleanName);

        // 解析分组列表 (支持分号分隔，如 "[高清]北京;[高清]卫视;4K")
        Set<String> groupSet = new LinkedHashSet<>();
        if (!isEmpty(groupRaw)) {
            String[] parts = groupRaw.split("[;,]");
            for (String p : parts) {
                String gp = p.trim();
                if (!gp.isEmpty()) {
                    groupSet.add(gp);
                }
            }
        }
        if ("4K".equals(resTag)) {
            groupSet.add("4K");
        }
        if (groupSet.isEmpty()) {
            groupSet.add("北京IPTV");
        }
        channel.setGroups(new ArrayList<>(groupSet));

        // 解析组播 IP 与端口 (如 rtp://239.3.1.129:8008 或 http://192.168.2.1:8012/rtp/239.3.1.129:8008)
        Matcher mcastMatcher = MULTICAST_ADDR.matcher(urlLine);
        if (mcastMatcher.find()) {
            channel.setMulticastIp(mcastMatcher.group(1));
            try {
                channel.setMulticastPort(Integer.parseInt(mcastMatcher.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }

        return channel;
    }

    /**
     * 导出符合 check_iptv_m3u.sh 的 modified_iptv.m3u 内容
     */
    public static String toM3uContent(List<IptvChannel> channels, boolean onlyConnected, String udpxyServer) {
        StringBuilder sb = new StringBuilder();
        sb.append(DEFAULT_EPG_HEADER).append("\n");
        for (IptvChannel ch : channels) {
            if (onlyConnected && !Boolean.TRUE.equals(ch.getConnected())) {
                continue;
            }
            sb.append(ch.toExtInfLine()).append("\n");
            String url = ch.getRawUrl();
            if (!isEmpty(udpxyServer) && !isEmpty(ch.getMulticastIp()) && ch.getMulticastPort() > 0) {
                String cleanServer = udpxyServer.replace("http://", "").replace("https://", "").replaceAll("/+$", "");
                url = "http://" + cleanServer + "/rtp/" + ch.getMulticastIp() + ":" + ch.getMulticastPort();
            }
            sb.append(url).append("\n");
        }
        return sb.toString();
    }

    /**
     * 导出符合 check_iptv_m3u.sh 的 results.csv 内容 (带 UTF-8 BOM)
     */
    public static String toResultsCsvContent(List<IptvChannel> channels) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("编号,频道名称,video track,audio track,screenshot_name,连接flag,分辨率flag\n");
        int i = 1;
        for (IptvChannel ch : channels) {
            String name = ch.getRawTitle() != null ? ch.getRawTitle().replace("\"", "\"\"") : ch.getDisplayName();
            String vTrack = ch.getVideoTrackInfo() != null ? ch.getVideoTrackInfo() : "";
            String aTrack = ch.getAudioTrackInfo() != null ? ch.getAudioTrackInfo() : "";
            String screenshotName = i + "_" + name + ".png";
            boolean conn = Boolean.TRUE.equals(ch.getConnected());
            String resFlag = ch.getActualResolutionFlag() != null
                    ? ch.getActualResolutionFlag()
                    : (conn ? ch.getResolutionTag() : "错误");
            sb.append(i).append(',')
                    .append('"').append(name).append("\",")
                    .append('"').append(vTrack).append("\",")
                    .append('"').append(aTrack).append("\",")
                    .append('"').append(screenshotName).append("\",")
                    .append(conn ? "true" : "false").append(',')
                    .append(resFlag).append('\n');
            i++;
        }
        return sb.toString();
    }

    private static String extractAttr(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }
}
