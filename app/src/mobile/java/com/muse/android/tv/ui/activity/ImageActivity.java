package com.muse.android.tv.ui.activity;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivityImageBinding;
import com.muse.android.tv.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 漫画/图片播放器（#61，自 VesperTV r60 整搬）：蜘蛛 playerContent 返回 pics://图1&&图2&&…
 * 时由详情页 startPlayback 分发进来。双模式（ViewPager2 横翻 / RecyclerView 竖向条漫）、
 * SeekBar 跳页、按钮缩放（0.5–3.0）、话导航与目录；切话只回传 episode_position，
 * 详情页据此 selectEpisode 续播该话。未选话返回时详情页在 onActivityResult 里 finish
 * （VesperTV 原设计：详情页对图片集是自动播放的，#63 恢复；逻辑在详情页侧）。
 * 界面按本壳设计语言重排：黑底沉浸、顶栏（返回/章节名/模式切换）吃状态栏 inset、
 * 底栏胶囊键弹性排布；功能与源码一一对应。
 * #63：目录二级页改为与小说目录同构的自适应胶囊网格（深色皮肤，见 dialog_image_catalog）。
 * #65：双指平移改到容器层实现（{@link #mPanListener}）——#63 挂在图片上的触摸监听会吞
 * 掉手势收尾的单指 ACTION_UP、截断点按显隐的点击序列（缩放键在功能框里，呼不出即
 * 「缩放没有了」）；容器层只在第二指落下瞬间拦截接管，单指事件一律不碰。
 * #80：缩放改每页独立保存（mPageZooms）——翻页不再清缩放，返回某页恢复其存值，
 * 显示百分比与实际 scale 同源，消除「显示 100%、实际 200%」。
 */
public class ImageActivity extends BaseActivity {

    public static final int REQUEST_IMAGE = 1001;

    private ImageAdapter mAdapter;
    private ActivityImageBinding mBinding;
    private List<String> mEpisodeNames;
    private int mEpisodePosition;
    private ArrayList<String> mEpisodeIds = new ArrayList<>();
    private String mVodKey = "";
    private String mVodId = "";
    private String mVodTitle = "";
    private String mVodPic = "";
    private List<String> mImages;
    private String mReferer;
    private String mTitle;
    private String mUserAgent;
    private float mZoom = 1.0f;
    // #80：每页独立保存缩放倍数（页号→倍数）。显示百分比与图片实际 scale 只读这一份：
    // 此前全局 mZoom 在翻页时被 resetZoom 清成 1（显示 100%），但 ViewPager2 保留页的
    // ImageView 不重绑、无人重设 scale（实际仍是 200%），两者脱节。每页存值后，绑定时
    // 按存值写 scale、翻页时按存值同步显示，回来看到多少就是多少。
    private final Map<Integer, Float> mPageZooms = new HashMap<>();
    private boolean mVertical = true;
    private int mCurrentPosition = 0;
    private int mControlsBaseBottom = -1;

    // ===== 目录弹窗（#63，与小说目录同构：常量与几何口径逐一对齐 NovelReaderActivity） =====
    private static final int CATALOG_MIN_COLUMNS = 1;
    private static final int CATALOG_MAX_COLUMNS = 4;
    private static final int CATALOG_MIN_ITEM_WIDTH_DP = 120;
    private static final float CATALOG_ITEM_TEXT_SP = 14.0f;
    private static final int CATALOG_ITEM_MARGIN_DP = 4;
    private static final int CATALOG_ITEM_PAD_H_DP = 12;
    private static final int CATALOG_ITEM_PAD_V_DP = 8;

    private int mCatalogPage;
    private int mCatalogColumns = CATALOG_MIN_COLUMNS;
    private int mCatalogPageSize = 0;
    private AlertDialog mCatalogDialog;
    private GridLayout mCatalogGrid;
    private ScrollView mCatalogScroll;
    private TextView mCatalogPageInfo;
    private TextView mCatalogPrev;
    private TextView mCatalogNext;

    /**
     * 双指平移（#65，容器层实现）：挂在横翻的内层 RecyclerView 与条漫 RecyclerView 上。
     * 只在第二指落下（ACTION_POINTER_DOWN 且已放大到有可平移余量）的一刻 intercept 接管
     * 整段手势——接管后子图收到 ACTION_CANCEL，点按显隐的点击序列由框架作废，不存在
     * #63 那种监听器自己吞 UP 的问题；单指的 DOWN/MOVE/UP 一律返回 false 不拦截，
     * 点按显隐、横翻翻页、条漫滚动与按钮缩放（setZoom→applyZoom 写 scale 的路径）
     * 与 #62 逐一一致，本监听只写 translationX/Y，永不碰 scale。
     *
     * <p>事件坐标即 RecyclerView 自身坐标系：接管期间容器不滚动，两个指针中点的帧间
     * 差就是手指位移，不需要 #63 那种本地坐标 translation 补偿换算。平移目标为第一指
     * 落点下的条目图（两个容器的条目视图本身就是 ImageView）。钳制公式沿用 #63：
     * 按轴 maxT = max(0, (图尺寸 × scale − 容器尺寸) / 2)，无余量的轴锁 0。</p>
     */
    private final RecyclerView.OnItemTouchListener mPanListener = new RecyclerView.SimpleOnItemTouchListener() {

        private ImageView panTarget;
        private boolean panning;
        private float lastFocusX;
        private float lastFocusY;

        @Override
        public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    View child = rv.findChildViewUnder(e.getX(), e.getY());
                    panTarget = child instanceof ImageView ? (ImageView) child : null;
                    return false;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (!panning && e.getPointerCount() == 2 && panTarget != null
                            && (maxPanOffset(panTarget, rv, true) > 0f || maxPanOffset(panTarget, rv, false) > 0f)) {
                        panning = true;
                        lastFocusX = (e.getX(0) + e.getX(1)) / 2f;
                        lastFocusY = (e.getY(0) + e.getY(1)) / 2f;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public void onTouchEvent(RecyclerView rv, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (panning && panTarget != null && e.getPointerCount() >= 2) {
                        float fx = (e.getX(0) + e.getX(1)) / 2f;
                        float fy = (e.getY(0) + e.getY(1)) / 2f;
                        panTarget.setTranslationX(clampPan(panTarget, rv, panTarget.getTranslationX() + fx - lastFocusX, true));
                        panTarget.setTranslationY(clampPan(panTarget, rv, panTarget.getTranslationY() + fy - lastFocusY, false));
                        lastFocusX = fx;
                        lastFocusY = fy;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 手势终结必达（框架保证送达拦截方），状态不会残留到下一次手势
                    panning = false;
                    panTarget = null;
                    break;
                default:
                    break;
            }
        }
    };

    /** 双指平移是否已挂到横翻的内层 RecyclerView（#65）。 */
    private boolean mPanAttachedToPager;

    /** 翻看图片时工具栏自动隐藏的状态：跳页条拖动中 / 横翻手指滑页中 / 条漫已累计滚动距离。 */
    private boolean mSeeking;
    private boolean mPagerDragging;
    private int mScrollAccum;

    /** 某一轴可平移的最大偏移：max(0, (缩放后尺寸 − 容器尺寸) / 2)，容器即手势所在的 RecyclerView。 */
    private float maxPanOffset(View view, RecyclerView host, boolean x) {
        float size = x ? view.getWidth() : view.getHeight();
        float scale = x ? view.getScaleX() : view.getScaleY();
        float viewport = x ? host.getWidth() : host.getHeight();
        return Math.max(0f, (size * scale - viewport) / 2f);
    }

    private float clampPan(View view, RecyclerView host, float value, boolean x) {
        float max = maxPanOffset(view, host, x);
        if (max <= 0f) return 0f;
        return Math.max(-max, Math.min(max, value));
    }

    /** 把平移监听挂到横翻 ViewPager2 的内层 RecyclerView（子视图就绪后调用，重复调用无副作用）。 */
    private void attachPagerPan() {
        if (mPanAttachedToPager) return;
        try {
            View child = mBinding.pager.getChildAt(0);
            if (child instanceof RecyclerView) {
                ((RecyclerView) child).addOnItemTouchListener(mPanListener);
                mPanAttachedToPager = true;
            }
        } catch (Exception ignored) {
        }
    }

    private final SeekBar.OnSeekBarChangeListener mSeekListener = new SeekBar.OnSeekBarChangeListener() {

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // 跳页条就在工具栏里：拖动期间两条翻看路径都不许藏栏
            mSeeking = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mSeeking = false;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser || progress == mCurrentPosition) return;
            if (mVertical) mBinding.list.scrollToPosition(progress);
            else mBinding.pager.setCurrentItem(progress, false);
        }
    };

    public static void start(Activity activity, String title, String picsUrl, Map<String, String> header) {
        startForResult(activity, title, picsUrl, header, null, null, 0);
    }

    public static void startForResult(Activity activity, String title, String picsUrl, Map<String, String> header, ArrayList<String> episodeNames, ArrayList<String> episodeIds, int episodePosition) {
        startForResult(activity, title, picsUrl, header, episodeNames, episodeIds, episodePosition, null, null, null, null);
    }

    /** 带作品上下文的入口（详情页分发专用）：切到视频话时可直接强播该话（序号定位），不必回详情页绕行。 */
    public static void startForResult(Activity activity, String title, String picsUrl, Map<String, String> header, ArrayList<String> episodeNames, ArrayList<String> episodeIds, int episodePosition, String vodKey, String vodId, String vodName, String vodPic) {
        Intent intent = new Intent(activity, ImageActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("picsUrl", picsUrl);
        intent.putExtra("episodeNames", episodeNames);
        if (episodeIds != null) intent.putExtra("episodeIds", episodeIds);
        intent.putExtra("episodePosition", episodePosition);
        if (vodKey != null) intent.putExtra("vodKey", vodKey);
        if (vodId != null) intent.putExtra("vodId", vodId);
        if (vodName != null) intent.putExtra("vodName", vodName);
        if (vodPic != null) intent.putExtra("vodPic", vodPic);
        if (header != null) {
            intent.putExtra("userAgent", header.get("User-Agent"));
            intent.putExtra("referer", header.get("Referer"));
        }
        activity.startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityImageBinding.inflate(getLayoutInflater());
        return mBinding;
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> applyBarInsets(insets));
        String picsUrl = getIntent().getStringExtra("picsUrl");
        mTitle = getIntent().getStringExtra("title");
        mUserAgent = getIntent().getStringExtra("userAgent");
        mReferer = getIntent().getStringExtra("referer");
        mEpisodeNames = getIntent().getStringArrayListExtra("episodeNames");
        mEpisodeIds = getIntent().getStringArrayListExtra("episodeIds");
        if (mEpisodeIds == null) mEpisodeIds = new ArrayList<>();
        mEpisodePosition = getIntent().getIntExtra("episodePosition", 0);
        mVodKey = getIntent().getStringExtra("vodKey");
        if (mVodKey == null) mVodKey = "";
        mVodId = getIntent().getStringExtra("vodId");
        if (mVodId == null) mVodId = "";
        mVodTitle = getIntent().getStringExtra("vodName");
        if (mVodTitle == null) mVodTitle = "";
        mVodPic = getIntent().getStringExtra("vodPic");
        if (mVodPic == null) mVodPic = "";
        if (mUserAgent == null) mUserAgent = "";
        if (mReferer == null) mReferer = "";
        if (mTitle == null) mTitle = "";
        if (mEpisodeNames == null) mEpisodeNames = new ArrayList<>();
        updateTitle();
        mImages = new ArrayList<>();
        if (picsUrl != null && picsUrl.startsWith("pics://")) {
            mImages.addAll(Arrays.asList(picsUrl.substring(7).split("&&")));
        }
        mAdapter = new ImageAdapter(mImages, this::loadImage);
        mBinding.pager.setAdapter(mAdapter);
        mBinding.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // 手指滑页真换页才算翻看（跳页条/程序跳页无 DRAGGING 态）；栏显着先自动藏
                if (mPagerDragging && !mSeeking && position != mCurrentPosition) hideControls();
                updatePageInfo(position);
                resetZoom();
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) mPagerDragging = true;
                else if (state == ViewPager2.SCROLL_STATE_IDLE) mPagerDragging = false;
            }
        });
        mBinding.list.setLayoutManager(new LinearLayoutManager(this));
        mBinding.list.setAdapter(mAdapter);
        // #65：双指平移挂容器层（条漫直接挂；横翻挂 ViewPager2 的内层 RecyclerView，就绪后补挂）
        mBinding.list.addOnItemTouchListener(mPanListener);
        attachPagerPan();
        mBinding.pager.post(this::attachPagerPan);
        mBinding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                // 条漫滚动累计过藏框阈值且栏显着 → 自动藏栏（拖跳页条时不藏）
                if (mVertical && !mSeeking && dy != 0 && mBinding.controls.getVisibility() == View.VISIBLE) {
                    mScrollAccum += Math.abs(dy);
                    if (mScrollAccum > hideThresholdPx()) hideControls();
                }
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (!mVertical || manager == null) return;
                int first = manager.findFirstVisibleItemPosition();
                if (first >= 0 && first != mCurrentPosition) updatePageInfo(first);
            }
        });
        mBinding.pageSeek.setMax(Math.max(0, mImages.size() - 1));
        mBinding.pageSeek.setOnSeekBarChangeListener(mSeekListener);
        updatePageInfo(0);
        mBinding.back.setOnClickListener(view -> finish());
        mBinding.zoomIn.setOnClickListener(view -> setZoom(mZoom + 0.25f));
        mBinding.zoomOut.setOnClickListener(view -> setZoom(mZoom - 0.25f));
        mBinding.zoomReset.setOnClickListener(view -> setZoom(1.0f));
        mBinding.modeToggle.setOnClickListener(view -> toggleMode());
        mBinding.prevEpisode.setOnClickListener(view -> gotoEpisode(mEpisodePosition - 1));
        mBinding.nextEpisode.setOnClickListener(view -> gotoEpisode(mEpisodePosition + 1));
        mBinding.episodeList.setOnClickListener(view -> showEpisodeDialog());
        updateNavButtons();
        // 默认条漫（纵向）：initView 末尾按 toggleMode 同一组设置切到竖向（Liu 2026-11-07 定）
        mVertical = true;
        mAdapter.setVertical(true);
        mBinding.pager.setVisibility(View.GONE);
        mBinding.list.setVisibility(View.VISIBLE);
        mBinding.modeToggle.setText("条漫");
        mBinding.modeToggle.setSelected(true);
    }

    /**
     * 顶栏吃状态栏 inset、底栏吃导航栏 inset（与阅读器 #59/#60 同一 WindowInsets 机制）：
     * 顶栏整行落在状态栏下方；底栏在手势导航下不被小横条压住。左右 padding 保持布局原值，
     * inset 不消费、原样返回。
     */
    private WindowInsetsCompat applyBarInsets(WindowInsetsCompat insets) {
        int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
        int extra = (int) (1 * getResources().getDisplayMetrics().density);
        mBinding.topBar.setPadding(mBinding.topBar.getPaddingLeft(), top + extra,
                mBinding.topBar.getPaddingRight(), mBinding.topBar.getPaddingBottom());
        if (mControlsBaseBottom < 0) mControlsBaseBottom = mBinding.controls.getPaddingBottom();
        mBinding.controls.setPadding(mBinding.controls.getPaddingLeft(), mBinding.controls.getPaddingTop(),
                mBinding.controls.getPaddingRight(), mControlsBaseBottom + bottom);
        return insets;
    }

    private void updateTitle() {
        // 顶栏主显章节名（本壳规格）；无章节列表时退回书名
        String text = mTitle;
        if (!mEpisodeNames.isEmpty() && mEpisodePosition < mEpisodeNames.size()) text = mEpisodeNames.get(mEpisodePosition);
        mBinding.title.setText(text == null ? "" : text);
    }

    private void updateNavButtons() {
        boolean empty = mEpisodeNames.isEmpty();
        mBinding.prevEpisode.setVisibility(empty || mEpisodePosition <= 0 ? View.GONE : View.VISIBLE);
        mBinding.nextEpisode.setVisibility(empty || mEpisodePosition >= mEpisodeNames.size() - 1 ? View.GONE : View.VISIBLE);
        mBinding.episodeList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /**
     * 目录二级页（#63）：照小说章节页处理——自适应胶囊网格 + 分页头，深色皮肤。
     * 结构/列数算法/分页/文案规则与 NovelReaderActivity 的目录逐项同构（见那边注释），
     * 差异只有皮肤（深色底 + 白字 + bg_image_pill 胶囊，选中态暖金）与标题/取消做进布局内：
     * 系统弹窗的标题栏与按钮栏是浅色主题，压在深色面板上会露白边，故不用 builder 的
     * title/按钮，改由布局自带「目录」题字与「取消」胶囊（窗口底另设透明，面板即 dialog_image_catalog 的根底色）。
     */
    private void showEpisodeDialog() {
        if (mEpisodeNames.isEmpty()) return;
        int pageCount = catalogPageCount();
        mCatalogPage = Math.max(0, Math.min(mEpisodePosition / catalogPageSize(), pageCount - 1));
        View view = getLayoutInflater().inflate(R.layout.dialog_image_catalog, null);
        mCatalogGrid = view.findViewById(R.id.catalogGrid);
        mCatalogGrid.setColumnCount(mCatalogColumns);
        mCatalogScroll = view.findViewById(R.id.catalogScroll);
        mCatalogPageInfo = view.findViewById(R.id.catalogPageInfo);
        mCatalogPrev = view.findViewById(R.id.catalogPrev);
        mCatalogNext = view.findViewById(R.id.catalogNext);
        View cancel = view.findViewById(R.id.catalogCancel);
        // 弹窗内容高度取屏幕 55%（同小说目录），超出部分在网格内滚动
        ViewGroup.LayoutParams params = mCatalogScroll.getLayoutParams();
        params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        mCatalogScroll.setLayoutParams(params);
        mCatalogPrev.setOnClickListener(v -> switchCatalogPage(-1));
        mCatalogNext.setOnClickListener(v -> switchCatalogPage(1));
        cancel.setOnClickListener(v -> closeCatalog());
        // 面板尺寸变化后重算列数与每页话数，保证铺满、无半行裁切（同小说目录）
        mCatalogScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyCatalogMetrics();
        });
        mCatalogDialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();
        mCatalogDialog.setOnDismissListener(d -> mCatalogDialog = null);
        // 打开前先按屏幕尺寸估算一次，保证定位到当前话所在页
        applyCatalogMetrics();
        mCatalogPage = Math.max(0, Math.min(mEpisodePosition / catalogPageSize(), catalogPageCount() - 1));
        fillCatalogPage();
        mCatalogDialog.show();
        if (mCatalogDialog.getWindow() != null) {
            // 窗口底透明，露出布局自身的深色面板；宽度 = 屏宽 × 0.95（同小说目录）
            mCatalogDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            mCatalogDialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.95f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        // 布局完成后按实测宽高再校一次，并把当前页重新对齐（同小说目录）
        mCatalogScroll.post(() -> {
            applyCatalogMetrics();
            mCatalogPage = Math.max(0, Math.min(mEpisodePosition / catalogPageSize(), catalogPageCount() - 1));
            fillCatalogPage();
        });
    }

    /** 目录每页话数：至少 1，未测量前用估算值兜底（同小说目录）。 */
    private int catalogPageSize() {
        if (mCatalogPageSize <= 0) mCatalogPageSize = estimatePageSize();
        return Math.max(1, mCatalogPageSize);
    }

    /** 按面板可用宽高重算列数（clamp 1..4）与每页话数；各列等权平分，整组天然左右对称（同小说目录）。 */
    private void applyCatalogMetrics() {
        if (mCatalogGrid == null || mCatalogScroll == null) return;
        float density = getResources().getDisplayMetrics().density;
        int margin = (int) (CATALOG_ITEM_MARGIN_DP * density);
        int width = mCatalogGrid.getWidth() > 0 ? mCatalogGrid.getWidth()
                : (mCatalogScroll.getWidth() > 0 ? mCatalogScroll.getWidth()
                : (int) (getResources().getDisplayMetrics().widthPixels * 0.95f));
        int height = mCatalogScroll.getHeight() > 0 ? mCatalogScroll.getHeight()
                : (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        int columns = computeColumns(width, margin);
        int pageSize = computePageSize(height, columns, margin);
        if (columns == mCatalogColumns && pageSize == mCatalogPageSize) return;
        mCatalogColumns = columns;
        mCatalogPageSize = pageSize;
        mCatalogGrid.setColumnCount(columns);
        fillCatalogPage();
    }

    private int computeColumns(int width, int margin) {
        int minItem = (int) (CATALOG_MIN_ITEM_WIDTH_DP * getResources().getDisplayMetrics().density);
        int columns = width / Math.max(1, minItem + margin * 2);
        return Math.max(CATALOG_MIN_COLUMNS, Math.min(CATALOG_MAX_COLUMNS, columns));
    }

    private int computePageSize(int height, int columns, int margin) {
        int itemHeight = measureCatalogItemHeight(margin);
        int rows = Math.max(1, height / Math.max(1, itemHeight));
        return rows * columns;
    }

    /** 实测单条目录项高度（含上下外边距），探针 padding 与真实胶囊条目一致（同小说目录）。 */
    private int measureCatalogItemHeight(int margin) {
        float density = getResources().getDisplayMetrics().density;
        TextView probe = new TextView(this);
        probe.setTextSize(CATALOG_ITEM_TEXT_SP);
        probe.setSingleLine(true);
        probe.setPadding((int) (CATALOG_ITEM_PAD_H_DP * density), (int) (CATALOG_ITEM_PAD_V_DP * density),
                (int) (CATALOG_ITEM_PAD_H_DP * density), (int) (CATALOG_ITEM_PAD_V_DP * density));
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        probe.measure(spec, spec);
        return probe.getMeasuredHeight() + margin * 2;
    }

    private int estimatePageSize() {
        float density = getResources().getDisplayMetrics().density;
        int margin = (int) (CATALOG_ITEM_MARGIN_DP * density);
        int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.95f);
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        return computePageSize(height, computeColumns(width, margin), margin);
    }

    private int catalogPageCount() {
        int size = catalogPageSize();
        return Math.max(1, (mEpisodeNames.size() + size - 1) / size);
    }

    /** 目录条目文案：与小说 catalogLabel() 完全一致——「序号. 名称」，纯数字名只显示数字，空名退回纯序号。 */
    private String catalogLabel(int i) {
        String name = mEpisodeNames.get(i).trim();
        if (name.isEmpty()) return String.valueOf(i + 1);
        if (name.matches("\\d+")) return name;
        return (i + 1) + ". " + name;
    }

    /** 按当前页重填网格，并刷新分页信息与翻页钮可用态（同小说目录；胶囊换图片页的深色款）。 */
    private void fillCatalogPage() {
        if (mCatalogGrid == null) return;
        int size = catalogPageSize();
        int pageCount = catalogPageCount();
        mCatalogPage = Math.max(0, Math.min(mCatalogPage, pageCount - 1));
        int start = mCatalogPage * size;
        int end = Math.min(mEpisodeNames.size(), start + size);
        mCatalogGrid.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int margin = (int) (CATALOG_ITEM_MARGIN_DP * density);
        int padH = (int) (CATALOG_ITEM_PAD_H_DP * density);
        int padV = (int) (CATALOG_ITEM_PAD_V_DP * density);
        for (int i = start; i < end; i++) {
            final int position = i;
            TextView item = new TextView(this);
            item.setText(catalogLabel(i));
            item.setTextSize(CATALOG_ITEM_TEXT_SP);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setGravity(Gravity.CENTER);
            item.setPadding(padH, padV, padH, padV);
            item.setFocusable(true);
            item.setClickable(true);
            item.setTextColor(Color.WHITE);
            // 胶囊底与图片页同一语言；当前话 selected 亮成暖金高亮
            item.setBackgroundResource(R.drawable.bg_image_pill);
            item.setSelected(i == mEpisodePosition);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);
            params.setMargins(margin, margin, margin, margin);
            item.setLayoutParams(params);
            item.setOnClickListener(v -> {
                closeCatalog();
                gotoEpisode(position);
            });
            mCatalogGrid.addView(item);
        }
        if (mCatalogPageInfo != null) {
            mCatalogPageInfo.setText("第 " + (start + 1) + "-" + end + " 话 · 共 " + mEpisodeNames.size() + " 话 · "
                    + (mCatalogPage + 1) + "/" + pageCount + " 页");
        }
        if (mCatalogPrev != null) mCatalogPrev.setAlpha(mCatalogPage > 0 ? 1f : 0.4f);
        if (mCatalogNext != null) mCatalogNext.setAlpha(mCatalogPage < pageCount - 1 ? 1f : 0.4f);
    }

    private void switchCatalogPage(int delta) {
        int target = mCatalogPage + delta;
        if (target < 0 || target >= catalogPageCount()) return;
        mCatalogPage = target;
        fillCatalogPage();
        if (mCatalogScroll != null) mCatalogScroll.scrollTo(0, 0);
    }

    private void closeCatalog() {
        if (mCatalogDialog != null && mCatalogDialog.isShowing()) mCatalogDialog.dismiss();
        mCatalogDialog = null;
    }

    private void gotoEpisode(int position) {
        if (position < 0 || position >= mEpisodeNames.size() || position == mEpisodePosition) return;
        // 目标话是视频直链且带作品上下文（详情页入口）：直接强播该话（序号定位），一跳到位；
        // 否则照旧回传序号给调用页（阅读器入口、纯漫画源）续行分发
        String target = position < mEpisodeIds.size() ? mEpisodeIds.get(position) : "";
        if (!mVodKey.isEmpty() && !mVodId.isEmpty() && isDirectMedia(target)) {
            VideoActivity.startForcePlay(this, mVodKey, mVodId, mVodTitle, mVodPic, mEpisodeNames.get(position), position);
            finish();
            return;
        }
        Intent intent = new Intent();
        intent.putExtra("episode_position", position);
        setResult(RESULT_OK, intent);
        finish();
    }

    /** 目标标识是一整条无空白的 http(s) 直链即视频话（与阅读器 isMediaUrl 同口径）。 */
    private boolean isDirectMedia(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) return false;
        for (int i = 0; i < v.length(); i++) if (Character.isWhitespace(v.charAt(i))) return false;
        return true;
    }

    private void loadImage(ImageView view, String url, int position) {
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (!mUserAgent.isEmpty()) builder.addHeader("User-Agent", mUserAgent);
        if (!mReferer.isEmpty()) builder.addHeader("Referer", mReferer);
        Glide.with(this).load(new GlideUrl(url, builder.build())).into(view);
        view.setOnClickListener(v -> toggleControls());
        applyZoom(view, pageZoom(position));
    }

    /** #80：某页存着的缩放倍数（没缩放过的页为 1.0）。 */
    private float pageZoom(int position) {
        Float zoom = mPageZooms.get(position);
        return zoom == null ? 1.0f : zoom;
    }

    private void setZoom(float zoom) {
        mZoom = Math.max(0.5f, Math.min(3.0f, zoom));
        mPageZooms.put(mCurrentPosition, mZoom);
        mBinding.zoomText.setText(Math.round(mZoom * 100.0f) + "%");
        ImageView current = getCurrentImageView();
        if (current != null) applyZoom(current, mZoom);
        // #63：缩放回到 100% 时平移一并归零（钳制公式下 100% 本无可平移余量，清掉残值防错位）
        if (mZoom == 1.0f) resetPan();
    }

    private void resetZoom() {
        // #80：翻页/切模式不再清缩放——当前页缩放已由 updatePageInfo 按 mPageZooms 同步
        // 到 mZoom 与百分比显示，保留页的实际 scale 也仍是其存值，两边一致。
        // #63：翻页/切模式时平移归零仍走这里统一生效。
        resetPan();
    }

    /** 双指平移归零（#63）：清掉两个容器内所有已挂载图片视图的 translation。 */
    private void resetPan() {
        resetPanIn(mBinding.list);
        try {
            View child = mBinding.pager.getChildAt(0);
            if (child instanceof RecyclerView) resetPanIn((RecyclerView) child);
        } catch (Exception ignored) {
        }
    }

    private void resetPanIn(RecyclerView recyclerView) {
        if (recyclerView == null) return;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            child.setTranslationX(0f);
            child.setTranslationY(0f);
        }
    }

    private void applyZoom(ImageView view, float zoom) {
        view.setScaleX(zoom);
        view.setScaleY(zoom);
    }

    @Nullable
    private ImageView getCurrentImageView() {
        if (mVertical) return null;
        try {
            RecyclerView child = (RecyclerView) mBinding.pager.getChildAt(0);
            if (child != null) {
                RecyclerView.ViewHolder holder = child.findViewHolderForAdapterPosition(mBinding.pager.getCurrentItem());
                if (holder instanceof ImageAdapter.ViewHolder) return ((ImageAdapter.ViewHolder) holder).imageView;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void toggleMode() {
        mVertical = !mVertical;
        mAdapter.setVertical(mVertical);
        mBinding.pager.setVisibility(mVertical ? View.GONE : View.VISIBLE);
        mBinding.list.setVisibility(mVertical ? View.VISIBLE : View.GONE);
        mBinding.modeToggle.setText(mVertical ? "条漫" : "横翻");
        mBinding.modeToggle.setSelected(mVertical);
        resetZoom();
    }

    private void updatePageInfo(final int position) {
        mCurrentPosition = position;
        // #80：页号一变，缩放上下文切到该页的存值（mZoom 与百分比显示同步）
        mZoom = pageZoom(position);
        runOnUiThread(() -> {
            mBinding.zoomText.setText(Math.round(mZoom * 100.0f) + "%");
            mBinding.pageCurrent.setText(String.valueOf(position + 1));
            mBinding.pageTotal.setText(String.valueOf(mImages.size()));
            mBinding.pageSeek.setOnSeekBarChangeListener(null);
            mBinding.pageSeek.setProgress(position);
            mBinding.pageSeek.setOnSeekBarChangeListener(mSeekListener);
        });
    }

    private void toggleControls() {
        int visibility = mBinding.controls.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
        mBinding.controls.setVisibility(visibility);
        mBinding.topBar.setVisibility(visibility);
        if (visibility == View.VISIBLE) mScrollAccum = 0;
    }

    /** 藏起工具栏（顶栏＋底栏）；已藏则不动。横翻滑页、条漫滚动自动隐藏的共用入口。 */
    private void hideControls() {
        if (mBinding.controls.getVisibility() != View.VISIBLE) return;
        mBinding.controls.setVisibility(View.GONE);
        mBinding.topBar.setVisibility(View.GONE);
        mScrollAccum = 0;
    }

    /** 藏框滚动阈值：4dp（与小说阅读器拖动藏框同口径），按设备密度换算成像素。 */
    private int hideThresholdPx() {
        return Math.round(4 * getResources().getDisplayMetrics().density);
    }
}
