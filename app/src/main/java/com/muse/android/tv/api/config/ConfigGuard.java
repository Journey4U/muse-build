package com.muse.android.tv.api.config;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.muse.android.tv.R;
import com.muse.android.tv.bean.Config;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.Notify;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * push v48（Liu 2026-11-24 23:01 定）：长时间不操作后回到壳子，界面还在、内存里的点播配置却空了
 * （VodConfig 是进程级单例，HomeActivity.onDestroy 会 clear() 它，clear 不发任何刷新事件，界面不知道）——
 * 点「进站主页」弹「该站点没有主页」、换源列表打不开，手动重新加载才恢复。
 * 这里在回前台、进站主页、浏览器、打开源列表时先查：配置为空且有已保存的配置地址 → 自动走一次与启动时
 * 相同的加载入口（VodConfig.get().init().load），加载成功后再继续原动作。不改加载本身，不碰搜索/播放/去广告。
 */
public final class ConfigGuard {

    /** 两次自动重载的最短间隔：加载失败（如断网）时不每次回前台都重试刷屏；成功后仍为空时也不循环重载。 */
    private static final long MIN_INTERVAL = 30_000;
    /** 已有加载在途且发起不到这么久 → 视为正在加载，不另起（启动时 initConfig 刚发起的那次就属于这种）。 */
    private static final long IN_FLIGHT = 20_000;

    private static final List<Runnable> PENDING = new ArrayList<>();
    private static boolean sReloading;
    private static long sLastAuto;
    /** 下一批（Liu 2026-11-26 06:27 批）：本进程里点播配置曾经有过源。新装/新进程头一次加载前为 false——
     * 那时配置空是正常的（用户刚在设置里填地址或选文件、首次加载还没完），不当「配置被清空」处理，不弹提示、不自动重载。 */
    private static volatile boolean sEverLoaded;

    private ConfigGuard() {
    }

    /** 下一批：VodConfig 解析出非空源表时调用（setSites），记下「本进程曾加载成功过」。 */
    public static void markLoaded() {
        sEverLoaded = true;
    }

    /** 内存里的点播配置有源 = 可用。 */
    public static boolean ready() {
        boolean ok = !VodConfig.get().getSites().isEmpty();
        if (ok) sEverLoaded = true;
        return ok;
    }

    /**
     * 配置可用 → 返回 false，调用方照原逻辑继续。
     * 配置为空、有已保存的配置地址 → 自动重载（主线程调用），成功后在主线程依次执行排队的 then，返回 true（调用方这次先不往下做）。
     * 自动重载已在途 → then 排队，返回 true；别处发起的加载在途 → 不另起，有 then 时提示「正在加载配置」并返回 true。
     * 没有配置地址，或 30 秒内刚自动重载过仍为空 → 返回 false，调用方照原逻辑（原提示）。
     * 本进程从没加载成功过（新装头一次加载）→ 返回 false，不弹提示、不重载。
     *
     * @param reason 记日志用：哪个入口触发的
     * @param then   重载成功后要继续的原动作（可为 null，如回前台）；调用方须在里面自己判页面是否还活着
     */
    public static synchronized boolean reloadIfEmpty(String reason, @Nullable Runnable then) {
        if (ready()) return false;
        // 本进程从没加载成功过（新装首次加载、首次填地址/选文件）：完全不插手，调用方照原逻辑。
        if (!sEverLoaded) return false;
        // 被别处的加载顶掉（BaseConfig 取消/换任务时不回调）→ 自动重载的在途标记超时作废，不让入口永远卡在「正在加载」。
        if (sReloading && System.currentTimeMillis() - sLastAuto >= IN_FLIGHT) {
            finish();
            sLastAuto = 0;
        }
        if (sReloading) {
            if (then != null) {
                PENDING.add(then);
                Notify.show(R.string.config_auto_reloading);
            }
            return true;
        }
        if (VodConfig.get().isLoadingWithin(IN_FLIGHT)) {
            if (then != null) Notify.show(R.string.config_auto_reloading);
            return then != null;
        }
        Config config = Config.vod();
        if (TextUtils.isEmpty(config.getUrl())) return false;
        long now = System.currentTimeMillis();
        if (now - sLastAuto < MIN_INTERVAL) return false;
        sLastAuto = now;
        sReloading = true;
        if (then != null) PENDING.add(then);
        DebugLogStore.add(LogCategory.FILE, "config", "点播配置为空（" + reason + "），自动重新加载 url=" + config.getUrl(), false);
        Notify.show(R.string.config_auto_reloading);
        VodConfig.get().init().load(new Callback() {
            @Override
            public void success() {
                List<Runnable> items = finish();
                DebugLogStore.add(LogCategory.FILE, "config", "自动重新加载完成（" + reason + "） 源数=" + VodConfig.get().getSites().size(), false);
                if (!ready()) return;
                for (Runnable item : items) item.run();
            }

            @Override
            public void error(String msg) {
                finish();
                DebugLogStore.addError(LogCategory.FILE, "config", "自动重新加载失败（" + reason + "）：" + msg);
                if (!TextUtils.isEmpty(msg)) Notify.show(msg);
            }
        });
        return true;
    }

    private static synchronized List<Runnable> finish() {
        sReloading = false;
        List<Runnable> items = new ArrayList<>(PENDING);
        PENDING.clear();
        return items;
    }
}
