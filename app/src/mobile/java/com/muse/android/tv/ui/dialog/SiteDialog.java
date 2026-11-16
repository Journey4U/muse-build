package com.muse.android.tv.ui.dialog;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.DialogSiteBinding;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.ui.adapter.SiteAdapter;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

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
    private boolean search;
    private boolean change;
    private boolean searchMark;
    private int spanCount;

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
        initGroup();
        // 过滤前先校验「上次选中的分类」在当前配置里还存不存在（换配置后旧分类可能已消失）
        normalizeGroup();
        refreshGroupSelected();
        applyFilter();
        scrollGroupToSelected();
        // 列表滚动定位到正在用的那个源（蓝底 + 蓝点那条），重开弹窗也能一眼看到
        scrollToCurrentSite();
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
                adapter.filter(group, s.toString());
            }
        });
    }

    /**
     * 从全部可见源名中提取分组标记（如 [PY]、[JS]），动态生成筛选标签。
     */
    private void initGroup() {
        groups = Site.getGroups(adapter.getItems());
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
