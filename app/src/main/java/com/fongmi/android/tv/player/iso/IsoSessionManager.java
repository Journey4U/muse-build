package com.fongmi.android.tv.player.iso;

import java.nio.ByteBuffer;

/**
 * JNI bridge kept at the original webhtv class name.
 *
 * <p>The prebuilt native bridge {@code libplayer.so} (webhtv's
 * {@code third_party/mpv-player-jni/src/iso_dvd.cpp}, {@code register_iso_protocol})
 * hardcodes this exact class name: during {@code MPVLib.create()} it runs
 * {@code FindClass("com/fongmi/android/tv/player/iso/IsoSessionManager")} and
 * caches {@code GetStaticMethodID} handles for the four static methods below.
 * If the class is missing, {@code FindClass} leaves a pending
 * {@code NoClassDefFoundError} that poisons the whole MPV initialization.
 *
 * <p>The implementation lives in
 * {@link com.muse.android.tv.player.iso.IsoSessionManager}; this class is a
 * stateless forwarder so Java callers and native callbacks share the one
 * session state held by the implementation class. Do not rename, move, or
 * remove this class, and keep the matching {@code -keep} rule in
 * {@code proguard-rules-media.pro} intact.
 */
public final class IsoSessionManager {

    private IsoSessionManager() {
    }

    public static long length(long id) {
        return com.muse.android.tv.player.iso.IsoSessionManager.length(id);
    }

    public static int readAt(long id, long offset, ByteBuffer target, int length) {
        return com.muse.android.tv.player.iso.IsoSessionManager.readAt(id, offset, target, length);
    }

    public static void close(long id) {
        com.muse.android.tv.player.iso.IsoSessionManager.close(id);
    }

    public static void prepareTrackMetadata(long id, int playlist) {
        com.muse.android.tv.player.iso.IsoSessionManager.prepareTrackMetadata(id, playlist);
    }
}
