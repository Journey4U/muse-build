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

    /** 局部刷新标记：只改勾选框显隐/勾选/选中/白框，不重设文字（共享网格挂进不同入口时用）。 */
    public static final Object PAYLOAD_STATE = new Object();

    private OnClickListener listener;
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

    /** 共享网格挂进哪个弹窗，点击就交给哪个弹窗；摘下时置空，避免泄漏。 */
    public void setListener(OnClickListener listener) {
        this.listener = listener;
    }

    public int getItemHeight() {
        return itemHeight;
    }

    /** 切入口类型（切源/搜索勾选/换源勾选）：只局部刷新状态，不重设文字。 */
    public void setType(int type) {
        this.type = type;
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_STATE);
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
        holder.baseHeight = holder.binding.getRoot().getLayoutParams().height;
        applyHeight(holder);
        // 遥控器按键转给当前挂载的弹窗（共享网格会被不同弹窗复用，不能把建格子时的弹窗写死）。
        holder.binding.getRoot().setOnKeyListener((v, keyCode, event) -> keyListener != null && keyListener.onKey(v, keyCode, event));
        // 快划卡顿修法：背景、对齐、点击/长按监听只在建格子时设一次，绑定时不再重复创建。
        holder.binding.getRoot().setBackgroundResource(R.drawable.selector_chip_round_site);
        holder.binding.text.setGravity(Gravity.CENTER);
        holder.binding.getRoot().setOnClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION || position >= mFiltered.size()) return;
            setListener(mFiltered.get(position), position);
        });
        holder.binding.getRoot().setOnLongClickListener(v -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION || position >= mFiltered.size()) return true;
            return setLongListener(mFiltered.get(position));
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            bindState(holder, mFiltered.get(position));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mFiltered.get(position);
        // 源名排版走应用级缓存（后台预算好的）；没算到就当场照常设置。
        SiteTextCache.bind(holder.binding.text, item);
        bindState(holder, item);
    }

    /** 只改状态：勾选、选中、勾选框显隐、白框。不碰文字，不重排。 */
    private void bindState(@NonNull ViewHolder holder, Site item) {
        applyHeight(holder);
        holder.binding.check.setChecked(getChecked(item));
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.check.setVisibility(type == 0 ? View.GONE : View.VISIBLE);
        // 触屏下打开不抢焦点，当前源直接画白框（与原版焦点框同一张图）；遥控器下白框跟焦点走，避免两个白框。
        // 只切 activated 状态，不换背景图、不重排（selector_chip_round_site 的 activated 项即白框）。
        holder.binding.getRoot().setActivated(item.isSelected() && holder.binding.getRoot().isInTouchMode());
    }

    /** 胶囊高度：原版 40dp，只有矮屏行距不足时才用弹窗传入的值；高度没变就什么都不做（共享网格换入口时用）。 */
    private void applyHeight(@NonNull ViewHolder holder) {
        int h = itemHeight > 0 ? itemHeight : holder.baseHeight;
        ViewGroup.LayoutParams lp = holder.binding.getRoot().getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            holder.binding.getRoot().setLayoutParams(lp);
        }
    }

    private boolean getChecked(Site item) {
        if (type == 1) return item.isSearchable();
        if (type == 2) return item.isChangeable();
        return false;
    }

    private void setListener(Site item, int position) {
        if (type == 0 && listener != null) listener.onItemClick(item);
        if (type == 1) item.setSearchable(!item.isSearchable()).save();
        if (type == 2) item.setChangeable(!item.isChangeable()).save();
        if (type != 0) notifyItemChanged(position, PAYLOAD_STATE);
    }

    private boolean setLongListener(Site item) {
        if (type == 1) setEnable(!item.isSearchable());
        if (type == 2) setEnable(!item.isChangeable());
        if (type == 0 && listener != null) listener.onItemLongClick(item);
        return true;
    }

    private void setEnable(boolean enable) {
        if (type == 1) for (Site site : mItems) site.setSearchable(enable).save();
        if (type == 2) for (Site site : mItems) site.setChangeable(enable).save();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_STATE);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSiteBinding binding;
        private int baseHeight;

        ViewHolder(@NonNull AdapterSiteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
