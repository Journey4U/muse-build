package com.muse.android.tv.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.github.catvod.crawler.DebugLogStore;
import com.muse.android.tv.impl.PermissionCallback;
import com.permissionx.guolindev.PermissionX;

import java.util.function.Consumer;

public class PermissionUtil {

    private static final String TAG = "Permission";

    public static void requestAudio(FragmentActivity activity, Consumer<Boolean> callback) {
        if (isGranted(activity, Manifest.permission.RECORD_AUDIO)) callback.accept(true);
        else post(activity, () -> PermissionX.init(activity).permissions(Manifest.permission.RECORD_AUDIO).request(new PermissionCallback(callback)));
    }

    public static void requestFile(FragmentActivity activity, Consumer<Boolean> callback) {
        if (isGranted(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) callback.accept(true);
        else post(activity, () -> PermissionX.init(activity).permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).request(new PermissionCallback(callback)));
    }

    public static void requestFile(Fragment fragment, Consumer<Boolean> callback) {
        FragmentActivity activity = fragment.requireActivity();
        if (isGranted(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) callback.accept(true);
        else post(fragment, () -> PermissionX.init(fragment).permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).request(new PermissionCallback(callback)));
    }

    public static void requestNotify(FragmentActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (isGranted(activity, PermissionX.permission.POST_NOTIFICATIONS)) return;
        post(activity, () -> PermissionX.init(activity).permissions(PermissionX.permission.POST_NOTIFICATIONS).request(new PermissionCallback()));
    }

    /**
     * v67 权限统一：存储权限只认两面旗子——WRITE_EXTERNAL_STORAGE 已授予，
     * 或 API 30+ 已是全部文件访问（Environment.isExternalStorageManager()）。
     * 只检查，不申请 MANAGE（不走 MANAGE 申请流程）。
     */
    public static boolean hasStoragePermission(Activity activity) {
        if (activity == null) return false;
        boolean writeGranted = isGranted(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        boolean allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
        logGate(writeGranted, allFiles);
        return writeGranted || allFiles;
    }

    /** v67：FragmentActivity 口径与 Activity 一致（只做类型转发）。 */
    public static boolean hasStoragePermission(FragmentActivity activity) {
        return hasStoragePermission((Activity) activity);
    }

    /**
     * v67 权限统一入口：有权限直接 onGranted，不弹系统框；没有才走 PermissionX
     * 申请 WRITE_EXTERNAL_STORAGE；拒绝调 onDenied（调用方弹提示，不静默失败）。
     */
    public static void ensureStoragePermission(FragmentActivity activity, Runnable onGranted, Runnable onDenied) {
        if (activity == null) {
            if (onDenied != null) onDenied.run();
            return;
        }
        if (hasStoragePermission(activity)) {
            if (onGranted != null) onGranted.run();
            return;
        }
        post(activity, () -> PermissionX.init(activity).permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).request(new PermissionCallback(granted -> {
            if (Boolean.TRUE.equals(granted)) {
                if (onGranted != null) onGranted.run();
            } else if (onDenied != null) {
                onDenied.run();
            }
        })));
    }

    /** v67：Fragment 口径与 FragmentActivity 一致。 */
    public static void ensureStoragePermission(Fragment fragment, Runnable onGranted, Runnable onDenied) {
        FragmentActivity activity = fragment.requireActivity();
        if (hasStoragePermission(activity)) {
            if (onGranted != null) onGranted.run();
            return;
        }
        post(fragment, () -> PermissionX.init(fragment).permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).request(new PermissionCallback(granted -> {
            if (Boolean.TRUE.equals(granted)) {
                if (onGranted != null) onGranted.run();
            } else if (onDenied != null) {
                onDenied.run();
            }
        })));
    }

    /** v67：跳应用详情页（用户去系统设置里手动开存储权限）。 */
    public static void openAppSettings(Activity activity) {
        if (activity == null) return;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "打开应用详情页失败：" + e);
        }
    }

    /** v67：每次 gate 处记诊断日志（WRITE 结果 + MANAGE 结果 + 外部存储可写探针）。 */
    private static void logGate(boolean writeGranted, boolean allFiles) {
        boolean canWrite = false;
        try {
            canWrite = Environment.getExternalStorageDirectory().canWrite();
        } catch (Throwable ignored) {
        }
        DebugLogStore.add(TAG, "存储权限门：WRITE=" + writeGranted + "，MANAGE=" + allFiles + "，外部存储可写=" + canWrite);
    }

    private static boolean isGranted(FragmentActivity activity, String permission) {
        return PermissionX.isGranted(activity, permission);
    }

    private static boolean isGranted(Activity activity, String permission) {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static void post(FragmentActivity activity, Runnable runnable) {
        post(activity, null, runnable);
    }

    private static void post(Fragment fragment, Runnable runnable) {
        post(fragment.requireActivity(), fragment, runnable);
    }

    private static void post(FragmentActivity activity, Fragment fragment, Runnable runnable) {
        activity.getWindow().getDecorView().post(() -> {
            if (isActive(activity, fragment)) runnable.run();
        });
    }

    private static boolean isActive(FragmentActivity activity, Fragment fragment) {
        return !activity.isFinishing() && !activity.isDestroyed() && (fragment == null || fragment.isAdded());
    }
}
