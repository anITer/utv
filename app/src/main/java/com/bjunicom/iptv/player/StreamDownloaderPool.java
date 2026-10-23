package com.bjunicom.iptv.player;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/**
 * 下载模块对象池 (StreamDownloaderPool) 与池化流下载器 (PooledStreamDownloader)
 *
 * 核心职责：
 * 1. 将【流媒体下载 + MPEG-TS 协议解析】与【音视频解码器】彻底分离并池化复用，避免切台时频繁分配大缓冲区与重建线程。
 * 2. 新频道开始下载后，实时解析 MPEG-TS 的 PAT -> PMT 表，提取 Video Codec (H.264 / HEVC) 与 Audio Codec (MP2 / AAC / EAC3 / AC3)，
 *    通过回调通知播放器从 CodecDecoderPool 中选择匹配的池化解码器。
 * 3. 根治切台前几秒「慢放 (Slow-Motion)」问题：
 *    - 切台慢放的根因是直播流随机切入 GOP 中间，FFmpeg 丢弃首个 IDR 关键帧之前的 P/B 视频帧，却保留了早到 0.5~1.5 秒的音频帧，
 *      导致首个视频帧 PTS 远超音频时钟 (video_pts > audio_clock)，触发 ijkplayer compute_target_delay() 将视频帧延迟翻倍 (2x 慢放)。
 *    - 本下载器在首个视频 IDR 关键帧到达前自动过滤掉非关键帧视频包及早到的旧音频包，
 *      将缓冲区起点严格对齐到【PAT + PMT + 首个视频 IDR 关键帧 + 同 PTS 音频帧】，使音视频起始 PTS 完全一致，实现零慢放秒开。
 */
public class StreamDownloaderPool {
    private static final String TAG = "StreamDownloaderPool";
    private static final int MAX_POOL_SIZE = 4;
    private static final int RING_BUFFER_SIZE = 8 * 1024 * 1024; // 8MB 预分配环形缓冲，保障 4K 50fps 高码率流零丢包

    private static volatile StreamDownloaderPool instance;

    private final Queue<PooledStreamDownloader> idlePool = new ConcurrentLinkedQueue<>();
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PooledTsDownloader");
        t.setPriority(Thread.MAX_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger createdCount = new AtomicInteger(0);

    public interface OnStreamEventsListener {
        void onCodecParsed(TsCodecDescriptor descriptor, PooledStreamDownloader downloader);

        void onStreamError(String reason, PooledStreamDownloader downloader);
    }

    public interface TsPacketTeeListener {
        void onTsPacket(byte[] buf, int offset);
    }

    private StreamDownloaderPool() {
        // 预热 2 个池化下载器实例
        for (int i = 0; i < 2; i++) {
            idlePool.offer(new PooledStreamDownloader(this, RING_BUFFER_SIZE));
            createdCount.incrementAndGet();
        }
    }

    public static StreamDownloaderPool getInstance() {
        if (instance == null) {
            synchronized (StreamDownloaderPool.class) {
                if (instance == null) {
                    instance = new StreamDownloaderPool();
                }
            }
        }
        return instance;
    }

    /**
     * 从对象池借出下载器实例
     */
    public PooledStreamDownloader acquire() {
        PooledStreamDownloader downloader = idlePool.poll();
        if (downloader == null) {
            downloader = new PooledStreamDownloader(this, RING_BUFFER_SIZE);
            int total = createdCount.incrementAndGet();
            Log.d(TAG, "Created new PooledStreamDownloader, total=" + total);
        } else {
            Log.d(TAG, "Reused PooledStreamDownloader from pool, remaining=" + idlePool.size());
        }
        downloader.resetForAcquire();
        return downloader;
    }

    /**
     * 归还下载器实例至对象池复用
     */
    public void release(PooledStreamDownloader downloader) {
        if (downloader == null) {
            return;
        }
        downloader.stopSession();
        if (idlePool.size() < MAX_POOL_SIZE) {
            idlePool.offer(downloader);
        }
    }

    ExecutorService getDownloadExecutor() {
        return downloadExecutor;
    }

    /**
     * 池化流媒体下载器：支持 UDPXY HTTP-TS 与 IGMP 原生组播 RTP/UDP 双通道下载、
     * 实时解析 MPEG-TS PAT/PMT/Codec、自动对齐 IDR 关键帧与音视频起始 PTS，并实现 IMediaDataSource 供解码器零拷贝读取。
     */
    public static class PooledStreamDownloader implements IMediaDataSource {
        private static final Pattern MCAST_URI_PATTERN = Pattern.compile("(?:rtp|udp)://@?([0-9.]+):(\\d+)");
        private static final Pattern UDPXY_PATH_PATTERN = Pattern.compile("/(?:rtp|udp)/([0-9.]+):(\\d+)");
        private static final long KEYFRAME_ALIGN_MAX_WAIT_MS = 420L;
        private static final long HEVC_KEYFRAME_ALIGN_MAX_WAIT_MS = 950L;
        private static final int MAX_PENDING_VIDEO_PES_PACKETS = 16;

        private final StreamDownloaderPool ownerPool;
        private final byte[] ringBuffer;
        private final int capacity;
        private final Object bufferLock = new Object();

        private long writePos = 0L;
        private long readPos = 0L;
        private int waitingReaders = 0;
        private volatile boolean sessionActive = false;
        private volatile boolean streamEof = false;
        private final AtomicInteger sessionToken = new AtomicInteger(0);

        // MPEG-TS 实时解析与 IDR 对齐状态
        private final byte[] cachedPatPacket = new byte[188];
        private final byte[] cachedPmtPacket = new byte[188];
        private final byte[] pendingVideoPesPackets = new byte[188 * MAX_PENDING_VIDEO_PES_PACKETS];
        private int pendingVideoPesCount = 0;
        private long pendingVideoPesPts = -1L;
        private boolean hasPat = false;
        private boolean hasPmt = false;
        private int pmtPid = -1;
        private int videoPid = -1;
        private int audioPid = -1;
        private int videoStreamType = 0x1B;
        private int audioStreamType = 0x03;
        private String parsedVideoCodec = TsCodecDescriptor.VIDEO_H264;
        private String parsedAudioCodec = TsCodecDescriptor.AUDIO_MP2;

        private boolean keyframeAligned = false;
        private boolean audioAligned = false;
        private long firstVideoPts = -1L;
        private long parseStartTimeMs = 0L;
        private boolean codecNotified = false;
        private volatile TsCodecDescriptor currentDescriptor = null;

        // 实时下载速率统计 (Bytes/sec)
        private long speedWindowStartMs = 0L;
        private long bytesInCurrentWindow = 0L;
        private volatile long currentSpeedBytesPerSec = 0L;

        private volatile TsPacketTeeListener audioTeeListener;
        private volatile HttpURLConnection activeHttpConn;
        private volatile MulticastSocket activeMcastSocket;

        PooledStreamDownloader(StreamDownloaderPool ownerPool, int bufferCapacity) {
            this.ownerPool = ownerPool;
            this.capacity = bufferCapacity;
            this.ringBuffer = new byte[bufferCapacity];
        }

        void resetForAcquire() {
            sessionToken.incrementAndGet();
            sessionActive = false;
            streamEof = false;
            audioTeeListener = null;
            synchronized (bufferLock) {
                writePos = 0L;
                readPos = 0L;
                waitingReaders = 0;
            }
            hasPat = false;
            hasPmt = false;
            pendingVideoPesCount = 0;
            pendingVideoPesPts = -1L;
            pmtPid = -1;
            videoPid = -1;
            audioPid = -1;
            videoStreamType = 0x1B;
            audioStreamType = 0x03;
            parsedVideoCodec = TsCodecDescriptor.VIDEO_H264;
            parsedAudioCodec = TsCodecDescriptor.AUDIO_MP2;
            keyframeAligned = false;
            audioAligned = false;
            firstVideoPts = -1L;
            codecNotified = false;
            currentDescriptor = null;
            currentSpeedBytesPerSec = 0L;
            bytesInCurrentWindow = 0L;
            speedWindowStartMs = System.currentTimeMillis();
        }

        public void setAudioTeeListener(TsPacketTeeListener listener) {
            this.audioTeeListener = listener;
        }

        public TsCodecDescriptor getCurrentDescriptor() {
            return currentDescriptor;
        }

        public long getDownloadSpeedBytesPerSec() {
            long now = System.currentTimeMillis();
            if (now - speedWindowStartMs > 2500L) {
                return 0L;
            }
            return currentSpeedBytesPerSec;
        }

        /**
         * 启动异步流下载与 MPEG-TS Codec 探测任务
         */
        public void startDownload(final Uri primaryUri,
                                  final String rawChannelUrl,
                                  final OnStreamEventsListener listener) {
            final int myToken = sessionToken.incrementAndGet();
            sessionActive = true;
            streamEof = false;
            parseStartTimeMs = System.currentTimeMillis();
            speedWindowStartMs = parseStartTimeMs;

            ownerPool.getDownloadExecutor().execute(() ->
                    runDownloadLoop(myToken, primaryUri, rawChannelUrl, listener));
        }

        public void stopSession() {
            sessionToken.incrementAndGet();
            sessionActive = false;
            streamEof = true;
            audioTeeListener = null;

            HttpURLConnection conn = activeHttpConn;
            activeHttpConn = null;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }

            MulticastSocket sock = activeMcastSocket;
            activeMcastSocket = null;
            if (sock != null) {
                try {
                    sock.close();
                } catch (Throwable ignored) {
                }
            }

            synchronized (bufferLock) {
                bufferLock.notifyAll();
            }
        }

        private boolean isTokenValid(int token) {
            return sessionActive && sessionToken.get() == token;
        }

        private void runDownloadLoop(int token,
                                     Uri primaryUri,
                                     String rawChannelUrl,
                                     OnStreamEventsListener listener) {
            String uriStr = primaryUri != null ? primaryUri.toString() : "";
            String mcastTarget = extractMulticastIpPort(uriStr, rawChannelUrl);

            boolean isLocalLoopbackHttp = uriStr.startsWith("http://127.0.0.1:");
            boolean preferDirectMulticast = !TextUtils.isEmpty(mcastTarget)
                    && (uriStr.startsWith("rtp://") || uriStr.startsWith("udp://") || isLocalLoopbackHttp);

            try {
                if (preferDirectMulticast) {
                    downloadFromMulticast(token, mcastTarget, listener);
                } else if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                    boolean httpOk = downloadFromHttp(token, uriStr, listener);
                    if (!httpOk && isTokenValid(token) && !TextUtils.isEmpty(mcastTarget)) {
                        Log.i(TAG, "HTTP stream unreachable, falling back in-downloader to multicast: " + mcastTarget);
                        downloadFromMulticast(token, mcastTarget, listener);
                    } else if (!httpOk && isTokenValid(token) && listener != null) {
                        listener.onStreamError("无法连接流媒体源: " + uriStr, this);
                    }
                } else if (!TextUtils.isEmpty(mcastTarget)) {
                    downloadFromMulticast(token, mcastTarget, listener);
                } else if (listener != null && isTokenValid(token)) {
                    listener.onStreamError("不支持的流地址: " + uriStr, this);
                }
            } catch (Throwable t) {
                if (isTokenValid(token) && listener != null) {
                    listener.onStreamError("流下载异常: " + t.getMessage(), this);
                }
            } finally {
                if (sessionToken.get() == token) {
                    streamEof = true;
                    synchronized (bufferLock) {
                        bufferLock.notifyAll();
                    }
                }
            }
        }

        private String extractMulticastIpPort(String uriStr, String rawChannelUrl) {
            if (!TextUtils.isEmpty(rawChannelUrl)) {
                Matcher m = MCAST_URI_PATTERN.matcher(rawChannelUrl.trim());
                if (m.find()) {
                    return m.group(1) + ":" + m.group(2);
                }
            }
            if (!TextUtils.isEmpty(uriStr)) {
                Matcher m1 = MCAST_URI_PATTERN.matcher(uriStr);
                if (m1.find()) {
                    return m1.group(1) + ":" + m1.group(2);
                }
                Matcher m2 = UDPXY_PATH_PATTERN.matcher(uriStr);
                if (m2.find()) {
                    return m2.group(1) + ":" + m2.group(2);
                }
            }
            return null;
        }

        private boolean downloadFromHttp(int token, String urlStr, OnStreamEventsListener listener) {
            HttpURLConnection conn = null;
            InputStream in = null;
            boolean receivedAnyTs = false;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                activeHttpConn = conn;
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(4000);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", "BjUnicomIptv-PooledDownloader/2.0");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    return false;
                }
                in = conn.getInputStream();
                byte[] readBuf = new byte[188 * 42];
                byte[] carry = new byte[376];
                int carryLen = 0;

                while (isTokenValid(token)) {
                    int n = in.read(readBuf, 0, readBuf.length);
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    recordDownloadedBytes(n);

                    byte[] workBuf;
                    int totalLen;
                    if (carryLen > 0) {
                        totalLen = carryLen + n;
                        workBuf = new byte[totalLen];
                        System.arraycopy(carry, 0, workBuf, 0, carryLen);
                        System.arraycopy(readBuf, 0, workBuf, carryLen, n);
                        carryLen = 0;
                    } else {
                        workBuf = readBuf;
                        totalLen = n;
                    }

                    int pos = 0;
                    while (pos + 188 <= totalLen && isTokenValid(token)) {
                        if ((workBuf[pos] & 0xFF) != 0x47) {
                            pos++;
                            continue;
                        }
                        if (pos + 376 <= totalLen && (workBuf[pos + 188] & 0xFF) != 0x47) {
                            pos++;
                            continue;
                        }
                        receivedAnyTs = true;
                        onAlignedTsPacket(token, workBuf, pos, listener);
                        pos += 188;
                    }

                    int rem = totalLen - pos;
                    if (rem > 0 && rem <= carry.length) {
                        System.arraycopy(workBuf, pos, carry, 0, rem);
                        carryLen = rem;
                    }
                }
                return receivedAnyTs;
            } catch (Exception e) {
                return receivedAnyTs;
            } finally {
                activeHttpConn = null;
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private void downloadFromMulticast(int token, String ipPort, OnStreamEventsListener listener) {
            String[] parts = ipPort.split(":");
            if (parts.length != 2) {
                if (listener != null && isTokenValid(token)) {
                    listener.onStreamError("无效的组播地址: " + ipPort, this);
                }
                return;
            }
            String mcastIp = parts[0];
            int mcastPort = Integer.parseInt(parts[1]);

            MulticastSocket socket = null;
            InetAddress groupAddr = null;
            NetworkInterface mcastIf = null;
            boolean joinedWithIf = false;

            try {
                groupAddr = InetAddress.getByName(mcastIp);
                socket = new MulticastSocket(null);
                activeMcastSocket = socket;
                socket.setReuseAddress(true);
                try {
                    socket.bind(new InetSocketAddress(groupAddr, mcastPort));
                } catch (Exception bindEx) {
                    socket.bind(new InetSocketAddress(mcastPort));
                }
                socket.setReceiveBufferSize(2 * 1024 * 1024);
                socket.setSoTimeout(4000);

                mcastIf = LocalMulticastProxyServer.findPreferredMulticastInterface();
                if (mcastIf != null) {
                    try {
                        socket.setNetworkInterface(mcastIf);
                    } catch (Exception ignored) {
                    }
                    try {
                        socket.joinGroup(new InetSocketAddress(groupAddr, mcastPort), mcastIf);
                        joinedWithIf = true;
                    } catch (Exception ignored) {
                    }
                }
                if (!joinedWithIf) {
                    socket.joinGroup(groupAddr);
                }

                byte[] udpBuf = new byte[2048];
                DatagramPacket packet = new DatagramPacket(udpBuf, udpBuf.length);

                while (isTokenValid(token)) {
                    socket.receive(packet);
                    int len = packet.getLength();
                    int offset = packet.getOffset();
                    if (len < 188) {
                        continue;
                    }
                    recordDownloadedBytes(len);

                    // 自动剥离 12 字节 RTP 协议头（含 CSRC 与扩展头）
                    int payloadOffset = offset;
                    int payloadLen = len;
                    if ((udpBuf[offset] & 0xFF) != 0x47 && len > 12) {
                        int version = (udpBuf[offset] & 0xC0) >>> 6;
                        if (version == 2) {
                            int csrcCount = udpBuf[offset] & 0x0F;
                            boolean hasExt = (udpBuf[offset] & 0x10) != 0;
                            boolean hasPad = (udpBuf[offset] & 0x20) != 0;
                            int hdrLen = 12 + csrcCount * 4;
                            if (hasExt && len >= hdrLen + 4) {
                                int extWords = ((udpBuf[offset + hdrLen + 2] & 0xFF) << 8)
                                        | (udpBuf[offset + hdrLen + 3] & 0xFF);
                                hdrLen += 4 + extWords * 4;
                            }
                            int padBytes = hasPad ? (udpBuf[offset + len - 1] & 0xFF) : 0;
                            if (hdrLen + padBytes < len) {
                                payloadOffset = offset + hdrLen;
                                payloadLen = len - hdrLen - padBytes;
                            }
                        }
                    }

                    int end = payloadOffset + payloadLen;
                    for (int pos = payloadOffset; pos + 188 <= end && isTokenValid(token); pos += 188) {
                        if ((udpBuf[pos] & 0xFF) == 0x47) {
                            onAlignedTsPacket(token, udpBuf, pos, listener);
                        }
                    }
                }
            } catch (Exception e) {
                if (isTokenValid(token) && listener != null) {
                    listener.onStreamError("组播流读取超时或中断: " + mcastIp + ":" + mcastPort, this);
                }
            } finally {
                activeMcastSocket = null;
                if (socket != null) {
                    if (groupAddr != null) {
                        try {
                            if (joinedWithIf && mcastIf != null) {
                                socket.leaveGroup(new InetSocketAddress(groupAddr, mcastPort), mcastIf);
                            } else {
                                socket.leaveGroup(groupAddr);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private void recordDownloadedBytes(int bytes) {
            long now = System.currentTimeMillis();
            bytesInCurrentWindow += bytes;
            long elapsed = now - speedWindowStartMs;
            if (elapsed >= 500L) {
                currentSpeedBytesPerSec = (bytesInCurrentWindow * 1000L) / Math.max(1L, elapsed);
                bytesInCurrentWindow = 0L;
                speedWindowStartMs = now;
            }
        }

        /**
         * 处理每一个 188 字节对齐的 MPEG-TS 包：
         * 1. 解析 PAT (PID 0) 与 PMT，提取音视频 PID 与 Codec 类型；
         * 2. 对齐首个视频 IDR 关键帧与同 PTS 音频帧，彻底消除切台前几秒的慢放现象；
         * 3. 写入环形缓冲区唤醒解码器读取，并按需将杜比音频包旁路给 TsHardwareAudioCompanion。
         */
        private void onAlignedTsPacket(int token, byte[] buf, int pktOffset, OnStreamEventsListener listener) {
            int tei = (buf[pktOffset + 1] & 0x80) != 0 ? 1 : 0;
            if (tei != 0) {
                return;
            }
            boolean pusi = (buf[pktOffset + 1] & 0x40) != 0;
            int pid = ((buf[pktOffset + 1] & 0x1F) << 8) | (buf[pktOffset + 2] & 0xFF);
            int adaptationCtrl = (buf[pktOffset + 3] >>> 4) & 0x03;

            int payloadStart = pktOffset + 4;
            boolean randomAccessIndicator = false;
            if ((adaptationCtrl & 0x02) != 0) {
                int adaptationLen = buf[pktOffset + 4] & 0xFF;
                if (adaptationLen > 0 && pktOffset + 5 < pktOffset + 188) {
                    randomAccessIndicator = (buf[pktOffset + 5] & 0x40) != 0;
                }
                payloadStart += 1 + adaptationLen;
            }
            int pktEnd = pktOffset + 188;

            // 1. 解析并缓存 PAT 表 (PID 0x0000)
            if (pid == 0x0000 && (adaptationCtrl & 0x01) != 0 && payloadStart < pktEnd) {
                if (parsePatPacket(buf, payloadStart, pktEnd, pusi)) {
                    System.arraycopy(buf, pktOffset, cachedPatPacket, 0, 188);
                    hasPat = true;
                }
            }

            // 2. 解析并缓存 PMT 表
            if (pmtPid > 0 && pid == pmtPid && (adaptationCtrl & 0x01) != 0 && payloadStart < pktEnd) {
                if (parsePmtPacket(buf, payloadStart, pktEnd, pusi)) {
                    System.arraycopy(buf, pktOffset, cachedPmtPacket, 0, 188);
                    hasPmt = true;
                }
            }

            // 旁路 TS 包给硬件伴音解码器 (仅当启用了 AC-3/E-AC-3 伴音监听且已对齐关键帧时)
            TsPacketTeeListener tee = this.audioTeeListener;
            if (tee != null && keyframeAligned && (pid == 0x0000 || pid == pmtPid || pid == audioPid)) {
                tee.onTsPacket(buf, pktOffset);
            }

            long elapsedSinceStart = System.currentTimeMillis() - parseStartTimeMs;

            // 3. 在首个视频关键帧到达前，拦截并丢弃非关键帧视频包与超前到达的旧音频包
            if (!keyframeAligned) {
                long maxWaitMs = TsCodecDescriptor.VIDEO_HEVC.equals(parsedVideoCodec)
                        ? HEVC_KEYFRAME_ALIGN_MAX_WAIT_MS
                        : KEYFRAME_ALIGN_MAX_WAIT_MS;
                if (hasPmt && videoPid > 0 && pid == videoPid && (adaptationCtrl & 0x01) != 0 && payloadStart < pktEnd) {
                    if (pusi) {
                        pendingVideoPesCount = 0;
                        pendingVideoPesPts = parsePesPts(buf, payloadStart, pktEnd);
                    }
                    if (pendingVideoPesCount < MAX_PENDING_VIDEO_PES_PACKETS) {
                        System.arraycopy(buf, pktOffset,
                                pendingVideoPesPackets, pendingVideoPesCount * 188, 188);
                        pendingVideoPesCount++;
                    }
                    if (randomAccessIndicator || isVideoKeyframePayload(buf, payloadStart, pktEnd)) {
                        keyframeAligned = true;
                        firstVideoPts = pendingVideoPesPts;
                        // 在缓冲区头部立即注入最新的 PAT + PMT 表，确保解码器 0 延迟识别流格式
                        if (hasPat) {
                            writeTsPacketToRing(cachedPatPacket, 0);
                        }
                        if (hasPmt) {
                            writeTsPacketToRing(cachedPmtPacket, 0);
                        }
                        // 将当前关键帧 PES 起始包一并写入环形缓冲区，保证 PES Header 与 VPS/SPS/IDR 完整对齐
                        for (int i = 0; i < pendingVideoPesCount - 1; i++) {
                            writeTsPacketToRing(pendingVideoPesPackets, i * 188);
                        }
                        pendingVideoPesCount = 0;
                        notifyCodecParsedIfNeeded(token, listener);
                    }
                } else if (elapsedSinceStart >= maxWaitMs && hasPmt) {
                    // 安全兜底：若超时仍未遇到显式 IDR 标记，立即放行，防止特殊流卡住
                    keyframeAligned = true;
                    audioAligned = true;
                    if (hasPat) {
                        writeTsPacketToRing(cachedPatPacket, 0);
                    }
                    if (hasPmt) {
                        writeTsPacketToRing(cachedPmtPacket, 0);
                    }
                    pendingVideoPesCount = 0;
                    notifyCodecParsedIfNeeded(token, listener);
                } else if (elapsedSinceStart >= maxWaitMs * 2) {
                    keyframeAligned = true;
                    audioAligned = true;
                    pendingVideoPesCount = 0;
                    notifyCodecParsedIfNeeded(token, listener);
                }

                if (!keyframeAligned) {
                    // 关键帧尚未到达，丢弃中途的残缺 GOP 视频包与提前到达的旧音频包
                    return;
                }
            }

            // 4. 关键帧已对齐后，检查首个音频 PES 包的 PTS，丢弃早于首个视频关键帧 PTS 的历史音频帧
            if (audioPid > 0 && pid == audioPid) {
                if (!audioAligned) {
                    if (pusi && (adaptationCtrl & 0x01) != 0 && payloadStart < pktEnd) {
                        long audioPts = parsePesPts(buf, payloadStart, pktEnd);
                        // 允许音频 PTS 在视频首帧 PTS 前后 45ms (4050 ticks @ 90kHz) 内对齐放行
                        if (firstVideoPts <= 0 || audioPts <= 0 || isPtsAtOrAfter(audioPts, firstVideoPts - 4050L)) {
                            audioAligned = true;
                        } else {
                            // 丢弃早于首个视频 IDR 帧的旧音频包，防止 audio_clock 落后于 video_pts 引发慢放
                            return;
                        }
                    } else {
                        return;
                    }
                }
            }

            writeTsPacketToRing(buf, pktOffset);
        }

        private void notifyCodecParsedIfNeeded(int token, OnStreamEventsListener listener) {
            if (codecNotified || !isTokenValid(token)) {
                return;
            }
            codecNotified = true;
            long cost = Math.max(1L, System.currentTimeMillis() - parseStartTimeMs);
            TsCodecDescriptor desc = new TsCodecDescriptor(
                    parsedVideoCodec,
                    parsedAudioCodec,
                    pmtPid,
                    videoPid,
                    audioPid,
                    videoStreamType,
                    audioStreamType,
                    keyframeAligned,
                    firstVideoPts,
                    cost
            );
            currentDescriptor = desc;
            Log.i(TAG, "Parsed channel codec & aligned keyframe: " + desc);
            if (listener != null) {
                listener.onCodecParsed(desc, this);
            }
        }

        private boolean parsePatPacket(byte[] buf, int start, int end, boolean pusi) {
            if (!pusi) {
                return false;
            }
            int pointerField = buf[start] & 0xFF;
            int secStart = start + 1 + pointerField;
            if (secStart + 12 > end) {
                return false;
            }
            int tableId = buf[secStart] & 0xFF;
            if (tableId != 0x00) {
                return false;
            }
            int sectionLength = ((buf[secStart + 1] & 0x0F) << 8) | (buf[secStart + 2] & 0xFF);
            int secEnd = Math.min(end, secStart + 3 + sectionLength - 4);
            int pos = secStart + 8;
            while (pos + 4 <= secEnd) {
                int programNum = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
                int pid = ((buf[pos + 2] & 0x1F) << 8) | (buf[pos + 3] & 0xFF);
                if (programNum != 0 && pid > 0) {
                    pmtPid = pid;
                    return true;
                }
                pos += 4;
            }
            return false;
        }

        private boolean parsePmtPacket(byte[] buf, int start, int end, boolean pusi) {
            if (!pusi) {
                return false;
            }
            int pointerField = buf[start] & 0xFF;
            int secStart = start + 1 + pointerField;
            if (secStart + 12 > end) {
                return false;
            }
            int tableId = buf[secStart] & 0xFF;
            if (tableId != 0x02) {
                return false;
            }
            int sectionLength = ((buf[secStart + 1] & 0x0F) << 8) | (buf[secStart + 2] & 0xFF);
            int secEnd = Math.min(end, secStart + 3 + sectionLength - 4);
            int programInfoLen = ((buf[secStart + 10] & 0x0F) << 8) | (buf[secStart + 11] & 0xFF);
            int pos = secStart + 12 + programInfoLen;

            int foundVideoPid = -1;
            int foundAudioPid = -1;

            while (pos + 5 <= secEnd) {
                int streamType = buf[pos] & 0xFF;
                int elemPid = ((buf[pos + 1] & 0x1F) << 8) | (buf[pos + 2] & 0xFF);
                int esInfoLen = ((buf[pos + 3] & 0x0F) << 8) | (buf[pos + 4] & 0xFF);
                int descStart = pos + 5;
                int descEnd = Math.min(secEnd, descStart + esInfoLen);

                // 识别视频流类型
                if (streamType == 0x1B) {
                    foundVideoPid = elemPid;
                    videoStreamType = streamType;
                    parsedVideoCodec = TsCodecDescriptor.VIDEO_H264;
                } else if (streamType == 0x24) {
                    foundVideoPid = elemPid;
                    videoStreamType = streamType;
                    parsedVideoCodec = TsCodecDescriptor.VIDEO_HEVC;
                } else if (streamType == 0x02 && foundVideoPid < 0) {
                    foundVideoPid = elemPid;
                    videoStreamType = streamType;
                    parsedVideoCodec = TsCodecDescriptor.VIDEO_MPEG2;
                }

                // 识别音频流类型
                if (foundAudioPid < 0) {
                    if (streamType == 0x03 || streamType == 0x04) {
                        foundAudioPid = elemPid;
                        audioStreamType = streamType;
                        parsedAudioCodec = TsCodecDescriptor.AUDIO_MP2;
                    } else if (streamType == 0x0F || streamType == 0x11) {
                        foundAudioPid = elemPid;
                        audioStreamType = streamType;
                        parsedAudioCodec = TsCodecDescriptor.AUDIO_AAC;
                    } else if (streamType == 0x81) {
                        foundAudioPid = elemPid;
                        audioStreamType = streamType;
                        parsedAudioCodec = TsCodecDescriptor.AUDIO_AC3;
                    } else if (streamType == 0x87) {
                        foundAudioPid = elemPid;
                        audioStreamType = streamType;
                        parsedAudioCodec = TsCodecDescriptor.AUDIO_EAC3;
                    } else if (streamType == 0x06) {
                        for (int d = descStart; d + 2 <= descEnd; ) {
                            int tag = buf[d] & 0xFF;
                            int dLen = buf[d + 1] & 0xFF;
                            if (tag == 0x6A) {
                                foundAudioPid = elemPid;
                                audioStreamType = 0x81;
                                parsedAudioCodec = TsCodecDescriptor.AUDIO_AC3;
                                break;
                            } else if (tag == 0x7A) {
                                foundAudioPid = elemPid;
                                audioStreamType = 0x87;
                                parsedAudioCodec = TsCodecDescriptor.AUDIO_EAC3;
                                break;
                            }
                            d += 2 + dLen;
                        }
                        if (foundAudioPid < 0 && TsCodecDescriptor.VIDEO_HEVC.equals(parsedVideoCodec)) {
                            foundAudioPid = elemPid;
                            audioStreamType = 0x87;
                            parsedAudioCodec = TsCodecDescriptor.AUDIO_EAC3;
                        }
                    }
                }

                pos += 5 + esInfoLen;
            }

            if (foundVideoPid > 0) {
                videoPid = foundVideoPid;
            }
            if (foundAudioPid > 0) {
                audioPid = foundAudioPid;
            }
            return videoPid > 0 || audioPid > 0;
        }

        /**
         * 检测视频 PES 包是否包含 H.264 (SPS=7 / IDR=5) 或 H.265/HEVC (VPS=32 / SPS=33 / IDR=19,20 / CRA=21) 关键帧起始码
         */
        private boolean isVideoKeyframePayload(byte[] buf, int start, int end) {
            boolean isHevc = TsCodecDescriptor.VIDEO_HEVC.equals(parsedVideoCodec);
            for (int i = start; i + 4 < end; i++) {
                if (buf[i] == 0x00 && buf[i + 1] == 0x00 && buf[i + 2] == 0x01) {
                    int nalByte = buf[i + 3] & 0xFF;
                    // 跳过 PES stream_id (0xE0 ~ 0xEF)
                    if (i == start && (nalByte & 0xF0) == 0xE0) {
                        continue;
                    }
                    if (isHevc) {
                        int nalType = (nalByte >>> 1) & 0x3F;
                        if (nalType == 32 || nalType == 33 || nalType == 19 || nalType == 20 || nalType == 21) {
                            return true;
                        }
                    } else {
                        int nalType = nalByte & 0x1F;
                        if (nalType == 7 || nalType == 5) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * 从 PES Header 中提取 33-bit PTS 时间戳 (90kHz 时钟)
         */
        private long parsePesPts(byte[] buf, int start, int end) {
            if (start + 14 > end) {
                return -1L;
            }
            if ((buf[start] & 0xFF) != 0x00 || (buf[start + 1] & 0xFF) != 0x00 || (buf[start + 2] & 0xFF) != 0x01) {
                return -1L;
            }
            int ptsDtsFlags = (buf[start + 7] >>> 6) & 0x03;
            if ((ptsDtsFlags & 0x02) == 0) {
                return -1L;
            }
            long b9 = buf[start + 9] & 0xFFL;
            long b10 = buf[start + 10] & 0xFFL;
            long b11 = buf[start + 11] & 0xFFL;
            long b12 = buf[start + 12] & 0xFFL;
            long b13 = buf[start + 13] & 0xFFL;
            return ((b9 & 0x0EL) << 29)
                    | (b10 << 22)
                    | ((b11 & 0xFEL) << 14)
                    | (b12 << 7)
                    | (b13 >>> 1);
        }

        /**
         * 处理 33-bit PTS 回绕比较
         */
        private boolean isPtsAtOrAfter(long pts, long thresholdPts) {
            long diff = (pts - thresholdPts) & 0x1FFFFFFFFL;
            return diff < 0x100000000L;
        }

        private void writeTsPacketToRing(byte[] src, int offset) {
            synchronized (bufferLock) {
                long unread = writePos - readPos;
                if (unread + 188 > capacity) {
                    // 若环形缓冲满，推进 readPos 保持 188 字节对齐丢弃最旧包，确保实时直播零积压
                    long dropPackets = ((unread + 188 - capacity) / 188) + 64;
                    readPos += dropPackets * 188L;
                }
                int writeIdx = (int) (writePos % capacity);
                int firstPart = Math.min(188, capacity - writeIdx);
                System.arraycopy(src, offset, ringBuffer, writeIdx, firstPart);
                if (firstPart < 188) {
                    System.arraycopy(src, offset + firstPart, ringBuffer, 0, 188 - firstPart);
                }
                writePos += 188L;
                if (waitingReaders > 0) {
                    bufferLock.notifyAll();
                }
            }
        }

        // ==================== IMediaDataSource 接口实现 (供 IjkMediaPlayer 零拷贝读取) ====================

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (size == 0) {
                return 0;
            }
            synchronized (bufferLock) {
                while (writePos - readPos < 188 && sessionActive && !streamEof) {
                    waitingReaders++;
                    try {
                        bufferLock.wait(120L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    } finally {
                        waitingReaders--;
                    }
                }
                if (!sessionActive && writePos <= readPos) {
                    return -1;
                }
                long available = writePos - readPos;
                if (available <= 0) {
                    return streamEof ? -1 : 0;
                }
                int toCopy = (int) Math.min((long) size, available);
                // 保持 188 字节 TS 包整数倍读取，加速 FFmpeg mpegts_read_packet 同步
                if (toCopy >= 188) {
                    toCopy = (toCopy / 188) * 188;
                }
                int readIdx = (int) (readPos % capacity);
                int firstChunk = Math.min(toCopy, capacity - readIdx);
                System.arraycopy(ringBuffer, readIdx, buffer, offset, firstChunk);
                if (firstChunk < toCopy) {
                    System.arraycopy(ringBuffer, 0, buffer, offset + firstChunk, toCopy - firstChunk);
                }
                readPos += toCopy;
                return toCopy;
            }
        }

        @Override
        public long getSize() throws IOException {
            // 返回 -1 告知 libijkplayer (mds_open) 当前为无限实时流 (is_streamed = 1)
            return -1L;
        }

        @Override
        public void close() throws IOException {
            // 由 StreamDownloaderPool 统一管理生命周期与回收
        }
    }
}
