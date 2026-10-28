package com.bjunicom.iptv.player;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.bjunicom.iptv.config.IptvSettings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSurfaceView;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkLibLoader;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class,
        shadows = IjkVideoViewProbeOptionsTest.ShadowIjk.class)
public class IjkVideoViewProbeOptionsTest {
    @Test public void nativeOptionsAreAppliedOnCreationRecoveryAndReuse() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        IptvSettings settings = new IptvSettings(context);
        settings.setPlayerType(IptvSettings.PLAYER_IJK);
        settings.setMediaCodecEnabled(true);
        IjkVideoView view = new IjkVideoView(context);
        ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(
                (SurfaceView) view.getChildAt(0)).getFakeSurfaceHolder();
        for (SurfaceHolder.Callback callback : holder.getCallbacks()) callback.surfaceCreated(holder);
        try {
            view.setRawChannelUrl("rtp://239.3.1.129:8008");
            view.setVideoPath("http://router:8012/rtp/239.3.1.129:8008");
            view.start();
            IjkMediaPlayer first = (IjkMediaPlayer) view.getActiveSlot().player;
            ShadowIjk firstShadow = Shadow.extract(first);
            assertEquals(Long.valueOf(3000000L), firstShadow.option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration"));
            assertEquals(Long.valueOf(4 * 1024 * 1024L), firstShadow.option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize"));
            assertEquals(Long.valueOf(0), firstShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared"));
            assertEquals(Long.valueOf(1), firstShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc"));
            assertEquals(Long.valueOf(0), firstShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering"));
            firstShadow.meta = IjkStartupPolicyTest.metadata("h264", 0, 0, -99);
            preparedListener(first).onPrepared(first);

            IjkMediaPlayer second = (IjkMediaPlayer) view.getActiveSlot().player;
            ShadowIjk secondShadow = Shadow.extract(second);
            assertNotSame(first, second);
            assertTrue(firstShadow.released);
            assertEquals(0, firstShadow.starts);
            assertEquals(Long.valueOf(6000000L), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration"));
            assertEquals(Long.valueOf(8 * 1024 * 1024L), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize"));
            assertEquals(Long.valueOf(0), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared"));
            assertEquals(Long.valueOf(1), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc"));
            preparedListener(second).onPrepared(second);
            assertEquals(1, secondShadow.starts);

            view.setRawChannelUrl(null);
            view.setVideoPath("https://server/live.m3u8");
            assertSame(second, view.getActiveSlot().player);
            assertEquals(Long.valueOf(400000L), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration"));
            assertEquals(Long.valueOf(512 * 1024L), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize"));
            assertEquals(Long.valueOf(1), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared"));
            assertEquals(Long.valueOf(1), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering"));
            assertEquals(Long.valueOf(0), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf"));
            assertEquals(Long.valueOf(25), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames"));
            view.setRawChannelUrl("rtp://239.3.1.130:8009");
            view.setVideoPath("http://router:8012/rtp/239.3.1.130:8009");
            assertSame(second, view.getActiveSlot().player);
            assertEquals(Long.valueOf(0), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering"));
            assertEquals(Long.valueOf(1), secondShadow.option(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf"));
        } finally { view.stopPlayback(); }
    }

    private static IMediaPlayer.OnPreparedListener preparedListener(IjkMediaPlayer player) throws Exception {
        Field field = AbstractMediaPlayer.class.getDeclaredField("mOnPreparedListener");
        field.setAccessible(true);
        return (IMediaPlayer.OnPreparedListener) field.get(player);
    }

    @Implements(value = IjkMediaPlayer.class, callThroughByDefault = false)
    public static class ShadowIjk {
        final Map<String, Long> options = new HashMap<>();
        Bundle meta = IjkStartupPolicyTest.metadata("h264", 1920, 1080, 100);
        int starts;
        boolean released;

        @Implementation protected void __constructor__() {}
        @Implementation protected void __constructor__(IjkLibLoader loader) {}
        @Implementation protected static void native_setLogLevel(int level) {}
        @Implementation protected void setOption(int category, String name, long value) {
            options.put(category + ":" + name, value);
        }
        @Implementation protected Bundle getMediaMeta() { return meta; }
        @Implementation protected int getVideoWidth() {
            return IjkMediaMeta.parse(meta).mVideoStream.mWidth;
        }
        @Implementation protected int getVideoHeight() {
            return IjkMediaMeta.parse(meta).mVideoStream.mHeight;
        }
        @Implementation protected void start() { starts++; }
        @Implementation protected void reset() { options.clear(); }
        @Implementation protected void release() { released = true; }
        Long option(int category, String name) { return options.get(category + ":" + name); }
    }
}
