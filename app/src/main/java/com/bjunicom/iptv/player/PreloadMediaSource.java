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
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.extractor.ts.TsPayloadReader;
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
import com.google.android.exoplayer2.util.TimestampAdjuster;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 后台预拉流池与自定义 MediaSource (PreloadMediaSource)
 *
 * 核心设计：
 * 1. 维护独立的 ProgressiveMediaPeriod 槽位（当前主屏频道 activeSlot + 后台预拉流频道 prevSlot / nextSlot）；
 * 2. 在用户尚未切台时，提前在后台创建 ProgressiveMediaPeriod 并调用 prepare()，拉取组播/HTTP-TS 流通过 TsExtractor
 *    实时解封装写入各自的 SampleQueue 中；
 * 3. 当用户切台、ExoPlayer 切换至目标 PreloadMediaSource 时，createPeriod() 直接交出已在后台预拉流热身的
 *    ProgressiveMediaPeriod (及其 SampleQueue)，零等待完成 prepare 并由同一个 MediaCodec flush() 解码出画；
 * 4. 旧主屏 MediaPeriod 的释放严格交由 ExoPlayer 内部的 releasePeriod() 在解绑 Renderer 之后执行，避免跨线程提前销毁 SampleQueue；
 * 5. 切台满 3 秒未继续切台时，自动停止并释放后台预拉流 ProgressiveMediaPeriod，节省光猫组播带宽。
 */
public class PreloadMediaSource extends BaseMediaSource {
    private static final String TAG = "PreloadMediaSource";

    public static final int MAX_PRELOAD_PERIODS = 1;
    private static final long PRELOAD_TARGET_BUFFER_US = 2_000_000L; // 后台 SampleQueue 预拉取目标时长 2.0s
    private static final int LOADING_CHECK_INTERVAL_BYTES = 2 * 1024 * 1024; // 2MB 检查间隔，保障完整 4K GOP 不中途阻塞

    private static volatile Field sSampleQueuesField;

    /**
     * 单个预拉流 ProgressiveMediaPeriod 槽位（每个频道流独立实例，避免原地修改引发跨会话竞态）
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
            if (released || (rawProgressivePeriod == null && backingSource == null)) {
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
    }

    /**
     * 维护当前频道与后台预拉流频道的预拉流池管理器。
     */
    public static final class PreloadPool {
        private final DataSource.Factory dataSourceFactory;
        private final ExtractorsFactory extractorsFactory;
        private final Allocator sharedAllocator;
        private PreloadedPeriodSlot activeSlot;
        private PreloadedPeriodSlot prevSlot;
        private PreloadedPeriodSlot nextSlot;
        private Looper playbackLooper;
        private Handler playbackHandler;

        public PreloadPool(DataSource.Factory dataSourceFactory, Allocator sharedAllocator) {
            this.dataSourceFactory = dataSourceFactory != null ? dataSourceFactory : new UTVStreamDataSource.Factory();
            this.sharedAllocator = sharedAllocator;
            this.extractorsFactory = createIptvTsExtractorsFactory();
        }

        /**
         * 构建专为北京联通/电信/移动 IPTV MPEG-TS 流优化的 ExtractorsFactory：
         * 1. createExtractors() 固定返回单元素数组 [TsExtractor]，使 BundledExtractorsAdapter.init() 直接命中
         *    extractors.length == 1 快速通道，跳过 sniff() 循环，彻底杜绝 UnrecognizedInputFormatException 与 Mp3Extractor 误匹配；
         * 2. 使用 TimestampAdjuster(0L) 将直播 TS 流的任意首个 33-bit PTS 自动折算偏移至 0us；
         * 3. 每个节目仅保留主视频轨道与主音频轨道，过滤无数据包的私有数据流/副音轨，防止 ProgressiveMediaPeriod.maybeFinishPrepare()
         *    因等待空 SampleQueue 的 getUpstreamFormat() 而无限卡在 STATE_BUFFERING。
         */
        public static ExtractorsFactory createIptvTsExtractorsFactory() {
            final int tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                    | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                    | DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM;

            return new ExtractorsFactory() {
                @Override
                public Extractor[] createExtractors() {
                    return new Extractor[]{createLiveIptvTsExtractor(tsFlags)};
                }

                @Override
                public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
                    return new Extractor[]{createLiveIptvTsExtractor(tsFlags)};
                }
            };
        }

        private static TsExtractor createLiveIptvTsExtractor(int tsFlags) {
            final DefaultTsPayloadReaderFactory defaultPayloadFactory =
                    new DefaultTsPayloadReaderFactory(tsFlags, Collections.emptyList());
            TsPayloadReader.Factory safePayloadFactory = new TsPayloadReader.Factory() {
                private boolean hasPrimaryVideo = false;
                private boolean hasPrimaryAudio = false;

                @Override
                public android.util.SparseArray<TsPayloadReader> createInitialPayloadReaders() {
                    return defaultPayloadFactory.createInitialPayloadReaders();
                }

                @Nullable
                @Override
                public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
                    if (isVideoStreamType(streamType)) {
                        if (hasPrimaryVideo) {
                            return null;
                        }
                        TsPayloadReader reader = defaultPayloadFactory.createPayloadReader(streamType, esInfo);
                        if (reader != null) {
                            hasPrimaryVideo = true;
                        }
                        return reader;
                    }
                    if (isAudioStreamType(streamType)) {
                        if (hasPrimaryAudio) {
                            return null;
                        }
                        TsPayloadReader reader = defaultPayloadFactory.createPayloadReader(streamType, esInfo);
                        if (reader != null) {
                            hasPrimaryAudio = true;
                        }
                        return reader;
                    }
                    // 忽略非音视频私有流 (0x05, 0x06, 0x15 ID3, 0x59 字幕, 0x86 SCTE-35, 0x101 AIT)，防止空轨道阻塞 prepare
                    return null;
                }
            };
            return new TsExtractor(
                    TsExtractor.MODE_SINGLE_PMT,
                    new TimestampAdjuster(0L),
                    safePayloadFactory,
                    112800);
        }

        private static boolean isVideoStreamType(int streamType) {
            return streamType == TsExtractor.TS_STREAM_TYPE_H264
                    || streamType == TsExtractor.TS_STREAM_TYPE_H265
                    || streamType == TsExtractor.TS_STREAM_TYPE_H262
                    || streamType == TsExtractor.TS_STREAM_TYPE_DC2_H262
                    || streamType == TsExtractor.TS_STREAM_TYPE_H263;
        }

        private static boolean isAudioStreamType(int streamType) {
            return streamType == TsExtractor.TS_STREAM_TYPE_MPA
                    || streamType == TsExtractor.TS_STREAM_TYPE_MPA_LSF
                    || streamType == TsExtractor.TS_STREAM_TYPE_AAC_ADTS
                    || streamType == TsExtractor.TS_STREAM_TYPE_AAC_LATM
                    || streamType == TsExtractor.TS_STREAM_TYPE_AC3
                    || streamType == TsExtractor.TS_STREAM_TYPE_E_AC3
                    || streamType == TsExtractor.TS_STREAM_TYPE_AC4
                    || streamType == TsExtractor.TS_STREAM_TYPE_DTS
                    || streamType == TsExtractor.TS_STREAM_TYPE_HDMV_DTS;
        }

        public void bindPlaybackLooper(Looper looper) {
            if (looper != null && this.playbackLooper != looper) {
                this.playbackLooper = looper;
                this.playbackHandler = new Handler(looper);
            }
        }

        public synchronized PreloadedPeriodSlot[] getSlots() {
            return new PreloadedPeriodSlot[]{
                    activeSlot != null ? activeSlot : new PreloadedPeriodSlot(0)
            };
        }

        public synchronized List<MediaPeriod> getActiveProgressiveMediaPeriods() {
            List<MediaPeriod> list = new ArrayList<>(MAX_PRELOAD_PERIODS);
            if (activeSlot != null && !activeSlot.released && activeSlot.rawProgressivePeriod != null) {
                list.add(activeSlot.rawProgressivePeriod);
            }
            if (prevSlot != null && !prevSlot.released && prevSlot.rawProgressivePeriod != null) {
                list.add(prevSlot.rawProgressivePeriod);
            }
            if (nextSlot != null && !nextSlot.released && nextSlot.rawProgressivePeriod != null) {
                list.add(nextSlot.rawProgressivePeriod);
            }
            return list;
        }

        /**
         * 为目标频道获取或认领 PreloadMediaSource：
         * - 若目标频道命中后台预拉流槽位 (prevSlot 或 nextSlot)，直接将其提升为 activeSlot 并标记 promotedFromPreload = true；
         * - 旧的 activeSlot 交由 ExoPlayer 在切换 MediaSource 时通过 releasePeriod() 安全释放，绝不在主线程提前销毁。
         */
        public synchronized PreloadMediaSource obtainMediaSource(Uri uri, String rawChannelUrl) {
            if (nextSlot != null && nextSlot.matches(uri, rawChannelUrl)) {
                PreloadedPeriodSlot promoted = nextSlot;
                nextSlot = null;
                promoted.promotedFromPreload = promoted.backgroundPrepareStarted;
                promoted.claimedByPlayer = true;
                activeSlot = promoted;
                return new PreloadMediaSource(this, promoted, uri, rawChannelUrl);
            }
            if (prevSlot != null && prevSlot.matches(uri, rawChannelUrl)) {
                PreloadedPeriodSlot promoted = prevSlot;
                prevSlot = null;
                promoted.promotedFromPreload = promoted.backgroundPrepareStarted;
                promoted.claimedByPlayer = true;
                activeSlot = promoted;
                return new PreloadMediaSource(this, promoted, uri, rawChannelUrl);
            }
            if (activeSlot != null && activeSlot.matches(uri, rawChannelUrl) && !activeSlot.released) {
                activeSlot.claimedByPlayer = true;
                return new PreloadMediaSource(this, activeSlot, uri, rawChannelUrl);
            }
            PreloadedPeriodSlot newActive = createSlotWithPeriod(0, uri, rawChannelUrl, false);
            newActive.claimedByPlayer = true;
            activeSlot = newActive;
            return new PreloadMediaSource(this, newActive, uri, rawChannelUrl);
        }

        /**
         * 维护当前频道与上/下一相邻频道的后台预拉流窗口
         */
        public synchronized void preloadThreeChannelWindow(Uri activeUri,
                                                           String activeRawUrl,
                                                           @Nullable Uri prevUri,
                                                           @Nullable String prevRawUrl,
                                                           @Nullable Uri nextUri,
                                                           @Nullable String nextRawUrl) {
            if (prevSlot != null) {
                boolean keepPrev = prevUri != null && prevSlot.matches(prevUri, prevRawUrl);
                if (!keepPrev) {
                    releaseSlotInternal(prevSlot);
                    prevSlot = null;
                }
            }
            if (nextSlot != null) {
                boolean keepNext = nextUri != null && nextSlot.matches(nextUri, nextRawUrl);
                if (!keepNext) {
                    releaseSlotInternal(nextSlot);
                    nextSlot = null;
                }
            }

            if (prevUri != null && !prevUri.equals(activeUri) && prevSlot == null) {
                prevSlot = createSlotWithPeriod(1, prevUri, prevRawUrl, true);
            }
            if (nextUri != null && !nextUri.equals(activeUri) && !nextUri.equals(prevUri) && nextSlot == null) {
                nextSlot = createSlotWithPeriod(2, nextUri, nextRawUrl, true);
            }
        }

        /**
         * 维护当前频道与当前切台方向上的下一个频道，并提前拉取后台频道。
         */
        public synchronized void preloadTwoChannelWindow(Uri activeUri,
                                                         String activeRawUrl,
                                                         @Nullable Uri nextUri,
                                                         @Nullable String nextRawUrl) {
            preloadThreeChannelWindow(activeUri, activeRawUrl, null, null, nextUri, nextRawUrl);
        }

        /**
         * 当用户切台 3 秒后未继续切台时调用：停止后台预拉流，仅保留当前播放频道。
         */
        public synchronized void stopBackgroundPreloadPeriods(@Nullable Uri activeUri, @Nullable String activeRawUrl) {
            if (prevSlot != null) {
                releaseSlotInternal(prevSlot);
                prevSlot = null;
            }
            if (nextSlot != null) {
                releaseSlotInternal(nextSlot);
                nextSlot = null;
            }
        }

        public synchronized boolean areBackgroundPreloadPeriodsStopped(@Nullable Uri activeUri, @Nullable String activeRawUrl) {
            boolean prevStopped = (prevSlot == null || prevSlot.released || prevSlot.rawProgressivePeriod == null);
            boolean nextStopped = (nextSlot == null || nextSlot.released || nextSlot.rawProgressivePeriod == null);
            return prevStopped && nextStopped;
        }

        public synchronized void releaseAll() {
            if (prevSlot != null) {
                releaseSlotInternal(prevSlot);
                prevSlot = null;
            }
            if (nextSlot != null) {
                releaseSlotInternal(nextSlot);
                nextSlot = null;
            }
            if (activeSlot != null) {
                releaseSlotInternal(activeSlot);
                activeSlot = null;
            }
        }

        private PreloadedPeriodSlot createSlotWithPeriod(int slotIndex,
                                                         Uri uri,
                                                         String rawChannelUrl,
                                                         boolean startImmediatePreload) {
            PreloadedPeriodSlot slot = new PreloadedPeriodSlot(slotIndex);
            slot.uri = uri;
            slot.rawChannelUrl = rawChannelUrl;
            slot.released = false;
            slot.claimedByPlayer = !startImmediatePreload;
            slot.periodPrepared = false;
            slot.backgroundPrepareStarted = startImmediatePreload;
            slot.promotedFromPreload = false;

            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(uri)
                    .setCustomCacheKey(rawChannelUrl != null ? rawChannelUrl : uri.toString())
                    .build();

            ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(
                    dataSourceFactory, extractorsFactory);
            factory.setContinueLoadingCheckIntervalBytes(LOADING_CHECK_INTERVAL_BYTES);
            ProgressiveMediaSource progSource = factory.createMediaSource(mediaItem);
            slot.backingSource = progSource;

            ensurePeriodCreatedSynchronously(slot, progSource, startImmediatePreload);
            return slot;
        }

        synchronized void ensurePeriodCreatedSynchronously(PreloadedPeriodSlot slot,
                                                           ProgressiveMediaSource expectedSource,
                                                           boolean startImmediatePreload) {
            if (slot == null || slot.released || slot.backingSource != expectedSource) {
                return;
            }
            if (slot.rawProgressivePeriod != null && slot.periodAdapter != null) {
                if (startImmediatePreload) {
                    slot.periodAdapter.startPreloadPrepare();
                }
                return;
            }
            try {
                MediaSourceCaller caller = (source, timeline) -> {
                };
                slot.backingCaller = caller;
                expectedSource.prepareSource(caller, null, PlayerId.UNSET);
                MediaPeriod period = expectedSource.createPeriod(
                        new MediaPeriodId(new Object()), sharedAllocator, 0L);
                slot.rawProgressivePeriod = period;
                slot.periodAdapter = new PreloadedMediaPeriodAdapter(this, slot, period);

                if (startImmediatePreload) {
                    slot.periodAdapter.startPreloadPrepare();
                }
                Log.d(TAG, "Created ProgressiveMediaPeriod in slot #" + slot.slotIndex
                        + " (preload=" + startImmediatePreload + ") for " + slot.uri);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to init ProgressiveMediaPeriod in slot #" + slot.slotIndex + ": " + t.getMessage());
            }
        }

        synchronized void releaseSlotInternal(PreloadedPeriodSlot slot) {
            if (slot == null || slot.released) {
                return;
            }
            slot.released = true;
            slot.claimedByPlayer = false;
            slot.periodPrepared = false;
            slot.backgroundPrepareStarted = false;
            slot.promotedFromPreload = false;
            if (slot == activeSlot) {
                activeSlot = null;
            }
            if (slot == prevSlot) {
                prevSlot = null;
            }
            if (slot == nextSlot) {
                nextSlot = null;
            }
            final MediaPeriod period = slot.rawProgressivePeriod;
            final ProgressiveMediaSource source = slot.backingSource;
            final MediaSourceCaller caller = slot.backingCaller;
            slot.rawProgressivePeriod = null;
            slot.periodAdapter = null;
            slot.backingSource = null;
            slot.backingCaller = null;

            Runnable releaseAction = () -> {
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
            };

            if (playbackLooper != null && Looper.myLooper() != playbackLooper && playbackHandler != null) {
                playbackHandler.post(releaseAction);
            } else {
                releaseAction.run();
            }
        }
    }

    /**
     * 包装已预拉流的 ProgressiveMediaPeriod：
     * - 在后台预拉流阶段先行调用 rawPeriod.prepare() 并将组播流写入 SampleQueue；
     * - 当 ExoPlayer 切台接管并调用 prepare(playerCallback) 时，若后台已完成 prepare 则立即回调 onPrepared，
     *   避免二次调用 ProgressiveMediaPeriod.prepare() 造成重新拉流。
     */
    public static final class PreloadedMediaPeriodAdapter implements MediaPeriod, MediaPeriod.Callback {
        private final PreloadPool ownerPool;
        private final PreloadedPeriodSlot ownerSlot;
        private final MediaPeriod rawPeriod;
        private volatile MediaPeriod.Callback playerCallback;
        private boolean rawPrepareInvoked = false;
        private boolean firstTrackSelectionDone = false;

        PreloadedMediaPeriodAdapter(PreloadPool ownerPool, PreloadedPeriodSlot ownerSlot, MediaPeriod rawPeriod) {
            this.ownerPool = ownerPool;
            this.ownerSlot = ownerSlot;
            this.rawPeriod = rawPeriod;
        }

        public MediaPeriod getRawProgressivePeriod() {
            return rawPeriod;
        }

        private void runOnPlaybackLooper(Runnable action) {
            Looper targetLooper = ownerPool != null ? ownerPool.playbackLooper : null;
            Handler targetHandler = ownerPool != null ? ownerPool.playbackHandler : null;
            if (targetLooper == null || Looper.myLooper() == targetLooper || targetHandler == null) {
                action.run();
            } else {
                targetHandler.post(action);
            }
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
                runOnPlaybackLooper(() -> {
                    Callback cb = this.playerCallback;
                    if (cb != null && !ownerSlot.released) {
                        cb.onPrepared(this);
                    }
                });
                return;
            }
            if (!rawPrepareInvoked) {
                rawPrepareInvoked = true;
                rawPeriod.prepare(this, 0L);
            } else {
                // 若后台预拉流已在准备中，确保 loadCondition 处于打开状态以继续完成 prepare
                rawPeriod.continueLoading(0L);
            }
        }

        @Override
        public synchronized void onPrepared(MediaPeriod mediaPeriod) {
            ownerSlot.periodPrepared = true;
            if (playerCallback != null) {
                runOnPlaybackLooper(() -> {
                    Callback cb = this.playerCallback;
                    if (cb != null && !ownerSlot.released) {
                        cb.onPrepared(this);
                    }
                });
            }
        }

        @Override
        public synchronized void onContinueLoadingRequested(MediaPeriod source) {
            if (ownerSlot.released) {
                return;
            }
            if (!ownerSlot.periodPrepared) {
                // 在完成首次 prepare (解析出 SPS/PPS 与音频 Format) 前，无条件继续拉流，绝不中途阻塞
                rawPeriod.continueLoading(0L);
                return;
            }
            if (playerCallback != null) {
                runOnPlaybackLooper(() -> {
                    Callback cb = this.playerCallback;
                    if (cb != null && !ownerSlot.released) {
                        cb.onContinueLoadingRequested(this);
                    }
                });
            } else if (!ownerSlot.claimedByPlayer) {
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
            // 首次轨道选择强制传入 0L 且不在 selectTracks 前调用 discardTo，
            // 确保 SampleQueue.getReadIndex() == 0，防止 ProgressiveMediaPeriod 触发 seekToUs(0) 清空缓冲与重置流
            long effectivePositionUs = firstTrackSelectionDone ? positionUs : 0L;
            firstTrackSelectionDone = true;
            return rawPeriod.selectTracks(
                    selections, mayRetainStreamFlags, streams, streamResetFlags, effectivePositionUs);
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
            if (positionUs <= 0L) {
                return 0L;
            }
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
                /* isDynamic= */ false,
                /* useLiveConfiguration= */ false,
                /* manifest= */ null,
                mediaItem);
        refreshSourceInfo(timeline);
    }

    @Override
    public void maybeThrowSourceInfoRefreshError() throws IOException {
    }

    @Override
    public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
        synchronized (ownerPool) {
            if (boundSlot != null && !boundSlot.released) {
                if (boundSlot.periodAdapter == null && boundSlot.backingSource != null) {
                    ownerPool.ensurePeriodCreatedSynchronously(boundSlot, boundSlot.backingSource, false);
                }
                if (boundSlot.periodAdapter != null) {
                    boundSlot.claimedByPlayer = true;
                    return boundSlot.periodAdapter;
                }
            }
        }
        throw new IllegalStateException("Unable to create MediaPeriod for " + mediaItem);
    }

    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
        if (boundSlot != null) {
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
