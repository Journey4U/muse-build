package com.muse.android.tv.api;

import androidx.annotation.NonNull;

import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.setting.Setting;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;

/**
 * push v47 起：每站搜索上限，壳子层限制，各类型源（网页源/蜘蛛/接口）都从 SiteApi.searchContent 出来，在这里统一收口。
 * push v50（Liu 2026-11-25 12:17 定）：改为**按页计**——设置选 1/2/3/4/5 页/不限，默认 2 页。
 * 一页之内不截断；第 N 页（N=上限）返回后把 pagecount 设成当前页，翻页就停；
 * 第 N+1 页起直接收口（exhausted → closed()），不联网。
 * 页号由搜索界面按「站 + 关键词」各自从第 1 页数起，换关键词自然重新计（原样关键词计；长句补搜不另放一轮，
 * 补搜词沿用原词的页号）。「不限」= 原样返回。
 */
public final class SearchCap {

    private SearchCap() {
    }

    static int pageOf(String page) {
        try {
            return Integer.parseInt(page.trim());
        } catch (Exception e) {
            return 1;
        }
    }

    private static void log(Site site, int cap, int pg) {
        DebugLogStore.add(LogCategory.MEDIA, "search", "每站搜索页数上限 " + cap + " 页：" + site.getName() + " 第 " + pg + " 页 → 收口", false);
    }

    /** 请求页已超过上限 → true（调用方直接返回 closed()，不联网）。 */
    public static boolean exhausted(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) {
        int cap = Setting.getSearchPages();
        int pg = pageOf(page);
        if (cap <= 0 || pg <= cap) return false;
        log(site, cap, pg);
        return true;
    }

    /** 收口用的空结果：pagecount=1，翻页滚动器据此停用加载更多。 */
    public static Result closed() {
        Result result = Result.empty();
        result.setPageCount(1);
        return result;
    }

    /** 不截断；到上限那一页把 pagecount 设成当前页，翻页停。 */
    public static Result apply(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page, Result result) {
        int cap = Setting.getSearchPages();
        if (cap <= 0 || result == null) return result;
        int pg = pageOf(page);
        if (pg < cap) return result;
        Integer pc = result.getPageCount();
        boolean more = pc == null || pc == 0 || pc > pg;
        result.setPageCount(pg);
        if (more && result.getList() != null && !result.getList().isEmpty()) log(site, cap, pg);
        return result;
    }
}
