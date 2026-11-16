package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.ActivitySettingPlayerBinding;
import com.muse.android.tv.impl.BufferListener;
import com.muse.android.tv.impl.UaListener;
import com.muse.android.tv.player.mpv.MpvUtil;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.dialog.BufferDialog;
import com.muse.android.tv.ui.dialog.MpvConfDialog;
import com.muse.android.tv.ui.dialog.UaDialog;
import com.muse.android.tv.utils.ResUtil;

public class SettingPlayerActivity extends BaseActivity implements UaListener, BufferListener {

    private ActivitySettingPlayerBinding mBinding;
    private String[] render;
    private String[] scale;
    private String[] engine;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPlayerActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPlayerBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setVisible();
        setPlaybackModeText();
        mBinding.engine.requestFocus();
        mBinding.adblockText.setText(Setting.getSwitch(Setting.isAdblock()));
        mBinding.libassText.setText(Setting.getSwitch(PlayerSetting.isLibass()));
        mBinding.bufferText.setText(String.valueOf(PlayerSetting.getBuffer()));
        mBinding.mpvVulkanText.setText(Setting.getSwitch(PlayerSetting.isMpvVulkan()));
        mBinding.mpvGpuNextText.setText(Setting.getSwitch(PlayerSetting.isMpvGpuNext()));
        mBinding.backgroundText.setText(Setting.getSwitch(PlayerSetting.isBackgroundOn()));
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);
        mBinding.netInfoText.setText(Setting.getSwitch(PlayerSetting.isNetInfo()));
        mBinding.mediaInfoText.setText(Setting.getSwitch(PlayerSetting.isMediaInfo()));
        setAutoPlayText();
    }

    @Override
    protected void initEvent() {
        mBinding.engine.setOnClickListener(this::setEngine);
        mBinding.decode.setOnClickListener(this::onDecodeSetting);
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.libass.setOnClickListener(this::setLibass);
        mBinding.mpvConf.setOnClickListener(this::onMpvConf);
        mBinding.mpvGpuNext.setOnClickListener(this::setMpvGpuNext);
        mBinding.mpvVulkan.setOnClickListener(this::setMpvVulkan);
        mBinding.render.setOnClickListener(this::setRender);
        mBinding.scale.setOnClickListener(this::setScale);
        mBinding.background.setOnClickListener(this::onBackground);
        mBinding.buffer.setOnClickListener(this::onBuffer);
        mBinding.preload.setOnClickListener(this::onPreloadSetting);
        mBinding.ua.setOnClickListener(this::onUa);
        mBinding.netInfo.setOnClickListener(this::setNetInfo);
        mBinding.mediaInfo.setOnClickListener(this::setMediaInfo);
        mBinding.autoPlay.setOnClickListener(this::setAutoPlay);
    }

    private void setVisible() {
        boolean exo = PlayerSetting.isExo();
        boolean vulkan = !exo && MpvUtil.isVulkanSupported();
        if (PlayerSetting.isBackgroundPiP()) PlayerSetting.putBackground(1);
        mBinding.mpvConf.setVisibility(exo ? View.GONE : View.VISIBLE);
        mBinding.mpvVulkan.setVisibility(vulkan ? View.VISIBLE : View.GONE);
        mBinding.mpvGpuNext.setVisibility(exo ? View.GONE : View.VISIBLE);
        mBinding.adblock.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.libass.setVisibility(exo ? View.VISIBLE : View.GONE);
        mBinding.buffer.setVisibility(exo ? View.VISIBLE : View.GONE);
    }

    private void setEngine(View view) {
        int index = (PlayerSetting.getEngine() + 1) % engine.length;
        PlayerSetting.putEngine(index);
        setPlaybackModeText();
        setVisible();
    }

    private void onMpvConf(View view) {
        MpvConfDialog.show(this);
    }

    private void setMpvGpuNext(View view) {
        PlayerSetting.putMpvGpuNext(!PlayerSetting.isMpvGpuNext());
        mBinding.mpvGpuNextText.setText(Setting.getSwitch(PlayerSetting.isMpvGpuNext()));
    }

    private void setMpvVulkan(View view) {
        PlayerSetting.putMpvVulkan(!PlayerSetting.isMpvVulkan());
        mBinding.mpvVulkanText.setText(Setting.getSwitch(PlayerSetting.isMpvVulkan()));
    }

    private void setRender(View view) {
        int index = (PlayerSetting.getRender() + 1) % render.length;
        PlayerSetting.putRender(index);
        setPlaybackModeText();
    }

    private void setPlaybackModeText() {
        engine = ResUtil.getStringArray(R.array.select_engine);
        render = ResUtil.getStringArray(R.array.select_render);
        mBinding.engineText.setText(engine[PlayerSetting.getEngine()]);
        mBinding.renderText.setText(render[PlayerSetting.getRender()]);
    }

    private void setScale(View view) {
        int index = (PlayerSetting.getScale() + 1) % scale.length;
        mBinding.scaleText.setText(scale[index]);
        PlayerSetting.putScale(index);
    }

    private void onBuffer(View view) {
        BufferDialog.show(this);
    }

    @Override
    public void setBuffer(int times) {
        mBinding.bufferText.setText(String.valueOf(times));
        PlayerSetting.putBuffer(times);
    }

    private void onBackground(View view) {
        PlayerSetting.putBackground(PlayerSetting.isBackgroundOn() ? 0 : 1);
        mBinding.backgroundText.setText(Setting.getSwitch(PlayerSetting.isBackgroundOn()));
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(Setting.getSwitch(Setting.isAdblock()));
    }

    private void setLibass(View view) {
        PlayerSetting.putLibass(!PlayerSetting.isLibass());
        mBinding.libassText.setText(Setting.getSwitch(PlayerSetting.isLibass()));
    }

    private void setNetInfo(View view) {
        PlayerSetting.putNetInfo(!PlayerSetting.isNetInfo());
        mBinding.netInfoText.setText(Setting.getSwitch(PlayerSetting.isNetInfo()));
    }

    private void setMediaInfo(View view) {
        PlayerSetting.putMediaInfo(!PlayerSetting.isMediaInfo());
        mBinding.mediaInfoText.setText(Setting.getSwitch(PlayerSetting.isMediaInfo()));
        setAutoPlayText();
    }

    private void setAutoPlayText() {
        if (!PlayerSetting.isAutoPlayTv()) mBinding.autoPlayText.setText(Setting.getSwitch(false));
        else mBinding.autoPlayText.setText(Setting.getSwitch(true) + " · " + ResUtil.getString(R.string.setting_auto_play_delay_seconds, PlayerSetting.getAutoPlayDelay()));
    }

    /** 自动播放设置（电视默认开）：弹窗内开关 + 0~10 秒延时（−/+ 键逐秒调），改动即时保存。 */
    private void setAutoPlay(View view) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_auto_play_tv, null);
        Switch switchView = dialogView.findViewById(R.id.autoPlaySwitch);
        TextView delayValue = dialogView.findViewById(R.id.autoPlayDelayValue);
        switchView.setChecked(PlayerSetting.isAutoPlayTv());
        delayValue.setText(ResUtil.getString(R.string.setting_auto_play_delay_seconds, PlayerSetting.getAutoPlayDelay()));
        switchView.setOnCheckedChangeListener((button, checked) -> {
            PlayerSetting.putAutoPlay(checked);
            setAutoPlayText();
        });
        dialogView.findViewById(R.id.autoPlayDelayMinus).setOnClickListener(v -> {
            int delay = Math.max(0, PlayerSetting.getAutoPlayDelay() - 1);
            PlayerSetting.putAutoPlayDelay(delay);
            PlayerSetting.putAutoPlay(switchView.isChecked());
            delayValue.setText(ResUtil.getString(R.string.setting_auto_play_delay_seconds, delay));
            setAutoPlayText();
        });
        dialogView.findViewById(R.id.autoPlayDelayPlus).setOnClickListener(v -> {
            int delay = Math.min(10, PlayerSetting.getAutoPlayDelay() + 1);
            PlayerSetting.putAutoPlayDelay(delay);
            PlayerSetting.putAutoPlay(switchView.isChecked());
            delayValue.setText(ResUtil.getString(R.string.setting_auto_play_delay_seconds, delay));
            setAutoPlayText();
        });
        new AlertDialog.Builder(this).setTitle(R.string.setting_auto_play).setView(dialogView).setNegativeButton(R.string.dialog_negative, null).show();
        switchView.requestFocus();
    }

    private void onPreloadSetting(View view) {
        SettingPreloadActivity.start(this);
    }

    private void onDecodeSetting(View view) {
        SettingDecodeActivity.start(this);
    }

    private void onUa(View view) {
        UaDialog.show(this);
    }

    @Override
    public void setUa(String ua) {
        Setting.putUa(ua);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) setPlaybackModeText();
    }
}
