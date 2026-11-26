package com.muse.android.tv.setting;

import android.os.Build;

import com.muse.android.tv.R;
import com.muse.android.tv.utils.ResUtil;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

public class Setting {

    private static final int MIN_WALL = 0;
    private static final int MAX_WALL = 3;
    private static final int MIN_WALL_TYPE = 0;
    private static final int MAX_WALL_TYPE = 2;
    private static final int MIN_SITE_MODE = 0;
    private static final int MAX_SITE_MODE = 1;
    private static final int MIN_SYNC_MODE = 0;
    private static final int MAX_SYNC_MODE = 2;

    public static String getSwitch(boolean value) {
        return ResUtil.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static String getLastWebHost() {
        return Prefers.getString("last_web_host");
    }

    public static void putLastWebHost(String host) {
        Prefers.put("last_web_host", host);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static int getWall() {
        return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", Math.clamp(wall, MIN_WALL, MAX_WALL));
    }

    public static int getWallType() {
        return Math.clamp(Prefers.getInt("wall_type", 0), MIN_WALL_TYPE, MAX_WALL_TYPE);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", Math.clamp(type, MIN_WALL_TYPE, MAX_WALL_TYPE));
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static int getSiteMode() {
        return Math.clamp(Prefers.getInt("site_mode"), MIN_SITE_MODE, MAX_SITE_MODE);
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", Math.clamp(mode, MIN_SITE_MODE, MAX_SITE_MODE));
    }

    public static int getSyncMode() {
        return Math.clamp(Prefers.getInt("sync_mode"), MIN_SYNC_MODE, MAX_SYNC_MODE);
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", Math.clamp(mode, MIN_SYNC_MODE, MAX_SYNC_MODE));
    }

    /** push v50：每站搜索页数档位 0=1页、1=2页（默认）、2=3页、3=4页、4=5页、5=不限（0 表示不限）。
     *  新存储键 search_pages；首次读取时从 v47 旧键 search_cap（0=20、1=40、2=80、3=不限 条）迁移：
     *  旧「不限」→ 不限，其余（20/40/80 条）→ 默认 2 页；迁移后写入新键，旧键不再读。 */
    public static final int[] SEARCH_PAGES_VALUES = {1, 2, 3, 4, 5, 0};

    public static int getSearchCapIndex() {
        int index = Prefers.getInt("search_pages", -1);
        if (index < 0) {
            index = Prefers.getInt("search_cap", -1) == 3 ? SEARCH_PAGES_VALUES.length - 1 : 1;
            Prefers.put("search_pages", index);
        }
        return Math.clamp(index, 0, SEARCH_PAGES_VALUES.length - 1);
    }

    public static void putSearchCapIndex(int index) {
        Prefers.put("search_pages", Math.clamp(index, 0, SEARCH_PAGES_VALUES.length - 1));
    }

    public static int getSearchPages() {
        return SEARCH_PAGES_VALUES[getSearchCapIndex()];
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    public static boolean isDebugLog() {
        return DebugLogStore.isEnabled();
    }

    public static void putDebugLog(boolean debugLog) {
        DebugLogStore.setEnabled(debugLog);
        if (debugLog) logDebugEnvironment("enable");
    }

    public static void logDebugEnvironment(String reason) {
        try {
            SpiderDebug.log("env", "reason=%s android=%s sdk=%s manufacturer=%s brand=%s model=%s device=%s product=%s supportedAbis=%s",
                    reason,
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT,
                    Build.MANUFACTURER,
                    Build.BRAND,
                    Build.MODEL,
                    Build.DEVICE,
                    Build.PRODUCT,
                    String.join(",", Build.SUPPORTED_ABIS));
        } catch (Throwable ignored) {
        }
    }
}
