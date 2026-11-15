package com.muse.android.tv.ui.dialog;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.github.catvod.utils.Prefers;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.DialogSiteBinding;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.adapter.SiteAdapter;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    private static final int GRID_COUNT = 10;

    private RecyclerView.ItemDecoration decoration;
    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private boolean action;
    private boolean keyword;
    private int type;

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

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        if (activity instanceof SiteListener) listener = (SiteListener) activity;
    }

    private boolean list() {
        // Fresh installs have no stored site_mode: default to the grid
        // (the stored pref default 0 would otherwise pin the dialog to a
        // single column forever). Only an explicit user choice counts.
        boolean modeStored = Prefers.getPrefers().contains("site_mode");
        return (modeStored && Setting.getSiteMode() == 0) || adapter.getItemCount() < GRID_COUNT;
    }

    private float getScreenWidthDp() {
        android.util.DisplayMetrics metrics = requireContext().getResources().getDisplayMetrics();
        return metrics.widthPixels / metrics.density;
    }

    private int getCount() {
        if (list()) return 1;
        float avail = getScreenWidthDp() * 0.94f - 48 - (action ? 56 : 0);
        return Math.clamp((int) ((avail + 16) / 216), 2, 5);
    }

    private int getIcon() {
        return list() ? com.muse.android.tv.R.drawable.ic_site_grid : com.muse.android.tv.R.drawable.ic_site_list;
    }

    private float getWidth() {
        int count = getCount();
        float dialog = count * 200f + (count - 1) * 16 + 48 + (action ? 56 : 0);
        return Math.clamp(dialog / getScreenWidthDp(), 0.4f, 0.94f);
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
        adapter = new SiteAdapter(this);
        if (action) binding.action.setVisibility(View.VISIBLE);
        setType(type);
        setRecyclerView();
        setMode();
        binding.keywordLayout.setVisibility(keyword ? View.VISIBLE : View.GONE);
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

    @Override
    protected void initEvent() {
        binding.mode.setOnClickListener(this::onMode);
        binding.select.setOnClickListener(v -> adapter.selectAll());
        binding.cancel.setOnClickListener(v -> adapter.cancelAll());
        binding.search.setOnClickListener(v -> setType(v.isSelected() ? 0 : 1));
        binding.change.setOnClickListener(v -> setType(v.isSelected() ? 0 : 2));
    }

    private void setRecyclerView() {
        binding.recycler.setAdapter(adapter);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        if (decoration != null) binding.recycler.removeItemDecoration(decoration);
        binding.recycler.addItemDecoration(decoration = new SpaceItemDecoration(getCount(), 16));
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), getCount()));
        if (!binding.mode.hasFocus()) binding.recycler.post(() -> binding.recycler.scrollToPosition(VodConfig.getHomeIndex()));
    }

    private void setType(int type) {
        binding.search.setSelected(type == 1);
        binding.change.setSelected(type == 2);
        binding.select.setClickable(type > 0);
        binding.cancel.setClickable(type > 0);
        adapter.setType(this.type = type);
    }

    private void setMode() {
        if (adapter.getItemCount() < GRID_COUNT) Setting.putSiteMode(0);
        binding.mode.setEnabled(adapter.getItemCount() >= GRID_COUNT);
        binding.mode.setImageResource(getIcon());
    }

    private void setWidth() {
        setWidth(getWidth());
    }

    private void onMode(View view) {
        Setting.putSiteMode(Math.abs(Setting.getSiteMode() - 1));
        setRecyclerView();
        setMode();
        setWidth();
    }

    @Override
    public void onItemClick(Site item) {
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth();
    }
}
