package com.github.catvod.net.interceptor;

import androidx.annotation.NonNull;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 请求日志（源调试用）：日志调试开启时，经壳 client() 发出的每条请求记一行
 * 「方法 完整网址 -> 状态码（耗时）」进日志仓蜘蛛类。用明文条目（addPlain）写入：
 * 网址经普通蜘蛛通道会被脱敏成 uri:[redacted]，源调试就没法核对了；本拦截器是
 * 壳自身代码、记的是壳自己发出的请求，与「正在播放」条目同属第一方明文。
 * 只记一行，不记请求头与体；回环地址与媒体分片（.ts/.m4s/.key）不记，防刷屏。
 * 播放器 client（player()）不挂本拦截器。
 */
public class RequestLogInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (!DebugLogStore.isEnabled() || skip(request)) return chain.proceed(request);
        long start = System.nanoTime();
        try {
            Response response = chain.proceed(request);
            write(request, " -> " + response.code(), start);
            return response;
        } catch (IOException e) {
            write(request, " -> 失败 " + e.getClass().getSimpleName(), start);
            throw e;
        }
    }

    private boolean skip(Request request) {
        String host = request.url().host();
        if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1")) return true;
        String path = request.url().encodedPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".key");
    }

    private void write(Request request, String tail, long startNanos) {
        try {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            DebugLogStore.addPlain(LogCategory.SPIDER, "spider-request",
                    request.method() + " " + request.url() + tail + " (" + ms + "ms)");
        } catch (Throwable ignored) {
        }
    }
}
