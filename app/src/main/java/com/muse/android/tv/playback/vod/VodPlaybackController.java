package com.muse.android.tv.playback.vod;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;

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
import com.muse.android.tv.utils.Notify;

import java.util.Collections;
import java.util.List;

public class VodPlaybackController {

    private static final String PUSH_PREFIX = "push://";
    private static final String SEARCH_PREFIX = "msearch:";

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
        startPlayback(result, startPositionMs(), episode);
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
        saveCurrentHistory();
        Flag selected = state.getFlag();
        for (Flag flag : state.getFlags()) flag.toggle(flag == selected, item);
        historyPolicy.updateEpisode(state.getHistory(), state.getFlag(), item);
        host.renderEpisodeSelection(item);
        if (host.isFullscreenForPlayback()) host.showEpisodeReady(item);
        playEpisode(item);
    }

    private void playEpisode(Episode item) {
        Result result = preloader.consume(item);
        host.stopPlaybackForRefresh();
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
        host.resetPlaybackForError(msg);
        fallbackPolicy.playbackError();
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
        historyPolicy.updateProgress(history, time, position, duration);
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
        historyPolicy.updateEpisode(state.getHistory(), flag, episode);
        VodPlayRequest request = VodPlayRequest.create(host.getVodKey(), flag, episode);
        state.setPendingRequest(request);
        publishPlaybackMetadata(episode);
        dataSource.playerContent(request);
        host.onPlaybackRequested();
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
