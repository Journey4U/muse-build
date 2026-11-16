package androidx.media3.exoplayer.libass;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;

// Compile-only stub for FongMi's private libass fork. Not functional at runtime.
public final class LibassPlaybackSession {

    public LibassPlaybackSession(LibassConfiguration configuration, boolean enabled) {}

    public boolean isAvailable() {
        return false;
    }

    public void setBottomPositionFraction(float fraction) {}

    public void setSecondaryBottomPositionFraction(float fraction) {}

    public void setFontScale(float scale, boolean force) {}

    public void setPreloadMediaItem(MediaItem mediaItem) {}

    public void close() {}

    public Renderer createClockRenderer() {
        return null;
    }

    public MediaComponents createMediaComponents(MediaItem mediaItem, ExtractorsFactory extractorsFactory) {
        return new MediaComponents();
    }

    public static final class MediaComponents {

        public ExtractorsFactory extractorsFactory;
        public SubtitleParser.Factory subtitleParserFactory;
    }
}
