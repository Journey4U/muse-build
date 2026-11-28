package com.muse.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Flag;
import com.muse.android.tv.databinding.AdapterFlagBinding;

import java.util.ArrayList;
import java.util.List;

public class FlagAdapter extends RecyclerView.Adapter<FlagAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Flag> mItems;
    private int nextFocusDown;
    // 钉死布局批：当前显示为选中的那一格（切线路只刷旧格和新格）
    private int shown = -1;

    public FlagAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        nextFocusDown = R.id.episode;
    }

    public void addAll(List<Flag> items) {
        mItems.clear();
        mItems.addAll(items);
        shown = findSelected();
        notifyDataSetChanged();
    }

    private int findSelected() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return -1;
    }

    /** 切线路后只刷旧选中格与新选中格，不整行重绑。 */
    public void refreshSelection() {
        int now = findSelected();
        if (now == shown) return;
        int old = shown;
        shown = now;
        if (old >= 0 && old < mItems.size()) notifyItemChanged(old);
        if (now >= 0) notifyItemChanged(now);
    }

    public void clear() {
        mItems.clear();
        shown = -1;
        notifyDataSetChanged();
    }

    public Flag get(int position) {
        return mItems.get(position);
    }

    public int indexOf(Flag item) {
        return mItems.indexOf(item);
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    // 钉死布局批：下焦点目标只存值，已显示的格子由 applyFocus 直接改，不整行重绑。
    public void setNextFocusDown(int nextFocusDown) {
        this.nextFocusDown = nextFocusDown;
    }

    public void applyFocus(RecyclerView view) {
        for (int i = 0; i < view.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = view.getChildViewHolder(view.getChildAt(i));
            if (holder instanceof ViewHolder) ((ViewHolder) holder).binding.text.setNextFocusDownId(nextFocusDown);
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        holder.binding.text.setNextFocusDownId(nextFocusDown);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFlagBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Flag item = mItems.get(position);
        holder.binding.text.setText(item.getShow());
        if (item.isSelected()) shown = position;
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setNextFocusDownId(nextFocusDown);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(Flag item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFlagBinding binding;

        ViewHolder(@NonNull AdapterFlagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
