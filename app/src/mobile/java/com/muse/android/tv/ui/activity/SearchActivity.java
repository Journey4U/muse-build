package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivitySearchBinding;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.fragment.SearchFragment;
import com.muse.android.tv.ui.fragment.SearchScope;

public class SearchActivity extends BaseActivity {

    public static void start(Activity activity) {
        start(activity, "");
    }

    public static void start(Activity activity, String keyword) {
        start(activity, keyword, SearchScope.ALL, "");
    }

    /**
     * 按搜索范围启动搜索页：scope 取 all / current / group / type。
     */
    public static void start(Activity activity, String keyword, String scope, String value) {
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword);
        intent.putExtra("scope", scope);
        intent.putExtra("value", value);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        return getIntent().getStringExtra("keyword");
    }

    private String getScope() {
        String scope = getIntent().getStringExtra("scope");
        return scope == null ? SearchScope.ALL : scope;
    }

    private String getValue() {
        String value = getIntent().getStringExtra("value");
        return value == null ? "" : value;
    }

    @Override
    protected ViewBinding getBinding() {
        return ActivitySearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.container, SearchFragment.newInstance(getKeyword(), getScope(), getValue()), SearchFragment.class.getSimpleName()).commit();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
        } else {
            super.onBackInvoked();
        }
    }
}

