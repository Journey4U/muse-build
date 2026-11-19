package com.muse.android.tv.api.loader;

// push v1：本文件放在 mobile 源集（不在 main）——它依赖移动端专属的 AdDomains（移动端广告域名表），
// main 源集看不到 mobile 的类；电视端 VideoActivity 不覆写 resolveWebEpisode（默认 false），永不碰它。

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.muse.android.tv.api.config.RuleConfig;
import com.muse.android.tv.ui.activity.AdDomains;
import com.muse.android.tv.api.loader.BrowserMeta;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.utils.Sniffer;
import com.github.catvod.utils.Util;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * push v1：移植自 fuzi WebEpisodeResolver（升级版138）——浏览器模式「全集」的逐集解析器（网页蜘蛛）。
 * 改动：包名 com.muse；WebView 基础配置内联（MuseTV 的 WebViewUtil 无 configureBase）；
 * 广告判定改用 MuseTV 移动端 AdDomains + RuleConfig；Resolved 由 record 改为静态内部类。
 *
 * <p>浏览器页只嗅得到当前集的流；其余集只有播放页网址。本类在用户
 * 点到某一集时，用一个不可见的 WebView 现开那集的播放页、等它自己
 * 把流请求出来（路径结尾为真流后缀的第一条），带上该页的
 * Referer/UA/Cookie 交回播放器。20 秒超时判失败。解析结果按页
 * 缓存，本会话内重播同集直接命中。
 *
 * <p>线程纪律（137 教训）：WebView 只在主线程碰；拦截回调在后台
 * 线程只读 request 数据、经 Handler 回主线程收尾。
 */
public class WebEpisodeResolver {

    public static final String PREFIX = "webep:";
    private static final long TIMEOUT_MS = 20000;
    private static final Map<String, Resolved> CACHE = new ConcurrentHashMap<>();

    public interface Callback {
        void onResolved(String url, Map<String, String> headers);

        void onFailed();
    }

    private static final class Resolved {
        final String url;
        final Map<String, String> headers;

        Resolved(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    private final Activity activity;
    private final String pageUrl;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean done;

    private WebEpisodeResolver(Activity activity, String pageUrl, Callback callback) {
        this.activity = activity;
        this.pageUrl = pageUrl;
        this.callback = callback;
    }

    public static void resolve(Activity activity, String pageUrl, Callback callback) {
        Resolved cached = CACHE.get(pageUrl);
        if (cached != null) {
            callback.onResolved(cached.url, cached.headers);
            return;
        }
        new WebEpisodeResolver(activity, pageUrl, callback).start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void start() {
        webView = new WebView(activity);
        webView.setVisibility(View.INVISIBLE);
        ViewGroup parent = (ViewGroup) activity.getWindow().getDecorView();
        parent.addView(webView, new ViewGroup.LayoutParams(8, 8));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        WebSettings settings = webView.getSettings();
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        settings.setUserAgentString(Setting.getUa());
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebResourceResponse empty = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                String host = request.getUrl() == null ? null : request.getUrl().getHost();
                if (!TextUtils.isEmpty(host) && isAd(host)) return empty;
                if (!request.isForMainFrame() && Sniffer.isVideoFormat(url) && BrowserMeta.isDirectStream(url)) {
                    Map<String, String> headers = captureHeaders(request, url);
                    main.post(() -> finish(url, headers));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 有些播放页要等一次点击才肯请求流，补一次无害的催播
                view.postDelayed(() -> {
                    if (!done && webView != null) webView.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v){try{v.play();}catch(e){}}})();", null);
                }, 1500);
            }
        });
        main.postDelayed(() -> finish(null, null), TIMEOUT_MS);
        webView.loadUrl(pageUrl);
    }

    /** 拦截线程专用：只读 request 与 CookieManager，不碰 WebView 本体。 */
    private Map<String, String> captureHeaders(WebResourceRequest request, String url) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> reqHeaders = request.getRequestHeaders();
        if (reqHeaders != null) {
            for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                if (entry.getKey().equalsIgnoreCase("Referer") || entry.getKey().equalsIgnoreCase("User-Agent"))
                    headers.put(entry.getKey(), entry.getValue());
            }
        }
        if (!hasHeader(headers, "Referer")) headers.put("Referer", pageUrl);
        if (!hasHeader(headers, "User-Agent")) headers.put("User-Agent", Setting.getUa());
        String cookie = CookieManager.getInstance().getCookie(url);
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        return headers;
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) if (key.equalsIgnoreCase(name)) return true;
        return false;
    }

    private boolean isAd(String host) {
        if (AdDomains.isAdDomain(host)) return true;
        for (String ad : RuleConfig.get().getAds()) if (Util.containOrMatch(host, ad)) return true;
        return false;
    }

    private void finish(String url, Map<String, String> headers) {
        if (done) return;
        done = true;
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup parent) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        if (url == null) {
            callback.onFailed();
        } else {
            CACHE.put(pageUrl, new Resolved(url, headers));
            callback.onResolved(url, headers);
        }
    }
}
