package com.bjunicom.iptv.player;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.upstream.DataReader;
import com.google.android.exoplayer2.util.ParsableByteArray;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LivePauseFrameBufferTest {

    @Test
    public void pauseExpandsCapacityTo3600AndResumeDrainsBufferedFramesInOrder() {
        LivePauseFrameBuffer buffer = new LivePauseFrameBuffer();
        assertEquals(LivePauseFrameBuffer.DEFAULT_SLOT_CAPACITY, buffer.getSlotCapacity());
        assertFalse(buffer.isPaused());

        RecordingTrackOutput videoOut = new RecordingTrackOutput();
        RecordingTrackOutput audioOut = new RecordingTrackOutput();
        TrackOutput wrappedVideo = buffer.wrapTrackOutput(C.TRACK_TYPE_VIDEO, videoOut);
        TrackOutput wrappedAudio = buffer.wrapTrackOutput(C.TRACK_TYPE_AUDIO, audioOut);

        wrappedVideo.format(new Format.Builder().setSampleMimeType("video/avc").setFrameRate(25f).build());
        wrappedAudio.format(new Format.Builder().setSampleMimeType("audio/mpeg-L2").setSampleRate(48000).build());

        // Pause -> slot capacity expands to 3600 and incoming data is cached in memory
        buffer.onPause();
        assertTrue(buffer.isPaused());
        assertEquals(3600, buffer.getSlotCapacity());

        byte[] vFrame1 = new byte[]{1, 2, 3, 4};
        byte[] vFrame2 = new byte[]{5, 6, 7, 8};
        byte[] aFrame1 = new byte[]{9, 10};

        ParsableByteArray vData1 = new ParsableByteArray(vFrame1);
        wrappedVideo.sampleData(vData1, vFrame1.length);
        wrappedVideo.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, vFrame1.length, 0, null);

        ParsableByteArray vData2 = new ParsableByteArray(vFrame2);
        wrappedVideo.sampleData(vData2, vFrame2.length);
        wrappedVideo.sampleMetadata(40_000L, 0, vFrame2.length, 0, null);

        ParsableByteArray aData1 = new ParsableByteArray(aFrame1);
        wrappedAudio.sampleData(aData1, aFrame1.length);
        wrappedAudio.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, aFrame1.length, 0, null);

        // Nothing delivered to decoder while paused
        assertEquals(0, videoOut.samples.size());
        assertEquals(0, audioOut.samples.size());
        assertEquals(2, buffer.getVideoFrameCount());
        assertEquals(1, buffer.getAudioFrameCount());

        // Resume -> buffered frames are flushed in order to downstream decoder outputs
        buffer.onResume();
        assertFalse(buffer.isPaused());
        assertEquals(0, buffer.getVideoFrameCount());
        assertEquals(0, buffer.getAudioFrameCount());
        assertEquals(2, videoOut.samples.size());
        assertEquals(1, audioOut.samples.size());
        assertArrayEquals(vFrame1, videoOut.samples.get(0));
        assertArrayEquals(vFrame2, videoOut.samples.get(1));
        assertArrayEquals(aFrame1, audioOut.samples.get(0));
    }

    @Test
    public void fullBufferEvictsVideoByGopAndAudioWithMatchingDuration() {
        LivePauseFrameBuffer buffer = new LivePauseFrameBuffer();
        buffer.onPause();
        assertEquals(3600, buffer.getSlotCapacity());

        // 60 GOPs * 60 frames/GOP = 3600 video frames (1 second per GOP = 1,000,000 us)
        int gopFrames = 60;
        long videoDurUs = 20_000L; // 60 * 20_000 = 1,200,000 us per GOP
        long audioDurUs = 24_000L; // 50 * 24_000 = 1,200,000 us per GOP

        for (int gop = 0; gop < 60; gop++) {
            for (int f = 0; f < gopFrames; f++) {
                long pts = (gop * gopFrames + f) * videoDurUs;
                buffer.offerVideoFrame(new byte[]{(byte) gop, (byte) f}, pts, videoDurUs, f == 0);
            }
            for (int a = 0; a < 50; a++) {
                long pts = (gop * 50 + a) * audioDurUs;
                buffer.offerAudioFrame(new byte[]{(byte) gop, (byte) a}, pts, audioDurUs);
            }
        }

        assertEquals(3600, buffer.getVideoFrameCount());
        assertEquals(3000, buffer.getAudioFrameCount());
        assertEquals(0, buffer.getEvictedGopCount());

        // Add 1 more video frame when buffer is at 3600 slots -> evicts oldest GOP (60 video frames = 1,200,000 us)
        // and 50 audio frames (50 * 24,000 = 1,200,000 us)
        buffer.offerVideoFrame(new byte[]{60, 0}, 3600L * videoDurUs, videoDurUs, true);

        assertEquals(1, buffer.getEvictedGopCount());
        assertEquals(60, buffer.getTotalEvictedVideoFrames());
        assertEquals(50, buffer.getTotalEvictedAudioFrames());
        assertEquals(1_200_000L, buffer.getLastEvictedVideoDurationUs());
        assertEquals(1_200_000L, buffer.getLastEvictedAudioDurationUs());
        assertEquals(buffer.getTotalEvictedVideoDurationUs(), buffer.getTotalEvictedAudioDurationUs());
        assertEquals(3600 - 60 + 1, buffer.getVideoFrameCount());
        assertEquals(3000 - 50, buffer.getAudioFrameCount());

        LivePauseFrameBuffer.FrameSlot newFirstVideo = buffer.peekFirstVideoFrame();
        assertNotNull(newFirstVideo);
        assertTrue(newFirstVideo.isKeyFrame);
        assertEquals(1, newFirstVideo.data[0]);
        assertEquals(0, newFirstVideo.data[1]);
    }

    @Test
    public void resumeStarts15xSpeedForVideoAndAudioUntilCatchingUpToLiveTimestamp() {
        LivePauseFrameBuffer buffer = new LivePauseFrameBuffer();
        buffer.onPause();

        // Buffer 3 seconds (3,000,000 us) of video and audio while paused
        for (int i = 0; i < 75; i++) {
            buffer.offerVideoFrame(new byte[]{(byte) i}, i * 40_000L, 40_000L, i % 25 == 0);
        }
        for (int i = 0; i < 125; i++) {
            buffer.offerAudioFrame(new byte[]{(byte) i}, i * 24_000L, 24_000L);
        }

        assertEquals(3_000_000L, buffer.getLatestLiveVideoPtsUs());
        assertEquals(0L, buffer.getCurrentPlaybackVideoPtsUs());

        // Resume -> enters 1.5x catch-up mode for both video and audio
        buffer.onResume();
        assertFalse(buffer.isPaused());
        assertTrue(buffer.isCatchingUp());
        assertEquals(1.5f, buffer.getPlaybackSpeed(), 0.001f);
        assertEquals(1.5f, buffer.getAudioPlaybackSpeed(), 0.001f);
        assertEquals(3_000_000L, buffer.getRemainingCatchUpLagUs());

        // Advance 4 seconds of real time while live stream also advances by 4s (playback advances 4s * 1.5 = 6s):
        // playbackPts = 6.0s, livePts = 3.0s + 4.0s = 7.0s -> still 1.0s behind live
        assertFalse(buffer.advanceCatchUpWithLiveStreamMs(4000L));
        assertTrue(buffer.isCatchingUp());
        assertEquals(1.5f, buffer.getPlaybackSpeed(), 0.001f);
        assertEquals(1.5f, buffer.getAudioPlaybackSpeed(), 0.001f);
        assertEquals(1_000_000L, buffer.getRemainingCatchUpLagUs());

        // Advance another 2 seconds of real time (playback advances 3.0s -> 9.0s, live advances 2.0s -> 9.0s):
        // playback catches up with live stream timestamp -> restores 1.0x speed
        assertTrue(buffer.advanceCatchUpWithLiveStreamMs(2000L));
        assertFalse(buffer.isCatchingUp());
        assertTrue(buffer.hasCaughtUpToLive());
        assertEquals(1.0f, buffer.getPlaybackSpeed(), 0.001f);
        assertEquals(1.0f, buffer.getAudioPlaybackSpeed(), 0.001f);
        assertEquals(0L, buffer.getRemainingCatchUpLagUs());
    }

    private static final class RecordingTrackOutput implements TrackOutput {
        final List<byte[]> samples = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();
        private byte[] pending = new byte[0];

        @Override
        public void format(Format format) {
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            return 0;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            byte[] next = new byte[pending.length + length];
            System.arraycopy(pending, 0, next, 0, pending.length);
            data.readBytes(next, pending.length, length);
            pending = next;
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            byte[] frame = new byte[size];
            System.arraycopy(pending, 0, frame, 0, size);
            samples.add(frame);
            timestamps.add(timeUs);
            pending = new byte[0];
        }
    }
}
