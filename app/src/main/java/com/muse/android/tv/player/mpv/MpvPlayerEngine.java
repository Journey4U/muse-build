package com.muse.android.tv.player.mpv;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.mpvplayer.MpvPlayer;

import com.muse.android.tv.bean.Sub;
import com.muse.android.tv.bean.Track;
import com.muse.android.tv.player.effect.PlayerEffect;
import com.muse.android.tv.player.engine.PlayerEngine;
import com.muse.android.tv.player.media.MediaItemFactory;
import com.muse.android.tv.player.media.PlaySpec;
import com.muse.android.tv.player.track.TrackUtil;
import com.muse.android.tv.setting.SubtitleSetting;
import com.github.catvod.crawler.SpiderDebug;

import is.xyz.mpv.MPVLib;

import java.util.ArrayList;
import java.util.List;

public class MpvPlayerEngine implements PlayerEngine, Player.Listener {

    private final MpvErrorMessageProvider provider;
    private final MpvPlayerEffect effect;
    private final MpvPlayer player;
    private PlaySpec spec;
    private int decode;
    private boolean rebuild;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        this.player = MpvUtil.buildPlayer(decode, listener);
        this.provider = new MpvErrorMessageProvider();
        this.effect = new MpvPlayerEffect(player);
        this.player.addListener(this);
        applySecondarySubtitleMode(SubtitleSetting.getSecondaryMode());
    }

    @Override
    public boolean needsRebuild() {
        return rebuild;
    }

    public static boolean isAvailable() {
        return MpvUtil.isAvailable();
    }

    @Override
    public Type getType() {
        return Type.MPV;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        // webhtv's MpvPlayer exposes no channel-count getter; derive it from
        // the selected audio track's format.
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) continue;
                Format format = group.getTrackFormat(i);
                if (format.channelCount != Format.NO_VALUE) return format.channelCount;
            }
        }
        return Format.NO_VALUE;
    }

    @Override
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        player.removeListener(this);
        player.release();
    }

    @Override
    public void applySubtitleStyle() {
        MpvUtil.applySubtitleStyle(player);
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        if (sub == null || sub.isEmpty() || player.getCurrentMediaItem() == null) return false;
        if (player.getPlaybackState() == Player.STATE_IDLE || player.getPlaybackState() == Player.STATE_ENDED) return false;
        // webhtv's MpvPlayer adds external subtitles from the media item's
        // subtitle configurations at load time; rebuild the current item
        // with the added configuration and reload at the same position.
        MediaItem current = player.getCurrentMediaItem();
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>(current.localConfiguration == null ? List.of() : current.localConfiguration.subtitleConfigurations);
        subs.add(MediaItemFactory.buildSubConfig(sub));
        boolean playWhenReady = player.getPlayWhenReady();
        player.setMediaItem(current.buildUpon().setSubtitleConfigurations(subs).build(), player.getCurrentPosition());
        player.prepare();
        player.setPlayWhenReady(playWhenReady);
        return true;
    }

    @Override
    public boolean setTrackSelection(Track track) {
        if (track == null) return false;
        if (!track.isSelected()) {
            // Toggling a track off mirrors the EXO path's override with an
            // empty selection for the type: mpv's "no" disables the track.
            player.setTrackSelection(track.getType(), "no");
            return true;
        }
        String id = resolveMpvTrackId(track);
        if (id == null) {
            SpiderDebug.log("mpv", "select track failed: no mpv id type=%d name=%s format=%s", track.getType(), track.getName(), track.getFormat());
            return false;
        }
        player.setTrackSelection(track.getType(), id);
        return true;
    }

    private String resolveMpvTrackId(Track track) {
        String id = parseMpvTrackId(track.getPlayerId());
        // playerId is supplied by the currently visible track list. Only
        // persisted preferences from an earlier playback need the fallback
        // of matching the stored format description against current tracks.
        return id != null ? id : findMpvTrackId(track);
    }

    private String findMpvTrackId(Track track) {
        if (track.getFormat() == null) return null;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != track.getType()) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                if (track.getFormat().equals(TrackUtil.describeFormat(format))) return parseMpvTrackId(format.id);
            }
        }
        return null;
    }

    private static String parseMpvTrackId(String id) {
        if (id == null) return null;
        int index = id.indexOf(':');
        return index >= 0 && index + 1 < id.length() ? id.substring(index + 1) : id;
    }

    @Override
    public MpvPlayer.AutoHlsBitrateResult applyAutoHlsBitrate(String option) {
        return player.applyAutoHlsBitrate(option);
    }

    @Override
    public void clearAutoHlsBitrate() {
        player.clearAutoHlsBitrate();
    }

    @Override
    public MpvPlayer.AutoHlsRuntimeSnapshot getAutoHlsRuntimeSnapshot() {
        return player.getAutoHlsRuntimeSnapshot();
    }

    @Override
    public boolean hasNativeMediaInfo() {
        return true;
    }

    @Override
    public void toggleMediaInfo() {
        // libmpv ships stats.lua; this binding flips its overlay. There is
        // no explicit on/off command, so callers must reset their own
        // state whenever this engine instance is rebuilt or released.
        if (player.getCurrentMediaItem() == null) return;
        MPVLib.command(new String[]{"script-binding", "stats/display-stats-toggle"});
    }

    @Override
    public void setDecode(int decode) {
        // Decode (hwdec) is a construction-time MpvPlayerConfig value in the
        // webhtv player; flag a rebuild so the factory recreates the engine
        // with the new config on the next start.
        if (this.decode == decode) return;
        this.decode = decode;
        this.rebuild = true;
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        effect.applyVideoEffect();
        effect.applyAudioEffect();
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        startInternal(startPositionMs);
    }

    private void startInternal(long startPositionMs) {
        effect.applyVideoEffect();
        effect.clearAudioEffect();
        player.setMediaItem(MediaItemFactory.from(spec), startPositionMs);
        prepareAndPlay();
    }

    private void prepareAndPlay() {
        player.prepare();
        player.play();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> retryHls();
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryHls() {
        if (spec == null || MimeTypes.APPLICATION_M3U8.equals(spec.getFormat())) return ErrorAction.FATAL;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }

    private void applySecondarySubtitleMode(int mode) {
        // Secondary-subtitle mode stays a no-op in the shell glue (unchanged
        // from the previous player); the webhtv player manages secondary
        // subtitle selection internally.
    }
}

