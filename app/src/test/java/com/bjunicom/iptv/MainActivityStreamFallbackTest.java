package com.bjunicom.iptv;

import android.net.Uri;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.ui.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = MainActivityStreamFallbackTest.TestApplication.class)
public class MainActivityStreamFallbackTest {

    public static class TestApplication extends IptvApplication {
        @Override
        public void onCreate() {
            // URL selection does not require native players or a live proxy.
        }

        @Override
        public int getLocalProxyPort() {
            return 18012;
        }
    }

    @Test
    public void failedChannelDoesNotBypassUdpxyOnNextChannelOrRetry() {
        IptvSettings settings = new IptvSettings(RuntimeEnvironment.getApplication());
        settings.setStreamMode(IptvSettings.STREAM_MODE_UDPXY);
        settings.setUdpxyAddress("192.0.2.1:8012");

        IptvChannel unavailable = M3uParser.buildChannel(1,
                "#EXTINF:-1,Unavailable[1]", "rtp://239.3.1.16:8056");
        IptvChannel available = M3uParser.buildChannel(2,
                "#EXTINF:-1,Available[2]", "rtp://239.3.1.129:8008");

        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            // Without a surface, setVideoPath records the URL without starting network I/O.
            MainActivity activity = controller.create().get();
            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            IMediaPlayer.OnErrorListener onError = ReflectionHelpers.getField(videoView, "mOnErrorListener");

            activity.playChannel(unavailable);
            assertEquals("http://192.0.2.1:8012/rtp/239.3.1.16:8056", playbackUrl(videoView));
            assertTrue(onError.onError(null, 1, 0));
            assertEquals("http://127.0.0.1:18012/rtp/239.3.1.16:8056", playbackUrl(videoView));
            assertTrue(onError.onError(null, 1, 0));

            activity.playChannel(available);
            assertEquals("http://192.0.2.1:8012/rtp/239.3.1.129:8008", playbackUrl(videoView));

            // Each new playback attempt still gets its own local fallback.
            assertTrue(onError.onError(null, 1, 0));
            assertEquals("http://127.0.0.1:18012/rtp/239.3.1.129:8008", playbackUrl(videoView));
            activity.playChannel(available);
            assertEquals("http://192.0.2.1:8012/rtp/239.3.1.129:8008", playbackUrl(videoView));
        }
    }

    private static String playbackUrl(IjkVideoView videoView) {
        Uri uri = ReflectionHelpers.getField(videoView, "mUri");
        return uri.toString();
    }
}
