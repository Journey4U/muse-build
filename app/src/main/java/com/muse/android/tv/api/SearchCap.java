package com.muse.android.tv.api;

import androidx.annotation.NonNull;

import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.bean.Vod;
import com.muse.android.tv.setting.Setting;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * push v47（Liu 2026-11-24 14:39 定：设置里选「每站搜索条数」20/40/80/不限，默认 40；壳子层限制，各类型源都管）。
 * 所有源（网页源/蜘蛛/接口）的搜索结果都从 SiteApi.searchContent 出来，这里统一截断：
 * 单次返回超过上限只留前 N 条；按「站 key + 关键词 + 快搜」累计，累计到上限后该站后续页不再联网、直接收口。
 * 第 1 页 = 该站新一轮搜索，计数清零；换关键词全部清零。「不限」= 原样返回，与 v46 完全一致。
 * 源自己一次取回、解析好的内容壳子管不到；这里管的是之后不存、不显示、不加载封面、不再翻页取数。
 */
public final class SearchCap {

    private static final Map<String, Integer> COUNT = new HashMap<>();
    private static String lastKeyword = "";

    private SearchCap() {
    }

    private static String key(Site site, String keyword, boolean quick) {
        return site.getKey() + "\u0001" + keyword + "\u0001" + quick;
    }

    static int pageOf(String page) {
        try {
            return Integer.parseInt(page.trim());
        } catch (Exception e) {
            return 1;
        }
    }

    /** 第 2 页起：该站累计已达上限 → true（调用方直接返回 closed()，不联网）。 */
    public static synchronized boolean exhausted(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) {
        int cap = Setting.getSearchCap();
        if (cap <= 0 || pageOf(page) <= 1 || !keyword.equals(lastKeyword)) return false;
        Integer n = COUNT.get(key(site, keyword, quick));
        return n != null && n >= cap;
    }

    /** 收口用的空结果：pagecount=1，翻页滚动器据此停用加载更多。 */
    public static Result closed() {
        Result result = Result.empty();
        result.setPageCount(1);
        return result;
    }

    public static synchronized Result apply(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page, Result result) {
        int cap = Setting.getSearchCap();
        if (cap <= 0 || result == null) return result;
        int pg = pageOf(page);
        if (!keyword.equals(lastKeyword)) {
            COUNT.clear();
            lastKeyword = keyword;
        }
        String k = key(site, keyword, quick);
        Integer before = COUNT.get(k);
        int prev = pg <= 1 || before == null ? 0 : before;
        List<Vod> list = result.getList();
        int size = list.size();
        int kept = Math.min(size, Math.max(0, cap - prev));
        if (kept < size) {
            result.setList(new ArrayList<>(list.subList(0, kept)));
            DebugLogStore.add(LogCategory.MEDIA, "search", "每站搜索条数上限 " + cap + "：" + site.getName() + " 第 " + pg + " 页 原 " + size + " 条 → 留 " + kept + " 条", false);
        }
        COUNT.put(k, prev + kept);
        if (prev + kept >= cap) result.setPageCount(kept > 0 ? pg : 1);
        return result;
    }
}
