package com.muse.android.tv.utils;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;

import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.ui.activity.FileActivity;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public final class FileChooser {

    private final ActivityResultLauncher<Intent> launcher;

    public static FileChooser from(ActivityResultLauncher<Intent> launcher) {
        return new FileChooser(launcher);
    }

    private FileChooser(ActivityResultLauncher<Intent> launcher) {
        this.launcher = launcher;
    }

    public static File getCacheDir() {
        return Path.cache("chooser");
    }

    public void show() {
        show("*/*", new String[]{"*/*"});
    }

    public void show(String[] mimeTypes) {
        show("*/*", mimeTypes);
    }

    public void show(String mimeType, String[] mimeTypes) {
        Intent intent = createIntent(mimeType, mimeTypes);
        launcher.launch(useInternal(intent) ? intent.setClass(App.get(), FileActivity.class) : Intent.createChooser(intent, ""));
    }

    // Always the in-app browser: it returns a real file URI, so the stored
    // address keeps pointing at the picked file's real directory. Config
    // files depend on this -- their relative references (./jar/, ./py/)
    // resolve against that directory, which a cache copy cannot provide.
    public void showInternal() {
        Intent intent = createIntent("*/*", new String[]{"*/*"});
        launcher.launch(intent.setClass(App.get(), FileActivity.class));
    }

    public static final String EXTRA_FOLDER_MODE = "muse_folder_mode";

    // Same in-app browser, but the browser additionally offers "select the
    // current folder", returning the directory itself as a file URI. Only
    // callers that can handle a directory result may use this.
    public void showInternalFolder() {
        Intent intent = createIntent("*/*", new String[]{"*/*"});
        intent.putExtra(EXTRA_FOLDER_MODE, true);
        launcher.launch(intent.setClass(App.get(), FileActivity.class));
    }

    private static Intent createIntent(String mimeType, String[] mimeTypes) {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType(mimeType)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private static boolean useInternal(Intent intent) {
        if (Util.isLeanback()) return true;
        List<ResolveInfo> resolveInfos = App.get().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo resolveInfo : resolveInfos) if (isUsable(resolveInfo)) return false;
        return true;
    }

    private static boolean isUsable(ResolveInfo resolveInfo) {
        String packageName = resolveInfo.activityInfo == null ? null : resolveInfo.activityInfo.packageName;
        return packageName != null && !packageName.contains("frameworkpackagestubs");
    }

    public static void getUri(ActivityResult result, Consumer<Uri> callback) {
        if (result.getResultCode() == Activity.RESULT_OK) getUri(result.getData(), callback);
    }

    public static void getUri(@Nullable Intent data, Consumer<Uri> callback) {
        getFileUri(data == null ? null : data.getData(), callback);
    }

    public static void getFileUri(@Nullable Uri uri, Consumer<Uri> callback) {
        if (!isFileSource(uri)) return;
        Task.execute(() -> resolveFileUri(uri, callback));
    }

    private static void resolveFileUri(Uri uri, Consumer<Uri> callback) {
        try {
            deliver(callback, resolveFileUri(uri));
        } catch (IOException | SecurityException e) {
            App.post(() -> Notify.show(Notify.getError(R.string.error_file_open, e)));
        }
    }

    private static Uri resolveFileUri(Uri uri) throws IOException {
        File file = getLocalFile(uri);
        return file == null ? materialize(uri, getCacheFile(uri)) : Uri.fromFile(file);
    }

    private static void deliver(Consumer<Uri> callback, Uri uri) {
        App.post(() -> callback.accept(uri));
    }

    @Nullable
    private static File getLocalFile(Uri uri) {
        try {
            String path = getLocalPath(App.get(), uri);
            if (TextUtils.isEmpty(path)) {
                DebugLogStore.addPlain(null, "config", "chooser no-local-path uri=" + uri);
                return null;
            }
            File file = new File(path);
            boolean isFile = file.isFile();
            boolean canRead = file.canRead();
            if (!isFile || !canRead) DebugLogStore.addPlain(null, "config", "chooser gate-fail uri=" + uri + " path=" + path + " isFile=" + isFile + " canRead=" + canRead);
            return isFile && canRead ? file : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String getLocalPath(Context context, Uri uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) return getDocumentPath(context, uri);
        if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
            String path = getDataColumn(context, uri);
            return path != null ? path : getEmbeddedDocumentPath(uri);
        }
        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) return uri.getPath();
        return null;
    }

    @Nullable
    private static String getDocumentPath(Context context, Uri uri) {
        String docId = DocumentsContract.getDocumentId(uri);
        String[] split = docId.split(":", 2);
        if (isExternalStorageDocument(uri)) return getExternalStoragePath(docId, split);
        if (isDownloadsDocument(uri)) return getDownloadPath(context, docId);
        if (isMediaDocument(uri)) return getMediaPath(context, split);
        return getGenericDocumentPath(docId, split);
    }

    @Nullable
    private static String getGenericDocumentPath(String docId, String[] split) {
        // Providers outside the well-known three (OEM file managers, e.g.
        // ColorOS) still hand out docIds in the same shapes; resolve those
        // generically instead of giving up to a cache copy, whose directory
        // cannot satisfy a picked file's relative references. Bogus
        // candidates are rejected by the caller's isFile/canRead gate.
        if (docId.startsWith("raw:")) return docId.substring(4);
        if (split.length == 2 && !split[0].isEmpty() && !split[1].isEmpty()) {
            if ("primary".equalsIgnoreCase(split[0])) return new File(Environment.getExternalStorageDirectory(), split[1]).getPath();
            return "/storage/" + split[0] + "/" + split[1];
        }
        if (docId.startsWith("/")) return docId;
        return null;
    }

    @Nullable
    private static String getEmbeddedDocumentPath(Uri uri) {
        // Some providers answer with a plain content URI whose last segment
        // still embeds the document id.
        String segment = uri.getLastPathSegment();
        if (TextUtils.isEmpty(segment)) return null;
        return getGenericDocumentPath(segment, segment.split(":", 2));
    }

    @Nullable
    private static String getExternalStoragePath(String docId, String[] split) {
        if (split.length < 2) return null;
        if ("primary".equalsIgnoreCase(split[0])) return new File(Environment.getExternalStorageDirectory(), split[1]).getPath();
        return "/storage/" + docId.replace(":", "/");
    }

    @Nullable
    private static String getDownloadPath(Context context, String docId) {
        try {
            if (docId.startsWith("raw:")) return docId.substring(4);
            boolean mediaStoreId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && docId.startsWith("msf:");
            long id = Long.parseLong(mediaStoreId ? docId.substring(4) : docId);
            Uri download = mediaStoreId ? ContentUris.withAppendedId(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), id) : ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id);
            return getDataColumn(context, download);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static String getMediaPath(Context context, String[] split) {
        if (split.length < 2) return null;
        Uri contentUri = getMediaUri(split[0]);
        return getDataColumn(context, ContentUris.withAppendedId(contentUri, Long.parseLong(split[1])));
    }

    private static Uri getMediaUri(String type) {
        String volume = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.VOLUME_EXTERNAL : "external";
        return switch (type) {
            case "image" -> MediaStore.Images.Media.getContentUri(volume);
            case "video" -> MediaStore.Video.Media.getContentUri(volume);
            case "audio" -> MediaStore.Audio.Media.getContentUri(volume);
            default -> MediaStore.Files.getContentUri(volume);
        };
    }

    @Nullable
    private static String getDataColumn(Context context, Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DATA};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            return cursor == null || !cursor.moveToFirst() ? null : cursor.getString(cursor.getColumnIndexOrThrow(projection[0]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static File getCacheFile(Uri uri) {
        String name = getFileName(uri);
        File dir = new File(getCacheDir(), Crypto.md5(uri.toString()));
        return new File(dir, name == null ? "file" : name);
    }

    @Nullable
    private static String getFileName(Uri uri) {
        String name = FileUtil.getDisplayName(uri, "");
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(separator + 1).replace('\0', '_');
        return TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name) ? null : name;
    }

    private static Uri materialize(Uri uri, File target) throws IOException {
        FileUtil.copyAtomically(uri, target);
        return Uri.fromFile(target);
    }

    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    private static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    public static boolean isFileSource(@Nullable Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        return ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(scheme) || ContentResolver.SCHEME_FILE.equalsIgnoreCase(scheme);
    }
}

