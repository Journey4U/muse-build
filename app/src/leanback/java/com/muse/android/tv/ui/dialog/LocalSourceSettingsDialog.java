package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.databinding.DialogLocalSourceSettingsBinding;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.UrlUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 电视版「本地源生成设置」：数据操作与保存/生成/重载语义与手机版 LocalSourceSettingsDialog 一致，
 * 控件全部换成遥控器可聚焦的行：OK = 勾选/取消或进入编辑，文本项点开输入框改。
 *
 * <p>与手机版的差异只有交互外壳：目录勾选/删除合并在一行（OK 切换启用、长按删除），
 * 选目录走电视版 FolderTreeDialog（从内部存储根开始），解析条目的地址编辑走单独输入框（右键打开）。</p>
 */
public class LocalSourceSettingsDialog extends BaseAlertDialog {

    private DialogLocalSourceSettingsBinding binding;
    private Runnable changed;
    private String spiderJar = "";
    private String outName = "";
    private String dirPath = "";
    private String liveUrl = "";
    private final List<String> blockedCandidates = new ArrayList<>();
    private final List<String> extCandidates = new ArrayList<>();

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
        setOutName(LocalSourceHub.getOutName());
        setSpiderJar(LocalSourceHub.getSpiderJar());
        setLiveUrl(LocalSourceHub.getLiveUrl());
        paintDirPath();
        binding.settingsBase64.setChecked(LocalSourceHub.isDecodeBase64());
        binding.settingsParses.setText(getString(R.string.local_source_settings_parses, LocalSourceHub.getParseEntries().size()));
        renderDirs();
        renderExtensions();
        refreshCandidates();
    }

    @Override
    protected void initEvent() {
        binding.settingsDirAdd.setOnClickListener(view -> onAddDir());
        binding.settingsOutName.setOnClickListener(view -> inputText(R.string.local_source_settings_out_name, outName, value -> setOutName(value)));
        binding.settingsDirInput.setOnClickListener(view -> inputText(R.string.local_source_settings_dirs, dirPath, value -> { dirPath = value; paintDirPath(); }));
        binding.settingsLiveUrl.setOnClickListener(view -> inputText(R.string.local_source_settings_live_url, liveUrl, value -> setLiveUrl(value)));
        binding.settingsDirPick.setOnClickListener(view -> onPickDir());
        binding.settingsParses.setOnClickListener(view -> showParses());
        binding.settingsExtAll.setOnClickListener(view -> toggleAllExtensions());
        binding.settingsSpiderPick.setOnClickListener(view -> onPickSpiderJar());
        // 清除 = 清掉手动选择、回到自动探测（探到默认 jar 才写 spider，见 getSpiderJar）。
        binding.settingsSpiderClear.setOnClickListener(view -> setSpiderJar(LocalSourceHub.getDefaultSpiderJar()));
        binding.settingsLiveClear.setOnClickListener(view -> setLiveUrl(""));
    }

    // ==================== spider jar / 直播源地址 ====================

    // ==================== 文本值行（v11：裸 EditText 嵌滚动弹窗里遥控器落不进焦点，统一点开 inputText 小弹窗改） ====================

    private void setOutName(String value) {
        outName = value == null ? "" : value.trim();
        binding.settingsOutName.setText(outName);
    }

    private void setLiveUrl(String value) {
        liveUrl = value == null ? "" : value.trim();
        binding.settingsLiveUrl.setText(liveUrl);
    }

    private void paintDirPath() {
        binding.settingsDirInput.setText(TextUtils.isEmpty(dirPath) ? getString(R.string.local_source_dir_hint) : dirPath);
    }

    private void setSpiderJar(String path) {
        spiderJar = path == null ? "" : path.trim();
        binding.settingsSpiderValue.setText(TextUtils.isEmpty(spiderJar) ? getString(R.string.local_source_settings_spider_none) : spiderJar);
    }

    /** 从「已选扫描目录」里挑一个 .jar（不选则用默认 ./jar/xbpq251118.jar）。 */
    private void onPickSpiderJar() {
        // 同 onPickDir：候选要扫外部存储，先走同一条权限线，无权限不许只报「没找到 .jar」。
        PermissionUtil.requestFile(this, granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                Notify.show(R.string.local_source_permission_required);
                return;
            }
            new Thread(() -> {
                List<String> found = LocalSourceHub.jarCandidates();
                new Handler(Looper.getMainLooper()).post(() -> showJarList(found));
            }).start();
        });
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
            boolean enabled = LocalSourceHub.isDirEnabled(dir);
            TextView row = toggleRow((enabled ? "☑ " : "☐ ") + dir, v -> {
                LocalSourceHub.setDirEnabled(dir, !LocalSourceHub.isDirEnabled(dir));
                renderDirs();
                refreshCandidates();
            });
            // 长按删除该目录（遥控器长按 OK）；删除后候选随之刷新（与手机版同一口径）。
            row.setOnLongClickListener(v -> {
                LocalSourceHub.removeScanDir(dir);
                renderDirs();
                refreshCandidates();
                return true;
            });
            binding.settingsDirList.addView(row);
        }
    }

    private void onPickDir() {
        // 选择器要读外部存储：先按 MuseTV 权限线申请 WRITE 运行时权限再打开，
        // 无权限时目录列不出来、选择器只剩空壳——不能要求用户先去源配置那边开权限。
        PermissionUtil.requestFile(this, granted -> {
            if (!Boolean.TRUE.equals(granted)) {
                Notify.show(R.string.local_source_permission_required);
                return;
            }
            showDirPicker();
        });
    }

    private void showDirPicker() {
        List<File> roots = new ArrayList<>();
        File root = Path.root();
        if (root != null && root.isDirectory()) roots.add(root);
        File storage = new File("/storage");
        if (storage.isDirectory() && (root == null || !storage.getAbsolutePath().equals(root.getAbsolutePath()))) roots.add(storage);
        if (roots.isEmpty()) {
            Notify.show(R.string.local_source_dir_required);
            return;
        }
        FolderTreeDialog.showRoots(requireContext(), roots, R.string.local_source_settings_dir_pick,
            R.string.local_source_settings_dir_pick, R.string.dialog_positive, dir -> {
                LocalSourceHub.addScanDir(dir.getAbsolutePath());
                renderDirs();
                refreshCandidates();
            });
    }

    private void onAddDir() {
        String raw = dirPath;
        // 手动输入也走同一归一化：file:// 串折成绝对路径后入库，绝不存 file:// 写法。
        String dir = LocalSourceHub.normalizeScanDir(raw);
        if (TextUtils.isEmpty(dir)) {
            Notify.show(R.string.local_source_dir_required);
            return;
        }
        LocalSourceHub.addScanDir(dir);
        dirPath = "";
        paintDirPath();
        renderDirs();
        refreshCandidates();
    }

    // ==================== 扫描类型（勾选） ====================

    private void renderExtensions() {
        binding.settingsExtList.removeAllViews();
        // 勾选项＝扫描目录里实际存在的后缀（候选还没扫出来时先用全部类型占位）。
        List<String> candidates = extCandidates.isEmpty() ? LocalSourceHub.allExtensions() : extCandidates;
        List<String> selected = LocalSourceHub.getScanExtensions();
        // 勾选值为空在扫描逻辑里就等于「全部类型」，界面上必须画成全部勾选（与手机版同一口径）。
        boolean all = selected.isEmpty();
        for (String extension : candidates) {
            boolean checked = all || selected.contains(extension);
            String label = "js".equalsIgnoreCase(extension) ? getString(R.string.local_source_settings_js_folder_hint, extension) : extension;
            binding.settingsExtList.addView(toggleRow((checked ? "☑ " : "☐ ") + label, v -> {
                // 从「空＝全部」的实际语义出发：先展开成全类型，再按本次点选增删。
                List<String> now = new ArrayList<>(LocalSourceHub.getScanExtensions());
                if (now.isEmpty()) now.addAll(candidates);
                boolean nowChecked = LocalSourceHub.getScanExtensions().isEmpty() || LocalSourceHub.getScanExtensions().contains(extension);
                if (nowChecked) now.remove(extension);
                else if (!now.contains(extension)) now.add(extension);
                LocalSourceHub.setScanExtensions(now);
                renderExtensions();
            }));
        }
        binding.settingsExtAll.setText(getString(R.string.local_source_settings_ext_all, all ? candidates.size() : selected.size(), candidates.size()));
    }

    private void toggleAllExtensions() {
        // 勾选为空＝全部类型（不可表示「一个都不扫」），按钮语义＝一键全选（与手机版一致）。
        List<String> candidates = extCandidates.isEmpty() ? LocalSourceHub.allExtensions() : extCandidates;
        LocalSourceHub.setScanExtensions(new ArrayList<>(candidates));
        renderExtensions();
    }

    // ==================== 忽略目录名（勾选） ====================

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
            boolean checked = selected.contains(name);
            binding.settingsBlockedList.addView(toggleRow((checked ? "☑ " : "☐ ") + name, v -> onBlockedChanged(name, !checked)));
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
        renderBlocked();
    }

    // ==================== 解析条目 ====================

    /**
     * 解析源列表（遥控器版）：OK = 启用/停用，右键 = 编辑地址，长按 = 删除；
     * 最后一行「新增」追加一条。确定时地址为空的行不写入（与手机版一致）。
     */
    private void showParses() {
        List<JsonObject> models = new ArrayList<>();
        for (JsonElement element : LocalSourceHub.getParseEntries()) {
            if (element != null && element.isJsonObject()) models.add(element.getAsJsonObject().deepCopy());
        }
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            column.removeAllViews();
            for (int i = 0; i < models.size(); i++) {
                JsonObject entry = models.get(i);
                int index = i;
                boolean enabled = bool(entry, "enabled");
                String name = TextUtils.isEmpty(str(entry, "name")) ? getString(R.string.local_source_parse_unnamed) : str(entry, "name");
                TextView row = toggleRow((enabled ? "☑ " : "☐ ") + name, v -> {
                    entry.addProperty("enabled", !bool(entry, "enabled"));
                    render[0].run();
                    refocus(column, index);
                });
                row.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        inputText(R.string.local_source_settings_parses, str(entry, "url"), value -> {
                            entry.addProperty("url", value);
                        });
                        return true;
                    }
                    return false;
                });
                row.setOnLongClickListener(v -> {
                    models.remove(index);
                    render[0].run();
                    return true;
                });
                column.addView(row);
            }
            if (models.isEmpty()) column.addView(hint(getString(R.string.local_source_settings_parses_empty)));
            TextView add = toggleRow("+ " + getString(R.string.local_source_parse_add), v -> {
                JsonObject fresh = new JsonObject();
                fresh.addProperty("name", "解析源" + (models.size() + 1));
                fresh.addProperty("url", "");
                fresh.addProperty("enabled", true);
                models.add(fresh);
                render[0].run();
                refocus(column, models.size() - 1);
            });
            column.addView(add);
        };
        render[0].run();

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(column);
        new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.local_source_settings_parses)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                JsonArray result = new JsonArray();
                for (JsonObject entry : models) {
                    if (TextUtils.isEmpty(str(entry, "url"))) continue; // 地址为空的行不写入
                    result.add(entry);
                }
                LocalSourceHub.setParseEntries(result);
                binding.settingsParses.setText(getString(R.string.local_source_settings_parses, result.size()));
            })
            .setNegativeButton(R.string.dialog_negative, null)
            .show();
    }

    private void refocus(LinearLayout column, int index) {
        if (index >= 0 && index < column.getChildCount()) column.getChildAt(index).requestFocus();
    }

    private interface TextCallback {
        void onText(String value);
    }

    /** 单行文本输入小弹窗（电视软键盘可操作）。 */
    private void inputText(int titleRes, String current, TextCallback callback) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setText(current == null ? "" : current);
        if (current != null) input.setSelection(current.length());
        new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.dialog_positive, (dialog, which) -> callback.onText(input.getText() == null ? "" : input.getText().toString().trim()))
            .setNegativeButton(R.string.dialog_negative, null)
            .show();
    }

    // ==================== 行控件 ====================

    /** 可聚焦的勾选/动作行：焦点态高亮，OK 触发点击（遥控器与触屏一致）。 */
    private TextView toggleRow(String text, View.OnClickListener listener) {
        TextView row = new TextView(requireContext());
        row.setText(text);
        row.setTextSize(15f);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setOnClickListener(listener);
        row.setOnFocusChangeListener((v, hasFocus) -> v.setBackgroundColor(hasFocus ? 0x5533B5E5 : 0x00000000));
        row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView hint(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(12f);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        // 产物文件名可自定义（留空＝默认 Muse本地源）。
        LocalSourceHub.setOutName(outName);
        LocalSourceHub.setSpiderJar(spiderJar);
        LocalSourceHub.setLiveUrl(liveUrl);
        LocalSourceHub.setDecodeBase64(binding.settingsBase64.isChecked());
    }

    /** MuseTV 权限线：同手机版，生成前先走 WRITE 运行时权限，绝不走 MANAGE 路线。 */
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
                String message = (LocalSourceHub.getSiteCount() == 0
                    ? appContext.getString(R.string.local_source_empty, LocalSourceHub.getDiagnosis())
                    : appContext.getString(R.string.local_source_done, LocalSourceHub.getSummary())) + blockedWriteHint(appContext);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (changed != null) changed.run();
                    Notify.show(message);
                    reload(host);
                });
            }, "local-source-generate").start();
        });
    }

    /** 本轮「保存并加载」若因自写源保护跳过了自动刷新回填，补一句说明。 */
    private static String blockedWriteHint(Context context) {
        String blocked = LocalSourceHub.consumeBlockedWrite();
        return blocked == null || blocked.isEmpty() ? "" : "\n" + context.getString(R.string.local_source_write_blocked, blocked);
    }

    /** 加载 = 把本壳生成的 json 设成「点播接口」并启用（与手机版设置页 reload 同口径）。 */
    private void reload(Activity host) {
        if (host == null || host.isFinishing()) return;
        File out = LocalSourceHub.outputFile();
        com.muse.android.tv.bean.Config config = out != null && out.isFile()
            ? com.muse.android.tv.bean.Config.find(UrlUtil.toLocalUrl(Uri.fromFile(out)), 0)
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

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.6f);
    }
}
