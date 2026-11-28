package com.muse.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.muse.android.tv.bean.Channel;
import com.muse.android.tv.databinding.AdapterChannelBinding;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Channel> mItems;

    public ChannelAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public void addAll(List<Channel> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void remove(Channel item) {
        int index = mItems.indexOf(item);
        if (index < 0) return;
        mItems.remove(index);
        notifyItemRemoved(index);
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public Channel get(int position) {
        return mItems.get(position);
    }

    // 钉死布局批：只刷选中状态真变了的格子（旧当前频道、新当前频道），不整列重绑。
    public void setSelected(Channel selected) {
        for (int i = 0; i < mItems.size(); i++) {
            Channel item = mItems.get(i);
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
        return new ViewHolder(AdapterChannelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Channel item = mItems.get(position);
        item.loadLogo(holder.binding.logo);
        holder.binding.name.setText(item.getShow());
        holder.binding.number.setText(item.getNumber());
        holder.binding.getRoot().setSelected(item.isSelected());
        // 频道名：焦点或当前频道跑马灯内循环，其余单行省略
        holder.binding.name.setSelected(holder.binding.getRoot().hasFocus() || item.isSelected());
        holder.binding.getRoot().setOnFocusChangeListener((v, hasFocus) -> holder.binding.name.setSelected(hasFocus || item.isSelected()));
        holder.binding.getRoot().setRightListener(() -> mListener.showEpg(item));
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(v -> mListener.onLongClick(item));
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.logo).clear(holder.binding.logo);
    }

    public interface OnClickListener {

        void showEpg(Channel item);

        void onItemClick(Channel item);

        boolean onLongClick(Channel item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterChannelBinding binding;

        ViewHolder(@NonNull AdapterChannelBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
