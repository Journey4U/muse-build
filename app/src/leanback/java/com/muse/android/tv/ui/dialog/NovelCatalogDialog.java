package com.muse.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.muse.android.tv.databinding.AdapterEpisodeDialogBinding;
import com.muse.android.tv.databinding.AdapterEpisodeRangeBinding;
import com.muse.android.tv.databinding.DialogNovelCatalogBinding;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;

import java.util.List;

// 小说目录（电视版）：照选集次级页面 EpisodeDialog 同构——
// 注意：主源集已有视频章节用的 ChapterDialog（同包同名），电视版小说对话框
// 必须用 NovelCatalogDialog 这个名字，否则 leanback 构建会因类重名编译失败。
// 上排区间页签 + 下排多列网格，列数随屏宽自适应（>=880dp 5 列、>=560dp 4 列、其余 3 列，
// 每页固定 5 行），条目复用选集条目布局（selector 底、可聚焦、居中、单行省略），
// 打开即聚焦当前章。原单列 Material 单选列表（setSingleChoiceItems）替换为本对话框。
public class NovelCatalogDialog extends BaseAlertDialog {

    public interface OnChapterClickListener {
        void onChapterClick(int position);
    }

    private DialogNovelCatalogBinding binding;
    private OnChapterClickListener listener;
    private List<String> names;
    private int position;
    private int page;
    private int spanCount;
    private int itemCount;
    private int focusRetries;

    public static NovelCatalogDialog create() {
        return new NovelCatalogDialog();
    }

    public NovelCatalogDialog names(List<String> names) {
        this.names = names;
        return this;
    }

    public NovelCatalogDialog position(int position) {
        this.position = position;
        return this;
    }

    public NovelCatalogDialog listener(OnChapterClickListener listener) {
        this.listener = listener;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNovelCatalogBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        setSpanCount();
        page = names == null || names.isEmpty() ? 0 : Math.min(position / itemCount, getPageCount() - 1);
        binding.ranges.setAdapter(new RangeAdapter());
        binding.ranges.setHasFixedSize(true);
        binding.ranges.setItemAnimator(null);
        binding.ranges.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        binding.grid.setAdapter(new GridAdapter());
        binding.grid.setHasFixedSize(true);
        binding.grid.setItemAnimator(null);
        binding.grid.addItemDecoration(new SpaceItemDecoration(spanCount, 12));
        binding.grid.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        binding.ranges.scrollToPosition(page);
        focusRetries = 0;
        binding.grid.post(this::focusCurrent);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (names == null || names.isEmpty()) dismiss();
        else setWidth(0.9f);
    }

    private void setSpanCount() {
        android.util.DisplayMetrics metrics = requireContext().getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        // 与选集弹窗同口径：固定 5 行/页，页大小随屏宽走 25 / 20 / 15 章。
        spanCount = widthDp >= 880 ? 5 : widthDp >= 560 ? 4 : 3;
        itemCount = spanCount * 5;
    }

    private int getPageCount() {
        if (names == null) return 0;
        return (names.size() + itemCount - 1) / itemCount;
    }

    private int getPageFrom(int page) {
        return page * itemCount;
    }

    private int getPageTo(int page) {
        return Math.min(getPageFrom(page) + itemCount, names.size());
    }

    private void setPage(int page) {
        this.page = page;
        binding.ranges.getAdapter().notifyDataSetChanged();
        binding.grid.getAdapter().notifyDataSetChanged();
        binding.ranges.scrollToPosition(page);
    }

    private void focusCurrent() {
        int index = position - getPageFrom(page);
        if (index < 0 || index >= binding.grid.getAdapter().getItemCount()) index = 0;
        binding.grid.scrollToPosition(index);
        RecyclerView.ViewHolder holder = binding.grid.findViewHolderForAdapterPosition(index);
        if (holder != null) holder.itemView.requestFocus();
        else if (++focusRetries <= 5) binding.grid.post(this::focusCurrent);
    }

    // 目录条目文案：与手机版 NovelReaderActivity.catalogLabel() 同规则——
    // 空名退回纯序号，纯数字名只显示该数字，避免“1. 1”式重复，其余“序号. 名称”。
    private String catalogLabel(int i) {
        String name = names.get(i).trim();
        if (name.isEmpty()) return String.valueOf(i + 1);
        if (name.matches("\\d+")) return name;
        return (i + 1) + ". " + name;
    }

    class RangeAdapter extends RecyclerView.Adapter<RangeAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterEpisodeRangeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int pos) {
            holder.binding.text.setText((getPageFrom(pos) + 1) + " - " + getPageTo(pos));
            holder.binding.text.setSelected(pos == page);
            holder.binding.getRoot().setOnClickListener(v -> {
                if (page != pos) setPage(pos);
                focusRetries = 0;
                binding.grid.post(NovelCatalogDialog.this::focusCurrent);
            });
        }

        @Override
        public int getItemCount() {
            return getPageCount();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final AdapterEpisodeRangeBinding binding;

            ViewHolder(@NonNull AdapterEpisodeRangeBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    class GridAdapter extends RecyclerView.Adapter<GridAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterEpisodeDialogBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int pos) {
            int absolute = getPageFrom(page) + pos;
            holder.binding.text.setText(catalogLabel(absolute));
            holder.binding.text.setSelected(absolute == position);
            holder.binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onChapterClick(absolute);
                dismiss();
            });
        }

        @Override
        public int getItemCount() {
            if (names == null) return 0;
            return Math.max(getPageTo(page) - getPageFrom(page), 0);
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final AdapterEpisodeDialogBinding binding;

            ViewHolder(@NonNull AdapterEpisodeDialogBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
