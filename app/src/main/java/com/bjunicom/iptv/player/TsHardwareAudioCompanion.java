package com.bjunicom.iptv.player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 针对 Android TV 的 MPEG-TS 独立硬件伴音解码器 (专用于 AC-3 / E-AC-3 杜比音频频道兜底)
 */
public class TsHardwareAudioCompanion {
    private static final String TAG = "TsHwAudioCompanion";
    private static final Pattern MCAST_PATTERN = Pattern.compile("(?:rtp|udp)://@?([0-9.]+):(\\d+)");

    private final AtomicInteger sessionToken = new AtomicInteger(0);
    private volatile boolean running = false;
    private Thread workerThread;
    private volatile String activeCodecLabel = "";
    private volatile HttpURLConnection activeConn;
    private volatile MulticastSocket activeSocket;

    public String getActiveCodecLabel() {
        return activeCodecLabel;
    }

    public synchronized void start(final Uri playbackUri, final String rawChannelUrl) {
        stop();
        if (playbackUri == null) {
            return;
        }
        final int token = sessionToken.incrementAndGet();
        running = true;
        workerThread = new Thread(
                () -> runStandaloneLoop(token, playbackUri.toString(), rawChannelUrl),
                "TsHwAudioCompanion");
        workerThread.setPriority(Thread.MAX_PRIORITY - 1);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public synchronized void stop() {
        sessionToken.incrementAndGet();
        running = false;
        activeCodecLabel = "";
        HttpURLConnection conn = activeConn;
        activeConn = null;
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
        MulticastSocket sock = activeSocket;
        activeSocket = null;
        if (sock != null) {
            try {
                sock.close();
            } catch (Throwable ignored) {
            }
        }
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private boolean isTokenActive(int token) {
        return running && sessionToken.get() == token && !Thread.currentThread().isInterrupted();
    }

    private void runStandaloneLoop(int token, String uriStr, String rawChannelUrl) {
        HttpURLConnection conn = null;
        InputStream in = null;
        MulticastSocket mcastSocket = null;
        InetAddress mcastGroup = null;
        int mcastPort = -1;
        List<NetworkInterface> joinedInterfaces = null;
        DecoderSession decoderSession = new DecoderSession(token);

        try {
            if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                URL url = new URL(uriStr);
                conn = (HttpURLConnection) url.openConnection();
                activeConn = conn;
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                conn.connect();
                in = conn.getInputStream();
                demuxHttpTsStream(token, in, decoderSession);
            } else {
                String target = (rawChannelUrl != null && !rawChannelUrl.isEmpty()) ? rawChannelUrl : uriStr;
                Matcher m = MCAST_PATTERN.matcher(target);
                if (m.find()) {
                    String ip = m.group(1);
                    mcastPort = Integer.parseInt(m.group(2));
                    mcastGroup = InetAddress.getByName(ip);
                    mcastSocket = new MulticastSocket(null);
                    activeSocket = mcastSocket;
                    mcastSocket.setReuseAddress(true);
                    LocalMulticastProxyServer.bindMulticastSocketWithGroupFilter(mcastSocket, mcastGroup, mcastPort);
                    mcastSocket.setReceiveBufferSize(512 * 1024);
                    mcastSocket.setSoTimeout(4000);
                    joinedInterfaces = LocalMulticastProxyServer.joinMulticastOnAllInterfaces(
                            mcastSocket, mcastGroup, mcastPort);
                    demuxMulticastTsStream(token, mcastSocket, decoderSession);
                }
            }
        } catch (Exception e) {
            if (isTokenActive(token)) {
                Log.w(TAG, "Hardware audio companion ended: " + e.getMessage());
            }
        } finally {
            activeConn = null;
            activeSocket = null;
            decoderSession.releaseNow();
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (mcastSocket != null) {
                LocalMulticastProxyServer.leaveMulticastOnAllInterfaces(
                        mcastSocket, mcastGroup, mcastPort, joinedInterfaces);
                try {
                    mcastSocket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void demuxHttpTsStream(int token, InputStream in, DecoderSession session) throws Exception {
        byte[] readBuf = new byte[188 * 32];
        byte[] carry = new byte[188 * 2];
        int carryLen = 0;

        while (isTokenActive(token)) {
            int n = in.read(readBuf, 0, readBuf.length);
            if (n < 0) {
                break;
            }
            if (n == 0) {
                continue;
            }
            byte[] combined;
            int totalLen;
            if (carryLen > 0) {
                totalLen = carryLen + n;
                combined = new byte[totalLen];
                System.arraycopy(carry, 0, combined, 0, carryLen);
                System.arraycopy(readBuf, 0, combined, carryLen, n);
                carryLen = 0;
            } else {
                combined = readBuf;
                totalLen = n;
            }

            int pos = 0;
            while (pos + 188 <= totalLen && isTokenActive(token)) {
                if ((combined[pos] & 0xFF) != 0x47) {
                    pos++;
                    continue;
                }
                processTsPacket(combined, pos, session);
                pos += 188;
            }
            int rem = totalLen - pos;
            if (rem > 0 && rem <= carry.length) {
                System.arraycopy(combined, pos, carry, 0, rem);
                carryLen = rem;
            }
        }
    }

    private void demuxMulticastTsStream(int token, MulticastSocket socket, DecoderSession session) throws Exception {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (isTokenActive(token)) {
            packet.setLength(buf.length);
            socket.receive(packet);
            int len = packet.getLength();
            int offset = packet.getOffset();
            if (len < 188) {
                continue;
            }
            int payloadOffset = offset;
            if ((buf[offset] & 0xFF) != 0x47 && len > 12) {
                int csrcCount = buf[offset] & 0x0F;
                boolean hasExt = (buf[offset] & 0x10) != 0;
                int hdrSize = 12 + csrcCount * 4;
                if (hasExt && len >= hdrSize + 4) {
                    int extWords = ((buf[offset + hdrSize + 2] & 0xFF) << 8)
                            | (buf[offset + hdrSize + 3] & 0xFF);
                    hdrSize += 4 + extWords * 4;
                }
                if (hdrSize < len) {
                    payloadOffset = offset + hdrSize;
                }
            }
            for (int pos = payloadOffset; pos + 188 <= offset + len && isTokenActive(token); pos += 188) {
                if ((buf[pos] & 0xFF) == 0x47) {
                    processTsPacket(buf, pos, session);
                }
            }
        }
    }

    private void processTsPacket(byte[] buf, int pktOffset, DecoderSession session) {
        int tei = (buf[pktOffset + 1] & 0x80) != 0 ? 1 : 0;
        if (tei != 0) {
            return;
        }
        boolean pusi = (buf[pktOffset + 1] & 0x40) != 0;
        int pid = ((buf[pktOffset + 1] & 0x1F) << 8) | (buf[pktOffset + 2] & 0xFF);
        int adaptationCtrl = (buf[pktOffset + 3] >>> 4) & 0x03;
        if ((adaptationCtrl & 0x01) == 0) {
            return;
        }
        int payloadStart = pktOffset + 4;
        if ((adaptationCtrl & 0x02) != 0) {
            int adaptationLen = buf[pktOffset + 4] & 0xFF;
            payloadStart += 1 + adaptationLen;
        }
        int pktEnd = pktOffset + 188;
        if (payloadStart >= pktEnd) {
            return;
        }

        if (pid == 0x0000) {
            parsePat(buf, payloadStart, pktEnd, pusi, session);
        } else if (session.pmtPid > 0 && pid == session.pmtPid) {
            parsePmt(buf, payloadStart, pktEnd, pusi, session);
        } else if (session.audioPid > 0 && pid == session.audioPid) {
            processAudioTsPayload(buf, payloadStart, pktEnd - payloadStart, pusi, session);
        }
    }

    private void parsePat(byte[] buf, int start, int end, boolean pusi, DecoderSession session) {
        if (!pusi) {
            return;
        }
        int pointerField = buf[start] & 0xFF;
        int secStart = start + 1 + pointerField;
        if (secStart + 12 > end) {
            return;
        }
        int tableId = buf[secStart] & 0xFF;
        if (tableId != 0x00) {
            return;
        }
        int sectionLength = ((buf[secStart + 1] & 0x0F) << 8) | (buf[secStart + 2] & 0xFF);
        int secEnd = Math.min(end, secStart + 3 + sectionLength - 4);
        int pos = secStart + 8;
        while (pos + 4 <= secEnd) {
            int programNum = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            int pid = ((buf[pos + 2] & 0x1F) << 8) | (buf[pos + 3] & 0xFF);
            if (programNum != 0 && pid > 0) {
                session.pmtPid = pid;
                return;
            }
            pos += 4;
        }
    }

    private void parsePmt(byte[] buf, int start, int end, boolean pusi, DecoderSession session) {
        if (!pusi || session.audioPid > 0) {
            return;
        }
        int pointerField = buf[start] & 0xFF;
        int secStart = start + 1 + pointerField;
        if (secStart + 12 > end) {
            return;
        }
        int tableId = buf[secStart] & 0xFF;
        if (tableId != 0x02) {
            return;
        }
        int sectionLength = ((buf[secStart + 1] & 0x0F) << 8) | (buf[secStart + 2] & 0xFF);
        int secEnd = Math.min(end, secStart + 3 + sectionLength - 4);
        int programInfoLen = ((buf[secStart + 10] & 0x0F) << 8) | (buf[secStart + 11] & 0xFF);
        int pos = secStart + 12 + programInfoLen;

        while (pos + 5 <= secEnd) {
            int streamType = buf[pos] & 0xFF;
            int elemPid = ((buf[pos + 1] & 0x1F) << 8) | (buf[pos + 2] & 0xFF);
            int esInfoLen = ((buf[pos + 3] & 0x0F) << 8) | (buf[pos + 4] & 0xFF);
            int descStart = pos + 5;
            int descEnd = Math.min(secEnd, descStart + esInfoLen);

            String detectedMime = null;
            if (streamType == 0x03 || streamType == 0x04) {
                detectedMime = "audio/mpeg-L2";
            } else if (streamType == 0x0F || streamType == 0x11) {
                detectedMime = MediaFormat.MIMETYPE_AUDIO_AAC;
            } else if (streamType == 0x81) {
                detectedMime = MediaFormat.MIMETYPE_AUDIO_AC3;
            } else if (streamType == 0x87) {
                detectedMime = MediaFormat.MIMETYPE_AUDIO_EAC3;
            } else if (streamType == 0x06) {
                int dPos = descStart;
                while (dPos + 2 <= descEnd) {
                    int tag = buf[dPos] & 0xFF;
                    int dLen = buf[dPos + 1] & 0xFF;
                    if (tag == 0x6A) {
                        detectedMime = MediaFormat.MIMETYPE_AUDIO_AC3;
                        break;
                    } else if (tag == 0x7A) {
                        detectedMime = MediaFormat.MIMETYPE_AUDIO_EAC3;
                        break;
                    }
                    dPos += 2 + dLen;
                }
                if (detectedMime == null) {
                    detectedMime = MediaFormat.MIMETYPE_AUDIO_EAC3;
                }
            }

            if (detectedMime != null) {
                session.audioPid = elemPid;
                session.mimeType = detectedMime;
                Log.i(TAG, "Selected MPEG-TS Audio PID=" + elemPid + " streamType=0x"
                        + Integer.toHexString(streamType) + " mime=" + detectedMime);
                return;
            }
            pos += 5 + esInfoLen;
        }
    }

    private void processAudioTsPayload(byte[] buf, int offset, int len, boolean pusi, DecoderSession session) {
        if (pusi) {
            if (session.pesBuffer.size() > 0) {
                flushPesPacket(session.pesBuffer.toByteArray(), session);
                session.pesBuffer.reset();
            }
            session.collectingPes = true;
        }
        if (session.collectingPes && len > 0) {
            session.pesBuffer.write(buf, offset, len);
            if (session.pesBuffer.size() > 64 * 1024) {
                flushPesPacket(session.pesBuffer.toByteArray(), session);
                session.pesBuffer.reset();
            }
        }
    }

    private void flushPesPacket(byte[] pes, DecoderSession session) {
        if (pes.length < 9) {
            return;
        }
        if ((pes[0] & 0xFF) != 0x00 || (pes[1] & 0xFF) != 0x00 || (pes[2] & 0xFF) != 0x01) {
            return;
        }
        int headerDataLen = pes[8] & 0xFF;
        int esStart = 9 + headerDataLen;
        if (esStart >= pes.length) {
            return;
        }
        int esLen = pes.length - esStart;

        if (MediaFormat.MIMETYPE_AUDIO_AC3.equals(session.mimeType)
                || MediaFormat.MIMETYPE_AUDIO_EAC3.equals(session.mimeType)) {
            for (int i = esStart; i + 6 < pes.length; i++) {
                if ((pes[i] & 0xFF) == 0x0B && (pes[i + 1] & 0xFF) == 0x77) {
                    int bsid = (pes[i + 5] >>> 3) & 0x1F;
                    if (bsid > 10 && bsid <= 16) {
                        session.mimeType = MediaFormat.MIMETYPE_AUDIO_EAC3;
                    } else if (bsid <= 10) {
                        session.mimeType = MediaFormat.MIMETYPE_AUDIO_AC3;
                    }
                    break;
                }
            }
        }

        if (!session.initialized) {
            session.initDecoder();
        }
        session.feedElementaryStream(pes, esStart, esLen);
    }

    private class DecoderSession {
        final int token;
        int pmtPid = -1;
        int audioPid = -1;
        String mimeType = null;
        boolean collectingPes = false;
        final ByteArrayOutputStream pesBuffer = new ByteArrayOutputStream(8192);

        boolean initialized = false;
        MediaCodec codec = null;
        AudioTrack audioTrack = null;
        boolean passthroughMode = false;
        int sampleRate = 48000;
        int channelCount = 2;
        long presentationTimeUs = 0L;

        // AAC (ADTS over TS) 专用：跨 PES 重组帧缓冲与已生效配置
        final ByteArrayOutputStream aacEsBuffer = new ByteArrayOutputStream(16384);
        boolean aacSawConfig = false;
        byte[] aacActiveAsc = null;
        int aacSampleRate = 48000;
        int aacChannels = 2;
        long aacPtsUs = 0L;

        DecoderSession(int token) {
            this.token = token;
        }

        void initDecoder() {
            initialized = true;
            if (mimeType == null) {
                return;
            }
            String[] candidateMimes = "audio/mpeg-L2".equals(mimeType)
                    ? new String[]{"audio/mpeg-L2", MediaFormat.MIMETYPE_AUDIO_MPEG}
                    : new String[]{mimeType};

            for (String candidate : candidateMimes) {
                try {
                    MediaFormat format = MediaFormat.createAudioFormat(candidate, 48000, 2);
                    MediaCodec mc = MediaCodec.createDecoderByType(candidate);
                    mc.configure(format, null, null, 0);
                    mc.start();
                    this.codec = mc;
                    this.audioTrack = createPcmAudioTrack(48000, 2);
                    this.passthroughMode = false;
                    activeCodecLabel = candidate + " (硬件伴音)";
                    return;
                } catch (Throwable t) {
                    if (this.codec != null) {
                        try {
                            this.codec.release();
                        } catch (Throwable ignored) {
                        }
                        this.codec = null;
                    }
                }
            }

            if (MediaFormat.MIMETYPE_AUDIO_AC3.equals(mimeType)
                    || MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mimeType)) {
                try {
                    int encoding = MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mimeType)
                            ? AudioFormat.ENCODING_E_AC3
                            : AudioFormat.ENCODING_AC3;
                    this.audioTrack = createPassthroughAudioTrack(48000, encoding);
                    this.passthroughMode = true;
                    activeCodecLabel = mimeType + " (HDMI透传)";
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to init passthrough AudioTrack: " + t.getMessage());
                }
            }
        }

        void feedElementaryStream(byte[] data, int offset, int length) {
            if (!isTokenActive(token)) {
                return;
            }
            if (passthroughMode && audioTrack != null) {
                audioTrack.write(data, offset, length);
                return;
            }
            if (codec == null) {
                return;
            }
            // AAC over TS 的 PES 载荷是 ADTS 帧序列，而 audio/mp4a-latm 解码器只吃裸 AAC：
            // 需按 ADTS 头切帧、剥头、由首帧头生成 AudioSpecificConfig 作为 csd-0
            if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mimeType)) {
                feedAacAdtsStream(data, offset, length);
            } else {
                queueEncodedFrame(data, offset, length, presentationTimeUs);
                presentationTimeUs += 24000;
            }
            drainDecoderOutputs();
        }

        /**
         * 从 ES 字节流中切出完整 ADTS 帧：裸 AAC 载荷入队解码器，首帧头推导 csd-0。
         * 帧可能跨 PES 包，用 aacEsBuffer 做尾保留重组。
         */
        private void feedAacAdtsStream(byte[] data, int offset, int length) {
            aacEsBuffer.write(data, offset, length);
            byte[] buf = aacEsBuffer.toByteArray();
            int n = buf.length;
            int pos = 0;
            int retainFrom = 0;
            while (pos + 7 <= n) {
                if (!((buf[pos] & 0xFF) == 0xFF && (buf[pos + 1] & 0xF6) == 0xF0)) {
                    pos++;
                    continue;
                }
                int protectionAbsent = buf[pos + 1] & 0x01;
                int profile = (buf[pos + 2] >> 6) & 0x03;
                int sfIndex = (buf[pos + 2] >> 2) & 0x0F;
                int chanConfig = ((buf[pos + 2] & 0x01) << 2) | ((buf[pos + 3] >> 6) & 0x03);
                int frameLen = ((buf[pos + 3] & 0x03) << 11)
                        | ((buf[pos + 4] & 0xFF) << 3) | ((buf[pos + 5] >> 5) & 0x07);
                int headerLen = protectionAbsent == 1 ? 7 : 9;
                if (sfIndex == 15 || frameLen < headerLen + 8 || frameLen > 8192) {
                    pos++; // 伪同步
                    continue;
                }
                if (pos + frameLen > n) {
                    retainFrom = pos; // 帧不完整，等下一批数据
                    break;
                }
                if (!aacSawConfig) {
                    int objectType = profile + 1; // ADTS profile 字段 = AudioObjectType - 1
                    byte[] asc = new byte[]{
                            (byte) ((objectType << 3) | (sfIndex >> 1)),
                            (byte) (((sfIndex & 0x01) << 7) | (chanConfig << 3))};
                    int sr = aacSampleRateFromIndex(sfIndex);
                    int ch = chanConfig == 0 ? 2 : chanConfig;
                    configureAacCsd(asc, sr, ch);
                    aacSampleRate = sr;
                    aacChannels = ch;
                    aacPtsUs = 0L;
                    aacSawConfig = true;
                }
                queueEncodedFrame(buf, pos + headerLen, frameLen - headerLen, aacPtsUs);
                aacPtsUs += (1024L * 1_000_000L) / Math.max(1, aacSampleRate);
                pos += frameLen;
            }
            if (pos < n) {
                retainFrom = pos;
            }
            aacEsBuffer.reset();
            int keep = n - retainFrom;
            if (keep > 0 && keep <= 32768) {
                aacEsBuffer.write(buf, retainFrom, keep);
            }
        }

        /**
         * 按 ADTS 头解析出的 AudioSpecificConfig 重新配置 AAC 解码器。
         * MediaCodec 的 audio/mp4a-latm 解码器只接受裸 AAC 帧且必须携带 csd-0，
         * 直接喂 ADTS 帧会静默无声（北京联通 4K 频道即此场景）。
         * 相同配置重复调用是 no-op。
         */
        private void configureAacCsd(byte[] asc, int sr, int ch) {
            if (codec == null || asc == null) {
                return;
            }
            if (aacActiveAsc != null && aacActiveAsc.length == asc.length) {
                boolean same = true;
                for (int i = 0; i < asc.length; i++) {
                    if (aacActiveAsc[i] != asc[i]) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    return;
                }
            }
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                MediaFormat format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
                codec.configure(format, null, null, 0);
                codec.start();
                aacActiveAsc = asc;
                sampleRate = sr;
                channelCount = ch;
                activeCodecLabel = MediaFormat.MIMETYPE_AUDIO_AAC + " (硬件伴音/ADTS)";
                Log.i(TAG, "Reconfigured AAC decoder with ADTS-derived CSD: sr=" + sr
                        + " ch=" + ch);
            } catch (Throwable t) {
                Log.w(TAG, "AAC reconfigure with CSD failed: " + t.getMessage());
            }
        }

        private int aacSampleRateFromIndex(int sfIndex) {
            switch (sfIndex) {
                case 0: return 96000;
                case 1: return 88200;
                case 2: return 64000;
                case 3: return 48000;
                case 4: return 44100;
                case 5: return 32000;
                case 6: return 24000;
                case 7: return 22050;
                case 8: return 16000;
                case 9: return 12000;
                case 10: return 11025;
                case 11: return 8000;
                case 12: return 7350;
                default: return 48000;
            }
        }

        private void queueEncodedFrame(byte[] data, int offset, int length, long ptsUs) {
            if (codec == null) {
                return;
            }
            try {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        int copyLen = Math.min(length, inBuf.remaining());
                        inBuf.put(data, offset, copyLen);
                        codec.queueInputBuffer(inIdx, 0, copyLen, ptsUs, 0);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "queueEncodedFrame error: " + t.getMessage());
            }
        }

        private void drainDecoderOutputs() {
            if (codec == null) {
                return;
            }
            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (isTokenActive(token)) {
                    int outIdx = codec.dequeueOutputBuffer(info, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFmt = codec.getOutputFormat();
                        int sr = outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                ? outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                        int ch = outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                ? outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                        int normCh = Math.min(2, Math.max(1, ch));
                        if (sr != sampleRate || normCh != channelCount || audioTrack == null) {
                            if (audioTrack != null) {
                                try {
                                    audioTrack.stop();
                                    audioTrack.release();
                                } catch (Throwable ignored) {
                                }
                            }
                            sampleRate = sr;
                            channelCount = normCh;
                            audioTrack = createPcmAudioTrack(sr, normCh);
                        }
                    } else if (outIdx >= 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        if (outBuf != null && info.size > 0 && audioTrack != null) {
                            byte[] pcm = new byte[info.size];
                            outBuf.position(info.offset);
                            outBuf.get(pcm, 0, info.size);
                            audioTrack.write(pcm, 0, pcm.length);
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                    } else {
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Decode cycle error: " + t.getMessage());
            }
        }

        void releaseNow() {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
                codec = null;
            }
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                } catch (Throwable ignored) {
                }
                try {
                    audioTrack.release();
                } catch (Throwable ignored) {
                }
                audioTrack = null;
            }
        }
    }

    private static AudioTrack createPcmAudioTrack(int sampleRate, int channelCount) {
        int channelConfig = channelCount == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, 32768);
        AudioTrack track;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    bufSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        } else {
            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM);
        }
        track.play();
        return track;
    }

    private static AudioTrack createPassthroughAudioTrack(int sampleRate, int encoding) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(encoding)
                            .build(),
                    32768,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
            return track;
        }
        return null;
    }
}
