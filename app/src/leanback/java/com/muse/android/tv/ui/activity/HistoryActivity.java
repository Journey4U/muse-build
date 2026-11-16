package com.muse.android.tv.ui.activity;

import android.os.Bundle;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.History;
import com.muse.android.tv.databinding.ActivityHistoryBinding;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.ui.adapter.BaseDiffCallback;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.presenter.HistoryPresenter;
import com.muse.android.tv.utils.ResUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

/**
 * 历史页（原版 OK 5.1.6 大屏版 HistoryActivity 的移植，功能组历史按钮的落点）。
 * 原版结构：工具栏 + 删除按钮 + ProgressLayout + 列表；点播进播放页；删除态下 BACK 先退删除态；
 * 销毁时发历史刷新事件；HISTORY 事件到达时重载。
 * 与原版的区别（均因我方没有原版的类，见 DIFF-REPORT，不自编）：
 * - 原版用 RecyclerView + ui.adapter.q；这里复用我方现成的 HistoryPresenter（与首页历史行同一 presenter）
 *   + VerticalGridView，不新写 adapter。
 * - 原版工具栏删除按钮行为（N3.q case 10）未在反编译中确认可读的分支语义；
 *   这里沿用首页历史行的删除 UX：非删除态点按进入删除态，删除态点按清空全部。
 * - activity_history.xml 未在原版资源中单独核对，按原版结构（工具栏+删除+ProgressLayout+网格）重建；
 *   删除图标用系统 android.R.drawable.ic_menu_delete（我方无原版删除图标资源）。
 */
public class HistoryActivity extends BaseActivity implements HistoryPresenter.OnClickListener {

    private ActivityHistoryBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this))));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        load();
    }

    @Override
    protected void initEvent() {
        mBinding.delete.setOnClickListener(v -> {
            if (mPresenter.isDelete()) clearHistory();
            else setHistoryDelete(true);
        });
    }

    /** 原版 R()：取 History.get() 全量，空→空态、非空→内容态。 */
    private void load() {
        List<History> items = History.get();
        mAdapter.setItems(items, new BaseDiffCallback<History>());
        if (items.isEmpty()) mBinding.progressLayout.showEmpty();
        else mBinding.progressLayout.showContent();
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mAdapter.notifyArrayItemRangeChanged(0, mAdapter.size());
    }

    private void clearHistory() {
        History.clear(VodConfig.getCid());
        mPresenter.setDelete(false);
        mAdapter.clear();
        mBinding.progressLayout.showEmpty();
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mAdapter.remove(item.delete());
        if (mAdapter.size() > 0) return;
        mPresenter.setDelete(false);
        mBinding.progressLayout.showEmpty();
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
            case HISTORY:
                load();
                break;
        }
    }

    @Override
    protected void onBackInvoked() {
        if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else {
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        RefreshEvent.history();
        super.onDestroy();
    }
}
