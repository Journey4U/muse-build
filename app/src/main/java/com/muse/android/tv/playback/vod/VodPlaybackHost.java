package com.muse.android.tv.playback.vod;

import androidx.media3.common.MediaMetadata;

import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.bean.Flag;
import com.muse.android.tv.bean.History;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Vod;

import java.util.List;

public interface VodPlaybackHost {

    String getVodKey();

    String getVodId();

    void setVodId(String id);

    String getVodName();

    String getVodPic();

    String getVodMark();

    /** 跨类型切集直达的目标集序号（未指定为 -1）：新开详情定位选集时优先按序号，不靠集名匹配。 */
    default int getEpisodeIndex() {
        return -1;
    }

    /** push v2：解析浏览器全集未解析集（空地址 + 播放页地址，走 HTTP 解析器）。返回 true 表示宿主已接手（异步解析后自行续播），控制器不再进 playerContent；默认不认。 */
    default boolean resolveWebEpisode(Flag flag, Episode episode) {
        return false;
    }

    /** push v5：推送集限时流过期报错后的重取恢复（手机版 VideoActivity 实现；默认不认，走正常报错路）。返回 true 表示宿主已接手重取（异步回调里自行续播或回落报错路），控制器暂缓 reset 与 fallback；errorMsg 供重取失败时回落原样报错。 */
    default boolean resolveWebEpisodeForErrorRecovery(Flag flag, Episode episode, String errorMsg) {
        return false;
    }

    String getHistoryKey();

    boolean isSiteChangeable();

    boolean isFromCollect();

    boolean isHostFinishing();

    boolean isPlayerEmpty();

    boolean hasPlaybackSession();

    boolean isFullscreenForPlayback();

    boolean isLivePlayback();

    boolean canTrackPlaybackProgress();

    boolean canPreloadNext();

    long getPlayerPosition();

    long getPlayerDuration();

    void usePushId(String id);

    void onPlaybackRequested();

    void prepareSource(Vod item);

    void stopPlaybackForRefresh();

    void resetPlaybackForError(String msg);

    void replay(long position);

    void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata);

    boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata);

    void clearPreload();

    void loadDanmaku(Result result, History history, Episode episode);

    void renderDetail(Vod item, History history);

    void renderVodUpdate(Vod item);

    void renderEmptyDetail();

    void renderFallbackName(String name);

    void renderFlags(List<Flag> items);

    void renderEpisodes(List<Episode> items);

    void renderFlagSelection(Flag item);

    void renderEpisodeSelection(Episode item);

    void renderReverseEpisodes(List<Episode> items, boolean scroll);

    void renderQuality(Result result, boolean visible);

    void renderQualityVisible(boolean visible);

    void renderSources(List<Vod> items);

    void renderHistory(History history);

    void renderUseParse(boolean useParse);

    void renderArtwork(String url);

    void renderDescription(String desc);

    void renderPlaybackMetadata(MediaMetadata metadata);

    void onDetailFallbackScheduled();

    void onDetailFallbackCancelled();

    void onSearchStarted(String keyword);

    void onSearchResult();

    void showDetailMessage(String msg);

    void showSwitchLine(Flag flag);

    void showSwitchSource(Vod item);

    void showEpisodeReady(Episode item);

    void showNoNext(boolean reversed);

    void showNoPrev(boolean reversed);

    void finishVod();
}
