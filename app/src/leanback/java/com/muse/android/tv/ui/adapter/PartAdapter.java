package com.muse.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.databinding.AdapterPartBinding;
import com.muse.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class PartAdapter extends RecyclerView.Adapter<PartAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<String> mItems;
    private final int maxWidth;
    private int nextFocusUp;

    public PartAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(48);
    }

    public void addAll(List<String> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    // 钉死布局批：上焦点目标只存值，已显示的格子由 applyFocus 直接改，不整行重绑。
    public void setNextFocusUp(int nextFocusUp) {
        this.nextFocusUp = nextFocusUp;
    }

    public void applyFocus(RecyclerView view) {
        for (int i = 0; i < view.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = view.getChildViewHolder(view.getChildAt(i));
            if (holder instanceof ViewHolder) ((ViewHolder) holder).binding.text.setNextFocusUpId(nextFocusUp);
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        holder.binding.text.setNextFocusUpId(nextFocusUp);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterPartBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String text = mItems.get(position);
        holder.binding.text.setText(text);
        holder.binding.text.setMaxWidth(maxWidth);
        holder.binding.text.setNextFocusUpId(nextFocusUp);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(text));
    }

    public interface OnClickListener {

        void onItemClick(String item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterPartBinding binding;

        ViewHolder(@NonNull AdapterPartBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
