package com.bjunicom.iptv.player;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;
import com.bjunicom.iptv.network.RtpTsPacketParser;
import com.bjunicom.iptv.network.TransportRouteCache;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UTVPlayer 专用混合流数据源 (UTVStreamDataSource)
 *
 * 供 ExoPlayer 管理的 ProgressiveMediaSource 拉流：
 * 1. 支持 rtp://、udp:// 以及本地组播网关地址 (127.0.0.1/rtp/...) 直接通过原生 IGMP MulticastSocket 拉流（校验并剥离 RTP 头）；
 * 2. 支持 http:// 与 https:// (路由器 udpxy / HLS)，当外部 udpxy 连接失败或首包读取超时/空流时，自动无缝回退至原生 IGMP 组播；
 * 3. 首次连接 HTTP-TS 流时自动执行 188B (0x47) 同步对齐；
 * 4. 统计实时网络拉流吞吐率供 HUD 展示。
 */
public class UTVStreamDataSource extends BaseDataSource {
    public interface Listener { void onInfo(int info); }
    private static final AtomicLong globalSpeedBytesPerSec = new AtomicLong(0L);
    private static volatile long lastSpeedUpdateMs = 0L;

    public static final class Factory implements DataSource.Factory {
        private final DefaultHttpDataSource.Factory httpFactory;
        private final SharedState state;
        private final Listener listener;
        private final long epoch;

        public Factory() {
            this(new SharedState(), null, -1);
        }

        private Factory(SharedState state, Listener listener, long epoch) {
            this.state = state;
            this.listener = listener;
            this.epoch = epoch;
            this.httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("uTV-ExoPlayer/2.19.1")
                    .setConnectTimeoutMs(2500)
                    .setReadTimeoutMs(3500)
                    .setAllowCrossProtocolRedirects(true);
        }

        @Override
        public DataSource createDataSource() {
            return new UTVStreamDataSource(httpFactory.createDataSource(), state, listener,
                    epoch >= 0 ? epoch : state.epoch);
        }

        public Factory forPlayback(Listener listener, java.util.Map<String, String> headers) {
            Factory factory = new Factory(state, listener, state.epoch);
            factory.httpFactory.setDefaultRequestProperties(headers);
            return factory;
        }

        public void preload(Uri uri, Uri activeUri) {
            String target = uri != null ? extractMulticastTarget(uri.toString()) : null;
            boolean direct = uri != null && ("rtp".equalsIgnoreCase(uri.getScheme())
                    || "udp".equalsIgnoreCase(uri.getScheme())
                    || "127.0.0.1".equals(uri.getHost()) || TransportRouteCache.shouldBypass(uri));
            state.preloader.preload(direct ? target : null,
                    activeUri != null ? extractMulticastTarget(activeUri.toString()) : null);
        }

        public void stopBackgroundPreload() { state.preloader.stop(); }

        public void cancelActiveReads() {
            state.epoch++;
            for (UTVStreamDataSource source : state.activeSources) source.abortNativeRead();
        }

        public void close() {
            cancelActiveReads();
            state.preloader.close();
        }
    }

    private static final class SharedState {
        final MulticastPreloader preloader = new MulticastPreloader();
        final Set<UTVStreamDataSource> activeSources = new CopyOnWriteArraySet<>();
        volatile long epoch;
    }

    private final DefaultHttpDataSource httpDataSource;
    private final SharedState state;
    private final Listener listener;
    private final long epoch;
    private boolean firstPacketNotified;
    private byte[] preloadedBytes;
    private int preloadOffset;
    private final RtpTsPacketParser rtpParser = new RtpTsPacketParser();
    private Uri currentUri;
    private String fallbackMulticastTarget;
    private boolean opened = false;
    private volatile boolean usingMulticast = false;
    private boolean needsInitialTsSync = false;

    private volatile MulticastSocket multicastSocket;
    private InetAddress multicastGroup;
    private int multicastPort = -1;
    private List<NetworkInterface> joinedInterfaces;

    private final byte[] udpPacketBuffer = new byte[65535];
    private final DatagramPacket datagramPacket = new DatagramPacket(udpPacketBuffer, udpPacketBuffer.length);
    private final byte[] initialSyncBuffer = new byte[188 * 12];
    private int remainingPayloadOffset = 0;
    private int remainingPayloadLength = 0;

    private long windowStartMs = 0L;
    private long windowBytes = 0L;

    private UTVStreamDataSource(DefaultHttpDataSource httpDataSource, SharedState state,
                               Listener listener, long epoch) {
        super(true);
        this.httpDataSource = httpDataSource;
        this.state = state;
        this.listener = listener;
        this.epoch = epoch;
    }

    public static long getRecentSpeedBytesPerSec() {
        if (SystemClock.elapsedRealtime() - lastSpeedUpdateMs > 2500L) {
            return 0L;
        }
        return globalSpeedBytesPerSec.get();
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        checkNotCancelled();
        state.activeSources.add(this);
        try {
            return openSource(dataSpec);
        } catch (IOException | RuntimeException error) {
            try { close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private long openSource(DataSpec dataSpec) throws IOException {
        firstPacketNotified = false;
        preloadedBytes = null;
        preloadOffset = remainingPayloadOffset = remainingPayloadLength = 0;
        rtpParser.reset();
        currentUri = dataSpec.uri;
        transferInitializing(dataSpec);

        String uriStr = currentUri != null ? currentUri.toString() : "";
        String scheme = currentUri != null ? currentUri.getScheme() : "";
        String mcastIpPort = extractMulticastTarget(uriStr);
        this.fallbackMulticastTarget = mcastIpPort;
        boolean isHlsOrDash = uriStr.contains(".m3u8") || uriStr.contains(".mpd");
        boolean isLocalLoopback = uriStr.startsWith("http://127.0.0.1:");

        // 若为 rtp://、udp:// 或本地 127.0.0.1 组播代理地址，直接使用原生 MulticastSocket 加入 IGMP 组播组并剥离 RTP 头，
        // 省去本地 TCP 环回开销并避免多连接代理会话抢占
        if ("rtp".equalsIgnoreCase(scheme)
                || "udp".equalsIgnoreCase(scheme)
                || (isLocalLoopback && !TextUtils.isEmpty(mcastIpPort))
                || TransportRouteCache.shouldBypass(currentUri)) {
            openMulticastSocket(mcastIpPort);
            usingMulticast = true;
            needsInitialTsSync = false;
            opened = true;
            windowStartMs = SystemClock.elapsedRealtime();
            windowBytes = 0L;
            transferStarted(dataSpec);
            return C.LENGTH_UNSET;
        }

        try {
            long len = httpDataSource.open(dataSpec);
            usingMulticast = false;
            needsInitialTsSync = !isHlsOrDash && !TextUtils.isEmpty(mcastIpPort);
            remainingPayloadOffset = 0;
            remainingPayloadLength = 0;
            opened = true;
            windowStartMs = SystemClock.elapsedRealtime();
            windowBytes = 0L;
            transferStarted(dataSpec);
            return len;
        } catch (IOException httpEx) {
            if (!TextUtils.isEmpty(mcastIpPort)
                    && PlaybackFailure.fromException(httpEx) == PlaybackFailure.NETWORK) {
                httpDataSource.close();
                openMulticastSocket(mcastIpPort);
                TransportRouteCache.recordNetworkFailure(currentUri);
                if (listener != null) listener.onInfo(PlaybackMetrics.INFO_TRANSPORT_FALLBACK);
                usingMulticast = true;
                needsInitialTsSync = false;
                opened = true;
                windowStartMs = SystemClock.elapsedRealtime();
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
        checkNotCancelled();
        MulticastPreloader.Lease lease = state.preloader.take(ipPort);
        if (lease == null) lease = MulticastPreloader.open(ipPort, 4000);
        this.multicastPort = lease.port;
        this.multicastGroup = lease.group;
        this.joinedInterfaces = lease.interfaces;
        this.multicastSocket = lease.socket;
        this.preloadedBytes = lease.startupBytes;
        this.preloadOffset = 0;
        if (epoch != state.epoch) {
            lease.close();
            throw new InterruptedIOException("Channel request was replaced");
        }
        this.remainingPayloadOffset = 0;
        this.remainingPayloadLength = 0;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        checkNotCancelled();
        if (length == 0) {
            return 0;
        }
        if (preloadedBytes != null && preloadOffset < preloadedBytes.length) {
            int n = Math.min(length, preloadedBytes.length - preloadOffset);
            System.arraycopy(preloadedBytes, preloadOffset, buffer, offset, n);
            preloadOffset += n;
            recordBytes(n);
            bytesTransferred(n);
            if (preloadOffset == preloadedBytes.length) preloadedBytes = null;
            return n;
        }
        if (!usingMulticast) {
            if (remainingPayloadLength > 0) {
                int toCopy = Math.min(length, remainingPayloadLength);
                System.arraycopy(initialSyncBuffer, remainingPayloadOffset, buffer, offset, toCopy);
                remainingPayloadOffset += toCopy;
                remainingPayloadLength -= toCopy;
                recordBytes(toCopy);
                bytesTransferred(toCopy);
                return toCopy;
            }
            if (needsInitialTsSync) {
                needsInitialTsSync = false;
                int totalRead = 0;
                try {
                    while (totalRead < 188 * 6) {
                        int r = httpDataSource.read(
                                initialSyncBuffer,
                                totalRead,
                                initialSyncBuffer.length - totalRead);
                        if (r < 0) {
                            break;
                        }
                        totalRead += r;
                    }
                } catch (IOException readEx) {
                    if (totalRead == 0 && !TextUtils.isEmpty(fallbackMulticastTarget)
                            && PlaybackFailure.fromException(readEx) == PlaybackFailure.NETWORK) {
                        // udpxy 虽然返回了 HTTP 200 OK 头，但读取首包超时：立即无缝切换至原生 IGMP 组播直连
                        try {
                            httpDataSource.close();
                        } catch (Exception ignored) {
                        }
                        openMulticastSocket(fallbackMulticastTarget);
                        TransportRouteCache.recordNetworkFailure(currentUri);
                        if (listener != null) listener.onInfo(PlaybackMetrics.INFO_TRANSPORT_FALLBACK);
                        usingMulticast = true;
                        return read(buffer, offset, length);
                    }
                    throw readEx;
                }
                if (totalRead == 0) {
                    if (!TextUtils.isEmpty(fallbackMulticastTarget)) {
                        try {
                            httpDataSource.close();
                        } catch (Exception ignored) {
                        }
                        openMulticastSocket(fallbackMulticastTarget);
                        TransportRouteCache.recordNetworkFailure(currentUri);
                        if (listener != null) listener.onInfo(PlaybackMetrics.INFO_TRANSPORT_FALLBACK);
                        usingMulticast = true;
                        return read(buffer, offset, length);
                    }
                    return C.RESULT_END_OF_INPUT;
                }
                int syncPos = findTsSyncOffset(initialSyncBuffer, totalRead);
                if (syncPos < 0) throw com.google.android.exoplayer2.ParserException.createForMalformedContainer(
                        "udpxy response contains no MPEG-TS synchronization", null);
                remainingPayloadOffset = syncPos;
                remainingPayloadLength = totalRead - syncPos;
                int toCopy = Math.min(length, remainingPayloadLength);
                System.arraycopy(initialSyncBuffer, remainingPayloadOffset, buffer, offset, toCopy);
                remainingPayloadOffset += toCopy;
                remainingPayloadLength -= toCopy;
                recordBytes(toCopy);
                bytesTransferred(toCopy);
                return toCopy;
            }
            int n = httpDataSource.read(buffer, offset, length);
            if (n > 0) {
                recordBytes(n);
                bytesTransferred(n);
            }
            return n;
        }

        return readFromMulticast(buffer, offset, length);
    }

    private int readFromMulticast(byte[] buffer, int offset, int length) throws IOException {
        while (remainingPayloadLength <= 0) {
            checkNotCancelled();
            MulticastSocket socket = multicastSocket;
            if (socket == null) throw new IOException("Multicast socket is closed");
            datagramPacket.setLength(udpPacketBuffer.length);
            socket.receive(datagramPacket);
            checkNotCancelled();
            int pktLen = datagramPacket.getLength();
            if (pktLen <= 0) {
                return 0;
            }

            RtpTsPacketParser.Payload payload = rtpParser.parse(udpPacketBuffer, pktLen);
            if (payload == null) continue;
            remainingPayloadOffset = payload.offset;
            remainingPayloadLength = payload.length;
        }

        int toCopy = Math.min(length, remainingPayloadLength);
        System.arraycopy(udpPacketBuffer, remainingPayloadOffset, buffer, offset, toCopy);
        remainingPayloadOffset += toCopy;
        remainingPayloadLength -= toCopy;
        recordBytes(toCopy);
        bytesTransferred(toCopy);
        return toCopy;
    }

    public static int findTsSyncOffset(byte[] buf, int totalLen) {
        for (int i = 0; i < Math.min(188, totalLen); i++) {
            if ((buf[i] & 0xFF) != 0x47) {
                continue;
            }
            boolean match = true;
            for (int p = 1; p < 5; p++) {
                int idx = i + p * 188;
                if (idx < totalLen && (buf[idx] & 0xFF) != 0x47) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private void recordBytes(int bytes) {
        if (!firstPacketNotified && bytes > 0) {
            firstPacketNotified = true;
            if (listener != null) listener.onInfo(PlaybackMetrics.INFO_FIRST_PACKET);
        }
        long now = SystemClock.elapsedRealtime();
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
        state.activeSources.remove(this);
        preloadedBytes = null;
        try {
            MulticastSocket socket = multicastSocket;
            multicastSocket = null;
            if (socket != null) {
                LocalMulticastProxyServer.leaveMulticastOnAllInterfaces(
                        socket, multicastGroup, multicastPort, joinedInterfaces);
                socket.close();
            }
            joinedInterfaces = null;
            multicastGroup = null;
            remainingPayloadLength = 0;
            httpDataSource.close();
        } finally {
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    private void checkNotCancelled() throws InterruptedIOException {
        if (epoch != state.epoch || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Channel request was replaced");
        }
    }

    private void abortNativeRead() {
        MulticastSocket socket = multicastSocket;
        if (socket != null) socket.close();
    }

    @Nullable
    public static String extractMulticastTarget(String uriStr) {
        return TextUtils.isEmpty(uriStr) ? null : TransportRouteCache.multicastTarget(Uri.parse(uriStr));
    }
}
