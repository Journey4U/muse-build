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

    /** 浅色壁纸时把浅色一套颜色叠到 Activity 主题上（须在 super.onCreate 之前调用）。 */
    public static boolean apply(Activity activity) {
        boolean light = isLight();
        if (light) activity.getTheme().applyStyle(R.style.ThemeOverlay_Muse_Light, true);
        return light;
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
