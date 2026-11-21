package com.muse.android.tv.ui.activity;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.ChangeBounds;
import androidx.transition.TransitionManager;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.github.catvod.crawler.SpiderDebug;
import com.muse.android.tv.App;
import com.muse.android.tv.Constant;
import com.muse.android.tv.R;
import com.muse.android.tv.api.DanmakuApi;
import com.muse.android.tv.api.SiteApi;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.api.loader.LocalSourceHub;
import com.muse.android.tv.api.loader.HttpEpisodeResolver;
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
import com.muse.android.tv.event.CastEvent;
import com.muse.android.tv.event.RefreshEvent;
import com.muse.android.tv.impl.CustomTarget;
import com.muse.android.tv.model.VideoViewModel;
import com.muse.android.tv.playback.PlaybackAction;
import com.muse.android.tv.playback.PlaybackIntent;
import com.muse.android.tv.playback.PlaybackOrientation;
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
import com.muse.android.tv.ui.adapter.EpisodeAdapter;
import com.muse.android.tv.ui.adapter.FlagAdapter;
import com.muse.android.tv.ui.adapter.QualityAdapter;
import com.muse.android.tv.ui.adapter.QuickAdapter;
import com.muse.android.tv.ui.base.ViewType;
import com.muse.android.tv.ui.custom.CustomKeyDown;
import com.muse.android.tv.ui.custom.CustomMovement;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.muse.android.tv.ui.dialog.CastDialog;
import com.muse.android.tv.ui.dialog.ChapterDialog;
import com.muse.android.tv.ui.dialog.ControlDialog;
import com.muse.android.tv.ui.dialog.DanmakuDialog;
import com.muse.android.tv.ui.dialog.EditionDialog;
import com.muse.android.tv.ui.dialog.EpisodeGridDialog;
import com.muse.android.tv.ui.dialog.InfoDialog;
import com.muse.android.tv.ui.dialog.ParseDialog;
import com.muse.android.tv.ui.dialog.PlayerEngineDialog;
import com.muse.android.tv.ui.dialog.ReceiveDialog;
import com.muse.android.tv.ui.dialog.SpeedSettingDialog;
import com.muse.android.tv.ui.dialog.TrackDialog;
import com.muse.android.tv.ui.activity.ImageActivity;
import com.muse.android.tv.ui.novel.NovelReaderActivity;
import com.muse.android.tv.utils.Clock;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.FileUtil;
import com.muse.android.tv.utils.ImgUtil;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.PiP;
import com.muse.android.tv.utils.ResUtil;
import com.muse.android.tv.utils.Sniffer;
import com.muse.android.tv.utils.Timer;
import com.muse.android.tv.utils.Traffic;
import com.muse.android.tv.utils.UrlUtil;
import com.muse.android.tv.utils.Util;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONArray;
import org.json.JSONObject;
import org.greenrobot.eventbus.ThreadMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoActivity extends PlaybackActivity implements Clock.Callback, CustomKeyDown.Listener, ControlDialog.Listener, ParseDialog.Listener, VodPlaybackHost, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, CastDialog.Listener, InfoDialog.Listener {

    private ActivityVideoBinding mBinding;
    private VideoViewModel mViewModel;
    private VodPlaybackController mVod;
    private FlagAdapter mFlagAdapter;
    private EpisodeAdapter mEpisodeAdapter;
    private QualityAdapter mQualityAdapter;
    private QuickAdapter mQuickAdapter;
    private ViewGroup.LayoutParams mFrameParams;
    private ValueAnimator mAnimator;
    private CustomKeyDown mKeyDown;
    private Clock mClock;
    private PiP mPiP;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Runnable mR5;
    private Runnable mR6;
    private Runnable mR7;
    private Player mMediaInfoPlayer;
    private boolean mMediaInfo;
    /** #104：本详情是否已发起过播放请求（onPlaybackRequested 置位，新详情 renderDetail 时清零）。 */
    private boolean mPlayRequested;
    /** push v17 F4(a)：applyBrowserLines 是否已把全集表摆进 vod（摆了表但认不出当前集时返回 -1，applyBrowserMeta 不走单线回落）。 */
    private boolean mBrowserLinesApplied;
    /** push v18 (c)：浏览器内点集解流进行中的回调（销毁时取消该单）。 */
    private WebPlayActivity.BrowserResolveCallback mBrowserResolveCallback;
    private long mInfoTxBytes;
    private long mInfoRxBytes;
    private long mInfoTime;
    private History mHistory;
    /** 网页播放兜底（B）：本集走了解析/嗅探时存下的解析前网页地址，进 WebPlayActivity 用。 */
    private String mWebUrl;
    private List<Flag> mFlags = new ArrayList<>();
    private Map<String, String> mWebHeader;
    private String mSiteBaseUrl;
    private String mSiteBaseUrlKey;
    private boolean fullscreen;
    private boolean useParse;
    private boolean rotate;

    /** push v2：浏览器全集待解析集的请求头（HTTP 解析完即用；startPlayback 合并后清空——fuzi 在 getPlayer 里先清后读的 bug 不带过来）。 */
    private Map<String, String> mWebEpHeaders = Collections.emptyMap();

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
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic());
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true);
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
        start(activity, key, id, name, pic, mark, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
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

    /** 明确选集跳转（如阅读器内选中视频集）：进详情后直接播 mark 指定的集，不受自动播放开关/延时影响。 */
    public static void startForcePlay(Activity activity, String key, String id, String name, String pic, String mark, int episodeIndex) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", false);
        intent.putExtra("force_play", true);
        intent.putExtra("mark", mark);
        intent.putExtra("episode_index", episodeIndex);
        intent.putExtra("name", name);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        putPic(intent, pic);
        activity.startActivity(intent);
    }

    public static void startForcePlay(Activity activity, String key, String id, String name, String pic, String mark) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", false);
        intent.putExtra("force_play", true);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        putPic(intent, pic);
        activity.startActivity(intent);
    }

    private static void putPic(Intent intent, String pic) {
        intent.removeExtra("pic");
        pic = ImgUtil.cache(pic);
        if (!TextUtils.isEmpty(pic)) intent.putExtra("pic", pic);
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

    /** 跨类型切集直达的目标集序号（阅读器选中视频集时传入，未传为 -1）。 */
    @Override
    public int getEpisodeIndex() {
        return getIntent().getIntExtra("episode_index", -1);
    }

    private boolean isForcePlay() {
        return getIntent().getBooleanExtra("force_play", false);
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

    private boolean isAutoRotate() {
        return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    private boolean isLand() {
        return mBinding.getRoot().getTag().equals("land");
    }

    private boolean isPort() {
        return mBinding.getRoot().getTag().equals("port");
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
        checkLand();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (TextUtils.isEmpty(intent.getStringExtra("id")) || isSameVideo(intent)) return;
        mBinding.swipeLayout.setRefreshing(true);
        saveHistory(true);
        mVod.reset();
        setIntent(intent);
        updateNavigationKey();
        checkControl();
        setOrient();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> setStatusBar(insets));
        mKeyDown = CustomKeyDown.create(this, mBinding.player);
        mFrameParams = mBinding.video.getLayoutParams();
        mBinding.progressLayout.showProgress();
        mBinding.swipeLayout.setEnabled(false);
        mClock = Clock.create();
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        mR3 = this::setOrient;
        mR4 = this::showEmpty;
        mR5 = this::updateNetInfo;
        mR6 = this::hideNetInfo;
        mR7 = this::tickMediaInfo;
        mPiP = new PiP();
        checkDanmakuImg();
        setRecyclerView();
        setVideoView();
        setViewModel();
        showProgress();
        setAnimator();
        checkId();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.name.setOnClickListener(view -> onName());
        mBinding.more.setOnClickListener(view -> onMore());
        mBinding.actor.setOnClickListener(view -> onActor());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.reverse.setOnClickListener(view -> onReverse());
        mBinding.director.setOnClickListener(view -> onDirector());
        mBinding.name.setOnLongClickListener(view -> onChange());
        mBinding.content.setOnLongClickListener(view -> onCopy());
        mBinding.control.back.setOnClickListener(view -> onBack());
        mBinding.control.cast.setOnClickListener(view -> onCast());
        mBinding.control.info.setOnClickListener(view -> onInfo());
        mBinding.control.keep.setOnClickListener(view -> onKeep());
        mBinding.control.play.setOnClickListener(view -> checkPlay());
        mBinding.control.next.setOnClickListener(view -> checkNext());
        mBinding.control.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.setting.setOnClickListener(view -> onSetting());
        mBinding.control.title.setOnLongClickListener(view -> onChange());
        mBinding.control.right.lock.setOnClickListener(view -> onLock());
        mBinding.control.right.rotate.setOnClickListener(view -> onRotate());
        mBinding.control.danmaku.setOnClickListener(view -> onDanmakuShow());
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onPlayer());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodes());
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.mediaInfoBtn.setOnClickListener(view -> onMediaInfo());
        mBinding.mediaInfoPanel.setOnClickListener(view -> { if (mMediaInfo) onMediaInfo(); });
        mBinding.control.fullscreen.setOnClickListener(view -> onFullscreen());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.control.action.getRoot().setOnTouchListener(this::onActionTouch);
        mBinding.swipeLayout.setOnRefreshListener(this::onSwipeRefresh);
    }

    private WindowInsetsCompat setStatusBar(WindowInsetsCompat insets) {
        int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        ViewGroup.LayoutParams lp = mBinding.statusBar.getLayoutParams();
        lp.height = top;
        mBinding.statusBar.setLayoutParams(lp);
        return insets;
    }

    private void setRecyclerView() {
        mBinding.flag.setHasFixedSize(true);
        mBinding.flag.setItemAnimator(null);
        mBinding.flag.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
        mBinding.episode.setHasFixedSize(true);
        mBinding.episode.setItemAnimator(null);
        mBinding.episode.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this, ViewType.HORI));
        mBinding.quality.setHasFixedSize(true);
        mBinding.quality.setItemAnimator(null);
        mBinding.quality.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
    }

    private void setVideoView() {
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.danmaku.setVisibility(DanmakuSetting.isLoad() ? View.VISIBLE : View.GONE);
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mPiP.update(this, view));
    }

    private void setVideoView(boolean isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        } else {
            mBinding.video.setLayoutParams(mFrameParams);
        }
    }

    private void setAnimator() {
        mAnimator = new ValueAnimator();
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(animation -> {
            if (isLand() || isFullscreen() || isInPictureInPictureMode()) return;
            mFrameParams.height = (int) animation.getAnimatedValue();
            mBinding.video.setLayoutParams(mFrameParams);
        });
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(VideoViewModel.class);
        mVod = mViewModel.createPlaybackController(this);
        if (isForcePlay()) mVod.setForcePlayOnce(true);
        observeWhenServiceReady(mViewModel.getDetail(), this::onDetailObserved);
        observeWhenServiceReady(mViewModel.getSearch(), this::onSearchObserved);
        observeWhenServiceReady(mViewModel.getPreload(), this::onPreloadObserved);
        observeWhenServiceReady(mViewModel.getPlayback(), this::onPlaybackObserved);
    }

    private void onDetailObserved(VodDetailResult result) {
        // push v2：浏览器转播放——详情落下后先把网页侧的片名/线路/集名摆进 VOD，再走正常详情流；
        // 当前集序号经 episode_index 透传给选集（不用集名匹配），用完立即清掉，免得后续切线误用。
        int browserIndex = -1;
        if (result != null && result.result() != null && (getIntent().hasExtra("browserTitle") || getIntent().hasExtra("browserLines"))) {
            browserIndex = applyBrowserMeta(result.result().getVod());
        }
        // push v15：单片推送（无线表/全集表；标题为空时 applyBrowserMeta 不进）也要给当前集记 pageUrl，
        // 否则 v4 探活与 v5 报错恢复都因 pageUrl 为空跳过，过期签名流直放即报 Bad HTTP Status 且无重试。
        // 已记过 pageUrl 或非单片时 markSinglePushEpisode 自身为空操作。
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
        mPlayRequested = true;
        showProgress();
    }

    @Override
    public void prepareSource(Vod item) {
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("id", item.getId());
        mBinding.swipeLayout.setRefreshing(true);
        mBinding.swipeLayout.setEnabled(false);
        putPic(getIntent(), item.getPic());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        player().reset();
        player().stop();
        checkControl();
    }

    @Override
    public void stopPlaybackForRefresh() {
        player().stop();
        player().clear();
        mClock.setCallback(null);
        checkControl();
    }

    @Override
    public void resetPlaybackForError(String msg) {
        mBinding.swipeLayout.setEnabled(true);
        PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        player().replay(position);
    }

    /**
     * novel:// 分支（#58）：蜘蛛 playerContent 返回小说载荷时，组装章节串交给内置小说阅读器，
     * 不再直交播放器（必然失败）。组装完成后结束本页（原设计）：详情页对小说集是自动播放的，
     * 保留详情页会让返回落到自动起播的播放页、退不回详情页浏览（#63 按 Liu 拍板恢复；
     * #62 曾改为保留详情页，已回滚）。
     */
    private void startNovelReader(String url) {
        ArrayList<String> chapters = new ArrayList<>();
        int position = 0;
        if (mEpisodeAdapter != null) {
            int origin = mEpisodeAdapter.getPosition();
            List<Episode> all = mEpisodeAdapter.getItems();
            int cursor = 0;
            for (int i = 0; i < all.size(); i++) {
                Episode item = all.get(i);
                String name = item == null ? "" : item.getName();
                String body = item == null ? "" : item.getUrl();
                if (body == null || body.isEmpty()) continue;
                if (i <= origin) position = cursor;
                chapters.add((name == null ? "" : name) + "$" + body);
                cursor++;
            }
        }
        if (chapters.isEmpty()) chapters.add("$" + url);
        Flag selected = mFlagAdapter == null || mFlagAdapter.isEmpty() ? null : mFlagAdapter.get(mFlagAdapter.getPosition());
        String flag = selected == null ? "" : selected.getFlag();
        Site site = getSite();
        NovelReaderActivity.start(this, getName(), getKey(), flag, chapters, position,
                getHistoryKey(), getName(), getPic(), site == null ? "" : site.getName());
        finish();
    }

    /**
     * pics:// 分支（#61）：蜘蛛 playerContent 返回漫画/图片载荷时，交给内置图片播放器，
     * 不再直交播放器（必然失败）。集列表（名/标识）与当前集位随行传入，图片页内切话
     * 经 onActivityResult 的 episode_position 回传，本页 selectEpisode 续播该话。
     */
    private void startImageViewer(Result result, String picsUrl, MediaMetadata metadata) {
        String title = metadata != null && metadata.title != null ? metadata.title.toString() : "";
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> ids = new ArrayList<>();
        int position = 0;
        if (mEpisodeAdapter != null) {
            position = mEpisodeAdapter.getPosition();
            for (Episode item : mEpisodeAdapter.getItems()) {
                names.add(item == null ? "" : item.getName());
                ids.add(item == null ? "" : item.getUrl());
            }
        }
        ImageActivity.startForResult(this, title, picsUrl, result.getHeader(), names, ids, position, getKey(), getId(), getName(), getPic());
    }

    /**
     * 网页播放（A：源声明 webPlay=1）：不走解析、不进原生播放器，直接进站主页的完整浏览器，
     * 站内搜片、换线路、选集全用网站自己的功能（对付鉴权/加密站）。
     */
    private void startWebPlayer(Result result) {
        String home = siteHomePageUrl();
        if (home == null) return;
        openWebPlayer(home, result.getHeader(), result.getClick());
    }

    /**
     * 网页播放（B）：按钮长显——锚点是当前这部片子的详情页/播放页，有地址就亮，
     * 没地址才藏；这条路与直链/嗅探并行，排最后（先直链，后嗅探，再 webview）。
     * 本集走过解析/嗅探且给的是网页地址时，锚点用这张播放页（比详情页更准）；
     * 播放结果是直链流地址或 msearch:/push:// 占位符时不当地址，锚点退回详情页。
     * 解析没出最终地址时（needParse 仍真）playUrl 是解析服务地址不是页面，
     * 绝不能当锚点——否则 WebView 打开的是解析接口的报错 JSON。
     */
    private void setWebFallback(Result result, boolean useParse) {
        String playUrl = result.getUrl().v();
        if (isWebPageUrl(playUrl) && (!result.needParse() || isSameSitePlayerPage(playUrl))) {
            mWebUrl = playUrl;
            mWebHeader = result.getHeader();
        } else {
            // 解析没出地址时锚点取详情页，且要跟当前集走（集换了锚点也要换，不能留上一集的）。
            showWebFallbackForDetail();
        }
        mBinding.control.action.web.setVisibility(TextUtils.isEmpty(mWebUrl) ? View.GONE : View.VISIBLE);
    }

    /**
     * 解析也要走的播放页：本站同域 + 以 .html/.htm 结尾的页面地址（如 CAT 站 /player/xxx.html，
     * 详情页没有播放器数据，播了也是黑屏）。解析服务地址（跨域的 /api/、带 key 的接口）不当锚点。
     */
    private boolean isSameSitePlayerPage(String url) {
        if (url == null) return false;
        String path = url.split("[?#]", 2)[0].toLowerCase(Locale.US);
        if (!path.endsWith(".html") && !path.endsWith(".htm")) return false;
        String base = siteBaseUrl();
        if (base == null) return false;
        try {
            String h1 = Uri.parse(url).getHost();
            String h2 = Uri.parse(base).getHost();
            if (h1 == null || h2 == null) return false;
            h1 = h1.toLowerCase(Locale.US);
            h2 = h2.toLowerCase(Locale.US);
            return h1.equals(h2) || h1.endsWith("." + h2) || h2.endsWith("." + h1);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 能进 WebView 的页面地址：http(s) 且不是流媒体直链后缀。 */
    private boolean isWebPageUrl(String url) {
        if (!UrlUtil.isWebUrl(url)) return false;
        String path = url.split("[?#]", 2)[0].toLowerCase();
        return !path.endsWith(".m3u8") && !path.endsWith(".mp4") && !path.endsWith(".mkv")
                && !path.endsWith(".ts") && !path.endsWith(".flv") && !path.endsWith(".mov")
                && !path.endsWith(".webm") && !path.endsWith(".m4s");
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
                    // 重定向站：http 的发布页是 JS 跳转页（跳第三方中转站会被 webview 跨站拦截成白屏），
                    // https 直接 301 到真实站，走网络层不被拦截——故升 https（v91）。
                    if (isRedirectSite(site) && origin.startsWith("http://")) {
                        origin = "https://" + origin.substring("http://".length());
                    }
                    return origin + "/";
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
                String home = toHomeUrl(site, object, raw);
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
            String home = toHomeUrl(site, object, rawC);
            if (home != null) return home;
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.contains("域名") || key.equals("域名-c")) continue;
            String raw = object.optString(key, "");
            if (raw.isEmpty()) continue;
            String home = toHomeUrl(site, object, raw);
            if (home != null) return home;
        }
        return null;
    }

    /** 把规则里的一条网址变成可进 webview 的主页地址。
     * 含 [工具: 的是重定向模板：展开后返回完整地址（带查询串），不截 origin——
     * 查询串就是跳转指令，截掉只剩服务根就 404（hsck v60 实证：cktongji.com:8899/ 裸根 404）。
     * 干净网址取 origin；重定向站 http→https（v91）；IP 跳过。 */
    private static String toHomeUrl(Site site, JSONObject object, String raw) {
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

    /**
     * 播放没出任何地址时：锚点先取本片详情页，取不到退回本集原始地址。
     * 走到这里的路：播放请求回空（setWebFallback）、详情根本没线路
     * （renderFlags 空）、有线路没集（renderEpisodes 空）、详情加载失败
     * （renderEmptyDetail）——没集可点、没详情可看时，按钮也要亮。
     */
    private void showWebFallbackForDetail() {
        mWebUrl = detailPageUrl();
        if (TextUtils.isEmpty(mWebUrl)) mWebUrl = episodePageUrl();
        mWebHeader = null;
        Site site = getSite();
    }

    /** 本片详情页地址：详情请求用的 id，绝对地址直接用，相对地址拼本站域名；裸 id 走配置里的页面模板。 */
    private String detailPageUrl() {
        String id = getVodId();
        if (id == null || id.isEmpty()) return null;
        if (id.startsWith("http://") || id.startsWith("https://")) return id;
        if (id.startsWith("msearch:") || id.startsWith("push://")) return null;
        // 复合 id（名$$$地址，如麦田 XBPQ 的「墨雨云间$$$详情地址」）：先从其中取出可用的一段。
        String composite = detailPageUrlFromComposite(id);
        if (composite != null) return composite;
        if (!id.contains("/") && !id.contains("?")) return webPageUrl(id);
        String base = siteBaseUrl();
        if (base == null) return null;
        String url;
        if (base.contains("?") && base.endsWith("=")) url = base + id;
        else url = UrlUtil.resolve(base, id);
        return url != null && (url.startsWith("http://") || url.startsWith("https://")) ? url : null;
    }

    /** 裸 id 源的页面地址：配置里给的页面模板（ext 行内 JSON 或规则文件里的 webPage 键，{id} 替成详情 id，{ep} 替成当前集号；模板里 {id} 后紧跟的 -{集}-{源} 段也按当前集换，如 vodplay/{id}-1-1.html）。 */
    private String webPageUrl(String id) {
        String template = webPageTemplate();
        if (template == null || !template.contains("{id}")) return null;
        int ep = currentEpisodeNumber();
        String tpl = template;
        if (!tpl.contains("{ep}")) {
            int at = tpl.indexOf("{id}");
            if (at >= 0) {
                Matcher m = Pattern.compile("-(\\d+)-(\\d+)").matcher(tpl);
                m.region(at + 4, tpl.length());
                if (m.find()) tpl = tpl.substring(0, m.start(1)) + ep + tpl.substring(m.end(1));
            }
        }
        String url = tpl.replace("{id}", id).replace("{ep}", String.valueOf(ep));
        return UrlUtil.isWebUrl(url) ? url : null;
    }

    /** 当前集的集号（1 起）：先从集名里取数字（第05集→5），没有取列表位置+1。 */
    private int currentEpisodeNumber() {
        if (mEpisodeAdapter != null && !mEpisodeAdapter.isEmpty()) {
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

    /** 复合 id 里取可用的一段：本站播放页直返；带页后缀的地址直返；纯数字 id 或地址里 id=数字时走 webPage 模板。 */
    private String detailPageUrlFromComposite(String id) {
        if (!id.contains("$$$")) return null;
        for (String raw : id.split("\\$\\$\\$")) {
            if (raw == null) continue;
            String part = raw.trim();
            if (part.isEmpty()) continue;
            if (isSameSitePlayerPage(part)) return part;
            if (isWebPageUrl(part)) {
                String path = part.split("[?#]", 2)[0];
                if (path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php")) return part;
            }
            if (part.matches("\\d+")) {
                String url = webPageUrl(part);
                if (url != null) return url;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]id=(\\d+)").matcher(part);
            if (m.find()) {
                String url = webPageUrl(m.group(1));
                if (url != null) return url;
            }
        }
        return null;
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

    /** 本集在详情里的原始播放地址，只认 http(s) 网页地址。 */
    private String episodePageUrl() {
        if (mEpisodeAdapter == null || mEpisodeAdapter.isEmpty()) return null;
        List<Episode> items = mEpisodeAdapter.getItems();
        int position = mEpisodeAdapter.getPosition();
        if (items == null || position < 0 || position >= items.size()) return null;
        Episode item = items.get(position);
        String url = item == null ? null : item.getUrl();
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) return null;
        String path = url.split("[?#]", 2)[0].toLowerCase();
        if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mkv")
                || path.endsWith(".ts") || path.endsWith(".flv") || path.endsWith(".mov")
                || path.endsWith(".webm") || path.endsWith(".m4s")) return null;
        return url;
    }

    private void onWebPlay() {
        String home = siteHomePageUrl();
        if (home == null) return;
        openWebPlayer(home, null, "");
        hideControl();
    }

    /** 把页面地址里的 -{线路}-{集} 段换成当前集号（如 vodplay/102317-1-1.html → vodplay/102317-1-5.html，第二段才是集号）；没有这段时原样返回。 */
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
    public void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata) {
        String url = result.getUrl().v();
        if (url != null && url.startsWith("novel://")) {
            startNovelReader(url);
            return;
        }
        if (url != null && url.startsWith("pics://")) {
            startImageViewer(result, url, metadata);
            return;
        }
        if (getSite().isWebPlay()) {
            startWebPlayer(result);
            return;
        }
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
        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, metadata);
    }

    // ===== push v2：浏览器转播放落地（多线表 + HTTP 集解析）=====

    /** push v2：浏览器待解析集（url 为空、pageUrl 为播放页地址）——走纯 HTTP 解析器取直链，解析成功后把直链写回该集并续播。 */
    @Override
    public boolean resolveWebEpisode(Flag flag, Episode episode) {
        if (episode == null || TextUtils.isEmpty(episode.getPageUrl())) return false;
        // push v18 (c)：无 href 按钮式选集（探针带 data-id，如 gaze）不走 HTTP 解析器——
        // 解析器对这类集四层全败；改走后台栈里的现有浏览器点按钮、站方自己解流、嗅探抓回。
        // 只对 url 为空且带 data-id 的集启用，其余站（MacCMS/子页/MissAV/hsck/NB/P站/奇优/枫叶）
        // 一律走原路、不动。浏览器已关/点按失败/15 秒超时回落现有 toast。
        if (TextUtils.isEmpty(episode.getUrl()) && !TextUtils.isEmpty(episode.getBrowserEpId())) {
            showProgress();
            Notify.show(getString(R.string.browser_resolving_episode, episode.getName()));
            WebPlayActivity.BrowserResolveCallback cb = new WebPlayActivity.BrowserResolveCallback() {
                @Override
                public void onStream(String url, Map<String, String> headers) {
                    mBrowserResolveCallback = null;
                    hideProgress();
                    // push v6 Layer 1：解析中用户已切走别的集时不回切；已选中的集只重跑 playEpisode，不重进 selectEpisode。
                    if (isHostFinishing() || !episode.isSelected()) return;
                    mWebEpHeaders = headers == null ? Collections.emptyMap() : headers;
                    episode.setUrl(url);
                    mVod.playResolvedEpisode(episode);
                }

                @Override
                public void onFailed() {
                    mBrowserResolveCallback = null;
                    hideProgress();
                    if (isHostFinishing()) return;
                    Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
                }
            };
            mBrowserResolveCallback = cb;
            if (WebPlayActivity.requestBrowserResolve(episode.getBrowserEpId(), cb)) return true;
            // 浏览器已关（后台栈里无 WebPlayActivity 实例）：直接回落现有失败提示，不走 HTTP 解析。
            mBrowserResolveCallback = null;
            hideProgress();
            Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
            return true;
        }
        showProgress();
        Notify.show(getString(R.string.browser_resolving_episode, episode.getName()));
        HttpEpisodeResolver.resolveFresh(this, episode.getPageUrl(), getIntent().getStringExtra("browserOrigin"), getIntent().getStringExtra("browserVid"), new HttpEpisodeResolver.Callback() {
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
                // push v19 (B)：HTTP 解析失败兜底走后台浏览器——浏览器跑站方 JS 能解出
                // 解析器解不出的流（枫叶 parse 回 HTML 播放器页、纯 HTTP 解不出，report18 §9）。
                // 注意：探针表里的非当前集 url 为空、播放页地址在 pageUrl（applyBrowserLines/
                // applyBrowserEpisodes：Episode.create(name, "") + setPageUrl(url)），所以
                // 兜底导航必须用 pageUrl——用 getUrl() 会永远走不到兜底。pageUrl 在入口已判非空。
                // 浏览器已关（request 返回 false）立即回落原 toast，不卡住；兜底进行中进度条
                // 保持可见、不重复弹失败 toast，等兜底回调再决定。
                if (!TextUtils.isEmpty(episode.getPageUrl())) {
                    WebPlayActivity.BrowserResolveCallback pageCb = new WebPlayActivity.BrowserResolveCallback() {
                        @Override
                        public void onStream(String url, Map<String, String> headers) {
                            mBrowserResolveCallback = null;
                            hideProgress();
                            // push v6 Layer 1：解析中用户已切走别的集时不回切；已选中的集只重跑 playEpisode，不重进 selectEpisode。
                            if (isHostFinishing() || !episode.isSelected()) return;
                            mWebEpHeaders = headers == null ? Collections.emptyMap() : headers;
                            episode.setUrl(url);
                            mVod.playResolvedEpisode(episode);
                        }

                        @Override
                        public void onFailed() {
                            mBrowserResolveCallback = null;
                            hideProgress();
                            if (isHostFinishing()) return;
                            Notify.show(getString(R.string.browser_resolve_failed, episode.getName()));
                        }
                    };
                    mBrowserResolveCallback = pageCb;
                    if (WebPlayActivity.requestBrowserPageResolve(episode.getPageUrl(), pageCb)) return;
                    mBrowserResolveCallback = null;
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
        showProgress();
        Notify.show(getString(R.string.browser_resolving_episode, episode.getName()));
        HttpEpisodeResolver.resolveFresh(this, episode.getPageUrl(), getIntent().getStringExtra("browserOrigin"), getIntent().getStringExtra("browserVid"), new HttpEpisodeResolver.Callback() {
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
        // push v17 F4(a)：表已摆上但认不出当前集时不走单线回落——回落会把每条线都改名成单 flag，
        // 毁掉刚摆好的全集表；直接返回 -1（不预选），播放器播首集上挂的嗅流。
        if (mBrowserLinesApplied) return -1;
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

    /** push v15：单片推送的当前集记 pageUrl=浏览器页地址（v4 探活与 v5 报错恢复的入口条件）。 */
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

    /** push v2：全部线路落地——每条线一面旗，当前线路置顶；当前集（探针 cur 标优先、无标时按集号对位兜底）挂已嗅到的直达流，其余集留空 url、记 pageUrl，未解析，点开时走 HTTP 解析。
     * push v17 F4(a)：认不出当前集时也不丢整表——把已嗅到的直达流挂到首个有集的线的首集上（播放器播嗅到的流），
     * 表全量摆上、当前集序号返回 -1（不预选）。 */
    private int applyBrowserLines(Vod vod, String episodeName) {
        mBrowserLinesApplied = false;
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
                    if (name.isEmpty() || url.isEmpty()) continue;
                    boolean isCurrent = oi == 0 && !streamPlaced && (ep.optInt("cur", 0) == 1
                            || (episodeName != null && !episodeName.isEmpty() && episodeNumber(episodeName) >= 0 && episodeNumber(episodeName) == episodeNumber(name)));
                    if (isCurrent) {
                        lineCurrentIndex = list.size();
                        streamPlaced = true;
                        Episode created = Episode.create(name, stream);
                        created.setPageUrl(url);
                        created.setBrowserEpId(ep.optString("id"));
                        created.setBrowserEpPath(ep.optString("path"));
                        list.add(created);
                    } else {
                        Episode created = Episode.create(name, "");
                        created.setPageUrl(url);
                        created.setBrowserEpId(ep.optString("id"));
                        created.setBrowserEpPath(ep.optString("path"));
                        list.add(created);
                    }
                }
                if (list.isEmpty()) continue;
                // push v17 F4(a)：当前线认不出当前集时也不丢整表——把已嗅到的直达流挂到
                // 首个有集的线（正常就是当前线 oi==0）的首集上，播放器播嗅到的流；只不预选
                // 当前集（currentIndex 保持 -1，lineCurrentIndex<0 时不 setPosition）。
                // 注：isCurrent 只在 oi==0 置位，lineCurrentIndex<0 且 streamPlaced 为假时，
                // 此处恰好只触发一次（oi==0 无当前集时落在当前线首集；oi==0 的表为空时落在下一条有集的线首集）。
                if (lineCurrentIndex < 0 && !streamPlaced) {
                    list.get(0).setUrl(stream);
                    streamPlaced = true;
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
            mBrowserLinesApplied = true;
            StringBuilder from = new StringBuilder();
            for (Flag f : built) {
                if (from.length() > 0) from.append(",");
                from.append(f.getFlag());
            }
            vod.setPlayFrom(from.toString());
            SpiderDebug.log("video-flow", "browser lines applied lines=%d current=%s", built.size(), built.get(0).getFlag());
            return currentIndex;
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
                    current.setBrowserEpId(obj.optString("id"));
                    current.setBrowserEpPath(obj.optString("path"));
                    list.add(current);
                } else {
                    Episode ep = Episode.create(name, "");
                    ep.setPageUrl(url);
                    ep.setBrowserEpId(obj.optString("id"));
                    ep.setBrowserEpPath(obj.optString("path"));
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

    @Override
    public void renderDetail(Vod item, History history) {
        mPlayRequested = false;
        mWebUrl = null;
        mWebHeader = null;
        mBinding.control.action.web.setVisibility(View.GONE);
        mHistory = history;
        mBinding.swipeLayout.setRefreshing(false);
        mBinding.progressLayout.showContent();
        mBinding.name.setText(item.getName());
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
        mFlags = items == null ? new ArrayList<>() : new ArrayList<>(items);
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
        notifyItemChanged(mBinding.flag, mFlagAdapter);
        scrollToPosition(mBinding.flag, mFlagAdapter.getPosition());
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
        if (items == null || items.isEmpty()) refreshWebButton();
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
        mBinding.swipeLayout.setRefreshing(false);
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
        setText(mBinding.content, 0, desc);
    }

    @Override
    public void renderPlaybackMetadata(MediaMetadata metadata) {
        if (service() != null && isOwner()) player().setMetadata(metadata);
        mBinding.control.title.setText(metadata.displayTitle);
        mBinding.control.title.setSelected(true);
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
        mQuickAdapter.clear();
    }

    @Override
    public void onSearchResult() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
        // #104：详情已落定但没有发起播放（自动播放关闭待选 / 延时读秒待播）时收起播放窗
        // 加载圈——没请求播放就不该一直转圈；真正发起请求时 onPlaybackRequested 会重新
        // 显示。post 到本轮详情处理（含 seamless 的决定）全部结束后再判定，避免误收在途请求。
        mBinding.progress.getRoot().post(() -> {
            if (!mPlayRequested && player().isEmpty()) {
                hideProgress();
                // #105：待播时把控制层默认亮到前端，中央播放键直接可见，不用先点一下
                // 屏幕才出现，避免黑屏被误会成加载失败；待播期间不自动隐藏（见
                // setR1Callback 的守卫），起播后恢复正常自动隐藏。
                showControl();
            }
        });
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

    private void checkId() {
        mVod.checkId();
    }

    private void checkLand() {
        if (isPort() && ResUtil.isLand(this)) enterFullscreen();
    }

    private void showEmpty() {
        showError(getString(R.string.error_detail));
        mBinding.swipeLayout.setEnabled(true);
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.content, 0, item.getContent());
        setText(mBinding.remark, 0, item.getRemarks());
        setOther(mBinding.other, item);
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(view.getText())) return;
        view.setText(Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (view == mBinding.content) setContentVisible();
        view.setLinkTextColor(Color.WHITE);
        CustomMovement.bind(view);
    }

    private void setContentVisible() {
        mBinding.contentLayout.setVisibility(mBinding.content.getVisibility());
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                FolderActivity.start(getActivity(), getKey(), result);
                ((TextView) view).setMaxLines(Integer.MAX_VALUE);
                setRedirect(true);
            }
        };
    }

    private void setOther(TextView view, Vod item) {
        StringBuilder sb = new StringBuilder();
        if (!item.getYear().isEmpty()) sb.append(getString(R.string.detail_year, item.getYear())).append("  ");
        if (!item.getArea().isEmpty()) sb.append(getString(R.string.detail_area, item.getArea())).append("  ");
        if (!item.getTypeName().isEmpty()) sb.append(getString(R.string.detail_type, item.getTypeName())).append("  ");
        view.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        view.setText(Util.substring(sb.toString(), 2));
    }

    @Override
    public void onItemClick(Flag item) {
        mVod.selectFlag(item);
    }

    @Override
    public void onItemClick(Episode item) {
        if (shouldEnterFullscreen(item)) return;
        mVod.selectEpisode(item);
    }

    @Override
    public void onItemClick(Result result) {
        mVod.selectQuality(result);
    }

    @Override
    public void onItemClick(Vod item) {
        mVod.selectSource(item);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        mBinding.control.action.episodes.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.action.next.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.action.prev.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.next.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.prev.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.reverse.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.more.setVisibility(items.size() < 10 ? View.GONE : View.VISIBLE);
        mEpisodeAdapter.addAll(items);
    }

    private void setQualityVisible(boolean visible) {
        mBinding.qualityText.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void onName() {
        String name = mBinding.name.getText().toString();
        Notify.show(getString(R.string.detail_search, name));
        mVod.search(name);
    }

    /**
     * 详情没出内容（如源没写播放数组）时 mHistory 为空，但选集/排序入口仍可点；
     * 直接调 mHistory.isRevSort() 会空指针崩溃，统一走这里判空。
     */
    private boolean isRevSort() {
        return mHistory != null && mHistory.isRevSort();
    }

    private void onMore() {
        EpisodeGridDialog.create().reverse(isRevSort()).episodes(mEpisodeAdapter.getItems()).show(this);
    }

    private void onActor() {
        mBinding.actor.setMaxLines(mBinding.actor.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onDirector() {
        mBinding.director.setMaxLines(mBinding.director.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onContent() {
        mBinding.content.setMaxLines(mBinding.content.getMaxLines() == 3 ? Integer.MAX_VALUE : 3);
    }

    private void onReverse() {
        mVod.setRevSort(!isRevSort());
        mVod.reverseEpisode(false);
    }

    private boolean onChange() {
        mVod.manualSwitchSource();
        return true;
    }

    private boolean onCopy() {
        Util.copy(mBinding.content.getText().toString());
        return true;
    }

    private void onBack() {
        if (isFullscreen()) exitFullscreen();
        else finish();
    }

    private void onCast() {
        CastDialog.create(player()).history(mHistory).show(this);
    }

    private void onInfo() {
        InfoDialog.create(player()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
    }

    private void checkPlay() {
        setR1Callback();
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) {
            // #104：待播状态（自动播放关 / 延时读秒中）点中央播放键 = 立即播放当前
            // 选中集，读秒让位于点击；控制器无可播集（详情未到）时回落原有刷新。
            if (!mVod.playSelected()) onRefresh();
        }
        else onPlay();
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        setR1Callback();
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        setR1Callback();
        mVod.prevEpisode(true);
    }

    private void onSetting() {
        ControlDialog.create().parent(mBinding).parse(isUseParse()).player(player()).show(this);
    }

    private void onLock() {
        setLock(!isLock());
        setRequestedOrientation(getLockOrient());
        mKeyDown.setLock(isLock());
        checkLockImg();
        showControl();
    }

    private void onRotate() {
        setR1Callback();
        setRotate(!isRotate());
        setRequestedOrientation(PlaybackOrientation.getRotateOrientation(this));
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

    private void onDanmakuShow() {
        DanmakuSetting.putShow(!DanmakuSetting.isShow());
        checkDanmakuImg();
        syncDanmakuEnabled();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        if (mKeyDown.getScale() != 1.0f) mKeyDown.resetScale();
        else setScale(index == array.length - 1 ? 0 : ++index);
        setR1Callback();
    }

    private void onSpeed() {
        SpeedSettingDialog.create().player(player()).save(true).show(this);
        hideControl();
    }

    private boolean onSpeedLong() {
        SpeedSetting.putPlayback(PlaybackAction.toggleSpeed(player(), mBinding.widget.message));
        setR1Callback();
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

    private void onDecode() {
        mClock.setCallback(null);
        player().toggleDecode();
        setR1Callback();
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
        setR1Callback();
    }

    private boolean onEndingReset() {
        setR1Callback();
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
        setR1Callback();
    }

    private boolean onOpeningReset() {
        setR1Callback();
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
    }

    private void onEpisodes() {
        EpisodeGridDialog.create().reverse(isRevSort()).episodes(mEpisodeAdapter.getItems()).show(this);
    }

    private void onFullscreen() {
        if (isFullscreen()) exitFullscreen();
        else enterFullscreen();
    }

    private void onPlayer() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player());
        hideControl();
    }

    private boolean onActionTouch(View v, MotionEvent e) {
        setR1Callback();
        return false;
    }

    private void onSwipeRefresh() {
        if (mBinding.progressLayout.isEmpty()) mVod.requestDetail();
        else mVod.refresh();
    }

    private boolean shouldEnterFullscreen(Episode item) {
        // push v4：已选集但当前没在播（停播/空播/解析失败后）时不再吞掉这次点击——只进全屏不重播，
        // 点了等于没点；此时返回 false 走正常 selectEpisode（重播/重试）。播着才进全屏。
        boolean enter = !isFullscreen() && item.isSelected() && player().isPlaying();
        if (enter) enterFullscreen();
        return enter;
    }

    private void enterFullscreen() {
        if (isFullscreen()) return;
        setFullscreen(true);
        if (isLand() && !player().isPortrait()) setTransition();
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        setRequestedOrientation(PlaybackOrientation.getEnterFullscreenOrientation(player().isPortrait()));
        mBinding.control.title.setVisibility(View.VISIBLE);
        setRotate(player().isPortrait());
        mKeyDown.resetScale();
        App.post(mR3, 2000);
        hideControl();
    }

    private void exitFullscreen() {
        if (!isFullscreen()) return;
        setFullscreen(false);
        if (isLand() && !player().isPortrait()) setTransition();
        setRequestedOrientation(PlaybackOrientation.getExitFullscreenOrientation(isPort()));
        mBinding.episode.postDelayed(() -> mBinding.episode.scrollToPosition(mEpisodeAdapter.getPosition()), 100);
        mBinding.control.title.setVisibility(View.INVISIBLE);
        mBinding.video.setLayoutParams(mFrameParams);
        mKeyDown.resetScale();
        App.post(mR3, 2000);
        setRotate(false);
        hideControl();
    }

    private void setTransition() {
        ChangeBounds transition = new ChangeBounds();
        transition.setDuration(150);
        ViewGroup parent = (ViewGroup) mBinding.video.getParent();
        TransitionManager.beginDelayedTransition(parent, transition);
    }

    private int getLockOrient() {
        return PlaybackOrientation.getLockOrientation(this, isLock(), isRotate(), isPort() && isAutoRotate());
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR2, 0);
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void showError(String text) {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.error.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.error.setText("");
    }

    private void syncDanmakuEnabled() {
        player().setDanmakuEnabled(DanmakuSetting.isShow());
    }

    private void hideDanmaku() {
        player().setDanmakuEnabled(false);
    }

    private void showControl() {
        if (service() == null || isInPictureInPictureMode()) return;
        mBinding.control.danmaku.setVisibility(isLock() || !player().haveDanmaku() ? View.GONE : View.VISIBLE);
        mBinding.control.setting.setVisibility(mHistory == null || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.right.rotate.setVisibility(isFullscreen() && !isLock() ? View.VISIBLE : View.GONE);
        mBinding.control.keep.setVisibility(mHistory == null || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.action.getRoot().setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.right.lock.setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
        mBinding.control.action.mediaInfoBtn.setVisibility(PlayerSetting.isMediaInfo() ? View.VISIBLE : View.GONE);
        mBinding.control.info.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.cast.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.center.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        mBinding.control.fullscreen.setImageResource(isFullscreen() ? R.drawable.exo_icon_fullscreen_exit : R.drawable.exo_icon_fullscreen_enter);
        showNetInfo();
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
        // netInfo no longer lives inside control: hiding control must not hide it.
        // When the net_info switch is off, the 2s hide tick from showNetInfo still hides it.
    }

    private void showNetInfo() {
        if (isLock() || player().isEmpty()) return;
        // Resolution and speed are two independent features: the settings
        // switch gates the speed line (and its TrafficStats sampling) only,
        // never the resolution line. The +1s refresh tick is always armed
        // so a resolution that only arrives late (right after playback
        // start or an episode switch) still fills in while the block is up.
        if (PlayerSetting.isNetInfo()) {
            long rx = TrafficStats.getUidRxBytes(getApplicationInfo().uid);
            if (rx == TrafficStats.UNSUPPORTED) return;
            mInfoTxBytes = TrafficStats.getUidTxBytes(getApplicationInfo().uid);
            mInfoRxBytes = rx;
            mInfoTime = System.currentTimeMillis();
        }
        refreshNetInfoText(-1, -1);
        App.removeCallbacks(mR5);
        App.removeCallbacks(mR6);
        App.post(mR5, 1000);
        // Persistent mode (net_info switch on): no 2s hide tick; updateNetInfo re-posts itself.
        // Switch off: keep the old 2s hide so the resolution line still behaves as before.
        if (!PlayerSetting.isNetInfo()) App.post(mR6, 2000);
    }

    private void updateNetInfo() {
        if (isLock() || player().isEmpty()) {
            hideNetInfo();
            return;
        }
        long txSpeedKb = -1;
        long rxSpeedKb = -1;
        if (PlayerSetting.isNetInfo()) {
            long now = System.currentTimeMillis();
            long tx = TrafficStats.getUidTxBytes(getApplicationInfo().uid);
            long rx = TrafficStats.getUidRxBytes(getApplicationInfo().uid);
            long elapsed = Math.max(now - mInfoTime, 1);
            txSpeedKb = tx == TrafficStats.UNSUPPORTED || mInfoTxBytes == TrafficStats.UNSUPPORTED ? -1 : (tx - mInfoTxBytes) * 1000 / elapsed / 1024;
            rxSpeedKb = (rx - mInfoRxBytes) * 1000 / elapsed / 1024;
            mInfoTxBytes = tx;
            mInfoRxBytes = rx;
            mInfoTime = now;
        }
        refreshNetInfoText(txSpeedKb, rxSpeedKb);
        // Persistent mode: keep ticking every second while playing, independent of control visibility.
        if (PlayerSetting.isNetInfo() && !player().isEmpty()) App.post(mR5, 1000);
        else if (!PlayerSetting.isNetInfo()) App.post(mR6, 2000);
    }

    private void refreshNetInfoText(long txSpeedKb, long rxSpeedKb) {
        String text = getNetInfoText(txSpeedKb, rxSpeedKb);
        mBinding.netInfo.setText(text);
        mBinding.netInfo.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void hideNetInfo() {
        App.removeCallbacks(mR5);
        App.removeCallbacks(mR6);
        mBinding.netInfo.setVisibility(View.GONE);
    }

    private String getNetInfoText(long txSpeedKb, long rxSpeedKb) {
        String size = player().getSizeText();
        if (!PlayerSetting.isNetInfo()) return size;
        String speed = "[↑" + formatNetSpeed(txSpeedKb) + " ↓" + formatNetSpeed(rxSpeedKb) + "]";
        return size.isEmpty() ? speed : size + "\n" + speed;
    }

    private String formatNetSpeed(long speedKb) {
        return speedKb < 0 ? "-- KB/s" : speedKb < 1000 ? speedKb + " KB/s" : new DecimalFormat("#.0").format(speedKb / 1024f) + " MB/s";
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
        App.removeCallbacks(mR7);
        if (mMediaInfo) {
            mMediaInfoPlayer = player().getPlayer();
            App.post(mR7, 1000);
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
        App.post(mR7, 1000);
    }

    private void resetMediaInfo() {
        mMediaInfo = false;
        mMediaInfoPlayer = null;
        App.removeCallbacks(mR7);
        mBinding.mediaInfoPanel.setVisibility(View.GONE);
        mBinding.control.action.mediaInfoBtn.setSelected(false);
    }

    private void dismissDialogs() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) if (fragment instanceof DialogFragment dialog) dialog.dismiss();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private void setOrient() {
        if (isPort() && isAutoRotate()) setRequestedOrientation(PlaybackOrientation.getPortAutoRotateOrientation());
        if (isLand() && isAutoRotate()) setRequestedOrientation(PlaybackOrientation.getLandAutoRotateOrientation());
    }

    private void setR1Callback() {
        if (isScrubbing()) return;
        // #105：待播（没发起过播放请求且播放器为空）时不挂自动隐藏——控制层常驻前端，
        // 中央播放键一直可见；起播后 mPlayRequested 置位，恢复正常自动隐藏。
        if (!mPlayRequested && player().isEmpty()) return;
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onScrubbingChanged(boolean scrubbing) {
        if (scrubbing) App.removeCallbacks(mR1);
        else if (isVisible(mBinding.control.getRoot())) setR1Callback();
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

    private void saveHistory(boolean exit) {
        PlaybackService service = service();
        boolean owner = service != null && getPlaybackKey().equals(service.player().getKey());
        long position = owner ? service.player().getPosition() : C.TIME_UNSET;
        long duration = owner ? service.player().getDuration() : C.TIME_UNSET;
        if (mVod != null) mVod.saveHistory(exit, System.currentTimeMillis(), position, duration);
    }

    private void checkControl() {
        if (isVisible(mBinding.control.getRoot())) showControl();
    }

    private void checkKeepImg() {
        mBinding.control.keep.setImageResource(Keep.find(getHistoryKey()) == null ? R.drawable.ic_control_keep_off : R.drawable.ic_control_keep_on);
    }

    private void checkLockImg() {
        mBinding.control.right.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }

    private void checkDanmakuImg() {
        mBinding.control.danmaku.setImageResource(DanmakuSetting.isShow() ? R.drawable.ic_control_danmaku_on : R.drawable.ic_control_danmaku_off);
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

        @Override
        public void onAudio() {
            Util.moveToBackground(VideoActivity.this);
            setAudioOnly(true);
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        setPlaybackMode();
        checkControl();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
    }

    @Override
    protected void onTracksChanged() {
        if (mMediaInfo && player().getPlayer() != mMediaInfoPlayer) resetMediaInfo();
        else refreshMediaInfo();
        setTrackVisible();
    }

    @Override
    protected void onMediaOptionsChanged() {
        setMediaOptionVisible();
    }

    @Override
    protected void onError(String msg) {
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
                checkControl();
                player().reset();
                mClock.setCallback(this);
                break;
            case Player.STATE_ENDED:
                hideProgress();
                setR1Callback();
                mVod.playbackEnded();
                updatePlayControl(false);
                mClock.setCallback(null);
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying || isPaused()) updatePlayControl(isPlaying);
    }

    private void updatePlayControl(boolean isPlaying) {
        mBinding.control.play.setImageResource(isPlaying ? R.drawable.exo_icon_pause : R.drawable.exo_icon_play);
        mPiP.update(this, isPlaying);
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        changeHeight();
        checkOrientation();
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
    public void onCastEvent(CastEvent event) {
        if (isRedirect()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) mVod.requestDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) mVod.refresh();
        else if (event.getType() == RefreshEvent.Type.VOD) mVod.updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    private void checkOrientation() {
        if (isFullscreen() && !isRotate() && player().isPortrait()) {
            setRequestedOrientation(PlaybackOrientation.getPortraitVideoSizeOrientation());
            setRotate(true);
        } else if (isFullscreen() && isRotate() && player().isLandscape()) {
            setRequestedOrientation(PlaybackOrientation.getLandscapeVideoSizeOrientation());
            setRotate(false);
        }
    }

    private void changeHeight() {
        if (isLand() || isFullscreen() || isInPictureInPictureMode()) return;
        int videoWidth = player().getVideoWidth();
        int videoHeight = player().getVideoHeight();
        if (videoWidth == 0 || videoHeight == 0) return;
        int viewWidth = ResUtil.getScreenWidth();
        int minHeight = ResUtil.dp2px(150);
        int maxHeight = ResUtil.getScreenHeight() / 2;
        int calculated = (int) (viewWidth * ((float) videoHeight / videoWidth));
        int finalHeight = Math.clamp(calculated, minHeight, Math.max(minHeight, maxHeight));
        if (finalHeight == mBinding.video.getHeight()) return;
        if (mAnimator.isRunning()) mAnimator.cancel();
        mAnimator.setIntValues(mBinding.video.getHeight(), finalHeight);
        mAnimator.setDuration(300);
        mAnimator.start();
    }

    private void setTrackVisible() {
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
    }

    private void setMediaOptionVisible() {
        PlaybackAction.setMediaOptions(player(), mBinding.control.action.edition, mBinding.control.action.chapter);
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        Util.toggleFullscreen(this, this.fullscreen = fullscreen);
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
        if (fullscreen && !rotate) setPadding(mBinding.control.getRoot());
        else noPadding(mBinding.control.getRoot());
    }

    private void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyItemRangeChanged(0, adapter.getItemCount()));
    }

    private void scrollToPosition(RecyclerView view, int position) {
        view.post(() -> view.scrollToPosition(position));
    }

    @Override
    public void onCasted() {
        player().stop();
    }

    @Override
    public void onScale(int tag) {
        mKeyDown.resetScale();
        setScale(tag);
    }

    @Override
    public void onParse(Parse item) {
        mVod.selectParse(item);
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
    public void onBright(int progress) {
        mBinding.widget.bright.setVisibility(View.VISIBLE);
        mBinding.widget.brightProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_low);
        else if (progress < 70) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_medium);
        else mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_high);
    }

    @Override
    public void onVolume(int progress) {
        mBinding.widget.volume.setVisibility(View.VISIBLE);
        mBinding.widget.volumeProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_low);
        else if (progress < 70) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_medium);
        else mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_high);
    }

    @Override
    public void onFlingUp() {
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else mVod.nextEpisode(true);
    }

    @Override
    public void onFlingDown() {
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else mVod.prevEpisode(true);
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        mBinding.widget.time.setText(player().getPositionTime(time));
        mBinding.widget.seek.setVisibility(View.VISIBLE);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        seekTo(time);
    }

    @Override
    public void onSingleTap() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    @Override
    public void onDoubleTap() {
        if (isLock()) return;
        if (!isFullscreen()) {
            enterFullscreen();
        } else if (player().isPlaying()) {
            showControl();
            onPaused();
        } else {
            hideControl();
            onPlay();
        }
    }

    @Override
    public void onTouchEnd() {
        mBinding.widget.seek.setVisibility(View.GONE);
        mBinding.widget.bright.setVisibility(View.GONE);
        mBinding.widget.volume.setVisibility(View.GONE);
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlaybackIntent.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
        if (requestCode == ImageActivity.REQUEST_IMAGE) {
            int position = data == null ? -1 : data.getIntExtra("episode_position", -1);
            if (resultCode == RESULT_OK && position >= 0 && mEpisodeAdapter != null && position < mEpisodeAdapter.getItemCount()) {
                mVod.selectEpisode(mEpisodeAdapter.getItems().get(position));
            } else {
                // 与 VesperTV 源码同构（#63 恢复原设计，REQUEST_IMAGE 即源码的 1001）：
                // 图片页未选话返回即结束本页——详情页对图片集是自动播放的，
                // 保留详情页会让返回落到自动起播状态、退不回详情页浏览。
                finish();
            }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isRedirect()) return;
        if (isLock()) App.post(this::onLock, 500);
        if (service() != null && player().haveTrack(C.TRACK_TYPE_VIDEO)) mPiP.enter(this, player().getVideoWidth(), player().getVideoHeight(), getScale());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        if (!isBindingOwner()) return;
        if (inPictureInPictureMode) onEnterPictureInPicture();
        else onExitPictureInPicture();
    }

    private void onEnterPictureInPicture() {
        if (!isFullscreen()) setVideoView(true);
        dismissDialogs();
        hideControl();
        hideDanmaku();
    }

    private void onExitPictureInPicture() {
        if (!isFullscreen()) setVideoView(false);
        syncDanmakuEnabled();
        if (isStop()) finish();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isAutoRotate() && isPort() && newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && !isRotate() && !isLock()) exitFullscreen();
        if (isAutoRotate() && isPort() && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) enterFullscreen();
        if (isFullscreen()) Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isFullscreen() && hasFocus && !Util.isFullscreen(this)) Util.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
        setAudioOnly(false);
        setStop(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveHistory(false);
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
        if (!isAudioOnly()) setStop(true);
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isFullscreen() && !isLock()) {
            exitFullscreen();
        } else if (!isLock()) {
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        // push v18 (c)：播放器销毁时取消浏览器内点集解流的待解单（静默取消，不回调）。
        if (mBrowserResolveCallback != null) {
            WebPlayActivity.cancelBrowserResolve(mBrowserResolveCallback);
            mBrowserResolveCallback = null;
        }
        mClock.release();
        saveHistory(true);
        Timer.get().reset();
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4, mR5, mR6, mR7);
        super.onDestroy();
    }
}
