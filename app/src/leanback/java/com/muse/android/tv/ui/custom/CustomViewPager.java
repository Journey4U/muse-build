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
 * g() 的落点是 executeKeyEvent override（v4 收正；此前误置于 dispatchKeyEvent，
 * 被 super 内含的 stock arrowScroll 抢先消费右键，致首页工具栏右向不循环）。
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

    /**
     * 原版 g() 本体 = ViewPager.executeKeyEvent 的 override（原版 CustomViewPager 直接
     * override g，故 stock 的 arrowScroll 不存在）：焦点在 TextView 或当前页是 noScrollItem
     * （首页）时直接 return false，方向键完全不经 pager 的手——首页功能行首末互指靠默认
     * 焦点导航生效；其余页才走 u()（moveFocus）边缘翻页。
     * 必须 override executeKeyEvent 本身，不能把逻辑放进 dispatchKeyEvent：
     * ViewPager.dispatchKeyEvent 内部 = super.dispatchKeyEvent(event) || executeKeyEvent(event)，
     * 若先调 super，super 里自带的 stock executeKeyEvent（arrowScroll）会先行消费方向键。
     * v3/v13 右向不循环的根因即此：stock arrowScroll 的 findNextFocus 虽按互指 id 返回首项，
     * 但同页坐标判定 nextLeft≤currLeft 误读为「非右移」→ pageRight 翻到第一个分类页；
     * 左向因 0 页 pageLeft 返回 false 漏过、显式 id 才得以生效（2026-11-01 Liu 实测坐实）。
     */
    @Override
    public boolean executeKeyEvent(KeyEvent event) {
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
