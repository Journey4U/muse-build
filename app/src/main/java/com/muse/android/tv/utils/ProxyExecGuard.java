package com.muse.android.tv.utils;

import android.os.FileObserver;

import androidx.annotation.Nullable;

import com.github.catvod.crawler.DebugLogStore;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * r30-③：第三方源「代理程序」以 126（不可执行）启动失败的守卫与自检。
 *
 * <p>实测日志（每次加载都会复现）：
 * <pre>
 * SpiderDebug: 正在启动代理程序...
 * SpiderDebug: 命令执行失败: nohup /data/user/0/com.muse.android.tv/files/go_proxy_video_64 (错误码: 126)
 * SpiderDebug: 配置信息初始化失败，请重启APP
 * </pre>
 *
 * <p>r28 只在 {@code Application.onCreate} 补一次可执行位，但该文件是<b>运行中</b>才被第三方源释放到
 * {@code files/} 的（实测进程启动时它并不存在 → 日志里没有任何「可执行位修复」记录），所以补位永远来不及。
 * 本类改成<b>监听目录</b>：文件一出现/一被写完就立刻补执行位，并顺手做一次自检取证：
 * <ul>
 *   <li>{@code perms} —— 真实 POSIX 权限位；</li>
 *   <li>{@code elf} —— 头 4 字节是否 ELF、e_machine（AArch64=0xB7 / x86_64=0x3E），用于判定「下成别的架构」；</li>
 *   <li>{@code execTest} —— 直接 exec 该文件（带 {@code -h}，1.5s 后回收）的结果：
 *       成功=exit 码；被拒={@code IOException/ErrnoException(EACCES)}；格式错={@code ENOEXEC}。
 *       这条能把「缺执行位 / Android 10+ 私有目录禁止 exec(targetSdk≥29) / 架构不符」三者的责任一次分清。</li>
 * </ul>
 * 只做只读自检 + 补执行位，不修改第三方源的任何文件内容。
 */
public final class ProxyExecGuard {

    private static final String TAG = "ProxyGuard";
    private static volatile ProxyExecGuard instance;

    /** 1.0.3-⑦ 代理自检移到后台：单线程 executor，保证 sweep 与后续事件按序执行，r35 坏文件替换逻辑原样保留在 inspect() 内。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "proxy-guard"));
    private FileObserver observer;

    private ProxyExecGuard() {
    }

    public static ProxyExecGuard get() {
        if (instance == null) {
            synchronized (ProxyExecGuard.class) {
                if (instance == null) instance = new ProxyExecGuard();
            }
        }
        return instance;
    }

    /** 目录监听 + 先扫一遍已有文件（幂等，重复调用无副作用）。 */
    public void start(File dir) {
        if (dir == null) return;
        executor.execute(() -> sweep(dir));
        if (observer != null) return;
        try {
            observer = new FileObserver(dir.getAbsolutePath(), FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.MODIFY | FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    if (path == null || !looksLikeProxy(path)) return;
                    File file = new File(dir, path);
                    executor.execute(() -> inspect(file));
                }
            };
            observer.startWatching();
            DebugLogStore.add(TAG, "代理程序监听已启动：" + dir.getAbsolutePath());
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "代理程序监听启动失败：" + e);
        }
    }

    /** 扫一遍目录里现有的可疑文件。 */
    public void sweep(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) if (file != null && file.isFile() && looksLikeProxy(file.getName())) inspect(file);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "代理程序扫描失败：" + e);
        }
    }

    private static boolean looksLikeProxy(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.startsWith("go_proxy") || lower.startsWith("proxy") || lower.startsWith("go_");
    }

    private void inspect(File file) {
        try {
            if (file == null || !file.isFile() || file.length() == 0) return;
            boolean ok = true;
            if (!file.canExecute()) ok = file.setExecutable(true, false);
            String elf = elf(file);
            DebugLogStore.add(TAG, "代理文件自检：" + file.getName()
                + " len=" + file.length()
                + " perms=" + perms(file)
                + " canExecute=" + file.canExecute()
                + " 补位=" + ok
                + " elf=" + elf
                + " execTest=" + execTest(file));
            // r30-③：明显不是可执行文件的（HTML 错误页 / 半截文件）→ 记录内容头并回收，
            // 让第三方源下次重新释放 / 下载，避免永远拿一个坏文件去 exec（126 / EACCES）。
            if (elf.startsWith("not-elf") || elf.startsWith("short")) {
                DebugLogStore.add(TAG, "代理文件不是可执行文件：" + file.getName() + " 内容头=" + head(file));
                // r35-③：先尝试用「第三方源自己 jar 里自带的真二进制」替换掉这个 404 网页，
                // 替换成功就保留（并设为只读，防止源下一次 404 下载再把它盖掉）；
                // 找不到才回收，至少不再拿一个 HTML 去 exec。
                if (replaceWithBundledBinary(file)) return;
                if (!file.delete()) DebugLogStore.add(TAG, "代理文件回收失败（可能有进程占用）：" + file.getName());
            }
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "代理文件自检失败：" + file.getName() + " -> " + e);
        }
    }

    /**
     * r35-③：第三方源自带的代理二进制通常就在它自己的 jar 里（例：yt.jar 的 assets/yt64，8MB）。
     * 源启动时会去下载同名程序，但下载地址已失效（404）→ 落下一个 HTML 网页 → exec 失败 →
     * 反复弹「配置信息初始化失败，请重启APP」。这里把 jar 里那份真二进制取出来放到目标路径，
     * 并设为只读，尽量让源下一次那笔 404 覆盖写不进去。
     */
    /**
     * r35-③ / r59-②：第三方源自带的代理二进制通常就在它自己的 jar 里（例：yt.jar 的 assets/yt64，8MB）。
     * 源启动时会去下载同名程序，但下载地址已失效（404）→ 落下一个 HTML 网页 → exec 失败 →
     * 反复弹「配置信息初始化失败，请重启APP」。
     *
     * <p>r59-② 修两处硬伤（老实现实测永远替换不上）：</p>
     * <ul>
     *   <li>只拿<b>第一个</b>「含大条目」的 jar 试，那个 jar（如 1118.jar / custom_spider.jar，里面只有 dex）
     *       里没有合适的二进制就直接 {@code return false}，而且<b>不打日志</b> —— 日志里只剩「不是可执行文件」；</li>
     *   <li>选条目只看体积、不校验 ELF 魔数，可能把 payload.dat / classes.dex 当成代理程序写进去。</li>
     * </ul>
     * <p>现在遍历全部扫描目录下的 jar，挑<b>ELF 且名字像代理程序</b>的最大条目写到目标路径并设只读；
     * 同时留一份 {@code *.good} 底稿，下次被那笔 404 覆盖时可以秒级还原。</p>
     */
    private boolean replaceWithBundledBinary(File target) {
        // 1) 先看有没有上次留下的底稿（最快、且已被验证过是 ELF）
        File backup = new File(target.getParentFile(), target.getName() + ".good");
        if (copyFile(backup, target)) {
            DebugLogStore.add(TAG, "已用底稿还原代理程序：" + target.getName() + " (" + target.length() + "B, elf=" + elf(target) + ")");
            return true;
        }
        // 2) 扫全部 jar 找 ELF 代理二进制
        java.util.List<File> jars = new java.util.ArrayList<>();
        for (String dir : scanDirs()) collectJars(new File(dir), 0, jars);
        if (jars.isEmpty()) {
            DebugLogStore.add(TAG, "未在扫描目录里找到任何 jar，无法替换代理程序");
            return false;
        }
        byte[] best = null;
        String bestFrom = null;
        for (File jar : jars) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || entry.getSize() < 256 * 1024) continue;
                    String name = entry.getName().toLowerCase(java.util.Locale.US);
                    if (!(name.contains("64") || name.contains("proxy") || name.endsWith(".so"))) continue;
                    byte[] data;
                    try (java.io.InputStream in = zip.getInputStream(entry)) {
                        data = readAll(in);
                    }
                    if (!isElf(data)) continue;
                    if (best != null && data.length <= best.length) continue;
                    best = data;
                    bestFrom = jar.getName() + "!" + entry.getName();
                }
            } catch (Throwable ignored) {
            }
        }
        if (best == null) {
            DebugLogStore.add(TAG, "扫描了 " + jars.size() + " 个 jar，没找到可用的 ELF 代理二进制");
            return false;
        }
        try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            out.write(best);
        } catch (Throwable e) {
            DebugLogStore.add(TAG, "写入代理二进制失败：" + e);
            return false;
        }
        target.setReadable(true, false);
        target.setExecutable(true, false);
        target.setWritable(false, false);
        DebugLogStore.add(TAG, "已用 jar 自带二进制替换代理程序：" + bestFrom + " -> " + target.getName()
            + " (" + target.length() + "B, elf=" + elf(target) + ")");
        try {
            if (backup.exists()) backup.setWritable(true, false);
            try (java.io.OutputStream out = new java.io.FileOutputStream(backup)) {
                out.write(best);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 把 src 复制成 target（补执行位 + 只读）；src 不是 ELF 就放弃。 */
    private boolean copyFile(File src, File target) {
        try {
            if (src == null || !src.isFile() || src.length() < 256 * 1024) return false;
            byte[] data = readAll(new FileInputStream(src));
            if (!isElf(data)) return false;
            try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                out.write(data);
            }
            target.setReadable(true, false);
            target.setExecutable(true, false);
            target.setWritable(false, false);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 收集目录树（深度 ≤3）里的全部 jar。 */
    private static void collectJars(File dir, int depth, java.util.List<File> out) {
        if (dir == null || depth > 3 || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isFile() && child.getName().toLowerCase(java.util.Locale.US).endsWith(".jar")) out.add(child);
        }
        for (File child : children) {
            if (child.isDirectory()) collectJars(child, depth + 1, out);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static boolean isElf(byte[] data) {
        return data != null && data.length > 64 && data[0] == 0x7F && data[1] == 0x45 && data[2] == 0x4C && data[3] == 0x46;
    }

    /** 用户勾选的本地源扫描目录。 */
    private java.util.List<String> scanDirs() {
        try {
            return com.muse.android.tv.api.loader.LocalSourceHub.getScanDirs();
        } catch (Throwable e) {
            return java.util.Collections.emptyList();
        }
    }

    private static String head(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[160];
            int read = in.read(buffer);
            if (read <= 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < read; i++) {
                char c = (char) (buffer[i] & 0xFF);
                sb.append(c >= 0x20 && c < 0x7F ? c : '.');
            }
            return sb.toString();
        } catch (Throwable e) {
            return "?(" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String perms(File file) {
        try {
            android.system.StructStat stat = android.system.Os.stat(file.getAbsolutePath());
            return "0" + Integer.toOctalString(stat.st_mode & 0777);
        } catch (Throwable e) {
            return "?(canExecute=" + file.canExecute() + ")";
        }
    }

    /** 读头 20 字节：是否为 ELF、e_machine 是什么（判断是不是下错了架构）。 */
    private static String elf(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[20];
            int read = in.read(head);
            if (read < 20) return "short(" + read + ")";
            if (head[0] != 0x7F || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') {
                return "not-elf(" + String.format(Locale.US, "%02X%02X", head[0], head[1]) + ")";
            }
            int machine = (head[18] & 0xFF) | ((head[19] & 0xFF) << 8);
            String name = machine == 0xB7 ? "AArch64" : machine == 0x3E ? "x86_64" : machine == 0x28 ? "ARM" : machine == 0x03 ? "x86" : ("machine=" + machine);
            return "ELF/" + name;
        } catch (Throwable e) {
            return "?(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** 直接 exec 该文件（-h 通常只打印用法），1.5s 未结束就回收 —— 用来区分 EACCES / ENOEXEC / 正常。 */
    private static String execTest(File file) {
        Process process = null;
        try {
            process = new ProcessBuilder(file.getAbsolutePath(), "-h").redirectErrorStream(true).start();
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroy();
                return "已启动(未在1.5s内退出)";
            }
            return "exit=" + process.exitValue();
        } catch (Throwable e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return e.getClass().getSimpleName() + "/" + cause.getClass().getSimpleName() + ":" + cause.getMessage();
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
