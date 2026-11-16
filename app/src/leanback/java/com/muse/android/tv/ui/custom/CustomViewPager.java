package com.muse.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.muse.android.tv.R;

/**
 * 原版 OK 5.1.6 大屏版 CustomViewPager（ViewPager1）的移植。
 * 我方 leanback 树此前已有一个同名存根（仅 setCurrentItem 立即切换），本文件将其替换为完整原版：
 * 立即切换 + setNoScrollItem(0) + 焦点边缘翻页 + 到头抖动（R.anim.shake）。
 * 与原版的细微差别：抖动动画用 layout 传入的 context 加载（原版用 App 单例），行为一致。
 */
public class CustomViewPager extends ViewPager {

    private final Animation mShake;
    private final Rect mRect;
    private int mNoScrollItem;

    public CustomViewPager(@NonNull Context context) {
        super(context);
        mRect = new Rect();
        mShake = AnimationUtils.loadAnimation(context, R.anim.shake);
        mNoScrollItem = -1;
    }

    public CustomViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mRect = new Rect();
        mShake = AnimationUtils.loadAnimation(context, R.anim.shake);
        mNoScrollItem = -1;
    }

    @Override
    public void setCurrentItem(int item) {
        super.setCurrentItem(item, false);
    }

    public void setNoScrollItem(int item) {
        mNoScrollItem = item;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((findFocus() instanceof TextView) || getCurrentItem() == mNoScrollItem) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            return moveFocus(View.FOCUS_LEFT);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return moveFocus(View.FOCUS_RIGHT);
        }
        return false;
    }

    /**
     * 原版 u()：焦点在页内移动时 requestFocus；越过左右边缘时翻页；
     * 无下一焦点时：首页左滑 / 末页右滑抖动，否则翻页；成功则播放方向音效。
     */
    private boolean moveFocus(int direction) {
        View focused = findFocus();
        if (focused == this) {
            focused = null;
        }
        if (focused != null) {
            ViewParent parent = focused.getParent();
            while (parent instanceof ViewGroup && parent != this) {
                parent = parent.getParent();
            }
            if (parent != this) {
                focused = null;
            }
        }
        View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
        boolean handled = false;
        if (next != null && next != focused) {
            if (direction == View.FOCUS_LEFT) {
                int nextLeft = getRect(next, mRect).left;
                int focusedLeft = getRect(focused, mRect).left;
                if (focused != null && nextLeft >= focusedLeft) {
                    if (getCurrentItem() > 0) {
                        setCurrentItem(getCurrentItem() - 1);
                        handled = true;
                    }
                } else {
                    handled = next.requestFocus();
                }
            } else if (direction == View.FOCUS_RIGHT) {
                int nextLeft = getRect(next, mRect).left;
                int focusedLeft = getRect(focused, mRect).left;
                if (focused != null && nextLeft <= focusedLeft) {
                    handled = nextPage();
                } else {
                    handled = next.requestFocus();
                }
            }
        } else {
            if (direction == View.FOCUS_LEFT) {
                if (getCurrentItem() == 0) {
                    if (focused != null) {
                        focused.clearAnimation();
                        focused.startAnimation(mShake);
                    }
                } else if (getCurrentItem() > 0) {
                    setCurrentItem(getCurrentItem() - 1);
                    handled = true;
                }
            } else if (direction == View.FOCUS_RIGHT) {
                if (getAdapter() != null && getCurrentItem() == getAdapter().getCount() - 1) {
                    if (focused != null) {
                        focused.clearAnimation();
                        focused.startAnimation(mShake);
                    }
                } else {
                    handled = nextPage();
                }
            }
        }
        if (handled) {
            playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
        }
        return handled;
    }

    private boolean nextPage() {
        if (getAdapter() == null || getCurrentItem() >= getAdapter().getCount() - 1) {
            return false;
        }
        setCurrentItem(getCurrentItem() + 1);
        return true;
    }

    /** 原版 v()：view 相对本 pager 的坐标矩形。 */
    private Rect getRect(View view, Rect rect) {
        if (rect == null) {
            rect = new Rect();
        }
        if (view == null) {
            rect.set(0, 0, 0, 0);
            return rect;
        }
        rect.left = view.getLeft();
        rect.right = view.getRight();
        rect.top = view.getTop();
        rect.bottom = view.getBottom();
        ViewParent parent = view.getParent();
        while ((parent instanceof ViewGroup) && parent != this) {
            ViewGroup viewGroup = (ViewGroup) parent;
            rect.left = viewGroup.getLeft() + rect.left;
            rect.right = viewGroup.getRight() + rect.right;
            rect.top = viewGroup.getTop() + rect.top;
            rect.bottom = viewGroup.getBottom() + rect.bottom;
            parent = viewGroup.getParent();
        }
        return rect;
    }
}
