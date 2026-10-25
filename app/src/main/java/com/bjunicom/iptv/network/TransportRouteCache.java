package com.bjunicom.iptv.network;

import android.net.Uri;
import android.os.SystemClock;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/** Shared by the UI and Exo data sources; only failed external udpxy endpoints are bypassed. */
public final class TransportRouteCache {
    private static final long RETRY_AFTER_MS = 5 * 60_000L;
    private static final Map<String, Long> failures = new ConcurrentHashMap<>();

    private static final Pattern DIRECT_TARGET = Pattern.compile(
            "^(?:rtp|udp)://@?([0-9.]+):(\\d+)(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROXY_TARGET = Pattern.compile("^/(?:rtp|udp)/([0-9.]+):(\\d+)/?$");

    private TransportRouteCache() {}

    public static String multicastTarget(Uri uri) {
        if (uri == null) return null;
        Matcher match = DIRECT_TARGET.matcher(uri.toString());
        if (!match.matches()) {
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getPath() == null) return null;
            match = PROXY_TARGET.matcher(uri.getPath());
            if (!match.matches()) return null;
        }
        String[] octets = match.group(1).split("\\.", -1);
        if (octets.length != 4) return null;
        try {
            int first = Integer.parseInt(octets[0]);
            if (first < 224 || first > 239) return null;
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255 || (octet.length() > 1 && octet.charAt(0) == '0')) return null;
            }
            int port = Integer.parseInt(match.group(2));
            return port > 0 && port <= 65535 ? match.group(1) + ":" + port : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    public static boolean isExternalProxy(Uri uri) {
        return uri != null && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                && !"127.0.0.1".equals(uri.getHost()) && !"localhost".equalsIgnoreCase(uri.getHost())
                && uri.getHost() != null && multicastTarget(uri) != null;
    }

    private static String key(Uri uri) {
        return uri.getScheme().toLowerCase(java.util.Locale.US) + "://"
                + uri.getAuthority().toLowerCase(java.util.Locale.US);
    }

    public static void recordNetworkFailure(Uri uri) {
        if (isExternalProxy(uri)) failures.put(key(uri), SystemClock.elapsedRealtime());
    }

    public static boolean shouldBypass(Uri uri) {
        if (!isExternalProxy(uri)) return false;
        Long failedAt = failures.get(key(uri));
        return failedAt != null && SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS;
    }

    public static void clear() {
        failures.clear();
    }
}
