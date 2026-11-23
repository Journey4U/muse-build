package com.muse.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.muse.android.tv.databinding.AdapterWebHomeBinding;

/**
 * 纯网页源进站主页（Liu 2026-11-23 方案 A）：首页页的「进站主页」大卡片——站名 + 进站主页。
 * 焦点写法照首页功能行 adapter_func（selector_item 背景、focusable），遥控器方向键可聚焦、确认键进站。
 */
public class WebHomePresenter extends Presenter {

    private final Runnable listener;

    public WebHomePresenter(Runnable listener) {
        this.listener = listener;
    }

    /** 卡片数据：只有站名。 */
    public static final class Card {

        private final String name;

        public Card(String name) {
            this.name = name == null ? "" : name;
        }

        public String getName() {
            return name;
        }
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new ViewHolder(AdapterWebHomeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        Card item = (Card) object;
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.binding.name.setText(item.getName());
        setOnClickListener(holder, view -> listener.run());
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterWebHomeBinding binding;

        public ViewHolder(@NonNull AdapterWebHomeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
