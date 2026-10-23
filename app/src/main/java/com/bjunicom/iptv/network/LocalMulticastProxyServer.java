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
import java.util.ArrayList;
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
    // 组播首包宽限与超时容忍：
    // 这台电视走 Wi-Fi 收组播，IGMP 组播转发建立实测要 10~30 秒以上，
    // 避免系统播放器等非 IJK 内核冷启动首包未达即断开
    private static final long MCAST_FIRST_PACKET_GRACE_MS = 45000L;
    private static final int MCAST_MAX_CONSECUTIVE_TIMEOUTS = 3;
    private static final int MAX_CONCURRENT_SESSIONS = 3; // 对应三实例播放器池 (当前播放 + 上一个离屏 + 下一个离屏)

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ActiveSession> activeSessions = new CopyOnWriteArrayList<>();
    private final Object sessionGateLock = new Object();
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private int listenPort = -1;

    private static final class ActiveSession {
        final String groupKey;
        final Socket client;
        final MulticastSocket multicastSocket;
        final InetAddress groupAddr;
        final int mcastPort;
        final List<NetworkInterface> joinedInterfaces;

        ActiveSession(String groupKey,
                      Socket client,
                      MulticastSocket multicastSocket,
                      InetAddress groupAddr,
                      int mcastPort,
                      List<NetworkInterface> joinedInterfaces) {
            this.groupKey = groupKey;
            this.client = client;
            this.multicastSocket = multicastSocket;
            this.groupAddr = groupAddr;
            this.mcastPort = mcastPort;
            this.joinedInterfaces = joinedInterfaces;
        }

        void closeQuietly() {
            leaveMulticastOnAllInterfaces(multicastSocket, groupAddr, mcastPort, joinedInterfaces);
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
        // 关闭所有活动会话的组播 Socket 与客户端连接，避免退出后组播组残留继续收流占用带宽
        for (ActiveSession session : activeSessions) {
            session.closeQuietly();
        }
        activeSessions.clear();
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
        List<NetworkInterface> joinedInterfaces = null;
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

            synchronized (sessionGateLock) {
                // 清理已断开或相同组播地址的旧连接，并保持最多 MAX_CONCURRENT_SESSIONS (3) 路并发组播流
                for (ActiveSession session : activeSessions) {
                    if (session.client.isClosed() || groupKey.equals(session.groupKey)) {
                        activeSessions.remove(session);
                        session.closeQuietly();
                    }
                }
                while (activeSessions.size() >= MAX_CONCURRENT_SESSIONS) {
                    ActiveSession oldest = activeSessions.remove(0);
                    if (oldest != null) {
                        oldest.closeQuietly();
                    }
                }

                groupAddr = InetAddress.getByName(mcastIp);
                multicastSocket = new MulticastSocket(null);
                multicastSocket.setReuseAddress(true);
                bindMulticastSocketWithGroupFilter(multicastSocket, groupAddr, mcastPort);
                multicastSocket.setReceiveBufferSize(1024 * 1024);
                multicastSocket.setSoTimeout(5000);
                joinedInterfaces = joinMulticastOnAllInterfaces(multicastSocket, groupAddr, mcastPort);

                currentSession = new ActiveSession(
                        groupKey, client, multicastSocket, groupAddr, mcastPort, joinedInterfaces);
                activeSessions.add(currentSession);
            }

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

            long joinTimeMs = System.currentTimeMillis();
            boolean receivedAnyPacket = false;
            int consecutiveTimeouts = 0;

            while (running.get() && !client.isClosed()) {
                try {
                    packet.setLength(buffer.length);
                    multicastSocket.receive(packet);
                } catch (java.net.SocketTimeoutException timeoutEx) {
                    // 组播接收超时处理：
                    // 1) 尚未收到首包（冷启动阶段）：IGMP 组播转发建立耗时可能超过单次超时窗口，
                    //    将首包等待宽限延长至 MCAST_FIRST_PACKET_GRACE_MS，期间周期性补发 IGMP 加入报告，
                    //    加速交换机/路由器建立组播转发，根治冷启动首次连接必然失败的问题；
                    // 2) 已收到首包：容忍偶发的组播空洞，连续超时超过上限才判定频道中断。
                    if (!receivedAnyPacket) {
                        long sinceJoinMs = System.currentTimeMillis() - joinTimeMs;
                        if (sinceJoinMs >= MCAST_FIRST_PACKET_GRACE_MS) {
                            throw timeoutEx;
                        }
                        joinedInterfaces = joinMulticastOnAllInterfaces(
                                multicastSocket, groupAddr, mcastPort);
                    } else {
                        consecutiveTimeouts++;
                        if (consecutiveTimeouts >= MCAST_MAX_CONSECUTIVE_TIMEOUTS) {
                            throw timeoutEx;
                        }
                    }
                    continue;
                }
                consecutiveTimeouts = 0;
                receivedAnyPacket = true;
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
                leaveMulticastOnAllInterfaces(multicastSocket, groupAddr, mcastPort, joinedInterfaces);
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
     * 加入组播组（可重复调用以在超时窗口内补发 IGMP 加入报告），
     * 优先通过指定组播网卡加入，失败则退化为默认网卡加入。
     *
     * @return 是否成功通过指定网卡加入
     */
    private static boolean joinMulticastGroup(MulticastSocket socket,
                                              InetAddress groupAddr,
                                              int mcastPort,
                                              NetworkInterface mcastIf) {
        try {
            if (mcastIf != null) {
                try {
                    socket.setNetworkInterface(mcastIf);
                } catch (Exception ignored) {
                }
                try {
                    socket.joinGroup(new InetSocketAddress(groupAddr, mcastPort), mcastIf);
                    return true;
                } catch (Exception ignored) {
                }
            }
            socket.joinGroup(groupAddr);
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 优先查找 eth0 / usb0 / wlan0 等已启用且具备 IPv4 地址的组播网卡（支持有线网卡直连光猫 IPTV 口）
     */
    public static NetworkInterface findPreferredMulticastInterface() {
        List<NetworkInterface> candidates = getCandidateMulticastInterfaces();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 优先将 MulticastSocket 绑定到具体的组播地址 (groupAddr:mcastPort)：
     * 在 Linux/Android 内核中，绑定具体组播 IP 可在内核层直接过滤掉同端口其它组播组的报文（规避 IP_MULTICAST_ALL=1 引起的同端口串流）；
     * 若宿主内核不支持绑定组播 IP，则平滑回退至绑定通配端口 (0.0.0.0:mcastPort)。
     */
    public static void bindMulticastSocketWithGroupFilter(
            MulticastSocket socket, InetAddress groupAddr, int mcastPort) throws IOException {
        if (groupAddr != null && groupAddr.isMulticastAddress()) {
            try {
                socket.bind(new InetSocketAddress(groupAddr, mcastPort));
                return;
            } catch (IOException ignored) {
            }
        }
        socket.bind(new InetSocketAddress(mcastPort));
    }

    /**
     * 获取所有可能承载 IPTV 组播流的候选网卡列表（按优先级排序：有线 eth0.3964 / eth0 / eth1 -> USB网卡 -> Wi-Fi wlan0 -> 其它支持组播的网卡）
     * 解决多网卡设备（如同时插了光猫 IPTV 网线 eth0 (VLAN 3964) 与家庭路由器 Wi-Fi wlan0）绑定单一网卡导致组播探测或播放超时的问题。
     */
    public static List<NetworkInterface> getCandidateMulticastInterfaces() {
        List<NetworkInterface> list = new ArrayList<>();
        try {
            String[] preferredNames = {"eth0.3964", "eth0", "eth1", "usb0", "rndis0", "wlan0"};
            for (String name : preferredNames) {
                NetworkInterface nif = NetworkInterface.getByName(name);
                if (nif != null && nif.isUp() && !nif.isLoopback() && nif.supportsMulticast() && hasIpv4Address(nif)) {
                    list.add(nif);
                }
            }
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (nif.isUp() && !nif.isLoopback() && nif.supportsMulticast() && hasIpv4Address(nif)) {
                        boolean exists = false;
                        for (NetworkInterface existing : list) {
                            if (existing.getName().equals(nif.getName())) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            list.add(nif);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /**
     * 按优先级在首选 IPTV 网卡上加入 IGMP 组播组：
     * - 若高优先级有线网卡 (eth0.3964 / eth0 / eth1 / usb0) 成功 Join，则不再向低优先级 Wi-Fi 重复 Join，避免多网卡桥接时收到双倍重复组播包；
     * - 若首选网卡 Join 失败则自动尝试下一候选网卡；
     * - 返回实际成功 Join 的网卡列表，供 Leave 时精确退组。
     */
    public static List<NetworkInterface> joinMulticastOnAllInterfaces(
            MulticastSocket socket, InetAddress groupAddr, int mcastPort) {
        List<NetworkInterface> joinedInterfaces = new ArrayList<>();
        List<NetworkInterface> candidates = getCandidateMulticastInterfaces();
        if (!candidates.isEmpty()) {
            for (NetworkInterface nif : candidates) {
                try {
                    if (joinedInterfaces.isEmpty()) {
                        socket.setNetworkInterface(nif);
                    }
                    socket.joinGroup(new InetSocketAddress(groupAddr, mcastPort), nif);
                    joinedInterfaces.add(nif);
                    String name = nif.getName();
                    if (name != null && (name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis"))) {
                        // 有线 IPTV 专口已成功加入，不再重复加入无线网卡以避免重复流
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (joinedInterfaces.isEmpty()) {
            try {
                socket.joinGroup(groupAddr);
            } catch (Exception ignored) {
            }
        }
        return joinedInterfaces;
    }

    public static void leaveMulticastOnAllInterfaces(
            MulticastSocket socket, InetAddress groupAddr, int mcastPort, List<NetworkInterface> joinedInterfaces) {
        if (socket == null || groupAddr == null) {
            return;
        }
        if (joinedInterfaces != null && !joinedInterfaces.isEmpty()) {
            for (NetworkInterface nif : joinedInterfaces) {
                try {
                    socket.leaveGroup(new InetSocketAddress(groupAddr, mcastPort), nif);
                } catch (Exception ignored) {
                }
            }
        } else {
            try {
                socket.leaveGroup(groupAddr);
            } catch (Exception ignored) {
            }
        }
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
