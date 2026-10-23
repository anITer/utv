package com.bjunicom.iptv.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 针对 Android TV 的 MPEG-TS 硬件音频伴音解码器 (与 StreamDownloaderPool 及 CodecDecoderPool 深度协同)
 *
 * 优化点：
 * 1. 下载与解码彻底分离：直接通过 StreamDownloaderPool.PooledStreamDownloader 的 AudioPacketTap 旁路回调
 *    接收已对齐关键帧的 188 字节 TS 音频包，不再重复建立第二条 HTTP / IGMP 组播网络连接；
 * 2. 硬件解码器池化复用：通过 CodecDecoderPool 按音频 MIME (audio/eac3, audio/ac3, audio/mpeg-L2) 获取和归还
 *    已初始化的 MediaCodec + AudioTrack，切台时仅需 flush() 即可复用，避免每次销毁重建。
 */
public class TsHardwareAudioCompanion {
    private static final String TAG = "TsHwAudioCompanion";
    private static final Pattern MCAST_PATTERN = Pattern.compile("(?:rtp|udp)://([0-9.]+):(\\d+)");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;
    private volatile String activeCodecLabel = "";
    private volatile StreamDownloaderPool.PooledStreamDownloader boundDownloader;

    public String getActiveCodecLabel() {
        return activeCodecLabel;
    }

    /**
     * 零重复连接模式：直接挂载到池化下载器 (PooledStreamDownloader) 接收已对齐关键帧的音频 TS 包，
     * 并根据解析出的 TsCodecDescriptor 从 CodecDecoderPool 获取对应的硬件音频解码器。
     */
    public synchronized void startWithDownloader(
            final StreamDownloaderPool.PooledStreamDownloader downloader,
            final TsCodecDescriptor descriptor) {
        stop();
        if (downloader == null) {
            return;
        }
        boundDownloader = downloader;
        running.set(true);
        final BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>(512);
        downloader.setAudioTeeListener((tsPacketBuf, offset) -> {
            if (!running.get()) {
                return;
            }
            int pid = ((tsPacketBuf[offset + 1] & 0x1F) << 8) | (tsPacketBuf[offset + 2] & 0xFF);
            if (pid == 0x0000
                    || (descriptor != null && (pid == descriptor.pmtPid || pid == descriptor.audioPid))
                    || descriptor == null
                    || descriptor.audioPid <= 0) {
                byte[] pktCopy = new byte[188];
                System.arraycopy(tsPacketBuf, offset, pktCopy, 0, 188);
                if (!audioQueue.offer(pktCopy)) {
                    audioQueue.poll();
                    audioQueue.offer(pktCopy);
                }
            }
        });

        workerThread = new Thread(() -> runDownloaderTapLoop(audioQueue, descriptor), "TsHwAudioTapWorker");
        workerThread.setPriority(Thread.MAX_PRIORITY - 1);
        workerThread.start();
    }

    public synchronized void start(final Uri playbackUri, final String rawChannelUrl) {
        stop();
        if (playbackUri == null) {
            return;
        }
        running.set(true);
        workerThread = new Thread(() -> runStandaloneLoop(playbackUri.toString(), rawChannelUrl), "TsHwAudioCompanion");
        workerThread.setPriority(Thread.MAX_PRIORITY - 1);
        workerThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        activeCodecLabel = "";
        if (boundDownloader != null) {
            boundDownloader.setAudioTeeListener(null);
            boundDownloader = null;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void runDownloaderTapLoop(BlockingQueue<byte[]> audioQueue, TsCodecDescriptor descriptor) {
        DecoderSession session = new DecoderSession();
        if (descriptor != null && descriptor.audioPid > 0) {
            session.pmtPid = descriptor.pmtPid;
            session.audioPid = descriptor.audioPid;
            session.mimeType = mapAudioCodecToMime(descriptor.audioCodec);
        }
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                byte[] pkt = audioQueue.poll(200, TimeUnit.MILLISECONDS);
                if (pkt == null) {
                    continue;
                }
                processTsPacket(pkt, 0, session);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            if (running.get()) {
                Log.w(TAG, "Hardware audio tap worker ended: " + t.getMessage());
            }
        } finally {
            session.releaseToPool();
        }
    }

    private static String mapAudioCodecToMime(String audioCodec) {
        if ("eac3".equalsIgnoreCase(audioCodec)) {
            return MediaFormat.MIMETYPE_AUDIO_EAC3;
        } else if ("ac3".equalsIgnoreCase(audioCodec)) {
            return MediaFormat.MIMETYPE_AUDIO_AC3;
        } else if ("aac".equalsIgnoreCase(audioCodec)) {
            return MediaFormat.MIMETYPE_AUDIO_AAC;
        } else if ("mp2".equalsIgnoreCase(audioCodec)) {
            return "audio/mpeg-L2";
        }
        return MediaFormat.MIMETYPE_AUDIO_EAC3;
    }

    private void runStandaloneLoop(String uriStr, String rawChannelUrl) {
        HttpURLConnection conn = null;
        InputStream in = null;
        MulticastSocket mcastSocket = null;
        InetAddress mcastGroup = null;
        int mcastPort = -1;
        List<NetworkInterface> joinedInterfaces = null;
        DecoderSession decoderSession = new DecoderSession();

        try {
            if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                URL url = new URL(uriStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                conn.connect();
                in = conn.getInputStream();
                demuxHttpTsStream(in, decoderSession);
            } else {
                String target = (rawChannelUrl != null && !rawChannelUrl.isEmpty()) ? rawChannelUrl : uriStr;
                Matcher m = MCAST_PATTERN.matcher(target);
                if (m.find()) {
                    String ip = m.group(1);
                    mcastPort = Integer.parseInt(m.group(2));
                    mcastGroup = InetAddress.getByName(ip);
                    mcastSocket = new MulticastSocket(mcastPort);
                    mcastSocket.setReuseAddress(true);
                    mcastSocket.setReceiveBufferSize(512 * 1024);
                    mcastSocket.setSoTimeout(4000);
                    joinedInterfaces = LocalMulticastProxyServer.joinMulticastOnAllInterfaces(
                            mcastSocket, mcastGroup, mcastPort);
                    demuxMulticastTsStream(mcastSocket, decoderSession);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                Log.w(TAG, "Hardware audio companion ended: " + e.getMessage());
            }
        } finally {
            decoderSession.releaseToPool();
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
                mcastSocket.close();
            }
        }
    }

    private void demuxHttpTsStream(InputStream in, DecoderSession session) throws Exception {
        byte[] readBuf = new byte[188 * 32];
        byte[] carry = new byte[188 * 2];
        int carryLen = 0;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
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
            while (pos + 188 <= totalLen && running.get()) {
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

    private void demuxMulticastTsStream(MulticastSocket socket, DecoderSession session) throws Exception {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (running.get() && !Thread.currentThread().isInterrupted()) {
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
            for (int pos = payloadOffset; pos + 188 <= offset + len; pos += 188) {
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
            session.acquireDecoderFromPool();
        }
        session.feedElementaryStream(pes, esStart, esLen);
    }

    private class DecoderSession {
        int pmtPid = -1;
        int audioPid = -1;
        String mimeType = null;
        boolean collectingPes = false;
        final ByteArrayOutputStream pesBuffer = new ByteArrayOutputStream(8192);

        boolean initialized = false;
        CodecDecoderPool.PooledHwAudioDecoder pooledSlot = null;
        boolean healthy = true;
        long presentationTimeUs = 0L;

        void acquireDecoderFromPool() {
            initialized = true;
            if (mimeType == null) {
                return;
            }
            pooledSlot = CodecDecoderPool.getInstance().acquireHwAudioDecoder(mimeType);
            if (pooledSlot != null) {
                activeCodecLabel = pooledSlot.label;
            }
        }

        void feedElementaryStream(byte[] data, int offset, int length) {
            if (pooledSlot == null) {
                return;
            }
            if (pooledSlot.passthroughMode && pooledSlot.audioTrack != null) {
                pooledSlot.audioTrack.write(data, offset, length);
                return;
            }
            MediaCodec codec = pooledSlot.codec;
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
                        codec.queueInputBuffer(inIdx, 0, copyLen, presentationTimeUs, 0);
                        presentationTimeUs += 24000;
                    }
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (running.get()) {
                    int outIdx = codec.dequeueOutputBuffer(info, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFmt = codec.getOutputFormat();
                        int sr = outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                ? outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                        int ch = outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                ? outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                        int normCh = Math.min(2, Math.max(1, ch));
                        if (sr != pooledSlot.sampleRate || normCh != pooledSlot.channelCount || pooledSlot.audioTrack == null) {
                            if (pooledSlot.audioTrack != null) {
                                try {
                                    pooledSlot.audioTrack.stop();
                                    pooledSlot.audioTrack.release();
                                } catch (Throwable ignored) {
                                }
                            }
                            pooledSlot.sampleRate = sr;
                            pooledSlot.channelCount = normCh;
                            pooledSlot.audioTrack = CodecDecoderPool.createPcmAudioTrack(sr, normCh);
                        }
                    } else if (outIdx >= 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        if (outBuf != null && info.size > 0 && pooledSlot.audioTrack != null) {
                            byte[] pcm = new byte[info.size];
                            outBuf.position(info.offset);
                            outBuf.get(pcm, 0, info.size);
                            pooledSlot.audioTrack.write(pcm, 0, pcm.length);
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                    } else {
                        break;
                    }
                }
            } catch (Throwable t) {
                healthy = false;
                Log.w(TAG, "Decode cycle error: " + t.getMessage());
            }
        }

        void releaseToPool() {
            if (pooledSlot != null) {
                CodecDecoderPool.getInstance().releaseHwAudioDecoder(pooledSlot, healthy);
                pooledSlot = null;
            }
        }
    }
}
