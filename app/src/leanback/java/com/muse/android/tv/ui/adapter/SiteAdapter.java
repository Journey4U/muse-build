package com.muse.android.tv.ui.adapter;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.AdapterSiteBinding;
import com.muse.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.ViewHolder> {

    /** 本地源操作勾选模式（长按源条目进入）。 */
    public static final int TYPE_OPS = 3;

    private final OnClickListener listener;
    private final List<Site> mItems;
    private final List<Site> mFiltered;
    private final Set<Site> opChecked = new LinkedHashSet<>();
    private int type;

    public SiteAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.mFiltered = new ArrayList<>();
        this.addAll();
    }

    public interface OnClickListener {

        void onItemClick(Site item);
    }

    /** 本地源操作勾选模式的回调（与 OnClickListener 分开，避免影响既有实现类）。 */
    public interface OpsListener {

        void onItemLongClick(Site item);

        void onOpsChanged();
    }

    private OpsListener opsListener;

    public void setOpsListener(OpsListener opsListener) {
        this.opsListener = opsListener;
    }

    public void setType(int type) {
        this.type = type;
        notifyDataSetChanged();
    }

    public int getType() {
        return type;
    }

    public boolean isOps() {
        return type == TYPE_OPS;
    }

    /** 进入操作勾选模式并勾上第一项（长按命中的那条）。 */
    public void enterOps(Site first) {
        type = TYPE_OPS;
        opChecked.clear();
        if (first != null) opChecked.add(first);
        notifyDataSetChanged();
        if (opsListener != null) opsListener.onOpsChanged();
    }

    public void exitOps() {
        opChecked.clear();
        type = 0;
        notifyDataSetChanged();
    }

    /** 已勾选的源（保持勾选顺序）。 */
    public List<Site> getCheckedSites() {
        return new ArrayList<>(opChecked);
    }

    public void invertOps() {
        for (Site site : mFiltered) {
            if (!opChecked.remove(site)) opChecked.add(site);
        }
        notifyDataSetChanged();
        if (opsListener != null) opsListener.onOpsChanged();
    }

    public void selectAll() {
        if (isOps()) {
            opChecked.addAll(mFiltered);
            notifyDataSetChanged();
            if (opsListener != null) opsListener.onOpsChanged();
            return;
        }
        setEnable(type != 3);
    }

    public void cancelAll() {
        if (isOps()) {
            opChecked.clear();
            notifyDataSetChanged();
            if (opsListener != null) opsListener.onOpsChanged();
            return;
        }
        setEnable(type == 3);
    }

    private void addAll() {
        for (Site site : VodConfig.get().getSites()) if (!site.isHide()) mItems.add(site);
        mFiltered.addAll(mItems);
    }

    /** 配置重载后重建列表（本地源增删/搬家后用）。 */
    public void refresh() {
        mItems.clear();
        mFiltered.clear();
        opChecked.clear();
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
        return new ViewHolder(AdapterSiteBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
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
        holder.binding.text.setGravity(Setting.getSiteMode() == 0 ? Gravity.CENTER : Gravity.START);
    }

    private boolean getChecked(Site item) {
        if (type == 1) return item.isSearchable();
        if (type == 2) return item.isChangeable();
        if (type == TYPE_OPS) return opChecked.contains(item);
        return false;
    }

    private void setListener(Site item, int position) {
        if (type == 0) listener.onItemClick(item);
        if (type == 1) item.setSearchable(!item.isSearchable()).save();
        if (type == 2) item.setChangeable(!item.isChangeable()).save();
        if (type == TYPE_OPS) {
            if (!opChecked.remove(item)) opChecked.add(item);
            if (opsListener != null) opsListener.onOpsChanged();
        }
        if (type != 0) notifyItemChanged(position);
    }

    private boolean setLongListener(Site item) {
        if (type == 1) setEnable(!item.isSearchable());
        if (type == 2) setEnable(!item.isChangeable());
        if (type == 0 && opsListener != null) opsListener.onItemLongClick(item);
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
