package com.muse.android.tv.player;

import android.text.TextUtils;

import androidx.media3.common.MediaMetadata;

import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;
import com.muse.android.tv.bean.Vod;
import com.muse.android.tv.db.AppDatabase;
import com.muse.android.tv.player.media.PlaySpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「播放」日志分类的数据源（#51，按 Liu 的定义）：播放分类只收"正在播的这部影片"
 * 的条目——详情页信息（上映时间、导演、演员）加上实际播放地址；引擎/播放诊断
 * 流水不属于这里（已归「运行」，见 LogCategory）。
 *
 * 数据路径：SiteApi.detailContent 解析出 Vod 时，按 站点key@@@影片id 暂存一份
 * 详情快照；PlayerManager 每次把最终地址交给引擎起播时（setMediaItem 是全仓
 * 唯一漏斗：直起、解析回填、解码重试都经它），合并 PlaySpec（最终地址、片名与
 * 集名来自 MediaMetadata）与快照，写一条 PLAY 条目。取不到快照时降级为只写
 * 可得字段；缺项整行省略。时长没有结构化来源（Vod 模型无时长字段），不写。
 *
 * 条目经 DebugLogStore.addPlain 写入：该通道只做长度上限与控制字符转义、
 * 不做内容脱敏——播放地址正是这条日志的用途所在。内部重试（切解码等）会
 * 重复走 setMediaItem，用签名去重保证一次起播只写一条；stop()/clear() 后
 * 重置签名，重新播放同一集会再写一条。
 */
public final class PlayInfoLog {

    private static final String TAG = "playback-info";
    private static final int MAX_SNAPSHOTS = 16;

    private static final Map<String, Snapshot> SNAPSHOTS = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
            return size() > MAX_SNAPSHOTS;
        }
    };

    private static String lastSignature;

    private record Snapshot(String siteName, String name, String year, String director, String actor) {
    }

    private PlayInfoLog() {
    }

    /** SiteApi.detailContent 每解析出一份详情 Vod 就调一次，刷新该片的快照。 */
    public static synchronized void remember(String siteKey, String siteName, Vod vod) {
        try {
            if (vod == null || TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vod.getId())) return;
            SNAPSHOTS.put(siteKey + AppDatabase.SYMBOL + vod.getId(), new Snapshot(
                    clean(siteName), clean(vod.getName()), clean(vod.getYear()),
                    clean(vod.getDirector()), clean(vod.getActor())));
        } catch (Throwable ignored) {
            // Diagnostics must never break detail loading.
        }
    }

    /** PlayerManager 在起播漏斗里调用；rawUrl 是解析前的原始地址，可为 null。 */
    public static synchronized void logStart(PlaySpec spec, String rawUrl) {
        try {
            if (spec == null || TextUtils.isEmpty(spec.getUrl())) return;
            String url = spec.getUrl();
            String key = spec.getKey() == null ? "" : spec.getKey();
            String[] parts = key.split(AppDatabase.SYMBOL, -1);
            String siteKey = parts.length > 0 ? parts[0] : key;
            String vodId = parts.length > 1 ? parts[1] : "";
            Snapshot snapshot = SNAPSHOTS.get(siteKey + AppDatabase.SYMBOL + vodId);
            MediaMetadata metadata = spec.getMetadata();
            String title = metadata == null || metadata.title == null ? "" : metadata.title.toString();
            String episode = metadata == null || metadata.artist == null ? "" : metadata.artist.toString();
            String name = snapshot != null && !snapshot.name().isEmpty() ? snapshot.name() : title;
            String signature = siteKey + '|' + vodId + '|' + name + '|' + episode + '|' + url;
            if (signature.equals(lastSignature)) return;
            lastSignature = signature;
            StringBuilder text = new StringBuilder();
            text.append("影片：").append(name.isEmpty() ? "（未知）" : name);
            if (!episode.isEmpty() && !episode.equals(name)) text.append("（").append(episode).append("）");
            String site = snapshot != null && !snapshot.siteName().isEmpty() ? snapshot.siteName() : siteKey;
            if (!site.isEmpty()) text.append("\n站点：").append(site);
            if (snapshot != null) {
                if (!snapshot.year().isEmpty()) text.append("\n上映时间：").append(snapshot.year());
                if (!snapshot.director().isEmpty()) text.append("\n导演：").append(snapshot.director());
                if (!snapshot.actor().isEmpty()) text.append("\n演员：").append(snapshot.actor());
            }
            text.append("\n播放地址：").append(url);
            if (!TextUtils.isEmpty(rawUrl) && !rawUrl.equals(url)) text.append("\n原始地址：").append(rawUrl);
            DebugLogStore.addPlain(LogCategory.PLAY, TAG, text.toString());
        } catch (Throwable ignored) {
            // Diagnostics must never break playback.
        }
    }

    /** stop()/clear() 后调用：同一集再次起播时重新写一条。 */
    public static synchronized void resetLast() {
        lastSignature = null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
