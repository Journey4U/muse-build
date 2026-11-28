package com.muse.android.tv.ui.adapter;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.AdapterSiteBinding;

import java.util.ArrayList;
import java.util.List;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Site> mItems;
    private final List<Site> mFiltered;
    private int type;
    private int itemHeight;
    private View.OnKeyListener keyListener;

    public SiteAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.mFiltered = new ArrayList<>();
        this.addAll();
    }

    public interface OnClickListener {

        void onItemClick(Site item);

        void onItemLongClick(Site item);
    }

    /** 胶囊高度（px）：0 = 用布局原版 40dp；只有屏幕矮到行距不足 4dp 时弹窗才传入缩小值。字号、勾选框不变。 */
    public void setItemHeight(int itemHeight) {
        this.itemHeight = itemHeight;
    }

    public void setKeyListener(View.OnKeyListener keyListener) {
        this.keyListener = keyListener;
    }

    public void setType(int type) {
        this.type = type;
        notifyDataSetChanged();
    }

    public int getType() {
        return type;
    }

    public void selectAll() {
        setEnable(true);
    }

    public void cancelAll() {
        setEnable(false);
    }

    private void addAll() {
        for (Site site : VodConfig.get().getSites()) if (!site.isHide()) mItems.add(site);
        mFiltered.addAll(mItems);
    }

    /** 配置重载后重建列表（本地源增删后用）。 */
    public void refresh() {
        mItems.clear();
        mFiltered.clear();
        addAll();
        notifyDataSetChanged();
    }

    public List<Site> getItems() {
        return mItems;
    }

    public void filter(String keyword) {
        mFiltered.clear();
        if (keyword.isEmpty()) {
            mFiltered.addAll(mItems);
        } else {
            String lowerCase = keyword.toLowerCase();
            for (Site site : mItems) if (site.getName().toLowerCase().contains(lowerCase)) mFiltered.add(site);
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
        ViewHolder holder = new ViewHolder(AdapterSiteBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        if (itemHeight > 0) {
            ViewGroup.LayoutParams lp = holder.binding.getRoot().getLayoutParams();
            lp.height = itemHeight;
            holder.binding.getRoot().setLayoutParams(lp);
        }
        if (keyListener != null) holder.binding.getRoot().setOnKeyListener(keyListener);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mFiltered.get(position);
        holder.binding.text.setText(item.getName());
        holder.binding.check.setChecked(getChecked(item));
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.check.setVisibility(type == 0 ? View.GONE : View.VISIBLE);
        holder.binding.getRoot().setOnLongClickListener(v -> setLongListener(item));
        holder.binding.getRoot().setOnClickListener(v -> setListener(item, position));
        holder.binding.text.setGravity(Gravity.CENTER);
        // 触屏下打开不抢焦点，当前源直接画白框（与原版焦点框同一张图）；遥控器下白框跟焦点走，避免两个白框。
        boolean frame = item.isSelected() && holder.binding.getRoot().isInTouchMode();
        holder.binding.getRoot().setBackgroundResource(frame ? R.drawable.shape_chip_round_focused : R.drawable.selector_chip_round);
    }

    private boolean getChecked(Site item) {
        if (type == 1) return item.isSearchable();
        if (type == 2) return item.isChangeable();
        return false;
    }

    private void setListener(Site item, int position) {
        if (type == 0) listener.onItemClick(item);
        if (type == 1) item.setSearchable(!item.isSearchable()).save();
        if (type == 2) item.setChangeable(!item.isChangeable()).save();
        if (type != 0) notifyItemChanged(position);
    }

    private boolean setLongListener(Site item) {
        if (type == 1) setEnable(!item.isSearchable());
        if (type == 2) setEnable(!item.isChangeable());
        if (type == 0) listener.onItemLongClick(item);
        return true;
    }

    private void setEnable(boolean enable) {
        if (type == 1) for (Site site : mItems) site.setSearchable(enable).save();
        if (type == 2) for (Site site : mItems) site.setChangeable(enable).save();
        notifyItemRangeChanged(0, getItemCount());
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteBinding binding;

        ViewHolder(@NonNull AdapterSiteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
