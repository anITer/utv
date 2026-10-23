package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.bjunicom.iptv.IptvApplication;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.network.StreamUrlResolver;

import java.util.Locale;
import java.util.Map;

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
 * 基于 bilibili/ijkplayer 的三实例级池化播放器视图 (3-Instance Player Pool)
 *
 * 池化策略（准备 3 个 Player 实例）：
 * 1. 实例 1 (mActiveSlot)：绑定主屏幕 SurfaceHolder，正常音量播放当前频道资源；
 * 2. 实例 2 (mPrevSlot)：绑定独立离屏 Surface，静音在后台离屏播放【上一个】相邻频道；
 * 3. 实例 3 (mNextSlot)：绑定独立离屏 Surface，静音在后台离屏播放【下一个】相邻频道；
 *
 * 切台流转机制：
 * - 当用户切换到上一个或下一个频道时，若命中对应的离屏 Player 实例，立即将原当前实例切换至其离屏 Surface 并静音（降级为新的相邻离屏实例），
 *   同时将命中的离屏实例直接绑定到主屏幕 Surface 并恢复音量（升级为新的当前播放实例），仅需重置剩下的第 3 个实例去离屏加载新的相邻频道。
 */
public class IjkVideoView extends FrameLayout {
    private static final String TAG = "IjkVideoView";

    public static final int PLAYER_POOL_SIZE = 3;
    public static final long OFFSCREEN_IDLE_STOP_MS = 3000L; // 切台 3 秒后若未继续切台，自动停止后台离屏播放器

    public static final int RENDER_SURFACE_VIEW = 0;
    public static final int RENDER_TEXTURE_VIEW = 1;

    public static final int STATE_ERROR = -1;
    public static final int STATE_IDLE = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_PREPARED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_PAUSED = 4;
    public static final int STATE_PLAYBACK_COMPLETED = 5;

    private static final int[] s_allAspectRatio = {
            IRenderView.AR_ASPECT_FIT_PARENT,
            IRenderView.AR_ASPECT_FILL_PARENT,
            IRenderView.AR_MATCH_PARENT,
            IRenderView.AR_16_9_FIT_PARENT,
            IRenderView.AR_4_3_FIT_PARENT
    };

    /**
     * 单个 Player 实例槽位：持有独立的 IMediaPlayer (IjkMediaPlayer 或 AndroidMediaPlayer) 与专属离屏 Surface
     */
    public static final class PlayerSlot {
        public final int slotIndex;
        public IMediaPlayer player;
        public int playerType = -1;
        public String codecConfigSignature = "";
        public Uri uri;
        public String rawChannelUrl;
        public int state = STATE_IDLE;
        public int videoWidth;
        public int videoHeight;
        public int videoSarNum;
        public int videoSarDen;
        public int videoRotationDegree;
        public long prepareStartTime;
        public long prepareEndTime;
        public boolean promotedFromOffscreen;
        // IJK 直连组播时的 IDR 对齐下载器（非空表示本槽位由它供流，释放槽位时必须归还池）
        StreamDownloaderPool.PooledStreamDownloader pooledDownloader;

        private SurfaceTexture offscreenTexture;
        private Surface offscreenSurface;

        PlayerSlot(int slotIndex) {
            this.slotIndex = slotIndex;
        }

        synchronized Surface ensureOffscreenSurface() {
            if (offscreenSurface != null && offscreenSurface.isValid()) {
                return offscreenSurface;
            }
            releaseOffscreenSurface();
            try {
                offscreenTexture = new SurfaceTexture(0);
                offscreenTexture.setDefaultBufferSize(16, 16);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    try {
                        offscreenTexture.detachFromGLContext();
                    } catch (Throwable ignored) {
                    }
                }
                offscreenSurface = new Surface(offscreenTexture);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to create offscreen surface for slot " + slotIndex + ": " + t.getMessage());
            }
            return offscreenSurface;
        }

        void bindToOffscreenSurface() {
            if (player == null) {
                return;
            }
            try {
                IMediaPlayer rawPlayer = unwrapPlayer(player);
                Surface offscreen = ensureOffscreenSurface();
                if (offscreen != null && offscreen.isValid()) {
                    // 直接将底层 IjkMediaPlayer 或 AndroidMediaPlayer (MediaPlayer) 切换至离屏 Surface，
                    // 避免先 setDisplay(null) 导致 AndroidMediaPlayer 在 PREPARING/PLAYING 状态触发空 Surface 异常
                    rawPlayer.setSurface(offscreen);
                } else {
                    rawPlayer.setDisplay(null);
                }
            } catch (Throwable ignored) {
            }
        }

        void setMuted(boolean muted) {
            if (player == null) {
                return;
            }
            try {
                float vol = muted ? 0.0f : 1.0f;
                player.setVolume(vol, vol);
            } catch (Throwable ignored) {
            }
        }

        boolean matchesStream(Uri targetUri, String targetRawUrl) {
            if (state == STATE_IDLE || player == null) {
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

        void resetMetadata() {
            uri = null;
            rawChannelUrl = null;
            state = STATE_IDLE;
            videoWidth = 0;
            videoHeight = 0;
            videoSarNum = 0;
            videoSarDen = 0;
            videoRotationDegree = 0;
            prepareStartTime = 0L;
            prepareEndTime = 0L;
            promotedFromOffscreen = false;
        }

        synchronized void releaseOffscreenSurface() {
            if (offscreenSurface != null) {
                try {
                    offscreenSurface.release();
                } catch (Throwable ignored) {
                }
                offscreenSurface = null;
            }
            if (offscreenTexture != null) {
                try {
                    offscreenTexture.release();
                } catch (Throwable ignored) {
                }
                offscreenTexture = null;
            }
        }
    }

    private final PlayerSlot[] mPlayerSlots = new PlayerSlot[PLAYER_POOL_SIZE];
    private PlayerSlot mActiveSlot = null;
    private PlayerSlot mPrevSlot = null;
    private PlayerSlot mNextSlot = null;
    private UTVPlayer mUtvPlayer = null;
    private final Runnable mStopOffscreenRunnable = this::stopOffscreenPlayers;

    private Uri mUri;
    private String mRawChannelUrl;
    private Uri mPendingPrevUri;
    private String mPendingPrevRawUrl;
    private Uri mPendingNextUri;
    private String mPendingNextRawUrl;
    private Map<String, String> mHeaders;
    private final TsHardwareAudioCompanion mHwAudioCompanion = new TsHardwareAudioCompanion();

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

    // 4K HEVC 硬件解码静默降级检测与自救（索尼/联发科 OMX 解码器在旧实例尚未拆除完毕时
    // configure 必失败 err -1010，ijk 无提示回退 avcodec 软解，4K@50fps 软解帧率仅个位数）：
    // onPrepared 后主动检查解码器实现，先彻底销毁全部实例做全新重试（最多 2 次），
    // 仍失败才回调外部一次性走系统播放器兜底；任何切台都重新优先 IJK
    private boolean mForceFallbackPlayer = false;
    private OnHevcSoftwareDecodeListener mOnHevcSoftwareDecodeListener;
    private int mHevcFreshRetryCount = 0;
    private static final int MAX_HEVC_FRESH_RETRIES = 2;
    private long mHevcRetryToken = 0L;

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

        for (int i = 0; i < PLAYER_POOL_SIZE; i++) {
            mPlayerSlots[i] = new PlayerSlot(i);
        }
        mActiveSlot = mPlayerSlots[0];
        mPrevSlot = mPlayerSlots[1];
        mNextSlot = mPlayerSlots[2];

        setRender(mSettings.getRenderViewType());

        mVideoWidth = 0;
        mVideoHeight = 0;
        setFocusable(false);
        setFocusableInTouchMode(false);
        mCurrentState = STATE_IDLE;
        mTargetState = STATE_IDLE;
    }

    public int getPlayerPoolSize() {
        return PLAYER_POOL_SIZE;
    }

    public PlayerSlot[] getPlayerSlots() {
        return mPlayerSlots;
    }

    public PlayerSlot getActiveSlot() {
        return mActiveSlot;
    }

    public PlayerSlot getPrevSlot() {
        return mPrevSlot;
    }

    public PlayerSlot getNextSlot() {
        return mNextSlot;
    }

    public boolean isActivePlayerPromotedFromPool() {
        if (mSettings.getPlayerType() == IptvSettings.PLAYER_UTV && mUtvPlayer != null) {
            return mUtvPlayer.isPromotedFromPreload();
        }
        return mActiveSlot != null && mActiveSlot.promotedFromOffscreen;
    }

    public UTVPlayer getUtvPlayer() {
        if (mUtvPlayer == null) {
            mUtvPlayer = UTVPlayer.getSharedInstance(mAppContext);
        }
        return mUtvPlayer;
    }

    public boolean areOffscreenPlayersStopped() {
        if (mSettings.getPlayerType() == IptvSettings.PLAYER_UTV && mUtvPlayer != null) {
            return mUtvPlayer.areBackgroundPreloadPeriodsStopped();
        }
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null && slot != mActiveSlot && slot.state != STATE_IDLE) {
                return false;
            }
        }
        return true;
    }

    /**
     * 当用户切台 3 秒后没有继续切台时调用：
     * 停止并重置除当前主屏实例 (mActiveSlot) 以外的两个后台离屏播放器 (mPrevSlot 与 mNextSlot)，
     * 若当前为 UTVPlayer 模式则停止后台 2 个预拉流 ProgressiveMediaPeriod，断开其后台流连接并释放资源。
     */
    public void stopOffscreenPlayers() {
        removeCallbacks(mStopOffscreenRunnable);
        if (mUtvPlayer != null) {
            mUtvPlayer.stopBackgroundPreloadPeriods();
        }
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null && slot != mActiveSlot) {
                stopAndResetOffscreenSlot(slot);
            }
        }
        Log.i(TAG, "Stopped off-screen background players after " + OFFSCREEN_IDLE_STOP_MS + "ms idle");
    }

    private void scheduleStopOffscreenPlayers() {
        removeCallbacks(mStopOffscreenRunnable);
        postDelayed(mStopOffscreenRunnable, OFFSCREEN_IDLE_STOP_MS);
    }

    private void stopAndResetOffscreenSlot(PlayerSlot slot) {
        if (slot == null) {
            return;
        }
        if (slot.player != null && slot.state != STATE_IDLE) {
            IMediaPlayer rawPlayer = unwrapPlayer(slot.player);
            // AndroidMediaPlayer 在 STATE_PREPARING 状态直接调 stop() 会抛 IllegalStateException，
            // 仅在已进入 PREPARED/PLAYING/PAUSED 时调用 stop()，随后统一 reset()
            if (slot.state == STATE_PREPARED || slot.state == STATE_PLAYING || slot.state == STATE_PAUSED) {
                try {
                    slot.player.stop();
                } catch (Throwable ignored) {
                }
            }
            try {
                rawPlayer.setSurface(null);
            } catch (Throwable ignored) {
            }
            try {
                slot.player.reset();
            } catch (Throwable ignored) {
            }
        }
        slot.resetMetadata();
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
        mHevcFreshRetryCount = 0;
        mHevcRetryToken++; // 使进行中的延迟重试失效，避免旧频道的重试打断新频道
        openVideo();
        requestLayout();
        invalidate();
    }

    /**
     * 设置并更新上一个、下一个频道的离屏播放实例 (mPrevSlot 与 mNextSlot)，
     * 或在 UTVPlayer 模式下驱动 PreloadMediaSource 维护 3 个独立的 ProgressiveMediaPeriod 预拉流池，
     * 并启动/重置 3 秒无切台自动停止后台播放器的计时器。
     */
    public void setAdjacentChannelPaths(String prevPath,
                                        String prevRawUrl,
                                        String nextPath,
                                        String nextRawUrl) {
        mPendingPrevUri = !TextUtils.isEmpty(prevPath) ? resolvePlaybackUri(Uri.parse(prevPath)) : null;
        mPendingPrevRawUrl = prevRawUrl;
        mPendingNextUri = !TextUtils.isEmpty(nextPath) ? resolvePlaybackUri(Uri.parse(nextPath)) : null;
        mPendingNextRawUrl = nextRawUrl;

        if (mSettings.getPlayerType() == IptvSettings.PLAYER_UTV) {
            getUtvPlayer().preloadAdjacentChannels(
                    mPendingPrevUri, mPendingPrevRawUrl,
                    mPendingNextUri, mPendingNextRawUrl);
            scheduleStopOffscreenPlayers();
            return;
        }

        if (mSurfaceHolder != null) {
            syncOffscreenAdjacentSlots();
        }
        scheduleStopOffscreenPlayers();
    }

    public void stopPlayback() {
        releaseAllSlots(true);
    }

    /**
     * 核心切台与三实例池化调度逻辑：
     * 1. 检查目标频道是否已由离屏实例 (mPrevSlot 或 mNextSlot) 在后台离屏播放/准备中；
     * 2. 若命中离屏实例：
     *    - 先将旧 mActiveSlot 切换绑定至其专属离屏 Surface 并静音（保留其播放状态直接作为新的相邻离屏实例）；
     *    - 再将命中的离屏实例绑定至主屏幕 SurfaceHolder 并恢复音量 1.0f，实现毫秒级无缝切台；
     * 3. 若未命中（如首次启动或数字键跨台）：
     *    - 在 mActiveSlot 中重置并加载当前频道，同时由 mPrevSlot 与 mNextSlot 在后台离屏播放上/下一频道。
     */
    private void openVideo() {
        if (mUri == null) {
            return;
        }
        int playerType = mForceFallbackPlayer
                ? IptvSettings.PLAYER_ANDROID_MEDIA
                : mSettings.getPlayerType();
        if (playerType != IptvSettings.PLAYER_UTV && mSurfaceHolder == null) {
            return;
        }
        mHwAudioCompanion.stop();
        ensureAllSlotsMatchPlayerType(playerType);

        AudioManager am = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        Uri playbackUri = resolvePlaybackUri(mUri);
        if (playerType == IptvSettings.PLAYER_UTV) {
            openWithUtvPlayer(playbackUri);
            return;
        }
        PlayerSlot hitSlot = findMatchingSlot(playbackUri, mRawChannelUrl);

        if (hitSlot != null && hitSlot != mActiveSlot) {
            promoteOffscreenSlotToActive(hitSlot);
            return;
        }

        if (hitSlot != null && hitSlot == mActiveSlot
                && (hitSlot.state == STATE_PLAYING || hitSlot.state == STATE_PREPARED || hitSlot.state == STATE_PREPARING)) {
            bindSurfaceHolder(hitSlot.player, mSurfaceHolder);
            hitSlot.setMuted(false);
            if (mTargetState == STATE_PLAYING && hitSlot.state != STATE_PREPARING) {
                start();
            }
            return;
        }

        if (mActiveSlot == null) {
            mActiveSlot = mPlayerSlots[0];
        } else if (mActiveSlot.state != STATE_IDLE
                && mActiveSlot.state != STATE_ERROR
                && mActiveSlot.player != null
                && !TextUtils.equals(mActiveSlot.rawChannelUrl, mRawChannelUrl)) {
            // 若用户超过 3 秒后再次切台（后台离屏实例已停止），将当前正在播放的主屏实例退居离屏作为上一频道缓存，
            // 并启用空闲槽位加载新频道
            PlayerSlot oldActive = mActiveSlot;
            oldActive.setMuted(true);
            oldActive.bindToOffscreenSurface();
            PlayerSlot nextActive = (mNextSlot != null && mNextSlot != oldActive)
                    ? mNextSlot
                    : (mPrevSlot != null && mPrevSlot != oldActive ? mPrevSlot : mPlayerSlots[0]);
            mActiveSlot = nextActive;
        }
        rebalancePrevNextPointers();

        mPrepareStartTime = System.currentTimeMillis();
        mCurrentState = STATE_PREPARING;
        loadSlotWithStream(mActiveSlot, playbackUri, mRawChannelUrl, true);
        scheduleStopOffscreenPlayers();
    }

    /**
     * 使用 UTVPlayer (全局单实例 ExoPlayer + PreloadMediaSource + UTVLoadControl + PlaceholderSurface) 打开频道流：
     * - 在同一个 ExoPlayer 实例中切换 MediaSource，触发 MediaCodecVideoRenderer.canReuseCodec() 直接 flush() 复用解码器；
     * - 通过 setVideoSurfaceView 动态绑定主屏 SurfaceView，若外部 SurfaceView 尚未就绪或已销毁则挂靠在 PlaceholderSurface 上后台预解码首帧。
     */
    private void openWithUtvPlayer(Uri playbackUri) {
        try {
            UTVPlayer utvPlayer = getUtvPlayer();
            mMediaPlayer = utvPlayer;
            mPrepareStartTime = System.currentTimeMillis();
            mCurrentState = STATE_PREPARING;

            if (mActiveSlot == null) {
                mActiveSlot = mPlayerSlots[0];
            }
            mActiveSlot.player = utvPlayer;
            mActiveSlot.playerType = IptvSettings.PLAYER_UTV;
            mActiveSlot.codecConfigSignature = buildCurrentCodecConfigSignature();
            mActiveSlot.uri = playbackUri;
            mActiveSlot.rawChannelUrl = mRawChannelUrl;
            mActiveSlot.state = STATE_PREPARING;
            mActiveSlot.prepareStartTime = mPrepareStartTime;

            attachSlotListeners(mActiveSlot, utvPlayer);
            bindSurfaceHolder(utvPlayer, mSurfaceHolder);
            utvPlayer.setRawChannelUrl(mRawChannelUrl);
            utvPlayer.setDataSource(mAppContext, playbackUri, mHeaders);
            utvPlayer.setVolume(1.0f, 1.0f);
            utvPlayer.setScreenOnWhilePlaying(true);
            utvPlayer.prepareAsync();
            scheduleStopOffscreenPlayers();
        } catch (Throwable ex) {
            Log.w(TAG, "Unable to open stream with UTVPlayer: " + playbackUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            if (mOnErrorListener != null) {
                mOnErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            }
        }
    }

    private PlayerSlot findMatchingSlot(Uri playbackUri, String rawChannelUrl) {
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null && slot.matchesStream(playbackUri, rawChannelUrl)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 将命中的离屏实例 (mPrevSlot 或 mNextSlot) 直接转正为主屏幕播放实例
     */
    private void promoteOffscreenSlotToActive(PlayerSlot promotedSlot) {
        PlayerSlot oldActive = mActiveSlot;
        if (oldActive != null && oldActive != promotedSlot && oldActive.player != null) {
            // 先将旧主屏实例切到其专属离屏 Surface 并静音，释放主屏 Surface 独占锁，同时保留其播放流作为新相邻频道
            oldActive.setMuted(true);
            oldActive.bindToOffscreenSurface();
        }

        mActiveSlot = promotedSlot;
        mActiveSlot.promotedFromOffscreen = true;
        mMediaPlayer = mActiveSlot.player;
        rebalancePrevNextPointers();

        // 绑定主屏 Surface 并恢复音量
        bindSurfaceHolder(mMediaPlayer, mSurfaceHolder);
        mActiveSlot.setMuted(false);

        mVideoWidth = mActiveSlot.videoWidth;
        mVideoHeight = mActiveSlot.videoHeight;
        mVideoSarNum = mActiveSlot.videoSarNum;
        mVideoSarDen = mActiveSlot.videoSarDen;
        mVideoRotationDegree = mActiveSlot.videoRotationDegree;
        mPrepareStartTime = mActiveSlot.prepareStartTime;

        Log.i(TAG, "Promoted off-screen player slot #" + promotedSlot.slotIndex
                + " (state=" + promotedSlot.state + ") to active main screen for " + mUri);

        if (mActiveSlot.state == STATE_PREPARED || mActiveSlot.state == STATE_PLAYING) {
            mPrepareEndTime = System.currentTimeMillis();
            mCurrentState = STATE_PREPARED;
            if (mVideoWidth > 0 && mVideoHeight > 0 && mRenderView != null) {
                mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
            }
            checkAndStartHwAudioIfNeeded();
            if (mOnPreparedListener != null) {
                mOnPreparedListener.onPrepared(mMediaPlayer);
            }
            if (mTargetState == STATE_PLAYING) {
                start();
            }
        } else if (mActiveSlot.state == STATE_ERROR) {
            // 若离屏预播放期间曾发生错误（如网络闪断或非真机环境），转正到主屏后立即在该槽位重新尝试连接
            mPrepareStartTime = System.currentTimeMillis();
            mCurrentState = STATE_PREPARING;
            loadSlotWithStream(mActiveSlot, resolvePlaybackUri(mUri), mRawChannelUrl, true);
            mActiveSlot.promotedFromOffscreen = true;
        } else {
            mCurrentState = STATE_PREPARING;
        }
    }

    /**
     * 重新分配除 mActiveSlot 外的另外两个实例作为 mPrevSlot 和 mNextSlot，
     * 优先保留已经在播放目标相邻频道的实例（例如刚从主屏退居离屏的上一频道实例）。
     */
    private void rebalancePrevNextPointers() {
        PlayerSlot candidateA = null;
        PlayerSlot candidateB = null;
        for (PlayerSlot s : mPlayerSlots) {
            if (s != mActiveSlot) {
                if (candidateA == null) {
                    candidateA = s;
                } else {
                    candidateB = s;
                }
            }
        }
        if (candidateA == null || candidateB == null) {
            return;
        }
        if (mPendingPrevUri != null && candidateA.matchesStream(mPendingPrevUri, mPendingPrevRawUrl)) {
            mPrevSlot = candidateA;
            mNextSlot = candidateB;
        } else if (mPendingPrevUri != null && candidateB.matchesStream(mPendingPrevUri, mPendingPrevRawUrl)) {
            mPrevSlot = candidateB;
            mNextSlot = candidateA;
        } else if (mPendingNextUri != null && candidateA.matchesStream(mPendingNextUri, mPendingNextRawUrl)) {
            mNextSlot = candidateA;
            mPrevSlot = candidateB;
        } else if (mPendingNextUri != null && candidateB.matchesStream(mPendingNextUri, mPendingNextRawUrl)) {
            mNextSlot = candidateB;
            mPrevSlot = candidateA;
        } else {
            mPrevSlot = candidateA;
            mNextSlot = candidateB;
        }
    }

    /**
     * 同步驱动 mPrevSlot 与 mNextSlot 在后台离屏播放上一个和下一个频道
     */
    private void syncOffscreenAdjacentSlots() {
        rebalancePrevNextPointers();

        if (mPrevSlot != null && mPendingPrevUri != null && !mPendingPrevUri.equals(mActiveSlot != null ? mActiveSlot.uri : null)) {
            if (!mPrevSlot.matchesStream(mPendingPrevUri, mPendingPrevRawUrl)) {
                loadSlotWithStream(mPrevSlot, mPendingPrevUri, mPendingPrevRawUrl, false);
            } else {
                mPrevSlot.setMuted(true);
                mPrevSlot.bindToOffscreenSurface();
            }
        }

        if (mNextSlot != null && mPendingNextUri != null
                && !mPendingNextUri.equals(mActiveSlot != null ? mActiveSlot.uri : null)
                && !mPendingNextUri.equals(mPendingPrevUri)) {
            if (!mNextSlot.matchesStream(mPendingNextUri, mPendingNextRawUrl)) {
                loadSlotWithStream(mNextSlot, mPendingNextUri, mPendingNextRawUrl, false);
            } else {
                mNextSlot.setMuted(true);
                mNextSlot.bindToOffscreenSurface();
            }
        }
    }

    /**
     * 在指定 PlayerSlot 中重置/创建播放器实例并加载流（支持主屏播放与离屏静音预播放）
     */
    private void loadSlotWithStream(final PlayerSlot slot,
                                    final Uri playbackUri,
                                    final String rawUrl,
                                    final boolean isMainScreen) {
        if (slot == null || playbackUri == null) {
            return;
        }
        slot.uri = playbackUri;
        slot.rawChannelUrl = rawUrl;
        slot.state = STATE_PREPARING;
        slot.prepareStartTime = System.currentTimeMillis();
        slot.prepareEndTime = 0L;
        slot.promotedFromOffscreen = false;
        try {
            resetOrCreateSlotPlayer(slot, rawUrl);
            slot.uri = playbackUri;
            slot.rawChannelUrl = rawUrl;
            slot.state = STATE_PREPARING;
            slot.prepareStartTime = System.currentTimeMillis();
            slot.prepareEndTime = 0L;
            slot.promotedFromOffscreen = false;

            IMediaPlayer mp = slot.player;
            attachSlotListeners(slot, mp);
            releaseSlotDownloader(slot); // 复用槽位时先归还上一频道的组播下载会话
            IjkMediaPlayer rawIjk = unwrapPlayer(mp) instanceof IjkMediaPlayer
                    ? (IjkMediaPlayer) unwrapPlayer(mp) : null;
            Uri rawMulticastUri = getRawMulticastUri(rawUrl);
            if (rawIjk != null && rawMulticastUri != null) {
                // IJK 直连组播 + PAT/PMT/IDR 对齐（旧架构已在本机验证）：
                // 本地 HTTP 代理从任意 GOP 中点开始转发，4K HEVC 在探针窗口内等不到完整 IDR，
                // FFmpeg 探测不到视频参数 → MTK 硬解以 0x0 配置失败 → 静默降级软解 (5fps)。
                // 对齐下载器在关键帧边界才开始喂数据，小探针也能秒级探测成功。
                StreamDownloaderPool.PooledStreamDownloader downloader =
                        StreamDownloaderPool.getInstance().acquire();
                slot.pooledDownloader = downloader;
                downloader.startDownload(rawMulticastUri, rawUrl,
                        new StreamDownloaderPool.OnStreamEventsListener() {
                            @Override
                            public void onCodecParsed(TsCodecDescriptor descriptor,
                                                      StreamDownloaderPool.PooledStreamDownloader dl) {
                                Log.i(TAG, "Slot #" + slot.slotIndex + " codec parsed: " + descriptor);
                            }

                            @Override
                            public void onStreamError(String reason,
                                                      StreamDownloaderPool.PooledStreamDownloader dl) {
                                Log.w(TAG, "Slot #" + slot.slotIndex + " stream error: " + reason);
                            }
                        });
                rawIjk.setDataSource(downloader);
            } else {
                mp.setDataSource(mAppContext, playbackUri, mHeaders);
            }

            if (isMainScreen) {
                mMediaPlayer = mp;
                bindSurfaceHolder(mp, mSurfaceHolder);
                slot.setMuted(false);
                mp.setScreenOnWhilePlaying(true);
            } else {
                slot.bindToOffscreenSurface();
                slot.setMuted(true);
            }

            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.prepareAsync();
        } catch (Throwable ex) {
            Log.w(TAG, "Unable to open stream in slot #" + slot.slotIndex + ": " + playbackUri, ex);
            if (isMainScreen && slot == mActiveSlot) {
                slot.state = STATE_ERROR;
                mCurrentState = STATE_ERROR;
                mTargetState = STATE_ERROR;
                if (mOnErrorListener != null) {
                    mOnErrorListener.onError(slot.player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
                }
            }
        }
    }

    /**
     * 原始频道地址为组播 (rtp/udp) 时返回其 Uri，供 IJK 直连 + IDR 对齐下载器使用
     */
    private static Uri getRawMulticastUri(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return null;
        }
        Uri uri;
        try {
            uri = Uri.parse(rawUrl);
        } catch (Throwable t) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme != null && (scheme.equalsIgnoreCase("rtp") || scheme.equalsIgnoreCase("udp"))) {
            return uri;
        }
        return null;
    }

    private static void releaseSlotDownloader(PlayerSlot slot) {
        if (slot == null || slot.pooledDownloader == null) {
            return;
        }
        StreamDownloaderPool.PooledStreamDownloader downloader = slot.pooledDownloader;
        slot.pooledDownloader = null;
        StreamDownloaderPool.getInstance().release(downloader);
    }

    private String buildCurrentCodecConfigSignature() {
        boolean hw = mSettings == null || mSettings.isMediaCodecEnabled();
        boolean hevc = mSettings == null || mSettings.isMediaCodecHevcEnabled();
        boolean opensl = mSettings != null && mSettings.isOpenSlEsEnabled();
        return "HW_" + (hw ? 1 : 0) + "_HEVC_" + (hevc ? 1 : 0) + "_SL_" + (opensl ? 1 : 0);
    }

    /**
     * 当用户在设置中切换播放器内核或修改硬解/软解开关时，
     * 立即清理或重置不匹配的旧实例，确保全部 3 个槽位均应用最新的硬解/软解与内核设置。
     */
    private void ensureAllSlotsMatchPlayerType(int desiredPlayerType) {
        String currentSig = buildCurrentCodecConfigSignature();
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null && slot.player != null) {
                if (slot.playerType != desiredPlayerType
                        || !TextUtils.equals(slot.codecConfigSignature, currentSig)) {
                    releaseSlotPlayerOnly(slot);
                    slot.resetMetadata();
                }
            }
        }
    }

    private void resetOrCreateSlotPlayer(PlayerSlot slot, String rawUrl) {
        int desiredPlayerType = mSettings.getPlayerType();
        String currentSig = buildCurrentCodecConfigSignature();
        boolean canReuseExisting = slot.player != null
                && slot.playerType == desiredPlayerType
                && slot.state != STATE_ERROR;
        if (slot.player instanceof TextureMediaPlayer && mCurrentRender != RENDER_TEXTURE_VIEW) {
            canReuseExisting = false;
        }
        if (canReuseExisting) {
            try {
                IMediaPlayer internal = unwrapPlayer(slot.player);
                internal.setSurface(null);
                // 与 releaseSlotPlayerOnly 同理：先停组播下载器再 reset，
                // 否则 reset 会死等阻塞在 readAt 的 ijk 读取线程（ANR）
                releaseSlotDownloader(slot);
                slot.player.reset();
                if (internal instanceof IjkMediaPlayer) {
                    configureIjkOptions((IjkMediaPlayer) internal, rawUrl);
                }
                slot.resetMetadata();
                slot.playerType = desiredPlayerType;
                slot.codecConfigSignature = currentSig;
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Slot #" + slot.slotIndex + " reset() failed, recreating player: " + t.getMessage());
            }
        }
        releaseSlotPlayerOnly(slot);
        slot.resetMetadata();
        slot.player = createConfiguredPlayer(desiredPlayerType, rawUrl);
        slot.playerType = desiredPlayerType;
        slot.codecConfigSignature = currentSig;
    }

    public IMediaPlayer createFallbackPlayer(int playerType) {
        return createConfiguredPlayer(playerType, mRawChannelUrl);
    }

    private IMediaPlayer createConfiguredPlayer(int playerType, String rawUrl) {
        IMediaPlayer mediaPlayer;
        switch (playerType) {
            case IptvSettings.PLAYER_IJK:
                try {
                    IjkMediaPlayer ijkPlayer = new IjkMediaPlayer();
                    IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_INFO);
                    configureIjkOptions(ijkPlayer, rawUrl);
                    mediaPlayer = ijkPlayer;
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to init IjkMediaPlayer, falling back to AndroidMediaPlayer", t);
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

    private void configureIjkOptions(IjkMediaPlayer ijkMediaPlayer, String rawUrl) {
        boolean useMediaCodec = mSettings == null || mSettings.isMediaCodecEnabled();
        boolean enableHevcHw = mSettings == null || mSettings.isMediaCodecHevcEnabled();
        boolean useOpenSlEs = mSettings != null && mSettings.isOpenSlEsEnabled();

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", enableHevcHw ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg2", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg4", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos",
                (useMediaCodec && enableHevcHw) ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate",
                (useMediaCodec || enableHevcHw) ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change",
                (useMediaCodec || enableHevcHw) ? 1 : 0);
        // mediacodec-sync=1：同步输入模式（旧版实测值）；上游改为 0 后 4K@50 输出掉到 25fps
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-sync", 1);

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", useOpenSlEs ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);
        // framedrop=2：直播流追同步时激进丢帧（旧版实测值）
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 2);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "fast", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
        // min-frames/max-buffer 恢复旧版实测值（5 / 2MB）；上游的 15 / 8MB 在 4K@50 下输出掉到 25fps
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 5);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 2 * 1024 * 1024L);

        // 探测窗口一律加大到 1.5MB/1.5s：Wi-Fi 组播加入初期丢包会损坏首个 IDR，
        // 探针太小 FFmpeg 可能探测不到视频参数 (unspecified size) → MTK 硬解以 0x0 配置失败
        // (err -1010) → ijk 静默降级软解，4K@50fps 软解帧率仅个位数。
        // 不能用 URL 后缀判断 4K（北京联通 4K 频道是 :3002/:8001 等非 :2000 端口），
        // 虽然下载器已做 IDR 对齐，探针留足余量兜住对齐前的裸流场景。
        // 代价是所有频道起播多等约 1 秒，由三实例离屏预加载抵消。
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1536 * 1024L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1500000L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fpsprobesize", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer+fastseek+discardcorrupt");
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8000000L);
        // 软解兜底时跳过全部循环滤波（旧实测值 48），硬解生效时此选项无影响
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
    }

    private void attachSlotListeners(final PlayerSlot slot, IMediaPlayer mp) {
        if (mp == null) {
            return;
        }
        mp.setOnPreparedListener(player -> {
            slot.prepareEndTime = System.currentTimeMillis();
            slot.state = STATE_PREPARED;
            slot.videoWidth = player.getVideoWidth();
            slot.videoHeight = player.getVideoHeight();
            slot.videoSarNum = player.getVideoSarNum();
            slot.videoSarDen = player.getVideoSarDen();

            if (slot != mActiveSlot) {
                // 离屏实例准备就绪：保持静音并在离屏 Surface 上开始播放
                slot.setMuted(true);
                try {
                    player.start();
                    slot.state = STATE_PLAYING;
                } catch (Throwable ignored) {
                }
                return;
            }

            mPrepareEndTime = slot.prepareEndTime;
            mCurrentState = STATE_PREPARED;
            slot.setMuted(false);
            checkAndStartHwAudioIfNeeded();
            checkHevcHardwareDecodeFailure(player);

            mVideoWidth = slot.videoWidth;
            mVideoHeight = slot.videoHeight;
            mVideoSarNum = slot.videoSarNum;
            mVideoSarDen = slot.videoSarDen;

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
        });

        mp.setOnVideoSizeChangedListener((player, width, height, sarNum, sarDen) -> {
            slot.videoWidth = player.getVideoWidth();
            slot.videoHeight = player.getVideoHeight();
            slot.videoSarNum = player.getVideoSarNum();
            slot.videoSarDen = player.getVideoSarDen();

            if (slot != mActiveSlot) {
                return;
            }

            mVideoWidth = slot.videoWidth;
            mVideoHeight = slot.videoHeight;
            mVideoSarNum = slot.videoSarNum;
            mVideoSarDen = slot.videoSarDen;
            if (mVideoWidth != 0 && mVideoHeight != 0) {
                if (mRenderView != null) {
                    mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                    mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
                    if (!mRenderView.shouldWaitForResize() || (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight)) {
                        if (mTargetState == STATE_PLAYING && !isPlaying()) {
                            start();
                        }
                    } else if (mTargetState == STATE_PLAYING && isInPlaybackState() && !isPlaying()) {
                        start();
                    }
                }
                requestLayout();
            }
        });

        mp.setOnCompletionListener(player -> {
            slot.state = STATE_PLAYBACK_COMPLETED;
            if (slot == mActiveSlot) {
                mCurrentState = STATE_PLAYBACK_COMPLETED;
                mTargetState = STATE_PLAYBACK_COMPLETED;
                if (mOnCompletionListener != null) {
                    mOnCompletionListener.onCompletion(mMediaPlayer);
                }
            }
        });

        mp.setOnInfoListener((player, arg1, arg2) -> {
            if (arg1 == IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED) {
                slot.videoRotationDegree = arg2;
            }
            if (slot != mActiveSlot) {
                return true;
            }
            if (mOnInfoListener != null) {
                mOnInfoListener.onInfo(mMediaPlayer, arg1, arg2);
            }
            if (arg1 == IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED) {
                mVideoRotationDegree = arg2;
                if (mRenderView != null) {
                    mRenderView.setVideoRotation(arg2);
                }
            }
            return true;
        });

        mp.setOnErrorListener((player, frameworkErr, implErr) -> {
            slot.state = STATE_ERROR;
            if (slot != mActiveSlot) {
                Log.d(TAG, "Off-screen slot #" + slot.slotIndex + " error ignored: " + frameworkErr + ", " + implErr);
                return true;
            }
            Log.e(TAG, "Playback error on active slot: " + frameworkErr + ", " + implErr);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            if (mOnErrorListener != null) {
                return mOnErrorListener.onError(mMediaPlayer, frameworkErr, implErr);
            }
            return true;
        });
    }

    private Uri resolvePlaybackUri(Uri sourceUri) {
        if (sourceUri == null) {
            return null;
        }
        String scheme = sourceUri.getScheme();
        if (scheme != null && (scheme.equalsIgnoreCase("rtp") || scheme.equalsIgnoreCase("udp"))) {
            if (mAppContext instanceof IptvApplication) {
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

    public static boolean isHlsUri(Uri uri) {
        return uri != null && isHlsUrl(uri.toString());
    }

    public static boolean isHlsUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".m3u8");
    }

    private void bindSurfaceHolder(IMediaPlayer mp, IRenderView.ISurfaceHolder holder) {
        if (mp == null) {
            return;
        }
        if (mp instanceof UTVPlayer) {
            UTVPlayer utvPlayer = (UTVPlayer) mp;
            if (holder == null) {
                utvPlayer.parkToPlaceholderSurface();
                return;
            }
            View renderUiView = mRenderView != null ? mRenderView.getView() : null;
            if (renderUiView instanceof android.view.SurfaceView) {
                utvPlayer.setVideoSurfaceView((android.view.SurfaceView) renderUiView);
            } else if (renderUiView instanceof android.view.TextureView) {
                utvPlayer.setVideoTextureView((android.view.TextureView) renderUiView);
            } else {
                holder.bindToMediaPlayer(utvPlayer);
            }
            return;
        }
        if (holder == null) {
            mp.setDisplay(null);
            return;
        }
        holder.bindToMediaPlayer(mp);
    }

    private void releaseSlotPlayerOnly(PlayerSlot slot) {
        if (slot == null) {
            return;
        }
        // 必须先停组播下载器：ijk shutdown 会等待其读取线程退出，
        // 而 readAt 阻塞在下载器环形缓冲上——先 reset/release 会造成死等（ANR）
        releaseSlotDownloader(slot);
        if (slot.player == null) {
            return;
        }
        IMediaPlayer mp = slot.player;
        slot.player = null;
        slot.playerType = -1;
        if (mp instanceof UTVPlayer) {
            // UTVPlayer 为全局单实例 ExoPlayer，切台或释放槽位时不销毁解码器，仅挂靠回 PlaceholderSurface
            ((UTVPlayer) mp).parkToPlaceholderSurface();
            return;
        }
        if (mp instanceof TextureMediaPlayer) {
            try {
                ((TextureMediaPlayer) mp).releaseSurfaceTexture();
            } catch (Throwable ignored) {
            }
        }
        try {
            mp.setDisplay(null);
            mp.setSurface(null);
        } catch (Throwable ignored) {
        }
        try {
            mp.reset();
        } catch (Throwable ignored) {
        }
        try {
            mp.release();
        } catch (Throwable ignored) {
        }
    }

    public void release(boolean cleartargetstate) {
        releaseAllSlots(cleartargetstate);
    }

    /**
     * 释放全部 3 个 Player 实例槽位及其离屏 Surface
     */
    public void releaseAllSlots(boolean cleartargetstate) {
        removeCallbacks(mStopOffscreenRunnable);
        mHwAudioCompanion.stop();
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null) {
                releaseSlotPlayerOnly(slot);
                slot.releaseOffscreenSurface();
                slot.resetMetadata();
            }
        }
        mMediaPlayer = null;
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
                if (mActiveSlot != null) {
                    mActiveSlot.state = STATE_PLAYING;
                }
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
                    if (mActiveSlot != null) {
                        mActiveSlot.state = STATE_PAUSED;
                    }
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

    public long getTcpSpeed() {
        if (mMediaPlayer instanceof UTVPlayer) {
            return ((UTVPlayer) mMediaPlayer).getTcpSpeedBytesPerSec();
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

    public float getVideoOutputFramesPerSecond() {
        if (mMediaPlayer instanceof UTVPlayer) {
            return ((UTVPlayer) mMediaPlayer).getVideoFrameRate();
        }
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

    public String getDecoderName() {
        int playerType = mSettings.getPlayerType();
        if (playerType == IptvSettings.PLAYER_UTV) {
            if (mUtvPlayer != null) {
                return mUtvPlayer.getDecoderSummaryLabel();
            }
            return "UTVPlayer (Exo池化硬解)";
        }
        String poolTag = isActivePlayerPromotedFromPool() ? "三实例离屏秒切" : "三实例池化";
        if (playerType == IptvSettings.PLAYER_ANDROID_MEDIA) {
            String baseName = "AndroidMediaPlayer (" + poolTag + "系统硬解)";
            String hwAudio = mHwAudioCompanion.getActiveCodecLabel();
            if (!TextUtils.isEmpty(hwAudio)) {
                return baseName + " / " + hwAudio;
            }
            return baseName;
        }

        boolean likely4K = !TextUtils.isEmpty(mRawChannelUrl) && mRawChannelUrl.endsWith(":2000");
        boolean hwEnabled = likely4K
                ? (mSettings.isMediaCodecEnabled() && mSettings.isMediaCodecHevcEnabled())
                : mSettings.isMediaCodecEnabled();
        String videoDecoder = hwEnabled
                ? ("MediaCodec (" + poolTag + "硬解)")
                : ("FFmpeg (" + poolTag + "软解)");
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

    public String getFormattedVideoTrackInfo() {
        if (!isInPlaybackState()) {
            return "";
        }
        String codec = "h264";
        int fps = Math.round(getVideoOutputFramesPerSecond());
        if (fps <= 0) {
            fps = (mVideoWidth >= 3840) ? 50 : 25;
        }
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof UTVPlayer) {
                com.google.android.exoplayer2.Format vf = ((UTVPlayer) mp).getCurrentVideoFormat();
                if (vf != null && !TextUtils.isEmpty(vf.sampleMimeType)) {
                    if (vf.sampleMimeType.contains("hevc")) {
                        codec = "hevc";
                    } else if (vf.sampleMimeType.contains("avc")) {
                        codec = "h264";
                    } else if (vf.sampleMimeType.contains("mpeg2")) {
                        codec = "mpeg2video";
                    }
                }
            } else if (mp instanceof IjkMediaPlayer) {
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

    public String getFormattedAudioTrackInfo() {
        if (!isInPlaybackState()) {
            return "";
        }
        String codec = "mp2";
        int sampleRate = 48000;
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof UTVPlayer) {
                com.google.android.exoplayer2.Format af = ((UTVPlayer) mp).getCurrentAudioFormat();
                if (af != null) {
                    if (af.sampleRate > 0) {
                        sampleRate = af.sampleRate;
                    }
                    if (!TextUtils.isEmpty(af.sampleMimeType)) {
                        if (af.sampleMimeType.contains("eac3")) {
                            codec = "eac3";
                        } else if (af.sampleMimeType.contains("ac3")) {
                            codec = "ac3";
                        } else if (af.sampleMimeType.contains("mp4a")) {
                            codec = "aac";
                        } else if (af.sampleMimeType.contains("mpeg")) {
                            codec = "mp2";
                        }
                    }
                }
            } else if (mp instanceof IjkMediaPlayer) {
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
        return unwrapPlayer(mMediaPlayer);
    }

    private static IMediaPlayer unwrapPlayer(IMediaPlayer mp) {
        if (mp instanceof TextureMediaPlayer) {
            return ((TextureMediaPlayer) mp).getInternalMediaPlayer();
        }
        return mp;
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

    public interface OnHevcSoftwareDecodeListener {
        void onHevcSoftwareDecodeDetected();
    }

    public void setOnHevcSoftwareDecodeListener(OnHevcSoftwareDecodeListener l) {
        mOnHevcSoftwareDecodeListener = l;
    }

    public void setForceFallbackPlayer(boolean force) {
        mForceFallbackPlayer = force;
    }

    private void checkAndStartHwAudioIfNeeded() {
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                IjkMediaPlayer ijk = (IjkMediaPlayer) mp;
                MediaInfo info = ijk.getMediaInfo();
                String audioDec = (info != null) ? info.mAudioDecoderImpl : null;
                String videoDec = (info != null) ? info.mVideoDecoderImpl : null;
                Log.i(TAG, "onPrepared IjkMediaPlayer audioDecoder=" + audioDec
                        + ", videoDecoder=" + videoDec);
                if (TextUtils.isEmpty(audioDec) || "unknown".equalsIgnoreCase(audioDec)) {
                    mHwAudioCompanion.start(mUri, mRawChannelUrl);
                } else {
                    mHwAudioCompanion.stop();
                }
            } else {
                mHwAudioCompanion.stop();
            }
        } catch (Throwable t) {
            Log.w(TAG, "checkAndStartHwAudioIfNeeded failed", t);
        }
    }

    /**
     * 检测 HEVC 硬件解码是否静默降级为软件解码：
     * 部分电视 (如索尼/联发科) 的 OMX HEVC 解码器 Surface 配置失败 (err -22/-1010) 时，
     * ijk 会无提示回退 avcodec 软解，4K@50fps 软解帧率仅个位数且无任何报错，
     * 必须在 onPrepared 后主动检查解码器实现并自救重试，最终仍失败才回调外部切换播放内核。
     */
    private void checkHevcHardwareDecodeFailure(IMediaPlayer mp) {
        if (mOnHevcSoftwareDecodeListener == null || mForceFallbackPlayer) {
            return;
        }
        if (mSettings.getPlayerType() != IptvSettings.PLAYER_IJK
                || !mSettings.isMediaCodecEnabled()
                || !mSettings.isMediaCodecHevcEnabled()) {
            return;
        }
        try {
            IMediaPlayer raw = unwrapIjkMediaPlayer();
            if (raw instanceof IjkMediaPlayer) {
                IjkMediaPlayer ijk = (IjkMediaPlayer) raw;
                int vdec = ijk.getVideoDecoder();
                if (isActiveStreamHevc() && vdec != IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC) {
                    if (mHevcFreshRetryCount < MAX_HEVC_FRESH_RETRIES) {
                        retryHevcHardwareDecodeWithFreshInstance();
                    } else {
                        Log.w(TAG, "HEVC hardware decode unavailable after " + mHevcFreshRetryCount
                                + " fresh retries (vdec=" + vdec + "), falling back to system player");
                        mOnHevcSoftwareDecodeListener.onHevcSoftwareDecodeDetected();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isActiveStreamHevc() {
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                Bundle bundle = ((IjkMediaPlayer) mp).getMediaMeta();
                IjkMediaMeta meta = IjkMediaMeta.parse(bundle);
                if (meta != null && meta.mStreams != null) {
                    for (IjkMediaMeta.IjkStreamMeta stream : meta.mStreams) {
                        if (stream != null && "video".equalsIgnoreCase(stream.mType)
                                && !TextUtils.isEmpty(stream.mCodecName)) {
                            return stream.mCodecName.toLowerCase(Locale.US).contains("hevc");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * HEVC 硬解静默降级后的自救重试：
     * 索尼/联发科 OMX.MTK.VIDEO.DECODER.HEVC 在旧实例尚未拆除完毕（Surface 断开、
     * 显存注销均为异步）时 configure 必失败 (setPortMode DynamicANWBuffer err -1010)，
     * 失败后 ijk 无提示降级 avcodec 软解。这里彻底销毁全部三实例槽位、等待旧 OMX 资源释放、
     * 再用全新实例重新初始化——干净环境下硬解必成；仍失败才回调外部走系统播放器兜底。
     */
    private void retryHevcHardwareDecodeWithFreshInstance() {
        mHevcFreshRetryCount++;
        Log.w(TAG, "HEVC hw decode fell back to software; fresh retry "
                + mHevcFreshRetryCount + "/" + MAX_HEVC_FRESH_RETRIES);
        releaseAllSlots(false);
        mTargetState = STATE_PLAYING;
        final long token = ++mHevcRetryToken;
        postDelayed(() -> {
            if (token != mHevcRetryToken || mForceFallbackPlayer) {
                return; // 期间用户已切台或兜底已触发
            }
            Log.i(TAG, "Fresh HEVC decode retry " + mHevcFreshRetryCount + ": re-opening stream");
            openVideo();
        }, 800);
    }

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
            if (mPendingPrevUri != null || mPendingNextUri != null) {
                syncOffscreenAdjacentSlots();
                scheduleStopOffscreenPlayers();
            }
        }

        @Override
        public void onSurfaceDestroyed(@NonNull IRenderView.ISurfaceHolder holder) {
            if (holder.getRenderView() != mRenderView) {
                return;
            }
            mSurfaceHolder = null;
            if (mMediaPlayer instanceof UTVPlayer) {
                // 外部 UI 的 SurfaceView 销毁时，将 UTVPlayer 挂靠在 PlaceholderSurface 上继续后台预解码首帧
                ((UTVPlayer) mMediaPlayer).parkToPlaceholderSurface();
            } else if (mMediaPlayer != null) {
                mMediaPlayer.setDisplay(null);
            }
        }
    };
}
