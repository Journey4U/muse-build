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
import com.muse.android.tv.api.SiteApi;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.databinding.ActivityImageBinding;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.dialog.NovelCatalogDialog;
import com.muse.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Ported from the original MuseTV 5.6.3 TV build (Liu's reference APK, 2026-10-24):
// remote-first image viewer - single page mode (DPAD flips) and seamless vertical
// mode (MENU toggles). Viewing keys auto-hide top + bottom; OK shows only the top menu.
public class ImageActivity extends BaseActivity {

    public static final int REQUEST_IMAGE = 1003;

    private ActivityImageBinding mBinding;
    private final List<String> mImages = new ArrayList<>();
    private String mUserAgent = "";
    private String mReferer = "";
    private String mTitle = "";
    private int mPosition = 0;
    private boolean mVertical = true;
    private ArrayList<String> mEpisodeNames;
    private ArrayList<String> mEpisodeUrls;
    private String mSiteKey = "";
    private String mVodId = "";
    private String mFlag = "";
    private String mVodName = "";
    private String mVodPic = "";
    private boolean mResolving = false;
    private int mEpisodePosition = 0;
    // 遥控器两态：false = 翻图模式（方向键翻图/滚动，OK 进入控件模式）；
    // true = 控件模式（方向键在上一话/目录/下一话间移动、OK 点按）。
    // 不能只看标题栏显隐——标题栏初始就是可见的，那时仍是翻图模式。
    private boolean mControlsMode = false;

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

    // 电视版漫画阅读器内切话：与小说 startForResult 同模式——带全集话名与当前话序号进图片页，
    // 选话后回传 episode_position 给 VideoActivity，由它 selectEpisode 重新起播该话。
    public static void startForResult(Activity activity, String title, String picsUrl, Map<String, String> header, ArrayList<String> episodeNames, int episodePosition) {
        startForResult(activity, title, picsUrl, header, episodeNames, null, episodePosition);
    }

    /** 带全话 url 的重载（跨类型分发用）：页内切到图集话直接换内容续看，不回传中转。 */
    public static void startForResult(Activity activity, String title, String picsUrl, Map<String, String> header, ArrayList<String> episodeNames, ArrayList<String> episodeUrls, int episodePosition) {
        startForResult(activity, title, picsUrl, header, episodeNames, episodeUrls, episodePosition, "", "", "", "", "");
    }

    /** 带站点上下文的重载：切到未解析标识话时页内回源解析再分发，切到视频话按序号强播。 */
    public static void startForResult(Activity activity, String title, String picsUrl, Map<String, String> header, ArrayList<String> episodeNames, ArrayList<String> episodeUrls, int episodePosition, String siteKey, String vodId, String flag, String vodName, String vodPic) {
        Intent intent = new Intent(activity, ImageActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("picsUrl", picsUrl);
        if (header != null) {
            intent.putExtra("userAgent", header.get("User-Agent"));
            intent.putExtra("referer", header.get("Referer"));
        }
        intent.putExtra("episodeNames", episodeNames);
        if (episodeUrls != null) intent.putExtra("episodeUrls", episodeUrls);
        intent.putExtra("episodePosition", episodePosition);
        intent.putExtra("siteKey", siteKey);
        intent.putExtra("vodId", vodId);
        intent.putExtra("flag", flag);
        intent.putExtra("vodName", vodName);
        intent.putExtra("vodPic", vodPic);
        activity.startActivityForResult(intent, REQUEST_IMAGE);
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
        mEpisodeNames = getIntent().getStringArrayListExtra("episodeNames");
        mEpisodeUrls = getIntent().getStringArrayListExtra("episodeUrls");
        if (mEpisodeUrls == null) mEpisodeUrls = new ArrayList<>();
        mEpisodePosition = getIntent().getIntExtra("episodePosition", 0);
        mSiteKey = getIntent().getStringExtra("siteKey");
        if (mSiteKey == null) mSiteKey = "";
        mVodId = getIntent().getStringExtra("vodId");
        if (mVodId == null) mVodId = "";
        mFlag = getIntent().getStringExtra("flag");
        if (mFlag == null) mFlag = "";
        mVodName = getIntent().getStringExtra("vodName");
        if (mVodName == null) mVodName = "";
        mVodPic = getIntent().getStringExtra("vodPic");
        if (mVodPic == null) mVodPic = "";
        if (mEpisodeNames == null) mEpisodeNames = new ArrayList<>();
        if (picsUrl != null && picsUrl.startsWith("pics://")) {
            mImages.addAll(Arrays.asList(picsUrl.substring("pics://".length()).split("&&")));
        }
        mBinding.titleText.setText(mTitle);
        updateEpisodeButtons();
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
        // 默认条漫（纵向）：按 toggleMode 的竖向一半在初始即应用（与手机版同批，Liu 2026-11-07 定）
        mBinding.imageView.setVisibility(View.GONE);
        mBinding.recyclerView.setVisibility(View.VISIBLE);
        updateModeText();
    }

    @Override
    protected void initEvent() {
        mBinding.getRoot().setFocusable(true);
        mBinding.getRoot().setFocusableInTouchMode(true);
        mBinding.getRoot().requestFocus();
        mBinding.prevEpisode.setOnClickListener(view -> {
            if (hasEpisodes() && mEpisodePosition > 0) gotoEpisode(mEpisodePosition - 1);
        });
        mBinding.tocEpisode.setOnClickListener(view -> {
            if (hasEpisodes()) showEpisodeDialog();
        });
        mBinding.nextEpisode.setOnClickListener(view -> {
            if (hasEpisodes() && mEpisodePosition < mEpisodeNames.size() - 1) gotoEpisode(mEpisodePosition + 1);
        });
    }

    private boolean hasEpisodes() {
        return mEpisodeNames != null && mEpisodeNames.size() > 1;
    }

    private void gotoEpisode(int position) {
        if (!hasEpisodes() || position < 0 || position >= mEpisodeNames.size() || position == mEpisodePosition) return;
        // 跨类型分发（与阅读器同构）：图集话页内换图续看；视频直链按序号强播；
        // 未解析标识先回源解析再按实际类型分发；正文或无上下文时回传序号给调用页分发
        String target = position < mEpisodeUrls.size() ? mEpisodeUrls.get(position) : "";
        if (target == null) target = "";
        if (target.startsWith("pics://")) {
            showPicsEpisode(position, target);
            return;
        }
        if (isDirectMedia(target) && !mSiteKey.isEmpty() && !mVodId.isEmpty()) {
            VideoActivity.startForceEpisode(this, mSiteKey, mVodId, mVodName, mVodPic, mEpisodeNames.get(position), position);
            finish();
            return;
        }
        if (mSiteKey.isEmpty() || target.startsWith("novel://")) {
            returnPosition(position);
            return;
        }
        resolveEpisode(position, target);
    }

    /** 图集话页内换图续看。 */
    private void showPicsEpisode(int position, String picsUrl) {
        mEpisodePosition = position;
        mImages.clear();
        mImages.addAll(Arrays.asList(picsUrl.substring("pics://".length()).split("&&")));
        mPosition = 0;
        mBinding.titleText.setText(mEpisodeNames.get(position));
        updateEpisodeButtons();
        updatePageInfo();
        if (mVertical) {
            mBinding.recyclerView.getAdapter().notifyDataSetChanged();
            mBinding.recyclerView.scrollToPosition(0);
        } else {
            showImage();
        }
    }

    /** 回传调用页分发（旧路兜底）。 */
    private void returnPosition(int position) {
        Intent intent = new Intent();
        intent.putExtra("episode_position", position);
        setResult(RESULT_OK, intent);
        finish();
    }

    /** 目标标识是一整条无空白的 http(s) 直链即视频话（与手机版阅读器同口径）。 */
    private boolean isDirectMedia(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) return false;
        for (int i = 0; i < v.length(); i++) if (Character.isWhitespace(v.charAt(i))) return false;
        return true;
    }

    /** 未解析标识回源：解析出图集→页内换图，视频→按序号强播，正文→回传调用页开阅读器。 */
    private void resolveEpisode(int position, String target) {
        if (mResolving) return;
        mResolving = true;
        Task.execute(() -> {
            String raw = "";
            try {
                Result result = SiteApi.playerContent(mSiteKey, mFlag, target);
                if (result != null) raw = result.getUrl().v();
            } catch (Throwable ignored) {
            }
            String url = raw == null ? "" : raw;
            runOnUiThread(() -> {
                mResolving = false;
                if (isFinishing()) return;
                if (url.startsWith("pics://")) {
                    showPicsEpisode(position, url);
                } else if (!url.isEmpty() && !url.startsWith("novel://") && !mVodId.isEmpty()) {
                    VideoActivity.startForceEpisode(this, mSiteKey, mVodId, mVodName, mVodPic, mEpisodeNames.get(position), position);
                    finish();
                } else {
                    returnPosition(position);
                }
            });
        });
    }

    private void showEpisodeDialog() {
        // 复用刚做的小说目录对话框（选集次级页面同构：区间页签 + 多列自适应网格，遥控器可聚焦操作）。
        NovelCatalogDialog.create()
                .names(mEpisodeNames)
                .position(mEpisodePosition)
                .listener(this::gotoEpisode)
                .show(this);
    }

    private void updateEpisodeButtons() {
        boolean has = hasEpisodes();
        mBinding.prevEpisode.setVisibility(!has || mEpisodePosition <= 0 ? View.GONE : View.VISIBLE);
        mBinding.tocEpisode.setVisibility(has ? View.VISIBLE : View.GONE);
        mBinding.nextEpisode.setVisibility(!has || mEpisodePosition >= mEpisodeNames.size() - 1 ? View.GONE : View.VISIBLE);
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

    // 看图模式的唯一藏法（共用规则，独立方法，不内联到各处）：上方 titleBar 与下方
    // modeText 同时 GONE。旧逻辑是两者互换显隐，所以看图时总有一条挡着画面。
    private void hideAll() {
        mControlsMode = false;
        mBinding.titleBar.setVisibility(View.GONE);
        mBinding.modeText.setVisibility(View.GONE);
        mBinding.getRoot().requestFocus();
    }

    private void showControls() {
        mControlsMode = true;
        mBinding.titleBar.setVisibility(View.VISIBLE);
        mBinding.modeText.setVisibility(View.GONE);
        if (mBinding.tocEpisode.getVisibility() == View.VISIBLE) mBinding.tocEpisode.requestFocus();
        else mBinding.getRoot().requestFocus();
    }

    private void hideControls() {
        hideAll();
    }

    @Override
    protected void onBackInvoked() {
        // 控件模式下先按返回只退出控件模式、回翻图模式，不直接退出图片页。
        if (mControlsMode) hideControls();
        else super.onBackInvoked();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 遥控器两态（以 mControlsMode 为准，不以标题栏显隐为准——标题栏初始就是可见的）：
        // 控件模式下方向键走默认焦点导航（在上一话/目录/下一话间移动），OK 点按聚焦的按钮；
        // 翻图模式下方向键翻图/滚动，OK 进入控件模式（有话列表时）或沿用旧的显隐标题栏（无话列表时）。
        if (mControlsMode) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return super.onKeyDown(keyCode, event);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                View focused = getCurrentFocus();
                if (focused == mBinding.prevEpisode || focused == mBinding.tocEpisode || focused == mBinding.nextEpisode) {
                    return super.onKeyDown(keyCode, event);
                }
                hideControls();
                return true;
            }
        }
        // 翻图模式：上下左右键一律先全藏（上方+下方同时 GONE），再翻图/滚动。
        // 已全藏时 hideAll 是幂等的，不影响后续翻页。菜单键不在此列：切换横/纵模式
        // 时不重显下方提示（审核点 3 允许不重显），翻页后仍是全藏。
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            hideAll();
        }
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
                // 全藏后按 OK：只调出上方菜单（showControls 只显 titleBar、下方仍藏），
                // 有话时聚焦目录按钮、无话时聚焦根视图，之后方向键/OK 仍可实际操作选择。
                showControls();
                return true;
            case KeyEvent.KEYCODE_MENU:
                toggleMode();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
