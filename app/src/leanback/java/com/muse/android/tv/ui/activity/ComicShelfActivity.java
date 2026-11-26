package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画封面格子页（电视功能区「漫画」按钮的入口，与书架「本地漫画」栏同一份数据 LocalComics.comics()）：
 * 封面取第一话第一张图，标题下面显示看到第几话第几页；点哪部打开哪部，进度续看。
 * 遥控器：方向键在格子间移动，OK 打开；长按 OK 进删除模式（选中即删记录与进度，不删源文件），返回键先退删除模式。
 */
public class ComicShelfActivity extends BaseActivity {

    private ActivityComicShelfBinding mBinding;
    private ComicAdapter mAdapter;
    private boolean mDelete;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, ComicShelfActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityComicShelfBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, 4));
        mBinding.recycler.setAdapter(mAdapter = new ComicAdapter());
        mBinding.recycler.requestFocus();
        loadData();
    }

    private void loadData() {
        List<JSONObject> comics = LocalComics.comics();
        mBinding.empty.setVisibility(comics.isEmpty() ? View.VISIBLE : View.GONE);
        mAdapter.setComics(comics);
        if (comics.isEmpty()) mDelete = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) loadData();
    }

    @Override
    protected void onBackInvoked() {
        if (mDelete) {
            mDelete = false;
            mAdapter.notifyDataSetChanged();
        } else {
            super.onBackInvoked();
        }
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
            holder.itemView.setOnClickListener(v -> {
                if (mDelete) {
                    LocalComics.remove(comic.optString("path"));
                    loadData();
                    if (comics.isEmpty()) mDelete = false;
                } else {
                    ImageActivity.openLocalComic(ComicShelfActivity.this, comic.optString("path"));
                }
            });
            holder.itemView.setOnLongClickListener(v -> {
                if (!mDelete) {
                    mDelete = true;
                    Notify.show(getString(R.string.comic_delete_hint));
                }
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
