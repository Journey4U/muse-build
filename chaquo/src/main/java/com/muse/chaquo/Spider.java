package com.muse.chaquo;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Spider extends com.github.catvod.crawler.Spider {

    private final PyObject app;
    private final PyObject obj;
    private final String api;
    private final Gson gson;

    public Spider(PyObject app, PyObject obj, String api) {
        this.gson = new Gson();
        this.app = app;
        this.obj = obj;
        this.api = api;
    }

    @Override
    public void init(Context context, String extend) {
        PyObject dependence = app.callAttr("getDependence", obj);
        if (dependence != null) for (PyObject item : dependence.asList()) download(item + ".py");
        obj.put("siteKey", siteKey);
        app.callAttr("init", obj, extend);
        startCfProxyFromJava(context, extend);
    }

    // Ported from MuseTV 5.6.3 (com.fongmi.chaquo.Spider): spiders whose ext
    // carries a "cfgo" companion binary (e.g. MissAV's cf-proxy) expect a local
    // proxy on port 12525. After the Python init, resolve the binary path from
    // cfgo, copy it into filesDir, start it from Java and report the outcome
    // back into the Python object (cfproxy / one_mark).
    private void startCfProxyFromJava(Context context, String extend) {
        if (extend == null) return;
        try {
            if (!extend.contains("cfgo")) return;
            try {
                JsonObject json = JsonParser.parseString(extend).getAsJsonObject();
                if (!json.has("cfgo")) return;
                String cfgo = json.get("cfgo").getAsString();
                if (cfgo == null || cfgo.isEmpty()) return;
                if (isPortOpen(12525)) {
                    setProxyState(true, null);
                    return;
                }
                String path = resolveCfgoPath(cfgo);
                if (path == null) return;
                File source = new File(path);
                if (!source.isFile()) return;
                File target = new File(context.getFilesDir(), source.getName());
                if (!target.isFile() || target.length() != source.length()) {
                    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                target.setExecutable(true);
                ArrayList<String> command = new ArrayList<>();
                command.add(target.getAbsolutePath());
                command.add("-port");
                command.add("12525");
                try {
                    if (json.has("proxy")) {
                        JsonObject proxy = json.getAsJsonObject("proxy");
                        if (proxy.has("http")) {
                            String http = proxy.get("http").getAsString();
                            if (http != null && !http.isEmpty()) {
                                command.add("-proxy");
                                command.add(http);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                new ProcessBuilder(command).directory(target.getParentFile()).start();
                boolean started = true;
                for (int i = 0; i < 15; i++) {
                    try {
                        Thread.sleep(200L);
                        if (isPortOpen(12525)) break;
                    } catch (InterruptedException ignored) {
                    }
                    if (i == 14) started = false;
                }
                setProxyState(started, started ? null : "Java 启动后端口未监听");
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            setProxyState(false, e.getMessage());
        }
    }

    private String resolveCfgoPath(String value) {
        if (!value.startsWith("http://127.0.0.1") && !value.startsWith("http://localhost")) {
            if (value.startsWith("file://")) {
                String path = value.substring(7);
                return path.startsWith("/") ? path : new File(Path.root(), path).getAbsolutePath();
            }
            if (value.startsWith("/")) return value;
            if (value.startsWith("/file/")) return new File(Path.root(), value.substring(6)).getAbsolutePath();
            return null;
        }
        int index = value.indexOf("/file/");
        if (index < 0) return null;
        return new File(Path.root(), value.substring(index + 6)).getAbsolutePath();
    }

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void setProxyState(boolean success, String reason) {
        try {
            if (success) {
                obj.put("cfproxy", "http://127.0.0.1:12525?url=");
                obj.put("one_mark", "✅ 代理已启动:12525");
            } else if (reason != null) {
                obj.put("one_mark", "❌ Java启动失败:" + reason);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) {
        return app.callAttr("homeContent", obj, filter).toString();
    }

    @Override
    public String homeVideoContent() {
        return app.callAttr("homeVideoContent", obj).toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return app.callAttr("categoryContent", obj, tid, pg, filter, gson.toJson(extend)).toString();
    }

    @Override
    public String detailContent(List<String> ids) {
        return app.callAttr("detailContent", obj, gson.toJson(ids)).toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return app.callAttr("searchContent", obj, key, quick).toString();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return app.callAttr("searchContent", obj, key, quick, pg).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return app.callAttr("playerContent", obj, flag, id, gson.toJson(vipFlags)).toString();
    }

    @Override
    public String liveContent(String url) {
        return app.callAttr("liveContent", obj, url).toString();
    }

    @Override
    public boolean manualVideoCheck() {
        return app.callAttr("manualVideoCheck", obj).toBoolean();
    }

    @Override
    public boolean isVideoFormat(String url) {
        return app.callAttr("isVideoFormat", obj, url).toBoolean();
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        List<PyObject> list = app.callAttr("localProxy", obj, gson.toJson(params)).asList();
        boolean base64 = list.size() > 4 && list.get(4).toInt() == 1;
        boolean header = list.size() > 3 && list.get(3) != null;
        Object[] result = new Object[4];
        result[0] = list.get(0).toInt();
        result[1] = list.get(1).toString();
        result[2] = getStream(list.get(2), base64);
        result[3] = header ? getHeader(list.get(3)) : null;
        return result;
    }

    @Override
    public String action(String action) {
        return app.callAttr("action", obj, action).toString();
    }

    @Override
    public void destroy() {
        try {
            app.callAttr("destroy", obj);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> getHeader(PyObject obj) {
        try {
            Map<String, String> header = new HashMap<>();
            for (Map.Entry<PyObject, PyObject> entry : obj.asMap().entrySet()) header.put(entry.getKey().toString(), entry.getValue().toString());
            return header;
        } catch (Exception e) {
            return null;
        }
    }

    private ByteArrayInputStream getStream(PyObject o, boolean base64) {
        if (o == null) return null;
        if (o.type().toString().contains("bytes")) return new ByteArrayInputStream(o.toJava(byte[].class));
        String content = o.toString();
        if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
        return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
    }

    private void download(String name) {
        String path = Path.py(name).getAbsolutePath();
        String url = UriUtil.resolve(api, name);
        app.callAttr("download", path, url);
    }
}
