package com.bjunicom.iptv.network;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android 端内置轻量级 udpxy (UDP/RTP 组播转 HTTP 单播) 本地代理网关
 *
 * 作用：
 * 当路由器开启了 IGMP Proxy / Snooping 或光猫直连电视盒子，但路由器未运行 udpxy 时，
 * 本服务在 Android 本地监听 http://127.0.0.1:<port>/rtp/239.3.1.x:port，
 * 自动加入对应的 IGMP 组播组，剥离 12 字节 RTP 协议头（含 CSRC 和扩展头），
 * 将纯净的 MPEG-TS (0x47 同步字节) 视频流以 HTTP 方式实时推送给 ijkplayer 播放。
 */
public class LocalMulticastProxyServer {
    private static final String TAG = "LocalMulticastProxy";
    private static final Pattern PATH_PATTERN = Pattern.compile("/(rtp|udp)/([0-9.]+):(\\d+)");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ActiveSession> activeSessions = new CopyOnWriteArrayList<>();
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private int listenPort = -1;

    private static final class ActiveSession {
        final String groupKey;
        final Socket client;
        final MulticastSocket multicastSocket;

        ActiveSession(String groupKey, Socket client, MulticastSocket multicastSocket) {
            this.groupKey = groupKey;
            this.client = client;
            this.multicastSocket = multicastSocket;
        }

        void closeQuietly() {
            try {
                multicastSocket.close();
            } catch (Exception ignored) {
            }
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized int start() {
        if (running.get() && listenPort > 0) {
            return listenPort;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            listenPort = serverSocket.getLocalPort();
            running.set(true);
            executorService = Executors.newCachedThreadPool();
            executorService.execute(this::acceptLoop);
            Log.i(TAG, "Local udpxy proxy started on 127.0.0.1:" + listenPort);
            return listenPort;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start local multicast proxy", e);
            return -1;
        }
    }

    public int getListenPort() {
        return listenPort;
    }

    public synchronized void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        listenPort = -1;
    }

    private void acceptLoop() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                if (executorService != null && !executorService.isShutdown()) {
                    executorService.execute(() -> handleClient(client));
                } else {
                    client.close();
                }
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        MulticastSocket multicastSocket = null;
        InetAddress groupAddr = null;
        int mcastPort = -1;
        NetworkInterface mcastIf = null;
        boolean joinedWithInterface = false;
        ActiveSession currentSession = null;
        try {
            client.setSoTimeout(8000);
            client.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            // 读取并丢弃剩余 HTTP 请求头
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                // consume headers
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String path = parts[1];
            Matcher matcher = PATH_PATTERN.matcher(path);
            if (!matcher.find()) {
                writeHttpError(client.getOutputStream(), 400, "Bad Request");
                return;
            }

            String proto = matcher.group(1);
            String mcastIp = matcher.group(2);
            mcastPort = Integer.parseInt(matcher.group(3));
            String groupKey = mcastIp + ":" + mcastPort;

            // 切台时立即关闭其它频道残留的组播连接，避免同端口(如8000/8008)旧频道串流或占用带宽
            for (ActiveSession session : activeSessions) {
                if (!groupKey.equals(session.groupKey)) {
                    activeSessions.remove(session);
                    session.closeQuietly();
                }
            }

            groupAddr = InetAddress.getByName(mcastIp);
            multicastSocket = new MulticastSocket(null);
            multicastSocket.setReuseAddress(true);
            try {
                // 优先绑定具体组播 IP+端口，防止 Linux 内核将同端口不同组播组的包混入当前 Socket
                multicastSocket.bind(new InetSocketAddress(groupAddr, mcastPort));
            } catch (Exception bindEx) {
                multicastSocket.bind(new InetSocketAddress(mcastPort));
            }
            multicastSocket.setReceiveBufferSize(512 * 1024);
            multicastSocket.setSoTimeout(5000);
            mcastIf = findPreferredMulticastInterface();
            if (mcastIf != null) {
                try {
                    multicastSocket.setNetworkInterface(mcastIf);
                } catch (Exception ignored) {
                }
                try {
                    multicastSocket.joinGroup(new InetSocketAddress(groupAddr, mcastPort), mcastIf);
                    joinedWithInterface = true;
                } catch (Exception ignored) {
                }
            }
            if (!joinedWithInterface) {
                multicastSocket.joinGroup(groupAddr);
            }

            currentSession = new ActiveSession(groupKey, client, multicastSocket);
            activeSessions.add(currentSession);

            OutputStream out = client.getOutputStream();
            String responseHeaders = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: video/mp2t\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n"
                    + "Server: BjUnicomIptv-LocalUdpxy/1.0\r\n\r\n";
            out.write(responseHeaders.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            boolean isRtp = "rtp".equalsIgnoreCase(proto);

            while (running.get() && !client.isClosed()) {
                multicastSocket.receive(packet);
                int len = packet.getLength();
                int offset = packet.getOffset();
                if (len <= 0) {
                    continue;
                }

                // 自动剥离 RTP 协议头，输出 MPEG-TS 188 字节对齐负载
                int payloadOffset = offset;
                int payloadLen = len;
                if (isRtp && len > 12 && (buffer[offset] & 0xFF) != 0x47) {
                    int version = (buffer[offset] & 0xC0) >>> 6;
                    if (version == 2) {
                        int csrcCount = buffer[offset] & 0x0F;
                        boolean hasExtension = (buffer[offset] & 0x10) != 0;
                        boolean hasPadding = (buffer[offset] & 0x20) != 0;
                        int headerSize = 12 + csrcCount * 4;
                        if (hasExtension && len >= headerSize + 4) {
                            int extLenWords = ((buffer[offset + headerSize + 2] & 0xFF) << 8)
                                    | (buffer[offset + headerSize + 3] & 0xFF);
                            headerSize += 4 + extLenWords * 4;
                        }
                        int paddingBytes = 0;
                        if (hasPadding) {
                            paddingBytes = buffer[offset + len - 1] & 0xFF;
                        }
                        if (headerSize + paddingBytes < len) {
                            payloadOffset = offset + headerSize;
                            payloadLen = len - headerSize - paddingBytes;
                        }
                    }
                }

                out.write(buffer, payloadOffset, payloadLen);
            }
        } catch (Exception e) {
            Log.d(TAG, "Stream closed: " + e.getMessage());
        } finally {
            if (currentSession != null) {
                activeSessions.remove(currentSession);
            }
            if (multicastSocket != null) {
                if (groupAddr != null) {
                    try {
                        if (joinedWithInterface && mcastIf != null && mcastPort > 0) {
                            multicastSocket.leaveGroup(new InetSocketAddress(groupAddr, mcastPort), mcastIf);
                        } else {
                            multicastSocket.leaveGroup(groupAddr);
                        }
                    } catch (Exception ignored) {
                    }
                }
                try {
                    multicastSocket.close();
                } catch (Exception ignored) {
                }
            }
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void writeHttpError(OutputStream out, int code, String message) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + message + "\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * 优先查找 eth0 / usb0 / wlan0 等已启用且具备 IPv4 地址的组播网卡（支持有线网卡直连光猫 IPTV 口）
     */
    public static NetworkInterface findPreferredMulticastInterface() {
        try {
            String[] preferredNames = {"eth0", "eth1", "usb0", "rndis0", "wlan0"};
            for (String name : preferredNames) {
                NetworkInterface nif = NetworkInterface.getByName(name);
                if (nif != null && nif.isUp() && !nif.isLoopback() && hasIpv4Address(nif)) {
                    return nif;
                }
            }
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (nif.isUp() && !nif.isLoopback() && nif.supportsMulticast() && hasIpv4Address(nif)) {
                        return nif;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean hasIpv4Address(NetworkInterface nif) {
        Enumeration<InetAddress> addrs = nif.getInetAddresses();
        while (addrs.hasMoreElements()) {
            if (addrs.nextElement() instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }
}
