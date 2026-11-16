package com.muse.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.muse.android.tv.R;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 电视版分类文件夹选择器（可展开目录树）——移植自手机版 FolderTreeDialog（源出昏黄影视 1.0.4），
 * 按遥控器逻辑改交互：行可聚焦，OK = 选中该目录，右键 = 展开 / 收起子目录，左键 = 收起。
 *
 * <p>用途与手机版一致：移动目标 / 增加分类 / 删除分类 / 重命名分类文件夹共用；
 * 另加 {@link #showRoots} 给「本地源生成设置」挑扫描目录（根目录可传任意起点，如内部存储根）。</p>
 *
 * <p>只列目录、不列源文件；子目录按需展开，避免一次性递归扫描整片存储。</p>
 */
public class FolderTreeDialog {

    /** 选中目录后的回调（在主线程）。 */
    public interface Listener {
        void onPick(File dir);
    }

    /** 多动作回调。{@code neutral=true} 表示点的是底部第二颗动作按钮（如「删除」）。 */
    public interface ActionListener {
        void onAction(File dir, boolean neutral);
    }

    private final Context context;
    private final ActionListener listener;
    private final int titleRes;
    private final int tipRes;
    private final int positiveRes;
    private final int neutralRes;
    private final List<File> roots;
    private final Set<String> expanded = new HashSet<>();
    private final LinearLayout rows;
    private final TextView tip;
    private final int density;
    private File selected;
    private View firstRow;
    private AlertDialog dialog;

    private FolderTreeDialog(Context context, List<File> roots, int titleRes, int tipRes, int positiveRes, int neutralRes, ActionListener listener) {
        this.context = context;
        this.listener = listener;
        this.titleRes = titleRes;
        this.tipRes = tipRes;
        this.positiveRes = positiveRes;
        this.neutralRes = neutralRes;
        this.roots = roots;
        this.density = Math.round(context.getResources().getDisplayMetrics().density);
        this.rows = new LinearLayout(context);
        this.rows.setOrientation(LinearLayout.VERTICAL);
        this.tip = new TextView(context);
        this.tip.setPadding(dp(14), dp(8), dp(14), dp(4));
        this.tip.setTextSize(12);
        this.tip.setAlpha(0.7f);
        this.tip.setText(tipRes);
    }

    private static List<File> scanRoots() {
        List<File> roots = new ArrayList<>();
        for (String scan : LocalSourceHub.getScanDirs()) {
            if (TextUtils.isEmpty(scan)) continue;
            File root = new File(scan);
            if (root.isDirectory()) roots.add(root);
        }
        return roots;
    }

    /** 重命名分类文件夹用的入口（标题/提示/按钮沿用「重命名」语义）。 */
    public static void show(Context context, Listener listener) {
        if (context == null) return;
        new FolderTreeDialog(context, scanRoots(), R.string.site_folder_pick_title, R.string.site_folder_pick_title,
                R.string.site_action_rename_folder, 0,
                (dir, neutral) -> {
                    if (listener != null) listener.onPick(dir);
                }).showDialog();
    }

    /**
     * 通用「选目录 + 动作按钮」入口（根 = 已保存的扫描目录）。
     *
     * @param neutralRes 次动作按钮文案（传 0 表示只要一颗动作按钮）
     */
    public static void showPicker(Context context, int titleRes, int tipRes, int positiveRes, int neutralRes, ActionListener listener) {
        if (context == null) return;
        new FolderTreeDialog(context, scanRoots(), titleRes, tipRes, positiveRes, neutralRes, listener).showDialog();
    }

    /** 自定义根目录版（本地源设置里挑扫描目录用：从存储根开始选）。 */
    public static void showRoots(Context context, List<File> roots, int titleRes, int tipRes, int positiveRes, Listener listener) {
        if (context == null) return;
        new FolderTreeDialog(context, roots, titleRes, tipRes, positiveRes, 0,
                (dir, neutral) -> {
                    if (listener != null) listener.onPick(dir);
                }).showDialog();
    }

    private void showDialog() {
        ScrollView scroll = new ScrollView(context);
        scroll.setFocusable(false);
        scroll.addView(rows, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(tip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(titleRes)
                .setView(container)
                .setNegativeButton(R.string.dialog_negative, null);
        if (neutralRes != 0) builder.setNeutralButton(neutralRes, null);
        AlertDialog dialog = builder.setPositiveButton(positiveRes, null).create();
        this.dialog = dialog;
        dialog.show();
        // 未选中目录时不允许关闭：原地提示，避免用户以为点了没反应
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> dispatch(dialog, false));
        if (neutralRes != 0) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> dispatch(dialog, true));
        // 按钮区按上键回列表（停在选中行，没有则最后一行）；列表最后一行按下键也能跳到「确定」（见 addRow）。
        View.OnKeyListener upToList = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                focusListRow();
                return true;
            }
            return false;
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnKeyListener(upToList);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnKeyListener(upToList);
        if (neutralRes != 0) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnKeyListener(upToList);
        rebuild();
        // 打开即把焦点给第一行，遥控器可直接上下选
        if (firstRow != null) firstRow.requestFocus();
    }

    private void dispatch(AlertDialog dialog, boolean neutral) {
        if (selected == null) {
            Notify.show(R.string.site_folder_none_selected);
            return;
        }
        final File dir = selected;
        dialog.dismiss();
        if (listener != null) listener.onAction(dir, neutral);
    }

    private void rebuild() {
        rows.removeAllViews();
        firstRow = null;
        boolean any = false;
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            any = true;
            addChildren(root, 0);
        }
        tip.setText(any ? tipRes : R.string.site_folder_pick_empty);
    }

    /** 把 dir 的子目录铺成行；对已展开的子目录递归下钻（depth 决定缩进）。 */
    private void addChildren(File dir, int depth) {
        for (File child : subDirs(dir)) {
            boolean hasChild = !subDirs(child).isEmpty();
            addRow(child, depth + 1, hasChild);
            if (expanded.contains(child.getAbsolutePath())) addChildren(child, depth + 1);
        }
    }

    private List<File> subDirs(File dir) {
        File[] all = dir == null ? null : dir.listFiles(File::isDirectory);
        if (all == null) return new ArrayList<>();
        List<File> out = new ArrayList<>();
        for (File f : all) {
            if (f.getName().startsWith(".")) continue;
            out.add(f);
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private void addRow(File dir, int depth, boolean hasChild) {
        boolean isExpanded = expanded.contains(dir.getAbsolutePath());
        boolean isSelected = selected != null && selected.getAbsolutePath().equals(dir.getAbsolutePath());

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10) * depth, dp(9), dp(10), dp(9));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        paint(row, isSelected, false);

        TextView arrow = new TextView(context);
        arrow.setText(hasChild ? (isExpanded ? "▾" : "▸") : "");
        arrow.setTextSize(15);
        arrow.setGravity(Gravity.CENTER);
        arrow.setWidth(dp(26));

        TextView name = new TextView(context);
        name.setText(dir.getName());
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        if (isSelected) name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        row.addView(arrow);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // OK = 选中；右键 = 展开/收起；左键 = 收起。触屏点击同样是选中（电视装手机上测试时可用）。
        row.setOnClickListener(v -> {
            selected = dir;
            rebuildAndRefocus(dir);
        });
        // 长按 OK = 选中并直接确定（不用把焦点移到底部按钮区）。
        row.setOnLongClickListener(v -> {
            selected = dir;
            if (dialog != null) dispatch(dialog, false);
            return true;
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            // 最后一行按下键：焦点跳到「确定」按钮（列表在定高滚动区里，系统焦点搜索够不到按钮区）。
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && dialog != null
                    && rows.getChildCount() > 0 && rows.getChildAt(rows.getChildCount() - 1) == v) {
                Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (positive != null) {
                    positive.requestFocus();
                    return true;
                }
                return false;
            }
            if (!hasChild) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !isExpanded) {
                expanded.add(dir.getAbsolutePath());
                rebuildAndRefocus(dir);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isExpanded) {
                expanded.remove(dir.getAbsolutePath());
                rebuildAndRefocus(dir);
                return true;
            }
            return false;
        });
        row.setOnFocusChangeListener((v, hasFocus) -> paint(row, isSelected, hasFocus));
        rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (firstRow == null) firstRow = row;
    }

    /** 选中态浅蓝高亮、焦点态描边底色（不依赖额外 drawable，避免主题差异）。 */
    private void paint(LinearLayout row, boolean isSelected, boolean hasFocus) {
        if (hasFocus) row.setBackgroundColor(0x5533B5E5);
        else if (isSelected) row.setBackgroundColor(0x332196F3);
        else row.setBackgroundColor(0x00000000);
    }

    /** 重建后把焦点还给同一目录的行（展开/选中都会重建列表，焦点不能丢）。 */
    private void rebuildAndRefocus(File target) {
        String path = target.getAbsolutePath();
        int focusIndex = -1;
        List<File> flat = new ArrayList<>();
        for (File root : roots) collect(root, flat);
        for (int i = 0; i < flat.size(); i++) {
            if (flat.get(i).getAbsolutePath().equals(path)) {
                focusIndex = i;
                break;
            }
        }
        rebuild();
        if (focusIndex >= 0 && focusIndex < rows.getChildCount()) rows.getChildAt(focusIndex).requestFocus();
    }

    /** 按钮区回列表：焦点停在选中行（没有则最后一行）。 */
    private void focusListRow() {
        if (rows.getChildCount() == 0) return;
        int index = rows.getChildCount() - 1;
        if (selected != null) {
            List<File> flat = new ArrayList<>();
            for (File root : roots) collect(root, flat);
            for (int i = 0; i < flat.size(); i++) {
                if (flat.get(i).getAbsolutePath().equals(selected.getAbsolutePath())) {
                    index = i;
                    break;
                }
            }
        }
        if (index >= rows.getChildCount()) index = rows.getChildCount() - 1;
        rows.getChildAt(index).requestFocus();
    }

    private void collect(File dir, List<File> out) {
        for (File child : subDirs(dir)) {
            out.add(child);
            if (expanded.contains(child.getAbsolutePath())) collect(child, out);
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
