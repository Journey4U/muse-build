package com.muse.android.tv.ui.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.AdapterSiteBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.ViewHolder> {

    /** 「当前源」高亮的配色（selected 已让位给跑马灯，故用颜色表达选中态）。 */
    private static final int COLOR_SELECTED_TEXT = 0xff174ea6;
    private static final int COLOR_SELECTED_BG = 0xffd2e3fc;
    private static final int COLOR_SELECTED_STROKE = 0xff0b57d0;
    private static final int COLOR_DOT_NORMAL = 0xffffffff;

    private final OnClickListener listener;
    private final List<Site> mItems;
    private final List<Site> mFiltered;
    private String group;
    private String keyword;
    private boolean search;
    private boolean change;
    private boolean searchMark;
    /** 当前列数：1 列显示小图标 + 14sp，2/3 列隐藏小图标并缩小字号。 */
    private int spanCount = 1;

    public SiteAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.mFiltered = new ArrayList<>();
        this.group = "";
        this.keyword = "";
        this.searchMark = true;
        this.addAll();
        this.filter("", "");
    }

    public interface OnClickListener {

        void onTextClick(Site item);

        void onSearchClick(int position, Site item);

        void onChangeClick(int position, Site item);

        boolean onSearchLongClick(Site item);

        boolean onChangeLongClick(Site item);
    }

    public SiteAdapter search(boolean search) {
        this.search = search;
        return this;
    }

    public SiteAdapter change(boolean change) {
        this.change = change;
        return this;
    }

    /** 是否在每行显示「可搜索」标识（放大镜/禁止图标），由弹窗右上角开关控制。 */
    public SiteAdapter searchMark(boolean searchMark) {
        this.searchMark = searchMark;
        return this;
    }

    /**
     * 列数变化时同步给适配器：2/3 列会隐藏每项右侧的小图标（可搜索/可切换）并缩小字号。
     * 由 {@code SiteDialog#setRecyclerView()} 调用。
     */
    public void setSpanCount(int spanCount) {
        this.spanCount = Math.min(3, Math.max(1, spanCount));
    }

    private void addAll() {
        for (Site site : VodConfig.get().getSites()) if (!site.isHide()) mItems.add(site);
    }

    public List<Site> getItems() {
        return mItems;
    }

    /** 当前**显示中**的列表（已按分组/关键词过滤）—— 用于滚动定位到在用的源。 */
    public List<Site> getFiltered() {
        return mFiltered;
    }

    /**
     * 按分组标记 + 关键词过滤源列表，两者为「与」关系；
     * 关键词同时匹配源名与源 key（大小写不敏感）。
     */
    public void filter(String group, String keyword) {
        this.group = group == null ? "" : group;
        this.keyword = keyword == null ? "" : keyword;
        String text = this.keyword.trim().toLowerCase(Locale.ROOT);
        boolean empty = TextUtils.isEmpty(text);
        mFiltered.clear();
        for (Site item : mItems) {
            boolean inGroup = this.group.isEmpty() || item.inGroup(this.group);
            String name = item.getName();
            String key = item.getKey();
            boolean hitName = !TextUtils.isEmpty(name) && name.toLowerCase(Locale.ROOT).contains(text);
            boolean hitKey = !TextUtils.isEmpty(key) && key.toLowerCase(Locale.ROOT).contains(text);
            if (inGroup && (empty || hitName || hitKey)) mFiltered.add(item);
        }
        notifyDataSetChanged();
    }

    public void filter(String keyword) {
        filter(group, keyword);
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
        boolean on = !search || change;
        // 2/3 列时隐藏每项右侧的小图标（可搜索/可切换），把宽度让给源名
        boolean iconsHidden = spanCount > 1;
        holder.binding.text.setText(item.getName());
        // RecyclerView 复用行会带着旧的跑马灯状态，重建 ellipsize 以重新触发滚动
        holder.binding.text.setEllipsize(null);
        holder.binding.text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        holder.binding.text.setEnabled(on);
        holder.binding.text.setFocusable(on);
        // selected 恒为 true 以启用跑马灯；「当前源」高亮改由背景/描边/文字与圆点颜色表达
        holder.binding.text.setSelected(true);
        holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, getTextSize());
        applySelected(holder.binding, item.isSelected());
        holder.binding.search.setImageResource(getSearchIcon(item));
        holder.binding.change.setImageResource(getChangeIcon(item));
        holder.binding.search.setVisibility(search && searchMark && !iconsHidden ? View.VISIBLE : View.GONE);
        holder.binding.change.setVisibility(change && !iconsHidden ? View.VISIBLE : View.GONE);
        holder.binding.health.setSelected(item.isSelected());
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.search.setOnClickListener(v -> listener.onSearchClick(position, item));
        holder.binding.change.setOnClickListener(v -> listener.onChangeClick(position, item));
        holder.binding.search.setOnLongClickListener(v -> listener.onSearchLongClick(item));
        holder.binding.change.setOnLongClickListener(v -> listener.onChangeLongClick(item));
        if (iconsHidden) {
            // 图标被隐藏后，长按整行空白处仍可批量切换（沿用小图标长按的批量语义）
            View.OnLongClickListener batch = v -> onItemLongClick(item);
            holder.binding.getRoot().setOnLongClickListener(batch);
        } else {
            holder.binding.getRoot().setOnLongClickListener(null);
        }
    }

    /** 小图标隐藏后的长按批量切换入口：优先「可切换」模式，其次「可搜索」模式。 */
    private boolean onItemLongClick(Site item) {
        if (change) return listener.onChangeLongClick(item);
        if (search) return listener.onSearchLongClick(item);
        return false;
    }

    /** 字号随列数自适应：1 列 14sp、2 列 13sp、3 列 12sp。 */
    private int getTextSize() {
        return spanCount >= 3 ? 12 : spanCount == 2 ? 13 : 14;
    }

    /** 「当前源」高亮：背景、描边、文字与健康圆点变色（选中态不再占用 selected）。 */
    private void applySelected(AdapterSiteBinding binding, boolean selected) {
        Context context = binding.getRoot().getContext();
        binding.text.setBackgroundTintList(selected ? ColorStateList.valueOf(COLOR_SELECTED_BG) : AppCompatResources.getColorStateList(context, R.color.dialog_outlined_button_bg));
        binding.text.setStrokeColor(selected ? ColorStateList.valueOf(COLOR_SELECTED_STROKE) : AppCompatResources.getColorStateList(context, R.color.dialog_outlined_button_stroke));
        binding.text.setTextColor(selected ? ColorStateList.valueOf(COLOR_SELECTED_TEXT) : AppCompatResources.getColorStateList(context, R.color.dialog_outlined_button_text));
        binding.health.setBackgroundTintList(ColorStateList.valueOf(selected ? COLOR_SELECTED_STROKE : COLOR_DOT_NORMAL));
    }

    private int getSearchIcon(Site item) {
        return item.isSearchable() ? R.drawable.ic_site_search : R.drawable.ic_site_block;
    }

    private int getChangeIcon(Site item) {
        return item.isChangeable() ? R.drawable.ic_site_change : R.drawable.ic_site_block;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteBinding binding;

        ViewHolder(@NonNull AdapterSiteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
