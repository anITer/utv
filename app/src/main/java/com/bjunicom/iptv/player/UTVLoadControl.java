package com.bjunicom.iptv.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaPeriodId;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import java.util.EnumMap;

/**
 * One allocator with protocol-specific load controls. Selection comes from each callback's
 * MediaItem on the playback thread, never a mutable UI-thread current URL.
 */
public final class UTVLoadControl implements LoadControl {
    public static final int UTV_MIN_BUFFER_MS = 800;
    public static final int UTV_MAX_BUFFER_MS = 4000;
    public static final int UTV_BUFFER_FOR_PLAYBACK_MS = 120;
    public static final int UTV_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 400;
    public static final int UTV_TARGET_BUFFER_BYTES = 12 * 1024 * 1024;
    public static final int UTV_BACK_BUFFER_DURATION_MS = 1500;

    private final DefaultAllocator sharedAllocator;
    private final EnumMap<PlaybackBufferPolicy, DefaultLoadControl> controls =
            new EnumMap<>(PlaybackBufferPolicy.class);
    private PlaybackBufferPolicy activePolicy = PlaybackBufferPolicy.DEFAULT;

    public UTVLoadControl() { this(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)); }
    public UTVLoadControl(DefaultAllocator allocator) {
        sharedAllocator = allocator;
        for (PlaybackBufferPolicy policy : PlaybackBufferPolicy.values()) {
            controls.put(policy, new DefaultLoadControl.Builder().setAllocator(allocator)
                    .setBufferDurationsMs(policy.minBufferMs, policy.maxBufferMs, policy.playbackMs, policy.rebufferMs)
                    .setTargetBufferBytes(policy.targetBytes)
                    .setPrioritizeTimeOverSizeThresholds(policy.prioritizeTime)
                    .setBackBuffer(UTV_BACK_BUFFER_DURATION_MS, true).build());
        }
    }
    public DefaultAllocator getSharedAllocator() { return sharedAllocator; }
    @Override public Allocator getAllocator() { return sharedAllocator; }
    @Override public void onPrepared() {
        activePolicy = PlaybackBufferPolicy.DEFAULT;
        for (DefaultLoadControl control : controls.values()) control.onPrepared();
    }
    @Override public void onStopped() {
        for (DefaultLoadControl control : controls.values()) control.onStopped();
        activePolicy = PlaybackBufferPolicy.DEFAULT;
    }
    @Override public void onReleased() {
        for (DefaultLoadControl control : controls.values()) control.onReleased();
        activePolicy = PlaybackBufferPolicy.DEFAULT;
    }

    private PlaybackBufferPolicy policyFor(Timeline timeline, MediaPeriodId id) {
        PlaybackBufferPolicy policy = PlaybackBufferPolicy.DEFAULT;
        int index = timeline.getIndexOfPeriod(id.periodUid);
        if (index != C.INDEX_UNSET) {
            Timeline.Period period = timeline.getPeriod(index, new Timeline.Period());
            Timeline.Window window = timeline.getWindow(period.windowIndex, new Timeline.Window());
            Object tag = window.mediaItem.localConfiguration != null
                    ? window.mediaItem.localConfiguration.tag : null;
            if (tag instanceof PlaybackBufferPolicy) policy = (PlaybackBufferPolicy) tag;
        }
        return policy;
    }
    @Override public void onTracksSelected(Timeline timeline, MediaPeriodId id, Renderer[] renderers,
                                           TrackGroupArray groups, ExoTrackSelection[] selections) {
        activePolicy = policyFor(timeline, id);
        controls.get(activePolicy).onTracksSelected(timeline, id, renderers, groups, selections);
    }
    @Override public boolean shouldContinueLoading(long playbackPositionUs, long bufferedDurationUs,
                                                    float playbackSpeed) {
        // Exo 2.19 supplies no timeline here; onTracksSelected selects the loading period first.
        return controls.get(activePolicy).shouldContinueLoading(playbackPositionUs,
                bufferedDurationUs, playbackSpeed);
    }
    @Override public boolean shouldStartPlayback(Timeline timeline, MediaPeriodId id,
                                                 long bufferedDurationUs, float playbackSpeed,
                                                 boolean rebuffering, long targetLiveOffsetUs) {
        return controls.get(policyFor(timeline, id)).shouldStartPlayback(timeline, id, bufferedDurationUs,
                playbackSpeed, rebuffering, targetLiveOffsetUs);
    }
    // Exo snapshots back-buffer settings at construction, so retain the same 1.5s for all protocols.
    @Override public long getBackBufferDurationUs() { return UTV_BACK_BUFFER_DURATION_MS * 1000L; }
    @Override public boolean retainBackBufferFromKeyframe() { return true; }
}
