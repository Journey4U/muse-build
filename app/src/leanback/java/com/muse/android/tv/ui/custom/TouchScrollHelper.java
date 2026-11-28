package com.muse.android.tv.ui.custom;

import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.BaseGridView;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 手机装电视版：影片网格手指滑动不回弹（2026-11-28 Liu 批准）。
 *
 * 根因（leanback-grid 1.0.0 GridLayoutManager 源码）：网格默认 FOCUS_SCROLL_ALIGNED，
 * 每次布局（海报加载完成、翻页追加、刷新等都会触发）onLayoutChildren 里 scrollToFocus 为真，
 * focusToViewInLayout() 会把「当前焦点/选中格」重新对齐到窗口；手指只改滚动位置、不改焦点，
 * 焦点还停在开头那一格，于是一布局就被拉回去。另外 removeInvisibleItemsAtFront() 不回收焦点格之后的行，
 * 焦点停在第 0 行时往下划的行全部留在内存里。
 *
 * 修法（只在触摸模式生效，遥控器路径一行不变）：
 * 1. 手指开始拖动（SCROLL_STATE_DRAGGING）且处于触摸模式时，临时把滚动策略换成 FOCUS_SCROLL_ITEM——
 *    布局时不再对齐焦点，划到哪停在哪；惯性甩动照常。
 * 2. 列表停下（SCROLL_STATE_IDLE）时，把选中位置换成屏幕内第一个完整可见、可获焦点的格子；
 *    该格完整可见，ITEM 策略下 setSelectedPosition 不产生滚动，不会再跳一下。
 * 3. 一离开触摸模式（按遥控器键，系统先退出触摸模式再分发按键）立刻恢复原滚动策略，之后与改前完全一致。
 * 真电视没有触摸屏，永远不进触摸模式，第 1 步不会发生，本类不改变任何行为。
 */
public final class TouchScrollHelper extends RecyclerView.OnScrollListener implements ViewTreeObserver.OnTouchModeChangeListener, View.OnAttachStateChangeListener {

    /** 停下并换好选中位置后回调（只在触摸模式下），position 为新的选中位置。 */
    public interface Callback {
        void onTouchSettled(int position);
    }

    private final BaseGridView grid;
    private final Callback callback;
    private final Runnable settleTask = this::settle;
    private int savedStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED;
    private boolean active;

    private TouchScrollHelper(BaseGridView grid, Callback callback) {
        this.grid = grid;
        this.callback = callback;
    }

    public static TouchScrollHelper attach(@NonNull BaseGridView grid, @Nullable Callback callback) {
        TouchScrollHelper helper = new TouchScrollHelper(grid, callback);
        grid.addOnScrollListener(helper);
        grid.addOnAttachStateChangeListener(helper);
        if (grid.isAttachedToWindow()) grid.getViewTreeObserver().addOnTouchModeChangeListener(helper);
        return helper;
    }

    @Override
    public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
        if (!grid.isInTouchMode()) return;
        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            enter();
        } else if (newState == RecyclerView.SCROLL_STATE_IDLE && active) {
            grid.removeCallbacks(settleTask);
            grid.post(settleTask);
        }
    }

    private void enter() {
        if (active) return;
        savedStrategy = grid.getFocusScrollStrategy();
        active = true;
        if (savedStrategy != BaseGridView.FOCUS_SCROLL_ITEM) grid.setFocusScrollStrategy(BaseGridView.FOCUS_SCROLL_ITEM);
    }

    private void exit() {
        grid.removeCallbacks(settleTask);
        if (!active) return;
        active = false;
        if (grid.getFocusScrollStrategy() != savedStrategy) grid.setFocusScrollStrategy(savedStrategy);
    }

    /** 列表停下后执行一次：找屏幕内第一个完整可见的可获焦格，设为选中。位置无效、列表为空、仍在滚动或布局中时什么都不做。 */
    private void settle() {
        if (!active || !grid.isInTouchMode() || !grid.isAttachedToWindow()) return;
        if (grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || grid.isComputingLayout()) return;
        RecyclerView.Adapter<?> adapter = grid.getAdapter();
        int count = adapter == null ? 0 : adapter.getItemCount();
        if (count == 0 || grid.getChildCount() == 0) return;
        int top = grid.getPaddingTop();
        int bottom = grid.getHeight() - grid.getPaddingBottom();
        int target = RecyclerView.NO_POSITION;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE || !child.hasFocusable()) continue;
            if (child.getTop() < top || child.getBottom() > bottom) continue;
            int position = grid.getChildAdapterPosition(child);
            if (position < 0 || position >= count) continue;
            if (target == RecyclerView.NO_POSITION || position < target) target = position;
        }
        if (target == RecyclerView.NO_POSITION) return;
        if (target != grid.getSelectedPosition()) grid.setSelectedPosition(target);
        if (callback != null) callback.onTouchSettled(target);
    }

    @Override
    public void onTouchModeChanged(boolean isInTouchMode) {
        if (!isInTouchMode) exit();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull View v) {
        v.getViewTreeObserver().addOnTouchModeChangeListener(this);
        if (!v.isInTouchMode()) exit();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull View v) {
        grid.removeCallbacks(settleTask);
        v.getViewTreeObserver().removeOnTouchModeChangeListener(this);
    }
}
