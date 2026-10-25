package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
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
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DecoderAudioRenderer;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.decoder.CryptoConfig;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
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

/**
 * One foreground ExoPlayer with hardware-preferred video and capability-based MP2 audio.
 * ProgressiveMediaSource owns periods on Exo's playback looper. Neighbour preloading
 * only receives bounded TS bytes and never allocates another player or decoder.
 */
public class UTVPlayer extends AbstractMediaPlayer {
    private static final String TAG = "UTVPlayer";

    private final Context appContext;
    private final IptvSettings settings;
    private final UTVLoadControl loadControl;
    private final UTVStreamDataSource.Factory dataSourceFactory;
    private final ExoPlayer exoPlayer;
    private final DefaultTrackSelector trackSelector;
    private final Handler applicationHandler;
    private volatile String currentMediaId = "";
    private long mediaSequence;
    private volatile boolean forceMp2Software;
    private boolean released;
    private PlaybackFailure lastFailure = PlaybackFailure.UNKNOWN;
    private int mp2AudioRendererIndex = -1;
    private final List<Integer> mediaCodecAudioRenderers = new ArrayList<>();
    private Map<String, String> currentHeaders = Collections.emptyMap();

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
        boolean preferHardware = mimeType == null || !mimeType.startsWith("video/")
                || isHardwareDecodingPreferred(mimeType, settings);
        List<MediaCodecInfo> hwList = new ArrayList<>();
        List<MediaCodecInfo> swList = new ArrayList<>();
        for (MediaCodecInfo info : allDecoders) {
            if (DecoderPolicy.hasFailed(mimeType, info.name)) continue;
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
     * MP2 uses only genuine hardware audio/mpeg-L2 candidates; it never aliases MP2 to MP3.
     * Missing or failed MP2 hardware is handled by the Ijk/FFmpeg audio renderer.
     */
    public static final class UTVMediaCodecSelector implements MediaCodecSelector {
        private final IptvSettings settings;
        private final UTVPlayer owner;

        public UTVMediaCodecSelector(@Nullable IptvSettings settings, @Nullable UTVPlayer owner) {
            this.settings = settings;
            this.owner = owner;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                    boolean requiresSecureDecoder,
                                                    boolean requiresTunnelingDecoder)
                throws com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException {
            if (Mp2SoftwareDecoder.isMp2MimeType(mimeType)) {
                return owner != null && owner.forceMp2Software
                        ? Collections.emptyList() : DecoderPolicy.getMp2HardwareDecoders(null);
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
            if (owner != null && !owner.forceMp2Software
                    && !DecoderPolicy.getMp2HardwareDecoders(format).isEmpty()) {
                return C.FORMAT_EXCEEDS_CAPABILITIES;
            }
            return C.FORMAT_HANDLED;
        }

        @Override
        protected Mp2SoftwareDecoder.ExoMp2SimpleDecoder createDecoder(Format format,
                                                                       @Nullable CryptoConfig cryptoConfig)
                throws DecoderException {
            Mp2SoftwareDecoder.ExoMp2SimpleDecoder decoder = new Mp2SoftwareDecoder.ExoMp2SimpleDecoder(format);
            if (owner != null) {
                owner.activeAudioDecoderName = decoder.getName();
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

    /** Retains Exo's codec reuse boundaries and records failed hardware candidates. */
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
        public DecoderReuseEvaluation canReuseCodec(MediaCodecInfo codecInfo,
                                                    Format oldFormat,
                                                    Format newFormat) {
            IptvSettings cfg = owner != null ? owner.getSettings() : null;
            if (!isHardwareDecodingPreferred(newFormat.sampleMimeType, cfg)
                    && !isSoftwareCodecInfo(codecInfo)) {
                return new DecoderReuseEvaluation(codecInfo.name, oldFormat, newFormat,
                        DecoderReuseEvaluation.REUSE_RESULT_NO,
                        DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE);
            }
            // Preserve ExoPlayer's capability, color, input-size and device workaround checks.
            // A software decoder selected after a hardware failure is also eligible for reuse.
            return super.canReuseCodec(codecInfo, oldFormat, newFormat);
        }

        @Override
        protected void onCodecError(Exception error) {
            super.onCodecError(error);
            if (error instanceof MediaCodecRenderer.DecoderInitializationException) {
                MediaCodecRenderer.DecoderInitializationException initError =
                        (MediaCodecRenderer.DecoderInitializationException) error;
                if (initError.codecInfo != null) {
                    DecoderPolicy.recordFailure(initError.mimeType, initError.codecInfo.name);
                }
            }
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
            setMediaCodecSelector(new UTVMediaCodecSelector(
                    owner != null ? owner.getSettings() : null, owner));
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
            if (owner != null) owner.mp2AudioRendererIndex = out.size();
            out.add(new Mp2DecoderAudioRenderer(eventHandler, eventListener, mp2Sink, owner));
            super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out);
            if (owner != null) {
                owner.mediaCodecAudioRenderers.clear();
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i) instanceof MediaCodecAudioRenderer) owner.mediaCodecAudioRenderers.add(i);
                }
            }
        }
    }

    public UTVPlayer(Context context) {
        this.appContext = context.getApplicationContext();
        this.settings = new IptvSettings(appContext);
        this.loadControl = new UTVLoadControl();
        this.dataSourceFactory = new UTVStreamDataSource.Factory();
        this.trackSelector = new DefaultTrackSelector(appContext);

        UTVRenderersFactory renderersFactory = new UTVRenderersFactory(appContext, this);
        this.exoPlayer = new ExoPlayer.Builder(appContext, renderersFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .build();
        this.applicationHandler = new Handler(exoPlayer.getApplicationLooper());
        this.exoPlayer.addAnalyticsListener(playerListener);

        // 等待前台 Surface 创建；邻台预收流无需 Surface 或解码器。
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

    public void preloadChannel(Uri uri) {
        dataSourceFactory.preload(uri, currentUri);
    }

    public void stopBackgroundPreload() {
        dataSourceFactory.stopBackgroundPreload();
    }

    public PlaybackFailure getLastFailure() {
        return lastFailure;
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

    /** Reset per-channel metadata while retaining the current foreground Surface. */
    public void prepareForSlotReuse() {
        preparedNotified = false;
        promotedFromPreload = false;
        videoWidth = 0;
        videoHeight = 0;
        activeAudioDecoderName = "";
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
        this.currentHeaders = headers != null ? new java.util.HashMap<>(headers) : Collections.emptyMap();
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
        currentMediaId = Long.toString(++mediaSequence);
        dataSourceFactory.cancelActiveReads();
        activeAudioDecoderName = "";
        lastFailure = PlaybackFailure.UNKNOWN;
        // A decoder fallback only disables the system audio renderer for the current MP2 attempt.
        setMp2SoftwareFallback(false);
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
        String mediaId = currentMediaId;
        DataSource.Factory playbackFactory = dataSourceFactory.forPlayback(
                info -> applicationHandler.post(() -> {
                    if (!released && mediaId.equals(currentMediaId)) {
                        notifyOnInfo(info, 0);
                    }
                }), currentHeaders);
        MediaItem item = new MediaItem.Builder().setUri(uri).setMediaId(mediaId).build();
        String uriStr = uri.toString().toLowerCase(Locale.US);
        String rawLower = rawUrl != null ? rawUrl.toLowerCase(Locale.US) : "";
        if (uriStr.contains(".m3u8") || rawLower.contains(".m3u8")) {
            return new HlsMediaSource.Factory(playbackFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }
        if (uriStr.contains(".mpd") || rawLower.contains(".mpd")) {
            return new DashMediaSource.Factory(playbackFactory).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(playbackFactory, new IptvTsExtractorsFactory())
                .setContinueLoadingCheckIntervalBytes(256 * 1024)
                .createMediaSource(item);
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
        dataSourceFactory.stopBackgroundPreload();
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
        currentMediaId = "";
        preparedNotified = false;
        promotedFromPreload = false;
        currentVolume = 0f;
        dataSourceFactory.stopBackgroundPreload();
        dataSourceFactory.cancelActiveReads();
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
        released = true;
        stopAndPark();
        try {
            exoPlayer.release();
        } catch (Throwable ignored) {
        }
        dataSourceFactory.close();
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
        boolean isMp2 = Mp2SoftwareDecoder.isMp2MimeType(af != null ? af.sampleMimeType : null);
        boolean softwareMp2 = isMp2 && ("IjkFfmpegMp2Decoder".equals(activeAudioDecoderName)
                || "UTVMp2SoftwareDecoder".equals(activeAudioDecoderName));
        info.mAudioDecoder = softwareMp2 ? activeAudioDecoderName : "MediaCodec";
        info.mAudioDecoderImpl = activeAudioDecoderName;
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
        boolean activeSw = !TextUtils.isEmpty(activeVideoDecoderName)
                ? isSoftwareDecoderName(activeVideoDecoderName) : !preferHw;
        String poolTag = "单实例";
        Format af = exoPlayer.getAudioFormat();
        boolean isMp2 = af != null && Mp2SoftwareDecoder.isMp2MimeType(af.sampleMimeType);
        String audioTag = isMp2 ? "+MP2:" + activeAudioDecoderName : "";
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

    private boolean matchesEvent(AnalyticsListener.EventTime eventTime) {
        if (released || currentMediaId.isEmpty() || eventTime.timeline.isEmpty()
                || eventTime.windowIndex < 0
                || eventTime.windowIndex >= eventTime.timeline.getWindowCount()) return false;
        com.google.android.exoplayer2.Timeline.Window window =
                new com.google.android.exoplayer2.Timeline.Window();
        eventTime.timeline.getWindow(eventTime.windowIndex, window);
        return currentMediaId.equals(window.mediaItem.mediaId);
    }

    private void setMp2SoftwareFallback(boolean force) {
        forceMp2Software = force;
        DefaultTrackSelector.Parameters.Builder parameters = trackSelector.buildUponParameters();
        for (int index : mediaCodecAudioRenderers) parameters.setRendererDisabled(index, force);
        trackSelector.setParameters(parameters);
    }

    private final AnalyticsListener playerListener = new AnalyticsListener() {
        @Override
        public void onPlaybackStateChanged(EventTime eventTime, int playbackState) {
            if (!matchesEvent(eventTime)) return;
            if (playbackState == Player.STATE_BUFFERING) {
                notifyOnInfo(IMediaPlayer.MEDIA_INFO_BUFFERING_START, 0);
            } else if (playbackState == Player.STATE_READY) {
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
        public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
            if (!matchesEvent(eventTime) || attachedToPlaceholderSurface) return;
            if (!preparedNotified) {
                preparedNotified = true;
                notifyOnPrepared();
            }
            notifyOnInfo(IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        }

        @Override
        public void onVideoSizeChanged(EventTime eventTime, VideoSize size) {
            if (!matchesEvent(eventTime)) return;
            videoWidth = size.width;
            videoHeight = size.height;
            if (videoWidth > 0 && videoHeight > 0) {
                notifyOnVideoSizeChanged(videoWidth, videoHeight, videoSarNum, videoSarDen);
            }
        }

        @Override
        public void onTracksChanged(EventTime eventTime, com.google.android.exoplayer2.Tracks tracks) {
            if (matchesEvent(eventTime)) notifyOnInfo(PlaybackMetrics.INFO_STREAM_INFO, 0);
        }

        @Override
        public void onVideoDecoderInitialized(EventTime eventTime, String name,
                                               long initializedAtMs, long durationMs) {
            if (!matchesEvent(eventTime)) return;
            activeVideoDecoderName = name;
            codecInitializedCount.incrementAndGet();
            notifyOnInfo(PlaybackMetrics.INFO_VIDEO_DECODER_READY, 0);
        }

        @Override
        public void onVideoInputFormatChanged(EventTime eventTime, Format format,
                                                @Nullable DecoderReuseEvaluation evaluation) {
            if (matchesEvent(eventTime) && evaluation != null
                    && evaluation.result != DecoderReuseEvaluation.REUSE_RESULT_NO) {
                codecReuseHitCount.incrementAndGet();
                notifyOnInfo(PlaybackMetrics.INFO_VIDEO_DECODER_READY, 0);
            }
        }

        @Override
        public void onAudioDecoderInitialized(EventTime eventTime, String name,
                                               long initializedAtMs, long durationMs) {
            if (matchesEvent(eventTime)) activeAudioDecoderName = name;
        }

        @Override
        public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {
            if (matchesEvent(eventTime)) notifyOnInfo(PlaybackMetrics.INFO_AUDIO_RENDERING_START, 0);
        }

        @Override
        public void onAudioCodecError(EventTime eventTime, Exception error) {
            if (matchesEvent(eventTime)
                    && error instanceof MediaCodecRenderer.DecoderInitializationException) {
                MediaCodecRenderer.DecoderInitializationException initError =
                        (MediaCodecRenderer.DecoderInitializationException) error;
                if (initError.codecInfo != null) {
                    DecoderPolicy.recordFailure(initError.mimeType, initError.codecInfo.name);
                }
            }
        }

        @Override
        public void onPlayerError(EventTime eventTime, PlaybackException error) {
            if (!matchesEvent(eventTime)) return;
            Format format = error instanceof ExoPlaybackException
                    ? ((ExoPlaybackException) error).rendererFormat : null;
            int rendererIndex = error instanceof ExoPlaybackException
                    ? ((ExoPlaybackException) error).rendererIndex : -1;
            boolean audio = (format != null && MimeTypes.isAudio(format.sampleMimeType))
                    || mediaCodecAudioRenderers.contains(rendererIndex) || rendererIndex == mp2AudioRendererIndex;
            lastFailure = PlaybackFailure.fromExo(error, audio);
            if (lastFailure == PlaybackFailure.AUDIO_DECODER
                    && format != null && Mp2SoftwareDecoder.isMp2MimeType(format.sampleMimeType)
                    && !forceMp2Software) {
                DecoderPolicy.recordMp2HardwareFailure();
                setMp2SoftwareFallback(true);
                Log.w(TAG, "MP2 hardware decoder failed; recovering with Ijk/FFmpeg audio", error);
                // Keep the same MediaSource, player and front surface. Only the audio selection changes.
                exoPlayer.prepare();
                exoPlayer.setPlayWhenReady(true);
                return;
            }
            if (lastFailure == PlaybackFailure.VIDEO_DECODER && format != null) {
                DecoderPolicy.recordFailure(format.sampleMimeType, activeVideoDecoderName);
            }
            Log.w(TAG, "UTVPlayer playback error (" + lastFailure + ")", error);
            notifyOnError(IMediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode);
        }
    };
}
