package com.muse.android.tv.ui.fragment;

import android.annotation.SuppressLint;
import android.content.Intent;
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
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.Product;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.LiveConfig;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Button;
import com.muse.android.tv.bean.Func;
import com.muse.android.tv.bean.History;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.bean.Style;
import com.muse.android.tv.bean.Vod;
import com.muse.android.tv.databinding.FragmentHomePageBinding;
import com.muse.android.tv.model.SiteViewModel;
import com.muse.android.tv.ui.activity.CastActivity;
import com.muse.android.tv.ui.activity.CollectActivity;
import com.muse.android.tv.ui.activity.HistoryActivity;
import com.muse.android.tv.ui.activity.HomeActivity;
import com.muse.android.tv.ui.activity.KeepActivity;
import com.muse.android.tv.ui.activity.LiveActivity;
import com.muse.android.tv.ui.activity.PushActivity;
import com.muse.android.tv.ui.activity.SearchActivity;
import com.muse.android.tv.ui.activity.SettingActivity;
import com.muse.android.tv.ui.activity.VideoActivity;
import com.muse.android.tv.ui.activity.VodActivity;
import com.muse.android.tv.ui.adapter.BaseDiffCallback;
import com.muse.android.tv.ui.base.BaseFragment;
import com.muse.android.tv.ui.custom.CustomRowPresenter;
import com.muse.android.tv.ui.custom.CustomSelector;
import com.muse.android.tv.ui.presenter.FuncPresenter;
import com.muse.android.tv.ui.presenter.HeaderPresenter;
import com.muse.android.tv.ui.presenter.HistoryPresenter;
import com.muse.android.tv.ui.presenter.ProgressPresenter;
import com.muse.android.tv.ui.presenter.VodPresenter;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * 电视版首页页（原版 OK 5.1.6 大屏版 P3/e 的移植）。
 * 与我方旧版的区别（均为拿掉原版没有的自加项）：
 * - 删 Activity 注册表（v22 遗留）：页查找走 HomePagerAdapter 的 fragment 缓存。
 * - 删 initialLoad/reloadHome/getVideo：首页内容不再由本页的 ViewModel 拉取，
 *   改由 HomeActivity 的 Activity 级 ViewModel 拉取（原版 R1/L），经 300ms 填充回调 fillHome() 到达。
 * - 删写死的功能行：功能行来自 Button.getButtons()（原版 P3/e.a0），直播在 LiveConfig 无 URL 时排除。
 * - 适配器顺序改回原版：功能行、home_history、home_recommend（旧版把 history 放 recommend 之后）。
 * - 历史行增删改回原版 b0()（旧版 getHistory 逻辑与原版不等价，且首页内容曾插在 history 标题之前）。
 * - 功能行间距由 16 改为 22（原版 k(22,1,1)；Vod 行 16、历史行 16+ALIGNED 与原版一致）。
 * - 布局改回原版 fragment_home：ProgressLayout 包 VerticalGridView（padding 26/12/26/24），
 *   旧版的裸 VerticalGridView + "progress" 适配器项已删掉。
 * 未搬的两处原版细节（我方 bean 不支持，见 DIFF-REPORT）：
 * - a0() 的首末功能首尾焦点环绕（Func bean 无 nextFocusLeft/Right 字段）。
 * - f0() 的标题 nextFocusDown（标题 DOWN 走 Activity 的 focusCurrentPageFirst，与旧版相同）。
 * 首页 Vod 不钻文件夹（与原版一致：直接进播放页/收藏）。
 */
public class HomePageFragment extends BaseFragment implements VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener {

    private FragmentHomePageBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mHistoryAdapter;
    private ListRow mFuncRow;
    private HistoryPresenter mPresenter;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    /** 原版 P3/e.W()：是否显示最近观看标题行（Prefers home_history，默认 true）。 */
    private boolean showHistory() {
        return Prefers.getBoolean("home_history", true);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentHomePageBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        setAdapter();
        // 重建补填（对原版的最小偏离，见 DIFF-REPORT D1）：
        // 原版首页内容只在类型到达时填充一次；ViewPager 隔页销毁后重建的首页页若不补填，
        // 将只剩功能行与标题、内容全空（v38 的页内自加载没有这个缺口）。
        // 这里用 Activity 已缓存的最新首页结果直接补填，不新发请求。
        if (getActivity() instanceof HomeActivity) {
            HomeActivity activity = (HomeActivity) getActivity();
            if (activity.hasHomeResult()) fillHome(activity.getHomeResult());
        }
    }

    @Override
    protected void initEvent() {
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (getActivity() instanceof HomeActivity) {
                    ((HomeActivity) getActivity()).onHomeGridSelected(position);
                }
            }
        });
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(22), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_SMALL, HorizontalGridView.FOCUS_SCROLL_ALIGNED), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
    }

    /** 原版 P3/e.Z 的前半：ProgressLayout 进度态 + 功能行 + history 标题（若 W）+ recommend 标题，随后 b0()。 */
    private void setAdapter() {
        mBinding.progressLayout.showProgress();
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        mFuncRow = new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this)));
        List<Func> funcs = getFuncItems();
        if (!funcs.isEmpty()) {
            mFuncAdapter.setItems(funcs, new BaseDiffCallback<Func>());
            mAdapter.add(mFuncRow);
        }
        if (showHistory()) mAdapter.add(R.string.home_history);
        mAdapter.add(R.string.home_recommend);
        b0();
    }

    /** 原版 P3/e.a0()：功能行来自 Button.getButtons()，直播在 LiveConfig 无 URL 时排除。首末焦点环绕未搬（见类注释）。 */
    private List<Func> getFuncItems() {
        List<Func> items = new ArrayList<>();
        boolean hasLiveUrl = LiveConfig.hasUrl();
        for (Button button : Button.getButtons()) {
            if (!hasLiveUrl && button.getResId() == R.string.home_live) continue;
            items.add(Func.create(button.getResId()));
        }
        return items;
    }

    /** 原版 P3/e.I() 的等价物：配置变化时重建功能行（删行 0、重建、行 0 加回）。 */
    public void setFunc() {
        if (mAdapter == null) return;
        int index = mAdapter.indexOf(mFuncRow);
        if (index >= 0) mAdapter.removeItems(index, 1);
        List<Func> funcs = getFuncItems();
        mFuncAdapter.setItems(funcs, new BaseDiffCallback<Func>());
        if (!funcs.isEmpty()) mAdapter.add(0, mFuncRow);
    }

    /** 原版 P3/e.b0()：历史行增删（原样，包括 W() 由真变假且无历史行时连带删 recommend 的原版边缘行为）。 */
    public void b0() {
        if (mAdapter == null) return;
        int c = getHistoryIndexAfter();
        int d = getRecommendIndexAfter();
        if (c == -1) {
            if (!showHistory()) return;
            int i = d - 1;
            if (i < 0) i = 0;
            mAdapter.add(i, R.string.home_history);
        }
        if (!showHistory()) {
            mAdapter.removeItems(c - 1, 2);
            return;
        }
        c = getHistoryIndexAfter();
        d = getRecommendIndexAfter();
        List<History> items = History.get();
        boolean hasRow = (d - c == 2);
        if (items.isEmpty() && hasRow) mAdapter.removeItems(c, 1);
        if (!items.isEmpty() && !hasRow) mAdapter.add(c, new ListRow(mHistoryAdapter));
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    /** 原版 Z0/E case 6 的填充：ProgressLayout 内容态 + 删 recommend 之后全部 + 按 style 追加结果。 */
    public void fillHome(Result result) {
        if (mBinding == null || mAdapter == null) return;
        mBinding.progressLayout.showContent();
        int d = getRecommendIndexAfter();
        if (d >= 0 && mAdapter.size() > d) mAdapter.removeItems(d, mAdapter.size() - d);
        Style style = result.getStyle(getHome().getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
    }

    private void addGrid(List<Vod> items, Style style) {
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            adapter.addAll(0, part);
            rows.add(new ListRow(adapter));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    public void showProgress() {
        if (mBinding == null) return;
        mBinding.progressLayout.showProgress();
    }

    public void showContent() {
        if (mBinding == null) return;
        mBinding.progressLayout.showContent();
    }

    public boolean isProgress() {
        return mBinding != null && mBinding.progressLayout.isProgress();
    }

    /** 原版 Z0/E case 6 的门：首页页已初始化（Z 跑完）才可填充。 */
    public boolean isAdapterReady() {
        return mAdapter != null;
    }

    private int getHistoryIndexAfter() {
        int index = mAdapter.indexOf(R.string.home_history);
        return index < 0 ? -1 : index + 1;
    }

    private int getRecommendIndexAfter() {
        int index = mAdapter.indexOf(R.string.home_recommend);
        return index < 0 ? -1 : index + 1;
    }

    public void setHistoryDelete(boolean delete) {
        if (mPresenter == null) return;
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    public boolean isHistoryDelete() {
        return mPresenter != null && mPresenter.isDelete();
    }

    private void clearHistory() {
        mAdapter.removeItems(getHistoryIndexAfter(), 1);
        History.clear(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    /**
     * 首页回顶：滚动到 0 并复位选区。原版 home BACK 只调 scrollToPosition(0)；
     * 这里多复位选区（我方 v22–v38 实测有效的形态），视觉终态与原版相同且保证下次 BACK 能进双击退出。
     */
    public void scrollToTop() {
        if (mBinding == null) return;
        mBinding.recycler.scrollToPosition(0);
        mBinding.recycler.setSelectedPosition(0);
    }

    public int getSelectedPosition() {
        return mBinding == null ? 0 : mBinding.recycler.getSelectedPosition();
    }

    public void focusFirst() {
        if (mBinding == null) return;
        View child = mBinding.recycler.getChildAt(0);
        if (child != null) child.requestFocus();
        else mBinding.recycler.requestFocus();
    }

    @Override
    public void onItemClick(Func item) {
        if (item.getResId() == R.string.home_live) LiveActivity.start(requireActivity());
        else if (item.getResId() == R.string.home_cast) startActivity(new Intent(requireActivity(), CastActivity.class));
        else if (item.getResId() == R.string.home_history_short) startActivity(new Intent(requireActivity(), HistoryActivity.class));
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(requireActivity());
        else if (item.getResId() == R.string.home_push) PushActivity.start(requireActivity());
        else if (item.getResId() == R.string.home_search) SearchActivity.start(requireActivity());
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(requireActivity());
        else if (item.getResId() == R.string.home_vod) {
            if (getActivity() instanceof HomeActivity) {
                VodActivity.start(requireActivity(), getHome().getKey(), ((HomeActivity) getActivity()).getHomeResult());
            }
        }
    }

    @Override
    public boolean onLongClick(Func item) {
        return false;
    }

    public void reloadConfig() {
        if (getActivity() instanceof HomeActivity) ((HomeActivity) getActivity()).reloadConfig();
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            new ViewModelProvider(requireActivity()).get(SiteViewModel.class).action(getHome().getKey(), item.getAction());
        } else if (getHome().isIndex()) CollectActivity.start(requireActivity(), item.getName());
        else VideoActivity.start(requireActivity(), getHome().getKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(requireActivity(), item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        mAdapter.removeItems(getHistoryIndexAfter(), 1);
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }
}
