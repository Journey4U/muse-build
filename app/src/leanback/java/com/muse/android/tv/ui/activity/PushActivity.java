package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivityPushBinding;
import com.muse.android.tv.server.Server;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.utils.QRCode;
import com.muse.android.tv.utils.ResUtil;
import com.muse.android.tv.utils.Sniffer;
import com.muse.android.tv.utils.ThemeTone;
import com.muse.android.tv.utils.Util;

public class PushActivity extends BaseActivity {

    private ActivityPushBinding mBinding;

    public static void start(Activity activity) {
        start(activity, 2);
    }

    public static void start(Activity activity, int tab) {
        Intent intent = new Intent(activity, PushActivity.class);
        intent.putExtra("tab", tab);
        activity.startActivity(intent);
    }

    private int getTab() {
        return getIntent().getIntExtra("tab", 2);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityPushBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        if (ThemeTone.isLight(this)) {
            // 浅色壁纸（羊皮纸/护眼绿）：去掉整页灰蒙层，「复制」用浅卡片，二维码改深点衬白卡（白点压浅底手机扫不出）
            boolean green = ThemeTone.isGreen();
            mBinding.getRoot().setBackgroundColor(Color.TRANSPARENT);
            mBinding.clip.setBackgroundResource(green ? R.drawable.shape_button_green_normal : R.drawable.shape_button_light_normal);
            mBinding.code.setBackgroundResource(R.drawable.shape_qr_card);
            int ink = ThemeTone.inkColor(this);
            mBinding.code.setImageBitmap(QRCode.getBitmap(Server.get().getAddress(getTab()), 250, 1, ink, Color.WHITE));
        } else {
            mBinding.code.setImageBitmap(QRCode.getBitmap(Server.get().getAddress(getTab()), 250, 1));
        }
        mBinding.info.setText(ResUtil.getString(R.string.push_info, Server.get().getAddress()));
    }

    @Override
    protected void initEvent() {
        mBinding.code.setOnClickListener(this::onCode);
        mBinding.clip.setOnClickListener(this::onClip);
    }

    private void onClip(View view) {
        CharSequence text = Util.getClipText();
        if (!TextUtils.isEmpty(text)) VideoActivity.start(this, Sniffer.getUrl(text.toString()));
    }

    private void onCode(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(Server.get().getAddress(getTab())));
        startActivity(intent);
    }
}
