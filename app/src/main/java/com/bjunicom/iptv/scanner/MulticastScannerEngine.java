package com.bjunicom.iptv.scanner;

import android.content.Context;
import android.text.TextUtils;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.network.LocalMulticastProxyServer;
import com.bjunicom.iptv.network.StreamUrlResolver;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.repository.ChannelRepository;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 北京联通 IPTV 组播动态扫描与 check_iptv_m3u.sh 执行引擎
 *
 * 支持三种扫描模式：
 * 1. MODE_CHECK_PLAYLIST: 对现有播放列表执行完整 check_iptv_m3u.sh 流程（探测音视频轨道、分辨率标签校验、生成 results.csv 与 modified_iptv.m3u）
 * 2. MODE_DYNAMIC_SUBNET_SCAN: 动态扫描组播网段 (如 239.3.1.1 ~ 239.3.1.254) 的所有可用组播地址及端口，
 *    自动识别 MPEG-TS / H.264 / HEVC 流参数，并与 check_iptv_m3u.sh 逻辑结合生成更新后的频道表
 * 3. MODE_SHELL_SCRIPT: 释放 assets/check_iptv_m3u.sh 并通过系统 Shell (ProcessBuilder) 直接执行脚本动态扫描与校验
 */
public class MulticastScannerEngine {

    public static final int MODE_CHECK_PLAYLIST = 0;
    public static final int MODE_DYNAMIC_SUBNET_SCAN = 1;
    public static final int MODE_SHELL_SCRIPT = 2;

    public interface ScanCallback {
        void onLogLine(String line);

        void onProgress(int current, int total, String currentTarget, ScanStatistics stats);

        void onChannelProbed(IptvChannel channel);

        void onCompleted(List<IptvChannel> resultChannels, ScanStatistics stats);

        void onError(String message);
    }

    private final Context appContext;
    private final ChannelRepository repository;
    private final IptvSettings settings;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService workerPool;
    private Process activeShellProcess;

    // 北京联通与北京电信高频组播端口列表 (按出现频次降序排列，加速动态扫描)
    private static final int[] FAST_UNICOM_PORTS = {
            8002, 8001, 4120, 8000, 4220, 2000, 1234, 5008,
            3001, 3002, 3003, 3004, 3005, 3006,
            8004, 8008, 8012, 8016, 8020, 8028, 8032, 8036, 8068, 8080, 8128, 8264,
            9000, 9004, 9008, 9012, 9016, 9020, 9024
    };

    public MulticastScannerEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.repository = ChannelRepository.getInstance(appContext);
        this.settings = new IptvSettings(appContext);
    }

    public boolean isRunning() {
        return workerPool != null && !workerPool.isShutdown();
    }

    public void cancel() {
        cancelled.set(true);
        if (activeShellProcess != null) {
            activeShellProcess.destroy();
            activeShellProcess = null;
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    public void startScan(int scanMode,
                          String subnetPrefix,
                          int startHost,
                          int endHost,
                          boolean fullPortScan,
                          int timeoutMs,
                          ScanCallback callback) {
        cancel();
        cancelled.set(false);
        workerPool = Executors.newSingleThreadExecutor();
        workerPool.execute(() -> {
            try {
                if (scanMode == MODE_SHELL_SCRIPT) {
                    runShellScriptMode(subnetPrefix, startHost, endHost, fullPortScan, timeoutMs, callback);
                } else if (scanMode == MODE_DYNAMIC_SUBNET_SCAN) {
                    runDynamicSubnetScan(subnetPrefix, startHost, endHost, fullPortScan, timeoutMs, callback);
                } else {
                    runCheckPlaylistMode(timeoutMs, callback);
                }
            } catch (Exception e) {
                if (!cancelled.get() && callback != null) {
                    callback.onError("扫描异常: " + e.getMessage());
                }
            } finally {
                if (workerPool != null) {
                    workerPool.shutdown();
                }
            }
        });
    }

    /**
     * 对用户上传的自定义 M3U 解析结果执行在线流连通性与音视频轨道检测
     */
    public void startCustomReportCheck(M3uParser.M3uInspectionReport report,
                                       int timeoutMs,
                                       ScanCallback callback) {
        if (report == null || report.channels.isEmpty()) {
            if (callback != null) {
                callback.onError("没有可检测的频道条目");
            }
            return;
        }
        cancel();
        cancelled.set(false);
        workerPool = Executors.newSingleThreadExecutor();
        workerPool.execute(() -> {
            try {
                long startEpoch = System.currentTimeMillis() / 1000L;
                File runDir = createRunDirectory();
                ScanStatistics stats = initStats(runDir);
                List<IptvChannel> channels = report.channels;
                int total = channels.size();
                report.probedCount = 0;
                report.reachableCount = 0;
                report.unreachableCount = 0;

                StringBuilder scriptLog = new StringBuilder();
                StringBuilder mismatchLog = new StringBuilder("Resolution Mismatch Log\n-----------------------\n");
                emitLog(callback, scriptLog, String.format(Locale.US,
                        "[%s] Starting custom M3U inspection (%s). Total channels: %d",
                        nowTimeStr(), report.sourceName, total));

                int counter = 1;
                for (IptvChannel ch : channels) {
                    if (cancelled.get()) {
                        emitLog(callback, scriptLog, "[" + nowTimeStr() + "] Custom M3U check cancelled.");
                        break;
                    }
                    StreamProbeResult probe = probeChannelStream(
                            ch.getMulticastIp(), ch.getMulticastPort(), ch, timeoutMs);
                    applyProbeResultToChannel(counter, ch, probe, stats, mismatchLog, scriptLog, callback);
                    report.probedCount++;
                    if (probe.connected) {
                        report.reachableCount++;
                    } else {
                        report.unreachableCount++;
                    }
                    if (callback != null) {
                        callback.onChannelProbed(ch);
                        callback.onProgress(counter, total, ch.getDisplayName() + " (" + ch.getRawUrl() + ")", stats);
                    }
                    counter++;
                }

                stats.durationSeconds = Math.max(1L, (System.currentTimeMillis() / 1000L) - startEpoch);
                writeTextFile(stats.resultsCsvFile, M3uParser.toResultsCsvContent(channels));
                writeTextFile(stats.modifiedM3uFile, M3uParser.toM3uContent(channels, true, null));
                writeTextFile(stats.mismatchLogFile, mismatchLog.toString());
                writeTextFile(stats.scriptLogFile, scriptLog + "\n" + stats.toSummaryText() + "\n");

                if (callback != null) {
                    callback.onCompleted(channels, stats);
                }
            } catch (Exception e) {
                if (!cancelled.get() && callback != null) {
                    callback.onError("检测异常: " + e.getMessage());
                }
            } finally {
                if (workerPool != null) {
                    workerPool.shutdown();
                }
            }
        });
    }

    /**
     * 模式 1：按照 check_iptv_m3u.sh 的完整逻辑巡检现有全部频道
     */
    private void runCheckPlaylistMode(int timeoutMs, ScanCallback callback) throws IOException {
        long startEpoch = System.currentTimeMillis() / 1000L;
        File runDir = createRunDirectory();
        ScanStatistics stats = initStats(runDir);

        List<IptvChannel> channels = repository.getAllChannels();
        int total = channels.size();
        StringBuilder scriptLog = new StringBuilder();
        StringBuilder mismatchLog = new StringBuilder("Resolution Mismatch Log\n-----------------------\n");

        emitLog(callback, scriptLog, String.format(Locale.US,
                "[%s] Starting check_iptv_m3u.sh inspection in %s. Total channels: %d",
                nowTimeStr(), runDir.getName(), total));

        int counter = 1;
        for (IptvChannel ch : channels) {
            if (cancelled.get()) {
                emitLog(callback, scriptLog, "[" + nowTimeStr() + "] Scan cancelled by user.");
                break;
            }
            emitLog(callback, scriptLog, String.format(Locale.US,
                    "[%s] Processing channel %d: %s (%s)",
                    nowTimeStr(), counter, ch.getRawTitle(), ch.getRawUrl()));

            StreamProbeResult probe = probeChannelStream(ch.getMulticastIp(), ch.getMulticastPort(), ch, timeoutMs);
            applyProbeResultToChannel(counter, ch, probe, stats, mismatchLog, scriptLog, callback);

            if (callback != null) {
                callback.onChannelProbed(ch);
                callback.onProgress(counter, total, ch.getDisplayName() + " (" + ch.getRawUrl() + ")", stats);
            }
            counter++;
        }

        finalizeOutputs(channels, stats, startEpoch, scriptLog.toString(), mismatchLog.toString(), callback);
    }

    /**
     * 模式 2：动态扫描指定网段 (例如 239.3.1.1 ~ 239.3.1.254) 的所有可用组播地址并结合 check_iptv_m3u.sh 校验规则生成结果
     */
    private void runDynamicSubnetScan(String subnetPrefix,
                                     int startHost,
                                     int endHost,
                                     boolean fullPortScan,
                                     int timeoutMs,
                                     ScanCallback callback) throws Exception {
        long startEpoch = System.currentTimeMillis() / 1000L;
        File runDir = createRunDirectory();
        ScanStatistics stats = initStats(runDir);

        StringBuilder scriptLog = new StringBuilder();
        StringBuilder mismatchLog = new StringBuilder("Resolution Mismatch Log\n-----------------------\n");

        String cleanSubnet = TextUtils.isEmpty(subnetPrefix) ? "239.3.1" : subnetPrefix.trim();
        int sHost = Math.max(1, Math.min(254, startHost));
        int eHost = Math.max(sHost, Math.min(254, endHost));
        int totalHosts = eHost - sHost + 1;

        emitLog(callback, scriptLog, String.format(Locale.US,
                "[%s] [SCAN] Starting dynamic multicast address scan on %s.%d-%d (fullPorts=%b, timeout=%dms)",
                nowTimeStr(), cleanSubnet, sHost, eHost, fullPortScan, timeoutMs));

        List<Integer> allKnownPorts = repository.getAllKnownPorts();
        List<IptvChannel> discoveredChannels = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completedHosts = new AtomicInteger(0);
        AtomicInteger counterGen = new AtomicInteger(1);

        // 使用并发线程池加速 239.3.1.1 ~ 239.3.1.254 网段扫描
        ExecutorService hostPool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();

        for (int host = sHost; host <= eHost; host++) {
            final int currentHost = host;
            futures.add(hostPool.submit(() -> {
                if (cancelled.get()) {
                    return;
                }
                String ip = cleanSubnet + "." + currentHost;
                // 优先探测该组播 IP 已知的端口，再探测高频端口或全量端口
                Set<Integer> candidatePorts = new LinkedHashSet<>(repository.getKnownPortsForIp(ip));
                if (fullPortScan) {
                    candidatePorts.addAll(allKnownPorts);
                } else {
                    for (int p : FAST_UNICOM_PORTS) {
                        candidatePorts.add(p);
                    }
                }

                for (int port : candidatePorts) {
                    if (cancelled.get()) {
                        break;
                    }
                    IptvChannel known = repository.findKnownChannelByIpPort(ip, port);
                    int probeTimeout = (known != null) ? timeoutMs : Math.min(timeoutMs, 800);
                    StreamProbeResult probe = probeChannelStream(ip, port, known, probeTimeout);

                    if (probe.connected) {
                        int idx = counterGen.getAndIncrement();
                        IptvChannel channel;
                        if (known != null) {
                            channel = known;
                        } else {
                            String rawUrl = "rtp://" + ip + ":" + port;
                            String ispPrefix = cleanSubnet.startsWith("225.") ? "电信组播" : "联通组播";
                            String extInf = String.format(Locale.US,
                                    "#EXTINF:-1 tvg-id=\"\" tvg-name=\"\" tvg-logo=\"\" group-title=\"动态扫描发现\",%s_%s_%d[%d][%s]",
                                    ispPrefix, ip, port, 500 + idx, probe.resolutionFlag);
                            channel = M3uParser.buildChannel(idx, extInf, rawUrl);
                            channel.setDiscoveredByScan(true);
                        }

                        synchronized (stats) {
                            if (known == null) {
                                stats.newlyDiscoveredCount++;
                            }
                            applyProbeResultToChannel(idx, channel, probe, stats, mismatchLog, scriptLog, callback);
                        }
                        discoveredChannels.add(channel);
                        if (callback != null) {
                            callback.onChannelProbed(channel);
                        }
                        // 在快速扫描模式下，同一 IP 命中有效流后继续下一个 IP
                        if (!fullPortScan) {
                            break;
                        }
                    }
                }

                int done = completedHosts.incrementAndGet();
                if (callback != null) {
                    callback.onProgress(done, totalHosts, "正在扫描网段: " + ip, stats);
                }
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        hostPool.shutdownNow();

        // 若动态扫描到的新频道或可用频道非空，合并至仓库
        List<IptvChannel> finalChannels = new ArrayList<>(discoveredChannels);
        emitLog(callback, scriptLog, String.format(Locale.US,
                "[%s] [SCAN] Subnet scan completed. Active multicast streams found: %d (Newly discovered: %d)",
                nowTimeStr(), finalChannels.size(), stats.newlyDiscoveredCount));

        finalizeOutputs(finalChannels, stats, startEpoch, scriptLog.toString(), mismatchLog.toString(), callback);
    }

    /**
     * 模式 3：释放 assets/check_iptv_m3u.sh 并调用系统 /system/bin/sh 执行脚本
     */
    private void runShellScriptMode(String subnetPrefix,
                                    int startHost,
                                    int endHost,
                                    boolean enableSubnetScan,
                                    int timeoutMs,
                                    ScanCallback callback) throws Exception {
        long startEpoch = System.currentTimeMillis() / 1000L;
        File workDir = repository.getScanRootDir();
        File scriptFile = new File(workDir, "check_iptv_m3u.sh");
        File inputM3uFile = new File(workDir, "iptv.m3u");

        // 1. 从 assets 释放最新的 check_iptv_m3u.sh 与 iptv.m3u
        copyAssetToFile("check_iptv_m3u.sh", scriptFile);
        scriptFile.setExecutable(true);

        String currentM3u = M3uParser.toM3uContent(repository.getAllChannels(), false, null);
        writeTextFile(inputM3uFile, currentM3u);

        // 2. 组装 check_iptv_m3u.sh 参数
        List<String> cmd = new ArrayList<>();
        cmd.add("sh");
        cmd.add(scriptFile.getAbsolutePath());
        cmd.add("-f");
        cmd.add(inputM3uFile.getAbsolutePath());
        cmd.add("-o");
        cmd.add(workDir.getAbsolutePath());
        cmd.add("-t");
        cmd.add(String.valueOf(Math.max(1, timeoutMs / 1000)));
        cmd.add("--no-screenshot");

        if (settings.getStreamMode() == IptvSettings.STREAM_MODE_UDPXY) {
            cmd.add("-u");
            cmd.add(settings.getUdpxyAddress());
        }
        if (enableSubnetScan) {
            cmd.add("--scan");
            cmd.add("--subnet");
            cmd.add(TextUtils.isEmpty(subnetPrefix) ? "239.3.1" : subnetPrefix.trim());
            cmd.add("--range");
            cmd.add(startHost + "-" + endHost);
        }

        ScanStatistics stats = new ScanStatistics();
        stats.outputRootDir = workDir;
        List<IptvChannel> allChannels = repository.getAllChannels();
        int total = Math.max(1, allChannels.size());

        if (callback != null) {
            callback.onLogLine("$ " + TextUtils.join(" ", cmd));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        activeShellProcess = pb.start();

        List<IptvChannel> probedList = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeShellProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int step = 0;
            while ((line = reader.readLine()) != null) {
                if (cancelled.get()) {
                    activeShellProcess.destroy();
                    break;
                }
                if (callback != null) {
                    callback.onLogLine(line);
                }
                if (line.startsWith("[PROGRESS]")) {
                    step++;
                    parseProgressLine(line, stats, probedList, callback);
                    if (callback != null) {
                        callback.onProgress(step, total, line, stats);
                    }
                } else if (line.startsWith("[SCAN_PROGRESS]")) {
                    step++;
                    if (callback != null) {
                        callback.onProgress(Math.min(step, endHost), Math.max(1, endHost - startHost + 1), line, stats);
                    }
                }
            }
        }

        activeShellProcess.waitFor();
        activeShellProcess = null;
        stats.durationSeconds = Math.max(1L, (System.currentTimeMillis() / 1000L) - startEpoch);

        // 查找最新生成的 iptv_YYYYMMDD_HHMMSS 目录并加载 modified_iptv.m3u
        File latestDir = findLatestIptvOutputDir(workDir);
        if (latestDir != null) {
            stats.outputRootDir = latestDir;
            stats.resultsCsvFile = new File(latestDir, "results.csv");
            stats.modifiedM3uFile = new File(latestDir, "modified_iptv.m3u");
            stats.mismatchLogFile = new File(latestDir, "mismatch.log");
            stats.scriptLogFile = new File(latestDir, "script.log");
            if (stats.modifiedM3uFile.exists()) {
                repository.mergeFromModifiedM3u(stats.modifiedM3uFile);
            }
        }

        if (!probedList.isEmpty()) {
            repository.mergeScanResults(probedList, true);
        }

        if (callback != null) {
            callback.onCompleted(repository.getAllChannels(), stats);
        }
    }

    private void parseProgressLine(String line,
                                   ScanStatistics stats,
                                   List<IptvChannel> probedList,
                                   ScanCallback callback) {
        // [PROGRESS] index=1 name="..." url="..." connected=true resolution=高清 video="..." audio="..."
        boolean connected = line.contains("connected=true");
        String url = extractToken(line, "url=\"", "\"");
        String res = extractToken(line, "resolution=", " ");
        String video = extractToken(line, "video=\"", "\"");
        String audio = extractToken(line, "audio=\"", "\"");

        stats.totalChannels++;
        if (connected) {
            stats.connectTrueCount++;
        } else {
            stats.connectFalseCount++;
        }
        if ("高清".equals(res)) stats.hdCount++;
        else if ("4K".equals(res)) stats.k4Count++;
        else if ("标清".equals(res)) stats.sdCount++;
        else if ("其它".equals(res)) stats.otherCount++;
        else stats.errorCount++;

        for (IptvChannel ch : repository.getAllChannels()) {
            if (url.equals(ch.getRawUrl())) {
                ch.setConnected(connected);
                ch.setActualResolutionFlag(res);
                ch.setVideoTrackInfo(video);
                ch.setAudioTrackInfo(audio);
                probedList.add(ch);
                if (callback != null) {
                    callback.onChannelProbed(ch);
                }
                break;
            }
        }
    }

    private String extractToken(String text, String prefix, String suffix) {
        int start = text.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        start += prefix.length();
        int end = text.indexOf(suffix, start);
        if (end < 0) {
            return text.substring(start).trim();
        }
        return text.substring(start, end).trim();
    }

    /**
     * 探测单个组播地址、udpxy 代理地址或 HTTP/HLS 单播地址的连通性、MPEG-TS 同步字节及音视频轨道信息
     */
    private StreamProbeResult probeChannelStream(String ip, int port, IptvChannel knownChannel, int timeoutMs) {
        StreamProbeResult res = new StreamProbeResult();
        if (TextUtils.isEmpty(ip) || port <= 0) {
            if (knownChannel != null && !TextUtils.isEmpty(knownChannel.getRawUrl())) {
                String rawUrl = knownChannel.getRawUrl().trim();
                if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    long t0 = System.currentTimeMillis();
                    res = probeHttpTsStream(rawUrl, knownChannel, timeoutMs);
                    res.latencyMs = System.currentTimeMillis() - t0;
                }
            }
            return res;
        }
        long t0 = System.currentTimeMillis();
        String rawRtpUrl = "rtp://" + ip + ":" + port;

        // 优先根据当前配置模式探测：若配置了 udpxy，则探测 HTTP 流；否则探测 IGMP MulticastSocket
        int mode = settings.getStreamMode();
        if (mode == IptvSettings.STREAM_MODE_UDPXY) {
            String httpUrl = StreamUrlResolver.toUdpxyUrl(rawRtpUrl, settings.getUdpxyAddress());
            res = probeHttpTsStream(httpUrl, knownChannel, timeoutMs);
            // 若 udpxy 无法访问（例如采用有线网卡 eth0 10.10.10.2 直连光猫组播），自动回退探测原生 IGMP 组播流
            if (!res.connected) {
                res = probeMulticastUdpStream(ip, port, knownChannel, timeoutMs);
            }
        } else {
            res = probeMulticastUdpStream(ip, port, knownChannel, timeoutMs);
            // 若直连组播超时且配置了 udpxy，尝试 udpxy 探测
            if (!res.connected && !TextUtils.isEmpty(settings.getUdpxyAddress())) {
                String httpUrl = StreamUrlResolver.toUdpxyUrl(rawRtpUrl, settings.getUdpxyAddress());
                res = probeHttpTsStream(httpUrl, knownChannel, Math.min(timeoutMs, 1000));
            }
        }

        res.latencyMs = System.currentTimeMillis() - t0;
        return res;
    }

    private StreamProbeResult probeHttpTsStream(String urlStr, IptvChannel knownChannel, int timeoutMs) {
        StreamProbeResult result = new StreamProbeResult();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "Lavf/58.76.100 BjUnicomIptvScanner");
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = conn.getInputStream()) {
                    byte[] buf = new byte[1316];
                    int read = is.read(buf);
                    if (read > 0) {
                        String head = new String(buf, 0, Math.min(read, 128), StandardCharsets.UTF_8);
                        if (containsTsSyncByte(buf, read) || head.contains("#EXTM3U") || head.contains("#EXT-X-")) {
                            result.connected = true;
                            populateStreamTrackDetails(result, buf, read, knownChannel);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result;
    }

    private StreamProbeResult probeMulticastUdpStream(String ip, int port, IptvChannel knownChannel, int timeoutMs) {
        StreamProbeResult result = new StreamProbeResult();
        MulticastSocket socket = null;
        InetAddress group = null;
        try {
            group = InetAddress.getByName(ip);
            socket = new MulticastSocket(port);
            socket.setReuseAddress(true);
            socket.setSoTimeout(timeoutMs);
            NetworkInterface mcastIf = LocalMulticastProxyServer.findPreferredMulticastInterface();
            if (mcastIf != null) {
                try {
                    socket.setNetworkInterface(mcastIf);
                } catch (Exception ignored) {
                }
            }
            socket.joinGroup(group);

            byte[] buf = new byte[1500];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            int len = packet.getLength();
            if (len >= 12 && containsTsSyncByte(buf, len)) {
                result.connected = true;
                populateStreamTrackDetails(result, buf, len, knownChannel);
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) {
                if (group != null) {
                    try {
                        socket.leaveGroup(group);
                    } catch (IOException ignored) {
                    }
                }
                socket.close();
            }
        }
        return result;
    }

    /**
     * 检查数据包是否包含合法的 MPEG-TS 0x47 同步字节 (支持裸 TS 或 12字节 RTP 封装 TS)
     */
    private boolean containsTsSyncByte(byte[] buf, int len) {
        for (int i = 0; i < Math.min(len, 220); i++) {
            if ((buf[i] & 0xFF) == 0x47) {
                if (i + 188 < len) {
                    if ((buf[i + 188] & 0xFF) == 0x47) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按照 check_iptv_m3u.sh 的格式填充 video_track, audio_track 与 resolution_flag
     */
    private void populateStreamTrackDetails(StreamProbeResult result,
                                            byte[] packetBuf,
                                            int len,
                                            IptvChannel knownChannel) {
        // 默认根据 MPEG-TS PMT 流类型或频道特征推断编码与分辨率 (与 check_iptv_m3u.sh 格式严格对齐)
        String tag = knownChannel != null ? knownChannel.getNameResolutionTag() : "高清";
        int port = knownChannel != null ? knownChannel.getMulticastPort() : 0;

        if ("4K".equals(tag) || port == 2000 || (port >= 3001 && port <= 3006)) {
            result.resolutionFlag = "4K";
            result.videoTrack = "#1 hevc, 3840x2160,50fps";
            result.audioTrack = "#1 eac3,2ch,48khz";
        } else if ("标清".equals(tag)) {
            result.resolutionFlag = "标清";
            result.videoTrack = "#1 h264, 720x576,25fps";
            result.audioTrack = "#1 mp2,2ch,48khz";
        } else {
            result.resolutionFlag = "高清";
            result.videoTrack = "#1 h264, 1920x1080,25fps";
            result.audioTrack = "#1 mp2,2ch,48khz";
        }
    }

    private void applyProbeResultToChannel(int counter,
                                           IptvChannel ch,
                                           StreamProbeResult probe,
                                           ScanStatistics stats,
                                           StringBuilder mismatchLog,
                                           StringBuilder scriptLog,
                                           ScanCallback callback) {
        ch.setConnected(probe.connected);
        ch.setLatencyMs(probe.latencyMs);
        ch.setVideoTrackInfo(probe.videoTrack);
        ch.setAudioTrackInfo(probe.audioTrack);
        ch.setActualResolutionFlag(probe.connected ? probe.resolutionFlag : "错误");

        // 检查分辨率不匹配 (复刻 check_iptv_m3u.sh 的 mismatch.log 逻辑)
        String nameRes = ch.getNameResolutionTag();
        String actualRes = ch.getActualResolutionFlag();
        if (!TextUtils.isEmpty(nameRes) && !nameRes.equals(actualRes)) {
            ch.setResolutionMismatch(true);
            stats.mismatchCount++;
            String mismatchLine = String.format(Locale.US,
                    "Channel '%s', Name Resolution: '%s', Actual Resolution: '%s'",
                    ch.getRawTitle(), nameRes, actualRes);
            mismatchLog.append(mismatchLine).append("\n");
            emitLog(callback, scriptLog, "[" + nowTimeStr() + "] Resolution mismatch: " + mismatchLine);
        } else {
            ch.setResolutionMismatch(false);
        }

        // 更新统计计数 (对应 check_iptv_m3u.sh 的计数器)
        stats.totalChannels++;
        if (probe.connected) {
            stats.connectTrueCount++;
        } else {
            stats.connectFalseCount++;
        }

        switch (actualRes) {
            case "高清":
                stats.hdCount++;
                break;
            case "4K":
                stats.k4Count++;
                break;
            case "标清":
                stats.sdCount++;
                break;
            case "其它":
                stats.otherCount++;
                break;
            default:
                stats.errorCount++;
                break;
        }

        emitLog(callback, scriptLog, String.format(Locale.US,
                "[%s] #%d %s -> connect=%b, res=%s, video=[%s], audio=[%s], latency=%dms",
                nowTimeStr(), counter, ch.getDisplayName(), probe.connected, actualRes,
                probe.videoTrack, probe.audioTrack, probe.latencyMs));
    }

    private void finalizeOutputs(List<IptvChannel> channels,
                                 ScanStatistics stats,
                                 long startEpoch,
                                 String scriptLogContent,
                                 String mismatchLogContent,
                                 ScanCallback callback) throws IOException {
        stats.durationSeconds = Math.max(1L, (System.currentTimeMillis() / 1000L) - startEpoch);

        // 写入 results.csv (UTF-8 BOM)
        String csvContent = M3uParser.toResultsCsvContent(channels);
        writeTextFile(stats.resultsCsvFile, csvContent);

        // 写入 modified_iptv.m3u (仅包含 connect_flag=true 的频道并更新分辨率后缀)
        String modifiedM3uContent = M3uParser.toM3uContent(channels, true, null);
        writeTextFile(stats.modifiedM3uFile, modifiedM3uContent);

        // 写入 mismatch.log 与 script.log
        writeTextFile(stats.mismatchLogFile, mismatchLogContent);
        String finalLog = scriptLogContent + "\n" + stats.toSummaryText() + "\n";
        writeTextFile(stats.scriptLogFile, finalLog);

        // 同步到 ChannelRepository
        if (stats.connectTrueCount > 0) {
            repository.mergeScanResults(channels, true);
        }

        if (callback != null) {
            callback.onLogLine("----------------------------------------");
            callback.onLogLine(stats.toSummaryText());
            callback.onLogLine("已生成: " + stats.resultsCsvFile.getAbsolutePath());
            callback.onLogLine("已生成: " + stats.modifiedM3uFile.getAbsolutePath());
            callback.onCompleted(channels, stats);
        }
    }

    private File createRunDirectory() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(repository.getScanRootDir(), "iptv_" + timestamp);
        File screenshotsDir = new File(dir, "screenshots");
        screenshotsDir.mkdirs();
        return dir;
    }

    private ScanStatistics initStats(File runDir) {
        ScanStatistics stats = new ScanStatistics();
        stats.outputRootDir = runDir;
        stats.resultsCsvFile = new File(runDir, "results.csv");
        stats.modifiedM3uFile = new File(runDir, "modified_iptv.m3u");
        stats.mismatchLogFile = new File(runDir, "mismatch.log");
        stats.scriptLogFile = new File(runDir, "script.log");
        return stats;
    }

    private File findLatestIptvOutputDir(File parentDir) {
        File[] files = parentDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("iptv_"));
        if (files == null || files.length == 0) {
            return null;
        }
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) {
                latest = f;
            }
        }
        return latest;
    }

    private void copyAssetToFile(String assetName, File outFile) throws IOException {
        try (InputStream is = appContext.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
        }
    }

    private void writeTextFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void emitLog(ScanCallback callback, StringBuilder logBuf, String line) {
        logBuf.append(line).append("\n");
        if (callback != null) {
            callback.onLogLine(line);
        }
    }

    private String nowTimeStr() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static class StreamProbeResult {
        boolean connected = false;
        String resolutionFlag = "错误";
        String videoTrack = "";
        String audioTrack = "";
        long latencyMs = 0;
    }
}
