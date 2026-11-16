package com.muse.android.tv.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivityFileBinding;
import com.muse.android.tv.ui.adapter.FileAdapter;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.PermissionUtil;
import com.github.catvod.utils.Path;

import java.io.File;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    /** 文件夹选择模式：只在本地源扫描目录入口用，普通文件选择（源配置等）不受影响。 */
    public static final String EXTRA_PICK_FOLDER = "pick_folder";

    private ActivityFileBinding mBinding;
    private FileAdapter mAdapter;
    private File dir;
    private boolean folderMode;

    private boolean isRoot() {
        return Path.root().equals(dir);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFileBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle("");
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        folderMode = getIntent().getBooleanExtra(FileChooser.EXTRA_FOLDER_MODE, false);
        setRecyclerView();
        if (getIntent().getBooleanExtra(EXTRA_PICK_FOLDER, false)) {
            mBinding.pickFolder.setVisibility(View.VISIBLE);
            mBinding.pickFolder.setOnClickListener(v -> {
                if (dir != null) {
                    setResult(RESULT_OK, new Intent().setData(Uri.fromFile(dir)));
                    finish();
                }
            });
        }
        checkPermission();
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setAdapter(mAdapter = new FileAdapter(this));
    }

    private void checkPermission() {
        PermissionUtil.ensureStoragePermission(this, () -> update(Path.root()), this::finish);
    }

    private void update(File dir) {
        mBinding.recycler.scrollToPosition(0);
        mAdapter.addAll(Path.list(this.dir = dir));
        mBinding.title.setText(dir.getAbsolutePath());
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (folderMode) getMenuInflater().inflate(R.menu.menu_file_folder, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.choose_folder && dir != null) {
            setResult(RESULT_OK, new Intent().setData(Uri.fromFile(dir)));
            finish();
        }
        return super.onOptionsItemSelected(item);
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
