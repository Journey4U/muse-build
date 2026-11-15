package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;

import java.util.List;

/**
 * Spike 0: GeckoView verification (spike branch only, NOT product code).
 * Runs on launch: installs the built-in test extension, loads a local test
 * page that requests ad domains, then http://www.qiyoudy2.com, then
 * https://missav.ai. All results go to logcat (tag SPIKE) and on screen.
 */
public class GeckoSpikeActivity extends Activity {
    private static final String TAG = "SPIKE";
    private static final String EXT_LOCATION = "resource://android/assets/muse-spike-ext/";
    private static final String EXT_ID = "muse-spike@example.com";

    private TextView logView;
    private GeckoRuntime runtime;
    private GeckoSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private boolean testPageLoaded;

    private void spikeLog(String msg) {
        Log.i(TAG, msg);
        synchronized (logBuf) {
            logBuf.append(msg).append("\n");
            if (logBuf.length() > 20000) logBuf.delete(0, logBuf.length() - 20000);
        }
        runOnUiThread(() -> {
            if (logView != null) logView.setText(logBuf.toString());
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GeckoView geckoView = new GeckoView(this);
        root.addView(geckoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        spikeLog("SPIKE-START");
        GeckoRuntimeSettings settings;
        try {
            settings = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(true)
                    .build();
            runtime = GeckoRuntime.create(this, settings);
            spikeLog("runtime-created");
        } catch (Throwable t) {
            spikeLog("runtime-create-FAILED " + t);
            return;
        }
        session = new GeckoSession();
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                    Boolean hasUserGesture) {
                spikeLog("location " + url);
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                spikeLog("load-request " + request.uri);
                return null;
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String uri,
                    WebRequestError error) {
                spikeLog("load-error " + uri + " err=" + error);
                return null;
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                spikeLog("new-session-blocked " + uri);
                return null;
            }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                spikeLog("page-start " + url);
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                spikeLog("page-stop success=" + success);
            }
        });
        try {
            session.open(runtime);
            geckoView.setSession(session);
            spikeLog("session-opened");
        } catch (Throwable t) {
            spikeLog("session-open-FAILED " + t);
            return;
        }
        try {
            spikeLog("default-ua " + session.getUserAgent());
        } catch (Throwable t) {
            spikeLog("getUserAgent-FAILED " + t);
        }
        // Install the built-in test extension, then load the test page.
        runtime.getWebExtensionController().ensureBuiltIn(EXT_LOCATION, EXT_ID).accept(
                extension -> {
                    spikeLog("extension-installed id=" + EXT_ID);
                    WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
                        @Override
                        public GeckoResult<Object> onMessage(String nativeApp, Object message,
                                WebExtension.MessageSender sender) {
                            spikeLog("ext-msg " + String.valueOf(message));
                            return null;
                        }
                    };
                    try {
                        extension.setMessageDelegate(delegate, "browser");
                        spikeLog("bg-message-delegate-set");
                    } catch (Throwable t) {
                        spikeLog("bg-delegate-FAILED " + t);
                    }
                    try {
                        session.getWebExtensionController()
                                .setMessageDelegate(extension, delegate, "browser");
                        spikeLog("content-message-delegate-set");
                    } catch (Throwable t) {
                        spikeLog("content-delegate-FAILED " + t);
                    }
                    loadTestPage();
                },
                e -> {
                    spikeLog("extension-install-FAILED " + e);
                    loadTestPage();
                }
        );
        // Side checks on a timer (only after the test page had its run).
        handler.postDelayed(() -> {
            spikeLog("loading http qiyou");
            try { session.loadUri("http://www.qiyoudy2.com"); } catch (Throwable t) { spikeLog("qiyou-load-FAILED " + t); }
        }, 20000);
        handler.postDelayed(() -> {
            spikeLog("loading missav");
            try { session.loadUri("https://missav.ai"); } catch (Throwable t) { spikeLog("missav-load-FAILED " + t); }
        }, 45000);
        handler.postDelayed(() -> spikeLog("SPIKE-DONE"), 90000);
    }

    private void loadTestPage() {
        if (testPageLoaded) return;
        testPageLoaded = true;
        spikeLog("loading test page");
        try {
            session.loadUri("resource://android/assets/spike-test.html");
        } catch (Throwable t) {
            spikeLog("testpage-load-FAILED " + t);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
