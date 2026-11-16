package com.muse.android.tv.bean;

import android.view.View;

import androidx.annotation.Nullable;

import com.muse.android.tv.R;
import com.muse.android.tv.impl.Diffable;
import com.muse.android.tv.utils.ResUtil;

public class Func implements Diffable<Func> {

    private final int resId;
    private int drawable;
    private final int id = View.generateViewId();
    private int nextFocusLeft;
    private int nextFocusRight;

    public static Func create(int resId) {
        return new Func(resId);
    }

    public Func(int resId) {
        this.resId = resId;
        this.setDrawable();
    }

    public int getResId() {
        return resId;
    }

    public int getId() {
        return id;
    }

    public int getNextFocusLeft() {
        return nextFocusLeft;
    }

    public int getNextFocusRight() {
        return nextFocusRight;
    }

    public void setNextFocusLeft(int nextFocusLeft) {
        this.nextFocusLeft = nextFocusLeft;
    }

    public void setNextFocusRight(int nextFocusRight) {
        this.nextFocusRight = nextFocusRight;
    }

    public int getDrawable() {
        return drawable;
    }

    public String getText() {
        return ResUtil.getString(resId);
    }

    public void setDrawable() {
        if (resId == R.string.home_home) this.drawable = R.drawable.ic_home_main;
        else if (resId == R.string.home_vod) this.drawable = R.drawable.ic_home_vod;
        else if (resId == R.string.home_live) this.drawable = R.drawable.ic_home_live;
        else if (resId == R.string.home_keep) this.drawable = R.drawable.ic_home_keep;
        else if (resId == R.string.home_push) this.drawable = R.drawable.ic_home_push;
        else if (resId == R.string.home_cast) this.drawable = R.drawable.ic_home_cast;
        else if (resId == R.string.home_history_short) this.drawable = R.drawable.ic_home_history;
        else if (resId == R.string.home_search) this.drawable = R.drawable.ic_home_search;
        else if (resId == R.string.shelf_title) this.drawable = R.drawable.ic_action_bookshelf;
        else if (resId == R.string.nav_browser) this.drawable = R.drawable.ic_nav_browser;
        else if (resId == R.string.home_setting) this.drawable = R.drawable.ic_home_setting;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Func it)) return false;
        return getResId() == it.getResId();
    }

    @Override
    public boolean isSameItem(Func other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Func other) {
        return equals(other);
    }
}

