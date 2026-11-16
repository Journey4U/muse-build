package com.muse.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.databinding.ActivityFileBinding;
import com.muse.android.tv.ui.adapter.FileAdapter;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.utils.Path;

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
        checkPermission();
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
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    private void checkPermission() {
        PermissionUtil.requestFile(this, granted -> { if (granted) update(Path.root()); else finish(); });
    }

    private void update(File dir) {
        mBinding.recycler.setSelectedPosition(0);
        mAdapter.addAll(Path.list(this.dir = dir));
        mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
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

    // 文件夹模式：文件列表里长按 OK 直接跳焦点到「选择当前文件夹」按钮，
    // 免去逐行按到底。短按照常进目录/选文件；跳过已消费的重复 DOWN 与收尾 UP，
    // 防止长按抬手时误点当前条目或按钮。
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (folderMode && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                if (!mOkLongJumped) {
                    mOkLongJumped = true;
                    mBinding.pickFolder.requestFocus();
                }
                return true;
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
        if (isRoot()) {
            super.onBackInvoked();
        } else {
            update(dir.getParentFile());
        }
    }
}
