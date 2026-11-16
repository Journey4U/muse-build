package com.muse.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.R;
import com.muse.android.tv.utils.KeyUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 原版 OK 5.1.6 大屏版 CustomVerticalGridView 的移植。
 * 原版语义（s/t/u/setMoveTop/BACK/选中监听）全数保留；与原版的区别只有两处，均已注明：
 * 1. s() 的焦点循环只认 R.id.recycler——原版还认 R.id.horiRecycler，我方仓库无此 id。
 * 2. 保留了我方现有的 moveToTop()（setSelectedPosition 版），只因 VodActivity/CollectFragment
 *    在本次移植范围之外仍在调用它；主页线（TypeFragment/FolderFragment/HomeActivity）一律走 s()，不再调它。
 */
public class CustomVerticalGridView extends VerticalGridView {

    private List<View> views;
    private boolean pressDown;
    private boolean pressUp;
    private boolean moveTop;

    public CustomVerticalGridView(@NonNull Context context) {
        this(context, null);
    }

    public CustomVerticalGridView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomVerticalGridView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setMoveTop(true);
    }

    @Override
    protected void initAttributes(@NonNull Context context, @Nullable AttributeSet attrs) {
        super.initAttributes(context, attrs);
        setOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable ViewHolder child, int position, int subposition) {
                if (pressDown && position == 1) hideHeader();
                if (pressUp && position == 0) showHeader();
            }
        });
    }

    /** 原版 t()：在宿主 Activity 里按 id 收集要联动的头视图（条 recycler 等），空值过滤。 */
    public void setHeader(FragmentActivity activity, int... layoutIds) {
        if (activity != null) views = Arrays.stream(layoutIds).mapToObj(id -> (View) activity.findViewById(id)).filter(Objects::nonNull).toList();
    }

    public void setMoveTop(boolean moveTop) {
        this.moveTop = moveTop;
    }

    /** 原版 u()：头视图全部 VISIBLE。 */
    public void showHeader() {
        if (views != null) for (View view : views) view.setVisibility(View.VISIBLE);
    }

    public void hideHeader() {
        if (views != null) for (View view : views) view.setVisibility(View.GONE);
    }

    /**
     * 原版 s()：只滚动 + 焦点移到条 + 显头，不动选区。
     * 在真顶（selected==0）或空适配器时返回 false，让 BACK 继续冒泡。
     */
    public boolean s() {
        if (views == null || getSelectedPosition() == 0 || getAdapter() == null || getAdapter().getItemCount() == 0) return false;
        for (View view : views) if (view.getId() == R.id.recycler) view.requestFocus();
        scrollToPosition(0);
        showHeader();
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (!KeyUtil.isActionDown(event)) return super.dispatchKeyEvent(event);
        if (KeyUtil.isBackKey(event)) return moveTop && s();
        pressUp = KeyUtil.isUpKey(event);
        pressDown = KeyUtil.isDownKey(event);
        return super.dispatchKeyEvent(event);
    }

    /**
     * 我方旧版回顶（setSelectedPosition 版），主页线已不再使用；
     * 仅为移植范围外的 VodActivity/CollectFragment 保留，勿在主页线调用。
     * 与旧版唯一的区别：焦点循环的 id 由 typeStrip 改为 recycler——旧 activity_home 被移植版取代后，
     * typeStrip 这个 id 在全仓库不再存在，继续引用会编译失败；对 Vod/Collect 而言旧循环本就是空转
     * （它们的布局里没有 typeStrip），改后要么仍空转、要么按方法本意聚焦到条，均不回归。
     */
    public boolean moveToTop() {
        if (views == null || getAdapter() == null || getAdapter().getItemCount() == 0) return false;
        View first = getChildAt(0);
        boolean atTop = getSelectedPosition() == 0 && first != null && getChildAdapterPosition(first) == 0;
        if (atTop) return false;
        for (View view : views) if (view.getId() == R.id.recycler) view.requestFocus();
        setSelectedPosition(0);
        scrollToPosition(0);
        showHeader();
        return true;
    }
}
