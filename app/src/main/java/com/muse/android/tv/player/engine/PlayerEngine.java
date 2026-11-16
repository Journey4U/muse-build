package com.muse.android.tv.player.engine;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.ui.PlayerView;

import com.muse.android.tv.bean.Sub;
import com.muse.android.tv.bean.Track;
import com.muse.android.tv.player.effect.PlayerEffect;
import com.muse.android.tv.player.media.PlaySpec;

import java.util.List;

public interface PlayerEngine {

    int SOFT = C.DECODE_SOFTWARE;
    int HARD = C.DECODE_HARDWARE;

    Type getType();

    default boolean needsRebuild() {
        return false;
    }

    Player getPlayer();

    int getAudioChannelCount();

    void release();

    void setDecode(int decode);

    default PlayerEffect getEffect() {
        return PlayerEffect.NONE;
    }

    void start(PlaySpec spec, long startPositionMs);

    default void preload(PlaySpec spec, long startPositionMs) {
    }

    default void clearPreload() {
    }

    default void bindPlayerView(PlayerView playerView) {
    }

    void stop();

    default void applySubtitleStyle() {
    }

    default SecondarySubtitleState getSecondarySubtitleState() {
        return SecondarySubtitleState.EMPTY;
    }

    default void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }

    // MPV-only capabilities; other engines keep these defaults and the
    // caller falls back to the TrackSelectionParameters channel.
    default boolean setTrackSelection(Track track) {
        return false;
    }

    default MpvPlayer.AutoHlsBitrateResult applyAutoHlsBitrate(String option) {
        return MpvPlayer.AutoHlsBitrateResult.REJECTED;
    }

    default void clearAutoHlsBitrate() {
    }

    // Media-info overlay: MPV has a native stats overlay to flip; other
    // engines return false here and the caller draws its own overlay from
    // getMediaInfoText().
    default boolean hasNativeMediaInfo() {
        return false;
    }

    default void toggleMediaInfo() {
    }

    default String getMediaInfoText() {
        return "";
    }

    @Nullable
    default MpvPlayer.AutoHlsRuntimeSnapshot getAutoHlsRuntimeSnapshot() {
        return null;
    }

    default boolean addSubtitle(Sub sub) {
        return false;
    }

    String getErrorMessage(PlaybackException e);

    ErrorAction handleError(PlaybackException e);

    enum ErrorAction {
        RECOVERED,
        DECODE,
        FATAL
    }

    enum Type {
        EXO,
        MPV
    }

    record SecondarySubtitleState(@Nullable TrackSelectionOverride primarySelection, @Nullable TrackSelectionOverride explicitSelection, List<TrackSelectionOverride> secondaryCandidates, boolean secondaryPromotedToPrimary) {

        public static final SecondarySubtitleState EMPTY = new SecondarySubtitleState(null, null, List.of(), false);

        public SecondarySubtitleState {
            secondaryCandidates = List.copyOf(secondaryCandidates);
        }
    }
}

