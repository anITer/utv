package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.ISurfaceTextureHolder;
import tv.danmaku.ijk.media.player.ISurfaceTextureHost;

/**
 * 基于 TextureView 的视频渲染器 (参考 bilibili/ijkplayer TextureRenderView)
 */
public class TextureRenderView extends TextureView implements IRenderView {
    private MeasureHelper mMeasureHelper;
    private SurfaceCallback mSurfaceCallback;

    public TextureRenderView(Context context) {
        super(context);
        initView();
    }

    public TextureRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public TextureRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView();
    }

    private void initView() {
        mMeasureHelper = new MeasureHelper(this);
        mSurfaceCallback = new SurfaceCallback(this);
        setSurfaceTextureListener(mSurfaceCallback);
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public boolean shouldWaitForResize() {
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        mSurfaceCallback.willDetachFromWindow();
        super.onDetachedFromWindow();
        mSurfaceCallback.didDetachFromWindow();
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            requestLayout();
        }
    }

    @Override
    public void setVideoSampleAspectRatio(int videoSarNum, int videoSarDen) {
        if (videoSarNum > 0 && videoSarDen > 0) {
            mMeasureHelper.setVideoSampleAspectRatio(videoSarNum, videoSarDen);
            requestLayout();
        }
    }

    @Override
    public void setVideoRotation(int degree) {
        mMeasureHelper.setVideoRotation(degree);
        setRotation(degree);
    }

    @Override
    public void setAspectRatio(int aspectRatio) {
        mMeasureHelper.setAspectRatio(aspectRatio);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(mMeasureHelper.getMeasuredWidth(), mMeasureHelper.getMeasuredHeight());
    }

    public IRenderView.ISurfaceHolder getSurfaceHolder() {
        return new InternalSurfaceHolder(this, mSurfaceCallback.mSurfaceTexture);
    }

    @Override
    public void addRenderCallback(@NonNull IRenderCallback callback) {
        mSurfaceCallback.addRenderCallback(callback);
    }

    @Override
    public void removeRenderCallback(@NonNull IRenderCallback callback) {
        mSurfaceCallback.removeRenderCallback(callback);
    }

    private static final class InternalSurfaceHolder implements IRenderView.ISurfaceHolder {
        private final TextureRenderView mTextureView;
        private final SurfaceTexture mSurfaceTexture;

        InternalSurfaceHolder(@NonNull TextureRenderView textureView, @Nullable SurfaceTexture surfaceTexture) {
            mTextureView = textureView;
            mSurfaceTexture = surfaceTexture;
        }

        @Override
        public void bindToMediaPlayer(IMediaPlayer mp) {
            if (mp == null) return;
            if (mp instanceof ISurfaceTextureHolder) {
                ISurfaceTextureHolder textureHolder = (ISurfaceTextureHolder) mp;
                mTextureView.mSurfaceCallback.setOwnSurfaceTexture(false);
                SurfaceTexture surfaceTexture = textureHolder.getSurfaceTexture();
                if (surfaceTexture != null) {
                    mTextureView.setSurfaceTexture(surfaceTexture);
                } else {
                    textureHolder.setSurfaceTexture(mSurfaceTexture);
                    textureHolder.setSurfaceTextureHost(mTextureView.mSurfaceCallback);
                }
            } else {
                mp.setSurface(openSurface());
            }
        }

        @NonNull
        @Override
        public IRenderView getRenderView() {
            return mTextureView;
        }

        @Nullable
        @Override
        public SurfaceHolder getSurfaceHolder() {
            return null;
        }

        @Nullable
        @Override
        public SurfaceTexture getSurfaceTexture() {
            return mSurfaceTexture;
        }

        @Nullable
        @Override
        public Surface openSurface() {
            return mSurfaceTexture != null ? new Surface(mSurfaceTexture) : null;
        }
    }

    private static final class SurfaceCallback implements TextureView.SurfaceTextureListener, ISurfaceTextureHost {
        private SurfaceTexture mSurfaceTexture;
        private boolean mIsFormatChanged;
        private int mWidth;
        private int mHeight;
        private boolean mOwnSurfaceTexture = true;
        private boolean mWillDetachFromWindow = false;
        private boolean mDidDetachFromWindow = false;

        private final WeakReference<TextureRenderView> mWeakRenderView;
        private final Map<IRenderCallback, Object> mRenderCallbackMap = new ConcurrentHashMap<>();

        SurfaceCallback(@NonNull TextureRenderView renderView) {
            mWeakRenderView = new WeakReference<>(renderView);
        }

        void setOwnSurfaceTexture(boolean ownSurfaceTexture) {
            mOwnSurfaceTexture = ownSurfaceTexture;
        }

        void addRenderCallback(@NonNull IRenderCallback callback) {
            mRenderCallbackMap.put(callback, callback);
            ISurfaceHolder surfaceHolder = null;
            TextureRenderView view = mWeakRenderView.get();
            if (mSurfaceTexture != null && view != null) {
                surfaceHolder = new InternalSurfaceHolder(view, mSurfaceTexture);
                callback.onSurfaceCreated(surfaceHolder, mWidth, mHeight);
            }
            if (mIsFormatChanged && view != null) {
                if (surfaceHolder == null) {
                    surfaceHolder = new InternalSurfaceHolder(view, mSurfaceTexture);
                }
                callback.onSurfaceChanged(surfaceHolder, 0, mWidth, mHeight);
            }
        }

        void removeRenderCallback(@NonNull IRenderCallback callback) {
            mRenderCallbackMap.remove(callback);
        }

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            mSurfaceTexture = surface;
            mIsFormatChanged = false;
            mWidth = 0;
            mHeight = 0;
            TextureRenderView view = mWeakRenderView.get();
            if (view == null) return;
            ISurfaceHolder surfaceHolder = new InternalSurfaceHolder(view, surface);
            for (IRenderCallback cb : mRenderCallbackMap.keySet()) {
                cb.onSurfaceCreated(surfaceHolder, 0, 0);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            mSurfaceTexture = surface;
            mIsFormatChanged = true;
            mWidth = width;
            mHeight = height;
            TextureRenderView view = mWeakRenderView.get();
            if (view == null) return;
            ISurfaceHolder surfaceHolder = new InternalSurfaceHolder(view, surface);
            for (IRenderCallback cb : mRenderCallbackMap.keySet()) {
                cb.onSurfaceChanged(surfaceHolder, 0, width, height);
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            mSurfaceTexture = surface;
            mIsFormatChanged = false;
            mWidth = 0;
            mHeight = 0;
            TextureRenderView view = mWeakRenderView.get();
            if (view != null) {
                ISurfaceHolder surfaceHolder = new InternalSurfaceHolder(view, surface);
                for (IRenderCallback cb : mRenderCallbackMap.keySet()) {
                    cb.onSurfaceDestroyed(surfaceHolder);
                }
            }
            return mOwnSurfaceTexture;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }

        @Override
        public void releaseSurfaceTexture(SurfaceTexture surfaceTexture) {
            if (surfaceTexture == null) return;
            if (mDidDetachFromWindow) {
                if (surfaceTexture != mSurfaceTexture || !mOwnSurfaceTexture) {
                    surfaceTexture.release();
                }
            } else if (mWillDetachFromWindow) {
                if (surfaceTexture != mSurfaceTexture) {
                    surfaceTexture.release();
                } else if (!mOwnSurfaceTexture) {
                    setOwnSurfaceTexture(true);
                }
            } else {
                if (surfaceTexture != mSurfaceTexture) {
                    surfaceTexture.release();
                } else if (!mOwnSurfaceTexture) {
                    setOwnSurfaceTexture(true);
                }
            }
        }

        void willDetachFromWindow() {
            mWillDetachFromWindow = true;
        }

        void didDetachFromWindow() {
            mDidDetachFromWindow = true;
        }
    }
}
