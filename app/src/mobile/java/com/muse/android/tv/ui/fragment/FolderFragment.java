package com.muse.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Class;
import com.muse.android.tv.bean.Filter;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Value;
import com.muse.android.tv.databinding.FragmentFolderBinding;
import com.muse.android.tv.ui.base.BaseFragment;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class FolderFragment extends BaseFragment {

    private Class mType;

    /** #102：子页栈的一层——分类 id + 进入该层时实际生效的筛选条件。 */
    public static class FolderLayer implements Serializable {

        public final String typeId;
        public final HashMap<String, String> extend;

        public FolderLayer(String typeId, HashMap<String, String> extend) {
            this.typeId = typeId;
            this.extend = extend;
        }

        public HashMap<String, String> copyExtend() {
            return extend == null ? new HashMap<>() : new HashMap<>(extend);
        }
    }

    public static FolderFragment newInstance(String key, Class type, int y) {
        return newInstance(key, type, y, null);
    }

    /** #102：带子页栈路径的创建——视图切换重建时，initView 按路径逐层恢复子页栈。 */
    public static FolderFragment newInstance(String key, Class type, int y, ArrayList<FolderLayer> path) {
        Bundle args = new Bundle();
        args.putInt("y", y);
        args.putString("key", key);
        args.putParcelable("type", type);
        if (path != null && path.size() > 1) args.putSerializable("folderPath", path);
        FolderFragment fragment = new FolderFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private ArrayList<FolderLayer> getFolderPath() {
        if (getArguments() == null) return null;
        Serializable data = getArguments().getSerializable("folderPath");
        if (!(data instanceof ArrayList)) return null;
        ArrayList<FolderLayer> path = new ArrayList<>();
        for (Object item : (ArrayList<?>) data) if (item instanceof FolderLayer) path.add((FolderLayer) item);
        return path.size() > 1 ? path : null;
    }

    /**
     * #102：抓取当前子页栈（根页→当前页的每一层分类 id 与筛选条件副本）。
     * 视图切换会换掉 pager 适配器、整页重建 FolderFragment，子回退栈不在参数里、
     * 默认会被丢弃——切换前由 VodFragment 调本方法留底，切换后经参数重建。
     */
    public ArrayList<FolderLayer> capturePath() {
        ArrayList<FolderLayer> path = new ArrayList<>();
        try {
            getChildFragmentManager().executePendingTransactions();
            List<Fragment> fragments = getChildFragmentManager().getFragments();
            for (Fragment fragment : fragments) {
                if (fragment instanceof TypeFragment) {
                    TypeFragment type = (TypeFragment) fragment;
                    path.add(new FolderLayer(type.getPathTypeId(), type.getPathExtends()));
                }
            }
        } catch (Throwable e) {
            path.clear();
        }
        return path;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    public Class getType() {
        return getArguments().getParcelable("type");
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private VodFragment getParent() {
        return (VodFragment) getParentFragment();
    }

    private TypeFragment getChild() {
        return (TypeFragment) getChildFragmentManager().findFragmentById(R.id.container);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return FragmentFolderBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mType = getType();
        ArrayList<FolderLayer> path = getFolderPath();
        // #102：带子页栈路径重建时（视图切换后），先装根页、再逐层 hide+add+addToBackStack
        // 恢复到切换前的层级，返回键逐层回退的行为保持原样；已存在子页（实例被复用）时不动。
        if (path != null && getChild() == null && getChildFragmentManager().getBackStackEntryCount() == 0) {
            // #103：逐层各用一个独立事务——根页单独 replace（不进回退栈，同原 initView），
            // 其余每层一个 hide+add+addToBackStack 事务（同 openFolder 的形状）。
            // #102 的教训：整条栈塞进同一个事务只有一个回退栈条目，按一次返回会把整个
            // 事务（含装根页的 replace）整体撤销，上级页被连根移除、只剩空白。
            TypeFragment current = TypeFragment.newInstance(getKey(), path.get(0).typeId, mType.getStyle(), path.get(0).copyExtend(), mType.isFolder(), getY());
            getChildFragmentManager().beginTransaction().replace(R.id.container, current).commit();
            for (int i = 1; i < path.size(); i++) {
                TypeFragment next = TypeFragment.newInstance(getKey(), path.get(i).typeId, mType.getStyle(), path.get(i).copyExtend(), mType.isFolder(), getY());
                FragmentTransaction ft = getChildFragmentManager().beginTransaction();
                ft.hide(current);
                ft.add(R.id.container, next);
                ft.addToBackStack(null);
                ft.commit();
                current = next;
            }
        } else {
            getChildFragmentManager().beginTransaction().replace(R.id.container, TypeFragment.newInstance(getKey(), mType.getTypeId(), mType.getStyle(), getExtend(), mType.isFolder(), getY())).commit();
        }
    }

    private HashMap<String, String> getExtend() {
        HashMap<String, String> extend = new HashMap<>();
        for (Filter filter : mType.getFilters()) if (filter.getInit() != null) extend.put(filter.getKey(), filter.setSelected(filter.getInit()));
        return extend;
    }

    public void openFolder(String typeId, HashMap<String, String> extend) {
        TypeFragment next = TypeFragment.newInstance(getKey(), typeId, mType.getStyle(), extend, mType.isFolder(), getY());
        FragmentTransaction ft = getChildFragmentManager().beginTransaction();
        Optional.ofNullable(getChild()).ifPresent(ft::hide);
        ft.add(R.id.container, next);
        ft.addToBackStack(null);
        ft.commit();
    }

    public Result getResult() {
        return getParent().getResult();
    }

    public void onRefresh() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::onRefresh);
    }

    public void scrollToTop() {
        Optional.ofNullable(getChild()).ifPresent(TypeFragment::scrollToTop);
    }

    public void setFilter(String key, Value value) {
        Optional.ofNullable(getChild()).ifPresent(f -> f.setFilter(key, value));
    }

    public boolean canBack() {
        return getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void goBack() {
        getChildFragmentManager().popBackStack();
    }
}
