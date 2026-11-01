package com.bjunicom.iptv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.network.StreamUrlResolver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 北京联通 IPTV 时移回看缩略图提取与预览画面生成器
 *
 * 支持三级缩略图获取管线：
 * 1. 内存 LRU 缓存（按频道 + 10 秒时间槽索引，毫秒级直接命中）；
 * 2. 针对北京联通 TVOD 时移流 (ts2hls / HTTP-RTSP)：后台异步通过 MediaMetadataRetriever 提取目标时间点的真实视频帧；
 * 3. 即时预览帧生成（结合当前视频帧快照 / 频道信息 / 精确时移时间戳 / 进度指示），确保遥控器快速连按左右键时缩略图窗口 0 延迟响应，绝不出现空白或黑屏。
 */
public class TimeshiftThumbnailExtractor {

    public static final int THUMB_WIDTH = 320;
    public static final int THUMB_HEIGHT = 180;
    private static final long BUCKET_MS = 10_000L; // 10 秒粒度缓存槽

    public interface ThumbnailCallback {
        void onThumbnailReady(@NonNull Bitmap bitmap, long targetEpochMs, boolean isDecodedFrame);
    }

    private final Context appContext;
    private final IptvSettings settings;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TimeshiftThumbExtractor");
        t.setDaemon(true);
        return t;
    });
    private final LruCache<String, Bitmap> frameCache = new LruCache<String, Bitmap>(48) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return 1;
        }
    };
    private final AtomicLong requestSeq = new AtomicLong(0L);
    private volatile Bitmap latestLiveSnapshot;
    private volatile String latestSnapshotChannelUrl = "";

    public TimeshiftThumbnailExtractor(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.settings = new IptvSettings(appContext);
    }

    /**
     * 在打开时移条或正常播放期间，捕获当前播放画面的快照作为基准帧
     */
    public void captureLiveSnapshot(@Nullable IjkVideoView videoView, @Nullable IptvChannel channel) {
        if (videoView == null || channel == null) {
            return;
        }
        String rawUrl = channel.getRawUrl() != null ? channel.getRawUrl() : "";
        View child = videoView.getChildCount() > 0 ? videoView.getChildAt(0) : null;
        if (child instanceof TextureView) {
            TextureView tv = (TextureView) child;
            if (tv.isAvailable()) {
                try {
                    Bitmap bmp = tv.getBitmap(THUMB_WIDTH, THUMB_HEIGHT);
                    if (bmp != null) {
                        latestLiveSnapshot = bmp;
                        latestSnapshotChannelUrl = rawUrl;
                        putCache(rawUrl, System.currentTimeMillis(), bmp);
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        } else if (child instanceof SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SurfaceView sv = (SurfaceView) child;
            if (sv.getHolder() != null
                    && sv.getHolder().getSurface() != null
                    && sv.getHolder().getSurface().isValid()
                    && sv.getWidth() > 0
                    && sv.getHeight() > 0) {
                try {
                    final Bitmap targetBmp = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888);
                    PixelCopy.request(sv, targetBmp, copyResult -> {
                        if (copyResult == PixelCopy.SUCCESS) {
                            latestLiveSnapshot = targetBmp;
                            latestSnapshotChannelUrl = rawUrl;
                            putCache(rawUrl, System.currentTimeMillis(), targetBmp);
                        }
                    }, mainHandler);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 请求指定频道在 targetEpochMs 时间点的时移缩略图：
     * - 立即同步返回/回调一张高清预览图（优先使用缓存帧或带时移时间水印的画面帧），保证 UI 立即显示；
     * - 若缓存未命中真实解码帧，则在后台线程向时移流请求提取关键帧并在完成后更新缩略图。
     */
    @NonNull
    public Bitmap requestThumbnail(@Nullable final IptvChannel channel,
                                   final long targetEpochMs,
                                   final long liveEdgeEpochMs,
                                   @Nullable final ThumbnailCallback callback) {
        final long reqId = requestSeq.incrementAndGet();
        final String channelKey = (channel != null && channel.getRawUrl() != null)
                ? channel.getRawUrl() : "default";

        Bitmap cached = getCached(channelKey, targetEpochMs);
        if (cached != null && !cached.isRecycled()) {
            if (callback != null) {
                callback.onThumbnailReady(cached, targetEpochMs, true);
            }
            return cached;
        }

        // 1. 立即生成实时合成预览帧（基于当前频道快照 + 时间戳与时移进度渲染）
        Bitmap baseSnapshot = TextUtils.equals(channelKey, latestSnapshotChannelUrl) ? latestLiveSnapshot : null;
        Bitmap immediatePreview = renderPreviewBitmap(channel, targetEpochMs, liveEdgeEpochMs, baseSnapshot);
        if (callback != null) {
            callback.onThumbnailReady(immediatePreview, targetEpochMs, false);
        }

        // 2. 若频道支持北京联通网络时移，后台异步提取该时间点的真实视频帧
        if (channel != null && channel.supportsTimeshift()) {
            executor.execute(() -> {
                if (reqId != requestSeq.get()) {
                    return;
                }
                Bitmap decoded = extractRemoteTimeshiftFrame(channel, targetEpochMs);
                if (decoded != null && !decoded.isRecycled()) {
                    Bitmap badged = overlayTimestampBadge(decoded, channel, targetEpochMs, liveEdgeEpochMs);
                    putCache(channelKey, targetEpochMs, badged);
                    if (reqId == requestSeq.get() && callback != null) {
                        mainHandler.post(() -> {
                            if (reqId == requestSeq.get()) {
                                callback.onThumbnailReady(badged, targetEpochMs, true);
                            }
                        });
                    }
                }
            });
        }

        return immediatePreview;
    }

    @Nullable
    private Bitmap extractRemoteTimeshiftFrame(@NonNull IptvChannel channel, long targetEpochMs) {
        String timeshiftUrl = StreamUrlResolver.resolveTimeshiftUrl(
                channel, settings, targetEpochMs, targetEpochMs + 30_000L, true);
        if (TextUtils.isEmpty(timeshiftUrl)) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            HashMap<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "uTV-ExoPlayer/2.19.1");
            retriever.setDataSource(timeshiftUrl, headers);
            Bitmap rawFrame = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                rawFrame = retriever.getScaledFrameAtTime(
                        0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_WIDTH, THUMB_HEIGHT);
            }
            if (rawFrame == null) {
                rawFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (rawFrame != null) {
                if (rawFrame.getWidth() != THUMB_WIDTH || rawFrame.getHeight() != THUMB_HEIGHT) {
                    Bitmap scaled = Bitmap.createScaledBitmap(rawFrame, THUMB_WIDTH, THUMB_HEIGHT, true);
                    if (scaled != rawFrame) {
                        rawFrame.recycle();
                    }
                    return scaled;
                }
                return rawFrame;
            }
        } catch (Throwable ignored) {
            // 非 IPTV 专网或单元测试环境下 MediaMetadataRetriever 无法连接 61.135.88.136 时静默回退
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 生成带频道信息、时移回看时间点与视觉波形帧的 16:9 缩略图 Bitmap
     */
    @NonNull
    public static Bitmap renderPreviewBitmap(@Nullable IptvChannel channel,
                                             long targetEpochMs,
                                             long liveEdgeEpochMs,
                                             @Nullable Bitmap baseSnapshot) {
        Bitmap bmp = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        long offsetSec = Math.max(0L, (liveEdgeEpochMs - targetEpochMs) / 1000L);

        if (baseSnapshot != null && !baseSnapshot.isRecycled()) {
            RectF dst = new RectF(0, 0, THUMB_WIDTH, THUMB_HEIGHT);
            canvas.drawBitmap(baseSnapshot, null, dst, paint);
            // 叠加半透明暗色遮罩使时间戳与回看标识清晰可读
            paint.setColor(Color.argb(115, 10, 15, 28));
            canvas.drawRect(0, 0, THUMB_WIDTH, THUMB_HEIGHT, paint);
        } else {
            // 根据目标时间点生成动态渐变背景与模拟视频帧画面纹理（不同时间点画面特征随之动态变化）
            int hueShift = (int) ((targetEpochMs / 10_000L) % 60L);
            int topColor = Color.rgb(18 + (hueShift % 20), 28 + (hueShift % 30), 52 + (hueShift % 40));
            int bottomColor = Color.rgb(10, 16, 30);
            LinearGradient bgGrad = new LinearGradient(
                    0, 0, THUMB_WIDTH, THUMB_HEIGHT,
                    topColor, bottomColor, Shader.TileMode.CLAMP);
            paint.setShader(bgGrad);
            canvas.drawRect(0, 0, THUMB_WIDTH, THUMB_HEIGHT, paint);
            paint.setShader(null);

            // 绘制动态电视信号预览网格与关键帧波形条
            paint.setColor(Color.argb(36, 148, 163, 184));
            paint.setStrokeWidth(1f);
            for (int x = 32; x < THUMB_WIDTH; x += 32) {
                canvas.drawLine(x, 0, x, THUMB_HEIGHT, paint);
            }
            for (int y = 30; y < THUMB_HEIGHT; y += 30) {
                canvas.drawLine(0, y, THUMB_WIDTH, y, paint);
            }

            // 动态帧条纹（随 seek 时间点变化而移动）
            paint.setColor(Color.argb(85, 56, 189, 248));
            int barCount = 12;
            float barWidth = (THUMB_WIDTH - 48f) / barCount;
            long seed = targetEpochMs / 5000L;
            for (int i = 0; i < barCount; i++) {
                float phase = (float) Math.abs(Math.sin((seed + i * 1.7) * 0.65));
                float barH = 18f + phase * 52f;
                float left = 24f + i * barWidth + 3f;
                float top = THUMB_HEIGHT * 0.56f - barH / 2f;
                canvas.drawRoundRect(new RectF(left, top, left + barWidth - 6f, top + barH), 4f, 4f, paint);
            }
        }

        return overlayTimestampBadge(bmp, channel, targetEpochMs, liveEdgeEpochMs);
    }

    @NonNull
    private static Bitmap overlayTimestampBadge(@NonNull Bitmap source,
                                                @Nullable IptvChannel channel,
                                                long targetEpochMs,
                                                long liveEdgeEpochMs) {
        Bitmap result = source.isMutable()
                ? source
                : source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 顶部频道名称与「北京联通时移」角标
        paint.setColor(Color.argb(170, 15, 23, 42));
        canvas.drawRect(0, 0, THUMB_WIDTH, 34, paint);

        String chTitle = (channel != null && !TextUtils.isEmpty(channel.getDisplayName()))
                ? channel.getDisplayName()
                : "北京联通 IPTV";
        paint.setColor(Color.parseColor("#F8FAFC"));
        paint.setTextSize(14f);
        paint.setFakeBoldText(true);
        canvas.drawText(chTitle, 12f, 22f, paint);

        long diffSec = Math.max(0L, (liveEdgeEpochMs - targetEpochMs) / 1000L);
        String badgeText = diffSec <= 5L ? "● 直播" : "↺ 时移回看";
        paint.setColor(diffSec <= 5L ? Color.parseColor("#10B981") : Color.parseColor("#F59E0B"));
        paint.setTextSize(12f);
        float badgeW = paint.measureText(badgeText);
        canvas.drawText(badgeText, THUMB_WIDTH - badgeW - 12f, 22f, paint);

        // 底部时间点胶囊栏
        paint.setColor(Color.argb(190, 15, 23, 42));
        canvas.drawRect(0, THUMB_HEIGHT - 44, THUMB_WIDTH, THUMB_HEIGHT, paint);

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String timeStr = sdf.format(new Date(targetEpochMs));

        paint.setColor(Color.parseColor("#38BDF8"));
        paint.setTextSize(17f);
        paint.setFakeBoldText(true);
        canvas.drawText(timeStr, 12f, THUMB_HEIGHT - 15f, paint);

        String offsetLabel = formatOffsetText(diffSec);
        paint.setColor(Color.parseColor("#E2E8F0"));
        paint.setTextSize(13f);
        paint.setFakeBoldText(false);
        float offsetW = paint.measureText(offsetLabel);
        canvas.drawText(offsetLabel, THUMB_WIDTH - offsetW - 12f, THUMB_HEIGHT - 16f, paint);

        return result;
    }

    public static String formatOffsetText(long diffSec) {
        if (diffSec <= 3L) {
            return "实时直播";
        }
        long hours = diffSec / 3600L;
        long minutes = (diffSec % 3600L) / 60L;
        long seconds = diffSec % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "-%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "-%02d:%02d", minutes, seconds);
    }

    @Nullable
    private Bitmap getCached(String channelKey, long targetEpochMs) {
        String key = channelKey + "#" + (targetEpochMs / BUCKET_MS);
        return frameCache.get(key);
    }

    private void putCache(String channelKey, long targetEpochMs, @NonNull Bitmap bitmap) {
        String key = channelKey + "#" + (targetEpochMs / BUCKET_MS);
        frameCache.put(key, bitmap);
    }

    public void release() {
        requestSeq.incrementAndGet();
        frameCache.evictAll();
        executor.shutdownNow();
    }
}
