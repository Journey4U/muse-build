package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.ui.adapter.SiteAdapter;
import com.muse.android.tv.ui.adapter.SiteTextCache;
import com.muse.android.tv.ui.custom.CustomRecyclerView;
import com.muse.android.tv.utils.ThemeTone;

/**
 * 源列表共享网格（Liu 2026-11-28 19:16「首次 500 个源加载好，之后每次点源列表都应秒进，上下滑都不卡」）。
 * 全应用只有一份：列表 + 全部格子一次建好、绑好。首页/搜索/设置三个入口打开源列表时，把这份网格从旧位置摘下、
 * 挂进当前弹窗（顶替布局里的占位列表），关闭时摘下留着；下次任一入口直接再挂，不重建格子、不重设文字。
 * 上下文 = 应用级上下文 + 弹窗当时的主题与资源（主题/资源对象不引用 Activity），不持有页面、不泄漏。
 * 作废条件（下次打开全量重建）：配置重载（源对象换了 / 增删 / 显隐变了）、主题或壁纸深浅、夜间模式、密度、屏幕尺寸。
 * 重载 = 全部重建，不做逐项比对（Liu 19:14）。
 */
public final class SiteGridCache {

    private static RecyclerView sGrid;
    private static SiteAdapter sAdapter;
    private static String sSignature;
    private static String sTextSignature;

    private SiteGridCache() {
    }

    /** 当前配置/主题/屏幕的签名；任一项变了，旧网格作废。 */
    private static String signature(Context context) {
        Resources res = context.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        int night = res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        long hash = 17;
        int count = 0;
        for (Site site : VodConfig.get().getSites()) {
            if (site == null) continue;
            hash = hash * 31 + System.identityHashCode(site);
            hash = hash * 31 + (site.isHide() ? 1 : 0);
            hash = hash * 31 + String.valueOf(site.getKey()).hashCode();
            hash = hash * 31 + String.valueOf(site.getName()).hashCode();
            count++;
        }
        return ThemeTone.tone() + "|" + night + "|" + dm.density + "|" + dm.scaledDensity + "|" + dm.widthPixels + "x" + dm.heightPixels + "|" + count + "|" + hash;
    }

    /**
     * 配置加载/重载完成时调用（首页内容出来时、设置页加载完成时）：
     * 签名变了就丢掉旧网格，并立刻在后台线程把全部源名的排版缓存重建好（三个入口共用）。
     * 签名没变什么都不做。网格本身（View）在下次打开源列表时一次建好。
     */
    public static void onConfigLoaded(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String sig = signature(activity);
        if (sGrid != null && !sig.equals(sSignature) && sGrid.getParent() == null) invalidate();
        if (!sig.equals(sTextSignature)) {
            sTextSignature = sig;
            SiteTextCache.rebuild(activity);
        }
    }

    /** 丢弃共享网格（下次打开全量重建）。 */
    public static void invalidate() {
        if (sGrid != null) {
            detach(sGrid);
            sGrid.setAdapter(null);
            sGrid.getRecycledViewPool().clear();
        }
        sGrid = null;
        sAdapter = null;
        sSignature = null;
    }

    private static void build(Context dialogContext, String sig) {
        invalidate();
        final Resources res = dialogContext.getResources();
        // 应用级上下文 + 弹窗当时的主题与资源：格子样子与原来在弹窗里建的一样，又不引用 Activity。
        Context ctx = new ContextThemeWrapper(dialogContext.getApplicationContext(), dialogContext.getTheme()) {
            @Override
            public Resources getResources() {
                return res;
            }
        };
        CustomRecyclerView grid = new CustomRecyclerView(ctx);
        grid.setId(R.id.recycler);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        SiteAdapter adapter = new SiteAdapter(null);
        grid.setLayoutManager(new FullGridLayoutManager(ctx, 4));
        grid.setAdapter(adapter);
        grid.getRecycledViewPool().setMaxRecycledViews(0, 40);
        // 兜底：即使有格子滑出额外布局区，也留在离屏缓存里直接复用，不重新绑定。
        grid.setItemViewCacheSize(Math.max(8, adapter.getItemCount()));
        sGrid = grid;
        sAdapter = adapter;
        sSignature = sig;
    }

    /**
     * 弹窗打开时调用：把共享网格换进弹窗布局里占位列表的位置（沿用占位列表的布局参数）。
     * 共享网格作废则当场全量重建（首次 / 重载后首次打开付一次代价）。
     */
    public static RecyclerView attach(Activity activity, RecyclerView placeholder) {
        String sig = signature(activity);
        if (!sig.equals(sTextSignature)) {
            sTextSignature = sig;
            SiteTextCache.rebuild(activity);
        }
        if (sGrid == null || sAdapter == null || !sig.equals(sSignature)) build(placeholder.getContext(), sig);
        RecyclerView grid = sGrid;
        detach(grid);
        // 上次可能在筛选时关的窗（那时共享网格是藏着的），挂进来先恢复显示。
        grid.setVisibility(View.VISIBLE);
        ViewParent p = placeholder.getParent();
        if (p instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) p;
            int index = parent.indexOfChild(placeholder);
            ViewGroup.LayoutParams lp = placeholder.getLayoutParams();
            parent.removeView(placeholder);
            parent.addView(grid, index, lp);
        }
        return grid;
    }

    /** 弹窗关闭时调用：把网格从弹窗摘下（不随弹窗销毁），并清掉指向弹窗的回调与间距装饰，避免泄漏。 */
    public static void release(RecyclerView grid) {
        if (grid == null || grid != sGrid) return;
        if (grid.hasFocus()) grid.clearFocus();
        detach(grid);
        if (sAdapter != null) {
            sAdapter.setListener(null);
            sAdapter.setKeyListener(null);
        }
    }

    /** 弹窗里重载配置并已就地刷新列表后调用：签名换成新的，网格继续有效，排版缓存在后台整体重建。 */
    public static void resign(Activity activity) {
        if (sGrid == null || sAdapter == null || activity == null) return;
        String sig = signature(activity);
        sGrid.setItemViewCacheSize(Math.max(8, sAdapter.getItemCount()));
        sSignature = sig;
        sTextSignature = sig;
        SiteTextCache.rebuild(activity);
    }

    public static SiteAdapter adapter() {
        return sAdapter;
    }

    private static void detach(RecyclerView grid) {
        ViewParent p = grid.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(grid);
    }

    /**
     * 一次性全量布局：上下额外布局空间 = 全部行高之和（行数按当前条目数现算 × 行距），
     * 打开时所有格子一次建好绑好、全部挂着；上下滑只是平移，不再建格子、不再绑定。
     * 筛选/重载后条目数变了，下一次布局自动按新数量算，不缓存旧行数。
     */
    public static class FullGridLayoutManager extends GridLayoutManager {

        private int rowPitch;

        public FullGridLayoutManager(Context context, int spanCount) {
            super(context, spanCount);
        }

        /** 行距（px）= 胶囊高 + 行间隔；只用来算额外布局空间，不影响格子尺寸与位置。 */
        public void setRowPitch(int rowPitch) {
            this.rowPitch = rowPitch;
        }

        @Override
        protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
            int span = Math.max(1, getSpanCount());
            int rows = (state.getItemCount() + span - 1) / span;
            if (rowPitch <= 0 || rows <= 0) {
                super.calculateExtraLayoutSpace(state, extraLayoutSpace);
                return;
            }
            int all = rows * rowPitch;
            extraLayoutSpace[0] = all;
            extraLayoutSpace[1] = all;
        }
    }
}
