package com.muse.android.tv.ui.comic;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.ui.novel.NovelProgress;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 本地漫画（手机/电视共用一份数据，换装第 1 步，Liu 2026-11-26 定）。
 * 规则：一个文件夹 = 一部，子文件夹 = 一话（文件夹里的 cbz/zip 也各算一话），
 * 直接放在作品文件夹根下的图片算第一话；单个 cbz/zip = 一部，包内按目录分话。
 * 图片按文件名数字自然排序。只认 jpg/jpeg/png/webp/gif/bmp；不做 rar/pdf。
 * 书架「本地漫画」栏、手机底栏「漫画」页、电视功能区「漫画」按钮都只读这里的
 * comics() / hasComics()，导入/删除也只走这里，两处显隐与打开逻辑天然一致。
 * 图片地址：文件夹里的图是 file://绝对路径；压缩包条目是 comiczip://包路径!!/条目名，
 * 由 resolve() 解到缓存文件后交 Glide（在线漫画的 http 地址不经这里）。
 * 所有 IO 方法须在后台线程调用。
 */
public final class LocalComics {

    public static final String FILE_PREFIX = "file://";
    public static final String ZIP_PREFIX = "comiczip://";
    private static final String ZIP_SEP = "!!/";
    private static final String PREFS = "local_comics";
    private static final String KEY_COMICS = "comics";
    private static final String KEY_PROGRESS = "progress";
    private static final Map<String, Charset> ZIP_CHARSETS = new HashMap<>();

    private LocalComics() {
    }

    public static final class Chapter {
        public final String name;
        public final ArrayList<String> images;

        Chapter(String name, ArrayList<String> images) {
            this.name = name;
            this.images = images;
        }

        public String picsUrl() {
            return "pics://" + TextUtils.join("&&", images);
        }
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ===== 作品列表 =====

    public static List<JSONObject> comics() {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_COMICS, "[]"));
            for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null) list.add(array.optJSONObject(i));
        } catch (Exception ignored) {
        }
        return list;
    }

    public static boolean hasComics() {
        return !comics().isEmpty();
    }

    public static JSONObject find(String path) {
        if (path == null) return null;
        for (JSONObject comic : comics()) if (path.equals(comic.optString("path"))) return comic;
        return null;
    }

    /** 只删书架记录与进度，不动源文件。 */
    public static void remove(String path) {
        try {
            JSONArray kept = new JSONArray();
            for (JSONObject comic : comics()) if (!path.equals(comic.optString("path"))) kept.put(comic);
            prefs().edit().putString(KEY_COMICS, kept.toString()).apply();
        } catch (Exception ignored) {
        }
        removeProgress(path);
    }

    public static boolean isArchive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cbz") || lower.endsWith(".zip");
    }

    public static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /** 导入一部（文件夹或 cbz/zip）；成功返回话数，没有图片返回 0，失败返回 -1。已在书架的同路径作品刷新信息。 */
    public static int importComic(File file) {
        try {
            if (file == null || !file.exists()) return -1;
            if (file.isFile() && !isArchive(file.getName())) return -1;
            String path = file.getAbsolutePath();
            List<Chapter> chapters = chapters(path);
            if (chapters.isEmpty()) return 0;
            JSONObject comic = new JSONObject();
            comic.put("path", path);
            comic.put("title", titleOf(file));
            comic.put("cover", chapters.get(0).images.get(0));
            comic.put("chapters", chapters.size());
            comic.put("time", System.currentTimeMillis());
            JSONArray array = new JSONArray();
            array.put(comic);
            for (JSONObject old : comics()) if (!path.equals(old.optString("path"))) array.put(old);
            prefs().edit().putString(KEY_COMICS, array.toString()).apply();
            return chapters.size();
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String titleOf(File file) {
        String name = file.getName();
        if (file.isFile()) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        return name;
    }

    // ===== 分话 =====

    public static List<Chapter> chapters(String path) {
        File file = new File(path);
        if (file.isDirectory()) return dirChapters(file);
        if (file.isFile() && isArchive(file.getName())) return zipChapters(file, titleOf(file));
        return new ArrayList<>();
    }

    private static List<Chapter> dirChapters(File dir) {
        List<Chapter> result = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return result;
        List<File> rootImages = new ArrayList<>();
        List<File> parts = new ArrayList<>();
        for (File child : children) {
            if (child.getName().startsWith(".")) continue;
            if (child.isDirectory()) parts.add(child);
            else if (child.isFile() && isImage(child.getName())) rootImages.add(child);
            else if (child.isFile() && isArchive(child.getName())) parts.add(child);
        }
        if (!rootImages.isEmpty()) result.add(new Chapter(dir.getName(), fileUris(rootImages)));
        parts.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
        for (File part : parts) {
            if (part.isDirectory()) {
                List<File> images = new ArrayList<>();
                collectImages(part, images, 0);
                if (!images.isEmpty()) result.add(new Chapter(part.getName(), fileUris(images)));
            } else {
                ArrayList<String> images = new ArrayList<>();
                for (Chapter chapter : zipChapters(part, titleOf(part))) images.addAll(chapter.images);
                if (!images.isEmpty()) result.add(new Chapter(titleOf(part), images));
            }
        }
        return result;
    }

    /** 话文件夹里的图片（含再下一层子文件夹，按相对路径自然排序）。 */
    private static void collectImages(File dir, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return;
        List<File> list = new ArrayList<>();
        for (File f : files) if (!f.getName().startsWith(".")) list.add(f);
        list.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
        for (File f : list) {
            if (f.isFile() && isImage(f.getName())) out.add(f);
        }
        for (File f : list) {
            if (f.isDirectory()) collectImages(f, out, depth + 1);
        }
    }

    private static ArrayList<String> fileUris(List<File> files) {
        files.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
        ArrayList<String> uris = new ArrayList<>();
        for (File f : files) uris.add(FILE_PREFIX + f.getAbsolutePath());
        return uris;
    }

    private static List<Chapter> zipChapters(File archive, String title) {
        List<Chapter> result = new ArrayList<>();
        ZipFile zip = null;
        try {
            zip = openZip(archive);
            Map<String, List<String>> groups = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !isImage(name)) continue;
                if (name.startsWith("__MACOSX/") || baseName(name).startsWith(".")) continue;
                int slash = name.lastIndexOf('/');
                String group = slash < 0 ? "" : name.substring(0, slash);
                List<String> list = groups.get(group);
                if (list == null) groups.put(group, list = new ArrayList<>());
                list.add(name);
            }
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.sort(LocalComics::naturalCompare);
            for (String key : keys) {
                List<String> names = groups.get(key);
                names.sort((a, b) -> naturalCompare(baseName(a), baseName(b)));
                ArrayList<String> uris = new ArrayList<>();
                for (String n : names) uris.add(ZIP_PREFIX + archive.getAbsolutePath() + ZIP_SEP + n);
                String chapterName = key.isEmpty() || keys.size() == 1 ? title : baseName(key);
                result.add(new Chapter(chapterName, uris));
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (zip != null) zip.close();
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** 中文名压缩包常见 GBK 编码且不带 UTF-8 标记：UTF-8 读条目名失败就改 GBK，并按包记住。 */
    private static ZipFile openZip(File archive) throws Exception {
        String key = archive.getAbsolutePath();
        Charset known;
        synchronized (ZIP_CHARSETS) {
            known = ZIP_CHARSETS.get(key);
        }
        if (known != null) return new ZipFile(archive, known);
        ZipFile zip = new ZipFile(archive, StandardCharsets.UTF_8);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) entries.nextElement().getName();
            known = StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            zip.close();
            known = Charset.forName("GBK");
            zip = new ZipFile(archive, known);
        }
        synchronized (ZIP_CHARSETS) {
            ZIP_CHARSETS.put(key, known);
        }
        return zip;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** 自然排序：数字段按数值比（第2话 < 第10话），其余按不区分大小写的字符比。 */
    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    // ===== 进度（按部记话 + 页） =====
    // 用小说现成的 NovelProgress 表（bookId="local-manga:"+路径，与小说的 "local:"+路径不撞键），
    // 漫画就进书架「最近阅读」栏；percent 只能记本话百分比，精确页码另存本表的 progress 字段。
    // 两处一起写、一起删，互为同一条进度记录。

    public static final String BOOK_ID_PREFIX = "local-manga:";

    public static String bookId(String path) {
        return BOOK_ID_PREFIX + path;
    }

    private static JSONObject progressAll() {
        try {
            return new JSONObject(prefs().getString(KEY_PROGRESS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 精确进度（话 + 页，两个字段：小说表的 percent 是本话百分比，page 是本话第几页 0 起）。 */
    public static JSONObject progress(String path) {
        if (path == null || path.isEmpty()) return null;
        JSONObject record = NovelProgress.find(bookId(path));
        if (record == null) return null;
        JSONObject page = progressAll().optJSONObject(path);
        if (page != null) {
            try {
                record.put("page", page.optInt("page", 0));
            } catch (Exception ignored) {
            }
        }
        return record;
    }

    public static void saveProgress(String path, String title, String cover, int chapter, String chapterName, int page, int pages) {
        if (path == null || path.isEmpty()) return;
        NovelProgress.save(bookId(path), title, cover == null ? "" : cover, "", chapter, chapterName, pages > 1 ? Math.round(page * 100.0f / (pages - 1)) : 0);
        try {
            JSONObject all = progressAll();
            JSONObject record = new JSONObject();
            record.put("page", page);
            all.put(path, record);
            prefs().edit().putString(KEY_PROGRESS, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void removeProgress(String path) {
        NovelProgress.remove(bookId(path));
        try {
            JSONObject progress = progressAll();
            progress.remove(path);
            prefs().edit().putString(KEY_PROGRESS, progress.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 封面格子下面那行字：看过的显示「看到 第几话 第几页」，没看过的显示「共 N 话」。 */
    public static String progressText(Context context, JSONObject comic) {
        JSONObject record = progress(comic.optString("path"));
        if (record == null) return context.getString(R.string.comic_chapters, comic.optInt("chapters"));
        return context.getString(R.string.comic_read_to, record.optString("chapterName"), record.optInt("page") + 1);
    }

    // ===== 图片地址 =====

    public static boolean isLocal(String uri) {
        return uri != null && (uri.startsWith(FILE_PREFIX) || uri.startsWith(ZIP_PREFIX));
    }

    /** 本地图片地址 → 可交给 Glide 的文件；压缩包条目解到缓存目录（已解过直接用）。失败返回 null。 */
    public static File resolve(String uri) {
        try {
            if (uri.startsWith(FILE_PREFIX)) return new File(uri.substring(FILE_PREFIX.length()));
            if (!uri.startsWith(ZIP_PREFIX)) return null;
            String rest = uri.substring(ZIP_PREFIX.length());
            int sep = rest.indexOf(ZIP_SEP);
            if (sep < 0) return null;
            File archive = new File(rest.substring(0, sep));
            String entryName = rest.substring(sep + ZIP_SEP.length());
            File dir = new File(App.get().getCacheDir(), "comic_zip");
            if (!dir.exists()) dir.mkdirs();
            String ext = entryName.substring(entryName.lastIndexOf('.') + 1);
            File out = new File(dir, Integer.toHexString(uri.hashCode()) + "_" + archive.length() + "_" + archive.lastModified() + "." + ext);
            if (out.exists() && out.length() > 0) return out;
            try (ZipFile zip = openZip(archive)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) return null;
                File tmp = new File(dir, out.getName() + ".tmp");
                try (InputStream in = zip.getInputStream(entry); OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
                }
                if (!tmp.renameTo(out)) return tmp;
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }
}
