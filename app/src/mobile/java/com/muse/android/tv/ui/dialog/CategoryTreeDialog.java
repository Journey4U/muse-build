package com.muse.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.AdapterCategoryTreeBinding;
import com.muse.android.tv.databinding.DialogCategoryTreeBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;

/**
 * 1.0.3-④ 分类移动的树状图选择：点文件夹名 = 选中该分类（回调并关闭），点 ▸ = 展开子目录，
 * 顶部 ↑ = 回上一级。起始屏列出已启用扫描目录（多个根并列），只列目录、不列文件。
 * 选中后调用方用 LocalSourceHub.moveSiteToDir(绝对目录) 搬家，不走单层 moveTargets。
 */
public class CategoryTreeDialog extends BaseAlertDialog {

    private DialogCategoryTreeBinding binding;
    private List<File> roots = new ArrayList<>();
    private Consumer<File> onSelect;
    private final Stack<File> stack = new Stack<>();
    private final List<File> current = new ArrayList<>();
    private final TreeAdapter adapter = new TreeAdapter();

    public static void show(Fragment fragment, List<String> rootDirs, Consumer<File> onSelect) {
        CategoryTreeDialog dialog = new CategoryTreeDialog();
        for (String dir : rootDirs) {
            if (dir == null) continue;
            File file = new File(dir);
            if (file.isDirectory()) dialog.roots.add(file);
        }
        dialog.onSelect = onSelect;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCategoryTreeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.TaixuAlertDialogTheme)
            .setTitle(R.string.site_move_title)
            .setView(getBinding().getRoot())
            .setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        binding.categoryTreeList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.categoryTreeList.setAdapter(adapter);
        binding.categoryTreeUp.setOnClickListener(v -> {
            if (!stack.isEmpty()) {
                stack.pop();
                refreshList();
            }
        });
        refreshList();
    }

    private void refreshList() {
        current.clear();
        if (stack.isEmpty()) {
            current.addAll(roots);
            binding.categoryTreeUp.setVisibility(View.GONE);
            binding.categoryTreePath.setText(getString(R.string.site_move_title));
        } else {
            File dir = stack.peek();
            binding.categoryTreeUp.setVisibility(View.VISIBLE);
            binding.categoryTreePath.setText(dir.getAbsolutePath());
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && !child.getName().startsWith(".")) current.add(child);
                }
                Collections.sort(current, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            }
        }
        binding.categoryTreeEmpty.setVisibility(current.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private class TreeAdapter extends RecyclerView.Adapter<TreeAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(AdapterCategoryTreeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            File dir = current.get(position);
            boolean isRoot = stack.isEmpty();
            holder.binding.treeName.setText(isRoot ? dir.getAbsolutePath() : dir.getName());
            holder.binding.treeName.setOnClickListener(v -> {
                if (onSelect != null) onSelect.accept(dir);
                dismiss();
            });
            holder.binding.treeExpand.setOnClickListener(v -> {
                stack.push(dir);
                refreshList();
            });
        }

        @Override
        public int getItemCount() {
            return current.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final AdapterCategoryTreeBinding binding;
            Holder(@NonNull AdapterCategoryTreeBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
