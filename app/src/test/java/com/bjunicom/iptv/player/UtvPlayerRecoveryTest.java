package com.bjunicom.iptv.player;

import android.app.Application;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class UtvPlayerRecoveryTest {
    @Test public void oldExoTimelineCannotDeliverPreparedOrDecoderFailureToNewRequest() throws Exception {
        UTVPlayer player = new UTVPlayer(RuntimeEnvironment.getApplication());
        AtomicInteger prepared = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        player.setOnPreparedListener(mp -> prepared.incrementAndGet());
        player.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        try {
            field("currentMediaId").set(player, "new");
            AnalyticsListener listener = (AnalyticsListener) field("playerListener").get(player);
            listener.onPlaybackStateChanged(event("old"), Player.STATE_READY);
            listener.onPlayerError(event("old"), new PlaybackException("old decoder", null,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED));
            assertEquals(0, errors.get());
            assertEquals(0, prepared.get());
            listener.onPlaybackStateChanged(event("new"), Player.STATE_READY);
            assertEquals(1, prepared.get());
        } finally { player.releasePlayer(); }
    }

    @Test public void mp2HardwareErrorSelectsSoftwareAudioOnSamePlayerAndSurface() throws Exception {
        UTVPlayer player = new UTVPlayer(RuntimeEnvironment.getApplication());
        Object originalExo = player.getExoPlayer();
        AtomicInteger errors = new AtomicInteger();
        player.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        try {
            field("currentMediaId").set(player, "mp2");
            field("attachedToPlaceholderSurface").set(player, false);
            List<?> hardwareIndices = (List<?>) field("mediaCodecAudioRenderers").get(player);
            int audioIndex = (Integer) hardwareIndices.get(0);
            Format format = new Format.Builder().setSampleMimeType("audio/mpeg-L2")
                    .setChannelCount(2).setSampleRate(48000).build();
            AnalyticsListener listener = (AnalyticsListener) field("playerListener").get(player);
            listener.onPlayerError(event("mp2"), ExoPlaybackException.createForRenderer(
                    new IllegalStateException("MP2 device codec failed"), "MediaCodecAudioRenderer",
                    audioIndex, format, C.FORMAT_HANDLED, false, PlaybackException.ERROR_CODE_DECODING_FAILED));
            assertSame(originalExo, player.getExoPlayer());
            assertFalse(player.isAttachedToPlaceholderSurface());
            assertEquals(0, errors.get());
            assertTrue(field("forceMp2Software").getBoolean(player));
            DefaultTrackSelector selector = (DefaultTrackSelector) field("trackSelector").get(player);
            assertTrue(selector.getParameters().getRendererDisabled(audioIndex));
            assertFalse(selector.getParameters().getRendererDisabled(
                    field("mp2AudioRendererIndex").getInt(player)));
        } finally { player.releasePlayer(); }
    }

    private static AnalyticsListener.EventTime event(String id) {
        MediaItem item = new MediaItem.Builder().setUri("http://example.test/live.ts").setMediaId(id).build();
        Timeline timeline = new SinglePeriodTimeline(C.TIME_UNSET, false, true, false, null, item);
        return new AnalyticsListener.EventTime(0, timeline, 0, null, 0, timeline, 0, null, 0, 0);
    }

    private static Field field(String name) throws Exception {
        Field field = UTVPlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
