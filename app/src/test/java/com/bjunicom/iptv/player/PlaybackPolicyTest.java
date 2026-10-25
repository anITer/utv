package com.bjunicom.iptv.player;

import android.app.Application;
import android.content.Context;
import android.net.Uri;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.network.TransportRouteCache;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.video.ColorInfo;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PlaybackPolicyTest {
    @Test public void networkFailuresAreNotConfusedWithUnsupportedFormatsOrAudioDecoders() {
        assertEquals(PlaybackFailure.NETWORK, PlaybackFailure.fromLegacy(1, -110));
        assertEquals(PlaybackFailure.NETWORK, PlaybackFailure.fromLegacy(1, -1004));
        assertEquals(PlaybackFailure.FORMAT, PlaybackFailure.fromLegacy(1, -1010));
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackFailure.fromLegacy(1, 0));
        assertEquals(PlaybackFailure.AUDIO_DECODER, PlaybackFailure.fromExo(
                new PlaybackException("MP2", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), true));
        assertEquals(PlaybackFailure.VIDEO_DECODER, PlaybackFailure.fromExo(
                new PlaybackException("video", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), false));
        assertEquals(PlaybackFailure.FORMAT, PlaybackFailure.fromExo(
                new PlaybackException("TS", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED), false));
    }

    @Test public void formatHttpStatusAndCancellationAreNotNetworkFallbacks() {
        assertEquals(PlaybackFailure.FORMAT, PlaybackFailure.fromException(
                com.google.android.exoplayer2.ParserException.createForMalformedContainer("bad TS", null)));
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackFailure.fromException(new java.io.IOException("unknown IO")));
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackFailure.fromException(new java.io.InterruptedIOException()));
        assertEquals(PlaybackFailure.NETWORK, PlaybackFailure.fromException(new java.net.SocketTimeoutException()));
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackFailure.fromExo(
                new PlaybackException("HTTP 403", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS), false));
    }

    @Test public void onlyValidLiteralMulticastTargetsCanBypassAProxy() {
        assertEquals("239.3.1.1:8008", UTVStreamDataSource.extractMulticastTarget("udp://@239.3.1.1:8008"));
        assertNull(UTVStreamDataSource.extractMulticastTarget("http://router/rtp/192.168.1.1:8008"));
        assertNull(UTVStreamDataSource.extractMulticastTarget("rtp://239.3.1.999:8008"));
        assertNull(UTVStreamDataSource.extractMulticastTarget("rtp://239.3.1.1:65536"));
        assertNull(UTVStreamDataSource.extractMulticastTarget("rtp://239.3.1.1:0"));
        assertNull(UTVStreamDataSource.extractMulticastTarget("http://router/page?next=rtp://239.3.1.1:8008"));
        assertFalse(TransportRouteCache.isExternalProxy(Uri.parse("http://router/rtp/192.168.1.1:8008")));
    }

    @Test public void failedProxyIsSharedAcrossChannelsButExpiresAndDoesNotAffectOtherEndpoints() {
        TransportRouteCache.clear();
        Uri first = Uri.parse("http://192.168.2.1:8012/rtp/239.3.1.129:8008");
        Uri next = Uri.parse("http://192.168.2.1:8012/rtp/239.3.1.130:8009");
        TransportRouteCache.recordNetworkFailure(first);
        assertTrue(TransportRouteCache.shouldBypass(next));
        assertFalse(TransportRouteCache.shouldBypass(Uri.parse("http://192.168.2.2:8012/rtp/239.3.1.130:8009")));
        assertFalse(TransportRouteCache.shouldBypass(Uri.parse("http://192.168.2.1:8012/index.m3u8")));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6));
        assertFalse(TransportRouteCache.shouldBypass(next));
        TransportRouteCache.recordNetworkFailure(first);
        TransportRouteCache.clear();
        assertFalse(TransportRouteCache.shouldBypass(first));
    }

    @Test public void decoderFailureCacheAvoidsRetryingBrokenHardwareButRetainsSoftwareFallback() {
        MediaCodecInfo hw = codec("c2.test.failed.avc.decoder", true);
        MediaCodecInfo sw = codec("c2.android.avc.decoder", false);
        IptvSettings settings = new IptvSettings(RuntimeEnvironment.getApplication());
        settings.setMediaCodecEnabled(true);
        assertSame(hw, UTVPlayer.selectDecodersForSettings(Arrays.asList(sw, hw), "video/avc", settings).get(0));
        DecoderPolicy.recordFailure("video/avc", hw.name);
        assertEquals(Arrays.asList(sw),
                UTVPlayer.selectDecodersForSettings(Arrays.asList(hw, sw), "video/avc", settings));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6));
        assertSame(hw, UTVPlayer.selectDecodersForSettings(Arrays.asList(sw, hw), "video/avc", settings).get(0));
    }

    @Test public void reusePreservesDeviceResolutionColorAndInputSizeLimits() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        IptvSettings settings = new IptvSettings(context);
        settings.setMediaCodecEnabled(true);
        UTVPlayer player = new UTVPlayer(context);
        try {
            UTVPlayer.AdaptiveMediaCodecVideoRenderer renderer = new UTVPlayer.AdaptiveMediaCodecVideoRenderer(
                    context, MediaCodecAdapter.Factory.DEFAULT, MediaCodecSelector.DEFAULT,
                    1500, true, null, null, 50, player);
            MediaCodecInfo hw = codec("c2.test.safe.avc.decoder", true);
            Format hd = new Format.Builder().setSampleMimeType("video/avc").setWidth(1920).setHeight(1080).build();
            initializeCodecBounds(renderer, hw, hd);
            assertNotEquals(0, renderer.canReuseCodec(hw, hd, hd).result);
            Format sd = hd.buildUpon().setWidth(1280).setHeight(720).build();
            assertEquals(0, renderer.canReuseCodec(hw, hd, sd).result);
            Format hdr = hd.buildUpon().setColorInfo(new ColorInfo(
                    C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_ST2084, null)).build();
            assertEquals(0, renderer.canReuseCodec(hw, hd, hdr).result);
            Format largeInput = hd.buildUpon().setMaxInputSize(16 * 1024 * 1024).build();
            assertEquals(0, renderer.canReuseCodec(hw, hd, largeInput).result);
            settings.setMediaCodecEnabled(false);
            assertEquals(0, renderer.canReuseCodec(hw, hd, hd).result);
            settings.setMediaCodecEnabled(true);
            MediaCodecInfo sw = codec("c2.android.avc.decoder", false);
            initializeCodecBounds(renderer, sw, hd);
            assertNotEquals(0, renderer.canReuseCodec(sw, hd, hd).result);
        } finally {
            player.releasePlayer();
        }
    }

    public static void initializeCodecBounds(MediaCodecVideoRenderer renderer, MediaCodecInfo codec, Format format)
            throws Exception {
        Method calculate = MediaCodecVideoRenderer.class.getDeclaredMethod(
                "getCodecMaxValues", MediaCodecInfo.class, Format.class, Format[].class);
        calculate.setAccessible(true);
        Object bounds = calculate.invoke(renderer, codec, format, new Format[]{format});
        Field width = bounds.getClass().getDeclaredField("width");
        Field height = bounds.getClass().getDeclaredField("height");
        width.setAccessible(true);
        height.setAccessible(true);
        assertEquals(format.width, width.getInt(bounds));
        assertEquals(format.height, height.getInt(bounds));
        Field configured = MediaCodecVideoRenderer.class.getDeclaredField("codecMaxValues");
        configured.setAccessible(true);
        configured.set(renderer, bounds);
    }

    private static MediaCodecInfo codec(String name, boolean hardware) {
        return MediaCodecInfo.newInstance(name, "video/avc", "video/avc", null,
                hardware, !hardware, true, false, false);
    }

    @Test public void firstFrameMetricIncludesPreparationAndIgnoresDuplicateEvents() {
        PlaybackMetrics metrics = new PlaybackMetrics(7);
        assertEquals(-1, metrics.getFirstVideoMs());
        ShadowSystemClock.advanceBy(Duration.ofMillis(50));
        metrics.prepared();
        ShadowSystemClock.advanceBy(Duration.ofMillis(73));
        metrics.onInfo(tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START);
        assertEquals(123, metrics.getFirstVideoMs());
        ShadowSystemClock.advanceBy(Duration.ofMillis(20));
        metrics.onInfo(tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START);
        assertEquals(123, metrics.getFirstVideoMs());
    }

    @Test public void realMp2SampleMatchesReferencePcm() throws Exception {
        byte[] encoded = resource("/audio/mp2-sine-48k-stereo.mp2");
        byte[] expected = resource("/audio/mp2-sine-48k-stereo.pcm");
        Mp2SoftwareDecoder.Mp2Decoder decoder = new Mp2SoftwareDecoder.Mp2Decoder();
        try {
            byte[] actual = decoder.decodeStreamChunk(encoded, 0, encoded.length);
            assertEquals(expected.length, actual.length);
            double squaredError = 0;
            double referenceEnergy = 0;
            for (int i = 0; i < expected.length; i += 2) {
                short reference = (short) ((expected[i] & 0xff) | (expected[i + 1] << 8));
                short decoded = (short) ((actual[i] & 0xff) | (actual[i + 1] << 8));
                squaredError += Math.pow(decoded - reference, 2);
                referenceEnergy += (double) reference * reference;
            }
            double relativeError = Math.sqrt(squaredError / referenceEnergy);
            assertTrue("MP2 PCM relative RMS error=" + relativeError, relativeError < 0.005);
        } finally {
            decoder.release();
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = PlaybackPolicyTest.class.getResourceAsStream(name)) {
            assertNotNull(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count);
            return output.toByteArray();
        }
    }
}
