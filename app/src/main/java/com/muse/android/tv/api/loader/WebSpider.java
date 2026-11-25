package com.muse.android.tv.api.loader;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 内置「网页源」引擎（r243 新增）。
 *
 * <p>目的：让「一个网址就是一个源」成立，不再需要写 py / js 脚本或准备 spider jar。
 *
 * <p>站点配置写法（三种等价）：
 * <pre>
 * {"key":"web_dfxinli","name":"影视大全","type":3,"api":"web","ext":"http://www.dfxinli.com/"}
 * {"key":"web_dfxinli","name":"影视大全","type":3,"api":"web://http://www.dfxinli.com/"}
 * {"key":"web_dfxinli","name":"影视大全","type":3,"api":"http://www.dfxinli.com/"}   // type=3 且 api 是网址时也走这里
 * </pre>
 *
 * <p>工作方式：首次请求时抓首页做「模板识别」，据此选择解析规则：
 * <ul>
 *   <li>{@code zanpian} —— zanpianCMS 系模板（导航 /分类/、条目 /forum/ID.html、播放 /bofang/ID-线路-集.html）</li>
 *   <li>{@code maccms} —— 苹果 CMS v10（/vodtype/、/voddetail/、/vodplay/）</li>
 *   <li>{@code generic} —— 未知模板兜底：导航 + 标题锚点 + 播放页直链正则</li>
 * </ul>
 * 播放地址优先从播放页明文正则里取 m3u8 / mp4；取不到才回退 iframe，再回退让壳自己嗅探（parse=1）。
 */
public class WebSpider extends Spider {

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";
    private static final int MAX_REDIRECT = 4;
    private static final int MAX_PAGE = 200;

    /** 导航里不是「片单分类」的路径，识别分类时排除。 */
    private static final Set<String> SKIP_CLASS = new LinkedHashSet<>(java.util.Arrays.asList(
            "top", "sitemap", "gb", "search", "user", "list", "label", "map", "rss", "baidu", "so", "index",
            "login", "reg", "member", "api", "static", "public", "js", "css", "images", "img", "upload",
            // r414：aisee9w11 实证 —— /network/ 是"全部分类"功能页，不是内容分类
            "network",
            "new", "hot", "play", "vod", "forum", "detail", "show", "about", "help", "dm", "gbook",
            // r250：页脚/法务/帮助类静态页不是分类。A 站（both.*）首页被 r249 的模板兜底链
            // 误判成 zanpian 后，这几条正是被当成「分类」抽出来的东西（真机日志实证 classes=7）。
            "home", "privacy", "protocol", "question", "hotsearch", "contact", "notice", "faq",
            "disclaimer", "copyright", "agreement", "terms", "service", "policy", "feedback", "app",
            "dmca", "report", "contactus", "advertise", "sitemap2", "rss2",
            // r374：浏览记录/历史页不是内容分类（真机 tuiimg 的 history.php 被抽成「浏览记录」分类，
            // 点进去是访客足迹；4156 行的剥扩展名逻辑会把它归一成 history）。
            "history", "record", "records", "archive", "tagcloud",
            // r353：maccms 播放页固定路径（vodplay/xxx-1-1.html）——分类是 vodshow，
            // vodplay 作为导航路径出现时永远是详情页（双保险，复合 id 判定之外再拦一层）。
            "vodplay"));

    private static final Pattern P_TITLE = Pattern.compile("<title[^>]*>(.{0,200}?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern P_H1 = Pattern.compile("<h1[^>]*>(?:<a[^>]*>)?([^<]{1,80})", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_NAV = Pattern.compile("<a[^>]+href=\"/([a-zA-Z][a-zA-Z0-9_]{1,20})/\"[^>]*>(.{0,24}?)</a>", Pattern.DOTALL);
    private static final Pattern P_M3U8 = Pattern.compile("(https?:\\\\?/\\\\?/[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_MP4 = Pattern.compile("(https?:\\\\?/\\\\?/[^\\s\"'<>]+?\\.(?:mp4|flv)[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_IFRAME = Pattern.compile("<iframe[^>]+src=[\"']([^\"']{6,400})[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_OG_IMAGE = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /**
     * r252：懒加载图必须先取 data-*，再退回 src。
     *
     * <p>A 站卡片是 {@code <img src="/static/web/images/poster_loading.svg" data-src="真图">}，
     * 旧写法把 src 与 data-src 放进同一个选择组，从左往右先命中占位 svg → 列表封面全空。
     */
    // r364：补 data-background / data-bg / data-poster —— btsn 首页 swiper 卡片就是
    // <a class="... swiper-lazy" data-background="封面.jpg">（分类页则是 data-original）：
    // 白名单没有它 → 首页 30 条里前 8 条 vod_pic 全空（真机日志实证）。
    private static final String[] IMG_ATTRS = {"data-src", "data-original", "data-background", "data-bg", "data-poster", "data-echo", "data-lazy-src", "data-url", "data-fallback-src"};

    /** r252：占位图/站内图标一律不要（poster_loading.svg、lazy.gif、1x1 之类）。 */
    private static boolean isPlaceholderPic(String u) {
        if (TextUtils.isEmpty(u)) return true;
        String s = u.toLowerCase(Locale.ROOT);
        return s.contains("loading") || s.contains("placeholder") || s.contains("blank.") || s.contains("spacer")
                || s.contains("lazy.") || s.contains("1x1") || s.startsWith("data:image")
                || s.endsWith(".svg") || s.contains("/static/web/images/")
                // r320：站点自身图标不是封面 —— 真机飞卢听书详情返回 vod_pic=logo_tingshu.png，
                // 用户看到的就是「封面是个 app 图标」。同类站还会用 default_/nopic/noimage。
                || s.contains("logo") || s.contains("/icon") || s.contains("default.") || s.contains("nopic")
                || s.contains("noimage") || s.contains("no_image") || s.contains("favicon")
                // r2xx：吃瓜网 Mirages 主题广告 banner 占位图 banner.png（真广告图在 z-image-loader-url，
                // 不在此白名单，绝不当封面）。
                || s.contains("banner") || s.contains("horizontal-block") || s.contains("z-image-loader");
    }

    /** r252：从一个片段里挑封面 —— 逐个 img 标签、逐个 data-* 属性按优先级取，最后才看 src。 */
    private static String pickImg(String seg) {
        return normalizePic(pickImgRaw(seg));
    }

    /**
     * r252b：A 站封面形如 {@code xxx.jpeg.webp}（服务器响应头其实是 image/jpeg）。
     * 个别图片加载器会按后缀挑 webp 解码器而解码失败 → 卡片空白；去掉尾部多余后缀更稳。
     */
    private static String normalizePic(String u) {
        if (TextUtils.isEmpty(u)) return u;
        return u.replaceAll("\\.(jpe?g|png)\\.webp(\\?|$)", ".$1$2");
    }

    private static String pickImgRaw(String seg) {
        if (TextUtils.isEmpty(seg)) return "";
        Matcher tag = Pattern.compile("<img[^>]{0,2000}>", Pattern.CASE_INSENSITIVE).matcher(seg);
        String fallback = "";
        while (tag.find()) {
            String t = tag.group(0);
            for (String attr : IMG_ATTRS) {
                String v = unescapeHtml(attrValue(t, attr));
                if (TextUtils.isEmpty(v) || isPlaceholderPic(v)) continue;
                if ("data-fallback-src".equals(attr)) {
                    if (fallback.isEmpty()) fallback = v;
                    continue;
                }
                return v;
            }
            String src = unescapeHtml(attrValue(t, "src"));
            if (fallback.isEmpty() && !TextUtils.isEmpty(src) && !isPlaceholderPic(src)) fallback = src;
        }
        if (!fallback.isEmpty()) return fallback;
        // r4xx（通用化）：Mirages 系主题（吃瓜网等）封面挂在 <div class="blog-background"> 的
        //   [a-z]-bg-loader-url 属性上，首字母当版本号轮换（x-/z-…）。这类图是 AES 加密字节，
        //   必须走本地代理解密。用 __aes__ 前缀标记，实例出口（pickImgNearest/detailPic）识别后包装代理。
        Matcher bgm = Pattern.compile("[a-z]-bg-loader-url=[\"']([^\"']{6,500})[\"']", Pattern.CASE_INSENSITIVE).matcher(seg);
        while (bgm.find()) {
            String bg = unescapeHtml(bgm.group(1));
            if (isPlaceholderPic(bg)) continue;
            if (looksLikeImage(bg)) return "__aes__" + bg;
        }
        // r5xx：Mirages 同系的第二种封面写法 —— 卡片是空的 <div class="blog-background"></div>，
        //   紧跟内联 <script>loadBannerDirect('https://pic…jpeg', …)</script>（91吃瓜 实测）。
        //   51吃瓜 用 z-bg-loader-url，91吃瓜 用 loadBannerDirect，图都是同套 AES 加密字节。
        Matcher lbm = Pattern.compile("load(?:Banner|Cover|Poster|Thumb)[A-Za-z]*\\s*\\(\\s*[\"']([^\"']{6,500})[\"']", Pattern.CASE_INSENSITIVE).matcher(seg);
        while (lbm.find()) {
            String bg = unescapeHtml(lbm.group(1));
            if (isPlaceholderPic(bg)) continue;
            if (looksLikeImage(bg)) return "__aes__" + bg;
        }
        // 其余「自定义图片属性」兜底（data-cover/data-thumb/data-image 等，非 AES 加密）。
        Matcher lazy = Pattern.compile("[a-zA-Z0-9:_-]*(?:background|cover|thumb|poster|picture|image-url)[a-zA-Z0-9:_-]*=[\"']([^\"']{6,500})[\"']", Pattern.CASE_INSENSITIVE).matcher(seg);
        while (lazy.find()) {
            String bg = unescapeHtml(lazy.group(1));
            if (isPlaceholderPic(bg)) continue;
            if (looksLikeImage(bg)) return bg;
        }
        // style 内联背景图 —— 很多站封面只在 background-image:url(...) 里（昏黄影视 _pic 同款兜底）。
        Matcher css = Pattern.compile("background-image\\s*:\\s*url\\(['\"]?([^'\")\\s]{6,500})", Pattern.CASE_INSENSITIVE).matcher(seg);
        while (css.find()) {
            String bg = unescapeHtml(css.group(1));
            if (isPlaceholderPic(bg)) continue;
            if (looksLikeImage(bg)) return bg;
        }
        // r252b：有些站的封面压根不在 <img> 里 —— 69ck 是
        // <a class="stui-vodlist__thumb lazyload" data-original="真图"><span class="play"></span></a>，
        // 整页只有一个统计用 <img>。旧实现只认 img 标签，于是这类站封面全空（r252 回归）。
        for (String attr : IMG_ATTRS) {
            Matcher m = Pattern.compile(attr + "=[\"']([^\"']{6,400})[\"']", Pattern.CASE_INSENSITIVE).matcher(seg);
            while (m.find()) {
                String v = unescapeHtml(m.group(1));
                if (isPlaceholderPic(v)) continue;
                if ("data-fallback-src".equals(attr)) {
                    if (fallback.isEmpty()) fallback = v;
                    continue;
                }
                return v;
            }
        }
        return fallback;
    }

    /** 判一段值是否「像图片地址」：带 http/相对路径且结尾是常见图片后缀（或明显是图片 CDN 路径）。 */
    private static boolean looksLikeImage(String u) {
        if (TextUtils.isEmpty(u)) return false;
        String s = u.toLowerCase(Locale.ROOT);
        if (s.startsWith("data:") || s.startsWith("javascript:")) return false;
        return s.contains(".jpg") || s.contains(".jpeg") || s.contains(".png") || s.contains(".gif")
                || s.contains(".webp") || s.contains(".bmp") || s.contains(".avif")
                || s.contains("image") || s.contains("/img/") || s.contains("/pic/") || s.contains("/upload");
    }

    private static String attrValue(String tag, String attr) {
        Matcher m = Pattern.compile(attr + "=[\"']([^\"']{4,400})[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    /** r254：data-fallback-src（备用图地址），主图域名不可达时用它。 */
    private static String pickImgFallback(String seg) {
        if (TextUtils.isEmpty(seg)) return "";
        Matcher m = Pattern.compile("data-fallback-src=[\"']([^\"']{6,400})[\"']", Pattern.CASE_INSENSITIVE).matcher(seg);
        while (m.find()) {
            String v = unescapeHtml(m.group(1));
            if (!isPlaceholderPic(v)) return normalizePic(v);
        }
        return "";
    }

    /**
     * r254：取封面 + 域名可达性校验。
     *
     * <p>A 站封面在 new2.drleao.cn 这种独立图床上，部分网络直接连不通（沙箱可达、手机不可达），
     * 表现为「分类里有条目、封面全空白」。这里按 host 缓存探活（同一域名只探一次），
     * 不可达就换 data-fallback-src（站点自己的备用图域名）。
     */
    /** r271：取「距离锚点最近」的 img —— 卡片图在链接之前（gaze.red）或之后（4kcz）都能对上，避免片名与封面错位。 */
    /**
     * r286：小说 / 图片 / 音乐页在壳里没有对应界面（activity 目录只有 CrashActivity 与 PlaybackActivity），
     * 旧行为是把它们当视频源丢进 parse=1 去做 m3u8 嗅探，必然失败（真机 12:43 日志：
     * player: {"parse":1,"url":"https://…/novels/1891772917/"}）。
     * 这里改成把 vod_id 包成 {"type":"browser","url":…,"title":…}，壳的 ToolVod 会认出来，
     * 用内置浏览器 ToolWebActivity 打开，小说能读、图片能看。
     */
    /** r287：小说页判定（章节页通常带 /novel /book /chapter /read）。 */
    /**
     * r298：URL 不含图片关键词时按**页面内容**判定 —— 真机 15:54 日志：
     * meizi5.com/13149.html 这种纯数字路径会被 isPicPage 漏掉，于是被当视频源丢进 WebView 嗅探 m3u8，
     * 最后只能弹网页、调不起图片查看器。
     * 判据：页面里没有 video / m3u8 / hls.js 迹象，且能收集到 ≥3 张大图。
     */
    /**
     * r318：统一的「这是视频页」判据 —— 图片分流必须用它做双保险。
     * 真机教训（r317 both.folrl0a2w.cc）：该站播放器在 iframe/JS 里、详情页没有 <video>，
     * 却有剧照与推荐图，于是被当成图集送进了图片查看器。
     */
    private boolean hasVideoSignal(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String low = html.toLowerCase(Locale.ROOT);
        return low.contains("<video") || low.contains(".m3u8") || low.contains("hls.js")
                || low.contains("<iframe") || low.contains("player") || low.contains(".mp4")
                || low.contains("/vod") || low.contains("播放") || low.contains("选集")
                || low.contains("集数") || low.contains("线路");
    }

    /**
     * r387（AGENTS.md 第十七节）：**强视频信号** —— 只认真视频证据，**不含泛词**。
     *
     * <p><b>为什么必须拆开（专业团队实测 A 级）</b>：
     * 拿工程里 20 个真实页面跑 {@link #hasVideoSignal(String)}，**19/20 返回 true**，
     * 而其中 <code>&lt;video</code> / <code>.m3u8</code> / <code>hls.js</code> 命中数**全部为 0**。
     * 也就是说：唯一可信的视频信号一次都没出现，闸门却 95% 时间都是关的。
     * 图片分流写成 <code>if (!hasVideoSignal(html) && …)</code>，等于**这条路根本没通**。
     *
     * <p><b>47o.net 事故（r387 用户报）</b>：详情页 42146 字、**50 张大图（聚类后 42 张）**，
     * 只因 <code>player</code>×4（来自 meitu-player.css/js 资产名）+ <code>播放</code>×1
     * （“播放幻灯片”按钮）→ 被判成视频页 → 转 WebView 嗅探 m3u8 → 必然失败。
     *
     * <p>本方法只认：video 标签、m3u8、hls.js、播放器数据变量、主流播放器库名。
     */
    private boolean hasStrongVideoSignal(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String low = html.toLowerCase(Locale.ROOT);
        if (low.contains("<video") || low.contains(".m3u8") || low.contains("hls.js")
                || low.contains("player_aaaa") || low.contains("vod_play_url")
                || low.contains("mac_player") || low.contains("macplayer")
                || low.contains("rawurl=") || low.contains("rawurl :")
                || low.contains("dplayer") || low.contains("artplayer")
                || low.contains("videojs") || low.contains("jwplayer")) return true;
        // r539：**播放器 iframe 也是强视频信号** —— hohoj.tv 详情页把播放器放在
        // `<iframe src="/embed?id=N">`，页面无 <video>/m3u8/vod_play_url → 旧判据判「无视频信号」
        // → collectBigImages 把 24 张推荐封面聚成图集 → 条目被当 pic → 点开进图片查看器而非播放器。
        // 判据：iframe src 含 embed/player/video 关键词（排除统计/广告 trck/track/ads 的 iframe）。
        Matcher ifr = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (ifr.find()) {
            String s = ifr.group(1).toLowerCase(Locale.ROOT);
            if (s.contains("trck") || s.contains("track") || s.contains("/ad") || s.contains("smartpop")) continue;
            if (s.contains("embed") || s.contains("player") || s.contains("/video") || s.contains("bofang")) return true;
        }
        return false;
    }

    /**
     * r387：**播放路由链接数**（H6，结构证据）—— 用“链接目标结构”证明“这是视频详情页”，
     * 替代 player/播放/线路 这些泛词。视频站必有 ≥2 条播放路由链接；图集/漫画/小说页没有。
     *
     * <p>实测：nnyy 播放页 7 条、nnyy 首页 183 条（命中）；
     * 47o 详情 0 条、18seman 章节 0 条、meizi5 详情 0 条（不命中）。
     */
    private int countPlayRouteLinks(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile(
                "href=\"([^\"]*(?:/|^)(?:play|v_play|bofang|vodplay|watch)/[^\"]*"
                        + "|[^\"]*/index\\.php/vod/play/[^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find() && seen.size() < 200) seen.add(m.group(1));
        return seen.size();
    }

    /** r387：统计中文字数（用于“图多字少”判据）。 */
    private static int chineseLen(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) n++;
        }
        return n;
    }

    private boolean looksLikePicturePage(String html) {
        if (TextUtils.isEmpty(html)) return false;
        // r387（AGENTS.md 第十七节）：**泛词不再一票否决**。
        // 专业团队实测：旧 hasVideoSignal 在 20 个真实页面上 19/20 返回 true，
        // 而 <video/.m3u8/hls.js 命中数全为 0 —— 闸门几乎恒关，图片分流实际是死代码。
        // 47o.net 事故：50 张大图只因 player×4（CSS 资产名）+ 播放×1（幻灯片按钮）被判视频。
        if (hasStrongVideoSignal(html)) return false;
        // r387：H6 播放路由链接 >=2 → 视频详情页（结构证据，替代泛词）
        if (countPlayRouteLinks(html) >= 2) return false;
        // r373：**图多优先** —— 图集站页面里有大量相邻文章链接，
        // 会被 collectPageEpisodes 误判成“选集页”，进而走视频探活 + WebView 嗅探。
        // 注：collectBigImages 已做图集聚类（r301），列表页卡片分散在不同前缀下会被聚成小簇，
        // 所以“图多”在这里指的是**同一图集的连续图**，而不是列表页的封面堆。
        int imgCount = collectBigImages(html).size();
        if (imgCount >= 8 && !html.toLowerCase(Locale.ROOT).contains("<video")) return true;
        // r387：图多字少 → 高置信度图集（防泛词误伤）
        if (imgCount >= 8 && chineseLen(stripTags(html)) <= imgCount * 25) return true;
        if (collectPageEpisodes(html).size() >= 6) return false;
        return imgCount >= 3;
    }

    /**
     * r380（AGENTS.md 第十二节 L1-C2）：**内容信号**判定音频/听书页。
     *
     * <p>为什么需要它：旧 {@link #isAudioPage(String)} 只看 URL 词表，遇到 qmtsw 的
     * <code>/play/21170-0-0.html</code> 命中 0 个词 → 被判成视频 → 去嗅探 m3u8 → 必然失败。
     * 实测该页有 <code>var now="https://.../xxx.m4a"</code>，却一个音频关键词都没有。
     *
     * <p><b>判据（反例已实测）</b>：页面含音频直链 <b>且</b> 无 <code>&lt;video</code> <b>且</b> 无 <code>.m3u8</code>。
     * <ul>
     *   <li>正例 qmtsw 播放页：.m4a=2、&lt;video=0、.m3u8=0 → 判音频 ✅</li>
     *   <li>反例 6 个 maccms 视频站详情页：.m4a=0、.m3u8=1 → 判视频 ✅（零误伤）</li>
     * </ul>
     *
     * <p><b>不能用 hasVideoSignal 做否决</b>（实测）：qmtsw 播放页含 <code>&lt;iframe</code> 与 <code>player</code>，
     * hasVideoSignal 会返回 true → 音频会被误否决。必须只用“强视频信号”&lt;video / .m3u8。
     */
    private boolean looksLikeAudioPage(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String low = html.toLowerCase(Locale.ROOT);
        if (low.contains("<video") || low.contains(".m3u8")) return false;
        return Pattern
                .compile("https?://[^\"'\\s\\\\]+?\\.(?:m4a|mp3|aac|flac|wav|ogg)(?:[\"'?\\s]|$)", Pattern.CASE_INSENSITIVE)
                .matcher(low).find();
    }

    /** r291：音乐/音频页判定。 */
    private static boolean isAudioPage(String url) {
        String s = url == null ? "" : url.toLowerCase();
        return s.contains("/music") || s.contains("/audio") || s.contains("/song")
                || s.contains("/mp3") || s.contains("/sound") || s.contains("/radio") || s.contains("/fm/")
                // r319：补听书站常用路径 —— 飞卢听书每集是 /listen/2350.html，旧词表不含 /listen，
                // 整站音频页被当成视频去嗅探 m3u8，必然「无法播放」（真机 20:26 日志：
                // 候选探活失败 → 转 WebView 嗅探播放页 → parse=1）。
                || s.contains("/listen") || s.contains("/tingshu") || s.contains("/audiobook")
                || s.contains("/m4a") || s.contains("/ting/");
    }

    private static boolean isNovelPage(String url) {
        String s = url == null ? "" : url.toLowerCase();
        return s.contains("/novel") || s.contains("/book/") || s.contains("/chapter") || s.contains("/read/");
    }

    /** r287：图片/漫画页判定。 */
    private static boolean isPicPage(String url) {
        String s = url == null ? "" : url.toLowerCase();
        return s.contains("/image") || s.contains("/photo") || s.contains("/gallery")
                || s.contains("/album") || s.contains("/pics") || s.contains("/comic") || s.contains("/manga");
    }

    /** r287：从小说页抽正文（常见正文容器优先，兕底用整页去标签后的最长文本）。 */
    private String extractArticle(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String[] pats = {
                "<div[^>]+id=\"content\"[^>]*>([\\s\\S]*?)</div>",
                "<div[^>]+class=\"[^\"]*(?:content|article|read|text)[^\"]*\"[^>]*>([\\s\\S]*?)</div>",
                "<article[^>]*>([\\s\\S]*?)</article>"
        };
        String best = "";
        for (String p : pats) {
            // r486：**遍历全部候选容器** —— m.x33yq.org 实证：章节页首个 class 命中是
            //   readtop「大家在看」推荐区（read 前缀撞 class 词表），真正的 div.content 在其后；
            //   旧实现只取首个匹配 → 正文容器永远抽不到 → 兕底整页去标签（标题+面包屑+推荐区全混入）。
            Matcher pm = Pattern.compile(p).matcher(html);
            while (pm.find()) {
                String seg = pm.group(1);
                String c = stripTags(seg);
                if (TextUtils.isEmpty(c)) continue;
                // r486：**链接密度反杂质** —— 推荐条容器（readtop 11 条书名链接）去标签后
                //   拼成一段文字被当正文 → 用户看到的"正文"全是《四合院：大清回来了》等推荐书名。
                //   真正的章节正文几乎不含链接；文本块里链接占比 >30% → 是导航/推荐区，丢弃。
                //   反例验证：真正文容器（div.content 2400+ 字 0 链接）零误伤。
                if (linkDensityOf(seg) > 0.30) continue;
                if (c.length() > best.length()) best = c;
            }
        }
        if (best.length() < 200) {
            String all = stripTags(html);
            if (all.length() > best.length()) best = all;
        }
        return best.trim();
    }

    /** r486：文本块链接密度 —— <a> 锚文本字数 / 总字数。导航/推荐区 >0.3，正文 <0.02。 */
    private static double linkDensityOf(String seg) {
        if (TextUtils.isEmpty(seg)) return 0;
        int anchor = 0;
        Matcher am = Pattern.compile("<a[\\s\\S]*?</a>", Pattern.CASE_INSENSITIVE).matcher(seg);
        while (am.find()) anchor += stripTags(am.group(0)).length();
        int total = stripTags(seg).length();
        return total == 0 ? 1 : (double) anchor / total;
    }

    /** r486：章节名形态 —— 「第N章/节/回/卷」「Chapter N」。强信号，推荐区书名/导航词都不命中。 */
    private static final Pattern CHAPTER_TEXT =
            Pattern.compile("第\\s*\\d{1,4}\\s*[章节回卷]|Chapter\\s*\\d{1,4}", Pattern.CASE_INSENSITIVE);

    /**
     * r486：**小说章节目录抽取**（框架级，非逐站）。
     * 修「章数不对/只有一章」：m.x33yq.org 书目录页 /read/{bookId}/ 章节列表在 <ul class="read">，
     * genericDetail 抽不到剧集 → 兕底「页面播放$」单条 → 用户看到的整本书只有 1 章。
     * 判据形态自适应：锚文本「第N章」强信号 + 同站 http 链接；目录分页（「下一页」）跟随聚合。
     * 返回 [章名, 章节页URL] 列表；<2 条返回 null（非目录页）。插在「页面播放$」兕底之前。
     */
    private List<String[]> novelChapters(String html, String url) {
        if (TextUtils.isEmpty(html)) return null;
        LinkedHashMap<String, String> byUrl = new LinkedHashMap<>();
        collectNovelChapterLinks(html, byUrl);
        // r486：**介绍页 → 目录页跟随** —— m.x33yq.org 实证：介绍页（/xiaoshuo_N.html）
        //   只有最新 9 章（部分列表），完整 148 章在目录页。形态自适应判据：章节链接去掉
        //   末段数字 id 得到目录前缀（/read/399649/…/ → /read/399649/），页面里存在指向它的
        //   <a href> 时跟随抓取（「阅读」按钮就是它）；目录页自身则 cat==url 跳过。
        if (!byUrl.isEmpty()) {
            try {
                String first = byUrl.keySet().iterator().next();
                String cat = first.substring(0, first.lastIndexOf('/') + 1);
                if (!TextUtils.isEmpty(url) && !cat.equals(url) && catalogLinked(html, cat)) {
                    String ch = get(cat);
                    if (!TextUtils.isEmpty(ch)) {
                        int before = byUrl.size();
                        collectNovelChapterLinks(ch, byUrl);
                        if (byUrl.size() > before) {
                            log("介绍页 → 目录页跟随 " + cat);
                            url = cat;
                            html = ch;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // r486：目录分页聚合 —— x33yq 每页 100 章，148 章分 2 页（/read/N/ → /read/N_2/）。
        // 「下一页」锚跟随 + 无新增即停（r467 教训），上限 4 页防溢。
        int pages = 1;
        java.util.Set<String> seenPages = new java.util.HashSet<>();
        seenPages.add(url == null ? "" : url);
        String cur = url;
        while (byUrl.size() < 800 && pages < 4) {
            String next = nextCatalogPageUrl(html, cur);
            if (TextUtils.isEmpty(next) || seenPages.contains(next)) break;
            seenPages.add(next);
            try {
                String ph = get(next);
                if (TextUtils.isEmpty(ph)) break;
                int before = byUrl.size();
                collectNovelChapterLinks(ph, byUrl);
                if (byUrl.size() == before) break;
                cur = next;
                html = ph;
                pages++;
            } catch (Throwable e) {
                break;
            }
        }
        if (byUrl.size() < 2) return null;
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : byUrl.entrySet()) out.add(new String[]{e.getValue(), e.getKey()});
        // r486：按章节号升序 —— x33yq 目录 DOM 顺序实测 [147, 1, 2, …]（最新章节区排在正文目录前），
        //   不排序 → 阅读器选集乱序（第 1 章在第 2 位）。章节号从锚文本取；无号的（罕见）保持原序。
        java.util.Map<String, Integer> order = new LinkedHashMap<>();
        int fallbackIdx = 0;
        for (String[] ep : out) {
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile("(?:第|Chapter)\\s*(\\d{1,4})", Pattern.CASE_INSENSITIVE).matcher(ep[0]);
            order.put(ep[1], nm.find() ? Integer.parseInt(nm.group(1)) : 100000 + fallbackIdx);
            fallbackIdx++;
        }
        out.sort((a, b) -> {
            int ia = order.getOrDefault(a[1], 200000), ib = order.getOrDefault(b[1], 200000);
            return ia != ib ? Integer.compare(ia, ib) : 0;
        });
        log("小说章节目录 " + out.size() + " 章" + (pages > 1 ? "（" + pages + " 页聚合）" : ""));
        return out;
    }

    /** r486：收章节链接 —— 锚文本「第N章」强信号 + 同站 http；阅读区「上一章/下一页」不命中。 */
    private void collectNovelChapterLinks(String html, LinkedHashMap<String, String> byUrl) {
        Matcher am = Pattern.compile("<a([^>]{0,2000}?)>([\\s\\S]{0,120}?)</a>", Pattern.DOTALL).matcher(html);
        while (am.find() && byUrl.size() < 900) {
            String text = stripTags(am.group(2)).trim();
            if (text.isEmpty() || text.length() > 40) continue;
            if (!CHAPTER_TEXT.matcher(text).find()) continue;
            String href = firstMatchGroup(am.group(1), "href=\"([^\"]{2,200})\"");
            if (TextUtils.isEmpty(href) || href.startsWith("#") || href.toLowerCase().startsWith("javascript")) continue;
            String absu = abs(href);
            if (!absu.startsWith("http")) continue;
            byUrl.putIfAbsent(absu, text);
        }
    }


    /** r486：页面是否存在指向 cat 的链接（abs 后精确相等）—— 介绍页判断「目录页可跟随」用。 */
    private boolean catalogLinked(String html, String cat) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(cat)) return false;
        Matcher am = Pattern.compile("<a[^>]{0,2000}?href=\"([^\"]{2,200})\"", Pattern.CASE_INSENSITIVE).matcher(html);
        while (am.find()) {
            try {
                if (cat.equals(abs(am.group(1)))) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
    /** r486：目录页的「下一页」链接 —— 锚文本精确「下一页/下页/Next」，abs 后同站。 */
    private String nextCatalogPageUrl(String html, String curUrl) {
        Matcher am = Pattern.compile("<a([^>]{0,2000}?)>([^<]{0,20}?)</a>").matcher(html);
        while (am.find()) {
            String text = stripTags(am.group(2)).trim();
            if (!"下一页".equals(text) && !"下页".equals(text) && !"next".equalsIgnoreCase(text)) continue;
            String href = firstMatchGroup(am.group(1), "href=\"([^\"]{2,200})\"");
            if (TextUtils.isEmpty(href) || href.startsWith("#")) continue;
            String absu = abs(href);
            if (!absu.startsWith("http") || absu.equals(curUrl)) continue;
            return absu;
        }
        return null;
    }

    /**
     * r486：**章节正文分页拼接** —— 长章分多页（x33yq 章节页「下一页」= /read/{book}/{chapter}_N.html）。
     * 旧逻辑只抓第 1 页 → 用户看到的正文只有半章。判据：「下一页」锚存在且 href 同章前缀（_N 后缀）。
     * 顺序推导 _2/_3…（存在才拼），上限 5 页；「下一章」不拼（不同章 id）。
     */
    private String followChapterPages(String body, String html, String pageUrl) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(pageUrl)) return body;
        String next = nextChapterPageUrl(html, pageUrl);
        if (TextUtils.isEmpty(next)) return body;
        StringBuilder full = new StringBuilder(body == null ? "" : body);
        String cur = pageUrl;
        int fetched = 0;
        while (!TextUtils.isEmpty(next) && fetched < 5) {
            try {
                String ph = get(next);
                if (TextUtils.isEmpty(ph)) break;
                String part = extractArticle(ph);
                if (TextUtils.isEmpty(part) || part.length() < 40) break;
                full.append('\n').append(part);
                cur = next;
                next = nextChapterPageUrl(ph, cur);
                fetched++;
            } catch (Throwable e) {
                break;
            }
        }
        if (fetched > 0) log("章节分页拼接 " + fetched + " 续页（" + pageUrl + "）");
        return full.toString();
    }

    /** r486：本章「下一页」URL —— 锚文本「下一页」且 href 去扩展名后 = 当前页去 _N 后缀 + _M（同章分页形态）。 */
    private String nextChapterPageUrl(String html, String curUrl) {
        String curBase = curUrl.replaceAll("[?#].*$", "").replaceAll("\\.html?$", "");
        String root = curBase.replaceAll("_\\d+$", "");
        Matcher am = Pattern.compile("<a([^>]{0,2000}?)>([^<]{0,20}?)</a>").matcher(html);
        while (am.find()) {
            String text = stripTags(am.group(2)).trim();
            if (!"下一页".equals(text) && !"next".equalsIgnoreCase(text)) continue;
            String href = firstMatchGroup(am.group(1), "href=\"([^\"]{2,200})\"");
            if (TextUtils.isEmpty(href) || href.startsWith("#")) continue;
            String absu = abs(href);
            String nb = absu.replaceAll("[?#].*$", "").replaceAll("\\.html?$", "");
            if (nb.startsWith(root + "_") && nb.length() > root.length() + 1) return absu;
        }
        return null;
    }


    /**
     * r486：小说页 → novel:// 载荷（正文抽取 + 目录页防御 + 章节分页拼接）。
     * 从 playerContent 小说分支抽出共用：浏览器模式 BROWSER_NOVEL 虚拟源回源同一章节页时
     * 也用它（SiteApi 侧临时 WebSpider 实例调用）。返回 novel://{JSON}；抽不到正文返回 null。
     */
    String novelUrlOf(String html, String page) {
        if (TextUtils.isEmpty(html)) return null;
        try {
            // r486：**目录页防御** —— player 直接拿到书目录页 URL 时（旧缓存/外部入口），
            //   整页去标签的兕底会把章节名列表当正文。novelChapters ≥2 章 → 是目录页 →
            //   直接改读第 1 章正文（阅读器里选集来自 detail 阶段，这里只负责这一章文本）。
            List<String[]> nch = novelChapters(html, page);
            String firstUrl = page;
            String srcHtml = html;
            if (nch != null && nch.size() >= 2) {
                try {
                    String ch1 = get(nch.get(0)[1]);
                    if (!TextUtils.isEmpty(ch1)) { srcHtml = ch1; firstUrl = nch.get(0)[1]; }
                    log("目录页 → 改读第 1 章（目录 " + nch.size() + " 章）");
                } catch (Throwable ignored) {
                }
            }
            String body = extractArticle(srcHtml);
            // r486：**章节分页拼接** —— 长章分多页（255074379.html → _2.html「下一页」），
            //   旧逻辑只抓第 1 页 → 正文只有半章。同章前缀（_N 后缀）跟随，上限 5 页。
            if (!TextUtils.isEmpty(body) && body.length() > 120) {
                body = followChapterPages(body, srcHtml, firstUrl);
            }
            if (TextUtils.isEmpty(body) || body.length() <= 120) return null;
            JSONObject o = new JSONObject();
            o.put("content", body);
            o.put("title", firstMatch(srcHtml, "<h1[^>]*>([^<]{1,80})</h1>"));
            log("小说正文 " + body.length() + " 字（分页已拼）→ novel://");
            return "novel://" + o;
        } catch (Throwable e) {
            log("novelUrlOf 失败 " + e);
            return null;
        }
    }
    /** r287：去标签、还原常见实体、压掉多余空白。 */
    private static String stripTags(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<script[\\s\\S]*?</script>", " ")
                .replaceAll("<style[\\s\\S]*?</style>", " ")
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</p>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return t.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** r287：收集图片页里的大图（排除 logo/icon/头像/占位）。 */
    private List<String> collectBigImages(String html) {
        List<String> out = new ArrayList<>();
        // r373：广告图常见形态是站点模板里的 **短名 gif**（如 aidjst.cc 的
        // /888/wp-content/uploads/2026/05/155555.gif）。真机日志：pics:// 第一张就是它，
        // 用户反馈「只能看一张垃圾广告图片」。策略：gif 先单独记，存在非 gif 图时就全丢。
        List<String> gifHits = new ArrayList<>();
        // r5xx：来自随机 data-* 的图（91吃瓜 系）是 AES 密文，出口要包本地代理解密。
        java.util.Set<String> aesUrls = new java.util.HashSet<>();
        if (TextUtils.isEmpty(html)) return out;
        // r376：**tuiimg 家族图集序列展开** —— 正文只内嵌第 1 张（`<img src=".../1.jpg" id="nowimg">`），
        // 其余靠 goshownext() JS 逐张替换 src；真实张数写在底部「展开全图(1/22)」。
        // 页面其它 img 是**推荐位**（realsrc=".../mc.jpg"，属于别的图集）—— 沙箱比对：
        // 真图 009/4134/1.jpg…22.jpg 均 200，而 mc.jpg 是邻居图集封面（用户看到的「重复内容」）。
        // 不展开的后果（真机 16:31/21:17）：pics:// 里 6 张全是别人的封面 + 1 张真图。
        try {
            Matcher nowImg = Pattern.compile("<img[^>]+id=\"nowimg\"[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
            Matcher totalM = Pattern.compile("展开全图\\s*\\(\\s*1\\s*/\\s*(\\d{1,3})\\s*\\)").matcher(html);
            // r482：tuiimg 家族新形态 —— 总数不在「展开全图(1/N)」文本，而在内联数组
            //   _pd=[4146,"009/4146/",1,33,0,4145,0,228]（m.tuiimg.com/meinv/4146/ 实证：页面仅
            //   5481 字、9 img 全是菜单/logo+1 张真图，真图由 beauty.js goshownext() 逐张切换；
            //   沙箱实证末张 17/33→200、越界 999→404，编号边界真实）。双条件（nowimg id +
            //   数组形态）同时满足才展开，普通站零误伤。
            int total = 0;
            if (totalM.find()) total = parseInt(totalM.group(1), 0);
            else {
                Matcher pd = Pattern.compile("\\[\\s*\\d+\\s*,\\s*\"[^\"]*/\"\\s*,\\s*\\d+\\s*,\\s*(\\d{1,4})\\s*[:,\\]]").matcher(html);
                if (pd.find()) total = parseInt(pd.group(1), 0);
            }
            if (nowImg.find() && total >= 2) {
                Matcher sm0 = Pattern.compile("src=\"([^\"]+)\"").matcher(nowImg.group(0));
                if (sm0.find()) {
                    String first = abs(sm0.group(1));
                    Matcher num = Pattern.compile("^(.*/)(\\d+)(\\.\\w+)$").matcher(first);
                    if (total >= 2 && total <= 200 && num.matches()) {
                        List<String> seq = new ArrayList<>();
                        for (int i = 1; i <= total; i++) seq.add(num.group(1) + i + num.group(3));
                        log("图集序列展开 " + (first.length() > 60 ? first.substring(0, 60) + "…" : first) + " → " + seq.size() + " 张");
                        return seq;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // r375：NAIHH Shared Media 家族（aidjst.cc 等）：img 的 src 是会话/时效绑定的 CDN 直链
        //（真机 ImageProbe 全 403，带/不带 Referer 都拒；token 随页面缓存一起过期），
        // data-naihh-shared-fallback 才是站点自身的 302 现签代理端点
        //（?naihh_shared_image=<id>&index=<n>，列表封面正是走它才显示）。
        // r376：**懒加载真图（realsrc）** —— tuiimg 家族卡片是
        //   `<img src=".../loading.gif" realsrc=".../1.jpg">`（r375 的整标签逻辑只取 src，
        //   拿到 loading.gif 后被占位词过滤，整条丢弃 → 沙箱复刻：图集页只抽到 1 张（旧逻辑 8 张），
        //   抽图数<3 后 looksLikePicturePage 判 false → 图片页被丢给 WebView 嗅探，图片查看器根本不开）。
        // 优先级：NAIHH fallback（aidjst）→ realsrc（懒加载真图）→ data-src/data-original → src。
        Matcher m = Pattern.compile("<img[^>]+>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find() && out.size() < 200) {
            String tag = m.group(0);
            boolean fb = false;
            boolean aesWrap = false;
            String raw = null;
            Matcher fm = Pattern.compile("data-naihh-shared-fallback=\"([^\"]+)\"").matcher(tag);
            if (fm.find()) {
                raw = fm.group(1).replace("&#038;", "&").replace("&amp;", "&");
                fb = true;
            } else {
                // r376：realsrc 必须排在 src 之前 —— 占位图站三家都这么写
                for (String attr : new String[]{"realsrc", "data-src", "data-original", "src"}) {
                    Matcher sm = Pattern.compile(attr + "=\"([^\"]+)\"").matcher(tag);
                    if (!sm.find()) continue;
                    String cand = sm.group(1);
                    String lowCand = cand.toLowerCase(Locale.ROOT);
                    // 占位图（loading/blank/placeholder）直接跳到下一个属性取真图
                    if (lowCand.contains("loading") || lowCand.contains("blank.") || lowCand.contains("placeholder")) continue;
                    raw = cand;
                    break;
                }
            }
            if (raw == null) {
                // r5xx：**防爬站把正文真图塞进随机命名的 data-*** —— 91吃瓜 实测：
                // <img src="/usr/plugins/tbxw/zw.png" data-xuid="1" data-xkrkllgl="https://pic…jpeg">
                // src 是占位图、真图属性名每篇随机（xuid/xkrkllgl/…），靠白名单永远抽不到；
                // 只能按「值像图片」兜底。这类图是 Mirages 系 AES 密文，交给本地代理解密。
                Matcher dm = Pattern.compile("data-[a-zA-Z0-9_-]{2,24}=\"(https?://[^\"]{6,400})\"").matcher(tag);
                while (dm.find()) {
                    String cand = dm.group(1);
                    if (looksLikeImage(cand) && !isPlaceholderPic(cand)) {
                        raw = cand;
                        aesWrap = true;
                        break;
                    }
                }
            }
            if (raw == null) continue;
            String u = abs(raw);
            String low = u.toLowerCase();
            if (low.startsWith("data:")) continue;
            // r318：把 /static/ 收窄成明确的 UI 目录 —— 全局匹配 /static/ 会误杀
            // 那些把正文图放在 /static/uploads/ 或 /static/media/ 下的站。
            if (low.contains("logo") || low.contains("icon") || low.contains("avatar") || low.contains("blank.")
                    || low.contains("placeholder") || low.contains("loading") || low.contains("sprite")
                    || low.contains("menu") || low.contains("-h5.") || low.contains("_h5.")
                    || low.contains("/static/img/") || low.contains("/static/images/") || low.contains("/static/web/")
                    || low.contains("/static/css/") || low.contains("/static/js/")
                    || low.contains("btn") || low.contains("banner")
                    // r489（AGENTS.md）：Typecho/WordPress 主题与插件资源目录 —— 51吃瓜 榜单文章页
                    // 有 10 张 tg/github/qq/x 等社交按钮 UI 图标（/usr/themes/Mirages/images/），
                    // 文件名极短且分散词干，r301 聚类无法剔除 → collectBigImages 返回 ≥8 →
                    // collectionEpisodes 的「图集判否」误触发 → 榜单 TOP10 链接不转选集 → 用户点开无视频。
                    // 正文内容图（封面/图集）几乎都在 uploads 目录，theme/plugin 目录只放 UI 素材。
                    || low.contains("/usr/themes/") || low.contains("/usr/plugins/")
                    || low.contains("/wp-content/themes/") || low.contains("/wp-content/plugins/")) continue;
            // r375：fallback 代理 URL 无扩展名（?naihh_shared_image=…&index=…），豁免扩展名校验
            if (!fb && !low.matches(".*\\.(jpg|jpeg|png|webp|gif)(\\?.*)?$")) continue;
            // r373：gif 单独收 —— 站点模板广告图多是短名 gif，与非 gif 正文图混在一起时全丢
            boolean isGif = !fb && low.matches(".*\\.gif(\\?.*)?$");
            if (isGif) {
                if (!gifHits.contains(u)) gifHits.add(u);
            } else if (!out.contains(u)) {
                out.add(u);
                if (aesWrap) aesUrls.add(u);
            }
        }
        // r373：只有「全都是 gif」时才回退用 gif（真正的 gif 图集站）
        if (out.isEmpty() && !gifHits.isEmpty()) out.addAll(gifHits);
        // r374：**按 host 聚类（结构性修法，取代黑名单加词）** ——
        // 真机 tuiimg：pics:// 第一张是 m.tuiimg.com/images/search.png（搜索按钮图标），
        // 而正文图 7 张全在 i.tuiimg.net（图片 CDN）。
        // 黑名单词表永远列不全（logo→验证码→gif→search→…），改为：按图片 URL 的 host 分组，
        // 取数量最多的一组（仅当它 ≥ 其余组总和时才采用，避免误杀混合图源站）。
        try {
            if (out.size() >= 4) {
                Map<String, List<String>> byHost = new LinkedHashMap<>();
                for (String u2 : out) byHost.computeIfAbsent(hostOf(u2), k -> new ArrayList<>()).add(u2);
                if (byHost.size() >= 2) {
                    List<String> top = null;
                    for (List<String> g : byHost.values()) if (top == null || g.size() > top.size()) top = g;
                    if (top != null && top.size() >= 3 && top.size() >= out.size() - top.size()) {
                        log("图集按 host 聚类：候选 " + out.size() + " 张 / " + byHost.size() + " 域 → 取 "
                                + hostOf(top.get(0)) + " " + top.size() + " 张");
                        out = top;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // r301：图集聚类 —— 从候选里挑「同目录 + 同词干」数量最多的一组作为正文图集。
        // 真机 meizi5 反馈：正文是 VOL_350_1..12（12 张同前缀），而广告图、推荐图各 1~2 张、词干不同，
        // 旧逻辑「收集到就全返回」把广告图和推荐图一起塞进了图片查看器。
        if (out.size() >= 4) {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            for (String u : out) groups.computeIfAbsent(stemOf(u), k -> new ArrayList<>()).add(u);
            List<String> best = null;
            for (List<String> g : groups.values()) if (best == null || g.size() > best.size()) best = g;
            if (best != null && best.size() >= 3 && best.size() < out.size()) {
                log("图集聚类：候选 " + out.size() + " 张 → 取同前缀 " + best.size() + " 张");
                return wrapAesPics(best, aesUrls);
            }
        }
        return wrapAesPics(out, aesUrls);
    }

    /** r5xx：把 AES 密文图（随机 data-* 取到的）包成本地代理解密地址。 */
    private List<String> wrapAesPics(List<String> list, java.util.Set<String> aes) {
        if (list == null || aes == null || aes.isEmpty()) return list;
        List<String> wrapped = new ArrayList<>(list.size());
        for (String u : list) wrapped.add(aes.contains(u) ? proxyPic(u) : u);
        return wrapped;
    }

    /** r301：图片「目录 + 去尾号词干」，把同一图集的连续图聚到一组（VOL_350_1 → VOL_350_）。 */
    private static String stemOf(String url) {
        try {
            String p = url;
            int q = p.indexOf('?');
            if (q > 0) p = p.substring(0, q);
            int slash = p.lastIndexOf('/');
            String dir = slash >= 0 ? p.substring(0, slash + 1) : "";
            String name = slash >= 0 ? p.substring(slash + 1) : p;
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            name = name.replaceAll("[_\\-]?\\d+$", "");
            return dir + name;
        } catch (Throwable e) {
            return url;
        }
    }

    /**
     * r390（AGENTS.md 第二十一节）：**图集详情分页展开**。
     *
     * <p>47o.net 实证（A 级，真实 HTTP）：`/meitu/5hn` 第 1 页只有 `1.jpg…42.jpg`，
     * 页底有 WordPress 分页 `href="/meitu/5hn/page/2"`；第 2 页是 `43.jpg…70.jpg`（28 张）。
     * 直接 HEAD 验证：43/44/60.jpg 均 200、80.jpg 404 —— 站点真有 70 张，不是 42 张。
     * 框架只抓第 1 页 → 用户看到「所有内容都只有 42 张图片」。</p>
     *
     * <p>规则：只认同详情路径的分页（`{page}/page/N` 或 `{page}?page=N`），最多展开 12 页、
     * 总图 ≤600 张；任一页无新增或命中死页即停（既防失控，也防把 404 页的推荐位当正文）。</p>
     */
    private List<String> expandGalleryPages(String pageUrl, String html, List<String> imgs) {
        if (imgs == null || imgs.isEmpty() || TextUtils.isEmpty(pageUrl)) return imgs;
        if (imgs.size() > 600) return imgs;
        String base = pageUrl.split("\\?")[0].replaceAll("/+$", "");
        int maxPage = 1;
        boolean queryStyle = false;
        Matcher m = Pattern.compile(Pattern.quote(base) + "/page/(\\d+)").matcher(html);
        while (m.find()) maxPage = Math.max(maxPage, parseInt(m.group(1), 1));
        if (maxPage <= 1) {
            Matcher q = Pattern.compile(Pattern.quote(base) + "\\?[^\"']{0,40}?page=(\\d+)").matcher(html);
            while (q.find()) {
                maxPage = Math.max(maxPage, parseInt(q.group(1), 1));
                queryStyle = true;
            }
        }
        if (maxPage <= 1) return imgs;
        int cap = Math.min(maxPage, 12);
        LinkedHashSet<String> all = new LinkedHashSet<>(imgs);
        int before = all.size();
        for (int p = 2; p <= cap; p++) {
            try {
                String u = queryStyle ? base + "?page=" + p : base + "/page/" + p;
                String ph = get(u);
                if (TextUtils.isEmpty(ph) || isDeadPage(ph)) break;
                List<String> more = collectBigImages(ph);
                int added = 0;
                for (String s : more) {
                    if (all.size() >= 600) break;
                    if (s != null && all.add(s)) added++;
                }
                log("图集分页 P" + p + " 新增 " + added + " 张（累计 " + all.size() + "）");
                if (added == 0) break;
            } catch (Throwable t) {
                log("图集分页 P" + p + " 异常 " + t);
                break;
            }
        }
        if (all.size() > before) {
            log("图集分页展开 " + before + " → " + all.size() + " 张（分页共 " + maxPage + " 页）");
        }
        return new ArrayList<>(all);
    }

    private static String readerAware(String id, String title) {
        String u = id == null ? "" : id.toLowerCase();
        boolean kind = u.contains("/novel") || u.contains("/book/") || u.contains("/chapter")
                || u.contains("/image") || u.contains("/photo") || u.contains("/gallery") || u.contains("/album")
                || u.contains("/music") || u.contains("/audio") || u.contains("/song") || u.contains("/mp3");
        // 只处理详情级链接（带数字 id），避免把「音乐」「图片」这类分类入口也变成浏览器卡
        if (!kind || !u.matches(".*\\d.*")) return id;
        try {
            JSONObject t = new JSONObject();
            t.put("type", "browser");
            t.put("url", id);
            t.put("title", title == null ? "" : title);
            return t.toString();
        } catch (Throwable e) {
            return id;
        }
    }


    /**
     * r412：**从锚点自身属性取封面** —— 很多站把封面写在 `<a data-original/data-src/realsrc>` 上
     * （lu9936 全站如此，卡片里根本没有 `<img>`）。属性优先级与图片一致：
     * NAIHH fallback → realsrc → data-src → data-original → src。
     */
    private static String pickImgFromAnchor(String anchorTag) {
        if (TextUtils.isEmpty(anchorTag)) return "";
        for (String attr : new String[]{"data-naihh-shared-fallback", "realsrc", "data-src", "data-original", "src"}) {
            Matcher m = Pattern.compile(attr + "=\"([^\"]{8,500})\"").matcher(anchorTag);
            if (!m.find()) continue;
            String u = m.group(1);
            if (u.startsWith("data:") || u.startsWith("javascript:")) continue;
            if (Pattern.compile("(?i)(loading|blank|placeholder|spacer|logo|avatar|/static/img/svg/|\\.gif$)")
                    .matcher(u).find()) continue;
            return u;
        }
        return "";
    }
    private String pickImgNearest(String back, String fwd) {
        String pb = "";
        int db = Integer.MAX_VALUE;
        Matcher mb = Pattern.compile("<img[^>]{0,2000}>", Pattern.CASE_INSENSITIVE).matcher(back);
        while (mb.find()) {
            pb = mb.group(0);
            db = back.length() - mb.start();
        }
        String pf = "";
        int df = Integer.MAX_VALUE;
        Matcher mf = Pattern.compile("<img[^>]{0,2000}>", Pattern.CASE_INSENSITIVE).matcher(fwd);
        if (mf.find()) {
            pf = mf.group(0);
            df = mf.start();
        }
        String pick = (db <= df) ? pb : pf;
        String url = pickImgOk(pick);
        if (!TextUtils.isEmpty(url)) return finishPic(url);
        url = pickImgOk(pb + fwd);
        if (!TextUtils.isEmpty(url)) return finishPic(url);
        // r4xx（通用化）：很多站的封面压根不在 <img> 里，而是挂在 div/a 的
        //   data-* / 自定义懒加载属性 / background-image 上（昏黄影视 _pic 同款兜底思路）。
        //   窗口内一张 <img> 都没有时，上面两个分支必空 → 封面全空白。这里把整个窗口
        //   交给 pickImgRaw 做通用属性扫描，任何「像图片的 URL 且非占位图」都可用。
        return finishPic(pickImgOk(back + fwd));
    }

    private String pickImgOk(String seg) {
        String pic = pickImg(seg);
        if (TextUtils.isEmpty(pic) || hostOk(pic)) return pic;
        String fb = pickImgFallback(seg);
        if (!TextUtils.isEmpty(fb) && hostOk(fb)) {
            log("封面主域名不可达，换备用域名 " + hostOf(pic) + " → " + hostOf(fb));
            return fb;
        }
        return pic;
    }

    private static final Map<String, Boolean> PIC_HOST_OK = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean hostOk(String url) {
        try {
            String host = hostOf(url);
            if (TextUtils.isEmpty(host)) return true;
            Boolean cached = PIC_HOST_OK.get(host);
            if (cached != null) return cached;
            boolean ok;
            try {
                okhttp3.Request req = new okhttp3.Request.Builder().url(url).head().build();
                try (Response r = OkHttp.client(1200).newCall(req).execute()) {
                    ok = (r.code() >= 200 && r.code() < 400) || r.code() == 405;
                }
            } catch (Throwable e) {
                ok = false;
            }
            PIC_HOST_OK.put(host, ok);
            return ok;
        } catch (Throwable e) {
            return true;
        }
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * r249：<b>明确播放路由</b>才算剧集 —— 必须命中 /play/、/bofang/、/vodplay/、/player/、/watch/
     * 或苹果 CMS 的 /index.php/vod/play/。
     *
     * <p>旧规则把 /video 路径也算播放链接，而 A 站这类站的详情页本身就是 /video/{id}/，
     * 页面上还散落 /video/now-month-hot/、/video/search/xxx/ 等导航，以及大量推荐位
     * /video/{其它id}/ —— 全被当成剧集，播放器第一条就是分类页，必然播不动
     * （r246/r248 真机日志三次实证 page=/video/now-month-hot/ 候选=0）。
     */
    private static final Pattern P_PLAY_ROUTE = Pattern.compile(
            // r383（AGENTS.md 第十四节）：漫画站选集两大形态，旧正则一个都不认：
            //   ① MANGABZ（18seman / 漫画库）：/index.php/chapter/{id}      → 旧正则抽 0 集
            //   ② 哇咔系：/comic/{id}/{n}.html                           → 旧正则抽 0 集
            // 旧行为后果：抽 0 集 → 退化用详情页 URL 调 playerContent → 详情页无视频/图不够
            //            → 转 WebView 嗅探 → 必有失败（这就是「漫画站无法一次性适配」的真因）。
            // 反例验证（24 份夹具）：加 chapter 仅 18seman/漫画库变化，加 comic/{id}/{n}
            //                       后旧站集数零变化（ddys/gaze/meizi5/4kcz/gaze/qmtsw 全部不变）。
            "<a[^>]+href=\"([^\"]*/(?:play|v_play|bofang|vodplay|player|watch|chapter)/[^\"]*"
                    + "|/comic/\\d+/\\d+\\.html"
                    + "|[^\"]*/index\\.php/vod/play/[^\"]*)\"[^>]*>(.{0,120}?)</a>",
            Pattern.DOTALL);

    private String root;      // https://host，无尾斜杠
    private String rawBase;   // r350：原始 base（含 path，如 https://www.thisav66.com/cn）——首页抓取用；root 仍是 origin（abs 拼接用）
    private String host;
    private String label;
    private String indexPath = "/index.php"; // r455：maccmsphp 子目录部署发现——cgy.cghjs2.boats 实证 index.php 挂在 /cn/home/web/ 下

    private boolean detected;
    /**
     * r520：最近一次 GET 实际落地的 URL（跟随 3xx 之后）。
     *
     * <p>用途：三方播放器（qiyoudy2 → meizi.yongfan99.com/content.php → 302 → /player/index.php）
     * 的 api.php 是**相对播放器页自身目录**的，只用外层播放页 URL 拼不出正确路径。
     */
    private String lastFetchUrl = "";

    /** push v44：最近一次 get() 的最终 HTTP 状态码（跟完 3xx 之后；0=未取到）。只供搜索候选判「报错页不算结果」用，
     *  首页/分类/详情路径不读它，行为不变。 */
    private volatile int lastFetchCode;

    /**
     * r292：首页按站点缓存 —— detect() 里的 detected 是实例字段，而壳对 detail/player 往往用不同实例，
     * 真机 14:37 日志里 detect 在同一次操作中跑了两遍，每次都去抓 224778 字的首页（2~5 秒）。
     * 缓存后第二遍直接复用。
     */
    private static final Map<String, String> HOME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * r388（AGENTS.md 第十八节）：付费墙分类缓存 —— key = root|组键，value = 判定时间戳。
     * 47o.net 实证：/comic、/novel、/movie、/meitu/category/* 都是站方「内容解锁」墙，
     * 每次 detect 重探会白耗 4 x 6s；30 分钟内直接命中跳过。
     */
    private static final Map<String, Long> PAYWALL_GROUPS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PAYWALL_TTL = 30 * 60 * 1000L;
    /** r356：密码盾 cookie —— ddys.la 首页「请输入密码」盾页 JS setCookie("pageLogin","ok")，
     *  提取名值对按 host 全局复用，get() 自动带上（沙箱 verify 32/32 实证过盾后首页 45210 字）。 */
    private static final Map<String, String> SHIELD_COOKIE = new java.util.concurrent.ConcurrentHashMap<>();
    /** r345：合集选集翻页聚合结果按 URL 缓存（LRU 8 条）—— both /moviesets/zipaitoupai/ 全量 58 页 1375 集，
     *  反复进出同一合集详情页时省去重抓 58 页（~4s + 9MB）。进程存活期内有效，与 HOME_CACHE 同策略。
     *  collectAllPages 内 synchronized 读写，不必并发容器。 */
    private static final LinkedHashMap<String, List<String[]>> COLLECTION_CACHE = new LinkedHashMap<>();
    /** r374：最近一次成功抓取的页面 {url, ts, html} —— gaze 真机实证：detail 在 16:27:55 抓过 play 页，
     *  player 阶段 WebView 16:27:57 再请求同 URL → HTTP 429 风控（验证弹窗弹了但页面上没有验证组件可点）。
     *  修法：WebView 遇 429/403 时用缓存 HTML loadDataWithBaseURL 重放，不再发网络请求，页面解密 JS 照常跑。 */
    private static volatile String[] LAST_PAGE;

    public static void rememberPage(String url, String html) {
        try {
            if (html == null || html.length() < 500) return;
            LAST_PAGE = new String[]{url, String.valueOf(System.currentTimeMillis()), html};
        } catch (Throwable ignored) {
        }
    }

    /** 取最近 10 分钟内抓过的同 URL 页面 HTML（没有则 null）。 */
    public static String lastPageHtml(String url) {
        return lastPageHtmlWithin(url, 600000);
    }

    /** r377：取 maxAgeMs 内抓过的同 URL 页面 —— player 阶段复用 detail 刚抓的页面，少发一次 GET。
     *  沙箱实测：同一 URL 每 45 秒只给 3 次 GET，第 4 次必 429；真机 22:14 日志：
     *  detail 抓 + detail cap 重试 + player 再抓 + WebView = 4 次同 URL GET → WebView 拿到 429，
     *  验证组件所在的真页根本没加载，用户点验证时 cap.js 的 POST 也一起 429 → 「验证失败」。 */
    public static String lastPageHtmlWithin(String url, long maxAgeMs) {
        try {
            String[] lp = LAST_PAGE;
            return lp != null && lp[0].equals(url)
                    && System.currentTimeMillis() - Long.parseLong(lp[1]) < maxAgeMs ? lp[2] : null;
        } catch (Throwable e) {
            return null;
        }
    }
    /** r292：Cap cookie 按站点缓存 —— detail 阶段求解过，player 阶段不必再解一次（省 1.5~2.5 秒）。 */
    private static final Map<String, String> CAP_COOKIE = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * r377：**不该纯 HTTP 重试的 Cap 站点**（协议非 rsw，如 hashwx/sha256-pow）。
     * 真机 22:12/22:14 两次实测：站点已升级为 hashwx（WASM 求解），老 rsw 解法拿不到有效 cookie，
     * 但代码仍会「带无效 cookie 重试一次」—— 沙箱量化实测同一 URL 每 45 秒只允许 3 次 GET（第 4 次必 429），
     * 这多出来的一次请求正好把 WebView 的额度吃掉，WebView 拿到 429 就再也看不到验证组件。
     * 命中此集合的站点直接跳过 HTTP 求解与重试，把整份额度留给 WebView 的 cap.js 原生验证。
     */
    private static final java.util.Set<String> CAP_UNSUPPORTED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /**
     * r301：分类分页内容指纹 —— 用来识别「分页其实无效」的站。
     * 真机 meizi5 日志：page=17/18/19/20/21 的 list 第一条全是同一个 13147.html，total 每页还 +20，
     * 于是翻页永远翻不完、同一批内容不断叠加。任何站只要出现「本页与上一页完全一致」就停止翻页。
     */
    private static final Map<String, String> PAGE_IDS = new java.util.concurrent.ConcurrentHashMap<>();
    private String template = "generic";
    private String detailPattern = "";
    /**
     * r415：**条目级内容类型**（vod_id → video/pic）。
     *
     * <p>用户 2026-11-09 明令：「混合站要点什么内容就调用什么播放器，而不是一个网站只能调用一种播放器」。
     * 之前类型判定是**页面级**（`looksLikePicturePage(html)`），混合站必然出事：
     * aisee9w11 实证 —— `/detail-44578/` 是 24 集视频，但页面里有 169 张剧照，
     * `hasStrongVideoSignal` 为 false（播放器靠 player_data 在 JS 里）→ 被判图集 → 返回 pics://，
     * 用户点视频却打开了图片查看器。</p>
     *
     * <p>修法：detail 阶段已经确知这条是视频（有选集/线路）还是图集（无选集+大图≥8），
     * 记下来；player 阶段优先按记录分流，兜底才用页面级判断。</p>
     */
    private static final Map<String, String> VOD_TYPE = new java.util.concurrent.ConcurrentHashMap<>();
    /** r349：搜索端点模板（含 {kw} 占位，如 /filter?search={kw} 或绝对地址 http://b.faloo.com/l/0/1.html?t=0&k={kw}）。
     *  detect 阶段从首页表单/JS 发现；跨实例静态缓存（detail/player/search 各建实例，不缓存会重复抓外链 JS）。 */
    private String searchEndpoint;
    /** r349：搜索端点按 host 缓存 —— 与 HOME_CACHE 同策略，进程存活期内有效。 */
    private static final Map<String, String> SEARCH_ENDPOINT = new java.util.concurrent.ConcurrentHashMap<>();
    /** push v45：同一站同一关键词第 1 页命中的候选模板 —— 第 2 页起只用它翻页（不再落进别的候选拿到无关页）。key=根|词 */
    private static final Map<String, String> SEARCH_HIT_TPL = lruMap(64);
    /** push v45：站方分页参数名（第 1 页结果里的同路径分页链接认出，如 page/p/pg/paged；认不出用 page）。key=host */
    private static final Map<String, String> SEARCH_PAGE_PARAM = lruMap(64);
    /** push v45：同一站同一关键词已出现过的条目（按 vod_id）与最近页码 —— 第 N 页条目全是见过的 = 翻到底了。key=根|词 */
    private static final Map<String, Object[]> SEARCH_SEEN = lruMap(64);
    /** push v45：站方第 2 页地址规律（第 1 页结果页里 rel=next / 分页条「2」「下一页」链接，页码位换成 {pg}）。key=根|词 */
    private static final Map<String, String> SEARCH_PAGE_TPL = lruMap(64);
    /** push v45：本次播放解析用到的播放页 HTML（找页面声明的多画质主清单用；每次 playerContent 先清空）。 */
    private String playHtmlForMaster;

    private static <V> Map<String, V> lruMap(int max) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        });
    }
    /**
     * r371：**页面自己请求过的 JSON 接口 URL**（按 host 收集）。
     * 来源：注入 JS 的 XHR/fetch hook 把 content-type 含 json 的 URL 用 sniff://jsonurl: 回传。
     * 用途：抽取失败时，Java 用同一 UA/Cookie 重放这些 URL，把页面已经解密好的
     * 分类/列表 JSON 当数据源 —— 比猜 HTML 结构可靠一个数量级（b7b88/thisav66 类站）。
     */
    private static final Map<String, java.util.LinkedHashSet<String>> JSON_URLS = new java.util.concurrent.ConcurrentHashMap<>();
    /** r373：已确认「不是 JSON」的 URL 缓存 —— 避免重复重放 68KB 分页 HTML（真机 aidjst.cc 慢的根因）。 */
    private static final Map<String, Boolean> JSON_BAD = new java.util.concurrent.ConcurrentHashMap<>();

    /** 注入脚本回传一个 JSON 接口 URL（由 CustomWebView 调）。 */
    public static void onJsonUrl(String url) {
        try {
            if (TextUtils.isEmpty(url)) return;
            String h = hostOf(url);
            if (TextUtils.isEmpty(h)) return;
            java.util.LinkedHashSet<String> set = JSON_URLS.computeIfAbsent(h.toLowerCase(Locale.ROOT), k -> new java.util.LinkedHashSet<>());
            synchronized (set) {
                if (set.size() < 24 && set.add(url)) {
                    String brief = url.length() > 160 ? url.substring(0, 160) : url;
                    com.github.catvod.crawler.SpiderDebug.log("web", "json 接口嗅探 " + brief);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * r372：**原生层看到的请求 URL 分流** —— 只留看起来像 JSON 接口的。
     * 来源：WebFetch 的 shouldInterceptRequest（能看到页面的每一个请求，比 JS 注入可靠）。
     * 排除：静态资源、视频分片；保留：.json / /api/ /ajax / category / list / search / vod / page= 等。
     */
    public static void onUrlSeen(String url) {
        try {
            if (TextUtils.isEmpty(url)) return;
            String low = url.toLowerCase(Locale.ROOT);
            if (low.endsWith(".js") || low.endsWith(".css") || low.endsWith(".png") || low.endsWith(".jpg")
                    || low.endsWith(".jpeg") || low.endsWith(".gif") || low.endsWith(".webp") || low.endsWith(".svg")
                    || low.endsWith(".woff") || low.endsWith(".woff2") || low.endsWith(".ttf") || low.endsWith(".ico")
                    || low.endsWith(".m3u8") || low.endsWith(".mp4") || low.endsWith(".ts") || low.endsWith(".m4s")) return;
            boolean api = low.contains(".json") || low.contains("/api/") || low.contains("/ajax")
                    || low.contains("category") || low.contains("/list") || low.contains("/search")
                    || low.contains("/vod") || low.contains("?page=") || low.contains("&page=")
                    || low.contains("/index.php?") || low.contains("graphql") || low.contains("wp-json");
            if (!api) return;
            onJsonUrl(url);
        } catch (Throwable ignored) {
        }
    }

    /**
     * r371：**重放页面请求过的 JSON 接口** —— 把页面已经自己解密好的数据当数据源。
     * 这是「像浏览器一样拿数据」的落地：页面解它的密，我们只要它请求到的明文 JSON。
     * 优先含 hint 的 URL（如 category/list/search），最多试 4 个，单次 get() 超时 15s。
     */
    private String probeJsonApis(String hint) {
        try {
            String h = hostOf(root).toLowerCase(Locale.ROOT);
            java.util.LinkedHashSet<String> set = JSON_URLS.get(h);
            if (set == null || set.isEmpty()) return null;
            List<String> urls;
            synchronized (set) {
                urls = new ArrayList<>(set);
            }
            if (!TextUtils.isEmpty(hint)) {
                final String hp = hint.toLowerCase(Locale.ROOT);
                urls.sort((a, b) -> (b.toLowerCase(Locale.ROOT).contains(hp) ? 1 : 0) - (a.toLowerCase(Locale.ROOT).contains(hp) ? 1 : 0));
            }
            for (int i = 0; i < Math.min(4, urls.size()); i++) {
                String u = abs(urls.get(i));
                if (JSON_BAD.containsKey(u)) continue;   // r373：已确认非 JSON，不再重放
                String body = get(u);
                if (TextUtils.isEmpty(body) || body.length() < 60) continue;
                String tb = body.trim();
                // r373：**只认真 JSON** —— 真机日志把 /category/.../page/5 这种分页 HTML 当接口重放，
                // 68KB 正文 × 8 次 = 用户感知“加载特别慢”。非 JSON 记入失败缓存，永不重试。
                if (tb.isEmpty() || (tb.charAt(0) != '{' && tb.charAt(0) != '[')) {
                    JSON_BAD.put(u, Boolean.TRUE);
                    log("json 探测跳过（非 JSON）：" + u);
                    continue;
                }
                log("json 数据源 " + u + " → " + body.length() + " 字 | "
                        + tb.substring(0, Math.min(180, tb.length())).replace('\n', ' '));
                return body;
            }
        } catch (Throwable e) {
            log("json 探测异常 " + e);
        }
        return null;
    }
    /** r257：列表页取到的标题/封面缓存（详情页 URL → {标题, 封面}），供挂了人机验证的站点兜底。 */
    private static final Map<String, String[]> LIST_META = new java.util.concurrent.ConcurrentHashMap<>();
    /** r343：分类页渲染兜底缓存（列表 URL → 渲染后 HTML）。只缓存「渲染后确实抽出条目」的页面，
     *  挑战页/垃圾页不落库；上限 8 条满了清空（会话级），避免反复切分类时每次都重跑 WebView。 */
    private static final Map<String, String> PANEL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** r260：缓存 key 归一化（去协议 / 尾斜杠 / query，统一小写）—— 详情与列表 id 稍有差异就命中不了，真机上就是这么空标题的。 */
    private static String normKey(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String s = url.trim().toLowerCase(Locale.ROOT);
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        s = s.replaceFirst("^https?://", "");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
    private final LinkedHashMap<String, String> classes = new LinkedHashMap<>();
    private String homeHtml = "";

    public WebSpider() {
    }

    public WebSpider(String key, String api, String ext) {
        this.siteKey = key;
        setBase(!TextUtils.isEmpty(ext) && ext.contains("http") ? ext : api);
    }

    /** muse v31：全局搜索专用实例置 true —— 不拉起隐形 WebView 渲染（搜索单站 15 秒超时，
     *  渲染兜底动辄 18–25 秒，必然超时且在后台空跑 WebView）；渲染调用一律当「渲染失败」处理（返回空串，
     *  各调用点原本就按失败回退原页/下一候选）。浏览器进站、首页/分类/详情不走本类，不受影响。 */
    public volatile boolean noRender;

    /** muse v31：搜索路径本次遇到验证/挑战页（Cloudflare、Cap、JS 验证、宝塔 WAF、429）。 */
    private volatile boolean challenged;

    /** muse v31：WebSearch 超时后置真，残留的后台搜索线程不再发请求。 */
    public volatile boolean aborted;

    public boolean isChallenged() {
        return challenged;
    }

    private void markSearchChallenge(String url, String why) {
        if (!challenged) log("搜索遇验证，跳过该站（" + why + "）" + hostOf(url));
        challenged = true;
    }

    /**
     * 纯 HTTP 过不了的验证/挑战页（只读，不求解）：Cloudflare 挑战、JS 验证空壳、「系统安全验证/访问验证」页、
     * 过完 JS-cookie/宝塔 后仍残留的挑战壳。Cap 验证另由 getCap 现成求解分支处理，这里不算。
     */
    private static boolean isUnsolvableChallenge(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String low = html.length() > 60000 ? "" : html.toLowerCase(Locale.ROOT);
        if (!low.isEmpty() && (low.contains("_cf_chl_opt") || low.contains("challenge-platform")
                || low.contains("cf-chl") || (low.contains("just a moment") && countHref(html) < 3))) return true;
        // Cloudflare 错误/限流页（1015/1020 等）：带 Ray ID 与 cloudflare.com 链接，不能当发布页去「换域名」。
        if (!low.isEmpty() && (low.contains("cf-error-details") || low.contains("cf-wrapper")
                || (low.contains("ray id") && low.contains("cloudflare") && countHref(html) < 15))) return true;
        if (isJsVerifyPage(html)) return true;
        if (html.length() <= 1500 && html.contains("btwaf=")) return true;
        if (html.length() < 8000 && (html.contains("系统安全验证") || html.contains("访问验证"))) return true;
        return false;
    }

    /**
     * muse v31：搜索路径「放弃该站」判定（Liu 2026-11-23 10:37/10:41）—— 纯 HTTP 过不了的验证/拦截页：
     * Cloudflare / JS 验证空壳 / 安全验证页；以及首页请求拿到的无内容拦截页（403/503 或标题为拦截，CDN CC 策略、WAF 封禁）。
     * 只认首页的那条：单个搜索候选路径 403 可能只是该路径不存在/禁止，不连坐其它候选。
     */
    private boolean searchGiveUp(String url, String cur, int code, String pbody) {
        if (isUnsolvableChallenge(pbody)) {
            markSearchChallenge(cur, "验证页");
            return true;
        }
        if (pbody != null && pbody.length() < 3000 && countHref(pbody) < 3 && isHomeRequest(url)
                && (code == 403 || code == 503 || isBlockTitle(pbody))) {
            markSearchChallenge(cur, "HTTP " + code + " 拦截");
            return true;
        }
        return false;
    }

    /** muse v31：拦截页标题（EdgeOne CC 策略拦截页走完 307 验证器后是 200 + 「403 Forbidden」页）。 */
    private static boolean isBlockTitle(String html) {
        String t = firstMatch(html, "<title[^>]*>\\s*([^<]{0,60})</title>");
        if (t == null) return false;
        String l = t.toLowerCase(Locale.ROOT);
        return l.contains("403") || l.contains("forbidden") || l.contains("access denied") || t.contains("拦截") || t.contains("禁止访问");
    }

    /** muse v31：本次请求是不是站点首页（按发起时的地址判，重定向到验证器路径也算首页请求）。 */
    private boolean isHomeRequest(String url) {
        String u = abs(url);
        if (u == null) return false;
        return u.equals(homeUrl()) || u.replaceFirst("^https?://[^/]+", "").replace("/", "").isEmpty();
    }

    /** muse v31：本次搜索里有现成纯 HTTP 验证分支自动通过（Cap 求解/缓存 cookie、JS 写 cookie、宝塔、403 退避）。 */
    private volatile boolean autoPassed;

    public boolean isAutoPassed() {
        return autoPassed;
    }

    /** 本实例的当前根地址（scheme://host，可能因落地映射变化），供调用方补全相对 vod_id。 */
    public String rootUrl() {
        return root == null ? "" : root;
    }

    private String webFetch(String url, Map<String, String> headers, long timeoutMs) {
        if (noRender) {
            log("搜索路径不渲染 " + url);
            return "";
        }
        return WebFetch.fetch(url, headers, timeoutMs);
    }

    private String webFetch(String url, Map<String, String> headers, long timeoutMs, java.util.concurrent.atomic.AtomicReference<String> landing) {
        if (noRender) {
            log("搜索路径不渲染 " + url);
            return "";
        }
        return WebFetch.fetch(url, headers, timeoutMs, landing);
    }

    /**
     * r486：小说「整本章节列表」公开入口 —— 供浏览器模式（BrowserActivity.routeNovel）复用网页源
     * 同一条章节抽取链路（介绍页→目录页跟随 + 目录分页聚合 + 章节号排序，novelChapters 全套形态自适应）。
     *
     * @param introUrl 介绍页/目录页绝对地址（浏览器模式当前页或「查看目录」链接）
     * @return 章节列表（[名字, 章节页 URL]）；失败返回空列表（调用方回退 DOM 首屏结果）
     */
    public List<String[]> novelChaptersFrom(String introUrl) {
        List<String[]> out = new ArrayList<>();
        if (TextUtils.isEmpty(introUrl) || !introUrl.startsWith("http")) return out;
        try {
            String html = get(introUrl);
            if (TextUtils.isEmpty(html)) return out;
            List<String[]> chs = novelChapters(html, introUrl);
            if (chs != null) out.addAll(chs);
        } catch (Throwable t) {
            log("novelChaptersFrom 失败 " + t);
        }
        return out;
    }

    /**
     * r486：小说「章节页正文」公开入口 —— 供 SiteApi BROWSER_NOVEL 虚拟源按需回源（阅读器翻章时）。
     * 内部复用 novelUrlOf 全套：目录页防御（整页去标签兑底不把章节名列表当正文）+
     * extractArticle 正文抽取（链接密度反杂质）+ 章节分页拼接（_N 后缀「下一页」）。
     *
     * @param url 章节页绝对地址（NovelReaderActivity 传来的章节标识）
     * @return novel://{JSON} 载荷；抽不到正文返回 null（阅读器显示加载失败）
     */
    public String novelPagePayload(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return null;
        try {
            String html = get(url);
            if (TextUtils.isEmpty(html)) return null;
            return novelUrlOf(html, url);
        } catch (Throwable t) {
            log("novelPagePayload 失败 " + t);
            return null;
        }
    }

    /**
     * r476：图集「全量」抽取入口 —— 供浏览器模式（BrowserActivity）复用网页源同一条图集链路。
     *
     * <p>背景：浏览器模式原先只扫 WebView 当前 DOM 的大图（IMG_PROBE），而「一页 5 张、共 N 页」的
     * 图集站（fp.xiezhen8 实证 76P/16 页，r476 真机日志「图集分流 4 张」）只能拿到首屏 → 用户报
     * 「图片一共有很多张，不过网站是分页了，转壳子图片阅读器只能看第一页」。
     *
     * <p>本方法零重复实现，复用既有链路：{@link #aesPicDecrypt}（AES 图集解密 + PAGE_list 分页 +
     * 连续编号推导，r464-r468 真机验证）→ {@link #collectBigImages} → {@link #expandGalleryPages}
     * （普通 /page/N 分页兜底）。
     *
     * @param pageUrl 详情页绝对地址（浏览器模式当前页）
     * @return 全量图片地址；失败返回空列表（调用方回退 DOM 首屏结果）
     */
    public List<String> galleryImagesFrom(String pageUrl) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(pageUrl) || !pageUrl.startsWith("http")) return out;
        try {
            String html = get(pageUrl);
            if (TextUtils.isEmpty(html)) return out;
            try {
                html = aesPicDecrypt(html);
            } catch (Throwable ignored) {
            }
            List<String> imgs = collectBigImages(html);
            if (imgs == null) imgs = new ArrayList<>();
            try {
                imgs = expandGalleryPages(pageUrl, html, imgs);
            } catch (Throwable ignored) {
            }
            if (imgs != null) {
                for (String s : imgs) {
                    if (!TextUtils.isEmpty(s) && !out.contains(s)) out.add(s);
                }
            }
        } catch (Throwable t) {
            log("图集全量抽取异常 " + t);
        }
        return out;
    }

    // ------------------------------------------------------------------ 基础

    /** r246：埋点，真机 /debug/logs 里用 tag=web 过滤即可看到网页源每一步。 */
    private void log(String msg) {
        try {
            LogCategory stage = LOG_STAGE.get();
            DebugLogStore.add(stage == null ? LogCategory.SPIDER : stage, "web", label + " " + msg, false);
        } catch (Throwable ignored) {
        }
    }

    // 日志分类（Liu 2026-11-23）：按调用入口记阶段——首页/分类/搜索 → 影片，详情/播放 → 播放；
    // 其余（init、引擎自己开的后台线程）拿不到阶段 → 蜘蛛。只影响日志落类，不影响任何判定。
    private static final ThreadLocal<LogCategory> LOG_STAGE = new ThreadLocal<>();

    private static LogCategory enterStage(LogCategory stage) {
        LogCategory previous = LOG_STAGE.get();
        LOG_STAGE.set(stage);
        return previous;
    }

    private static void leaveStage(LogCategory previous) {
        if (previous == null) LOG_STAGE.remove();
        else LOG_STAGE.set(previous);
    }

    /** r246：探测播放地址是否真的可播（有些 CDN 直链是失效的，播不了不等于解析错）。 */
    private boolean playable(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url) || (!url.startsWith("http"))) return false;
        boolean m3u8 = isM3u8(url);
        // r282：m3u8 走 GET + #EXTM3U 校验；mp4/mkv 这类大文件必须先 HEAD（也不能 GET —— 会开始下载，
        // 真机日志实测一次探活被 5 秒超时拖成 13 秒），HEAD 不支持再退回 Range: bytes=0-0。
        // r354：m3u8 **绝不能带 Range** —— 真机 18:06 nnyy 日志「探活拿到的不是 m3u8 清单（1 字）」：
        // CDN（hn.bfvvs.com）对 Range: bytes=0-0 老实回 206 + 1 字节，正文里当然没有 #EXTM3U，
        // 明明可播的直链被判死 → 候选全灭转 WebView 嗅探（用户看到的就是「播放不对」）。
        // 沙箱实证：同地址不带 Range → 200 + 完整 #EXTM3U（AES-128 标准 HLS）。
        // 清单本身只有几 KB，不带 Range 的代价远小于误杀。
        if (m3u8) return probe(url, headers, false, true, false);
        if (probe(url, headers, true, false, true)) return true;
        return probe(url, headers, false, false, true);
    }

    private boolean probe(String url, Map<String, String> headers, boolean head, boolean needExtM3u) {
        return probe(url, headers, head, needExtM3u, !head);
    }

    /** r354：withRange=false 时不发 Range（m3u8 清单必须整份取回才能校验 #EXTM3U）。 */
    private boolean probe(String url, Map<String, String> headers, boolean head, boolean needExtM3u, boolean withRange) {
        try {
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) builder.header(e.getKey(), e.getValue());
            }
            if (!head && withRange) builder.header("Range", "bytes=0-0");
            okhttp3.Request request = head ? builder.head().build() : builder.get().build();
            // r411：探活超时放宽 —— lu9936 真机日志铁证：
            // `SocketTimeoutException: failed to connect to thm3u8.co (port 443) after 4000ms`，
            // 而同一 m3u8 在沙箱是 HTTP 200。CDN 慢的时候 4 秒必然误判成「不可播」。
            // HEAD 3s→6s、GET 4s→8s（m3u8 清单要整份取回，给足首包时间）。
            try (Response response = OkHttp.client(head ? 6000 : 8000).newCall(request).execute()) {
                int code = response.code();
                if (code < 200 || code >= 400) {
                    log("探活 HTTP " + code + " " + url);
                    return false;
                }
                if (!needExtM3u) return true;
                ResponseBody body = response.body();
                if (body == null) return false;
                String text = body.string();
                if (text != null && text.contains("#EXTM3U")) return true;
                log("探活拿到的不是 m3u8 清单（" + (text == null ? 0 : text.length()) + " 字） " + url);
                return false;
            }
        } catch (Throwable e) {
            log("探活异常 " + e.getClass().getSimpleName() + " " + e.getMessage() + " " + url);
            return false;
        }
    }

    /** r252：同一地址换 Referer 依次试 —— 这类站的 CDN 往往只认「真实播放器页」的 Referer。 */
    private Map<String, String> probeHeaders(String url, Map<String, String> base, String frameRef) {
        List<Map<String, String>> tries = new ArrayList<>();
        Map<String, String> h1 = new LinkedHashMap<>(base);
        h1.put("Referer", root + "/");
        tries.add(h1);
        if (!TextUtils.isEmpty(frameRef) && !frameRef.equals(root + "/")) {
            Map<String, String> h2 = new LinkedHashMap<>(base);
            h2.put("Referer", frameRef);
            tries.add(h2);
        }
        Map<String, String> h3 = new LinkedHashMap<>(base);
        h3.remove("Referer");
        // r252b：媒体直链只试两档（站点 / 播放器页 Referer），省得每条候选卡满三次超时
        if (!isMediaUrl(url)) tries.add(h3);
        for (Map<String, String> h : tries) if (playable(url, h)) return h;
        return null;
    }

    /** r252：取 scheme://host/，用来当 Referer。 */
    /** r377：取 challenges[0].protocol（rsw=老的 PoW 可纯 HTTP 解，hashwx/sha256-pow=WASM，纯 HTTP 解不了）。 */
    private static String c0Proto(JSONArray chs) {
        try {
            if (chs == null || chs.length() == 0) return "";
            JSONObject c0 = chs.optJSONObject(0);
            return c0 == null ? "" : c0.optString("protocol");
        } catch (Throwable e) {
            return "";
        }
    }

    /** r268：站点自建 Cap（cap.js）验证的纯 HTTP 求解，返回可直接用的 Cookie 串。沙箱已跑通。 */
    private String solveCapCookie(String base) {
        Map<String, String> jar = new LinkedHashMap<>();
        try {
            // r377：**先 POST challenge 探协议，不先 GET base** —— 沙箱实测（2026-11-07）：
            //   ① 冷启动直接 POST /event/cap/challenge 能拿到 protocols，不依赖前置 GET 的 cookie；
            //   ② POST 不占「同一 URL 每 45 秒 3 次 GET」的配额，而 GET base 会占。
            // 所以对 hashwx 站（纯 HTTP 无解）在这里就返回，一个 GET 都不浪费，全留给 WebView。
            Response r1 = postJson(base + "event/cap/challenge", "{}", jar);
            String chal = "";
            if (r1 != null) {
                if (r1.body() != null) chal = r1.body().string();
                collectCookies(r1, jar);
                r1.close();
            }
            JSONObject o = new JSONObject(chal);
            JSONArray chs = o.optJSONArray("challenges");
            // r377：**协议识别** —— 站点已从 rsw(N,x,t) 换成 hashwx(c,d,n)（WASM 求解，纯 HTTP 无解）。
            // r309 能跑通是因为当时还是 rsw。继续走老路只会白白烧 GET 配额，
            // 导致 WebView 拿到 429、页面上没有验证组件，用户点验证必然失败。
            String proto = c0Proto(chs);
            if (!TextUtils.isEmpty(proto) && !"rsw".equals(proto)) {
                log("cap 协议=" + proto + "（纯 HTTP 无解）→ 不重试，交给 WebView 的 cap.js 原生验证");
                CAP_UNSUPPORTED.add(base);
                return "";
            }
            // 以下只对老 rsw 协议：先 GET base 收 PHPSESSID，再重新 challenge
            okhttp3.Request r0 = new okhttp3.Request.Builder().url(base).header("User-Agent", UA).build();
            try (Response resp = OkHttp.client(15000).newCall(r0).execute()) {
                collectCookies(resp, jar);
            }
            Response r1b = postJson(base + "event/cap/challenge", "{}", jar);
            if (r1b != null) {
                if (r1b.body() != null) chal = r1b.body().string();
                collectCookies(r1b, jar);
                r1b.close();
            }
            o = new JSONObject(chal);
            chs = o.optJSONArray("challenges");
            if (chs == null || chs.length() == 0) return cookieHeader(jar);
            JSONObject c0 = chs.optJSONObject(0);
            Object payload = c0 == null ? null : c0.opt("payload");
            String nHex = "";
            String xHex = "";
            int rounds = 0;
            if (payload instanceof JSONObject) {
                JSONObject p = (JSONObject) payload;
                nHex = p.optString("N");
                xHex = p.optString("x");
                rounds = p.optInt("t");
            } else if (payload != null) {
                String p = String.valueOf(payload);
                nHex = quotedAfter(p, "'N'");
                xHex = quotedAfter(p, "'x'");
                String tt = numberAfter(p, "'t'");
                rounds = TextUtils.isEmpty(tt) ? 0 : Integer.parseInt(tt);
            }
            if (TextUtils.isEmpty(nHex) || rounds <= 0 || rounds > 500000) return cookieHeader(jar);
            long t0 = System.currentTimeMillis();
            java.math.BigInteger n = new java.math.BigInteger(nHex, 16);
            java.math.BigInteger y = new java.math.BigInteger(xHex, 16);
            for (int i = 0; i < rounds; i++) y = y.multiply(y).mod(n);
            JSONObject one = new JSONObject();
            one.put("y", y.toString(16));
            JSONArray sol = new JSONArray();
            sol.put(one);
            JSONObject body = new JSONObject();
            body.put("token", o.optString("token"));
            body.put("solutions", sol);
            Response r2 = postJson(base + "event/cap/redeem", body.toString(), jar);
            String respText = "";
            if (r2 != null) {
                if (r2.body() != null) respText = r2.body().string();
                collectCookies(r2, jar);
                r2.close();
            }
            log("cap 求解 " + rounds + " 轮/" + (System.currentTimeMillis() - t0) + "ms → "
                    + (respText.length() > 80 ? respText.substring(0, 80) : respText) + " cookie=" + jar.keySet());
            return cookieHeader(jar);
        } catch (Throwable e) {
            log("cap 求解异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return "";
        }
    }

    /** 从 {'N': 'abc...'} 这种 Python 风格串里取引号值。 */
    private static String quotedAfter(String p, String key) {
        int k = p.indexOf(key);
        if (k < 0) return "";
        int q1 = p.indexOf('\'', p.indexOf(':', k) + 1);
        if (q1 < 0) return "";
        int q2 = p.indexOf('\'', q1 + 1);
        return q2 > q1 ? p.substring(q1 + 1, q2) : "";
    }

    private static String numberAfter(String p, String key) {
        int k = p.indexOf(key);
        if (k < 0) return "";
        int c = p.indexOf(':', k);
        if (c < 0) return "";
        int end = p.indexOf(',', c);
        return p.substring(c + 1, end > 0 ? end : p.length()).trim();
    }

    private Response postJson(String url, String json, Map<String, String> jar) throws Exception {
        okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.parse("application/json")));
        String ck = cookieHeader(jar);
        if (!TextUtils.isEmpty(ck)) b.header("Cookie", ck);
        return OkHttp.client(20000).newCall(b.build()).execute();
    }

    private static void collectCookies(Response resp, Map<String, String> jar) {
        try {
            for (String s : resp.headers("Set-Cookie")) {
                int i = s.indexOf('=');
                if (i <= 0) continue;
                int j = s.indexOf(';');
                jar.put(s.substring(0, i).trim(), j > i ? s.substring(i + 1, j) : s.substring(i + 1));
            }
        } catch (Throwable ignore) {
        }
    }

    private static String cookieHeader(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String schemeHost(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Matcher m = Pattern.compile("^(https?://[^/]+)").matcher(url);
        return m.find() ? m.group(1) + "/" : "";
    }

    /** r251：是否为媒体直链（只看路径后缀，忽略 query）。 */
    private static boolean isMediaUrl(String u) {
        if (TextUtils.isEmpty(u)) return false;
        String path = u;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        String low = path.toLowerCase(Locale.ROOT);
        return low.endsWith(".m3u8") || low.endsWith(".mp4") || low.endsWith(".flv")
                || low.endsWith(".ts") || low.endsWith(".mkv") || low.endsWith(".m4a") || low.endsWith(".mp3");
    }

    private static boolean isM3u8(String u) {
        if (TextUtils.isEmpty(u)) return false;
        int q = u.indexOf('?');
        String path = q > 0 ? u.substring(0, q) : u;
        return path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    /**
     * r251：把「外层播放器页」拆成真直链。
     *
     * <p>69ck 这类站 iframe 是 {@code player.centercdn.top/player.html?video_url=<URL 编码的 m3u8>}。
     * 旧逻辑因为整串里含 {@code .m3u8} 子串，就把这个 HTML 页当直链交给播放器（parse=0），
     * 播放器打开的是网页 → 必然播不了（真机 r250 日志：采用=player.html?video_url=... 候选=2）。
     */
    private String unwrapPlayerUrl(String u) {
        if (TextUtils.isEmpty(u)) return "";
        String s = unescapeHtml(u).trim();
        if (isMediaUrl(s)) return s;
        Matcher q = Pattern.compile("[?&](?:video_url|play_url|url|v|src|m3u8)=([^&#\"']{6,900})", Pattern.CASE_INSENSITIVE).matcher(s);
        while (q.find()) {
            String val = q.group(1);
            try {
                val = java.net.URLDecoder.decode(val, "UTF-8");
            } catch (Throwable ignore) {
            }
            if (val.startsWith("//")) val = "https:" + val;
            if (isMediaUrl(val)) return val;
        }
        return s;
    }

    @Override
    public void init(Context context, String extend) {
        if (TextUtils.isEmpty(root)) setBase(extend);
    }

    private void setBase(String raw) {
        if (TextUtils.isEmpty(raw)) return;
        String url = raw.trim();
        if (url.startsWith("web://")) url = url.substring(6);
        if (!url.startsWith("http")) url = "https://" + url;
        this.rawBase = url;   // r350：保留完整 base（含 path）——missav 系首页必须 /cn 路径，origin 根路径 500
        int end = url.indexOf('/', url.indexOf("://") + 3);
        String base = end > 0 ? url.substring(0, end) : url;
        this.root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.indexPath = "/index.php"; // r455：换源重置，detect 重新发现
        this.host = root.replaceFirst("^https?://", "");
        this.label = host;
    }

    private String abs(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.trim();
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http")) return u;
        if (u.startsWith("/")) return root + u;
        return root + "/" + u;
    }

    /**
     * r526：3xx 的 Location 按「当前请求地址」解析，而不是全局 root。
     * 跨 host 跳转（hsck 中转网关 → 轮换域名；qiyoudy2 → meizi.yongfan99.com 播放器）
     * 若 Location 是相对路径，旧 abs() 会把它拼回源站域名 → 404 / 拿到盾页。
     * 同源时完全沿用旧逻辑，避免影响 rawBase 带 path 的站（missav /cn）。
     */
    private String abs(String url, String base) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.trim();
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http")) return u;
        if (TextUtils.isEmpty(base)) return abs(u);
        String curHost = schemeHost(base);
        String rootHost = schemeHost(root + "/");
        if (curHost.isEmpty() || curHost.equalsIgnoreCase(rootHost)) return abs(u);
        if (u.startsWith("/")) return curHost + u.substring(1);
        String dir = base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : curHost;
        return dir + u;
    }

    /** r350：首页抓取地址 —— 优先用原始 base（含 path）。missav 系 origin 根路径 500、必须 /cn 路径；
     * 旧源（无 path）等价于 root + "/"，行为不变。 */
    private String homeUrl() {
        return TextUtils.isEmpty(rawBase) ? root + "/" : rawBase;
    }

    /**
     * r531：**网络层统一浏览器兜底**。r530 只在 detect() 借了一次浏览器，但 category/detail/player
     * 的取页全走纯 HTTP —— 站方对子路径仍返回 423B 中转壳，于是「首页能识别、分类全空、点进去断链」。
     * 这里把浏览器能力下沉到 get()：纯 HTTP 拿到中转壳 / JS 盾 / CF 挑战时，让 WebView 跑完
     * 跳转与脚本，回灌落地域名、取回真 DOM、顺手把浏览器 cookie 交给后续纯 HTTP 直出。
     * 同时在 get() 入口把「换基址前缓存的绝对 URL」重映射到落地域名（搜索端点、分类 map 都受益）。
     * 全部按页面形态判定，不认任何域名；同 host 渲染次数与结果都做限流/缓存，避免慢站拖死。
     */
    private static final java.util.Map<String, String> RENDER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Integer> RENDER_COUNT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 把指向「入口域名」的旧 URL 换成浏览器落地域名，保留 path/query。 */
    private String remapLanded(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return url;
        String landed = TRANSIT_LANDED.get(hostOf(url).toLowerCase(Locale.ROOT));
        if (TextUtils.isEmpty(landed)) return url;
        String lh = schemeHost(landed);
        if (lh.isEmpty() || lh.equalsIgnoreCase(schemeHost(url))) return url;
        return url.replaceFirst("(?i)^https?://[^/]+", Matcher.quoteReplacement(lh));
    }

    /** 是否必须借浏览器：有内容链接即真页；只兜本站域名（探测外部候选域名不起 WebView）。 */
    private boolean needsRender(String url, String html) {
        if (TextUtils.isEmpty(html) || html.length() > 60000) return false;
        if (contentLinkCount(html) >= 3) return false;
        if (!hostOf(url).equalsIgnoreCase(hostOf(root))) return false;
        return isCloudflareHost(url) || isJsVerifyPage(html) || looksLikeTransitShell(html);
    }

    /** 借浏览器重取该页：回灌落地域名 + 提取 cookie + 返回渲染后真 DOM。失败一律返回原页（fail-closed）。 */
    private String renderViaBrowser(String url, String html) {
        String h = hostOf(url).toLowerCase(Locale.ROOT);
        Integer prev = RENDER_COUNT.get(h);
        int n = (prev == null ? 0 : prev) + 1;
        RENDER_COUNT.put(h, n);
        if (n > 4) return html;   // 同 host 最多 4 次渲染，防盾站反复拉起 WebView
        try {
            java.util.concurrent.atomic.AtomicReference<String> landing =
                    new java.util.concurrent.atomic.AtomicReference<>("");
            Map<String, String> hd = new LinkedHashMap<>();
            hd.put("User-Agent", UA);
            String ck = JS_COOKIE.get(h);
            if (!TextUtils.isEmpty(ck)) hd.put("Cookie", ck);
            String dom = webFetch(url, hd, 18000, landing);
            String lr = schemeHost(landing.get());
            if (!lr.isEmpty() && !hostOf(lr).equalsIgnoreCase(h)) {
                TRANSIT_LANDED.put(h, lr);
                if (hostOf(url).equalsIgnoreCase(hostOf(root))) setBase(lr);
                log("网络层浏览器落地 " + h + " → " + lr);
            }
            String cookieUrl = lr.isEmpty() ? url : lr;
            try {
                String bck = android.webkit.CookieManager.getInstance().getCookie(cookieUrl);
                if (!TextUtils.isEmpty(bck)) JS_COOKIE.put(hostOf(cookieUrl).toLowerCase(Locale.ROOT), bck);
            } catch (Throwable ignored) {
            }
            if (!TextUtils.isEmpty(dom) && (dom.length() > html.length() * 2 || contentLinkCount(dom) >= 3)) {
                log("浏览器渲染补回正文 " + html.length() + " → " + dom.length() + " 字 " + url);
                RENDER_CACHE.put(url, dom);
                rememberPage(url, dom);
                lastFetchUrl = url;
                return dom;
            }
        } catch (Throwable t) {
            log("浏览器兜底异常 " + t);
        }
        return html;
    }

    /** get() 统一收口：编码内联页还原 + 必要时借浏览器。 */
    private String finishGet(String url, String html) {
        String out = unwrapEncodedPage(html);
        return needsRender(url, out) ? renderViaBrowser(url, out) : out;
    }

    /** GET 抓取：手动跟随 3xx（壳里的 OkHttp 默认关闭自动重定向）。 */
    private String get(String url) {
        if (noRender && (challenged || aborted)) return "";   // muse v31：搜索路径遇验证/已超时即整站放弃，不再发请求
        String cur = remapLanded(abs(url));
        lastFetchCode = 0;
        String cached = RENDER_CACHE.get(cur);
        if (!TextUtils.isEmpty(cached)) { lastFetchUrl = cur; lastFetchCode = 200; return cached; }
        for (int i = 0; i < MAX_REDIRECT; i++) {
            // r284：超时必须回到 15s —— 4kcz 这类站的详情页本身要 8~10 秒，
            // r283 压到 8s 会让请求直接超时 → 页面空 → 判成错误页 → 抽不到播放路由（真机 12:33 实证）。
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(cur)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", root + "/");
            // r356：密码盾 cookie 按当前 host 带（ddys.la pageLogin=ok；重定向跨 host 不带）
            String shCk = SHIELD_COOKIE.get(hostOf(cur).toLowerCase(Locale.ROOT));
            // r469：JS 写 cookie（ccguard 等）也按 host 带 —— qiyoudy2 实证：ccguard 时效极短，
            // passJsCookie 首次重试可能因时序失败，但 cookie 已缓存，后续请求必须带上才能过盾。
            String jsCk = JS_COOKIE.get(hostOf(cur).toLowerCase(Locale.ROOT));
            String ckHeader = TextUtils.isEmpty(shCk) ? jsCk
                    : (TextUtils.isEmpty(jsCk) ? shCk : shCk + "; " + jsCk);
            if (!TextUtils.isEmpty(ckHeader)) rb.header("Cookie", ckHeader);
            try (Response res = OkHttp.noRedirect(15000).newCall(rb.build()).execute()) {
                int code = res.code();
                // r405：403/503 退避重试一次（换 UA + no-cache）；429 不重试 —— r377 实测同 URL
                // 45 秒只给 3 次 GET，多打一次会把 WebView 的额度吃掉，导致验证组件永远加载不出来。
                // muse v31（Liu 10:41：能自动过的照用，过不了的才放弃）：搜索路径 429 = 风控限流，纯 HTTP 过不了，
                // 直接放弃该站（不退避、不重试）；403/503 的现成换 UA 退避重试保留。
                if (noRender && code == 429) {
                    markSearchChallenge(cur, "HTTP 429");
                    return "";
                }
                if ((code == 403 || code == 503) && FORBIDDEN_TRIED.add(cur)) {
                    try {
                        okhttp3.Request.Builder rb2 = new okhttp3.Request.Builder()
                                .url(cur)
                                .header("User-Agent", UA_ALT)
                                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .header("Cache-Control", "no-cache")
                                .header("Referer", root + "/");
                        try (Response res2 = OkHttp.noRedirect(15000).newCall(rb2.build()).execute()) {
                            if (res2.code() == 200 && res2.body() != null) {
                                String out = new String(res2.body().bytes(), "utf-8");
                                if (out.length() > 1500) {
                                    lastFetchUrl = cur;
                                    lastFetchCode = 200;
                                    log("403 退避重试成功 " + cur + " → " + out.length() + " 字");
                                    if (noRender) autoPassed = true;
                                    rememberPage(cur, out);
                                    return finishGet(cur, out);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("403 退避异常 " + t);
                    }
                }
                if (code >= 300 && code < 400) {
                    String loc = res.header("Location");
                    if (TextUtils.isEmpty(loc)) return "";
                    String next = abs(loc, cur);   // r526：跨 host 相对 Location 按当前地址拼
                    // r537：**跨 host 3xx 落地映射** —— 入口域名 301 到真站（wangfei.tv → bfjc.cc 实证）。
                    // 旧代码只 follow 拿正文，却把 root/TRANSIT_LANDED 留在入口域名 → 后续分类/详情
                    // 仍拼到入口域名反复 301，或壳侧 derive 出错。这里在「当前地址 == 配置入口 host」时
                    // 登记「入口 → 落地」全局映射并 setBase，让所有新实例（detail/player/category）统一对齐。
                    // 形态判据（host 变化），不认任何域名；中间 CDN 跳板（host≠入口）不登记，避免污染。
                    String entryHost = hostOf(abs(url)).toLowerCase(Locale.ROOT);
                    String curHost = hostOf(cur).toLowerCase(Locale.ROOT);
                    String nextHost = hostOf(next).toLowerCase(Locale.ROOT);
                    if (entryHost.equals(curHost) && !nextHost.isEmpty() && !nextHost.equals(entryHost)) {
                        TRANSIT_LANDED.put(entryHost, schemeHost(next));
                        if (hostOf(root).equalsIgnoreCase(entryHost)) setBase(next);
                        log("网络层 3xx 落地 " + entryHost + " → " + nextHost);
                    }
                    cur = next;
                    continue;
                }
                ResponseBody body = res.body();
                if (body == null) return "";
                // r321：不能直接用 body.string() —— 它按 OkHttp 默认（UTF-8）解码，
                // 而很多中文小说/听书站（真机飞卢 ts.faloo.com/Category.aspx）是 **GBK**，
                // 响应头不带 charset 时整页解成乱码，中文分类名一个都匹配不到。
                byte[] bytes = body.bytes();
                lastFetchCode = code;   // push v44：搜索候选据此丢弃 4xx/5xx 报错页
                lastFetchUrl = cur;   // r520：记录落地地址（3xx 已跟完）—— 三方播放器按它拼 api.php
                String charset = "utf-8";
                String ct = res.header("Content-Type");
                if (ct != null) {
                    String c = ct.toLowerCase(Locale.ROOT);
                    int ci = c.indexOf("charset=");
                    if (ci >= 0) charset = c.substring(ci + 8).split("[;\\s]")[0];
                }
                try {
                    String head = new String(bytes, 0, Math.min(bytes.length, 2048), "ISO-8859-1").toLowerCase(Locale.ROOT);
                    int mi = head.indexOf("charset=");
                    if (mi >= 0) {
                        String mc = head.substring(mi + 8).replaceAll("[\"'/>\\s;].*$", "");
                        if (mc.startsWith("gb")) charset = "gbk";
                        else if (!mc.isEmpty() && !mc.startsWith("iso")) charset = mc;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    String pbody = deobfuscate(new String(bytes, charset));
                    // r405：JS 写 cookie 验证（554 可用站里占 6.7%）—— 提取 cookie 回写后重抓
                    String before = pbody;
                    pbody = passJsCookie(cur, pbody);
                    // r403：宝塔 WAF（BT-WAF）JS 挑战 —— lu9936.lol 实证，纯 HTTP 一跳可过
                    pbody = passBtwaf(cur, pbody);
                    if (!java.util.Objects.equals(pbody, before)) lastFetchCode = 200;   // push v44：过盾重取的是新页面
                    if (noRender) {
                        // muse v31（Liu 2026-11-23 10:37/10:41）：现成纯 HTTP 能过的（JS 写 cookie、宝塔 WAF）上面已照用；
                        // 过完仍是 Cloudflare / JS 验证空壳 / 安全验证页 = 需真浏览器或人工，搜索路径直接放弃该站，
                        // 不重试、不渲染兜底。Cap 验证不在这里判（由 getCap 的现成求解分支处理）。
                        if (!java.util.Objects.equals(pbody, before) && !isUnsolvableChallenge(pbody)) autoPassed = true;
                        if (searchGiveUp(url, cur, code, pbody)) return "";
                    }
                    // r405：Cloudflare 挑战识别（占 5.8%）—— 纯 HTTP 无解，标记后交 WebView 渲染兜底
                    markCloudflare(cur, pbody);
                    rememberPage(cur, pbody);   // r374：429 风控时 WebView 可用此缓存重放
                    return finishGet(cur, pbody);
                } catch (Throwable e) {
                    String pbody = deobfuscate(new String(bytes, "utf-8"));
                    // muse v31：字符集解析异常走这条回退（EdgeOne 拦截页 meta charset 写法实证），搜索路径同样要判验证/拦截页。
                    if (noRender && searchGiveUp(url, cur, code, pbody)) return "";
                    rememberPage(cur, pbody);
                    return finishGet(cur, pbody);
                }
            } catch (Throwable e) {
                return "";
            }
        }
        return "";
    }

    /**
     * r469：**atob+decodeURIComponent 内联页解码**（框架级通用能力）。
     * fljtu20 实证：整个页面被编码内联在 script 里 ——
     *   &lt;script&gt;window.addEventListener("load",function(){document.write(decodeURIComponent(atob("&lt;base64&gt;")))})&lt;/script&gt;
     * → 纯 HTTP 只见几十万字节空壳（href=0）→ 框架被迫走 WebView 渲染（每次 4s；翻页累计 12s），
     *   且详情页拿 0 张图 → 判不出图集 → 走视频链路 → 跳网页。
     * 本方法把「编码内联页」还原成真实 HTML，纯 HTTP 即可拿到分类/列表/详情（免渲染）。
     * 只认「长度 ≥200 的 base64 + 解出含 href/&lt;a 且 >400 字」的强特征，避免误伤普通页面。
     */
    private String unwrapEncodedPage(String html) {
        if (TextUtils.isEmpty(html) || !html.contains("atob(") || !html.contains("decodeURIComponent")) return html;
        Matcher m = Pattern.compile("atob\\(\\s*\"([A-Za-z0-9+/=\\s]{200,})\"\\s*\\)").matcher(html);
        if (!m.find()) return html;
        try {
            String b64 = m.group(1).replaceAll("\\s+", "");
            String dec = java.net.URLDecoder.decode(
                    new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8"), "UTF-8");
            if (!TextUtils.isEmpty(dec) && dec.length() > 400 && (dec.contains("href=") || dec.contains("<a "))) {
                log("内联编码页已解码 " + html.length() + " → " + dec.length() + " 字（免 WebView 渲染）");
                return dec;
            }
        } catch (Throwable e) {
            log("内联编码页解码失败：" + e);
        }
        return html;
    }

    /**
     * r359：document.write 加密站通用解密 —— sm455.vip（3e5bh.sm455.vip）的分类名和卡片标题全是
     * <script>document.write(l("噽丧"))</script>，l() = String.fromCharCode(128^r.charCodeAt(i))。
     * 纯 HTTP 抽取时标题=空 → 整页抽空、分类名乱码丢弃（真机 03:21 日志 classes=1、genericItems 抽空）。
     * 沙箱实证：'噽丧' XOR-128='国产'、'簾邉'='精选'、长串=完整卡片标题。
     * 通用判据：页面含 fromCharCode(N^ 解密特征时，把 document.write(单参短名函数("密文")) 整体替换为明文。
     * 特征不匹配的站原样返回，零影响。同类 XOR document.write 加密站自动适配，不再逐站调试。
     */
    private String deobfuscate(String html) {
        if (html == null || html.length() < 200 || !html.contains("document.write(")) return html;
        try {
            Matcher fm = Pattern.compile("fromCharCode\\s*\\(\\s*(\\d{1,3})\\s*\\^").matcher(html);
            if (!fm.find()) return html;
            int key = Integer.parseInt(fm.group(1));
            if (key <= 0 || key >= 256) return html;
            Matcher m = Pattern.compile("document\\.write\\s*\\(\\s*\\w{1,3}\\s*\\(\\s*[\"']([^\"']{1,1200})[\"']\\s*\\)\\s*\\)").matcher(html);
            StringBuffer sb = new StringBuffer();
            int n = 0;
            while (m.find()) {
                String enc = m.group(1);
                StringBuilder dec = new StringBuilder(enc.length());
                for (int i = 0; i < enc.length(); i++) dec.append((char) (key ^ enc.charAt(i)));
                m.appendReplacement(sb, Matcher.quoteReplacement(dec.toString()));
                n++;
            }
            m.appendTail(sb);
            if (n > 0) log("解密 document.write 加密 " + n + " 处（XOR " + key + "）");
            return sb.toString();
        } catch (Throwable t) {
            return html;
        }
    }

    /**
     * r360：XOR document.write 加密站（sm455.vip 型）的残留特征 —— deobfuscate 解密后调用块
     * 已被替换成明文，但解密函数定义（fromCharCode(N^）会残留在页面里，是「本站是加密站」的可靠信号。
     * 用迹：detail/search 渲染兜底判据 —— 这类站正文靠运行时拉取（沙箱+真实 WebView 实证 sm455
     * 详情页：纯 HTTP 解密后 title/图/播放地址全无，渲染后 title=正在播放:xxx、播放器 22:33 全出来）。
     * 常规站 JS 极少同时出现 fromCharCode(N^ 与 document.write( 组合，误伤面极小。
     * 注意与 deobfuscate 的判据同源（fromCharCode\s*\(\s*\d{1,3}\s*\^），两处必须一起改（r317 教训）。
     */
    private static boolean hasXorShellTrace(String html) {
        return html != null && html.length() >= 200 && html.contains("document.write(")
                && Pattern.compile("fromCharCode\\s*\\(\\s*\\d{1,3}\\s*\\^").matcher(html).find();
    }

    /**
     * r356：密码盾自愈 —— ddys.la 这类站首页是「请输入密码访问」盾页（5,775 字），
     * 页面 JS 是 setCookie("pageLogin","ok",…) + 自动提交。纯 HTTP 无法执行 JS，
     * 但带上提取出的 cookie 重抓一次就能过盾（沙箱 verify 32/32 实证：盾页 5,775 → 45,210 字）。
     * 判据从严：页面 <12000 字 + 含「请输入密码」+ 能提取 setCookie 名值对；
     * 重抓后页面 >3 倍才算成功，否则回滚 cookie（防止正常小站误判污染全局）。
     */

    /**
     * r403：**宝塔 WAF（BT-WAF）JS 挑战** —— lu9936.lol 实证（A 级真实请求）。
     *
     * <p>直接 GET 首页只回 131 字节：`<title>检测中</title>` +
     * `window.location.href="/?btwaf=<动态token>"`。浏览器执行 JS 后带 token 再请求，
     * 服务器才下发真实页面（实测 86,337 字，maccms 站）。</p>
     *
     * <p>纯 HTTP 一跳就能过，不需要 WebView：取 token → 请求 `/?btwaf=token` → 用返回页替换。
     * 它与「密码盾」passShield、「safeline 挑战」并列，是第三种拦截形态，统一在 get() 里处理，
     * 所有模板（maccms/maccmsphp/generic）自动受益。</p>
     */
    private String passBtwaf(String url, String html) {
        if (TextUtils.isEmpty(html) || html.length() > 1500) return html;
        if (!html.contains("btwaf=")) return html;
        Matcher m = Pattern.compile("btwaf=([A-Za-z0-9]+)").matcher(html);
        if (!m.find()) return html;
        String token = m.group(1);
        try {
            String u = (root == null ? "" : root) + "/?btwaf=" + token;
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(u)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", abs(url));
            try (Response res = OkHttp.noRedirect(15000).newCall(rb.build()).execute()) {
                if (res.body() == null) return html;
                String out = new String(res.body().bytes(), "utf-8");
                if (out.length() > 1500) {
                    log("BT-WAF 已通过 token=" + token + " → " + out.length() + " 字");
                    return out;
                }
                log("BT-WAF 未通过 token=" + token + " 返回 " + out.length() + " 字");
            }
        } catch (Throwable t) {
            log("BT-WAF 异常 " + t);
        }
        return html;
    }

    // ===================== r405：风控自动链路（JS-cookie / Cloudflare / 403 退避） =====================

    /** 全站 cookie（按 host）；与密码盾 SHIELD_COOKIE 分开，避免互相覆盖。 */
    private static final java.util.Map<String, String> JS_COOKIE = new java.util.concurrent.ConcurrentHashMap<>();
    /** 已尝试过 JS-cookie 重抓的 URL，防死循环。 */
    private static final java.util.Set<String> JS_COOKIE_TRIED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 403/503 退避重试过的 URL（只重试一次）。 */
    private static final java.util.Set<String> FORBIDDEN_TRIED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 退避用的备选 UA。 */
    private static final String UA_ALT = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";
    /** 已识别为 Cloudflare 挑战的 host（供渲染兜底决策与日志去重）。 */
    private static final java.util.Set<String> CF_HOSTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * r405：**JS 写 cookie 验证穿透**。
     *
     * <p>实测 554 个可用站里 37 个（6.7%）属于这种：首屏是极短的跳转页，靠
     * `document.cookie = "k=v"` 或 `setCookie("k","v")` 写入通行 cookie 再 reload。
     * 这类**纯 HTTP 可过**：把 key=value 抠出来、按 host 存下、带 cookie 重抓一次即可。</p>
     *
     * <p>收紧避免误判：只认「赋值」形态（`document.cookie =` 后面带引号内容）或 setCookie 调用，
     * 且页面 < 30000 字；同一 URL 只重试一次。</p>
     */
    private String passJsCookie(String url, String html) {
        if (TextUtils.isEmpty(html) || html.length() > 30000) return html;
        if (!html.contains("document.cookie") && !html.toLowerCase().contains("setcookie")) return html;
        String host = hostOf(url).toLowerCase(Locale.ROOT);
        java.util.List<String> pairs = new java.util.ArrayList<>();
        // document.cookie = "k=v"  —— 值里可能混入 cookie 属性（ccguard=xxx; Max-Age=1800; Path=/; SameSite=Lax），
        // 必须剥掉属性只留 name=value，否则作为 Cookie header 回送时服务器把属性当额外键值对、验证失败（qiyoudy2 实证）。
        Matcher m1 = Pattern.compile("document\\.cookie\\s*=\\s*[\"']([^\"']{3,200}?)[\"']").matcher(html);
        while (m1.find()) pairs.add(stripCookieAttrs(m1.group(1)));
        // setCookie("k","v") / setCookie('k','v')
        Matcher m2 = Pattern.compile("setCookie\\(\\s*[\"']([^\"']{1,60})[\"']\\s*,\\s*[\"']([^\"']{0,120})[\"']").matcher(html);
        while (m2.find()) pairs.add(m2.group(1) + "=" + m2.group(2));
        if (pairs.isEmpty()) return html;
        String ck = TextUtils.join("; ", pairs);
        JS_COOKIE.put(host, ck);
        log("JS-cookie 提取 host=" + host + " " + ck.length() + " 字");
        if (!JS_COOKIE_TRIED.add(host + "|" + url)) return html;
        try {
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(abs(url))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", root + "/")
                    .header("Cookie", ck);
            try (Response res = OkHttp.noRedirect(15000).newCall(rb.build()).execute()) {
                if (res.body() == null) return html;
                String out = new String(res.body().bytes(), "utf-8");
                if (out.length() > html.length() * 2 && out.length() > 2000) {
                    log("JS-cookie 已通过 host=" + host + " → " + out.length() + " 字");
                    return out;
                }
                log("JS-cookie 未通过 host=" + host + " 返回 " + out.length() + " 字");
            }
        } catch (Throwable t) {
            log("JS-cookie 异常 " + t);
        }
        return html;
    }

    /** 剥离 document.cookie 赋值串里的 cookie 属性（Max-Age/Path/SameSite/Expires/Domain/Secure/HttpOnly），
     *  只保留 name=value。ccguard 这类验证 cookie 的值后面紧跟属性，直接回送会验证失败。 */
    private static String stripCookieAttrs(String raw) {
        if (TextUtils.isEmpty(raw)) return raw;
        String s = raw.trim();
        // 以 ; 分段，只保留含 = 且属性名不是已知 cookie 属性的第一段
        String[] parts = s.split(";");
        StringBuilder keep = new StringBuilder();
        for (String p : parts) {
            String seg = p.trim();
            if (seg.isEmpty()) continue;
            int eq = seg.indexOf('=');
            String key = (eq > 0 ? seg.substring(0, eq) : seg).trim().toLowerCase(Locale.ROOT);
            if (key.equals("max-age") || key.equals("path") || key.equals("samesite") || key.equals("expires")
                    || key.equals("domain") || key.equals("secure") || key.equals("httponly")) continue;
            if (keep.length() > 0) keep.append("; ");
            keep.append(seg);
        }
        return keep.toString();
    }

    /**
     * r405：**Cloudflare 挑战识别**（554 可用站里 32 个，5.8%）。
     *
     * <p>`cf_clearance` 必须靠浏览器执行 JS 拿，纯 HTTP 无解；这里只做识别与标记，
     * 由既有的「分类渲染兜底」走 WebView 真跑页面。标记同时用于日志去重，避免每次请求都刷。</p>
     */
    private void markCloudflare(String url, String html) {
        if (TextUtils.isEmpty(html) || html.length() > 60000) return;
        String low = html.toLowerCase(Locale.ROOT);
        boolean cf = low.contains("_cf_chl_opt") || low.contains("challenge-platform")
                || low.contains("just a moment") || low.contains("cf-chl");
        if (!cf) return;
        String host = hostOf(url).toLowerCase(Locale.ROOT);
        if (CF_HOSTS.add(host)) log("Cloudflare 挑战 host=" + host + "（交 WebView 渲染兜底）");
    }

    /** 该 host 是否被识别为 Cloudflare 挑战（渲染兜底决策用）。 */
    private static boolean isCloudflareHost(String url) {
        return CF_HOSTS.contains(hostOfStatic(url).toLowerCase(Locale.ROOT));
    }

    private static String hostOfStatic(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getHost() == null ? "" : u.getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * r408：**JS 验证页识别** —— lvse25 全量探测实证：一批 200 且 >8KB 的站其实是
     * `<title>正在验证</title>` 的 16KB 空壳（0 链接 0 图），必须先执行 JS 才出内容。
     * 判据：title 含 正在验证/Just a moment/请稍候/检测中，且 链接 <3、图片 <2。
     */
    private static boolean isJsVerifyPage(String html) {
        if (TextUtils.isEmpty(html) || html.length() > 40000) return false;
        String title = firstMatch(html, "<title[^>]*>\\s*([^<]{0,60})</title>");
        if (title == null) return false;
        String t = title.toLowerCase(Locale.ROOT);
        boolean named = t.contains("正在验证") || t.contains("just a moment") || t.contains("请稍候")
                || t.contains("检测中") || t.contains("安全验证") || t.contains("verifying");
        if (!named) return false;
        int links = countOccurrences(html, "href=\"");
        int imgs = countOccurrences(html, "<img");
        return links < 3 && imgs < 2;
    }

    /**
     * r408：**推广/工具页识别** —— 实测这批"站"里 11% 根本不是影视站：
     * 全是 登录/注册/博客/关于我们/防骗指南/APP下载 的落地页。
     * 判据（三条同时满足，防误杀）：认证类词 ≥3、链接 <25、无卡片特征。
     */

    /**
     * r410：**空列表容器识别** —— 列表容器存在但内部 0 条目。
     *
     * <p>lu9936 分类 36 实证：`<ul class="stui-vodlist clearfix"> </ul>` 直接闭合，
     * 第 1 页/第 2 页/换排序全是 0 条 —— 是站方该分类没有数据，不是抽取失败。
     * 识别出来直接空态，避免渲染兜底白等（用户感知就是"卡住且没内容"）。</p>
     *
     * <p>判据从严，只认「已知列表容器 + 内部紧接闭合或无 href」：
     * 正常有内容的分类容器内必有 `<a href>`，不会命中。</p>
     */
    private static boolean isEmptyListPage(String html) {
        if (TextUtils.isEmpty(html) || html.length() < 2000) return false;
        Matcher m = Pattern.compile(
                "(?:stui-vodlist|vodlist|module-list|module-items|list-box|card-list|video-list|public-list-box|m-list)[^>]*>([\\s\\S]{0,600})",
                Pattern.CASE_INSENSITIVE).matcher(html);
        int checked = 0;
        while (m.find() && checked++ < 6) {
            String inner = m.group(1);
            // 紧接闭合 → 空
            if (inner.matches("(?s)^\\s*</[a-zA-Z]+>.*")) return true;
            // 600 字符内没有任何链接 → 也视为空
            if (!inner.contains("href=\"")) return true;
        }
        return false;
    }
    private static boolean isPromoPage(String html) {
        if (TextUtils.isEmpty(html) || html.length() < 3000) return false;
        String low = html.toLowerCase(Locale.ROOT);
        int authWords = 0;
        for (String k : new String[]{"login", "register", "about-us", "privacy", "contact-us",
                "登录", "注册", "关于我们", "联系我们", "防骗", "服务条款", "隐私政策"}) {
            if (low.contains(k)) authWords++;
        }
        if (authWords < 3) return false;
        int links = countOccurrences(html, "href=\"");
        if (links >= 25) return false;
        boolean cardish = Pattern.compile("class=\"[^\"]*(?:item|card|movie|video|post|thumb|cover)[^\"]*\"",
                Pattern.CASE_INSENSITIVE).matcher(html).find();
        return !cardish;
    }

    /** 子串出现次数（只需近似值，封顶 1000 防长页拖慢）。 */
    private static int countOccurrences(String html, String needle) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(needle)) return 0;
        int c = 0, i = 0;
        while ((i = html.indexOf(needle, i)) >= 0) {
            if (++c >= 1000) break;
            i += needle.length();
        }
        return c;
    }

    // ===================== r413：静态数据 SPA（data.js 型） =====================

    private JSONArray jsdataArr;

    /** 判定：短页面 + 声明了数据脚本或内联数据变量。 */
    private static boolean isJsDataSpa(String html) {
        if (TextUtils.isEmpty(html) || html.length() > 15000) return false;
        if (html.contains("window.REELS") || html.contains("window.DATA") || html.contains("window.Videos")) return true;
        return Pattern.compile("(?i)<script[^>]+src=\"[^\"]*(?:data|reels|list|videos?)\\.js").matcher(html).find()
                && html.length() < 12000;
    }

    private void jsdataLoad() {
        if (jsdataArr != null) return;
        jsdataArr = new JSONArray();
        try {
            String src = firstMatch(homeHtml, "(?i)<script[^>]+src=\"([^\"]*(?:data|reels|list|videos?)\\.js)[^\"]*\"");
            if (TextUtils.isEmpty(src)) src = "data.js";
            String js = get(abs(src));
            if (TextUtils.isEmpty(js)) {
                log("jsdata 数据脚本为空 " + src);
                return;
            }
            Matcher m = Pattern.compile("window\\.[A-Za-z_$][\\w$]*\\s*=\\s*(\\[[\\s\\S]*?\\])\\s*;").matcher(js);
            if (!m.find()) {
                log("jsdata 未找到 window.XXX=[] 结构（" + js.length() + " 字）");
                return;
            }
            jsdataArr = new JSONArray(m.group(1));
            log("jsdata 加载 " + jsdataArr.length() + " 条 ← " + src);
        } catch (Throwable t) {
            log("jsdata 异常 " + t);
        }
    }

    /** 分类 = 条目 g 标签聚合（出现次数 Top 24）。 */
    private LinkedHashMap<String, String> jsdataClasses() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        jsdataLoad();
        Map<String, Integer> cnt = new LinkedHashMap<>();
        for (int i = 0; i < jsdataArr.length(); i++) {
            JSONObject o = jsdataArr.optJSONObject(i);
            if (o == null) continue;
            JSONArray g = o.optJSONArray("g");
            if (g == null) continue;
            for (int j = 0; j < g.length(); j++) {
                String t = g.optString(j, "").trim();
                if (t.isEmpty() || t.length() > 12) continue;
                cnt.merge(t, 1, Integer::sum);
            }
        }
        cnt.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(24)
                .forEach(e -> map.put(e.getKey(), e.getKey()));
        if (map.isEmpty()) map.put("all", "全部");
        return map;
    }

    private String jsdataCategory(String tid, int page) {
        jsdataLoad();
        int limit = 30;
        int skip = Math.max(0, (Math.max(1, page) - 1) * limit);
        JSONArray list = new JSONArray();
        int idx = 0, taken = 0;
        for (int i = 0; i < jsdataArr.length(); i++) {
            JSONObject o = jsdataArr.optJSONObject(i);
            if (o == null) continue;
            boolean hit = TextUtils.isEmpty(tid) || "all".equals(tid);
            if (!hit) {
                JSONArray g = o.optJSONArray("g");
                if (g != null) for (int j = 0; j < g.length(); j++) if (tid.equals(g.optString(j))) { hit = true; break; }
            }
            if (!hit) continue;
            if (idx++ < skip) continue;
            if (taken++ >= limit) break;
            list.put(jsdataVod(o));
        }
        JSONObject r = new JSONObject();
        try {
            r.put("page", page);
            r.put("pagecount", page + 1);
            r.put("limit", limit);
            r.put("total", jsdataArr.length());
            r.put("list", list);
        } catch (Throwable ignored) {
        }
        return r.toString();
    }

    private JSONObject jsdataVod(JSONObject o) {
        JSONObject v = new JSONObject();
        try {
            v.put("vod_id", o.optString("id"));
            v.put("vod_name", o.optString("t"));
            v.put("vod_pic", o.optString("p"));
            int d = o.optInt("d", 0);
            v.put("vod_remarks", d > 0 ? (d / 60) + "分钟" : "");
        } catch (Throwable ignored) {
        }
        return v;
    }

    private String jsdataDetail(String id) {
        jsdataLoad();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < jsdataArr.length(); i++) {
            JSONObject o = jsdataArr.optJSONObject(i);
            if (o == null || !id.equals(o.optString("id"))) continue;
            JSONObject v = jsdataVod(o);
            try {
                v.put("vod_play_from", "直链");
                v.put("vod_play_url", "正片$" + id);
            } catch (Throwable ignored) {
            }
            arr.put(v);
            break;
        }
        JSONObject r = new JSONObject();
        try {
            r.put("list", arr);
        } catch (Throwable ignored) {
        }
        return r.toString();
    }

    private String jsdataPlayer(String id) {
        jsdataLoad();
        for (int i = 0; i < jsdataArr.length(); i++) {
            JSONObject o = jsdataArr.optJSONObject(i);
            if (o == null || !id.equals(o.optString("id"))) continue;
            String u = o.optString("u", "");
            if (TextUtils.isEmpty(u)) return "";
            JSONObject r = new JSONObject();
            try {
                r.put("parse", 0);
                r.put("url", u);
            } catch (Throwable ignored) {
            }
            log("jsdata 直链 " + u);
            return r.toString();
        }
        return "";
    }

    /**
     * r414：**huangguo 播放源直解**（aisee9w11.buzz 实证，A 级）。
     *
     * <p>该站播放页是 `var player_data={...,"url":"11602|1","from":"huangguo"}`，
     * `playerconfig.js` 里 `huangguo:{ps:"0",parse:""}` —— 不吃第三方解析，`url` 是 **vid|ep** 编号。
     * 纯 HTTP 三步可解（实测 4 个不同 vid 全部 code:1）：</p>
     *
     * <pre>
     * GET /huangguo.php?ac=play&id={vid}&ep={ep}   （带播放页 Referer）
     *   → {"code":1,"url":"/hgmedia.php?e=&lt;base64 的 m3u8&gt;"}
     * → 用同源 /hgmedia.php?e=… 交给播放器（清单里 key/ts 都被改写成同源，无跨域/鉴权问题）
     * </pre>
     */
    private String huangguoPlay(String page, String html) {
        try {
            if (TextUtils.isEmpty(html)) return "";
            String from = firstMatch(html, "var player_(?:aaaa|data|player_config)\\s*=\\s*\\{[^}]*\"from\"\\s*:\\s*\"([^\"]{1,30})\"");
            if (!"huangguo".equals(from)) return "";
            String raw = firstMatch(html, "var player_(?:aaaa|data|player_config)\\s*=\\s*\\{[^}]*\"url\"\\s*:\\s*\"([^\"]{1,60})\"");
            if (raw == null || !raw.matches("\\d+\\|\\d+")) return "";
            String[] parts = raw.split("\\|");
            String api = root + "/huangguo.php?ac=play&id=" + parts[0] + "&ep=" + parts[1];
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(api)
                    .header("User-Agent", UA)
                    .header("Referer", page)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01");
            try (Response res = OkHttp.noRedirect(12000).newCall(rb.build()).execute()) {
                if (res.body() == null) return "";
                String js = new String(res.body().bytes(), "utf-8");
                String u = firstMatch(js, "\"url\"\\s*:\\s*\"([^\"]{1,500})\"");
                if (TextUtils.isEmpty(u)) {
                    log("huangguo 无 url 返回：" + js.substring(0, Math.min(120, js.length())));
                    return "";
                }
                if (u.startsWith("/")) u = root + u;
                log("huangguo 直解 " + parts[0] + "|" + parts[1] + " → " + u);
                return u;
            }
        } catch (Throwable t) {
            log("huangguo 异常 " + t);
            return "";
        }
    }
    private String passShield(String html) {
        try {
            if (html == null || html.length() > 12000) return html;
            if (!html.contains("请输入密码") && !html.contains("需要密码")) return html;
            Matcher m = Pattern.compile("setCookie\\s*\\(\\s*[\"']([\\w-]+)[\"']\\s*,\\s*[\"']([\\w-]+)[\"']").matcher(html);
            if (!m.find()) return html;
            String cookie = m.group(1) + "=" + m.group(2);
            String host = hostOf(root).toLowerCase(Locale.ROOT);
            if (TextUtils.isEmpty(host)) return html;
            SHIELD_COOKIE.put(host, cookie);
            String again = get(homeUrl());
            if (again != null && again.length() > html.length() * 3) {
                log("密码盾已过 cookie=" + cookie + " " + html.length() + "→" + again.length() + " 字");
                return again;
            }
            SHIELD_COOKIE.remove(host);
            return html;
        } catch (Throwable t) {
            return html;
        }
    }

    // ------------------------------------------------------------------ 发布页自愈

    /**
     * r500：**地址发布页 → 真实站点自愈**。
     *
     * <p>用户加网页源时填的常常是「地址发布页」（麦田 mtyy.tv、黄果 huangguo88.pages.dev），
     * 那一页只有一排备用域名、没有任何影片链接，旧逻辑抽 0 条 → 用户看到「暂无内容」。
     * 这里做通用的三步：①判定像不像发布页；②从 href / 裸域名 / JS 数组里收候选域名；
     * ③逐个探活（短超时），第一条真有内容的域名换上重抓首页。</p>
     *
     * <p>判据刻意保守：页面内容链接 ≥5 一律不当发布页，避免把正常站点误换域名。</p>
     */
    private static final Map<String, String> PUBLISH_RESOLVED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * r530：多层中转壳的「入口 host → 浏览器落地域名」映射。
     * detect() 是实例字段，但壳对 detail/player 常换实例，必须静态缓存才能让所有实例对齐同一基址。
     */
    private static final Map<String, String> TRANSIT_LANDED = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String[] PUBLISH_WORDS = {"地址发布", "发布页", "最新地址", "永久地址", "收藏本站",
            "防走失", "备用网址", "回家不迷路", "最新网址", "备用域名", "请牢记"};

    private static final Set<String> PUB_IGNORE_HOST = new HashSet<>();

    static {
        PUB_IGNORE_HOST.add("t.me");
        // muse v31：Cloudflare 自家域名（站点 520/错误页里的 cloudflare.com 链接）不是发布页候选——
        // 沙箱实测 gaze.red 520 时被「发布页自愈」换到 developers.cloudflare.com，搜出文档链接。
        PUB_IGNORE_HOST.add("cloudflare.com");
        PUB_IGNORE_HOST.add("www.cloudflare.com");
        PUB_IGNORE_HOST.add("developers.cloudflare.com");
        PUB_IGNORE_HOST.add("static.cloudflareinsights.com");
        PUB_IGNORE_HOST.add("telegram.org");
        PUB_IGNORE_HOST.add("github.com");
        PUB_IGNORE_HOST.add("google.com");
        PUB_IGNORE_HOST.add("www.google.com");
        PUB_IGNORE_HOST.add("gstatic.com");
        PUB_IGNORE_HOST.add("jsdelivr.net");
        PUB_IGNORE_HOST.add("bootcdn.net");
        PUB_IGNORE_HOST.add("jquery.com");
        PUB_IGNORE_HOST.add("w3.org");
        PUB_IGNORE_HOST.add("baidu.com");
        PUB_IGNORE_HOST.add("qq.com");
        PUB_IGNORE_HOST.add("lanzou.com");
        PUB_IGNORE_HOST.add("lanzout.com");
        PUB_IGNORE_HOST.add("lanzoui.com");
        PUB_IGNORE_HOST.add("lanzoux.com");
    }

    private static final Pattern PUB_HOST = Pattern.compile(
            "(?i)\\b((?:[a-z0-9](?:[a-z0-9-]{0,30})?\\.)+(?:cc|com|net|org|tv|io|me|la|co|top|xyz|vip|club|site|online|info|pro|app|dev|cn|us|shop|art|fun|live|link|one|space|pw|ws|in|tk|ml|ga|cf|su|st|to|li|fm|am|sh|ac|gg|so|best|fit|work|life|world|website|store|tech|press|host|cloud|page))\\b");

    private boolean containsAnyWord(String s, String[] words) {
        if (TextUtils.isEmpty(s)) return false;
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** 页面里「内容链接」的数量 —— 用来区分真站点与发布页/停放页。 */
    private int contentLinkCount(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        Matcher m = Pattern.compile(
                "(?i)(/voddetail/|/vodplay/|/vodshow/|/archives/\\d|/detail[-/]|/video/\\d|/post/\\d|"
                        + "/v5/\\d+-\\d+-\\d+|/play/\\d+-\\d+-\\d+|/view/\\d+\\.html|/list/\\d+\\.html|"
                        + "/movie/\\d+|/vod/\\d+|/show/\\d+|/browse/\\d+"
                        // r538：查询参数式详情链接（hohoj.tv /video?id=12564）—— 自研站非 macCMS
                        + "|[?&]id=\\d+)").matcher(html);
        int n = 0;
        while (m.find() && n < 500) n++;
        return n;
    }

    private boolean looksLikePublishPage(String html) {
        if (TextUtils.isEmpty(html) || html.length() < 400) return false;
        if (contentLinkCount(html) >= 5) return false;
        String title = clean(firstMatch(html, "(?is)<title[^>]*>([^<]{0,150})</title>"));
        if (containsAnyWord(title, PUBLISH_WORDS)) return true;
        // r527：正常站点首页/列表页链接很多，且常带 CDN、统计、友情链接等外链；
        // publishCandidates 会把它们收成十几个「候选域名」→ 误判成发布页，白试一轮（真机 qiyoudy2
        // 95 个 href → 18 个候选 → 首屏多花 5 秒）。title 不含发布词时，链接数 >= 30 一律按正常站点处理；
        // 真发布页（麦田 13 链接 / 黄果 763B）远低于这个量级，不受影响。
        if (countHref(html) >= 30) return false;
        return publishCandidates(html).size() >= 3;
    }

    /** 从发布页收候选域名：JS 数组（子域名×域名）、绝对外链、裸域名。 */
    private LinkedHashSet<String> publishCandidates(String html) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String curHost = hostOf(root).toLowerCase(Locale.ROOT);
        List<String> subs = jsStringArray(html, "subdomains");
        List<String> bases = jsStringArray(html, "urls");
        if (!subs.isEmpty() && !bases.isEmpty()) {
            for (String b : bases) {
                String bb = b.trim().replaceFirst("^https?://", "");
                if (bb.isEmpty() || !bb.contains(".")) continue;
                for (int i = 0; i < subs.size() && i < 4; i++) {
                    addPublishCandidate(out, "https://" + subs.get(i).trim() + "." + bb, curHost);
                }
            }
        }
        Matcher h = Pattern.compile("(?i)(?:href|src|data-url)=\"(https?://[^\"'\\s>]{6,140})\"").matcher(html);
        int guard = 0;
        while (h.find() && guard++ < 200) addPublishCandidate(out, h.group(1), curHost);
        Matcher d = PUB_HOST.matcher(html);
        guard = 0;
        while (d.find() && guard++ < 200) addPublishCandidate(out, "https://" + d.group(1), curHost);
        return out;
    }

    private void addPublishCandidate(LinkedHashSet<String> out, String url, String curHost) {
        if (TextUtils.isEmpty(url)) return;
        String cand = url.trim();
        if (!cand.startsWith("http")) cand = "https://" + cand;
        String h = hostOf(cand).toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(h) || !h.contains(".")) return;
        if (h.equals(curHost) || PUB_IGNORE_HOST.contains(h)) return;
        int end = cand.indexOf('/', cand.indexOf("://") + 3);
        if (end > 0) cand = cand.substring(0, end);
        out.add(cand);
    }

    private List<String> jsStringArray(String html, String name) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return out;
        Matcher m = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*\\[([^\\]]{0,6000})\\]").matcher(html);
        if (!m.find()) return out;
        Matcher q = Pattern.compile("[\"']([^\"']{1,120})[\"']").matcher(m.group(1));
        while (q.find()) out.add(q.group(1));
        return out;
    }

    /** 站点「品牌词」：mtyy.tv / www.mtyy1.cc 都取 mtyy，用于候选域名排序。 */
    private String brandOf(String host) {
        if (TextUtils.isEmpty(host)) return "";
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        int dot = h.indexOf('.');
        String first = dot > 0 ? h.substring(0, dot) : h;
        String letters = first.replaceAll("[^a-z]", "");
        return letters.length() >= 3 ? letters : "";
    }

    /** 探活单个候选域名：10 秒超时、**跟随跳转**（mtyy1.cc 会 301 到 mtyy2.cc，不跟随永远探空）。 */
    private String probeSite(String url) {
        try {
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9");
            try (Response res = OkHttp.client(true, 10000).newCall(rb.build()).execute()) {
                if (res.body() == null) return "";
                byte[] bytes = res.body().bytes();
                if (bytes == null || bytes.length < 1500) return "";
                return new String(bytes, "utf-8");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * r530：多层中转壳形态判据 —— 短页面、几乎没有内容链接、且带「网关?u=」式 JS 拼接跳转。
     * 只看形态，不认任何域名；判据保守，正常站点（内容链接 >= 3）不会触发。
     */
    private boolean looksLikeTransitShell(String html) {
        if (TextUtils.isEmpty(html) || html.length() > 4000) return false;
        if (contentLinkCount(html) >= 3) return false;
        if (!TextUtils.isEmpty(jumpShellTarget(html, homeUrl()))) return true;
        String low = html.toLowerCase(Locale.ROOT);
        boolean jsJump = low.contains("window.location") || low.contains("location.href")
                || low.contains("location.replace") || (low.contains("http-equiv") && low.contains("refresh"));
        if (jsJump && (low.contains("?u=") || low.contains("&p=") || low.contains("?url="))) return true;
        // r542：**站内路径型 JS 跳转壳** —— 91吃瓜 搜索页服务端直连只回 106 字节：
        //   <script>location.replace(location.protocol+'//'+location.host+'/search/黑料/')</script>
        // 旧判据只认带 ?u=/&p=/?url= 的「发布页中转壳」，这种同源自跳转（无任何中转参数、
        // 目标是站内绝对路径）不认 → 不触发浏览器渲染 → 106B 空壳抽 0 条 =「搜索不支持」。
        // 判据：极短（<600B）+ 有 location 跳转 + 几乎无正文文本 → 必是跳转壳，渲染才有内容。
        // 只在「跳转目标写死在页面里」时认（location.replace('...路径...')），动态的 location.host
        // 拼接同样由浏览器执行，交给 WebFetch 落地即可；用 contentLinkCount<2 排除正常落地页。
        if (low.length() < 600 && jsJump && contentLinkCount(html) < 2
                && (low.contains("location.replace") || low.contains("location.href") || low.contains("window.location"))
                && !low.contains("<input") && !low.contains("<article")) return true;
        return false;
    }

    /**
     * r530：浏览器落地换基址 —— 让 WebView 从入口 URL 跑完所有 JS 跳转 / 3xx / 盾，
     * 取最终落地 URL 回灌 root，再用新域名重抓首页。
     * 这是「多层中转 + 域名轮换」这一整类站的通用解，不是单站适配：
     * 换对基址后，分类页、详情页、播放页、手势接口、Referer、header 全部自动对齐。
     * 失败一律返回空串且不改状态（fail-closed）。
     */
    private String healByBrowserLanding(String html) {
        try {
            String entry = homeUrl();
            String oldHost = hostOf(root).toLowerCase(Locale.ROOT);
            java.util.concurrent.atomic.AtomicReference<String> landing =
                    new java.util.concurrent.atomic.AtomicReference<>("");
            Map<String, String> hd = new LinkedHashMap<>();
            String rendered = webFetch(entry, hd, 20000, landing);
            String newRoot = schemeHost(landing.get());
            if (TextUtils.isEmpty(newRoot)) return "";
            String newHost = hostOf(newRoot).toLowerCase(Locale.ROOT);
            if (newHost.isEmpty() || newHost.equalsIgnoreCase(oldHost)) return "";
            // 落地域名必须有真内容才认 —— 防止把网关/错误页当成新域名误换基址
            String body = get(newRoot);
            if (TextUtils.isEmpty(body) || contentLinkCount(body) < 3) {
                if (TextUtils.isEmpty(rendered) || contentLinkCount(rendered) < 3) {
                    log("落地域名无内容，不换基址 " + newHost);
                    return "";
                }
                body = rendered;
            }
            TRANSIT_LANDED.put(oldHost, newRoot);
            setBase(newRoot);
            log("浏览器落地换基址 " + oldHost + " → " + newRoot + "（" + body.length() + " 字）");
            return body;
        } catch (Throwable t) {
            log("浏览器落地异常 " + t);
            return "";
        }
    }

    private String healFromPublishPage(String html) {
        try {
            String curHost = hostOf(root).toLowerCase(Locale.ROOT);
            String cached = PUBLISH_RESOLVED.get(curHost);
            if (!TextUtils.isEmpty(cached)) return cached;
            LinkedHashSet<String> cands = publishCandidates(html);
            // 黄果短剧这类发布页把 urls[]/subdomains[] 放在外部 publish.js 里，页面本身没有域名 ——
            // 候选为空时把同源脚本抓下来一起扫（最多 3 个），这是「发布页 + 脚本生成域名」的通用形态。
            if (cands.isEmpty()) {
                StringBuilder sb = new StringBuilder(html);
                Matcher sm = Pattern.compile("(?i)<script[^>]+src=[\"']([^\"']{1,200})[\"']").matcher(html);
                int got = 0;
                while (sm.find() && got < 3) {
                    String src = abs(sm.group(1));
                    if (TextUtils.isEmpty(src) || !src.startsWith("http")) continue;
                    String js = probeSite(src);
                    if (TextUtils.isEmpty(js)) continue;
                    sb.append('\n').append(js);
                    got++;
                }
                if (got > 0) {
                    log("发布页脚本补充 " + got + " 个，重新收候选域名");
                    cands = publishCandidates(sb.toString());
                }
            }
            if (cands.isEmpty()) return "";
            log("发布页识别：候选域名 " + cands.size() + " 个");
            String brand = brandOf(curHost);
            int tried = 0;
            // 两轮：先试同品牌的（mtyy.tv → www.mtyy1.cc），再试其余（huangguo → 随机子域名）
            for (int pass = 0; pass < 2 && tried < 6; pass++) {
                for (String cand : cands) {
                    if (tried >= 6) break;
                    String h = hostOf(cand).toLowerCase(Locale.ROOT);
                    if (pass == 0 && (brand.isEmpty() || !h.replace("www.", "").contains(brand))) continue;
                    tried++;
                    String body = probeSite(cand + "/");
                    int links = contentLinkCount(body);
                    boolean real = body.length() >= 2000 && (links >= 5 || body.contains("vod_play_url")
                            || body.contains("voddetail") || body.contains("vodplay")
                            || body.contains("player_aaaa") || body.contains("dplayer"));
                    if (!real) {
                        log("发布页候选无内容 " + h + "（" + body.length() + " 字 / 链接 " + links + "）");
                        continue;
                    }
                    log("发布页解析命中 → " + cand + "（" + body.length() + " 字 / 链接 " + links + "）");
                    PUBLISH_RESOLVED.put(curHost, cand);
                    return cand;
                }
            }
        } catch (Throwable t) {
            log("发布页解析异常 " + t);
        }
        return "";
    }

    // ------------------------------------------------------------------ 探测

    private synchronized void detect() {
        if (detected) return;
        detected = true;
        // r530：入口域名若已被浏览器落地解析过，所有实例统一换到真域名（detail/player 常是新实例）。
        if (root != null) {
            String landed = TRANSIT_LANDED.get(hostOf(root).toLowerCase(Locale.ROOT));
            if (!TextUtils.isEmpty(landed) && !landed.equalsIgnoreCase(root)) {
                setBase(landed);
                log("复用落地域名 → " + root);
            }
        }
        String homeKey = root == null ? "" : root;
        String cachedHome = HOME_CACHE.get(homeKey);
        if (cachedHome != null && cachedHome.length() > 2000) {
            homeHtml = cachedHome;
            log("detect 复用首页缓存 " + homeHtml.length() + " 字（省一次首页请求）");
        } else {
            homeHtml = get(homeUrl());
            // r250：首页是跳转壳时跟随一次（很多「输入网址即用」的站首页只有 meta refresh / location.href）
            homeHtml = followShell(homeHtml);
            // r356：密码盾 —— ddys.la 首页是「请输入密码」盾页（5775B），detect 后 classes=1 first=all
            // （盾页无链接）→ 全站无内容。提取 setCookie 名值对重抓过盾（verify 32/32 实证）。
            homeHtml = passShield(homeHtml);
            // r350：域名失效自愈 —— 首页抓不到且源配置了发布页时，从发布页找新域名重抓一次
            // （参考 jable_失效自愈版 py：发布页 x99dh.pro 返 500 但 body 完整，只认内容不认状态码）。
            // homeKey 必须重算 —— 否则新域名首页会存进旧缓存 key。
            if (homeHtml == null || homeHtml.length() < 200) {
                String healed = null; // AVBox 未接入 WebSourceStore 域名自愈基础设施，跳过自愈（原：WebSourceHeal.quickHeal(root)）
                if (!TextUtils.isEmpty(healed)) {
                    setBase(healed);
                    homeKey = root;
                    homeHtml = followShell(get(homeUrl()));
                    log("发布页自愈 → " + root);
                }
            }
            if (homeHtml != null && homeHtml.length() > 2000) HOME_CACHE.put(homeKey, homeHtml);
        }
        // r530：**多层中转壳 / 域名轮换** —— 纯 HTTP 的 followShell 只跟一跳就跟不动，
        // 浏览器能跑完整条链。先放在发布页自愈之前，否则 423B 壳会被误判成地址发布页白试候选域名。
        if (looksLikeTransitShell(homeHtml)) {
            String landed = healByBrowserLanding(homeHtml);
            if (!TextUtils.isEmpty(landed)) {
                homeHtml = landed;
                if (homeHtml.length() > 2000) HOME_CACHE.put(root, homeHtml);
            }
        }
        // r500：**地址发布页自愈** —— 用户填的常是发布页（mtyy.tv / huangguo88.pages.dev），
        // 那一页没有影片链接，抽 0 条 → 「暂无内容」。这里识别并换到真实域名重抓首页。
        if (looksLikePublishPage(homeHtml)) {
            String healed = healFromPublishPage(homeHtml);
            if (!TextUtils.isEmpty(healed)) {
                setBase(healed);
                String reHome = followShell(get(homeUrl()));
                reHome = passShield(reHome);
                if (reHome != null && reHome.length() > 200) {
                    homeHtml = reHome;
                    if (homeHtml.length() > 2000) HOME_CACHE.put(root, homeHtml);
                    log("发布页自愈完成 root=" + root + " 首页 " + homeHtml.length() + " 字");
                }
            }
        }
        // r380（AGENTS.md 第十二节 L0 门禁层）：探一次就先把门禁类型打进日志 ——
        // 以后出问题先看这一行，就知道是「拿不到真页面」还是「后面的抽取错了」。
        logGateL0(homeHtml);
        // r380（AGENTS.md 第十二节 L2-R5）：**假首页 + 子路径探测**。
        // 真实实测（947 站）：393/830 站（47%）的根路径是「360导航停放页」（115107B、0 条影片），
        // 真实内容在子路径（如 https://dcs.ywp3.lat/dcs/ → 「原味片」84904B 真分类 69 条）。
        // 旧代码在这类站上 100% 失败（首页抽 0 条目 → 分类 0 条）。
        // 完整互斥条件见 AGENTS.md 12.3，任何一条不满足都不触发；失败时 fail-closed 不改 root。
        homeHtml = tryFakeHomepage(homeHtml);
        String low = homeHtml.toLowerCase(Locale.ROOT);
        // r250：模板只看首页「真实路由结构」，字符串特征不再单独作判据。
        // 事故原型：A 站（both.*）是 /video/{id}/ 结构，却被分类兜底链反推成 zanpian，
        // 于是分类抽成页脚法务页（home/privacy/protocol）、详情走 /bofang/ 解析器一条剧集都抽不到。
        // r359：YouTube 专项模板 —— SPA 巨站首页 ytInitialData 无 cookie 只有骨架
        //（沙箱实证 chips 0、richGrid 空），字符串特征全不命中、collectClasses 全抽空。host 判定优先。
        // r360：isYouTube 命中后必须短路后面整条 else 链 —— 真机实证 m.youtube.com 首页不含
        // maccms/zanpian 任何特征，else 链把它覆盖回 generic（日志 detect template=generic），
        // youtubeClasses 预设分类与四能力专项分支全部失效（三站投诉真因之一）。
        if (isYouTube()) template = "youtube";
        else if (low.contains("/index.php/vod/")) { template = "maccmsphp"; discoverIndexPath(homeHtml); }
        else if (low.contains("/bofang/") || low.contains("/forum/")) template = "zanpian";
        else if (low.contains("/voddetail/") || low.contains("/vodplay/") || low.contains("/vodtype/") || low.contains("macplayer")) template = "maccms";
        // r413：**静态数据 SPA**（data.js 型）—— zaka.live 实证：首页仅 3453 字节空壳，
        // 数据在 data.js 的 `window.REELS=[...]`（639 部，含标题/封面/m3u8 直链/标签）。
        // 这类站纯 HTTP 完全可解析，比渲染快两个数量级。
        else if (isJsDataSpa(homeHtml)) template = "jsdata";
        else if (nuxtDetect(homeHtml)) template = "nuxt-json-aes";
        else template = "generic";
        classes.clear();
        if ("youtube".equals(template)) classes.putAll(youtubeClasses());
        if ("zanpian".equals(template)) classes.putAll(zanpianClasses(homeHtml));
        else if ("maccmsphp".equals(template)) classes.putAll(maccmsPhpClasses(homeHtml));
        else if ("maccms".equals(template)) classes.putAll(maccmsClasses(homeHtml));
        // r250：兜底只有在首页真有该模板结构特征时才允许改 template。
        // 绝不能「随便抽到几条链接就改模板」—— 那会把分类和解析器一起带错（r249 回归根因）。
        if (classes.isEmpty() && low.contains("/index.php/vod/")) {
            LinkedHashMap<String, String> t = maccmsPhpClasses(homeHtml);
            if (!t.isEmpty()) {
                classes.putAll(t);
                template = "maccmsphp";
            }
        }
        if (classes.isEmpty() && (low.contains("/voddetail/") || low.contains("/vodplay/") || low.contains("/vodtype/"))) {
            LinkedHashMap<String, String> t = maccmsClasses(homeHtml);
            if (!t.isEmpty()) {
                classes.putAll(t);
                template = "maccms";
            }
        }
        if (classes.isEmpty() && (low.contains("/bofang/") || low.contains("/forum/"))) {
            LinkedHashMap<String, String> t = zanpianClasses(homeHtml);
            if (!t.isEmpty()) {
                classes.putAll(t);
                template = "zanpian";
            }
        }
        if (classes.isEmpty()) classes.putAll(genericClasses(homeHtml));
        // r356：classes 里剔除「与详情 pattern 同形态的链接」—— ddys.la 首页 43 个 /video/xxx.html
        // 影片卡片被 collectClasses 当分类（verify 实证 classes 混入影片名），既污染分类栏、
        // 又让 extractItemsByPattern 跳过这些链接 → 列表 0 条。剔除后只留真分类（电影/剧集/动漫）。
        // 反例：meizi5 真分类 /topic/slug 不匹配数字 pattern；nnyy vodplay 已被 SKIP_CLASS 拦。
        if (!classes.isEmpty()) {
            String detailPat = computeDetailPattern(homeHtml);
            // r365：剔除前必须确认这确实是「详情页形态」，否则会把与详情同形的分类误杀。
            // 反例（真机日志实证，用户称作 “missav” 的源实际就是 www.thisav66.com）：
            //   它的 detailPattern = \Q/cn/\E[A-Za-z][A-Za-z0-9_-]*/?（slug 分支，源自 r342/r356），
            //   而真实分类也写成 /cn/1pondo、/cn/10musume —— 同形，于是 “24→3” 再 “3→2”，
            //   用户看到的就是“分类就两三个”。
            // 判据：pattern 含数字 id（\\d）**或**带文件后缀（.html/.htm/.php/.asp）才算详情；
            //   ddys 的 slug 详情是 /video/<slug>.html（带 .html）→ 仍正常剔除，不受影响；
            //   thisav66 的 /cn/<slug>/ （无后缀无数字）→ 不做剔除。
            boolean detailShaped = detailPat.contains("\\d") || detailPat.contains(".html")
                    || detailPat.contains(".htm") || detailPat.contains(".php") || detailPat.contains(".asp");
            if (!TextUtils.isEmpty(detailPat) && detailShaped) {
                Pattern dpx = Pattern.compile(detailPat);
                int beforeA2 = classes.size();
                classes.keySet().removeIf(k -> k != null && dpx.matcher("/" + k).find());
                if (classes.size() != beforeA2) log("分类剔除详情链接 " + beforeA2 + "→" + classes.size() + "（pattern=" + detailPat + "）");
            } else if (!TextUtils.isEmpty(detailPat)) {
                log("分类不剔除：detailPattern 无数字无后缀（" + detailPat + "）→ 分类与详情同形，剔除会误杀");
            }
        }
        // r366：索引/筛选入口不是内容列表 —— 真机 thisav66 实证：
        //   「类型」tid=cn/genres 点进去抽到的是语言切换链接（简体中文），
        //   「发行商」tid=cn/makers 直接空；而具体项「VR」tid=cn/genres/VR 是正常内容。
        // 判据：**只看 tid 末段**是否为索引词；cn/genres/VR 末段是 VR，不受影响。
        // 反例验证（不得误杀）：meizi5 的 tag/meitui、topic/xxx 末段是具体 slug；
        //   飞卢 category/1/1.html、nnyy vodshow/…、btsn 20-28、sm455 list/1.html 均不受影响。
        if (!classes.isEmpty()) {
            Pattern idxPat = Pattern.compile("(?:^|/)(genres?|makers?|actors|actress|labels?|tags?|studios?|series)(?:/)?$", Pattern.CASE_INSENSITIVE);
            int beforeIdx = classes.size();
            classes.keySet().removeIf(k -> k != null && idxPat.matcher(k).find());
            if (classes.size() != beforeIdx) log("分类剔除索引入口 " + beforeIdx + "→" + classes.size());
        }
        // r251：站点同时有 视频/图片/音乐/小说 多个内容板块时，只保留这四类各一条（用户口径）；
        // 单一板块的站（绝大多数影视站只有「视频」类）不受影响，避免把 电影/电视剧/动漫 合成一条。
        if (!classes.isEmpty()) {
            LinkedHashMap<String, String> merged = dropNonContent(classes);
            // r321：二级分类展开 —— 有些站首页导航只有一个「分类」入口（真机飞卢听书：
            // 首页只有 /ts.faloo.com/Category.aspx，点进去才是 11 个真实分类：
            // 玄幻奇幻/武侠仙侠/都市言情/青春校园/军事历史/科幻网游/耽美同人/恐怖灵异/…）。
            // 只在「过滤后 ≤1 条且名字像分类入口」时展开一次，不对正常站多做请求。
            // r326：**入口要从原始 classes 里找**，不能只看过滤后的 merged ——
            // 真机飞卢：3 条（快速充值/更多>/飞卢听书分类）先被剔成 0 条，旧逻辑 onlyKey=null 直接放弃，
            // 二级展开永远不触发，于是用户看到「分类不对、毫无变化」。
            // r327：**入口要按优先级选**，不是随便取第一条。
            // 真机飞卢：3 条里同时有「更多>」(category/0/1.html) 和「飞卢听书分类」(Category.aspx)；
            // 而 category/0/1.html 是**书籍列表页**（沙箱实测：30 个 /listen/N.html + 2 个分页链接），
            // 不是分类页。旧代码按 map 顺序取到「更多>」→ 展开出 20 个书名当分类
            // （用户看到的就是「分类里全是书名」）。
            String entryKey = null;
            for (String want : new String[]{"分类", "全部", "更多"}) {
                for (Map.Entry<String, String> e : classes.entrySet()) {
                    String n = e.getValue() == null ? "" : e.getValue();
                    if (n.contains(want)) {
                        entryKey = e.getKey();
                        break;
                    }
                }
                if (entryKey != null) break;
            }
            if (merged != null && merged.size() <= 1 && entryKey != null) {
                LinkedHashMap<String, String> sub = new LinkedHashMap<>();
                collectClasses(get(abs(entryKey)), sub, true);
                sub = dropNonContent(sub);
                sub.remove(entryKey);
                if (sub.size() >= 3) {
                    merged.clear();
                    merged.putAll(sub);
                    log("二级分类展开 " + sub.size() + " 条（" + entryKey + "）");
                }
            }
            if (merged != classes) {
                classes.clear();
                classes.putAll(merged);
            }
        }
        // r361：nuxt-json-aes 分类来自权威 JSON 树（5 组 93 叶子），不走上面的 href 详情剔除 /
        // 多板块收缩后处理 —— 那是给 DOM 抽取降噪用的，套到 JSON 树上会误杀
        // （叶子名「自拍视频」含「视频」字样会被 slipNonContent 当板块入口合并）。
        if ("nuxt-json-aes".equals(template)) {
            classes.clear();
            classes.putAll(nuxtClasses());
            if (classes.isEmpty()) template = "generic";
        }
        // r388（AGENTS.md 第十八节）：付费墙分类剔除 —— 放在 nuxt JSON 覆盖之后，避免对权威 JSON 树空跑探测。
        // 47o.net 实证：24 条分类里 22 条是 /meitu/category/* 付费墙，另有 /comic、/novel、/movie 三板块。
        if (!classes.isEmpty() && !"nuxt-json-aes".equals(template)) {
            int beforePaywall = classes.size();
            dropPaywalledGroups(classes);
            if (classes.size() != beforePaywall) log("分类付费墙剔除 " + beforePaywall + "→" + classes.size());
        }
        // r259：抽不到分类（多为源站 520 / 人机验证页）→ 回退上次成功的分类，别让分类栏直接清空。
        // r408：把「JS 验证页」和「非内容站」也纳入渲染兜底的判定面。
        // 这批站实测形态：title「正在验证」+ 16663 字 + 0 链接 0 图 → 分类必然抽不到，
        // 但走一次 WebView 真跑有机会过验证；而推广/工具页即使渲染也没内容，先标出来省一次渲染。
        boolean jsVerifyPage = isJsVerifyPage(homeHtml);
        boolean promoPage = isPromoPage(homeHtml);
        if (jsVerifyPage) log("检测到 JS 验证页（" + homeHtml.length() + " 字）→ 走渲染兜底");
        if (promoPage) log("检测到推广/工具页（无内容结构）→ 不做渲染兜底，直接空态");
        boolean classesEmpty = classes.isEmpty() || (classes.size() == 1 && classes.containsKey("all"));
        if (classesEmpty && !promoPage) {
            // r339：HTTP 抽不到分类，多半是 WAF 挑战页 / JS 渲染页（真机 4kcz：694KB 全是 .safeline 挑战代码，
            // links 只有 3 个）。这里用 WebView 真跑一次页面、取渲染后的 DOM 再抽一遍。
            // 只在「已经抽不到」时触发，不影响任何能正常抽到分类的站。
            // r359：加密壳留痕 —— k84h5x 这类 ap-d 型壳站分类只能靠下面的 WebView 渲染兜底，
            // 明确标记站点类型，真机日志可直接定位（不再与「源站慢/被拦」混淆）。
            if (isEncryptedShell(homeHtml)) log("检测到 JS 加密壳（ap-d 型 " + homeHtml.length() + " 字）→ 分类走渲染兜底");
            try {
                Map<String, String> hd = new LinkedHashMap<>();
                String rh = webFetch(abs(root) + "/", hd, 25000);
                if (!TextUtils.isEmpty(rh) && rh.length() > 2000) {
                    // r361：渲染 DOM 里找 __NUXT__ 的 json_url（nuxt-json-aes 站：ap-d 壳 + Nuxt SPA）。
                    // 找到 → 切模板并直接取 JSON 分类；这类站解壳后分类全是 javascript:void(0)，
                    // href 抽取永远 0 条（真机 r360：渲染 176945 字、分类 0 条的根因）。
                    // 只在默认门户也失效（域名轮换）时才走到这里，不增加任何既有站的渲染次数。
                    nuxtHealSource = rh;
                    String nuxtJu = firstMatch(rh, "json_url\\s*:\\s*\"(https?://[^\"]+)\"");
                    if (!"nuxt-json-aes".equals(template) && nuxtVerify(nuxtJu)) {
                        template = "nuxt-json-aes";
                        nuxtBase = nuxtJu;
                        NUXT_JSON.put(root == null ? "" : root, nuxtJu);
                        classes.clear();
                        classes.putAll(nuxtClasses());
                        classesEmpty = classes.isEmpty();
                        log("nuxt-json-aes 渲染发现 json_url=" + nuxtJu + " 分类 " + classes.size() + " 条");
                    }
                    // r361：已经是 nuxt-json-aes 就不要再用 DOM 分类覆盖 ——
                    // 这类站解壳后分类全是 javascript:void(0)，偶尔凑出的几条假分类会盖掉权威 JSON 树。
                    if (!"nuxt-json-aes".equals(template)) {
                    LinkedHashMap<String, String> rendered = new LinkedHashMap<>();
                    collectClasses(rh, rendered, true);
                    rendered = dropNonContent(rendered);
                    if (rendered.size() >= 3) {
                        classes.clear();
                        classes.putAll(rendered);
                        classesEmpty = false;
                        log("WebView 渲染补抓分类 " + rendered.size() + " 条（" + rh.length() + " 字）");
                    } else {
                        log("WebView 渲染完成但分类仍不足（" + rendered.size() + " 条 / " + rh.length() + " 字）");
                    }
                    }
                } else {
                    log("WebView 渲染未取到 DOM（" + (rh == null ? 0 : rh.length()) + " 字）");
                }
            } catch (Throwable e) {
                log("WebView 渲染补抓异常 " + e);
            }
        }
        if (classesEmpty) {
            LinkedHashMap<String, String> cached = cachedClasses();
            if (!cached.isEmpty()) {
                classes.clear();
                classes.putAll(cached);
                log("分类抽取为空（首页不可用），回退上轮缓存 " + cached.size() + " 条");
            }
        } else {
            cacheClasses(classes);
        }
        if (classes.isEmpty()) classes.put("all", "全部");
        log("detect template=" + template + " homeLen=" + homeHtml.length() + " links=" + countHref(homeHtml)
                + " classes=" + classes.size()
                + (classes.isEmpty() ? "" : " first=" + classes.keySet().iterator().next()));
        // r249/r250：分类抽不到时把首页链接与正文样本写进 /debug/logs —— 部分站点沙箱连不上，
        // 只能靠真机取证；正文样本用来判断首页到底是反爬占位页还是 JS 渲染页。
        if (classes.size() <= 1) log("分类稀疏 href样本=" + hrefSample(homeHtml, 24) + " | text=" + textSample(homeHtml, 200));
        // r371：分类不足时，**重放页面自己请求过的 JSON 接口** —— 把页面已解密好的数据当数据源。
        // （XHR/fetch hook 在渲染兜底里注入，所以这里跑时已经收集好；空集合会立即返回）
        // r413：jsdata 的分类直接在 detect 阶段聚合（不用再抓页面）
        if ("jsdata".equals(template)) {
            classes.clear();
            classes.putAll(jsdataClasses());
        }
        if (classes.size() <= 1 && !"nuxt-json-aes".equals(template)) {
            String json = probeJsonApis("category");
            if (!TextUtils.isEmpty(json)) log("json 分类候选已取到（" + json.length() + " 字），下一步按结构解析");
        }
        // r349：搜索端点发现（表单/内联JS/外链JS 三层）—— 真机日志实证网页源搜索全 0 结果的根因
        // 是 generic 分支 4 个写死路径不匹配真实站点（meizi5=/?s=、gaze.red=/filter?search=、faloo=b.faloo.com/l/0/1.html?t=0&k=）。
        // r361：nuxt-json-aes 的搜索走 /api/v2/search 专属分支，这里没必要再去抑表单/内联JS/外链JS
        // （外链 JS 会发额外请求），避免无效开销。
        // r367：搜索端点发现改为**懒执行** —— 原先在这里无条件跑，其中 findSearchFromLinkedJs
        // 会额外拉 ≤2 个外链 JS（每个 get() 超时 15s），用户感知就是「首次打开网页源要等很久」。
        // 而搜索端点只有搜索时才用到，所以挪到 searchContent 首次搜索时再发现（见 searchContent）。
        // 效果：打开源少 1~3 次网络往返（外链 JS 最坏 30s），搜索行为不变。
    }

    /**
     * r359：JS 加密壳页判定 —— k84h5x 实证：首页/分类/详情/搜索页全是
     * <script type="text/plain" id="ap-d" data-k=…> 密文块 + 页内 d.js 解密渲染，
     * 纯 HTTP 拿到的页面零链接零文本（首页实测 5462 字、href=0、title 空）。
     * 同类「ap-d 型防红加密壳」通用；WebFetch 的 r342 轮询机制
     * （title 空 + 链接<5 时每 2.5s 探测，d.js 解密完成即命中取 DOM）
     * 天然适配这类壳的异步解密，无需额外等待逻辑。
     */
    private static boolean isEncryptedShell(String html) {
        return html != null && html.contains("id=\"ap-d\"");
    }

    /** r380（AGENTS.md 12.3 R3）：盾页判定 —— R5 的前置否决。
     *  ddys 盾页 5519 字 0 链接，完全符合「假首页」特征，不否决就会误触。 */
    private static boolean looksLikeShield(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String low = html.toLowerCase(Locale.ROOT);
        return low.contains("mac_verify_img") || low.contains("mac_verify")
                || low.contains("请输入密码") || low.contains("verify_code");
    }

    /** r380：标题像导航停放页（「360导航--主页」这类）。 */
    private static boolean titleLooksNav(String html) {
        if (TextUtils.isEmpty(html)) return false;
        Matcher m = P_TITLE.matcher(html);
        if (!m.find()) return false;
        String t = m.group(1).toLowerCase(Locale.ROOT);
        return t.contains("导航") || t.contains("主页") || t.contains("nav") || t.contains("daohang");
    }

    /**
     * r380：数站内「详情页形态」链接数（R5 的「0 链接」判据）。
     *
     * <p><b>r380 回归修正（实测事故）</b>：初版只认相对地址 {@code href="/..."}，
     * 而 meizi5 的导航**全是绝对地址**（实测 81 个 href 中 79 个是 https://meizi5.com/...、0 个相对）
     * → 会漏数 → 可能误触 R5。故先把同站绝对地址归一化成路径再判。
     * 外域绝对地址不归一（避免把广告位当站内详情）。
     */
    private int countDetailLinks(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        Set<String> s = new LinkedHashSet<>();
        Matcher m = Pattern.compile("href=\"([^\"'?#\\s]{2,160})\"").matcher(html);
        while (m.find() && s.size() < 300) {
            String p = m.group(1);
            if (p.startsWith("http")) {
                int slash = p.indexOf('/', p.indexOf("://") + 3);
                String pHost = slash > 0 ? p.substring(p.indexOf("://") + 3, slash) : p.substring(p.indexOf("://") + 3);
                if (!TextUtils.isEmpty(host) && !pHost.equalsIgnoreCase(host) && !pHost.equalsIgnoreCase("www." + host)
                        && !("www." + pHost).equalsIgnoreCase(host)) continue;
                p = slash > 0 ? p.substring(slash) : "/";
            }
            if (!p.startsWith("/")) continue;
            if (NON_CONTENT_PATH.matcher(p).find()) continue;
            if (p.matches(".*[-/]\\d+[^/]*$") || p.contains("/voddetail/") || p.contains("/vodplay/")
                    || p.contains("/vodshow/") || p.contains("/movie/") || p.contains("/detail/")
                    || p.contains("/video/") || p.contains("/show/") || p.contains("/book/")) {
                s.add(p);
            }
        }
        return s.size();
    }

    /**
     * r380（AGENTS.md 第十二节 L2-R5）：假首页 + 子路径探测。
     *
     * <p>真实实测（947 站）：393/830 站（47%）根路径是「360导航停放页」（115107B、0 条影片、
     * 0 详情链接），真实内容在子路径。例：https://dcs.ywp3.lat/dcs/ → 「原味片」84904B、真分类 69 条。
     * 旧代码在这类站上 100% 失败（首页抽 0 条目 → 分类 0 条）。
     *
     * <p><b>七条互斥（AGENTS.md 12.3，缺一不触发）</b>：
     * ① 不是盾页（R3 红线）；② 不是 ap-d 加密壳；③ 不是 Cap 站；④ 不是页内 MSE 站（gaze）；
     * ⑤ 站内详情链接 == 0；⑥ 按 detailPattern 抽不出条目；⑦ 长度为 < 20000 或标题像导航。
     *
     * <p><b>fail-closed</b>：只扫出真内容（条目 >0 或详情链接 >0）才改写 root/homeHtml；
     * 否则原样返回且不改 template。最多探测 2 个子路径，避免请求爆炸。
     */
    private String tryFakeHomepage(String home) {
        try {
            if (TextUtils.isEmpty(home) || home.length() < 200) return home;
            // r380（回归实测修正）：403/500 错误页不能当「假首页」去扫子路径。
            // 实测 4kcz(403,15279B，含 forbidden) / missav09(500,2337B) 原本会误触 R5。
            if (isErrorPage(home)) { log("R5 跳过：错误页/盾页"); return home; }
            if (looksLikeShield(home)) { log("R5 跳过：盾页"); return home; }
            if (isEncryptedShell(home)) { log("R5 跳过：ap-d 壳"); return home; }
            if (home.contains("cap-widget")) { log("R5 跳过：Cap 站"); return home; }
            if (isGazeLike(root, home)) { log("R5 跳过：页内MSE 站"); return home; }
            // r380（回归实测修正）：**停放页形态是唯一触发入口，必须先判**。
            // 实测对比：真停放页 title="360导航--主页"、112728B、368 href、内嵌 7 个推荐卡片链接；
            //           4kcz(403)=title空、15279B；missav09(500)=title空、2337B；均无停放标志。
            // 旧写法先卡 dl>0 → 停放页的 7 个推荐卡片直接把它挡掉，R5 永远不会触发。
            boolean parking = titleLooksNav(home)
                    || home.contains("360导航") || home.contains("请收藏此网址")
                    || home.contains("防止失联") || home.contains("备用网址")
                    || home.contains("本站永久域名");
            if (!parking) return home;
            // 停放页允许少量内嵌推荐链接（实测 7 个）；≥20 条视为真内容站，不折腾。
            int dl = countDetailLinks(home);
            if (dl >= 20) { log("R5 跳过：详情链接 " + dl + " 条，像真内容站"); return home; }
            String pat = computeDetailPattern(home);
            int items = TextUtils.isEmpty(pat) ? 0 : extractItemsByPattern(home, pat, 5).length();
            if (items >= 5) { log("R5 跳过：能抽出 " + items + " 条目，像真内容站"); return home; }

            java.util.List<String> cands = new java.util.ArrayList<>();
            Matcher m = Pattern.compile("href=\"(/[a-z0-9]{2,12}/)\"", Pattern.CASE_INSENSITIVE).matcher(home);
            while (m.find() && cands.size() < 2) {
                String p = m.group(1);
                if (!cands.contains(p)) cands.add(p);
            }
            if (cands.isEmpty() && !TextUtils.isEmpty(host)) {
                String[] parts = host.split("\\.");
                if (parts.length >= 2) {
                    cands.add("/" + parts[0] + "/");
                    String seg = parts[1].replaceAll("\\d+$", "");
                    String p2 = "/" + seg + "/";
                    if (!p2.equals(cands.get(0))) cands.add(p2);
                }
            }
            int tried = 0;
            for (String path : cands) {
                if (tried++ >= 2) break;
                String probeUrl = root + path;
                String ph = get(probeUrl);
                if (TextUtils.isEmpty(ph) || ph.length() < 15000) continue;
                if (looksLikeShield(ph) || isEncryptedShell(ph)) continue;
                String pp = computeDetailPattern(ph);
                int pi = TextUtils.isEmpty(pp) ? 0 : extractItemsByPattern(ph, pp, 5).length();
                int pdl = countDetailLinks(ph);
                if (pi <= 0 && pdl <= 0) continue;
                log("R5 假首页 → 真内容 " + probeUrl + "（条目" + pi + " 详情链接" + pdl + "）");
                setBase(probeUrl);
                this.homeHtml = ph;
                return ph;
            }
            log("R5 未找到真首页（试了 " + tried + " 个子路径）→ 保持原样");
        } catch (Throwable e) {
            log("R5 假首页探测异常 " + e);
        }
        return home;
    }

    /**
     * r380（AGENTS.md 第十二节 L0 门禁层）：把门禁类型打进日志。
     *
     * <p>目的：以后出问题**先看这一行**，就能分清两种完全不同的情况：
     * <ul>
     *   <li>“拿不到真页面”（G1~G6）—— 改解析器没意义；</li>
     *   <li>“拿到了但抽取错”（G0）—— 才是改抽取器的活。</li>
     * </ul>
     * 只做判定与日志，**不改任何行为**（零回归风险）。
     */
    private void logGateL0(String html) {
        try {
            String gate;
            if (html == null || html.length() < 200) gate = "G6_不可达";
            else if (looksLikeShield(html)) gate = "G3_密码盾";
            else if (isEncryptedShell(html)) gate = "G4_ap-d壳";
            else if (html.contains("cap-widget")) gate = "G2_Cap验证";
            else if (isGazeLike(root, html)) gate = "G7_页内MSE";
            else if (html.length() < 20000 && titleLooksNav(html)) gate = "G8_导航停放页";
            else gate = "G0_正常";
            log("L0 门禁 gate=" + gate + " len=" + (html == null ? 0 : html.length())
                    + " title=" + titleSample(html, 40));
        } catch (Throwable e) {
            log("L0 门禁判定异常 " + e);
        }
    }

    /** 取页面 title 样本（带容错）。 */
    private static String titleSample(String html, int max) {
        if (TextUtils.isEmpty(html)) return "";
        Matcher m = P_TITLE.matcher(html);
        if (!m.find()) return "";
        String t = m.group(1).trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ===== r361：nuxt-json-aes 模板 —— Nuxt SPA + AES 加密 JSON 数据层 =====
    // 取证（沙箱 + 真机浏览器，A 级）：
    //  ① 主域只发 ap-d 加密壳（5462 字），由页内 d.js 解密渲染；纯 HTTP 拿不到真页面。
    //  ② 即便 WebView 解壳渲染，分类导航全是 javascript:void(0)（194 个链接 0 个真 href），
    //     基于 href 的 collectClasses 必然 0 条 —— r360 真机「渲染 176945 字但分类 0 条」根因。
    //  ③ 真实数据全在 json_url 指向的数据层，且门户不在防红壳后面（纯 HTTP 可达）；
    //     返回体统一 {"status":1,"data":"<base64url 密文>"}，AES-256-CBC/PKCS7。
    // AES key/IV 取自站点自身 chunk 的字面量（浏览器取数用的同一把，非暴力破解）。
    private static final String NUXT_JSON_DEFAULT = "https://portal.instruno.com/public2/json";
    // 默认 key/IV = 站点 chunk 里的字面量（2026-11 实测：chunk 内 32hex 全局唯一，正则命中 1/1）。
    // 改为可自愈字段：站点换 key 时从它自己的 JS chunk 里重新读回，不静默失效。
    private static volatile String NUXT_AES_KEY = "22946bc50fd63164b79df55070a85a92";
    private static volatile String NUXT_AES_IV = "kaixin1234567890";
    private static volatile long NUXT_KEY_HEALED_AT = 0L;
    // m3u8 相对路径的 CDN 前缀池。JS 源码判据：self(国产)→ybfzsb、actor(女优)→73/74、
    // 默认→41/44/46/47/48/34；沙箱实测 7/8 池同内容可播（34cdn 当时不可达，排末位）。
    private static final String[] NUXT_CDN = {"m3u8.41cdn.com", "m3u8.44cdn.com", "m3u8.46cdn.com", "m3u8.47cdn.com", "m3u8.48cdn.com", "m3u8.73cdn.com", "m3u8.74cdn.com", "tdmt9rb9o7z0euxd.ybfzsb.com"};
    private static final Map<String, String> NUXT_JSON = new LinkedHashMap<>();
    // key 自愈时的 HTML 线索来源（壳页/渲染 DOM），由 detect 与渲染兜底填入
    private String nuxtHealSource;
    private String nuxtBase;
    private String nuxtCatCache;

    private static byte[] nuxtAes(int mode, byte[] in) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(NUXT_AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(NUXT_AES_IV.getBytes(StandardCharsets.UTF_8)));
        return c.doFinal(in);
    }

    /** 解密数据层响应：{"status":1,"data":"<base64url>"} → 明文 JSON；失败返回 null。 */
    private String nuxtDecrypt(String body) {
        if (TextUtils.isEmpty(body)) return null;
        try {
            String data = body.trim();
            if (data.startsWith("{")) data = new JSONObject(data).optString("data");
            if (TextUtils.isEmpty(data)) return null;
            String b64 = data.replace('-', '+').replace('_', '/');
            StringBuilder sb = new StringBuilder(b64);
            while (sb.length() % 4 != 0) sb.append('=');
            byte[] ct = Base64.decode(sb.toString(), Base64.DEFAULT);
            return new String(nuxtAes(Cipher.DECRYPT_MODE, ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log("nuxt 解密失败：" + e);
            return null;
        }
    }

    /** 搜索词加密（同 key/IV；固定 IV → 同词密文恒定，与站点一致），base64url 无填充。 */
    private static String nuxtEncrypt(String word) {
        try {
            byte[] ct = nuxtAes(Cipher.ENCRYPT_MODE, word.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(ct, Base64.NO_WRAP).replace('+', '-').replace('/', '_').replace("=", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * r361：AES key 自愈 —— 站点换 key 时从它自己的 JS chunk 里读回，不静默失效。
     * 依据（A 级实测）：chunk 内 32hex 全局唯一，且与 16 字符 IV 相邻出现，
     * 正则 `"32hex",var="16字符"` 命中 1/1。chunk 名是构建哈希、无清单可枚举（实测
     * builds/latest.json、index.js、index.html 均 404/403），所以只能从页面它自己引用的
     * assets/*.js 里找 —— 用已拿到的 homeHtml 或渲染 DOM 的 script[src] 作线索。
     */
    private boolean nuxtHealKey(String html) {
        if (TextUtils.isEmpty(html)) return false;
        // 每 10 分钟最多自愈一次，避免站点真不可用时反复拉 chunk
        long now = System.currentTimeMillis();
        if (now - NUXT_KEY_HEALED_AT < 600000L) return false;
        try {
            Matcher sm = Pattern.compile("(?:src|href)=[\"']([^\"']*?/?assets/[A-Za-z0-9_-]{6,40}\\.js)[\"']").matcher(html);
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            while (sm.find() && urls.size() < 8) urls.add(abs(sm.group(1)));
            if (urls.isEmpty()) {
                // 壳页只引用 d.js，不含 assets —— 退一步：直接试门户默认 assets 路径下的已知 chunk 名
                log("nuxt key 自愈：页面内无 assets chunk 引用（壳页特征），跳过");
                return false;
            }
            // 只有确实找到候选 chunk 才算一次自愈，避免无关站把限流窗口占掉
            NUXT_KEY_HEALED_AT = System.currentTimeMillis();
            Pattern kp = Pattern.compile("[\"']([0-9a-f]{32})[\"']\\s*,\\s*[A-Za-z_$][\\w$]*\\s*=\\s*[\"']([^\"']{16})[\"']");
            for (String u : urls) {
                String js = nuxtProbeGet(u);
                if (TextUtils.isEmpty(js) || js.length() < 1000) continue;
                Matcher km = kp.matcher(js);
                if (km.find()) {
                    String nk = km.group(1), nv = km.group(2);
                    if (!nk.equals(NUXT_AES_KEY) || !nv.equals(NUXT_AES_IV)) {
                        NUXT_AES_KEY = nk;
                        NUXT_AES_IV = nv;
                        log("nuxt key 自愈成功：从 " + u + " 读到新 key/IV");
                        return true;
                    }
                    return false;
                }
            }
            log("nuxt key 自愈：已试 " + urls.size() + " 个 chunk，未找到 key 特征");
        } catch (Throwable e) {
            log("nuxt key 自愈异常：" + e);
        }
        return false;
    }

    private String nuxtJson(String path) {
        String base = TextUtils.isEmpty(nuxtBase) ? NUXT_JSON_DEFAULT : nuxtBase;
        return nuxtDecrypt(get(base + path));
    }
    /**
     * r361：探测专用短超时取数 —— 不能用 get()（固定 15s）。
     * 既有 ap-d 壳站（k84h5x 等）会进 nuxtDetect 探一次默认门户；
     * 若门户不可达而走 15s 超时，等于给旧站白加 15 秒延迟，违反「不破坏旧站」。
     */
    private String nuxtProbeGet(String url) {
        try {
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(abs(url))
                    .header("User-Agent", UA)
                    .build();
            try (Response res = OkHttp.noRedirect(6000).newCall(req).execute()) {
                ResponseBody body = res.body();
                if (res.code() != 200 || body == null) return "";
                return new String(body.bytes(), StandardCharsets.UTF_8);
            }
        } catch (Throwable e) {
            return "";
        }
    }

    /** 只在壳页/空页探测（正常 HTML 站零成本跳过）；命中后 nuxtBase 就位。 */
    private boolean nuxtDetect(String homeHtml) {
        try {
            boolean shell = isEncryptedShell(homeHtml) || TextUtils.isEmpty(homeHtml) || homeHtml.length() < 200;
            if (!shell) return false;
            String key = root == null ? "" : root;
            nuxtHealSource = homeHtml;
            // r361 铁律：只认「本主机已验证过的缓存」，不再无条件信任共享门户。
            // 门户是站点家族的公共数据层，任何 ap-d 壳站（如 k84h5x）都能验通并解密成功 ——
            // 若无条件采信，会把别的站误判成本站、串入别站内容（不可接受）。
            // 首次发现走渲染兜底：页面 DOM 里真的写了 json_url 才算数（下面 homeContent 的渲染块）。
            String cached = NUXT_JSON.get(key);
            if (nuxtVerify(cached)) {
                nuxtBase = cached;
                log("nuxt-json-aes 命中（缓存 json_url=" + nuxtBase + "）");
                return true;
            }
            return false;
        } catch (Exception e) {
            log("nuxt 探测异常：" + e);
            return false;
        }
    }

    /** 校验 json_url：能取到分类树并解密成功；解密失败时尝试 key 自愈后重验一次。 */
    private boolean nuxtVerify(String base) {
        if (TextUtils.isEmpty(base)) return false;
        String p = nuxtDecrypt(nuxtProbeGet(base + "/category-list.json"));
        if (p == null) {
            // r361：解密失败 = 站点可能换 key。从它自己的 HTML（壳页/渲染 DOM）里找 chunk
            // 读回新 key/IV 再验一次；仍失败则判定非本站。key 未变时走不到这里，零额外开销。
            if (!nuxtHealKey(nuxtHealSource)) return false;
            p = nuxtDecrypt(nuxtProbeGet(base + "/category-list.json"));
        }
        if (p == null || !p.trim().startsWith("[") || !p.contains("classid")) return false;
        nuxtCatCache = p;
        return true;
    }

    /** 分类树扁平化：5 组（国产/女优/电影/图文/小说）93 叶子，叶子名全唯一，classid 作 tid。 */
    private LinkedHashMap<String, String> nuxtClasses() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try {
            String p = nuxtCatCache != null ? nuxtCatCache : nuxtJson("/category-list.json");
            if (TextUtils.isEmpty(p)) {
                log("nuxt 分类树空（json_url=" + nuxtBase + "）");
                return out;
            }
            JSONArray groups = new JSONArray(p);
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                JSONArray items = g == null ? null : g.optJSONArray("items");
                if (items == null) continue;
                for (int j = 0; j < items.length(); j++) {
                    JSONObject leaf = items.optJSONObject(j);
                    if (leaf == null) continue;
                    String cid = leaf.optString("classid"), cname = leaf.optString("classname");
                    if (!TextUtils.isEmpty(cid) && !TextUtils.isEmpty(cname)) out.put(cid, cname);
                }
            }
            log("nuxt 分类树 " + groups.length() + " 组 / " + out.size() + " 叶子");
        } catch (Exception e) {
            log("nuxt 分类解析失败：" + e);
        }
        return out;
    }

    /** 封面：站点自身播放器用 titlepic.replace(".txt",".jpg")；[db:缩略图] 是占位串，丢弃。 */
    private static String nuxtPic(String pic) {
        if (TextUtils.isEmpty(pic) || pic.startsWith("[db:")) return "";
        return pic.replace(".txt", ".jpg");
    }

    /** m3u8 相对路径拼 CDN 前缀；绝对地址原样返回。 */
    private static String nuxtM3u8(String m3u8) {
        if (TextUtils.isEmpty(m3u8)) return "";
        if (m3u8.startsWith("http")) return m3u8;
        return "https://" + NUXT_CDN[0] + (m3u8.startsWith("/") ? m3u8 : "/" + m3u8);
    }

    private static String nuxtDate(long ts) {
        if (ts <= 0) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date(ts * 1000L));
    }

    /** 列表 → vod 数组。视频条目带 m3u8/yulan；图文/小说条目没有，vod_id 加 n: 前缀区分详情端点。 */
    private JSONArray nuxtVods(JSONArray data) throws Exception {
        JSONArray videos = new JSONArray();
        if (data == null) return videos;
        for (int i = 0; i < data.length(); i++) {
            JSONObject it = data.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id");
            if (TextUtils.isEmpty(id)) continue;
            boolean news = !it.has("m3u8") && !it.has("yulan");
            JSONObject v = new JSONObject();
            v.put("vod_id", news ? "n:" + id : id);
            v.put("vod_name", it.optString("title"));
            v.put("vod_pic", nuxtPic(it.optString("titlepic")));
            v.put("vod_remarks", nuxtDate(it.optLong("newstime")));
            videos.put(v);
        }
        return videos;
    }

    /** 分类列表：json/category/<classid>-<page>.json（实测 28 条/页，last_page/total 随页返回）。 */
    private String nuxtCategory(String tid, int page) throws Exception {
        String p = nuxtJson("/category/" + tid + "-" + page + ".json");
        if (TextUtils.isEmpty(p)) {
            log("nuxt 分类页空 tid=" + tid + " page=" + page);
            return new JSONObject().put("list", new JSONArray()).toString();
        }
        JSONObject o = new JSONObject(p);
        JSONArray data = o.optJSONArray("data");
        int last = o.optInt("last_page", 0), total = o.optInt("total", 0), per = o.optInt("per_page", 28);
        JSONObject r = new JSONObject();
        r.put("list", nuxtVods(data));
        r.put("page", page);
        r.put("pagecount", last > 0 ? last : (total > 0 && per > 0 ? (total + per - 1) / per : 1));
        r.put("total", total);
        r.put("limit", per);
        log("nuxt 分类 tid=" + tid + " page=" + page + " 条数=" + (data == null ? 0 : data.length()) + " last=" + last + " total=" + total);
        return r.toString();
    }

    /** 详情：视频走 video/<id>.json；图文/小说走 view/<id>.json（JS 源码判据 tbname=news）。 */
    private String nuxtDetail(String raw) throws Exception {
        boolean news = raw != null && raw.startsWith("n:");
        String id = news ? raw.substring(2) : raw;
        String p = nuxtJson((news ? "/view/" : "/video/") + id + ".json");
        if (TextUtils.isEmpty(p)) {
            log("nuxt 详情空 id=" + id + " news=" + news);
            return new JSONObject().put("list", new JSONArray()).toString();
        }
        JSONObject o = new JSONObject(p);
        JSONObject v = new JSONObject();
        v.put("vod_id", raw);
        v.put("vod_name", o.optString("title"));
        v.put("vod_pic", nuxtPic(o.optString("titlepic")));
        String date = nuxtDate(o.optLong("newstime"));
        v.put("vod_year", date.length() >= 4 ? date.substring(0, 4) : "");
        v.put("vod_remarks", o.optString("category_name"));
        v.put("vod_content", o.optString("keyboard"));
        if (news) {
            // 图文/小说分流：实测图片集 img≥1 且纯文本<2000（穹妹cos[48P]）；
            // 真小说 0 图纯文本（古典武侠 14937 字）。播放阶段回 playerContent 再分流。
            v.put("vod_play_from", "在线播放");
            v.put("vod_play_url", "全集$n:" + id);
            log("nuxt 图文/小说详情 " + id + " → 播放阶段分流 pics:// 或 novel://");
        } else {
            String m3u8 = nuxtM3u8(o.optString("m3u8")), mp4 = o.optString("mp4");
            List<String> from = new ArrayList<>(), url = new ArrayList<>();
            if (!TextUtils.isEmpty(m3u8)) {
                from.add("m3u8");
                url.add("正片$" + m3u8);
            }
            if (!TextUtils.isEmpty(mp4)) {
                from.add("mp4");
                url.add("正片$" + mp4);
            }
            v.put("vod_play_from", TextUtils.join("$$$", from));
            v.put("vod_play_url", TextUtils.join("$$$", url));
            log("nuxt 视频详情 " + id + " m3u8=" + m3u8 + " mp4=" + (TextUtils.isEmpty(mp4) ? "无" : "有"));
        }
        JSONArray list = new JSONArray();
        list.put(v);
        return new JSONObject().put("list", list).toString();
    }

    /** content 里抽图片（<img src=…> 与不带引号的 <img src=…> 两种写法都吃过）。 */
    private static List<String> nuxtImgs(String content) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(content)) return out;
        Matcher m = Pattern.compile("<img[^>]+src=[\"']?([^\"'\\s>]+)", Pattern.CASE_INSENSITIVE).matcher(content);
        while (m.find()) {
            String u = m.group(1);
            if (!TextUtils.isEmpty(u) && !out.contains(u)) out.add(u);
        }
        return out;
    }

    /** 搜索：活域 /api/v2/search?keyword=<同 key 加密词>&classid=0&page=N（门户不代理，实测 502/404）。 */
    private String nuxtSearch(String key, int page) throws Exception {
        String enc = nuxtEncrypt(key);
        if (TextUtils.isEmpty(enc)) return new JSONObject().put("list", new JSONArray()).toString();
        String url = abs(root) + "/api/v2/search?keyword=" + URLEncoder.encode(enc, "UTF-8") + "&classid=0&page=" + page;
        String p = nuxtDecrypt(get(url));
        if (TextUtils.isEmpty(p)) {
            log("nuxt 搜索空 key=" + key);
            return new JSONObject().put("list", new JSONArray()).toString();
        }
        JSONObject o = new JSONObject(p);
        JSONArray data = o.optJSONArray("data");
        JSONArray videos = new JSONArray();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject it = data.optJSONObject(i);
                if (it == null) continue;
                String id = it.optString("id");
                if (TextUtils.isEmpty(id)) continue;
                boolean news = !"movie".equals(it.optString("tbname"));
                JSONObject v = new JSONObject();
                v.put("vod_id", news ? "n:" + id : id);
                v.put("vod_name", it.optString("title"));
                v.put("vod_remarks", it.optString("classname"));
                videos.put(v);
            }
        }
        JSONObject r = new JSONObject();
        r.put("list", videos);
        r.put("page", page);
        r.put("pagecount", 1);
        r.put("total", o.optInt("total", videos.length()));
        r.put("limit", o.optInt("per_page", 50));
        log("nuxt 搜索 key=" + key + " 条数=" + videos.length() + " total=" + o.optInt("total", 0));
        return r.toString();
    }

    /** 播放：直链直投；n: 前缀回到数据层取 content，按图/文分流 pics:// 与 novel://。 */
    private String nuxtPlay(String id) throws Exception {
        JSONObject r = new JSONObject();
        r.put("parse", 0);
        JSONObject h = new JSONObject();
        h.put("User-Agent", UA);
        h.put("Referer", abs(root) + "/");
        if (id != null && id.startsWith("n:")) {
            String p = nuxtJson("/view/" + id.substring(2) + ".json");
            if (TextUtils.isEmpty(p)) {
                log("nuxt 播放取详情空 id=" + id);
                r.put("url", "");
                return r.toString();
            }
            JSONObject o = new JSONObject(p);
            String content = o.optString("content");
            String plain = content.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
            List<String> imgs = nuxtImgs(content);
            if (!imgs.isEmpty() && plain.length() < 2000) {
                r.put("url", "pics://" + TextUtils.join("&&", imgs));
                r.put("header", h);
                log("nuxt 图片 " + imgs.size() + " 张 → pics://");
            } else {
                JSONObject n = new JSONObject();
                n.put("content", plain);
                n.put("title", o.optString("title"));
                r.put("url", "novel://" + n);
                log("nuxt 小说正文 " + plain.length() + " 字 → novel://");
            }
            return r.toString();
        }
        r.put("url", id);
        r.put("header", h);
        log("nuxt 直链直投 " + id);
        return r.toString();
    }
    /**
     * r250：首页常是跳转壳（meta refresh / location.href / location.replace），跟随一次拿真页面。
     * 只在首页极短且几乎没链接时触发，避免对正常站点多发请求。
     *
     * <p>r520：补 **JS 中转页**（hsck.net 系）。该站首页 423 字节：
     * <pre>
     * var strU="https://32d233.com:8899/?u="+window.location+"&p="+window.location.pathname+window.location.search;
     * hao123.href=strU; document.getElementById("hao123").click();
     * </pre>
     * 网关再 302 到轮换域名（333.9986hsck.cc）。纯 HTTP 只能拿到这段脚本，抽 0 条 → 分类空/播不了。
     * 这里把前缀 + 当前地址拼成网关地址跟一跳，落地后**把 root 换成落地域名**，
     * 否则后续相对链接（/v5/xxx-1-1.html、/vodtype/8.html）仍指向中转域名，全部 404。
     */
    private String followShell(String html) {
        return followShell(html, homeUrl());
    }

    private String followShell(String html, String curUrl) {
        if (TextUtils.isEmpty(html)) return html;
        String target = jumpShellTarget(html, curUrl);
        if (TextUtils.isEmpty(target)) {
            if (html.length() > 8000 || countHref(html) > 5) return html;
            Matcher meta = Pattern.compile("<meta[^>]+http-equiv=[\"']?refresh[\"']?[^>]+url=([^\"'>\\s]+)", Pattern.CASE_INSENSITIVE).matcher(html);
            if (meta.find()) target = meta.group(1);
            if (TextUtils.isEmpty(target)) {
                Matcher js = Pattern.compile("location(?:\\.href)?\\s*=\\s*[\"']([^\"']{4,200})[\"']|location\\.replace\\(\\s*[\"']([^\"']{4,200})[\"']").matcher(html);
                if (js.find()) target = js.group(1) != null ? js.group(1) : js.group(2);
            }
        }
        if (TextUtils.isEmpty(target) || target.startsWith("javascript")) return html;
        String url = abs(target);
        String next = get(url);
        log("首页跳转壳 → 跟随 " + url + " len=" + (next == null ? 0 : next.length()));
        if (TextUtils.isEmpty(next)) return html;
        // r520：落地域名与配置域名不同 → 换基址（中转网关 302 到轮换域名，相对链接必须按新域名拼）
        String landedHost = hostOf(lastFetchUrl);
        if (!TextUtils.isEmpty(landedHost) && !landedHost.equalsIgnoreCase(host) && next.length() > 400) {
            String old = root;
            // r532：同步登记全局「入口 host → 落地域名」映射。r520 旧代码只 setBase 当前实例，
            // detail/player/category 是各自独立的新实例，root 仍是入口壳域名 → 分类页拿到 423 中转壳、
            // 播放页相对链接拼错 host。静态映射让所有实例 detect() 时自动对齐到落地域名。
            TRANSIT_LANDED.put(hostOf(old).toLowerCase(Locale.ROOT), schemeHost(lastFetchUrl));
            setBase(lastFetchUrl);
            log("跳转壳换基址 " + old + " → " + root);
        }
        return next;
    }

    /**
     * r520：识别 `"https://gw:port/?u="+window.location` 这类中转脚本，拼出可访问的网关地址。
     * 只在页面极短（&lt;4000 字节）时认，避免把正文里的同类拼接串当跳转。
     */
    private String jumpShellTarget(String html, String curUrl) {
        if (html.length() > 4000) return "";
        Matcher m = Pattern.compile("([\"'])(https?://[^\"']{6,300}?)\\1\\s*\\+\\s*(?:window\\.|document\\.)?location").matcher(html);
        if (!m.find()) return "";
        String pre = m.group(2);
        String here = TextUtils.isEmpty(curUrl) ? homeUrl() : curUrl;
        String out = pre + here;
        if (html.contains("&p=")) {
            String path = here.replaceFirst("^https?://[^/]+", "");
            out = out + "&p=" + (TextUtils.isEmpty(path) ? "/" : path);
        }
        return out;
    }

    // ------------------------------------------------------------------ 搜索端点发现（r349）

    /**
     * r349：搜索端点发现 —— 三层递进（成本递增，命中即停）：
     * 1) 首页搜索表单（零请求）：&lt;form action="A" method=get&gt; + &lt;input name="N"&gt;（meizi5 实测命中 /?s={kw}）；
     * 2) 首页内联 JS：location.assign/href='…search…' + 拼接（部分站）；
     * 3) 外链 JS（最多 2 个请求）：form 的 action 赋值 + document.form.input.value（faloo 实测命中
     *    b.faloo.com/l/0/1.html?t=0&k={kw}）；location.assign 拼接（gaze.red 实测命中 /filter?search={kw}）。
     * 结果存 searchEndpoint 并静态缓存 SEARCH_ENDPOINT（host key），后续实例零成本复用。
     */

    /** r415：数 vod_play_url 里的集数（`名$地址` 用 # 分隔）。 */
    private static int countEps(String playUrl) {
        if (TextUtils.isEmpty(playUrl)) return 0;
        int c = 1;
        for (int i = 0; i < playUrl.length(); i++) if (playUrl.charAt(i) == '#') c++;
        return playUrl.contains("$") ? c : 0;
    }
    private void discoverSearch() {
        try {
            if (TextUtils.isEmpty(root)) return;
            String cached = SEARCH_ENDPOINT.get(host);
            if (cached != null) {
                searchEndpoint = cached;
                log("搜索端点复用缓存 " + cached);
                return;
            }
            if (TextUtils.isEmpty(homeHtml) || homeHtml.length() < 500) return;
            String found = findSearchFromForm(homeHtml);
            // push v44：站方在首页 JSON-LD 里声明的搜索地址（WebSite → SearchAction.target）—— P站实证
            if (TextUtils.isEmpty(found)) found = findSearchFromSearchAction(homeHtml);
            if (TextUtils.isEmpty(found)) found = findSearchFromJs(homeHtml);
            // push v44：内联脚本 location.href = `…/search/${kw}` 路径型模板串 —— MissAV 实证
            if (TextUtils.isEmpty(found)) found = findSearchFromPathTemplate(homeHtml);
            if (TextUtils.isEmpty(found)) found = findSearchFromLinkedJs();
            if (!TextUtils.isEmpty(found)) {
                searchEndpoint = found;
                SEARCH_ENDPOINT.put(host, found);
                log("搜索端点发现 " + found);
            }
        } catch (Throwable e) {
            log("搜索端点发现异常 " + e);
        }
    }

    /** 表单发现：&lt;form action="A"&gt; 内含 &lt;input name="N"&gt;（非 hidden/token），GET 型。 */
    private String findSearchFromForm(String html) {
        Matcher fm = Pattern.compile("<form\\s[^>]{0,300}action=[\"']([^\"'\\s]{2,200})[\"'][^>]{0,300}>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (fm.find()) {
            String action = fm.group(1);
            if (action.startsWith("javascript") || action.startsWith("#")) continue;
            String lowHtml = html.toLowerCase(Locale.ROOT);
            int end = lowHtml.indexOf("</form>", fm.end());
            int segEnd = end > 0 ? Math.min(end, fm.start() + 20000) : Math.min(html.length(), fm.start() + 20000);
            String seg = html.substring(fm.start(), segEnd);
            String lowSeg = seg.toLowerCase(Locale.ROOT);
            // POST 表单（ASP.NET runat=server 等）GET 不了，跳过
            if (lowSeg.contains("method=\"post\"") || lowSeg.contains("method='post'")) continue;
            Matcher im = Pattern.compile("<input\\s[^>]{0,300}name=[\"']([A-Za-z][A-Za-z0-9_]{0,24})[\"'][^>]{0,300}>", Pattern.CASE_INSENSITIVE).matcher(seg);
            while (im.find()) {
                String n = im.group(1);
                String tag = im.group(0).toLowerCase(Locale.ROOT);
                if (tag.contains("hidden") || tag.contains("submit") || tag.contains("button")) continue;
                if (n.toLowerCase(Locale.ROOT).matches(".*(token|csrf|email|user|captcha|code|__).*")) continue;
                // 输入框要有搜索语义，否则可能是登录/订阅表单。meizi5 实测线索在 class（form class=searchform、
                // input class=search、value="To search type and hit enter"），type=text 且无中文 —— 只认 type=search 会漏。
                String scope = seg.substring(Math.max(0, im.start() - 300), Math.min(seg.length(), im.end() + 300)).toLowerCase(Locale.ROOT);
                boolean sem = scope.contains("search") || tag.contains("type=\"search\"") || tag.contains("type='search'")
                        || scope.matches("(?s).*[找搜查].*");
                if (!sem) continue;
                return buildSearchUrl(action, n);
            }
        }
        // push v34（S1）：页面上没有可用的 GET 搜索表单时，才认 POST 搜索表单（海洋 CMS 式 /search.php + searchword，奇优实证）。
        return findPostSearchForm(html);
    }

    /**
     * push v34（S1）：POST 搜索表单发现 —— 只认 &lt;form&gt; 标签本身写着 method=post、表单内有带搜索语义的文本框，
     * 且表单内没有密码框/文件框/验证码类字段（登录框、带验证的搜索一律不用）。结构认，不认站名。
     * 端点记成 POST|绝对action|关键词字段|隐藏字段(name=value&amp;… 已按 UTF-8 编码)。
     */
    private String findPostSearchForm(String html) {
        try {
            Matcher fm = Pattern.compile("<form\\s[^>]{0,300}action=[\"']([^\"'\\s]{2,200})[\"'][^>]{0,300}>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            String lowHtml = html.toLowerCase(Locale.ROOT);
            while (fm.find()) {
                String action = fm.group(1);
                if (action.startsWith("javascript") || action.startsWith("#")) continue;
                String tag0 = fm.group(0).toLowerCase(Locale.ROOT);
                if (!Pattern.compile("method\\s*=\\s*[\"']?post").matcher(tag0).find()) continue;
                int end = lowHtml.indexOf("</form>", fm.end());
                int segEnd = end > 0 ? Math.min(end, fm.start() + 20000) : Math.min(html.length(), fm.start() + 20000);
                String seg = html.substring(fm.start(), segEnd);
                String lowSeg = seg.toLowerCase(Locale.ROOT);
                if (Pattern.compile("type=[\"']?(password|file)").matcher(lowSeg).find()
                        || Pattern.compile("captcha|验证码|vcode|geetest|recaptcha|turnstile").matcher(lowSeg).find()) {
                    log("搜索 POST 表单跳过（含登录/验证字段）" + action);
                    continue;
                }
                String kwName = null;
                StringBuilder hidden = new StringBuilder();
                Matcher im = Pattern.compile("<input\\s[^>]{0,300}name=[\"']([A-Za-z][A-Za-z0-9_]{0,24})[\"'][^>]{0,300}>", Pattern.CASE_INSENSITIVE).matcher(seg);
                while (im.find()) {
                    String n = im.group(1);
                    String tag = im.group(0).toLowerCase(Locale.ROOT);
                    if (tag.contains("hidden")) {
                        Matcher vm = Pattern.compile("value=[\"']([^\"']*)", Pattern.CASE_INSENSITIVE).matcher(im.group(0));
                        String v = vm.find() ? unescapeHtml(vm.group(1)) : "";
                        if (hidden.length() > 0) hidden.append('&');
                        hidden.append(URLEncoder.encode(n, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"));
                        continue;
                    }
                    if (tag.contains("submit") || tag.contains("button")) continue;
                    if (n.toLowerCase(Locale.ROOT).matches(".*(token|csrf|email|user|captcha|code|pass|__).*")) continue;
                    String scope = seg.substring(Math.max(0, im.start() - 300), Math.min(seg.length(), im.end() + 300)).toLowerCase(Locale.ROOT);
                    boolean sem = scope.contains("search") || tag.contains("type=\"search\"") || tag.contains("type='search'")
                            || scope.matches("(?s).*[找搜查].*");
                    if (kwName == null && sem) kwName = n;
                }
                if (kwName == null) continue;
                String a = action;
                if (!a.startsWith("http") && !a.startsWith("/")) a = "/" + a;
                if (!a.startsWith("http")) a = abs(a);
                return "POST|" + a + "|" + kwName + "|" + hidden;
            }
        } catch (Throwable e) {
            log("POST 表单发现异常 " + e);
        }
        return "";
    }

    /** push v34（S1）：页面字符集（meta charset 为 gb* 则 GBK，否则 UTF-8）——POST 关键词按它编码。 */
    private static String pageCharset(String html) {
        if (TextUtils.isEmpty(html)) return "UTF-8";
        String head = html.substring(0, Math.min(html.length(), 4096)).toLowerCase(Locale.ROOT);
        int mi = head.indexOf("charset=");
        if (mi >= 0) {
            String mc = head.substring(mi + 8).replaceAll("^[\"']", "").replaceAll("[\"'/>\\s;].*$", "");
            if (mc.startsWith("gb")) return "GBK";
        }
        return "UTF-8";
    }

    /**
     * push v34（S1）：按 POST 端点提交搜索。隐藏字段原样带、关键词按页面字符集编码，带当前 UA 与 Referer=首页；
     * 3xx 跟随（改用 GET 取落地页）。响应走 get() 同一套搜索验证判定（429/验证页/拦截页 → 整站放弃，不重试不渲染）。
     */
    private String postSearch(String ep, String key) {
        try {
            if (noRender && (challenged || aborted)) return "";
            String[] parts = ep.split("\\|", -1);
            if (parts.length < 3) return "";
            String url = parts[1];
            String cs = pageCharset(homeHtml);
            String body = (parts.length > 3 && !parts[3].isEmpty() ? parts[3] + "&" : "")
                    + URLEncoder.encode(parts[2], "UTF-8") + "=" + URLEncoder.encode(key, cs);
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", homeUrl())
                    .post(okhttp3.RequestBody.create(body, okhttp3.MediaType.parse("application/x-www-form-urlencoded")));
            String shCk = SHIELD_COOKIE.get(hostOf(url).toLowerCase(Locale.ROOT));
            String jsCk = JS_COOKIE.get(hostOf(url).toLowerCase(Locale.ROOT));
            String ckHeader = TextUtils.isEmpty(shCk) ? jsCk : (TextUtils.isEmpty(jsCk) ? shCk : shCk + "; " + jsCk);
            if (!TextUtils.isEmpty(ckHeader)) rb.header("Cookie", ckHeader);
            try (Response res = OkHttp.noRedirect(15000).newCall(rb.build()).execute()) {
                int code = res.code();
                if (noRender && code == 429) {
                    markSearchChallenge(url, "HTTP 429");
                    return "";
                }
                if (code >= 300 && code < 400) {
                    String loc = res.header("Location");
                    if (TextUtils.isEmpty(loc)) return "";
                    return get(abs(loc, url));
                }
                ResponseBody rbody = res.body();
                if (rbody == null) return "";
                byte[] bytes = rbody.bytes();
                String charset = cs;
                String ct = res.header("Content-Type");
                if (ct != null) {
                    String c = ct.toLowerCase(Locale.ROOT);
                    int ci = c.indexOf("charset=");
                    if (ci >= 0) {
                        String hc = c.substring(ci + 8).split("[;\\s]")[0];
                        charset = hc.startsWith("gb") ? "GBK" : hc;
                    }
                }
                String out;
                try {
                    out = new String(bytes, charset);
                } catch (Throwable t) {
                    out = new String(bytes, "UTF-8");
                }
                out = deobfuscate(out);
                if (noRender && searchGiveUp(url, url, code, out)) return "";
                lastFetchUrl = url;
                return unwrapEncodedPage(out);
            }
        } catch (Throwable e) {
            log("搜索 POST 异常 " + e);
            return "";
        }
    }

    /** JS 跳转发现：location.assign/href/replace('URL' + 拼接) 或反引号模板字面量（`/filter?search=${kw}`），且 URL 含搜索参数名。 */
    /**
     * push v44：JSON-LD 搜索声明 —— &lt;script type="application/ld+json"&gt; 里 "@type":"SearchAction" 的
     * target（字符串，或 EntryPoint 对象的 urlTemplate），把 query-input 声明的占位名（缺省任意 {名}）换成 {kw}。
     * 只收与本站同主域的地址（防把 Google 等外站当端点）。结构认，不认站名。P站实证：
     * target = https://cn.pornhub.com/video/search?search={search_term_string}。
     */
    private String findSearchFromSearchAction(String html) {
        try {
            if (TextUtils.isEmpty(html) || !html.contains("SearchAction")) return "";
            Matcher sm = Pattern.compile("<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
            while (sm.find()) {
                String js = sm.group(1);
                int at = js.indexOf("SearchAction");
                if (at < 0) continue;
                String seg = js.substring(at, Math.min(js.length(), at + 1500)).replace("\\/", "/");
                String target = firstMatchGroup(seg, "\"urlTemplate\"\\s*:\\s*\"([^\"]{8,300})\"");
                if (TextUtils.isEmpty(target)) target = firstMatchGroup(seg, "\"target\"\\s*:\\s*\"([^\"]{8,300})\"");
                if (TextUtils.isEmpty(target)) continue;
                String name = firstMatchGroup(seg, "name=([A-Za-z0-9_]{1,40})");
                String tpl;
                if (!TextUtils.isEmpty(name) && target.contains("{" + name + "}")) tpl = target.replace("{" + name + "}", "{kw}");
                else tpl = target.replaceFirst("\\{[A-Za-z0-9_]{1,40}\\}", "{kw}");
                if (!tpl.contains("{kw}")) continue;
                if (!tpl.startsWith("http")) tpl = abs(tpl);
                if (!sameSiteHost(hostOf(tpl), hostOf(root))) {
                    log("搜索端点(SearchAction) 非本站地址，跳过 " + tpl);
                    continue;
                }
                log("搜索端点发现(SearchAction) " + tpl);
                return tpl;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** push v44：两个 host 是否同主域（去掉 www./语言子域等，比较末两段；任一为空视为否）。 */
    private static boolean sameSiteHost(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return false;
        a = a.toLowerCase(Locale.ROOT);
        b = b.toLowerCase(Locale.ROOT);
        if (a.equals(b)) return true;
        String[] pa = a.split("\\."), pb = b.split("\\.");
        if (pa.length < 2 || pb.length < 2) return false;
        return (pa[pa.length - 2] + "." + pa[pa.length - 1]).equals(pb[pb.length - 2] + "." + pb[pb.length - 1]);
    }

    /**
     * push v44：内联脚本里的路径型搜索跳转 —— `location.href = 条件 ? \`/search/${kw}\` : \`/${this.locale}/search/${kw}\``
     * （MissAV 首页 Vue 片段实证）。旧 findSearchFromJs 只认「location.href(\`…\`」紧跟模板串、且以 参数名= 结尾的形态，
     * 这种「赋值 + 三元 + 路径末段是关键词」认不出。规则：取 location.(href|assign|replace) 赋值语句里的模板串，
     * 末个 ${…} 前须以 /search/ 结尾（不分大小写）；只含这一个占位的模板优先（站方默认语言分支），
     * 都带别的占位时，用页面里 locale: '<值>' 代入 ${this.locale}/${locale}，代不了就放弃。结构认，不认站名。
     */
    private String findSearchFromPathTemplate(String html) {
        try {
            if (TextUtils.isEmpty(html)) return "";
            Matcher am = Pattern.compile("location\\.(?:href|assign|replace)\\s*(?:=|\\()([^;\\n]{4,400})").matcher(html);
            String localized = "";
            String plain = "";
            // FIX-A：站方三元按「当前语言 === 默认语言」选分支；页面当前语言≠默认语言时，用户看的是本地化页，取本地化分支
            String pageLoc = firstMatchGroup(html, "[{,\\s]locale\\s*:\\s*['\"]([A-Za-z-]{2,8})['\"]");
            String fbLoc = firstMatchGroup(html, "fallbackLocale\\s*:\\s*['\"]([A-Za-z-]{2,8})['\"]");
            boolean preferLocalized = !TextUtils.isEmpty(pageLoc) && !TextUtils.isEmpty(fbLoc) && !pageLoc.equals(fbLoc);
            while (am.find()) {
                String expr = am.group(1);
                Matcher tm = Pattern.compile("`([^`]{4,200})`").matcher(expr);
                while (tm.find()) {
                    String t = tm.group(1);
                    int last = t.lastIndexOf("${");
                    if (last <= 0 || t.indexOf('}', last) != t.length() - 1) continue;
                    String head = t.substring(0, last);
                    if (!head.toLowerCase(Locale.ROOT).endsWith("/search/")) continue;
                    if (!head.contains("${")) {
                        String tpl = abs(head + "{kw}");
                        if (preferLocalized && am.group(1).contains("fallbackLocale")) { if (TextUtils.isEmpty(plain)) plain = tpl; continue; }
                        log("搜索端点发现(脚本模板) " + tpl);
                        return tpl;
                    }
                    if (TextUtils.isEmpty(localized)) {
                        String loc = firstMatchGroup(html, "[{,\\s]locale\\s*:\\s*['\"]([A-Za-z-]{2,8})['\"]");
                        if (!TextUtils.isEmpty(loc)) {
                            String h2 = head.replace("${this.locale}", loc).replace("${locale}", loc);
                            if (!h2.contains("${")) localized = abs(h2 + "{kw}");
                        }
                    }
                }
            }
            if (!TextUtils.isEmpty(localized)) {
                log("搜索端点发现(脚本模板) " + localized + (preferLocalized ? "（页面语言 " + pageLoc + " ≠ 默认 " + fbLoc + "，取本地化分支）" : ""));
                return localized;
            }
            if (!TextUtils.isEmpty(plain)) {
                log("搜索端点发现(脚本模板) " + plain);
                return plain;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String findSearchFromJs(String js) {
        Matcher m = Pattern.compile("location\\.(?:assign|href|replace)\\s*\\(?\\s*[\"']([^\"']{4,200})[\"']\\s*\\+").matcher(js);
        while (m.find()) {
            String tpl = searchTemplateOf(m.group(1));
            if (!TextUtils.isEmpty(tpl)) return tpl;
        }
        // gaze.red 实测形态：window.location.assign(`/filter?search=${encodeURIComponent(t)}`) —— 模板字面量
        Matcher t = Pattern.compile("location\\.(?:assign|href|replace)\\s*\\(?\\s*`([^`]{4,200})`").matcher(js);
        while (t.find()) {
            String tpl = searchTemplateOf(t.group(1));
            if (!TextUtils.isEmpty(tpl)) return tpl;
        }
        Matcher w = Pattern.compile("window\\.open\\s*\\(\\s*[\"']([^\"']{4,200})[\"']\\s*\\+").matcher(js);
        while (w.find()) {
            String tpl = searchTemplateOf(w.group(1));
            if (!TextUtils.isEmpty(tpl)) return tpl;
        }
        return "";
    }

    /** URL 片段 → 搜索模板：以 参数名= 结尾且参数名是常见搜索词，返回 …参数名={kw}。
     *  支持两种形态：引号拼接（'/filter?search=' + kw）与模板字面量（`/filter?search=${…}`，截到 ${ 前）。 */
    private String searchTemplateOf(String urlPart) {
        if (TextUtils.isEmpty(urlPart)) return "";
        String u = urlPart.trim();
        // 模板字面量形态：`/filter?search=${encodeURIComponent(t)}` —— 截到 ${ 占位前
        int ph = u.indexOf("${");
        if (ph > 0) u = u.substring(0, ph);
        // 只认以 = 结尾的「参数值待拼」形态（'/filter?search=' + kw）
        if (!u.endsWith("=")) return "";
        int q = u.lastIndexOf('?');
        int amp = u.lastIndexOf('&');
        int p = Math.max(q, amp);
        if (p < 0 || p >= u.length() - 1) return "";
        String param = u.substring(p + 1, u.length() - 1).toLowerCase(Locale.ROOT);
        if (!param.matches("(wd|kw|q|s|key|keyword|search|so|word|w)")) return "";
        return u + "{kw}";
    }

    /** 外链 JS 发现：抓首页引用的业务 JS（跳过 CDN/统计/库），在 JS 里找 form action 赋值或 location 跳转。 */
    private String findSearchFromLinkedJs() {
        try {
            List<String> candidates = new java.util.ArrayList<>();
            Matcher sm = Pattern.compile("<script[^>]+src=[\"']([^\"']{2,200})[\"']", Pattern.CASE_INSENSITIVE).matcher(homeHtml);
            while (sm.find()) {
                String src = sm.group(1);
                String low = src.toLowerCase(Locale.ROOT);
                if (low.contains("cdn-cgi") || low.contains("cloudflare") || low.contains("analytics") || low.contains("gtag")
                        || low.contains("baidu") || low.contains("hm.js") || low.contains("jquery") || low.contains("bootstrap")
                        || low.contains("vue") || low.contains("react") || low.contains("swiper")) continue;
                if (!src.startsWith("http") && !src.startsWith("/")) continue;
                candidates.add(src);
            }
            // 名字像业务入口的排前（search/index/main/app/common/util/home）
            candidates.sort((a, b) -> Integer.compare(jsScore(b), jsScore(a)));
            int fetched = 0;
            for (String src : candidates) {
                if (fetched >= 2) break;
                String js = get(abs(src));
                fetched++;
                if (TextUtils.isEmpty(js) || js.length() < 100) continue;
                // a) form action 赋值 + document.formName.input.value（faloo 型）
                Matcher am = Pattern.compile("document\\.(\\w+)\\.action\\s*=\\s*[\"'](https?://[^\"'\\s]{4,200}|/[^\"'\\s]{2,200})[\"']").matcher(js);
                while (am.find()) {
                    String formName = am.group(1);
                    String action = am.group(2);
                    Matcher vm = Pattern.compile("document\\." + Pattern.quote(formName) + "\\.(\\w+)\\.value").matcher(js);
                    if (vm.find()) {
                        String tpl = buildSearchUrl(action, vm.group(1));
                        log("搜索外链JS action发现 " + tpl);
                        return tpl;
                    }
                }
                // b) location 跳转拼接（gaze.red 型）
                String tpl = findSearchFromJs(js);
                if (!TextUtils.isEmpty(tpl)) {
                    log("搜索外链JS跳转发现 " + tpl);
                    return tpl;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static int jsScore(String src) {
        String low = src.toLowerCase(Locale.ROOT);
        if (low.contains("search")) return 6;
        if (low.contains("index")) return 5;
        if (low.contains("main") || low.contains("app")) return 4;
        if (low.contains("common") || low.contains("util") || low.contains("home")) return 3;
        return 0;
    }

    /** action + 参数名 → 搜索模板（action 已带 query 用 &，否则 ?；相对地址转绝对）。 */
    private String buildSearchUrl(String action, String param) {
        String a = action;
        if (!a.startsWith("http") && !a.startsWith("/")) a = "/" + a;
        if (!a.startsWith("http")) a = abs(a);
        String sep = a.contains("?") ? "&" : "?";
        return a + sep + param + "={kw}";
    }

    /**
     * push v50（Liu 2026-11-25 长段文字搜索）：把关键词填进搜索模板的 {kw}。
     * URLEncoder 把空格编成「+」——「+」只在问号之后的查询串里表示空格；在路径段里（MissAV /search/{kw}、
     * 苹果CMS /vodsearch/{kw}----------1---.html、脚本路径型模板）是字面加号，站方会去搜「A+B」。
     * 所以 {kw} 位于问号之前时把「+」换成 %20（关键词里真正的加号已被编成 %2B，不受影响）；问号之后照旧。
     * 不改关键词本身、不去空格。
     */
    static String fillKw(String tpl, String key) {
        if (tpl == null) return null;
        String enc;
        try {
            enc = URLEncoder.encode(key == null ? "" : key, "UTF-8");
        } catch (Exception e) {
            enc = key == null ? "" : key;
        }
        String pathEnc = enc.replace("+", "%20");
        int q = tpl.indexOf('?');
        StringBuilder sb = new StringBuilder();
        int from = 0, at;
        while ((at = tpl.indexOf("{kw}", from)) >= 0) {
            sb.append(tpl, from, at).append(q < 0 || at < q ? pathEnc : enc);
            from = at + 4;
        }
        sb.append(tpl.substring(from));
        return sb.toString();
    }

    /**
     * r355：给搜索 URL 带上页码（已替换好 {kw} 的成品 URL）。
     * query 型（含 ?）→ 追加 &page=N；maccms rewrite 路径型 → 把 /vodsearch/{kw}----------1---.html
     * 里的那个 1 换成页码（nnyy 实测分页位就在这个横线序列中间）。不认识的形态原样返回。
     */
    private String applySearchPage(String u, int page) {
        if (page <= 1 || TextUtils.isEmpty(u)) return u;
        if (u.contains("page=")) return u;
        if (u.contains("?")) return u + "&page=" + page;
        Matcher m = Pattern.compile("^(.*?)(-+)(\\d+)(-+)(\\.html.*)$").matcher(u);
        if (m.matches()) return m.group(1) + m.group(2) + page + m.group(4) + m.group(5);
        return u;
    }

    /**
     * r349：带 Cap 验证的 GET —— 搜索页也会被挑战页挡（沙箱实证 gaze.red /filter?search= 返回 14908 字
     * cap.js 壳、无一条条目）。与 detailContent r285 同姿势：错误页/cap-widget → solveCapCookie → 带 cookie 重取。
     * retry 只要不再是错误页/挑战页就采用（搜索结果页可能比挑战壳短，不套用 detail 的长度判据）。
     */
    private String getCap(String url) {
        String html = get(url);
        if (noRender && challenged) return "";
        // muse v31：搜索路径只在页面真有 Cap 组件时才走现成求解（光是错误页不求解——每个候选路径都去求解会白耗 15 秒预算）。
        if (noRender && (html == null || !html.contains("cap-widget"))) return html;
        if (isErrorPage(html) || (html != null && html.contains("cap-widget"))) {
            String capHost = schemeHost(url);
            String ck = CAP_COOKIE.getOrDefault(capHost, "");
            if (TextUtils.isEmpty(ck)) {
                ck = solveCapCookie(capHost);
                if (!TextUtils.isEmpty(ck)) CAP_COOKIE.put(capHost, ck);
            } else {
                log("search cap cookie 复用缓存（跳过求解）");
            }
            if (!TextUtils.isEmpty(ck)) {
                Map<String, String> ch = new LinkedHashMap<>();
                ch.put("User-Agent", UA);
                ch.put("Referer", schemeHost(url) + "/");
                ch.put("Cookie", ck);
                String retry = OkHttp.string(url, ch);
                boolean retryBad = TextUtils.isEmpty(retry) || isErrorPage(retry) || retry.contains("cap-widget");
                if (!retryBad) {
                    log("search cap 已通过，取到真实搜索页 " + retry.length() + " 字");
                    html = retry;
                    lastFetchCode = 200;
                    if (noRender) autoPassed = true;
                }
            }
            // muse v31（Liu 10:38/10:41）：搜索页要验证、现成 Cap 求解/缓存 cookie 也没过 → 该站需验证，放弃。
            if (noRender && html != null && html.contains("cap-widget")) {
                markSearchChallenge(url, "搜索页 Cap 验证未过");
                return "";
            }
        }
        return html;
    }

    private static int countHref(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        Matcher m = Pattern.compile("href=").matcher(html);
        int n = 0;
        while (m.find() && n < 9999) n++;
        return n;
    }

    /** r250：取去标签后的正文样本（用于判断首页是占位/反爬页还是真页面）。 */
    private static String textSample(String html, int max) {
        if (TextUtils.isEmpty(html)) return "";
        String t = html.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** r249：抽前 N 个站内相对链接做样本，供真机日志定位分类路由（不落整页 HTML）。 */
    private static String hrefSample(String html, int max) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher m = Pattern.compile("href=\"(/[^\"?#]{1,60})\"").matcher(html);
        while (m.find() && set.size() < max) set.add(m.group(1));
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() + s.length() > 700) break;
            sb.append(s).append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 首页

    @Override
    public String homeContent(boolean filter) {
        LogCategory previousStage = enterStage(LogCategory.MEDIA);
        try {
            return homeContentStaged(filter);
        } finally {
            leaveStage(previousStage);
        }
    }

    private String homeContentStaged(boolean filter) {
        try {
            detect();
            JSONObject result = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : classes.entrySet()) {
                JSONObject c = new JSONObject();
                c.put("type_id", e.getKey());
                c.put("type_name", e.getValue());
                arr.put(c);
            }
            result.put("class", arr);
            result.put("filters", new JSONObject());
            JSONArray list = "zanpian".equals(template) ? zanpianItems(homeHtml, 24) : genericItems(homeHtml, 24);
            if (list.length() > 0) result.put("list", list);
            // r470：页面抓到了但分类与片单全空（JS 跳板壳 / 防红壳 / href=0）→ 同样降级浏览器模式卡
            else if (arr.length() == 0) return browserFallback("页面无可抽取内容");
            return result.toString();
        } catch (Throwable e) {
            // r470：抓取异常（RST / 超时 / DNS）不再返回空 —— 降级浏览器模式卡，站点仍可开（WebView + 壳播放器）
            return browserFallback(e.getClass().getSimpleName());
        }
    }

    @Override
    public String homeVideoContent() {
        LogCategory previousStage = enterStage(LogCategory.MEDIA);
        try {
            return homeVideoContentStaged();
        } finally {
            leaveStage(previousStage);
        }
    }

    private String homeVideoContentStaged() {
        try {
            detect();
            JSONArray list = zanpianItems(homeHtml, 30);
            if (list.length() == 0) list = genericItems(homeHtml, 30);
            return new JSONObject().put("list", list).toString();
        } catch (Throwable e) {
            return "{\"list\":[]}";
        }
    }

    /**
     * r470：HTTP 模式抓取失败（连接被拒 / 超时 / 防红壳 / JS 跳板壳抽不出内容）时的自动降级 ——
     * 返回一张「浏览器模式」卡（vod_id 携带 {@code browser} 类型与本站首页），点击进 {@code BrowserActivity}
     * 用 WebView 打开站点（跑 JS 跟跳板、UA 过滤站也能进），网页视频可转壳播放器。
     * 分流语义与用户确认的架构一致：HTTP 抓得到 → 正常网页源；失败信号明确 → 落浏览器模式，秒级判定不干耗。
     */
    private String browserFallback(String why) {
        try {
            String url = homeUrl();
            JSONObject id = new JSONObject();
            id.put("type", "browser");
            id.put("url", url);
            id.put("title", hostOf(url));
            JSONObject card = new JSONObject();
            card.put("vod_id", id.toString());
            card.put("vod_name", "浏览器打开 " + hostOf(url));
            card.put("vod_pic", "");
            card.put("vod_remarks", why);
            JSONArray list = new JSONArray();
            list.put(card);
            log("HTTP 模式失败（" + why + "）→ 浏览器模式卡：" + url);
            return new JSONObject().put("class", new JSONArray()).put("filters", new JSONObject()).put("list", list).toString();
        } catch (Throwable t) {
            return "{\"class\":[],\"filters\":{}}";
        }
    }

    // ------------------------------------------------------------------ 分类

    /**
     * r329：从列表页解析**真实最大页码**。
     * 之前 pagecount/total 是本地估算（page+1 / page*20+len），而飞卢听书分类实际有 3 页、
     * 约 90 本（沙箱实测 category/1/1.html：30 卡片 + 下一页/[02]/[03]），
     * 壳却只给 2 页 50 条 —— 用户看到的就是「每个分类就这么点内容」。
     */
    private int maxPageNo(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        int max = 0;
        Matcher m = Pattern.compile("/(?:category|list|type|vodshow|vodtype|vodlist|show)/[^/\"']{0,24}/?(\\d{1,4})\\.html?").matcher(html);
        while (m.find()) {
            try {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            } catch (Throwable ignored) {
            }
        }
        Matcher m2 = Pattern.compile(">\\s*\\[(\\d{1,4})\\]\\s*<").matcher(html);
        while (m2.find()) {
            try {
                max = Math.max(max, Integer.parseInt(m2.group(1)));
            } catch (Throwable ignored) {
            }
        }
        return max;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        LogCategory previousStage = enterStage(LogCategory.MEDIA);
        try {
            return categoryContentStaged(tid, pg, filter, extend);
        } finally {
            leaveStage(previousStage);
        }
    }

    private String categoryContentStaged(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            detect();
            if ("nuxt-json-aes".equals(template)) return nuxtCategory(tid, parseInt(pg, 1));
            if ("jsdata".equals(template)) return jsdataCategory(tid, parseInt(pg, 1));
            int page = parseInt(pg, 1);
            JSONArray list;
            String pageHtml = "";
            if ("youtube".equals(template)) {
                // r359：youtube 分类=搜索式（tid 即搜索词）。搜索无翻页（continuation 需 INNERTUBE POST），返回第 1 页。
                list = youtubeSearchItems(tid, 20);
                JSONObject result = new JSONObject();
                result.put("page", 1);
                result.put("pagecount", 1);
                result.put("total", list.length());
                result.put("limit", 20);
                result.put("list", list);
                return result.toString();
            }
            if ("zanpian".equals(template)) {
                pageHtml = get(zanpianListUrl(tid, page));
                list = zanpianItems(pageHtml, 0);
            } else if ("maccmsphp".equals(template)) {
                pageHtml = get(maccmsPhpListUrl(tid, page));
                list = maccmsPhpItems(pageHtml, 0);
                // r457：形态自适应兜底 —— 判型命中但专有判据抽 0 条时，用形态自适应抽取再试一次
                if (list.length() == 0 && !TextUtils.isEmpty(pageHtml)) {
                    list = maccmsItems(pageHtml, 0);
                    if (list.length() > 0) log("maccmsphp 判据 0 条 → 形态自适应兜底 " + list.length() + " 条");
                }
            } else if ("maccms".equals(template)) {
                pageHtml = get(maccmsListUrl(tid, page));
                list = maccmsItems(pageHtml, 0);
                // r457：同上 —— 形态自适应仍 0 条时，用通用模式自学习兜底
                if (list.length() == 0 && !TextUtils.isEmpty(pageHtml)) {
                    list = genericItems(pageHtml, 0);
                    if (list.length() > 0) log("maccms 形态自适应 0 条 → 通用自学习兜底 " + list.length() + " 条");
                }
                // r510：V10 双路由回退（见 maccmsTypeUrl 注释）
                if (list.length() == 0 && page <= 1 && hasSoftEmptyRoute(tid)) {
                    String alt = maccmsTypeUrl(tid);
                    if (!TextUtils.isEmpty(alt)) {
                        String h2 = get(alt);
                        JSONArray r2 = maccmsItems(h2, 0);
                        if (r2.length() == 0) r2 = genericItems(h2, 0);
                        if (r2.length() > 0) {
                            list = r2;
                            pageHtml = h2;
                            log("分类 vodshow 空页 → vodtype 回退 " + alt + " → " + r2.length() + " 条");
                        }
                    }
                }
            } else {
                pageHtml = genericPanelHtml(tid, page);
                // r388：**付费墙分类兜底** —— 探测阶段没覆盖到的分类（单条板块/新增分类），
                // 抓到的就是「内容解锁」墙页时直接返回空，别把导航链接当条目
                //（真机 47o：total=2、名字叫「内容解锁」）。
                if (isPaywallPage(pageHtml)) {
                    String g = paywallGroupOf(tid);
                    PAYWALL_GROUPS.put(paywallCacheKey(g.isEmpty() ? "#" + tid : g), System.currentTimeMillis());
                    log("分类命中付费墙 tid=" + tid + " → 返回空（站方需登录/付费解锁）");
                    JSONObject pw = new JSONObject();
                    pw.put("page", page);
                    pw.put("pagecount", 0);
                    pw.put("total", 0);
                    pw.put("limit", 20);
                    pw.put("list", new JSONArray());
                    return pw.toString();
                }
                // r360 C增量：JS 加密壳站（ap-d 型）HTTP 抓到的必是密文壳，genericItems 必空
                // ——先跳过 HTTP 抽取直接进渲染兜底（省掉必空的抽取+候选探测白跑；真机 b7b88
                // HTTP 阶段白耗 5-10 秒才轮到渲染）。isEncryptedShell 判据极窄（id="ap-d" 密文块），
                // 常规站零影响。渲染兜底自身有 PANEL_CACHE 缓存，二次进入秒回。
                boolean shellSite = isEncryptedShell(pageHtml);
                if (shellSite) log("JS 加密壳站 → 分类跳过 HTTP 抽取直走渲染 tid=" + tid);
                list = shellSite ? new JSONArray() : genericItems(pageHtml, 0);
                // r510：V10 双路由回退 —— tid 是 vodshow/vodlist 且抽 0 条时，/vodtype/{id}.html 再试一次。
                // 必须放在「空列表容器」判定之前：mtyy2 的 vodshow 空页本身就带空容器与「暂无」文案，
                // 放后面会被当「站方本来没数据」直接返回空（用户看到的就是分类全空）。
                if (!shellSite && list.length() == 0 && page <= 1 && hasSoftEmptyRoute(tid)) {
                    String alt = maccmsTypeUrl(tid);
                    if (!TextUtils.isEmpty(alt) && !alt.equals(abs(tid))) {
                        try {
                            String h2 = get(alt);
                            JSONArray r2 = maccmsItems(h2, 0);
                            if (r2.length() == 0) r2 = genericItems(h2, 0);
                            if (r2.length() > 0) {
                                list = r2;
                                pageHtml = h2;
                                log("分类 vodshow 空页 → vodtype 回退 " + alt + " → " + r2.length() + " 条");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                // r410：**空列表容器识别** —— 容器在但 0 条目 = 站方该分类本来没数据。
                // lu9936 实证：分类 36 的页面是 `<ul class="stui-vodlist clearfix"> </ul>`，
                // 第 1 页 / 第 2 页 / 换排序（最热、评分）全部 0 条，服务端就是空的。
                // 以前这种情况会去渲染兜底，白等 3~9 秒仍抽 0 条（用户看到的就是"分类没内容且卡"）。
                if (!shellSite && list.length() == 0 && isEmptyListPage(pageHtml)) {
                    log("分类为空数据页（列表容器在、0 条目）tid=" + tid + " → 不做渲染兜底");
                    JSONObject empty = new JSONObject();
                    empty.put("page", page);
                    empty.put("pagecount", 0);
                    empty.put("total", 0);
                    empty.put("limit", 20);
                    empty.put("list", new JSONArray());
                    return empty.toString();
                }
                // r343：**分类渲染兜底** —— safeline WAF 站（4kcz）HTTP 直连分类页全是 403 挑战页
                //（沙箱实测 /dbtop250/、/movie/849.html 均 15603B 挑战壳，r342 真机 23 个分类全空）。
                // 只在「已抽不到」时才触发：WebView 真跑一遍页面再抽；能正常解析的站零影响。
                // nav- tid 排除：其面板是首页片段，没有可渲染的独立 URL。
                // r344：站点自报空页（schema numberOfItems:0 / 服务端渲染的「暂无短剧，敬请期待」）
                // —— 服务端就是空的，渲染兜底跑两遍 WebView 也变不出条目（真机 duanju 8 个分类
                // 各白等 1.4~4.3s；沙箱+真浏览器实证：JSON-LD itemListElement 空、渲染后 0 卡片、无数据 XHR）。
                // 只挡「站点明确说空」的页 —— 4kcz 的 403 挑战页不含这些标记，渲染兜底照常触发（r343 不可破坏）。
                boolean emptyBySite = list.length() == 0 && isExplicitEmpty(pageHtml);
                if (emptyBySite) log("站点该分类自报为空（schema/暂无标记）→ 跳过渲染兜底 tid=" + tid);
                // r369：**尾斜杠回退** —— 少数站必须带尾斜杠；作为**纯 HTTP** 第二候选，
                // 避免一上来就走 25s 的 WebView 渲染（真机 thisav66「新作上市」就是这么白等了两次）。
                if (list.length() == 0 && page <= 1 && !"all".equals(tid) && !tid.contains("?")
                        && !tid.endsWith(".html") && !tid.endsWith(".htm") && !tid.startsWith("nav-")) {
                    String altSlash = root + "/" + tid + "/";
                    try {
                        String h2 = PANEL_CACHE.get(altSlash);
                        if (TextUtils.isEmpty(h2)) h2 = get(altSlash);
                        if (!TextUtils.isEmpty(h2) && h2.length() > 2000) {
                            JSONArray r2 = genericItems(h2, 0);
                            if (r2.length() > 0) {
                                list = r2;
                                // r375：同步 pageHtml —— 页数解析需要看到真实分类页（tuiimg 的分页块
                                // 在 /meinv/ 页里：list_2/3/4/207.html），404 壳里抽不到真实总页数。
                                pageHtml = h2;
                                log("分类尾斜杠回退 " + altSlash + " → " + r2.length() + " 条");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (list.length() == 0 && !tid.startsWith("nav-") && !emptyBySite) {
                    String rurl = genericListUrl(tid, page);
                    try {
                        String rh = PANEL_CACHE.get(rurl);
                        if (!TextUtils.isEmpty(rh)) {
                            JSONArray rr = genericItems(rh, 0);
                            if (rr.length() > 0) {
                                list = rr;
                                log("分类渲染兜底（缓存） " + rurl + " → " + rr.length() + " 条");
                            }
                        } else {
                            for (int attempt = 1; attempt <= 2; attempt++) {
                                Map<String, String> hd = new LinkedHashMap<>();
                                rh = webFetch(rurl, hd, 25000);
                                if (TextUtils.isEmpty(rh) || rh.length() <= 2000) {
                                    log("分类渲染兜底第" + attempt + "次渲染失败 " + rurl);
                                    continue;
                                }
                                JSONArray rr = genericItems(rh, 0);
                                if (rr.length() > 0) {
                                    list = rr;
                                    if (PANEL_CACHE.size() >= 8) PANEL_CACHE.clear();
                                    PANEL_CACHE.put(rurl, rh);
                                    log("分类渲染兜底 " + rurl + " → " + rr.length() + " 条（" + rh.length() + " 字）");
                                    break;
                                }
                                log("分类渲染兜底第" + attempt + "次抽空（" + rh.length() + " 字）");
                            }
                        }
                    } catch (Throwable e) {
                        log("分类渲染兜底异常 " + e);
                    }
                }
            }
            // r301：分页守门 —— 本页与上一页内容完全相同就判定「分页无效」，直接收尾。
            // 真机 meizi5：page=17..21 的 list 全是同一批（第一条永远 13147.html），pagecount 还每页 +1，
            // 结果就是「翻页永远翻不完、同一批内容反复叠加」。任何站只要命中这条就停止翻页。
            if (page > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o != null) sb.append(o.optString("vod_id")).append(',');
                }
                String ids = sb.toString();
                // r318：改成与「该分类最近一次」比对，而不是只看 page-1。
                // 旧写法 PAGE_IDS.get(tid + "#" + (page-1)) 在用户直接跳到第 5 页时
                // （tid#4 从未请求过）prev 恒为 null → 守门完全失效，重复内容会一路叠下去。
                String prev = PAGE_IDS.get(tid);
                if (prev != null && prev.equals(ids)) {
                    log("分类分页无效（与上一次完全相同）→ 停止翻页 tid=" + tid + " page=" + page);
                    list = new JSONArray();
                } else if (ids.length() > 0) {
                    PAGE_IDS.put(tid, ids);
                }
            }
            // r389（AGENTS.md 第二十节）：Next.js 站封面只在 RSC payload 里，<img src> 是占位 logo → 回填
            enrichPicsFromInlineJson(pageHtml, list);
            // r486：**条目级死页过滤** —— m.x33yq.org 实证 tid=top page=2 抽出 1 条
            //   {name:"“真的很抱歉，我们搞丢了页面……”要不去网站首页看看？", pic:"…/404.png"}：
            //   分页翻过头 → 404 页 → 页面上的"回首页"链接被 genericItems 抽成条目。
            //   isErrorPage 只判页面、isDeadPage 只判 <title>，条目级没有防线。
            //   名字含死页文案 或 图含 404 → 剔除；全部被剔 → 当空页处理（不参与 pagecount/total 虚增）。
            int before = list.length();
            for (int di = list.length() - 1; di >= 0; di--) {
                JSONObject dItem = list.optJSONObject(di);
                if (dItem == null) continue;
                String dn = dItem.optString("vod_name", "");
                String dp = dItem.optString("vod_pic", "");
                if (dn.contains("搞丢") || dn.contains("走丢") || dn.contains("页面不存在") || dn.contains("页面未找到")
                        || dp.matches(".*[/\\\\]404[^/\\\\]*\\.(?:png|jpg|jpeg|gif|webp)$")) {
                    log("剔除死页条目 " + (dn.length() > 40 ? dn.substring(0, 40) : dn));
                    list.remove(di);
                }
            }
            if (before != list.length()) log("死页条目过滤 " + before + "→" + list.length() + " 条 tid=" + tid);
            JSONObject result = new JSONObject();
            result.put("page", page);
            // r330：pagecount/total 不再估算。
            // 实测（沙箱逐页抓取 category/1/1.html）：真实 10 页、约 300 本，第 10 页仍有 30 条且「下一页」消失；
            // 而 maxPageNo 依赖分页控件，控件只显示 [02][03]…（前后各一页），永远取不到真实末页；
            // 逐页探测成本太高（每分类 10 次请求 × 12 个分类）。
            // 关键事实：飞卢翻页内容本身正常（第 9/10 页 id 零重复），不需要预先知道总页数，
            // 只要保证「还能继续翻」即可 —— 翻到空页自然停。
            // PAGE_IDS 内容指纹守门仍然生效（meizi5 那种「每页内容相同」的站会被拦下）。
            result.put("pagecount", list.length() == 0 ? page : page + 1);
            // r375：tuiimg 家族分页块是 /{tid}/list_N.html（尾页 class="end"）。
            // 从真实分类页抽取总页数（需 ≥3 个不同页码且链接目录与 tid 一致，防误判），
            // 否则 UI 只显示 page+1 页，用户不知道能翻 207 页。其它站无 list_N.html 链接，零影响。
            int maxListPage = 0;
            if (list.length() > 0 && !TextUtils.isEmpty(pageHtml)) {
                try {
                    Matcher lm = Pattern.compile("href=\"[^\"]*/" + Pattern.quote(tid) + "/list_(\\d{1,4})\\.html").matcher(pageHtml);
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    while (lm.find()) {
                        seen.add(lm.group(1));
                        maxListPage = Math.max(maxListPage, Integer.parseInt(lm.group(1)));
                    }
                    if (seen.size() < 3) maxListPage = 0;
                } catch (Throwable ignored) {
                }
            }
            if (maxListPage > page) {
                log("分页块解析总页数 " + maxListPage + "（list_N.html）");
                result.put("pagecount", maxListPage);
            }
            // total 用「当前页累计」而不是固定估算：随翻页单调增长，不会让用户误以为「只有 50 条到底了」；
            // 也不做虚假的精确值（站点不提供总条数）。
            result.put("total", list.length() * Math.max(page, 1));
            result.put("limit", list.length() == 0 ? 20 : list.length());
            result.put("list", list);
            return result.toString();
        } catch (Throwable e) {
            return "{\"page\":1,\"pagecount\":1,\"limit\":20,\"total\":0,\"list\":[]}";
        }
    }

    private String zanpianListUrl(String tid, int page) {
        if (page <= 1) return root + "/" + tid + "/";
        return root + "/" + tid + "/1-up-" + page + ".html";
    }

    private String maccmsListUrl(String tid, int page) {
        // r354：tid 可能**已经带 vodshow/ 前缀**（nunuyy 模板的分类导航就是 vodshow/N-----------.html），
        // 旧写法一律再拼一次 → /vodshow/vodshow/2-----------.html → 404 → 真机 18:06 五个分类全空。
        // 沙箱实证：/vodshow/vodshow/2-----------.html = 404 12290B；/vodshow/2-----------.html = 200 33825B。
        //
        // r362：tid 自带目录前缀时（wangfei.tv 的 vodtype/4.html、sm455 的 list/1.html）也不能再拼 /vodshow/，
        // 否则拼出 /vodshow/vodtype/4.html —— 沙箱实测 200 但 **0 个 voddetail（软 404）**，
        // 而 /vodtype/4.html 有 96 个。含 "-----------" 的 nunuyy 形态走老逻辑不变。
        if (tid.startsWith("/")) tid = tid.substring(1);
        // r364：**纯数字 tid 优先走 /vodtype/{tid}.html** —— btsn 实证：
        //   /vodshow/26-----------.html → **500**（0 条）；/vodtype/26.html → 200 / 64189 字 / 144 条；
        //   分页 /vodtype/26-2.html → 200，首条 id 与第1页不同（真翻页）。
        // 旧写法对纯数字 tid 一律拼 /vodshow/-----------，这类站分类页全是 500 → 用户看到「只有推荐页有内容」。
        // nunuyy 的 tid 带 "-----------"，wangfei 的带 "vodtype/"，都不会进入本分支。
        if (tid.matches("\\d{1,8}")) {
            return page <= 1 ? root + "/vodtype/" + tid + ".html"
                    : root + "/vodtype/" + tid + "-" + page + ".html";
        }
        boolean selfPath = tid.contains("/") && !tid.contains("-----------");
        String base = selfPath ? root + "/" : (tid.startsWith("vodshow/") ? root + "/" : root + "/vodshow/");
        if (page <= 1) return base + tid + (tid.endsWith(".html") ? "" : "-----------.html");
        if (selfPath) {
            String sb2 = tid.endsWith(".html") ? tid.substring(0, tid.length() - 5) : tid;
            return base + sb2 + "-" + page + ".html";
        }
        String body = tid.endsWith(".html") ? tid.substring(0, tid.length() - 5) : tid;
        // 分页位：nunuyy 是 vodshow/N--------2---.html（首个横线位放页码），保持与旧格式一致
        if (body.endsWith("-----------")) body = body.substring(0, body.length() - 11);
        return base + body + "--------" + page + "---.html";
    }

    /** r245：苹果CMS 未开 rewrite 的路由（/index.php/vod/type/id/1.html，翻页 /index.php/vod/type/id/1/page/2.html）。 */
    private String maccmsPhpListUrl(String tid, int page) {
        if (page <= 1) return root + indexPath + "/vod/type/id/" + tid + ".html";
        return root + indexPath + "/vod/type/id/" + tid + "/page/" + page + ".html";
    }

    /**
     * r265：tab 型分类（nav-de/nav-cn…）没有独立列表 URL，内容就在首页对应面板里，
     * 直接截取 detect 缓存的首页 HTML，避免再请求站点（该站有盾）。
     */
    /**
     * r301：分类分页取数 —— 旧代码根本没把 page 传给面板（`genericItems(genericPanelHtml(tid), 0)`），
     * 所以每一页都返回首页面板的同一批内容。这里按站点常见分页格式依次试，命中有效内容才采用。
     */
    private String genericPanelHtml(String tid, int page) {
        if (page <= 1) return genericPanelHtml(tid);
        // r331：**末段页码替换**必须排第一 —— 真机日志证明翻不动：
        //   分类分页 https://ts.faloo.com/category/1/1.html/page/2/ → 30 条（其实是同一页）
        //   分类分页无效（与上一次完全相同）→ 停止翻页
        // 飞卢真实分页格式是 /category/1/2.html（沙箱实测：第1页与第2页 id 交集 0，内容确实不同）。
        String paged = null;
        Matcher lastNum = Pattern.compile("^(.*/)(\\d+)(\\.\\w+)$").matcher(tid);
        if (lastNum.matches()) paged = root + "/" + lastNum.group(1) + page + lastNum.group(3);
        // r362：sm455 形态 —— tid=list/1.html（2 段，末段是**分类号**不是页码），
        // 真实分页是 /list/1/2.html。旧的 paged 会把 list/1.html 拼成 list/2.html（那是分类2的首页），
        // 于是每翻一页都是另一个分类的内容 —— 用户看到的就是「内容重复 + 翻不动」。
        // 仅在 2 段且末段全数字时启用；飞卢 category/1/1.html 是 3 段，不受影响（回归零风险）。
        String twoSeg = null;
        if (tid.endsWith(".html") || tid.endsWith(".htm")) {
            String body = tid.replaceAll("\\.(html|htm)$", "");
            int slashes = body.length() - body.replace("/", "").length();
            if (slashes == 1 && body.matches(".*/\\d+")) twoSeg = root + "/" + body + "/" + page + ".html";
        }
        String[] candidates = {
                twoSeg,
                paged,
                // r358：.html 结尾且 lastNum 不匹配的 vodshow 形态（ddys.la）——页码插在扩展名前（-----------2.html）。
                // paged 非 null（飞卢末段数字形态）时不提供，避免拼出 category/1/12.html 这种错 URL。
                // 未实测格式（盾页挡着），候选失败自动回退，不伤现状。
                (paged == null && (tid.endsWith(".html") || tid.endsWith(".htm"))) ? root + "/" + tid.replaceAll("\\.(html|htm)$", page + ".$1") : null,
                root + "/" + tid + "/page/" + page + "/",
                root + "/" + tid + "/page/" + page,
                root + "/" + tid + "?page=" + page,
                // r375：tuiimg 家族 —— 分类页必须带尾斜杠（/meinv/?page=2 → 200，无斜杠 → 404），
                // 以及真实分页格式 /meinv/list_2.html（共 207 页，分页块 class="end" 指向尾页）。
                // 真机 16:32 实证：page=2 三候选全 404（548 字快速失败）→ 回退首页面板 → 翻页无效。
                // 两个新候选都排在既有候选之后，其它站零影响。
                root + "/" + tid + "/?page=" + page,
                root + "/" + tid + "/list_" + page + ".html"
        };
        for (String u : candidates) {
            if (TextUtils.isEmpty(u)) continue;
            try {
                String html = get(u);
                // r389：死页判据 —— 47o 真机 page=2 先试 /meitu/models/page/2/（404），
                // 该页 24255 字且能被 genericItems 抽出 1 条「这个页面走丢了」→ 旧逻辑「能抽条就算数」直接采纳，
                // 用户翻页看到的就是 404 页。isErrorPage 对 >8000 字一律 false（防误杀），所以另加标题级死页判据。
                if (!TextUtils.isEmpty(html) && html.length() > 2000 && !isDeadPage(html)) {
                    JSONArray probe = genericItems(html, 0);
                    if (probe.length() > 0) {
                        log("分类分页 " + u + " → " + probe.length() + " 条");
                        return html;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        log("分类分页试过 3 种格式都无有效内容，回退首页面板 tid=" + tid + " page=" + page);
        return genericPanelHtml(tid);
    }

    private String genericPanelHtml(String tid) {
        if (!TextUtils.isEmpty(homeHtml) && tid != null && tid.startsWith("nav-")) {
            Matcher m = Pattern.compile("id=\"" + Pattern.quote(tid) + "\"([\\s\\S]*?)(?=id=\"nav-[A-Za-z0-9_-]+\"|</main>|</body>)").matcher(homeHtml);
            if (m.find()) {
                String seg = m.group(0);
                if (seg.contains("play/")) {
                    log("分类 " + tid + " 用首页面板解析 " + seg.length() + " 字");
                    return seg;
                }
            }
        }
        return get(genericListUrl(tid, 1));
    }

    private String genericListUrl(String tid, int page) {
        if ("all".equals(tid)) return root + "/";
        // r262：带查询的分类（/filter?mform=1&sort=grade）不能再拼尾斜杠，翻页用 &page=
        if (tid.contains("?")) return root + "/" + tid + (page > 1 ? "&page=" + page : "");
        // r358：以 .html/.htm 结尾的 tid（ddys.la /list/dongman-----------.html 这类 maccms vodshow 形态）
        // 必须原样拼接——拼尾斜杠后部分站 404/跳首页，所有分类渲染出同一批内容（真机实证「每个分类内容都一样」）。
        // 飞卢 category/1/1.html 沙箱实测：带/不带尾斜杠返回 md5 相同，此改动对它零影响。
        // 分页：末段数字形态（飞卢 category/1/1.html → category/1/2.html）优先替换末段数字；
        // 其余（vodshow）页码插在扩展名前（xxx-----------2.html）。两者都是候选失败自动回退。
        if (tid.endsWith(".html") || tid.endsWith(".htm")) {
            if (page <= 1) return root + "/" + tid;
            Matcher m = Pattern.compile("^(.*/)(\\d+)\\.(\\w+)$").matcher(tid);
            if (m.matches()) return root + "/" + m.group(1) + page + "." + m.group(3);
            return root + "/" + tid.replaceAll("\\.(html|htm)$", page + ".$1");
        }
        // r369：page<=1 **默认不带尾斜杠** —— thisav66 实证：
        //   /dm635/cn/release  → 200 / 149620 字 / 30 个影片
        //   /dm635/cn/release/ → **500** / 375 字 / 0 个
        //   meizi5 /tag/meitui → 200；/tag/meitui/ → 301
        // 两者对比：不带尾斜杠更安全；服务端 301 会自己补上正确的 dmN 前缀（get() 会跟随 Location）。
        // 若某些站必须带尾斜杠，categoryContent 里有回退候选。
        if (page <= 1) return root + "/" + tid;
        return root + "/" + tid + "/page/" + page;
    }

    // ------------------------------------------------------------------ 详情

    @Override
    public String detailContent(List<String> ids) {
        LogCategory previousStage = enterStage(LogCategory.PLAY);
        try {
            return detailContentStaged(ids);
        } finally {
            leaveStage(previousStage);
        }
    }

    private String detailContentStaged(List<String> ids) {
        try {
            detect();
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            if ("nuxt-json-aes".equals(template)) return nuxtDetail(ids.get(0));
            if ("jsdata".equals(template)) return jsdataDetail(ids.get(0));
            // r359：youtube 详情 —— watch 页 ytInitialPlayerResponse.videoDetails（沙箱实证全字段）。
            if (isYouTube()) {
                JSONArray dl = new JSONArray();
                dl.put(youtubeDetail(ids.get(0)));
                return new JSONObject().put("list", dl).toString();
            }
            String id = ids.get(0);
            String url = resolveDetailUrl(id);
            String html = get(url);
            int detailCode = lastFetchCode;   // push v50（mrds 修法 B 前半）：详情页状态码
            // r285：detail 阶段也要过 Cap —— 真机 12:34 日志里 player 阶段求解后拿到 513133 字真实页，
            // 而 detail 阶段没求解，只拿到「访问验证」空壳 → 被判页面不可用 → 标题/简介全空。
            if (isErrorPage(html) || (html != null && html.contains("cap-widget"))) {
                String dHost = schemeHost(url);
                // r377：协议不支持的站（hashwx）直接跳过 —— 实测每 45 秒只允许 3 次 GET，
                // 多一次无效重试就会把 WebView 的额度烧光（真机 22:14 detail+cap重试+player+WebView = 4 次 → 429）。
                if (CAP_UNSUPPORTED.contains(dHost)) {
                    log("detail cap 协议不支持纯 HTTP 求解 → 跳过重试，把请求额度留给 WebView 验证");
                } else {
                String ck = CAP_COOKIE.getOrDefault(dHost, "");
                if (TextUtils.isEmpty(ck)) {
                    ck = solveCapCookie(dHost);
                    if (!TextUtils.isEmpty(ck)) CAP_COOKIE.put(dHost, ck);
                } else {
                    log("detail cap cookie 复用缓存（跳过求解）");
                }
                if (!TextUtils.isEmpty(ck)) {
                    Map<String, String> ch = new LinkedHashMap<>();
                    ch.put("User-Agent", UA);
                    ch.put("Referer", schemeHost(url) + "/");
                    ch.put("Cookie", ck);
                    String retry = OkHttp.string(url, ch);
                    boolean retryBad = TextUtils.isEmpty(retry) || isErrorPage(retry) || retry.contains("cap-widget");
                    if (!retryBad && retry.length() > (html == null ? 0 : html.length())) {
                        log("detail cap 已通过，取到真实详情页 " + retry.length() + " 字");
                        html = retry;
                        detailCode = 200;
                        rememberPage(url, html);   // r375：cap 链路抓取不终 get()，LAST_PAGE 从未记录
                                                         // → r374 的 429 降级拿不到缓存（真机 17:15 三次 429 无重放日志）
                    } else {
                        // r377：带 cookie 重试仍拿到验证壳 → 说明协议已升级（hashwx）、旧 cookie 失效。
                        // 标记后不再浪费请求，并丢掉无效 cookie（否则每轮都要白烧一次 GET）。
                        CAP_UNSUPPORTED.add(dHost);
                        CAP_COOKIE.remove(dHost);
                        log("detail cap 重试仍为验证壳 → 标记该站为 WebView 原生验证，后续不再重试");
                    }
                }
                }
            }
            // r459：**detail 路由不可用 → 回退列表项自带的 play 页**（框架级修法，非逐站打补丁）。
            //   很多 macCMS 站列表项直接给 /vod/play/id/N/sid/1/nid/1.html，而 detail 路由 500：
            //   avu.wmq9 实证 detail=/vod/detail/id/1565532.html → HTTP 500；同 vid 的 play 页 → 200 / 23855 字含 m3u8。
            //   原逻辑只把名字/封面用列表缓存补，播放入口仍指向 500 的 detail → player 候选 0 → 转 WebView 嗅探
            //   → 用户看到的「播放跳转网页」（历史反复出现的同类问题）。此处换 html 与 url，
            //   后续解析与「页面播放$」播放入口都跟着用 play 页（play 页通常同时带片名与播放器）。
            boolean idIsPlay = !TextUtils.isEmpty(id)
                    && (id.contains("/vod/play/id/") || id.contains("/vodplay/") || id.contains("/index.php/vod/play/"));
            if (idIsPlay && (TextUtils.isEmpty(html) || html.length() < 800 || isErrorPage(html))) {
                String alt = get(abs(id));
                int altCode = lastFetchCode;
                if (!TextUtils.isEmpty(alt) && alt.length() >= 800 && !isErrorPage(alt)) {
                    html = alt;
                    detailCode = altCode;
                    url = abs(id);
                    log("detail 路由不可用 → 回退 play 页 " + alt.length() + " 字（播放入口同步换为 play 页）");
                }
            }
            JSONObject vod;
            if ("maccmsphp".equals(template)) vod = maccmsPhpDetail(html, id);
            else if ("maccms".equals(template)) vod = maccmsDetail(html, id);
            else if ("zanpian".equals(template)) vod = zanpianDetail(html, id);
            else vod = genericDetail(html, id);
            if (vod == null) vod = genericDetail(html, id);
            // r359：壳站 detail 渲染兜底 —— k84h5x 全站 ap-d 加密壳（详情页纯 HTTP 零文本，
            // A 级检索实证全仓 WebFetch.fetch 仅 detect/categoryContent 两处，detail 断链），
            // genericDetail 抽空且页面确为壳时用 WebView 真跑一遍详情页（d.js 自己解密）再抽。
            // r360：判据扩展 —— 除 ap-d 壳（k84h5x 型）外，XOR document.write 加密站（sm455.vip 型）
            // 正文也是运行时拉取（纯 HTTP 解密后 title/图/播放地址全无，真实 WebView 实证渲染后
            // title=正在播放:xxx、播放器 22:33 全出）。hasXorShellTrace 判据见方法注释，常规站零误伤。
            if ((isEncryptedShell(html) || hasXorShellTrace(html)) && (vod == null || TextUtils.isEmpty(vod.optString("vod_name")))) {
                try {
                    Map<String, String> hd = new LinkedHashMap<>();
                    String rh = webFetch(url, hd, 25000);
                    if (!TextUtils.isEmpty(rh) && rh.length() > html.length()) {
                        JSONObject rv = genericDetail(rh, id);
                        if (rv != null && !TextUtils.isEmpty(rv.optString("vod_name"))) {
                            vod = rv;
                            // r360：渲染兜底的 name 常来自渲染后 <title>（「正在播放:片名-站点」形态，
                            // 真实 WebView 实证 sm455），剥前缀防片名难看。后缀不剥（防误伤含-片名）。
                            String nm = vod.optString("vod_name");
                            int pp = nm.indexOf("正在播放:");
                            if (pp >= 0) { nm = nm.substring(pp + 5).trim(); vod.put("vod_name", nm); }
                            log("detail 渲染兜底（加密壳）→ " + nm);
                        } else {
                            log("detail 渲染兜底（加密壳）拿到 " + rh.length() + " 字但仍抽不到 vod");
                        }
                    } else {
                        log("detail 渲染兜底（加密壳）未取到渲染 DOM");
                    }
                } catch (Throwable e) {
                    log("detail 渲染兜底（加密壳）异常 " + e);
                }
            }
            // r257/r258：详情页被 Cap 验证或源站 520 挡着时，HTTP 只拿到空壳/错误页 ——
            // 标题/封面用列表缓存补，简介直接留空，绝不显示「Web server is returning…」这种垃圾。
            if (vod != null) {
                boolean bad = isErrorPage(html);
                // push v50（mrds 修法 B 前半）：详情取到死页（标题级死页判断命中或 HTTP ≥400）时，
                //   404 页的标题/简介不上屏：片名、封面用搜索结果带来的列表缓存，简介清空。地址解析不动。
                if (!bad && (isDeadPage(html) || detailCode >= 400)) {
                    bad = true;
                    log("详情取到死页（HTTP " + detailCode + "）" + abs(url) + " → 片名/封面用搜索结果，简介清空");
                }
                String name = vod.optString("vod_name");
                if (bad) {
                    vod.put("vod_content", "");
                    log("detail 页面不可用（验证/源站错误页）→ 用列表缓存兜底");
                }
                // push v33：片名回落到 <title> 截短（如麦田「家业电视剧免费」）时，优先用搜索/列表带来的片名。
                if (!bad && !TextUtils.isEmpty(name) && Boolean.TRUE.equals(V33_TITLE_FROM_TAG.get())) {
                    String[] tm = LIST_META.get(normKey(abs(url)));
                    if (tm != null && !TextUtils.isEmpty(tm[0]) && !tm[0].equals(name) && !adLikeText(tm[0]) && tm[0].trim().length() >= 1) {
                        vod.put("vod_name", tm[0]);
                        name = tm[0];
                    }
                }
                if (bad || TextUtils.isEmpty(name) || name.contains("访问验证") || name.length() < 2) {
                    String[] meta = LIST_META.get(normKey(abs(url)));
                    if (meta != null) {
                        vod.put("vod_name", meta[0]);
                        if (!TextUtils.isEmpty(meta[1])) vod.put("vod_pic", meta[1]);
                        log("detail 命中列表缓存 " + meta[0]);
                    }
                }
                // r536：**封面纠错** —— 广告注入型详情页（hsck 实证）封面区被顶部横幅占位，
                // 页面无 og:image/video-pic → detailPic 降级 pickImg 抓到「福利」广告大图（gif+外链横幅）。
                // 判据命中广告图时，改从正文「指向本片的推荐缩略图 <a data-original>」取真封面；
                // 若正文没有，再用列表缓存 LIST_META 兜底。仅广告图场景覆盖，正常站零影响。
                String picNow = vod.optString("vod_pic");
                if (isAdBannerPic(picNow, html)) {
                    String good = selfThumbPic(html, url, id);
                    if (TextUtils.isEmpty(good)) {
                        String[] picMeta = LIST_META.get(normKey(abs(url)));
                        if (picMeta != null) good = picMeta[1];
                    }
                    if (!TextUtils.isEmpty(good) && !good.equals(picNow)) {
                        vod.put("vod_pic", abs(good));
                        log("detail 封面纠错（广告横幅→本片缩略图）" + picNow + " → " + abs(good));
                    }
                }
            }
            if (vod != null && isAudioPage(abs(url))) {
                // r324：**不能**再加 `vod_play_from 为空` 这个条件 —— genericDetail 内部
                // （lineName.put("line1","页面播放")）早就把它设成「页面播放」了，
                // 条件永远为 false，这个分支从 r320 上线起一次都没执行过（真机日志无「音频分集探测」行即证）。
                List<String> eps = collectAudioEpisodes(html);
                log("音频分集探测 eps=" + eps.size() + " html=" + (html == null ? 0 : html.length()) + "字 url=" + abs(url));
                if (eps.size() >= 2) {
                    StringBuilder urls = new StringBuilder();
                    for (int i = 0; i < eps.size(); i++) {
                        if (i > 0) urls.append('#');
                        urls.append("第").append(i + 1).append("集$").append(eps.get(i));
                    }
                    vod.put("vod_play_from", "听书");
                    vod.put("vod_play_url", urls.toString());
                    log("音频分集 " + eps.size() + " 集（" + abs(url) + "）");
                }
            }
            if (vod != null && TextUtils.isEmpty(vod.optString("vod_play_from"))) {
                // r344：generic 模板的合集判定已挪进 genericDetail 的 grouped.isEmpty() ——
                // 这里的 isEmpty 判定对 generic 模板永远赶不上（genericDetail r249 兜底先把
                // vod_play_from 设成「页面播放」，r342 的分支成了死代码，真机 18:05 实证）。
                // 此处只兜其余模板（zanpian/maccms 抽空剧集时），判据与 genericDetail 共用同一个函数。
                // r345：传 url 供翻页聚合（rel="next" 尾页码模板推导）。
                List<String[]> eps = collectionEpisodes(html, url);
                if (eps != null) {
                    vod.put("vod_play_from", "合集");
                    vod.put("vod_play_url", joinEpisodeNames(eps));
                    log("合集选集 " + eps.size() + " 集（" + abs(url) + "）");
                }
            }
            // r486：**小说章节目录** —— 书目录页（/read/{bookId}/）章节列表在 <ul class="read">，
            //   generic 抽不到剧集 → 兕底「页面播放$」单条 → 用户看到的整本书只有 1 章（真机实锤）。
            //   novelChapters 形态自适应抽「第N章」链接（目录分页聚合），≥2 章即成选集；
            //   每章 id 是章节页 URL，player 回源时 isNovelPage 命中 → extractArticle 正文 → novel://。
            if (vod != null && TextUtils.isEmpty(vod.optString("vod_play_from"))) {
                List<String[]> neps = novelChapters(html, url);
                if (neps != null && neps.size() >= 2) {
                    vod.put("vod_play_from", "小说");
                    vod.put("vod_play_url", joinEpisodeNames(neps));
                    log("小说选集 " + neps.size() + " 章（" + abs(url) + "）");
                }
            }
            if (vod != null && TextUtils.isEmpty(vod.optString("vod_play_from"))) {
                // r250：任何模板都抽不到剧集时，给播放器一条「页面播放」= 详情页自身，交给 WebView 嗅探。
                // 空的 vod_play_url 会让播放器卡在「无剧集」，比给一个可嗅探入口更糟。
                // r459：条目本身是 play 链接时，**入口必须用 play 页**（macCMS 的播放器只在 play 页，
                //   detail 页可能无播放数据甚至 500）—— 否则 player 拿到 detail 页抽 0 候选 → 转 WebView。
                vod.put("vod_play_from", "页面播放");
                String entry = idIsPlay ? abs(id) : abs(url);
                // r528：入口必须是「播放页」而不是详情页 —— 盾站详情页里没有播放器，
                // WebView 打开它、盾过得再好也嗅探不到 m3u8。归一后盾由浏览器自己过，
                // 播放页里的播放器自己发请求，网络层嗅探即可（不逐站适配）。
                String pp = playPageOf(entry, html);
                if (!TextUtils.isEmpty(pp)) entry = pp;
                vod.put("vod_play_url", "页面播放$" + entry);
                log("detail 无剧集（" + template + "）→ " + (idIsPlay ? "play 页" : "本页") + "嗅探 " + entry);
            }
            // r415：记录条目类型（供 player 按条目分流，混合站必需）
            if (vod != null && !TextUtils.isEmpty(id)) {
                String from = vod.optString("vod_play_from", "");
                String pu = vod.optString("vod_play_url", "");
                // r418：删「合集/听书」from 判据 —— 假合集（推荐区链接被当选集）也会
                // 命中该判据 → 图集条目被记成 video → player 跳过图片分流 → 跳网页。
                // 真视频的可靠信号只有「真选集」（播放形态链接，P_PLAY_ROUTE 抽出，countEps≥2）。
                if (countEps(pu) >= 2) {
                    VOD_TYPE.put(id, "video");
                    log("条目类型 video（选集 " + countEps(pu) + "）");
                } else {
                    // r464：**AES 加密图集先解密** —— fp.xiezhen8 实证：详情页图片由 CryptoJS 页内解密后
                    //   document.write(IMG_content)，纯 HTTP 只见 1 张 logo → collectBigImages 判不出图集
                    //   → 条目被当视频 → 点开出不了内容（用户：「兜底都没出内容」）。
                    String htmlX = aesPicDecrypt(html);
                    List<String> ci = hasStrongVideoSignal(htmlX) ? null : collectBigImages(htmlX);
                    if (ci != null && ci.size() >= 8) {
                        VOD_TYPE.put(id, "pic");
                        log("条目类型 pic（大图 " + ci.size() + "）");
                    }
                }
            }
            return new JSONObject().put("list", new JSONArray().put(vod)).toString();
        } catch (Throwable e) {
            return "{\"list\":[]}";
        }
    }

    /**
     * r464：**AES 加密图集**解密 + 分页补图（框架级新能力，非逐站）。
     * 真因（fp.xiezhen8.help 实证）：详情页把图片数据由页内 CryptoJS 解密后 document.write 输出：
     *   <script>var key=CryptoJS.enc.Utf8.parse("DNwyxsN7llNlcHlS"); var iv=CryptoJS.enc.Utf8.parse("5pkynwex1HP3qH6W");
     *           var IMG_content='&lt;base64密文&gt;'; document.write(IMG_content);</script>
     * 纯 HTTP 只能拿到密文 → 页面里只剩 1 张 logo → collectBigImages 判不出图集 → 条目被当视频 → 出不了内容。
     * 本方法：① 取页内 key/iv（两个 CryptoJS.enc.Utf8.parse 字面量）；② 解密所有 IMG_content 密文块拼回 html；
     *          ③ 解密 PAGE_list 拿分页链接（该站每页 5 张、共 N 页）跟进补图（限 6 页，够判图集且不拖慢）。
     * 解开的 HTML 片段形如 &lt;p&gt;&lt;img src="/style/lazy.gif" class="lazy" data-src="//host/...jpg"&gt;&lt;/p&gt;
     *   —— data-src 属 collectBigImages 已有的懒加载属性优先级（realsrc → data-src/data-original → src），能直接收到。
     */
    private String aesPicDecrypt(String html) {
        if (TextUtils.isEmpty(html) || !html.contains("IMG_content") || !html.contains("CryptoJS")) return html;
        List<String> kv = new ArrayList<>();
        Matcher km = Pattern.compile("CryptoJS\\.enc\\.Utf8\\.parse\\(\"([A-Za-z0-9+/=]{8,32})\"\\)").matcher(html);
        while (km.find()) kv.add(km.group(1));
        if (kv.size() < 2) return html;
        String key = kv.get(0), iv = kv.get(1);
        StringBuilder sb = new StringBuilder(html);
        List<String> pageImgs = new ArrayList<>();
        int block = 0;
        Matcher cm = Pattern.compile("IMG_content\\d*\\s*=\\s*'([A-Za-z0-9+/=]{16,})'").matcher(html);
        while (cm.find()) {
            String pt = aesDecryptBase64(cm.group(1), key, iv);
            if (TextUtils.isEmpty(pt)) continue;
            sb.append(pt);
            block++;
            Matcher im = Pattern.compile("data-src=\"([^\"]+)\"").matcher(pt);
            while (im.find()) if (!pageImgs.contains(im.group(1))) pageImgs.add(im.group(1));
        }
        log("AES 图集解密 " + block + " 块 / 本页 " + pageImgs.size() + " 张");
        if (block == 0) return html;
        Matcher pm = Pattern.compile("PAGE_list\\d*\\s*=\\s*'([A-Za-z0-9+/=]{16,})'").matcher(html);
        if (pm.find()) {
            String pl = aesDecryptBase64(pm.group(1), key, iv);
            List<String> pages = new ArrayList<>();
            Matcher pum = Pattern.compile("href=\"([^\"]*?/sid/\\d+\\.html)\"").matcher(pl);
            while (pum.find()) {
                String u = abs(pum.group(1));
                if (!TextUtils.isEmpty(u) && !pages.contains(u)) pages.add(u);
            }
            // r468：**优先「连续编号推导」**（旧写法逐页抓 15 页 → 用户实测进图集要等 10 秒）。
            //   fp.xiezhen8 实测：图片 URL = 同前缀 + 递增编号（…/888883/71553.jpg, 71554, … 跨页也连续 71558…），
            //   且 <title> 带总数 (73P) → 抓 1 页 + 抓 1 页跨页校验，即可推导全部，免抓 14 页。
            int total = 0;
            String np = firstMatch(html, "<title[^>]*>[^<]*?\\((\\d{1,4})\\s*[Pp]\\)");
            if (!TextUtils.isEmpty(np)) try { total = Integer.parseInt(np.trim()); } catch (Throwable ignore) {}
            if (total <= 0) total = pageImgs.size() * (pages.size() + 1);
            String seq = seqUrl(pageImgs);
            if (seq != null && total > pageImgs.size() && !pages.isEmpty()) {
                String ph2 = get(pages.get(0));
                String pt2 = "";
                Matcher c2 = Pattern.compile("IMG_content\\d*\\s*=\\s*'([A-Za-z0-9+/=]{16,})'").matcher(ph2);
                if (c2.find()) pt2 = aesDecryptBase64(c2.group(1), key, iv);
                List<String> nextImgs = new ArrayList<>();
                Matcher im2 = Pattern.compile("data-src=\"([^\"]+)\"").matcher(pt2);
                while (im2.find()) if (!nextImgs.contains(im2.group(1))) nextImgs.add(im2.group(1));
                String[] sp = seq.split("\\|");
                int start = Integer.parseInt(sp[1]);
                int step = Integer.parseInt(sp[3]);
                boolean contiguous = !nextImgs.isEmpty()
                        && nextImgs.get(0).equals(sp[0] + (start + pageImgs.size() * step) + sp[2]);
                if (contiguous) {
                    int added = 0;
                    for (int k = pageImgs.size(); k < total; k++) {
                        sb.append("<img src=\"/style/lazy.gif\" class=\"lazy\" data-src=\"").append(sp[0]).append(start + k * step).append(sp[2]).append("\" />");
                        added++;
                    }
                    log("AES 图集**连续编号推导** " + added + " 张（共 " + total + "，免抓 " + pages.size() + " 页）");
                    return sb.toString();
                }
                log("AES 图集分页不连续 → 回退逐页抓取（本页 " + pageImgs.size() + " 张）");
            }
            // 回退：逐页抓取（4 并发）—— **用图片 URL 集合去重**（旧写法用 pt[:80] 当指纹，
            //   而各页该前缀完全相同 → 后续页被误判重复全部丢弃，30 张被砍成 10 张）。
            java.util.LinkedHashSet<String> gotImgs = new java.util.LinkedHashSet<>(pageImgs);
            int cap = Math.min(pages.size() + 1, 20);
            List<String> todo = pages.size() > cap - 1 ? pages.subList(0, cap - 1) : pages;
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, Math.max(1, todo.size())));
            List<java.util.concurrent.Future<String>> futs = new ArrayList<>();
            for (String pu : todo) {
                final String u2 = pu;
                futs.add(pool.submit(() -> { try { return get(u2); } catch (Throwable t) { return ""; } }));
            }
            int got = 1;
            for (java.util.concurrent.Future<String> f : futs) {
                String ph = "";
                try { ph = f.get(20, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignore) {}
                got++;
                if (TextUtils.isEmpty(ph) || sb.length() >= 600 * 120) continue;
                Matcher cm2 = Pattern.compile("IMG_content\\d*\\s*=\\s*'([A-Za-z0-9+/=]{16,})'").matcher(ph);
                while (cm2.find()) {
                    String pt = aesDecryptBase64(cm2.group(1), key, iv);
                    if (TextUtils.isEmpty(pt)) continue;
                    boolean fresh = false;
                    Matcher im3 = Pattern.compile("data-src=\"([^\"]+)\"").matcher(pt);
                    while (im3.find()) if (gotImgs.add(im3.group(1))) fresh = true;
                    if (fresh) sb.append(pt);
                }
            }
            pool.shutdown();
            log("AES 图集分页补图 " + got + " 页 / 上限 " + cap + "（4 并发）");
        }
        return sb.toString();
    }

    /** r468：从有序图片 URL 列表推导「前缀|起始编号|后缀|步长」；不满足连续递增则返回 null。 */
    private String seqUrl(List<String> urls) {
        if (urls == null || urls.size() < 2) return null;
        Matcher m0 = Pattern.compile("^(.*?)(\\d+)(\\D*)$").matcher(urls.get(0));
        Matcher m1 = Pattern.compile("^(.*?)(\\d+)(\\D*)$").matcher(urls.get(1));
        if (!m0.find() || !m1.find()) return null;
        if (!m0.group(1).equals(m1.group(1)) || !m0.group(3).equals(m1.group(3))) return null;
        int a = Integer.parseInt(m0.group(2)), b = Integer.parseInt(m1.group(2));
        int step = b - a;
        if (step <= 0) return null;
        return m0.group(1) + "|" + a + "|" + m0.group(3) + "|" + step;
    }

    /** r464：AES/CBC/PKCS5 解密（base64 密文）—— 页内 CryptoJS 用的就是 CBC+PKCS7。 */
    private static String aesDecryptBase64(String b64, String key, String iv) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                    new javax.crypto.spec.IvParameterSpec(iv.getBytes("UTF-8")));
            return new String(c.doFinal(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)), "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    /** r245：把「列表里拿到的 id」换算成详情页地址（这类站的列表项可能直接是播放页）。 */
    private String resolveDetailUrl(String id) {
        if (TextUtils.isEmpty(id)) return root + "/";
        if (id.startsWith("http")) return id;
        if ("maccmsphp".equals(template)) {
            Matcher m = Pattern.compile("/index\\.php/vod/(?:play|detail)/id/(\\d+)").matcher(id);
            if (m.find()) return root + indexPath + "/vod/detail/id/" + m.group(1) + ".html";
        }
        if (id.startsWith("/")) return root + id;
        return root + "/" + id;
    }

    private JSONObject zanpianDetail(String html, String id) throws Exception {
        JSONObject vod = baseDetail(html, id);
        vod.put("vod_name", detailTitle(html));
        vod.put("vod_pic", detailPic(html));
        vod.put("vod_year", firstMatch(html, "年代[：:]\\s*(?:<[^>]+>\\s*)*([0-9]{4})", "年份[：:]\\s*(?:<[^>]+>\\s*)*([0-9]{4})"));
        vod.put("vod_area", firstMatch(html, "国家/地区[：:]\\s*(?:<[^>]+>\\s*)*([^<\\s]{1,12})", "地区[：:]\\s*(?:<[^>]+>\\s*)*([^<\\s]{1,12})"));
        String intro = firstMatchGroup(html, "<span[^>]+class=\"details-content-all[^\"]*\"[^>]*>(.*?)</span>");
        if (TextUtils.isEmpty(intro)) intro = firstMatchGroup(html, "<div[^>]+class=\"[^\"]*intro[^\"]*\"[^>]*>(.*?)</div>");
        if (!TextUtils.isEmpty(intro)) vod.put("vod_content", clean(intro));
        // 线路名：<a class="gico lzm3u8" href="#con_playlist_1" ...>量子</a>
        LinkedHashMap<String, String> lineName = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<a[^>]+class=\"gico\\s+([a-zA-Z0-9_]+)\"[^>]*href=\"#con_playlist_(\\d+)\"[^>]*>([^<]*)</a>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String key = m.group(2); // 与剧集 URL 里的线路号一致（/bofang/ID-线路-集.html）
            String name = clean(m.group(3));
            if (!lineName.containsKey(key) && !TextUtils.isEmpty(name)) lineName.put(key, name);
        }
        // 剧集：/bofang/ID-线路-集.html
        LinkedHashMap<String, List<String[]>> grouped = new LinkedHashMap<>();
        Matcher e = Pattern.compile("<a[^>]+href=\"(/bofang/(\\d+)-(\\d+)-(\\d+)\\.html)\"[^>]*>(.{0,40}?)</a>", Pattern.DOTALL).matcher(html);
        while (e.find()) {
            String line = e.group(3);
            String epName = clean(e.group(5));
            if (TextUtils.isEmpty(epName)) epName = "第" + e.group(4) + "集";
            List<String[]> eps = grouped.get(line);
            if (eps == null) {
                eps = new ArrayList<>();
                grouped.put(line, eps);
            }
            boolean dup = false;
            for (String[] old : eps) if (old[0].equals(e.group(4))) dup = true;
            if (!dup) eps.add(new String[]{e.group(4), epName, e.group(1)});
        }
        joinPlay(vod, grouped, lineName, null);
        return vod;
    }

    /**
     * r362：从页面 JSON-LD 里取播放地址（wangfei.tv 实证：
     * {"@type":"VideoObject","contentUrl":"https://vip.ffzy-play9.com/.../index.m3u8"}）。
     * 这类 SEO 型 maccms 站详情页里 0 个 /vodplay/ 链接，29 个都是 /artdetail/ 资讯文章，
     * 旧 detail 只能拿到 0 选集 → 转 WebView 嗅探 → 嗅探文章页永远拿不到视频。
     * 兼容：contentUrl / embedUrl / "url" 三种字段；先找 VideoObject，再全文兑底。
     */
    private static String jsonLdMediaUrl(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String u = firstMatch(html, "\"@type\"\\s*:\\s*\"(?:VideoObject|Movie|Clip)\"[^}]{0,400}?\"(?:contentUrl|embedUrl)\"\\s*:\\s*\"([^\"]{8,500})\"");
        if (TextUtils.isEmpty(u)) u = firstMatch(html, "\"(?:contentUrl|embedUrl)\"\\s*:\\s*\"([^\"]{8,500}\\.(?:m3u8|mp4)[^\"]*)\"");
        if (!TextUtils.isEmpty(u)) return u.replace("\\/", "/").replace("\\u0026", "&");
        // r362：运行时拼接型（sm455.vip）—— 页面里没有完整 URL，只有
        //   let bfz='https://38.je:38/'+vid.dataset.id+'/index.m3u8';   配合 <a id="vid" data-id="31160">
        // 必须按 data-id 还原；P_M3U8 要求连续 https?://….m3u8，对这种永远匹配不到。
        return runtimeConcatMediaUrl(html);
    }

    /**
     * r362：还原「前缀 + 数据集 id + 后缀」运行时拼接的播放地址（sm455.vip 型）。
     * 沙箱实证：拼出 https://38.je:38/31160/index.m3u8 → 带 Referer 200 / 不带 404；
     * 子列表 12077B、首分片 269216B，链路完整可播。
     */
    private static String runtimeConcatMediaUrl(String html) {
        try {
            Matcher m = Pattern.compile("['\"](https?://[^'\"]{4,200}/)['\"]\\s*\\+\\s*\\w+\\s*\\.\\s*dataset\\s*\\.\\s*id\\s*\\+\\s*['\"]([^'\"]{0,120}\\.(?:m3u8|mp4)[^'\"]*)['\"]").matcher(html);
            if (!m.find()) return "";
            String pre = m.group(1), suf = m.group(2);
            String id = firstMatch(html, "id=[\"']vid[\"'][^>]{0,80}?data-id=[\"']([A-Za-z0-9_-]{1,40})[\"']");
            if (TextUtils.isEmpty(id)) id = firstMatch(html, "data-id=[\"']([A-Za-z0-9_-]{1,40})[\"'][^>]{0,80}?id=[\"']vid[\"']");
            if (TextUtils.isEmpty(id)) id = firstMatch(html, "data-id=[\"']([A-Za-z0-9_-]{1,40})[\"']");
            if (TextUtils.isEmpty(id)) return "";
            return pre + id + (suf.startsWith("/") ? suf : "/" + suf);
        } catch (Throwable e) {
            return "";
        }
    }

    /** r362：无真实选集时，用 JSON-LD 的直链补一集（与各 detail 共用，不逐站写一套）。 */
    private void jsonLdFallback(JSONObject vod, String html, LinkedHashMap<String, List<String[]>> grouped, LinkedHashMap<String, String> lineName) {
        try {
            if (grouped != null && !grouped.isEmpty()) return;
            String ld = jsonLdMediaUrl(html);
            if (TextUtils.isEmpty(ld)) return;
            List<String[]> one = new ArrayList<>();
            one.add(new String[]{"1", "正片", ld});
            grouped.put("ld", one);
            lineName.put("ld", "直链");
            log("详情 JSON-LD 直链 → 单集 " + ld);
        } catch (Throwable ignored) {
        }
    }

    /**
     * r4xx（通用化，照搬昏黄影视 rules.md §9）：详情页内「多个播放器节点」全部转成剧集列表。
     * <p>昏黄影视通用经验：HTML 属性里的播放器配置先还原 {@code \/} 再解析 JSON；多个播放器节点
     * （dplayer / video / source / 播放器容器）必须全部转成剧集，不能只取第一个。
     * <p>这里不认具体站点、不认具体 class，通用按「播放器节点」切块：dplayer、xgplayer、video、
     * source、或带 data-video_* / data-src-m3u8 等播放器信号的元素。每块用已有 findPlayUrl
     * （全文扫 m3u8 + 还原转义）取第一个直链；集名优先用节点标题尾部编号，否则第 N 集。
     */
    /** push v50（mrds 修法 A）：播放器节点信号 —— dplayer/xgplayer 容器（class 值后不跟「-」）或带 data-video_* 的节点。
     *  pagePlaylistEpisodes 切块与 collectionEpisodes 的「有播放器」门共用这一个判断，不各写一套。 */
    private static final Pattern PLAYER_NODE = Pattern.compile("<(?:div|section|span)[^>]*(?:class=[\"'][^\"']*(?:dplayer|xgplayer)(?!-)|data-video_[a-z]+=)", Pattern.CASE_INSENSITIVE);

    private static boolean hasPlayerNode(String html) {
        return !TextUtils.isEmpty(html) && PLAYER_NODE.matcher(html).find();
    }

    private void pagePlaylistEpisodes(String html, LinkedHashMap<String, List<String[]>> grouped, LinkedHashMap<String, String> lineName) {
        try {
            if (TextUtils.isEmpty(html)) return;
            // 通用播放器节点切块信号：dplayer/xgplayer 容器、或带 data-video_* 的自定义播放器。
            // class 值用「后不跟 -」负向前瞻精确匹配，避免误中 dplayer-loading / dplayer-play-icon 等。
            Matcher nm = PLAYER_NODE.matcher(html);
            java.util.List<Integer> starts = new java.util.ArrayList<>();
            while (nm.find()) starts.add(nm.start());
            java.util.List<String[]> out = new java.util.ArrayList<>();
            int idx = 0;
            for (int i = 0; i < starts.size(); i++) {
                int s = starts.get(i);
                int e = (i + 1 < starts.size()) ? starts.get(i + 1) : Math.min(html.length(), s + 6000);
                String seg = html.substring(s, e);
                String title = firstMatchGroup(seg, "data-video_title=[\"']([^\"']+)[\"']");
                String no = "";
                if (!TextUtils.isEmpty(title)) {
                    Matcher t = Pattern.compile("(\\d{1,4})\\s*$").matcher(title.trim());
                    if (t.find()) no = t.group(1);
                }
                // 优先从 data-config JSON 的 "video":{"url":"..."} 取主源（照搬 51吃瓜 py 源），
                // 还原 \/ 转义；取不到再回退 findPlayUrl 通用扫 m3u8。
                String url = dplayerVideoUrl(seg);
                if (TextUtils.isEmpty(url) || !url.startsWith("http")) url = findPlayUrl(seg);
                if (TextUtils.isEmpty(url) || !url.startsWith("http")) continue;
                idx++;
                String name = TextUtils.isEmpty(no) ? ("第" + idx + "集") : ("第" + Integer.parseInt(no) + "集");
                out.add(new String[]{String.valueOf(idx), name, url});
            }
            if (out.isEmpty()) return;
            grouped.put("line1", out);
            lineName.put("line1", "默认线路");
            log("页内多播放器选集 " + out.size() + " 集");
        } catch (Throwable t) {
            log("多播放器选集解析异常 " + t.getClass().getSimpleName());
        }
    }

    /** 从 dplayer 的 data-config JSON 里提取主视频源 m3u8（"video":{"url":"..."}，还原 \/ 转义）。 */
    private String dplayerVideoUrl(String seg) {
        try {
            String cfg = firstMatchGroup(seg, "data-config=['\"]([^']{1,8000})['\"]");
            if (TextUtils.isEmpty(cfg)) cfg = firstMatchGroup(seg, "data-config=[\"']([^\"]{1,8000})[\"']");
            if (TextUtils.isEmpty(cfg)) return "";
            String dec = unescapeHtml(cfg);
            JSONObject o = new JSONObject(dec);
            JSONObject video = o.optJSONObject("video");
            if (video == null) return "";
            String url = video.optString("url", "");
            if (TextUtils.isEmpty(url)) {
                JSONObject h265 = o.optJSONObject("video_h265");
                if (h265 != null) url = h265.optString("url", "");
            }
            if (TextUtils.isEmpty(url)) return "";
            return url.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=");
        } catch (Throwable t) {
            return "";
        }
    }

    private JSONObject maccmsDetail(String html, String id) throws Exception {
        JSONObject vod = baseDetail(html, id);
        vod.put("vod_name", detailTitle(html));
        vod.put("vod_pic", detailPic(html));
        vod.put("vod_year", firstMatch(html, "年份[：:]\\s*(?:<[^>]+>\\s*)*([0-9]{4})"));
        vod.put("vod_area", firstMatch(html, "地区[：:]\\s*(?:<[^>]+>\\s*)*([^<\\s]{1,12})"));
        // r353：① class 加 (?!list) 负向前瞻 —— nnyy.la 第一个命中 "content" 关键词的 div 是
        // lists-content（列表容器），抽到的是推荐位标签「热血比赛,团队精神,逆境成长」而非本片简介。
        // ② 抽空或过短（<20 字）回落 og:description / meta name=description（几乎所有站都有，内容就是剧情简介）。
        String mIntro = clean(firstMatchGroup(html, "<div[^>]+class=\"(?!list)[^\"]*(?:desc|content|intro)[^\"]*\"[^>]*>(.*?)</div>"));
        if (mIntro.length() < 20) mIntro = clean(firstMatch(html,
                "<meta[^>]+property=\"og:description\"[^>]+content=\"([^\"]{20,600})\"",
                "<meta[^>]+name=\"description\"[^>]+content=\"([^\"]{20,600})\""));
        if (!TextUtils.isEmpty(mIntro)) vod.put("vod_content", mIntro);
        LinkedHashMap<String, String> lineName = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<a[^>]+href=\"#playlist(\\d+)\"[^>]*>([^<]{1,20})</a>", Pattern.DOTALL).matcher(html);
        while (m.find()) lineName.put(m.group(1), clean(m.group(2)));
        LinkedHashMap<String, List<String[]>> grouped = new LinkedHashMap<>();
        // r353：nunuyy 模板（nnyy.la）—— 真选集是 <li data-sid data-nid> 的 onclick="play(sid,nid)"
        // JS 链接，不是 vodplay 明文链接；旧正则全文找不到选集，把页脚推荐区的 6 部影片
        // 全收成了「选集」（真机：超级少女的选集全是「失衡生活」等推荐片）。
        // 页面 JS 的 urlDictionary[sid][nid] 是全量的（换线路 URL 返回同一份字典，沙箱已验证），
        // 播放标识构造回 vodplay/{vid}-{sid}-{nid}.html，playerContent 按 URL 的 sid/nid 取密文解密。
        if (html.contains("urlDictionary")) {
            Matcher dt = Pattern.compile("<dt[^>]*data-sid=\"(\\d+)\"[^>]*>([^<]{1,20})</dt>").matcher(html);
            while (dt.find()) lineName.put(dt.group(1), clean(dt.group(2).replaceAll("\\(\\d+\\)\\s*$", "")));
            String vid = firstMatch(id, "/vodplay/(\\d+)-");
            if (!TextUtils.isEmpty(vid)) {
                Matcher li = Pattern.compile("<li[^>]*data-sid=\"(\\d+)\"[^>]*data-nid=\"(\\d+)\"[^>]*>\\s*<a[^>]*>\\s*([^<]{0,40}?)\\s*</a>", Pattern.DOTALL).matcher(html);
                while (li.find()) {
                    String epName = clean(li.group(3));
                    if (TextUtils.isEmpty(epName)) epName = "第" + li.group(2) + "集";
                    eps(grouped, li.group(1)).add(new String[]{li.group(2), epName, "/vodplay/" + vid + "-" + li.group(1) + "-" + li.group(2) + ".html"});
                }
                if (!grouped.isEmpty()) {
                    log("nunuyy 选集 " + grouped.size() + " 线路（dt data-sid）");
                    joinPlay(vod, grouped, lineName, null);
                    return vod;
                }
            }
        }
        // push v33：链接内容放宽到首个 </a> 为止（≤300 字）——麦田播放页「正在播放」那一集写成
        //   <span>HD</span><em class="play-on"><i></i>…</em>，超 40 字被旧正则整条漏掉（单集片整条线路消失）；
        //   同一线路同一地址只收一次（播放页「下一集」按钮会重复收第 2 集）。
        Matcher e = Pattern.compile("<a[^>]+href=\"(/vodplay/(\\d+)-(\\d+)-(\\d+)\\.html)\"[^>]*>((?:(?!</a>).){0,300}?)</a>", Pattern.DOTALL).matcher(html);
        Map<String, String[]> v33Seen = new HashMap<>();
        // 放宽只对本片（地址里的剧号 = 本页剧号）生效；别的片（推荐卡片）仍按旧的 40 字限制，行为不变。
        String v33Vid = firstMatch(id == null ? "" : id, "/(?:voddetail|vodplay)/(\\d+)");
        while (e.find()) {
            if (e.group(5).length() > 40 && (TextUtils.isEmpty(v33Vid) || !v33Vid.equals(e.group(2)))) continue;
            String line = e.group(3);
            String epName = clean(e.group(5));
            if (TextUtils.isEmpty(epName)) epName = e.group(4);
            String[] dup = v33Seen.get(e.group(1));
            if (dup != null) {
                dup[1] = epName;   // 后出现的（选集面板里的）名字为准，替掉「下一集」按钮的名字
                continue;
            }
            List<String[]> eps = grouped.get(line);
            if (eps == null) {
                eps = new ArrayList<>();
                grouped.put(line, eps);
            }
            String[] ent = new String[]{e.group(4), epName, e.group(1)};
            v33Seen.put(e.group(1), ent);
            eps.add(ent);
        }
        // push v33 L1（Liu 2026-11-23）：线路名只认 #playlistN 锚点，ds6 等模板（麦田）tab 无 href → 全落「线路N」。
        //   按页面 tab 与其后选集面板的下标配对补名，并按面板顺序（= 网页 tab 顺序）排线路；校验不过什么都不改。
        tabPanelLineNames(html, grouped, lineName);
        // r362：无真实选集时用 JSON-LD 直链补一集（wangfei.tv：0 个 /vodplay/，29 个 artdetail）
        jsonLdFallback(vod, html, grouped, lineName);
        joinPlay(vod, grouped, lineName, null);
        return vod;
    }

    private static final Pattern V33_TAB = Pattern.compile("<(?:div|ul)[^>]+class=\"[^\"]*(?:anthology-tab|play_from|from_list|playfrom)[^\"]*\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern V33_PANEL = Pattern.compile("<(?:div|ul)[^>]+(?:class=[\"'](?:[^\"']*\\s)?(?:anthology-list-box|tab-pane|playlist-box)(?:\\s[^\"']*)?[\"']|id=[\"'](?:playlist|down)\\d*[\"'])", Pattern.CASE_INSENSITIVE);
    private static final Pattern V33_SID = Pattern.compile("/vodplay/\\d+-(\\d+)-\\d+\\.html");

    /**
     * push v33 L1：tab ↔ 选集面板按下标配对取线路真名（同推送探针 tab/面板锚定的认法，结构认，不看尺寸/时长）。
     * 只在有分组线路缺名字时跑；tab 数 = 面板数、每个面板都有线路号、线路号互不重复且都在已分组里才采用；
     * 只补缺名（#playlist 已有名不覆盖）；线路顺序改为面板顺序，面板外的线路原样排后。
     */
    private void tabPanelLineNames(String html, LinkedHashMap<String, List<String[]>> grouped, LinkedHashMap<String, String> lineName) {
        try {
            if (TextUtils.isEmpty(html) || grouped == null || grouped.isEmpty()) return;
            List<String> miss = new ArrayList<>();
            for (String k : grouped.keySet()) if (TextUtils.isEmpty(lineName.get(k))) miss.add(k);
            if (miss.isEmpty()) return;
            Matcher t = V33_TAB.matcher(html);
            if (!t.find()) return;
            int ts = t.start();
            int end = html.indexOf("anthology-list", t.end());
            String seg = html.substring(ts, end > 0 ? end : Math.min(html.length(), ts + 8000));
            if (seg.length() > 8000) seg = seg.substring(0, 8000);
            List<String> tabs = new ArrayList<>();
            Matcher a = Pattern.compile("<a\\b[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(seg);
            while (a.find()) tabs.add(v33TabText(a.group(1)));
            if (tabs.isEmpty()) {
                Matcher li = Pattern.compile("<li\\b[^>]*>(.*?)</li>", Pattern.DOTALL).matcher(seg);
                while (li.find()) tabs.add(v33TabText(li.group(1)));
            }
            List<Integer> starts = new ArrayList<>();
            Matcher p = V33_PANEL.matcher(html);
            while (p.find()) if (p.start() > ts) starts.add(p.start());
            List<String> sids = new ArrayList<>();
            for (int i = 0; i < starts.size(); i++) {
                int s = starts.get(i);
                int e = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
                Matcher sm = V33_SID.matcher(html);
                sm.region(s, e);
                sids.add(sm.find() ? sm.group(1) : "");
            }
            boolean ok = tabs.size() == sids.size() && !sids.contains("") && new java.util.HashSet<>(sids).size() == sids.size();
            if (ok) for (String sd : sids) if (!grouped.containsKey(sd)) ok = false;
            if (!ok) {
                return;
            }
            for (int i = 0; i < tabs.size(); i++) {
                String tn = tabs.get(i);
                String sd = sids.get(i);
                if (miss.contains(sd) && !TextUtils.isEmpty(tn) && tn.length() <= 24 && !tn.matches("\\d+")) lineName.put(sd, tn);
            }
            LinkedHashMap<String, List<String[]>> re = new LinkedHashMap<>();
            for (String sd : sids) re.put(sd, grouped.get(sd));
            for (Map.Entry<String, List<String[]>> en : grouped.entrySet()) if (!re.containsKey(en.getKey())) re.put(en.getKey(), en.getValue());
            grouped.clear();
            grouped.putAll(re);
        } catch (Throwable ignored) {
        }
    }

    /** push v33 L1：tab 文字——去 <i> 图标、去 badge/num/count/gm 角标、去标签、反转义、去私用区图标字、压空白。 */
    private static String v33TabText(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?is)<i\\b[^>]*>.*?</i>", "");
        t = t.replaceAll("(?is)<(span|em|sup|small|b)\\b[^>]*class=\"[^\"]*\\b(badge|num|count|gm)\\b[^\"]*\"[^>]*>.*?</\\1>", "");
        t = t.replaceAll("<[^>]+>", "");
        t = unescapeHtml(t);
        t = t.replaceAll("[\\uE000-\\uF8FF]", "");
        return t.replaceAll("[\\s\\u00A0\\u3000]+", " ").trim();
    }

    private JSONObject genericDetail(String html, String id) throws Exception {
        JSONObject vod = baseDetail(html, id);
        vod.put("vod_name", detailTitle(html));
        vod.put("vod_pic", detailPic(html));
        vod.put("vod_year", firstMatch(html, "年份[：:]\\s*(?:<[^>]+>\\s*)*([0-9]{4})", "年代[：:]\\s*(?:<[^>]+>\\s*)*([0-9]{4})"));
        vod.put("vod_area", firstMatch(html, "地区[：:]\\s*(?:<[^>]+>\\s*)*([^<\\s]{1,12})"));
        String gIntro = clean(firstMatchGroup(html, "<div[^>]+(?:class|id)=\"[^\"]*(?:desc|intro|summary|juqing|plot)[^\"]*\"[^>]*>(.*?)</div>"));
        if (!TextUtils.isEmpty(gIntro)) vod.put("vod_content", gIntro);
        genericIntroFallback(vod, html);
        LinkedHashMap<String, List<String[]>> grouped = new LinkedHashMap<>();
        LinkedHashMap<String, String> tabLineName = new LinkedHashMap<>();
        boolean tabSplit = tabAnchorLines(html, grouped, tabLineName);
        // r249：只认明确播放路由（见 P_PLAY_ROUTE），并把「本页自身」剔掉，避免自链接成集。
        Matcher e = P_PLAY_ROUTE.matcher(tabSplit ? "" : html);
        int idx = 0;
        java.util.Set<String> seenEp = new java.util.HashSet<>();
        while (e.find()) {
            String href = e.group(1);
            if (samePage(href, id)) continue;
            // r356：href 去重 —— ddys.la 详情页「立即播放」大按钮与选集区第 1 集同 href，
            // 旧逻辑不去重 → 第 1 集出现两次（verify 实证 40→39）。所有站通用无回归。
            if (!seenEp.add(href)) continue;
            eps(grouped, "line1").add(new String[]{String.valueOf(++idx), clean(e.group(2)).isEmpty() ? ("第" + idx + "集") : clean(e.group(2)), href});
            if (idx > 500) break;
        }
        LinkedHashMap<String, String> lineName = new LinkedHashMap<>();
        lineName.put("line1", "默认线路");
        if (tabSplit) {
            lineName.putAll(tabLineName);
        }
        if (grouped.isEmpty()) {
            // r344：合集/专辑型详情页 —— 页面本身不是播放页，而是同模式高频链接列表
            //（真机 18:05 both.folrl0a2w.cc/moviesets/AiCollection/：24 条 /video/\d+/ 主区链接，
            //  无 ql-video、无 packer）。转成选集后用户选第 N 集进普通视频页，由 WebView 嗅探起播（浏览器已验证可播）。
            // 放这里而不是 detailContent 的 r342 分支：genericDetail 的 r249 兜底**先把**
            // vod_play_from 设成「页面播放」，detailContent 再判 isEmpty 永远赶不上（r342 真机死代码实证）。
            // r345：传 id 供翻页聚合（rel="next" 尾页码模板推导 + COLLECTION_CACHE 缓存键）。
            List<String[]> ceps = collectionEpisodes(html, id);
            if (ceps != null) {
                for (int i = 0; i < ceps.size(); i++) {
                    String name = TextUtils.isEmpty(ceps.get(i)[0]) ? ("第" + (i + 1) + "集") : ceps.get(i)[0];
                    eps(grouped, "line1").add(new String[]{String.valueOf(i + 1), name, ceps.get(i)[1]});
                }
                lineName.put("line1", "合集");
                log("合集选集 " + ceps.size() + " 集（" + abs(id) + "）");
            }
        }
        // push v50（mrds 修法 A）：页内有播放器节点（与合集门同一个判断 hasPlayerNode）时先交给 dplayer 抽真线路，
        //   再轮到 JSON-LD —— mrds 的 VideoObject.embedUrl 是文章页地址本身而不是媒体，先用它会把单片变成「直链=本页」。
        boolean playerFirst = grouped.isEmpty() && hasPlayerNode(html);
        if (playerFirst) pagePlaylistEpisodes(html, grouped, lineName);
        // r362：JSON-LD 直链优先于「本页嗅探」—— 嗅探只有 WebView 才跑得起来（~20s），
        // JSON-LD 是页面里现成的 m3u8，纯 HTTP 直接拿。先试它，失败才落到下面的嗅探兜底。
        jsonLdFallback(vod, html, grouped, lineName);
        // r4xx（通用化）：页内多播放器节点全部转成选集（昏黄影视 rules.md §9 通用经验），
        //   不认具体站点/class，覆盖 dplayer/xgplayer/video/source 等任意播放器形态。
        if (grouped.isEmpty() && !playerFirst) pagePlaylistEpisodes(html, grouped, lineName);
        if (grouped.isEmpty()) {
            // r249：抽不到真实播放路由就给「本页播放」一条线路，把详情页交给壳内 WebView 嗅探。
            // A 站播放地址在 eval(function(p,a,c,k,e,d){...}) 里运行时生成，纯 HTTP 拿不到 m3u8，
            // 只有 WebView 打开详情页（parse=1）才嗅得到；旧行为是把分类页当播放页，永远播不动。
            // r528：但**有盾的 macCMS 站详情页里没有播放器** —— ccguard 之类纯 HTTP 只拿到
            // 「正在验证访问」壳，detail 抽不到播放路由，入口就变成 /view/123.html；
            // WebView 打开详情页盾过得再好也嗅不到 m3u8（实测奇优影院「添加后无法播放」）。
            // 归一到播放页 /play/123-0-1.html：盾由浏览器自己写 cookie+reload 过掉，
            // 播放页 iframe 里的三方播放器自己算实时签名发请求，网络层嗅探最终 m3u8 —— 零站点适配。
            List<String[]> self = new ArrayList<>();
            String entry = abs(id);
            String pp = playPageOf(entry, html);
            if (!TextUtils.isEmpty(pp)) entry = pp;
            self.add(new String[]{"1", "页面播放", entry});
            grouped.put("line1", self);
            lineName.put("line1", "页面播放");
            log("detail 无播放路由 → 本页嗅探 " + entry + (TextUtils.isEmpty(pp) ? "" : "（已归一到播放页）"));
        }
        joinPlay(vod, grouped, lineName, "line1");
        return vod;
    }

    /**
     * push v34（D1）：通用详情按标签页拆线路 —— 线路标签 &lt;a data-toggle="tab" href="#X"&gt;名字&lt;/a&gt;（属性先后都认）
     * 与面板 id="X"（div/ul）按锚点一一配对；面板选集 = 面板内第一个播放链接起、到其后第一个 &lt;/ul&gt; 为止的播放链接
     * （截掉面板下方「猜你喜欢」推荐卡），面板内按地址去重（本页自身也算一集：播放页当前集）。
     * 全部满足才采用：≥2 个标签、每个标签都找得到面板、≥2 个面板真有选集；否则返回 false 走老逻辑。结构认，不认站名。
     */
    private boolean tabAnchorLines(String html, LinkedHashMap<String, List<String[]>> grouped, LinkedHashMap<String, String> lineName) {
        try {
            if (TextUtils.isEmpty(html) || !html.contains("data-toggle")) return false;
            Matcher tm = Pattern.compile("<a[^>]*data-toggle=\"tab\"[^>]*href=\"#([A-Za-z][\\w-]{0,30})\"[^>]*>(.*?)</a>"
                    + "|<a[^>]*href=\"#([A-Za-z][\\w-]{0,30})\"[^>]*data-toggle=\"tab\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
            List<String[]> tabs = new ArrayList<>();
            java.util.Set<String> tids = new java.util.HashSet<>();
            while (tm.find()) {
                String tid = tm.group(1) != null ? tm.group(1) : tm.group(3);
                String nm = clean(tm.group(2) != null ? tm.group(2) : (tm.group(4) != null ? tm.group(4) : ""));
                if (tids.add(tid)) tabs.add(new String[]{tid, nm});
            }
            if (tabs.size() < 2) return false;
            int[] starts = new int[tabs.size()];
            for (int i = 0; i < tabs.size(); i++) {
                Matcher pm = Pattern.compile("<(?:div|ul)[^>]+id=\"" + Pattern.quote(tabs.get(i)[0]) + "\"").matcher(html);
                if (!pm.find()) return false;
                starts[i] = pm.start();
            }
            LinkedHashMap<String, List<String[]>> g = new LinkedHashMap<>();
            LinkedHashMap<String, String> names = new LinkedHashMap<>();
            int withEps = 0;
            for (int i = 0; i < tabs.size(); i++) {
                int s = starts[i];
                int e = -1;
                for (int x : starts) if (x > s && (e < 0 || x < e)) e = x;
                if (e < 0) e = Math.min(html.length(), s + 60000);
                String seg = html.substring(s, e);
                Matcher fa = P_PLAY_ROUTE.matcher(seg);
                if (fa.find()) {
                    int ue = seg.indexOf("</ul>", fa.end());
                    if (ue > 0) seg = seg.substring(0, ue);
                }
                List<String[]> list = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                Matcher a = P_PLAY_ROUTE.matcher(seg);
                int n = 0;
                while (a.find()) {
                    String href = a.group(1);
                    if (!seen.add(href)) continue;
                    String epName = clean(a.group(2));
                    n++;
                    list.add(new String[]{String.valueOf(n), epName.isEmpty() ? ("第" + n + "集") : epName, href});
                    if (n > 2000) break;
                }
                if (list.isEmpty()) continue;
                withEps++;
                String key = "tab" + (i + 1);
                g.put(key, list);
                String nm = tabs.get(i)[1];
                names.put(key, TextUtils.isEmpty(nm) ? ("线路" + (i + 1)) : nm);
            }
            if (withEps < 2) return false;
            grouped.putAll(g);
            lineName.putAll(names);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * push v34（D2）：通用详情简介兜底 —— 现有取到的简介不足 20 字时，依次回落：
     * &lt;p class="…desc…"&gt; 文字（去「简介：」标签与末尾「详情」）→ og:description → meta description（均须 ≥20 字）。
     * 都没有就保留原值。
     */
    private void genericIntroFallback(JSONObject vod, String html) {
        try {
            String cur = clean(vod.optString("vod_content"));
            if (cur.length() >= 20 || isErrorPage(html)) return;
            String cand = "";
            Matcher pm = Pattern.compile("<p[^>]+class=\"[^\"]*desc[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(html);
            while (pm.find()) {
                String c = clean(pm.group(1)).replaceAll("^(剧情)?简介[：:]\\s*", "").replaceAll("\\s*详情\\s*$", "").trim();
                if (c.length() >= 20) { cand = c; break; }
            }
            if (cand.isEmpty()) {
                String og = clean(firstMatchGroup(html, "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']{8,1500})[\"']"));
                if (og.length() < 20) og = clean(firstMatchGroup(html, "<meta[^>]+content=[\"']([^\"']{8,1500})[\"'][^>]+property=[\"']og:description[\"']"));
                if (og.length() >= 20) cand = og;
            }
            if (cand.isEmpty()) {
                String md = clean(firstMatchGroup(html, "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']{8,1500})[\"']"));
                if (md.length() < 20) md = clean(firstMatchGroup(html, "<meta[^>]+content=[\"']([^\"']{8,1500})[\"'][^>]+name=[\"']description[\"']"));
                if (md.length() >= 20) cand = md;
            }
            if (!cand.isEmpty()) vod.put("vod_content", cand);
        } catch (Throwable ignore) {
        }
    }

    /** r249：判两个地址是否同一页（忽略协议尾斜杠与 query），用于剔掉详情页自链接。 */
    private boolean samePage(String a, String b) {
        String x = normUrl(abs(a));
        String y = normUrl(abs(b));
        return !TextUtils.isEmpty(x) && x.equals(y);
    }

    private static String normUrl(String u) {
        if (TextUtils.isEmpty(u)) return "";
        String s = u.trim();
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    private List<String[]> eps(LinkedHashMap<String, List<String[]>> grouped, String key) {
        List<String[]> list = grouped.get(key);
        if (list == null) {
            list = new ArrayList<>();
            grouped.put(key, list);
        }
        return list;
    }

    /** 把「线路 → 剧集」拼成壳要的 vod_play_from / vod_play_url。 */
    private void joinPlay(JSONObject vod, LinkedHashMap<String, List<String[]>> grouped, LinkedHashMap<String, String> lineName, String forceKey) throws Exception {
        StringBuilder from = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (Map.Entry<String, List<String[]>> entry : grouped.entrySet()) {
            List<String[]> eps = entry.getValue();
            if (eps == null || eps.isEmpty()) continue;
            eps.sort((a, b) -> parseInt(a[0], 0) - parseInt(b[0], 0));
            String name = lineName.get(entry.getKey());
            if (TextUtils.isEmpty(name)) name = forceKey != null ? "默认线路" : ("线路" + entry.getKey());
            if (from.length() > 0) {
                from.append("$$$");
                urls.append("$$$");
            }
            from.append(name);
            for (int i = 0; i < eps.size(); i++) {
                if (i > 0) urls.append("#");
                urls.append(eps.get(i)[1]).append("$").append(abs(eps.get(i)[2]));
            }
        }
        vod.put("vod_play_from", from.toString());
        vod.put("vod_play_url", urls.toString());
    }

    private JSONObject baseDetail(String html, String id) throws Exception {
        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", detailTitle(html));
        vod.put("vod_pic", "");
        vod.put("vod_remarks", "");
        vod.put("vod_year", "");
        vod.put("vod_area", "");
        vod.put("vod_content", detailIntro(html));
        vod.put("vod_play_from", "");
        vod.put("vod_play_url", "");
        return vod;
    }

    /**
     * r252：简介取页面真值 —— 先 og:description / meta description，再页面区块，最后长段落。
     *
     * <p>A 站详情页没有「简介」区块，旧规则拿 class 含 content 的容器去捞，捞到页头用户菜单
     * （真机日志 vod_content="个人设置 退出登录"），所以简介全错。
     */

    /**
     * r389（AGENTS.md 第二十节）：**死页判据**（404 / 页面不存在）。
     *
     * <p>与 {@link #isErrorPage} 分开的原因：isErrorPage 对 >8000 字一律 false（r288 教训：
     * 长页面里播放器文案就含「正在验证」，用它判会把真实详情页误杀），而 404 页往往很长
     * （47o 的「这个页面走丢了」24255 字），页内导航链接足以让 genericItems 抽出 1~2 条垃圾，
     * 翻页判定就被骗过 —— 真机第 2 页拿到 404 页就是这条路径。</p>
     *
     * <p>只看标题级信号，零误伤：正常内容页标题不会写「走丢了 / 页面不存在 / 404」。</p>
     */
    private static boolean isDeadPage(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String title = firstMatch(html, "<title[^>]*>\\s*([^<]{0,120})</title>");
        if (TextUtils.isEmpty(title)) return false;
        String t = title.toLowerCase(Locale.ROOT);
        return t.contains("走丢") || t.contains("页面不存在") || t.contains("页面未找到")
                || t.contains("页面出错") || t.contains("出错了") || t.contains("已被删除")
                // r486：m.x33yq.org 404 页 title 正常、错误文案在正文（「真的很抱歉，我们搞丢了页面……」），
                //   旧词表漏拦 → 该页被 genericItems 抽出 1 条"首页链接"当内容（真机 tid=top page=2 实证）。
                || t.contains("搞丢") || t.contains("not found");
    }
    /**
     * r258：站点源站故障（Cloudflare 520/522）或人机验证页 —— 这类页面里的文字绝不能当标题/简介，
     * 否则详情页会显示「2024913.xyz | 520: Web server is returning an unknown error」这种垃圾。
     */
    private static boolean isErrorPage(String html) {
        if (TextUtils.isEmpty(html)) return true;
        String low = html.toLowerCase(Locale.ROOT);
        // 明确的服务端错误：任何大小都算
        if (low.contains("web server is returning") || low.contains("error code 52")) return true;
        if (low.contains("cloudflare") && low.contains("unknown error")) return true;
        // r288：关键词判定只对「短页面」生效 —— 真实内容页（几十万字符）里播放器文案本身就含
        // 「请稍候 / 正在验证 / 访问验证」，用它们判错误页会把真实播放页误杀。
        // 真机 13:11 铁证：detail cap 已通过取到 349237 字 → 紧接着被判「页面不可用」→ 标题/简介全空。
        if (html.length() > 8000) return false;
        if (low.contains("访问验证") || low.contains("just a moment") || low.contains("attention required")) return true;
        if (low.contains("请稍候") || low.contains("正在验证") || low.contains("检查您的浏览器")) return true;
        // 极短且完全没有内容特征才当作空壳
        return html.length() < 200 && !low.contains("<h1") && !low.contains("<a ") && !low.contains("description");
    }

    private String detailIntro(String html) {
        if (isErrorPage(html)) return "";
        String intro = firstMatchGroup(html, "<meta[^>]+(?:property|name)=[\"'](?:og:description|description|twitter:description)[\"'][^>]+content=[\"']([^\"']{8,800})[\"']");
        if (TextUtils.isEmpty(intro)) intro = firstMatchGroup(html, "<meta[^>]+content=[\"']([^\"']{8,800})[\"'][^>]+(?:property|name)=[\"'](?:og:description|description|twitter:description)[\"']");
        if (TextUtils.isEmpty(intro)) intro = firstMatchGroup(html, "<(?:div|span|p)[^>]+(?:class|id)=[\"'][^\"']*(?:desc|intro|summary|juqing|plot|synopsis)[^\"']*[\"'][^>]*>(.*?)</(?:div|span|p)>");
        // r356：content|detail 兜底改为循环验证 —— ddys.la 详情页 DOM 顺序上 stui-content__thumb
        // （封面容器，clean 后为空）在 detail-content 之前，旧 firstMatchGroup 只取第一个命中
        // → 简介永远空。逐个匹配 clean 验证 ≥20 字才采用（verify 实证，其他站行为不变）。
        if (TextUtils.isEmpty(intro)) {
            Matcher im = Pattern.compile("<(?:div|span|p)[^>]+(?:class|id)=[\"'][^\"']*(?:content|detail)[^\"']*[\"'][^>]*>(.{30,800}?)</(?:div|span|p)>").matcher(html);
            while (TextUtils.isEmpty(intro) && im.find()) {
                String cand = clean(im.group(1));
                if (cand.length() >= 20 && !cand.contains("个人设置") && !cand.contains("退出登录") && !cand.contains("登录注册")) intro = im.group(1);
            }
        }
        if (TextUtils.isEmpty(intro)) intro = firstMatchGroup(html, "<p[^>]*>([^<]{30,800})</p>");
        String out = clean(intro);
        if (out.length() < 20 || out.contains("个人设置") || out.contains("退出登录") || out.contains("登录注册")) return "";
        return out;
    }

    /**
     * r461：详情标题抽取 —— 候选按可信度排序 + **广告/公告文本过滤**。
     * 背景：不少站把 <title> 写成推广语（mnn.hsh6 实证：<title>【https://ujh.balecao1.com/x/】黄上黄永久网址，请您牢记</title>），
     *   旧实现 h1 取不到就直取 <title> → 片名变成公告（真机日志 vod_name 与弹幕剧名都是这句广告）。
     * 策略：h1 → og:title → <title>（保持旧截断；命中广告则丢弃）→ meta keywords 首段（去「第N集免费在线观看」尾）。
     */
    /** push v33：本线程最近一次 detailTitle 是否回落到 <title>（无合格 h1 / og:title）。 */
    private static final ThreadLocal<Boolean> V33_TITLE_FROM_TAG = new ThreadLocal<>();

    private String detailTitle(String html) {
        V33_TITLE_FROM_TAG.set(Boolean.FALSE);
        if (isErrorPage(html)) return "";
        // r468：**h1 可能有多个，且第一个常是站点名/logo** —— fp.xiezhen8 浏览器实测：
        //   h1 = ["写真吧", "XiuRen秀人网 No.11319 Yang Chen Chen (73P)"]，旧写法 firstMatch 取第一个 →
        //   vod_name="写真吧" → 图片查看器/历史/弹幕标题全变成站点名（用户报「前几轮就说过，还犯」）。
        //   改为：遍历所有 h1，跳过「站点名 / 广告词」，取第一个合格者。
        String siteName = siteNameOf(html);
        String t = "";
        Matcher hm = Pattern.compile("<h1[^>]*>(?:<a[^>]*>)?([^<]{2,80})").matcher(html);
        while (hm.find()) {
            String c = clean(hm.group(1));
            if (TextUtils.isEmpty(c) || adLikeText(c)) continue;
            if (!TextUtils.isEmpty(siteName) && c.equals(siteName)) continue;
            t = c;
            break;
        }
        if (TextUtils.isEmpty(t)) t = clean(firstMatch(html, "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]{1,80})\""));
        if (TextUtils.isEmpty(t)) {
            t = clean(firstMatch(html, "<title[^>]*>([^<|_\\-]{1,60})"));
            V33_TITLE_FROM_TAG.set(Boolean.TRUE);
        }
        if (adLikeText(t)) {
            // r461：<title> 是推广语 → 改从 meta keywords 首段取（keywords 常写「片名第N集免费在线观看,片名剧情介绍」）。
            String kw = firstMatch(html, "<meta[^>]+name=\"keywords\"[^>]*content=\"([^\"]{1,200})\"");
            if (!TextUtils.isEmpty(kw)) {
                int c = kw.indexOf(',');
                t = clean(c > 0 ? kw.substring(0, c) : kw);
            }
        }
        if (adLikeText(t)) t = "";
        t = t.replaceAll("(第\\d+集)?(全集)?免费在线观看.*$", "").replaceAll("在线观看.*$", "")
                .replaceAll("(第\\d+集|剧情介绍|在线播放|免费观看|高清完整版).*$", "").trim();
        return t;
    }

    /** r468：站点名（从 <title> 的「-」/「_」/「|」后缀取）—— 用于把 h1 里的站点名排除掉。 */
    private String siteNameOf(String html) {
        String t = clean(firstMatch(html, "<title[^>]*>([^<]{2,120})</title>"));
        if (TextUtils.isEmpty(t)) return "";
        String[] parts = t.split("[-_|]");
        String last = parts.length > 1 ? parts[parts.length - 1].trim() : "";
        return (last.length() >= 2 && last.length() <= 20) ? last : "";
    }

    /** r461：广告/公告文本特征（站点把 <title> 写成推广语时不得拿来当片名）。只列强特征，避免误伤正常站名后缀。 */
    private boolean adLikeText(String t) {
        if (TextUtils.isEmpty(t)) return true;
        String s = t.toLowerCase();
        if (s.contains("http://") || s.contains("https://") || s.contains("www.")) return true;
        for (String k : new String[]{"永久网址", "请您牢记", "请牢记", "发布器", "下载地址", "最新地址",
                "收藏本站", "防走失", "备用网址", "地址发布", "发布页"}) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    /**
     * r320：从音频页抽取分集地址。同类站普遍把分集写成「同前缀 + 递增序号」的文件名，
     * 例如飞卢听书：http://tss.faloo.com/ListenNode/2/2350/1.mp3 … N.mp3。
     * 做法：按「目录 + 去尾号词干」分组，取数量最多且 ≥2 的那组，按序号升序返回。
     */
    private List<String> collectAudioEpisodes(String html) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return out;
        Matcher m = Pattern.compile(
                "(https?://[^\"'\\s\\\\]{4,200}?/)([^\"'\\s/]{0,40}?)(\\d{1,4})\\.(?:mp3|m4a|aac|flac|wav|ogg)",
                Pattern.CASE_INSENSITIVE).matcher(html);
        Map<String, Map<Integer, String>> groups = new LinkedHashMap<>();
        while (m.find()) {
            int idx;
            try {
                idx = Integer.parseInt(m.group(3));
            } catch (Throwable e) {
                continue;
            }
            String key = m.group(1) + m.group(2);
            Map<Integer, String> g = groups.get(key);
            if (g == null) {
                g = new LinkedHashMap<>();
                groups.put(key, g);
            }
            g.put(idx, m.group(0));
        }
        Map<Integer, String> best = null;
        for (Map<Integer, String> g : groups.values()) if (best == null || g.size() > best.size()) best = g;
        if (best == null || best.size() < 2) return out;
        List<Integer> idxs = new ArrayList<>(best.keySet());
        java.util.Collections.sort(idxs);
        for (Integer i : idxs) out.add(best.get(i));
        return out;
    }

    /**
     * r535：详情页是否有**显式封面语义**（og:image / video-pic）。
     * 站点把封面区换成广告区（hsck read_zone 实证）时两者都没有，detailPic 只能降级 pickImg(html)
     * 抓页面第一张图 → 抓到广告大图。此时应改用列表页在卡片 DOM 上下文抽到的正确封面。
     */
    private boolean hasExplicitDetailCover(String html) {
        if (TextUtils.isEmpty(html)) return false;
        return html.contains("og:image")
                || html.contains("video-pic")
                || html.contains("stui-player__thumb")
                || html.contains("vod_pic")
                || html.contains("detail-pic");
    }

    private String detailPic(String html) {
        String pic = firstMatch(html, "<a[^>]+class=\"video-pic[^\"]*\"[^>]+(?:data-background|data-original)=\"([^\"]+)\"");
        if (TextUtils.isEmpty(pic)) pic = firstMatchGroup(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']");
        // r283：详情封面不做域名探活 —— pickImgOk 会 HEAD 2.5s，主域名不通还要再等 2.5s，
        // 真机实测 detail 因此从 2 秒级涨到 14 秒。列表页保留探活，详情页直接取。
        if (TextUtils.isEmpty(pic) || isPlaceholderPic(pic)) pic = pickImg(html);
        return abs(finishPic(pic));
    }

    /**
     * r536：广告注入型详情页（hsck 实证）封面区被顶部横幅占位 —— body 开头几十个
     * <a href=博彩外链 target=_blank><img ...gif width=100% height=60px></a>，
     * pickImg 全局扫到的第一个 img 就是这类广告图（封面错位根因）。
     * 判据：若 pic 是「顶部通栏小横幅图」（外链 <a target=_blank> 包裹 或 width=100%+height<=120px 的 gif），
     * 则拒绝，改从正文「指向本片的推荐缩略图 <a class=stui-vodlist__thumb data-original=...>」里挑。
     * 只按形态、不看域名，正常站（og:image/video-pic 已在前面命中）零影响。
     */
    private static boolean isAdBannerPic(String pic, String html) {
        if (TextUtils.isEmpty(pic)) return false;
        String u = pic.toLowerCase(Locale.ROOT);
        boolean gif = u.contains(".gif");
        // 横幅特征：紧邻的 <a 包裹里 target=_blank，且图片本身 width=100% / height 小值
        Pattern wrap = Pattern.compile(
                "<a\\b[^>]*target=[\"']?_blank[\"']?[^>]*>\\s*<img\\b[^>]{0,800}?>" , Pattern.CASE_INSENSITIVE);
        if (wrap.matcher(html).find() && gif) return true;
        Pattern strip = Pattern.compile(
                "<img\\b[^>]{0,400}?src=[\"'][^\"']*?" + Pattern.quote(u.substring(Math.max(0, u.lastIndexOf('/') + 1))) + "[^\"']*?[\"'][^>]{0,400}?(?:width=[\"']?100%|height=[\"']?\\d{1,3}px)",
                Pattern.CASE_INSENSITIVE);
        return gif && strip.matcher(html).find();
    }

    /** r536：从正文「指向本片的推荐卡片」里取真封面（stui-vodlist__thumb 的 data-original）。 */
    private static String selfThumbPic(String html, String url, String id) {
        if (TextUtils.isEmpty(html)) return "";
        String tail = "";
        String src = TextUtils.isEmpty(id) ? url : id;
        if (!TextUtils.isEmpty(src)) {
            int slash = src.lastIndexOf('/');
            if (slash >= 0) tail = src.substring(slash + 1);   // 例 292557-1-1.html
        }
        if (TextUtils.isEmpty(tail)) return "";
        Matcher m = Pattern.compile(
                "<a\\b[^>]+href=[\"'][^\"']*?" + Pattern.quote(tail) + "[\"'][^>]*?data-original=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return unescapeHtml(m.group(1));
        // 属性顺序颠倒的站
        Matcher m2 = Pattern.compile(
                "<a\\b[^>]+data-original=[\"']([^\"']+)[\"'][^>]*?href=[\"'][^\"']*?" + Pattern.quote(tail) + "[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m2.find()) return unescapeHtml(m2.group(1));
        return "";
    }

    // ------------------------------------------------------------------ 搜索

    /**
     * r511：macCMS V10 原生搜索接口 —— {@code /index.php/ajax/suggest?mid=1&wd=xxx}
     * 返回 {@code {"code":1,"list":[{"id":235689,"name":"庆余年2","pic":"…"}]}}。
     * 不少模板（mtyy2 实证）只做了搜索框、没做搜索页：HTML 路由一律 200 空模板，
     * 但 suggest 接口是好的。这条既是首选也可能是唯一可用路径。
     */
    private JSONArray maccmsSuggest(String key) {
        JSONArray arr = new JSONArray();
        try {
            String kw = URLEncoder.encode(key, "UTF-8");
            for (String p : new String[]{"/index.php/ajax/suggest?mid=1&wd=", "/ajax/suggest?mid=1&wd="}) {
                String body = get(root + p + kw);
                if (TextUtils.isEmpty(body)) continue;
                String t = body.trim();
                if (!t.startsWith("{")) continue;
                JSONObject o = new JSONObject(t);
                JSONArray l = o.optJSONArray("list");
                if (l == null) continue;
                for (int i = 0; i < l.length(); i++) {
                    JSONObject it = l.optJSONObject(i);
                    if (it == null) continue;
                    String id = it.optString("id");
                    String name = unescapeHtml(it.optString("name"));
                    if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
                    JSONObject v = new JSONObject();
                    v.put("vod_id", root + "/voddetail/" + id + ".html");
                    v.put("vod_name", name);
                    v.put("vod_pic", it.optString("pic"));
                    // push v33：suggest 结果也写列表缓存（详情片名回落时用）。
                    LIST_META.put(normKey(root + "/voddetail/" + id + ".html"), new String[]{name, it.optString("pic")});
                    v.put("vod_remarks", "");
                    arr.put(v);
                }
                if (arr.length() > 0) return arr;
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        LogCategory previousStage = enterStage(LogCategory.MEDIA);
        try {
            return searchContentStaged(key, quick, pg);
        } finally {
            leaveStage(previousStage);
        }
    }

    private String searchContentStaged(String key, boolean quick, String pg) {
        try {
            detect();
            int page = parseInt(pg, 1);
            if ("nuxt-json-aes".equals(template)) return repeatGuardJson(root + "|" + key, page, nuxtSearch(key, page));
            // r367：【首次打开提速】搜索端点发现改为懒执行 —— 打开源时不再跑
            // findSearchFromForm/Js/LinkedJs（后者会额外拉 ≤2 个外链 JS，每个 get() 超时 15s）。
            // 只有真正搜索时才做首次发现，之后走 SEARCH_ENDPOINT 静态缓存。
            if (TextUtils.isEmpty(searchEndpoint)) discoverSearch();
            JSONArray list = new JSONArray();
            String kw = URLEncoder.encode(key, "UTF-8");
            if ("youtube".equals(template)) {
                list = youtubeSearchItems(key, 20);
                list = stopRepeatedPage(root + "|" + key, page, list);   // push v45：不分页的接口第 2 页起同一批 → 收口
                return new JSONObject().put("list", list).toString();
            }
            if ("zanpian".equals(template)) {
                String body = get(root + "/index.php?s=home-search-index-wd-" + kw + "&sid=1&p=" + page);
                if (TextUtils.isEmpty(body)) body = get(root + "/search/?wd=" + kw);
                list = zanpianItems(unescapeJs(body), 0);
            } else if ("maccms".equals(template) || "maccmsphp".equals(template)) {
                // r355：maccms 分支以前**只拼写死的老路由** /index.php/vod/search.html?wd=
                // 真机 18:36 日志：nnyy.la 只有「搜索端点复用缓存」一行，之后再无任何日志、0 结果。
                // 沙箱实证：nnyy 开了 rewrite，老路由 → **500**；detect 发现的端点
                // /vodsearch/-------------.html?wd= → **200 + 15 条**。就是说 r349 已经把端点发现做对了，
                // 但 maccms 分支根本没去用 —— 不是回归，是当初覆盖面只补到 generic 分支。
                // 现在：① 发现的端点优先；② 抽空再回退老路由（未开 rewrite 的老站靠它，行为不变）。
                String ep = searchEndpoint;
                log("搜索诊断 template=" + template + " root=" + root + " ep=" + ep + " landing=" + TRANSIT_LANDED.get(hostOf(root).toLowerCase(Locale.ROOT)));
                // r511：**V10 ajax suggest 优先** —— mtyy2 实测：表单端点 /vodsearch/-------------.html?wd=
                // 返回 200/20294B 的**空搜索模板**（0 条），老路由 404；而同日可用的
                // /index.php/ajax/suggest?mid=1&wd=庆余年 → JSON 12 条（id/name/pic 齐全）。
                // 这类模板只上架了搜索框、没实现搜索页，靠 HTML 抽取永远 0 条 → 搜索「什么也搜不到」。
                list = maccmsSuggest(key);
                if (list.length() > 0) log("搜索 suggest JSON -> " + list.length() + " 条");
                if (list.length() == 0 && !TextUtils.isEmpty(ep) && ep.startsWith("POST|")) {
                    // push v34（S1）：POST 搜索端点（无第 2 页：站方 GET 翻页回首页）
                    if (page > 1) {
                        log("搜索第 " + page + " 页：POST 表单搜索没有分页 → 收口，不再翻页");
                        return new JSONObject().put("list", new JSONArray()).toString();
                    }
                    String body = postSearch(ep, key);
                    list = "maccmsphp".equals(template) ? maccmsPhpItems(body, 0) : maccmsItems(body, 0);
                    if (list.length() == 0) list = searchItems(body);
                    if (list.length() > 0 && searchSaysNoResult(body)) list = new JSONArray();
                    log("搜索尝试(POST) " + ep.split("\\|", -1)[1] + " -> " + list.length() + " 条");
                } else if (list.length() == 0 && !TextUtils.isEmpty(ep)) {
                    String u = applySearchPage(fillKw(ep, key), page);   // push v50：路径段里空格编 %20
                    String mPageTpl = page > 1 ? SEARCH_PAGE_TPL.get(root + "|" + key) : null;
                    if (!TextUtils.isEmpty(mPageTpl)) {
                        u = mPageTpl.replace("{pg}", String.valueOf(page));   // push v45：站方分页链接规律优先
                        log("搜索翻页：按站方分页链接规律取第 " + page + " 页 " + u);
                    }
                    String body = getCap(u);
                    if (lastFetchCode >= 400) {   // push v44：报错页不算结果
                        log("搜索候选跳过（HTTP " + lastFetchCode + "）" + u);
                        body = "";
                    }
                    list = "maccmsphp".equals(template) ? maccmsPhpItems(body, 0) : maccmsItems(body, 0);
                    // r542：**通用兜底** —— maccmsItems 锚死数字 .html（/voddetail/123.html），
                    // 落地站点详情是 /v5/123-1-1.html 时正则能匹配；但若是 /play/123 之类的形态就 0 条。
                    // 同一份 HTML 再用 genericItems 形态自适应抽一次，命中即停（正规 maccms 页首抽取已满，
                    // 这里只在 0 条时补，零副作用）。黄色仓库 hsck 落地域名即此形态。
                    if (list.length() == 0) list = searchItems(body);
                    log("搜索尝试 " + u + " -> " + list.length() + " 条");
                    if (page <= 1 && list.length() > 0) rememberPageTpl(root + "|" + key, body, lastFetchUrl, u, key);
                }
                if (list.length() == 0) {
                    String u = root + indexPath + "/vod/search.html?wd=" + kw + (page > 1 ? "&page=" + page : "");
                    String body = getCap(u);
                    if (lastFetchCode >= 400) {   // push v44：报错页不算结果
                        log("搜索候选跳过（HTTP " + lastFetchCode + "）" + u);
                        body = "";
                    }
                    list = "maccmsphp".equals(template) ? maccmsPhpItems(body, 0) : maccmsItems(body, 0);
                    if (list.length() == 0) list = searchItems(body);
                    log("搜索尝试(老路由) " + u + " -> " + list.length() + " 条");
                }
            } else {
                // r349：generic 搜索候选链 —— 真机日志实证旧版 4 个写死路径全部不匹配真实站点
                // （用户 6 个网页源搜索全 0 结果）。优先级：① detect 阶段发现的搜索端点（表单/JS）；
                // ② 常见模板清单。每次抓取过 getCap（gaze.red 实证搜索页也被挑战页挡）。
                String[] urls = searchEndpoint != null
                        ? new String[]{searchEndpoint, "/search/?wd={kw}", "/search/?keyword={kw}",
                        "/search/{kw}/", "/search/{kw}", "/search/{kw}.html",
                        "/index.php/vod/search.html?wd={kw}", "/?s={kw}", "/vodsearch/{kw}----------1---.html",
                        "/cn/search/{kw}", "/search.html?wd={kw}", "/?q={kw}"}
                        : new String[]{"/search/?wd={kw}", "/search/?keyword={kw}",
                        "/search/{kw}/", "/search/{kw}", "/search/{kw}.html",
                        "/index.php/vod/search.html?wd={kw}", "/?s={kw}", "/vodsearch/{kw}----------1---.html",
                        "/cn/search/{kw}", "/search.html?wd={kw}", "/?q={kw}"};
                // push v45：第 2 页起只用第 1 页命中的那个候选翻页（P站/MissAV 实测：后页落进别的候选只会拿到无关页或回第 1 页）
                String hitKey = root + "|" + key;
                String hitTpl = page > 1 ? SEARCH_HIT_TPL.get(hitKey) : null;
                if (!TextUtils.isEmpty(hitTpl)) urls = new String[]{hitTpl};
                for (String tpl : urls) {
                    if (tpl.startsWith("POST|")) {
                        // push v34（S1）：POST 表单搜索端点 —— POST 无第 2 页（站方 GET 翻页 302 回首页），page>1 直接返回空。
                        if (page > 1) {
                            log("搜索第 " + page + " 页：POST 表单搜索没有分页 → 收口，不再翻页");
                            return new JSONObject().put("list", new JSONArray()).toString();
                        }
                        String ph = postSearch(tpl, key);
                        if (noRender && challenged) break;
                        list = searchItems(ph);
                        log("搜索尝试(POST) " + tpl.split("\\|", -1)[1] + " -> " + list.length() + " 条");
                        if (list.length() > 0 && (searchSaysNoResult(ph) || searchFellBackToHome(ph, key))) {
                            log("搜索候选忽略（POST：站点提示未找到或回首页）");
                            list = new JSONArray();
                        }
                        if (list.length() > 0) break;
                        continue;
                    }
                    String u = fillKw(tpl, key);   // push v50：路径段里空格编 %20，问号后照旧
                    if (!u.startsWith("http")) u = abs(u);
                    // query 型模板带分页；push v45：路径型（/search/{kw}，MissAV 实证翻页是 ?page=N）也带——
                    // maccms 横线序列型照 applySearchPage 换页码位，其余追加站方分页参数（第 1 页认出的，认不出用 page）。
                    String pageParam = SEARCH_PAGE_PARAM.getOrDefault(hostOf(u).toLowerCase(Locale.ROOT), "page");
                    String sitePageTpl = page > 1 && tpl.equals(hitTpl) ? SEARCH_PAGE_TPL.get(hitKey) : null;
                    if (!TextUtils.isEmpty(sitePageTpl)) {
                        // push v45：第 1 页结果里认出了站方分页链接规律 → 第 N 页直接用站方的地址（优先于下面的猜法）
                        u = sitePageTpl.replace("{pg}", String.valueOf(page));
                        log("搜索翻页：按站方分页链接规律取第 " + page + " 页 " + u);
                    } else if (page > 1 && !hasQueryParam(u, pageParam) && !u.contains("page=")) {
                        String ap = u.contains("?") ? u : applySearchPage(u, page);
                        if (!ap.equals(u)) u = ap;
                        else u += (u.contains("?") ? "&" : "?") + pageParam + "=" + page;
                    }
                    String searchHtml = getCap(u);
                    // push v45：站方跳转把页码丢了（P站实证：search=SSIS&page=2 → 301 到小写 search=ssis，page 没了 → 永远第 1 页），
                    // 在落地地址上补回同名页码参数再取一次。只在第 2 页起、请求带页码而落地地址不带时触发。
                    // push v45：候选被站方跳回站点首页（请求的不是首页路径、落地却是根路径 /）= 站方没按词搜，
                    // 首页内容不算搜索结果（P站实测：/search/?wd= 有时 301→/search?wd=→ 跳回 /，首页 50 条被当结果）。
                    if (lastFetchCode > 0 && lastFetchCode < 400 && !TextUtils.isEmpty(lastFetchUrl) && !lastFetchUrl.equals(u)
                            && isRootPath(lastFetchUrl) && !isRootPath(u)) {
                        log("搜索候选跳过（被站方跳回首页）" + u + " → " + lastFetchUrl);
                        continue;
                    }
                    // 只在落地地址与请求同一路径时补（仅查询串被改写）；被跳到别的路径（P站实测有时第 2 页直接跳回首页 /?page=2）
                    // = 站方没有这一页，本候选按无结果处理，不把首页内容当搜索结果。
                    if (page > 1 && lastFetchCode > 0 && lastFetchCode < 400) {
                        String landed = lastFetchUrl;
                        String pn = hasQueryParam(u, pageParam) ? pageParam : "page";
                        if (!TextUtils.isEmpty(landed) && !landed.equals(u)) {
                            if (!samePath(landed, u)) {
                                log("搜索翻页：第 " + page + " 页被站方跳到别的页面 " + landed + "，视为没有这一页");
                                continue;
                            }
                            if (hasQueryParam(u, pn) && !hasQueryParam(landed, pn)) {
                                String retry = landed + (landed.contains("?") ? "&" : "?") + pn + "=" + page;
                                log("搜索翻页：站方跳转丢了页码，补回重取 " + retry);
                                searchHtml = getCap(retry);
                                u = retry;
                                String landed2 = lastFetchUrl;
                                if (lastFetchCode > 0 && lastFetchCode < 400 && !TextUtils.isEmpty(landed2) && !samePath(landed2, retry)) {
                                    log("搜索翻页：补页码后仍被跳到别的页面 " + landed2 + "，视为没有这一页");
                                    continue;
                                }
                            }
                        }
                    }
                    // push v44：搜索候选回 4xx/5xx = 报错页（MissAV/P站 404 页的导航、页脚站群链接曾被当结果收下即停），
                    // 内容一律丢弃、试下一个候选。只看本次 get 的最终状态码，不碰首页/分类/详情路径。
                    int sc = lastFetchCode;
                    if (sc >= 400) {
                        log("搜索候选跳过（HTTP " + sc + "）" + u);
                        continue;
                    }
                    // r356：搜索页被验证码/盾拦截时明确留痕 —— ddys.la 搜索页是「系统安全验证」
                    // 页（3426B），genericItems 抽 0 条但日志只显示「-> 0 条」，分不清是没结果还是被拦。
                    if (searchHtml != null && searchHtml.length() < 8000
                            && (searchHtml.contains("系统安全验证") || searchHtml.contains("请输入密码") || searchHtml.contains("访问验证"))) {
                        log("搜索页被验证页拦截（客观受限，需人工过验证）size=" + searchHtml.length() + " " + u);
                    }
                    list = searchItems(searchHtml);
                    log("搜索尝试 " + u + " -> " + list.length() + " 条");
                    // push v46：结果区只有占位卡 + 前端模板（missav2 搜无此片）= 这站没结果，返回空、不再试别的候选（防别的候选抽出假条目）
                    if (list.length() == 0 && searchResultIsEmptyTemplate(searchHtml, key)) {
                        log("搜索无结果（结果区为空模板）" + u);
                        break;
                    }
                    // r5xx：**「站点说没搜到，但侧栏还有一堆推荐文章」识别** —— 91吃瓜 实测：
                    // /search/黑料.html 被站点当成关键词「黑料html」→ 标题「未找到黑料html的相关内容」，
                    // 正文 0 条结果，但侧栏 10 个 /archives/ 链接被 genericItems 当成结果抽走 →
                    // 用户看到的就是「搜的词不对」（内容和自己搜的毫无关系）。
                    if (list.length() > 0 && searchSaysNoResult(searchHtml)) {
                        log("搜索候选忽略（站点明确提示未找到）" + u);
                        list = new JSONArray();
                        continue;
                    }
                    // r5xx：识别「站点忽略关键词、直接回首页」—— 51吃瓜/91吃瓜 实测：/search/?wd= 410，
                    // 而 /?s=xxx 返回 200 + 227KB 首页（标题一字不差）。旧逻辑见条目就 break，
                    // 用户拿到的是首页那堆无关内容，表现为「搜的词不对、搜索不能用」。
                    if (list.length() > 0 && searchFellBackToHome(searchHtml, key)) {
                        log("搜索候选忽略（站点忽略关键词，回的是首页）" + u);
                        list = new JSONArray();
                        continue;
                    }
                    if (list.length() > 0) {
                        if (page <= 1) {
                            SEARCH_HIT_TPL.put(hitKey, tpl);
                            String pp = detectPageParam(searchHtml, u);
                            if (!TextUtils.isEmpty(pp)) SEARCH_PAGE_PARAM.put(hostOf(u).toLowerCase(Locale.ROOT), pp);
                            rememberPageTpl(hitKey, searchHtml, lastFetchUrl, u, key);
                        }
                        break;
                    }
                    // r359：壳站搜索渲染兜底 —— k84h5x 全站 ap-d 加密壳（搜索页纯 HTTP 零链接），
                    // 只对壳页触发：正常站搜索 0 结果不多花 25 秒。
                    // r360：判据扩展到 XOR document.write 加密站（sm455 型，与 detail 兜底同源），
                    // 这类站搜索页正文同样靠运行时拉取。
                    if (list.length() == 0 && (isEncryptedShell(searchHtml) || hasXorShellTrace(searchHtml))) {
                        try {
                            Map<String, String> hd = new LinkedHashMap<>();
                            String rh = webFetch(u, hd, 25000);
                            list = searchItems(rh);
                            log("搜索渲染兜底（加密壳）" + u + " -> " + list.length() + " 条");
                            if (list.length() > 0) break;
                        } catch (Throwable e) {
                            log("搜索渲染兜底（加密壳）异常 " + e);
                        }
                    }
                }
            }
            list = dedupPlayRouteItems(list);
            list = stopRepeatedPage(root + "|" + key, page, list);
            return new JSONObject().put("list", list).toString();
        } catch (Throwable e) {
            return "{\"list\":[]}";
        }
    }

    /**
     * push v45（Liu 2026-11-24 实测：翻到第 10 页仍是那几部）：第 N 页（N≥2）的条目全都在前面各页出现过 = 站方没给新页
     * （回了第 1 页或末页）→ 视为到底，返回空列表让列表停止加载。按 vod_id 判，对所有网页源通用；第 1 页重置记录。
     */
    private JSONArray stopRepeatedPage(String seenKey, int page, JSONArray list) {
        try {
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                String id = o == null ? "" : o.optString("vod_id", "").trim();   // 原样比（不能去查询串：P站 view_video.php?viewkey= 全靠它区分）
                if (!TextUtils.isEmpty(id)) ids.add(id);
            }
            if (page <= 1) {
                SEARCH_SEEN.put(seenKey, new Object[]{1, ids});
                return list;
            }
            Object[] prev = SEARCH_SEEN.get(seenKey);
            if (prev == null || ids.isEmpty()) return list;
            @SuppressWarnings("unchecked") Set<String> seen = (Set<String>) prev[1];
            if (seen.containsAll(ids)) {
                log("搜索第 " + page + " 页条目与前面各页完全重复（" + ids.size() + " 条）→ 视为到底，不再翻页");
                return new JSONArray();
            }
            Set<String> merged = new HashSet<>(seen);
            merged.addAll(ids);
            SEARCH_SEEN.put(seenKey, new Object[]{page, merged});
            return list;
        } catch (Throwable t) {
            return list;
        }
    }

    /** push v45：对已成 JSON 的搜索结果套防重复（nuxt 等提前返回的路由）。 */
    private String repeatGuardJson(String seenKey, int page, String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray l = o.optJSONArray("list");
            if (l == null) return json;
            o.put("list", stopRepeatedPage(seenKey, page, l));
            return o.toString();
        } catch (Throwable t) {
            return json;
        }
    }

    /** push v45：第 1 页命中后记下站方第 2 页地址规律（认不出就不记，翻页走原猜法 + 防重复收口）。 */
    private void rememberPageTpl(String hitKey, String html, String landed, String requested, String key) {
        try {
            String base = TextUtils.isEmpty(landed) ? requested : landed;
            String t = detectPageTpl(html, base, key);
            if (TextUtils.isEmpty(t)) {
                SEARCH_PAGE_TPL.remove(hitKey);
                return;
            }
            SEARCH_PAGE_TPL.put(hitKey, t);
            log("搜索翻页：认出站方分页链接规律 " + t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * push v45（Liu 2026-11-24「搜索其他站点的，也重复」）：从第 1 页搜索结果页认站方第 2 页地址规律，返回页码位为 {pg} 的模板。
     * 只认分页链接：rel="next"，或分页条里文字是「2」/「下一页」/next/›/» 的 <a>；链接须同站、且属于本次搜索
     * （与搜索页同路径，或地址里带关键词）。页码位按常见写法认：?page=2 / &p=2 / pg / paged / pn、/page/2、
     * 苹果CMS 横线序列 ----2---.html、-2.html、_2.html、末段 /2/。认不出返回空。
     */
    private String detectPageTpl(String html, String searchUrl, String key) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(searchUrl)) return "";
        List<String> cands = new ArrayList<>();
        Matcher rel = Pattern.compile("<(?:a|link)\\b[^>]*\\brel=[\"']?next\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (rel.find() && cands.size() < 4) {
            Matcher hm = Pattern.compile("\\bhref=[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(rel.group());
            if (hm.find()) cands.add(hm.group(1));
        }
        Matcher a = Pattern.compile("<a\\b([^>]*)>(.{0,200}?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        int n = 0;
        while (a.find() && n++ < 6000) {
            String text = a.group(2).replaceAll("<[^>]*>", "").replace("&nbsp;", " ").trim();
            String attrs = a.group(1);
            boolean two = text.equals("2");
            boolean next = text.contains("下一页") || text.contains("下页") || text.equalsIgnoreCase("next") || text.equals("›") || text.equals("»")
                    || text.equals("&gt;") || text.equals("&raquo;") || text.equals("&rsaquo;") || text.equals(">");
            if (!two && !next) continue;
            Matcher hm = Pattern.compile("\\bhref=[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(attrs);
            if (hm.find()) cands.add(hm.group(1));
        }
        String host = hostOf(searchUrl).toLowerCase(Locale.ROOT);
        String kl = key == null ? "" : key.toLowerCase(Locale.ROOT);
        for (String c : cands) {
            String h = unescapeHtml(c).trim();
            if (h.startsWith("javascript") || h.isEmpty()) continue;
            if (h.startsWith("//")) {
                h = "https:" + h;
            } else if (!h.startsWith("http")) {
                if (h.startsWith("?")) {
                    int q = searchUrl.indexOf('?');
                    h = (q < 0 ? searchUrl : searchUrl.substring(0, q)) + h;
                } else {
                    h = abs(h, searchUrl);
                }
            }
            if (!hostOf(h).toLowerCase(Locale.ROOT).equals(host)) continue;
            String dec;
            try {
                dec = java.net.URLDecoder.decode(h.replace("+", "%2B"), "UTF-8").toLowerCase(Locale.ROOT);
            } catch (Throwable e) {
                dec = h.toLowerCase(Locale.ROOT);
            }
            if (!samePath(h, searchUrl) && (kl.isEmpty() || (!dec.contains(kl) && !dec.replace('+', ' ').contains(kl)))) continue;
            String t = pageSlot(h);
            if (!TextUtils.isEmpty(t)) return t;
        }
        return "";
    }

    /** push v45：把地址里第 2 页的页码位换成 {pg}；认不出页码位返回空。 */
    private static String pageSlot(String h) {
        String[] pats = {
                "([?&](?:page|p|pg|paged|pn)=)2(?=&|$)",
                "(/page/)2(?=/|\\?|\\.html|$)",
                "(-+)2(-*\\.html)",
                "(_)2(\\.html)",
                "(/)2(/?)(?=\\?|$)"
        };
        for (String p : pats) {
            Matcher m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(h);
            if (m.find()) {
                String g2 = m.groupCount() >= 2 && m.group(2) != null ? m.group(2) : "";
                return h.substring(0, m.start()) + m.group(1) + "{pg}" + g2 + h.substring(m.end());
            }
        }
        return "";
    }

    /** push v45：两个地址是否同主机同路径（不分大小写、忽略结尾斜杠与查询串）。 */
    private static boolean samePath(String a, String b) {
        return pathKey(a).equals(pathKey(b));
    }

    /** push v45：地址路径是不是站点根（/ 或空）。 */
    private static boolean isRootPath(String u) {
        String k = pathKey(u);
        return !k.isEmpty() && k.indexOf('/') < 0;
    }

    private static String pathKey(String u) {
        if (TextUtils.isEmpty(u)) return "";
        String s = u.replaceFirst("^https?://", "");
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    /** push v45：地址的查询串里有没有名为 name 的参数。 */
    private static boolean hasQueryParam(String url, String name) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(name)) return false;
        int q = url.indexOf('?');
        if (q < 0) return false;
        for (String kv : url.substring(q + 1).split("[&#]")) {
            int e = kv.indexOf('=');
            String k = e < 0 ? kv : kv.substring(0, e);
            if (k.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * push v45：从第 1 页搜索结果里认站方分页参数名 —— 找与本次搜索同路径、查询串里某参数值为 2 的链接
     * （MissAV：/search/SSIS?page=2），参数名限 page/p/pg/paged/pn。认不出返回空（调用方用 page）。
     */
    private static String detectPageParam(String html, String searchUrl) {
        try {
            if (TextUtils.isEmpty(html) || TextUtils.isEmpty(searchUrl)) return "";
            String path = searchUrl.replaceFirst("^https?://[^/]+", "");
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.length() < 2) return "";
            Matcher m = Pattern.compile("href=[\"']([^\"'#]*\\?[^\"'#]*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            Pattern pp = Pattern.compile("(?:^|&)(page|p|pg|paged|pn)=2(?:&|$)", Pattern.CASE_INSENSITIVE);
            int seen = 0;
            while (m.find() && seen < 4000) {
                seen++;
                String h = unescapeHtml(m.group(1));
                String hp = h.replaceFirst("^https?://[^/]+", "");
                int hq = hp.indexOf('?');
                if (hq < 0 || !hp.substring(0, hq).equalsIgnoreCase(path)) continue;
                Matcher pm = pp.matcher(hp.substring(hq + 1));
                if (pm.find()) return pm.group(1);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * push v34（S2）：搜索结果同片去重 —— 某条是播放路由（/play/N-…），而同一作品号 N 已有非播放路由（详情）结果时，
     * 丢掉播放路由那条（奇优单结果页「立即播放」按钮被抽成以站名命名的第 2 条实证）。只按地址里的作品号判，不看名字/站名。
     */
    private static JSONArray dedupPlayRouteItems(JSONArray list) {
        try {
            if (list == null || list.length() < 2) return list;
            Pattern play = Pattern.compile("/(?:play|v_play|bofang|vodplay|player)/(\\d+)[-/._]");
            Pattern det = Pattern.compile("/(\\d+)(?:\\.html?)?/?$");
            java.util.Set<String> detailIds = new java.util.HashSet<>();
            for (int i = 0; i < list.length(); i++) {
                String id = normUrl(list.optJSONObject(i) == null ? "" : list.optJSONObject(i).optString("vod_id"));
                if (play.matcher(id).find()) continue;
                Matcher m = det.matcher(id);
                if (m.find()) detailIds.add(m.group(1));
            }
            if (detailIds.isEmpty()) return list;
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                String id = normUrl(o == null ? "" : o.optString("vod_id"));
                Matcher m = play.matcher(id);
                if (m.find() && detailIds.contains(m.group(1))) continue;
                out.put(o);
            }
            return out;
        } catch (Throwable t) {
            return list;
        }
    }

    /** 页面 &lt;title&gt; 纯文本（去实体、压空白）。 */
    private static String htmlTitleOf(String html) {
        if (TextUtils.isEmpty(html)) return "";
        Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (!m.find()) return "";
        return unescapeHtml(m.group(1)).replaceAll("\\s+", " ").trim();
    }

    /**
     * r5xx：搜索结果是不是「站点根本没搜，直接把首页丢回来」。
     * <p>判据（双条件，避免误杀正常站）：标题与首页标题完全一致（同一路由/同一文档）
     * 且正文规模也接近首页（±5% 或 2KB 内）。吃瓜系 {@code /?s=词} 正是这种：URL 带词、内容却是首页。
     * <p>标题里出现关键词时一律认为站点确实按词搜了，直接放行。
     */
    /**
     * push v46（Liu 2026-11-24 13:49 截图：missav2 搜站里没有的词出「韓國直播」假卡）：搜索结果页抽条目前，
     * 先把**导航性容器**整段拿掉 —— &lt;nav&gt;/&lt;aside&gt;/&lt;footer&gt;、role=navigation/menu/menubar、
     * 以及「点外面就收起」的弹出菜单（Alpine @click.outside / @click.away、Vue v-click-outside）。
     * 这些容器里的链接是导航项（分类、直播、站群），不是搜索结果。&lt;header&gt; 不拿（文章卡片标题常在卡内 header 里）。
     * 只用于搜索调用链；首页/分类/详情不走这里。找不到配对闭合标签的容器原样保留（宁可不拿，不误删正文）。
     */
    private static final Pattern NAV_REGION_OPEN = Pattern.compile(
            "<(nav|aside|footer)\\b[^>]*>|<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*(?:\\srole=\"(?:navigation|menu|menubar)\"|\\s(?:@click\\.outside|@click\\.away|x-on:click\\.outside|x-on:click\\.away|v-click-outside)\\b)[^>]*>",
            Pattern.CASE_INSENSITIVE);

    static String stripNavRegions(String html) {
        if (TextUtils.isEmpty(html)) return html;
        StringBuilder out = new StringBuilder(html.length());
        Matcher m = NAV_REGION_OPEN.matcher(html);
        int pos = 0;
        while (pos < html.length() && m.find(pos)) {
            String tag = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
            int close = matchCloseTag(html, tag, m.end());
            if (close < 0) {   // 没配对上：本标签原样保留，从它后面接着找
                out.append(html, pos, m.end());
                pos = m.end();
                continue;
            }
            out.append(html, pos, m.start());
            pos = close;
        }
        if (pos < html.length()) out.append(html, pos, html.length());
        return out.toString();
    }

    /** push v46：从 from 起找与已打开的 &lt;tag&gt; 配对的闭合标签，返回闭合标签之后的位置；找不到返回 -1。 */
    private static int matchCloseTag(String html, String tag, int from) {
        Matcher t = Pattern.compile("<(/?)" + Pattern.quote(tag) + "\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        t.region(from, html.length());
        int depth = 1;
        while (t.find()) {
            if (t.group(0).endsWith("/>")) continue;
            if (t.group(1).isEmpty()) depth++;
            else if (--depth == 0) return t.end();
        }
        return -1;
    }

    /** push v46：搜索调用链统一的条目抽取 —— 先去导航性容器再走 genericItems。 */
    private JSONArray searchItems(String html) {
        return genericItems(stripNavRegions(html), 0);
    }

    /**
     * push v46：**结果区为空模板** —— 站方服务端只给了结果网格的占位卡和前端模板（Alpine x-for / Vue v-for 的
     * &lt;template&gt;，链接写成 :href 绑定），真条目要浏览器跑脚本才出来；源码里一张影片卡都没有。
     * missav2 实证：搜「每日大赛」200，标题「每日大赛的搜尋結果」，网格里只有 x-show="! recommendItems.length"
     * 的 href="#" 占位卡 + &lt;template x-for="item in recommendItems…"&gt;（推荐片，不是搜索结果）。
     * 判据（全部满足）：本页 title 或首个 h1 含搜索词（确是这个词的结果页）；页里有 x-for/v-for 前端模板；
     * 去导航后抽不到任何条目（调用处保证）。满足即判「这站没结果」，不再试别的候选。
     */
    private static boolean searchResultIsEmptyTemplate(String html, String key) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(key)) return false;
        if (!Pattern.compile("\\s(?:x-for|v-for)=\"", Pattern.CASE_INSENSITIVE).matcher(html).find()) return false;
        String probe = htmlTitleOf(html);
        Matcher h = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (h.find()) probe = probe + " " + unescapeHtml(h.group(1)).replaceAll("<[^>]+>", "");
        return probe.toLowerCase(Locale.ROOT).contains(key.trim().toLowerCase(Locale.ROOT));
    }

    private boolean searchFellBackToHome(String html, String key) {
        try {
            if (TextUtils.isEmpty(html) || TextUtils.isEmpty(homeHtml)) return false;
            String title = htmlTitleOf(html);
            if (TextUtils.isEmpty(title)) return false;
            if (!TextUtils.isEmpty(key) && title.contains(key)) return false;
            String homeTitle = htmlTitleOf(homeHtml);
            if (TextUtils.isEmpty(homeTitle) || !title.equals(homeTitle)) return false;
            int diff = Math.abs(html.length() - homeHtml.length());
            return diff < Math.max(2000, homeHtml.length() / 20);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * r5xx：站点在页面上明说「没搜到」。只看 title 与首个 h1 —— 正文里偶尔出现「未找到」字样的站太多，
     * 全文匹配会误杀正常结果页。
     * <p>91吃瓜 实证：{@code /search/黑料.html} 被当成关键词「黑料html」→
     * 「未找到黑料html的相关内容」，但侧栏 10 条推荐仍会被抽成「搜索结果」。
     */
    private static boolean searchSaysNoResult(String html) {
        if (TextUtils.isEmpty(html)) return false;
        String probe = htmlTitleOf(html);
        Matcher h = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (h.find()) probe = probe + " " + unescapeHtml(h.group(1)).replaceAll("<[^>]+>", "");
        return Pattern.compile("未找到.{0,40}相关|没有找到.{0,30}相关|没有搜到|未搜索到|暂无.{0,12}相关|無相關|沒有找到")
                .matcher(probe).find();
    }

    // ------------------------------------------------------------------ 播放

    /**
     * r328：判断 URL 本身是不是媒体文件直链（路径末段是媒体扩展名）。
     * 注意 query 里带 .mp4 的不算（`xxx.html?url=1.mp4` 仍是网页），所以扩展名必须紧贴路径末尾。
     */
    private static boolean isDirectMediaUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String s = url.toLowerCase(Locale.ROOT);
        return s.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|mp4|m3u8|flv|mkv|webm|mov|ts|wmv|avi)(\\?.*)?$");
    }

    /**
     * r382（AGENTS.md 第十三节）：**音频探活 + 失效集自动跳过**。
     *
     * <p><b>实测真因（A 级，qmtsw.com 全量）</b>：全站音频可播率仅 **34%（25/72）**；
     * 失效地址统一返回 **S3 <code>NoSuchKey</code>（文件已被删除）**，换 UA / Referer /
     * 去 Range / 换喜马拉雅 UA 均无效（逐项实测），站方自己的播放器也报
     * <code>MEDIA_ERR_SRC_NOT_SUPPORTED(code=4)</code>。
     * → 这不是壳的取址问题，是**站方音频文件本身已删除**。
     *
     * <p>但是同一本书内「部分集失效」很常见（实测 20 本里 **8 本** 能靠跳集救回）：
     * 例 21170 前 5 集 = x ✓ x ✓ ✓，第1集失效但第2集可播。
     *
     * <p>做法：当前集探活失败时，按 <code>/play/{vid}-{vfrom}-{vpart}.html</code> 的
     * vpart 向前/向后各找 ≤3 集，命中可播就用它并打日志；全失败则原样返回（不假装成功）。
     *
     * <p><b>零回归措施</b>：
     * <ul>
     *   <li>只对形如 {@code /play/{数字}-{数字}-{数字}.html} 的页生效；其它形态立即返回原地址。</li>
     *   <li>最多额外探 3 次（head 请求、每次 3s 超时），不会拖死播放。</li>
     *   <li>当前集探活通过就直接返回，**不增加任何耗时**（只多一次 HEAD）。</li>
     * </ul>
     */
    private String skipDeadAudio(String page, String audioUrl) {
        try {
            if (TextUtils.isEmpty(audioUrl)) return audioUrl;
            // r382 零回归：**先判页面形态，不是分集页立即返回、一个请求也不发**。
            // 旧音频站（飞卢 /ListenNode/…、其它 /listen/xxx）不走这里，行为完全不变。
            Matcher m = Pattern.compile("/play/(\\d+)-(\\d+)-(\\d+)\\.html?").matcher(page);
            if (!m.find()) return audioUrl;

            // 当前集探活：通过就不折腾（HEAD，无 body）
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", UA);
            h.put("Referer", root + "/");
            if (probe(audioUrl, h, true, false)) return audioUrl;

            log("音频探活失败（S3 NoSuchKey/文件已删）→ 尝试跳集：" + audioUrl);
            String vid = m.group(1);
            String vfrom = m.group(2);
            int part = Integer.parseInt(m.group(3));
            String base = page.substring(0, m.start());

            int[] deltas = {1, -1, 2, -2, 3, -3};
            int tried = 0;
            for (int d : deltas) {
                if (tried >= 3) break;
                int np = part + d;
                if (np < 0) continue;
                String npage = base + "/play/" + vid + "-" + vfrom + "-" + np + ".html";
                String nhtml = get(npage);
                if (TextUtils.isEmpty(nhtml)) continue;
                String na = firstMatch(nhtml, "<audio[^>]+src=\"([^\"]+)\"");
                if (TextUtils.isEmpty(na)) {
                    na = firstMatch(nhtml, "(https?://[^\"'\\s\\\\]+\\.(?:mp3|m4a|aac|flac|wav|ogg))");
                }
                if (TextUtils.isEmpty(na)) continue;
                tried++;
                if (probe(abs(na), h, true, false)) {
                    log("音频第" + (part + 1) + "集失效 → 自动跳到第" + (np + 1) + "集（可播）");
                    return abs(na);
                }
            }
            log("音频第" + (part + 1) + "集失效，跳集也未找到可播（试了 " + tried + " 集）");
        } catch (Throwable e) {
            log("音频跳集异常 " + e.getClass().getSimpleName());
        }
        return audioUrl;
    }

    /**
     * 图片 AES 解密代理（照搬 51吃瓜 py 源 localProxy 的 type=img 分支）。
     * <p>吃瓜网系（Mirages 主题）封面图是 AES-128-CBC 加密字节（key=f5d965df75336270, iv=97b60394abc2fbe1），
     * 直接给图片加载器得到的是密文 → 封面空白。这里下载原图字节、解密、校验魔数（JPEG/PNG/GIF/WEBP），
     * 是明文才返回解密结果，否则回退原字节（兼容明文图）。
     */
    /** muse v31：本壳 BaseLoader.proxy 按 siteKey 分发到 spider.proxy()（Spider 基类无 proxyLocal），
     *  图片解密代理（proxyPic 生成的 proxy?do=img&type=img&siteKey=…）由此转进 proxyLocal。 */
    @Override
    public Object[] proxy(Map<String, String> params) {
        return proxyLocal(params);
    }

    public Object[] proxyLocal(Map<String, String> params) {
        try {
            String type = params == null ? "" : params.get("type");
            String url = params == null ? "" : params.get("url");
            if (!"img".equals(type) || TextUtils.isEmpty(url)) return null;
            if (url.startsWith("http")) {
                try {
                    url = java.net.URLDecoder.decode(url, "UTF-8");
                } catch (Throwable ignored) {
                }
            }
            if (!url.startsWith("http")) return null;
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .header("Referer", root + "/");
            try (Response res = OkHttp.noRedirect(15000).newCall(rb.build()).execute()) {
                if (res.body() == null) return null;
                byte[] raw = res.body().bytes();
                byte[] dec = aesImgDecrypt(raw);
                String mime = res.header("Content-Type");
                if (TextUtils.isEmpty(mime)) mime = "image/jpeg";
                return new Object[]{200, mime, new java.io.ByteArrayInputStream(dec)};
            }
        } catch (Throwable t) {
            log("图片代理异常 " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 把封面 URL 包装成本地代理 URL，让图片加载走 proxyLocal 解密（AES 图）或透传（明文图）。 */
    private String proxyPic(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return url;
        try {
            String base = com.muse.android.tv.server.Server.get().getAddress(true); // muse v31：AVBox LocalAddress → 本壳本地代理地址
            if (TextUtils.isEmpty(base)) return url;
            if (!base.endsWith("/")) base = base + "/";
            String enc = URLEncoder.encode(url, "UTF-8");
            String key = siteKey == null ? "" : siteKey;
            return base + "proxy?do=img&url=" + enc + "&type=img&siteKey=" + URLEncoder.encode(key, "UTF-8");
        } catch (Throwable t) {
            return url;
        }
    }

    /** 封面 URL 出口统一处理：识别 __aes__ 标记（Mirages 系懒加载图）→ 走代理解密。 */
    private String finishPic(String url) {
        if (TextUtils.isEmpty(url)) return url;
        if (url.startsWith("__aes__")) return proxyPic(url.substring(7));
        return url;
    }

    /** 吃瓜网系图片 AES-128-CBC 解密：解密后须是图片魔数才采用，否则回退原字节。 */
    private static byte[] aesImgDecrypt(byte[] raw) {
        if (raw == null || raw.length < 16) return raw;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec("f5d965df75336270".getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec("97b60394abc2fbe1".getBytes(StandardCharsets.UTF_8)));
            byte[] dec = c.doFinal(raw);
            boolean jpeg = dec.length > 2 && (dec[0] & 0xFF) == 0xFF && (dec[1] & 0xFF) == 0xD8 && (dec[2] & 0xFF) == 0xFF;
            boolean png = dec.length > 4 && (dec[0] & 0xFF) == 0x89 && dec[1] == 'P' && dec[2] == 'N' && dec[3] == 'G';
            boolean gif = dec.length > 3 && dec[0] == 'G' && dec[1] == 'I' && dec[2] == 'F' && dec[3] == '8';
            boolean webp = dec.length > 12 && dec[0] == 'R' && dec[1] == 'I' && dec[2] == 'F' && dec[3] == 'F'
                    && dec[8] == 'W' && dec[9] == 'E' && dec[10] == 'B' && dec[11] == 'P';
            return (jpeg || png || gif || webp) ? dec : raw;
        } catch (Throwable t) {
            return raw;
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        LogCategory previousStage = enterStage(LogCategory.PLAY);
        try {
            playHtmlForMaster = null;
            String out = playerContentStaged(flag, id, vipFlags);
            offerDeclaredMaster(out);
            return out;
        } finally {
            playHtmlForMaster = null;
            leaveStage(previousStage);
        }
    }

    private String playerContentStaged(String flag, String id, List<String> vipFlags) {
        try {
            detect();
            if ("nuxt-json-aes".equals(template)) return nuxtPlay(id);
            if ("jsdata".equals(template)) return jsdataPlayer(id);
            String page = abs(id);
            // r328：**id 本身就是媒体直链时直接返回** —— 绝不能再 get(page)。
            // 真机证据（22:35 日志）：id=http://tss.faloo.com/ListenNode/1/1522/15.mp3 已经是 mp3，
            // 旧代码先 get(page) 把整个 mp3 下载一遍，再走「候选探活」等 11 秒超时 → 转 WebView 嗅探，
            // 最后又绕回同一个地址。用户感知就是「要等 10 秒才有声音」。
            if (isDirectMediaUrl(page)) {
                JSONObject r = new JSONObject();
                r.put("parse", 0);
                r.put("url", page);
                JSONObject h = new JSONObject();
                h.put("User-Agent", UA);
                h.put("Referer", root + "/");
                r.put("header", h);
                log("直链直投 " + page);
                return r.toString();
            }
            // r359：YouTube 专项 —— watch 页 streamingData 直接给签名直链（沙箱实证 29 个 format，
            // itag18=360p mp4 音视频合一），零嗅探直投；拿不到再走下面的通用链路兜底。
            if (isYouTube()) {
                JSONObject yt = youtubePlay(page);
                if (yt != null) return yt.toString();
            }
            // r377：**先复用 detail 刚抓过的同一 URL** —— 沙箱量化实测：同一 URL 每 45 秒只允许 3 次 GET，
            // 第 4 次必 429。真机 22:14 日志的请求序列正是：detail 抓 → detail cap 重试 → player 再抓 → WebView，
            // 第 4 次（WebView）拿到 429 → 页面是错误页、没有验证组件 → 用户点验证必然失败。
            // 复用后 WebView 成为第 2 次请求，在配额内，能拿到真验证页。
            String html = lastPageHtmlWithin(page, 120000);
            if (!TextUtils.isEmpty(html)) {
                log("player 复用 detail 刚抓的页面 " + html.length() + " 字（少发一次 GET，保 429 额度）");
            } else {
                html = get(page);
            }
            // r540：旧缓存/直连详情 URL 也走同一条播放路由 —— detail 已经归一过就命中不到这里；
            // 若用户点的是历史记录里的 /video?id=N，这里把入口换成 <iframe> 指向的播放页再解析，
            // 避免「detail 无播放路由 → 本页嗅探」在详情页里空转。
            String routed = playPageOf(page, html);
            if (!TextUtils.isEmpty(routed) && !routed.equals(page)) {
                String ph = get(routed);
                if (!TextUtils.isEmpty(ph) && ph.length() > 1000) {
                    page = routed;
                    html = ph;
                    log("player 入口归一 → iframe 播放页 " + page);
                }
            }
            // r529：**JS 盾（ccguard / document.cookie 型）通用过盾** —— 不认站点，只认形态。
            // 盾页自带 document.cookie="k=v; ..."，把 k=v 抽出来：①回写重取一次播放页，
            // 纯 HTTP 能过就直接 r522 直解；②缓存下来交给 WebView（见下方 header），
            // 让它第一次请求就带盾 cookie —— 否则页面只有一次 reload 机会，时序不对就永远卡
            // 「验证尚未完成」(真机奇优影院实证)。这是「借用浏览器模板」能过盾的关键，零站点适配。
            if (!TextUtils.isEmpty(html) && html.contains("document.cookie")) {
                Matcher cm = Pattern.compile("document\\.cookie\\s*=\\s*[\"']([^\"']{3,300})[\"']").matcher(html);
                String shield = "";
                while (cm.find()) {
                    String one = stripCookieAttrs(cm.group(1));
                    if (TextUtils.isEmpty(one)) continue;
                    shield = TextUtils.isEmpty(shield) ? one : shield + "; " + one;
                }
                if (!TextUtils.isEmpty(shield)) {
                    JS_COOKIE.put(hostOf(page).toLowerCase(Locale.ROOT), shield);
                    Map<String, String> sch = new LinkedHashMap<>();
                    sch.put("User-Agent", UA);
                    sch.put("Cookie", shield);
                    sch.put("Referer", root + "/");
                    String fresh = OkHttp.string(page, sch);
                    if (!TextUtils.isEmpty(fresh) && fresh.length() > html.length() * 2 && fresh.length() > 2000) {
                        log("JS 盾 cookie 重取播放页成功 " + fresh.length() + " 字");
                        html = fresh;
                    } else {
                        log("JS 盾 cookie 已备（交 WebView 带盾访问）");
                    }
                }
            }
            // r287：小说页 / 图片页交给壳内置的阅读器与图片查看器 ——
            // VideoActivity:1094 认 novel://（NovelReaderActivity），:1098 认 pics://（ImageActivity）。
            // 小说 JSON 字段见 NovelReaderActivity:636：{"content":正文,"title":章节名}
            // 图片列表见 ImageActivity:100：pics:// 之后用 && 分隔。
            // push v45（Liu 2026-11-24：P站从全局搜索点播只有单分辨率）：播放页播放器配置里站方声明了各档 hls
            //（mediaDefinitions 数组：format=hls + videoUrl，P站系播放器结构）→ 直接用站方默认档起播，并把各档登记给
            // PushMultiRes，起播前合成本机多画质主清单（与推送 v43 同一套）。没有这段配置的页面照旧走下面原路。
            playHtmlForMaster = html;
            String declared = mediaDefsPlay(html, page);
            if (!TextUtils.isEmpty(declared)) return declared;
            if (isNovelPage(page)) {
                // r386（AGENTS.md 第十六节）：**漫画页先于小说页判定** —— 18seman 实证事故：
                // 这是韩漫站，章节页 URL 是 /index.php/chapter/26197，命中了 isNovelPage 的 /chapter，
                // 被当成小说送进 NovelReaderActivity，用户看到的是文字不是漫画图。
                // 真机日志铁证：`小说正文 304 字 → novel:// 交给内置阅读器`（实际该页 58~176 张漫画图）。
                // 判据（反例已验证）：无视频信号 且 大图>=8 且 中文<500 → 是漫画/图片页，不是小说。
                //   18seman 章节页：图 58~176、中文 12~21 → 图片页 ✓
                //   66story 真小说章节页：图 9、中文 2937 → 仍走小说 ✓（中文>500 不被误判）
                // r387：改用强视频信号（泛词会让漫画页自己也拦不住）
                List<String> comicImgs = hasStrongVideoSignal(html) ? null : collectBigImages(html);
                boolean comicLike = comicImgs != null && comicImgs.size() >= 8;
                if (comicLike) {
                    String probe = extractArticle(html);
                    int cn = 0;
                    for (int ci = 0; ci < probe.length(); ci++) {
                        if (probe.charAt(ci) >= 0x4e00 && probe.charAt(ci) <= 0x9fff) cn++;
                    }
                    if (cn >= 500) comicLike = false;   // 文字够多 → 确实是小说
                    else log("r386 漫画页优先：大图 " + comicImgs.size() + " 张、中文 " + cn + " 字 → 不当小说");
                }
                if (!comicLike) {
                // r486：正文抽取/目录页防御/分页拼接 抽出共用 novelUrlOf（浏览器模式虚拟源也用它回源）
                String nu = novelUrlOf(html, page);
                if (!TextUtils.isEmpty(nu)) {
                    JSONObject r = new JSONObject();
                    r.put("parse", 0);
                    r.put("url", nu);
                    return r.toString();
                }
                }
            }
            // r387（AGENTS.md 第十七节）：泛词拦改为**强视频信号**拦。
            // 旧写法 !hasVideoSignal 在 20 个真实页面里 19 个为 true——图片分流实际是死代码。
            // 47o.net（50 张大图）就死在这一行。
            // r387：另补 isGazeLike 否决 —— 页内 MSE 站（gaze/4kcz）首页有 156 张大图，
            // 但它们走 WebView 页内播放，不能进图片查看器。
            // r415：**按条目分流**（混合站）—— detail 已定类型时，player 不再用页面重新猜。
            // r416：**val=flag 才是可靠信号**。aisee9w11 实证（日志时间线）：
            //   20:14:17 detail id=/detail-219485/ → 20:14:26 条目类型 video（缓存记的是 219485）
            //   20:14:26 player flag=合集 id=/detail-219436/  ← 播放器要的是「合集里的子集」
            // 合集 = detail 返回 24 集、每集指向另一个 detail 页；子集 id 与本条缓存必然不同，
            // 于是 VOD_TYPE 永远不命中 → 又回落到页面级判断 → 视频被判图集。
            // 判据：detail 给了明确线路（flag 非「页面播放」）就说明这是视频条目，禁止再走图片分流。
            // r464：**AES 加密图集在 player 阶段同样要先解密** —— 否则这里只看到 1 张 logo，
            //   forcePic/图片分流全不触发，用户点开图集仍是「出不了内容」。
            html = aesPicDecrypt(html);
            String knownType = VOD_TYPE.get(id);
            // r418：删 flag 判据 —— flag=合集 的子条目在这个站是【图集】（detail-219436 实测
            // 169 张 img5 连续图），按 flag 强制视频会把图片查看器挡掉 → 跳网页。
            // 唯一可靠信号是 VOD_TYPE（detail 阶段按真选集/真大图定型）。
            boolean forceVideo = "video".equals(knownType);
            boolean forcePic = "pic".equals(knownType);
            if (forceVideo) log("条目已判定为视频 → 不走图片分流（" + id + "）");
            // r475：**音频判定前置**（一类站通用）—— qmtsw 真机实证：播放页 /play/11737-0-0.html
            // 含 14 张图（广告图+推荐图）≥3 且无 video/m3u8 → 先命中图片分支返回 pics:// 进图片查看器，
            // 音频直链 var now="https://…m4a" 根本没机会执行（用户报「无法听书」）。
            // 音频信号比图片信号更特异（图集站无音频直链；视频站有 m3u8 被 looksLikeAudioPage 否决），
            // 判据顺序改为：视频定型 → 音频 → 图片。另加 !forceVideo/!forcePic 否决保 detail 定型优先。
            if (!forceVideo && !forcePic && (isAudioPage(page) || looksLikeAudioPage(html))) {
                log("L1 内容类型：音频（" + (isAudioPage(page) ? "URL词表" : "内容信号") + "）");
                String a = firstMatch(html, "<audio[^>]+src=\"([^\"]+)\"");
                if (TextUtils.isEmpty(a)) a = firstMatch(html, "(https?://[^\"'\\s\\\\]+\\.(?:mp3|m4a|aac|flac|wav|ogg))");
                if (!TextUtils.isEmpty(a)) {
                    // r382（AGENTS.md 第十三节）：**音频探活 + 失效集自动跳过**。
                    String fixed = skipDeadAudio(page, abs(a));
                    JSONObject r = new JSONObject();
                    r.put("parse", 0);
                    r.put("url", fixed);
                    log("音频 " + (fixed.length() > 60 ? fixed.substring(0, 60) + "…" : fixed) + " → 交给播放器");
                    return r.toString();
                }
            }
            if (forcePic || (!forceVideo && !hasStrongVideoSignal(html) && !isGazeLike(page, html)
                    && (isPicPage(page) || looksLikePicturePage(html)))) {
                List<String> imgs = collectBigImages(html);
                // r390（AGENTS.md 第二十一节）：**图集详情分页展开** —— 47o.net 实证：
                // 详情页第 1 页固定只有 1.jpg…42.jpg，第 2 页才是 43…70；HEAD 实测 43/44/60.jpg 均 200、80.jpg 404，
                // 站点真有 70 张。不展开就是用户看到的「所有内容都只有 42 张图片」。
                imgs = expandGalleryPages(page, html, imgs);
                if (!imgs.isEmpty()) {
                    JSONObject r = new JSONObject();
                    r.put("parse", 0);
                    r.put("url", "pics://" + TextUtils.join("&&", imgs));
                    // r299：图片查看器要用 header 去加载 —— 图片站普遍校验 Referer，
                    // 不带的话 ImageActivity 里全是空白（真机反馈「图片无法播放」）。
                    JSONObject h = new JSONObject();
                    h.put("User-Agent", UA);
                    h.put("Referer", page);
                    r.put("header", h);
                    log("图片 " + imgs.size() + " 张 → pics:// 交给内置图片查看器");
                    return r.toString();
                }
            }
            // r268：Cap 验证型站点 —— 先纯 HTTP 解验证拿 cookie，再带 cookie 取真实播放页。
            // 沙箱已验证：challenge(rsw t=75000) → y=x^(2^t) mod N → redeem 体必须是
            // {"token":…,"solutions":[{"y":"<hex>"}]}（传裸数组会 403），成功后下发 __Host-xxx cookie。
            // r281：只在页面**确实含 Cap 组件**时才求解 —— 之前用 isErrorPage 兜底，
            // 把 4kcz 这类普通站也拖进来白跑一次 Cap 求解（日志：cap 求解异常 InterruptedException），
            // 每次播放多花 0.5~4 秒，壳还反复重试，用户侧就是「加载十几秒」。
            // r292：Cap cookie 按站点复用 —— detail 阶段通常已经解过，这里直接拿来用（省 1.5~2.5 秒）；
            // 但命中缓存也必须带 cookie 重取一次，否则拿到的还是「访问验证」壳。
            String capHost = schemeHost(page);
            String capCookie = CAP_COOKIE.getOrDefault(capHost, "");
            if (html != null && html.contains("cap-widget")) {
                // r377：协议不支持的站（hashwx）绝不重试 —— 每 45s 只给 3 次 GET，
                // 这多出来的一次正是「WebView 拿到 429 → 页面上没验证组件 → 用户点验证失败」的真因。
                if (CAP_UNSUPPORTED.contains(capHost)) {
                    log("player cap 协议不支持纯 HTTP 求解 → 跳过重试，直接交 WebView 原生验证");
                } else {
                if (TextUtils.isEmpty(capCookie)) {
                    capCookie = solveCapCookie(capHost);
                    if (!TextUtils.isEmpty(capCookie)) CAP_COOKIE.put(capHost, capCookie);
                } else {
                    log("cap cookie 复用缓存（跳过求解）");
                }
                if (!TextUtils.isEmpty(capCookie)) {
                    Map<String, String> ch = new LinkedHashMap<>();
                    ch.put("User-Agent", UA);
                    ch.put("Cookie", capCookie);
                    String html2 = OkHttp.string(page, ch);
                    boolean bad2 = TextUtils.isEmpty(html2) || isErrorPage(html2) || html2.contains("cap-widget");
                    if (!bad2 && html2.length() > html.length()) {
                        log("cap 已通过，取到真实播放页 " + html2.length() + " 字");
                        html = html2;
                    } else {
                        // r377：重试仍为验证壳 → 标记协议不支持，后续不再重试（保 429 额度）
                        CAP_UNSUPPORTED.add(capHost);
                        CAP_COOKIE.remove(capHost);
                        log("player cap 重试仍为验证壳 → 标记该站为 WebView 原生验证");
                    }
                }
                }
            }
            // r342：melon 动态播放器（both 站黑料等）纯 HTTP 直解 —— 详情页 .ql-video 里是
            // packer 混淆的 document.write，解第一层拿 /index/melon_detail_play.js?u=<密文>&t=<窗口>，
            // 再解第二层拿 data-url m3u8。沙箱已验证：解码结果与浏览器 DOM 逐字节一致，
            // 该 m3u8 纯 HTTP 可拉（200，AES-128 标准 HLS，ExoPlayer 原生支持）。
            // r340 的 WebView 注入方向是错的：melon js 是异步 document.write，加载后会清空整页。
            String melon = melonPlay(page, html);
            if (!TextUtils.isEmpty(melon)) {
                Map<String, String> mh = new LinkedHashMap<>();
                mh.put("User-Agent", UA);
                Map<String, String> ok = probeHeaders(melon, mh, "");
                log("melon 直解 m3u8 probe=" + (ok != null ? "OK" : "FAIL") + " " + melon);
                if (ok != null) {
                    JSONObject r = new JSONObject();
                    r.put("parse", 0);
                    r.put("url", melon);
                    JSONObject uh = new JSONObject();
                    for (Map.Entry<String, String> he : ok.entrySet()) uh.put(he.getKey(), he.getValue());
                    r.put("header", uh);
                    return r.toString();
                }
            }
            // r353：RC4 密文字典站（nnyy.la nunuyy 模板）纯 HTTP 直解 —— 播放地址在
            // urlDictionary[sid][nid] 的 RC4 密文里（key 在页内 rc4Decrypt 函数），
            // HTML 里没有任何明文 m3u8/mp4 → 旧逻辑候选 0 → 转 WebView 嗅探；而 xgplayer
            // 从 unpkg CDN 加载，真机嗅探经常连播放器都起不来（15:43 日志：候选=0 采用=嗅探）。
            // 沙箱已验证：解密出的 m3u8 纯 HTTP 可拉（各线路 4/4）。
            String rc4u = rc4Play(page, html);
            if (!TextUtils.isEmpty(rc4u)) {
                Map<String, String> rh4 = new LinkedHashMap<>();
                rh4.put("User-Agent", UA);
                Map<String, String> ok4 = probeHeaders(rc4u, rh4, "");
                log("rc4 直解 m3u8 probe=" + (ok4 != null ? "OK" : "FAIL") + " " + rc4u);
                if (ok4 != null) {
                    JSONObject r = new JSONObject();
                    r.put("parse", 0);
                    r.put("url", rc4u);
                    JSONObject uh4 = new JSONObject();
                    for (Map.Entry<String, String> he : ok4.entrySet()) uh4.put(he.getKey(), he.getValue());
                    r.put("header", uh4);
                    return r.toString();
                }
            }
            // r356：dd 模板 player_aaaa 密文站（ddys.la）—— 播放地址是 player_aaaa.url 密文，
            // 站内 ddcloud 解析器直解出 m3u8（沙箱 verify 32/32：playerconfig 查表 → ddcloud → m3u8 可拉）。
            // 旧逻辑候选 0 → 转 WebView 嗅探；该站页面 JS 全靠自建 ddcloud，嗅探拿不到地址。
            // r413：`url` 不是 URL（如 MacPlayer 的 "11602|1" = ID|集）时，说明该 from 源要站点专属解析器，
            // 硬探活只会白等（aisee9w11 的 huangguo ps=0/parse 为空，壳永远拿不到直链）。
            // 这种情况直接留给 WebView 嗅探 / 网页播放，并留痕说明原因。
            String pv = firstMatch(html, "var (?:player_aaaa|player_data|player_config)\\s*=\\s*\\{[^}]*\"url\"\\s*:\\s*\"([^\"]{1,200})\"");
            if (pv != null && !pv.contains("://") && pv.contains("|")) {
                log("播放源是 ID 形式（" + pv + "）→ 需站点专属解析，直接交 WebView/网页播放");
            }
            // r414：huangguo 源直解（aisee9w11 实证，子智能体实测 4 个 vid 全部 code:1）
            String hgu = huangguoPlay(page, html);
            if (!TextUtils.isEmpty(hgu)) {
                JSONObject hr = new JSONObject();
                hr.put("parse", 0);
                hr.put("url", hgu);
                JSONObject hh = new JSONObject();
                hh.put("User-Agent", UA);
                hh.put("Referer", page);
                hr.put("header", hh);
                return hr.toString();
            }
            String ddu = ddPlay(page, html);
            if (!TextUtils.isEmpty(ddu)) {
                Map<String, String> dh4 = new LinkedHashMap<>();
                dh4.put("User-Agent", UA);
                Map<String, String> dok4 = probeHeaders(ddu, dh4, "");
                log("dd 直解 m3u8 probe=" + (dok4 != null ? "OK" : "FAIL") + " " + ddu);
                if (dok4 != null) {
                    JSONObject dr = new JSONObject();
                    dr.put("parse", 0);
                    dr.put("url", ddu);
                    JSONObject duh4 = new JSONObject();
                    for (Map.Entry<String, String> he : dok4.entrySet()) duh4.put(he.getKey(), he.getValue());
                    dr.put("header", duh4);
                    return dr.toString();
                }
            }
            // r521：**手势门 + 接口返 base64**（hsck 系）—— 播放页没有 m3u8，页面上是一个「立即播放」
            // 按钮：倒计时解锁后 POST /static/count.php（id/sid/nid/tk + 手势坐标/时间差）返回
            // {"ok":true,"u":"aHR0cHM6…"}（base64 的 m3u8）。纯 HTTP 直接照抄这串参数即可，
            // 沙箱实证 200 且解出的 m3u8 可拉（t27.cdn2020.com）。
            String gp = gesturePlay(page, html);
            if (!TextUtils.isEmpty(gp)) return mediaResult(gp, page, "手势接口直解");
            // r522：**三方 artplayer 解析器**（qiyoudy2 系）—— 播放页只有一个 iframe：
            // meizi.yongfan99.com/content.php?vid=… → 302 → /player/index.php?t=lekanzyw&url=…
            // 该页 JS 里有 const Url/Sign/From，再 GET ./api.php?url=&sign=&t= 换出 m3u8。
            String ap = iframeApiPlay(page, html);
            if (!TextUtils.isEmpty(ap)) return mediaResult(ap, page, "三方播放器接口直解");
            // r246：候选不唯一 —— 明文直链 / iframe query 参数 / iframe 页面各算一个，
            // 逐个探活，能通的才交出去；都不通就返回 parse=1 让壳内嗅探接手，并留痕。
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            String frameRef = "";
            for (String d : findPlayUrls(html)) {
                if (!isMediaUrl(d) && TextUtils.isEmpty(frameRef)) frameRef = schemeHost(d);
                candidates.add(unwrapPlayerUrl(d));
            }
            String framed = findPlayInFrame(html);
            if (!TextUtils.isEmpty(framed)) {
                if (!isMediaUrl(framed) && TextUtils.isEmpty(frameRef)) frameRef = schemeHost(framed);
                candidates.add(unwrapPlayerUrl(framed));
            }
            // r346：eval packer 直解候选并入（missav09：surrit m3u8 + 页面自身 jmpres 域改写版）
            candidates.addAll(packerCandidates(html));
            // r251/r252：媒体直链优先探活；HTML 播放器页只在拿不到真直链时当「嗅探入口」。
            List<String> ordered = new ArrayList<>();
            for (String c : candidates) if (isMediaUrl(c)) ordered.add(c);
            for (String c : candidates) if (!isMediaUrl(c)) ordered.add(c);
            // r346：磁力链不是播放地址 —— 真机 missav09 keepshare.org/p5uj7smo/magnet:?xt=urn:btih:…
            // 被 probe=OK 采用（keepshare 页面本身 200），播放器拿到的是下载页不是流。
            if (ordered.removeIf(c -> c != null && (c.contains("magnet:") || c.contains("magnet%3A") || c.contains("magnet%3a"))))
                log("剔除磁力链候选（keepshare 下载页）");
            // r525：**封面图冒充播放地址** —— hsck 系封面是
            // https://23.225.11.173:8443/video/m3u8/2026/11/19/8c7612f8/vod.jpg：
            // 路径里带 `.m3u8` 字样，P_M3U8 会整条抽出来；探活返回 200（图片也是 200）→ 被当成播放地址
            // 交给播放器 → 黑屏/「无法播放」。这里直接丢弃「路径实际以图片后缀结尾」的候选。
            if (ordered.removeIf(WebSpider::looksLikeImageUrl)) log("剔除封面图候选（路径带 m3u8 但实为图片）");
            // r252：探活基准 header（Map）；result 输出仍用后面的 JSONObject header
            Map<String, String> baseHeader = new LinkedHashMap<>();
            baseHeader.put("User-Agent", UA);
            baseHeader.put("Referer", root + "/");
            String play = "";
            Map<String, String> useHeader = null;
            for (String cand : ordered) {
                Map<String, String> ok = probeHeaders(cand, baseHeader, frameRef);
                log("播放候选 probe=" + (ok != null ? "OK" : "FAIL") + " " + cand);
                if (ok != null) {
                    play = cand;
                    useHeader = ok;
                    break;
                }
            }
            // r252：探不通的直链不再硬塞给播放器（那只会黑屏），改为交给 WebView 打开播放页嗅探，
            // 页面自己带 cookie/referer 请求，成功率更高。
            // r411：**探活失败 ≠ 不可播**。lu9936 实证：候选 m3u8 沙箱 200、真机 4s 超时，
            // 说明只是网络慢/抖动。候选唯一时直接交给播放器直连（播放器有更长的缓冲与重连），
            // 仍失败再让用户重试；比「提前判死转嗅探、嗅探也够不到同一 CDN」好。
            if (TextUtils.isEmpty(play) && ordered.size() == 1) {
                play = ordered.get(0);
                useHeader = baseHeader;
                log("探活失败但唯一候选 → 交播放器直连试 " + play);
            }
            // r417：**详情页 → 播放页两级结构**（aisee9w11 合集实证，A 级日志）：
            //   用户点合集条目 → player id=/detail-220463/（详情页）→ 候选 0 → 跳网页
            //   而 player_data / huangguo.php 只在 /play/N-1-1/ 播放页里。
            // 候选为空时先跟一跳站内播放链接，再按同一套解析（huangguo / dd / 明文 m3u8）。
            // 这类「详情页当播放地址」的站很常见，属于通用修法。
            if (TextUtils.isEmpty(play)) {
                String plink = firstMatch(html, "href=\"(/play/\\d+-\\d+-\\d+/?)\"");
                if (TextUtils.isEmpty(plink)) plink = firstMatch(html, "href=\"([^\"]*/play/[^\"]+/)\"");
                if (!TextUtils.isEmpty(plink)) {
                    String purl = abs(plink);
                    String ph = get(purl);
                    if (!TextUtils.isEmpty(ph) && ph.length() > 2000) {
                        log("详情页无播放地址 → 跟一跳播放页 " + purl);
                        String hgu2 = huangguoPlay(purl, ph);
                        if (!TextUtils.isEmpty(hgu2)) {
                            JSONObject r2 = new JSONObject();
                            r2.put("parse", 0);
                            r2.put("url", hgu2);
                            JSONObject h2 = new JSONObject();
                            h2.put("User-Agent", UA);
                            h2.put("Referer", purl);
                            r2.put("header", h2);
                            log("跟跳后 huangguo 直解成功 " + hgu2);
                            return r2.toString();
                        }
                        String dd2 = ddPlay(purl, ph);
                        if (!TextUtils.isEmpty(dd2)) {
                            JSONObject r3 = new JSONObject();
                            r3.put("parse", 0);
                            r3.put("url", dd2);
                            JSONObject h3 = new JSONObject();
                            h3.put("User-Agent", UA);
                            h3.put("Referer", purl);
                            r3.put("header", h3);
                            log("跟跳后 dd 直解成功 " + dd2);
                            return r3.toString();
                        }
                        String m2 = firstMatch(ph, "(https?://[^\"'\\s]{20,300}?\\.m3u8[^\"'\\s]*)");
                        if (!TextUtils.isEmpty(m2)) {
                            play = m2;
                            useHeader = probeHeaders(m2, baseHeader, purl);
                            log("跟跳后取到明文 m3u8 " + m2);
                        }
                    }
                }
            }
            if (TextUtils.isEmpty(play)) log("全部候选探活失败 → 转 WebView 嗅探播放页");
            log("player page=" + page + " 候选=" + candidates.size() + " 采用=" + (TextUtils.isEmpty(play) ? "嗅探" : play));
            JSONObject result = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("User-Agent", UA);
            header.put("Referer", root + "/");
            // r288：把 Cap 求解到的 cookie 一并交给 WebView —— 之前 cookie 只喂给 OkHttp，
            // WebView 打开时不带，于是页面仍是「访问验证」壳。真机 13:11 日志铁证：
            // 「页面脚本 noHex cnt=0 cap=1」「页面脚本 DimGive 未定义」→ 拿不到密文、调不到解密桥。
            // r288/r268：把 Cap 求解到的 cookie 一并交给 WebView —— 之前 cookie 只喂给 OkHttp，
            // WebView 打开时不带，页面仍是「访问验证」壳（真机 13:11 日志铁证 noHex cnt=0）。
            // r359：密码盾 cookie 也要交给 WebView —— ddys.la player 页在纯 HTTP 层已过盾
            //（pageLogin=ok，detail 阶段日志「密码盾已过」），但 WebView 的 CookieManager 不知道，
            // 打开 player 页又是「请输入密码」密码页 → 用户被迫跳网页输密码且无法调输入法
            //（真机 03:12 日志：player 候选=0 采用=嗅探 → WebView 密码页）。
            String shCk = SHIELD_COOKIE.get(hostOf(page).toLowerCase(Locale.ROOT));
            String ck = capCookie == null ? "" : capCookie;
            if (TextUtils.isEmpty(ck) && !TextUtils.isEmpty(shCk)) ck = shCk;
            else if (!TextUtils.isEmpty(ck) && !TextUtils.isEmpty(shCk) && !ck.contains(shCk)) ck = ck + "; " + shCk;
            // r529：JS 写 cookie 的盾（ccguard 等）也交给 WebView —— 之前只喂给 OkHttp，
            // WebView 打开播放页仍是盾页，页面仅一次 reload 机会，时序不对就卡死「验证尚未完成」。
            String jsCk = JS_COOKIE.get(hostOf(page).toLowerCase(Locale.ROOT));
            if (!TextUtils.isEmpty(jsCk) && !ck.contains(jsCk)) ck = TextUtils.isEmpty(ck) ? jsCk : ck + "; " + jsCk;
            if (!TextUtils.isEmpty(ck)) header.put("Cookie", ck);
            boolean sniff = TextUtils.isEmpty(play) || !isMediaUrl(play);
            if (sniff) {
                String target = TextUtils.isEmpty(play) ? page : play;
                // r528：兜底 —— 若入口仍是详情页（盾站 detail 抽不到剧集时的旧缓存/旧配置），
                // 换成播放页再交给 WebView，否则浏览器里没有播放器，嗅探必然空手。
                String pp = playPageOf(target, html);
                if (!TextUtils.isEmpty(pp)) {
                    target = pp;
                    log("嗅探入口归一 → 播放页 " + target);
                }
                result.put("parse", 1);
                result.put("url", target);
                result.put("header", header);
                // r249/r251：播放地址由运行时 JS 生成，或命中的是「外层播放器页」时，
                // WebView 打开后还得触发播放，否则不请求 m3u8、嗅探只抓到空壳。
                boolean cap = html != null && (html.contains("cap-widget") || html.contains("访问验证"));
                // r266：parse=1 一律注入点播脚本 —— 页面不点线路/集数就不会请求 m3u8，嗅探必然抓不到
                // r275：三段脚本必须用分号隔开 —— 否则 "})()(function(){" 会被 JS 解析成
                // 「调用前一个 IIFE 的返回值」，导致后面的 PAGE_PLAY_API 整段失效
                String click = (cap ? CAP_CLICK_SCRIPT + ";" : "") + IK_HOOK + ";" + PAGE_PLAY_API;
                // r333（方案 1）：非 gaze 类站追加通用嗅探 hook —— 让页面自己把播放地址送出来，
                // 拿到就回传 sniff://，由壳播放器接管，而不是把整页网页怼给用户。
                // gaze.red 走原样：它的三段（CAP_CLICK_SCRIPT/IK_HOOK/PAGE_PLAY_API）逐字节不变，
                // 判定用 isGazeLike（IwasKing/GazeVideo10/data-source-group 技术指纹）。
                // r378：gaze 类站**改为追加被动嗅探 hook**（不自动点击）。
                // 真机 23:05/23:10 日志：`noHex cnt=4 cap=0` 反复出现但既无 `cand*` 也无「回传地址」；
                // 真浏览器验证后 DOM：[data-src] 只剩 4 个 /user/verifys 图片（长度 13~15 且带 /），
                // 剧集已改成 `<button data-id data-mid data-path>` —— 旧 hex 密文链彻底不存在。
                // r378：gaze 类站【页内播放模式】—— 不再注入 PAGE_PLAY_API / IK_HOOK。
                // A 级证据：全新标签页零注入时页面自己就能开播（readyState=4、blob src、错误层 display:none）；
                // 而真机 23:05/23:10 日志里 isolate 隐藏 3462/3404 个元素后又连点线路 1/10…10/10，页面始终不请求媒体。
                // 站点已无明文地址可回传（MEV4 加密容器 + 分片被混淆成 blob 名），因此改为：
                //   GAZE_NET_HOOK（被动嗅探，万一以后站点改回明文可直接命中）
                //   GAZE_PAGE_PLAY（只改样式全屏 + readyState 轮询，开播后回传 pageplay 让壳取消超时、保留网页）
                if (isGazeLike(page, html)) click = (cap ? CAP_CLICK_SCRIPT + ";" : "") + GAZE_NET_HOOK + ";" + GAZE_PAGE_PLAY;
                else click = click + GENERIC_PLAY_HOOK;
                if (!TextUtils.isEmpty(click)) {
                    result.put("click", click);
                    log("player 嗅探入口 " + target + "（已注入自动播放）");
                }
            } else {
                result.put("parse", 0);
                result.put("playUrl", "");
                result.put("url", play);
                if (useHeader == null) {
                    result.put("header", header);
                } else {
                    JSONObject uh = new JSONObject();
                    for (Map.Entry<String, String> he : useHeader.entrySet()) uh.put(he.getKey(), he.getValue());
                    result.put("header", uh);
                }
            }
            return result.toString();
        } catch (Throwable e) {
            log("player 异常 " + id + " : " + e);
            return "{\"parse\":1,\"url\":\"\"}";
        }
    }

    /**
     * r249：给「播放器由运行时 JS 创建」的详情页返回一段点击脚本。
     *
     * <p>理由：A 站这类页面的播放地址是 eval(packer) 运行时拼出来的，播放器（xgplayer/hls.js）
     * 不点就不去请求 m3u8，壳的嗅探只能抓到空壳。只在页面确实含动态播放器时注入，其它站不动。
     */
    private static final String AUTO_PLAY_SCRIPT =
            "(function(){var n=0,s=['video','.xgplayer-play','.dplayer-play-icon','.play-btn','.player-container','.play'];"
                    + "function t(){if(n++>6)return;for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){try{e.click()}catch(x){}}}}"
                    + "setTimeout(t,800);setTimeout(t,2500);setTimeout(t,5000);})();";

    /**
     * r257：gaze.red 播放页用 Cap(cap.js) 人机验证，且校验 isTrusted（合成事件过不了）。
     * 这里尽量把 cap-widget 与播放器点一遍：用户手动点一次「完成验证」后即可嗅探到 m3u8。
     */
    private static final String CAP_CLICK_SCRIPT =
            "(function(){var n=0;function t(){var w=document.querySelector('cap-widget');"
                    + "if(w){try{w.click()}catch(e){}}"
                    // r293：绝不能自动调 video.play()！那会把播放器推进 MSE 模式，IwasKing 随之返回
                    // blob:https://… 的对象 URL（真机 14:52 日志 ik cb blob:…），ExoPlayer 播不了。
                    // 只点验证组件，取地址交给 PAGE_PLAY_API。
                    + "if(n++<10)setTimeout(t,1500);}setTimeout(t,800);})();";

    /**
     * r266：parse=1 一律注入这段脚本 —— 站点播放器只有点过线路/集数后才去请求 m3u8。
     * 旧逻辑在「请稍候」页上算出空脚本，什么都不点，于是嗅探永远抓不到地址，用户只能停在网页里。
     */
    private static final String AUTO_CLICK_ALL =
            "(function(){var n=0;"
                    + "function tap(e){try{e.click()}catch(x){}}"
                    + "function run(){var i,els;"
                    + "els=document.querySelectorAll('.play-btn,.btn-play,.playlist a,[class*=episode],[class*=number]');"
                    + "for(i=0;i<els.length&&i<3;i++)tap(els[i]);"
                    + "var all=document.querySelectorAll('a,button,li,span,div');"
                    + "for(i=0;i<all.length&&i<600;i++){var t=(all[i].textContent||'').trim();"
                    + "if(t==='第1集'||t==='第01集'||t==='第1話'||t==='播放'||t==='立即播放'){tap(all[i]);}}"
                    + "var v=document.querySelector('video');if(v){try{v.play()}catch(x){}}"
                    + "if(n++<8)setTimeout(run,1500);}"
                    + "setTimeout(run,1200);})()";

    /**
     * r267：页面内主动回传脚本 —— 只靠网络嗅探抓不到（播放器可能走 iframe/blob/JS 拼地址），
     * 这里点完线路/集数后，把 video.src、iframe 的 url 参数、页面里的 m3u8 用 sniff:// 发回原生侧。
     */
    private static final String PAGE_SNIFF =
            "(function(){var n=0,sent=false;"
                    + "function tap(e){try{e.click()}catch(x){}}"
                    + "function send(u){if(sent)return;sent=true;try{new Image().src='sniff://'+encodeURIComponent(u)}catch(e){}}"
                    + "function rep(m){try{new Image().src='sniff://'+encodeURIComponent('log:'+m)}catch(e){}}"
                    + "function find(){var v=document.querySelector('video');if(v&&v.src&&v.src.indexOf('m3u8')>=0)return v.src;"
                    + "var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){var s=fs[i].src||'';var k=s.indexOf('url=');"
                    + "if(k>0){try{var d=decodeURIComponent(s.substring(k+4).split('&')[0]);if(d.indexOf('m3u8')>=0||d.indexOf('.mp4')>=0)return d}catch(e){}}"
                    + "if(s.indexOf('m3u8')>=0)return s}"
                    + "var h=document.documentElement.innerHTML,mm='',k=h.indexOf('.m3u8');"
                    + "while(k>0){var st=h.lastIndexOf('http',k);if(st>=0){var s=h.substring(st,k+5);"
                    + "if(s.indexOf(' ')<0&&s.indexOf(String.fromCharCode(34))<0&&s.indexOf(String.fromCharCode(39))<0"
                    + "&&s.indexOf(')')<0&&s.indexOf('(')<0&&s.indexOf('<')<0&&s.length<400){mm=s;break}}"
                    + "k=h.indexOf('.m3u8',k+5);}"
                    + "if(mm)return mm;"
                    + "return ''}"
                    + "function run(){var i,els=document.querySelectorAll('.play-btn,.btn-play,.playlist a,[class*=episode],[class*=number]');"
                    + "for(i=0;i<els.length&&i<3;i++)tap(els[i]);"
                    + "var all=document.querySelectorAll('a,button,li,span,div');"
                    + "for(i=0;i<all.length&&i<600;i++){var t=(all[i].textContent||'').trim();"
                    + "if(t==='第1集'||t==='第01集'||t==='第1話'||t==='播放'||t==='立即播放')tap(all[i]);}"
                    + "var v=document.querySelector('video');if(v){try{v.play()}catch(x){}}"
                    + "var u=find();if(u)send(u);else if(n%3===0)rep('round'+n+' video='+document.querySelectorAll('video').length+' iframe='+document.querySelectorAll('iframe').length);"
                    + "if(n++<10&&!sent)setTimeout(run,1500);}"
                    + "setTimeout(run,1200);})()";

    /**
     * r270：包装页面自己的解密桥 window.IwasKing 与播放器 src()。
     * 该站点击集数后走 IwasKing(data, cb) → 内部 DimGive('8069',mid,key) → 解密 m3u8 → cb({src,type})；
     * 一旦图形验证没解锁，DimGive 返回字符串 "null"，回调永不触发（页面显示「播放源解析失败」）。
     * 这里在出口处挂钩：一旦页面自己解出地址，立刻用 sniff:// 回传给原生交给 ExoPlayer。
     */
    private static final String IK_HOOK =
            "(function(){var sent=false;"
                    + "function send(u){if(!u||sent)return;if(u.indexOf('m3u8')<0&&u.indexOf('.mp4')<0)return;sent=true;"
                    + "try{new Image().src='sniff://'+encodeURIComponent(u)}catch(e){}}"
                    + "function rep(m){try{new Image().src='sniff://'+encodeURIComponent('log:'+m)}catch(e){}}"
                    + "function wrap(){var f=window.IwasKing;if(typeof f==='function'&&!f.__w){try{"
                    + "var w=function(t,cb){return f.call(this,t,function(o){try{var ks=o?Object.keys(o).join(','):'null';"
                    + "rep('ik cb keys='+ks+' src='+((o&&o.src)?String(o.src).slice(0,70):'empty'))}catch(e){};"
                    // r295：blob 是页面用 MSE 造的，网络层抓不到 m3u8。这里直接读 blob 内容，
                    // 前 150 字就能判断它是 m3u8 文本（#EXTM3U）还是 fMP4 分片。
                    + "try{var gv2=window.GazeVideo10;var pp=gv2&&gv2.player;var hh=pp&&pp.hls;"
                    + "if(hh&&!window.__hls){window.__hls=1;"
                    + "var ks=[];try{ks=Object.keys(hh).slice(0,14)}catch(e){}"
                    + "rep('hls实例 keys='+ks.join(','));"
                    + "try{var cfg=hh.config;rep('hls config keys='+(cfg?Object.keys(cfg).slice(0,12).join(','):'无'))}catch(e){}"
                    + "try{var ld=cfg&&cfg.loader;rep('hls loader='+(ld?String(ld).slice(0,120):'无'))}catch(e){}}}catch(e){};"
                    // r297：分片标识形如 !_0D55nU~2~R~R…，需要找到页面把标识翻译成真实 URL 的函数。
                    // 这里探测全局函数名里含 decode/unm/parse/url 的可疑入口。
                    + "try{if(!window.__fn2){window.__fn2=1;"
                    + "var names=[];for(var k in window){try{if(typeof window[k]==='function'&&/dec|unm|deobf|parse|sign|token|url|mix/i.test(k)&&k.length<40)names.push(k)}catch(e){}}"
                    + "if(names.length)rep('可疑函数 '+names.slice(0,18).join(','))}}catch(e){};"
                    + "try{if(o&&o.src&&String(o.src).indexOf('blob:')===0){"
                    + "fetch(o.src).then(function(r){return r.text()}).then(function(t){"
                    + "var txt=String(t);"
                    + "rep('blob内容 '+txt.slice(0,150).replace(/\\n/g,'|'));"
                    // r296：m3u8 里的分片行才是关键 —— 判型「绝对 URL」还是「相对路径」决定能不能交给 ExoPlayer
                    + "try{var ls=txt.split('\\n');var seg='';var key='';"
                    + "for(var i=0;i<ls.length;i++){var s=ls[i].trim();"
                    + "if(!s)continue;"
                    + "if(s.indexOf('#EXT-X-KEY')===0)key=s.slice(0,120);"
                    + "if(s.charAt(0)!=='#'){seg=s;break}}"
                    + "rep('m3u8 分片 '+(seg||'无')+(key?(' KEY '+key):''))}catch(e){}"
                    + "}).catch(function(e){rep('blob读取失败')})}}catch(e){};"
                    + "try{send(o&&o.src)}catch(e){};"
                    + "if(typeof cb==='function')cb(o)})};w.__w=1;window.IwasKing=w;}catch(e){}}"
                    + "var gv=window.GazeVideo10;var p=gv&&gv.player;"
                    + "if(p&&!p.__w){try{var os=p.src;p.src=function(s){try{send(typeof s==='string'?s:(s&&s.src))}catch(e){};"
                    + "return os.apply(this,arguments)};p.__w=1;}catch(e){}}}"
                    + "var n=0,iv=setInterval(function(){wrap();"
                    + "if(n===3){try{var dg=window.DimGive;if(typeof dg==='function'){"
                    + "var el=document.querySelector('[data-player-mid]');var mid=el?el.dataset.playerMid:'';"
                    + "var k=decodeURIComponent(window.killing_formalism||'');"
                    + "rep('DimGive='+dg('8069',mid,k)+' mid='+(mid?mid.slice(0,8):''))}else{rep('DimGive 未定义')}}catch(e){rep('DimGive 异常')}}"
                    + "if(++n>60)clearInterval(iv)},800);})()";

    /**
     * r272：不再乱点 DOM（r271 的全量点击把页面点回了首页，播放器一丢就必然失败）。
     * 改成直接调用页面自己的播放接口：找带 data-src 密文的集数元素，把它的 dataset 交给 window.IwasKing，
     * 解密回调里的 src 就是 m3u8 —— 日志已证明 DimGive 返回的是有效函数名（5d9369d71790）而不是 null。
     */
    /**
     * r333（方案 1）：**通用嗅探 hook** —— 只给非 gaze 类站注入。
     * 做三件事：①hook XHR/fetch/video.src，捕获所有可能成为播放地址的 URL；
     * ②自动点播放按钮/视频区，触发页面去请求真实地址（不点就不请求）；
     * ③一旦命中 m3u8/mp4，立刻 sniff:// 回传，由壳播放器接管，而不是把整页网页丢给用户。
     * 整段以 `;` 开头，避免与前面的 IIFE 粘连（r275 教训：`})()(function(){` 会让后段整段失效）。
     */
    private static final String GENERIC_PLAY_HOOK =
            ";(function(){try{"
                    + "if(window.__gph)return;window.__gph=1;"
                    + "function rep(m){try{new Image().src='sniff://'+encodeURIComponent('log:'+m)}catch(e){}}"
                    + "var sent=false;"
                    + "function send(u){try{if(sent||!u)return;u=String(u);"
                    + "if(u.indexOf('m3u8')<0&&u.indexOf('.mp4')<0)return;"
                    + "sent=true;rep('通用嗅探命中 '+u.slice(0,140));"
                    + "new Image().src='sniff://'+encodeURIComponent(u)}catch(e){}}"
                    // r371：【JSON 接口嗅探】—— 上面 send() 只收 m3u8/mp4，会把页面自己解密好的
                    // 分类/列表 JSON 全丢掉（这就是 b7b88 需要手写 AES、thisav66 卡住的根源）。
                    // 这里把「content-type 含 json 的响应 URL」也回传，Java 侧用同一 cookie 重放取数据。
                    + "var jseen={};function sendJson(u){try{if(!u||jseen[u])return;jseen[u]=1;"
                    + "if(u.indexOf('m3u8')>=0||u.indexOf('.mp4')>=0)return;"
                    + "if(u.length>900)u=u.slice(0,900);"
                    + "new Image().src='sniff://jsonurl:'+encodeURIComponent(u)}catch(e){}}"
                    // r501：【响应体里藏地址】—— 一类站点的播放地址不在任何请求 URL 上，而是 XHR 回来一段
                    // JSON：要么 url 字段被 base64 过（hsck/黄色仓库系：{"ok":1,"u":"aHR0cHM6…"}），
                    // 要么是 `https:\/\/…m3u8?auth_key=…` 这种转义串。两种都在浏览器里解，这里也在浏览器里解，
                    // 解出来仍是标准 m3u8 → 走同一个 send()，壳侧不用新增协议。
                    + "var BS=String.fromCharCode(92);"
                    + "function unesc(s){while(s.indexOf(BS+'/')>=0)s=s.split(BS+'/').join('/');return s}"
                    + "function b64u(b){try{var d=atob(b);if(d.indexOf('http')===0)return d}catch(e){}return ''}"
                    + "function digBody(t){try{if(!t||t.length>500000)return;"
                    // ① 明文 m3u8（允许 \/ 转义）
                    + "var i=t.indexOf('m3u8');"
                    + "if(i>0){var s=t.lastIndexOf('http',i);"
                    + "if(s>=0&&i-s<600){var e=t.indexOf('\"',i);if(e<0||e-i>300)e=i+4;"
                    + "send(unesc(t.substring(s,e)));return}}"
                    // ② {"u":"<base64>"} / {"url":"<base64>"}（手势验证接口的返回形态）
                    + "var keys=['\"u\":\"','\"url\":\"','\"src\":\"'];"
                    + "for(var q=0;q<keys.length;q++){var k=t.indexOf(keys[q]);if(k<0)continue;"
                    + "var j=t.indexOf('\"',k+keys[q].length);if(j<0)continue;"
                    + "var b=t.substring(k+keys[q].length,j);if(b.length<24)continue;"
                    + "var u=b64u(b);if(u&&u.indexOf('m3u8')>=0){send(u);return}"
                    + "if(u&&u.indexOf('.mp4')>=0){send(u);return}}"
                    + "}catch(e){}}"
                    + "try{if(window.__jh!==1){window.__jh=1;"
                    + "var XO2=XMLHttpRequest.prototype.open;"
                    + "XMLHttpRequest.prototype.open=function(m,u){try{this.__ju=u}catch(e){};return XO2.apply(this,arguments)};"
                    + "var XS2=XMLHttpRequest.prototype.send;"
                    + "XMLHttpRequest.prototype.send=function(){var x=this;try{x.addEventListener('load',function(){"
                    + "try{var ct=(x.getResponseHeader('content-type')||'');"
                    + "if(ct.indexOf('json')>=0)sendJson(x.__ju)}catch(e){}"
                    + "try{digBody(x.responseText)}catch(e){}"
                    + "})}catch(e){};return XS2.apply(this,arguments)};"
                    + "}}catch(e){}"
                    + "try{if(window.fetch&&window.__jf!==1){window.__jf=1;var F2=window.fetch;"
                    + "window.fetch=function(i){var u=typeof i==='string'?i:(i&&i.url);"
                    + "return F2.apply(this,arguments).then(function(r){"
                    + "try{var ct=(r.headers.get('content-type')||'');if(ct.indexOf('json')>=0)sendJson(u)}catch(e){}"
                    + "return r})}}}catch(e){}"
                    + "try{var XO=XMLHttpRequest.prototype.open;"
                    + "XMLHttpRequest.prototype.open=function(m,u){send(u);return XO.apply(this,arguments)}}catch(e){}"
                    + "try{if(window.fetch){var F=window.fetch;window.fetch=function(i){"
                    + "try{send(typeof i==='string'?i:(i&&i.url))}catch(e){};return F.apply(this,arguments)}}}catch(e){}"
                    + "try{var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');"
                    + "if(d&&d.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{"
                    + "set:function(v){send(v);return d.set.call(this,v)},get:d.get,configurable:true})}catch(e){}"
                    + "function clickPlay(){try{"
                    // r335：**去掉 [data-src] 与 video** —— 真机 both.folrl0a2w.cc 首页有 94 个
                    // `<div class="post-item-poster" data-src="…jpeg">` 懒加载图，点它会跳转/刷新页面，
                    // 日志表现为 pageFinished 连续 9 次、用户看到「跳网页之后不会跳回来」。
                    // 只点明确的播放按钮类，宁可点不到，也不能乱点。
                    + "var sels=['.playbtn','.play-btn','.xgplayer-start','.vjs-big-play-button','.dplayer-play-icon','.episode-link','.episode-list a'];"
                    // r338：**撤掉 r337 加的 .ql-video / .melon-player / video-sub-item 点击**。
                    // 真机证据（10:58:34~10:58:46，REV r337）：点了 .ql-video 10 次，但
                    //   ① 网络请求里始终没有 melon_detail_play.js（document.write 的脚本压根没加载）
                    //   ② 页面里的 <div class="ql-video"> 内容就是 eval(...document.write(...))，
                    //      在页面加载完成后调 document.write 会**清空整个文档** → 用户看到封面变纯色块、页面异常
                    // ③ 始终没有「通用嗅探命中」
                    // 即：点它对拿地址毫无帮助，只会破坏页面。恢复成只点真正的播放按钮。
                    + "for(var i=0;i<sels.length;i++){var el=document.querySelector(sels[i]);"
                    + "if(el&&el.click){el.click();rep('通用嗅探点击 '+sels[i]);return true}}"
                    + "}catch(e){}return false}"
                    // r501：**两条与播放器实现无关的取址通道** —— 页面用 xgplayer/hls.js/WASM 拼地址时，
                    // 未必经过 XMLHttpRequest 或 HTMLMediaElement.src（黄果短剧的 edge-api-crypto 就是这型），
                    // 但它一定会发网络请求、最终一定把地址交给某个 <video>。所以：
                    //   ① performance 资源时间线里出现的 m3u8/mp4 一定真被请求过（任何实现都躲不过）；
                    //   ② DOM 里 video/audio 的 currentSrc 是最终生效地址。
                    + "function perfScan(){try{var ps=performance.getEntriesByType('resource');"
                    + "for(var i=ps.length-1;i>=0&&i>ps.length-80;i--){var nm=ps[i].name;"
                    + "if(nm.indexOf('m3u8')>=0||nm.indexOf('.mp4')>=0){send(nm);return}}}catch(e){}}"
                    + "function domScan(){try{var vs=document.querySelectorAll('video,audio');"
                    + "for(var i=0;i<vs.length;i++){var s2=vs[i].currentSrc||vs[i].src;if(s2)send(s2)}}catch(e){}}"
                    + "var n=0;(function tick(){if(sent)return;perfScan();domScan();if(n++<10){clickPlay();setTimeout(tick,1300)}})();"
                    + "}catch(e){}})()";
    /**
     * r378：gaze 播放器换代（真机日志 + 真实浏览器双重实证）。
     *
     * <p>站点已把剧集从 `.playbtn[data-src=<hex密文>]` 改成 `<button data-id data-mid data-path>`，
     * 播放器换成 GazeVideo10(video.js 系) + hls-engine；真正构建 WASM 的代码在 StartKilling 里，
     * 它把 icon.svg + skin.css 的字节拼成 wasm 二进制。
     * 真浏览器验证后 `[data-src]` 只剩 4 个 `<img src="/user/verifys">`（长度 13~15、带 `/`），
     * 全被 tryApi 的「纯 hex」判据滤掉 → `tried=0` → 永远不回传地址 → 用户卡在网页里出不来。
     * 真机 23:10 日志铁证：`noHex cnt=4 cap=0` 反复出现，但既无 `cand*` 也无 `页面脚本回传地址`。
     *
     * <p>修法：只做**被动嗅探**（hook HTMLMediaElement.src / XHR / fetch / GazeVideo10.player.src），
     * **绝不自动点击** —— r333 已实证 gaze 被通用 hook 的自动点击干扰过线路加载。
     */
    private static final String GAZE_NET_HOOK =
            ";(function(){try{if(window.__gnh)return;window.__gnh=1;"
                    + "var sent=false;"
                    + "function rep(m){try{new Image().src='sniff://'+encodeURIComponent('log:'+m)}catch(e){}}"
                    + "function send(u){try{if(sent||!u)return;u=String(u);"
                    + "if(u.indexOf('m3u8')<0&&u.indexOf('.mp4')<0)return;sent=true;"
                    + "rep('gaze嗅探命中 '+u.slice(0,140));"
                    + "new Image().src='sniff://'+encodeURIComponent(u);return true}catch(e){return false}}"
                    + "try{var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');"
                    + "if(d&&d.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{"
                    + "set:function(v){send(v);return d.set.call(this,v)},get:d.get,configurable:true})}catch(e){}"
                    + "try{var XO=XMLHttpRequest.prototype.open;"
                    + "XMLHttpRequest.prototype.open=function(m,u){send(u);return XO.apply(this,arguments)}}catch(e){}"
                    + "try{if(window.fetch){var F=window.fetch;window.fetch=function(i){"
                    + "try{send(typeof i==='string'?i:(i&&i.url))}catch(e){};return F.apply(this,arguments)}}}catch(e){}"
                    + "var n=0,iv=setInterval(function(){try{"
                    + "var p=window.GazeVideo10&&window.GazeVideo10.player;"
                    + "if(p&&!p.__gn){var os=p.src;p.src=function(s){try{send(typeof s==='string'?s:(s&&s.src))}catch(e){};"
                    + "return os.apply(this,arguments)};p.__gn=1;}}catch(e){}"
                    + "if(sent||++n>60)clearInterval(iv)},800);"
                    + "})()";
    /**
     * r378：gaze 类站【页内播放模式】—— 站点已无任何明文地址（真浏览器 + 真机双重实证）：
     * <ul>
     *   <li>媒体请求 <code>gaze_file_vl/pb/雨霖铃/1+gazes_v-@_info</code> 返回 gzip，解压后开头魔数 <b>MEV4</b>（自研加密容器）；</li>
     *   <li>页内 HLS 清单是 blob:，分片地址也是被混淆的 blob 名，不是 http URL；</li>
     *   <li>旧 hex 密文链（.playbtn[data-src] + IwasKing + DimGive）已消失，[data-src] 只剩 4 个 /user/verifys 验证图片。</li>
     * </ul>
     * 所以「嗅探明文地址交给 ExoPlayer」这条路已经不可行。
     *
     * <p>本脚本只做两件事（严格按 skill webview-fullscreen：<b>只改样式、不动 DOM 结构</b>）：
     * <ol>
     *   <li>hideSib：逐层隐藏播放器兄弟节点（仅 display:none，不删除/不搬移），再把播放器容器固定为全屏；</li>
     *   <li>轮询 video.readyState，真正开播后回传 sniff://pageplay，让壳取消解析超时、保留网页供用户观看。</li>
     * </ol>
     * <p><b>不包含任何点击</b> —— 真机 23:05/23:10 日志：旧 PAGE_PLAY_API 的 clickSource 连点线路 1/10…10/10，
     * 而全新标签页零注入时页面自己能正常自动播放（readyState=4），对照证明是自动点击打断了新版播放器。
     */
    private static final String GAZE_PAGE_PLAY =
            ";(function(){try{if(window.__gpp)return;window.__gpp=1;"
                    + "var sent=false;"
                    + "function rep(m){try{new Image().src='sniff://'+encodeURIComponent('log:'+m)}catch(e){}}"
                    // skill：不 appendChild / 不 cloneNode / 不 innerHTML，只做 display:none 与 fixed 定位
                    + "function hideSib(node){try{var n=node;"
                    + "while(n&&n.parentElement&&n.parentElement!==document.documentElement){"
                    + "var p=n.parentElement;var kids=p.children;"
                    + "for(var i=0;i<kids.length;i++){var k=kids[i];"
                    + "if(k!==n&&k.tagName!=='SCRIPT'&&k.tagName!=='STYLE'){try{k.style.display='none'}catch(e){}}}"
                    + "n=p;}}catch(e){}}"
                    + "function fs(){try{if(window.__gpfs)return;"
                    + "var box=document.querySelector('video-skin')||document.querySelector('video-player')||document.querySelector('video');"
                    + "if(!box)return;window.__gpfs=1;hideSib(box);"
                    + "document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden';"
                    + "box.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;z-index:2147483647;background:#000';"
                    + "var v=box.querySelector('video');if(v)v.style.cssText='width:100%;height:100%;object-fit:contain';"
                    + "rep('页内全屏 ok')}catch(e){}}"
                    + "var n=0,iv=setInterval(function(){try{"
                    + "var v=document.querySelector('video');"
                    + "if(v&&v.readyState>=2){fs();"
                    + "if(!sent){sent=true;rep('页内播放已接管 ready='+v.readyState);"
                    + "try{new Image().src='sniff://pageplay'}catch(e){}}"
                    + "clearInterval(iv);return}}"
                    + "}catch(e){}"
                    + "if(++n>120)clearInterval(iv)},1000);"
                    + "})()";


    /** r334：gaze 类站判定 —— **必须同时看 URL，不能只看 HTML**。
     *  事故（r333 引入）：playerContent 里的 html 来自 get(page)，而 gaze 的 /play/&lt;hex&gt; 要过 Cap 校验，
     *  get() 常拿回「访问验证」空壳（没有 IwasKing 等指纹）→ isGazeLike 判 false → 通用 hook 被误注入
     *  → 它的自动点击干扰了 gaze 自己的线路加载。真机日志：web_gazered 的 click 里连续 5 次「通用嗅探点击 .playbtn」。
     */
    private static boolean isGazeLike(String url, String html) {
        if (!TextUtils.isEmpty(url)) {
            String u = url.toLowerCase(Locale.ROOT);
            if (u.contains("gaze.red")) return true;
        }
        if (TextUtils.isEmpty(html)) return false;
        String low = html.toLowerCase(Locale.ROOT);
        return low.contains("iwasking") || low.contains("gazevideo") || low.contains("data-source-group");
    }

    private static final String PAGE_PLAY_API =
            "(function(){var n=0,sent=false;"
                    + "function send(u){if(!u||sent)return;if(u.indexOf('m3u8')<0&&u.indexOf('.mp4')<0)return;sent=true;"
                    + "try{new Image().src='sniff://'+encodeURIComponent(u)}catch(e){}}"
                    + "function rep(m){try{new Image().src='sniff://'+encodeURIComponent('log:'+m)}catch(e){}}"
                    + "var _tried={};"
                    + "function tryApi(){var els=document.querySelectorAll('.playbtn[data-src]');"
                    + "if(!els.length)els=document.querySelectorAll('[data-src]');var tried=0;"
                    + "for(var i=0;i<els.length&&tried<12;i++){if(_tried[i])continue;var ds=els[i].dataset||{};var s=ds.src||'';"
                    + "if(!s||s.length<60)continue;"
                    // r273：<img data-src> 是图片 URL（含 / 与 :），必须排除，只认纯 hex 密文
                    + "if(s.indexOf('/')>=0||s.indexOf(':')>=0||s.indexOf('.')>=0)continue;"
                    + "_tried[i]=1;tried++;rep('cand'+i+' len'+s.length);"
                    + "if(typeof window.IwasKing==='function'){try{window.IwasKing(ds,function(o){try{send(o&&o.src)}catch(e){}});"
                    + "rep('invoke IwasKing#'+i+' len='+s.length)}catch(e){rep('invoke 异常')}}"
                    + "else{rep('IwasKing 未就绪')}}"
                    + "if(tried===0)rep('noHex cnt='+els.length+' cap='+(document.querySelector('cap-widget')?1:0));"
                    + "return tried>0}"
                    // r336：**不能再用裸 [data-id]** —— 真机 both.folrl0a2w.cc 有 62 个
                    // `<span class="search-history__hot-text--item" data-id="175952">`（搜索历史热门词），
                    // 点它就跳搜索页 → 日志 pageFinished 每 1.5 秒一次、连续 8 次，用户看到「跳网页不回来」。
                    // 限定成「按钮 / 播放列表内的 data-id」，gaze 的 <button class="playbtn" data-id="74595"> 仍能命中。
                    + "function clickOne(){var b=document.querySelector('.playlist [data-id],button[data-id],[class*=playlist] [data-id],.playbtn,.num')"
                    + "||document.querySelector('.playlist button')||document.querySelector('.episode-link');"
                    + "if(b){try{b.click();rep('click ep ok')}catch(e){rep('click ep fail')}}}"
                    // r307：清场挪到 PAGE_PLAY_API 里 —— 真机日志证明 REQ_HOOK（onPageStarted 注入）整段没执行，
                    // 而 PAGE_PLAY_API 是活的（cand/invoke 有 800+ 行）。这里做：
                    // 锁滚动 + 隐藏所有不在播放器祖先链上的元素 + 播放器钉满视口。
                    + "function isolate(){try{if(window.__iso)return;"
                    + "var box=document.querySelector('video-player')||document.querySelector('video');"
                    + "if(!box)return;window.__iso=1;"
                    + "document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden';"
                    + "var keep=[],n=box;while(n){keep.push(n);n=n.parentElement;}"
                    + "var all=document.querySelectorAll('body *');var hid=0;"
                    + "for(var i=0;i<all.length;i++){var el=all[i];"
                    + "if(el.contains(box)||keep.indexOf(el)>=0)continue;"
                    + "if(el.tagName==='SCRIPT'||el.tagName==='STYLE')continue;"
                    + "el.style.display='none';hid++;}"
                    + "box.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;z-index:2147483647;background:#000';"
                    + "var v=box.querySelector('video');if(v)v.style.cssText='width:100%;height:100%;object-fit:contain';"
                    + "rep('清场 hid='+hid)}catch(e){}}"
                    + "function readVideo(){try{"
                    // r308：照抄米侠浏览器（米侠浏览器_5.5.4.3）的取址方式 —— 直接读 video 元素的 src，
                    // blob 跳过；再退到 <source src>。这条通道比 hook IwasKing 回调更直接：
                    // 只要该线路的 video.src 是真实 http，就能立刻拿到交给壳播放器。
                    + "var vs=document.getElementsByTagName('video');"
                    + "for(var i=0;i<vs.length;i++){var v=vs[i];"
                    + "var u=v.src||v.currentSrc||'';"
                    + "if(u&&u.indexOf('blob:')!==0&&(u.indexOf('.mp4')>=0||u.indexOf('m3u8')>=0)){send(u);return}}"
                    + "var ss=document.getElementsByTagName('source');"
                    + "for(var j=0;j<ss.length;j++){var su=ss[j].src||'';"
                    + "if(su&&su.indexOf('blob:')!==0&&(su.indexOf('.mp4')>=0||su.indexOf('m3u8')>=0)){send(su);return}}"
                    + "}catch(e){}}"
                    + "function clickSource(){try{"
                    // r309：页面用 POST /play_source_group（gid + key + 签名头）按线路懒加载集数，
                    // 默认只渲染第一条线路。所以只读 .playbtn 永远只能看到默认线路的密文。
                    // 这里逐条点开线路面板，让页面自己加载新一批集数，再由 tryApi 去解密。
                    + "var tabs=document.querySelectorAll('[data-source-group]');"
                    + "if(!tabs.length)return;"
                    + "var i=window.__srcIdx||0;if(i>=tabs.length)return;"
                    + "window.__srcIdx=i+1;"
                    + "try{tabs[i].click();rep('点线路'+(i+1)+'/'+tabs.length)}catch(e){}"
                    + "}catch(e){}}"
                    // r340：**主动加载动态播放器脚本** —— 真机 both.folrl0a2w.cc 黑料页：
                    //   <div class="ql-video"><script>eval(packer…document.write("<script src=/index/melon_detail_play.js?u=<token>&g=<ts>>"))</script></div>
                    // 沙箱解码 packer 后语法完全正确，但真机网络请求里**从来没有** melon_detail_play.js
                    // （只请求了统计和剧照图片），说明页面里的 document.write 没生效。
                    // 做法：拦住 document.write 把它写的内容接住，自己用 appendChild 插 script
                    // —— 不用 document.write（它在加载完成后调用会清空文档，r338 的教训）。
                    + "function loadDynPlayer(){try{"
                    + "var boxes=document.querySelectorAll('.ql-video,.melon-player');"
                    + "for(var b=0;b<boxes.length;b++){var sc=boxes[b].querySelector('script');"
                    + "if(!sc||!sc.textContent)continue;"
                    // r343：document.write 持久包装 —— melon js 加载后会 document.write 写出
                    // data-url m3u8 的 div；恢复原生 write 会在 load 后清空整页（r338 教训），
                    // 玩家初始化脚本被清掉 → 没人消费 data-url → 永远不起播（真机 r342 实证）。
                    // 持久包装：拦截写入串，扫到 m3u8/mp4 直接 send() 回传；不再恢复原生。
                    + "if(!window.__dwHook){window.__dwHook=1;"
                    + "document.write=function(s){try{var t=String(s);rep('write 捕获 '+t.slice(0,60));"
                    + "var mu=/(https?:[^\"'\\s\\\\]*?\\.m3u8[^\"'\\s\\\\]*)|data-url=\"([^\"]+)\"/.exec(t);"
                    + "if(mu){send(mu[1]||mu[2])}}catch(e){}};}"
                    + "var ow=document.write,cap=null;"
                    + "try{document.write=function(s){if(!cap)cap=String(s);"
                    + "try{var t=String(s);var mu=/(https?:[^\"'\\s\\\\]*?\\.m3u8[^\"'\\s\\\\]*)|data-url=\"([^\"]+)\"/.exec(t);"
                    + "if(mu){send(mu[1]||mu[2])}}catch(e){}};eval(sc.textContent);}catch(e){}"
                    + "finally{document.write=window.__dwHook?document.write:ow;}"
                    + "if(!cap)continue;"
                    + "var m=/src=[\"']([^\"']+)[\"']/.exec(cap);if(!m)continue;"
                    + "var u=m[1];if(window.__dyn&&window.__dyn[u])continue;"
                    + "window.__dyn=window.__dyn||{};window.__dyn[u]=1;"
                    + "rep('动态播放器 '+u.slice(0,90));"
                    + "var s=document.createElement('script');s.src=u;"
                    + "s.onload=function(){rep('动态播放器已加载')};"
                    + "s.onerror=function(){rep('动态播放器加载失败')};"
                    + "document.head.appendChild(s);}"
                    + "}catch(e){}}"
                    // r534：**倒计时解锁 + 手势按钮**（hsck/黄色仓库系新形态）—— 播放页没有任何
                    // 现成播放器：inline 脚本倒计时 6s 后解锁 #go_btn，用户 pointerdown(手势坐标) + click
                    // 触发 startPlayer() → POST /static/count.php → {ok:1,u:base64(m3u8)} → mountPlayer。
                    // 上面的 XHR hook 已能 digBody 解 base64 并 send()，缺的就是有人点这个按钮。
                    + "function unlockGesture(){try{var b=document.getElementById('go_btn');"
                    + "if(!b){rep('go_btn 无');return false;}"
                    + "var dis=b.getAttribute('aria-disabled');"
                    + "if(dis==='true'){rep('go_btn 倒计时中 '+String(b.textContent||'').slice(0,16));return false;}"
                    + "window.__gclick=window.__gclick||0;if(window.__gclick>=2)return false;window.__gclick++;"
                    + "var x=120,y=300;"
                    + "try{b.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,clientX:x,clientY:y,pointerId:1}));}"
                    + "catch(e){try{b.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,clientX:x,clientY:y}))}catch(e2){}}"
                    + "rep('go_btn 手势已派发');"
                    + "if(typeof b.click==='function')b.click();"
                    + "rep('go_btn click 已发');return true;"
                    + "}catch(e){rep('unlockGesture 异常 '+e);return false;}}"
                    // r534：**直接读播放器全局** —— mountPlayer() 解出 base64 后设 window.MacPlayer.PlayUrl，
                    // 这是站点自己解好的明文 m3u8，比 XHR hook 更可靠。
                    + "function readMacPlayer(){try{var p=window['Mac'+'Player'];"
                    + "if(p){var u=p.PlayUrl||p.FullUrl||p.JsonUrl;if(u&&u.indexOf('http')===0){send(u);return true}}}"
                    + "catch(e){}return false;}"
                    + "function run(){isolate();readVideo();readMacPlayer();loadDynPlayer();clickSource();unlockGesture();if(!sent){if(!tryApi())clickOne()}"
                    + "if(n++<20&&!sent)setTimeout(run,1500);}"
                    + "setTimeout(run,1500);})()";

    private static String autoPlayScript(String html) {
        if (TextUtils.isEmpty(html)) return "";
        boolean dyn = html.contains("eval(function(p,a,c,k,e,d)")
                || html.contains("xgplayer") || html.contains("dplayer") || html.contains("hls.js");
        return dyn ? AUTO_PLAY_SCRIPT : "";
    }

    private String findPlayUrl(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String text = unescapeJs(html);
        Matcher m = P_M3U8.matcher(text);
        if (m.find()) return m.group(1).replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=");
        m = P_MP4.matcher(text);
        if (m.find()) return m.group(1).replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=");
        return "";
    }

    /**
     * r252b：把播放页里的 m3u8/mp4 候选**全部**收出来逐个探活。
     *
     * <p>69ck 这类站一条线路上会挂多个 CDN 域名，某些域名在特定网络下解析得到、连不上
     * （真机日志：jpxm3u8.com 探活 SocketTimeoutException），只取第一个就必然播不了。
     */
    private List<String> findPlayUrls(String html) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return out;
        String text = unescapeJs(html);
        for (Pattern p : new Pattern[]{P_M3U8, P_MP4}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String u = m.group(1).replace("\\/", "/");
                if (!out.contains(u)) out.add(u);
                if (out.size() >= 6) return out;
            }
        }
        return out;
    }

    /**
     * r521：手势门播放接口（hsck / 黄色仓库系）。
     *
     * <p>播放页结构（沙箱实证 /v5/292570-1-1.html）：
     * <pre>
     * var AID='292570', ASID='1', ANID='1', AK='2224d7fc…';
     * var ep='/' + ['st','atic','/','count','.php'].join('');      // 端点被拆成数组，防直连
     * x.send(xhrBody({id:AID,sid:ASID,nid:ANID,tk:AK,g:1,x:__gx,y:__gy,dt:…,sw:…,sh:…,tz:…,t:Date.now()}));
     * </pre>
     * 返回 {"ok":true,"u":"&lt;base64 的 m3u8&gt;"}。参数里的手势坐标只做「像人」校验，
     * 给一组合理值即可（120/300/1500ms/412x915）。
     */
    private String gesturePlay(String page, String html) {
        try {
            if (TextUtils.isEmpty(html) || !html.contains("xhrBody(")) return "";
            String aid = firstMatch(html, "AID\\s*=\\s*[\"']([^\"']{1,64})[\"']");
            String ak = firstMatch(html, "AK\\s*=\\s*[\"']([A-Za-z0-9+/=._-]{16,256})[\"']");
            if (TextUtils.isEmpty(aid) || TextUtils.isEmpty(ak)) return "";
            String sid = firstMatch(html, "ASID\\s*=\\s*[\"']([^\"']{1,32})[\"']");
            String nid = firstMatch(html, "ANID\\s*=\\s*[\"']([^\"']{1,32})[\"']");
            String path = jsConcat(firstMatch(html, "ep\\s*=\\s*['\"]([^'\"]{1,300})['\"]"));
            if (TextUtils.isEmpty(path) || !path.startsWith("/")) return "";
            okhttp3.FormBody.Builder fb = new okhttp3.FormBody.Builder()
                    .add("id", aid)
                    .add("sid", TextUtils.isEmpty(sid) ? "1" : sid)
                    .add("nid", TextUtils.isEmpty(nid) ? "1" : nid)
                    .add("tk", ak)
                    .add("g", "1")
                    .add("x", "120")
                    .add("y", "300")
                    .add("dt", "1500")
                    .add("sw", "412")
                    .add("sh", "915")
                    .add("tz", "-480")
                    .add("t", String.valueOf(System.currentTimeMillis()));
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(abs(path))
                    .post(fb.build())
                    .header("User-Agent", UA)
                    .header("Referer", page)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .build();
            try (Response res = OkHttp.client(15000).newCall(req).execute()) {
                if (res.body() == null) return "";
                String body = new String(res.body().bytes(), "utf-8");
                String b64 = firstMatch(body, "\"u\"\\s*:\\s*\"([A-Za-z0-9+/=]{16,4000})\"");
                if (TextUtils.isEmpty(b64)) {
                    b64 = firstMatch(body, "\"u\"\\s*:\\s*\"([^\"]{16,4000})\"");
                }
                if (TextUtils.isEmpty(b64)) {
                    log("手势接口无地址 " + (body.length() > 120 ? body.substring(0, 120) : body));
                    return "";
                }
                String u = new String(Base64.decode(b64, Base64.DEFAULT), "utf-8").trim();
                if (u.startsWith("//")) u = "https:" + u;
                if (!u.startsWith("http")) return "";
                log("手势接口直解 " + (u.length() > 90 ? u.substring(0, 90) + "…" : u));
                return u;
            }
        } catch (Throwable e) {
            log("手势接口异常 " + e);
            return "";
        }
    }

    /**
     * r522：三方 artplayer 解析器（qiyoudy2 系）。
     *
     * <p>链路：站内播放页 → iframe（第三方 content.php?vid=…）→ 302 到 /player/index.php，
     * 该页内联脚本：
     * <pre>
     * const Url="MIPR…", Sign="56640a…", From="lekanzyw";
     * $.ajax({url:'api.php', data:{url:Url, sign:Sign, t:From}}) → {"code":200,"url":"https://…m3u8"}
     * </pre>
     * api.php 是**播放器页相对路径**，必须用落地地址（lastFetchUrl）的目录拼。
     */
    private String iframeApiPlay(String page, String html) {
        try {
            if (TextUtils.isEmpty(html)) return "";
            String frame = firstMatchGroup(html, "<iframe[^>]+src=[\"']([^\"']{6,500})[\"']");
            if (TextUtils.isEmpty(frame)) return "";
            String fu = abs(unescapeHtml(frame));
            String fh = get(fu);
            if (TextUtils.isEmpty(fh) || !fh.contains("api.php")) return "";
            String u = firstMatch(fh, "(?:const|var|let)\\s+Url\\s*=\\s*[\"']([^\"']{6,4000})[\"']");
            String sign = firstMatch(fh, "(?:const|var|let)\\s+Sign\\s*=\\s*[\"']([^\"']{6,200})[\"']");
            if (TextUtils.isEmpty(u) || TextUtils.isEmpty(sign)) return "";
            String from = firstMatch(fh, "(?:const|var|let)\\s+From\\s*=\\s*[\"']([^\"']{1,60})[\"']");
            String apiRel = firstMatch(fh, "url\\s*:\\s*[\"']([^\"']{2,120}?api\\.php[^\"']*)[\"']");
            if (TextUtils.isEmpty(apiRel)) apiRel = "api.php";
            String landed = TextUtils.isEmpty(lastFetchUrl) ? fu : lastFetchUrl;
            String dir = landed.contains("/") ? landed.substring(0, landed.lastIndexOf('/') + 1) : schemeHost(landed);
            String api = apiRel.startsWith("http") ? apiRel : dir + apiRel.replaceFirst("^\\./", "");
            String q = api + (api.contains("?") ? "&" : "?")
                    + "url=" + java.net.URLEncoder.encode(u, "UTF-8")
                    + "&sign=" + java.net.URLEncoder.encode(sign, "UTF-8")
                    + (TextUtils.isEmpty(from) ? "" : "&t=" + java.net.URLEncoder.encode(from, "UTF-8"));
            // r526：api.php 常校验 Referer 必须是播放器页自身，通用 get() 带的是源站首页 → 会被拒。
            // 这里显式带落地播放器页地址，并声明 XHR（页面里本来就是 $.ajax）。
            String body;
            try (Response ar = OkHttp.client(15000).newCall(new okhttp3.Request.Builder()
                    .url(q)
                    .header("User-Agent", UA)
                    .header("Referer", landed)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()).execute()) {
                body = ar.body() == null ? "" : new String(ar.body().bytes(), "utf-8");
            }
            if (TextUtils.isEmpty(body)) return "";
            // r526：有些解析器 code!=200 时把错误放进 msg，照样不含 url；留痕便于真机排查
            if (!body.contains("\"url\"")) log("三方播放器接口无 url " + (body.length() > 120 ? body.substring(0, 120) : body));
            String real = firstMatch(body, "\"url\"\\s*:\\s*\"([^\"]{6,2000})\"");
            if (TextUtils.isEmpty(real)) return "";
            real = real.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=");
            if (!real.startsWith("http")) return "";
            log("三方播放器接口直解 " + (real.length() > 90 ? real.substring(0, 90) + "…" : real));
            return real;
        } catch (Throwable e) {
            log("三方播放器接口异常 " + e);
            return "";
        }
    }

    /**
     * r528：**播放页归一** —— 盾站「添加后无法播放」的通用修法，不再逐站适配。
     *
     * <p>很多站有 ccguard / JS 盾，纯 HTTP 只拿到「正在验证访问」壳；detail 抽不到剧集，
     * 兜底给的「页面播放」入口就是详情页 /view/123.html。详情页里根本没有播放器，
     * WebView 打开它、盾过得再好也嗅探不到任何 m3u8 —— 这是「添加后无法播放」的通病。
     *
     * <p>正确做法是把入口换成播放页 /play/123-0-1.html 交给 WebView：
     * 盾由浏览器自己执行 JS 写 cookie + reload 过掉；播放页里 iframe 三方播放器
     * 自己算实时 Sign 发 api.php，随后请求 m3u8，网络层（shouldInterceptRequest /
     * XHR·fetch hook）嗅到即可。全程零站点适配。
     */
    private String playPageOf(String page, String html) {
        if (TextUtils.isEmpty(page)) return "";
        // 已经是播放页 / 选集路由 → 不归一
        if (Pattern.compile("(?i)/(?:play|vodplay|player|v5|watch|episode)/").matcher(page).find()) return "";
        // r540：**查询参数式详情页 → iframe 播放页** —— hohoj 的 /video?id=N 详情页本身没有 m3u8，
        // 真正的 <iframe class="player" src="/embed?id=N"> 里才有直链；旧代码只认路径式详情页，
        // 日志「detail 无播放路由 → 本页嗅探」直达死路。这里按「同 host + 同 id + embed/player 语义」
        // 三个形态条件取播放页，不认域名；广告 iframe（trck/smartpop/ads）直接跳过。
        String qid = firstMatch(page, "(?:\\?|&)(?:id|vid|v|m|n)=(\\d{2,9})");
        if (!TextUtils.isEmpty(qid) && !TextUtils.isEmpty(html)) {
            Matcher ifr = Pattern.compile("<iframe[^>]+src=[\"']([^\"']{4,500})[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            while (ifr.find()) {
                String src = unescapeHtml(ifr.group(1));
                if (TextUtils.isEmpty(src) || src.startsWith("javascript:")) continue;
                String low = src.toLowerCase(Locale.ROOT);
                if (low.contains("trck") || low.contains("track") || low.contains("smartpop")
                        || low.contains("/ad") || low.contains("ads")) continue;
                if (!low.contains("embed") && !low.contains("player") && !low.contains("bofang")) continue;
                String u = abs(src);
                if (!hostOf(u).equalsIgnoreCase(hostOf(page))) continue;
                if (!Pattern.compile("(?:\\?|&)(?:id|vid|v|m|n)=" + Pattern.quote(qid) + "(?:\\D|$)").matcher(u).find()) continue;
                log("详情 iframe 播放路由 " + page + " → " + u);
                return u;
            }
        }
        // ① 页面自己给出的播放链接最准（macCMS 详情页的「立即播放」按钮）。
        //    但必须与当前条目同一部片 —— 列表缓存兜底时 html 可能是列表页，会抽到别的影片的播放链接。
        if (!TextUtils.isEmpty(html)) {
            String vid = firstMatch(page, "(?i)/(?:view|detail|movie|show|vod|bangumi|voddetail)/(\\d{2,9})");
            Matcher m = Pattern.compile("href=[\"']((?:https?://[^\"']+)?/(?:play|vodplay)/\\d+-\\d+-\\d+\\.html)[\"']").matcher(html);
            while (m.find()) {
                String u = m.group(1);
                if (!TextUtils.isEmpty(vid) && !u.contains("/" + vid + "-")) continue;
                return abs(unescapeHtml(u));   // 含 -0-0：电影单集就是它，不能跳过
            }
        }
        // ② macCMS 系详情页路径 → 推导播放页。编号必须用 **-0-0**：
        //    macCMS 的 /play/{id}-0-0.html 是「立即播放」入口，电影（单集）和剧集（第 1 集）都存在；
        //    而 -0-1 对电影是 404（真机奇优影院《狼蛇行动》实证：50587-0-0 = 22176B 有 iframe，
        //    50587-0-1 = 655B 404）。sid 常见 0，站方一般兼容。
        Matcher d = Pattern.compile("(?i)/(?:view|detail|movie|show|vod|bangumi)/(\\d{2,9})(?:\\.html|/|$)").matcher(page);
        if (d.find()) return schemeHost(page) + "play/" + d.group(1) + "-0-0.html";
        Matcher d2 = Pattern.compile("(?i)/voddetail/(\\d{2,9})").matcher(page);
        if (d2.find()) return schemeHost(page) + "vodplay/" + d2.group(1) + "-0-0.html";
        return "";
    }

    /**
     * r521：把被拆成数组的 JS 路径拼回来 —— `'/' + ['st','atic','/','count','.php'].join('')`
     * 取出的引号串按顺序拼接即 `/static/count.php`；纯字面量（无方括号）原样返回。
     */
    private static String jsConcat(String expr) {
        if (TextUtils.isEmpty(expr)) return "";
        if (!expr.contains("[") && !expr.contains("+")) return expr.trim();
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("['\"]([^'\"]*)['\"]").matcher(expr);
        while (m.find()) sb.append(m.group(1));
        String out = sb.toString().trim();
        return out.startsWith("/") ? out : "";
    }

    /** r525：路径实际是图片（封面/剧照常常放在 /video/m3u8/<日期>/<hash>/vod.jpg 这种目录里）。 */
    private static boolean looksLikeImageUrl(String u) {
        if (TextUtils.isEmpty(u)) return false;
        String path = u;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        return path.matches(".*\\.(?:jpg|jpeg|png|gif|webp|bmp|ico|svg)$");
    }

    /** r521：直解结果的统一出口（parse=0 + 带 Referer 的 header）。 */
    /**
     * push v45：从播放页取站方播放器配置声明的各档 hls（"mediaDefinitions":[{format:"hls",videoUrl,height,defaultQuality},…]，
     * 取第一组）。≥1 档就用默认档（defaultQuality=true；没有标默认取第一条）起播；≥2 档登记给 PushMultiRes 合成多画质。
     * mp4/remote 条目不收。按结构认，不认站名；不按时长判。
     */
    private String mediaDefsPlay(String html, String page) {
        try {
            if (TextUtils.isEmpty(html)) return "";
            int k = html.indexOf("\"mediaDefinitions\"");
            if (k < 0) return "";
            int s = html.indexOf('[', k);
            if (s < 0 || s - k > 40) return "";
            int depth = 0, e = -1;
            boolean inStr = false;
            for (int i = s; i < html.length() && i < s + 200000; i++) {
                char c = html.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (c == '"') inStr = true;
                else if (c == '[') depth++;
                else if (c == ']' && --depth == 0) { e = i; break; }
            }
            if (e < 0) return "";
            JSONArray arr = new JSONArray(html.substring(s, e + 1));
            JSONArray defs = new JSONArray();
            String defUrl = "";
            int defH = 0;
            Set<String> seenUrl = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !"hls".equalsIgnoreCase(o.optString("format", ""))) continue;
                String u = o.optString("videoUrl", "");
                if (!u.startsWith("http") || !seenUrl.add(u)) continue;
                int h = o.optInt("height", 0);
                if (h <= 0) h = parseInt(o.optString("quality", ""), 0);
                boolean d = o.optBoolean("defaultQuality", false);
                if (d && TextUtils.isEmpty(defUrl)) { defUrl = u; defH = h; }
                defs.put(new JSONObject().put("u", u).put("h", h).put("d", d ? 1 : 0));
            }
            if (defs.length() == 0) return "";
            if (TextUtils.isEmpty(defUrl)) {
                defUrl = defs.getJSONObject(0).getString("u");
                defH = defs.getJSONObject(0).optInt("h", 0);
                defs.getJSONObject(0).put("d", 1);
            }
            if (defs.length() >= 2) PushMultiRes.offer(defUrl, defs.toString(), "");
            return mediaResult(defUrl, page, "站方声明各档 hls " + defs.length() + " 档，默认 " + defH + "p" + (defs.length() >= 2 ? "（已登记多画质）" : ""));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * push v45（Liu 2026-11-24「不仅仅是P站」）：网页源解出单个 m3u8 后，若播放页里另外声明了主清单
     * （…/playlist.m3u8 或 …/master.m3u8，与解出地址不同），登记给 PushMultiRes 作「页面声明」候选；
     * 起播前升级时取回核实（≥2 档且含解出地址）才用，不成照原地址。已登记过站方各档的不覆盖。
     */
    private void offerDeclaredMaster(String out) {
        try {
            String html = playHtmlForMaster;
            if (TextUtils.isEmpty(out) || TextUtils.isEmpty(html)) return;
            JSONObject r = new JSONObject(out);
            if (r.optInt("parse", 0) != 0) return;
            String url = r.optString("url", "");
            if (!url.startsWith("http") || !url.toLowerCase(Locale.ROOT).contains(".m3u8") || PushMultiRes.hasOffer(url)) return;
            Matcher m = Pattern.compile("https?:(?:\\\\?/){2}[^\"'\\s<>()]+?/(?:playlist|master)\\.m3u8[^\"'\\s<>()]*", Pattern.CASE_INSENSITIVE).matcher(html);
            int n = 0;
            while (m.find() && n++ < 50) {
                String d = m.group().replace("\\/", "/");
                if (d.equals(url)) continue;
                PushMultiRes.offer(url, "", d);
                log("播放页声明了主清单，登记为多画质候选：" + (d.length() > 100 ? d.substring(0, 100) + "…" : d));
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    private String mediaResult(String url, String referer, String tag) {
        try {
            JSONObject r = new JSONObject();
            r.put("parse", 0);
            r.put("url", url);
            JSONObject h = new JSONObject();
            h.put("User-Agent", UA);
            h.put("Referer", TextUtils.isEmpty(referer) ? root + "/" : referer);
            r.put("header", h);
            log(tag + " → " + (url.length() > 100 ? url.substring(0, 100) + "…" : url));
            return r.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * r245：播放页本身没有直链时，从内嵌 iframe 里挖：
     * ①某些站把地址放在 query 参数里（?video_url=https%3A%2F%2F...index.m3u8，实测 69ck.ws 就是这种），
     * ②放不进参数的，再跟进一次 iframe 页面本身。
     */
    private String findPlayInFrame(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String frame = firstMatchGroup(html, "<iframe[^>]+src=[\"']([^\"']{6,500})[\"']");
        if (TextUtils.isEmpty(frame)) return "";
        frame = unescapeHtml(abs(frame));
        // ① query 参数里直接带地址
        Matcher q = Pattern.compile("[?&](?:video_url|play_url|url|v|src)=([^&#\"']{6,800})", Pattern.CASE_INSENSITIVE).matcher(frame);
        while (q.find()) {
            String val = q.group(1);
            try {
                val = java.net.URLDecoder.decode(val, "UTF-8");
            } catch (Throwable ignore) {
            }
            if (val.startsWith("//")) val = "https:" + val;
            if (val.contains(".m3u8") || val.contains(".mp4") || val.contains(".flv")) return val;
        }
        // ② 跟进 iframe 页面再找
        String inner = get(abs(frame));
        String play = findPlayUrl(inner);
        if (!TextUtils.isEmpty(play)) return play;
        String frame2 = firstMatchGroup(inner, "<iframe[^>]+src=[\"']([^\"']{6,500})[\"']");
        if (!TextUtils.isEmpty(frame2)) return findPlayUrl(get(abs(frame2)));
        return "";
    }

    /**
     * r346：解 eval(function(p,a,c,k,e,d){…}) packer 找播放直链（missav09 等），并应用页面
     * 自身的域名改写模板。真机实证（23:56 fns-220）：m3u8 在 packer 里运行时拼出
     * （f='https://surrit.com/&lt;uuid&gt;/playlist.m3u8'），P_M3U8 对原始 HTML 是 token 混淆匹配不到，
     * 候选=0 → 转 WebView 嗅探后 hls.js 迟迟不起（广告 mp4 先占位），全程 0 回传 → 一直转圈。
     * 页面 JS 自己把 CDN 域改写为 missav09.com/jmpres/&lt;域&gt;/（同域代理过 Cloudflare），
     * surrit 直连 403、jmpres 302→vckeepw CDN——原样与改写版都进候选探活，谁通用谁。
     * 黑料（ql-video 站）第一层 packer 解出的是 document.write 脚本地址，无 m3u8/mp4 直链
     * → findPlayUrls 抽不到，自然跳过，不回归。
     */
    private LinkedHashSet<String> packerCandidates(String html) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            if (html == null || !html.contains("eval(function(p,a,c,k,e,d)")) return out;
            // 从 eval( 定位截取再解，避免 unpackPacker 的非贪婪正则跨 packer 边界匹配
            // （missav09 页面前部还有 CSS/字体噪声，直接传整页会把无关内容混进 payload）
            int p = html.indexOf("eval(function(p,a,c,k,e,d)");
            String unpacked = unpackPacker(html.substring(p, Math.min(html.length(), p + 6000)));
            if (TextUtils.isEmpty(unpacked)) { log("packer 直解：解不出（结构不符）"); return out; }
            List<String> urls = findPlayUrls(unpacked);
            if (urls.isEmpty()) { log("packer 直解：解出但无 m3u8/mp4 直链 → 跳过"); return out; }
            // payload 里引号是 \' 转义，P_M3U8 会把 URL 尾部的 \ 吃进来（探活必 404），清掉
            for (int i = 0; i < urls.size(); i++) urls.set(i, urls.get(i).replaceAll("\\\\+$", ""));
            log("packer 直解 " + urls.size() + " 条 " + urls.get(0));
            // 页面自身的域名改写模板：source.replace(/https:\/\/(([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,})\//,'https://host/jmpres/$1/')
            String tpl = "";
            Matcher rw = Pattern.compile("\\.replace\\(/https:\\\\/\\\\/\\(\\(\\[a-zA-Z0-9-\\]\\+\\\\.\\)\\+\\[a-zA-Z\\]\\{2,\\}\\)\\\\//\\s*,\\s*['\"]([^'\"]*?)\\$1/?['\"]\\)").matcher(html);
            if (rw.find()) {
                tpl = rw.group(1);
                log("packer 域改写模板 " + tpl);
            }
            for (String u : urls) {
                if (TextUtils.isEmpty(u) || !u.startsWith("http")) continue;
                out.add(u);
                if (TextUtils.isEmpty(tpl)) continue;
                String sh = schemeHost(u);                       // https://surrit.com/
                if (TextUtils.isEmpty(sh)) continue;
                String host = sh.replaceFirst("^https?://", "").replaceFirst("/$", "");
                String path = u.substring(sh.length());          // 去掉 scheme://host/ 后的路径
                // 模板两种形态：r346 实测 missav09 的捕获是 "…/jmpres/"（非贪婪吞掉 $1 前的斜杠，
                // $1 应补在末尾）；若模板本身含 $1 则直接替换。两种都归一到「前缀 + host + / + path」。
                String rewritten;
                if (tpl.contains("$1")) rewritten = tpl.replace("$1", host);
                else rewritten = tpl.endsWith("/") ? tpl + host : tpl + "/" + host;
                if (!rewritten.endsWith("/")) rewritten += "/";
                out.add(rewritten + path);
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    // ------------------------------------------------------------------ zanpian 规则

    private LinkedHashMap<String, String> zanpianClasses(String html) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        Matcher m = P_NAV.matcher(html);
        while (m.find()) {
            String tid = m.group(1).toLowerCase(Locale.ROOT);
            String name = clean(m.group(2));
            if (SKIP_CLASS.contains(tid) || map.containsKey(tid)) continue;
            if (name.isEmpty() || name.length() > 8) continue;
            map.put(tid, name);
        }
        return map;
    }

    private JSONArray zanpianItems(String html, int limit) {
        JSONArray arr = new JSONArray();
        if (TextUtils.isEmpty(html)) return arr;
        // r362：绝对地址站也要能抽 —— wangfei.tv 分类页卡片全写 https://host/voddetail/N.html，
        // 旧正则只认 href="/forum/N.html"，整页抽 0 条。与 genericItems 同一个修法，不再只修一处。
        html = normalizeHref(html);
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<a[^>]+href=\"/forum/(\\d+)\\.html\"[^>]*title=\"([^\"]+)\"[^>]*>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String vid = m.group(1);
            if (!seen.add(vid)) continue;
            String name = unescapeHtml(m.group(2)).trim();
            String seg = html.substring(m.start(), Math.min(html.length(), m.start() + 700));
            // r363：与其余条目入口统一 —— 封面取「离锦点最近的 img」，避免错位一格
            String back = html.substring(Math.max(0, m.start() - 900), m.start());
            String pic = firstMatchGroup(seg, "data-background=\"([^\"]+)\"");
            if (TextUtils.isEmpty(pic) || isPlaceholderPic(pic)) pic = pickImgNearest(back, seg);
            String remark = firstMatchGroup(seg, "<span[^>]+class=\"note[^\"]*\"[^>]*>([^<]*)</span>");
            if (TextUtils.isEmpty(remark)) remark = firstMatchGroup(seg, "<div[^>]+class=\"subtitle[^\"]*\"[^>]*>([^<]{0,40})</div>");
            try {
                JSONObject o = new JSONObject();
                o.put("vod_id", "/forum/" + vid + ".html");
                o.put("vod_name", name);
                o.put("vod_pic", abs(pic));
                o.put("vod_remarks", clean(remark));
                arr.put(o);
            } catch (Throwable ignore) {
            }
            if (limit > 0 && arr.length() >= limit) break;
        }
        return arr;
    }

    // ------------------------------------------------------------------ maccms 规则

    private LinkedHashMap<String, String> maccmsClasses(String html) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        // r510：导航名几乎总包在 <em>/<span>/<i> 里（mtyy2 实测
        //   <a href="/vodtype/1.html" class=""><em class="fa ds-dianying"></em>…<em …></em>电影</a>），
        // 旧正则要求 a 内直接是纯文本 → 5 个分类一条都抽不到，于是掉到 genericClasses，
        // 拿到的是头部「更多」链接（/vodshow/1-----------.html；该模板下是 20KB 空列表页）→
        // 首页 8 个板块（电影精选/电视剧精选/…）全部「暂无内容」。
        // 改为剥标签取文本，仍限 12 字防把整段导航文案当分类名。
        Matcher m = Pattern.compile("<a[^>]+href=\"/vodtype/(\\d{1,8})\\.html\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String tid = m.group(1);
            String name = clean(unescapeHtml(m.group(2).replaceAll("<[^>]+>", " ")));
            // r523：目录型菜单（hsck 的「目录」块）把影片数放在 <span class="count"> 里，
            // 剥标签后变成「96804日本有码」→ 长度超 12 被丢弃，用户看到「分类显示不全」。
            name = name.replaceFirst("^[\\d,]+\\s*", "").trim();
            if (name.isEmpty() || name.length() > 14 || name.contains("更多")) continue;
            map.putIfAbsent(tid, name);
        }
        return map;
    }

    /**
     * r510：macCMS V10 双路由 —— 同一后台能同时生成 /vodshow/ 与 /vodtype/ 两套分类 URL，
     * 但模板只实现其中一套：mtyy2 的 /vodshow/1-----------.html 是 200/20KB 的**空列表**（title 都还是
     * 「最新-推荐-第页」），/vodtype/1.html 才是 168KB/322 条的正文。
     * 抽 0 条时拿 tid 里的数字 id 试一次另一种路由，避免「分类点进去空白」。
     */
    private String maccmsTypeUrl(String tid) {
        if (TextUtils.isEmpty(tid)) return "";
        Matcher m = Pattern.compile("(\\d{1,8})").matcher(tid);
        if (!m.find()) return "";
        return root + "/vodtype/" + m.group(1) + ".html";
    }

    /** r510：tid 属于 V10 的 vodshow/vodlist 路由族（这类路由在部分模板上是空列表页）。 */
    private static boolean hasSoftEmptyRoute(String tid) {
        return tid != null && (tid.contains("vodshow") || tid.contains("vodlist"));
    }

    private JSONArray maccmsItems(String html, int limit) {
        JSONArray arr = new JSONArray();
        if (TextUtils.isEmpty(html)) return arr;
        // r362：绝对地址归一化 —— wangfei.tv 沙箱复刻：改前 0 条 → 归一化后 96 条。
        // r255 修 4kcz 时只改了 genericItems 三个入口，漏了这里，导致 maccms 分类页全空。
        html = normalizeHref(html);
        Set<String> seen = new LinkedHashSet<>();
        // r354：nunuyy 类模板的列表项**直接链到播放页** /vodplay/{vid}-{sid}-{nid}.html，
        // 旧正则只认 /voddetail/\d+\.html → 分类页 maccmsItems 抽 0 条（真机 18:06 五个分类全空 + 沙箱实证 0 条）。
        // 另一个坑：这类模板的 <a> **没有 title 属性**，片名在 a 内容里（<h2><a href=...>片名</a></h2>），
        // 硬要求 title=" 会一个也抽不到。这里同时兼容三种取标题方式，取到就停。
        // r457：**详情链接形态自适应** —— 这是「改来改去都是同一个问题」的框架层根源：
        //   判型对了（首页含 /vodtype/ 导航 → maccms），但抽取锚死 /voddetail/、/vodplay/ 两种形态，
        //   站点一换详情路由就整页抽 0 条。dwh.mgyy4（蘑菇影院）实证：详情是**根路径纯数字**
        //   https://dwh.mgyy4.best/1179274.html —— /vodtype/20.html 沙箱 200/72 条，Java 抽取 0 条
        //   → 真机 5 个分类全返回 {"total":0,"list":[]}（用户「分类不对没有内容」）。
        //   改为：任意路径 + 末段 ≥3 位数字 + .html；用非内容路径表排除分类/筛选/分页（防 r325 式误收）。
        Pattern nonContent = Pattern.compile("/(?:vodtype|vodshow|vodlist|vodsearch|vodclass|vodlabel|list|type|show|label|search|tag|topic|actor|star|year|area|lang|sort|page)(?:/|$)", Pattern.CASE_INSENSITIVE);
        Matcher m = Pattern.compile("<a[^>]+href=\"((?:https?://[^/\"]+)?((?:/[^/\"]*)*)/(\\d{3,})(?:-\\d{1,3}-\\d{1,3})?\\.html)\"([^>]*)>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            if (nonContent.matcher(m.group(2)).find()) continue;
            String vid = m.group(3);
            // r354：同一部片在分类页会出现两次（封面 a 内是 img、标题 a 内才是片名），
            // 去重不能放在取标题之前 —— 旧写法先把 vid 写进 seen，结果封面 a 抢到席位、
            // 标题抽空 continue，带标题的那次被去重跳过，整页抽 0 条（沙箱复刻实证）。
            // 改成「先取标题，抽到真标题才占位」。
            if (seen.contains(vid)) continue;
            String attrs = m.group(4) == null ? "" : m.group(4);
            int segEnd = Math.min(html.length(), m.end() + 700);
            String seg = html.substring(m.start(), segEnd);
            // 标题：① a 自身内容（nunuyy：<h2><a>片名</a></h2>）
            // r460：名称抽取统一走 itemNameOf（与 maccmsPhpItems 共用同一份，消除双副本 → 不再「修一处漏一处」）
            String name = itemNameOf(seg, attrs);
            if (TextUtils.isEmpty(name)) continue;
            seen.add(vid);
            // r363：改用「取离锦点最近的 img」（与 genericItems 的 r271 同一修法）。
            // 旧写法 pickImgOk(往前 400 的固定窗口) 取窗口内第一张图 —— 卡片间距小于 400 时
            // 必然抽到上一张卡片的封面。wangfei 真 HTML 复刻：**64/68 条错位**（整齐错一位）。
            // r271 只修了 genericItems，maccms 路径从未同步，这就是「修过又复发」的原因。
            String back = html.substring(Math.max(0, m.start() - 900), m.start());
            // r412：**封面先看锚点自身属性** —— lu9936 的封面不在 <img>，而是写在
            // <a class="thumb" data-original="…png"> 上；只走 pickImgNearest(<img>) 会全空，
            // 空封面又会让相邻卡片「借」到邻居的图，看起来就是错位。
            String pic = pickImgFromAnchor(m.group(0));
            if (TextUtils.isEmpty(pic)) pic = pickImgNearest(back, seg);
            try {
                JSONObject o = new JSONObject();
                o.put("vod_id", m.group(1));
                o.put("vod_name", name);
                o.put("vod_pic", abs(pic));
                o.put("vod_remarks", clean(firstMatchGroup(seg, "<span[^>]+class=\"[^\"]*(?:pic-text|note)[^\"]*\"[^>]*>([^<]{0,20})</span>")));
                arr.put(o);
            } catch (Throwable ignore) {
            }
            if (limit > 0 && arr.length() >= limit) break;
        }
        return arr;
    }

    // ------------------------------------------------------------------ maccms（index.php 路由）

    private LinkedHashMap<String, String> maccmsPhpClasses(String html) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        // r245：这类站的导航项里常常混着 <i> 图标，inner 不能用 [^<] 卡死，洗净后再取
        // r455：href 允许子目录前缀（cgy.cghjs2.boats 部署在 /cn/home/web/index.php/vod/ 下）；
        // 前缀只认 /word 段杜绝外站绝对 URL 混入；数字 id 从组1移到组2，文本组3
        Matcher m = Pattern.compile("<a[^>]+href=\"((?:/[\\w.-]+)*/index\\.php/vod/type/id/(\\d+)\\.html)\"[^>]*>(.{0,80}?)</a>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String name = clean(m.group(3));
            if (TextUtils.isEmpty(name)) name = clean(firstMatchGroup(m.group(0), "title=\"([^\"]{1,14})\""));
            if (name.contains("更多") || name.length() > 14) name = "";
            // r248：名字抽不到也必须出分类 —— 否则整个源会表现为「没有分类」
            if (TextUtils.isEmpty(name)) name = "分类" + m.group(2);
            map.putIfAbsent(m.group(2), name);
        }
        return map;
    }

    /**
     * r455：maccmsphp 子目录部署发现。cgy.cghjs2.boats 实证（真机 detect classes=1 分类稀疏 + href 样本）：
     * 入口 /cghjs/ 只是跳板，内容挂在 /cn/home/web/index.php/vod/ 下，root 是纯 host——
     * 旧链路 root+"/index.php/..." 整条（分类抽取/列表/详情换算/搜索）全部 404 或漏收。
     * 从首页真实 href 里取含 index.php 的完整挂载路径（与默认值 "/index.php" 同语义，拼接模板 root+indexPath+"/vod/…" 两边对齐）；
     * 普通部署（href="/index.php/vod/"）提取值等于默认值，不触发更新，零影响。
     */
    private void discoverIndexPath(String html) {
        if (TextUtils.isEmpty(html)) return;
        Matcher m = Pattern.compile("href=\"((?:/[\\w.-]+)*/index\\.php)/vod/").matcher(html);
        if (m.find() && !m.group(1).equals(indexPath)) {
            indexPath = m.group(1);
            log("maccmsphp 子目录前缀 indexPath=" + indexPath);
        }
    }

    /**
     * r245：这类站的列表项往往**直接链到播放页**（/index.php/vod/play/id/N/sid/1/nid/1.html），
     * 而不是详情页，所以两种链接都要收，vod_id 原样保留，详情阶段再换算出详情页。
     */
    private JSONArray maccmsPhpItems(String html, int limit) {
        JSONArray arr = new JSONArray();
        if (TextUtils.isEmpty(html)) return arr;
        // r362：绝对地址归一化（同 maccmsItems，防止 /index.php/vod/ 类的同样问题）
        html = normalizeHref(html);
        // r456：主体列表容器限定 —— 部分模板（吃瓜合集社 cghjs / 016_tpl_wap）分类页顶部有横幅推荐位，
        // 每页/每类渲染同一批 10 条（真机实证：三个分类前 10 条 vid 完全相同 → 用户「所有分类内容都一样」）。
        // 找到主体列表容器就只抽容器内；找不到、或容器没缩小范围，则维持全页抽取（零回归）。
        String scoped = listContainerScope(html);
        if (scoped != html) {
            log("列表容器限定：全页 " + countItems(html) + " 条 → 主体容器 " + countItems(scoped) + " 条");
            html = scoped;
        }
        Set<String> seen = new LinkedHashSet<>();
        // r414：**lu9936 错位真因就在这一行**（子智能体逐行定位，A 级）：
        //   卡片结构 <li><a class="thumb" href="/detail/id/N" title="片名" data-original="封面.png">
        //                <span class="play"></span><span class="pic-text"></span></a>
        //            <h4><a href="/detail/id/N" title="片名">片名</a></h4></li>
        // 旧正则 `(.{0,60}?)</a>` 要求锚点内部 ≤60 字：
        //   · 封面锚内部 ≈93 字（两个 span）→ 永不匹配；
        //   · 只有 h4 标题锚能匹配，但它不含 data-original → 封面走 pickImgNearest，
        //     而 fwd 再往后 500 字必然跨进下一张卡片 → 取到下一张的封面（整齐错位一格）；
        //   · 片名 >60 字的卡片两个锚都不匹配 → 整条丢弃（内容不全）。
        // 修法：内部正文放宽到 300 字（覆盖封面锚），名字回退锚点 title，封面优先锚点属性，
        //       fwd 截断到下一个详情锚之前（不跨卡片）。
        // r455：同 maccmsPhpClasses，href 允许子目录前缀（组1=完整路径 vod_id、组2=数字 id、组3=内部，组号不变）
        // r460：**锚点内部长度上限不再锚死** —— 旧正则 ([\s\S]{0,300}?)</a> 要求锚点内部 ≤300 字，
        //   而模板卡片把 <img src=“长CDN地址”> + 多个 div 都放在锚点内（mnn.hsh6 实证：内部 >300 字
        //   → 正则整体不匹配 → 分类页 0 条；真机日志 4 个分类全 {"total":0}，用户报「分类没内容」）。
        //   改为「开标签 + ≤2000 字窗口」（与 maccmsItems 窗口式对齐），名称走 itemNameOf 多级候选（双副本合一）。
        Matcher m = Pattern.compile("<a[^>]+href=\"((?:/[\\w.-]+)*/index\\.php/vod/(?:play|detail)/id/(\\d+)[^\"]*)\"([^>]*)>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String vid = m.group(2);
            int segEnd = Math.min(html.length(), m.end() + 2000);
            String seg = html.substring(m.start(), segEnd);
            String name = itemNameOf(seg, m.group(3));
            if (TextUtils.isEmpty(name)) continue;
            if (!seen.add(vid)) continue;
            String back = html.substring(Math.max(0, m.start() - 500), m.start());
            int endFwd = Math.min(html.length(), m.start() + 500);
            Matcher nxt = Pattern.compile("<a[^>]+href=\"(?:/[\\w.-]+)*/index\\.php/vod/(?:play|detail)/id/\\d+").matcher(html);
            nxt.region(m.end(), html.length());
            if (nxt.find()) endFwd = Math.min(endFwd, nxt.start());
            String fwd = html.substring(m.start(), endFwd);
            String pic = pickImgFromAnchor(seg);
            if (TextUtils.isEmpty(pic)) pic = pickImgNearest(back, fwd);
            try {
                JSONObject o = new JSONObject();
                o.put("vod_id", m.group(1));
                o.put("vod_name", name);
                o.put("vod_pic", abs(pic));
                o.put("vod_remarks", "");
                arr.put(o);
            } catch (Throwable ignore) {
            }
            if (limit > 0 && arr.length() >= limit) break;
        }
        return arr;
    }

    /** r456：分类/列表页主体容器限定 —— 返回「确实缩小了范围」的主体列表容器 HTML，否则返回原 HTML（零回归）。 */
    private String listContainerScope(String html) {
        if (TextUtils.isEmpty(html)) return html;
        int whole = countItems(html);
        if (whole < 6) return html;
        for (String cls : new String[]{"list_vod", "vodlist", "stui-vodlist", "module-list", "list-videos"}) {
            Matcher m = Pattern.compile("<div[^>]+class=\"[^\"]*\\b" + cls + "\\b[^\"]*\"[^>]*>").matcher(html);
            while (m.find()) {
                int end = divEnd(html, m.end());
                if (end <= m.start()) continue;
                String scope = html.substring(m.start(), end);
                int n = countItems(scope);
                if (n >= 3 && n < whole) return scope;   // 有实质条目且确实剔除了容器外的内容（推荐位/侧栏）
            }
        }
        return html;
    }

    /**
     * r460：条目名称多级候选 —— maccmsItems / maccmsPhpItems **共用同一份**（消除双副本，避免「修一处漏一处」复发）。
     * ① 锚点直接文本（<h2><a>片名</a></h2>）② 开标签 title 属性 ③ 锚内任意 title 属性
     * ④ 锚内「标题类 div/span」文本（mnn.hsh6：<div class="video-title title-truncate m-t-5">片名</div>）
     * ⑤ 锚内最后一个非空文本节点。超长（>150）视为噪声弃用。
     */
    private String itemNameOf(String seg, String attrs) {
        String name = clean(firstMatchGroup(seg, ">\\s*([^<]{1,80})</a>"));
        if (TextUtils.isEmpty(name)) name = clean(firstMatchGroup(attrs == null ? "" : attrs, "title=\"([^\"]{1,80})\""));
        if (TextUtils.isEmpty(name)) name = clean(firstMatchGroup(seg, "title=\"([^\"]{1,80})\""));
        if (TextUtils.isEmpty(name)) name = clean(firstMatchGroup(seg, "class=\"[^\"]*(?:title|name)[^\"]*\"[^>]*>([^<]{1,80})<"));
        if (TextUtils.isEmpty(name)) name = clean(firstMatchGroup(seg, "([^<>]{2,80})</a>"));
        return (!TextUtils.isEmpty(name) && name.length() <= 150) ? name : "";
    }

    /** r456/r457：条目锚计数（listContainerScope 判据用）—— 两类形态都算：index.php 路由 + 末段≥3位数字的 .html。 */
    private int countItems(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        Matcher m = Pattern.compile("/index\\.php/vod/(?:play|detail)/id/\\d+|/[^/\"]*?\\d{3,}\\.html").matcher(html);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** r456：从 openEnd 起做 div 配对扫描，返回配对区域结束位置（页面末尾兜底）。 */
    private int divEnd(String html, int openEnd) {
        int depth = 1, i = openEnd;
        while (i < html.length()) {
            int no = html.indexOf("<div", i), nc = html.indexOf("</div", i);
            if (nc < 0) return html.length();
            if (no >= 0 && no < nc) { depth++; i = no + 4; }
            else { depth--; if (depth == 0) return Math.min(html.length(), nc + 6); i = nc + 5; }
        }
        return html.length();
    }

    private JSONObject maccmsPhpDetail(String html, String id) throws Exception {
        JSONObject vod = baseDetail(html, id);
        vod.put("vod_name", detailTitle(html));
        vod.put("vod_pic", detailPic(html));
        vod.put("vod_year", firstMatch(html, "年份[：:]\\s*(?:<[^>]+>\\s*)*([0-9]{4})", "年代[：:]\\s*(?:<[^>]+>\\s*)*([0-9]{4})"));
        vod.put("vod_area", firstMatch(html, "地区[：:]\\s*(?:<[^>]+>\\s*)*([^<\\s]{1,12})"));
        String intro = firstMatchGroup(html, "<span[^>]+class=\"[^\"]*(?:detail|content|desc)[^\"]*\"[^>]*>(.*?)</span>");
        if (TextUtils.isEmpty(intro)) intro = firstMatchGroup(html, "<div[^>]+class=\"[^\"]*(?:desc|content|intro)[^\"]*\"[^>]*>(.*?)</div>");
        if (!TextUtils.isEmpty(intro)) vod.put("vod_content", clean(intro));
        // 线路名：<div class="down-title"><h2>麻花-在线播放</h2>
        List<String> names = new ArrayList<>();
        Matcher nm = Pattern.compile("<div[^>]+class=\"[^\"]*down-title[^\"]*\"[^>]*>\\s*<h2[^>]*>([^<]{1,30})</h2>", Pattern.DOTALL).matcher(html);
        while (nm.find()) names.add(clean(nm.group(1)));
        // 集数：/index.php/vod/play/id/N/sid/S/nid/E.html
        LinkedHashMap<String, List<String[]>> grouped = new LinkedHashMap<>();
        LinkedHashMap<String, String> lineName = new LinkedHashMap<>();
        // r456：① 允许子目录前缀（r455 漏改 —— /cn/home/web/index.php/... 真实选集抽不到，
        //        导致详情页落入 collectionEpisodes，把推荐位 16 条当成「合集」=用户点开全是别的视频）；
        //       ② 只收与当前条目同 vid 的集数链接 —— 详情页推荐位（其它 vid）不得当选集（框架级通用判据）。
        String curVid = itemKeyOf(id);
        Matcher e = Pattern.compile("<a[^>]+href=\"((?:/[\\w.-]+)*/index\\.php/vod/play/id/(\\d+)/sid/(\\d+)/nid/(\\d+)\\.html)\"[^>]*>(.{0,40}?)</a>", Pattern.DOTALL).matcher(html);
        while (e.find()) {
            if (!TextUtils.isEmpty(curVid) && !curVid.equals(e.group(2))) continue;
            String line = e.group(3);
            String epName = clean(e.group(5));
            if (TextUtils.isEmpty(epName)) epName = "第" + e.group(4) + "集";
            List<String[]> eps = grouped.get(line);
            if (eps == null) {
                eps = new ArrayList<>();
                grouped.put(line, eps);
            }
            boolean dup = false;
            for (String[] old : eps) if (old[0].equals(e.group(4))) dup = true;
            if (!dup) eps.add(new String[]{e.group(4), epName, e.group(1)});
        }
        int i = 0;
        for (String key : grouped.keySet()) {
            String name = i < names.size() ? names.get(i) : "";
            if (!TextUtils.isEmpty(name)) lineName.put(key, name);
            i++;
        }
        joinPlay(vod, grouped, lineName, null);
        return vod;
    }

    // ------------------------------------------------------------------ 通用兜底

    // ------------------------------------------------------------------ 内容大类归并

    /**
     * r251：把分类收敛到「视频 / 图片 / 音乐 / 小说」四个内容大类。
     *
     * <p>触发条件：分类里能同时识别出 ≥2 个大类（说明这是个「综合站」，有视频又有小说/图片…）。
     * 这时每个大类只留一条最像的入口，其余（树洞求助、tag、av/theme 子分类…）全部去掉。
     * 单一板块的站不触发 —— 影视站的「电影/电视剧/动漫/综艺」必须原样保留。
     */
    /** r259：分类持久化缓存 —— 站点源站抖动（Cloudflare 520）时首页抽不到分类，不能因此把分类栏清空。 */
    private void cacheClasses(LinkedHashMap<String, String> map) {
        if (map == null || map.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : map.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("name", e.getValue());
                arr.put(o);
            }
            com.github.catvod.utils.Prefers.put("web_classes_" + siteKey, arr.toString());
        } catch (Throwable ignore) {
        }
    }

    private LinkedHashMap<String, String> cachedClasses() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        try {
            String s = com.github.catvod.utils.Prefers.getString("web_classes_" + siteKey);
            if (TextUtils.isEmpty(s)) return map;
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                if (!TextUtils.isEmpty(id)) map.put(id, o.optString("name"));
            }
        } catch (Throwable ignore) {
        }
        return map;
    }

    private LinkedHashMap<String, String> dropNonContent(LinkedHashMap<String, String> src) {
        if (src == null || src.size() < 2) return src;
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : src.entrySet()) {
            if (isNonContent(e.getKey(), e.getValue())) continue;
            // r346：站名/logo 不是分类（missav09 的 "missav07"，与域名主体 missav09 同源）
            if (isSiteName(e.getValue())) continue;
            out.put(e.getKey(), e.getValue());
        }
        // r326：**全被剔除时不能原样退回** —— 旧代码 `if (out.isEmpty()) return src;`
        // 等于「过滤失效」：真机飞卢 3 条全被判非内容后，又把原样的垃圾退了回去，
        // 于是分类永远是那 3 条、二级展开（要求 ≤1 条）永远不触发。
        // 现在返回空，由上层决定（二级展开 / 回退缓存 / 兜底 all）。
        if (out.isEmpty()) {
            log("分类全部被判为非内容 " + src.size() + " 条 → 清空等待上层处理");
            return out;
        }
        if (out.size() != src.size()) {
            List<String> gone = new ArrayList<>();
            for (Map.Entry<String, String> e : src.entrySet()) if (!out.containsKey(e.getKey())) gone.add(e.getValue());
            log("分类剔除非内容项 " + src.size() + "→" + out.size() + " 去掉=" + gone);
        }
        return out;
    }

    /**
     * r252：非内容分类 —— 树洞闲聊（全部/求助/吐槽/倾诉）、公告/帮助/法务/会员这类，不是片子列表。
     *
     * <p>r251 把「多内容大类」的站归并成 4 条，实测把 A 站 18 条砍成 2 条，过度了；
     * 现在只剔明显非内容项，视频/图片/音乐/小说的各子分类（含吃瓜、AV 主题）原样保留。
     */
    private static boolean isNonContent(String tid, String name) {
        String t = tid == null ? "" : tid.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name;
        if (t.startsWith("treehole") || t.contains("/treehole")) return true;
        // r320：分页链接、站点自身名字都不是分类。真机飞卢听书首页抽出
        // {"name":"更多>","tid":"category/0/1.html"} 和 {"name":"飞卢听书","tid":"/ts.faloo.com"}（logo），
        // 用户看到的就是「分类不对，只有几条垃圾」。
        if (n.contains("更多")) return true;
        // r326：搜索入口不是分类（真机飞卢分类页展开后混进一条「搜书」= Search.aspx）
        if (n.contains("搜书") || n.contains("搜索") || n.contains("查找")) return true;
        // r539：`/search?type=xxx` 是「按类型筛选」的分类页，不是搜索入口（hohoj.tv 实证 無碼/中文字幕/歐美）。
        // 只有裸 /search?q= 或 /search 才是搜索。带 type= 的按分类放行。
        boolean searchCategory = t.contains("search") && (t.contains("type=") || t.contains("cat=") || t.contains("ctg="));
        if (!searchCategory && (t.contains("search") || t.contains("so.") || t.startsWith("so/"))) return true;
        // r327：分页链接不是分类（真机飞卢书籍列表页里的「下一页」「[02]」「[03]」）
        if (n.matches("^\\[?\\d{1,3}\\]?$") || n.contains("下一页") || n.contains("上一页") || n.contains("首页")
                || n.contains("末页") || n.equals("页")) return true;
        // r322：这里**不能**用 `xxx/N/M.html` 判分页 —— maccms/听书站的真实分类就是
        // category/1/1.html（分类id/页码），旧规则把它们全当分页丢掉：
        // 真机飞卢听书 11 个分类（玄幻/武侠/都市/军事/科幻/耽美/恐怖/校园/短篇/古典/人文）
        // 就是这样被砍到只剩 3 条，用户看到的就是「分类不对」。
        // 分页只认明确的页码参数；「更多>」这类由上面的名字规则处理。
        if (t.contains("/page/") || t.contains("paged=") || t.contains("page=")) return true;
        // r326：**纯主机名判定必须限定 TLD** —— 旧规则 `^/?[a-z0-9.\-]+\.[a-z]{2,}$` 会把
        // Category.aspx、list.html 这种**页面路径**也当成域名剔掉。
        // 真机后果：飞卢听书 3 条分类（快速充值/更多>/飞卢听书分类）全被剔除 →
        // dropNonContent 走到 `out.isEmpty() → return src` 退回原样 → 分类永远 3 条垃圾、
        // 二级展开（要求 ≤1 条）永远不触发，用户看到的就是「分类不对，毫无变化」。
        if (t.matches("^/?[a-z0-9.\\-]+\\.(com|net|org|cn|cc|io|tv|me|xyz|top|vip|info|co|la|ws|pw|us|in|site|online|club|shop|app)(/)?$")) return true;
        for (String k : new String[]{"树洞", "求助", "吐槽", "倾诉", "闲聊", "灌水", "公告", "活动", "关于我们",
                "帮助", "客服", "反馈", "招聘", "友链", "会员", "會員", "登录", "注册", "充值", "下载app", "App下载"})
            if (n.contains(k)) return true;
        // r486：小说站功能位不是分类 —— m.x33yq.org 实证 6 分类里混着「作者」（/author/ 作者索引页，
        //   点进去抽 0 条）、「临时书架」（/history.html）、「会员书架」（/mark.html）、
        //   「网站地图」（/sitemap/）、「电脑版」（跨域链接）。用户报「分类很多垃圾分类没过滤」。
        //   词表按「点进去无内容列表」的功能位语义归类，不锚域名；完本/全部小说/排行等内容板块不在表内。
        for (String k : new String[]{"作者", "书架", "阅读记录", "书签", "网站地图", "电脑版", "手機版", "手机版"})
            if (n.contains(k)) return true;
        // r486：tid 侧同批排除（名字可能被模板改写，路径形态更稳）。
        //   history/mark/bookshelf/favorite/shelf = 个人功能位；sitemap = 地图；author = 作者索引。
        //   反例防护：飞卢 category/1/1.html 真分类不含这些段，零误伤（r322 教训保持）。
        for (String k : new String[]{"/history", "/mark", "/bookshelf", "/favorite", "/shelf", "/sitemap", "/author"})
            if (t.contains(k)) return true;
        // r346：个人功能位/导航位不是分类 —— 真机 missav09 分类 24 条里混着「升級 VIP/我的影片收藏/
        // 我的片單/觀看記錄/女優一覽/韓國直播/女優排行」，点进去是导航页/收藏页不是片单。
        for (String k : new String[]{"VIP", "vip", "我的", "收藏", "片單", "片单", "觀看記錄", "观看记录", "直播", "排行", "女優一覽", "女优一览"})
            if (n.contains(k)) return true;
        // r540：演员/模特索引页不是影片分类 —— hohoj 的 /all_models 点进去是 /model?id= 演员页，
        // genericItems 按 /video?id= 抽 0 条，用户看到「女優分类没内容」。按路径形态剔除，
        // 常规 /video|/movie 分类零影响。
        if (t.matches("(?i)(?:.*/)?(?:all_)?(?:models|actresses|actors|performers)(?:[/?]|$)")) return true;
        return false;
    }

    /** r346：站名/logo 不是分类 —— 真机 missav09 分类第一条 {"cn","missav07"}，tid=cn 落不进
     * 纯主机名规则，但名字 missav07 与域名主体 missav09 同源（去数字后相等）。站名当分类点进去是首页。 */
    private boolean isSiteName(String name) {
        try {
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(root)) return false;
            String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            String h = hostOf(root).toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) h = h.substring(4);
            int dot = h.indexOf('.');
            if (dot > 0) h = h.substring(0, dot);
            if (n.length() < 4 || h.length() < 4) return false;
            return n.equals(h) || ((h.startsWith(n) || n.startsWith(h)) && Math.abs(n.length() - h.length()) <= 6);
        } catch (Throwable t) {
            return false;
        }
    }

    /** r346：导航功能位词（无封面无数字时才判垃圾）—— 真机 missav09 每个分类页前几条全是
     * 「升級 VIP/我的影片收藏/中國直播/女優一覽/類型/發行商」，点进去是导航页不是片单。 */
    private static final Pattern NAV_JUNK = Pattern.compile(
            "VIP|會員|会员|我的|收藏|片單|片单|觀看記錄|观看记录|直播|排行|女優一覽|女优一览|類型|类型|發行商|发行商"
            // r367：语言切换/字幕入口也不是影片 —— thisav66 每个分类页顶部都有
            // <a href="https://www.thisav66.com/cn">简体中文</a>，旧词表拦不住，
            // 用户看到的就是「每个分类下都有 VR/中文字幕这种标签」。
            + "|简体中文|繁體中文|繁体中文|中文字幕|日本語|English|한국어", Pattern.CASE_INSENSITIVE);
    /**
     * r368：索引/筛选页不是影片条目 —— thisav66 列表里反复出现
     *   vod_id=.../cn/makers（name=VR）、/cn/genres/xxx 这类导航页，
     * 点进去是分类列表不是影片，必然播不了（用户明确要求不要保留）。
     * 与分类侧「索引入口排除」共用同一套词；区别：这里用路径**包含**判断，
     * 因为 /cn/makers 与 /cn/genres/VR 都属索引路径。
     */
    private static final Pattern INDEX_PATH = Pattern.compile(
            "/(genres?|makers?|actors|actress|labels?|tags?|studios?|series|legacy|langs?|languages?)(/|$)", Pattern.CASE_INSENSITIVE);
    /** r346：广告位词（无封面无数字时才判垃圾）—— 真机 missav09 站内短链 3UywEhg=全球最大色情平台、
     * 4iDdcJA=AI女友任你肏，点进去 404/跳 x99dh.com 广告站（真机 23:55 实证）。 */
    private static final Pattern AD_JUNK = Pattern.compile(
            "色情平台|任你肏|約炮|约炮|破解版|百家樂|百家乐|抖陰|抖阴|糖心|嗶咔|哔咔|配信|好片看|禁果|黃果|黄果", Pattern.CASE_INSENSITIVE);

    /** r346：列表里的导航位/广告位不是条目。判据三选一：①外域链接；②无封面无数字+导航词；
     * ③无封面无数字+广告词。真卡（missav09 san-502-uncensored-leak）有封面有数字，全不命中；
     * r252 教训：不能一刀切「无封面就剔」——无 img 站会被清空，所以无封面必须再叠加
     * 「无数字 + 词表命中」两个条件，把误杀面压到最小（有数字或有封面的条目永不误杀）。 */

    /**
     * r363：分页控件文字 —— 绝不是片名。
     * 真机日志实证（sm455，498 条 category 条目里 117 条 = 23.5% 被污染）：
     *   vod_id/vod_pic 全对，但 vod_name 变成「末页」「上一页」。
     * 成因：genericItems 的标题兜底 `</a>\s*<a[^>]*>文字</a>` 会跨过卡片边界，
     * 最后几张卡片的 1600 字窗口落到页脚分页区（实测仅距 341 字），于是取到「末页」。
     * 而 isJunkListItem 的「有封面就放行」又提前 return，把这道关也绕过了。
     * 铁律：分类侧（isJunkClassName r327）与条目侧必须共用同一个函数，不得各写一套。
     */
    private static boolean isPagerText(String t) {
        if (TextUtils.isEmpty(t)) return false;
        String n = t.trim();
        if (n.length() > 12) return false;
        if (n.matches("^\\[?\\d{1,3}\\]?$")) return true;          // [02] / 3 / 12
        if (n.equals("页") || n.equals("上一页") || n.equals("下一页") || n.equals("首页")
                || n.equals("末页") || n.equals("尾页") || n.equals("上页") || n.equals("下页")) return true;
        // 带箭头/括号的常见写法：«上一页、下一页»、<上一页>、[下一页]
        String core = n.replaceAll("^[«<\\[【(（]+|[»>\\]】)）]+$", "").trim();
        return core.equals("上一页") || core.equals("下一页") || core.equals("首页")
                || core.equals("末页") || core.equals("尾页");
    }
    private boolean isJunkListItem(String href, String rel, String title, String pic) {
        try {
            String low = href == null ? "" : href.toLowerCase(Locale.ROOT);
            // ① 外域链接（normalizeHref 只转同域绝对地址，剩下的 http 开头就是外站广告位 bit.ly/…）
            if (low.startsWith("http") && !TextUtils.isEmpty(root)
                    && !low.contains(hostOf(root).toLowerCase(Locale.ROOT))) return true;
            // r363：分页控件文字必须在「有封面就放行」**之前**判 —— 否则分页按钮带的图标/占位图
            // 让 pic 非空，下一行的 return false 直接把「末页」放行（真机 117/498 条就是这么漏的）。
            if (isPagerText(title)) return true;
            // r368：**索引/筛选页不是影片条目** —— thisav66 列表里反复出现
            // vod_id=.../cn/makers（name=VR）、/cn/genres/xxx，点进去是分类列表不是影片，必然播不了。
            // 用户明确要求不要保留；与分类侧「索引入口排除」共用同一套词。
            // 反例验证：真实影片 URL 形如 /cn/1pondo/xxxxx，不含这些段，不受影响。
            String idxPath = low.startsWith("http") ? low.replaceFirst("^https?://[^/]+", "") : low;
            if (INDEX_PATH.matcher(idxPath).find()) return true;
            String t = title == null ? "" : title;
            // r367：**广告词必须先于「有封面就放行」**。
            // 真机 thisav66 实证：分类页顶部广告位是 bit.ly 短链（成人抖阴破解版/哔咔漫画/AI女友任你肏），
            // 它们带图 → 旧顺序在下一行就被 return false 放行，
            // 用户看到的就是「每个分类下都有 VR/中文字幕/破解版」。
            // 与 r363「末页」同一个坑：收紧型判断必须排在宽松放行之前。
            if (AD_JUNK.matcher(t).find()) return true;
            if (!TextUtils.isEmpty(pic)) return false;
            if (rel == null || !rel.matches(".*\\d.*")) {
                if (NAV_JUNK.matcher(t).find()) return true;
                String r = rel == null ? "" : rel.toLowerCase(Locale.ROOT);
                if (r.matches(".*(vip|saved|playlist|history|klive|clive).*")) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hit(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private LinkedHashMap<String, String> genericClasses(String html) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        // r251：先收「站点自报的一级板块」——带 data-navigation_name 的链接（A 站里就是
        // 首页/排序/合集/黑料吃瓜/av/小说）。这些是站点自己标的板块名，比启发式抽链接可靠，
        // 而且 /novels/new/ 这种多段路径、/moviesets/ 这种短路径在启发式里会被过滤掉。
        Matcher plate = Pattern.compile("<a[^>]{0,800}?data-navigation_name=\"([^\"]{1,12})\"[^>]{0,800}?>", Pattern.DOTALL).matcher(html);
        while (plate.find()) {
            String tag = plate.group(0);
            String name = unescapeHtml(plate.group(1)).trim();
            String href = firstMatchGroup(tag, "href=\"([^\"]{2,140})\"");
            String navKey = firstMatchGroup(tag, "data-navigation_key=\"([^\"]+)\"");
            if ("home".equals(navKey) || "order".equals(navKey)) continue;
            if (TextUtils.isEmpty(href) || href.startsWith("#") || !href.startsWith("/")) continue;
            String path = href.substring(1);
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (TextUtils.isEmpty(path) || path.contains("#")) continue;
            if (name.isEmpty() || name.length() > 12) continue;
            map.putIfAbsent(path, name);
        }
        // r245：能抽到 <nav> 就只在导航里挑（A 站 /av/theme/N/ 这种多段分类只在导航出现），
        // 导航里抽不到再退回整页的「一级栏目」。
        String scope = html;
        // r265：Bootstrap tab 型分类 —— gaze.red 是 <button data-target="#nav-cn">国产剧</button>，
        // 每个 tab 对应一个服务端渲染好的面板，只认 <a href> 的抽取完全看不到它们。
        Matcher tab = Pattern.compile("<button[^>]*data-target=\"#(nav-[A-Za-z0-9_-]{1,20})\"[^>]*>([^<]{1,12})</button>").matcher(html);
        while (tab.find()) {
            String tabName = unescapeHtml(tab.group(2)).trim();
            if (!tabName.isEmpty() && tabName.length() <= 12) map.putIfAbsent(tab.group(1), tabName);
        }
        Matcher nav = Pattern.compile("<nav[^>]*>(.*?)</nav>", Pattern.DOTALL).matcher(html);
        if (nav.find()) scope = nav.group(1);
        collectClasses(scope, map);
        // r263：不是「nav 抽到任意 1 条就停」—— gaze.red 的 <nav> 里只挂了一个专题链接（Dark/index.html），
        // map 非空于是不回退整页，页面外的「最近更新/高分电影/高分剧集/高分动漫」全被丢掉，分类只剩 1 条。
        if (map.size() < 3 && !scope.equals(html)) collectClasses(html, map);
        // r540：**正文里的 query 型二级分类** —— hohoj.tv 首页 nav 只有 3 条（女優/無碼/中文字幕），
        // 真正的内容分类（/main_ctg?id=N&name=…）在正文卡片区，且锚点内带 img/div、长 300+ 字，
        // 非 relaxed 的 collectClasses 根本匹配不到；而 map 已有 3 条，旧逻辑也不会回退整页。
        // 判据（纯形态）：正文 query 分类锚点数明显多于导航 → 用 relaxed 把正文分类补进来。
        // 不认域名、不看具体参数名，只比较「query 分类链接」数量；普通站正文没有这类链接，零影响。
        if (!scope.equals(html) && map.size() < 24) {
            int inNav = countQueryClassLinks(scope);
            int inPage = countQueryClassLinks(html);
            if (inPage > inNav) {
                collectClasses(html, map, true);
                log("正文 query 分类补充 " + inNav + "→" + inPage + " 锚点，classes=" + map.size());
            }
        }
        return map;
    }

    /** r540：统计范围内的 query 型分类锚点数（与 collectClasses 同一套形态判据）。 */
    private static int countQueryClassLinks(String scope) {
        if (TextUtils.isEmpty(scope)) return 0;
        int n = 0;
        Matcher a = Pattern.compile("<a([^>]{0,2000})>([\\s\\S]{0,600}?)</a>", Pattern.DOTALL).matcher(scope);
        while (a.find()) {
            String href = firstMatchGroup(a.group(1), "href=\"([^\"]{2,140})\"");
            if (TextUtils.isEmpty(href) || !href.startsWith("/")) continue;
            String path = href.substring(1);
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String name = clean(a.group(2));
            if (isQueryClassLink(path, name)) n++;
        }
        return n;
    }

    /**
     * 收导航型链接：支持 /dianying/ 这种一级栏目，也支持 /av/theme/2/ 这种「前缀 + 编号」分类。
     * 名称优先取 data-navigation_name / title 属性，其次才是标签文本（A 站的导航文本在 span 里，属性才靠得住）。
     */
    private void collectClasses(String scope, LinkedHashMap<String, String> map) {
        collectClasses(scope, map, false);
    }

    /**
     * r323：relaxed=true 时允许 <a> 与 </a> 之间最多 600 字符。
     * **只给「二级分类页」用** —— 飞卢听书分类页把整张卡片（img+dl+dd）包在 <a> 里，
     * 40 字符匹配不到，12 个真分类全抽不出；
     * 但首页绝对不能放宽：首页的书籍卡片同样是 <a> 包整块，放宽后书名会被当分类抽出
     * （沙箱反例实测：飞卢首页 7 条 → 48 条，全是《我的战舰能升级》这类书名）。
     */
    private void collectClasses(String scope, LinkedHashMap<String, String> map, boolean relaxed) {
        // r300：先归一化绝对地址 —— 旧代码里 `if (href.startsWith("http")) continue;` 会把
        // **用绝对路径写导航的站**整类跳过。真机 meizi5 日志的表现就是「分类稀疏 href样本= 」为空：
        // 它的分类链接全是 https://meizi5.com/tag/meitui、https://meizi5.com/topic/xiaoxinggan，
        // 于是美腿/美胸/美臀/神颜/小性感/小甜美/原创/女老师 8 条一条都没进来。
        scope = normalizeHref(scope);
        // r245：分两轮收 —— 第一轮只要「栏目/专题型」（/av/theme/2/、/dianying/ 这种），
        // 标签型（/tag/xxx/、/av/tag/xxx/）放第二轮，避免像色花堂这种站被十几个 tag 挤满、真分类反而进不来。
        LinkedHashMap<String, String> tagLike = new LinkedHashMap<>();
        // r323：relaxed 时放宽到 600 字符（二级分类页的卡片式布局）；首页仍用 40，防止书名被当分类。
        // r359：导航编号前缀预扫描 —— 同前缀 ≥3 条且末段数字 ≤20 的编号链接组是导航分类
        //（sm455 /list/1~6.html），防止下面 r327 判据把它们当详情页整类丢掉。
        Set<String> navPrefixes = new HashSet<>();
        try {
            Map<String, Integer> navCount = new HashMap<>();
            Matcher pre = Pattern.compile("href=\"(/[^\"]{2,80})\"").matcher(scope);
            while (pre.find()) {
                String hp = pre.group(1);
                int q = hp.indexOf('?');
                if (q >= 0) hp = hp.substring(0, q);
                if (hp.endsWith("/")) hp = hp.substring(0, hp.length() - 1);
                int sl = hp.lastIndexOf('/');
                if (sl <= 0) continue;
                String lc = hp.substring(sl + 1).replaceAll("\\.(html?|shtml|php|aspx?)$", "");
                if (!lc.matches("\\d{1,2}") || Integer.parseInt(lc) > 20) continue;
                navCount.merge(hp.substring(1, sl + 1), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : navCount.entrySet()) {
                if (e.getValue() >= 3) navPrefixes.add(e.getKey());
            }
        } catch (Throwable ignored) {
        }
        Matcher a = Pattern.compile(relaxed
                ? "<a([^>]{0,2000})>([\\s\\S]{0,600}?)</a>"
                : "<a([^>]{0,2000})>(.{0,160}?)</a>", Pattern.DOTALL).matcher(scope);
        while (a.find()) {
            String attrs = a.group(1);

            // r388（AGENTS.md 第十八节）：**首页导航项带图标** —— jysct15 实证：
            // <a href="/vod/show/id/1">电影<span class="iconfont icon-returnLog"></span></a>，inner 49 字符，
            // 旧 40 字符上限让它在正则层就被跳过（分类整类丢失）。放宽只对「有图标迹象」的链接生效，
            // 且排除含块级/媒体元素的卡片 —— 飞卢首页书名卡片含 <img>/<div>，放宽后会抽出书名（r259 教训）。
            String innerHtml = a.group(2);
            if (!relaxed) {
                boolean iconish = innerHtml.contains("<svg") || innerHtml.contains("<i ") || innerHtml.contains("icon");
                if (!iconish && innerHtml.length() > 40) continue;
                if (innerHtml.matches("(?is).*<(img|div|p|h[1-6]|ul|li|article|section|button)\\b.*")) continue;
            }
            String href = firstMatchGroup(attrs, "href=\"([^\"]{2,80})\"");
            if (TextUtils.isEmpty(href) || href.startsWith("http") || !href.startsWith("/")) continue;
            if (href.equals("/")) continue;
            String path = href.substring(1);
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (TextUtils.isEmpty(path) || path.contains("#")) continue;
            // r540：查询参数式详情页不能当分类 —— hohoj 的 /video?id=N 卡片锚点在 relaxed 模式下
            // 也会被扫到，12 个真分类会被前面的视频链接挤满 24 条上限。只挡「详情语义 path + 数字 id」，
            // /ctg?id=N&name=…、/search?type=… 这类分类参数不受影响。
            if (path.matches("(?i)(?:video|detail|play|view|watch|item|post|movie|article|novel|read)\\?[^\\s\"']*[?&](?:id|vid|v|m|n)=\\d+.*")) continue;
            // r262：/filter?mform=1&sort=grade 这类带查询的链接是真分类（高分电影/剧集/动漫），
            // 以前见到 "?" 就整条丢掉，分类只剩一个「最近更新」。
            final String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            if (pathOnly.contains("/static/") || pathOnly.contains("/search") || pathOnly.contains("/user") || pathOnly.contains("/login")) continue;
            // r381：**数据文件后缀不是分类** —— qmtsw.com 实证（A 级，真实首页 HTML）：
            //   首页最早出现的 3 个 href 是 /xml/baidu.xml(百度地图) /xml/google.xml(谷歌地图) /xml/rss.xml(RSS订阅)，
            //   它们排在真实分类 /book/1.html(玄幻) 之前，而 map 有 24 条上限 →
            //   被垃圾占掉 3 个位置，最后 3 条真实分类（穿越/网游/广播）被截掉。
            // 反例验证（5 个旧站真实 HTML 均 0 命中）：ddys/4kcz/meizi5/gaze/missav09 无一含此类链接。
            if (pathOnly.matches(".*\\.(xml|json|txt|csv|rss|atom|sitemap)$")) continue;
            String[] seg = pathOnly.split("/");
            // r370：**含分类词的深路径也算分类** —— WordPress 子目录博客实证（aidjst.cc/888）：
            //   分类是 /888/index.php/category/uncategorized/（4 段），旧规则 seg.length>3 直接拦掉，
            //   真机日志 classes=1 first=all，用户看到「分类只有一条」。
            // 判据：段数 >3 时，仅当路径里出现 category/tag/archives 这类分类词才放行；
            //   文章页 /888/index.php/2026/10/31/xxx（6 段、无分类词）仍被拦。
            boolean catWord = false;
            for (String sg : seg) {
                if (sg.equalsIgnoreCase("category") || sg.equalsIgnoreCase("categories")
                        || sg.equalsIgnoreCase("tag") || sg.equalsIgnoreCase("archives")
                        || sg.equalsIgnoreCase("topic") || sg.equalsIgnoreCase("cat")) { catWord = true; break; }
            }
            if (seg.length > 3 && !catWord) {
                // r388（AGENTS.md 第十八节）：**导航编号分类放行** —— m.jysct15.com 实证（真机日志 + 真实 HTML）：
                // 真分类是 /vod/show/id/1..4,88（电影/电视剧/综艺/动漫/短剧），4 段路径且不含 category/tag 词，
                // 被 r370「段数>3 且无分类词 → 丢」整类拦掉，用户看到「只有推荐页有内容」。
                // navPrefixes 预扫描已收录 /vod/show/id/（同前缀 ≥3 条、末段编号 ≤20）→ 命中即导航分类，放行。
                // 段数 ≤5：/888/index.php/2026/10/31/xxx（7 段文章页）仍被拦。
                String npNav = pathOnly.substring(0, pathOnly.lastIndexOf('/') + 1);
                if (!navPrefixes.contains(npNav) || seg.length > 5) continue;
            }
            // r327 双保险：末段是「单数字」（去扩展名后）的是**详情页**，不是分类。
            // 分类 = category/N/M.html（双数字）；详情 = listen/1021.html（单数字）。
            // 真机飞卢：「更多>」指向的 category/0/1.html 其实是书籍列表页，
            // 里面有 30 个 /listen/N.html —— 以前这些书名会被当成分类。
            // r353：末段是「数字-数字(-数字)」复合 id 的也是详情页 —— nnyy.la 的影片链接
            // vodplay/29086-1-1.html 末段 "29086-1-1" 不是纯数字，旧判定漏掉 → 19 部首页
            // 推荐影片全进了分类（真机 classes=24，真分类只有 5 个）。
            // 零误伤：vodshow/1-----------.html 的 "1-----------" 横线后无数字，不匹配此正则。
            String lastCore = seg.length > 0 ? seg[seg.length - 1].replaceAll("\\.(html?|shtml|php|aspx?)$", "") : "";
            if (lastCore.matches("\\d+(-\\d+)*")) {
                if (seg.length < 2) continue;
                String prevCore = seg[seg.length - 2].replaceAll("\\.(html?|shtml|php|aspx?)$", "");
                if (!prevCore.matches("\\d+")) {
                    // r359：导航编号白名单 —— sm455.vip 的分类是 /list/1.html~6.html（前段 list 非数字、
                    // 末段单数字 ≤20、同前缀 ≥3 条），被 r327「末段单数字=详情页」整类误杀
                    //（真机 03:21 日志 classes=1，href 样本里 /list/1~6.html 全在）。
                    // 飞卢 listen/1021.html 安全：1021 是 4 位数字，预扫描要求 ≤20 两位数，listen 不进白名单。
                    if (!navPrefixes.contains(pathOnly.substring(0, pathOnly.lastIndexOf('/') + 1))) continue;
                }
            }
            boolean numbered = pathOnly.matches(".*/\\d+$");
            if (seg.length == 1) {
                // r337：单段路径长度上限 14 → 24。真机 4kcz 的真分类是单段英文路径：
                //   /zuixindianying(15 最新电影)、/dongmanjuchangban(18 动漫剧场版)、/hanguodianying(15 韩国电影)
                // 全被 14 字符限制砍掉 → 用户看到「分类名字对的但没内容」（分类缺了）。
                // 垃圾项（如 /wangzhanliuyan 网站留言）由 isNonContent 的名字规则处理，不靠长度兜。
                if (numbered || pathOnly.length() > 24) continue;
            }
            // r299：删掉「非编号且 3 段就丢」这条 —— 它把 /topic/xiaoxinggan/yuanchuang、
            // /tag/meitui/list 这类**真实分类**整类误杀（真机 meizi5 日志：导航里明明有
            // 美腿/美胸/美臀/神颜/小性感/小甜美/原创/女老师，结果 classes=1 first=all）。
            // 判详情页应该看「末段是不是数字/长 id」，不是看段数。
            // r362：SKIP_CLASS 条件放行 —— sm455.vip 的分类是 /list/1~6.html，前段 "list"
            // 在词表里，但已被上面的 navPrefixes 判定为导航分类（同前缀≥3 条、末段≤20）。
            // r359 只加了白名单没查这里，白名单等于白加（真机 classes 仍为 1，本行就是断点）。
            String navPfx = pathOnly.substring(0, pathOnly.lastIndexOf('/') + 1);
            // r539：**query 参数式分类链接放行** —— hohoj.tv 实证分类是 `/search?type=censored`、
            // `/ctg?id=101&name=無碼流出`（自研站，非路径式）。首段 search 在 SKIP_CLASS 被整类丢掉
            // → 分类只剩 1 条。判据（纯形态）：带 query 且是分类语义参数（type= / ctg?id=&name=）
            // 且链接文本是短中文标签 → 放行；搜索参数（q/wd/keyword/s）仍按 SKIP 丢弃。
            // name 需先取出来才能判，故这里先算 name。
            String clsName = firstMatchGroup(attrs, "data-navigation_name=\"([^\"]{1,12})\"");
            if (TextUtils.isEmpty(clsName)) clsName = firstMatchGroup(attrs, "aria-label=\"([^\"]{1,12})\"");
            if (TextUtils.isEmpty(clsName)) clsName = firstMatchGroup(attrs, "title=\"([^\"]{1,12})\"");
            if (TextUtils.isEmpty(clsName)) clsName = firstMatchGroup(a.group(2), "<span[^>]*>\\s*([^<]{1,12})</span>");
            if (TextUtils.isEmpty(clsName)) clsName = a.group(2);
            clsName = clean(clsName);
            boolean queryClass = isQueryClassLink(path, clsName);
            if (SKIP_CLASS.contains(seg[0].toLowerCase(Locale.ROOT)) && !navPrefixes.contains(navPfx) && !queryClass) continue;
            // r356：带扩展名的单段路径去扩展名后再查词表 —— ddys.la 页脚 rss.xml 会被
            // collectClasses 当「RSS订阅」分类（多段 rss/baidu.xml 已被上面 seg[0]=rss 拦住，
            // 单段 rss.xml 词表匹配不到漏进来；沙箱 verify 32/32 实证）。
            // 反例：飞卢 Category.aspx 去 .aspx 后是 category，不在词表 → 不拦（二级展开保留）。
            if (seg.length == 1) {
                String core = seg[0].toLowerCase(Locale.ROOT).replaceAll("\\.(html?|shtml|php|aspx?|xml)$", "");
                if (!core.equals(seg[0].toLowerCase(Locale.ROOT)) && SKIP_CLASS.contains(core)) continue;
            }
            String name = firstMatchGroup(attrs, "data-navigation_name=\"([^\"]{1,12})\"");
            if (TextUtils.isEmpty(name)) name = firstMatchGroup(attrs, "aria-label=\"([^\"]{1,12})\"");
            if (TextUtils.isEmpty(name)) name = firstMatchGroup(attrs, "title=\"([^\"]{1,12})\"");
            // r262：很多站的导航文字包在 <span> 里，<a> 直接文本是空的（gaze.red 的精选片单/云资源/公告就是这样），
            // 只取 a.group(2) 会把这些分类整条丢掉，最后只剩 1 条。
            if (TextUtils.isEmpty(name)) name = firstMatchGroup(a.group(2), "<span[^>]*>\\s*([^<]{1,12})</span>");
            if (TextUtils.isEmpty(name)) name = a.group(2);
            name = clean(name);
            if (TextUtils.isEmpty(name) || name.length() > 12) continue;
            if (path.contains("tag") || path.contains("label") || path.contains("pornstar")) {
                tagLike.putIfAbsent(path, name);
                continue;
            }
            map.putIfAbsent(path, name);
            if (map.size() >= 24) return;
        }
        if (map.size() < 6) for (Map.Entry<String, String> e : tagLike.entrySet()) {
            map.putIfAbsent(e.getKey(), e.getValue());
            if (map.size() >= 24) return;
        }
    }

    /**
     * r539：query 参数式链接是否为「真分类」（形态判据，不认域名）。
     * 命中条件：path 带 `?`，参数是分类语义（type=ctg/type=censored、id=N&name=中文、cat=、cid= 等），
     * 且链接文本是短标签（1~12 字，可含中文）；搜索参数（q/wd/keyword/s/query）一律返回 false。
     */
    private static boolean isQueryClassLink(String path, String name) {
        if (TextUtils.isEmpty(path) || path.indexOf('?') < 0) return false;
        if (TextUtils.isEmpty(name) || name.length() > 12) return false;
        String q = path.substring(path.indexOf('?') + 1).toLowerCase(Locale.ROOT);
        // 搜索参数 → 不是分类
        if (q.matches("(?:^|&)(?:q|wd|keyword|keywords|s|query|search|word)=.*")) return false;
        // 分类语义参数：type= / cat= / cid= / ctg= / id=N&name=中文
        if (q.matches("(?:^|&)(?:type|cat|cid|ctg|class|category|main_ctg)=[^&]+")) return true;
        if (q.matches("(?:^|&)id=\\d+&name=[^&]+")) return true;
        return false;
    }

    // ===================== r388：付费墙分类剔除（AGENTS.md 第十八节） =====================

    /**
     * r388：**付费墙/解锁墙页判据**。47o.net 实证（A 级，真实 HTTP）：
     * /comic、/novel、/movie、/meitu/category/* 全部返回 21226~21342 字的「内容解锁」页
     * （title=内容解锁-47O福利社，解锁×5/登录×4/会员×2，0 卡片 0 视频 1 张 logo）。
     *
     * <p>三个条件**全满足**才判墙（沙箱 24 份真实页面：4 个墙页 True、20 个正常页 False）：
     * ① title/h1 出现解锁/付费/会员专享/登录后查看；
     * ② 无任何内容信号（无强视频 / 大图 <3 / media-card 卡片 <2）；
     * ③ 正文「解锁」≥2 次。</p>
     *
     * <p>fail-open：页面为空或短页不判墙，拿不准时保留分类（宁可多留不可误删）。</p>
     */
    private boolean isPaywallPage(String html) {
        if (TextUtils.isEmpty(html) || html.length() < 500) return false;
        String title = firstMatch(html, "<title[^>]*>\\s*([^<]{0,120})</title>");
        String h1 = firstMatch(html, "<h1[^>]*>\\s*([^<]{0,80})</h1>");
        String head = (title == null ? "" : title) + " " + (h1 == null ? "" : h1);
        if (!(head.contains("解锁") || head.contains("付费") || head.contains("会员专享") || head.contains("登录后查看")))
            return false;
        // ① 有内容信号 → 不是墙页（正常文章里出现「解锁」二字极多，靠这一层挡住）
        if (hasStrongVideoSignal(html)) return false;
        if (collectBigImages(html).size() >= 3) return false;
        int cards = 0;
        for (int i = 0; (i = html.indexOf("media-card", i)) >= 0; i += 10) {
            if (++cards >= 2) break;
        }
        if (cards >= 2) return false;
        // ② 正文「解锁」次数
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ").replaceAll("<[^>]+>", " ");
        int cnt = 0;
        for (int i = 0; (i = text.indexOf("解锁", i)) >= 0; i += 2) {
            if (++cnt >= 2) break;
        }
        return cnt >= 2;
    }

    /** r388：分类分组键 —— 两段以上取前两段（meitu/category/），单段返回空串（由调用方单独成组）。 */
    private static String paywallGroupOf(String tid) {
        if (TextUtils.isEmpty(tid)) return "";
        String t = tid.trim();
        int q = t.indexOf('?');
        if (q >= 0) t = t.substring(0, q);
        if (t.startsWith("/")) t = t.substring(1);
        if (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        String[] seg = t.split("/");
        return seg.length >= 2 ? seg[0] + "/" + seg[1] + "/" : "";
    }

    private String paywallCacheKey(String groupOrTid) {
        return (root == null ? "" : root) + "|" + groupOrTid;
    }

    private boolean cachedPaywall(String groupOrTid) {
        Long ts = PAYWALL_GROUPS.get(paywallCacheKey(groupOrTid));
        return ts != null && System.currentTimeMillis() - ts < PAYWALL_TTL;
    }

    /**
     * r388：**付费墙分类分组剔除** —— 修「一类站」而不是单独给 47o 打补丁。
     *
     * <p>背景（A 级）：47o 首页导航 24 条分类里 22 条是 /meitu/category/*（付费墙），
     * 框架把它们全收进分类栏，点开就是「内容解锁」页；还有 /comic、/novel、/movie 三个板块同理。</p>
     *
     * <p>规则：同前缀（前两段）且 ≥3 条的组抽 1 条样本；单段分类 ≤2 条时逐个探。
     * 并发探测、总超时 6s、fail-open；命中后整组剔除并记 30 分钟缓存。
     * 旧站影响：meizi5（tag/topic 各组 2 条）/4kcz（40 个单段 >2 不探）不触发；
     * 分类少于 3 条的站直接 return。</p>
     */
    private void dropPaywalledGroups(LinkedHashMap<String, String> classes) {
        if (classes == null || classes.size() < 3) return;
        Map<String, List<String>> groups = new LinkedHashMap<>();
        List<String> singles = new ArrayList<>();
        for (String tid : new ArrayList<>(classes.keySet())) {
            String g = paywallGroupOf(tid);
            if (g.isEmpty()) singles.add(tid);
            else groups.computeIfAbsent(g, k -> new ArrayList<>()).add(tid);
        }
        int removed = 0;
        // ① 已判定过的单条（缓存来自上一次探测或 categoryContent 墙页兜底）
        for (String tid : new ArrayList<>(singles)) {
            if (!cachedPaywall("#" + tid)) continue;
            if (classes.remove(tid) != null) { removed++; log("分类剔除（缓存）" + tid); }
        }
        // ② 抽样探测：组内均匀取 3 个样本（首/中/尾），**逐条判定**
        // r389 修正 r388 的事故：r388 只看组内 1 个样本就整组删，而 47o 同一组里
        // /meitu/category/cosplay 是墙、/meitu/category/xiurenwang 与 mzsock 各有 42 条真内容（57831 字，解锁=0），
        // 结果 22 条分类被一起删掉，用户直接问「是不是误删了有内容的分类」。
        List<String> probeTid = new ArrayList<>();
        List<String> probeGroup = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            if (cachedPaywall(e.getKey())) {
                for (String tid : e.getValue()) if (classes.remove(tid) != null) removed++;
                log("分类（缓存整组）剔除 group=" + e.getKey() + " → " + classes.size() + " 条");
                continue;
            }
            if (e.getValue().size() < 3) continue;
            int take = Math.min(3, e.getValue().size());
            for (int i = 0; i < take; i++) {
                int idx = take == 1 ? 0 : (int) Math.round((double) i * (e.getValue().size() - 1) / (take - 1));
                probeTid.add(e.getValue().get(idx));
                probeGroup.add(e.getKey());
            }
        }
        if (singles.size() <= 3) {
            for (String s : singles) {
                if (cachedPaywall("#" + s)) continue;
                probeTid.add(s);
                probeGroup.add("#" + s);
            }
        }
        int n = Math.min(8, probeTid.size());
        if (n > 0) {
            final int[] verdict = new int[n]; // 0 未知 / 1 墙 / 2 非影视板块
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int ix = i;
                final String tid = probeTid.get(i);
                Thread t = new Thread(() -> {
                    try {
                        verdict[ix] = probeCategoryVerdict(get(abs(tid)));
                    } catch (Throwable ignored) {
                    }
                });
                t.setDaemon(true);
                ts.add(t);
                t.start();
            }
            long deadline = System.currentTimeMillis() + 8000;
            for (Thread t : ts) {
                try {
                    t.join(Math.max(1, deadline - System.currentTimeMillis()));
                } catch (Throwable ignored) {
                }
            }
            for (int i = 0; i < n; i++) {
                if (verdict[i] == 0) continue;
                String tid = probeTid.get(i);
                if (classes.remove(tid) != null) removed++;
                PAYWALL_GROUPS.put(paywallCacheKey("#" + tid), System.currentTimeMillis());
                if (verdict[i] == 3) log("分类死页剔除 " + tid + "（404/页面不存在）→ " + classes.size() + " 条");
                else if (verdict[i] == 2) log("分类非影视板块剔除 " + tid + "（体育直播/赛程类）→ " + classes.size() + " 条");
                else log("分类付费墙剔除 " + tid + " → " + classes.size() + " 条");
            }
            // ③ 只有「组内 3 个样本全墙」才整组剔除（验证过的强信号）
            for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                String g = e.getKey();
                int total = 0, wall = 0;
                for (int i = 0; i < n; i++) {
                    if (!g.equals(probeGroup.get(i))) continue;
                    total++;
                    if (verdict[i] == 1) wall++;
                }
                if (total >= 3 && wall == total) {
                    for (String tid : e.getValue()) if (classes.remove(tid) != null) removed++;
                    PAYWALL_GROUPS.put(paywallCacheKey(g), System.currentTimeMillis());
                    log("分类付费墙（3/3 全墙）整组剔除 group=" + g + " " + e.getValue().size() + " 条 → " + classes.size() + " 条");
                }
            }
        }
        if (removed > 0) log("分类剔除合计 " + removed + " 条，剩 " + classes.size() + " 条");
    }

    /** r389：分类页四态判定 —— 0 正常 / 1 付费墙 / 2 非影视板块 / 3 死页（分类已失效）。 */
    private int probeCategoryVerdict(String html) {
        // 47o /meitu/category/meitui 实测 404（页面未找到），但页面 24255 字、有导航 —— 不判死页就会留在分类栏里点开必空
        if (isDeadPage(html)) return 3;
        if (isPaywallPage(html)) return 1;
        if (isNonVideoSection(html)) return 2;
        return 0;
    }

    /**
     * r389：**非影视板块**（体育直播/赛程/比分）—— m.jysct15.com 实证：
     * 分类栏里的「全部赛程」实际是 /sports「体育直播」页（NBA 赛事），页内 0 个 /detail 链接，
     * 用户点开只能转圈/空列表（真机日志：抓取完成 134367 字 → 分类渲染兜底第2次渲染失败）。
     * 判据保守：标题/h1 有体育类词 + 无强视频 + 详情链接 <3 且大图 <3。
     */
    private boolean isNonVideoSection(String html) {
        if (TextUtils.isEmpty(html) || html.length() < 2000) return false;
        String title = firstMatch(html, "<title[^>]*>\\s*([^<]{0,120})</title>");
        String h1 = firstMatch(html, "<h1[^>]*>\\s*([^<]{0,80})</h1>");
        String head = (title == null ? "" : title) + " " + (h1 == null ? "" : h1);
        boolean sporty = head.contains("体育") || head.contains("直播") || head.contains("赛程")
                || head.contains("赛事") || head.contains("比分") || head.contains("竞猜");
        if (!sporty) return false;
        if (hasStrongVideoSignal(html)) return false;
        // r389：不再用「大图 <3」作为否决 —— 体育直播页满屏球队徽章（jysct15 /sports 实测 55 张
        // /teams/*.png），旧条件会把 sports 判成"有内容"。标题含体育类词已经够窄（真影视/图集站不会这么写）。
        return countDetailLikeLinks(html) < 3;
    }

    /** r389：页面内「详情类链接」计数（只需前 3 个就能判定有无内容）。 */
    private static int countDetailLikeLinks(String html) {
        int c = 0;
        Matcher m = Pattern.compile("href=\"[^\"]*?/(?:detail|vod|video|movie|play|show)/[^\"]{0,80}?\\d{2,}[^\"]*\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            if (++c >= 3) break;
        }
        return c;
    }

    /**
     * r389（AGENTS.md 第二十节）：**内联 JSON 封面回填** —— 修「所有分类都没有封面预览图」。
     *
     * <p>m.jysct15.com 实证（A 级，真实页面）：卡片封面用的是 Next.js next/image 的 fill 模式，
     * 但 `<img src>` / `srcSet` 指向的是**站点占位 logo**（所有卡片同一张
     * `/upload/site_logo/20260601-1/2f7041….png`），真封面只在 RSC payload 里：</p>
     *
     * <pre>"videoList":{…,"list":[{"vodId":147973,…,"vodPic":"https://…/upload/movie/20261104/vod38581479.webp",…}]}</pre>
     *
     * <p>本方法把 payload 里的 vodId → vodPic 映射回填到还缺封面的条目上（按 vod_id 末尾数字关联）。
     * 只在页面确实含 vodPic 且仍有空封面时才做，其余站零开销。</p>
     */
    private void enrichPicsFromInlineJson(String html, JSONArray list) {
        if (TextUtils.isEmpty(html) || list == null || list.length() == 0) return;
        if (!html.contains("vodPic")) return;
        boolean need = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && TextUtils.isEmpty(o.optString("vod_pic"))) {
                need = true;
                break;
            }
        }
        if (!need) return;
        String plain = html.replace("\\\"", "\"");
        Matcher m = Pattern.compile("\"vodId\"\\s*:\\s*\"?(\\d+)\"?[^}]{0,800}?\"vodPic\"\\s*:\\s*\"([^\"]{8,400})\"",
                Pattern.DOTALL).matcher(plain);
        Map<String, String> pics = new HashMap<>();
        while (m.find()) pics.put(m.group(1), m.group(2));
        if (pics.isEmpty()) return;
        int filled = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || !TextUtils.isEmpty(o.optString("vod_pic"))) continue;
            String id = o.optString("vod_id");
            int q = id.indexOf('?');
            if (q >= 0) id = id.substring(0, q);
            Matcher dm = Pattern.compile("(\\d{2,})$").matcher(id.replaceAll("/+$", ""));
            if (!dm.find()) continue;
            String pic = pics.get(dm.group(1));
            if (TextUtils.isEmpty(pic)) continue;
            try {
                o.put("vod_pic", pic);
                filled++;
            } catch (Throwable ignored) {
            }
        }
        if (filled > 0) log("内联JSON封面回填 " + filled + "/" + list.length() + " 条");
    }

    /**
     * r332：侧边栏/推荐位容器特征 —— 这些容器里的链接**不是列表内容**。
     * 真机 both.folrl0a2w.cc：每页 24 个链接里 10 个是 `search-history__hot-item`（热门推荐），
     * 三页下来用户看到 30 条完全重复的条目。
     * 沙箱用真实 HTML 验证：加上这层排除后，每页 24→14，三页 id 各不相同。
     */
    private static final Pattern SIDEBAR_BLOCK = Pattern.compile(
            "class=\"[^\"]*(hot-item|search-history|recommend|sidebar|aside|rank|hotlist|related|guess)[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    /** r488：导航下拉菜单（dropdown-menu/dropdown-item）—— 里面的链接是导航项不是内容条目。
     *  r489：补 post-near（上一篇/下一篇导航）与 post-near-span —— 吃瓜榜单文章页正文末尾
     *  的「上一篇/下一篇」链接 href 也是 /archives/\d+/，会被 collectPageEpisodes 误当正文选集。 */
    private static final Pattern NAV_DROPDOWN = Pattern.compile(
            "class=\"[^\"]*(?:dropdown-menu|dropdown-item|dropdown-toggle|nav-item|post-near|menu-item|navbar|(?<![\\w-])menu(?![\\w-]))[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    /** push v50（mrds 修法 A）：站方自标广告链接。 */
    private static final Pattern AD_MARKED_LINK = Pattern.compile(
            "rel=[\"'][^\"']*\\bsponsored\\b|data-event=[\"']ad_click[\"']", Pattern.CASE_INSENSITIVE);

    private JSONArray genericItems(String html, int limit) {
        html = normalizeHref(html);
        if (TextUtils.isEmpty(html)) return new JSONArray();
        String pattern = detailPatternOf(html);
        JSONArray arr = extractItemsByPattern(html, pattern, limit);
        // push v49/v33（MissAV 搜索修法 E，只记日志）：抽出条数远少于本页模式命中数时记一行「影片」日志，不改结果、不触发重推。
        if (arr.length() > 0) logSparseExtract(html, pattern, arr.length(), limit);
        if (arr.length() == 0) {
            // r344：pattern 自愈 —— detailPattern 是实例字段，单次请求内多个页面（翻页候选探测、
            // 渲染兜底）共用同一实例时，第一个页面把 pattern「焊死」，后续条目形态不同的页全被抽空
            //（真机 18:11 both/moviesets p2：候选探测先碰到首页定下 /video/\d+/，真实 p2 页的
            //  /moviesets/<slug>/ 全丢，宽松兜底只捡回 1 条）。这里按**本页**重推导一次再抽，
            //  仍空才走宽松兜底。沙箱复刻：p2 自愈 0→18 条；jrcg/首页/合集首屏首轮非空不触发，零影响。
            String fresh = computeDetailPattern(html);
            if (!TextUtils.isEmpty(fresh) && !fresh.equals(pattern)) {
                JSONArray healed = extractItemsByPattern(html, fresh, limit);
                if (healed.length() > 0) {
                    log("genericItems pattern 自愈 " + pattern + " → " + fresh + " → " + healed.length() + " 条");
                    return healed;
                }
            }
        }
        if (arr.length() == 0) {
            // r254：抽空必须留证据（用了哪个模式、页面链接长什么样），否则只能靠猜
            // r465：证据升级 —— 加 len（页长）/ patHits（**不限长**的 pattern 命中数）/ detailCnt（页面里 /detail/ 出现次数），
            //   三者一次就能区分：「pattern 与页面不符」（patHits=0 且 detailCnt=0）vs「页里根本没详情链接」vs「被后续过滤丢弃」（patHits>0）。
            int patHits = 0;
            Matcher ph = Pattern.compile("<a[^>]+href=\"(" + pattern + ")\"[^>]*>").matcher(html);
            while (ph.find()) patHits++;
            int detailCnt = 0;
            Matcher dh = Pattern.compile("/detail/").matcher(html);
            while (dh.find()) detailCnt++;
            log("genericItems 抽空 pattern=" + pattern + " len=" + html.length() + " patHits=" + patHits + " detailCnt=" + detailCnt + " href样本=" + hrefSample(html, 20));
            arr = genericItemsLoose(html, limit);
            if (arr.length() > 0) log("genericItems 宽松兜底命中 " + arr.length() + " 条");
        }
        return arr;
    }

    /** push v49/v33（修法 E）：本页模式命中的不同地址数（按 href 去重，MissAV 每卡 3 个同址锚不重复计）与抽出条数对比；
     *  命中 ≥4 个不同地址、抽出不到一半、且不是被 limit 截住时，记一行「影片」日志（命中数/抽出数/站）。只记日志。 */
    private void logSparseExtract(String html, String pattern, int got, int limit) {
        try {
            if (limit > 0 && got >= limit) return;
            Set<String> hrefs = new HashSet<>();
            Matcher ph = Pattern.compile("<a[^>]+href=\"(" + pattern + ")\"[^>]*>").matcher(html);
            while (ph.find() && hrefs.size() < 1000) hrefs.add(ph.group(1));
            int hits = hrefs.size();
            if (hits >= 4 && got * 2 < hits) {
                DebugLogStore.add(LogCategory.MEDIA, "web", label + " 条目抽出偏少 命中=" + hits + " 抽出=" + got + " 站=" + label + " pattern=" + pattern, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** r344：genericItems 主路径按指定 pattern 抽条目（首抽与自愈重试共用同一段逻辑）。 */
    private JSONArray extractItemsByPattern(String html, String pattern, int limit) {
        JSONArray arr = new JSONArray();
        // r540：**无标题封面卡降级队列** —— 轮播位卡片只有 <a href="/video?id=N"><img></a>，
        // 没有 alt/title；若与真正带片名的卡片混在同一页，旧逻辑按 DOM 顺序先收 24 条轮播，
        // 用户看到的全是数字 id 标题。带真标题的条目优先，真标题不够时才用 id 占位补齐。
        JSONArray untitled = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        // r465：**<a> 标签属性长度上限不再锚死** —— 主正则的 `[^>]{0,300}` 要求标签属性 ≤300 字符，
        //   而真机渲染后的 DOM 属性会膨胀（fp.xiezhen8 真机页 61223/66890 字 vs 沙箱 47498 字），
        //   超限即整条不匹配 → 页面有 15 条详情链接却抽 0 条（与 r460「锚点内部长度上限」同一病根的另一面）。
        //   放宽到 2000：[^>] 不含 `>`，最多匹配到该标签结束，不跨标签，安全；全仓 5 处统一横向。
        Matcher m = Pattern.compile("<a[^>]+href=\"(" + pattern + ")\"[^>]{0,2000}>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (!seen.add(href)) continue;
            // r332：侧边栏/推荐位里的链接不是列表内容
            if (SIDEBAR_BLOCK.matcher(html.substring(Math.max(0, m.start() - 200),
                    Math.min(html.length(), m.end() + 80))).find()) continue;
            // r488：导航下拉菜单项（dropdown-item/dropdown-menu）不是内容条目 —— 吃瓜网「官方活动」下拉里的
            // 「原创招募/百万现金扶持」href 也是 /archives/\d+/，旧逻辑把它们当首页卡片，用户看到每个板块都重复。
            // 这是通用导航特征，不针对站点。
            if (NAV_DROPDOWN.matcher(html.substring(Math.max(0, m.start() - 400),
                    Math.min(html.length(), m.end() + 80))).find()) continue;
            // r246：详情页一定带数字 id。真机日志实证 A 站把 /video/now-month-hot/ 这种分类页当成了影片，
            // 播放必然失败，这里硬性挡掉。
            // r342：数字闸只对「数字模式」生效 —— slug 模式（pattern 不含 \d，如 /moviesets/<slug>/）
            // 的条目本来就没有数字 id，再要求数字会把合集列表 48 条全部丢光（真机「合集只有一两个」真因）。
            if (pattern.contains("\\d") && !href.matches(".*\\d.*")) continue;
            // r248：分类入口（/video/now-month-hot/ 这类）绝不能当影片，否则播放必然失败（真机日志已实证）
            String rel = href.startsWith("/") ? href.substring(1) : href;
            if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (classes.containsKey(rel) || classes.containsKey(rel.toLowerCase(Locale.ROOT))) continue;
            // r412：**窗口必须截在本卡片内**。lu9936 实证：卡片是
            //   <li><a class="thumb" href="/detail/id/265519" data-original="…png"></a>
            //       <h4 class="stui-vodlist__title"><a href="/detail/id/265519">标题</a></h4></li>
            // 旧写法固定 1600 字符窗口，会跨到下一张卡片 —— 缺 title 属性的卡片就把
            // **下一张卡片的 h4 标题**当自己的（真机现象：265519 显示 265518 的片名）。
            // 修法：窗口截到「下一个同 pattern 详情链接」之前，保证不跨卡片。
            int end = Math.min(html.length(), m.end() + 1600);
            Matcher nextAnchor = Pattern.compile("<a[^>]+href=\"(" + pattern + ")\"", Pattern.DOTALL).matcher(html);
            nextAnchor.region(m.end(), html.length());
            // r466：**同 href 的第二个锚 = 同一卡片的另一个入口**（封面锚 + <h2> 内标题锚），不是「下一张卡片」。
            //   fp.xiezhen8 实证（浏览器真实 DOM 复现）：卡片有 2 个同 href 的 <a>，旧写法把 seg 截断到
            //   标题锚之前 → 标题恒空 → 第 1 条被 continue 丢弃；第 2 条又因 seen 去重被丢 → 30 条全丢 = 0 条。
            //   改为跳过同 href，直到真正的下一个**不同** href 锚点；单锚卡片行为完全不变（回归已验）。
            while (nextAnchor.find()) {
                if (!nextAnchor.group(1).equals(href)) { end = Math.min(end, nextAnchor.start()); break; }
            }
            String seg = html.substring(m.start(), end);
            // r271：封面取「离锚点最近的 img」—— gaze.red 的 <img> 在 <a href="play/…"> 之前，
            // 只从 a 往后截会拿到下一张卡片的图，界面就会「片名与封面错位一格」。
            String back = html.substring(Math.max(0, m.start() - 900), m.start());
            String pic = pickImgNearest(back, seg);
            String title = "";
            // r376：**标题错位一格的根因与修法** —— 卡片真实结构（tuiimg 家族实测）：
            //   <li><h2><a href=".../4140/">性感女神…</a></h2><a href=".../4140/"><img …></a><p>…</p></li>
            // 匹配到的第一个 <a> 就在 <h2> 里，而 seg 从 m.start() 开始 —— **卡片自己的 <h2> 在窗口外**，
            // 于是下面的 h 系列 pattern 全部落到**下一张卡片**的 <h2>：真机日志 4140 显示 4139 的标题、
            // 4139 显示 4138 的…，相邻条目看起来「内容重复」。修法：锚点紧跟 <h1-6> 时，
            // 直接取**锚点自身内部的文字**（最精确，不可能跳到邻居卡片）。
            boolean hBefore = back.matches("(?s).*<h[1-6][^>]*>\\s*$");
            if (hBefore) {
                String inner = html.substring(m.end(), Math.min(html.length(), m.end() + 200));
                title = firstMatchGroup(inner, "^\\s*([^<]{1,80})</a>");
            }
            // r255：h1-h6 必须排在 title 与 </a><a> 之前 —— 4kcz 卡片标题在
            // <h3 class="dytit"><a ...>片名</a></h3>，而 </a><a> 兜底会把卡片后面导航里的「国产剧」当成标题
            // （本地用真实卡片 HTML 验证时实际踩到了这个坑）。
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "<h[1-6][^>]*>\\s*<a[^>]*>\\s*([^<]{1,80})</a>");
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "<h[1-6][^>]*>\\s*([^<]{1,80})</h[1-6]>");
            // r257：gaze.red 卡片标题是 <h3 class="B"><span class="V">铁拳教育</span></h3>
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "<h[1-6][^>]*>\\s*<span[^>]*>\\s*([^<]{1,80})</span>");
            // r389（AGENTS.md 第二十节）：**锚点紧跟 <strong>/<b> 的目录卡片标题源** —— 47o.net 实证：
            //   <a class="tag-directory-card" href="/meitu/model/%E4%B8%9D%E7%A4%BE"><strong>丝社</strong><small>320 部内容</small></a>
            // 模特目录卡无 img、无 h，七条旧 pattern 全不命中；而 seg 会跳到页脚友链的 <img alt>，
            // 真机日志把 vod_name 抽成「59福利社」（站点友链名）。这里只认「锚点后紧跟 strong/b」的精确形态，零误伤。
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "^<a[^>]*>\\s*<(?:strong|b)[^>]*>\\s*([^<]{1,40})</(?:strong|b)>");
            // r257：alt 只能排在 h 之后 —— seg 会跨到下一张卡片的 img，先看 alt 会把「下一部片名」当本条标题
            // （本地用 gaze.red 真实卡片验证时实际踩到：铁拳教育 → 家业）
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "<img[^>]+alt=\"([^\"]{1,300})\"");
            if (TextUtils.isEmpty(title)) // r412：title 属性上限 80 -> 150。lu9936 实测：265520 标题 78 字（能取到），
            // 265519 标题 120 字（超 80 取不到）-> 落到 h 系列 -> 取到下一张卡片的标题（错位一格）。
            title = firstMatchGroup(seg, "title=\"([^\"]{1,150})\"");
            // r385（AGENTS.md 第十五节）：**<p class="*title*"> 标题源** —— 18seman/漫画模板卡片实测：
            //   <div class="manga-i-list-item" mid="12">
            //     <a href="/index.php/comic/xxx"><img src="..." class="manga-i-cover"></a>
            //     <p class="manga-i-list-title">水電工日誌</p>     ← 标题在这里，在 <a> 外面
            //   </div>
            // 上面 7 条 pattern 全部不认 <p>，标题恒为空 → `if (isEmpty(title)) continue` 把 12 条全丢。
            // 真机日志铁证：pattern 已正确（\Q/index.php/comic/\E[A-Za-z][A-Za-z0-9_-]*/?）但 genericItems 抽空。
            // 用 \btitle\b 而不是裸 title：subtitle 里也含 title，\b 能精确排除（反向验证见下）。
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "<p[^>]+class=\"[^\"]*\\btitle\\b[^\"]*\"[^>]*>\\s*([^<]{1,80})</p>");
            // r540：**div 卡片标题** —— hohoj 分类页真标题在 <div class="video-item-title mt-1">…</div>，
            // 若 img 没写 alt，只能靠这里；限定 class 含 title，避免把评分/时长等 div 当片名。
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "<div[^>]+class=\"[^\"]*\\btitle\\b[^\"]*\"[^>]*>\\s*([^<]{1,120})</div>");
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "</a>\\s*<a[^>]*>\\s*([^<]{1,80})</a>");
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(m.group(0), "title=\"([^\"]{1,80})\"");
            // r360：document.write 解密站（sm455 型）标题在 <p><script>明文</script> 内（沙箱实证），
            // 七个既有 pattern 全不命中。排最后，常规站优先走常规标题，零影响。
            if (TextUtils.isEmpty(title)) title = scriptTitleOf(seg);
            // r363：分页控件污染修复 —— 上面后几个 pattern（尤其 `</a><a>文字</a>`）会越界取到
            // 页脚分页区。真机日志：sm455 每页末几条的 vod_name 变成「末页/上一页」，
            // 而 vod_id 与 vod_pic 全对（分页区距最后一张卡片仅 341 字，1600 字窗口直接跨过去）。
            // 取到分页词就当作没取到，重新走 scriptTitleOf（sm455 真标题在 <script> 里），
            // 仍为空则丢弃该条，绝不把分页按钮当片名。
            if (isPagerText(clean(title))) {
                title = scriptTitleOf(seg);
            }
            title = clean(title);
            // r538：**纯封面 query 卡片无标题兜底** —— hohoj.tv 首页视频卡是
            // `<a href="/video?id=12564"><img src=...></a>`，无任何标题文本/alt，七条旧 pattern 全空 →
            // 被下面的 continue 丢光 =「没资源」。用详情 id 作占位标题（点进详情页会拿到真标题）。
            // 仅当 href 是 `xxx=<数字>` 查询式且 pattern 也含 \d 时兜底，常规站标题非空零影响。
            // r540：占位条进 untitled 队列而不是 arr —— 同页有真标题卡片时，真片名优先。
            boolean fallbackTitle = false;
            if (TextUtils.isEmpty(title) && pattern.contains("\\d")) {
                Matcher im = Pattern.compile("[?&](?:id|vid|v|m|n)=(\\d+)").matcher(href);
                if (im.find()) {
                    title = im.group(1);
                    fallbackTitle = true;
                }
            }
            if (TextUtils.isEmpty(title)) continue;
            // r346：导航位/广告位不是条目（missav09 分类页顶部 nav：VIP/收藏/直播/短链广告）
            if (isJunkListItem(href, rel, title, pic)) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("vod_id", abs(href));
                o.put("vod_name", title);
                o.put("vod_pic", abs(pic));
                // r343：主路径也要写列表缓存 —— 4kcz 分类靠渲染兜底抽出后（走主路径），
                // 详情页直连仍是 403 挑战页，标题/封面全靠这份缓存补（宽松路径 r257 起就有，主路径漏了）。
                // r540：占位标题也写入缓存，详情页抓不到 og/title 时至少比空强。
                LIST_META.put(normKey(abs(href)), new String[]{title, abs(pic)});
                o.put("vod_remarks", "");
            } catch (Throwable ignore) {
            }
            if (fallbackTitle) untitled.put(o);
            else arr.put(o);
            if (limit > 0 && arr.length() >= limit) break;
        }
        // r540：真标题不够时用无标题封面卡补齐；界面不会再出现「有图但名字是数字」占满首屏。
        if (untitled.length() > 0) {
            try {
                for (int i = 0; i < untitled.length(); i++) {
                    if (limit > 0 && arr.length() >= limit) break;
                    arr.put(untitled.get(i));
                }
            } catch (Throwable ignore) {
            }
        }
        return arr;
    }

    /**
     * r254：宽松兜底 —— 不依赖 detailPattern，凡「带数字的站内链接 + 卡片有图 + 有标题」都算条目。
     * 用于结构不规整 / 详情页地址形态各异的站，避免一个站点结构差异就整类站没内容。
     */
    private JSONArray genericItemsLoose(String html, int limit) {
        html = normalizeHref(html);
        JSONArray arr = new JSONArray();
        if (TextUtils.isEmpty(html)) return arr;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<a[^>]+href=\"(/[^\"'?#\\s]{2,100})\"[^>]{0,2000}>", Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (!href.matches(".*\\d.*")) continue;
            if (!seen.add(href)) continue;
            // r332：侧边栏/推荐位里的链接不是列表内容（与 genericItems 同一判据）
            if (SIDEBAR_BLOCK.matcher(html.substring(Math.max(0, m.start() - 200),
                    Math.min(html.length(), m.end() + 80))).find()) continue;
            String rel = href.startsWith("/") ? href.substring(1) : href;
            if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (classes.containsKey(rel) || classes.containsKey(rel.toLowerCase(Locale.ROOT))) continue;
            String low = rel.toLowerCase(Locale.ROOT);
            if (low.contains("static") || low.contains("search") || low.contains("user") || low.contains("login")
                    || low.contains("comment") || low.contains("gbook") || low.contains("tag/")) continue;
            // r326：**统一用 NON_CONTENT_PATH 排除分类/导航/付费路径**。
            // 真机飞卢：「飞卢听书分类」点进去后返回 12 条，vod_id 全是 /category/N/1.html（分类被当影片）；
            // r325 只改了 detailPatternOf，这条宽松兜底没管，所以用户看到「毫无变化」。
            if (!isDetailPath(href) && NON_CONTENT_PATH.matcher(href).find()) continue;
            int stop = Math.min(html.length(), m.end() + 1800);
            String seg = html.substring(m.start(), stop);
            // r363：宽松路径同样改「取离锦点最近的 img」，不再取窗口内第一张
            String back = html.substring(Math.max(0, m.start() - 900), m.start());
            String pic = pickImgNearest(back, seg);
            if (TextUtils.isEmpty(pic)) continue;
            String title = firstMatchGroup(seg, "<img[^>]+alt=\"([^\"]{2,80})\"");
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "title=\"([^\"]{2,80})\"");
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(seg, "</a>\\s*<a[^>]*>\\s*([^<]{2,80})</a>");
            if (TextUtils.isEmpty(title)) title = firstMatchGroup(m.group(0), "title=\"([^\"]{2,80})\"");
            if (TextUtils.isEmpty(title)) title = scriptTitleOf(seg);   // r360：与主路径共用，sm455 型解密站标题
            // r363：与主路径共用同一道分页词闸（铁律：所有调用点共用同一个函数）
            if (isPagerText(clean(title))) title = scriptTitleOf(seg);
            title = clean(title);
            if (title.length() < 2) continue;
            // r346：与主路径共用同一垃圾判定（铁律：所有调用点共用同一个函数）
            if (isJunkListItem(href, rel, title, pic)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("vod_id", abs(href));
                o.put("vod_name", title);
                o.put("vod_pic", abs(pic));
                LIST_META.put(normKey(abs(href)), new String[]{title, abs(pic)});
                o.put("vod_remarks", "");
                arr.put(o);
            } catch (Throwable ignore) {
            }
            if (limit > 0 && arr.length() >= limit) break;
        }
        return arr;
    }

    /**
     * r245：不从「猜测的 nav 结构」出发，而是从页面自身的链接里统计出详情页模式。
     *
     * <p>做法：取所有以 / 开头且含数字的路径，把数字段归一化成 \d+ 后按出现次数排序，取最高频的一个。
     * 这样 /video/191802/ 和 /video/av/174831/ 都能被认出来，同类换站也不用改代码。
     */
    /**
     * r254：详情页模式不再只认「目录式 /xxx/123/」。
     *
     * <p>大量站点是 /detail/123.html、/voddetail/123-1-1.html（4kcz 真机日志 category 返回 list=[]，
     * 就是这条规则把它整页过滤掉了）。改成：只要 href 路径含数字，就按「非数字段转义 + 数字段 \d+」
     * 生成模式，取出现 ≥3 次的最高频模式。
     */
    /**
     * r255：把 href="https://host/path" 归一化成 href="/path"。
     *
     * <p>4kcz 这类站列表页每个卡片都是绝对地址（https://www.4kcz.com/movie/24087.html），
     * 而模式统计与条目匹配都只认相对路径 → 整页抽 0 条（真机日志 list=[] 就是这个）。
     */
    /**
     * r367：**只归一化同域绝对地址**，外站链接（bit.ly 等广告短链）保持原样。
     * 事故：thisav66 分类页顶部广告位 `<a href="https://bit.ly/4817gnP">成年人抖阴破解版</a>`，
     * 旧实现把任何 https?:// 都转成 /4817gnP → abs() 又补成本站域名 →
     * 外站广告变成了「站内条目」，isJunkListItem 的外域判断永远失效。
     * 同域判定容许 www. 前缀差异；无 root（极罕）时退回旧行为。
     */
    /** r414：站方 bug —— `href="https://https://host/x"`（协议重复）。归一成单协议，否则整条链接失效。 */
    private static String fixDoubleScheme(String html) {
        if (TextUtils.isEmpty(html) || !html.contains("https://https://")) return html;
        return html.replace("https://https://", "https://").replace("http://http://", "http://");
    }

    private String normalizeHref(String html) {
        html = fixDoubleScheme(html);   // r414
        if (TextUtils.isEmpty(html)) return html;
        String host = TextUtils.isEmpty(root) ? "" : hostOf(root).toLowerCase(Locale.ROOT);
        String s = html;
        if (!TextUtils.isEmpty(host)) {
            s = rewriteSameHost(s, "href=\"https?://([^/\"]+)((?:/[^\"'?#\\s]*)?)\"", host);
            // r323：协议相对 URL（//host/path）同样只处理同域（飞卢 //ts.faloo.com/category/1/1.html）
            s = rewriteSameHost(s, "href=\"//([^/\"]+)((?:/[^\"'?#\\s]*)?)\"", host);
        } else {
            s = s.replaceAll("href=\"https?://[^/\"]+((?:/[^\"'?#\\s]*)?)\"", "href=\"$1\"");
            s = s.replaceAll("href=\"//[^/\"]+((?:/[^\"'?#\\s]*)?)\"", "href=\"$1\"");
        }
        // r257：不带前导斜杠的相对链接（gaze.red href="play/<hex>"）补斜杠
        return s.replaceAll("href=\"(?!(?:https?:)?//|/|#|javascript:|mailto:|tel:)([^\"'#?\\s]{2,120})\"", "href=\"/$1\"");
    }

    /** r367：把「同 host」的绝对 href 改写成站内相对路径，外域原样保留。 */
    private static String rewriteSameHost(String html, String regex, String siteHost) {
        Matcher m = Pattern.compile(regex).matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String h = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
            boolean same = h.equals(siteHost) || h.equals("www." + siteHost) || ("www." + h).equals(siteHost);
            m.appendReplacement(sb, Matcher.quoteReplacement(same ? ("href=\"" + m.group(2) + "\"") : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * r325：**非内容路径** —— 这些路径绝不能参与「详情页模式」统计，也不能被当成影片条目。
     * 真机事故（用户反馈「明明是分类点进去却跳到播放页」）：
     *  - 飞卢听书分类页上 category/N/1.html 出现 12 次 → 成为最高频 → detailPattern 被写成
     *    /category/\d+/... → genericItems 把 12 个分类全当影片 → 点进去直接跳播放页；
     *  - both.folrl0a2w.cc 首页 /tag/xxx 出现 411 次，把真正的 /video/\d+（190 次）盖掉。
     * 沙箱用真实 HTML 验证：加上这个过滤后，both 恢复成 /video/\d+，飞卢分类页不再产生模式。
     */
    // r370：**分类词可能在路径任意位置**（WordPress 子目录站 aidjst.cc/888 的
    // /888/index.php/category/uncategorized/ 就是第 3 段才是 category）。
    // 旧写法只认开头 `^/`，导致分类页被算成详情模式（真机 detailPattern 最高频
    // 变成 /\d+/index.php/category/uncategorized/ x10，列表抽取全乱）。
    private static final Pattern NON_CONTENT_PATH = Pattern.compile(
            "(?:^|/)(category|categories|tag|topic|vodtype|list|type|sort|filter|catalog|channel|series|collections?"
                    + "|wap|user|login|logout|register|reg|search|help|kefu|pay|about|privacy|protocol|feed)(/|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * r463：**明确详情段的豁免** —— fp.xiezhen8 实证：详情链接是 /8.php/topic/detail/id/N.html，
     *   而 NON_CONTENT_PATH 含 topic → computeDetailPattern 统计时把**全部详情链接**排除
     *   → 最高频为空 → 落兜底 pattern（/[a-zA-Z0-9_-/]*\d... 字符类不含 `.`）→ 匹配不上 .php/.html
     *   → 分类页 61130 字抽出 0 条（真机日志 genericItems 抽空）。
     *   规则：路径含 `detail` / `play` 独立段时视为明确详情，豁免非内容判定（分类导航 /topic/xxx 无此段，不受影响）。
     */
    private static final Pattern DETAIL_SEGMENT = Pattern.compile("/(?:detail|play)(?:/|\\.|$)", Pattern.CASE_INSENSITIVE);

    /** r463：是否为「明确详情」路径（供 NON_CONTENT_PATH 豁免用）。 */
    private static boolean isDetailPath(String path) {
        return !TextUtils.isEmpty(path) && DETAIL_SEGMENT.matcher(path).find();
    }

    private String detailPatternOf(String html) {
        if (!TextUtils.isEmpty(detailPattern)) return detailPattern;
        String p = computeDetailPattern(html);
        if (!TextUtils.isEmpty(p)) {
            detailPattern = p;   // 只缓存「真推导出来」的模式；推导失败不缓存，下个页面重新推
            rememberTemplate(html, p);   // r406：记住「模板指纹 → 模式」，同族站直接复用
            return p;
        }
        // r406：**同模板聚类** —— 推导失败时按 HTML 指纹查同族站已成功的模式。
        // 只作用于失败路径，成功路径一行未动（零回归）。
        String reuse = recallTemplate(html);
        if (!TextUtils.isEmpty(reuse)) {
            detailPattern = reuse;
            log("模板聚类复用 detailPattern=" + reuse);
            return reuse;
        }
        return "/[a-zA-Z0-9_\\-/]*\\d[a-zA-Z0-9_\\-/]*";
    }

    /**
     * r406：**模板指纹** —— 同一 CMS 的多个域名共用同一套解析。
     *
     * <p>取三样最稳定的结构特征：`meta generator`、主样式表路径、出现频率最高的 3 个 class 名。
     * 同族站（同 CMS 换皮/换域名）这三样几乎完全一致，异族站很难同时撞上。</p>
     */
    private static String templateFp(String html) {
        if (TextUtils.isEmpty(html)) return null;
        String gen = firstMatch(html, "<meta[^>]+name=\"generator\"[^>]+content=\"([^\"]{2,60})\"");
        String css = firstMatch(html, "href=\"(/[^\"]{0,50}?(?:style|main|index|common)[^\"]{0,20}\\.css[^\"]*)\"");
        java.util.Map<String, Integer> cnt = new java.util.HashMap<>();
        Matcher m = Pattern.compile("class=\"([a-zA-Z0-9_\\- ]{2,80})\"").matcher(html);
        int guard = 0;
        while (m.find() && guard++ < 4000) {
            for (String c : m.group(1).split(" ")) if (c.length() > 3) cnt.merge(c, 1, Integer::sum);
        }
        StringBuilder top = new StringBuilder();
        cnt.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                .forEach(e -> top.append(e.getKey()).append(','));
        String raw = (gen == null ? "" : gen) + "|" + (css == null ? "" : css) + "|" + top;
        if (raw.length() < 12) return null;
        return Integer.toHexString(raw.hashCode());
    }

    private void rememberTemplate(String html, String pattern) {
        String fp = templateFp(html);
        if (fp == null || TextUtils.isEmpty(pattern)) return;
        try {
            com.github.catvod.utils.Prefers.getPrefers().edit().putString("tplfp_" + fp, pattern).apply();
        } catch (Throwable ignored) {
        }
    }

    private String recallTemplate(String html) {
        String fp = templateFp(html);
        if (fp == null) return "";
        try {
            return com.github.catvod.utils.Prefers.getPrefers().getString("tplfp_" + fp, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * r344：pattern 纯推导（不读不写实例缓存），自愈/首抽共用。
     * detailPattern 是实例字段，翻页候选探测里第一个页面定下的模式会「焊死」整次请求，
     * 后续条目形态不同的页全被抽空 —— 自愈必须按当前页重算，不能再读缓存。
     */
    private String computeDetailPattern(String html) {
        html = normalizeHref(html);
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        Matcher m = Pattern.compile("href=\"(/[^\"'?#\\s]{2,100})\"").matcher(html);
        while (m.find()) {
            String path = m.group(1);
            if (!isDetailPath(path) && NON_CONTENT_PATH.matcher(path).find()) continue;
            // 统一归一成 <前缀>/index.php/\d+/\d+/\d+/[^/]+/? —— **必须保留前缀**：
            // aidjst.cc/888 的实际路径是 /888/index.php/2026/10/31/<slug>/，
            // 丢掉 /888 就变成 /index.php/... → genericItems 一条也匹配不到（沙箱复刻命中 0）。
            if (path.matches(".*/index\\.php/\\d{4}/\\d{1,2}/\\d{1,2}/[^/]+/?$")) {
                int ip = path.indexOf("/index.php/");
                String prefix = ip > 0 ? path.substring(0, ip) : "";
                String wpPat = prefix + "/index\\.php/\\d+/\\d+/\\d+/[^/]+/?";
                Integer wc = counts.get(wpPat);
                counts.put(wpPat, wc == null ? 1 : wc + 1);
                continue;
            }
            // r370：URL 编码路径不参与模式统计 —— aidjst.cc/888 的文章链接是
            // /888/index.php/2026/10/31/%e6%96%97%e7%a0%b4...-ai%e7%bb%98/，
            // 编码里的 %e6/%96 也会被 \d+ 替换，导致每篇文章一个“特异模式”，永远凑不齐频次，
            // 最终只能落到兑底 pattern（真机 genericItems 抽空）。
            if (path.contains("%")) continue;
            String pat = numericPattern(path);
            if (pat == null) continue;
            Integer c = counts.get(pat);
            counts.put(pat, c == null ? 1 : c + 1);
        }
        // r538：**查询参数式详情链接** —— hohoj.tv 实证详情页是 /video?id=12564（自研站，非 macCMS）。
        // 上面的 href 正则在 `?` 处截断，只拿到 /video → 推不出模式 → genericItems 抽 0 条 =「没资源」。
        // 这里单独统计 `/xxx?id=<数字>&...` 形态，归一成 \Q/xxx?id=\E\d+（尾部其它参数忽略，避免每个不同参数生成特异模式）。
        // 分类/筛选语义的 query path（hohoj /ctg?id=562 次是分类标签，绝不能压过 /video?id=222 详情）
        Pattern queryNonContent = Pattern.compile(
                "(?i)/(?:ctg|main_ctg|categories|category|tag|topic|list|type|sort|filter|catalog|channel|series|collections?|search|user|member)(?:\\?|$)");
        // 详情语义的 query path —— 加权 10 倍，确保同页分类标签再多也压不过内容入口
        Pattern queryDetail = Pattern.compile("(?i)/(?:video|detail|play|view|watch|item|post|movie|article|novel|read)(?:\\?|$)");
        // push v44：参数值放宽到「字母数字 id」（P站 /view_video.php?viewkey=6977fd7474886 实证：旧版只认纯数字值，
        //   这类十六进制 id 要么不收、要么按 \d+ 收了却抽不中）；同前缀里只要出现过带字母的值，模式值段用 [A-Za-z0-9_-]+，
        //   全是数字的照旧 \d+（hohoj /video?id=N 等老站行为不变）。值里至少要有一位数字（排除 ?lang=en 这类）。
        //   参数名是分类/排序/翻页语义（c、cat、tag、type、sort、page…）的不是详情：P站搜索页 /video?c=732 分类链接
        //   旧版按 video 路径加权 10 倍压过影片卡。参数名是 id/viewkey/vid 这类详情语义的，同样加权 10 倍；静态资源（.css/.js/图片?v=版本号）不计。
        Pattern queryNonContentKey = Pattern.compile("(?i)^(?:c|cat|cate|catid|category|tag|tags|type|sort|order|o|page|p|lang|filter)$");
        Pattern queryDetailKey = Pattern.compile("(?i)^(?:id|vid|viewkey|vkey|aid)$");
        LinkedHashMap<String, int[]> qcounts = new LinkedHashMap<>();
        Matcher qm = Pattern.compile("href=\"(/[a-zA-Z0-9_./-]{1,60}\\?([a-zA-Z_][a-zA-Z0-9_]*)=([A-Za-z0-9_-]{1,40})[^\"'\\s]*)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        while (qm.find()) {
            String q = qm.group(1);
            String key = qm.group(2);
            String val = qm.group(3);
            if (!val.matches(".*\\d.*")) continue;
            // 静态资源带版本号（/xx.css?v=2026112001、.js?v=）不是链接条目：参数名详情加权后会被它们抢走模式（吃瓜搜索页实证）
            if (q.substring(0, q.indexOf('?')).toLowerCase(Locale.ROOT).matches(".*\\.(?:css|js|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|json|xml)$")) continue;
            if (queryNonContent.matcher(q).find()) continue;
            if (queryNonContentKey.matcher(key).matches()) continue;
            int eq = q.indexOf('=');
            // 只保留 `?key=` + 值，丢掉 & 后的追踪参数（否则每个不同参数生成一个特异模式）
            String prefix = q.substring(0, eq + 1);
            int weight = (queryDetail.matcher(q).find() || queryDetailKey.matcher(key).matches()) ? 10 : 1;
            int[] qc = qcounts.get(prefix);
            if (qc == null) { qc = new int[2]; qcounts.put(prefix, qc); }
            qc[0] += weight;
            if (!val.matches("\\d+")) qc[1] = 1;
        }
        for (Map.Entry<String, int[]> e : qcounts.entrySet()) {
            String pat = Pattern.quote(e.getKey()) + (e.getValue()[1] == 1 ? "[A-Za-z0-9_-]+" : "\\d+");
            Integer c = counts.get(pat);
            counts.put(pat, (c == null ? 0 : c) + e.getValue()[0]);
        }
        // r342：slug 模式统计 —— /moviesets/AiCollection/ 这类「无数字详情页」（合集/专辑站）。
        // 真机 both 站「合集」抽空真因：主列表 48 条全是无数字 slug，numericPattern 全返回 null 被丢弃，
        // 最高频被侧栏 /video/\d+（10 次）抢走 → 列表抽的是侧栏推荐而不是合集。
        // 规则（沙箱真实页面复刻验证）：两段路径 /前缀/纯字母slug/，前缀不在导航黑名单。
        LinkedHashMap<String, Integer> slugs = new LinkedHashMap<>();
        // r384（AGENTS.md 第十五节）：**slug 模式支持多段路径** —— 旧正则固定「两段」且首段不含点，
        // 所以 /index.php/comic/{拼音slug} 这种「三段且首段带 .php」的详情链接一条也匹配不到。
        // 真机日志（18seman）：genericItems 抽空 pattern=/[a-zA-Z0-9_\-/]*\d[a-zA-Z0-9_\-/]*，
        // 谷底 pattern 要求含数字，而全站详情页是纯拼音 slug → 抽 0 条，连书都显示不出来。
        // 新正则：首段允许带点（.php），中间可有任意段，末段为纯字母 slug。
        Matcher sm = Pattern.compile("href=\"(/[a-z][a-z0-9_.-]{1,20}(?:/[a-z][a-z0-9_-]{1,20})*/[a-z][a-z0-9_-]{2,60}(?:\\.html?)?/?)\"").matcher(html);
        while (sm.find()) {
            String p = sm.group(1);
            if (NON_CONTENT_PATH.matcher(p).find()) continue;
            // r384：前缀截取改用 lastIndexOf（旧写法 indexOf('/',1) 只能处理两段）。
            // 先去掉尾斜杠再找最后一段，避免 /a/b/ 截成 /a/b/ 而 rest 为空。
            // r343：slug 模式补尾斜杠可选 + 字母开头 —— 沙箱实测：/moviesets/<slug>/ 类尾斜杠条目
            // 旧正则缺尾斜杠整页抽空（真机 r342 实证）；字母开头与统计正则 [a-z][…] 对齐，
            // 顺带排除 /moviesets/2/ 这类纯数字翻页链接（沙箱 ms_r343.html：24 条、污染 0）。
            // r356：slug 模式补 .html/.htm 后缀 —— ddys.la（dd 模板）详情页是 /video/拼音slug.html，
            // 旧正则只收无后缀形态整页抽空（沙箱实证：旧 max=6 条垃圾数字 → 新 slug=88 条真条目）。
            // 后缀从样本末段第一个 '.' 起取（xxx.html → .html），pattern 用 quote 保证字面安全。
            // r384：去尾斜杠后用 lastIndexOf 定位最后一段的起点
            String p2 = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            int cut = p2.lastIndexOf('/') + 1;
            if (cut <= 0 || cut >= p2.length()) continue;
            String rest = p2.substring(cut);
            int dot = rest.indexOf('.');
            String suffix = dot >= 0 ? rest.substring(dot) : "";
            String pat = Pattern.quote(p2.substring(0, cut)) + "[A-Za-z][A-Za-z0-9_-]*"
                    + (suffix.isEmpty() ? "/?" : Pattern.quote(suffix));
            Integer c = slugs.get(pat);
            slugs.put(pat, c == null ? 1 : c + 1);
        }
        // r388（AGENTS.md 第十八节）：**中文 slug 兜底模式** —— 47o.net 实证：
        // 条目是 /meitu/model/%E4%B8%9D%E7%A4%BE（URL 编码的中文模特名，120 条）。
        // 上面两套模式都认不了：
        //   ① numericPattern：中文段不含 \d → 返回 null
        //   ② slug 正则：[a-z] 不匹配中文 → 0 命中
        // 加上 r370 的 `if (path.contains("%")) continue` 提前跳过 → 整页抽 0 条，
        // 只剩 /movie/category/xxx 这类导航链接被当成条目（真机：total=2、名字叫「内容解锁」）。
        // 新规则：末段含 % → 取「前缀 + [^"/]+」；只在两套旧模式都不达标且本模式显著更强时才启用。
        LinkedHashMap<String, Integer> cnSlugs = new LinkedHashMap<>();
        Matcher cm = Pattern.compile("href=\"(/[^\"'?#\\s]{2,120})\"").matcher(html);
        while (cm.find()) {
            String p = cm.group(1);
            if (NON_CONTENT_PATH.matcher(p).find()) continue;
            String p2 = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            int sl = p2.lastIndexOf('/');
            if (sl <= 0 || sl >= p2.length() - 1) continue;
            String lastSeg = p2.substring(sl + 1);
            if (!lastSeg.contains("%")) continue;      // 只处理「末段被 URL 编码」的形态
            String pat = Pattern.quote(p2.substring(0, sl + 1)) + "[^\"/]+";
            Integer c = cnSlugs.get(pat);
            cnSlugs.put(pat, c == null ? 1 : c + 1);
        }
        String bestCn = "";
        int maxCn = 0;
        for (Map.Entry<String, Integer> e : cnSlugs.entrySet()) {
            if (e.getValue() > maxCn) {
                maxCn = e.getValue();
                bestCn = e.getKey();
            }
        }
        String best = "";
        int max = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                best = e.getKey();
            }
        }
        String bestSlug = "";
        int maxSlug = 0;
        for (Map.Entry<String, Integer> e : slugs.entrySet()) {
            if (e.getValue() > maxSlug) {
                maxSlug = e.getValue();
                bestSlug = e.getKey();
            }
        }
        // r325：阈值 3 → 5。过滤后可能只剩「/wap 3 次」这类噪音，不足以代表详情页；
        // 沙箱实测：飞卢首页 77、meizi5 32、both 190 都远超 5，不影响正常站。
        String out = "";
        // r388（AGENTS.md 第十八节）：**中文 slug 模式优先判断**。
        // 启用条件（20 份夹具全量验证）：maxCn >= 5 且显著强于两套旧模式（各 >1.5 倍）。
        // 实测只有 47o_models.html 命中（数字 2 / slug 21 / 中文slug 115），其余 19 份全 0 → 零误伤。
        // FIX-B：**番号形末段同目录合并** —— MissAV 系影片地址 /[dmN/][cn/]<字母>-<数字>[-后缀]，
        //   numericPattern 保留字母字面量 → 每个番号前缀/后缀各成一个模式（ssis- / ssis-…-uncensored-leak / fc2-ppv- …），
        //   只抽中最高频那一小撮（12 张出 1~6 张）；中文 slug（女优头像卡）还会反超。按「同一目录 + 末段是 字母开头、含连字符、含数字」
        //   合并计数，显著强于单一模式（>1.5 倍）且不同地址 ≥4 个才启用。结构认，不认站名。
        LinkedHashMap<String, Integer> codeCnt = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> codeDistinct = new LinkedHashMap<>();
        Matcher km = Pattern.compile("href=\"(/[^\"'?#\\s]{2,100})\"").matcher(html);
        while (km.find()) {
            String path = km.group(1);
            if (path.contains("%") || NON_CONTENT_PATH.matcher(path).find()) continue;
            String p2 = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int sl = p2.lastIndexOf('/');
            if (sl < 0) continue;
            String last = p2.substring(sl + 1);
            if (!last.matches("[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9_-]*\\d[A-Za-z0-9_-]*")) continue;
            String dir = p2.substring(0, sl + 1);
            String dp = numericPattern(dir);
            if (dp == null) dp = Pattern.quote(dir);
            String key = dp + "[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9_-]*\\d[A-Za-z0-9_-]*/?";
            Integer c = codeCnt.get(key);
            codeCnt.put(key, c == null ? 1 : c + 1);
            codeDistinct.computeIfAbsent(key, k -> new HashSet<>()).add(p2);
        }
        String bestCode = "";
        int maxCode = 0;
        for (Map.Entry<String, Integer> e : codeCnt.entrySet()) {
            if (e.getValue() > maxCode && codeDistinct.get(e.getKey()).size() >= 4) {
                maxCode = e.getValue();
                bestCode = e.getKey();
            }
        }
        if (maxCode >= 5 && maxCode * 2 > max * 3 && maxCode * 2 > maxCn * 3 && maxCode * 2 > maxSlug * 3) {
            log("detailPattern 番号形同目录合并 合并=" + maxCode + " 数字=" + max + " slug=" + maxSlug + " 中文=" + maxCn + " → " + bestCode);
            return bestCode;
        }
        if (maxCn >= 5 && maxCn * 3 / 2 > max && maxCn * 3 / 2 > maxSlug) {
            log("detailPattern 中文slug 模式 数字=" + max + " slug=" + maxSlug + " 中文=" + maxCn);
            return bestCn;
        }
        if (max >= 5) out = best;
        // r342：slug 模式仅在「显著强于数字模式」（>1.5 倍）或数字模式不达标时启用。
        // 沙箱复刻：合集列表页 slug 48 > 数字 10×1.5 → slug ✓；黑料列表页 slug 28 < 数字 24×1.5=36 → 维持数字 ✓；
        // 合集详情页 slug 14 < 数字 82×1.5 → 维持数字 ✓；both 首页 slug 最多 3 → 不受影响 ✓。
        if (max >= 5 && max * 3 / 2 >= maxSlug) {
            // 数字模式足够强，维持上面已赋的值
        } else if (maxSlug >= 5) {
            out = bestSlug;
        } else if (max >= 5) {
            out = best;
        }
        return TextUtils.isEmpty(out) ? "" : out;
    }

    /** r254：/detail/123.html → \Q/detail/\E\d+\Q.html\E（非数字段整段转义，避免 . 被当通配）。 */
    private static String numericPattern(String path) {
        StringBuilder sb = new StringBuilder();
        // r257：不能只按「连续数字」切 —— gaze.red 的 id 是 32 位 hex（play/6be8f9058d86...），
        // 逐段切数字会得到每条都不一样的模式，统计永远失败。
        // 规则：纯数字 token → \d+；含字母的字母数字 token（含数字）→ [A-Za-z0-9]+。
        Matcher m = Pattern.compile("[A-Za-z0-9_]{2,}").matcher(path);
        int last = 0;
        boolean any = false;
        while (m.find()) {
            String tok = m.group();
            if (!tok.matches(".*\\d.*")) continue;
            boolean alnum = tok.matches(".*[A-Za-z].*");
            sb.append(Pattern.quote(path.substring(last, m.start()))).append(alnum ? "[A-Za-z0-9]+" : "\\d+");
            last = m.end();
            any = true;
        }
        if (!any) return null;
        sb.append(Pattern.quote(path.substring(last)));
        return sb.toString();
    }

    // ------------------------------------------------------------------ r342 新增

    /**
     * r342：解 eval(function(p,a,c,k,e,d){…}) packer 混淆。
     * 关键坑（沙箱踩过）：keys 第一项为空串时表示「该 token 不替换、保持字面量」——
     * 黑料 melon js 里 uid=0、v=3、time=0 全是字面量 0/3，不是 token。把空 token 也建映射
     * 会把 0/3 替换成空串，m3u8 参数缺角 → CDN 403（沙箱实测：错版 403、正版 200）。
     */
    static String unpackPacker(String js) {
        try {
            if (js == null) return "";
            Matcher m = Pattern.compile("\\('([\\s\\S]*?)',(\\d+),(\\d+),'([\\s\\S]*?)'\\.split\\('\\|'\\)").matcher(js);
            if (!m.find()) return "";
            String payload = m.group(1);
            String[] keys = m.group(4).split("\\|", -1);
            java.util.HashMap<String, String> dict = new java.util.HashMap<>();
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].isEmpty()) continue;          // 空 token：字面量保留
                String id = Integer.toString(i, 36);
                if (id.length() <= 2) dict.put(id, keys[i]);
            }
            StringBuilder out = new StringBuilder(payload.length() * 2);
            Matcher w = Pattern.compile("\\b[a-z0-9]+\\b").matcher(payload);
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

    /** r342：JS encodeURIComponent 的最小等价（melon js 的 u 参数是 base64 变体，只需编码 + / = 与非 ASCII）。 */
    private static String jsEncode(String s) {
        if (TextUtils.isEmpty(s)) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*' || c == '\'' || c == '(' || c == ')') {
                sb.append(c);
            } else {
                try {
                    for (byte b : String.valueOf(c).getBytes("UTF-8")) sb.append(String.format("%%%02X", b));
                } catch (Throwable e) {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * r353：RC4 密文字典站（nunuyy 模板，nnyy.la）纯 HTTP 直解。
     * 播放地址不在 HTML 里（明文 m3u8/mp4 一条都没有），而是 urlDictionary[sid][nid]
     * 的 RC4 密文，key 硬编码在页内 rc4Decrypt 函数（nnyy.la 是 "i_love_you"，
     * 必须动态提取，不能写死 —— 换站即换 key）。
     * 沙箱已验证：按 URL 的 sid/nid 取密文 → RC4 解密 → 4/4 条线路解出 m3u8；
     * 换线路 URL 返回的字典与原页一致（全量输出），任意该影片 URL 都能解任意 (sid,nid)。
     */
    private String rc4Play(String page, String html) {
        try {
            if (html == null || !html.contains("urlDictionary") || !html.contains("rc4Decrypt")) return "";
            String key = firstMatch(html, "function\\s+rc4Decrypt[\\s\\S]{0,600}?key\\s*=\\s*[\"']([^\"']{2,64})[\"']");
            if (TextUtils.isEmpty(key)) return "";
            String sid = "", nid = "";
            Matcher pm = Pattern.compile("/vodplay/\\d+-(\\d+)-(\\d+)\\.html").matcher(page);
            if (pm.find()) {
                sid = pm.group(1);
                nid = pm.group(2);
            } else {
                // URL 不带 sid/nid（maccms 正常不会）→ 取字典第一组
                Matcher fm = Pattern.compile("urlDictionary\\s*\\[\\s*(\\d+)\\s*\\]\\s*\\[\\s*(\\d+)\\s*\\]").matcher(html);
                if (fm.find()) {
                    sid = fm.group(1);
                    nid = fm.group(2);
                }
            }
            if (TextUtils.isEmpty(sid) || TextUtils.isEmpty(nid)) return "";
            String hexs = firstMatch(html, "urlDictionary\\s*\\[\\s*" + sid + "\\s*\\]\\s*\\[\\s*" + nid + "\\s*\\]\\s*=\\s*[\"']([0-9a-fA-F]{16,})[\"']");
            if (TextUtils.isEmpty(hexs)) return "";
            byte[] data = new byte[hexs.length() / 2];
            for (int i = 0; i < data.length; i++) data[i] = (byte) Integer.parseInt(hexs.substring(i * 2, i * 2 + 2), 16);
            String url = rc4(key, data);
            return url.startsWith("http") ? url : "";
        } catch (Exception e) {
            log("rc4 直解异常 " + e);
            return "";
        }
    }

    /** r356：playerconfig.js 的 from → parse 前缀表，按 host 缓存（进程存活期复用，不重复抓配置）。 */
    private static final Map<String, Map<String, String>> DD_PARSE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * r356：dd 模板 player_aaaa 密文站（ddys.la）站内自建解析器直解。
     * 链路（沙箱 verify 32/32 全 PASS，真实页面逐环节对照）：
     * 播放页 var player_aaaa={"url":"密文","from":"zy1080_01",…}（无明文地址）
     *   → 播放页外链 playerconfig.js 的 from → "parse" 前缀（zy1080_01 → /ddcloud/index.php?url=）
     *   → GET root+parse+密文 → 响应 JSON.parse('{"url":"https://…m3u8…"}')
     *   → 提取 + 还原 \/ 转义 → m3u8 直链（实测纯 HTTP 可拉，无需 WebView）。
     * player_aaaa 里没有 parse 字段，前缀必须查 playerconfig.js；外链路径从播放页
     * script src 提取（不写死 /static/js/，换模板路径即失效）。
     */
    private String ddPlay(String page, String html) {
        try {
            // r413：兼容三种变量名 —— maccms 家族并不统一：
            //   player_aaaa（ddys 等）、player_data（aisee9w11 等）、player_config。
            // 值结构一致（flag/encrypt/url/from/link_next…），只是名字不同。
            boolean hasVar = html != null && (html.contains("player_aaaa") || html.contains("player_data") || html.contains("player_config"));
            if (html == null || !hasVar) return "";
            Matcher pm = Pattern.compile("var (?:player_aaaa|player_data|player_config)\\s*=\\s*(\\{.*?\\})\\s*</script>", Pattern.DOTALL).matcher(html);
            if (!pm.find()) { log("dd 直解跳过：player_aaaa 提取失败"); return ""; }
            JSONObject pa;
            try { pa = new JSONObject(pm.group(1)); } catch (Exception e) { log("dd 直解跳过：player_aaaa 非合法 JSON"); return ""; }
            String enc = pa.optString("url", "");
            String from = pa.optString("from", "");
            if (TextUtils.isEmpty(enc) || enc.startsWith("http") || TextUtils.isEmpty(from)) return "";
            String cfgUrl = firstMatch(html, "<script[^>]+src=\"([^\"]*playerconfig[^\"]*)\"");
            if (TextUtils.isEmpty(cfgUrl)) { log("dd 直解跳过：播放页无 playerconfig 外链"); return ""; }
            String prefix = ddParsePrefix(abs(cfgUrl), from);
            if (TextUtils.isEmpty(prefix)) { log("dd 直解跳过：from=" + from + " 无 parse 配置"); return ""; }
            String res = get(abs(prefix) + URLEncoder.encode(enc, "UTF-8"));
            String u = firstMatch(res, "JSON\\.parse\\('\\{\"url\":\"(https?:[^\"']+?)\"");
            if (TextUtils.isEmpty(u)) { log("dd 直解失败：ddcloud 响应无 url（len=" + (res == null ? -1 : res.length()) + "）"); return ""; }
            u = u.replace("\\/", "/");
            log("dd 直解成功 " + u.substring(0, Math.min(60, u.length())));
            return u;
        } catch (Exception e) {
            log("dd 直解异常 " + e);
            return "";
        }
    }

    /** r356：playerconfig.js 的 from → parse 前缀查表（按 host 缓存；值内 \/ 转义还原）。 */
    private String ddParsePrefix(String cfgUrl, String from) {
        try {
            String host = hostOf(cfgUrl).toLowerCase(Locale.ROOT);
            Map<String, String> table = DD_PARSE_CACHE.get(host);
            if (table == null) {
                String js = get(cfgUrl);
                table = new LinkedHashMap<>();
                if (!TextUtils.isEmpty(js)) {
                    Matcher m = Pattern.compile("\"([\\w-]+)\"\\s*:\\s*\\{[^{}]*?\"parse\"\\s*:\\s*\"([^\"]*)\"").matcher(js);
                    while (m.find()) if (!TextUtils.isEmpty(m.group(2))) table.put(m.group(1), m.group(2).replace("\\/", "/"));
                }
                if (!table.isEmpty()) DD_PARSE_CACHE.put(host, table);
            }
            return table == null ? "" : table.getOrDefault(from, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 标准 RC4（KSA+PRGA），与页面 rc4Decrypt 逐字节一致（沙箱 Python 复刻验证 4/4 线路）。 */
    private static String rc4(String key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key.charAt(i % key.length())) % 256;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        int i = 0;
        j = 0;
        byte[] out = new byte[data.length];
        for (int k = 0; k < data.length; k++) {
            i = (i + 1) % 256;
            j = (j + s[i]) % 256;
            int t = s[i]; s[i] = s[j]; s[j] = t;
            out[k] = (byte) (data[k] ^ s[(s[i] + s[j]) % 256]);
        }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * r342：melon 动态播放器纯 HTTP 直解（both 站黑料）。
     * 链路（沙箱全流程验证）：详情页 .ql-video 内 packer → decode 出
     * document.write('<script src="/index/melon_detail_play.js?img=&u="+encodeURIComponent("密文")+"&t="+parseInt(now/1000/1800)>')
     * → 请求该 js（带 UA+Referer）→ 响应又是 packer → decode 出 data-url="…m3u8?auth_key=…"。
     */
    private String melonPlay(String page, String html) {
        try {
            // r343：6 个静默 return 全部加诊断 —— 真机 player 阶段全程仅 459ms、无「melon 直解」日志，
            // 现有日志无法区分「哪一步早退」，下轮真机日志必能定位。
            if (html == null || !html.contains("ql-video")) { log("melon 跳过：无 ql-video（len=" + (html == null ? -1 : html.length()) + "）"); return ""; }
            int q = html.indexOf("ql-video");
            String win = html.substring(q, Math.min(html.length(), q + 8000));
            int e = win.indexOf("eval(function(p,a,c,k,e,d)");
            if (e < 0) { log("melon 跳过：ql-video 8000 字窗内无 packer"); return ""; }
            String out1 = unpackPacker(win.substring(e));
            if (TextUtils.isEmpty(out1) || !out1.contains("melon_detail_play.js")) { log("melon 跳过：一层解包无 melon js（out1=" + (out1 == null ? -1 : out1.length()) + " 字）"); return ""; }
            String uEnc = firstMatchGroup(out1, "encodeURIComponent\\(\"([^\"]+)\"\\)");
            if (TextUtils.isEmpty(uEnc)) { log("melon 跳过：一层解包无 u 密文"); return ""; }
            String jsPath = firstMatchGroup(out1, "([^\"]*melon_detail_play\\.js)");
            if (TextUtils.isEmpty(jsPath)) jsPath = "/index/melon_detail_play.js";
            // t 窗口数：页面 JS 是 parseInt((new Date).getTime()/1000/1800)（沙箱实测 t=996488 与浏览器一致）
            int t = (int) (System.currentTimeMillis() / 1000 / 1800);
            String jsUrl = schemeHost(page) + jsPath + "?img=&u=" + jsEncode(uEnc) + "&t=" + t;
            // r343：内层请求改走 noRedirect(15000) 专用调用（与 get() 同客户端，不跟 probeClient 的 4.5s callTimeout），
            // 手跟 2 跳 3xx；沙箱实测该 js 直返 200（601 字节），不改行为只换通道 + 加状态日志。
            String js2 = "";
            String cur = jsUrl;
            for (int i = 0; i < 3; i++) {
                try (Response res = OkHttp.noRedirect(15000).newCall(new okhttp3.Request.Builder()
                        .url(cur)
                        .header("User-Agent", UA)
                        .header("Accept", "*/*")
                        .header("Referer", page)
                        .build()).execute()) {
                    int code = res.code();
                    if (code >= 300 && code < 400) {
                        String loc = res.header("Location");
                        if (TextUtils.isEmpty(loc)) break;
                        cur = abs(loc);
                        continue;
                    }
                    okhttp3.ResponseBody body = res.body();
                    if (body != null) js2 = body.string();
                    log("melon js 请求 HTTP " + code + " " + (js2 == null ? -1 : js2.length()) + " 字");
                    break;
                }
            }
            if (TextUtils.isEmpty(js2) || !js2.contains("eval(function(p,a,c,k,e,d)")) { log("melon 跳过：js 拉取失败或无二层 packer"); return ""; }
            String out2 = unpackPacker(js2);
            if (TextUtils.isEmpty(out2)) { log("melon 跳过：二层解包空"); return ""; }
            String m3u8 = firstMatchGroup(out2, "data-url=\"([^\"]+)\"");
            if (TextUtils.isEmpty(m3u8) || !m3u8.contains(".m3u8")) { log("melon 跳过：二层无 data-url m3u8（out2=" + out2.length() + " 字）"); return ""; }
            log("melon 两层 packer 解码成功 " + m3u8.length() + " 字");
            return m3u8;
        } catch (Throwable t) {
            log("melon 直解异常 " + t);
            return "";
        }
    }

    /**
     * r342：合集/专辑详情页的选集抽取 —— 页面主体是同模式高频链接列表（/video/\d+/ ×82）。
     * 用 detailPatternOf 的模式匹配页面全部链接，去重保序返回绝对地址。
     */
    /**
     * r344：合集/专辑型页判定（所有调用点共用这一个函数 —— r317/r318 教训：判据不许两处各写一套）。三重门：
     * ① 页面有播放容器（ql-video/player-container/packer）→ null。那是播放页，选集语义是「它自己」，
     *   侧栏链接不能当它的集（黑料详情页铁律；沙箱 r344_video.html 实测视频页 29 条 /video/\d+/ 全靠这道门挡住）；
     * ② r345 门序重排：非侧栏数字模式链接 ≥6 条 → 选集列表（{题名, 绝对URL}），立判合集 —— 页面大图
     *   是条目封面不是图集正文，不能拿 collectBigImages≥8 先挡；
     * ③ 链接 <6 且同词干大图 ≥8 张 → 图集页 null，pics:// 语义留给 playerContent
     *   （沙箱实证：meizi5 /13149.html 0 条非侧栏链接 + 8 张图 → 不进；both /moviesets/AiCollection/ 24 条链接 → 进）。
     * r345 事故实证（真机 22:41 both /moviesets/zipaitoupai/）：34 条 /video/\d+/ + 41 张封面 gif
     * （40 个词干组、聚类 best=2<3 不打日志）→ 旧序 41≥8 命中图集门 → 不进合集 → 页面播放兜底 →
     * playerContent 阶段 looksLikePicturePage 判真 → pics:// 跳图片查看器；同站 pochu 同样 41 张封面
     * 但 5 张同前缀 → 聚类后 5<8 过门 → 进合集 24 集。同站两页两种结局，门序错了。
     */
    private List<String[]> collectionEpisodes(String html, String pageUrl) {
        if (TextUtils.isEmpty(html)) return null;
        boolean hasPlayer = html.contains("ql-video") || html.contains("player-container")
                || html.contains("eval(function(p,a,c,k,e,d)")
                // push v50（mrds 修法 A）：页内有 dplayer/xgplayer/data-video_* 播放器节点 = 单片播放页，
                //   不判合集，交给 pagePlaylistEpisodes 抽真线路（mrds /archives/N/ 实证：菜单 11 链接曾被当合集）。
                || hasPlayerNode(html);
        if (hasPlayer) return null;
        // r418：**先判图集再判链接** —— 顺序反了会把图集页的推荐区链接当「选集」。
        // aisee9w11 实证（沙箱复刻）：detail-138213 是 202 张 img3 的图集页（comicpage 容器），
        // 推荐区 24 个 /detail-{id}/ 链接被 collectPageEpisodes 抽成 24 集 → 假合集
        // → from=合集 → forceVideo → 图片分流被跳过 → 候选 0 → 跳网页（用户连点 5 个全跳）。
        // 修正后：大图 ≥8 → 本页就是图集，不当合集（交给 pics:// 链路）。
        if (collectBigImages(html).size() >= 8) return null;
        List<String[]> eps = collectPageEpisodes(html);
        // r456：详情页形态判否 —— 抽到的条目若全部不属于当前条目 vid（详情页推荐位/侧栏），
        // 它们不是本条选集（真机实证：cghjs /vod/detail/id/156298.html 推荐位 16 条全异 vid 被当「合集 16 集」，
        // 用户点开全是别的视频）。条目 URL 里含 /id/<当前vid>/ 即算同条。
        if (eps.size() >= 6 && collectionForeignOnly(pageUrl, eps)) {
            log("合集判否：详情页抽到 " + eps.size() + " 条全异 vid（推荐位），不当作选集");
            return null;
        }
        if (eps.size() >= 6) return collectAllPages(eps, html, pageUrl);
        return null;
    }

    /**
     * r458：从任意条目 URL 提取「条目 id」—— **形态自适应，禁止锚死单一路由**。
     * 背景：r456 的假合集判否只认 /vod/(detail|play)/id/N（maccms10 形态），
     *   子目录/根路径数字站（auwitf.hby4 /1173972.html）提取为空 → 判据失效 → 假合集照旧（本轮真因）。
     * 覆盖：maccms10、maccmsphp 子目录、/voddetail/N、/vodplay/N、根路径 /N.html、?id=N。
     */
    private String itemKeyOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String v = firstMatch(url, "/vod/(?:detail|play)/id/(\\d+)");
        if (TextUtils.isEmpty(v)) v = firstMatch(url, "/voddetail/(\\d+)");
        if (TextUtils.isEmpty(v)) v = firstMatch(url, "/vodplay/(\\d+)");
        if (TextUtils.isEmpty(v)) v = firstMatch(url, "/(\\d{3,})\\.html");
        if (TextUtils.isEmpty(v)) v = firstMatch(url, "[?&]id=(\\d{3,})");
        // push v50（mrds 修法 A）：路径最后一段 3 位以上纯数字（/archives/203155/）。
        if (TextUtils.isEmpty(v)) v = firstMatch(url, "/(\\d{3,})/?(?:[?#]|$)");
        return TextUtils.isEmpty(v) ? "" : v;
    }

    /**
     * r456/r458：抽到的条目是否是「推荐位/侧栏」而非本条的选集 —— 形态自适应判据。
     * r458 两次修正：
     *   ① 条目 id 提取改用 itemKeyOf（原只认 /vod/(detail|play)/id/N，根路径数字站提取为空 → 判据失效）；
     *   ② 判据由「全异 vid」改为「**同 vid ≤ 1 条**」—— 详情页总有一条指向自身的链接
     *      （分享/面包屑/当前播放），它会把「全异」判据破坏掉。auwitf.hby4 实证：
     *      抽到 19 条 = 18 条推荐位（异 vid）+ 1 条自身 → same=1 → 旧判据 false → 仍造「合集 19 集」。
     *      真选集必然 ≥ 2 条同 vid（第1集/第2集…），故 same ≤ 1 判否。
     */
    private boolean collectionForeignOnly(String pageUrl, List<String[]> eps) {
        String vid = itemKeyOf(pageUrl);
        if (TextUtils.isEmpty(vid)) return false;
        int same = 0;
        for (String[] e : eps) {
            if (e.length > 1 && vid.equals(itemKeyOf(e[1]))) same++;
        }
        if (same <= 1) log("合集判否：同 vid 仅 " + same + " 条 / 共 " + eps.size() + " 条（推荐位为主，不造合集）");
        return same <= 1;
    }

    /**
     * r345：合集选集翻页聚合 —— 页面 head 的 rel="next" 指向下一页（both 站 /moviesets/&lt;slug&gt;/&lt;N&gt;/），
     * 真机实证 zipaitoupai 共 58 页 1375 集，r344 只给第一页 24 集。
     * 末段纯数字才推导 URL 模板（?page=N 等形态不猜 URL，保守不翻页）；
     * 8 线程一批递进抓取，整批零新增（404/空页/全重复）即停，总预算 12s、上限 60 页；
     * 结果按页码升序、URL 判重聚合；COLLECTION_CACHE 按 URL 缓存（LRU 8 条，防反复进出重抓 58 页）。
     */
    private List<String[]> collectAllPages(List<String[]> first, String html, String pageUrl) {
        try {
            String key = abs(pageUrl);
            List<String[]> cached = COLLECTION_CACHE.get(key);
            if (cached != null) {
                log("合集选集缓存命中 " + cached.size() + " 集（" + key + "）");
                return cached;
            }
            Matcher nx = Pattern.compile("<link[^>]*rel=\"next\"[^>]*href=\"([^\"]+)\"|<link[^>]*href=\"([^\"]+)\"[^>]*rel=\"next\"", Pattern.CASE_INSENSITIVE).matcher(html);
            if (!nx.find()) return first;
            String absNext = abs(TextUtils.isEmpty(nx.group(1)) ? nx.group(2) : nx.group(1));
            Matcher tm = Pattern.compile("^(.*?/)(\\d+)(/?)$").matcher(absNext);
            if (!tm.find()) return first;
            String head = tm.group(1), suffix = tm.group(3);
            Set<String> seen = new LinkedHashSet<>();
            for (String[] e : first) seen.add(e[1]);
            List<String[]> all = new ArrayList<>(first);
            long deadline = System.currentTimeMillis() + 12_000L;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                int page = 2;
                while (page <= 60 && System.currentTimeMillis() < deadline) {
                    int batchEnd = Math.min(page + 7, 60);
                    List<Callable<String>> batch = new ArrayList<>();
                    for (int p = page; p <= batchEnd; p++) {
                        final String u = head + p + suffix;
                        batch.add(() -> get(u));
                    }
                    long left = deadline - System.currentTimeMillis();
                    List<Future<String>> fs = left > 0 ? pool.invokeAll(batch, left, TimeUnit.MILLISECONDS) : java.util.Collections.emptyList();
                    int added = 0;
                    for (Future<String> f : fs) {
                        try {
                            String h = f.get();
                            if (TextUtils.isEmpty(h)) continue;
                            List<String[]> eps = collectPageEpisodes(h);
                            if (eps.isEmpty()) continue;
                            for (String[] e : eps) if (seen.add(e[1])) { all.add(e); added++; }
                        } catch (Throwable ignore) {
                        }
                    }
                    if (added == 0) break;   // 整批零新增 → 后面没有更多页（404/空页/全重复），停
                    page = batchEnd + 1;
                }
            } finally {
                pool.shutdownNow();
            }
            if (all.size() > first.size()) {
                log("合集翻页聚合 " + all.size() + " 集（" + key + "）");
                synchronized (COLLECTION_CACHE) {
                    COLLECTION_CACHE.put(key, all);
                    while (COLLECTION_CACHE.size() > 8) {
                        Iterator<Map.Entry<String, List<String[]>>> it = COLLECTION_CACHE.entrySet().iterator();
                        if (it.hasNext()) { it.next(); it.remove(); }
                    }
                }
            }
            return all;
        } catch (Throwable t) {
            log("合集翻页异常 " + t);
            return first;
        }
    }

    /** r344：把合集选集拼成 vod_play_url 串（题名$URL 以 # 分隔；无题名退回 第N集）。 */
    private static String joinEpisodeNames(List<String[]> eps) {
        StringBuilder urls = new StringBuilder();
        for (int i = 0; i < eps.size(); i++) {
            if (i > 0) urls.append('#');
            String name = TextUtils.isEmpty(eps.get(i)[0]) ? ("第" + (i + 1) + "集") : eps.get(i)[0];
            urls.append(name).append('$').append(eps.get(i)[1]);
        }
        return urls.toString();
    }

    /**
     * r344：站点自报「本页无内容」—— schema.org JSON-LD numberOfItems:0，或服务端渲染的
     * 「暂无短剧/暂无内容/暂无数据/暂无影片」「暂无…敬请期待」空态文案。
     * 沙箱+真浏览器实证 both 站 duanju 8 个子分类服务端就是空的，渲染兜底跑两遍 WebView 也变不出条目；
     * 4kcz 的 403 挑战页不含这些标记，不受影响（r343 渲染兜底不可破坏）。
     */
    private static boolean isExplicitEmpty(String html) {
        if (TextUtils.isEmpty(html)) return false;
        if (html.contains("\"numberOfItems\":0")) return true;
        if (html.contains("暂无短剧") || html.contains("暂无内容") || html.contains("暂无数据") || html.contains("暂无影片")) return true;
        return html.contains("暂无") && html.contains("敬请期待");
    }

    /** r344：返回 {题名, 绝对URL} 列表；题名取同 href 第一个带文本的锚点（图片锚点无文本），缺省空串由调用方退回 第N集。 */
    private List<String[]> collectPageEpisodes(String html) {
        List<String[]> eps = new ArrayList<>();
        try {
            if (TextUtils.isEmpty(html)) return eps;
            html = normalizeHref(html);   // r344：绝对地址站也要能抽（href 统一成站内相对形态再匹配 pattern）
            String pattern = detailPatternOf(html);
            if (!pattern.contains("\\d")) return eps;   // 只收数字模式（视频条目），slug 模式页面不是选集页
            Map<String, String> names = new LinkedHashMap<>();
            Matcher t = Pattern.compile("<a[^>]+href=\"(" + pattern + ")\"[^>]{0,2000}>([\\s\\S]{0,150}?)</a>", Pattern.DOTALL).matcher(html);
            while (t.find() && names.size() < 200) {
                String txt = clean(t.group(2));
                if (TextUtils.isEmpty(txt)) continue;
                if (txt.length() > 24) txt = txt.substring(0, 24);   // 题名超长截断，防止整段文本混入选集名
                String prev = names.get(t.group(1));
                if (TextUtils.isEmpty(prev)) names.put(t.group(1), txt.replace("$", "＄").replace("#", "＃"));
            }
            Set<String> seen = new LinkedHashSet<>();
            // r344：先剔侧栏再判重 —— 侧栏出现过的 href 不能把主区同名链接顶掉（侧栏在前时旧顺序会误杀主区条目）
            Matcher m = Pattern.compile("<a[^>]+href=\"(" + pattern + ")\"[^>]{0,2000}>", Pattern.DOTALL).matcher(html);
            while (m.find()) {
                String href = m.group(1);
                if (SIDEBAR_BLOCK.matcher(html.substring(Math.max(0, m.start() - 200),
                        Math.min(html.length(), m.end() + 80))).find()) continue;
                // r489：选集链接也要排除导航项（dropdown-item 广告「原创招募/百万现金扶持」、
                // post-near 上一篇/下一篇）—— 吃瓜榜单文章正文里这些 href 同是 /archives/\d+/，
                // 不排除就会混进 TOP10 选集里。
                if (NAV_DROPDOWN.matcher(html.substring(Math.max(0, m.start() - 400),
                        Math.min(html.length(), m.end() + 80))).find()) continue;
                // push v50（mrds 修法 A）：站方自己标成广告的链接（rel="sponsored" / data-event="ad_click"）不当选集。
                if (AD_MARKED_LINK.matcher(m.group(0)).find()) continue;
                if (!seen.add(href)) continue;
                String name = names.get(href);
                eps.add(new String[]{name == null ? "" : name, abs(href)});
                if (eps.size() >= 200) break;
            }
        } catch (Throwable ignore) {
        }
        return eps;
    }

    // ------------------------------------------------------------------ 工具

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private static String firstMatch(String text, String... regexes) {
        for (String regex : regexes) {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
            if (m.find()) return unescapeHtml(clean(m.group(1)));
        }
        return "";
    }

    /**
     * r360：document.write 解密站（sm455.vip 型）的卡片标题 —— deobfuscate 把
     * document.write(l("密文")) 整体替换为明文后，标题是 <p><script>肌肉男SPA按摩馆…;</script></p>
     * （沙箱实证 3e5bh.sm455.vip 卡片：href/img 全在、七个既有标题 pattern 全不命中 → 整页抽空、
     * 宽松兜底同样空，真机日志 genericItems 抽空 ×3）。<p> 直接包 <script> 的组合形态正常站
     * 极罕见，组内再排除 {}（JS 代码必带花括号）挡住误抓。主路径与宽松路径共用本方法
     * （铁律：通用判据所有调用点共用同一个函数，r317/r318 教训）。
     */
    private static String scriptTitleOf(String seg) {
        String t = firstMatchGroup(seg, "<p[^>]*>\\s*<script>\\s*([^<>{}]{2,120}?)\\s*;?\\s*</script>");
        if (TextUtils.isEmpty(t)) return "";
        return clean(t);
    }
    private static String firstMatchGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 去标签 + 反转义常见实体。 */
    private static String clean(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String t = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return unescapeHtml(t);
    }

    private static String unescapeHtml(String s) {
        if (TextUtils.isEmpty(s)) return "";
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&ldquo;", "“").replace("&rdquo;", "”");
    }

    /** 把页面里 JS/JSON 转义过的内容还原：转义斜杠 → /，转义引号 → "，unicode 转义 → 字符。 */
    private static String unescapeJs(String s) {
        if (TextUtils.isEmpty(s)) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '/') {
                    sb.append('/');
                    i++;
                    continue;
                }
                if (n == '"') {
                    sb.append('"');
                    i++;
                    continue;
                }
                if (n == 'u' && i + 5 < s.length()) {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (Throwable ignore) {
                    }
                }
                if (n == 'n' || n == 'r' || n == 't') {
                    sb.append(' ');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ r359: YouTube 专项模板

    /**
     * r359：m.youtube.com 是 SPA 巨站 —— 无 cookie 首页 ytInitialData 只有骨架
     * （沙箱实证：chips 0、richGrid 空），collectClasses/genericItems 全抽空
     * （真机 03:24 日志「分类渲染兜底第1/2次渲染失败」白等 50 秒）。
     * 沙箱 A 级实证：搜索页 results?search_query=Q 的 ytInitialData 有 19 个
     * videoWithContextRenderer（videoId/headline/thumbnail/lengthText 全字段）；
     * watch 页 ytInitialPlayerResponse.streamingData 29 个 format 全带签名直链
     * （itag18=360p mp4 音视频合一）。四能力全走 yt JSON，零嗅探、零渲染。
     * 分类用预设搜索式（tid=搜索词），同类 ytInitialData 结构 SPA 巨站自动适配。
     */
    private static final String[][] YT_CLASSES = {
            {"热门", "热门视频"}, {"电影", "电影解说"}, {"剧集", "电视剧混剪"},
            {"动漫", "动漫"}, {"音乐", "音乐MV"}, {"游戏", "游戏实况"},
            {"综艺", "综艺"}, {"纪录片", "纪录片"}, {"搞笑", "搞笑视频"}, {"短剧", "短剧"},
    };

    private boolean isYouTube() {
        return root != null && root.toLowerCase(Locale.ROOT).contains("youtube.com");
    }

    private LinkedHashMap<String, String> youtubeClasses() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (String[] p : YT_CLASSES) m.put(p[0], p[1]);
        return m;
    }

    /** JS 字符串转义（\xNN 逐字节 + U+NNNN 码点 + 常规转义）→ UTF-8 字节。ytInitialData 专用。 */
    private static byte[] jsEscapeBytes(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                try {
                    if (n == 'x' && i + 2 < s.length()) {
                        out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                    } else if (n == 'u' && i + 4 < s.length()) {
                        int cp = Integer.parseInt(s.substring(i + 1, i + 5), 16);
                        if (cp < 0x80) out.write(cp);
                        else if (cp < 0x800) { out.write(0xC0 | (cp >> 6)); out.write(0x80 | (cp & 0x3F)); }
                        else { out.write(0xE0 | (cp >> 12)); out.write(0x80 | ((cp >> 6) & 0x3F)); out.write(0x80 | (cp & 0x3F)); }
                        i += 4;
                    } else if (n == 'n') out.write('\n');
                    else if (n == 'r') out.write('\r');
                    else if (n == 't') out.write('\t');
                    else out.write(n & 0xFF);
                } catch (Throwable ignored) {
                }
            } else if (c < 0x80) {
                out.write(c);
            } else {
                byte[] b = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return out.toByteArray();
    }

    /** 抽 ytInitialData（\x 转义字符串）并解码为 JSON 文本；取不到返回空串。 */
    private String ytInitialDataOf(String html) {
        if (html == null) return "";
        Matcher m = Pattern.compile("var ytInitialData = '((?:[^'\\\\]|\\\\.)*)'").matcher(html);
        if (!m.find()) return "";
        return new String(jsEscapeBytes(m.group(1)), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 抽 ytInitialPlayerResponse（先试直接 JSON 括号配平，再试 \x 转义串）。 */
    private JSONObject ytPlayerResponseOf(String html) {
        if (html == null) return new JSONObject();
        try {
            Matcher m = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*\\{").matcher(html);
            if (m.find()) {
                int start = html.indexOf('{', m.end() - 1);
                int depth = 0;
                for (int i = start; i < html.length(); i++) {
                    char ch = html.charAt(i);
                    if (ch == '{') depth++;
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0) return new JSONObject(html.substring(start, i + 1));
                    }
                }
            }
            Matcher m2 = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*'((?:[^'\\\\]|\\\\.)*)'").matcher(html);
            if (m2.find()) return new JSONObject(new String(jsEscapeBytes(m2.group(1)), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    private String ytRuns(JSONObject holder) {
        if (holder == null) return "";
        JSONArray runs = holder.optJSONArray("runs");
        if (runs != null && runs.length() > 0) return runs.optJSONObject(0).optString("text");
        return holder.optString("simpleText");
    }

    private String ytThumb(JSONObject holder) {
        if (holder == null) return "";
        JSONArray ts = holder.optJSONArray("thumbnails");
        if (ts != null && ts.length() > 0) return ts.optJSONObject(ts.length() - 1).optString("url");
        return "";
    }

    /** 递归遍历 yt JSON，收集 videoWithContextRenderer（m 端搜索结果节点）。 */
    private void ytCollectVideos(Object node, JSONArray out, Set<String> seen, int limit) {
        if (out.length() >= limit) return;
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                JSONObject v = o.optJSONObject("videoWithContextRenderer");
                if (v != null) {
                    String vid = v.optString("videoId");
                    if (!TextUtils.isEmpty(vid) && seen.add(vid)) {
                        JSONObject item = new JSONObject();
                        item.put("vod_id", "https://m.youtube.com/watch?v=" + vid);
                        item.put("vod_name", ytRuns(v.optJSONObject("headline")));
                        item.put("vod_pic", ytThumb(v.optJSONObject("thumbnail")));
                        item.put("vod_remarks", ytRuns(v.optJSONObject("lengthText")));
                        out.put(item);
                    }
                }
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) ytCollectVideos(o.get(it.next()), out, seen, limit);
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) ytCollectVideos(a.get(i), out, seen, limit);
            }
        } catch (Throwable ignored) {
        }
    }

    /** youtube 搜索（tid=搜索词）→ 条目列表。 */
    private JSONArray youtubeSearchItems(String query, int limit) {
        JSONArray arr = new JSONArray();
        try {
            String url = "https://m.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8");
            String json = ytInitialDataOf(get(url));
            if (TextUtils.isEmpty(json)) {
                log("youtube 搜索未取到 ytInitialData q=" + query);
                return arr;
            }
            Set<String> seen = new HashSet<>();
            ytCollectVideos(new JSONObject(json), arr, seen, limit <= 0 ? 20 : limit);
            log("youtube 搜索 " + query + " → " + arr.length() + " 条");
        } catch (Throwable t) {
            log("youtube 搜索异常 " + t);
        }
        return arr;
    }

    /** youtube 详情 —— watch 页 ytInitialPlayerResponse.videoDetails。 */
    private JSONObject youtubeDetail(String id) {
        JSONObject vod = new JSONObject();
        try {
            String url = id.startsWith("http") ? id : "https://m.youtube.com/watch?v=" + id;
            JSONObject vd = ytPlayerResponseOf(get(url)).optJSONObject("videoDetails");
            if (vd == null || TextUtils.isEmpty(vd.optString("title"))) {
                log("youtube 详情未取到 videoDetails " + url);
                return vod;
            }
            vod.put("vod_id", url);
            vod.put("vod_name", vd.optString("title"));
            JSONObject th = vd.optJSONObject("thumbnail");
            if (th != null) vod.put("vod_pic", ytThumb(th));
            long sec = vd.optLong("lengthSeconds", 0);
            if (sec > 0) vod.put("vod_remarks", (sec / 60) + "分钟");
            vod.put("type_name", "视频");
            vod.put("vod_play_from", "YouTube");
            vod.put("vod_play_url", "播放$" + url);
            log("youtube 详情 " + vd.optString("title"));
        } catch (Throwable t) {
            log("youtube 详情异常 " + t);
        }
        return vod;
    }

    /** youtube 播放 —— streamingData 直链（itag18=mp4 音视频合一优先）；拿不到返回 null 走通用嗅探兜底。 */
    private JSONObject youtubePlay(String page) {
        try {
            String url = page.startsWith("http") ? page : "https://m.youtube.com/watch?v=" + page;
            JSONObject sd = ytPlayerResponseOf(get(url)).optJSONObject("streamingData");
            if (sd == null) {
                log("youtube 播放未取到 streamingData → 走通用嗅探兜底");
                return null;
            }
            JSONArray formats = sd.optJSONArray("formats");
            String pick = "";
            if (formats != null) {
                for (int i = 0; i < formats.length(); i++) {
                    JSONObject f = formats.optJSONObject(i);
                    if ("18".equals(f.optString("itag")) && !TextUtils.isEmpty(f.optString("url"))) {
                        pick = f.optString("url");
                        break;
                    }
                }
                if (TextUtils.isEmpty(pick)) {
                    for (int i = 0; i < formats.length(); i++) {
                        JSONObject f = formats.optJSONObject(i);
                        if (!TextUtils.isEmpty(f.optString("url"))) {
                            pick = f.optString("url");
                            break;
                        }
                    }
                }
            }
            if (TextUtils.isEmpty(pick)) {
                log("youtube formats 无直链 → 走通用嗅探兜底");
                return null;
            }
            JSONObject r = new JSONObject();
            r.put("parse", 0);
            r.put("url", pick);
            JSONObject h = new JSONObject();
            h.put("User-Agent", UA);
            r.put("header", h);
            log("youtube 直链命中（" + pick.length() + " 字）→ parse=0 直投");
            return r;
        } catch (Throwable t) {
            log("youtube 播放异常 " + t);
            return null;
        }
    }
}
