package com.muse.android.tv.ui.dialog;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;

/**
 * v62 网页源：设置页入口（v61 在切站弹窗里，Liu 嫌挤，挪到设置）。
 * 输网址（必填）+ 名称（可选，留空自动取域名），追加到当前配置 sites 数组末尾，
 * 写完重载配置，新站立刻出现在切站列表里。
 */
public class WebSourceDialog {

    public static void show(Context context) {
        if (context == null) return;
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
        layout.addView(urlInput, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(nameInput, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
                .setTitle(R.string.site_web_source_title)
                .setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.show();
        // 强校验：网址无效原地提示，不关弹窗，避免「点了确定没反应」
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
            dialog.dismiss();
            addWebSource(context, webName, webUrl);
        });
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
                else Notify.show(R.string.site_web_source_fail);
            });
        }, "local-source-websource").start();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
