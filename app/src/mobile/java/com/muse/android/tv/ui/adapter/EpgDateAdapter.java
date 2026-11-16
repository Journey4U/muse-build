package com.muse.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.bean.Epg;
import com.muse.android.tv.databinding.AdapterEpgDateBinding;
import com.muse.android.tv.utils.Formatters;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EpgDateAdapter extends RecyclerView.Adapter<EpgDateAdapter.ViewHolder> {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);
    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("EEE", Locale.CHINA);

    private final OnClickListener mListener;
    private final List<Epg> mItems;
    private String mToday;
    private String mSelected;

    public EpgDateAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        mToday = "";
        mSelected = "";
    }

    public void setToday(String today) {
        mToday = today == null ? "" : today;
    }

    public void setSelected(String date) {
        mSelected = date == null ? "" : date;
        notifyDataSetChanged();
    }

    public void addAll(List<Epg> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        mSelected = "";
        notifyDataSetChanged();
    }

    public Epg get(int position) {
        return mItems.get(position);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpgDateBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Epg item = mItems.get(position);
        holder.binding.text.setText(label(item));
        holder.binding.getRoot().setSelected(item.getDate().equals(mSelected));
        holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
    }

    private String label(Epg item) {
        try {
            LocalDate date = LocalDate.parse(item.getDate(), Formatters.DATE);
            LocalDate today = LocalDate.parse(mToday, Formatters.DATE);
            if (date.equals(today)) return "今天 " + date.format(DAY);
            if (date.equals(today.minusDays(1))) return "昨天 " + date.format(DAY);
            return date.format(WEEK) + " " + date.format(DAY);
        } catch (Exception e) {
            return item.getDate();
        }
    }

    public interface OnClickListener {

        void onItemClick(Epg item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterEpgDateBinding binding;

        ViewHolder(@NonNull AdapterEpgDateBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
