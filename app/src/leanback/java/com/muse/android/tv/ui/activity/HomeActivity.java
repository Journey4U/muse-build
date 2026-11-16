package com.muse.android.tv.ui.activity;

import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.Updater;
import com.muse.android.tv.api.config.LiveConfig;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.config.WallConfig;
import com.muse.android.tv.bean.Button;
import com.muse.android.tv.bean.Cache;
import com.muse.android.tv.bean.Class;
import com.muse.android.tv.bean.Config;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.ActivityHomeBinding;
import com.muse.android.tv.db.BackupManager;
import com.muse.android.tv.event.CastEvent;
import com.muse.android.tv.event.ConfigEvent;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.event.ServerEvent;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.model.SiteViewModel;
import com.muse.android.tv.player.extractor.Source;
import com.muse.android.tv.server.Server;
import com.muse.android.tv.service.DLNARendererService;
import com.muse.android.tv.service.PlaybackService;
import com.muse.android.tv.ui.adapter.BaseDiffCallback;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.custom.CustomTitleView;
import com.muse.android.tv.ui.dialog.SiteDialog;
import com.muse.android.tv.ui.fragment.FolderFragment;
import com.muse.android.tv.ui.fragment.HomePageFragment;
import com.muse.android.tv.ui.presenter.ClassPresenter;
import com.muse.android.tv.utils.Clock;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.ImgUtil;
import com.muse.android.tv.utils.KeyUtil;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;
import com.muse.android.tv.utils.UrlUtil;
import com.muse.android.tv.utils.Util;
import com.github.catvod.utils.Prefers;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视版主页：原版 OK 5.1.6 大屏版 HomeActivity 结构的移植。
 * 工具栏 + 分类条（HorizontalGridView @id/recycler）+ ViewPager1（CustomViewPager），每页一个独立 fragment：
 * 位置 0 = 首页页 HomePageFragment，其余位置 = 各分类的 FolderFragment/TypeFragment。
 * 遥控器路径与原版一致：条选区移动（100ms 防抖）驱动 pager；pager 切页反向同步条选区；
 * 条上 OK：首页页弹线路列表，分类页切筛选显隐；条长按（仅首页页）= 更新源配置；上键：首页页→标题获焦，分类页→重载第 1 页。
 * 与我方旧版的区别（均为拿掉原版没有的自加项，详见 DIFF-REPORT）：
 * ViewPager2→ViewPager1、删 mFilterMemory/页注册表/epoch/pager defer/remeasureStrip/updateToolbarVisibility/
 * typeStrip id（改回 recycler）/Activity 级 ProgressLayout（进度改走首页页的 ProgressLayout）。
 * 工具栏常显，只在首页页网格选区 ≥4 时隐藏（原版 N3/x case 1 + U()），分类页不再隐藏工具栏。
 * 保留的我方外壳（不在主页线内，原样不动）：启动/意图/直播/壁纸/DLNA/Updater/Server/备份/播放服务清理、
 * MENU 首页页→showDialog() 单一路（v36）、触屏点另一分类条→切页（原版只有遥控器路径，见 DIFF-REPORT）。
 */
public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, ClassPresenter.OnClickListener {

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mStripAdapter;
    private HomePagerAdapter mPagerAdapter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private boolean mHasHomeResult;
    private View mSavedFocus;
    private View mActivatedView;
    private int mHomeLoadToken;
    private Clock mClock;
    private long mExitTime;
    private int mCurrentPage;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mClock = Clock.create(mBinding.clock).format("HH:mm:ss MM/dd E");
        PermissionUtil.requestNotify(this);
        setStrip();
        setPager();
        setViewModel();
        initConfig();
        setTitle();
        setLogo();
        X();
        if (!Button.has("5")) DLNARendererService.start(this);
        Updater.create().start(this);
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }

            @Override
            public void onPageSelected(int position) {
                mCurrentPage = position;
                mBinding.recycler.setSelectedPosition(position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mActivatedView != null) mActivatedView.setActivated(false);
                if (child != null) {
                    mActivatedView = child.itemView;
                    mActivatedView.setActivated(true);
                }
                int pos = position;
                App.post(() -> {
                    int cur = mBinding.recycler.getSelectedPosition();
                    if (cur == pos) mBinding.pager.setCurrentItem(pos);
                    // 原版条 runnable（G5.a case 17）后半句：按当前条位置处理工具栏；移植版 v39 只抄了切页半句，这里补回。
                    if (cur == 0) {
                        mBinding.toolbar.setVisibility(View.VISIBLE);
                        mBinding.blank.setVisibility(View.GONE);
                    } else {
                        updateToolbar();
                    }
                }, 100);
            }
        });
    }

    private void setStrip() {
        mStripAdapter = new ArrayObjectAdapter(new ClassPresenter(this));
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mStripAdapter));
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(-2);
        mBinding.recycler.setVisibility(View.VISIBLE);
        setStripTypes(new ArrayList<>());
    }

    private void setPager() {
        mPagerAdapter = new HomePagerAdapter(getSupportFragmentManager(), getHome().getKey(), new ArrayList<>());
        mBinding.pager.setAdapter(mPagerAdapter);
        mBinding.pager.setNoScrollItem(0);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, this::onHomeResult);
    }

    private void setStripTypes(List<Class> types) {
        List<Class> items = new ArrayList<>();
        Class home = new Class();
        home.setTypeId("home");
        home.setTypeName(getString(R.string.home));
        items.add(home);
        items.addAll(types);
        mStripAdapter.setItems(items, new BaseDiffCallback<Class>());
    }

    /** 原版 R1/L：类型到达 → Cache 清后存入 + 条与页一起重建（新 adapter）+ 300ms 后填首页。 */
    private void onHomeResult(Result result) {
        Cache.clear().put(result);
        mResult = result;
        mHasHomeResult = true;
        setStripTypes(result.getTypes());
        mPagerAdapter = new HomePagerAdapter(getSupportFragmentManager(), getHome().getKey(), result.getTypes());
        mBinding.pager.setAdapter(mPagerAdapter);
        mBinding.pager.setNoScrollItem(0);
        mPagerAdapter.notifyDataSetChanged();
        App.post(() -> fillHome(result), 300);
    }

    /** 原版 Z0/E case 6：首页页已初始化才填充；焦点恢复：存过的焦点不是标题 → 条获焦（大屏分支）；标题尚不可获焦 → 500ms 后放开。 */
    private void fillHome(Result result) {
        HomePageFragment home = getHomeFragment();
        if (home == null || !home.isAdapterReady()) return;
        home.fillHome(result);
        if (mSavedFocus != mBinding.title) mBinding.recycler.requestFocus();
        if (!mBinding.title.isFocusable()) App.post(() -> mBinding.title.setFocusable(true), 500);
    }

    /** 原版 V()：标题 + Result 清空 + 首页源 key 为空直接返回 + 存当前焦点 + 200ms 后触发首页加载。空 key 时先把首页页切到内容态：否则首次无配置会卡在进度态转圈（返回键那支就是切内容态），功能行出不来、设置进不去。 */
    public void refreshHome() {
        setTitle();
        mResult = Result.empty();
        if (getHome().getKey().isEmpty()) {
            HomePageFragment home = getHomeFragment();
            if (home != null) home.showContent();
            return;
        }
        mSavedFocus = getCurrentFocus();
        int token = ++mHomeLoadToken;
        App.post(() -> {
            if (token != mHomeLoadToken) return;
            HomePageFragment home = getHomeFragment();
            if (home != null) home.showProgress();
            mViewModel.homeContent();
        }, 200);
    }

    /** 原版 W()：标题取首页源名 / 配置名 / 应用名第一个非空。 */
    private void setTitle() {
        List<String> items = new ArrayList<>();
        items.add(getHome().getName());
        items.add(getConfig().getName());
        items.add(getString(R.string.app_name));
        for (String s : items) {
            if (!TextUtils.isEmpty(s)) {
                mBinding.title.setText(s);
                return;
            }
        }
    }

    /** 原版 X()：锁标按 home_site_lock（默认 false）显隐；大屏分支标题与时钟 20sp。 */
    private void X() {
        mBinding.homeSiteLock.setVisibility(Prefers.getBoolean("home_site_lock", false) ? View.VISIBLE : View.GONE);
        mBinding.title.setTextSize(20.0f);
        mBinding.clock.setTextSize(20.0f);
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        };
    }

    private void showContent() {
        setTitle();
        setLogo();
        checkAction(getIntent());
        refreshHome();
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.getUri(intent, uri -> loadLive(UrlUtil.toLocalUrl(uri)));
        } else {
            FileChooser.getUri(intent, uri -> VideoActivity.file(this, uri));
        }
    }

    private void loadLive(String url) {
        if (isFinishing() || isDestroyed()) return;
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    /** 原版 T()：首页页（adapter 缓存，未实例化返回 null）。 */
    private HomePageFragment getHomeFragment() {
        if (mPagerAdapter == null) return null;
        return mPagerAdapter.getFragment(0) instanceof HomePageFragment ? (HomePageFragment) mPagerAdapter.getFragment(0) : null;
    }

    /** 原版 S()：当前分类包装页；首页页时返回 null。 */
    private FolderFragment getCurrentWrapper() {
        if (mPagerAdapter == null || mCurrentPage == 0) return null;
        return mPagerAdapter.getFragment(mCurrentPage) instanceof FolderFragment ? (FolderFragment) mPagerAdapter.getFragment(mCurrentPage) : null;
    }

    /** 原版 f11666R：最新一次首页结果，供功能组点播入口（VodActivity.start）使用。 */
    public Result getHomeResult() {
        return mResult;
    }

    /** 是否已收到过首页结果（供重建的首页页补填，见 HomePageFragment.initView 与 DIFF-REPORT D1）。 */
    public boolean hasHomeResult() {
        return mHasHomeResult;
    }

    private Class currentStripClass() {
        if (mCurrentPage < 0 || mCurrentPage >= mStripAdapter.size()) return null;
        Object item = mStripAdapter.get(mCurrentPage);
        return item instanceof Class ? (Class) item : null;
    }

    private boolean focusCurrentPageFirst() {
        HomePageFragment home = getHomeFragment();
        if (mCurrentPage == 0 && home != null) {
            home.focusFirst();
            return true;
        }
        FolderFragment wrapper = getCurrentWrapper();
        if (wrapper != null) {
            wrapper.focusFirst();
            return true;
        }
        return false;
    }

    @Override
    public void onItemClick(Class item) {
        if (item.isHome()) {
            if (mCurrentPage == 0) {
                showDialog();
            } else {
                mBinding.pager.setCurrentItem(0);
                setTitle();
            }
            return;
        }
        int pos = mStripAdapter.indexOf(item);
        if (pos == mCurrentPage && mCurrentPage != 0) {
            toggleFilter(item);
            return;
        }
        if (pos >= 0) mBinding.pager.setCurrentItem(pos);
    }

    /** 原版 n()：上键——首页页标题获焦，分类页当前页重载第 1 页。 */
    @Override
    public void onItemUpKey(Class item) {
        int pos = mStripAdapter.indexOf(item);
        if (pos != mCurrentPage) return;
        if (mCurrentPage == 0) {
            mBinding.title.requestFocus();
            return;
        }
        FolderFragment wrapper = getCurrentWrapper();
        if (wrapper != null) wrapper.onRefresh();
    }

    /** 原版 q()：条长按仅首页页有效 = 更新源配置。 */
    @Override
    public void onItemLongClick(Class item) {
        if (mCurrentPage != 0) return;
        reloadConfig();
    }

    /** 原版 Z(Class)：筛选为空直接返回；否则翻 flag、内页开关、通知当前条。 */
    private void toggleFilter(Class item) {
        if (item.getFilters().isEmpty()) return;
        item.setFilter(!item.getFilter());
        FolderFragment wrapper = getCurrentWrapper();
        if (wrapper != null) wrapper.toggleFilter(item.getFilter());
        notifyTypeItem(item);
    }

    private void notifyTypeItem(Class item) {
        int index = mStripAdapter.indexOf(item);
        if (index >= 0) mStripAdapter.notifyArrayItemRangeChanged(index, 1);
    }

    public void notifyTypeItem(String typeId) {
        for (int i = 0; i < mStripAdapter.size(); i++) {
            Object item = mStripAdapter.get(i);
            if (item instanceof Class && ((Class) item).getTypeId().equals(typeId)) {
                mStripAdapter.notifyArrayItemRangeChanged(i, 1);
                return;
            }
        }
    }

    /** 原版 B3.b(21)：打开文件夹时，若当前分类的筛选是开着的，把它关掉。 */
    public void onFolderOpened() {
        Class current = currentStripClass();
        if (current != null && current.getFilter()) toggleFilter(current);
    }

    /** 原版 N3/x case 1：首页页网格选区 <4 → 工具栏显、blank 藏；≥4 → 调 updateToolbar()（原版 U()）。 */
    public void onHomeGridSelected(int position) {
        if (mCurrentPage != 0) return;
        if (position < 4) {
            mBinding.toolbar.setVisibility(View.VISIBLE);
            mBinding.blank.setVisibility(View.GONE);
        } else {
            updateToolbar();
        }
    }

    /** 原版 U()：工具栏藏、blank 跟条显隐。条 runnable（非首页页）与首页网格 ≥4 分支共用，不得再内联。 */
    private void updateToolbar() {
        mBinding.toolbar.setVisibility(View.GONE);
        mBinding.blank.setVisibility(mBinding.recycler.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private void setHomeFunc() {
        HomePageFragment home = getHomeFragment();
        if (home != null) home.setFunc();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setHomeFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                refreshHome();
                break;
            case CATEGORY:
                FolderFragment wrapper = getCurrentWrapper();
                if (wrapper != null) wrapper.onRefresh();
                break;
            case HISTORY:
                HomePageFragment home = getHomeFragment();
                if (home != null) home.b0();
                break;
            case SIZE:
                HomePageFragment homePage = getHomeFragment();
                if (homePage != null) homePage.b0();
                refreshHome();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history());
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    public void showDialog() {
        SiteDialog.create().keyword().show(this);
    }

    /** 标题上键刷新 = 首页刷新（原版标题 UP 语义）。 */
    public void onRefresh() {
        refreshHome();
    }

    /** 原版 l(Site)：切源 + 分类重载事件 + 首页刷新。 */
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
        RefreshEvent.category();
        refreshHome();
    }

    /** 原版 C0640o case 1 的等价链：重载源配置再刷新；进度走首页页的 ProgressLayout。 */
    public void reloadConfig() {
        VodConfig.get().clear().config(getConfig()).load(new Callback() {
            @Override
            public void start() {
                HomePageFragment home = getHomeFragment();
                if (home != null) home.showProgress();
            }

            @Override
            public void success() {
                Notify.show(getString(R.string.config_refreshed));
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) {
            if (mCurrentPage == 0) showDialog();
            else {
                Class current = currentStripClass();
                if (current != null) toggleFilter(current);
            }
        }
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && getCurrentFocus() == mBinding.title) {
            return focusCurrentPageFirst();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
        X();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
    }

    /** 原版 s() 的等价物（入口按我方 BaseActivity 的 onBackInvoked）。 */
    @Override
    protected void onBackInvoked() {
        if (mCurrentPage != 0) {
            Class current = currentStripClass();
            boolean stripVisible = mBinding.recycler.getVisibility() == View.VISIBLE;
            if (current != null && current.getFilter() && stripVisible) {
                toggleFilter(current);
                return;
            }
            FolderFragment wrapper = getCurrentWrapper();
            if (wrapper != null && wrapper.canBack()) {
                wrapper.goBack();
                return;
            }
            if (!stripVisible) {
                mBinding.recycler.setVisibility(View.VISIBLE);
                mBinding.recycler.requestFocus();
                if (wrapper != null) wrapper.scrollChildToTop();
                return;
            }
            if (!mBinding.recycler.hasFocus()) {
                mBinding.recycler.requestFocus();
                return;
            }
        }
        if (mBinding.recycler.getVisibility() == View.VISIBLE && mBinding.recycler.getSelectedPosition() != 0) {
            mBinding.recycler.scrollToPosition(0);
            return;
        }
        HomePageFragment home = getHomeFragment();
        if (home != null && home.isProgress()) {
            home.showContent();
            return;
        }
        if (home != null && home.isHistoryDelete()) {
            home.setHistoryDelete(false);
            return;
        }
        if (home != null && home.getSelectedPosition() != 0) {
            home.scrollToTop();
            return;
        }
        if (System.currentTimeMillis() - mExitTime > 2000) {
            mExitTime = System.currentTimeMillis();
            Notify.show(R.string.exit_confirm);
        } else if (PlaybackService.isRunning()) {
            Util.moveToBackground(this);
        } else {
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        DLNARendererService.stop(this);
        LiveConfig.get().clear();
        VodConfig.get().clear();
        BackupManager.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}


