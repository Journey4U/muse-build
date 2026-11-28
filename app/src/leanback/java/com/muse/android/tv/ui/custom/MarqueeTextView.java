package com.muse.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;

/**
 * 单行文字：平时放不下就尾部省略（…），获得焦点或被选中（当前项）时跑马灯内循环滚动。
 * 照书架长书名的做法（失焦截断、焦点才滚），只是把切换放进控件本身，
 * 自己可聚焦的胶囊（选集弹窗、目录弹窗）与跟随父布局的文字（直播频道）都能用。
 */
public class MarqueeTextView extends MaterialTextView {

    public MarqueeTextView(@NonNull Context context) {
        super(context);
        init();
    }

    public MarqueeTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarqueeTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setMarqueeRepeatLimit(-1);
        apply(isFocused() || isSelected());
    }

    private void apply(boolean on) {
        TextUtils.TruncateAt want = on ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END;
        if (getEllipsize() != want) setEllipsize(want);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, @Nullable Rect previouslyFocusedRect) {
        apply(focused || isSelected());
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    @Override
    public void setSelected(boolean selected) {
        apply(selected || isFocused());
        super.setSelected(selected);
    }
}
