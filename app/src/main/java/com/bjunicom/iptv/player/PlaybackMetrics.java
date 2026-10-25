package com.bjunicom.iptv.player;

import android.os.SystemClock;

/** Monotonic startup timestamps belonging to one channel request, including recovery attempts. */
public final class PlaybackMetrics {
    public static final int INFO_FIRST_PACKET = 20001;
    public static final int INFO_TRANSPORT_FALLBACK = 20002;
    public static final int INFO_STREAM_INFO = 20003;
    public static final int INFO_VIDEO_DECODER_READY = 20004;
    public static final int INFO_AUDIO_RENDERING_START = 20005;

    public final long requestId;
    private final long startedAtMs;
    private long firstPacketMs = -1;
    private long streamInfoMs = -1;
    private long decoderReadyMs = -1;
    private long preparedMs = -1;
    private long firstVideoMs = -1;
    private long firstAudioMs = -1;

    public PlaybackMetrics(long requestId) {
        this.requestId = requestId;
        startedAtMs = SystemClock.elapsedRealtime();
    }

    public synchronized void prepared() {
        if (preparedMs < 0) preparedMs = elapsed();
    }

    public synchronized void onInfo(int info) {
        switch (info) {
            case INFO_FIRST_PACKET:
                if (firstPacketMs < 0) firstPacketMs = elapsed();
                break;
            case INFO_STREAM_INFO:
            case tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_FIND_STREAM_INFO:
                if (streamInfoMs < 0) streamInfoMs = elapsed();
                break;
            case INFO_VIDEO_DECODER_READY:
            case tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_COMPONENT_OPEN:
                if (decoderReadyMs < 0) decoderReadyMs = elapsed();
                break;
            case tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                if (firstVideoMs < 0) firstVideoMs = elapsed();
                break;
            case INFO_AUDIO_RENDERING_START:
            case tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START:
                if (firstAudioMs < 0) firstAudioMs = elapsed();
                break;
            default:
                break;
        }
    }

    public synchronized long getFirstVideoMs() {
        return firstVideoMs;
    }

    public synchronized String summary() {
        return "request=" + requestId + " firstPacketMs=" + firstPacketMs
                + " streamInfoMs=" + streamInfoMs + " decoderReadyMs=" + decoderReadyMs
                + " preparedMs=" + preparedMs + " firstVideoMs=" + firstVideoMs
                + " firstAudioMs=" + firstAudioMs;
    }

    private long elapsed() {
        return SystemClock.elapsedRealtime() - startedAtMs;
    }
}
