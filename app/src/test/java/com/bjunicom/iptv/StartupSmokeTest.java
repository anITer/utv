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
                    R.id.btn_switch_isp,
                    R.id.btn_upload_m3u,
                    R.id.btn_open_scanner,
                    R.id.btn_open_settings,
                    R.id.btn_prev_channel,
                    R.id.btn_next_channel,
                    R.id.btn_numpad_dialog,
                    R.id.btn_favorite_channel,
                    R.id.btn_aspect_ratio,
                    R.id.btn_reload_stream
            };

            // Initial HUD focus is on the first HUD button
            assertNotNull(activity.getFocusedHudButton());
            assertEquals(expectedOrder[0], activity.getFocusedHudButton().getId());
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

            // Hide HUD via BACK key -> both HUD and drawer should be GONE
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());

            // In full-screen playback (both GONE), pressing OK brings up the info page (HUD), NOT channel drawer
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());

            // Hide HUD again via BACK key
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());

            // In full-screen playback (both GONE), pressing DPAD_LEFT brings up channel drawer, NOT HUD
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());

            // Close channel drawer via BACK key -> both GONE (does not pop up HUD)
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());

            // In full-screen playback (both GONE), pressing DPAD_RIGHT also brings up channel drawer, NOT HUD
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());
        }
    }

    @Test
    public void testChannelDrawerCyclicCategoryNavigationAndAutoHide() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            // Click the toggle drawer button to open the channel list drawer (and hide HUD so they are not shown together)
            activity.findViewById(R.id.btn_toggle_drawer).performClick();
            assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.channel_drawer_panel).getVisibility());
            assertEquals(android.view.View.GONE, activity.findViewById(R.id.player_hud_overlay).getVisibility());

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

    @Test
    public void testSwitchIspBetweenUnicomMobileAndTelecom() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            com.bjunicom.iptv.repository.ChannelRepository repo =
                    com.bjunicom.iptv.repository.ChannelRepository.getInstance(activity);
            com.bjunicom.iptv.config.IptvSettings settings =
                    new com.bjunicom.iptv.config.IptvSettings(activity);

            // 1. Default is Beijing Unicom
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM, settings.getIspProvider());
            assertTrue(repo.getAllChannels().size() >= 100);
            assertTrue(repo.getAllChannels().get(0).getRawUrl().startsWith("rtp://239.3.1."));

            // 2. Switch to Beijing Mobile IPTV
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_MOBILE);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_MOBILE, settings.getIspProvider());
            assertTrue(repo.getAllChannels().size() >= 25);
            assertTrue(repo.getAllChannels().get(0).isHlsStream());
            assertTrue(repo.getCategoryGroups().contains(com.bjunicom.iptv.repository.ChannelRepository.GROUP_HD_NEWTV));

            // 3. Switch to Beijing Telecom IPTV
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_TELECOM);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_TELECOM, settings.getIspProvider());
            assertEquals(149, repo.getAllChannels().size());
            assertTrue(repo.getAllChannels().get(0).getRawUrl().startsWith("rtp://225.1.8."));
            assertEquals("225.1.8", settings.getScanSubnet());

            // 4. Switch to All Merged (Unicom + Mobile + Telecom)
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_ALL_MERGED);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.ISP_ALL_MERGED, settings.getIspProvider());
            assertTrue(repo.getAllChannels().size() > 350);
            assertTrue(repo.getCategoryGroups().contains(com.bjunicom.iptv.repository.ChannelRepository.GROUP_ISP_MOBILE));
            assertTrue(repo.getCategoryGroups().contains(com.bjunicom.iptv.repository.ChannelRepository.GROUP_ISP_TELECOM));

            // Restore Unicom
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
            assertEquals("239.3.1", settings.getScanSubnet());
            Robolectric.flushForegroundThreadScheduler();
        }
    }

    @Test
    public void testUploadAndApplyCustomM3uPlaylist() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            com.bjunicom.iptv.repository.ChannelRepository repo =
                    com.bjunicom.iptv.repository.ChannelRepository.getInstance(activity);
            com.bjunicom.iptv.config.IptvSettings settings =
                    new com.bjunicom.iptv.config.IptvSettings(activity);

            String customM3u = "#EXTM3U\n" +
                    "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" group-title=\"自选央视频道\",CCTV-1综合[1][高清]\n" +
                    "rtp://239.3.1.129:8008\n" +
                    "#EXTINF:-1 tvg-id=\"85\" tvg-name=\"CCTV4K\" group-title=\"自选4K专区;4K\",CCTV-4K超高清[85][4K]\n" +
                    "rtp://239.3.1.244:2000\n";

            com.bjunicom.iptv.parser.M3uParser.M3uInspectionReport report =
                    repo.importCustomM3uContent(customM3u, "user_iptv.m3u", true);
            assertTrue(report.isValid());
            assertEquals(2, report.validChannelCount);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.ISP_CUSTOM_M3U, settings.getIspProvider());
            assertEquals("user_iptv.m3u", settings.getCustomM3uName());
            assertTrue(repo.hasCustomUploadedM3u());
            assertEquals(2, repo.getAllChannels().size());
            assertTrue(repo.getCategoryGroups().contains("自选央视频道"));
            assertTrue(repo.getCategoryGroups().contains("自选4K专区"));

            // Switch to custom ISP mode via MainActivity and verify it stays in custom ISP mode
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_CUSTOM_M3U);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.ISP_CUSTOM_M3U, settings.getIspProvider());

            // Restore Unicom
            activity.switchIspProvider(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.ISP_BEIJING_UNICOM, settings.getIspProvider());
            Robolectric.flushForegroundThreadScheduler();
        }
    }

    @Test
    public void testAppUpdateManagerVersionComparisonAndReleaseParsing() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().get();
            assertEquals("1.1.0", com.bjunicom.iptv.update.AppUpdateManager.getCurrentVersionName(activity));
        }
        assertEquals("1.1.0", com.bjunicom.iptv.update.AppUpdateManager.CURRENT_VERSION_NAME);
        assertTrue(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("v1.2.0", "1.1.0"));
        assertTrue(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("1.1.1", "1.1.0"));
        assertTrue(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("2.0", "1.9.9"));
        assertFalse(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("1.1.0", "1.1.0"));
        assertFalse(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("v1.0.0", "1.1.0"));

        String sampleApiJson = "[\n" +
                "  {\n" +
                "    \"tag_name\": \"v1.2.0\",\n" +
                "    \"name\": \"uTV v1.2.0 Release\",\n" +
                "    \"draft\": false,\n" +
                "    \"body\": \"Fix playback and add update checker\",\n" +
                "    \"assets\": [\n" +
                "      {\n" +
                "        \"name\": \"utv-v1.2.0-release.apk\",\n" +
                "        \"size\": 13109007,\n" +
                "        \"browser_download_url\": \"https://github.com/anITer/utv/releases/download/v1.2.0/utv-v1.2.0-release.apk\"\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "]";
        com.bjunicom.iptv.update.AppUpdateManager.ReleaseInfo apiInfo =
                com.bjunicom.iptv.update.AppUpdateManager.parseReleasesApiJson(sampleApiJson);
        assertNotNull(apiInfo);
        assertEquals("1.2.0", apiInfo.versionName);
        assertEquals("utv-v1.2.0-release.apk", apiInfo.apkFileName);
        assertEquals("https://github.com/anITer/utv/releases/download/v1.2.0/utv-v1.2.0-release.apk", apiInfo.apkDownloadUrl);

        String sampleHtml = "<a href=\"/anITer/utv/releases/download/v1.3.0/bj-unicom-iptv-release-signed.apk\">Download APK</a>";
        com.bjunicom.iptv.update.AppUpdateManager.ReleaseInfo htmlInfo =
                com.bjunicom.iptv.update.AppUpdateManager.parseReleasesHtml(sampleHtml);
        assertNotNull(htmlInfo);
        assertEquals("1.3.0", htmlInfo.versionName);
        assertEquals("https://github.com/anITer/utv/releases/download/v1.3.0/bj-unicom-iptv-release-signed.apk", htmlInfo.apkDownloadUrl);
    }
}
