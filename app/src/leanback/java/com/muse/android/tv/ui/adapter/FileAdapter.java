package com.muse.android.tv.ui.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.AdapterFileBinding;
import com.muse.android.tv.utils.ThemeTone;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<File> mItems;
    private final Map<String, String> mLabels = new HashMap<>();

    public FileAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public void addAll(List<File> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    /** 个别条目的显示名（按绝对路径），没有的照常显示文件名。 */
    public void setLabels(Map<String, String> labels) {
        mLabels.clear();
        if (labels != null) mLabels.putAll(labels);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = mItems.get(position);
        String label = mLabels.get(file.getAbsolutePath());
        holder.binding.name.setText(label != null ? label : file.getName());
        holder.binding.image.setImageResource(file.isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file);
        // 图标跟主题走：浅色壁纸时文件夹用赤陶强调色、文件用正文墨色；深色壁纸保持原样
        android.content.Context context = holder.binding.image.getContext();
        ColorStateList tint = null;
        if (ThemeTone.isLight(context)) tint = ColorStateList.valueOf(file.isDirectory() ? ContextCompat.getColor(context, R.color.muse_tone_accent) : ThemeTone.inkColor(context));
        ImageViewCompat.setImageTintList(holder.binding.image, tint);
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(file));
    }

    public interface OnClickListener {

        void onItemClick(File file);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFileBinding binding;

        ViewHolder(@NonNull AdapterFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
