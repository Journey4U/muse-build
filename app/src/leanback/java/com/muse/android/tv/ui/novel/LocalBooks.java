package com.muse.android.tv.ui.novel;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.muse.android.tv.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 本地 TXT 书：导入解析（编码识别 + 章节索引）与存储（SharedPreferences JSON）。
 * 章节索引用解码后文本的 char 偏移；全文解码结果做单条静态缓存，读章时按偏移切分。
 * 所有 IO 方法须在后台线程调用。
 */
public final class LocalBooks {

    private static final String PREFS = "local_books";
    private static final String KEY_BOOKS = "books";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int FALLBACK_CHUNK = 8000;
    private static final Pattern HEADING = Pattern.compile(
            "^\\s{0,4}(第[0-9零一二三四五六七八九十百千万两〇]+[章节卷回部篇集].{0,28}|序章.{0,20}|楔子.{0,20}|前言.{0,20}|引子.{0,20}|后记.{0,20}|尾声.{0,20}|番外.{0,28}|[Cc]hapter\\s+\\d+.{0,28})$");

    private static String cachePath = "";
    private static String cacheText = null;

    private LocalBooks() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<JSONObject> books() {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_BOOKS, "[]"));
            for (int i = 0; i < array.length(); i++) list.add(array.optJSONObject(i));
        } catch (Exception ignored) {
        }
        return list;
    }

    public static JSONObject find(String path) {
        for (JSONObject book : books()) if (path.equals(book.optString("path"))) return book;
        return null;
    }

    public static void remove(String path) {
        try {
            JSONArray kept = new JSONArray();
            for (JSONObject book : books()) if (!path.equals(book.optString("path"))) kept.put(book);
            prefs().edit().putString(KEY_BOOKS, kept.toString()).apply();
            if (path.equals(cachePath)) {
                cachePath = "";
                cacheText = null;
            }
        } catch (Exception ignored) {
        }
    }

    /** 导入并解析一本 TXT；成功返回章节数，失败返回 -1。 */
    public static int importBook(File file) {
        try {
            if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) return -1;
            String charset = detectCharset(file);
            String text = decode(file, charset);
            if (TextUtils.isEmpty(text)) return -1;
            JSONArray chapters = indexChapters(text);
            String path = file.getAbsolutePath();
            JSONObject book = new JSONObject();
            book.put("path", path);
            book.put("title", titleOf(file));
            book.put("charset", charset);
            book.put("size", file.length());
            book.put("chapters", chapters);
            book.put("addedAt", System.currentTimeMillis());
            JSONArray kept = new JSONArray();
            for (JSONObject old : books()) if (!path.equals(old.optString("path"))) kept.put(old);
            kept.put(book);
            prefs().edit().putString(KEY_BOOKS, kept.toString()).apply();
            cachePath = path;
            cacheText = text;
            return chapters.length();
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 阅读器用的章节项列表：章名$local://起始char:长度。 */
    public static ArrayList<String> chapterItems(String path) {
        ArrayList<String> items = new ArrayList<>();
        JSONObject book = find(path);
        if (book == null) return items;
        JSONArray chapters = book.optJSONArray("chapters");
        if (chapters == null) return items;
        for (int i = 0; i < chapters.length(); i++) {
            JSONObject chapter = chapters.optJSONObject(i);
            if (chapter == null) continue;
            items.add(chapter.optString("n") + "$local://" + chapter.optInt("s") + ":" + chapter.optInt("l"));
        }
        return items;
    }

    public static String readChapter(String path, int start, int length) {
        try {
            String text = textOf(path);
            if (text == null) return "";
            int from = Math.max(0, Math.min(start, text.length()));
            int to = Math.max(from, Math.min(start + length, text.length()));
            return text.substring(from, to).trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String textOf(String path) throws Exception {
        if (path.equals(cachePath) && cacheText != null) return cacheText;
        JSONObject book = find(path);
        if (book == null) return null;
        String text = decode(new File(path), book.optString("charset", "UTF-8"));
        cachePath = path;
        cacheText = text;
        return text;
    }

    private static String titleOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static byte[] readBytes(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return bytes;
    }

    private static String detectCharset(File file) throws Exception {
        byte[] bytes = readBytes(file);
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) return "UTF-8";
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) return "UTF-16LE";
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) return "UTF-16BE";
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return "UTF-8";
        } catch (CharacterCodingException e) {
            return "GBK";
        }
    }

    private static String decode(File file, String charset) throws Exception {
        byte[] bytes = readBytes(file);
        String text = new String(bytes, Charset.forName(charset));
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static JSONArray indexChapters(String text) throws Exception {
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int length = text.length();
        int pos = 0;
        while (pos < length) {
            int nl = text.indexOf('\n', pos);
            int end = nl < 0 ? length : nl;
            String line = text.substring(pos, end);
            String trimmed = line.trim();
            if (trimmed.length() <= 42 && HEADING.matcher(trimmed).matches()) {
                starts.add(pos);
                names.add(trimmed);
            }
            if (nl < 0) break;
            pos = nl + 1;
        }
        JSONArray chapters = new JSONArray();
        if (starts.size() >= 3) {
            int first = starts.get(0);
            if (first > 0 && text.substring(0, first).trim().length() > 50) {
                putChapter(chapters, "前言", 0, first);
            }
            for (int i = 0; i < starts.size(); i++) {
                int start = starts.get(i);
                int end = i + 1 < starts.size() ? starts.get(i + 1) : length;
                if (i == 0 && chapters.length() > 0) start = first;
                putChapter(chapters, names.get(i), start, end - start);
            }
            return chapters;
        }
        if (length <= FALLBACK_CHUNK) {
            putChapter(chapters, "正文", 0, length);
            return chapters;
        }
        int start = 0;
        int index = 1;
        while (start < length) {
            int end = Math.min(length, start + FALLBACK_CHUNK);
            if (end < length) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start) end = nl;
            }
            putChapter(chapters, "第" + index++ + "节", start, end - start);
            start = end;
        }
        return chapters;
    }

    private static void putChapter(JSONArray chapters, String name, int start, int len) throws Exception {
        JSONObject chapter = new JSONObject();
        chapter.put("n", name);
        chapter.put("s", start);
        chapter.put("l", len);
        chapters.put(chapter);
    }
}
