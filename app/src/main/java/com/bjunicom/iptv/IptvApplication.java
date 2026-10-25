package com.bjunicom.iptv;

import android.app.Application;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.util.Log;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;
import com.bjunicom.iptv.network.TransportRouteCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 应用程序入口
 * 负责初始化 bilibili/ijkplayer 原生库、持有 Wi-Fi 组播锁 (MulticastLock) 以及启动本地组播转单播代理网关
 */
public class IptvApplication extends Application {
    private static final String TAG = "IptvApplication";
    public static final String LAST_CRASH_LOG_FILE = "last_crash.log";

    private WifiManager.MulticastLock multicastLock;
    private LocalMulticastProxyServer localProxyServer;
    private ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { TransportRouteCache.clear(); }
        @Override public void onLost(Network network) { TransportRouteCache.clear(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            TransportRouteCache.clear();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashRecorder();
        try {
            IjkMediaPlayer.loadLibrariesOnce(null);
            IjkMediaPlayer.native_profileBegin("libijkplayer.so");
        } catch (Throwable t) {
            Log.w(TAG, "ijkplayer native library load warning: " + t.getMessage());
        }

        acquireMulticastLock();
        localProxyServer = new LocalMulticastProxyServer();
        localProxyServer.start();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            try {
                connectivityManager.registerNetworkCallback(
                        new NetworkRequest.Builder().clearCapabilities().build(), networkCallback);
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot observe network changes", error);
            }
        }
    }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("Thread: " + (thread != null ? thread.getName() : "unknown"));
                if (throwable != null) {
                    throwable.printStackTrace(pw);
                }
                pw.flush();
                File crashFile = new File(getFilesDir(), LAST_CRASH_LOG_FILE);
                try (FileOutputStream fos = new FileOutputStream(crashFile, false)) {
                    fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("BjUnicomIptvMulticastLock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to acquire MulticastLock: " + t.getMessage());
        }
    }

    public int getLocalProxyPort() {
        if (localProxyServer != null) {
            int port = localProxyServer.getListenPort();
            if (port <= 0) {
                port = localProxyServer.start();
            }
            return port;
        }
        return -1;
    }

    @Override
    public void onTerminate() {
        if (connectivityManager != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) {}
        }
        if (localProxyServer != null) {
            localProxyServer.stop();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        try {
            IjkMediaPlayer.native_profileEnd();
        } catch (Throwable ignored) {
        }
        super.onTerminate();
    }
}
