package com.bjunicom.iptv.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaPeriodId;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

/**
 * UTVPlayer 专用低延迟直播与预拉流缓冲控制器 (UTVLoadControl)
 *
 * 特性：
 * 1. 提供共享 DefaultAllocator 供后台 PreloadMediaSource 的 3 个 ProgressiveMediaPeriod (SampleQueue) 与主解码器共用；
 * 2. 针对 IPTV 组播/HTTP-TS 直播将起播缓冲阈值降至 120ms，恢复缓冲降至 400ms，只要 SampleQueue 中已有预拉取的关键帧即可瞬间起播；
 * 3. 开启 retainBackBufferFromKeyframe = true (保留最近 1.5s 关键帧起点回溯缓冲)。
 */
public class UTVLoadControl extends DefaultLoadControl {
    public static final int UTV_MIN_BUFFER_MS = 800;
    public static final int UTV_MAX_BUFFER_MS = 4000;
    public static final int UTV_BUFFER_FOR_PLAYBACK_MS = 120;
    public static final int UTV_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 400;
    public static final int UTV_TARGET_BUFFER_BYTES = 12 * 1024 * 1024;
    public static final int UTV_BACK_BUFFER_DURATION_MS = 1500;

    private final DefaultAllocator sharedAllocator;

    public UTVLoadControl() {
        this(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE));
    }

    public UTVLoadControl(DefaultAllocator allocator) {
        super(
                allocator,
                UTV_MIN_BUFFER_MS,
                UTV_MAX_BUFFER_MS,
                UTV_BUFFER_FOR_PLAYBACK_MS,
                UTV_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                UTV_TARGET_BUFFER_BYTES,
                true,
                UTV_BACK_BUFFER_DURATION_MS,
                true
        );
        this.sharedAllocator = allocator;
    }

    public DefaultAllocator getSharedAllocator() {
        return sharedAllocator;
    }

    @Override
    public Allocator getAllocator() {
        return sharedAllocator;
    }

    @Override
    public boolean shouldStartPlayback(Timeline timeline,
                                       MediaPeriodId mediaPeriodId,
                                       long bufferedDurationUs,
                                       float playbackSpeed,
                                       boolean rebuffering,
                                       long targetLiveOffsetUs) {
        long minThresholdUs = rebuffering
                ? (UTV_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS * 1000L)
                : (UTV_BUFFER_FOR_PLAYBACK_MS * 1000L);
        if (bufferedDurationUs >= minThresholdUs) {
            return true;
        }
        return super.shouldStartPlayback(
                timeline, mediaPeriodId, bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs);
    }

    @Override
    public void onTracksSelected(Timeline timeline,
                                 MediaPeriodId mediaPeriodId,
                                 Renderer[] renderers,
                                 TrackGroupArray trackGroups,
                                 ExoTrackSelection[] trackSelections) {
        super.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections);
    }
}
