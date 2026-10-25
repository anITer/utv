package com.bjunicom.iptv.player;

import java.util.Arrays;

final class TsFixtures {
    static final int PMT_PID = 256;
    static final int VIDEO_PID = 257;
    static final int AUDIO_PID = 258;

    static byte[] packet(int pid, boolean start, byte[] payload) {
        byte[] packet = new byte[188];
        Arrays.fill(packet, (byte) 0xff);
        packet[0] = 0x47;
        packet[1] = (byte) ((start ? 0x40 : 0) | (pid >> 8));
        packet[2] = (byte) pid;
        packet[3] = 0x10;
        System.arraycopy(payload, 0, packet, 4, payload.length);
        return packet;
    }

    static byte[] pat() {
        return psi(0, new byte[]{0, (byte) 0xb0, 13, 0, 1, (byte) 0xc1, 0, 0,
                0, 1, (byte) 0xe1, 0});
    }

    static byte[] pmt(boolean hevc) {
        return psi(PMT_PID, new byte[]{2, (byte) 0xb0, 23, 0, 1, (byte) 0xc1, 0, 0,
                (byte) 0xe1, 1, (byte) 0xf0, 0,
                (byte) (hevc ? 0x24 : 0x1b), (byte) 0xe1, 1, (byte) 0xf0, 0,
                3, (byte) 0xe1, 2, (byte) 0xf0, 0});
    }

    static byte[] psi(int pid, byte[] section) {
        int crc = -1;
        for (byte b : section) {
            crc ^= (b & 0xff) << 24;
            for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
        }
        byte[] payload = new byte[section.length + 5];
        System.arraycopy(section, 0, payload, 1, section.length);
        for (int i = 0; i < 4; i++) payload[section.length + 1 + i] = (byte) (crc >>> (24 - 8 * i));
        return packet(pid, true, payload);
    }

    static byte[] pes(int pid, long pts, byte[] es) {
        byte[] payload = new byte[14 + es.length];
        payload[2] = 1;
        payload[3] = (byte) (pid == VIDEO_PID ? 0xe0 : 0xc0);
        payload[5] = (byte) (8 + es.length);
        payload[6] = (byte) 0x80;
        payload[7] = (byte) 0x80;
        payload[8] = 5;
        payload[9] = (byte) (0x21 | ((pts >> 29) & 0xe));
        payload[10] = (byte) (pts >> 22);
        payload[11] = (byte) (((pts >> 14) & 0xfe) | 1);
        payload[12] = (byte) (pts >> 7);
        payload[13] = (byte) (((pts << 1) & 0xfe) | 1);
        System.arraycopy(es, 0, payload, 14, es.length);
        return packet(pid, true, payload);
    }

    static byte[] h264Idr() {
        return pes(VIDEO_PID, 90000, new byte[]{
                0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 1, 0, 0, 1, 0x65, 1});
    }

    static byte[] concat(byte[]... packets) {
        byte[] result = new byte[Arrays.stream(packets).mapToInt(p -> p.length).sum()];
        int position = 0;
        for (byte[] packet : packets) {
            System.arraycopy(packet, 0, result, position, packet.length);
            position += packet.length;
        }
        return result;
    }
}
