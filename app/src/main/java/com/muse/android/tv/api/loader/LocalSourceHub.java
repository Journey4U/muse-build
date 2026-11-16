package com.muse.android.tv.api.loader;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Prefers;
import com.muse.android.tv.bean.Site;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import android.content.Context;
import android.util.TypedValue;
import java.util.concurrent.ConcurrentHashMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * r13-③ / r14-② 本地文件夹源：完全对齐用户手工脚本「本地包生成.py」的生成规则。
 *
 * <p>r14 变化（此前版本比用户脚本少很多源，原因是路径写法、分类判定与文件类型覆盖都不一致）：
 * <ul>
 *     <li>扫描规则逐条对齐脚本：深度 5、屏蔽目录、排除文件、
 *     pq → XBPQ、yq → XYQ、js → drpy、本地包/package/pkg/包 → 文件包、直播文件 → lives（2026-11-11 起类型目录识别词改短词，老目录名 PQ类/YQ类/JS类 因包含/开头匹配照样认）；</li>
 *     <li>扩展名与站点类型逐条对齐：py / js / wv.js / php / html / xbpq / xyq / json / txt / m3u / db / zip / pkg；</li>
 *     <li>r17-①②：站点名 = {@code 原始文件名[文件夹名](源类型)}。名字主体保留文件名原样（{@code badnews (1)}
 *     这种自带圆括号不动，只有 {@code [标签]} 会被删掉 —— 否则 App 会把它当成一个源分类），
 *     分类只允许是文件夹名，不是文件夹名的分类在落盘前整条删除（见 {@link #enforceFolderCategories}，
 *     不再有「主页 / 设置」这类例外），同名源用 {@code ·标签} / {@code ·序号} 区分；</li>
 *     <li>路径写法对齐脚本：输出目录内用 {@code ./相对路径}，目录外用 {@code file://绝对路径}；
 *     由于本壳不会自动解析配置里的相对路径，解析配置前用 {@link #resolveRelative} 把 {@code ./}
 *     按「生成文件所在目录」展开成宿主机可读地址（脚本产物直接当配置地址用也能正常加载）；</li>
 *     <li>输出为完整配置（spider / logo / wallpaper / lives / sites / parses / rules / doh / flags / ijk / ads），
 *     与脚本产出的 json 同构；</li>
 *     <li>设置项与脚本的设置文件一一对应（输出路径、扫描目录、目录启用、扫描类型、扫描模式、
 *     屏蔽目录、排除文件、解析条目），另加「Base64 规则自动解码」开关；r17-⑤ 新增「spider jar」
 *     与「直播源地址」两项：留空则产物里<b>不写</b>这两个字段，写过则持久保留直到用户主动改或清空；</li>
 *     <li>r17-④：不再有「扫描 18+ 内容」设置，带 {@code [密]} / {@code [18]} 这类标签的目录一律照常扫描。</li>
 *     <li>r20-①：「直播源地址」不再回落脚本内置默认值 —— 默认空，只有用户自己填过才写进产物；</li>
 *     <li>r20-②：长按删源按 {@code ext → homePage → api} 定位真实源文件（引擎型源如
 *     {@code csp_FileSpider} / {@code csp_XBPQ} / {@code csp_Nostr} / drpy 的 api 只是引擎名，
 *     文件路径在 ext / homePage 里，老实现只按 api 找 → 一律误报「该源没有源文件」）。</li>
 * </ul>
 *
 * <p>注入策略仍是只追加 sites / lives（不覆盖用户订阅源里的 parses / rules 等），异常只记日志不影响原订阅源。
 */
public class LocalSourceHub {

    private static final String TAG = "LocalSource";

    /** 1.0.3-① 源身份缓存：key = api + \u0001 + ext，空身份记哨兵，满 8000 清空后重建（对应 1.0.3 rp1.R0 的 ConcurrentHashMap 层）。 */
    private static final ConcurrentHashMap<String, String> sourcePathCache = new ConcurrentHashMap<>();
    private static final String NULL_PATH_SENTINEL = "\u0001null";
    private static final int SOURCE_PATH_CACHE_CAP = 8000;

    /** 设置整包（与脚本 本地包生成_settings.json 同构，多存一个 decode_base64）。 */
    private static final String KEY_SETTINGS = "local_source_settings";
    /** 功能总开关。 */
    private static final String KEY_ENABLED = "local_source_enabled";
    /** 加载订阅源时是否自动重新扫描。 */
    private static final String KEY_AUTO = "local_source_auto";
    /** 上次扫描摘要。 */
    private static final String KEY_SUMMARY = "local_source_summary";
    /** 上次扫描诊断（目录里实际有哪些类型 / 当前勾选了哪些）。 */
    private static final String KEY_HINT = "local_source_diagnosis";
    /** 上次生成的 json 路径。 */
    private static final String KEY_FILE = "local_source_file";
    /** r13 旧的单目录设置（迁移用）。 */
    private static final String KEY_DIR_LEGACY = "local_source_dir";
    /** r23-①：扫描路径口径「一次性收口」的标记位（只做一次，之后完全尊重用户自己的勾选）。 */
    private static final String KEY_PATH_SCOPE = "local_source_path_scope_fixed";

    /** r15：扫描期临时字段前缀，只用于去重判断，落盘前会全部剔除。 */
    private static final String HINT_PREFIX = "#";
    private static final String HINT_PATH = HINT_PREFIX + "path";
    private static final String HINT_BASE = HINT_PREFIX + "base";
    private static final String HINT_LABEL = HINT_PREFIX + "label";

    /** 外部存储根的各种写法（归一化用于判断“是不是同一个目录”）。 */
    private static final String EXTERNAL_ROOT = "/sdcard";
    private static final String[] EXTERNAL_PREFIXES = {
        "/storage/emulated/0/", "/storage/emulated/legacy/", "/storage/self/primary/", "/mnt/user/0/primary/", "/sdcard/"
    };

    /**
     * r25-②：{@code file://自己整理 (1)/PQ类/a.json} 这种「脚本写法」的展开基准 —— 路径里那一段是
     * <b>相对外部存储根</b>的（App 载入配置本身也用这种写法：{@code file://自己整理 (1)/昏黄本地源.json}），
     * 解析成绝对路径时必须补上存储根，否则整批本地源都会被判成「外地源」而被剔除。
     */
    private static final String[] EXTERNAL_BASES = {"/storage/emulated/0", "/sdcard", "/storage/self/primary"};

    /** 生成文件名（默认放在第一个启用的扫描目录里）。r29-⑤：名字可自定义，默认 Muse本地源.json。 */
    public static final String DEFAULT_OUT_NAME = "Muse本地源";
    /** r29 之前写死的产物名：仍按「本壳产物」识别，避免老用户已有文件被当成自写源。 */
    /** Vesper 时代的默认产物名：仍按「本壳产物」识别，避免已用 Vesper 的老用户文件被误判成自写源。 */
    public static final String LEGACY_VESPER_OUT_NAME = "Vesper自动本地源";
    public static final String LEGACY_OUT_NAME = "昏黄本地源.json";
    /** 资产里的默认常量（由用户脚本导出）。 */
    private static final String ASSET = "local_source_defaults.json";

    /** 直播目录名：该目录及其子目录里的支持文件生成 lives。 */
    private static final String LIVE_DIR_NAME = "直播文件";
    /**
     * 特殊目录关键词：目录名命中后，其所有子目录继承该解析类型。识别口径：目录名包含即算。
     *
     * <p>2026-11-11 Liu 定（v41）：关键词加 {@code json} —— 有人把 XBPQ 规则放在名为
     * json 的文件夹里，与 pq 文件夹同等待遇；{@link #dirKey} 里 json 开头不判 js 的
     * 防线保留，json 文件夹不会被误认成 JS 文件夹。</p>
     */
    private static final String[] XBPQ_DIR_KEYWORDS = {"pq", "json"};
    private static final String[] XYQ_DIR_KEYWORDS = {"yq"};
    /**
     * 「PY 源」目录关键词（2026-11-11 Liu 定）：py 条目本身仍按 {@code .py} 后缀生成（放哪个
     * 文件夹都认），这个词只把 py 文件夹立为正式类型目录 —— 用于分类归属与「同类目录之间
     * 搬家」的同类判定（见 {@link #dirKeywords}）。识别口径与 pq / yq 一致：目录名包含即算，
     * 所以老的 {@code PY类} 文件夹照样认。
     */
    private static final String[] PY_DIR_KEYWORDS = {"py"};
    /**
     * 「JS 源」目录关键词：目录名命中后，里面的 {@code .js} 按 drpy 形态生成 ——
     * {@code api=./js/drpy2.min.js}、{@code ext=脚本路径}（引擎库 + 脚本两段式调用）。
     *
     * <p>2026-11-11 Liu 定：识别词收成一个 {@code js}。历史叫法 {@code JS[Drpy]} / {@code JS类}
     * 不再单列 —— {@link #dirKey} 是「去括号后以关键词开头」匹配，两个老名字都以 js 开头，
     * 照样认；纯 {@code drpy} 命名的文件夹不再特殊（drpy 形态只认 js 文件夹）。
     * r29-① 的教训保留：认不出时 js 会被当成「cat 源」生成（api 直接指向脚本），与 drpy 引擎对不上。
     */
    private static final String[] DRPY_DIR_KEYWORDS = {"js"};
    /**
     * 「cat 源」目录关键词（r29-①）：目录名命中后，强制按 cat 形态生成 ——
     * {@code api} 直接指向 {@code .js} 脚本、不带 {@code ext}（cat 源必须放在 {@code cat类} 文件夹下）。
     *
     * <p>普通 js 本来就是 cat 形态，这条关键词的价值在于「反向覆盖」：万一 {@code cat类}
     * 被放在 drpy 目录之下（父目录继承），也要把它拉回 cat 形态。
     */
    private static final String[] CAT_DIR_KEYWORDS = {"cat类", "cat"};
    private static final String[] PKG_DIR_KEYWORDS = {"本地包", "package", "pkg", "包"};
    /**
     * 全部可选扫描类型（r16：与「源类型」显示规则统一 —— 只认文件名的最后一个后缀）。
     *
     * <p>r15 之前这里沿用脚本 SCAN_FILE_TYPES，里面混着 {@code xbpq} / {@code xyq} / {@code wv.js}
     * 这类「引擎名」：它们不是真实后缀，勾选列表里出现的名字在目录里永远找不到，也和源名末尾的
     * {@code (类型)} 标记（= 最后一个后缀）对不上。r16 起两边统一成后缀；json 用哪个引擎
     * （XBPQ / XYQ）仍由所在目录决定，不写进类型。
     */
    public static final String[] ALL_EXTENSIONS = {"py", "js", "php", "html", "json", "txt", "m3u", "db", "zip", "pkg"};

    /** r15 出厂默认的忽略目录名；用户没动过设置时按「未设置」处理（r16-③ 默认全不选）。 */
    private static final String LEGACY_DEFAULT_BLOCKED = ".git,.idea,labeditor,lib,pycache,webview,道长,全能王,直播转点播辅助文件";

    private static final int DEFAULT_DEPTH = 5;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_FILES = 20000;
    private static final int MAX_SITES = 4000;
    private static final int MAX_LIVES = 2000;
    private static final int MAX_JSON_CHARS = 8 * 1024 * 1024;
    private static final long MIN_INTERVAL = 3000L;
    private static final int DEFAULT_PHP_PORT = 8901;
    /** 忽略目录名 / 忽略文件名勾选列表最多展示的候选数量。 */
    private static final int MAX_CANDIDATES = 3000;

    /** r17-②③：名字里的 {@code [标签]}（带捕获组，用来判断它是不是一个「文件夹名」）。 */
    private static final java.util.regex.Pattern TAG_PATTERN = java.util.regex.Pattern.compile("\\[([^\\]]+)]");

    private static final Object LOCK = new Object();
    private static JsonObject cache;
    private static long cacheAt;
    /** 上次扫描生成的站点数。 */
    private static volatile int lastSites;

    // ---- r18-④：本地源自动回写（把最新生成结果自动落到输出 json）----
    /** 自动回写节流间隔：1 秒内最多写一次。 */
    private static final long AUTO_WRITE_INTERVAL = 1000L;
    /** 上次自动回写的正文签名（内容一致就不再写盘）。 */
    private static String autoWriteHash = "";
    /** 上次自动回写时间。 */
    private static long autoWriteAt;
    /** 是否有后台回写线程正在跑（避免设置连续变更时堆线程）。 */
    private static volatile boolean autoWriteRunning;

    // ---- r26-②：自己编写的本地源保护（自动刷新回填 / 删除源 / 移动源 三项都不做）----
    /** r26-②：删除 / 移动被拒 —— 这是用户自己编写的本地源，本壳不许动。 */
    public static final int BLOCK_SELF_AUTHORED = -4;
    /** r26-②：当前加载的配置地址（{@code VodConfig.parseConfig} 在解析前写入）。 */
    private static volatile String currentConfigUrl = "";
    /** r26-②：最近一次被拦下的自动回写目标（界面提示后由 {@link #consumeBlockedWrite} 取走清空）。 */
    private static volatile String blockedWriteTarget = "";

    private LocalSourceHub() {
    }

    // ==================== 资产常量 ====================

    /** r60-③：assets 默认常量的解析结果缓存（运行期不变，见下方 asset() 说明）。 */
    private static JsonObject assetCache;

    /**
     * r60-③：默认常量（assets/{@link #ASSET}）。
     *
     * <p>卡顿真因：这个方法以前**每次调用都读一次 assets 文件并解析 JSON**，而
     * {@code settings() → defaultSettings() → asset()} 是「每条源算一次物理身份」的必经之路
     * （{@link #sourcePathOf} → {@link #relativeSourcePath} → {@code sourceDirs(settings())}）。
     * 打开源搜索弹窗时 {@code SiteAdapter} 要给上千条源逐条算身份 → 上千次「文件读取 + JSON 解析」
     * 全挤在 UI 线程上，用户实测「点击进入源搜索界面每次卡顿近 1 秒」。</p>
     *
     * <p>修法：解析结果缓存；调用方拿到的仍是**可自由修改的副本**（deepCopy），语义与原来一致。</p>
     */
    private static JsonObject asset() {
        JsonObject cached = assetCache;
        if (cached != null) return cached.deepCopy();
        try {
            String text = Asset.read(ASSET);
            if (!TextUtils.isEmpty(text)) {
                JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
                assetCache = parsed;
                return parsed.deepCopy();
            }
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "默认常量读取失败：" + e);
        }
        return new JsonObject();
    }

    private static String assetString(String key, String def) {
        try {
            JsonObject object = asset();
            if (object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsString();
        } catch (Throwable ignored) {
        }
        return def;
    }

    // ==================== 设置读写 ====================

    /** 脚本的 default_settings()：读取失败时回落到脚本默认值。 */
    public static JsonObject defaultSettings() {
        JsonObject defaults = new JsonObject();
        try {
            JsonObject object = asset();
            if (object.has("DEFAULTS") && object.get("DEFAULTS").isJsonObject()) {
                defaults = object.getAsJsonObject("DEFAULTS").deepCopy();
            }
        } catch (Throwable ignored) {
        }
        // r29-⑥①：产物固定生成到「第一个已勾选扫描目录」下，文件名可在设置里自定义；
        // output_path 设置项已删除，不再写入默认值。
        defaults.addProperty("out_name", DEFAULT_OUT_NAME);
        defaults.add("scan_dirs", new JsonArray());
        defaults.add("scan_enabled_dirs", new JsonArray());
        // r16-⑤：扫描类型默认全不勾选（空 = 全部类型），候选由「实际扫描目录里存在的后缀」生成。
        defaults.add("scan_extensions", new JsonArray());
        // r29-⑥：「扫描模式」「忽略文件名」「同名源只保留一个」三个设置项已删除，
        // 对应键不再写默认值（老用户设置里的旧键会被读取逻辑忽略，不影响产物）。
        // r17-④：「扫描 18+ 内容」设置已删除，带 [密]/[18] 这类标签的目录一律照常扫描。
        // r16-③：忽略目录名默认全不勾选，候选列表只列扫描目录里真实存在的目录名。
        defaults.add("blocked_dirs", new JsonArray());
        // r17-⑤：spider_jar 不写默认值 —— 没设置过 / 存空时按脚本默认探测，探到
        // xbpq251118.jar 才写 spider、探测不到不写（见 getSpiderJar）；
        // live_url 仍是用户清空后显式为空就不写直播条目。
        if (!defaults.has("parse_entries")) defaults.add("parse_entries", assetParses(true));
        // r18-⑪：清空/替换原有点播站点列表的逻辑已移除 —— 本地源只做追加，绝不屏蔽其它源。
        // replace_sites 仅作为历史遗留键保留（不再生效），默认 false。
        if (!defaults.has("replace_sites")) defaults.addProperty("replace_sites", false);
        defaults.addProperty("decode_base64", true);
        defaults.addProperty("max_depth", DEFAULT_DEPTH);
        return defaults;
    }

    /** 脚本默认的解析条目（EXTRA_JSON.parses），全部默认启用。 */
    private static JsonArray assetParses(boolean enabled) {
        JsonArray result = new JsonArray();
        try {
            JsonObject object = asset();
            if (!object.has("EXTRA") || !object.get("EXTRA").isJsonObject()) return result;
            JsonElement parses = object.getAsJsonObject("EXTRA").get("parses");
            if (parses == null || !parses.isJsonArray()) return result;
            for (JsonElement item : parses.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject entry = item.getAsJsonObject().deepCopy();
                if (enabled) entry.addProperty("enabled", true);
                result.add(entry);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    public static JsonObject settings() {
        // r63-③：批量搬家期间只解析一次设置（每次 moveSiteTo 都会调它，几十个源就是几十次解析）
        Batch batch = BATCH.get();
        if (batch != null) return batch.data;
        JsonObject data = defaultSettings();
        try {
            String raw = Prefers.getString(KEY_SETTINGS, "");
            if (!TextUtils.isEmpty(raw)) {
                JsonElement saved = JsonParser.parseString(raw);
                if (saved.isJsonObject()) for (var entry : saved.getAsJsonObject().entrySet()) data.add(entry.getKey(), entry.getValue().deepCopy());
            }
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "设置读取失败：" + e);
        }
        // r16-③：r15 的出厂默认忽略目录按「用户没设置过」处理，迁移成空（只匹配完全相同的整份默认值，
        // 用户自己勾过的列表不受影响）。
        if (LEGACY_DEFAULT_BLOCKED.equals(TextUtils.join(",", list(data, "blocked_dirs")))) data.add("blocked_dirs", new JsonArray());
        // r13 旧设置迁移：单目录 → scan_dirs
        if (list(data, "scan_dirs").isEmpty()) {
            String legacy = Prefers.getString(KEY_DIR_LEGACY, "");
            if (!TextUtils.isEmpty(legacy)) {
                data.add("scan_dirs", stringArray(new String[]{legacy}));
                data.add("scan_enabled_dirs", stringArray(new String[]{legacy}));
            }
        }
        // 扫描目录 file:// 迁移：以前存进去的 file:// 串（手动粘贴 / 选择器回传）按 normalizeScanDir
        // 折成绝对路径后回写一次；折后不变的不写盘，下次读到已是绝对路径就不再动。
        {
            List<String> dirs = list(data, "scan_dirs");
            List<String> enabled = list(data, "scan_enabled_dirs");
            List<String> normDirs = new ArrayList<>();
            for (String d : dirs) {
                String n = normalizeScanDir(d);
                if (!TextUtils.isEmpty(n) && !normDirs.contains(n)) normDirs.add(n);
            }
            List<String> normEnabled = new ArrayList<>();
            for (String d : enabled) {
                String n = normalizeScanDir(d);
                if (!TextUtils.isEmpty(n) && normDirs.contains(n) && !normEnabled.contains(n)) normEnabled.add(n);
            }
            if (!normDirs.equals(dirs) || !normEnabled.equals(enabled)) {
                data.add("scan_dirs", stringArray(normDirs));
                data.add("scan_enabled_dirs", stringArray(normEnabled));
                saveSettings(data);
            }
        }
        // r23-①：扫描路径口径一次性收口 —— 老版本预填/脚本导出的「内置默认目录」并不是用户添加的路径，
        // 一律取消勾选（条目保留在列表里，用户勾一下即可恢复）；只做一次，之后不再干预用户的选择。
        if (!bool(data, KEY_PATH_SCOPE, false)) {
            data.addProperty(KEY_PATH_SCOPE, true);
            int dropped = demoteBuiltinScanDirs(data);
            saveSettings(data);
            if (dropped > 0) DebugLogStore.add(TAG, "扫描口径收口：取消勾选内置默认目录 " + dropped + " 个（只扫用户自己添加的路径）");
        }
        return data;
    }

    public static void saveSettings(JsonObject data) {
        try {
            Prefers.put(KEY_SETTINGS, data.toString());
            cache = null;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "设置保存失败：" + e);
        }
    }

    /** 修改一个设置项（设置弹窗用）。 */
    public static JsonObject edit(String key, JsonElement value) {
        JsonObject data = settings();
        data.add(key, value);
        saveSettings(data);
        // r18-④：设置项一改就自动重新生成并回写 json（后台线程，不卡界面）
        autoWriteAsync();
        return data;
    }

    // ---- 单项读写（设置弹窗用） ----

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean value) {
        Prefers.put(KEY_ENABLED, value);
    }

    public static boolean isAuto() {
        return Prefers.getBoolean(KEY_AUTO, true);
    }

    public static void setAuto(boolean value) {
        Prefers.put(KEY_AUTO, value);
    }

    public static String getSummary() {
        return Prefers.getString(KEY_SUMMARY, "");
    }

    /** 上次扫描生成的站点数（0 = 一条都没生成，设置弹窗据此给出诊断）。 */
    public static int getSiteCount() {
        return lastSites;
    }

    /** 上次扫描的诊断：目录里实际有哪些类型、当前勾选了哪些。 */
    public static String getDiagnosis() {
        return Prefers.getString(KEY_HINT, "");
    }

    private static String diagnosis(Scan scan, JsonObject data) {
        List<String> picked = list(data, "scan_extensions");
        return "扫描目录 " + enabledScanDirs(data).size() + " 个 · 文件 " + scan.files + " · 命中 " + scan.accepted
            + " · 目录中实际类型：" + (scan.types.isEmpty() ? "未发现可识别类型" : join(scan.types, 12))
            + " · 当前勾选类型：" + (picked.isEmpty() ? "全部（未勾选）" : TextUtils.join("、", picked));
    }

    private static String join(Set<String> values, int limit) {
        List<String> items = new ArrayList<>(values);
        if (items.size() <= limit) return TextUtils.join("、", items);
        return TextUtils.join("、", items.subList(0, limit)) + "…(共 " + items.size() + " 种)";
    }

    /** 上次生成的文件路径（未生成时给出将要写入的路径）。 */
    public static String getOutputPath() {
        String saved = Prefers.getString(KEY_FILE, "");
        return TextUtils.isEmpty(saved) ? outputPath(settings()) : saved;
    }

    /**
     * r29-⑤⑥①：产物路径 = 第一个「已勾选」扫描目录 + 自定义文件名。
     *
     * <p>「生成文件路径」设置项已删除（r29-⑥①）：不再读 {@code output_path}，
     * 一律生成到用户勾选的扫描目录里；勾了多个就用列表里的第一个。
     * 用户填的文件名留空时用默认名 {@link #DEFAULT_OUT_NAME}（自动补 `.json`）。
     */
    public static String outputPath(JsonObject data) {
        List<String> dirs = enabledScanDirs(data);
        if (dirs.isEmpty()) return "";
        return new File(dirs.get(0), outName(data)).getAbsolutePath();
    }

    /** r29-⑤：产物文件名（含 `.json` 后缀；用户留空时用默认名）。 */
    public static String outName(JsonObject data) {
        String name = str(data, "out_name");
        if (TextUtils.isEmpty(name)) name = DEFAULT_OUT_NAME;
        name = name.trim();
        if (name.toLowerCase(Locale.US).endsWith(".json")) name = name.substring(0, name.length() - 5);
        return name + ".json";
    }

    /** r29-⑤：设置界面里回显的「文件名（不含后缀）」。 */
    public static String getOutName() {
        String name = str(settings(), "out_name");
        return TextUtils.isEmpty(name) ? DEFAULT_OUT_NAME : name.trim();
    }

    public static void setOutName(String name) {
        edit("out_name", new com.google.gson.JsonPrimitive(name == null ? "" : name.trim()));
    }

    /**
     * r29-⑤：这个文件名是不是「本壳产物」。
     *
     * <p>既认当前自定义名，也认 r29 之前的固定名 {@link #LEGACY_OUT_NAME}，
     * 保证老用户已有的 {@code 昏黄本地源.json} 不会被误判成「用户自写源」而被排除在自动回写之外。
     */
    private static boolean isProductName(String name, JsonObject data) {
        if (TextUtils.isEmpty(name)) return false;
        String base = name;
        if (base.toLowerCase(Locale.ROOT).endsWith(".json")) base = base.substring(0, base.length() - 5);
        return name.equalsIgnoreCase(outName(data)) || name.equalsIgnoreCase(LEGACY_OUT_NAME) || base.equalsIgnoreCase(LEGACY_VESPER_OUT_NAME);
    }


    private static boolean isProductName(String name) {
        return isProductName(name, settings());
    }

    public static List<String> getScanDirs() {
        return list(settings(), "scan_dirs");
    }

    /**
     * 扫描目录归一化：只存绝对路径，绝不存 file:// 写法。
     *
     * <p>文件夹选择器回传的常常是 file:// 串（应用内 FileActivity 回传 Uri.fromFile，
     * 手动粘贴也可能是 file:// 串），而扫描全程都用 new File(dir).isDirectory() 判断，
     * file:// 串直接当路径会永远不是目录、扫不出东西。所以在入库边界统一折算：
     * file:///storage/... → /storage/...；file://相对外部存储根（如 file://自己整理）
     * → /storage/emulated/0/自己整理；已经是绝对路径的原样保留，只去结尾斜杠。
     * content:// 等无法落到文件系统的写法返回空串，调用方按“未填”处理，不入库。
     */
    public static String normalizeScanDir(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        String value = raw.trim();
        if (value.startsWith("file:")) {
            String rest = value.substring(5);
            if (value.startsWith("file:///")) {
                value = "/" + rest.replaceFirst("^/+", "");
            } else if (value.startsWith("file://")) {
                String rel = rest.replaceFirst("^/+", "");
                value = rel.isEmpty() ? "" : EXTERNAL_BASES[0] + "/" + rel;
            } else {
                value = rest.startsWith("/") ? rest : EXTERNAL_BASES[0] + "/" + rest;
            }
            try {
                value = Uri.decode(value);
            } catch (Throwable ignored) {
            }
        } else if (value.startsWith("content:") || value.startsWith("http:") || value.startsWith("https:")) {
            return "";
        }
        value = value.replace('\\', '/');
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public static void addScanDir(String dir) {
        dir = normalizeScanDir(dir);
        if (TextUtils.isEmpty(dir)) return;
        JsonObject data = settings();
        List<String> dirs = list(data, "scan_dirs");
        if (!dirs.contains(dir)) dirs.add(dir);
        List<String> enabled = list(data, "scan_enabled_dirs");
        if (!enabled.contains(dir)) enabled.add(dir);
        data.add("scan_dirs", stringArray(dirs));
        data.add("scan_enabled_dirs", stringArray(enabled));
        saveSettings(data);
    }

    public static void removeScanDir(String dir) {
        dir = normalizeScanDir(dir);
        JsonObject data = settings();
        List<String> dirs = list(data, "scan_dirs");
        dirs.remove(dir);
        List<String> enabled = list(data, "scan_enabled_dirs");
        enabled.remove(dir);
        data.add("scan_dirs", stringArray(dirs));
        data.add("scan_enabled_dirs", stringArray(enabled));
        saveSettings(data);
    }

    public static boolean isDirEnabled(String dir) {
        return list(settings(), "scan_enabled_dirs").contains(normalizeScanDir(dir));
    }

    public static void setDirEnabled(String dir, boolean enabled) {
        dir = normalizeScanDir(dir);
        JsonObject data = settings();
        List<String> dirs = list(data, "scan_dirs");
        List<String> active = list(data, "scan_enabled_dirs");
        if (enabled) {
            if (!active.contains(dir)) active.add(dir);
        } else {
            active.remove(dir);
        }
        data.add("scan_enabled_dirs", stringArray(filterActive(dirs, active)));
        saveSettings(data);
    }

    private static List<String> filterActive(List<String> dirs, List<String> active) {
        List<String> result = new ArrayList<>();
        for (String dir : dirs) if (active.contains(dir)) result.add(dir);
        return result;
    }

    /** 全部可选扫描类型（设置界面勾选用）。 */
    public static List<String> allExtensions() {
        return new ArrayList<>(Arrays.asList(ALL_EXTENSIONS));
    }

    /** r15：是否用生成结果顶替当前点播接口的站点列表（默认开）。 */
    public static boolean isReplace() {
        return bool(settings(), "replace_sites", true);
    }

    public static void setReplace(boolean value) {
        edit("replace_sites", new com.google.gson.JsonPrimitive(value));
    }

    /**
     * 忽略目录名候选（r16-③）：只列扫描目录里**真实存在**的目录名。
     * 不再把「已保存但目录里已经没有」的名字混进来，列表与目录一一对应。
     * 候选遍历不看忽略规则，因此已被忽略的目录也会照常出现，才能被取消勾选。
     */
    public static List<String> dirCandidates() {
        return candidates(true);
    }

    private static List<String> candidates(boolean wantDirs) {
        JsonObject data = settings();
        Set<String> scanned = new LinkedHashSet<>();
        String base = outputBaseDir(data);
        for (String dir : enabledScanDirs(data)) {
            File path = new File(dir);
            if (!path.isDirectory()) continue;
            collect(path, 0, wantDirs, scanned, data, base);
        }
        List<String> all = new ArrayList<>(scanned);
        if (!wantDirs) all.sort(LocalSourceHub::compareGbk);
        if (all.size() > MAX_CANDIDATES) return new ArrayList<>(all.subList(0, MAX_CANDIDATES));
        return all;
    }

    /**
     * 扫描类型候选（r16-⑤）：勾选列表按扫描目录里**实际存在的后缀**生成。
     * 空候选时回落到全部类型，避免列表是空的没法选。
     */
    public static List<String> extensionCandidates() {
        JsonObject data = settings();
        Set<String> found = new LinkedHashSet<>();
        String base = outputBaseDir(data);
        for (String dir : enabledScanDirs(data)) {
            File path = new File(dir);
            if (path.isDirectory()) collectTypes(path, 0, found, data, base);
        }
        List<String> ordered = new ArrayList<>();
        for (String type : ALL_EXTENSIONS) if (found.contains(type)) ordered.add(type);
        if (ordered.isEmpty()) ordered.addAll(allExtensions());
        return ordered;
    }

    private static void collectTypes(File dir, int depth, Set<String> out, JsonObject data, String base) {
        if (depth > maxDepth(data)) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (left, right) -> compareGbk(left.getName(), right.getName()));
        for (File file : files) {
            if (file.isDirectory()) {
                collectTypes(file, depth + 1, out, data, base);
            } else if (file.isFile()) {
                String suffix = extOf(file.getName()).suffix;
                if (!TextUtils.isEmpty(suffix) && Arrays.asList(ALL_EXTENSIONS).contains(suffix)) out.add(suffix);
            }
        }
    }

    private static void collect(File dir, int depth, boolean wantDirs, Set<String> out, JsonObject data, String base) {
        if (depth > maxDepth(data) || out.size() >= MAX_CANDIDATES) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (left, right) -> compareGbk(left.getName(), right.getName()));
        for (File file : files) {
            if (out.size() >= MAX_CANDIDATES) return;
            if (file.isDirectory()) {
                if (wantDirs) out.add(file.getName());
                collect(file, depth + 1, wantDirs, out, data, base);
            } else if (!wantDirs && file.isFile() && !isRuntimeGenerated(file, data, base)) {
                out.add(file.getName());
            }
        }
    }

    public static List<String> getScanExtensions() {
        return list(settings(), "scan_extensions");
    }

    public static void setScanExtensions(List<String> extensions) {
        JsonObject data = settings();
        Set<String> keep = new LinkedHashSet<>();
        for (String ext : extensions) if (Arrays.asList(ALL_EXTENSIONS).contains(ext)) keep.add(ext);
        data.add("scan_extensions", stringArray(new ArrayList<>(keep)));
        saveSettings(data);
    }

    // ==================== r17-⑤ spider jar / 直播源地址 ====================

    /**
     * spider jar（全局源包）的「有效值」：
     * <ol>
     *     <li>设置里存过非空值就按存的来（用户手动选的 jar）——指定优先，jar 文件夹里
     *     有没有默认 jar 都按指定的写；</li>
     *     <li>没设置过、或存的是空串（以前“清除”留下的空、或旧版探测失败留下的空），
     *     回到自动探测：按脚本原值（{@code CONFIG_SPIDER}，{@code ./jar/xbpq251118.jar}，
     *     相对生成文件所在目录展开，文件不在就按文件名在扫描目录里找）探到文件才用它，
     *     探不到返回空、产物不写 spider 行。</li>
     * </ol>
     * <p>沿革：最早“存空 = 主动清空 → 不写”会让点过清除的用户生成永远缺 spider
     * （2026-10-27 修）；当时改成“探测不到也硬写脚本默认 ./ 写法”，但有些用户的本地
     * 配置根本没有 json 源、jar 文件夹里也没有这个 jar，硬写一行指向不存在文件的
     * spider 只会拖累配置加载——现按 Liu 定：没指定且探测不到就不写（2026-11-06）。
     * “清除” = 清掉手动选择、回到自动探测。
     */
    public static String getSpiderJar() {
        JsonObject data = settings();
        if (data.has("spider_jar")) {
            String stored = str(data, "spider_jar");
            if (!TextUtils.isEmpty(stored)) return stored;
        }
        return getDefaultSpiderJar(data);
    }

    /** 不看已存值、只算默认：探测到的绝对路径；探测不到返回空（产物不写 spider）。 */
    public static String getDefaultSpiderJar(JsonObject data) {
        return detectSpiderJar(data);
    }

    public static String getDefaultSpiderJar() {
        return getDefaultSpiderJar(settings());
    }

    public static void setSpiderJar(String path) {
        edit("spider_jar", new com.google.gson.JsonPrimitive(path == null ? "" : path.trim()));
    }

    private static String detectSpiderJar(JsonObject data) {
        String seed = assetString("CONFIG_SPIDER", "");
        if (TextUtils.isEmpty(seed)) return "";
        String base = outputBaseDir(data);
        if (!TextUtils.isEmpty(base)) {
            // 去掉开头的 "./" 再拼接，避免 getAbsolutePath() 里留下 "/./"（否则产物写成 "././jar/x.jar"）
            String relative = seed.startsWith("./") ? seed.substring(2) : seed;
            File direct = new File(base, relative);
            if (direct.isFile()) return direct.getAbsolutePath();
        }
        String name = new File(seed).getName();
        if (TextUtils.isEmpty(name)) return "";
        File found = findAnyFile(enabledScanDirs(data), name, maxDepth(data));
        return found == null ? "" : found.getAbsolutePath();
    }

    /**
     * logo / wallpaper 本地图探测：生成文件旁的 jar 文件夹里按 gif > jpg > png > webp 找
     * （{@code <base>/jar/logo.gif} 等），找到返回脚本写法 {@code ./jar/logo.gif}；
     * 四个都没有返回空——产物不写这行，壳子用自带默认 logo / 壁纸。
     */
    private static String detectJarImage(String base, String name) {
        if (TextUtils.isEmpty(base)) return "";
        for (String ext : new String[]{"gif", "jpg", "png", "webp"}) {
            if (new File(base, "jar/" + name + "." + ext).isFile()) return "./jar/" + name + "." + ext;
        }
        return "";
    }

    /**
     * r20-①：直播源地址只认「用户自己填过的值」。
     *
     * <p>老实现没填过时回落到脚本内置的 {@code DEFAULT_LIVES} 地址，设置弹窗用它当输入框初始值，
     * 保存时又被当成用户值写进设置 —— 于是「新增 / 编辑本地源」时直播源地址总是自带一个默认地址、
     * 产物里还总会多出一条直播。现在：
     * <ul>
     *     <li>没设置过 → 空（产物里不写这条直播）；</li>
     *     <li>设置里的值恰好等于脚本内置默认值 → 同样按「用户没填」处理（老版本回填后存下来的就是这个值，
     *     不能让它继续被当成用户值写进产物；这里只判断不清理，用户的原始设置字符串不动）；</li>
     *     <li>用户自己填过（含主动清空成的空串）→ 原样保留，编辑时照旧回显、产物里照旧写。</li>
     * </ul>
     */
    public static String getLiveUrl() {
        JsonObject data = settings();
        if (!data.has("live_url")) return "";
        String url = str(data, "live_url");
        return url.equals(assetLiveUrl()) ? "" : url;
    }

    public static void setLiveUrl(String url) {
        edit("live_url", new com.google.gson.JsonPrimitive(url == null ? "" : url.trim()));
    }

    /** 用户脚本 DEFAULT_LIVES 里写死的直播源地址。 */
    private static String assetLiveUrl() {
        try {
            JsonElement element = asset().get("DEFAULT_LIVES");
            if (element == null || !element.isJsonArray()) return "";
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                String url = str(item.getAsJsonObject(), "url");
                if (!TextUtils.isEmpty(url)) return url;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** spider jar 候选（r29-⑥②）：只列「已勾选」扫描目录里深度 ≤ max_depth 的所有 .jar。 */
    public static List<String> jarCandidates() {
        JsonObject data = settings();
        List<String> dirs = enabledScanDirs(data);
        List<String> found = new ArrayList<>();
        for (String dir : dirs) {
            File path = new File(dir);
            if (path.isDirectory()) collectFiles(path, 0, maxDepth(data), ".jar", found);
        }
        found.sort(LocalSourceHub::compareGbk);
        if (found.size() > MAX_CANDIDATES) return new ArrayList<>(found.subList(0, MAX_CANDIDATES));
        return found;
    }

    private static void collectFiles(File dir, int depth, int maxDepth, String suffix, List<String> out) {
        if (depth > maxDepth || out.size() >= MAX_CANDIDATES) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (left, right) -> compareGbk(left.getName(), right.getName()));
        for (File file : files) {
            if (out.size() >= MAX_CANDIDATES) return;
            if (file.isDirectory()) collectFiles(file, depth + 1, maxDepth, suffix, out);
            else if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(suffix)) out.add(file.getAbsolutePath());
        }
    }

    private static File findAnyFile(List<String> dirs, String name, int maxDepth) {
        for (String dir : dirs) {
            File found = findAnyFile(new File(dir), name, 0, maxDepth);
            if (found != null) return found;
        }
        return null;
    }

    private static File findAnyFile(File dir, String name, int depth, int maxDepth) {
        if (depth > maxDepth || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) if (file.isFile() && file.getName().equals(name)) return file;
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findAnyFile(file, name, depth + 1, maxDepth);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ==================== r17-②③ 源分类收口 ====================

    /**
     * 分类只允许是「文件夹名」：把每条源名字里**不是扫描目录下真实文件夹名**的 {@code [xx]} 全部删掉。
     *
     * <p>App 的「源分类」筛选直接抓名字里的 {@code [xx]}（{@code Site.GROUP_PATTERN}），所以内置附加源
     * 与扫描结果一样要收口 —— 不再有「主页 / 设置」这类例外。只改 name 不动 key（key 只是条目 id，
     * 改 key 反而可能与其它条目撞车）。返回被改动过的条数。
     */
    private static int enforceFolderCategories(List<JsonObject> sites, Set<String> categories) {
        int changed = 0;
        for (JsonObject site : sites) {
            String name = str(site, "name");
            if (TextUtils.isEmpty(name)) continue;
            String cleaned = dropUnknownCategories(name, categories);
            if (TextUtils.isEmpty(cleaned) || TextUtils.equals(cleaned, name)) continue;
            site.addProperty("name", cleaned);
            changed++;
        }
        return changed;
    }

    /** 删掉名字里不是文件夹名的 {@code [标签]}，其余字符原样保留。 */
    private static String dropUnknownCategories(String name, Set<String> categories) {
        if (TextUtils.isEmpty(name)) return "";
        java.util.regex.Matcher matcher = TAG_PATTERN.matcher(name);
        StringBuilder builder = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            if (!isFolderCategory(categories, plain(matcher.group(1)))) continue;
            builder.append(name, last, matcher.start());
            builder.append(name, matcher.start(), matcher.end());
            last = matcher.end();
        }
        builder.append(name.substring(last));
        return builder.toString().trim();
    }

    private static boolean isFolderCategory(Set<String> categories, String tag) {
        if (TextUtils.isEmpty(tag)) return false;
        if (categories.contains(tag)) return true;
        String lower = tag.toLowerCase(Locale.US);
        for (String category : categories) if (category.toLowerCase(Locale.US).equals(lower)) return true;
        return false;
    }

    public static String getBlockedDirs() {
        return TextUtils.join(",", list(settings(), "blocked_dirs"));
    }

    public static void setBlockedDirs(String dirs) {
        edit("blocked_dirs", stringArray(split(dirs)));
    }

    // r29-⑥：「忽略文件名」功能已取消 —— getExcludedFiles / setExcludedFiles 一并删除，
    // 扫描流程不再按文件名跳过任何文件（只保留产物自身与 *_settings.json 的自引用保护）。

    public static boolean isDecodeBase64() {
        return bool(settings(), "decode_base64", true);
    }

    public static void setDecodeBase64(boolean value) {
        edit("decode_base64", new com.google.gson.JsonPrimitive(value));
    }

    public static JsonArray getParseEntries() {
        JsonObject data = settings();
        JsonElement entries = data.get("parse_entries");
        return entries != null && entries.isJsonArray() ? entries.getAsJsonArray() : assetParses(true);
    }

    public static void setParseEntries(JsonArray entries) {
        edit("parse_entries", entries == null ? new JsonArray() : entries);
    }

    /** 设置界面用的一行状态文本。 */
    public static String getStatusText() {
        if (!isEnabled()) return "未启用";
        List<String> dirs = getScanDirs();
        if (dirs.isEmpty()) return "未设置文件夹";
        String summary = getSummary();
        return TextUtils.isEmpty(summary) ? "共 " + dirs.size() + " 个目录，待扫描" : summary;
    }

    // ==================== 对外主流程 ====================

    /**
     * 注入当前配置对象：把本地扫描到的 sites / lives 追加进配置 json。
     * 由 {@code VodConfig.parseConfig} 在解析前调用，因此每次加载源都会自动刷新一次。
     */
    public static void inject(JsonObject config) {
        if (config == null || !isEnabled()) return;
        if (enabledScanDirs(settings()).isEmpty()) return;
        // r28-① 两套规则彻底剥离：当前加载的是「你自己编写的本地源配置」（本地文件型、且不是本壳
        // 产物「昏黄本地源.json」，例如 file://自用2026plus/家享plus.json）时，自动扫描生成的源
        // 一律不注入 —— 你在这套规则下只看到自己写的源与自己的分类，自动扫描那套不再掺进来。
        // （实测：以前加载自己的配置时被追加 1103 条扫描源，源列表里全是自动生成的分类。）
        // 自动扫描那套只在加载本壳产物或远程订阅配置时生效，两套互不干扰。
        String configUrl = currentConfigUrl == null ? "" : currentConfigUrl.trim();
        // r29（Liu 2026-11-06 定）：本地源彻底独立 —— 自动扫描生成的源只活在本壳产物配置里，
        // 不再注入任何其它配置（在线源、用户自有本地配置一律不掺，源列表保持各归各）。
        // 只有加载产物本身时才走下面的追加/回写：append 按 key 去重保证幂等，
        // 顺带把产物刷新到最新扫描结果。
        if (!isAppProductConfig(configUrl)) {
            DebugLogStore.add(TAG, "自动扫描源已跳过：本地源只活在产物配置里，不注入当前配置（" + configUrl + "）");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            JsonObject built = build(!isAuto());
            // r18-⑪：启用本地源不再屏蔽（清空）订阅里原有的点播站点 —— 统一改成「追加」：
            // 原订阅的站点 / 解析 / 规则 / 直播 / 壁纸全部保留（旧的 replace_sites 清空逻辑已移除）。
            JsonArray builtSites = built.getAsJsonArray("sites");
            int sites = append(config, "sites", "key", builtSites, MAX_SITES);
            int lives = append(config, "lives", "name", built.getAsJsonArray("lives"), MAX_LIVES);
            if (!config.has("spider") && built.has("spider")) config.addProperty("spider", built.get("spider").getAsString());
            resolveRelative(config, "");
            // r18-④：本地源自动回写 —— 本地源参与解析后把最新生成结果落盘到输出 json，
            // 用户不再需要手点「生成并加载」；内容未变或距上次写入不足 1 秒时跳过，避免反复写盘。
            autoWrite(built);
            DebugLogStore.add(TAG, "本地源注入完成（" + (System.currentTimeMillis() - start) + "ms）：站点 +" + sites + "，直播 +" + lives);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "本地源注入失败：" + e + android.util.Log.getStackTraceString(e));
        }
    }

    private static int append(JsonObject config, String arrayKey, String keyField, JsonArray items, int cap) {
        if (items == null || items.isEmpty()) return 0;
        JsonArray target = config.has(arrayKey) && config.get(arrayKey).isJsonArray() ? config.getAsJsonArray(arrayKey) : new JsonArray();
        // r20-③：顺手清掉老实现（r19-#3 之前）留下的重复项，让用户机器上已生成的 json 自愈。
        pruneLegacyTwins(target, keyField);
        Set<String> exists = new LinkedHashSet<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        // r20-⑬：再按「物理源文件路径」建一份索引。同一个源文件在配置里与扫描产物里可能叫完全不同的名字、
        // api 写法也不同（配置里 17C[胡佬](XBPQ)、扫描产物 17C[胡佬](json)，但 ext 都是 ./PQ类/[胡佬]/17C.json），
        // 只比名字 + api 的指纹识别不出这种重复，用户看到的就是同一条源列两行。
        Set<String> paths = new LinkedHashSet<>();
        for (JsonElement item : target) if (item.isJsonObject()) {
            JsonObject object = item.getAsJsonObject();
            exists.add(str(object, keyField));
            fingerprints.add(fingerprint(object, keyField));
            // r25-②：物理文件身份改用 fileKeyOf（归一化绝对路径 key），生成侧的 ./x.json 与配置侧的
            // file://…/x.json 才会算成同一份源；老实现比的是书写形式，写法一变就漏判成两份。
            String path = fileKeyOf(object);
            if (!TextUtils.isEmpty(path)) paths.add(path);
        }
        int added = 0;
        int skipped = 0;
        for (JsonElement item : items) {
            if (!item.isJsonObject() || target.size() >= cap) break;
            JsonObject object = item.getAsJsonObject().deepCopy();
            // r19-#3：同一条源（同名 + 同一 api/url）已在配置里 → 直接跳过。
            // 旧实现遇到同名 key 会改名成 key_2 再追加，于是「配置里本来就有的源」在列表里显示成两份
            // （生成的 json 里却没有重复，用户看到的就是这种「凭空多一份」的重复）。
            if (fingerprints.contains(fingerprint(object, keyField))) continue;
            // r20-⑬ / r25-②：这个源文件已经在配置里了（不管叫什么名字、地址怎么写）→ 也跳过，否则列表里会多出一份。
            String path = fileKeyOf(object);
            if (!TextUtils.isEmpty(path) && paths.contains(path)) {
                skipped++;
                continue;
            }
            String key = str(object, keyField);
            if (!TextUtils.isEmpty(key)) {
                String unique = key;
                int index = 1;
                while (exists.contains(unique)) unique = key + "_" + (++index);
                if (!unique.equals(key)) object.addProperty(keyField, unique);
                exists.add(unique);
            }
            target.add(object);
            fingerprints.add(fingerprint(object, keyField));
            if (!TextUtils.isEmpty(path)) paths.add(path);
            added++;
        }
        if (!config.has(arrayKey)) config.add(arrayKey, target);
        if (skipped > 0) DebugLogStore.add(TAG, "按源文件路径跳过重复注入 " + skipped + " 条（" + arrayKey + "）");
        return added;
    }

    /**
     * r20-⑬：一条源条目的「物理源文件身份」——跨命名识别同一条源。
     *
     * <p>配置里已有的源和自动扫描追加的源，可能名字、引擎标签都不一样，却指向同一个源文件：
     * 用户在配置里是 {@code 17C[胡佬](XBPQ)}（api = {@code csp_XBPQ}、ext = {@code ./PQ类/[胡佬]/17C.json}），
     * 壳端扫描追加的是 {@code 17C[胡佬](json)}，两者 ext 完全相同、名字与 api 都不同 ——
     * 只比「名字 + api」的 {@link #fingerprint} 永远识别不出这种重复，用户看到的就是同一条源列两行。
     *
     * <p>这里统一取「归一化后的源文件路径」当身份：按 ext → homePage → api 顺序挑第一个像文件路径的字段
     * （顺序与 {@link #sourcePaths} 一致，避免把 {@code csp_XXX} 引擎名、drpy 引擎库当源文件），
     * 绝对路径再折算成相对某个启用扫描目录的写法，保证「配置侧」与「生成侧」算出来的身份一致。
     * 拿不到文件路径（远程订阅源等）时返回 null，调用方自行退化为其它判重方式。
     */
    public static String sourcePathOf(String api, String ext, String homePage) {
        return sourcePathOf(api, ext, homePage, settings());
    }

    /**
     * r60-③：{@link #sourcePathOf} 的「设置快照」版本 —— 批量算身份时只取一次设置，
     * 避免每条源都重走一遍 {@code settings()}（内含 assets 读取与 JSON 深拷贝）。
     */
    private static String sourcePathOf(String api, String ext, String homePage, JsonObject data) {
        for (String raw : new String[]{ext, homePage, api}) {
            if (TextUtils.isEmpty(raw)) continue;
            List<String> paths = parseSourcePath(raw);
            if (!paths.isEmpty()) return relativeSourcePath(paths.get(0), data);
        }
        return null;
    }

    /**
     * r60-③：批量取「源文件物理身份」—— 整张源列表只取一次设置快照。
     *
     * <p>调用方是 {@code SiteAdapter#addAll()}：打开源搜索弹窗时要把上千条源逐条算一遍身份用于去重，
     * 旧实现每条都调 {@link #sourcePathOf(String, String, String)}（→ settings() → asset() 读文件），
     * 上千次 IO + JSON 解析全在 UI 线程上，就是用户反馈的「进入源搜索界面卡近 1 秒」。</p>
     *
     * <p>单条异常不影响整表（该条身份记 null，退化为「源名 + api」判重）。</p>
     */
    public static Map<Site, String> sourcePathsOf(List<Site> sites) {
        Map<Site, String> result = new LinkedHashMap<>();
        if (sites == null || sites.isEmpty()) return result;
        JsonObject snapshot;
        try {
            snapshot = settings();
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "批量算源身份：设置读取失败 " + e);
            snapshot = new JsonObject();
        }
        long startedAt = System.currentTimeMillis();
        int hits = 0;
        for (Site site : sites) {
            if (site == null) continue;
            try {
                String api = site.getApi() == null ? "" : site.getApi();
                String ext = site.getExt() == null ? "" : site.getExt();
                String key = api + "\u0001" + ext;
                String cached = sourcePathCache.get(key);
                if (cached != null) {
                    hits++;
                    result.put(site, NULL_PATH_SENTINEL.equals(cached) ? null : cached);
                } else {
                    if (sourcePathCache.size() >= SOURCE_PATH_CACHE_CAP) sourcePathCache.clear();
                    String path = sourcePathOf(site.getApi(), site.getExt(), null, snapshot);
                    sourcePathCache.put(key, path == null ? NULL_PATH_SENTINEL : path);
                    result.put(site, path);
                }
            } catch (Throwable e) {
                result.put(site, null);
            }
        }
        long cost = System.currentTimeMillis() - startedAt;
        if (cost >= 5) DebugLogStore.add(TAG, "批量算源身份：" + sites.size() + " 条，" + cost + "ms（命中缓存 " + hits + "）");
        return result;
    }


    /** {@link #sourcePathOf} 的 JsonObject 版本；配置里的 {@code ext} 允许是对象，取值必须防崩。 */
    private static String sourcePathOf(JsonObject object) {
        if (object == null) return null;
        return sourcePathOf(primitiveString(object, "api"), primitiveString(object, "ext"), primitiveString(object, "homePage"));
    }

    /** 只取字符串型字段：历史配置里出现过 {@code ext} 是对象，直接 {@code getAsString()} 会抛异常。 */
    private static String primitiveString(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        try {
            JsonElement element = object.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 折算成「相对某个启用扫描目录」的写法（与生成期的 {@code ./相对路径} 对齐）。
     *
     * <p>r25-②：相对写法里可能带着扫描目录自身的名字（{@code 自己整理 (1)/PQ类/a.json}，相对外部存储根）、
     * 或者整段被 URL 编码过；绝对写法也可能落在外部存储根而不是扫描目录里。
     * 这些写法以前会原样返回，导致「配置侧」与「生成侧」算出来的物理身份对不上（去重失效、判成外地源）。
     */
    private static String relativeSourcePath(String path) {
        return relativeSourcePath(path, settings());
    }

    /** r60-③：{@link #relativeSourcePath(String)} 的「设置快照」版本（批量算身份时复用同一份设置）。 */
    private static String relativeSourcePath(String path, JsonObject data) {
        if (TextUtils.isEmpty(path)) return path;
        if (!path.startsWith("/")) {
            String value = path;
            try {
                value = Uri.decode(value);
            } catch (Throwable ignored) {
            }
            try {
                for (String dir : sourceDirs(data)) {
                    String name = new File(dir).getName();
                    if (TextUtils.isEmpty(name)) continue;
                    String prefix = name + "/";
                    if (value.startsWith(prefix)) return value.substring(prefix.length());
                }
            } catch (Throwable ignored) {
            }
            return value;
        }
        try {
            for (String dir : sourceDirs(data)) {
                String base = dir.endsWith("/") ? dir : dir + "/";
                if (path.startsWith(base)) return path.substring(base.length());
            }
            for (String root : EXTERNAL_BASES) {
                String base = root + "/";
                if (path.startsWith(base)) return path.substring(base.length());
            }
        } catch (Throwable ignored) {
        }
        return path;
    }

    /** r19-#3 / r20-⑭：一条源的「同一性」指纹 = 名字 + 归一化后的文件/接口地址，用于识别被重复追加的同一条源。 */
    private static String fingerprint(JsonObject object, String keyField) {
        String value = str(object, "api");
        if (TextUtils.isEmpty(value)) value = str(object, "url");
        return str(object, keyField) + "\u0000" + normalizeApi(value);
    }

    /**
     * r20-⑭：接口地址归一化 —— 同一个源文件在「生成侧」与「订阅侧」可能写成完全不同的形态：
     * {@code ./PY类/a.py}、{@code PY类/a.py}、{@code /storage/…/PY类/a.py}、
     * {@code file:///storage/…/a.py}、{@code http://127.0.0.1:9978/file//storage/…/a.py}
     * （运行时被 {@link #resolveRelative} 与 {@code UrlUtil.convert} 展开，订阅 json 里往往是另一种写法）。
     * 只比原始字符串永远认不出是同一个源，用户看到的就是同一条源列两行。
     * 归一化后统一成「相对生成目录 / 扫描目录的路径」；拿不到文件路径的（{@code csp_XXX} 引擎名、
     * 远程 http 订阅地址）回落原值，保证引擎型源仍然按引擎名判重。
     */
    public static String normalizeApi(String api) {
        String raw = api == null ? "" : api.trim();
        if (TextUtils.isEmpty(raw)) return "";
        try {
            List<String> paths = parseSourcePath(raw);
            if (!paths.isEmpty()) return relativeSourcePath(paths.get(0));
        } catch (Throwable ignored) {
        }
        return raw;
    }

    /**
     * r20-③：清掉老实现（r19-#3 之前）留在配置里的重复项。
     *
     * <p>老 append 遇到同名 key 会把同一条源改名成 {@code key_2 / key_3} 再追加，于是列表里同一条源
     * 显示成两份（显示名完全相同）——用户看到的就是这种「凭空多一份」的重复。判定三连：
     * key 形如 {@code base_N} ＋ 数组里确实存在 {@code base} ＋ 两者「名字 + api/url」一致
     * → 删除 {@code base_N} 那一条。只删这种成对残留，不动其它任何条目。
     */
    private static void pruneLegacyTwins(JsonArray target, String keyField) {
        Map<String, String> origins = new LinkedHashMap<>();
        for (JsonElement item : target) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String key = str(object, keyField);
            if (TextUtils.isEmpty(key) || legacyBase(key) != null || origins.containsKey(key)) continue;
            origins.put(key, fingerprint(object, keyField));
        }
        List<JsonElement> drop = new ArrayList<>();
        for (JsonElement item : target) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String base = legacyBase(str(object, keyField));
            if (base == null) continue;
            String origin = origins.get(base);
            if (origin != null && origin.equals(fingerprint(object, keyField))) drop.add(item);
        }
        for (JsonElement item : drop) target.remove(item);
        if (!drop.isEmpty()) DebugLogStore.add(TAG, "清理历史重复源 " + drop.size() + " 条（" + keyField + "）");
    }

    /** {@code xxx_2} → {@code xxx}；不是这种「老实现改名」形式时返回 null。 */
    private static String legacyBase(String key) {
        if (TextUtils.isEmpty(key)) return null;
        int cut = key.lastIndexOf('_');
        if (cut <= 0 || cut >= key.length() - 1) return null;
        String suffix = key.substring(cut + 1);
        for (int i = 0; i < suffix.length(); i++) if (!Character.isDigit(suffix.charAt(i))) return null;
        return key.substring(0, cut);
    }

    /** 手动扫描（设置界面按钮 / 注入共用）。force = true 时忽略缓存强制重扫。 */
    public static JsonObject build(boolean force) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (!force && cache != null && now - cacheAt < MIN_INTERVAL) return cache;
            cache = scan();
            cacheAt = now;
            return cache;
        }
    }

    /** 扫描并落盘（设置界面「生成并加载」按钮）。返回完整配置对象。 */
    public static JsonObject generate() {
        JsonObject config = build(true);
        String path = write(config);
        // r26-②：被自写源保护拦下时（path == null）不覆盖已记录的上次生成路径
        if (path != null) Prefers.put(KEY_FILE, path);
        return config;
    }

    /**
     * r18-④：本地源自动回写。
     *
     * <p>把最新生成结果自动写到产物 json（{@link #DEFAULT_OUT_NAME}，输出路径与「生成并加载」按钮一致），
     * 这样本地源一直在用时，磁盘上的 json 始终跟界面设置保持一致，不需要手动点生成。
     * 两道闸门：正文签名相同（内容没变）→ 跳过；距上次写入不足 {@link #AUTO_WRITE_INTERVAL} → 跳过。
     */
    private static void autoWrite(JsonObject config) {
        autoWrite(config, false);
    }

    /** r18-⑩：{@code force=true} 时不看本地源总开关（删源后必须把 json 写回去）。 */
    private static void autoWrite(JsonObject config, boolean force) {
        try {
            if (config == null) return;
            if (!force && !isEnabled()) return;
            String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(config);
            synchronized (LOCK) {
                if (text.equals(autoWriteHash)) return;
                long now = System.currentTimeMillis();
                // r20-⑭：force（删源后的回写）不受「距上次写入不足 AUTO_WRITE_INTERVAL」限制 ——
                // 删源往往紧跟在一次加载触发的自动回写之后，旧实现会把这次回写静默吞掉，
                // 用户看到的就是「文件删了、昏黄本地源.json 里的条目还在」。
                if (!force && now - autoWriteAt < AUTO_WRITE_INTERVAL) return;
                autoWriteAt = now;
                autoWriteHash = text;
            }
            String path = write(config);
            if (path != null) Prefers.put(KEY_FILE, path);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "本地源自动回写失败：" + e);
        }
    }

    /** r18-④：设置变更后在后台线程强制重扫并回写（设置弹窗里的任何开关/勾选都会走这里）。 */
    public static void autoWriteAsync() {
        if (!isEnabled()) return;
        if (autoWriteRunning) return;
        autoWriteRunning = true;
        new Thread(() -> {
            try {
                autoWrite(build(true));
            } catch (Throwable e) {
                DebugLogStore.add(TAG, "本地源自动回写（后台）失败：" + e);
            } finally {
                autoWriteRunning = false;
            }
        }, "local-source-autowrite").start();
    }

    // ==================== r63-③：批量搬家（几十个源时把 O(N×全量扫描) 降到 O(1×)） ====================

    /**
     * 批量搬家的线程局部合并状态。
     *
     * <p>为什么需要：旧实现每搬一个源就走一遍
     * 「解析设置 → 定位文件 → 改写扫描目录里<b>每一个</b> json → 触发一次全量重扫回写」。
     * 用户一次勾 30 个源，等于 30 次全量扫描 + 30 次全量 json 解析/写盘，
     * 耗时长、IO 打满，壳端就卡死（用户报的「移动几十个源很慢且卡顿」）。</p>
     *
     * <p>现在：批量期间只在内存里记录「哪些文件的路径改成了什么」，最后统一改写一轮 json、
     * 同步回写一次产物。搬迁本身的语义（保持子目录结构、撞名不动、自写源不动）完全不变。</p>
     */
    private static final class Batch {
        final JsonObject data = settings();
        /** 每条：{旧绝对路径, 新绝对路径, json 里的新地址, 新文件名} */
        final List<String[]> rewrites = new ArrayList<>();
    }

    private static final ThreadLocal<Batch> BATCH = new ThreadLocal<>();

    /** 开始批量搬家（同一个线程内配对调用 {@link #endBatch()}，必须写在 finally 里）。 */
    public static void beginBatch() {
        if (BATCH.get() == null) BATCH.set(new Batch());
    }

    /** 当前线程是否处于批量搬家模式。 */
    public static boolean inBatch() {
        return BATCH.get() != null;
    }

    /**
     * 结束批量搬家：把攒下的路径改写一次性落到 json，再同步回写一次产物。
     *
     * <p>无论中间是否有源搬家失败，都要走到这里（批量标记必须清掉，且界面要拿到最新 json）。</p>
     *
     * @return 产物 json 是否写成功（含义与 {@link #refreshOutputBlocking()} 一致）
     */
    public static boolean endBatch() {
        Batch batch = BATCH.get();
        if (batch == null) return false;
        try {
            if (!batch.rewrites.isEmpty()) {
                int rewritten = rewriteJsonPathsMerged(batch.rewrites, batch.data);
                DebugLogStore.add(TAG, "批量搬家：合并改写 json 指向 " + rewritten + " 处（" + batch.rewrites.size() + " 个源）");
            }
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "批量搬家合并改写 json 失败：" + e);
        } finally {
            BATCH.remove();
        }
        return refreshOutputBlocking();
    }

    /** 批量期间：不在每个源搬完后触发全量重扫（末尾由 {@link #endBatch()} 统一做一次）。 */
    private static void refreshAfterMove() {
        if (inBatch()) return;
        refreshOutput();
    }

    /**
     * r63-③：一轮读完 / 一轮写完的合并版 json 改写。
     *
     * <p>与逐条 {@link #rewriteJsonPaths} 的差别：① 每个配置 json 只读一次、解析一次、写一次；
     * ② <b>跳过自动生成的产物 json</b>（它马上会被 {@link #refreshOutputBlocking()} 整份重建，
     * 再改写一遍纯属浪费 —— 这正是旧实现最贵的一步）。</p>
     */
    private static int rewriteJsonPathsMerged(List<String[]> rewrites, JsonObject data) {
        if (rewrites == null || rewrites.isEmpty()) return 0;
        Map<String, String[]> byOld = new HashMap<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String[] item : rewrites) {
            if (item == null || item.length < 4) continue;
            byOld.put(item[0], item);
            if (!TextUtils.isEmpty(item[3])) names.add(item[3]);
        }
        if (byOld.isEmpty()) return 0;
        String output = outputPath(data);
        String outputAbs = TextUtils.isEmpty(output) ? "" : new File(output).getAbsolutePath();
        int total = 0;
        for (File json : configJsonFiles(data)) {
            try {
                if (json.getAbsolutePath().equals(outputAbs)) continue;
                if (isUserOwnedConfig(json.getAbsolutePath())) continue;
                String text = readTextFile(json);
                if (TextUtils.isEmpty(text) || text.length() > MAX_JSON_CHARS) continue;
                // 廉价筛查：一个改名后的文件名都没出现，就不可能指向这些源
                boolean hit = false;
                for (String name : names) {
                    if (text.contains(name) || text.contains(Uri.encode(name))) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) continue;
                JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                int count = rewriteJsonArrayMerged(root.get("sites"), byOld, data)
                    + rewriteJsonArrayMerged(root.get("lives"), byOld, data);
                if (count == 0) continue;
                writeTextFile(json, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
                total += count;
                DebugLogStore.add(TAG, "已改写 json 指向：" + json.getAbsolutePath() + "（" + count + " 处）");
            } catch (Throwable e) {
                DebugLogStore.add(TAG, "批量改写 json 失败（跳过 " + json.getName() + "）：" + e);
            }
        }
        return total;
    }

    /** r63-③：sites / lives 里指向「本批任意一个旧路径」的地址字段改成对应新地址。 */
    private static int rewriteJsonArrayMerged(JsonElement array, Map<String, String[]> byOld, JsonObject data) {
        if (array == null || !array.isJsonArray()) return 0;
        int count = 0;
        for (JsonElement element : array.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            for (String key : new String[]{"ext", "homePage", "api"}) {
                String raw = primitiveString(item, key);
                if (TextUtils.isEmpty(raw)) continue;
                String[] matched = null;
                for (String candidate : absoluteCandidates(raw, data)) {
                    matched = byOld.get(candidate);
                    if (matched != null) break;
                }
                if (matched == null) continue;
                item.addProperty(key, matched[2]);
                count++;
                break;
            }
        }
        return count;
    }

    /**
     * r18-⑩：删源后强制重新生成并回写 json（不看本地源总开关，避免关着开关就写不回去）。
     *
     * <p>r20-⑭：正在回写时不再直接丢弃本次请求 —— 删源后紧跟一次自动回写时，旧实现会让删除结果
     * 「写不回 json」（用户看到条目还在）。改为稍后重试一次，保证删除结果最终一定落到磁盘。
     */
    public static void refreshOutput() {
        if (autoWriteRunning) {
            new Thread(() -> {
                try {
                    Thread.sleep(AUTO_WRITE_INTERVAL + 200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                refreshOutput();
            }, "local-source-refresh-retry").start();
            return;
        }
        autoWriteRunning = true;
        new Thread(() -> {
            try {
                autoWrite(build(true), true);
            } catch (Throwable e) {
                DebugLogStore.add(TAG, "本地源回写失败：" + e);
            } finally {
                autoWriteRunning = false;
            }
        }, "local-source-refresh").start();
    }

    /**
     * r62-①：**同步版**回写（「删了要立刻生效」的真修复）。
     *
     * <p>旧实现：删源 / 搬家后只调 {@link #refreshOutput()} —— 它起一个 {@code local-source-refresh}
     * 线程去 {@code build(true) + write}，**立刻返回**；而界面紧接着 {@code VodConfig.load()} 重新读盘，
     * 读到的还是**没写回去的旧 json**，于是「删掉的源又回来了」「移动后名字/分类没变」，
     * 用户必须重新加载一次源（或重开弹窗）才正常。</p>
     *
     * <p>本方法在**调用线程**里把「重扫 → 落盘」做完，返回后 {@code VodConfig.load()} 一定读到新内容。
     * <b>必须在后台线程调用</b>（一次全量扫描约 0.3~1 秒）。</p>
     *
     * @return 产物 json 是否真的写成功（false = 未设置扫描目录 / 产物被判定为用户手写配置 / 写盘失败）
     */
    public static boolean refreshOutputBlocking() {
        try {
            // 后台可能正有一次 autoWrite 在跑，先等它收尾（最多 3 秒），避免两个线程同时写同一个产物文件
            long deadline = System.currentTimeMillis() + 3000L;
            while (autoWriteRunning && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(30L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            JsonObject config = build(true);
            if (config == null) return false;
            String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(config);
            synchronized (LOCK) {
                autoWriteAt = System.currentTimeMillis();
                autoWriteHash = text;
            }
            String path = write(config);
            if (path != null) Prefers.put(KEY_FILE, path);
            return path != null;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "本地源同步回写失败：" + e);
            return false;
        }
    }

    /**
     * r18-⑩ / r20-②：把站点还原成磁盘上的本地源文件。
     *
     * <p>「文件路径」在站点里有两种放法，只认 {@code api} 一条路必然漏：
     * <ul>
     *     <li>py / js 源：路径就在 {@code api} 里（脚本原样的 {@code ./相对路径}，运行时已被
     *     {@link #resolveRelative} 展开成 {@code file://绝对路径}，App 内部还会换成内置服务的
     *     {@code http://127.0.0.1:port/file/…} 形式）；</li>
     *     <li><b>引擎型源</b>：{@code api} 只是引擎名，真实文件在 {@code ext}（网页源在 {@code homePage}）里 ——
     *     本地包 / m3u / txt / db / json 是 {@code csp_FileSpider}、PQ类 / YQ类 json 是
     *     {@code csp_XBPQ} / {@code csp_XYQHiker}、网页源是 {@code csp_Nostr}（路径写在 homePage）、
     *     drpy 的 {@code api} 反而指向引擎脚本 {@code ./lib/drpy2*.min.js}。
     *     老实现只拿 api 找文件，这类源一律被判成「没有源文件」—— 这就是长按删除时误报的根因。</li>
     * </ul>
     *
     * @return 文件存在时返回它，否则 null（说明是远程 / 内置源，确实没有源文件可删）
     */
    public static File siteFile(String api) {
        return siteFile(api, null, null);
    }

    /** r20-②：引擎型源必须把 ext 一起传进来（api 里只有引擎名，拿它找不到文件）。 */
    public static File siteFile(String api, String ext) {
        return siteFile(api, ext, null);
    }

    /** r20-②：网页源（{@code csp_Nostr}）的文件路径在 homePage 里，需要第三个参数。 */
    public static File siteFile(String api, String ext, String homePage) {
        try {
            for (String path : sourcePaths(api, ext, homePage)) {
                File file = resolveSource(path);
                if (file != null) return file;
            }
            // r20-⑭：兜底 —— 地址与实际目录对不上时（地址里只写了文件名、写的是旧的绝对路径、文件被移动过），
            // 在生成目录 / 扫描目录里按「文件名」唯一匹配一次。只在唯一命中时返回，
            // 多个同名文件时宁可不删（返回 null），绝不猜着删。
            String bare = bareFileName(api, ext, homePage);
            if (!TextUtils.isEmpty(bare)) return findByBareName(bare);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "定位本地源文件失败：" + e);
        }
        return null;
    }

    /** r20-⑭：候选地址里的文件名（带后缀才认；引擎名 {@code csp_XXX}、远程地址不算）。 */
    private static String bareFileName(String api, String ext, String homePage) {
        for (String raw : sourcePaths(api, ext, homePage)) {
            String path = raw == null ? "" : raw.trim();
            if (TextUtils.isEmpty(path) || !path.contains(".")) continue;
            int cut = path.lastIndexOf('/');
            String name = cut < 0 ? path : path.substring(cut + 1);
            try {
                name = Uri.decode(name);
            } catch (Throwable ignored) {
            }
            if (!TextUtils.isEmpty(name) && name.contains(".") && !name.startsWith("csp_")) return name;
        }
        return null;
    }

    /** r20-⑭：在生成目录 / 扫描目录里按文件名找（目录深度 ≤ 4）；唯一命中才返回，否则 null。 */
    private static File findByBareName(String name) {
        List<File> hits = new ArrayList<>();
        for (String dir : sourceDirs(settings())) {
            File root = new File(dir);
            if (!root.isDirectory()) continue;
            collectByName(root, name.toLowerCase(), hits, 0);
            if (hits.size() > 1) return null;
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static void collectByName(File dir, String name, List<File> hits, int depth) {
        if (depth > 4 || hits.size() > 1) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectByName(file, name, hits, depth + 1);
            else if (file.getName().toLowerCase().equals(name)) hits.add(file);
        }
    }

    /** 候选路径 → 磁盘文件：绝对路径直接判断；相对路径按「生成 json 所在目录 → 各启用扫描目录」逐个拼。 */
    private static File resolveSource(String path) {
        if (TextUtils.isEmpty(path)) return null;
        File direct = new File(path);
        if (direct.isAbsolute()) return direct.isFile() ? direct : null;
        for (String dir : sourceDirs(settings())) {
            File file = new File(dir, path);
            if (file.isFile()) return file;
        }
        return null;
    }

    /**
     * r20-⑭：配置级最终去重（在 {@code sites} 数组解析成站点列表之前调用）。
     *
     * <p>本地源进配置有两条彼此独立的链路：① 订阅源 json 自己列出的站点；② {@link #inject} 把扫描结果追加进去。
     * 同一批本地源在两侧的名字/地址写法只要有一点差别（{@code ./a.py} / {@code a.py} / 绝对路径 /
     * {@code file://} / 内部 {@code http://127.0.0.1:port/file/…}），追加时的指纹就漏判，
     * 站源列表里于是出现同名两行：上面那条往往定位不到文件（长按删不掉），
     * 下面那条删掉文件后两条一起消失 —— 用户看到的就是「重复源」。
     *
     * <p>这里按「物理源文件 + 显示名」收敛：同一个文件、同一个名字只留一条，
     * 且优先留「能定位到本地文件」的那条（它才删得掉）。拿不到文件路径的条目（远程订阅源、内置源）不参与。
     *
     * @return 移除的重复条数
     */
    public static int dedupSites(JsonObject config) {
        if (config == null || !config.has("sites") || !config.get("sites").isJsonArray()) return 0;
        try {
            JsonArray sites = config.getAsJsonArray("sites");
            Map<String, Integer> keep = new LinkedHashMap<>();
            for (int i = 0; i < sites.size(); i++) {
                JsonElement item = sites.get(i);
                if (!item.isJsonObject()) continue;
                String identity = siteIdentity(item.getAsJsonObject());
                if (TextUtils.isEmpty(identity)) continue;
                Integer index = keep.get(identity);
                if (index == null) {
                    keep.put(identity, i);
                    continue;
                }
                // 已有一条同身份：优先保留能定位到本地文件的那条（删得掉的那条）
                JsonElement exist = sites.get(index);
                if (exist.isJsonObject() && !hasLocalFile(exist.getAsJsonObject()) && hasLocalFile(item.getAsJsonObject()))
                    keep.put(identity, i);
            }
            Set<Integer> keeps = new LinkedHashSet<>(keep.values());
            List<JsonElement> drop = new ArrayList<>();
            for (int i = 0; i < sites.size(); i++) {
                JsonElement item = sites.get(i);
                if (!item.isJsonObject() || keeps.contains(i)) continue;
                if (TextUtils.isEmpty(siteIdentity(item.getAsJsonObject()))) continue;
                drop.add(item);
            }
            for (JsonElement item : drop) sites.remove(item);
            if (!drop.isEmpty()) DebugLogStore.add(TAG, "配置级去重 " + drop.size() + " 条（同一源文件被订阅源与本地源各列了一条）");
            return drop.size();
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "配置级去重失败：" + e);
            return 0;
        }
    }

    /**
     * r23-①：只保留「用户添加的扫描路径」下的本地源 —— 用户口径是：其它路径的源一律不管
     * （包括同级目录 {@code /storage/emulated/0/自己整理/} 这类不在设置里的目录）。
     *
     * <p>扫描侧本来只遍历 {@link #enabledScanDirs}，但站点列表还有第二条链路：当前加载的 json
     * （老配置、脚本产物、同级目录的 json）自己列出的本地源条目。这里在「解析成站点列表之前」
     * 把这类条目抖掉 —— 判定只看源文件落点：
     * <ul>
     *   <li>引擎型（{@code csp_XXX}）、内置源、远程源（http/https）没有本地文件 → 一律不动；</li>
     *   <li>绝对路径 → 必须落在允许范围里，否则剔除；</li>
     *   <li>相对路径 → 必须能在允许范围里定位到文件，否则剔除（json 在别的目录里、
     *       文件只有别的目录才有，就是我们不要的那种）。</li>
     * </ul>
     * 没有添加任何扫描目录时不做任何删减（避免把用户的站点列表清空）。
     *
     * <p>r26-①：<b>允许范围 = 已勾选的扫描目录 ∪ 当前配置自己所在的目录</b>。
     * 用户手写的配置（例如 {@code file://自用2026plus/私享plus.json}）用 {@code ./py/x.py}、
     * {@code ./xbpq/y.json} 这类相对写法带着<b>自己目录里的随行源</b>；只认扫描目录时它们会被
     * 当成"其它路径的源"整批剔掉（实测 133 条全丢、站点只剩 2 个）＝用户反馈的
     * 「自己编写的本地源无法加载」。这些源是用户自己的，必须留。
     * 远程订阅（http/https）没有本地随行目录，行为不变。
     *
     * @return 剔除条数
     */
    public static int pruneForeignLocalSites(JsonObject config) {
        return pruneForeignLocalSites(config, "");
    }

    /**
     * r26-①：完整版 —— 额外接收「当前配置地址」，把配置自己所在目录并入允许范围。
     *
     * @param configUrl 当前加载的配置地址（{@code VodConfig} 里的 {@code config.getUrl()}）
     */
    public static int pruneForeignLocalSites(JsonObject config, String configUrl) {
        if (config == null || !config.has("sites") || !config.get("sites").isJsonArray()) return 0;
        try {
            List<String> roots = new ArrayList<>(enabledScanDirs(settings()));
            if (roots.isEmpty()) return 0;
            List<String> keys = new ArrayList<>();
            for (String dir : roots) {
                String key = normalizedPath(dir);
                if (!TextUtils.isEmpty(key) && !keys.contains(key)) keys.add(key);
            }
            // r28-② 修：configRootDirs() 返回的**已经是归一化 key**（形如 #external/自用2026plus），
            // 老实现把它再丢给 normalizedPath() —— 后者走 File.getCanonicalPath()，会把 key 当成
            // 文件系统相对路径解析成 <cwd>/#external/自用2026plus，与待判定源的 key
            // （#external/自用2026plus/py/x.py）永远匹配不上 → 用户自己编写的本地源被整批剔除
            // （实测「剔除其它路径的本地源 97 条」，例子全是 自用2026plus/ 下的自写源）。
            // 这里直接按 key 使用（与 underEnabledDir 的前缀匹配语义一致）。
            for (String dir : configRootDirs(configUrl)) {
                if (TextUtils.isEmpty(dir)) continue;
                if (!keys.contains(dir)) keys.add(dir);
                if (!roots.contains(dir)) roots.add(dir);
            }
            if (keys.isEmpty()) return 0;
            JsonArray sites = config.getAsJsonArray("sites");
            List<JsonElement> drop = new ArrayList<>();
            List<String> samples = new ArrayList<>();
            for (JsonElement item : sites) {
                if (!item.isJsonObject()) continue;
                JsonObject object = item.getAsJsonObject();
                String path = sourcePathOf(object);
                if (TextUtils.isEmpty(path)) continue;
                if (path.startsWith("http://") || path.startsWith("https://")) continue;
                if (belongsToEnabledDir(path, roots, keys)) continue;
                drop.add(item);
                if (samples.size() < 5) samples.add(str(object, "name") + "（" + path + "）");
            }
            for (JsonElement item : drop) sites.remove(item);
            if (!drop.isEmpty()) DebugLogStore.add(TAG, "剔除其它路径的本地源 " + drop.size() + " 条（允许范围：" + TextUtils.join("、", roots) + "；例：" + TextUtils.join("；", samples) + "）");
            return drop.size();
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "剔除其它路径的本地源失败：" + e);
            return 0;
        }
    }

    /**
     * r23-① / r25-②：这条源文件路径是否落在某个已启用扫描目录里。
     *
     * <p>r25-② 起改成「路径层面」判定：{@code file://相对外部存储根}（脚本写法）、URL 编码、绝对路径三种写法
     * 都能归一化比较；<b>绝对写法不再要求文件存在</b>（配置里的条目路径与磁盘路径写法本来就不同），
     * 相对写法仍先按「相对某扫描目录」找一次文件，再按「相对外部存储根」折算比较（保持不变地遵守
     * r23 硬约束：同级 {@code 自己整理/} 这类没勾选的目录仍然不算）。
     */
    private static boolean belongsToEnabledDir(String path, List<String> roots, List<String> keys) {
        String raw = path == null ? "" : path.trim();
        if (TextUtils.isEmpty(raw)) return true;
        if (raw.startsWith("http://") || raw.startsWith("https://")) return true;
        for (String candidate : parseSourcePath(raw)) {
            if (TextUtils.isEmpty(candidate)) continue;
            if (!candidate.startsWith("/")) {
                if (relativeUnderEnabledDir(candidate, roots, keys)) return true;
                continue;
            }
            if (underEnabledDir(candidateKey(candidate), keys)) return true;
        }
        return false;
    }

    /** r25-②：相对写法落点判定 —— 先按「相对扫描目录」找文件，再按「相对外部存储根」折算比较。 */
    private static boolean relativeUnderEnabledDir(String candidate, List<String> roots, List<String> keys) {
        for (String dir : roots) {
            try {
                if (new File(dir, candidate).isFile()) return true;
            } catch (Throwable ignored) {
            }
        }
        for (String base : EXTERNAL_BASES) {
            if (underEnabledDir(candidateKey(base + "/" + candidate), keys)) return true;
        }
        return false;
    }

    private static boolean underEnabledDir(String key, List<String> keys) {
        if (TextUtils.isEmpty(key)) return false;
        for (String dir : keys) {
            if (TextUtils.isEmpty(dir)) continue;
            if (key.equals(dir)) return true;
            if (key.startsWith(dir.endsWith("/") ? dir : dir + "/")) return true;
        }
        return false;
    }

    /**
     * r26-①：当前配置的「随行源」目录 —— 手写配置会带着自己目录里的 py / js / json / cat 源。
     *
     * <p>只认本地配置：{@code file://…}、{@code /绝对路径}，以及本壳把本地文件折成的
     * {@code http://127.0.0.1:9978/file/…}；远程订阅（http/https）返回空表 = 没有随行目录。
     * 本壳约定 {@code file://自用2026plus/私享plus.json} 是「相对外部存储根」的写法，
     * 因此按各外部存储根展开成候选绝对目录；{@code file:///storage/…/x.json} 这类绝对写法直接用其父目录。
     * 返回的都是 {@link #candidateKey} 归一化后的绝对目录（{@code /sdcard}、{@code /storage/emulated/0} 会折成同一把 key）。
     */
    private static List<String> configRootDirs(String configUrl) {
        List<String> dirs = new ArrayList<>();
        try {
            if (TextUtils.isEmpty(configUrl)) return dirs;
            String url = configUrl.trim();
            String raw = null;
            if (url.contains("127.0.0.1") && url.contains("/file/")) raw = url.substring(url.indexOf("/file/") + 6);
            else if (url.startsWith("file:")) raw = url.substring(url.indexOf("file:") + 5);
            else if (url.startsWith("/")) raw = url;
            if (TextUtils.isEmpty(raw)) return dirs;
            try {
                raw = Uri.decode(raw);
            } catch (Throwable ignored) {
            }
            raw = raw.replace('\\', '/');
            // file://自用2026plus/x.json（相对外部存储根）与本壳镜像写法 → 需要按存储根展开；
            // file:///storage/…/x.json 与 /storage/…/x.json → 本身就是绝对路径
            boolean relative = !raw.startsWith("/") || (raw.startsWith("//") && !raw.startsWith("///"));
            String body = raw;
            while (body.startsWith("/")) body = body.substring(1);
            if (TextUtils.isEmpty(body)) return dirs;
            String dir = body.contains("/") ? body.substring(0, body.lastIndexOf('/')) : "";
            if (TextUtils.isEmpty(dir)) {
                for (String base : EXTERNAL_BASES) addRootDir(dirs, base);
                return dirs;
            }
            if (relative) for (String base : EXTERNAL_BASES) addRootDir(dirs, base + "/" + dir);
            else addRootDir(dirs, "/" + dir);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "解析配置随行目录失败：" + e);
        }
        return dirs;
    }

    private static void addRootDir(List<String> dirs, String dir) {
        if (TextUtils.isEmpty(dir)) return;
        String key = candidateKey(dir);
        if (!TextUtils.isEmpty(key) && !dirs.contains(key)) dirs.add(key);
    }

    // ==================== r26-②：自己编写的本地源保护 ====================

    /**
     * r26-②：记下当前加载的配置地址 —— {@code VodConfig.parseConfig} 在解析前调用。
     *
     * <p>「自己编写的本地源」的判定要用到「这份配置自己所在的目录」（手写配置的随行源目录），
     * 而 {@link #inject} 拿到的只有配置对象、拿不到地址，因此在解析入口处统一记一份。
     */
    public static void setCurrentConfigUrl(String url) {
        currentConfigUrl = url == null ? "" : url.trim();
    }

    /** r26-②：当前加载的配置地址（从未加载过时为空串）。 */
    public static String getCurrentConfigUrl() {
        return currentConfigUrl;
    }

    /**
     * r26-②：取走并清空「本次被拦下的自动回写目标」——「保存并加载」结束后界面据此补一句提示。
     *
     * @return 被跳过的输出文件路径；没有跳过时返回空串
     */
    public static String consumeBlockedWrite() {
        String value = blockedWriteTarget;
        blockedWriteTarget = "";
        return value == null ? "" : value;
    }

    /**
     * r26-②：这条源是不是「你<b>自己编写</b>的本地源」。
     *
     * <p>本地源分两类：① 本壳<b>扫描生成</b>的（落在「已启用扫描目录」里，可以删、可以移动）；
     * ② 你<b>自己编写</b>的（手写配置带着的 py / js / json / cat 源，App 只许只读加载）。
     * 第 ② 类源一律禁止「自动刷新回填」「删除源文件」「移动到其他分类」：触发时只给提示，不做任何改动。
     *
     * <p>判定规则（任一命中即算自写源）：
     * <ul>
     *     <li>源文件落在「当前配置的随行目录」里 —— 手写配置同目录，例如
     *     {@code file://自用2026plus/私享plus.json} → {@code /sdcard/自用2026plus/**}；</li>
     *     <li>或者：源文件是本地文件，却不在任何「已启用扫描目录」里（既不是本壳扫描产物、又是本地源
     *     → 只能是你自己带来的）。</li>
     * </ul>
     *
     * <p>远程订阅源（http/https）、引擎名（{@code csp_XXX}）、内置源都不算本地源 → false，
     * 保持原来「没有本地源文件」的提示不变。当前配置本身就是本壳产物（{@code 昏黄本地源.json}）时，
     * 「随行目录」不参与判定 —— 否则产物所在的扫描目录会把整批扫描源误判成自写源。
     *
     * @param api      站点 api（可能只是引擎名）
     * @param ext      站点 ext（引擎型源的真实文件路径在这里）
     * @param homePage 网页源地址字段（可能传 null）
     */
    public static boolean isSelfAuthored(String api, String ext, String homePage) {
        try {
            List<String> scanDirs = enabledScanDirs(settings());
            List<String> scanKeys = keysOf(scanDirs);
            List<String> companions = companionDirs();
            List<String> companionKeys = keysOf(companions);
            for (String raw : sourcePaths(api, ext, homePage)) {
                String text = raw == null ? "" : raw.trim();
                if (TextUtils.isEmpty(text)) continue;
                boolean mirror = text.startsWith("http://") || text.startsWith("https://");
                if (mirror && text.indexOf("/file/") < 0) continue;
                for (String candidate : parseSourcePath(text)) {
                    String path = candidate == null ? "" : candidate.trim();
                    if (TextUtils.isEmpty(path) || isEnginePath(path)) continue;
                    if (path.startsWith("/")) {
                        String key = candidateKey(path);
                        if (TextUtils.isEmpty(key)) continue;
                        if (!companionKeys.isEmpty() && underEnabledDir(key, companionKeys)) return true;
                        return !underEnabledDir(key, scanKeys);
                    }
                    // 相对写法（配置里 ./py/x.py 这种，运行期一般已被展开成绝对写法）：
                    // 落到随行目录 = 自写源；落到扫描目录 = 本壳产物；都找不到 → 交给原来的「没有源文件」提示。
                    if (existsUnder(companions, path)) return true;
                    if (relativeUnderEnabledDir(path, scanDirs, scanKeys)) return false;
                }
            }
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "自写源判定失败：" + e);
        }
        return false;
    }

    /**
     * r26-②：这个 json 文件是不是「你自己编写的配置」（而不是本壳生成 / 回写的产物）。
     *
     * <p>用在两道自动刷新回填的闸门上：① 生成产物要写盘时（{@link #write}）—— 目标恰好是你自己的配置
     * 就一律不写，免得把手写 json 覆盖成本壳产物；② 搬家 / 删源后回填其它 json 的指向时
     * （{@link #rewriteJsonPaths}）—— 你手写的 json 一个字段都不改。
     *
     * <p>判定：文件名就是本壳产物名（{@code 昏黄本地源.json}）→ 不是你的；是当前正在加载的那份配置本身
     * → 是你的；位于当前配置的随行目录里 → 是你的。
     */
    public static boolean isUserOwnedConfig(String path) {
        try {
            if (TextUtils.isEmpty(path)) return false;
            String key = candidateKey(path);
            if (TextUtils.isEmpty(key)) return false;
            String name = key.substring(key.lastIndexOf('/') + 1);
            if (isProductName(name)) return false;
            String own = configFileKey();
            if (!TextUtils.isEmpty(own) && key.equals(own)) return true;
            return underEnabledDir(key, keysOf(configRootDirs(currentConfigUrl)));
        } catch (Throwable e) {
            return false;
        }
    }

    /** r26-②：目录列表 → 归一化 key 列表（去重，供 {@link #underEnabledDir} 使用）。 */
    private static List<String> keysOf(List<String> dirs) {
        List<String> keys = new ArrayList<>();
        if (dirs == null) return keys;
        for (String dir : dirs) {
            String key = candidateKey(dir);
            if (!TextUtils.isEmpty(key) && !keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    /** r26-②：当前配置的随行目录（手写配置同目录里的本地源都算自写源）；本壳产物配置不参与判定。 */
    private static List<String> companionDirs() {
        if (TextUtils.isEmpty(currentConfigUrl)) return new ArrayList<>();
        if (isAppProductConfig(currentConfigUrl)) return new ArrayList<>();
        return configRootDirs(currentConfigUrl);
    }

    /** r26-②：配置地址是不是本壳产物（{@code 昏黄本地源.json}）。 */
    private static boolean isAppProductConfig(String url) {
        String name = url == null ? "" : url.trim();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) name = name.substring(cut + 1);
        try {
            name = Uri.decode(name);
        } catch (Throwable ignored) {
        }
        return isProductName(name);
    }

    /** r26-②：当前加载配置文件的归一化 key（拿不到地址时为空串）。 */
    private static String configFileKey() {
        for (String candidate : parseSourcePath(currentConfigUrl)) {
            if (candidate != null && candidate.startsWith("/")) {
                String key = candidateKey(candidate);
                if (!TextUtils.isEmpty(key)) return key;
            }
        }
        return "";
    }

    /** r26-②：相对写法在某个目录集合下真的存在文件。 */
    private static boolean existsUnder(List<String> dirs, String relative) {
        if (dirs == null || TextUtils.isEmpty(relative)) return false;
        for (String dir : dirs) {
            try {
                if (new File(dir, relative).isFile()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * r25-②：路径字符串归一化（纯字符串，不碰文件系统）—— URL 解码、统一斜杠、
     * 消解 {@code ./} 与 {@code ..}，最后按「外部存储根写法」折算成同一个 key
     * （{@code /sdcard}、{@code /storage/emulated/0} 等指的是同一个目录）。
     */
    private static String candidateKey(String path) {
        String value = path == null ? "" : path.trim();
        try {
            value = Uri.decode(value);
        } catch (Throwable ignored) {
        }
        value = value.replace('\\', '/');
        boolean absolute = value.startsWith("/");
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/")) {
            if (TextUtils.isEmpty(part) || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(part);
        }
        return canonicalKey((absolute ? "/" : "") + TextUtils.join("/", parts));
    }

    /**
     * r25-②：条目「物理源文件」的归一化身份 —— 扫描产物带 {@code #path} 提示时直接用，
     * 否则按 ext → homePage → api 的顺序（与 {@link #sourcePaths} 一致）折成绝对路径再归一化。
     * 与 {@link #sourcePathOf} 的区别：这里不做「相对扫描目录」的书写形式折算，只比「是不是同一个文件」，
     * 所以名字写法不同的同一份源也能识别出来（生成侧 {@code ./x.json} 与配置侧 {@code file://…/x.json}）。
     *
     * @return 拿不到本地文件路径（远程源、引擎名）时返回 null
     */
    private static String fileKeyOf(JsonObject object) {
        if (object == null) return null;
        try {
            String hint = primitiveString(object, HINT_PATH);
            if (!TextUtils.isEmpty(hint)) {
                String key = candidateKey(hint);
                if (!TextUtils.isEmpty(key)) return key;
            }
            for (String field : new String[]{"ext", "homePage", "api"}) {
                String raw = primitiveString(object, field);
                if (TextUtils.isEmpty(raw)) continue;
                for (String candidate : parseSourcePath(raw)) {
                    if (TextUtils.isEmpty(candidate)) continue;
                    if (candidate.startsWith("/")) return candidateKey(candidate);
                    String dir = sourceDirOf(candidate);
                    if (!TextUtils.isEmpty(dir)) return candidateKey(dir + "/" + candidate);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 相对路径在哪个基准目录下存在（都不存在时用第一个基准目录兜底，保证同一写法永远同一把 key）。 */
    private static String sourceDirOf(String relative) {
        List<String> dirs;
        try {
            dirs = sourceDirs(settings());
        } catch (Throwable e) {
            return "";
        }
        for (String dir : dirs) {
            try {
                if (new File(dir, relative).isFile()) return dir;
            } catch (Throwable ignored) {
            }
        }
        return dirs.isEmpty() ? "" : dirs.get(0);
    }

    /** r20-⑭：「物理源文件 + 显示名」身份；拿不到文件路径时返回空串（不参与去重）。 */
    private static String siteIdentity(JsonObject object) {
        String path = sourcePathOf(object);
        if (TextUtils.isEmpty(path)) return "";
        String name = str(object, "name");
        return path + "\u0000" + (name == null ? "" : name.trim());
    }

    /** r20-⑭：这条站点能不能在磁盘上定位到对应的本地源文件。 */
    private static boolean hasLocalFile(JsonObject object) {
        try {
            return siteFile(primitiveString(object, "api"), primitiveString(object, "ext"), primitiveString(object, "homePage")) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * r19-#7 / r20-②：候选路径列表 —— 真实文件（{@code ext} → {@code homePage}）优先于 {@code api}。
     *
     * <p>顺序很关键：引擎型源的 api 是引擎名、drpy 的 api 更是引擎库本身，
     * 先按 api 找会找到（甚至删掉）引擎文件，必须让 ext / homePage 先命中。
     */
    private static List<String> sourcePaths(String api, String ext, String homePage) {
        List<String> paths = new ArrayList<>();
        collectSourcePaths(paths, ext);
        collectSourcePaths(paths, homePage);
        if (!isEnginePath(api)) collectSourcePaths(paths, api);
        return paths;
    }

    private static void collectSourcePaths(List<String> out, String raw) {
        for (String path : parseSourcePath(raw)) if (!out.contains(path)) out.add(path);
    }

    /**
     * r20-②：引擎型地址 —— 不是源文件路径，不能拿来找 / 删。
     *
     * <p>{@code csp_XXX} 是内置蜘蛛名（本壳把 ext 当参数传给它们，见 JarLoader#getSpider 的
     * {@code api.split("csp_")[1]}），drpy 的 api 指向引擎库 {@code ./lib/drpy2*.min.js}
     * —— 用它删源会把引擎删掉。
     */
    private static boolean isEnginePath(String raw) {
        String path = raw == null ? "" : raw.trim();
        if (TextUtils.isEmpty(path)) return true;
        if (path.startsWith("csp_")) return true;
        if (path.startsWith("assets:")) return true;
        // r23-①：只有 drpy 的*引擎库*（./lib/drpy2.min.js 这类）才算引擎地址。旧实现是「.js 且含 drpy」，
        // 把 drpy 的源文件（./JS[Drpy]/…/drpy2_xxx.js）也一起当成引擎 —— 那条源的「物理身份」直接变成
        // null，于是既去不了重、也定位不到文件（重复源 / 删不掉的源就是这么来的）。
        if (!path.endsWith(".js")) return false;
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".min.js")) return true;
        return lower.startsWith("./lib/") || lower.startsWith("lib/") || lower.contains("/lib/");
    }

    /**
     * r19-#7 / r20-②：把一条站点地址还原成文件系统路径（「原样」与「URL 解码后」两种写法都给出，逐个试）。
     *
     * <p>运行时 {@link #resolveRelative} 已经把 {@code ./xxx.js} 展开为 {@code file:///abs/xxx.js}，
     * App 内部还会换成内置服务的 {@code http://127.0.0.1:9978/file//abs/xxx.js} 形式
     * （{@code UrlUtil.convert} 把 {@code file://} 去掉、拼到 {@code /file/} 后面，所以
     * 「/file/」后面可能是双斜杠、中文路径还会被 URL 编码）；
     * 老实现只认 {@code ./相对路径} 与 {@code /绝对路径}，所以长按删除本地源总提示「该源没有源文件」。
     */
    private static List<String> parseSourcePath(String raw) {
        List<String> paths = new ArrayList<>();
        String path = raw == null ? "" : raw.trim();
        if (isEnginePath(path)) return paths;
        // 判重根因修：ext 是对象时，Site 的 ext 经 ExtAdapter 转成 JSON 串
        // （MissAV：{"site":...,"cfgo":<同一份 cf-proxy>,"proxy":{}}；金牌：
        // {"site":<同一串域名>}），同类判定的多个文件 ext 串完全相同。
        // 若把整串当成文件路径，sourcePathOf 永远取 ext 当身份、不到 api，
        // SiteAdapter.addAll 按该身份去重就会把多个文件折成一条（普通 py
        // ext 为空、回落到 api 的 py 文件路径各不相同，所以 MoTV 多个都出）。
        // JSON 串本身永远不是文件路径：直接返回空，让调用方回落到 api。
        if (path.startsWith("{") || path.startsWith("[")) return paths;
        // r25-②：`file://自己整理 (1)/PQ类/a.json` 是「脚本写法」（相对外部存储根），必须补成绝对路径：
        // 生成产物、App 载入的配置、运行期展开后的地址都用这种写法；不补的话
        // ① prune 把整批本地源当外地源剔除（.json 源从列表消失就是这一步）；
        // ② 去重口径两侧对不上，注入时会再追加一份重复源。
        boolean rootRelative = false;
        if (path.startsWith("file:")) {
            path = path.substring(5);
            rootRelative = !path.startsWith("/") && !path.startsWith("//");
        } else if (path.startsWith("http://") || path.startsWith("https://")) {
            int index = path.indexOf("/file/");
            if (index < 0) return paths;
            path = path.substring(index + 6);
            // 内置服务地址也可能是脚本写法的相对地址：http://127.0.0.1:9978/file/%E8%87%AA…/PQ类/a.json
            rootRelative = !path.startsWith("/") && !path.startsWith("//");
        }
        while (path.startsWith("//")) path = path.substring(1);
        if (path.startsWith("./")) path = path.substring(2);
        if (TextUtils.isEmpty(path)) return paths;
        List<String> values = new ArrayList<>();
        // r25-②：解码后的写法排前面 —— 中文目录被 URL 编码过时，只有解码后的路径才能真的找到文件
        // （定位源文件 / 删除源文件 / 比较物理身份都靠它）。
        String decodedValue = path;
        try {
            String decoded = Uri.decode(path);
            if (!TextUtils.isEmpty(decoded)) decodedValue = decoded;
        } catch (Throwable ignored) {
        }
        values.add(decodedValue);
        if (!decodedValue.equals(path)) values.add(path);
        if (rootRelative) {
            // 绝对写法排在最前：sourcePathOf 取首个候选当「物理身份」，折成相对扫描目录后才与生成侧一致。
            for (String value : new ArrayList<>(values)) {
                for (String base : EXTERNAL_BASES) {
                    String absolute = base + "/" + value;
                    if (!paths.contains(absolute)) paths.add(absolute);
                }
            }
        }
        for (String value : values) if (!paths.contains(value)) paths.add(value);
        return paths;
    }

    /**
     * r19-#7：相对路径的查找基准 —— 生成 json 所在目录（与运行时 {@code ./} 的解析基准一致）优先，
     * 其次是各启用扫描目录（{}）；老实现只用扫描目录，路径一多层级就会找不到。
     */
    private static List<String> sourceDirs(JsonObject data) {
        List<String> dirs = new ArrayList<>();
        String base = outputBaseDir(data);
        if (!TextUtils.isEmpty(base)) dirs.add(base);
        for (String dir : enabledScanDirs(data)) if (!TextUtils.isEmpty(dir) && !dirs.contains(dir)) dirs.add(dir);
        return dirs;
    }

    /**
     * r18-⑩：删除本地源文件（站源列表长按删除用）。
     *
     * @return 1 = 文件已删除并已触发 json 回写；0 = 不是本地源文件，未做任何改动；-1 = 删除失败；
     *         -4 = 这是你自己编写的本地源，禁止删除（自动刷新回填 / 删除 / 移动 三项都不做）
     */
    public static int deleteSiteFile(String api) {
        return deleteSiteFile(api, null, null);
    }

    /** r20-②：引擎型源要一起传 ext（api 里只有引擎名，单靠它定位不到文件）。 */
    public static int deleteSiteFile(String api, String ext) {
        return deleteSiteFile(api, ext, null);
    }

    /** r20-②：网页源（{@code csp_Nostr}）另外需要 homePage。 */
    public static int deleteSiteFile(String api, String ext, String homePage) {
        // r26-②：你自己编写的本地源禁止删除 —— 只提示，连文件都不去定位、不做任何改动
        if (isSelfAuthored(api, ext, homePage)) {
            DebugLogStore.add(TAG, "自己编写的本地源：拒绝删除（" + api + "）");
            return BLOCK_SELF_AUTHORED;
        }
        File file = siteFile(api, ext, homePage);
        if (file == null) return 0;
        try {
            if (!file.delete()) return -1;
            DebugLogStore.add(TAG, "已删除本地源文件：" + file.getAbsolutePath() + "（随后回写 json）");
            refreshOutput();
            return 1;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "删除本地源文件失败：" + e);
            return -1;
        }
    }

    // ==================== r24：源文件移动（换分类） ====================

    /** r24：最近一次成功移动后的新绝对路径（界面提示用）。 */
    private static String movedPath;

    /**
     * r102：**重命名源文件**（同目录改名 + 改写 json 指向 + 回写产物）。
     *
     * <p>与 {@link #moveSiteToDir} 走同一条收尾链路（保持相对结构 → 改写其它 json 的指向 → 回写产物），
     * 区别只在于目标不是另一个目录，而是同目录下的新文件名。</p>
     *
     * @param newName 新文件名（可带扩展名；不带时沿用原文件的扩展名）
     * @return 1 成功｜0 不是本地源文件｜-1 目标文件已存在｜-2 改名失败｜-3 名称非法｜-4 自写源
     */
    public static int renameSiteFile(String api, String ext, String homePage, String newName) {
        movedPath = null;
        if (isSelfAuthored(api, ext, homePage)) {
            DebugLogStore.add(TAG, "自己编写的本地源：拒绝重命名（" + api + "）");
            return BLOCK_SELF_AUTHORED;
        }
        try {
            JsonObject data = settings();
            File file = siteFile(api, ext, homePage);
            if (file == null) return 0;
            String name = sanitizeName(newName);
            if (name == null) return -3;
            String oldName = file.getName();
            int dot = oldName.lastIndexOf('.');
            String oldExt = dot > 0 ? oldName.substring(dot + 1) : "";
            if (name.lastIndexOf('.') <= 0 && !TextUtils.isEmpty(oldExt)) name = name + "." + oldExt;
            File dest = new File(file.getParentFile(), name);
            if (dest.getAbsolutePath().equals(file.getAbsolutePath())) return 1;
            if (dest.exists()) return -1;
            if (!moveFile(file, dest)) return -2;
            movedPath = dest.getAbsolutePath();
            String address = relPath(dest, outputBaseDir(data));
            int rewritten = rewriteJsonPaths(file, dest, address, data);
            DebugLogStore.add(TAG, "源文件重命名：" + file.getAbsolutePath() + " → " + movedPath
                    + "，json 指向已改写 " + rewritten + " 处，新地址 " + address);
            refreshAfterMove();
            return 1;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "源文件重命名失败：" + e);
            return -2;
        }
    }

    /**
     * r102：**重命名分类文件夹**（整目录改名 + 批量改写目录下所有源在 json 里的指向）。
     *
     * <p>整目录 {@code renameTo} 之后，目录内每个源文件的绝对路径都变了，所以要按「旧路径 → 新路径」
     * 逐条改写 json；改写用批量模式（{@link #beginBatch()}），末尾只重扫/回写一次，避免 1300+ 源时
     * 每条都全量重扫导致界面卡死。</p>
     *
     * @param dirPath 要改名的目录绝对路径
     * @param newName 新目录名（单层名，不含路径分隔符）
     * @return 1 成功｜-1 目标目录已存在｜-2 改名前失败/复制失败｜-3 目录或名称非法｜-5 扫描根目录不允许改名
     */
    public static int renameFolder(String dirPath, String newName) {
        try {
            if (TextUtils.isEmpty(dirPath)) return -3;
            File dir = new File(dirPath);
            if (!dir.isDirectory()) return -3;
            JsonObject data = settings();
            String base = outputBaseDir(data);
            String abs = dir.getAbsolutePath();
            if (!TextUtils.isEmpty(base) && abs.equals(base)) return -5;
            for (String scan : getScanDirs()) {
                if (!TextUtils.isEmpty(scan) && abs.equals(new File(scan).getAbsolutePath())) return -5;
            }
            String name = sanitizeName(newName);
            if (name == null) return -3;
            File parent = dir.getParentFile();
            if (parent == null) return -3;
            File dest = new File(parent, name);
            if (dest.getAbsolutePath().equals(abs)) return 1;
            if (dest.exists()) return -1;
            List<File> oldFiles = new ArrayList<>();
            collectFilesQuiet(dir, oldFiles, 0);
            if (!dir.renameTo(dest)) return -2;
            DebugLogStore.add(TAG, "分类文件夹重命名：" + abs + " → " + dest.getAbsolutePath()
                    + "（含 " + oldFiles.size() + " 个文件）");
            int rewritten = 0;
            beginBatch();
            try {
                String destPath = dest.getAbsolutePath();
                for (File old : oldFiles) {
                    String rel = old.getAbsolutePath().substring(abs.length());
                    File now = new File(destPath + rel);
                    if (!now.exists()) continue;
                    rewritten += rewriteJsonPaths(old, now, relPath(now, base), data);
                }
            } finally {
                endBatch();
            }
            DebugLogStore.add(TAG, "文件夹重命名：json 指向已改写 " + rewritten + " 处");
            return 1;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "分类文件夹重命名失败：" + e);
            return -2;
        }
    }

    /** r104：统计目录内的文件个数（深度与数量上限与 {@link #collectFilesQuiet} 一致）。 */
    public static int countFolderFiles(String dirPath) {
        if (TextUtils.isEmpty(dirPath)) return 0;
        try {
            File dir = new File(dirPath);
            if (!dir.isDirectory()) return 0;
            List<File> files = new ArrayList<>();
            collectFilesQuiet(dir, files, 0);
            return files.size();
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "统计目录文件数失败：" + e);
            return 0;
        }
    }

    /**
     * r104：删除一个分类文件夹（连同其中所有文件）。
     *
     * <p>「增加/删除分类」里删除动作的落地实现。守卫与 {@link #renameFolder} 一致：
     * <b>扫描根目录与产物目录一律不允许删</b>——那等于把整片源目录清空，不是「删一个分类」。</p>
     *
     * @return &gt;=0 = 删除成功并返回被删掉的文件个数；-2 = 删除失败；-3 = 目录非法；
     *         -5 = 扫描根目录 / 产物目录不允许删除
     */
    public static int deleteFolder(String dirPath) {
        if (TextUtils.isEmpty(dirPath)) return -3;
        try {
            File dir = new File(dirPath);
            if (!dir.isDirectory()) return -3;
            JsonObject data = settings();
            String base = outputBaseDir(data);
            String abs = dir.getAbsolutePath();
            if (!TextUtils.isEmpty(base) && abs.equals(new File(base).getAbsolutePath())) return -5;
            for (String scan : getScanDirs()) {
                if (!TextUtils.isEmpty(scan) && abs.equals(new File(scan).getAbsolutePath())) return -5;
            }
            List<File> files = new ArrayList<>();
            collectFilesQuiet(dir, files, 0);
            int deleted = 0;
            for (File file : files) if (file.delete()) deleted++;
            boolean removed = deleteEmptyTree(dir);
            DebugLogStore.add(TAG, "删除分类文件夹：" + abs + "（文件 " + deleted + "/" + files.size()
                    + "，目录已删=" + removed + "）");
            // 与 deleteSiteFile 同一条刷新链路：产物按磁盘现状整份重建，界面上分类随之消失
            refreshOutputBlocking();
            return deleted;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "删除分类文件夹失败：" + e);
            return -2;
        }
    }

    /** r104：自底向上删目录（残留的子目录也一并清掉），返回顶层目录是否已不存在。 */
    private static boolean deleteEmptyTree(File dir) {
        try {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) deleteEmptyTree(child);
                    else child.delete();
                }
            }
            return dir.delete() || !dir.exists();
        } catch (Throwable e) {
            return !dir.exists();
        }
    }

    /** r102：文件/目录名的强校验（非空、不以点开头、不含路径分隔符与 Windows 非法字符）。 */
    public static String sanitizeName(String input) {
        if (TextUtils.isEmpty(input)) return null;
        String name = input.trim();
        if (name.isEmpty() || name.startsWith(".")) return null;
        if (name.matches(".*[\\\\/:*?\"<>|].*")) return null;
        return name;
    }

    /** r102：递归收集目录下的文件（深度与数量都设上限，避免超大目录把主流程拖住）。 */
    private static void collectFilesQuiet(File dir, List<File> out, int depth) {
        if (dir == null || depth > 6 || out.size() > 4000) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectFilesQuiet(child, out, depth + 1);
            else out.add(child);
        }
    }

    /** r24：最近一次成功移动后的新绝对路径，没有则 null。 */
    public static String movedPath() {
        return movedPath;
    }

    /**
     * r24 / r25-③：可选的「目标分类」列表 —— 只列<b>源分类文件夹</b>（影视类 / 测试 / 自己 / 直播 / 音乐类 …），
     * 不再把 {@code PQ类 / PY类 / JS[Drpy] / jar} 这些「源类型目录」当成目标（那是 r24 的做法，用户明确否掉）。
     *
     * <p>候选按「能不能安全搬」筛过：优先同一个类型目录下的同名分类，其次是「同类」类型目录
     * （目录关键词一致，例如 PQ类 ↔ 另一个 PQ类）；跨类型搬动会改掉源的引擎，不出现在候选里。
     * 当前源所在的那个分类本身也会排除。
     *
     * <p>没有源文件（远程订阅源 / 内置源）时返回空列表，界面据此提示「没有可选的目标分类」。
     */
    public static List<String> moveTargets(String api, String ext, String homePage) {
        List<String> result = new ArrayList<>();
        try {
            // r26-②：你自己编写的本地源禁止移动 → 一条目标分类都不提供
            if (isSelfAuthored(api, ext, homePage)) return result;
            JsonObject data = settings();
            File file = siteFile(api, ext, homePage);
            if (file == null) return result;
            File current = currentCategoryDir(file, data);
            String skip = current == null ? "" : categoryOf(current.getName());
            for (String name : categoryTargets(file, data).keySet()) {
                if (!TextUtils.isEmpty(skip) && skip.equals(name)) continue;
                result.add(name);
            }
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "枚举可移动分类失败：" + e);
        }
        return result;
    }

    /**
     * r24：把本地源文件搬到另一个分类下，源文件与 json 指向一起改，最后触发重新生成 / 回写。
     *
     * <p>r25-③：分类 = <b>源分类文件夹</b>（类型目录下的子目录，如 {@code PY类/影视类[剧]}），
     * 目标由 {@link #categoryTargets} 按「同类型目录优先」解析出来。搬家用 {@code renameTo}，跨挂载点失败时
     * 退化为「拷贝 + 删原件」。json 要同步两处：① 生成产物（{@link #DEFAULT_OUT_NAME}）由
     * {@link #refreshOutput()} 按新位置重新生成；② 扫描目录里其它配置 json —— 逐条按「条目实际
     * 解析出的文件」匹配，只改写真正指向这个文件的那个字段，避免误伤。
     *
     * @return 1 = 移动成功；0 = 不是本地源文件（远程 / 内置源）；-1 = 目标分类下已有同名文件；
     *         -2 = 移动失败（占用 / 权限 / 拷贝失败）；-3 = 目标分类不存在或没选分类；
     *         -4 = 这是你自己编写的本地源，禁止移动（自动刷新回填 / 删除 / 移动 三项都不做）
     */
    public static int moveSiteTo(String api, String ext, String homePage, String category) {
        movedPath = null;
        // r26-②：你自己编写的本地源禁止移动 —— 只提示，不做任何改动
        if (isSelfAuthored(api, ext, homePage)) {
            DebugLogStore.add(TAG, "自己编写的本地源：拒绝移动（" + api + "）");
            return BLOCK_SELF_AUTHORED;
        }
        try {
            if (TextUtils.isEmpty(category)) return -3;
            JsonObject data = settings();
            File file = siteFile(api, ext, homePage);
            if (file == null) return 0;
            String dir = categoryTargets(file, data).get(category);
            if (TextUtils.isEmpty(dir)) return -3;
            File current = currentCategoryDir(file, data);
            // 保留「分类文件夹之内」的相对结构（直播类/子目录/a.py → 电影类/子目录/a.py），
            // 这样同名但分放两个子目录的源不会在搬家后撞在一起。
            String rel = current == null ? null : relativeUnder(current, file);
            if (TextUtils.isEmpty(rel)) rel = file.getName();
            File dest = new File(dir, rel);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return -2;
            if (dest.exists()) return -1;
            if (!moveFile(file, dest)) return -2;
            movedPath = dest.getAbsolutePath();
            DebugLogStore.add(TAG, "源文件搬家：" + file.getAbsolutePath() + " → " + movedPath);
            String address = relPath(dest, outputBaseDir(data));
            int rewritten = rewriteJsonPaths(file, dest, address, data);
            DebugLogStore.add(TAG, "json 指向已改写 " + rewritten + " 处，新地址 " + address);
            refreshAfterMove();
            return 1;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "源文件搬家失败：" + e);
            return -2;
        }
    }

    /**
     * r62-②：按**绝对目录**搬家（不经过分类候选解析）。
     *
     * <p>为什么需要：{@link #moveSiteTo} 的 category 只能是 {@link #moveTargets} 里列出来的 key，
     * 而「移进失效列表」「回退到上一次分类」这两个动作的目标目录可能是 <b>刚被搬空的目录</b>
     * （{@code collectCategories} 要求目录里还有文件）或 {@code blocked_dirs} 里的目录 —— 都不在候选里，
     * 用 moveSiteTo 只会拿到 -3「分类不存在」。这里直接给定目标目录，其余逻辑（保持子结构、改写 json 指向、
     * 回写产物）与 moveSiteTo 完全一致。</p>
     *
     * @param targetDir 目标分类目录的绝对路径（不存在则创建）
     * @return 1 成功｜0 不是本地源文件｜-1 目标已有同名文件或原地未动｜-2 移动失败｜-3 目标目录不可用｜-4 自写源
     */
    public static int moveSiteToDir(String api, String ext, String homePage, String targetDir) {
        movedPath = null;
        try {
            if (TextUtils.isEmpty(targetDir)) return -3;
            JsonObject data = settings();
            File file = siteFile(api, ext, homePage);
            if (file == null) return 0;
            if (isSelfAuthored(api, ext, homePage)) return BLOCK_SELF_AUTHORED;
            File dir = new File(targetDir);
            if (!dir.exists() && !dir.mkdirs()) return -3;
            if (!dir.isDirectory()) return -3;
            File current = currentCategoryDir(file, data);
            String rel = current == null ? null : relativeUnder(current, file);
            if (TextUtils.isEmpty(rel)) rel = file.getName();
            File dest = new File(dir, rel);
            if (dest.getAbsolutePath().equals(file.getAbsolutePath())) return -1;
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return -2;
            if (dest.exists()) return -1;
            if (!moveFile(file, dest)) return -2;
            movedPath = dest.getAbsolutePath();
            String address = relPath(dest, outputBaseDir(data));
            int rewritten = rewriteJsonPaths(file, dest, address, data);
            DebugLogStore.add(TAG, "源文件搬家：" + file.getAbsolutePath() + " → " + movedPath + "（json 指向已改写 " + rewritten + " 处）");
            refreshAfterMove();
            return 1;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "源文件搬家失败：" + e);
            return -2;
        }
    }

    /**
     * r62-②：源文件**当前所在的分类目录**（绝对路径）；不是本地源文件（远程/内置）返回 null。
     *
     * <p>「回退到上一次分类」需要先知道它现在在哪 —— 而 {@code currentCategoryDir} 是私有的。</p>
     */
    public static String categoryDirOf(String api, String ext, String homePage) {
        try {
            File file = siteFile(api, ext, homePage);
            if (file == null) return null;
            File dir = currentCategoryDir(file, settings());
            return dir == null ? null : dir.getAbsolutePath();
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "取源所在分类目录失败：" + e);
            return null;
        }
    }

    /** r62-②：源文件的绝对路径（远程/内置源返回 null）。 */
    public static String sourceFilePathOf(String api, String ext, String homePage) {
        try {
            File file = siteFile(api, ext, homePage);
            return file == null ? null : file.getAbsolutePath();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * r25-③：分类名 → 目标分类文件夹（绝对路径）。候选 = 「源分类文件夹」，也就是类型目录下的子目录
     * （{@code PY类/影视类[剧]}、{@code PQ类/[胡佬]}、{@code JS[Drpy]/影视类[剧]} …），
     * 分类名同样按 {@link #categoryOf} 取——与扫描时给源打的分类标记是同一套规则。
     *
     * <p>顺序即优先级：① 源自己所在类型目录下的分类（引擎/类型一定不变）；
     * ② 其它「同类」类型目录（目录关键词一致）下的同名分类。跨类型目录（json 源搬进 PY类 之类）
     * 会让源的引擎失效，一律不列。
     */
    private static Map<String, String> categoryTargets(File file, JsonObject data) {
        Map<String, String> result = new LinkedHashMap<>();
        File type = currentTypeDir(file, data);
        String family = type == null ? null : dirKeywords(type.getName());
        collectCategories(type, data, result);
        for (String dir : enabledScanDirs(data)) {
            File[] children = new File(dir).listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!child.isDirectory() || child.equals(type)) continue;
                if (family == null || !family.equals(dirKeywords(child.getName()))) continue;
                collectCategories(child, data, result);
            }
        }
        // r40-⑤：再补上**扫描目录下的所有文件夹**（键 = 相对扫描目录的路径，如 JS类/影视类[剧]）——
        // 用户要求「移动到本地源文件夹的扫描目录下的任意文件夹」，不再只限于同族分类。
        collectFolderPaths(data, result);
        return result;
    }

    /** r40-⑤：扫描目录下的所有文件夹（深度 ≤3），键用相对扫描目录的路径。 */
    private static void collectFolderPaths(JsonObject data, Map<String, String> result) {
        for (String dir : enabledScanDirs(data)) {
            File root = new File(dir);
            collectFolderPaths(root, root, 0, result);
        }
    }

    private static void collectFolderPaths(File root, File dir, int depth, Map<String, String> result) {
        if (dir == null || depth > 3 || result.size() > 500) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")) continue;
            String rel = relativeUnder(root, child);
            if (!TextUtils.isEmpty(rel) && !result.containsKey(rel)) result.put(rel, child.getAbsolutePath());
            collectFolderPaths(root, child, depth + 1, result);
        }
    }

    /** r25-③：收一个类型目录下的源分类文件夹（跳过屏蔽目录与空目录），同名分类只留第一个。 */
    private static void collectCategories(File type, JsonObject data, Map<String, String> result) {
        if (type == null || !type.isDirectory()) return;
        List<String> blocked = list(data, "blocked_dirs");
        File[] children = type.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory() || blocked.contains(child.getName())) continue;
            if (!hasAnyFile(child, 0)) continue;
            String name = categoryOf(child.getName());
            if (TextUtils.isEmpty(name) || result.containsKey(name)) continue;
            result.put(name, child.getAbsolutePath());
        }
    }

    /** r25-③：目录里至少有一个文件（含子目录，深度 ≤ 3）—— 空目录 / 纯结构目录不当目标。 */
    private static boolean hasAnyFile(File dir, int depth) {
        if (depth > 3) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile()) return true;
            if (file.isDirectory() && hasAnyFile(file, depth + 1)) return true;
        }
        return false;
    }

    /** r25-③：源文件所在的类型目录（扫描目录下的一级子目录，如 PY类 / PQ类 / JS[Drpy]）。 */
    private static File currentTypeDir(File file, JsonObject data) {
        if (file == null) return null;
        for (String dir : enabledScanDirs(data)) {
            String rel = relativeUnder(new File(dir), file);
            if (TextUtils.isEmpty(rel)) continue;
            int cut = rel.indexOf('/');
            if (cut <= 0) return null;
            File child = new File(dir, rel.substring(0, cut));
            if (child.isDirectory()) return child;
        }
        return null;
    }

    /** r25-③：类型目录的「关键词族」——一致的目录之间搬家不会改掉源的引擎（PQ类 / YQ类 / JS[Drpy] / 本地包）。 */
    private static String dirKeywords(String name) {
        StringBuilder builder = new StringBuilder();
        if (containsAny(name, Arrays.asList(XBPQ_DIR_KEYWORDS))) builder.append("xbpq,");
        if (containsAny(name, Arrays.asList(XYQ_DIR_KEYWORDS))) builder.append("xyq,");
        if (containsAny(name, Arrays.asList(PY_DIR_KEYWORDS))) builder.append("py,");
        if (containsAny(name, Arrays.asList(DRPY_DIR_KEYWORDS))) builder.append("drpy,");
        if (containsAny(name, Arrays.asList(PKG_DIR_KEYWORDS))) builder.append("pkg,");
        return builder.toString();
    }

    /**
     * r24 / r25-③：源文件所在的分类文件夹 = 它自己的父目录。
     *
     * <p>分类就是「所在文件夹名」（与 {@link #walk} 给源打分类的规则完全一致），所以父目录才是分类目录；
     * r24 老实现取的是「扫描目录下的一级子目录」，对 {@code PY类/瑟瑟类[密]/a.py} 这种会算出
     * 「PY类」，于是界面上会把自己所在的分类也列成可移动目标（搬了等于没搬）。
     */
    private static File currentCategoryDir(File file, JsonObject data) {
        if (file == null) return null;
        for (String dir : enabledScanDirs(data)) {
            if (TextUtils.isEmpty(relativeUnder(new File(dir), file))) continue;
            File parent = file.getParentFile();
            return parent != null && parent.isDirectory() ? parent : null;
        }
        return null;
    }

    /** r24：file 相对 dir 的路径；不在 dir 里面返回 null。 */
    private static String relativeUnder(File dir, File file) {
        if (dir == null || file == null) return null;
        String base = dir.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (path.equals(base)) return "";
        return path.startsWith(base + "/") ? path.substring(base.length() + 1) : null;
    }

    /** r24：搬家 —— 先 renameTo，跨挂载点失败时退化为拷贝 + 删原件（长度不一致视为失败）。 */
    private static boolean moveFile(File from, File to) {
        try {
            if (from.renameTo(to)) return true;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "renameTo 失败，改用拷贝：" + e);
        }
        try (java.io.InputStream in = new java.io.FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
            if (to.length() != from.length()) return false;
            return from.delete();
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "源文件拷贝失败：" + e);
            return false;
        }
    }

    /**
     * r24：把扫描目录（含生成产物）里所有配置 json 中「指向被搬家文件」的字段改写成新地址。
     *
     * <p>判据只有一条：条目里 {@code ext / homePage / api} 解析出的文件正好是被搬家的那个文件
     * （与 {@link #sourcePathOf} 同一套解析，顺序也一致）。只有真改动了才写回，不会格式化别的文件。
     *
     * @return 改写的字段个数
     */
    private static int rewriteJsonPaths(File oldFile, File newFile, String address, JsonObject data) {
        // r63-③：批量搬家时先攒着，最后统一一轮改写（避免每个源都把所有配置 json 读/写一遍）
        Batch batch = BATCH.get();
        if (batch != null) {
            batch.rewrites.add(new String[]{oldFile.getAbsolutePath(), newFile.getAbsolutePath(), address, newFile.getName()});
            return 0;
        }
        int total = 0;
        String oldAbs = oldFile.getAbsolutePath();
        for (File json : configJsonFiles(data)) {
            // r26-②：你自己编写的配置 json 不做自动刷新回填 —— 一个字段都不改
            if (isUserOwnedConfig(json.getAbsolutePath())) {
                DebugLogStore.add(TAG, "自己编写的本地源：跳过 json 回填 " + json.getAbsolutePath());
                continue;
            }
            try {
                String text = readTextFile(json);
                // 廉价筛查：文件里连（原样或 URL 编码后的）源文件名都没出现，就不可能指向它，不必解析
                if (TextUtils.isEmpty(text) || text.length() > MAX_JSON_CHARS) continue;
                String name = newFile.getName();
                if (!text.contains(name) && !text.contains(Uri.encode(name))) continue;
                JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                int count = rewriteJsonArray(root.get("sites"), oldAbs, address, data) + rewriteJsonArray(root.get("lives"), oldAbs, address, data);
                if (count == 0) continue;
                writeTextFile(json, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root));
                total += count;
                DebugLogStore.add(TAG, "已改写 json 指向：" + json.getAbsolutePath() + "（" + count + " 处）");
            } catch (Throwable e) {
                DebugLogStore.add(TAG, "改写 json 失败（跳过 " + json.getName() + "）：" + e);
            }
        }
        return total;
    }

    /** r24：sites / lives 数组里指向 oldAbs 的地址字段改成新地址（一条源只改一个字段）。 */
    private static int rewriteJsonArray(JsonElement array, String oldAbs, String address, JsonObject data) {
        if (array == null || !array.isJsonArray()) return 0;
        int count = 0;
        for (JsonElement element : array.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            for (String key : new String[]{"ext", "homePage", "api"}) {
                String raw = primitiveString(item, key);
                if (TextUtils.isEmpty(raw)) continue;
                if (!pointsTo(raw, oldAbs, data)) continue;
                item.addProperty(key, address);
                count++;
                break;
            }
        }
        return count;
    }

    /**
     * r24：raw 地址是否指向 oldAbs。
     *
     * <p>关键点：搬家是在改写 json *之前*做的，被搬走的文件在磁盘上已经不存在了，所以这里只能做
     * 「路径层面」的比较（相对路径按各基准目录展开，含 URL 解码后的写法），绝不能再去要求文件存在 ——
     * 否则旧路径永远解析不出文件，json 指向就一处也改不动。
     */
    private static boolean pointsTo(String raw, String oldAbs, JsonObject data) {
        for (String candidate : absoluteCandidates(raw, data)) {
            if (candidate.equals(oldAbs)) return true;
        }
        return false;
    }

    /** r24：raw 地址所有可能的绝对路径写法（相对路径按生成目录 / 各扫描目录展开），不触碰文件系统。 */
    private static List<String> absoluteCandidates(String raw, JsonObject data) {
        List<String> result = new ArrayList<>();
        for (String path : parseSourcePath(raw)) {
            if (TextUtils.isEmpty(path)) continue;
            if (path.startsWith("/")) {
                if (!result.contains(path)) result.add(path);
                continue;
            }
            for (String dir : sourceDirs(data)) {
                if (TextUtils.isEmpty(dir)) continue;
                String base = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
                String candidate = base + "/" + path;
                if (!result.contains(candidate)) result.add(candidate);
            }
        }
        return result;
    }

    /** r24：扫描目录里的配置 json（深度 ≤ 3，跳过屏蔽目录）+ 生成产物自身。 */
    private static List<File> configJsonFiles(JsonObject data) {
        List<File> result = new ArrayList<>();
        for (String dir : enabledScanDirs(data)) {
            File root = new File(dir);
            if (root.isDirectory()) collectJson(root, result, 0, data);
        }
        String output = outputPath(data);
        if (!TextUtils.isEmpty(output)) {
            File file = new File(output);
            if (file.isFile() && !result.contains(file)) result.add(file);
        }
        return result;
    }

    private static void collectJson(File dir, List<File> out, int depth, JsonObject data) {
        if (depth > 3) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        List<String> blocked = list(data, "blocked_dirs");
        for (File file : files) {
            if (file.isDirectory()) {
                if (!blocked.contains(file.getName())) collectJson(file, out, depth + 1, data);
            } else if (file.getName().toLowerCase(Locale.US).endsWith(".json") && !out.contains(file)) {
                out.add(file);
            }
        }
    }

    /** r24：读文本文件（失败返回 null）。 */
    private static String readTextFile(File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable e) {
            return null;
        }
    }

    /** r24：写文本文件（失败返回 false）。 */
    private static boolean writeTextFile(File file, String text) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "写 json 失败：" + file.getAbsolutePath() + "：" + e);
            return false;
        }
    }

    // ==================== 扫描 ====================

    private static final class Scan {
        final List<JsonObject> sites = new ArrayList<>();
        final List<JsonObject> lives = new ArrayList<>();
        final Set<String> paths = new LinkedHashSet<>();
        /** 目录里出现过的后缀（诊断用）。 */
        final Set<String> types = new LinkedHashSet<>();
        /** r17-②③：扫描目录下真实存在的文件夹名（源分类白名单）。 */
        final Set<String> categories = new LinkedHashSet<>();
        int files;
        /** 通过类型勾选、真正生成条目的文件数。 */
        int accepted;
        int skipped;
        boolean truncated;
        final List<String> reasons = new ArrayList<>();

        void skip(String name, String reason) {
            skipped++;
            if (reasons.size() < 12) reasons.add(name + "：" + reason);
        }
    }

    private static JsonObject scan() {
        long start = System.currentTimeMillis();
        JsonObject data = settings();
        Scan scan = new Scan();
        String base = outputBaseDir(data);
        String phpPrefix = "http://0.0.0.0:" + DEFAULT_PHP_PORT;
        for (String dir : enabledScanDirs(data)) {
            File path = new File(dir);
            if (!path.isDirectory()) {
                scan.skip(dir, "目录不存在或不可读");
                continue;
            }
            if (list(data, "blocked_dirs").contains(path.getName())) continue;
            walk(path, 0, "", false, false, false, false, scan, data, base, phpPrefix);
        }

        List<JsonObject> sites = new ArrayList<>();
        JsonObject asset = asset();
        addAll(sites, asset.get("PINNED_SITES"));
        sites.addAll(scan.sites);
        addAll(sites, asset.get("MANUAL_SITES"));
        List<JsonObject> merged = deduplicate(sites);
        // r29-⑥③：取消「同名源只保留一个」——默认有什么就生成什么，不再按引擎优先级折叠同名源。
        for (JsonObject site : merged) stripHints(site);
        // r17-②③：内置附加源与扫描结果一视同仁 —— 不是文件夹名的分类整条删除（不再有「主页 / 设置」例外）
        int dropped = enforceFolderCategories(merged, scan.categories);
        if (dropped > 0) DebugLogStore.add(TAG, "分类收口：删掉 " + dropped + " 条源里非文件夹名的分类");

        // r17-⑤：直播源地址留空 → 不写这条直播；写了 → 沿用脚本那条直播的结构（名字 / EPG / logo）只换 url
        JsonArray lives = new JsonArray();
        String liveUrl = getLiveUrl();
        if (!TextUtils.isEmpty(liveUrl)) {
            JsonElement templates = asset.get("DEFAULT_LIVES");
            if (templates != null && templates.isJsonArray()) for (JsonElement item : templates.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject live = item.getAsJsonObject().deepCopy();
                live.addProperty("url", liveUrl);
                lives.add(live);
            }
        }
        for (JsonObject live : scan.lives) lives.add(live);

        JsonObject config = new JsonObject();
        // spider jar：getSpiderJar() 给出手动指定值或探测到的默认 jar，为空不写这行；
        // ./ 开头的直接原样写，绝对路径才转换成脚本写法（输出目录内用 ./ 相对路径）。
        String spiderJar = getSpiderJar();
        if (!TextUtils.isEmpty(spiderJar)) {
            if (spiderJar.startsWith("./") || spiderJar.startsWith("../")) config.addProperty("spider", spiderJar);
            else config.addProperty("spider", relPath(new File(spiderJar), base));
        }
        // logo / wallpaper 只认生成文件旁 jar 文件夹里的本地图（gif > jpg > png > webp）；
        // 都没有就不写这两行 —— 壳子加载时缺这两项会用自带默认，不再写死默认表里的网络地址。
        String localLogo = detectJarImage(base, "logo");
        if (!TextUtils.isEmpty(localLogo)) config.addProperty("logo", localLogo);
        String localWallpaper = detectJarImage(base, "wallpaper");
        if (!TextUtils.isEmpty(localWallpaper)) config.addProperty("wallpaper", localWallpaper);
        config.add("lives", lives);
        JsonArray siteArray = new JsonArray();
        for (JsonObject site : merged) siteArray.add(site);
        config.add("sites", siteArray);

        // 与脚本一致：额外配置（parses / rules / doh / flags / ijk / ads），parses 用设置里的条目
        JsonObject extra = new JsonObject();
        try {
            if (asset.has("EXTRA") && asset.get("EXTRA").isJsonObject()) extra = asset.getAsJsonObject("EXTRA").deepCopy();
        } catch (Throwable ignored) {
        }
        extra.add("parses", enabledParses(data));
        for (var entry : extra.entrySet()) config.add(entry.getKey(), entry.getValue());

        long cost = System.currentTimeMillis() - start;
        String summary = "站点 " + merged.size() + " · 直播 " + lives.size() + " · 文件 " + scan.files
            + " · 命中 " + scan.accepted + " · 跳过 " + scan.skipped + " · " + cost + "ms" + (scan.truncated ? "（已截断）" : "");
        Prefers.put(KEY_SUMMARY, summary);
        lastSites = merged.size();
        String diagnosis = diagnosis(scan, data);
        Prefers.put(KEY_HINT, diagnosis);
        DebugLogStore.add(TAG, "扫描完成：" + summary);
        DebugLogStore.add(TAG, "扫描诊断：" + diagnosis);
        for (String dir : enabledScanDirs(data)) DebugLogStore.add(TAG, "扫描目录：" + dir + "（基准 " + base + "）");
        if (!scan.reasons.isEmpty()) DebugLogStore.add(TAG, "跳过明细：" + TextUtils.join("；", scan.reasons));
        return config;
    }

    private static JsonArray enabledParses(JsonObject data) {
        JsonArray result = new JsonArray();
        JsonElement entries = data.get("parse_entries");
        JsonArray source = entries != null && entries.isJsonArray() ? entries.getAsJsonArray() : assetParses(true);
        for (JsonElement item : source) {
            if (!item.isJsonObject()) continue;
            JsonObject entry = item.getAsJsonObject();
            if (!bool(entry, "enabled", true)) continue;
            JsonObject clean = entry.deepCopy();
            clean.remove("enabled");
            result.add(clean);
        }
        return result;
    }

    private static void walk(File dir, int depth, String parentCategory, boolean parentXbpq, boolean parentXyq, boolean parentDrpy, boolean parentLive, Scan scan, JsonObject data, String base, String phpPrefix) {
        if (depth > maxDepth(data) || scan.truncated) return;
        String dirName = dir.getName();
        // r17-④：不再按「18+ 标签目录」跳过任何目录，一律照常扫描。
        // r16-④：源分类＝该文件所在文件夹的名字（不再沿用上级 [标签]，也不再用文件名的标签）
        String category = categoryOf(dirName);
        // r17-②③：记下这个文件夹名，作为「源分类」白名单
        if (!TextUtils.isEmpty(category)) scan.categories.add(category);

        boolean isLive = parentLive || LIVE_DIR_NAME.equals(dirName);
        boolean isXbpq = parentXbpq || containsAny(dirName, Arrays.asList(XBPQ_DIR_KEYWORDS));
        boolean isXyq = parentXyq || containsAny(dirName, Arrays.asList(XYQ_DIR_KEYWORDS));
        boolean isDrpy = parentDrpy || dirKey(dirName, DRPY_DIR_KEYWORDS);
        // r29-①：cat类 目录强制回到 cat 形态（api 直接指向脚本）——即使它不是 drpy 目录，
        // 或被放在 drpy 目录之下继承到了 isDrpy，也要按「cat 源」生成。
        // r65：cat 目录永远按「cat 写法」（api 直接指向脚本、不带 ext）生成，绝不套 drpy 写法。
        if (dirKey(dirName, CAT_DIR_KEYWORDS)) isDrpy = false;

        File[] files = dir.listFiles();
        if (files == null) {
            scan.skip(dirName, "无法读取目录（缺少存储权限？）");
            return;
        }
        Arrays.sort(files, (left, right) -> compareGbk(left.getName(), right.getName()));
        List<String> blocked = list(data, "blocked_dirs");
        Set<String> selected = normalizeExtensions(list(data, "scan_extensions"));
        // r29-⑥：扫描模式设置项已删除 —— 固定按「站源模式」（脚本 / 规则）扫描。
        String mode = "source";
        boolean decode = bool(data, "decode_base64", true);

        for (File file : files) {
            if (scan.truncated) return;
            String name = file.getName();
            if (blocked.contains(name)) continue;
            if (file.isDirectory()) {
                walk(file, depth + 1, category, isXbpq, isXyq, isDrpy, isLive, scan, data, base, phpPrefix);
                continue;
            }
            if (!file.isFile() || isRuntimeGenerated(file, data, base)) continue;
            // r15：同一物理文件（软链接 / 重叠扫描根）只收一次
            String physical = normalizedPath(file.getAbsolutePath());
            if (!TextUtils.isEmpty(physical) && !scan.paths.add(physical)) continue;
            if (scan.files >= MAX_FILES || scan.sites.size() >= MAX_SITES) {
                scan.truncated = true;
                return;
            }
            scan.files++;
            Ext ext = extOf(name);
            if (!TextUtils.isEmpty(ext.suffix)) scan.types.add(ext.suffix);
            if (!supported(ext, isXbpq, isXyq, isDrpy, mode, selected)) continue;
            if ("json".equals(ext.full) && (isXbpq || isXyq) && decode) decodeBase64File(file);
            scan.accepted++;
            String localPath = relPath(file, base);
            String clean = removeTags(ext.base);
            String siteName = TextUtils.isEmpty(clean) ? ext.base : clean;
            if (isLive) {
                scan.lives.add(live(liveName(siteName, category), localPath));
                if (scan.lives.size() >= MAX_LIVES) scan.truncated = true;
                continue;
            }
            JsonObject built = buildSite(siteName, category, ext, localPath, phpPrefix, isXbpq, isXyq, isDrpy, data, base);
            built.addProperty(HINT_PATH, physical);
            built.addProperty(HINT_BASE, ext.base);
            built.addProperty(HINT_LABEL, ext.suffix);
            scan.sites.add(built);
        }
    }

    /**
     * 与脚本 build_site_config 对齐；引擎判定仍按完整后缀（xbpq / xyq / wv.js / drpy），
     * 但 r16-④ 起名字末尾的 {@code (类型)} 一律写「最后一个后缀」。
     */
    private static JsonObject buildSite(String name, String category, Ext ext, String localPath, String phpPrefix, boolean isXbpq, boolean isXyq, boolean isDrpy, JsonObject data, String base) {
        String label = ext.suffix;
        // 2026-11-11 Liu 定（v39，纠正 v38 塞错格子）：站名格式是 名[文件夹分类](源类型)——
        // [] 里是文件夹分类名（照旧），() 里是源类型，统一写规范名：XBPQ 规则 (JSON)、
        // XYQ (XYQ)、py (PY)、drpy (JS)、cat 式 js (CAT)；五类之外（ws / php / html /
        // txt 等）照旧写原始后缀。长按搜索的「按源类型搜索」按钮从站名 () 现扒，跟着统一。
        if (isXbpq && "json".equals(ext.full)) label = "JSON";
        else if (isXyq && "json".equals(ext.full)) label = "XYQ";
        else if ("py".equals(ext.full)) label = "PY";
        else if (isDrpy && "js".equals(ext.full)) label = "JS";
        else if ("js".equals(ext.full)) label = "CAT";
        if (isXbpq && "json".equals(ext.full)) {
            return site(fullName(name, category, label), "csp_XBPQ", localPath, false, 1, null, null);
        }
        if (isXyq && "json".equals(ext.full)) {
            // YQ 类：api + jar 两行都要写，jar 是 XYQ 自己的 ./jar/xyqxbpq.jar（顶层 spider
            // 是 XBPQ 的 xbpq251118.jar，盖不住 XYQ；此前只写了 api、没写 jar，导致 YQ 源跑不起来）。
            // 字段顺序按 Liu 给的示例：key / name / type / jar / api / searchable / quickSearch
            // / filterable / changeable / ext（不带 switchable）。
            String full = fullName(name, category, label);
            JsonObject object = new JsonObject();
            object.addProperty("key", full);
            object.addProperty("name", full);
            object.addProperty("type", 3);
            object.addProperty("jar", "./jar/xyqxbpq.jar");
            object.addProperty("api", "csp_XYQHiker");
            object.addProperty("searchable", 1);
            object.addProperty("quickSearch", 1);
            object.addProperty("filterable", 1);
            object.addProperty("changeable", 1);
            object.addProperty("ext", localPath);
            return object;
        }
        if (isDrpy && "js".equals(ext.full)) {
            JsonObject object = site(fullName(name, category, label), drpyApiPath(base), localPath, false, 1, null, null);
            object.addProperty("order_num", 0);
            return object;
        }
        if ("php".equals(ext.full)) {
            String cleanPath = localPath.startsWith("./") ? localPath.substring(2) : localPath;
            JsonObject object = new JsonObject();
            String full = fullName(name, category, label);
            object.addProperty("key", full);
            object.addProperty("name", full);
            object.addProperty("type", 4);
            object.addProperty("api", phpPrefix + "/" + cleanPath);
            object.addProperty("searchable", 1);
            object.addProperty("quickSearch", 1);
            object.addProperty("filterable", 1);
            object.addProperty("changeable", 1);
            return object;
        }
        if ("wv.js".equals(ext.full)) {
            return siteWithJar(fullName(name, category, label), "csp_WvSpider", "./jar/WvSpider.jar", localPath);
        }
        if ("py".equals(ext.full)) {
            // MissAV 固定生成：文件名含 MissAV 字样时（跟金牌一样按“包含”判定，不再
            // 要求整名恰好等于 MissAV），ext 必须是对象 {site, cfgo, proxy}，
            // 顺序按 Liu 示例：key / name / type / api / searchable / quickSearch / filterable
            // / switchable / ext（不带 changeable）。site 固定 https://missav.ai，proxy 固定 {}。
            // cfgo 写相对路径（与 api 同一种 ./ 写法：跟 py 文件同目录，跨设备可移植；
            // 2026-11-01 Liu 定：很多电视设备不认写死在配置里的绝对路径，改回相对）。
            // 相对能跑起来的关键在加载侧：resolveRelative 会把 ext 对象里的 cfgo 按配置
            // 所在目录展开成纯绝对路径再交给 Spider（Spider.resolveCfgoPath 只认绝对
            // 路径 / file://，不认 ./），所以文件里存相对、运行时仍是绝对。定位顺序
            // 不变：先看 MissAV.py 所在目录下有没有 cf-proxy-linux-arm64（纠正：文件夹
            // 是 PY类，不是 py），有就用它；没有就到已勾选扫描目录里按文件名找（写成
            // 相对基准目录的相对路径）；都找不到也按 MissAV.py 所在目录拼一个相对路径
            // （二进制就该跟 py 文件同在 PY类 里，日后放进去即生效）。
            if (ext.base != null && ext.base.toLowerCase(Locale.US).contains("missav")) {
                String full = fullName(name, category, label);
                JsonObject object = new JsonObject();
                object.addProperty("key", full);
                object.addProperty("name", full);
                object.addProperty("type", 3);
                object.addProperty("api", localPath);
                object.addProperty("searchable", 1);
                object.addProperty("quickSearch", 1);
                object.addProperty("filterable", 1);
                object.addProperty("switchable", 1);
                JsonObject extObject = new JsonObject();
                extObject.addProperty("site", "https://missav.ai");
                extObject.addProperty("cfgo", missavCfgoRelative(localPath, data, base));
                extObject.add("proxy", new JsonObject());
                object.add("ext", extObject);
                return object;
            }
            // PY类金牌：文件名含“金牌”字样的 py 站点，在条目最下面加两处：playerType 2
            // 与 ext.site（逗号分隔的域名串，原样保留，包括其中重复的 sizhengxt 与
            // 9zhoukj 末尾的斜杠，不去重、不归一化）。其余字段（key/name/type/api/
            // searchable/quickSearch/filterable/switchable）仍按现有 py 生成，不改动。
            if (ext.base != null && ext.base.contains("金牌")) {
                JsonObject object = site(fullName(name, category, label), localPath, null, true, 0, null, null);
                object.addProperty("playerType", 2);
                JsonObject extObject = new JsonObject();
                extObject.addProperty("site", "https://www.hkybqufgh.com,https://www.sizhengxt.com,https://0996zp.com,https://9zhoukj.com/,https://www.sizhengxt.com,https://www.tjrongze.com,https://www.jiabaide.cn,https://cqzuoer.com");
                object.add("ext", extObject);
                return object;
            }
            JsonObject object = site(fullName(name, category, label), localPath, null, true, 0, null, null);
            return object;
        }
        if ("js".equals(ext.full)) {
            return site(fullName(name, category, label), localPath, null, true, 0, null, null);
        }
        if ("html".equals(ext.full)) {
            JsonObject object = site(fullName(name, category, label), "csp_Nostr", null, true, 0, null, null);
            object.addProperty("homePage", localPath);
            return object;
        }
        if (Arrays.asList("txt", "m3u", "json", "db", "zip", "pkg").contains(ext.full)
            || Arrays.asList("txt", "m3u", "json", "db", "zip", "pkg").contains(ext.simple)) {
            return fileSite(fullName(name, category, label), localPath);
        }
        return fileSite(fullName(name, category, TextUtils.isEmpty(label) ? "file" : label), localPath);
    }

    /**
     * py / js 站点：api 直接用（脚本写法是 ./相对路径，这里在同一份产物里保持脚本原样，
     * 解析配置前由 resolveRelative() 展开成宿主机可读地址）。
     */
    private static JsonObject site(String full, String api, String ext, boolean switchable, int changeable, String jar, String ignore) {
        JsonObject object = new JsonObject();
        object.addProperty("key", full);
        object.addProperty("name", full);
        object.addProperty("type", 3);
        object.addProperty("api", api);
        object.addProperty("searchable", 1);
        object.addProperty("quickSearch", 1);
        object.addProperty("filterable", 1);
        if (changeable == 1) object.addProperty("changeable", 1);
        if (switchable) object.addProperty("switchable", 1);
        if (!TextUtils.isEmpty(ext)) object.addProperty("ext", ext);
        if (!TextUtils.isEmpty(jar)) object.addProperty("jar", jar);
        return object;
    }

    /** wv.js：csp_WvSpider + jar + switchable（与脚本一致，不带 changeable）。 */
    private static JsonObject siteWithJar(String full, String api, String jar, String extPath) {
        JsonObject object = site(full, api, extPath, true, 0, null, null);
        object.addProperty("jar", jar);
        object.addProperty("switchable", 1);
        return object;
    }

    /** MissAV.py 的 cfgo 相对路径（与 api 同一种 ./ 写法：跟 py 文件同目录，跨设备可移植；加载时由 resolveRelative 展开成绝对路径）。 */
    private static String missavCfgoRelative(String localPath, JsonObject data, String base) {
        String pyAbs = "";
        if (!TextUtils.isEmpty(localPath)) {
            if (localPath.startsWith("./")) {
                pyAbs = TextUtils.isEmpty(base) ? localPath.substring(2) : base + File.separator + localPath.substring(2);
            } else if (localPath.startsWith("file://")) {
                pyAbs = localPath.substring(7);
            } else {
                pyAbs = localPath;
            }
        }
        String parent = "";
        if (!TextUtils.isEmpty(pyAbs)) {
            File parentFile = new File(pyAbs).getParentFile();
            if (parentFile != null) parent = parentFile.getAbsolutePath();
        }
        // 相对写法 = py 的相对目录 + 二进制名（localPath 以 ./ 开头时换掉文件名即可，基准天然一致）。
        String beside = "";
        if (!TextUtils.isEmpty(localPath) && localPath.startsWith("./")) {
            int slash = localPath.lastIndexOf('/');
            beside = (slash >= 0 ? localPath.substring(0, slash + 1) : "./") + "cf-proxy-linux-arm64";
        }
        // 1) MissAV.py 所在目录（即 PY类）下有二进制就用它；
        if (!TextUtils.isEmpty(parent)) {
            File candidate = new File(parent, "cf-proxy-linux-arm64");
            if (candidate.isFile()) return !TextUtils.isEmpty(beside) ? beside : relPath(candidate, base);
        }
        // 2) 到已勾选扫描目录里按文件名找（同名二进制在别的扫描根的 PY类 里也能找到），写成相对基准目录的相对路径；
        File found = findAnyFile(enabledScanDirs(data), "cf-proxy-linux-arm64", maxDepth(data));
        if (found != null) return relPath(found, base);
        // 3) 都找不到：仍按 MissAV.py 所在目录拼相对路径（二进制就该跟 py 文件同在 PY类 里）；
        if (!TextUtils.isEmpty(beside)) return beside;
        if (!TextUtils.isEmpty(parent)) return relPath(new File(parent, "cf-proxy-linux-arm64"), base);
        return "./" + existingDirName(base, "py") + "/cf-proxy-linux-arm64";
    }

    private static JsonObject fileSite(String full, String extPath) {
        JsonObject object = new JsonObject();
        object.addProperty("key", full);
        object.addProperty("name", full);
        object.addProperty("type", 3);
        object.addProperty("api", "csp_FileSpider");
        object.addProperty("jar", "./lib/WvSpider.jar");
        object.addProperty("searchable", 0);
        object.addProperty("quickSearch", 0);
        object.addProperty("filterable", 0);
        object.addProperty("switchable", 0);
        object.addProperty("changeable", 0);
        object.addProperty("ext", extPath);
        return object;
    }

    private static JsonObject live(String name, String url) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("type", 0);
        object.addProperty("url", url);
        object.addProperty("playerType", 2);
        object.addProperty("epg", "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}");
        object.addProperty("logo", "https://11.112114.xyz/logo/" + name + ".png");
        object.addProperty("ua", "");
        return object;
    }

    /**
     * drpy 引擎路径（2026-11-11 Liu 定）：引擎固定住在产物目录下的 {@code js} 文件夹里，
     * 与 js 源同居，api 恒写 {@code ./js/drpy2.min.js}，不再多路探测 / 递归找引擎。
     *
     * <p>废掉探测的理由：r30 的递归找法曾选中无关目录的引擎、还写成绝对路径 → 404 →
     * drpy 源全灭；r33 虽收回到产物目录内，仍有五路候选 + 递归 + 缓存的复杂度。
     * 现在只有一条路：生成时 js 文件夹里缺引擎本体或缺 core，就从内置 assets 复制补齐
     * （文件夹不存在先建）；已有的文件一律不覆盖（用户自备版本优先）。
     * {@code drpy2.min.js} 运行时会去**同目录**加载 {@code drpy-core-lite.min.js}
     * （实测该 js 内引用 core 两处），所以两个文件必须并排放在 js 文件夹里。
     */
    /**
     * 配套文件夹跟随用户现成的大小写：base 下已有同名文件夹（大小写不敏感）就返回
     * 它的真实名，没有才返回 want 本身（调用方照旧新建小写）。避免用户只有大写
     * JS/PY 文件夹时，旁边被另建一个小写文件夹、两份并存。
     */
    private static String existingDirName(String base, String want) {
        if (!TextUtils.isEmpty(base)) {
            File[] kids = new File(base).listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    if (kid.isDirectory() && kid.getName().equalsIgnoreCase(want)) return kid.getName();
                }
            }
        }
        return want;
    }

    private static String drpyApiPath(String base) {
        String folder = existingDirName(base, "js");
        File dir = new File(base, folder);
        File engine = new File(dir, "drpy2.min.js");
        File core = new File(dir, "drpy-core-lite.min.js");
        if (!engine.isFile() && copyEngineFromAsset(engine)) {
            DebugLogStore.add(TAG, "已补齐 drpy 引擎（内置 assets）：" + engine.getAbsolutePath());
        }
        if (!core.isFile()) copyCoreFromAsset(core);
        return "./" + folder + "/drpy2.min.js";
    }

    /** r35-①：从内置 assets 复制 drpy 引擎本体（缺引擎时用）。 */
    private static boolean copyEngineFromAsset(File engine) {
        if (engine.isFile()) return true;
        try (java.io.InputStream in = com.muse.android.tv.App.get().getAssets().open("js/lib/drpy2.min.js")) {
            File parent = engine.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (java.io.OutputStream out = new java.io.FileOutputStream(engine)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            return true;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "从内置 assets 补齐 drpy 引擎失败：" + e);
            return false;
        }
    }

    /**
     * r34：从 APK 内置 assets（{@code js/lib/drpy-core-lite.min.js}，708 KB，与用户脚本同版本）复制一份 core 到引擎目录。
     *
     * <p>引擎与 core 并排放在 js 文件夹里，drpy 源即可正常加载，
     * 不依赖设备上其它目录（避免之前「跑到全设备乱找引擎」那种回归）。</p>
     */
    private static boolean copyCoreFromAsset(File core) {
        try (java.io.InputStream in = com.muse.android.tv.App.get().getAssets().open("js/lib/drpy-core-lite.min.js")) {
            File parent = core.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (java.io.OutputStream out = new java.io.FileOutputStream(core)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            DebugLogStore.add(TAG, "已补齐 drpy 引擎依赖（内置 assets）：" + core.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "从内置 assets 补齐 drpy core 失败：" + e);
            return false;
        }
    }

    /** 直播频道名：{@code 干净名[分类]}（分类 = 所在文件夹名）。 */
    private static String liveName(String name, String category) {
        return name + (TextUtils.isEmpty(category) ? "" : "[" + plain(category) + "]");
    }

    // ==================== 规则判定 ====================

    private static final class Ext {
        String full = "";
        String simple = "";
        String base = "";
        /** r16：文件名的最后一个后缀 —— 「源类型」的显示依据（.wv.js 显示 js）。 */
        String suffix = "";
    }

    private static Ext extOf(String filename) {
        Ext ext = new Ext();
        String lower = filename.toLowerCase(Locale.US);
        if (lower.endsWith(".wv.js")) {
            ext.full = "wv.js";
            ext.simple = "js";
            ext.suffix = "js";
            ext.base = filename.substring(0, filename.length() - 6);
            return ext;
        }
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            ext.base = filename.substring(0, dot);
            ext.full = filename.substring(dot + 1).toLowerCase(Locale.US);
        } else {
            ext.base = filename;
        }
        ext.simple = ext.full;
        ext.suffix = ext.full;
        return ext;
    }

    /**
     * 扫描类型勾选判定（r16-④⑤）：勾选值就是「最后一个后缀」，json 的引擎由所在目录决定。
     *
     * <p>勾选为空 = 全部类型（设置界面默认全不勾选，不会因为没勾就什么都不扫）。
     * r15 之前存过的 {@code xbpq / xyq / wv.js} 会折算成 {@code json / js}。
     */
    private static boolean supported(Ext ext, boolean isXbpq, boolean isXyq, boolean isDrpy, String mode, Set<String> selected) {
        String type = ext.suffix;
        if (TextUtils.isEmpty(type)) return false;
        if ("source".equals(mode)) {
            if ("json".equals(type)) return (isXbpq || isXyq) && selected.contains("json");
            if (Arrays.asList("py", "js", "php", "html").contains(type)) return selected.contains(type);
            return false;
        }
        if ("json".equals(type)) return !isXbpq && !isXyq && selected.contains("json");
        if (Arrays.asList("txt", "m3u", "db", "zip", "pkg").contains(type)) return !isXbpq && !isXyq && !isDrpy && selected.contains(type);
        return false;
    }

    /** 归一化勾选的扫描类型：空 = 全部；旧引擎名折算成真实后缀。 */
    private static Set<String> normalizeExtensions(List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            for (String type : ALL_EXTENSIONS) result.add(type);
            return result;
        }
        for (String item : raw) {
            if (TextUtils.isEmpty(item)) continue;
            String value = item.trim().toLowerCase(Locale.US);
            if ("xbpq".equals(value) || "xyq".equals(value)) result.add("json");
            else if ("wv.js".equals(value)) result.add("js");
            else if (Arrays.asList(ALL_EXTENSIONS).contains(value)) result.add(value);
        }
        if (result.isEmpty()) for (String type : ALL_EXTENSIONS) result.add(type);
        return result;
    }

    /**
     * 生成站点名 {@code 干净文件名[分类](源类型)}（r16-④）。
     *
     * <p>分类 = 该文件所在文件夹名；类型 = 文件名的最后一个后缀。名字里除「分类」外不允许再出现
     * {@code [ ]} —— App 的「源分类」筛选（{@code Site.getGroups()} 抓名字里的 {@code [xx]}）
     * 会把文件名自带的标签（{@code [修复翻页]} 之类）也当成一个新分类。
     */
    private static String fullName(String name, String category, String label) {
        String clean = plain(removeTags(name));
        if (TextUtils.isEmpty(clean)) clean = plain(name);
        if (TextUtils.isEmpty(clean)) clean = "未命名";
        String marker = TextUtils.isEmpty(category) ? "" : "[" + plain(category) + "]";
        return clean + marker + "(" + (label == null ? "" : label) + ")";
    }

    /** 去掉残留的单边方括号，保证名字里的 {@code [...]} 只剩「分类」这一处。 */
    private static String plain(String text) {
        return text == null ? "" : text.replace("[", "").replace("]", "").trim();
    }

    /** 分类名 = 所在文件夹名（去掉方括号标记；目录名本身就叫 [测试] 时取标记里的内容）。 */
    private static String categoryOf(String dirName) {
        if (TextUtils.isEmpty(dirName)) return "";
        String clean = plain(removeTags(dirName));
        if (!TextUtils.isEmpty(clean)) return clean;
        List<String> tags = allTags(dirName);
        if (!tags.isEmpty()) return plain(tags.get(0));
        return dirName.trim();
    }

    private static String removeTags(String text) {
        return text == null ? "" : text.replaceAll("\\[[^\\]]*\\]", "");
    }

    /** 文件名自带标签的内容（{@code 秀色女神[修复翻页].py → 修复翻页}），只用于区分同名源。 */
    private static String tagHint(String base) {
        StringBuilder builder = new StringBuilder();
        for (String tag : allTags(base)) {
            String value = plain(tag);
            if (TextUtils.isEmpty(value)) continue;
            if (builder.length() > 0) builder.append('+');
            builder.append(value);
        }
        return builder.toString();
    }

    /** 把区分文字插在名字的 {@code (类型)} 之前：{@code 秀色女神[漫画类]·修复翻页(py)}。 */
    private static String withHint(String name, String hint) {
        int pos = name.lastIndexOf('(');
        return pos <= 0 ? name + "·" + hint : name.substring(0, pos) + "·" + hint + name.substring(pos);
    }

    private static List<String> allTags(String text) {
        List<String> tags = new ArrayList<>();
        if (text == null) return tags;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[[^\\]]+\\]").matcher(text);
        while (matcher.find()) tags.add(matcher.group());
        return tags;
    }

    /**
     * r65：目录名 → 分类关键词判定（前缀匹配，取代原先的「子串包含」）。
     *
     * <p>用户实际用的目录名是 {@code JS} / {@code cat}（不是早期的 {@code JS类} / {@code cat类}），
     * 子串匹配认不出它们，导致 JS 目录里的脚本被按 cat 写法生成（api 直接指向脚本、drpy 引擎没用上）、
     * cat 目录又可能被父级继承成 drpy 写法。这里改成：
     * 目录名小写、去空格、去方括号后做「前缀匹配」；{@code json} 开头的目录直接排除（json 不是 js 源）。</p>
     */
    private static boolean dirKey(String dirName, String[] keys) {
        if (TextUtils.isEmpty(dirName) || keys == null) return false;
        String plain = dirName.trim().toLowerCase().replace("[", "").replace("]", "").replace(" ", "");
        if (plain.isEmpty() || plain.startsWith("json")) return false;
        for (String key : keys) {
            if (TextUtils.isEmpty(key)) continue;
            String k = key.toLowerCase().replace("[", "").replace("]", "");
            if (plain.startsWith(k)) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.US);
        for (String keyword : keywords) {
            if (TextUtils.isEmpty(keyword)) continue;
            // r29-①：目录关键词匹配不分大小写（JS类 / js类 / Cat类 都要认）
            if (lower.contains(keyword.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    /**
     * 站源去重（r16）：
     * 1) 只有「文件名 + 内容逐字节都相同」才算真重复（同一份文件的复制件 / 同一目录被填了两遍），
     *    折叠成一条；文件名不同的副本一律保留，不再因为内容相同被删掉（r16-①）；
     * 2) 名字撞车但确实是两个不同文件时，用文件名自带的标签内容区分，后缀写成
     *    {@code 名字[分类]·标签(类型)} —— 不再把标签塞进 {@code []}，否则会被当成新分类（r16-②）；
     * 3) 仍然撞车才退化成 (N) 后缀保底，确保 key 唯一。
     */
    private static List<JsonObject> deduplicate(List<JsonObject> sites) {
        List<JsonObject> result = new ArrayList<>();
        Map<String, JsonObject> byKey = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (JsonObject item : sites) {
            JsonObject site = item.deepCopy();
            String key = str(site, "key");
            if (TextUtils.isEmpty(key)) {
                result.add(site);
                used.add(str(site, "name"));
                continue;
            }
            JsonObject exist = byKey.get(key);
            if (exist == null) {
                byKey.put(key, site);
                result.add(site);
                used.add(str(site, "name"));
                continue;
            }
            String path = str(site, HINT_PATH);
            String existPath = str(exist, HINT_PATH);
            if (!TextUtils.isEmpty(path) && !TextUtils.isEmpty(existPath) && sameFileName(path, existPath) && sameContent(path, existPath)) {
                DebugLogStore.add(TAG, "重复源折叠：" + new File(path).getName() + " 与 " + new File(existPath).getName() + " 同名同内容，只保留一条");
                continue;
            }
            int index = 1;
            String unique = key + "_" + index;
            while (byKey.containsKey(unique)) unique = key + "_" + (++index);
            String name = disambiguate(site, index, used);
            site.addProperty("key", unique);
            site.addProperty("name", name);
            used.add(name);
            byKey.put(unique, site);
            result.add(site);
        }
        return result;
    }

    /**
     * 同 key 不同文件时的可解释重命名（r16-②）：把文件名自带的标签内容用 {@code ·} 挂上去，
     * 不再写成 {@code [标签]} —— 名字里的 {@code [...]} 只允许是「分类」，否则 App 会多出一个分类。
     * 名字都用过才退化成 {@code ·N} 序号（不写 {@code (N)}：App 的源类型筛选是
     * {@code TYPE_PATTERN = "\(([^)]+)\)"}，会抓名字里任何一对圆括号，多出的 {@code (1)}
     * 会变成一个新的「源类型」）。
     */
    private static String disambiguate(JsonObject site, int index, Set<String> used) {
        String name = str(site, "name");
        String hint = tagHint(str(site, HINT_BASE));
        if (!TextUtils.isEmpty(hint)) {
            String candidate = withHint(name, hint);
            if (!used.contains(candidate)) return candidate;
        }
        int count = Math.max(index, 1);
        String candidate = withHint(name, String.valueOf(count));
        while (used.contains(candidate)) candidate = withHint(name, String.valueOf(++count));
        return candidate;
    }

    /** 两个物理文件的文件名（含后缀）是否相同：只有同名同内容才算真重复。 */
    private static boolean sameFileName(String left, String right) {
        try {
            return new File(left).getName().equals(new File(right).getName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** 两个物理文件是否字节相同（只在 key 撞车时按需比对，代价可忽略）。 */
    private static boolean sameContent(String left, String right) {
        try {
            File a = new File(left);
            File b = new File(right);
            if (!a.isFile() || !b.isFile() || a.length() != b.length()) return false;
            return md5(a).equals(md5(b));
        } catch (Throwable e) {
            return false;
        }
    }

    private static String md5(File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) digest.update(buffer, 0, length);
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) builder.append(String.format(Locale.US, "%02x", value));
            return builder.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** 可选：同名（忽略结尾引擎标签）多引擎副本只保留优先级最高的一个。 */
    private static List<JsonObject> mergeSameName(List<JsonObject> sites) {
        Map<String, JsonObject> best = new LinkedHashMap<>();
        int removed = 0;
        for (JsonObject site : sites) {
            String name = normalizedName(str(site, "name"));
            JsonObject exist = best.get(name);
            if (exist == null) {
                best.put(name, site);
                continue;
            }
            removed++;
            if (enginePriority(site) > enginePriority(exist)) best.put(name, site);
        }
        if (removed > 0) DebugLogStore.add(TAG, "同名多引擎合并：折叠 " + removed + " 条");
        return new ArrayList<>(best.values());
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name.replaceAll("\\([^()]*\\)\\s*$", "").trim();
    }

    private static int enginePriority(JsonObject site) {
        String api = str(site, "api");
        if ("csp_XBPQ".equals(api)) return 60;
        if ("csp_XYQHiker".equals(api)) return 50;
        if (api.endsWith(".js")) return 45;
        if (api.endsWith(".py")) return 40;
        if (api.startsWith("csp_")) return 30;
        return 20;
    }

    /** 去掉扫描期临时字段，保证产物里只有脚本对应字段。 */
    private static void stripHints(JsonObject site) {
        List<String> keys = new ArrayList<>();
        for (String key : site.keySet()) if (key.startsWith(HINT_PREFIX)) keys.add(key);
        for (String key : keys) site.remove(key);
    }

    // ==================== 路径 ====================

    private static String outputBaseDir(JsonObject data) {
        String path = outputPath(data);
        if (TextUtils.isEmpty(path)) return "";
        File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent == null ? "" : parent.getAbsolutePath();
    }

    /** 脚本 rel_path：输出目录内用 ./ 相对路径，目录外用 file:// 绝对地址。 */
    private static String relPath(File file, String base) {
        String abs = file.getAbsolutePath();
        if (!TextUtils.isEmpty(base) && (abs.equals(base) || abs.startsWith(base + File.separator))) {
            if (abs.equals(base)) return "./";
            return "./" + abs.substring(base.length() + 1);
        }
        return fileUrl(abs);
    }

    /**
     * 目录外文件的可读地址。脚本写法是 {@code file:// + 相对 /storage/emulated/0 的路径}，
     * 那种形式在本壳里解析不出真实文件，这里统一用 {@code file://绝对路径}（本壳会转成内置 /file/ 服务地址）。
     */
    private static String fileUrl(String absolute) {
        return "file://" + absolute;
    }

    private static boolean isRuntimeGenerated(File file, JsonObject data, String base) {
        String abs = file.getAbsolutePath();
        String output = outputPath(data);
        if (!TextUtils.isEmpty(output) && abs.equals(new File(output).getAbsolutePath())) return true;
        String name = file.getName();
        // 与脚本一致：跳过生成产物与设置文件本身，避免自引用 / 把设置文件当源
        if (isProductName(name, data)) return true;
        if (name.endsWith("_settings.json")) return true;
        // drpy 引擎文件是运行时补进 js 文件夹的配套文件，不是源（否则引擎自己会被生成一条源条目）
        String lower = name.toLowerCase(Locale.US);
        return lower.equals("drpy2.min.js") || lower.equals("drpy2-fast.min.js") || lower.equals("drpy-core-lite.min.js");
    }

    /**
     * 把配置里 {@code ./}、{@code ../} 开头的 api / ext / jar / homePage / lives.url 展开成宿主机可读地址。
     * 基准目录优先取配置地址所在目录（本地配置），否则取生成目录（注入场景）。
     */
    public static void resolveRelative(JsonObject object, String configUrl) {
        if (object == null) return;
        String base = baseDirOf(configUrl);
        if (TextUtils.isEmpty(base)) base = outputBaseDir(settings());
        if (TextUtils.isEmpty(base)) return;
        int count = 0;
        count += resolveAll(object.get("sites"), base);
        count += resolveAll(object.get("lives"), base);
        if (count > 0) DebugLogStore.add(TAG, "相对路径展开 " + count + " 处（基准 " + base + "）");
    }

    private static int resolveAll(JsonElement array, String base) {
        if (array == null || !array.isJsonArray()) return 0;
        int count = 0;
        for (JsonElement element : array.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            for (String key : new String[]{"api", "ext", "jar", "homePage", "url"}) {
                count += resolve(item, key, base);
            }
            count += resolveCfgo(item, base);
        }
        return count;
    }

    private static int resolve(JsonObject item, String key, String base) {
        JsonElement element = item.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return 0;
        String value = element.getAsString();
        if (!value.startsWith("./") && !value.startsWith("../")) return 0;
        String abs;
        try {
            abs = new File(base, value).getCanonicalPath();
        } catch (Throwable e) {
            abs = new File(base, value).getAbsolutePath();
        }
        // jar 用纯绝对路径（JarLoader 会先复制进私有目录再加载），其余用 file://（本壳会转成内置 /file/ 地址）
        item.addProperty(key, "jar".equals(key) ? abs : fileUrl(abs));
        return 1;
    }

    /** MissAV 的 ext 是对象 {site, cfgo, proxy}，字符串字段展开碰不到嵌在里面的 cfgo：相对 cfgo 按同一基准展开成纯绝对路径（不带 file://，Spider.resolveCfgoPath 按文件路径取用）。 */
    private static int resolveCfgo(JsonObject item, String base) {
        JsonElement ext = item.get("ext");
        if (ext == null || !ext.isJsonObject()) return 0;
        JsonObject extObject = ext.getAsJsonObject();
        JsonElement cfgo = extObject.get("cfgo");
        if (cfgo == null || !cfgo.isJsonPrimitive() || !cfgo.getAsJsonPrimitive().isString()) return 0;
        String value = cfgo.getAsString();
        if (!value.startsWith("./") && !value.startsWith("../")) return 0;
        String abs;
        try {
            abs = new File(base, value).getCanonicalPath();
        } catch (Throwable e) {
            abs = new File(base, value).getAbsolutePath();
        }
        extObject.addProperty("cfgo", abs);
        return 1;
    }

    private static String baseDirOf(String configUrl) {
        try {
            if (TextUtils.isEmpty(configUrl)) return "";
            String url = configUrl.trim();
            if (url.startsWith("file:")) {
                String path = url.substring(url.indexOf("file:") + 5);
                while (path.startsWith("/")) path = path.substring(1);
                path = "/" + android.net.Uri.decode(path);
                return parentOf(path);
            }
            if (url.startsWith("/")) return parentOf(url);
            if (url.startsWith("http://127.0.0.1") && url.contains("/file/")) {
                String path = url.substring(url.indexOf("/file/") + 6);
                path = android.net.Uri.decode(path);
                return parentOf(path.startsWith("/") ? path : "/" + path);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String parentOf(String path) {
        File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent == null ? "" : parent.getAbsolutePath();
    }

    // ==================== 落盘 ====================

    /** r35-④：「保存并加载」时把生成的 json 直接设成点播接口（图1 里「点播」那一栏）用。 */
    public static File outputFile() {
        String path = outputPath(settings());
        return TextUtils.isEmpty(path) ? null : new File(path);
    }

    private static String write(JsonObject config) {
        try {
            String path = outputPath(settings());
            if (TextUtils.isEmpty(path)) {
                DebugLogStore.add(TAG, "未设置扫描目录，无法确定生成位置");
                return null;
            }
            // r26-②：你自己编写的本地源禁止自动刷新回填 —— 输出路径落在「你自己编写的配置」上时一律不写
            // （否则会把你的手写 json 覆盖成本壳产物），只记日志 + 留给界面提示。
            if (isUserOwnedConfig(path)) {
                blockedWriteTarget = path;
                DebugLogStore.add(TAG, "自己编写的本地源：跳过自动刷新回填 " + path + "（这是你自己的配置，App 不改写它）");
                return null;
            }
            String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(config);
            if (text.length() > MAX_JSON_CHARS) {
                DebugLogStore.add(TAG, "生成内容过大（" + text.length() + " 字符），已放弃落盘");
                return null;
            }
            File target = new File(path);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建目录 " + parent);
            try (OutputStream out = new FileOutputStream(target, false)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            DebugLogStore.add(TAG, "已生成：" + target.getAbsolutePath() + "（" + text.length() + " 字符）");
            return target.getAbsolutePath();
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "生成失败：" + e + android.util.Log.getStackTraceString(e));
            return null;
        }
    }

    /**
     * 与脚本 decode_base64_file 一致：文件不是合法 json 时尝试 Base64 解码，
     * 解码结果是合法 json 才覆盖原文件（只在这种确定情况下写盘）。
     */
    private static void decodeBase64File(File file) {
        try {
            StringBuilder builder = new StringBuilder();
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = in.read(buffer)) > 0) builder.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
            }
            String content = builder.toString().trim();
            try {
                JsonParser.parseString(content);
                return;
            } catch (Throwable ignored) {
            }
            byte[] decoded = Base64.decode(content, Base64.DEFAULT);
            String text = new String(decoded, StandardCharsets.UTF_8);
            JsonParser.parseString(text);
            try (OutputStream out = new FileOutputStream(file, false)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            DebugLogStore.add(TAG, "Base64 规则已解码：" + file.getName());
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "Base64 解码跳过：" + file.getName() + "（" + e + "）");
        }
    }

    // ==================== 小工具 ====================

    /** 与脚本 scan_sort_key 一致：默认按 GBK 码位排序（中文目录近似拼音序）。 */
    private static int compareGbk(String left, String right) {
        try {
            byte[] a = left.toLowerCase(Locale.US).getBytes(Charset.forName("GBK"));
            byte[] b = right.toLowerCase(Locale.US).getBytes(Charset.forName("GBK"));
            int length = Math.min(a.length, b.length);
            for (int i = 0; i < length; i++) {
                int diff = (a[i] & 0xff) - (b[i] & 0xff);
                if (diff != 0) return diff;
            }
            return a.length - b.length;
        } catch (Throwable e) {
            return left.compareToIgnoreCase(right);
        }
    }

    private static int maxDepth(JsonObject data) {
        int depth = intOf(data, "max_depth", DEFAULT_DEPTH);
        return Math.max(0, Math.min(depth, MAX_DEPTH));
    }

    /**
     * 启用的扫描目录。r15：同一物理目录只扫一次
     * （{@code /sdcard/自己整理} 与 {@code /storage/emulated/0/自己整理}、带不带结尾斜杠都算同一个），
     * 否则两处都填会把整棵树重复扫一遍、站点成倍膨胀。
     */
    private static List<String> enabledScanDirs(JsonObject data) {
        List<String> dirs = list(data, "scan_dirs");
        List<String> enabled = list(data, "scan_enabled_dirs");
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String dir : dirs) {
            if (!enabled.contains(dir)) continue;
            String normalized = normalizedPath(dir);
            if (TextUtils.isEmpty(normalized) || !seen.add(normalized)) continue;
            result.add(dir);
        }
        return result;
    }

    /**
     * r23-①：把「内置默认扫描目录」从勾选列表里去掉（条目本身保留，用户想扫再自己勾）。
     *
     * <p>这个列表来自脚本/出厂内置的 {@code DEFAULTS.scan_dirs}，从来不是用户一条条添加的路径；
     * 用户口径是「只扫我添加的路径」，所以第一次升级时把它取消勾选（只取消勾选、不删除，可逆），
     * 且只在「已有别的扫描目录」时才动，绝不清空用户的目录列表。
     *
     * @return 取消勾选的目录个数
     */
    private static int demoteBuiltinScanDirs(JsonObject data) {
        try {
            List<String> builtin = builtinDefaultDirs();
            if (builtin.isEmpty()) return 0;
            List<String> dirs = list(data, "scan_dirs");
            List<String> enabled = list(data, "scan_enabled_dirs");
            if (dirs.size() <= 1 || enabled.size() <= 1) return 0;
            Set<String> builtinKeys = new LinkedHashSet<>();
            for (String dir : builtin) {
                String key = normalizedPath(dir);
                if (!TextUtils.isEmpty(key)) builtinKeys.add(key);
            }
            if (builtinKeys.isEmpty()) return 0;
            List<String> keep = new ArrayList<>();
            int dropped = 0;
            for (String dir : enabled) {
                String key = normalizedPath(dir);
                boolean inner = !TextUtils.isEmpty(key) && builtinKeys.contains(key) && enabled.size() - dropped > 1;
                if (inner) dropped++;
                else keep.add(dir);
            }
            if (dropped == 0) return 0;
            data.add("scan_enabled_dirs", stringArray(filterActive(dirs, keep)));
            return dropped;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "扫描路径收口失败：" + e);
            return 0;
        }
    }

    /** r23-①：脚本/出厂内置的默认扫描目录（asset 的 {@code DEFAULTS.scan_dirs}）。 */
    private static List<String> builtinDefaultDirs() {
        List<String> result = new ArrayList<>();
        try {
            JsonObject object = asset();
            if (!object.has("DEFAULTS") || !object.get("DEFAULTS").isJsonObject()) return result;
            JsonElement dirs = object.getAsJsonObject("DEFAULTS").get("scan_dirs");
            if (dirs == null || !dirs.isJsonArray()) return result;
            for (JsonElement item : dirs.getAsJsonArray()) if (item.isJsonPrimitive()) result.add(item.getAsString());
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** 归一化路径：解析符号链接与 .. 、结尾斜杠，并把同一目录的不同写法归一到同一个 key。 */
    private static String normalizedPath(String path) {
        if (TextUtils.isEmpty(path)) return "";
        File file = new File(path.trim());
        try {
            return canonicalKey(file.getCanonicalPath());
        } catch (Throwable e) {
            return canonicalKey(file.getAbsolutePath());
        }
    }

    /**
     * 外部存储根的常见写法归一：/sdcard、/storage/emulated/0、/storage/self/primary、
     * /mnt/user/0/primary 指的是同一个目录，getCanonicalPath 在部分机型上不一定能把它们合并，
     * 这里统一折算成同一个 key，避免「同一目录填两遍 → 整棵树扫两遍 → 站点成倍膨胀」。
     */
    private static String canonicalKey(String path) {
        String value = path.replace('\\', '/');
        for (String prefix : EXTERNAL_PREFIXES) {
            if (value.startsWith(prefix)) return HINT_PREFIX + "external/" + value.substring(prefix.length());
        }
        if (EXTERNAL_ROOT.equals(value)) return HINT_PREFIX + "external/";
        return value;
    }

    public static List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return result;
        for (String item : text.replace('，', ',').replace('；', ',').replace('、', ',').split("[,\\n\\r|]")) {
            String value = item.trim().replace("\"", "").replace("'", "");
            if (!TextUtils.isEmpty(value) && !result.contains(value)) result.add(value);
        }
        return result;
    }

    public static JsonArray stringArray(List<String> items) {
        JsonArray array = new JsonArray();
        for (String item : items) array.add(item);
        return array;
    }

    public static JsonArray stringArray(String[] items) {
        JsonArray array = new JsonArray();
        for (String item : items) array.add(item);
        return array;
    }

    private static void addAll(List<JsonObject> target, JsonElement array) {
        if (array == null || !array.isJsonArray()) return;
        for (JsonElement item : array.getAsJsonArray()) if (item.isJsonObject()) target.add(item.getAsJsonObject().deepCopy());
    }

    private static void addAll(JsonArray target, JsonElement array) {
        if (array == null || !array.isJsonArray()) return;
        for (JsonElement item : array.getAsJsonArray()) target.add(item.deepCopy());
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean def) {
        try {
            JsonElement element = object.get(key);
            return element == null || !element.isJsonPrimitive() ? def : element.getAsBoolean();
        } catch (Throwable e) {
            return def;
        }
    }

    private static int intOf(JsonObject object, String key, int def) {
        try {
            JsonElement element = object.get(key);
            return element == null || !element.isJsonPrimitive() ? def : element.getAsInt();
        } catch (Throwable e) {
            return def;
        }
    }

    private static List<String> list(JsonObject data, String key) {
        List<String> result = new ArrayList<>();
        JsonElement element = data.get(key);
        if (element == null || !element.isJsonArray()) return result;
        for (JsonElement item : element.getAsJsonArray()) {
            try {
                result.add(item.isJsonPrimitive() ? item.getAsString() : item.toString());
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /** 1.0.3-① 后台预热：配置加载完成后另起线程算一遍源身份填满缓存，源搜索弹窗打开时不再现算。调用方：VodConfig.parseConfig 末尾。 */
    public static void preheatAsync(List<Site> sites) {
        if (sites == null || sites.isEmpty()) return;
        List<Site> copy = new ArrayList<>(sites);
        new Thread(() -> {
            try {
                sourcePathsOf(copy);
            } catch (Throwable ignored) {
            }
        }, "local-source-preheat").start();
    }

    /** 1.0.3-⑤ 转存目标是否在扫描范围内（FileTransferActivity.I0 的移植）：候选 = 已启用扫描目录 + 产物所在目录；r104 起不在范围内只提示、不自动加入扫描目录。 */
    public static boolean isInScanRange(File target) {
        String absolutePath = target == null ? null : target.getAbsolutePath();
        boolean inRange = false;
        if (!TextUtils.isEmpty(absolutePath)) {
            try {
                List<String> candidates = new ArrayList<>();
                for (String dir : getScanDirs()) if (isDirEnabled(dir)) candidates.add(dir);
                File out = outputFile();
                if (out != null && out.getParentFile() != null) candidates.add(out.getParentFile().getAbsolutePath());
                String normalized = new File(absolutePath).getAbsolutePath();
                for (String candidate : candidates) {
                    if (TextUtils.isEmpty(candidate)) continue;
                    String base = new File(candidate).getAbsolutePath();
                    if (base.endsWith("/") && base.length() > 1) base = base.substring(0, base.length() - 1);
                    if (normalized.equals(base) || normalized.startsWith(base + "/")) {
                        inRange = true;
                        break;
                    }
                }
            } catch (Throwable e) {
                DebugLogStore.add(TAG, "判断目录是否在扫描范围内失败：" + e);
            }
        }
        DebugLogStore.add(TAG, "转存：目标目录 " + absolutePath + (inRange ? " 在扫描范围内，产物照常重建" : " 不在扫描范围内（r104 起不再自动加入扫描目录，仅提示用户）"));
        return inRange;
    }

    /** 1.0.3-⑥ 主题色解析（FileTransferActivity.N0 的移植）：解主题属性，资源色或 28–31 型直接值，失败回 fallback 并记日志。 */
    public static int resolveThemeColor(Context context, int attr, int fallback) {
        try {
            TypedValue typedValue = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, typedValue, true)) {
                if (typedValue.resourceId != 0) return context.getColor(typedValue.resourceId);
                if (typedValue.type >= 28 && typedValue.type <= 31) return typedValue.data;
            }
            return fallback;
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "解析主题色失败 " + attr + "：" + e);
            return fallback;
        }
    }

    /** 删除二次确认用：取源文件绝对路径（找不到返回空串）；Hub 内部 siteFile() 的只读包装，不做任何改动。 */
    public static String siteFilePath(String api, String ext) {
        try {
            File file = siteFile(api, ext, null);
            return file == null ? "" : file.getAbsolutePath();
        } catch (Throwable e) {
            return "";
        }
    }
}
