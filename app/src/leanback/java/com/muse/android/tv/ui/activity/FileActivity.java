package com.muse.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivityFileBinding;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.adapter.FileAdapter;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.custom.RemoteJump;
import com.muse.android.tv.utils.FileBrowse;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    private ActivityFileBinding mBinding;
    private FileAdapter mAdapter;
    private File dir;
    private boolean folderMode;
    private boolean mOkLongJumped;
    // 选目录模式（本地源选扫描目录、源列表「移动」选目标，2026-11-28 第二批替换原树形弹窗）：
    // 只列文件夹；roots 第一项是起点（家），其余作为家目录顶部的快捷入口（如 /storage 的 U 盘 / SD 卡）；
    // currentRoot 记当前所在的那个根，返回到根时：在快捷根就回家，在家就退出页面。
    private static final String EXTRA_DIR_ONLY = "muse_dir_only";
    private static final String EXTRA_ROOTS = "muse_roots";
    private static final String EXTRA_PICK_TEXT = "muse_pick_text";
    private boolean dirOnly;
    private final List<File> roots = new ArrayList<>();
    private File currentRoot;

    /** 选目录入口：roots 第一项为起点，其余为起点顶部的快捷入口；pickTextRes 为底部选择按钮文字（0=默认「选择当前文件夹」）。 */
    public static Intent folderPicker(Context context, List<File> roots, int pickTextRes) {
        String[] paths = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) paths[i] = roots.get(i).getAbsolutePath();
        return new Intent(context, FileActivity.class)
                .putExtra(FileChooser.EXTRA_FOLDER_MODE, true)
                .putExtra(EXTRA_DIR_ONLY, true)
                .putExtra(EXTRA_ROOTS, paths)
                .putExtra(EXTRA_PICK_TEXT, pickTextRes);
    }

    /** 取选目录结果：成功且是文件夹才返回，否则 null（取消、返回都是 null）。 */
    @Nullable
    public static File pickedDir(ActivityResult result) {
        if (result == null || result.getResultCode() != RESULT_OK) return null;
        Intent data = result.getData();
        if (data == null || data.getData() == null || data.getData().getPath() == null) return null;
        File dir = new File(data.getData().getPath());
        return dir.isDirectory() ? dir : null;
    }

    private File home() {
        return roots.isEmpty() ? Path.root() : roots.get(0);
    }

    private boolean isRoot() {
        return currentRoot != null && currentRoot.equals(dir);
    }

    private boolean isRootDir(File file) {
        return file != null && roots.contains(file);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFileBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        // 文件夹模式（书架导入专用，FileChooser.showInternalFolder 进入）：
        // 显示「选择当前文件夹」按钮返回当前目录；点 TXT 文件仍按单本返回。
        folderMode = getIntent().getBooleanExtra(FileChooser.EXTRA_FOLDER_MODE, false);
        dirOnly = getIntent().getBooleanExtra(EXTRA_DIR_ONLY, false);
        String[] rootPaths = getIntent().getStringArrayExtra(EXTRA_ROOTS);
        if (rootPaths != null) for (String path : rootPaths) {
            File root = path == null ? null : new File(path);
            if (root != null && root.isDirectory() && !roots.contains(root)) roots.add(root);
        }
        currentRoot = home();
        int pickText = getIntent().getIntExtra(EXTRA_PICK_TEXT, 0);
        if (pickText != 0) mBinding.pickFolder.setText(pickText);
        if (folderMode) {
            mBinding.pickFolder.setVisibility(View.VISIBLE);
            mBinding.pickFolder.setOnClickListener(v -> {
                // 选目录模式下当前所在的根目录本身不可选（与原树形弹窗一致：只能选根下面的文件夹）
                if (dirOnly && isRoot()) {
                    Notify.show(R.string.site_folder_none_selected);
                    return;
                }
                if (dir != null) {
                    setResult(RESULT_OK, new Intent().setData(Uri.fromFile(dir)));
                    finish();
                }
            });
        }
        // 排序 / 隐藏系统文件：所有模式都显示（源配置选文件也用），遥控器左右切换、确认键操作；菜单键弹同一组选项。
        mBinding.fileSort.setOnClickListener(v -> showSortDialog());
        mBinding.fileHide.setOnClickListener(v -> toggleHide());
        // 底部按钮一层：左右只在层内移动、到头停住；上键回列表原选中行（VerticalGridView 记得选中位置）
        RemoteJump.bindRow(() -> {
            if (mAdapter.getItemCount() > 0) mBinding.recycler.requestFocus();
        }, null, mBinding.pickFolder, mBinding.fileSort, mBinding.fileHide);
        setButtonText();
        setRootLabels();
        checkPermission();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    /** 快捷根的显示名：/storage 显示「U 盘 / SD 卡（/storage）」，其余显示完整路径。 */
    private void setRootLabels() {
        Map<String, String> labels = new HashMap<>();
        for (int i = 1; i < roots.size(); i++) {
            String path = roots.get(i).getAbsolutePath();
            labels.put(path, "/storage".equals(path) ? getString(R.string.file_storage_entry, path) : path);
        }
        mAdapter.setLabels(labels);
    }

    private void checkPermission() {
        PermissionUtil.ensureStoragePermission(this, () -> update(home()), this::finish);
    }

    private void update(File dir) {
        mBinding.recycler.setSelectedPosition(0);
        this.dir = dir;
        List<File> items = new ArrayList<>(dirOnly ? FileBrowse.listDirs(dir) : FileBrowse.list(dir));
        // 家目录顶部放其余根的快捷入口（如 U 盘 / SD 卡），触摸点一下、遥控器 OK 都能进
        if (roots.size() > 1 && dir.equals(home())) items.addAll(0, roots.subList(1, roots.size()));
        mAdapter.addAll(items);
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
        // 空目录（或全是隐藏文件）时列表不可见，焦点落到底部按钮上，免得遥控器无处可按
        // 焦点已在底部按钮上（如刚切了隐藏/排序）就留在原按钮，不抢到第一颗
        if (mAdapter.getItemCount() == 0 && !RemoteJump.anyFocused(mBinding.pickFolder, mBinding.fileSort, mBinding.fileHide)) firstButton().requestFocus();
    }

    private View firstButton() {
        return folderMode ? mBinding.pickFolder : mBinding.fileSort;
    }

    private void setButtonText() {
        mBinding.fileSort.setText(ResUtil.getString(R.string.file_sort_label, FileBrowse.getSortName()));
        mBinding.fileHide.setText(ResUtil.getString(R.string.file_hide_label, Setting.getSwitch(FileBrowse.isHideDot())));
    }

    private void refresh() {
        setButtonText();
        if (dir != null) update(dir);
    }

    private void showSortDialog() {
        // 弹出后焦点显式落在当前已选项；OK 选中并关闭、返回取消，关闭后焦点回「排序」按钮
        RemoteJump.focusChoice(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_sort)
                .setSingleChoiceItems(FileBrowse.getSortNames(), FileBrowse.getSort(), (dialog, which) -> {
                    FileBrowse.putSort(which);
                    dialog.dismiss();
                    refresh();
                })
                .show(), FileBrowse.getSort(), mBinding.fileSort);
    }

    private void toggleHide() {
        FileBrowse.putHideDot(!FileBrowse.isHideDot());
        refresh();
    }

    private void showMenuDialog() {
        String[] sorts = FileBrowse.getSortNames();
        String[] items = new String[sorts.length + 1];
        int current = FileBrowse.getSort();
        for (int i = 0; i < sorts.length; i++) items[i] = (i == current ? "● " : "○ ") + sorts[i];
        items[sorts.length] = ResUtil.getString(R.string.file_hide_label, Setting.getSwitch(FileBrowse.isHideDot()));
        RemoteJump.focusChoice(new MaterialAlertDialogBuilder(this)
                .setItems(items, (dialog, which) -> {
                    if (which < sorts.length) FileBrowse.putSort(which);
                    else FileBrowse.putHideDot(!FileBrowse.isHideDot());
                    refresh();
                })
                .show(), current, null);
    }

    @Override
    public void onItemClick(File file) {
        if (dir != null && dir.equals(home()) && isRootDir(file) && !file.equals(home())) {
            currentRoot = file;
            update(file);
        } else if (file.isDirectory()) {
            update(file);
        } else {
            setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
            finish();
        }
    }

    // 文件列表里长按 OK 直接跳焦点到底部按钮（文件夹模式跳「选择当前文件夹」，其余跳「排序」），
    // 免去逐行按到底。短按照常进目录/选文件；跳过已消费的重复 DOWN 与收尾 UP，
    // 防止长按抬手时误点当前条目或按钮。菜单键弹排序/隐藏选项。
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) showMenuDialog();
            return true;
        }
        // 列表里左键→底部最左按钮（导入时为「选择当前文件夹」），右键→最右「隐藏系统文件」；列表单列，左右键原本无用
        if (RemoteJump.isLeftRight(event) && mBinding.recycler.hasFocus() && mAdapter.getItemCount() > 0
                && RemoteJump.jump(event, mBinding.pickFolder, mBinding.fileSort, mBinding.fileHide)) {
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // 长按跳底部只在焦点在列表里时生效；焦点已在底部按钮上时 OK 走按钮正常点击，不被吞掉
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                if (!mOkLongJumped && mBinding.recycler.hasFocus()) {
                    mOkLongJumped = true;
                    firstButton().requestFocus();
                }
                if (mOkLongJumped) return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && mOkLongJumped) {
                mOkLongJumped = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onBackInvoked() {
        // 返回逐层退：焦点在底部按钮层时先回列表原选中行（不退目录、不退页面）；焦点在列表里照原样回上一级/退出
        if (mAdapter.getItemCount() > 0 && RemoteJump.anyFocused(mBinding.pickFolder, mBinding.fileSort, mBinding.fileHide)) {
            mBinding.recycler.requestFocus();
            return;
        }
        if (isRoot() && !currentRoot.equals(home())) {
            // 在快捷根（如 U 盘 / SD 卡）的顶层按返回：回到家目录
            currentRoot = home();
            update(home());
        } else if (isRoot()) {
            super.onBackInvoked();
        } else {
            update(dir.getParentFile());
        }
    }
}
