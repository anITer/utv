package com.bjunicom.iptv;

import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.bjunicom.iptv.player.IjkVideoView;
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
    public void testSinglePlayerInstanceStrategyAcrossAllCores() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();
            activity.onWindowFocusChanged(true);

            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            assertEquals(1, IjkVideoView.PLAYER_POOL_SIZE);
            assertEquals(1, videoView.getPlayerPoolSize());
            assertNotNull(videoView.getPlayerSlots());
            assertEquals(1, videoView.getPlayerSlots().length);
            assertEquals(null, videoView.getBackgroundSlot());

            SurfaceView surfaceView = (SurfaceView) videoView.getChildAt(0);
            ShadowSurfaceView.FakeSurfaceHolder holder = Shadows.shadowOf(surfaceView).getFakeSurfaceHolder();
            for (SurfaceHolder.Callback cb : holder.getCallbacks()) {
                cb.surfaceCreated(holder);
                cb.surfaceChanged(holder, 1, 1920, 1080);
            }
            org.robolectric.shadows.ShadowLooper.runUiThreadTasks();

            IjkVideoView.PlayerSlot initialActive = videoView.getActiveSlot();
            assertNotNull(initialActive);
            assertNotNull(initialActive.player);
            assertNotNull(initialActive.rawChannelUrl);
            assertEquals(null, videoView.getBackgroundSlot());
            assertTrue(videoView.areOffscreenPlayersStopped());

            String initialActiveRawUrl = initialActive.rawChannelUrl;

            // Hide HUD first so DPAD_UP switches to the next channel
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));

            // Switch to next channel (DPAD_UP) -> reuses the single activeSlot without creating any background player
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));

            assertSame(initialActive, videoView.getActiveSlot());
            assertNotNull(videoView.getActiveSlot().player);
            assertFalse(initialActiveRawUrl.equals(videoView.getActiveSlot().rawChannelUrl));
            assertEquals(null, videoView.getBackgroundSlot());
            assertTrue(videoView.areOffscreenPlayersStopped());

            // Switch back to previous channel (DPAD_DOWN) -> still reuses the single activeSlot
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));

            assertSame(initialActive, videoView.getActiveSlot());
            assertEquals(initialActiveRawUrl, videoView.getActiveSlot().rawChannelUrl);
            assertEquals(null, videoView.getBackgroundSlot());
            assertTrue(videoView.areOffscreenPlayersStopped());

            // Switch player core to AndroidMediaPlayer (PLAYER_ANDROID_MEDIA) and verify only 1 instance is used
            com.bjunicom.iptv.config.IptvSettings settings =
                    new com.bjunicom.iptv.config.IptvSettings(activity);
            settings.setPlayerType(com.bjunicom.iptv.config.IptvSettings.PLAYER_ANDROID_MEDIA);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));

            assertEquals(1, videoView.getPlayerSlots().length);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.PLAYER_ANDROID_MEDIA, videoView.getActiveSlot().playerType);
            assertTrue(videoView.getActiveSlot().player instanceof tv.danmaku.ijk.media.player.AndroidMediaPlayer);
            assertEquals(null, videoView.getBackgroundSlot());
            assertTrue(videoView.getDecoderName().contains("单实例"));
            // Verify when AndroidMediaPlayer finishes preparing, TsHardwareAudioCompanion is NOT started (preventing dual audio playback)
            videoView.checkAndStartHwAudioIfNeeded();
            assertFalse(videoView.isHwAudioCompanionRunning());

            // Switch player core to UTVPlayer (PLAYER_UTV) and verify only 1 UTVPlayer instance is used and reused across channel switches
            settings.setPlayerType(com.bjunicom.iptv.config.IptvSettings.PLAYER_UTV);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));

            assertEquals(1, videoView.getPlayerSlots().length);
            assertEquals(com.bjunicom.iptv.config.IptvSettings.PLAYER_UTV, videoView.getActiveSlot().playerType);
            assertTrue(videoView.getActiveSlot().player instanceof com.bjunicom.iptv.player.UTVPlayer);
            assertEquals(null, videoView.getBackgroundSlot());

            com.bjunicom.iptv.player.UTVPlayer utvPlayer = videoView.getUtvPlayer();
            assertNotNull(utvPlayer);
            assertSame(videoView.getActiveSlot().player, utvPlayer);
            assertNotNull(utvPlayer.getExoPlayer());

            // Verify TsExtractor factory returns a single TsExtractor (skipping sniff() in BundledExtractorsAdapter)
            // and filters private stream_type=0x06 while using TimestampAdjuster(0L)
            com.google.android.exoplayer2.extractor.Extractor[] extractors =
                    com.bjunicom.iptv.player.PreloadMediaSource.PreloadPool.createIptvTsExtractorsFactory()
                            .createExtractors(android.net.Uri.parse("http://192.168.2.1:8012/rtp/239.3.1.129:8008"), java.util.Collections.emptyMap());
            assertEquals(1, extractors.length);
            assertTrue(extractors[0] instanceof com.google.android.exoplayer2.extractor.ts.TsExtractor);

            // Verify UTVStreamDataSource 188B TS sync alignment helper
            byte[] unalignedTs = new byte[17 + 188 * 5];
            for (int p = 0; p < 5; p++) {
                unalignedTs[17 + p * 188] = 0x47;
            }
            assertEquals(17, com.bjunicom.iptv.player.UTVStreamDataSource.findTsSyncOffset(unalignedTs, unalignedTs.length));

            // Switch to next channel on UTVPlayer -> reuses the same single UTVPlayer instance in mActiveSlot
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
            assertSame(utvPlayer, videoView.getUtvPlayer());
            assertEquals(null, videoView.getBackgroundSlot());
            assertTrue(videoView.getDecoderName().contains("单实例"));

            // Verify AdaptiveMediaCodecVideoRenderer.canReuseCodec() returns REUSE_RESULT_YES_WITH_FLUSH across CCTV-1 -> CCTV-2
            com.bjunicom.iptv.player.UTVPlayer.AdaptiveMediaCodecVideoRenderer adaptiveRenderer =
                    new com.bjunicom.iptv.player.UTVPlayer.AdaptiveMediaCodecVideoRenderer(
                            activity,
                            com.google.android.exoplayer2.mediacodec.MediaCodecAdapter.Factory.DEFAULT,
                            com.google.android.exoplayer2.mediacodec.MediaCodecSelector.DEFAULT,
                            1500L,
                            true,
                            null,
                            null,
                            50,
                            utvPlayer);
            com.google.android.exoplayer2.Format cctv1Format = new com.google.android.exoplayer2.Format.Builder()
                    .setSampleMimeType(com.google.android.exoplayer2.util.MimeTypes.VIDEO_H264)
                    .setWidth(1920)
                    .setHeight(1080)
                    .build();
            com.google.android.exoplayer2.Format cctv2Format = new com.google.android.exoplayer2.Format.Builder()
                    .setSampleMimeType(com.google.android.exoplayer2.util.MimeTypes.VIDEO_H264)
                    .setWidth(1920)
                    .setHeight(1080)
                    .build();
            com.google.android.exoplayer2.mediacodec.MediaCodecInfo hwCodecInfo =
                    com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
                            "c2.qti.avc.decoder",
                            com.google.android.exoplayer2.util.MimeTypes.VIDEO_H264,
                            com.google.android.exoplayer2.util.MimeTypes.VIDEO_H264,
                            null,
                            true,
                            false,
                            true,
                            false,
                            false);
            com.google.android.exoplayer2.mediacodec.MediaCodecInfo swCodecInfo =
                    com.google.android.exoplayer2.mediacodec.MediaCodecInfo.newInstance(
                            "c2.android.avc.decoder",
                            com.google.android.exoplayer2.util.MimeTypes.VIDEO_H264,
                            com.google.android.exoplayer2.util.MimeTypes.VIDEO_H264,
                            null,
                            false,
                            true,
                            false,
                            false,
                            false);

            // 1. Default is hardware decoding for both IjkPlayer and ExoPlayer
            assertTrue(settings.isMediaCodecEnabled());
            assertTrue(settings.isMediaCodecHevcEnabled());
            assertTrue(com.bjunicom.iptv.player.UTVPlayer.isHardwareDecodingPreferred("video/avc", settings));
            assertTrue(com.bjunicom.iptv.player.UTVPlayer.isHardwareDecodingPreferred("video/hevc", settings));
            java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> orderedDefault =
                    com.bjunicom.iptv.player.UTVPlayer.selectDecodersForSettings(
                            java.util.Arrays.asList(swCodecInfo, hwCodecInfo), "video/avc", settings);
            assertSame(hwCodecInfo, orderedDefault.get(0));
            assertTrue(videoView.getDecoderName().contains("硬解"));

            com.google.android.exoplayer2.decoder.DecoderReuseEvaluation eval =
                    adaptiveRenderer.canReuseCodec(hwCodecInfo, cctv1Format, cctv2Format);
            assertTrue(eval.result != com.google.android.exoplayer2.decoder.DecoderReuseEvaluation.REUSE_RESULT_NO);
            assertTrue(utvPlayer.getCodecReuseHitCount() > 0);

            // 2. When user switches to software decoding in settings, both ExoPlayer and IjkPlayer switch to software decoding
            settings.setMediaCodecEnabled(false);
            settings.setMediaCodecHevcEnabled(false);
            assertFalse(com.bjunicom.iptv.player.UTVPlayer.isHardwareDecodingPreferred("video/avc", settings));
            assertFalse(com.bjunicom.iptv.player.UTVPlayer.isHardwareDecodingPreferred("video/hevc", settings));
            java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> orderedSw =
                    com.bjunicom.iptv.player.UTVPlayer.selectDecodersForSettings(
                            java.util.Arrays.asList(hwCodecInfo, swCodecInfo), "video/avc", settings);
            assertSame(swCodecInfo, orderedSw.get(0));

            // Hardware decoder is rejected when software decoding is requested
            com.google.android.exoplayer2.decoder.DecoderReuseEvaluation rejectHwEval =
                    adaptiveRenderer.canReuseCodec(hwCodecInfo, cctv1Format, cctv2Format);
            assertEquals(com.google.android.exoplayer2.decoder.DecoderReuseEvaluation.REUSE_RESULT_NO, rejectHwEval.result);
            assertEquals(com.google.android.exoplayer2.decoder.DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE,
                    rejectHwEval.discardReasons);
            assertTrue(videoView.getDecoderName().contains("软解"));

            // Verify IjkPlayer also switches to FFmpeg software decoding when MediaCodec is disabled
            settings.setPlayerType(com.bjunicom.iptv.config.IptvSettings.PLAYER_IJK);
            assertTrue(videoView.getDecoderName().contains("FFmpeg") && videoView.getDecoderName().contains("软解"));

            // Restore default hardware decoding & switch back to PLAYER_UTV to test PlaceholderSurface & idle stop
            settings.setMediaCodecEnabled(true);
            settings.setMediaCodecHevcEnabled(true);
            settings.setPlayerType(com.bjunicom.iptv.config.IptvSettings.PLAYER_UTV);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
            utvPlayer = videoView.getUtvPlayer();
            assertNotNull(utvPlayer);

            // Verify PlaceholderSurface parking when external UI SurfaceView is destroyed
            for (SurfaceHolder.Callback cb : holder.getCallbacks()) {
                cb.surfaceDestroyed(holder);
            }
            assertTrue(utvPlayer.isAttachedToPlaceholderSurface());
            assertTrue(videoView.areOffscreenPlayersStopped());
            assertEquals(null, videoView.getBackgroundSlot());

            // Verify player settings dialog has the three options: IjkPlayer, ExoPlayer, MediaPlayer
            android.view.View settingsView = android.view.LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_player_settings, null);
            android.widget.RadioButton rbIjk = settingsView.findViewById(R.id.rb_core_ijk);
            android.widget.RadioButton rbExo = settingsView.findViewById(R.id.rb_core_utv);
            android.widget.RadioButton rbMedia = settingsView.findViewById(R.id.rb_core_android);
            assertEquals("IjkPlayer", rbIjk.getText().toString());
            assertEquals("ExoPlayer", rbExo.getText().toString());
            assertEquals("MediaPlayer", rbMedia.getText().toString());

            // Restore default PLAYER_IJK
            settings.setPlayerType(com.bjunicom.iptv.config.IptvSettings.PLAYER_IJK);
        }
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
            rvCategories.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(420, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(48, android.view.View.MeasureSpec.EXACTLY));
            rvCategories.layout(0, 0, 420, 48);
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
                org.robolectric.shadows.ShadowLooper.runUiThreadTasks();
                int expectedPos = step % totalCategories;
                assertEquals(expectedPos, adapter.getSelectedPosition());
                // Focus must never drift to the top search EditText when switching to an empty category
                assertFalse(activity.findViewById(R.id.et_search_channel).hasFocus());
                androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                        rvCategories.findViewHolderForAdapterPosition(expectedPos);
                if (vh != null && vh.itemView != null) {
                    assertTrue(vh.itemView.findViewById(R.id.tv_category_name).isSelected());
                }
            }

            // Continuously press DPAD_LEFT across all categories and wrap around backwards twice
            for (int step = 1; step <= totalCategories * 2; step++) {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
                org.robolectric.shadows.ShadowLooper.runUiThreadTasks();
                int expected = ((totalCategories * 2 - step) % totalCategories + totalCategories) % totalCategories;
                assertEquals(expected, adapter.getSelectedPosition());
                assertFalse(activity.findViewById(R.id.et_search_channel).hasFocus());
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
            assertEquals("1.2.1", com.bjunicom.iptv.update.AppUpdateManager.getCurrentVersionName(activity));
        }
        assertEquals("1.2.1", com.bjunicom.iptv.update.AppUpdateManager.CURRENT_VERSION_NAME);
        assertTrue(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("v1.3.0", "1.2.1"));
        assertTrue(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("1.2.2", "1.2.1"));
        assertTrue(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("2.0", "1.9.9"));
        assertFalse(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("1.2.1", "1.2.1"));
        assertFalse(com.bjunicom.iptv.update.AppUpdateManager.isNewerVersion("v1.2.0", "1.2.1"));

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

    @Test
    public void testMp2AudioSoftwareFallbackDecoderAndRenderer() throws Exception {
        // 1. Construct a valid MPEG-1 Layer II (MP2) 192kbps 48kHz Stereo frame (576 bytes):
        // Sync=0xFFF, ID=1(MPEG-1), Layer=10(Layer II), Protection=1(no CRC),
        // BitrateIdx=10(192kbps), SampleRateIdx=1(48000Hz), Padding=0, Mode=00(Stereo) -> 0xFFFDa400
        int headerInt = 0xFFFDA400;
        com.bjunicom.iptv.player.Mp2SoftwareDecoder.FrameHeader hdr =
                com.bjunicom.iptv.player.Mp2SoftwareDecoder.parseHeader(headerInt);
        assertNotNull(hdr);
        assertEquals(2, hdr.layer);
        assertFalse(hdr.lsf);
        assertEquals(48000, hdr.sampleRate);
        assertEquals(192, hdr.bitrateKbps);
        assertEquals(2, hdr.channels);
        assertEquals(576, hdr.frameSize);

        byte[] mp2Frame = new byte[576];
        mp2Frame[0] = (byte) 0xFF;
        mp2Frame[1] = (byte) 0xFD;
        mp2Frame[2] = (byte) 0xA4;
        mp2Frame[3] = (byte) 0x00;
        // Set subband 0 left/right bit allocation index = 1 (stepIdx=2 -> 3 bits/sample) and non-zero samples
        mp2Frame[4] = (byte) 0x11;
        for (int i = 16; i < 120; i++) {
            mp2Frame[i] = (byte) 0x5A;
        }

        com.bjunicom.iptv.player.Mp2SoftwareDecoder.Mp2Decoder decoder =
                new com.bjunicom.iptv.player.Mp2SoftwareDecoder.Mp2Decoder();
        byte[] pcm = decoder.decodeStreamChunk(mp2Frame, 0, mp2Frame.length);
        assertNotNull(pcm);
        // 1152 samples * 2 channels * 2 bytes (16-bit PCM) = 4608 bytes
        assertEquals(1152 * 2 * 2, pcm.length);
        assertEquals(48000, decoder.getSampleRate());
        assertEquals(2, decoder.getChannelCount());

        // 2. Verify UTVPlayer registers Mp2DecoderAudioRenderer and supports audio/mpeg-L2
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().get();
            com.bjunicom.iptv.player.UTVPlayer sampleUtv =
                    new com.bjunicom.iptv.player.UTVPlayer(activity);
            com.bjunicom.iptv.player.UTVPlayer.UTVRenderersFactory rf =
                    new com.bjunicom.iptv.player.UTVPlayer.UTVRenderersFactory(activity, sampleUtv);
            com.google.android.exoplayer2.Renderer[] renderers = rf.createRenderers(
                    new android.os.Handler(android.os.Looper.getMainLooper()),
                    new com.google.android.exoplayer2.video.VideoRendererEventListener() {},
                    new com.google.android.exoplayer2.audio.AudioRendererEventListener() {},
                    cues -> {},
                    metadata -> {});
            com.bjunicom.iptv.player.UTVPlayer.Mp2DecoderAudioRenderer mp2Renderer = null;
            for (com.google.android.exoplayer2.Renderer r : renderers) {
                if (r instanceof com.bjunicom.iptv.player.UTVPlayer.Mp2DecoderAudioRenderer) {
                    mp2Renderer = (com.bjunicom.iptv.player.UTVPlayer.Mp2DecoderAudioRenderer) r;
                    break;
                }
            }
            assertNotNull(mp2Renderer);
            com.google.android.exoplayer2.Format mp2Format = new com.google.android.exoplayer2.Format.Builder()
                    .setSampleMimeType(com.google.android.exoplayer2.util.MimeTypes.AUDIO_MPEG_L2)
                    .setSampleRate(48000)
                    .setChannelCount(2)
                    .build();
            int support = mp2Renderer.supportsFormat(mp2Format);
            assertEquals(com.google.android.exoplayer2.C.FORMAT_HANDLED,
                    com.google.android.exoplayer2.RendererCapabilities.getFormatSupport(support));
            sampleUtv.releasePlayer();

            // 3. Verify switching from UTVPlayer (ExoPlayer) to IjkPlayer or MediaPlayer stops ExoPlayer audio
            IjkVideoView videoView = activity.findViewById(R.id.ijk_video_view);
            com.bjunicom.iptv.config.IptvSettings settings = new com.bjunicom.iptv.config.IptvSettings(activity);
            settings.setPlayerType(com.bjunicom.iptv.config.IptvSettings.PLAYER_UTV);
            videoView.setRawChannelUrl("rtp://239.3.1.129:8008");
            videoView.setVideoPath("http://127.0.0.1:18080/rtp/239.3.1.129:8008");
            com.bjunicom.iptv.player.UTVPlayer activeUtvPlayer = videoView.getUtvPlayer();
            assertNotNull(activeUtvPlayer);
            assertTrue(activeUtvPlayer.getExoPlayer().getPlayWhenReady());

            // Switch player core to PLAYER_IJK -> UTVPlayer slot must stop and mute immediately
            settings.setPlayerType(com.bjunicom.iptv.config.IptvSettings.PLAYER_IJK);
            videoView.setVideoPath("http://127.0.0.1:18080/rtp/239.3.1.129:8008");
            assertFalse(activeUtvPlayer.getExoPlayer().getPlayWhenReady());
            assertEquals(com.google.android.exoplayer2.Player.STATE_IDLE,
                    activeUtvPlayer.getExoPlayer().getPlaybackState());
            assertEquals(0f, activeUtvPlayer.getExoPlayer().getVolume(), 0.001f);
        }
    }

    @Test
    public void testScanCompletedSaveToLocalAndApplyReplacesDefaultAndRefreshesList() throws Exception {
        try (ActivityController<MainActivity> mainCtrl = Robolectric.buildActivity(MainActivity.class);
             ActivityController<com.bjunicom.iptv.ui.ScannerActivity> scanCtrl =
                     Robolectric.buildActivity(com.bjunicom.iptv.ui.ScannerActivity.class)) {
            MainActivity mainActivity = mainCtrl.create().start().resume().visible().get();
            com.bjunicom.iptv.ui.ScannerActivity scannerActivity = scanCtrl.create().start().resume().visible().get();

            com.bjunicom.iptv.repository.ChannelRepository repo =
                    com.bjunicom.iptv.repository.ChannelRepository.getInstance(mainActivity);
            repo.clearAppliedDefaultM3u();
            int initialDefaultCount = repo.getAllChannels().size();
            assertTrue(initialDefaultCount > 50);

            // Prepare 2 scanned channels (1 connected=true [CCTV-4K], 1 connected=false)
            com.bjunicom.iptv.model.IptvChannel ch1 = com.bjunicom.iptv.parser.M3uParser.buildChannel(
                    1,
                    "#EXTINF:-1 tvg-id=\"85\" tvg-name=\"CCTV4K\" group-title=\"[高清]央视;4K\",CCTV-4K超高清[85][4K]",
                    "rtp://239.3.1.244:2000");
            ch1.setConnected(true);
            ch1.setActualResolutionFlag("4K");
            ch1.setVideoTrackInfo("#1 hevc, 3840x2160,50fps");

            com.bjunicom.iptv.model.IptvChannel ch2 = com.bjunicom.iptv.parser.M3uParser.buildChannel(
                    2,
                    "#EXTINF:-1 tvg-id=\"2\" tvg-name=\"CCTV2\" group-title=\"[高清]央视\",CCTV-2财经[2][高清]",
                    "rtp://239.3.1.130:8008");
            ch2.setConnected(false);

            java.util.List<com.bjunicom.iptv.model.IptvChannel> scannedList =
                    java.util.Arrays.asList(ch1, ch2);
            com.bjunicom.iptv.scanner.ScanStatistics stats = new com.bjunicom.iptv.scanner.ScanStatistics();
            stats.totalChannels = 2;
            stats.connectTrueCount = 1;
            stats.connectFalseCount = 1;

            // 1. Trigger scan completion -> verify dialog with "立即应用" and "保存到本地" is shown,
            // and default channel list is NOT replaced before user chooses "立即应用"
            scannerActivity.handleScanCompleted(scannedList, stats);
            androidx.appcompat.app.AlertDialog dialog = scannerActivity.getLastCompletionDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            assertEquals("立即应用", dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("保存到本地", dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertEquals(initialDefaultCount, repo.getAllChannels().size());

            // 2. Click "保存到本地" -> verify local .m3u file is saved with only the valid connected channel,
            // and default channel list is still untouched
            java.io.File savedFile = scannerActivity.saveScanResultsToLocalM3u(scannedList);
            assertNotNull(savedFile);
            assertTrue(savedFile.exists());
            assertTrue(savedFile.length() > 0);
            assertEquals(initialDefaultCount, repo.getAllChannels().size());

            // 3. Click "立即应用" -> verify it first saves to a local .m3u file, imports that local .m3u file
            // to update the channel list, and immediately jumps MainActivity to play the first channel
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.shadows.ShadowLooper.runUiThreadTasks();

            assertNotNull(repo.getLastSavedLocalM3uFile());
            assertTrue(repo.getLastSavedLocalM3uFile().exists());
            assertTrue(repo.hasCustomUploadedM3u());
            assertEquals(1, repo.getAllChannels().size());
            assertEquals("rtp://239.3.1.244:2000", repo.getAllChannels().get(0).getRawUrl());

            androidx.recyclerview.widget.RecyclerView rvChannels = mainActivity.findViewById(R.id.rv_channels);
            com.bjunicom.iptv.ui.adapter.ChannelAdapter channelAdapter =
                    (com.bjunicom.iptv.ui.adapter.ChannelAdapter) rvChannels.getAdapter();
            assertNotNull(channelAdapter);
            assertEquals(1, channelAdapter.getItemCount());
            assertNotNull(mainActivity.getCurrentChannel());
            assertEquals("rtp://239.3.1.244:2000", mainActivity.getCurrentChannel().getRawUrl());

            // Clean up applied default M3U for subsequent tests
            repo.clearAppliedDefaultM3u();
            org.robolectric.shadows.ShadowLooper.runUiThreadTasks();
            assertEquals(initialDefaultCount, repo.getAllChannels().size());
        }
    }

    @Test
    public void testScanDeviceM3uFilesSupportsSystemDownloadAndExternalUsbDirs() throws Exception {
        // 1. Verify /proc/mounts USB mount point parser and volume root extraction
        java.util.List<String> procMountsSample = java.util.Arrays.asList(
                "/dev/fuse /storage/emulated rw,nosuid,nodev,noexec 0 0",
                "/dev/block/vold/public:8,1 /mnt/media_rw/1A2B-3C4D vfat rw,dirsync,nosuid,nodev 0 0",
                "/dev/fuse /storage/1A2B-3C4D fuse rw,nosuid,nodev,noexec,noatime 0 0",
                "/dev/block/sda1 /mnt/usb/sda1 exfat rw,nosuid,nodev 0 0",
                "/dev/block/sdb1 /mnt/usb_storage/USB_DISK0 ntfs rw,nosuid,nodev 0 0"
        );
        java.util.List<String> usbMounts =
                com.bjunicom.iptv.ui.CustomM3uDialogHelper.parseUsbMountPointsFromProcMounts(procMountsSample);
        assertEquals(4, usbMounts.size());
        assertTrue(usbMounts.contains("/mnt/media_rw/1A2B-3C4D"));
        assertTrue(usbMounts.contains("/storage/1A2B-3C4D"));
        assertTrue(usbMounts.contains("/mnt/usb/sda1"));
        assertTrue(usbMounts.contains("/mnt/usb_storage/USB_DISK0"));
        assertFalse(usbMounts.contains("/storage/emulated"));

        java.io.File appUsbDir = new java.io.File("/storage/1A2B-3C4D/Android/data/com.bjunicom.iptv/files");
        java.io.File extractedRoot =
                com.bjunicom.iptv.ui.CustomM3uDialogHelper.extractVolumeRootFromAppExternalDir(appUsbDir);
        assertNotNull(extractedRoot);
        assertEquals("/storage/1A2B-3C4D", extractedRoot.getAbsolutePath());

        // 2. Verify scanning both System Download directory and External USB Drive directory
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.create().start().resume().visible().get();

            java.io.File sysDownloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            sysDownloadDir.mkdirs();
            java.io.File downloadM3u = new java.io.File(sysDownloadDir, "system_download_iptv.m3u");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(downloadM3u)) {
                fos.write(("#EXTM3U\n#EXTINF:-1,CCTV-1\nrtp://239.3.1.129:8008\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            java.io.File simulatedUsbRoot = new java.io.File(activity.getCacheDir(), "simulated_usb_drive_1A2B");
            java.io.File usbSubFolder = new java.io.File(simulatedUsbRoot, "iptv_playlists");
            usbSubFolder.mkdirs();
            java.io.File usbRootM3u = new java.io.File(simulatedUsbRoot, "usb_root_channels.m3u");
            java.io.File usbSubM3u = new java.io.File(usbSubFolder, "usb_sub_channels.m3u8");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(usbRootM3u)) {
                fos.write(("#EXTM3U\n#EXTINF:-1,CCTV-2\nrtp://239.3.1.130:8008\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(usbSubM3u)) {
                fos.write(("#EXTM3U\n#EXTINF:-1,CCTV-4K\nrtp://239.3.1.244:2000\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            com.bjunicom.iptv.ui.CustomM3uDialogHelper.clearCustomUsbScanRoots();
            com.bjunicom.iptv.ui.CustomM3uDialogHelper.addCustomUsbScanRoot(simulatedUsbRoot);
            try {
                com.bjunicom.iptv.ui.CustomM3uDialogHelper helper =
                        new com.bjunicom.iptv.ui.CustomM3uDialogHelper(activity, null);
                java.util.List<java.io.File> found = helper.scanDeviceM3uFiles();
                assertTrue(found.contains(usbRootM3u));
                assertTrue(found.contains(usbSubM3u));
                assertTrue(found.contains(downloadM3u));

                java.util.List<java.io.File> usbDirs =
                        com.bjunicom.iptv.ui.CustomM3uDialogHelper.collectExternalUsbDirectories(activity);
                java.util.List<java.io.File> downloadDirs =
                        com.bjunicom.iptv.ui.CustomM3uDialogHelper.collectSystemDownloadDirectories(activity);
                assertEquals("[外接U盘]", com.bjunicom.iptv.ui.CustomM3uDialogHelper
                        .formatScannedFileSourceTag(usbRootM3u, usbDirs, downloadDirs));
                assertEquals("[外接U盘]", com.bjunicom.iptv.ui.CustomM3uDialogHelper
                        .formatScannedFileSourceTag(usbSubM3u, usbDirs, downloadDirs));
                assertEquals("[系统Download]", com.bjunicom.iptv.ui.CustomM3uDialogHelper
                        .formatScannedFileSourceTag(downloadM3u, usbDirs, downloadDirs));

                helper.showLocalM3uFilesPickerDialog();
                assertNotNull(helper.getLastLocalFilesDialog());
                assertTrue(helper.getLastLocalFilesDialog().isShowing());
                helper.getLastLocalFilesDialog().dismiss();

                // 3. Verify M3U content preview window disables scrollbar by default when selected/focused,
                // and only enables scrollbar after becoming focused and pressing OK key again
                helper.showUploadAndInspectDialog();
                org.robolectric.shadows.ShadowLooper.runUiThreadTasks();
                android.widget.EditText etContent = helper.getContentPreviewView();
                assertNotNull(etContent);
                assertFalse(etContent.isVerticalScrollBarEnabled());
                assertFalse(helper.isContentPreviewScrollBarEnabled());

                // Move focus to the content preview window -> scrollbar remains disabled by default
                etContent.requestFocus();
                assertTrue(etContent.hasFocus());
                assertFalse(etContent.isVerticalScrollBarEnabled());
                assertFalse(helper.isContentPreviewScrollBarEnabled());

                // Pressing DPAD_DOWN while scrollbar is disabled navigates focus down to btn_inspect_m3u_content
                etContent.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                etContent.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
                assertFalse(etContent.hasFocus());

                // Focus content preview window again and press OK (KEYCODE_DPAD_CENTER) -> scrollbar is now enabled
                etContent.requestFocus();
                assertTrue(etContent.hasFocus());
                assertFalse(helper.isContentPreviewScrollBarEnabled());
                etContent.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                etContent.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                assertTrue(etContent.isVerticalScrollBarEnabled());
                assertTrue(helper.isContentPreviewScrollBarEnabled());

                // Pressing BACK while scrollbar is enabled exits scrollbar mode back to normal focus mode
                etContent.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                etContent.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                assertFalse(etContent.isVerticalScrollBarEnabled());
                assertFalse(helper.isContentPreviewScrollBarEnabled());
            } finally {
                com.bjunicom.iptv.ui.CustomM3uDialogHelper.clearCustomUsbScanRoots();
                downloadM3u.delete();
                usbSubM3u.delete();
                usbRootM3u.delete();
                usbSubFolder.delete();
                simulatedUsbRoot.delete();
            }
        }
    }
}
