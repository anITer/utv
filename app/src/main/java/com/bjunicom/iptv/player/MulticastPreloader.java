package com.bjunicom.iptv.player;

import android.os.SystemClock;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;
import com.bjunicom.iptv.network.RtpTsPacketParser;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One short-lived, decoder-free neighbour receiver. Ownership transfers to the playback loader. */
final class MulticastPreloader {
    private static final long IDLE_TIMEOUT_MS = 3000;
    interface SocketOpener { Lease open(String target, int timeoutMs) throws IOException; }
    private final SocketOpener opener;
    private final long idleTimeoutMs;

    MulticastPreloader() { this(MulticastPreloader::open, IDLE_TIMEOUT_MS); }

    MulticastPreloader(SocketOpener opener, long idleTimeoutMs) {
        this.opener = opener;
        this.idleTimeoutMs = idleTimeoutMs;
    }
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "UTV-preload-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private WarmStream background;
    private String pendingTarget;
    private String pendingActiveTarget;
    private boolean closed;

    static final class Lease {
        final MulticastSocket socket;
        final InetAddress group;
        final int port;
        final List<NetworkInterface> interfaces;
        byte[] startupBytes;

        Lease(MulticastSocket socket, InetAddress group, int port, List<NetworkInterface> interfaces) {
            this.socket = socket;
            this.group = group;
            this.port = port;
            this.interfaces = interfaces;
        }

        void close() { socket.close(); }
    }

    static Lease open(String target, int timeoutMs) throws IOException {
        String[] parts = target.split(":");
        InetAddress group = InetAddress.getByName(parts[0]);
        int port = Integer.parseInt(parts[1]);
        if (!group.isMulticastAddress()) throw new IOException("Not a multicast address: " + target);
        MulticastSocket socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true);
            LocalMulticastProxyServer.bindMulticastSocketWithGroupFilter(socket, group, port);
            socket.setReceiveBufferSize(2 * 1024 * 1024);
            socket.setSoTimeout(timeoutMs);
            return new Lease(socket, group, port,
                    LocalMulticastProxyServer.joinMulticastOnAllInterfaces(socket, group, port));
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
    }

    synchronized void preload(String target, String activeTarget) {
        // Wildcard-bound Java sockets cannot filter the destination group on every Android kernel.
        // Do not join two different groups sharing a port until destination filtering is available.
        boolean eligible = !closed && target != null && activeTarget != null
                && !target.equals(activeTarget) && !port(target).equals(port(activeTarget));
        if (background != null && background.target.equals(activeTarget) && !background.closed) {
            // The new foreground loader has not claimed its warm socket yet. Do not close it
            // when the UI immediately requests the next neighbour after setMediaSource().
            pendingTarget = eligible ? target : null;
            pendingActiveTarget = activeTarget;
            return;
        }
        if (!eligible) {
            stop();
            return;
        }
        if (background != null && background.target.equals(target) && !background.closed) return;
        stop();
        WarmStream stream = new WarmStream(target);
        background = stream;
        stream.worker.start();
        expiry.schedule(() -> {
            synchronized (MulticastPreloader.this) {
                if (background == stream) stop();
            }
        }, idleTimeoutMs, TimeUnit.MILLISECONDS);
    }

    Lease take(String target) {
        WarmStream stream;
        synchronized (this) {
            stream = background;
            if (stream == null || !stream.target.equals(target) || stream.closed) return null;
            background = null;
            stream.claimed = true;
        }
        try {
            return claim(stream);
        } finally {
            synchronized (this) {
                if (!closed && background == null && pendingTarget != null) {
                    String targetToPreload = pendingTarget;
                    String activeTarget = pendingActiveTarget;
                    pendingTarget = pendingActiveTarget = null;
                    preload(targetToPreload, activeTarget);
                }
            }
        }
    }

    private Lease claim(WarmStream stream) {
        try {
            stream.worker.join(250); // Runs on Exo's loading thread, never on the UI thread.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stream.close();
            return null;
        }
        if (stream.worker.isAlive() || stream.lease == null || stream.closed) {
            stream.close();
            return null;
        }
        Lease lease = stream.lease;
        lease.startupBytes = stream.buffer.snapshot(SystemClock.elapsedRealtime());
        try {
            lease.socket.setSoTimeout(4000);
            return lease;
        } catch (IOException error) {
            lease.close();
            return null;
        }
    }

    synchronized void stop() {
        pendingTarget = pendingActiveTarget = null;
        if (background != null) {
            background.close();
            background = null;
        }
    }

    synchronized void close() {
        closed = true;
        stop();
        expiry.shutdownNow();
    }

    private static String port(String target) { return target.substring(target.lastIndexOf(':') + 1); }

    private final class WarmStream {
        final String target;
        final TsStartupBuffer buffer = new TsStartupBuffer();
        final Thread worker;
        volatile Lease lease;
        volatile boolean claimed;
        volatile boolean closed;

        WarmStream(String target) {
            this.target = target;
            worker = new Thread(this::receive, "UTV-neighbour-receiver");
            worker.setDaemon(true);
        }

        void close() {
            closed = true;
            Lease current = lease;
            if (current != null) current.close();
            worker.interrupt();
        }

        void receive() {
            try {
                lease = opener.open(target, 100);
                byte[] bytes = new byte[65535];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                RtpTsPacketParser parser = new RtpTsPacketParser();
                while (!closed && !claimed) {
                    try {
                        packet.setLength(bytes.length);
                        lease.socket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    }
                    RtpTsPacketParser.Payload payload = parser.parse(bytes, packet.getLength());
                    if (payload == null) continue;
                    if (payload.discontinuity) buffer.discontinuity();
                    buffer.append(bytes, payload.offset, payload.length, SystemClock.elapsedRealtime());
                }
            } catch (IOException | RuntimeException ignored) {
                closed = true;
            } finally {
                if (closed || !claimed) {
                    Lease current = lease;
                    if (current != null) current.close();
                }
            }
        }
    }
}
