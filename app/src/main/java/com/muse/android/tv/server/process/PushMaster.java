package com.muse.android.tv.server.process;

import com.muse.android.tv.server.impl.Process;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * push v43/v25（推送多画质）：壳内合成的多画质主清单（站方把各分辨率给成独立地址时，
 * 由 PushMultiRes 合成）经本机服务以 http://127.0.0.1:端口/pushmaster/N.m3u8 交给播放器——
 * Exo 与 MPV（MpvHlsProxy 只代理 http 地址）都按普通 HLS 主清单处理，子清单仍走各自原有的取流与智能去广告。
 * 只存内存、最多 16 份，进程重启即失效（播放器回落原单档地址由调用方负责）。
 */
public class PushMaster implements Process {

    private static final String PREFIX = "/pushmaster/";
    private static final String MIME_M3U8 = "application/vnd.apple.mpegurl";
    private static final int MAX = 16;
    private static final AtomicLong NEXT = new AtomicLong();
    private static final Map<String, String> STORE = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX;
        }
    };

    /** 存一份主清单，返回本机路径（/pushmaster/N.m3u8）。 */
    public static String put(String text) {
        String path = PREFIX + NEXT.incrementAndGet() + ".m3u8";
        synchronized (STORE) {
            STORE.put(path, text);
        }
        return path;
    }

    public static boolean isLocalMaster(String url) {
        return url != null && url.contains("127.0.0.1") && url.contains(PREFIX);
    }

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(PREFIX);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        String text;
        synchronized (STORE) {
            text = STORE.get(url);
        }
        if (text == null) return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found");
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        Response res = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_M3U8, new java.io.ByteArrayInputStream(data), data.length);
        res.addHeader("Cache-Control", "no-cache");
        return res;
    }
}
