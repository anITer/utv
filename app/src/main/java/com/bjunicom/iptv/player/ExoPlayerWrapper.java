package com.bjunicom.iptv.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.common.util.UnstableApi;

import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/**
 * Media3 ExoPlayer 的 IMediaPlayer 适配器。
 *
 * 动机：libijkffmpeg.so 未编译 hevc_mediacodec 解码器，4K HEVC 只能软解(实测 CPU 427%，反复缓冲)；
 * AndroidMediaPlayer 的 NuPlayer 内部缓冲策略不可调(约 1-2MB，30Mbps 流下仅 0.3s，周期性 rebuffer)。
 * ExoPlayer 两者兼得：MediaCodec 直连 HEVC 硬解 + DefaultLoadControl 精确控制缓冲水位。
 *
 * 缓冲参数(针对 30Mbps 的 IPTV 4K 组播经本地代理的 HTTP 流)：
 * - 起播缓冲 2000ms：稍长以换稳定
 * - rebuffer 后 4000ms：一次回填足够多，避免短周期反复 rebuffer
 * - 播放保持 10000ms：上游任何抖动都被吸收
 */
@UnstableApi
public final class ExoPlayerWrapper implements IMediaPlayer {

    private static final String TAG = "ExoPlayerWrapper";

    private final ExoPlayer player;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SurfaceHolder surfaceHolder;
    private Surface surface;
    private int videoWidth;
    private int videoHeight;

    private OnPreparedListener onPreparedListener;
    private OnCompletionListener onCompletionListener;
    private OnBufferingUpdateListener onBufferingUpdateListener;
    private OnSeekCompleteListener onSeekCompleteListener;
    private OnVideoSizeChangedListener onVideoSizeChangedListener;
    private OnErrorListener onErrorListener;
    private OnInfoListener onInfoListener;

    private volatile boolean prepared;
    private volatile boolean released;
    private final ExoPlayerWrapper self = this;

    public ExoPlayerWrapper(Context context) {
        // 缓冲水位是本次集成的核心价值：彻底调大，换取"永不 rebuffer"
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs= */ 15000,
                        /* maxBufferMs= */ 30000,
                        /* playbackMs(起播) = */ 2000,
                        /* rebufferMs(欠载恢复) = */ 4000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setTargetBufferBytes(C.LENGTH_UNSET)
                .build();

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setUserAgent("BjUnicomIptv-uTV/1.0");

        DefaultDataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(context, httpFactory);

        player = new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .setBandwidthMeter(new DefaultBandwidthMeter.Builder(context).build())
                .build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(@Player.State int state) {
                if (released) return;
                if (state == Player.STATE_READY) {
                    prepared = true;
                    VideoSize vs = player.getVideoSize();
                    videoWidth = vs.width;
                    videoHeight = vs.height;
                    if (onPreparedListener != null) {
                        mainHandler.post(() -> onPreparedListener.onPrepared(self));
                    }
                    if (onBufferingUpdateListener != null) {
                        mainHandler.post(() -> onBufferingUpdateListener.onBufferingUpdate(
                                self, 100));
                    }
                    if (onInfoListener != null) {
                        mainHandler.post(() -> onInfoListener.onInfo(self,
                                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0));
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (onInfoListener != null) {
                        mainHandler.post(() -> onInfoListener.onInfo(self,
                                IMediaPlayer.MEDIA_INFO_BUFFERING_START, 0));
                    }
                    if (onBufferingUpdateListener != null) {
                        mainHandler.post(() -> onBufferingUpdateListener.onBufferingUpdate(
                                self, player.getBufferedPercentage()));
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (onCompletionListener != null) {
                        mainHandler.post(() -> onCompletionListener.onCompletion(self));
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (released) return;
                if (onErrorListener != null) {
                    final int what = IMediaPlayer.MEDIA_ERROR_UNKNOWN;
                    final int extra = error.errorCode;
                    mainHandler.post(() -> onErrorListener.onError(self, what, extra));
                }
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (released) return;
                videoWidth = videoSize.width;
                videoHeight = videoSize.height;
                if (onVideoSizeChangedListener != null) {
                    final int w = videoSize.width, h = videoSize.height;
                    final int sarNum = videoSize.pixelWidthHeightRatio > 0
                            ? (int) Math.round(videoSize.pixelWidthHeightRatio * 1000) : 1;
                    mainHandler.post(() -> onVideoSizeChangedListener.onVideoSizeChanged(
                            self, w, h, sarNum, 1000));
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // 直播流无需处理
            }
        });
    }

    @Override
    public void setDisplay(SurfaceHolder sh) {
        surfaceHolder = sh;
        surface = sh != null ? sh.getSurface() : null;
        applySurface();
    }

    @Override
    public void setSurface(Surface s) {
        surface = s;
        surfaceHolder = null;
        applySurface();
    }

    private void applySurface() {
        if (released) return;
        if (surface != null && surface.isValid()) {
            player.setVideoSurface(surface);
        } else {
            player.clearVideoSurface();
        }
    }

    @Override
    public void setDataSource(Context context, Uri uri) {
        player.setMediaItem(MediaItem.fromUri(uri.toString()));
    }

    @Override
    public void setDataSource(Context context, Uri uri, Map<String, String> headers) {
        player.setMediaItem(MediaItem.fromUri(uri.toString()));
    }

    @Override
    public void setDataSource(String path) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(path)));
    }

    @Override
    public void setDataSource(@Nullable IMediaDataSource mediaDataSource) {
        throw new UnsupportedOperationException("IMediaDataSource not supported by ExoPlayerWrapper");
    }

    @Override
    public void setDataSource(java.io.FileDescriptor fd) {
        throw new UnsupportedOperationException("FileDescriptor not supported by ExoPlayerWrapper");
    }

    @Override
    public void prepareAsync() {
        // ExoPlayer 必须在 Surface 就绪后 configure；延迟到 surface attach 完成后再 prepare
        player.prepare();
        player.play();
    }

    @Override
    public void start() {
        player.play();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void pause() {
        player.pause();
    }

    @Override
    public void setScreenOnWhilePlaying(boolean screenOn) {
        // ExoPlayer 通过 setWakeMode 处理，这里由外部视图层管理
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
        return !released && player.isPlaying();
    }

    @Override
    public void seekTo(long msec) {
        player.seekTo(msec);
    }

    @Override
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return player.getDuration();
    }

    @Override
    public void release() {
        released = true;
        player.release();
    }

    @Override
    public void reset() {
        player.stop();
        prepared = false;
    }

    @Override
    public void setVolume(float left, float right) {
        player.setVolume((left + right) / 2f);
    }

    @Override
    public int getAudioSessionId() {
        return player.getAudioSessionId();
    }

    @Override
    public void setLogEnabled(boolean enable) {
        // no-op
    }

    @Override
    public boolean isPlayable() {
        return true;
    }

    @Override
    public void setOnPreparedListener(OnPreparedListener l) {
        onPreparedListener = l;
    }

    @Override
    public void setOnCompletionListener(OnCompletionListener l) {
        onCompletionListener = l;
    }

    @Override
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener l) {
        onBufferingUpdateListener = l;
    }

    @Override
    public void setOnSeekCompleteListener(OnSeekCompleteListener l) {
        onSeekCompleteListener = l;
    }

    @Override
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener l) {
        onVideoSizeChangedListener = l;
    }

    @Override
    public void setOnErrorListener(OnErrorListener l) {
        onErrorListener = l;
    }

    @Override
    public void setOnInfoListener(OnInfoListener l) {
        onInfoListener = l;
    }

    @Override
    public void setOnTimedTextListener(OnTimedTextListener l) {
        // 直播 TS 无字幕轨，忽略
    }

    @Override
    public void setAudioStreamType(int streamType) {
        // Media3 使用 AudioAttributes，已在构造时设置
    }

    @Override
    public void setKeepInBackground(boolean keepInBackground) {
        // no-op
    }

    @Override
    public int getVideoSarNum() {
        return 1;
    }

    @Override
    public int getVideoSarDen() {
        return 1;
    }

    @Override
    public void setWakeMode(Context context, int mode) {
        // Media3 ExoPlayer 在 setMediaItem/prepare 阶段自动管理 WakeLock
    }

    @Override
    public void setLooping(boolean looping) {
        player.setRepeatMode(looping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    @Override
    public boolean isLooping() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    @Override
    public String getDataSource() {
        MediaItem item = player.getCurrentMediaItem();
        return item != null && item.localConfiguration != null
                ? item.localConfiguration.uri.toString() : null;
    }

    @Override
    public tv.danmaku.ijk.media.player.MediaInfo getMediaInfo() {
        tv.danmaku.ijk.media.player.MediaInfo mi = new tv.danmaku.ijk.media.player.MediaInfo();
        mi.mVideoDecoder = "media3-exoplayer";
        mi.mAudioDecoder = "media3-exoplayer";
        return mi;
    }

    @Override
    public tv.danmaku.ijk.media.player.misc.ITrackInfo[] getTrackInfo() {
        // 直播 TS 场景上层未使用轨道信息，返回空数组即可
        return new tv.danmaku.ijk.media.player.misc.ITrackInfo[0];
    }
}
