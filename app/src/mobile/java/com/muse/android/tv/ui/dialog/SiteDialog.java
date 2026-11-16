package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.appcompat.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Window;
import android.widget.TextView;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.App;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.FileUtil;
import com.muse.android.tv.utils.MoveHistory;
import com.muse.android.tv.databinding.DialogSiteBinding;
import com.muse.android.tv.ui.fragment.SiteType;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.ui.adapter.SiteAdapter;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    /** 列数持久化 key，默认 1 列。 */
    private static final String KEY_COLUMN = "site_dialog_column";
    /** 上次选中的分类名（重开弹窗要保持选中）。 */
    private static final String KEY_GROUP = "site_dialog_group";
    /** 内存兜底（Prefers 万一没写进去也能保持选中）。 */
    private static String lastGroup = "";
    private static final int MAX_COLUMN = 3;

    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private List<String> groups;
    private String group;
    /** 1.0.3-⑧ 搜索过滤 160ms 合并节流的 Handler/待执行任务。 */
    private final Handler filterHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;
    private boolean search;
    private boolean change;
    private boolean searchMark;
    private int spanCount;
    /** r123：当前打开的「选定当前目标」菜单；null 表示没开。 */
    private AlertDialog actionMenu;
    /** r62-③：「失效列表」分类名（源分类 = 所在文件夹名，所以目录名直接用它）。 */
    private static final String FAIL_DIR_NAME = "失效列表";

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        search = true;
        return this;
    }

    public SiteDialog change() {
        change = true;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
        if (fragment instanceof SiteListener) listener = (SiteListener) fragment;
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
    protected void initView() {
        // 恢复上次选中的分类（静态字段兜底，避免 Prefers 未就绪时丢失）
        lastGroup = Prefers.getString(KEY_GROUP, lastGroup);
        group = lastGroup;
        groups = new ArrayList<>();
        searchMark = true;
        // 列数从 Prefers 读取，切换后写回，下次打开沿用
        spanCount = clamp(Prefers.getInt(KEY_COLUMN, 1));
        adapter = new SiteAdapter(this);
        binding.recycler.setAdapter(adapter);
        adapter.search(search).change(change);
        setRecyclerView();
        initKeyword();
        initMode();
        initSelection();
        initGroup();
        // 过滤前先校验「上次选中的分类」在当前配置里还存不存在（换配置后旧分类可能已消失）
        normalizeGroup();
        refreshGroupSelected();
        applyFilter();
        scrollGroupToSelected();
        // 列表滚动定位到正在用的那个源（蓝底 + 蓝点那条），重开弹窗也能一眼看到
        scrollToCurrentSite();
        updateSelectionUi();
    }

    /**
     * 分类残留自愈：持久化的分类不在当前配置的分类集合里就退回「全部」并写回，
     * 否则会出现「分类栏有标签、列表恒为空」。
     */
    private void normalizeGroup() {
        if (group == null) group = "";
        if (group.isEmpty()) return;
        if (groups != null && groups.contains(group)) return;
        group = "";
        lastGroup = "";
        Prefers.put(KEY_GROUP, "");
    }

    /** 过滤 + 兜底：分类把列表筛空了就自动退回「全部」，绝不把空列表丢给用户。 */
    private void applyFilter() {
        adapter.filter(group, binding.keyword.getText().toString());
        if (adapter.getItemCount() > 0 || adapter.getItems().isEmpty()) return;
        if (!group.isEmpty()) {
            group = "";
            lastGroup = "";
            Prefers.put(KEY_GROUP, "");
            refreshGroupSelected();
            adapter.filter(group, binding.keyword.getText().toString());
        }
    }

    /**
     * r38-①：**自适应 + 长列表定高**。
     *
     * <p>卡顿真因：RecyclerView 用 {@code wrap_content} 时会一次性 measure/layout 全部子项（不虚拟化），
     * 源上千个就要几百毫秒；但短列表用固定高度又难看。所以：条目多（>12）才切到固定高度走按需渲染，
     * 条目少保持 wrap_content 自适应。</p>
     */
    private void applyListHeight() {
        if (binding == null || adapter == null) return;
        int count = adapter.getItemCount();
        int max = listMaxHeightPx();
        binding.recycler.setMaxHeight(max); // MuseTV 的 CustomRecyclerView 无 getMaxHeight，直接设值（幂等）
        android.view.ViewGroup.LayoutParams params = binding.recycler.getLayoutParams();
        // r133：勾选模式下按钮必须留在屏内。以前 count<=12 走 WRAP_CONTENT，12 条 × 行高
        // 直接把底部「选定当前目标」顶出屏幕；而 >12 条反而走固定 360dp 正常 ——
        // 于是出现「只有某个分类（条目 9~12 条的『测试类』）没有确认按钮」的怪现象。
        int target;
        if (adapter.isSelecting()) {
            target = Math.min(ResUtil.dp2px(360), max);
        } else {
            target = count > 12 ? Math.min(ResUtil.dp2px(360), max) : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        if (params != null && params.height != target) {
            params.height = target;
            binding.recycler.setLayoutParams(params);
        }
    }

    /**
     * r101：列表高度上限 = 弹窗可用高度 - 顶部（标题行/分类 chips/内边距）- 底部（勾选栏/确认按钮）。
     *
     * <p>真机 bug（用户报「偶尔没有弹出『选定当前目标』」）：条目 ≤12 时列表走 wrap_content，
     * 12 条 × 行高就足以把弹窗顶出屏幕，底部的「已勾选 N 项」与「选定当前目标」被挤到可视区之外
     * —— 表现为勾选之后按钮压根不出现，无法下一步。条数刚好卡在临界值、或系统字体放大时才复现，
     * 所以看起来是偶发。现在无论条数多少，列表都被这条上限钳住，底部操作栏一定留在屏内。</p>
     */
    private int listMaxHeightPx() {
        int avail = ResUtil.getScreenHeight() - ResUtil.dp2px(96); // 状态栏 + 弹窗上下留白
        int fixed = ResUtil.dp2px(56 + 12 + 48 + 16);              // 标题行 + 间距 + 分类 chips + 内边距
        if (adapter != null && adapter.isSelecting()) fixed += ResUtil.dp2px(52 + 60 + 12); // 勾选栏 + 确认按钮 + 间距
        int list = Math.max(ResUtil.dp2px(140), avail - fixed);
        return Math.min(list, ResUtil.dp2px(500));
    }

    private void setRecyclerView() {
        adapter.setSpanCount(spanCount);
        adapter.notifyDataSetChanged();
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        while (binding.recycler.getItemDecorationCount() > 0) binding.recycler.removeItemDecorationAt(0);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(spanCount, spanCount == 1 ? 8 : 4));
    }

    /** 右上角按钮：block=切换「显示每个源是否可搜索」标识（仅搜索模式可用）；search=切换 1/2/3 列展示。 */
    private void initMode() {
        binding.block.setVisibility(search ? View.VISIBLE : View.GONE);
        binding.block.setOnClickListener(v -> toggleSearchMark());
        binding.search.setOnClickListener(v -> toggleSpanCount());
        updateModeIcons();
    }

    private void toggleSearchMark() {
        searchMark = !searchMark;
        adapter.searchMark(searchMark);
        adapter.notifyDataSetChanged();
        updateModeIcons();
    }

    private void toggleSpanCount() {
        spanCount = spanCount % MAX_COLUMN + 1;
        // 列数切换后永久保存
        Prefers.put(KEY_COLUMN, spanCount);
        setRecyclerView();
        updateModeIcons();
    }

    private int clamp(int column) {
        return Math.min(MAX_COLUMN, Math.max(1, column));
    }

    private void updateModeIcons() {
        binding.block.setImageResource(searchMark ? R.drawable.ic_site_search : R.drawable.ic_site_hidden);
        binding.search.setImageResource(spanCount == 1 ? R.drawable.ic_site_single_column : spanCount == 2 ? R.drawable.ic_site_two_column : R.drawable.ic_site_three_column);
        binding.block.setSelected(searchMark);
        binding.search.setSelected(spanCount > 1);
    }

    private void initKeyword() {
        binding.keyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleFilter();
            }
        });
    }

    /**
     * 筛选标签 = 文件夹分类（Liu 2026-11-11 定）：从站名 [] 提取，按钮显示
     * [文件夹名] 形式；大小写视为同一个（pq 与 PQ 合并成一个按钮，点它两种
     * 写法的源都出来）。源类型不在源列表里分——按类型找源走长按搜索的
     * 「按源类型」。groups 里存 "[文件夹名]" 形式的值。
     */
    private void initGroup() {
        groups = SiteType.presentCategories(adapter.getItems());
        if (groups.isEmpty()) {
            binding.groupScroll.setVisibility(View.GONE);
            return;
        }
        binding.groupScroll.setVisibility(View.VISIBLE);
        binding.groupList.removeAllViews();
        addGroup(binding.groupList, getString(R.string.site_group_all), "");
        for (String item : groups) addGroup(binding.groupList, item, item);
    }

    private void addGroup(LinearLayoutCompat parent, String text, String value) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setTextSize(14);
        button.setTextColor(AppCompatResources.getColorStateList(requireContext(), R.color.dialog_outlined_button_text));
        button.setBackgroundTintList(AppCompatResources.getColorStateList(requireContext(), R.color.dialog_outlined_button_bg));
        button.setStrokeColor(AppCompatResources.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
        button.setSelected(value.equals(group));
        int padH = ResUtil.dp2px(14);
        int padV = ResUtil.dp2px(6);
        button.setPadding(padH, padV, padH, padV);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(ResUtil.dp2px(8));
        button.setLayoutParams(params);
        button.setOnClickListener(v -> {
            this.group = value;
            // 切换即记住，下次打开弹窗保持选中（静态字段 + Prefers 双保险）
            lastGroup = value;
            Prefers.put(KEY_GROUP, value);
            refreshGroupSelected();
            adapter.filter(this.group, binding.keyword.getText().toString());
            binding.recycler.scrollToPosition(0);
        });
        parent.addView(button);
    }

    /** 分类栏自动滚动到选中的那个分类（重开弹窗后能立刻看到自己在哪一类）。 */
    private void scrollGroupToSelected() {
        if (binding == null || groups == null) return;
        for (int i = 0; i < binding.groupList.getChildCount(); i++) {
            View child = binding.groupList.getChildAt(i);
            String value = i == 0 ? "" : (i - 1 < groups.size() ? groups.get(i - 1) : "");
            if (value.equals(group)) {
                final View target = child;
                binding.groupScroll.post(() -> binding.groupScroll.smoothScrollTo(
                    Math.max(0, target.getLeft() - ResUtil.dp2px(24)), 0));
                break;
            }
        }
    }

    /** 把列表滚动到「正在用的那个源」（蓝底 + 左侧蓝点那条）。 */
    private void scrollToCurrentSite() {
        try {
            List<Site> shown = adapter.getFiltered();
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).isSelected()) {
                    final int position = i;
                    binding.recycler.post(() -> {
                        try {
                            binding.recycler.scrollToPosition(position);
                        } catch (Throwable ignored) {
                        }
                    });
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshGroupSelected() {
        for (int i = 0; i < binding.groupList.getChildCount(); i++) {
            View child = binding.groupList.getChildAt(i);
            String value = i == 0 ? "" : groups.get(i - 1);
            boolean on = value.equals(group);
            child.setSelected(on);
            // 选中态必须看得出来：直接给硬配色（蓝底白字），不依赖 color state list 的 selected 状态
            if (child instanceof MaterialButton) {
                MaterialButton button = (MaterialButton) child;
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(on ? 0xFF1E88E5 : 0xFFF2F3F5));
                button.setTextColor(on ? 0xFFFFFFFF : 0xFF444444);
                button.setStrokeColor(android.content.res.ColorStateList.valueOf(on ? 0xFF1E88E5 : 0xFFD5D8DC));
            }
        }
    }

    @Override
    public void onTextClick(Site item) {
        // r40-②：进入勾选模式后，**短按**也能勾选/取消（不必再长按）
        if (adapter.isSelecting()) {
            adapter.toggleChecked(item);
            updateSelectionUi();
            return;
        }
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onSearchClick(int position, Site item) {
        item.setSearchable(!item.isSearchable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public void onChangeClick(int position, Site item) {
        item.setChangeable(!item.isChangeable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public boolean onSearchLongClick(Site item) {
        boolean result = !item.isSearchable();
        adapter.getItems().forEach(site -> site.setSearchable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public boolean onChangeLongClick(Site item) {
        boolean result = !item.isChangeable();
        adapter.getItems().forEach(site -> site.setChangeable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }


    /** 1.0.3-⑧ 搜索列表 160ms 合并批量：连续输入只在停稳 160ms 后刷一次，且最后一次一定执行（trailing flush），避免每次按键全表复制 + 刷新。 */
    private void scheduleFilter() {
        if (pendingFilter != null) filterHandler.removeCallbacks(pendingFilter);
        pendingFilter = this::applyFilter;
        filterHandler.postDelayed(pendingFilter, 160);
    }

    /**
     * r35-⑤⑥：长按源名 = 进入**勾选（多选）模式**并勾上这一项。
     *
     * <p>原来的「移动到其他分类 / 删除源文件」弹窗已取消：现在长按只负责勾选，
     * 底部会出现 全选 / 反选 / 全不选 / 取消 与「选定当前目标」；点「选定当前目标」再决定
     * 要做什么（增加新分类 / 移动到其他分类 / 删除源文件）。</p>
     */
    @Override
    public boolean onTextLongClick(Site item) {
        if (getContext() == null) return true;
        if (!adapter.isSelecting()) adapter.setSelecting(true);
        adapter.toggleChecked(item);
        updateSelectionUi();
        return true;
    }

    /** 勾选操作栏的接线与显隐。 */
    private void initSelection() {
        binding.selectAll.setOnClickListener(v -> {
            adapter.checkAll();
            updateSelectionUi();
        });
        binding.selectInvert.setOnClickListener(v -> {
            adapter.invertChecked();
            updateSelectionUi();
        });
        binding.selectNone.setOnClickListener(v -> {
            adapter.clearChecked();
            updateSelectionUi();
        });
        binding.selectCancel.setOnClickListener(v -> exitSelection());
        binding.selectConfirm.setOnClickListener(v -> onActOnSelection());
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        boolean selecting = adapter.isSelecting();
        int count = adapter.getChecked().size();
        binding.selectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        // r132：进入勾选模式就必显示「选定当前目标」。以前这里绑的是 selecting && count>0，
        // 只要勾选没落进集合（条目 key 为空、长按被别的监听吃掉、或集合被清过），
        // 整条按钮就消失，用户看到的就是「选中了源但没有确认按钮」。
        // 现在 0 项时置灰但仍可见，点了会提示先勾选。
        binding.selectConfirm.setVisibility(selecting ? View.VISIBLE : View.GONE);
        binding.selectConfirm.setEnabled(!selecting || count > 0);
        binding.selectConfirm.setAlpha(selecting && count == 0 ? 0.45f : 1f);
        binding.selectCount.setText(getString(R.string.site_select_hint, count));
        // r101：勾选栏显隐改变列表可用高度 —— 重新钳制，保证「选定当前目标」始终留在屏内。
        applyListHeight();
        // r101：勾选计数行本身也可点 = 第二个「选定当前目标」入口（按钮贴近屏幕边缘时的兜底）。
        if (selecting && count > 0) binding.selectCount.setOnClickListener(v -> onActOnSelection());
        else binding.selectCount.setOnClickListener(null);
    }

    private void exitSelection() {
        adapter.setSelecting(false);
        // r123：退出勾选模式 = 离开「选定当前目标」这一层，菜单也该收起来
        dismissActionMenu();
    }

    /**
     * r123：收起「选定当前目标」菜单（幂等）。
     *
     * <p>菜单改成「点某一项后不自动关闭」之后，必须有人负责收尾：只在操作真的落地
     * （退出勾选模式 / 搬家后重载列表）时才收；用户中途取消就一直留着，形成返回层级。</p>
     */
    private void dismissActionMenu() {
        androidx.appcompat.app.AlertDialog dialog = actionMenu;
        actionMenu = null;
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 「选定当前目标」→ 选择要对勾选的源做什么。 */
    private void onActOnSelection() {
        // 我方 SiteAdapter 的勾选集存的是 Site 本体、且与当前筛选无关，getChecked() 返回的
        // 天然就是原版 r101 要的「全量」目标（原版按 key 存勾选、还要反查全量集合，我方不需要）。
        List<Site> targets = adapter.getChecked();
        if (targets.isEmpty()) {
            Notify.show(R.string.site_select_empty);
            return;
        }
        Context context = getContext();
        if (context == null) return;
        // r102：动作排序按「作用对象」渐进 —— 先改这个源自身，再动它所在的位置/分类，
        // 然后是跨端输出，最后才是不可恢复的删除。破坏性动作永远放最后一条。
        String[] actions = new String[]{
            // ① 作用在源自身
            getString(R.string.site_action_rename_source),
            // ② 作用在源的位置/分类
            getString(R.string.site_action_move),
            getString(R.string.site_action_new_category),
            // r62-②：回退到上一次分类（搬家前的分类目录）
            getString(R.string.site_action_restore),
            // ③ 作用在分类文件夹
            getString(R.string.site_action_rename_folder),
            // r62f：分享给他人（微信 / QQ 等）
            getString(R.string.site_action_share),
            // ④ 破坏性动作压到最后
            getString(R.string.site_action_delete)};
        // r123：重建菜单前先收掉旧的，避免连点两次「选定当前目标」叠出两层一模一样的菜单
        dismissActionMenu();
        // r123：**点击某一项后不再自动关闭菜单** —— 子流程（重命名 / 移动 / 目录树…）
        // 叠在这层菜单之上，用户在子界面按返回或点取消时自然回到这一层，
        // 而不是一下子掉回最初的源搜索列表。
        final androidx.appcompat.app.AlertDialog menu = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_target_title)
            .setItems(actions, null)
            .setNegativeButton(R.string.dialog_negative, null)
            .create();
        menu.show();
        actionMenu = menu;
        menu.setOnDismissListener(d -> {
            if (actionMenu == menu) actionMenu = null;
        });
        menu.getListView().setOnItemClickListener((parent, view, position, id) -> {
            // r102 索引与 actions 顺序一一对应；r123 起这里不再 dismiss()
            if (position == 0) renameSourceFlow(targets);
            else if (position == 1) chooseMoveTargetForSelection(targets);
            else if (position == 2) createCategoryFlow();
            else if (position == 3) restoreSelected(targets);
            else if (position == 4) renameFolderFlow();
            else if (position == 5) shareSelected(targets);
            else confirmDeleteSelected(targets);
        });
    }

    /**
     * r101：删除源文件前的**二次确认**（用户要求：不要在选中后一点就直接删掉磁盘文件）。
     *
     * <p>批量删除会真删磁盘上的源文件并回写 json，不可恢复，所以先弹确认框；
     * 确认后再走 {@link #deleteSelected(List)}。</p>
     */
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

    /** r29-⑧ 自写源提示浮层（照搬 VesperTV：宽度随内容自适应、上限屏宽 86%、2 秒自动消失、点任意处提前关闭；
     * 原来 Notify Toast 在 Android 12+ 会被截断成两行）。 */
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

    // ==================== r102：重命名（源文件 / 分类文件夹） ====================

    /**
     * 重命名源文件：一次只改一个文件名，多选时取第一个并明确提示，避免用户以为整批都改了。
     */
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
        android.widget.EditText input = new android.widget.EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.site_rename_hint);
        input.setText(baseName);
        input.setSelection(baseName.length());
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_rename_title)
            .setView(input)
            .setNegativeButton(R.string.dialog_negative, null)
            .setPositiveButton(R.string.dialog_positive, null)
            .create();
        dialog.show();
        // 强校验：空名 / 非法字符原地提示，不关弹窗，避免「点了确定没反应」
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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
            App.post(() -> {
                if (!isAdded()) return;
                if (result == 1) {
                    Notify.show(getString(R.string.site_rename_done, path == null ? newName : new File(path).getName()));
                    reloadAfterMove();
                } else if (result == -1) Notify.show(R.string.site_rename_exists);
                else if (result == -3) Notify.show(R.string.site_rename_invalid);
                else if (result == LocalSourceHub.BLOCK_SELF_AUTHORED) showSelfAuthoredHint(getString(R.string.site_self_authored, site.getName()));
                else if (result == 0) Notify.show(R.string.site_rename_no_file);
                else Notify.show(R.string.site_rename_fail);
            });
        }, "local-source-rename").start();
    }

    /** 重命名分类文件夹：先在目录树里把光标落到某一层，再输入新名。 */
    private void renameFolderFlow() {
        Context context = getContext();
        if (context == null) return;
        FolderTreeDialog.show(context, this::promptRenameFolder);
    }

    private void promptRenameFolder(File dir) {
        Context context = getContext();
        if (context == null || dir == null) return;
        String oldName = dir.getName();
        android.widget.EditText input = new android.widget.EditText(context);
        input.setSingleLine(true);
        input.setHint(R.string.site_rename_hint);
        input.setText(oldName);
        input.setSelection(oldName.length());
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_rename_folder_title)
            .setView(input)
            .setNegativeButton(R.string.dialog_negative, null)
            .setPositiveButton(R.string.dialog_positive, null)
            .create();
        dialog.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String name = LocalSourceHub.sanitizeName(raw);
            if (name == null) {
                Notify.show(isBlank(raw) ? R.string.site_rename_empty : R.string.site_rename_invalid);
                return;
            }
            dialog.dismiss();
            if (name.equals(oldName)) return;
            doRenameFolder(dir, name);
        });
    }

    private void doRenameFolder(File dir, String newName) {
        new Thread(() -> {
            final int result = LocalSourceHub.renameFolder(dir.getAbsolutePath(), newName);
            App.post(() -> {
                if (!isAdded()) return;
                if (result == 1) {
                    Notify.show(getString(R.string.site_rename_done, newName));
                    reloadAfterMove();
                } else if (result == -1) Notify.show(R.string.site_rename_exists);
                else if (result == -5) Notify.show(R.string.site_folder_root_forbidden);
                else if (result == -3) Notify.show(R.string.site_rename_invalid);
                else Notify.show(R.string.site_rename_fail);
            });
        }, "local-source-rename-folder").start();
    }

    // ==================== r104：增加 / 删除分类（树状图选择） ====================

    /**
     * r104：增加 / 删除分类 —— 先用目录树选中目标文件夹，再决定对这个文件夹做什么：
     * 「增加」= 在它下面新建一个分类文件夹；「删除」= 把这个分类文件夹（连同里面的源）删掉。
     *
     * <p>改造原因（用户要求）：原来「增加新分类」是单选列表，只列到扫描目录下两级，
     * 深层目录根本选不到；而且分类建了没法在同一个入口删掉。</p>
     */
    private void createCategoryFlow() {
        Context context = getContext();
        if (context == null) return;
        FolderTreeDialog.showPicker(context, R.string.site_action_new_category, R.string.site_category_pick_tip,
                R.string.site_category_add, R.string.site_category_delete, (dir, neutral) -> {
                    if (dir == null) return;
                    if (neutral) confirmDeleteCategory(dir);
                    else promptCategoryName(dir);
                });
    }

    /**
     * r104：删除分类文件夹前的**二次确认**（用户要求：所有删除都必须二次确认，
     * 且必须把「这个文件夹里有内容」说清楚再让用户决定）。
     *
     * <p>文件统计要读磁盘，放后台算；空文件夹也照样确认一次，只是文案不同 ——
     * 不因为「看起来是空的」就直接删。</p>
     */
    private void confirmDeleteCategory(File dir) {
        if (dir == null) return;
        Context context = getContext();
        if (context == null) return;
        final String path = dir.getAbsolutePath();
        final String name = dir.getName();
        new Thread(() -> {
            final int files = LocalSourceHub.countFolderFiles(path);
            App.post(() -> {
                if (!isAdded()) return;
                Context ctx = getContext();
                if (ctx == null) return;
                new MaterialAlertDialogBuilder(ctx, R.style.TaixuAlertDialogTheme)
                    .setTitle(R.string.site_category_del_title)
                    .setMessage(files > 0
                            ? getString(R.string.site_category_del_msg, name, files)
                            : getString(R.string.site_category_del_empty, name))
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.site_del_confirm, (dialog, which) -> doDeleteCategory(dir))
                    .show();
            });
        }, "local-source-category-count").start();
    }

    /** r104：真正删分类文件夹（后台执行 → 主线程提示 + 重载）。 */
    private void doDeleteCategory(File dir) {
        if (dir == null) return;
        final String path = dir.getAbsolutePath();
        final String name = dir.getName();
        new Thread(() -> {
            final int result = LocalSourceHub.deleteFolder(path);
            App.post(() -> {
                if (!isAdded()) return;
                if (result >= 0) {
                    Notify.show(getString(R.string.site_category_del_done, name));
                    reloadAfterMove();
                } else if (result == -5) {
                    Notify.show(R.string.site_category_del_forbidden);
                } else {
                    Notify.show(R.string.site_category_del_fail);
                }
            });
        }, "local-source-category-delete").start();
    }

    /** 强校验分类名：非空 + 不含路径分隔符等非法字符。 */
    private String validCategoryName(String input) {
        if (isBlank(input)) return null;
        String name = input.trim();
        if (name.isEmpty() || name.startsWith(".")) return null;
        if (name.matches(".*[\\\\/:*?\"<>|].*")) return null;
        return name;
    }

    private void promptCategoryName(File parent) {
        Context context = getContext();
        if (context == null || parent == null) return;
        android.widget.EditText input = new android.widget.EditText(context);
        input.setHint(R.string.site_new_category_name);
        input.setSingleLine(true);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_new_category_title)
            .setView(input)
            .setNegativeButton(R.string.dialog_negative, null)
            .setPositiveButton(R.string.site_new_category_create, null)
            .create();
        dialog.show();
        // 强校验：为空 / 含非法字符时**不允许保存**，原地提示且不关闭弹窗
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            String name = validCategoryName(raw);
            if (name == null) {
                Notify.show(isBlank(raw) ? R.string.site_new_category_empty : R.string.site_new_category_invalid);
                return;
            }
            File target = new File(parent, name);
            if (!target.isDirectory() && !target.mkdirs()) {
                Notify.show(R.string.site_new_category_fail);
                return;
            }
            dialog.dismiss();
            Notify.show(getString(R.string.site_new_category_done, target.getAbsolutePath()));
            exitSelection();
            reloadAfterMove();
        });
    }

    // ==================== 对勾选项的批量操作 ====================

    /**
     * r104：批量移动的目标改为**目录树选择**（用户要求：与「重命名分类文件夹」同一套树状图交互）。
     *
     * <p>与旧实现的差别：不再受 {@link LocalSourceHub#moveTargets} 的「候选分类名」限制 ——
     * 树里点到哪一层就搬到哪一层（扫描目录下的任意层文件夹都能当目标），所以落地改走
     * {@link LocalSourceHub#moveSiteToDir}（绝对目录版），刷新链路不变。</p>
     */
    private void chooseMoveTargetForSelection(List<Site> targets) {
        Context context = getContext();
        if (context == null || targets.isEmpty()) return;
        FolderTreeDialog.showPicker(context, R.string.site_action_move, R.string.site_move_pick_tip,
                R.string.site_move_here, 0, (dir, neutral) -> {
                    if (dir != null) moveSelectedToDir(targets, dir.getAbsolutePath());
                });
    }

    /** r104：按绝对目录批量移动（树里选中的目标）—— 搬完立刻重载。 */
    private void moveSelectedToDir(List<Site> targets, String targetDir) {
        if (TextUtils.isEmpty(targetDir)) return;
        final List<Site> snapshot = new ArrayList<>(targets);
        final String dirPath = targetDir;
        exitSelection();
        updateSelectionUi();
        new Thread(() -> {
            int moved = 0;
            int failed = 0;
            int same = 0;
            String selfName = null;
            try {
                LocalSourceHub.beginBatch();
                for (Site site : snapshot) {
                    String prevDir = LocalSourceHub.categoryDirOf(site.getApi(), site.getExt(), null);
                    int result = LocalSourceHub.moveSiteToDir(site.getApi(), site.getExt(), null, dirPath);
                    if (result == 1) {
                        moved++;
                        // r62-②：记下「搬家前所在分类」，供「回退到上一次分类」用
                        MoveHistory.record(LocalSourceHub.movedPath(), prevDir);
                    } else if (result == LocalSourceHub.BLOCK_SELF_AUTHORED) {
                        selfName = site.getName();
                    } else if (result == -1) {
                        same++;
                    } else {
                        failed++;
                    }
                }
            } catch (Throwable e) {
                // 异常不吞声：计数按已搬的走，finally 里 endBatch，收尾在 App.post 里按计数提示
            } finally {
                LocalSourceHub.endBatch();
            }
            final int movedCount = moved;
            final String blockedName = selfName;
            App.post(() -> {
                if (!isAdded()) return;
                if (blockedName != null) showSelfAuthoredHint(getString(R.string.site_self_authored, blockedName));
                if (movedCount == 0) {
                    Notify.show(R.string.site_move_fail);
                    updateSelectionUi();
                    return;
                }
                Notify.show(getString(R.string.site_select_moved, movedCount));
                reloadAfterMove();
            });
        }, "local-source-move-tree").start();
    }

    /**
     * r62-①：批量删除源文件 —— 删完**立刻**从列表消失（不必重开源列表）。
     *
     * <p>收尾走我方既有的 {@link #reloadAfterMove()}：同步回写后重载配置，
     * 列表与磁盘一次对齐（不搬原版的 purgeFromMemory/refreshListUi 内存摘除链）。</p>
     */
    private void deleteSelected(List<Site> targets) {
        final List<Site> snapshot = new ArrayList<>(targets);
        exitSelection();
        updateSelectionUi();
        new Thread(() -> {
            int removed = 0;
            String selfName = null;
            try {
                // r63-③：批量删除同样合并成「一次改写 + 一次回写」
                LocalSourceHub.beginBatch();
                for (Site site : snapshot) {
                    int result = LocalSourceHub.deleteSiteFile(site.getApi(), site.getExt(), null);
                    if (result > 0) removed++;
                    else if (result == LocalSourceHub.BLOCK_SELF_AUTHORED) selfName = site.getName();
                }
            } catch (Throwable ignored) {
                // 单条异常不中断整批：计数按已删的走，finally 里统一 endBatch 收尾
            } finally {
                LocalSourceHub.endBatch();
            }
            final int removedCount = removed;
            final String blockedName = selfName;
            App.post(() -> {
                if (blockedName != null && isAdded()) showSelfAuthoredHint(getString(R.string.site_self_authored, blockedName));
                if (removedCount == 0) {
                    Notify.show(R.string.site_del_fail);
                    if (isAdded()) updateSelectionUi();
                    return;
                }
                Notify.show(getString(R.string.site_del_done_multi, removedCount));
                reloadAfterMove();
            });
        }, "local-source-delete").start();
    }

    // ==================== r62f：分享给他人 ====================

    /**
     * 把选中的源分享给其他 App（微信 / QQ / 邮件…）。
     *
     * <p>本地源：定位到磁盘上的源文件，用 {@link FileUtil#getShareUri(File)}（FileProvider）换成
     * {@code content://} URI 再 {@code ACTION_SEND}。系统不允许直接分享 {@code file://}，所以必须走 provider。
     * 远程源（只有 http 接口、没有本地文件）：退化成分享「名称 : 接口地址」文本，避免出现「点了没反应」。</p>
     */
    private void shareSelected(List<Site> targets) {
        if (getContext() == null) return;
        final List<Site> snapshot = new ArrayList<>(targets);
        new Thread(() -> {
            final List<Uri> uris = new ArrayList<>();
            final StringBuilder text = new StringBuilder();
            String mime = null;
            for (Site site : snapshot) {
                String path = LocalSourceHub.sourceFilePathOf(site.getApi(), site.getExt(), null);
                File file = path == null ? null : new File(path);
                Uri uri = file != null && file.exists() ? shareUriOf(file) : null;
                if (uri != null) {
                    uris.add(uri);
                    String one = mimeOf(file.getName());
                    // 混选时：只要有一个不是纯文本，就用那个类型（微信对 text/plain + 二进制会拒收）
                    if (mime == null) mime = one;
                    else if ("text/plain".equals(mime) && !"text/plain".equals(one)) mime = one;
                    continue;
                }
                if (text.length() > 0) text.append('\n');
                text.append(site.getName());
                if (!TextUtils.isEmpty(site.getApi())) text.append(" : ").append(site.getApi());
            }
            final String shareMime = mime;
            final String shareText = text.toString();
            App.post(() -> {
                if (!isAdded()) return;
                shareNow(uris, shareText, shareMime);
            });
        }, "local-source-share").start();
    }

    private void shareNow(List<Uri> uris, String text, String mime) {
        try {
            Intent intent;
            if (!uris.isEmpty()) {
                intent = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
                intent.setType(mime == null ? "*/*" : mime);
                if (uris.size() == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!TextUtils.isEmpty(text)) intent.putExtra(Intent.EXTRA_TEXT, text);
            } else if (!TextUtils.isEmpty(text)) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, text);
            } else {
                Notify.show(R.string.site_share_empty);
                return;
            }
            Intent chooser = Intent.createChooser(intent, getString(R.string.site_action_share));
            // 授权要同时挂在 chooser 上，否则部分 ROM 的目标 App 拿不到读权限
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            App.get().startActivity(chooser);
        } catch (Throwable e) {
            Notify.show(R.string.site_share_fail);
        }
    }

    /** FileProvider 白名单外的目录（少数自定义扫描目录）会被拒：退一步复制到私有缓存再分享。 */
    private Uri shareUriOf(File file) {
        try {
            return FileUtil.getShareUri(file);
        } catch (Throwable e) {
            try {
                File dir = new File(App.get().getCacheDir(), "share");
                if (!dir.exists() && !dir.mkdirs()) return null;
                File copy = new File(dir, file.getName());
                try (FileInputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(copy)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
                }
                return FileUtil.getShareUri(copy);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private String mimeOf(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".zip") || lower.endsWith(".pkg")) return "application/zip";
        if (lower.endsWith(".db")) return "application/octet-stream";
        return "text/plain";
    }

    /**
     * r62-②：批量「回退到上一次分类」。
     *
     * <p>「上一次分类」记录在 {@link MoveHistory}（每次搬家成功时写入）。对「失效列表」里的源还有一条兜底：
     * 它上一级目录就是原分类（移进失效列表时是在原分类下建子目录），所以即使没有历史记录也能退回去。</p>
     */
    private void restoreSelected(List<Site> targets) {
        final List<Site> snapshot = new ArrayList<>(targets);
        exitSelection();
        updateSelectionUi();
        new Thread(() -> {
            int restored = 0;
            try {
                LocalSourceHub.beginBatch();
                for (Site site : snapshot) {
                    String currentDir = LocalSourceHub.categoryDirOf(site.getApi(), site.getExt(), null);
                    String prev = prevCategoryOf(site, currentDir);
                    if (prev == null) continue;
                    int result = LocalSourceHub.moveSiteToDir(site.getApi(), site.getExt(), null, prev);
                    if (result > 0) {
                        restored++;
                        MoveHistory.record(LocalSourceHub.movedPath(), currentDir);
                    }
                }
            } catch (Throwable e) {
                // 异常不吞声：计数按已回退的走，finally 里 endBatch，收尾在 App.post 里按计数提示
            } finally {
                LocalSourceHub.endBatch();
            }
            final int count = restored;
            App.post(() -> {
                if (count == 0) {
                    Notify.show(R.string.site_restore_none);
                    if (isAdded()) updateSelectionUi();
                    return;
                }
                Notify.show(getString(R.string.site_restore_done, count));
                reloadAfterMove();
            });
        }, "local-source-restore").start();
    }

    /** r62-②：某个源「上一次所在的分类目录」绝对路径；拿不到返回 null。 */
    private String prevCategoryOf(Site site, String currentDir) {
        String path = LocalSourceHub.sourceFilePathOf(site.getApi(), site.getExt(), null);
        String prev = path == null ? null : MoveHistory.prevOf(path);
        if (prev != null) {
            // r62b：历史里可能记着「失效列表 → 失效列表」（旧实现套过层），一路跳到不是失效列表的祖先
            String outside = outsideFailDirs(prev);
            if (outside != null) return outside;
        }
        if (currentDir == null) return null;
        return outsideFailDirs(currentDir);
    }

    /** 从 dir 往上找到第一个名字不是「失效列表」的目录。 */
    private static String outsideFailDirs(String dir) {
        if (isBlank(dir)) return null;
        File file = new File(dir);
        while (file != null && FAIL_DIR_NAME.equals(file.getName())) file = file.getParentFile();
        return file == null ? null : file.getAbsolutePath();
    }

    /** 空串或 null。 */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 搬家/删除后立刻生效：重载点播配置 → 重建适配器列表 → 重建分类栏 → 重筛 → 滚回在用源。 */
    private void reloadAfterMove() {
        // r123：操作已落地，「选定当前目标」菜单收起来
        dismissActionMenu();
        VodConfig.get().init().load(new Callback() {
            @Override
            public void start() {
            }

            @Override
            public void success() {
                if (!isAdded()) return;
                adapter.refresh();
                groups = SiteType.presentCategories(adapter.getItems());
                initGroup();
                applyFilter();
                scrollToCurrentSite();
                updateSelectionUi();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                if (isAdded()) {
                    adapter.refresh();
                    applyFilter();
                }
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) {
            dismiss();
            return;
        }
        // 弹窗宽度：竖屏照 Vesper 0.98 近全宽大卡片，横屏保留原 0.5
        setWidth(ResUtil.isLand(requireContext()) ? 0.5f : 0.98f);
    }
}
