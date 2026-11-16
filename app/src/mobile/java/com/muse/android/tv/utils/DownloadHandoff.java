package com.muse.android.tv.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.Map;

/**
 * 影片下载交接:把解析后的真实播放地址连同文件名、请求头交给 1DM/1DM+ 下载。
 * 按 1DM 官方第三方 intent 规范:ACTION_VIEW + 组件 &lt;包名&gt;/idm.internet.download.manager.Downloader,
 * data=下载链接,extras 为 extra_filename / extra_referer / extra_useragent / extra_cookies /
 * extra_headers(Bundle)。本 App 不内置任何下载引擎(守 PCDN 红线);未装 1DM 时兜底复制链接到剪贴板。
 * 目标 SDK 28 不受 Android 11+ 包可见性限制,查包可直接用 PackageManager,无需 manifest queries。
 */
public final class DownloadHandoff {

    private static final String DOWNLOADER_CLASS = "idm.internet.download.manager.Downloader";
    private static final String[] PACKAGES = {
            "idm.internet.download.manager.plus",
            "idm.internet.download.manager",
            "idm.internet.download.manager.adm.lite"};

    private DownloadHandoff() {
    }

    public static void start(Activity activity, String url, String fileName, Map<String, String> headers) {
        String pkg = findInstalled(activity);
        if (pkg == null) {
            copy(activity, url);
            Notify.show("未安装 1DM 下载器，已复制下载链接");
            return;
        }
        try {
            activity.startActivity(buildIntent(pkg, url, fileName, headers));
            Notify.show("已交给 1DM 下载：" + fileName);
        } catch (Exception e) {
            copy(activity, url);
            Notify.show("打开 1DM 失败，已复制下载链接");
        }
    }

    private static String findInstalled(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    private static Intent buildIntent(String pkg, String url, String fileName, Map<String, String> headers) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setComponent(new ComponentName(pkg, DOWNLOADER_CLASS));
        intent.setData(Uri.parse(url));
        if (!TextUtils.isEmpty(fileName)) intent.putExtra("extra_filename", fileName);
        if (headers != null) {
            Bundle bundle = new Bundle();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || TextUtils.isEmpty(value)) continue;
                if (key.equalsIgnoreCase("Referer")) intent.putExtra("extra_referer", value);
                else if (key.equalsIgnoreCase("User-Agent")) intent.putExtra("extra_useragent", value);
                else if (key.equalsIgnoreCase("Cookie")) intent.putExtra("extra_cookies", value);
                else bundle.putString(key, value);
            }
            if (!bundle.isEmpty()) intent.putExtra("extra_headers", bundle);
        }
        return intent;
    }

    private static void copy(Context context, String url) {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("download", url));
    }
}
