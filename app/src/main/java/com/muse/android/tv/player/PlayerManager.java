package com.muse.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.muse.android.tv.App;
import com.muse.android.tv.Constant;
import com.muse.android.tv.R;
import com.muse.android.tv.bean.Danmaku;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Sub;
import com.muse.android.tv.bean.Track;
import com.muse.android.tv.impl.ParseCallback;
import com.muse.android.tv.player.effect.PlayerEffectManager;
import com.muse.android.tv.player.effect.audio.AudioEffectBands;
import com.muse.android.tv.player.engine.PlayerEngine;
import com.muse.android.tv.player.engine.PlayerEngine.SecondarySubtitleState;
import com.muse.android.tv.player.engine.PlayerEngineFactory;
import com.muse.android.tv.player.media.PlaySpec;
import com.muse.android.tv.player.parse.ParseJob;
import com.muse.android.tv.api.loader.PushMultiRes;
import com.muse.android.tv.player.track.TrackUtil;
import com.muse.android.tv.setting.DanmakuSetting;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.setting.PreloadSetting;
import com.muse.android.tv.setting.SpeedSetting;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.ResUtil;
import com.muse.android.tv.utils.Util;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private final PlayerEffectManager effects;
    private final Runnable runnable;
    private final Callback callback;
    private PendingPreload pendingPreload;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    /** push v45/v27：网页源起播时由 VideoActivity 记下该片 key——只有这条片子的嗅探结果交播放器前先走 PushMultiRes 多画质升级（播放服务共用，别的页面/别的片不受影响）。 */
    private String upgradeOnParseKey;
    private PlaySpec spec;
    private String parseSourceUrl;
    private Player player;

    private long pendingStartPositionMs;
    private boolean danmakuEnabled;
    private boolean initTrack;
    private int retry;
    private int decode;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.decode = PlayerEngine.HARD;
        this.runnable = this::onPlayTimeout;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.engine = PlayerEngineFactory.create(decode, listener);
        this.effects = new PlayerEffectManager(() -> engine);
        this.danmakuEnabled = DanmakuSetting.isShow();
        this.player = engine.getPlayer();
    }

    public void release() {
        App.removeCallbacks(runnable);
        if (player != null) player.removeListener(listener);
        if (engine != null) engine.release();
        engine = null;
        player = null;
    }

    public Player getPlayer() {
        return player;
    }

    private void setPlayer(Player player) {
        this.player = player;
        callback.onPlayerRebuild(player);
    }

    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    public int getAudioChannelCount() {
        return engine == null ? Format.NO_VALUE : engine.getAudioChannelCount();
    }

    public List<MediaChapter> getCurrentMediaChapters() {
        return java.util.Collections.emptyList();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return player.getCurrentMediaEditions();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : List.of();
    }

    private void notifyDanmakuSourceChanged() {
        callback.onDanmakuSourceChanged(getSelectedDanmakuUri());
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public String getMediaTitle() {
        MediaMetadata metadata = getMetadata();
        if (metadata == null) return "";
        CharSequence title = !TextUtils.isEmpty(metadata.displayTitle) ? metadata.displayTitle : metadata.title;
        if (TextUtils.isEmpty(title)) title = metadata.artist;
        return TextUtils.isEmpty(title) ? "" : title.toString();
    }

    public void setMetadata(@NonNull MediaMetadata metadata) {
        if (spec == null || metadata.equals(spec.getMetadata())) return;
        spec.setMetadata(metadata);
        if (TextUtils.isEmpty(spec.getUrl())) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(metadata).build());
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean hasPlaySpec() {
        return spec != null;
    }

    public boolean canPreloadNext() {
        return PreloadSetting.isEnabled() && PreloadSetting.isNextEpisodeEnabled() && engine != null && engine.getType() == PlayerEngine.Type.EXO;
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return player.getCurrentMediaItem() != null && player.isCurrentMediaItemLive();
    }

    public boolean isVod() {
        return player.getCurrentMediaItem() != null && !player.isCurrentMediaItemLive();
    }

    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    public boolean haveEdition() {
        return !getCurrentMediaEditions().isEmpty();
    }

    public boolean haveChapter() {
        return !getCurrentMediaChapters().isEmpty();
    }

    public boolean haveDanmaku() {
        return spec != null && spec.getSelectedDanmaku() != null;
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public boolean canSetAudioSetting() {
        return effects.canSetAudioSetting();
    }

    public AudioEffectBands getAudioSettingBands() {
        return effects.getAudioSettingBands();
    }

    public int getAudioSettingError() {
        return effects.getAudioSettingError();
    }

    public boolean canSetVideoSetting() {
        return effects.canSetVideoSetting();
    }

    public int getVideoSettingError() {
        return effects.getVideoSettingError();
    }

    public boolean supportsVideoSharpness() {
        return effects.supportsVideoSharpness();
    }

    public int getEngine() {
        return isMpvEngine() ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
    }

    public void setEngine(int targetEngine) {
        PlayerSetting.putEngine(targetEngine);
        if (isEmpty() || PlayerEngineFactory.matches(engine, spec)) return;
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
        startCurrent(snapshot.positionMs());
        snapshot.restore(player);
    }

    public String getPositionTime(long delta) {
        return Util.timeMs(Math.clamp(getPosition() + delta, 0, Math.max(0, getDuration())));
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (sub == null || sub.isEmpty()) return;
        if (spec != null) spec.setSub(sub);
        if (engine.addSubtitle(sub)) play();
        else startCurrent();
    }

    public void selectChapter(MediaChapter chapter) {
        // No-op: FongMi private API not in public Media3
    }

    public void selectEdition(MediaEdition edition) {
        // No-op: FongMi private API not in public Media3
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        callback.onDanmakuConfigChanged(config);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuEnabled == enabled) return;
        danmakuEnabled = enabled;
        callback.onDanmakuEnabledChanged(danmakuEnabled);
    }

    public void applySubtitleStyle() {
        if (engine != null) engine.applySubtitleStyle();
    }

    public SecondarySubtitleState getSecondarySubtitleState() {
        return engine == null ? SecondarySubtitleState.EMPTY : engine.getSecondarySubtitleState();
    }

    public void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
        if (engine != null) engine.setSecondarySubtitleSelection(selection);
    }

    public void sendDanmaku(String text) {
        callback.onDanmakuSent(text);
    }

    public float setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeed();
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(SpeedSetting.clamp(speed)));
        return getSpeed();
    }

    public float toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? SpeedSetting.getLongPress() : 1);
    }

    public boolean supportsSkipSilence() {
        return effects.supportsSkipSilence();
    }

    public boolean isSkipSilence() {
        return effects.isSkipSilence();
    }

    public void setSkipSilenceEnabled(boolean enabled) {
        effects.setSkipSilenceEnabled(enabled);
    }

    public void setTrack(Track track) {
        // The MPV player never consumes TrackSelectionParameters overrides;
        // its tracks are selected by native track id through the engine.
        if (isMpvEngine() && engine.setTrackSelection(track)) return;
        TrackUtil.setTrackSelection(player, track);
    }

    @Nullable
    public MpvPlayer.AutoHlsRuntimeSnapshot getHlsSnapshot() {
        return engine == null ? null : engine.getAutoHlsRuntimeSnapshot();
    }

    public void setHlsVariant(long bits) {
        if (!isMpvEngine() || spec == null) return;
        MpvPlayer.AutoHlsRuntimeSnapshot snapshot = engine.getAutoHlsRuntimeSnapshot();
        String previous = snapshot == null ? "" : !snapshot.acceptedOption().isEmpty() ? snapshot.acceptedOption() : snapshot.stagedOption();
        if (bits > 0 ? bits == parseHlsBits(previous) : TextUtils.isEmpty(previous)) return;
        boolean accepted;
        if (bits > 0) {
            accepted = engine.applyAutoHlsBitrate(String.valueOf(bits)).accepted();
        } else {
            engine.clearAutoHlsBitrate();
            accepted = true;
        }
        if (!accepted) {
            restoreHlsCeiling(previous);
            Notify.show(ResUtil.getString(R.string.error_play_variant));
            return;
        }
        // The hls-bitrate ceiling only takes effect when the demuxer
        // (re)opens the stream, so applying it pairs with a reload of the
        // current spec: VOD resumes at the current position, live rejoins
        // the live edge.
        if (!reloadForHlsVariant()) {
            restoreHlsCeiling(previous);
            if (!reloadForHlsVariant()) Notify.show(ResUtil.getString(R.string.error_play_variant));
        }
    }

    private static long parseHlsBits(String option) {
        try {
            return TextUtils.isEmpty(option) ? 0 : Long.parseLong(option.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void restoreHlsCeiling(String previous) {
        if (TextUtils.isEmpty(previous)) engine.clearAutoHlsBitrate();
        else engine.applyAutoHlsBitrate(previous);
    }

    private boolean reloadForHlsVariant() {
        try {
            PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
            startCurrent(isLive() ? C.TIME_UNSET : snapshot.positionMs());
            snapshot.restore(player);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void setVideoSetting(int preset) {
        effects.setVideoSetting(preset);
    }

    public void refreshVideoSetting() {
        effects.refreshVideoSetting();
    }

    public void previewVideoSetting(boolean original) {
        effects.previewVideoSetting(original);
    }

    public void setAudioSetting(int preset) {
        effects.setAudioSetting(preset);
    }

    public void refreshAudioSetting() {
        effects.refreshAudioSetting();
    }

    public void previewAudioSetting(boolean original) {
        effects.previewAudioSetting(original);
    }

    private boolean isMpvEngine() {
        return engine != null && engine.getType() == PlayerEngine.Type.MPV;
    }

    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        engine.stop();
        stopParse();
        PlayInfoLog.resetLast();
    }

    public void clearMediaItems() {
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void replay(long positionMs) {
        if (positionMs == C.TIME_UNSET) player.seekToDefaultPosition();
        else player.seekTo(positionMs);
        player.play();
    }

    public void seekTo(long time) {
        player.seekTo(time);
    }

    public long getTextOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET) ? player.getTextOffsetMs() : 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET) ? player.getAudioOffsetMs() : 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        spec = null;
        parseSourceUrl = null;
        PlayInfoLog.resetLast();
    }

    public boolean preload(PlaySpec spec, long startPositionMs) {
        if (!canPreloadNext() || spec == null || !PlayerEngineFactory.matches(engine, spec)) return false;
        pendingPreload = new PendingPreload(spec.checkUa(), Math.max(0, startPositionMs));
        startPreloadIfReady();
        return true;
    }

    public void clearPreload() {
        pendingPreload = null;
        if (engine != null) engine.clearPreload();
    }

    public void bindPlayerView(PlayerView playerView) {
        if (engine != null) engine.bindPlayerView(playerView);
    }

    public void resetTrack() {
        TrackUtil.reset(player);
    }

    public boolean hasNativeMediaInfo() {
        return engine != null && engine.hasNativeMediaInfo();
    }

    public void toggleMediaInfo() {
        if (engine != null) engine.toggleMediaInfo();
    }

    public String getMediaInfoText() {
        return engine == null ? "" : engine.getMediaInfoText();
    }

    public void toggleDecode() {
        setDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
    }

    private void handleDecodeError(PlaybackException e) {
        if (++retry > 1) callback.onError(engine.getErrorMessage(e));
        else retryDecode(isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
    }

    private void retryDecode(int decode) {
        setDecode(decode);
        PlaybackSnapshot snapshot = PlaybackSnapshot.capture(player);
        startCurrent(snapshot.positionMs());
        snapshot.restore(player);
    }

    private void setDecode(int decode) {
        this.decode = decode;
        engine.setDecode(decode);
        callback.onDecodeChanged();
    }

    private boolean isHard() {
        return decode == PlayerEngine.HARD;
    }

    private void onPlayTimeout() {
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
        stop();
    }

    private void ensureEngine(PlaySpec spec) {
        if (PlayerEngineFactory.matches(engine, spec)) return;
        PlayerEngine old = engine;
        player.removeListener(listener);
        // MuseTV: release-then-create, matching webhtv's engine switch — the
        // old engine's native context must be fully released before the new
        // engine creates its own.
        old.release();
        engine = PlayerEngineFactory.create(decode, spec, listener);
        setPlayer(engine.getPlayer());
    }

    public void browse(PlaySpec spec, long startPositionMs) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY, startPositionMs);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        this.spec = spec;
        parseSourceUrl = null;
        setMediaItem(timeout, startPositionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();
        pendingStartPositionMs = startPositionMs;
        spec = PlaySpec.fromParse(result, key, metadata);
        parseSourceUrl = result.getRealUrl();
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
        pendingStartPositionMs = C.TIME_UNSET;
    }

    private void setMediaItem(long timeout, long startPositionMs) {
        if (spec == null || spec.getUrl() == null) return;
        ensureEngine(spec.checkUa());
        pendingPreload = null;
        initTrack = false;
        engine.start(spec, startPositionMs);
        PlayInfoLog.logStart(spec, parseSourceUrl);
        parseSourceUrl = null;
        notifyDanmakuSourceChanged();
        // webhtv arms this same watchdog at TIMEOUT_PLAY for every engine and
        // relies on its adaptive preload stack to make MPV start in time;
        // that stack is only partly ported here, so the MPV engine gets the
        // longer TIMEOUT_PLAY_MPV window. A larger caller-provided (site)
        // timeout still wins for both engines.
        App.post(runnable, isMpvEngine() ? Math.max(timeout, Constant.TIMEOUT_PLAY_MPV) : timeout);
        callback.onPrepare();
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    private void startPreloadIfReady() {
        PendingPreload preload = pendingPreload;
        if (preload == null || player.getPlaybackState() != Player.STATE_READY) return;
        pendingPreload = null;
        engine.preload(preload.spec(), preload.startPositionMs());
    }

    @Nullable
    public Uri getSelectedDanmakuUri() {
        Danmaku item = spec != null ? spec.getSelectedDanmaku() : null;
        return item == null ? null : item.getUri();
    }

    public void setDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.selectDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void toggleDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.toggleDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void addDanmaku(Danmaku item) {
        if (spec != null) spec.addDanmaku(item);
    }

    public void setUpgradeOnParse(String key) {
        this.upgradeOnParseKey = key;
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (upgradeOnParseKey != null && spec != null && upgradeOnParseKey.equals(spec.getKey()) && url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            // push v45/v27：网页源嗅探结果同样升级多画质（失败/非 m3u8 照原地址）；期间换了片则作废
            final PlaySpec target = spec;
            PushMultiRes.upgrade(url, headers, upgraded -> {
                if (target == null || target != spec) return;
                // push v49/v33：PushMultiRes 试头选中的那套（有登记候选时）换掉原头
                Map<String, String> chosen = PushMultiRes.chosenHeaders(url);
                spec.setHeaders(chosen != null ? chosen : headers);
                spec.setUrl(TextUtils.isEmpty(upgraded) ? url : upgraded);
                startCurrent(pendingStartPositionMs);
                pendingStartPositionMs = C.TIME_UNSET;
            });
            return;
        }
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        startCurrent(pendingStartPositionMs);
        pendingStartPositionMs = C.TIME_UNSET;
    }

    @Override
    public void onParseError() {
        pendingStartPositionMs = C.TIME_UNSET;
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onDecodeChanged();

        void onMediaOptionsChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);

        void onDanmakuSourceChanged(@Nullable Uri uri);

        void onDanmakuConfigChanged(DanmakuConfig config);

        void onDanmakuEnabledChanged(boolean enabled);

        void onDanmakuSent(String text);
    }

    private record PendingPreload(PlaySpec spec, long startPositionMs) {
    }

    private record PlaybackSnapshot(long positionMs, boolean playWhenReady, PlaybackParameters playbackParameters, int repeatMode, float volume, long audioOffsetMs, long textOffsetMs) {

        private static PlaybackSnapshot capture(Player player) {
            float volume = player.isCommandAvailable(Player.COMMAND_GET_VOLUME) ? player.getVolume() : Float.NaN;
            long audioOffsetMs = player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET) ? player.getAudioOffsetMs() : C.TIME_UNSET;
            long textOffsetMs = player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET) ? player.getTextOffsetMs() : C.TIME_UNSET;
            return new PlaybackSnapshot(player.getCurrentPosition(), player.getPlayWhenReady(), player.getPlaybackParameters(), player.getRepeatMode(), volume, audioOffsetMs, textOffsetMs);
        }

        private void restore(Player player) {
            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) player.setPlaybackParameters(playbackParameters);
            if (player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) player.setRepeatMode(repeatMode);
            if (!Float.isNaN(volume) && player.isCommandAvailable(Player.COMMAND_SET_VOLUME)) player.setVolume(volume);
            if (audioOffsetMs != C.TIME_UNSET && player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(audioOffsetMs);
            if (textOffsetMs != C.TIME_UNSET && player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(textOffsetMs);
            if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) player.setPlayWhenReady(playWhenReady);
        }
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) App.removeCallbacks(runnable);
            if (state == Player.STATE_READY) startPreloadIfReady();
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            initTrack = true;
            TrackUtil.setTrackSelection(player, Track.find(getKey()));
            callback.onTracksChanged();
        }

        @Override
        public void onMediaChaptersChanged(@NonNull List<MediaChapter> chapters) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            if (spec == null) return;
            PlayerEngine.ErrorAction action = engine.handleError(e);
            if (action != PlayerEngine.ErrorAction.RECOVERED) App.removeCallbacks(runnable);
            switch (action) {
                case DECODE -> handleDecodeError(e);
                case RECOVERED -> notifyDanmakuSourceChanged();
                case FATAL -> callback.onError(engine.getErrorMessage(e));
            }
        }
    };
}



