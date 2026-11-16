package com.muse.android.tv.setting;

// Adapter for the ported webhtv player layer: webhtv's performance-option
// catalog is part of its per-kernel performance-profile system, which this
// shell does not adopt. Only the option ids the player layer actually
// references are provided, with webhtv's original id values.
public final class PlaybackPerformanceCatalog {

    public static final String BUFFER_TIME = "buffer_time";
    public static final String PRELOAD_THREADS = "preload_threads";
    public static final String MPV_HLS_BITRATE = "mpv_hls_bitrate";

    private PlaybackPerformanceCatalog() {
    }
}
