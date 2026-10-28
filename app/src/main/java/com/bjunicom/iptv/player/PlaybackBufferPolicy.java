package com.bjunicom.iptv.player;

import android.net.Uri;
import java.util.Locale;

/** Immutable stream classification shared by source routing and playback-thread load control. */
public enum PlaybackBufferPolicy {
    MULTICAST_TS(800, 4000, 120, 400, 12 * 1024 * 1024, true),
    HTTP_TS(1500, 6000, 500, 1000, 12 * 1024 * 1024, true),
    ADAPTIVE(15000, 50000, 2500, 5000, -1, false),
    DEFAULT(15000, 50000, 2500, 5000, -1, false);

    public final int minBufferMs, maxBufferMs, playbackMs, rebufferMs, targetBytes;
    public final boolean prioritizeTime;

    PlaybackBufferPolicy(int min, int max, int playback, int rebuffer, int bytes, boolean time) {
        minBufferMs = min; maxBufferMs = max; playbackMs = playback; rebufferMs = rebuffer;
        targetBytes = bytes; prioritizeTime = time;
    }

    static boolean isHls(Uri uri, String rawUrl) {
        return pathEndsWith(uri, ".m3u8") || pathEndsWith(parse(rawUrl), ".m3u8");
    }
    static boolean isDash(Uri uri, String rawUrl) {
        return pathEndsWith(uri, ".mpd") || pathEndsWith(parse(rawUrl), ".mpd");
    }

    public static PlaybackBufferPolicy forStream(Uri uri, String rawUrl) {
        if (isHls(uri, rawUrl) || isDash(uri, rawUrl)) return ADAPTIVE;
        if (uri == null) return DEFAULT;
        String scheme = uri.getScheme();
        String target = UTVStreamDataSource.extractMulticastTarget(uri.toString());
        if (target != null) {
            if ("udp".equalsIgnoreCase(scheme) || "rtp".equalsIgnoreCase(scheme)
                    || "127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())
                    || "::1".equals(uri.getHost()) || "[::1]".equals(uri.getHost())) return MULTICAST_TS;
            return HTTP_TS;
        }
        if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && (pathEndsWith(uri, ".ts") || pathEndsWith(parse(rawUrl), ".ts")
                || UTVStreamDataSource.extractMulticastTarget(rawUrl) != null)) return HTTP_TS;
        return DEFAULT;
    }

    private static Uri parse(String value) { return value == null ? null : Uri.parse(value); }
    private static boolean pathEndsWith(Uri uri, String suffix) {
        return uri != null && uri.getPath() != null
                && uri.getPath().toLowerCase(Locale.US).endsWith(suffix);
    }
}
