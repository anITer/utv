package com.bjunicom.iptv.player;

import android.app.Application;
import android.net.Uri;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class UtvStreamDataSourceTest {
    @Test public void invalidHttpPayloadIsAFormatFailureAndNeverSwitchesToMulticast() throws Exception {
        httpResponseTest(200, false);
    }

    @Test public void forbiddenHttpStatusDoesNotTriggerMulticastFallback() throws Exception {
        httpResponseTest(403, true);
    }

    private void httpResponseTest(int status, boolean statusError) throws Exception {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        server.setSoTimeout(2500);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        Thread responder = new Thread(() -> {
            try (Socket client = server.accept()) {
                client.setSoTimeout(2500);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                byte[] body = new byte[188 * 8];
                String headers = "HTTP/1.1 " + status + " Test\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n";
                client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().write(body);
            } catch (Throwable failure) { serverError.set(failure); }
        });
        responder.setDaemon(true);
        responder.start();
        UTVStreamDataSource.Factory factory = new UTVStreamDataSource.Factory();
        DataSource source = factory.createDataSource();
        try {
            Uri uri = Uri.parse("http://localhost:" + server.getLocalPort() + "/rtp/239.3.1.129:8008");
            try {
                source.open(new DataSpec(uri));
                source.read(new byte[188], 0, 188);
                fail("Invalid HTTP response should fail without native fallback");
            } catch (IOException failure) {
                if (statusError) assertTrue(failure instanceof HttpDataSource.InvalidResponseCodeException);
                else assertEquals(PlaybackFailure.FORMAT, PlaybackFailure.fromException(failure));
            }
            assertFalse(field(source, "usingMulticast").getBoolean(source));
        } finally { source.close(); factory.close(); server.close(); responder.join(1000); }
        assertNull(serverError.get());
    }

    @Test public void switchingChannelsClosesAnActiveBlockedNativeReceiveImmediately() throws Exception {
        UTVStreamDataSource.Factory factory = new UTVStreamDataSource.Factory();
        DataSource source = factory.createDataSource();
        BlockingSocket socket = new BlockingSocket();
        field(source, "usingMulticast").set(source, true);
        field(source, "multicastSocket").set(source, socket);
        Object shared = field(source, "state").get(source);
        @SuppressWarnings("unchecked") Set<UTVStreamDataSource> active =
                (Set<UTVStreamDataSource>) field(shared, "activeSources").get(shared);
        active.add((UTVStreamDataSource) source);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try { source.read(new byte[188], 0, 188); }
            catch (Throwable error) { result.set(error); }
        });
        reader.setDaemon(true);
        try {
            reader.start();
            assertTrue(socket.receiving.await(1, TimeUnit.SECONDS));
            factory.cancelActiveReads();
            reader.join(1000);
            assertFalse(reader.isAlive());
            assertTrue(result.get() instanceof IOException);
            assertTrue(socket.isClosed());
        } finally { socket.close(); source.close(); factory.close(); }
    }

    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class BlockingSocket extends MulticastSocket {
        final CountDownLatch receiving = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        BlockingSocket() throws IOException { super(null); }
        @Override public void receive(DatagramPacket packet) throws IOException {
            receiving.countDown();
            try { closed.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            throw new SocketException("Closed");
        }
        @Override public void close() { closed.countDown(); super.close(); }
    }
}
