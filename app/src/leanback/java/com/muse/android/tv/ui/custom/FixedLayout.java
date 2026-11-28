package com.muse.android.tv.ui.custom;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.muse.android.tv.databinding.AdapterEpisodeDialogBinding;
import com.muse.android.tv.databinding.AdapterEpisodeRangeBinding;

import com.muse.android.tv.utils.ResUtil;

/**
 * 「按屏幕钉死」布局的公用小工具（2026-11-28 电视钉死布局批）。
 * 规矩：容器尺寸只看屏幕（屏宽/屏高比例）在打开前算一次；胶囊本身保持原版 dp/sp，
 * 不跟比例缩。胶囊高度用真实布局模板 + 固定样字量一次（量的是字体，不是数据），
 * 样字含中文与 g j p q y 下沿字母，量出来的高度装得下下沿，不会切字。
 */
public final class FixedLayout {

    /** 量胶囊高度的样字：中文 + 数字 + 带下沿的英文字母。 */
    public static final String SAMPLE = "第88集 Agjpqy";

    private FixedLayout() {
    }

    /** 宽度不限（wrap_content 胶囊）时的模板高度。 */
    public static int height(View template, TextView text) {
        if (text != null) text.setText(SAMPLE);
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        template.measure(spec, spec);
        return template.getMeasuredHeight();
    }

    /** 宽度固定（match_parent 格子）时的模板高度。 */
    public static int height(View template, TextView text, int width) {
        if (text != null) text.setText(SAMPLE);
        int w = View.MeasureSpec.makeMeasureSpec(Math.max(width, 1), View.MeasureSpec.EXACTLY);
        int h = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        template.measure(w, h);
        return template.getMeasuredHeight();
    }

    /**
     * 选集弹窗/小说目录弹窗：格子区高度钉死为「rows 行原版格子 + 行间距」，页签行钉死为原版页签高。
     * 翻到最后一页不足 rows 行时弹窗也不缩（座位原则：布局只看屏幕，不看条目数）。
     */
    public static void pinPagedGrid(View grid, View ranges, int rows, int gapDp) {
        LayoutInflater inflater = LayoutInflater.from(grid.getContext());
        AdapterEpisodeDialogBinding item = AdapterEpisodeDialogBinding.inflate(inflater, (ViewGroup) grid.getParent(), false);
        AdapterEpisodeRangeBinding range = AdapterEpisodeRangeBinding.inflate(inflater, (ViewGroup) ranges.getParent(), false);
        int itemHeight = height(item.getRoot(), item.text);
        int rangeHeight = height(range.getRoot(), range.text);
        setHeight(grid, itemHeight * rows + ResUtil.dp2px(gapDp) * (rows - 1));
        setHeight(ranges, rangeHeight);
    }

    public static void setHeight(View view, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null || params.height == height) return;
        params.height = height;
        view.setLayoutParams(params);
    }

    /** 屏宽 × 比例（像素）。 */
    public static int widthOf(float ratio) {
        return Math.round(ResUtil.getScreenWidth() * ratio);
    }
}
