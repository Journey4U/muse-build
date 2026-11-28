package com.muse.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivityFileBinding;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.adapter.FileAdapter;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.custom.RemoteJump;
import com.muse.android.tv.utils.FileBrowse;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    private ActivityFileBinding mBinding;
    private FileAdapter mAdapter;
    private File dir;
    private boolean folderMode;
    private boolean mOkLongJumped;

    private boolean isRoot() {
        return Path.root().equals(dir);
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
        if (folderMode) {
            mBinding.pickFolder.setVisibility(View.VISIBLE);
            mBinding.pickFolder.setOnClickListener(v -> {
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
        checkPermission();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    private void checkPermission() {
        PermissionUtil.ensureStoragePermission(this, () -> update(Path.root()), this::finish);
    }

    private void update(File dir) {
        mBinding.recycler.setSelectedPosition(0);
        mAdapter.addAll(FileBrowse.list(this.dir = dir));
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
        if (file.isDirectory()) {
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
        if (isRoot()) {
            super.onBackInvoked();
        } else {
            update(dir.getParentFile());
        }
    }
}
