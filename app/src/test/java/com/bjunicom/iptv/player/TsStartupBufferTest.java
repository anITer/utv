package com.bjunicom.iptv.player;

import org.junit.Test;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TsStartupBufferTest {
    @Test public void replaysTablesAndIdrButDropsAudioBeforeVideoPts() {
        TsStartupBuffer buffer = new TsStartupBuffer();
        byte[] pat = TsFixtures.pat();
        byte[] pmt = TsFixtures.pmt(false);
        byte[] idr = TsFixtures.h264Idr();
        byte[] oldAudio = TsFixtures.pes(TsFixtures.AUDIO_PID, 87840, new byte[]{1});
        byte[] currentAudio = TsFixtures.pes(TsFixtures.AUDIO_PID, 90000, new byte[]{2});
        currentAudio[3] = 0x11;
        byte[] input = TsFixtures.concat(pat, pmt, idr, oldAudio, currentAudio);
        buffer.append(input, 0, input.length, 100);
        assertArrayEquals(TsFixtures.concat(pat, pmt, idr, currentAudio), buffer.snapshot(100));
        assertNull(buffer.snapshot(1601));
    }

    @Test public void nonIdrAndIdrWithoutParameterSetsAreNotStartupPoints() {
        TsStartupBuffer buffer = new TsStartupBuffer();
        byte[] input = TsFixtures.concat(TsFixtures.pat(), TsFixtures.pmt(false),
                TsFixtures.pes(TsFixtures.VIDEO_PID, 90000, new byte[]{0, 0, 1, 0x61, 1}));
        buffer.append(input, 0, input.length, 0);
        assertNull(buffer.snapshot(0));
        byte[] missingParameters = TsFixtures.pes(TsFixtures.VIDEO_PID, 92000,
                new byte[]{0, 0, 1, 0x65, 1});
        missingParameters[3] = 0x11;
        buffer.append(missingParameters, 0, 188, 0);
        assertNull(buffer.snapshot(0));
    }

    @Test public void packetLossOrEvictedKeyframeInvalidatesReplay() {
        TsStartupBuffer buffer = new TsStartupBuffer();
        byte[] input = TsFixtures.concat(TsFixtures.pat(), TsFixtures.pmt(false), TsFixtures.h264Idr());
        buffer.append(input, 0, input.length, 0);
        assertNotNull(buffer.snapshot(0));
        buffer.discontinuity();
        assertNull(buffer.snapshot(0));
        buffer.append(input, 0, input.length, 0);
        byte[] filler = TsFixtures.packet(1000, false, new byte[]{1});
        for (int i = 0; i <= TsStartupBuffer.MAX_BYTES / 188; i++) {
            filler[3] = (byte) (0x10 | (i & 15));
            buffer.append(filler, 0, 188, 0);
        }
        assertNull(buffer.snapshot(0));
    }

    @Test public void corruptProgrammeMapIsNeverReplayed() {
        byte[] corruptPmt = TsFixtures.pmt(false);
        corruptPmt[20] ^= 1;
        TsStartupBuffer buffer = new TsStartupBuffer();
        byte[] input = TsFixtures.concat(TsFixtures.pat(), corruptPmt, TsFixtures.h264Idr());
        buffer.append(input, 0, input.length, 0);
        assertNull(buffer.snapshot(0));
    }

    @Test public void corruptUpdatedMapInvalidatesPreviouslyValidReplay() {
        TsStartupBuffer buffer = new TsStartupBuffer();
        byte[] input = TsFixtures.concat(TsFixtures.pat(), TsFixtures.pmt(false), TsFixtures.h264Idr());
        buffer.append(input, 0, input.length, 0);
        assertNotNull(buffer.snapshot(0));
        byte[] corrupt = TsFixtures.pmt(false);
        corrupt[3] = 0x11;
        corrupt[20] ^= 1;
        buffer.append(corrupt, 0, corrupt.length, 0);
        assertNull(buffer.snapshot(0));
    }

    @Test public void adaptationOnlyDiscontinuityInvalidatesReplayButNullPacketDoesNot() {
        TsStartupBuffer buffer = new TsStartupBuffer();
        byte[] input = TsFixtures.concat(TsFixtures.pat(), TsFixtures.pmt(false), TsFixtures.h264Idr());
        buffer.append(input, 0, input.length, 0);
        byte[] nullPacket = TsFixtures.packet(8191, false, new byte[]{1});
        nullPacket[3] = 0x19;
        buffer.append(nullPacket, 0, 188, 0);
        assertNotNull(buffer.snapshot(0));
        byte[] discontinuity = TsFixtures.packet(TsFixtures.VIDEO_PID, false, new byte[]{1});
        discontinuity[3] = 0x20;
        discontinuity[4] = (byte) 183;
        discontinuity[5] = (byte) 0x80;
        buffer.append(discontinuity, 0, 188, 0);
        assertNull(buffer.snapshot(0));
    }

    @Test public void hevcRequiresParameterSetsAndIdrRatherThanCra() {
        TsStartupBuffer buffer = new TsStartupBuffer();
        byte[] cra = TsFixtures.pes(TsFixtures.VIDEO_PID, 90000, new byte[]{
                0,0,1,64,1, 0,0,1,66,1, 0,0,1,68,1, 0,0,1,42,1});
        byte[] input = TsFixtures.concat(TsFixtures.pat(), TsFixtures.pmt(true), cra);
        buffer.append(input, 0, input.length, 0);
        assertNull(buffer.snapshot(0));
        byte[] idr = Arrays.copyOf(cra, cra.length);
        idr[36] = 38; // HEVC IDR_W_RADL, after VPS/SPS/PPS in this PES.
        idr[3] = 0x11;
        buffer.append(idr, 0, 188, 0);
        assertNotNull(buffer.snapshot(0));
    }
}
