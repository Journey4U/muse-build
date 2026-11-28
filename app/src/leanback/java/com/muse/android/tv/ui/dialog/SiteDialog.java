package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.util.TypedValue;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.DialogSiteBinding;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.ui.activity.FileActivity;
import com.muse.android.tv.ui.adapter.SiteAdapter;
import com.muse.android.tv.utils.MoveHistory;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    /**
     * 弹窗尺寸用 dp 钉死（Liu 2026-11-28 17:37「框给我钉死，不要按什么80%」）：宽 896dp、高 376dp，
     * 与胶囊、关键字框、行距同一单位（原来框按屏幕真实像素×比例、胶囊按 dp，AutoSize 密度一变行距就乱）。
     * 1920×1080 电视（密度 2）= 1792×752px，1280×720 电视（密度 4/3）= 1195×501px（v55 为 1194×501，四舍五入差 1px）。
     * 唯一兜底：超出屏幕可用宽高时才夹到屏幕（只防越界，不按比例）。不按内容或源数量算。
     */
    private static final float DIALOG_W_DP = 896f;
    private static final float DIALOG_H_DP = 376f;
    /** 固定 4 列（废除「源少于 10 个自动单列」与单列/网格切换）。 */
    private static final int COLUMNS = 4;
    /** 胶囊恢复原版尺寸：高 40dp（字 18sp、勾选框 24dp 在布局里），不随弹窗比例缩放；关键字框与胶囊同高。 */
    private static final float CHIP_DP = 40f;
    /** 行距最小 4dp；只有屏幕矮到放不下时才按比例缩胶囊高度兜底（字号不缩）。 */
    private static final float MIN_GAP_DP = 4f;

    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private boolean action;
    private boolean keyword;
    private int type;
    private int dialogW;
    private int dialogH;
    /** 行距（px）：列表高度减去行数×胶囊高，平均分给行与行之间。 */
    private int rowGap;
    private ViewTreeObserver touchObserver;
    private final ViewTreeObserver.OnTouchModeChangeListener touchModeListener = inTouch -> {
        // 触屏/遥控器切换时重绑一次：触屏下当前源显示白框，遥控器下白框随焦点走，不会出现两个白框。
        if (adapter != null) adapter.notifyItemRangeChanged(0, adapter.getItemCount());
    };
    /** 从源区按右键进按钮列时记下的格子，按钮列按左键回到这里。 */
    private int lastPosition = -1;

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

    private void computeSize() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        dialogW = Math.min(dp(DIALOG_W_DP), metrics.widthPixels);
        dialogH = Math.min(dp(DIALOG_H_DP), metrics.heightPixels);
    }

    private int dp(float value) {
        return Math.max(1, Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics())));
    }

    /** 可见行数：只有首页带关键字框放 5 行；搜索页、设置页没有关键字框，放 6 行。 */
    private int getRows() {
        return keyword ? 5 : 6;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        // 去掉 Material 默认的透明外边距，让看到的弹窗本体就是定死的宽高。
        return builder().setBackgroundInsetStart(0).setBackgroundInsetEnd(0).setBackgroundInsetTop(0).setBackgroundInsetBottom(0).setView(getBinding().getRoot());
    }

    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // 先定尺寸再建窗（super 里会调 initView → applySize，要用到 dialogW/dialogH）。
        computeSize();
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        // 宽高在显示前一次定死，之后不再改：不跟内容走、不重排。
        // 只由屏幕尺寸决定，与源数量无关（源数量只影响填了几格）。
        if (dialog.getWindow() != null) {
            WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = dialogW;
            params.height = dialogH;
            dialog.getWindow().setAttributes(params);
            // 触屏模式下格子不再抢焦点，关键字框可能拿到焦点：打开时不自动弹输入法。
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        return dialog;
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this);
        if (action) binding.action.setVisibility(View.VISIBLE);
        binding.keywordLayout.setVisibility(keyword ? View.VISIBLE : View.GONE);
        applySize();
        setType(type);
        setRecyclerView();
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

    /** 所有尺寸一律 dp：内边距、关键字框、格子、间距、按钮列。全是固定数，不量内容。 */
    private void applySize() {
        int pad = dp(24);
        binding.getRoot().setPadding(pad, pad, pad, pad);
        int recyclerH = dialogH - pad * 2;
        if (keyword) {
            // 关键字框与胶囊同高；浮动标签已在布局里关掉（hintEnabled=false），「关键字...」作占位提示，描边样式保留。
            int kh = dp(CHIP_DP);
            int kgap = dp(8);
            ViewGroup.MarginLayoutParams klp = (ViewGroup.MarginLayoutParams) binding.keywordLayout.getLayoutParams();
            klp.height = kh;
            klp.bottomMargin = kgap;
            binding.keywordLayout.setLayoutParams(klp);
            binding.keyword.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            binding.keyword.setMinHeight(0);
            binding.keyword.setMinimumHeight(0);
            binding.keyword.setPadding(dp(16), 0, dp(16), 0);
            ViewGroup.LayoutParams elp = binding.keyword.getLayoutParams();
            elp.height = kh;
            binding.keyword.setLayoutParams(elp);
            recyclerH -= kh + kgap;
        }
        ViewGroup.LayoutParams rlp = binding.recycler.getLayoutParams();
        rlp.height = recyclerH;
        binding.recycler.setLayoutParams(rlp);
        // 胶囊用原版尺寸；行数只看屏幕（首页 5 行，搜索页/设置页 6 行），多出来的高度平均分给行距。
        int rows = getRows();
        int chipH = dp(CHIP_DP);
        int minGap = dp(MIN_GAP_DP);
        int gapV = (recyclerH - rows * chipH) / (rows - 1);
        if (gapV < minGap) {
            gapV = minGap;
            chipH = Math.max(1, (recyclerH - (rows - 1) * gapV) / rows);
            adapter.setItemHeight(chipH);
        } else {
            adapter.setItemHeight(0);
        }
        rowGap = gapV;
        if (action) {
            ViewGroup.MarginLayoutParams alp = (ViewGroup.MarginLayoutParams) binding.action.getLayoutParams();
            alp.setMarginStart(dp(16));
            binding.action.setLayoutParams(alp);
            View[] buttons = {binding.search, binding.change, binding.select, binding.cancel};
            for (int i = 0; i < buttons.length; i++) {
                ViewGroup.MarginLayoutParams blp = (ViewGroup.MarginLayoutParams) buttons[i].getLayoutParams();
                blp.width = dp(40);
                blp.height = dp(40);
                blp.topMargin = i == 0 ? 0 : dp(16);
                buttons[i].setLayoutParams(blp);
                buttons[i].setPadding(dp(8), dp(8), dp(8), dp(8));
            }
        }
    }

    @Override
    protected void initEvent() {
        binding.select.setOnClickListener(v -> adapter.selectAll());
        binding.cancel.setOnClickListener(v -> adapter.cancelAll());
        binding.search.setOnClickListener(v -> setType(v.isSelected() ? 0 : 1));
        binding.change.setOnClickListener(v -> setType(v.isSelected() ? 0 : 2));
        adapter.setKeyListener(this::onItemKey);
        View.OnKeyListener buttonKey = this::onButtonKey;
        binding.search.setOnKeyListener(buttonKey);
        binding.change.setOnKeyListener(buttonKey);
        binding.select.setOnKeyListener(buttonKey);
        binding.cancel.setOnKeyListener(buttonKey);
    }

    /** 源区遥控器：左右到头停住；最右一列按右键进按钮列（只设置页有）；没有关键字框时顶行按上停住；最后一行按下停住。 */
    private boolean onItemKey(View view, int keyCode, KeyEvent event) {
        int pos = binding.recycler.getChildAdapterPosition(view);
        int count = adapter.getItemCount();
        if (pos < 0 || count == 0) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int col = pos % COLUMNS;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return col == 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col == COLUMNS - 1 || pos == count - 1) {
                    if (action && down) {
                        lastPosition = pos;
                        focusNearestButton(view);
                    }
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_UP:
                return pos < COLUMNS && !keyword;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return pos / COLUMNS == (count - 1) / COLUMNS;
            default:
                return false;
        }
    }

    private void focusNearestButton(View item) {
        int[] loc = new int[2];
        item.getLocationOnScreen(loc);
        int centerY = loc[1] + item.getHeight() / 2;
        View[] buttons = {binding.search, binding.change, binding.select, binding.cancel};
        View best = buttons[0];
        int bestDist = Integer.MAX_VALUE;
        for (View b : buttons) {
            b.getLocationOnScreen(loc);
            int dist = Math.abs(loc[1] + b.getHeight() / 2 - centerY);
            if (dist < bestDist) {
                bestDist = dist;
                best = b;
            }
        }
        best.requestFocus();
    }

    /** 按钮列遥控器：上下由布局 nextFocus 管、到头停住；左键回到刚离开的格子；右键停住。 */
    private boolean onButtonKey(View view, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) focusPosition(lastPosition);
        return true;
    }

    private void focusPosition(int position) {
        int count = adapter.getItemCount();
        if (count == 0) return;
        int target = position;
        if (target < 0 || target >= count) {
            RecyclerView.LayoutManager lm = binding.recycler.getLayoutManager();
            target = lm instanceof GridLayoutManager ? Math.max(0, ((GridLayoutManager) lm).findFirstCompletelyVisibleItemPosition()) : 0;
        }
        RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(target);
        if (holder != null) {
            holder.itemView.requestFocus();
        } else {
            binding.recycler.scrollToPosition(target);
        }
    }

    private void setRecyclerView() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.addItemDecoration(new GridGap(COLUMNS, dp(16), rowGap));
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), COLUMNS));
    }

    private void setType(int type) {
        binding.search.setSelected(type == 1);
        binding.change.setSelected(type == 2);
        binding.select.setClickable(type > 0);
        binding.cancel.setClickable(type > 0);
        adapter.setType(this.type = type);
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
        int home = VodConfig.getHomeIndex();
        if (binding.recycler.isInTouchMode()) {
            // 触屏：只滚到当前源，不抢焦点（抢焦点会让手划时列表被往回拽）。
            RecyclerView.LayoutManager lm = binding.recycler.getLayoutManager();
            if (lm instanceof GridLayoutManager && home >= 0) ((GridLayoutManager) lm).scrollToPositionWithOffset(home, 0);
        } else {
            // 遥控器：与原来一致，滚到当前源并把焦点给它；不让搜索框在打开时抢焦点。
            binding.recycler.scrollToPosition(home);
            binding.recycler.requestFocus();
        }
        touchObserver = binding.getRoot().getViewTreeObserver();
        if (touchObserver.isAlive()) touchObserver.addOnTouchModeChangeListener(touchModeListener);
    }

    @Override
    public void onStop() {
        if (touchObserver != null && touchObserver.isAlive()) touchObserver.removeOnTouchModeChangeListener(touchModeListener);
        touchObserver = null;
        super.onStop();
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

    // 移动选目标：打开文件管理页的选目录模式（根 = 已保存的扫描目录，按钮「移动到此处」），选好后照原逻辑搬文件
    private List<Site> pendingMoveTargets;
    private final ActivityResultLauncher<Intent> moveLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onMoveTargetPicked);

    /** v27：单条移动——选目的文件夹，搬文件后重载生成新配置（主线 v35 同款逻辑）。2026-11-28 起选目录走文件管理页（原树形弹窗）。 */
    private void chooseMoveTargetForSelection(List<Site> targets) {
        Context context = getContext();
        if (context == null || targets.isEmpty()) return;
        List<File> roots = new ArrayList<>();
        for (String scan : LocalSourceHub.getScanDirs()) {
            if (TextUtils.isEmpty(scan)) continue;
            File root = new File(scan);
            if (root.isDirectory() && !roots.contains(root)) roots.add(root);
        }
        if (roots.isEmpty()) {
            Notify.show(R.string.site_folder_pick_empty);
            return;
        }
        pendingMoveTargets = new ArrayList<>(targets);
        moveLauncher.launch(FileActivity.folderPicker(context, roots, R.string.site_move_here));
    }

    private void onMoveTargetPicked(ActivityResult result) {
        List<Site> targets = pendingMoveTargets;
        pendingMoveTargets = null;
        File dir = FileActivity.pickedDir(result);
        if (dir == null || targets == null || targets.isEmpty() || !isAdded()) return;
        moveSelectedToDir(targets, dir.getAbsolutePath());
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

    /** 格子间距（px，跟弹窗等比换算）：横向 h、纵向 v，固定 span 列。 */
    private static class GridGap extends RecyclerView.ItemDecoration {

        private final int span;
        private final int h;
        private final int v;

        GridGap(int span, int h, int v) {
            this.span = span;
            this.h = h;
            this.v = v;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            if (position < 0) {
                outRect.set(0, 0, 0, 0);
                return;
            }
            int column = position % span;
            outRect.left = column * h / span;
            outRect.right = h - (column + 1) * h / span;
            outRect.top = position >= span ? v : 0;
            outRect.bottom = 0;
        }
    }
}
