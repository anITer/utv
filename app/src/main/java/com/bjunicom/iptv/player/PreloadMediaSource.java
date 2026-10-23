package com.bjunicom.iptv.player;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.analytics.PlayerId;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.source.BaseMediaSource;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.SampleQueue;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后台预拉流池与自定义 MediaSource (PreloadMediaSource)
 *
 * 核心设计：
 * 1. 在后台维护 3 个独立的 ProgressiveMediaPeriod 槽位（当前频道 + 上一频道 + 下一频道）；
 * 2. 在用户尚未切台时，提前在后台创建 ProgressiveMediaPeriod 并调用 prepare()，拉取组播/HTTP-TS 流通过 TsExtractor
 *    实时解封装写入各自的 SampleQueue 中；
 * 3. 当用户切台、ExoPlayer 切换至目标 PreloadMediaSource 时，createPeriod() 直接交出已在后台预拉流热身的
 *    ProgressiveMediaPeriod (及其 SampleQueue)，零等待完成 prepare 并由同一个 MediaCodec flush() 解码出画；
 * 4. 切台满 3 秒未继续切台时，支持自动停止并释放后台 2 个预拉流 ProgressiveMediaPeriod，节省光猫组播带宽。
 */
public class PreloadMediaSource extends BaseMediaSource {
    private static final String TAG = "PreloadMediaSource";

    public static final int MAX_PRELOAD_PERIODS = 3;
    private static final long PRELOAD_TARGET_BUFFER_US = 2_000_000L; // 后台 SampleQueue 预拉取目标时长 2.0s
    private static final long PRELOAD_MAX_BUFFER_US = 3_500_000L;    // 超过 3.5s 时自动裁剪至最新关键帧

    private static volatile Field sSampleQueuesField;

    /**
     * 单个预拉流 ProgressiveMediaPeriod 槽位
     */
    public static final class PreloadedPeriodSlot {
        public final int slotIndex;
        public Uri uri;
        public String rawChannelUrl;
        public ProgressiveMediaSource backingSource;
        public MediaSourceCaller backingCaller;
        public MediaPeriod rawProgressivePeriod; // 实际类型为 com.google.android.exoplayer2.source.ProgressiveMediaPeriod
        public PreloadedMediaPeriodAdapter periodAdapter;
        public volatile boolean backgroundPrepareStarted = false;
        public volatile boolean periodPrepared = false;
        public volatile boolean claimedByPlayer = false;
        public volatile boolean promotedFromPreload = false;
        public volatile boolean released = false;

        public PreloadedPeriodSlot(int slotIndex) {
            this.slotIndex = slotIndex;
        }

        public boolean matches(Uri targetUri, String targetRawUrl) {
            if (released || rawProgressivePeriod == null) {
                return false;
            }
            if (targetUri != null && uri != null && targetUri.equals(uri)) {
                return true;
            }
            if (!TextUtils.isEmpty(targetRawUrl)
                    && !TextUtils.isEmpty(rawChannelUrl)
                    && targetRawUrl.equals(rawChannelUrl)) {
                return true;
            }
            return false;
        }

        /**
         * 获取底层 ProgressiveMediaPeriod 内部维护的 SampleQueue 数组
         */
        public SampleQueue[] getSampleQueues() {
            return extractSampleQueuesFromPeriod(rawProgressivePeriod);
        }

        /**
         * 计算当前 ProgressiveMediaPeriod 的 SampleQueue 中已写入的样本总时长 (us)
         */
        public long getSampleQueueBufferedDurationUs() {
            SampleQueue[] queues = getSampleQueues();
            long maxBufferedUs = 0L;
            for (SampleQueue sq : queues) {
                if (sq != null) {
                    long firstUs = sq.getFirstTimestampUs();
                    long largestUs = sq.getLargestQueuedTimestampUs();
                    if (firstUs != Long.MIN_VALUE && largestUs != Long.MIN_VALUE && largestUs >= firstUs) {
                        maxBufferedUs = Math.max(maxBufferedUs, largestUs - firstUs);
                    }
                }
            }
            return maxBufferedUs;
        }

        /**
         * 将 SampleQueue 中过期的旧 GOP 裁剪至最新关键帧起点，保持预拉流始终处于最新直播边缘
         */
        public void trimSampleQueuesToLiveKeyframe() {
            SampleQueue[] queues = getSampleQueues();
            for (SampleQueue sq : queues) {
                if (sq != null) {
                    long firstUs = sq.getFirstTimestampUs();
                    long largestUs = sq.getLargestQueuedTimestampUs();
                    if (firstUs != Long.MIN_VALUE
                            && largestUs != Long.MIN_VALUE
                            && (largestUs - firstUs) > PRELOAD_MAX_BUFFER_US) {
                        long targetKeyframeTimeUs = largestUs - 1_500_000L;
                        sq.discardTo(targetKeyframeTimeUs, true, true);
                    }
                }
            }
        }
    }

    /**
     * 维护 3 个独立 ProgressiveMediaPeriod 的预拉流池管理器
     */
    public static final class PreloadPool {
        private final DataSource.Factory dataSourceFactory;
        private final ExtractorsFactory extractorsFactory;
        private final Allocator sharedAllocator;
        private final PreloadedPeriodSlot[] slots = new PreloadedPeriodSlot[MAX_PRELOAD_PERIODS];
        private Looper playbackLooper;
        private Handler playbackHandler;

        public PreloadPool(DataSource.Factory dataSourceFactory, Allocator sharedAllocator) {
            this.dataSourceFactory = dataSourceFactory != null ? dataSourceFactory : new UTVStreamDataSource.Factory();
            this.sharedAllocator = sharedAllocator;
            this.extractorsFactory = createIptvTsExtractorsFactory();
            for (int i = 0; i < MAX_PRELOAD_PERIODS; i++) {
                slots[i] = new PreloadedPeriodSlot(i);
            }
        }

        public static ExtractorsFactory createIptvTsExtractorsFactory() {
            DefaultExtractorsFactory factory = new DefaultExtractorsFactory()
                    .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
                    .setTsExtractorFlags(
                            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                                    | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                    .setTsExtractorTimestampSearchBytes(112800);
            return factory;
        }

        public void bindPlaybackLooper(Looper looper) {
            if (looper != null && this.playbackLooper != looper) {
                this.playbackLooper = looper;
                this.playbackHandler = new Handler(looper);
            }
        }

        public PreloadedPeriodSlot[] getSlots() {
            return slots;
        }

        public List<MediaPeriod> getActiveProgressiveMediaPeriods() {
            List<MediaPeriod> list = new ArrayList<>(MAX_PRELOAD_PERIODS);
            for (PreloadedPeriodSlot slot : slots) {
                if (slot != null && !slot.released && slot.rawProgressivePeriod != null) {
                    list.add(slot.rawProgressivePeriod);
                }
            }
            return list;
        }

        /**
         * 为目标频道获取或认领 PreloadMediaSource：
         * 若该频道已在 3 槽位预拉流池中提前创建了 ProgressiveMediaPeriod，直接复用该槽位并标记 promotedFromPreload = true。
         */
        public synchronized PreloadMediaSource obtainMediaSource(Uri uri, String rawChannelUrl) {
            PreloadedPeriodSlot matched = findMatchingSlot(uri, rawChannelUrl);
            if (matched != null) {
                matched.promotedFromPreload = matched.backgroundPrepareStarted;
                return new PreloadMediaSource(this, matched, uri, rawChannelUrl);
            }
            PreloadedPeriodSlot targetSlot = selectSlotForAllocation(null, null);
            prepareSlotInBackground(targetSlot, uri, rawChannelUrl, false);
            return new PreloadMediaSource(this, targetSlot, uri, rawChannelUrl);
        }

        /**
         * 在后台同时维护 3 个独立的 ProgressiveMediaPeriod（当前频道 + 上一频道 + 下一频道），
         * 对尚未切入的相邻频道提前执行 prepare() 并拉取组播流写入各自的 SampleQueue。
         */
        public synchronized void preloadThreeChannelWindow(Uri activeUri,
                                                           String activeRawUrl,
                                                           @Nullable Uri prevUri,
                                                           @Nullable String prevRawUrl,
                                                           @Nullable Uri nextUri,
                                                           @Nullable String nextRawUrl) {
            // 1. 释放不再属于 (active, prev, next) 窗口的旧槽位
            for (PreloadedPeriodSlot slot : slots) {
                if (slot == null || slot.rawProgressivePeriod == null || slot.released) {
                    continue;
                }
                boolean isKeep = slot.matches(activeUri, activeRawUrl)
                        || (prevUri != null && slot.matches(prevUri, prevRawUrl))
                        || (nextUri != null && slot.matches(nextUri, nextRawUrl));
                if (!isKeep && !slot.claimedByPlayer) {
                    releaseSlotInternal(slot);
                }
            }

            // 2. 确保上一频道 (prevUri) 在独立槽位中提前创建 ProgressiveMediaPeriod 并后台预拉流写入 SampleQueue
            if (prevUri != null && !prevUri.equals(activeUri)) {
                PreloadedPeriodSlot prevSlot = findMatchingSlot(prevUri, prevRawUrl);
                if (prevSlot == null) {
                    prevSlot = selectSlotForAllocation(activeUri, nextUri);
                    if (prevSlot != null) {
                        prepareSlotInBackground(prevSlot, prevUri, prevRawUrl, true);
                    }
                }
            }

            // 3. 确保下一频道 (nextUri) 在独立槽位中提前创建 ProgressiveMediaPeriod 并后台预拉流写入 SampleQueue
            if (nextUri != null && !nextUri.equals(activeUri) && !nextUri.equals(prevUri)) {
                PreloadedPeriodSlot nextSlot = findMatchingSlot(nextUri, nextRawUrl);
                if (nextSlot == null) {
                    nextSlot = selectSlotForAllocation(activeUri, prevUri);
                    if (nextSlot != null) {
                        prepareSlotInBackground(nextSlot, nextUri, nextRawUrl, true);
                    }
                }
            }
        }

        /**
         * 当用户切台 3 秒后未继续切台时调用：停止并释放后台 2 个预拉流 ProgressiveMediaPeriod，仅保留当前播放频道
         */
        public synchronized void stopBackgroundPreloadPeriods(@Nullable Uri activeUri, @Nullable String activeRawUrl) {
            for (PreloadedPeriodSlot slot : slots) {
                if (slot == null || slot.rawProgressivePeriod == null || slot.released) {
                    continue;
                }
                boolean isActive = (activeUri != null && slot.matches(activeUri, activeRawUrl)) || slot.claimedByPlayer;
                if (!isActive) {
                    releaseSlotInternal(slot);
                }
            }
        }

        public synchronized boolean areBackgroundPreloadPeriodsStopped(@Nullable Uri activeUri, @Nullable String activeRawUrl) {
            for (PreloadedPeriodSlot slot : slots) {
                if (slot != null && !slot.released && slot.rawProgressivePeriod != null) {
                    boolean isActive = (activeUri != null && slot.matches(activeUri, activeRawUrl)) || slot.claimedByPlayer;
                    if (!isActive) {
                        return false;
                    }
                }
            }
            return true;
        }

        public synchronized void releaseAll() {
            for (PreloadedPeriodSlot slot : slots) {
                if (slot != null) {
                    releaseSlotInternal(slot);
                }
            }
        }

        @Nullable
        public synchronized PreloadedPeriodSlot findMatchingSlot(Uri uri, String rawUrl) {
            for (PreloadedPeriodSlot slot : slots) {
                if (slot != null && slot.matches(uri, rawUrl)) {
                    return slot;
                }
            }
            return null;
        }

        @Nullable
        private PreloadedPeriodSlot selectSlotForAllocation(@Nullable Uri protectUriA, @Nullable Uri protectUriB) {
            for (PreloadedPeriodSlot slot : slots) {
                if (slot.rawProgressivePeriod == null || slot.released) {
                    return slot;
                }
            }
            for (PreloadedPeriodSlot slot : slots) {
                if (!slot.claimedByPlayer
                        && (protectUriA == null || !protectUriA.equals(slot.uri))
                        && (protectUriB == null || !protectUriB.equals(slot.uri))) {
                    releaseSlotInternal(slot);
                    return slot;
                }
            }
            for (PreloadedPeriodSlot slot : slots) {
                if (!slot.claimedByPlayer) {
                    releaseSlotInternal(slot);
                    return slot;
                }
            }
            return slots[0];
        }

        private void prepareSlotInBackground(final PreloadedPeriodSlot slot,
                                             final Uri uri,
                                             final String rawChannelUrl,
                                             final boolean startImmediatePreload) {
            if (slot == null || uri == null) {
                return;
            }
            releaseSlotInternal(slot);

            slot.uri = uri;
            slot.rawChannelUrl = rawChannelUrl;
            slot.released = false;
            slot.claimedByPlayer = false;
            slot.periodPrepared = false;
            slot.backgroundPrepareStarted = startImmediatePreload;
            slot.promotedFromPreload = false;

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(uri)
                    .setCustomCacheKey(rawChannelUrl != null ? rawChannelUrl : uri.toString())
                    .build();

            ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(
                    dataSourceFactory, extractorsFactory);
            factory.setContinueLoadingCheckIntervalBytes(256 * 1024);
            final ProgressiveMediaSource progSource = factory.createMediaSource(mediaItem);
            slot.backingSource = progSource;

            Runnable createPeriodAction = () -> {
                if (slot.released || slot.backingSource != progSource) {
                    return;
                }
                try {
                    MediaSourceCaller caller = (source, timeline) -> {
                    };
                    slot.backingCaller = caller;
                    progSource.prepareSource(caller, null, PlayerId.UNSET);
                    MediaPeriod period = progSource.createPeriod(
                            new MediaPeriodId(new Object()), sharedAllocator, 0L);
                    slot.rawProgressivePeriod = period;
                    slot.periodAdapter = new PreloadedMediaPeriodAdapter(slot, period);

                    if (startImmediatePreload) {
                        slot.periodAdapter.startPreloadPrepare();
                    }
                    Log.d(TAG, "Created ProgressiveMediaPeriod in slot #" + slot.slotIndex
                            + " (preload=" + startImmediatePreload + ") for " + uri);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to init ProgressiveMediaPeriod in slot #" + slot.slotIndex + ": " + t.getMessage());
                }
            };

            if (playbackHandler != null && Looper.myLooper() != playbackLooper) {
                createPeriodAction.run();
            } else {
                createPeriodAction.run();
            }
        }

        synchronized void releaseSlotInternal(PreloadedPeriodSlot slot) {
            if (slot == null) {
                return;
            }
            slot.released = true;
            slot.claimedByPlayer = false;
            slot.periodPrepared = false;
            slot.backgroundPrepareStarted = false;
            slot.promotedFromPreload = false;
            final MediaPeriod period = slot.rawProgressivePeriod;
            final ProgressiveMediaSource source = slot.backingSource;
            final MediaSourceCaller caller = slot.backingCaller;
            slot.rawProgressivePeriod = null;
            slot.periodAdapter = null;
            slot.backingSource = null;
            slot.backingCaller = null;
            slot.uri = null;
            slot.rawChannelUrl = null;

            if (period != null && source != null) {
                try {
                    source.releasePeriod(period);
                } catch (Throwable ignored) {
                }
            }
            if (source != null && caller != null) {
                try {
                    source.releaseSource(caller);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 包装已预拉流的 ProgressiveMediaPeriod：
     * - 在后台预拉流阶段先行调用 rawPeriod.prepare() 并将组播流写入 SampleQueue；
     * - 当 ExoPlayer 切台接管并调用 prepare(playerCallback) 时，若后台已完成 prepare 则立即同步回调 onPrepared，
     *   避免二次调用 ProgressiveMediaPeriod.prepare() 造成重新拉流。
     */
    public static final class PreloadedMediaPeriodAdapter implements MediaPeriod, MediaPeriod.Callback {
        private final PreloadedPeriodSlot ownerSlot;
        private final MediaPeriod rawPeriod;
        private MediaPeriod.Callback playerCallback;
        private boolean rawPrepareInvoked = false;

        PreloadedMediaPeriodAdapter(PreloadedPeriodSlot ownerSlot, MediaPeriod rawPeriod) {
            this.ownerSlot = ownerSlot;
            this.rawPeriod = rawPeriod;
        }

        public MediaPeriod getRawProgressivePeriod() {
            return rawPeriod;
        }

        synchronized void startPreloadPrepare() {
            if (!rawPrepareInvoked && !ownerSlot.released) {
                rawPrepareInvoked = true;
                ownerSlot.backgroundPrepareStarted = true;
                rawPeriod.prepare(this, 0L);
            }
        }

        @Override
        public synchronized void prepare(Callback callback, long positionUs) {
            this.playerCallback = callback;
            ownerSlot.claimedByPlayer = true;
            if (ownerSlot.periodPrepared) {
                callback.onPrepared(this);
                return;
            }
            if (!rawPrepareInvoked) {
                rawPrepareInvoked = true;
                rawPeriod.prepare(this, positionUs);
            }
        }

        @Override
        public synchronized void onPrepared(MediaPeriod mediaPeriod) {
            ownerSlot.periodPrepared = true;
            ownerSlot.trimSampleQueuesToLiveKeyframe();
            if (playerCallback != null) {
                playerCallback.onPrepared(this);
            }
        }

        @Override
        public synchronized void onContinueLoadingRequested(MediaPeriod source) {
            if (playerCallback != null) {
                playerCallback.onContinueLoadingRequested(this);
            } else if (!ownerSlot.released && !ownerSlot.claimedByPlayer) {
                // 后台预拉流阶段：保持 SampleQueue 中有约 2 秒的最新 GOP 样本待命
                ownerSlot.trimSampleQueuesToLiveKeyframe();
                if (ownerSlot.getSampleQueueBufferedDurationUs() < PRELOAD_TARGET_BUFFER_US) {
                    rawPeriod.continueLoading(0L);
                }
            }
        }

        @Override
        public void maybeThrowPrepareError() throws IOException {
            rawPeriod.maybeThrowPrepareError();
        }

        @Override
        public TrackGroupArray getTrackGroups() {
            return rawPeriod.getTrackGroups();
        }

        @Override
        public long selectTracks(ExoTrackSelection[] selections,
                                 boolean[] mayRetainStreamFlags,
                                 SampleStream[] streams,
                                 boolean[] streamResetFlags,
                                 long positionUs) {
            ownerSlot.trimSampleQueuesToLiveKeyframe();
            return rawPeriod.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs);
        }

        @Override
        public void discardBuffer(long positionUs, boolean toKeyframe) {
            rawPeriod.discardBuffer(positionUs, toKeyframe);
        }

        @Override
        public void reevaluateBuffer(long positionUs) {
            rawPeriod.reevaluateBuffer(positionUs);
        }

        @Override
        public boolean continueLoading(long positionUs) {
            return rawPeriod.continueLoading(positionUs);
        }

        @Override
        public boolean isLoading() {
            return rawPeriod.isLoading();
        }

        @Override
        public long getNextLoadPositionUs() {
            return rawPeriod.getNextLoadPositionUs();
        }

        @Override
        public long readDiscontinuity() {
            return rawPeriod.readDiscontinuity();
        }

        @Override
        public long getBufferedPositionUs() {
            return rawPeriod.getBufferedPositionUs();
        }

        @Override
        public long seekToUs(long positionUs) {
            return rawPeriod.seekToUs(positionUs);
        }

        @Override
        public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
            return rawPeriod.getAdjustedSeekPositionUs(positionUs, seekParameters);
        }
    }

    private final PreloadPool ownerPool;
    private final PreloadedPeriodSlot boundSlot;
    private final MediaItem mediaItem;

    PreloadMediaSource(PreloadPool ownerPool,
                       PreloadedPeriodSlot boundSlot,
                       Uri uri,
                       String rawChannelUrl) {
        this.ownerPool = ownerPool;
        this.boundSlot = boundSlot;
        this.mediaItem = new MediaItem.Builder()
                .setUri(uri)
                .setCustomCacheKey(rawChannelUrl != null ? rawChannelUrl : (uri != null ? uri.toString() : ""))
                .build();
    }

    public PreloadedPeriodSlot getBoundSlot() {
        return boundSlot;
    }

    @Override
    public MediaItem getMediaItem() {
        return mediaItem;
    }

    @Override
    protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
        Timeline timeline = new SinglePeriodTimeline(
                C.TIME_UNSET,
                /* isSeekable= */ false,
                /* isDynamic= */ true,
                /* useLiveConfiguration= */ true,
                /* manifest= */ null,
                mediaItem);
        refreshSourceInfo(timeline);
    }

    @Override
    public void maybeThrowSourceInfoRefreshError() throws IOException {
        if (boundSlot != null && boundSlot.rawProgressivePeriod != null && boundSlot.periodPrepared) {
            boundSlot.rawProgressivePeriod.maybeThrowPrepareError();
        }
    }

    @Override
    public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
        if (boundSlot != null && !boundSlot.released && boundSlot.periodAdapter != null) {
            boundSlot.claimedByPlayer = true;
            return boundSlot.periodAdapter;
        }
        if (boundSlot != null && boundSlot.uri != null) {
            ownerPool.prepareSlotInBackground(boundSlot, boundSlot.uri, boundSlot.rawChannelUrl, false);
            if (boundSlot.periodAdapter != null) {
                boundSlot.claimedByPlayer = true;
                return boundSlot.periodAdapter;
            }
        }
        throw new IllegalStateException("Unable to create MediaPeriod for " + mediaItem);
    }

    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
        if (boundSlot != null) {
            boundSlot.claimedByPlayer = false;
            // 释放旧主屏 Period，使其在下一次窗口更新或 3s 空闲时安全回收
            ownerPool.releaseSlotInternal(boundSlot);
        }
    }

    @Override
    protected void releaseSourceInternal() {
    }

    /**
     * 反射提取 ProgressiveMediaPeriod 内部的 SampleQueue[] 数组
     */
    public static SampleQueue[] extractSampleQueuesFromPeriod(MediaPeriod period) {
        if (period instanceof PreloadedMediaPeriodAdapter) {
            period = ((PreloadedMediaPeriodAdapter) period).getRawProgressivePeriod();
        }
        if (period == null) {
            return new SampleQueue[0];
        }
        try {
            Field f = sSampleQueuesField;
            if (f == null) {
                f = period.getClass().getDeclaredField("sampleQueues");
                f.setAccessible(true);
                sSampleQueuesField = f;
            }
            Object val = f.get(period);
            if (val instanceof SampleQueue[]) {
                return (SampleQueue[]) val;
            }
        } catch (Throwable ignored) {
        }
        return new SampleQueue[0];
    }
}
