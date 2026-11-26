package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivityComicShelfBinding;
import com.muse.android.tv.databinding.AdapterComicBinding;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.comic.ComicImageLoader;
import com.muse.android.tv.ui.comic.LocalComics;
import com.muse.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画封面格子页（手机底栏「漫画」格的入口，与书架「本地漫画」栏同一份数据 LocalComics.comics()）：
 * 封面取第一话第一张图，标题下面显示看到第几话第几页；点哪部打开哪部，进度续看；长按移除（只删记录与进度，不删源文件）。
 */
public class ComicShelfActivity extends BaseActivity {

    private ActivityComicShelfBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, ComicShelfActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityComicShelfBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 3 : 2));
        mBinding.recycler.setAdapter(new ComicAdapter());
        loadData();
    }

    private void loadData() {
        List<JSONObject> comics = LocalComics.comics();
        mBinding.empty.setVisibility(comics.isEmpty() ? View.VISIBLE : View.GONE);
        ((ComicAdapter) mBinding.recycler.getAdapter()).setComics(comics);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) loadData();
    }

    private class ComicAdapter extends RecyclerView.Adapter<ComicAdapter.Holder> {

        private final List<JSONObject> comics = new ArrayList<>();

        void setComics(List<JSONObject> items) {
            comics.clear();
            comics.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(AdapterComicBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            JSONObject comic = comics.get(position);
            holder.binding.title.setText(comic.optString("title"));
            holder.binding.sub.setText(LocalComics.progressText(ComicShelfActivity.this, comic));
            ComicImageLoader.load(holder.binding.cover, comic.optString("cover"));
            holder.itemView.setOnClickListener(v -> ImageActivity.openLocalComic(ComicShelfActivity.this, comic.optString("path")));
            holder.itemView.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(ComicShelfActivity.this).setTitle(R.string.shelf_remove_book)
                        .setMessage(R.string.comic_remove_msg)
                        .setNegativeButton(R.string.dialog_negative, null)
                        .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                            LocalComics.remove(comic.optString("path"));
                            loadData();
                        }).show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return comics.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final AdapterComicBinding binding;

            Holder(AdapterComicBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
