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
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    private static final int GRID_COUNT = 10;

    private RecyclerView.ItemDecoration decoration;
    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private boolean action;
    private boolean keyword;
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
        if (action) binding.action.setVisibility(View.VISIBLE);
        setType(type);
        setRecyclerView();
        // 修复已写坏的 site_mode：旧版 setMode 在条目 <10 时自动写 0，钉死单列；清掉一次，之后只认用户手动切换。
        if (!Prefers.getPrefers().contains("site_mode_fixed")) {
            Prefers.remove("site_mode");
            Prefers.put("site_mode_fixed", true);
        }
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
        binding.search.setSelected(type == 1);
        binding.change.setSelected(type == 2);
        binding.select.setClickable(type > 0);
        binding.cancel.setClickable(type > 0);
        adapter.setType(this.type = type);
    }

    private void setMode() {
        // 旧版这里在条目 <10 时自动写 site_mode=0，永久钉死单列（多列变 1 列的根因）；<10 显示单列由 list() 按当前条数判定，不写设置。
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
    }

    // ==================== 长按：直接弹删除 / 重命名（不进勾选模式） ====================

    @Override
    public void onItemLongClick(Site item) {
        if (item == null) return;
        if (LocalSourceHub.isSelfAuthored(item.getApi(), item.getExt(), null)) {
            showSelfAuthoredHint(getString(R.string.site_self_authored, item.getName()));
            return;
        }
        // 只对本地源文件与网页源生效；远程源长按不响应（与之前同一口径）。
        if (LocalSourceHub.sourceFilePathOf(item.getApi(), item.getExt(), null) == null && !item.isWebSource()) return;
        Context context = getContext();
        if (context == null) return;
        boolean web = item.isWebSource();
        // v27：长按菜单加“移动”（第三项）；网页源没有本地文件，菜单里不显示移动项。
        String[] actions = web ? new String[]{
            getString(R.string.site_websource_delete),
            getString(R.string.site_websource_rename)} : new String[]{
            getString(R.string.site_action_delete),
            getString(R.string.site_action_rename_source),
            getString(R.string.site_action_move)};
        new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(item.getName())
            .setItems(actions, (dialog, which) -> {
                if (which == 0) {
                    List<Site> single = new ArrayList<>();
                    single.add(item);
                    confirmDeleteSelected(single);
                } else if (which == 1) {
                    if (web) {
                        renameWebSourceFlow(item);
                    } else {
                        List<Site> single = new ArrayList<>();
                        single.add(item);
                        renameSourceFlow(single);
                    }
                } else {
                    // 移动：只对本地源文件（网页源菜单里没有这一项）。
                    List<Site> single = new ArrayList<>();
                    single.add(item);
                    chooseMoveTargetForSelection(single);
                }
            })
            .setNegativeButton(R.string.dialog_negative, null)
            .show();
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

    // ==================== 重命名（本地源文件 / 网页源） ====================

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

    /** 网页源改名：两个输入框（名字 + 网址），都可改，预填当前值；确定后调 Hub 文本级改名（手机版 v67 同款口径）。 */
    private void renameWebSourceFlow(Site site) {
        Context context = getContext();
        if (context == null || site == null) return;
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        EditText urlInput = new EditText(context);
        urlInput.setSingleLine(true);
        urlInput.setHint(R.string.site_web_source_url_hint);
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText nameInput = new EditText(context);
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.site_web_source_name_hint);
        urlInput.setText(site.getHomePageUrl());
        nameInput.setText(site.getName());
        nameInput.setSelection(nameInput.getText() == null ? 0 : nameInput.getText().length());
        layout.addView(urlInput, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(nameInput, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(R.string.site_websource_rename_title)
                .setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.show();
        // 强校验：网址无效原地提示，不关弹窗（手机版 WebSourceDialog 同款口径）。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String rawUrl = urlInput.getText() == null ? "" : urlInput.getText().toString();
            String webUrl = LocalSourceHub.normalizeWebSourceUrl(rawUrl);
            if (webUrl == null) {
                Notify.show(isBlank(rawUrl) ? R.string.site_web_source_url_empty : R.string.site_web_source_url_invalid);
                return;
            }
            String rawName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
            String webName = rawName.isEmpty() ? LocalSourceHub.deriveWebSourceName(webUrl) : rawName;
            if (webName.isEmpty()) {
                Notify.show(R.string.site_web_source_url_invalid);
                return;
            }
            // 先走权限门；已授予直接提交，未授予弹系统申请，通过后自动重试本次改名。
            PermissionUtil.ensureStoragePermission(this,
                    () -> {
                        dialog.dismiss();
                        doRenameWebSource(site.getKey(), webName, webUrl);
                    },
                    this::showWebSourcePermissionDenied);
        });
    }

    /** 网页源改名提交：后台调 Hub 文本级改名；成功提示 + 重载列表，其余按返回码提示（手机版 v67 同款口径）。 */
    private void doRenameWebSource(String oldKey, String name, String url) {
        new Thread(() -> {
            final int result = LocalSourceHub.renameWebSource(oldKey, name, url);
            post(() -> {
                if (!isAdded()) return;
                if (result == LocalSourceHub.WEB_OK) {
                    Notify.show(getString(R.string.site_websource_rename_done, name));
                    reloadAfterMove();
                } else if (result == LocalSourceHub.WEB_EXISTS) Notify.show(R.string.site_web_source_exists);
                else if (result == LocalSourceHub.WEB_NETWORK) Notify.show(R.string.site_websource_network);
                else if (result == LocalSourceHub.WEB_NOT_FOUND) {
                    Notify.show(R.string.site_websource_not_found);
                    reloadAfterMove();
                } else if (result == LocalSourceHub.WEB_NO_PERMISSION) showWebSourcePermissionDenied();
                else Notify.show(R.string.site_websource_edit_fail);
            });
        }, "local-source-websource-rename").start();
    }

    /** 拒绝 / 写盘时才发现没权限：弹权限提示 +「去设置」；弹窗已不在时退回 Toast。 */
    private void showWebSourcePermissionDenied() {
        if (!isAdded()) {
            Notify.show(R.string.websource_storage_permission_required);
            return;
        }
        Activity activity = requireActivity();
        new MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.websource_storage_permission_required)
                .setPositiveButton(R.string.permission_open_settings, (dialog, which) -> PermissionUtil.openAppSettings(activity))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    /** 删除源文件前的二次确认（真删磁盘文件并回写 json，不可恢复）。 */
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

    /** 删除：本地源删文件，网页源摘配置条目；删完同步回写并重载配置，条目立刻从列表消失。 */
    private void deleteSelected(List<Site> targets) {
        final List<Site> snapshot = new ArrayList<>(targets);
        new Thread(() -> {
            int removed = 0;
            String selfName = null;
            boolean noPermission = false;
            try {
                LocalSourceHub.beginBatch();
                for (Site site : snapshot) {
                    if (site.isWebSource()) {
                        // v25：网页源只删当前配置里的条目，不删文件；删完重载后列表里即消失。
                        int webResult = LocalSourceHub.deleteWebSource(site.getKey());
                        if (webResult == LocalSourceHub.WEB_OK || webResult == LocalSourceHub.WEB_NOT_FOUND) removed++;
                        else if (webResult == LocalSourceHub.WEB_NO_PERMISSION) noPermission = true;
                        continue;
                    }
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
            final boolean denied = noPermission;
            post(() -> {
                if (!isAdded()) return;
                if (blockedName != null) showSelfAuthoredHint(getString(R.string.site_self_authored, blockedName));
                if (denied) Notify.show(R.string.websource_storage_permission_required);
                if (removedCount == 0) {
                    if (!denied) Notify.show(R.string.site_del_fail);
                    return;
                }
                Notify.show(getString(R.string.site_del_done_multi, removedCount));
                reloadAfterMove();
            });
        }, "local-source-delete").start();
    }

    /** v27：单条移动——弹目录树选目的文件夹，搬文件后重载生成新配置（主线 v35 同款逻辑）。 */
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

    /** 删除/改名后立刻生效：重载点播配置 → 重建列表。 */
    private void reloadAfterMove() {
        VodConfig.get().init().load(new Callback() {
            @Override
            public void start() {
            }

            @Override
            public void success() {
                if (!isAdded()) return;
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
