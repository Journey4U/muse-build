package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Config;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.databinding.DialogLocalSourceBinding;
import com.muse.android.tv.ui.activity.FileTransferActivity;
import android.content.Intent;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;
import com.github.catvod.crawler.DebugLogStore;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.UrlUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;

/**
 * r13-③ / r14-② 本地文件夹源弹窗：目录与规则在「本地源生成设置」里配置，
 * 这里负责开关、立即生成并加载、查看当前目录与上次扫描结果。
 *
 * <p>生成的源在每次加载订阅源时由 LocalSourceHub 自动重新扫描并注入配置，
 * 因此「保存并重新加载」之后无需再做任何手动操作。
 */
public class LocalSourceDialog extends BaseAlertDialog {

    private DialogLocalSourceBinding binding;
    private Runnable changed;

    public static void show(Fragment fragment, Runnable changed) {
        LocalSourceDialog dialog = new LocalSourceDialog();
        dialog.changed = changed;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogLocalSourceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.local_source_title)
            .setView(getBinding().getRoot())
            .setPositiveButton(R.string.local_source_settings_save_load, (dialog, which) -> onSaveAndLoad())
            .setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        binding.localSourceEnable.setChecked(LocalSourceHub.isEnabled());
        binding.localSourceAuto.setChecked(LocalSourceHub.isAuto());
        refresh();
    }

    @Override
    protected void initEvent() {
        binding.localSourceSettings.setOnClickListener(view -> LocalSourceSettingsDialog.show(this, this::onSettingsChanged));
        binding.localSourceTransfer.setOnClickListener(view -> startActivity(new Intent(requireActivity(), FileTransferActivity.class)));
        binding.localSourceEnable.setOnCheckedChangeListener((button, checked) -> refresh());
    }

    private void onSettingsChanged() {
        refresh();
        if (changed != null) changed.run();
    }

    private void refresh() {
        if (!isAdded() || binding == null) return;
        List<String> dirs = LocalSourceHub.getScanDirs();
        StringBuilder builder = new StringBuilder(getString(R.string.local_source_settings_dirs));
        if (dirs.isEmpty()) {
            builder.append("\n").append(getString(R.string.local_source_settings_dirs_empty));
        } else {
            for (String dir : dirs) builder.append("\n").append(LocalSourceHub.isDirEnabled(dir) ? "☑ " : "☐ ").append(dir);
        }
        binding.localSourceDirs.setText(builder.toString());
        binding.localSourceStatus.setText(status());
    }

    private String status() {
        StringBuilder builder = new StringBuilder(getString(R.string.local_source_status, LocalSourceHub.getStatusText()));
        String file = LocalSourceHub.getOutputPath();
        if (!TextUtils.isEmpty(file)) builder.append("\n").append(getString(R.string.local_source_output, file));
        return builder.toString();
    }

    private boolean save() {
        boolean enable = binding.localSourceEnable.isChecked();
        LocalSourceHub.setEnabled(enable);
        LocalSourceHub.setAuto(binding.localSourceAuto.isChecked());
        if (enable && LocalSourceHub.getScanDirs().isEmpty()) {
            Notify.show(R.string.local_source_dir_required);
            return false;
        }
        return true;
    }

    /**
     * r15：只保留一个动作按钮「保存并加载」＝ 保存开关 → 生成 → 重载点播列表。
     * 之前「保存并重新加载」与「立即生成并加载」的差别只在于是否重新扫描，这里统一成一步。
     *
     * <p>生成跑在后台线程，回调只使用 Application Context 的字符串与已捕获的 Activity，
     * 不再在弹窗 detach 之后调用 {@code getString()} / {@code requireActivity()}
     * （那正是关闭弹窗后主线程崩溃的原因）。
     */
    /** MuseTV 权限线：扫描/生成读写外部存储前先走 WRITE 运行时权限，绝不走 VesperTV 的 MANAGE 路线。 */
    private void onSaveAndLoad() {
        if (!save()) return;
        Activity host = getActivity();
        if (host == null) return;
        PermissionUtil.requestFile(this, granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                Notify.show(R.string.local_source_permission_required);
                return;
            }
            Context appContext = host.getApplicationContext();
            Notify.show(R.string.local_source_scanning);
            new Thread(() -> {
                LocalSourceHub.generate();
            String message = appContext.getString(R.string.local_source_done, LocalSourceHub.getSummary()) + blockedWriteHint(appContext);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (changed != null) changed.run();
                if (isAdded() && binding != null) refresh();
                Notify.show(message);
                // 弹窗在点「保存并加载」时已自动 dismiss：到这里弹窗多半已 detach，
                // 再用 getActivity() 拿宿主会拿到 null，reload() 静默返回、配置永远设不上
                // （v128 的日志里生成后没有任何配置记录、点播仍为空，正是这条路）。
                // 所以把点按钮时捕获的 host 直接传进去，不再事后向弹窗要 Activity。
                reload(host);
            });
            }, "local-source-scan").start();
        });
    }

    /** r26-②：本轮「保存并加载」若因自写源保护跳过了自动刷新回填，补一句说明。 */
    private static String blockedWriteHint(Context context) {
        String blocked = LocalSourceHub.consumeBlockedWrite();
        return blocked == null || blocked.isEmpty() ? "" : "\n" + context.getString(R.string.local_source_write_blocked, blocked);
    }

    private void reload(Activity host) {
        if (host == null || host.isFinishing()) return;
        DebugLogStore.add("LocalSource", "本地源重载开始：已保存点播=" + VodConfig.getUrl() + "，产物路径=" + LocalSourceHub.getOutputPath());
        Callback callback = new Callback() {
            @Override
            public void start() {
                Notify.progress(host);
            }

            @Override
            public void success() {
                Notify.dismiss();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
        // 「保存并加载」＝把刚生成的本壳产物设为点播配置并加载（与「本地源生成设置」
        // 对话框的 reload 同一口径）：产物文件存在就直接设为点播，配置栏随之变成产物地址；
        // 当前配置为空、或已经就是这个产物时同样走这条路。产物不存在（生成失败）时才
        // 退回重载当前配置。生成前 onSaveAndLoad 已走过 requestFile，这里不再另起权限路径。
        String output = LocalSourceHub.getOutputPath();
        if (TextUtils.isEmpty(output) || !new File(output).isFile()) {
            VodConfig.get().init().load(callback);
            return;
        }
        String url = UrlUtil.toLocalUrl(Uri.fromFile(new File(output)));
        VodConfig.load(Config.find(url, 0), callback);
    }
}
