package com.muse.android.tv.playback.vod;

import android.text.TextUtils;
import android.webkit.CookieManager;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

import com.github.catvod.net.OkHttp;
import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.bean.Flag;
import com.muse.android.tv.bean.History;
import com.muse.android.tv.bean.Keep;
import com.muse.android.tv.bean.Parse;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Vod;
import com.muse.android.tv.playback.PlaybackResult;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.utils.Notify;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class VodPlaybackController {

    private static final String PUSH_PREFIX = "push://";
    private static final String SEARCH_PREFIX = "msearch:";

    // push v4：已存直链探活——与 HttpEpisodeResolver.probe 相同的超时/重定向上限。
    private static final long PROBE_TIMEOUT_MS = 9000;
    private static final int PROBE_MAX_REDIRECTS = 3;

    private final VodPlaybackHost host;
    private final VodDataSource dataSource;
    private final VodPlaybackState state;
    private final VodHistoryPolicy historyPolicy;
    private final VodFallbackPolicy fallbackPolicy;
    private final VodPreloader preloader;
    private History lastHistory;
    private Runnable pendingAutoPlay;
    private Episode pendingEpisode;
    private boolean forcePlayOnce;
    // push v5：过期流报错恢复守卫——一次报错只重取一次。重取的新流须实播满 5 秒（onTimeChanged 计）
    // 才重新武装，否则新流立报错会无限重取；pushRecoveryEpisode 记本次恢复的集，换集即清守卫。
    private boolean pushErrorRecoveryTried;
    private Episode pushRecoveryEpisode;
    // push v15：v4 探活重解析守卫——同一集只重解析一次（重解析回来的仍是死链时不再空转，
    // 直接放原地址、由 v5 报错恢复接手）。换集 / 探活通过 / 报错恢复清零时一并清。
    private boolean probeReparseTried;
    private Episode probeReparseEpisode;
    private long pushErrorResumePosition = C.TIME_UNSET;
    private long pushRecoveryBasePosition = C.TIME_UNSET;

    public VodPlaybackController(VodPlaybackHost host, VodDataSource dataSource, VodPlaybackState state) {
        this.host = host;
        this.dataSource = dataSource;
        this.state = state;
        this.historyPolicy = new VodHistoryPolicy();
        this.fallbackPolicy = new VodFallbackPolicy(this, state, host, dataSource);
        this.preloader = new VodPreloader(host, dataSource, state);
    }

    public void reset() {
        cancelAutoPlay();
        clearPushErrorRecovery();
        preloader.clear();
        state.reset();
    }

    /** 阅读器内明确选中的视频集跳回播放页时置位：无视自动播放开关与延时，进详情直接播这一集（只生效一次）。 */
    public void setForcePlayOnce(boolean forcePlayOnce) {
        this.forcePlayOnce = forcePlayOnce;
    }

    private void cancelAutoPlay() {
        if (pendingAutoPlay != null) App.removeCallbacks(pendingAutoPlay);
        pendingAutoPlay = null;
        pendingEpisode = null;
    }

    private void scheduleAutoPlay(Episode episode, int delaySeconds) {
        cancelAutoPlay();
        pendingEpisode = episode;
        pendingAutoPlay = () -> {
            Episode target = pendingEpisode;
            cancelAutoPlay();
            if (target != null && target.isSelected() && !host.isHostFinishing() && state.getPlayingRequest() == null) selectEpisode(target);
        };
        App.post(pendingAutoPlay, delaySeconds * 1000L);
    }

    /**
     * #104：中央播放键——立即播放当前选中集。
     * 延时读秒待播时取消读秒、抢先播这一集（点击优先于自动播放延时）；
     * 已有播放请求在途时不重复发起；详情未加载完、无可选集时返回 false，宿主走原有刷新。
     * 自动播放关闭时播的是已被标记选中的那一集（state.getEpisode）。
     */
    public boolean playSelected() {
        if (pendingEpisode != null) {
            selectEpisode(pendingEpisode);
            return true;
        }
        if (state.getPendingRequest() != null) return true;
        if (!state.hasEpisode()) return false;
        selectEpisode(state.getEpisode());
        return true;
    }

    /** 自动播放关闭时只标记选中（高亮+写历史），不真正开播，等用户点集。 */
    private void markEpisodeSelected(Flag flag, Episode episode) {
        for (Flag item : state.getFlags()) item.toggle(item == flag, episode);
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        host.renderEpisodeSelection(episode);
    }

    public void checkId() {
        String id = resolveVodId();
        if (id.isEmpty() || id.startsWith(SEARCH_PREFIX)) detailEmpty(false);
        else if (!state.isDetailRequested(host.getVodKey(), id)) requestDetail();
    }

    private String resolveVodId() {
        String id = host.getVodId();
        if (!id.startsWith(PUSH_PREFIX)) return id;
        host.usePushId(id.substring(PUSH_PREFIX.length()));
        return host.getVodId();
    }

    public void requestDetail() {
        String key = host.getVodKey();
        String id = host.getVodId();
        state.setDetailRequest(key, id);
        dataSource.detailContent(key, id);
    }

    public void onDetailResult(VodDetailResult detail) {
        if (detail == null || !detail.matches(host.getVodKey(), host.getVodId())) return;
        Result result = detail.result();
        if (result.getList().isEmpty()) detailEmpty(result.hasMsg());
        else detailLoaded(result.getVod());
        host.showDetailMessage(result.getMsg());
    }

    public void updateVod(Vod item) {
        if (preloader.isRequestPending()) return;
        History history = state.getHistory();
        replaceVodId(history, item.getId());
        mergeFlags(item.getFlags());
        updateHistory(history, item);
        host.renderVodUpdate(item);
        publishActivePlaybackMetadata();
    }

    private void updateHistory(History history, Vod item) {
        String pic = item.getPic();
        String name = item.getName();
        boolean hasPic = !pic.isEmpty();
        boolean hasName = !name.isEmpty();
        if (hasPic) history.setVodPic(pic);
        if (hasName) history.setVodName(name);
        if (hasName || hasPic) historyPolicy.saveCurrent(history);
    }

    private void publishActivePlaybackMetadata() {
        Episode episode = findEpisode(state.getActiveRequest());
        if (episode != null) publishPlaybackMetadata(episode);
    }

    private void replaceVodId(History history, String id) {
        if (id.isEmpty() || id.equals(host.getVodId())) return;
        String oldKey = host.getHistoryKey();
        host.setVodId(id);
        String newKey = host.getHistoryKey();
        history.replace(newKey);
        Keep.replace(oldKey, newKey);
    }

    public void onPlaybackResult(PlaybackResult<VodPlayRequest> playback) {
        if (playback == null || cannotApply(playback)) return;
        applyPlaybackResult(playback.result(), playback.request());
    }

    private void applyPlaybackResult(Result result, VodPlayRequest request) {
        Episode episode = findEpisode(request);
        if (episode == null) return;
        applyPlaybackState(result, request);
        renderPlaybackResult(result);
        updatePlaybackPosition(result);
        host.loadDanmaku(result, state.getHistory(), episode);
        // push v4：新集地址已确认可播，在此才停掉旧流（原先 playEpisode 里先停播，解析失败会杀掉正在播的流）。
        host.stopPlaybackForRefresh();
        // push v5：过期流报错恢复——用报错时捕获的位置续播（不用历史位：历史位可能已被结果位覆盖或滞后）；只用一次。
        long startPosition = pushErrorResumePosition != C.TIME_UNSET ? pushErrorResumePosition : startPositionMs();
        pushErrorResumePosition = C.TIME_UNSET;
        startPlayback(result, startPosition, episode);
        preloader.update(result);
    }

    private void applyPlaybackState(Result result, VodPlayRequest request) {
        state.setQuality(result);
        state.setPlayingRequest(request);
        state.setUseParse(result.isUseParse());
        result.getUrl().set(state.getQualityPosition());
    }

    private void renderPlaybackResult(Result result) {
        host.renderUseParse(state.isUseParse());
        host.renderQuality(result, result.getUrl().isMulti());
        if (result.hasDesc()) host.renderDescription(result.getDesc());
        if (result.hasArtwork()) host.renderArtwork(result.getArtwork());
    }

    private void updatePlaybackPosition(Result result) {
        if (result.hasPosition()) state.getHistory().setPosition(result.getPosition());
    }

    private void startPlayback(Result result, long startPositionMs, Episode episode) {
        MediaMetadata metadata = publishPlaybackMetadata(episode);
        host.startPlayback(result, state.isUseParse(), startPositionMs, metadata);
    }

    public void onSearchResult(Result result) {
        fallbackPolicy.onSearchResult(result);
    }

    public void selectFlag(Flag item) {
        selectFlag(item, false);
    }

    private void selectFlag(Flag item, boolean force) {
        if (!state.hasFlags()) return;
        Flag selected = resolveFlag(item);
        if (!force && selected.isSelected()) return;
        preloader.clear();
        for (Flag flag : state.getFlags()) flag.setSelected(selected);
        host.renderFlagSelection(selected);
        host.renderEpisodes(selected.getEpisodes());
        host.renderQualityVisible(false);
        seamless(selected, force);
    }

    public void selectEpisode(Episode item) {
        if (!state.hasFlags()) return;
        cancelAutoPlay();
        // push v5：换到别的集即清报错恢复守卫（恢复中的旧集重取结果不再续播该集，见 VideoActivity 恢复回调的 isSelected 守卫）。
        // push v15：探活重解析守卫同理，换集即清。
        if ((pushRecoveryEpisode != null && item != pushRecoveryEpisode)
                || (probeReparseEpisode != null && item != probeReparseEpisode)) clearPushErrorRecovery();
        saveCurrentHistory();
        Flag selected = state.getFlag();
        for (Flag flag : state.getFlags()) flag.toggle(flag == selected, item);
        historyPolicy.updateEpisode(state.getHistory(), state.getFlag(), item);
        host.renderEpisodeSelection(item);
        if (host.isFullscreenForPlayback()) host.showEpisodeReady(item);
        playEpisode(item);
    }

    /** push v6 Layer 1：浏览器解析回调只重跑 playEpisode，不重进 selectEpisode——该集已选中，重进会再跑 saveCurrentHistory（把仍在播的旧集位置写进新集槽）+ updateEpisode（history 集名已是新集，不重置位置）。解析中用户已切走别的集时不回切（isSelected 守卫）。 */
    public void playResolvedEpisode(Episode item) {
        if (item == null || !item.isSelected()) return;
        playEpisode(item);
    }

    private void playEpisode(Episode item) {
        Result result = preloader.consume(item);
        // push v4：切集前不再先停播——解析失败（或探活失败）时旧流继续播；新地址确认可播后才在 applyPlaybackResult 里停旧流。
        if (result == null) requestSelectedEpisode();
        else applyPlaybackResult(result, VodPlayRequest.create(host.getVodKey(), state.getFlag(), item));
    }

    public void selectQuality(Result result) {
        if (!state.hasEpisode()) return;
        state.setQuality(result);
        state.setQualityPosition(result.getUrl().getPosition());
        preloader.clear();
        startPlayback(result, host.getPlayerPosition(), state.getEpisode());
        preloader.preloadNext();
    }

    public void selectParse(Parse item) {
        VodConfig.get().setParse(item);
        refresh();
    }

    private void mergeFlags(List<Flag> items) {
        if (items.isEmpty()) return;
        if (state.hasFlags()) {
            Flag activated = state.getFlag();
            for (Flag item : items) mergeFlag(activated, item);
            host.renderFlags(state.getFlags());
        } else {
            state.setFlags(items);
            host.renderFlags(state.getFlags());
        }
    }

    public void selectSource(Vod item) {
        switchSource(item, false);
    }

    void fallbackSource(Vod item) {
        switchSource(item, true);
    }

    private void switchSource(Vod item, boolean autoFallback) {
        cancelAutoPlay();
        state.setAutoFallback(autoFallback);
        saveCurrentHistory();
        preloader.clear();
        state.clearPlayRequest();
        host.prepareSource(item);
        requestDetail();
    }

    public void search(String keyword) {
        fallbackPolicy.search(keyword, false);
    }

    public void manualSwitchSource() {
        fallbackPolicy.manualSwitchSource();
    }

    public void playbackError(String msg) {
        preloader.clear();
        // push v5：推送集限时流过期报错——先重取新鲜流续播；重取失败才走正常报错路（reset + showError + fallback）。
        if (tryPushErrorRecovery(msg)) return;
        host.resetPlaybackForError(msg);
        fallbackPolicy.playbackError();
    }

    /**
     * push v5：过期流报错恢复。当前集是推送集（pageUrl 非空）且本次报错尚未重取过时，
     * 捕获报错位置、经宿主走 HttpEpisodeResolver 重取新鲜流；返回 true 表示已接手恢复
     * （reset 与 fallback 均暂缓，等异步回调定夺），false 表示走正常报错路。
     * 宿主不认（返回 false）时清守卫、回落正常路；重取失败时宿主再调 playbackError，
     * 此时守卫已置位，直接走正常路，不会再次重取。
     */
    /** push v15：是否 HTTP 错（含 "http" / 响应码字样 / 4xx·5xx 状态码，如 403/404/410/503）。 */
    private static boolean isHttpError(String msg) {
        if (TextUtils.isEmpty(msg)) return false;
        String m = msg.toLowerCase(Locale.US);
        if (m.contains("http") || m.contains("response code") || m.contains("status code") || m.contains("bad status")) return true;
        return java.util.regex.Pattern.compile("(^|[^0-9])(400|401|403|404|405|408|410|429|500|502|503|504)([^0-9]|$)").matcher(m).find();
    }

    private boolean tryPushErrorRecovery(String msg) {
        if (pushErrorRecoveryTried || !state.hasEpisode()) return false;
        // push v15：只对 HTTP 错重取（过期签名 403/410、“Bad HTTP Status”等）——
        // 解码/网络中断等非 HTTP 报错直接走正常报错路（v5 原先任何报错都重取，现按任务收窄）。
        if (!isHttpError(msg)) return false;
        Episode episode = state.getEpisode();
        if (episode == null || TextUtils.isEmpty(episode.getPageUrl())) return false;
        pushErrorRecoveryTried = true;
        pushRecoveryEpisode = episode;
        long position = host.getPlayerPosition();
        pushErrorResumePosition = position;
        pushRecoveryBasePosition = position;
        if (!host.resolveWebEpisodeForErrorRecovery(state.getFlag(), episode, msg)) {
            clearPushErrorRecovery();
            return false;
        }
        return true;
    }

    private void clearPushErrorRecovery() {
        pushErrorRecoveryTried = false;
        pushRecoveryEpisode = null;
        probeReparseTried = false;
        probeReparseEpisode = null;
        pushErrorResumePosition = C.TIME_UNSET;
        pushRecoveryBasePosition = C.TIME_UNSET;
    }

    public void playbackEnded() {
        nextEpisode(true);
    }

    public void replay() {
        if (state.getHistory() != null) state.getHistory().setPosition(C.TIME_UNSET);
        if (host.isPlayerEmpty()) refresh();
        else host.replay(startPositionMs());
    }

    public void refresh() {
        saveCurrentHistory();
        preloader.clear();
        host.stopPlaybackForRefresh();
        restorePlaybackSelection(state.getPlayingRequest());
        requestSelectedEpisode();
    }

    public void onPlaybackServiceReady() {
        MediaMetadata metadata = state.getPlaybackMetadata();
        if (metadata != null) host.renderPlaybackMetadata(metadata);
        VodPlayRequest request = state.getPlayingRequest();
        if (request == null || host.hasPlaybackSession()) return;
        restorePlaybackSelection(request);
        requestSelectedEpisode();
    }

    private void requestSelectedEpisode() {
        if (state.hasEpisode()) requestPlayer(state.getFlag(), state.getEpisode());
    }

    public void nextEpisode(boolean notify) {
        boolean reversed = state.getHistory() != null && state.getHistory().isRevPlay();
        moveEpisode(reversed ? -1 : 1, notify, reversed);
    }

    public void prevEpisode(boolean notify) {
        boolean reversed = state.getHistory() != null && state.getHistory().isRevPlay();
        moveEpisode(reversed ? 1 : -1, notify, reversed);
    }

    private void moveEpisode(int offset, boolean notify, boolean reversed) {
        if (!state.hasEpisode()) return;
        Episode item = state.getRelativeEpisode(offset);
        if (!item.isSelected()) selectEpisode(item);
        else if (notify && offset > 0) host.showNoNext(reversed);
        else if (notify) host.showNoPrev(reversed);
    }

    public void reverseEpisode(boolean scroll) {
        if (!state.hasFlags()) return;
        for (Flag flag : state.getFlags()) reverseEpisodes(flag);
        host.renderReverseEpisodes(state.getFlag().getEpisodes(), scroll);
    }

    private void reverseEpisodes(Flag flag) {
        List<Episode> episodes = flag.getEpisodes();
        int position = flag.getPosition();
        Collections.reverse(episodes);
        if (position >= 0 && position < episodes.size()) flag.setPosition(episodes.size() - position - 1);
    }

    private void saveCurrentHistory() {
        if (state.getPlayingRequest() == null || !host.canTrackPlaybackProgress()) historyPolicy.save(currentHistory());
        else saveHistory(false, System.currentTimeMillis(), host.getPlayerPosition(), host.getPlayerDuration());
    }

    public void saveHistory(boolean exit, long time, long position, long duration) {
        History history = exit ? historyForExit() : currentHistory();
        if (host.isLivePlayback()) historyPolicy.saveVisit(history, exit, time);
        else historyPolicy.saveProgress(history, exit, time, position, duration);
    }

    public void onTimeChanged(long time, long position, long duration) {
        History history = currentHistory();
        // push v6 Layer 1：切集解析窗口内旧集仍在播（停播已移到 applyPlaybackResult），1 秒时钟会把旧集位置写进新集共用的单 History 槽，新集就从旧位置起播；
        // 播的集与选的集不一致时跳过写历史，applyPlaybackResult 把 playingRequest 切到新集后再恢复写。playing 为空（未开播）时沿旧行为写。
        VodPlayRequest playing = state.getPlayingRequest();
        boolean sameEpisode = playing == null || (state.hasEpisode() && playing.matches(host.getVodKey(), state.getFlag(), state.getEpisode()));
        if (sameEpisode) historyPolicy.updateProgress(history, time, position, duration);
        // push v5：恢复的新流实播满 5 秒（位置越过报错点 5 秒）才重新武装报错恢复；
        // 新流立报错（未实播）时守卫仍在，不重取，防无限循环。
        if (pushErrorRecoveryTried && pushRecoveryBasePosition != C.TIME_UNSET && position > pushRecoveryBasePosition + 5000) clearPushErrorRecovery();
        if (history != null && history.getEnding() > 0 && history.getEnding() + position >= duration) nextEpisode(false);
    }

    public long startPositionMs() {
        return historyPolicy.startPositionMs(state.getHistory());
    }

    private History currentHistory() {
        History history = state.getHistory();
        if (history != null) lastHistory = history;
        return history;
    }

    private History historyForExit() {
        History history = currentHistory();
        return history == null ? lastHistory : history;
    }

    public void onPreloadResult(PlaybackResult<VodPlayRequest> preload) {
        preloader.onResult(preload);
    }

    public void setOpening(long opening) {
        if (state.getHistory() != null) state.getHistory().setOpening(opening);
    }

    public void setEnding(long ending) {
        if (state.getHistory() != null) state.getHistory().setEnding(ending);
    }

    public void setScale(int scale) {
        if (state.getHistory() != null) state.getHistory().setScale(scale);
    }

    public void setRevSort(boolean revSort) {
        if (state.getHistory() != null) state.getHistory().setRevSort(revSort);
    }

    public void setRevPlay(boolean revPlay) {
        if (state.getHistory() != null) state.getHistory().setRevPlay(revPlay);
    }

    private void detailEmpty(boolean shouldFinish) {
        if (shouldFinish || host.isFromCollect()) {
            // A：收藏路径、且后续不会再有非空提示时，退回前先弹一次“暂无播放数据”。
            // shouldFinish=true 说明 onDetailResult() 后还会 showDetailMessage(msg)（非空），
            // 这里再弹会双 Toast；shouldFinish=false 且 collect 时，后续只会
            // showDetailMessage("")（被 Notify 空串守卫丢掉），所以这里是唯一提示位。
            // 非收藏路径不进此分支，走下方空页/fallback，不弹。
            // Notify 用 App.get() 的 Toast，退回后仍能显示。
            if (!shouldFinish && host.isFromCollect()) Notify.show(R.string.error_detail);
            host.finishVod();
            return;
        }
        String name = host.getVodName();
        if (name.isEmpty()) {
            host.renderEmptyDetail();
        } else {
            host.renderFallbackName(name);
            host.onDetailFallbackScheduled();
            fallbackPolicy.emptyDetail();
        }
    }

    private void detailLoaded(Vod item) {
        VodPlayRequest activeRequest = state.getActiveRequest();
        History history = applyDetail(item);
        if (state.hasFlags()) restoreDetail(activeRequest, history);
        else fallbackPolicy.emptyFlag();
    }

    private History applyDetail(Vod item) {
        item.checkPic(host.getVodPic());
        item.checkName(host.getVodName());
        List<Flag> flags = item.getFlags();
        state.setFlags(flags);
        History history = historyPolicy.findOrCreate(host.getHistoryKey(), host.getVodMark(), item);
        state.setHistory(history);
        lastHistory = history;
        host.renderDetail(item, history);
        host.renderFlags(flags);
        host.renderHistory(history);
        host.onDetailFallbackCancelled();
        return history;
    }

    private void restoreDetail(VodPlayRequest activeRequest, History history) {
        if (restorePlaybackSelection(activeRequest)) publishPlaybackMetadata(state.getEpisode());
        else selectFlag(history.getFlag(), true);
        if (history.isRevSort()) reverseEpisode(true);
        if (state.getPlayingRequest() != null && !host.hasPlaybackSession()) requestSelectedEpisode();
    }

    private boolean restorePlaybackSelection(VodPlayRequest request) {
        Flag flag = state.findFlag(host.getVodKey(), request);
        Episode episode = state.findEpisode(host.getVodKey(), flag, request);
        if (flag == null || episode == null) return false;
        for (Flag item : state.getFlags()) item.setSelected(flag);
        for (Flag item : state.getFlags()) item.toggle(item == flag, episode);
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        host.renderFlagSelection(flag);
        host.renderEpisodes(flag.getEpisodes());
        host.renderEpisodeSelection(episode);
        host.renderQualityVisible(state.getQuality().getUrl().isMulti());
        return true;
    }

    private void requestPlayer(Flag flag, Episode episode) {
        // push v2：浏览器待解析集（url 为空、pageUrl 为播放页地址）——不进 playerContent，交给宿主走纯 HTTP 解析器取直链；宿主不认（返回 false）才继续走正常播放。
        if (episode != null && TextUtils.isEmpty(episode.getUrl()) && !TextUtils.isEmpty(episode.getPageUrl()) && host.resolveWebEpisode(flag, episode)) return;
        // push v4：已存直链的推送集——直链可能是限时签名地址，过期后播了也是死链；先在后台探活，活着才直放，死了走重解析。
        // 只探 http(s) 直链：非 http 地址（如 novel:// / pics://）不进探活，直接走原直放路。
        if (episode != null && !TextUtils.isEmpty(episode.getUrl()) && episode.getUrl().startsWith("http") && !TextUtils.isEmpty(episode.getPageUrl())) {
            probeStoredStream(flag, episode);
            return;
        }
        requestPlayerContent(flag, episode);
    }

    // 原 requestPlayer 的直放体：写历史、建请求、走 playerContent。
    private void requestPlayerContent(Flag flag, Episode episode) {
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        VodPlayRequest request = VodPlayRequest.create(host.getVodKey(), flag, episode);
        state.setPendingRequest(request);
        publishPlaybackMetadata(episode);
        dataSource.playerContent(request);
        host.onPlaybackRequested();
    }

    // ===== push v4：已存直链探活 =====
    //
    // 注意：HttpEpisodeResolver.probe() 是私有实例方法，控制器调不到（改它可见性会动只读文件）；
    // 下面 probeStream / probeGet / probeHead 是按它原算法移植的等价实现（m3u8 走 GET + #EXTM3U 校验，
    // 其余先 HEAD、不支持再退 Range GET；共享 OkHttp 默认关自动重定向，所以 3xx 全部手动跟随，上限 3 次）。
    // 探活只判死活、不改地址；探活期间旧流继续播（停播已移到 applyPlaybackResult）。
    private void probeStoredStream(Flag flag, Episode episode) {
        String url = episode.getUrl();
        String pageUrl = episode.getPageUrl();
        new Thread(() -> {
            boolean alive = probeStream(url, pageUrl);
            App.post(() -> {
                if (host.isHostFinishing() || !episode.isSelected()) return;
                if (alive) {
                    // push v15：探活通过即清掉本集的重解析标记（下次过期可再重解析一次）。
                    probeReparseTried = false;
                    probeReparseEpisode = null;
                    requestPlayerContent(flag, episode);
                    return;
                }
                // push v15：同一集只重解析一次——重解析回来的仍是死链时直接放原地址，
                // 让播放器报错走 v5 恢复（一次、HTTP 错才重取），不许探活→重解析空转死循环。
                if (probeReparseTried && probeReparseEpisode == episode) {
                    requestPlayerContent(flag, episode);
                    return;
                }
                probeReparseTried = true;
                probeReparseEpisode = episode;
                if (!host.resolveWebEpisode(flag, episode)) requestPlayerContent(flag, episode);
            });
        }).start();
    }

    private boolean probeStream(String url, String pageUrl) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return false;
        Map<String, String> headers = probeHeaders(url, pageUrl);
        if (probeIsM3u8(url)) {
            ProbeResult res = probeGet(url, headers);
            if (res.code < 200 || res.code >= 400) return false;
            return res.body != null && res.body.contains("#EXTM3U");
        }
        int code = probeHead(url, headers);
        if (code >= 200 && code < 400) return true;
        return probeRangeGet(url, headers);
    }

    private boolean probeRangeGet(String url, Map<String, String> headers) {
        Request.Builder rb = new Request.Builder().url(url).header("Range", "bytes=0-0");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) rb.header(e.getKey(), e.getValue());
        }
        try (Response res = OkHttp.client(PROBE_TIMEOUT_MS).newCall(rb.build()).execute()) {
            int c = res.code();
            return c == 200 || c == 206;
        } catch (Exception e) {
            return false;
        }
    }

    // GET：手动跟随 3xx（上限 3 次）；m3u8 只读前 32KB 验 #EXTM3U，不整包进内存。
    private ProbeResult probeGet(String url, Map<String, String> headers) {
        String cur = url;
        for (int i = 0; i <= PROBE_MAX_REDIRECTS; i++) {
            Request.Builder rb = new Request.Builder().url(cur);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) rb.header(e.getKey(), e.getValue());
            }
            try (Response res = OkHttp.client(PROBE_TIMEOUT_MS).newCall(rb.build()).execute()) {
                int code = res.code();
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && i < PROBE_MAX_REDIRECTS) {
                    String loc = res.header("Location");
                    if (TextUtils.isEmpty(loc)) return new ProbeResult(code, "");
                    cur = probeAbsUrl(loc, cur);
                    continue;
                }
                return new ProbeResult(code, probeBodyHead(res.body()));
            } catch (Exception e) {
                return new ProbeResult(-1, "");
            }
        }
        return new ProbeResult(-1, "");
    }

    // HEAD：手动跟随 3xx（上限 3 次），返回最终状态码；异常/超时返回 -1。
    private int probeHead(String url, Map<String, String> headers) {
        String cur = url;
        for (int i = 0; i <= PROBE_MAX_REDIRECTS; i++) {
            Request.Builder rb = new Request.Builder().url(cur);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (!TextUtils.isEmpty(e.getKey()) && !TextUtils.isEmpty(e.getValue())) rb.header(e.getKey(), e.getValue());
            }
            try (Response res = OkHttp.client(PROBE_TIMEOUT_MS).newCall(rb.head().build()).execute()) {
                int code = res.code();
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && i < PROBE_MAX_REDIRECTS) {
                    String loc = res.header("Location");
                    if (TextUtils.isEmpty(loc)) return code;
                    cur = probeAbsUrl(loc, cur);
                    continue;
                }
                return code;
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    // 只读响应体前 32KB（#EXTM3U 在清单首行，够了）；体为空返回 ""。
    private String probeBodyHead(ResponseBody body) {
        if (body == null) return "";
        try (InputStream in = body.byteStream()) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int total = 0;
            int n;
            while (total < 32768 && (n = in.read(buf, 0, Math.min(buf.length, 32768 - total))) > 0) {
                total += n;
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.indexOf("#EXTM3U") >= 0) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // 与解析器取头法相同：Referer=播放页、UA=HttpEpisodeResolver.nativeUa()（push v25 A：
    // 用户没设 UA 时用 WebView 的 UA，不再落成 okhttp/x 默认 UA 被 missav2 回 500 判死）、Cookie 同源。
    private Map<String, String> probeHeaders(String streamUrl, String pageUrl) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", pageUrl);
        headers.put("User-Agent", com.muse.android.tv.api.loader.HttpEpisodeResolver.nativeUa());
        String cookie = probeCookie(streamUrl);
        if (TextUtils.isEmpty(cookie)) cookie = probeCookie(pageUrl);
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        return headers;
    }

    private static String probeCookie(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean probeIsM3u8(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String path = url;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        return path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    private static String probeAbsUrl(String url, String base) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url.trim();
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http")) return u;
        if (u.startsWith("/")) return probeSchemeHost(base) + u;
        String dir = base;
        int cut = dir.indexOf('?');
        if (cut >= 0) dir = dir.substring(0, cut);
        cut = dir.indexOf('#');
        if (cut >= 0) dir = dir.substring(0, cut);
        int slash = dir.lastIndexOf('/');
        if (slash >= 0) dir = dir.substring(0, slash + 1);
        return dir + u;
    }

    private static String probeSchemeHost(String url) {
        if (TextUtils.isEmpty(url)) return "";
        int scheme = url.indexOf("://");
        if (scheme < 0) return "";
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url + "/" : url.substring(0, slash + 1);
    }

    // 探活用的最小 HTTP 结果（与 HttpEpisodeResolver.HttpResult 同形）。
    private static final class ProbeResult {
        final int code;
        final String body;

        ProbeResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Episode findEpisode(VodPlayRequest request) {
        return state.findEpisode(host.getVodKey(), request);
    }

    private MediaMetadata publishPlaybackMetadata(Episode episode) {
        MediaMetadata metadata = VodPlaybackMedia.metadata(state.getHistory(), episode);
        state.setPlaybackMetadata(metadata);
        host.renderPlaybackMetadata(metadata);
        return metadata;
    }

    private void seamless(Flag flag, boolean initial) {
        History history = state.getHistory();
        int episodeIndex = host.getEpisodeIndex();
        // 跨类型切集直达：带序号时按当前线路集序直接定位，绕开集名打分匹配（同名/异名集不再串位或落回首集）
        Episode episode = episodeIndex >= 0 && episodeIndex < flag.getEpisodes().size()
                ? flag.getEpisodes().get(episodeIndex)
                : history == null ? null : flag.find(history.getVodRemarks(), host.getVodMark().isEmpty());
        host.renderQualityVisible(episode != null && episode.isSelected() && state.getQuality().getUrl().isMulti());
        if (episode == null || episode.isSelected()) return;
        history.setVodRemarks(episode.getName());
        if (forcePlayOnce) {
            forcePlayOnce = false;
            selectEpisode(episode);
            return;
        }
        if (!initial) {
            selectEpisode(episode);
            return;
        }
        if (!PlayerSetting.isAutoPlay()) {
            markEpisodeSelected(flag, episode);
            return;
        }
        int delay = PlayerSetting.getAutoPlayDelay();
        if (delay <= 0) selectEpisode(episode);
        else {
            // 延时档必须先标记选中：scheduleAutoPlay 的回调以 target.isSelected() 为放行条件，
            // 不先标记则读秒到期也永远不播（原 bug：搜索点击 + 自动播放开 + 延时>0 时静默不播）。
            markEpisodeSelected(flag, episode);
            scheduleAutoPlay(episode, delay);
        }
    }

    private void mergeFlag(Flag activated, Flag item) {
        Flag target = findFlag(item);
        if (target == null) {
            state.getFlags().add(item);
        } else {
            target.mergeEpisodes(item.getEpisodes(), state.getHistory() != null && state.getHistory().isRevSort());
            if (target.equals(activated)) host.renderEpisodes(target.getEpisodes());
        }
    }

    private Flag resolveFlag(Flag item) {
        Flag flag = findFlag(item);
        if (flag != null) return flag;
        return state.getFlags().get(0);
    }

    private Flag findFlag(Flag item) {
        if (item != null) for (Flag flag : state.getFlags()) if (flag.equals(item)) return flag;
        return null;
    }

    private boolean cannotApply(PlaybackResult<VodPlayRequest> playback) {
        VodPlayRequest pending = state.getPendingRequest();
        VodPlayRequest request = playback.request();
        if (host.isHostFinishing() || pending == null || request == null) return true;
        return !pending.matches(request) || findEpisode(request) == null || !request.accepts(playback.result());
    }
}
