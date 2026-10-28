package com.bjunicom.iptv.player;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import com.bjunicom.iptv.config.IptvSettings;
import tv.danmaku.ijk.media.player.AndroidMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.TextureMediaPlayer;

/** Creates native cores and applies options independently of the view lifecycle. */
final class PlaybackCoreFactory {
    private PlaybackCoreFactory() {}

    static IMediaPlayer create(Context context, IptvSettings settings, PlaybackRecovery recovery,
                               int playerType, String rawUrl, Uri playbackUri, boolean requireIjk) {
        IMediaPlayer player;
        switch (playerType) {
            case IptvSettings.PLAYER_UTV:
                return new UTVPlayer(context);
            case IptvSettings.PLAYER_IJK:
                try {
                    IjkMediaPlayer ijk = new IjkMediaPlayer();
                    try {
                        IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_INFO);
                        configureIjkOptions(ijk, rawUrl, playbackUri, settings, recovery);
                    } catch (Throwable error) {
                        ijk.release();
                        throw error;
                    }
                    player = ijk;
                } catch (Throwable error) {
                    if (requireIjk) throw new IllegalStateException("Ijk decoder fallback is unavailable", error);
                    Log.e("PlaybackCoreFactory", "Failed to init IjkMediaPlayer, using AndroidMediaPlayer", error);
                    player = new AndroidMediaPlayer();
                }
                break;
            case IptvSettings.PLAYER_ANDROID_MEDIA:
            default:
                player = new AndroidMediaPlayer();
                break;
        }
        return settings.getRenderViewType() == IjkVideoView.RENDER_TEXTURE_VIEW
                ? new TextureMediaPlayer(player) : player;
    }

    static void configureIjkOptions(IjkMediaPlayer ijkMediaPlayer, String rawUrl, Uri playbackUri,
                                    IptvSettings settings, PlaybackRecovery recovery) {
        boolean useMediaCodec = !recovery.forceSoftwareVideo()
                && (settings == null || settings.isMediaCodecEnabled());
        boolean enableHevcHw = useMediaCodec
                && (settings == null || settings.isMediaCodecHevcEnabled());
        boolean useOpenSlEs = settings != null && settings.isOpenSlEsEnabled();
        PlaybackBufferPolicy bufferPolicy = PlaybackBufferPolicy.forStream(playbackUri, rawUrl);
        boolean fastTs = bufferPolicy == PlaybackBufferPolicy.MULTICAST_TS
                || bufferPolicy == PlaybackBufferPolicy.HTTP_TS;
        boolean likely4K = !TextUtils.isEmpty(rawUrl) && rawUrl.endsWith(":2000");
        IjkStartupPolicy startupPolicy = IjkStartupPolicy.forStream(rawUrl, playbackUri, recovery.expandedProbe());

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", enableHevcHw ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg2", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg4", useMediaCodec ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos",
                (useMediaCodec && enableHevcHw) ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate",
                (useMediaCodec || enableHevcHw) ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change",
                (useMediaCodec || enableHevcHw) ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-sync", 0);

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", useOpenSlEs ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        // Keep multicast paused until Java has validated the native probe result.
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared",
                startupPolicy.multicast ? 0 : 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "fast", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", fastTs ? 1 : 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", fastTs ? 0 : 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", fastTs ? (likely4K ? 15 : 5) : 25);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size",
                fastTs ? (likely4K ? (8 * 1024 * 1024L) : (2 * 1024 * 1024L)) : (16 * 1024 * 1024L));

        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",
                startupPolicy.probeSizeBytes);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration",
                startupPolicy.analyzeDurationUs);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fpsprobesize", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek+discardcorrupt");
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8000000L);
        ijkMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter",
                (useMediaCodec || enableHevcHw) ? 0 : 48);
    }
}
