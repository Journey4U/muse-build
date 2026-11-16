package com.muse.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.databinding.AdapterEpisodeDialogBinding;
import com.muse.android.tv.databinding.AdapterEpisodeRangeBinding;
import com.muse.android.tv.databinding.DialogEpisodeBinding;
import com.muse.android.tv.ui.adapter.EpisodeAdapter;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class EpisodeDialog extends BaseAlertDialog {

    private DialogEpisodeBinding binding;
    private EpisodeAdapter.OnClickListener listener;
    private List<Episode> episodes;
    private RangeAdapter rangeAdapter;
    private GridAdapter gridAdapter;
    private int position;
    private int page;
    private int spanCount;
    private int itemCount;
    private int focusRetries;

    public static EpisodeDialog create() {
        return new EpisodeDialog();
    }

    public EpisodeDialog episodes(List<Episode> episodes) {
        this.episodes = episodes;
        return this;
    }

    public EpisodeDialog position(int position) {
        this.position = position;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        if (activity instanceof EpisodeAdapter.OnClickListener) listener = (EpisodeAdapter.OnClickListener) activity;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogEpisodeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        setSpanCount();
        page = episodes == null || episodes.isEmpty() ? 0 : Math.min(position / itemCount, getPageCount() - 1);
        rangeAdapter = new RangeAdapter();
        gridAdapter = new GridAdapter();
        binding.ranges.setAdapter(rangeAdapter);
        binding.ranges.setHasFixedSize(true);
        binding.ranges.setItemAnimator(null);
        binding.ranges.setLayoutManager(new LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false));
        binding.grid.setAdapter(gridAdapter);
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
        if (episodes == null || episodes.isEmpty()) dismiss();
        else setWidth(0.9f);
    }

    private void setSpanCount() {
        android.util.DisplayMetrics metrics = requireContext().getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        // Fixed 5 rows per page; the page size follows the screen width:
        // 25 episodes per page (5 columns), 20 (4 columns) or 15 (3 columns).
        spanCount = widthDp >= 880 ? 5 : widthDp >= 560 ? 4 : 3;
        itemCount = spanCount * 5;
    }

    private int getPageCount() {
        if (episodes == null) return 0;
        return (episodes.size() + itemCount - 1) / itemCount;
    }

    private int getPageFrom(int page) {
        return page * itemCount;
    }

    private int getPageTo(int page) {
        return Math.min(getPageFrom(page) + itemCount, episodes.size());
    }

    private void setPage(int page) {
        this.page = page;
        rangeAdapter.notifyDataSetChanged();
        gridAdapter.notifyDataSetChanged();
        binding.ranges.scrollToPosition(page);
    }

    private void focusCurrent() {
        int index = position - getPageFrom(page);
        if (index < 0 || index >= gridAdapter.getItemCount()) index = 0;
        binding.grid.scrollToPosition(index);
        RecyclerView.ViewHolder holder = binding.grid.findViewHolderForAdapterPosition(index);
        if (holder != null) holder.itemView.requestFocus();
        else if (++focusRetries <= 5) binding.grid.post(this::focusCurrent);
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
                binding.grid.post(EpisodeDialog.this::focusCurrent);
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
            Episode item = episodes.get(getPageFrom(page) + pos);
            holder.binding.text.setText(item.getDesc().concat(item.getName()));
            holder.binding.text.setSelected(item.isSelected());
            holder.binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
                dismiss();
            });
        }

        @Override
        public int getItemCount() {
            if (episodes == null) return 0;
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
