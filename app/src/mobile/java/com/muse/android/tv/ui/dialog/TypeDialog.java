package com.muse.android.tv.ui.dialog;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Class;
import com.muse.android.tv.databinding.DialogTypeBinding;
import com.muse.android.tv.ui.adapter.TypeDialogAdapter;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.List;

/**
 * 「全部分类」弹窗：分类栏放不下时，点一下展开全部分类并可直达任意分类。
 * 复用首页已加载的分类数据，不额外发起请求。
 *
 * 5.5.6 原版有同类入口，本项目按 mobile 端实现。
 *
 * 右上角支持切换列数（1/2/3 列），切换结果写入 Prefers，下次打开沿用。
 */
public class TypeDialog extends BaseBottomSheetDialog implements TypeDialogAdapter.OnClickListener {

    /** 列数持久化 key，默认 1 列。 */
    private static final String KEY_COLUMN = "type_dialog_column";
    private static final int MAX_COLUMN = 3;

    private DialogTypeBinding binding;
    private TypeDialogAdapter adapter;
    private Listener listener;
    private List<Class> items = new ArrayList<>();
    private int position = -1;
    private int spanCount = 1;

    public interface Listener {

        void onTypeClick(int position, Class item);
    }

    public static TypeDialog create() {
        return new TypeDialog();
    }

    public TypeDialog types(List<Class> items) {
        this.items = items == null ? new ArrayList<>() : items;
        return this;
    }

    public TypeDialog position(int position) {
        this.position = position;
        return this;
    }

    public void show(Fragment fragment) {
        for (Fragment f : fragment.getChildFragmentManager().getFragments()) if (f instanceof TypeDialog) return;
        show(fragment.getChildFragmentManager(), null);
        if (fragment instanceof Listener) listener = (Listener) fragment;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        spanCount = clamp(Prefers.getInt(KEY_COLUMN, 1));
        binding.recycler.setAdapter(adapter = new TypeDialogAdapter(this));
        setRecyclerView();
        initColumn();
        adapter.addAll(items);
        if (position >= 0 && position < items.size()) binding.recycler.post(() -> binding.recycler.scrollToPosition(position));
    }

    /** 按当前列数重建布局管理器与间距：列数变化时会重新调用。 */
    private void setRecyclerView() {
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        while (binding.recycler.getItemDecorationCount() > 0) binding.recycler.removeItemDecorationAt(0);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(spanCount, spanCount == 1 ? 8 : 16, 8));
    }

    private void initColumn() {
        binding.column.setOnClickListener(v -> toggleColumn());
        updateColumnIcon();
    }

    /** 右上角列数按钮：循环 1→2→3→1，立即生效并持久化。 */
    private void toggleColumn() {
        spanCount = spanCount % MAX_COLUMN + 1;
        Prefers.put(KEY_COLUMN, spanCount);
        setRecyclerView();
        updateColumnIcon();
        binding.recycler.scrollToPosition(0);
    }

    private void updateColumnIcon() {
        binding.column.setImageResource(spanCount == 1 ? R.drawable.ic_site_single_column : spanCount == 2 ? R.drawable.ic_site_two_column : R.drawable.ic_site_three_column);
        binding.column.setSelected(spanCount > 1);
    }

    private int clamp(int column) {
        return Math.min(MAX_COLUMN, Math.max(1, column));
    }

    @Override
    protected void initEvent() {
        binding.keyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
                binding.recycler.scrollToPosition(0);
            }
        });
    }

    @Override
    public void onItemClick(Class item) {
        if (listener != null) listener.onTypeClick(items.indexOf(item), item);
        dismiss();
    }
}
