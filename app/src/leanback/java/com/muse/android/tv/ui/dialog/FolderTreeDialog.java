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
import com.muse.android.tv.ui.custom.RemoteJump;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.utils.FileBrowse;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
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
 *
 * <p>列目录统一走 {@link FileBrowse}（Liu 2026-11-28 文件浏览批）：跟「隐藏系统文件」开关与排序方式走，
 * 两项与文件页、设置页共用同一设置。列表上方一排「排序」「隐藏系统文件」按钮，遥控器可达；菜单键也弹选项。</p>
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
    private final TextView sortButton;
    private final TextView hideButton;
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
        this.sortButton = toolButton();
        this.hideButton = toolButton();
        this.sortButton.setOnClickListener(v -> showSortDialog());
        this.hideButton.setOnClickListener(v -> toggleHide());
        // 上方工具按钮一层：左右只在两颗之间移动、到头停住；下键回列表（选中行，没有则第一行；列表空则到「确定」）
        RemoteJump.bindRow(null, () -> {
            if (rows.getChildCount() > 0) focusRowOrFirst();
            else if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        }, sortButton, hideButton);
        updateToolButtons();
    }

    /** 列表上方的工具按钮（排序 / 隐藏系统文件）：可聚焦，焦点态与行同色。 */
    private TextView toolButton() {
        TextView button = new TextView(context);
        button.setTextSize(14);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setBackgroundColor(0x1AFFFFFF);
        button.setOnFocusChangeListener((v, hasFocus) -> v.setBackgroundColor(hasFocus ? 0x5533B5E5 : 0x1AFFFFFF));
        return button;
    }

    private void updateToolButtons() {
        sortButton.setText(ResUtil.getString(R.string.file_sort_label, FileBrowse.getSortName()));
        hideButton.setText(ResUtil.getString(R.string.file_hide_label, Setting.getSwitch(FileBrowse.isHideDot())));
    }

    private void showSortDialog() {
        // 弹出后焦点显式落在当前已选项；OK 选中并关闭、返回取消，关闭后焦点回弹窗里的「排序」按钮
        RemoteJump.focusChoice(new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(R.string.file_sort)
                .setSingleChoiceItems(FileBrowse.getSortNames(), FileBrowse.getSort(), (d, which) -> {
                    FileBrowse.putSort(which);
                    d.dismiss();
                    applyBrowseChange(sortButton);
                })
                .show(), FileBrowse.getSort(), sortButton);
    }

    private void toggleHide() {
        FileBrowse.putHideDot(!FileBrowse.isHideDot());
        applyBrowseChange(hideButton);
    }

    /** 菜单键：四种排序 + 隐藏开关（与电视文件页同一套写法）。 */
    private void showMenuDialog() {
        String[] sorts = FileBrowse.getSortNames();
        String[] items = new String[sorts.length + 1];
        int current = FileBrowse.getSort();
        for (int i = 0; i < sorts.length; i++) items[i] = (i == current ? "● " : "○ ") + sorts[i];
        items[sorts.length] = ResUtil.getString(R.string.file_hide_label, Setting.getSwitch(FileBrowse.isHideDot()));
        RemoteJump.focusChoice(new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setItems(items, (d, which) -> {
                    if (which < sorts.length) FileBrowse.putSort(which);
                    else FileBrowse.putHideDot(!FileBrowse.isHideDot());
                    applyBrowseChange(null);
                })
                .show(), current, null);
    }

    /**
     * 排序/隐藏改了：重建当前树（展开状态保留），选中目录若被隐藏则清掉；
     * 焦点留在触发的按钮上，菜单键触发的则回到选中行（没有则第一行），保证遥控器焦点不丢。
     */
    private void applyBrowseChange(View keepFocus) {
        updateToolButtons();
        rebuild();
        if (selected != null && indexOf(selected) < 0) {
            selected = null;
            rebuild();
        }
        if (keepFocus != null) keepFocus.requestFocus();
        else if (rows.getChildCount() > 0) focusRowOrFirst();
        else sortButton.requestFocus();
    }

    /** 当前树（含已展开子目录）里某目录所在的行号，不在则 -1。 */
    private int indexOf(File target) {
        String path = target.getAbsolutePath();
        List<File> flat = new ArrayList<>();
        for (File root : roots) collect(root, flat);
        for (int i = 0; i < flat.size(); i++) {
            if (flat.get(i).getAbsolutePath().equals(path)) return i;
        }
        return -1;
    }

    private void focusRowOrFirst() {
        if (rows.getChildCount() == 0) return;
        int index = selected == null ? 0 : indexOf(selected);
        if (index < 0 || index >= rows.getChildCount()) index = 0;
        rows.getChildAt(index).requestFocus();
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
        LinearLayout tools = new LinearLayout(context);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(dp(10), dp(2), dp(10), dp(6));
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sortLp.setMarginEnd(dp(8));
        tools.addView(sortButton, sortLp);
        tools.addView(hideButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        container.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(titleRes)
                .setView(container)
                .setNegativeButton(R.string.dialog_negative, null);
        if (neutralRes != 0) builder.setNeutralButton(neutralRes, null);
        AlertDialog dialog = builder.setPositiveButton(positiveRes, null).create();
        this.dialog = dialog;
        // 菜单键（弹窗内任意位置）弹排序/隐藏选项；抬手时弹，按下与抬起都吃掉避免重复
        // 返回逐层退：焦点在底部按钮或上方排序/隐藏按钮上时，返回先回列表（选中行，没有则第一行），不关弹窗；
        // 焦点在列表里按返回照原样关闭弹窗
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && rows.getChildCount() > 0
                    && RemoteJump.anyFocused(sortButton, hideButton, dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE), dialog.getButton(AlertDialog.BUTTON_NEUTRAL))) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    // 上方工具按钮回选中行（没有则第一行）；底部按钮回选中行（没有则最后一行），与上键一致
                    if (RemoteJump.anyFocused(sortButton, hideButton)) focusRowOrFirst();
                    else focusListRow();
                }
                return true;
            }
            if (keyCode != KeyEvent.KEYCODE_MENU) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) showMenuDialog();
            return true;
        });
        dialog.show();
        // 未选中目录时不允许关闭：原地提示，避免用户以为点了没反应
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> dispatch(dialog, false));
        if (neutralRes != 0) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> dispatch(dialog, true));
        // 底部按钮层：左右只在层内移动、到头停住；上键回列表（停在选中行，没有则最后一行，列表空则回上方「排序」）。
        // 列表最后一行按下键也能跳到「确定」（见 addRow）。树节点的左右键已用于展开/收起，不做左右直达。
        RemoteJump.bindRow(() -> {
            if (rows.getChildCount() > 0) focusListRow();
            else sortButton.requestFocus();
        }, null, dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
                neutralRes != 0 ? dialog.getButton(AlertDialog.BUTTON_NEUTRAL) : null,
                dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        rebuild();
        // 打开即把焦点给第一行，遥控器可直接上下选；没有子目录时给「排序」按钮，焦点不落空
        if (firstRow != null) firstRow.requestFocus();
        else sortButton.requestFocus();
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

    /** 子目录：按「隐藏系统文件」开关过滤、按当前排序方式排好（FileBrowse 统一规则）。 */
    private List<File> subDirs(File dir) {
        return FileBrowse.listDirs(dir);
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
            // 第一行按上键：焦点跳到上方「排序」按钮
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && rows.getChildCount() > 0 && rows.getChildAt(0) == v) {
                sortButton.requestFocus();
                return true;
            }
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
