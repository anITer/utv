package com.bjunicom.iptv.player;

import com.bjunicom.iptv.config.IptvSettings;
import java.util.HashSet;
import java.util.Set;

/** Recovery budgets belong to a request; known Android audio failures belong to this session. */
final class PlaybackRecovery {
    private boolean forcedIjk;
    private boolean softwareVideo;
    private boolean expandedProbe;
    private int requestedPlayerType = -1;
    private final Set<String> androidAudioFailures = new HashSet<>();

    void newRequest() {
        forcedIjk = softwareVideo = expandedProbe = false;
    }

    int effectivePlayerType(int requested, String rawUrl, String audioHint) {
        if (forcedIjk) return IptvSettings.PLAYER_IJK;
        if (requested == IptvSettings.PLAYER_ANDROID_MEDIA
                && (androidAudioFailures.contains(rawUrl)
                || (audioHint.contains("mp2") && DecoderPolicy.getMp2HardwareDecoders(null).isEmpty()))) {
            return IptvSettings.PLAYER_IJK;
        }
        return requested;
    }

    boolean fallbackToIjk(String rawUrl, boolean softwareVideo, boolean unsupportedAudio) {
        if (forcedIjk) return false;
        forcedIjk = true;
        this.softwareVideo = softwareVideo;
        if (unsupportedAudio && rawUrl != null && !rawUrl.isEmpty()) androidAudioFailures.add(rawUrl);
        return true;
    }

    boolean expandProbe() {
        if (expandedProbe) return false;
        expandedProbe = true;
        return true;
    }

    boolean forcedIjk() { return forcedIjk; }
    boolean forceSoftwareVideo() { return softwareVideo; }
    boolean expandedProbe() { return expandedProbe; }
    int lastRequestedPlayerType() { return requestedPlayerType; }
    void requestedPlayerType(int type) { requestedPlayerType = type; }
}
