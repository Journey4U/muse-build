package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.muse.android.tv.databinding.ActivityImageBinding;
import com.muse.android.tv.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Ported from the original MuseTV 5.6.3 TV build (Liu's reference APK, 2026-10-24):
// remote-first image viewer - single page mode (DPAD flips) and seamless vertical
// mode (MENU toggles), OK toggles the title bar.
public class ImageActivity extends BaseActivity {

    private ActivityImageBinding mBinding;
    private final List<String> mImages = new ArrayList<>();
    private String mUserAgent = "";
    private String mReferer = "";
    private String mTitle = "";
    private int mPosition = 0;
    private boolean mVertical = false;

    private class ImageListAdapter extends RecyclerView.Adapter<ImageListAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final ImageView imageView;

            Holder(ImageView view) {
                super(view);
                imageView = view;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView imageView = new ImageView(parent.getContext());
            imageView.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return new Holder(imageView);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            loadImage(holder.imageView, mImages.get(position));
        }

        @Override
        public int getItemCount() {
            return mImages.size();
        }
    }

    public static void start(Activity activity, String title, String picsUrl, Map<String, String> header) {
        Intent intent = new Intent(activity, ImageActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("picsUrl", picsUrl);
        if (header != null) {
            intent.putExtra("userAgent", header.get("User-Agent"));
            intent.putExtra("referer", header.get("Referer"));
        }
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityImageBinding.inflate(getLayoutInflater());
        return mBinding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        String picsUrl = getIntent().getStringExtra("picsUrl");
        mTitle = getIntent().getStringExtra("title");
        mUserAgent = getIntent().getStringExtra("userAgent");
        mReferer = getIntent().getStringExtra("referer");
        if (mUserAgent == null) mUserAgent = "";
        if (mReferer == null) mReferer = "";
        if (mTitle == null) mTitle = "";
        if (picsUrl != null && picsUrl.startsWith("pics://")) {
            mImages.addAll(Arrays.asList(picsUrl.substring("pics://".length()).split("&&")));
        }
        mBinding.titleText.setText(mTitle);
        updatePageInfo();
        updateModeText();
        mBinding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recyclerView.setAdapter(new ImageListAdapter());
        mBinding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager == null) return;
                int first = manager.findFirstVisibleItemPosition();
                if (first < 0 || first == mPosition) return;
                mPosition = first;
                updatePageInfo();
            }
        });
        showImage();
    }

    @Override
    protected void initEvent() {
        mBinding.getRoot().setFocusable(true);
        mBinding.getRoot().setFocusableInTouchMode(true);
        mBinding.getRoot().requestFocus();
    }

    private void loadImage(ImageView view, String url) {
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (!mUserAgent.isEmpty()) builder.addHeader("User-Agent", mUserAgent);
        if (!mReferer.isEmpty()) builder.addHeader("Referer", mReferer);
        Glide.with(this).load(new GlideUrl(url, builder.build())).into(view);
    }

    private void showImage() {
        if (mImages.isEmpty()) return;
        loadImage(mBinding.imageView, mImages.get(mPosition));
        updatePageInfo();
    }

    private void nextImage() {
        if (mPosition < mImages.size() - 1) {
            mPosition++;
            showImage();
        }
    }

    private void prevImage() {
        if (mPosition > 0) {
            mPosition--;
            showImage();
        }
    }

    private void updatePageInfo() {
        mBinding.pageText.setText((mPosition + 1) + " / " + mImages.size());
    }

    private void updateModeText() {
        mBinding.modeText.setText(mVertical ? "纵向无缝（菜单键切换）" : "横向单张（菜单键切换）");
    }

    private void toggleMode() {
        mVertical = !mVertical;
        mBinding.imageView.setVisibility(mVertical ? View.GONE : View.VISIBLE);
        mBinding.recyclerView.setVisibility(mVertical ? View.VISIBLE : View.GONE);
        if (mVertical) mBinding.recyclerView.scrollToPosition(mPosition);
        else showImage();
        updateModeText();
    }

    private void toggleTitleBar() {
        boolean visible = mBinding.titleBar.getVisibility() == View.VISIBLE;
        mBinding.titleBar.setVisibility(visible ? View.GONE : View.VISIBLE);
        mBinding.modeText.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mVertical) mBinding.recyclerView.smoothScrollBy(0, -400);
                else prevImage();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mVertical) mBinding.recyclerView.smoothScrollBy(0, 400);
                else nextImage();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!mVertical) prevImage();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!mVertical) nextImage();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleTitleBar();
                return true;
            case KeyEvent.KEYCODE_MENU:
                toggleMode();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
