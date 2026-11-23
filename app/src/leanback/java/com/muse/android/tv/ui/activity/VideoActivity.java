package com.muse.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.muse.android.tv.App;
import com.muse.android.tv.Constant;
import com.muse.android.tv.R;
import com.muse.android.tv.api.DanmakuApi;
import com.muse.android.tv.api.SiteApi;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.HttpEpisodeResolver;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.bean.Danmaku;
import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.bean.Flag;
import com.muse.android.tv.bean.History;
import com.muse.android.tv.bean.Keep;
import com.muse.android.tv.bean.Parse;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.bean.Site;
import com.muse.android.tv.bean.Sub;
import com.muse.android.tv.bean.Vod;
import com.muse.android.tv.databinding.ActivityVideoBinding;
import com.muse.android.tv.db.AppDatabase;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.impl.CustomTarget;
import com.muse.android.tv.model.VideoViewModel;
import com.muse.android.tv.playback.PlaybackAction;
import com.muse.android.tv.playback.PlaybackIntent;
import com.muse.android.tv.playback.PlaybackReset;
import com.muse.android.tv.playback.PlaybackResult;
import com.muse.android.tv.playback.vod.VodDetailResult;
import com.muse.android.tv.playback.vod.VodPlayRequest;
import com.muse.android.tv.playback.vod.VodPlaybackController;
import com.muse.android.tv.playback.vod.VodPlaybackHost;
import com.muse.android.tv.playback.vod.VodPlaybackMedia;
import com.muse.android.tv.player.media.PlaySpec;
import com.muse.android.tv.service.PlaybackService;
import com.muse.android.tv.setting.DanmakuSetting;
import com.muse.android.tv.setting.PlayerSetting;
import com.muse.android.tv.setting.SpeedSetting;
import com.muse.android.tv.ui.adapter.ArrayAdapter;
import com.muse.android.tv.ui.adapter.EpisodeAdapter;
import com.muse.android.tv.ui.adapter.FlagAdapter;
import com.muse.android.tv.ui.adapter.PartAdapter;
import com.muse.android.tv.ui.adapter.QualityAdapter;
import com.muse.android.tv.ui.adapter.QuickAdapter;
import com.muse.android.tv.ui.custom.CustomKeyDownVod;
import com.muse.android.tv.ui.custom.CustomWebView;
import com.muse.android.tv.ui.custom.CustomMovement;
import com.muse.android.tv.ui.dialog.ChapterDialog;
import com.muse.android.tv.ui.dialog.ContentDialog;
import com.muse.android.tv.ui.dialog.DanmakuDialog;
import com.muse.android.tv.ui.dialog.EditionDialog;
import com.muse.android.tv.ui.dialog.EpisodeDialog;
import com.muse.android.tv.ui.dialog.ParseDialog;
import com.muse.android.tv.ui.dialog.PlayerEngineDialog;
import com.muse.android.tv.ui.dialog.SpeedSettingDialog;
import com.muse.android.tv.ui.dialog.TrackDialog;
import com.muse.android.tv.utils.Clock;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.FileUtil;
import com.muse.android.tv.utils.ImgUtil;
import com.muse.android.tv.utils.KeyUtil;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PartUtil;
import com.muse.android.tv.utils.ResUtil;
import com.muse.android.tv.utils.Sniffer;
import com.muse.android.tv.utils.Traffic;
import com.muse.android.tv.utils.UrlUtil;
import com.muse.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.Subscribe;
import org.json.JSONArray;
import org.json.JSONObject;
import org.greenrobot.eventbus.ThreadMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoActivity extends PlaybackActivity implements VodPlaybackHost, CustomKeyDownVod.Listener, ParseDialog.Listener, ArrayAdapter.OnClickListener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, Clock.Callback {

    private ActivityVideoBinding mBinding;
    private VideoViewModel mViewModel;
    private VodPlaybackController mVod;
    /** push v2：浏览器全集待解析集的请求头（HTTP 解析完即用；startPlayback 合并后清空——fuzi 在 getPlayer 里先清后读的 bug 不带过来）。 */
    private Map<String, String> mWebEpHeaders = Collections.emptyMap();
    /** push v32（方案 R，电视 v19 补）：网页源本集正交 WebView 嗅探时的播放页地址；嗅探失败（error_play_parse）按回落 b 用浏览器打开它。 */
    private String mWebSniffPage;
    /** push v32：网页源 parse=1 先走 HttpEpisodeResolver 的轮次号；换集/重播后旧回调作废。 */
    private int mWebResolveSeq;
    private FlagAdapter mFlagAdapter;
    private EpisodeAdapter mEpisodeAdapter;
    private QualityAdapter mQualityAdapter;
    private ArrayAdapter mArrayAdapter;
    private PartAdapter mPartAdapter;
    private QuickAdapter mQuickAdapter;
    private ViewGroup.LayoutParams mFrameParams;
    private CustomKeyDownVod mKeyDown;
    private Clock mClock;
    private View mFocus1;
    private View mFocus2;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Runnable mR5;
    private long mLastTxBytes;
    private long mLastRxBytes;
    private long mLastRxTime;
    private Player mMediaInfoPlayer;
    private boolean mMediaInfo;
    private History mHistory;
    private boolean mNovelTtsContinue;
    private boolean fullscreen;
    private boolean useParse;
    private String mSiteBaseUrl;
    private String mSiteBaseUrlKey;

    public static void push(FragmentActivity activity, String text) {
        Uri uri = UrlUtil.uri(text);
        if (FileChooser.isFileSource(uri)) FileChooser.getFileUri(uri, fileUri -> file(activity, fileUri));
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, Uri fileUri) {
        if (fileUri == null || activity.isFinishing() || activity.isDestroyed()) return;
        start(activity, SiteApi.PUSH, fileUri.toString(), FileUtil.getDisplayName(fileUri));
    }

    public static void cast(Activity activity, History history) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic(), null, false, true);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true, false);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false, false);
    }

    // 跨类型切集直达的目标集序号（阅读器/图片页强播时暂存，紧随的 start 调用写入 intent 后清零）
    private static int sPendingEpisodeIndex = -1;

    /** 跨类型切集强播：不经自动播放开关，进详情直接播指定序号的集（多集按序号定位不串位）。 */
    public static void startForceEpisode(Activity activity, String key, String id, String name, String pic, String mark, int episodeIndex) {
        sPendingEpisodeIndex = episodeIndex;
        start(activity, key, id, name, pic, mark);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        if (sPendingEpisodeIndex >= 0) intent.putExtra("episode_index", sPendingEpisodeIndex);
        sPendingEpisodeIndex = -1;
        intent.putExtra("cast", cast);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        putPic(intent, pic);
        activity.startActivity(intent);
    }

    /** push v1：直达播放并附带请求头（浏览器页嗅流转播放用：带上网页的 Referer/UA/Cookie）。走与 start(url) 相同的推送通道，请求头经 playHeaders 透传、在 startPlayback 前并入结果。 */
    public static void startWithHeaders(Activity activity, String url, Map<String, String> headers) {
        startWithHeaders(activity, url, headers, null, null, null);
    }

    /** push v1：同上，再带网页侧提取的片名/线路名/集名，播放页显示真标题而不是网址、推送、01。 */
    public static void startWithHeaders(Activity activity, String url, Map<String, String> headers, String title, String flag, String episode) {
        startWithHeaders(activity, url, headers, title, flag, episode, null, null);
    }

    /** push v1：另带同线路选集表（JSON 数组 [{name,url}]）与当前播放页网址，播放器据此摆出全集。 */
    public static void startWithHeaders(Activity activity, String url, Map<String, String> headers, String title, String flag, String episode, String episodesJson, String pageUrl) {
        startWithHeaders(activity, url, headers, title, flag, episode, episodesJson, pageUrl, null);
    }

    /** push v1：再带全部线路表（JSON {lines:[{name,episodes:[{name,url,cur}]}]}），播放器摆出全部线路，当前线路置顶。 */
    public static void startWithHeaders(Activity activity, String url, Map<String, String> headers, String title, String flag, String episode, String episodesJson, String pageUrl, String linesJson) {
        startWithHeaders(activity, url, headers, title, flag, episode, episodesJson, pageUrl, linesJson, null);
    }

    /** push v1：再带探针抓到的页面海报（browserPic）——落地时写进 VOD，历史记录不再全是占位图。 */
    public static void startWithHeaders(Activity activity, String url, Map<String, String> headers, String title, String flag, String episode, String episodesJson, String pageUrl, String linesJson, String pic) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", false);
        intent.putExtra("name", url);
        intent.putExtra("key", SiteApi.PUSH);
        intent.putExtra("id", url);
        intent.putExtra("playHeaders", headersToJson(headers));
        if (title != null && !title.isEmpty()) intent.putExtra("browserTitle", title);
        if (flag != null && !flag.isEmpty()) intent.putExtra("browserFlag", flag);
        if (episode != null && !episode.isEmpty()) intent.putExtra("browserEpisode", episode);
        if (episodesJson != null && !episodesJson.isEmpty()) intent.putExtra("browserEpisodes", episodesJson);
        if (pageUrl != null && !pageUrl.isEmpty()) intent.putExtra("browserPage", pageUrl);
        if (linesJson != null && !linesJson.isEmpty()) intent.putExtra("browserLines", linesJson);
        if (pic != null && !pic.isEmpty()) intent.putExtra("browserPic", pic);
        activity.startActivity(intent);
    }

    /** push v2：再带浏览器探针的来源站（origin）、剧号（vid）、线路号（sid）与集号（nid），供 HTTP 解析器走 Provide API；只在非空时写进 intent。 */
    public static void startWithHeaders(Activity activity, String url, Map<String, String> headers, String title, String flag, String episode, String episodesJson, String pageUrl, String linesJson, String pic, String origin, String vid, String sid, String nid) {
        startWithHeaders(activity, url, headers, title, flag, episode, episodesJson, pageUrl, linesJson, pic, origin, vid, sid, nid, null, null, null);
    }

    /** push v33 M1（电视 v19 补）：再带当前页读到的简介/年份/地区（browserContent/browserYear/browserArea），只在非空时写进 intent。 */
    public static void startWithHeaders(Activity activity, String url, Map<String, String> headers, String title, String flag, String episode, String episodesJson, String pageUrl, String linesJson, String pic, String origin, String vid, String sid, String nid, String content, String year, String area) {
        Intent intent = new Intent(activity, VideoActivity.class);
        if (content != null && !content.isEmpty()) intent.putExtra("browserContent", content);
        if (year != null && !year.isEmpty()) intent.putExtra("browserYear", year);
        if (area != null && !area.isEmpty()) intent.putExtra("browserArea", area);
        intent.putExtra("collect", false);
        intent.putExtra("name", url);
        intent.putExtra("key", SiteApi.PUSH);
        intent.putExtra("id", url);
        intent.putExtra("playHeaders", headersToJson(headers));
        if (title != null && !title.isEmpty()) intent.putExtra("browserTitle", title);
        if (flag != null && !flag.isEmpty()) intent.putExtra("browserFlag", flag);
        if (episode != null && !episode.isEmpty()) intent.putExtra("browserEpisode", episode);
        if (episodesJson != null && !episodesJson.isEmpty()) intent.putExtra("browserEpisodes", episodesJson);
        if (pageUrl != null && !pageUrl.isEmpty()) intent.putExtra("browserPage", pageUrl);
        if (linesJson != null && !linesJson.isEmpty()) intent.putExtra("browserLines", linesJson);
        if (pic != null && !pic.isEmpty()) intent.putExtra("browserPic", pic);
        if (origin != null && !origin.isEmpty()) intent.putExtra("browserOrigin", origin);
        if (vid != null && !vid.isEmpty()) intent.putExtra("browserVid", vid);
        if (sid != null && !sid.isEmpty()) intent.putExtra("browserSid", sid);
        if (nid != null && !nid.isEmpty()) intent.putExtra("browserNid", nid);
        activity.startActivity(intent);
    }

    /** push v1：请求头 Map ↔ JSON（MuseTV 无 MusicHeaders，用 JSONObject 直写）。 */
    private static String headersToJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                obj.put(e.getKey(), e.getValue());
            }
            return obj.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> headersFromJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return map;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, obj.optString(k));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static void putPic(Intent intent, String pic) {
        intent.removeExtra("pic");
        pic = ImgUtil.cache(pic);
        if (!TextUtils.isEmpty(pic)) intent.putExtra("pic", pic);
    }

    private boolean isCast() {
        return getIntent().getBooleanExtra("cast", false);
    }

    private String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    private String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    private boolean isSameVideo(Intent intent) {
        String key = Objects.toString(intent.getStringExtra("key"), "");
        String id = Objects.toString(intent.getStringExtra("id"), "");
        return TextUtils.equals(key, getKey()) && TextUtils.equals(id, getId());
    }

    @Override
    public String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private Episode getEpisode() {
        return mEpisodeAdapter.getActivated();
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : PlayerSetting.getScale();
    }

    private void setScale(int scale) {
        mVod.setScale(scale);
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    @Override
    public boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected PlayerView getPlayerView() {
        return mBinding.player;
    }

    @Override
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mVod.onPlaybackServiceReady();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (TextUtils.isEmpty(intent.getStringExtra("id")) || isSameVideo(intent)) return;
        saveHistory(true);
        mVod.reset();
        setIntent(intent);
        updateNavigationKey();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mFrameParams = mBinding.video.getLayoutParams();
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownVod.create(this);
        mR1 = this::hideControl;
        mR2 = this::updateFocus;
        mR3 = this::setTraffic;
        mR4 = this::showEmpty;
        mR5 = this::tickMediaInfo;
        setRecyclerView();
        setVideoView();
        setViewModel();
        checkCast();
        checkId();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.keep.setOnClickListener(view -> onKeep());
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.change.setOnClickListener(view -> onChange());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.ending.setUpListener(this::onEndingAdd);
        mBinding.control.action.ending.setDownListener(this::onEndingSub);
        mBinding.control.action.opening.setUpListener(this::onOpeningAdd);
        mBinding.control.action.opening.setDownListener(this::onOpeningSub);
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onPlayer());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.mediaInfoBtn.setOnClickListener(view -> onMediaInfo());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodes());
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.flag.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mFlagAdapter.getItemCount() > 0) onItemClick(mFlagAdapter.get(position));
            }
        });
        mBinding.episode.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (child != null && mBinding.video != mFocus1) mFocus1 = child.itemView;
            }
        });
        mBinding.array.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mEpisodeAdapter.getItemCount() > 20 && position > 1) mBinding.episode.setSelectedPosition((position - 2) * 20);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.flag.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.episode.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.episode.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this));
        mBinding.quality.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quality.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.array.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.array.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.array.setAdapter(mArrayAdapter = new ArrayAdapter(this));
        mBinding.part.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.part.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.part.setAdapter(mPartAdapter = new PartAdapter(item -> mVod.search(item)));
        mBinding.quick.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quick.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
    }

    private void setVideoView() {
        setSeekNextFocusDown(R.id.next);
        setActionFocusBoundary(mBinding.control.action.getRoot());
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.danmaku.setVisibility(DanmakuSetting.isLoad() ? View.VISIBLE : View.GONE);
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(VideoViewModel.class);
        mVod = mViewModel.createPlaybackController(this);
        observeWhenServiceReady(mViewModel.getDetail(), this::onDetailObserved);
        observeWhenServiceReady(mViewModel.getSearch(), this::onSearchObserved);
        observeWhenServiceReady(mViewModel.getPreload(), this::onPreloadObserved);
        observeWhenServiceReady(mViewModel.getPlayback(), this::onPlaybackObserved);
    }

    private void onDetailObserved(VodDetailResult result) {
        // push v2：浏览器转播放——详情落下后先把网页侧的片名/线路/集名摆进 VOD，再走正常详情流；
        // 当前集序号经 episode_index 透传给选集（不用集名匹配），用完立即清掉，免得后续切线误用。
        int browserIndex = -1;
        // push v33 M1：推送带来的简介/年份/地区，详情对应字段为空时填（不覆盖蜘蛛已有的）。
        if (result != null && result.result() != null) applyBrowserInfo(result.result().getVod());
        if (result != null && result.result() != null && (getIntent().hasExtra("browserTitle") || getIntent().hasExtra("browserLines"))) {
            browserIndex = applyBrowserMeta(result.result().getVod());
        }
        // push v16：单片推送（无线表/全集表；标题为空时 applyBrowserMeta 不进）也要给当前集记 pageUrl，
        // 否则 v4 探活与 v5 报错恢复都因 pageUrl 为空跳过、(e1) 兜底进不了 resolveWebEpisode。
        // 已记过 pageUrl 或非单片时 markSinglePushEpisode 自身为空操作（由手机 v15 同款移植）。
        if (browserIndex < 0 && result != null && result.result() != null) markSinglePushEpisode(result.result().getVod());
        if (browserIndex >= 0) getIntent().putExtra("episode_index", browserIndex);
        mVod.onDetailResult(result);
        if (browserIndex >= 0) getIntent().removeExtra("episode_index");
    }

    private void onPlaybackObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPlaybackResult(result);
    }

    private void onPreloadObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPreloadResult(result);
    }

    private void onSearchObserved(Result result) {
        mVod.onSearchResult(result);
    }

    @Override
    public String getVodKey() {
        return getKey();
    }

    @Override
    public String getVodId() {
        return getId();
    }

    @Override
    public void setVodId(String id) {
        getIntent().putExtra("id", id);
    }

    @Override
    public String getVodName() {
        String name = mBinding.name.getText().toString();
        return name.isEmpty() ? getName() : name;
    }

    @Override
    public String getVodPic() {
        return getPic();
    }

    @Override
    public String getVodMark() {
        return getMark();
    }

    @Override
    public int getEpisodeIndex() {
        return getIntent().getIntExtra("episode_index", -1);
    }

    @Override
    public boolean isSiteChangeable() {
        return getSite().isChangeable();
    }

    @Override
    public boolean isHostFinishing() {
        return isFinishing() || isDestroyed();
    }

    @Override
    public boolean isPlayerEmpty() {
        return player().isEmpty();
    }

    @Override
    public boolean hasPlaybackSession() {
        return service() != null && isOwner() && player().hasPlaySpec();
    }

    @Override
    public boolean isFullscreenForPlayback() {
        return isFullscreen();
    }

    @Override
    public boolean isLivePlayback() {
        return service() != null && isOwner() && player().isLive();
    }

    @Override
    public boolean canTrackPlaybackProgress() {
        return service() != null && isOwner() && player().isVod();
    }

    @Override
    public boolean canPreloadNext() {
        return service() != null && isOwner() && player().canPreloadNext();
    }

    @Override
    public long getPlayerPosition() {
        return player().getPosition();
    }

    @Override
    public long getPlayerDuration() {
        return player().getDuration();
    }

    @Override
    public void usePushId(String id) {
        getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", id);
    }

    @Override
    public void onPlaybackRequested() {
        showProgress();
    }

    @Override
    public void prepareSource(Vod item) {
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("id", item.getId());
        putPic(getIntent(), item.getPic());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        player().reset();
        player().stop();
    }

    @Override
    public void stopPlaybackForRefresh() {
        player().stop();
        player().clear();
        mClock.setCallback(null);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        player().replay(position);
    }

    @Override
    public void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata) {
        String url = result.getUrl().v();
        String title = metadata != null && metadata.title != null ? metadata.title.toString() : "";
        if (url != null && url.startsWith("novel://")) {
            ArrayList<String> names = new ArrayList<>();
            ArrayList<String> urls = new ArrayList<>();
            int position = 0;
            if (mEpisodeAdapter != null) {
                position = mEpisodeAdapter.getPosition();
                for (Episode episode : mEpisodeAdapter.getItems()) {
                    names.add(episode.getName());
                    urls.add(episode.getUrl());
                }
            }
            NovelActivity.startForResult(this, title, url, names, urls, position, getHistoryKey(), mHistory.getVodName(), mHistory.getVodPic(), getSite().getName(), mNovelTtsContinue, getKey(), getId(), result.getFlag());
            mNovelTtsContinue = false;
        } else if (url != null && url.startsWith("pics://")) {
            // 漫画：有话列表时走 startForResult（不 finish），阅读器内选话回传后由 onActivityResult 重新 selectEpisode；
            // 无话列表时沿用旧路（直接进图、finish 上一级）。
            if (mEpisodeAdapter != null && mEpisodeAdapter.getItemCount() > 0) {
                ArrayList<String> names = new ArrayList<>();
                for (Episode episode : mEpisodeAdapter.getItems()) names.add(episode.getName());
                ArrayList<String> urls = new ArrayList<>();
                for (Episode episode : mEpisodeAdapter.getItems()) urls.add(episode.getUrl());
                ImageActivity.startForResult(this, title, url, result.getHeader(), names, urls, mEpisodeAdapter.getPosition(), getKey(), getId(), result.getFlag(), mHistory.getVodName(), mHistory.getVodPic());
            } else {
                ImageActivity.start(this, title, url, result.getHeader());
                finish();
            }
        } else if (getSite().isWebPlay()) {
            startWebPlayer(result);
        } else {
            // push v2：浏览器转播放的请求头（playHeaders + 待解析集 HTTP 解析头）并入结果，PlaySpec 会把它带给播放器；
            // fuzi 在 getPlayer 里先清后读的 bug 不带过来——这里先合并、再清空。
            Map<String, String> extraHeaders = new LinkedHashMap<>();
            extraHeaders.putAll(headersFromJson(getIntent().getStringExtra("playHeaders")));
            extraHeaders.putAll(mWebEpHeaders);
            if (!extraHeaders.isEmpty()) {
                if (result.getHeader().isEmpty()) result.setHeader(extraHeaders);
                else result.getHeader().putAll(extraHeaders);
            }
            mWebEpHeaders = Collections.emptyMap();
            mWebSniffPage = null;
            final int seq = ++mWebResolveSeq;
            String page = url == null ? "" : url;
            if (getSite().isWebSource() && result.getParse() == 1 && (page.startsWith("http://") || page.startsWith("https://"))) {
                // push v32（方案 R，加固 a；电视 v19 补）：网页源要嗅探时，先用推送线现成的纯 HTTP 解析器
                // （player_aaaa、候选探活、ArtPlayer、art.php 链，浏览器同款 UA）现解播放页；解出直接播，
                // 解不出再走参考实现的 WebView 嗅探（WebSpider 自动点播脚本 + 加固 b）。只对网页源生效，其他源走下面原路。
                CustomWebView.setExtraAdFilter(AdDomains::isAdDomain);
                showProgress();
                HttpEpisodeResolver.resolveFresh(this, page, null, null, new HttpEpisodeResolver.Callback() {
                    @Override
                    public void onResolved(String stream, Map<String, String> headers) {
                        if (isHostFinishing() || seq != mWebResolveSeq) return;
                        result.setUrl(stream);
                        result.setParse(0);
                        if (headers != null && !headers.isEmpty()) {
                            if (result.getHeader().isEmpty()) result.setHeader(new LinkedHashMap<>(headers));
                            else result.getHeader().putAll(headers);
                        }
                        startPlayer(getHistoryKey(), result, false, getSite().getTimeout(), startPositionMs, metadata);
                    }

                    @Override
                    public void onFailed() {
                        if (isHostFinishing() || seq != mWebResolveSeq) return;
                        mWebSniffPage = page;
                        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, metadata);
                    }
                });
                return;
            }
            startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, metadata);
        }
    }

    // ===== push v2：浏览器转播放落地（多线表 + HTTP 集解析）=====

    /** push v2：浏览器待解析集（url 为空、pageUrl 为播放页地址）——走纯 HTTP 解析器取直链，解析成功后把直链写回该集并续播。 */
    @Override
    public boolean resolveWebEpisode(Flag flag, Episode episode) {
        if (episode == null || TextUtils.isEmpty(episode.getPageUrl())) return false;
        // push v14 (c)：按钮式选集（有站方 id、无 url，如 gaze）不走 HTTP 解析器——
        // 让后台栈里的现有浏览器点该集按钮，站方自己解出流、现有嗅探抓回。其它站
        // （有 url 的 MacCMS/子页/单片）一律走下面的原路，不动。
        if (!TextUtils.isEmpty(episode.getBrowserId()) && TextUtils.isEmpty(episode.getUrl())) {
            boolean requested = WebPlayActivity.requestBrowserEpisodeClick(episode.getBrowserId(),
                    flag == null ? "" : flag.getFlag(), episode.getName(), new WebPlayActivity.BrowserEpisodeCallback() {
                        @Override
                        public void onResolved(String url, Map<String, String> headers) {
                            hideProgress();
                            // push v6 Layer 1：与 HTTP 路同一守卫——解析中用户已切走别的集时不回切。
                            if (isHostFinishing() || !episode.isSelected()) return;
                            mWebEpHeaders = headers == null ? Collections.emptyMap() : headers;
                            episode.setUrl(url);
                            mVod.playResolvedEpisode(episode);
                        }

                        @Override
                        public void onFailed() {
                            hideProgress();
                            if (isHostFinishing()) return;
                            Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
                        }
                    });
            if (requested) {
                showProgress();
                Notify.show(getString(R.string.browser_resolving_episode, episode.getName()));
                return true;
            }
            // 后台已无浏览器（用户已关掉）：直接回落现有 toast，不再走 HTTP 解析器
            // （播放页地址不是流地址、vid 为空，解析器四层全败，只是多等一次网络）。
            Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
            return true;
        }
        showProgress();
        Notify.show(getString(R.string.browser_resolving_episode, episode.getName()));
        HttpEpisodeResolver.resolve(this, episode.getPageUrl(), getIntent().getStringExtra("browserOrigin"), getIntent().getStringExtra("browserVid"), new HttpEpisodeResolver.Callback() {
            @Override
            public void onResolved(String url, Map<String, String> headers) {
                hideProgress();
                // push v6 Layer 1：解析中用户已切走别的集时不回切；已选中的集只重跑 playEpisode，不重进 selectEpisode（重进会再写一次历史，旧集位置污染新集槽）。
                if (isHostFinishing() || !episode.isSelected()) return;
                mWebEpHeaders = headers == null ? Collections.emptyMap() : headers;
                episode.setUrl(url);
                mVod.playResolvedEpisode(episode);
            }

            @Override
            public void onFailed() {
                // push v15 (e1)：HTTP 解析失败兜底——枫叶 parse 回 HTML 播放器页、纯 HTTP
                // 解不出的确定性失败，改让后台栈里的现有浏览器直接导航到该集播放页，
                // 站方自己解流、现有嗅探抓回（复用 v14 (c) 的轮询/回交机器，不另造）。
                // 纠正（2026-11-21）：触发条件与导航地址一律用 getPageUrl()——getUrl()
                // 是流地址（未解析时为空串），播放页地址在 getPageUrl()；按 getUrl()
                // 判断兜底永不触发（探针表非当前集是 create(name, "") + setPageUrl）。
                // 入口守卫已判 pageUrl 非空，此处判空只是防御；单片推送 pageUrl 缺口
                // 的集根本进不了 resolveWebEpisode，仍走不到兜底（已知未修项，另批）。
                // 浏览器已关（request 返回 false）直接原 toast，不卡住。
                if (!TextUtils.isEmpty(episode.getPageUrl())) {
                    boolean requested = WebPlayActivity.requestBrowserPageResolve(episode.getPageUrl(), episode.getName(), new WebPlayActivity.BrowserEpisodeCallback() {
                        @Override
                        public void onResolved(String url, Map<String, String> headers) {
                            hideProgress();
                            // push v6 Layer 1：与 HTTP 路同一守卫——解析中用户已切走别的集时不回切。
                            if (isHostFinishing() || !episode.isSelected()) return;
                            mWebEpHeaders = headers == null ? Collections.emptyMap() : headers;
                            episode.setUrl(url);
                            mVod.playResolvedEpisode(episode);
                        }

                        @Override
                        public void onFailed() {
                            hideProgress();
                            if (isHostFinishing()) return;
                            Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
                        }
                    });
                    if (requested) {
                        // 兜底进行中：progress 保持可见（本 onFailed 尚未 hideProgress），
                        // 不弹失败 toast，等兜底回调再决定。
                        showProgress();
                        return;
                    }
                }
                hideProgress();
                Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
            }
        });
        return true;
    }

    /** push v5：过期流报错恢复——重取新鲜流（纯 HTTP 解析器），取到后重选该集续播（控制器用报错时捕获的位置续播）；取失败回落正常报错路（mVod.playbackError 再进时守卫已置位，直接 reset + showError + fallback，不再重取）。恢复中用户已切走别的集时不回切（isSelected 守卫）。 */
    @Override
    public boolean resolveWebEpisodeForErrorRecovery(Flag flag, Episode episode, String errorMsg) {
        if (episode == null || TextUtils.isEmpty(episode.getPageUrl())) return false;
        // push v11 A2：先清本集解析缓存再重取——缓存里是旧签名地址，不清就等于原地重播过期流。
        HttpEpisodeResolver.invalidateForRetry(episode.getPageUrl(), getIntent().getStringExtra("browserOrigin"), getIntent().getStringExtra("browserVid"));
        showProgress();
        Notify.show(getString(R.string.browser_resolving_episode, episode.getName()));
        HttpEpisodeResolver.resolve(this, episode.getPageUrl(), getIntent().getStringExtra("browserOrigin"), getIntent().getStringExtra("browserVid"), new HttpEpisodeResolver.Callback() {
            @Override
            public void onResolved(String url, Map<String, String> headers) {
                hideProgress();
                if (isHostFinishing() || !episode.isSelected()) return;
                mWebEpHeaders = headers == null ? Collections.emptyMap() : headers;
                episode.setUrl(url);
                mVod.selectEpisode(episode);
            }

            @Override
            public void onFailed() {
                hideProgress();
                if (isHostFinishing()) return;
                Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
                mVod.playbackError(errorMsg);
            }
        });
        return true;
    }

    /** push v2：浏览器转播放落地——网页侧的片名/线路名/集名覆盖推送详情的写死值；普通入口无这些参数、零影响。返回当前集在当前线路里的下标（-1 表示未摆线）。 */
    /** push v33 M1（电视 v19 补）：推送带来的简介/年份/地区落进 VOD——只在详情对应字段为空时填。 */
    private void applyBrowserInfo(Vod vod) {
        if (vod == null) return;
        String c = getIntent().getStringExtra("browserContent");
        String y = getIntent().getStringExtra("browserYear");
        String a = getIntent().getStringExtra("browserArea");
        if (!TextUtils.isEmpty(c) && vod.getContent().isEmpty()) vod.setContent(c);
        if (!TextUtils.isEmpty(y) && vod.getYear().isEmpty()) vod.setYear(y);
        if (!TextUtils.isEmpty(a) && vod.getArea().isEmpty()) vod.setArea(a);
    }

    private int applyBrowserMeta(Vod vod) {
        if (vod == null) return -1;
        // 探针海报落地提到标题早退之前——海报不依赖标题，标题为空时也要落图。
        String browserPic = getIntent().getStringExtra("browserPic");
        if (browserPic != null && !browserPic.isEmpty() && (vod.getPic() == null || vod.getPic().isEmpty())) vod.setPic(browserPic);
        String title = getIntent().getStringExtra("browserTitle");
        if (title == null || title.isEmpty()) return -1;
        vod.setName(title);
        String flag = getIntent().getStringExtra("browserFlag");
        String episode = getIntent().getStringExtra("browserEpisode");
        // 带全部线路表时按线摆旗（当前线路置顶、当前集挂直达流、其余集留空 url 记 pageUrl、点开走 HTTP 解析）；摆成功就不再走单线路径。
        int index = applyBrowserLines(vod, episode);
        if (index >= 0) return index;
        // push v13 F4(a)：表已落下、无当前集（-2）时不走单线回落——回落会把各线全改名为
        // browserFlag；仍向调用方报 -1（不设 episode_index，不预选）。
        if (index == -2) return -1;
        if (flag != null && !flag.isEmpty()) vod.setPlayFrom(flag);
        int firstEpIndex = -1;
        for (Flag item : vod.getFlags()) {
            if (flag != null && !flag.isEmpty()) {
                item.setFlag(flag);
                item.trans(); // 显示名（getShow）由 trans 从 flag 派生，须刷新
            }
            // 有全集表先摆全集（当前集挂已嗅到的流、其余集留空 url 记 pageUrl、点开走 HTTP 解析）；摆成功就不再走单集改名。
            int epIndex = applyBrowserEpisodes(item);
            if (firstEpIndex < 0 && epIndex >= 0) firstEpIndex = epIndex;
            if (epIndex < 0 && episode != null && !episode.isEmpty() && !item.getEpisodes().isEmpty())
                item.getEpisodes().get(0).setName(episode);
        }
        return firstEpIndex;
    }

    /** push v16：单片推送的当前集记 pageUrl=浏览器页地址（v4 探活与 v5 报错恢复的入口条件，(e1) 兜底进 resolveWebEpisode 的前提；由手机 v15 同款移植）。 */
    private void markSinglePushEpisode(Vod vod) {
        String page = getIntent().getStringExtra("browserPage");
        if (page == null || page.isEmpty() || vod == null || vod.getFlags() == null) return;
        if (vod.getFlags().size() != 1) return;
        Flag flag = vod.getFlags().get(0);
        if (flag == null || flag.getEpisodes() == null || flag.getEpisodes().size() != 1) return;
        Episode ep = flag.getEpisodes().get(0);
        if (ep == null || TextUtils.isEmpty(ep.getUrl()) || !ep.getUrl().startsWith("http")) return;
        if (!TextUtils.isEmpty(ep.getPageUrl())) return;
        ep.setPageUrl(page);
    }

    /** push v2：取集名里的第一串阿拉伯数字作集号（如「第01集」→ 1），无数字返回 -1；集名按集号比对，杀掉「第01集」vs「第1集」的补零错位。 */
    private static int episodeNumber(String name) {
        if (name == null) return -1;
        int start = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                if (start < 0) start = i;
            } else if (start >= 0) {
                try {
                    return Integer.parseInt(name.substring(start, i));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        if (start < 0) return -1;
        try {
            return Integer.parseInt(name.substring(start));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** push v2：全部线路落地——每条线一面旗，当前线路置顶；当前集（探针 cur 标优先、无标时按集号对位兜底）挂已嗅到的直达流，其余集留空 url、记 pageUrl，未解析，点开时走 HTTP 解析。无当前集时表照摆、流挂当前线首集、不预选，返回 -2（见 applyBrowserMeta）；表未摆上才返回 -1 走旧路径。 */
    private int applyBrowserLines(Vod vod, String episodeName) {
        String json = getIntent().getStringExtra("browserLines");
        if (json == null || json.isEmpty() || vod.getFlags().isEmpty()) return -1;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray lines = root.optJSONArray("lines");
            if (lines == null || lines.length() == 0) return -1;
            String stream = vod.getFlags().get(0).getEpisodes().isEmpty() ? "" : vod.getFlags().get(0).getEpisodes().get(0).getUrl();
            if (stream.isEmpty()) return -1;
            // 先解析成中间结构，并定位当前线（有 cur 标的那条）
            List<String> names = new ArrayList<>();
            List<JSONArray> epsPerLine = new ArrayList<>();
            int currentLine = -1;
            int lineCur = -1;
            int totalEpisodes = 0;
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.getJSONObject(i);
                JSONArray eps = line.optJSONArray("episodes");
                if (eps == null || eps.length() == 0) continue;
                names.add(line.optString("name"));
                epsPerLine.add(eps);
                totalEpisodes += eps.length();
                if (line.optInt("cur", 0) == 1) lineCur = names.size() - 1;
                for (int j = 0; j < eps.length(); j++) if (eps.getJSONObject(j).optInt("cur", 0) == 1) currentLine = names.size() - 1;
            }
            // 探针的线路级 cur 标（按页面 sid 锚定）优先于集级对位
            if (lineCur >= 0) currentLine = lineCur;
            if (names.isEmpty() || (names.size() == 1 && totalEpisodes <= 1)) return -1;
            if (currentLine < 0) {
                // 无 cur 标：按线路名对位（browserFlag），再不行默认第一条
                String flag = getIntent().getStringExtra("browserFlag");
                currentLine = 0;
                if (flag != null) for (int i = 0; i < names.size(); i++) if (flag.equals(names.get(i))) { currentLine = i; break; }
            }
            // 当前线置顶，其余保持站点原序
            List<Integer> order = new ArrayList<>();
            order.add(currentLine);
            for (int i = 0; i < names.size(); i++) if (i != currentLine) order.add(i);
            List<Flag> built = new ArrayList<>();
            // push v14 (b)：浏览器播放页地址，无 url 的按钮集用它作 pageUrl。
            String browserPage = getIntent().getStringExtra("browserPage");
            if (browserPage == null) browserPage = "";
            boolean streamPlaced = false;
            int currentIndex = -1;
            for (int oi = 0; oi < order.size(); oi++) {
                int li = order.get(oi);
                JSONArray eps = epsPerLine.get(li);
                Flag flagObj = oi == 0 ? vod.getFlags().get(0) : new Flag();
                flagObj.setFlag(names.get(li));
                List<Episode> list = new ArrayList<>();
                int lineCurrentIndex = -1;
                for (int j = 0; j < eps.length(); j++) {
                    JSONObject ep = eps.getJSONObject(j);
                    String name = ep.optString("name");
                    String url = ep.optString("url");
                    // push v14 (b)：按钮式选集（gaze 式）没有 url、但带站方 id——照样落表，
                    // pageUrl 记浏览器播放页地址（点集时走浏览器内点集解流，不走 HTTP 解析）。
                    String browserId = ep.optString("id");
                    String browserPath = ep.optString("path");
                    if (name.isEmpty() || (url.isEmpty() && browserId.isEmpty())) continue;
                    String pageUrl = url.isEmpty() ? browserPage : url;
                    boolean isCurrent = oi == 0 && !streamPlaced && (ep.optInt("cur", 0) == 1
                            || (episodeName != null && !episodeName.isEmpty() && episodeNumber(episodeName) >= 0 && episodeNumber(episodeName) == episodeNumber(name)));
                    if (isCurrent) {
                        lineCurrentIndex = list.size();
                        streamPlaced = true;
                        Episode created = Episode.create(name, stream);
                        created.setPageUrl(pageUrl);
                        created.setBrowserId(browserId);
                        created.setBrowserPath(browserPath);
                        list.add(created);
                    } else {
                        Episode created = Episode.create(name, "");
                        created.setPageUrl(pageUrl);
                        created.setBrowserId(browserId);
                        created.setBrowserPath(browserPath);
                        list.add(created);
                    }
                }
                if (list.isEmpty()) continue;
                if (oi == 0 && lineCurrentIndex < 0) {
                    // push v13 F4(a)：当前线无当前集时不再整表作废——把嗅到的流挂到当前线首集
                    // （播放器能播嗅到的流），但不标当前集（lineCurrentIndex 保持 -1，currentIndex
                    // 返回 -1，不预选）；其余集照常摆全集。
                    if (!streamPlaced) {
                        list.get(0).setUrl(stream);
                        streamPlaced = true;
                    }
                }
                flagObj.getEpisodes().clear();
                flagObj.getEpisodes().addAll(list);
                if (lineCurrentIndex >= 0) {
                    flagObj.setPosition(lineCurrentIndex);
                    // MuseTV 注意：这里不预置 Episode.setSelected(true)——详情流的 seamless
                    // 见已选集会直接返回不播；当前集序号经返回值 + episode_index 透传，正常流里再选。
                    if (oi == 0) currentIndex = lineCurrentIndex;
                }
                flagObj.trans();
                built.add(flagObj);
            }
            if (!streamPlaced || built.isEmpty()) return -1;
            vod.getFlags().clear();
            vod.getFlags().addAll(built);
            StringBuilder from = new StringBuilder();
            for (Flag f : built) {
                if (from.length() > 0) from.append(",");
                from.append(f.getFlag());
            }
            vod.setPlayFrom(from.toString());
            // push v13 F4(a)：表已落下但无当前集时返回 -2（不是 -1）：applyBrowserMeta 见 -2 即
            // 跳过单线回落（回落会把各线全改名为 browserFlag），仍向调用方报 -1，不设 episode_index。
            return currentIndex >= 0 ? currentIndex : -2;
        } catch (Exception e) {
            return -1;
        }
    }

    /** push v2：浏览器全集表落地：表里与当前播放页同址的那集换成已嗅到的直达流，其余集留空 url、记 pageUrl，未解析，点开时走 HTTP 解析。返回当前集下标，摆不上返回 -1。 */
    private int applyBrowserEpisodes(Flag item) {
        String json = getIntent().getStringExtra("browserEpisodes");
        String page = getIntent().getStringExtra("browserPage");
        if (json == null || json.isEmpty() || page == null || item.getEpisodes().isEmpty()) return -1;
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() <= 1) return -1;
            String stream = item.getEpisodes().get(0).getUrl();
            List<Episode> list = new ArrayList<>();
            Episode current = null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String name = obj.optString("name");
                String url = obj.optString("url");
                if (name.isEmpty() || url.isEmpty()) continue;
                // 探针按（剧号,线路,集号）打的 cur 标优先，全址相等只作兜底
                if (obj.optInt("cur", 0) == 1 || url.equals(page)) {
                    current = Episode.create(name, stream);
                    current.setPageUrl(url);
                    list.add(current);
                } else {
                    Episode ep = Episode.create(name, "");
                    ep.setPageUrl(url);
                    list.add(ep);
                }
            }
            if (current == null || list.size() <= 1) return -1;
            item.getEpisodes().clear();
            item.getEpisodes().addAll(list);
            int currentIndex = list.indexOf(current);
            item.setPosition(currentIndex);
            // MuseTV 注意：不预置 setSelected(true)，见 applyBrowserLines。
            return currentIndex;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata) {
        return player().preload(PlaySpec.from(result, getHistoryKey(), metadata), startPositionMs);
    }

    @Override
    public void clearPreload() {
        if (service() != null && isOwner()) player().clearPreload();
    }

    @Override
    public void loadDanmaku(Result result, History history, Episode episode) {
        VodPlaybackMedia.searchDanmaku(result, history, episode, player()::setDanmaku, player()::addDanmaku);
    }


    /**
     * 网页播放（与手机版 VideoActivity 同批方法，逐字抄）：站主页推导 + 进 WebPlayActivity。
     * 只调共享层现成方法（Site/VodConfig/SpiderDebug/LocalSourceHub/UrlUtil），不改共享层。
     */
    /**
     * 网页播放（A：源声明 webPlay=1）：不走解析、不进原生播放器，直接进站主页的完整浏览器，
     * 站内搜片、换线路、选集全用网站自己的功能（对付鉴权/加密站）。
     */
    private void startWebPlayer(Result result) {
        String home = siteHomePageUrl();
        if (home == null) return;
        openWebPlayer(home, result.getHeader(), result.getClick());
    }

    /** 播放器里的网页播放入口已去掉（2026-11-13 Liu 定）：webview 只从主页页进，按钮恒藏。 */
    private void refreshWebButton() {
        mBinding.control.action.web.setVisibility(View.GONE);
    }

    /** 站主页：webPage 模板的域名优先，其次站点域名（跳过 IP/API），再次蜘蛛详情真实域名；都没有返回 null。 */
    /** 主页模式与主页浏览器入口共用的主页推导：webPage 模板域名 > 站点域名（跳 IP/API）> 蜘蛛详情真实域名。 */
    /** 站点是否走重定向/域名跳转（api/ext 含 [工具: 模板）：这类站的配置首页是跳转页，须用 jar 追踪的真实域名。 */
    private static boolean isRedirectSite(Site site) {
        if (site == null) return false;
        String api = site.getApi();
        if (api != null && api.contains("[工具:")) return true;
        String ext = site.getExt();
        if (ext != null && ext.contains("[工具:")) return true;
        return false;
    }

    public static String siteHomePageUrl(Site site) {
        String url = resolveHomePageUrl(site);
        return url;
    }

    private static String resolveHomePageUrl(Site site) {
        // v60（Liu 2026-11-16 定）：主页取法重排——条目「主页url」> webPage 模板 > 规则里「发布站」/「防丢url」
        // > 规则里「域名-c」/「域名*」> 详情真实域名 > siteBaseUrl 兜底（所有源）。
        // v62：siteBaseUrl 不再只限 rule == null——py/js 源的 ext 也可以是行内 JSON，门会误杀。
        if (site != null) {
            String entry = site.getHomePageUrl();
            if (entry != null && !entry.isEmpty() && !entry.contains("[工具:")) {
                String origin = originOf(entry);
                if (origin != null) {
                    // v21（跳转1 修法，扩大的 A）：条目「主页url」原样返回完整地址，不截 origin——
                    // 跳转逻辑可能活在非根路径上（如 PLMM 防丢url /放松身心/），截成 origin 会落到托管默认页。
                    // 重定向站：http 的发布页是 JS 跳转页（跳第三方中转站会被 webview 跨站拦截成白屏），
                    // https 直接 301 到真实站，走网络层不被拦截——故升 https（v91）。
                    if (isRedirectSite(site) && entry.startsWith("http://")) {
                        entry = "https://" + entry.substring("http://".length());
                    }
                    return entry;
                }
            }
        }
        String template = webPageTemplate(site);
        if (template != null && !template.isEmpty()) {
            String origin = originOf(template);
            if (origin != null) return origin + "/";
        }
        JSONObject rule = ruleJsonObject(site);
        String publish = rulePublishUrl(site, rule);
        if (publish != null) return publish;
        String domain = ruleDomainUrl(site, rule);
        if (domain != null) return domain;
        // detailOrigin 只对重定向站有意义（v89：非重定向站即使误记了也绝不能用，否则主页被带偏）。
        if (site != null && isRedirectSite(site)) {
            String recorded = SpiderDebug.detailOrigin(site.getKey());
            if (recorded != null && !recorded.isEmpty()) {
                String origin = originOf(recorded);
                if (origin != null) return origin + "/";
            }
        }
        // v62：siteBaseUrl 对所有源都当兜底。v60 的 if (rule == null) 门是错的——
        // py 源（MissAV、老版多域名金牌）的 ext 本身就是行内 JSON（含 site 字段），
        // ruleJsonObject 会返回非空，被误判成 JSON 规则源，siteBaseUrl 被跳过，
        // 主页推不出来，浏览器入口就藏起来了。「ext 是 JSON」≠「是 xbpq 规则源」。
        // 放 detailOrigin 后面：跳转站仍优先真实域名（v58 的顺序）。
        String base = siteBaseUrl(site);
        if (base != null && !base.isEmpty()) {
            String origin = originOf(base);
            if (origin != null && !isIpHost(origin)) return origin + "/";
        }
        return null;
    }

    /** 规则 JSON 对象：行内 JSON ext 直接解析，否则读规则文件（仅 .json）。py/js 脚本没有 JSON 规则，返回 null。 */
    private static JSONObject ruleJsonObject(Site site) {
        if (site == null) return null;
        String ext = site.getExt();
        if (ext == null || ext.isEmpty()) return null;
        String rule = ext.trim();
        if (rule.startsWith("{")) {
            try {
                return new JSONObject(rule);
            } catch (Exception ignored) {
                return null;
            }
        }
        File file = ruleFile(site, ext);
        if (file == null || !file.isFile() || !file.getName().toLowerCase().endsWith(".json")) return null;
        String text = readText(file);
        if (text == null) return null;
        try {
            return new JSONObject(text.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** v60：规则里「发布站」/「防丢url」。发布站是静态发布页（如 http://hsck.net/），优先于动态域名。 */
    private static String rulePublishUrl(Site site, JSONObject rule) {
        if (rule == null) return null;
        String home = publishUrlFromObject(site, rule);
        if (home != null) return home;
        if (rule.has("ext")) {
            try {
                JSONObject ext = rule.getJSONObject("ext");
                home = publishUrlFromObject(site, ext);
                if (home != null) return home;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String publishUrlFromObject(Site site, JSONObject object) {
        // 先找「发布站*」（发布站、发布站-b、发布站-s……），再找「防丢url*」。
        for (String prefix : new String[]{"发布站", "防丢url"}) {
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.startsWith(prefix)) continue;
                String raw = object.optString(key, "");
                if (raw.isEmpty()) continue;
                String home = toHomeUrl(site, object, raw, true);
                if (home != null) return home;
            }
        }
        return null;
    }

    /** v60：规则里「域名-c」/「域名*」。「域名-c」优先于其它「域名*」键。 */
    private static String ruleDomainUrl(Site site, JSONObject rule) {
        if (rule == null) return null;
        String home = domainUrlFromObject(site, rule);
        if (home != null) return home;
        if (rule.has("ext")) {
            try {
                JSONObject ext = rule.getJSONObject("ext");
                home = domainUrlFromObject(site, ext);
                if (home != null) return home;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String domainUrlFromObject(Site site, JSONObject object) {
        String rawC = object.optString("域名-c", "");
        if (!rawC.isEmpty()) {
            String home = toHomeUrl(site, object, rawC, false);
            if (home != null) return home;
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.contains("域名") || key.equals("域名-c")) continue;
            String raw = object.optString(key, "");
            if (raw.isEmpty()) continue;
            String home = toHomeUrl(site, object, raw, false);
            if (home != null) return home;
        }
        return null;
    }

    /** 把规则里的一条网址变成可进 webview 的主页地址。
     * 含 [工具: 的是重定向模板：展开后返回完整地址（带查询串），不截 origin——
     * 查询串就是跳转指令，截掉只剩服务根就 404（hsck v60 实证：cktongji.com:8899/ 裸根 404）。
     * keepPath（发布站/防丢url）：原样返回完整地址——跳转逻辑可能活在非根路径上
     * （PLMM /放松身心/，v21 跳转1 修法），截成 origin 会落到托管默认页。
     * 干净网址取 origin；重定向站 http→https（v91）；IP 跳过。 */
    private static String toHomeUrl(Site site, JSONObject object, String raw, boolean keepPath) {
        boolean isTool = raw.contains("[工具:");
        String value = resolveDomainTemplate(object, raw);
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null;
        if (isTool) {
            // 重定向工具 URL：原样返回，让 webview 自己跟 302 到真站。
            return value;
        }
        String origin = originOf(value);
        if (origin == null || isIpHost(origin)) return null;
        if (isRedirectSite(site) && origin.startsWith("http://")) {
            origin = "https://" + origin.substring("http://".length());
        }
        if (keepPath) {
            // 发布站/防丢url：跳转页本身可能在非根路径，原样返回完整地址。
            if (isRedirectSite(site) && value.startsWith("http://")) {
                value = "https://" + value.substring("http://".length());
            }
            return value;
        }
        return origin + "/";
    }
    /** v61：主页是发布站/跳转页时返回其 host（供 WebPlayActivity 放行该页上的人工点按跳转）。
     * 只有「发布站/防丢url」（第 3 步）与「域名-c/域名* 的 [工具:重定向]」（第 4 步工具）算跳转页；
     * 条目主页url、webPage 模板、干净域名直接就是本站，不算。 */
    public static String homeJumpHost(Site site) {
        if (site == null) return null;
        String entry = site.getHomePageUrl();
        if (entry != null && !entry.isEmpty() && !entry.contains("[工具:")) {
            if (originOf(entry) != null) return null;
        }
        String template = webPageTemplate(site);
        if (template != null && !template.isEmpty() && originOf(template) != null) return null;
        JSONObject rule = ruleJsonObject(site);
        String publish = rulePublishUrl(site, rule);
        if (publish != null) return hostOf(publish);
        String domain = ruleDomainUrl(site, rule);
        if (domain != null && domainRawIsTool(rule)) return hostOf(domain);
        return null;
    }

    /** v61：规则里用于取主页的那条域名值是不是 [工具:重定向] 模板（与 domainUrlFromObject 同顺序）。 */
    private static boolean domainRawIsTool(JSONObject rule) {
        if (rule == null) return false;
        String rawC = rule.optString("域名-c", "");
        if (!rawC.isEmpty()) return rawC.contains("[工具:");
        java.util.Iterator<String> keys = rule.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.contains("域名") || key.equals("域名-c")) continue;
            String raw = rule.optString(key, "");
            if (!raw.isEmpty()) return raw.contains("[工具:");
        }
        if (rule.has("ext")) {
            try {
                return domainRawIsTool(rule.getJSONObject("ext"));
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** v61：取 URL 的 host（小写，去端口）；取不到返回 null。 */
    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            String host = Uri.parse(url).getHost();
            return TextUtils.isEmpty(host) ? null : host.toLowerCase(Locale.US);
        } catch (Throwable e) {
            return null;
        }
    }

    private String siteHomePageUrl() {
        return siteHomePageUrl(getSite());
    }

    /** host 是不是纯 IP（API 地址，不是网站）；带端口时先剥端口。 */
    private static boolean isIpHost(String origin) {
        if (origin == null) return true;
        int schemeEnd = origin.indexOf("://") + 3;
        String host = schemeEnd > 2 ? origin.substring(schemeEnd) : origin;
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private static String webPageTemplate(Site site) {
        if (site == null) return null;
        // 站点条目顶层直接写了 webPage（如麦田Ai），优先用它，不再只认 ext/规则文件。
        String top = site.getWebPage();
        if (top != null && !top.isEmpty()) return top;
        String ext = site.getExt();
        if (ext == null || ext.isEmpty()) return null;
        String rule = ext.trim();
        if (rule.startsWith("{")) return webPageFromJson(rule);
        File file = ruleFile(site, ext);
        if (file == null || !file.isFile() || !file.getName().toLowerCase().endsWith(".json")) return null;
        String text = readText(file);
        return text == null ? null : webPageFromJson(text);
    }

    private String webPageTemplate() {
        return webPageTemplate(getSite());
    }

    private static String webPageFromJson(String json) {
        try {
            JSONObject object = new JSONObject(json);
            for (String key : new String[]{"webPage", "webpage", "web_page"}) {
                String value = object.optString(key, "");
                if (!value.isEmpty()) return value;
            }
            // 有的规则把 webPage 写在 ext 里（如麦田旧规则），也认。
            JSONObject ext = object.optJSONObject("ext");
            if (ext != null) {
                for (String key : new String[]{"webPage", "webpage", "web_page"}) {
                    String value = ext.optString(key, "");
                    if (!value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 本站域名：所有类型通用，不只 XBPQ。取法按序：
     * type-1 取 api；行内 JSON ext 取「域名*」键或 site/host/domain 键；本地规则/
     * 脚本文件先定位文件再读——json 规则取「域名*」键，py/js 脚本取脚本里的站点
     * 地址（host/api 赋值优先，没有取文件里第一个站点地址）。
     * 文件定位跟蜘蛛同一基准：先按当前加载的配置文件所在目录，再走扫描目录。
     * 一部片子只算一次。
     */
    private String siteBaseUrl() {
        String key = getKey() + "|" + getVodId();
        if (!key.equals(mSiteBaseUrlKey)) {
            mSiteBaseUrlKey = key;
            mSiteBaseUrl = resolveSiteBaseUrl(getSite());
        }
        return mSiteBaseUrl;
    }

    private static String siteBaseUrl(Site site) {
        return resolveSiteBaseUrl(site);
    }

    private static String resolveSiteBaseUrl(Site site) {
        if (site == null) return null;
        String api = site.getApi();
        // 真实远程地址直接当站点域名；本地代理 /file/ 地址不是远程站，继续往下走文件分支。
        if (api != null && (api.startsWith("http://") || api.startsWith("https://")) && !api.contains("/file/")) return api;
        String ext = site.getExt();
        if (ext == null || ext.isEmpty()) {
            // cat 类 JS 源：脚本路径写在 api 里（ext 为空），也按规则/脚本文件走。
            // api 可能是本地代理地址（http://127.0.0.1:port/file/...），先经 filePathOf 还原成本地路径再判。
            String apiPath = site.getApi();
            if (apiPath != null) {
                String real = filePathOf(apiPath.trim());
                String low = real.toLowerCase();
                if (!low.startsWith("http://") && !low.startsWith("https://") && !low.startsWith("csp_")
                        && (low.endsWith(".js") || low.endsWith(".py") || low.endsWith(".json"))) {
                    ext = apiPath.trim();
                }
            }
        }
        if (ext == null || ext.isEmpty()) return null;
        String rule = ext.trim();
        // 本地代理 /file/ 地址先还原成本地路径，不当远程站直接返回。
        if ((rule.startsWith("http://") || rule.startsWith("https://")) && rule.contains("/file/")) rule = filePathOf(rule);
        if (rule.startsWith("http://") || rule.startsWith("https://")) return rule;
        if (rule.startsWith("{")) {
            // 行内 JSON ext（金牌 ext.site 是多域名逗号列表）：显式「主页url」优先；
            // 没有则 py 的运行时竞速域名优先于静态列表（detailOrigin 只对 py 源有值）。
            try {
                JSONObject o = new JSONObject(rule);
                for (String k : new String[]{"主页url", "homepage", "homePage"}) {
                    String v = o.optString(k, "");
                    if (v.startsWith("http://") || v.startsWith("https://")) return v;
                }
            } catch (Exception ignored) {
            }
            String recorded = SpiderDebug.detailOrigin(site.getKey());
            if (recorded != null) return recorded;
            return domainFromJson(rule);
        }
        File file = ruleFile(site, ext);
        if (file == null || !file.isFile()) return null;
        String text = readText(file);
        if (text == null) return null;
        String name = file.getName().toLowerCase();
        if (name.endsWith(".py") || name.endsWith(".js")) {
            // 源里显式写的前端网址（homepage 注释）优先，盖过 API 地址与详情来源。
            String home = homepageFromScript(text);
            if (home != null) return home;
            // 蜘蛛详情页实际用的域名（py 源运行时解析的镜像站）优先，静态猜测兜底。
            String recorded = SpiderDebug.detailOrigin(site.getKey());
            if (recorded != null) return recorded;
            return domainFromScript(text);
        }
        String domain = domainFromJson(text);
        return domain != null ? domain : domainFromScript(text);
    }

    /** 定位本地规则/脚本文件：绝对路径直取，否则按配置文件所在目录拼，最后走扫描目录。 */
    private static File ruleFile(Site site, String ext) {
        if (ext == null) return null;
        String raw = ext.trim();
        if (raw.startsWith("{") || raw.startsWith("[")) return null;
        String path = filePathOf(raw);
        if (path == null || path.isEmpty()) return null;
        File direct = new File(path);
        if (direct.isAbsolute() && direct.isFile()) return direct;
        String base = configBaseDir();
        if (base != null) {
            File f = new File(base, path);
            if (f.isFile()) return f;
        }
        return LocalSourceHub.siteFile(site.getApi(), ext, null);
    }

    /** ext 的文件写法转成本地路径：file:// 取 path 段，内置 /file/ 服务地址解码后取。 */
    private static String filePathOf(String raw) {
        if (raw.startsWith("file:")) return Uri.parse(raw).getPath();
        if ((raw.startsWith("http://") || raw.startsWith("https://")) && raw.contains("/file/")) {
            return Uri.decode(raw.substring(raw.indexOf("/file/") + 6));
        }
        return raw;
    }

    /** 当前加载的配置文件所在目录，跟蜘蛛解析规则用的是同一个基准。 */
    private static String configBaseDir() {
        try {
            String configUrl = VodConfig.getUrl();
            if (TextUtils.isEmpty(configUrl)) return null;
            String url = configUrl.trim();
            if (url.startsWith("file:")) {
                String path = url.substring(url.indexOf("file:") + 5);
                while (path.startsWith("/")) path = path.substring(1);
                path = "/" + Uri.decode(path);
                return parentOf(path);
            }
            if (url.startsWith("/")) return parentOf(url);
            if (url.startsWith("http://127.0.0.1") && url.contains("/file/")) {
                String path = url.substring(url.indexOf("/file/") + 6);
                path = Uri.decode(path);
                return parentOf(path.startsWith("/") ? path : "/" + path);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : null;
    }

    /** 从 JSON 文本里找站点地址：显式「主页url」优先（源里写死的前端网址），再「域名*」键，再 site/host/domain/baseUrl/url 键；域名值是模板时按同规则的变量替出来。 */
    private static String domainFromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject object = new JSONObject(json);
            String found = domainFromObject(object);
            if (found != null) return found;
            // XBPQ 规则的动态域名变量（域名-c 等）写在 ext 里面，顶层读不到，再进 ext 找一遍。
            if (object.has("ext")) {
                try {
                    JSONObject ext = object.getJSONObject("ext");
                    found = domainFromObject(ext);
                    if (found != null) return found;
                } catch (Exception ignored) {
                    // ext 不是对象（字符串/数组），跳过
                }
            }
        } catch (Exception e) {
            // 不是 JSON，直接放弃
        }
        return null;
    }

    private static String domainFromObject(JSONObject object) {
        boolean hasRedirectTemplate = false;
        for (String key : new String[]{"主页url", "homepage", "homePage"}) {
            String raw = object.optString(key, "");
            if (raw.contains("[工具:")) { hasRedirectTemplate = true; continue; }
            String value = resolveDomainTemplate(object, raw);
            if (value.startsWith("http://") || value.startsWith("https://")) return value;
        }
        // 「域名-c」是发布页/多域名解析出来的当前可用域名，优先于静态的「域名」。
        String rawC = object.optString("域名-c", "");
        if (rawC.contains("[工具:")) {
            hasRedirectTemplate = true;
        } else {
            String c = resolveDomainTemplate(object, rawC);
            if (c.startsWith("http://") || c.startsWith("https://")) return c;
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.contains("域名")) continue;
            String raw = object.optString(key, "");
            if (raw.contains("[工具:")) { hasRedirectTemplate = true; continue; }
            String value = resolveDomainTemplate(object, raw);
            if (value.startsWith("http://") || value.startsWith("https://")) return value;
        }
        for (String key : new String[]{"site", "host", "domain", "baseUrl", "base_url", "url"}) {
            String raw = object.optString(key, "");
            if (raw.contains("[工具:")) { hasRedirectTemplate = true; continue; }
            String value = resolveDomainTemplate(object, raw);
            if (value.startsWith("http://") || value.startsWith("https://")) return value;
        }
        // 本对象含重定向模板（[工具:...]）时，真实域名取蜘蛛实际请求的来源（jar 已解析）。
        if (hasRedirectTemplate) {
            String last = SpiderDebug.lastSpiderOrigin();
            if (last != null && (last.startsWith("http://") || last.startsWith("https://"))) return last;
        }
        return null;
    }

    /** 从 py/js 脚本注释里找显式前端网址：# homepage: URL 或 // homepage: URL（注释不执行，不会跟蜘蛛冲突）；找不到返回 null。 */
    private static String homepageFromScript(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = Pattern.compile("(?i)(?:#|//)\\s*(homepage|home_page|主页url|主页|前台|前端|域名-c|域名c)\\s*[:：]\\s*(https?://[^\\s'\"<>]+)").matcher(text);
        while (m.find()) {
            String origin = originOf(m.group(2));
            if (origin != null && !isLocalOrCodeHost(origin) && !isIpHost(origin)) return origin;
        }
        return null;
    }

    /** 域名模板：{{变量}} 按同一规则的其它字段替出来（最多 5 轮防循环），[工具:...] 后缀去掉。 */
    private static String resolveDomainTemplate(JSONObject object, String value) {
        if (value == null) return "";
        String out = value.trim();
        for (int i = 0; i < 5 && out.contains("{{"); i++) {
            int s = out.indexOf("{{");
            int e = out.indexOf("}}", s + 2);
            if (s < 0 || e < 0) break;
            String name = out.substring(s + 2, e).trim();
            out = out.substring(0, s) + object.optString(name, "") + out.substring(e + 2);
        }
        int tool = out.indexOf("[工具:");
        if (tool >= 0) out = out.substring(0, tool).trim();
        return out;
    }

    /** 从 py/js 脚本里找站点地址：host/api 赋值优先，没有取文件里第一个站点地址（跳过本地代理与代码托管站）。 */
    private static String domainFromScript(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = Pattern.compile("(?i)(host|api|domain|site|baseurl|base_url|base)\\s*=\\s*[\"'](https?://[^\"'\\s]+)[\"']").matcher(text);
        while (m.find()) {
            String origin = originOf(m.group(2));
            if (origin != null && !isLocalOrCodeHost(origin)) return origin;
        }
        Matcher m2 = Pattern.compile("https?://[A-Za-z0-9.-]+(?::\\d+)?").matcher(text);
        while (m2.find()) {
            String origin = m2.group();
            if (isLocalOrCodeHost(origin)) continue;
            return origin;
        }
        return null;
    }

    private static boolean isLocalOrCodeHost(String origin) {
        String host = origin.substring(origin.indexOf("://") + 3).toLowerCase();
        return host.startsWith("127.0.0.1") || host.startsWith("localhost")
                || host.startsWith("raw.githubusercontent.com") || host.startsWith("github.com")
                || host.startsWith("cdn.jsdelivr.net") || host.startsWith("unpkg.com");
    }

    /** 地址只留协议+域名（+端口），路径不要——相对 id 拼上去才不会错位。 */
    private static String originOf(String url) {
        if (url == null) return null;
        int scheme = url.indexOf("://");
        if (scheme < 0) return null;
        int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            // 逗号是多域名列表的分隔符（金牌 ext.site 是逗号列表），到逗号就停，只取第一个域名。
            if (c == '/' || c == '?' || c == '#' || c == ',') {
                end = i;
                break;
            }
        }
        if (end <= start) return null;
        return url.substring(0, end);
    }

    private static String readText(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void onWebPlay() {
        String home = siteHomePageUrl();
        if (home == null) return;
        openWebPlayer(home, null, "");
        hideControl();
    }

    /** 把页面地址里的 -{线路}-{集} 段换成当前集号（如 vodplay/102317-1-1.html → vodplay/102317-1-5.html，第二段才是集号）；没有这段时原样返回。 */
    /** v18 补：电视 v17 的 rewriteEpisode() 调 currentEpisodeNumber()，但 leanback 未定义（手机版 VideoActivity 有此方法）；
     * v17 提交未构建，此遗漏未被发现。照手机版逐字抄，仅一处等价改写：leanback EpisodeAdapter 无 isEmpty()，改用 getItemCount() > 0。 */
    /** 当前集的集号（1 起）：先从集名里取数字（第05集→5），没有取列表位置+1。 */
    private int currentEpisodeNumber() {
        if (mEpisodeAdapter != null && mEpisodeAdapter.getItemCount() > 0) {
            int position = mEpisodeAdapter.getPosition();
            List<Episode> items = mEpisodeAdapter.getItems();
            if (items != null && position >= 0 && position < items.size()) {
                String name = items.get(position).getName();
                if (name != null) {
                    Matcher m = Pattern.compile("第(\\d+)集").matcher(name);
                    if (m.find()) {
                        try {
                            int n = Integer.parseInt(m.group(1));
                            if (n > 0) return n;
                        } catch (Exception ignored) {
                        }
                    }
                    m = Pattern.compile("(\\d+)").matcher(name);
                    int last = -1;
                    while (m.find()) {
                        try {
                            last = Integer.parseInt(m.group(1));
                        } catch (Exception ignored) {
                        }
                    }
                    if (last > 0) return last;
                }
            }
            if (position >= 0) return position + 1;
        }
        return 1;
    }

    private String rewriteEpisode(String url) {
        if (url == null) return null;
        int ep = currentEpisodeNumber();
        Matcher m = Pattern.compile("-(\\d+)-(\\d+)").matcher(url);
        int start = -1, end = -1;
        while (m.find()) {
            start = m.start(2);
            end = m.end(2);
        }
        if (start < 0) return url;
        return url.substring(0, start) + ep + url.substring(end);
    }

    private void openWebPlayer(String url, Map<String, String> header, String click) {
        if (TextUtils.isEmpty(url) || !UrlUtil.isWebUrl(url)) return;
        // 主页模式：站内搜片、换线路、选集全用网站自己的功能，壳子不传集列表/线路。
        if (TextUtils.isEmpty(click)) click = getSite().getClick();
        player().stop();
        // v72：jumpHost 直接取要打开的 url 的 host（即初始页 host），不依赖规则字段名
        //（防丢url/发布站改名也不怕）；防丢url 跳转后由 WebPlayActivity 在 onPageStarted 里一次性跟进。
        String jumpHost = hostOf(url);
        if (jumpHost == null) jumpHost = "";
        WebPlayActivity.start(this, getName(), url, header, new ArrayList<>(), new ArrayList<>(), 0, getKey(), "", click, new ArrayList<>(), jumpHost);
    }

    @Override
    public void renderDetail(Vod item, History history) {
        mHistory = history;
        mBinding.progressLayout.showContent();
        mBinding.name.setText(item.getName());
        mBinding.video.requestFocus();
        App.removeCallbacks(mR4);
        setArtwork(item.getPic());
        checkKeepImg();
        setText(item);
        updateKeep();
        refreshWebButton();
    }

    @Override
    public void renderVodUpdate(Vod item) {
        if (!item.getId().isEmpty()) updateNavigationKey();
        renderVodMetadata(item.getName(), item.getPic());
        setText(item);
    }

    private void renderVodMetadata(String name, String pic) {
        if (name.isEmpty() && pic.isEmpty()) return;
        if (!name.isEmpty()) mBinding.name.setText(name);
        if (!name.isEmpty()) setPartAdapter();
        if (!pic.isEmpty()) setArtwork();
        updateKeep();
    }

    @Override
    public void renderEmptyDetail() {
        showEmpty();
        refreshWebButton();
    }

    @Override
    public void renderFallbackName(String name) {
        mBinding.name.setText(name);
    }

    @Override
    public void renderFlags(List<Flag> items) {
        mBinding.flag.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mFlagAdapter.addAll(items);
        if (items.isEmpty()) refreshWebButton();
    }

    @Override
    public void renderEpisodes(List<Episode> items) {
        setEpisodeAdapter(items);
        if (items == null || items.isEmpty()) refreshWebButton();
    }

    @Override
    public void renderFlagSelection(Flag item) {
        mBinding.flag.setSelectedPosition(mFlagAdapter.indexOf(item));
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
        if (items == null || items.isEmpty()) refreshWebButton();
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
        mQualityAdapter.addAll(result);
        setQualityVisible(visible);
    }

    @Override
    public void renderQualityVisible(boolean visible) {
        setQualityVisible(visible);
    }

    @Override
    public void renderSources(List<Vod> items) {
        mQuickAdapter.addAll(items);
        mBinding.quick.setVisibility(mQuickAdapter.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void renderHistory(History history) {
        mHistory = history;
        mBinding.control.action.opening.setText(history.getOpening() <= 0 ? getString(R.string.play_op) : Util.timeMs(history.getOpening()));
        mBinding.control.action.ending.setText(history.getEnding() <= 0 ? getString(R.string.play_ed) : Util.timeMs(history.getEnding()));
        player().setSpeed(SpeedSetting.getPlayback());
        setScale(getScale());
        setPartAdapter();
    }

    @Override
    public void renderUseParse(boolean useParse) {
        setUseParse(useParse);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void renderArtwork(String url) {
        setArtwork(url);
    }

    @Override
    public void renderDescription(String desc) {
        mBinding.content.setTag(desc);
    }

    @Override
    public void renderPlaybackMetadata(MediaMetadata metadata) {
        if (service() != null && isOwner()) player().setMetadata(metadata);
        mBinding.widget.title.setText(metadata.displayTitle);
        mBinding.widget.title.setSelected(true);
    }

    @Override
    public void onDetailFallbackScheduled() {
        App.post(mR4, 10000);
    }

    @Override
    public void onDetailFallbackCancelled() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void onSearchStarted(String keyword) {
        mBinding.part.setTag(keyword);
        mQuickAdapter.clear();
    }

    @Override
    public void onSearchResult() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
    }

    @Override
    public void showSwitchLine(Flag flag) {
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
    }

    @Override
    public void showSwitchSource(Vod item) {
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
    }

    @Override
    public void showEpisodeReady(Episode item) {
        Notify.show(getString(R.string.play_ready, item.getName()));
    }

    @Override
    public void showNoNext(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_prev : R.string.error_play_next);
    }

    @Override
    public void showNoPrev(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_next : R.string.error_play_prev);
    }

    @Override
    public void finishVod() {
        finish();
    }

    private void checkCast() {
        if (isCast() && !isFullscreen()) enterFullscreen();
        else mBinding.progressLayout.showProgress();
    }

    private void checkId() {
        // 强播直达（跨类型切集）或自动播放开关开（电视默认开，Liu 2026-11-07 定）才强制播；
        // 开关关时详情回来只高亮当前集、等用户点播；开+延时>0 时不强播，
        // 交给 seamless 的延时档（标记选中后读秒起播，开关状态已由设置弹窗写回共享键）
        if (getEpisodeIndex() >= 0 || (PlayerSetting.isAutoPlayTv() && PlayerSetting.getAutoPlayDelay() <= 0)) mVod.setForcePlayOnce(true);
        mVod.checkId();
    }

    private void showEmpty() {
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        mBinding.content.setTag(item.getContent());
        setText(mBinding.year, R.string.detail_year, item.getYear());
        setText(mBinding.area, R.string.detail_area, item.getArea());
        setText(mBinding.type, R.string.detail_type, item.getTypeName());
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.remark, 0, item.getRemarks());
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(view.getText())) return;
        view.setText(Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        view.setLinkTextColor(MDColor.YELLOW_500);
        CustomMovement.bind(view);
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                VodActivity.start(getActivity(), getKey(), result);
                setRedirect(true);
            }
        };
    }

    @Override
    public void onItemClick(Flag item) {
        mVod.selectFlag(item);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mEpisodeAdapter.addAll(items);
        updateEpisodesButton();
        setArrayAdapter(items.size());
        setR2Callback();
    }

    @Override
    public void onItemClick(Episode item) {
        if (shouldEnterFullscreen(item)) return;
        mVod.selectEpisode(item);
    }

    private void setQualityVisible(boolean visible) {
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
        setR2Callback();
    }

    @Override
    public void onItemClick(Result result) {
        mVod.selectQuality(result);
    }

    /**
     * 详情没出内容（如源没写播放数组）时 mHistory 为空；集数组渲染与排序/倒播入口
     * 仍会走到，直接调 mHistory 会空指针崩溃，统一走这里判空。
     */
    private boolean isRevSort() {
        return mHistory != null && mHistory.isRevSort();
    }

    private void setArrayAdapter(int size) {
        if (mHistory == null) return;
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.play_reverse));
        items.add(getString(mHistory.getRevPlayText()));
        mBinding.array.setVisibility(size > 1 ? View.VISIBLE : View.GONE);
        if (isRevSort()) for (int i = size; i > 0; i -= 20) items.add(i + "-" + Math.max(i - 19, 1));
        else for (int i = 0; i < size; i += 20) items.add((i + 1) + "-" + Math.min(i + 20, size));
        mArrayAdapter.addAll(items);
    }

    private int findFocusDown(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick);
        for (int i = 0; i < orders.size(); i++) if (i > index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private int findFocusUp(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick);
        for (int i = orders.size() - 1; i >= 0; i--) if (i < index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private void updateFocus() {
        mPartAdapter.setNextFocusUp(findFocusUp(4));
        mEpisodeAdapter.setNextFocusUp(findFocusUp(2));
        mFlagAdapter.setNextFocusDown(findFocusDown(0));
        mEpisodeAdapter.setNextFocusDown(findFocusDown(2));
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        notifyItemChanged(mBinding.part, mPartAdapter);
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    @Override
    public void onRevSort() {
        mVod.setRevSort(!isRevSort());
        mVod.reverseEpisode(false);
    }

    @Override
    public void onRevPlay(TextView view) {
        if (mHistory == null) return;
        mVod.setRevPlay(!mHistory.isRevPlay());
        view.setText(mHistory.getRevPlayText());
        Notify.show(mHistory.getRevPlayHint());
    }

    private boolean shouldEnterFullscreen(Episode item) {
        boolean enter = !isFullscreen() && item.isSelected();
        if (enter) enterFullscreen();
        return enter;
    }

    private void enterFullscreen() {
        mFocus1 = getCurrentFocus();
        mBinding.video.requestFocus();
        mBinding.video.setForeground(null);
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        mBinding.flag.setSelectedPosition(mFlagAdapter.getPosition());
        mKeyDown.setFull(true);
        setFullscreen(true);
        mFocus2 = null;
    }

    private void exitFullscreen() {
        mBinding.video.setForeground(ResUtil.getDrawable(R.drawable.selector_video));
        mBinding.video.setLayoutParams(mFrameParams);
        getFocus1().requestFocus();
        mKeyDown.setFull(false);
        setFullscreen(false);
        mFocus2 = null;
        hideInfo();
    }

    private void onContent() {
        if (mBinding.content.getTag() == null) return;
        ContentDialog.create().content(mBinding.content.getTag().toString()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
    }

    private void onVideo() {
        if (!isFullscreen()) enterFullscreen();
    }

    private void onChange() {
        mVod.manualSwitchSource();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        mVod.prevEpisode(true);
    }

    private void onNext(boolean notify) {
        Episode item = mEpisodeAdapter.getNext();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
    }

    private void onPrev(boolean notify) {
        Episode item = mEpisodeAdapter.getPrev();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        SpeedSettingDialog.create().player(player()).save(true).show(this);
        hideControl();
    }

    private boolean onSpeedLong() {
        SpeedSetting.putPlayback(PlaybackAction.toggleSpeed(player(), mBinding.widget.message));
        return true;
    }

    private void onReset() {
        onRefresh();
    }

    private void onParse() {
        ParseDialog.create().show(this);
        hideControl();
    }

    private void onReplay() {
        mVod.replay();
    }

    private void onRefresh() {
        mVod.refresh();
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
    }

    private void onOpeningAdd() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) + 1000));
    }

    private void onOpeningSub() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) - 1000));
    }

    private boolean onOpeningReset() {
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
    }

    private void onEndingAdd() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) + 1000));
    }

    private void onEndingSub() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) - 1000));
    }

    private boolean onEndingReset() {
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
    }

    private void onPlayer() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player());
        hideControl();
    }

    private void onDecode() {
        mClock.setCallback(null);
        player().toggleDecode();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).view(mBinding.player.getSubtitleView()).show(this);
        hideControl();
    }

    private void onEdition() {
        EditionDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onChapter() {
        ChapterDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onDanmaku() {
        DanmakuDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl(getFocus2());
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR3, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        // Persistent playInfo: keep it and its tick when the switch is on, so the
        // readout survives loading finishing. Switch off: old hide + reset behavior.
        if (!PlayerSetting.isNetInfo()) {
            mBinding.playInfo.setVisibility(View.GONE);
            App.removeCallbacks(mR3);
            Traffic.reset();
        }
    }

    private void showError(String text) {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.text.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    private void showInfo() {
        mBinding.widget.top.setVisibility(View.VISIBLE);
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(0));
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    /** 集数按钮显隐：选集仅全屏且集数大于 1 时显示；上一集/下一集仅集数大于 1 时显示，单集一律不显示（与手机版一致）。 */
    private void updateEpisodesButton() {
        boolean multi = mEpisodeAdapter != null && mEpisodeAdapter.getItemCount() > 1;
        mBinding.control.action.episodes.setVisibility(isFullscreen() && multi ? View.VISIBLE : View.GONE);
        mBinding.control.action.next.setVisibility(multi ? View.VISIBLE : View.GONE);
        mBinding.control.action.prev.setVisibility(multi ? View.VISIBLE : View.GONE);
    }

    private void showControl(View view) {
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        mBinding.control.action.mediaInfoBtn.setVisibility(PlayerSetting.isMediaInfo() ? View.VISIBLE : View.GONE);
        updateEpisodesButton();
        App.post(mR3, 0);
        updatePlayInfo();
        view.requestFocus();
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
        // Persistent playInfo (net_info switch on): playInfo lives outside control,
        // so hiding control must not hide it or stop its 1s tick. Switch off: old behavior.
        if (!PlayerSetting.isNetInfo() && !isVisible(mBinding.progress.getRoot())) {
            mBinding.playInfo.setVisibility(View.GONE);
            App.removeCallbacks(mR3);
        }
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
        hideInfo();
    }

    private void onEpisodes() {
        if (mEpisodeAdapter == null || mEpisodeAdapter.getItemCount() == 0) return;
        EpisodeDialog.create().episodes(mEpisodeAdapter.getItems()).position(mEpisodeAdapter.getPosition()).show(this);
    }

    private void onMediaInfo() {
        if (!PlayerSetting.isMediaInfo() || player().isEmpty()) return;
        mMediaInfo = !mMediaInfo;
        mBinding.control.action.mediaInfoBtn.setSelected(mMediaInfo);
        if (player().hasNativeMediaInfo()) {
            // MPV draws its own stats overlay inside the video surface;
            // the command only flips it, so the app-side flag must stay
            // in sync with the instance that received the flip (see
            // tickMediaInfo).
            player().toggleMediaInfo();
        } else {
            mBinding.mediaInfoPanel.setVisibility(mMediaInfo ? View.VISIBLE : View.GONE);
            if (mMediaInfo) refreshMediaInfo();
        }
        App.removeCallbacks(mR5);
        if (mMediaInfo) {
            mMediaInfoPlayer = player().getPlayer();
            App.post(mR5, 1000);
        } else {
            mMediaInfoPlayer = null;
        }
    }

    private void refreshMediaInfo() {
        if (!mMediaInfo || player().hasNativeMediaInfo()) return;
        mBinding.mediaInfoPanel.setText(player().getMediaInfoText());
    }

    private void tickMediaInfo() {
        if (!mMediaInfo) return;
        // An engine rebuild (engine switch, MPV decode rebuild) replaces
        // the Player instance and drops a native stats overlay with it;
        // resync the app-side state instead of trusting the stale flag.
        if (player().getPlayer() != mMediaInfoPlayer) {
            resetMediaInfo();
            return;
        }
        refreshMediaInfo();
        App.post(mR5, 1000);
    }

    private void resetMediaInfo() {
        mMediaInfo = false;
        mMediaInfoPlayer = null;
        App.removeCallbacks(mR5);
        mBinding.mediaInfoPanel.setVisibility(View.GONE);
        mBinding.control.action.mediaInfoBtn.setSelected(false);
    }

    private void setTraffic() {
        if (PlayerSetting.isNetInfo()) applySpeed(mBinding.progress.traffic);
        updatePlayInfo();
        App.post(mR3, 1000);
    }

    private void updatePlayInfo() {
        if (service() == null) return;
        if (mMediaInfo) {
            mBinding.playInfo.setVisibility(View.GONE);
            return;
        }
        mBinding.playInfo.setVisibility(View.VISIBLE);
        String sizeText = player().getSizeText();
        if (PlayerSetting.isNetInfo()) {
            String speedText = mBinding.progress.traffic.getText().toString();
            if (!speedText.isEmpty()) {
                if (sizeText.isEmpty()) sizeText = speedText;
                else sizeText = sizeText + " | " + speedText;
            }
        }
        mBinding.playInfo.setText(sizeText);
    }

    private void applySpeed(TextView view) {
        if (TrafficStats.getUidRxBytes(getApplicationInfo().uid) != TrafficStats.UNSUPPORTED) {
            Traffic.setSpeed(view);
            return;
        }
        // Some TV boxes do not support per-UID traffic stats; fall back to
        // the device-wide counters so the readout still works there.
        view.setVisibility(View.VISIBLE);
        long now = System.currentTimeMillis();
        long totalTx = TrafficStats.getTotalTxBytes();
        long totalRx = TrafficStats.getTotalRxBytes() / 1024;
        long elapsed = Math.max(now - mLastRxTime, 1);
        long txSpeed = totalTx == TrafficStats.UNSUPPORTED ? -1 : (totalTx / 1024 - mLastTxBytes) * 1000 / elapsed;
        long rxSpeed = (totalRx - mLastRxBytes) * 1000 / elapsed;
        mLastTxBytes = totalTx == TrafficStats.UNSUPPORTED ? mLastTxBytes : totalTx / 1024;
        mLastRxBytes = totalRx;
        mLastRxTime = now;
        view.setText("[↑" + formatFallbackSpeed(txSpeed) + " ↓" + formatFallbackSpeed(rxSpeed) + "]");
    }

    private String formatFallbackSpeed(long speed) {
        return speed < 0 ? "-- KB/s" : speed < 1000 ? speed + " KB/s" : String.format("%.1f MB/s", speed / 1024f);
    }

    private void setR1Callback() {
        if (isScrubbing()) return;
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onScrubbingChanged(boolean scrubbing) {
        if (scrubbing) App.removeCallbacks(mR1);
        else if (isVisible(mBinding.control.getRoot())) setR1Callback();
    }

    private void setR2Callback() {
        App.post(mR2, 500);
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        ImgUtil.load(this, mHistory.getVodPic(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.player.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.player.setDefaultArtwork(errorDrawable);
            }
        });
    }

    private void setPartAdapter() {
        mPartAdapter.addAll(PartUtil.split(mHistory.getVodName()));
        mBinding.part.setVisibility(View.VISIBLE);
        setR2Callback();
    }

    private void saveHistory(boolean exit) {
        PlaybackService service = service();
        boolean owner = service != null && getPlaybackKey().equals(service.player().getKey());
        long position = owner ? service.player().getPosition() : C.TIME_UNSET;
        long duration = owner ? service.player().getDuration() : C.TIME_UNSET;
        if (mVod != null) mVod.saveHistory(exit, System.currentTimeMillis(), position, duration);
    }

    private void checkKeepImg() {
        mBinding.keep.setCompoundDrawablesWithIntrinsicBounds(Keep.find(getHistoryKey()) == null ? R.drawable.ic_detail_keep_off : R.drawable.ic_detail_keep_on, 0, 0, 0);
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(mHistory.getVodPic());
        keep.setVodName(mHistory.getVodName());
        keep.setSiteName(getSite().getName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    private void updateKeep() {
        Keep keep = Keep.find(getHistoryKey());
        if (keep != null) {
            keep.setVodName(mHistory.getVodName());
            keep.setVodPic(mHistory.getVodPic());
            keep.save();
        }
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        setPlaybackMode();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
        if (mMediaInfo && player().getPlayer() != mMediaInfoPlayer) resetMediaInfo();
        else refreshMediaInfo();
    }

    @Override
    protected void onMediaOptionsChanged() {
        setMediaOptionVisible();
    }

    @Override
    protected void onError(String msg) {
        // push v32（方案 R，回落 b；电视 v19 补）：网页源解析器与 WebView 嗅探都失败 → 提示并用浏览器打开该集播放页
        //（与主页浏览器入口同一套 openWebPlayer 参数，推送/探针/去广告照旧；电视焦点照现规矩落返回键）。只认嗅探失败（error_play_parse），其他报错走原路。
        if (mWebSniffPage != null && getSite().isWebSource() && getString(R.string.error_play_parse).equals(msg)) {
            String page = mWebSniffPage;
            mWebSniffPage = null;
            hideProgress();
            Notify.show("解析失败，已用浏览器打开");
            openWebPlayer(page, null, "");
            return;
        }
        mVod.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        mVod.refresh();
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                mClock.setCallback(null);
                break;
            case Player.STATE_READY:
                hideProgress();
                player().reset();
                mClock.setCallback(this);
                break;
            case Player.STATE_ENDED:
                hideProgress();
                mVod.playbackEnded();
                mClock.setCallback(null);
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            hideCenter();
        } else if (isPaused()) {
            if (isFullscreen()) showInfo();
            else hideInfo();
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner() || !player().isVod()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position < 0 || duration <= 0) return;
        mVod.onTimeChanged(time, position, duration);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) {
            mVod.setForcePlayOnce(true);
            mVod.requestDetail();
        }
        else if (event.getType() == RefreshEvent.Type.PLAYER) mVod.refresh();
        else if (event.getType() == RefreshEvent.Type.VOD) mVod.updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    private void setTrackVisible() {
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
    }

    private void setMediaOptionVisible() {
        PlaybackAction.setMediaOptions(player(), mBinding.control.action.edition, mBinding.control.action.chapter);
    }

    @Override
    public void onItemClick(Vod item) {
        mVod.selectSource(item);
    }

    @Override
    public void onParse(Parse item) {
        mVod.selectParse(item);
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
    }

    private boolean onSeekBack() {
        controller().seekBack();
        return true;
    }

    private boolean onSeekForward() {
        controller().seekForward();
        return true;
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    private View getFocus1() {
        return mFocus1 == null || mFocus1.getVisibility() != View.VISIBLE ? mBinding.video : mFocus1;
    }

    private View getFocus2() {
        return mFocus2 == null || mFocus2.getVisibility() != View.VISIBLE || mFocus2 == mBinding.control.action.opening || mFocus2 == mBinding.control.action.ending ? mBinding.control.action.next : mFocus2;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isFullscreen() && KeyUtil.isMenuKey(event)) onToggle();
        if (isVisible(mBinding.control.getRoot())) setR1Callback();
        if (isVisible(mBinding.control.getRoot())) mFocus2 = getCurrentFocus();
        if (isFullscreen() && isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event) && service() != null) return mKeyDown.onKeyDown(event);
        if (KeyUtil.isMediaFastForward(event)) return onSeekForward();
        if (KeyUtil.isMediaRewind(event)) return onSeekBack();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (seekTo(time)) hideCenter();
        mKeyDown.reset();
    }

    @Override
    public void onSpeedUp() {
        if (!player().isPlaying()) return;
        PlaybackAction.startSpeedPress(player(), mBinding.widget.message);
    }

    @Override
    public void onSpeedEnd() {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        player().setSpeed(SpeedSetting.getPlayback());
    }

    @Override
    public void onKeyUp() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) {
            showControl(mBinding.control.action.opening);
        } else if (player().canSetEnding(position, duration)) {
            showControl(mBinding.control.action.ending);
        } else {
            showControl(getFocus2());
        }
    }

    @Override
    public void onKeyDown() {
        showControl(getFocus2());
    }

    @Override
    public void onKeyCenter() {
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        if (isFullscreen()) onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isFullscreen()) onKeyCenter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlaybackIntent.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
        if (requestCode == NovelActivity.REQUEST_NOVEL || requestCode == ImageActivity.REQUEST_IMAGE) {
            int position = data == null ? -1 : data.getIntExtra("episode_position", -1);
            if (resultCode != RESULT_OK || position < 0 || mEpisodeAdapter == null || position >= mEpisodeAdapter.getItemCount()) finish();
            else {
                // 小说朗读跨章接力：上一实例离开时正在朗读，则新实例自动续读。
                mNovelTtsContinue = requestCode == NovelActivity.REQUEST_NOVEL && data.getBooleanExtra("tts_continue", false);
                mVod.selectEpisode(mEpisodeAdapter.getItems().get(position));
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveHistory(false);
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.center)) {
            hideCenter();
        } else if (isFullscreen()) {
            exitFullscreen();
        } else {
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        mClock.release();
        saveHistory(true);
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4, mR5);
        super.onDestroy();
    }
}


