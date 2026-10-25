package com.bjunicom.iptv.player;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.bjunicom.iptv.config.IptvSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSurfaceView;
import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.AndroidMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class IjkVideoViewRecoveryTest {
    private static final String FIRST = "http://192.168.2.1:8012/rtp/239.3.1.129:8008";
    private static final String SECOND = "http://192.168.2.1:8012/rtp/239.3.1.130:8009";

    private TestView view() {
        Context context = RuntimeEnvironment.getApplication();
        new IptvSettings(context).setPlayerType(IptvSettings.PLAYER_ANDROID_MEDIA);
        TestView view = new TestView(context);
        ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(
                (SurfaceView) view.getChildAt(0)).getFakeSurfaceHolder();
        for (SurfaceHolder.Callback callback : holder.getCallbacks()) callback.surfaceCreated(holder);
        view.setRawChannelUrl("rtp://239.3.1.129:8008");
        view.setAudioCodecHint(null);
        view.setVideoPath(FIRST);
        return view;
    }

    @Test public void unsupportedSystemAudioFallsBackOnSameUriWithoutProxyRetry() {
        TestView view = view();
        AtomicInteger errors = new AtomicInteger();
        view.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        try {
            view.android.info(1005);
            assertEquals(1, view.ijkCreates);
            assertEquals(FIRST, view.ijkUri.toString());
            assertEquals(IptvSettings.PLAYER_IJK, view.getActiveSlot().playerType);
            assertEquals(0, errors.get());
            assertTrue(view.android.released);
            assertEquals(1, view.getPlayerPoolSize());
        } finally { view.stopPlayback(); }
    }

    @Test public void networkFailureDoesNotSwitchDecoder() {
        TestView view = view();
        AtomicInteger errors = new AtomicInteger();
        view.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        try {
            view.android.error(IMediaPlayer.MEDIA_ERROR_IO);
            assertEquals(PlaybackFailure.NETWORK, view.getLastFailure());
            assertEquals(0, view.ijkCreates);
            assertEquals(1, errors.get());
        } finally { view.stopPlayback(); }
    }

    @Test public void capturedOldCallbacksCannotPrepareOrFailTheNewChannel() throws Exception {
        TestView view = view();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger prepared = new AtomicInteger();
        view.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        try {
            IMediaPlayer.OnPreparedListener oldPrepared = listener(view.android, "mOnPreparedListener");
            IMediaPlayer.OnErrorListener oldError = listener(view.android, "mOnErrorListener");
            view.setRawChannelUrl("rtp://239.3.1.130:8009");
            view.setVideoPath(SECOND);
            assertEquals(1, view.androidCreates);
            oldPrepared.onPrepared(view.android);
            oldError.onError(view.android, 1, IMediaPlayer.MEDIA_ERROR_IO);
            assertEquals(0, errors.get());
            assertEquals(0, prepared.get());
            assertEquals(PlaybackFailure.UNKNOWN, view.getLastFailure());
            assertEquals(SECOND, view.getPlaybackUri().toString());
            view.android.prepared();
            assertEquals(1, prepared.get());
        } finally { view.stopPlayback(); }
    }

    @Test public void unknownAudioMetadataDoesNotPretendToBeMp2() {
        TestView view = view();
        try {
            view.android.prepared();
            assertEquals("", view.getFormattedAudioTrackInfo());
        } finally { view.stopPlayback(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T listener(AbstractMediaPlayer player, String name) throws Exception {
        Field field = AbstractMediaPlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(player);
    }

    private static final class TestView extends IjkVideoView {
        RecordingAndroid android;
        int androidCreates;
        int ijkCreates;
        Uri ijkUri;
        TestView(Context context) { super(context); }
        @Override protected IMediaPlayer createConfiguredPlayer(int type, String raw) {
            if (type == IptvSettings.PLAYER_ANDROID_MEDIA) {
                androidCreates++;
                android = new RecordingAndroid();
                return android;
            }
            assertEquals(IptvSettings.PLAYER_IJK, type);
            ijkCreates++;
            return (IMediaPlayer) Proxy.newProxyInstance(IMediaPlayer.class.getClassLoader(),
                    new Class<?>[]{IMediaPlayer.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setDataSource") && args.length >= 2 && args[1] instanceof Uri)
                            ijkUri = (Uri) args[1];
                        Class<?> result = method.getReturnType();
                        if (result == boolean.class) return false;
                        if (result == int.class) return 0;
                        if (result == long.class) return 0L;
                        return null;
                    });
        }
    }

    private static final class RecordingAndroid extends AndroidMediaPlayer {
        boolean released;
        @Override public void setDataSource(Context context, Uri uri, Map<String, String> headers) {}
        @Override public void prepareAsync() {}
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void reset() { resetListeners(); }
        @Override public void setVolume(float left, float right) {}
        @Override public void setScreenOnWhilePlaying(boolean on) {}
        @Override public int getVideoWidth() { return 0; }
        @Override public int getVideoHeight() { return 0; }
        @Override public ITrackInfo[] getTrackInfo() { return new ITrackInfo[0]; }
        @Override public void release() { released = true; super.release(); }
        void info(int info) { notifyOnInfo(info, 0); }
        void error(int extra) { notifyOnError(1, extra); }
        void prepared() { notifyOnPrepared(); }
    }
}
