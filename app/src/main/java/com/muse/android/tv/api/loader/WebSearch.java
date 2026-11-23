package com.muse.android.tv.api.loader;

import android.text.TextUtils;

import com.muse.android.tv.bean.Site;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * push v31（Liu 2026-11-23 定方案 A）：网页源（只有 key/name/主页url 的条目）接进全局搜索。
 * <p>搜：用内置 {@link WebSpider}（夫子 WebSpider r542 移植）纯 HTTP 发现站点搜索端点、抽结果。
 * push v32（方案 R）：点开结果与其他源一样进 VideoActivity，详情/播放由 {@link #detailSpider(Site)} 的 WebSpider 承担；
 * 首页/主页浏览器入口不走这里，「网页源主页 = 完整浏览器」不变。</p>
 * <ul>
 *   <li>单站 15 秒超时，超时/异常一律返回空，不影响其他源；</li>
 *   <li>搜索路径不拉起隐形 WebView 渲染；WebSpider 现成纯 HTTP 能自动过的验证（Cap 求解/缓存 cookie、JS 写 cookie、
 *       宝塔 WAF、403 退避）照用，但都在 15 秒内；过不了的（Cloudflare、JS 验证壳、429 等）整站跳过（Liu 10:37/10:41）；</li>
 *   <li>主页地址 = 条目「主页url」原样完整地址（与 VideoActivity.resolveHomePageUrl 条目分支同口径；网页源无 api/ext，
 *       走不到其它分支；含 [工具: 的模板不支持，跳过）。</li>
 * </ul>
 */
public final class WebSearch {

    public static final long TIMEOUT_MS = 15000;

    private static final Map<String, WebSpider> CACHE = new ConcurrentHashMap<>();
    /** push v32：详情/播放实例（按站 key）及其建实例时的主页地址。 */
    private static final Map<String, WebSpider> DETAIL = new ConcurrentHashMap<>();
    private static final Map<String, String> DETAIL_HOME = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "web-search");
        t.setDaemon(true);
        return t;
    });

    private WebSearch() {
    }

    /** 网页源的主页地址；推不出返回空串。 */
    public static String homeOf(Site site) {
        if (site == null) return "";
        String home = site.getHomePageUrl();
        if (TextUtils.isEmpty(home) || home.contains("[工具:")) return "";
        home = home.trim();
        if (!home.startsWith("http://") && !home.startsWith("https://")) return "";
        return home;
    }

    /** 已为该站建过的实例（图片解密代理按 siteKey 分发时用）：详情/播放实例优先，其次搜索实例；没有返回 null。 */
    public static WebSpider cached(String key) {
        if (key == null) return null;
        WebSpider d = DETAIL.get(key);
        return d != null ? d : CACHE.get(key);
    }

    /**
     * push v32（Liu 2026-11-23 方案 R）：网页源的详情/播放蜘蛛 —— 照参考实现（夫子壳：网页源=普通蜘蛛，
     * 点搜索结果进 VideoActivity，详情走 WebSpider.detailContent、播放走 playerContent）。
     * 按站 key 缓存一个实例（同站详情→播放共用模板识别、cookie、根地址等状态，与参考壳 spider() 复用同义）；
     * 主页地址同搜索（条目「主页url」原样）；主页地址变了就重建。详情/播放路径不设 noRender，即照参考开渲染兜底
     * （WebFetch 隐形 WebView，只对加密壳站触发）。搜索路径仍是上面 search() 的新实例 + noRender + 15 秒，不受影响。
     * 推不出主页地址返回 null。
     */
    public static WebSpider detailSpider(Site site) {
        String home = homeOf(site);
        if (home.isEmpty()) return null;
        String key = site.getKey();
        WebSpider sp = DETAIL.get(key);
        if (sp != null && home.equals(DETAIL_HOME.get(key))) return sp;
        sp = new WebSpider(key, "web", home);
        DETAIL.put(key, sp);
        DETAIL_HOME.put(key, home);
        return sp;
    }

    /** 每次搜索新建实例（端点/首页按 host 有静态缓存，新建很便宜；避免超时后台未结束的上一次搜索与本次共用状态）。 */
    private static WebSpider spider(Site site, String home) {
        WebSpider sp = new WebSpider(site.getKey(), "web", home);
        sp.noRender = true;
        CACHE.put(site.getKey(), sp);
        return sp;
    }

    /** 搜一个网页源，返回标准结果 JSON（{"list":[...]}）；失败/超时/需验证返回空串。 */
    public static String search(Site site, String keyword, boolean quick, String page) {
        String home = homeOf(site);
        if (home.isEmpty()) return "";
        final WebSpider sp = spider(site, home);
        final boolean first = page == null || page.isEmpty() || "1".equals(page);
        long start = System.currentTimeMillis();
        Future<String> f = POOL.submit(() -> first ? sp.searchContent(keyword, quick) : sp.searchContent(keyword, quick, page));
        String json;
        try {
            json = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            sp.aborted = true;
            f.cancel(true);
            SpiderDebug.error("web-search", "%s 超时 %sms，跳过", site.getName(), TIMEOUT_MS);
            return "";
        } catch (Throwable e) {
            sp.aborted = true;
            f.cancel(true);
            SpiderDebug.error("web-search", "%s 异常 %s，跳过", site.getName(), e.toString());
            return "";
        }
        if (sp.isChallenged()) {
            SpiderDebug.log("web-search", "%s 需验证，跳过", site.getName());
            return "";
        }
        String out = normalize(json, sp.rootUrl(), home);
        SpiderDebug.log("web-search", "%s 完成 %sms%s", site.getName(), System.currentTimeMillis() - start, sp.isAutoPassed() ? "（验证已自动过）" : "");
        return out;
    }

    /**
     * vod_id 补成浏览器能直接打开的绝对地址：相对地址按蜘蛛当前根补全；「浏览器打开」卡
     * （vod_id={"type":"browser","url":…}）取其 url；补不出绝对地址的条目丢弃。
     */
    static String normalize(String json, String root, String home) {
        if (TextUtils.isEmpty(json)) return "";
        try {
            JSONObject o = new JSONObject(json);
            JSONArray list = o.optJSONArray("list");
            if (list == null) return "";
            String base = TextUtils.isEmpty(root) ? originOf(home) : root;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.optJSONObject(i);
                if (v == null) continue;
                String id = absolute(v.optString("vod_id", ""), base);
                if (id.isEmpty()) continue;
                v.put("vod_id", id);
                String pic = v.optString("vod_pic", "");
                if (!pic.isEmpty() && !pic.startsWith("http")) {
                    String p = absolute(pic, base);
                    if (!p.isEmpty()) v.put("vod_pic", p);
                }
                kept.put(v);
            }
            JSONObject r = new JSONObject();
            r.put("list", kept);
            return r.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    public static String absolute(String id, String base) {
        if (TextUtils.isEmpty(id)) return "";
        String u = id.trim();
        if (u.startsWith("{")) {
            try {
                u = new JSONObject(u).optString("url", "").trim();
            } catch (Throwable e) {
                return "";
            }
        }
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;
        if (TextUtils.isEmpty(base)) return "";
        if (u.startsWith("/")) return base + u;
        if (u.contains("://")) return "";
        return base + "/" + u;
    }

    static String originOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int s = url.indexOf("://");
        if (s < 0) return "";
        int e = url.indexOf('/', s + 3);
        return e > 0 ? url.substring(0, e) : url;
    }
}
