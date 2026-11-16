package com.muse.android.tv.ui.novel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AlignmentSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.SiteApi;
import com.muse.android.tv.api.config.VodConfig;
import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.bean.Keep;
import com.muse.android.tv.bean.Result;
import com.muse.android.tv.databinding.ActivityNovelReaderBinding;
import com.muse.android.tv.ui.activity.ImageActivity;
import com.muse.android.tv.ui.activity.VideoActivity;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 小说阅读器（#58 自昏黄 r60 整搬 + 按 Liu 图 1 改版）。
 *
 * 章节项格式为 章节名$标识；标识可能是已内嵌的 novel://{JSON}，
 * 也可能是需要回源请求的普通 id，此时按需调用 playerContent 取回正文。
 *
 * 与原版的差异（图 1 口径）：
 * <ul>
 *     <li>顶栏 = 返回 | 章名（居中）| 书签 | 收藏☆（整本，接 Keep）| 音量（点开系统媒体音量滑块）；
 *         原顶栏目录键去掉，目录改由底栏章节指示点选打开；原顶栏朗读喇叭改作音量键，朗读只留底栏。
 *         精修：顶栏吃状态栏 inset 作顶部 padding 再叠加约 5dp 余量，整行下移避开状态栏。</li>
 *     <li>底栏 = 细滑杆 / 上一章·章节指示（当前章/总章数，可点开目录）·下一章 /
 *         上一页·翻页 chips（上下翻页/左右翻页）·下一页 合并行 / 自动阅读·朗读·主题·更多设置。</li>
 *     <li>翻页三模式（#87）：「上下」= 原 ScrollView 滚动模型（上一页/下一页按屏步进）；
 *         「平移」= {@link PageSlideView} 整页水平平移；「仿真」= {@link PageCurlView} 真卷页。
 *         两分页模式同排版分页、手势规矩一致（方向按滑动定、任意位置起滑、边缘排除）。</li>
 *     <li>正文标题独立居中加粗成行；每段段首补两全角空格缩进（stripHtml 不再清全角空格）。</li>
 *     <li>#65 全屏阅读（Liu 定案）：屏幕单击不翻页（点按分区全删，翻页只在功能键上）、点按
 *         呼出功能框（原双击独占；2026-11-11 改单击切换：单击呼出/藏起、双击只认第一下）；开自动阅读即隐藏功能框、自然结束恢复，上下模式手指滚动 8dp
 *         即隐藏；触碰正文不停自动阅读，停自动只靠「自动阅读」按钮。#63 的点按呼出 +
 *         40dp 累计隐藏 + 卷页整段吞手势整套已推翻。见 {@link #setControlsVisible(boolean)}。</li>
 * </ul>
 */
public class NovelReaderActivity extends BaseActivity implements NovelSettingsDialog.OnSettingsChangedListener {

    // ===== 朗读（TTS）=====
    /** 系统语音引擎；懒初始化（首次点「朗读」才创建）。 */
    private TextToSpeech tts;
    private boolean ttsReady;
    /** 初始化还没完成就被点了朗读 —— 就绪后自动开始。 */
    private boolean ttsPending;
    private boolean ttsSpeaking;
    /** 朗读进行中：期间锁定平移/仿真翻页胶囊，只许上下滚动跟随（暂停即解锁，续读时重新锁定）。 */
    private boolean ttsSession;
    private final java.util.List<String> ttsChunks = new java.util.ArrayList<>();
    private int ttsIndex;
    /** 已朗读字符数 / 本章总字符数：驱动视图跟随滚动。 */
    private int ttsChars;
    private int ttsTotal = 1;
    private float ttsRate = 1f;
    private float ttsPitch = 1f;
    private final java.util.ArrayList<Integer> ttsStarts = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> ttsEnds = new java.util.ArrayList<>();
    private String ttsBuiltFor;
    private int ttsHighlight = 1;
    private android.text.style.BackgroundColorSpan hlSpan;
    private static final String[] TTS_HL_NAMES = {"关闭", "黄色", "浅绿色", "粉色"};

    private static final String EXTRA_ITEMS = "novel_items";
    private static final String EXTRA_INDEX = "novel_index";
    private static final String EXTRA_TITLE = "novel_title";
    private static final String EXTRA_KEY = "novel_key";
    private static final String EXTRA_FLAG = "novel_flag";
    /** 收藏（Keep）接线：详情页传入的 historyKey 与展示信息，阅读器内 ☆ 据此收藏整本。 */
    private static final String EXTRA_KEEP_KEY = "novel_keep_key";
    private static final String EXTRA_VOD_NAME = "novel_vod_name";
    private static final String EXTRA_VOD_PIC = "novel_vod_pic";
    private static final String EXTRA_SITE_NAME = "novel_site_name";
    private static final String NOVEL = "novel://";
    private static final String BOOKMARK_PREFS = "novel_bookmarks";
    private static final String EXTRA_LOCAL_PATH = "novel_local_path";
    private static final String LOCAL = "local://";
    /** 目录网格最小列数（列数按面板可用宽度自适应后 clamp 的下限；窄面板只剩 1 列时整列居中）。 */
    private static final int CATALOG_MIN_COLUMNS = 1;
    /** 目录网格最大列数（clamp 的上限，避免宽屏上窄条目挤成一排）。 */
    private static final int CATALOG_MAX_COLUMNS = 4;
    /** 单个目录条目的最小可读宽度（dp，含「序号. 名称」文案），用面板可用宽度反推列数。 */
    private static final int CATALOG_MIN_ITEM_WIDTH_DP = 120;
    /** 目录条目字号（sp）：列数估算与条目渲染共用，避免二者不一致。 */
    private static final float CATALOG_ITEM_TEXT_SP = 14.0f;
    /** 目录条目外间距基准（dp，行列两侧合计 2 倍）。 */
    private static final int CATALOG_ITEM_MARGIN_DP = 4;
    /** 目录胶囊条目内部横向 padding（dp）。 */
    private static final int CATALOG_ITEM_PAD_H_DP = 12;
    /** 目录胶囊条目内部纵向 padding（dp）。 */
    private static final int CATALOG_ITEM_PAD_V_DP = 8;

    private ActivityNovelReaderBinding mBinding;
    private final List<String> names = new ArrayList<>();
    private final List<String> targets = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int index;
    private String bookTitle = "";
    private String siteKey = "";
    private String flag = "";
    private String keepKey = "";
    private String vodName = "";
    private String vodPic = "";
    private String siteName = "";
    private String localPath = "";
    private boolean openedOnce;
    private int pendingPercent = -1;
    private boolean loading;
    private boolean seeking;
    /** 当前章已排版的正文（滚动模式 content 的文本同源；卷页分页与 TTS/书签共用）。 */
    private String lastBody = "";
    private static final int FLIP_SCROLL = 0;
    private static final int FLIP_CURL = 1;
    private static final int FLIP_SLIDE = 2;
    /** 翻页模式，存 prefs flip_mode：0=上下（滚动），1=仿真（卷页），2=平移。旧版 1 原就是卷页，沿用不迁移。 */
    private int flipMode = FLIP_SCROLL;
    private int themeBg = 0xFFF5F0E1;
    private int themeText = 0xFF333333;

    private int catalogPage;
    private int catalogColumns = CATALOG_MIN_COLUMNS;
    private int catalogPageSize = 0;
    private AlertDialog catalogDialog;
    private GridLayout catalogGrid;
    private ScrollView catalogScroll;
    private TextView catalogPageInfo;
    private TextView catalogPrev;
    private TextView catalogNext;
    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;
    /** 功能框（顶栏 + 底栏四行）当前是否显示（#65 全屏阅读），默认显示。 */
    private boolean controlsVisible = true;
    private int sysStatusTop;         // 状态栏高度（WindowInsets 下发；全屏时正文区顶部避让用）
    private int sysNavBottom;         // 导航/手势条高度（同上，底部避让用）
    private int basePadH, basePadTop, basePadBottom; // 设置换算出的基础正文边距
    private boolean insetsReady;      // 基础边距已算出后才允许下发有效边距
    /** 上下模式：手指是否正触摸正文（滚动自动隐藏只认手指滚动，程序滚动永不触发）。 */
    private boolean textTouching;
    /** 上下模式：本次触摸手势内累计的滚动距离（px），超过 8dp 即隐藏功能框（灵敏触发）。 */
    private int touchScrollAccum;
    private int lastTouchScrollY;
    /** 点按判定（三模式共用）：上一次点按的抬手时刻与位置——单击切换功能框显隐，300ms 内同点第二下同属一次双击、只认第一下。 */
    private long lastTapUpTime;
    private float lastTapX;
    private float lastTapY;

    private final Handler autoScrollHandler = new Handler(Looper.getMainLooper());
    private boolean autoScrolling;
    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoScrolling) return;
            if (isPaged()) {
                // 分页模式（平移/仿真）的「自动阅读」= 定时翻页；翻到本章末页即停（与滚动模式停在章末一致）
                if (!turnPagedNext()) {
                    setAutoScroll(false);
                    // #65：自动阅读自然结束 → 功能框恢复显示
                    setControlsVisible(true);
                    return;
                }
                autoScrollHandler.postDelayed(this, autoPageInterval());
                return;
            }
            mBinding.scrollView.smoothScrollBy(0, Math.max(1, getPrefs().getInt("auto_scroll", 50) / 10));
            View child = mBinding.scrollView.getChildAt(0);
            if (child != null && mBinding.scrollView.getScrollY() >= (child.getHeight() - mBinding.scrollView.getHeight()) - 5) {
                setAutoScroll(false);
                // #65：自动阅读自然结束 → 功能框恢复显示
                setControlsVisible(true);
                return;
            }
            autoScrollHandler.postDelayed(this, 50L);
        }
    };

    /** 分页模式自动阅读的翻页间隔：由自动滚动速度（0..100）映射，约 1.5s..11.5s 一页。 */
    private long autoPageInterval() {
        int speed = Math.max(0, Math.min(100, getPrefs().getInt("auto_scroll", 50)));
        return Math.max(1500L, (105L - speed) * 110L);
    }

    /** 是否为分页模式（平移/仿真），与上下滚动模式相对。 */
    private boolean isPaged() {
        return flipMode != FLIP_SCROLL;
    }

    /** 当前分页视图驱动下一页（自动阅读用）；本章末页返回 false。 */
    private boolean turnPagedNext() {
        return flipMode == FLIP_CURL ? mBinding.curlView.turnNextAnimated() : mBinding.slideView.turnNextAnimated();
    }

    /** 当前分页视图的整章字符数（进度条口径）。 */
    private int pagedTotalChars() {
        return flipMode == FLIP_CURL ? mBinding.curlView.getTotalChars() : mBinding.slideView.getTotalChars();
    }

    /** 当前分页视图的当前页起始字符偏移（进度条口径）。 */
    private int pagedCharOffset() {
        return flipMode == FLIP_CURL ? mBinding.curlView.getCurrentCharOffset() : mBinding.slideView.getCurrentCharOffset();
    }

    /** 跳转当前分页视图到指定字符偏移（进度条跳转 / TTS 跟随共用）。 */
    private void pagedGotoCharOffset(int offset) {
        if (flipMode == FLIP_CURL) mBinding.curlView.gotoCharOffset(offset);
        else mBinding.slideView.gotoCharOffset(offset);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(NovelSettingsDialog.PREFS, 0);
    }

    /**
     * 上下留白基准：屏幕**宽度**的 3%，动态换算为像素（不写死 dp，适配不同分辨率/密度）。
     */
    private int widthGapPx() {
        return (int) (getResources().getDisplayMetrics().widthPixels * 0.03f);
    }

    /**
     * 用户在「排版」面板里调整的上/下边距（dp→px）。
     * 默认 0：表示不再额外叠加留白，仅保留 3% 屏宽的紧凑留白。
     */
    private int userInsetPx(String key) {
        return (int) (getPrefs().getInt(key, 0) * getResources().getDisplayMetrics().density);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityNovelReaderBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> applyStatusBarInset(insets));
        parseIntent();
        initProgress();
        if (targets.isEmpty()) {
            showTip("暂无内容");
            return;
        }
        open(index);
    }

    @Override
    protected void initEvent() {
        mBinding.back.setOnClickListener(v -> finish());
        mBinding.prevChapter.setOnClickListener(v -> step(-1));
        mBinding.nextChapter.setOnClickListener(v -> step(1));
        // 目录入口（图 1 口径）：点底栏章节指示打开
        mBinding.chapterIndicator.setOnClickListener(v -> showCatalog());
        // 顶栏书签：打开原版书签弹窗（列表 + 添加当前位置书签 + 点击跳转 + 长按删除）
        mBinding.btnBookmarkTop.setOnClickListener(v -> showBookmarkDialog());
        mBinding.btnKeep.setOnClickListener(v -> toggleKeep());
        mBinding.btnVolume.setOnClickListener(v -> showVolumeDialog());
        mBinding.prevPage.setOnClickListener(v -> turnPageSmart(false));
        mBinding.nextPage.setOnClickListener(v -> turnPageSmart(true));
        mBinding.chipScroll.setOnClickListener(v -> setFlipMode(FLIP_SCROLL));
        mBinding.chipSlide.setOnClickListener(v -> setFlipMode(FLIP_SLIDE));
        mBinding.chipCurl.setOnClickListener(v -> setFlipMode(FLIP_CURL));
        mBinding.btnAuto.setOnClickListener(v -> setAutoScroll(!autoScrolling));
        // 朗读 —— 单击开始/暂停；长按打开朗读设置（语速 / 音色 / 停止）
        mBinding.btnTts.setOnClickListener(v -> toggleTts());
        mBinding.btnTts.setOnLongClickListener(v -> {
            showTtsSettings();
            return true;
        });
        // 「主题」（精修 3）：直达主题点选，与设置弹窗里的主题共用同一弹窗/存储/应用链路
        mBinding.btnTheme.setOnClickListener(v -> showThemeDialog());
        mBinding.btnMore.setOnClickListener(v -> showMoreDialog());
        // #65：正文长按开关自动阅读已移除——Liu 定案自动阅读的启停只在「自动阅读」按钮上，
        // 触碰正文（单击/长按/拖动）一律不停自动，避免误触打断。
        mBinding.scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            updateProgress();
            onScrollForAutoHide();
        });
        mBinding.curlView.setListener(new PageCurlView.Listener() {
            @Override
            public void onPageChanged(int pageIndex, int pageCount) {
                updateProgress();
            }

            @Override
            public void onUserDrag() {
                // #85：左右模式拖动卷页（位移 >4dp）隐藏功能框，与上下模式滚动即藏同机制（阈值：左右 4dp / 上下 8dp，#86）；
                // 只藏框，不停自动阅读（停自动仍只靠「自动阅读」按钮，#65 定案）
                if (controlsVisible) setControlsVisible(false);
                lastTapUpTime = 0; // 拖动不是点按：断开点按连点链（点-拖-点不并成一次双击）
            }

            @Override
            public void onUserTouch() {
                // #65：触碰正文不再停自动阅读（停自动只靠再点「自动阅读」按钮），此处仅保留回调
            }

            @Override
            public void onTap(float x, float y) {
                // 点按切换功能框显隐；自动阅读不中断（不调 setAutoScroll）
                onBodyTap(x, y);
            }
        });
        mBinding.slideView.setListener(new PageSlideView.Listener() {
            @Override
            public void onPageChanged(int pageIndex, int pageCount) {
                updateProgress();
            }

            @Override
            public void onUserDrag() {
                // 与卷页一致：拖动翻页（位移 >4dp）隐藏功能框，只藏框不停自动阅读
                if (controlsVisible) setControlsVisible(false);
                lastTapUpTime = 0; // 同卷页：拖动断开点按连点链
            }

            @Override
            public void onUserTouch() {
                // 同卷页：触碰正文不停自动阅读，此处仅保留回调
            }

            @Override
            public void onTap(float x, float y) {
                // 同卷页：点按切换功能框显隐，自动阅读不中断
                onBodyTap(x, y);
            }
        });
        // 正文触摸（#65）：只做两件事——记录手指滚动供自动隐藏判定、点按判定切换功能框显隐；
        // 单击不翻页（原点按分区翻页已移除，翻页只在功能键上）、触摸不停自动阅读；
        // 监听全程返回 false 不消费事件，滑动滚动与子视图行为不受任何影响。
        mBinding.scrollView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    textTouching = true;
                    touchScrollAccum = 0;
                    lastTouchScrollY = mBinding.scrollView.getScrollY();
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    touchDownTime = System.currentTimeMillis();
                    break;
                case MotionEvent.ACTION_UP: {
                    textTouching = false;
                    // 点按判定：短时（<400ms）且几乎没位移（<24dp）才算一次点按
                    float slop = 24 * getResources().getDisplayMetrics().density;
                    boolean tap = !loading && System.currentTimeMillis() - touchDownTime < 400
                            && Math.abs(event.getX() - touchDownX) < slop
                            && Math.abs(event.getY() - touchDownY) < slop;
                    if (tap) onBodyTap(event.getX(), event.getY());
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    textTouching = false;
                    break;
                default:
                    break;
            }
            return false;
        });
        mBinding.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    setAutoScroll(false);
                    scrollToProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
            }
        });
        if (getPrefs().getBoolean("keep_screen", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        applySavedSettings();
        int savedFlip = getPrefs().getInt("flip_mode", FLIP_SCROLL);
        setFlipMode(savedFlip == FLIP_CURL || savedFlip == FLIP_SLIDE ? savedFlip : FLIP_SCROLL);
    }

    /**
     * 顶栏下移（精修 1；#60 再上移）：状态栏 inset 作为顶栏顶部 padding，与 VideoActivity
     * 同一 WindowInsets 机制；#60 起余量由 5dp 收为 1dp、布局侧下 padding 同步 5dp→1dp，
     * 按键区整体上移约 4dp，但整行仍完整落在状态栏下方、可点区域不被吞。
     * 左右与底部 padding 保持布局原值。
     */
    private WindowInsetsCompat applyStatusBarInset(WindowInsetsCompat insets) {
        int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        sysStatusTop = top;
        sysNavBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
        int extra = (int) (1 * getResources().getDisplayMetrics().density);
        mBinding.topBar.setPadding(mBinding.topBar.getPaddingLeft(), top + extra,
                mBinding.topBar.getPaddingRight(), mBinding.topBar.getPaddingBottom());
        applyContentInsets();
        return insets;
    }

    private void parseIntent() {
        Intent intent = getIntent();
        bookTitle = safe(intent.getStringExtra(EXTRA_TITLE));
        siteKey = safe(intent.getStringExtra(EXTRA_KEY));
        flag = safe(intent.getStringExtra(EXTRA_FLAG));
        keepKey = safe(intent.getStringExtra(EXTRA_KEEP_KEY));
        vodName = safe(intent.getStringExtra(EXTRA_VOD_NAME));
        vodPic = safe(intent.getStringExtra(EXTRA_VOD_PIC));
        siteName = safe(intent.getStringExtra(EXTRA_SITE_NAME));
        localPath = safe(intent.getStringExtra(EXTRA_LOCAL_PATH));
        ArrayList<String> items = intent.getStringArrayListExtra(EXTRA_ITEMS);
        if (items == null) items = new ArrayList<>();
        for (String item : items) {
            int at = item.indexOf('$');
            String name = at >= 0 ? item.substring(0, at).trim() : "";
            String target = at >= 0 ? item.substring(at + 1).trim() : item.trim();
            if (target.isEmpty()) continue;
            names.add(TextUtils.isEmpty(name) ? "第 " + (names.size() + 1) + " 章" : name);
            targets.add(target);
            bodies.add("");
        }
        int size = targets.size();
        index = size == 0 ? 0 : Math.max(0, Math.min(intent.getIntExtra(EXTRA_INDEX, 0), size - 1));
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    /** 打开指定章节：正文已在内存则直接渲染，否则回源拉取。 */
    private void open(int position) {
        if (openedOnce && position != index) saveProgress();
        openedOnce = true;
        index = position;
        renderBar();
        mBinding.seekBar.setProgress(0);
        mBinding.scrollView.scrollTo(0, 0);
        String cached = bodies.get(position);
        if (!TextUtils.isEmpty(cached)) {
            render(cached);
            return;
        }
        String target = targets.get(position);
        if (target.startsWith(NOVEL)) {
            bodies.set(position, decode(target));
            render(bodies.get(position));
            return;
        }
        if (target.startsWith(LOCAL)) {
            loadLocal(position, target);
            return;
        }
        if (siteKey.isEmpty()) {
            render(target);
            return;
        }
        request(position, target);
    }

    /**
     * 回源取内容并按类型分发（混合类型源）：novel:// 是正文，照旧解码渲染；
     * pics:// 是图集集，跳图片播放器（带全章列表）；一整条 http(s) 链接是视频集，
     * 关阅读器跳播放页直接播该集——不再把图片串/视频链接当正文显示。
     */
    private void request(int position, String id) {
        loading = true;
        showTip("加载中…");
        Task.execute(() -> {
            String raw = "";
            Map<String, String> header = null;
            try {
                Result result = SiteApi.playerContent(siteKey, flag, id);
                if (result != null) {
                    raw = string(result.getUrl().v());
                    header = result.getHeader();
                }
            } catch (Throwable e) {
                SpiderDebug.log("novel", "load fail: %s", e.getMessage());
            }
            String url = raw;
            Map<String, String> map = header;
            runOnUiThread(() -> {
                loading = false;
                boolean pics = url.startsWith("pics://");
                boolean media = !pics && !url.contains(NOVEL) && isMediaUrl(url);
                if (pics || media) {
                    if (index != position) return;
                    if (pics) openImageChapter(position, url, map);
                    else openVideoChapter(position);
                    return;
                }
                String content = decode(url);
                bodies.set(position, content);
                if (index != position) return;
                if (TextUtils.isEmpty(content)) {
                    showTip("正文加载失败");
                    return;
                }
                render(content);
            });
        });
    }

    /** playerContent 返回一整条无空白的 http(s) 链接即视频/音频集，而不是正文文本。 */
    private boolean isMediaUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = url.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return false;
        return true;
    }

    /**
     * 图集集：跳内置图片播放器并带上全章列表（#101，与详情页分发同构）——图片页内才有
     * 目录/上一话/下一话可切章；切章结果经 onActivityResult 回传本页续行分发。
     * 本页不再即时 finish：有选中章就按其实际类型续行，未选章返回才关页（见下）。
     */
    private void openImageChapter(int position, String picsUrl, Map<String, String> header) {
        ImageActivity.startForResult(this, names.get(position), picsUrl, header, new ArrayList<>(names), new ArrayList<>(targets), position);
    }

    /**
     * 图片页切章回传（#101）：选中其它章 → 回本页 open() 按该章实际类型续行
     * （正文就地渲染、图集再进图片页、视频跳播放页）；未选章直接返回 → 关阅读器，
     * 落点与 #100「开图即关阅读器」一致（回主页）——本页对图集集没有可渲染的正文可退回。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ImageActivity.REQUEST_IMAGE) return;
        int position = data == null ? -1 : data.getIntExtra("episode_position", -1);
        if (resultCode == RESULT_OK && position >= 0 && position < targets.size()) open(position);
        else finish();
    }

    /** 视频集：关阅读器，以本站/本作品/当前章名新开播放页并直接播该集（用户已明确选集，不受自动播放开关影响）。 */
    private void openVideoChapter(int position) {
        String vodId = extractVodId();
        if (siteKey.isEmpty() || vodId.isEmpty()) {
            showTip("无法打开视频");
            return;
        }
        // 带集序号直达：新开详情按序号定位目标集（视频集多集也不串位），不再只靠集名模糊匹配
        VideoActivity.startForcePlay(this, siteKey, vodId, vodName, vodPic, names.get(position), position);
        finish();
    }

    /** keepKey 形如 siteKey@@@vodId@@@cid，从中拆出 vodId。 */
    private String extractVodId() {
        String prefix = siteKey + "@@@";
        if (keepKey == null || !keepKey.startsWith(prefix)) return "";
        String rest = keepKey.substring(prefix.length());
        int at = rest.indexOf("@@@");
        return at >= 0 ? rest.substring(0, at) : rest;
    }

    /** 把 novel://{JSON} 解成正文，兼容纯文本载荷。 */
    private String decode(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String payload = url;
        int at = payload.indexOf(NOVEL);
        if (at >= 0) payload = payload.substring(at + NOVEL.length());
        if (!payload.startsWith("{")) return payload;
        try {
            JSONObject object = new JSONObject(payload);
            String content = object.optString("content", "");
            String title = object.optString("title", "");
            if (!TextUtils.isEmpty(content)) return content;
            return title;
        } catch (Throwable e) {
            SpiderDebug.log("novel", "decode fail: %s", e.getMessage());
            return payload;
        }
    }

    private void renderBar() {
        if (names.isEmpty()) return;
        // 顶栏标题 = 当前章名（居中）；章节指示 = 当前章 / 总章数（章节进度，非页码）
        mBinding.title.setText(names.get(index));
        mBinding.chapterIndicator.setText((index + 1) + " / " + names.size());
        mBinding.prevChapter.setAlpha(index > 0 ? 1f : 0.4f);
        mBinding.nextChapter.setAlpha(index < names.size() - 1 ? 1f : 0.4f);
        refreshKeepImg();
    }

    /** 渲染正文：标题独立成行（居中加粗），正文逐段补段首缩进。 */
    private void render(String content) {
        // 正文常带 HTML 标签与实体，先清成纯文本再排版，避免出现裸标签
        String body = stripHtml(content == null ? "" : content);
        int paraSpacing = getPrefs().getInt("para_spacing", 10);
        if (paraSpacing > 10) {
            StringBuilder gap = new StringBuilder();
            for (int i = 0; i < (paraSpacing - 10) / 10; i++) gap.append("\n");
            body = body.replace("\n\n", "\n\n" + gap);
        }
        body = addIndents(body);
        lastBody = body;
        mBinding.chapterTitle.setText(names.get(index));
        mBinding.content.setText(body, android.widget.TextView.BufferType.SPANNABLE);
        if (isPaged()) setupPagedPages(0);
        // 换章 / 重排版后，若正在朗读就重新切段并从本章开头续读（跨章连读靠这里）
        if (ttsSpeaking) {
            buildChunks();
            speakCurrentChunk();
        }
        if (pendingPercent >= 0) {
            int percent = pendingPercent;
            pendingPercent = -1;
            mBinding.getRoot().post(() -> scrollToProgress(percent));
        }
    }

    /** 书架接线：本书标识（蜘蛛书 = keepKey，本地书 = local:路径）。 */
    private String bookId() {
        if (!localPath.isEmpty()) return "local:" + localPath;
        if (!keepKey.isEmpty()) return keepKey;
        return siteKey + "@@@" + bookTitle;
    }

    /** 进度恢复与入架自愈：记录章与入口章一致时才恢复章内百分比；已收藏的蜘蛛书打开即标记入架。 */
    private void initProgress() {
        if (!localPath.isEmpty()) mBinding.btnKeep.setVisibility(View.GONE);
        if (!keepKey.isEmpty() && Keep.find(keepKey) != null) NovelProgress.markShelf(keepKey, true);
        JSONObject record = NovelProgress.find(bookId());
        if (record != null && record.optInt("chapter", -1) == index) pendingPercent = record.optInt("percent", -1);
    }

    /** 保存当前章进度（切章前与 onPause 调用）：章 index + seekBar 章内百分比。 */
    private void saveProgress() {
        if (names.isEmpty() || index < 0 || index >= names.size()) return;
        try {
            updateProgress();
        } catch (Exception ignored) {
        }
        NovelProgress.save(bookId(), TextUtils.isEmpty(vodName) ? bookTitle : vodName, vodPic,
                localPath.isEmpty() ? siteName : getString(R.string.shelf_local_tag),
                index, names.get(index), mBinding.seekBar.getProgress());
    }

    /** 本地书章节：按索引偏移从源文件切出正文（后台读、主线程渲染），与回源 request 同构。 */
    private void loadLocal(int position, String target) {
        loading = true;
        showTip("加载中…");
        Task.execute(() -> {
            String content = "";
            try {
                String spec = target.substring(LOCAL.length());
                int colon = spec.indexOf(':');
                int start = Integer.parseInt(spec.substring(0, colon));
                int length = Integer.parseInt(spec.substring(colon + 1));
                content = LocalBooks.readChapter(localPath, start, length);
            } catch (Exception e) {
                SpiderDebug.log("novel", "local load fail: %s", e.getMessage());
            }
            String text = content;
            runOnUiThread(() -> {
                loading = false;
                bodies.set(position, text);
                if (index != position) return;
                if (TextUtils.isEmpty(text)) {
                    showTip("正文加载失败");
                    return;
                }
                render(text);
            });
        });
    }

    /**
     * 段首缩进（图 1 排版）：每行先清掉行首已有的半角/全角空格，再统一补两全角空格，
     * 保证每段恰好缩进两字、不会叠加源文本自带的缩进；空行保持为空。
     */
    private String addIndents(String body) {
        String[] lines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder(body.length() + lines.length * 2);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            int start = 0;
            while (start < line.length()) {
                char c = line.charAt(start);
                if (c == ' ' || c == '\u3000' || c == '\t') start++;
                else break;
            }
            String trimmed = line.substring(start);
            if (!trimmed.isEmpty()) sb.append("\u3000\u3000");
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private void showTip(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /**
     * 把正文里的 HTML 标签与实体转成纯文本。
     * 这里不用 Util.clean()：那个方法会把空行一并压掉，与「段间距」设置冲突。
     * 注意：不再把全角空格替换成普通空格——段首缩进要靠它（见 addIndents）。
     */
    private String stripHtml(String text) {
        if (TextUtils.isEmpty(text) || !text.contains("<")) return text;
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\u00A0", " ");
    }

    private void step(int delta) {
        if (loading) return;
        int target = index + delta;
        if (target < 0) {
            showTip("已经是第一章");
            return;
        }
        if (target >= names.size()) {
            showTip("已经是最后一章");
            return;
        }
        setAutoScroll(false);
        open(target);
    }

    // ===== 收藏（顶栏 ☆，接应用 Keep 体系，收藏整本） =====

    private void refreshKeepImg() {
        boolean kept = !keepKey.isEmpty() && Keep.find(keepKey) != null;
        mBinding.btnKeep.setImageResource(kept ? R.drawable.ic_star_filled : R.drawable.ic_star);
    }

    private void toggleKeep() {
        if (keepKey.isEmpty()) {
            showTip("收藏信息缺失");
            return;
        }
        Keep keep = Keep.find(keepKey);
        if (keep != null) {
            keep.delete();
            NovelProgress.markShelf(keepKey, false);
            showTip(getString(R.string.keep_del));
        } else {
            Keep item = new Keep();
            item.setKey(keepKey);
            item.setCid(VodConfig.getCid());
            item.setVodPic(vodPic);
            item.setVodName(TextUtils.isEmpty(vodName) ? bookTitle : vodName);
            item.setSiteName(siteName);
            item.setCreateTime(System.currentTimeMillis());
            item.save();
            NovelProgress.markShelf(keepKey, true);
            showTip(getString(R.string.keep_add));
        }
        refreshKeepImg();
    }

    // ===== 音量（顶栏喇叭改音量键：点击弹出音量滑块） =====

    private void showVolumeDialog() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView value = new TextView(this);
        value.setText("音量：" + current + " / " + max);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(current);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    value.setText("音量：" + progress + " / " + max);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(value);
        box.addView(bar);
        new AlertDialog.Builder(this)
                .setTitle("音量")
                .setView(box)
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    /** 「更多设置」：原「阅读设置」「主题」两入口合流为一个（同一弹窗类的两种模式）。 */
    private void showMoreDialog() {
        new AlertDialog.Builder(this)
                .setTitle("更多设置")
                .setItems(new String[]{"阅读设置", "主题"}, (dialog, which) -> {
                    if (which == 0) showSettings();
                    else showThemeDialog();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    // ===== 翻页模式（上下滚动 / 平移 / 仿真卷页） =====

    private void setFlipMode(int mode) {
        // 朗读会话期间不许切进分页模式：分页视图没有跟随落点，高亮与朗读必然对不上（开读时已切回滚动）
        if (ttsSession && mode != FLIP_SCROLL) return;
        setAutoScroll(false);
        int previous = flipMode;
        flipMode = mode;
        getPrefs().edit().putInt("flip_mode", mode).apply();
        mBinding.scrollView.setVisibility(mode == FLIP_SCROLL ? View.VISIBLE : View.GONE);
        mBinding.slideView.setVisibility(mode == FLIP_SLIDE ? View.VISIBLE : View.GONE);
        mBinding.curlView.setVisibility(mode == FLIP_CURL ? View.VISIBLE : View.GONE);
        mBinding.chipScroll.setSelected(mode == FLIP_SCROLL);
        mBinding.chipSlide.setSelected(mode == FLIP_SLIDE);
        mBinding.chipCurl.setSelected(mode == FLIP_CURL);
        if (mode == FLIP_SCROLL) {
            // 切回滚动：进度条已被分页位置持续同步，滚回同一进度即可
            int progress = mBinding.seekBar.getProgress();
            mBinding.scrollView.post(() -> scrollToProgress(progress));
        } else if (previous == FLIP_SCROLL) {
            // 自滚动切入分页：按切换前的阅读进度（进度条口径）落到对应字符位置
            int progress = mBinding.seekBar.getProgress();
            setupPagedPages(0);
            int total = pagedTotalChars();
            if (total > 0 && progress > 0) pagedGotoCharOffset(total * progress / 100);
        } else {
            // 平移与仿真互切：按切换前分页视图的字符位置对齐（两视图内容同源、页界一致）
            int keep = previous == FLIP_CURL ? mBinding.curlView.getCurrentCharOffset()
                    : mBinding.slideView.getCurrentCharOffset();
            setupPagedPages(keep);
        }
    }

    /** 用当前章标题（居中加粗 span）+ 正文组分页文本，同步下发平移/仿真两视图并按现行排版分页。 */
    private void setupPagedPages(int charOffset) {
        String title = names.isEmpty() ? "" : names.get(index);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!title.isEmpty()) {
            int start = sb.length();
            sb.append(title);
            sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.15f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n\n");
        }
        sb.append(lastBody == null ? "" : lastBody);
        mBinding.curlView.setContent(sb, mBinding.content.getPaint(),
                mBinding.content.getLineSpacingMultiplier(), mBinding.content.getLineSpacingExtra());
        mBinding.slideView.setContent(sb, mBinding.content.getPaint(),
                mBinding.content.getLineSpacingMultiplier(), mBinding.content.getLineSpacingExtra());
        if (charOffset > 0) {
            mBinding.curlView.gotoCharOffset(charOffset);
            mBinding.slideView.gotoCharOffset(charOffset);
        }
    }

    /** 排版/字体/主题变化后：分页模式下保持当前字符位置重分页。 */
    private void refreshPaged() {
        if (!isPaged()) return;
        setupPagedPages(pagedCharOffset());
    }

    /** 「上一页 / 下一页」按钮：上下模式按屏滚动，分页模式走当前分页视图的翻页动画。 */
    private void turnPageSmart(boolean forward) {
        if (loading) return;
        setAutoScroll(false);
        if (isPaged()) {
            if (flipMode == FLIP_CURL) {
                if (forward) mBinding.curlView.turnNextAnimated();
                else mBinding.curlView.turnPrevAnimated();
            } else {
                if (forward) mBinding.slideView.turnNextAnimated();
                else mBinding.slideView.turnPrevAnimated();
            }
        } else {
            turnPage(forward);
        }
    }

    /**
     * 章节目录：网格列数与每页章数按面板可用宽高自适应，
     * 支持上一页/下一页切换，打开时自动定位到当前章节所在页。
     */
    private void showCatalog() {
        if (names.isEmpty()) {
            showTip("暂无目录");
            return;
        }
        int pageCount = catalogPageCount();
        catalogPage = Math.max(0, Math.min(index / catalogPageSize(), pageCount - 1));
        View view = getLayoutInflater().inflate(R.layout.dialog_novel_catalog, null);
        catalogGrid = view.findViewById(R.id.catalogGrid);
        catalogGrid.setColumnCount(catalogColumns);
        catalogScroll = view.findViewById(R.id.catalogScroll);
        catalogPageInfo = view.findViewById(R.id.catalogPageInfo);
        catalogPrev = view.findViewById(R.id.catalogPrev);
        catalogNext = view.findViewById(R.id.catalogNext);
        // 目录弹窗高度取屏幕 55%，超出部分在网格内滚动，长篇小说不再一次性平铺
        ViewGroup.LayoutParams params = catalogScroll.getLayoutParams();
        params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        catalogScroll.setLayoutParams(params);
        catalogPrev.setOnClickListener(v -> switchCatalogPage(-1));
        catalogNext.setOnClickListener(v -> switchCatalogPage(1));
        // 面板可用宽高就绪后（含旋转/窗口尺寸变化）重算列数与每页章数，保证铺满、无半行裁切
        catalogScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyCatalogMetrics();
        });
        catalogDialog = new AlertDialog.Builder(this)
                .setTitle("目录")
                .setView(view)
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        catalogDialog.setOnDismissListener(d -> catalogDialog = null);
        // 弹窗显示前先用屏幕尺寸估算一次，保证打开时已定位到当前章节所在页
        applyCatalogMetrics();
        catalogPage = Math.max(0, Math.min(index / catalogPageSize(), catalogPageCount() - 1));
        fillCatalogPage();
        catalogDialog.show();
        if (catalogDialog.getWindow() != null) {
            // 目录选择窗口宽度动态 = 屏幕宽度 × 0.95（比屏幕宽度小 5%），按屏幕宽度实时换算像素，不写死 dp
            catalogDialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.95f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        // 弹窗真正布局完成后按实测宽高再校一次，并把当前页重新对齐
        catalogScroll.post(() -> {
            applyCatalogMetrics();
            catalogPage = Math.max(0, Math.min(index / catalogPageSize(), catalogPageCount() - 1));
            fillCatalogPage();
        });
    }

    /** 目录每页章数：至少 1，未测量前用估算值兜底。 */
    private int catalogPageSize() {
        if (catalogPageSize <= 0) catalogPageSize = estimatePageSize();
        return Math.max(1, catalogPageSize);
    }

    /**
     * 按目录面板的可用宽高重算列数与每页章数：
     * 列数 = 可用宽度 /（单条最小可读宽度 + 两侧外间距），clamp 到 1..4；
     * 各列等权平分面板宽度，故整组天然左右对称（1 列时单列居满、文案居中）。
     * 行数 = 可用高度 / 条目实测高度（至少 1 行），每页章数 = 行数 × 列数。
     */
    private void applyCatalogMetrics() {
        if (catalogGrid == null || catalogScroll == null) return;
        float density = getResources().getDisplayMetrics().density;
        int margin = (int) (CATALOG_ITEM_MARGIN_DP * density);
        // 宽度优先取网格实测宽度；未布局时退回按弹窗宽度（屏宽 95%）估算
        int width = catalogGrid.getWidth() > 0 ? catalogGrid.getWidth()
                : (catalogScroll.getWidth() > 0 ? catalogScroll.getWidth()
                : (int) (getResources().getDisplayMetrics().widthPixels * 0.95f));
        int height = catalogScroll.getHeight() > 0 ? catalogScroll.getHeight()
                : (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        int columns = computeColumns(width, margin);
        int pageSize = computePageSize(height, columns, margin);
        if (columns == catalogColumns && pageSize == catalogPageSize) return;
        catalogColumns = columns;
        catalogPageSize = pageSize;
        catalogGrid.setColumnCount(columns);
        fillCatalogPage();
    }

    /** 由面板可用宽度反推列数：单条最小可读宽度 + 列间距，clamp 到 2..6。 */
    private int computeColumns(int width, int margin) {
        int minItem = (int) (CATALOG_MIN_ITEM_WIDTH_DP * getResources().getDisplayMetrics().density);
        int columns = width / Math.max(1, minItem + margin * 2);
        return Math.max(CATALOG_MIN_COLUMNS, Math.min(CATALOG_MAX_COLUMNS, columns));
    }

    /** 由面板可用高度反推每页章数：行数（至少 1）× 列数。 */
    private int computePageSize(int height, int columns, int margin) {
        int itemHeight = measureCatalogItemHeight(margin);
        int rows = Math.max(1, height / Math.max(1, itemHeight));
        return rows * columns;
    }

    /** 实测单条目录项高度（含上下外边距），探针 padding 与真实胶囊条目保持一致，避免行高估算偏差。 */
    private int measureCatalogItemHeight(int margin) {
        float density = getResources().getDisplayMetrics().density;
        TextView probe = new TextView(this);
        probe.setTextSize(CATALOG_ITEM_TEXT_SP);
        probe.setSingleLine(true);
        probe.setPadding((int) (CATALOG_ITEM_PAD_H_DP * density), (int) (CATALOG_ITEM_PAD_V_DP * density),
                (int) (CATALOG_ITEM_PAD_H_DP * density), (int) (CATALOG_ITEM_PAD_V_DP * density));
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        probe.measure(spec, spec);
        return probe.getMeasuredHeight() + margin * 2;
    }

    /** 弹窗布局前用屏幕尺寸估算每页章数，仅作打开定位的兜底。 */
    private int estimatePageSize() {
        float density = getResources().getDisplayMetrics().density;
        int margin = (int) (CATALOG_ITEM_MARGIN_DP * density);
        int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.95f);
        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        return computePageSize(height, computeColumns(width, margin), margin);
    }

    private int catalogPageCount() {
        int size = catalogPageSize();
        return Math.max(1, (names.size() + size - 1) / size);
    }

    /**
     * 目录条目文案（#60）：「序号. 名称」。
     * 名称本身是纯数字时（不少源的章节名就是「1」「2」…）只显示该数字，避免出现「1. 1」式重复；
     * 名称为空时退回纯序号。
     */
    private String catalogLabel(int i) {
        String name = names.get(i).trim();
        if (name.isEmpty()) return String.valueOf(i + 1);
        if (name.matches("\\d+")) return name;
        return (i + 1) + ". " + name;
    }

    /** 按当前页重填网格（列数/每页章数均按面板尺寸自适应），并刷新分页信息与翻页按钮可用态。 */
    private void fillCatalogPage() {
        if (catalogGrid == null) return;
        int size = catalogPageSize();
        int pageCount = catalogPageCount();
        catalogPage = Math.max(0, Math.min(catalogPage, pageCount - 1));
        int start = catalogPage * size;
        int end = Math.min(names.size(), start + size);
        catalogGrid.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int margin = (int) (CATALOG_ITEM_MARGIN_DP * density);
        int padH = (int) (CATALOG_ITEM_PAD_H_DP * density);
        int padV = (int) (CATALOG_ITEM_PAD_V_DP * density);
        for (int i = start; i < end; i++) {
            final int position = i;
            TextView item = new TextView(this);
            // #60：文案 =「序号. 名称」；名称本身为纯数字时只显示该数字（避免「1. 1」）
            item.setText(catalogLabel(i));
            item.setTextSize(CATALOG_ITEM_TEXT_SP);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setGravity(Gravity.CENTER);
            item.setPadding(padH, padV, padH, padV);
            item.setFocusable(true);
            item.setClickable(true);
            item.setTextColor(i == index ? androidx.core.content.ContextCompat.getColor(this, R.color.hh_text_accent) : androidx.core.content.ContextCompat.getColor(this, R.color.hh_text_primary));
            // #60：每个章节项统一胶囊底；bg_hh_pill 是选择器，当前章给 selected 语义亮成暖金高亮
            item.setBackgroundResource(R.drawable.bg_hh_pill);
            item.setSelected(i == index);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);
            params.setMargins(margin, margin, margin, margin);
            item.setLayoutParams(params);
            item.setOnClickListener(v -> {
                closeCatalog();
                if (loading) return;
                open(position);
            });
            catalogGrid.addView(item);
        }
        if (catalogPageInfo != null) {
            catalogPageInfo.setText("第 " + (start + 1) + "-" + end + " 章 · 共 " + names.size() + " 章 · "
                    + (catalogPage + 1) + "/" + pageCount + " 页");
        }
        if (catalogPrev != null) catalogPrev.setAlpha(catalogPage > 0 ? 1f : 0.4f);
        if (catalogNext != null) catalogNext.setAlpha(catalogPage < pageCount - 1 ? 1f : 0.4f);
    }

    private void switchCatalogPage(int delta) {
        int pageCount = catalogPageCount();
        int target = catalogPage + delta;
        if (target < 0) {
            showTip("已经是第一页");
            return;
        }
        if (target >= pageCount) {
            showTip("已经是最后一页");
            return;
        }
        catalogPage = target;
        fillCatalogPage();
        if (catalogScroll != null) catalogScroll.scrollTo(0, 0);
    }

    private void closeCatalog() {
        if (catalogDialog != null && catalogDialog.isShowing()) catalogDialog.dismiss();
        catalogDialog = null;
    }

    /** 整屏翻页：按可视高度前进/后退一屏，保留少量重叠避免断句（仅供「上一页/下一页」功能键）。 */
    private void turnPage(boolean forward) {
        View child = mBinding.scrollView.getChildAt(0);
        if (child == null) return;
        int overlap = (int) (16 * getResources().getDisplayMetrics().density);
        int page = Math.max(1, mBinding.scrollView.getHeight() - overlap);
        int max = Math.max(0, child.getHeight() - mBinding.scrollView.getHeight());
        int target = Math.max(0, Math.min(max, mBinding.scrollView.getScrollY() + (forward ? page : -page)));
        mBinding.scrollView.smoothScrollTo(0, target);
    }

    // ===== 阅读设置 =====

    /** 「主题」入口：只包含配色与字体等外观项。 */
    private void showThemeDialog() {
        if (getSupportFragmentManager().findFragmentByTag("novel_theme") != null) return;
        NovelSettingsDialog.create(this, NovelSettingsDialog.MODE_THEME).show(getSupportFragmentManager(), "novel_theme");
    }

    /** 「设置」入口：只包含字号/行距/亮度/翻页模式等行为项。 */
    private void showSettings() {
        if (getSupportFragmentManager().findFragmentByTag("novel_settings") != null) return;
        NovelSettingsDialog.create(this, NovelSettingsDialog.MODE_SETTINGS).show(getSupportFragmentManager(), "novel_settings");
    }

    /**
     * 正文区有效边距 = 设置基础边距 +（全屏时）系统栏避让。功能框显示时正文框本就被
     * 顶/底功能框夹在中间，无需避让；功能框隐藏（全屏）时正文框铺满整屏，顶部须避开
     * 状态栏、底部须避开导航/手势条——否则第一行被状态栏压、最后一行贴屏底被挡
     * （#90 修复，此前正文边距只有屏幕宽 3%≈11dp，远小于系统栏高度）。
     * 三种翻页模式共用：滚动模式走 ScrollView padding，分页模式走视图 insets。
     */
    private void applyContentInsets() {
        if (!insetsReady) return;
        int top = basePadTop + (controlsVisible ? 0 : sysStatusTop);
        int bottom = basePadBottom + (controlsVisible ? 0 : sysNavBottom);
        mBinding.scrollView.setPadding(basePadH, top, basePadH, bottom);
        mBinding.curlView.setInsets(basePadH, top, basePadH, bottom);
        mBinding.slideView.setInsets(basePadH, top, basePadH, bottom);
    }

    private void applySavedSettings() {
        SharedPreferences prefs = getPrefs();
        mBinding.content.setTextSize(prefs.getInt("font_size", 18));
        applyFont(prefs.getInt("font_index", 0), prefs.getBoolean("bold", false));
        mBinding.content.setLineSpacing(0.0f, prefs.getInt("line_spacing", 18) / 10.0f);
        mBinding.content.setLetterSpacing((prefs.getInt("letter_spacing", 5) / 10.0f) / 10.0f);
        // 上下留白按屏幕宽度 3% 动态换算；用户设置更大时以用户设置为准（取较大值）
        basePadH = (int) (prefs.getInt("padding_h", 20) * getResources().getDisplayMetrics().density);
        int gapPx = widthGapPx();
        basePadTop = Math.max(gapPx, userInsetPx("padding_top"));
        basePadBottom = Math.max(gapPx, userInsetPx("padding_bottom"));
        insetsReady = true;
        applyContentInsets();
        int themeIndex = prefs.getInt("theme_index", 0);
        if (themeIndex >= 0 && themeIndex < NovelSettingsDialog.THEME_COLORS.length) {
            int[] colors = NovelSettingsDialog.THEME_COLORS[themeIndex];
            applyTheme(colors[0], colors[1]);
        }
        refreshPaged();
    }

    private void applyFont(int index, boolean bold) {
        Typeface typeface = loadFont(index, bold);
        if (typeface != null) mBinding.content.setTypeface(typeface);
    }

    private Typeface loadFont(int index, boolean bold) {
        String[] paths;
        if (index == 1) {
            paths = new String[]{"/system/fonts/NotoSerifSC-Regular.otf", "/system/fonts/NotoSerifCJK-Regular.ttc", "/system/fonts/NotoSerif-Regular.ttf"};
        } else if (index == 2) {
            paths = new String[]{"/system/fonts/DroidSansMono.ttf", "/system/fonts/CutiveMono.ttf"};
        } else if (index == 3) {
            paths = new String[]{"/system/fonts/NotoSansSC-Bold.otf", "/system/fonts/NotoSansSC-Regular.otf", "/system/fonts/NotoSansCJK-Regular.ttc"};
        } else {
            paths = new String[]{"/system/fonts/NotoSansSC-Regular.otf", "/system/fonts/NotoSansCJK-Regular.ttc"};
        }
        for (String path : paths) {
            try {
                File file = new File(path);
                if (!file.exists()) continue;
                Typeface typeface = Typeface.createFromFile(file);
                if (index == 3 && path.contains("Bold")) return typeface;
                return bold ? Typeface.create(typeface, Typeface.BOLD) : typeface;
            } catch (Exception ignored) {
            }
        }
        Typeface base;
        if (index == 1) base = Typeface.SERIF;
        else if (index == 2) base = Typeface.MONOSPACE;
        else if (index == 3) base = Typeface.SANS_SERIF;
        else base = Typeface.DEFAULT;
        return Typeface.create(base, bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void applyTheme(int bgColor, int textColor) {
        themeBg = bgColor;
        themeText = textColor;
        mBinding.root.setBackgroundColor(bgColor);
        mBinding.content.setTextColor(textColor);
        mBinding.chapterTitle.setTextColor(textColor);
        mBinding.title.setTextColor(textColor);
        mBinding.curlView.setPageBackground(bgColor);
        mBinding.slideView.setPageBackground(bgColor);
        refreshPaged();
    }

    @Override
    public void onFontSizeChanged(int size) {
        mBinding.content.setTextSize(size);
        refreshPaged();
    }

    @Override
    public void onBoldChanged(boolean bold) {
        applyFont(getPrefs().getInt("font_index", 0), bold);
        refreshPaged();
    }

    @Override
    public void onFontChanged(int index) {
        applyFont(index, getPrefs().getBoolean("bold", false));
        refreshPaged();
    }

    @Override
    public void onLineSpacingChanged(float spacing) {
        mBinding.content.setLineSpacing(0.0f, spacing);
        refreshPaged();
    }

    @Override
    public void onLayoutChanged() {
        applySavedSettings();
    }

    @Override
    public void onThemeChanged(int bgColor, int textColor) {
        applyTheme(bgColor, textColor);
    }

    @Override
    public void onAutoScrollChanged(boolean enable) {
        setAutoScroll(enable);
    }

    // ===== 自动阅读（上下=自动滚动，分页=定时翻页） =====

    public void setAutoScroll(boolean enable) {
        autoScrolling = enable;
        // #65：开启自动阅读即进全屏（上下自动滚动与分页定时翻页统一收口在此，含设置页的
        // 自动滚动开关回调）；关闭路径不动功能框——手动停自动时功能框本就可见（靠双击
        // 呼出后再点按钮），自然结束的恢复在 autoScrollRunnable 的结束点单独做。
        if (enable) setControlsVisible(false);
        if (mBinding.btnAuto != null) mBinding.btnAuto.setSelected(enable);
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
        if (enable) {
            autoScrollHandler.post(autoScrollRunnable);
            showTip("自动阅读已开启");
        }
    }

    // ===== 功能框显隐（#65 全屏阅读，Liu 定案的交互） =====

    /**
     * 功能框统一显隐开关：顶栏 + 底栏四行（进度滑杆 / 章节行 / 翻页行 / 动作行）一起动，
     * 150ms 淡入淡出，隐藏终态 GONE（正文区随之铺满，即全屏阅读）。默认显示。
     * 隐藏只有三条路径：开自动阅读（setAutoScroll）、上下模式手指滚动（onScrollForAutoHide）、
     * 朗读开始（startTts）；呼出/藏起由单击正文切换（onBodyTap），另有自动阅读/朗读结束恢复显示。二级页（目录/设置/书签等）都是独立弹窗窗口，
     * 打开期间正文收不到触摸、天然不响应；关闭后回到打开前的显隐状态——状态自洽。
     */
    private void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) return;
        controlsVisible = visible;
        View[] bars = {mBinding.topBar, mBinding.seekBar, mBinding.chapterBar, mBinding.pageFlipBar, mBinding.actionBar};
        for (View bar : bars) {
            bar.animate().cancel();
            if (visible) {
                bar.setVisibility(View.VISIBLE);
                bar.setAlpha(0f);
                bar.animate().alpha(1f).setDuration(150).start();
            } else {
                bar.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    // 动画期间又被呼出则不收起，以 controlsVisible 的最新值为准
                    if (!controlsVisible) {
                        bar.setVisibility(View.GONE);
                        bar.setAlpha(1f);
                    }
                }).start();
            }
        }
        // 边距随功能框状态切换（全屏避让系统栏见 applyContentInsets）；布局落定后再
        // 复核一次，保证分页几何与正文框最终尺寸严格一致——功能框在场时按钮翻页，
        // 最后一行也不会被框压住。
        applyContentInsets();
        mBinding.readerFrame.post(() -> {
            applyContentInsets();
            if (isPaged()) refreshPaged();
        });
    }

    /**
     * 正文点按（三模式共用，2026-11-11 Liu 定：改单击切换）：单击切换功能框显隐——藏着呼出、
     * 显着藏起；自动阅读/朗读都不中断（与原双击呼出同一条路）。300ms 内落在同一点的第二下
     * 与第一下同属一次双击，只由第一下动作、第二下不再切换——保住原双击手感，连点两下
     * 不会呼出又藏回。点-拖-点不并成双击：拖动路径各自断链（滚动见 onScrollForAutoHide，
     * 分页见两 Listener 的 onUserDrag）。
     */
    private void onBodyTap(float x, float y) {
        long now = System.currentTimeMillis();
        float tapSlop = 40 * getResources().getDisplayMetrics().density;
        if (lastTapUpTime > 0 && now - lastTapUpTime <= 300
                && Math.abs(x - lastTapX) < tapSlop && Math.abs(y - lastTapY) < tapSlop) {
            lastTapUpTime = 0;
            return;
        }
        lastTapUpTime = now;
        lastTapX = x;
        lastTapY = y;
        setControlsVisible(!controlsVisible);
    }

    /**
     * 上下模式滚动回调（与 updateProgress 同一监听）：只统计手指触摸正文期间的滚动量，
     * 本次手势累计超过 8dp 即隐藏功能框——真实滚动一发生就响应，不等大位移。
     * 手指没落下时的滚动一律是程序滚动（自动滚动、按钮整页翻、进度条跳转、切章定位、
     * TTS 跟随），不触发隐藏；抬手后的惯性滑行也不再计入（真正的甩动在离手前早已越过阈值）。
     * 累计量超过点按阈值时顺带断开点按连点链：点-滑-点不并成一次双击。
     */
    private void onScrollForAutoHide() {
        int y = mBinding.scrollView.getScrollY();
        if (textTouching && flipMode == FLIP_SCROLL) {
            touchScrollAccum += Math.abs(y - lastTouchScrollY);
            float density = getResources().getDisplayMetrics().density;
            if (touchScrollAccum > 24 * density) lastTapUpTime = 0;
            if (controlsVisible && touchScrollAccum > 8 * density) setControlsVisible(false);
        }
        lastTouchScrollY = y;
    }

    // ===== 阅读进度 =====

    private void updateProgress() {
        if (seeking) return;
        if (isPaged()) {
            int total = pagedTotalChars();
            int offset = pagedCharOffset();
            mBinding.seekBar.setProgress(total <= 0 ? 0 : Math.min(100, Math.max(0, offset * 100 / total)));
            return;
        }
        View child = mBinding.scrollView.getChildAt(0);
        if (child == null) return;
        int height = child.getHeight() - mBinding.scrollView.getHeight();
        if (height <= 0) {
            mBinding.seekBar.setProgress(0);
        } else {
            mBinding.seekBar.setProgress(Math.min(100, Math.max(0, (int) (mBinding.scrollView.getScrollY() * 100.0d / height))));
        }
    }

    private void scrollToProgress(int progress) {
        if (isPaged()) {
            int total = pagedTotalChars();
            if (total > 0) pagedGotoCharOffset(total * progress / 100);
            return;
        }
        View child = mBinding.scrollView.getChildAt(0);
        if (child == null) return;
        int height = child.getHeight() - mBinding.scrollView.getHeight();
        if (height > 0) mBinding.scrollView.scrollTo(0, (int) (progress / 100.0d * height));
    }

    // ===== 书签 =====

    private List<JSONObject> getBookmarks() {
        List<JSONObject> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(getSharedPreferences(BOOKMARK_PREFS, 0).getString("bm_" + bookId(), "[]"));
            for (int i = 0; i < array.length(); i++) list.add(array.getJSONObject(i));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private void saveBookmarks(List<JSONObject> list) {
        try {
            JSONArray array = new JSONArray();
            for (JSONObject object : list) array.put(object);
            getSharedPreferences(BOOKMARK_PREFS, 0).edit().putString("bm_" + bookId(), array.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addBookmark() {
        try {
            List<JSONObject> bookmarks = getBookmarks();
            int progress = mBinding.seekBar.getProgress();
            JSONObject object = new JSONObject();
            object.put("progress", progress);
            object.put("chapter", index);
            object.put("chapterName", names.get(index));
            object.put("snippet", getSnippetAtProgress(progress));
            object.put("time", System.currentTimeMillis());
            bookmarks.add(0, object);
            saveBookmarks(bookmarks);
            showTip("书签已添加");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getSnippetAtProgress(int progress) {
        try {
            String text = mBinding.content.getText().toString();
            int length = (int) (text.length() * progress / 100.0d);
            return text.substring(Math.max(0, length - 20), Math.min(text.length(), length + 30)).replace("\n", " ").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void showBookmarkDialog() {
        final List<JSONObject> bookmarks = getBookmarks();
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
                    String chapterName = object.optString("chapterName", "");
                    items[i] = (chapterName.isEmpty() ? "" : chapterName + " · ") + progress + "% - " + snippet;
                } catch (Exception e) {
                    items[i] = "书签 " + (i + 1);
                }
            }
            builder.setItems(items, (dialog, which) -> {
                try {
                    JSONObject object = bookmarks.get(which);
                    int progress = object.getInt("progress");
                    int chapter = object.optInt("chapter", index);
                    if (chapter != index && chapter >= 0 && chapter < targets.size()) {
                        pendingPercent = progress;
                        open(chapter);
                    } else {
                        scrollToProgress(progress);
                    }
                    showTip("已跳转到 " + progress + "%");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        builder.setPositiveButton("添加书签", (dialog, which) -> addBookmark());
        if (!bookmarks.isEmpty()) builder.setNeutralButton("清空", (dialog, which) -> {
            saveBookmarks(new ArrayList<>());
            showTip("书签已清空");
        });
        builder.setNegativeButton("关闭", null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        ListView listView = dialog.getListView();
        if (listView != null) {
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                List<JSONObject> list = getBookmarks();
                if (position < 0 || position >= list.size()) return true;
                list.remove(position);
                saveBookmarks(list);
                showTip("书签已删除");
                dialog.dismiss();
                showBookmarkDialog();
                return true;
            });
        }
    }

    // ===== 朗读（TTS）=====

    /** 「朗读」按钮：正在读 → 暂停；未读 → 开始（首次会初始化系统语音引擎）。 */
    private void toggleTts() {
        if (ttsSpeaking) pauseTts();
        else startTts();
    }

    private void startTts() {
        loadTtsParams();
        if (tts == null) initTts();
        if (!ttsReady) {
            ttsPending = true;
            showTip(getString(R.string.novel_tts_loading));
            return;
        }
        if (ttsChunks.isEmpty() || !java.util.Objects.equals(ttsBuiltFor, mBinding.content.getText() == null ? "" : mBinding.content.getText().toString())) buildChunks();
        if (ttsChunks.isEmpty()) {
            showTip(getString(R.string.novel_tts_empty));
            return;
        }
        if (ttsIndex >= ttsChunks.size()) ttsIndex = 0;
        anchorTtsToView();
        // 朗读跟随只在上下滚动模式成立：正处平移/仿真时先切回滚动（锚点已按分页位置取好），
        // 会话期间锁定分页胶囊，并藏起功能框沉浸听读（单击照常呼出）
        if (flipMode != FLIP_SCROLL) setFlipMode(FLIP_SCROLL);
        ttsSpeaking = true;
        setTtsSession(true);
        renderTtsLabel();
        setControlsVisible(false);
        speakCurrentChunk();
    }

    /** 懒初始化系统 TTS：中文优先，失败退化到系统默认语言。 */
    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                tts = null;
                showTip(getString(R.string.novel_tts_unsupported));
                return;
            }
            int result = tts.setLanguage(java.util.Locale.CHINA);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(java.util.Locale.getDefault());
            }
            applyTtsParams();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    runOnUiThread(NovelReaderActivity.this::onChunkDone);
                }

                @Override
                public void onError(String utteranceId) {
                    runOnUiThread(NovelReaderActivity.this::onChunkDone);
                }
            });
            ttsReady = true;
            if (ttsPending) {
                ttsPending = false;
                startTts();
            }
        });
    }

    private void loadTtsParams() {
        ttsRate = getPrefs().getFloat("tts_rate", 1f);
        ttsPitch = getPrefs().getFloat("tts_pitch", 1f);
        ttsHighlight = getPrefs().getInt("tts_highlight", 1);
    }

    private void applyTtsParams() {
        if (tts == null) return;
        tts.setSpeechRate(Math.max(0.1f, Math.min(3f, ttsRate)));
        tts.setPitch(Math.max(0.1f, Math.min(3f, ttsPitch)));
    }

    /** 把本章正文切成适合朗读的小段（分级断句，单段 ≤ 50 字），
     *  同时记下每段在正文中的起止偏移——跟随与段高亮都按偏移定位，不再按字数估算。
     *  断句分级：先在句末标点（换行/。！？；…）处断，段长须 ≥ 20 字；
     *  一句攒到 50 字还没句末标点，就往回找最后一个逗号/顿号/冒号在其后断；
     *  全段无任何标点才在第 50 字硬切。切口永远落在标点上，不落词中间；
     *  句末标点后紧跟的收尾符号（引号、括号、省略号）并入本段，不留到下一段开头。 */
    private void buildChunks() {
        ttsChunks.clear();
        ttsStarts.clear();
        ttsEnds.clear();
        ttsIndex = 0;
        ttsChars = 0;
        hlSpan = null;
        CharSequence text = mBinding.content.getText();
        String raw = text == null ? "" : text.toString();
        ttsBuiltFor = raw;
        if (raw.trim().isEmpty()) {
            ttsTotal = 1;
            return;
        }
        ttsTotal = Math.max(1, raw.length());
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
        ttsChunks.add(raw.substring(s, e));
        ttsStarts.add(s);
        ttsEnds.add(e);
    }

    private void speakCurrentChunk() {
        if (tts == null || ttsIndex >= ttsChunks.size()) {
            finishTts();
            return;
        }
        String chunk = ttsChunks.get(ttsIndex);
        tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "novel-" + ttsIndex);
        followTts();
        applyTtsHighlight();
    }

    /** 一段读完：推进下一段；本章读完自动进入下一章继续（由 render() 重新切段续读）。 */
    private void onChunkDone() {
        if (!ttsSpeaking) return;
        ttsIndex++;
        if (ttsIndex < ttsChunks.size()) {
            speakCurrentChunk();
            return;
        }
        if (index < names.size() - 1) step(1);
        else {
            finishTts();
            showTip(getString(R.string.novel_tts_end));
        }
    }

    /** 起播锚点：按当前视图定位段落——分页模式取当前页首偏移，上下模式取屏幕顶行；
     *  朗读从正在看的位置开始（与阅读位置合一）。 */
    private void anchorTtsToView() {
        if (ttsStarts.isEmpty()) return;
        int offset;
        if (isPaged()) {
            offset = pagedCharOffset() - pagedTextShift();
        } else {
            android.graphics.Rect rect = new android.graphics.Rect();
            if (!mBinding.content.getLocalVisibleRect(rect)) return;
            android.text.Layout layout = mBinding.content.getLayout();
            if (layout == null || layout.getText() == null) return;
            int y = Math.max(0, rect.top - mBinding.content.getTotalPaddingTop());
            offset = layout.getLineStart(layout.getLineForVertical(y));
        }
        int idx = 0;
        for (int i = 0; i < ttsStarts.size(); i++) {
            if (ttsStarts.get(i) <= offset) idx = i;
            else break;
        }
        ttsIndex = idx;
    }

    /** 视图跟随朗读进度：分页模式跳到当前段所在页，上下模式按段偏移精确滚到该段首行。 */
    private void followTts() {
        if (ttsIndex >= ttsStarts.size()) return;
        final int start = ttsStarts.get(ttsIndex);
        if (isPaged()) {
            pagedGotoCharOffset(start + pagedTextShift());
            return;
        }
        mBinding.content.post(() -> {
            android.text.Layout layout = mBinding.content.getLayout();
            if (layout == null || layout.getText() == null) return;
            int line = layout.getLineForOffset(Math.min(start, Math.max(0, layout.getText().length() - 1)));
            int y = mBinding.content.getTotalPaddingTop() + layout.getLineTop(line);
            View child = mBinding.scrollView.getChildAt(0);
            int max = child == null ? 0 : Math.max(0, child.getHeight() - mBinding.scrollView.getHeight());
            mBinding.scrollView.scrollTo(0, Math.max(0, Math.min(y, max)));
        });
    }

    /** 分页视图的正文坐标偏移：分页文本 = 章名 + 两个换行 + 正文，滚动面只有正文。 */
    private int pagedTextShift() {
        String title = names.isEmpty() ? "" : names.get(index);
        return title == null || title.isEmpty() ? 0 : title.length() + 2;
    }

    /** 朗读高亮颜色（设置项 tts_highlight：0 关 / 1 黄 / 2 浅绿 / 3 粉），0 表示关闭。 */
    private int ttsHighlightColor() {
        switch (ttsHighlight) {
            case 1: return 0xFFFFF59D;
            case 2: return 0xFFC5E1A5;
            case 3: return 0xFFF8BBD0;
            default: return 0;
        }
    }

    /** 给正在读的这一段套底色高亮；暂停/停止/读完/切章时清除。 */
    private void applyTtsHighlight() {
        int color = ttsHighlightColor();
        if (isPaged()) {
            int s = -1;
            int e = -1;
            if (color != 0 && ttsIndex < ttsStarts.size()) {
                s = ttsStarts.get(ttsIndex) + pagedTextShift();
                e = ttsEnds.get(ttsIndex) + pagedTextShift();
            }
            mBinding.curlView.setTtsHighlight(s, e, color);
            mBinding.slideView.setTtsHighlight(s, e, color);
            return;
        }
        CharSequence text = mBinding.content.getText();
        if (!(text instanceof android.text.Spannable)) return;
        android.text.Spannable sp = (android.text.Spannable) text;
        if (hlSpan != null) {
            sp.removeSpan(hlSpan);
            hlSpan = null;
        }
        if (color == 0 || ttsIndex >= ttsStarts.size()) return;
        int s = ttsStarts.get(ttsIndex);
        int e = Math.min(ttsEnds.get(ttsIndex), sp.length());
        if (s < 0 || s >= e) return;
        hlSpan = new android.text.style.BackgroundColorSpan(color);
        sp.setSpan(hlSpan, s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void clearTtsHighlight() {
        if (isPaged()) {
            mBinding.curlView.setTtsHighlight(-1, -1, 0);
            mBinding.slideView.setTtsHighlight(-1, -1, 0);
            return;
        }
        CharSequence text = mBinding.content.getText();
        if (hlSpan != null && text instanceof android.text.Spannable) {
            ((android.text.Spannable) text).removeSpan(hlSpan);
        }
        hlSpan = null;
    }

    /** 朗读锁定开关：朗读进行中平移/仿真胶囊置灰锁定，暂停/读完/停止即解锁。 */
    private void setTtsSession(boolean active) {
        ttsSession = active;
        mBinding.chipSlide.setEnabled(!active);
        mBinding.chipCurl.setEnabled(!active);
        mBinding.chipSlide.setAlpha(active ? 0.35f : 1f);
        mBinding.chipCurl.setAlpha(active ? 0.35f : 1f);
    }

    private void pauseTts() {
        ttsSpeaking = false;
        if (tts != null) tts.stop();
        clearTtsHighlight();
        // 暂停即解锁胶囊：点按钮停声是最顺手的「停止」；续读时 startTts 会重新锁定并切回滚动
        setTtsSession(false);
        renderTtsLabel();
    }

    private void finishTts() {
        ttsSpeaking = false;
        ttsPending = false;
        clearTtsHighlight();
        setTtsSession(false);
        setControlsVisible(true);
        renderTtsLabel();
    }

    /** 停止朗读：停声并清除高亮；再点朗读按当前视图位置起播。 */
    private void stopTts() {
        ttsSpeaking = false;
        ttsPending = false;
        if (tts != null) tts.stop();
        clearTtsHighlight();
        setTtsSession(false);
        setControlsVisible(true);
        renderTtsLabel();
    }

    private void renderTtsLabel() {
        if (mBinding.btnTtsLabel == null) return;
        mBinding.btnTtsLabel.setText(ttsSpeaking ? R.string.novel_tts_pause : R.string.novel_tts);
    }

    /** 朗读设置：语速 / 音色 / 停止朗读（长按「朗读」打开）。 */
    private void showTtsSettings() {
        final String[] rates = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
        final float[] rateValues = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        final String[] pitches = {"低", "正常", "高"};
        final float[] pitchValues = {0.8f, 1f, 1.2f};
        new AlertDialog.Builder(this)
            .setTitle(R.string.novel_tts_settings)
            .setItems(new String[]{
                getString(R.string.novel_tts_rate) + "（" + String.format(java.util.Locale.US, "%.2f", ttsRate) + "x）",
                getString(R.string.novel_tts_pitch),
                "朗读高亮（" + TTS_HL_NAMES[ttsHighlight] + "）",
                getString(R.string.novel_tts_stop)}, (dialog, which) -> {
                if (which == 0) {
                    int checked = 2;
                    for (int i = 0; i < rateValues.length; i++) if (Math.abs(rateValues[i] - ttsRate) < 0.01f) checked = i;
                    new AlertDialog.Builder(this).setTitle(R.string.novel_tts_rate)
                        .setSingleChoiceItems(rates, checked, (d, w) -> {
                            ttsRate = rateValues[w];
                            getPrefs().edit().putFloat("tts_rate", ttsRate).apply();
                            applyTtsParams();
                            d.dismiss();
                            if (ttsSpeaking && ttsIndex < ttsChunks.size()) speakCurrentChunk();
                        }).show();
                } else if (which == 1) {
                    int checked = 1;
                    for (int i = 0; i < pitchValues.length; i++) if (Math.abs(pitchValues[i] - ttsPitch) < 0.01f) checked = i;
                    new AlertDialog.Builder(this).setTitle(R.string.novel_tts_pitch)
                        .setSingleChoiceItems(pitches, checked, (d, w) -> {
                            ttsPitch = pitchValues[w];
                            getPrefs().edit().putFloat("tts_pitch", ttsPitch).apply();
                            applyTtsParams();
                            d.dismiss();
                            if (ttsSpeaking && ttsIndex < ttsChunks.size()) speakCurrentChunk();
                        }).show();
                } else if (which == 2) {
                    new AlertDialog.Builder(this).setTitle("朗读高亮")
                        .setSingleChoiceItems(TTS_HL_NAMES, ttsHighlight, (d, w) -> {
                            ttsHighlight = w;
                            getPrefs().edit().putInt("tts_highlight", w).apply();
                            d.dismiss();
                            applyTtsHighlight();
                        }).show();
                } else {
                    stopTts();
                    showTip(getString(R.string.novel_tts_stopped));
                }
            })
            .setNegativeButton(R.string.dialog_negative, null)
            .show();
    }

    @Override
    protected void onPause() {
        if (openedOnce) saveProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopTts();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        ttsReady = false;
        setAutoScroll(false);
        closeCatalog();
        super.onDestroy();
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    /** 从 Episode 列表启动。 */
    public static void start(Activity activity, String title, String key, String flag, List<Episode> episodes, int position) {
        if (activity == null || episodes == null || episodes.isEmpty()) return;
        ArrayList<String> items = new ArrayList<>();
        for (Episode item : episodes) {
            if (item == null) continue;
            String url = item.getUrl();
            if (TextUtils.isEmpty(url)) continue;
            items.add(string(item.getName()) + "$" + url);
        }
        start(activity, title, key, flag, items, position);
    }

    /** 从原始章节串启动，每项形如 章节名$标识。 */
    public static void start(Activity activity, String title, String key, String flag, @Nullable ArrayList<String> items, int position) {
        start(activity, title, key, flag, items, position, "", "", "", "");
    }

    /** 带收藏接线地启动：keepKey 非空时顶栏 ☆ 可对当前 Vod 收藏/取消收藏（与详情页同一 Keep 记录）。 */
    public static void start(Activity activity, String title, String key, String flag, @Nullable ArrayList<String> items, int position,
                             String keepKey, String vodName, String vodPic, String siteName) {
        if (activity == null || items == null || items.isEmpty()) return;
        Intent intent = new Intent(activity, NovelReaderActivity.class);
        intent.putExtra(EXTRA_TITLE, string(title));
        intent.putExtra(EXTRA_KEY, string(key));
        intent.putExtra(EXTRA_FLAG, string(flag));
        intent.putExtra(EXTRA_KEEP_KEY, string(keepKey));
        intent.putExtra(EXTRA_VOD_NAME, string(vodName));
        intent.putExtra(EXTRA_VOD_PIC, string(vodPic));
        intent.putExtra(EXTRA_SITE_NAME, string(siteName));
        intent.putStringArrayListExtra(EXTRA_ITEMS, items);
        intent.putExtra(EXTRA_INDEX, Math.max(0, Math.min(position, items.size() - 1)));
        activity.startActivity(intent);
    }

    /** 本地书启动：items 为 LocalBooks.chapterItems 生成的 章名$local://偏移:长度 列表。 */
    public static void startLocal(Activity activity, String path, String title, @Nullable ArrayList<String> items, int position) {
        if (activity == null || items == null || items.isEmpty()) return;
        Intent intent = new Intent(activity, NovelReaderActivity.class);
        intent.putExtra(EXTRA_TITLE, string(title));
        intent.putExtra(EXTRA_KEY, "");
        intent.putExtra(EXTRA_FLAG, "");
        intent.putExtra(EXTRA_KEEP_KEY, "");
        intent.putExtra(EXTRA_VOD_NAME, string(title));
        intent.putExtra(EXTRA_VOD_PIC, "");
        intent.putExtra(EXTRA_SITE_NAME, "");
        intent.putExtra(EXTRA_LOCAL_PATH, string(path));
        intent.putStringArrayListExtra(EXTRA_ITEMS, items);
        intent.putExtra(EXTRA_INDEX, Math.max(0, Math.min(position, items.size() - 1)));
        activity.startActivity(intent);
    }

    private static String string(String text) {
        return text == null ? "" : text;
    }
}
