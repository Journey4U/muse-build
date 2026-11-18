package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;

import java.util.List;

/**
 * v62 网页源：设置页入口（v61 在切站弹窗里，Liu 嫌挤，挪到设置）。
 * 输网址（必填）+ 名称（可选，留空自动取域名），追加到当前配置 sites 数组末尾，
 * 写完重载配置，新站立刻出现在切站列表里。
 * v67：入口改传 Fragment，点确定先走权限统一门；通过后自动重试本次添加（名字和
 * 网址保留，不让用户重输），拒绝弹权限提示 +「去设置」。
 */
public class WebSourceDialog {

    public static void show(Fragment fragment) {
        showInternal(fragment, null, null);
    }

    /**
     * v67：网页源改名 —— 复用添加弹窗：预填当前名字与网址，标题换成改名，
     * 提交走改名接口；改后由调用方重载列表（onEdited，见 SiteDialog）。
     */
    public static void showEdit(Fragment fragment, Site site, Runnable onEdited) {
        if (site == null) return;
        showInternal(fragment, site, onEdited);
    }

    /**
     * v70：批量导入网页源 —— 多行输入框（不设 maxLength），一次粘贴全部网址，
     * 点一次确定全部一次处理完，不用分批。空输入原地提示，不关弹窗。
     * >= 100 条时弹横向进度框，后台线程处理、进度发回主线程刷新。
     */
    public static void showBatch(Fragment fragment) {
        if (fragment == null || !fragment.isAdded()) return;
        Context context = fragment.requireContext();
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(18 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        android.widget.EditText input = new android.widget.EditText(context);
        input.setSingleLine(false);
        input.setMinLines(5);
        input.setGravity(android.view.Gravity.TOP);
        input.setHint(R.string.web_source_batch_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        layout.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(R.string.web_source_batch_import)
                .setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString();
            List<String> urls = LocalSourceHub.parseBatchWebSourceUrls(text);
            if (urls.isEmpty()) {
                Notify.show(R.string.web_source_batch_empty);
                return;
            }
            // 与单个添加同一权限门；通过后自动重试本次导入（输入保留，不让用户重输）
            PermissionUtil.ensureStoragePermission(fragment,
                    () -> {
                        dialog.dismiss();
                        runBatchImport(fragment, urls);
                    },
                    () -> showPermissionDenied(fragment));
        });
    }

    /**
     * v70：批量导入执行 —— total >= 100 弹横向进度框（ProgressDialog.STYLE_HORIZONTAL，
     * 标题“批量导入网页源”，消息“正在导入网页源（已处理 X / 共 Y 条）”），后台线程跑导入，
     * listener 经 App.post 发回主线程刷新进度条与消息；跑完关框再弹结果提示。
     * total < 100 直接后台跑导入（listener 传 null），跑完弹结果提示。
     */
    private static void runBatchImport(Fragment fragment, List<String> urls) {
        if (fragment == null || !fragment.isAdded()) return;
        Context context = fragment.requireContext();
        int total = urls.size();
        if (total >= 100) {
            android.app.ProgressDialog progress = new android.app.ProgressDialog(context);
            progress.setTitle(R.string.web_source_batch_import);
            progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
            progress.setMax(total);
            progress.setProgress(0);
            progress.setMessage(context.getString(R.string.web_source_batch_progress, 0, total));
            progress.setCancelable(false);
            progress.show();
            new Thread(() -> {
                int[] result = LocalSourceHub.importBatchWebSources(urls, (done, totalCount) -> App.post(() -> {
                    if (progress.isShowing()) {
                        progress.setProgress(done);
                        progress.setMessage(context.getString(R.string.web_source_batch_progress, done, totalCount));
                    }
                }));
                App.post(() -> {
                    try {
                        if (progress.isShowing()) progress.dismiss();
                    } catch (Throwable ignored) {
                    }
                    showBatchResult(context, result);
                });
            }, "local-source-batch-import").start();
        } else {
            new Thread(() -> {
                int[] result = LocalSourceHub.importBatchWebSources(urls, null);
                App.post(() -> showBatchResult(context, result));
            }, "local-source-batch-import").start();
        }
    }

    /** v70：批量导入结果 —— 提示成功/跳过数；成功数 > 0 时重载配置，新站立刻进切站列表。 */
    private static void showBatchResult(Context context, int[] result) {
        int added = result == null || result.length < 2 ? 0 : result[0];
        int skipped = result == null || result.length < 2 ? 0 : result[1];
        Notify.show(context.getString(R.string.web_source_batch_result, added, skipped));
        if (added > 0) {
            VodConfig.get().init().load(new Callback() {
                @Override
                public void start() {
                }

                @Override
                public void success() {
                }
            });
        }
    }

    private static void showInternal(Fragment fragment, Site editTarget, Runnable onEdited) {
        if (fragment == null || !fragment.isAdded()) return;
        Context context = fragment.requireContext();
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(18 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        android.widget.EditText urlInput = new android.widget.EditText(context);
        urlInput.setSingleLine(true);
        urlInput.setHint(R.string.site_web_source_url_hint);
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        android.widget.EditText nameInput = new android.widget.EditText(context);
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.site_web_source_name_hint);
        // v67：改名预填当前名字与网址（光标落到名字末尾，方便直接改）
        if (editTarget != null) {
            urlInput.setText(editTarget.getHomePageUrl());
            nameInput.setText(editTarget.getName());
            nameInput.setSelection(nameInput.getText() == null ? 0 : nameInput.getText().length());
        }
        layout.addView(urlInput, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(nameInput, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(editTarget == null ? R.string.site_web_source_title : R.string.site_websource_rename_title)
                .setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.show();
        // 强校验：网址无效原地提示，不关弹窗，避免「点了确定没反应」
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String rawUrl = urlInput.getText() == null ? "" : urlInput.getText().toString();
            String webUrl = LocalSourceHub.normalizeWebSourceUrl(LocalSourceHub.sanitizeWebSourceUrl(rawUrl));
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
            // v67：先走权限门；已授予直接提交，未授予弹系统申请，通过后自动重试本次操作
            // （名字和网址保留，不让用户重输）
            PermissionUtil.ensureStoragePermission(fragment,
                    () -> {
                        dialog.dismiss();
                        if (editTarget == null) addWebSource(context, webName, webUrl);
                        else editWebSource(context, editTarget.getKey(), webName, webUrl, onEdited);
                    },
                    () -> showPermissionDenied(fragment));
        });
    }

    /** v67：拒绝弹权限提示 +「去设置」（输入弹窗还留着，点取消回来不用重输）。 */
    private static void showPermissionDenied(Fragment fragment) {
        if (fragment == null || !fragment.isAdded()) return;
        Activity activity = fragment.requireActivity();
        new MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.websource_storage_permission_required)
                .setPositiveButton(R.string.permission_open_settings, (dialog, which) -> PermissionUtil.openAppSettings(activity))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private static void addWebSource(Context context, String name, String url) {
        new Thread(() -> {
            final int result = LocalSourceHub.appendWebSource(name, url);
            App.post(() -> {
                if (result == LocalSourceHub.WEB_OK || result == LocalSourceHub.WEB_CREATED) {
                    Notify.show(context.getString(result == LocalSourceHub.WEB_CREATED ? R.string.site_web_source_created : R.string.site_web_source_done, name));
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
                else if (result == LocalSourceHub.WEB_NO_PERMISSION) showNoPermission(context);
                else Notify.show(R.string.site_web_source_fail);
            });
        }, "local-source-websource").start();
    }

    /** v67：改名提交 —— 后台调改名接口；成功提示 + 通知调用方重载列表，其余按返回码提示。 */
    private static void editWebSource(Context context, String oldKey, String name, String url, Runnable onEdited) {
        new Thread(() -> {
            final int result = LocalSourceHub.renameWebSource(oldKey, name, url);
            App.post(() -> {
                if (result == LocalSourceHub.WEB_OK) {
                    Notify.show(context.getString(R.string.site_websource_rename_done, name));
                    if (onEdited != null) onEdited.run();
                } else if (result == LocalSourceHub.WEB_EXISTS) Notify.show(R.string.site_web_source_exists);
                else if (result == LocalSourceHub.WEB_NETWORK) Notify.show(R.string.site_websource_network);
                else if (result == LocalSourceHub.WEB_NOT_FOUND) {
                    Notify.show(R.string.site_websource_not_found);
                    if (onEdited != null) onEdited.run();
                } else if (result == LocalSourceHub.WEB_NO_PERMISSION) showNoPermission(context);
                else Notify.show(R.string.site_websource_edit_fail);
            });
        }, "local-source-websource-rename").start();
    }

    /**
     * v67：写盘时才发现没权限（与笼统的失败分开）：同样弹权限提示 +「去设置」。
     * 包内可见，供 SiteDialog 的网页源删除复用（同一写盘失败口径）。
     */
    static void showNoPermission(Context context) {
        if (context instanceof Activity && !((Activity) context).isFinishing()) {
            Activity activity = (Activity) context;
            new MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.websource_storage_permission_required)
                    .setPositiveButton(R.string.permission_open_settings, (dialog, which) -> PermissionUtil.openAppSettings(activity))
                    .setNegativeButton(R.string.dialog_negative, null)
                    .show();
        } else {
            Notify.show(R.string.websource_storage_permission_required);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
