package com.muse.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.Updater;
import com.muse.android.tv.api.config.ConfigGuard;
import com.muse.android.tv.api.config.LiveConfig;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.config.WallConfig;
import com.muse.android.tv.bean.Config;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.ActivityHomeBinding;
import com.muse.android.tv.db.BackupManager;
import com.muse.android.tv.event.ConfigEvent;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.event.ServerEvent;
import com.muse.android.tv.event.StateEvent;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.player.extractor.Source;
import com.muse.android.tv.receiver.ShortcutReceiver;
import com.muse.android.tv.server.Server;
import com.muse.android.tv.service.PlaybackService;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.base.BaseFragment;
import com.muse.android.tv.ui.comic.LocalComics;
import com.muse.android.tv.ui.custom.CustomNavBar;
import com.muse.android.tv.ui.custom.FragmentStateManager;
import com.muse.android.tv.ui.fragment.SettingDanmakuFragment;
import com.muse.android.tv.ui.fragment.SettingDecodeFragment;
import com.muse.android.tv.ui.fragment.SettingFragment;
import com.muse.android.tv.ui.fragment.SettingPlayerFragment;
import com.muse.android.tv.ui.fragment.SettingPreloadFragment;
import com.muse.android.tv.ui.fragment.VodFragment;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PermissionUtil;
import com.muse.android.tv.utils.UrlUtil;
import com.muse.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements CustomNavBar.OnItemSelectedListener {

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private int orientation;
    private long mExitTime;

    /** 纯网页源进站主页（Liu 2026-11-23 方案 A）：已自动进过站的源 key——同一次启动/同一次选中只自动进一次，返回后不再弹。 */
    private static String sWebAutoKey;
    /** 浏览器顶栏「切源」：回主页后打开切源对话框的待办标记（onResume 里消费）。 */
    private static boolean sPendingSiteDialog;

    /** 这个源这次还没自动进过站就记下并返回 true；已进过返回 false（防返回后又被弹进去）。 */
    public static boolean takeWebAutoOpen(String key) {
        if (key == null || key.equals(sWebAutoKey)) return false;
        sWebAutoKey = key;
        return true;
    }

    /** 用户在切源列表里重新选了源：清掉记号，选中纯网页源时照样自动进站。 */
    public static void resetWebAutoOpen() {
        sWebAutoKey = null;
    }

    /** 浏览器顶栏「切源」点击：回到主页后打开切源对话框。 */
    public static void requestSiteDialog() {
        sPendingSiteDialog = true;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        orientation = getResources().getConfiguration().orientation;
        // 纯网页源进站主页：新启动（非重建）才重置「已自动进站」记号——App 启动时当前源是纯网页源会自动进站一次。
        if (savedInstanceState == null) {
            sWebAutoKey = null;
            sPendingSiteDialog = false;
        }
        initNavigation();
        mBinding.navigation.setOnItemSelectedListener(this);
        PermissionUtil.requestNotify(this);
        initFragment(savedInstanceState);
        Updater.create().start(this);
        initConfig();
    }

    @Override
    protected void initEvent() {
        mBinding.navigation.getItemView(R.id.live).setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.getUri(intent, uri -> loadLive(UrlUtil.toLocalUrl(uri)));
        } else {
            FileChooser.getUri(intent, uri -> VideoActivity.file(this, uri));
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 3 -> SettingDanmakuFragment.newInstance();
            case 4 -> SettingPreloadFragment.newInstance();
            case 5 -> SettingDecodeFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        if (isFinishing() || isDestroyed()) return;
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    /** 自定义底栏的格子（顺序同原菜单，漫画紧跟书架；全部初始隐藏，由 setNavigation 按配置点亮）。 */
    private void initNavigation() {
        mBinding.navigation.addItem(R.id.vod, R.drawable.ic_nav_vod, R.string.nav_vod);
        mBinding.navigation.addItem(R.id.live, R.drawable.ic_nav_live, R.string.nav_live);
        mBinding.navigation.addItem(R.id.browser, R.drawable.ic_nav_browser, R.string.nav_browser);
        mBinding.navigation.addItem(R.id.bookshelf, R.drawable.ic_action_bookshelf, R.string.shelf_title);
        mBinding.navigation.addItem(R.id.comic, R.drawable.ic_nav_comic, R.string.nav_comic);
        mBinding.navigation.addItem(R.id.setting, R.drawable.ic_nav_setting, R.string.nav_setting);
    }

    private void setNavigation() {
        mBinding.navigation.setItemVisible(R.id.vod, true);
        mBinding.navigation.setItemVisible(R.id.setting, true);
        mBinding.navigation.setItemVisible(R.id.live, LiveConfig.hasUrl());
        mBinding.navigation.setItemVisible(R.id.bookshelf, true);
        refreshComicItem();
        refreshBrowserItem();
    }

    /** 漫画格：书架里导入了本地漫画才显示，删完自动隐藏（与书架「本地漫画」栏同一份数据 LocalComics）。 */
    private void refreshComicItem() {
        mBinding.navigation.setItemVisible(R.id.comic, LocalComics.hasComics());
    }

    /** 浏览器入口只在当前站点能推出主页时亮；推不出就藏起来。 */
    private void refreshBrowserItem() {
        Site site = VodConfig.get().getHome();
        String url = site == null ? null : VideoActivity.siteHomePageUrl(site);
        mBinding.navigation.setItemVisible(R.id.browser, !TextUtils.isEmpty(url));
    }

    /** 进当前站点的主页（网页播放那套主页推导）；推不出就提示，不进空白页。 */
    private boolean openBrowser() {
        // push v48：配置被清空（长时间不操作后）→ 先自动重载，加载完再进主页，不再只弹「没有主页」。
        if (ConfigGuard.reloadIfEmpty("浏览器", () -> {
            if (isFinishing() || isDestroyed()) return;
            takeWebAutoOpen(VodConfig.get().getHome().getKey());
            openBrowser();
        })) return false;
        Site site = VodConfig.get().getHome();
        String url = site == null ? null : VideoActivity.siteHomePageUrl(site);
        if (TextUtils.isEmpty(url)) {
            Notify.show(R.string.nav_browser_empty);
            return false;
        }
        WebPlayActivity.startHome(this, site.getName(), url, site.getKey(), site.getClick());
        return false;
    }

    /** 浏览器顶栏「切源」回来：切回点播页，由 VodFragment 打开切源对话框（与点标题切源同一个对话框）。 */
    @Override
    protected void onResume() {
        super.onResume();
        // push v48：长时间不操作回来，内存里的点播配置已被清空而界面还在 → 自动重载（加载完照常刷新主页）。
        ConfigGuard.reloadIfEmpty("回到前台", null);
        // 书架里导入/删除漫画后回到主页：刷新漫画格显隐（底栏已点亮时才刷，未加载配置前不单独露出）
        if (mBinding.navigation.isItemVisible(R.id.vod)) refreshComicItem();
        if (!sPendingSiteDialog) return;
        sPendingSiteDialog = false;
        change(0);
        App.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            BaseFragment fragment = mManager.getFragment(0);
            if (fragment instanceof VodFragment) ((VodFragment) fragment).openSiteDialog();
        }, 100);
    }

    private boolean openBookshelf() {
        BookshelfActivity.start(this);
        return false;
    }

    private boolean openComics() {
        ComicShelfActivity.start(this);
        return false;
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        mExitTime = 0;
        if (position < 2) mBinding.navigation.setSelectedItemId(position == 0 ? R.id.vod : R.id.setting);
        else mManager.change(position);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
        if (event.getType() == RefreshEvent.Type.HOME) refreshBrowserItem();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    @Override
    public boolean onItemSelected(int id) {
        mExitTime = 0;
        if (id == R.id.setting) return mManager.change(1);
        if (id == R.id.vod) return mManager.change(0);
        if (id == R.id.live) return openLive();
        if (id == R.id.browser) return openBrowser();
        if (id == R.id.bookshelf) return openBookshelf();
        if (id == R.id.comic) return openComics();
        return false;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        App.post(() -> checkOrientation(newConfig), 100);
    }

    private void checkOrientation(Configuration newConfig) {
        if (orientation != newConfig.orientation) {
            orientation = newConfig.orientation;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (!mBinding.navigation.isItemVisible(R.id.vod)) {
            mExitTime = 0;
            setNavigation();
        } else if (mManager.isVisible(4) || mManager.isVisible(5)) {
            change(2);
        } else if (mManager.isVisible(3) || mManager.isVisible(2)) {
            change(1);
        } else if (mManager.isVisible(1)) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) {
                Util.moveToBackground(this);
            } else if (System.currentTimeMillis() - mExitTime > 2000) {
                // #101: exit requires two back gestures - the first only shows the
                // prompt and arms a 2s window; any in-app navigation (change())
                // disarms it, so a stray/edge gesture cannot quit the app directly.
                mExitTime = System.currentTimeMillis();
                Notify.show(R.string.exit_confirm);
            } else {
                super.onBackInvoked();
            }
        } else {
            mExitTime = 0;
        }
    }

    @Override
    protected void onDestroy() {
        LiveConfig.get().clear();
        VodConfig.get().clear();
        BackupManager.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
