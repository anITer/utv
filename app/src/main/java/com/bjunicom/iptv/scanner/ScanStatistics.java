package com.bjunicom.iptv.scanner;

import java.io.File;

/**
 * 对应 check_iptv_m3u.sh 的统计信息与输出产物路径
 */
public class ScanStatistics {
    public int totalChannels;
    public int connectTrueCount;
    public int connectFalseCount;
    public int hdCount;         // 高清 (1920x1080)
    public int k4Count;         // 4K (3840x2160 / 0x0)
    public int sdCount;         // 标清 (720x576 / 720x560)
    public int otherCount;      // 其它分辨率
    public int errorCount;      // 错误 (无法连接或无视频流)
    public int mismatchCount;   // 分辨率不匹配数量
    public int newlyDiscoveredCount; // 网段动态扫描新发现的频道数
    public long durationSeconds;

    public File outputRootDir;
    public File resultsCsvFile;
    public File modifiedM3uFile;
    public File mismatchLogFile;
    public File scriptLogFile;

    public String toSummaryText() {
        return String.format(
                "总计检测: %d | 可用(true): %d | 不可用(false): %d | 高清: %d | 4K: %d | 标清: %d | 其它: %d | 错误: %d | 新发现: %d | 耗时: %ds",
                totalChannels, connectTrueCount, connectFalseCount,
                hdCount, k4Count, sdCount, otherCount, errorCount,
                newlyDiscoveredCount, durationSeconds
        );
    }
}
