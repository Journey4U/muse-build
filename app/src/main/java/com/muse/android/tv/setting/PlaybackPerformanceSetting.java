package com.muse.android.tv.setting;

// Adapter for the ported webhtv player layer: webhtv's performance-profile
// system (auto/recommended/compatible profiles with per-option overrides)
// is not adopted by this shell. Out of the box, webhtv treats performance
// options as automatic unless the user overrode them; with no profile
// system here, options are always automatic, matching webhtv's default
// behavior on a fresh install.
public final class PlaybackPerformanceSetting {

    private PlaybackPerformanceSetting() {
    }

    public static boolean isAuto() {
        return true;
    }

    public static boolean isAuto(int kernel) {
        return true;
    }

    public static boolean isAuto(String optionId) {
        return true;
    }

    public static boolean isAuto(int kernel, String optionId) {
        return true;
    }
}
