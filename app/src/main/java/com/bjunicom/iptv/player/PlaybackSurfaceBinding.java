package com.bjunicom.iptv.player;

import android.graphics.SurfaceTexture;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.TextureMediaPlayer;

/** Owns offscreen surfaces and isolates the binding rules of each playback core. */
final class PlaybackSurfaceBinding {
    private static final String TAG = "PlaybackSurfaceBinding";
    private SurfaceTexture offscreenTexture;
    private Surface offscreenSurface;

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
            Log.w(TAG, "Failed to create offscreen surface: " + t.getMessage());
        }
        return offscreenSurface;
    }

    void park(IMediaPlayer player) {
        if (player == null) {
            return;
        }
        try {
            IMediaPlayer rawPlayer = unwrap(player);
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

    static void bind(IMediaPlayer mp, IRenderView.ISurfaceHolder holder, IRenderView renderView) {
        if (mp == null) {
            return;
        }
        if (mp instanceof UTVPlayer) {
            UTVPlayer utvPlayer = (UTVPlayer) mp;
            if (holder == null) {
                utvPlayer.parkToPlaceholderSurface();
                return;
            }
            View renderUiView = renderView != null ? renderView.getView() : null;
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

    static IMediaPlayer unwrap(IMediaPlayer mp) {
        if (mp instanceof TextureMediaPlayer) {
            return ((TextureMediaPlayer) mp).getInternalMediaPlayer();
        }
        return mp;
    }
}
