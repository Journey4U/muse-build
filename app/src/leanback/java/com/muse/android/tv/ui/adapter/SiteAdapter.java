package com.muse.android.tv.ui.adapter;

import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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
    private float textSize;
    private int checkSize;
    private int checkMargin;
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

    /** 弹窗按比例定死后由弹窗传入的格子尺寸（px）；0 = 用布局默认值。 */
    public void setItemSize(int itemHeight, float textSize, int checkSize, int checkMargin) {
        this.itemHeight = itemHeight;
        this.textSize = textSize;
        this.checkSize = checkSize;
        this.checkMargin = checkMargin;
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
            holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
            ViewGroup.MarginLayoutParams clp = (ViewGroup.MarginLayoutParams) holder.binding.check.getLayoutParams();
            clp.width = checkSize;
            clp.height = checkSize;
            clp.setMarginStart(checkMargin);
            holder.binding.check.setLayoutParams(clp);
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
