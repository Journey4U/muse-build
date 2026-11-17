package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;

import java.util.List;

/**
 * v62 网页源：设置页入口（v61 在切站弹窗里，Liu 嫌挤，挪到设置）。
 * 输网址（必填）+ 名称（可选，留空自动取域名），追加到当前配置 sites 数组末尾，
 * 写完重载配置，新站立刻出现在切站列表里。
 * v32 批量导入：同一弹窗的批量模式（showBatch），只留多行网址框，不设字节上限；
 * 确认后先纯解析，100 条及以上弹横向进度框、后台线程逐条追加，进度发回主线程刷新。
 */
public class WebSourceDialog {

    public static void show(Context context) {
        show(context, false);
    }

    /** v32：批量导入入口——与单个添加共用同一弹窗，批量模式只留多行网址框。 */
    public static void showBatch(Context context) {
        show(context, true);
    }

    private static void show(Context context, boolean batch) {
        if (context == null) return;
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(18 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        android.widget.EditText urlInput = new android.widget.EditText(context);
        if (batch) {
            // v32 批量模式：只要多行网址框——多行输入、不设字节上限，提示换成批量说明
            urlInput.setSingleLine(false);
            urlInput.setMinLines(6);
            urlInput.setHint(R.string.web_source_batch_hint);
            urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            urlInput.setSingleLine(true);
            urlInput.setHint(R.string.site_web_source_url_hint);
            urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        }
        android.widget.EditText nameInput = new android.widget.EditText(context);
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.site_web_source_name_hint);
        layout.addView(urlInput, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        if (!batch) {
            // 批量模式不要名称框（key/name 一律取域名）
            layout.addView(nameInput, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(batch ? R.string.web_source_batch_import : R.string.site_web_source_title)
                .setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.show();
        // 强校验：网址无效原地提示，不关弹窗，避免「点了确定没反应」
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (batch) {
                onBatchPositive(context, dialog, urlInput);
                return;
            }
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
            // v67：先走权限门；已授予直接加，未授予弹系统申请，通过后自动重试本次添加
            if (context instanceof FragmentActivity) {
                PermissionUtil.ensureStoragePermission((FragmentActivity) context,
                        () -> {
                            dialog.dismiss();
                            addWebSource(context, webName, webUrl);
                        },
                        () -> showPermissionDenied(context));
            } else {
                dialog.dismiss();
                addWebSource(context, webName, webUrl);
            }
        });
    }

    /** v32：批量导入确认——先纯解析得 URL 列表，空列表直接提示；再走权限门，通过后后台线程导入。 */
    private static void onBatchPositive(Context context, AlertDialog dialog, android.widget.EditText urlInput) {
        String raw = urlInput.getText() == null ? "" : urlInput.getText().toString();
        List<String> urls = LocalSourceHub.parseBatchWebSourceUrls(raw);
        if (urls.isEmpty()) {
            Notify.show(R.string.web_source_batch_empty);
            return;
        }
        // 与单个添加同款权限门：已授予直接跑，未授予弹系统申请，通过后自动重试本次导入
        if (context instanceof FragmentActivity) {
            PermissionUtil.ensureStoragePermission((FragmentActivity) context,
                    () -> {
                        dialog.dismiss();
                        runBatchImport(context, urls);
                    },
                    () -> showPermissionDenied(context));
        } else {
            dialog.dismiss();
            runBatchImport(context, urls);
        }
    }

    /**
     * v32：批量导入执行——后台线程逐条追加，不卡界面；
     * 100 条及以上弹横向进度框（ProgressDialog STYLE_HORIZONTAL），进度回调发回主线程刷新
     * 进度条与“已处理 X / 共 Y 条”消息；跑完关进度框，再弹成功/跳过数结果，成功过就重载配置。
     */
    private static void runBatchImport(Context context, List<String> urls) {
        int total = urls.size();
        ProgressDialog progress = null;
        if (total >= 100) {
            try {
                progress = new ProgressDialog(context);
                progress.setTitle(R.string.web_source_batch_import);
                progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                progress.setMax(total);
                progress.setProgress(0);
                progress.setMessage(context.getString(R.string.web_source_batch_progress, 0, total));
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
                        dialog.setMessage(context.getString(R.string.web_source_batch_progress, done, totalCount));
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
                Notify.show(context.getString(R.string.web_source_batch_result, result[0], result[1]));
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

    private static void addWebSource(Context context, String name, String url) {
        new Thread(() -> {
            final int result = LocalSourceHub.appendWebSource(name, url);
            App.post(() -> {
                if (result == LocalSourceHub.WEB_OK) {
                    Notify.show(context.getString(R.string.site_web_source_done, name));
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
                else if (result == LocalSourceHub.WEB_NO_PERMISSION) showPermissionDenied(context);
                else Notify.show(R.string.site_web_source_fail);
            });
        }, "local-source-websource").start();
    }

    /** v67：拒绝 / 写盘时才发现没权限：弹权限提示 +「去设置」；不是 Activity 上下文时退回 Toast。 */
    private static void showPermissionDenied(Context context) {
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
