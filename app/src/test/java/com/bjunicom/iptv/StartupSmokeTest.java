package com.bjunicom.iptv;

import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.player.StreamDownloaderPool;
import com.bjunicom.iptv.player.TsCodecDescriptor;
import com.bjunicom.iptv.ui.MainActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSurfaceView;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = IptvApplication.class)
public class StartupSmokeTest {

    @Test
    public void testMainActivityStartupAndSurfaceCreated() {
        ShadowLog.stream = System.out;
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            SurfaceView surfaceView = (SurfaceView) videoView.getChildAt(0);
            ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(surfaceView).getFakeSurfaceHolder();
            for (SurfaceHolder.Callback cb : holder.getCallbacks()) {
                cb.surfaceCreated(holder);
                cb.surfaceChanged(holder, 1, 1920, 1080);
            }

            Robolectric.flushForegroundThreadScheduler();
        }
    }

    @Test
    public void testStreamDownloaderPoolAndCodecDescriptorProfile() {
        TsCodecDescriptor hdDesc = TsCodecDescriptor.createDefault(false);
        assertEquals("h264", hdDesc.videoCodec);
        assertEquals("mp2", hdDesc.audioCodec);
        assertFalse(hdDesc.isHevc());
        assertFalse(hdDesc.requiresHardwareAudioCompanion());

        TsCodecDescriptor uhdDesc = TsCodecDescriptor.createDefault(true);
        assertEquals("hevc", uhdDesc.videoCodec);
        assertEquals("eac3", uhdDesc.audioCodec);
        assertTrue(uhdDesc.isHevc());
        assertTrue(uhdDesc.requiresHardwareAudioCompanion());

        StreamDownloaderPool pool = StreamDownloaderPool.getInstance();
        StreamDownloaderPool.PooledStreamDownloader dl1 = pool.acquire();
        StreamDownloaderPool.PooledStreamDownloader dl2 = pool.acquire();
        assertNotNull(dl1);
        assertNotNull(dl2);
        pool.release(dl1);
        pool.release(dl2);

        StreamDownloaderPool.PooledStreamDownloader reused1 = pool.acquire();
        StreamDownloaderPool.PooledStreamDownloader reused2 = pool.acquire();
        assertSame(dl1, reused1);
        assertSame(dl2, reused2);
        pool.release(reused1);
        pool.release(reused2);
    }
}
