package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.BuildConfig;
import com.muse.android.tv.R;
import com.muse.android.tv.Updater;
import com.muse.android.tv.api.config.LiveConfig;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.config.WallConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.bean.Config;
import com.muse.android.tv.bean.Live;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.ActivitySettingBinding;
import com.muse.android.tv.db.AppDatabase;
import com.muse.android.tv.db.BackupManager;
import com.muse.android.tv.event.ConfigEvent;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.impl.ConfigListener;
import com.muse.android.tv.impl.LiveListener;
import com.muse.android.tv.impl.SiteListener;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.dialog.ConfigDialog;
import com.muse.android.tv.ui.dialog.DohDialog;
import com.muse.android.tv.ui.dialog.HistoryDialog;
import com.muse.android.tv.ui.dialog.LiveDialog;
import com.muse.android.tv.ui.dialog.LocalSourceDialog;
import com.muse.android.tv.ui.dialog.WebSourceDialog;
import com.muse.android.tv.ui.dialog.RestoreDialog;
import com.muse.android.tv.ui.dialog.SiteDialog;
import com.muse.android.tv.utils.FileUtil;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity implements ConfigListener, SiteListener, LiveListener, DohDialog.Listener {

    private ActivitySettingBinding mBinding;
    private String[] size;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.vod.requestFocus();
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        setCacheText();
        setOtherText();
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        setLocalSourceText();
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
        setSearchCapText();
        setWebViewportText();
    }

    // push tv v32：浏览器缩放（网页视口宽度 1920/1600/1280，默认 1600）；遥控器 OK 键循环切换，照「每站搜索条数」项做法
    private void setWebViewportText() {
        mBinding.webViewportText.setText(String.valueOf(Setting.getWebViewport()));
    }

    private void setWebViewport(View view) {
        Setting.putWebViewportIndex((Setting.getWebViewportIndex() + 1) % Setting.WEB_VIEWPORT_VALUES.length);
        setWebViewportText();
    }

    // push v47 起；push v50 改为每站搜索页数（1/2/3/4/5 页/不限，默认 2 页）；遥控器 OK 键循环切换，照「图片尺寸」项做法
    private String[] getSearchCapItems() {
        int[] values = Setting.SEARCH_PAGES_VALUES;
        String[] items = new String[values.length];
        for (int i = 0; i < values.length; i++) items[i] = values[i] <= 0 ? ResUtil.getString(R.string.setting_search_cap_unlimited) : ResUtil.getString(R.string.setting_search_cap_page, values[i]);
        return items;
    }

    private void setSearchCapText() {
        mBinding.searchCapText.setText(getSearchCapItems()[Setting.getSearchCapIndex()]);
    }

    private void setSearchCap(View view) {
        Setting.putSearchCapIndex((Setting.getSearchCapIndex() + 1) % Setting.SEARCH_PAGES_VALUES.length);
        setSearchCapText();
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
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.localSource.setOnClickListener(this::onLocalSource);
        mBinding.webSource.setOnClickListener(v -> WebSourceDialog.show(this));
        mBinding.webSourceBatch.setOnClickListener(v -> WebSourceDialog.showBatch(this));
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.searchCap.setOnClickListener(this::setSearchCap);
        mBinding.webViewport.setOnClickListener(this::setWebViewport);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.resetApp.setOnClickListener(this::onResetApp);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
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
                Notify.progress(getActivity());
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
        SiteDialog.create().action().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.create().action().show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onDanmaku(View view) {
        SettingDanmakuActivity.start(this);
    }

    private void onVersion(View view) {
        // Update check closed (Liu, 2026-10-24): no toast, no request.
        // Restore the line below to re-enable it later.
        // Updater.create().force().start(this);
    }

    private void onResetApp(View view) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_reset_app)
                .setMessage(R.string.reset_app_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> resetApp())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void resetApp() {
        Prefers.getPrefers().edit().clear().commit();
        AppDatabase.get().close();
        deleteDatabase(AppDatabase.NAME);
        clearDir(new File(getDataDir(), "databases"));
        clearDir(getCacheDir());
        clearDir(getFilesDir());
        clearDir(getExternalCacheDir());
        clearDir(getExternalFilesDir(null));
        Intent intent = new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (manager != null) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 300, pending);
            } catch (Exception e) {
                manager.set(AlarmManager.RTC, System.currentTimeMillis() + 300, pending);
            }
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void clearDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) deleteRecursive(file);
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
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

    private void setSize(View view) {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        PlayerSetting.putSize(index);
        RefreshEvent.size();
    }

    private void setDoh(View view) {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
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

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().callback(new Callback() {
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
        }).show(this));
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


    /** 设置页本地源行副标题：显示 Hub 状态（未启用 / 未设置文件夹 / 上次扫描摘要）。 */
    private void setLocalSourceText() {
        mBinding.localSourceText.setText(LocalSourceHub.getStatusText());
    }

    private void onLocalSource(View view) {
        LocalSourceDialog.show(this, this::setLocalSourceText);
    }
}
