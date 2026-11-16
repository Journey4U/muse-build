package com.muse.android.tv.player.exo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import com.muse.android.tv.player.effect.PlayerEffect;
import com.muse.android.tv.player.engine.PlayerEngine;
import com.muse.android.tv.player.media.MediaItemFactory;
import com.muse.android.tv.player.media.PlaySpec;

import java.text.DecimalFormat;
import java.util.Locale;

public class ExoPlayerEngine implements PlayerEngine, AnalyticsListener {

    private final ExoErrorMessageProvider provider;
    private final ExoSubtitleController subtitles;
    private final ExoPlayerSession session;
    private final ExoPlayerEffect effect;
    private final ExoDiskPreload preload;
    private final ExoPlayer player;
    private PlaySpec spec;
    private String videoDecoder;
    private String audioDecoder;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this.effect = new ExoPlayerEffect();
        this.preload = new ExoDiskPreload();
        this.provider = new ExoErrorMessageProvider();
        this.session = new ExoPlayerSession(decode, listener, effect.getAudioProcessor());
        this.subtitles = new ExoSubtitleController(session);
        this.player = session.player();
        this.player.addAnalyticsListener(this);
        this.effect.setPlayer(player);
    }

    @Override
    public void onAudioTrackInitialized(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onAudioTrackReleased(@NonNull EventTime eventTime, @NonNull AudioSink.AudioTrackConfig audioTrackConfig) {
        effect.applyAudioEffect();
    }

    @Override
    public void onTracksChanged(@NonNull EventTime eventTime, @NonNull Tracks tracks) {
        effect.applyVideoEffect();
    }

    @Override
    public void onVideoDecoderInitialized(@NonNull EventTime eventTime, @NonNull String decoderName, long initializedTimestampMs, long initializationDurationMs) {
        videoDecoder = decoderName;
    }

    @Override
    public void onAudioDecoderInitialized(@NonNull EventTime eventTime, @NonNull String decoderName, long initializedTimestampMs, long initializationDurationMs) {
        audioDecoder = decoderName;
    }

    @Override
    public Type getType() {
        return Type.EXO;
    }

    @Override
    public boolean needsRebuild() {
        return session.hasLibassSettingChanged();
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        Format format = player.getAudioFormat();
        return format == null ? Format.NO_VALUE : format.channelCount;
    }

    @Override
    public PlayerEffect getEffect() {
        return effect;
    }

    @Override
    public void release() {
        subtitles.release();
        player.removeAnalyticsListener(this);
        preload.release();
        effect.release();
        session.release();
    }

    @Override
    public void setDecode(int decode) {
        session.setDecode(decode);
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        videoDecoder = null;
        audioDecoder = null;
        startInternal(startPositionMs);
    }

    @Override
    public String getMediaInfoText() {
        StringBuilder text = new StringBuilder();
        Format video = player.getVideoFormat();
        if (video != null) {
            text.append("Video: ").append(codecName(video.sampleMimeType, video.codecs));
            if (videoDecoder != null) text.append(" [").append(videoDecoder).append("] ").append(isSoftwareDecoder(videoDecoder) ? "SW" : "HW");
            text.append("\nResolution: ").append(video.width).append(" x ").append(video.height);
            if (video.frameRate != Format.NO_VALUE && video.frameRate > 0) text.append("\nFrame Rate: ").append(Math.round(video.frameRate)).append(" fps");
            if (video.bitrate != Format.NO_VALUE && video.bitrate > 0) text.append("\nBitrate: ").append(new DecimalFormat("#.###").format(video.bitrate / 1_000_000f)).append(" Mbps");
            text.append("\n");
        }
        Format audio = player.getAudioFormat();
        if (audio != null) {
            text.append("\nAudio: ").append(codecName(audio.sampleMimeType, audio.codecs));
            if (audioDecoder != null) text.append(" [").append(audioDecoder).append("] ").append(isSoftwareDecoder(audioDecoder) ? "SW" : "HW");
            if (audio.channelCount != Format.NO_VALUE) text.append("\nChannels: ").append(audio.channelCount);
            if (audio.sampleRate != Format.NO_VALUE) text.append("  Sample Rate: ").append(audio.sampleRate).append(" Hz");
            if (audio.bitrate != Format.NO_VALUE && audio.bitrate > 0) text.append("\nBitrate: ").append(Math.round(audio.bitrate / 1000f)).append(" kbps");
            text.append("\n");
        }
        // ExoPlayer exposes no demuxer byte counters; report the buffered
        // ahead time instead of a cache byte total.
        long ahead = Math.max(0, player.getBufferedPosition() - player.getCurrentPosition());
        text.append("\nCache: ").append(new DecimalFormat("#.#").format(ahead / 1000f)).append(" sec buffered");
        return text.toString().trim();
    }

    private static boolean isSoftwareDecoder(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("google") || lower.contains("ffmpeg") || lower.contains("c2.android.") || lower.contains("omx.android.");
    }

    private static String codecName(String mime, String codecs) {
        if (mime == null) return codecs == null ? "" : codecs;
        return switch (mime) {
            case MimeTypes.VIDEO_H264 -> "H.264 / AVC";
            case MimeTypes.VIDEO_H265 -> "H.265 / HEVC";
            case MimeTypes.VIDEO_VP9 -> "VP9";
            case MimeTypes.VIDEO_AV1 -> "AV1";
            case MimeTypes.VIDEO_MP4V -> "MPEG-4";
            case MimeTypes.VIDEO_MPEG2 -> "MPEG-2";
            case MimeTypes.AUDIO_AAC -> "AAC";
            case MimeTypes.AUDIO_AC3 -> "AC-3";
            case MimeTypes.AUDIO_E_AC3 -> "E-AC-3";
            case MimeTypes.AUDIO_FLAC -> "FLAC";
            case MimeTypes.AUDIO_MPEG -> "MP3";
            case MimeTypes.AUDIO_OPUS -> "Opus";
            case MimeTypes.AUDIO_TRUEHD -> "TrueHD";
            case MimeTypes.AUDIO_DTS -> "DTS";
            default -> codecs != null ? codecs : mime;
        };
    }

    @Override
    public void preload(PlaySpec spec, long startPositionMs) {
        session.preload(MediaItemFactory.from(spec), startPositionMs);
    }

    @Override
    public void clearPreload() {
        session.clearPreload();
    }

    @Override
    public void bindPlayerView(PlayerView playerView) {
        subtitles.bindPlayerView(playerView);
    }

    @Override
    public void applySubtitleStyle() {
        subtitles.applySubtitleStyle();
    }

    @Override
    public SecondarySubtitleState getSecondarySubtitleState() {
        return subtitles.getSecondarySubtitleState();
    }

    @Override
    public void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
        subtitles.setSecondarySubtitleSelection(selection);
    }

    @Override
    public void stop() {
        preload.stop();
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            default -> ErrorAction.FATAL;
        };
    }

    private void startInternal(long position) {
        MediaItem item = MediaItemFactory.from(spec);
        MediaSource source = session.usePreloadedMediaSource(item);
        effect.clearAudioEffect();
        if (source == null) player.setMediaItem(item, position);
        else player.setMediaSource(source, position);
        preload.start(player, item);
        prepareAndPlay();
    }

    private void prepareAndPlay() {
        player.prepare();
        player.play();
    }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        player.prepare();
        return ErrorAction.RECOVERED;
    }

    private ErrorAction retryFormat(int errorCode) {
        spec.setFormat(ExoUtil.getMimeType(errorCode));
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
