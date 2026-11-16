package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.utils.Prefers;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.DialogSiteBinding;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.adapter.SiteAdapter;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.muse.android.tv.utils.MoveHistory;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener, SiteAdapter.OpsListener {

    private static final int GRID_COUNT = 10;
    private static final String FAIL_DIR_NAME = "失效列表";

    private RecyclerView.ItemDecoration decoration;
    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private boolean action;
    private boolean keyword;
    private boolean ops;
    private int type;

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        type = 1;
        return this;
    }

    public SiteDialog keyword() {
        keyword = true;
        return this;
    }

    public SiteDialog action() {
        action = true;
        return this;
    }

    public static final String TAG = "site_dialog";

    public void show(FragmentActivity activity) {
        // 防重入：已有同名弹窗在场时不再叠弹第二个（双入口/连点各调一次会叠出两层窗）。
        activity.getSupportFragmentManager().executePendingTransactions();
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        show(activity.getSupportFragmentManager(), TAG);
        if (activity instanceof SiteListener) listener = (SiteListener) activity;
    }

    private boolean list() {
        // Fresh installs have no stored site_mode: default to the grid
        // (the stored pref default 0 would otherwise pin the dialog to a
        // single column forever). Only an explicit user choice counts.
        boolean modeStored = Prefers.getPrefers().contains("site_mode");
        return (modeStored && Setting.getSiteMode() == 0) || adapter.getItemCount() < GRID_COUNT;
    }

    private float getScreenWidthDp() {
        android.util.DisplayMetrics metrics = requireContext().getResources().getDisplayMetrics();
        return metrics.widthPixels / metrics.density;
    }

    private int getCount() {
        if (list()) return 1;
        float avail = getScreenWidthDp() * 0.94f - 48 - (action ? 56 : 0);
        return Math.clamp((int) ((avail + 16) / 216), 2, 5);
    }

    private int getIcon() {
        return list() ? R.drawable.ic_site_grid : R.drawable.ic_site_list;
    }

    private float getWidth() {
        int count = getCount();
        float dialog = count * 200f + (count - 1) * 16 + 48 + (action ? 56 : 0);
        return Math.clamp(dialog / getScreenWidthDp(), 0.4f, 0.94f);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // 宽度在显示前一次定死：旧写法在 onStart（窗已显示）才改宽，整窗重排一次，
        // 老设备上看上去像多弹一次窗、站点越多越卡。onMode 切换时的改宽是用户主动操作，不走这里。
        if (adapter != null && adapter.getItemCount() > 0 && dialog.getWindow() != null) {
            WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = (int) (ResUtil.getScreenWidth() * getWidth());
            dialog.getWindow().setAttributes(params);
        }
        return dialog;
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this);
        adapter.setOpsListener(this);
        if (action) binding.action.setVisibility(View.VISIBLE);
        setType(type);
        setRecyclerView();
        setMode();
        binding.keywordLayout.setVisibility(keyword ? View.VISIBLE : View.GONE);
        if (keyword) {
            binding.keyword.addTextChangedListener(new TextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    adapter.filter(editable.toString().trim());
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }
            });
        }
    }

    @Override
    protected void initEvent() {
        binding.mode.setOnClickListener(this::onMode);
        binding.select.setOnClickListener(v -> adapter.selectAll());
        binding.cancel.setOnClickListener(v -> adapter.cancelAll());
        binding.search.setOnClickListener(v -> setType(v.isSelected() ? 0 : 1));
        binding.change.setOnClickListener(v -> setType(v.isSelected() ? 0 : 2));
        binding.selectInvert.setOnClickListener(v -> adapter.invertOps());
        binding.selectExit.setOnClickListener(v -> exitOps());
        binding.selectConfirm.setOnClickListener(v -> onActOnSelection());
    }

    private void setRecyclerView() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        if (decoration != null) binding.recycler.removeItemDecoration(decoration);
        binding.recycler.addItemDecoration(decoration = new SpaceItemDecoration(getCount(), 16));
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), getCount()));
        if (!binding.mode.hasFocus()) binding.recycler.scrollToPosition(VodConfig.getHomeIndex());
    }

    private void setType(int type) {
        if (ops) exitOps();
        binding.search.setSelected(type == 1);
        binding.change.setSelected(type == 2);
        binding.select.setClickable(type > 0);
        binding.cancel.setClickable(type > 0);
        adapter.setType(this.type = type);
    }

    private void setMode() {
        if (adapter.getItemCount() < GRID_COUNT) Setting.putSiteMode(0);
        binding.mode.setEnabled(adapter.getItemCount() >= GRID_COUNT);
        binding.mode.setImageResource(getIcon());
    }

    private void setWidth() {
        setWidth(getWidth());
    }

    private void onMode(View view) {
        Setting.putSiteMode(Math.abs(Setting.getSiteMode() - 1));
        setRecyclerView();
        setMode();
        setWidth();
    }

    @Override
    public void onItemClick(Site item) {
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) {
            dismiss();
            return;
        }
        // 初始焦点给列表：不让搜索框在打开时抢焦点（老设备上会多出一步亮边/光标，还可能带起输入法）。
        binding.recycler.requestFocus();
        // 勾选模式下返回键先退出勾选模式，不直接关弹窗（遥控器层级）。
        if (getDialog() != null) getDialog().setOnKeyListener((dialog, keyCode, event) -> {
            if (ops && keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                exitOps();
                return true;
            }
            return false;
        });
    }

    // ==================== 本地源操作（勾选模式，长按 OK 进入） ====================

    @Override
    public void onItemLongClick(Site item) {
        if (item == null || ops) return;
        if (LocalSourceHub.isSelfAuthored(item.getApi(), item.getExt(), null)) {
            showSelfAuthoredHint(getString(R.string.site_self_authored, item.getName()));
            return;
        }
        // 只对本地源文件生效；远程源长按不进勾选模式（与手机版同一口径）。
        if (LocalSourceHub.sourceFilePathOf(item.getApi(), item.getExt(), null) == null) return;
        ops = true;
        binding.selectBar.setVisibility(View.VISIBLE);
        adapter.enterOps(item);
    }

    @Override
    public void onOpsChanged() {
        updateSelectionUi();
    }

    private void exitOps() {
        if (!ops) return;
        ops = false;
        binding.selectBar.setVisibility(View.GONE);
        adapter.exitOps();
        // 回到进入前的类型（搜索/换源标记模式）
        adapter.setType(type);
        binding.recycler.requestFocus();
    }

    private void updateSelectionUi() {
        if (binding == null || adapter == null) return;
        int count = adapter.getCheckedSites().size();
        binding.selectCount.setText(getString(R.string.site_select_hint, count));
        binding.selectConfirm.setEnabled(count > 0);
        binding.selectConfirm.setAlpha(count > 0 ? 1f : 0.4f);
    }

    /** 自写源提示浮层（与手机版同实现：宽度随内容自适应、上限屏宽 86%、2 秒自动消失）。 */
    private void showSelfAuthoredHint(String message) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        TextView view = new TextView(activity);
        view.setText(message);
        view.setTextSize(14f);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        view.setMaxWidth(Math.round(metrics.widthPixels * 0.86f));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity, R.style.TaixuAlertDialogTheme)
            .setView(view)
            .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }, 2000);
    }

    /**
     * 「选定当前目标」→ 对勾选的源做什么（电视版 6 项：分享在电视上无使用场景，按遥控器适配原则不列）。
     * 排序与手机版一致：先改源自身，再动位置/分类，最后才是不可恢复的删除。
     */
    private void onActOnSelection() {
        List<Site> targets = adapter.getCheckedSites();
        if (targets.isEmpty()) {
            Notify.show(R.string.site_select_empty);
            return;
        }
        Context context = getContext();
        if (context == null) return;
        String[] actions = new String[]{
            getString(R.string.site_action_rename_source),
            getString(R.string.site_action_move),
            getString(R.string.site_action_new_category),
            getString(R.string.site_action_restore),
            getString(R.string.site_action_rename_folder),
            getString(R.string.site_action_delete)};
        new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_target_title)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) renameSourceFlow(targets);
                else if (which == 1) chooseMoveTargetForSelection(targets);
                else if (which == 2) createCategoryFlow();
                else if (which == 3) restoreSelected(targets);
                else if (which == 4) renameFolderFlow();
                else confirmDeleteSelected(targets);
            })
            .setNegativeButton(R.string.dialog_negative, null)
            .show();
    }

    // ==================== 重命名（源文件 / 分类文件夹） ====================

    /** 重命名源文件：一次只改一个文件名，多选时取第一个并明确提示。 */
    private void renameSourceFlow(List<Site> targets) {
        Context context = getContext();
        if (context == null || targets == null || targets.isEmpty()) return;
        if (targets.size() > 1) Notify.show(R.string.site_rename_single_only);
        Site site = targets.get(0);
        File file = LocalSourceHub.siteFile(site.getApi(), site.getExt(), null);
        if (file == null) {
            Notify.show(R.string.site_rename_no_file);
            return;
        }
        String oldName = file.getName();
        int dot = oldName.lastIndexOf('.');
        String baseName = dot > 0 ? oldName.substring(0, dot) : oldName;
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.site_rename_hint);
        input.setText(baseName);
        input.setSelection(baseName.length());
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_rename_title)
            .setView(input)
            .setNegativeButton(R.string.dialog_negative, null)
            .setPositiveButton(R.string.dialog_positive, null)
            .create();
        dialog.show();
        // 强校验：空名 / 非法字符原地提示，不关弹窗
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String name = LocalSourceHub.sanitizeName(raw);
            if (name == null) {
                Notify.show(isBlank(raw) ? R.string.site_rename_empty : R.string.site_rename_invalid);
                return;
            }
            dialog.dismiss();
            doRenameSource(site, name);
        });
    }

    private void doRenameSource(Site site, String newName) {
        new Thread(() -> {
            final int result = LocalSourceHub.renameSiteFile(site.getApi(), site.getExt(), null, newName);
            final String path = LocalSourceHub.movedPath();
            post(() -> {
                if (!isAdded()) return;
                if (result == 1) {
                    Notify.show(getString(R.string.site_rename_done, path == null ? newName : new File(path).getName()));
                    reloadAfterMove();
                } else if (result == -1) Notify.show(R.string.site_rename_exists);
                else if (result == -3) Notify.show(R.string.site_rename_invalid);
                else if (result == -4) Notify.show(getString(R.string.site_self_authored, site.getName()));
                else if (result == 0) Notify.show(R.string.site_rename_no_file);
                else Notify.show(R.string.site_rename_fail);
            });
        }, "local-source-rename").start();
    }

    /** 重命名分类文件夹：先在目录树里选中一层，再输入新名。 */
    private void renameFolderFlow() {
        Context context = getContext();
        if (context == null) return;
        FolderTreeDialog.show(context, this::promptRenameFolder);
    }

    private void promptRenameFolder(File dir) {
        Context context = getContext();
        if (context == null || dir == null) return;
        String oldName = dir.getName();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.site_rename_hint);
        input.setText(oldName);
        input.setSelection(oldName.length());
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_rename_folder_title)
            .setView(input)
            .setNegativeButton(R.string.dialog_negative, null)
            .setPositiveButton(R.string.dialog_positive, null)
            .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String name = LocalSourceHub.sanitizeName(raw);
            if (name == null) {
                Notify.show(isBlank(raw) ? R.string.site_rename_empty : R.string.site_rename_invalid);
                return;
            }
            dialog.dismiss();
            if (name.equals(oldName)) return;
            doRenameFolder(dir, name);
        });
    }

    private void doRenameFolder(File dir, String newName) {
        new Thread(() -> {
            final int result = LocalSourceHub.renameFolder(dir.getAbsolutePath(), newName);
            post(() -> {
                if (!isAdded()) return;
                if (result == 1) {
                    Notify.show(getString(R.string.site_rename_done, newName));
                    reloadAfterMove();
                } else if (result == -1) Notify.show(R.string.site_rename_exists);
                else if (result == -5) Notify.show(R.string.site_folder_root_forbidden);
                else if (result == -3) Notify.show(R.string.site_rename_invalid);
                else Notify.show(R.string.site_rename_fail);
            });
        }, "local-source-rename-folder").start();
    }

    // ==================== 增加 / 删除分类（树状图选择） ====================

    /** 增加 / 删除分类：先用目录树选中目标文件夹，「增加」= 在它下面新建分类；「删除」= 删掉这个分类文件夹。 */
    private void createCategoryFlow() {
        Context context = getContext();
        if (context == null) return;
        FolderTreeDialog.showPicker(context, R.string.site_action_new_category, R.string.site_category_pick_tip,
            R.string.site_category_add, R.string.site_category_delete, (dir, neutral) -> {
                if (dir == null) return;
                if (neutral) confirmDeleteCategory(dir);
                else promptCategoryName(dir);
            });
    }

    /** 删除分类文件夹前的二次确认：先在后台数清里面有几个文件，文案说清再让用户决定。 */
    private void confirmDeleteCategory(File dir) {
        if (dir == null) return;
        Context context = getContext();
        if (context == null) return;
        final String path = dir.getAbsolutePath();
        final String name = dir.getName();
        new Thread(() -> {
            final int files = LocalSourceHub.countFolderFiles(path);
            post(() -> {
                if (!isAdded()) return;
                Context ctx = getContext();
                if (ctx == null) return;
                new MaterialAlertDialogBuilder(ctx, R.style.TaixuAlertDialogTheme)
                    .setTitle(R.string.site_category_del_title)
                    .setMessage(files > 0
                        ? getString(R.string.site_category_del_msg, name, files)
                        : getString(R.string.site_category_del_empty, name))
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.site_del_confirm, (dialog, which) -> doDeleteCategory(dir))
                    .show();
            });
        }, "local-source-category-count").start();
    }

    private void doDeleteCategory(File dir) {
        if (dir == null) return;
        final String path = dir.getAbsolutePath();
        final String name = dir.getName();
        new Thread(() -> {
            final int result = LocalSourceHub.deleteFolder(path);
            post(() -> {
                if (!isAdded()) return;
                if (result >= 0) {
                    Notify.show(getString(R.string.site_category_del_done, name));
                    reloadAfterMove();
                } else if (result == -5) {
                    Notify.show(R.string.site_category_del_forbidden);
                } else {
                    Notify.show(R.string.site_category_del_fail);
                }
            });
        }, "local-source-category-delete").start();
    }

    /** 强校验分类名：非空 + 不含路径分隔符等非法字符。 */
    private String validCategoryName(String input) {
        if (isBlank(input)) return null;
        String name = input.trim();
        if (name.isEmpty() || name.startsWith(".")) return null;
        if (name.matches(".*[\\\\/:*?\"<>|].*")) return null;
        return name;
    }

    private void promptCategoryName(File parent) {
        Context context = getContext();
        if (context == null || parent == null) return;
        EditText input = new EditText(context);
        input.setHint(R.string.site_new_category_name);
        input.setSingleLine(true);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_new_category_title)
            .setView(input)
            .setNegativeButton(R.string.dialog_negative, null)
            .setPositiveButton(R.string.site_new_category_create, null)
            .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String name = validCategoryName(raw);
            if (name == null) {
                Notify.show(isBlank(raw) ? R.string.site_new_category_empty : R.string.site_new_category_invalid);
                return;
            }
            File target = new File(parent, name);
            if (!target.isDirectory() && !target.mkdirs()) {
                Notify.show(R.string.site_new_category_fail);
                return;
            }
            dialog.dismiss();
            Notify.show(getString(R.string.site_new_category_done, target.getAbsolutePath()));
            exitOps();
            reloadAfterMove();
        });
    }

    // ==================== 批量移动 / 回退 / 删除 ====================

    /** 批量移动：目录树里点到哪一层就搬到哪一层（走 moveSiteToDir 绝对目录版）。 */
    private void chooseMoveTargetForSelection(List<Site> targets) {
        Context context = getContext();
        if (context == null || targets.isEmpty()) return;
        FolderTreeDialog.showPicker(context, R.string.site_action_move, R.string.site_move_pick_tip,
            R.string.site_move_here, 0, (dir, neutral) -> {
                if (dir != null) moveSelectedToDir(targets, dir.getAbsolutePath());
            });
    }

    private void moveSelectedToDir(List<Site> targets, String targetDir) {
        if (TextUtils.isEmpty(targetDir)) return;
        final List<Site> snapshot = new ArrayList<>(targets);
        final String dirPath = targetDir;
        exitOps();
        new Thread(() -> {
            int moved = 0;
            String selfName = null;
            try {
                LocalSourceHub.beginBatch();
                for (Site site : snapshot) {
                    String prevDir = LocalSourceHub.categoryDirOf(site.getApi(), site.getExt(), null);
                    int result = LocalSourceHub.moveSiteToDir(site.getApi(), site.getExt(), null, dirPath);
                    if (result == 1) {
                        moved++;
                        // 记下「搬家前所在分类」，供「回退到上一次分类」用
                        MoveHistory.record(LocalSourceHub.movedPath(), prevDir);
                    } else if (result == LocalSourceHub.BLOCK_SELF_AUTHORED) {
                        selfName = site.getName();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                LocalSourceHub.endBatch();
            }
            final int movedCount = moved;
            final String blockedName = selfName;
            post(() -> {
                if (!isAdded()) return;
                if (blockedName != null) showSelfAuthoredHint(getString(R.string.site_self_authored, blockedName));
                if (movedCount == 0) {
                    Notify.show(R.string.site_move_fail);
                    return;
                }
                Notify.show(getString(R.string.site_select_moved, movedCount));
                reloadAfterMove();
            });
        }, "local-source-move-tree").start();
    }

    /** 批量「回退到上一次分类」：记录在 MoveHistory（每次搬家成功时写入）。 */
    private void restoreSelected(List<Site> targets) {
        final List<Site> snapshot = new ArrayList<>(targets);
        exitOps();
        new Thread(() -> {
            int restored = 0;
            try {
                LocalSourceHub.beginBatch();
                for (Site site : snapshot) {
                    String currentDir = LocalSourceHub.categoryDirOf(site.getApi(), site.getExt(), null);
                    String prev = prevCategoryOf(site, currentDir);
                    if (prev == null) continue;
                    int result = LocalSourceHub.moveSiteToDir(site.getApi(), site.getExt(), null, prev);
                    if (result > 0) {
                        restored++;
                        MoveHistory.record(LocalSourceHub.movedPath(), currentDir);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                LocalSourceHub.endBatch();
            }
            final int count = restored;
            post(() -> {
                if (!isAdded()) return;
                if (count == 0) {
                    Notify.show(R.string.site_restore_none);
                    return;
                }
                Notify.show(getString(R.string.site_restore_done, count));
                reloadAfterMove();
            });
        }, "local-source-restore").start();
    }

    /** 某个源「上一次所在的分类目录」绝对路径；拿不到返回 null。 */
    private String prevCategoryOf(Site site, String currentDir) {
        String path = LocalSourceHub.sourceFilePathOf(site.getApi(), site.getExt(), null);
        String prev = path == null ? null : MoveHistory.prevOf(path);
        if (prev != null) {
            String outside = outsideFailDirs(prev);
            if (outside != null) return outside;
        }
        if (currentDir == null) return null;
        return outsideFailDirs(currentDir);
    }

    /** 从 dir 往上找到第一个名字不是「失效列表」的目录。 */
    private static String outsideFailDirs(String dir) {
        if (isBlank(dir)) return null;
        File file = new File(dir);
        while (file != null && FAIL_DIR_NAME.equals(file.getName())) file = file.getParentFile();
        return file == null ? null : file.getAbsolutePath();
    }

    /** 删除源文件前的二次确认（批量删除真删磁盘文件并回写 json，不可恢复）。 */
    private void confirmDeleteSelected(List<Site> targets) {
        if (targets == null || targets.isEmpty()) {
            Notify.show(R.string.site_select_empty);
            return;
        }
        Context context = getContext();
        if (context == null) return;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < targets.size() && i < 8; i++) {
            if (names.length() > 0) names.append('\n');
            names.append("· ").append(targets.get(i).getName());
        }
        if (targets.size() > 8) names.append("\n· …共 ").append(targets.size()).append(" 项");
        new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_del_title)
            .setMessage(getString(R.string.site_del_multi_message, targets.size()) + "\n\n" + names)
            .setNegativeButton(R.string.dialog_negative, null)
            .setPositiveButton(R.string.site_del_confirm, (dialog, which) -> deleteSelected(targets))
            .show();
    }

    /** 批量删除源文件：删完同步回写并重载配置，条目立刻从列表消失。 */
    private void deleteSelected(List<Site> targets) {
        final List<Site> snapshot = new ArrayList<>(targets);
        exitOps();
        new Thread(() -> {
            int removed = 0;
            String selfName = null;
            try {
                LocalSourceHub.beginBatch();
                for (Site site : snapshot) {
                    int result = LocalSourceHub.deleteSiteFile(site.getApi(), site.getExt(), null);
                    if (result > 0) removed++;
                    else if (result == LocalSourceHub.BLOCK_SELF_AUTHORED) selfName = site.getName();
                }
            } catch (Throwable ignored) {
            } finally {
                LocalSourceHub.endBatch();
            }
            final int removedCount = removed;
            final String blockedName = selfName;
            post(() -> {
                if (!isAdded()) return;
                if (blockedName != null) showSelfAuthoredHint(getString(R.string.site_self_authored, blockedName));
                if (removedCount == 0) {
                    Notify.show(R.string.site_del_fail);
                    return;
                }
                Notify.show(getString(R.string.site_del_done_multi, removedCount));
                reloadAfterMove();
            });
        }, "local-source-delete").start();
    }

    /** 搬家/删除/改名后立刻生效：重载点播配置 → 重建列表（勾选态随 refresh 清空）。 */
    private void reloadAfterMove() {
        VodConfig.get().init().load(new Callback() {
            @Override
            public void start() {
            }

            @Override
            public void success() {
                if (!isAdded()) return;
                if (ops) exitOps();
                adapter.refresh();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                if (isAdded()) adapter.refresh();
            }
        });
    }

    private void post(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
