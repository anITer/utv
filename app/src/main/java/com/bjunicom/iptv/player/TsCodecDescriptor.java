package com.bjunicom.iptv.player;

import com.bjunicom.iptv.config.IptvSettings;

import java.util.Locale;

/**
 * 下载模块从 MPEG-TS 流 (PAT -> PMT -> ES Header) 中实时解析出的编解码器描述信息
 * 用于从 CodecDecoderPool 中按 Codec 规格匹配或创建对应的解码器实例。
 */
public class TsCodecDescriptor {
    public static final String VIDEO_H264 = "h264";
    public static final String VIDEO_HEVC = "hevc";
    public static final String VIDEO_MPEG2 = "mpeg2video";

    public static final String AUDIO_MP2 = "mp2";
    public static final String AUDIO_MP3 = "mp3";
    public static final String AUDIO_AAC = "aac";
    public static final String AUDIO_AC3 = "ac3";
    public static final String AUDIO_EAC3 = "eac3";

    public final String videoCodec;
    public final String audioCodec;
    public final int pmtPid;
    public final int videoPid;
    public final int audioPid;
    public final int videoStreamType;
    public final int audioStreamType;
    public final boolean keyframeAligned;
    public final long firstVideoPts;
    public final long parseCostMs;

    public TsCodecDescriptor(String videoCodec,
                             String audioCodec,
                             int pmtPid,
                             int videoPid,
                             int audioPid,
                             int videoStreamType,
                             int audioStreamType,
                             boolean keyframeAligned,
                             long firstVideoPts,
                             long parseCostMs) {
        this.videoCodec = videoCodec != null ? videoCodec : VIDEO_H264;
        this.audioCodec = audioCodec != null ? audioCodec : AUDIO_MP2;
        this.pmtPid = pmtPid;
        this.videoPid = videoPid;
        this.audioPid = audioPid;
        this.videoStreamType = videoStreamType;
        this.audioStreamType = audioStreamType;
        this.keyframeAligned = keyframeAligned;
        this.firstVideoPts = firstVideoPts;
        this.parseCostMs = parseCostMs;
    }

    public boolean isHevc() {
        return VIDEO_HEVC.equalsIgnoreCase(videoCodec);
    }

    /**
     * 判断是否需要启用 Android TV 硬件伴音引擎 (AC-3 / E-AC-3 杜比音频)
     */
    public boolean requiresHardwareAudioCompanion() {
        return AUDIO_AC3.equalsIgnoreCase(audioCodec) || AUDIO_EAC3.equalsIgnoreCase(audioCodec);
    }

    /**
     * 生成解码器池化 Key，使相同视频编码(H264/HEVC)与音频编码规格的频道直接复用池中解码器
     */
    public String toDecoderPoolKey(IptvSettings settings) {
        boolean hw = settings == null || settings.isMediaCodecEnabled();
        boolean hevcHw = settings == null || settings.isMediaCodecHevcEnabled();
        boolean opensl = settings != null && settings.isOpenSlEsEnabled();
        return String.format(Locale.US, "V_%s_A_%s_HW_%d_HEVC_%d_SL_%d",
                videoCodec.toUpperCase(Locale.US),
                audioCodec.toUpperCase(Locale.US),
                hw ? 1 : 0,
                hevcHw ? 1 : 0,
                opensl ? 1 : 0);
    }

    public static TsCodecDescriptor createDefault(boolean likely4K) {
        return new TsCodecDescriptor(
                likely4K ? VIDEO_HEVC : VIDEO_H264,
                likely4K ? AUDIO_EAC3 : AUDIO_MP2,
                -1,
                -1,
                -1,
                likely4K ? 0x24 : 0x1B,
                likely4K ? 0x87 : 0x03,
                false,
                -1L,
                0L
        );
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "TsCodecDescriptor{video=%s(pid=%d,0x%02x), audio=%s(pid=%d,0x%02x), keyframeAligned=%b, pts=%d, parseMs=%d}",
                videoCodec, videoPid, videoStreamType,
                audioCodec, audioPid, audioStreamType,
                keyframeAligned, firstVideoPts, parseCostMs);
    }
}
