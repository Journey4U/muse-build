package com.muse.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Cache;
import com.muse.android.tv.bean.Class;
import com.muse.android.tv.databinding.AdapterTypeBinding;
import com.muse.android.tv.utils.ResUtil;

/**
 * 分类条 presenter（原版 OK 5.1.6 大屏版 R3/P 的移植）。
 * 图标门控与原版一致：Cache.get(type) 为空→无图标；flag 真→ic_vod_filter_off、假→ic_vod_filter_on
 * （注意命名与直觉相反，原版即如此，照搬）。
 * 与我方旧版的区别：图标门控由 Cache.copy 改为 Cache.get（原版用法， emptiness 相同且免深拷贝）；
 * 新增长按回调（原版 R3/C case 1 → HomeActivity.q()，仅首页页有效）。
 */
public class ClassPresenter extends Presenter {

    private final OnClickListener listener;

    public ClassPresenter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Class item);

        default void onItemUpKey(Class item) {
        }

        default void onItemLongClick(Class item) {
        }
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new ViewHolder(AdapterTypeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        Class item = (Class) object;
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.binding.text.setText(item.getTypeName());
        boolean hasFilter = !Cache.get(item).isEmpty();
        int icon = !hasFilter ? 0 : item.getFilter() ? R.drawable.ic_vod_filter_off : R.drawable.ic_vod_filter_on;
        holder.binding.text.setCompoundDrawablesWithIntrinsicBounds(0, 0, icon, 0);
        holder.binding.text.setCompoundDrawablePadding(ResUtil.dp2px(4));
        holder.binding.text.setListener(() -> listener.onItemUpKey(item));
        holder.view.setOnClickListener(v -> listener.onItemClick(item));
        holder.view.setOnLongClickListener(v -> {
            listener.onItemLongClick(item);
            return true;
        });
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterTypeBinding binding;

        public ViewHolder(@NonNull AdapterTypeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
