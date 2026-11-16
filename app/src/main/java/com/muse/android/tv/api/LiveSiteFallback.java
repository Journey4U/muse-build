package com.muse.android.tv.api;

import android.text.TextUtils;

import com.muse.android.tv.App;
import com.muse.android.tv.api.loader.BaseLoader;
import com.muse.android.tv.api.parser.LiveParser;
import com.muse.android.tv.bean.Channel;
import com.muse.android.tv.bean.Class;
import com.muse.android.tv.bean.Group;
import com.muse.android.tv.bean.Live;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.bean.Vod;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播转点播：type-3 站点的蜘蛛主页全空时（引擎型直播源，站点接口为空、频道只走本机代理订阅），
 * 探测其 localProxy 订阅（type=link / type=all），用 LiveParser 解析成分组与频道，
 * 拼成站点主页、分类与详情；播放仍走蜘蛛自己的 playerContent（代理频道以订阅链接里的 v 参数为集 id，
 * 直链频道以地址本身为集 id）。探测失败或拿不到订阅时一律返回 null，调用方维持原空结果。
 */
public class LiveSiteFallback {

    public static final String PREFIX = "lv:";

    private static final Map<String, List<Group>> CACHE = new ConcurrentHashMap<>();

    public static boolean isLiveType(String tid) {
        return tid != null && tid.startsWith(PREFIX);
    }

    public static boolean isLiveId(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    public static Result home(Site site) {
        List<Group> groups = probe(site, true);
        if (groups == null) return null;
        Result result = new Result();
        List<Class> types = new ArrayList<>();
        List<Vod> list = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            Class type = new Class();
            type.setTypeId(PREFIX + i);
            type.setTypeName(name(group));
            types.add(type);
            List<Channel> channels = group.getChannel();
            for (int j = 0; j < channels.size(); j++) list.add(item(channels.get(j), i, j));
        }
        if (types.isEmpty() || list.isEmpty()) return null;
        result.setTypes(types);
        result.setList(list);
        return result;
    }

    public static Result category(Site site, String tid) {
        int index = parseIndex(tid);
        List<Group> groups = probe(site, false);
        if (groups == null || index < 0 || index >= groups.size()) return null;
        List<Channel> channels = groups.get(index).getChannel();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int j = 0; j < channels.size(); j++) {
            Vod vod = item(channels.get(j), index, j);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("vod_id", vod.getId());
            map.put("vod_name", vod.getName());
            map.put("vod_pic", vod.getPic());
            items.add(map);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("list", items);
        root.put("page", 1);
        root.put("pagecount", 1);
        root.put("limit", items.size());
        root.put("total", items.size());
        return Result.fromJson(App.gson().toJson(root));
    }

    public static Result detail(Site site, String id) {
        String[] parts = id.substring(PREFIX.length()).split(":");
        if (parts.length != 2) return null;
        int gi, ci;
        try {
            gi = Integer.parseInt(parts[0]);
            ci = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return null;
        }
        List<Group> groups = probe(site, false);
        if (groups == null || gi < 0 || gi >= groups.size()) return null;
        List<Channel> channels = groups.get(gi).getChannel();
        if (ci < 0 || ci >= channels.size()) return null;
        Channel channel = channels.get(ci);
        List<String> urls = channel.getUrls();
        if (urls == null || urls.isEmpty()) return null;
        Vod vod = new Vod();
        vod.setId(id);
        vod.setName(channel.getName());
        vod.setPic(channel.getLogo());
        vod.setPlayFrom("直播");
        List<String> episodes = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            String name = urls.size() > 1 ? "线路" + (i + 1) : "直播";
            episodes.add(name + "$" + episodeId(urls.get(i)));
        }
        vod.setPlayUrl(TextUtils.join("#", episodes));
        return Result.vod(vod);
    }

    private static Vod item(Channel channel, int gi, int ci) {
        Vod vod = new Vod();
        vod.setId(PREFIX + gi + ":" + ci);
        vod.setName(channel.getName());
        vod.setPic(channel.getLogo());
        return vod;
    }

    private static String name(Group group) {
        return group.getName().isEmpty() ? "全部频道" : group.getName();
    }

    private static int parseIndex(String tid) {
        try {
            return Integer.parseInt(tid.substring(PREFIX.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String episodeId(String url) {
        String v = query(url, "v");
        return TextUtils.isEmpty(v) ? url : v;
    }

    private static String query(String url, String key) {
        try {
            int q = url.indexOf('?');
            if (q < 0) return "";
            for (String pair : url.substring(q + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) return URLDecoder.decode(kv[1], "UTF-8");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static List<Group> probe(Site site, boolean refresh) {
        String key = site.getKey();
        if (!refresh) {
            List<Group> cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        for (String type : new String[]{"link", "all"}) {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("siteKey", key);
                params.put("type", type);
                Object[] resp = BaseLoader.get().proxy(params);
                if (resp == null || resp.length < 3 || !(resp[0] instanceof Number) || ((Number) resp[0]).intValue() != 200) continue;
                if (!(resp[2] instanceof InputStream)) continue;
                String text = read((InputStream) resp[2]);
                if (text.isEmpty()) continue;
                Live live = new Live();
                LiveParser.text(live, text);
                List<Group> groups = new ArrayList<>();
                for (Group group : live.getGroups()) if (!group.getChannel().isEmpty()) groups.add(group);
                if (groups.isEmpty()) continue;
                CACHE.put(key, groups);
                return groups;
            } catch (Throwable ignored) {
            }
        }
        return CACHE.get(key);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
