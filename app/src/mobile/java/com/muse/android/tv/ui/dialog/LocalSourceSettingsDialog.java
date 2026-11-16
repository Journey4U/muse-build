package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.databinding.DialogLocalSourceSettingsBinding;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.ui.activity.FileActivity;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * r14-③ / r15 本地源生成设置：与用户手工脚本「本地包生成_settings.json」的设置项一一对应。
 *
 * <p>r15 变更：
 * <ul>
 *   <li>只保留一个动作按钮「保存并加载」＝ 保存设置 → 生成 → 重载点播列表（不再分「保存并重新加载」与「立即生成并加载」）；</li>
 *   <li>扫描类型、忽略目录名、忽略文件名改为勾选列表：类型默认全选，目录名 / 文件名由扫描目录自动生成候选；</li>
 *   <li>新增「替换原有点播站点」「同名多引擎只保留一个」两个开关，外加扫描模式的文字说明；</li>
 *   <li>生成回调不再触碰已销毁的弹窗（修复关闭后回调导致的主线程崩溃）。</li>
 * </ul>
 */
public class LocalSourceSettingsDialog extends BaseAlertDialog {

    private DialogLocalSourceSettingsBinding binding;
    private Runnable changed;
    private final List<String> blockedCandidates = new ArrayList<>();
    /** r16-⑤：扫描目录里实际存在的后缀（扫描类型勾选项）。 */
    private final List<String> extCandidates = new ArrayList<>();
    /** r17-⑤：当前选中的 spider jar（绝对路径；空 = 产物里不写 spider）。 */
    private String spiderJar = "";

    /** 扫描目录文件夹选择：应用内 FileActivity 文件夹模式，回传当前目录的 file:// Uri。 */
    private final ActivityResultLauncher<Intent> dirLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        if (!isAdded() || binding == null) return;
        Uri uri = result.getData().getData();
        String dir = LocalSourceHub.normalizeScanDir(uri.toString());
        if (TextUtils.isEmpty(dir)) return;
        LocalSourceHub.addScanDir(dir);
        binding.settingsDirInput.setText("");
        renderDirs();
        refreshCandidates();
    });

    public static void show(Fragment fragment, Runnable changed) {
        LocalSourceSettingsDialog dialog = new LocalSourceSettingsDialog();
        dialog.changed = changed;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogLocalSourceSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.local_source_settings_title)
            .setView(getBinding().getRoot())
            .setPositiveButton(R.string.local_source_settings_save_load, (dialog, which) -> onSaveAndLoad())
            .setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        JsonObject data = LocalSourceHub.settings();
        // r29-⑤：产物文件名改为可自定义（留空＝Muse本地源）；原来的「生成文件路径」已删除。
        binding.settingsOutName.setText(LocalSourceHub.getOutName());
        binding.settingsReplace.setChecked(LocalSourceHub.isReplace());
        setSpiderJar(LocalSourceHub.getSpiderJar());
        binding.settingsLiveUrl.setText(LocalSourceHub.getLiveUrl());
        binding.settingsBase64.setChecked(LocalSourceHub.isDecodeBase64());
        binding.settingsParses.setText(getString(R.string.local_source_settings_parses, LocalSourceHub.getParseEntries().size()));
        renderDirs();
        renderExtensions();
        refreshCandidates();
    }

    @Override
    protected void initEvent() {
        binding.settingsDirAdd.setOnClickListener(view -> onAddDir());
        binding.settingsDirPick.setOnClickListener(view -> onPickDir());
        binding.settingsParses.setOnClickListener(view -> showParses());
        binding.settingsExtAll.setOnClickListener(view -> toggleAllExtensions());
        // r18-⑪：本地源不再屏蔽（清空）其它源，只做追加，「替换原有点播站点」开关已失效 → 直接隐藏
        binding.settingsReplace.setVisibility(android.view.View.GONE);
        // r29-⑥②：「重新扫描目录刷新列表」按钮已删除 —— 改为勾选/取消勾选扫描目录时自动刷新候选
        // （见 renderDirs 里的勾选监听），未勾选的目录不参与候选生成。
        // r17-⑤：spider jar（从扫描目录里挑 .jar）与直播源地址
        binding.settingsSpiderPick.setOnClickListener(view -> onPickSpiderJar());
        // 清除 = 清掉手动选择、回到自动探测（探到默认 jar 才写 spider，见 getSpiderJar）。
        binding.settingsSpiderClear.setOnClickListener(view -> setSpiderJar(LocalSourceHub.getDefaultSpiderJar()));
        binding.settingsLiveClear.setOnClickListener(view -> binding.settingsLiveUrl.setText(""));
    }

    // ==================== r17-⑤ spider jar / 直播源地址 ====================

    private void setSpiderJar(String path) {
        spiderJar = path == null ? "" : path.trim();
        binding.settingsSpiderValue.setText(TextUtils.isEmpty(spiderJar) ? getString(R.string.local_source_settings_spider_none) : spiderJar);
    }

    /** 从「已选扫描目录」里挑一个 .jar（不选则用默认 ./jar/xbpq251118.jar）。 */
    private void onPickSpiderJar() {
        new Thread(() -> {
            List<String> found = LocalSourceHub.jarCandidates();
            new Handler(Looper.getMainLooper()).post(() -> showJarList(found));
        }).start();
    }

    private void showJarList(List<String> found) {
        if (!isAdded()) return;
        if (found.isEmpty()) {
            new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme)
                .setMessage(R.string.local_source_settings_spider_empty)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
            return;
        }
        String[] items = found.toArray(new String[0]);
        new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.local_source_settings_spider_pick_title)
            .setItems(items, (dialog, which) -> setSpiderJar(items[which]))
            .setNegativeButton(R.string.dialog_negative, null)
            .show();
    }

    // ==================== 扫描目录 ====================

    private void renderDirs() {
        binding.settingsDirList.removeAllViews();
        List<String> dirs = LocalSourceHub.getScanDirs();
        if (dirs.isEmpty()) {
            binding.settingsDirList.addView(hint(getString(R.string.local_source_settings_dirs_empty)));
            return;
        }
        for (String dir : dirs) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            MaterialCheckBox check = new MaterialCheckBox(requireContext());
            check.setText(dir);
            check.setChecked(LocalSourceHub.isDirEnabled(dir));
            check.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // r29-⑥②：切换勾选（勾上 / 取消）立即按新的扫描路径重算候选 —— 不再需要「重新扫描目录刷新列表」按钮。
            // 未勾选的目录不参与候选生成（LocalSourceHub 的候选/类型/ jar 都只看 enabledScanDirs），
            // 所以取消勾选后，它下面的内容立刻从列表里消失。
            check.setOnCheckedChangeListener((button, checked) -> {
                LocalSourceHub.setDirEnabled(dir, checked);
                refreshCandidates();
            });

            MaterialButton remove = new MaterialButton(requireContext());
            remove.setText(R.string.local_source_settings_dir_remove);
            remove.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            remove.setOnClickListener(view -> {
                LocalSourceHub.removeScanDir(dir);
                renderDirs();
                refreshCandidates();
            });

            row.addView(check);
            row.addView(remove);
            binding.settingsDirList.addView(row);
        }
    }

    private void onPickDir() {
        Intent intent = new Intent(requireContext(), FileActivity.class);
        intent.putExtra(FileActivity.EXTRA_PICK_FOLDER, true);
        dirLauncher.launch(intent);
    }

    private void onAddDir() {
        String raw = binding.settingsDirInput.getText() == null ? "" : binding.settingsDirInput.getText().toString().trim();
        // 手动输入也走同一归一化：file:// 串折成绝对路径后入库，绝不存 file:// 写法。
        String dir = LocalSourceHub.normalizeScanDir(raw);
        if (TextUtils.isEmpty(dir)) {
            Notify.show(R.string.local_source_dir_required);
            return;
        }
        LocalSourceHub.addScanDir(dir);
        binding.settingsDirInput.setText("");
        renderDirs();
        refreshCandidates();
    }

    private TextView hint(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(12f);
        return view;
    }

    // ==================== 扫描类型（勾选） ====================

    private void renderExtensions() {
        binding.settingsExtList.removeAllViews();
        // r16-⑤：勾选项＝扫描目录里实际存在的后缀（候选还没扫出来时先用全部类型占位）。
        List<String> candidates = extCandidates.isEmpty() ? LocalSourceHub.allExtensions() : extCandidates;
        List<String> selected = LocalSourceHub.getScanExtensions();
        // r25-①：勾选值为空在扫描逻辑里就等于「全部类型」，界面上必须画成全部勾选。
        // 以前直接 selected.contains(...) → 空列表时 11 个类型一个都不勾，用户看到「没勾任何类型」
        // 却仍然全类型扫描，误以为类型被漏掉（.json 源的入口问题就藏在这个语义差上）。
        boolean all = selected.isEmpty();
        for (String extension : candidates) {
            MaterialCheckBox check = new MaterialCheckBox(requireContext());
            // r65：JS 选项后补单字节括号说明（用户口径：道长 js 源必须放 JS 文件夹，cat 源必须放 cat 文件夹）。
            check.setText("js".equalsIgnoreCase(extension) ? getString(R.string.local_source_settings_js_folder_hint, extension) : extension);
            check.setChecked(all || selected.contains(extension));
            check.setOnCheckedChangeListener((button, checked) -> {
                // 从「空＝全部」的实际语义出发：先展开成全类型，再按本次点选增删，
                // 这样取消其中一个类型时，其余类型仍然是勾选状态。
                List<String> now = new ArrayList<>(LocalSourceHub.getScanExtensions());
                if (now.isEmpty()) now.addAll(candidates);
                if (checked) {
                    if (!now.contains(extension)) now.add(extension);
                } else {
                    now.remove(extension);
                }
                LocalSourceHub.setScanExtensions(now);
            });
            binding.settingsExtList.addView(check);
        }
        binding.settingsExtAll.setText(getString(R.string.local_source_settings_ext_all, all ? candidates.size() : selected.size(), candidates.size()));
    }

    private void toggleAllExtensions() {
        // r25-①：勾选为空＝全部类型（不可表示「一个都不扫」），按钮语义改为「全选」：
        // 一键写全类型，再按需单独取消某一类。
        List<String> candidates = extCandidates.isEmpty() ? LocalSourceHub.allExtensions() : extCandidates;
        LocalSourceHub.setScanExtensions(new ArrayList<>(candidates));
        renderExtensions();
    }

    // ==================== 忽略目录名 / 忽略文件名（勾选） ====================

    private void refreshCandidates() {
        if (!isAdded() || binding == null) return;
        binding.settingsCandidates.setText(getString(R.string.local_source_settings_list_loading));
        binding.settingsBlockedList.removeAllViews();
        new Thread(() -> {
            List<String> dirs = LocalSourceHub.dirCandidates();
            List<String> types = LocalSourceHub.extensionCandidates();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded() || binding == null) return;
                blockedCandidates.clear();
                blockedCandidates.addAll(dirs);
                extCandidates.clear();
                extCandidates.addAll(types);
                // r29-⑥：候选统计不再包含「忽略文件名」（该功能已取消）
                binding.settingsCandidates.setText(getString(R.string.local_source_settings_list_count, types.size(), dirs.size()));
                renderExtensions();
                renderBlocked();
            });
        }, "local-source-candidates").start();
    }

    private void renderBlocked() {
        binding.settingsBlockedList.removeAllViews();
        if (blockedCandidates.isEmpty()) {
            binding.settingsBlockedList.addView(hint(getString(R.string.local_source_settings_list_empty)));
            return;
        }
        List<String> selected = LocalSourceHub.split(LocalSourceHub.getBlockedDirs());
        for (String name : blockedCandidates) {
            binding.settingsBlockedList.addView(checkedRow(name, selected.contains(name), this::onBlockedChanged));
        }
    }

    private void onBlockedChanged(String name, boolean checked) {
        List<String> now = LocalSourceHub.split(LocalSourceHub.getBlockedDirs());
        if (checked) {
            if (!now.contains(name)) now.add(name);
        } else {
            now.remove(name);
        }
        LocalSourceHub.setBlockedDirs(TextUtils.join(",", now));
    }

    private void renderExcluded() {
        // r29-⑥：「忽略文件名」功能已取消（布局与读写方法均已删除）——本方法仅保留空实现占位，
        // 避免历史调用点残留；实际不再被 refreshCandidates 调用。
    }

    private View checkedRow(String name, boolean checked, NameListener listener) {
        MaterialCheckBox check = new MaterialCheckBox(requireContext());
        check.setText(name);
        check.setChecked(checked);
        check.setOnCheckedChangeListener((button, value) -> listener.onChanged(name, value));
        return check;
    }

    private interface NameListener {
        void onChanged(String name, boolean checked);
    }

    // ==================== 解析条目 ====================

    /**
     * r29-⑥④：解析源支持「增加 / 删除」。
     *
     * <p>用可重建的行列表：每行 = 启用勾选 + 名称 + 地址 + 删除按钮；
     * 底部「新增解析源」追加一条空行。点「确定」时按当前行内容整体写回。
     */
    private void showParses() {
        JsonArray entries = LocalSourceHub.getParseEntries().deepCopy();
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 18);
        column.setPadding(padding, padding / 3, padding, padding / 3);

        List<MaterialCheckBox> checks = new ArrayList<>();
        List<EditText> urls = new ArrayList<>();
        List<JsonObject> models = new ArrayList<>();

        for (JsonElement element : entries) {
            if (!element.isJsonObject()) continue;
            models.add(element.getAsJsonObject().deepCopy());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme);
        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            column.removeAllViews();
            checks.clear();
            urls.clear();
            for (int i = 0; i < models.size(); i++) {
                JsonObject entry = models.get(i);
                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.VERTICAL);
                LinearLayout head = new LinearLayout(requireContext());
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(android.view.Gravity.CENTER_VERTICAL);

                MaterialCheckBox check = new MaterialCheckBox(requireContext());
                check.setText(TextUtils.isEmpty(str(entry, "name")) ? getString(R.string.local_source_parse_unnamed) : str(entry, "name"));
                check.setChecked(bool(entry, "enabled"));
                check.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                MaterialButton remove = new MaterialButton(requireContext());
                remove.setText(R.string.local_source_parse_remove);
                remove.setMinWidth(0);
                remove.setMinimumWidth(0);
                final int index = i;
                remove.setOnClickListener(view -> {
                    syncParseModels(models, checks, urls);
                    if (index >= 0 && index < models.size()) models.remove(index);
                    render[0].run();
                });

                head.addView(check);
                head.addView(remove);
                column.addView(head);

                EditText url = new EditText(requireContext());
                url.setText(str(entry, "url"));
                url.setSingleLine(true);
                url.setTextSize(12f);
                column.addView(url);

                checks.add(check);
                urls.add(url);
            }
            if (models.isEmpty()) column.addView(hint(getString(R.string.local_source_settings_parses_empty)));

            MaterialButton add = new MaterialButton(requireContext());
            add.setText(R.string.local_source_parse_add);
            add.setOnClickListener(view -> {
                syncParseModels(models, checks, urls);
                JsonObject fresh = new JsonObject();
                fresh.addProperty("name", "解析源" + (models.size() + 1));
                fresh.addProperty("url", "");
                fresh.addProperty("enabled", true);
                models.add(fresh);
                render[0].run();
                if (holder[0] != null && holder[0].getWindow() != null) {
                    holder[0].getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            });
            column.addView(add);
        };
        render[0].run();

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(column);
        builder.setTitle(R.string.local_source_settings_parses)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                syncParseModels(models, checks, urls);
                JsonArray result = new JsonArray();
                for (JsonObject entry : models) {
                    if (TextUtils.isEmpty(str(entry, "url"))) continue; // 地址为空的行不写入
                    result.add(entry);
                }
                LocalSourceHub.setParseEntries(result);
                binding.settingsParses.setText(getString(R.string.local_source_settings_parses, result.size()));
            })
            .setNegativeButton(R.string.dialog_negative, null);
        holder[0] = builder.create();
        holder[0].show();
    }

    /** r29-⑥④：把界面上「当前行」的勾选 / 地址写回内存模型（增删行前后都要先同步一次）。 */
    private static void syncParseModels(List<JsonObject> models, List<MaterialCheckBox> checks, List<EditText> urls) {
        for (int i = 0; i < models.size() && i < checks.size() && i < urls.size(); i++) {
            JsonObject entry = models.get(i);
            entry.addProperty("enabled", checks.get(i).isChecked());
            String url = urls.get(i).getText() == null ? "" : urls.get(i).getText().toString().trim();
            entry.addProperty("url", url);
        }
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            return element == null || !element.isJsonPrimitive() || element.getAsBoolean();
        } catch (Throwable e) {
            return true;
        }
    }

    // ==================== 保存并加载（唯一动作） ====================

    private void saveFields() {
        // r29-⑤：保存自定义产物文件名（留空＝默认 Muse本地源）。
        LocalSourceHub.setOutName(binding.settingsOutName.getText() == null ? "" : binding.settingsOutName.getText().toString().trim());
        LocalSourceHub.setReplace(binding.settingsReplace.isChecked());
        LocalSourceHub.setSpiderJar(spiderJar);
        LocalSourceHub.setLiveUrl(binding.settingsLiveUrl.getText() == null ? "" : binding.settingsLiveUrl.getText().toString().trim());
        LocalSourceHub.setDecodeBase64(binding.settingsBase64.isChecked());
    }

    /**
     * 保存设置 → 生成 → 重载点播列表（唯一的动作按钮）。
     *
     * <p>生成在后台线程执行，回调里只使用 Application Context 的字符串与已捕获的 Activity，
     * 不再调用 {@code getString()} / {@code requireActivity()}（弹窗此时已被按钮关闭，
     * 之前正是这一步在弹窗 detach 后抛 IllegalStateException 把主线程打崩）。
     */
    /** MuseTV 权限线：同 LocalSourceDialog，生成前先走 WRITE 运行时权限，绝不走 MANAGE 路线。 */
    private void onSaveAndLoad() {
        saveFields();
        if (LocalSourceHub.getScanDirs().isEmpty()) {
            Notify.show(R.string.local_source_settings_dirs_empty);
            return;
        }
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
            // r16：一条源都没生成时，直接把「目录里实际有什么类型 / 当前勾了什么」摆出来，
            // 省得用户对着空列表猜是权限、路径还是勾选的问题。
            String message = (LocalSourceHub.getSiteCount() == 0
                ? appContext.getString(R.string.local_source_empty, LocalSourceHub.getDiagnosis())
                : appContext.getString(R.string.local_source_done, LocalSourceHub.getSummary())) + blockedWriteHint(appContext);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (changed != null) changed.run();
                Notify.show(message);
                reload();
            });
            }, "local-source-generate").start();
        });
    }

    /** r26-②：本轮「保存并加载」若因自写源保护跳过了自动刷新回填，补一句说明。 */
    private static String blockedWriteHint(Context context) {
        String blocked = LocalSourceHub.consumeBlockedWrite();
        return blocked == null || blocked.isEmpty() ? "" : "\n" + context.getString(R.string.local_source_write_blocked, blocked);
    }

    /**
     * r35-④：加载 = **把本壳生成的 json 设成「点播接口」并启用**（设置页「点播」那一栏会显示它），
     * 而不是像以前那样回到默认配置 —— 用户点一次「保存并加载」就能直接吃上刚生成的源。
     */
    private void reload() {
        final Activity host = getActivity();
        if (host == null || host.isFinishing()) return;
        java.io.File out = LocalSourceHub.outputFile();
        com.muse.android.tv.bean.Config config = out != null && out.isFile()
            ? com.muse.android.tv.bean.Config.find(com.muse.android.tv.utils.UrlUtil.toLocalUrl(android.net.Uri.fromFile(out)), 0)
            : null;
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
        if (config == null) VodConfig.get().init().load(callback);
        else VodConfig.load(config, callback);
    }
}
