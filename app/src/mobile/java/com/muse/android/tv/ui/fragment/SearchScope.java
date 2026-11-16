package com.muse.android.tv.ui.fragment;

/**
 * 搜索范围常量。首页放大镜轻按 = 全部源（原版行为），长按可选更精细的范围。
 */
public final class SearchScope {

    /** 全部可搜索源（默认，等同原版行为）。 */
    public static final String ALL = "all";
    /** 仅当前选中的源。 */
    public static final String CURRENT = "current";
    /** 按源名 [分类] 标记筛选，value 形如 "[剧]"。 */
    public static final String GROUP = "group";
    /** 按源名 (写法) 标记筛选，value 形如 "(PY)"。 */
    public static final String TYPE = "type";

    private SearchScope() {
    }
}