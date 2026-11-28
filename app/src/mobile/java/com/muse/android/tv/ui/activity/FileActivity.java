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
import com.muse.android.tv.utils.FileBrowse;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.PermissionUtil;
import com.github.catvod.utils.Path;

import java.io.File;

public class FileActivity extends BaseActivity implements FileAdapter.OnClickListener {

    /** 文件夹选择模式：只在本地源扫描目录入口用，普通文件选择（源配置等）不受影响。 */
    public static final String EXTRA_PICK_FOLDER = "pick_folder";

    private static final int[] SORT_IDS = {R.id.file_sort_name_asc, R.id.file_sort_name_desc, R.id.file_sort_time_new, R.id.file_sort_time_old};

    private ActivityFileBinding mBinding;
    private FileAdapter mAdapter;
    private File dir;

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
        setRecyclerView();
        // 本地源选目录（EXTRA_PICK_FOLDER）与书架导入（FileChooser.EXTRA_FOLDER_MODE）统一用底部大按钮选当前文件夹
        boolean pickFolder = getIntent().getBooleanExtra(EXTRA_PICK_FOLDER, false) || getIntent().getBooleanExtra(FileChooser.EXTRA_FOLDER_MODE, false);
        if (pickFolder) {
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
        mAdapter.addAll(FileBrowse.list(this.dir = dir));
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
        getMenuInflater().inflate(R.menu.menu_file, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem sort = menu.findItem(SORT_IDS[FileBrowse.getSort()]);
        if (sort != null) sort.setChecked(true);
        MenuItem hide = menu.findItem(R.id.file_hide_dot);
        if (hide != null) hide.setChecked(FileBrowse.isHideDot());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackInvoked();
            return true;
        }
        for (int i = 0; i < SORT_IDS.length; i++) {
            if (id == SORT_IDS[i]) {
                FileBrowse.putSort(i);
                item.setChecked(true);
                if (dir != null) update(dir);
                return true;
            }
        }
        if (id == R.id.file_hide_dot) {
            FileBrowse.putHideDot(!FileBrowse.isHideDot());
            item.setChecked(FileBrowse.isHideDot());
            if (dir != null) update(dir);
            return true;
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
