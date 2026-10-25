package com.bjunicom.iptv.player;

import com.google.android.exoplayer2.PlaybackException;

import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import java.net.UnknownHostException;

import tv.danmaku.ijk.media.player.IMediaPlayer;

/** Keeps transport failures separate from format and decoder failures. */
public enum PlaybackFailure {
    NETWORK, AUDIO_DECODER, VIDEO_DECODER, FORMAT, UNKNOWN;

    public static PlaybackFailure fromLegacy(int what, int extra) {
        if (what == IMediaPlayer.MEDIA_ERROR_IO || extra == IMediaPlayer.MEDIA_ERROR_IO
                || what == IMediaPlayer.MEDIA_ERROR_TIMED_OUT
                || extra == IMediaPlayer.MEDIA_ERROR_TIMED_OUT
                || extra == -5 || extra == -110 || extra == -111
                || extra == -101 || extra == -113) {
            return NETWORK;
        }
        if (what == IMediaPlayer.MEDIA_ERROR_UNSUPPORTED
                || extra == IMediaPlayer.MEDIA_ERROR_UNSUPPORTED
                || what == IMediaPlayer.MEDIA_ERROR_MALFORMED
                || extra == IMediaPlayer.MEDIA_ERROR_MALFORMED) {
            return FORMAT;
        }
        return UNKNOWN;
    }

    public static PlaybackFailure fromException(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ParserException || cause instanceof HttpDataSource.InvalidContentTypeException) {
                return FORMAT;
            }
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) return UNKNOWN;
            if (cause instanceof SocketTimeoutException) return NETWORK;
            if (cause instanceof InterruptedIOException) return UNKNOWN;
            if (cause instanceof ConnectException || cause instanceof SocketException
                    || cause instanceof NoRouteToHostException || cause instanceof UnknownHostException) {
                return NETWORK;
            }
            if (cause instanceof HttpDataSource.HttpDataSourceException) {
                int code = ((HttpDataSource.HttpDataSourceException) cause).reason;
                if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) return NETWORK;
            }
        }
        return UNKNOWN;
    }

    public static PlaybackFailure fromExo(PlaybackException error, boolean audioRenderer) {
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return NETWORK;
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return audioRenderer ? AUDIO_DECODER : VIDEO_DECODER;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                return FORMAT;
            default:
                return fromException(error);
        }
    }
}
