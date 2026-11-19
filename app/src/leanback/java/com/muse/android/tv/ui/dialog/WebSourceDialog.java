package com.muse.android.tv.ui.dialog;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.databinding.DialogWebSourceBinding;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;

import java.util.List;

/**
 * v22 网页源（电视版，移植自主线手机 v61/v62）：设置页「本地源」行下的「添加网页源」入口。
 * 输网址（必填）+ 名称（可选，留空自动取域名），追加到当前配置 sites 数组末尾，
 * 写完重载配置，新站立刻出现在切站列表里。
 * 电视写法：BaseAlertDialog + dialog_web_source.xml（CustomEditText 行式输入 + 底部确认/取消，
 * 与电视版 ConfigDialog 同款），不用手机版程序拼 AlertDialog 的写法。
 * v25：点确定先走权限统一门（同步主线手机 v67）；通过后自动重试本次添加，拒绝弹
 * 权限提示 +「去设置」。
 * v28 批量导入：同一弹窗的批量模式（showBatch），只留多行网址框，不设字节上限；
 * 确认后先纯解析，100 条及以上弹横向进度框、后台线程逐条追加，进度发回主线程刷新。
 */
public class WebSourceDialog extends BaseAlertDialog {

    private DialogWebSourceBinding binding;

    public static void show(FragmentActivity activity) {
        if (activity == null) return;
        new WebSourceDialog().show(activity.getSupportFragmentManager(), null);
    }

    /** v28：批量导入入口——与单个添加共用同一弹窗，批量模式只留多行网址框。 */
    public static void showBatch(FragmentActivity activity) {
        WebSourceDialog dialog = new WebSourceDialog();
        Bundle args = new Bundle();
        args.putBoolean("batch", true);
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    private boolean isBatch() {
        return getArguments() != null && getArguments().getBoolean("batch", false);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogWebSourceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(isBatch() ? R.string.web_source_batch_import : R.string.site_web_source_title).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        if (isBatch()) {
            // v28 批量模式：只要多行网址框——多行输入、不设字节上限（清掉长度过滤器），
            // 名称框隐藏（key/name 一律取域名），提示换成批量说明
            binding.name.setVisibility(View.GONE);
            binding.url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            binding.url.setSingleLine(false);
            binding.url.setMinLines(6);
            binding.url.setMaxLines(6);
            binding.url.setNextFocusDownId(R.id.positive);
            binding.positive.setNextFocusUpId(R.id.url);
            binding.negative.setNextFocusUpId(R.id.url);
            binding.url.setFilters(new InputFilter[0]);
            binding.url.setHint(R.string.web_source_batch_hint);
            binding.url.setImeOptions(EditorInfo.IME_ACTION_DONE);
            binding.url.requestFocus();
            return;
        }
        binding.url.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(view -> {
            if (isBatch()) onBatchPositive();
            else onPositive();
        });
        binding.negative.setOnClickListener(v -> dismiss());
        binding.url.setOnEditorActionListener((textView, actionId, event) -> {
            if (isBatch() && actionId == EditorInfo.IME_ACTION_DONE) {
                binding.positive.performClick();
                return true;
            }
            return false;
        });
    }

    /** v28：批量导入确认——先纯解析得 URL 列表，空列表直接提示；再走权限门，通过后后台线程导入。 */
    private void onBatchPositive() {
        String raw = binding.url.getText() == null ? "" : binding.url.getText().toString();
        List<String> urls = LocalSourceHub.parseBatchWebSourceUrls(raw);
        if (urls.isEmpty()) {
            Notify.show(R.string.web_source_batch_empty);
            return;
        }
        // 与单个添加同款权限门：已授予直接跑，未授予弹系统申请，通过后自动重试本次导入
        PermissionUtil.ensureStoragePermission(this,
                () -> {
                    FragmentActivity activity = requireActivity();
                    dismiss();
                    runBatchImport(activity, urls);
                },
                this::showPermissionDenied);
    }

    /**
     * v28：批量导入执行——后台线程逐条追加，不卡界面；
     * 100 条及以上弹横向进度框（ProgressDialog STYLE_HORIZONTAL），进度回调发回主线程刷新
     * 进度条与“已处理 X / 共 Y 条”消息；跑完关进度框，再弹成功/跳过数结果，成功过就重载配置。
     */
    private void runBatchImport(FragmentActivity activity, List<String> urls) {
        int total = urls.size();
        ProgressDialog progress = null;
        if (total >= 100) {
            try {
                progress = new ProgressDialog(activity);
                progress.setTitle(R.string.web_source_batch_import);
                progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                progress.setMax(total);
                progress.setProgress(0);
                progress.setMessage(ResUtil.getString(R.string.web_source_batch_progress, 0, total));
                progress.setCancelable(false);
                progress.show();
            } catch (Throwable ignored) {
                progress = null;
            }
        }
        final ProgressDialog dialog = progress;
        new Thread(() -> {
            int[] result = LocalSourceHub.importBatchWebSources(urls, dialog == null ? null : (done, totalCount) -> App.post(() -> {
                try {
                    if (dialog.isShowing()) {
                        dialog.setProgress(done);
                        dialog.setMessage(ResUtil.getString(R.string.web_source_batch_progress, done, totalCount));
                    }
                } catch (Throwable ignored) {
                }
            }));
            App.post(() -> {
                if (dialog != null) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
                Notify.show(ResUtil.getString(R.string.web_source_batch_result, result[0], result[1]));
                if (result[0] > 0) {
                    // 写完重载配置：新站立刻进切站列表，不用手动刷新
                    VodConfig.get().init().load(new Callback() {
                        @Override
                        public void start() {
                        }

                        @Override
                        public void success() {
                        }
                    });
                }
            });
        }, "local-source-batch").start();
    }

    private void onPositive() {
        String rawUrl = binding.url.getText() == null ? "" : binding.url.getText().toString();
        String webUrl = LocalSourceHub.normalizeWebSourceUrl(LocalSourceHub.sanitizeWebSourceUrl(rawUrl));
        if (webUrl == null) {
            Notify.show(isBlank(rawUrl) ? R.string.site_web_source_url_empty : R.string.site_web_source_url_invalid);
            return;
        }
        String rawName = binding.name.getText() == null ? "" : binding.name.getText().toString().trim();
        String webName = rawName.isEmpty() ? LocalSourceHub.deriveWebSourceName(webUrl) : rawName;
        if (webName.isEmpty()) {
            Notify.show(R.string.site_web_source_url_invalid);
            return;
        }
        String done = getString(R.string.site_web_source_done, webName);
        // v25：先走权限门；已授予直接加，未授予弹系统申请，通过后自动重试本次添加
        PermissionUtil.ensureStoragePermission(this,
                () -> {
                    dismiss();
                    addWebSource(webName, webUrl, done);
                },
                this::showPermissionDenied);
    }

    private void addWebSource(String name, String url, String done) {
        new Thread(() -> {
            final int result = LocalSourceHub.appendWebSource(name, url);
            App.post(() -> {
                if (result == LocalSourceHub.WEB_OK) {
                    Notify.show(done);
                    // 写完重载配置：新站立刻进切站列表，不用手动刷新
                    VodConfig.get().init().load(new Callback() {
                        @Override
                        public void start() {
                        }

                        @Override
                        public void success() {
                        }
                    });
                } else if (result == LocalSourceHub.WEB_EXISTS) Notify.show(R.string.site_web_source_exists);
                else if (result == LocalSourceHub.WEB_NETWORK) Notify.show(R.string.site_web_source_network);
                else if (result == LocalSourceHub.WEB_NO_CONFIG) Notify.show(R.string.site_web_source_no_config);
                else if (result == LocalSourceHub.WEB_NO_PERMISSION) showPermissionDenied();
                else Notify.show(R.string.site_web_source_fail);
            });
        }, "local-source-websource").start();
    }

    /** v25：拒绝 / 写盘时才发现没权限：弹权限提示 +「去设置」；弹窗已不在时退回 Toast。 */
    private void showPermissionDenied() {
        if (!isAdded()) {
            Notify.show(R.string.websource_storage_permission_required);
            return;
        }
        FragmentActivity activity = requireActivity();
        new MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.websource_storage_permission_required)
                .setPositiveButton(R.string.permission_open_settings, (dialog, which) -> PermissionUtil.openAppSettings(activity))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
