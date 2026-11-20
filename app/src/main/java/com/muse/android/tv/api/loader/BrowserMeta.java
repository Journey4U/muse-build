package com.muse.android.tv.api.loader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 升级版137：浏览器页嗅流转播放的显示信息提取（纯逻辑，可单测）。
 *
 * <p>推送播放原本写死「名字=网址、线路=推送、集数=01」。转播放时其实
 * 手边就有现成信息：播放页的网页标题（这类站多为「片名 第X集 在线
 * 播放-站名」格式）、播放页网址（苹果CMS 的 vodplay 网址里带着
 * 线路号与集号）。本类负责把它们清洗成可显示的片名/站名/集名，
 * 拿不准就返回空串，调用方回落原有显示，绝不编造。
 */
public class BrowserMeta {

    private static final Pattern EPISODE_IN_TITLE = Pattern.compile("第\\s*(\\d+)\\s*[集话回]");
    private static final Pattern EPISODE_IN_URL = Pattern.compile("-(\\d+)-(\\d+)\\.html");
    private static final String BOILERPLATE = "(高清完整版|完整版|高清版|免费在线观看|在线观看|在线播放|高清在线|免费观看|全集观看|免费在线|在线免费)";
    private static final String[] SEPARATORS = {"-", "_", "｜", "|", "—", "–"};

    private BrowserMeta() {
    }

    private static final String[] TRAILING_TOKENS = {"电视剧", "连续剧", "电影", "动漫", "综艺", "全集", "免费", "高清", "完整版"};

    /** 片名：去掉集数表述与观看套话、截掉站名后缀、去书名号、剥尾巴词。 */
    public static String cleanTitle(String rawTitle) {
        if (rawTitle == null) return "";
        String t = rawTitle.trim().replace("《", "").replace("》", "");
        t = EPISODE_IN_TITLE.matcher(t).replaceAll(" ");
        t = t.replaceAll(BOILERPLATE, " ");
        t = cutSuffix(t);
        t = t.replaceAll("\\s+", " ").trim();
        // 尾巴词（「骄阳似我电视剧免费」「走马上任电视剧 全集」）：反复剥，
        // 剥完不足 2 字就停（防把正名剥没）。
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String token : TRAILING_TOKENS) {
                if (t.endsWith(token) && t.length() - token.length() >= 2) {
                    t = t.substring(0, t.length() - token.length()).trim();
                    changed = true;
                }
            }
        }
        return t;
    }

    /** 是否直达流地址：视频后缀必须落在路径末尾（查询串里带 .m3u8 字样的
     *  解析中转地址不算，升级版138 实锤 getM3u8?url=...m3u8 骗过嗅探）。 */
    public static boolean isDirectStream(String url) {
        if (url == null) return false;
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        path = path.toLowerCase();
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4")
                || path.endsWith(".flv") || path.endsWith(".mkv") || path.endsWith(".avi")
                || path.endsWith(".mov") || path.endsWith(".rmvb") || path.endsWith(".mp3")
                || path.endsWith(".m4a") || path.endsWith(".aac") || path.endsWith(".flac");
    }

    /** 网页颜色字符串（#hex / rgb() / rgba()）→ Android 颜色值；解不出返回 0。 */
    public static int parseColor(String css) {
        if (css == null) return 0;
        String s = css.trim();
        try {
            if (s.startsWith("#") && (s.length() == 7 || s.length() == 4)) {
                if (s.length() == 4) {
                    int r = Integer.parseInt(s.substring(1, 2), 16) * 17;
                    int g = Integer.parseInt(s.substring(2, 3), 16) * 17;
                    int b = Integer.parseInt(s.substring(3, 4), 16) * 17;
                    return 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
            }
            if (s.startsWith("rgb")) {
                String inner = s.substring(s.indexOf('(') + 1, s.indexOf(')'));
                String[] parts = inner.split(",");
                if (parts.length >= 3) {
                    int r = clamp(Float.parseFloat(parts[0].trim()));
                    int g = clamp(Float.parseFloat(parts[1].trim()));
                    int b = clamp(Float.parseFloat(parts[2].trim()));
                    return 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 颜色是否偏亮（决定标题栏文字用深色还是白色）。 */
    public static boolean isLight(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (299 * r + 587 * g + 114 * b) > 160000;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /** 集名：只从标题找「第X集」（单片模式用；网址里的 -n-n.html 序号不是真集名）。 */
    public static String episodeNameFromTitle(String rawTitle) {
        if (rawTitle != null) {
            Matcher m = EPISODE_IN_TITLE.matcher(rawTitle);
            if (m.find()) return "第" + parse(m.group(1)) + "集";
        }
        return "";
    }

    /** 集名：先从标题找「第X集」，再从播放页网址找 -线路号-集号.html。 */
    public static String episodeName(String pageUrl, String rawTitle) {
        if (rawTitle != null) {
            Matcher m = EPISODE_IN_TITLE.matcher(rawTitle);
            if (m.find()) return "第" + parse(m.group(1)) + "集";
        }
        if (pageUrl != null) {
            Matcher m = EPISODE_IN_URL.matcher(pageUrl);
            if (m.find()) return "第" + parse(m.group(2)) + "集";
        }
        return "";
    }

    /** 站名（作线路名显示）：标题各段里挑带影院/影视等字样的那段，否则取较短的尾段，再否则用回落值。 */
    public static String siteName(String rawTitle, String fallback) {
        if (rawTitle != null) {
            String best = "";
            for (String segment : splitSegments(rawTitle)) {
                String s = segment.replaceAll(BOILERPLATE, "").trim();
                if (s.length() < 2) continue;
                if (s.contains("影院") || s.contains("影视") || s.contains("影城") || s.contains("剧场") || s.contains("视频")) return s;
                if (s.length() <= 8) best = s;
            }
            if (!best.isEmpty()) return best;
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String cutSuffix(String t) {
        for (String sep : SEPARATORS) {
            int from = 0;
            while (true) {
                int i = t.indexOf(sep, from);
                if (i < 2 || i >= t.length() - 1) break;
                // push v15：'-' 后跟数字是编号的一部分（FWAY-104、SNOS-334），不切，继续找下一个分隔符。
                if ("-".equals(sep) && Character.isDigit(t.charAt(i + 1))) {
                    from = i + 1;
                    continue;
                }
                return t.substring(0, i).trim();
            }
        }
        return t;
    }

    private static String[] splitSegments(String t) {
        return t.split("[-_｜|—–]");
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }
}
