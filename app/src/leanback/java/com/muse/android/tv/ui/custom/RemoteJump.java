package com.muse.android.tv.ui.custom;

import android.app.Dialog;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

/**
 * 遥控器「列表 ↔ 底部按钮层」跳转（Liu 2026-11-28）：
 * <ul>
 *   <li>单列列表里按左/右键直达底部按钮层的最左/最右按钮（只在列表项左右键原本无用的页面接管）；</li>
 *   <li>底部按钮层里左右键只在这一层相邻按钮间移动，到最左/最右停住不跑出这一层；</li>
 *   <li>上/下键交给调用方回调（回列表原位置等），回调为 null 时走系统默认。</li>
 * </ul>
 * 按钮左右顺序按屏幕实际位置算，隐藏/不可聚焦的按钮自动跳过，不写死哪颗在哪。
 */
public final class RemoteJump {

    private RemoteJump() {
    }

    public static boolean isLeftRight(KeyEvent event) {
        return event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    private static boolean usable(View view) {
        return view != null && view.isShown() && view.isFocusable() && view.isEnabled();
    }

    private static int screenX(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[0];
    }

    /** 可用按钮里屏幕上最左（left=true）或最右的那颗；都不可用返回 null。 */
    public static View edge(boolean left, View... buttons) {
        View best = null;
        int bestX = 0;
        for (View button : buttons) {
            if (!usable(button)) continue;
            int x = screenX(button);
            if (best == null || (left ? x < bestX : x > bestX)) {
                best = button;
                bestX = x;
            }
        }
        return best;
    }

    /** 左键跳最左按钮、右键跳最右按钮；跳成功返回 true。 */
    public static boolean jump(KeyEvent event, View... buttons) {
        View target = edge(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT, buttons);
        return target != null && target.requestFocus();
    }

    /** 把一排按钮串成一层：左右只在层内移动、到头停住；上/下键走回调（null = 系统默认）。 */
    public static void bindRow(Runnable onUp, Runnable onDown, View... buttons) {
        for (View button : buttons) {
            if (button == null) continue;
            button.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View next = neighbor(view, keyCode == KeyEvent.KEYCODE_DPAD_LEFT, buttons);
                    if (next != null) next.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && onUp != null) {
                    onUp.run();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && onDown != null) {
                    onDown.run();
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * 选项弹窗（单选/列表）弹出后焦点显式落进选项列表、停在当前已选项上（不依赖系统默认，避免焦点留在底下页面或标题上）；
     * 关闭（选中或返回取消）后焦点回到 returnTo（为 null 则不管）。
     */
    public static void focusChoice(AlertDialog dialog, int position, View returnTo) {
        if (dialog == null) return;
        ListView list = dialog.getListView();
        if (list != null) {
            list.post(() -> {
                list.requestFocus();
                if (position >= 0 && position < list.getCount()) list.setSelection(position);
            });
        }
        if (returnTo != null) dialog.setOnDismissListener(d -> returnTo.post(returnTo::requestFocus));
    }

    /** 返回键逐层退：焦点在这排按钮上时按返回只回内容区（toContent），不关弹窗；焦点在内容区时照原样关弹窗。 */
    public static void backToContent(Dialog dialog, Runnable toContent, View... buttons) {
        if (dialog == null) return;
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK || !anyFocused(buttons)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) toContent.run();
            return true;
        });
    }

    /** 这排按钮里是否有一颗正拿着焦点。 */
    public static boolean anyFocused(View... views) {
        for (View view : views) if (view != null && view.hasFocus()) return true;
        return false;
    }

    private static View neighbor(View from, boolean left, View... buttons) {
        int fromX = screenX(from);
        View best = null;
        int bestX = 0;
        for (View button : buttons) {
            if (button == from || !usable(button)) continue;
            int x = screenX(button);
            if (left ? x >= fromX : x <= fromX) continue;
            if (best == null || (left ? x > bestX : x < bestX)) {
                best = button;
                bestX = x;
            }
        }
        return best;
    }
}
