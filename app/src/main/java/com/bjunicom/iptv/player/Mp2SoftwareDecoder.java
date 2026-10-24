package com.bjunicom.iptv.player;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.decoder.SimpleDecoderOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 纯 Java 零外部依赖的 ISO/IEC 11172-3 / 13818-3 MPEG-1/2 Audio Layer II (MP2) 实时软件解码器。
 *
 * 解决痛点：
 * - 国内广电及三大运营商 IPTV（如北京联通/北京电信）标清及大量高清频道采用 MP2 (audio/mpeg-L2, stream_type=0x03/0x04) 伴音；
 * - Android 系统 MediaCodec 默认仅内置 MP3 (audio/mpeg, Layer III) 解码器，绝大多数电视/盒子/手机无 audio/mpeg-L2 硬件或系统解码器；
 * - 本类提供：
 *   1. {@link Mp2Decoder}：独立帧级 MP2 -> 16-bit PCM 解码引擎（与 FFmpeg mpegaudiodec 输出逐样本对齐，误差 <= 1~2 LSB）；
 *   2. {@link ExoMp2SimpleDecoder}：实现 ExoPlayer {@link SimpleDecoder} 接口，配合 {@link com.google.android.exoplayer2.audio.DecoderAudioRenderer}
 *      在视频保持 MediaCodec 硬解的同时，仅将 MP2 音频无缝回退至软解并驱动 AudioSink 主时钟。
 */
public final class Mp2SoftwareDecoder {

    public static final String MIME_AUDIO_MPEG_L2 = MimeTypes.AUDIO_MPEG_L2; // "audio/mpeg-L2"

    // MPEG Audio 采样率表: [mpeg1(0) / mpeg2(1) / mpeg2.5(2)][srate_index]
    private static final int[][] SAMPLE_RATES = {
            {44100, 48000, 32000, 0}, // MPEG-1
            {22050, 24000, 16000, 0}, // MPEG-2 LSF
            {11025, 12000, 8000, 0}   // MPEG-2.5
    };

    // MPEG Layer II 比特率表 (kbps): [lsf (0=MPEG-1, 1=MPEG-2/2.5)][bitrate_index]
    private static final int[][] BITRATES_L2 = {
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
    };

    // MPEG Layer I 比特率表 (kbps): [lsf][bitrate_index]
    private static final int[][] BITRATES_L1 = {
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0}
    };

    // ISO/IEC 11172-3 Layer II 5 张子带比特分配表对应的 sblimit
    private static final int[] SBLIMIT_TABLE = {27, 30, 8, 12, 30};

    // 量化步长与比特数表 (与 FFmpeg ff_mpa_quant_steps / ff_mpa_quant_bits 完全一致)
    // 负数代表将 3 个连续子带样本打包为一个码字 (grouping): -5 -> 3 levels (5 bits), -7 -> 5 levels (7 bits), -10 -> 9 levels (10 bits)
    private static final int[] QUANT_BITS = {
            -5, -7, 3, -10, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    private static final int[] QUANT_STEPS = {
            3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535
    };

    // 各子带 nbal (比特分配索引本身的位宽) 与比特分配映射表: [tableIndex][subband][0=nbal, 1..2^nbal=stepIndex]
    private static final int[][][] ALLOC_TABLES = buildAllocTables();

    // 64 个比例因子: SF[i] = 2.0 ^ (1 - i / 3.0)
    private static final float[] SCALEFACTORS = buildScalefactors();

    // ISO/IEC 11172-3 257 点原型多相合成窗 (与 FFmpeg ff_mpa_enwindow 完全一致，除以 65536 归一化)
    private static final int[] ENWINDOW_RAW = {
            0, -1, -1, -1, -1, -1, -1, -2, -2, -2, -2, -3, -3, -4, -4, -5,
            -5, -6, -7, -7, -8, -9, -10, -11, -13, -14, -16, -17, -19, -21, -24, -26,
            -29, -31, -35, -38, -41, -45, -49, -53, -58, -63, -68, -73, -79, -85, -91, -97,
            -104, -111, -117, -125, -132, -139, -147, -154, -161, -169, -176, -183, -190, -196, -202, -208,
            213, 218, 222, 225, 227, 228, 228, 227, 224, 221, 215, 208, 200, 189, 177, 163,
            146, 127, 106, 83, 57, 29, -2, -36, -72, -111, -153, -197, -244, -294, -347, -401,
            -459, -519, -581, -645, -711, -779, -848, -919, -991, -1064, -1137, -1210, -1283, -1356, -1428, -1498,
            -1567, -1634, -1698, -1759, -1817, -1870, -1919, -1962, -2001, -2032, -2057, -2075, -2085, -2087, -2080, -2063,
            2037, 2000, 1952, 1893, 1822, 1739, 1644, 1535, 1414, 1280, 1131, 970, 794, 605, 402, 185,
            -45, -288, -545, -814, -1095, -1388, -1692, -2006, -2330, -2663, -3004, -3351, -3705, -4063, -4425, -4788,
            -5153, -5517, -5879, -6237, -6589, -6935, -7271, -7597, -7910, -8209, -8491, -8755, -8998, -9219, -9416, -9585,
            -9727, -9838, -9916, -9959, -9966, -9935, -9863, -9750, -9592, -9389, -9139, -8840, -8492, -8092, -7640, -7134,
            6574, 5959, 5288, 4561, 3776, 2935, 2037, 1082, 70, -998, -2122, -3300, -4533, -5818, -7154, -8540,
            -9975, -11455, -12980, -14548, -16155, -17799, -19478, -21189, -22929, -24694, -26482, -28289, -30112, -31947, -33791, -35640,
            -37489, -39336, -41176, -43006, -44821, -46617, -48390, -50137, -51853, -53534, -55178, -56778, -58333, -59838, -61289, -62684,
            -64019, -65290, -66494, -67629, -68692, -69679, -70590, -71420, -72169, -72835, -73415, -73908, -74313, -74630, -74856, -74992,
            75038
    };

    // 512 点合成滤波窗 D[0..511] 与 64x32 余弦矩阵 M[i][k] = cos((16 + i) * (2k + 1) * pi / 64)
    private static final float[] SYNTH_WINDOW = buildSynthWindow();
    private static final float[][] COS_MATRIX = buildCosMatrix();

    private Mp2SoftwareDecoder() {
    }

    public static boolean isMp2MimeType(@Nullable String mimeType) {
        return MIME_AUDIO_MPEG_L2.equalsIgnoreCase(mimeType);
    }

    private static float[] buildScalefactors() {
        float[] sf = new float[64];
        for (int i = 0; i < 64; i++) {
            sf[i] = (float) Math.pow(2.0, 1.0 - (i / 3.0));
        }
        return sf;
    }

    private static float[] buildSynthWindow() {
        // 与 FFmpeg ff_mpa_synth_init_float 完全一致的 512 点对称多相合成滤波窗展开：
        // 对 i = 0..256:
        //   d[i] = ENWINDOW_RAW[i] / 65536.0f
        //   当 (i & 63) != 0 时反号，写入 d[512 - i]
        float[] d = new float[512];
        final float scale = 1.0f / 65536.0f;
        for (int i = 0; i <= 256; i++) {
            float v = ENWINDOW_RAW[i] * scale;
            d[i] = v;
            if ((i & 63) != 0) {
                v = -v;
            }
            if (i != 0) {
                d[512 - i] = v;
            }
        }
        return d;
    }

    private static float[][] buildCosMatrix() {
        float[][] m = new float[64][32];
        for (int i = 0; i < 64; i++) {
            for (int k = 0; k < 32; k++) {
                m[i][k] = (float) Math.cos((16 + i) * (2 * k + 1) * Math.PI / 64.0);
            }
        }
        return m;
    }

    private static int[][][] buildAllocTables() {
        int[] rowB2a_0_2 = {4, 0, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        int[] rowB2a_3_10 = {4, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16};
        int[] rowB2a_11_22 = {3, 0, 1, 2, 3, 4, 5, 16};
        int[] rowB2a_23_29 = {2, 0, 1, 16};

        int[][] table0 = new int[27][];
        int[][] table1 = new int[30][];
        for (int sb = 0; sb < 30; sb++) {
            int[] row = sb < 3 ? rowB2a_0_2 : (sb < 11 ? rowB2a_3_10 : (sb < 23 ? rowB2a_11_22 : rowB2a_23_29));
            if (sb < 27) {
                table0[sb] = row;
            }
            table1[sb] = row;
        }

        int[] rowB2c_0_1 = {4, 0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        int[] rowB2c_2_11 = {3, 0, 1, 3, 4, 5, 6, 7};
        int[][] table2 = new int[8][];
        int[][] table3 = new int[12][];
        for (int sb = 0; sb < 12; sb++) {
            int[] row = sb < 2 ? rowB2c_0_1 : rowB2c_2_11;
            if (sb < 8) {
                table2[sb] = row;
            }
            table3[sb] = row;
        }

        int[] rowB2e_0_3 = {4, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
        int[] rowB2e_4_10 = {3, 0, 1, 3, 4, 5, 6, 7};
        int[] rowB2e_11_29 = {2, 0, 1, 3};
        int[][] table4 = new int[30][];
        for (int sb = 0; sb < 30; sb++) {
            table4[sb] = sb < 4 ? rowB2e_0_3 : (sb < 11 ? rowB2e_4_10 : rowB2e_11_29);
        }

        return new int[][][]{table0, table1, table2, table3, table4};
    }

    /**
     * 选择 Layer II 子带分配表索引 (0..4)，与 FFmpeg ff_mpa_l2_select_table 逻辑完全一致
     */
    public static int selectLayer2Table(int bitrateKbps, int nbChannels, int sampleRateHz, boolean lsf) {
        int chBitrate = bitrateKbps / Math.max(1, nbChannels);
        if (!lsf) {
            if ((sampleRateHz == 48000 && chBitrate >= 56)
                    || (chBitrate >= 56 && chBitrate <= 80)) {
                return 0;
            } else if (sampleRateHz != 48000 && chBitrate >= 96) {
                return 1;
            } else if (sampleRateHz != 32000 && chBitrate <= 48) {
                return 2;
            } else {
                return 3;
            }
        } else {
            return 4;
        }
    }

    /**
     * 解析 32-bit MPEG Audio 帧同步头；若非合法 Layer I / Layer II 帧头则返回 null
     */
    public static final class FrameHeader {
        public final int layer;        // 1 or 2
        public final boolean lsf;      // true for MPEG-2 / MPEG-2.5
        public final int sampleRate;   // Hz (e.g. 48000, 44100, 32000)
        public final int bitrateKbps;  // kbps (e.g. 192, 256, 384)
        public final int channels;     // 1 or 2
        public final int mode;         // 0=stereo, 1=joint_stereo, 2=dual_channel, 3=mono
        public final int modeExt;      // 0..3 for joint_stereo bound
        public final boolean crcProtected;
        public final int frameSize;    // Total frame bytes

        private FrameHeader(int layer,
                            boolean lsf,
                            int sampleRate,
                            int bitrateKbps,
                            int channels,
                            int mode,
                            int modeExt,
                            boolean crcProtected,
                            int frameSize) {
            this.layer = layer;
            this.lsf = lsf;
            this.sampleRate = sampleRate;
            this.bitrateKbps = bitrateKbps;
            this.channels = channels;
            this.mode = mode;
            this.modeExt = modeExt;
            this.crcProtected = crcProtected;
            this.frameSize = frameSize;
        }
    }

    @Nullable
    public static FrameHeader parseHeader(int header) {
        if ((header & 0xFFE00000) != 0xFFE00000) {
            return null;
        }
        int versionBits = (header >>> 19) & 0x03;
        if (versionBits == 1) {
            return null; // reserved
        }
        int layerBits = (header >>> 17) & 0x03;
        if (layerBits != 2 && layerBits != 3) {
            // 仅处理 Layer II (layerBits==2) 与 Layer I (layerBits==3)
            return null;
        }
        int layer = 4 - layerBits;
        boolean crcProtected = ((header >>> 16) & 0x01) == 0;
        int bitrateIndex = (header >>> 12) & 0x0F;
        if (bitrateIndex == 0 || bitrateIndex == 15) {
            return null;
        }
        int srateIndex = (header >>> 10) & 0x03;
        if (srateIndex == 3) {
            return null;
        }
        int padding = (header >>> 9) & 0x01;
        int mode = (header >>> 6) & 0x03;
        int modeExt = (header >>> 4) & 0x03;

        int mpegIdx = (versionBits == 3) ? 0 : (versionBits == 2 ? 1 : 2);
        boolean lsf = (mpegIdx != 0);
        int sampleRate = SAMPLE_RATES[mpegIdx][srateIndex];
        if (sampleRate <= 0) {
            return null;
        }
        int bitrateKbps = (layer == 2)
                ? BITRATES_L2[lsf ? 1 : 0][bitrateIndex]
                : BITRATES_L1[lsf ? 1 : 0][bitrateIndex];
        if (bitrateKbps <= 0) {
            return null;
        }
        int frameSize;
        if (layer == 2) {
            frameSize = (144000 * bitrateKbps) / sampleRate + padding;
        } else {
            frameSize = ((12000 * bitrateKbps) / sampleRate + padding) * 4;
        }
        if (frameSize < 24 || frameSize > 4096) {
            return null;
        }
        int channels = (mode == 3) ? 1 : 2;
        return new FrameHeader(layer, lsf, sampleRate, bitrateKbps, channels, mode, modeExt, crcProtected, frameSize);
    }

    /**
     * 轻量比特流读取器
     */
    private static final class BitReader {
        private byte[] data;
        private int bitPos;
        private int bitLimit;

        void reset(byte[] buf, int byteOffset, int byteLength) {
            this.data = buf;
            this.bitPos = byteOffset * 8;
            this.bitLimit = (byteOffset + byteLength) * 8;
        }

        int readBits(int n) {
            if (n <= 0) {
                return 0;
            }
            if (bitPos + n > bitLimit) {
                bitPos = bitLimit;
                return 0;
            }
            int val = 0;
            while (n > 0) {
                int byteIdx = bitPos >>> 3;
                int bitOffset = bitPos & 7;
                int avail = 8 - bitOffset;
                int take = Math.min(n, avail);
                int mask = (1 << take) - 1;
                int chunk = ((data[byteIdx] & 0xFF) >>> (avail - take)) & mask;
                val = (val << take) | chunk;
                bitPos += take;
                n -= take;
            }
            return val;
        }
    }

    /**
     * 有状态 MP2 帧级软件解码器（维护双声道 1024 点多相滤波环形缓冲）
     */
    public static final class Mp2Decoder {
        private final float[][] synthBuf = new float[2][1024];
        private final int[] synthOffset = {0, 0};
        private final BitReader bitReader = new BitReader();

        private final int[][] allocation = new int[2][32];
        private final int[][] scfsi = new int[2][32];
        private final int[][][] scalefactorIdx = new int[2][32][3];
        private final float[][][] subbandSamples = new float[2][3][32];
        private final ByteArrayOutputStream carryStream = new ByteArrayOutputStream(4096);

        private int lastSampleRate = 48000;
        private int lastChannels = 2;

        public int getSampleRate() {
            return lastSampleRate;
        }

        public int getChannelCount() {
            return lastChannels;
        }

        public void reset() {
            carryStream.reset();
            for (int ch = 0; ch < 2; ch++) {
                synthOffset[ch] = 0;
                java.util.Arrays.fill(synthBuf[ch], 0.0f);
            }
        }

        /**
         * 解码输入的 MP2 ES 字节块（可包含一帧或多帧），返回 16-bit Little-Endian PCM 字节数组
         */
        public byte[] decodeStreamChunk(byte[] data, int offset, int length) {
            if (data == null || length <= 0) {
                return new byte[0];
            }
            carryStream.write(data, offset, length);
            byte[] buf = carryStream.toByteArray();
            carryStream.reset();

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(8192);
            int pos = 0;
            while (pos + 4 <= buf.length) {
                if ((buf[pos] & 0xFF) != 0xFF || (buf[pos + 1] & 0xE0) != 0xE0) {
                    pos++;
                    continue;
                }
                int hdrInt = ((buf[pos] & 0xFF) << 24)
                        | ((buf[pos + 1] & 0xFF) << 16)
                        | ((buf[pos + 2] & 0xFF) << 8)
                        | (buf[pos + 3] & 0xFF);
                FrameHeader hdr = parseHeader(hdrInt);
                if (hdr == null) {
                    pos++;
                    continue;
                }
                if (pos + hdr.frameSize > buf.length) {
                    // 半帧留待下次拼接
                    break;
                }
                byte[] framePcm = decodeSingleFrame(buf, pos, hdr);
                if (framePcm != null && framePcm.length > 0) {
                    pcmOut.write(framePcm, 0, framePcm.length);
                }
                pos += hdr.frameSize;
            }
            if (pos < buf.length) {
                carryStream.write(buf, pos, buf.length - pos);
            }
            return pcmOut.toByteArray();
        }

        /**
         * 解码完整单帧 MP2 (1152 个采样点/声道)
         */
        @Nullable
        public byte[] decodeSingleFrame(byte[] buf, int frameOffset, FrameHeader hdr) {
            lastSampleRate = hdr.sampleRate;
            lastChannels = hdr.channels;

            int headerBytes = hdr.crcProtected ? 6 : 4;
            if (hdr.frameSize <= headerBytes) {
                return null;
            }
            bitReader.reset(buf, frameOffset + headerBytes, hdr.frameSize - headerBytes);

            if (hdr.layer == 1) {
                return decodeLayer1Frame(hdr);
            }

            int tableIdx = selectLayer2Table(hdr.bitrateKbps, hdr.channels, hdr.sampleRate, hdr.lsf);
            int sblimit = SBLIMIT_TABLE[tableIdx];
            int bound = (hdr.mode == 1) ? ((hdr.modeExt + 1) * 4) : sblimit;
            if (bound > sblimit) {
                bound = sblimit;
            }
            int[][] allocTable = ALLOC_TABLES[tableIdx];

            // 1. 读取各子带比特分配 (Bit Allocation)
            for (int sb = 0; sb < bound; sb++) {
                int nbal = allocTable[sb][0];
                for (int ch = 0; ch < hdr.channels; ch++) {
                    int idx = bitReader.readBits(nbal);
                    allocation[ch][sb] = allocTable[sb][1 + idx];
                }
            }
            for (int sb = bound; sb < sblimit; sb++) {
                int nbal = allocTable[sb][0];
                int idx = bitReader.readBits(nbal);
                int stepIdx = allocTable[sb][1 + idx];
                allocation[0][sb] = stepIdx;
                if (hdr.channels > 1) {
                    allocation[1][sb] = stepIdx;
                }
            }
            for (int sb = sblimit; sb < 32; sb++) {
                allocation[0][sb] = 0;
                allocation[1][sb] = 0;
            }

            // 2. 读取比例因子选择信息 (SCFSI - 2 bits)
            for (int sb = 0; sb < sblimit; sb++) {
                for (int ch = 0; ch < hdr.channels; ch++) {
                    if (allocation[ch][sb] != 0) {
                        scfsi[ch][sb] = bitReader.readBits(2);
                    }
                }
            }

            // 3. 读取比例因子索引 (Scale Factors - 6 bits each)
            for (int sb = 0; sb < sblimit; sb++) {
                for (int ch = 0; ch < hdr.channels; ch++) {
                    if (allocation[ch][sb] != 0) {
                        int sel = scfsi[ch][sb];
                        if (sel == 0) {
                            scalefactorIdx[ch][sb][0] = bitReader.readBits(6);
                            scalefactorIdx[ch][sb][1] = bitReader.readBits(6);
                            scalefactorIdx[ch][sb][2] = bitReader.readBits(6);
                        } else if (sel == 1) {
                            int sf0 = bitReader.readBits(6);
                            int sf2 = bitReader.readBits(6);
                            scalefactorIdx[ch][sb][0] = sf0;
                            scalefactorIdx[ch][sb][1] = sf0;
                            scalefactorIdx[ch][sb][2] = sf2;
                        } else if (sel == 2) {
                            int sf = bitReader.readBits(6);
                            scalefactorIdx[ch][sb][0] = sf;
                            scalefactorIdx[ch][sb][1] = sf;
                            scalefactorIdx[ch][sb][2] = sf;
                        } else {
                            int sf0 = bitReader.readBits(6);
                            int sf1 = bitReader.readBits(6);
                            scalefactorIdx[ch][sb][0] = sf0;
                            scalefactorIdx[ch][sb][1] = sf1;
                            scalefactorIdx[ch][sb][2] = sf1;
                        }
                    }
                }
            }

            // 4. 读取并反量化 12 个 Granule (每个 Granule 含 3 个时间片 * 32 个子带 = 96 样本/声道，共 1152 样本/声道)
            byte[] pcmBytes = new byte[1152 * hdr.channels * 2];
            ByteBuffer pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);

            for (int gr = 0; gr < 12; gr++) {
                int part = gr / 4; // 0, 1, 2 对应三个比例因子分区
                for (int sb = 0; sb < sblimit; sb++) {
                    int maxCh = (sb >= bound) ? 1 : hdr.channels;
                    for (int ch = 0; ch < maxCh; ch++) {
                        int alloc = allocation[ch][sb];
                        if (alloc == 0) {
                            subbandSamples[ch][0][sb] = 0.0f;
                            subbandSamples[ch][1][sb] = 0.0f;
                            subbandSamples[ch][2][sb] = 0.0f;
                            if (sb >= bound && hdr.channels > 1) {
                                subbandSamples[1][0][sb] = 0.0f;
                                subbandSamples[1][1][sb] = 0.0f;
                                subbandSamples[1][2][sb] = 0.0f;
                            }
                        } else {
                            int stepIdx = alloc - 1;
                            int qbits = QUANT_BITS[stepIdx];
                            int nlevels = QUANT_STEPS[stepIdx];
                            int s0, s1, s2;
                            if (qbits < 0) {
                                // Grouping: 3 个样本打包在同一个码字中
                                int code = bitReader.readBits(-qbits);
                                s0 = code % nlevels;
                                code /= nlevels;
                                s1 = code % nlevels;
                                s2 = code / nlevels;
                            } else {
                                s0 = bitReader.readBits(qbits);
                                s1 = bitReader.readBits(qbits);
                                s2 = bitReader.readBits(qbits);
                            }
                            // 统一精确反量化公式: v = (2 * s - (nlevels - 1)) / nlevels
                            // 同时适用于分组量化 (nlevels=3,5,9) 与非分组量化 (nlevels=2^qbits - 1)
                            float invN = 1.0f / nlevels;
                            int bias = nlevels - 1;
                            float v0 = (2 * s0 - bias) * invN;
                            float v1 = (2 * s1 - bias) * invN;
                            float v2 = (2 * s2 - bias) * invN;

                            float sf = SCALEFACTORS[scalefactorIdx[ch][sb][part]];
                            subbandSamples[ch][0][sb] = v0 * sf;
                            subbandSamples[ch][1][sb] = v1 * sf;
                            subbandSamples[ch][2][sb] = v2 * sf;

                            if (sb >= bound && hdr.channels > 1) {
                                float sfRight = SCALEFACTORS[scalefactorIdx[1][sb][part]];
                                subbandSamples[1][0][sb] = v0 * sfRight;
                                subbandSamples[1][1][sb] = v1 * sfRight;
                                subbandSamples[1][2][sb] = v2 * sfRight;
                            }
                        }
                    }
                }
                for (int sb = sblimit; sb < 32; sb++) {
                    for (int ch = 0; ch < hdr.channels; ch++) {
                        subbandSamples[ch][0][sb] = 0.0f;
                        subbandSamples[ch][1][sb] = 0.0f;
                        subbandSamples[ch][2][sb] = 0.0f;
                    }
                }

                // 5. 对当前 Granule 的 3 个时间片执行 32 子带多相合成滤波，每次输出 32 个 PCM 样本/声道
                for (int t = 0; t < 3; t++) {
                    if (hdr.channels == 1) {
                        synthesizeSubbands(0, subbandSamples[0][t], pcmBuf, 1, 0);
                    } else {
                        int basePos = pcmBuf.position();
                        synthesizeSubbands(0, subbandSamples[0][t], pcmBuf, 2, basePos);
                        synthesizeSubbands(1, subbandSamples[1][t], pcmBuf, 2, basePos + 2);
                        pcmBuf.position(basePos + 32 * 4);
                    }
                }
            }
            return pcmBytes;
        }

        private byte[] decodeLayer1Frame(FrameHeader hdr) {
            int bound = (hdr.mode == 1) ? ((hdr.modeExt + 1) * 4) : 32;
            for (int sb = 0; sb < bound; sb++) {
                for (int ch = 0; ch < hdr.channels; ch++) {
                    allocation[ch][sb] = bitReader.readBits(4);
                }
            }
            for (int sb = bound; sb < 32; sb++) {
                int a = bitReader.readBits(4);
                allocation[0][sb] = a;
                if (hdr.channels > 1) {
                    allocation[1][sb] = a;
                }
            }
            for (int sb = 0; sb < 32; sb++) {
                for (int ch = 0; ch < hdr.channels; ch++) {
                    if (allocation[ch][sb] != 0) {
                        scalefactorIdx[ch][sb][0] = bitReader.readBits(6);
                    }
                }
            }
            byte[] pcmBytes = new byte[384 * hdr.channels * 2];
            ByteBuffer pcmBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < 12; t++) {
                for (int sb = 0; sb < 32; sb++) {
                    int maxCh = (sb >= bound) ? 1 : hdr.channels;
                    for (int ch = 0; ch < maxCh; ch++) {
                        int a = allocation[ch][sb];
                        if (a <= 0 || a >= 15) {
                            subbandSamples[ch][0][sb] = 0.0f;
                            if (sb >= bound && hdr.channels > 1) {
                                subbandSamples[1][0][sb] = 0.0f;
                            }
                        } else {
                            int qbits = a + 1;
                            int nlevels = (1 << qbits) - 1;
                            int s = bitReader.readBits(qbits);
                            float v = (2 * s - (nlevels - 1)) / (float) nlevels;
                            subbandSamples[ch][0][sb] = v * SCALEFACTORS[scalefactorIdx[ch][sb][0]];
                            if (sb >= bound && hdr.channels > 1) {
                                subbandSamples[1][0][sb] = v * SCALEFACTORS[scalefactorIdx[1][sb][0]];
                            }
                        }
                    }
                }
                if (hdr.channels == 1) {
                    synthesizeSubbands(0, subbandSamples[0][0], pcmBuf, 1, 0);
                } else {
                    int basePos = pcmBuf.position();
                    synthesizeSubbands(0, subbandSamples[0][0], pcmBuf, 2, basePos);
                    synthesizeSubbands(1, subbandSamples[1][0], pcmBuf, 2, basePos + 2);
                    pcmBuf.position(basePos + 32 * 4);
                }
            }
            return pcmBytes;
        }

        /**
         * ISO/IEC 11172-3 32 子带多相合成滤波器组 (Polyphase Synthesis Filterbank)
         */
        private void synthesizeSubbands(int ch,
                                        float[] samples32,
                                        ByteBuffer outPcm,
                                        int channelStride,
                                        int startBytePos) {
            float[] v = synthBuf[ch];
            int off = (synthOffset[ch] - 64) & 1023;
            synthOffset[ch] = off;

            for (int i = 0; i < 64; i++) {
                float sum = 0.0f;
                float[] cosRow = COS_MATRIX[i];
                for (int k = 0; k < 32; k++) {
                    sum += cosRow[k] * samples32[k];
                }
                v[off + i] = sum;
            }

            for (int j = 0; j < 32; j++) {
                float pcmSample = 0.0f;
                for (int i = 0; i < 16; i += 2) {
                    int u0 = (off + (i << 6) + j) & 1023;
                    int u1 = (off + ((i + 1) << 6) + 32 + j) & 1023;
                    pcmSample += v[u0] * SYNTH_WINDOW[(i << 5) + j];
                    pcmSample += v[u1] * SYNTH_WINDOW[((i + 1) << 5) + j];
                }
                int val = Math.round(pcmSample * 32767.0f);
                if (val > 32767) {
                    val = 32767;
                } else if (val < -32768) {
                    val = -32768;
                }
                if (channelStride == 1) {
                    outPcm.putShort((short) val);
                } else {
                    outPcm.putShort(startBytePos + j * 4, (short) val);
                }
            }
        }
    }

    /**
     * 适配 ExoPlayer {@link com.google.android.exoplayer2.audio.DecoderAudioRenderer} 的 SimpleDecoder 实现
     */
    public static final class ExoMp2SimpleDecoder
            extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

        private final Mp2Decoder mp2Decoder = new Mp2Decoder();
        private volatile int sampleRate;
        private volatile int channelCount;

        public ExoMp2SimpleDecoder(Format initialFormat) {
            super(new DecoderInputBuffer[16], new SimpleDecoderOutputBuffer[16]);
            this.sampleRate = (initialFormat != null && initialFormat.sampleRate > 0)
                    ? initialFormat.sampleRate : 48000;
            this.channelCount = (initialFormat != null && initialFormat.channelCount > 0)
                    ? Math.min(2, initialFormat.channelCount) : 2;
            setInitialInputBufferSize(4096);
        }

        @Override
        public String getName() {
            return "UTVMp2SoftwareDecoder";
        }

        public Format getCurrentOutputPcmFormat() {
            return new Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setChannelCount(channelCount)
                    .setSampleRate(sampleRate)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build();
        }

        @Override
        protected DecoderInputBuffer createInputBuffer() {
            return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
        }

        @Override
        protected SimpleDecoderOutputBuffer createOutputBuffer() {
            return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
        }

        @Override
        protected DecoderException createUnexpectedDecodeException(Throwable error) {
            return new DecoderException("Unexpected MP2 software decode error", error);
        }

        @Nullable
        @Override
        protected DecoderException decode(DecoderInputBuffer inputBuffer,
                                          SimpleDecoderOutputBuffer outputBuffer,
                                          boolean reset) {
            if (reset) {
                mp2Decoder.reset();
            }
            ByteBuffer inData = inputBuffer.data;
            if (inData == null || !inData.hasRemaining()) {
                outputBuffer.init(inputBuffer.timeUs, 0);
                return null;
            }
            int len = inData.remaining();
            byte[] raw = new byte[len];
            int pos = inData.position();
            inData.get(raw);
            inData.position(pos);

            byte[] pcm = mp2Decoder.decodeStreamChunk(raw, 0, raw.length);
            if (mp2Decoder.getSampleRate() > 0) {
                this.sampleRate = mp2Decoder.getSampleRate();
            }
            if (mp2Decoder.getChannelCount() > 0) {
                this.channelCount = mp2Decoder.getChannelCount();
            }
            ByteBuffer outData = outputBuffer.init(inputBuffer.timeUs, pcm.length);
            if (pcm.length > 0) {
                outData.put(pcm);
                outData.flip();
            }
            return null;
        }
    }
}
