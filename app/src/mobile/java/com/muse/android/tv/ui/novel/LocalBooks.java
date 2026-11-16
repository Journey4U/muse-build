package com.muse.android.tv.ui.novel;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.muse.android.tv.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/**
 * 本地书（TXT / EPUB）：导入解析与存储（SharedPreferences JSON）。
 * TXT 走编码识别 + 标题正则切章；EPUB 走 zip 解包（OPF/spine/目录）抽纯文本。
 * 两种格式最终都是「整书纯文本 + 章节 char 偏移索引」，阅读器/进度/书签共用一套。
 * 全文解码结果做单条静态缓存，读章时按偏移切分。所有 IO 方法须在后台线程调用。
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

    /** 导入并解析一本本地书（按扩展名分发 TXT / EPUB）；成功返回章节数，失败返回 -1。 */
    public static int importBook(File file) {
        try {
            if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) return -1;
            boolean epub = file.getName().toLowerCase(Locale.ROOT).endsWith(".epub");
            EpubBook parsed = epub ? parseEpub(file) : null;
            String charset;
            String text;
            JSONArray chapters;
            String title;
            if (epub) {
                if (parsed == null || TextUtils.isEmpty(parsed.text)) return -1;
                charset = "UTF-8";
                text = parsed.text;
                chapters = parsed.chapters;
                title = TextUtils.isEmpty(parsed.title) ? titleOf(file) : parsed.title;
            } else {
                charset = detectCharset(file);
                text = decode(file, charset);
                if (TextUtils.isEmpty(text)) return -1;
                chapters = indexChapters(text);
                title = titleOf(file);
            }
            String path = file.getAbsolutePath();
            JSONObject book = new JSONObject();
            book.put("path", path);
            book.put("title", title);
            book.put("format", epub ? "epub" : "txt");
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
        String text;
        if ("epub".equals(book.optString("format"))) {
            EpubBook parsed = parseEpub(new File(path));
            if (parsed == null) return null;
            text = parsed.text;
        } else {
            text = decode(new File(path), book.optString("charset", "UTF-8"));
        }
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


    // ==================== EPUB 解析 ====================
    // 标准 EPUB（无 DRM）：zip → META-INF/container.xml → OPF（manifest/spine/元数据）
    // → 目录（EPUB3 nav 优先，其次 EPUB2 NCX，再次 spine 内首个标题）→ 逐章 XHTML 转纯文本。
    // 章节偏移沿用 TXT 的 char 偏移模型：整书文本 = 各章文本以 "\n\n" 相连。

    private static final class EpubBook {
        String title;
        String text;
        JSONArray chapters;
    }

    private static EpubBook parseEpub(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("META-INF/encryption.xml") != null) return null; // DRM 加密书不支持
            String container = readEntry(zip, "META-INF/container.xml");
            if (container == null) return null;
            Matcher rootMatcher = Pattern.compile("full-path=\"([^\"]+)\"").matcher(container);
            if (!rootMatcher.find()) return null;
            String opfPath = rootMatcher.group(1);
            String opfDir = dirOf(opfPath);
            String opf = readEntry(zip, opfPath);
            if (opf == null) return null;

            Map<String, String[]> manifest = new LinkedHashMap<>(); // id -> [href, media-type]
            Matcher itemMatcher = Pattern.compile("<item\\s[^>]*>").matcher(opf);
            while (itemMatcher.find()) {
                String tag = itemMatcher.group();
                String id = attr(tag, "id");
                String href = attr(tag, "href");
                if (id == null || href == null) continue;
                manifest.put(id, new String[]{href, attr(tag, "media-type")});
            }
            List<String> spine = new ArrayList<>();
            Matcher refMatcher = Pattern.compile("<itemref\\s[^>]*>").matcher(opf);
            while (refMatcher.find()) {
                String idref = attr(refMatcher.group(), "idref");
                if (idref != null) spine.add(idref);
            }
            if (spine.isEmpty()) return null;

            String title = null;
            Matcher titleMatcher = Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>").matcher(opf);
            if (titleMatcher.find()) title = decodeEntities(titleMatcher.group(1)).trim();

            // 封面/图片等非文档项不进正文；CSS 等在下方按 media-type 再过滤
            List<String> docs = new ArrayList<>();
            List<String> docPaths = new ArrayList<>();
            for (String idref : spine) {
                String[] item = manifest.get(idref);
                if (item == null) continue;
                String type = item[1] == null ? "" : item[1];
                if (!type.contains("xhtml") && !type.contains("html") && !type.contains("xml")) continue;
                String full = resolvePath(opfDir, item[0]);
                docs.add(idref);
                docPaths.add(full);
            }
            if (docs.isEmpty()) return null;

            Map<String, String> tocNames = readTocNames(zip, opf, opfDir, manifest);
            StringBuilder full = new StringBuilder();
            JSONArray chapters = new JSONArray();
            for (int i = 0; i < docs.size(); i++) {
                String html = readEntry(zip, docPaths.get(i));
                if (html == null) continue;
                List<String[]> blocks = xhtmlBlocks(html);
                String name = tocNames.get(baseName(docPaths.get(i)));
                if (name == null && !blocks.isEmpty()) name = blocks.get(0)[0];
                if (name == null) name = "第" + (chapters.length() + 1) + "章";
                StringBuilder chapterText = new StringBuilder();
                for (int j = 0; j < blocks.size(); j++) {
                    if (j == 0 && blocks.get(j)[0].equals(name)) continue; // 首块就是章名，不重复进正文
                    chapterText.append(blocks.get(j)[1]).append("\n\n");
                }
                String body = chapterText.toString().trim();
                String chapterFull = body.isEmpty() ? name : name + "\n\n" + body;
                int start = full.length();
                if (start > 0) {
                    full.append("\n\n");
                    start += 2;
                }
                full.append(chapterFull);
                putChapter(chapters, name, start, chapterFull.length());
            }
            if (chapters.length() == 0) return null;
            EpubBook book = new EpubBook();
            book.title = title;
            book.text = full.toString();
            book.chapters = chapters;
            return book;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 读目录标题：href 文件名 -> 章名。nav（EPUB3）优先，其次 NCX（EPUB2）。 */
    private static Map<String, String> readTocNames(ZipFile zip, String opf, String opfDir, Map<String, String[]> manifest) {
        Map<String, String> names = new HashMap<>();
        try {
            String navPath = null;
            String ncxPath = null;
            for (Map.Entry<String, String[]> e : manifest.entrySet()) {
                String type = e.getValue()[1] == null ? "" : e.getValue()[1];
                if (type.contains("nav")) navPath = resolvePath(opfDir, e.getValue()[0]);
                if (type.contains("ncx")) ncxPath = resolvePath(opfDir, e.getValue()[0]);
            }
            if (ncxPath == null) {
                Matcher spineToc = Pattern.compile("<spine[^>]*toc=\"([^\"]+)\"").matcher(opf);
                if (spineToc.find()) {
                    String[] item = manifest.get(spineToc.group(1));
                    if (item != null) ncxPath = resolvePath(opfDir, item[0]);
                }
            }
            if (navPath != null) {
                String nav = readEntry(zip, navPath);
                if (nav != null) {
                    String navDir = dirOf(navPath);
                    Matcher a = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(nav);
                    while (a.find()) {
                        String text = stripTags(a.group(2)).trim();
                        if (!text.isEmpty()) names.put(resolveBase(navDir, a.group(1)), text);
                    }
                }
            }
            if (names.isEmpty() && ncxPath != null) {
                String ncx = readEntry(zip, ncxPath);
                if (ncx != null) {
                    String ncxDir = dirOf(ncxPath);
                    Matcher pt = Pattern.compile("<navPoint[^>]*>(.*?)</navPoint>", Pattern.DOTALL).matcher(ncx);
                    while (pt.find()) {
                        String block = pt.group(1);
                        Matcher label = Pattern.compile("<text>([^<]+)</text>").matcher(block);
                        Matcher src = Pattern.compile("src=\"([^\"]+)\"").matcher(block);
                        if (label.find() && src.find()) {
                            String text = decodeEntities(label.group(1)).trim();
                            if (!text.isEmpty()) names.put(resolveBase(ncxDir, src.group(1)), text);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    /** XHTML 转 [标题候选, 正文段落] 列表：第 0 项标题候选 = 首个 h1/h2/h3 文本（无则 ""），其余为正文段落。 */
    private static List<String[]> xhtmlBlocks(String html) {
        List<String[]> blocks = new ArrayList<>();
        String heading = "";
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(html));
            StringBuilder current = null;
            boolean skipDepth = false;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName() == null ? "" : parser.getName().toLowerCase(Locale.ROOT);
                    if (tag.equals("script") || tag.equals("style") || tag.equals("head")) skipDepth = true;
                    else if (!skipDepth && (tag.equals("p") || tag.equals("div") || tag.equals("h1") || tag.equals("h2") || tag.equals("h3")
                            || tag.equals("h4") || tag.equals("h5") || tag.equals("h6") || tag.equals("li") || tag.equals("blockquote"))) {
                        flushBlock(blocks, current);
                        current = new StringBuilder();
                        current.append(tag.startsWith("h") ? "\u0001" : "\u0002");
                    } else if (!skipDepth && tag.equals("br")) {
                        if (current != null) current.append('\n');
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (!skipDepth && current != null) current.append(parser.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName() == null ? "" : parser.getName().toLowerCase(Locale.ROOT);
                    if (tag.equals("script") || tag.equals("style") || tag.equals("head")) skipDepth = false;
                    else if (!skipDepth && current != null && (tag.equals("p") || tag.equals("div") || tag.equals("h1") || tag.equals("h2")
                            || tag.equals("h3") || tag.equals("h4") || tag.equals("h5") || tag.equals("h6") || tag.equals("li") || tag.equals("blockquote"))) {
                        flushBlock(blocks, current);
                        current = null;
                    }
                }
                event = parser.next();
            }
            flushBlock(blocks, current);
            StringBuilder merged = new StringBuilder();
            boolean headingTaken = false;
            for (String[] block : blocks) {
                if (block[0].equals("H") && !headingTaken) {
                    heading = block[1];
                    headingTaken = true;
                } else merged.append(block[1]).append("\n\n");
            }
            List<String[]> result = new ArrayList<>();
            result.add(new String[]{heading, ""});
            String body = merged.toString().trim();
            if (!body.isEmpty()) {
                for (String para : body.split("\n\n")) if (!para.trim().isEmpty()) result.add(new String[]{"", para.trim()});
            }
            return result;
        } catch (Throwable e) {
            // 解析失败的兜底：整页粗暴去标签
            List<String[]> result = new ArrayList<>();
            result.add(new String[]{"", ""});
            String text = stripTags(html).trim();
            if (!text.isEmpty()) result.add(new String[]{"", text});
            return result;
        }
    }

    private static void flushBlock(List<String[]> blocks, StringBuilder current) {
        if (current == null || current.length() <= 1) return;
        char kind = current.charAt(0);
        String text = current.substring(1).replaceAll("[\\s\\u00A0]+", " ").trim();
        if (!text.isEmpty()) blocks.add(new String[]{kind == '\u0001' ? "H" : "P", text});
    }

    private static String readEntry(ZipFile zip, String path) {
        try {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                // 少数打包器路径大小写/编码不一致：按文件名兜底找一次
                java.util.Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry e = all.nextElement();
                    if (e.getName().equalsIgnoreCase(path)) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return text.startsWith("\uFEFF") ? text.substring(1) : text;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(tag);
        if (m.find()) return decodeEntities(m.group(1));
        m = Pattern.compile(name + "='([^']*)'").matcher(tag);
        return m.find() ? decodeEntities(m.group(1)) : null;
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private static String baseName(String path) {
        String p = path;
        int hash = p.indexOf('#');
        if (hash >= 0) p = p.substring(0, hash);
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    /** href 相对其所在目录解析为 zip 内完整路径（处理 ../ 与 ./，忽略 #fragment）。 */
    private static String resolvePath(String dir, String href) {
        String h = href;
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        try {
            h = java.net.URLDecoder.decode(h, "UTF-8");
        } catch (Exception ignored) {
        }
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String part : (dir + h).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) parts.pollLast();
            else parts.addLast(part);
        }
        return String.join("/", parts);
    }

    private static String resolveBase(String dir, String href) {
        return baseName(resolvePath(dir, href));
    }

    private static String stripTags(String html) {
        String text = html.replaceAll("(?s)<[^>]+>", " ");
        return decodeEntities(text).replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static String decodeEntities(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static void putChapter(JSONArray chapters, String name, int start, int len) throws Exception {
        JSONObject chapter = new JSONObject();
        chapter.put("n", name);
        chapter.put("s", start);
        chapter.put("l", len);
        chapters.put(chapter);
    }
}
