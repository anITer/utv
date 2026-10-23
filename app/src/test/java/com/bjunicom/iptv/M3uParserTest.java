package com.bjunicom.iptv;

import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class M3uParserTest {

    @Test
    public void testParseBeijingUnicomM3uAndExport() {
        String sample = "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"\n" +
                "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" tvg-logo=\"http://epg.51zmt.top:8000/tb1/CCTV/CCTV1.png\" group-title=\"[高清]央视\",CCTV-1综合[1][高清]\n" +
                "rtp://239.3.1.129:8008\n" +
                "#EXTINF:-1 tvg-id=\"106\" tvg-name=\"CCTV4K\" tvg-logo=\"http://epg.51zmt.top:8000/tb1/CCTV/CCTV4k.png\" group-title=\"[高清]央视;[高清]北京IPTV;4K\",CCTV 4K[85][4K]\n" +
                "rtp://239.3.1.244:2000\n" +
                "#EXTINF:-1 tvg-id=\"2\" tvg-name=\"CCTV2\" group-title=\"[高清]央视\",CCTV-2财经[2][高清]\n" +
                "rtp://239.3.1.130:8008\n";

        List<IptvChannel> list = M3uParser.parseString(sample);
        assertEquals(3, list.size());

        IptvChannel cctv1 = list.get(0);
        assertEquals(1, cctv1.getChannelNumber());
        assertEquals("CCTV-1综合", cctv1.getDisplayName());
        assertEquals("高清", cctv1.getResolutionTag());
        assertEquals("239.3.1.129", cctv1.getMulticastIp());
        assertEquals(8008, cctv1.getMulticastPort());

        IptvChannel cctv4k = list.get(1);
        assertEquals(85, cctv4k.getChannelNumber());
        assertEquals("4K", cctv4k.getResolutionTag());
        assertTrue(cctv4k.getGroups().contains("4K"));

        IptvChannel cctv2Unprobed = list.get(2);
        assertEquals(null, cctv2Unprobed.getConnected());

        cctv1.setConnected(true);
        cctv1.setActualResolutionFlag("高清");
        cctv1.setVideoTrackInfo("#1 h264, 1920x1080,25fps");
        cctv1.setAudioTrackInfo("#1 mp2,2ch,48khz");

        cctv4k.setConnected(false);
        cctv4k.setActualResolutionFlag("错误");

        // When onlyConnected=true, both false and null (unprobed) channels must be excluded
        String modifiedM3u = M3uParser.toM3uContent(list, true, "192.168.2.1:8012");
        assertTrue(modifiedM3u.contains("http://192.168.2.1:8012/rtp/239.3.1.129:8008"));
        assertFalse(modifiedM3u.contains("239.3.1.244"));
        assertFalse(modifiedM3u.contains("239.3.1.130"));

        String csv = M3uParser.toResultsCsvContent(list);
        assertTrue(csv.contains("编号,频道名称,video track,audio track,screenshot_name,连接flag,分辨率flag"));
        assertTrue(csv.contains("#1 h264, 1920x1080,25fps"));
    }

    @Test
    public void testParseBeijingMobileAndTelecomM3u() {
        String sample = "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"\n" +
                "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" group-title=\"[高清]央视\",CCTV-1综合[高清]\n" +
                "http://otttv.bj.chinamobile.com/PLTV/88888888/224/3221226527/index.m3u8?from=3000&hms_devid=302\n" +
                "#EXTINF:-1 tvg-id=\"3090\" tvg-name=\"NewTV中国功夫\" group-title=\"[高清]NewTV\",NewTV中国功夫[高清]\n" +
                "http://111.13.42.8/PLTV/88888888/224/3221225922/1.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" group-title=\"[高清]央视\",CCTV-1综合[1][高清]\n" +
                "rtp://225.1.8.1:8008\n" +
                "#EXTINF:-1 tvg-id=\"\" tvg-name=\"\" group-title=\"[高清]北京IPTV;4K\",北京IPTV4K[超清]\n" +
                "rtp://225.1.8.120:8002\n";

        List<IptvChannel> list = M3uParser.parseString(sample);
        assertEquals(4, list.size());

        IptvChannel mobileCctv1 = list.get(0);
        assertEquals("CCTV-1综合", mobileCctv1.getDisplayName());
        assertEquals("高清", mobileCctv1.getResolutionTag());
        assertTrue(mobileCctv1.isHlsStream());
        assertFalse(mobileCctv1.isMulticastStream());

        IptvChannel mobileNewTv = list.get(1);
        assertEquals("NewTV中国功夫", mobileNewTv.getDisplayName());
        assertTrue(mobileNewTv.getGroups().contains("[高清]NewTV"));
        assertTrue(mobileNewTv.isHlsStream());

        IptvChannel telecomCctv1 = list.get(2);
        assertEquals("CCTV-1综合", telecomCctv1.getDisplayName());
        assertEquals("225.1.8.1", telecomCctv1.getMulticastIp());
        assertEquals(8008, telecomCctv1.getMulticastPort());
        assertTrue(telecomCctv1.isMulticastStream());

        IptvChannel telecom4k = list.get(3);
        assertEquals("北京IPTV4K", telecom4k.getDisplayName());
        assertEquals("4K", telecom4k.getResolutionTag());
        assertEquals("225.1.8.120", telecom4k.getMulticastIp());
        assertEquals(8002, telecom4k.getMulticastPort());
    }

    @Test
    public void testInspectAndValidateCustomM3uContent() {
        String customM3u = "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"\n" +
                "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" group-title=\"自定义央视\",CCTV-1综合[1][高清]\n" +
                "rtp://239.3.1.129:8008\n" +
                "#EXTINF:-1 tvg-id=\"85\" tvg-name=\"CCTV4K\" group-title=\"自定义4K;4K\",CCTV-4K超高清[85][4K]\n" +
                "rtp://239.3.1.244:2000\n" +
                "#EXTINF:-1 tvg-id=\"21\" tvg-name=\"北京卫视\" group-title=\"自定义卫视\",北京卫视[21][高清]\n" +
                "http://otttv.bj.chinamobile.com/PLTV/88888888/224/3221226527/index.m3u8\n" +
                "地方频道,#genre#\n" +
                "北京新闻[标清],rtp://225.1.8.21:8002\n";

        M3uParser.M3uInspectionReport report = M3uParser.inspectAndParse(customM3u, "my_custom.m3u");
        assertTrue(report.isValid());
        assertTrue(report.hasExtM3uHeader);
        assertEquals("my_custom.m3u", report.sourceName);
        assertEquals("http://epg.51zmt.top:8000/e.xml.gz", report.epgUrl);
        assertEquals(4, report.validChannelCount);
        assertEquals(0, report.invalidEntryCount);
        assertEquals(3, report.multicastCount);
        assertEquals(1, report.unicastCount);
        assertEquals(1, report.hlsCount);
        assertEquals(1, report.hd4kCount);
        assertEquals(2, report.hdCount);
        assertEquals(1, report.sdCount);
        assertTrue(report.multicastSubnets.contains("239.3.1"));
        assertTrue(report.multicastSubnets.contains("225.1.8"));
        assertTrue(report.groups.contains("自定义央视"));
        assertTrue(report.groups.contains("地方频道"));
        assertTrue(report.toSummaryText().contains("✅ 格式标准，内容校验通过"));
    }

    @Test
    public void testInspectInvalidAndMalformedM3uContent() {
        String malformedM3u = "#EXTINF:-1 group-title=\"测试\",孤立EXTINF无地址\n" +
                "#EXTINF:-1 group-title=\"测试\",非法组播IP[高清]\n" +
                "rtp://192.168.1.1:8008\n" +
                "#EXTINF:-1 group-title=\"测试\",非法端口[高清]\n" +
                "rtp://239.3.1.129:99999\n" +
                "#EXTINF:-1 group-title=\"测试\",正常频道1[1][高清]\n" +
                "rtp://239.3.1.129:8008\n" +
                "#EXTINF:-1 group-title=\"测试\",重复地址频道[2][高清]\n" +
                "rtp://239.3.1.129:8008\n";

        M3uParser.M3uInspectionReport report = M3uParser.inspectAndParse(malformedM3u, "test_warn.m3u");
        assertTrue(report.isValid());
        assertFalse(report.hasExtM3uHeader);
        assertEquals(2, report.validChannelCount);
        assertEquals(3, report.invalidEntryCount);
        assertEquals(1, report.duplicateUrlCount);
        assertFalse(report.warnings.isEmpty());

        M3uParser.M3uInspectionReport emptyReport = M3uParser.inspectAndParse("   \n  ", "empty.m3u");
        assertFalse(emptyReport.isValid());

        M3uParser.M3uInspectionReport binaryReport = M3uParser.inspectAndParse("PNG\0\0binary", "bad.m3u");
        assertFalse(binaryReport.isValid());
        assertTrue(binaryReport.binaryOrCorrupted);
    }
}
