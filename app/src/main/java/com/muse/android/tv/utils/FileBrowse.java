package com.muse.android.tv.utils;

import com.github.catvod.utils.Prefers;
import com.muse.android.tv.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 选本地文件页面的常规功能（Liu 2026-11-28 批）：隐藏系统文件开关 + 排序方式，两项都记住。
 * 所有「选本地文件/文件夹」的地方统一走这里列目录：文件页各模式（源配置选文件、本地源选文件夹、
 * 书架导入书/漫画）与手机的两个目录树弹窗。catvod 的 Path.list/Path.sort 不动（别处还在用）。
 */
public final class FileBrowse {

    private static final String KEY_HIDE = "file_hide_dot";
    private static final String KEY_SORT = "file_sort";

    public static final int SORT_NAME_ASC = 0;
    public static final int SORT_NAME_DESC = 1;
    public static final int SORT_TIME_NEW = 2;
    public static final int SORT_TIME_OLD = 3;
    private static final int SORT_COUNT = 4;

    private FileBrowse() {
    }

    /** 隐藏系统文件（名字以「.」开头的文件/文件夹），默认开。 */
    public static boolean isHideDot() {
        return Prefers.getBoolean(KEY_HIDE, true);
    }

    public static void putHideDot(boolean hide) {
        Prefers.put(KEY_HIDE, hide);
    }

    public static int getSort() {
        int sort = Prefers.getInt(KEY_SORT, SORT_NAME_ASC);
        return sort < 0 || sort >= SORT_COUNT ? SORT_NAME_ASC : sort;
    }

    public static void putSort(int sort) {
        Prefers.put(KEY_SORT, sort < 0 || sort >= SORT_COUNT ? SORT_NAME_ASC : sort);
    }

    /** 四种排序的显示名，下标与 SORT_* 常量一致。 */
    public static String[] getSortNames() {
        return new String[]{
                ResUtil.getString(R.string.file_sort_name_asc),
                ResUtil.getString(R.string.file_sort_name_desc),
                ResUtil.getString(R.string.file_sort_time_new),
                ResUtil.getString(R.string.file_sort_time_old)};
    }

    public static String getSortName() {
        return getSortNames()[getSort()];
    }

    /** 列目录（文件与文件夹），按开关过滤、按排序方式排好；文件夹始终在文件前。 */
    public static List<File> list(File dir) {
        return list(dir, false);
    }

    /** 只列子文件夹（目录树弹窗用）。 */
    public static List<File> listDirs(File dir) {
        return list(dir, true);
    }

    private static List<File> list(File dir, boolean dirsOnly) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        boolean hide = isHideDot();
        for (File file : files) {
            if (hide && file.getName().startsWith(".")) continue;
            if (dirsOnly && !file.isDirectory()) continue;
            out.add(file);
        }
        sort(out);
        return out;
    }

    public static void sort(List<File> files) {
        Collections.sort(files, comparator(getSort()));
    }

    private static Comparator<File> comparator(int sort) {
        return (a, b) -> {
            boolean da = a.isDirectory(), db = b.isDirectory();
            if (da != db) return da ? -1 : 1;
            int name = a.getName().compareToIgnoreCase(b.getName());
            switch (sort) {
                case SORT_NAME_DESC:
                    return -name;
                case SORT_TIME_NEW: {
                    int time = Long.compare(b.lastModified(), a.lastModified());
                    return time != 0 ? time : name;
                }
                case SORT_TIME_OLD: {
                    int time = Long.compare(a.lastModified(), b.lastModified());
                    return time != 0 ? time : name;
                }
                default:
                    return name;
            }
        };
    }
}
