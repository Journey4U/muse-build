package com.muse.android.tv.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.muse.android.tv.R;
import com.muse.android.tv.ui.activity.HomeActivity;

/**
 * Trampoline activity in its own :phoenix process (declared in the main
 * manifest with a translucent theme). AppReset starts it right before
 * the main process dies: this process survives the death, kills any
 * leftover process of the app, then relaunches HomeActivity with a
 * plain startActivity from a live process — the ProcessPhoenix pattern.
 * Used because an AlarmManager PendingIntent armed by the dying process
 * never relaunches the app on the user's device (v13 trace: alarm armed
 * with exact=true, process never started again; the original mobile
 * reset had been broken this way since its first version).
 *
 * The hand-off is designed to be invisible (a "seamless reset"): the
 * only content is the same built-in wallpaper the home screen shows
 * after a reset (R.drawable.wallpaper_1), with no icon or text, and
 * the translucent theme lets the still alive settings screen show
 * through while this process starts — never black, never the launcher.
 * The old processes are killed only after this cover has been laid
 * out, and both activity transitions run without animation, so the
 * user sees the settings UI dissolve into the wallpaper and the home
 * UI appear on top of that same wallpaper.
 */
public class RestartActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImageView cover = new ImageView(this);
        cover.setImageResource(R.drawable.wallpaper_1);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(cover);
        // Once the cover is laid out it hides whatever is underneath;
        // only then is it safe to kill the old processes. Killing them
        // earlier can expose the launcher between the two processes.
        cover.postDelayed(new Runnable() {
            @Override
            public void run() {
                killOtherProcesses();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        launchHome();
                    }
                }, 350);
            }
        }, 100);
    }

    private void launchHome() {
        try {
            Intent home = new Intent(this, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            overridePendingTransition(0, 0);
        } catch (Exception ignored) {
        }
        finish();
        overridePendingTransition(0, 0);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void killOtherProcesses() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            int myPid = android.os.Process.myPid();
            String pkg = getPackageName();
            for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (info.pid != myPid && info.processName != null && info.processName.startsWith(pkg)) {
                    android.os.Process.killProcess(info.pid);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
