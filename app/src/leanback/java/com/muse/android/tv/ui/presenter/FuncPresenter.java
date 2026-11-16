package com.muse.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.muse.android.tv.bean.Func;
import com.muse.android.tv.databinding.AdapterFuncBinding;

public class FuncPresenter extends Presenter {

    private final OnClickListener listener;

    public FuncPresenter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(Func item);

        boolean onLongClick(Func item);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new ViewHolder(AdapterFuncBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        Func item = (Func) object;
        ViewHolder holder = (ViewHolder) viewHolder;
        if (item.getId() > 0) holder.binding.getRoot().setId(item.getId());
        holder.binding.text.setText(item.getText());
        holder.binding.icon.setImageResource(item.getDrawable());
        if (item.getNextFocusLeft() > 0) holder.binding.getRoot().setNextFocusLeftId(item.getNextFocusLeft());
        if (item.getNextFocusRight() > 0) holder.binding.getRoot().setNextFocusRightId(item.getNextFocusRight());
        setOnClickListener(holder, view -> listener.onItemClick(item));
        holder.view.setOnLongClickListener(view -> listener.onLongClick(item));
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterFuncBinding binding;

        public ViewHolder(@NonNull AdapterFuncBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}