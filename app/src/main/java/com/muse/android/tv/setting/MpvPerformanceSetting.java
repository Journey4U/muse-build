package com.muse.android.tv.setting;

// Adapter for the ported webhtv player layer: only the surface-frame-rate
// surface of webhtv's MpvPerformanceSetting is consumed by the player
// (MpvPlayer's surface frame-rate request). This shell has no MPV frame
// rate setting, so the mode is webhtv's default: seamless.
public final class MpvPerformanceSetting {

    public static final int FRAME_RATE_OFF = 0;
    public static final int FRAME_RATE_SEAMLESS = 1;

    private MpvPerformanceSetting() {
    }

    public static int getFrameRateMode() {
        return FRAME_RATE_SEAMLESS;
    }

    public static String getFrameRateText() {
        return "仅无缝";
    }
}
