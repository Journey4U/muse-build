package com.muse.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.muse.android.tv.api.loader.BaseLoader;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.ProxyExecGuard;
// import com.muse.hook.Hook; // disabled
import com.github.catvod.Init;
import com.github.catvod.crawler.DebugLogStore;
import com.google.gson.Gson;

import java.util.concurrent.atomic.AtomicBoolean;

import is.xyz.mpv.MPVLib;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private final AtomicBoolean prewarmed = new AtomicBoolean();
    private Object hook; // Hook disabled

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Object hook) { // Hook disabled
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLogStore.setAvailable(BuildConfig.DEBUG_LOG_AVAILABLE);
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) Setting.logDebugEnvironment("restore");
        Notify.createChannel();
        post(() -> ProxyExecGuard.get().start(getFilesDir()), 1200);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public PackageManager getPackageManager() {
        return getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
        if (prewarmed.compareAndSet(false, true)) post(this::prewarm, 1000);
    }

    private void prewarm() {
        Thread thread = new Thread(() -> {
            try {
                MPVLib.ensureLoaded(this);
            } catch (Throwable ignored) {
            }
            try {
                BaseLoader.get();
            } catch (Throwable ignored) {
            }
        }, "prewarm");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}