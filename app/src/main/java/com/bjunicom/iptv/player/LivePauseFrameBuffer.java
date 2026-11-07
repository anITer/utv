package com.bjunicom.iptv.player;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.upstream.DataReader;
import com.google.android.exoplayer2.util.ParsableByteArray;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * 观看过程中暂停/恢复与 1.5x 追帧专用的内存帧缓存区 (LivePauseFrameBuffer)
 *
 * 核心能力：
 * 1. 用户触发暂停后，立即暂停解码器解码，并将帧缓存区容量扩大到 3600 个槽位 (PAUSED_SLOT_CAPACITY = 3600)；
 * 2. 暂停期间持续接收直播流解复用出的音视频帧并缓存在内存中，待用户恢复播放时按序送入解码器继续播放；
 * 3. 异常处理：当内存帧缓存区满 (达到槽位上限或内存上限) 时，视频以 GOP (关键帧 I 帧至下一个关键帧前) 为单位淘汰过时的帧，
 *    音频同步淘汰与被淘汰视频 GOP 时长一致的过时音频帧，保持音视频严格同步与解码参考帧完整；
 * 4. 追帧加速：暂停恢复后以 1.5 倍速 (CATCHUP_PLAYBACK_SPEED = 1.5f) 快放视频与音频，
 *    直到播放时间戳追上正常的直播流时间戳后自动恢复 1.0x 原速播放。
 */
public class LivePauseFrameBuffer {

    public static final int DEFAULT_SLOT_CAPACITY = 240;
    public static final int PAUSED_SLOT_CAPACITY = 3600;
    public static final long MAX_PAUSE_DURATION_MS = 60_000L;
    public static final long DEFAULT_VIDEO_FRAME_DURATION_US = 40_000L; // 默认 25fps (40ms)
    public static final long DEFAULT_AUDIO_FRAME_DURATION_US = 24_000L; // 默认 MP2 1152/48kHz (24ms)
    public static final float NORMAL_PLAYBACK_SPEED = 1.0f;
    public static final float CATCHUP_PLAYBACK_SPEED = 1.5f;
    public static final long DEFAULT_MIN_CATCHUP_LAG_US = 600_000L;

    public interface CatchUpStateListener {
        void onCatchUpStarted(float speed);

        void onCatchUpCompleted();
    }

    /**
     * 单个帧缓存槽位：保存编码帧数据、PTS、帧时长、关键帧标记与所属 GOP 序号
     */
    public static final class FrameSlot {
        public static final int TRACK_VIDEO = C.TRACK_TYPE_VIDEO;
        public static final int TRACK_AUDIO = C.TRACK_TYPE_AUDIO;

        public final int trackType;
        public final byte[] data;
        public final long ptsUs;
        public long durationUs;
        public final boolean durationEstimated;
        public final boolean isKeyFrame;
        public final int flags;
        public final int gopIndex;
        @Nullable
        public final TrackOutput.CryptoData cryptoData;

        public FrameSlot(int trackType,
                         @NonNull byte[] data,
                         long ptsUs,
                         long durationUs,
                         boolean durationEstimated,
                         boolean isKeyFrame,
                         int flags,
                         int gopIndex,
                         @Nullable TrackOutput.CryptoData cryptoData) {
            this.trackType = trackType;
            this.data = data;
            this.ptsUs = ptsUs;
            this.durationUs = durationUs;
            this.durationEstimated = durationEstimated;
            this.isKeyFrame = isKeyFrame;
            this.flags = flags;
            this.gopIndex = gopIndex;
            this.cryptoData = cryptoData;
        }
    }

    private final Deque<FrameSlot> videoFrames = new ArrayDeque<>();
    private final Deque<FrameSlot> audioFrames = new ArrayDeque<>();

    private int slotCapacity;
    private long maxMemoryBytes = 0L; // 0 表示仅按槽位控制，>0 时同时限制最大内存字节数
    private long bufferedBytes = 0L;
    private volatile boolean paused = false;
    private volatile boolean catchingUp = false;
    private volatile float playbackSpeed = NORMAL_PLAYBACK_SPEED;
    private volatile float audioPlaybackSpeed = NORMAL_PLAYBACK_SPEED;

    private long defaultVideoFrameDurationUs = DEFAULT_VIDEO_FRAME_DURATION_US;
    private long defaultAudioFrameDurationUs = DEFAULT_AUDIO_FRAME_DURATION_US;

    private int currentGopIndex = -1;
    private long nextAutoVideoPtsUs = 0L;
    private long nextAutoAudioPtsUs = 0L;

    private long pauseStartClockMs = 0L;
    private long currentPlaybackVideoPtsUs = 0L;
    private long currentPlaybackAudioPtsUs = 0L;
    private long latestLiveVideoPtsUs = 0L;
    private long latestLiveAudioPtsUs = 0L;
    private boolean hasReceivedVideoFrames = false;
    private boolean hasReceivedAudioFrames = false;
    private boolean livePtsUpdatedExternallySinceLastTick = false;

    private int evictedGopCount = 0;
    private int totalEvictedVideoFrames = 0;
    private int totalEvictedAudioFrames = 0;
    private long lastEvictedVideoDurationUs = 0L;
    private long lastEvictedAudioDurationUs = 0L;
    private long totalEvictedVideoDurationUs = 0L;
    private long totalEvictedAudioDurationUs = 0L;
    private long actualEvictedAudioFrameDurationSumUs = 0L;

    @Nullable
    private TrackOutput downstreamVideoOutput;
    @Nullable
    private TrackOutput downstreamAudioOutput;
    @Nullable
    private Format pendingVideoFormat;
    @Nullable
    private Format pendingAudioFormat;
    @Nullable
    private CatchUpStateListener catchUpStateListener;

    public LivePauseFrameBuffer() {
        this(DEFAULT_SLOT_CAPACITY);
    }

    public LivePauseFrameBuffer(int initialSlotCapacity) {
        this.slotCapacity = Math.max(1, initialSlotCapacity);
    }

    public synchronized void setCatchUpStateListener(@Nullable CatchUpStateListener listener) {
        this.catchUpStateListener = listener;
    }

    /**
     * 用户触发暂停：立即进入内存缓存模式，并将帧缓存区扩大到 3600 个槽位
     */
    public synchronized void onPause() {
        this.paused = true;
        this.catchingUp = false;
        this.playbackSpeed = NORMAL_PLAYBACK_SPEED;
        this.audioPlaybackSpeed = NORMAL_PLAYBACK_SPEED;
        this.pauseStartClockMs = readClockMs();
        if (this.slotCapacity < PAUSED_SLOT_CAPACITY) {
            this.slotCapacity = PAUSED_SLOT_CAPACITY;
        }
    }

    /**
     * 用户触发恢复：退出暂停拦截状态，启动 1.5x 音视频同步加速追帧，并将内存中缓存的音视频帧按序送入下游解码管线
     */
    public synchronized void onResume() {
        boolean wasPaused = this.paused;
        this.paused = false;

        if (wasPaused || !videoFrames.isEmpty() || !audioFrames.isEmpty()) {
            if (!videoFrames.isEmpty()) {
                currentPlaybackVideoPtsUs = videoFrames.peekFirst().ptsUs;
                FrameSlot lastVideo = videoFrames.peekLast();
                latestLiveVideoPtsUs = Math.max(latestLiveVideoPtsUs, lastVideo.ptsUs + lastVideo.durationUs);
            }
            if (!audioFrames.isEmpty()) {
                currentPlaybackAudioPtsUs = audioFrames.peekFirst().ptsUs;
                FrameSlot lastAudio = audioFrames.peekLast();
                latestLiveAudioPtsUs = Math.max(latestLiveAudioPtsUs, lastAudio.ptsUs + lastAudio.durationUs);
            }
            if (latestLiveVideoPtsUs <= currentPlaybackVideoPtsUs) {
                long nowMs = readClockMs();
                long pausedMs = pauseStartClockMs > 0L && nowMs > pauseStartClockMs
                        ? Math.min(MAX_PAUSE_DURATION_MS, nowMs - pauseStartClockMs)
                        : 0L;
                long lagUs = pausedMs > 0L ? (pausedMs * 1000L) : DEFAULT_MIN_CATCHUP_LAG_US;
                latestLiveVideoPtsUs = currentPlaybackVideoPtsUs + lagUs;
                latestLiveAudioPtsUs = currentPlaybackAudioPtsUs + lagUs;
            } else if (latestLiveAudioPtsUs <= currentPlaybackAudioPtsUs) {
                long videoLagUs = latestLiveVideoPtsUs - currentPlaybackVideoPtsUs;
                latestLiveAudioPtsUs = currentPlaybackAudioPtsUs + videoLagUs;
            }

            this.catchingUp = true;
            this.playbackSpeed = CATCHUP_PLAYBACK_SPEED;
            this.audioPlaybackSpeed = CATCHUP_PLAYBACK_SPEED;
            this.livePtsUpdatedExternallySinceLastTick = false;
        }

        drainToDownstreamOutputs();

        if (this.catchingUp && this.catchUpStateListener != null) {
            this.catchUpStateListener.onCatchUpStarted(CATCHUP_PLAYBACK_SPEED);
        }
    }

    /**
     * 当已经追上正常的直播流时间戳时调用，恢复 1.0x 正常播放速度
     */
    public synchronized void completeCatchUp() {
        boolean wasCatchingUp = this.catchingUp;
        this.catchingUp = false;
        this.playbackSpeed = NORMAL_PLAYBACK_SPEED;
        this.audioPlaybackSpeed = NORMAL_PLAYBACK_SPEED;
        this.currentPlaybackVideoPtsUs = this.latestLiveVideoPtsUs;
        this.currentPlaybackAudioPtsUs = this.latestLiveAudioPtsUs;
        if (downstreamVideoOutput == null) {
            videoFrames.clear();
            audioFrames.clear();
            bufferedBytes = 0L;
        }
        if (wasCatchingUp && this.catchUpStateListener != null) {
            this.catchUpStateListener.onCatchUpCompleted();
        }
    }

    /**
     * 推进追帧进度（追帧期间解码器按 1.5x 消耗音视频时间戳，而直播流按 1.0x 持续推进时间戳）：
     * 当播放时间戳追上最新直播流时间戳 (currentPlaybackVideoPtsUs >= latestLiveVideoPtsUs) 时自动恢复 1.0x。
     *
     * @param realElapsedUs 真实流逝的微秒数
     * @return 是否已追上直播流时间戳
     */
    public synchronized boolean advanceCatchUpWithLiveStreamUs(long realElapsedUs) {
        if (!catchingUp || paused) {
            return !catchingUp;
        }
        if (realElapsedUs <= 0L) {
            return hasCaughtUpToLive();
        }
        long playbackDeltaUs = Math.round(realElapsedUs * CATCHUP_PLAYBACK_SPEED);
        currentPlaybackVideoPtsUs += playbackDeltaUs;
        currentPlaybackAudioPtsUs += playbackDeltaUs;

        if (!livePtsUpdatedExternallySinceLastTick) {
            latestLiveVideoPtsUs += realElapsedUs;
            latestLiveAudioPtsUs += realElapsedUs;
        }
        livePtsUpdatedExternallySinceLastTick = false;

        consumePlayedFramesUpTo(currentPlaybackVideoPtsUs, currentPlaybackAudioPtsUs);

        if (currentPlaybackVideoPtsUs >= latestLiveVideoPtsUs) {
            completeCatchUp();
            return true;
        }
        return false;
    }

    public synchronized boolean advanceCatchUpWithLiveStreamMs(long realElapsedMs) {
        return advanceCatchUpWithLiveStreamUs(realElapsedMs * 1000L);
    }

    /**
     * 仅按 1.5x 推进解码播放时间戳（适用于直播源已通过 offerVideoFrame/offerAudioFrame 注入固定/实时 PTS 的场景）
     */
    public synchronized boolean advanceCatchUpByRealTimeUs(long realElapsedUs) {
        if (!catchingUp || paused) {
            return !catchingUp;
        }
        if (realElapsedUs <= 0L) {
            return hasCaughtUpToLive();
        }
        long playbackDeltaUs = Math.round(realElapsedUs * CATCHUP_PLAYBACK_SPEED);
        currentPlaybackVideoPtsUs += playbackDeltaUs;
        currentPlaybackAudioPtsUs += playbackDeltaUs;
        livePtsUpdatedExternallySinceLastTick = false;

        consumePlayedFramesUpTo(currentPlaybackVideoPtsUs, currentPlaybackAudioPtsUs);

        if (currentPlaybackVideoPtsUs >= latestLiveVideoPtsUs) {
            completeCatchUp();
            return true;
        }
        return false;
    }

    /**
     * 更新当前解码器已播放到的时间戳，若已追上最新直播流时间戳则自动恢复 1.0x 原速
     */
    public synchronized boolean updatePlaybackTimestampUs(long playbackPtsUs) {
        if (playbackPtsUs > currentPlaybackVideoPtsUs) {
            currentPlaybackVideoPtsUs = playbackPtsUs;
        }
        if (playbackPtsUs > currentPlaybackAudioPtsUs) {
            currentPlaybackAudioPtsUs = playbackPtsUs;
        }
        consumePlayedFramesUpTo(currentPlaybackVideoPtsUs, currentPlaybackAudioPtsUs);
        if (catchingUp && currentPlaybackVideoPtsUs >= latestLiveVideoPtsUs) {
            completeCatchUp();
            return true;
        }
        return !catchingUp;
    }

    private void consumePlayedFramesUpTo(long videoPtsUs, long audioPtsUs) {
        if (downstreamVideoOutput == null) {
            while (!videoFrames.isEmpty()) {
                FrameSlot head = videoFrames.peekFirst();
                if (head.ptsUs + head.durationUs <= videoPtsUs) {
                    videoFrames.pollFirst();
                    bufferedBytes = Math.max(0L, bufferedBytes - head.data.length);
                } else {
                    break;
                }
            }
        }
        if (downstreamAudioOutput == null) {
            while (!audioFrames.isEmpty()) {
                FrameSlot head = audioFrames.peekFirst();
                if (head.ptsUs + head.durationUs <= audioPtsUs) {
                    audioFrames.pollFirst();
                    bufferedBytes = Math.max(0L, bufferedBytes - head.data.length);
                } else {
                    break;
                }
            }
        }
    }

    public synchronized void setPaused(boolean paused) {
        if (paused) {
            onPause();
        } else {
            onResume();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isCatchingUp() {
        return catchingUp;
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public float getAudioPlaybackSpeed() {
        return audioPlaybackSpeed;
    }

    public synchronized boolean hasCaughtUpToLive() {
        return !catchingUp || currentPlaybackVideoPtsUs >= latestLiveVideoPtsUs;
    }

    public synchronized long getRemainingCatchUpLagUs() {
        if (!catchingUp && !paused) {
            return 0L;
        }
        return Math.max(0L, latestLiveVideoPtsUs - currentPlaybackVideoPtsUs);
    }

    public synchronized long getCurrentPlaybackVideoPtsUs() {
        return currentPlaybackVideoPtsUs;
    }

    public synchronized long getCurrentPlaybackAudioPtsUs() {
        return currentPlaybackAudioPtsUs;
    }

    public synchronized long getLatestLiveVideoPtsUs() {
        return latestLiveVideoPtsUs;
    }

    public synchronized long getLatestLiveAudioPtsUs() {
        return latestLiveAudioPtsUs;
    }

    public synchronized int getSlotCapacity() {
        return slotCapacity;
    }

    public synchronized void setSlotCapacity(int slotCapacity) {
        this.slotCapacity = Math.max(1, slotCapacity);
        while (videoFrames.size() > this.slotCapacity) {
            if (evictOldestGop() <= 0) {
                break;
            }
        }
    }

    public synchronized void setMaxMemoryBytes(long maxMemoryBytes) {
        this.maxMemoryBytes = Math.max(0L, maxMemoryBytes);
    }

    public synchronized long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public synchronized long getBufferedBytes() {
        return bufferedBytes;
    }

    public synchronized void setDefaultVideoFrameDurationUs(long durationUs) {
        if (durationUs > 0L) {
            this.defaultVideoFrameDurationUs = durationUs;
        }
    }

    public synchronized void setDefaultAudioFrameDurationUs(long durationUs) {
        if (durationUs > 0L) {
            this.defaultAudioFrameDurationUs = durationUs;
        }
    }

    /**
     * 向帧缓存区写入一帧视频数据；若内存 buffer 已满则先按 GOP 淘汰最旧的视频帧及等长音频帧
     */
    public synchronized void offerVideoFrame(@NonNull byte[] data,
                                             long ptsUs,
                                             long durationUs,
                                             boolean isKeyFrame) {
        int flags = isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0;
        offerVideoFrame(data, ptsUs, durationUs, isKeyFrame, flags, null);
    }

    public synchronized void offerVideoFrame(@NonNull byte[] data,
                                             long ptsUs,
                                             long durationUs,
                                             boolean isKeyFrame,
                                             int flags,
                                             @Nullable TrackOutput.CryptoData cryptoData) {
        boolean estimated = durationUs <= 0L;
        long resolvedPtsUs = ptsUs;
        long resolvedDurationUs = durationUs > 0L ? durationUs : defaultVideoFrameDurationUs;

        FrameSlot prev = videoFrames.peekLast();
        if (resolvedPtsUs < 0L) {
            resolvedPtsUs = prev != null ? (prev.ptsUs + prev.durationUs) : nextAutoVideoPtsUs;
        } else if (prev != null && resolvedPtsUs > prev.ptsUs) {
            long deltaUs = resolvedPtsUs - prev.ptsUs;
            if (deltaUs > 0L && deltaUs <= 1_000_000L) {
                if (prev.durationEstimated) {
                    prev.durationUs = deltaUs;
                }
                if (estimated) {
                    resolvedDurationUs = deltaUs;
                }
            }
        }
        nextAutoVideoPtsUs = resolvedPtsUs + resolvedDurationUs;

        if (!hasReceivedVideoFrames) {
            hasReceivedVideoFrames = true;
            currentPlaybackVideoPtsUs = resolvedPtsUs;
        }
        latestLiveVideoPtsUs = Math.max(latestLiveVideoPtsUs, resolvedPtsUs + resolvedDurationUs);
        if (catchingUp) {
            livePtsUpdatedExternallySinceLastTick = true;
        } else if (!paused) {
            currentPlaybackVideoPtsUs = latestLiveVideoPtsUs;
        }

        if (!paused && downstreamVideoOutput != null && videoFrames.isEmpty()) {
            long adjustedPts = Math.max(0L, resolvedPtsUs - totalEvictedVideoDurationUs);
            writeSlotToTrackOutput(downstreamVideoOutput, data, adjustedPts, flags, cryptoData);
            return;
        }

        // 当内存 buffer 满（槽位数达到上限或字节数达到上限）时，视频以 GOP 为单位淘汰过时的帧，音频淘汰相同帧时长
        while (isBufferFullForNextFrame(data.length) && !videoFrames.isEmpty()) {
            if (prev != null && prev.durationEstimated && resolvedPtsUs > prev.ptsUs) {
                long deltaUs = resolvedPtsUs - prev.ptsUs;
                if (deltaUs > 0L && deltaUs <= 1_000_000L) {
                    prev.durationUs = deltaUs;
                }
            }
            int evicted = evictOldestGop(resolvedPtsUs);
            if (evicted <= 0) {
                break;
            }
        }

        if (isKeyFrame || currentGopIndex < 0) {
            currentGopIndex++;
        }

        byte[] copy = Arrays.copyOf(data, data.length);
        FrameSlot slot = new FrameSlot(
                FrameSlot.TRACK_VIDEO,
                copy,
                resolvedPtsUs,
                resolvedDurationUs,
                estimated,
                isKeyFrame,
                flags,
                currentGopIndex,
                cryptoData);
        videoFrames.addLast(slot);
        bufferedBytes += copy.length;
        if (paused && !videoFrames.isEmpty()) {
            currentPlaybackVideoPtsUs = videoFrames.peekFirst().ptsUs;
        }
    }

    /**
     * 向帧缓存区写入一帧音频数据
     */
    public synchronized void offerAudioFrame(@NonNull byte[] data,
                                             long ptsUs,
                                             long durationUs) {
        offerAudioFrame(data, ptsUs, durationUs, C.BUFFER_FLAG_KEY_FRAME, null);
    }

    public synchronized void offerAudioFrame(@NonNull byte[] data,
                                             long ptsUs,
                                             long durationUs,
                                             int flags,
                                             @Nullable TrackOutput.CryptoData cryptoData) {
        boolean estimated = durationUs <= 0L;
        long resolvedPtsUs = ptsUs;
        long resolvedDurationUs = durationUs > 0L ? durationUs : defaultAudioFrameDurationUs;

        FrameSlot prev = audioFrames.peekLast();
        if (resolvedPtsUs < 0L) {
            resolvedPtsUs = prev != null ? (prev.ptsUs + prev.durationUs) : nextAutoAudioPtsUs;
        } else if (prev != null && resolvedPtsUs > prev.ptsUs) {
            long deltaUs = resolvedPtsUs - prev.ptsUs;
            if (deltaUs > 0L && deltaUs <= 500_000L) {
                if (prev.durationEstimated) {
                    prev.durationUs = deltaUs;
                }
                if (estimated) {
                    resolvedDurationUs = deltaUs;
                }
            }
        }
        nextAutoAudioPtsUs = resolvedPtsUs + resolvedDurationUs;

        if (!hasReceivedAudioFrames) {
            hasReceivedAudioFrames = true;
            currentPlaybackAudioPtsUs = resolvedPtsUs;
        }
        latestLiveAudioPtsUs = Math.max(latestLiveAudioPtsUs, resolvedPtsUs + resolvedDurationUs);
        if (!paused && !catchingUp) {
            currentPlaybackAudioPtsUs = latestLiveAudioPtsUs;
        }

        if (!paused && downstreamAudioOutput != null && audioFrames.isEmpty()) {
            long adjustedPts = Math.max(0L, resolvedPtsUs - totalEvictedVideoDurationUs);
            writeSlotToTrackOutput(downstreamAudioOutput, data, adjustedPts, flags, cryptoData);
            return;
        }

        byte[] copy = Arrays.copyOf(data, data.length);
        FrameSlot slot = new FrameSlot(
                FrameSlot.TRACK_AUDIO,
                copy,
                resolvedPtsUs,
                resolvedDurationUs,
                estimated,
                true,
                flags,
                -1,
                cryptoData);
        audioFrames.addLast(slot);
        bufferedBytes += copy.length;

        // 若此前视频已发生过 GOP 淘汰但当时音频帧尚未全部到达，立即补齐淘汰等长音频帧
        evictPendingAudioFramesToMatchVideo();
        if (paused && !audioFrames.isEmpty()) {
            currentPlaybackAudioPtsUs = audioFrames.peekFirst().ptsUs;
        }
    }

    private boolean isBufferFullForNextFrame(int incomingFrameBytes) {
        if (videoFrames.size() >= slotCapacity) {
            return true;
        }
        if (maxMemoryBytes > 0L && !videoFrames.isEmpty()
                && (bufferedBytes + incomingFrameBytes > maxMemoryBytes)) {
            return true;
        }
        return false;
    }

    /**
     * 以 GOP 为单位淘汰最旧的一组视频帧，并同步淘汰时长一致的音频帧
     *
     * @return 本次淘汰的视频帧数量
     */
    public synchronized int evictOldestGop() {
        return evictOldestGop(-1L);
    }

    private synchronized int evictOldestGop(long incomingNextFramePtsUs) {
        if (videoFrames.isEmpty()) {
            return 0;
        }

        int evictedVideoCount = 0;
        long evictedVideoDurUs = 0L;

        FrameSlot first = videoFrames.pollFirst();
        long firstPtsUs = first.ptsUs;
        FrameSlot lastEvicted = first;
        evictedVideoCount++;
        evictedVideoDurUs += first.durationUs;
        bufferedBytes = Math.max(0L, bufferedBytes - first.data.length);

        // 持续淘汰当前最旧 GOP 内的非关键帧（P/B 帧），直到遇到下一个 GOP 的关键帧 (I 帧)
        while (!videoFrames.isEmpty() && !videoFrames.peekFirst().isKeyFrame) {
            FrameSlot slot = videoFrames.pollFirst();
            lastEvicted = slot;
            evictedVideoCount++;
            evictedVideoDurUs += slot.durationUs;
            bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
        }

        // 若队首原本是暂停瞬间残留的半个 GOP 尾部非关键帧，且队列仍满并包含完整 GOP，则继续淘汰至下一个关键帧
        if (!first.isKeyFrame && videoFrames.size() >= slotCapacity && containsAtLeastTwoKeyFrames(videoFrames)) {
            FrameSlot gopStart = videoFrames.pollFirst();
            lastEvicted = gopStart;
            evictedVideoCount++;
            evictedVideoDurUs += gopStart.durationUs;
            bufferedBytes = Math.max(0L, bufferedBytes - gopStart.data.length);
            while (!videoFrames.isEmpty() && !videoFrames.peekFirst().isKeyFrame) {
                FrameSlot slot = videoFrames.pollFirst();
                lastEvicted = slot;
                evictedVideoCount++;
                evictedVideoDurUs += slot.durationUs;
                bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
            }
        }

        // 若被淘汰 GOP 最后一帧的时长此前为估算值，且新队首（下一 GOP 首帧）具有精确 PTS，则用新队首 PTS 校准
        FrameSlot newVideoHead = videoFrames.peekFirst();
        long boundaryPtsUs = newVideoHead != null ? newVideoHead.ptsUs : incomingNextFramePtsUs;
        if (boundaryPtsUs > firstPtsUs && lastEvicted.durationEstimated) {
            long exactGopDurationUs = boundaryPtsUs - firstPtsUs;
            if (exactGopDurationUs > 0L) {
                evictedVideoDurUs = exactGopDurationUs;
            }
        }

        evictedGopCount++;
        totalEvictedVideoFrames += evictedVideoCount;
        lastEvictedVideoDurationUs = evictedVideoDurUs;
        totalEvictedVideoDurationUs += evictedVideoDurUs;

        if (newVideoHead != null) {
            currentPlaybackVideoPtsUs = newVideoHead.ptsUs;
        } else if (boundaryPtsUs > currentPlaybackVideoPtsUs) {
            currentPlaybackVideoPtsUs = boundaryPtsUs;
        }

        // 音频淘汰的帧时长与视频一致
        long beforeAudioEvictedSum = actualEvictedAudioFrameDurationSumUs;
        evictPendingAudioFramesToMatchVideo();
        long newlyEvictedAudioDur = actualEvictedAudioFrameDurationSumUs - beforeAudioEvictedSum;
        lastEvictedAudioDurationUs = newlyEvictedAudioDur > 0L ? newlyEvictedAudioDur : evictedVideoDurUs;

        return evictedVideoCount;
    }

    private void evictPendingAudioFramesToMatchVideo() {
        long targetTotalAudioEvictUs = totalEvictedVideoDurationUs;
        while (!audioFrames.isEmpty() && actualEvictedAudioFrameDurationSumUs < targetTotalAudioEvictUs) {
            FrameSlot headAudio = audioFrames.peekFirst();
            long frameDurUs = headAudio.durationUs > 0L ? headAudio.durationUs : defaultAudioFrameDurationUs;
            long remainingNeededUs = targetTotalAudioEvictUs - actualEvictedAudioFrameDurationSumUs;
            if (frameDurUs <= remainingNeededUs || remainingNeededUs >= (frameDurUs + 1L) / 2L) {
                audioFrames.pollFirst();
                bufferedBytes = Math.max(0L, bufferedBytes - headAudio.data.length);
                actualEvictedAudioFrameDurationSumUs += frameDurUs;
                totalEvictedAudioFrames++;
            } else {
                break;
            }
        }
        if (!audioFrames.isEmpty()) {
            currentPlaybackAudioPtsUs = audioFrames.peekFirst().ptsUs;
        }
        // 当所有待淘汰音频帧均已到达并完成按时长淘汰后，同步更新累计淘汰音频时长
        if (actualEvictedAudioFrameDurationSumUs > 0L) {
            if (Math.abs(targetTotalAudioEvictUs - actualEvictedAudioFrameDurationSumUs)
                    <= Math.max(1L, defaultAudioFrameDurationUs / 2L)) {
                totalEvictedAudioDurationUs = targetTotalAudioEvictUs;
            } else {
                totalEvictedAudioDurationUs = actualEvictedAudioFrameDurationSumUs;
            }
        }
    }

    private static boolean containsAtLeastTwoKeyFrames(Deque<FrameSlot> frames) {
        int keyCount = 0;
        for (FrameSlot slot : frames) {
            if (slot.isKeyFrame) {
                keyCount++;
                if (keyCount >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized void drainToDownstreamOutputs() {
        if (downstreamVideoOutput != null) {
            if (pendingVideoFormat != null) {
                downstreamVideoOutput.format(pendingVideoFormat);
                pendingVideoFormat = null;
            }
            while (!videoFrames.isEmpty()) {
                FrameSlot slot = videoFrames.pollFirst();
                bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
                long adjustedPts = Math.max(0L, slot.ptsUs - totalEvictedVideoDurationUs);
                writeSlotToTrackOutput(downstreamVideoOutput, slot.data, adjustedPts, slot.flags, slot.cryptoData);
            }
        }
        if (downstreamAudioOutput != null) {
            if (pendingAudioFormat != null) {
                downstreamAudioOutput.format(pendingAudioFormat);
                pendingAudioFormat = null;
            }
            while (!audioFrames.isEmpty()) {
                FrameSlot slot = audioFrames.pollFirst();
                bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
                long adjustedPts = Math.max(0L, slot.ptsUs - totalEvictedVideoDurationUs);
                writeSlotToTrackOutput(downstreamAudioOutput, slot.data, adjustedPts, slot.flags, slot.cryptoData);
            }
        }
    }

    private static void writeSlotToTrackOutput(@NonNull TrackOutput output,
                                               @NonNull byte[] data,
                                               long timeUs,
                                               int flags,
                                               @Nullable TrackOutput.CryptoData cryptoData) {
        ParsableByteArray buffer = new ParsableByteArray(data);
        output.sampleData(buffer, data.length);
        output.sampleMetadata(timeUs, flags, data.length, 0, cryptoData);
    }

    @Nullable
    public synchronized FrameSlot pollNextVideoFrame() {
        FrameSlot slot = videoFrames.pollFirst();
        if (slot != null) {
            bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
            currentPlaybackVideoPtsUs = Math.max(currentPlaybackVideoPtsUs, slot.ptsUs + slot.durationUs);
            if (catchingUp && videoFrames.isEmpty() && currentPlaybackVideoPtsUs >= latestLiveVideoPtsUs) {
                completeCatchUp();
            }
        }
        return slot;
    }

    @Nullable
    public synchronized FrameSlot pollNextAudioFrame() {
        FrameSlot slot = audioFrames.pollFirst();
        if (slot != null) {
            bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
            currentPlaybackAudioPtsUs = Math.max(currentPlaybackAudioPtsUs, slot.ptsUs + slot.durationUs);
        }
        return slot;
    }

    @NonNull
    public synchronized List<FrameSlot> drainAllVideoFrames() {
        List<FrameSlot> list = new ArrayList<>(videoFrames);
        for (FrameSlot slot : list) {
            bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
        }
        videoFrames.clear();
        return list;
    }

    @NonNull
    public synchronized List<FrameSlot> drainAllAudioFrames() {
        List<FrameSlot> list = new ArrayList<>(audioFrames);
        for (FrameSlot slot : list) {
            bufferedBytes = Math.max(0L, bufferedBytes - slot.data.length);
        }
        audioFrames.clear();
        return list;
    }

    public synchronized int getVideoFrameCount() {
        return videoFrames.size();
    }

    public synchronized int getAudioFrameCount() {
        return audioFrames.size();
    }

    public synchronized int getTotalFrameCount() {
        return videoFrames.size() + audioFrames.size();
    }

    public synchronized int getGopCount() {
        if (videoFrames.isEmpty()) {
            return 0;
        }
        int gops = 0;
        for (FrameSlot slot : videoFrames) {
            if (slot.isKeyFrame) {
                gops++;
            }
        }
        return Math.max(1, gops);
    }

    public synchronized long getBufferedVideoDurationUs() {
        long sum = 0L;
        for (FrameSlot slot : videoFrames) {
            sum += slot.durationUs;
        }
        return sum;
    }

    public synchronized long getBufferedAudioDurationUs() {
        long sum = 0L;
        for (FrameSlot slot : audioFrames) {
            sum += slot.durationUs;
        }
        return sum;
    }

    public synchronized int getEvictedGopCount() {
        return evictedGopCount;
    }

    public synchronized int getTotalEvictedVideoFrames() {
        return totalEvictedVideoFrames;
    }

    public synchronized int getTotalEvictedAudioFrames() {
        return totalEvictedAudioFrames;
    }

    public synchronized long getLastEvictedVideoDurationUs() {
        return lastEvictedVideoDurationUs;
    }

    public synchronized long getLastEvictedAudioDurationUs() {
        return lastEvictedAudioDurationUs;
    }

    public synchronized long getTotalEvictedVideoDurationUs() {
        return totalEvictedVideoDurationUs;
    }

    public synchronized long getTotalEvictedAudioDurationUs() {
        return totalEvictedAudioDurationUs;
    }

    @Nullable
    public synchronized FrameSlot peekFirstVideoFrame() {
        return videoFrames.peekFirst();
    }

    @Nullable
    public synchronized FrameSlot peekFirstAudioFrame() {
        return audioFrames.peekFirst();
    }

    @NonNull
    public synchronized List<FrameSlot> getVideoFramesSnapshot() {
        return new ArrayList<>(videoFrames);
    }

    @NonNull
    public synchronized List<FrameSlot> getAudioFramesSnapshot() {
        return new ArrayList<>(audioFrames);
    }

    /**
     * 切台或重置播放器时清空帧缓存区并恢复初始状态
     */
    public synchronized void reset() {
        videoFrames.clear();
        audioFrames.clear();
        bufferedBytes = 0L;
        paused = false;
        catchingUp = false;
        playbackSpeed = NORMAL_PLAYBACK_SPEED;
        audioPlaybackSpeed = NORMAL_PLAYBACK_SPEED;
        slotCapacity = DEFAULT_SLOT_CAPACITY;
        currentGopIndex = -1;
        nextAutoVideoPtsUs = 0L;
        nextAutoAudioPtsUs = 0L;
        pauseStartClockMs = 0L;
        currentPlaybackVideoPtsUs = 0L;
        currentPlaybackAudioPtsUs = 0L;
        latestLiveVideoPtsUs = 0L;
        latestLiveAudioPtsUs = 0L;
        hasReceivedVideoFrames = false;
        hasReceivedAudioFrames = false;
        livePtsUpdatedExternallySinceLastTick = false;
        evictedGopCount = 0;
        totalEvictedVideoFrames = 0;
        totalEvictedAudioFrames = 0;
        lastEvictedVideoDurationUs = 0L;
        lastEvictedAudioDurationUs = 0L;
        totalEvictedVideoDurationUs = 0L;
        totalEvictedAudioDurationUs = 0L;
        actualEvictedAudioFrameDurationSumUs = 0L;
        downstreamVideoOutput = null;
        downstreamAudioOutput = null;
        pendingVideoFormat = null;
        pendingAudioFormat = null;
    }

    private static long readClockMs() {
        try {
            long elapsed = SystemClock.elapsedRealtime();
            if (elapsed > 0L) {
                return elapsed;
            }
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis();
    }

    /**
     * 包装 ExoPlayer TsExtractor 的 TrackOutput：
     * 在正常播放时直通下游 SampleQueue；在暂停期间将音视频帧缓存在 3600 槽位内存队列中并执行 GOP 淘汰。
     */
    @NonNull
    public synchronized TrackOutput wrapTrackOutput(int trackType, @NonNull TrackOutput downstream) {
        if (trackType == C.TRACK_TYPE_VIDEO) {
            this.downstreamVideoOutput = downstream;
            return new BufferingTrackOutput(C.TRACK_TYPE_VIDEO, downstream);
        } else if (trackType == C.TRACK_TYPE_AUDIO) {
            this.downstreamAudioOutput = downstream;
            return new BufferingTrackOutput(C.TRACK_TYPE_AUDIO, downstream);
        }
        return downstream;
    }

    private final class BufferingTrackOutput implements TrackOutput {
        private final int trackType;
        private final TrackOutput downstream;
        private byte[] scratchBuffer = new byte[64 * 1024];
        private int scratchLength = 0;

        BufferingTrackOutput(int trackType, TrackOutput downstream) {
            this.trackType = trackType;
            this.downstream = downstream;
        }

        private void ensureCapacity(int additionalBytes) {
            int required = scratchLength + additionalBytes;
            if (required > scratchBuffer.length) {
                int newCap = Math.max(scratchBuffer.length * 2, required);
                scratchBuffer = Arrays.copyOf(scratchBuffer, newCap);
            }
        }

        @Override
        public void format(Format format) {
            synchronized (LivePauseFrameBuffer.this) {
                if (format != null) {
                    if (trackType == C.TRACK_TYPE_VIDEO && format.frameRate > 0f) {
                        defaultVideoFrameDurationUs = Math.round(1_000_000.0 / format.frameRate);
                    } else if (trackType == C.TRACK_TYPE_AUDIO && format.sampleRate > 0) {
                        int samplesPerFrame = (format.sampleMimeType != null && format.sampleMimeType.contains("mp4a"))
                                ? 1024 : 1152;
                        defaultAudioFrameDurationUs = (samplesPerFrame * 1_000_000L) / format.sampleRate;
                    }
                }
                if (paused) {
                    if (trackType == C.TRACK_TYPE_VIDEO) {
                        pendingVideoFormat = format;
                    } else if (trackType == C.TRACK_TYPE_AUDIO) {
                        pendingAudioFormat = format;
                    }
                } else {
                    downstream.format(format);
                }
            }
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            ensureCapacity(length);
            int bytesRead = input.read(scratchBuffer, scratchLength, length);
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) {
                    return C.RESULT_END_OF_INPUT;
                }
                throw new java.io.EOFException();
            }
            if (bytesRead > 0) {
                scratchLength += bytesRead;
            }
            return bytesRead;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            if (length <= 0) {
                return;
            }
            ensureCapacity(length);
            data.readBytes(scratchBuffer, scratchLength, length);
            scratchLength += length;
        }

        @Override
        public void sampleMetadata(long timeUs,
                                   int flags,
                                   int size,
                                   int offset,
                                   @Nullable CryptoData cryptoData) {
            byte[] frameBytes;
            int safeOffset = Math.max(0, offset);
            int availableBeforeOffset = Math.max(0, scratchLength - safeOffset);
            int actualSize = Math.max(0, Math.min(size, availableBeforeOffset));
            int frameStart = Math.max(0, availableBeforeOffset - actualSize);
            frameBytes = Arrays.copyOfRange(scratchBuffer, frameStart, frameStart + actualSize);

            if (safeOffset > 0 && safeOffset <= scratchLength) {
                System.arraycopy(scratchBuffer, scratchLength - safeOffset, scratchBuffer, 0, safeOffset);
                scratchLength = safeOffset;
            } else {
                scratchLength = 0;
            }

            long resolvedTimeUs = timeUs == C.TIME_UNSET ? -1L : timeUs;
            synchronized (LivePauseFrameBuffer.this) {
                if (trackType == C.TRACK_TYPE_VIDEO) {
                    boolean isKeyFrame = (flags & C.BUFFER_FLAG_KEY_FRAME) != 0;
                    offerVideoFrame(frameBytes, resolvedTimeUs, 0L, isKeyFrame, flags, cryptoData);
                } else {
                    offerAudioFrame(frameBytes, resolvedTimeUs, 0L, flags, cryptoData);
                }
            }
        }
    }
}
