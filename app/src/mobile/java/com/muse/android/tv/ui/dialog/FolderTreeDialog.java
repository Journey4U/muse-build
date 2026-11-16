package com.muse.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
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
 * r102 / r104：**分类文件夹选择器（可展开目录树）**，界面上是一个「选目录 + 底部动作按钮」的通用弹窗。
 *
 * <p>三个用途共用同一套交互（用户要求「移动目标 / 增加分类 / 删除分类」都跟重命名分类一样用树状图）：</p>
 * <ul>
 *   <li>{@link #show(Context, Listener)} —— 重命名分类文件夹（单动作，r102 老入口，保持兼容）。</li>
 *   <li>{@link #showPicker} —— 多动作：底部一颗主动作 + 可选一颗次动作（移动 / 增加分类 / 删除分类）。</li>
 * </ul>
 *
 * <p>实现要点（为什么不是一次性把整棵树摊开）：</p>
 * <ul>
 *   <li>只列**目录**、不列源文件 —— 源数量可达 1300+，摊开文件必然卡顿；目录通常几十个，可控。</li>
 *   <li>子目录**按需展开**（点击 ▸ 才 {@code listFiles}），避免一次性递归扫描整片存储。</li>
 *   <li>用 {@code ScrollView + LinearLayout} 动态挂行，不使用 RecyclerView：
 *       行数少、无回收复用需求，省掉 Adapter/ViewHolder 与错位的坑。</li>
 * </ul>
 */
public class FolderTreeDialog {

    /** r102：选中目录后的回调（在主线程）。 */
    public interface Listener {
        void onPick(File dir);
    }

    /** r104：多动作回调。{@code neutral=true} 表示点的是底部第二颗动作按钮（如「删除」）。 */
    public interface ActionListener {
        void onAction(File dir, boolean neutral);
    }

    private final Context context;
    private final ActionListener listener;
    private final int titleRes;
    private final int tipRes;
    private final int positiveRes;
    private final int neutralRes;
    private final Set<String> expanded = new HashSet<>();
    private final LinearLayout rows;
    private final TextView tip;
    private final int density;
    private File selected;

    private FolderTreeDialog(Context context, int titleRes, int tipRes, int positiveRes, int neutralRes, ActionListener listener) {
        this.context = context;
        this.listener = listener;
        this.titleRes = titleRes;
        this.tipRes = tipRes;
        this.positiveRes = positiveRes;
        this.neutralRes = neutralRes;
        this.density = Math.round(context.getResources().getDisplayMetrics().density);
        this.rows = new LinearLayout(context);
        this.rows.setOrientation(LinearLayout.VERTICAL);
        this.tip = new TextView(context);
        this.tip.setPadding(dp(14), dp(8), dp(14), dp(4));
        this.tip.setTextSize(12);
        this.tip.setAlpha(0.7f);
        this.tip.setText(tipRes);
    }

    /** r102：重命名分类文件夹用的老入口（标题/提示/按钮沿用「重命名」语义）。 */
    public static void show(Context context, Listener listener) {
        if (context == null) return;
        new FolderTreeDialog(context, R.string.site_folder_pick_title, R.string.site_folder_pick_title,
                R.string.site_action_rename_folder, 0,
                (dir, neutral) -> {
                    if (listener != null) listener.onPick(dir);
                }).showDialog();
    }

    /**
     * r104：通用「选目录 + 动作按钮」入口。
     *
     * @param titleRes    弹窗标题
     * @param tipRes      顶部提示文案
     * @param positiveRes 主动作按钮文案
     * @param neutralRes  次动作按钮文案（传 0 表示只要一颗动作按钮）
     * @param listener    选中目录后点动作按钮的回调（未选目录会原地提示，不会回调）
     */
    public static void showPicker(Context context, int titleRes, int tipRes, int positiveRes, int neutralRes, ActionListener listener) {
        if (context == null) return;
        new FolderTreeDialog(context, titleRes, tipRes, positiveRes, neutralRes, listener).showDialog();
    }

    private void showDialog() {
        ScrollView scroll = new ScrollView(context);
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
        dialog.show();
        // 未选中目录时不允许关闭：原地提示，避免用户以为点了没反应
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> dispatch(dialog, false));
        if (neutralRes != 0) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> dispatch(dialog, true));
        rebuild();
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
        List<String> scans = LocalSourceHub.getScanDirs();
        boolean any = false;
        for (String scan : scans) {
            if (TextUtils.isEmpty(scan)) continue;
            File root = new File(scan);
            if (!root.isDirectory()) continue;
            any = true;
            addChildren(root, 0);
        }
        if (!any) tip.setText(R.string.site_folder_pick_empty);
        else tip.setText(tipRes);
    }

    /** 把 dir 的子目录铺成行；对已展开的子目录递归下钻（depth 决定缩进）。 */
    private void addChildren(File dir, int depth) {
        List<File> subs = subDirs(dir);
        for (File child : subs) {
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
        Collections.sort(out, (a, b) -> {
            String left = a.getName();
            String right = b.getName();
            return left.compareToIgnoreCase(right);
        });
        return out;
    }

    private void addRow(File dir, int depth, boolean hasChild) {
        boolean isExpanded = expanded.contains(dir.getAbsolutePath());
        boolean isSelected = selected != null && selected.getAbsolutePath().equals(dir.getAbsolutePath());

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10) * depth, dp(9), dp(10), dp(9));
        // 选中态：浅色高亮 + 名称加粗（不依赖额外 drawable，避免主题差异）
        if (isSelected) row.setBackgroundColor(0x332196F3);

        TextView arrow = new TextView(context);
        arrow.setText(hasChild ? (isExpanded ? "▾" : "▸") : "");
        arrow.setTextSize(15);
        arrow.setGravity(Gravity.CENTER);
        arrow.setWidth(dp(26));
        if (hasChild) {
            arrow.setOnClickListener(v -> {
                String path = dir.getAbsolutePath();
                if (!expanded.remove(path)) expanded.add(path);
                rebuild();
            });
        }

        TextView name = new TextView(context);
        name.setText(dir.getName());
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        if (isSelected) {
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        row.addView(arrow);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            selected = dir;
            rebuild();
        });
        rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
