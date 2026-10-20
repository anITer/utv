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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 北京联通 IPTV M3U 播放列表解析与生成器
 * 完整支持 https://github.com/wuwentao/bj-unicom-iptv/ 的 bj-unicom-iptv.m3u 格式与 check_iptv_m3u.sh 规范
 */
public final class M3uParser {

    public static final String DEFAULT_EPG_HEADER = "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"";

    private static final Pattern ATTR_TVG_ID = Pattern.compile("tvg-id=\"([^\"]*)\"");
    private static final Pattern ATTR_TVG_NAME = Pattern.compile("tvg-name=\"([^\"]*)\"");
    private static final Pattern ATTR_TVG_LOGO = Pattern.compile("tvg-logo=\"([^\"]*)\"");
    private static final Pattern ATTR_GROUP_TITLE = Pattern.compile("group-title=\"([^\"]*)\"");
    private static final Pattern ATTR_GROUP_TITLE_UNCLOSED = Pattern.compile("group-title=\"([^\",]*)");
    private static final Pattern BRACKET_NUMBER = Pattern.compile("\\[(\\d{1,4})\\]");
    private static final Pattern MULTICAST_ADDR = Pattern.compile("(2(?:2[4-9]|3\\d)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{1,5})");

    private M3uParser() {
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
            if (onlyConnected && Boolean.FALSE.equals(ch.getConnected())) {
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
