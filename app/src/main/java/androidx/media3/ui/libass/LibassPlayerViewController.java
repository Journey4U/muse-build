package androidx.media3.ui.libass;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.libass.LibassPlaybackSession;
import androidx.media3.exoplayer.libass.LibassSubtitleController;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.function.Consumer;

// Compile-only stub for FongMi's private libass fork. Not functional at runtime.
public final class LibassPlayerViewController {

    public LibassPlayerViewController(
            Player player,
            LibassPlaybackSession libassPlaybackSession,
            LibassSubtitleController libassSubtitleController) {}

    public void close() {}

    public void bind(PlayerView playerView) {}

    public void setStyleOverride(CaptionStyleCompat style, String fontFamily) {}

    public void setSecondarySubtitleViewConfigurator(Consumer<SubtitleView> configurator) {}
}
