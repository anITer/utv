package com.bjunicom.iptv.player;

import android.net.Uri;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;

/** Keeps source routing and its immutable buffering policy together. */
final class PlaybackSourceFactory {
    private PlaybackSourceFactory() {}

    static MediaSource create(Uri uri, String rawUrl, String mediaId, DataSource.Factory dataSource) {
        PlaybackBufferPolicy policy = PlaybackBufferPolicy.forStream(uri, rawUrl);
        MediaItem item = new MediaItem.Builder().setUri(uri).setMediaId(mediaId).setTag(policy).build();
        if (PlaybackBufferPolicy.isHls(uri, rawUrl)) {
            return new HlsMediaSource.Factory(dataSource).setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }
        if (PlaybackBufferPolicy.isDash(uri, rawUrl)) {
            return new DashMediaSource.Factory(dataSource).createMediaSource(item);
        }
        // Preserve the IPTV extractor, including for extensionless progressive TS.
        return new ProgressiveMediaSource.Factory(dataSource, new IptvTsExtractorsFactory())
                .setContinueLoadingCheckIntervalBytes(256 * 1024).createMediaSource(item);
    }
}
