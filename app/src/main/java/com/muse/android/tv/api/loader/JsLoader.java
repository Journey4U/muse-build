package com.muse.android.tv.api.loader;

import com.muse.android.tv.App;
import com.muse.quickjs.crawler.Loader;
import com.muse.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider spider = spiders.get(key);
        if (spider != null) return spider;
        Spider created;
        try {
            created = loader.spider(api, BaseLoader.get().dex(jar));
            created.siteKey = key;
            created.init(App.get(), ext);
        } catch (Throwable e) {
            // 失败不缓存：瞬时失败（脚本加载、init 异常）不得让站点在本进程内永久失效，
            // 下次打开自动重试；失败原因写进应用内蜘蛛日志，不再只 printStackTrace 静默。
            SpiderDebug.log("js-spider", "create failed key=" + key + " api=" + api);
            SpiderDebug.log("js-spider", e);
            return new SpiderNull();
        }
        // 创建在缓存锁外完成：某蜘蛛 init 缓慢或卡住，不再堵住其他站点的存取；
        // 并发创建同一站时只有先放入者生效，后到者丢弃自建实例、返回已缓存者。
        Spider prev = spiders.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
