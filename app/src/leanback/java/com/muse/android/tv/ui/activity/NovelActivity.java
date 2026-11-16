package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;

import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.SiteApi;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Keep;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.databinding.ActivityNovelBinding;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.dialog.NovelCatalogDialog;
import com.muse.android.tv.ui.novel.LocalBooks;
import com.muse.android.tv.ui.novel.NovelProgress;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;

// Ported from the original MuseTV 5.6.3 TV build (Liu's reference APK, 2026-10-24):
// remote-first novel reader - DPAD scroll/page, OK shows UI, MENU cycles text size,
// long-press OK = night mode, long-press MENU = auto scroll.
// 2026-11-01 (Liu 实测功能区遥控不可操作后下令三线全修)：照漫画版 ImageActivity
// 已验收的两态模型补控件模式——OK 进控件模式聚焦目录钮，方向键在章节按钮间移动、
// OK 点按，BACK 先退控件模式。原单态下根视图独占焦点且方向键被 onKeyDown 全消费，
// 功能区的上一章/目录/下一章按钮永远拿不到焦点。
// 2026-11-03 功能栏加书签/收藏/朗读三件套（Liu 下令，四条电视线）：书签照手机版
// 按章存取，收藏接 Keep 与详情页互通，朗读照手机版 TTS 移植（在线跨章经详情页
// 回传 tts_continue 在新实例续读，本地切章在实例内落定后续读）。
public class NovelActivity extends BaseActivity {

    private static final long LONG_PRESS_TIMEOUT = 3000;
    public static final int REQUEST_NOVEL = 1002;

    private ActivityNovelBinding mBinding;
    private ArrayList<String> mEpisodeNames;
    private ArrayList<String> mEpisodeUrls;
    private String mSiteKey = "";
    private String mVodId = "";
    private String mFlag = "";
    private boolean mResolving = false;
    private int mEpisodePosition;
    private Runnable mMenuLongPressTask;
    private Runnable mOkLongPressTask;
    private String mTitle = "";
    private String mLocalPath = "";
    private String mContent = "";
    private float mTextSize = 20.0f;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mAutoScrolling = false;
    private int mAutoScrollSpeed = 3;
    private boolean mNightMode = false;
    private boolean mMenuLongFired = false;
    private boolean mOkLongFired = false;
    // 遥控器两态：false = 阅读模式（方向键滚动/翻页，OK 进入控件模式）；
    // true = 控件模式（方向键在上一章/目录/下一章间移动、OK 点按）。
    // 不能只看标题栏显隐——标题栏初始就是可见的，那时仍是阅读模式。
    private boolean mControlsMode = false;
    // 功能栏三件套（书签 / 收藏 / 朗读）
    private static final String BOOKMARK_PREFS = "novel_bookmarks";
    private String mKeepKey = "";
    private String mVodName = "";
    private String mVodPic = "";
    private String mSiteName = "";
    private TextToSpeech mTts;
    private boolean mTtsReady = false;
    private boolean mTtsPending = false;
    private boolean mTtsSpeaking = false;
    private final ArrayList<String> mTtsChunks = new ArrayList<>();
    private int mTtsIndex = 0;
    private boolean mTtsAnchorStart = true;
    private int mTtsChars = 0;
    private int mTtsTotal = 1;
    private float mTtsRate = 1f;
    private float mTtsPitch = 1f;
    private final java.util.ArrayList<Integer> mTtsStarts = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> mTtsEnds = new java.util.ArrayList<>();
    private String mTtsBuiltFor;
    private int mTtsHighlight = 1;
    private android.text.style.BackgroundColorSpan mHlSpan;
    private static final String[] TTS_HL_NAMES = {"关闭", "黄色", "浅绿色", "粉色"};

    private final Runnable mAutoScrollTask = new Runnable() {
        @Override
        public void run() {
            if (!mAutoScrolling) return;
            int max = mBinding.contentText.getHeight() - mBinding.scrollView.getHeight();
            if (max > 0 && mBinding.scrollView.getScrollY() >= max - 10) {
                stopAutoScroll();
            } else {
                mBinding.scrollView.smoothScrollBy(0, mAutoScrollSpeed);
                mHandler.postDelayed(this, 50L);
            }
        }
    };

    public static void start(Activity activity, String title, String novelUrl) {
        Intent intent = new Intent(activity, NovelActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("novelUrl", novelUrl);
        activity.startActivity(intent);
    }

    public static void startForResult(Activity activity, String title, String novelUrl, ArrayList<String> episodeNames, ArrayList<String> episodeUrls, int episodePosition) {
        startForResult(activity, title, novelUrl, episodeNames, episodeUrls, episodePosition, "", "", "", "", false);
    }

    // keepKey 等收藏接线由详情页传入（与详情页同一 Keep 记录）；ttsContinue = 上一章
    // 朗读未尽、经详情页回传接力，本实例打开后自动续读。
    public static void startForResult(Activity activity, String title, String novelUrl, ArrayList<String> episodeNames, ArrayList<String> episodeUrls, int episodePosition, String keepKey, String vodName, String vodPic, String siteName, boolean ttsContinue) {
        startForResult(activity, title, novelUrl, episodeNames, episodeUrls, episodePosition, keepKey, vodName, vodPic, siteName, ttsContinue, "", "", "");
    }

    /** 带站点上下文的重载（详情页分发专用）：切到图集/视频话时页内直接分发，不经详情页回传中转。 */
    public static void startForResult(Activity activity, String title, String novelUrl, ArrayList<String> episodeNames, ArrayList<String> episodeUrls, int episodePosition, String keepKey, String vodName, String vodPic, String siteName, boolean ttsContinue, String siteKey, String vodId, String flag) {
        Intent intent = new Intent(activity, NovelActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("novelUrl", novelUrl);
        intent.putExtra("episodeNames", episodeNames);
        intent.putExtra("episodeUrls", episodeUrls);
        intent.putExtra("episodePosition", episodePosition);
        intent.putExtra("keepKey", keepKey);
        intent.putExtra("vodName", vodName);
        intent.putExtra("vodPic", vodPic);
        intent.putExtra("siteName", siteName);
        intent.putExtra("ttsContinue", ttsContinue);
        if (siteKey != null) intent.putExtra("siteKey", siteKey);
        if (vodId != null) intent.putExtra("vodId", vodId);
        if (flag != null) intent.putExtra("flag", flag);
        activity.startActivityForResult(intent, REQUEST_NOVEL);
    }

    /** 本地书启动：items 为 LocalBooks.chapterItems 生成的 章名$local://偏移:长度 列表。 */
    public static void startLocal(Activity activity, String path, String title, ArrayList<String> items, int position) {
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> urls = new ArrayList<>();
        for (String item : items) {
            int split = item.lastIndexOf('$');
            names.add(split > 0 ? item.substring(0, split) : item);
            urls.add(split > 0 ? item.substring(split + 1) : item);
        }
        Intent intent = new Intent(activity, NovelActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("localPath", path);
        intent.putExtra("episodeNames", names);
        intent.putExtra("episodeUrls", urls);
        intent.putExtra("episodePosition", position);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        mBinding = ActivityNovelBinding.inflate(getLayoutInflater());
        return mBinding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mTitle = getIntent().getStringExtra("title");
        String novelUrl = getIntent().getStringExtra("novelUrl");
        mEpisodeNames = getIntent().getStringArrayListExtra("episodeNames");
        mEpisodeUrls = getIntent().getStringArrayListExtra("episodeUrls");
        mSiteKey = getIntent().getStringExtra("siteKey");
        if (mSiteKey == null) mSiteKey = "";
        mVodId = getIntent().getStringExtra("vodId");
        if (mVodId == null) mVodId = "";
        mFlag = getIntent().getStringExtra("flag");
        if (mFlag == null) mFlag = "";
        mEpisodePosition = getIntent().getIntExtra("episodePosition", 0);
        mLocalPath = getIntent().getStringExtra("localPath");
        if (mLocalPath == null) mLocalPath = "";
        mKeepKey = getIntent().getStringExtra("keepKey");
        if (mKeepKey == null) mKeepKey = "";
        mVodName = getIntent().getStringExtra("vodName");
        if (mVodName == null) mVodName = "";
        mVodPic = getIntent().getStringExtra("vodPic");
        if (mVodPic == null) mVodPic = "";
        mSiteName = getIntent().getStringExtra("siteName");
        if (mSiteName == null) mSiteName = "";
        if (mEpisodeNames == null) mEpisodeNames = new ArrayList<>();
        if (mEpisodeUrls == null) mEpisodeUrls = new ArrayList<>();
        if (mTitle == null) mTitle = "";
        if (isLocal()) loadLocalChapter();
        else parseNovel(novelUrl);
        mNightMode = getPrefs().getBoolean("night_mode", false);
        applyNightMode();
        mBinding.titleText.setText(mTitle);
        mBinding.contentText.setText(mContent, android.widget.TextView.BufferType.SPANNABLE);
        mBinding.contentText.setTextSize(mTextSize);
        updateProgress();
        updateChapterButtons();
        restoreProgress();
        mBinding.keepChapter.setVisibility(mKeepKey.isEmpty() ? View.GONE : View.VISIBLE);
        refreshKeepLabel();
        renderTtsLabel();
        if (getIntent().getBooleanExtra("ttsContinue", false)) {
            mTtsAnchorStart = false;
            mHandler.post(this::startTts);
        }
    }

    @Override
    protected void initEvent() {
        mBinding.getRoot().setFocusable(true);
        mBinding.getRoot().setFocusableInTouchMode(true);
        mBinding.getRoot().requestFocus();
        mBinding.scrollView.getViewTreeObserver().addOnScrollChangedListener(this::updateProgress);
        mBinding.prevChapter.setOnClickListener(view -> {
            if (hasEpisodes() && mEpisodePosition > 0) gotoEpisode(mEpisodePosition - 1);
        });
        mBinding.nextChapter.setOnClickListener(view -> {
            if (hasEpisodes() && mEpisodePosition < mEpisodeNames.size() - 1) gotoEpisode(mEpisodePosition + 1);
        });
        mBinding.tocChapter.setOnClickListener(view -> {
            if (hasEpisodes()) showEpisodeDialog();
        });
        mBinding.bookmarkChapter.setOnClickListener(view -> showBookmarkDialog());
        mBinding.keepChapter.setOnClickListener(view -> toggleKeep());
        mBinding.ttsChapter.setOnClickListener(view -> toggleTts());
        mBinding.ttsChapter.setOnLongClickListener(view -> {
            showTtsSettings();
            return true;
        });
    }

    private boolean isLocal() {
        return !mLocalPath.isEmpty();
    }

    private void loadLocalChapter() {
        mContent = "";
        if (mEpisodeUrls == null || mEpisodePosition < 0 || mEpisodePosition >= mEpisodeUrls.size()) return;
        String url = mEpisodeUrls.get(mEpisodePosition);
        if (url == null || !url.startsWith("local://")) return;
        try {
            String[] parts = url.substring("local://".length()).split(":");
            mContent = LocalBooks.readChapter(mLocalPath, Integer.parseInt(parts[0]), Integer.parseInt(parts[1])).replace("\n", "\n\n");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void parseNovel(String url) {
        mContent = "";
        if (url == null || !url.startsWith("novel://")) return;
        try {
            JSONObject object = new JSONObject(url.substring("novel://".length()));
            String title = object.optString("title", "");
            if (!title.isEmpty() && mTitle.isEmpty()) mTitle = title;
            mContent = object.optString("content", "").replace("\n", "\n\n");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("novel_tv", 0);
    }

    private boolean hasEpisodes() {
        return mEpisodeNames != null && mEpisodeNames.size() > 1;
    }

    private void gotoEpisode(int position) {
        if (position < 0 || position >= mEpisodeNames.size() || position == mEpisodePosition) return;
        if (isLocal()) {
            saveProgress();
            mEpisodePosition = position;
            loadLocalChapter();
            mBinding.contentText.setText(mContent, android.widget.TextView.BufferType.SPANNABLE);
            mBinding.scrollView.scrollTo(0, 0);
            updateProgress();
            updateChapterButtons();
            onChapterLoaded();
            return;
        }
        dispatchEpisode(position);
    }

    /**
     * 跨类型切章分发（与手机版阅读器同构）：集标识已是成品串（novel://、pics://、视频直链）
     * 时直接按型分发；是未解析标识（如图文源的 p:/i: 段）时先经 playerContent 回源解析，
     * 再按解析结果的实际类型分发——正文页内渲染、图集页内开图片页、视频按序号强播，
     * 一轮到位不经详情页回传中转；无站点上下文或解析落空才回传详情页分发。
     */
    private void dispatchEpisode(int position) {
        if (position < 0 || position >= mEpisodeNames.size()) return;
        String target = position < mEpisodeUrls.size() ? mEpisodeUrls.get(position) : "";
        if (target == null) target = "";
        if (target.startsWith("novel://")) {
            renderTextChapter(position, target);
            return;
        }
        if (target.startsWith("pics://")) {
            openPicsChapter(position, target, null);
            return;
        }
        if (isDirectMedia(target) && !mSiteKey.isEmpty() && !mVodId.isEmpty()) {
            if (mTtsSpeaking) stopTts();
            VideoActivity.startForceEpisode(this, mSiteKey, mVodId, mVodName, mVodPic, mEpisodeNames.get(position), position);
            finish();
            return;
        }
        if (mSiteKey.isEmpty()) {
            returnPosition(position);
            return;
        }
        resolveEpisode(position, target);
    }

    /** 正文落页内渲染（与本地切章同一落定序列，朗读中经 onChapterLoaded 重切段续读）。 */
    private void renderTextChapter(int position, String novelUrl) {
        saveProgress();
        mEpisodePosition = position;
        parseNovel(novelUrl);
        mBinding.contentText.setText(mContent, android.widget.TextView.BufferType.SPANNABLE);
        mBinding.scrollView.scrollTo(0, 0);
        updateProgress();
        updateChapterButtons();
        onChapterLoaded();
    }

    /** 图集在本页之上开图片页（带全话列表与站点上下文，图片页内可继续跨类型切话）。 */
    private void openPicsChapter(int position, String picsUrl, Map<String, String> header) {
        if (mTtsSpeaking) stopTts();
        mEpisodePosition = position;
        ImageActivity.startForResult(this, mEpisodeNames.get(position), picsUrl, header, new ArrayList<>(mEpisodeNames), new ArrayList<>(mEpisodeUrls), position, mSiteKey, mVodId, mFlag, mVodName, mVodPic);
    }

    /** 回传详情页分发（旧路兜底）。 */
    private void returnPosition(int position) {
        Intent intent = new Intent();
        intent.putExtra("episode_position", position);
        intent.putExtra("tts_continue", mTtsSpeaking);
        setResult(RESULT_OK, intent);
        finish();
    }

    /** 未解析标识回源：playerContent 按线路解析出成品串后，再按实际类型分发。 */
    private void resolveEpisode(int position, String target) {
        if (mResolving) return;
        mResolving = true;
        Task.execute(() -> {
            String raw = "";
            Map<String, String> header = null;
            try {
                Result result = SiteApi.playerContent(mSiteKey, mFlag, target);
                if (result != null) {
                    raw = result.getUrl().v();
                    header = result.getHeader();
                }
            } catch (Throwable ignored) {
            }
            String url = raw == null ? "" : raw;
            Map<String, String> map = header;
            runOnUiThread(() -> {
                mResolving = false;
                if (isFinishing() || position >= mEpisodeNames.size()) return;
                if (url.startsWith("novel://")) {
                    renderTextChapter(position, url);
                } else if (url.startsWith("pics://")) {
                    openPicsChapter(position, url, map);
                } else if (!url.isEmpty() && !mVodId.isEmpty()) {
                    if (mTtsSpeaking) stopTts();
                    VideoActivity.startForceEpisode(this, mSiteKey, mVodId, mVodName, mVodPic, mEpisodeNames.get(position), position);
                    finish();
                } else {
                    returnPosition(position);
                }
            });
        });
    }

    /** 目标标识是一整条无空白的 http(s) 直链即视频话（与手机版阅读器同口径）。 */
    private boolean isDirectMedia(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) return false;
        for (int i = 0; i < v.length(); i++) if (Character.isWhitespace(v.charAt(i))) return false;
        return true;
    }

    // 章节内容落定（本地切章在实例内加载完成）：朗读中则重切段、从新章开头续读。
    private void onChapterLoaded() {
        if (!mTtsSpeaking) return;
        buildChunks();
        speakCurrentChunk();
    }

    private void showEpisodeDialog() {
        // 电视版小说目录：原单列 Material 单选列表改为选集次级页面同构的多列自适应网格。
        NovelCatalogDialog.create()
                .names(mEpisodeNames)
                .position(mEpisodePosition)
                .listener(this::gotoEpisode)
                .show(this);
    }

    private void updateChapterButtons() {
        boolean has = hasEpisodes();
        mBinding.prevChapter.setVisibility(!has || mEpisodePosition <= 0 ? View.GONE : View.VISIBLE);
        mBinding.nextChapter.setVisibility(!has || mEpisodePosition >= mEpisodeNames.size() - 1 ? View.GONE : View.VISIBLE);
        mBinding.tocChapter.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private int getProgress() {
        int range = mBinding.contentText.getHeight() - mBinding.scrollView.getHeight();
        if (range <= 0) return 0;
        return Math.max(0, Math.min(100, (int) (mBinding.scrollView.getScrollY() * 100.0d / range)));
    }

    private void updateProgress() {
        StringBuilder sb = new StringBuilder("阅读进度 ");
        sb.append(getProgress()).append("%");
        if (mAutoScrolling) sb.append(" · 自动滚动(速度").append(mAutoScrollSpeed).append(")");
        if (mNightMode) sb.append(" · 夜间");
        mBinding.progressText.setText(sb.toString());
    }

    private void saveProgress() {
        if (isLocal()) {
            if (!mEpisodeNames.isEmpty() && mEpisodePosition >= 0 && mEpisodePosition < mEpisodeNames.size())
                NovelProgress.save("local:" + mLocalPath, mTitle, "", getString(R.string.shelf_local_tag), mEpisodePosition, mEpisodeNames.get(mEpisodePosition), getProgress());
            return;
        }
        getPrefs().edit().putInt("progress_" + mTitle.hashCode(), getProgress()).apply();
    }

    private void restoreProgress() {
        if (isLocal()) {
            JSONObject record = NovelProgress.find("local:" + mLocalPath);
            if (record == null || record.optInt("chapter", -1) != mEpisodePosition) return;
            final int progress = record.optInt("percent", -1);
            if (progress <= 0 || progress >= 100) return;
            mBinding.scrollView.post(() -> {
                int range = mBinding.contentText.getHeight() - mBinding.scrollView.getHeight();
                if (range > 0) mBinding.scrollView.scrollTo(0, (int) (progress / 100.0d * range));
            });
            return;
        }
        final int progress = getPrefs().getInt("progress_" + mTitle.hashCode(), -1);
        if (progress <= 0 || progress >= 100) return;
        mBinding.scrollView.post(() -> {
            int range = mBinding.contentText.getHeight() - mBinding.scrollView.getHeight();
            if (range > 0) mBinding.scrollView.scrollTo(0, (int) (progress / 100.0d * range));
        });
    }

    private int getPageScroll() {
        int lineHeight = mBinding.contentText.getLineHeight();
        if (lineHeight <= 0) lineHeight = (int) (mTextSize * 1.5f);
        int visible = mBinding.scrollView.getHeight() - mBinding.scrollView.getPaddingTop() - mBinding.scrollView.getPaddingBottom();
        return Math.max(2, visible / lineHeight - 1) * lineHeight;
    }

    private void applyNightMode() {
        if (mNightMode) {
            mBinding.getRoot().setBackgroundColor(-15592942);
            mBinding.contentText.setTextColor(-3618616);
        } else {
            mBinding.getRoot().setBackgroundColor(-659224);
            mBinding.contentText.setTextColor(-13421773);
        }
    }

    private void toggleNightMode() {
        mNightMode = !mNightMode;
        getPrefs().edit().putBoolean("night_mode", mNightMode).apply();
        applyNightMode();
        updateProgress();
    }

    private void cycleTextSize() {
        if (mTextSize == 20.0f) mTextSize = 24.0f;
        else if (mTextSize == 24.0f) mTextSize = 28.0f;
        else if (mTextSize == 28.0f) mTextSize = 16.0f;
        else mTextSize = 20.0f;
        mBinding.contentText.setTextSize(mTextSize);
    }

    // ===== 书签（照手机版阅读器原法：prefs novel_bookmarks 按章存，键为章名 hash） =====

    private String bookmarkKey() {
        String name = mEpisodeNames != null && mEpisodePosition >= 0 && mEpisodePosition < mEpisodeNames.size() ? mEpisodeNames.get(mEpisodePosition) : mTitle;
        return "bm_" + name.hashCode();
    }

    private ArrayList<JSONObject> getBookmarks() {
        ArrayList<JSONObject> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(getSharedPreferences(BOOKMARK_PREFS, 0).getString(bookmarkKey(), "[]"));
            for (int i = 0; i < array.length(); i++) list.add(array.getJSONObject(i));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private void saveBookmarks(ArrayList<JSONObject> list) {
        try {
            JSONArray array = new JSONArray();
            for (JSONObject object : list) array.put(object);
            getSharedPreferences(BOOKMARK_PREFS, 0).edit().putString(bookmarkKey(), array.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addBookmark() {
        try {
            ArrayList<JSONObject> bookmarks = getBookmarks();
            int progress = getProgress();
            JSONObject object = new JSONObject();
            object.put("progress", progress);
            object.put("snippet", getSnippetAtProgress(progress));
            object.put("time", System.currentTimeMillis());
            bookmarks.add(0, object);
            saveBookmarks(bookmarks);
            Notify.show("书签已添加");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getSnippetAtProgress(int progress) {
        try {
            int length = (int) (mContent.length() * progress / 100.0d);
            return mContent.substring(Math.max(0, length - 20), Math.min(mContent.length(), length + 30)).replace("\n", " ").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void scrollToProgress(int progress) {
        mBinding.scrollView.post(() -> {
            int range = mBinding.contentText.getHeight() - mBinding.scrollView.getHeight();
            if (range > 0) mBinding.scrollView.scrollTo(0, (int) (progress / 100.0d * range));
        });
    }

    private void showBookmarkDialog() {
        final ArrayList<JSONObject> bookmarks = getBookmarks();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("书签");
        if (bookmarks.isEmpty()) {
            builder.setMessage("暂无书签，点击\"添加书签\"保存当前进度");
        } else {
            String[] items = new String[bookmarks.size()];
            for (int i = 0; i < bookmarks.size(); i++) {
                try {
                    JSONObject object = bookmarks.get(i);
                    int progress = object.getInt("progress");
                    String snippet = object.optString("snippet", "");
                    if (snippet.length() > 30) snippet = snippet.substring(0, 30) + "...";
                    items[i] = progress + "% - " + snippet;
                } catch (Exception e) {
                    items[i] = "书签 " + (i + 1);
                }
            }
            builder.setItems(items, (dialog, which) -> {
                try {
                    int progress = bookmarks.get(which).getInt("progress");
                    scrollToProgress(progress);
                    Notify.show("已跳转到 " + progress + "%");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        builder.setPositiveButton("添加书签", (dialog, which) -> addBookmark());
        if (!bookmarks.isEmpty()) builder.setNeutralButton("清空", (dialog, which) -> {
            saveBookmarks(new ArrayList<>());
            Notify.show("书签已清空");
        });
        builder.setNegativeButton("关闭", null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        ListView listView = dialog.getListView();
        if (listView != null) {
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                ArrayList<JSONObject> list = getBookmarks();
                if (position < 0 || position >= list.size()) return true;
                list.remove(position);
                saveBookmarks(list);
                Notify.show("书签已删除");
                dialog.dismiss();
                showBookmarkDialog();
                return true;
            });
        }
    }

    // ===== 收藏（接应用 Keep 体系，与详情页同一记录） =====

    private void refreshKeepLabel() {
        boolean kept = !mKeepKey.isEmpty() && Keep.find(mKeepKey) != null;
        mBinding.keepChapter.setText(kept ? "已收藏" : "收藏");
    }

    private void toggleKeep() {
        if (mKeepKey.isEmpty()) return;
        Keep keep = Keep.find(mKeepKey);
        if (keep != null) {
            keep.delete();
            Notify.show(getString(R.string.keep_del));
        } else {
            Keep item = new Keep();
            item.setKey(mKeepKey);
            item.setCid(VodConfig.getCid());
            item.setVodPic(mVodPic);
            item.setVodName(TextUtils.isEmpty(mVodName) ? mTitle : mVodName);
            item.setSiteName(mSiteName);
            item.setCreateTime(System.currentTimeMillis());
            item.save();
            Notify.show(getString(R.string.keep_add));
        }
        refreshKeepLabel();
    }

    // ===== 朗读（TTS，照手机版阅读器整套移植） =====

    private void toggleTts() {
        if (mTtsSpeaking) pauseTts();
        else startTts();
    }

    private void startTts() {
        loadTtsParams();
        if (mTts == null) initTts();
        if (!mTtsReady) {
            mTtsPending = true;
            Notify.show(getString(R.string.novel_tts_loading));
            return;
        }
        if (mTtsChunks.isEmpty() || !java.util.Objects.equals(mTtsBuiltFor, mContent)) buildChunks();
        if (mTtsChunks.isEmpty()) {
            Notify.show(getString(R.string.novel_tts_empty));
            return;
        }
        if (mTtsIndex >= mTtsChunks.size()) mTtsIndex = 0;
        if (mTtsAnchorStart) anchorTtsToView();
        else mTtsAnchorStart = true; // 跨章续读仅起播这一次跳过锚定，之后恢复按视图锚定（停读翻页再开读从新页起）
        mTtsSpeaking = true;
        renderTtsLabel();
        speakCurrentChunk();
    }

    /** 懒初始化系统 TTS：中文优先，失败退化到系统默认语言。 */
    private void initTts() {
        mTts = new TextToSpeech(this, status -> {
            if (mTts == null) return;
            if (status != TextToSpeech.SUCCESS) {
                mTts = null;
                Notify.show(getString(R.string.novel_tts_unsupported));
                return;
            }
            int result = mTts.setLanguage(java.util.Locale.CHINA);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                mTts.setLanguage(java.util.Locale.getDefault());
            }
            applyTtsParams();
            mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    runOnUiThread(NovelActivity.this::onChunkDone);
                }

                @Override
                public void onError(String utteranceId) {
                    runOnUiThread(NovelActivity.this::onChunkDone);
                }
            });
            mTtsReady = true;
            if (mTtsPending) {
                mTtsPending = false;
                startTts();
            }
        });
        // 看门狗：无可用引擎的电视上系统连失败回调都不给，初始化会永远悬着。
        // 5 秒未就绪即判不可用：清掉半成品引擎并复位等待态，免得永远卡在
        // 「正在初始化语音引擎」；引擎正常时在时限内就绪，本检查自动跳过。
        mHandler.postDelayed(() -> {
            if (mTtsReady || mTts == null || isFinishing()) return;
            mTts.shutdown();
            mTts = null;
            if (mTtsPending) {
                mTtsPending = false;
                Notify.show(getString(R.string.novel_tts_unsupported));
            }
        }, 5000);
    }

    private void loadTtsParams() {
        mTtsRate = getPrefs().getFloat("tts_rate", 1f);
        mTtsPitch = getPrefs().getFloat("tts_pitch", 1f);
        mTtsHighlight = getPrefs().getInt("tts_highlight", 1);
    }

    private void applyTtsParams() {
        if (mTts == null) return;
        mTts.setSpeechRate(Math.max(0.1f, Math.min(3f, mTtsRate)));
        mTts.setPitch(Math.max(0.1f, Math.min(3f, mTtsPitch)));
    }

    /** 把本章正文切成适合朗读的小段（分级断句，单段 ≤ 50 字，与手机版定版一致），
     *  同时记下每段在正文中的起止偏移——跟随滚动与段高亮都按偏移定位，不再按字数估算。
     *  断句分级：先在句末标点（换行/。！？；…）处断，段长须 ≥ 20 字；
     *  一句攒到 50 字还没句末标点，就往回找最后一个逗号/顿号/冒号在其后断；
     *  全段无任何标点才在第 50 字硬切。切口永远落在标点上，不落词中间；
     *  句末标点后紧跟的收尾符号（引号、括号、省略号）并入本段，不留到下一段开头。 */
    private void buildChunks() {
        mTtsChunks.clear();
        mTtsStarts.clear();
        mTtsEnds.clear();
        mTtsIndex = 0;
        mTtsChars = 0;
        mHlSpan = null;
        String raw = mContent == null ? "" : mContent;
        mTtsBuiltFor = mContent;
        if (raw.trim().isEmpty()) {
            mTtsTotal = 1;
            return;
        }
        mTtsTotal = Math.max(1, raw.length());
        final String enders = "\n。！？；…";
        final String soft = "，、：";
        final String closers = "」』”’\"）】》…";
        int n = raw.length();
        int segStart = 0;
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            int len = i - segStart + 1;
            if (enders.indexOf(c) >= 0 && len >= 20) {
                int end = i + 1;
                while (end < n && closers.indexOf(raw.charAt(end)) >= 0) end++;
                addChunk(raw, segStart, end);
                segStart = end;
                i = end;
                continue;
            }
            if (len >= 50) {
                int cut = -1;
                for (int j = i + 1; j > segStart + 10; j--) {
                    if (soft.indexOf(raw.charAt(j - 1)) >= 0) {
                        cut = j;
                        break;
                    }
                }
                if (cut < 0) cut = i + 1;
                int end = cut;
                while (end < n && closers.indexOf(raw.charAt(end)) >= 0) end++;
                addChunk(raw, segStart, end);
                segStart = end;
                i = end;
                continue;
            }
            i++;
        }
        addChunk(raw, segStart, n);
    }

    /** 收一段：把 [from, to) 两端空白缩掉后入列（朗读文本与高亮范围都用缩后的范围）。 */
    private void addChunk(String raw, int from, int to) {
        int s = from;
        int e = to;
        while (s < e && Character.isWhitespace(raw.charAt(s))) s++;
        while (e > s && Character.isWhitespace(raw.charAt(e - 1))) e--;
        if (s >= e) return;
        mTtsChunks.add(raw.substring(s, e));
        mTtsStarts.add(s);
        mTtsEnds.add(e);
    }

    private void speakCurrentChunk() {
        if (mTts == null || mTtsIndex >= mTtsChunks.size()) {
            finishTts();
            return;
        }
        String chunk = mTtsChunks.get(mTtsIndex);
        mTts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "novel-" + mTtsIndex);
        followTts();
        applyTtsHighlight();
    }

    /** 一段读完：推进下一段；本章读完自动进入下一章继续（本地版在实例内落定续读，
     *  在线版经详情页回传 tts_continue 在新实例续读）。 */
    private void onChunkDone() {
        if (!mTtsSpeaking) return;
        mTtsIndex++;
        if (mTtsIndex < mTtsChunks.size()) {
            speakCurrentChunk();
            return;
        }
        if (hasEpisodes() && mEpisodePosition < mEpisodeNames.size() - 1) gotoEpisode(mEpisodePosition + 1);
        else {
            finishTts();
            Notify.show(getString(R.string.novel_tts_end));
        }
    }

    /** 视图跟随朗读进度：按当前段的字符偏移精确滚到该段首行（不再按字数占比估算）。 */
    /** 起播锚点：按当前屏幕顶行定位段落——朗读从正在看的位置开始（与阅读位置合一）。 */
    private void anchorTtsToView() {
        if (mTtsStarts.isEmpty()) return;
        android.graphics.Rect rect = new android.graphics.Rect();
        if (!mBinding.contentText.getLocalVisibleRect(rect)) return;
        android.text.Layout layout = mBinding.contentText.getLayout();
        if (layout == null || layout.getText() == null) return;
        int y = Math.max(0, rect.top - mBinding.contentText.getTotalPaddingTop());
        int offset = layout.getLineStart(layout.getLineForVertical(y));
        int idx = 0;
        for (int i = 0; i < mTtsStarts.size(); i++) {
            if (mTtsStarts.get(i) <= offset) idx = i;
            else break;
        }
        mTtsIndex = idx;
    }

    private void followTts() {
        if (mTtsIndex >= mTtsStarts.size()) return;
        final int start = mTtsStarts.get(mTtsIndex);
        mBinding.contentText.post(() -> {
            android.text.Layout layout = mBinding.contentText.getLayout();
            if (layout == null || layout.getText() == null) return;
            int line = layout.getLineForOffset(Math.min(start, Math.max(0, layout.getText().length() - 1)));
            int y = mBinding.contentText.getTotalPaddingTop() + layout.getLineTop(line);
            int max = Math.max(0, mBinding.contentText.getHeight() - mBinding.scrollView.getHeight());
            mBinding.scrollView.scrollTo(0, Math.max(0, Math.min(y, max)));
        });
    }

    /** 朗读高亮颜色（设置项 tts_highlight：0 关 / 1 黄 / 2 浅绿 / 3 粉），0 表示关闭。 */
    private int ttsHighlightColor() {
        switch (mTtsHighlight) {
            case 1: return 0xFFFFF59D;
            case 2: return 0xFFC5E1A5;
            case 3: return 0xFFF8BBD0;
            default: return 0;
        }
    }

    /** 给正在读的这一段套底色高亮；暂停/停止/读完/切章时清除。 */
    private void applyTtsHighlight() {
        CharSequence text = mBinding.contentText.getText();
        if (!(text instanceof android.text.Spannable)) return;
        android.text.Spannable sp = (android.text.Spannable) text;
        if (mHlSpan != null) {
            sp.removeSpan(mHlSpan);
            mHlSpan = null;
        }
        int color = ttsHighlightColor();
        if (color == 0 || mTtsIndex >= mTtsStarts.size()) return;
        int s = mTtsStarts.get(mTtsIndex);
        int e = Math.min(mTtsEnds.get(mTtsIndex), sp.length());
        if (s < 0 || s >= e) return;
        mHlSpan = new android.text.style.BackgroundColorSpan(color);
        sp.setSpan(mHlSpan, s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void clearTtsHighlight() {
        CharSequence text = mBinding.contentText.getText();
        if (mHlSpan != null && text instanceof android.text.Spannable) {
            ((android.text.Spannable) text).removeSpan(mHlSpan);
        }
        mHlSpan = null;
    }

    private void pauseTts() {
        mTtsSpeaking = false;
        if (mTts != null) mTts.stop();
        clearTtsHighlight();
        renderTtsLabel();
    }

    private void finishTts() {
        mTtsSpeaking = false;
        mTtsPending = false;
        clearTtsHighlight();
        renderTtsLabel();
    }

    /** 停止朗读：停声并清除高亮；再点朗读按当前屏幕位置起播。 */
    private void stopTts() {
        mTtsSpeaking = false;
        mTtsPending = false;
        if (mTts != null) mTts.stop();
        clearTtsHighlight();
        renderTtsLabel();
    }

    private void renderTtsLabel() {
        mBinding.ttsChapter.setText(mTtsSpeaking ? R.string.novel_tts_pause : R.string.novel_tts);
    }

    /** 朗读设置：语速 / 音调 / 停止朗读（长按「朗读」打开，照手机版）。 */
    private void showTtsSettings() {
        final String[] rates = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
        final float[] rateValues = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        final String[] pitches = {"低", "正常", "高"};
        final float[] pitchValues = {0.8f, 1f, 1.2f};
        new AlertDialog.Builder(this)
            .setTitle(R.string.novel_tts_settings)
            .setItems(new String[]{
                getString(R.string.novel_tts_rate) + "（" + String.format(java.util.Locale.US, "%.2f", mTtsRate) + "x）",
                getString(R.string.novel_tts_pitch),
                "朗读高亮（" + TTS_HL_NAMES[mTtsHighlight] + "）",
                getString(R.string.novel_tts_stop)}, (dialog, which) -> {
                if (which == 0) {
                    int checked = 2;
                    for (int i = 0; i < rateValues.length; i++) if (Math.abs(rateValues[i] - mTtsRate) < 0.01f) checked = i;
                    new AlertDialog.Builder(this).setTitle(R.string.novel_tts_rate)
                        .setSingleChoiceItems(rates, checked, (d, w) -> {
                            mTtsRate = rateValues[w];
                            getPrefs().edit().putFloat("tts_rate", mTtsRate).apply();
                            applyTtsParams();
                            d.dismiss();
                            if (mTtsSpeaking && mTtsIndex < mTtsChunks.size()) speakCurrentChunk();
                        }).show();
                } else if (which == 1) {
                    int checked = 1;
                    for (int i = 0; i < pitchValues.length; i++) if (Math.abs(pitchValues[i] - mTtsPitch) < 0.01f) checked = i;
                    new AlertDialog.Builder(this).setTitle(R.string.novel_tts_pitch)
                        .setSingleChoiceItems(pitches, checked, (d, w) -> {
                            mTtsPitch = pitchValues[w];
                            getPrefs().edit().putFloat("tts_pitch", mTtsPitch).apply();
                            applyTtsParams();
                            d.dismiss();
                            if (mTtsSpeaking && mTtsIndex < mTtsChunks.size()) speakCurrentChunk();
                        }).show();
                } else if (which == 2) {
                    new AlertDialog.Builder(this).setTitle("朗读高亮")
                        .setSingleChoiceItems(TTS_HL_NAMES, mTtsHighlight, (d, w) -> {
                            mTtsHighlight = w;
                            getPrefs().edit().putInt("tts_highlight", w).apply();
                            d.dismiss();
                            applyTtsHighlight();
                        }).show();
                } else {
                    stopTts();
                    Notify.show(getString(R.string.novel_tts_stopped));
                }
            })
            .setNegativeButton(R.string.dialog_negative, null)
            .show();
    }

    // 照漫画版 ImageActivity 的 showControls：显示功能区并进入控件模式，焦点落到
    // 第一个可用的功能键（目录优先，其次下一章/上一章/书签/收藏/朗读）。功能栏有
    // 恒在的书签/朗读键，故无章节列表时也进控件模式（旧的「只显隐」随之取消）。
    private void showControls() {
        mBinding.titleBar.setVisibility(View.VISIBLE);
        mBinding.hintText.setVisibility(View.VISIBLE);
        mControlsMode = true;
        if (mBinding.tocChapter.getVisibility() == View.VISIBLE) mBinding.tocChapter.requestFocus();
        else if (mBinding.nextChapter.getVisibility() == View.VISIBLE) mBinding.nextChapter.requestFocus();
        else if (mBinding.prevChapter.getVisibility() == View.VISIBLE) mBinding.prevChapter.requestFocus();
        else if (mBinding.bookmarkChapter.getVisibility() == View.VISIBLE) mBinding.bookmarkChapter.requestFocus();
        else if (mBinding.keepChapter.getVisibility() == View.VISIBLE) mBinding.keepChapter.requestFocus();
        else mBinding.ttsChapter.requestFocus();
    }

    // 退出控件模式（共用规则，独立方法）：功能区全藏、回阅读模式、根视图收回焦点。
    private void hideControls() {
        mControlsMode = false;
        mBinding.titleBar.setVisibility(View.GONE);
        mBinding.hintText.setVisibility(View.GONE);
        mBinding.getRoot().requestFocus();
    }

    private void onOkShortPress() {
        if (mAutoScrolling) {
            stopAutoScroll();
            return;
        }
        // 控件模式下 OK 应点按聚焦的按钮（按键由按钮自身消费，不会走到这里）；
        // 走到这里说明焦点不在按钮上，按漫画版语义退出控件模式。
        if (mControlsMode) {
            hideControls();
            return;
        }
        showControls();
    }

    private void startAutoScroll() {
        mAutoScrolling = true;
        mHandler.post(mAutoScrollTask);
        updateProgress();
    }

    private void stopAutoScroll() {
        if (!mAutoScrolling) return;
        mAutoScrolling = false;
        mHandler.removeCallbacks(mAutoScrollTask);
        updateProgress();
    }

    private void toggleAutoScroll() {
        if (mAutoScrolling) stopAutoScroll();
        else startAutoScroll();
    }

    private void adjustSpeed(int delta) {
        mAutoScrollSpeed = Math.max(1, Math.min(10, mAutoScrollSpeed + delta));
        updateProgress();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 控件模式（照漫画版）：方向键放行给默认焦点导航（在上一章/目录/下一章间
        // 移动）；OK 由聚焦的按钮自身消费点按、不会走到这里，走到这里也同样放行，
        // 不再被下面的滚动/翻页与长按调度截走。
        if (mControlsMode) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return super.onKeyDown(keyCode, event);
            }
        }
        if (event.getRepeatCount() > 0) return true;
        // 阅读模式按方向键滚动前，工具栏显着先全藏（照漫画版状态机；藏栏走共用 hideControls）。
        if ((keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                && mBinding.titleBar.getVisibility() == View.VISIBLE) hideControls();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mAutoScrolling) adjustSpeed(1);
                else mBinding.scrollView.smoothScrollBy(0, -300);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mAutoScrolling) adjustSpeed(-1);
                else mBinding.scrollView.smoothScrollBy(0, 300);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mAutoScrolling) stopAutoScroll();
                mBinding.scrollView.smoothScrollBy(0, -getPageScroll());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mAutoScrolling) stopAutoScroll();
                mBinding.scrollView.smoothScrollBy(0, getPageScroll());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                mOkLongFired = false;
                mOkLongPressTask = () -> {
                    mOkLongFired = true;
                    toggleNightMode();
                };
                mHandler.postDelayed(mOkLongPressTask, LONG_PRESS_TIMEOUT);
                return true;
            case KeyEvent.KEYCODE_MENU:
                mMenuLongFired = false;
                mMenuLongPressTask = () -> {
                    mMenuLongFired = true;
                    toggleAutoScroll();
                };
                mHandler.postDelayed(mMenuLongPressTask, LONG_PRESS_TIMEOUT);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            mHandler.removeCallbacks(mOkLongPressTask);
            if (!mOkLongFired) {
                onOkShortPress();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            mHandler.removeCallbacks(mMenuLongPressTask);
            if (!mMenuLongFired) cycleTextSize();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onBackInvoked() {
        // 控件模式、或工具栏还显着（进页初始状态）时，先按返回只藏工具栏、回阅读模式，
        // 不直接退出阅读页（照漫画版）；工具栏已藏才退出。
        if (mControlsMode || mBinding.titleBar.getVisibility() == View.VISIBLE) hideControls();
        else super.onBackInvoked();
    }

    // 图片页跨类型回传：目标话仍是图集→在本页内换图集内容续开图片页；视频→强播；正文→回传详情页分发
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ImageActivity.REQUEST_IMAGE) return;
        int position = data == null ? -1 : data.getIntExtra("episode_position", -1);
        if (resultCode != RESULT_OK || position < 0 || position >= mEpisodeNames.size()) {
            finish();
            return;
        }
        dispatchEpisode(position);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        stopAutoScroll();
        mHandler.removeCallbacks(mMenuLongPressTask);
        mHandler.removeCallbacks(mOkLongPressTask);
    }

    @Override
    protected void onDestroy() {
        stopTts();
        if (mTts != null) {
            mTts.shutdown();
            mTts = null;
        }
        mTtsReady = false;
        super.onDestroy();
    }
}
