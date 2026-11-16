package com.muse.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.BuildConfig;
import com.muse.android.tv.R;
import com.muse.android.tv.Updater;
import com.muse.android.tv.api.config.LiveConfig;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.config.WallConfig;
import com.muse.android.tv.bean.Config;
import com.muse.android.tv.bean.Live;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.FragmentSettingBinding;
import com.muse.android.tv.db.BackupManager;
import com.muse.android.tv.event.ConfigEvent;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.impl.ConfigListener;
import com.muse.android.tv.impl.LiveListener;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.activity.HomeActivity;
import com.muse.android.tv.ui.base.BaseFragment;
import com.muse.android.tv.ui.dialog.ConfigDialog;
import com.muse.android.tv.ui.dialog.RuntimeLogPanel;
import com.muse.android.tv.ui.dialog.HistoryDialog;
import com.muse.android.tv.ui.dialog.LiveDialog;
import com.muse.android.tv.ui.dialog.RestoreDialog;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.ui.dialog.LocalSourceDialog;
import com.muse.android.tv.ui.dialog.WebSourceDialog;
import com.muse.android.tv.ui.dialog.SiteDialog;
import com.muse.android.tv.utils.AppReset;
import com.muse.android.tv.utils.FileUtil;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingFragment extends BaseFragment implements ConfigListener, SiteListener, LiveListener {

    private FragmentSettingBinding mBinding;
    private String[] size;

    public static SettingFragment newInstance() {
        return new SettingFragment();
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        setLogDebugText();
        setOtherText();
        setCacheText();
        setLocalSourceText();
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
        mBinding.netInfoText.setText(Setting.getSwitch(PlayerSetting.isNetInfo()));
        setMediaInfoText();
        setAutoPlayText();
        setSizeText();
    }

    /** 一级显示的图片尺寸文字：切回设置页时也要刷新，否则从主页视图切换钮改的尺寸同步不过来。 */
    private void setSizeText() {
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
    }

    private void setMediaInfoText() {
        mBinding.mediaInfoText.setText(Setting.getSwitch(PlayerSetting.isMediaInfo()));
    }

    private void setAutoPlayText() {
        if (!PlayerSetting.isAutoPlay()) mBinding.autoplayText.setText(Setting.getSwitch(false));
        else mBinding.autoplayText.setText(Setting.getSwitch(true) + " · " + ResUtil.getString(R.string.setting_auto_play_delay_seconds, PlayerSetting.getAutoPlayDelay()));
    }

    /** 设置页本地源行副标题：显示 Hub 状态（未启用 / 未设置文件夹 / 上次扫描摘要）。 */
    private void setLocalSourceText() {
        mBinding.localSourceText.setText(LocalSourceHub.getStatusText());
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.localSource.setOnClickListener(v -> LocalSourceDialog.show(this, this::setLocalSourceText));
        mBinding.webSource.setOnClickListener(v -> WebSourceDialog.show(getContext()));
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.logDebug.setOnClickListener(this::setLogDebug);
        mBinding.logRealtime.setOnClickListener(this::onLogRealtime);
        mBinding.resetApp.setOnClickListener(this::onResetApp);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.netInfo.setOnClickListener(this::setNetInfo);
        mBinding.mediaInfo.setOnClickListener(this::setMediaInfo);
        mBinding.autoplay.setOnClickListener(this::setAutoPlay);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(requireActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().search().change().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        getRoot().change(2);
    }

    private void onDanmaku(View view) {
        getRoot().change(3);
    }

    private void onVersion(View view) {
        Updater.create().force().start(requireActivity());
    }

    private void setWallDefault(View view) {
        Setting.putWall(Setting.getWall() == 4 ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
    }

    private void setNetInfo(View view) {
        PlayerSetting.putNetInfo(!PlayerSetting.isNetInfo());
        mBinding.netInfoText.setText(Setting.getSwitch(PlayerSetting.isNetInfo()));
    }

    private void setMediaInfo(View view) {
        PlayerSetting.putMediaInfo(!PlayerSetting.isMediaInfo());
        mBinding.mediaInfoText.setText(Setting.getSwitch(PlayerSetting.isMediaInfo()));
    }

    /** 自动播放设置：弹窗内开关 + 0~10 秒滑动条（步进 1 秒），改动即时保存。 */
    private void setAutoPlay(View view) {
        View dialogView = LayoutInflater.from(requireActivity()).inflate(R.layout.dialog_auto_play, null);
        MaterialSwitch switchView = dialogView.findViewById(R.id.autoPlaySwitch);
        Slider slider = dialogView.findViewById(R.id.autoPlayDelay);
        TextView delayText = dialogView.findViewById(R.id.autoPlayDelayText);
        switchView.setChecked(PlayerSetting.isAutoPlay());
        slider.setValue(PlayerSetting.getAutoPlayDelay());
        slider.setEnabled(switchView.isChecked());
        delayText.setText(ResUtil.getString(R.string.setting_auto_play_delay_seconds, PlayerSetting.getAutoPlayDelay()));
        switchView.setOnCheckedChangeListener((button, checked) -> {
            PlayerSetting.putAutoPlay(checked);
            slider.setEnabled(checked);
            setAutoPlayText();
        });
        slider.addOnChangeListener((s, value, fromUser) -> {
            delayText.setText(ResUtil.getString(R.string.setting_auto_play_delay_seconds, Math.round(value)));
            if (fromUser) {
                PlayerSetting.putAutoPlayDelay(Math.round(value));
                setAutoPlayText();
            }
        });
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_auto_play).setView(dialogView).setNegativeButton(R.string.dialog_negative, null).show();
    }

    private void setSize(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_size).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(size, PlayerSetting.getSize(), (dialog, which) -> {
            mBinding.sizeText.setText(size[which]);
            PlayerSetting.putSize(which);
            RefreshEvent.size();
            dialog.dismiss();
        }).show();
    }

    private void setDoh(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_doh).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(getDohList(), getDohIndex(), (dialog, which) -> {
            setDoh(VodConfig.get().getDoh().get(which));
            dialog.dismiss();
        }).show();
    }

    private void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> BackupManager.backup(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        }));
    }

    private void setLogDebugText() {
        mBinding.logDebugText.setText(Setting.isDebugLog() ? R.string.setting_enabled : R.string.setting_disabled);
    }

    private void setLogDebug(View view) {
        Setting.putDebugLog(!Setting.isDebugLog());
        setLogDebugText();
    }

    private void onLogRealtime(View view) {
        RuntimeLogPanel.show(requireActivity());
    }

    private void onResetApp(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_reset_app).setMessage(R.string.reset_app_message).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.setting_reset, (dialog, which) -> AppReset.reset(requireActivity())).show();
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().show(requireActivity(), new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) return;
        setCacheText();
        setLogDebugText();
        setSizeText();
        setMediaInfoText();
        setAutoPlayText();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }
}

