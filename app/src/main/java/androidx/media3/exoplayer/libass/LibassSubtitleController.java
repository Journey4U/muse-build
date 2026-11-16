package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.exoplayer.text.SecondaryTextOutput;
import androidx.media3.exoplayer.trackselection.SecondaryTextTrackSelector;

import java.util.Collections;
import java.util.List;

// Compile-only stub for FongMi's private libass fork. Not functional at runtime.
public final class LibassSubtitleController {

    public LibassSubtitleController(
            Player player,
            LibassPlaybackSession libassPlaybackSession,
            SecondaryTextTrackSelector.Factory secondaryTextTrackSelectorFactory,
            SecondaryTextOutput secondaryTextOutput) {}

    public void close() {}

    @Nullable
    public TrackSelectionOverride getPrimaryTextTrackSelectionOverride() {
        return null;
    }

    @Nullable
    public TrackSelectionOverride getSecondaryTextTrackSelectionOverride() {
        return null;
    }

    public List<TrackSelectionOverride> getSecondaryTextTrackSelectionOverrides() {
        return Collections.emptyList();
    }

    public boolean isSecondaryTextTrackSuppressed() {
        return false;
    }

    public void setSecondaryTextTrackSelectionOverride(@Nullable TrackSelectionOverride selection) {}

    public void setSecondaryTextTrackAutoSelectionEnabled(boolean enabled) {}
}
