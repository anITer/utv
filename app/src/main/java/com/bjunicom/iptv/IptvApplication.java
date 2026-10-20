package com.bjunicom.iptv;

import android.app.Application;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.bjunicom.iptv.network.LocalMulticastProxyServer;

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

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) {
                    multicastLock.release();
                }
            } catch (Throwable ignored) {
            }
            multicastLock = null;
        }
    }

    /**
     * 彻底退出播放时调用：停止本地组播代理网关并释放组播锁，
     * 确保退出应用后流媒体服务、IGMP 组播组与后台线程完全回收，不再残留占用带宽。
     */
    public void shutdownStreamingService() {
        if (localProxyServer != null) {
            localProxyServer.stop();
        }
        releaseMulticastLock();
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
        shutdownStreamingService();
        try {
            IjkMediaPlayer.native_profileEnd();
        } catch (Throwable ignored) {
        }
        super.onTerminate();
    }
}
