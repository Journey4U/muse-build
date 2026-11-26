package com.muse.android.tv.ui.custom;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.muse.android.tv.R;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手机自定义底栏（换装第 1 步，Liu 2026-11-26 14:42 定）：替掉 Material BottomNavigationView，
 * 去掉它「最多 5 项」的组件上限；每格图标 + 文字，可见格平分宽度。
 * 行为与原组件对齐：点格子回调监听，返回 true 才把该格标为选中（直播/浏览器/书架/漫画
 * 打开新页返回 false，选中态留在点播/设置）；setSelectedItemId 同样走监听；
 * 选中格序号随界面重建保存恢复。
 */
public class CustomNavBar extends LinearLayout {

    public interface OnItemSelectedListener {
        boolean onItemSelected(@IdRes int id);
    }

    private final Map<Integer, View> mItems = new LinkedHashMap<>();
    private OnItemSelectedListener mListener;
    private int mSelectedId = View.NO_ID;

    public CustomNavBar(Context context) {
        this(context, null);
    }

    public CustomNavBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setSaveEnabled(true);
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        mListener = listener;
    }

    /** 加一格（初始隐藏，与原菜单 android:visible="false" 一致，由 setItemVisible 按配置点亮）。 */
    public void addItem(@IdRes int id, @DrawableRes int icon, @StringRes int title) {
        View item = LayoutInflater.from(getContext()).inflate(R.layout.view_nav_item, this, false);
        item.setId(id);
        ((ImageView) item.findViewById(R.id.navIcon)).setImageResource(icon);
        ((TextView) item.findViewById(R.id.navText)).setText(title);
        item.setContentDescription(getContext().getString(title));
        item.setVisibility(View.GONE);
        item.setOnClickListener(v -> performSelect(id));
        LayoutParams params = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        addView(item, params);
        mItems.put(id, item);
        item.setSelected(id == mSelectedId);
    }

    @Nullable
    public View getItemView(@IdRes int id) {
        return mItems.get(id);
    }

    public void setItemVisible(@IdRes int id, boolean visible) {
        View item = mItems.get(id);
        if (item != null) item.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isItemVisible(@IdRes int id) {
        View item = mItems.get(id);
        return item != null && item.getVisibility() == View.VISIBLE;
    }

    public int getSelectedItemId() {
        return mSelectedId;
    }

    public void setSelectedItemId(@IdRes int id) {
        performSelect(id);
    }

    private void performSelect(int id) {
        if (!mItems.containsKey(id)) return;
        if (mListener != null && mListener.onItemSelected(id)) markSelected(id);
    }

    private void markSelected(int id) {
        mSelectedId = id;
        for (Map.Entry<Integer, View> entry : mItems.entrySet()) entry.getValue().setSelected(entry.getKey() == id);
    }

    @Nullable
    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable("super", super.onSaveInstanceState());
        bundle.putInt("selected", mSelectedId);
        return bundle;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle bundle) {
            super.onRestoreInstanceState(bundle.getParcelable("super"));
            int id = bundle.getInt("selected", View.NO_ID);
            if (id != View.NO_ID) markSelected(id);
        } else {
            super.onRestoreInstanceState(state);
        }
    }
}
