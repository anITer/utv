package com.bjunicom.iptv.player;

import com.bjunicom.iptv.network.RtpTsPacketParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class RtpTsPacketParserTest {
    private byte[] rtp(int sequence) {
        byte[] packet = new byte[12 + 188];
        packet[0] = (byte) 0x80;
        packet[1] = 33;
        packet[2] = (byte) (sequence >> 8);
        packet[3] = (byte) sequence;
        System.arraycopy(TsFixtures.pat(), 0, packet, 12, 188);
        return packet;
    }

    @Test public void dropsDuplicateAndLatePacketsAndDetectsLossAcrossSequenceWrap() {
        RtpTsPacketParser parser = new RtpTsPacketParser();
        byte[] packet = rtp(65535);
        assertFalse(parser.parse(packet, packet.length).discontinuity);
        assertNull(parser.parse(packet, packet.length));
        packet = rtp(0);
        assertFalse(parser.parse(packet, packet.length).discontinuity);
        packet = rtp(2);
        assertTrue(parser.parse(packet, packet.length).discontinuity);
        packet = rtp(1);
        assertNull(parser.parse(packet, packet.length));
    }

    @Test public void senderRestartCanResetSequenceOnSsrcChange() {
        RtpTsPacketParser parser = new RtpTsPacketParser();
        byte[] packet = rtp(100);
        assertNotNull(parser.parse(packet, packet.length));
        packet = rtp(1);
        packet[11] = 1;
        assertTrue(parser.parse(packet, packet.length).discontinuity);
        packet = rtp(2);
        packet[11] = 1;
        assertFalse(parser.parse(packet, packet.length).discontinuity);
    }

    @Test public void invalidTsCannotAdvanceTheRtpSequence() {
        RtpTsPacketParser parser = new RtpTsPacketParser();
        byte[] packet = rtp(10);
        assertNotNull(parser.parse(packet, packet.length));
        packet = rtp(11);
        packet[12] = 0;
        assertNull(parser.parse(packet, packet.length));
        packet = rtp(12);
        assertTrue(parser.parse(packet, packet.length).discontinuity);
    }

    @Test public void validatesExtensionPaddingAndTsPacketBoundaries() {
        byte[] packet = new byte[28 + 188 + 4];
        packet[0] = (byte) 0xb1; // One CSRC, extension and padding.
        packet[1] = 33;
        packet[19] = 2; // Two extension words.
        System.arraycopy(TsFixtures.pat(), 0, packet, 28, 188);
        packet[packet.length - 1] = 4;
        RtpTsPacketParser.Payload payload = new RtpTsPacketParser().parse(packet, packet.length);
        assertNotNull(payload);
        assertEquals(28, payload.offset);
        assertEquals(188, payload.length);
        packet[packet.length - 1] = 0;
        assertNull(new RtpTsPacketParser().parse(packet, packet.length));
        packet[19] = (byte) 0xff;
        assertNull(new RtpTsPacketParser().parse(packet, packet.length));
        assertNull(new RtpTsPacketParser().parse(new byte[187], 187));
    }
}
