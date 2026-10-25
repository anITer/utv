package com.bjunicom.iptv.player;

import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.extractor.ts.TsPayloadReader;
import com.google.android.exoplayer2.util.TimestampAdjuster;

import java.util.Collections;

/** One live TS extractor, using independently decodable video startup samples. */
public final class IptvTsExtractorsFactory implements ExtractorsFactory {
    @Override
    public Extractor[] createExtractors() {
        int flags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                | DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM;
        DefaultTsPayloadReaderFactory defaults =
                new DefaultTsPayloadReaderFactory(flags, Collections.emptyList());
        TsPayloadReader.Factory primaryTracks = new TsPayloadReader.Factory() {
            private boolean hasVideo;
            private boolean hasAudio;

            @Override
            public android.util.SparseArray<TsPayloadReader> createInitialPayloadReaders() {
                return defaults.createInitialPayloadReaders();
            }

            @Override
            public TsPayloadReader createPayloadReader(int type, TsPayloadReader.EsInfo esInfo) {
                boolean video = type == TsExtractor.TS_STREAM_TYPE_H264
                        || type == TsExtractor.TS_STREAM_TYPE_H265
                        || type == TsExtractor.TS_STREAM_TYPE_H262
                        || type == TsExtractor.TS_STREAM_TYPE_DC2_H262
                        || type == TsExtractor.TS_STREAM_TYPE_H263;
                boolean audio = type == TsExtractor.TS_STREAM_TYPE_MPA
                        || type == TsExtractor.TS_STREAM_TYPE_MPA_LSF
                        || type == TsExtractor.TS_STREAM_TYPE_AAC_ADTS
                        || type == TsExtractor.TS_STREAM_TYPE_AAC_LATM
                        || type == TsExtractor.TS_STREAM_TYPE_AC3
                        || type == TsExtractor.TS_STREAM_TYPE_E_AC3
                        || type == TsExtractor.TS_STREAM_TYPE_AC4
                        || type == TsExtractor.TS_STREAM_TYPE_DTS
                        || type == TsExtractor.TS_STREAM_TYPE_HDMV_DTS;
                if ((!video && !audio) || (video && hasVideo) || (audio && hasAudio)) return null;
                TsPayloadReader reader = defaults.createPayloadReader(type, esInfo);
                if (reader != null) {
                    hasVideo |= video;
                    hasAudio |= audio;
                }
                return reader;
            }
        };
        return new Extractor[]{new TsExtractor(
                TsExtractor.MODE_SINGLE_PMT, new TimestampAdjuster(0), primaryTracks, 112800)};
    }
}
