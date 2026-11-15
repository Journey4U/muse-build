package com.muse.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Class;
import com.muse.android.tv.bean.Config;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.bean.Value;
import com.muse.android.tv.databinding.FragmentVodBinding;
import com.muse.android.tv.event.CastEvent;
import com.muse.android.tv.event.ConfigEvent;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.event.StateEvent;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.impl.ConfigListener;
import com.muse.android.tv.impl.FilterListener;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.model.SiteViewModel;
import com.muse.android.tv.ui.activity.HistoryActivity;
import com.muse.android.tv.ui.activity.KeepActivity;
import com.muse.android.tv.ui.activity.SearchActivity;
import com.muse.android.tv.ui.adapter.TypeAdapter;
import com.muse.android.tv.ui.base.BaseFragment;
import com.muse.android.tv.ui.dialog.FilterDialog;
import com.muse.android.tv.ui.dialog.HistoryDialog;
import com.muse.android.tv.ui.dialog.LinkDialog;
import com.muse.android.tv.ui.dialog.ReceiveDialog;
import com.muse.android.tv.ui.dialog.SearchScopeDialog;
import com.muse.android.tv.ui.dialog.SiteDialog;
import com.muse.android.tv.ui.dialog.TypeDialog;
import com.muse.android.tv.utils.ImgUtil;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigListener, SiteListener, FilterListener, TypeAdapter.OnClickListener, SearchScopeDialog.Listener, TypeDialog.Listener {

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private TypeAdapter mAdapter;
    private Result mResult;
    /** #102：视图切换前按分类位置留底的子页栈路径，换适配器后传给新建的 FolderFragment 按层重建。 */
    private final Map<Integer, ArrayList<FolderFragment.FolderLayer>> mFolderPaths = new HashMap<>();

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.title.setSelected(true);
        setRecyclerView();
        setViewModel();
        showProgress();
        setTitle();
        setLogo();
        syncViewSwitchIcon();
    }

    @Override
    protected void initEvent() {
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.logo.setOnClickListener(this::onLogo);
        mBinding.logo.setOnLongClickListener(this::onLogoRefresh);
        mBinding.link.setOnClickListener(this::onLink);
        mBinding.title.setOnClickListener(this::onSite);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.filter.setOnLongClickListener(this::onLink);
        mBinding.typeAll.setOnClickListener(v -> onTypeAll());
        mBinding.viewSwitch.setOnClickListener(v -> onViewSwitch());
        mBinding.toolbar.setOnMenuItemClickListener(this::onMenuItemClick);
        mBinding.toolbar.post(this::bindSearchLongClick);
        mBinding.appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            float factor = Math.abs(verticalOffset * 1f / appBarLayout.getTotalScrollRange());
            int padding = (int) (ResUtil.dp2px(12) * factor);
            if (mBinding.type.getPaddingTop() == padding) return;
            mBinding.type.setPadding(mBinding.type.getPaddingStart(), padding, mBinding.type.getPaddingEnd(), mBinding.type.getPaddingBottom());
        });
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setSelected(position);
                setFabVisible(position);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new TypeAdapter(this));
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        mAdapter.addAll(mResult = result);
        mBinding.pager.getAdapter().notifyDataSetChanged();
        setFabVisible(0);
        hideProgress();
        showContent();
    }

    private void setFabVisible(int position) {
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.VISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.show();
        } else if (position == 0 || mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.show();
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void onTop(View view) {
        getFragment().scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
        else if (mBinding.link.getVisibility() == View.INVISIBLE) mBinding.link.show();
    }

    private boolean onLink(View view) {
        LinkDialog.show(this);
        return true;
    }

    private void onLogo(View view) {
        HistoryDialog.create().vod().readOnly().show(this);
    }

    private boolean onLogoRefresh(View view) {
        VodConfig.load(getConfig(), new Callback() {
            @Override
            public void success() {
                Notify.show("接口已刷新");
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
        return true;
    }

    private void onSite(View view) {
        SiteDialog.create().change().show(this);
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    /** 打开分类列表面板（底部弹层，可搜索、可切换列数），选中后经 onTypeClick 直达。 */
    private void onTypeAll() {
        if (mAdapter.getItemCount() == 0) return;
        TypeDialog.create().types(mAdapter.getItems()).position(mBinding.pager.getCurrentItem()).show(this);
    }

    /** 分类列表面板的选中回调：与分类签点选走同一路径。 */
    @Override
    public void onTypeClick(int position, Class item) {
        onItemClick(position, item);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.keep) KeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) SearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        return true;
    }

    /**
     * 给工具栏放大镜菜单项挂长按监听：轻按走全局搜索（原有行为），长按弹出范围选择。
     */
    private void bindSearchLongClick() {
        View view = mBinding.toolbar.findViewById(R.id.search);
        if (view == null || view.getTag(R.id.search) != null) return;
        view.setTag(R.id.search, Boolean.TRUE);
        view.setOnLongClickListener(v -> {
            SearchScopeDialog.create().show(this);
            return true;
        });
    }

    /**
     * 搜索范围选择结果：按「当前源 / 分类 / 源写法」跳转到搜索页。
     */
    @Override
    public void onScope(String scope, String value) {
        SearchActivity.start(requireActivity(), "", scope, value);
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        mBinding.type.setVisibility(View.VISIBLE);
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        showProgress();
        setFabVisible(0);
        mAdapter.clear();
        mFolderPaths.clear();
        mViewModel.homeContent();
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    /**
     * #84：视图（尺寸）切换专用刷新——照 VesperTV 的 sizeContent 移植：分类数据与列数无关，
     * 不清分类栏、不重拉首页，只换 pager 适配器套用新列数；换完在布局前同步跳回原分类，
     * pager 第一帧就落在正确分类，消除 #81–#83 全量刷新路线的标签空白与跳动。
     */
    private void sizeContent() {
        try {
            int position = mBinding.pager.getCurrentItem();
            if (position < 0 || position >= mAdapter.getItemCount()) position = 0;
            captureFolderPaths();
            mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
            if (position > 0) {
                mBinding.pager.setCurrentItem(position, false);
                mAdapter.setSelected(position);
                mBinding.type.scrollToPosition(position);
            }
            setFabVisible(position);
            DebugLogStore.addPlain(LogCategory.RUN, "view-restore", "sizeContent 目标分类=" + position + " 分类总数=" + mAdapter.getItemCount() + " 保留子页分类数=" + mFolderPaths.size());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * #102：换适配器前，把每个已创建分类的 FolderFragment 子页栈留底（按分类位置）。
     * 适配器整页重建会丢弃子回退栈——留底后由新适配器的 getItem 经参数带回，逐层重建。
     * 仅视图切换走这条路；homeContent（全量刷新/换源）不留底，回到各分类根页（原有行为）。
     */
    private void captureFolderPaths() {
        mFolderPaths.clear();
        if (!(mBinding.pager.getAdapter() instanceof PageAdapter)) return;
        PageAdapter adapter = (PageAdapter) mBinding.pager.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            FolderFragment folder = adapter.getCreated(i);
            if (folder == null) continue;
            ArrayList<FolderFragment.FolderLayer> path = folder.capturePath();
            if (path != null && path.size() > 1) mFolderPaths.put(i, path);
        }
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
    }

    /** #81：视图切换——与设置页「图片尺寸」同值（PlayerSetting.size）同事件（RefreshEvent.size），点按循环四档。 */
    private void onViewSwitch() {
        PlayerSetting.putSize((PlayerSetting.getSize() + 1) % 4);
        syncViewSwitchIcon();
        RefreshEvent.size();
    }

    private void syncViewSwitchIcon() {
        int icon = switch (PlayerSetting.getSize()) {
            case 0 -> R.drawable.ic_view_list;
            case 2 -> R.drawable.ic_view_medium;
            case 3 -> R.drawable.ic_view_large;
            default -> R.drawable.ic_view_small;
        };
        mBinding.viewSwitch.setImageResource(icon);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                setTitle();
                homeContent();
                break;
            case SIZE:
                syncViewSwitchIcon();
                sizeContent();
                break;
            case CATEGORY:
                getFragment().onRefresh();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        switch (event.type()) {
            case EMPTY:
                hideProgress();
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        int real = mAdapter.indexOf(item);
        if (real < 0) real = position;
        mBinding.pager.setCurrentItem(real);
        mAdapter.setSelected(real);
    }

    @Override
    public void setFilter(String key, Value value) {
        getFragment().setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        if (!getFragment().canBack()) return true;
        getFragment().goBack();
        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        /** #102：本适配器创建过的 FolderFragment（按位置），供切换前抓取子页栈。 */
        private final Map<Integer, FolderFragment> mCreated = new HashMap<>();

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        public FolderFragment getCreated(int position) {
            return mCreated.get(position);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            FolderFragment fragment = FolderFragment.newInstance(getHome().getKey(), type, 4, mFolderPaths.get(position));
            mCreated.put(position, fragment);
            return fragment;
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}

