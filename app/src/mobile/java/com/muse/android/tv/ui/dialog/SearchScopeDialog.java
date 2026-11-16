package com.muse.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.DialogSearchScopeBinding;
import com.muse.android.tv.ui.fragment.SiteType;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页搜索按钮长按弹出的「搜索范围」选择对话框。
 * 提供三类范围：当前源、按分类（源名 [分类] 标记）、按源类型（站名 () 里的规范类型名，备注不算）。
 * 选择结果通过 {@link Listener} 回传，由调用方拼装 SearchActivity 的跳转参数。
 *
 * 5.5.6 原版无此功能，属自研（按 mobile42 记录实现）。
 * 居中显示：继承 BaseAlertDialog（普通居中对话框），不再使用底部 BottomSheet。
 */
public class SearchScopeDialog extends BaseAlertDialog {

    public static final String SCOPE_CURRENT = "current";
    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_GROUP = "group";
    public static final String SCOPE_TYPE = "type";

    private DialogSearchScopeBinding binding;
    private Listener listener;

    public interface Listener {

        void onScope(String scope, String value);
    }

    public static SearchScopeDialog create() {
        return new SearchScopeDialog();
    }

    public void show(Fragment fragment) {
        for (Fragment f : fragment.getChildFragmentManager().getFragments()) if (f instanceof SearchScopeDialog) return;
        show(fragment.getChildFragmentManager(), null);
        if (fragment instanceof Listener) listener = (Listener) fragment;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSearchScopeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        List<Site> sites = visibleSites();
        List<String> groups = SiteType.presentCategories(sites);
        List<String> types = new ArrayList<>();
        for (String item : SiteType.presentIn(sites)) types.add("(" + item + ")");
        binding.scopeCurrent.setText(getString(R.string.search_scope_current));
        binding.scopeAll.setText(getString(R.string.search_scope_all));
        binding.groupTitle.setText(getString(R.string.search_scope_group));
        binding.typeTitle.setText(getString(R.string.search_scope_type));
        binding.scopeCurrent.setOnClickListener(v -> select(SCOPE_CURRENT, ""));
        binding.scopeAll.setOnClickListener(v -> select(SCOPE_ALL, ""));
        binding.groupTitle.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
        binding.groupScroll.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
        binding.typeTitle.setVisibility(types.isEmpty() ? View.GONE : View.VISIBLE);
        binding.typeScroll.setVisibility(types.isEmpty() ? View.GONE : View.VISIBLE);
        if (!groups.isEmpty()) addChips(binding.groupList, groups, SCOPE_GROUP);
        if (!types.isEmpty()) addChips(binding.typeList, types, SCOPE_TYPE);
    }

    private void select(String scope, String value) {
        if (listener != null) listener.onScope(scope, value);
        dismiss();
    }

    private List<Site> visibleSites() {
        List<Site> items = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (!site.isHide()) items.add(site);
        return items;
    }

    private void addChips(LinearLayoutCompat parent, List<String> items, String scope) {
        parent.removeAllViews();
        for (String item : items) {
            View view = LayoutInflater.from(requireContext()).inflate(R.layout.adapter_scope_chip, parent, false);
            MaterialButton chip = (MaterialButton) view;
            chip.setText(item);
            chip.setOnClickListener(v -> select(scope, item));
            parent.addView(chip);
        }
    }
}