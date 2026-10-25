package com.bjunicom.iptv.player;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class MulticastPreloaderTest {
    private static final String ACTIVE = "239.3.1.1:8008";
    private static final String NEIGHBOUR = "239.3.1.2:8009";

    @Test public void handoffTransfersTheSocketAndSafeStartupBytes() throws Exception {
        FakeSocket socket = new FakeSocket();
        byte[] input = TsFixtures.concat(TsFixtures.pat(), TsFixtures.pmt(false), TsFixtures.h264Idr());
        socket.packets.add(input);
        MulticastPreloader preloader = preloader(socket, 3000);
        try {
            preloader.preload(NEIGHBOUR, ACTIVE);
            assertTrue(socket.received.await(1, TimeUnit.SECONDS));
            MulticastPreloader.Lease lease = preloader.take(NEIGHBOUR);
            assertNotNull(lease);
            assertSame(socket, lease.socket);
            assertArrayEquals(input, lease.startupBytes);
            assertNull(preloader.take(NEIGHBOUR));
            preloader.close();
            assertFalse(socket.wasClosed);
            lease.close();
            assertTrue(socket.wasClosed);
        } finally { preloader.close(); socket.close(); }
    }

    @Test public void schedulingNextNeighbourWaitsForForegroundHandoff() throws Exception {
        FakeSocket warm = new FakeSocket();
        FakeSocket next = new FakeSocket();
        MulticastPreloader preloader = new MulticastPreloader((target, timeout) -> {
            FakeSocket socket = target.equals(NEIGHBOUR) ? warm : next;
            MulticastPreloader.Lease lease = new MulticastPreloader.Lease(socket,
                    InetAddress.getByName(target.substring(0, target.indexOf(':'))),
                    Integer.parseInt(target.substring(target.indexOf(':') + 1)), Collections.emptyList());
            socket.opened.countDown();
            return lease;
        }, 3000);
        try {
            preloader.preload(NEIGHBOUR, ACTIVE);
            assertTrue(warm.opened.await(1, TimeUnit.SECONDS));
            preloader.preload("239.3.1.3:8010", NEIGHBOUR);
            assertFalse(warm.wasClosed);
            assertEquals(1, next.opened.getCount());
            MulticastPreloader.Lease lease = preloader.take(NEIGHBOUR);
            assertNotNull(lease);
            assertSame(warm, lease.socket);
            assertTrue(next.opened.await(1, TimeUnit.SECONDS));
            lease.close();
            preloader.stop();
            assertTrue(next.closed.await(1, TimeUnit.SECONDS));
        } finally { preloader.close(); warm.close(); next.close(); }
    }

    @Test public void cancellationAndExpiryCloseTheUnclaimedSocket() throws Exception {
        for (boolean cancel : new boolean[]{true, false}) {
            FakeSocket socket = new FakeSocket();
            MulticastPreloader preloader = preloader(socket, cancel ? 3000 : 80);
            try {
                preloader.preload(NEIGHBOUR, ACTIVE);
                assertTrue(socket.opened.await(1, TimeUnit.SECONDS));
                if (cancel) preloader.stop();
                assertTrue(socket.closed.await(1, TimeUnit.SECONDS));
                assertNull(preloader.take(NEIGHBOUR));
            } finally { preloader.close(); socket.close(); }
        }
    }

    @Test public void samePortOrSameChannelNeverOpensASecondReceiver() {
        AtomicInteger opens = new AtomicInteger();
        MulticastPreloader preloader = new MulticastPreloader((target, timeout) -> {
            opens.incrementAndGet();
            throw new IOException("Unexpected preload");
        }, 3000);
        try {
            preloader.preload("239.3.1.2:8008", ACTIVE);
            preloader.preload(ACTIVE, ACTIVE);
            preloader.preload(NEIGHBOUR, null);
            assertEquals(0, opens.get());
        } finally { preloader.close(); }
    }

    private static MulticastPreloader preloader(FakeSocket socket, long ttl) {
        return new MulticastPreloader((target, timeout) -> {
            MulticastPreloader.Lease lease = new MulticastPreloader.Lease(socket,
                    InetAddress.getByName("239.3.1.2"), 8009, Collections.emptyList());
            socket.opened.countDown();
            return lease;
        }, ttl);
    }

    private static final class FakeSocket extends MulticastSocket {
        final LinkedBlockingQueue<byte[]> packets = new LinkedBlockingQueue<>();
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean wasClosed;
        FakeSocket() throws IOException { super(null); }
        @Override public void receive(DatagramPacket packet) throws IOException {
            try {
                if (wasClosed) throw new SocketException("Closed");
                byte[] data = packets.poll(30, TimeUnit.MILLISECONDS);
                if (data == null) throw new SocketTimeoutException();
                System.arraycopy(data, 0, packet.getData(), packet.getOffset(), data.length);
                packet.setLength(data.length);
                received.countDown();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new SocketException("Interrupted");
            }
        }
        @Override public void close() { wasClosed = true; closed.countDown(); super.close(); }
    }
}
