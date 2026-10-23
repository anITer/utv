package com.bjunicom.iptv.player;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UTVPlayer 专用混合流数据源 (UTVStreamDataSource)
 *
 * 供 PreloadMediaSource 的 3 个 ProgressiveMediaPeriod 拉流并写入各自的 SampleQueue：
 * 1. 支持 rtp:// 与 udp:// 原生 IGMP 组播直连（自动按组播地址过滤绑定、加入 IPTV 网卡组播组并剥离 12B RTP 头）；
 * 2. 支持 http:// 与 https:// (udpxy / 本地代理 / HLS)，当外部 udpxy 不可达时自动回退至原生 IGMP 组播；
 * 3. 统计实时网络拉流吞吐率供 HUD 展示。
 */
public class UTVStreamDataSource extends BaseDataSource {
    private static final Pattern MCAST_URI_PATTERN = Pattern.compile("(?:rtp|udp)://@?([0-9.]+):(\\d+)");
    private static final Pattern UDPXY_PATH_PATTERN = Pattern.compile("/(?:rtp|udp)/([0-9.]+):(\\d+)");

    private static final AtomicLong globalSpeedBytesPerSec = new AtomicLong(0L);
    private static volatile long lastSpeedUpdateMs = 0L;

    public static final class Factory implements DataSource.Factory {
        private final DefaultHttpDataSource.Factory httpFactory;

        public Factory() {
            this.httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("uTV-ExoPlayer/2.19.1")
                    .setConnectTimeoutMs(2500)
                    .setReadTimeoutMs(4000)
                    .setAllowCrossProtocolRedirects(true);
        }

        @Override
        public DataSource createDataSource() {
            return new UTVStreamDataSource(httpFactory.createDataSource());
        }
    }

    private final DefaultHttpDataSource httpDataSource;
    private Uri currentUri;
    private boolean opened = false;
    private boolean usingMulticast = false;

    private MulticastSocket multicastSocket;
    private InetAddress multicastGroup;
    private int multicastPort = -1;
    private List<NetworkInterface> joinedInterfaces;

    private final byte[] udpPacketBuffer = new byte[2048];
    private final DatagramPacket datagramPacket = new DatagramPacket(udpPacketBuffer, udpPacketBuffer.length);
    private int remainingPayloadOffset = 0;
    private int remainingPayloadLength = 0;

    private long windowStartMs = 0L;
    private long windowBytes = 0L;

    public UTVStreamDataSource(DefaultHttpDataSource httpDataSource) {
        super(true);
        this.httpDataSource = httpDataSource;
    }

    public static long getRecentSpeedBytesPerSec() {
        if (System.currentTimeMillis() - lastSpeedUpdateMs > 2500L) {
            return 0L;
        }
        return globalSpeedBytesPerSec.get();
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        currentUri = dataSpec.uri;
        transferInitializing(dataSpec);

        String uriStr = currentUri != null ? currentUri.toString() : "";
        String scheme = currentUri != null ? currentUri.getScheme() : "";
        String mcastIpPort = extractMulticastTarget(uriStr);

        if ("rtp".equalsIgnoreCase(scheme) || "udp".equalsIgnoreCase(scheme)) {
            openMulticastSocket(mcastIpPort);
            usingMulticast = true;
            opened = true;
            windowStartMs = System.currentTimeMillis();
            windowBytes = 0L;
            transferStarted(dataSpec);
            return C.LENGTH_UNSET;
        }

        try {
            long len = httpDataSource.open(dataSpec);
            usingMulticast = false;
            opened = true;
            windowStartMs = System.currentTimeMillis();
            windowBytes = 0L;
            transferStarted(dataSpec);
            return len;
        } catch (IOException httpEx) {
            if (!TextUtils.isEmpty(mcastIpPort)) {
                openMulticastSocket(mcastIpPort);
                usingMulticast = true;
                opened = true;
                windowStartMs = System.currentTimeMillis();
                windowBytes = 0L;
                transferStarted(dataSpec);
                return C.LENGTH_UNSET;
            }
            throw httpEx;
        }
    }

    private void openMulticastSocket(String ipPort) throws IOException {
        if (TextUtils.isEmpty(ipPort) || !ipPort.contains(":")) {
            throw new IOException("Invalid multicast target: " + ipPort);
        }
        String[] parts = ipPort.split(":");
        String ip = parts[0];
        this.multicastPort = Integer.parseInt(parts[1]);
        this.multicastGroup = InetAddress.getByName(ip);

        MulticastSocket socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        LocalMulticastProxyServer.bindMulticastSocketWithGroupFilter(socket, multicastGroup, multicastPort);
        socket.setReceiveBufferSize(2 * 1024 * 1024);
        socket.setSoTimeout(4000);
        this.joinedInterfaces = LocalMulticastProxyServer.joinMulticastOnAllInterfaces(
                socket, multicastGroup, multicastPort);
        this.multicastSocket = socket;
        this.remainingPayloadOffset = 0;
        this.remainingPayloadLength = 0;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!usingMulticast) {
            int n = httpDataSource.read(buffer, offset, length);
            if (n > 0) {
                recordBytes(n);
                bytesTransferred(n);
            }
            return n;
        }

        if (remainingPayloadLength <= 0) {
            if (multicastSocket == null) {
                return C.RESULT_END_OF_INPUT;
            }
            datagramPacket.setLength(udpPacketBuffer.length);
            multicastSocket.receive(datagramPacket);
            int pktLen = datagramPacket.getLength();
            int pktOffset = datagramPacket.getOffset();
            if (pktLen <= 0) {
                return 0;
            }

            // 自动剥离 12 字节 RTP 协议头（含 CSRC 与扩展头），直接将纯净 TS 流交给 ProgressiveMediaPeriod
            int payloadOffset = pktOffset;
            int payloadLen = pktLen;
            if ((udpPacketBuffer[pktOffset] & 0xFF) != 0x47 && pktLen > 12) {
                int version = (udpPacketBuffer[pktOffset] & 0xC0) >>> 6;
                if (version == 2) {
                    int csrcCount = udpPacketBuffer[pktOffset] & 0x0F;
                    boolean hasExt = (udpPacketBuffer[pktOffset] & 0x10) != 0;
                    boolean hasPad = (udpPacketBuffer[pktOffset] & 0x20) != 0;
                    int hdrLen = 12 + csrcCount * 4;
                    if (hasExt && pktLen >= hdrLen + 4) {
                        int extWords = ((udpPacketBuffer[pktOffset + hdrLen + 2] & 0xFF) << 8)
                                | (udpPacketBuffer[pktOffset + hdrLen + 3] & 0xFF);
                        hdrLen += 4 + extWords * 4;
                    }
                    int padBytes = hasPad ? (udpPacketBuffer[pktOffset + pktLen - 1] & 0xFF) : 0;
                    if (hdrLen + padBytes < pktLen) {
                        payloadOffset = pktOffset + hdrLen;
                        payloadLen = pktLen - hdrLen - padBytes;
                    }
                }
            }
            remainingPayloadOffset = payloadOffset;
            remainingPayloadLength = payloadLen;
        }

        int toCopy = Math.min(length, remainingPayloadLength);
        System.arraycopy(udpPacketBuffer, remainingPayloadOffset, buffer, offset, toCopy);
        remainingPayloadOffset += toCopy;
        remainingPayloadLength -= toCopy;
        recordBytes(toCopy);
        bytesTransferred(toCopy);
        return toCopy;
    }

    private void recordBytes(int bytes) {
        long now = System.currentTimeMillis();
        windowBytes += bytes;
        long elapsed = now - windowStartMs;
        if (elapsed >= 450L) {
            long speed = (windowBytes * 1000L) / Math.max(1L, elapsed);
            globalSpeedBytesPerSec.set(speed);
            lastSpeedUpdateMs = now;
            windowBytes = 0L;
            windowStartMs = now;
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return usingMulticast ? currentUri : httpDataSource.getUri();
    }

    @Override
    public void close() throws IOException {
        try {
            if (usingMulticast) {
                if (multicastSocket != null) {
                    LocalMulticastProxyServer.leaveMulticastOnAllInterfaces(
                            multicastSocket, multicastGroup, multicastPort, joinedInterfaces);
                    try {
                        multicastSocket.close();
                    } catch (Exception ignored) {
                    }
                    multicastSocket = null;
                }
                joinedInterfaces = null;
                multicastGroup = null;
                remainingPayloadLength = 0;
            } else {
                httpDataSource.close();
            }
        } finally {
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    @Nullable
    public static String extractMulticastTarget(String uriStr) {
        if (TextUtils.isEmpty(uriStr)) {
            return null;
        }
        Matcher m1 = MCAST_URI_PATTERN.matcher(uriStr);
        if (m1.find()) {
            return m1.group(1) + ":" + m1.group(2);
        }
        Matcher m2 = UDPXY_PATH_PATTERN.matcher(uriStr);
        if (m2.find()) {
            return m2.group(1) + ":" + m2.group(2);
        }
        return null;
    }
}
