package com.muse.android.tv.ui.activity;

import android.util.SparseArray;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;

import com.muse.android.tv.bean.Class;
import com.muse.android.tv.ui.fragment.FolderFragment;
import com.muse.android.tv.ui.fragment.HomePageFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * 原版 OK 5.1.6 大屏版 C0633h 的 Home 分支移植（FragmentStatePagerAdapter，BEHAVIOR_SET_USER_VISIBLE_HINT）。
 * 原版 C0633h 被 Home/Collect/Vod 三处共用，这里只写 Home 这一支 case。
 * 页数 = strip 项数（首页合成项 + 分类）；位置 0 = HomePageFragment，其余 = FolderFragment（Bundle: key=首页源 key，type=Class）。
 * 原版在新建 fragment 时显式 setUserVisibleHint(false)，BEHAVIOR_SET_USER_VISIBLE_HINT 下 ViewPager 会做同样的事，
 * 因此 HomeActivity 的 pin 钩子（V()/H()）能如原版一样在切页时触发——ViewPager2 做不到这一点。
 * 类型每次到达都新建一个 adapter（HomeActivity.onHomeResult），旧页全部重建，与原版一致。
 * fragment 缓存（instantiateItem/destroyItem 维护）供 HomeActivity.S()/T() 查找当前页与首页页，
 * 对应原版 C0633h.c(pager, position) 的缓存表。
 */
public class HomePagerAdapter extends FragmentStatePagerAdapter {

    private final String mHomeKey;
    private final List<Class> mTypes;
    private final SparseArray<Fragment> mFragments = new SparseArray<>();

    public HomePagerAdapter(FragmentManager fm, String homeKey, List<Class> types) {
        super(fm, BEHAVIOR_SET_USER_VISIBLE_HINT);
        mHomeKey = homeKey;
        mTypes = types == null ? new ArrayList<>() : new ArrayList<>(types);
    }

    @Override
    public int getCount() {
        return mTypes.size() + 1;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        if (position == 0) {
            return new HomePageFragment();
        }
        return FolderFragment.newInstance(mHomeKey, mTypes.get(position - 1));
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        Fragment fragment = (Fragment) super.instantiateItem(container, position);
        mFragments.put(position, fragment);
        return fragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        mFragments.remove(position);
        super.destroyItem(container, position, object);
    }

    /** 原版 C0633h.c(pager, position) 的等价物：已实例化的页 fragment，未实例化返回 null。 */
    public Fragment getFragment(int position) {
        return mFragments.get(position);
    }
}
