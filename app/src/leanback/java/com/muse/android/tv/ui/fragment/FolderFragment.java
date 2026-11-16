package com.muse.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Cache;
import com.muse.android.tv.bean.Class;
import com.muse.android.tv.bean.Filter;
import com.muse.android.tv.databinding.FragmentFolderBinding;
import com.muse.android.tv.ui.activity.VodActivity;
import com.muse.android.tv.ui.base.BaseFragment;

import java.util.HashMap;
import java.util.Optional;

/**
 * 分类包装页（原版 OK 5.1.6 大屏版 P3/c 的移植）。
 * 与我方旧版的区别（均为拿掉原版没有的自加项）：
 * - 删 Activity 注册表（v22 遗留）：页查找走 HomePagerAdapter 的 fragment 缓存。
 * - 删 setInitialExtend / mFilterMemory：extends 只取 Cache 里各 Filter 的 init（原版 P3/c.Z 的原样）。
 * - 删 moveToTop()/getSelectedPosition()：原版包装页没有这两个方法；回顶走内页 grid.s()（见 scrollChildToTop）。
 * - setUserVisibleHint 仍只在隐藏时向内页传 V(false)（原版 P3/c.V 的原样）。
 * fragment_folder.xml 结构已与原版一致（FrameLayout + FragmentContainerView @id/container），不改。
 */
public class FolderFragment extends BaseFragment {

    private FragmentFolderBinding mBinding;
    private Class mType;

    public static FolderFragment newInstance(String key, Class type) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putParcelable("type", type);
        FolderFragment fragment = new FolderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    public Class getType() {
        return getArguments().getParcelable("type");
    }

    public TypeFragment getChild() {
        return (TypeFragment) getChildFragmentManager().findFragmentById(R.id.container);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentFolderBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mType = getType();
        getChildFragmentManager().beginTransaction().replace(R.id.container, TypeFragment.newInstance(getKey(), mType.getTypeId(), mType.getStyle(), getExtend(), mType.isFolder())).commit();
    }

    /** 原版 P3/c.Z：extends 只取 Cache 里各 Filter 的 init，用户已选项不跨页携带。 */
    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        for (Filter filter : Cache.get(mType)) if (filter.getInit() != null) extend.put(filter.getKey(), filter.getInit());
        return extend;
    }

    public void openFolder(String typeId, HashMap<String, String> extend) {
        TypeFragment next = TypeFragment.newInstance(getKey(), typeId, mType.getStyle(), extend, mType.isFolder());
        FragmentTransaction ft = getChildFragmentManager().beginTransaction();
        if (getActivity() instanceof VodActivity) ((VodActivity) getActivity()).closeFilter();
        Optional.ofNullable(getChild()).ifPresent(ft::hide);
        ft.add(R.id.container, next);
        ft.addToBackStack(null);
        ft.commit();
    }

    public void toggleFilter(boolean visible) {
        Optional.ofNullable(getChild()).ifPresent(f -> f.toggleFilter(visible));
    }

    /** 原版 S().b0()：内页重载第 1 页。 */
    public void onRefresh() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::onRefresh);
    }

    /** 原版 B3.b(18) 的等价物：内页 grid 只滚动到 0（不碰选区）。 */
    public void scrollChildToTop() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::scrollGridToTop);
    }

    /** 兼容 VodActivity 的 BACK 回顶（旧版方法）：委托内页 moveToTop；主页线不调它。 */
    public boolean moveToTop() {
        return Optional.ofNullable(getChild()).map(TypeFragment::moveToTop).orElse(false);
    }

    public void focusFirst() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::focusFirst);
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        getChildFragmentManager().popBackStack();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null && !isVisibleToUser) Optional.ofNullable(getChild()).ifPresent(f -> f.setUserVisibleHint(false));
    }
}
