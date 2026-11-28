package com.muse.android.tv.ui.adapter;

import android.app.Activity;
import android.view.LayoutInflater;
import android.widget.TextView;

import androidx.core.text.PrecomputedTextCompat;
import androidx.core.widget.TextViewCompat;

import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.databinding.AdapterSiteBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 源名文字排版缓存（应用级，同一份配置内首页/搜索/设置三处共用）。
 * 配置加载/重载后整体清空，立刻在后台线程把全部源名按电视胶囊的字号/字体预算一遍（顺带把 emoji 等字体在后台加载好）；
 * 绑定时直接取用。后台没算完时打开弹窗：缺的项当场照常排版，不阻塞、不出错。
 * 排版参数取自胶囊里的文字控件（字号、字体、语言、断行方式）；参数不一致时自动退回普通 setText。
 * 注意：PrecomputedText 只依赖字体参数，不依赖宽度；胶囊宽度由布局定死。
 */
public final class SiteTextCache {

    private static final Map<String, PrecomputedTextCompat> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger GEN = new AtomicInteger();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "site-text-cache");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static volatile PrecomputedTextCompat.Params sParams;
    private static volatile boolean sMismatch;

    private SiteTextCache() {
    }

    private static String key(Site site) {
        return site.getKey() + "|" + site.getName();
    }

    /** 整体清空并在后台重建（配置加载/重载、主题或密度变化后由共享网格调用）。 */
    public static void rebuild(Activity activity) {
        final int gen = GEN.incrementAndGet();
        CACHE.clear();
        sMismatch = false;
        final PrecomputedTextCompat.Params params;
        try {
            AdapterSiteBinding b = AdapterSiteBinding.inflate(LayoutInflater.from(activity));
            params = TextViewCompat.getTextMetricsParams(b.text);
        } catch (Throwable e) {
            sParams = null;
            return;
        }
        sParams = params;
        final List<Site> sites = new ArrayList<>(VodConfig.get().getSites());
        EXEC.execute(() -> {
            for (Site site : sites) {
                if (GEN.get() != gen) return;
                if (site == null || site.isHide()) continue;
                String k = key(site);
                if (CACHE.containsKey(k)) continue;
                try {
                    CACHE.put(k, PrecomputedTextCompat.create(site.getName(), params));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 绑定源名：有缓存直接用，没有（后台还没算到/参数不符）就当场照常设置。 */
    public static void bind(TextView view, Site item) {
        PrecomputedTextCompat cached = sParams == null || sMismatch ? null : CACHE.get(key(item));
        if (cached != null) {
            try {
                TextViewCompat.setPrecomputedText(view, cached);
                return;
            } catch (Throwable e) {
                // 参数与控件不符（理论上不会）：本轮不再尝试，免得每格都抛异常；下次重建再试。
                sMismatch = true;
            }
        }
        view.setText(item.getName());
    }
}
