package com.muse.android.tv.ui.activity;

import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;

import java.util.List;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ViewHolder> {

    public interface ImageLoader {
        void load(ImageView view, String url, int position);
    }

    private final List<String> mImages;
    private final ImageLoader mLoader;
    private boolean mVertical = false;

    public ImageAdapter(List<String> images, ImageLoader loader) {
        this.mImages = images;
        this.mLoader = loader;
    }

    public void setVertical(boolean vertical) {
        if (this.mVertical != vertical) {
            this.mVertical = vertical;
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemCount() {
        return this.mImages.size();
    }

    @Override
    public ImageAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        ImageView imageView = new ImageView(parent.getContext());
        if (this.mVertical) {
            imageView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            imageView.setScaleType(ImageView.ScaleType.FIT_XY);
            imageView.setAdjustViewBounds(true);
        } else {
            imageView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        imageView.setBackgroundColor(parent.getContext().getResources().getColor(R.color.black));
        imageView.setPadding(0, 0, 0, 0);
        // #65：图片视图不再挂任何触摸监听——#63 挂在图上的 PanTouchListener 会在平移收尾
        // 吞掉最后一指的 ACTION_UP（点按显隐的点击序列被截断）并把状态残留在复用的
        // holder 上，是「缩放/功能框呼不出」的回归源。双指平移改由 ImageActivity 在两个
        // RecyclerView 容器层用 OnItemTouchListener 拦截实现（见那边注释）。
        return new ViewHolder(imageView);
    }

    @Override
    public void onBindViewHolder(ImageAdapter.ViewHolder holder, int position) {
        // 复用/重绑先归零平移，避免上一页的平移量残留到新图上（平移量由 Activity 的容器级手势写入）
        holder.imageView.setTranslationX(0f);
        holder.imageView.setTranslationY(0f);
        this.mLoader.load(holder.imageView, this.mImages.get(position), position);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        final ImageView imageView;

        ViewHolder(ImageView view) {
            super(view);
            this.imageView = view;
        }
    }
}
