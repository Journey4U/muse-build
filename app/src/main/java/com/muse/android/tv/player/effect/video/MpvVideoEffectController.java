package com.muse.android.tv.player.effect.video;

import androidx.media3.mpvplayer.MpvPlayer;

// webhtv's MpvPlayer has no video-equalizer object API; the equalizer maps to
// mpv's native video properties (range -100..100), applied through the
// player's runtime property bridge.
public final class MpvVideoEffectController {

    public void apply(MpvPlayer player, VideoEffectProfile profile) {
        player.setRuntimeProperty("brightness", value(convert(profile.getBrightness(), 0.0f)));
        player.setRuntimeProperty("contrast", value(convert(profile.getContrast(), 1.0f)));
        player.setRuntimeProperty("saturation", value(convert(profile.getSaturation(), 1.0f)));
        player.setRuntimeProperty("hue", value(toHue(profile.getHue())));
        player.setRuntimeProperty("gamma", value(toGamma(profile.getGamma())));
        player.setRuntimeProperty("sharpen", Float.toString(Math.clamp(profile.getSharpness(), 0.0f, 1.0f)));
    }

    public void clear(MpvPlayer player) {
        player.setRuntimeProperty("brightness", "0");
        player.setRuntimeProperty("contrast", "0");
        player.setRuntimeProperty("saturation", "0");
        player.setRuntimeProperty("hue", "0");
        player.setRuntimeProperty("gamma", "0");
        player.setRuntimeProperty("sharpen", "0");
    }

    private String value(float value) {
        return String.valueOf(Math.round(value));
    }

    private float toGamma(float gamma) {
        return Math.clamp((float) (Math.log(gamma) * 100.0 / Math.log(8.0)), -100.0f, 100.0f);
    }

    private float convert(float value, float neutral) {
        return Math.clamp((value - neutral) * 100.0f, -100.0f, 100.0f);
    }

    private float toHue(float hue) {
        return Math.clamp(hue / 1.8f, -100.0f, 100.0f);
    }
}
