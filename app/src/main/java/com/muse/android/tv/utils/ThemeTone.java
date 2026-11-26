package com.muse.android.tv.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.TypedValue;

import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.muse.android.tv.R;
import com.muse.android.tv.setting.Setting;

import java.io.File;

/**
 * 换装第 2 步：界面深浅跟壁纸走。
 * 内置壁纸 1/2（羊皮纸）= 浅，3（墨棕）= 深；配置 wallpaper 字段的自定义壁纸按快照平均亮度判，
 * 判一次按文件（路径+修改时间+大小）缓存，换壁纸文件即重判。
 * 浅色时给 Activity 主题叠 ThemeOverlay.Muse.Light；深色不叠 = 原样。
 */
public final class ThemeTone {

    /** 壁纸切换后由 CustomWallView 通知当前页面复核深浅。 */
    public interface Host {
        void checkTone();
    }

    private static final int TYPE_RES = 0;
    private static final int LIGHT_THRESHOLD = 150;
    private static final String KEY_FILE = "tone_wall_key";
    private static final String KEY_LIGHT = "tone_wall_light";
    private static String cacheKey = "";
    private static boolean cacheLight;

    private ThemeTone() {
    }

    public static boolean isLight() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (type == TYPE_RES && wall >= 1 && wall <= 3) return wall != 3;
        File file = Path.wallCache();
        if (!file.exists()) return false;
        String key = file.getAbsolutePath() + "@" + file.lastModified() + ":" + file.length();
        if (key.equals(cacheKey)) return cacheLight;
        if (key.equals(Prefers.getString(KEY_FILE))) {
            cacheLight = Prefers.getBoolean(KEY_LIGHT);
            cacheKey = key;
            return cacheLight;
        }
        int luma = averageLuma(file);
        if (luma < 0) return false;
        cacheLight = luma >= LIGHT_THRESHOLD;
        cacheKey = key;
        Prefers.put(KEY_FILE, key);
        Prefers.put(KEY_LIGHT, cacheLight);
        return cacheLight;
    }

    /** 主题 2 护眼绿：内置壁纸 2 号（豆沙绿）。自定义壁纸不算，仍按亮度判深浅。 */
    public static boolean isGreen() {
        return Setting.getWallType() == TYPE_RES && Setting.getWall() == 2;
    }

    /** 当前壁纸对应的一套颜色：0=深色原样，1=浅色羊皮纸，2=浅色护眼绿。 */
    public static int tone() {
        if (!isLight()) return 0;
        return isGreen() ? 2 : 1;
    }

    /** 浅色壁纸时把浅色一套颜色叠到 Activity 主题上（须在 super.onCreate 之前调用）；护眼绿再叠绿色一套。 */
    public static boolean apply(Activity activity) {
        int tone = tone();
        reapply(activity, tone);
        return tone != 0;
    }

    /** 按创建时记下的 tone 再叠一次（电视 AppCompat 重刷主题后用）。 */
    public static void reapply(Activity activity, int tone) {
        if (tone == 0) return;
        activity.getTheme().applyStyle(R.style.ThemeOverlay_Muse_Light, true);
        if (tone == 2) activity.getTheme().applyStyle(R.style.ThemeOverlay_Muse_Green, true);
    }

    /** 所在页面主题里的正文色（羊皮纸墨褐 / 护眼绿深墨绿 / 深色白），供 Java 动态着色。 */
    public static int inkColor(Context context) {
        TypedValue value = new TypedValue();
        if (context != null && context.getTheme().resolveAttribute(R.attr.museToneText, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
            if (value.resourceId != 0) return androidx.core.content.ContextCompat.getColor(context, value.resourceId);
        }
        return 0xFF4A3527;
    }

    /** 按所在页面（含弹窗）实际套用的主题判深浅，供 Java 里动态着色用。 */
    public static boolean isLight(Context context) {
        if (context == null) return false;
        TypedValue value = new TypedValue();
        return context.getTheme().resolveAttribute(R.attr.museToneLight, value, true) && value.type == TypedValue.TYPE_INT_BOOLEAN && value.data != 0;
    }

    /** 弹窗/浮层：恢复原配色（弹窗有自己的不透明底，不跟壁纸走）。 */
    public static void reset(Context context) {
        if (context != null) context.getTheme().applyStyle(R.style.ThemeOverlay_Muse_Reset, true);
    }

    /** 壁纸刷新后通知所在页面复核。 */
    public static void notifyChanged(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Host)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        if (context instanceof Host) ((Host) context).checkTone();
    }

    private static int averageLuma(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap == null) return -1;
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int[] px = new int[w * h];
            bitmap.getPixels(px, 0, w, 0, 0, w, h);
            bitmap.recycle();
            long sum = 0;
            for (int c : px) sum += (299L * ((c >> 16) & 0xFF) + 587L * ((c >> 8) & 0xFF) + 114L * (c & 0xFF)) / 1000;
            return px.length == 0 ? -1 : (int) (sum / px.length);
        } catch (Throwable e) {
            return -1;
        }
    }
}
