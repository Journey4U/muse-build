package com.muse.android.tv.ui.novel;

import android.content.Context;
import android.content.SharedPreferences;

import com.muse.android.tv.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 小说阅读进度与书架标记（SharedPreferences JSON 存储，不建 Room 表）。
 * 记录以 bookId 为键：蜘蛛书 = keepKey（siteKey@@@vodId@@@cid），本地书 = "local:" + 文件路径。
 * records 兼作「最近阅读」数据源——只有阅读器会写它，故天然只有小说。
 */
public final class NovelProgress {

    private static final String PREFS = "novel_progress";
    private static final String KEY_RECORDS = "records";
    private static final String KEY_SHELF = "shelf_keys";

    private NovelProgress() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject readRecords() {
        try {
            return new JSONObject(prefs().getString(KEY_RECORDS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void save(String bookId, String title, String pic, String site, int chapter, String chapterName, int percent) {
        if (bookId == null || bookId.isEmpty()) return;
        try {
            JSONObject all = readRecords();
            JSONObject item = new JSONObject();
            item.put("title", title == null ? "" : title);
            item.put("pic", pic == null ? "" : pic);
            item.put("site", site == null ? "" : site);
            item.put("chapter", chapter);
            item.put("chapterName", chapterName == null ? "" : chapterName);
            item.put("percent", Math.max(0, Math.min(100, percent)));
            item.put("time", System.currentTimeMillis());
            all.put(bookId, item);
            prefs().edit().putString(KEY_RECORDS, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 返回带 bookId 字段的记录副本；无记录返回 null。 */
    public static JSONObject find(String bookId) {
        if (bookId == null || bookId.isEmpty()) return null;
        try {
            JSONObject item = readRecords().optJSONObject(bookId);
            if (item == null) return null;
            JSONObject copy = new JSONObject(item.toString());
            copy.put("bookId", bookId);
            return copy;
        } catch (Exception e) {
            return null;
        }
    }

    /** 全部记录，按最近阅读时间倒序；每条带 bookId 字段。 */
    public static List<JSONObject> all() {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONObject all = readRecords();
            JSONArray names = all.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i);
                    JSONObject item = all.optJSONObject(key);
                    if (item == null) continue;
                    JSONObject copy = new JSONObject(item.toString());
                    copy.put("bookId", key);
                    list.add(copy);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(list, (a, b) -> Long.compare(b.optLong("time"), a.optLong("time")));
        return list;
    }

    public static void remove(String bookId) {
        if (bookId == null || bookId.isEmpty()) return;
        try {
            JSONObject all = readRecords();
            all.remove(bookId);
            prefs().edit().putString(KEY_RECORDS, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void markShelf(String keepKey, boolean shelved) {
        if (keepKey == null || keepKey.isEmpty()) return;
        Set<String> keys = shelfKeys();
        if (shelved) keys.add(keepKey);
        else keys.remove(keepKey);
        JSONArray array = new JSONArray();
        for (String key : keys) array.put(key);
        prefs().edit().putString(KEY_SHELF, array.toString()).apply();
    }

    public static boolean isShelved(String keepKey) {
        return keepKey != null && shelfKeys().contains(keepKey);
    }

    public static Set<String> shelfKeys() {
        Set<String> keys = new HashSet<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_SHELF, "[]"));
            for (int i = 0; i < array.length(); i++) keys.add(array.optString(i));
        } catch (Exception ignored) {
        }
        return keys;
    }
}
