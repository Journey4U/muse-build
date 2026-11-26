package com.muse.android.tv.ui.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.ui.custom.CustomWallView;
import com.muse.android.tv.utils.ThemeTone;
import com.muse.android.tv.utils.Util;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import me.jessyan.autosize.AutoSizeCompat;

public abstract class BaseActivity extends AppCompatActivity implements ThemeTone.Host {

    /** 换装第 2 步：本页创建时套用的深浅（true=浅色壁纸那套颜色与赤陶焦点框）。 */
    private static final String KEY_TONE_FOCUS = "muse_tone_focus";
    private boolean mToneLight;
    private boolean mTonePending;
    private boolean mRecreating;

    protected abstract ViewBinding getBinding();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mToneLight = customWall() && ThemeTone.apply(this);
        super.onCreate(savedInstanceState);
        reapplyTone();
        setContentView(getBinding().getRoot());
        EventBus.getDefault().register(this);
        initView(savedInstanceState);
        Util.hideSystemUI(this);
        setBackCallback();
        initEvent();
        restoreToneFocus(savedInstanceState);
    }

    /** 换装第 2 步：壁纸换了深浅就重建本页（不在前台的页回到前台时再重建）；遥控器焦点重建后落回原控件。 */
    @Override
    public void checkTone() {
        if (!customWall() || mRecreating || isFinishing()) return;
        if (mToneLight == ThemeTone.isLight()) return;
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) recreate();
        else mTonePending = true;
    }

    /**
     * 电视对比度修复：AppCompat 在 super.onCreate / onStart 里遇到配置差异（夜间模式、语言等）时，
     * 会 setTheme(主题) 并 applyStyle(主题, true) 重刷一遍，把先前叠上的浅色叠层覆盖回深色那套
     * （Liu 电视真机照片：羊皮纸壁纸上仍是白字、灰胶囊、白焦点框）。浅色时在这两处之后再叠一次；深色不动。
     */
    private void reapplyTone() {
        if (mToneLight) getTheme().applyStyle(R.style.ThemeOverlay_Muse_Light, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reapplyTone();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mTonePending) return;
        mTonePending = false;
        checkTone();
    }

    @Override
    public void recreate() {
        if (mRecreating) return;
        mRecreating = true;
        super.recreate();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        View focus = getCurrentFocus();
        if (mRecreating && focus != null && focus.getId() != View.NO_ID) outState.putInt(KEY_TONE_FOCUS, focus.getId());
    }

    private void restoreToneFocus(Bundle savedInstanceState) {
        if (savedInstanceState == null || !savedInstanceState.containsKey(KEY_TONE_FOCUS)) return;
        int id = savedInstanceState.getInt(KEY_TONE_FOCUS);
        getWindow().getDecorView().post(() -> {
            View view = findViewById(id);
            if (view != null && view.isFocusable()) view.requestFocus();
        });
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (!customWall()) return;
        ((ViewGroup) findViewById(android.R.id.content)).addView(new CustomWallView(this, null), 0, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    protected FragmentActivity getActivity() {
        return this;
    }

    protected boolean customWall() {
        return true;
    }

    protected void initView(Bundle savedInstanceState) {
    }

    protected void initEvent() {
    }

    protected boolean isVisible(View view) {
        return view.getVisibility() == View.VISIBLE;
    }

    protected boolean isGone(View view) {
        return view.getVisibility() == View.GONE;
    }

    protected void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyDataSetChanged());
    }

    private void setBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBackInvoked();
            }
        });
    }

    private Resources hackResources(Resources resources) {
        try {
            AutoSizeCompat.autoConvertDensityOfGlobal(resources);
            return resources;
        } catch (Exception ignored) {
            return resources;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSubscribe(Object o) {
    }

    @Override
    public Resources getResources() {
        return hackResources(super.getResources());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    protected void onBackInvoked() {
        finish();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
