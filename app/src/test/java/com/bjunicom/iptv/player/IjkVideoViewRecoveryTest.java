package com.bjunicom.iptv.player;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
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
import org.robolectric.shadows.ShadowSurfaceView;
import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.AndroidMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
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

    private TestView ijkView(String path, String rawUrl) {
        Context context = RuntimeEnvironment.getApplication();
        IptvSettings settings = new IptvSettings(context);
        settings.setPlayerType(IptvSettings.PLAYER_IJK);
        settings.setMediaCodecEnabled(true);
        TestView view = new TestView(context);
        ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(
                (SurfaceView) view.getChildAt(0)).getFakeSurfaceHolder();
        for (SurfaceHolder.Callback callback : holder.getCallbacks()) callback.surfaceCreated(holder);
        view.setRawChannelUrl(rawUrl);
        view.setVideoURI(Uri.parse(path), Collections.singletonMap("User-Agent", "test-player"));
        view.start();
        return view;
    }

    @Test public void incompleteVideoRecreatesIjkWithLargerProbeAndPreservesTheRequest() throws Exception {
        TestView view = ijkView(FIRST, "rtp://239.3.1.129:8008");
        AtomicInteger prepared = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        view.setOnErrorListener((mp, what, extra) -> { errors.incrementAndGet(); return true; });
        try {
            RecordingIjk first = view.ijk;
            first.meta = IjkStartupPolicyTest.metadata("h264", 0, 0, -99);
            IMediaPlayer.OnPreparedListener stalePrepared = first.onPrepared;
            first.prepared();
            assertEquals(2, view.ijkCreates);
            assertTrue(first.released);
            assertEquals(0, first.starts);
            assertEquals(0, prepared.get());
            assertEquals(0, errors.get());
            assertEquals(IjkVideoView.STATE_PREPARING, view.getActiveSlot().state);
            assertEquals(FIRST, view.ijk.uri.toString());
            assertEquals("test-player", view.ijk.headers.get("User-Agent"));
            assertEquals(3000000L, first.policy.analyzeDurationUs);
            assertEquals(6000000L, view.ijk.policy.analyzeDurationUs);
            assertEquals(8 * 1024 * 1024L, view.ijk.policy.probeSizeBytes);
            assertFalse(recovery(view).forceSoftwareVideo());
            assertTrue(view.getStartupMetricsSummary().startsWith("request=1 "));
            stalePrepared.onPrepared(first.proxy);
            assertEquals(2, view.ijkCreates);
            view.ijk.prepared();
            assertEquals(1, prepared.get());
            assertEquals(1, view.ijk.starts);
            assertEquals(IjkVideoView.STATE_PLAYING, view.getActiveSlot().state);
            assertEquals(1, view.getPlayerPoolSize());
        } finally { view.stopPlayback(); }
    }

    @Test public void incompleteExpandedProbeReportsOneFormatErrorAndReleasesThePlayer() {
        TestView view = ijkView(FIRST, "rtp://239.3.1.129:8008");
        AtomicInteger prepared = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        view.setOnErrorListener((mp, what, extra) -> {
            assertEquals(IMediaPlayer.MEDIA_ERROR_MALFORMED, extra);
            errors.incrementAndGet();
            return true;
        });
        try {
            view.ijk.meta = IjkStartupPolicyTest.metadata("h264", 1920, 1080, -99);
            view.ijk.prepared();
            RecordingIjk second = view.ijk;
            second.meta = IjkStartupPolicyTest.metadata("h264", 1920, 1080, -99);
            IMediaPlayer.OnPreparedListener stale = second.onPrepared;
            second.prepared();
            assertTrue(second.released);
            assertEquals(0, second.starts);
            assertEquals(2, view.ijkCreates);
            assertEquals(0, prepared.get());
            assertEquals(1, errors.get());
            assertEquals(PlaybackFailure.FORMAT, view.getLastFailure());
            assertEquals(IjkVideoView.STATE_ERROR, view.getActiveSlot().state);
            assertNull(view.getActiveSlot().player);
            assertEquals(FIRST, view.getPlaybackUri().toString());
            stale.onPrepared(second.proxy);
            assertEquals(1, errors.get());
            assertEquals(2, view.ijkCreates);
        } finally { view.stopPlayback(); }
    }

    @Test public void switchingChannelsResetsTheProbeBudgetAndIgnoresOldPreparation() throws Exception {
        TestView view = ijkView(FIRST, "rtp://239.3.1.129:8008");
        AtomicInteger prepared = new AtomicInteger();
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        try {
            view.ijk.meta = null;
            view.ijk.prepared();
            assertTrue(recovery(view).expandedProbe());
            IMediaPlayer.OnPreparedListener old = view.ijk.onPrepared;
            IMediaPlayer samePlayer = view.ijk.proxy;
            view.setRawChannelUrl("rtp://239.3.1.130:8009");
            view.setVideoPath(SECOND);
            assertFalse(recovery(view).expandedProbe());
            old.onPrepared(samePlayer);
            assertEquals(0, prepared.get());
            view.ijk.meta = IjkStartupPolicyTest.metadata("h264", 0, 0, -99);
            view.ijk.prepared();
            assertEquals(3, view.ijkCreates);
            assertEquals(SECOND, view.ijk.uri.toString());
            assertTrue(recovery(view).expandedProbe());
            view.ijk.prepared();
            assertEquals(1, prepared.get());
        } finally { view.stopPlayback(); }
    }

    @Test public void ordinaryHttpPlaybackDoesNotUseTheMulticastPreparationGate() {
        TestView view = ijkView("https://server/live.m3u8", null);
        AtomicInteger prepared = new AtomicInteger();
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        try {
            view.ijk.meta = null;
            view.ijk.prepared();
            assertEquals(1, view.ijkCreates);
            assertEquals(1, prepared.get());
            assertFalse(view.ijk.policy.multicast);
            assertEquals(400000L, view.ijk.policy.analyzeDurationUs);
        } finally { view.stopPlayback(); }
    }

    @Test public void pausingDuringProbeRecoveryPreventsAutomaticPlayback() {
        TestView view = ijkView(FIRST, "rtp://239.3.1.129:8008");
        try {
            view.ijk.meta = null;
            view.ijk.prepared();
            view.pause();
            view.ijk.prepared();
            assertEquals(0, view.ijk.starts);
            assertEquals(IjkVideoView.STATE_PREPARED, view.getActiveSlot().state);
        } finally { view.stopPlayback(); }
    }

    @Test public void audioOnlyMp2DoesNotTriggerVideoProbeRecovery() {
        TestView view = ijkView(FIRST, "rtp://239.3.1.129:8008");
        AtomicInteger prepared = new AtomicInteger();
        view.setOnPreparedListener(mp -> prepared.incrementAndGet());
        try {
            view.ijk.meta = IjkStartupPolicyTest.audioOnlyMetadata();
            view.ijk.prepared();
            assertEquals(1, view.ijkCreates);
            assertEquals(1, prepared.get());
            assertEquals(1, view.ijk.starts);
        } finally { view.stopPlayback(); }
    }

    @Test public void systemAudioFallbackCanExpandIjkProbeWithoutChangingItsBackend() {
        TestView view = view();
        try {
            view.android.info(1005);
            view.ijk.meta = IjkStartupPolicyTest.metadata("h264", 0, 0, -99);
            view.ijk.prepared();
            assertEquals(2, view.ijkCreates);
            assertEquals(1, view.androidCreates);
            assertEquals(IptvSettings.PLAYER_IJK, view.getActiveSlot().playerType);
            assertEquals(FIRST, view.ijk.uri.toString());
            view.ijk.prepared();
            assertEquals(IjkVideoView.STATE_PREPARED, view.getActiveSlot().state);
        } finally { view.stopPlayback(); }
    }

    private static boolean booleanField(IjkVideoView view, String name) throws Exception {
        Field field = IjkVideoView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(view);
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
        RecordingIjk ijk;
        TestView(Context context) { super(context); }
        @Override protected IMediaPlayer createConfiguredPlayer(int type, String raw) {
            if (type == IptvSettings.PLAYER_ANDROID_MEDIA) {
                androidCreates++;
                android = new RecordingAndroid();
                return android;
            }
            assertEquals(IptvSettings.PLAYER_IJK, type);
            ijkCreates++;
            try {
                ijk = new RecordingIjk(this, IjkStartupPolicy.forStream(raw, getPlaybackUri(),
                        recovery(this).expandedProbe()));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            return ijk.proxy;
        }
        @Override protected Bundle readIjkMediaMeta(IMediaPlayer player) { return ijk.meta; }
    }

    private static PlaybackRecovery recovery(IjkVideoView view) throws Exception {
        Field field = IjkVideoView.class.getDeclaredField("mRecovery");
        field.setAccessible(true);
        return (PlaybackRecovery) field.get(view);
    }

    private static final class RecordingIjk implements InvocationHandler {
        final TestView view;
        final IjkStartupPolicy policy;
        final IMediaPlayer proxy;
        Bundle meta = IjkStartupPolicyTest.metadata("h264", 1920, 1080, 100);
        Uri uri;
        Map<String, String> headers;
        IMediaPlayer.OnPreparedListener onPrepared;
        int starts;
        boolean released;

        RecordingIjk(TestView view, IjkStartupPolicy policy) {
            this.view = view;
            this.policy = policy;
            proxy = (IMediaPlayer) Proxy.newProxyInstance(IMediaPlayer.class.getClassLoader(),
                    new Class<?>[]{IMediaPlayer.class}, this);
        }

        @SuppressWarnings("unchecked")
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setOnPreparedListener": onPrepared = (IMediaPlayer.OnPreparedListener) args[0]; break;
                case "setDataSource":
                    if (args.length >= 2 && args[1] instanceof Uri) {
                        uri = (Uri) args[1];
                        view.ijkUri = uri;
                        headers = (Map<String, String>) args[2];
                    }
                    break;
                case "start": starts++; break;
                case "reset": onPrepared = null; meta = null; break;
                case "release": released = true; break;
                case "getVideoWidth":
                case "getVideoHeight":
                    IjkMediaMeta parsed = IjkMediaMeta.parse(meta);
                    return parsed == null || parsed.mVideoStream == null ? 0
                            : method.getName().equals("getVideoWidth")
                            ? parsed.mVideoStream.mWidth : parsed.mVideoStream.mHeight;
                case "toString": return "RecordingIjk";
            }
            Class<?> result = method.getReturnType();
            if (result == boolean.class) return false;
            if (result == int.class) return 0;
            if (result == long.class) return 0L;
            if (result == float.class) return 0f;
            return null;
        }

        void prepared() { onPrepared.onPrepared(proxy); }
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
