package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.ISurfaceTextureHolder;

/**
 * 基于 SurfaceView 的视频渲染器 (参考 bilibili/ijkplayer SurfaceRenderView)
 */
public class SurfaceRenderView extends SurfaceView implements IRenderView {
    private MeasureHelper mMeasureHelper;
    private SurfaceCallback mSurfaceCallback;

    public SurfaceRenderView(Context context) {
        super(context);
        initView();
    }

    public SurfaceRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public SurfaceRenderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView();
    }

    private void initView() {
        mMeasureHelper = new MeasureHelper(this);
        mSurfaceCallback = new SurfaceCallback(this);
        getHolder().addCallback(mSurfaceCallback);
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public boolean shouldWaitForResize() {
        return true;
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            getHolder().setFixedSize(videoWidth, videoHeight);
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

    @Override
    public void addRenderCallback(@NonNull IRenderCallback callback) {
        mSurfaceCallback.addRenderCallback(callback);
    }

    @Override
    public void removeRenderCallback(@NonNull IRenderCallback callback) {
        mSurfaceCallback.removeRenderCallback(callback);
    }

    private static final class InternalSurfaceHolder implements IRenderView.ISurfaceHolder {
        private final SurfaceRenderView mSurfaceView;
        private final SurfaceHolder mSurfaceHolder;

        InternalSurfaceHolder(@NonNull SurfaceRenderView surfaceView, @Nullable SurfaceHolder surfaceHolder) {
            mSurfaceView = surfaceView;
            mSurfaceHolder = surfaceHolder;
        }

        @Override
        public void bindToMediaPlayer(IMediaPlayer mp) {
            if (mp != null) {
                if (mp instanceof ISurfaceTextureHolder) {
                    ((ISurfaceTextureHolder) mp).setSurfaceTexture(null);
                }
                mp.setDisplay(mSurfaceHolder);
            }
        }

        @NonNull
        @Override
        public IRenderView getRenderView() {
            return mSurfaceView;
        }

        @Nullable
        @Override
        public SurfaceHolder getSurfaceHolder() {
            return mSurfaceHolder;
        }

        @Nullable
        @Override
        public SurfaceTexture getSurfaceTexture() {
            return null;
        }

        @Nullable
        @Override
        public Surface openSurface() {
            return mSurfaceHolder != null ? mSurfaceHolder.getSurface() : null;
        }
    }

    private static final class SurfaceCallback implements SurfaceHolder.Callback {
        private SurfaceHolder mSurfaceHolder;
        private boolean mIsFormatChanged;
        private int mFormat;
        private int mWidth;
        private int mHeight;

        private final WeakReference<SurfaceRenderView> mWeakSurfaceView;
        private final Map<IRenderCallback, Object> mRenderCallbackMap = new ConcurrentHashMap<>();

        SurfaceCallback(@NonNull SurfaceRenderView surfaceView) {
            mWeakSurfaceView = new WeakReference<>(surfaceView);
        }

        void addRenderCallback(@NonNull IRenderCallback callback) {
            mRenderCallbackMap.put(callback, callback);
            ISurfaceHolder surfaceHolder = null;
            SurfaceRenderView view = mWeakSurfaceView.get();
            if (mSurfaceHolder != null && view != null) {
                surfaceHolder = new InternalSurfaceHolder(view, mSurfaceHolder);
                callback.onSurfaceCreated(surfaceHolder, mWidth, mHeight);
            }
            if (mIsFormatChanged && view != null) {
                if (surfaceHolder == null) {
                    surfaceHolder = new InternalSurfaceHolder(view, mSurfaceHolder);
                }
                callback.onSurfaceChanged(surfaceHolder, mFormat, mWidth, mHeight);
            }
        }

        void removeRenderCallback(@NonNull IRenderCallback callback) {
            mRenderCallbackMap.remove(callback);
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            mSurfaceHolder = holder;
            mIsFormatChanged = false;
            mFormat = 0;
            mWidth = 0;
            mHeight = 0;
            SurfaceRenderView view = mWeakSurfaceView.get();
            if (view == null) return;
            ISurfaceHolder surfaceHolder = new InternalSurfaceHolder(view, mSurfaceHolder);
            for (IRenderCallback cb : mRenderCallbackMap.keySet()) {
                cb.onSurfaceCreated(surfaceHolder, 0, 0);
            }
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            mSurfaceHolder = null;
            mIsFormatChanged = false;
            mFormat = 0;
            mWidth = 0;
            mHeight = 0;
            SurfaceRenderView view = mWeakSurfaceView.get();
            if (view == null) return;
            ISurfaceHolder surfaceHolder = new InternalSurfaceHolder(view, null);
            for (IRenderCallback cb : mRenderCallbackMap.keySet()) {
                cb.onSurfaceDestroyed(surfaceHolder);
            }
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            mSurfaceHolder = holder;
            mIsFormatChanged = true;
            mFormat = format;
            mWidth = width;
            mHeight = height;
            SurfaceRenderView view = mWeakSurfaceView.get();
            if (view == null) return;
            ISurfaceHolder surfaceHolder = new InternalSurfaceHolder(view, mSurfaceHolder);
            for (IRenderCallback cb : mRenderCallbackMap.keySet()) {
                cb.onSurfaceChanged(surfaceHolder, format, width, height);
            }
        }
    }
}
