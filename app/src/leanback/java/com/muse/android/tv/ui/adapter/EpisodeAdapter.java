package com.muse.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.databinding.AdapterEpisodeBinding;
import com.muse.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Episode> mItems;
    private final int maxWidth;
    private int nextFocusDown;
    private int nextFocusUp;
    // 钉死布局批：当前显示为选中的那一格（切集只刷旧格和新格）
    private int shown = -1;

    public EpisodeAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(48);
    }

    public void addAll(List<Episode> items) {
        mItems.clear();
        mItems.addAll(items);
        shown = findSelected();
        notifyDataSetChanged();
    }

    private int findSelected() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return -1;
    }

    /** 切集后只刷旧选中格与新选中格，不整行重绑。 */
    public void refreshSelection() {
        int now = findSelected();
        if (now == shown) return;
        int old = shown;
        shown = now;
        if (old >= 0 && old < mItems.size()) notifyItemChanged(old);
        if (now >= 0) notifyItemChanged(now);
    }

    public void clear() {
        mItems.clear();
        shown = -1;
        notifyDataSetChanged();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public Episode getActivated() {
        return mItems.isEmpty() ? new Episode() : mItems.get(getPosition());
    }
    public List<Episode> getItems() {
        return mItems;
    }

    public Episode getNext() {
        int current = getPosition();
        int max = getItemCount() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    // 钉死布局批：上下焦点目标只存值；已显示的格子由 applyFocus 直接改，
    // 回到屏幕上的缓存格子在 onViewAttachedToWindow 补上，不再整行重绑。
    public void setNextFocusDown(int nextFocusDown) {
        this.nextFocusDown = nextFocusDown;
    }

    public void setNextFocusUp(int nextFocusUp) {
        this.nextFocusUp = nextFocusUp;
    }

    public void applyFocus(RecyclerView view) {
        for (int i = 0; i < view.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = view.getChildViewHolder(view.getChildAt(i));
            if (holder instanceof ViewHolder) setFocus((ViewHolder) holder);
        }
    }

    private void setFocus(ViewHolder holder) {
        holder.binding.text.setNextFocusUpId(nextFocusUp);
        holder.binding.text.setNextFocusDownId(nextFocusDown);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        setFocus(holder);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode item = mItems.get(position);
        holder.binding.text.setMaxWidth(maxWidth);
        setFocus(holder);
        if (item.isSelected()) shown = position;
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setText(item.getDesc().concat(item.getName()));
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpisodeBinding binding;

        ViewHolder(@NonNull AdapterEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}