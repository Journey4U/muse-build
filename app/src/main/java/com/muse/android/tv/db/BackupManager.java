package com.muse.android.tv.db;

import android.util.Log;

import com.muse.android.tv.App;
import com.muse.android.tv.bean.Backup;
import com.muse.android.tv.impl.Callback;
import com.muse.android.tv.utils.FileUtil;
import com.muse.android.tv.utils.Formatters;
import com.muse.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class BackupManager {

    private static final String TAG = BackupManager.class.getSimpleName();
    // 2026-11-28 Liu：自动备份（退出首页时）按天一份、同日覆盖，留 3 份；
    // 手动备份（设置页「备份」）单独命名、不被自动覆盖，留 3 份；两类各自计数、各自清理。
    private static final int MAX_AUTO_BACKUPS = 3;
    private static final int MAX_MANUAL_BACKUPS = 3;
    private static final String BACKUP_EXTENSION = ".tv";
    // 手动备份文件名：yyyy-MM-dd_HHmmss-manual.tv（文件名用 ASCII，界面显示「手动」）
    private static final String MANUAL_SUFFIX = "-manual" + BACKUP_EXTENSION;
    private static final DateTimeFormatter MANUAL_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);
    private static final DateTimeFormatter MANUAL_SHOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final String LEGACY_BACKUP_PREFIX = "tv-";
    private static final String LEGACY_BACKUP_SUFFIX = ".bk.gz";

    /** 自动备份：首页 onDestroy 调用。按天一份 yyyy-MM-dd.tv，同日覆盖，只清理自动备份。 */
    public static void backup() {
        Task.executeSerial(() -> {
            if (save(false)) trim(false);
        });
    }

    /** 手动备份：设置页「备份」调用。单独命名，不被自动备份覆盖，只清理手动备份。 */
    public static void backup(Callback callback) {
        Task.executeSerial(() -> {
            boolean success = save(true);
            post(callback, success);
            if (success) trim(true);
        });
    }

    public static boolean isManual(File file) {
        return file != null && file.getName().endsWith(MANUAL_SUFFIX);
    }

    /** 手动备份的显示时间（从文件名还原，如 2026-11-28 21:30:15）；不是手动备份或名字不规范时返回文件名。 */
    public static String getManualTime(File file) {
        String name = file.getName();
        if (!isManual(file)) return name;
        try {
            return LocalDateTime.parse(name.substring(0, name.length() - MANUAL_SUFFIX.length()), MANUAL_NAME).format(MANUAL_SHOW);
        } catch (Exception e) {
            return name.substring(0, name.length() - MANUAL_SUFFIX.length());
        }
    }

    public static void restore(File file, Callback callback) {
        Task.executeSerial(() -> post(callback, restore(file)));
    }

    public static List<File> getFiles() {
        migrate();
        List<File> backups = new ArrayList<>(Path.list(Path.backup()));
        backups.removeIf(file -> !file.isFile() || !file.getName().endsWith(BACKUP_EXTENSION));
        for (File file : Path.list(Path.tv())) if (isUnmigratedLegacy(file)) backups.add(file);
        backups.sort(Comparator.comparingLong(File::lastModified).reversed());
        return backups;
    }

    private static boolean save(boolean manual) {
        Backup backup = Backup.create();
        String name = manual ? LocalDateTime.now().format(MANUAL_NAME) + MANUAL_SUFFIX : LocalDate.now().format(Formatters.DATE) + BACKUP_EXTENSION;
        File file = new File(Path.backup(), name);
        return !backup.getConfig().isEmpty() && FileUtil.gzipCompress(backup.toString().getBytes(StandardCharsets.UTF_8), file);
    }

    private static boolean restore(File file) {
        Backup backup = Backup.objectFrom(FileUtil.readGzip(file));
        boolean valid = !backup.getConfig().isEmpty();
        if (valid) backup.restore();
        return valid;
    }

    private static void post(Callback callback, boolean success) {
        App.post(success ? callback::success : callback::error);
    }

    // getFiles() 已按修改时间新在前；旧的日期名备份与迁移来的 tv-*.bk.gz 都算自动备份
    private static void trim(boolean manual) {
        Predicate<File> sameKind = file -> isManual(file) == manual;
        getFiles().stream().filter(sameKind).skip(manual ? MAX_MANUAL_BACKUPS : MAX_AUTO_BACKUPS).forEach(Path::clear);
    }

    private static void migrate() {
        for (File file : Path.list(Path.tv())) if (isLegacy(file)) migrate(file);
    }

    private static boolean isLegacy(File file) {
        String name = file.getName();
        return file.isFile() && name.startsWith(LEGACY_BACKUP_PREFIX) && name.endsWith(LEGACY_BACKUP_SUFFIX);
    }

    private static void migrate(File file) {
        File target = getMigrationTarget(file);
        if (target.exists()) return;
        try {
            Path.move(file, target);
        } catch (IOException e) {
            Log.w(TAG, "Unable to migrate backup file=" + file, e);
        }
    }

    private static boolean isUnmigratedLegacy(File file) {
        return isLegacy(file) && !getMigrationTarget(file).exists();
    }

    private static File getMigrationTarget(File file) {
        String name = file.getName();
        String date = name.substring(LEGACY_BACKUP_PREFIX.length(), name.length() - LEGACY_BACKUP_SUFFIX.length());
        return new File(Path.backup(), date + BACKUP_EXTENSION);
    }
}
