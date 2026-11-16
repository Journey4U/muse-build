package com.muse.android.tv.player.mpv;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;

import com.muse.android.tv.App;
import com.muse.android.tv.player.subtitle.AndroidFontConfig;
import com.muse.android.tv.player.track.LangUtil;
import com.muse.android.tv.setting.DecodeSetting;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.setting.PreloadSetting;
import com.muse.android.tv.setting.SubtitleSetting;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import is.xyz.mpv.MPVLib;

public final class MpvUtil {

    private static final List<String> FONT_OPTIONS = List.of("sub-font", "sub-fonts-dir", "sub-ass-style-overrides");
    private static final List<String> SCALE_OPTIONS = List.of("sub-scale", "sub-scale-signs");
    private static final List<String> CACHE_OPTIONS = List.of("cache", "cache-on-disk", "demuxer-cache-dir", "cache-secs");
    private static final List<String> PLAYER_OPTIONS = List.of("vo", "gpu-api", "gpu-context", "hwdec", "audio-spdif");
    private static final List<String> STYLE_OPTIONS = List.of("embeddedfonts", "sub-color", "sub-back-color", "sub-border-style", "sub-outline-color", "sub-outline-size", "sub-shadow-offset");

    private static final String ASSET_CA_FILE = "cacert.pem";
    private static final String SPDIF_CODECS = "ac3,eac3,dts,truehd,dts-hd";
    private static final float DEFAULT_SUB_POS = 100.0f;
    private static final float MIN_SUB_POS = 0.0f;
    private static final float MAX_SUB_POS = 150.0f;

    // webhtv mechanism: the bundled mpv-libs assets are extracted and loaded by
    // MPVLib.ensureLoaded(); availability is exactly "the bundle loads".
    public static boolean isAvailable() {
        try {
            return MPVLib.ensureLoaded(App.get());
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isVulkanSupported() {
        try {
            return MPVLib.isVulkanRendererAvailable(App.get());
        } catch (Throwable e) {
            return false;
        }
    }

    public static MpvPlayer buildPlayer(int decode, Player.Listener listener) {
        MpvPlayer player = new MpvPlayer(App.get(), buildConfig(decode));
        // webhtv arms the HLS proxy's automatic preload right after player
        // construction, gated on its performance profile being AUTO — the
        // profile it seeds by default (PlaybackPerformanceSetting
        // .applyAutoValues). This shell has no performance-profile setting,
        // so the AUTO behaviour is applied unconditionally: automatic mode
        // on, external resource/traffic grants off until playback policy
        // (not ported) would raise them.
        player.updateAutomaticPreloadControl(true, false, false);
        setPreferredTextLanguages(player);
        player.addListener(listener);
        return player;
    }

    public static void applySubtitleStyle(MpvPlayer player) {
        if (!SubtitleSetting.isPositionSet() && !SubtitleSetting.isScaleApplied()) return;
        float scale = SubtitleSetting.isScaleApplied() ? SubtitleSetting.getAppliedScale() : 1.0f;
        float position = SubtitleSetting.isPositionSet() ? getSubtitlePosition() : DEFAULT_SUB_POS;
        player.setSubtitleStyle(scale, position);
    }

    static List<String> getManagedOptionNames() {
        List<String> options = new ArrayList<>(PLAYER_OPTIONS);
        boolean hasFontOverride = SubtitleSetting.getFont() != null;
        boolean hasStyleOverride = SubtitleSetting.isStyleForced();
        if (PreloadSetting.isEnabled()) options.addAll(CACHE_OPTIONS);
        if (hasFontOverride) options.addAll(FONT_OPTIONS);
        if (hasStyleOverride) options.addAll(STYLE_OPTIONS);
        if (hasFontOverride || hasStyleOverride) options.add("sub-ass-override");
        if (SubtitleSetting.isPositionSet()) options.add("sub-pos");
        if (SubtitleSetting.isScaleApplied()) options.addAll(SCALE_OPTIONS);
        if (SubtitleSetting.isSecondaryPositionSet()) options.add("secondary-sub-pos");
        return options;
    }

    private static MpvPlayerConfig buildConfig(int decode) {
        AndroidFontConfig.prepare();
        // Aligned with webhtv's shell (its MpvPlayerEngine.buildConfig):
        // prepare the app-private config dir before building the player, and
        // use it instead of the shared external Path.mpv().
        MpvConfigStore.ensureReady();
        MpvPlayerConfig.Builder builder = MpvPlayerConfig.builder(App.get())
                .configDir(MpvConfigStore.configDir())
                .cacheDir(Path.mpvCache())
                .caFile(extractCaFile())
                .hwdec(decode == C.DECODE_SOFTWARE ? "no" : "auto")
                .audioSpdif(DecodeSetting.isAudioPassThrough() ? SPDIF_CODECS : "")
                // webhtv's engine takes these from its AUTO performance
                // profile (BUFFER_TIME / MPV_HLS_BITRATE both seed to AUTO,
                // cache target = clamp(buffer x 3, 15, 60) via
                // PlayerSetting.getBuffer, rebuffer = recommended 2s). This
                // shell has no profile system, so the AUTO values are wired
                // directly; PlayerSetting.getBuffer() is the same buffer
                // setting webhtv's formula reads.
                .automaticCacheTime(true)
                .automaticHlsVariant(true)
                .cacheSeconds(Math.clamp(PlayerSetting.getBuffer() * 3, 15, 60))
                .rebufferMs(MpvPlayerConfig.DEFAULT_REBUFFER_MS);
        // NOTE: no "ad" option is set anywhere, deliberately (mainstream mpv
        // policy, same as mpv's official Android app). FFmpeg's MediaCodec
        // audio wrappers (aac_mediacodec & friends) are never forced: that
        // wrapper faults on some devices (OnePlus PJZ110 / Android 16) and
        // kills the audio chain. With "ad" unset, mpv's default decoder
        // selection prefers the native decoders for every format.
        if (PlayerSetting.isMpvGpuNext()) builder.vo("gpu-next");
        if (PlayerSetting.isMpvVulkan() && isVulkanSupported()) {
            builder.gpuApi("vulkan");
            builder.gpuContext("androidvk");
        }
        if (PreloadSetting.isEnabled()) {
            builder.cache(true);
            builder.cacheSeconds(PreloadSetting.getTimeSeconds());
        }
        return builder.build();
    }

    private static File extractCaFile() {
        File out = Path.files(ASSET_CA_FILE);
        if (out.isFile() && out.length() > 0) return out;
        try (InputStream in = App.get().getAssets().open(ASSET_CA_FILE); OutputStream os = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) os.write(buffer, 0, read);
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void setPreferredTextLanguages(MpvPlayer player) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setPreferredTextLanguages(LangUtil.getPreferredTextLanguages()).build());
    }

    private static float getSubtitlePosition() {
        return Math.clamp(DEFAULT_SUB_POS - SubtitleSetting.getPosition(), MIN_SUB_POS, MAX_SUB_POS);
    }
}



