package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;
import com.github.catvod.crawler.diagnostics.DiagnosticText;
import com.github.catvod.crawler.diagnostics.LogCategory;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    /** 蜘蛛详情页实际用的域名（py 源运行时解析的镜像站，如 P 站的 cn 镜像），供网页播放锚点用；与日志开关无关。 */
    private static final ConcurrentHashMap<String, String> sDetailOrigin = new ConcurrentHashMap<>();
    /** v91：本站记的域名也要有失效时间（Liu 2026-11-14 定）——域名会坏（轮换/镜像挂掉），坏了还一直占着，主页就被带去坏域名白屏；与全局 lastSpiderOrigin 同为 10 分钟。 */
    private static final ConcurrentHashMap<String, Long> sDetailOriginAt = new ConcurrentHashMap<>();
    private static final long DETAIL_ORIGIN_TTL_MS = 10 * 60 * 1000L;

    public static void recordDetailOrigin(String key, String origin) {
        if (key == null || key.isEmpty() || origin == null || origin.isEmpty()) return;
        sDetailOrigin.put(key, origin);
        sDetailOriginAt.put(key, System.currentTimeMillis());
    }

    public static String detailOrigin(String key) {
        if (key == null || key.isEmpty()) return null;
        Long at = sDetailOriginAt.get(key);
        if (at == null) return null;
        if (System.currentTimeMillis() - at > DETAIL_ORIGIN_TTL_MS) {
            sDetailOrigin.remove(key);
            sDetailOriginAt.remove(key);
            return null;
        }
        return sDetailOrigin.get(key);
    }

    /** 蜘蛛最近一次请求的来源（scheme://host[:port]），供重定向模板站点取 jar 解析后的真实域名；与日志开关无关。只记最近一次，10 分钟内有效。 */
    private static volatile String sLastSpiderOrigin;
    private static volatile long sLastSpiderOriginAt;

    public static void recordSpiderOrigin(String origin) {
        if (origin == null || origin.isEmpty()) return;
        sLastSpiderOrigin = origin;
        sLastSpiderOriginAt = System.currentTimeMillis();
    }

    public static String lastSpiderOrigin() {
        if (sLastSpiderOrigin == null || sLastSpiderOrigin.isEmpty()) return null;
        if (System.currentTimeMillis() - sLastSpiderOriginAt > 10 * 60 * 1000L) return null;
        return sLastSpiderOrigin;
    }

    /** 配置重载时清掉蜘蛛记的旧域名：否则改了配置里的「主页url」后，webview 仍先认旧域名的 lastSpiderOrigin，非得重启才生效。 */
    public static void clearSpiderOrigin() {
        sLastSpiderOrigin = null;
        sLastSpiderOriginAt = 0;
        sDetailOrigin.clear();
        sDetailOriginAt.clear();
    }

    /** v105：切源清零改细——只清别的站的记录，保留要进的站自己的。
     * v104 全清会把要进站自己的好域名也清掉（P站靠蜘蛛记的 cn 镜像进站才没遮罩，清掉只能走慢入口带 8 秒遮罩）；
     * 只清别的站，既防串站（上一个站的记录带不进新站），又不拆要进站自己的路。 */
    public static void clearSpiderOriginExcept(String keepKey) {
        sLastSpiderOrigin = null;
        sLastSpiderOriginAt = 0;
        if (keepKey == null || keepKey.isEmpty()) {
            sDetailOrigin.clear();
            sDetailOriginAt.clear();
            return;
        }
        sDetailOrigin.keySet().removeIf(k -> !keepKey.equals(k));
        sDetailOriginAt.keySet().removeIf(k -> !keepKey.equals(k));
    }

    public static boolean isEnabled() {
        return DebugLogStore.isEnabled();
    }

    /** Capture only: the runtime remains the sole owner of its Logcat/stdout output. */
    public static void console(String tag, String level, String message) {
        if (!DebugLogStore.acceptsTag(tag) || message == null || message.isEmpty()) return;
        String severity = switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "DEBUG", "LOG" -> "DEBUG";
            case "WARN", "WARNING" -> "WARN";
            case "ERROR", "FATAL" -> "ERROR";
            case "STDERR" -> "STDERR"; // A stream is not a severity (logging.info can use stderr).
            default -> "INFO";
        };
        try {
            // The console channel is the spiders' own Java/Python/JS output.
            DebugLogStore.add(LogCategory.SPIDER, tag, "[" + severity + "] " + message, severity.equals("ERROR"));
        } catch (RuntimeException ignored) {
            DebugLogStore.collectorFailure();
        }
    }

    public static void log(Throwable th) {
        log(TAG, th);
    }

    public static void log(String tag, Throwable th) {
        if (th == null) return;
        if (!DebugLogStore.acceptsTag(tag)) return;
        String message = DiagnosticText.throwable(th);
        DebugLogStore.add(tag, message, true);
        Logger.t(safeTag(tag)).e(message);
    }

    public static void log(String msg) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.acceptsTag(TAG)) return;
        DebugLogStore.add(TAG, msg);
        Logger.t(TAG).d(DiagnosticText.clean(msg).text());
    }

    public static void log(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.acceptsTag(tag)) return;
        String message = format(msg, args);
        DebugLogStore.add(tag, message);
        Logger.t(safeTag(tag)).d(DiagnosticText.clean(message).text());
    }

    /** Explicit-category variant for emitters whose tag belongs to another bucket by default. */
    public static void log(LogCategory category, String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.acceptsTag(tag)) return;
        String message = format(msg, args);
        DebugLogStore.add(category, tag, message, false);
        Logger.t(safeTag(tag)).d(DiagnosticText.clean(message).text());
    }

    /** Error variant: the entry keeps its tag's category and is also listed under 报错. */
    public static void error(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.acceptsTag(tag)) return;
        String message = format(msg, args);
        DebugLogStore.addError(LogCategory.forTag(tag), tag, message);
        Logger.t(safeTag(tag)).e(DiagnosticText.clean(message).text());
    }

    private static String safeTag(String tag) {
        return DiagnosticText.clean(TextUtils.isEmpty(tag) ? TAG : tag).text();
    }

    private static String format(String msg, Object... args) {
        try {
            return args == null || args.length == 0 ? msg : String.format(Locale.US, msg, args);
        } catch (Throwable e) {
            return msg;
        }
    }
}

