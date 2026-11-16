package com.muse.android.tv.utils;

import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * r62-②：「源文件搬家的上一次分类」记录（供「回退到上一次分类」用）。
 *
 * <p>为什么需要单独记：源文件的「分类」= 它所在文件夹名，搬完家后旧位置信息当场就丢了，
 * 没有任何地方留着「它原来在哪」。{@code LocalSourceHub} 里也确实没有这类记录
 * （全仓 {@code last_category|lastCategory|上次分类} 零命中）。</p>
 *
 * <p>存法：{@code Prefers} 里一个 JSON 对象 {@code {新路径 -> 原分类目录}}，键是**绝对路径**。
 * 读写都是一次内存操作（Prefers 有内存缓存），只在搬家/回退这类用户动作里发生，开销可忽略。</p>
 *
 * <p>写入时机：任何一次「源文件搬家成功」后（手动移动、移进失效列表、回退本身）。
 * 回退成功后会把当前路径记成「上一次分类 = 搬家目的地」，于是来回点「回退」可以互相切换。</p>
 */
public final class MoveHistory {

    private static final String KEY = "local_source_move_history";
    /** 上限：超了从最早的开始丢（避免 Prefers 里堆成一大坨）。 */
    private static final int MAX = 800;

    private MoveHistory() {
    }

    /** 记一条：{@code path}（搬家后的文件绝对路径）上一次在 {@code prevDir}（分类目录绝对路径）。 */
    public static synchronized void record(String path, String prevDir) {
        String key = norm(path);
        String value = norm(prevDir);
        if (key.isEmpty() || value.isEmpty()) return;
        JSONObject all = load();
        try {
            all.put(key, value);
        } catch (Throwable e) {
            return;
        }
        trim(all);
        Prefers.put(KEY, all.toString());
    }

    /** 查：{@code path} 上一次所在的分类目录；没有记录返回 null。 */
    public static synchronized String prevOf(String path) {
        String key = norm(path);
        if (key.isEmpty()) return null;
        String value = load().optString(key, "");
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /** 删掉某条记录（回退后由调用方按需清）。 */
    public static synchronized void forget(String path) {
        String key = norm(path);
        if (key.isEmpty()) return;
        JSONObject all = load();
        if (!all.has(key)) return;
        all.remove(key);
        Prefers.put(KEY, all.toString());
    }

    /** 丢掉的记录条数（调试用）。 */
    public static synchronized int size() {
        return load().length();
    }

    private static JSONObject load() {
        try {
            return new JSONObject(Prefers.getString(KEY, "{}"));
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    private static void trim(JSONObject all) {
        try {
            if (all.length() <= MAX) return;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = all.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            for (int i = 0; i < keys.size() - MAX; i++) all.remove(keys.get(i));
        } catch (Throwable ignored) {
        }
    }

    private static String norm(String path) {
        if (path == null) return "";
        String value = path.trim();
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
