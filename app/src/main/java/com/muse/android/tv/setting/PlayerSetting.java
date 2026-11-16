package com.muse.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class PlayerSetting {

    public static final int ENGINE_EXO = 0;
    public static final int ENGINE_MPV = 1;
    // Kernel ids in webhtv's numbering, used only by the ported webhtv
    // player layer (androidx.media3.mpvplayer and its support classes).
    // Distinct namespace from ENGINE_* above; do not mix the two.
    public static final int EXO = 0;
    public static final int IJK = 1;
    public static final int MPV = 2;
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;
    public static final int MIN_SCALE = 0;
    public static final int MAX_SCALE = 4;
    private static final int MIN_SIZE = 0;
    private static final int MAX_SIZE = 3;
    private static final int MIN_BUFFER = 1;
    private static final int MAX_BUFFER = 10;
    private static final int MIN_BACKGROUND = 0;
    private static final int MAX_BACKGROUND = 2;

    // webhtv player-layer accessors (ported mpvplayer support code calls
    // these on PlayerSetting; they map onto this shell's own settings).
    public static int sanitizePlayer(int player) {
        return player == IJK || player == MPV ? player : EXO;
    }

    public static long getPlayCacheSize() {
        return PreloadSetting.getSizeBytes();
    }

    public static long getPlayCacheSize(int kernel) {
        return getPlayCacheSize();
    }

    public static boolean isBlurayMenu() {
        return Prefers.getBoolean("playback_bluray_menu", false);
    }

    public static boolean isCaption() {
        return Prefers.getBoolean("caption");
    }

    public static boolean isPreferAAC() {
        return DecodeSetting.isPreferAAC();
    }

    public static boolean isPreferAAC(int kernel) {
        return DecodeSetting.isPreferAAC();
    }

    public static int getEngine() {
        return Math.clamp(Prefers.getInt("player_engine", ENGINE_EXO), ENGINE_EXO, ENGINE_MPV);
    }

    public static void putEngine(int engine) {
        Prefers.put("player_engine", Math.clamp(engine, ENGINE_EXO, ENGINE_MPV));
        if (isExo() && DecodeSetting.isTunnel()) putRender(RENDER_SURFACE);
    }

    public static boolean isExo() {
        return getEngine() == ENGINE_EXO;
    }

    public static boolean isMpv() {
        return getEngine() == ENGINE_MPV;
    }

    public static boolean isDebug() {
        return Prefers.getBoolean("player_debug");
    }

    public static void putDebug(boolean debug) {
        Prefers.put("player_debug", debug);
    }

    public static boolean isNetInfo() {
        return Prefers.getBoolean("net_info", false);
    }

    public static void putNetInfo(boolean netInfo) {
        Prefers.put("net_info", netInfo);
    }

    public static boolean isMediaInfo() {
        return Prefers.getBoolean("media_info", false);
    }

    public static void putMediaInfo(boolean mediaInfo) {
        Prefers.put("media_info", mediaInfo);
    }

    public static boolean isAutoPlay() {
        return Prefers.getBoolean("auto_play", false);
    }

    /** 电视版自动播放：与手机版共用 auto_play 键，但电视默认开（Liu 2026-11-07 定）；仅 leanback 调用。 */
    public static boolean isAutoPlayTv() {
        return Prefers.getBoolean("auto_play", true);
    }

    public static void putAutoPlay(boolean autoPlay) {
        Prefers.put("auto_play", autoPlay);
    }

    public static int getAutoPlayDelay() {
        return Math.clamp(Prefers.getInt("auto_play_delay", 0), 0, 10);
    }

    public static void putAutoPlayDelay(int delay) {
        Prefers.put("auto_play_delay", Math.clamp(delay, 0, 10));
    }

    public static boolean isLibass() {
        return Prefers.getBoolean("player_libass", true);
    }

    public static void putLibass(boolean libass) {
        Prefers.put("player_libass", libass);
    }

    public static boolean isMpvGpuNext() {
        return Prefers.getBoolean("mpv_gpu_next");
    }

    public static void putMpvGpuNext(boolean gpuNext) {
        Prefers.put("mpv_gpu_next", gpuNext);
    }

    public static boolean isMpvVulkan() {
        return Prefers.getBoolean("mpv_vulkan");
    }

    public static void putMpvVulkan(boolean vulkan) {
        Prefers.put("mpv_vulkan", vulkan);
    }

    public static int getRender() {
        return Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
    }

    public static void putRender(int render) {
        Prefers.put("render", Math.clamp(render, RENDER_SURFACE, RENDER_TEXTURE));
        if (isExo() && DecodeSetting.isTunnel() && getRender() == RENDER_TEXTURE) DecodeSetting.putTunnel(false);
    }

    public static int getSize() {
        return Math.clamp(Prefers.getInt("size", 2), MIN_SIZE, MAX_SIZE);
    }

    public static void putSize(int size) {
        Prefers.put("size", Math.clamp(size, MIN_SIZE, MAX_SIZE));
    }

    public static int getScale() {
        return Math.clamp(Prefers.getInt("scale"), MIN_SCALE, MAX_SCALE);
    }

    public static void putScale(int scale) {
        Prefers.put("scale", Math.clamp(scale, MIN_SCALE, MAX_SCALE));
    }

    public static int getBackground() {
        return Math.clamp(Prefers.getInt("background", 2), MIN_BACKGROUND, MAX_BACKGROUND);
    }

    public static void putBackground(int background) {
        Prefers.put("background", Math.clamp(background, MIN_BACKGROUND, MAX_BACKGROUND));
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static int getBuffer() {
        return Math.clamp(Prefers.getInt("buffer"), MIN_BUFFER, MAX_BUFFER);
    }

    public static void putBuffer(int buffer) {
        Prefers.put("buffer", Math.clamp(buffer, MIN_BUFFER, MAX_BUFFER));
    }
}
