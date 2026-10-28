package com.bjunicom.iptv.player;

import android.app.Application;
import android.net.Uri;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaPeriodId;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PlaybackBufferPolicyTest {
    @Test public void routesAndProfilesFollowTheActualProtocolAndIgnoreQueryText() {
        assertProfile(PlaybackBufferPolicy.MULTICAST_TS, "udp://@239.1.1.1:8008", null);
        assertProfile(PlaybackBufferPolicy.MULTICAST_TS, "http://127.0.0.1:9000/rtp/239.1.1.1:8008", null);
        assertProfile(PlaybackBufferPolicy.HTTP_TS, "http://router:9000/rtp/239.1.1.1:8008", null);
        assertProfile(PlaybackBufferPolicy.HTTP_TS, "https://server/LIVE.TS?token=abc", null);
        assertProfile(PlaybackBufferPolicy.HTTP_TS, "https://server/live", "rtp://239.1.1.1:8008");
        assertProfile(PlaybackBufferPolicy.ADAPTIVE, "https://server/LIVE.M3U8?token=abc", null);
        assertProfile(PlaybackBufferPolicy.ADAPTIVE, "https://server/live.mpd", null);
        assertProfile(PlaybackBufferPolicy.ADAPTIVE, "http://127.0.0.1/live", "https://origin/live.m3u8");
        assertProfile(PlaybackBufferPolicy.DEFAULT, "https://server/live?next=live.m3u8", null);
        assertProfile(PlaybackBufferPolicy.DEFAULT, "http://router/rtp/192.168.1.1:8008", null);
        assertProfile(PlaybackBufferPolicy.DEFAULT, "https://server/video.mp4", null);
        assertEquals(PlaybackBufferPolicy.DEFAULT, PlaybackBufferPolicy.forStream(null, null));
    }

    @Test public void sourceTypeAndPolicyAreAttachedToTheSameMediaItem() {
        assertSource(HlsMediaSource.class, PlaybackBufferPolicy.ADAPTIVE, "https://server/live.m3u8");
        assertSource(DashMediaSource.class, PlaybackBufferPolicy.ADAPTIVE, "https://server/live.mpd");
        assertSource(ProgressiveMediaSource.class, PlaybackBufferPolicy.MULTICAST_TS, "rtp://239.1.1.1:8008");
        assertSource(ProgressiveMediaSource.class, PlaybackBufferPolicy.HTTP_TS, "https://server/live.ts");
    }

    @Test public void startupRecoveryAndSpeedUseEachSourcesThresholds() {
        UTVLoadControl control = new UTVLoadControl();
        control.onPrepared();
        for (PlaybackBufferPolicy policy : PlaybackBufferPolicy.values()) {
            Timeline timeline = timeline(policy);
            MediaPeriodId id = new MediaPeriodId(timeline.getUidOfPeriod(0));
            assertFalse(control.shouldStartPlayback(timeline, id, policy.playbackMs * 1000L - 1, 1, false, C.TIME_UNSET));
            assertTrue(control.shouldStartPlayback(timeline, id, policy.playbackMs * 1000L, 1, false, C.TIME_UNSET));
            assertFalse(control.shouldStartPlayback(timeline, id, policy.rebufferMs * 1000L - 1, 1, true, C.TIME_UNSET));
            assertTrue(control.shouldStartPlayback(timeline, id, policy.rebufferMs * 1000L, 1, true, C.TIME_UNSET));
            assertFalse(control.shouldStartPlayback(timeline, id, policy.playbackMs * 1000L, 2, false, C.TIME_UNSET));
            assertTrue(control.shouldStartPlayback(timeline, id, policy.playbackMs * 2000L, 2, false, C.TIME_UNSET));
        }
        control.onReleased();
    }

    @Test public void fastSwitchingDoesNotLeakStartupPolicyIntoTheLoadingPeriod() {
        UTVLoadControl control = new UTVLoadControl();
        control.onPrepared();
        Timeline multicast = timeline(PlaybackBufferPolicy.MULTICAST_TS);
        Timeline http = timeline(PlaybackBufferPolicy.HTTP_TS);
        Timeline adaptive = timeline(PlaybackBufferPolicy.ADAPTIVE);
        tracks(control, adaptive);
        assertTrue(control.shouldContinueLoading(0, 1000000, 1));
        assertTrue(start(control, multicast, 120000));
        assertFalse(start(control, adaptive, 120000));
        // A startup query about another period must not select the loading policy.
        assertTrue(control.shouldContinueLoading(0, 1000000, 1));
        tracks(control, multicast);
        assertFalse(control.shouldContinueLoading(0, 5000000, 1));
        assertTrue(control.shouldContinueLoading(0, 700000, 1));
        tracks(control, http);
        assertTrue(control.shouldContinueLoading(0, 1000000, 1));
        assertFalse(control.shouldContinueLoading(0, 7000000, 1));
        assertFalse(start(control, http, 120000));
        assertTrue(start(control, http, 500000));
        assertEquals(1500000L, control.getBackBufferDurationUs());
        assertTrue(control.retainBackBufferFromKeyframe());
        assertSame(control.getSharedAllocator(), control.getAllocator());
        control.onStopped();
        assertTrue(control.shouldContinueLoading(0, 1000000, 1));
        control.onReleased();
    }

    @Test public void adaptiveLiveOffsetStillUsesExosLiveWindowRule() {
        UTVLoadControl control = new UTVLoadControl();
        Timeline adaptive = timeline(PlaybackBufferPolicy.ADAPTIVE);
        assertTrue(control.shouldStartPlayback(adaptive, new MediaPeriodId(adaptive.getUidOfPeriod(0)),
                2000000, 1, true, 4000000));
        control.onReleased();
    }

    private static void assertProfile(PlaybackBufferPolicy expected, String url, String raw) {
        assertEquals(url, expected, PlaybackBufferPolicy.forStream(Uri.parse(url), raw));
    }
    private static void assertSource(Class<?> type, PlaybackBufferPolicy policy, String url) {
        MediaSource source = PlaybackSourceFactory.create(Uri.parse(url), null, "request-1", new UTVStreamDataSource.Factory());
        assertTrue(type.isInstance(source));
        assertEquals("request-1", source.getMediaItem().mediaId);
        assertSame(policy, source.getMediaItem().localConfiguration.tag);
    }
    private static Timeline timeline(PlaybackBufferPolicy policy) {
        MediaItem item = new MediaItem.Builder().setUri("https://server/live").setTag(policy).build();
        return new SinglePeriodTimeline(60000000L, true, false, false, null, item);
    }
    private static boolean start(UTVLoadControl control, Timeline timeline, long bufferUs) {
        return control.shouldStartPlayback(timeline, new MediaPeriodId(timeline.getUidOfPeriod(0)),
                bufferUs, 1, false, C.TIME_UNSET);
    }
    private static void tracks(UTVLoadControl control, Timeline timeline) {
        control.onTracksSelected(timeline, new MediaPeriodId(timeline.getUidOfPeriod(0)),
                new Renderer[0], TrackGroupArray.EMPTY, new ExoTrackSelection[0]);
    }
}
