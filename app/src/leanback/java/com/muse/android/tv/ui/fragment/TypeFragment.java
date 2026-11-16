package com.muse.android.tv.ui.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.Product;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Cache;
import com.muse.android.tv.bean.Filter;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.bean.Style;
import com.muse.android.tv.bean.Value;
import com.muse.android.tv.bean.Vod;
import com.muse.android.tv.databinding.FragmentTypeBinding;
import com.muse.android.tv.model.SiteViewModel;
import com.muse.android.tv.ui.activity.CollectActivity;
import com.muse.android.tv.ui.activity.HomeActivity;
import com.muse.android.tv.ui.activity.VideoActivity;
import com.muse.android.tv.ui.base.BaseFragment;
import com.muse.android.tv.ui.custom.CustomRowPresenter;
import com.muse.android.tv.ui.custom.CustomScroller;
import com.muse.android.tv.ui.custom.CustomSelector;
import com.muse.android.tv.ui.presenter.FilterPresenter;
import com.muse.android.tv.ui.presenter.VodPresenter;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.ResUtil;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 分类页（原版 OK 5.1.6 大屏版 P3/f 的移植）。
 * 与我方旧版的区别（均为拿掉原版没有的自加项）：
 * - setHeader 改回 R.id.recycler（旧版曾指到已删的 typeStrip，v28 的条失联根因之一）。
 * - 删 setClick 里的 putFilterMemory（mFilterMemory 已随 HomeActivity 旧版一起拿掉；筛选只认 extends）。
 * - 删 setAdapter 里 v26 的 post-data 回顶（原版数据路径无任何滚动/选区代码）。
 * - 删 initEvent 里向 Activity 汇报选区的监听（它只服务已删的 updateToolbarVisibility；条显隐走 grid 自身监听）。
 * - 删 onPause 回顶（v27）：原版回顶只来自 ViewPager1 的 V()/H() 钩子，不来自 onPause。
 * - 删 moveToTop()/getSelectedPosition()：原版内页没有这两个方法；回顶走 grid.s()（V）与 scrollGridToTop（B3.b(18)）。
 * - 文件夹分支 headerVisible 改为原版的恒 false（旧版用 isHeaderVisible() 读条显隐，那个方法已随 grid 改写删掉）。
 * - 文件夹分支补原版 B3.b(21)：打开文件夹时若当前分类的筛选是开着的，调 Activity 关掉它。
 * 保留的我方机制（与原版行为一致，仅 API 映射不同）：Filter/Value 用 setSelected（原版 setActivated），
 * 分页用 CustomScroller（原版 N3/m 的同名方法），行分区用 Product.getColumn（原版按 style 分块）。
 */
public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Filter> mFilters;
    private boolean headerVisible;
    private boolean filterVisible;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        TypeFragment fragment = new TypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private List<Filter> getFilter() {
        return Cache.copy(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return ((FolderFragment) getParentFragment());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        mFilters = getFilter();
        setRecyclerView();
        setViewModel();
        setFilters();
        getVideo();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(8, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED), FilterPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setHeader(getActivity(), R.id.recycler);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
    }

    private void setFilters() {
        for (Filter filter : mFilters) {
            if (mExtends.containsKey(filter.getKey())) {
                filter.setSelected(mExtends.get(filter.getKey()));
            }
        }
    }

    private void setClick(ArrayObjectAdapter adapter, String key, Value item) {
        for (int i = 0; i < adapter.size(); i++) ((Value) adapter.get(i)).setSelected(item);
        adapter.notifyArrayItemRangeChanged(0, adapter.size());
        if (item.isSelected()) mExtends.put(key, item.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    private void getVideo() {
        mLast = null;
        checkFilter();
        mScroller.reset();
        getVideo(getTypeId(), "1");
    }

    private void getVideo(String typeId, String page) {
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        boolean first = mScroller.first();
        boolean flag = mExtends.isEmpty();
        int size = result.getList().size();
        mBinding.progressLayout.showContent(first & flag, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        Style style = result.getStyle(getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
        checkMore();
    }

    private void checkMore() {
        if (mScroller.isDisable() || mAdapter.size() >= 5) return;
        mScroller.checkMore();
    }

    private boolean checkLastSize(List<Vod> items, Style style) {
        if (mLast == null || items.isEmpty()) return false;
        int size = Product.getColumn(style) - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addGrid(items.subList(size, items.size()), style);
        return true;
    }

    private void addGrid(List<Vod> items, Style style) {
        if (checkLastSize(items, style)) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private ListRow getRow(Filter filter) {
        FilterPresenter presenter = new FilterPresenter(filter.getKey());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        presenter.setOnClickListener((key, item) -> setClick(adapter, key, item));
        adapter.setItems(filter.getValue(), null);
        return new ListRow(adapter);
    }

    /** 原版 P3/b：每 Filter 一 ListRow，48ms 后滚到 0，addAll(0, rows)（原版即无保护的 addAll，照搬）。 */
    private void showFilter() {
        List<ListRow> rows = new ArrayList<>();
        for (Filter filter : mFilters) rows.add(getRow(filter));
        mBinding.recycler.postDelayed(() -> mBinding.recycler.scrollToPosition(0), 48);
        mAdapter.addAll(0, rows);
    }

    private void hideFilter() {
        mAdapter.removeItems(0, mFilters.size());
    }

    public void toggleFilter(boolean visible) {
        if (mFilters.isEmpty()) return;
        this.filterVisible = visible;
        if (visible) showFilter();
        else hideFilter();
    }

    private void checkFilter() {
        int adapterSize = mAdapter.size();
        int filterSize = filterVisible ? mFilters.size() : 0;
        if (adapterSize > filterSize) mAdapter.removeItems(filterSize, mAdapter.size() - filterSize);
        if (adapterSize == 0) mBinding.progressLayout.showProgress();
        else mBinding.swipeLayout.setRefreshing(true);
    }

    public void onRefresh() {
        getVideo();
    }

    /** 原版 B3.b(18)：内页 grid 只滚动到 0（不碰选区），供 Activity BACK 的条隐藏分支调用。 */
    public void scrollGridToTop() {
        if (mBinding == null) return;
        mBinding.recycler.scrollToPosition(0);
    }

    /** 兼容 VodActivity 的 BACK 回顶（旧版方法）：内页 grid 的 moveToTop（选区复位版）；主页线不调它，主页线走 s()。 */
    public boolean moveToTop() {
        return mBinding != null && mBinding.recycler.moveToTop();
    }

    public void focusFirst() {
        if (mBinding == null) return;
        View child = mBinding.recycler.getChildAt(0);
        if (child != null) child.requestFocus();
        else mBinding.recycler.requestFocus();
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
            headerVisible = false;
            if (getActivity() instanceof HomeActivity) ((HomeActivity) getActivity()).onFolderOpened();
        } else {
            if (getSite().isIndex()) CollectActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        CollectActivity.start(requireActivity(), item.getName());
        return true;
    }

    @Override
    public boolean onLoadMore(String page) {
        getVideo(getTypeId(), page);
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            mBinding.recycler.showHeader();
        } else {
            if (headerVisible) mBinding.recycler.showHeader();
            else mBinding.recycler.hideHeader();
            mBinding.recycler.requestFocus();
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null) mBinding.recycler.s();
    }
}
