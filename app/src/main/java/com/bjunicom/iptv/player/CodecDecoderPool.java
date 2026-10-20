package com.bjunicom.iptv.player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.bjunicom.iptv.config.IptvSettings;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * 解码器对象池 (CodecDecoderPool)
 *
 * 核心职责：
 * 1. 按下载模块实时解析出的 TsCodecDescriptor (如 H264+MP2、HEVC+EAC3、H264+AAC) 对解码器进行分类池化：
 *    - 视频/综合解码器池 (IjkMediaPlayer Pool)：按 Codec Profile Key 缓存已初始化并预配置好对应硬解/软解参数的 IjkMediaPlayer 实例。
 *    - 硬件伴音解码器池 (MediaCodec + AudioTrack Pool)：按音频 MIME 类型 (audio/eac3, audio/ac3, audio/mpeg-L2, audio/mp4a-latm)
 *      池化 Android TV 硬件 MediaCodec 与 AudioTrack 实例，切台时仅需 flush() 即可复用，避免反复 stop/release/createDecoderByType。
 * 2. 切台时异步回收旧解码器 (后台线程执行 reset() 并重新注入 Codec 参数归还池中)，主线程零阻塞。
 * 3. 配合 StreamDownloaderPool 的「PAT+PMT+IDR 关键帧与音画同 PTS 对齐流」，将 probesize 降至 96KB、analyzeduration 降至 120ms、
 *    fpsprobesize=0、sync-av-start=1，彻底消除切台前几秒的 0.5x 慢放现象。
 */
public class CodecDecoderPool {
    private static final String TAG = "CodecDecoderPool";
    private static final int MAX_IJK_PER_PROFILE = 2;
    private static final int MAX_HW_AUDIO_PER_MIME = 2;

    private static volatile CodecDecoderPool instance;

    private final Map<String, Queue<PooledIjkDecoder>> ijkDecoderPools = new ConcurrentHashMap<>();
    private final Map<String, Queue<PooledHwAudioDecoder>> hwAudioPools = new ConcurrentHashMap<>();
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CodecDecoderPoolRecycler");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger ijkReuseHits = new AtomicInteger(0);
    private final AtomicInteger ijkCreatedCount = new AtomicInteger(0);
    private final AtomicInteger hwAudioReuseHits = new AtomicInteger(0);

    public static class PooledIjkDecoder {
        public final IjkMediaPlayer player;
        public final String profileKey;
        public final TsCodecDescriptor codecDescriptor;
        public final boolean reusedFromPool;

        PooledIjkDecoder(IjkMediaPlayer player,
                         String profileKey,
                         TsCodecDescriptor codecDescriptor,
                         boolean reusedFromPool) {
            this.player = player;
            this.profileKey = profileKey;
            this.codecDescriptor = codecDescriptor;
            this.reusedFromPool = reusedFromPool;
        }
    }

    public static class PooledHwAudioDecoder {
        public final String mimeType;
        public MediaCodec codec;
        public AudioTrack audioTrack;
        public boolean passthroughMode;
        public int sampleRate = 48000;
        public int channelCount = 2;
        public String label = "";
        // AAC 已生效的 AudioSpecificConfig (csd-0)，避免重复 stop/configure/start
        private byte[] aacActiveAsc;

        PooledHwAudioDecoder(String mimeType) {
            this.mimeType = mimeType;
        }

        /**
         * 按 ADTS 头解析出的 AudioSpecificConfig 重新配置 AAC 解码器。
         * MediaCodec 的 audio/mp4a-latm 解码器只接受裸 AAC 帧且必须携带 csd-0，
         * 直接喂 ADTS 帧会静默无声（北京联通 4K 频道即此场景）。
         * 相同配置重复调用是 no-op；flush() 复用不会丢失 csd-0。
         */
        public synchronized void configureAac(byte[] asc, int sampleRate, int channels) {
            if (codec == null || !MediaFormat.MIMETYPE_AUDIO_AAC.equals(mimeType) || asc == null) {
                return;
            }
            if (aacActiveAsc != null && aacActiveAsc.length == asc.length) {
                boolean same = true;
                for (int i = 0; i < asc.length; i++) {
                    if (aacActiveAsc[i] != asc[i]) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    return;
                }
            }
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                MediaFormat format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(asc));
                codec.configure(format, null, null, 0);
                codec.start();
                aacActiveAsc = asc;
                this.sampleRate = sampleRate;
                this.channelCount = channels;
                this.label = MediaFormat.MIMETYPE_AUDIO_AAC + " (池化硬解/ADTS)";
                Log.i(TAG, "Reconfigured AAC decoder with ADTS-derived CSD: sr=" + sampleRate
                        + " ch=" + channels);
            } catch (Throwable t) {
                Log.w(TAG, "AAC reconfigure with CSD failed: " + t.getMessage());
            }
        }

        public void flushForReuse() {
            if (codec != null) {
                try {
                    codec.flush();
                } catch (Throwable ignored) {
                }
            }
            if (audioTrack != null) {
                try {
                    audioTrack.pause();
                    audioTrack.flush();
                    audioTrack.play();
                } catch (Throwable ignored) {
                }
            }
        }

        public void releaseNow() {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
                codec = null;
            }
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                } catch (Throwable ignored) {
                }
                try {
                    audioTrack.release();
                } catch (Throwable ignored) {
                }
                audioTrack = null;
            }
        }
    }

    private CodecDecoderPool() {
    }

    public static CodecDecoderPool getInstance() {
        if (instance == null) {
            synchronized (CodecDecoderPool.class) {
                if (instance == null) {
                    instance = new CodecDecoderPool();
                }
            }
        }
        return instance;
    }

    /**
     * 预热常用 Codec 规格的解码器 (H264+MP2 高清频道与 HEVC+EAC3 4K 频道)
     */
    public void prewarmCommonDecoders(final IptvSettings settings) {
        lifecycleExecutor.execute(() -> {
            try {
                TsCodecDescriptor hdDesc = TsCodecDescriptor.createDefault(false);
                String hdKey = hdDesc.toDecoderPoolKey(settings);
                Queue<PooledIjkDecoder> hdQueue = getOrCreateIjkQueue(hdKey);
                if (hdQueue.isEmpty()) {
                    IjkMediaPlayer mp = createConfiguredIjkPlayer(hdDesc, settings);
                    hdQueue.offer(new PooledIjkDecoder(mp, hdKey, hdDesc, true));
                    Log.i(TAG, "Prewarmed H264+MP2 decoder in pool: " + hdKey);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Prewarm decoder skipped: " + t.getMessage());
            }
        });
    }

    /**
     * 根据下载模块解析出的 TsCodecDescriptor 从池中匹配或创建对应的 IjkMediaPlayer 解码器
     */
    public PooledIjkDecoder acquireIjkDecoder(TsCodecDescriptor descriptor, IptvSettings settings) {
        TsCodecDescriptor safeDesc = descriptor != null ? descriptor : TsCodecDescriptor.createDefault(false);
        String profileKey = safeDesc.toDecoderPoolKey(settings);
        Queue<PooledIjkDecoder> queue = getOrCreateIjkQueue(profileKey);

        PooledIjkDecoder pooled = queue.poll();
        if (pooled != null && pooled.player != null) {
            int hits = ijkReuseHits.incrementAndGet();
            Log.i(TAG, "Reused pooled IjkMediaPlayer for profile=" + profileKey + " (hits=" + hits + ")");
            return new PooledIjkDecoder(pooled.player, profileKey, safeDesc, true);
        }

        IjkMediaPlayer created = createConfiguredIjkPlayer(safeDesc, settings);
        int totalCreated = ijkCreatedCount.incrementAndGet();
        Log.i(TAG, "Created new IjkMediaPlayer for profile=" + profileKey + " (created=" + totalCreated + ")");
        return new PooledIjkDecoder(created, profileKey, safeDesc, false);
    }

    /**
     * 归还 IjkMediaPlayer 到对应 Codec Profile 池中：
     * 主线程立即解绑 Surface 与回调，后台线程执行 reset() 与参数重配，避免切台卡顿与重复创建。
     */
    public void releaseIjkDecoder(final PooledIjkDecoder decoderSlot,
                                  final IptvSettings settings,
                                  final boolean healthy) {
        if (decoderSlot == null || decoderSlot.player == null) {
            return;
        }
        final IjkMediaPlayer mp = decoderSlot.player;
        try {
            mp.resetListeners();
            mp.setDisplay(null);
            mp.setSurface(null);
        } catch (Throwable ignored) {
        }

        lifecycleExecutor.execute(() -> {
            Queue<PooledIjkDecoder> queue = getOrCreateIjkQueue(decoderSlot.profileKey);
            if (healthy && queue.size() < MAX_IJK_PER_PROFILE) {
                try {
                    mp.reset();
                    configureIjkOptionsForCodec(mp, decoderSlot.codecDescriptor, settings);
                    queue.offer(new PooledIjkDecoder(mp, decoderSlot.profileKey, decoderSlot.codecDescriptor, true));
                    Log.d(TAG, "Recycled IjkMediaPlayer back to pool: " + decoderSlot.profileKey
                            + " (poolSize=" + queue.size() + ")");
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "reset() failed during recycle, releasing native instance: " + t.getMessage());
                }
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 清空某 Codec Profile 的池化解码器（全部彻底 release，不回收）。
     * 用于 HEVC 硬解失败后的全新实例重试，避免重试拿到池中的旧实例。
     */
    public void drainIjkDecodersForProfile(TsCodecDescriptor descriptor, IptvSettings settings) {
        if (descriptor == null) {
            return;
        }
        String key = descriptor.toDecoderPoolKey(settings);
        Queue<PooledIjkDecoder> q = ijkDecoderPools.get(key);
        if (q == null) {
            return;
        }
        PooledIjkDecoder slot;
        int drained = 0;
        while ((slot = q.poll()) != null) {
            try {
                slot.player.release();
            } catch (Throwable ignored) {
            }
            drained++;
        }
        if (drained > 0) {
            Log.i(TAG, "Drained " + drained + " pooled decoder(s) for profile=" + key
                    + " before fresh HEVC retry");
        }
    }

    private Queue<PooledIjkDecoder> getOrCreateIjkQueue(String profileKey) {
        Queue<PooledIjkDecoder> q = ijkDecoderPools.get(profileKey);
        if (q == null) {
            Queue<PooledIjkDecoder> newQ = new ConcurrentLinkedQueue<>();
            Queue<PooledIjkDecoder> existing = ijkDecoderPools.putIfAbsent(profileKey, newQ);
            q = existing != null ? existing : newQ;
        }
        return q;
    }

    private IjkMediaPlayer createConfiguredIjkPlayer(TsCodecDescriptor desc, IptvSettings settings) {
        IjkMediaPlayer ijkMediaPlayer = new IjkMediaPlayer();
        IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_INFO);
        configureIjkOptionsForCodec(ijkMediaPlayer, desc, settings);
        return ijkMediaPlayer;
    }

    /**
     * 按解析出的 Codec 特征配置解码器参数，并针对已对齐 IDR 关键帧的 TS 流消除慢放：
     * 1. 根据 desc.isHevc() 精确开启 H.264 或 HEVC 硬件解码器；
     * 2. 由于 StreamDownloaderPool 已在头部注入 PAT+PMT+首个IDR关键帧+对齐PTS的音频帧，
     *    将 probesize 设为 96KB、analyzeduration 设为 120ms、fpsprobesize=0，避免 FFmpeg 缓冲 2 秒旧包导致音画时钟错位慢放；
     * 3. 开启 sync-av-start=1、infbuf=1、packet-buffering=0、min-frames=5，确保起播即刻正常倍速(1.0x)播放。
     */
    public static void configureIjkOptionsForCodec(IjkMediaPlayer ijkMediaPlayer,
                                                   TsCodecDescriptor desc,
                                                   IptvSettings settings) {
        boolean useMediaCodec = settings == null || settings.isMediaCodecEnabled();
        boolean isHevcStream = desc != null && desc.isHevc();
        boolean enableHevcHw = useMediaCodec && (settings == null || settings.isMediaCodecHevcEnabled()) && isHevcStream;
        boolean useOpenSlEs = settings != null && settings.isOpenSlEsEnabled();

        // 1. 硬件解码器按解析出的视频编码 (H.264 vs HEVC) 精确配置
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", enableHevcHw ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-sync", 1);

        // 2. 音频输出设置：若流为 EAC3/AC3 则交由池化的硬件伴音解码器处理
        boolean companionAudio = desc != null && desc.requiresHardwareAudioCompanion();
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", useOpenSlEs ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", companionAudio ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "volume", 100);

        // 3. 消除切台慢放 (Slow-Motion) 的音画同步与起播探针参数
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 2);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "fast", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 5);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 2 * 1024 * 1024L);

        // 下载模块已完成 PAT+PMT+IDR 对齐，FFmpeg 仅需极小探针即可完成流信息识别，避免积压历史包导致慢放
        // HEVC 4K 例外：Wi-Fi 组播加入初期的丢包常损坏首个 IDR，探针太小会探测不到视频参数
        // (unspecified size) → MTK 硬解以 0x0 尺寸配置失败 (err -1010) 或被 ijk 直接跳过。
        // 加大 HEVC 的探测窗口让 FFmpeg 等到下一个完整 IDR，代价仅是 4K 起播多等约 1 秒。
        boolean preAligned = desc != null && desc.keyframeAligned;
        long probeSize = preAligned ? (96 * 1024L) : (256 * 1024L);
        long analyzeUs = preAligned ? 120000L : 400000L;
        if (isHevcStream) {
            probeSize = Math.max(probeSize, 1536 * 1024L);
            analyzeUs = Math.max(analyzeUs, 1500000L);
        }
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", probeSize);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", analyzeUs);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fpsprobesize", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer+fastseek+discardcorrupt");
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8000000L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        ijkMediaPlayer.setVolume(1.0f, 1.0f);
    }

    // ==================== 硬件音频 MediaCodec + AudioTrack 池化 ====================

    /**
     * 按音频 MIME 类型 (如 audio/eac3, audio/ac3, audio/mpeg-L2) 从池中获取已初始化的硬件音频解码器
     */
    public PooledHwAudioDecoder acquireHwAudioDecoder(String mimeType) {
        if (TextUtils.isEmpty(mimeType)) {
            return null;
        }
        Queue<PooledHwAudioDecoder> q = getOrCreateHwAudioQueue(mimeType);
        PooledHwAudioDecoder slot = q.poll();
        if (slot != null && (slot.codec != null || slot.passthroughMode)) {
            slot.flushForReuse();
            int hits = hwAudioReuseHits.incrementAndGet();
            Log.i(TAG, "Reused pooled Hardware Audio Decoder for " + mimeType + " (hits=" + hits + ")");
            return slot;
        }

        PooledHwAudioDecoder created = createHwAudioDecoder(mimeType);
        return created;
    }

    /**
     * 归还硬件音频解码器到池中复用 (切台时不销毁 MediaCodec，下次同编码频道直接 flush 复用)
     */
    public void releaseHwAudioDecoder(final PooledHwAudioDecoder slot, final boolean healthy) {
        if (slot == null) {
            return;
        }
        lifecycleExecutor.execute(() -> {
            Queue<PooledHwAudioDecoder> q = getOrCreateHwAudioQueue(slot.mimeType);
            if (healthy && q.size() < MAX_HW_AUDIO_PER_MIME && (slot.codec != null || slot.passthroughMode)) {
                try {
                    if (slot.codec != null) {
                        slot.codec.flush();
                    }
                    if (slot.audioTrack != null) {
                        slot.audioTrack.pause();
                        slot.audioTrack.flush();
                    }
                    q.offer(slot);
                    Log.d(TAG, "Recycled Hardware Audio Decoder to pool: " + slot.mimeType);
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to flush HW audio decoder for pool, releasing: " + t.getMessage());
                }
            }
            slot.releaseNow();
        });
    }

    private Queue<PooledHwAudioDecoder> getOrCreateHwAudioQueue(String mimeType) {
        Queue<PooledHwAudioDecoder> q = hwAudioPools.get(mimeType);
        if (q == null) {
            Queue<PooledHwAudioDecoder> newQ = new ConcurrentLinkedQueue<>();
            Queue<PooledHwAudioDecoder> existing = hwAudioPools.putIfAbsent(mimeType, newQ);
            q = existing != null ? existing : newQ;
        }
        return q;
    }

    private PooledHwAudioDecoder createHwAudioDecoder(String mimeType) {
        PooledHwAudioDecoder slot = new PooledHwAudioDecoder(mimeType);
        String[] candidateMimes = "audio/mpeg-L2".equals(mimeType)
                ? new String[]{"audio/mpeg-L2", MediaFormat.MIMETYPE_AUDIO_MPEG}
                : new String[]{mimeType};

        for (String candidate : candidateMimes) {
            try {
                MediaFormat format = MediaFormat.createAudioFormat(candidate, 48000, 2);
                MediaCodec codec = MediaCodec.createDecoderByType(candidate);
                codec.configure(format, null, null, 0);
                codec.start();
                slot.codec = codec;
                slot.audioTrack = createPcmAudioTrack(48000, 2);
                slot.passthroughMode = false;
                slot.label = candidate + " (池化硬解)";
                Log.i(TAG, "Created pooled TV MediaCodec audio decoder for " + candidate);
                return slot;
            } catch (Throwable t) {
                if (slot.codec != null) {
                    try {
                        slot.codec.release();
                    } catch (Throwable ignored) {
                    }
                    slot.codec = null;
                }
            }
        }

        if (MediaFormat.MIMETYPE_AUDIO_AC3.equals(mimeType)
                || MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mimeType)) {
            try {
                int encoding = MediaFormat.MIMETYPE_AUDIO_EAC3.equals(mimeType)
                        ? AudioFormat.ENCODING_E_AC3
                        : AudioFormat.ENCODING_AC3;
                slot.audioTrack = createPassthroughAudioTrack(48000, encoding);
                slot.passthroughMode = true;
                slot.label = mimeType + " (池化HDMI透传)";
                return slot;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to init passthrough AudioTrack: " + t.getMessage());
            }
        }
        return slot;
    }

    public static AudioTrack createPcmAudioTrack(int sampleRate, int channelCount) {
        int channelConfig = channelCount == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, 32768);
        AudioTrack track;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    bufSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        } else {
            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM);
        }
        track.play();
        return track;
    }

    public static AudioTrack createPassthroughAudioTrack(int sampleRate, int encoding) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(encoding)
                            .build(),
                    32768,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
            return track;
        }
        return null;
    }

    public String getPoolStatsSummary() {
        return "IjkHits=" + ijkReuseHits.get() + ", HwAudioHits=" + hwAudioReuseHits.get();
    }
}
