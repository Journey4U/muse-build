package com.muse.android.tv.api.loader;

// push v2 Phase A：纯 HTTP 逐集解析器，替代已删除的隐形 WebView 逐集解析器（fuzi 移植版）。
// 本文件不含任何 WebView / WebViewClient / evaluateJavascript：四层全部走 OkHttp 纯 HTTP。
//
// 层级（先成功者胜，失败全部如实落日志、绝不交假流）：
//   L0 Provide API —— {origin}/api.php/provide/vod/?ac=detail&ids={vid}，一次拿全线全集，
//      命中即用；API 失败/无可用数据则记「API closed」，本会话内同一 (origin, vid) 永不重试。
//   L1 播放页 player_aaaa / player_data / player_config —— 明文直链直接探活；密文 + from
//      走 playerconfig.js 的 from → parse 前缀表再解（fuzi ddPlay 链路，算法重写，见 ddResolve）；
//      密文无 parse 前缀时再试 art.php 后端签名链（push v8，见 artPhpResolve）：
//      令牌 → art.php?get_signed_url=1 → fetch_data → jmurl，按内容探活（不用密钥）；
//      ID 形 url（如 "11602|1"）HTTP 解不了，交给 L2。
//   L2 通用候选 —— 页内明文直链 / iframe 参数 / iframe 内页 / eval packer 解包，逐个探活。
//   L4 ArtPlayer 解析播放器 —— 仅 L0/L1/L2 全败后：播放页 iframe → iframe 页
//      const Url / Sign / From 三行字面量 → {iframe 目录}api.php?url=&sign=&t=
//      → JSON code==200 取 ret.url（无 ret 时取顶层 url）→ 探活；按形认，不认域名。
//   L5 子页解析（push v12）：探针 subEps（无 .dplayer 文章的子帖表）——并行取子页，
//      首个 .dplayer 的 video.url（没有取 video_h265.url）拼完整分集；集名 = 链接文字，
//      没有就用子页标题/h1，再没有就用「剧集-{帖子id}」，还没有就用「第N集」（push v13 对齐电视）；子页取不到视频就跳过该集，
//      全部取不到就 onFailed，绝不交假流。直取失败时按 py 请求头（桌面 UA + 站根 Referer）重试一次。
//   L-direct 直链快路径（push v12）：集地址本身就是直链（多视频/子页合成线的非当前集，
//      applyBrowserLines 把直链记进 pageUrl）时直接探活返回，不再当播放页解析。
//   全败 → onFailed()，不交假流。
//
// 线程：resolve() 在后台线程跑网络，回调一律经主线程 Handler 回调；结果按播放页 URL 缓存。
// 单次请求超时 9 秒（OkHttp.client(TIMEOUT_MS)）；m3u8 探活走 GET + #EXTM3U 校验，
// 其余媒体先 HEAD、不支持再退 Range GET（fuzi probe 结论：m3u8 绝不能带 Range，会误杀）。

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;
import com.github.catvod.net.OkHttp;
import com.muse.android.tv.App;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.utils.Sniffer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class HttpEpisodeResolver {

    private static final long TIMEOUT_MS = 9000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_CANDIDATES = 8;
    private static final int LOG_URL_LEN = 90;

    private static final Map<String, Resolved> CACHE = new ConcurrentHashMap<>();
    /** 每部剧的 L0 全量表：key = origin + "\n" + vid，value = 按线路分的集表。 */
    private static final Map<String, List<List<Ep>>> SHOW_CACHE = new ConcurrentHashMap<>();
    /** 已证实关闭的 Provide API：key = origin + "\n" + vid，本会话内永不重试。 */
    private static final Set<String> API_CLOSED = ConcurrentHashMap.newKeySet();
    /** playerconfig.js 的 from → parse 前缀表：key = 配置地址 host。 */
    private static final Map<String, Map<String, String>> PARSE_CACHE = new ConcurrentHashMap<>();

    private static final Pattern PLAYER_VAR = Pattern.compile("var (?:player_aaaa|player_data|player_config)\\s*=\\s*(\\{.*?\\})\\s*</script>", Pattern.DOTALL);
    private static final Pattern PLAYER_CFG_SRC = Pattern.compile("<script[^>]+src=[\"']([^\"']*playerconfig[^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARSE_ENTRY = Pattern.compile("\"([\\w-]+)\"\\s*:\\s*\\{[^{}]*?\"parse\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PARSE_JSON_URL = Pattern.compile("JSON\\.parse\\('[^']*?\"url\"\\s*:\\s*\"(https?://[^\"']+?)\"");
    private static final Pattern PLAIN_JSON_URL = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"']+?)\"");
    private static final Pattern P_M3U8 = Pattern.compile("(https?:\\\\?/\\\\?/[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_MP4 = Pattern.compile("(https?:\\\\?/\\\\?/[^\\s\"'<>]+?\\.(?:mp4|flv|mkv)[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IFRAME_SRC = Pattern.compile("<iframe[^>]+src=[\"']([^\"']{6,500})[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_MEDIA_PARAM = Pattern.compile("[?&](?:video_url|play_url|url|v|src|m3u8)=([^&#\"']{6,900})", Pattern.CASE_INSENSITIVE);
    /** L4 ArtPlayer：iframe 播放器页内的三行字面量（值是字面字符串，不跑 JS）。 */
    private static final Pattern ART_URL = Pattern.compile("const\\s+Url\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ART_SIGN = Pattern.compile("const\\s+Sign\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ART_FROM = Pattern.compile("const\\s+From\\s*=\\s*\"([^\"]+)\"");
    /** push v8：art.php 链——播放页 maccms.path（线路播放器 JS 的目录前缀）。 */
    private static final Pattern MACCMS_PATH = Pattern.compile("var\\s+maccms\\s*=\\s*\\{[^}]*?\"path\"\\s*:\\s*\"([^\"]*)\"");
    /** push v8：art.php 链——线路播放器 JS（如 NBY.js）里的 art.php 相对路径。 */
    private static final Pattern ART_PHP_PATH = Pattern.compile("([^\\s\"']*art\\.php)");
    private static final Pattern SID_EP = Pattern.compile("-(\\d+)-(\\d+)\\.html");
    /** push v12：子页首个 .dplayer 的 data-config（单引号属性内是 JSON 双引号串）。 */
    private static final Pattern DPLAYER_CONFIG = Pattern.compile("<[^>]*\\bdplayer\\b[^>]*data-config\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DPLAYER_VIDEO_URL = Pattern.compile("\"video\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DPLAYER_H265_URL = Pattern.compile("\"video_h265\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TITLE_TAG = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern H1_TAG = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ARCHIVES_ID = Pattern.compile("/archives/(\\d+)");
    private static final int MAX_SUB_PAGES = 20;
    private static final Pattern EP_NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern PACKER_ARGS = Pattern.compile("\\('([\\s\\S]*?)',(\\d+),(\\d+),'([\\s\\S]*?)'\\.split\\('\\|'\\)");
    private static final Pattern PACKER_WORD = Pattern.compile("\\b[a-z0-9]+\\b");
    private static final Pattern REWRITE_TPL = Pattern.compile("\\.replace\\(\\s*(?:/(?:\\\\.|[^/\\\\\\n])+/[gimsuy]*|[^,]*)\\s*,\\s*['\"]([^'\"]*?)\\$1/?['\"]\\)");
    private static final Pattern SCHEME_HOST = Pattern.compile("^(https?://[^/]+)");

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

    private static final class Ep {
        final String name;
        final String url;

        Ep(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;
        /** 跟完 3xx 后的最终地址（无跳转时即请求地址）；L4 由此取 iframe 目录。 */
        final String url;

        HttpResult(int code, String body, String url) {
            this.code = code;
            this.body = body;
            this.url = url == null ? "" : url;
        }
    }

    private final String pageUrl;
    private final String origin;
    private final String vid;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HttpEpisodeResolver(String pageUrl, String origin, String vid, Callback callback) {
        this.pageUrl = pageUrl;
        this.origin = origin == null ? "" : origin.trim();
        this.vid = vid == null ? "" : vid.trim();
        this.callback = callback;
    }

    /**
     * 入口：worker B 从 VideoActivity.resolveWebEpisode 调用。
     * activity 仅为保持与旧解析器相同的调用形状而保留，
     * v2 不再建 WebView，解析全程不需要它（可传 null 以外的任意 Activity）。
     * origin / vid 为空时跳过 L0。
     */
    public static void resolve(Activity activity, String pageUrl, String origin, String vid, Callback callback) {
        if (callback == null || TextUtils.isEmpty(pageUrl)) {
            if (callback != null) new Handler(Looper.getMainLooper()).post(callback::onFailed);
            return;
        }
        Resolved cached = CACHE.get(pageUrl);
        if (cached != null) {
            new Handler(Looper.getMainLooper()).post(() -> callback.onResolved(cached.url, cached.headers));
            return;
        }
        new Thread(new HttpEpisodeResolver(pageUrl, origin, vid, callback)::run).start();
    }

    /**
     * push v15：绕过 CACHE 的重解析——缓存的地址可能是已过期的限时签名，
     * v4 探活判死 / v5 报错恢复时重取必须拿新签名；取到后仍由 finish() 写回 CACHE。
     * 其余行为与 resolve 完全一致。
     */
    public static void resolveFresh(Activity activity, String pageUrl, String origin, String vid, Callback callback) {
        if (callback == null || TextUtils.isEmpty(pageUrl)) {
            if (callback != null) new Handler(Looper.getMainLooper()).post(callback::onFailed);
            return;
        }
        new Thread(new HttpEpisodeResolver(pageUrl, origin, vid, callback)::run).start();
    }

    private void run() {
        try {
            log("resolve page=" + trunc(pageUrl) + " vid=" + vid);
            int[] sidEp = parseSidEp(pageUrl);
            int sid = sidEp[0];
            int epNum = sidEp[1];
            Map<String, String> headers = pageHeaders(pageUrl);

            // push v12：直链快路径——集地址本身就是直链（多视频/子页合成线的非当前集，
            // applyBrowserLines 把直链记进 pageUrl）时直接探活返回，不再当播放页解析。
            if (validStream(pageUrl) && probe(pageUrl, headers)) {
                finish(pageUrl, streamHeaders(pageUrl, pageUrl), "L-direct");
                return;
            }

            String stream = layer0(sid, epNum, headers);
            if (stream != null) {
                finish(stream, streamHeaders(stream, pageUrl), "L0");
                return;
            }

            HttpResult page = get(pageUrl, headers);
            log("L1 page http=" + page.code + " len=" + page.body.length());
            if (page.code == -1) logError("L1 page GET blocked/timeout");
            String html = page.body;
            List<String> extraCandidates = new ArrayList<>();
            if (!TextUtils.isEmpty(html)) {
                stream = layer1(html, headers, extraCandidates);
                if (stream != null) {
                    finish(stream, streamHeaders(stream, pageUrl), "L1");
                    return;
                }
                stream = layer2(html, headers, extraCandidates);
                if (stream != null) {
                    finish(stream, streamHeaders(stream, pageUrl), "L2");
                    return;
                }
                stream = layer4(html, headers);
                if (stream != null) {
                    finish(stream, streamHeaders(stream, pageUrl), "L4");
                    return;
                }
            } else {
                log("L1 skip empty-page http=" + page.code);
            }
            logError("FAIL all-layers page=" + trunc(pageUrl));
            fail();
        } catch (Throwable t) {
            logError("FAIL exception " + t.getClass().getSimpleName());
            fail();
        }
    }


    // ================= L5：子页解析（push v12） =================

    public interface SubPagesCallback {
        void onResolved(String episodesJson);

        void onFailed();
    }

    /**
     * push v12：子页解析层（L5）——探针在无 .dplayer 的文章里取到的 subEps（[{name,url}]），
     * 且无 lines/mainStream 时调用：并行取每个子页，解析首个 .dplayer 的 video.url
     * （没有取 video_h265.url），拼成完整分集。集名 = 链接文字，没有就用子页标题/h1，
     * 再没有就用「剧集-{帖子id}」，还没有就用「第N集」（push v13 对齐电视）。子页取不到视频就跳过该集；全部取不到就 onFailed，
     * 绝不交假流。单个子页沿用 TIMEOUT_MS 超时；上限 20 个。
     */
    public static void resolveSubPages(String pageUrl, String subEpsJson, SubPagesCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        if (callback == null || TextUtils.isEmpty(pageUrl) || TextUtils.isEmpty(subEpsJson)) {
            if (callback != null) mainHandler.post(callback::onFailed);
            return;
        }
        List<Ep> subs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            JSONArray arr = new JSONArray(subEpsJson);
            for (int i = 0; i < arr.length() && subs.size() < MAX_SUB_PAGES; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String raw = o.optString("url", "");
                if (TextUtils.isEmpty(raw)) continue;
                String abs = absUrl(raw, pageUrl);
                if (!abs.startsWith("http")) continue;
                String norm = abs.split("#")[0].split("\\?")[0];
                if (norm.endsWith("/")) norm = norm.substring(0, norm.length() - 1);
                String self = pageUrl.split("#")[0].split("\\?")[0];
                if (self.endsWith("/")) self = self.substring(0, self.length() - 1);
                if (norm.equals(self)) continue;
                if (!seen.add(norm)) continue;
                subs.add(new Ep(o.optString("name", ""), abs));
            }
        } catch (Exception e) {
            mainHandler.post(callback::onFailed);
            return;
        }
        if (subs.isEmpty()) {
            mainHandler.post(callback::onFailed);
            return;
        }
        HttpEpisodeResolver worker = new HttpEpisodeResolver(pageUrl, "", "", null);
        new Thread(() -> {
            List<Ep> done = worker.resolveSubPagesSync(subs);
            if (done.isEmpty()) {
                logError("L5 subpages fail all n=" + subs.size());
                mainHandler.post(callback::onFailed);
                return;
            }
            try {
                JSONArray out = new JSONArray();
                for (Ep ep : done) {
                    JSONObject o = new JSONObject();
                    o.put("name", ep.name);
                    o.put("url", ep.url);
                    out.put(o);
                }
                log("L5 subpages ok n=" + done.size() + "/" + subs.size());
                mainHandler.post(() -> callback.onResolved(out.toString()));
            } catch (Exception e) {
                mainHandler.post(callback::onFailed);
            }
        }).start();
    }

    /** 并行取子页（上限 20 路），按原顺序收结果、跳过取不到视频的集。 */
    private List<Ep> resolveSubPagesSync(List<Ep> subs) {
        int n = subs.size();
        Ep[] results = new Ep[n];
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, MAX_SUB_PAGES));
        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final Ep sub = subs.get(i);
            pool.submit(() -> {
                try {
                    results[idx] = resolveOneSubPage(sub, idx);
                } catch (Throwable t) {
                    results[idx] = null;
                } finally {
                    latch.countDown();
                }
            });
        }
        pool.shutdown();
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Ep> done = new ArrayList<>();
        for (Ep ep : results) if (ep != null) done.add(ep);
        return done;
    }

    /** 取一个子页：首个 .dplayer 的 video.url（没有取 video_h265.url）；直取失败时按 py 请求头重试一次。 */
    private Ep resolveOneSubPage(Ep sub, int idx) {
        Map<String, String> headers = subPageHeaders(pageUrl, sub.url);
        HttpResult res = get(sub.url, headers);
        if (res.code < 200 || res.code >= 400 || TextUtils.isEmpty(res.body)) {
            HttpResult retry = get(sub.url, pyStyleHeaders(pageUrl));
            if (retry.code >= 200 && retry.code < 400 && !TextUtils.isEmpty(retry.body)) res = retry;
        }
        if (res.code < 200 || res.code >= 400 || TextUtils.isEmpty(res.body)) {
            log("L5 sub miss http=" + res.code + " sub=" + trunc(sub.url));
            return null;
        }
        String stream = extractDplayerUrl(res.body);
        if (TextUtils.isEmpty(stream)) {
            return null;
        }
        String name = sub.name;
        if (TextUtils.isEmpty(name)) name = extractSubTitle(res.body);
        if (TextUtils.isEmpty(name)) {
            Matcher m = ARCHIVES_ID.matcher(sub.url);
            name = m.find() ? "剧集-" + m.group(1) : "";
        }
        if (TextUtils.isEmpty(name)) name = "第" + (idx + 1) + "集";
        log("L5 sub hit name=" + name + " stream=" + trunc(stream));
        return new Ep(name, stream);
    }

    /** 子页首个 .dplayer 的 data-config 解析：video.url，没有取 video_h265.url；JSON 解析失败时用正则兜底。 */
    private static String extractDplayerUrl(String html) {
        if (TextUtils.isEmpty(html)) return "";
        Matcher m = DPLAYER_CONFIG.matcher(html);
        if (m.find()) {
            String cfg = m.group(2).replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
            try {
                JSONObject j = new JSONObject(cfg);
                JSONObject video = j.optJSONObject("video");
                String u = video == null ? "" : video.optString("url", "");
                if (!TextUtils.isEmpty(u) && u.startsWith("http")) return u;
                JSONObject h265 = j.optJSONObject("video_h265");
                String h = h265 == null ? "" : h265.optString("url", "");
                if (!TextUtils.isEmpty(h) && h.startsWith("http")) return h;
            } catch (Exception ignored) {
            }
        }
        String u = firstGroup(DPLAYER_VIDEO_URL, html);
        if (!TextUtils.isEmpty(u) && u.startsWith("http")) return u;
        String h = firstGroup(DPLAYER_H265_URL, html);
        if (!TextUtils.isEmpty(h) && h.startsWith("http")) return h;
        return "";
    }

    /** 子页标题：<title>（去站名后缀）→ <h1>；都没有返回空串，调用方再用「剧集-{帖子id}」。 */
    private static String extractSubTitle(String html) {
        String title = firstGroup(TITLE_TAG, html);
        if (!TextUtils.isEmpty(title)) {
            title = title.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
            title = title.replaceAll("\\s*[–\\-|]\\s*51吃瓜.*$", "").replaceAll("\\s*[–\\-|]\\s*吃瓜.*$", "").trim();
            if (!title.isEmpty()) return title;
        }
        String h1 = firstGroup(H1_TAG, html);
        if (!TextUtils.isEmpty(h1)) {
            h1 = h1.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
            if (!h1.isEmpty()) return h1;
        }
        return "";
    }

    /** 子页请求头：Referer=父文章页、UA=Setting、Cookie=子页（没有取父页）。 */
    private Map<String, String> subPageHeaders(String parentUrl, String subUrl) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", parentUrl);
        headers.put("User-Agent", nativeUa());
        String cookie = getCookie(subUrl);
        if (TextUtils.isEmpty(cookie)) cookie = getCookie(parentUrl);
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        return headers;
    }

    /** py 式请求头（51吃瓜v3.py init）：桌面 UA + 站根 Origin/Referer；直取失败时重试一次用。 */
    private static Map<String, String> pyStyleHeaders(String parentUrl) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36");
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        String host = schemeHost(parentUrl);
        if (!host.isEmpty()) {
            if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
            h.put("Origin", host);
            h.put("Referer", host + "/");
        }
        return h;
    }

    // ================= L0：MacCMS Provide API =================

    private String layer0(int sid, int epNum, Map<String, String> headers) {
        if (TextUtils.isEmpty(origin) || TextUtils.isEmpty(vid)) {
            return null;
        }
        List<List<Ep>> show = loadShow(headers);
        if (show == null) return null;
        Ep ep = findEpisode(show, sid, epNum);
        if (ep == null) {
            return null;
        }
        if (!validStream(ep.url)) {
            return null;
        }
        if (!probe(ep.url, headers)) {
            log("L0 miss probe-fail stream=" + trunc(ep.url));
            return null;
        }
        return ep.url;
    }

    /** 取回并缓存整部剧的全线全集表；API 失败/无可用数据则记 closed，之后同一部剧不再请求。 */
    private List<List<Ep>> loadShow(Map<String, String> headers) {
        String key = origin + "\n" + vid;
        List<List<Ep>> cached = SHOW_CACHE.get(key);
        if (cached != null) return cached;
        if (API_CLOSED.contains(key)) {
            return null;
        }
        String base = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        String apiUrl;
        try {
            apiUrl = base + "/api.php/provide/vod/?ac=detail&ids=" + URLEncoder.encode(vid, "UTF-8");
        } catch (Exception e) {
            apiUrl = base + "/api.php/provide/vod/?ac=detail&ids=" + vid;
        }
        HttpResult res = get(apiUrl, headers);
        if (res.code < 200 || res.code >= 400 || TextUtils.isEmpty(res.body)) {
            API_CLOSED.add(key);
            log("L0 api closed http=" + res.code);
            return null;
        }
        List<List<Ep>> show = parseProvide(res.body);
        if (show == null || show.isEmpty()) {
            API_CLOSED.add(key);
            log("L0 api closed empty-list http=" + res.code);
            return null;
        }
        SHOW_CACHE.put(key, show);
        int total = 0;
        for (List<Ep> line : show) total += line.size();
        log("L0 api open http=" + res.code + " lines=" + show.size() + " eps=" + total);
        return show;
    }

    /** MacCMS Provide 响应：{"list":[{"vod_play_from":"线1$$$线2","vod_play_url":"第1集$直链#第2集$直链$$$..."}]}。 */
    private static List<List<Ep>> parseProvide(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray list = root.optJSONArray("list");
            if (list == null || list.length() == 0) return null;
            JSONObject vod = list.optJSONObject(0);
            if (vod == null) return null;
            String[] froms = vod.optString("vod_play_from", "").split("\\$\\$\\$");
            String[] urls = vod.optString("vod_play_url", "").split("\\$\\$\\$");
            List<List<Ep>> show = new ArrayList<>();
            int lines = Math.min(froms.length, urls.length);
            for (int i = 0; i < lines; i++) {
                List<Ep> line = new ArrayList<>();
                for (String item : urls[i].split("#")) {
                    if (TextUtils.isEmpty(item)) continue;
                    int sep = item.indexOf('$');
                    if (sep > 0) line.add(new Ep(item.substring(0, sep), item.substring(sep + 1)));
                    else if (item.startsWith("http")) line.add(new Ep("", item));
                }
                if (!line.isEmpty()) show.add(line);
            }
            return show.isEmpty() ? null : show;
        } catch (Exception e) {
            return null;
        }
    }

    /** sid 为播放页 URL 里的线路号（1 起）；先在该线找集号，找不到再扫其余线。 */
    private static Ep findEpisode(List<List<Ep>> show, int sid, int epNum) {
        List<Integer> order = new ArrayList<>();
        if (sid >= 1 && sid <= show.size()) order.add(sid - 1);
        for (int i = 0; i < show.size(); i++) if (!order.contains(i)) order.add(i);
        for (int li : order) {
            List<Ep> line = show.get(li);
            for (int j = 0; j < line.size(); j++) {
                Ep ep = line.get(j);
                int n = episodeNumber(ep.name);
                if (epNum > 0 && n == epNum) return ep;
                if (epNum > 0 && n < 0 && j + 1 == epNum) return ep;
            }
        }
        if (epNum <= 0 && !order.isEmpty() && !show.get(order.get(0)).isEmpty()) return show.get(order.get(0)).get(0);
        return null;
    }

    private static int episodeNumber(String name) {
        if (TextUtils.isEmpty(name)) return -1;
        Matcher m = EP_NUMBER.matcher(name);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return -1;
        }
    }

    /** 播放页 /vodplay/{vid}-{sid}-{ep}.html 里的线路号与集号；取不到则 sid=1、ep 未知。 */
    private static int[] parseSidEp(String pageUrl) {
        Matcher m = SID_EP.matcher(pageUrl == null ? "" : pageUrl);
        if (!m.find()) return new int[]{1, -1};
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (Exception e) {
            return new int[]{1, -1};
        }
    }

    // ================= L1：player_aaaa / player_data / player_config =================

    private String layer1(String html, Map<String, String> headers, List<String> extraCandidates) {
        Matcher pm = PLAYER_VAR.matcher(html);
        if (!pm.find()) {
            return null;
        }
        JSONObject pa;
        try {
            pa = new JSONObject(pm.group(1));
        } catch (Exception e) {
            log("L1 player_aaaa bad-json");
            return null;
        }
        String url = pa.optString("url", "");
        String from = pa.optString("from", "");
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (url.startsWith("http")) {
            if (validStream(url) && probe(url, headers)) return url;
            if (validStream(url)) log("L1 plaintext probe-fail");
            extraCandidates.add(url);
            return null;
        }
        if (!TextUtils.isEmpty(from)) {
            String stream = ddResolve(html, headers, url, from);
            if (!TextUtils.isEmpty(stream) && validStream(stream) && probe(stream, headers)) return stream;
            if (!TextUtils.isEmpty(stream)) log("L1 dd stream invalid/probe-fail " + trunc(stream));
            // push v8：art.php 后端签名链（麦田 NB 式）：密文令牌 + from 对应播放器 JS 里含 art.php；
            // 令牌 → get_signed_url → fetch_data → jmurl，纯 HTTP，不用密钥；jmurl 按内容探活。
            String art = artPhpResolve(html, headers, url, from);
            if (!TextUtils.isEmpty(art)) return art;
            return null;
        }
        return null;
    }

    /**
     * push v8：art.php 后端签名链（按形认，不认域名/站点）：
     * 播放页 player_data.url 是加密令牌（如 NBY-XMYAES…|…）、from 是线路代码（如 NBY）；
     * 线路播放器 JS（{origin}{maccms.path}/static/player/{from}.js）里含 art.php 路径；
     * GET art.php?get_signed_url=1&url=<令牌> → JSON {signed_url}；
     * GET 该 signed_url（相对地址按 art.php 解析）→ JSON {jmurl}；
     * jmurl 按内容探活（GET + #EXTM3U，路径不带 .m3u8 后缀，不走 validStream）。
     * 任一步失败返回 null，不交假流。2026-11-20 沙箱实测走通（麦田 NB 新鲜令牌 → 真清单）。
     */
    private String artPhpResolve(String html, Map<String, String> headers, String token, String from) {
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(from)) return null;
        String playerJs = playerJsUrl(from, firstGroup(MACCMS_PATH, html));
        if (TextUtils.isEmpty(playerJs)) {
            return null;
        }
        HttpResult js = get(playerJs, headers);
        if (js.code < 200 || js.code >= 400 || TextUtils.isEmpty(js.body) || !js.body.contains("art.php")) {
            log("L1 art miss no-art-php js-http=" + js.code);
            return null;
        }
        String artPath = firstGroup(ART_PHP_PATH, js.body);
        if (TextUtils.isEmpty(artPath)) {
            return null;
        }
        String artPhp = absUrl(artPath, playerJs);
        if (!artPhp.startsWith("http")) {
            return null;
        }
        String signedUrl = "";
        try {
            String u1 = artPhp + "?get_signed_url=1&url=" + URLEncoder.encode(token, "UTF-8");
            HttpResult r1 = get(u1, headers);
            if (r1.code < 200 || r1.code >= 400 || TextUtils.isEmpty(r1.body)) {
                logError("L1 art miss sign-http=" + r1.code);
                return null;
            }
            signedUrl = new JSONObject(r1.body).optString("signed_url", "");
        } catch (Exception e) {
            logError("L1 art miss sign-ex");
            return null;
        }
        if (TextUtils.isEmpty(signedUrl)) {
            return null;
        }
        String jmurl = "";
        try {
            // signed_url 是纯查询串（以 ? 开头，如 ?url=…&fetch_data=1…），须直接缀在 art.php 后；
            // absUrl 会把它当相对路径拼进目录（…/player/?url=…），服务器不认。
            String u2 = signedUrl.startsWith("?") ? artPhp + signedUrl : absUrl(signedUrl, artPhp);
            HttpResult r2 = get(u2, headers);
            if (r2.code < 200 || r2.code >= 400 || TextUtils.isEmpty(r2.body)) {
                logError("L1 art miss fetch-http=" + r2.code);
                return null;
            }
            jmurl = new JSONObject(r2.body).optString("jmurl", "");
        } catch (Exception e) {
            logError("L1 art miss fetch-ex");
            return null;
        }
        if (TextUtils.isEmpty(jmurl)) {
            return null;
        }
        jmurl = jmurl.replace("\\/", "/");
        if (!probeM3u8Content(jmurl, headers)) {
            log("L1 art miss probe-fail jmurl=" + trunc(jmurl));
            return null;
        }
        return jmurl;
    }

    /** 线路播放器 JS 地址：{origin}{maccms.path}/static/player/{from}.js（MacCMS 惯例，路径从播放页读，不写死）。 */
    private String playerJsUrl(String from, String maccmsPath) {
        if (TextUtils.isEmpty(from)) return "";
        String base = origin;
        if (TextUtils.isEmpty(base)) {
            base = schemeHost(pageUrl);
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        }
        if (TextUtils.isEmpty(base)) return "";
        String p = maccmsPath == null ? "" : maccmsPath;
        StringBuilder sb = new StringBuilder(base);
        if (!p.isEmpty()) {
            if (!p.startsWith("/")) sb.append('/');
            sb.append(p);
        }
        String s = sb.toString();
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s + "/static/player/" + from + ".js";
    }

    /** 按内容探活 m3u8（GET + #EXTM3U）：专供路径不带 .m3u8 后缀的清单地址（如 art.php 链的 jmurl）；不放宽 validStream 的路径口径。 */
    private boolean probeM3u8Content(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return false;
        HttpResult res = get(url, headers);
        if (res.code < 200 || res.code >= 400) return false;
        return res.body != null && res.body.contains("#EXTM3U");
    }

    /**
     * fuzi ddPlay 链路（算法重写，未抄代码）：
     * 播放页 var player_aaaa={"url":"密文","from":"zy1080_01"} → 播放页外链 playerconfig.js
     * 的 from → parse 前缀表 → GET parse + 密文 → 响应 JSON.parse('{"url":"...m3u8"}') → 直链。
     * player_aaaa 里没有 parse 字段，前缀必须查 playerconfig.js；外链路径从播放页提取，不写死。
     */
    private String ddResolve(String html, Map<String, String> headers, String enc, String from) {
        Matcher cm = PLAYER_CFG_SRC.matcher(html);
        if (!cm.find()) {
            return "";
        }
        String cfgUrl = absUrl(cm.group(1), pageUrl);
        String prefix = parsePrefix(cfgUrl, headers, from);
        if (TextUtils.isEmpty(prefix)) {
            return "";
        }
        String parseUrl;
        try {
            parseUrl = absUrl(prefix, cfgUrl) + URLEncoder.encode(enc, "UTF-8");
        } catch (Exception e) {
            parseUrl = absUrl(prefix, cfgUrl) + enc;
        }
        HttpResult res = get(parseUrl, headers);
        if (res.code < 200 || res.code >= 400 || TextUtils.isEmpty(res.body)) {
            log("L1 dd miss parse-http=" + res.code);
            return "";
        }
        String u = firstGroup(PARSE_JSON_URL, res.body);
        if (TextUtils.isEmpty(u)) u = firstGroup(PLAIN_JSON_URL, res.body);
        if (TextUtils.isEmpty(u)) {
            try {
                u = new JSONObject(res.body).optString("url", "");
            } catch (Exception ignored) {
            }
        }
        if (TextUtils.isEmpty(u)) {
            return "";
        }
        u = u.replace("\\/", "/");
        return u;
    }

    /** playerconfig.js 的 from → parse 前缀查表（按 host 缓存；值内 \/ 转义还原）。 */
    private String parsePrefix(String cfgUrl, Map<String, String> headers, String from) {
        String host = hostOf(cfgUrl).toLowerCase(Locale.ROOT);
        Map<String, String> table = PARSE_CACHE.get(host);
        if (table == null) {
            table = new LinkedHashMap<>();
            HttpResult res = get(cfgUrl, headers);
            if (!TextUtils.isEmpty(res.body)) {
                Matcher m = PARSE_ENTRY.matcher(res.body);
                while (m.find()) {
                    if (!TextUtils.isEmpty(m.group(2))) table.put(m.group(1), m.group(2).replace("\\/", "/"));
                }
            }
            if (!table.isEmpty()) PARSE_CACHE.put(host, table);
        }
        String prefix = table.get(from);
        return prefix == null ? "" : prefix;
    }

    // ================= L2：通用候选 =================

    /**
     * fuzi 候选链（算法重写）：页内明文直链 / iframe 参数 / iframe 内页 / packer 解包，
     * 媒体直链优先探活；磁力链剔除（不是播放地址）；非直链候选一律不要——没有 WebView
     * 兜底，把 HTML 播放器页交给播放器只会黑屏。
     */
    private String layer2(String html, Map<String, String> headers, List<String> extraCandidates) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String c : extraCandidates) candidates.add(unwrapPlayerUrl(c));
        candidates.addAll(findPlayUrls(html));
        String frameRef = "";
        String framed = findPlayInFrame(html, headers);
        if (!TextUtils.isEmpty(framed)) {
            if (!validStream(framed)) frameRef = schemeHost(framed);
            candidates.add(unwrapPlayerUrl(framed));
        }
        candidates.addAll(packerCandidates(html));
        candidates.removeIf(c -> c != null && (c.contains("magnet:") || c.contains("magnet%3A") || c.contains("magnet%3a")));
        List<String> ordered = new ArrayList<>();
        for (String c : candidates) if (validStream(c)) ordered.add(c);
        int tried = 0;
        for (String cand : ordered) {
            if (tried++ >= MAX_CANDIDATES) break;
            if (probeWithReferer(cand, headers, pageUrl)) return cand;
            if (!TextUtils.isEmpty(frameRef) && probeWithReferer(cand, headers, frameRef)) return cand;
        }
        return null;
    }

    // ================= L4：ArtPlayer 解析播放器 =================

    /**
     * L4 ArtPlayer 解析播放器模式（按形认，不认域名）：
     * 播放页 iframe → 跟进 iframe 页（常 302 到 player/index.php 之类）→ 页内
     * const Url / const Sign / const From 三行字面量 → GET {iframe 目录}api.php
     * ?url=&sign=&t= → JSON code==200 取 ret.url（无 ret 时取顶层 url）→ 探活。
     * 三行缺任一、页内无 api.php、code 非 200、缺 url、探活不过，一律返回 null。
     * 只在 L0/L1/L2 全败后调用；不跑 JS、不建 WebView。
     */
    private String layer4(String html, Map<String, String> headers) {
        if (TextUtils.isEmpty(html)) return null;
        Matcher fm = IFRAME_SRC.matcher(html);
        while (fm.find()) {
            String frame = absUrl(fm.group(1), pageUrl);
            if (!frame.startsWith("http")) continue;
            String stream = artResolveFrame(frame, headers);
            if (!TextUtils.isEmpty(stream)) return stream;
        }
        return null;
    }

    private String artResolveFrame(String frameUrl, Map<String, String> headers) {
        HttpResult inner = get(frameUrl, headers);
        if (inner.code < 200 || inner.code >= 400 || TextUtils.isEmpty(inner.body)) {
            log("L4 frame miss http=" + inner.code + " frame=" + trunc(frameUrl));
            return null;
        }
        String framePage = TextUtils.isEmpty(inner.url) ? frameUrl : inner.url;
        String url = firstGroup(ART_URL, inner.body);
        String sign = firstGroup(ART_SIGN, inner.body);
        String from = firstGroup(ART_FROM, inner.body);
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(sign) || TextUtils.isEmpty(from)
                || !inner.body.contains("api.php")) {
            return null;
        }
        int slash = framePage.lastIndexOf('/');
        if (slash < 0) return null;
        String dir = framePage.substring(0, slash + 1);
        String apiUrl;
        try {
            apiUrl = dir + "api.php?url=" + URLEncoder.encode(url, "UTF-8")
                    + "&sign=" + URLEncoder.encode(sign, "UTF-8")
                    + "&t=" + URLEncoder.encode(from, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        Map<String, String> apiHeaders = new LinkedHashMap<>(headers);
        apiHeaders.put("Referer", framePage);
        HttpResult res = get(apiUrl, apiHeaders);
        if (res.code < 200 || res.code >= 400 || TextUtils.isEmpty(res.body)) {
            log("L4 api miss http=" + res.code);
            return null;
        }
        String stream = artStreamFromJson(res.body);
        if (TextUtils.isEmpty(stream)) {
            return null;
        }
        stream = stream.replace("\\/", "/");
        if (!validStream(stream)) {
            return null;
        }
        if (!probe(stream, apiHeaders)) {
            log("L4 api hit probe-fail");
            return null;
        }
        return stream;
    }

    /** ArtPlayer api.php 响应：{"code":200,"type":"m3u8","ret":{"url":"..."}}，无 ret 时取顶层 url。 */
    private static String artStreamFromJson(String body) {
        try {
            JSONObject root = new JSONObject(body);
            if (root.optInt("code", -1) != 200) return "";
            JSONObject ret = root.optJSONObject("ret");
            String u = ret == null ? "" : ret.optString("url", "");
            if (TextUtils.isEmpty(u)) u = root.optString("url", "");
            return u == null ? "" : u;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean probeWithReferer(String url, Map<String, String> headers, String referer) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Referer", referer);
        return probe(url, h);
    }

    /** 页内明文直链（先 \/ 还原再抽）；m3u8 与 mp4/flv/mkv 各取，合计封顶。 */
    private static List<String> findPlayUrls(String html) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return out;
        String text = html.replace("\\/", "/");
        for (Pattern p : new Pattern[]{P_M3U8, P_MP4}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String u = m.group(1);
                if (!out.contains(u)) out.add(u);
                if (out.size() >= MAX_CANDIDATES) return out;
            }
        }
        return out;
    }

    /**
     * iframe 里挖：① query 参数直接带地址（?video_url=https%3A%2F%2F...index.m3u8）；
     * ② 跟进 iframe 页再找；③ 再跟一层嵌套 iframe。
     */
    private String findPlayInFrame(String html, Map<String, String> headers) {
        if (TextUtils.isEmpty(html)) return "";
        Matcher fm = IFRAME_SRC.matcher(html);
        if (!fm.find()) return "";
        String frame = absUrl(fm.group(1), pageUrl);
        Matcher q = QUERY_MEDIA_PARAM.matcher(frame);
        while (q.find()) {
            String val = decode(q.group(1));
            if (val.startsWith("//")) val = "https:" + val;
            if (val.contains(".m3u8") || val.contains(".mp4") || val.contains(".flv")) return val;
        }
        HttpResult inner = get(frame, headers);
        if (TextUtils.isEmpty(inner.body)) return "";
        List<String> urls = findPlayUrls(inner.body);
        if (!urls.isEmpty()) return urls.get(0);
        Matcher fm2 = IFRAME_SRC.matcher(inner.body);
        if (!fm2.find()) return "";
        HttpResult inner2 = get(absUrl(fm2.group(1), frame), headers);
        List<String> urls2 = findPlayUrls(inner2.body);
        return urls2.isEmpty() ? "" : urls2.get(0);
    }

    /**
     * eval(function(p,a,c,k,e,d){...}) packer 解包找直链（算法重写，见 unpackPacker），
     * 并应用页面自身的域名改写模板（source.replace(/https:\/\/.../,'https://host/jmpres/$1/')）：
     * 原样与改写版都进候选，谁通谁上。
     */
    private static LinkedHashSet<String> packerCandidates(String html) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            if (html == null || !html.contains("eval(function(p,a,c,k,e,d)")) return out;
            int p = html.indexOf("eval(function(p,a,c,k,e,d)");
            String unpacked = unpackPacker(html.substring(p, Math.min(html.length(), p + 6000)));
            if (TextUtils.isEmpty(unpacked)) return out;
            List<String> urls = findPlayUrls(unpacked);
            if (urls.isEmpty()) return out;
            for (int i = 0; i < urls.size(); i++) urls.set(i, urls.get(i).replaceAll("\\\\+$", ""));
            String tpl = "";
            Matcher rw = REWRITE_TPL.matcher(html);
            if (rw.find()) tpl = rw.group(1);
            for (String u : urls) {
                if (TextUtils.isEmpty(u) || !u.startsWith("http")) continue;
                out.add(u);
                if (TextUtils.isEmpty(tpl)) continue;
                String sh = schemeHost(u);
                if (TextUtils.isEmpty(sh)) continue;
                String host = sh.replaceFirst("^https?://", "").replaceFirst("/$", "");
                String path = u.substring(sh.length());
                String rewritten;
                if (tpl.contains("$1")) rewritten = tpl.replace("$1", host);
                else rewritten = tpl.endsWith("/") ? tpl + host : tpl + "/" + host;
                if (!rewritten.endsWith("/")) rewritten += "/";
                out.add(rewritten + path);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Dean Edwards packer 解包：payload 按 base36 下标换 k 表里的词（fuzi unpackPacker 算法重写）。 */
    private static String unpackPacker(String js) {
        try {
            if (js == null) return "";
            Matcher m = PACKER_ARGS.matcher(js);
            if (!m.find()) return "";
            String payload = m.group(1);
            String[] keys = m.group(4).split("\\|", -1);
            Map<String, String> dict = new LinkedHashMap<>();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].isEmpty()) continue;
                String id = Integer.toString(i, 36);
                if (id.length() <= 2) dict.put(id, keys[i]);
            }
            StringBuilder out = new StringBuilder(payload.length() * 2);
            Matcher w = PACKER_WORD.matcher(payload);
            int last = 0;
            while (w.find()) {
                out.append(payload, last, w.start());
                String rep = dict.get(w.group());
                out.append(rep != null ? rep : w.group());
                last = w.end();
            }
            out.append(payload.substring(last));
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 把「外层播放器页」拆成真直链：query 参数里带的 video_url / play_url / url / v / src / m3u8。 */
    private static String unwrapPlayerUrl(String u) {
        if (TextUtils.isEmpty(u)) return "";
        String s = u.replace("\\/", "/").replace("&amp;", "&").trim();
        if (BrowserMeta.isDirectStream(s)) return s;
        Matcher q = QUERY_MEDIA_PARAM.matcher(s);
        while (q.find()) {
            String val = decode(q.group(1));
            if (val.startsWith("//")) val = "https:" + val;
            if (BrowserMeta.isDirectStream(val)) return val;
        }
        return s;
    }

    // ================= HTTP 与探活 =================

    /** GET：应用层拦截器 ProxyRedirectInterceptor 已先跟随 3xx，最终 URL 以 res.request().url() 为准；单次请求 9 秒超时；返回最终状态码与正文。 */
    private HttpResult get(String url, Map<String, String> headers) {
        String cur = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            Request.Builder rb = new Request.Builder().url(cur);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) rb.header(e.getKey(), e.getValue());
            }
            try (Response res = OkHttp.client(TIMEOUT_MS).newCall(rb.build()).execute()) {
                int code = res.code();
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && i < MAX_REDIRECTS) {
                    String loc = res.header("Location");
                    if (TextUtils.isEmpty(loc)) return new HttpResult(code, "", res.request().url().toString());
                    cur = absUrl(loc, cur);
                    continue;
                }
                ResponseBody body = res.body();
                return new HttpResult(code, body == null ? "" : body.string(), res.request().url().toString());
            } catch (Exception e) {
                // catch 里 res 已出作用域（try-with-resources 变量只在 try 块内可见）；异常时无响应，cur 即最后一次请求地址。
                return new HttpResult(-1, "", cur);
            }
        }
        return new HttpResult(-1, "", cur);
    }

    /** HEAD：手动跟随 3xx，返回最终状态码；异常/超时返回 -1。 */
    private int head(String url, Map<String, String> headers) {
        String cur = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            Request.Builder rb = new Request.Builder().url(cur);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) rb.header(e.getKey(), e.getValue());
            }
            try (Response res = OkHttp.client(TIMEOUT_MS).newCall(rb.head().build()).execute()) {
                int code = res.code();
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && i < MAX_REDIRECTS) {
                    String loc = res.header("Location");
                    if (TextUtils.isEmpty(loc)) return code;
                    cur = absUrl(loc, cur);
                    continue;
                }
                return code;
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 探活（fuzi probe 结论重写）：m3u8 走 GET + #EXTM3U 校验（清单只有几 KB）；
     * 其余媒体先 HEAD，HEAD 不支持再退 Range GET（bytes=0-0）；m3u8 绝不能带 Range。
     */
    private boolean probe(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return false;
        if (isM3u8(url)) {
            HttpResult res = get(url, headers);
            if (res.code < 200 || res.code >= 400) return false;
            return res.body != null && res.body.contains("#EXTM3U");
        }
        int code = head(url, headers);
        if (code >= 200 && code < 400) return true;
        Request.Builder rb = new Request.Builder().url(url).header("Range", "bytes=0-0");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) rb.header(e.getKey(), e.getValue());
        }
        try (Response res = OkHttp.client(TIMEOUT_MS).newCall(rb.build()).execute()) {
            int c = res.code();
            return c == 200 || c == 206;
        } catch (Exception e) {
            return false;
        }
    }

    /** 只认直达流地址（路径末尾真流后缀）且过视频格式判定；两个判定复用现有工具，不自写正则。 */
    private static boolean validStream(String url) {
        return BrowserMeta.isDirectStream(url) && Sniffer.isVideoFormat(url);
    }

    private static boolean isM3u8(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String path = url;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        return path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    // ================= 请求头 / 地址 / 收尾 =================

    private static volatile String sWebUa;

    /**
     * push v25 (A)：App 自发请求（播前探活 / 本解析器）用的 UA。用户设了 UA 照旧用用户的；
     * 没设时用 WebView 自己发的 UA（WebSettings.getDefaultUserAgent），取不到再用系统 http.agent。
     * 原先留空会被 OkHttp 补成默认的 okhttp/x.y.z，missav2 对 okhttp/ 开头的 UA 一律回 500
     * （同地址同 Referer，浏览器 UA / Media3 UA / Dalvik UA 均 200）。不是全局 UA 设置项，不改蜘蛛请求。
     */
    public static String nativeUa() {
        String ua = Setting.getUa();
        if (!TextUtils.isEmpty(ua)) return ua;
        String cached = sWebUa;
        if (cached != null) return cached;
        String w = "";
        try {
            w = WebSettings.getDefaultUserAgent(App.get());
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(w)) {
            String d = System.getProperty("http.agent");
            w = d == null ? "" : d;
        }
        if (!TextUtils.isEmpty(w)) sWebUa = w;
        return w;
    }

    /** 与旧解析器相同的取头法：Referer=播放页、UA=Setting、Cookie=WebView 同源。 */
    private Map<String, String> pageHeaders(String url) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", url);
        headers.put("User-Agent", nativeUa());
        String cookie = getCookie(url);
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        return headers;
    }

    private Map<String, String> streamHeaders(String streamUrl, String pageUrl) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", pageUrl);
        headers.put("User-Agent", nativeUa());
        String cookie = getCookie(streamUrl);
        if (TextUtils.isEmpty(cookie)) cookie = getCookie(pageUrl);
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        return headers;
    }

    private static String getCookie(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String absUrl(String url, String base) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.trim();
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http")) return u;
        if (u.startsWith("/")) return schemeHost(base) + u;
        String dir = base;
        int cut = dir.indexOf('?');
        if (cut >= 0) dir = dir.substring(0, cut);
        cut = dir.indexOf('#');
        if (cut >= 0) dir = dir.substring(0, cut);
        int slash = dir.lastIndexOf('/');
        if (slash >= 0) dir = dir.substring(0, slash + 1);
        return dir + u;
    }

    private static String schemeHost(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Matcher m = SCHEME_HOST.matcher(url);
        return m.find() ? m.group(1) + "/" : "";
    }

    private static String hostOf(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri == null ? null : uri.getHost();
            return host == null ? "" : host;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String trunc(String url) {
        if (url == null) return "";
        return url.length() <= LOG_URL_LEN ? url : url.substring(0, LOG_URL_LEN) + "...";
    }

    private static void log(String message) {
        DebugLogStore.add("HttpResolver", message);
    }

    /** Same as {@link #log} but also listed under the 报错 filter (HTTP error / timeout / exception / final FAIL). */
    private static void logError(String message) {
        DebugLogStore.addError(LogCategory.PLAY, "HttpResolver", message);
    }

    private void finish(String url, Map<String, String> headers, String layer) {
        CACHE.put(pageUrl, new Resolved(url, headers));
        log("OK layer=" + layer + " stream-domain=" + hostOf(url) + " stream=" + trunc(url));
        main.post(() -> callback.onResolved(url, headers));
    }

    private void fail() {
        main.post(callback::onFailed);
    }
}
