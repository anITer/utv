package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

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
 * 基于 bilibili/ijkplayer、ExoPlayer (UTVPlayer) 与 AndroidMediaPlayer 的单实例播放器视图
 *
 * 播放器缓冲与实例策略（IjkPlayer / ExoPlayer / MediaPlayer 三种模式完全一致）：
 * - 全局仅保留 1 个当前频道的播放器实例 (mActiveSlot / PLAYER_POOL_SIZE = 1)，避免多播放器实例带来的内存、解码器与组播带宽开销；
 * - 切台时直接在同一个播放器实例槽位上复用/切换频道流（如 UTVPlayer 直接触发 canReuseCodec() 复用硬件解码器，IjkPlayer 重置并加载新频道）。
 */
public class IjkVideoView extends FrameLayout {
    private static final String TAG = "IjkVideoView";

    public static final int PLAYER_POOL_SIZE = 1;
    public static final long OFFSCREEN_IDLE_STOP_MS = 3000L;

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
        public long generation;

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
                if (rawPlayer instanceof UTVPlayer) {
                    ((UTVPlayer) rawPlayer).parkToPlaceholderSurface();
                    return;
                }
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
    private PlayerSlot mBackgroundSlot = null;
    private final Runnable mStopOffscreenRunnable = this::stopOffscreenPlayers;

    private Uri mUri;
    private String mRawChannelUrl;
    private Uri mPendingBackgroundUri;
    private String mPendingBackgroundRawUrl;
    private Map<String, String> mHeaders;
    private boolean mSimulateBackgroundPlayerCreationFailure = false;
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

    private long mPrepareStartTime = 0;
    private long mPrepareEndTime = 0;
    private long mGeneration;
    private long mRequestSequence;
    private PlaybackMetrics mPlaybackMetrics;
    private PlaybackFailure mLastFailure = PlaybackFailure.UNKNOWN;
    private String mAudioCodecHint = "";
    private boolean mForceIjkForCurrentStream;
    private boolean mForceIjkVideoSoftware;
    private int mLastRequestedPlayerType = -1;
    private final Set<String> mAndroidAudioFallbackChannels = new HashSet<>();

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
        mBackgroundSlot = null;

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

    public PlayerSlot getBackgroundSlot() {
        return mBackgroundSlot;
    }

    public PlayerSlot getNextSlot() {
        return mBackgroundSlot;
    }

    public boolean isActivePlayerPromotedFromPool() {
        return mActiveSlot != null && mActiveSlot.promotedFromOffscreen;
    }

    public boolean isHwAudioCompanionRunning() {
        return mHwAudioCompanion.isRunning();
    }

    public UTVPlayer getUtvPlayer() {
        if (mActiveSlot != null && mActiveSlot.player instanceof UTVPlayer) {
            return (UTVPlayer) mActiveSlot.player;
        }
        if (mMediaPlayer instanceof UTVPlayer) {
            return (UTVPlayer) mMediaPlayer;
        }
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null && slot.player instanceof UTVPlayer) {
                return (UTVPlayer) slot.player;
            }
        }
        return null;
    }

    public boolean areOffscreenPlayersStopped() {
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null && slot != mActiveSlot && (slot.state != STATE_IDLE || slot.player != null)) {
                return false;
            }
        }
        return true;
    }

    public void setSimulateBackgroundPlayerCreationFailure(boolean simulateFailure) {
        this.mSimulateBackgroundPlayerCreationFailure = simulateFailure;
    }

    /**
     * 当用户停止切台满 3 秒（或初始播放未切台）时调用：
     * 彻底销毁除当前主屏实例 (mActiveSlot) 以外的后台播放器实例 (mBackgroundSlot)，
     * 断开其后台流连接并释放底层解码器与离屏 Surface 资源（IjkPlayer、MediaPlayer、UTVPlayer 统一销毁）。
     */
    public void stopOffscreenPlayers() {
        UTVPlayer utv = getUtvPlayer();
        if (utv != null) utv.stopBackgroundPreload();
        removeCallbacks(mStopOffscreenRunnable);
        mPendingBackgroundUri = null;
        mPendingBackgroundRawUrl = null;
        for (PlayerSlot slot : mPlayerSlots) {
            if (slot != null && slot != mActiveSlot) {
                destroyOffscreenSlot(slot);
            }
        }
        Log.i(TAG, "Destroyed off-screen background player after " + OFFSCREEN_IDLE_STOP_MS + "ms idle");
    }

    private void scheduleStopOffscreenPlayers() {
        removeCallbacks(mStopOffscreenRunnable);
        postDelayed(mStopOffscreenRunnable, OFFSCREEN_IDLE_STOP_MS);
    }

    /**
     * 彻底销毁指定后台槽位的播放器实例及其离屏 Surface（使 slot.player == null 且 slot.state == STATE_IDLE）
     */
    private void destroyOffscreenSlot(PlayerSlot slot) {
        if (slot == null) {
            return;
        }
        if (slot.player != null) {
            IMediaPlayer rawPlayer = unwrapPlayer(slot.player);
            if (!(rawPlayer instanceof UTVPlayer)) {
                // AndroidMediaPlayer 在 STATE_PREPARING 状态直接调 stop() 会抛 IllegalStateException，
                // 仅在已进入 PREPARED/PLAYING/PAUSED 时调用 stop()，随后由 releaseSlotPlayerOnly 统一 reset() + release()
                if (slot.state == STATE_PREPARED || slot.state == STATE_PLAYING || slot.state == STATE_PAUSED) {
                    try {
                        slot.player.stop();
                    } catch (Throwable ignored) {
                    }
                }
            }
            releaseSlotPlayerOnly(slot);
        }
        slot.releaseOffscreenSurface();
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

    public void setAudioCodecHint(String audioTrackInfo) {
        mAudioCodecHint = audioTrackInfo != null ? audioTrackInfo.toLowerCase(Locale.US) : "";
    }

    public PlaybackFailure getLastFailure() {
        return mLastFailure;
    }

    public long getFirstFrameCostMs() {
        return mPlaybackMetrics != null ? mPlaybackMetrics.getFirstVideoMs() : -1;
    }

    public String getStartupMetricsSummary() {
        return mPlaybackMetrics != null ? mPlaybackMetrics.summary() : "";
    }

    public Uri getPlaybackUri() {
        return mActiveSlot != null ? mActiveSlot.uri : mUri;
    }

    private int getEffectivePlayerType() {
        if (mForceIjkForCurrentStream) return IptvSettings.PLAYER_IJK;
        int requested = mSettings.getPlayerType();
        if (requested == IptvSettings.PLAYER_ANDROID_MEDIA
                && (mAndroidAudioFallbackChannels.contains(mRawChannelUrl)
                || (mAudioCodecHint.contains("mp2")
                && DecoderPolicy.getMp2HardwareDecoders(null).isEmpty()))) {
            return IptvSettings.PLAYER_IJK;
        }
        return requested;
    }

    private boolean isCurrentSlot(PlayerSlot slot, IMediaPlayer player, long generation) {
        return slot.player == player && slot.generation == generation;
    }

    private static void clearSlotListeners(IMediaPlayer player) {
        player.setOnPreparedListener(null);
        player.setOnVideoSizeChangedListener(null);
        player.setOnCompletionListener(null);
        player.setOnErrorListener(null);
        player.setOnInfoListener(null);
    }

    private boolean fallbackToIjk(PlayerSlot slot, boolean softwareVideo, boolean unsupportedAudio) {
        if (slot != mActiveSlot || mForceIjkForCurrentStream) return false;
        mForceIjkForCurrentStream = true;
        mForceIjkVideoSoftware = softwareVideo;
        if (unsupportedAudio && !TextUtils.isEmpty(slot.rawChannelUrl)) {
            mAndroidAudioFallbackChannels.add(slot.rawChannelUrl);
        }
        Log.w(TAG, "Recovering channel with Ijk: softwareVideo=" + softwareVideo
                + ", unsupportedAudio=" + unsupportedAudio);
        slot.state = STATE_ERROR;
        mCurrentState = STATE_PREPARING;
        loadSlotWithStream(slot, slot.uri, slot.rawChannelUrl, true);
        return true;
    }

    private static boolean isMp2Audio(IMediaPlayer player) {
        try {
            ITrackInfo[] tracks = player.getTrackInfo();
            if (tracks != null) {
                for (ITrackInfo track : tracks) {
                    if (track != null && track.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
                            && track.getFormat() != null
                            && Mp2SoftwareDecoder.isMp2MimeType(
                            track.getFormat().getString(IMediaFormat.KEY_MIME))) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Some system players expose no track format until preparation completes.
        }
        return false;
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
        boolean sameRequest = mActiveSlot != null
                && mActiveSlot.matchesStream(resolvePlaybackUri(uri), mRawChannelUrl)
                && mLastRequestedPlayerType == mSettings.getPlayerType()
                && mActiveSlot.state != STATE_ERROR;
        if (!sameRequest) {
            mForceIjkForCurrentStream = false;
            mForceIjkVideoSoftware = false;
            mPlaybackMetrics = new PlaybackMetrics(++mRequestSequence);
        }
        mLastRequestedPlayerType = mSettings.getPlayerType();
        mLastFailure = PlaybackFailure.UNKNOWN;
        mUri = uri;
        mHeaders = headers;
        openVideo();
        requestLayout();
        invalidate();
    }

    /**
     * 单播放器实例策略：不再创建任何后台播放器实例，仅保留当前频道的播放器实例 (mActiveSlot)
     */
    public void setBackgroundChannelPath(String nextPath, String nextRawUrl) {
        mPendingBackgroundUri = null;
        mPendingBackgroundRawUrl = null;
        UTVPlayer utv = getUtvPlayer();
        if (utv != null) {
            if (TextUtils.isEmpty(nextPath)) utv.stopBackgroundPreload();
            else utv.preloadChannel(Uri.parse(nextPath));
        }
    }

    public void setAdjacentChannelPaths(String prevPath,
                                        String prevRawUrl,
                                        String nextPath,
                                        String nextRawUrl) {
        setBackgroundChannelPath(null, null);
    }

    public void stopPlayback() {
        releaseAllSlots(true);
    }

    /**
     * 核心切台与单实例复用逻辑（IjkPlayer / ExoPlayer / MediaPlayer 统一策略）：
     * - 始终只保留 1 个当前频道播放器实例 (mActiveSlot)，不创建任何后台播放器实例；
     * - 切台时直接在 mActiveSlot 中复用当前播放器实例加载新频道流。
     */
    private void openVideo() {
        if (mUri == null) {
            return;
        }
        int playerType = getEffectivePlayerType();
        mHwAudioCompanion.stop();
        ensureAllSlotsMatchPlayerType(playerType);
        if (playerType != IptvSettings.PLAYER_UTV && mSurfaceHolder == null) {
            return;
        }

        AudioManager am = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        Uri playbackUri = resolvePlaybackUri(mUri);
        if (mActiveSlot == null) {
            mActiveSlot = mPlayerSlots[0];
        }

        if (mActiveSlot.matchesStream(playbackUri, mRawChannelUrl)
                && (mActiveSlot.state == STATE_PLAYING || mActiveSlot.state == STATE_PREPARED || mActiveSlot.state == STATE_PREPARING)) {
            bindSurfaceHolder(mActiveSlot.player, mSurfaceHolder);
            mActiveSlot.setMuted(false);
            if (mTargetState == STATE_PLAYING && mActiveSlot.state != STATE_PREPARING) {
                start();
            }
            return;
        }

        mPrepareStartTime = SystemClock.elapsedRealtime();
        mCurrentState = STATE_PREPARING;
        loadSlotWithStream(mActiveSlot, playbackUri, mRawChannelUrl, true);
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
     * 将命中的唯一后台实例 (mBackgroundSlot) 直接转正为主屏幕播放实例
     */
    private void promoteOffscreenSlotToActive(PlayerSlot promotedSlot) {
        PlayerSlot oldActive = mActiveSlot;
        if (oldActive != null && oldActive != promotedSlot && oldActive.player != null) {
            // 先将旧主屏实例切到其专属离屏 Surface 并静音，释放主屏 Surface 独占锁
            oldActive.setMuted(true);
            oldActive.bindToOffscreenSurface();
        }

        mActiveSlot = promotedSlot;
        mActiveSlot.promotedFromOffscreen = true;
        if (mActiveSlot.player instanceof UTVPlayer) {
            ((UTVPlayer) mActiveSlot.player).setPromotedFromPreload(true);
        }
        mMediaPlayer = mActiveSlot.player;
        mBackgroundSlot = oldActive;
        rebalanceBackgroundPointer();

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
            mPrepareEndTime = SystemClock.elapsedRealtime();
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
            mPrepareStartTime = SystemClock.elapsedRealtime();
            mCurrentState = STATE_PREPARING;
            loadSlotWithStream(mActiveSlot, resolvePlaybackUri(mUri), mRawChannelUrl, true);
            mActiveSlot.promotedFromOffscreen = true;
        } else {
            mCurrentState = STATE_PREPARING;
        }
    }

    private PlayerSlot findOtherSlot(PlayerSlot slot) {
        for (PlayerSlot candidate : mPlayerSlots) {
            if (candidate != slot) {
                return candidate;
            }
        }
        return slot;
    }

    private void rebalanceBackgroundPointer() {
        if (mBackgroundSlot == null || mBackgroundSlot == mActiveSlot) {
            mBackgroundSlot = findOtherSlot(mActiveSlot);
        }
    }

    /**
     * 同步驱动唯一后台槽位 (mBackgroundSlot) 在离屏状态静音预播放当前方向上的下一个频道
     */
    private void syncBackgroundSlot() {
        rebalanceBackgroundPointer();
        if (mBackgroundSlot == null) {
            return;
        }
        if (mPendingBackgroundUri == null
                || mPendingBackgroundUri.equals(mActiveSlot != null ? mActiveSlot.uri : null)) {
            destroyOffscreenSlot(mBackgroundSlot);
            return;
        }
        if (!mBackgroundSlot.matchesStream(mPendingBackgroundUri, mPendingBackgroundRawUrl)) {
            loadSlotWithStream(mBackgroundSlot, mPendingBackgroundUri, mPendingBackgroundRawUrl, false);
        } else {
            mBackgroundSlot.setMuted(true);
            mBackgroundSlot.bindToOffscreenSurface();
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
        slot.generation = ++mGeneration;
        try {
            resetOrCreateSlotPlayer(slot, rawUrl, isMainScreen);
            if (slot.player == null) {
                throw new IllegalStateException("Player creation returned null for slot #" + slot.slotIndex);
            }
        } catch (Throwable createEx) {
            Log.w(TAG, "Unable to create player in slot #" + slot.slotIndex + ": " + playbackUri, createEx);
            if (isMainScreen && slot == mActiveSlot) {
                slot.state = STATE_ERROR;
                mCurrentState = STATE_ERROR;
                mTargetState = STATE_ERROR;
                mLastFailure = PlaybackFailure.UNKNOWN;
                if (mOnErrorListener != null) {
                    mOnErrorListener.onError(slot.player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
                }
            } else {
                // 后台播放器创建失败时，彻底清理该后台槽位，绝不影响当前频道播放器的正常工作
                destroyOffscreenSlot(slot);
            }
            return;
        }

        slot.uri = playbackUri;
        slot.rawChannelUrl = rawUrl;
        slot.state = STATE_PREPARING;
        slot.prepareStartTime = SystemClock.elapsedRealtime();
        slot.prepareEndTime = 0L;
        slot.promotedFromOffscreen = false;
        try {
            IMediaPlayer mp = slot.player;
            if (mp instanceof UTVPlayer) {
                ((UTVPlayer) mp).setRawChannelUrl(rawUrl);
                ((UTVPlayer) mp).setPromotedFromPreload(false);
            }
            attachSlotListeners(slot, mp);
            mp.setDataSource(mAppContext, playbackUri, mHeaders);

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
                mLastFailure = PlaybackFailure.fromException(ex);
                if (mOnErrorListener != null) {
                    mOnErrorListener.onError(slot.player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
                }
            }
        }
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

    private void resetOrCreateSlotPlayer(PlayerSlot slot, String rawUrl, boolean isMainScreen) {
        if (!isMainScreen && mSimulateBackgroundPlayerCreationFailure) {
            throw new IllegalStateException("Simulated background player creation failure");
        }
        int desiredPlayerType = getEffectivePlayerType();
        String currentSig = buildCurrentCodecConfigSignature();
        boolean canReuseExisting = slot.player != null
                && slot.playerType == desiredPlayerType
                && slot.state != STATE_ERROR;
        if (slot.player instanceof TextureMediaPlayer && mCurrentRender != RENDER_TEXTURE_VIEW) {
            canReuseExisting = false;
        }
        if (canReuseExisting) {
            try {
                clearSlotListeners(slot.player);
                IMediaPlayer internal = unwrapPlayer(slot.player);
                if (internal instanceof UTVPlayer) {
                    ((UTVPlayer) internal).prepareForSlotReuse();
                } else {
                    try {
                        slot.player.setVolume(0f, 0f);
                    } catch (Throwable ignored) {
                    }
                    if (slot.state == STATE_PREPARED || slot.state == STATE_PLAYING || slot.state == STATE_PAUSED) {
                        try {
                            slot.player.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                    slot.player.reset();
                    if (internal instanceof IjkMediaPlayer) {
                        configureIjkOptions((IjkMediaPlayer) internal, rawUrl);
                    }
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
        IMediaPlayer actual = unwrapPlayer(slot.player);
        slot.playerType = actual instanceof AndroidMediaPlayer ? IptvSettings.PLAYER_ANDROID_MEDIA
                : desiredPlayerType;
        slot.codecConfigSignature = currentSig;
    }

    public IMediaPlayer createFallbackPlayer(int playerType) {
        return createConfiguredPlayer(playerType, mRawChannelUrl);
    }

    protected IMediaPlayer createConfiguredPlayer(int playerType, String rawUrl) {
        IMediaPlayer mediaPlayer;
        switch (playerType) {
            case IptvSettings.PLAYER_UTV:
                return new UTVPlayer(mAppContext);
            case IptvSettings.PLAYER_IJK:
                try {
                    IjkMediaPlayer ijkPlayer = new IjkMediaPlayer();
                    IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_INFO);
                    configureIjkOptions(ijkPlayer, rawUrl);
                    mediaPlayer = ijkPlayer;
                } catch (Throwable t) {
                    if (mForceIjkForCurrentStream || getEffectivePlayerType() != mSettings.getPlayerType()) {
                        throw new IllegalStateException("Ijk decoder fallback is unavailable", t);
                    }
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
        boolean useMediaCodec = !mForceIjkVideoSoftware
                && (mSettings == null || mSettings.isMediaCodecEnabled());
        boolean enableHevcHw = useMediaCodec
                && (mSettings == null || mSettings.isMediaCodecHevcEnabled());
        boolean useOpenSlEs = mSettings != null && mSettings.isOpenSlEsEnabled();
        boolean likely4K = !TextUtils.isEmpty(rawUrl) && rawUrl.endsWith(":2000");

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
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-sync", 0);

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", useOpenSlEs ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "fast", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", likely4K ? 15 : 5);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size",
                likely4K ? (8 * 1024 * 1024L) : (2 * 1024 * 1024L));

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",
                likely4K ? (1536 * 1024L) : (512 * 1024L));
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration",
                likely4K ? 800000L : 400000L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fpsprobesize", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek+discardcorrupt");
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8000000L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter",
                (useMediaCodec || enableHevcHw) ? 0 : 48);
    }

    private void attachSlotListeners(final PlayerSlot slot, IMediaPlayer mp) {
        if (mp == null) {
            return;
        }
        final long generation = slot.generation;
        mp.setOnPreparedListener(player -> {
            if (!isCurrentSlot(slot, mp, generation)) return;
            slot.prepareEndTime = SystemClock.elapsedRealtime();
            if (mPlaybackMetrics != null) mPlaybackMetrics.prepared();
            if (unwrapPlayer(mp) instanceof AndroidMediaPlayer
                    && isMp2Audio(mp) && DecoderPolicy.getMp2HardwareDecoders(null).isEmpty()) {
                fallbackToIjk(slot, false, true);
                return;
            }
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
            if (!isCurrentSlot(slot, mp, generation)) return;
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
            if (!isCurrentSlot(slot, mp, generation)) return;
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
            if (!isCurrentSlot(slot, mp, generation)) return true;
            if (arg1 == 1005 && unwrapPlayer(mp) instanceof AndroidMediaPlayer) {
                // Android's MEDIA_INFO_UNSUPPORTED_AUDIO may accompany otherwise valid video.
                fallbackToIjk(slot, false, true);
                return true;
            }
            if (mPlaybackMetrics != null) {
                mPlaybackMetrics.onInfo(arg1);
                if (arg1 == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        || arg1 == IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START
                        || arg1 == PlaybackMetrics.INFO_AUDIO_RENDERING_START) {
                    Log.i(TAG, "Startup " + mPlaybackMetrics.summary());
                }
            }
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
            if (!isCurrentSlot(slot, mp, generation)) return true;
            IMediaPlayer actual = unwrapPlayer(mp);
            mLastFailure = actual instanceof UTVPlayer
                    ? ((UTVPlayer) actual).getLastFailure()
                    : PlaybackFailure.fromLegacy(frameworkErr, implErr);
            if (actual instanceof AndroidMediaPlayer && mLastFailure == PlaybackFailure.FORMAT
                    && (mAudioCodecHint.contains("mp2") || isMp2Audio(mp))) {
                return fallbackToIjk(slot, false, true);
            }
            if (actual instanceof UTVPlayer && mLastFailure == PlaybackFailure.VIDEO_DECODER) {
                return fallbackToIjk(slot, true, false);
            }
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
        if (slot == null || slot.player == null) {
            return;
        }
        IMediaPlayer mp = slot.player;
        slot.generation = ++mGeneration;
        int prevState = slot.state;
        slot.player = null;
        slot.playerType = -1;
        try {
            mp.setOnPreparedListener(null);
            mp.setOnVideoSizeChangedListener(null);
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            mp.setOnInfoListener(null);
        } catch (Throwable ignored) {
        }
        if (mp instanceof UTVPlayer) {
            // 切换播放器类型或释放槽位时，立即停止并销毁该槽位的 UTVPlayer (ExoPlayer) 实例
            ((UTVPlayer) mp).releasePlayer();
            return;
        }
        try {
            mp.setVolume(0f, 0f);
        } catch (Throwable ignored) {
        }
        if (prevState == STATE_PREPARED || prevState == STATE_PLAYING || prevState == STATE_PAUSED) {
            try {
                mp.stop();
            } catch (Throwable ignored) {
            }
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
        mHwAudioCompanion.stop();
        UTVPlayer utv = getUtvPlayer();
        if (utv != null) utv.stopBackgroundPreload();
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
        int playerType = mActiveSlot != null && mActiveSlot.player != null
                ? mActiveSlot.playerType : getEffectivePlayerType();
        if (playerType == IptvSettings.PLAYER_UTV) {
            UTVPlayer utvPlayer = getUtvPlayer();
            if (utvPlayer != null) {
                return utvPlayer.getDecoderSummaryLabel();
            }
            return "UTVPlayer (Exo单实例硬解)";
        }
        String poolTag = "单实例";
        if (playerType == IptvSettings.PLAYER_ANDROID_MEDIA) {
            return "AndroidMediaPlayer (" + poolTag + "系统解码)";
        }

        boolean likely4K = !TextUtils.isEmpty(mRawChannelUrl) && mRawChannelUrl.endsWith(":2000");
        boolean hwEnabled = !mForceIjkVideoSoftware && (likely4K
                ? (mSettings.isMediaCodecEnabled() && mSettings.isMediaCodecHevcEnabled())
                : mSettings.isMediaCodecEnabled());
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
        String codec = "";
        int sampleRate = 0;
        int channels = 0;
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof UTVPlayer) {
                com.google.android.exoplayer2.Format af = ((UTVPlayer) mp).getCurrentAudioFormat();
                if (af != null) {
                    channels = af.channelCount;
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
                        } else if (Mp2SoftwareDecoder.isMp2MimeType(af.sampleMimeType)) {
                            codec = "mp2";
                        } else if ("audio/mpeg".equals(af.sampleMimeType)) {
                            codec = "mp3";
                        } else {
                            codec = af.sampleMimeType.replace("audio/", "");
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
                            channels = Long.bitCount(stream.mChannelLayout);
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
                                String c = fmt.getString(IMediaFormat.KEY_MIME);
                                if (Mp2SoftwareDecoder.isMp2MimeType(c)) c = "mp2";
                                else if ("audio/mpeg".equals(c)) c = "mp3";
                                else if (c != null) c = c.replace("audio/", "");
                                if (!TextUtils.isEmpty(c)) {
                                    codec = c.toLowerCase(Locale.US);
                                }
                                channels = fmt.getInteger("channel-count");
                                int sr = fmt.getInteger("sample-rate");
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
        if (TextUtils.isEmpty(codec)) return "";
        return "#1 " + codec + (channels > 0 ? "," + channels + "ch" : "")
                + (sampleRate > 0 ? "," + sampleRate / 1000 + "khz" : "");
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

    public void checkAndStartHwAudioIfNeeded() {
        try {
            IMediaPlayer mp = unwrapIjkMediaPlayer();
            if (mp instanceof IjkMediaPlayer) {
                IjkMediaPlayer ijk = (IjkMediaPlayer) mp;
                MediaInfo info = ijk.getMediaInfo();
                String audioDec = (info != null) ? info.mAudioDecoderImpl : null;
                String videoDec = (info != null) ? info.mVideoDecoderImpl : null;
                Log.i(TAG, "onPrepared IjkMediaPlayer audioDecoder=" + audioDec
                        + ", videoDecoder=" + videoDec);
                String audioCodec = "";
                IjkMediaMeta meta = IjkMediaMeta.parse(ijk.getMediaMeta());
                if (meta != null && meta.mStreams != null) {
                    for (IjkMediaMeta.IjkStreamMeta stream : meta.mStreams) {
                        if (stream != null && "audio".equals(stream.mType)) {
                            audioCodec = stream.mCodecName;
                            break;
                        }
                    }
                }
                if (("ac3".equalsIgnoreCase(audioCodec) || "eac3".equalsIgnoreCase(audioCodec))
                        && (TextUtils.isEmpty(audioDec) || "unknown".equalsIgnoreCase(audioDec))) {
                    // 仅当 IjkMediaPlayer 自身无对应音频解码器（如 AC-3/E-AC-3）时才启动伴音，并将主播放器静音防重音
                    try {
                        ijk.setVolume(0f, 0f);
                    } catch (Throwable ignored) {
                    }
                    mHwAudioCompanion.start(mUri, mRawChannelUrl);
                } else {
                    mHwAudioCompanion.stop();
                }
            } else {
                // AndroidMediaPlayer (系统 MediaPlayer) 与 UTVPlayer (ExoPlayer) 均由自身内置音频渲染管线播放音频，
                // 严禁再启动 TsHardwareAudioCompanion，避免出现两个音频播放器同时发声（双声道重音/回声）
                mHwAudioCompanion.stop();
            }
        } catch (Throwable t) {
            Log.w(TAG, "checkAndStartHwAudioIfNeeded failed", t);
        }
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
            if (mPendingBackgroundUri != null) {
                syncBackgroundSlot();
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
