package com.muse.android.tv.api.loader;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.muse.hook.Hook;

import java.util.List;

/**
 * 第三方订阅源 jar 的身份伪装上下文。
 *
 * <p>背景：部分源 jar（带「二次打包」保护的爬虫包）在 {@code Init.init(Context)} 里会做三件事：
 * 校验运行包名是否在其内部白名单内、校验应用名（{@code PackageManager#getApplicationLabel}）是否在白名单内、
 * 校验签名 DN 是否命中黑名单。任何一项不过就写日志并启动惩罚线程，约 5 秒后
 * {@code Process.killProcess(Process.myPid())} 自杀，表现为「切换订阅链接源提示加载错误并闪退」。</p>
 *
 * <p>这里交给 jar 一个透明代理上下文：除包名/应用名伪装成常见白名单身份（与参考实现 fongmi/TV 一致）外，
 * 资源、目录、系统服务等全部转发到真实 Context；PackageManager 的常用查询也转发到真实实现，
 * 只有「查自己」时返回伪装身份，从而不影响源本身的正常功能。</p>
 */
public final class SourceIdentity {

    /** 伪装包名：位于常见源 jar 的包名白名单内 */
    public static final String PKG = "com.fongmi.android.tv";

    /** 伪装应用名：位于常见源 jar 的应用名白名单内 */
    public static final String LABEL = "影视";

    private SourceIdentity() {
    }


    /** 生成交给 {@code com.github.catvod.spider.Init#init(Context)} 的伪装上下文 */
    public static Context context(Context base) {
        return new SpoofContext(base);
    }


    /** 取真实 PackageManager（绕开 App 上可能挂载的 Hook，例如电视直播内核路径） */
    private static PackageManager manager(Context base) {
        Context real = base instanceof ContextWrapper ? ((ContextWrapper) base).getBaseContext() : base;
        return real != null ? real.getPackageManager() : base.getPackageManager();
    }

    private static class SpoofContext extends Application {

        private final PackageManager manager;
        /** r33-①：真实包名。绝不能被伪装 —— 系统的 IPC 安全检查（Toast / 通知 / 前台服务）认的就是它。 */
        private final String realPackage;

        private SpoofContext(Context base) {
            this.realPackage = base.getPackageName();
            this.manager = new SpoofManager(SourceIdentity.manager(base), base.getPackageName());
            attachBaseContext(base);
        }

        /**
         * r34-①：包名伪装改成「按调用方区分」——只有**源 jar 自己的代码**读到的才是伪装名；
         * 只要调用栈里出现 {@code android.*} / {@code androidx.*} / {@code com.android.*}
         * （Toast、通知、Dialog、PopupWindow 等系统组件拿这个 Context 建窗口），一律给**真实包名**。
         *
         * <p>为什么 r33 没修好：当时只改了 {@code getOpPackageName()}，而 Toast 的窗口参数取的是
         * {@code getPackageName()} 这条路（栈：{@code ToastPresenter.addToastView → WMS}），
         * 伪装名被 WMS 判成「不属于本 UID」→ SecurityException → 主线程被杀（用户看到的「软件崩溃」）。</p>
         *
         * <p>jar 的白名单校验是它自己的代码直接调 {@code context.getPackageName()}，
         * 栈里没有框架帧 → 依旧看到 {@link #PKG}，伪装效果不受影响。</p>
         */
        @Override
        public String getPackageName() {
            return fromFramework() ? realPackage : PKG;
        }

        /** 只往前看有限几帧（避免每次 getPackageName 都做完整栈遍历）。 */
        private boolean fromFramework() {
            StackTraceElement[] frames = new Throwable().getStackTrace();
            int limit = Math.min(frames.length, 8);
            for (int i = 1; i < limit; i++) {
                String name = frames[i].getClassName();
                if (name == null || name.startsWith("com.muse.android.tv.api.loader.SourceIdentity")) continue;
                return name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("com.android.");
            }
            return false;
        }

        /**
         * r33-①：把 op-package 显式改回**真实包名**（com.muse.android.tv）。
         *
         * <p>崩溃实证：源 jar 在自己的线程里弹 Toast，用的还是我们给它的伪装上下文；
         * {@code Context#getOpPackageName()} 的默认实现返回 {@code getPackageName()}（也就是伪装名
         * {@code com.fongmi.android.tv}），于是 WMS 在
         * {@code WindowManagerService#doesAddToastWindowRequireToken} 处判定
         * 「Package com.fongmi.android.tv not in UID 10608」→ SecurityException → 主线程未捕获 → 进程被杀。
         * 日志栈：{@code Toast$TN.handleShow -> ToastPresenter.show -> ViewRootImpl.setView -> addToDisplayAsUser}。</p>
         *
         * <p>注意：只改 op-package，不动 {@link #getPackageName()}，因此源 jar 的
         * 「包名白名单」校验依旧能通过（它读的是 getPackageName / getApplicationInfo / 应用名）。</p>
         */
        @Override
        public String getOpPackageName() {
            return realPackage;
        }

        // r11-① / r34-①：这里**绝不能**把 op-package 伪装成 com.fongmi.android.tv。
        // 系统的 IPC 安全检查（Toast / 通知 / 前台服务等）判定的是「调用方包名是否属于本 uid」，
        // 一旦伪装就被判成「Package com.fongmi.android.tv not in UID …」并抛 SecurityException；
        // 源 jar 在自己线程里弹提示时该异常无人接管 → 直接杀进程（实测崩溃栈：
        // Toast$TN.handleShow -> ToastPresenter.addToastView -> IWindowSession.addToDisplayAsUser）。
        // r33 起 getOpPackageName() 固定返回真实包名；r34 起 getPackageName() 也做「按调用方区分」，
        // 框架内部（android./androidx.）一律看到真实包名，只有源 jar 自己读到的才是伪装名。

        @Override
        public PackageManager getPackageManager() {
            return manager;
        }
    }

    private static final class SpoofManager extends Hook {

        private final PackageManager real;
        private final String pkg;
        private final ApplicationInfo info;

        private SpoofManager(PackageManager real, String pkg) {
            super("", PKG);
            this.real = real;
            this.pkg = pkg;
            this.info = create();
        }

        /** 复制真实的应用信息后改写为伪装身份，避免丢掉 sourceDir 等字段 */
        private ApplicationInfo create() {
            ApplicationInfo result = new ApplicationInfo();
            ApplicationInfo source = null;
            try {
                source = real.getApplicationInfo(pkg, 0);
            } catch (Throwable ignored) {
            }
            if (source != null) {
                result.sourceDir = source.sourceDir;
                result.publicSourceDir = source.publicSourceDir;
                result.dataDir = source.dataDir;
                result.nativeLibraryDir = source.nativeLibraryDir;
                result.uid = source.uid;
                result.flags = source.flags;
                result.enabled = source.enabled;
                result.targetSdkVersion = source.targetSdkVersion;
            }
            result.packageName = PKG;
            result.name = LABEL;
            result.nonLocalizedLabel = LABEL;
            result.labelRes = 0;
            return result;
        }

        private boolean self(String packageName) {
            return PKG.equals(packageName) || pkg.equals(packageName);
        }

        /** 取真实包信息（含真实签名），仅把包名改写为伪装值 */
        private PackageInfo info(int flags) {
            try {
                PackageInfo result = real.getPackageInfo(pkg, flags | PackageManager.GET_SIGNATURES);
                if (result != null && (result.signatures == null || result.signatures.length == 0) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageInfo raw = real.getPackageInfo(pkg, 0);
                    if (raw != null && raw.signingInfo != null) {
                        raw.signatures = raw.signingInfo.getApkContentsSigners();
                        result = raw;
                    }
                }
                if (result != null) result.packageName = PKG;
                return result;
            } catch (Throwable e) {
                return null;
            }
        }

        @NonNull
        @Override
        public ApplicationInfo getApplicationInfo(@NonNull String packageName, int flags) {
            if (self(packageName)) return info;
            return super.getApplicationInfo(packageName, flags);
        }

        @NonNull
        @Override
        public CharSequence getApplicationLabel(@NonNull ApplicationInfo info) {
            if (info != null && info.nonLocalizedLabel != null) return info.nonLocalizedLabel;
            return super.getApplicationLabel(info);
        }

        @Override
        public PackageInfo getPackageInfo(@NonNull String packageName, int flags) {
            if (self(packageName)) return info(flags);
            return super.getPackageInfo(packageName, flags);
        }

        @RequiresApi(Build.VERSION_CODES.O)
        @Override
        public PackageInfo getPackageInfo(@NonNull VersionedPackage versionedPackage, int flags) {
            if (self(versionedPackage.getPackageName())) return info(flags);
            return super.getPackageInfo(versionedPackage, flags);
        }

        @NonNull
        @Override
        public List<PackageInfo> getInstalledPackages(int flags) {
            try {
                return real.getInstalledPackages(flags);
            } catch (Throwable e) {
                return super.getInstalledPackages(flags);
            }
        }

        @NonNull
        @Override
        public List<ApplicationInfo> getInstalledApplications(int flags) {
            try {
                return real.getInstalledApplications(flags);
            } catch (Throwable e) {
                return super.getInstalledApplications(flags);
            }
        }

        @Override
        public boolean hasSystemFeature(@NonNull String featureName) {
            try {
                return real.hasSystemFeature(featureName);
            } catch (Throwable e) {
                return false;
            }
        }

        @Override
        public boolean hasSystemFeature(@NonNull String featureName, int version) {
            try {
                return real.hasSystemFeature(featureName, version);
            } catch (Throwable e) {
                return false;
            }
        }

        @Override
        public Intent getLaunchIntentForPackage(@NonNull String packageName) {
            try {
                return real.getLaunchIntentForPackage(packageName);
            } catch (Throwable e) {
                return null;
            }
        }
    }
}
