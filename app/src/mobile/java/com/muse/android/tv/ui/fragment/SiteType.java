package com.muse.android.tv.ui.fragment;

import com.muse.android.tv.bean.Site;
import com.muse.android.tv.ui.activity.VideoActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * 站名标记口径（Liu 2026-11-11 定）：名[文件夹](源类型)——[] 里是文件夹分类，
 * () 里只有 JSON / JS / PY / CAT / XYQ 五个规范名是源类型，其余 () 值是备注
 * （如 (ws) = 无搜），不算类型，与筛选无关。
 * 源类型供长按搜索的「按源类型」用；文件夹分类供源列表与长按搜索的
 * 「按分类」用，大小写视为同一个（pq 与 PQ 合并）。类型匹配大小写不敏感，
 * 老名字里的 (json) / (py) 也归到规范名下。
 */
public final class SiteType {

    /** 规范类型，顺序即按钮出现顺序。 */
    public static final List<String> CANONICAL = Arrays.asList("JSON", "JS", "PY", "CAT", "XYQ");

    /** 源列表 / 长按搜索「按分类」里的纯网页源标签（v42，Liu 2026-11-24：只做手机版）。 */
    public static final String WEB_SOURCE_LABEL = "网页源";
    public static final String WEB_SOURCE_MARK = "[" + WEB_SOURCE_LABEL + "]";

    private SiteType() {
    }

    /** () 里的原始值 → 规范类型名；不是五类之一（备注）返回 null。 */
    public static String canonicalOf(String rawValue) {
        if (rawValue == null) return null;
        String value = rawValue.trim().toUpperCase(Locale.ROOT);
        return CANONICAL.contains(value) ? value : null;
    }

    /** 本源的规范类型（站名 () 提取，五类之外的值忽略）。 */
    public static List<String> of(Site site) {
        Set<String> result = new LinkedHashSet<>();
        if (site != null) {
            for (String raw : site.getTypes()) {
                String inner = raw.length() > 2 ? raw.substring(1, raw.length() - 1) : "";
                String canonical = canonicalOf(inner);
                if (canonical != null) result.add(canonical);
            }
        }
        return new ArrayList<>(result);
    }

    /** 全部可见源里实际出现的规范类型，按 CANONICAL 的固定顺序。 */
    public static List<String> presentIn(List<Site> sites) {
        Set<String> found = new LinkedHashSet<>();
        if (sites != null) {
            for (Site site : sites) {
                if (site == null || site.isHide()) continue;
                found.addAll(of(site));
            }
        }
        List<String> result = new ArrayList<>();
        for (String canonical : CANONICAL) if (found.contains(canonical)) result.add(canonical);
        return result;
    }

    /** 本源是否属于指定规范类型；类型为空 = 不限。 */
    public static boolean contains(Site site, String canonical) {
        return canonical == null || canonical.isEmpty() || of(site).contains(canonical);
    }

    /** 本源的文件夹分类（站名 [] 里的原始值，不带括号）。 */
    public static List<String> categoriesOf(Site site) {
        Set<String> result = new LinkedHashSet<>();
        if (site != null) {
            for (String raw : site.getGroups()) {
                String inner = raw.length() > 2 ? raw.substring(1, raw.length() - 1).trim() : "";
                if (!inner.isEmpty()) result.add(inner);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 全部可见源里实际出现的文件夹分类，返回 "[名]" 形式（与站名一致）。
     * 大小写视为同一个：pq 与 PQ 只出一个，保留先出现的写法。
     */
    public static List<String> presentCategories(List<Site> sites) {
        Map<String, String> byLower = new LinkedHashMap<>();
        // v42（Liu 2026-11-24）：有纯网页源就把 [网页源] 放第一位（紧跟「全部」）；
        // 同名文件夹（大小写不敏感）并进这一个，不重复出按钮。
        if (hasPureWebSource(sites)) byLower.put(WEB_SOURCE_LABEL.toLowerCase(Locale.ROOT), WEB_SOURCE_MARK);
        if (sites != null) {
            for (Site site : sites) {
                if (site == null || site.isHide()) continue;
                for (String category : categoriesOf(site)) byLower.putIfAbsent(category.toLowerCase(Locale.ROOT), "[" + category + "]");
            }
        }
        return new ArrayList<>(byLower.values());
    }

    /** 可见源里是否有纯网页源（判定沿用进站主页那套 VideoActivity.isPureWebSource）。 */
    private static boolean hasPureWebSource(List<Site> sites) {
        if (sites == null) return false;
        for (Site site : sites) {
            if (site == null || site.isHide()) continue;
            if (VideoActivity.isPureWebSource(site)) return true;
        }
        return false;
    }

    /** 文件夹分类是否命中："[PQ]" 与站名里的 [pq] 也算命中（大小写不敏感）；空 = 不限。 */
    public static boolean matchesCategory(Site site, String groupMark) {
        if (groupMark == null || groupMark.isEmpty()) return true;
        String value = groupMark.trim();
        if (value.length() > 2 && value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        // v42：[网页源] = 纯网页源，或文件夹名本就叫「网页源」的源
        if (WEB_SOURCE_LABEL.equalsIgnoreCase(value.trim()) && VideoActivity.isPureWebSource(site)) return true;
        for (String category : categoriesOf(site)) if (category.equalsIgnoreCase(value)) return true;
        return false;
    }

    /** 长按搜索传来的 "(PY)" 形式标记是否命中本源的规范类型。 */
    public static boolean matches(Site site, String typeMark) {
        if (typeMark == null || typeMark.isEmpty()) return true;
        String value = typeMark;
        if (value.startsWith("(") && value.endsWith(")")) value = value.substring(1, value.length() - 1);
        String canonical = canonicalOf(value);
        return canonical != null && of(site).contains(canonical);
    }
}
