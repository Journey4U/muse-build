package androidx.media3.ui.danmaku;

import android.net.Uri;
import androidx.media3.ui.PlayerView;
import okhttp3.OkHttpClient;

// Compile-only stub for FongMi's private danmaku controller. Method signatures match
// the private implementation (verified against v24 bytecode); bodies are no-ops,
// so danmaku rendering is non-functional at runtime.
public class DanmakuPlayerViewController {

    public DanmakuPlayerViewController() {}

    public void bind(PlayerView playerView) {}

    public void close() {}

    public void sendNow(String text) {}

    public void setConfig(DanmakuConfig config) {}

    public void setDataSource(Uri uri) {}

    public void setEnabled(boolean enabled) {}

    public void setOkHttpClient(OkHttpClient client) {}
}
