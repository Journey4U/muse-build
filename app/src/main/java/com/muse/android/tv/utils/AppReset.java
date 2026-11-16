package com.muse.android.tv.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.muse.android.tv.db.AppDatabase;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * True factory reset of the app: wipes every piece of app-private data
 * (SharedPreferences, databases, cache, files, code cache and the
 * app-specific external directories) and restarts into HomeActivity, so
 * the next start behaves like a fresh install. The relaunch goes through
 * the :phoenix-process RestartActivity: an AlarmManager PendingIntent
 * armed by the dying process never relaunches the app on the user's
 * device, so a surviving process performs the start instead — and kills
 * this process itself once its cover is on screen, which keeps the
 * hand-off seamless (see RestartActivity). The delayed kill below is
 * only a fallback in case the trampoline never comes up.
 *
 * Only directories owned by this app are touched; user folders on shared
 * storage (e.g. /storage/emulated/0/123) are never deleted.
 */
public class AppReset {

    public static void reset(Context context) {
        closeDatabase(context);
        clearInternal(context);
        clearExternal(context);
        launchPhoenix(context);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 1500);
    }

    private static void closeDatabase(Context context) {
        try {
            AppDatabase.get().close();
        } catch (Exception ignored) {
        }
        try {
            context.deleteDatabase(AppDatabase.NAME);
        } catch (Exception ignored) {
        }
    }

    private static void launchPhoenix(Context context) {
        try {
            Intent restart = new Intent(context, RestartActivity.class);
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(restart);
            // No transition animation: the trampoline's wallpaper cover
            // simply replaces the settings UI.
            if (context instanceof Activity) ((Activity) context).overridePendingTransition(0, 0);
        } catch (Exception ignored) {
        }
    }

    private static void clearInternal(Context app) {
        // Well-known data locations first.
        deleteContents(app.getCacheDir());
        try {
            deleteContents(app.getCodeCacheDir());
        } catch (Exception ignored) {
        }
        deleteContents(app.getFilesDir());
        try {
            deleteContents(app.getNoBackupFilesDir());
        } catch (Exception ignored) {
        }
        // Then sweep every remaining sub-directory of the data dir
        // (shared_prefs, databases, app_webview, ...) except the native lib link.
        try {
            File dataDir = new File(app.getApplicationInfo().dataDir);
            File[] children = dataDir.listFiles();
            if (children == null) return;
            for (File child : children) {
                if ("lib".equals(child.getName())) continue;
                if (child.isDirectory()) deleteContents(child);
                else delete(child);
            }
        } catch (Exception ignored) {
        }
    }

    private static void clearExternal(Context app) {
        Set<File> roots = new HashSet<>();
        try {
            File files = app.getExternalFilesDir(null);
            if (files != null && files.getParentFile() != null) roots.add(files.getParentFile()); // Android/data/<pkg>
        } catch (Exception ignored) {
        }
        try {
            File cache = app.getExternalCacheDir();
            if (cache != null && cache.getParentFile() != null) roots.add(cache.getParentFile()); // same Android/data/<pkg>
        } catch (Exception ignored) {
        }
        try {
            roots.add(app.getObbDir()); // Android/obb/<pkg>
        } catch (Exception ignored) {
        }
        try {
            File[] media = app.getExternalMediaDirs();
            if (media != null) for (File dir : media) if (dir != null) roots.add(dir); // Android/media/<pkg>
        } catch (Exception ignored) {
        }
        String pkg = app.getPackageName();
        for (File root : roots) {
            // Safety: only ever delete paths that belong to this package.
            if (root != null && root.getAbsolutePath().contains(pkg)) deleteRecursively(root);
        }
    }

    private static void deleteContents(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) deleteRecursively(child);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        delete(file);
    }

    private static void delete(File file) {
        try {
            file.delete();
        } catch (Exception ignored) {
        }
    }
}
