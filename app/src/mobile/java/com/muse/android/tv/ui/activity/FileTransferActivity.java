package com.muse.android.tv.ui.activity;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.muse.android.tv.R;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.databinding.ActivityFileTransferBinding;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 转存 Activity —— 干净自写实现，不复刻 1.0.3 的反编译。
 *
 * <p>本质：VesperTV 与 MuseTV 同源（FongMi 基底），这里只按 MuseTV 自身基底写：
 * 目标目录浏览（只列子目录）、当前层新建文件夹、文件名可编辑、改扩展名实时重算提示、
 * 保存前走 MuseTV 的 WRITE 运行时权限、写盘用同目录临时文件 + rename（跨挂载点失败时
 * 退化为拷贝 + 删临时文件），绝不直接往目标文件直写。Hub 的 {@code write()} 本身是
 * 直接写盘且不对外暴露原子写接口，所以原子写由本 Activity 自己做（见 {@link #copyAtomically}）。
 *
 * <p>入口有两条，且只有这两条，不另开第三条路：
 * <ul>
 *   <li>外部分享 / 打开方式：manifest 里 SEND（文本类 mime）与 VIEW（file/content +
 *       扩展名 pathPattern）两组 filter，入口名就是「转存」，不带任何旧品牌字样；</li>
 *   <li>应用内：本地源对话框里的「转存文件」按钮，无源文件时进本页，页内「选择源文件」
 *       按钮走系统文件选择器（ACTION_OPEN_DOCUMENT），拿到 Uri 后再走同一套保存流程。</li>
 * </ul>
 *
 * <p>范围与取舍（写进代码里，验收时以此为准）：
 * <ul>
 *   <li>转存扩展名组（1.0.3 日志 r100 的那组）：py / js / json / txt / m3u / m3u8 /
 *       conf / php。改扩展名后不在组内时保存按钮禁用、提示写明允许的组，不静默改写；</li>
 *   <li>转存目标列表是导航不是勾选：点子目录 = 进入该目录，保存按钮永远保存到当前
 *       显示的目录，不受搜索框筛选影响（搜索只过滤当前层的显示行）；列表定高 284dp
 *       来自 dialog_source_transfer.xml，不顶出底部操作栏；</li>
 *   <li>r104 规则：转存到非扫描目录只提示（底部提示 + 保存后 Toast 追加），绝不自动
 *       把目标目录加入扫描目录；是否出现在源列表，取决于目标是否在扫描范围内；</li>
 *   <li>保存后若目标在扫描范围内，后台跑一次 {@link LocalSourceHub#generate()} 重建
 *       产物，让新文件出现在源列表；不在范围内则不重建、不改任何配置；</li>
 *   <li>同名文件已存在时先弹覆盖确认，不静默覆盖；文件名里的 / \ 会被去掉，空名不保存。</li>
 * </ul>
 */
public class FileTransferActivity extends BaseActivity {

    /** 转存入口接受的扩展名（1.0.3 日志 r100 组，MuseTV 转存沿用这组）。 */
    private static final String[] TRANSFER_EXTENSIONS = {"py", "js", "json", "txt", "m3u", "m3u8", "conf", "php"};

    private ActivityFileTransferBinding mBinding;
    private DirAdapter mAdapter;

    private Uri sourceUri;
    private String sourceName = "";
    private long sourceSize = -1;
    private File currentDir;
    private File rootDir;
    private final List<File> allSubDirs = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean saving = false;

    private final ActivityResultLauncher<String[]> pickSourceLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(uri, flags);
                } catch (Throwable ignored) {
                }
                setSource(uri);
            });

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityFileTransferBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setTitle(R.string.transfer_title);
        mBinding.transferPanel.transferList.setLayoutManager(new LinearLayoutManager(this));
        mBinding.transferPanel.transferList.setAdapter(mAdapter = new DirAdapter());
        mBinding.transferPanel.transferUp.setOnClickListener(v -> goUp());
        mBinding.transferPanel.transferSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterDirs(s == null ? "" : s.toString());
            }
        });
        mBinding.transferPanel.transferName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshHint();
            }
        });
        mBinding.transferPick.setOnClickListener(v -> pickSourceLauncher.launch(new String[]{"*/*"}));
        mBinding.transferCreateDir.setOnClickListener(v -> createNewDir());
        mBinding.transferSave.setOnClickListener(v -> onSaveClicked());
        mBinding.transferCancel.setOnClickListener(v -> finish());
        applyThemeColors();
        resolveSource(getIntent());
        initDir();
        refreshDirs();
        refreshHint();
    }

    // ---------------- 源文件 ----------------

    private void resolveSource(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) setSource(uri);
    }

    private void setSource(Uri uri) {
        sourceUri = uri;
        sourceName = displayName(uri);
        sourceSize = sizeOf(uri);
        mBinding.transferPick.setVisibility(View.GONE);
        mBinding.transferPanel.transferName.setText(sourceName);
        mBinding.transferPanel.transferPath.setText(sourceName);
        mBinding.transferPanel.transferSize.setText(formatSize(sourceSize));
        refreshHint();
    }

    private String displayName(Uri uri) {
        if (uri == null) return "";
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (!TextUtils.isEmpty(path)) return new File(path).getName();
        }
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) return name;
            }
        } catch (Throwable ignored) {
        }
        String segment = uri.getLastPathSegment();
        return TextUtils.isEmpty(segment) ? "" : segment;
    }

    private long sizeOf(Uri uri) {
        if (uri == null) return -1;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).length();
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String formatSize(long size) {
        if (size < 0) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }

    // ---------------- 目录浏览 ----------------

    private void initDir() {
        File root = null;
        try {
            root = Path.root();
        } catch (Throwable ignored) {
        }
        if (root == null) root = new File("/sdcard");
        rootDir = root;
        File start = null;
        for (String dir : LocalSourceHub.getScanDirs()) {
            if (!LocalSourceHub.isDirEnabled(dir)) continue;
            File candidate = new File(dir);
            if (candidate.isDirectory()) {
                start = candidate;
                break;
            }
        }
        if (start == null) {
            File out = LocalSourceHub.outputFile();
            if (out != null && out.getParentFile() != null && out.getParentFile().isDirectory()) start = out.getParentFile();
        }
        currentDir = start != null ? start : rootDir;
    }

    private void refreshDirs() {
        allSubDirs.clear();
        File[] children = currentDir == null ? null : currentDir.listFiles(File::isDirectory);
        if (children != null) {
            Collections.addAll(allSubDirs, children);
            Collections.sort(allSubDirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        mBinding.transferPanel.transferPathBox.setText(currentDir == null ? "" : currentDir.getAbsolutePath());
        filterDirs(mBinding.transferPanel.transferSearch.getText() == null ? "" : mBinding.transferPanel.transferSearch.getText().toString());
    }

    private void filterDirs(String query) {
        List<File> shown = new ArrayList<>();
        String key = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (File dir : allSubDirs) {
            if (key.isEmpty() || dir.getName().toLowerCase(Locale.US).contains(key)) shown.add(dir);
        }
        mAdapter.setItems(shown);
        mBinding.transferPanel.transferEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        refreshHint();
    }

    private void goUp() {
        if (currentDir == null) return;
        File parent = currentDir.getParentFile();
        if (parent == null || !parent.isDirectory()) return;
        // 不越过外部存储根往上走。
        if (!isAtOrUnderRoot(parent) && !parent.equals(rootDir)) return;
        currentDir = parent;
        mBinding.transferPanel.transferSearch.setText("");
        refreshDirs();
    }

    private boolean isAtOrUnderRoot(File dir) {
        try {
            String root = rootDir.getCanonicalPath();
            String path = dir.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + "/");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void createNewDir() {
        String name = mBinding.transferPanel.transferNewDir.getText() == null ? "" : mBinding.transferPanel.transferNewDir.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Notify.show(R.string.transfer_new_dir_empty);
            return;
        }
        String clean = name.replace("/", "").replace("\\", "");
        if (TextUtils.isEmpty(clean)) {
            Notify.show(R.string.transfer_new_dir_empty);
            return;
        }
        File created = new File(currentDir, clean);
        if (created.exists()) {
            Notify.show(R.string.transfer_new_dir_exist);
            return;
        }
        PermissionUtil.requestFile(this, granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                Notify.show(R.string.local_source_permission_required);
                return;
            }
            if (created.mkdir()) {
                mBinding.transferPanel.transferNewDir.setText("");
                refreshDirs();
                Notify.show(getString(R.string.transfer_new_dir_done, created.getAbsolutePath()));
            } else {
                Notify.show(R.string.transfer_new_dir_fail);
            }
        });
    }

    // ---------------- 提示与校验 ----------------

    private static String extensionOf(String name) {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static boolean isTransferExtension(String ext) {
        for (String allowed : TRANSFER_EXTENSIONS) {
            if (allowed.equals(ext)) return true;
        }
        return false;
    }

    private String currentFileName() {
        String raw = mBinding.transferPanel.transferName.getText() == null ? "" : mBinding.transferPanel.transferName.getText().toString().trim();
        return raw.replace("/", "").replace("\\", "");
    }

    private void refreshHint() {
        if (mBinding == null || mBinding.transferPanel == null) return;
        String name = currentFileName();
        String base = getString(R.string.transfer_hint);
        if (sourceUri == null) {
            mBinding.transferPanel.transferHint.setText(getString(R.string.transfer_open_save_to));
            mBinding.transferSave.setEnabled(false);
            return;
        }
        String ext = extensionOf(name);
        if (TextUtils.isEmpty(name)) {
            mBinding.transferPanel.transferHint.setText(getString(R.string.transfer_name_empty));
            mBinding.transferSave.setEnabled(false);
            return;
        }
        if (!isTransferExtension(ext)) {
            String allowed = TextUtils.join("、", TRANSFER_EXTENSIONS);
            mBinding.transferPanel.transferHint.setText(getString(R.string.transfer_unsupported, name, allowed));
            mBinding.transferSave.setEnabled(false);
            return;
        }
        StringBuilder hint = new StringBuilder(base);
        if (currentDir != null && !LocalSourceHub.isInScanRange(currentDir)) {
            hint.append("\n").append(getString(R.string.transfer_saved_out_of_scope));
        }
        mBinding.transferPanel.transferHint.setText(hint.toString());
        mBinding.transferSave.setEnabled(true);
    }

    private void applyThemeColors() {
        // 布局里 transferName / transferNewDir 已走主题属性；这里再按 1.0.3-⑥ 的
        // resolveThemeColor 做一次程序化设色，解析失败时回退到控件当前颜色，不写死色值。
        try {
            int nameColor = LocalSourceHub.resolveThemeColor(this, com.google.android.material.R.attr.colorOnSurface,
                    mBinding.transferPanel.transferName.getCurrentTextColor());
            int hintColor = LocalSourceHub.resolveThemeColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant,
                    mBinding.transferPanel.transferNewDir.getCurrentTextColor());
            mBinding.transferPanel.transferName.setTextColor(nameColor);
            mBinding.transferPanel.transferNewDir.setTextColor(hintColor);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 保存 ----------------

    private void onSaveClicked() {
        if (saving) return;
        if (sourceUri == null) {
            Notify.show(R.string.transfer_no_stream);
            return;
        }
        String name = currentFileName();
        if (TextUtils.isEmpty(name) || !isTransferExtension(extensionOf(name))) {
            refreshHint();
            return;
        }
        File target = new File(currentDir, name);
        if (target.exists()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.transfer_overwrite, name))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> saveWithPermission(target))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            saveWithPermission(target);
        }
    }

    private void saveWithPermission(File target) {
        PermissionUtil.requestFile(this, granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                Notify.show(R.string.local_source_permission_required);
                return;
            }
            saving = true;
            Notify.show(R.string.transfer_importing);
            new Thread(() -> {
                boolean ok = false;
                String error = null;
                try {
                    ok = copyAtomically(target);
                } catch (Throwable e) {
                    error = String.valueOf(e.getMessage());
                }
                boolean inScope = false;
                if (ok) {
                    try {
                        inScope = LocalSourceHub.isInScanRange(currentDir);
                        if (inScope) LocalSourceHub.generate();
                    } catch (Throwable ignored) {
                    }
                }
                boolean saved = ok;
                boolean scoped = inScope;
                String failure = error;
                mainHandler.post(() -> {
                    saving = false;
                    if (!saved) {
                        Notify.show(failure == null ? getString(R.string.transfer_failed) : getString(R.string.transfer_failed) + "\n" + failure);
                        return;
                    }
                    String message = getString(R.string.transfer_saved, target.getAbsolutePath());
                    if (!scoped) message += "\n" + getString(R.string.transfer_saved_out_of_scope);
                    Notify.show(message);
                    finish();
                });
            }, "file-transfer-save").start();
        });
    }

    /**
     * 原子写盘：先写同目录临时文件并 flush，再 rename 到目标；rename 失败（跨挂载点等）
     * 时退化为拷贝临时文件到目标后删临时文件。任何一步失败都删临时文件，不留半成品目标。
     */
    private boolean copyAtomically(File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("cannot create dir " + parent);
        File tmp = new File(parent, target.getName() + ".tmp-" + android.os.Process.myPid());
        try (InputStream in = openSourceStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
            out.flush();
            out.getFD().sync();
        } catch (Throwable e) {
            tmp.delete();
            throw e;
        }
        if (tmp.renameTo(target)) return true;
        try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
            out.flush();
        } finally {
            tmp.delete();
        }
        return target.exists() && target.length() > 0;
    }

    private InputStream openSourceStream() throws Exception {
        InputStream in = getContentResolver().openInputStream(sourceUri);
        if (in == null) throw new IllegalStateException("cannot open source");
        return in;
    }

    @Override
    protected void onBackInvoked() {
        if (currentDir != null && currentDir.getParentFile() != null && isAtOrUnderRoot(currentDir.getParentFile())) {
            goUp();
        } else {
            super.onBackInvoked();
        }
    }

    // ---------------- 目录列表 ----------------

    private class DirAdapter extends RecyclerView.Adapter<DirAdapter.Holder> {

        private final List<File> items = new ArrayList<>();

        void setItems(List<File> dirs) {
            items.clear();
            items.addAll(dirs);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            File dir = items.get(position);
            holder.text.setText(dir.getName());
            holder.itemView.setOnClickListener(v -> {
                currentDir = dir;
                mBinding.transferPanel.transferSearch.setText("");
                refreshDirs();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(android.R.id.text1);
            }
        }
    }
}
