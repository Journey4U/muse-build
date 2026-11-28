package com.muse.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.bean.EpgData;
import com.muse.android.tv.databinding.AdapterEpgDataBinding;

import java.util.ArrayList;
import java.util.List;

public class EpgDataAdapter extends RecyclerView.Adapter<EpgDataAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<EpgData> mItems;

    public EpgDataAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public void addAll(List<EpgData> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public EpgData get(int position) {
        return mItems.get(position);
    }

    // 钉死布局批：只刷选中状态真变了的格子，不整列重绑。
    public void setSelected(EpgData selected) {
        for (int i = 0; i < mItems.size(); i++) {
            EpgData item = mItems.get(i);
            boolean before = item.isSelected();
            item.setSelected(selected);
            if (before != item.isSelected()) notifyItemChanged(i);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpgDataBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EpgData item = mItems.get(position);
        holder.binding.time.setText(item.getTime());
        holder.binding.title.setText(item.getTitle());
        holder.binding.getRoot().setSelected(item.isSelected());
        // 节目名：焦点或当前节目跑马灯内循环，其余单行省略
        holder.binding.title.setSelected(holder.binding.getRoot().hasFocus() || item.isSelected());
        holder.binding.getRoot().setOnFocusChangeListener((v, hasFocus) -> holder.binding.title.setSelected(hasFocus || item.isSelected()));
        holder.binding.getRoot().setLeftListener(mListener::onLeft);
        holder.binding.getRoot().setOnClickListener(v -> {
            if (!item.isFuture()) mListener.onItemClick(item);
        });
    }

    public interface OnClickListener {

        void onLeft();

        void onItemClick(EpgData item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpgDataBinding binding;

        ViewHolder(@NonNull AdapterEpgDataBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
