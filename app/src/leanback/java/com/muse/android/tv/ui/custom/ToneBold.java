package com.muse.android.tv.ui.custom;

import android.graphics.Typeface;
import android.widget.TextView;

import com.muse.android.tv.utils.ThemeTone;

/**
 * 电视浅色主题对比度修复：浅色壁纸（羊皮纸 1/2 号、亮的自定义壁纸）下把站名、时钟、行标题、分类胶囊字加粗。
 * 按所在页面实际套用的主题判深浅；深色 3 号与不跟壁纸的页面不调用 setTypeface，外观不变。
 */
public final class ToneBold {

    private ToneBold() {
    }

    public static void apply(TextView view) {
        if (view == null || !ThemeTone.isLight(view.getContext())) return;
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
    }
}
