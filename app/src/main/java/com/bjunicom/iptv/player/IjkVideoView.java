package com.bjunicom.iptv.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.network.StreamUrlResolver;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import tv.danmaku.ijk.media.exo.IjkExoMediaPlayer;
import tv.danmaku.ijk.media.player.AndroidMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;
import tv.danmaku.ijk.media.player.TextureMediaPlayer;
import tv.danmaku.ijk.media.player.misc.IMediaFormat;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkMediaFormat;

/**
 * 基于 bilibili/ijkplayer 深度定制的北京联通 IPTV 播放器核心视图
 *
 * 架构特性（下载与解码分离 + 双缓冲池化 + 消除切台慢放）：
 * 1. 下载模块池化 (StreamDownloaderPool)：
 *    切台时从池中获取预分配环形缓冲区的 PooledStreamDownloader，独立连接 HTTP-TS 或 IGMP 组播流，
 *    实时解复用 PAT/PMT 提取视频与音频编码格式 (TsCodecDescriptor)，并丢弃首个 IDR 关键帧之前的
 *    残留 P/B 帧与超前音频包，实现「PAT+PMT+IDR关键帧+同PTS音频」精准起播对齐，彻底解决切台前几秒 0.5x 慢放问题。
 * 2. 解码器模块池化 (CodecDecoderPool)：
 *    根据下载模块 parse 出来的 TsCodecDescriptor (如 H264+MP2、HEVC+EAC3)，从 CodecDecoderPool 按编码规格
 *    匹配复用或创建对应的 IjkMediaPlayer 与硬件音频 MediaCodec，切台时异步 reset 归还池中，避免每次销毁重建。
 */
public class IjkVideoView extends FrameLayout {
    private static final String TAG = "IjkVideoView";

    public static final int RENDER_SURFACE_VIEW = 0;
    public static final int RENDER_TEXTURE_VIEW = 1;

    private static final int STATE_ERROR = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_PREPARED = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;

    private static final int[] s_allAspectRatio = {
            IRenderView.AR_ASPECT_FIT_PARENT,
            IRenderView.AR_ASPECT_FILL_PARENT,
            IRenderView.AR_MATCH_PARENT,
            IRenderView.AR_16_9_FIT_PARENT,
            IRenderView.AR_4_3_FIT_PARENT
    };

    private Uri mUri;
    private String mRawChannelUrl;
    private Map<String, String> mHeaders;
    private final TsHardwareAudioCompanion mHwAudioCompanion = new TsHardwareAudioCompanion();

    // 下载模块与解码模块的池化句柄
    private final AtomicLong mPlaybackSessionToken = new AtomicLong(0);
    private StreamDownloaderPool.PooledStreamDownloader mActiveDownloader = null;
    private CodecDecoderPool.PooledIjkDecoder mPooledIjkDecoder = null;
    private volatile TsCodecDescriptor mActiveCodecDescriptor = null;

    private int mCurrentState = STATE_IDLE;
    private int mTargetState = STATE_IDLE;

    private IRenderView.ISurfaceHolder mSurfaceHolder = null;
    private IMediaPlayer mMediaPlayer = null;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mVideoRotationDegree;
    private int mVideoSarNum;
    private int mVideoSarDen;

    private Context mAppContext;
    private IptvSettings mSettings;
    private int mCurrentRender = RENDER_SURFACE_VIEW;
    private IRenderView mRenderView;
    private int mCurrentAspectRatioIndex = 0;
    private int mCurrentAspectRatio = s_allAspectRatio[0];

    private IMediaPlayer.OnPreparedListener mOnPreparedListener;
    private IMediaPlayer.OnCompletionListener mOnCompletionListener;
    private IMediaPlayer.OnErrorListener mOnErrorListener;
    private IMediaPlayer.OnInfoListener mOnInfoListener;

    private long mPrepareStartTime = 0;
    private long mPrepareEndTime = 0;

    public IjkVideoView(Context context) {
        super(context);
        initVideoView(context);
    }

    public IjkVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initVideoView(context);
    }

    public IjkVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initVideoView(context);
    }

    private void initVideoView(Context context) {
        mAppContext = context.getApplicationContext();
        mSettings = new IptvSettings(mAppContext);
        mCurrentAspectRatio = mSettings.getAspectRatio();

        setRender(mSettings.getRenderViewType());

        // 预热常用 Codec 规格的解码器池
        CodecDecoderPool.getInstance().prewarmCommonDecoders(mSettings);

        mVideoWidth = 0;
        mVideoHeight = 0;
        setFocusable(false);
        setFocusableInTouchMode(false);
        mCurrentState = STATE_IDLE;
        mTargetState = STATE_IDLE;
    }

    public void setRenderView(IRenderView renderView) {
        if (mRenderView != null) {
            if (mMediaPlayer != null) {
                mMediaPlayer.setDisplay(null);
            }
            View renderUIView = mRenderView.getView();
            mRenderView.removeRenderCallback(mSHCallback);
            mRenderView = null;
            mSurfaceHolder = null;
            mSurfaceWidth = 0;
            mSurfaceHeight = 0;
            removeView(renderUIView);
        }

        if (renderView == null) {
            return;
        }

        mRenderView = renderView;
        renderView.setAspectRatio(mCurrentAspectRatio);
        if (mVideoWidth > 0 && mVideoHeight > 0) {
            renderView.setVideoSize(mVideoWidth, mVideoHeight);
        }
        if (mVideoSarNum > 0 && mVideoSarDen > 0) {
            renderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
        }

        View renderUIView = mRenderView.getView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        renderUIView.setLayoutParams(lp);
        addView(renderUIView, 0);

        mRenderView.addRenderCallback(mSHCallback);
        mRenderView.setVideoRotation(mVideoRotationDegree);
    }

    public void setRender(int render) {
        if (mRenderView != null && mCurrentRender == render) {
            return;
        }
        mCurrentRender = render;
        switch (render) {
            case RENDER_TEXTURE_VIEW: {
                TextureRenderView renderView = new TextureRenderView(getContext());
                if (mMediaPlayer != null) {
                    renderView.getSurfaceHolder().bindToMediaPlayer(mMediaPlayer);
                    renderView.setVideoSize(mMediaPlayer.getVideoWidth(), mMediaPlayer.getVideoHeight());
                    renderView.setVideoSampleAspectRatio(mMediaPlayer.getVideoSarNum(), mMediaPlayer.getVideoSarDen());
                    renderView.setAspectRatio(mCurrentAspectRatio);
                }
                setRenderView(renderView);
                break;
            }
            case RENDER_SURFACE_VIEW:
            default: {
                mCurrentRender = RENDER_SURFACE_VIEW;
                SurfaceRenderView renderView = new SurfaceRenderView(getContext());
                setRenderView(renderView);
                break;
            }
        }
    }

    public void setRawChannelUrl(String rawChannelUrl) {
        mRawChannelUrl = rawChannelUrl;
    }

    public void setVideoPath(String path) {
        if (!TextUtils.isEmpty(path)) {
            setVideoURI(Uri.parse(path));
        }
    }

    public void setVideoURI(Uri uri) {
        setVideoURI(uri, null);
    }

    public void setVideoURI(Uri uri, Map<String, String> headers) {
        mUri = uri;
        mHeaders = headers;
        openVideo();
        requestLayout();
        invalidate();
    }

    public void stopPlayback() {
        release(true);
    }

    /**
     * 核心切台流程（下载模块与解码模块解耦）：
     * 1. 先从 StreamDownloaderPool 获取池化下载器启动新频道下载，实时解析 PAT/PMT 获取 TsCodecDescriptor 并对齐首个 IDR 关键帧；
     * 2. 下载器回调 onCodecParsed(descriptor) 后，根据解析出的编码规格从 CodecDecoderPool 获取（或创建）匹配的解码器；
     * 3. 通过 IMediaDataSource 零拷贝环形缓冲区将已对齐关键帧的 TS 流喂给池化解码器，避免每次重建解码器并彻底消除切台慢放。
     */
    private void openVideo() {
        if (mUri == null || mSurfaceHolder == null) {
            return;
        }
        release(false);

        final long sessionToken = mPlaybackSessionToken.incrementAndGet();

        AudioManager am = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        mPrepareStartTime = System.currentTimeMillis();
        mCurrentState = STATE_PREPARING;

        try {
            Uri playbackUri = resolvePlaybackUri(mUri);
            String scheme = playbackUri.getScheme();
            boolean isHlsStream = isHlsUri(playbackUri) || isHlsUrl(mRawChannelUrl);
            boolean useSeparatedPoolPipeline = (mSettings.getPlayerType() == IptvSettings.PLAYER_IJK)
                    && !isHlsStream
                    && scheme != null
                    && (scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("rtp")
                    || scheme.equalsIgnoreCase("udp"));

            if (useSeparatedPoolPipeline) {
                final StreamDownloaderPool.PooledStreamDownloader downloader =
                        StreamDownloaderPool.getInstance().acquire();
                mActiveDownloader = downloader;

                downloader.startDownload(playbackUri, mRawChannelUrl, new StreamDownloaderPool.OnStreamEventsListener() {
                    @Override
                    public void onCodecParsed(final TsCodecDescriptor descriptor,
                                              final StreamDownloaderPool.PooledStreamDownloader activeDl) {
                        post(() -> {
                            if (sessionToken != mPlaybackSessionToken.get()) {
                                return;
                            }
                            attachPooledDecoderForParsedStream(sessionToken, descriptor, activeDl);
                        });
                    }

                    @Override
                    public void onStreamError(final String reason,
                                              final StreamDownloaderPool.PooledStreamDownloader activeDl) {
                        post(() -> {
                            if (sessionToken != mPlaybackSessionToken.get()) {
                                return;
                            }
                            Log.w(TAG, "StreamDownloader error for " + mUri + ": " + reason);
                            mCurrentState = STATE_ERROR;
                            mTargetState = STATE_ERROR;
                            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
                        });
                    }
                });
                return;
            }

            if (isHlsStream && mSettings.getPlayerType() == IptvSettings.PLAYER_IJK) {
                TsCodecDescriptor hlsDesc = TsCodecDescriptor.createDefault(false);
                mActiveCodecDescriptor = hlsDesc;
                mPooledIjkDecoder = CodecDecoderPool.getInstance().acquireIjkDecoder(hlsDesc, mSettings);
                IjkMediaPlayer ijkPlayer = mPooledIjkDecoder.player;
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", 0);
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512 * 1024L);
                ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000L);
                if (mSettings.getRenderViewType() == RENDER_TEXTURE_VIEW) {
                    mMediaPlayer = new TextureMediaPlayer(ijkPlayer);
                } else {
                    mMediaPlayer = ijkPlayer;
                }
            } else {
                // 非 IJK 模式 (ExoPlayer / AndroidMediaPlayer) 走常规播放器路径
                mMediaPlayer = createFallbackPlayer(mSettings.getPlayerType());
            }
            attachPlayerListeners(mMediaPlayer);
            mMediaPlayer.setDataSource(mAppContext, playbackUri, mHeaders);
            bindSurfaceHolder(mMediaPlayer, mSurfaceHolder);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setScreenOnWhilePlaying(true);
            mMediaPlayer.prepareAsync();
        } catch (Throwable ex) {
            Log.w(TAG, "Unable to open IPTV stream: " + mUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    /**
     * 当下载模块完成 MPEG-TS 的 PAT/PMT Codec 解析与首个关键帧对齐后，
     * 根据 TsCodecDescriptor 从 CodecDecoderPool 中选择（或创建）对应规格的解码器并绑定数据源。
     */
    private void attachPooledDecoderForParsedStream(
            long sessionToken,
            TsCodecDescriptor descriptor,
            StreamDownloaderPool.PooledStreamDownloader downloader) {
        if (sessionToken != mPlaybackSessionToken.get() || mSurfaceHolder == null) {
            return;
        }
        try {
            mActiveCodecDescriptor = descriptor;
            mPooledIjkDecoder = CodecDecoderPool.getInstance().acquireIjkDecoder(descriptor, mSettings);
            IjkMediaPlayer ijkPlayer = mPooledIjkDecoder.player;

            if (mSettings.getRenderViewType() == RENDER_TEXTURE_VIEW) {
                mMediaPlayer = new TextureMediaPlayer(ijkPlayer);
            } else {
                mMediaPlayer = ijkPlayer;
            }

            attachPlayerListeners(mMediaPlayer);
            // 将池化下载器作为 IMediaDataSource 直接绑定给池化解码器
            ijkPlayer.setDataSource(downloader);
            bindSurfaceHolder(mMediaPlayer, mSurfaceHolder);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setScreenOnWhilePlaying(true);
            mMediaPlayer.prepareAsync();

            Log.i(TAG, "Bound pooled decoder [reused=" + mPooledIjkDecoder.reusedFromPool
                    + ", profile=" + mPooledIjkDecoder.profileKey
                    + "] to pooled downloader for codec=" + descriptor);
        } catch (Throwable ex) {
            Log.w(TAG, "Failed to bind pooled decoder for stream: " + mUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    private Uri resolvePlaybackUri(Uri sourceUri) {
        if (sourceUri == null) {
            return null;
        }
        String scheme = sourceUri.getScheme();
        if (scheme != null && (scheme.equalsIgnoreCase("rtp") || scheme.equalsIgnoreCase("udp"))) {
            // StreamDownloaderPool 内置了直连 IGMP 组播 Socket 的能力，可直接处理 rtp:// / udp://；
            // 若用户切换到 ExoPlayer / AndroidMediaPlayer，则通过 LocalMulticastProxyServer 转换。
            if (mSettings.getPlayerType() != IptvSettings.PLAYER_IJK && mAppContext instanceof IptvApplication) {
                int proxyPort = ((IptvApplication) mAppContext).getLocalProxyPort();
                if (proxyPort > 0) {
                    String localUrl = StreamUrlResolver.toUdpxyUrl(
                            sourceUri.toString(), "127.0.0.1:" + proxyPort);
                    return Uri.parse(localUrl);
                }
            }
        }
        return sourceUri;
    }

    private void attachPlayerListeners(IMediaPlayer mp) {
        if (mp == null) {
            return;
        }
        mp.setOnPreparedListener(mPreparedListener);
        mp.setOnVideoSizeChangedListener(mSizeChangedListener);
        mp.setOnCompletionListener(mCompletionListener);
        mp.setOnErrorListener(mErrorListener);
        mp.setOnInfoListener(mInfoListener);
    }

    public static boolean isHlsUri(Uri uri) {
        return uri != null && isHlsUrl(uri.toString());
    }

    public static boolean isHlsUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains(".m3u8");
    }

    public IMediaPlayer createFallbackPlayer(int playerType) {
        IMediaPlayer mediaPlayer;
        switch (playerType) {
            case IptvSettings.PLAYER_IJK:
                try {
                    IjkMediaPlayer ijkPlayer = new IjkMediaPlayer();
                    CodecDecoderPool.configureIjkOptionsForCodec(
                            ijkPlayer, TsCodecDescriptor.createDefault(false), mSettings);
                    mediaPlayer = ijkPlayer;
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to init IjkMediaPlayer, falling back to AndroidMediaPlayer", t);
                    mediaPlayer = new AndroidMediaPlayer();
                }
                break;
            case IptvSettings.PLAYER_IJK_EXO:
                try {
                    mediaPlayer = new IjkExoMediaPlayer(mAppContext);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to init IjkExoMediaPlayer, falling back to AndroidMediaPlayer", t);
                    mediaPlayer = new AndroidMediaPlayer();
                }
                break;
            case IptvSettings.PLAYER_ANDROID_MEDIA:
            default:
                mediaPlayer = new AndroidMediaPlayer();
                break;
        }
        if (mSettings.getRenderViewType() == RENDER_TEXTURE_VIEW) {
            mediaPlayer = new TextureMediaPlayer(mediaPlayer);
        }
        return mediaPlayer;
    }

    private void bindSurfaceHolder(IMediaPlayer mp, IRenderView.ISurfaceHolder holder) {
        if (mp == null) {
            return;
        }
        if (holder == null) {
            mp.setDisplay(null);
            return;
        }
        holder.bindToMediaPlayer(mp);
    }

    /**
     * 释放或回收当前播放会话：
     * - 下载模块对象归还至 StreamDownloaderPool；
     * - 解码模块对象归还至 CodecDecoderPool（后台异步 reset 复用，不销毁重建）。
     */
    public void release(boolean cleartargetstate) {
        mPlaybackSessionToken.incrementAndGet();
        mHwAudioCompanion.stop();

        if (mActiveDownloader != null) {
            StreamDownloaderPool.getInstance().release(mActiveDownloader);
            mActiveDownloader = null;
        }
        mActiveCodecDescriptor = null;

        if (mPooledIjkDecoder != null) {
            boolean healthy = (mCurrentState != STATE_ERROR);
            if (mMediaPlayer instanceof TextureMediaPlayer) {
                try {
                    ((TextureMediaPlayer) mMediaPlayer).releaseSurfaceTexture();
                } catch (Throwable ignored) {
                }
            }
            CodecDecoderPool.getInstance().releaseIjkDecoder(mPooledIjkDecoder, mSettings, healthy);
            mPooledIjkDecoder = null;
            mMediaPlayer = null;
        } else if (mMediaPlayer != null) {
            if (mMediaPlayer instanceof TextureMediaPlayer) {
                try {
                    ((TextureMediaPlayer) mMediaPlayer).releaseSurfaceTexture();
                } catch (Throwable ignored) {
                }
            }
            try {
                mMediaPlayer.setDisplay(null);
                mMediaPlayer.setSurface(null);
            } catch (Throwable ignored) {
            }
            try {
                mMediaPlayer.reset();
            } catch (Throwable ignored) {
            }
            try {
                mMediaPlayer.release();
            } catch (Throwable ignored) {
            }
            mMediaPlayer = null;
        }

        mCurrentState = STATE_IDLE;
        if (cleartargetstate) {
            mTargetState = STATE_IDLE;
        }
        AudioManager am = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.abandonAudioFocus(null);
        }
    }

    public void start() {
        if (isInPlaybackState()) {
            try {
                mMediaPlayer.start();
                mCurrentState = STATE_PLAYING;
            } catch (Throwable t) {
                Log.w(TAG, "start() failed", t);
            }
        }
        mTargetState = STATE_PLAYING;
    }

    public void pause() {
        if (isInPlaybackState()) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.pause();
                    mCurrentState = STATE_PAUSED;
                }
            } catch (Throwable t) {
                Log.w(TAG, "pause() failed", t);
            }
        }
        mTargetState = STATE_PAUSED;
    }

    public boolean isPlaying() {
        try {
            return isInPlaybackState() && mMediaPlayer.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isInPlaybackState() {
        return (mMediaPlayer != null &&
                mCurrentState != STATE_ERROR &&
                mCurrentState != STATE_IDLE &&
                mCurrentState != STATE_PREPARING);
    }

    public int toggleAspectRatio() {
        mCurrentAspectRatioIndex++;
        mCurrentAspectRatioIndex %= s_allAspectRatio.length;
        mCurrentAspectRatio = s_allAspectRatio[mCurrentAspectRatioIndex];
        if (mRenderView != null) {
            mRenderView.setAspectRatio(mCurrentAspectRatio);
        }
        mSettings.setAspectRatio(mCurrentAspectRatio);
        return mCurrentAspectRatio;
    }

    public int getCurrentAspectRatio() {
        return mCurrentAspectRatio;
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    public long getPrepareCostMs() {
        return mPrepareEndTime > mPrepareStartTime ? (mPrepareEndTime - mPrepareStartTime) : 0;
    }

    /**
     * 获取实时网络下载速度 (Bytes/sec)
     * 优先从分离的池化下载模块获取精确实时速率（同时支持 IGMP 组播与 HTTP-TS）
     */
    public long getTcpSpeed() {
        if (mActiveDownloader != null) {
            long dlSpeed = mActiveDownloader.getDownloadSpeedBytesPerSec();
            if (dlSpeed > 0) {
                return dlSpeed;
            }
        }
        if (!isInPlaybackState()) {
            return 0;
        }
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                return ((IjkMediaPlayer) mp).getTcpSpeed();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 获取实时渲染帧率
     */
    public float getVideoOutputFramesPerSecond() {
        if (!isInPlaybackState()) {
            return 0f;
        }
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                return ((IjkMediaPlayer) mp).getVideoOutputFramesPerSecond();
            }
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    /**
     * 获取当前解码器名称与池化状态
     */
    public String getDecoderName() {
        int playerType = mSettings.getPlayerType();
        if (playerType == IptvSettings.PLAYER_ANDROID_MEDIA) {
            String hwAudio = mHwAudioCompanion.getActiveCodecLabel();
            if (!TextUtils.isEmpty(hwAudio)) {
                return "AndroidMediaPlayer / " + hwAudio;
            }
            return "AndroidMediaPlayer (系统硬解)";
        } else if (playerType == IptvSettings.PLAYER_IJK_EXO) {
            String hwAudio = mHwAudioCompanion.getActiveCodecLabel();
            if (!TextUtils.isEmpty(hwAudio)) {
                return "IjkExoMediaPlayer / " + hwAudio;
            }
            return "IjkExoMediaPlayer (Exo硬解)";
        }

        boolean pooledHit = (mPooledIjkDecoder != null && mPooledIjkDecoder.reusedFromPool);
        String poolTag = pooledHit ? "池化复用" : "池化";
        String videoDecoder = mSettings.isMediaCodecEnabled() ? "MediaCodec" : "FFmpeg";
        if (isInPlaybackState()) {
            try {
                IMediaPlayer mp = unwrapIjkMediaPlayer();
                if (mp instanceof IjkMediaPlayer) {
                    IjkMediaPlayer ijk = (IjkMediaPlayer) mp;
                    int vdec = ijk.getVideoDecoder();
                    if (vdec == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC) {
                        videoDecoder = "MediaCodec (" + poolTag + "硬解)";
                    } else if (vdec == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC) {
                        videoDecoder = "FFmpeg (" + poolTag + "软解)";
                    }
                    String hwAudio = mHwAudioCompanion.getActiveCodecLabel();
                    if (!TextUtils.isEmpty(hwAudio)) {
                        return videoDecoder + " / " + hwAudio;
                    }
                    MediaInfo info = ijk.getMediaInfo();
                    if (info != null && !TextUtils.isEmpty(info.mAudioDecoderImpl)) {
                        return videoDecoder + " / Audio:" + info.mAudioDecoderImpl;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        String hwAudio = mHwAudioCompanion.getActiveCodecLabel();
        if (!TextUtils.isEmpty(hwAudio)) {
            return videoDecoder + " / " + hwAudio;
        }
        return videoDecoder;
    }

    /**
     * 提取符合 check_iptv_m3u.sh 格式的视频轨道描述，如 "#1 h264, 1920x1080,25fps"
     */
    public String getFormattedVideoTrackInfo() {
        if (!isInPlaybackState()) {
            return "";
        }
        String codec = (mActiveCodecDescriptor != null && !TextUtils.isEmpty(mActiveCodecDescriptor.videoCodec))
                ? mActiveCodecDescriptor.videoCodec
                : "h264";
        int fps = Math.round(getVideoOutputFramesPerSecond());
        if (fps <= 0) {
            fps = (mVideoWidth >= 3840) ? 50 : 25;
        }
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                Bundle bundle = ((IjkMediaPlayer) mp).getMediaMeta();
                IjkMediaMeta meta = IjkMediaMeta.parse(bundle);
                if (meta != null && meta.mStreams != null) {
                    for (IjkMediaMeta.IjkStreamMeta stream : meta.mStreams) {
                        if (stream != null && "video".equalsIgnoreCase(stream.mType)) {
                            if (!TextUtils.isEmpty(stream.mCodecName)) {
                                codec = stream.mCodecName.toLowerCase(Locale.US);
                            }
                            break;
                        }
                    }
                }
            } else if (mp != null) {
                ITrackInfo[] tracks = mp.getTrackInfo();
                if (tracks != null) {
                    for (ITrackInfo t : tracks) {
                        if (t != null && t.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                            IMediaFormat fmt = t.getFormat();
                            if (fmt != null) {
                                String c = fmt.getString(IjkMediaFormat.KEY_IJK_CODEC_NAME_UI);
                                if (!TextUtils.isEmpty(c)) {
                                    codec = c.toLowerCase(Locale.US);
                                }
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (mVideoWidth > 0 && mVideoHeight > 0) {
            return String.format(Locale.US, "#1 %s, %dx%d,%dfps", codec, mVideoWidth, mVideoHeight, fps);
        }
        return "";
    }

    /**
     * 提取符合 check_iptv_m3u.sh 格式的音频轨道描述，如 "#1 mp2,2ch,48khz"
     */
    public String getFormattedAudioTrackInfo() {
        if (!isInPlaybackState()) {
            return "";
        }
        String codec = (mActiveCodecDescriptor != null && !TextUtils.isEmpty(mActiveCodecDescriptor.audioCodec))
                ? mActiveCodecDescriptor.audioCodec
                : "mp2";
        int sampleRate = 48000;
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                Bundle bundle = ((IjkMediaPlayer) mp).getMediaMeta();
                IjkMediaMeta meta = IjkMediaMeta.parse(bundle);
                if (meta != null && meta.mStreams != null) {
                    for (IjkMediaMeta.IjkStreamMeta stream : meta.mStreams) {
                        if (stream != null && "audio".equalsIgnoreCase(stream.mType)) {
                            if (!TextUtils.isEmpty(stream.mCodecName)) {
                                codec = stream.mCodecName.toLowerCase(Locale.US);
                            }
                            if (stream.mSampleRate > 0) {
                                sampleRate = stream.mSampleRate;
                            }
                            break;
                        }
                    }
                }
            } else if (mp != null) {
                ITrackInfo[] tracks = mp.getTrackInfo();
                if (tracks != null) {
                    for (ITrackInfo t : tracks) {
                        if (t != null && t.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                            IMediaFormat fmt = t.getFormat();
                            if (fmt != null) {
                                String c = fmt.getString(IjkMediaFormat.KEY_IJK_CODEC_NAME_UI);
                                if (!TextUtils.isEmpty(c)) {
                                    codec = c.toLowerCase(Locale.US);
                                }
                                int sr = fmt.getInteger(IjkMediaFormat.KEY_IJK_SAMPLE_RATE_UI);
                                if (sr > 0) {
                                    sampleRate = sr;
                                }
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return String.format(Locale.US, "#1 %s,2ch,%dkhz", codec, sampleRate / 1000);
    }

    /**
     * 按照 check_iptv_m3u.sh 规则判定当前视频流分辨率标记 ("高清", "4K", "标清", "其它")
     */
    public String getDetectedResolutionFlag() {
        if (mVideoWidth == 1920 && mVideoHeight == 1080) {
            return "高清";
        } else if (mVideoWidth == 3840 && mVideoHeight == 2160) {
            return "4K";
        } else if (mVideoWidth == 720 && (mVideoHeight == 576 || mVideoHeight == 560)) {
            return "标清";
        } else if (mVideoWidth > 0 && mVideoHeight > 0) {
            return "其它";
        }
        return "";
    }

    private IMediaPlayer unwrapIjkMediaPlayer() {
        if (mMediaPlayer instanceof TextureMediaPlayer) {
            return ((TextureMediaPlayer) mMediaPlayer).getInternalMediaPlayer();
        }
        return mMediaPlayer;
    }

    public void setOnPreparedListener(IMediaPlayer.OnPreparedListener l) {
        mOnPreparedListener = l;
    }

    public void setOnCompletionListener(IMediaPlayer.OnCompletionListener l) {
        mOnCompletionListener = l;
    }

    public void setOnErrorListener(IMediaPlayer.OnErrorListener l) {
        mOnErrorListener = l;
    }

    public void setOnInfoListener(IMediaPlayer.OnInfoListener l) {
        mOnInfoListener = l;
    }

    private final IMediaPlayer.OnVideoSizeChangedListener mSizeChangedListener =
            new IMediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
                    mVideoWidth = mp.getVideoWidth();
                    mVideoHeight = mp.getVideoHeight();
                    mVideoSarNum = mp.getVideoSarNum();
                    mVideoSarDen = mp.getVideoSarDen();
                    if (mVideoWidth != 0 && mVideoHeight != 0) {
                        if (mRenderView != null) {
                            mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                            mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
                            if (!mRenderView.shouldWaitForResize() || (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight)) {
                                if (mTargetState == STATE_PLAYING && !isPlaying()) {
                                    start();
                                }
                            } else if (mTargetState == STATE_PLAYING && isInPlaybackState() && !isPlaying()) {
                                // AndroidMediaPlayer 播放 MPEG-TS 时若 Surface 尺寸已就绪，兜底立即启动播放，防止因等待 resize 导致卡在 PREPARED 黑屏
                                start();
                            }
                        }
                        requestLayout();
                    }
                }
            };

    private final IMediaPlayer.OnPreparedListener mPreparedListener = new IMediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(IMediaPlayer mp) {
            mPrepareEndTime = System.currentTimeMillis();
            mCurrentState = STATE_PREPARED;

            try {
                mp.setVolume(1.0f, 1.0f);
            } catch (Throwable ignored) {
            }
            checkAndStartHwAudioIfNeeded();

            mVideoWidth = mp.getVideoWidth();
            mVideoHeight = mp.getVideoHeight();

            if (mOnPreparedListener != null) {
                mOnPreparedListener.onPrepared(mMediaPlayer);
            }

            if (mVideoWidth != 0 && mVideoHeight != 0) {
                if (mRenderView != null) {
                    mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                    mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
                    if (!mRenderView.shouldWaitForResize()
                            || (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight)
                            || mSettings.getPlayerType() != IptvSettings.PLAYER_IJK) {
                        if (mTargetState == STATE_PLAYING) {
                            start();
                        }
                    }
                }
            } else {
                if (mTargetState == STATE_PLAYING) {
                    start();
                }
            }
        }
    };

    /**
     * 检查音频轨道是否需要挂载池化硬件伴音解码器：
     * - MP2 / MP3 / AAC / FLAC 音频由补丁后的 libijkffmpeg.so 直接原生解码输出；
     * - 若遇 AC-3 / E-AC-3 (杜比 4K 频道) 等格式，直接通过当前 PooledStreamDownloader 的旁路缓冲流
     *   驱动池化的 Android TV MediaCodec 硬件音频解码器，无需建立第二条网络连接。
     */
    private void checkAndStartHwAudioIfNeeded() {
        try {
            TsCodecDescriptor desc = mActiveCodecDescriptor;
            if (desc != null && desc.requiresHardwareAudioCompanion() && mActiveDownloader != null) {
                Log.i(TAG, "Starting pooled TsHardwareAudioCompanion via active downloader for " + desc);
                mHwAudioCompanion.startWithDownloader(mActiveDownloader, desc);
                return;
            }
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                IjkMediaPlayer ijk = (IjkMediaPlayer) mp;
                MediaInfo info = ijk.getMediaInfo();
                String audioDec = (info != null) ? info.mAudioDecoderImpl : null;
                String videoDec = (info != null) ? info.mVideoDecoderImpl : null;
                Log.i(TAG, "onPrepared IjkMediaPlayer audioDecoder=" + audioDec
                        + ", videoDecoder=" + videoDec);
                if (TextUtils.isEmpty(audioDec) || "unknown".equalsIgnoreCase(audioDec)) {
                    if (mActiveDownloader != null) {
                        mHwAudioCompanion.startWithDownloader(mActiveDownloader, desc);
                    } else {
                        mHwAudioCompanion.start(mUri, mRawChannelUrl);
                    }
                } else {
                    mHwAudioCompanion.stop();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "checkAndStartHwAudioIfNeeded failed", t);
        }
    }

    private final IMediaPlayer.OnCompletionListener mCompletionListener = new IMediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(IMediaPlayer mp) {
            mCurrentState = STATE_PLAYBACK_COMPLETED;
            mTargetState = STATE_PLAYBACK_COMPLETED;
            if (mOnCompletionListener != null) {
                mOnCompletionListener.onCompletion(mMediaPlayer);
            }
        }
    };

    private final IMediaPlayer.OnInfoListener mInfoListener = new IMediaPlayer.OnInfoListener() {
        @Override
        public boolean onInfo(IMediaPlayer mp, int arg1, int arg2) {
            if (mOnInfoListener != null) {
                mOnInfoListener.onInfo(mp, arg1, arg2);
            }
            if (arg1 == IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED) {
                mVideoRotationDegree = arg2;
                if (mRenderView != null) {
                    mRenderView.setVideoRotation(arg2);
                }
            }
            return true;
        }
    };

    private final IMediaPlayer.OnErrorListener mErrorListener = new IMediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(IMediaPlayer mp, int frameworkErr, int implErr) {
            Log.e(TAG, "Playback error: " + frameworkErr + ", " + implErr);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            if (mOnErrorListener != null) {
                return mOnErrorListener.onError(mMediaPlayer, frameworkErr, implErr);
            }
            return true;
        }
    };

    private final IRenderView.IRenderCallback mSHCallback = new IRenderView.IRenderCallback() {
        @Override
        public void onSurfaceChanged(@NonNull IRenderView.ISurfaceHolder holder, int format, int w, int h) {
            if (holder.getRenderView() != mRenderView) {
                return;
            }
            mSurfaceWidth = w;
            mSurfaceHeight = h;
            boolean isValidState = (mTargetState == STATE_PLAYING);
            boolean hasValidSize = !mRenderView.shouldWaitForResize() || (mVideoWidth == w && mVideoHeight == h);
            if (mMediaPlayer != null && isValidState && hasValidSize) {
                start();
            }
        }

        @Override
        public void onSurfaceCreated(@NonNull IRenderView.ISurfaceHolder holder, int width, int height) {
            if (holder.getRenderView() != mRenderView) {
                return;
            }
            mSurfaceHolder = holder;
            if (mMediaPlayer != null) {
                bindSurfaceHolder(mMediaPlayer, holder);
            } else {
                openVideo();
            }
        }

        @Override
        public void onSurfaceDestroyed(@NonNull IRenderView.ISurfaceHolder holder) {
            if (holder.getRenderView() != mRenderView) {
                return;
            }
            mSurfaceHolder = null;
            if (mMediaPlayer != null) {
                mMediaPlayer.setDisplay(null);
            }
        }
    };
}
