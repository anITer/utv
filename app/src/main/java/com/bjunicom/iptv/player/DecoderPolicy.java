package com.bjunicom.iptv.player;

import android.os.SystemClock;
import android.util.Log;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local device failures prevent repeated attempts at the same broken decoder. */
public final class DecoderPolicy {
    private static final long FAILURE_TTL_MS = 5 * 60_000L;
    private static final Map<String, Long> failures = new ConcurrentHashMap<>();
    private static volatile long mp2HardwareFailureAtMs = -1;

    private DecoderPolicy() {}

    public static void recordFailure(String mime, String decoderName) {
        if (mime != null && decoderName != null) {
            failures.put(mime + "|" + decoderName, SystemClock.elapsedRealtime());
        }
    }

    public static boolean hasFailed(String mime, String decoderName) {
        Long failedAt = failures.get(mime + "|" + decoderName);
        return failedAt != null && SystemClock.elapsedRealtime() - failedAt < FAILURE_TTL_MS;
    }

    public static void recordMp2HardwareFailure() {
        mp2HardwareFailureAtMs = SystemClock.elapsedRealtime();
    }

    public static List<MediaCodecInfo> getMp2HardwareDecoders(Format format) {
        List<MediaCodecInfo> result = new ArrayList<>();
        if (mp2HardwareFailureAtMs >= 0
                && SystemClock.elapsedRealtime() - mp2HardwareFailureAtMs < FAILURE_TTL_MS) {
            return result;
        }
        try {
            for (MediaCodecInfo info : MediaCodecSelector.DEFAULT.getDecoderInfos(
                    MimeTypes.AUDIO_MPEG_L2, false, false)) {
                if (info.hardwareAccelerated && !info.softwareOnly
                        && !hasFailed(MimeTypes.AUDIO_MPEG_L2, info.name)
                        && (format == null || info.isFormatSupported(format))) {
                    result.add(info);
                }
            }
        } catch (Exception e) {
            Log.w("DecoderPolicy", "Cannot query MP2 hardware capabilities", e);
        }
        return result;
    }
}
