package com.muse.android.tv.api;

import androidx.annotation.NonNull;

import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * push v50（Liu 2026-11-25 11:58 定「按改过的顺序开工，补搜覆盖全部源类型」）：长段文字搜索。
 * 顺序：① 原样整段去搜（很多站的搜索本身支持多词/简介/演员）；② 该站 0 条 → 从原文提取番号（SSIS-123）或《》里的片名再搜一次；
 * ③ 仍 0 条 → 按半角/全角空格拆开，取最长一段（≥2 字、没试过）再搜一次。
 * 只在壳子侧换词，不改任何源；蜘蛛/接口/网页源一视同仁。只补非快搜的第 1 页；每站每个原始关键词各步最多一次；
 * 第 2 页起沿用第 1 页实际出结果的那个词；出错（异常）不补搜，照原样抛出。界面关键词与 SearchCap 计数都按原文。
 */
public final class SearchRetry {

    private static final Pattern CODE = Pattern.compile("(?<![A-Za-z])([A-Za-z]{2,6})[-_]?(\\d{2,5})(?!\\d)");
    private static final Pattern TITLE = Pattern.compile("《([^》]{1,80})》");
    private static final java.util.Set<String> NOT_CODE = new java.util.HashSet<>(java.util.Arrays.asList(
            "HD", "FHD", "UHD", "SD", "BD", "DVD", "WEB", "MP", "AAC", "DTS", "AVC", "HEVC", "FPS", "EP", "TV", "VOL", "NO", "PART", "CD", "PT", "SP"));
    private static final Pattern SPACE = Pattern.compile("[\\s\\u3000]+");

    private static final Map<String, String> USED = new HashMap<>();
    private static String lastKeyword = "";

    private SearchRetry() {
    }

    private static String key(Site site, String keyword) {
        return site.getKey() + "\u0001" + keyword;
    }

    private static synchronized void remember(Site site, String keyword, String word) {
        if (!keyword.equals(lastKeyword)) {
            USED.clear();
            lastKeyword = keyword;
        }
        if (word == null) USED.remove(key(site, keyword));
        else USED.put(key(site, keyword), word);
    }

    private static synchronized String used(Site site, String keyword) {
        if (!keyword.equals(lastKeyword)) return null;
        return USED.get(key(site, keyword));
    }

    private static boolean empty(Result r) {
        return r == null || r.getList() == null || r.getList().isEmpty();
    }

    /** 番号（字母 2–6 位 + 可选 -/_ + 数字 2–5 位，取第一个，规范成 大写-数字）优先，否则《》里的片名；都没有返回空串。 */
    static String extract(String keyword) {
        if (keyword == null) return "";
        Matcher m = CODE.matcher(keyword);
        while (m.find()) {
            String letters = m.group(1).toUpperCase(java.util.Locale.ROOT);
            if (NOT_CODE.contains(letters)) continue;   // HD1080、EP01、MP4 之类画质/集数写法不当番号
            return letters + "-" + m.group(2);
        }
        Matcher t = TITLE.matcher(keyword);
        while (t.find()) {
            String s = t.group(1).trim();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** 按半角/全角空格拆开取最长一段（同长取靠前；跳过 skip 里已试过的词），不足 2 字返回空串。 */
    static String longestPart(String keyword, List<String> skip) {
        if (keyword == null) return "";
        String best = "";
        for (String p : SPACE.split(keyword.trim())) {
            if (p.length() < 2 || (skip != null && skip.contains(p))) continue;
            if (p.length() > best.length()) best = p;
        }
        return best;
    }

    /** 补搜候选（按顺序：① 番号/《》片名；② 空格拆出的最长段，与原文及①不同）。 */
    static List<String> candidates(String keyword) {
        List<String> out = new ArrayList<>();
        String k = keyword == null ? "" : keyword.trim();
        String a = extract(k);
        if (!a.isEmpty() && !a.equals(k)) out.add(a);
        List<String> skip = new ArrayList<>(out);
        skip.add(k);
        if (!a.isEmpty()) skip.add(a);
        String b = longestPart(k, skip);
        if (!b.isEmpty() && !out.contains(b)) out.add(b);
        return out;
    }

    @NonNull
    static Result run(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) throws Exception {
        if (quick) return SiteApi.searchContentRaw(site, keyword, true, page);
        if (SearchCap.pageOf(page) > 1) {
            String w = used(site, keyword);
            return SiteApi.searchContentRaw(site, w == null ? keyword : w, false, page);
        }
        remember(site, keyword, null);
        Result first = SiteApi.searchContentRaw(site, keyword, false, page);
        if (!empty(first)) return first;
        for (String w : candidates(keyword)) {
            Result r = SiteApi.searchContentRaw(site, w, false, page);
            int n = empty(r) ? 0 : r.getList().size();
            String shown = keyword.length() > 40 ? keyword.substring(0, 40) + "…" : keyword;
            DebugLogStore.add(LogCategory.MEDIA, "search", "长句补搜：" + site.getName() + " 原词「" + shown + "」0 条 → 补搜「" + w + "」" + n + " 条", false);
            if (n > 0) {
                remember(site, keyword, w);
                return r;
            }
        }
        return first;
    }
}
