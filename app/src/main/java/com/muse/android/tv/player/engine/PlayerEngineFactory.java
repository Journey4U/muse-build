package com.muse.android.tv.player.engine;

import static com.muse.android.tv.player.engine.PlayerEngine.Type.EXO;
import static com.muse.android.tv.player.engine.PlayerEngine.Type.MPV;

import androidx.media3.common.Player;

import com.muse.android.tv.player.exo.ExoPlayerEngine;
import com.muse.android.tv.player.media.PlaySpec;
import com.muse.android.tv.player.mpv.MpvPlayerEngine;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.utils.UrlUtil;

public final class PlayerEngineFactory {

    public static PlayerEngine create(int decode, Player.Listener listener) {
        return create(decode, resolve(), listener);
    }

    public static PlayerEngine create(int decode, PlaySpec spec, Player.Listener listener) {
        return create(decode, resolve(spec), listener);
    }

    public static PlayerEngine create(int decode, PlayerEngine.Type type, Player.Listener listener) {
        return switch (type) {
            case EXO -> new ExoPlayerEngine(decode, listener);
            case MPV -> new MpvPlayerEngine(decode, listener);
        };
    }

    /** v54 有声无画恢复：本片临时换核（不持久化）。override 为空或该片用不了时照常规则。 */
    public static PlayerEngine create(int decode, PlaySpec spec, PlayerEngine.Type override, Player.Listener listener) {
        return create(decode, resolve(spec, override), listener);
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec, PlayerEngine.Type override) {
        return engine != null && engine.getType() == resolve(spec, override) && !engine.needsRebuild();
    }

    /** v54：该片能否用指定核——DRM/smb 只能 Exo；MPV 须库可用。 */
    public static boolean canUse(PlaySpec spec, PlayerEngine.Type type) {
        if (spec == null || type == null) return false;
        if (type == MPV) return !requiresExo(spec) && MpvPlayerEngine.isAvailable();
        return true;
    }

    private static PlayerEngine.Type resolve(PlaySpec spec, PlayerEngine.Type override) {
        return override != null && canUse(spec, override) ? override : resolve(spec);
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == resolve(spec) && !engine.needsRebuild();
    }

    private static PlayerEngine.Type resolve(PlaySpec spec) {
        if (requiresExo(spec)) return EXO;
        if (!isMpvReady()) return EXO;
        return MPV;
    }

    private static PlayerEngine.Type resolve() {
        return isMpvReady() ? MPV : EXO;
    }

    private static boolean requiresExo(PlaySpec spec) {
        return spec.getDrm() != null || "smb".equals(UrlUtil.scheme(spec.getUrl()));
    }

    private static boolean isMpvReady() {
        return PlayerSetting.isMpv() && MpvPlayerEngine.isAvailable();
    }
}
