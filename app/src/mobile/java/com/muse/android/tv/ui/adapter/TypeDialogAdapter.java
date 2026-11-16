package com.muse.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Class;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「全部分类」弹窗的列表适配器。
 * 支持按关键词过滤分类名，供分类过多时快速定位并直达。
 */
public class TypeDialogAdapter extends RecyclerView.Adapter<TypeDialogAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Class> mItems;
    private final List<Class> mFiltered;

    public interface OnClickListener {

        void onItemClick(Class item);
    }

    public TypeDialogAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.mFiltered = new ArrayList<>();
    }

    public void addAll(List<Class> items) {
        mItems.clear();
        mItems.addAll(items);
        filter("");
    }

    public void filter(String keyword) {
        String text = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        boolean empty = TextUtils.isEmpty(text);
        mFiltered.clear();
        for (Class item : mItems) {
            String name = item.getTypeName();
            boolean hit = empty || (!TextUtils.isEmpty(name) && name.toLowerCase(Locale.ROOT).contains(text));
            if (hit) mFiltered.add(item);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mFiltered.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder((MaterialButton) LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_type_dialog, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Class item = mFiltered.get(position);
        holder.button.setText(item.getTypeName());
        holder.button.setSelected(item.isSelected());
        holder.button.setOnClickListener(v -> listener.onItemClick(item));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final MaterialButton button;

        ViewHolder(@NonNull MaterialButton button) {
            super(button);
            this.button = button;
        }
    }
}