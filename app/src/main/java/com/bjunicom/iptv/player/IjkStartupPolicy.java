package com.bjunicom.iptv.player;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import com.bjunicom.iptv.network.TransportRouteCache;

import tv.danmaku.ijk.media.player.IjkMediaMeta;

/** Probe limits for joining live TS between keyframes, and checks before accepting preparation. */
final class IjkStartupPolicy {
    final boolean multicast;
    final long probeSizeBytes;
    final long analyzeDurationUs;

    private IjkStartupPolicy(boolean multicast, long probeSizeBytes, long analyzeDurationUs) {
        this.multicast = multicast;
        this.probeSizeBytes = probeSizeBytes;
        this.analyzeDurationUs = analyzeDurationUs;
    }

    static IjkStartupPolicy forStream(String rawUrl, Uri playbackUri, boolean expanded) {
        String target = TransportRouteCache.multicastTarget(playbackUri);
        if (target == null && !TextUtils.isEmpty(rawUrl)) {
            target = TransportRouteCache.multicastTarget(Uri.parse(rawUrl));
        }
        boolean likely4K = target != null ? target.endsWith(":2000")
                : !TextUtils.isEmpty(rawUrl) && rawUrl.endsWith(":2000");
        if (target == null) {
            return new IjkStartupPolicy(false, likely4K ? 1536 * 1024L : 512 * 1024L,
                    likely4K ? 800000L : 400000L);
        }
        // These are upper bounds, not fixed waits: FFmpeg can finish once parameters are complete.
        return new IjkStartupPolicy(true,
                expanded ? (likely4K ? 16 : 8) * 1024 * 1024L
                        : (likely4K ? 8 : 4) * 1024 * 1024L,
                expanded ? (likely4K ? 8000000L : 6000000L)
                        : (likely4K ? 4000000L : 3000000L));
    }

    /** A successful find_stream_info may only mean that audio was identified. */
    static String videoParametersIssue(Bundle bundle) {
        IjkMediaMeta meta = IjkMediaMeta.parse(bundle);
        if (meta == null || meta.mStreams.isEmpty()) return "missing stream metadata";
        IjkMediaMeta.IjkStreamMeta video = meta.mVideoStream;
        boolean hasAudio = false;
        for (IjkMediaMeta.IjkStreamMeta stream : meta.mStreams) {
            if ("video".equals(stream.mType) && video == null) video = stream;
            if ("audio".equals(stream.mType)) hasAudio = true;
        }
        if (video == null) return hasAudio ? null : "missing video stream";
        if (video.mWidth <= 0 || video.mHeight <= 0) {
            return "invalid video size " + video.mWidth + "x" + video.mHeight;
        }
        if (TextUtils.isEmpty(video.mCodecName)) return "missing video codec";
        // Java metadata does not expose SPS/PPS bytes. Width/height and the AVC profile
        // reveal whether FFmpeg parsed the parameters needed by Ijk's MediaCodec selector.
        if ("h264".equalsIgnoreCase(video.mCodecName)
                && video.getInt(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID, -99) <= 0) {
            return "unknown H.264 profile";
        }
        return null;
    }
}
