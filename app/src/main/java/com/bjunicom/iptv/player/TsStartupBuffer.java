package com.bjunicom.iptv.player;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Bounded neighbour-channel replay. A snapshot requires PAT/PMT and an IDR with
 * repeated parameter sets in the same PES; unknown layouts remain live-only.
 */
public final class TsStartupBuffer {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final long MAX_AGE_MS = 1500;
    private final Deque<Entry> packets = new ArrayDeque<>();
    private byte[] pat;
    private byte[] pmt;
    private int pmtPid = -1;
    private int videoPid = -1;
    private int audioPid = -1;
    private int videoType;
    private long nextIndex;
    private long videoPesIndex = -1;
    private long videoPts = -1;
    private long safeIndex = -1;
    private long safePts = -1;
    private long safeAtMs;
    private int nalWindow;
    private int parameterSets;
    private final int[] continuity = new int[8192];
    private int pmtCrc;

    public TsStartupBuffer() { Arrays.fill(continuity, -1); }

    private static final class Entry {
        final long index;
        final byte[] bytes;
        Entry(long index, byte[] bytes) { this.index = index; this.bytes = bytes; }
    }

    public void discontinuity() {
        packets.clear();
        safeIndex = videoPesIndex = -1;
        parameterSets = nalWindow = 0;
        Arrays.fill(continuity, -1);
    }

    public void append(byte[] data, int offset, int length, long nowMs) {
        for (int p = offset; p + 188 <= offset + length; p += 188) {
            if ((data[p] & 0xff) != 0x47 || (data[p + 1] & 0x80) != 0
                    || (data[p + 3] & 0xc0) != 0) {
                discontinuity();
                continue;
            }
            byte[] packet = Arrays.copyOfRange(data, p, p + 188);
            int pid = pid(packet);
            if (pid == 8191) continue; // Null-packet continuity is not defined for programme timing.
            int payload = payloadOffset(packet);
            boolean start = (packet[1] & 0x40) != 0;
            if ((packet[3] & 0x20) != 0 && (packet[4] & 0xff) > 0
                    && (packet[5] & 0x80) != 0) discontinuity();
            if (payload >= 188) continue;
            int cc = packet[3] & 15;
            if (continuity[pid] == cc) continue;
            if (continuity[pid] >= 0 && cc != ((continuity[pid] + 1) & 15)) discontinuity();
            continuity[pid] = cc;
            if (start && (pid == 0 || pid == pmtPid)) readPsi(packet, pid, payload);
            long index = nextIndex++;
            packets.addLast(new Entry(index, packet));
            while (packets.size() * 188 > MAX_BYTES) packets.removeFirst();
            if (pid != videoPid) continue;
            if (start) {
                videoPesIndex = index;
                videoPts = pesPts(packet, payload);
                parameterSets = nalWindow = 0;
                if (payload + 9 <= 188 && packet[payload] == 0 && packet[payload + 1] == 0
                        && packet[payload + 2] == 1) {
                    payload += 9 + (packet[payload + 8] & 0xff);
                }
            }
            for (int b = payload; b < 188; b++) {
                nalWindow = (nalWindow << 8) | (packet[b] & 0xff);
                if ((nalWindow & 0xffffff00) != 0x00000100) continue;
                int type = videoType == 0x24
                        ? (packet[b] & 0x7e) >> 1 : packet[b] & 0x1f;
                if (videoType == 0x1b) {
                    if (type == 7) parameterSets |= 1;
                    if (type == 8) parameterSets |= 2;
                    if (type == 5 && parameterSets == 3) markSafe(nowMs);
                } else if (videoType == 0x24) {
                    if (type == 32) parameterSets |= 1;
                    if (type == 33) parameterSets |= 2;
                    if (type == 34) parameterSets |= 4;
                    if ((type == 19 || type == 20) && parameterSets == 7) markSafe(nowMs);
                }
            }
        }
    }

    private void markSafe(long nowMs) {
        if (videoPesIndex >= 0 && videoPts >= 0) {
            safeIndex = videoPesIndex;
            safePts = videoPts;
            safeAtMs = nowMs;
        }
    }

    public byte[] snapshot(long nowMs) {
        if (pat == null || pmt == null || safeIndex < 0 || packets.isEmpty()
                || packets.peekFirst().index > safeIndex || nowMs - safeAtMs > MAX_AGE_MS) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(pat, 0, pat.length);
        output.write(pmt, 0, pmt.length);
        boolean audioStarted = false;
        for (Entry entry : packets) {
            if (entry.index < safeIndex) continue;
            byte[] packet = entry.bytes;
            if (pid(packet) == audioPid) {
                if ((packet[1] & 0x40) != 0) {
                    long pts = pesPts(packet, payloadOffset(packet));
                    long delta = (pts - safePts) & ((1L << 33) - 1);
                    audioStarted = pts >= 0 && delta < (1L << 32);
                }
                if (!audioStarted) continue;
            }
            output.write(packet, 0, packet.length);
        }
        return output.toByteArray();
    }

    private void readPsi(byte[] packet, int pid, int payload) {
        int section = payload + 1 + (packet[payload] & 0xff);
        if (section + 12 > 188) return;
        int length = ((packet[section + 1] & 0x0f) << 8) | (packet[section + 2] & 0xff);
        int end = section + 3 + length;
        // Never replay a partial section, encrypted packet or a corrupt programme map.
        if (end > 188) {
            if (pid == 0) pat = null;
            else pmt = null;
            discontinuity();
            return;
        }
        if (!crcValid(packet, section, end)) {
            if (pid == 0) pat = null;
            else pmt = null;
            discontinuity();
            return;
        }
        if ((packet[section + 5] & 1) == 0) return; // A future table is not the current programme map.
        if (pid == 0 && packet[section] == 0) {
            for (int p = section + 8; p + 4 <= end - 4; p += 4) {
                int program = ((packet[p] & 0xff) << 8) | (packet[p + 1] & 0xff);
                if (program == 0) continue;
                int newPid = ((packet[p + 2] & 0x1f) << 8) | (packet[p + 3] & 0xff);
                if (pmtPid != newPid) {
                    discontinuity();
                    pmt = null;
                    videoPid = audioPid = -1;
                    pmtPid = newPid;
                }
                pat = packet;
                break;
            }
        } else if (pid == pmtPid && packet[section] == 2) {
            int infoLength = ((packet[section + 10] & 0x0f) << 8) | (packet[section + 11] & 0xff);
            int newVideo = -1;
            int newAudio = -1;
            int newType = 0;
            for (int p = section + 12 + infoLength; p + 5 <= end - 4;) {
                int type = packet[p] & 0xff;
                int esPid = ((packet[p + 1] & 0x1f) << 8) | (packet[p + 2] & 0xff);
                int esInfoLength = ((packet[p + 3] & 0x0f) << 8) | (packet[p + 4] & 0xff);
                if (p + 5 + esInfoLength > end - 4) return;
                if (newVideo < 0 && (type == 0x1b || type == 0x24)) {
                    newVideo = esPid;
                    newType = type;
                } else if (newAudio < 0 && (type == 3 || type == 4 || type == 0x0f
                        || type == 0x11 || type == 0x81 || type == 0x87)) newAudio = esPid;
                p += 5 + esInfoLength;
            }
            int crc = 0;
            for (int p = end - 4; p < end; p++) crc = (crc << 8) | (packet[p] & 0xff);
            if (newVideo != videoPid || newAudio != audioPid || newType != videoType || crc != pmtCrc) {
                discontinuity();
            }
            pmtCrc = crc;
            videoPid = newVideo;
            videoType = newType;
            audioPid = newAudio;
            pmt = packet;
        }
    }

    private static int pid(byte[] packet) {
        return ((packet[1] & 0x1f) << 8) | (packet[2] & 0xff);
    }

    private static int payloadOffset(byte[] packet) {
        int control = (packet[3] >> 4) & 3;
        if (control == 0 || control == 2) return 188;
        return control == 3 ? Math.min(188, 5 + (packet[4] & 0xff)) : 4;
    }

    private static long pesPts(byte[] packet, int p) {
        if (p + 14 > 188 || packet[p] != 0 || packet[p + 1] != 0 || packet[p + 2] != 1
                || (packet[p + 7] & 0x80) == 0 || (packet[p + 8] & 0xff) < 5
                || (packet[p + 9] & packet[p + 11] & packet[p + 13] & 1) == 0) return -1;
        return ((long) (packet[p + 9] & 0x0e) << 29)
                | ((long) (packet[p + 10] & 0xff) << 22)
                | ((long) (packet[p + 11] & 0xfe) << 14)
                | ((long) (packet[p + 12] & 0xff) << 7)
                | ((packet[p + 13] & 0xfe) >> 1);
    }

    private static boolean crcValid(byte[] bytes, int start, int end) {
        int crc = -1;
        for (int i = start; i < end; i++) {
            crc ^= (bytes[i] & 0xff) << 24;
            for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        return crc == 0;
    }
}
