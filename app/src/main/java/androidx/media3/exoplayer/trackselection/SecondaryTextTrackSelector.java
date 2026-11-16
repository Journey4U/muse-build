package androidx.media3.exoplayer.trackselection;

import android.content.Context;

// Functional replacement for FongMi's private SecondaryTextTrackSelector.
// The previous compile-only stub returned null from createTrackSelector(),
// which crashed DefaultPreloadManager (Objects.requireNonNull) during
// PlaybackService startup. This factory delegates to the wrapped factory so
// a real TrackSelector (DecodeTrackSelector) is always produced. The
// secondary (dual-subtitle) text track itself stays unwired, matching the
// other libass stubs.
public final class SecondaryTextTrackSelector {

    private SecondaryTextTrackSelector() {}

    public static final class Factory implements TrackSelector.Factory {

        private final TrackSelector.Factory trackSelectorFactory;

        public Factory(TrackSelector.Factory trackSelectorFactory) {
            this.trackSelectorFactory = trackSelectorFactory;
        }

        @Override
        public TrackSelector createTrackSelector(Context context) {
            return trackSelectorFactory.createTrackSelector(context);
        }
    }
}
