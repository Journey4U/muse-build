package com.muse.android.tv.api.loader;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;
import com.github.catvod.net.OkHttp;
import com.muse.android.tv.server.Server;
import com.muse.android.tv.server.process.PushMaster;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * push v43/v25（Liu 2026-11-24 批准）：浏览器推送「升级为多画质」。
 * 探针认正片的结果一字不动；拿到推送地址后，在交给播放器前多走一步（后台线程、短超时、带推送原本的请求头）：
 *   1. 推送地址本身已是多画质主清单（≥2 条 #EXT-X-STREAM-INF）→ 原样播；
 *   2. 是单档子清单 → 先试页面声明的主清单（如 MissAV 脚本 source= 的 playlist.m3u8），再试同目录规律
 *      （子清单所在目录名是分辨率，如 720p、842x480，就试上一级 playlist.m3u8 / master.m3u8）；
 *      取回核实：≥2 档，且其中一档正是推送地址，才用；
 *   3. 站方把各分辨率给成独立地址（P站 mediaDefinitions 多条 hls）→ 逐档取回，合成本机多画质主清单
 *      （BANDWIDTH/RESOLUTION/CODECS 取自子清单，取不到按文件名/高度补），站方默认档排第一；
 *   4. 都不成 → 原地址照旧推，不报错。
 * 不按时长/段数判断；智能去广告仍由播放器对每个子清单各自生效（主清单无 #EXT-X-ENDLIST，HlsAdsParser 原样放行）。
 */
public final class PushMultiRes {

    public interface Callback {
        void onResult(String url);
    }

    private static final String TAG = "PushMultiRes";
    private static final long TIMEOUT_MS = 5000;
    private static final long SYNTH_WAIT_MS = 7000;
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_DEFS = 6;
    private static final Pattern RES_DIR = Pattern.compile("(?i)^(\\d{3,4}p|\\d{3,4}x\\d{3,4}|\\d{3,4}p_\\d+k)$");
    private static final Pattern ATTR_BW = Pattern.compile("(?i)(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern ATTR_RES = Pattern.compile("(?i)(?:^|,)RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern FILE_KBPS = Pattern.compile("(?i)[_/-](\\d{3,5})K[_./-]");
    private static final Pattern FILE_HEIGHT = Pattern.compile("(?i)[_/-](\\d{3,4})P[_./-]");

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Offer> OFFERS = lru(16);
    private static final Map<String, String> DONE = lru(32);

    private PushMultiRes() {
    }

    private static <V> Map<String, V> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        });
    }

    private static final class Def {
        final String url;
        final int height;
        final boolean def;

        Def(String url, int height, boolean def) {
            this.url = url;
            this.height = height;
            this.def = def;
        }
    }

    private static final class Offer {
        final List<Def> defs;
        final String declMaster;

        Offer(List<Def> defs, String declMaster) {
            this.defs = defs;
            this.declMaster = declMaster;
        }
    }

    private static final class Fetched {
        final int code;
        final String text;
        final String finalUrl;

        Fetched(int code, String text, String finalUrl) {
            this.code = code;
            this.text = text;
            this.finalUrl = finalUrl;
        }

        boolean ok() {
            return code >= 200 && code < 300 && text != null && text.trim().startsWith("#EXTM3U");
        }
    }

    private static final class Variant {
        final String attrs;
        final String uri;
        final int height;
        final boolean def;

        Variant(String attrs, String uri, int height, boolean def) {
            this.attrs = attrs;
            this.uri = uri;
            this.height = height;
            this.def = def;
        }
    }

    /** 浏览器推送前登记本页探针带回的候选（各档 hls 列表 JSON、页面声明的主清单），按推送地址记。主线程调用。 */
    public static void offer(String stream, String hlsDefsJson, String declMaster) {
        if (TextUtils.isEmpty(stream)) return;
        List<Def> defs = new ArrayList<>();
        try {
            if (!TextUtils.isEmpty(hlsDefsJson)) {
                JSONArray arr = new JSONArray(hlsDefsJson);
                for (int i = 0; i < arr.length() && defs.size() < MAX_DEFS; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String u = o.optString("u", "");
                    if (!u.startsWith("http")) continue;
                    defs.add(new Def(u, o.optInt("h", 0), o.optInt("d", 0) == 1));
                }
            }
        } catch (Throwable ignored) {
        }
        String dm = declMaster == null ? "" : declMaster;
        if (defs.isEmpty() && dm.isEmpty()) {
            OFFERS.remove(stream);
            return;
        }
        OFFERS.put(stream, new Offer(defs, dm));
        DONE.remove(stream);
    }

    /** push v45：该地址是否登记过多画质候选（网页源全局搜索播放据此决定要不要走升级，没登记的不联网、不多等）。 */
    public static boolean hasOffer(String url) {
        return !TextUtils.isEmpty(url) && OFFERS.containsKey(url);
    }

    /** 播放前升级；结果在主线程回调（失败也回调原地址）。只对 http(s) 的 m3u8 或登记过候选的地址联网。 */
    public static void upgrade(String url, Map<String, String> headers, Callback cb) {
        if (cb == null) return;
        if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://")) || PushMaster.isLocalMaster(url)) {
            cb.onResult(url);
            return;
        }
        String done = DONE.get(url);
        if (done != null) {
            cb.onResult(done);
            return;
        }
        Offer offer = OFFERS.get(url);
        if (offer == null && !isM3u8(url)) {
            cb.onResult(url);
            return;
        }
        Map<String, String> h = headers == null ? Collections.emptyMap() : new LinkedHashMap<>(headers);
        try {
            EXEC.execute(() -> {
                String out = url;
                try {
                    out = doUpgrade(url, h, offer);
                } catch (Throwable e) {
                    log("多画质升级异常，照原地址播放：" + e.getClass().getSimpleName());
                }
                if (TextUtils.isEmpty(out)) out = url;
                DONE.put(url, out);
                final String r = out;
                MAIN.post(() -> cb.onResult(r));
            });
        } catch (Throwable e) {
            cb.onResult(url);
        }
    }

    private static String doUpgrade(String url, Map<String, String> headers, Offer offer) {
        Fetched self = fetch(url, headers);
        if (self.ok()) {
            int n = countVariants(self.text);
            if (n >= 2) {
                log("推送地址已是多画质主清单（" + n + " 档），原样播放");
                return url;
            }
        }
        // 2. 找主清单：页面声明 → 同目录规律；核实 ≥2 档且含推送地址。
        List<String[]> cands = new ArrayList<>();
        if (offer != null && isM3u8(offer.declMaster) && !offer.declMaster.equals(url)) cands.add(new String[]{offer.declMaster, "页面声明"});
        for (String g : guessMasters(url)) {
            boolean dup = false;
            for (String[] c : cands) if (c[0].equals(g)) dup = true;
            if (!dup) cands.add(new String[]{g, "同目录"});
        }
        for (String[] c : cands) {
            Fetched m = fetch(c[0], headers);
            if (!m.ok()) continue;
            int n = countVariants(m.text);
            if (n < 2) continue;
            if (!associates(m.text, m.finalUrl, url)) {
                log("候选主清单（" + c[1] + "）有 " + n + " 档但不含推送地址，不用");
                continue;
            }
            log("找到主清单（" + c[1] + "），" + n + " 档，默认交播放器按清单选：" + shortUrl(c[0]));
            return c[0];
        }
        // 3. 站方各档独立地址 → 合成本机主清单。
        if (offer != null && offer.defs.size() >= 2) {
            boolean contains = false;
            for (Def d : offer.defs) if (d.url.equals(url)) contains = true;
            if (!contains) {
                log("站方多档列表不含推送地址，不合成，照原地址播放");
                return url;
            }
            String local = synthesize(offer.defs, headers);
            if (local != null) return local;
            return url;
        }
        log("未找到多画质来源，照原地址播放（单档）");
        return url;
    }

    private static String synthesize(List<Def> defs, Map<String, String> headers) {
        final Fetched[] got = new Fetched[defs.size()];
        CountDownLatch latch = new CountDownLatch(defs.size());
        for (int i = 0; i < defs.size(); i++) {
            final int idx = i;
            final String u = defs.get(i).url;
            try {
                EXEC.execute(() -> {
                    try {
                        got[idx] = fetch(u, headers);
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (Throwable e) {
                latch.countDown();
            }
        }
        try {
            latch.await(SYNTH_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        List<Variant> vs = new ArrayList<>();
        java.util.Set<Integer> heights = new java.util.HashSet<>();
        int defHeight = 0;
        for (int i = 0; i < defs.size(); i++) {
            Def d = defs.get(i);
            Fetched f = got[i];
            if (f == null || !f.ok()) continue;
            Variant v = toVariant(d, f);
            if (v == null) {
                if (f.text.contains("#EXT-X-MEDIA:")) {
                    log("站方子清单带独立音轨/字幕组，不合成，照原地址播放");
                    return null;
                }
                continue;
            }
            int key = v.height > 0 ? v.height : -(i + 1);
            if (!heights.add(key)) continue;
            vs.add(v);
            if (d.def) defHeight = v.height;
        }
        if (vs.size() < 2) {
            log("站方多档可用不足 2 档（取回 " + vs.size() + " 档），照原地址播放");
            return null;
        }
        final int dh = defHeight;
        Collections.sort(vs, (a, b) -> {
            boolean ad = a.def, bd = b.def;
            if (ad != bd) return ad ? -1 : 1;
            return Integer.compare(b.height, a.height);
        });
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        StringBuilder names = new StringBuilder();
        for (Variant v : vs) {
            sb.append("#EXT-X-STREAM-INF:").append(v.attrs).append('\n').append(v.uri).append('\n');
            if (names.length() > 0) names.append('/');
            names.append(v.height > 0 ? v.height + "P" : "?");
        }
        String path = PushMaster.put(sb.toString());
        String local = Server.get().getAddress(path);
        log("已合成多画质主清单 " + vs.size() + " 档（" + names + "），站方默认档 " + (dh > 0 ? dh + "P" : "未标") + " 排第一");
        return local;
    }

    /** 子清单 → 主清单里的一档。子清单若本身是单档主清单（P站），取其 STREAM-INF 属性与真实子清单地址；带 EXT-X-MEDIA 的不收。 */
    private static Variant toVariant(Def d, Fetched f) {
        String text = f.text;
        String base = TextUtils.isEmpty(f.finalUrl) ? d.url : f.finalUrl;
        if (text.contains("#EXT-X-STREAM-INF")) {
            if (text.contains("#EXT-X-MEDIA:")) return null;
            String bestAttrs = null;
            String bestUri = null;
            long bestBw = -1;
            String[] lines = text.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (!l.startsWith("#EXT-X-STREAM-INF:")) continue;
                String attrs = l.substring("#EXT-X-STREAM-INF:".length());
                String uri = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String n = lines[j].trim();
                    if (n.isEmpty()) continue;
                    if (n.startsWith("#")) continue;
                    uri = n;
                    break;
                }
                if (uri == null) continue;
                long bw = longOf(ATTR_BW, attrs);
                if (bw > bestBw) {
                    bestBw = bw;
                    bestAttrs = attrs;
                    bestUri = resolve(base, uri);
                }
            }
            if (bestAttrs == null) return null;
            int h = intOf(ATTR_RES, bestAttrs, 2);
            if (h <= 0) h = d.height;
            String attrs = bestAttrs;
            if (longOf(ATTR_BW, attrs) <= 0) attrs = "BANDWIDTH=" + estimateBw(d.url, h) + "," + attrs;
            if (intOf(ATTR_RES, attrs, 2) <= 0 && h > 0) attrs = attrs + ",RESOLUTION=" + widthOf(h) + "x" + h;
            return new Variant(attrs, bestUri, h, d.def);
        }
        if (text.contains("#EXTINF")) {
            int h = d.height > 0 ? d.height : (int) longOf(FILE_HEIGHT, d.url);
            String attrs = "BANDWIDTH=" + estimateBw(d.url, h) + (h > 0 ? ",RESOLUTION=" + widthOf(h) + "x" + h : "");
            return new Variant(attrs, base, h, d.def);
        }
        return null;
    }

    private static long estimateBw(String url, int h) {
        long k = longOf(FILE_KBPS, url);
        if (k > 0) return k * 1000;
        if (h >= 2160) return 16000000;
        if (h >= 1440) return 9000000;
        if (h >= 1080) return 5000000;
        if (h >= 720) return 3000000;
        if (h >= 480) return 1500000;
        if (h >= 360) return 800000;
        if (h > 0) return 400000;
        return 1000000;
    }

    private static int widthOf(int h) {
        int w = Math.round(h * 16f / 9f);
        return w % 2 == 0 ? w : w + 1;
    }

    private static long longOf(Pattern p, String s) {
        if (s == null) return -1;
        Matcher m = p.matcher(s);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(1));
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int intOf(Pattern p, String s, int group) {
        if (s == null) return -1;
        Matcher m = p.matcher(s);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(group));
        } catch (Throwable e) {
            return -1;
        }
    }

    static int countVariants(String text) {
        if (text == null) return 0;
        int n = 0;
        for (String l : text.split("\\r?\\n")) if (l.trim().startsWith("#EXT-X-STREAM-INF")) n++;
        return n;
    }

    /** 主清单的某一档（解析成绝对地址后）与推送地址同路径（一方路径以另一方结尾，容站方 /jmpres/ 之类代理前缀）。 */
    static boolean associates(String master, String masterUrl, String stream) {
        String sp = pathOf(stream);
        if (TextUtils.isEmpty(sp)) return false;
        String[] lines = master.split("\\r?\\n");
        boolean expect = false;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                expect = true;
                continue;
            }
            if (l.startsWith("#")) continue;
            if (!expect) continue;
            expect = false;
            String vp = pathOf(resolve(masterUrl, l));
            if (TextUtils.isEmpty(vp)) continue;
            if (vp.equals(sp) || vp.endsWith(sp) || sp.endsWith(vp)) return true;
        }
        return false;
    }

    /** 同目录规律：子清单所在目录名是分辨率（720p / 842x480 / 720P_4000K），试上一级 playlist.m3u8、master.m3u8。 */
    static List<String> guessMasters(String url) {
        List<String> out = new ArrayList<>();
        try {
            Uri u = Uri.parse(url);
            List<String> segs = u.getPathSegments();
            if (segs == null || segs.size() < 2) return out;
            String dir = segs.get(segs.size() - 2);
            if (!RES_DIR.matcher(dir).matches()) return out;
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < segs.size() - 2; i++) p.append('/').append(segs.get(i));
            String prefix = u.getScheme() + "://" + u.getEncodedAuthority() + p;
            String q = u.getEncodedQuery();
            for (String name : new String[]{"playlist.m3u8", "master.m3u8"}) {
                out.add(prefix + "/" + name);
                if (!TextUtils.isEmpty(q)) out.add(prefix + "/" + name + "?" + q);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static boolean isM3u8(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String l = url.toLowerCase(Locale.ROOT);
        int q = l.indexOf('?');
        String p = q > 0 ? l.substring(0, q) : l;
        return p.endsWith(".m3u8") || l.contains(".m3u8?") || l.contains(".m3u8&");
    }

    private static String pathOf(String url) {
        try {
            return Uri.parse(url).getPath();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String resolve(String base, String rel) {
        try {
            return java.net.URI.create(base).resolve(rel.replace(" ", "%20")).toString();
        } catch (Throwable e) {
            return rel;
        }
    }

    private static Fetched fetch(String url, Map<String, String> headers) {
        try {
            Request.Builder rb = new Request.Builder().url(url);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) rb.header(e.getKey(), e.getValue());
            }
            try (Response res = OkHttp.client(TIMEOUT_MS).newCall(rb.build()).execute()) {
                ResponseBody body = res.body();
                String text = "";
                if (body != null) {
                    InputStream in = body.byteStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > MAX_BYTES) break;
                    }
                    text = bos.toString(StandardCharsets.UTF_8.name());
                }
                return new Fetched(res.code(), text, res.request().url().toString());
            }
        } catch (Throwable e) {
            return new Fetched(-1, "", url);
        }
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        return url.length() <= 120 ? url : url.substring(0, 120) + "...";
    }

    private static void log(String message) {
        DebugLogStore.add(LogCategory.PLAY, TAG, "push 多画质 " + message, false);
    }
}
