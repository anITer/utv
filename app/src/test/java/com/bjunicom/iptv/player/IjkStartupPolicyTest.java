package com.bjunicom.iptv.player;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import tv.danmaku.ijk.media.player.IjkMediaMeta;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class IjkStartupPolicyTest {
    @Test public void directAndProxiedMulticastAllowAFullGopAndOneLargerProbe() {
        for (String url : new String[]{"rtp://239.3.1.129:8008", "udp://@239.3.1.129:8008",
                "http://router:8012/rtp/239.3.1.129:8008",
                "http://127.0.0.1:8888/udp/239.3.1.129:8008"}) {
            IjkStartupPolicy initial = IjkStartupPolicy.forStream(null, Uri.parse(url), false);
            IjkStartupPolicy expanded = IjkStartupPolicy.forStream(null, Uri.parse(url), true);
            assertTrue(initial.multicast);
            assertEquals(3000000L, initial.analyzeDurationUs);
            assertEquals(4 * 1024 * 1024L, initial.probeSizeBytes);
            assertEquals(6000000L, expanded.analyzeDurationUs);
            assertEquals(8 * 1024 * 1024L, expanded.probeSizeBytes);
        }
    }

    @Test public void rawMulticastAndHighBitrateChannelsGetAppropriateProbeBudgets() {
        Uri proxy = Uri.parse("http://router/live.ts");
        IjkStartupPolicy hd = IjkStartupPolicy.forStream("rtp://239.3.1.129:8008", proxy, false);
        IjkStartupPolicy uhd = IjkStartupPolicy.forStream("rtp://239.3.1.150:2000", proxy, false);
        IjkStartupPolicy expanded = IjkStartupPolicy.forStream("rtp://239.3.1.150:2000", proxy, true);
        assertTrue(hd.multicast);
        assertEquals(4000000L, uhd.analyzeDurationUs);
        assertEquals(8 * 1024 * 1024L, uhd.probeSizeBytes);
        assertEquals(8000000L, expanded.analyzeDurationUs);
        assertEquals(16 * 1024 * 1024L, expanded.probeSizeBytes);
    }

    @Test public void ordinaryHttpAndHlsKeepTheirExistingProbeLimits() {
        for (String url : new String[]{"https://server/live.m3u8", "http://server/live.ts",
                "http://server/rtp/192.168.1.1:8008",
                "http://server/page?next=rtp://239.3.1.1:8008"}) {
            IjkStartupPolicy policy = IjkStartupPolicy.forStream(null, Uri.parse(url), true);
            assertFalse(policy.multicast);
            assertEquals(400000L, policy.analyzeDurationUs);
            assertEquals(512 * 1024L, policy.probeSizeBytes);
        }
    }

    @Test public void identifyingMp2DoesNotMakeAnIncompleteVideoStreamReady() {
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 0, 0, -99)));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 0, 100)));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 0, 1080, 100)));
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 1080, 100)));
    }

    @Test public void avcNeedsAParsedProfileEvenWhenDimensionsArePresent() {
        Bundle unknown = metadata("h264", 1920, 1080, -99);
        assertNotNull(IjkStartupPolicy.videoParametersIssue(unknown));
        unknown.<Bundle>getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS).get(0)
                .remove(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID);
        assertNotNull(IjkStartupPolicy.videoParametersIssue(unknown));
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 1080, 578)));
        // A known unsupported hardware profile is not a probing failure.
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("h264", 1920, 1080, 110)));
    }

    @Test public void selectedVideoIsValidatedEvenIfAnotherVideoStreamIsComplete() {
        Bundle meta = metadata("h264", 1920, 1080, 100);
        ArrayList<Bundle> streams = meta.getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS);
        streams.add(metadata("h264", 0, 0, -99)
                .<Bundle>getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS).get(0));
        meta.putString(IjkMediaMeta.IJKM_KEY_VIDEO_STREAM, "2");
        assertNotNull(IjkStartupPolicy.videoParametersIssue(meta));
    }

    @Test public void otherVideoCodecsAndAudioOnlyStreamsDoNotRequireAnAvcProfile() {
        assertNull(IjkStartupPolicy.videoParametersIssue(metadata("hevc", 3840, 2160, -99)));
        assertNull(IjkStartupPolicy.videoParametersIssue(audioOnlyMetadata()));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(null));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(new Bundle()));
        assertNotNull(IjkStartupPolicy.videoParametersIssue(metadata("", 1920, 1080, 100)));
    }

    static Bundle metadata(String codec, int width, int height, int profile) {
        Bundle meta = audioOnlyMetadata();
        Bundle video = new Bundle();
        video.putString(IjkMediaMeta.IJKM_KEY_TYPE, "video");
        video.putString(IjkMediaMeta.IJKM_KEY_CODEC_NAME, codec);
        video.putString(IjkMediaMeta.IJKM_KEY_WIDTH, Integer.toString(width));
        video.putString(IjkMediaMeta.IJKM_KEY_HEIGHT, Integer.toString(height));
        video.putString(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID, Integer.toString(profile));
        meta.<Bundle>getParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS).add(0, video);
        meta.putString(IjkMediaMeta.IJKM_KEY_VIDEO_STREAM, "0");
        meta.putString(IjkMediaMeta.IJKM_KEY_AUDIO_STREAM, "1");
        return meta;
    }

    static Bundle audioOnlyMetadata() {
        Bundle meta = new Bundle();
        Bundle audio = new Bundle();
        audio.putString(IjkMediaMeta.IJKM_KEY_TYPE, "audio");
        audio.putString(IjkMediaMeta.IJKM_KEY_CODEC_NAME, "mp2");
        audio.putString(IjkMediaMeta.IJKM_KEY_SAMPLE_RATE, "48000");
        ArrayList<Bundle> streams = new ArrayList<>();
        streams.add(audio);
        meta.putParcelableArrayList(IjkMediaMeta.IJKM_KEY_STREAMS, streams);
        meta.putString(IjkMediaMeta.IJKM_KEY_VIDEO_STREAM, "-1");
        meta.putString(IjkMediaMeta.IJKM_KEY_AUDIO_STREAM, "0");
        return meta;
    }
}
