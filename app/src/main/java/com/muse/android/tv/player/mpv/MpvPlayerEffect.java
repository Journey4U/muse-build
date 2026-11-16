package com.muse.android.tv.player.mpv;

import androidx.media3.mpvplayer.MpvPlayer;

import com.muse.android.tv.R;
import com.muse.android.tv.player.effect.PlayerEffect;
import com.muse.android.tv.player.effect.audio.AudioEffectBands;
import com.muse.android.tv.player.effect.video.MpvVideoEffectController;
import com.muse.android.tv.player.effect.video.VideoEffectProfile;
import com.muse.android.tv.setting.VideoSetting;

// Video effects map to mpv's native video properties (see
// MpvVideoEffectController). webhtv's MpvPlayer exposes no audio-filter
// chain API, so audio effects report unsupported under MPV (the settings
// UI hides them); EXO audio effects are unaffected.
public final class MpvPlayerEffect implements PlayerEffect {

    private final MpvVideoEffectController videoEffectController;
    private final MpvPlayer player;

    private boolean previewVideoEffect;
    private boolean previewAudioEffect;

    public MpvPlayerEffect(MpvPlayer player) {
        this.videoEffectController = new MpvVideoEffectController();
        this.player = player;
    }

    @Override
    public boolean supportsVideoEffect() {
        return true;
    }

    @Override
    public int getVideoEffectError() {
        return 0;
    }

    @Override
    public boolean supportsVideoSharpness() {
        return true;
    }

    @Override
    public void applyVideoEffect() {
        if (previewVideoEffect || VideoSetting.isEnabled()) videoEffectController.apply(player, getVideoProfile());
        else videoEffectController.clear(player);
    }

    @Override
    public void previewVideoEffect(boolean original) {
        if (previewVideoEffect == original) return;
        previewVideoEffect = original;
        applyVideoEffect();
    }

    @Override
    public boolean supportsAudioEffect() {
        return false;
    }

    @Override
    public AudioEffectBands getAudioEffectBands() {
        return AudioEffectBands.STANDARD;
    }

    @Override
    public int getAudioEffectError() {
        return R.string.error_audio_effect_unsupported;
    }

    @Override
    public void applyAudioEffect() {
    }

    public void clearAudioEffect() {
    }

    @Override
    public void previewAudioEffect(boolean original) {
        if (previewAudioEffect == original) return;
        previewAudioEffect = original;
        applyAudioEffect();
    }

    private VideoEffectProfile getVideoProfile() {
        return previewVideoEffect ? VideoEffectProfile.off() : VideoSetting.getAppliedProfile();
    }
}
