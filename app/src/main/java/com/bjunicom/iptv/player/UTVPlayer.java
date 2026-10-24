package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import com.google.android.exoplayer2.decoder.DecoderCounters;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/**
 * 基于 ExoPlayer + 自定义 PreloadMediaSource 与 UTVLoadControl 的极速池化播放器模块 (UTVPlayer)
 *
 * 核心架构特性：
 * 1. 全局单实例 ExoPlayer + 解码器池化与不销毁（原生支持 canReuseCodec）：
 *    - 全局仅维护单一 ExoPlayer 实例；
 *    - 通过自定义 AdaptiveMediaCodecVideoRenderer 将 MediaFormat 最大宽高固定配置为 3840x2160
 *      (KEY_MAX_WIDTH=3840, KEY_MAX_HEIGHT=2160)，当在同一个 ExoPlayer 实例中切换 MediaSource（如从 CCTV-1 切到 CCTV-2）时，
 *      MediaCodecVideoRenderer.canReuseCodec() 评估通过 (REUSE_RESULT_YES_WITH_FLUSH)，直接调用 codec.flush() 复用同一个硬件解码器而不销毁重建。
 * 2. 后台预拉流池（自定义 PreloadMediaSource）：
 *    - 在后台维护 3 个独立的 ProgressiveMediaPeriod（当前频道 + 上一频道 + 下一频道），在用户还没切台时就提前 prepare()
 *      并拉取组播/HTTP-TS 流写入各自的 SampleQueue。
 * 3. SurfaceView 动态绑定与 PlaceholderSurface 离屏挂靠（原生支持）：
 *    - 调用 setVideoSurfaceView(newSurfaceView) 底层直接触发 MediaCodec.setOutputSurface()；
 *    - 配合 ExoPlayer 内置的 PlaceholderSurface（EGL 创建的 1x1 离屏 Surface），即使外部 UI 的 SurfaceView 销毁了，
 *      解码器依然挂靠在 PlaceholderSurface 上在后台继续预解码首帧。
 */
public class UTVPlayer extends AbstractMediaPlayer {
    private static final String TAG = "UTVPlayer";

    public static final int MAX_ADAPTIVE_WIDTH = 3840;
    public static final int MAX_ADAPTIVE_HEIGHT = 2160;
    public static final int MAX_ADAPTIVE_INPUT_SIZE = 4 * 1024 * 1024;

    private static volatile UTVPlayer sSharedInstance;

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
     * 支持硬解/软解动态切换的自定义 MediaCodecSelector
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
            // 注意：绝不能将 audio/mpeg-L2 (MP2) 强行替换为 audio/mpeg (MP3)！
            // 系统 MP3 解码器 (c2.android.mp3.decoder) 无法解码 Layer II 帧，强行替换会导致音频解码报错并阻塞 AudioSink 主时钟。
            // 若系统无原生 audio/mpeg-L2 MediaCodec，这里返回空列表，ExoPlayer 将自动回退至 Mp2DecoderAudioRenderer 软解。
            List<MediaCodecInfo> defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            return selectDecodersForSettings(defaults, mimeType, settings);
        }
    }

    /**
     * 专用于 MP2 (audio/mpeg-L2) 的 ExoPlayer 软解音频渲染器：
     * 当设备 MediaCodec 不支持 audio/mpeg-L2 硬解时，自动回退到此软解渲染器解码为 16-bit PCM 送入 AudioSink，
     * 同时保持视频链路继续使用 MediaCodec 硬解与 canReuseCodec 秒切复用。
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
            if (owner != null) {
                owner.activeAudioDecoderName = "MP2软解";
            }
            return new Mp2SoftwareDecoder.ExoMp2SimpleDecoder(format);
        }

        @Override
        protected Format getOutputFormat(Mp2SoftwareDecoder.ExoMp2SimpleDecoder decoder) {
            return decoder.getCurrentOutputPcmFormat();
        }
    }

    /**
     * 自定义 MediaCodecVideoRenderer：
     * 1. 强制将 CodecMaxValues 与 MediaFormat 的最大宽高设为 3840x2160；
     * 2. 在同一个 ExoPlayer 实例切换 MediaSource 时，通过 canReuseCodec() 返回 REUSE_RESULT_YES_WITH_FLUSH，
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
            mediaFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, MAX_ADAPTIVE_WIDTH);
            mediaFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, MAX_ADAPTIVE_HEIGHT);
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
     * 注入 AdaptiveMediaCodecVideoRenderer 与 UTVMediaCodecSelector 的 RenderersFactory
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
            super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out);
            // 将 Mp2DecoderAudioRenderer 注册在 MediaCodecAudioRenderer 之后：
            // 1. 对 AAC / AC3 / EAC3 等系统 MediaCodec 支持的格式，优先使用硬件/系统 MediaCodecAudioRenderer；
            // 2. 对 MP2 (audio/mpeg-L2) 且系统无对应 MediaCodec 时，自动回退至 Mp2DecoderAudioRenderer 纯软解。
            out.add(new Mp2DecoderAudioRenderer(eventHandler, eventListener, audioSink, owner));
        }
    }

    /**
     * 获取全局单实例 UTVPlayer（保持全局单实例 ExoPlayer，切台时不销毁解码器）
     */
    public static UTVPlayer getSharedInstance(Context context) {
        if (sSharedInstance == null) {
            synchronized (UTVPlayer.class) {
                if (sSharedInstance == null) {
                    sSharedInstance = new UTVPlayer(context.getApplicationContext());
                }
            }
        }
        return sSharedInstance;
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

        // 初始将 ExoPlayer 挂靠在 PlaceholderSurface 上，确保即使无 SurfaceView 也能在后台预解码首帧
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
     * 自动切换至其内部 placeholderSurface 并启用 skipOutputBuffer 机制（避免离屏 BufferQueue 积压阻塞），
     * 即使外部 UI 的 SurfaceView 销毁了，解码器依然能挂靠在 PlaceholderSurface 上在后台继续预解码首帧。
     */
    public void parkToPlaceholderSurface() {
        attachedToPlaceholderSurface = true;
        try {
            exoPlayer.clearVideoSurface();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 在后台维护 3 个独立的 ProgressiveMediaPeriod（当前频道 + 上一频道 + 下一频道），
     * 在用户尚未切台时提前 prepare 并拉取组播流写入各自的 SampleQueue。
     */
    public void preloadAdjacentChannels(@Nullable Uri prevUri,
                                        @Nullable String prevRawUrl,
                                        @Nullable Uri nextUri,
                                        @Nullable String nextRawUrl) {
        preloadPool.preloadThreeChannelWindow(
                currentUri, currentRawChannelUrl,
                prevUri, prevRawUrl,
                nextUri, nextRawUrl);
    }

    /**
     * 当用户切台 3 秒后未继续切台时，停止后台预拉流的 ProgressiveMediaPeriod
     */
    public void stopBackgroundPreloadPeriods() {
        preloadPool.stopBackgroundPreloadPeriods(currentUri, currentRawChannelUrl);
    }

    public boolean areBackgroundPreloadPeriodsStopped() {
        return preloadPool.areBackgroundPreloadPeriodsStopped(currentUri, currentRawChannelUrl);
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
        // 在同一个全局单实例 ExoPlayer 中切换 MediaSource：
        // MediaCodecVideoRenderer 自动调用 canReuseCodec() 评估并直接 flush() 复用同一个解码器
        exoPlayer.setMediaSource(mediaSource, /* resetPosition= */ true);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }

    private MediaSource buildMediaSourceForUri(Uri uri, String rawUrl) {
        String uriStr = uri.toString().toLowerCase(Locale.US);
        String rawLower = rawUrl != null ? rawUrl.toLowerCase(Locale.US) : "";
        if (uriStr.contains(".m3u8") || rawLower.contains(".m3u8")) {
            promotedFromPreload = false;
            return new HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        if (uriStr.contains(".mpd") || rawLower.contains(".mpd")) {
            promotedFromPreload = false;
            return new DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri));
        }
        PreloadMediaSource preloadSource = preloadPool.obtainMediaSource(uri, rawUrl);
        PreloadMediaSource.PreloadedPeriodSlot slot = preloadSource.getBoundSlot();
        promotedFromPreload = (slot != null && slot.promotedFromPreload);
        return preloadSource;
    }

    @Override
    public void start() throws IllegalStateException {
        exoPlayer.setPlayWhenReady(true);
    }

    @Override
    public void stop() throws IllegalStateException {
        exoPlayer.stop();
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
        // 全局单实例 ExoPlayer 切台时不销毁解码器，仅停止当前流并挂靠回 PlaceholderSurface
        reset();
    }

    /**
     * 彻底销毁全局单实例 ExoPlayer 及 PlaceholderSurface（仅在应用退出时调用）
     */
    public synchronized void releaseGlobalInstance() {
        preloadPool.releaseAll();
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
        sSharedInstance = null;
    }

    @Override
    public void reset() {
        preparedNotified = false;
        // 保持解码器挂靠在 PlaceholderSurface 上，不释放底层 MediaCodec
        parkToPlaceholderSurface();
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        exoPlayer.setVolume(Math.max(leftVolume, rightVolume));
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
        boolean isMp2Sw = !TextUtils.isEmpty(activeAudioDecoderName)
                || (af != null && Mp2SoftwareDecoder.isMp2MimeType(af.sampleMimeType));
        info.mAudioDecoder = isMp2Sw ? "Mp2SoftwareDecoder" : "MediaCodec";
        info.mAudioDecoderImpl = isMp2Sw
                ? "MP2软解"
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
        String preloadTag = promotedFromPreload ? "+SampleQueue秒切" : "";
        Format af = exoPlayer.getAudioFormat();
        boolean isMp2Sw = !TextUtils.isEmpty(activeAudioDecoderName)
                || (af != null && Mp2SoftwareDecoder.isMp2MimeType(af.sampleMimeType));
        String audioTag = isMp2Sw ? "+音频MP2软解" : "";
        if (activeSw) {
            return "ExoPlayer (软解" + preloadTag + audioTag + ")";
        }
        String reuseTag = codecReuseHitCount.get() > 0 ? "canReuseCodec硬解复用" : "池化硬解";
        return "ExoPlayer (" + reuseTag + preloadTag + audioTag + ")";
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
