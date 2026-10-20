package com.bjunicom.iptv;

import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.bjunicom.iptv.player.IjkVideoView;
import com.bjunicom.iptv.player.StreamDownloaderPool;
import com.bjunicom.iptv.player.TsCodecDescriptor;
import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.repository.ChannelRepository;
import com.bjunicom.iptv.ui.MainActivity;

import java.util.List;

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

    @Test
    public void testSwitchFromIjkToMediaPlayerPreservesRenderViewAndSurface() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            SurfaceView initialSurfaceView = (SurfaceView) videoView.getChildAt(0);
            ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(initialSurfaceView).getFakeSurfaceHolder();
            for (SurfaceHolder.Callback cb : holder.getCallbacks()) {
                cb.surfaceCreated(holder);
                cb.surfaceChanged(holder, 1, 1920, 1080);
            }

            // Calling setRender with the same render type should not recreate the SurfaceView
            videoView.setRender(IjkVideoView.RENDER_SURFACE_VIEW);
            assertSame(initialSurfaceView, videoView.getChildAt(0));

            // Switching to TextureView and back should clear stale surface holder and recreate cleanly
            videoView.setRender(IjkVideoView.RENDER_TEXTURE_VIEW);
            assertFalse(videoView.getChildAt(0) instanceof SurfaceView);
            videoView.setRender(IjkVideoView.RENDER_SURFACE_VIEW);
            assertTrue(videoView.getChildAt(0) instanceof SurfaceView);
        }
    }

    @Test
    public void testPlaybackLeftRightKeyMovesHudButtonFocus() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            int[] expectedOrder = {
                    R.id.btn_toggle_drawer,
                    R.id.btn_open_scanner,
                    R.id.btn_open_settings,
                    R.id.btn_prev_channel,
                    R.id.btn_next_channel,
                    R.id.btn_numpad_dialog,
                    R.id.btn_favorite_channel,
                    R.id.btn_aspect_ratio,
                    R.id.btn_reload_stream
            };

            // Playback starts without covering the picture; right reveals the HUD.
            assertNotNull(activity.getFocusedHudButton());
            assertEquals(expectedOrder[0], activity.getFocusedHudButton().getId());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
            assertTrue(activity.getFocusedHudButton().isSelected());

            // Pressing DPAD_RIGHT steps through all HUD buttons and wraps around
            for (int i = 1; i <= expectedOrder.length; i++) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                int expectedId = expectedOrder[i % expectedOrder.length];
                assertEquals(expectedId, activity.getFocusedHudButton().getId());
                assertTrue(activity.getFocusedHudButton().isSelected());
                assertTrue(activity.getFocusedHudButton().getScaleX() > 1.0f);
            }

            // Pressing DPAD_LEFT moves focus backwards
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals(R.id.btn_reload_stream, activity.getFocusedHudButton().getId());
            assertTrue(activity.getFocusedHudButton().isSelected());
        }
    }

    @Test
    public void testRemoteUpDownWrapChannelsWithoutShowingHud() {
        List<IptvChannel> channels = ChannelRepository.getInstance(
                org.robolectric.RuntimeEnvironment.getApplication()).getAllChannels();
        assertTrue(channels.size() > 2);
        IptvSettings settings = new IptvSettings(org.robolectric.RuntimeEnvironment.getApplication());
        settings.setLastChannelUrl(channels.get(0).getRawUrl());

        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();

            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            assertEquals(channels.get(channels.size() - 1).getRawUrl(), settings.getLastChannelUrl());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());

            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            assertEquals(channels.get(0).getRawUrl(), settings.getLastChannelUrl());

            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            assertEquals(channels.get(1).getRawUrl(), settings.getLastChannelUrl());

            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            assertEquals(channels.get(0).getRawUrl(), settings.getLastChannelUrl());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
        }
    }

    @Test
    public void testChannelDrawerCyclicCategoryNavigationAndAutoHide() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            // Click the toggle drawer button to open the channel list drawer
            activity.findViewById(R.id.btn_toggle_drawer).performClick();
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());

            androidx.recyclerview.widget.RecyclerView rvCategories = activity.findViewById(R.id.rv_categories);
            com.bjunicom.iptv.ui.adapter.CategoryAdapter adapter =
                    (com.bjunicom.iptv.ui.adapter.CategoryAdapter) rvCategories.getAdapter();
            assertNotNull(adapter);
            int totalCategories = adapter.getItemCount();
            assertTrue(totalCategories > 2);
            assertEquals(0, adapter.getSelectedPosition());

            // Continuously press DPAD_RIGHT across all categories (including empty Favorites) and wrap around twice
            for (int step = 1; step <= totalCategories * 2; step++) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                assertEquals(step % totalCategories, adapter.getSelectedPosition());
            }

            // Continuously press DPAD_LEFT across all categories and wrap around backwards twice
            for (int step = 1; step <= totalCategories * 2; step++) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
                int expected = ((totalCategories * 2 - step) % totalCategories + totalCategories) % totalCategories;
                assertEquals(expected, adapter.getSelectedPosition());
            }

            // Advance main looper by 6000ms (HUD_AUTO_HIDE_MS) and verify both channel drawer and HUD auto-hide
            org.robolectric.shadows.ShadowLooper.idleMainLooper(6000, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
        }
    }
}
