package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bjunicom.iptv.config.IptvSettings;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DecoderAudioRenderer;
import com.google.android.exoplayer2.decoder.CryptoConfig;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.PlaceholderSurface;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.video.VideoSize;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioSink.UnexpectedDiscontinuityException;
import com.google.android.exoplayer2.audio.AudioSink.WriteException;
import com.google.android.exoplayer2.audio.AudioSink.InitializationException;
import com.google.android.exoplayer2.audio.AudioSink.ConfigurationException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.audio.AuxEffectInfo;
import com.google.android.exoplayer2.audio.AudioAttributes;
import java.nio.ByteBuffer;

/**
 * 基于 ExoPlayer 的实例级池化播放器模块 (UTVPlayer)
 *
 * 核心架构特性：
 * 1. 与 IjkPlayer、MediaPlayer 完全一致的 Player 实例级双槽位池化（非全局单例）：
 *    - 由 {@link IjkVideoView} 维护 2 个独立的 {@link UTVPlayer} 实例（mActiveSlot 主屏播放当前频道 + mBackgroundSlot 离屏静音预热下一个频道）；
 *    - 切台命中后台槽位时直接将离屏预热的 UTVPlayer 绑定至主屏 Surface 并恢复音量，实现毫秒级切台；
 *    - 单个槽位内部重用时，通过自定义 {@link AdaptiveMediaCodecVideoRenderer} 将 MediaFormat 最大宽高固定为 3840x2160，
 *      触发 {@code canReuseCodec() -> REUSE_RESULT_YES_WITH_FLUSH} 直接 {@code codec.flush()} 复用解码器。
 * 2. ijkplayer (libijkffmpeg.so) MP2 音频解码器集成：
 *    - 对 IPTV 普遍采用的 MP2 (audio/mpeg-L2) 伴音，通过 {@link Mp2DecoderAudioRenderer} 调用 ijkplayer 内置的
 *      FFmpeg MP2 解码器输出 16-bit PCM 送入独立 AudioSink，彻底解决 ExoPlayer 音频严重失真问题。
 * 3. SurfaceView 动态绑定与 PlaceholderSurface 离屏挂靠：
 *    - 离屏预热或外部 SurfaceView 销毁时自动挂靠在 PlaceholderSurface 上，避免离屏 BufferQueue 积压阻塞。
 */
public class UTVPlayer extends AbstractMediaPlayer {
    private static final String TAG = "UTVPlayer";

    public static final int MAX_ADAPTIVE_WIDTH = 3840;
    public static final int MAX_ADAPTIVE_HEIGHT = 2160;
    public static final int MAX_ADAPTIVE_INPUT_SIZE = 4 * 1024 * 1024;

    private final Context appContext;
    private final IptvSettings settings;
    private final UTVLoadControl loadControl;
    private final DataSource.Factory dataSourceFactory;
    private final PreloadMediaSource.PreloadPool preloadPool;
    private final ExoPlayer exoPlayer;

    private final AtomicInteger codecReuseHitCount = new AtomicInteger(0);
    private final AtomicInteger codecInitializedCount = new AtomicInteger(0);
    private volatile String activeVideoDecoderName = "";
    private volatile String activeAudioDecoderName = "";
    private String lastAppliedCodecConfigSignature = "";

    private PlaceholderSurface exoPlaceholderSurface;
    private SurfaceTexture fallbackSurfaceTexture;
    private Surface fallbackPlaceholderSurface;
    private volatile boolean attachedToPlaceholderSurface = false;

    private Uri currentUri;
    private String currentRawChannelUrl;
    private float currentVolume = 1.0f;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private int videoSarNum = 1;
    private int videoSarDen = 1;
    private boolean preparedNotified = false;
    private boolean promotedFromPreload = false;

    /**
     * 判断指定视频 MIME 类型当前是否应使用硬件加速解码：
     * - 默认开启硬解 (H.264 与 HEVC 默认均为 true)；
     * - 当用户在设置中取消勾选 H.264 硬解或 HEVC 硬解时返回 false，自动切换为软解。
     */
    public static boolean isHardwareDecodingPreferred(@Nullable String mimeType, @Nullable IptvSettings settings) {
        if (settings == null) {
            return true;
        }
        if (mimeType != null) {
            String lower = mimeType.toLowerCase(Locale.US);
            if (lower.contains("hevc") || lower.contains("h265") || lower.contains("dolby-vision")) {
                return settings.isMediaCodecEnabled() && settings.isMediaCodecHevcEnabled();
            }
        }
        return settings.isMediaCodecEnabled();
    }

    /**
     * 判断 MediaCodecInfo 是否为软件解码器 (如 c2.android.avc.decoder / OMX.google.h264.decoder)
     */
    public static boolean isSoftwareCodecInfo(@Nullable MediaCodecInfo info) {
        if (info == null) {
            return false;
        }
        if (info.softwareOnly) {
            return true;
        }
        if (isSoftwareDecoderName(info.name)) {
            return true;
        }
        return !info.hardwareAccelerated;
    }

    public static boolean isSoftwareDecoderName(@Nullable String decoderName) {
        if (TextUtils.isEmpty(decoderName)) {
            return false;
        }
        String lower = decoderName.toLowerCase(Locale.US);
        return lower.startsWith("omx.google.")
                || lower.startsWith("c2.android.")
                || lower.contains(".sw.")
                || lower.contains("ffmpeg")
                || lower.contains("avcodec");
    }

    /**
     * 根据用户硬解/软解设置对候选解码器列表排序与过滤：
     * - 硬解模式 (默认)：硬件解码器优先，软件解码器作为兜底；
     * - 软解模式 (用户关闭硬解)：软件解码器 (c2.android.* / OMX.google.*) 优先。
     */
    public static List<MediaCodecInfo> selectDecodersForSettings(List<MediaCodecInfo> allDecoders,
                                                                 @Nullable String mimeType,
                                                                 @Nullable IptvSettings settings) {
        if (allDecoders == null || allDecoders.isEmpty()) {
            return allDecoders;
        }
        if (mimeType != null && !mimeType.toLowerCase(Locale.US).startsWith("video/")) {
            return allDecoders;
        }
        boolean preferHardware = isHardwareDecodingPreferred(mimeType, settings);
        List<MediaCodecInfo> hwList = new ArrayList<>();
        List<MediaCodecInfo> swList = new ArrayList<>();
        for (MediaCodecInfo info : allDecoders) {
            if (isSoftwareCodecInfo(info)) {
                swList.add(info);
            } else {
                hwList.add(info);
            }
        }
        List<MediaCodecInfo> result = new ArrayList<>(allDecoders.size());
        if (preferHardware) {
            result.addAll(hwList);
            result.addAll(swList);
        } else {
            result.addAll(swList);
            result.addAll(hwList);
        }
        return result;
    }

    /**
     * 支持硬解/软解动态切换的自定义 MediaCodecSelector：
     * 对 MP2 (audio/mpeg-L2) 音频强制交由内置的 ijkplayer FFmpeg MP2 解码渲染器处理，
     * 避免部分机顶盒/电视固件将 audio/mpeg-L2 错误映射至系统 MP3 解码器或残缺 OMX 组件导致破音失真。
     */
    public static final class UTVMediaCodecSelector implements MediaCodecSelector {
        private final IptvSettings settings;

        public UTVMediaCodecSelector(@Nullable IptvSettings settings) {
            this.settings = settings;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                    boolean requiresSecureDecoder,
                                                    boolean requiresTunnelingDecoder)
                throws com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException {
            if (Mp2SoftwareDecoder.isMp2MimeType(mimeType)) {
                return Collections.emptyList();
            }
            List<MediaCodecInfo> defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            return selectDecodersForSettings(defaults, mimeType, settings);
        }
    }

    /**
     * 专用于 MP2 (audio/mpeg-L2) 的 ijkplayer FFmpeg 音频解码渲染器：
     * 调用 ijkplayer (libijkffmpeg.so) 的 FFmpeg MP2 解码器将 MP2 帧解码为 16-bit PCM 送入独立 AudioSink，
     * 彻底解决 ExoPlayer 播放 MP2 伴音严重失真的问题，同时保持视频链路 MediaCodec 硬解。
     */
    public static final class Mp2DecoderAudioRenderer
            extends DecoderAudioRenderer<Mp2SoftwareDecoder.ExoMp2SimpleDecoder> {

        private final UTVPlayer owner;

        public Mp2DecoderAudioRenderer(@Nullable Handler eventHandler,
                                       @Nullable AudioRendererEventListener eventListener,
                                       AudioSink audioSink,
                                       @Nullable UTVPlayer owner) {
            super(eventHandler, eventListener, audioSink);
            this.owner = owner;
        }

        @Override
        public String getName() {
            return "UTVMp2AudioRenderer";
        }

        @Override
        protected int supportsFormatInternal(Format format) {
            if (format == null || !Mp2SoftwareDecoder.isMp2MimeType(format.sampleMimeType)) {
                return C.FORMAT_UNSUPPORTED_TYPE;
            }
            if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
                return C.FORMAT_UNSUPPORTED_DRM;
            }
            int channels = format.channelCount > 0 ? Math.min(2, format.channelCount) : 2;
            int sampleRate = format.sampleRate > 0 ? format.sampleRate : 48000;
            Format pcmFormat = new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setChannelCount(channels)
                    .setSampleRate(sampleRate)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build();
            if (!sinkSupportsFormat(pcmFormat)) {
                return C.FORMAT_UNSUPPORTED_SUBTYPE;
            }
            return C.FORMAT_HANDLED;
        }

        @Override
        protected Mp2SoftwareDecoder.ExoMp2SimpleDecoder createDecoder(Format format,
                                                                       @Nullable CryptoConfig cryptoConfig)
                throws DecoderException {
            Mp2SoftwareDecoder.ExoMp2SimpleDecoder decoder = new Mp2SoftwareDecoder.ExoMp2SimpleDecoder(format);
            if (owner != null) {
                owner.activeAudioDecoderName = "ijkffmpeg-mp2";
            }
            return decoder;
        }

        @Override
        protected Format getOutputFormat(Mp2SoftwareDecoder.ExoMp2SimpleDecoder decoder) {
            return decoder.getCurrentOutputPcmFormat();
        }

        @Override
        protected DecoderReuseEvaluation canReuseDecoder(String decoderName,
                                                         Format oldFormat,
                                                         Format newFormat) {
            if (oldFormat != null
                    && newFormat != null
                    && Mp2SoftwareDecoder.isMp2MimeType(oldFormat.sampleMimeType)
                    && Mp2SoftwareDecoder.isMp2MimeType(newFormat.sampleMimeType)
                    && oldFormat.sampleRate == newFormat.sampleRate
                    && oldFormat.channelCount == newFormat.channelCount) {
                return new DecoderReuseEvaluation(
                        decoderName,
                        oldFormat,
                        newFormat,
                        DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH,
                        0);
            }
            return super.canReuseDecoder(decoderName, oldFormat, newFormat);
        }
    }

    /**
     * 自定义 MediaCodecVideoRenderer：
     * 1. 强制将 CodecMaxValues 与 MediaFormat 的最大宽高设为 3840x2160；
     * 2. 在同一个 UTVPlayer 槽位切换 MediaSource 时，通过 canReuseCodec() 返回 REUSE_RESULT_YES_WITH_FLUSH，
     *    使 ExoPlayer 直接 flush() 复用同一个解码器，不销毁重建；
     * 3. 当用户在设置中切换硬解/软解时，canReuseCodec() 自动识别并返回 REUSE_RESULT_NO (DISCARD_REASON_APP_OVERRIDE)，
     *    立即切换至目标软解或硬解解码器。
     */
    public static final class AdaptiveMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
        private final UTVPlayer owner;

        public AdaptiveMediaCodecVideoRenderer(Context context,
                                               MediaCodecAdapter.Factory codecAdapterFactory,
                                               MediaCodecSelector mediaCodecSelector,
                                               long allowedJoiningTimeMs,
                                               boolean enableDecoderFallback,
                                               @Nullable Handler eventHandler,
                                               @Nullable VideoRendererEventListener eventListener,
                                               int maxDroppedFramesToNotify,
                                               @Nullable UTVPlayer owner) {
            super(
                    context,
                    codecAdapterFactory,
                    mediaCodecSelector,
                    allowedJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    maxDroppedFramesToNotify);
            this.owner = owner;
        }

        @Override
        protected List<MediaCodecInfo> getDecoderInfos(MediaCodecSelector mediaCodecSelector,
                                                       Format format,
                                                       boolean requiresSecureDecoder)
                throws com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> infos = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder);
            IptvSettings cfg = owner != null ? owner.getSettings() : null;
            return selectDecodersForSettings(infos, format != null ? format.sampleMimeType : null, cfg);
        }

        @Override
        protected CodecMaxValues getCodecMaxValues(MediaCodecInfo codecInfo,
                                                   Format format,
                                                   Format[] streamFormats) {
            CodecMaxValues base = super.getCodecMaxValues(codecInfo, format, streamFormats);
            // 仅对 HEVC(4K) 放大到 3840x2160 以支持跨频道解码器复用；
            // 对 H.264 1080P 等低分辨率流若同样放大，MTK 解码器会按 4K 分配缓冲区，
            // 随后 setPortMode(DynamicANWBuffer) 失败(err -1010)，表现为"只有声音没有画面"。
            boolean isHevc = format != null && MimeTypes.VIDEO_H265.equals(format.sampleMimeType);
            if (!isHevc) {
                return base;
            }
            int width = Math.max(base.width, MAX_ADAPTIVE_WIDTH);
            int height = Math.max(base.height, MAX_ADAPTIVE_HEIGHT);
            int inputSize = Math.max(base.inputSize, MAX_ADAPTIVE_INPUT_SIZE);
            return new CodecMaxValues(width, height, inputSize);
        }

        @Override
        protected MediaFormat getMediaFormat(Format format,
                                             String codecMimeType,
                                             CodecMaxValues codecMaxValues,
                                             float codecOperatingRate,
                                             boolean deviceNeedsNoPostProcessWorkaround,
                                             int tunnelingAudioSessionId) {
            MediaFormat mediaFormat = super.getMediaFormat(
                    format,
                    codecMimeType,
                    codecMaxValues,
                    codecOperatingRate,
                    deviceNeedsNoPostProcessWorkaround,
                    tunnelingAudioSessionId);
            // 同上：只对 HEVC 声明 4K 上限。对 1080P 的 H.264 流声明 4K 会让
            // MTK 解码器按 4K 分配输出缓冲，导致 DynamicANWBuffer 连接失败、画面不出。
            boolean isHevc = format != null && MimeTypes.VIDEO_H265.equals(format.sampleMimeType);
            if (isHevc) {
                mediaFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, MAX_ADAPTIVE_WIDTH);
                mediaFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, MAX_ADAPTIVE_HEIGHT);
            }
            return mediaFormat;
        }

        @Override
        public DecoderReuseEvaluation canReuseCodec(MediaCodecInfo codecInfo,
                                                    Format oldFormat,
                                                    Format newFormat) {
            IptvSettings cfg = owner != null ? owner.getSettings() : null;
            String targetMime = (newFormat != null && !TextUtils.isEmpty(newFormat.sampleMimeType))
                    ? newFormat.sampleMimeType
                    : (oldFormat != null ? oldFormat.sampleMimeType : null);
            boolean preferHw = isHardwareDecodingPreferred(targetMime, cfg);
            boolean currentIsSw = isSoftwareCodecInfo(codecInfo);
            if (codecInfo != null && (preferHw == currentIsSw)) {
                // 用户在设置中切换了硬解/软解开关：禁止复用当前类型不匹配的解码器，强制切换为软解或硬解
                return new DecoderReuseEvaluation(
                        codecInfo.name,
                        oldFormat,
                        newFormat,
                        DecoderReuseEvaluation.REUSE_RESULT_NO,
                        DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE);
            }

            DecoderReuseEvaluation eval = null;
            try {
                eval = super.canReuseCodec(codecInfo, oldFormat, newFormat);
            } catch (NullPointerException ignored) {
                if (codecInfo != null && oldFormat != null && newFormat != null) {
                    eval = codecInfo.canReuseCodec(oldFormat, newFormat);
                }
            }
            if (eval != null && eval.result != DecoderReuseEvaluation.REUSE_RESULT_NO) {
                if (owner != null) {
                    owner.codecReuseHitCount.incrementAndGet();
                }
                return eval;
            }
            // 只要新旧频道的视频编码格式一致 (如均为 video/avc 或均为 video/hevc) 且不超过预配置的 3840x2160 上限，
            // 允许直接通过 codec.flush() + 重配 SPS/PPS 复用同一个解码器，无需销毁重建 MediaCodec
            if (oldFormat != null
                    && newFormat != null
                    && !TextUtils.isEmpty(oldFormat.sampleMimeType)
                    && oldFormat.sampleMimeType.equals(newFormat.sampleMimeType)
                    && newFormat.width <= MAX_ADAPTIVE_WIDTH
                    && newFormat.height <= MAX_ADAPTIVE_HEIGHT) {
                if (owner != null) {
                    owner.codecReuseHitCount.incrementAndGet();
                }
                return new DecoderReuseEvaluation(
                        codecInfo != null ? codecInfo.name : "MediaCodec",
                        oldFormat,
                        newFormat,
                        DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH,
                        0);
            }
            return eval != null
                    ? eval
                    : new DecoderReuseEvaluation(
                            codecInfo != null ? codecInfo.name : "MediaCodec",
                            oldFormat,
                            newFormat,
                            DecoderReuseEvaluation.REUSE_RESULT_NO,
                            DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED);
        }

        @Override
        protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
            return false;
        }

        @Override
        protected void onCodecInitialized(String name,
                                          MediaCodecAdapter.Configuration configuration,
                                          long initializedTimestampMs,
                                          long initializationDurationMs) {
            super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs);
            if (owner != null) {
                owner.codecInitializedCount.incrementAndGet();
                owner.activeVideoDecoderName = name;
            }
        }
    }

    /**
     * 容错 AudioSink：把 DefaultAudioSink 的 UnexpectedDiscontinuityException 降级为告警。
     *
     * IPTV 组播在 UDP 丢包时，MP2 音频帧的 PTS 会出现跳变，DefaultAudioSink.handleBuffer()
     * 检测到时间戳不连续后抛 UnexpectedDiscontinuityException。该异常沿
     * MediaCodecAudioRenderer.processOutputBuffer() → ExoPlayerImplInternal.doSomeWork()
     * 冒泡，会终止整个播放循环，视频渲染器随之停摆——用户看到"有声音但画面卡死"。
     *
     * 直播场景下丢包导致的时间戳跳变无法避免，正确处理是让音频链路自行重新对齐，
     * 而不是让整个播放器崩溃。这里吞掉该异常并跳过该帧，继续后续 handleBuffer 调用。
     */
    static final class DiscontinuityTolerantAudioSink implements AudioSink {
        private final AudioSink delegate;
        private long droppedDiscontinuities;

        DiscontinuityTolerantAudioSink(AudioSink delegate) {
            this.delegate = delegate;
        }

        long getDroppedDiscontinuities() {
            return droppedDiscontinuities;
        }

        @Override
        public void setListener(Listener listener) {
            delegate.setListener(listener);
        }

        @Override
        public boolean supportsFormat(Format format) {
            return delegate.supportsFormat(format);
        }

        @Override
        public int getFormatSupport(Format format) {
            return delegate.getFormatSupport(format);
        }

        @Override
        public long getCurrentPositionUs(boolean sourceEnded) {
            return delegate.getCurrentPositionUs(sourceEnded);
        }

        @Override
        public void configure(Format inputFormat, int specifiedBufferSize, int[] outputChannels)
                throws ConfigurationException {
            delegate.configure(inputFormat, specifiedBufferSize, outputChannels);
        }

        @Override
        public void play() {
            delegate.play();
        }

        @Override
        public void handleDiscontinuity() {
            delegate.handleDiscontinuity();
        }

        @Override
        public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
                throws InitializationException, WriteException {
            try {
                return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
            } catch (InitializationException | WriteException e) {
                throw e;
            } catch (Exception e) {
                // UnexpectedDiscontinuityException 是受检异常但未在 handleBuffer 签名中声明，
                // 由 DefaultAudioSink 以未声明受检异常的形式抛出。直播丢包导致的 PTS 跳变
                // 若向上冒泡会终止 ExoPlayerImplInternal 播放循环（画面卡死），此处降级为告警。
                if (e instanceof UnexpectedDiscontinuityException) {
                    droppedDiscontinuities++;
                    Log.w(TAG, "Audio discontinuity tolerated (#" + droppedDiscontinuities
                            + "): " + e.getMessage());
                    return false;
                }
                throw new WriteException(0, null, false);
            }
        }

        @Override
        public void playToEndOfStream() throws WriteException {
            delegate.playToEndOfStream();
        }

        @Override
        public boolean isEnded() {
            return delegate.isEnded();
        }

        @Override
        public boolean hasPendingData() {
            return delegate.hasPendingData();
        }

        @Override
        public void setPlaybackParameters(PlaybackParameters playbackParameters) {
            delegate.setPlaybackParameters(playbackParameters);
        }

        @Override
        public PlaybackParameters getPlaybackParameters() {
            return delegate.getPlaybackParameters();
        }

        @Override
        public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
            delegate.setSkipSilenceEnabled(skipSilenceEnabled);
        }

        @Override
        public boolean getSkipSilenceEnabled() {
            return delegate.getSkipSilenceEnabled();
        }

        @Override
        public void setAudioAttributes(AudioAttributes audioAttributes) {
            delegate.setAudioAttributes(audioAttributes);
        }

        @Override
        public AudioAttributes getAudioAttributes() {
            return delegate.getAudioAttributes();
        }

        @Override
        public void setAudioSessionId(int audioSessionId) {
            delegate.setAudioSessionId(audioSessionId);
        }

        @Override
        public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
            delegate.setAuxEffectInfo(auxEffectInfo);
        }

        @Override
        public void enableTunnelingV21() {
            delegate.enableTunnelingV21();
        }

        @Override
        public void disableTunneling() {
            delegate.disableTunneling();
        }

        @Override
        public void setVolume(float volume) {
            delegate.setVolume(volume);
        }

        @Override
        public void pause() {
            delegate.pause();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void experimentalFlushWithoutAudioTrackRelease() {
            delegate.experimentalFlushWithoutAudioTrackRelease();
        }

        @Override
        public void reset() {
            delegate.reset();
        }
    }

    /**
     * 注入 AdaptiveMediaCodecVideoRenderer、UTVMediaCodecSelector 与 Mp2DecoderAudioRenderer 的 RenderersFactory
     */
    public static final class UTVRenderersFactory extends DefaultRenderersFactory {
        private final UTVPlayer owner;

        public UTVRenderersFactory(Context context, @Nullable UTVPlayer owner) {
            super(context);
            this.owner = owner;
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER);
            setEnableDecoderFallback(true);
            setAllowedVideoJoiningTimeMs(1500L);
            if (owner != null) {
                setMediaCodecSelector(new UTVMediaCodecSelector(owner.getSettings()));
            }
        }

        /**
         * MP2 伴音在 IPTV 组播丢包时 PTS 会跳变，DefaultAudioSink 会抛
         * UnexpectedDiscontinuityException，该异常从 ExoPlayerImplInternal.doSomeWork()
         * 冒泡会直接终止整条播放循环 —— 表现为"有声音、画面卡死"。
         * 这里给 MP2 专用链路包一层容错 AudioSink，把时间戳跳变降级为告警，
         * 让视频渲染器继续出帧。
         */
        @Override
        protected AudioSink buildAudioSink(Context context,
                                           boolean enableFloatOutput,
                                           boolean enableAudioTrackPlaybackParams,
                                           boolean enableOffload) {
            AudioSink inner = super.buildAudioSink(
                    context, enableFloatOutput, enableAudioTrackPlaybackParams, enableOffload);
            return inner == null ? null : new DiscontinuityTolerantAudioSink(inner);
        }

        @Override
        protected void buildVideoRenderers(Context context,
                                           int extensionRendererMode,
                                           MediaCodecSelector mediaCodecSelector,
                                           boolean enableDecoderFallback,
                                           Handler eventHandler,
                                           VideoRendererEventListener eventListener,
                                           long allowedVideoJoiningTimeMs,
                                           ArrayList<Renderer> out) {
            AdaptiveMediaCodecVideoRenderer videoRenderer = new AdaptiveMediaCodecVideoRenderer(
                    context,
                    getCodecAdapterFactory(),
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                    owner);
            out.add(videoRenderer);
        }

        @Override
        protected void buildAudioRenderers(Context context,
                                           int extensionRendererMode,
                                           MediaCodecSelector mediaCodecSelector,
                                           boolean enableDecoderFallback,
                                           AudioSink audioSink,
                                           Handler eventHandler,
                                           AudioRendererEventListener eventListener,
                                           ArrayList<Renderer> out) {
            // 为 Mp2DecoderAudioRenderer 分配独立的 AudioSink，避免与 MediaCodecAudioRenderer 争抢同一个 AudioSink.Listener
            AudioSink mp2Sink = buildAudioSink(context, false, false, false);
            if (mp2Sink == null) {
                mp2Sink = audioSink;
            }
            out.add(new Mp2DecoderAudioRenderer(eventHandler, eventListener, mp2Sink, owner));
            // 标准 MediaCodecAudioRenderer 用的是父类传入的 audioSink 参数，
            // 不经过 buildAudioSink() 覆盖，因此这里也要包一层容错，
            // 否则 AAC/AC3 等走 MediaCodec 音频渲染器的频道仍会因 PTS 跳变终止播放循环。
            AudioSink tolerantSink = audioSink == null
                    ? null : new DiscontinuityTolerantAudioSink(audioSink);
            super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    tolerantSink,
                    eventHandler,
                    eventListener,
                    out);
        }
    }

    public UTVPlayer(Context context) {
        this.appContext = context.getApplicationContext();
        this.settings = new IptvSettings(appContext);
        this.loadControl = new UTVLoadControl();
        this.dataSourceFactory = new UTVStreamDataSource.Factory();
        this.preloadPool = new PreloadMediaSource.PreloadPool(dataSourceFactory, loadControl.getSharedAllocator());

        UTVRenderersFactory renderersFactory = new UTVRenderersFactory(appContext, this);
        this.exoPlayer = new ExoPlayer.Builder(appContext, renderersFactory)
                .setLoadControl(loadControl)
                .build();
        this.preloadPool.bindPlaybackLooper(exoPlayer.getPlaybackLooper());
        this.exoPlayer.addListener(playerListener);

        // 初始将 UTVPlayer 挂靠在 PlaceholderSurface 上，确保离屏槽位预热时也能解码首帧
        parkToPlaceholderSurface();
    }

    public IptvSettings getSettings() {
        return settings;
    }

    public ExoPlayer getExoPlayer() {
        return exoPlayer;
    }

    public UTVLoadControl getLoadControl() {
        return loadControl;
    }

    public PreloadMediaSource.PreloadPool getPreloadPool() {
        return preloadPool;
    }

    public int getCodecReuseHitCount() {
        return codecReuseHitCount.get();
    }

    public int getCodecInitializedCount() {
        return codecInitializedCount.get();
    }

    public boolean isAttachedToPlaceholderSurface() {
        return attachedToPlaceholderSurface;
    }

    public boolean isPromotedFromPreload() {
        return promotedFromPreload;
    }

    public void setPromotedFromPreload(boolean promoted) {
        this.promotedFromPreload = promoted;
    }

    public void setRawChannelUrl(String rawChannelUrl) {
        this.currentRawChannelUrl = rawChannelUrl;
    }

    /**
     * 原生支持 SurfaceView 动态绑定：底层直接调用 MediaCodec.setOutputSurface(newSurfaceView)
     */
    public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {
        if (surfaceView != null && surfaceView.getHolder() != null
                && surfaceView.getHolder().getSurface() != null
                && surfaceView.getHolder().getSurface().isValid()) {
            attachedToPlaceholderSurface = false;
            exoPlayer.setVideoSurfaceView(surfaceView);
        } else {
            parkToPlaceholderSurface();
        }
    }

    public void setVideoTextureView(@Nullable TextureView textureView) {
        if (textureView != null && textureView.isAvailable()) {
            attachedToPlaceholderSurface = false;
            exoPlayer.setVideoTextureView(textureView);
        } else {
            parkToPlaceholderSurface();
        }
    }

    /**
     * 获取或创建离屏占位 Surface：
     * 优先使用 ExoPlayer 内置的 PlaceholderSurface.newInstanceV17(appContext, false)（EGL 1x1 离屏 Surface）；
     * 若在无 EGL 环境的单元测试中则回退至离屏 SurfaceTexture(0)。
     */
    public synchronized Surface getOrCreatePlaceholderSurface() {
        if (exoPlaceholderSurface != null && exoPlaceholderSurface.isValid()) {
            return exoPlaceholderSurface;
        }
        if (fallbackPlaceholderSurface != null && fallbackPlaceholderSurface.isValid()) {
            return fallbackPlaceholderSurface;
        }
        try {
            exoPlaceholderSurface = PlaceholderSurface.newInstanceV17(appContext, false);
            if (exoPlaceholderSurface != null && exoPlaceholderSurface.isValid()) {
                return exoPlaceholderSurface;
            }
        } catch (Throwable t) {
            Log.d(TAG, "PlaceholderSurface.newInstanceV17 fallback: " + t.getMessage());
        }
        try {
            fallbackSurfaceTexture = new SurfaceTexture(0);
            fallbackSurfaceTexture.setDefaultBufferSize(1, 1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                try {
                    fallbackSurfaceTexture.detachFromGLContext();
                } catch (Throwable ignored) {
                }
            }
            fallbackPlaceholderSurface = new Surface(fallbackSurfaceTexture);
        } catch (Throwable ignored) {
        }
        return fallbackPlaceholderSurface;
    }

    /**
     * 将解码器挂靠在 ExoPlayer MediaCodecVideoRenderer 内置的 PlaceholderSurface (EGL 1x1 离屏 Surface) 上：
     * 调用 exoPlayer.clearVideoSurface() 会触发 MediaCodecVideoRenderer.setOutput(null)，
     * 自动切换至其内部 placeholderSurface 并启用 skipOutputBuffer 机制（避免离屏 BufferQueue 积压阻塞）。
     */
    public void parkToPlaceholderSurface() {
        attachedToPlaceholderSurface = true;
        try {
            exoPlayer.clearVideoSurface();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 当复用当前 PlayerSlot 加载新频道时调用：
     * 仅重置状态标记并挂靠至 PlaceholderSurface，保留底层 ExoPlayer 的 MediaCodec 实例，
     * 以便接下来 prepareAsync() 切换 MediaSource 时触发 canReuseCodec() 直接 flush() 复用解码器。
     */
    public void prepareForSlotReuse() {
        preparedNotified = false;
        promotedFromPreload = false;
        videoWidth = 0;
        videoHeight = 0;
        parkToPlaceholderSurface();
    }

    // ==================== IMediaPlayer 接口实现 ====================

    @Override
    public void setDisplay(SurfaceHolder sh) {
        if (sh != null && sh.getSurface() != null && sh.getSurface().isValid()) {
            attachedToPlaceholderSurface = false;
            exoPlayer.setVideoSurfaceHolder(sh);
        } else {
            parkToPlaceholderSurface();
        }
    }

    @Override
    public void setSurface(Surface surface) {
        if (surface != null && surface.isValid()) {
            attachedToPlaceholderSurface = (surface == exoPlaceholderSurface || surface == fallbackPlaceholderSurface);
            exoPlayer.setVideoSurface(surface);
        } else {
            parkToPlaceholderSurface();
        }
    }

    @Override
    public void setDataSource(Context context, Uri uri) throws IOException {
        setDataSource(context, uri, null);
    }

    @Override
    public void setDataSource(Context context, Uri uri, Map<String, String> headers) throws IOException {
        this.currentUri = uri;
        this.preparedNotified = false;
    }

    @Override
    public void setDataSource(String path) throws IOException {
        if (!TextUtils.isEmpty(path)) {
            setDataSource(appContext, Uri.parse(path), null);
        }
    }

    @Override
    public void setDataSource(FileDescriptor fd) throws IOException {
        throw new UnsupportedOperationException("FileDescriptor not supported by UTVPlayer");
    }

    @Override
    public String getDataSource() {
        return currentUri != null ? currentUri.toString() : "";
    }

    private String buildCurrentCodecConfigSignature() {
        boolean hw = settings == null || settings.isMediaCodecEnabled();
        boolean hevc = settings == null || settings.isMediaCodecHevcEnabled();
        return "HW_" + (hw ? 1 : 0) + "_HEVC_" + (hevc ? 1 : 0);
    }

    @Override
    public void prepareAsync() throws IllegalStateException {
        if (currentUri == null) {
            return;
        }
        preparedNotified = false;
        String currentSig = buildCurrentCodecConfigSignature();
        if (!TextUtils.isEmpty(lastAppliedCodecConfigSignature)
                && !lastAppliedCodecConfigSignature.equals(currentSig)) {
            // 用户在设置中切换了硬解/软解开关：先行 stop() 释放旧渲染器解码器，使新软解/硬解设置立即生效
            try {
                exoPlayer.stop();
            } catch (Throwable ignored) {
            }
            activeVideoDecoderName = "";
        }
        lastAppliedCodecConfigSignature = currentSig;

        MediaSource mediaSource = buildMediaSourceForUri(currentUri, currentRawChannelUrl);
        exoPlayer.setVolume(currentVolume);
        exoPlayer.setMediaSource(mediaSource, /* resetPosition= */ true);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }

    private MediaSource buildMediaSourceForUri(Uri uri, String rawUrl) {
        String uriStr = uri.toString().toLowerCase(Locale.US);
        String rawLower = rawUrl != null ? rawUrl.toLowerCase(Locale.US) : "";
        if (uriStr.contains(".m3u8") || rawLower.contains(".m3u8")) {
            return new HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        if (uriStr.contains(".mpd") || rawLower.contains(".mpd")) {
            return new DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        return preloadPool.obtainMediaSource(uri, rawUrl);
    }

    @Override
    public void start() throws IllegalStateException {
        exoPlayer.setPlayWhenReady(true);
    }

    @Override
    public void stop() throws IllegalStateException {
        stopAndPark();
    }

    @Override
    public void pause() throws IllegalStateException {
        exoPlayer.setPlayWhenReady(false);
    }

    @Override
    public void setScreenOnWhilePlaying(boolean screenOn) {
        exoPlayer.setWakeMode(screenOn ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_NONE);
    }

    @Override
    public int getVideoWidth() {
        return videoWidth;
    }

    @Override
    public int getVideoHeight() {
        return videoHeight;
    }

    @Override
    public boolean isPlaying() {
        int state = exoPlayer.getPlaybackState();
        return exoPlayer.getPlayWhenReady() && (state == Player.STATE_READY || state == Player.STATE_BUFFERING);
    }

    @Override
    public void seekTo(long msec) throws IllegalStateException {
        exoPlayer.seekTo(msec);
    }

    @Override
    public long getCurrentPosition() {
        return exoPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        long d = exoPlayer.getDuration();
        return d == C.TIME_UNSET ? 0L : d;
    }

    @Override
    public void release() {
        releasePlayer();
    }

    /**
     * 停止当前 UTVPlayer 槽位的音视频流，静音并挂靠回 PlaceholderSurface（用于 3 秒空闲停止离屏实例或重置槽位时）
     */
    public void stopAndPark() {
        preparedNotified = false;
        promotedFromPreload = false;
        currentVolume = 0f;
        try {
            preloadPool.releaseAll();
        } catch (Throwable ignored) {
        }
        try {
            exoPlayer.setPlayWhenReady(false);
            exoPlayer.setVolume(0f);
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        } catch (Throwable ignored) {
        }
        parkToPlaceholderSurface();
    }

    /**
     * 彻底销毁当前 UTVPlayer 实例及其底层 ExoPlayer 与 PlaceholderSurface（在切换至其他播放器内核或释放槽位时调用）
     */
    public synchronized void releasePlayer() {
        stopAndPark();
        try {
            exoPlayer.release();
        } catch (Throwable ignored) {
        }
        if (exoPlaceholderSurface != null) {
            try {
                exoPlaceholderSurface.release();
            } catch (Throwable ignored) {
            }
            exoPlaceholderSurface = null;
        }
        if (fallbackPlaceholderSurface != null) {
            try {
                fallbackPlaceholderSurface.release();
            } catch (Throwable ignored) {
            }
            fallbackPlaceholderSurface = null;
        }
        if (fallbackSurfaceTexture != null) {
            try {
                fallbackSurfaceTexture.release();
            } catch (Throwable ignored) {
            }
            fallbackSurfaceTexture = null;
        }
    }

    @Override
    public void reset() {
        stopAndPark();
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        currentVolume = Math.max(leftVolume, rightVolume);
        exoPlayer.setVolume(currentVolume);
    }

    @Override
    public int getAudioSessionId() {
        return exoPlayer.getAudioSessionId();
    }

    @Override
    public MediaInfo getMediaInfo() {
        MediaInfo info = new MediaInfo();
        info.mMediaPlayerName = "UTVPlayer (ExoPlayer 2.19)";
        info.mVideoDecoder = "MediaCodec";
        info.mVideoDecoderImpl = activeVideoDecoderName;
        Format af = exoPlayer.getAudioFormat();
        boolean isMp2 = !TextUtils.isEmpty(activeAudioDecoderName)
                || (af != null && Mp2SoftwareDecoder.isMp2MimeType(af.sampleMimeType));
        info.mAudioDecoder = isMp2 ? "IjkFfmpegMp2Decoder" : "MediaCodec";
        info.mAudioDecoderImpl = isMp2
                ? "ijkffmpeg-mp2"
                : ((af != null && !TextUtils.isEmpty(af.sampleMimeType)) ? af.sampleMimeType : "ExoAudio");
        return info;
    }

    public String getDecoderSummaryLabel() {
        Format vf = exoPlayer.getVideoFormat();
        String mime = (vf != null && !TextUtils.isEmpty(vf.sampleMimeType))
                ? vf.sampleMimeType
                : (!TextUtils.isEmpty(currentRawChannelUrl) && currentRawChannelUrl.endsWith(":2000")
                ? "video/hevc"
                : "video/avc");
        boolean preferHw = isHardwareDecodingPreferred(mime, settings);
        boolean activeSw = !preferHw || isSoftwareDecoderName(activeVideoDecoderName);
        String poolTag = "单实例";
        Format af = exoPlayer.getAudioFormat();
        boolean isMp2 = !TextUtils.isEmpty(activeAudioDecoderName)
                || (af != null && Mp2SoftwareDecoder.isMp2MimeType(af.sampleMimeType));
        String audioTag = isMp2 ? "+IjkMP2解码" : "";
        if (activeSw) {
            return "ExoPlayer (" + poolTag + "软解" + audioTag + ")";
        }
        String reuseTag = codecReuseHitCount.get() > 0 ? (poolTag + "+canReuseCodec硬解") : (poolTag + "硬解");
        return "ExoPlayer (" + reuseTag + audioTag + ")";
    }

    public Format getCurrentVideoFormat() {
        return exoPlayer.getVideoFormat();
    }

    public Format getCurrentAudioFormat() {
        return exoPlayer.getAudioFormat();
    }

    public float getVideoFrameRate() {
        Format vf = exoPlayer.getVideoFormat();
        if (vf != null && vf.frameRate > 0) {
            return vf.frameRate;
        }
        return 0f;
    }

    public long getTcpSpeedBytesPerSec() {
        return UTVStreamDataSource.getRecentSpeedBytesPerSec();
    }

    @Override
    public void setLogEnabled(boolean enable) {
    }

    @Override
    public boolean isPlayable() {
        return true;
    }

    @Override
    public void setAudioStreamType(int streamtype) {
    }

    @Override
    public void setKeepInBackground(boolean keepInBackground) {
    }

    @Override
    public int getVideoSarNum() {
        return videoSarNum;
    }

    @Override
    public int getVideoSarDen() {
        return videoSarDen;
    }

    @Override
    public void setWakeMode(Context context, int mode) {
    }

    @Override
    public void setLooping(boolean looping) {
        exoPlayer.setRepeatMode(looping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    @Override
    public boolean isLooping() {
        return exoPlayer.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    @Override
    public ITrackInfo[] getTrackInfo() {
        return new ITrackInfo[0];
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
                notifyOnInfo(IMediaPlayer.MEDIA_INFO_BUFFERING_START, 0);
            } else if (playbackState == Player.STATE_READY) {
                Format vf = exoPlayer.getVideoFormat();
                if (vf != null && vf.width > 0 && vf.height > 0 && (videoWidth <= 0 || videoHeight <= 0)) {
                    videoWidth = vf.width;
                    videoHeight = vf.height;
                    notifyOnVideoSizeChanged(videoWidth, videoHeight, videoSarNum, videoSarDen);
                }
                if (!preparedNotified) {
                    preparedNotified = true;
                    notifyOnPrepared();
                }
                notifyOnInfo(IMediaPlayer.MEDIA_INFO_BUFFERING_END, 0);
            } else if (playbackState == Player.STATE_ENDED) {
                notifyOnCompletion();
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            if (!preparedNotified) {
                preparedNotified = true;
                notifyOnPrepared();
            }
            notifyOnInfo(IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
            videoWidth = videoSize.width;
            videoHeight = videoSize.height;
            if (videoWidth > 0 && videoHeight > 0) {
                notifyOnVideoSizeChanged(videoWidth, videoHeight, videoSarNum, videoSarDen);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Log.w(TAG, "UTVPlayer playback error: " + error.getMessage());
            notifyOnError(IMediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode);
        }
    };
}
