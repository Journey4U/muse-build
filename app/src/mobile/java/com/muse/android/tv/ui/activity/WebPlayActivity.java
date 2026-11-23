package com.muse.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.RuleConfig;
import com.muse.android.tv.api.loader.BrowserMeta;
import com.muse.android.tv.api.loader.HttpEpisodeResolver;
import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.databinding.ActivityWebPlayBinding;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.adapter.EpisodeAdapter;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.dialog.EpisodeGridDialog;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.Sniffer;
import com.muse.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页播放页（手机版）：整页 WebView 打开剧集网页，页面自己的播放器负责播放，
 * 壳子只管四件事 —— 按配置的广告域名单拦截请求、堵弹窗与外部唤起、页面加载后
 * 跑规则脚本（含 click 自动点击）、清理页面内的广告元素与弹窗
 * （见 {@link #CLEAN_JS}）。页面本身按正常浏览器显示：点片、点集都在页面里走，
 * 壳子不插手页面内的跳转。用途：
 * 对付播放带鉴权/加密、规则抠不出直链的站，
 * 以及普通源解析/嗅探失败时的兜底（播放页「网页播放」按钮）。
 *
 * <p>壳子自己的上下栏是悬浮遮罩，压在页面播放器上太抢眼：停手 3 秒自动隐藏，
 * 点一下页面唤回；返回键在栏隐藏时先唤回栏，不误退页面。顶栏唤回时不盖视频：
 * 页面整体往下让出顶栏的高度，视频完整排在顶栏下方，栏收起页面再回到全屏。
 * 页面播放器进 HTML5 全屏时强制传感器横屏（不依赖系统自动旋转开关），
 * 退出全屏恢复。</p>
 *
 * <p>入口有二（VideoActivity）：源在配置里声明 {@code "webPlay": 1} 时自动走这里；
 * 否则本集走过解析时，用户可在播放控制条手动切过来。集列表（名/标识）随行传入，
 * 页内切集时直接跳到该集的播放页（按当前页地址换集号）；多线路时
 * 顶栏可切线路（按同集位换该线路的集列表），选集用网格弹窗（竖屏 3 列、横屏 4 列）。</p>
 *
 * <p>已知取舍：没有原生播放器的进度记录、倍速、投屏 —— 页面播放器自带什么用什么。</p>
 */
public class WebPlayActivity extends BaseActivity implements EpisodeAdapter.OnClickListener {

    /**
     * 页面内广告清理脚本：广告域名单只能挡已知域名，这类站的横幅用一次性域名/IP
     * 轮换，挡不完 —— 改按结构下手（判定只认「是否外站」，不认域名名单）：
     * ① 外站链接包图片且几乎无文字 = 横幅/图标广告，摘掉并折叠其空壳容器；
     * ② 不在链接里的外站散图。站内链接（相对地址/同域）永不命中：导航、推荐位、
     * 选集都安全；播放器 iframe 与 video 一律不碰；遮罩与倒计时这类页面自身的
     * 解锁机制也不摘（摘了反而播不了）。大屏固定弹窗（带外站链接/图、不含播放器）
     * 也藏掉；图片地址是 blob: 或 data:（内嵌 base64）的 opaque 图同样按弹窗处理。页面常延迟注入广告，故装 MutationObserver 在 60 秒内持续清扫，
     * onPageCommitVisible 起跑以减少广告闪现。观察器另监听 style 属性（先藏后显的弹窗），防抖 100ms；同一播放器内多余 video 与文档底部 in-flow 横幅另有专 rule；近乎全屏的定死图片覆盖层（同站图亦然）另有专 rule。
     */
    // v119 (P0, 同步主线 v58)：isChallenge 扩为整页挑战检测——标题含「请稍候/Just a moment」或 HTML 含 challenges.cloudflare.com/cf-challenge 时整页跳过 CLEAN_JS；
    // div 循环加 isChallenge 守卫；isModal/isOpaqueModal/isBigOverlay 内部加 isChallenge 守卫。
    private static final String[] CLEAN_JS_PARTS = {
        "(function(){if(window.__museWebClean)return;try{var __museCT=(document.title||'').toLowerCase();if(__museCT.in",
        "dexOf('请稍候')>=0||__museCT.indexOf('just a moment')>=0)return;var __museCH=document.documentElement.outerHTML.s",
        "lice(0,200000).toLowerCase();if(__museCH.indexOf('challenges.cloudflare.com')>=0||__museCH.indexOf('cf-challen",
        "ge')>=0)return;}catch(e){}window.__museWebClean=1;try{var __museHost=(location.hostname||'').toLowerCase();if(",
        "__museHost.indexOf('qiyou')>=0){if(window.WebSocket){var __museWS=window.WebSocket;window.WebSocket=function(u",
        ",p){var s=String(u==null?'':u);var xh='';try{xh=new URL(s,location.href).hostname;}catch(e){}if(xh&&xh.toLower",
        "Case()!==__museHost){window.__museAdWs=(window.__museAdWs||0)+1;var d=null;try{d=p===undefined?new __museWS('w",
        "ss://127.0.0.1:9/'):new __museWS('wss://127.0.0.1:9/',p);}catch(e){d=null;}if(d){try{d.close();}catch(e){}}if(",
        "!d){d={readyState:3,close:function(){},send:function(){throw new Error('muse: ws blocked');},addEventListener:",
        "function(){},removeEventListener:function(){},dispatchEvent:function(){return false;}};}return d;}return p===u",
        "ndefined?new __museWS(s):new __museWS(s,p);};window.WebSocket.prototype=__museWS.prototype;window.WebSocket.CO",
        "NNECTING=__museWS.CONNECTING;window.WebSocket.OPEN=__museWS.OPEN;window.WebSocket.CLOSING=__museWS.CLOSING;win",
        "dow.WebSocket.CLOSED=__museWS.CLOSED;}if(window.history&&window.history.pushState){var __musePS=window.history",
        ".pushState.bind(window.history);window.history.pushState=function(s,t,u){if(arguments.length>=3){var uu=u===un",
        "defined?'':String(u);if(uu===''||uu==='#'){window.__museAdBack=(window.__museAdBack||0)+1;return;}}if(argument",
        "s.length>=3)return __musePS(s,t,u);return __musePS(s,t);};}}}catch(e){}try{var __museSt=document.createElement",
        "('style');__museSt.textContent='.ad-item,.ec-ad,.ads_w,.ads_close_btn,.adspop,.gkktXd,.ec-ad-fixed,.ads_w.slid",
        "e-wap,.ec-ad.ads_w.slide-wap,#AdBottomLeader,#AdBottomRight,#AdPopUp,#AdRectangleBanner,#AdSlot_megabanner,#Ad",
        "banner,#AdsBannerTop,#AdsBottomContainer,#Ads_google_bottom_wide,#AdsenseBottom,#Banner728x90,#ad--article-bot",
        "tom-wrapper,#ad-Superbanner,#ad-banner-1,#ad-banner-atf,#ad-banner-bottom,#ad-banner-btf,#ad-banner-desktop,#a",
        "d-banner-image,#ad-banner-placement,#ad-banner-top,#ad-banner-wrap,#ad-banner_atf-label,#ad-billboard-bottom,#",
        "ad-block-bottom,#ad-bottom,#ad-bottom-banner,#ad-bottom-fixed,#ad-bottom-right-container,#ad-bottom-wrapper,#a",
        "d-box-bottom,#ad-container-banner,#ad-container-overlay,#ad-desktop-bottom,#ad-fixed-bottom,#ad-fullbanner-btf",
        ",#ad-fullbanner-outer,#ad-large-banner-top,#ad-leaderboard-bottom,#ad-leaderboard_bottom,#ad-main-bottom,#ad-o",
        "verlay,#ad-performanceFullbanner1,#ad-popup,#ad-popup-home,#ad-popup-int,#ad-small-banner,#ad-spot-bottom,#ad-",
        "sticky-footer-container,#ad-top-banner,#ad-unit-right-bottom-160-600,#ad-unit-top-banner,#adBanner1,#adBanner3",
        "36x280,#adBannerBottom,#adBannerHeader,#adBannerSpacer,#adBannerTable,#adBannerTop,#adBlockBanner,#adBlockOver",
        "lay,#adBottom,#adModal,#adMpuBottom,#adPopup,#adSlot-leaderBottom,#adSpaceBottom,#adSuperbanner,#ad_Banner,#ad",
        "_Bottom,#ad_banner,#ad_banner_1,#ad_banner_468x60,#ad_banner_728x90,#ad_banner_bot,#ad_banner_top,#ad_banners,",
        "#ad_bottom,#ad_cont_superbanner,#ad_div_bottom,#ad_from_bottom,#ad_fullbanner,#ad_middle_bottom,#ad_overlay,#a",
        "d_overlayer,#ad_popup_background,#ad_popup_wrapper,#ad_rect_bottom,#ad_slot_bottom,#ad_sticky_wrap,#ad_superba",
        "nner,#ad_topBanner,#ad_top_banner,#ad_topbanner,#adb_bottom,#adbanner-container,#adbanner1,#adbannerbox,#adban",
        "nerdiv,#adbannerleft,#adbannerright,#adbannerwidget,#adblockbottom,#adbottom,#adbottomleft,#adbottomright,#adb",
        "ox--page_bottom_ad,#adbox-topbanner,#adfloat,#admpubottom,#admpubottom2,#adpositionbottom,#adrightbottom,#ads-",
        "banner,#ads-banner-top,#ads-bottom,#ads-right-bottom,#ads-superBanner,#ads728bottom,#adsBottom,#adsMobileFixed",
        ",#adsTopMobileFixed,#ads_banner,#ads_banner1,#ads_banner_header,#ads_bottom,#ads_box_bottom,#ads_center_banner",
        ",#ads_in_modal,#ads_layout_bottom,#ads_leaderbottom,#ads_overlay,#ads_popup,#ads_superbanner1,#ads_superbanner",
        "2,#ads_top_banner,#ads_topbanner,#adsbottom,#adsense-bottom,#adsense-container-bottom,#adsense_banner_top,#ads",
        "ense_bottom_ad,#adsense_overlay,#adsense_r_side_sticky_container,#adslot-sticky-headerbar,#adspace-bottom,#ads",
        "pot-bottom,#adunit-article-bottom,#adv-Bottom,#adv-banner,#adv-banner-r,#adv-sticky-1,#adv-sticky-2,#advBoxBot",
        "tom,#adv_BoxBottom,#adv_overlay,#adv_overlay_content,#advert-banner,#advert-banner-container,#advert-banner-wr",
        "ap,#advert-banner2,#advert-top-banner,#advertBanner,#advertOverlay,#advert_banner,#advertiseBanner,#advertisem",
        "ent-bottom,#advertisement_banner,#advertisements_bottom,#advertising-banner,#advertising__banner__content,#adv",
        "t_bottom,#adzbanner,#adzonebanner,#after_bottom_ad,#article-bottom-ad,#articleBottom-ads,#article_ad_bottom,#a",
        "rticle_banner_ad,#banner-300x250,#banner-468x60,#banner-728,#banner-728x90,#banner-ad,#banner-ad-container,#ba",
        "nner-ad-large,#banner-ads,#banner-advert,#banner-lg-ad,#banner-native-ad,#banner-skyscraper,#banner300x250,#ba",
        "nner468,#banner468x60,#banner728,#banner728x90,#banner_ad_bottom,#banner_ad_footer,#banner_ad_module,#banner_a",
        "d_placeholder,#banner_ad_top,#banner_ads,#banner_adsense,#banner_adv,#banner_advertisement,#banner_adverts,#ba",
        "nner_content_ad,#banner_sedo,#banner_slot,#banner_spacer,#banner_topad,#banner_videoad,#banner_wrapper_top,#bl",
        "ock-adsense-banner-article-bottom,#block-adsense-banner-channel-bottom,#bottom-ad,#bottom-ad-1,#bottom-ad-area",
        ",#bottom-ad-banner,#bottom-ad-container,#bottom-ad-leaderboard,#bottom-ad-slot,#bottom-ad-tray,#bottom-ad-wrap",
        "per,#bottom-ads,#bottom-ads-bar,#bottom-ads-container,#bottom-not-ads,#bottom-side-ad,#bottom-sponsor-add,#bot",
        "tom_ad,#bottom_ad_728,#bottom_ad_area,#bottom_ad_box,#bottom_ad_region,#bottom_ad_unit,#bottom_ad_wrapper,#bot",
        "tom_ads,#bottom_banner_ad,#bottom_fixed_ad_overlay,#bottom_leader_ad,#bottom_sponsor_ads,#bottom_sponsored_lin",
        "ks,#bottom_text_ad,#content_bottom_ad,#content_bottom_ads,#desktop_middle_ad_fixed,#desktop_top_ad_fixed,#dfp-",
        "ad-bottom-wrapper,#dfp-ad-floating,#dfp-banner,#dfp-banner-popup,#dfp-top-banner,#div-ad-bottom,#div-ad-top_ba",
        "nner,#div_ad_float,#dnn_ad_banner,#dsk-banner-ad-a,#dsk-banner-ad-b,#dsk-banner-ad-c,#dsk-banner-ad-d,#em_ad_s",
        "uperbanner,#fixed-ad,#floating-ad-wrapper,#floating-ads,#floating-advert,#floating_ad,#floating_ad_container,#",
        "floating_ads_bottom_textcss_container,#footer-banner-ad,#full_banner_ad,#global-banner-ad,#google-ads-bottom,#",
        "google-ads-bottom-container,#gpt-ad-banner,#gpt-ad-side-bottom,#header-ad-bottom,#header-banner-728-90,#header",
        "-banner-ad,#header-banner-ad-wrapper,#header_ad_banner,#header_bottom_ad,#home-banner-ad,#home_bottom_ad,#hori",
        "zontal-banner-ad,#index-bottom-advert,#large-bottom-leaderboard-ad,#leaderboard-bottom-ad,#leaderboard_bottom_",
        "ad,#left-bottom-ad,#leftBanner-ad,#left_float_ad,#lg-banner-ad,#long-bottom-ad-wrapper,#lower-ad-banner,#m-ban",
        "ner-bannerAd,#medrec_bottom_ad,#medrectangle_banner,#modal-ad,#mpu_banner,#overlay-ad-bg,#overlay_ad,#popup_ad",
        "_wrapper,#post-bottom-ads,#rail-bottom-ad,#redirect-ad-modal,#right_bottom_ad,#section-ad-bottom,#show-sticky-",
        "ad,#side-banner-ad,#site_bottom_ad_div,#sm-banner-ad,#sp-adv-banner-top,#sponsor_bottom,#sponsored_link_bottom",
        ",#sticky-ad,#sticky-ad-bottom,#sticky-ad-container,#sticky-ad-header,#sticky-ads,#sticky-ads-top,#sticky-custo",
        "m-ads,#sticky-footer-ad,#sticky-footer-ads,#sticky-left-ad,#sticky-rail-ad,#sticky_sidebar_ads,#story-bottom-a",
        "d,#top-ad-banner,#top-banner-ad,#top-banner-ad-browser,#topBanner-ad,#top_ad_banner,#top_banner_ads,#topbanner",
        "_ad,#topbanner_sponsor,#video_overlay_ad,#zeus_top-banner,.AdBottomPage,.Ads-sticky,.AdsBottom,.AdsBoxBottom,.",
        "AdzerkBanner,.Banner300x250,.Banner468X60,._bottom_ad_wrapper,.ac-banner-ad,.ad--banner,.ad--banner2,.ad--bott",
        "om,.ad--bottom-label,.ad--bottommpu,.ad--inArticleBanner,.ad--overlayer,.ad--sticky,.ad-728-banner,.ad-SuperBa",
        "nner,.ad-adlink-bottom,.ad-banner-2,.ad-banner-250x600,.ad-banner-300,.ad-banner-300x250,.ad-banner-5,.ad-bann",
        "er-6,.ad-banner-728x90,.ad-banner-bottom-container,.ad-banner-box,.ad-banner-btf,.ad-banner-container,.ad-bann",
        "er-content,.ad-banner-full-wrapper,.ad-banner-header,.ad-banner-image,.ad-banner-inlisting,.ad-banner-leaderbo",
        "ard,.ad-banner-placeholder,.ad-banner-single,.ad-banner-smaller,.ad-banner-static,.ad-banner-top,.ad-banner-to",
        "p-wrapper,.ad-banner-wrapper,.ad-banners,.ad-bigbanner,.ad-bigbox-fixed,.ad-block-banner-container,.ad-block-b",
        "ottom,.ad-block-sticky-ad,.ad-bottom,.ad-bottom-container,.ad-bottom-right-container,.ad-bottom728x90,.ad-bott",
        "omLeft,.ad-bottomleader,.ad-bottomline,.ad-boxbottom,.ad-boxsticky,.ad-component-fullbanner2,.ad-container-ban",
        "ner-top,.ad-container-bottom,.ad-container__sticky-wrapper,.ad-fixed,.ad-float,.ad-floating,.ad-fullbanner,.ad",
        "-fullbanner-btf-container,.ad-fullbannernohieght,.ad-home-bottom,.ad-leader-bottom,.ad-m-banner,.ad-mobile--st",
        "icky,.ad-mobile-banner,.ad-mpu-bottom,.ad-mpufixed,.ad-overlay,.ad-popup,.ad-popup-content,.ad-rectangle-banne",
        "r,.ad-relatedbottom,.ad-single-bottom,.ad-slot--mpu-banner-ad,.ad-slot--top-banner-ad,.ad-slot-banner,.ad-spac",
        "e-topbanner,.ad-sticky,.ad-sticky-banner,.ad-sticky-bottom,.ad-sticky-container,.ad-sticky-slot,.ad-sticky-wra",
        "pper,.ad-stickyhero,.ad-stickyhero--standard,.ad-stickyhero-enable-mobile,.ad-superbanner,.ad-superbanner-node",
        ",.ad-top-banner,.ad-topbanner,.ad-wide-bottom,.ad-wrapper-sticky,.ad1-float,.ad1_bottom,.ad2-float,.ad300banne",
        "r,.adArticleBanner,.adBanner300x250,.adBanner728x90,.adBlockBottom,.adFullbanner,.adHeaderAdbanner,.adInnerLef",
        "tBottom,.adMrginBottom,.adOverlay,.adRectangleBanner,.adTopFloat,.ad__sticky,.ad_banner,.ad_banner2,.ad_banner",
        "_2,.ad_banner_250x250,.ad_banner_468,.ad_banner_728,.ad_banner_728x90_inner,.ad_banner_border,.ad_banner_div,.",
        "ad_big_banner,.ad_botbanner,.ad_bottom,.ad_bottom_728,.ad_bottom_leaderboard,.ad_bottom_left,.ad_bottom_mpu,.a",
        "d_bottom_space,.ad_center_bottom,.ad_container_bottom,.ad_content_bottom,.ad_custombanner,.ad_div_banner,.ad_f",
        "loat,.ad_floating_box,.ad_footer_super_banner,.ad_leader_bottom,.ad_megabanner,.ad_middle_banner,.ad_overlays,",
        ".ad_placeholder_d_sticky,.ad_side_rectangle_banner,.ad_top_banner,.ad_wrapper_fixed,.adace-adi-popup-wrapper,.",
        "adalert-overlayer,.adbanner-300-250,.adbanner-bottom,.adbanner1,.adbannerbox,.adbannerright,.adbannertop,.adbl",
        "ock-bottom,.adblock-popup,.adblock__banner,.adborderbottom,.adbottom,.adbottomright,.adbox-topbanner,.adboxTop",
        "Banner,.adbox_bottom,.adbutler-top-banner,.adbutler_top_banner,.adform__topbanner,.adfoxly-overlay,.adframe_ba",
        "nner,.adguru-modal-popup,.adpopup,.adriverBanner,.adrotate_top_banner,.ads--bottom-spacing,.ads-articlebottom,",
        ".ads-banner,.ads-banner-bottom,.ads-banner-js,.ads-banner-middle,.ads-banner-spacing,.ads-banner-top,.ads-bann",
        "er-top-right,.ads-bing-bottom,.ads-block-bottom-wrap,.ads-bottom,.ads-bottom-block,.ads-bottom-center,.ads-bot",
        "tom-content,.ads-bottom-left,.ads-bottom-right,.ads-google-bottom,.ads-horizontal-banner,.ads-relatedbottom,.a",
        "ds-sticky,.ads-superbanner,.ads-top-fixed,.adsBanner,.adsBannerLink,.adsBottom,.adsFixed,.adsSticky,.adsTopBan",
        "ner,.ads_banner,.ads_header_bottom,.ads_top_banner,.ads_topbanner,.adsbanner1,.adsbanner2,.adsbottom,.adsbotto",
        "mbox,.adsense-float,.adsense-overlay,.adsense-sticky-slide,.adsenseBannerArea,.adsense_bottom,.adsensefloat,.a",
        "dsfloat,.adsfloatpanel,.adslot-banner,.adslot_popup,.adspace_bottom,.adtech-banner,.adv-banner,.adv-bottom,.ad",
        "v-leaderboard-banner,.adv-top-banner,.advBottom,.advBottomHome,.adv_banner,.adv_banner_hor,.advads-sticky,.adv",
        "ert--banner-wrap,.advert--sticky,.advert-article-bottom,.advert-banner,.advert-banner-container,.advert-banner",
        "-holder,.advert-bannerad,.advert-bottom,.advert-overlay,.advert-sticky-wrapper,.advert-wingbanner-left,.advert",
        "-wingbanner-right,.advert1Banner,.advertBanner,.advertBottom,.advertFullBanner,.advertSuperBanner,.advert__ful",
        "lbanner,.advert_banner,.advert_banners,.advert_main_bottom,.advertisement-banner,.advertisement-bottom,.advert",
        "isementBanner,.advertising-banner,.advertising-fixed,.advertising-top-banner,.advertisingBanner,.advertising_b",
        "anner,.advertising_bottom_box,.advertisment-banner,.advertizing-banner,.adverts--banner,.adviewDFPBanner,.advt",
        "-banner-3,.adzbanner,.after_content_banner_advert,.anzeige_banner,.article-ad-bottom,.articleBottom-ads,.artic",
        "le_ads_banner,.article_bottom-ads,.article_bottom_ad,.banner--ad,.banner-125,.banner-300,.banner-300-100,.bann",
        "er-300-250,.banner-300x250,.banner-300x600,.banner-320-100,.banner-468,.banner-468-60,.banner-468x60,.banner-7",
        "28,.banner-728x90,.banner-ad-below,.banner-ad-block,.banner-ad-bottom-fixed,.banner-ad-container,.banner-ad-co",
        "ntianer,.banner-ad-footer,.banner-ad-image,.banner-ad-inner,.banner-ad-label,.banner-ad-large,.banner-ad-pos,.",
        "banner-ad-row,.banner-ad-skeleton-box,.banner-ad-space,.banner-ad-wrap,.banner-ad-wrapper,.banner-ad2,.banner-",
        "ads-right,.banner-ads-sidebar,.banner-adsense,.banner-adv,.banner-advert,.banner-advert-wrapper,.banner-advert",
        "isement,.banner-advertising,.banner-adverts,.banner-asd__title,.banner-buysellads,.banner-img > .pbl,.banner-s",
        "ponsorship,.banner-top-ads,.banner120x600,.banner160,.banner160x600,.banner200x200,.banner300,.banner300x250,.",
        "banner336,.banner336x280,.banner350,.banner468,.banner728,.banner728-ad,.banner728-container,.banner728x90,.ba",
        "nner_160x600,.banner_240x400,.banner_250x250,.banner_300_250,.banner_300x250,.banner_300x600,.banner_468_60,.b",
        "anner_468x60,.banner_728_90,.banner_ad-728x90,.banner_ad_300x250,.banner_ad_728x90,.banner_ad_container,.banne",
        "r_ad_footer,.banner_ad_full,.banner_ad_leaderboard,.banner_ad_link,.banner_ad_wrapper,.banner_ads1,.banner_rek",
        "lam,.banner_reklam2,.banner_slot,.bannergroup-ads,.banners_ad,.block-ads-bottom,.block-advertisement-banner-bl",
        "ock,.bottom-ad,.bottom-ad--bigbox,.bottom-ad-banner,.bottom-ad-box,.bottom-ad-container,.bottom-ad-desktop,.bo",
        "ttom-ad-large,.bottom-ad-placeholder,.bottom-ad-wrapper,.bottom-ad-zone,.bottom-ad2,.bottom-ads,.bottom-ads-co",
        "ntainer,.bottom-ads-sticky,.bottom-ads-wrapper,.bottom-banner-ad,.bottom-fixed-ad,.bottom-left-ad,.bottom-mobi",
        "le-ad,.bottom-mpu-ad,.bottom-post-ad-space,.bottom-post-ads,.bottom-right-advert,.bottom-side-advertisement,.b",
        "ottom_ad,.bottom_ad_block,.bottom_ad_placeholder,.bottom_ad_responsive,.bottom_ads,.bottom_banner_ad,.bottom_b",
        "anner_advert_text,.bottom_bar_ads,.bottom_left_advert,.bottom_right_ad,.bottom_side_ad,.bottom_sponsor,.bottom",
        "_sticky_ad,.box-fixed-ads,.brn-ads-sticky-wrapper,.c-ad--adStickyContainer,.c-advert-superbanner,.c-advertisin",
        "g__banner-area,.cb-ad-banner,.chapter-bottom-ads,.container-banner-ads,.container-bottom-ad,.content-ads-botto",
        "m,.content-bottom-ad,.ct-bottom-ads,.custom-advert-banner,.custom-sticky-ad-container,.custom_banner_ad,.dac__",
        "banner__wrapper,.desktop-ad-banner,.dfp-banner,.dfp-banner-slot,.dfp_ad_content_bottom,.entry-bottom-ad,.entry",
        "-bottom-ads,.first-banner-ad,.fixed-ad-aside,.fixed-ad-bottom,.fixed-ads,.fixed-bottom-ad,.fixed-sidebar-ad,.f",
        "ixed_ad,.fixed_advert_banner,.floated-ad,.floated_right_ad,.floating-ads,.floating-advert,.follower-ad-bottom,",
        ".footer-banner-ad,.footer-banner-ads,.footer-floating-ad,.footer_bottom_ad,.fullbanner_ad,.gallery-ad-overlay,",
        ".gnm-banner-ad,.google-ads-bottom,.google-bottom-ads,.gpt-leaderboard-banner,.gpt-mpu-banner,.guide__row--fixe",
        "d-ad,.has-fixed-bottom-ad,.head-banner468,.header-ad-banner,.header-banner-ad,.header-banner-ads,.header-banne",
        "r-advertising,.home-sticky-ad,.home_ad_bottom,.homepage-banner-ad,.inline-banner-ad,.is-sticky-ad,.js-ad-banne",
        "r-container,.js-ad-loader-bottom,.js-ad-unit-bottom,.js-dfp-ad-bottom,.js-no-sticky-ad,.js-overlay_ad,.js-stic",
        "ky-ad,.l-bottom-ads,.leaderboard-ad-fixed,.leaderboard_banner_ad,.m_banner_ads,.mc_floating_ad,.mid-article-ba",
        "nner-ad,.mid_banner_ad,.mj-floating-ad-wrapper,.moads-top-banner,.mob-hero-banner-ad-wrap,.mobile-fixed-ad,.mo",
        "bile_ad_banner,.modal-ad,.mpu-top-left-banner,.news_vibrant_ads_banner,.ng-ad-banner,.o-ad-banner-top,.oio-ban",
        "ner-zone,.overlay-ad,.overlay-ad-container,.overlay-ads,.overlay-box-ad,.overlay_ad,.p-ad-dfp-banner,.page-bot",
        "tom-fixed-ads,.player-ad-overlay,.pm-ads-banner,.pm-banner-ad,.profile_ad_bottom,.region-ad-bottom-leaderboard",
        ",.region-banner-ad,.show-sticky-ad,.sidebar-banner-ad,.sidebar-bottom-ad,.sidebar-sticky--ad,.single-bottom-ad",
        "s,.single-post-bottom-ads,.single_bottom_ad,.site-bottom-ad-slot,.sponsor-bottom,.sponsor-popup,.sponsor_popup",
        ",.sponsored-container-bottom,.sticky-ad,.sticky-ad-bottom,.sticky-ad-container,.sticky-ad-footer,.sticky-ad-he",
        "ader,.sticky-ad-wrapper,.sticky-ads,.sticky-ads-container,.sticky-ads-content,.sticky-advert-widget,.sticky-bo",
        "ttom-ad,.sticky-footer-ad,.sticky-footer-ad-container,.sticky-navbar-ad-container,.sticky-rail-ad-container,.s",
        "ticky-side-ad,.sticky-sidebar-ad,.sticky-top-ad-wrap,.sticky_ad_sidebar,.sticky_ad_wrapper,.sticky_ads,.top-ad",
        "-sticky,.top-ads-bottom-bar,.top-adsense-banner,.top-banner-468,.top-banner-ad,.top-banner-ad-container,.top-b",
        "anner-ad-wrapper,.top-banner-add,.top-banner-ads,.top-banner-advert,.top-fixed-ad,.top_ad_banner,.tpd-banner-a",
        "d-container,.tpd-banner-desktop,.vi-sticky-ad,.video-ad-bottom,.vjs-ad-overlay,.vodl-ad__bigsizebanner,.wp_ban",
        "nerize_banner_box,.wpx_bannerize_banner_box,.ww_ads_banner_wrapper,.boxOverContent__banner,.RC-AD-BOX-BOTTOM,.",
        "RC-AD-TOP-BANNER,.popup-box-ads,.amp-sticky-ad-custom,.amp-sticky-ads,#mobile-swipe-banner,#banner_pos1_ddb_0,",
        "#banner_pos2_ddb_0,#banner_pos3_ddb_0,#banner_pos4_ddb_0,.taboola-banner{display:none!important}';(document.he",
        "ad||document.documentElement).appendChild(__museSt);}catch(e){}var host=location.hostname;var __museAdSet=new ",
        "Set(\"005ad66f1d.com,10bet.com,152media.com,18porn.sex,18teensex.tv,19sex.live,1adda2699c.com,1av.to,1betandgon",
        "ow.com,1gbjadpsq.de,1pop.online,1push.io,206ads.com,20tracks.com,21f5f01ad3.com,21sexturycash.com,22523e8ad1.c",
        "om,22betpartners.com,22bettracking.online,22media.world,24affiliates.com,24click.top,24x7adservice.com,27bad22",
        "e4a.com,2mdn.net,2smarttracker.com,2track.info,300daytravel.com,360ads.com,365av.net,3c1d2ad9d8.com,3d-porn.co",
        ",3mads.com,3myad.com,492tracker.net,4ad4ba0610.com,4affiliate.net,4dsbanner.net,4kporn.xxx,4pornhd.com,4rabett",
        "raff.com,4tube.com,4wnetwork.com,567bets10.com,56ad42c3a8.com,570dad652f.com,5advertise.com,62ad.com,70ad2eae1",
        "e.com,777av.cc,840withnetworks.net,888media.net,888promos.com,8c56c86ead.com,8d1uavww.xyz,8f7cd5dfad.com,8sfdl",
        "adng.xyz,900bets10.com,901radius.net,930networks.net,99ad29e111.com,99ddb6ad48.com,a-ads.com,aa783adfeb.com,aa",
        "ddcount.com,aanetwork.vn,aavvsaqmktwmu.website,aaxads.com,abadit5rckb.com,abadit5rckd.com,abateadversity.com,a",
        "bethow.com,abnad.net,aboveaverageheavenly.com,abovemyselfsadness.com,abroadsuicidaleliminated.com,absoluporn.c",
        "om,absolutionthornleader.com,academywristadroit.com,acam-2.com,acceptableredheadcaviar.com,acceptancecrowdadve",
        "rtising.com,accomplishedpersuadedprinter.com,accoybrava.com,ace-adserver.com,acebet.com,aceporntube.com,achiev",
        "ablecpmrevenue.com,achievementseeingladder.com,acmexxx.com,acrossexterminatorfoggy.com,activejavgg124.fun,ad-a",
        "dapex.io,ad-arrow.com,ad-back.net,ad-balancer.net,ad-block-offer.com,ad-cheers.com,ad-delivery.net,ad-flow.com",
        ",ad-free.info,ad-m.asia,ad-mapps.com,ad-maven.com,ad-mixr.com,ad-nex.com,ad-opt.site,ad-recommend.com,ad-score",
        ".com,ad-server.co.za,ad-srv.net,ad-stir.com,ad-tech.ru,ad-vortex.com,ad.gt,ad.guru,ad.page,ad.plus,ad.style,ad",
        "1n.net,ad20.net,ad2391c451.com,ad2adnetwork.biz,ad2bitcoin.com,ad2f8c6f8e.com,ad2the.net,ad4.com.cn,ad4989.co.",
        "kr,ad4game.com,ad62c988a3.com,ad69.com,ad6media.fr,ad999.biz,adacado.com,adactioner.com,adacts.com,adadvisor.n",
        "et,adagora.com,adahumanhear.com,adalh-zcq.com,adalliance.io,adalso.com,adamantundergone.com,adamatic.co,adandh",
        "ub.com,adangle.online,adaos-ads.net,adapex.io,adappi.co,adaptationprodigalevil.com,adaptdivinity.com,adaptedes",
        "timatedcaress.com,adaptedmercifulinterruption.com,adapterwith1024.net,adapthopperpriest.com,adaptive5000.net,a",
        "daptunemployed.com,adaround.net,adarutoad.com,adasgsts.cc,adatrix.com,adbakbhknpczi.site,adbard.net,adbasket.n",
        "et,adbeacon.com,adbestnet.com,adbetclickin.pink,adbetnetwork.com,adbfkaoxvm.in,adbit.biz,adbits.online,adblade",
        ".com,adblck.com,adblock-360.com,adblock-guru.com,adblock-one-protection.com,adblock-tester.com,adblock-zen.com",
        ",adblockanalytics.com,adblocker-sentinel.net,adblockermax.com,adblockeromega.com,adblockerprofessional.com,adb",
        "lockersentinel.com,adblockstream.com,adblockstrtape.link,adblockstrtech.link,adbmi.com,adboost.it,adbooth.com,",
        "adbox.lv,adbpage.com,adbrite.com,adbro.me,adbuddiz.com,adbuff.com,adbull.com,adbutler-fermion.com,adbutler.com",
        ",adbwublzlyxvg.online,adbyss.com,adca.st,adcalm.com,adcannyxml.com,adcash.com,adcdnx.com,adcell.com,adcell.de,",
        "adcharriot.com,adcleanerpage.com,adclerks.com,adclick.pk,adclicka.com,adclickad.com,adclickafrica.com,adclickb",
        "yte.com,adclickmedia.com,adclickppc.com,adclicks.io,adcloud.net,adcolo.com,adcolony.com,adconscious.com,adcont",
        "ext.pl,adcovery.com,adcrax.com,addefenderplus.info,addictedpledgeabridge.com,addictrelive.com,addin1.name,addi",
        "nginstancesroadmap.com,addiply.com,additionalcasualcabinet.com,additionalheritagenose.com,additionalmedia.com,",
        "additionmillionaireimpending.com,addizhi.top,addkt.com,addoer.com,addonsmash.com,addoor.net,addotnet.com,addre",
        "ssimpertinenceclockwise.com,addroid.com,addroplet.com,addthief.com,addynamo.net,adecn.com,adehhurslxxmh.space,",
        "adelement.com,adelphic.net,ademails.com,adenc.co.kr,adengage.com,adentifi.com,adenza.dev,adeoplonrprkm.site,ad",
        "espresso.com,adevppl.com,adewpxnzsnvib.site,adex.media,adexchangeclear.com,adexchangecloud.com,adexchangedirec",
        "t.com,adexchangegate.com,adexchangeguru.com,adexchangemachine.com,adexchangeprediction.com,adexchangerapid.com",
        ",adexchangetracker.com,adexcite.com,adexmedias.com,adextrem.com,adf01.net,adfgetlink.net,adfinity.pro,adfinix.",
        "com,adflyer.media,adfoc.us,adfootprints.com,adforcast.com,adforgames.com,adforge.io,adform.net,adfox.cam,adfpo",
        "int.com,adfrontiers.com,adfusion.com,adfyre.co,adgage.es,adgatemedia.com,adgear.com,adgebra.co.in,adgebra.in,a",
        "dgitize.com,adglare.net,adglidepro.com,adgoi.com,adgorithms.com,adgrid.io,adgroups.com,adgrx.com,adhash.com,ad",
        "haven.com,adhealers.com,adherebarrowdomesticated.com,adherepreserveshrimp.com,adhese.be,adhese.com,adhese.net,",
        "adhigh.net,adhitzads.com,adhoc2.net,adhouse.pro,adhub.digital,adhub.media,adhunt.net,adikteev.com,adimpact.com",
        ",adinc.co.kr,adinc.kr,adinch.com,adingo.jp,adinplay.com,adintend.com,adinterax.com,adinvigorate.com,adipolo.co",
        "m,adipolosolutions.com,adiquity.com,adireland.com,adireto.com,adisfy.com,adisn.com,adit-media.com,adition.com,",
        "aditize.com,aditms.me,aditsafeweb.com,aditude.io,adjacenthairshag.com,adjal.com,adjector.com,adjkulzooirq.com,",
        "adjoininglearning.com,adjs.media,adjug.com,adjuggler.com,adjuggler.net,adjungle.com,adjust.com,adjustmentthesi",
        "lver.com,adjux.com,adk2x.com,adkaora.space,adkernel.com,adklimages.com,adklip.com,adkmbc.com,adknowledge.com,a",
        "dkonekt.com,adkora.com,adkova.com,adlane.info,adlatch.com,adlayer.net,adlegend.com,adligature.com,adlightning.",
        "com,adlink.net,adlive.io,adlogists.com,adlook.me,adloop.co,adlooxtracking.com,adlpartner.com,adlserq.com,adlts",
        "erv.com,adlux.com,adlzmqouddtim.space,admaaodfwkcuj.store,admachina.com,adman.gr,admana.net,admanmedia.com,adm",
        "aru.com,admasters.media,admatic.com.tr,admatic.de,admax.network,admaxim.com,admcmscyyhzem.online,admd.ink,admd",
        "oqipetokp.space,adme-net.com,adme2.click,admedia.com,admediaportal.com,admediatex.net,admedit.net,admedo.com,a",
        "dmeerkat.com,admeking.com,admeridianads.com,admetricspro.com,admicro.vn,admidainsight.com,administerembodiment",
        ".com,administersurlyunbelievable.com,admirableinstance.com,admirecallpalpate.com,admiredjumper.com,admiregarri",
        "sonnetwork.com,admissibleblinkbesiege.com,admissibleconference.com,admission.net,admissiondemeanourusage.com,a",
        "dmitad-connect.com,admitad.com,admittanceskirtanthony.com,admittedhi.com,admixer.md,admixer.net,admjmp.com,adm",
        "mjpbxebrfu.space,admoai.com,admob-cn.com,admob.com,admobe.com,admolo.com,admonishconcentrate.com,admonishfistg",
        "ipsy.com,admonishmentfastest.com,admonishmenttub.com,admost.com,admothreewallent.com,admpire.com,admulti.com,a",
        "dmunvlvrcsaf.online,adnade.net,adnami.io,adnami2.io,adnanny.com,adncdn.net,adnet.biz,adnet.com,adnet.de,adnet.",
        "lt,adnet.ru,adnetworkperformance.com,adnetwrk.com,adnext.fr,adnext.pl,adngin.com,adnico.jp,adnigma.com,adnimat",
        "ion.com,adnimo.com,adnitro.pro,adnium.com,adnmore.co.kr,adnotebook.com,adnow.com,adnuntius.com,adnxs-simple.co",
        "m,adnxs.com,adnxs.net,adobe.com,adocean.pl,adodishonourcrutch.com,adokutrtb.com,adolcgailvycg.site,adomic.com,",
        "adomik.com,adonnews.com,adonweb.ru,adop.co,adoperator.com,adoperatorx.com,adopexchange.com,adopsboost.com,adop",
        "star.uk,adoptim.com,adoptum.net,adorableoil.pro,adorableold.com,adorableorchid.com,adorablequestion.pro,adorar",
        "ama.com,adorika.com,adornmadeup.com,adornmenttaunt.com,adosia.com,adotic.com,adotmob.com,adoto.net,adotone.com",
        ",adotube.com,adpacks.com,adparlor.com,adpass.co.uk,adpatrof.com,adpay.com,adpays.net,adpeepshosted.com,adperfe",
        "ct.com,adperium.com,adpicmedia.net,adpinion.com,adpionier.de,adplsr.com,adplugg.com,adplxmd.com,adpmbexo.com,a",
        "dpmbexoxvid.com,adpmbglobal.com,adpmbtf.com,adpmbtj.com,adpmbts.com,adpnut.com,adpod.in,adpone.com,adpool.bet,",
        "adpopblzuma.in,adport.io,adport.net,adpredictive.com,adpresenter.de,adprime.com,adprnvjzubygw.online,adpushup.",
        "com,adqsqmeyixjaf.online,adquake.com,adquery.io,adquire.com,adqva.com,adqxchmsttoyz.online,adreactor.com,adrea",
        "dytractions.com,adrealclick.com,adrecord.com,adrecover.com,adrecreate.com,adrenalinemanipulativeact.com,adreno",
        "vate.com,adrent.net,adresellers.com,adrevenuerescue.com,adrevolver.com,adrianpeachdesign.com,adrifttenderly.co",
        "m,adright.co,adrino.io,adrise.de,adro.co,adrocket.com,adroitham.com,adroitirresponsibleshoes.com,adroitswarmcu",
        "ltural.com,adrokt.com,adroll.com,adrta.com,adrunnr.com,ads-abc.com,ads-adv.top,ads-bitcoin.com,ads-blocker.app",
        ",ads-blocker.pro,ads-pixiv.net,ads-tracking.com,ads-twitter.com,ads.cc,ads1-adnow.com,ads2550.bid,ads2ads.net,",
        "ads3-adnow.com,ads4g.pl,ads4media.online,ads4trk.com,ads5-adnow.com,ads6-adnow.com,ads7-adnow.com,adsafeprotec",
        "ted.com,adsafety.net,adsame.com,adsappier.com,adsarcade.com,adsb4trk.com,adsbar.online,adsbetnet.com,adsblocke",
        "rsentinel.info,adsbookie.com,adsboosters.xyz,adsbreak.com,adsbtrk.com,adsbuddy.net,adscale.de,adscampaign.net,",
        "adschill.com,adscienceltd.com,adsco.re,adscoreip.com,adscout.io,adscreendirect.com,adsdk.com,adsdot.ph,adsenix",
        ".com,adsensecamp.com,adsensecustomsearchads.com,adserv.mobi,adserve.com,adserve.ph,adserverplus.com,adserverpu",
        "b.com,adservicemedia.dk,adservingfactory.com,adservon.com,adservrs.com,adsession.com,adsessionserv.com,adsexse",
        ".com,adsfac.net,adsfast.com,adsforallmedia.com,adsgateway.net,adshack.com,adshnk.com,adshopping.com,adshostnet",
        ".com,adshot.de,adsiduous.com,adsight.nl,adsinstant.com,adsinteractive.com,adsjqaobchetb.space,adskape.ru,adske",
        "eper.co.uk,adskeeper.com,adskpak.com,adslidango.com,adslivecorp.com,adsloom.com,adslop.link,adslot.com,adslots",
        ".com,adslowezlzadr.space,adsmarch.online,adsmarket.com,adsmart.hk,adsmediabox.com,adsnative.com,adsninja.ca,ad",
        "sniper.ru,adsoftware.top,adsolutely.com,adsonar.com,adsparc.net,adspdbl.com,adspector.io,adspeed.com,adspeed.n",
        "et,adspirit.de,adsplay.in,adsplex.com,adspop.me,adspredictiv.com,adspruce.com,adspygl.xyz,adspyglass.com,adsqu",
        "ash.info,adsquasher.com,adsquasher.pro,adsquirrel.ai,adsratcarp.com,adsreference.com,adsring.com,adsrv.net,ads",
        "rv.wtf,adsrv4k.com,adsrvmedia.com,adsrvr.org,adstargeting.com,adstargets.com,adstean.com,adsterra.com,adsterra",
        "server.com,adstik.click,adstock.pro,adstoo.com,adstook.com,adstub.net,adstudio.cloud,adstuna.com,adsturn.com,a",
        "dsupply.com,adsupplyads.com,adsupplyads.net,adsupplyssl.com,adsurve.com,adsvert.com,adsvolum.com,adsvolume.com",
        ",adswam.com,adswizz.com,adsxtits.com,adsxtits.pro,adsxyz.com,adt328.com,adt545.net,adt567.net,adt574.com,adt59",
        "8.com,adtags.mobi,adtaily.com,adtaily.pl,adtarget.biz,adtarget.com.tr,adtarget.market,adtdp.com,adtear.com,adt",
        "ech.de,adtechium.com,adtechjp.com,adtechus.com,adtegrity.net,adtelligent.com,adteractive.com,adtheyhaveset.org",
        ",adthrive.com,adtival.network,adtlgc.com,adtng.com,adtoma.com,adtonement.com,adtonos.com,adtoox.com,adtotal.pl",
        ",adtpix.com,adtrace.org,adtraction.com,adtrafficquality.google,adtrcdn.io,adtrhogpvnwkt.space,adtrieval.com,ad",
        "trix.com,adtrue.com,adtrue24.com,adtumxjckljej.space,adult-sex-games.com,adult.xyz,adult3dcomics.com,adultadve",
        "rtising.net,adultadworld.com,adultasianporn.com,adultcamchatfree.com,adultcamfree.com,adultcamliveweb.com,adul",
        "tdeepfakes.cam,adultfilmdatabase.com,adultforce.com,adultfriendfinder.com,adultgameexchange.com,adultimate.net",
        ",adultlinkexchange.com,adultsense.com,adultsense.net,adultsense.org,adultsjuniorfling.com,adultswim.com,adunbl",
        "ocker.com,adup-tech.com,adv-adserver.com,advally.com,advancedadblocker.pro,advancedsystemrepairpro.com,advanci",
        "ngtheirs.com,advanseads.com,advantageroughplay.com,advard.com,advcash.com,adventori.com,adventurefeeds.com,adv",
        "enturehannahmechanic.com,adventurerforewordpatio.com,adventurouscomprehendhold.com,adverge.ai,adversal.com,adv",
        "ersaldisplay.com,adversalservers.com,adverserve.net,advertica.com,adverticum.net,advertipros.com,advertise.com",
        ",advertiserubyproximity.com,advertiserurl.com,advertiseserve.com,advertisespace.com,advertising-cdn.com,advert",
        "ising365.com,advertisingiq.com,advertisoo.com,advertjunction.com,advertlets.com,advertnative.com,advertone.ru,",
        "advertserve.com,advertsource.co.uk,advertur.ru,advfeeds.com,adviad.com,adviceglossaryeminence.com,advisesaltsa",
        "rmless.com,advisorthrowbible.com,adviva.net,advmaker.ru,advmd.com,advmedialtd.com,advombat.ru,advon.net,advoti",
        "onhot.com,advozcevf.com,advp1.com,advp2.com,advp3.com,advpx.com,advpy.com,advpz.com,advservert.com,advsnx.net,",
        "advtise.net,adw9.com,adwalte.info,adway.org,adwebster.com,adworldmedia.com,adworldmedia.net,adwx6vcj.com,adx-t",
        ".com,adx.io,adx.ws,adx1.com,adxadserv.com,adxapi.online,adxawkejwjdfh.site,adxbid.info,adxfire.in,adxfire.net,",
        "adxhand1.name,adxion.com,adxnexus.com,adxpansion.com,adxpartner.com,adxplay.com,adxpose.com,adxpremium.com,adx",
        "premium.services,adxpub.com,adxscope.com,adxsource.com,adxsrver.com,adxvsipicgwop.site,adxxx.biz,adxxxxxmost.c",
        "o,adyoulike.com,adysis.com,adywrlubfplrh.site,adz2you.xyz,adzbazar.com,adzblockersentinel.net,adzcivwjgktmz.si",
        "te,adzen.ai,adzerk.net,adziff.com,adzilla.meme,adzilla1.name,adzivijnyizgp.space,adzmedia.com,adzmob.com,adzou",
        "k.com,adzpier.com,adzs.com,adzs.nl,aehjiuheifpop.online,aemediatraffic.com,afdads.com,aff-track.net,aff1xstavk",
        "a.com,affbuzzads.com,affidavitheadfirstonward.com,affiliate-b.com,affiliate-robot.com,affiliate-wg.com,affilia",
        "teboutiquenetwork.com,affiliateedge.com,affiliateer.com,affiliatefuel.com,affiliatefuture.com,affiliategroove.",
        "com,affiliatelounge.com,affiliatemembership.com,affiliates.systems,affiliatesensor.com,affiliatesleague.com,af",
        "filiatestonybet.com,affiliatewindow.com,affstrack.com,afftrack.com,afftrackr.com,afiadwcsvntpn.space,afishamed",
        "ia.net,afrikad.com,afrsportsbetting.com,afterdownload.com,afterdownloads.com,afyads.com,agavstdlunmyb.website,",
        "agendadejection.com,aggravatingchicken.pro,aggravatingoil.com,agravicsensual.cyou,agvmufccuavus.online,ahadspl",
        "y.com,aheadpains.com,ahejadicyivel.site,aim4media.com,aipmedia.com,airlessstereoadvised.com,ak-tracker.com,aka",
        "vita.com,aknadnauhsmgu.space,al-adtech.com,alarmsportsnetwork.com,alexatracker.com,alienateparadise.com,alipro",
        "mo.com,alleliteads.com,alleviatepracticableaddicted.com,allmediadesk.com,allosponsor.com,allpornovids.com,allu",
        "desexptl.cyou,almightypush.com,alpha-affiliates.com,alphaporno.com,alterassumeaggravate.com,alternads.info,alt",
        "ernatewavyrid.com,alternativecpmgate.com,alturaadvertising.com,alumniheadfirstshamrock.com,amateurcouplewebcam",
        ".com,amateurporn.net,amazing-dating.com,amazon-adsystem.com,americanacademyofthermology.org,amesgraduatel.xyz,",
        "amididsavola.com,amperwave.net,amsacpcugjc.in,amuletsourcemadden.com,amusedradish.com,anacams.com,analsexstars",
        ".com,anastasiasaffiliate.com,ancestorheadquarters.com,aneadtwrlpqob.online,anguishaddition.com,angularpoppyrob",
        "bing.com,anjavjkadvun.in,anonymousads.com,antiadblock.info,antiadblocksystems.com,antique-average.com,antonyth",
        "wartlead.com,anyadx.live,anyclip-media.com,anymedia.lv,anyporn.com,anysex.com,aorzbyyrhlavn.site,apbrumavwr.in",
        ",apex-ad.com,apophishoggism.cfd,appendad.com,appetitecamisolereawake.com,apromoweb.com,aqua-adserver.com,araby",
        "ads.com,arbattadr.in,arcadebannerexchange.org,arcadechain.com,arcadiablankdenying.com,archipelagokennelleaders",
        "hip.com,arid-leadership.pro,arkadiumhosted.com,aromadenotes.cyou,arsesexuded.cfd,arxoplagziavr.site,asaunmavgi",
        "tw.com,ascendeummedia.com,ascentlitigateunsaved.com,asdasdad.net,asgadmp.cc,asgadsts.cc,asgclick.com,asgclickp",
        "p.com,asian-xxx-videos.com,asiangfsex.com,askdomainad.com,asktiava.com,asterouhavenev.org,asthmanotchcave.com,",
        "athyimemediat.org,atube.xxx,auavaxrfgyyrv.online,audiblyacademyadaptation.com,audience2media.com,august15downl",
        "oad.com,aurescadrachen.com,auroraveil.bid,authorizedimmediate.com,auto-ads6.site,autoads.asia,automatad.com,au",
        "totrader.ca,autotrader.co.za,auyydavghsuox.site,avads.co.uk,avads.live,avalancheofnews.com,avalanchetremorunfi",
        "lled.com,avariciousnobody.pro,avatrade.io,avatraffic.com,avazu.net,avazutracking.net,avbsqhunnykee.site,avcafz",
        "oz.in,avdludlmpseoe.space,avebeentriflin.com,aveherald.com,avelpwnzhdoyx.site,avenidageranic.cyou,avenueninete",
        "enthendorsement.com,average-movie.com,average-statement.com,averinierne.cyou,avestastupendfreit.cyou,avevumywa",
        "dmrw.website,avfay.com,avfbqhwuowien.online,avfdikaldj.in,avgle.com,avgony.xyz,avhlvyyhjhvli.site,avhtaapxml.c",
        "om,avhxlgougslod.space,aviatedmidair.com,avifwlqraveqt.online,avigatejungle.click,avilionrewrote.shop,avimdxze",
        "kvlhe.space,avjecheybdtqj.space,avjlllfpqvloi.website,avkauywgzdxlx.online,avkmgwykmovxx.site,avkszbsmzamaj.si",
        "te,avlmy.com,avmakravzqfvj.space,avmmsekplixsk.space,avmviiih.com,avn.com,avnhqqqoljvgf.space,avnqsnekvmpzu.si",
        "te,avnwqayeafuve.website,avocams.com,avouchlawsrethink.com,avouchuramil.com,avoutergubbins.qpon,avowdanielungr",
        "ave.cyou,avowingoxaliteglancer.qpon,avpjihtkjsnfe.space,avplayer.com,avqjufpki.in,avqovczcoanp.com,avsfppmelmk",
        "zr.online,avswajharwhe.in,avszjedalkciq.space,avthelkp.net,avtrrdtzmnmsv.website,avvpsstgoheny.online,avxsduff",
        "diewn.space,avxskbxkmtawx.space,avylshidkaggoj.com,avypcwqqhuz.com,avytybblrvweh.space,avzaqmaoryehd.website,a",
        "vzmhurdchosj.space,avzwgvitrfmsbg.com,avzxcwkpckxyz.space,awakengoaveserasion.cyou,awmradivlamtw.online,awrsfa",
        "di.in,axabpelvjfqav.site,axiaffiliates.com,axoxktmvnhtav.site,axupfpniadauk.site,ayads.co,ayadsdyieahbn.space,",
        "ayxbetuxafa.com,azadify.com,azoogleads.com,aztadtvrog.in,b677b1ad62.com,babelmedia.global,babesinporn.com,baby",
        "boomboomads.com,backbeatmedia.com,badgegirdle.com,badgertulip.com,badjocks.com,badjojo.com,badlandlispyippee.c",
        "om,badsllcvgillm.space,badwtokg.com,bagdigravy.com,balzadicyema.com,bancadeltempoidea.org,banner-iframe.com,ba",
        "nneradsday.com,bannerbank.ru,bannerboo.com,bannerbridge.net,bannerconnect.com,bannerconnect.net,bannerdealer.c",
        "om,bannerflow.com,bannerflow.net,bannerignition.co.za,banneroctober.store,banners5html2.com,bannersmall.com,ba",
        "nnersnack.com,bannersnack.net,bannerweb.com,baseporno.com,bavaroylocker.cyou,bavbfmokxwqtn.space,bavdxafbegkvr",
        ".space,bavggygpconuq.site,bavoozimaxiev.website,bc48fa9adc.com,bcaadnibyntzm.space,bckbhadz.com,bcwhnizcbethy.",
        "website,bdavjbhjpzjmt.space,beaconadnetwork.com,beavertron.com,bebraveknob.com,becausepop.com,beead.net,begins",
        "rainbowcamomile.com,behave.com,behavepassword.com,belavoplay.com,beleavenbg.com,bellamylavatory.com,benefit-ad",
        "s.com,bentadaptation.com,beringmedia.com,bestadload.com,bestadsforyou.com,bestcontenttrade.top,bestcpmnetwork.",
        "com,bestfunnyads.com,bestonlinecasino.club,bestrevenuenetwork.com,bet3000partners.com,bet365.com,bet365affilia",
        "tes.com,betads.xyz,betafot.com,betahit.click,betaoutinnovations.net,betclever.com,beterrakionan.com,betfair.co",
        "m,betforakiea.com,betforce.io,betgorebysson.club,betmasquerainchan.com,betmenaff.com,betoga.com,betotodilea.co",
        "m,betpartners.it,betplays.com,betpupitarr.com,betr-for-tod.com,betrad.com,betrapcb.cfd,betrayalbadger.com,betr",
        "ayedjoltmethod.com,betshucklean.com,betsson.com,bettentacruela.com,betteradsystem.com,betterbonnet.pro,betterc",
        "ollective.rocks,betterdatabase.pro,betterdomino.com,bettingcurly.com,bettingoffer.xyz,bettingpartners.com,bett",
        "ingpremier.com,bettraff.com,between-legs.com,betweenbrr.com,betweendigital.com,betweenprimarily.com,betwinner1",
        ".com,betzillopartners.com,betzone2000.com,beutavpysjojf.site,bexkpopzujdus.space,bf-ad.net,bgwsktadxvgjv.websi",
        "te,bhabhiporn.pro,bhavkiyrxbifv.space,bhwgdvpavsfzw.site,biadglomdzyys.online,bicyclebusgrave.work,bidclickmed",
        "ia.com,bidderads.com,biemedia.com,bigboxads.com,biodegradablegrand.com,biqadnwzega.in,bistredchavercaption.qpo",
        "n,bit-ad.com,bitadexchange.com,bitcoadz.io,bitmedia.io,bitonclick.com,bitspush.io,bizonads-ssp.com,bizzclick.c",
        "om,bjuojadykz.com,bkgwadxzovl.com,bkjblyuukoavf.site,blacksexmatch.com,bladdersdisarmaware.com,bladderssewing.",
        "com,bladeamendable.com,bladedescendant.com,bladespillowcase.com,bladswetis.com,blamads.com,blarcamxooysi.store",
        ",blaze-media.com,blinkedlanentablelanentableunavailable.com,blinksbobblepadding.com,blismedia.com,block-ad.com",
        ",blockadsnot.com,blockchain-ads.com,blogads.com,blogherads.com,blue-ribbonmacadamizeprovide.com,bluekai.com,bl",
        "uepromocode.com,bluerunningshadow.com,bluetoad.com,blwadijbvbttx.space,bmfads.com,bmmads.com,bngmadjd.de,bngpo",
        "p.com,boffoadsfeeds.com,bonad.io,bongacams.com,bongacams10.com,bongacams11.com,bongacams7.com,bongacams8.com,b",
        "ookadil.com,bookbannershop.com,bookofsex.com,boomads.com,boostads.net,boreaddingobserve.com,boskodating.com,bo",
        "unceads.net,bovinetimingunsaddle.com,bovmkbuiaddbb.website,boxlikepavers.com,bqszugpxadzlg.store,brainlyads.co",
        "m,brave.com,bravefork.com,braverybreezebinding.com,braveryriftraining.com,bravoteens.com,bravotrk.com,bravotub",
        "e.net,brazzersnetwork.com,breadmljlfdwx.site,bridgetrack.com,brigadedelegatesandbox.com,brightadnetwork.com,br",
        "itainbetrayalbands.com,broadensilkslush.com,broadlyjukeboxunrevised.com,broadsheetspikesnick.com,broadsidedais",
        "ysanta.com,broadstreetads.com,brodownloads.site,broggleceburbetire.qpon,brothypopuli.com,bruceleadx1.com,brxql",
        "wrzcpcvx.website,bsadcmesynzyb.store,bskuxhcaduumi.online,btagmedia.com,btccasinoexpert.com,bthtcgwaniavh.webs",
        "ite,btpnav.com,btpopoogrnqvj.site,btprmnav.com,bttrack.com,btxxxnav.com,bucketsofbanners.com,budadymskizcs.onl",
        "ine,buffetaccommodating.com,bukvwavcmonjv.space,burstyflavia.com,businessclick.com,butwehaveino.com,buyadvupfo",
        "r24.com,buysellads.com,buysellads.net,buzzadexchange.com,buzzadnetwork.com,buzzonclick.com,buzzparadise.com,bw",
        "qfyjaynjpad.site,bwwuveplotadr.site,byumhwhapayad.space,byxxxporn.com,bzltavhalapzv.online,bztzpzqpltlad.site,",
        "c21media.net,c4tracking01.com,ca.ijknabd.top,cachuadirked.top,cactusheadroomscaling.com,cadencebenefit.com,cad",
        "secs.com,cadsimz.com,cadskiz.com,cadwgsofoyxky.site,calombotubelet.com,cam-content.com,cam-lolita.net,cam4.com",
        ",cam4flat.com,cam4tracking.com,camailwhickerruddles.cyou,camaligraia.cyou,cambaddies.com,cambay.tv,cambioturki",
        "sh.com,camcaps.ac,camcaps.io,camcaps.to,camcrush.com,camdough.com,camduty.com,camelusnakulabiotite.cyou,cameoe",
        "dexcuse.com,camghosts.com,camhub.cc,camiocw.com,camliveshow.xyz,cammpaign.com,camomilegentlemennotable.com,cam",
        "onade.com,camonster.com,camonster.io,camouflagemm.com,campaignlook.com,campanaoutgrin.cyou,campartner.com,camp",
        "enosine.qpon,campfirecroutondecorator.com,camph.org,camplacecash.com,camptwined.com,cams.com,cams.gratis,camsa",
        "im.com,camschat.net,camsense.com,camshq.info,camsitecash.com,camsoda.com,camsoda1.com,camspacelive.com,camspla",
        "netlive.com,camterest.com,camvideos.tv,camzap.com,candyaversion.com,candynetwork.ai,cantorspaver.com,caravanex",
        "hale.com,carbonads.com,carbonads.net,carpalsgeneuradiiamb.qpon,cartoonporn.to,casalemedia.com,cash-ads.com,cas",
        "inobonusdeals.io,casinohacksforyou.com,casinousagevacant.com,casiyouaffiliates.com,casserolerehabilitationgrad",
        "ual.com,casumoaffiliates.com,catchtheclick.com,caveletganoine.qpon,cavergalere.com,cavernsinaite.com,cavewrap.",
        "care,cavuscecilydvornik.cfd,cavvfjxabrffj.website,cavzgoqiozmyc.space,cbhadfiwcdgsl.space,ccjzuavqrh.com,cdn-a",
        "dtech.com,cdn-adtrue.com,cdn4ads.com,cdnads.com,cdymqigrkwzav.site,ceasedaggravation.com,celeb-ads.com,celebji",
        "had.com,celebjihad.live,cellaradorn.com,cerxxxwjtxebr.site,cfivfadtlr.com,cgpjpvvcadzrp.store,chabadokor.com,c",
        "hainads.io,chalazataverns.com,chandrabinduad.com,changedadroitharassment.com,charltonmedia.com,cheemsporn.com,",
        "chiatlaadovus.website,chihuahuadoorstoppond.com,chikiporn.com,chipleader.com,chpadblock.com,chymiadolors.com,c",
        "hzhqwirsxmad.online,cibleclick.com,cieapkjfmavqd.site,cinemadeck.com,circaeavamper.com,circlejavgg124.fun,cita",
        "delexampleruckus.com,citadelpathstatue.com,citizenshadowrequires.com,citrusad.com,city-ads.de,cityadspix.com,c",
        "jgimadfnuztn.online,cjlmffavyoprm.site,ckiqxrqwnavqt.website,ckwouavsimupd.space,cladupius.com,clash-media.com",
        ",clavetriple.cyou,clavuvirameses.com,clbanners16.com,cleanmediaads.com,clearadnetwork.com,clearonclick.com,cle",
        "avebullydevaluation.com,cleverads.vn,clicadu.com,click-cdn.com,click-for-preview.com,click4.pro,clickable.com,",
        "clickad.pl,clickadilla.com,clickadsource.com,clickadu.com,clickadu.io,clickadu.net,clickagy.com,clickaine.com,",
        "clickallow.net,clickbank.net,clickbet88.com,clickblitzo.com,clickbooth.com,clickboothlnk.com,clickcash.com,cli",
        "ckcdn.co,clickcertain.com,clickco.net,clickdaly.com,clickdescentchristmas.com,clickexperts.net,clickfilter.co,",
        "clickfunnels.com,clickfuse.com,clickinc.com,clickintext.com,clickintext.net,clickiocdn.com,clickmagick.com,cli",
        "ckmediahub.com,clickmetertracking.com,clickmi.net,clickmobad.net,clickmon.co.kr,clicknano.com,clicknerd.com,cl",
        "icknplay.to,clickopop1000.com,clickoutcare.io,clickoutnetwork.care,clickpapa.com,clickpoint.com,clickppcbuzz.c",
        "om,clickprotects.com,clickpunch.icu,clickpupbit.com,clicks4tc.com,clicksgear.com,clicksor.com,clicksor.net,cli",
        "ckterra.net,clickthruhost.com,clickthruserver.com,clicktimes.bid,clicktoupdservice.online,clicktraceclick.com,",
        "clicktripz.com,clicktrixredirects.com,clickupto.com,clickwhore.com,clickwinks.com,clickwork7secure.com,clickxc",
        "hange.com,clinicadentalmariangeleslazcano.com,clivetadds.com,clladss.com,clobberprocurertightwad.com,clockwise",
        "leaderfilament.com,cloudbet.com,cloudcinemaheavily.com,cloudtrack-camp.com,clrqbqtonradt.online,clusiavacuo.qp",
        "on,cmadserver.de,cmfads.com,cnptuyxjmdave.site,cocambgwyeeym.website,cockpitadministration.com,codeonclick.com",
        ",coffeavirify.com,coinad.in,coinad.media,coinads.online,coinadster.com,coinmedia.co,collectbladders.com,comedi",
        "andesired.com,comfortclick.co.uk,comicad.net,commiseratebeamingbarricade.com,commonwealthgroovemadam.com,compa",
        "ssionatespreadinquire.com,complexnetwork.pro,comradegoodsfloor.com,concealtraveller.com,conciergeradialsuperno",
        "va.com,condescendingaveragerain.com,conditioneavesdroppingbarter.com,conduit-banners.com,connectad.io,connecti",
        "ngadjusthospitality.com,conscientiouscabbageadrift.com,constantlyauthoritativegradually.com,containingstripesa",
        "dmonish.com,contemplatepushed.com,contemplatewaryheadquarter.com,content-loader.com,contentcave.co.kr,contentc",
        "lick.co.uk,contextads.live,contextuads.com,continue-download.com,contributorshaveangry.com,controlslaverystuff",
        "ing.com,conxxx.pro,cookpad-ads.com,cooladblocker.info,coolerads.com,coolestinstead.com,cooperativepop.com,coor",
        "dinatedwave.pro,coreadness.com,cosponsorgarnetmorphing.com,counciladvertising.net,coursejavgg124.fun,courthous",
        "ecadenceconsiderable.com,couveaverral.cyou,covivado.club,coxmavefzvnqwl.com,cpaclickz.com,cpalabtracking.com,c",
        "palead.com,cpcmart.com,cpcstar.com,cpm.biz,cpm20.com,cpmadvisors.com,cpmbugaboo.com,cpmclktrk.online,cpmleader",
        ".com,cpmmedia.net,cpmprofitablenetwork.com,cpmrevenuegate.com,cpmroi.com,cpmspace.com,cpmstar.com,cpmterra.com",
        ",cpmtree.com,cpuzspopetlsg.space,cpvadvertise.com,cq.jqygnbfq.top,cqrksadeerzsp.space,cqvxrmwcwyvad.site,craal",
        "edcamistutu.qpon,cradaygo.com,crakbanner.com,crakmedia.com,crampleadspace.com,craniavestas.com,crannyadministe",
        "rvolunteer.com,cravesgage.cyou,crazyporn.xxx,crbetfcijuhtw.site,creativeformatsnetwork.com,crihbeppscadw.site,",
        "crispads.com,criteo.com,criteo.net,crockadilla.com,crocotube.com,crowderleavens.cyou,crowdsubsequentadmire.com",
        ",crpdadadgogyh.site,crtracklink.com,cruhtravbsuio.space,crwdcntrl.net,crypto-ads.net,cryptobeneluxbanner.care,",
        "cryptocoinsad.com,css-load.com,cstwpush.com,csxbezadpeazu.site,ctebiooadmfty.space,ctm-media.com,cuisineenvoya",
        "dvertise.com,cuoieadrraxle.space,cupis-lady-dreampartner.com,cursecrookadvantage.com,customads.co,cvyadqizofrs",
        "w.space,cylindrical-wave.com,czgacpadsspuj.space,czhavjzsxkcwa.space,czhjjavbyhuun.online,czuadkhcudkl.com,czu",
        "oszwava.in,d58ad0184a.com,da-ads.com,daadpefptsdqv.online,dacqfadozkvie.site,dadaejghzcnaq.site,dadmivxze.in,d",
        "adqcuxqdnecx.space,dads-banging-teens.com,dadsats.com,dadsimz.com,dadsoks.com,daftporn.com,dailyporn.club,daka",
        "fxgnqcpmm.website,dallavel.com,damagedbehaved.com,dantaradiohd.net,dargopastinadogsled.cyou,dark-reader.com,da",
        "rknessporn.com,date4sex.pro,datedeskads.com,datetrackservice.com,dating-amour-club.com,dating-honey-girls.com,",
        "dating-honeygirles.com,dating-masters-haven.com,dating-service.net,dating2cloud.org,datingadvicefree.com,datin",
        "gamateurs.com,datingero.com,datinggold.com,datinghoneygirls.com,datingmeetnet.com,datingprudethimble.com,datin",
        "gshall.life,datingsspads.com,datingstyle.top,datingtoday.top,datingtopgirls.com,datingtorrid.top,datingwithmee",
        ".net,datoporn.com,davincisgold.com,davipkybnzfji.site,davtwymjlx.com,davynetriumf.com,dbdhnavwbvhep.website,db",
        "occfzxlavyp.online,dcraddock.uk,dcssptrack.com,ddownload.com,de2bdad928.com,dead-put.com,deadelegantannotation",
        ".com,deadlinescreechvisit.com,deadlyfeasible.com,decknetwork.net,decorateadverb.com,dedating.online,dedicatedm",
        "edia.com,dedicatednetworks.com,deductgreedyheadroom.com,deeavouter.com,deepxtube.com,deltabravo.net,demandingo",
        "verdriveunthread.com,demdex.net,deofleadyetca.org,dependantcarefullyheavenly.com,dependentadequatemyth.com,dep",
        "leteheadphoneshoe.com,deployads.com,depriverippledecadence.com,depwwdntgavyc.website,despoteavesdropblazing.co",
        "m,detectbravecomfy.com,developermedia.com,dextralravi.cyou,dgafgadsgkjg.top,dggazqmcpmtwc.website,dgzykxavrvek",
        "j.site,dhovqdnulcadz.site,digipathmedia.com,digitaladvertisingalliance.org,digitalmediapp.com,digitalmediaworl",
        "d.tv,digitalpush.org,diningfreshmansavage.com,dinsbetrlsiqc.space,dinzfafad.in,directaclick.com,directadvert.r",
        "u,directcpmfwr.com,directnavbt.com,disableadblock.com,discoveredcowardlymadden.com,discretioningratitudeadvise",
        "dly.com,disgustingmad.com,disneyadvertising.com,disparagewardenbetrayal.com,displaycontentnetwork.com,displayn",
        "etworkcontent.com,displaynetworkprofit.com,disqusads.com,distractiontradingamass.com,dixyporn.com,djncmafidfcp",
        "m.online,dmzadtwonmlfn.online,dnavexch.com,dobbyporn.com,dollyspreadingbeer.com,domainadvertising.com,domestic",
        "atednavigationgrip.com,domipush.com,doodlesunshinecamp.com,dopaleads.com,doseofporn.com,doubleadserve.com,doub",
        "leclick-cn.net,doubleclick.com,doubleclick.net,down-paradise.com,download-file.org,download-performance.com,do",
        "wnloadboutique.com,downloaddecidedly.com,downloadoperagx.com,downloadyt.com,downstairspoppy.com,dpfwqzjkavjmb.",
        "site,dporn.com,dqhepocpcwspj.space,dradvice.in,dragstergibletshaded.com,draviteinosine.com,dreadedrevisableves",
        "t.com,dreadfullyemulateconservation.com,dreadfullynormallyinsist.com,dreadfulrulingextension.com,dreamteamaffi",
        "liates.com,droghvadutsov.space,dropdeadgems.org,dropkickmedia.com,droughtpavidsoths.cyou,drsmediaexchange.com,",
        "drtuber.com,dsadghrthysdfadwr3sdffsdaghedsa2gf.xyz,dtadnetwork.com,dtyamiadjnapm.space,dtzvavrnbzemd.space,duc",
        "hesspleasurelollipop.com,dulvxkusxdcpc.online,dunderaffiliates.com,dunhilltraveldeals.com,dupsavour.com,duregq",
        "gfvravp.space,duxlpoavm.in,dvwrcpmeeozgq.site,dvzycytrxbxav.space,dwlladiupirrc.site,dymqtojvadily.site,dynad.",
        "net,dz4ad.com,dziorhnav.in,dzubavstal.com,e7e0addf05.com,ea-tube.com,eadeuifduvres.site,eadsrv.com,eadziukwqze",
        "eb.space,earatikvavrsa.site,earbossysavvy.com,earringmavin.cyou,easy-ads.com,easy-dating.org,easyad.com,easyad",
        "sapp.com,easyporn.xxx,eavakddcdofik.site,eavesdropbogusye.com,eazyleads.com,ebannertraffic.com,ebayadservices.",
        "com,ebetoni.com,ebfclhdkkmeav.store,eboglvqdtadmz.space,ecknbonfiftad.website,eclick.vn,ecxufxavldnpd.site,ed4",
        "9f13aad.com,edcuaqnhioavg.com,editorovertimeradiation.com,edshookherhead.org,edzusegqavqik.store,eekjdpreeghad",
        ".com,effectivecpmcontent.com,effectivecpmgate.com,effectivecpmnetwork.com,effectivecpmrate.com,effectivegatecp",
        "m.com,effectiveratecpm.com,eforebuthehadsta.org,efrwdjavjkiqw.online,egadvertising.com,egotisticexcavateplywoo",
        "d.com,egyihjgwfzadc.site,ehaveagoldenbed.org,ehszycpcxrskg.website,ejadhobthckao.site,ekvhgfruqyadw.site,elado",
        "ve.com,elasticad.net,elatedcamera.com,eleavers.com,elephant-ads.com,elliottwave.com,ellipticaltrack.com,elntlr",
        "utxdavp.online,elogiado.info,elsespeakingbetraying.com,elutessheavednude.cyou,embraveanderun.com,emediate.dk,e",
        "mergencyadditions.com,eminentcleaveproduces.com,emjmeadlsjrho.online,emqyssutavqxc.online,enbpnlaiatvav.space,",
        "encoresweaved.com,engineladleworn.com,enporn.org,entertainenslave.com,eociugytvzhad.website,eoievavdtucqs.onli",
        "ne,eonsmedia.com,epbrwsksexzem.site,ephesiansavvyinto.com,epicclicks.net,epicgameads.com,epidermispushupvariab",
        "le.com,eporner.com,epxniaadddb.com,eqads.com,eradisciplinemomentary.com,ergonomicparadeupstroke.com,erhlyvjhet",
        "cad.online,ero-advertising.com,eroadvertising.com,erotikdating.com,errandchavish.cfd,eruptstarapinbetrail.cfd,",
        "erwknxujxxxbh.space,esawbynaavjmi.online,escalatenetwork.com,eskarsunleavesmokier.cyou,essayads.com,estrelabet",
        "partners.com,ethereumads.com,ethicalads.io,etjrkgaqrkfad.space,eudgjelqtadcq.website,euro4ads.de,euros4click.d",
        "e,evadereprimand.com,evadesickness.com,evasiuywadnox.space,everesttech.net,everydayporn.co,evmdamxavzsgk.websi",
        "te,evolvemediallc.com,ewesmedia.com,exactshaver.shop,excavatedefeatedshudder.com,excavatenearbywand.com,exchan",
        "ge4media.com,excitead.com,exjzuoukavbnv.space,exnesstrack.com,exoclick.com,exoticads.com,explorads.com,extensi",
        "ons-media.com,extensivetravelled.com,externalfavlink.com,extinguishbethpasta.com,extqoqykkcpmo.space,extremesc",
        "atporn.com,eyeofporn.com,eyeshadowkeynote.com,eyeviewads.com,eyqsrrwmvhavp.website,ezadblocker.com,ezdownloadp",
        "ro.info,ezvthdrknbfad.space,f30fpad7.xyz,f85fd721ad.com,fa85cadfb0.com,faceporn.com,fadegranted.com,fadexplosi",
        "on.com,fadppvjumphyh.online,fadsipz.com,fadskis.com,fadslimz.com,faithfullyreadiness.com,fallinmatches-paradis",
        "es.com,fam-ad.com,familyporner.com,fancytravelingrid.com,fantasticbet.com,fapnado.com,fapnado.xxx,fastclick.ne",
        "t,fastnativead.com,fastwavehilltop.com,fattenspopulum.com,favigirningnonly.cfd,favmaqyqgcirx.space,favorite-mi",
        "ne.com,favoriteinside.pro,favosevergery.cfd,favousshakha.cfd,favusraband.com,favy.cam,fbetlixgee.eu,fbmedia-ck",
        "l.com,fcqrzagadhlvn.online,fdadmvbteroqf.space,feadrope.net,feckfulmisaver.com,feed-ads.com,feedbackadmiralfor",
        "tnight.com,feedyourheadmag.com,fellowearnwave.com,fenacheaverage.com,ferntravelleddeduct.com,festaporno.com,fe",
        "sttube.com,feudalbetrayalcast.com,ffnetbeteeguu.site,fgvowyavmmcau.site,fiddlestructureparadise.com,file-uploa",
        "d.net,filestube.com,filingcarnivorousexclaim.com,financeads.net,financialfadinglobster.com,financialgobetweend",
        "ancer.com,findbetterresults.com,findcouponspromos.com,finddatinglocally.com,fiorewonyhade.online,fireworkadser",
        "vices.com,fireworkadservices1.com,fireworkswad.com,firstclass-download.com,firstload.de,fishermanwithavenge.co",
        "m,fixavcha.in,fixionmedia.com,fkdeavpxkjuoa.space,flagads.net,flairadscpc.com,flashadtools.com,flat-ads.com,fl",
        "aviar.com,flavidwarwolf.cfd,flavoryjehus.com,flirtclick-matches.com,flower-ads.com,fluavildemist.com,fluemenav",
        "id.qpon,fluxads.com,flyads1.com,flyingadvert.com,flymyads.com,fmsads.com,footballtradedirectory.com,footztube.",
        "com,forbesresistread.com,foremedia.net,forestysavory.qpon,forfrogadiertor.com,forgavedisciplinetolerance.com,f",
        "orgivenbeavervague.com,forkconnectinghead.com,foxpush.io,fpadserver.com,fqadcwyr.com,fractionsadly.com,fractur",
        "ebroadcastsaw.com,freakads.com,free-datings.com,freeadblockerbrowser.com,freeadultcomix.com,freecamsexposed.co",
        "m,freedomadnetwork.com,freefromads.com,freefromads.pro,freelivesex.tv,freelygreatestscammer.com,freeonlinegayp",
        "orn.com,freepopnews.skin,freeporn8.com,freepornxxx.mobi,freepublicporn.com,freesexcam365.com,freeuseporn.com,f",
        "reewebcams.com,freshforestroad.com,freshpops.net,frivol-ads.com,frnaqbeavtdvm.website,fryhzcadnypnn.online,fs-",
        "loader.com,fuck-dating.com,fuckbookdating.com,fufrbtjgadkncr.com,funnedravage.com,furiousexpansion.com,fusetra",
        "cking.com,fusionads.net,fusterquaver.com,futureads.io,fxavdiifzfjbw.website,fxgzkkroeavcj.space,fxrokhvydhadu.",
        "website,fxxavsxmuovyd.website,fyauuswqwavou.website,fytbadthewihr.store,gacurad9559.net,gadgetfortunatelysunny",
        ".com,gadgets360.com,gadlt.nl,gadmagckfy.com,gadnsohyud.com,gadsabs.com,gadsatz.com,gadslimz.com,gadspms.com,ga",
        "dspmz.com,gadssystems.com,galaxypush.com,galleries-pornstar.com,gambadudman.com,game-clicks.com,gameads.io,gam",
        "er-network.net,gamersad.com,gaming-adult.com,gamingadlt.com,gamingadult.com,gandrad.org,gapwadfcmeilz.space,ga",
        "solineimproveabroad.com,gatetotrustednetwork.com,gaveastound.com,gavedeliverknee.com,gavnuifk.xyz,gavotsallele",
        "u.com,gay4porn.com,gayadnetwork.com,gayadpros.com,gayporntube.com,gazeflavour.com,gbads.net,gbcamahjbcsff.site",
        ",gcpcvtdsdztbz.website,gcpczbniuuuoo.site,gdsavcfiitlxs.online,gearcambridge.com,geckad.com,geeunlessarcade.co",
        "m,genesismedia.com,geniad.net,geniusonclick.com,geoaddicted.net,georgianacaravanscoop.com,germaniavid.com,getf",
        "lowads.net,getjad.io,getmyads.com,getpopunder.com,gettubetv.com,gfadvpljfwagd.online,gfesbjnadvxrb.online,ggbe",
        "t-online.net,ggbetapk.com,ggbetpromo.com,ghepxcvmadltj.space,giantaffiliates.com,gibedencave.cfd,giftornado.co",
        "m,girls-romancehaven.com,gitads.dev,gjkrwpafad.com,gkvfndkdzavrv.online,glade-static.online,gladlydotselfevide",
        "nt.com,gladlythattoady.com,glaoxxxppcvwz.site,glkadcvafpqhe.online,global-adblocker.com,globaladblocker.com,gl",
        "obaladblocker.net,globaladmedia.com,globaladmedia.net,globaladsales.com,globaladv.net,globtube.com,gloporn.com",
        ",glsfreeads.com,gmads.net,gmmads.com,gmpavcpqcdhxu.space,gnawpush.com,gnopfoaddfxto.website,gnxnfkdgadbkf.onli",
        "ne,go-g3t-push.net,go2bdmbet.com,go2media.org,goads.pro,goadserver.com,goclicknext.com,godaddy.com,godlesscour",
        "ieradmirable.com,gofdtdxdtzkav.site,good-ads-online.com,goodadvert.ru,googleads-cn.com,googleadservices-cn.com",
        ",googleadservices.com,googleadsserving.cn,googlesyndication.com,googlevads-cn.com,gotibetho.pro,gotrackier.com",
        ",gotrackier.io,gourmetads.com,gpsecureads.com,gqavlsyfgmkeb.online,graciamediaweb.com,gradualtelevision.com,gr",
        "aduateamazingly.com,graduateangeles.com,graduatedfreezing.com,graduatedoutside.com,graduationpaltryherbal.com,",
        "gradwcntoprvn.online,granddadfindsponderous.com,grasshoppergainfulexcavator.com,gravegrandmother.com,gravelsem",
        "esterflourish.com,gravelspeorianstime.cyou,gravelyjovialelemental.com,gravenesswomanlesszestfully.com,gravenpi",
        "cked.com,graveuniversalapologies.com,gravicjibbs.com,gravitationtaxpayer.com,greenadblocker.com,greenads.org,g",
        "regalemedials.qpon,grievecamouflage.com,grimytradition.pro,gripdownload.co,gripping-bread.com,groovinads.com,g",
        "ross-ladder.pro,grvmedia.com,guacosravaka.com,guadscfwaweae.site,gunzblazingpromo.com,gupload.xyz,guvrwavupliw",
        ".in,gvcaffiliates.com,gwalaissexruj.site,gwdkavydmyuf.com,gwhbladbytxd.com,gyoqtcogunavk.site,h12-media.com,ha",
        "adqzvaencuq.online,hadronid.net,hadsanz.com,hadsecz.com,hadsokz.com,haffnetworkmm.com,hairdressergraduate.com,",
        "halogennetwork.com,harborjavgg124.fun,hardbritlads.com,harrenmedianetwork.com,hasaeblmtcadr.website,hattedrave",
        "d.com,hauledquaverguardian.com,haveameet.com,havefiliate.com,havelberme.qpon,havenclick.com,havenyl.xyz,haverd",
        "entata.cfd,havetohave.com,havingunderwear.com,havpcbrpaftjv.online,havstpqgejqbw.site,hazelstrictlyheadless.co",
        "m,hcjzvzvavb.com,hddrfyoifiuad.website,hdkbxadnbxvua.online,hdopxenavimwu.online,hdporn24.org,hdpornfree.xxx,h",
        "dpornmax.com,hdpornphotos.com,hdtube.porn,hduolaveybzmo.site,headacheaim.com,headbidder.net,headerbidding.ai,h",
        "eaderlift.com,headlinesplus.com,headphonedilution.com,headquartersfallclassify.com,healthtrader.com,heardspade",
        "dime.com,heateradvisoraplenty.com,heathertravelledpast.com,heathsleavenlinaloe.cfd,heavenfull.com,heavengabbyk",
        "atcina.cyou,heavenly-holiday.com,heavenmedia.com,heavenomitzoom.com,heavenspecificallygait.com,heavenstyler.cy",
        "ou,heavepacepallor.com,heavilyaflareknifes.qpon,heimalesssinpad.com,heldsadness.com,heliumads.com,hellporno.co",
        "m,helpfulavenue.com,herexxxtube.com,hetadinh.com,hetlmedia.com,hfblxccnycadt.website,hhbetevelthyg.site,hicpm1",
        "0.com,highcpmcreativeformat.com,highcpmgate.com,highcpmrevenuegate.com,highcpmrevenuenetwork.com,highperforman",
        "cecpm.com,highperformancecpmgate.com,highperformancecpmnetwork.com,highprofitnetwork.com,highratecpm.com,highr",
        "evenuecpm.com,highrevenuecpmnetwork.com,highrevenuenetwork.com,highwaycpmrevenue.com,hilltopads.com,hilltopads",
        ".net,hiltonbett.com,himediads.com,hiprofitnetworks.com,hitcpm.com,hityyzyxbadim.online,hjvewzbtkbetn.online,hm",
        "adkixrebk.in,hmdypadvcolcw.space,hnbanvxtkavun.site,hnntube.com,hntixkyrwyiav.website,hnzjwtcajadzi.space,hoad",
        "aphagoar.net,hoasvvtyavmbn.store,hockeyhavoc.com,hodlwvavzwt.com,hodnjqyavomuj.site,hollow-population.pro,holy",
        "familyradio.org,home-xxx-videos.com,homejavlive.com,homemade.xxx,honestbroadcasting.com,honestinfuriatemedia.c",
        "om,hooliganmedia.com,hopcleftirradiate.com,horse-racing-affiliate-program.co.uk,hostadsmarkets.com,hostave.net",
        ",hostave2.net,hostave3.net,hostave4.net,hot-bbw-tube.com,hot-sex-tube.com,hotadultvids.com,housejomadkc.com,ho",
        "utoucavus.cfd,hpadjptcyyafh.space,hpdipavuplwoe.website,hqporn.su,hqpornstream.com,hraayzvvjlead.site,hrlvadtk",
        "fqils.space,hrrlwaavrttks.online,hryaavcnugcpp.site,hsefhnuodtav.com,html-load.cc,html-load.com,huavdntfzhprm.",
        "online,hueads.com,hueadsxml.com,huluads.info,hunterlead.com,hurlmedia.design,hustlereadcoffeehouse.com,hvwoeav",
        "jjemhq.site,hwepopmhhsz.com,hwpnocpctu.com,hyenadata.com,hyhavaflmeguk.site,hyperbanner.net,hyperpromote.com,i",
        "adbsntna.com,iadnsamqheemb.space,iaeaadfleusbk.site,iajlxlnkkocav.space,ianoubcdoavip.website,iasbetaffiliates",
        ".com,iavlxljzdclmz.space,iavszerhxigkh.space,ibannerexchange.com,ibrapush.com,iceporn.com,iclickcdn.com,icypor",
        "no.com,idealmedia.io,idevaffiliate.com,idhoyoadekuzf.website,idreammedia.com,ieecfjcwadvrz.online,ieladhfhoicl",
        "v.online,ifadnfhgaozlo.store,igoraodadkwlk.space,iiasyavgjqwat.site,iionads.com,ijiyhavkjzvi.com,ijpopvca.com,",
        "ikqyazeadzqvo.space,ileuadlrdnpow.space,ilxilmadwxfah.space,imageadvantage.net,imbuemadeup.com,imediaaudiences",
        ".com,img-load.com,imgadult.com,imgkdetaddxre.space,imiclick.org,immediateequivalentstunning.com,immediatelydog",
        "head.com,impact-ad.jp,impactradius-go.com,impactradius.com,imperfect-dead.pro,imprasavariac.com,impressexaltsc",
        "ulptor.com,imyanmarads.com,in-appadvertising.com,in-page-push.com,in-page-push.net,inadequatebuoythin.com,inad",
        "equateconsolation.com,inadmissibleeyewitness.com,indiadesire.com,indianbannerexchange.com,indieclick.com,indig",
        "estiontreatmentgraduated.com,indlyment-stuador.com,indofad.com,indoleads.com,indyadvertising.com,inexpedientla",
        "ds.com,infectiousmedia.com,infinite-ads.com,infinityads.com,influads.com,infobetting.com,informedcleave.com,in",
        "heritedgeneralrailroad.com,inhousead.net,initiallybrigadebookstore.com,inkingabave.cfd,inktad.com,inliersilica",
        "mvalyl.cyou,inpage-push.net,inpagepush.com,inporn.com,inscribenaiveavoid.com,insightadz.com,insightspendadvanc",
        "ement.com,insitepromotion.com,inskinad.com,inskinmedia.com,instantbannercreator.com,instantpaydaynetwork.com,i",
        "nsteadempty.com,insteadjell.com,insteadmodification.com,instinctradiatorrook.com,insurads.com,intellibanners.c",
        "om,intelligenceadx.com,intellipopup.com,interactiveads.ai,interadv.net,interclick.com,intermediaryquay.com,int",
        "ermediategillsevent.com,internationalsexguide.nl,interworksmedia.co.kr,intimidatingsideway.com,intopicmedia.co",
        "m,intporn.com,intrapromotion.com,intravert.co,invadedisheartentrail.com,invadermonks.com,inviziads.com,inxxx.c",
        "om,ioadserve.com,iohgttbethmaj.online,iohsoavvddvmo.store,iomaduexiqtho.space,iononetravoy.com,ip-adress.com,i",
        "pornia.com,ipromote.com,ipvhqdchweavs.site,irbnydavspxxt.space,irfgithdpoadi.site,ironendeavour.com,irontube.n",
        "et,irradiatestartle.com,iseawave.com,itsmillionlead.com,iuzvusqotkhad.online,ivstracker.net,iyqtpifqeoavq.site",
        ",iyrrtosjcravm.online,izavjtqqznucm.site,jaavnacsdw.com,jacaminfley.com,jacamitympana.qpon,jackpotave.com,jack",
        "potcitycasino.com,jadcenter.com,jaded-actor.com,jaded-wing.com,jadedsingle.com,jadedtwist.com,jadehlbswdlqw.on",
        "line,jadeinvitelawsuit.com,jadjkuofzwad.com,jadkuwwtyp.in,jads.cc,jads.co,jadwidal.com,jagisjbavpwdj.space,jam",
        "anetwork.com,jangonetwork.com,jarvispopsu.com,jav-bukkake.net,jav.sb,java8.xyz,javascriptcdnlive.com,javbucks.",
        "com,javdock.com,javenglish.net,javfor.tv,javgg.eu,javgg.me,javhd-trk.com,javhd.com,javhdporn.live,javhub.net,j",
        "avideo.net,javkagiitfkuw.site,javlibrary.com,javmg.com,javmix.tv,javmost.com,javpornclub.com,javpwyyvcoorg.web",
        "site,javslon.com,javxxx.me,javynow.com,javyvkzwwsf.com,jbckwavvvsugv.space,jdfhwxboadwvg.online,jdrfaqinvrcpm.",
        "space,jeetyetmedia.com,jegiopad.top,jegjuitgvavuz.space,jespwdvojaavj.online,jetavbemsmnur.space,jewellerybroa",
        "dlyisabella.com,jewishadgroup.com,jewishcontentnetwork.com,jfoazkavnozuo.online,jghadikzlvvnr.space,jiawavgaks",
        "dbq.store,jicamasmiscrop.qpon,jilioadvrvpwm.site,jitterendeavour.com,jjqlzrxadgi.com,jmadyknjxonua.website,jmk",
        "ladoihjrsa.space,jnqbkpsymoads.site,jolrfeqxyavvj.site,jovialcadence.com,joxaviri.com,jpadxrydoiorz.online,jpa",
        "vgtzkbjpwi.site,jpbphraoemadf.online,jpkwoyveladnt.online,jradumbwgmggr.space,jsadapi.com,jsfeedadsget.com,jti",
        "avpaxso.in,jtxlzsuxadaxe.site,jubileebread.com,jubnaadserve.com,juddersexcusedligure.qpon,juiceadv.com,juicyad",
        "s.com,juicyads.me,jumboaffiliates.com,junbi-tracker.com,junkyadexchange.com,junmediadclikrmdi.com,justfullporn",
        ".net,justtube.net,jwrtoravx.com,jxavjloeqgrbz.space,jxcuocswkavpi.site,jxvhcvhdeaadf.site,jyjioxtadobac.websit",
        "e,jyusupfxadswy.site,jzhbethwfhnlq.space,kaadqpoorrhsy.site,kadam.ru,kadime.cc,kadmkyie.com,kagocvncavfqi.stor",
        "e,kappavowal.com,kavanga.ru,kavblcvs.com,kavcapfpjbhpn.space,kavekduhupvky.space,kavtqkbsyrpzt.site,kawai-jav.",
        "com,kbcjugyneadmk.online,kbwfirbugcpmk.site,kcpcmdepbvyjq.website,kdlgpcradvcmi.space,kebbyizzyavicula.cyou,kf",
        "gxuiyutbwav.space,kfpuejiakadbw.site,khiclickrkafw.online,kidsinstancesfavor.com,kinkadservercdn.com,kiomthavs",
        "ruuk.online,kjetdxtcammhc.com,kjkavoimtxart.online,kjmrldarxadmw.online,kkczadzgl.com,kllastroad.com,klvadvvrb",
        "usee.website,kneestolerableadulthood.com,kniravkrmejus.online,knwjmjujhadoo.site,koalaglade.com,kohadfzjolyfy.",
        "website,konraddiscrimination.com,konradissuesimperial.com,konradsheriff.com,konradterriblesect.com,kotqbebetyo",
        "eed.com,kpopsingers.com,krudavmbgnwrc.website,krzkasckoqvad.site,ktiebyesqavuz.online,kudtapgad.in,kunvertads.",
        "com,kvcmgvhnbadzq.online,kvjthavimymgk.online,kvsadman.com,kvylquisaduxr.store,kwgavfflgjpop.site,kwintravetoe",
        "r.cyou,kxgadtlettueg.website,kzzbdsattavdw.space,labadena.com,labisexecute.com,ladbrokesaffiliates.com.au,lade",
        "ykyxsaosi.site,ladgritperformed.com,ladhlbetxnjlm.space,ladiesmatchesfinder.com,ladsabs.com,ladsans.com,ladsec",
        "z.com,ladsips.com,ladsipz.com,ladskiz.com,ladyboygoo.com,ladysabryna-xxiqlkt.work,lalapush.com,lampshadescreen",
        ".com,landscaperedressave.com,lanxtkacpucpc.site,laptopprivacyado.com,lavatorydownybasket.com,lavatoryhitschool",
        "master.com,lavender64369.com,lavenderzenith.com,lavishboot.com,lavnkios.in,layer-ad.org,layingframingcomrade.c",
        "om,lblscpmfxuzqq.space,lbyarvladuyzy.space,lcpavtrwvavnq.website,ldqkyedvfepop.online,leadadvert.info,leadfaml",
        "y.com,leadgainz.com,leadimmensely.com,leading-back.com,leadmediapartners.com,leadnote.me,leadsecnow.com,leadsl",
        "eap.net,leadzu.com,leaveundo.com,leavingaileen.com,leavingneedyengaged.com,leithstaving.cfd,lemonadds.com,leon",
        "betvouum.com,leopardfavorite.com,leplvcbadrmgb.space,letsporn.com,lfavcwgydohba.store,lfstmedia.com,lgsmartad.",
        "com,lhkmedia.in,lhvavsdsnhxgz.website,liadm.com,libertystmedia.com,lifeporn.net,lifepromo.biz,lightmeadowwalk.",
        "com,likeads.com,likelihoodcheerleaderdefender.com,likexxxtube.com,limetreeloadherself.com,linkadvdirect.com,li",
        "nkonclick.com,linksex.club,linuxtracker.org,litavvcc.in,literallycircumscribetome.com,litterarenadisembroildis",
        "embroil.com,liveadexchanger.com,liveadoptimizer.com,livecam.com,livecams.com,livepromotools.com,livesexasian.c",
        "om,livesexbar.com,livesportmedia.eu,liveuniversenetwork.com,livexxx.me,livyncxvleiad.website,lkadhbifkzvtt.onl",
        "ine,lkqagavhhaqrp.space,lkseavjkzupfw.space,llqmbetbgjhyt.website,lmavfuplmvhbq.space,lmueckvzozqav.website,lo",
        "ad-res-static.lol,load-static-res.xyz,loadedbummer.com,loadercdn.com,loadingscripts.com,loadtime.org,lobefinti",
        "nklecleaved.cyou,loboclick.com,localedgemedia.com,locksadvancedrelease.com,lodgeastronautadore.com,logsaddicti",
        "on.com,lokiporn.com,loohaiturigavik.net,loppetickierbavoso.cyou,lotadversaryvolatile.com,lotongmamonadowses.qp",
        "on,lotritereavows.cfd,lotteryaffiliates.com,loupesstavedtriene.cyou,love-banner.com,love4porn.com,lovecam.com.",
        "br,loveflirt-haven.com,lpmediastorage.com,lrvpquoavttir.site,lscsexsnjacwr.site,ltnavwfpsffpi.space,lucidmedia",
        ".com,luckyads.pro,luckyforbet.com,luggedzabeta.com,lumbersavians.cyou,lunacyumpireunleave.cfd,lunatictoxicambi",
        "guity.com,lunlyqvavjkfc.website,lustytube.net,luxadv.com,luxupadva.com,lvwytobyemady.online,lwadm.com,lwgadm.c",
        "om,lwuntajpwadku.online,lwxmapxavophf.online,lxadqdidnsnzd.online,lxoukuwkzvada.online,lyconery-readset.com,lz",
        "udmwytadqrg.site,m2track.co,mad-consist.com,madadsmedia.com,madamconducted.com,madcheddar.net,madcpms.com,madd",
        "enwiped.com,madeupice.com,madeupregular.pro,madeupteamspupil.com,madeupvigourpoll.com,madmanbiological.com,mad",
        "nesscoupon.com,madqwcbhobeca.space,madratesforall.com,madrogueindulge.com,madsabs.com,madsans.com,madsecs.com,",
        "madserving.com,madslimz.com,madsone.com,madspmz.com,madurird.com,mafroad.com,magicads.nl,magnetisemedia.com,ma",
        "ideneyeshadow.com,mainadcenter.com,mainadv.com,majorads.site,maleficscamper.qpon,malisonrepope.com,mangadex.or",
        "g,mangadistrict.com,mangarawad.biz,mangoads.net,manipulativeslaves.com,manorflounderheady.com,mantisadnetwork.",
        "com,manureads.com,manysex.com,manysex.tube,mappilaaverrer.com,marimedia.com,marinadelifestyle.com,marinadeworr",
        "iesdurable.com,marketjavgg124.fun,markswebcams.com,marmaladestrikingcolourless.com,marrergraves.com,marsads.co",
        "m,martpleadescended.com,masqueradeegoismcurtain.com,masqueradejournalism.com,masqueradepatheticburden.com,math",
        "ads.com,mathtag.com,maturexxx.vip,mavq.net,mavrtracktor.com,maxiadv.com,maxonclick.com,maxvaluead.com,mbidadm.",
        "com,mbsspads.com,mcbadljmbhdni.online,mcepadnkovcy.in,mdroadmmbxrlj.space,me4track.com,meadow-assets.click,mea",
        "dow-static.xyz,media-412.com,media-amazon.com,media-click.ru,media-general.com,media-sapiens.com,media.net,med",
        "ia6degrees.com,mediaalpha.com,mediacandy.ai,mediacpm.pl,mediad2.jp,mediaf.media,mediaffiliation.com,mediaforce",
        ".com,mediaforge.com,mediafuse.com,mediahell.world,mediamixer.co.kr,mediapalmtree.com,mediaplex.com,mediasama.c",
        "om,mediatarget.com,mediatemple.net,mediative.ca,mediative.com,mediatradecraft.com,mediatrias.com,mediaupdate.c",
        "o.za,mediavine.com,mediaxchange.co,medleyads.com,medyanetads.com,meetradar.com,meetsexygirls.org,megaad.nz,meg",
        "adeliveryn.com,megaffiliates.com,megatube.xxx,mepuisgsxadha.site,mercenaryslaveryplays.com,mergedlava.com,mess",
        "yadvance.com,metavertising.com,mfadsrvr.com,mfeibqadbbzaz.site,mfyavsmiha.com,mgladthereisso.com,mgtckhufqjlad",
        ".store,mharavczumra.in,microad.jp,microad.net,microadinc.com,midas-network.com,midnighttraditionally.com,midpo",
        "pedge.com,migrationoctavian.com,mihmadovjkpi.in,mileporn.com,milftube.mobi,millennialmedia.com,mimosaavior.top",
        ",minklemonade.com,minutemedia-prebid.com,minutemediaservices.com,miraclethisaddicted.com,mixadvert.com,mk-ads.",
        "com,mkadujfywhxis.website,mkatakavcaxm.in,mktbqkzdzadrg.site,mladedda.net,mlnadvertising.com,mmadsgadget.com,m",
        "mdcokadaklfg.website,mnetads.com,mng-ads.com,moatads.com,mobileoffers-dld-download.com,mobiletracking.ru,mobip",
        "romote.com,mochicadermis.cyou,modelsgonebad.com,modulepush.com,mojoaffiliates.com,momentummedia.com.au,monadpl",
        "ug.com,monetixads.com,monthsexpressive.com,moonads.net,moonrocketaffiliates.com,mootermedia.com,mortypush.com,",
        "motaflavine.com,motherlyglad.com,moustacheoverloadwar.com,movad.de,movad.net,movcpm.com,moviead55.ru,moviemedi",
        "ahub.com,moviesex.org,mpanythathaveresultet.info,mpocadpbnevwa.site,mpsuadv.ru,mqdownload.com,mqedledjjjdad.we",
        "bsite,mqqhfyfavftyk.com,mrgrekeroad.com,mrporngeek.com,msads.net,msgpbwbfradny.website,mtdutavlkpzzz.site,mtwa",
        "taveihajz.space,muggurscamden.click,multiwall-ads.shop,muniadazzled.qpon,mutenessdollyheadlong.com,mvmrcygtoad",
        "gz.online,mxhkadwedflsr.online,mxpopad.com,my-promo7.com,myadcash.com,myadultimpressions.com,myaffiliates.com,",
        "myaudioads.com,myavlive.com,mybestcpm.com,mybetterdatings.com,mybrowseraddon.com,mycams.com,mycasinoaccounts.c",
        "om,mydatingstheladys-club.com,mydatingswomanonlines.com,mydatinguniverse.com,myeasetrack.com,myemailtracking.c",
        "om,mykocam.com,mypopads.com,myprecisionads.com,myreadingmanga.info,mysexgames.com,mysexjourney.com,mysticalado",
        "ptionocean.com,mythad.com,mywebclick.net,mywovcjadrtcb.online,mzafanowhdcpc.space,nababroadbirch.com,nadese.xy",
        "z,nads.io,nadwkwzfjnxzw.site,naiadexports.com,nailadversary.com,naivewidespreadbody.com,nameads.com,nanoadexch",
        "ange.com,narrowveneeringsavoury.com,nasbhavuovwzi.space,nasssmedia.com,nativclick.com,native-adserver.com,nati",
        "veadmatch.com,nativeads.com,nativeadsfeed.com,navaidaosmic.top,navajospoule.com,navalrememberedcarve.com,navie",
        "mytotgbg.online,navigatingnautical.xyz,navtlkdihvagi.online,navwmaakbo.com,navxhefspincz.space,navyhalfclinica",
        "l.com,navyrecentuproot.com,navyrecognition.com,navzkokpufm.in,nawpush.com,naxadrug.com,nbadgnaqqqrxx.website,n",
        "bdysfxadsfji.website,ndfhqtezbkavl.online,ndhadbeensoa.org,ndqpopziznuuq.space,neads.delivery,neatmadeiraairbo",
        "rne.com,necessarilymosquitoheadphones.com,neglectsquad.com,neladyhe.com,neptuntrack.com,netavenir.com,netcpms.",
        "com,netmedia.hu,netsolads.com,networkad.net,networkhubcontrol.com,networking444.net,networkmanag.com,networks1",
        "000.net,neudesicmediagroup.com,newjulads.com,newmayads.com,newoctads.com,newormedia.com,newosnotadprx.space,ne",
        "ws-headlines.co,newsadsppush.com,newsatads.com,newscadence.com,newsmaxfeednetwork.com,newsunads.com,newtab-med",
        "ia.com,newthuads.com,newtueads.com,nextclick.pl,nextmillmedia.com,nexxxt.biz,ngadverts.com,nglmedia.com,ngunme",
        "umeklav.online,nguswfavipgqm.website,ngvyktsuuyadu.online,nhatsadwafljs.site,nhbavbqogc.com,nhiaoadcolahl.webs",
        "ite,nhzylwcpmcchb.space,nikkiexxxads.com,nkyavipvkfxgo.online,noaaduliugvul.online,noavoahevugrib.com,nobblesp",
        "ush.cyou,nomadsbrand.com,nomadsfit.com,noncommittalsupposedlybroadcast.com,nonsenseaggravation.com,noosebangha",
        "d.com,norfolkbroads.com,northdownload.com,notifpushnext.net,novadune.com,novemberadventures.name,novibet.partn",
        "ers,nowfavourable.com,npcad.com,nrowumdcamr.in,nsfwadds.com,nsmartad.com,ntepadkozezji.online,ntkgkyalavlcs.sp",
        "ace,ntvpwpush.com,nutentertainintermediate.com,nutterguava.cyou,nvfigpopnczvp.online,nwnxyivttoaad.site,nxxxpm",
        "yejmudz.space,nylons-porno.com,nylonsporno.com,nythingamglad.com,nzavmqooblmpl.site,nzme-ads.co.nz,nznoxxx.com",
        ",nzwecadkryhup.site,oadehibut.xyz,oagbadcuwzruq.site,oavgofyxwyryj.online,oavqowdsenguy.online,oavsgdmtbealk.w",
        "ebsite,oavurognaurd.net,oavyctdvbrlpz.space,obliquescallionexcavator.com,oboxads.com,obsoletepaddlevehicular.c",
        "om,obstinacysexist.com,ocadnxsghpseo.space,ocoopdcpmqxpy.site,octopuspop.com,odnwadzre.in,oeubadryzvwyk.space,",
        "ofbdaktxxxldn.space,ofcamerupta.com,offergate-games-download1.com,offerstrack.net,ogavjmydvhvqe.com,oglalavood",
        "oo.world,ogzavfebqsdmy.online,ohlladamnak.in,ohmycams.com,oilcampshift.qpon,oirdwcadrzrqj.online,oivpkegadjuqc",
        ".site,oizfzrjavugyq.space,ojtuqqiohqadx.online,ojxtxmaivsavq.site,okadgpkptxakv.website,okrqmavp.com,oktavvavg",
        ".in,olayomad.com,oldgamesdownload.com,omegaadblock.net,omegadblocker.com,omni-ads.com,omtrdc.net,onad.eu,onals",
        "killsexkce.org,onclckpop.com,onclickads.net,onclickalgo.com,onclickclear.com,onclickgenius.com,onclickmax.com,",
        "onclickmega.com,onclickperformance.com,onclickprediction.com,onclickpredictiv.com,onclickpulse.com,onclicksupe",
        "r.com,onclicktop.com,onhercam.com,online-adnetwork.com,onlineporno.fun,onlinepromogift.com,onlinepromousa.com,",
        "onlyalad.net,onnetwork.tv,onsetblouselampshade.com,ontnkqwlnimav.online,onxgaladrj.in,ooadrhsqiofhv.space,oohk",
        "badeeaaem.site,ooozxxadrsnpo.space,oouutjpavsxfv.site,openadserving.com,openadultdirectory.com,openrtb-banner.",
        "com,openx.net,oplmbxdyndadi.website,opptmzpops.com,opsqbrqhqcamb.online,optad360.io,optad360.net,optiads.org,o",
        "ptidownloader.com,oqmiadfkmlcwz.website,orangeads.fr,orangeclickmedia.com,orfrsexlxozvc.site,orgyxxxhub.com,or",
        "igintube.com,orqlzuoqgad.com,osahsadctgzfa.site,osamadada.com,oscineabettal.cfd,oshacampelis.com,osiaffiliate.",
        "com,ospreymedialp.com,otaavlxnmqwyi.online,otoadom.com,ottadvisors.com,ottavvrdokror.website,ouiiadyzaieqx.spa",
        "ce,ourtecads.com,outbrain.com,outbrain.net,outrageoussquad.com,outrightphiladelphia.com,outstandingfrailabroad",
        ".com,overloadenjoy.com,overnightabroadcraftsman.com,ovgadhtcyvak.in,ovpniyavjvob.com,oxado.com,oxtracking.com,",
        "oxzmasxuadysh.space,ozadzoddxxxqs.online,ozonemedia.com,p-advg.com,packlenseavesdrop.com,paddleweekendnudge.co",
        "m,padgntohmxigt.site,padgzdbyelrca.online,padinventoryvile.com,padsabs.com,padsans.com,padsatz.com,padsims.com",
        ",padsimz.com,padskis.com,padslims.com,padspms.com,pakbanners.com,pantrycornyunload.com,pantyhosepornstars.com,",
        "papayads.net,paraderascaldefinitely.com,paradise8.com,paradizeconstruction.com,paradocs.ru,parlouroutlayfavor.",
        "com,partner-ads.com,partycasino.com,pas-rahav.com,patesihobbet.com,patwyavweuxzx.space,paulgravitynumeral.com,",
        "pavagedisawa.cfd,paviliongiddy.com,paviortummels.com,pavljbnheqajx.space,pavramzdvprsi.online,paxmedia.net,pay",
        "-click.ru,payloadz.com,payvclick.com,pblcpush.com,pboadsgyalldd.site,pc-ads.com,pcpcg47ycvwxzyuq8fkwu.cfd,pcyq",
        "advwrqsmc.online,peacefulmeadowwalk.com,peadeplete.com,peafzfaghradr.com,peakclick.com,peavyegeria.cyou,pebadi",
        "petto.cfd,pebblemedia.be,pedxbqnadnebr.space,peltishpushful.com,pennynetwork.com,pepperjamnetwork.com,perfectt",
        "oolmedia.com,performanceadexchange.com,performanceonclick.com,performancetrustednetwork.com,perimeteravertendl",
        "ess.com,perimeterravenousdelusional.com,perkcanada.com,perkyabroad.pro,peugyiwmgvadr.space,peukasrsihavele.org",
        ",pgammedia.com,pgmediaserve.com,pgzmbkdavgilh.online,phmtcfsfjtcpm.space,phrfushpadbco.website,phuakravmo.in,p",
        "ilotstracking.com,pip-pip-pop.com,pipaffiliates.com,pitdjtfxcamyx.site,pityneedsdads.com,pixellitomedia.com,pj",
        "yibsavpisod.space,plajwaduivmqg.space,planetwin365affiliate.com,playrainbet.com,pleasedmad.com,pleasetrack.com",
        ",plenomedia.com,pllsadrp.net,plowmenepoptic.com,ployimmediately.com,plucheavennel.com,plungeflavour.com,pnspih",
        "rxaduyk.site,poavoabe.net,poetizesuaver.cfd,pointclicktrack.com,polishleadennineteen.com,polisnetwork.io,pollu",
        "xnetwork.com,polyad.net,poorlyadjustvisual.com,pop5sjhspear.com,popads.media,popads.net,popander.com,popblocke",
        "rgold.com,popblockergold.info,popbounty.com,popbutler.com,popcash.net,popcornvod.com,popcpm.com,popdemission.c",
        "om,popedomwresterfingian.qpon,poperblocker.com,popexhale.com,popgoldblocker.info,pophandler.net,popiemarsala.c",
        "fd,popinacelektrameered.cyou,popjanuary.shop,poplardealtcarrying.com,poplartenth.com,popmansion.com,popmog.com",
        ",popmonetizer.com,popmonetizer.net,popmyads.com,poppastabour.com,popped.biz,poppyrimpace.com,poppysol.com,popr",
        "tb.com,popsads.net,popteen.pro,popular-tea.com,popularslant.com,populationeverydaygale.com,populumframmit.com,",
        "popunder.bid,popunderjs.com,popunderoctober.store,popunderstar.com,popunderz.com,popupblockergold.com,popupblo",
        "ckermax.app,popupclick.ru,popupdomination.com,popupgoldblocker.net,popupsblocker.org,popviking.com,popxperts.c",
        "om,popxyz.com,porchadvertise.com,porn-star.com,porn.com,porn.fr,porn300.com,porn99.net,pornalin.com,pornattitu",
        "de.com,pornav.net,pornconversions.com,porndeals.com,porndig.com,porndoe.com,porndroids.com,porndude.link,porne",
        "arn.com,pornerbros.com,pornforrelax.com,porngals4.com,porngames.adult,porngames.tv,porngamespass.com,pornglee.",
        "com,porngo.tube,porngray.com,pornhat.com,pornhb.me,pornicom.com,pornid.xxx,pornimg.xyz,pornintellect.com,pornj",
        ".com,pornjam.com,pornjv.com,pornkings.com,pornktube.tv,pornleep.com,pornmix.org,porno666.com,pornoegg.com,porn",
        "ogramxxx.com,pornohans.net,pornoheat.com,pornone.com,pornpapa.com,pornpics.com,pornpics.de,pornpoppy.com,pornr",
        "abbit.com,pornsex.rocks,pornstargold.com,porntez.com,porntrack.com,porntry.com,pornv.xxx,pornve.com,pornvids.f",
        "r,pornxbox.com,pornxp.com,pornxp.net,pornyhd.com,positivenetwork.pro,postaffiliatepro.com,powerad.ai,poweradbl",
        "ocker.com,powferads.com,ppuqlcyadikqe.website,pqnnvegqkyadt.space,preciousadequate.com,precisionclick.com,prec",
        "onyklavernbombax.cfd,predestinationicebergpredicament.com,predictivadnetwork.com,predictivadvertising.com,pred",
        "ominantleadingsprout.com,prefixbesidesbetray.com,premiumads.net,presscllad.com,presselhavedartoic.cfd,prestige",
        "casino.com,pretrackings.com,prettyroad.biz,prettytube.net,preventtributebroad.com,prickly-tradition.com,prideh",
        "ickintimidating.com,primaryads.com,prizeadultery.com,prmtracking.com,pro-adblocker.com,pro-advert.de,proadscdn",
        ".com,processedlizardadjoining.com,producedpigsforehead.com,profitablecpmgate.com,profitablecpmrate.com,profita",
        "blecpmratenetwork.com,profitabledisplaynetwork.com,profitablegatecpm.com,profitableratecpmnetwork.com,profitab",
        "letrustednetwork.com,programmesheadstoneconsult.com,projectjav.com,promo-bc.com,promo-reklama.ru,promo4partner",
        "s.com,promobenef.com,promocionesweb.com,promokrot.com,promoteconditionerkeyboard.com,promotools.biz,promowebst",
        "ar.com,propadsviews.com,propeller-tracking.com,propellerads.com,propellerads.tech,propellerclick.com,propeller",
        "pops.com,propositionvowelladen.com,proprietordauntaddition.com,prospershave.com,protectioncleave.com,proudradi",
        "sh.com,prplad.com,prplads.com,prpops.com,prtrackings.com,prwave.info,pryzmads.com,pseavibrsijgx.site,psjadixqz",
        "liqg.online,psxzadkhsaevp.space,psychologylosttraveling.com,ptwebcams.com,pubadx.one,pubfuture-ad.com,pubjzoxa",
        "v.com,publicidad.net,publpush.com,pubmatic.com,puffnetwork.com,puinnjsexnlbo.space,pulpyads.com,pulseadnetwork",
        ".com,pulseonclick.com,pumavobis.cyou,pumpbead.com,punctuationbandycontradict.com,puporn.com,pureadexchange.com",
        ",purpleads.io,push-notifications.top,push-sdk.com,push-sdk.net,push-sense.com,push.house,push1000.com,push1000",
        ".top,push1001.com,push1005.com,pushabl.com,pushadvert.bid,pushaffiliate.net,pushatomic.com,pushbizapi.com,push",
        "campaign.club,pushcentric.com,pushclk.com,pushdom.co,pushdomains.biz,pushelp.pro,pushflow.net,pushflow.org,pus",
        "himg.com,pushingtiling.com,pushinpage.com,pushking.net,pushlapush.com,pushmaster-in.xyz,pushmenews.com,pushmin",
        "e.com,pushmobilenews.com,pushnami.com,pushnews.org,pushno.com,pushnotice.xyz,pushpong.net,pushprofit.net,pushr",
        "ase.com,pushserve.xyz,pushtorm.net,pushub.net,pushwelcome.com,pushy.ai,pushyimmaculateinestimable.com,pvdnugjw",
        "yrkad.website,pvhlewexcpcqq.online,pvuznrtadztxu.space,pxlcrykadtsby.site,pxuxlswadjlfu.space,pydsexwwnwiuj.sp",
        "ace,pzmbetafgealw.online,q1media.com,qaeadnlcctjvh.online,qbiavgzkhyqji.space,qbkofavetnrgr.site,qcxavdkqorwri",
        ".space,qdemrnocqeadj.site,qetsjadxfjtzq.online,qgxbluhsgad.com,qhbetzsrzzslg.site,qhngmav.com,qjwmihzmadpsm.sp",
        "ace,qkosavpevkjzc.site,qoclick.com,qoqqgkzavy.com,qpadmalav.in,qpnicavmjtytk.site,qpnippagdkadi.online,qqdrbmd",
        "bcavgm.space,qsgjcqiqhaiad.online,qtnobmxbgavob.space,quackgraduation.com,quadrinhoseroticos.net,qualiclicks.c",
        "om,qualitydating.top,quanta-wave.com,quantserve.com,quantumadblocker.com,quantumads.com,quickads.net,quickload",
        "binpack.click,quilladot.org,qunskbadgzcdx.site,quwtzyadstt.com,qwxolavxykodb.site,qxdownload.com,qxgloyadavqvf",
        ".space,qxpxrbettkskb.space,qyeavpkdevzzc.site,qyexjolwpv6wvgbet5wp.cfd,rabbetget.cfd,rabbitporno.com,racebets.",
        "com,radarconsultation.com,radcffbpaqwyr.space,radiancelongitude.com,radiancerelaysacred.com,radiancethedevice.",
        "com,radiantack.com,radiantmedia.net,radiantspacecraftbell.com,radiantvalerelieve.com,radiodarn.com,radioonline",
        "hd.net,radioreference.com,radiusfellowship.com,radiusix.net,radiusmarketing.com,ragfishcleptavgases.cfd,ragged",
        "twinheadmaster.com,rakutenadvertising.io,ramppopulateresponse.com,rapidadexchange.com,ratedeaddisk.com,rationa",
        "lizeinadequatecomedian.com,rationalizeoverwhelmendeavour.com,raveigryeggfj.website,ravenfarm.tech,ravenousamus",
        "e.com,ravktufmpgula.site,ravm.tv,rbpksavddouil.store,rcamagqknhmx.com,rcygxjvmjadds.site,rdrbunaadhpyn.site,re",
        "activebetting.com,readiong.net,readme.ru,readpeak.com,readserv.com,readycelebrationleaf.com,realcfadsblog.com,",
        "realclick.co.kr,realgfporn.com,realxavounow.com,reasonable-bet.com,recamnjggepku.site,receiptcadenceresurrecti",
        "on.com,recipeaddingavenue.com,recipientpopulationregret.com,reclaimvanillademure.com,recordedheavinessexperien",
        "ce.com,rectadesc.cfd,recyclehorridleading.com,redads.com,rediads.com,redirect-ads.com,redpineapplemedia.com,re",
        "dtube.com,redtube.fm,reedsoutpop.com,refbanners.com,refbanners.website,referenceswarmerbread.com,regionads.ru,",
        "regionalavouchcreeping.com,regularseatradio.com,rehearsejuniorloaded.com,reliablebanners.com,rememberineffecti",
        "veadvisedly.com,remetalthecladayment.cfd,rencontreadultere.club,rentfavourablecashmere.com,resacaduende.com,re",
        "sentlocallycamomile.com,resentmentsadflew.com,resteddiabetepocket.com,restorepushed.com,retaingravely.com,reve",
        "alingplacingpopped.com,revenuecpmgate.com,revenuecpmnetwork.com,revenuenetwork.com,revenuenetworkcpm.com,revil",
        "lusiveheavily.com,revimedia.com,revive-adserver.net,revopush.com,revupads.com,rewardsaffiliates.com,rexadvert.",
        "xyz,rexpush.club,rfwmfadnncajl.site,rhinoaffiliates.com,rhombusads.com,rhubarbambassadorweep.com,richads.com,r",
        "ichwebmedia.com,rideradjective.com,ridgepilotavalanche.com,risingshadewhite.com,rivinadinomic.qpon,rmqgscbitoa",
        "dp.site,rmyhibtadohj.com,roadcontagion.com,roadwaspfang.com,robeheadlong.com,rocketmedia24.com,rockmostbet.com",
        ",rocoads.com,rodesquad.com,rogersmedia.com,rokymedia.com,rollbackavenge.com,roobet.com,roobetaffiliates.com,ro",
        "otzaffiliates.com,rotatingadult.com,roxyaffiliates.com,rpyztjadsbonh.store,rqerokcafoadv.space,rtb-media.me,rt",
        "bpop.com,rtbreachads.com,rteomxavwvnol.site,rubanners.com,rubiconproject.com,rummyaffiliates.com,ruscams.com,r",
        "yscxxxelrsdw.space,rytadlqujhwsz.site,rzedmincpjcam.website,s-adzone.com,sabavision.com,saczvfpaeadxv.space,sa",
        "dbaguette.com,sadbasindinner.com,saduvqpcfykcj.online,sadvvowejqfet.site,safelinktracker.com,sagaciousexceptin",
        "grate.com,sagsajijqpavd.space,saladattic.com,salbraddrepilly.com,salvador24.com,samsungads.com,sandracismabove",
        "average.com,sandwichconscientiousroadside.com,saravanacyetic.qpon,satisfactorybroadformidable.com,savageavowmi",
        "dland.com,savamea.org,save-editor.com,savefiles.com,saveit.us,saveterracehangnail.com,saveunstable.com,savines",
        "yohimbe.cfd,savinist.com,saviourcrimpycrimpyhem.com,savourbiggergait.com,savttuegxztst.site,saymedia.com,sbaff",
        "iliates.com,sbicrwatvnavf.online,sbsbetlinfevn.site,scamblefeedman.com,scamforgottenbyte.com,scammerfail.com,s",
        "cammerrecorder.com,scarlet-clicks.info,scatxxxporn.com,scholarshipexplosivepopular.com,sciouspavlovpundum.qpon",
        ",scorchads.com,scorecardresearch.com,scowpoppanasals.com,scqcigoladk.com,sdadarotfnhoy.website,sdk4push.com,sd",
        "omxqcaeuadm.site,sdtzadpayrgsc.space,seafoodclickwaited.com,seaofads.com,searchschoolsnetwork.com,searingcavel",
        "et.rest,seatrackingdomain.com,secondhandbrave.pro,secretbehindporn.com,secthatlead.com,secureaddisplay.com,sec",
        "ureleadsforever.com,secureleadsrn.com,sedatingnews.com,seizureblade.com,selectad.com,semperinshave.rest,sendwe",
        "bpush.com,sensible-ads.com,sensitiveclick.com,seriolavangloe.com,servedby-buysellads.com,servedbyadbutler.com,",
        "servemeads.com,server4ads.com,set-push.net,setravieso.com,setupad.com,setupad.net,sewboldlyavow.com,sex-and-fl",
        "irt.com,sex-chat.me,sex-techniques-and-positions.com,sex3.com,sexad.net,sexcelebrity.net,sexclic.com,sexcoolda",
        "ting.com,sexdatecash.com,sexei.net,sexemulator.com,sexfamilysim.net,sexfg.com,sexfinder.com,sexflirtbook.com,s",
        "exgangsters.com,sexiba.com,sexier.com,sexintheuk.com,sexlist.com,sexmature.fun,sexmoney.com,sexmotors.com,sexm",
        "y.site,sexpin.net,sexplaycam.com,sexsearch.com,sexstories69.com,sextadate.net,sextf.com,sextracker.com,sextube",
        "box.com,sextubepromo.com,sextubeweb.com,sextvx.com,sexuhot.com,sexvertise.com,sexvid.pro,sexvid.xxx,sexy-ch.co",
        "m,sexycams.com,sexypornvideo.net,sf-ads.io,shadowybalance.com,shadysetting.pro,sharemedia.rs,sharktube.net,sha",
        "uladubhe.com,shauladubhe.top,shaversoutjumphobnobs.cyou,shavesfoilermissus.cfd,shavingwitchcraft.com,shipmentd",
        "oorwaydecade.com,shoogloonetwork.com,shopmonthtravel.com,short-wave.info,showcasead.com,showmeyouradsnow.com,s",
        "hubadubadlskjfkf.com,siavtcwmgehxp.online,sibrdiarmuvav.site,sierrakonradreorganize.com,siftoctaveuntimed.qpon",
        ",signaturewoundedgraduation.com,silesiadeuces.com,sillorangesladen.com,silverleafroad.com,simple-affiliate.com",
        ",sinadolcoralla.cfd,sincerelydiscreetcomedian.com,sindonpavis.cyou,singlesexdates.com,sizednavahoduncify.cyou,",
        "sjadkbbvcmdfd.website,sjobmfxcchbad.online,sjzieadynfqtl.online,skeetads.com,skidwaverorigin.com,skipvideoads.",
        "com,skirlsravager.com,skyadsmart.com,skymedia.co.uk,skynetworkcdn.com,slaveguna.cfd,slavesthy.com,slavingaint.",
        "com,slippersexamination.com,slthavjtoxjoi.site,smac-ad.com,smadex.com,smart1adserver.com,smartadserver.com,sma",
        "rtadtags.com,smartclick.net,smartcpatrack.com,smartyads.com,smashpops.com,smentbrads.info,smwadnuydogrh.space,",
        "snack-media.com,snaeyjnavvojo.online,snathsextends.com,snbets.com,sneakyadministration.com,snipersex.com,snlaa",
        "vecjmpja.online,socalledpushedcollege.com,socialsex.biz,socialsex.com,socialsexnetwork.net,sofinpushpile.com,s",
        "oftforestmeadow.com,softonicads.com,softpopads.com,soldierreproduceadmiration.com,solicitorvalvemade.com,soloc",
        "pm.com,solutionsadultes.com,somehowluxuriousreader.com,sophiaaccoladewest.com,sorbetbungler.com,sordidsickness",
        "faded.com,sousefulhead.com,soxnhocmadklc.site,spadeharassrefusal.com,spaderonium.com,spadsync.com,spanpromo-li",
        "nk.com,sparrowaboveaveragedefence.com,sparta-tracking.xyz,special-promotions.online,specificmedia.com,speckenc",
        "ampment.com,speechcleopatraadmiral.com,speedingdeadlyplays.com,speedycomediandecember.com,sphinxtube.com,spmed",
        "iacdn.com,spokeoaffiliates.com,sponsorinserttimeout.com,sponsorpay.com,sponsorshipprickly.com,sportradar.com,s",
        "portradarserving.com,spotidownloader.com,sppopups.com,spreadingsinew.com,spreadpreferencetelevision.com,sprint",
        "rade.com,spry-glad.pro,srvupads.com,srzwbetxwdkpt.online,sseadlhhrusrj.website,ssqcqaavrncxt.online,stackadapt",
        ".com,stagepopkek.com,staggeredravehospitality.com,star-advertising.com,star-clicks.com,star-theladyfinds.com,s",
        "targamesaffiliate.com,starmobmedia.com,startwebpromo.com,statcamp.net,statsforads.com,staturebanner.com,staviz",
        "okhbhe.in,steadyglobe.com,steadyhelmet.com,sterilecontradiction.com,stickyadstv.com,stiffenshave.com,stimtavy.",
        "net,stinkyloadeddoctor.com,stop-adblocker.info,stopads-now.com,stopadzblock.net,stopblockads.com,storader.com,",
        "storage-ad.com,store4porn.com,straightenedphiladelphiarequire.com,streamjav.top,stringroadway.com,stripsaver.c",
        "om,striptube.net,studads.com,studiotrackmusic.com,sukaporn.com,sunglassesexpensive.com,sunmedia.tv,sunmediaads",
        ".com,sunporno.com,sunshineadditions.com,superadexchange.com,superonclick.com,superxxxfree.com,supposedlymadeup",
        ".com,supremeadblocker.com,suptube.com,surpavdggpjar.site,surplusaltarmadness.com,swak-adguard.pro,swarmpush.co",
        "m,swatowbetokenganam.cfd,sweetmedia.org,swesomepop.com,swiftladle.com,swimmingnarcotictravel.com,swmlidloaetad",
        ".site,swwpush.com,sygvwadaxjesn.space,synsads.com,syrupimmediate.com,systemads.net,systemleadb.com,taadd.com,t",
        "aboola.com,taboola.net,tacxemkinqtav.space,taddpxmmooi.com,tadodpfikom.in,tafdiywfnavss.site,tahrqfbavxmxf.sit",
        "e,takeads.com,tanagerpoppel.rest,tantoporno.com,tapingferocitymadeira.com,tapjoyads.com,taroads.com,taversroom",
        "thy.com,taversserang.cyou,tavgvouyakozy.website,tavlomikyionr.website,tavryuhcshdsr.website,tcpcharms.com,tead",
        "s-xo.com,teads.tv,teadumiccdoct.website,technologies2radar.net,technoratimedia.com,technoshadows.com,techporn.",
        "ph,tectrixsnavvle.com,teenporno.xxx,teensexgfs.com,tehseelaves.com,tejxthyxavhfb.site,teneralavarice.cfd,terma",
        "gewaving.com,terrapush.com,text-link-ads.com,tgtmedia.com,thavxaiwvxtja.website,the-adult-company.com,the-bina",
        "ry-trader.biz,theadgateway.com,theadsparks.com,thebetterdealz.com,thecoreadv.com,thelotter-affiliates.com,them",
        "ediaplanets.com,themselvespresentationfavourable.com,theplayadvisor.com,theporndude.com,thepornstar.com,thesis",
        "adornpathetic.com,thesocialsexnetwork.com,thingsmadenavy.com,third-tracking.com,thorpeslav.com,thoughtleadr.co",
        "m,thrad.ai,thrads.ai,threadpassivetrespass.com,thriftytraveler.com,thrixxx.com,thtrsihwdznav.site,thunderhead.",
        "com,tiatmuadwuhwy.online,tidycamel.com,tiedhamadjoin.com,tihmjppaavrmz.site,tiladlolbtu.in,tiltgardenheadlight",
        ".com,tinyadz.com,titanads1.com,titanads2.com,titanads3.com,titanads4.com,tjcfeyegncavs.online,tjesbxadovyot.si",
        "te,tjiyavxmtihln.space,tkpopd.cfd,tkpopp.com,tktube.com,tlavideo.com,tlrzavbrosrsv.space,tlucfqhtavxyx.space,t",
        "luihoeadocjs.store,tlvmedia.com,tnnfecqadqzck.online,toboads.com,toibjavyncxnc.site,tokelaunavet.cyou,tokenads",
        ".com,tomladvert.com,tomorrowlemonade.com,tonemedia.com,tonicheaviness.com,topadsservices.com,topadvert.ru,topb",
        "etfast.com,topclickguru.com,topcpmcreativeformat.com,topdealad.com,topdisplaynetwork.com,topprofitablecpm.com,",
        "toprevenuecpmnetwork.com,topsexcams.club,topsexmovs.com,toptopleads.com,toroadvertising.com,tossoffads.com,tot",
        "aladblock.com,totaladperformance.com,totalav.com,totymanglave.com,tpdads.com,tpmedia-reactads.com,tpopfgvlqytl",
        "m.online,tprfavksbqzmr.online,tqqavzfshkdsh.website,track-campaing.club,track-victoriadates.com,track4ref.com,",
        "trackad.cz,trackcherry.com,trackcitg.com,trackclicks00.com,tracker-2.com,tracker-tds.info,trackerflw.com,track",
        "errr.com,tracki.click,tracking-aws-5.com,tracking-zy.com,trackingmembers.com,trackingtraffo.com,trackmundo.com",
        ",tracks20.com,tracksfaster.com,trackspeeder.com,trackstracker.com,tracktds.com,tracktilldeath.club,trackuity.c",
        "om,trackvoluum.com,trackwilltrk.com,trackzd.com,tradbypass.com,tradeadexchange.com,tradedoubler.com,tradeexper",
        "t.net,tradehouse.media,tradepub.com,tradertimerz.media,tradetracker.net,traditionalboycottflung.com,traditiona",
        "lmusic.co.uk,traditionblindness.com,tradplusad.com,traffic-media.co.uk,trafficad-biz.com,trafficadbar.com,traf",
        "ficmediaserver.com,trailerheavendirectness.com,trainedwave.pro,trapdoortissuepush.com,travelandtourworld.com,t",
        "ravelaudience.com,travellingcrushslit.com,travellingpresidentding.com,travelscream.com,travelsplayboy.com,trav",
        "eltop.org,traverseseven.com,travidia.com,tremormedia.com,trimreaddrunken.com,tripcarriagewaving.com,tripsthorp",
        "elemonade.com,triumphantthingsreading.com,tronads.io,trustedcpmrevenue.com,trustphiladelphiacompelling.com,ts-",
        "tracker.com,tsadcebakufrd.site,tsmodelstube.com,tspops.com,tsuccwavchpvh.website,tsztqlwravxkl.site,ttfwotjeta",
        "vmk.website,ttzmedia.com,tube-sexs.com,tubeadnetwork.com,tubeadv.com,tubeadvertising.eu,tubecorp.com,tubecorpo",
        "rate.com,tubecup.net,tubeelite.com,tubemov.com,tubeoffline.com,tubepork.com,tuberel.com,tubetruck.com,tubeultr",
        "a.com,tubev.sex,tubewolf.com,tubexazimebeze.com,tubexo.tv,turboadv.com,turnstileunavailablesite.com,tvewiifmav",
        "niu.website,twads.gg,tweakpersuadelowest.com,twinpinenetwork.com,twiselaveria.com,twistads.com,twittad.com,txg",
        "tadnmmwwte.space,txxx.com,typhulaverging.cfd,uadllyad.com,uads.cc,uaeapcmjbmzad.space,uaqiwadxmsniz.online,uav",
        "efhtrbkbhq.online,uavnfdezxzote.site,ubercpm.com,ubgvqakukavmm.space,ublockpop.com,ucaavjcweklhn.space,ucnwhht",
        "aadaqp.site,udsqbadxjvzanh.com,ueleensadcyxh.site,ufauolqhhcpmd.space,ufidxaduyqqkq.site,ufirmirylfavb.online,",
        "ugadceetawiae.site,ugazuogyeeadt.online,uhuvadkubxjkv.website,uitopadxdy.com,uiuhawavotxsv.space,ukaadmlcfluko",
        ".site,ukaviayvjfydp.online,ukcampsite.co.uk,ulbruiqadlmaa.online,ulpradvpeaiqs.store,ultimedia.com,umljflgaddc",
        "dk.space,unavoidablestates.com,uncadyaewiajd.online,uncededtravoy.com,uncertainadministration.com,underclick.r",
        "u,undergroundportugueseheadache.com,unibet.co.uk,unibet.com,unidentifiedscammertravel.com,universalads.com,uni",
        "versityroadsidecircumstantial.com,unloadonly.com,unripe-head.pro,unrulymedia.com,unsettledradiator.com,unshave",
        "paraphs.com,untimely-promotion.com,unwavedmalarky.cyou,unweavederiv.com,uoutube.com,upbeatmeadow.com,uploadclo",
        "ud.pro,uploaded.net,uploadgig.com,upornia.com,uprivaladserver.net,uptopopunder.com,upush.co,upymgrtnacpco.site",
        ",urdatingassistant.com,urgayporn.com,urzdxiiugisad.site,usasexcams.com,usavmhnwphcmm.online,userimmediate.com,",
        "usesponsorarrange.com,ussmtrack.com,ust-ad.com,usuxodfefavbm.space,utavbvaervidi.site,utm-campaign.com,utrocaa",
        "drboym.space,utteredadisappo.com,utubepwhml.com,uubvkadmkvnys.space,uuwjrmvavkgrs.site,uvvsyeadlyrgb.space,uwz",
        "wqadqkcjgw.site,uyqwpnspgadkq.site,uyriroadwtdwu.space,uywfpprhavyog.site,uzpuaveerqjei.site,v8eaezd.cc,vaguem",
        "ad.com,valemedia.net,validclick.com,valuad.cloud,valuead.com,valueclick.cc,valueclick.com,valueclick.net,value",
        "clickmedia.com,variabilityaviationmutiny.com,vatanclick.ir,vavuwetus.com,vazijiavfyebf.site,vcmedia.com,vdezte",
        "mladdeb.online,vdhvldghccavh.site,vdxsvladcihnm.online,venturead.com,verbsponsor.com,verdecasino-offers.com,ve",
        "rdepromo.info,vertismedia.co.uk,vespymedia.com,vexilladuper.cyou,vfreecams.com,vhojdkvcadavb.space,viadata.sto",
        "re,viads.com,viads.net,vid-adblocker.com,vidcpm.com,video-adblocker.com,videoadstech.org,videocampaign.co,vide",
        "osprofitnetwork.com,viewablemedia.net,viiadaof.com,viibslav.com,vikablavflhe.com,vikiporn.com,villainous-track",
        ".pro,vipads.live,vipavkacrfujf.online,viptube.com,viradisazo.qpon,viralmediatech.com,virtuedeadlock.com,visari",
        "omedia.com,viscidvareusebetitle.cfd,visiads.com,vjzlvloavqarr.space,vkgtrack.com,vkipfmadahrwh.site,vkoxgtewza",
        "dqi.site,vkyieskdlavjt.space,vlogerads.com,vlwqyhgnslfad.space,vmpdmfacpctto.space,vnakjojlazcpm.online,voeqzu",
        "uvnwpad.website,voirpfhavircs.online,votepercussivepoplar.com,vowsavageskim.com,vpvlxuooad.com,vqkusfxadcdll.w",
        "ebsite,vradfvwfmwvaw.space,vrbcauaviehga.space,vrgbaevvadiwn.site,vrizead.com,vrstnadgljwiy.space,vrtzads.com,",
        "vsavbpsnxdrgt.online,vsexshop.ru,vtdoadqxkv.com,vtube.to,vucxfzsavoxdd.online,vuevgiavjbplr.space,vuiebktuavkc",
        "c.online,vulkanbet.me,vulnerableimmigrateaboveaverage.com,vvautxavycmwh.space,vvladxqdlpihl.online,vwzusobugmr",
        "ad.space,vxxgxicadjtia.space,vxyqwespdaadz.site,w00tmedia.net,wadecarbohydrate.com,waexhoxdqigad.space,wafmedi",
        "a6.com,wagsad.com,wailedshave.com,walkeralacrityfavorite.com,wangheebehav.com,wanlyavower.com,warashi-asian-po",
        "rnstars.fr,warilyclickframes.com,warmmeadowroad.com,washedadvance.com,washoffwaviercyanite.cfd,watchadsfree.co",
        "m,watchcpm.com,wavecdn.de,wavedsuspect.click,waveetiquette.com,waveletiricize.cfd,wavermiddayboundary.com,wavi",
        "esdaywork.cyou,wavingauradignity.com,wavtcdqwbuhiw.space,waxapushlite.com,wayanatomyunavailable.com,wayfterthe",
        "yhadgo.org,wbamedia.com,wcgbetyplakno.website,wclpvybetahei.site,wdahqumfpnmav.space,wdczlavbweeby.online,wdxu",
        "ugjqeavzv.space,wearadmiration.com,weaveradrenaline.com,weaversprinkle.com,weaveworkingheaviness.com,web-adblo",
        "cker.com,web3ads.net,webads.co.nz,webads.nl,webadserver.net,webbed-leadership.com,webcampromo.com,webcampromot",
        "ions.com,webcams.com,webcamtests.com,webclickengine.com,webclickmanager.com,webpushcloud.info,websitepromoserv",
        "er.com,webtradehub.com,weekheadlong.com,weforads.com,wellmadesurvey.pro,wellworn-lead.com,wetrackgames.com,wfb",
        "cfadgivxqd.space,wfiutburadoho.online,wfnetwork.com,wgfqayocqadzp.space,whaleads.com,wheebsadree.com,wheegneho",
        "ad.net,wheelchairavoid.com,wheelchairbewilderedwaver.com,whirlclick.com,whoadhoxtojg.com,whopping-click.com,wh",
        "orledebbets.cyou,whwdxzadairoq.website,widespreadcomponent.com,wifescamara.click,wigetmedia.com,williamhillcas",
        "ino.com,windfallleavebrawl.com,winneradequateablebodied.com,winneradsmedia.com,wisjevyyjdjad.site,withdrawtrad",
        "e.com,wktmzgeadgmje.com,wlkokwriavlw.com,wmmediacorp.com,wnt-s0me-push.net,wnvqeizwbpads.online,woffxxx.com,wo",
        "mxwsnxadxtl.space,wonderadblock.com,wootmedia.net,workdeadlinededicate.com,worldsbestcams.com,worried-advantag",
        "e.com,worthylighteravert.com,wowgwmezvkuav.site,wpadmngr.com,wpiadbxpy.in,wpnetwork.eu,wpush.org,wpushorg.com,",
        "wpushsdk.com,wqbeavbpztwsm.website,wravogram.cfd,wretchedadvantage.com,wsknwakvkeiad.site,wtg-ads.com,wtpiylrm",
        "avdfp.space,wtqhxggxngfad.site,wtzqznypmadeo.store,wujtefbetb.com,wunsofiubphad.space,wwads.cn,wwwadcntr.com,w",
        "wwpromoter.com,wwwxxx.uno,wzkqcamv.com,xad.com,xadcentral.com,xadpagwro.com,xadsmart.com,xapads.com,xavhiszxjs",
        "pce.website,xavions.shop,xbcpcn.com,xbhcyadhm.com,xbhoylaadarbq.website,xcelsiusadserver.com,xcpmfvlfcdltd.onl",
        "ine,xcrrdxmdadebs.site,xglvldsblpkad.online,xgwhfyaavzpyp.space,xhamstercams.com,xinbzybuosadk.website,xjav.tu",
        "be,xjoavfrckohwo.online,xjsgturadzcgo.online,xkavrcwjklaitf.com,xldadwsosaqiq.site,xlovecam.com,xmayktavadugm.",
        "site,xmladserver.com,xmtrading.com,xnxxporn.video,xnxxxnxx.site,xoaveubcbdpld.com,xogogowebcams.com,xrff-adgua",
        "rd.pro,xrqcamarbnhxg.website,xslbuoadsfsrf.space,xsqltdwuccamf.space,xtendmedia.com,xuytfncamofxt.site,xvidxxx",
        ".com,xwkvojadxcnyd.space,xxbifrkzadx.com,xxx-porn-tube.com,xxxbannerswap.com,xxxblackbook.com,xxxbox.me,xxxbp.",
        "tv,xxxbule.com,xxxdessert.com,xxxex.com,xxxfetish24.com,xxxhd.icu,xxxiijmp.com,xxxijmp.com,xxxivjmp.com,xxxmat",
        "ch.com,xxxoh.com,xxxpornhd.pro,xxxshake.com,xxxviiijmp.com,xxxviijmp.com,xxxvijmp.com,xxxvjmp.com,xxxvogue.net",
        ",xxxwap.pro,xxxwebtraffic.com,xxxxsx.com,xxxzbkhpjxzry.store,yadmarmizuogr.website,yads.tech,yadsunkzjtzlb.spa",
        "ce,yavecqiztteba.site,yavli.com,yavsmataemray.online,yavurxepklnzm.space,yazatadoolysups.cfd,ybb-network.com,y",
        "bcnevrcnyadv.site,ydjadyzuepuvf.site,ydwekhadydlnz.space,yekavcxlkbejc.online,yesporn.cam,yespornpleasexxx.com",
        ",yetterslave.com,yeyswxtehnave.site,yieldads.com,yieldlove-ad-serving.net,yjjvzmruicyav.online,ykdqqskuziadx.s",
        "ite,ykveifvdzadrl.online,yoads.net,yoc-adserver.com,yogadeprive.com,yopuademtfkwb.space,you-porn.com,youhavequ",
        "ited.com,youlamedia.com,youporn.com,youradexchange.com,yourquickads.com,youtube.com,yowiespopian.com,yoxfjudad",
        "p.com,yoyadsdom.com,ypcjgbavpkray.site,yporn.tv,ypztdrxavedah.space,yqepkjcpcxnay.space,yqopmxeyoaveu.online,y",
        "rrnadvdbvdow.space,yuintbradshed.com,yukclick.me,yumenetworks.com,yummyadvertiseexploded.com,yuppads.com,yuppy",
        "ads.com,yurekbkmhoadl.net,yuwseujcjavak.space,yvmads.com,ywiicadcuvilo.online,yx-ads6.com,yxaasylsdjado.site,y",
        "xrlvfirqiave.website,yyecdiehcvadf.site,yyxnoegkqkavu.space,zadlclqnequke.site,zaimads.com,zanox-affiliate.de,",
        "zarpop.com,zavcmzahutmqo.site,zavirand.com,zavnnfijuylnu.site,zavunmknjlpwl.space,zavylwpyuijzb.website,zbkrmw",
        "rzftiav.site,zbporn.com,zcbetcczfvylq.site,zcfpfbavrffxm.site,zeads.com,zealshaving.com,zebracave.com,zedporn.",
        "com,zenomedia.com,zerads.com,zesty-lead.pro,zetadeo.com,zeusadx.com,zextzccadpr.com,zfctrack.net,zgaxadxsycppa",
        ".website,zgijtonvzgadk.online,zhyhadnrqzpcd.site,ziffdavis.com,zimoccaengrave.shop,zimvlqwrtadmi.space,zipcryp",
        "ticbroadsheet.com,zixxsovtavprk.online,zkdwtdzlcadfu.site,zkvsavwzcbfaj.online,zkxweadue.com,zlavxbjuyekwo.sit",
        "e,zlzwhrhkavos.xyz,zotqhsyqbetkg.online,zoxlzvrqeqadk.site,zplxavnqigfrp.site,zpuavdcswqnml.online,zpxsadurflh",
        "s.com,zrbezdaddiahk.space,zrdubetejajen.website,zrygvvxyadvro.site,zsfbadlhjmpow.site,zsnweuiavdmzs.online,zte",
        "eavqibjsgj.space,ztrack.online,zumidxxxwcjob.site,zunoptjciavj.in,zuzaghbkajav.in,zvwjrwav.com,zvzpopazdasxh.w",
        "ebsite,zwkfzyjtadeuz.space,zxavziahazmyb.online,zyadpzcdswdre.com,zypenetwork.com,zytfycpmnlzio.online,eab.elb",
        "phzgw.cc,demjpgtf.zvpxyyql.cc,api2.jpfviwgkz.cc,xxycw341.com,vnhgr465.com,caow.us,583.cc,9521.com,2726.com,831",
        "9.com,9212.app,322.cc,384.tv,4477.tv,177.gg,5651.com,275.cc,391.cc,837.tv,1x4g6f2.com,aizhantj.com,0cy5aps.com",
        ",ax8rylb.com,7xkpxuw.com,4gl3g.z.com,mehqk5u.com,smun7ro.com,67x8epk.com,kvkluj8.com,lw1su4n.com,hcxftcl.com,n",
        "f65hwr.com,3dzmgtc.com,dkepyys.com,hv7hpkk.com,tq.qwehkg.top,ta.dqgjla.top,tz.erghsasq.top,magsrv.com,forkedspare.com\".split(','));functi",
        "on isAdDomain(h){if(!h)return false;h=h.toLowerCase();if(__museAdSet.has(h))return true;var i=h.indexOf('.');w",
        "hile(i>=0){h=h.slice(i+1);if(__museAdSet.has(h))return true;i=h.indexOf('.');}return false;}function isAdUrl(u",
        "){try{var x=new URL(u,location.href);return !!x.hostname&&isAdDomain(x.hostname);}catch(e){return false;}}func",
        "tion isContent(el){if(!el||!el.querySelector)return false;if(el.querySelector('a[href*=\"/voddetail/\"],a[href*=",
        "\"/vodplay/\"],a[href*=\"/video/\"],a[href*=\"/player/\"],a[href*=\"/v5/\"]'))return true;if(el.querySelector('#go_btn",
        ",#time_badge,#read_zone'))return true;return false;}function foreign(u){try{var x=new URL(u,location.href);ret",
        "urn !!x.hostname&&x.hostname!==host;}catch(e){return false;}}function isRealSite(){var as=document.querySelectorAll(\'a[href]\');var n=0;for(var i=0;i<as.length;i++){var h=as[i].getAttribute(\'href\')||\'\';if(foreign(h))continue;var p=\'\';try{p=new URL(h,location.href).pathname;}catch(e){continue;}if(p.indexOf(\'/vodtype/\')===0||p.indexOf(\'/voddetail/\')===0||p.indexOf(\'/vodplay/\')===0){if(++n>=2)return true;}}return false;}function isPlaceholderHref(h){if(!h)return true;h",
        "=String(h).trim().toLowerCase();return h===''||h.charAt(0)==='#'||h.indexOf('javascript:')===0;}function inSam",
        "eHostLink(el){var a=el&&el.closest?el.closest('a'):null;return !!(a&&a.href&&!foreign(a.href));}function isOpa",
        "queImg(s){return s&&(s.indexOf('blob:')===0||s.indexOf('data:')===0);}function txt(el){return (el.innerText||'",
        "').replace(/\\s+/g,'');}function ageIdc(el){return ((el.id||'')+' '+((el.className&&el.className.baseVal!==unde",
        "fined)?el.className.baseVal:(el.className||''))).toLowerCase();}function isAgeGate(el){if(!el)return false;if(",
        "/agedisclaimer|age[-_]?verif|age[-_]?verification|age[-_]?gate|age[-_]?check|over18|under18|age[-_]?confirm/.t",
        "est(ageIdc(el)))return true;var t=(el.innerText||'');if(/这是个成人网站|我年满\\s*18\\s*岁|i\\s*am\\s*18|i'm\\s*18|verify your",
        " age|age verification/i.test(t))return true;if(t.length<=120&&t.indexOf('18')>=0&&/(成人|年满|\\bage\\b|adult|over 1",
        "8|under 18)/i.test(t))return true;return false;}\nfunction isChallenge(el){if(!el)return false;try{var __ct=(do",
        "cument.title||'').toLowerCase();if(__ct.indexOf('请稍候')>=0||__ct.indexOf('just a moment')>=0)return true;}catch",
        "(e){}if(el.tagName==='IFRAME'){var s0=el.src||(el.getAttribute?el.getAttribute('src'):'')||'';if(s0&&(s0.index",
        "Of('cloudflare')>=0||s0.indexOf('hcaptcha')>=0||s0.indexOf('recaptcha')>=0))return true;return false;}if(!el.q",
        "uerySelectorAll)return false;var frs=el.querySelectorAll('iframe');for(var i=0;i<frs.length;i++){var f=frs[i].",
        "src||(frs[i].getAttribute?frs[i].getAttribute('src'):'')||'';if(f&&(f.indexOf('cloudflare')>=0||f.indexOf('hca",
        "ptcha')>=0||f.indexOf('recaptcha')>=0))return true;}return false;}function inAgeGate(el){if(!el)return false;i",
        "f(isAgeGate(el))return true;var n=el.parentElement,p=0;while(n&&p<6){if(/agedisclaimer|age[-_]?verif|age[-_]?v",
        "erification|age[-_]?gate|age[-_]?check|over18|under18|age[-_]?confirm/.test(ageIdc(n)))return true;n=n.parentE",
        "lement;p++;}return false;}function kill(el){if(el&&el.parentNode)el.parentNode.removeChild(el);}function colla",
        "pse(el){var p=el,i=0;while(p&&i<3&&p!==document.body){if(txt(p).length===0&&!p.querySelector('img,video,iframe",
        ",form,input,button,select,textarea')){var n=p.parentElement;kill(p);p=n;i++;}else break;}}function collapseCard(a){var p=a,i=0;while(p&&i<3&&p!==document.body){var links=p.querySelectorAll(\'a[href]\');var hasReal=false;for(var k=0;k<links.length;k++){var h=links[k].getAttribute(\'href\')||\'\';if(!h)continue;if(isPlaceholderHref(h))continue;if(!foreign(links[k].href)){hasReal=true;break;}}if(hasReal)break;if(p.querySelector(\'img,video,iframe\'))break;if(txt(p).length>200)break;var n=p.parentElement;kill(p);p=n;i++;}}function killLoaderP",
        "adding(){var ss=document.querySelectorAll('style');for(var i=0;i<ss.length;i++){var st=ss[i];var id=st.id||'';",
        "if(id.slice(-9)!=='_style_id')continue;var t=(st.textContent||'').replace(/\\s/g,'');if(t.indexOf('padding-bott",
        "om:100px')>=0&&st.parentNode)st.parentNode.removeChild(st);}}function mainLink(card){var as=card.querySelector",
        "All('a[href]');for(var i=0;i<as.length;i++){var a=as[i];if(isPlaceholderHref(a.getAttribute('href')))continue;",
        "if(txt(a).length>=4)return a;}return as.length?as[0]:null;}\nfunction hideMixedForeignCards(){var lists=documen",
        "t.querySelectorAll('ul,ol');for(var li=0;li<lists.length;li++){var list=lists[li];if(!list.isConnected)continu",
        "e;if(list.closest('nav,header,footer'))continue;if(isAgeGate(list))continue;var cards=[];var kids=list.childre",
        "n;for(var k=0;k<kids.length;k++){var c=kids[k];if(c.nodeType!==1)continue;if(!c.querySelector('a[href]'))conti",
        "nue;cards.push(c);}if(cards.length<8)continue;var same=0,total=0;for(var m=0;m<cards.length;m++){var a=mainLin",
        "k(cards[m]);if(!a)continue;var href=a.getAttribute('href')||'';if(isPlaceholderHref(href))continue;total++;if(",
        "!foreign(href))same++;}if(total<8)continue;if(same<=total*0.5)continue;for(var n=0;n<cards.length;n++){var a2=",
        "mainLink(cards[n]);if(!a2||!a2.isConnected)continue;var h2=a2.getAttribute('href')||'';if(!h2)continue;if(fore",
        "ign(h2))kill(cards[n]);}}}\nfunction hideNoidDiv(){var n=document.getElementById('noiddiv');if(!n||!n.isConnect",
        "ed||n===document.body)return;if(n.querySelector&&n.querySelector('video'))return;n.style.display='none';}\nfunc",
        "tion hideHeadingPromoLinks(){var hs=document.querySelectorAll('h1 a[href],h2 a[href],h3 a[href],h4 a[href],h5 ",
        "a[href],h6 a[href]');for(var i=0;i<hs.length;i++){var a=hs[i];if(!a.isConnected)continue;if(inAgeGate(a))conti",
        "nue;var href=a.getAttribute('href')||'';if(!href)continue;if(foreign(href))a.style.display='none';}}\nfunction ",
        "noteHide(rule,el){try{if(!window.__museHideLog)window.__museHideLog=[];if(window.__museHideLog.length<20){var ",
        "cn=(el.className&&el.className.baseVal!==undefined)?el.className.baseVal:(el.className||'');window.__museHideL",
        "og.push(rule+':'+(el.tagName||'')+'.'+String(cn).slice(0,30));}}catch(e){}}\n/*push v30 R32-1*/function lfReg(h){h=String(h||'').toLowerCase().replace(/^www\\./,'');var p=h.split('.');return p.length>2?p.slice(-2).join('.'):h;}function killLinkFarms(){var hr=lfReg(location.hostname);function same(u){try{var x=new URL(u,location.href);return lfReg(x.hostname)===hr;}catch(e){return true;}}function real(a){var h=(a.getAttribute('href')||'').trim().toLowerCase();return !!h&&h.charAt(0)!=='#'&&h.indexOf('javascript:')!==0;}var al=document.querySelectorAll('a[href]');var tot=0,ss=0;for(var i=0;i<al.length;i++){if(!real(al[i]))continue;tot++;if(same(al[i].href))ss++;}if(ss<2||ss<tot*0.1)return;var cs=document.querySelectorAll('p,div,ul,ol,footer,nav,section,td,li,dl');var hit=[];for(var j=0;j<cs.length;j++){var e=cs[j];if(e.getAttribute('data-muse-lf'))continue;if(e.querySelector('[data-muse-lf]'))continue;var as=e.querySelectorAll('a[href]');var n=0,sameN=0,hs={},hn=0;for(var k=0;k<as.length;k++){var a=as[k];if(!real(a))continue;n++;if(same(a.href)){sameN++;break;}var rh='';try{rh=lfReg(new URL(a.href,location.href).hostname);}catch(x){rh='';}if(rh&&!hs[rh]){hs[rh]=1;hn++;}}if(sameN>0||n<8||hn<8)continue;if(e.querySelector('img,picture,video,audio,iframe,canvas,form,input,button,select,textarea,svg'))continue;var c=e.cloneNode(true);var rm=c.querySelectorAll('a,script,style');for(var q=0;q<rm.length;q++)if(rm[q].parentNode)rm[q].parentNode.removeChild(rm[q]);var rest=(c.textContent||'').replace(/[\\s|｜/、，,·•:：\\-–—]+/g,'');if(rest.length>12)continue;hit.push(e);}for(var m=0;m<hit.length;m++){var h0=hit[m];var deeper=false;for(var o=0;o<hit.length;o++){if(o!==m&&h0!==hit[o]&&h0.contains(hit[o])){deeper=true;break;}}if(deeper)continue;h0.setAttribute('data-muse-lf','1');try{h0.style.setProperty('display','none','important');}catch(x){}try{noteHide('lf',h0);}catch(x){}}}/*push v30 R32-2*/function brandOf(h){var l=lfReg(h).split('.')[0]||'';return l.replace(/[0-9]+/g,'');}function sameBrand(u){try{var x=new URL(u,location.href);var a=brandOf(x.hostname),b=brandOf(location.hostname);return a.length>=3&&a===b;}catch(e){return false;}}/*push v28 F1*/function adFrameDoc(d){try{if(!d||!d.body)return false;var loc='';try{loc=String(d.location&&d.location.href||'');}catch(e){}if(/cdn-cgi\\/challenge|captcha|turnstile|challenges\\.cloudflare/i.test(loc))return false;if(d.querySelector('[class*=captcha],[id*=captcha],.cf-turnstile,[class*=turnstile],[id*=challenge]'))return false;var site='';try{site=String(d.location.hostname||'').toLowerCase().replace(/^www\\./,'');}catch(e){}var ad=0,els=d.querySelectorAll('script[src],iframe[src]');for(var i=0;i<els.length;i++){var s=els[i].getAttribute('src')||'';if(/^\\s*\\/\\//.test(s))s='https:'+s.trim();var m=/^\\s*https?:\\/\\/([^\\/?#:]+)/i.exec(s);if(m&&isAdDomain(m[1].toLowerCase()))ad++;}if(!ad)return false;var as=d.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){var h=(as[j].getAttribute('href')||'').trim();if(!h||h.charAt(0)==='#'||/^javascript:/i.test(h))continue;var hm=/^\\s*(?:https?:)?\\/\\/([^\\/?#:]+)/i.exec(h);if(!hm)return false;var hh=hm[1].toLowerCase().replace(/^www\\./,'');if(site&&(hh===site||hh.slice(-site.length-1)==='.'+site||site.slice(-hh.length-1)==='.'+hh))return false;}if(d.querySelector('video,audio,form,input,button,textarea,select'))return false;var c=d.body.cloneNode(true);var rm=c.querySelectorAll('script,style,noscript,template');for(var k=0;k<rm.length;k++)if(rm[k].parentNode)rm[k].parentNode.removeChild(rm[k]);if((c.textContent||'').replace(/\\s+/g,'').length>0)return false;return true;}catch(e){return false;}}/*push v29 R31-2*/function f1Judge(f){if(f.__museF1)return;var d=null;try{d=f.contentDocument;}catch(e){d=null;}if(!d){f.__museF1=1;return;}var lh='';try{lh=String(d.location.href||'');}catch(e){}if(!lh||lh==='about:blank')return;var rs=d.readyState;if(rs!=='interactive'&&rs!=='complete')return;if(rs==='interactive'){if(f.__museF1I===lh)return;f.__museF1I=lh;}if(adFrameDoc(d)){f.__museF1=1;try{f.style.setProperty('display','none','important');}catch(e){}try{f.src='about:blank';}catch(e){}return;}if(rs==='complete')f.__museF1=1;}function f1Hook(f){if(f.__museF1H)return;f.__museF1H=1;try{f.addEventListener('load',function(){try{f1Judge(f);}catch(e){}});}catch(e){}var raf=window.requestAnimationFrame?function(cb){window.requestAnimationFrame(cb);}:function(cb){setTimeout(cb,16);};var n=0;(function poll(){if(f.__museF1||!f.isConnected||n++>1800)return;var d=null;try{d=f.contentDocument;}catch(e){d=null;}if(!d){f.__museF1=1;return;}var lh='';try{lh=String(d.location.href||'');}catch(e){}if(lh&&lh!=='about:blank'&&d.readyState!=='loading'){try{f1Judge(f);}catch(e){}if(f.__museF1||f.__museF1I)return;}raf(poll);})();}function killAdFrames(){var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){var f=fs[i];if(f.__museF1)continue;f1Hook(f);f1Judge(f);}}/*push v29 R31-3*/function f3Style(){try{var st=document.getElementById('__museF3Style');if(st&&st.isConnected)return;st=document.createElement('style');st.id='__museF3Style';st.textContent='[data-cl-spot],[data-muse-f3]{display:none!important}';(document.head||document.documentElement).appendChild(st);}catch(e){}}function pureAdWrap(p){var c=p.cloneNode(true);var rm=c.querySelectorAll('[data-cl-spot],script,style,noscript,template,[data-muse-f3]');for(var k=0;k<rm.length;k++)if(rm[k].parentNode)rm[k].parentNode.removeChild(rm[k]);if((c.textContent||'').replace(/\\s+/g,'').length>0)return false;if(c.querySelector('a[href],img,picture,svg,video,audio,iframe,canvas,embed,object,form,input,button,textarea,select'))return false;return true;}function killClSpots(){f3Style();var ss=document.querySelectorAll('[data-cl-spot]');for(var i=0;i<ss.length;i++){var s=ss[i];if(s.getAttribute('data-muse-f3'))continue;s.setAttribute('data-muse-f3','1');var p=s.parentElement;while(p&&p!==document.body&&p!==document.documentElement){if(!pureAdWrap(p))break;p.setAttribute('data-muse-f3','1');p=p.parentElement;}}}function sweep(){try{killAdFrames();}catch(e){}try{killClSpots();}catch(e){}try{killLinkFarms();}catch(e){}killLoaderPadding",
        "();killPreRoll();pruneExtraVideos();",
        // v76：假分类/假视频（跳转完成后，外链 <a> 藏掉；真分类/真视频是站内相对路径）+ 赌博横幅墙（javascript:void(0) 含图 <a> 藏掉）。
        // v77：直接进站也激活——isRealSite()（页含 ≥2 站内正片链接即真实站）时同样触发；发布站页无站内正片链接，不触发。
        "/*v77*/if(window.__museJumpDone||isRealSite()){var __museFAs=document.querySelectorAll('a[href]');for(var __museFI=0;__museFI<__museFAs.length;__museFI++){var __museFA=__museFAs[__museFI];if(!__museFA.isConnected)continue;if(inAgeGate(__museFA))continue;if(isContent(__museFA))continue;if(!foreign(__museFA.href))continue;/*push v30 R32-2*/if(sameBrand(__museFA.href))continue;var __museFP=__museFA.parentElement;kill(__museFA);collapseCard(__museFP);}var __museVAs=document.querySelectorAll('a[href^=\"javascript:void(0)\"]');for(var __museVI=0;__museVI<__museVAs.length;__museVI++){var __museVA=__museVAs[__museVI];if(!__museVA.isConnected)continue;if(inAgeGate(__museVA))continue;if(!__museVA.querySelector('img'))continue;var __museVP=__museVA.parentElement;kill(__museVA);collapseCard(__museVP);}}",
        "var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){v",
        "ar a=as[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;if(!isAdUrl(a.href))continue;var rel=(a.getAttr",
        "ibute('rel')||'').toLowerCase();var spon=rel.indexOf('sponsored')>=0;var hasImg=!!a.querySelector('img');var h",
        "asBg=false;if(!spon){var dv2=a.querySelectorAll('div');for(var q=0;q<dv2.length;q++){var de=dv2[q];if(de.getAt",
        "tribute('x-bg-loader-url')||de.getAttribute('data-bg')){hasBg=true;break;}var dc=getComputedStyle(de);if((dc.b",
        "ackgroundImage||'').indexOf('url(')>=0){hasBg=true;break;}}}if(!spon&&!hasImg&&!hasBg)continue;if(!spon&&hasIm",
        "g&&!hasBg&&txt(a).length>8)continue;var p=a.parentElement;kill(a);collapse(p);}var bas=document.querySelectorA",
        "ll('a[href]');for(var i=0;i<bas.length;i++){var a=bas[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;i",
        "f(isAdUrl(a.href))continue;if(isBannerAd(a)){var p=a.parentElement;kill(a);collapse(p);}}var imgs=document.que",
        "rySelectorAll('img');for(var j=0;j<imgs.length;j++){var im=imgs[j];if(!im.isConnected)continue;if(inAgeGate(im",
        "))continue;if(im.closest('a'))continue;var s=im.currentSrc||im.src||im.getAttribute('data-original')||'';if(s&",
        "&isAdUrl(s)){var q=im.parentElement;var w2=im.parentElement,hid2=false;while(w2&&w2!==document.body){var csw2=",
        "getComputedStyle(w2);if(csw2.position==='fixed'||csw2.position==='absolute'){var rw2=w2.getBoundingClientRect(",
        ");if(rw2.width>=window.innerWidth*0.6&&rw2.height>=window.innerHeight*0.35){w2.style.display='none';hid2=true;",
        "}else if(rw2.width>=window.innerWidth*0.4&&rw2.bottom>=window.innerHeight-2&&rw2.top>=window.innerHeight*0.5&&",
        "rw2.height>=60){w2.style.display='none';hid2=true;}break;}w2=w2.parentElement;}if(!hid2){kill(im);collapse(q);",
        "}continue;}if(s&&isOpaqueImg(s)){if(im.__museLb)continue;if(recentTap()&&window.__museTapImg){im.__museLb=1;co",
        "ntinue;}var cs2=getComputedStyle(im);if(cs2.position==='fixed'||cs2.position==='absolute'){var r2=im.getBoundi",
        "ngClientRect();if(r2.width>=window.innerWidth*0.4||r2.height>=window.innerHeight*0.5){var w=im.parentElement,h",
        "id=false;while(w&&w!==document.body){var csw=getComputedStyle(w);if(csw.position==='fixed'||csw.position==='ab",
        "solute'){var rw=w.getBoundingClientRect();if(rw.width>=window.innerWidth*0.4||rw.height>=window.innerHeight*0.",
        "5){w.style.display='none';hid=true;}break;}w=w.parentElement;}if(!hid)kill(im);}}}}var dvs=document.querySelec",
        "torAll('div');for(var k=0;k<dvs.length;k++){var d=dvs[k];if(!d.isConnected)continue;if(inAgeGate(d))continue;i",
        "f(inSameHostLink(d))continue;if(isContent(d))continue;if(d.querySelector&&d.querySelector('video'))continue;if",
        "(isClickTrap(d)){kill(d);continue;}if(isChallenge(d))continue;if(isModal(d)||isOpaqueModal(d)||isBigOverlay(d)",
        ")d.style.display='none';}var all=document.querySelectorAll('div,section,aside,ins,iframe');for(var m=0;m<all.l",
        "ength;m++){var el=all[m];if(!el.isConnected)continue;if(inAgeGate(el))continue;if(isChallenge(el))continue;if(",
        "inSameHostLink(el))continue;if(isContent(el))continue;if(el.querySelector&&el.querySelector('video'))continue;",
        "var hr=null;if(isAdIframe(el))hr='isAdIframe';else if(isFixedBottomIframe(el))hr='isFixedBottomIframe';else if",
        "(isBottomBar(el))hr='isBottomBar';else if(isBottomAd(el))hr='isBottomAd';else if(isBottomFlowAd(el))hr='isBott",
        "omFlowAd';else if(isFloatAd(el))hr='isFloatAd';else if(isAdLinkGrid(el))hr='isAdLinkGrid';else if(isFixedBotto",
        "mAd(el))hr='isFixedBottomAd';else if(isGenericBottomBanner(el))hr='isGenericBottomBanner';else if(isFixedBottomImageCardRow(el))hr='isFixedBottomImageCardRow';else if(isFixedBotto",
        "mImageAd(el))hr='isFixedBottomImageAd';else if(isGenericMidBanner(el))hr='isGenericMidBanner';else if(isFixedT",
        "opBanner(el))hr='isFixedTopBanner';else if(isBottomPornAd(el))hr='isBottomPornAd';if(hr){noteHide(hr,el);el.st",
                "yle.display=\'none\';}}",
                "var bare=document.querySelectorAll(\'img,video\');for(var bm=0;bm<bare.length;bm++){var be=bare[bm];if(!be.isCon",
                "nected)continue;if(isFixedBottomBareMedia(be)){noteHide(\'isFixedBottomBareMedia\',be);be.style.display=\'none\';}",
                "}",
                "hideSpriteBarGroup();var links=document.querySelectorAll(\'a[href]\');for(var q=0;q<links.l",
        "ength;q++){var lk=lin",
        "ks[q];if(!lk.isConnected)continue;if(inAgeGate(lk))continue;if(isAdImageLink(lk)||isBottomPornAd(lk)){lk.style",
        ".display='none';continue;}if(foreign(lk.href)||isAdUrl(lk.href)){var fbr=fixedBottomAdRoot(lk);if(fbr){noteHid",
        "e('isFixedBottomAd',fbr);fbr.style.display='none';}}}var cbs=document.querySelectorAll('[class*=\"close\"],[id*=",
        "\"close\"]');for(var n=0;n<cbs.length;n++){var cb=cbs[n];if(!isAdClose((cb.className&&cb.className.baseVal!==und",
        "efined?cb.className.baseVal:cb.className||'')+' '+(cb.id||'')))continue;var slot=cb.closest('div,section,aside",
        ",ins');if(slot&&slot!==document.body&&!slot.querySelector('video'))slot.style.display='none';}hideMixedForeign",
        "Cards();hideNoidDiv();hideHeadingPromoLinks();hideMyavliveIframes();}function hideMyavliveIframes(){var frs=do",
        "cument.querySelectorAll('iframe');for(var i=0;i<frs.length;i++){var fr=frs[i];if(!fr.isConnected)continue;if(i",
        "sChallenge(fr))continue;if(inAgeGate(fr))continue;var s=(fr.src||fr.getAttribute('src')||'').toLowerCase();if(",
        "s.indexOf('myavlive')<0&&s.indexOf('mavrtracktor')<0)continue;var p=fr.parentElement;noteHide('myavliveIframe'",
        ",fr);kill(fr);if(p)collapse(p);}}function isNavMenu(el){if(!el||!el.querySelectorAll)return false;if(el.tagNam",
        "e==='NAV')return true;if(el.closest){try{var nv=el.closest('nav,[role=\"navigation\"]');if(nv)return true;}catch",
        "(e){}}var as=el.querySelectorAll('a[href]');if(as.length<3)return false;var same=0;for(var i=0;i<as.length;i++",
        "){if(!foreign(as[i].href))same++;}return same>=3&&same/as.length>0.6;}\nfunction isModal(el){if(!el||el===docum",
        "ent.body)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isNavMenu(el))return fa",
        "lse;if(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.",
        "position!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.6||r.height",
        "<window.innerHeight*0.35)return false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){if(is",
        "AdUrl(as[i].href))return true;}var ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s=ims[j].c",
        "urrentSrc||ims[j].src||'';if(s&&isAdUrl(s))return true;}return false;}function isBottomBar(el){if(!el||el===do",
        "cument.body)return false;if(el.querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.positi",
        "on!=='fixed'&&cs.position!=='sticky'&&cs.position!=='absolute')return false;var r=el.getBoundingClientRect();i",
        "f(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<window.inn",
        "erHeight*0.5)return false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){if(isAdUrl(as[i].",
        "href))return true;}var ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s=ims[j].currentSrc||i",
        "ms[j].src||'';if(s&&isAdUrl(s))return true;}var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++",
        "){var f=frs[k].src||'';if(f&&isAdUrl(f))return true;}if(el.tagName==='IFRAME'){var s2=el.src||'';if(s2&&isAdUr",
        "l(s2))return true;}return false;}function isBottomAd(el){if(!el||el===document.body)return false;if(isAgeGate(",
        "el))return false;if(el.querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.position!=='fi",
        "xed'&&cs.position!=='sticky'&&cs.position!=='absolute')return false;var r=el.getBoundingClientRect();if(r.widt",
        "h<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight",
        "*0.5)return false;if(r.height<60&&r.height<window.innerHeight*0.08)return false;if(el.tagName==='IFRAME')retur",
        "n true;var im=el.querySelector('img');var vd=el.querySelector('video');var bg=cs.backgroundImage||'';var bgF=f",
        "alse;if(bg.indexOf('url(')>=0){var bm=bg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(bm&&bm[1]&&isAdUrl(bm[1]))bgF=",
        "true;}var fl=false;var fas=el.querySelectorAll('a[href]');for(var fi=0;fi<fas.length;fi++){if(isAdUrl(fas[fi].",
        "href)){fl=true;break;}}if(!im&&!vd&&!bgF&&!fl)return false;if(cs.position==='absolute'){var fa=false;var abs=e",
        "l.querySelectorAll('a[href]');for(var ai=0;ai<abs.length;ai++){if(isAdUrl(abs[ai].href)){fa=true;break;}}var f",
        "src='';if(im)fsrc=im.currentSrc||im.src||'';if(!vd&&!fa&&!(fsrc&&isAdUrl(fsrc)))return false;}return true;}fun",
        "ction pruneExtraVideos(){var players=document.querySelectorAll('div.dplayer');for(var i=0;i<players.length;i++",
        "){var d=players[i];if(!d.isConnected)continue;var vs=d.querySelectorAll('video');if(vs.length<2)continue;var k",
        "eep=null;for(var k=0;k<vs.length;k++){if(!vs[k].classList.contains('ads_video')){keep=vs[k];break;}}if(!keep)k",
        "eep=vs[0];for(var j=0;j<vs.length;j++){var av=vs[j];if(av===keep)continue;var wp=av.parentElement;if(av.parent",
        "Node)av.parentNode.removeChild(av);if(wp&&wp!==d&&wp.children.length===0&&wp.parentNode)wp.parentNode.removeCh",
        "ild(wp);}}}function isBottomFlowAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;i",
        "f(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position==='fixed'||cs.posit",
        "ion==='sticky')return false;var r=el.getBoundingClientRect();if(r.width>=window.innerWidth*0.9&&r.height>=wind",
        "ow.innerHeight*0.9)return false;var _ims0=el.querySelectorAll('img');if(_ims0.length>=10)return false;var _cx=",
        "window.innerWidth/2,_cy=window.innerHeight/2;if(r.left<=_cx&&_cx<=r.right&&r.top<=_cy&&_cy<=r.bottom)return fa",
        "lse;var docBot=r.top+(window.scrollY||0)+r.height;var dh=document.documentElement.scrollHeight||0;if(docBot<dh",
        "-400)return false;if(r.width<window.innerWidth*0.5||r.height<80)return false;var cn=((el.className&&el.classNa",
        "me.baseVal!==undefined)?el.className.baseVal:(el.className||''))+' '+(el.id||'');if(/footer|copyright/i.test(c",
        "n))return false;var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[",
        "i].src||'';if(isOpaqueImg(s))return true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){v",
        "ar a=as[j];if(isAdUrl(a.href)&&a.querySelector('img'))return true;}for(var j2=0;j2<as.length;j2++){var a2=as[j",
        "2];if(foreign(a2.href)){var im2=a2.querySelector('img');if(im2)return true;}}var fbg=cs.backgroundImage||'';if",
        "(fbg.indexOf('url(')>=0){var fbm=fbg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(fbm&&fbm[1]&&isAdUrl(fbm[1]))retur",
        "n true;}return false;}function isBottomPornAd(el){if(!el||el===document.body)return false;if(inAgeGate(el))ret",
        "urn false;if(isContent(el))return false;var cs=getComputedStyle(el);var r=el.getBoundingClientRect();var isFix",
        "ed=cs.position==='fixed'||cs.position==='sticky'||cs.position==='absolute';if(isFixed){if(r.bottom<window.inne",
        "rHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;}else{if(r.top<window.innerHeight*0.7)retu",
        "rn false;}if(r.width<window.innerWidth*0.4)return false;if(r.height<40||r.height>300)return false;var t=(el.in",
        "nerText||'');if(!/(约炮|兼职上门|国产自拍|性感少妇|裸聊)/.test(t))return false;if(el.querySelector('img,video'))return true;va",
        "r bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)return true;var kids=el.querySelectorAll('*');for(var k=0",
        ";k<kids.length;k++){var kb=getComputedStyle(kids[k]).backgroundImage||'';if(kb.indexOf('url(')>=0)return true;",
        "}return false;}\nfunction isAdImageLink(el){if(!el||el.tagName!=='A')return false;if(inAgeGate(el))return false",
        ";if(isContent(el))return false;var href=el.getAttribute('href')||'';if(!href)return false;if(!foreign(href)&&!",
        "isAdUrl(href))return false;var txt=(el.innerText||'').trim();if(txt.length>8)return false;var cs=getComputedSt",
        "yle(el);var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)return true;var im=el.querySelector('img');if(i",
        "m){var ir=im.getBoundingClientRect();if(ir.width>=100&&ir.height>=60)return true;var src=im.currentSrc||im.src",
        "||'';if(src&&(foreign(src)||isAdUrl(src)))return true;}return false;}\nfunction isAdIframe(el){if(!el||el.tagNa",
        "me!=='IFRAME')return false;if(isAgeGate(el))return false;if(inAgeGate(el))return false;var src=el.src||el.getA",
        "ttribute('src')||'';if(!src)return false;var h='';try{h=new URL(src,location.href).hostname.toLowerCase();}cat",
        "ch(e){}if(h.indexOf('cloudflare')>=0||h.indexOf('hcaptcha')>=0||h.indexOf('recaptcha')>=0)return false;var r=e",
        "l.getBoundingClientRect();if(r.width<100||r.height<40)return false;var ar=r.width/Math.max(1,r.height);if(r.wi",
        "dth>=300&&r.height>=150&&ar>1.5&&ar<1.9)return false;if(el.hasAttribute&&el.hasAttribute('allowfullscreen')&&r",
        ".width>=300&&r.height>=150)return false;if(isAdUrl(src))return true;if(ar>=3)return true;if(!foreign(src)&&!is",
        "AdUrl(src))return false;if(ar>=1.0&&ar<=1.4&&r.width>=250&&r.height>=200)return true;return false;}function is",
        "FixedBottomIframe(el){if(!el||el.tagName!=='IFRAME')return false;if(isAgeGate(el))return false;if(isContent(el",
        "))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='",
        "sticky')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.botto",
        "m<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60||r.height>250)",
        "return false;var ar=r.width/Math.max(1,r.height);if(r.width>=300&&r.height>=150&&ar>1.5&&ar<1.9)return false;i",
        "f(el.hasAttribute&&el.hasAttribute('allowfullscreen')&&r.width>=300&&r.height>=150)return false;var src=el.src",
        "||el.getAttribute('src')||'';var h='';try{h=new URL(src,location.href).hostname.toLowerCase();}catch(e){}if(h.",
        "indexOf('cloudflare')>=0||h.indexOf('hcaptcha')>=0||h.indexOf('recaptcha')>=0)return false;if(src&&(foreign(sr",
        "c)||isAdUrl(src)))return true;if(!src)return true;if(ar>=3)return true;return false;}\nfunction fixedBottomAdRo",
        "ot(el){if(!el)return null;if(isAgeGate(el))return null;if(inAgeGate(el))return null;var cur=el,depth=0;while(c",
        "ur&&cur!==document.body&&depth<4){if(isFixedBottomAd(cur))return cur;cur=cur.parentElement;depth++;}return nul",
        "l;}\nfunction isFixedBottomAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(isCo",
        "ntent(el))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.posi",
        "tion!=='sticky')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if",
        "(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60||r.hei",
        "ght>250)return false;var hasForeign=false;if(el.tagName==='IFRAME'){var fs=el.src||'';if(fs&&(foreign(fs)||isA",
        "dUrl(fs)))hasForeign=true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j]",
        ".href)){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i+",
        "+){var s=ims[i].currentSrc||ims[i].src||'';if(s&&(foreign(s)||isAdUrl(s))){hasForeign=true;break;}}}if(!hasFor",
        "eign){var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&(foreign(",
        "f)||isAdUrl(f))){hasForeign=true;break;}}}if(!hasForeign){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>",
        "=0){var bm=bg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(bm&&bm[1]&&(foreign(bm[1])||isAdUrl(bm[1])))hasForeign=tr",
        "ue;}}if(!hasForeign)return false;var hasVisual=false;if(el.tagName==='IFRAME')hasVisual=true;else if(el.queryS",
        "elector('img'))hasVisual=true;if(!hasVisual){var bg2=cs.backgroundImage||'';if(bg2.indexOf('url(')>=0)hasVisua",
        "l=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getComputedS",
        "tyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el.querySe",
        "lector('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nfunction isFixedBottomImageAd(el){if(",
        "!el||el===document.body)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isConten",
        "t(el))return false;if(isNavMenu(el))return false;if(el.querySelector&&el.querySelector('video'))return false;v",
        "ar cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')return f",
        "alse;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHe",
        "ight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60)return false;var",
        " hasVisual=false;if(el.tagName==='IMG'||el.tagName==='IFRAME')hasVisual=true;else if(el.querySelector&&el.quer",
        "ySelector('img,iframe'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)h",
        "asVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k=0;k<kids.length;k++){var kb=getCompu",
        "tedStyle(kids[k]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual)return f",
                "alse;return true;}\n",
                "function isFixedBottomBareMedia(el){if(!el||el===document.body)return false;if(el.tagName!==\'IMG\'&&el.tagName!",
                "==\'VIDEO\')return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return f",
                "alse;if(isNavMenu(el))return false;if(el.closest){try{if(el.closest(\'.dplayer\'))return false;}catch(e){}}var c",
                "s=getComputedStyle(el);if(cs.position!==\'fixed\'&&cs.position!==\'absolute\'&&cs.position!==\'sticky\')return false",
                ";var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight",
                "-2)return false;if(r.top<window.innerHeight*0.5)return false;var s=\'\';try{s=el.currentSrc||el.src||\'\';}catch(e",
                "){s=el.src||\'\';}var cross=false;if(s&&(foreign(s)||isAdUrl(s)))cross=true;var click=false;if(el.getAttribute){",
                "try{if(el.getAttribute(\'onclick\'))click=true;}catch(e2){}}if(!click){try{if(el.onclick)click=true;}catch(e3){}",
                "}if(!cross&&!click)return false;return true;}\n",
                "function isImageCard(c){if(!c||c.nodeType!==1)return false;if(c.tagName===\'IMG\')return fals",
        "e;var hasImg=false;var ch=c.children;for(var i=0;i<ch.length;i++){if(ch[i].tagName===\'IMG\'){hasImg=true;break;",
        "}}if(!hasImg){var b=getComputedStyle(c).backgroundImage||\'\';if(b.indexOf(\'url(\')>=0)hasImg=true;}if(!hasImg)re",
        "turn false;return txt(c).length<=16;}\nfunction isFixedBottomImageCardRow(el){if(!el||el===document.body)return",
        " false;if(isAgeGate(el)||inAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return fa",
        "lse;if(el.tagName===\'NAV\')return false;if(el.closest){try{if(el.closest(\'nav,[role=\"navigation\"]\'))return fals",
        "e;}catch(e){}}var isClose=function(n){if(!n||n.nodeType!==1)return false;var t=((n.textContent||\'\')+(n.value||",
        "\'\')).replace(/\\s+/g,\'\');if(t===\'\\u00d7\'||t===\'\\u2715\'||t===\'\\u2716\'||t===\'\\u2573\'||t===\'x\'||t===\'X\'||t===\'\\u51",
        "73\\u95ed\')return true;var s=((n.id||\'\')+\' \'+((n.className&&n.className.baseVal!==undefined)?n.className.baseVa",
        "l:(n.className||\'\'))+\' \'+(n.getAttribute?n.getAttribute(\'aria-label\')||\'\':\'\')).toLowerCase();if(s.indexOf(\'clo",
        "se\')>=0||s.indexOf(\'dismiss\')>=0)return true;return false;};if(el.querySelector&&el.querySelector(\'video,.dpla",
        "yer\'))return false;if(el.querySelector){if(el.querySelector(\'form,select,textarea\'))return false;var bs=el.que",
        "rySelectorAll(\'button,input\');for(var bi=0;bi<bs.length;bi++){if(bs[bi].tagName===\'INPUT\'&&(bs[bi].type||\'\').t",
        "oLowerCase()===\'hidden\')continue;if(!isClose(bs[bi]))return false;}}var an=el,depth=0,found=false;while(an&&an",
        "!==document.body&&depth<=2){var cs=getComputedStyle(an);if(cs.position===\'fixed\'||cs.position===\'absolute\'||cs",
        ".position===\'sticky\'){found=true;break;}an=an.parentElement;depth++;}if(!found)return false;var r=an.getBoundi",
        "ngClientRect();if(r.width<window.innerWidth*0.02||r.height<window.innerHeight*0.01)return false;if(r.bottom<wi",
        "ndow.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;var as=el.querySelectorAll(\'a[hre",
        "f]\');for(var i=0;i<as.length;i++){var h=as[i].getAttribute(\'href\')||\'\';if(isPlaceholderHref(h))continue;if(!fo",
        "reign(as[i].href))return false;}var cards=0;var kids=el.children;for(var k=0;k<kids.length;k++){if(kids[k].tag",
        "Name===\'BUTTON\'||kids[k].tagName===\'INPUT\')continue;if(isClose(kids[k]))continue;if(isImageCard(kids[k])){card",
        "s++;continue;}var gcs=kids[k].children;for(var g=0;g<gcs.length;g++){if(gcs[g].tagName===\'BUTTON\'||gcs[g].tagN",
        "ame===\'INPUT\')continue;if(isClose(gcs[g]))continue;if(isImageCard(gcs[g]))cards++;}}if(cards<2)return false;re",
        "turn true;}\nfunction hideSpriteBarGroup(){var hidden=0;var isSpriteLike=function(el){if(!el||el.nodeType!==1||",
        "el===document.body)return false;var tag=el.tagName;if(tag===\'IMG\'||tag===\'VIDEO\'||tag===\'BUTTON\'||tag===\'INPUT",
        "\'||tag===\'FORM\'||tag===\'SELECT\'||tag===\'TEXTAREA\'||tag===\'A\'||tag===\'IFRAME\'||tag===\'SCRIPT\'||tag===\'STYLE\'||t",
        "ag===\'NAV\')return false;try{if(isContent(el))return false;if(isAgeGate(el)||inAgeGate(el))return false;if(isCh",
        "allenge(el))return false;if(el.closest){var nv=el.closest(\'nav,[role=\"navigation\"]\');if(nv)return false;}}catc",
        "h(e){return false;}if(txt(el).length>0)return false;if(el.children&&el.children.length>0)return false;if(el.qu",
        "erySelector&&el.querySelector(\'a[href],img,video,button,input,form,select,textarea,iframe\'))return false;var c",
        "s;try{cs=getComputedStyle(el);}catch(e2){return false;}if(!cs)return false;var pos=cs.position;if(pos!==\'fixed",
        "\'&&pos!==\'absolute\'&&pos!==\'sticky\')return false;var bg=cs.backgroundImage||\'\';if(bg.indexOf(\'url(\')<0)return ",
        "false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.02||r.height<window.innerHeight*0.01)ret",
        "urn false;return true;};var isSeed=function(el){if(!isSpriteLike(el))return false;var r=el.getBoundingClientRe",
        "ct();if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;return true;};",
        "var fire=function(el,type){try{el.dispatchEvent(new Event(type));return;}catch(e){}try{var ev=document.createE",
        "vent(\'Event\');ev.initEvent(type,false,false);el.dispatchEvent(ev);}catch(e2){}};var removeSpriteStyle=function",
        "(tag){var low=tag.toLowerCase();var styles=document.querySelectorAll(\'style\');for(var s=0;s<styles.length;s++)",
        "{var st=styles[s];var tx=st.textContent||\'\';if(tx.length>2000)continue;var lx=tx.toLowerCase();if(lx.indexOf(l",
        "ow)<0)continue;if(lx.indexOf(\'background-image\')<0)continue;if(lx.indexOf(\'url(\')<0)continue;if(st.parentNode)",
        "st.parentNode.removeChild(st);}};try{var body=document.body;if(!body)return 0;var kids=body.children;if(!kids|",
        "|kids.length===0)return 0;var groups={};var order=[];var i,j,k;for(i=0;i<kids.length;i++){var t=kids[i].tagNam",
        "e;if(!groups[t]){groups[t]=[];order.push(t);}groups[t].push(kids[i]);}for(var g=0;g<order.length;g++){var tagG",
        "=order[g];var members=groups[tagG];var likes=[];var seeds=0;var top=1000000000;for(j=0;j<members.length;j++){v",
        "ar m=members[j];if(isSpriteLike(m)){likes.push(m);var rr=m.getBoundingClientRect();if(rr.top<top)top=rr.top;if",
        "(isSeed(m))seeds++;}}if(seeds<4||likes.length<4)continue;var barH=window.innerHeight-top;var closeEl=null;var ",
        "tol=window.innerHeight*0.05;for(k=0;k<kids.length;k++){var c=kids[k];if(c.tagName===tagG)continue;if(groups[c.",
        "tagName].length!==1)continue;var ct=c.tagName;if(ct===\'IMG\'||ct===\'VIDEO\'||ct===\'BUTTON\'||ct===\'INPUT\'||ct===\'",
        "FORM\'||ct===\'SELECT\'||ct===\'TEXTAREA\'||ct===\'A\'||ct===\'IFRAME\'||ct===\'SCRIPT\'||ct===\'STYLE\'||ct===\'NAV\')contin",
        "ue;if(txt(c).length>0)continue;if(c.children&&c.children.length>0)continue;if(c.querySelector&&c.querySelector",
        "(\'a[href],img,video,button,input,form,select,textarea,iframe\'))continue;var bad=false;try{if(isContent(c)||isA",
        "geGate(c)||inAgeGate(c)||isChallenge(c))bad=true;}catch(e3){bad=true;}if(bad)continue;var cs2;try{cs2=getCompu",
        "tedStyle(c);}catch(e4){continue;}if(!cs2)continue;var pos2=cs2.position;if(pos2!==\'fixed\'&&pos2!==\'absolute\'&&",
        "pos2!==\'sticky\')continue;var bg2=cs2.backgroundImage||\'\';if(bg2.indexOf(\'url(\')<0)continue;var r2=c.getBoundin",
        "gClientRect();if(r2.width<=0||r2.height<=0)continue;if(r2.bottom>=window.innerHeight-2)continue;if(r2.bottom<t",
        "op-tol||r2.bottom>top+tol)continue;closeEl=c;break;}if(closeEl){fire(closeEl,\'touchstart\');if(closeEl.isConnec",
        "ted)fire(closeEl,\'click\');}for(j=0;j<likes.length;j++){likes[j].style.display=\'none\';hidden++;}if(likes.length",
        ">0)noteHide(\'hideSpriteBarGroup\',likes[0]);removeSpriteStyle(tagG);var pb=body.style.paddingBottom||\'\';if(pb){",
        "var n=parseInt(pb,10);if(!isNaN(n)&&Math.abs(n-barH)<=2)body.style.paddingBottom=\'\';}}}catch(e){}return hidden",
        ";}\nfunction isGenericBottomBanner(el){if(!el||el===document.body)return false;if(isAgeGate(el)",
        ")return false;if(isChallenge(el))return false;if(isContent(el))return false;if(isNavMenu(el))return false;if(e",
        "l.querySelector&&el.querySelector('video'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&",
        "cs.position!=='absolute'&&cs.position!=='sticky')return false;var r=el.getBoundingClientRect();if(r.width<wind",
        "ow.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-60)return false;if(r.top<window.innerHeight*0.5)",
        "return false;if(r.height<60||r.height>250)return false;var hasForeign=false;if(el.tagName==='IFRAME'){var fs=e",
        "l.src||'';if(fs&&foreign(fs))hasForeign=true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j+",
        "+){if(foreign(as[j].href)){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var ",
        "i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';if(s&&foreign(s)){hasForeign=true;break;}}}if(!h",
        "asForeign){var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&fore",
        "ign(f)){hasForeign=true;break;}}}if(!hasForeign)return false;var hasVisual=false;if(el.tagName==='IFRAME')hasV",
        "isual=true;else if(el.querySelector('img'))hasVisual=true;if(!hasVisual){var bg2=cs.backgroundImage||'';if(bg2",
        ".indexOf('url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.leng",
        "th;k2++){var kb=getComputedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;",
        "}}}if(!hasVisual&&el.querySelector('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nvar __mus",
        "eSameHostStats=null;function sameHostLinkStats(){if(__museSameHostStats)return __museSameHostStats;var as=docu",
        "ment.querySelectorAll('a[href]');var same=0,total=0;for(var i=0;i<as.length;i++){var h=as[i].getAttribute('hre",
        "f')||'';if(isPlaceholderHref(h))continue;total++;if(!foreign(as[i].href))same++;}__museSameHostStats={same:sam",
        "e,total:total};return __museSameHostStats;}\nfunction isGenericMidBanner(el){if(!el||el===document.body)return ",
        "false;var __st=sameHostLinkStats();if(__st.same<2||__st.same*10<__st.total)return false;if(isAgeGate(el))retur",
        "n false;if(isChallenge(el))return false;if(isContent(el))return false;if(isNavMenu(el))return false;if(el.quer",
        "ySelector&&el.querySelector('video'))return false;if(el.querySelector&&el.querySelector('iframe'))return false",
        ";var cs=getComputedStyle(el);if(cs.position==='fixed'||cs.position==='sticky')return false;var r=el.getBoundin",
        "gClientRect();if(r.width<window.innerWidth*0.02||r.height<window.innerHeight*0.01)return false;var pe=el.paren",
        "tElement;if(!pe)return false;var pr=pe.getBoundingClientRect();if(r.width<pr.width*0.8)return false;var gp=pe.",
        "parentElement;if(gp){var gr=gp.getBoundingClientRect();if(pr.width<gr.width*0.5)return false;}var hasForeign=f",
        "alse;var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){var h=as[j].getAttribute('href')||'';i",
        "f(isPlaceholderHref(h))continue;if(foreign(as[j].href))hasForeign=true;else return false;}if(!hasForeign)retur",
        "n false;var hasVisual=false;if(el.querySelector('img'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage",
        "||'';if(bg.indexOf('url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2",
        "<kids.length;k2++){var kb=getComputedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=t",
        "rue;break;}}}if(!hasVisual)return false;return true;}\nfunction isFixedTopBanner(el){if(!el||el===document.body",
        ")return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return false;if(i",
        "sNavMenu(el))return false;if(el.querySelector&&el.querySelector('video'))return false;var cs=getComputedStyle(",
        "el);if(cs.position!=='fixed')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)re",
        "turn false;if(r.bottom>=window.innerHeight-60)return false;if(r.height<60||r.height>250)return false;var hasFo",
        "reign=false;var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j].href)){hasFore",
        "ign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i]",
        ".currentSrc||ims[i].src||'';if(s&&foreign(s)){hasForeign=true;break;}}}if(!hasForeign)return false;var hasVisu",
        "al=false;if(el.querySelector('img'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||'';if(bg.indexOf(",
        "'url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){",
        "var kb=getComputedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!ha",
        "sVisual&&el.querySelector('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nfunction isAdLinkG",
        "rid(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(isContent(el))return false;if",
        "(el.querySelector('video,iframe'))return false;if(el.querySelector('p'))return false;var as=el.querySelectorAl",
        "l('a[href]');if(as.length<8)return false;var total=0,foreignCount=0,shortText=0;for(var i=0;i<as.length;i++){v",
        "ar a=as[i];var href=a.getAttribute('href')||'';if(!href)continue;total++;if(foreign(href))foreignCount++;var t",
        "=txt(a);if(t.length>0&&t.length<=6)shortText++;}if(total<8)return false;if(foreignCount<total*0.7)return false",
        ";if(shortText<total*0.7)return false;return true;}\nfunction isBannerAd(a){if(!a||a===document.body)return fals",
        "e;if(inAgeGate(a))return false;if(isContent(a))return false;var href=a.getAttribute('href')||'';if(!href)retur",
        "n false;if(!foreign(href))return false;var im=a.querySelector('img');if(!im)return false;if(txt(a).length>8)re",
        "turn false;var r=im.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.height<20)retur",
        "n false;return true;}\nfunction isFloatAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return f",
        "alse;if(el.querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.pos",
        "ition!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width>=window.innerWidth*0.6)return fals",
        "e;if(r.height>=window.innerHeight*0.4)return false;if(r.width<20||r.height<20)return false;if(r.bottom<window.",
        "innerHeight-120)return false;if(r.top<window.innerHeight*0.4)return false;var hasClose=false;var cbs=el.queryS",
        "electorAll('[class*=\"close\"],[id*=\"close\"]');if(cbs.length>0)hasClose=true;if(!hasClose){var t0=txt(el);if(t0.",
        "indexOf('\\u00d7')>=0||t0.indexOf('\\u2715')>=0||t0.indexOf('\\u2573')>=0)hasClose=true;}if(!hasClose)return fals",
        "e;var hasCarousel=false;var t=txt(el);if(/\\d+\\s*\\/\\s*\\d+/.test(t))hasCarousel=true;if(!hasCarousel){var pg=el.",
        "querySelectorAll('[class*=\"pagination\"],[class*=\"swiper-pagination\"],[class*=\"dot\"],[class*=\"dots\"],[class*=\"i",
        "ndicator\"]');if(pg.length>0)hasCarousel=true;}var hasForeign=false;var as=el.querySelectorAll('a[href]');for(v",
        "ar i=0;i<as.length;i++){var h=as[i].href||'';if(h&&(foreign(h)||isAdUrl(h))){hasForeign=true;break;}}if(!hasFo",
        "reign){var ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s2=ims[j].currentSrc||ims[j].src||",
        "'';if(s2&&(foreign(s2)||isAdUrl(s2))){hasForeign=true;break;}}}if(!hasForeign&&el.tagName==='A'){var h0=el.hre",
        "f||'';if(h0&&(foreign(h0)||isAdUrl(h0)))hasForeign=true;}if(hasForeign||hasCarousel)return true;return false;}",
        "function isAdClose(cls){var t=(' '+cls+' ').toLowerCase().split(/[\\s_-]+/);var a=false,c=false;for(var i=0;i<t",
        ".length;i++){if(t[i]==='ad'||t[i]==='ads')a=true;if(t[i].indexOf('close')>=0)c=true;}return a&&c;}window.__mus",
        "eLastTap=0;window.__museTapImg=false;document.addEventListener('click',function(e){window.__museLastTap=Date.n",
        "ow();var t=e?e.target:null;var ti=false;if(t&&t.closest){if(t.closest('img'))ti=true;else{var a=t.closest('a')",
        ";if(a&&a.querySelector('img'))ti=true;}}window.__museTapImg=ti;},true);function recentTap(){return (Date.now()",
        "-(window.__museLastTap||0))<1500;}function isClickTrap(el){if(!el||el===document.body)return false;if(el.query",
        "Selector('video,iframe,img'))return false;if(txt(el).length>0)return false;var cs=getComputedStyle(el);if(cs.p",
        "osition!=='fixed')return false;var op=parseFloat(cs.opacity);if(!(op<0.05))return false;var r=el.getBoundingCl",
        "ientRect();if(r.width<window.innerWidth*0.02&&r.height<window.innerHeight*0.02)return false;return true;}funct",
        "ion isOpaqueModal(el){if(!el||el===document.body)return false;if(el.__museLb)return false;if(isAgeGate(el))ret",
        "urn false;if(isChallenge(el))return false;if(isNavMenu(el))return false;if(el.querySelector('video,iframe'))re",
        "turn false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')return false;var r=e",
        "l.getBoundingClientRect();if(r.width<window.innerWidth*0.4&&r.height<window.innerHeight*0.5)return false;var i",
        "ms=el.querySelectorAll('img');var hasOpaque=false;for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i",
        "].src||'';if(isOpaqueImg(s)){hasOpaque=true;break;}}if(!hasOpaque)return false;if(recentTap()&&window.__museTa",
        "pImg){el.__museLb=1;return false;}return true;}function isBigOverlay(el){if(!el||el===document.body)return fal",
        "se;if(el.__museLb)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isNavMenu(el))",
        "return false;if(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fi",
        "xed'&&cs.position!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.8|",
        "|r.height<window.innerHeight*0.7)return false;if(!el.querySelector('img'))return false;if(recentTap()&&window.",
        "__museTapImg){el.__museLb=1;return false;}return true;}function preRollBox(cd){var n=cd;while(n&&n!==document.",
        "body){if(n.querySelector&&n.querySelector('video'))return n;n=n.parentElement;}return cd.parentElement||cd;}fu",
        "nction adOverlay(cd,box){if(!box||!cd)return null;var v=box.querySelector('video');if(!v)return null;var cur=c",
        "d;while(cur.parentElement&&cur.parentElement!==box){cur=cur.parentElement;}if(cur===cd)return null;if(cur.cont",
        "ains&&cur.contains(v))return null;return cur;}function clickNeutral(el){if(!el)return;var oo=window.open;try{w",
        "indow.open=function(){return null;};el.click();}catch(e){}try{window.open=oo;}catch(e){}}function killPreRollO",
        "ne(cd){if(!cd||!cd.isConnected)return;var box=preRollBox(cd);if(!box)return;var ov=adOverlay(cd,box);var scope",
        "=ov||box;var img=scope.querySelector('.dplayer-pre-img');if(!img){var ims=scope.querySelectorAll('img'),best=n",
        "ull,ba=0;for(var i=0;i<ims.length;i++){var r=ims[i].getBoundingClientRect(),a=r.width*r.height;if(a>ba){ba=a;b",
        "est=ims[i];}}img=best;}if(img)clickNeutral(img);var cd2=cd,box2=box,ov2=ov,img2=img;setTimeout(function(){if(!",
        "cd2.isConnected)return;if(ov2&&ov2.parentNode){ov2.parentNode.removeChild(ov2);}else{var ns=box2.querySelector",
        "All('.dplayer-pre-bg,.dplayer-pre-img,.dplayer-pre-countdown');for(var k=0;k<ns.length;k++){if(ns[k].parentNod",
        "e)ns[k].parentNode.removeChild(ns[k]);}var vs=box2.querySelectorAll('video');for(var vi=1;vi<vs.length;vi++){v",
        "ar av=vs[vi];var wp=av.parentElement;if(av.parentNode)av.parentNode.removeChild(av);if(wp&&wp!==box2&&wp.child",
        "ren.length===0&&wp.parentNode)wp.parentNode.removeChild(wp);}if(img2&&img2.parentNode){var ip=img2.parentEleme",
        "nt;img2.parentNode.removeChild(img2);if(ip&&ip!==box2&&ip.children.length===0&&ip.parentNode)ip.parentNode.rem",
        "oveChild(ip);}if(cd2.parentNode)cd2.parentNode.removeChild(cd2);}box2.classList.remove('dplayer-pre-playing');",
        "box2.classList.remove('dplayer-pre-clickable');var v=box2.querySelector('video');if(v){try{v.play();}catch(e){",
        "}}},200);}function killPreRoll(){var cds=document.querySelectorAll('.dplayer-pre-countdown');for(var i=0;i<cds",
        ".length;i++){killPreRollOne(cds[i]);}var els=document.querySelectorAll('div,span,button');for(var j=0;j<els.le",
        "ngth;j++){var el2=els[j];if(!el2.isConnected)continue;if(el2.querySelector('video,iframe'))continue;var t=txt(",
        "el2);if(t.length>16)continue;if(/^(广告)?(\\d{1,2}:\\d{2}|\\d+s?|\\d+秒)后(自动)?(关闭|关)$/.test(t)){killPreRollOne(el2);}",
        "else if(/^(跳过广告|SkipAd)$/i.test(t)){var h=el2.tagName==='A'?(el2.getAttribute('href')||''):'';if(el2.tagName!=",
        "='A'||h.indexOf('javascript:')===0)clickNeutral(el2);}}}function run(){__museSameHostStats=null;if(document.fu",
        "llscreenElement)return;try{sweep();}catch(e){}}run();setTimeout(run,1200);setTimeout(run,3500);var sweepTimer=",
        "setInterval(run,2000);setTimeout(function(){try{clearInterval(sweepTimer);}catch(e){}},300000);if(document.add",
        "EventListener){document.addEventListener('fullscreenchange',function(){if(!document.fullscreenElement){var vs=",
        "document.querySelectorAll('video');for(var i=0;i<vs.length;i++){var v=vs[i];if(v.style&&v.style.display==='non",
        "e')v.style.display='';var p=v.parentElement;while(p&&p!==document.body){if(p.style&&p.style.display==='none')p",
        ".style.display='';p=p.parentElement;}}setTimeout(function(){run();},300);}});}if(window.addEventListener){wind",
        "ow.addEventListener('pageshow',function(){run();});}if(document.addEventListener){document.addEventListener('v",
        "isibilitychange',function(){if(!document.hidden)run();});}var pending=null;var ob=new MutationObserver(functio",
        "n(){if(pending)return;pending=setTimeout(function(){pending=null;run();},100);});if(document.documentElement)o",
        "b.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style']});se",
        "tTimeout(function(){try{ob.disconnect();}catch(e){}},300000);var preOb=new MutationObserver(function(muts){for",
        "(var i=0;i<muts.length;i++){var ns=muts[i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeTy",
        "pe!==1)continue;var cd=null;if(n.classList&&n.classList.contains('dplayer-pre-countdown'))cd=n;else if(n.query",
        "Selector)cd=n.querySelector('.dplayer-pre-countdown');if(cd){killPreRollOne(cd);return;}}}});if(document.docum",
        "entElement)preOb.observe(document.documentElement,{childList:true,subtree:true});setTimeout(function(){try{pre",
        "Ob.disconnect();}catch(e){}},600000);/*push v29 R31-2*/var f1Ob=new MutationObserver(function(muts){for(var i=0;i<muts.length;i++){var ns=muts[i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;if(n.hasAttribute&&n.hasAttribute('data-cl-spot')){try{killClSpots();}catch(e){}}var l=n.tagName==='IFRAME'?[n]:(n.querySelectorAll?n.querySelectorAll('iframe'):[]);for(var k=0;k<l.length;k++){try{f1Hook(l[k]);f1Judge(l[k]);}catch(e){}}}}});if(document.documentElement)f1Ob.observe(document.documentElement,{childList:true,subtree:true});setTimeout(function(){try{f1Ob.disconnect();}catch(e){}},600000);})();"
    };
    private static final String CLEAN_JS;
    static {
        StringBuilder sb = new StringBuilder();
        for (String p : CLEAN_JS_PARTS) sb.append(p);
        CLEAN_JS = sb.toString();
    }
    // push v1: fuzi 多线探针（MacCMS 播放页全部线路 + 每线选集表）与页面 video 直取探针——逐字取自 fuzi BrowserActivity，不转录。
                                private static final String PROBE_JS = "(function(){function txt(el){return el?(el.innerText||'').trim():'';}function cleanName(t){return (t||'').replace(/[\\uE000-\\uF8FF]/g,'').replace(/\\s+/g,' ').trim();}function tabText(el){if(!el)return "
        + "'';var parts=[];var ns=el.childNodes;for(var i=0;i<ns.length;i++){var n=ns[i];if(n.nodeType===3)parts.push(n.textContent||'');else if(n.nodeType===1){var tg=n.tagName;var cl=n.getAttribute('class')||'';if(tg==='I'||/badge|num|count/i.test(cl))continue;parts.push(n.innerText||n.textContent||'');}}return "
        + "cleanName(parts.join(' '));}function cleanCand(L){var c=L.cand||'';if(!c)return c;var cnt=L.order.length;var m=c.match(/^(.*?)[-_\\s·]*?(\\d+)$/);if(m&&parseInt(m[2],10)===cnt&&m[1]&&m[1].trim())c=m[1].trim();return "
        + "c.replace(/[-_\\s·]+$/,'');}function picOf(){try{var m=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');if(m&&m.content){try{return new URL(m.content,location.href).href}catch(e){return "
        + "m.content}}var ps=['.vodpic img','.detail-pic img','.poster img','[class*=\"vodpic\"] img','[class*=\"poster\"] img','.detail .pic img','.video-info .pic img','.stui-content__thumb img','.myui-content__thumb "
        + "img','.vod-detail img','.detail-box img','.play-pic img','[class*=\"thumb\"] img','[class*=\"cover\"] img','.thumb img','.cover img'];for(var i=0;i<ps.length;i++){var im=document.querySelector(ps[i]);if(!im)continue;var "
        + "u=im.getAttribute('data-src')||im.getAttribute('data-original')||im.getAttribute('src')||'';if(u&&u.indexOf('data:')!==0){try{return new URL(u,location.href).href}catch(e){return u}}}var imgs=document.images;for(var "
        + "g=0;g<imgs.length&&g<6;g++){var gi=imgs[g];var gu=gi.getAttribute('data-src')||gi.getAttribute('src')||'';if(!gu||gu.indexOf('data:')===0)continue;var okN=(gi.naturalWidth||0)>=400&&(gi.naturalHeight||0)>=150;var "
        + "okC=(gi.clientWidth||0)>=300&&(gi.clientHeight||0)>=140;if(!okN&&!okC)continue;var ga=gi.closest?gi.closest('a'):null;if(ga){var gh=ga.getAttribute('href')||'';if(/^https?:\\/\\//i.test(gh)&&gh.indexOf(location.host)<0)continue;}try{return "
        + "new URL(gu,location.href).href}catch(e){return gu}}var bs=['.this-pic-bj','[class*=\"pic-bj\"]','[class*=\"backdrop\"]'];for(var j=0;j<bs.length;j++){var be=document.querySelector(bs[j]);if(!be)continue;var "
        + "bg='';try{bg=getComputedStyle(be).backgroundImage||''}catch(e){}var bm=/url\\([\"']?([^\"')]+)[\"']?\\)/.exec(bg);if(bm&&bm[1]&&bm[1].indexOf('data:')!==0){try{return new URL(bm[1],location.href).href}catch(e){return "
        + "bm[1]}}}}catch(e){}return '';}function epName(t){var m=t.match(/第\\s*\\d+\\s*[集期话回]|\\d+\\s*[集期]/);if(m)return m[0].replace(/\\s+/g,'');if(/^\\d{1,3}$/.test(t))return '第'+parseInt(t,10)+'集';return '';}var "
        + "STOP=/上一|下一|预告|播放|详情|首页|返回|收藏|分享|评论|清空|倒序|正序|排序|切换|展开|收起|更多|全部|点赞|纠错|下载|下集|上集/;function stopWord(t){return STOP.test(t)||/^(线路|来源)\\d{0,2}$/.test(t);}function isSourceTab(t){return /^播放源\\d{1,2}$/.test(t);}var "
        + "pm=location.pathname.match(/(\\d+)-(\\d+)-(\\d+)\\.html/);var vid=pm?pm[1]:'',sid=pm?pm[2]:'',curEp=pm?parseInt(pm[3],10):-1;if(!vid){var dv=location.pathname.match(/\\/(?:voddetail|vodplay|movie|detail|view|watch)\\/(\\d+)/);if(dv)vid=dv[1];}var "
        + "lines={},lineOrder=[];function lineOf(s){if(!lines[s]){lines[s]={sid:s,cand:'',candRaw:'',raws:[],eps:{},order:[],singles:[]};lineOrder.push(lines[s]);}return lines[s];}function put(s,aep,name,url,id,path){var "
        + "L=lineOf(s);var old=L.eps[aep];if(old){if(!old.clean&&name.clean){old.name=name.text;old.clean=true;old.url=url;}return;}var en={name:name.text,url:url,ep:aep,cur:(sid!==''&&s===sid&&aep===curEp),clean:name.clean,id:(id||''),path:(path||'')};L.eps[aep]=en;L.order.push(en);}function "
        + "nameOf(raw){var en=epName(raw);if(en)return{text:en,clean:true};return{text:raw,clean:false};}var links=document.querySelectorAll('a[href]');for(var j=0;j<links.length;j++){var a=links[j];var h=a.href||'';var "
        + "m=h.match(/(\\d+)-(\\d+)-(\\d+)\\.html($|\\?)/);if(!m)continue;if(vid&&m[1]!==vid)continue;var raw=txt(a);if(!raw)continue;var aep=parseInt(m[3],10);if(epName(raw)){put(m[2],aep,nameOf(raw),h);}else{var "
        + "craw=tabText(a);var inBox=false;try{inBox=!!(a.closest&&a.closest('.anthology-list-box,.anthology-list,.tab-pane,[id^=\"down\"],[id^=\"playlist\"],.playlist-box,[class*=\"playlist-box\"],.stui-content__playlist'));}catch(e){}if(inBox&&craw&&craw.length<=20&&craw.indexOf(':')<0&&!/^\\d+(\\.\\d+)?\\s*(小时|分钟|秒)$/.test(craw)&&!stopWord(craw)&&!/^[[【]/.test(craw)){var "
        + "L2=lineOf(m[2]);if(!L2.cand){L2.cand=craw;L2.candRaw=craw;}if(L2.singles.length<3)L2.singles.push({name:craw,url:h,ep:aep});}else{var L3=lineOf(m[2]);var rt=(raw||'').replace(/\\s+/g,' ').trim().slice(0,30);if(rt&&L3.raws.length<2&&L3.raws.indexOf(rt)<0)L3.raws.push(rt);}}}var "
        + "nids=document.querySelectorAll('[data-nid]');for(var q=0;q<nids.length;q++){var el2=nids[q];var nid=parseInt(el2.getAttribute('data-nid'),10);if(isNaN(nid))continue;var oc=el2.getAttribute('onclick')||(el2.parentElement?el2.parentElement.getAttribute('onclick')||'':'');var "
        + "om=oc.match(/play\\((\\d+)\\D+(\\d+)\\)/);var esid=om?om[1]:(el2.getAttribute('data-sid')||sid);if(!esid||!pm)continue;var raw2=txt(el2);var url2=location.origin+location.pathname.replace(/(\\d+)-(\\d+)-(\\d+)\\.html/,vid+'-'+esid+'-'+nid+'.html');if(!epName(raw2)){var "
        + "craw2=tabText(el2);if(craw2&&craw2.length<=20&&craw2.indexOf(':')<0&&!/^\\d+(\\.\\d+)?\\s*(小时|分钟|秒)$/.test(craw2)&&!stopWord(craw2)&&!/^[[【]/.test(craw2)){var L9=lineOf(esid);if(L9.singles.length<3)L9.singles.push({name:craw2,url:url2,ep:nid});}continue;}put(esid,nid,nameOf(raw2),url2);}for(var "
        + "si=0;si<lineOrder.length;si++){var SL=lineOrder[si];if(!vid||SL.order.length>0||!SL.singles.length)continue;var sg=SL.singles[0];for(var gi=0;gi<SL.singles.length;gi++){if(sid!==''&&SL.sid===sid&&SL.singles[gi].ep===curEp){sg=SL.singles[gi];break;}}put(SL.sid,sg.ep,{text:sg.name,clean:false},sg.url);}var "
        + "sels=['.play_source .active','.play-source .active','.player_source .active','.myui-player__source .active','.stui-player__source .active','.source-list .active','[class*=\"source\"] .active','[class*=\"source\"] "
        + ".on','[class*=\"source\"] .current'];var activeName='';for(var i=0;i<sels.length;i++){var e=document.querySelector(sels[i]);if(e){var t=tabText(e);if(t&&t.length<=24&&!epName(t)&&(!stopWord(t)||isSourceTab(t))){activeName=t;break;}}}var "
        + "sorted=lineOrder.filter(function(L){return L.order.length>0;});sorted.sort(function(a,b){return parseInt(a.sid,10)-parseInt(b.sid,10);});var tabNames=[],tabActive=-1,tabRaw=[],tabSel=-1,tabEls=[];(function(){var "
        + "ts=['.anthology-tab a','[class*=\"anthology-tab\"] a','[class*=\"anthology-tab\"] li','a[data-form]','[class*=\"source\"] a','[class*=\"source\"] li','[class*=\"from_list\"] li','[class*=\"from_list\"] "
        + "a','[class*=\"play_from\"] li','a[data-toggle=\"tab\"]'];var tabs=null;for(var i=0;i<ts.length;i++){var c=document.querySelectorAll(ts[i]);if(c.length>=2){tabs=c;tabSel=i;break;}}if(!tabs)return;for(var "
        + "j=0;j<tabs.length;j++){var el=tabs[j];var nm2=tabText(el);if(!nm2||nm2.length>24||epName(nm2)||(stopWord(nm2)&&!isSourceTab(nm2))||/^[[【]/.test(nm2)){if(nm2&&tabRaw.length<3)tabRaw.push(nm2.slice(0,24));nm2='';}tabNames.push(nm2);tabEls.push(el);var "
        + "cls=el.getAttribute('class')||'';if(tabActive<0&&/(^|\\s)(on|active|current|cur|selected)(\\s|$)/.test(cls))tabActive=j;}})();function boxSid(box){if(!box)return '';try{var as=box.querySelectorAll('a[href]');for(var "
        + "bi=0;bi<as.length;bi++){var hm=(as[bi].href||'').match(/(\\d+)-(\\d+)-(\\d+)\\.html($|\\?)/);if(hm&&(!vid||hm[1]===vid))return hm[2];}}catch(e){}return '';}var tabFor={};for(var ta=0;ta<tabEls.length;ta++){var "
        + "tn0=tabNames[ta]||'';if(!tn0)continue;var tel0=tabEls[ta];var taEl=(tel0.tagName==='A')?tel0:(tel0.querySelector?tel0.querySelector('a'):null);if(!taEl)continue;var tgt=(taEl.getAttribute('href')||taEl.getAttribute('data-target')||'').trim();if(!tgt||tgt.charAt(0)!=='#')continue;var "
        + "bx=null;try{bx=document.getElementById(tgt.slice(1));}catch(e){}var bs=boxSid(bx);if(bs&&!tabFor[bs])tabFor[bs]=tn0;}var boxSels=['.anthology-list-box','.tab-pane','[id^=\"down\"]','[id^=\"playlist\"]','.playlist-box','[class*=\"playlist-box\"]'];var "
        + "sortedSids={};for(var ss=0;ss<sorted.length;ss++)sortedSids[sorted[ss].sid]=1;for(var bsi=0;bsi<boxSels.length;bsi++){var boxes=null;try{boxes=document.querySelectorAll(boxSels[bsi]);}catch(e){continue;}if(!boxes||boxes.length!==tabNames.length)continue;var "
        + "bSids=[];var okB=true;var seenB={};for(var bj=0;bj<boxes.length;bj++){var bs2=boxSid(boxes[bj]);if(!bs2||!sortedSids[bs2]||seenB[bs2]){okB=false;break;}seenB[bs2]=1;bSids.push(bs2);}if(!okB)continue;for(var "
        + "bk=0;bk<tabNames.length;bk++){var nmB=tabNames[bk]||'';if(!nmB)continue;if(!tabFor[bSids[bk]])tabFor[bSids[bk]]=nmB;}break;}var genTabsFound=0,genEpsFound=0,genSidSeq=0,genTabList=[];function genTabName(el){if(!el)return "
        + "'';var parts=[];var ns=el.childNodes;for(var i=0;i<ns.length;i++){var n=ns[i];if(n.nodeType===3){parts.push(n.textContent||'');}else if(n.nodeType===1){var tg=n.tagName;var cl=n.getAttribute('class')||'';var "
        + "tx=(n.innerText||n.textContent||'').trim();if(tg==='I'||/(^|[\\s_\\-])(badge|num|count|gm)([\\s_\\-]|$)/i.test(cl))continue;if(tx==='广告'||/^\\d+\\s*集$/.test(tx))continue;parts.push(n.innerText||n.textContent||'');}}var "
        + "s=cleanName(parts.join(' '));s=s.replace(/广告/g,'').replace(/\\d+\\s*集/g,'').replace(/\\s+/g,' ').trim();return s.replace(/[-_\\s·]+$/,'');}function panelOfTab(el){var id='';try{id=el.getAttribute('aria-controls')||'';}catch(e){}var "
        + "tgt='';try{tgt=el.getAttribute('data-target')||el.getAttribute('href')||'';}catch(e){}if(tgt&&tgt.charAt(0)==='#')id=tgt.slice(1);if(id){try{var pp=document.getElementById(id);if(pp)return pp;}catch(e){}}try{var "
        + "par=el.parentElement;if(!par)return null;var sibs=par.querySelectorAll('[role=\"tab\"]');var idx=-1;for(var k=0;k<sibs.length;k++){if(sibs[k]===el){idx=k;break;}}if(idx<0)return null;var anc=par;for(var "
        + "d=0;d<4&&anc;d++){var panels=anc.querySelectorAll('[role=\"tabpanel\"]');if(panels.length===sibs.length)return panels[idx];anc=anc.parentElement;}}catch(e){}return null;}function genTabActive(el){try{var "
        + "cl=el.getAttribute('class')||'';return/(^|\\s)(active|current|cur|selected|on)(\\s|$)/.test(cl)||el.getAttribute('aria-selected')==='true';}catch(e){return false;}}function scanGenPanel(panel,sid,tabName,isActive,strict){if(!panel)return "
        + "0;var items=null;try{items=panel.querySelectorAll('button,a');}catch(e){return 0;}var cands=[];for(var i=0;i<items.length;i++){var el=items[i];var raw=cleanName(txt(el));if(!raw||raw.length>24)continue;if(stopWord(raw))continue;if(/^[[【]/.test(raw))continue;if(/^\\d+(\\.\\d+)?\\s*(小时|分钟|秒)$/.test(raw))continue;if(raw.indexOf(':')>=0)continue;var "
        + "rawHref='';try{rawHref=el.getAttribute('href')||'';}catch(e){}var href=rawHref;if(href){try{href=new URL(href,location.href).href;}catch(e){}}var en=epName(raw);var hm=rawHref.match(/(\\d+)-(\\d+)-(\\d+)\\.html/);if(strict&&!en&&!hm)continue;var "
        + "epNum=-1;if(en){var dm=raw.match(/(\\d+)/);if(dm)epNum=parseInt(dm[1],10);}else{if(hm)epNum=parseInt(hm[3],10);}var act=false;try{var cl2=el.getAttribute('class')||'';act=/(^|\\s)(active|current|cur|selected|on|yH)(\\s|$)/.test(cl2)||el.getAttribute('aria-current')==='true'||el.getAttribute('aria-selected')==='true';}catch(e){}var "
        + "eid='',epath='';try{if(el.tagName==='BUTTON'||!rawHref){eid=el.getAttribute('data-id')||'';epath=el.getAttribute('data-path')||'';}}catch(e){}cands.push({raw:raw,href:href,en:en,ep:epNum,act:act,id:eid,path:epath});}if(!cands.length)return "
        + "0;var L=lineOf(sid);if(tabName&&!L.genName)L.genName=tabName;if(isActive)L.genCur=1;var named=0;for(var j=0;j<cands.length;j++)if(cands[j].en)named++;if(named===0&&cands.length===1){var c0=cands[0];var "
        + "ep0=c0.ep>0?c0.ep:1;var u0=c0.href||location.href;var dup=false;for(var k=0;k<L.singles.length;k++)if(L.singles[k].name===c0.raw&&L.singles[k].url===u0)dup=true;if(!dup)L.singles.push({name:c0.raw,url:u0,ep:ep0});put(sid,ep0,{text:c0.raw,clean:false},u0,c0.id,c0.path);L.fromGeneric=1;if(c0.act){var "
        + "eo=L.eps[ep0];if(eo)eo.cur=true;}return 1;}var n=0;for(var j2=0;j2<cands.length;j2++){var c=cands[j2];if(!c.en||c.ep<0)continue;var u=c.href||location.href;put(sid,c.ep,{text:c.en,clean:true},u,c.id,c.path);L.fromGeneric=1;if(c.act){var "
        + "eo2=L.eps[c.ep];if(eo2)eo2.cur=true;}n++;}return n;}function fallbackScope(tab){var n=tab.parentElement;for(var d=0;d<3&&n;d++){try{var bs=n.querySelectorAll('button,a');for(var i=0;i<bs.length;i++){var "
        + "t2=cleanName(txt(bs[i]));if(epName(t2))return n;var h2=bs[i].getAttribute('href')||'';if(/(\\d+)-(\\d+)-(\\d+)\\.html/.test(h2))return n;}}catch(e){}n=n.parentElement;}return null;}(function(){var all=document.querySelectorAll('[role=\"tab\"],[data-toggle=\"tab\"]');var "
        + "groups=[];for(var i=0;i<all.length;i++){var t=all[i];var par=t.parentElement;if(!par)continue;var g=null;for(var gi=0;gi<groups.length;gi++)if(groups[gi].par===par){g=groups[gi];break;}if(!g){g={par:par,tabs:[]};groups.push(g);}g.tabs.push(t);}for(var "
        + "gi2=0;gi2<groups.length;gi2++){var grp=groups[gi2];if(grp.tabs.length<2)continue;var grpGot=0;var grpTabs=[];for(var ti=0;ti<grp.tabs.length;ti++){var tab=grp.tabs[ti];var nm=genTabName(tab);if(!nm||nm.length>24||epName(nm))continue;var "
        + "cnt=0;try{var tm=tabText(tab).match(/(\\d+)\\s*集/);if(tm)cnt=parseInt(tm[1],10);}catch(e){}var act2=genTabActive(tab);grpTabs.push({name:nm,count:cnt,active:act2?1:0});var pn=panelOfTab(tab);var fb=false;if(!pn){pn=fallbackScope(tab);fb=true;}if(!pn)continue;var "
        + "sid2=boxSid(pn);if(!sid2)sid2='g'+(genSidSeq++);var got=scanGenPanel(pn,sid2,nm,act2,fb);genEpsFound+=got;grpGot+=got;}if(grpGot>0){genTabsFound+=grpTabs.length;for(var gi3=0;gi3<grpTabs.length;gi3++)genTabList.push(grpTabs[gi3]);}}var "
        + "scanned={};for(var xi=0;xi<tabEls.length;xi++){var tel=tabEls[xi];var pn2=panelOfTab(tel);var fb2=false;if(!pn2){pn2=fallbackScope(tel);fb2=true;}if(!pn2)continue;var id2=pn2.id||'';if(id2&&scanned[id2])continue;if(id2)scanned[id2]=1;var "
        + "nm2=tabNames[xi]||genTabName(tel);var sid3=boxSid(pn2);if(!sid3)sid3='g'+(genSidSeq++);var act3=genTabActive(tel);var got2=scanGenPanel(pn2,sid3,nm2,act3,fb2);genEpsFound+=got2;}})();sorted=lineOrder.filter(function(L){return "
        + "L.order.length>0;});sorted.sort(function(a,b){return parseInt(a.sid,10)-parseInt(b.sid,10);});var out=[];for(var z=0;z<sorted.length&&z<8;z++){var L=sorted[z];L.order.sort(function(x,y){return x.ep-y.ep;});L.cand=cleanCand(L);var "
        + "tn=tabFor[L.sid]||'';var nm=tn||L.genName||((sid!==''&&L.sid===sid&&activeName)?activeName:(L.cand||('线路'+(out.length+1))));var eps=[];for(var w=0;w<L.order.length&&w<300;w++){var o=L.order[w];eps.push({name:o.name,url:o.url,cur:o.cur?1:0,id:o.id||'',path:o.path||''});}out.push({sid:L.sid,name:nm,cur:((sid!==''&&L.sid===sid)||L.genCur)?1:0,episodes:eps});}var "
        + "genLines=0;for(var zg=0;zg<sorted.length&&zg<8;zg++){if(sorted[zg].fromGeneric)genLines++;}var curEpName='';for(var ce=0;ce<out.length;ce++){var epsC=out[ce].episodes;for(var cw=0;cw<epsC.length;cw++){if(epsC[cw].cur){curEpName=epsC[cw].name;break;}}if(curEpName)break;}var "
        + "dp=null;try{dp=dplayerEpisodes();}catch(e){dp=null;}if(dp&&dp.line)out.push(dp.line);var subs=[];try{subs=subEpsList();}catch(e){subs=[];}var diag='';if((sorted.length>1&&tabNames.length!==sorted.length)||tabRaw.length){var "
        + "dc=[];for(var d1=0;d1<sorted.length;d1++)dc.push(sorted[d1].sid+':'+(sorted[d1].candRaw||'-'));diag='tabs='+tabNames.length+' srcA='+document.querySelectorAll('[class*=\"source\"] a').length+' srcLi='+document.querySelectorAll('[class*=\"source\"] "
        + "li').length+' cand='+dc.join('|');var dr=[];for(var d2=0;d2<sorted.length;d2++){var Lr=sorted[d2];if(!Lr.candRaw&&Lr.raws.length)dr.push(Lr.sid+'='+Lr.raws.join('~'));}if(dr.length)diag+=(diag?' ':'')+'raw='+dr.join('|');if(tabRaw.length)diag+=(diag?' "
        + "':'')+'tabRaw='+tabRaw.join('|');if(tabSel>=0)diag+=(diag?' ':'')+'tsel='+tabSel;if(tabNames.length!==sorted.length){var hs=[];var cs=document.querySelectorAll('ul,ol,div');for(var hi=0;hi<cs.length&&hi<80;hi++){var "
        + "c0=cs[hi];var ch=c0.children;if(!ch||ch.length<2||ch.length>8)continue;var ts2=[];var bad=false;for(var hj=0;hj<ch.length;hj++){var tt=tabText(ch[hj]);if(!tt||tt.length>12||epName(tt)){bad=true;break;}ts2.push(tt);}if(bad||ts2.length<2)continue;hs.push(((c0.getAttribute('class')||c0.tagName)||'').slice(0,30)+':'+ts2.slice(0,3).join('~'));if(hs.length>=3)break;}if(hs.length)diag+=(diag?' "
        + "':'')+'hunt='+hs.join('|');}}function articleBody(){try{var a=document.querySelector('.post-content[itemprop=\"articleBody\"]');if(a)return a;return document.querySelector('.post-content');}catch(e){return "
        + "null;}}function inRemovedZone(el){try{var n=el;while(n&&n!==document.body){if(n.matches&&n.matches('.post-near,.content-copyright,.hot-news-section,.tags,.content-tabs,.txt-apps,.horizontal-banner,.article-ads-btn,.btn-app,.line,blockquote,table'))return "
        + "true;n=n.parentElement;}return false;}catch(e){return false;}}function dplayerCfg(el){var c='';try{c=el.getAttribute('data-config')||'';}catch(e){}if(!c)return null;try{return JSON.parse(c);}catch(e){try{var "
        + "m=/\\\"video\\\"\\s*:\\s*\\{[^}]*?\\\"url\\\"\\s*:\\s*\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"/.exec(c);if(m)return{video:{url:m[1].replace(/\\\\\\//g,'/')}};var m2=/\\\"video_h265\\\"\\s*:\\s*\\{[^}]*?\\\"url\\\"\\s*:\\s*\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"/.exec(c);if(m2)return{video_h265:{url:m2[1].replace(/\\\\\\//g,'/')}};}catch(e2){}return "
        + "null;}}function dplayerUrl(cfg){try{var u=cfg&&cfg.video&&cfg.video.url;if(u&&/^https?:/i.test(u))return u;var h=cfg&&cfg.video_h265&&cfg.video_h265.url;if(h&&/^https?:/i.test(h))return h;}catch(e){}return "
        + "'';}function dplayerEpisodes(){try{var dps=document.querySelectorAll('.dplayer[data-config]');if(!dps||!dps.length)return null;var eps=[];for(var i=0;i<dps.length;i++){var el=dps[i];try{if(hasAdMark(el)||inRecCtx(el))continue;}catch(e){}var "
        + "u=dplayerUrl(dplayerCfg(el));if(!u)continue;eps.push({url:u,el:el});}if(!eps.length)return null;var art=articleBody();var atxt='';try{atxt=art?(art.innerText||art.textContent||''):'';}catch(e){}if(!atxt){try{atxt=document.body?(document.body.innerText||document.body.textContent||''):'';}catch(e){}}var "
        + "epNames=atxt.match(/第[一二三四五六七八九十百]+集/g)||[];var curIdx=0;for(var j=0;j<eps.length;j++){var v=null;try{v=eps[j].el.querySelector('video');}catch(e){}if(v&&v.paused===false){curIdx=j;break;}}var lineName='';try{lineName=cleanName(document.title||'');}catch(e){}if(!lineName)lineName='视频';var "
        + "out_eps=[];for(var k=0;k<eps.length;k++){var nm=epNames.length>=eps.length?epNames[k]:('第'+(k+1)+'集');out_eps.push({name:nm,url:eps[k].url,cur:k===curIdx?1:0});}return{line:{sid:'dp',name:lineName,cur:1,episodes:out_eps},curUrl:eps[curIdx].url,curName:out_eps[curIdx].name};}catch(e){return "
        + "null;}}function subEpsList(){try{var dps=document.querySelectorAll('.dplayer[data-config]');if(dps&&dps.length)return [];}catch(e){}try{var art=articleBody();if(!art)return [];var as=art.querySelectorAll('a[href]');var "
        + "out2=[],seen={};for(var i=0;i<as.length&&out2.length<20;i++){var a=as[i];if(inRemovedZone(a))continue;var raw='';try{raw=a.getAttribute('href')||'';}catch(e){}if(!raw)continue;var u='';try{u=new URL(raw,location.href).href;}catch(e){continue;}var "
        + "uu=null;try{uu=new URL(u);}catch(e){continue;}if(uu.host!==location.host)continue;if(!/^\\/archives\\/\\d+(\\/|\\.html)/.test(uu.pathname))continue;var norm=u.split('#')[0].split('?')[0];if(norm.charAt(norm.length-1)==='/')norm=norm.slice(0,-1);var "
        + "cur=location.href.split('#')[0].split('?')[0];if(cur.charAt(cur.length-1)==='/')cur=cur.slice(0,-1);if(norm===cur)continue;if(seen[norm])continue;seen[norm]=1;var nm='';try{nm=cleanName(a.innerText||a.textContent||'');}catch(e){}out2.push({name:nm,url:u});}return "
        + "out2;}catch(e){return [];}}function isStreamUrl(u){if(!u)return false;var l=(''+u).toLowerCase();var q=l.indexOf('?');var p=q>0?l.substring(0,q):l;if(p.slice(-5)==='.m3u8'||p.slice(-4)==='.mp4')return "
        + "true;var i=l.indexOf('.m3u8');while(i>=0){var a=i+5;if(a>=l.length||l.charAt(a)==='&'||l.charAt(a)==='#')return true;i=l.indexOf('.m3u8',a);}i=l.indexOf('.mp4');while(i>=0){var a2=i+4;if(a2>=l.length||l.charAt(a2)==='&'||l.charAt(a2)==='#')return "
        + "true;i=l.indexOf('.mp4',a2);}return false;}function absUrl(u){if(!u)return '';try{return new URL(u,location.href).href;}catch(e){return u;}}function vidSrc(v){var s='';try{s=v.currentSrc||v.src||'';}catch(e){}if(s===location.href)s='';if(s&&/^https?:/i.test(s))return "
        + "s;if(v.querySelector){try{var qs=v.querySelectorAll('source');for(var i=0;i<qs.length;i++){var q=qs[i].currentSrc||qs[i].src||'';if(q&&q!==location.href&&/^https?:/i.test(q))return q;}}catch(e){}}return "
        + "s||'';}function vidSrcAny(v){var s='';try{s=v.currentSrc||v.src||'';}catch(e){}if(s===location.href)s='';if(s)return s;if(v.querySelector){try{var q=v.querySelector('source');if(q){var qs2=q.currentSrc||q.src||'';if(qs2&&qs2!==location.href)s=qs2;}}catch(e){}}return "
        + "s||'';}function ctxStr(el){return ((el.getAttribute('class')||'')+' '+(el.getAttribute('id')||''));}function hasAdMark(el){var n=el;while(n&&n!==document.body){if(/(^|[\\s_\\-])(ad|ads|advert|advertisement|banner|sponsor|sponsored|promotion)([\\s_\\-]|$)/i.test(ctxStr(n)))return "
        + "true;n=n.parentElement;}return false;}function inRecCtx(el){var n=el;while(n&&n!==document.body){if(/(^|[\\s_\\-])(recommend|recommended|related|thumbnail|thumbnails|preview|previews|more-videos|sidebar|also-watch)([\\s_\\-]|$)/i.test(ctxStr(n)))return "
        + "true;n=n.parentElement;}return false;}function inPlayerCtx(el){var n=el;while(n&&n!==document.body){if(/(^|[\\s_\\-])(player|playbox|dplayer)([\\s_\\-]|$)/i.test(ctxStr(n)))return true;n=n.parentElement;}return "
        + "false;}function isGifPrev(v){var c=v.getAttribute('class')||'';if(/gifVideo|lazyVideo/i.test(c))return true;if(v.hasAttribute&&v.hasAttribute('data-mp4'))return true;return false;}function elHidden(el){if(!el)return "
        + "true;if(el.hidden)return true;if(/(^|[\\s_\\-])(displayNone|display-none|hidden|hide|invisible|collapsed)([\\s_\\-]|$)/i.test(ctxStr(el)))return true;var s=el.getAttribute?el.getAttribute('style')||'':'';if(/display\\s*:\\s*none/i.test(s)||/visibility\\s*:\\s*hidden/i.test(s))return "
        + "true;try{var cs=getComputedStyle(el);if(cs&&(cs.display==='none'||cs.visibility==='hidden'))return true;}catch(e){}if(el.tagName==='VIDEO'){var w=el.getAttribute('width'),h=el.getAttribute('height');if((w!==null&&parseInt(w,10)===0)||(h!==null&&parseInt(h,10)===0))return "
        + "true;}return false;}function vidArea(v){try{var r=v.getBoundingClientRect();if(r&&r.width>0&&r.height>0)return r.width*r.height;}catch(e){}var w=parseInt(v.getAttribute('width')||'0',10),h=parseInt(v.getAttribute('height')||'0',10);if(w>0&&h>0)return "
        + "w*h;try{var a=v.videoWidth||0,b=v.videoHeight||0;if(a>0&&b>0)return a*b;}catch(e){}return 0;}function mainVideo(){var vs=document.querySelectorAll('video');var best=null,bs=-1;for(var i=0;i<vs.length;i++){var "
        + "v=vs[i];if(isGifPrev(v)||hasAdMark(v)||inRecCtx(v)||elHidden(v))continue;var s=vidArea(v);if(s>bs){bs=s;best=v;}}return best;}function mainVideoSrc(){var mv=mainVideo();if(!mv)return '';var s=vidSrc(mv);if(!s)return "
        + "'';if(/^blob:/i.test(s))return '';if(/^https?:/i.test(s))return s;return absUrl(s);}function playerScope(mv){if(!mv)return document;var sc=mv.parentElement||document;var n=mv.parentElement;for(var d=0;d<4&&n;d++){if(/(^|[\\s_\\-])player([\\s_\\-]|$)/i.test(ctxStr(n)))sc=n;n=n.parentElement;}return "
        + "sc||document;}function adPlaying(){try{var mv=mainVideo();if(!mv)return false;var sc=playerScope(mv);var sk=sc.querySelectorAll('[class*=skip],[id*=skip]');for(var i=0;i<sk.length;i++){if(!elHidden(sk[i]))return "
        + "true;}var els=sc.querySelectorAll('button,a,div,span');for(var j=0;j<els.length;j++){var e=els[j];if(e.children&&e.children.length>0)continue;var t=txt(e);if(t&&t.length<40&&/^(skip|跳过)/i.test(t))return "
        + "true;}var ovs=sc.querySelectorAll('[class*=preroll],[class*=vast],[id*=preroll],[id*=vast]');for(var k=0;k<ovs.length;k++){if(!elHidden(ovs[k]))return true;}return false;}catch(e){return false;}}function "
        + "adSrcs(){var out=[],seen={};function add(u){if(!u)return;u=absUrl(u);if(!/^https?:/i.test(u))return;if(!isStreamUrl(u))return;if(seen[u])return;seen[u]=1;out.push(u);}try{var vs=document.querySelectorAll('video');for(var "
        + "i=0;i<vs.length;i++){var v=vs[i];var dm=v.getAttribute('data-mp4');if(dm)add(dm);if(!vidSrcAny(v)){var ds=v.getAttribute('data-src');if(ds)add(ds);var du=v.getAttribute('data-url');if(du)add(du);}if(isGifPrev(v)||hasAdMark(v)||inRecCtx(v)||(v.loop&&v.muted))add(vidSrcAny(v));}if(adPlaying()){var "
        + "mv=mainVideo();if(mv)add(vidSrcAny(mv));}}catch(e){}return out.slice(0,50);}function mediaDefDefault(){try{var ss=document.querySelectorAll('script');for(var i=0;i<ss.length;i++){var t=ss[i].textContent||'';if(t.indexOf('mediaDefinitions')<0)continue;var "
        + "m;var re1=/\"defaultQuality\"\\s*:\\s*true[^}]{0,300}?\"videoUrl\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"/g;while((m=re1.exec(t))!==null){var u=m[1].replace(/\\\\\\//g,'/');if(isStreamUrl(u))return u;}var "
        + "re2=/\"videoUrl\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^}]{0,300}?\"defaultQuality\"\\s*:\\s*true/g;while((m=re2.exec(t))!==null){var u2=m[1].replace(/\\\\\\//g,'/');if(isStreamUrl(u2))return u2;}}return "
        + "'';}catch(e){return '';}}function parseQuoted(s,pos){var q=s.charAt(pos);if(q!==\"'\"&&q!=='\"')return null;var out='',i=pos+1;while(i<s.length){var ch=s.charAt(i);if(ch==='\\\\'){var nx=s.charAt(i+1);if(nx==='u'){out+=String.fromCharCode(parseInt(s.substr(i+2,4),16));i+=6;continue;}if(nx==='x'){out+=String.fromCharCode(parseInt(s.substr(i+2,2),16));i+=4;continue;}if(nx==='n'){out+='\\n';i+=2;continue;}if(nx==='r'){out+='\\r';i+=2;continue;}if(nx==='t'){out+='\\t';i+=2;continue;}out+=nx;i+=2;continue;}if(ch===q)return "
        + "[out,i+1];out+=ch;i++;}return null;}function tokOf(n,a){if(a<=36)return n.toString(a);var s='',c=n;do{var r=c%a;s=((r>35?String.fromCharCode(r+29):r.toString(36))+s);c=Math.floor(c/a);}while(c>0);return "
        + "s;}function decodePacker(t){var start=t.indexOf('eval(function(p,a,c,k,e,d)');if(start<0)return '';var call=-1;for(var i=start;i<t.length-2;i++){if(t.charAt(i)==='}'){var j=i+1;while(j<t.length&&(t.charAt(j)===' "
        + "'||t.charAt(j)==='\\t'||t.charAt(j)==='\\n'||t.charAt(j)==='\\r'))j++;if(t.charAt(j)==='('){var k2=j+1;while(k2<t.length&&(t.charAt(k2)===' '||t.charAt(k2)==='\\t'||t.charAt(k2)==='\\n'||t.charAt(k2)==='\\r'))k2++;if(t.charAt(k2)===\"'\"||t.charAt(k2)==='\"'){call=k2;break;}}}}if(call<0)return "
        + "'';var p1=parseQuoted(t,call);if(!p1)return '';var rest=t.slice(p1[1]);var m=/^\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*/.exec(rest);if(!m)return '';var a=parseInt(m[1],10),c=parseInt(m[2],10);var k1=parseQuoted(t,p1[1]+m[0].length);if(!k1)return "
        + "'';var p=p1[0],k=k1[0].split('|');for(var j2=c-1;j2>=0;j2--){var tok=tokOf(j2,a);var rep=(k[j2]!==undefined&&k[j2]!=='')?k[j2]:tok;if(rep===tok)continue;p=p.replace(new RegExp('\\\\b'+tok+'\\\\b','g'),rep);}return "
        + "p;}function packedSource(){try{var ss=document.querySelectorAll('script');for(var i=0;i<ss.length;i++){var t=ss[i].textContent||'';if(t.indexOf('eval(function(p,a,c,k,e,d)')<0)continue;var dec=decodePacker(t);if(!dec)continue;var "
        + "m=/source\\s*[:=]\\s*[^;]{0,400}?(https?:\\/\\/[^'\"\\s;]+\\.(?:m3u8|mp4)[^'\"\\s;]*)/.exec(dec);if(m&&isStreamUrl(m[1])){var rw=pageRw(m[1]);return (rw&&isStreamUrl(rw))?rw:m[1];}}}catch(e){}return '';}function pageRw(raw){try{var RW=/\\b([A-Za-z_$][\\w$]*)\\s*=\\s*\\1\\.replace\\(\\s*\\/((?:\\\\.|[^\\/\\\\\\n])+)\\/([gimsuy]*)\\s*,\\s*(['\"])([^'\"\\n]*)\\4\\s*\\)/g;var ss=document.querySelectorAll('script');for(var i=0;i<ss.length;i++){var t=ss[i].textContent||'';if(t.indexOf('.replace(')<0)continue;var m;RW.lastIndex=0;while((m=RW.exec(t))){if(m[1]!=='source')continue;var re;try{re=new RegExp(m[2],m[3]);}catch(e){continue;}var out=raw.replace(re,m[5]);if(out===raw)continue;try{if(new URL(out).origin===location.origin)return out;}catch(e){}}}}catch(e){}return '';}function perfM3u8(){try{if(!window.performance||!performance.getEntriesByType)return "
        + "'';var es=performance.getEntriesByType('resource');var found=[];for(var i=0;i<es.length;i++){var u=es[i].name||'';if(/\\.m3u8($|[?&#])/i.test(u)&&found.indexOf(u)<0)found.push(u);}if(found.length===1)return "
        + "found[0];return '';}catch(e){return '';}}function frameStream(){try{var AD=/(^|[\\s_\\-])(ad|ads|advert|advertisement|banner|sponsor|sponsored|promotion)([\\s_\\-]|$)/i;var PL=/(^|[\\s_\\-])(player|playbox|dplayer)([\\s_\\-]|$)/i;var RC=/(^|[\\s_\\-])(recommend|recommended|related|thumbnail|thumbnails|preview|previews|more-videos|sidebar|also-watch)([\\s_\\-]|$)/i;function cx(el){return ((el.getAttribute&&el.getAttribute('class'))||'')+' '+((el.getAttribute&&el.getAttribute('id'))||'');}function up(el,re){var d=el.ownerDocument,n=el;while(n&&n!==d.body&&n!==d.documentElement){if(re.test(cx(n)))return true;n=n.parentElement;}return false;}var fr=document.querySelectorAll('iframe');for(var k=0;k<fr.length;k++){var f=fr[k];if(up(f,AD)||!up(f,PL))continue;var d=null,w=null;try{d=f.contentDocument;w=f.contentWindow;}catch(e){}if(!d||!d.body)continue;var vs=d.querySelectorAll('video'),best=null,bs=-1;for(var i=0;i<vs.length;i++){var v=vs[i];if(up(v,AD)||up(v,RC)||(v.loop&&v.muted))continue;var r=v.getBoundingClientRect(),a=r.width*r.height;if(a>bs){bs=a;best=v;}}if(best){var s='';try{s=best.currentSrc||best.getAttribute('src')||'';}catch(e){}if(/^https?:/i.test(s)&&isStreamUrl(s))return s;}try{var es=w.performance.getEntriesByType('resource'),fl=[];for(var j=0;j<es.length;j++){var u=es[j].name||'';if(/\\.m3u8($|[?&#])/i.test(u)&&fl.indexOf(u)<0)fl.push(u);}if(fl.length===1)return fl[0];}catch(e){}}return '';}catch(e){return '';}}function mainStream(){try{var mv=mainVideo();if(mv&&!adPlaying()){var s=vidSrc(mv);if(s&&/^https?:/i.test(s)&&isStreamUrl(s))return s;}try{var c=window.player_aaaa||window.player_data||window.player_config||window.MacPlayer;if(c&&c.url&&isStreamUrl(c.url))return "
        + "absUrl(c.url);}catch(e){}var md=mediaDefDefault();if(md)return md;var pk=packedSource();if(pk)return pk;var pf=perfM3u8();if(pf)return pf;var fsr=frameStream();if(fsr)return fsr;return '';}catch(e){return '';}}function hasMainPlayer(){try{var "
        + "mv=mainVideo();if(mv){var s=vidSrc(mv);if(s&&/^https?:/i.test(s))return true;}try{var c=window.player_aaaa||window.player_data||window.player_config||window.MacPlayer;if(c&&c.url)return true;}catch(e){}if(packedSource())return "
        + "true;try{var dps=document.querySelectorAll('.dplayer[data-config]');for(var i=0;i<dps.length;i++){if(dplayerUrl(dplayerCfg(dps[i])))return true;}}catch(e){}if(out&&out.length)return true;if(subs&&subs.length)return "
        + "true;try{var frs=document.querySelectorAll('iframe[src]');for(var j=0;j<frs.length;j++){var f=frs[j].getAttribute('src')||'';if(/\\/static\\/player\\//i.test(f)||/dplayer\\.html/i.test(f))return true;if(/^https?:/i.test(absUrl(f))&&!hasAdMark(frs[j])&&inPlayerCtx(frs[j]))return "
        + "true;}}catch(e){}try{var vids=document.querySelectorAll('video');var mv2=mainVideo();for(var vi=0;vi<vids.length;vi++){var vv=vids[vi];if(isGifPrev(vv)||hasAdMark(vv)||inRecCtx(vv)||elHidden(vv))continue;if(vv.closest&&vv.closest('video-player,[data-playback-player]'))return "
        + "true;var anc=vv.parentElement,plc=false;while(anc&&anc!==document.body){if(/(^|[\\s_\\-])(player|playbox|dplayer)([\\s_\\-]|$)/i.test(ctxStr(anc))){plc=true;break;}anc=anc.parentElement;}if(plc&&vidSrcAny(vv))return "
        + "true;if(mv2&&vv===mv2&&vidSrcAny(vv))return true;}}catch(e){}return false;}catch(e){return false;}}function playerCfg(){try{var c=window.player_aaaa||window.player_data||window.player_config||window.MacPlayer;if(!c)return "
        + "'';return JSON.stringify(c);}catch(e){return '';}}return JSON.stringify({lines:out,diag:diag,pic:picOf(),player:playerCfg(),curEpName:(dp&&dp.curName)||curEpName,origin:location.origin,vid:vid,sid:sid,ep:curEp,mainStream:(dp&&dp.curUrl)||mainStream(),mainVideo:mainVideoSrc(),adSrcs:adSrcs(),adPlaying:adPlaying()?1:0,subEps:subs,hasMainPlayer:hasMainPlayer()?1:0,tabsFound:genTabsFound,episodesFound:genEpsFound,genericLines:genLines,genTabs:genTabList});})()";
    // push v20：gaze 懒加载面板预加载——tab+按钮式线路有未加载面板（data-loaded=false）时逐个点开 tab 让站方填集、点回原 tab；返回 {"clicked":N,"pending":P}。
    private static final String PROBE_PRELOAD_JS = "(function(){try{var all=document.querySelectorAll('[role=\"tab\"],[data-toggle=\"tab\"]');var groups=[];for(var i=0;i<all.length;i++){var t=all[i];var par=t.parentElement;if(!par)continue;var g=null;for(var gi=0;gi<groups.length;gi++)if(groups[gi].par===par){g=groups[gi];break;}if(!g){g={par:par,tabs:[]};groups.push(g);}g.tabs.push(t);}if(!window.__museProbePreloadPanels)window.__museProbePreloadPanels=[];var clicked=0,pending=0;function alreadyClicked(p){var a=window.__museProbePreloadPanels;for(var k=0;k<a.length;k++)if(a[k]===p)return true;return false;}function panelOf(el){var id='';try{id=el.getAttribute('aria-controls')||'';}catch(e){}var tgt='';try{tgt=el.getAttribute('data-target')||el.getAttribute('href')||'';}catch(e){}if(tgt&&tgt.charAt(0)==='#')id=tgt.slice(1);if(id){try{var pp=document.getElementById(id);if(pp)return pp;}catch(e){}}try{var par2=el.parentElement;if(!par2)return null;var sibs=par2.querySelectorAll('[role=\"tab\"]');var idx=-1;for(var k=0;k<sibs.length;k++)if(sibs[k]===el){idx=k;break;}if(idx<0)return null;var anc=par2;for(var d=0;d<4&&anc;d++){var panels=anc.querySelectorAll('[role=\"tabpanel\"]');if(panels.length===sibs.length)return panels[idx];anc=anc.parentElement;}}catch(e){}return null;}function isActive(el){try{var cl=el.getAttribute('class')||'';return /(^|\\s)(active|current|cur|selected|on)(\\s|$)/.test(cl)||el.getAttribute('aria-selected')==='true';}catch(e){return false;}}function hasEps(p){try{var bs=p.querySelectorAll('button');for(var i=0;i<bs.length;i++){var b=bs[i];if(b.getAttribute('data-id'))return true;var tx=(b.innerText||b.textContent||'').trim();if(/^第\\d+集$/.test(tx)||/^\\d+$/.test(tx))return true;}return false;}catch(e){return false;}}for(var gi2=0;gi2<groups.length;gi2++){var grp=groups[gi2];if(grp.tabs.length<2)continue;var gaze=true;var panels=[];for(var ti=0;ti<grp.tabs.length;ti++){var tab=grp.tabs[ti];if(tab.tagName!=='BUTTON'){gaze=false;break;}var pn=panelOf(tab);if(!pn||!pn.dataset||pn.dataset.loaded===undefined){gaze=false;break;}panels.push(pn);}if(!gaze)continue;var orig=null;for(var ai=0;ai<grp.tabs.length;ai++)if(isActive(grp.tabs[ai])){orig=grp.tabs[ai];break;}if(!orig)orig=grp.tabs[0];var groupClicked=0;for(var pi=0;pi<panels.length;pi++){var p2=panels[pi];if(p2.dataset.loaded==='true')continue;if(hasEps(p2))continue;pending++;if(alreadyClicked(p2))continue;try{grp.tabs[pi].click();}catch(e){}window.__museProbePreloadPanels.push(p2);clicked++;groupClicked++;}if(groupClicked>0&&orig){try{orig.click();}catch(e){}}}return JSON.stringify({clicked:clicked,pending:pending});}catch(e){return '{\"clicked\":0,\"pending\":0}';}})()";
    // push v20：预加载轮询——返回还未出集的已点面板数（'0' 即齐了）。
    private static final String PROBE_PRELOAD_POLL_JS = "(function(){try{var a=window.__museProbePreloadPanels;if(!a||!a.length)return '0';var n=0;for(var i=0;i<a.length;i++){var p=a[i];try{if(!p||p.isConnected===false)continue;if(p.dataset&&p.dataset.loaded==='true')continue;var bs=p.querySelectorAll('button');var has=false;for(var j=0;j<bs.length;j++){var b=bs[j];if(b.getAttribute('data-id')){has=true;break;}var tx=(b.innerText||b.textContent||'').trim();if(/^第\\d+集$/.test(tx)||/^\\d+$/.test(tx)){has=true;break;}}if(!has)n++;}catch(e){}}return String(n);}catch(e){return '0';}})()";
    /** push v20：预加载轮询间隔 300ms、上限 20 轮（6 秒到顶即收表，不无限等站方接口）。 */
    private static final long PROBE_PRELOAD_POLL_MS = 300;
    private static final int PROBE_PRELOAD_MAX_TRIES = 20;
    private static final String VIDEO_PROBE = "(function(){try{function hasM(el){var n=el;while(n&&n!==document.body){var c=((n.getAttribute('class')||'')+' '+(n.getAttribute('id')||''));if(/(^|[\\s_\\-])(ad|ads|advert|advertisement|banner|sponsor|sponsored|promotion)([\\s_\\-]|$)/i.test(c))return "
                    + "true;n=n.parentElement;}return false;}function gif(v){var c=v.getAttribute('class')||'';if(/gifVideo|lazyVideo/i.test(c))return true;if(v.hasAttribute&&v.hasAttribute('data-mp4'))return true;return false;}function "
                    + "rec(el){var n=el;while(n&&n!==document.body){var c=((n.getAttribute('class')||'')+' '+(n.getAttribute('id')||''));if(/(^|[\\s_\\-])(recommend|recommended|related|thumbnail|thumbnails|preview|previews|more-videos|sidebar|also-watch)([\\s_\\-]|$)/i.test(c))return "
                    + "true;n=n.parentElement;}return false;}function hid(v){if(v.hidden)return true;if(/(^|[\\s_\\-])(displayNone|display-none|hidden|hide|invisible|collapsed)([\\s_\\-]|$)/i.test(((v.getAttribute('class')||'')+' "
                    + "'+(v.getAttribute('id')||''))))return true;var s=v.getAttribute('style')||'';if(/display\\s*:\\s*none/i.test(s)||/visibility\\s*:\\s*hidden/i.test(s))return true;try{var cs=getComputedStyle(v);if(cs&&(cs.display==='none'||cs.visibility==='hidden'))return "
                    + "true;}catch(e){}var w=v.getAttribute('width'),h=v.getAttribute('height');if((w!==null&&parseInt(w,10)===0)||(h!==null&&parseInt(h,10)===0))return true;return false;}function area(v){try{var r=v.getBoundingClientRect();if(r&&r.width>0&&r.height>0)return "
                    + "r.width*r.height;}catch(e){}var w=parseInt(v.getAttribute('width')||'0',10),h=parseInt(v.getAttribute('height')||'0',10);if(w>0&&h>0)return w*h;try{var a=v.videoWidth||0,b=v.videoHeight||0;if(a>0&&b>0)return "
                    + "a*b;}catch(e){}return 0;}function mainV(){var vs=document.querySelectorAll('video');var best=null,bs=-1;for(var i=0;i<vs.length;i++){var v=vs[i];if(gif(v)||hasM(v)||rec(v)||hid(v))continue;var s=area(v);if(s>bs){bs=s;best=v;}}return "
                    + "best;}var v=mainV();if(!v)return '';var s=v.currentSrc||v.src||'';if(s===location.href)s='';if(s&&/^https?:/i.test(s))return s;if(v.querySelector){var qs=v.querySelectorAll('source');for(var i=0;i<qs.length;i++){var "
                    + "q=qs[i].currentSrc||qs[i].src||'';if(q&&q!==location.href&&/^https?:/i.test(q))return q;}}return (s&&s!==location.href)?s:'';}catch(e){return ''}})()";
    // push v1: 浏览器转原生播放状态（嗅探到的直链 + 多线探针结果 + 当前页信息）。
    private volatile String mPushLastSniff;
    private volatile long mPushSniffAt;
    // push v14：嗅流所属页——只认本页嗅到的流，跨页一律不认。
    private volatile String mPushSniffPageUrl = "";
    // push v15：探针判定的本页主播放器（主视频直链 / player_aaaa / packed source /
    // .dplayer 有效地址 / 线路表 / 子帖表 / 播放器型 iframe 任一成立，iframe 认 src
    // 含 /static/player/ 或 dplayer.html、或处在 player/playbox 容器内）；切页时重置。
    private volatile boolean mPushHasMainPlayer = false;
    // push v11：探针带回的主片流（声明地址）+ 广告流黑名单 + 片头广告播放中（PROBE_JS 新字段；切页时清）。
    private volatile String mPushMainStream = "";
    private volatile java.util.Set<String> mPushAdSrcs = java.util.Collections.emptySet();
    private volatile boolean mPushAdPlaying = false;
    private volatile Map<String, String> mPushSniffedHeaders;
    private volatile String mPushProbeLines = "";
    private volatile String mPushProbePageUrl = "";
    private volatile boolean mPushPlayPending = false;
    private volatile String mPushPagePic = "";
    // push v33 M1：推送带简介/年份/地区——页完时从当前页 DOM 只读取一次（零网络请求），切页即清。
    private volatile String mPushPageContent = "";
    private volatile String mPushPageYear = "";
    private volatile String mPushPageArea = "";
    private volatile String mPushPageOgPic = "";
    private volatile String mPushMetaPageUrl = "";
    private static final String PUSH_META_JS = "(function(){try{function cl(s){return (s||'').replace(/[\\s\\u00a0\\u3000]+/g,' ').trim();}function codey(s){return /window\\.|function\\s*\\(|=\\s*\\[|[{}]|=>|\\bvar\\s/.test(s);}var c='';var m=document.querySelector('meta[property=\"og:description\"]');if(m&&cl(m.content).length>=20)c=cl(m.content);if(!c){m=document.querySelector('meta[name=\"description\"]');if(m&&cl(m.content).length>=20)c=cl(m.content);}if(!c){var ds=document.querySelectorAll('div[class*=\"desc\"],div[class*=\"content\"],div[class*=\"intro\"]');for(var i=0;i<ds.length&&i<40;i++){var cn=ds[i].getAttribute('class')||'';if(/^list/i.test(cn))continue;var t=cl(ds[i].innerText||'');if(t.length>=20&&t.length<=3000&&!codey(t)){c=t;break;}}}if(codey(c))c='';var y='',a='';var es=document.querySelectorAll('li,dd,p,span,div');for(var k=0;k<es.length&&k<4000&&(!y||!a);k++){var tx=cl(es[k].textContent||'');if(tx.length>40)continue;var lb=tx.match(/^(年份|年代|地区)[：:]$/);if(lb&&es[k].parentElement){var pt=cl(es[k].parentElement.textContent||'');var ix=pt.indexOf(tx);tx=ix>=0?(tx+pt.slice(ix+tx.length).slice(0,40)):tx;}var ym=tx.match(/^(?:年份|年代)[：:]\\s*([0-9]{4})(?![0-9])/);if(ym&&!y)y=ym[1];var am=tx.match(/^地区[：:]\\s*([^\\s：:]{1,12})(?:\\s|$)/);if(am&&!a&&!/[：:]/.test(tx.slice(am[0].length).split(' ')[0]||''))a=am[1];}var p='';var og=document.querySelector('meta[property=\"og:image\"]');if(og&&og.content){try{p=new URL(og.content,location.href).href}catch(e){p=og.content}}return JSON.stringify({c:c.slice(0,1500),y:y,a:a,p:p});}catch(e){return '';}})()";
    private volatile String mPushPageUrl = "";
    // push v18 (c)：后台浏览器内点集解流——播放器点无 href 按钮式选集（带 data-id，如 gaze）
    // 时，不新建隐藏 WebView，用留在后台栈里的本实例点按钮、站方自己解流、嗅探抓回播放器。
    // 点按会切掉浏览器自己的当前播放（Liu 已知情并点头）；后台 WebView 媒体加载可能被节流，
    // 本次只做原型：解流期间先恢复计时器（web.onResume），解完/超时/失败再按活动状态恢复暂停。
    private static WeakReference<WebPlayActivity> sBrowserInstance;
    private BrowserResolveCallback mBrowserResolveCallback;
    private String mBrowserResolveKnownStream = "";
    private long mBrowserResolveAt;
    private final Handler mBrowserResolveHandler = new Handler(Looper.getMainLooper());
    private Runnable mBrowserResolveTimeout;
    private Runnable mBrowserResolveMute;
    private boolean mBrowserResolveWebResumed;
    // push v25 (B)：页面兜底导航（page-resolve）模式——落地后探针主片优先，嗅流只在无主片时用。
    private boolean mBrowserResolvePageMode;
    private boolean mBrowserResolvePageStarted;
    private boolean mBrowserResolvePageDone;
    private String mBrowserResolveSniff = "";
    private Runnable mBrowserResolveForce;
    private static final long BROWSER_RESOLVE_SNIFF_WAIT_MS = 3000;
    private boolean mActivityResumed;
    // push v3：探针补跑状态（选集表可能是 JS 后渲染的，页完那一刻还没进 DOM）。
    private Runnable mPushProbeRetry;
    private int mPushProbeRetryCount;
    private String mPushProbeRetryPageUrl = "";
    // push v20：gaze 懒加载面板预加载状态（tab+按钮式线路、面板 data-loaded=false 时先点开等站方填集）。
    private Runnable mPushProbePreloadPoll;
    private int mPushProbePreloadTries;
    private String mPushProbePreloadPageUrl = "";
    private ActivityWebPlayBinding mBinding;
    private WebResourceResponse mEmpty;
    private HashSet<String> mBlockedHosts = new HashSet<>();
    private HashSet<String> mResErrUrls = new HashSet<>();
    private HashSet<String> mConsoleErrSeen = new HashSet<>();
    private Runnable mForceShowWeb;
    private WebChromeClient mChromeClient;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;
    private ArrayList<String> mNames = new ArrayList<>();
    private ArrayList<String> mIds = new ArrayList<>();
    private ArrayList<WebFlag> mWebFlags = new ArrayList<>();
    private HashMap<String, String> mHeader;
    private String mTitle = "";
    private String mKey = "";
    private String mFlag = "";
    private String mClick = "";
    private String mAnchorUrl = "";
    /** v61：主页是发布站/跳转页时其 host（VideoActivity 传进来）；该页上的人工点按跳转放行。 */
    private String mJumpHost = "";
    /** v72：用户是否已点按——mJumpHost 只在用户点按前跟进初始页跳转。 */
    private boolean mUserGestured = false;
    /** v73:经放行的点按/新窗口到达新页时,让 mJumpHost 跟到新页一次(修 v72 mUserGestured 缺陷)。 */
    private boolean mFollowJumpHost = false;
    /** v76：跳转已用——v61/v74/v92 放行一次跨站跳转后记下起跳 host；真实站落地时清 mJumpHost，之后真实站上的跨站点击不再放行（假分类/假视频）。 */
    private boolean mJumpUsed = false;
    private String mJumpOriginHost = null;
    /** push v23 P2'：点按意图记录——{所点本站 <a> 的地址, 点按所在页地址}；页面 window 捕获阶段 click 监听经 JS 桥写入
     * （只记录，永不放行）。非链接点按写 {"", 页}；新页开始导航即清。字段跟本浏览器实例走，不是全局状态。 */
    private volatile String[] mClickMark = null;
    /** push v28 F2a：按下记录（mousedown/pointerdown 捕获阶段，格式同 mClickMark）——广告脚本可能在按下那一刻就开窗，
     * 早于 click 记录；只用于新窗口路径判定，主框路径不用（主框照 v23 只认 click）。新页开始导航即清。 */
    private volatile String[] mPressMark = null;
    /** push v23 P2'：主框最近一次开始导航的地址——拦下劫持后还原用户本意前，判断主框是否已在去那个链接的路上。 */
    private volatile String mClickLastStarted = "";
    // v93 跳转链路(v73 起放 Activity 层:onCreateWindow 放行中转跳转后也要记,供 302 跟进认)。
    private String mJumpAnchorHost = null;
    private String mJumpRelayHost = null;
    private long mJumpChainUntil = 0;
    private boolean mBlankRecovered = false;
    private Runnable mBlankRecheck; // v56: delayed blank re-check, cancelled on new navigation
    private int mValidatorLogs = 0;
    private int mPosition;
    private boolean mBarsVisible = true;

    /** 一条线路：名 + 该线路的集列表（名/标识），页内切线路时整组换。 */
    private static class WebFlag {
        final String flag;
        final ArrayList<String> names;
        final ArrayList<String> ids;

        WebFlag(String flag, ArrayList<String> names, ArrayList<String> ids) {
            this.flag = flag;
            this.names = names;
            this.ids = ids;
        }
    }

    public static void start(Activity activity, String title, String url, Map<String, String> header, ArrayList<String> names, ArrayList<String> ids, int position, String key, String flag, String click, ArrayList<String> flags) {
        // v75：不传 jumpHost 的入口也按入口 URL 的 host 算——主页浏览器入口（HomeActivity.openBrowser）
        // 走这条重载，之前传 "" 导致 mJumpHost 为空，v61/v74 永远不生效，发布站/中间页跳转全被拦。
        String host = "";
        try {
            if (!TextUtils.isEmpty(url)) host = Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        start(activity, title, url, header, names, ids, position, key, flag, click, flags, host == null ? "" : host);
    }


    /** v61：带跳转页 host 的重载——主页是发布站/跳转页时，把其 host 传进来放行该页上的人工点按跳转。 */
    public static void start(Activity activity, String title, String url, Map<String, String> header, ArrayList<String> names, ArrayList<String> ids, int position, String key, String flag, String click, ArrayList<String> flags, String jumpHost) {
        Intent intent = new Intent(activity, WebPlayActivity.class);
        // v91：切源进新浏览器时，先关掉已在栈里的旧浏览器页（只留一个 webview 在跑；否则换 20 条站源就攒 20 个 webview，内存先爆）。
        // standard 模式 + CLEAR_TOP = 旧实例 finish（onDestroy 里 web.destroy()）再建新实例；正常流程（已按返回关掉旧页）下是空操作。
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("title", title == null ? "" : title);
        intent.putExtra("url", url == null ? "" : url);
        if (header != null && !header.isEmpty()) intent.putExtra("header", new HashMap<>(header));
        intent.putStringArrayListExtra("names", names == null ? new ArrayList<>() : names);
        intent.putStringArrayListExtra("ids", ids == null ? new ArrayList<>() : ids);
        intent.putExtra("position", position);
        intent.putExtra("key", key == null ? "" : key);
        intent.putExtra("flag", flag == null ? "" : flag);
        intent.putExtra("click", click == null ? "" : click);
        intent.putStringArrayListExtra("flags", flags == null ? new ArrayList<>() : flags);
        intent.putExtra("jumpHost", jumpHost == null ? "" : jumpHost);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityWebPlayBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // push v18 (c)：本实例即后台浏览器入口（播放器点集时经静态方法找到它）。
        sBrowserInstance = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent intent = getIntent();
        mTitle = intent.getStringExtra("title");
        if (mTitle == null) mTitle = "";
        mKey = intent.getStringExtra("key");
        if (mKey == null) mKey = "";
        mFlag = intent.getStringExtra("flag");
        if (mFlag == null) mFlag = "";
        mClick = intent.getStringExtra("click");
        if (mClick == null) mClick = "";
        mJumpHost = intent.getStringExtra("jumpHost");
        if (mJumpHost == null) mJumpHost = "";
        // v75：兜底——没传 jumpHost 的入口按入口 URL 的 host 算，mJumpHost 永不为空。
        if (TextUtils.isEmpty(mJumpHost)) {
            try {
                String entryUrl = intent.getStringExtra("url");
                if (!TextUtils.isEmpty(entryUrl)) {
                    String h = Uri.parse(entryUrl).getHost();
                    if (!TextUtils.isEmpty(h)) mJumpHost = h;
                }
            } catch (Exception ignored) {
            }
        }
        mJumpUsed = false;
        mJumpOriginHost = null;
        ArrayList<String> names = intent.getStringArrayListExtra("names");
        if (names != null) mNames = names;
        ArrayList<String> ids = intent.getStringArrayListExtra("ids");
        if (ids != null) mIds = ids;
        ArrayList<String> flags = intent.getStringArrayListExtra("flags");
        if (flags != null) {
            for (String json : flags) {
                try {
                    JSONObject object = new JSONObject(json);
                    ArrayList<String> flagNames = new ArrayList<>();
                    ArrayList<String> flagIds = new ArrayList<>();
                    JSONArray jNames = object.optJSONArray("names");
                    JSONArray jIds = object.optJSONArray("ids");
                    if (jNames != null) for (int i = 0; i < jNames.length(); i++) flagNames.add(jNames.optString(i));
                    if (jIds != null) for (int i = 0; i < jIds.length(); i++) flagIds.add(jIds.optString(i));
                    mWebFlags.add(new WebFlag(object.optString("flag"), flagNames, flagIds));
                } catch (Exception ignored) {
                }
            }
        }
        mPosition = Math.clamp(intent.getIntExtra("position", 0), 0, Math.max(0, mNames.size() - 1));
        try {
            Object header = intent.getSerializableExtra("header");
            if (header instanceof HashMap) mHeader = (HashMap<String, String>) header;
        } catch (Exception ignored) {
        }
        mEmpty = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        initInsets();
        initWebView();
        // v56：只在换站时清缓存——v105 的无条件 clearCache(true) 会把 CF 验证关联的缓存状态一起清掉，
        // 导致每次进站都像全新浏览器、反复验证。同站重进不再清缓存，验证状态得以保留。
        String openUrl = intent.getStringExtra("url");
        String openHost = "";
        if (!TextUtils.isEmpty(openUrl)) {
            String h = Uri.parse(openUrl).getHost();
            if (h != null) openHost = h;
        }
        String lastHost = Setting.getLastWebHost();
        if (!TextUtils.isEmpty(openHost) && openHost.equals(lastHost)) {
        } else {
            mBinding.web.clearCache(true);
            if (!TextUtils.isEmpty(openHost)) Setting.putLastWebHost(openHost);
        }
        updateNav();
        mBinding.getRoot().post(this::applyBarOffset);
        String url = intent.getStringExtra("url");
        if (!TextUtils.isEmpty(url)) {
            mAnchorUrl = url;
            if (UrlUtil.isWebUrl(url)) loadPage(url, mHeader);
            else {
                Notify.show(R.string.web_load_fail);
                finish();
            }
        } else if (!mIds.isEmpty()) playEpisode(mPosition);
    }

    @Override
    protected void initEvent() {
        mBinding.back.setOnClickListener(view -> {
            // 顶栏返回直回壳子播放器，不在网页历史里退。
            if (mCustomView != null) mChromeClient.onHideCustomView();
            else finish();
        });
        mBinding.refresh.setOnClickListener(view -> {
            showBars();
            mBinding.web.reload();
        });
        mBinding.flag.setOnClickListener(view -> showFlags());
        mBinding.episodes.setOnClickListener(view -> showEpisodes());
        // push v1：悬浮「播放视频」钮——MacCMS 播放页探到可用线路表时才亮起，点后转原生播放器。
        mBinding.pushPlay.setOnClickListener(view -> playNative());
        // 点页面只用来唤回/续住上下栏，手势原样透传给页面（不消费）
        mBinding.web.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) showBars();
            return false;
        });
    }




    /** 只留顶栏：常驻不自动隐藏（像浏览器一样）；全屏播放时顶栏由全屏视图接管隐藏。 */
    private void showBars() {
        if (mCustomView != null) return;
        mBarsVisible = true;
        mBinding.topBar.setVisibility(View.VISIBLE);
        mBinding.topBar.post(this::applyBarOffset);
    }

    /** 顶栏在时把页面整体往下顶出顶栏的高度，视频排在顶栏下方不被遮；栏收起则回到全屏。 */
    private void applyBarOffset() {
        if (mBinding == null) return;
        if (!(mBinding.web.getLayoutParams() instanceof FrameLayout.LayoutParams lp)) return;
        int target = mBarsVisible ? mBinding.topBar.getHeight() : 0;
        if (lp.topMargin != target) {
            lp.topMargin = target;
            mBinding.web.setLayoutParams(lp);
        }
    }

    private void initInsets() {
        int padH = dp(4);
        int padTop = dp(3);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            mBinding.topBar.setPadding(padH, top + padTop, padH, dp(3));
            return insets;
        });
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        CookieManager.getInstance().setAcceptThirdPartyCookies(mBinding.web, true);
        WebSettings setting = mBinding.web.getSettings();
        setting.setJavaScriptEnabled(true);
        setting.setDomStorageEnabled(true);
        setting.setDatabaseEnabled(true);
        setting.setSupportZoom(true);
        setting.setBuiltInZoomControls(true);
        setting.setDisplayZoomControls(false);
        setting.setUseWideViewPort(true);
        setting.setLoadWithOverviewMode(true);
        setting.setMediaPlaybackRequiresUserGesture(false);
        setting.setSupportMultipleWindows(true);
        setting.setJavaScriptCanOpenWindowsAutomatically(false);
        setting.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        String ua = Setting.getUa();
        if (!TextUtils.isEmpty(ua)) setting.setUserAgentString(ua);
        mBinding.web.setWebViewClient(webViewClient());
        // push v23 P2'：点按意图记录桥（只有一个 mark 方法，只写 mClickMark，不能放行任何东西）。
        mBinding.web.addJavascriptInterface(new ClickMarkBridge(), "museClickGuard");
        mChromeClient = webChromeClient();
        mBinding.web.setWebChromeClient(mChromeClient);
    }

    private WebViewClient webViewClient() {
        return new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                String reqUrl = request.getUrl().toString();
                // v92 金牌诊断：验证挑战——站点的 /api 被 307 到 /GE/CC/VALIDATOR 时，跟过去的请求会在这里出现；
                // 记下来才知道接口是不是被验证拦了（只记前 20 次，接口重试循环时不刷屏）。
                if (reqUrl.toLowerCase(Locale.US).contains("/ge/cc/validator") && mValidatorLogs < 20) {
                    mValidatorLogs++;
                    DebugLogStore.add("WebPlay", "validator hit url=" + reqUrl);
                }
                // 奇优根除（v115）：gdw.js / xs2.js 是奇优的纯广告加载器（gdw.js 写 #noiddiv + WebSocket 远程代码注底部横幅；
                // xs2.js 往列表插「小说」推荐卡），只在奇优站内按文件名子串拦，别的站不碰（文件名可能撞）。不带 query 串，站方改版也不怕。
                // v116 补漏：兄弟插卡器 xs.js（与 xs2.js 同模板）此前未拦——"xs2.js" 子串不含 "xs.js"；
                // "xs.js" + "xs2.js" 并列覆盖 js_xs.js / js_xs2.js / xs.js / xs2.js 四种写法（见下表）。
                if (isQiyouSite()) {
                    String u = reqUrl.toLowerCase(Locale.US);
                    if (u.contains("gdw.js") || u.contains("xs.js") || u.contains("xs2.js")) {
                        if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked qiyou ad-loader url=" + request.getUrl(), false);
                        return mEmpty;
                    }
                }
                // v53 奇优：/static/jquery.js 是底部横幅的 WebSocket 通道（解析阶段同步开 WS，早于 CLEAN_JS 注入；
                // WS 握手不走 shouldInterceptRequest，只能拦加载器本身）。完整路径匹配：只拦 path 恰好是
                // /static/jquery.js 的请求；页面真 jQuery 走 CDN（path 不是这条），不误伤。
                // 不 gate isQiyouSite()：新奇优域名（如 wdbay.com）key/title 里没有 qiyou/奇优，isQiyouSite() 认不出，
                // 门一关加载器就漏过去、WS 就开了；WS 域名轮换拦不住，按 path 拦才管用。
                if ("/static/jquery.js".equals(request.getUrl().getPath())) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked qiyou static jquery url=" + request.getUrl(), false);
                    return mEmpty;
                }
                // 广告加载器（混淆 js 拉的远程广告脚本，域名按日期轮换）：按子串拦，通用、不只麦田。
                if (isAdLoaderUrl(request.getUrl().toString())) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked ad-loader url=" + request.getUrl(), false);
                    return mEmpty;
                }
                // 底部广告轮询脚本（如 Mirages 主题 bottom_ad_poll.js）：只拉广告 feed，不承载正片，直接拦。
                String path = request.getUrl().getPath();
                if (path != null && path.toLowerCase(Locale.US).contains("ad_poll")) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked ad_poll script=" + request.getUrl(), false);
                    return mEmpty;
                }
                // 骚火（v7 net）：/back_flow 是广告加载器劫持返回键的通道，按 path 子串拦，通用。
                if (path != null && path.toLowerCase(Locale.US).contains("/back_flow")) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked back_flow url=" + request.getUrl(), false);
                    return mEmpty;
                }
                if (!TextUtils.isEmpty(host) && isAd(host)) {
                    // 视频流地址永不拦截：广告是图片/脚本/iframe，流本身不是广告。
                    // 有的站视频走轮换域名，域名单误伤时播放器只剩 0:00 空壳。
                    if (isMediaUrl(request.getUrl().getPath())) return super.shouldInterceptRequest(view, request);
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked host=" + host + " url=" + request.getUrl(), false);
                    return mEmpty;
                }
                // push v1：嗅探 MacCMS 播放页媒体直链——只记录、不拦截（媒体地址永不拦）；嗅到新流后探一次多线表。
                // push v8：放宽——流地址的 .m3u8/.mp4 后缀可能在问号后的参数里（麦田 NB 真流
                // getM3u8?name=…&url=NBY-xxx.m3u8，路径是 /nby/m3u8/getM3u8）；见 isPushStreamUrl。
                // push v11：探针黑名单里的广告流不记为候选（只不记、不拦截；浏览器里广告照常放）。
                if (isPushStreamUrl(reqUrl) && !mPushAdSrcs.contains(reqUrl) && !reqUrl.equals(mPushLastSniff)) {
                    mPushLastSniff = reqUrl;
                    mPushSniffAt = System.currentTimeMillis();
                    mPushSniffPageUrl = mPushPageUrl;
                    capturePushSniffHeaders(request, reqUrl);
                    maybeDeliverBrowserResolve(reqUrl);
                    probePageInfo();
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // v72：记下用户是否已点按——mJumpHost 只在用户点按前跟进初始页跳转。
                if (request.hasGesture()) mUserGestured = true;
                String scheme = request.getUrl().getScheme();
                // 只放行网页本身；intent://、market://、tel: 等外部唤起一律堵死
                if (!"http".equals(scheme) && !"https".equals(scheme) && !"blob".equals(scheme)) return true;
                // 挡广告跳转：整页跳到别的站，一律不放行——不管带不带手势。
                // 广告常劫持点击（点播放也跳广告），带手势照样是广告；本片的选集/片名/站内导航
                // 都在本站内，放行；站内跳转与子域也放行。
                String targetHost = request.getUrl().getHost();
                String currentHost = null;
                try {
                    String cur = view.getUrl();
                    if (!TextUtils.isEmpty(cur)) currentHost = Uri.parse(cur).getHost();
                } catch (Exception ignored) {
                }
                if (TextUtils.isEmpty(currentHost)) {
                    try {
                        if (!TextUtils.isEmpty(mAnchorUrl)) currentHost = Uri.parse(mAnchorUrl).getHost();
                    } catch (Exception ignored) {
                    }
                }
                // v107：拦弹窗广告路径——MissAV 点播调 window.open('/pop')，壳子关了多窗口，
                // 弹窗地址被当成主框导航，同站放行后主框被空白 /pop 劫走（片页丢、全页按钮死）。
                // /pop 本身空白且立即跳广告域名，不是正片/分类内容。
                if (isPopunderPath(request.getUrl().getPath())) {
                    return true;
                }
                // push v23 P2'：点按劫持守卫（放在 v61/v74 之前，只会多拦）——用户点的是本站 <a>，去向却是外站
                // （广告脚本在同一次点击里改走 location.href）→ 拦；服务器 301/302（isRedirect）与 v92 中转豁免；
                // 点外站链接/按钮/没点按一律不介入（发布页、防丢页自动跳不受影响）。拦下后打开用户实际点的本站链接。
                String[] clickMark = mClickMark;
                if (isClickHijack(clickMark, view.getUrl(), request.getUrl().toString(), request.isRedirect())) {
                    mClickMark = null;
                    DebugLogStore.add(LogCategory.AD, "WebPlay", "push v23 点按劫持拦截(主框) 点=" + clickMark[0] + " 去=" + request.getUrl(), false);
                    restoreClickTarget(view, clickMark[0]);
                    return true;
                }
                // push v30 R32-3：外站链接被改道——用户点（或按下）的是外站 <a>，带手势的主框导航去的却是第三个站
                //（既不是所点链接的站、也不是本站；非 301/302、非 v92 中转、去向地址里不带所点域名）→ 广告脚本把这一下
                // 送进了广告链：拦下，打开用户实际点的那个外站链接。只认带链接地址的点/按记录（javascript: 按钮如
                // PLMM「无需等待」记录为空、不介入）；无手势的自动跳转（发布页、防丢页）不介入。
                if (request.isForMainFrame() && request.hasGesture()) {
                    String reqUrl3 = request.getUrl().toString();
                    String[] fm = mClickMark;
                    if (!isForeignClickDetour(fm, view.getUrl(), reqUrl3, request.isRedirect())) fm = null;
                    if (fm == null && isForeignClickDetour(mPressMark, view.getUrl(), reqUrl3, request.isRedirect())) fm = mPressMark;
                    if (fm != null) {
                        mClickMark = null;
                        mPressMark = null;
                        DebugLogStore.add(LogCategory.AD, "WebPlay", "push v30 外站点击被改道 点=" + fm[0] + " 去=" + reqUrl3, false);
                        restoreClickTarget(view, fm[0]);
                        return true;
                    }
                }
                // v61：发布站/跳转页上的人工点按放行——主页本身就是发布站/防丢url 或 [工具:重定向] 跳转页时，
                // 用户在该页上点按进真实站是正常操作（有些发布站不自动跳，必须人工点）。只放行「当前页就是跳转页」
                // 且带手势的点按；目标是已知广告域名时照样拦。v76：落到真实站即清 mJumpHost，这里不再生效
                //（之前 mJumpHost 会跟到真实站，假分类/假视频也被放行）；防广告劫持的规矩照旧。
                if (request.hasGesture() && !TextUtils.isEmpty(mJumpHost)
                        && !TextUtils.isEmpty(currentHost) && mJumpHost.equalsIgnoreCase(currentHost)
                        && (TextUtils.isEmpty(targetHost) || !isAd(targetHost))) {
                    // v73:放行的点按到达新页后,让 mJumpHost 跟到新页一次(修 v72 mUserGestured 缺陷)。
                    mFollowJumpHost = true;
                    // v76：记下这次跳转（起跳 host），真实站落地时清 mJumpHost。
                    mJumpUsed = true;
                    mJumpOriginHost = currentHost;
                    return false;
                }
                // v74：跳转页上的无手势跳转放行——发布站/中间页自动跳到最新真实域名时，
                // 目标域名根本不含当前页域名（shewo1.cc → shewo49.cc、vip1949.com → zzztool.com），
                // v92（目标含当前 host）永远对不上。当前页就是跳转页（mJumpHost==当前 host）
                // 且目标不是广告域名时，无手势也放行；广告域名照样拦。
                // 这条同时打通两处：中间页 location.href 自动跳转，与 onCreateWindow 放行后
                // loadUrl 的二次拦截（程序调 loadUrl 没有手势）。
                if (!request.hasGesture() && !TextUtils.isEmpty(mJumpHost)
                        && !TextUtils.isEmpty(currentHost) && mJumpHost.equalsIgnoreCase(currentHost)
                        && (TextUtils.isEmpty(targetHost) || !isAd(targetHost))) {
                    // v76：记下这次跳转（起跳 host），真实站落地时清 mJumpHost。
                    mJumpUsed = true;
                    mJumpOriginHost = currentHost;
                    return false;
                }
                if (!TextUtils.isEmpty(targetHost) && !TextUtils.isEmpty(currentHost)
                        && !sameSite(targetHost, currentHost)) {
                    // v92：页面自发的中转跳转放行（Liu 2026-11-14 定）——无手势（页面加载时程序自己点的，
                    // 不是用户点按）且目标地址里带了当前页地址（中转站模式：?u=<当前页>&p=<路径>，拿当前地址去换最新域名）。
                    // 用户点按触发的跨站跳转（广告劫持：透明层盖按钮，touchend 送走）照样拦；广告地址不带当前页地址，
                    // 即使延迟出点按处理丢了手势，第二条也拦着。
                    if (!request.hasGesture() && isJumpServiceUrl(request.getUrl().toString(), currentHost)) {
                        // v93：记下这条跳转链（起跳站 + 中转站），302 跟进时认。
                        mJumpAnchorHost = currentHost;
                        mJumpRelayHost = targetHost;
                        mJumpChainUntil = SystemClock.uptimeMillis() + 10000;
                        // v76：记下这次跳转（起跳 host），真实站落地时清 mJumpHost。
                        mJumpUsed = true;
                        mJumpOriginHost = currentHost;
                        return false;
                    }
                    // v93：已放行跳转的 302 跟进——无手势 + 10 秒内 + 起跳站/中转站仍是当前页
                    //（跳转链里 view.getUrl() 仍是起跳站地址，页面未落地）。点按劫持带手势，第一条就拦；
                    // 没放行过跳转时 mJumpAnchorHost 为空，这里不生效。
                    if (!request.hasGesture() && mJumpAnchorHost != null
                            && SystemClock.uptimeMillis() < mJumpChainUntil
                            && (mJumpAnchorHost.equals(currentHost) || mJumpRelayHost.equals(currentHost))) {
                        mJumpChainUntil = SystemClock.uptimeMillis() + 10000;
                        return false;
                    }
                    // v106：只 return true，不 stopLoading()——v101 的 stopLoading() 会把当前片页也杀成空白
                    //（23:34:06 点播后广告跳转被拦，紧随其后 page videos=0 titleLen=0；只拦跳转、页面留着）。
                    return true;
                }
                return false;
            }


            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (url == null || url.equals("about:blank")) return;
                // v72：初始页跳转跟进——防丢url/发布站自动跳到真正的发布页时（如 n.plmmttv7y7m.com → vip1949.com），
                // mJumpHost 还是起跳站 host，与当前页对不上，v61 例外不生效。用户点按前一次性跟进到新 host；
                // 新 host 是广告域名时不跟进；用户一点按就不再跟进。
                // v73:mFollowJumpHost——经放行的点按/新窗口到达新页时也跟一次(修 v72 mUserGestured 缺陷:
                // mUserGestured 一旦置 true 永不复位,点按到达发布页后 mJumpHost 卡在旧 host,v61 永远对不上)。
                boolean followJumpHost = !mUserGestured || mFollowJumpHost;
                mFollowJumpHost = false;
                // push v23 P2'：新页开始导航——清点按记录（新页的点按重新记），记下主框去向供还原判断。
                mClickMark = null;
                mPressMark = null;
                mClickLastStarted = url;
                if (followJumpHost && !TextUtils.isEmpty(mJumpHost)) {
                    try {
                        String newHost = Uri.parse(url).getHost();
                        if (!TextUtils.isEmpty(newHost) && !newHost.equalsIgnoreCase(mJumpHost) && !isAd(newHost)) {
                            mJumpHost = newHost;
                        }
                    } catch (Exception ignored) {
                    }
                }
                // v76：跳转已用且新页 host 与起跳 host 不同 → 跳转完成（落到真实站），清 mJumpHost；
                // 此后真实站上的跨站点击不再走 v61/v74 放行（假分类/假视频点按即拦），CLEAN_JS 的 __museJumpDone 也随之置真。
                // 同 host 新页（shewo 中间页）不清除；用户点按前（mJumpUsed 假，如防丢 url 预跳）不清除。
                if (mJumpUsed && mJumpOriginHost != null) {
                    try {
                        String landHost = Uri.parse(url).getHost();
                        if (!TextUtils.isEmpty(landHost) && !landHost.equalsIgnoreCase(mJumpOriginHost)) {
                            mJumpHost = "";
                            mJumpUsed = false;
                            mJumpOriginHost = null;
                        }
                    } catch (Exception ignored) {
                    }
                }
                // v96：先藏 webview，等 CLEAN_JS 首扫跑完再显示——hsck 首页顶部 58 条赌博横幅在首扫前会闪约 0.25 秒。
                mBlankRecovered = false;
                if (mBlankRecheck != null) view.removeCallbacks(mBlankRecheck);
                view.setVisibility(View.INVISIBLE);
                if (mForceShowWeb != null) view.removeCallbacks(mForceShowWeb);
                mForceShowWeb = () -> view.setVisibility(View.VISIBLE);
                view.postDelayed(mForceShowWeb, 5000);
                // push v1：导航开始，清多线探针状态（海报 mPushPagePic 不清，活过详情→播放页一跳）；记当前页地址。
                mPushPageUrl = url;
                mPushProbeLines = "";
                mPushPageContent = "";
                mPushPageYear = "";
                mPushPageArea = "";
                mPushPageOgPic = "";
                mPushMetaPageUrl = "";
                mPushMainStream = "";
                mPushAdSrcs = java.util.Collections.emptySet();
                mPushAdPlaying = false;
                // push v14：嗅流按页隔离——切页即清旧流/旧头/嗅流页与待续播，旧页的流绝不许占位到新页。
                mPushLastSniff = null;
                mPushSniffAt = 0;
                mPushSniffPageUrl = "";
                mPushHasMainPlayer = false;
                mPushSniffedHeaders = null;
                // push v25 (B)：页面兜底待解单的导航已开始（之后的 onPageFinished 才算目标页落地）。
                if (mBrowserResolveCallback != null && mBrowserResolvePageMode) {
                    mBrowserResolvePageStarted = true;
                    mBrowserResolvePageDone = false;
                    mBrowserResolveSniff = "";
                }
                mPushPlayPending = false;
                // push v14：导航开始先藏推送钮——旧页嗅流亮起的钮不许带到新页，等新页探针跑完再由 updatePushButton 决定。
                if (mBinding != null && mBinding.pushPlay != null) mBinding.pushPlay.setVisibility(View.GONE);
                cancelPushProbeRetry();
                cancelPushProbePreloadPoll();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (url == null || url.equals("about:blank")) return;
                evalCleanJs(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url == null || url.equals("about:blank")) return;
                // v93：页面落地，跳转链路结束（再有跨站跳转就是新的，不再跟进）。
                mJumpAnchorHost = null;
                mJumpRelayHost = null;
                mJumpChainUntil = 0;
                evaluate(getScript(url), 0);
                // v97：不再立即显示——等 CLEAN_JS 回调再显示，否则分类页加载快会抢在首扫前闪出横幅墙（同 onPageCommitVisible）。
                evalCleanJs(view);
                // v77：直接进站也算“跳转完成”——入口页加载完，若是真实站（含 ≥2 站内正片链接），清 mJumpHost 激活 v76 规则；发布站页无站内正片链接，不触发。
                if (!TextUtils.isEmpty(mJumpHost)) {
                    String curHost = null;
                    try { curHost = Uri.parse(url).getHost(); } catch (Exception ignored) {}
                    if (!TextUtils.isEmpty(curHost) && curHost.equalsIgnoreCase(mJumpHost)) {
                        view.evaluateJavascript("(function(){var as=document.querySelectorAll('a[href]');var n=0;for(var i=0;i<as.length;i++){var h=as[i].getAttribute('href')||'';if(h.indexOf('/vodtype/')===0||h.indexOf('/voddetail/')===0||h.indexOf('/vodplay/')===0){if(++n>=2)return true;}}return false;})()", value -> {
                            if ("true".equals(value)) {
                                mJumpHost = "";
                                mJumpUsed = false;
                                mJumpOriginHost = null;
                            }
                        });
                    }
                }
                // v56：把 Cookie 落盘——cf_clearance 只留在内存里时进程一死验证就丢；flush 后重进、刷新仍有效。
                CookieManager.getInstance().flush();
                // v100：空白页回滚——被拦的广告跳转有时会把当前页卸成空文档（标题空、无视频、无图），
                // 点什么都没反应。检出空白就回滚到入口地址一次（mAnchorUrl），避免死在空白页。
                // v56：首次检出空白不立即回滚——Nuxt 等 SPA 壳在 onPageFinished 时尚未水合，标题/正文/图
                // 暂时全空是正常现象；延时 1.5 秒复核，仍空白才回滚，避免刷新被误判成空白页。
                String blankJs = "(function(){try{var t=(document.title||'').trim()==='';var b=(document.body?document.body.innerText.trim():'')==='';var n=document.images.length;return (t?'1':'0')+','+(b?'1':'0')+','+n;}catch(e){return '0,0,0';}})()";
                view.evaluateJavascript(blankJs, blankVal -> {
                    String val = blankVal;
                    if (val != null && val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                    if (!"1,1,0".equals(val)) return;
                    if (mBlankRecovered || TextUtils.isEmpty(mAnchorUrl) || mAnchorUrl.equals(url)) return;
                    if (mBlankRecheck != null) view.removeCallbacks(mBlankRecheck);
                    mBlankRecheck = () -> view.evaluateJavascript(blankJs, reVal -> {
                        String re = reVal;
                        if (re != null && re.length() >= 2 && re.startsWith("\"") && re.endsWith("\"")) re = re.substring(1, re.length() - 1);
                        if (!"1,1,0".equals(re)) return;
                        if (mBlankRecovered || TextUtils.isEmpty(mAnchorUrl) || mAnchorUrl.equals(url)) return;
                        mBlankRecovered = true;
                        DebugLogStore.add("WebPlay", "blank page, reload anchor url=" + mAnchorUrl);
                        view.loadUrl(mAnchorUrl);
                    });
                    view.postDelayed(mBlankRecheck, 1500);
                });
                // push v1：页完探一次多线表（MacCMS 播放页才够格亮起播放按钮）。
                mPushPageUrl = url;
                probePageInfo();
                // push v19 (B)：页面兜底导航落地时再静音一次——新页播放器自动播放时后台不得出声。
                // 只在有待解单时执行；(c) 点按钮路不导航，正常不触发，若触发也是静音，本就该静。
                if (mBrowserResolveCallback != null) {
                    try {
                        view.evaluateJavascript(MUTE_ALL_VIDEOS_JS, null);
                    } catch (Throwable ignored) {
                    }
                    // push v25 (B)：目标页落地——先跑探针，有主片交主片；无主片且已嗅到流才交嗅流。
                    if (mBrowserResolvePageMode && mBrowserResolvePageStarted) {
                        mBrowserResolvePageDone = true;
                        tryDeliverPageResolve(false);
                    }
                }
                // push v3：选集表可能是 JS 后渲染的，页完那一刻还没进 DOM——0.5/1.5/3 秒各补跑一次，探到可用线路就停。
                schedulePushProbeRetry();
                showBars();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                // v92 金牌诊断：主框错误记下来，看页面是不是加载失败变黑。
                // v95：子资源错误也记（去重 + 上限 20 条）——JS 分块加载失败会让页面水合卡住变黑，之前只记主框看不到。
                if (request == null) return;
                String errUrl = String.valueOf(request.getUrl());
                if (request.isForMainFrame()) {
                    DebugLogStore.add(LogCategory.RUN, "WebPlay", "page error url=" + errUrl + " code=" + error.getErrorCode() + " desc=" + error.getDescription(), true);
                } else if (mResErrUrls.size() < 20 && mResErrUrls.add(errUrl)) {
                    DebugLogStore.add(LogCategory.RUN, "WebPlay", "res error url=" + errUrl + " code=" + error.getErrorCode(), true);
                }
            }

            @Override
            @SuppressLint("WebViewClientOnReceivedSslError")
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        };
    }

    private WebChromeClient webChromeClient() {
        return new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // v73:拦截新窗口请求(target="_blank" / window.open),在当前 WebView 打开。
                // 新窗口的 URL 不直接给出:建一个临时 WebView 接住它,在其 shouldOverrideUrlLoading 里
                // 用 isAllowedNewWindowUrl 判断(与 shouldOverrideUrlLoading 同一套规则)。
                // 注意:这里只处理新窗口请求;同标签的自动跨域跳转走 shouldOverrideUrlLoading
                // (v74:跳转页到非广告域名放行,其余照旧拦截)。
                // push v23 P2'：新窗口创建这一刻（站方 window.open 同步触发，必早于异步拿到的广告地址）快照点按记录
                // 与所在页；真实地址到来时按快照判，不受主框之后跳走的影响（无竞态、不计时）。
                final String[] clickSnap = mClickMark;
                final String[] pressSnap = mPressMark;
                final String clickPage = view.getUrl();
                WebView tempWebView = new WebView(view.getContext());
                tempWebView.setWebViewClient(new WebViewClient() {
                    private boolean mHandled = false;

                    private void handleNewWindowUrl(String url, boolean redirect) {
                        if (mHandled) return;
                        // v74：空白页不占标记、不销毁——新窗口先报 about:blank 时，等真正的 URL 再判断。
                        if (TextUtils.isEmpty(url) || url.equals("about:blank")) return;
                        mHandled = true;
                        if (isClickHijack(clickSnap, clickPage, url, redirect)) {
                            // push v23 P2'：点的是本站链接、弹出的却是外站 → 劫持：丢弃该窗，不装进主页面；
                            // 还原用户本意（打开所点的本站链接，主框已在去的路上则不重复）。
                            DebugLogStore.add(LogCategory.AD, "WebPlay", "push v23 点按劫持拦截(弹窗) 点=" + clickSnap[0] + " 去=" + url, false);
                            restoreClickTarget(view, clickSnap[0]);
                        } else if (isTapHijack(clickSnap, clickPage, url, redirect, isUserGesture)
                                || isTapHijack(pressSnap, clickPage, url, redirect, isUserGesture)) {
                            // push v28 F2a：点/按的是本站任意元素（按钮、菜单等），弹出的却是外站，且外站地址不在
                            // 被点元素及其祖先的 href/onclick/data-* 属性里 → 劫持：丢弃该窗（只在新窗口路径，主框不动）。
                            // push v29 R31-1：判定不变，拦下后还原用户本意——广告脚本在 touchend 吞了 click（点=-），
                            // 按下记录里若是本站链接就由壳子打开它；不是链接（按钮/菜单）则约 0.6 秒后对被按元素补一次点击
                            //（只为避开广告脚本写死的 500ms 事件锁，不拿时长判广告；按下后已收到真实点击则不补）。
                            String restoreHref = sameSiteMarkHref(clickSnap, clickPage);
                            if (TextUtils.isEmpty(restoreHref)) restoreHref = sameSiteMarkHref(pressSnap, clickPage);
                            DebugLogStore.add(LogCategory.AD, "WebPlay", "push v28 点按劫持拦截(弹窗·元素) 去=" + url + " 手势=" + isUserGesture
                                    + " 点=" + (clickSnap == null ? "-" : (clickSnap[0].isEmpty() ? "元素" : clickSnap[0])), false);
                            if (!TextUtils.isEmpty(restoreHref)) {
                                DebugLogStore.add(LogCategory.AD, "WebPlay", "push v29 拦后打开所按链接 " + restoreHref, false);
                                restoreClickTarget(view, restoreHref);
                            } else {
                                view.postDelayed(() -> {
                                    if (!clickNoHash(clickPage).equals(clickNoHash(view.getUrl()))) return;
                                    view.evaluateJavascript("window.__museReplayPress?window.__museReplayPress():'nofn'",
                                            v -> DebugLogStore.add(LogCategory.AD, "WebPlay", "push v29 拦后补点 结果=" + v, false));
                                }, 600);
                            }
                        } else if (isUserGesture && (isForeignClickDetour(clickSnap, clickPage, url, redirect)
                                || isForeignClickDetour(pressSnap, clickPage, url, redirect))) {
                            // push v30 R32-3：点/按的是外站 <a>，弹出的却是第三个站 → 丢弃该窗，打开用户点的那个外站链接。
                            String[] fm = isForeignClickDetour(clickSnap, clickPage, url, redirect) ? clickSnap : pressSnap;
                            DebugLogStore.add(LogCategory.AD, "WebPlay", "push v30 外站点击被改道(弹窗) 点=" + fm[0] + " 去=" + url, false);
                            restoreClickTarget(view, fm[0]);
                        } else if (isAllowedNewWindowUrl(url, isUserGesture)) {
                            // 放行:在当前 WebView 打开;让 mJumpHost 跟到新页(修 v72 mUserGestured 缺陷)。
                            DebugLogStore.add(LogCategory.AD, "WebPlay", "push v28 放行新窗口 目标=" + url + " 手势=" + isUserGesture
                                    + " 点=" + (clickSnap == null ? "-" : (clickSnap[0].isEmpty() ? "元素" : clickSnap[0])), false);
                            mFollowJumpHost = true;
                            view.loadUrl(url);
                        }
                        // 拦:什么都不做。销毁临时 WebView。
                        tempWebView.post(tempWebView::destroy);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        handleNewWindowUrl(request.getUrl().toString(), request.isRedirect());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        // 兜底:若 shouldOverrideUrlLoading 未被调用,在这里拦截。
                        handleNewWindowUrl(url, false);
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tempWebView);
                resultMsg.sendToTarget();
                return true;
            }
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mBinding.progress.setProgress(newProgress);
                mBinding.progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                // v92 金牌诊断：只记页面报错，看 JS 是不是报错导致页面黑掉。
                // v95：按文案去重（上限 20 条不同报错）——之前按条数截前 10 条，AudioContext 刷 10 次就把真正的报错盖住了。
                if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String msg = consoleMessage.message();
                    if (msg != null && mConsoleErrSeen.size() < 20 && mConsoleErrSeen.add(msg)) {
                        DebugLogStore.add(LogCategory.RUN, "WebPlay", "console error " + msg + " @" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber(), true);
                    }
                }
                return super.onConsoleMessage(consoleMessage);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (mCustomView != null) {
                    onHideCustomView();
                    return;
                }
                mCustomView = view;
                mCustomCallback = callback;
                mBinding.fullscreen.addView(view);
                mBinding.fullscreen.setVisibility(View.VISIBLE);
                mBinding.topBar.setVisibility(View.GONE);
                // 网页全屏一律横屏，不看系统自动旋转开关；退出全屏再恢复
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }

            @Override
            public void onHideCustomView() {
                if (mCustomView == null) return;
                mBinding.fullscreen.removeView(mCustomView);
                mBinding.fullscreen.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                if (mCustomCallback != null) mCustomCallback.onCustomViewHidden();
                mCustomView = null;
                mCustomCallback = null;
                showBars();
            }
        };
    }

    /** 奇优站内限定：当前站是奇优才拦 gdw.js/xs2.js。站 key 含 qiyou、站名含「奇优」、入口地址 host 含 qiyou，三者任一即认。 */
    private boolean isQiyouSite() {
        String k = mKey == null ? "" : mKey.toLowerCase(Locale.US);
        if (k.contains("qiyou")) return true;
        if (mTitle != null && mTitle.contains("奇优")) return true;
        try {
            if (!TextUtils.isEmpty(mAnchorUrl)) {
                String h = Uri.parse(mAnchorUrl).getHost();
                if (h != null && h.toLowerCase(Locale.US).contains("qiyou")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 广告加载器（混淆 js 拉的远程广告脚本，域名按日期轮换）：按子串拦，通用、不只麦田。 */
    private boolean isAdLoaderUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase(Locale.US);
        if (u.contains("douytmvbf")) return true; // v3 PLMM 底飘：d.douytmvbf.site 是底条广告脚本加载器（见 jump1_reinvest/report.md §2.2）；域名固定、后缀随机，按子串拦。
        // v94 按活动根除：域名/端口/活动编号全在轮换（8265→8210→1244），但路径模板 /cc|/d|/sc/<活动编号> 是活动的骨架——
        // 换了它等于重写加载器和服务器。路径是 /cc|/d|/sc/<数字> 且端口不是 80/443 就拦，下个活动换什么域名、端口、编号都不用再出包；
        // 视频后缀永不拦（金牌播片 host 119.8.55.111:81 的地址是媒体路径，不撞）。
        if (isAdLoaderCampaignPath(u)) return true;
        // v91 根上拦：域名会轮换，但端口 + 路径是广告网的基础设施——8004/8005（脚本/劫持）与 20094/20095（WS 远程代码），
        // v93 再加 902（1244 活动脚本/劫持）与 992（1244 WS 远程代码）；路径固定 /cc|/d|/sc/数字 或 /数字；域名随便换都拦。
        // 正常站不用这些端口拉资源。v94 起退成后备（只补裸 /数字 路径那一档）。
        if (isAdLoaderPortPath(u)) return true;
        // 麦田网（8265）：8265c.4gl3g / 8265t.1x4g6f2（8004 端口）+ aizhantj 统计 + WS 远程代码。
        // 耐看网（8210，同套混淆加载器模板、另一套域名）：8210gc.mehqk5u.com（劫持 /cc/8210 + 远程脚本 /d/8210，8005 端口）、
        // 8210g.smun7ro.com（备用 /sc/8210）、WS 远程代码 gw.67x8epk.com / gw.kvkluj8.com / gw.lw1su4n.com（20095 端口）、广告 id brlvflxk。
        // 麦田网新活动（1244，2026-11-14 实测复现）：<日期>1244c.hcxftcl.com（劫持 /cc/1244 + 远程脚本 /d/1244，902 端口）、
        // <日期>1244g.nf65hwr.com（备用 /sc/1244）、WS 远程代码 z.3dzmgtc.com / z.dkepyys.com / z.hv7hpkk.com（992 端口；wss 不进拦截器，
        // 且只在百度/华为等浏览器 UA 下才开，壳子 WebView 不走这条）、广告 id rjhtcqjm。8265 已停，1244 接替。
        return u.contains("8265c") || u.contains("8265t") || u.contains("4gl3g") || u.contains("1x4g6f2") || u.contains("aizhan") || u.contains("0cy5aps") || u.contains("ax8rylb") || u.contains("7xkpxuw")
                || u.contains("8210gc") || u.contains("mehqk5u") || u.contains("smun7ro") || u.contains("67x8epk") || u.contains("kvkluj8") || u.contains("lw1su4n") || u.contains("brlvflxk")
                || u.contains("1244c") || u.contains("1244g") || u.contains("hcxftcl") || u.contains("nf65hwr") || u.contains("3dzmgtc") || u.contains("dkepyys") || u.contains("hv7hpkk") || u.contains("rjhtcqjm");
    }

    /** v91：广告加载器的端口 + 路径指纹（与域名无关）。端口 8004/8005/20094/20095，路径 /cc|/d|/sc/数字 或 /数字。 */
    private boolean isAdLoaderPortPath(String u) {
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) return false;
        int hostStart = schemeEnd + 3;
        int slash = u.indexOf('/', hostStart);
        if (slash < 0) return false;
        int colon = u.lastIndexOf(':', slash);
        if (colon <= hostStart) return false;
        int port;
        try {
            port = Integer.parseInt(u.substring(colon + 1, slash));
        } catch (NumberFormatException e) {
            return false;
        }
        if (port != 8004 && port != 8005 && port != 902 && port != 20094 && port != 20095 && port != 992) return false;
        String path = u.substring(slash);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        String[] parts = path.split("/");
        // /cc/8210、/d/8210、/sc/8210 -> ["", "cc", "8210"]；/8210（WS） -> ["", "8210"]
        if (parts.length == 3 && ("cc".equals(parts[1]) || "d".equals(parts[1]) || "sc".equals(parts[1])) && isDigits(parts[2])) return true;
        if (parts.length == 2 && isDigits(parts[1])) return true;
        return false;
    }

    /** v94：按活动根除的通用规则——路径 /cc|/d|/sc/<数字> + 显式非常用端口（非 80/443）。域名、端口、活动编号随便换都拦；
     *  裸 /<数字> 路径不进这条（只在已知广告端口下才拦，见 isAdLoaderPortPath），免得误伤正经接口；视频后缀永不拦。 */
    private boolean isAdLoaderCampaignPath(String u) {
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) return false;
        int hostStart = schemeEnd + 3;
        int slash = u.indexOf('/', hostStart);
        if (slash < 0) return false;
        int colon = u.lastIndexOf(':', slash);
        if (colon <= hostStart) return false; // 没写端口 = 默认端口（80/443），不是加载器
        int port;
        try {
            port = Integer.parseInt(u.substring(colon + 1, slash));
        } catch (NumberFormatException e) {
            return false;
        }
        if (port == 80 || port == 443) return false;
        String path = u.substring(slash);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        if (isMediaUrl(path)) return false;
        String[] parts = path.split("/");
        // /cc/1244、/d/1244、/sc/1244 -> ["", "cc", "1244"]
        if (parts.length == 3 && ("cc".equals(parts[1]) || "d".equals(parts[1]) || "sc".equals(parts[1])) && isDigits(parts[2])) return true;
        return false;
    }

    private boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** v107：弹窗广告路径——点播/点按触发的 window.open 弹窗，壳子单窗口时会被当成主框导航。
     * 特征：短路径、空白页、立即跳广告域名。只拦这几个通用弹窗路径名，不碰正片/分类地址
     *（片名、分类、选集路径里不会单独叫 pop/popunder/popup）。 */
    private boolean isPopunderPath(String path) {
        if (TextUtils.isEmpty(path)) return false;
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.equals("/pop") || p.equals("/popunder") || p.equals("/popup")
                || p.endsWith("/pop") || p.endsWith("/popunder") || p.endsWith("/popup");
    }

    /** 同站：同域名，或互为子域名（如 mtyy2.cc 与 www.mtyy2.cc），
     * 或兄弟子域（如 www.hkybqufgh.com 与 m.hkybqufgh.com——金牌首页 302 到 m 站，
     * 旧判法当跨站拦掉，webview 一片白）。 */
    private boolean sameSite(String a, String b) {
        if (a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a)) return true;
        String ra = registrableDomain(a), rb = registrableDomain(b);
        return ra != null && ra.equals(rb);
    }

    /** v92：中转站跳转——目标地址里带了当前页地址（域名）。hsck 发布页拼的是
     * https://32d233.com:8899/?u=http://hsck.net/&p=/ ，中转站拿 u= 去换最新域名，再 302 跟过去。
     * URL 编码的 u=（u=http%3A%2F%2Fhsck.net%2F）里域名本身不编码，contains 照样命中。 */
    private boolean isJumpServiceUrl(String targetUrl, String currentHost) {
        if (targetUrl == null || targetUrl.isEmpty() || currentHost == null || currentHost.isEmpty()) return false;
        return targetUrl.toLowerCase(Locale.US).contains(currentHost.toLowerCase(Locale.US));
    }

    /** 注册域（eTLD+1 的近似）：多级公共后缀取后三段，其余取后两段。 */
    private static String registrableDomain(String host) {
        if (host == null) return null;
        String[] parts = host.toLowerCase(java.util.Locale.ROOT).split("\\.");
        if (parts.length < 2) return host;
        String last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
        // 常见多级公共后缀（co.uk / com.cn 等），这些上面再取一段才是注册域。
        if (parts.length >= 3 && ("co.uk,org.uk,ac.uk,com.cn,net.cn,org.cn,gov.cn,edu.cn,co.jp,com.au,net.au,org.au,com.hk,com.tw,co.kr,com.sg".contains(last2))) {
            return parts[parts.length - 3] + "." + last2;
        }
        return last2;
    }

    /** v73:新窗口请求(target="_blank" / window.open)放行判断,与 shouldOverrideUrlLoading 同一套规则。
     * 只放行合法跳转:发布页人工点按到非广告域名、同站、中转站;其余一律拦(自动跨域跳转、/pop、广告域名)。
     * 注意:自动跨域跳转(无手势、非中转、无线链)在这里也是拦——不要把它改成放行。 */
    /** v76：跑 CLEAN_JS 前先把跳转状态（真实站落地与否）告诉页面，供假链接/广告墙规则用。 */
    private void evalCleanJs(WebView view) {
        String flag = "window.__museJumpDone=" + (TextUtils.isEmpty(mJumpHost) ? "true" : "false") + ";";
        view.evaluateJavascript(flag + CLICK_MARK_JS + CLEAN_JS, value -> view.setVisibility(View.VISIBLE));
    }

    /** push v23 P2'：点按意图记录脚本——window 捕获阶段（早于站方在 document 上的捕获监听、早于广告脚本的 window.open）
     * 记下真实点按（isTrusted）所点的本站 <a> 地址交给 Java；href 为空/# 锚点/javascript:/指向本页的，按「非链接点按」记空。
     * 只读 DOM、不改页面、不阻止事件；每页只装一次。 */
    private static final String CLICK_MARK_JS = "(function(){try{if(window.__museClickMark)return;window.__museClickMark=1;function at(t0){var o='';var t=t0;while(t&&t.nodeType===1&&o.length<4000){var as=t.attributes;if(as){for(var i=0;i<as.length;i++){var n=String(as[i].name||'').toLowerCase();if(n==='href'||n==='onclick'||n==='action'||n==='formaction'||n.indexOf('data-')===0)o+=String(as[i].value||'')+' ';}}t=t.parentNode;}return o.slice(0,4000).toLowerCase();}function rec(e,kind){try{if(!e.isTrusted)return;var b=window.museClickGuard;if(!b||!b.mark)return;var t=e.target,a=null;while(t&&t!==document){if(t.tagName&&String(t.tagName).toUpperCase()==='A'&&t.getAttribute&&t.getAttribute('href')!==null){a=t;break;}t=t.parentNode;}var h='';if(a){var raw=(a.getAttribute('href')||'').trim();if(raw&&raw.charAt(0)!=='#'&&!/^javascript:/i.test(raw)){var abs=a.href;if(typeof abs==='string'&&/^https?:/i.test(abs)&&abs.split('#')[0]!==location.href.split('#')[0])h=abs;}}b.mark(h,location.href,at(e.target),kind);}catch(x){}}function pel(e){try{if(!e.isTrusted)return;window.__musePressEl=e.target;window.__musePressClicked=false;}catch(x){}}window.__museReplayPress=function(){try{var el=window.__musePressEl;if(!el)return 'none';if(window.__musePressClicked)return 'clicked';if(!el.isConnected||el.ownerDocument!==document)return 'gone';window.__musePressClicked=true;el.click();return 'ok:'+String(el.tagName||'').toLowerCase();}catch(x){return 'err';}};window.addEventListener('click',function(e){try{if(e.isTrusted)window.__musePressClicked=true;}catch(x){}rec(e,'click');},true);window.addEventListener('mousedown',function(e){pel(e);rec(e,'press');},true);window.addEventListener('pointerdown',function(e){pel(e);rec(e,'press');},true);}catch(e){}})();";

    /** push v23 P2'：JS 桥——只写点按记录，永不放行（即便广告 iframe 伪造调用，最多让壳子多拦它自己的跳转）。 */
    private final class ClickMarkBridge {
        @android.webkit.JavascriptInterface
        public void mark(String href, String docUrl, String attrs, String kind) {
            String[] m = new String[]{href == null ? "" : href, docUrl == null ? "" : docUrl, attrs == null ? "" : attrs};
            // push v28 F2a：按下（mousedown/pointerdown）另存一份，只给新窗口路径用；点按（click）照 v23 写 mClickMark。
            if ("press".equals(kind)) mPressMark = m;
            else mClickMark = m;
        }
    }

    private static String clickNoHash(String url) {
        if (url == null) return "";
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }

    private static String clickHostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    /** push v23 P2'：点按劫持判定（全满足才算）——①有点按记录且点的是 <a>；②记录属于当前页；③所点链接与该页同站；
     * ④去向与所点链接不同站；⑤不是服务器重定向；⑥不是 v92 中转链接。不看页面是不是发布站、不数链接、不认尺寸/时长。 */
    private boolean isClickHijack(String[] mark, String pageUrl, String target, boolean redirect) {
        if (mark == null || mark.length < 2 || redirect) return false;
        String href = mark[0];
        String doc = mark[1];
        if (TextUtils.isEmpty(href) || TextUtils.isEmpty(doc) || TextUtils.isEmpty(pageUrl) || TextUtils.isEmpty(target)) return false;
        if (!clickNoHash(doc).equals(clickNoHash(pageUrl))) return false;
        String docHost = clickHostOf(doc);
        String hrefHost = clickHostOf(href);
        String targetHost = clickHostOf(target);
        if (docHost.isEmpty() || hrefHost.isEmpty() || targetHost.isEmpty()) return false;
        if (!sameSite(hrefHost, docHost)) return false;
        if (sameSite(targetHost, hrefHost)) return false;
        if (isJumpServiceUrl(target, docHost)) return false;
        return true;
    }

    /** push v28 F2a：新窗口路径的元素点按劫持判定（全满足才算）——①有手势；②有点/按记录且属于当前页；
     * ③记录是本站链接时按 v23 原判；记录不是链接（按钮/菜单/div 等）时：去向与该页不同站，且去向域名
     * 不出现在被点元素及其祖先的 href/onclick/action/data-* 属性里；④不是服务器重定向；⑤不是 v92 中转链接。
     * 只在新窗口（onCreateWindow）路径调用，主框导航不走这里（PLMM 防丢页「无需等待」是主框 JS 跳转，不受影响）。 */
    private boolean isTapHijack(String[] mark, String pageUrl, String target, boolean redirect, boolean gesture) {
        if (!gesture || mark == null || mark.length < 3 || redirect) return false;
        if (!TextUtils.isEmpty(mark[0])) return isClickHijack(mark, pageUrl, target, false);
        String doc = mark[1];
        if (TextUtils.isEmpty(doc) || TextUtils.isEmpty(pageUrl) || TextUtils.isEmpty(target)) return false;
        if (!clickNoHash(doc).equals(clickNoHash(pageUrl))) return false;
        String docHost = clickHostOf(doc);
        String targetHost = clickHostOf(target);
        if (docHost.isEmpty() || targetHost.isEmpty()) return false;
        if (sameSite(targetHost, docHost)) return false;
        if (isJumpServiceUrl(target, docHost)) return false;
        String attrs = mark[2] == null ? "" : mark[2];
        if (!attrs.isEmpty()) {
            if (attrs.contains(targetHost)) return false;
            String root = registrableDomain(targetHost);
            if (root != null && attrs.contains(root)) return false;
            try {
                String uni = java.net.IDN.toUnicode(targetHost).toLowerCase(Locale.US);
                if (!uni.equals(targetHost) && attrs.contains(uni)) return false;
                String uroot = registrableDomain(uni);
                if (uroot != null && !uni.equals(targetHost) && attrs.contains(uroot)) return false;
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    /** push v30 R32-3：外站链接被改道判定（全满足才算）——①点/按记录带链接地址且属于当前页；②所点链接是外站
     * （本站链接归 P2'）；③去向既不是所点链接的站、也不是本站；④不是服务器重定向；⑤去向不是 v92 中转（地址里
     * 不带本站域名），也不带所点链接的域名（中转到所点站的不拦）。不看尺寸/时长、不数链接、不加全局状态。 */
    private boolean isForeignClickDetour(String[] mark, String pageUrl, String target, boolean redirect) {
        if (mark == null || mark.length < 2 || redirect) return false;
        String href = mark[0];
        String doc = mark[1];
        if (TextUtils.isEmpty(href) || TextUtils.isEmpty(doc) || TextUtils.isEmpty(pageUrl) || TextUtils.isEmpty(target)) return false;
        if (!clickNoHash(doc).equals(clickNoHash(pageUrl))) return false;
        String docHost = clickHostOf(doc);
        String hrefHost = clickHostOf(href);
        String targetHost = clickHostOf(target);
        if (docHost.isEmpty() || hrefHost.isEmpty() || targetHost.isEmpty()) return false;
        if (sameSite(hrefHost, docHost)) return false;
        if (sameSite(targetHost, hrefHost)) return false;
        if (sameSite(targetHost, docHost)) return false;
        if (isJumpServiceUrl(target, docHost) || isJumpServiceUrl(target, hrefHost)) return false;
        String hrefRoot = registrableDomain(hrefHost);
        if (hrefRoot != null && target.toLowerCase(Locale.US).contains(hrefRoot)) return false;
        // 所点链接本身就带着去向域名（跟踪/统计链 ?url=去向）→ 是链接自己的去处，不拦。
        String targetRoot = registrableDomain(targetHost);
        if (targetRoot != null && href.toLowerCase(Locale.US).contains(targetRoot)) return false;
        return true;
    }

    /** push v29 R31-1：点/按记录里的本站链接（记录属于该页、链接与该页同站）；否则空。 */
    private String sameSiteMarkHref(String[] mark, String pageUrl) {
        if (mark == null || mark.length < 2 || TextUtils.isEmpty(mark[0]) || TextUtils.isEmpty(mark[1]) || TextUtils.isEmpty(pageUrl)) return "";
        if (!clickNoHash(mark[1]).equals(clickNoHash(pageUrl))) return "";
        String dh = clickHostOf(mark[1]);
        String hh = clickHostOf(mark[0]);
        if (dh.isEmpty() || hh.isEmpty() || !sameSite(hh, dh)) return "";
        return mark[0];
    }

    /** push v23 P2'：拦下劫持后还原用户本意——主框打开所点的本站链接；主框已在该页或正去该页则不重复加载。 */
    private void restoreClickTarget(WebView view, String href) {
        if (view == null || TextUtils.isEmpty(href)) return;
        String want = clickNoHash(href);
        if (want.equals(clickNoHash(view.getUrl())) || want.equals(clickNoHash(mClickLastStarted))) return;
        view.post(() -> view.loadUrl(href));
    }

    private boolean isAllowedNewWindowUrl(String url, boolean hasGesture) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme) && !"blob".equals(scheme)) return false;
        String targetHost = uri.getHost();
        String currentHost = null;
        try {
            String cur = mBinding.web.getUrl();
            if (!TextUtils.isEmpty(cur)) currentHost = Uri.parse(cur).getHost();
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(currentHost)) {
            try {
                if (!TextUtils.isEmpty(mAnchorUrl)) currentHost = Uri.parse(mAnchorUrl).getHost();
            } catch (Exception ignored) {
            }
        }
        // /pop 弹窗路径一律拦(与 v107 同)。
        if (isPopunderPath(uri.getPath())) return false;
        // v61:当前页是跳转页 + 带手势 + 目标非广告 → 放行(发布页人工点按到真实站)。
        if (hasGesture && !TextUtils.isEmpty(mJumpHost)
                && !TextUtils.isEmpty(currentHost) && mJumpHost.equalsIgnoreCase(currentHost)
                && (TextUtils.isEmpty(targetHost) || !isAd(targetHost))) {
            // v76：记下这次跳转（起跳 host），真实站落地时清 mJumpHost。
            mJumpUsed = true;
            mJumpOriginHost = currentHost;
            return true;
        }
        // v74:当前页是跳转页 + 无手势 + 目标非广告 → 放行(与 shouldOverrideUrlLoading 同口径；
        // 跳转页 window.open 到最新真实域名时，setTimeout 丢了手势也要放行)。
        if (!hasGesture && !TextUtils.isEmpty(mJumpHost)
                && !TextUtils.isEmpty(currentHost) && mJumpHost.equalsIgnoreCase(currentHost)
                && (TextUtils.isEmpty(targetHost) || !isAd(targetHost))) {
            // v76：记下这次跳转（起跳 host），真实站落地时清 mJumpHost。
            mJumpUsed = true;
            mJumpOriginHost = currentHost;
            return true;
        }
        // 同站放行。
        if (!TextUtils.isEmpty(targetHost) && !TextUtils.isEmpty(currentHost) && sameSite(targetHost, currentHost)) {
            return true;
        }
        // v92:无手势的中转跳转(目标地址里带当前页地址)放行,并记跳转链供 302 跟进。
        if (!hasGesture && isJumpServiceUrl(url, currentHost)) {
            mJumpAnchorHost = currentHost;
            mJumpRelayHost = targetHost;
            mJumpChainUntil = SystemClock.uptimeMillis() + 10000;
            // v76：记下这次跳转（起跳 host），真实站落地时清 mJumpHost。
            mJumpUsed = true;
            mJumpOriginHost = currentHost;
            return true;
        }
        return false;
    }

    // 骚火（v7 net）：广告加载器域名按日轮换 cdnjs.<数字>.xyz（如 cdnjs.086123.xyz），名单列不进；按形态认。
    private static final Pattern CDNJS_ROTATING_AD = Pattern.compile("^cdnjs\\.\\d+\\.xyz$", Pattern.CASE_INSENSITIVE);

    private boolean isAd(String host) {
        if (AdDomains.isAdDomain(host)) return true;
        if (!TextUtils.isEmpty(host) && CDNJS_ROTATING_AD.matcher(host).matches()) return true;
        for (String ad : RuleConfig.get().getAds()) if (Util.containOrMatch(host, ad)) return true;
        return false;
    }

    private boolean isMediaUrl(String path) {
        if (TextUtils.isEmpty(path)) return false;
        String p = path.toLowerCase(Locale.US);
        return p.endsWith(".m3u8") || p.endsWith(".mp4") || p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".mpd") || p.endsWith(".flv") || p.endsWith(".mov") || p.endsWith(".webm");
    }

    /** 规则脚本口径与嗅探一致：Sniffer 按网址匹配的脚本 + 本站 click 脚本置首。 */
    private List<String> getScript(String url) {
        List<String> script = new ArrayList<>(Sniffer.getScript(Uri.parse(url)));
        if (TextUtils.isEmpty(mClick) || script.contains(mClick)) return script;
        script.add(0, mClick);
        return script;
    }

    private void evaluate(List<String> script, int index) {
        if (index >= script.size()) return;
        String js = script.get(index);
        if (TextUtils.isEmpty(js)) evaluate(script, index + 1);
        else mBinding.web.evaluateJavascript(js, value -> evaluate(script, index + 1));
    }

    private void loadPage(String url, Map<String, String> header) {
        if (!UrlUtil.isWebUrl(url)) {
            Notify.show(R.string.web_load_fail);
            return;
        }
        Map<String, String> extra = new HashMap<>();
        if (header != null) {
            for (Map.Entry<String, String> entry : header.entrySet()) {
                if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) mBinding.web.getSettings().setUserAgentString(entry.getValue());
                else if (HttpHeaders.COOKIE.equalsIgnoreCase(entry.getKey())) CookieManager.getInstance().setCookie(url, entry.getValue());
                else extra.put(entry.getKey(), entry.getValue());
            }
        }
        mBinding.web.loadUrl(url, extra);
    }

    /** 浏览器式切集：不走壳子解析，直接让浏览器跳到该集的播放页；跳不过去才报错。 */
    private void playEpisode(int position) {
        if (position < 0 || position >= mIds.size() || mIds.isEmpty()) return;
        if (position == mPosition && mBinding.web.getUrl() != null) return;
        String page = episodePageUrl(position);
        if (page != null) {
            mPosition = position;
            loadPage(page, null);
            updateNav();
        } else {
            Notify.show(R.string.web_load_fail);
        }
    }

    /** 该集的网页（播放页）：先用网页当前实际地址（进页后可能重定向到带集号的播放页），再退回初始锚点；两处都没有这段时返回 null。 */
    private String episodePageUrl(int position) {
        if (position < 0 || position >= mNames.size()) return null;
        // 集 id 本身就是播放页路径时（如 CAT 站 /player/xxx.html、P站 /view_video.php），直接用它——
        // 集号/线路都在 id 里，改段会改错（耐看点播是 {vod}-{线路}-{集}，改第一段会改成别的线路）。
        if (position < mIds.size()) {
            String page = resolveEpisodePage(mIds.get(position));
            if (page != null) return page;
        }
        int ep = episodeNumber(position);
        if (ep <= 0) return null;
        String page = rewriteEpisodeSegment(mBinding.web.getUrl(), ep);
        if (page != null) return page;
        return rewriteEpisodeSegment(mAnchorUrl, ep);
    }

    /** 集 id 解析成播放页：绝对 http(s) 地址直接用；/ 开头的页面路径按当前页/锚点域名拼；其余（令牌、直链流）返回 null 走改段。 */
    private String resolveEpisodePage(String id) {
        if (TextUtils.isEmpty(id)) return null;
        if (id.startsWith("http://") || id.startsWith("https://")) {
            return UrlUtil.isWebUrl(id) && isPagePath(id) ? id : null;
        }
        if (id.startsWith("/") && isPagePath(id)) {
            String base = mBinding.web.getUrl();
            if (TextUtils.isEmpty(base)) base = mAnchorUrl;
            if (TextUtils.isEmpty(base)) return null;
            String url = UrlUtil.resolve(base, id);
            return url != null && UrlUtil.isWebUrl(url) ? url : null;
        }
        return null;
    }

    private boolean isPagePath(String url) {
        String path = url.split("[?#]", 2)[0].toLowerCase(Locale.US);
        return path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php");
    }

    /** 把地址里的 -{线路}-{集}.html 段换成目标集号（第二段才是集号，第一段是线路）；没有这段时返回 null。 */
    private String rewriteEpisodeSegment(String base, int ep) {
        if (TextUtils.isEmpty(base)) return null;
        Matcher m = Pattern.compile("-(\\d+)-(\\d+)\\.html").matcher(base);
        int start = -1, end = -1;
        while (m.find()) {
            start = m.start(2);
            end = m.end(2);
        }
        if (start < 0) return null;
        String url = base.substring(0, start) + ep + base.substring(end);
        return UrlUtil.isWebUrl(url) ? url : null;
    }

    /** 集号：先从集名里取数字（第05集→5），没有取列表位置+1。 */
    private int episodeNumber(int position) {
        if (position >= 0 && position < mNames.size()) {
            String name = mNames.get(position);
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
        return position + 1;
    }

    private void showEpisodes() {
        if (mNames.size() < 2) return;
        ArrayList<Episode> episodes = new ArrayList<>();
        for (int i = 0; i < mNames.size(); i++) {
            Episode episode = Episode.create(mNames.get(i), mIds.get(i));
            episode.setSelected(i == mPosition);
            episodes.add(episode);
        }
        EpisodeGridDialog.create().episodes(episodes).show(this);
    }

    @Override
    public void onItemClick(Episode episode) {
        if (episode == null) return;
        int index = mIds.indexOf(episode.getUrl());
        if (index >= 0) playEpisode(index);
    }

    /** 线路选择：只有多线路才亮；切线路后按同集位换该线路的集列表，重新跑一遍本集的播放请求。 */
    private void showFlags() {
        if (mWebFlags.size() < 2) return;
        CharSequence[] items = new CharSequence[mWebFlags.size()];
        int checked = 0;
        for (int i = 0; i < mWebFlags.size(); i++) {
            items[i] = mWebFlags.get(i).flag;
            if (mWebFlags.get(i).flag.equals(mFlag)) checked = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    switchFlag(which);
                })
                .create();
        dialog.setOnDismissListener(d -> showBars());
        dialog.show();
    }

    private void switchFlag(int index) {
        if (index < 0 || index >= mWebFlags.size()) return;
        WebFlag target = mWebFlags.get(index);
        if (target.flag.equals(mFlag)) return;
        mFlag = target.flag;
        mNames = target.names;
        mIds = target.ids;
        int targetPosition = Math.clamp(mPosition, 0, Math.max(0, mNames.size() - 1));
        mPosition = -1;
        updateNav();
        playEpisode(targetPosition);
    }

    private void updateNav() {
        String episode = mPosition >= 0 && mPosition < mNames.size() ? mNames.get(mPosition) : "";
        mBinding.title.setText(episode.isEmpty() ? mTitle : mTitle + " - " + episode);
        mBinding.flag.setText(mFlag);
        mBinding.flag.setVisibility(mWebFlags.size() > 1 ? View.VISIBLE : View.GONE);
        boolean multi = mIds.size() > 1;
        mBinding.episodes.setVisibility(multi ? View.VISIBLE : View.GONE);
    }

    // ===== push v1：MacCMS 浏览器转原生播放（嗅探 + 多线探针）=====
    // 只认 MacCMS 播放页（vodplay/{vid}-{sid}-{ep}.html）；非 MacCMS 站不亮按钮、不转播。

    /** push v1：当前页是否 MacCMS 播放页（-剧号-线路-集数.html），只有这种页才转播放。 */
    private boolean isMacCmsPlayPage(String url) {
        if (url == null) return false;
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int cut = q >= 0 && h >= 0 ? Math.min(q, h) : Math.max(q, h);
        String path = cut >= 0 ? url.substring(0, cut) : url;
        if (!path.endsWith(".html")) return false;
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        String core = last.substring(0, last.length() - 5);
        String[] parts = core.split("-");
        if (parts.length != 3) return false;
        for (String p : parts) if (p.isEmpty() || !TextUtils.isDigitsOnly(p)) return false;
        return true;
    }

    /** push v1：多线探针——探当前播放页全部线路与选集表，存 mPushProbeLines；够格才亮播放按钮。后台线程进来时先 post 到主线程（evaluateJavascript 必须在主线程调）。
     * push v20：先跑 PROBE_PRELOAD_JS——tab+按钮式线路（gaze）有未加载面板（data-loaded=false）时逐个点开 tab
     * 让站方自己填集，轮询等面板出选集按钮（300ms 一轮、最多 20 轮=6 秒），再跑 PROBE_JS 收表；
     * 预加载已点回原来选中的 tab。MacCMS 式（面板已在 DOM 里）与枫叶（面板无 data-loaded）不触发、不多余点击。 */
    private void probePageInfo() {
        if (mBinding.web == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mBinding.web.post(this::probePageInfo);
            return;
        }
        // push v2：记下本次探针跑在哪一页——回调时页面可能已切走，回调里再记就锚错页。
        mPushProbePageUrl = mPushPageUrl;
        cancelPushProbePreloadPoll();
        mBinding.web.evaluateJavascript(PROBE_PRELOAD_JS, value -> {
            if (mBinding == null || mBinding.web == null) return;
            if (!mPushProbePageUrl.equals(mPushPageUrl)) return;
            if (parsePreloadClicked(value) <= 0) {
                probePageInfoCollect();
                return;
            }
            schedulePushProbePreloadPoll();
        });
    }

    /** push v20：预加载轮询——每 300ms 问一次还有几块已点面板未出集，齐了或 6 秒到顶就收表；页切走即停。 */
    private void schedulePushProbePreloadPoll() {
        if (mBinding == null || mBinding.web == null) return;
        mPushProbePreloadTries = 0;
        mPushProbePreloadPageUrl = mPushPageUrl;
        mPushProbePreloadPoll = () -> {
            if (mBinding == null || mBinding.web == null) return;
            if (!mPushProbePreloadPageUrl.equals(mPushPageUrl)) return;
            mBinding.web.evaluateJavascript(PROBE_PRELOAD_POLL_JS, value -> {
                if (mBinding == null || mBinding.web == null) return;
                if (!mPushProbePreloadPageUrl.equals(mPushPageUrl)) return;
                if (parsePreloadPending(value) <= 0 || ++mPushProbePreloadTries >= PROBE_PRELOAD_MAX_TRIES) {
                    mPushProbePreloadPoll = null;
                    probePageInfoCollect();
                    return;
                }
                if (mPushProbePreloadPoll != null) mBinding.web.postDelayed(mPushProbePreloadPoll, PROBE_PRELOAD_POLL_MS);
            });
        };
        mBinding.web.postDelayed(mPushProbePreloadPoll, PROBE_PRELOAD_POLL_MS);
    }

    /** push v20：撤掉预加载轮询（新探针进场/切页/销毁时）。 */
    private void cancelPushProbePreloadPoll() {
        if (mPushProbePreloadPoll != null && mBinding != null && mBinding.web != null) {
            mBinding.web.removeCallbacks(mPushProbePreloadPoll);
        }
        mPushProbePreloadPoll = null;
    }

    /** push v20：PROBE_PRELOAD_JS 回值里的 clicked（点开的未加载面板数）；解析失败按 0（直接收表，与 v19 一致）。 */
    private int parsePreloadClicked(String value) {
        try {
            JSONObject obj = parseProbeJson(value);
            if (obj == null) return 0;
            return obj.optInt("clicked", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** push v20：PROBE_PRELOAD_POLL_JS 回值（还未出集的已点面板数）；解析失败按 0（直接收表）。 */
    private int parsePreloadPending(String value) {
        if (value == null) return 0;
        try {
            String s = new JSONTokener(value).nextValue().toString().trim();
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** push v20：跑 PROBE_JS 收表（v19 原 probePageInfo 的收表回调，原样移入；预加载走完或无需预加载时调）。 */
    /** push v33 M1：读当前页简介/年份/地区/og:image（只读脚本，按页锚定，迟到的旧页回调丢弃）。 */
    private void collectPushPageMeta() {
        if (mBinding == null || mBinding.web == null) return;
        final String page = mPushPageUrl;
        try {
            mBinding.web.evaluateJavascript(PUSH_META_JS, value -> {
                if (!page.equals(mPushPageUrl) || value == null) return;
                try {
                    Object o = new JSONTokener(value).nextValue();
                    if (!(o instanceof String) || ((String) o).isEmpty()) return;
                    JSONObject m = new JSONObject((String) o);
                    mPushPageContent = m.optString("c");
                    mPushPageYear = m.optString("y");
                    mPushPageArea = m.optString("a");
                    mPushPageOgPic = m.optString("p");
                    mPushMetaPageUrl = page;
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void probePageInfoCollect() {
        if (mBinding == null || mBinding.web == null) return;
        collectPushPageMeta();
        mBinding.web.evaluateJavascript(PROBE_JS, value -> {
            // push v14：迟到的旧页探针回调直接丢弃——不许把旧页的表/主片流写回已切走的新页。
            if (!mPushProbePageUrl.equals(mPushPageUrl)) {
                mPushPlayPending = false;
                return;
            }
            if (value == null) {
                if (mPushPlayPending) {
                    mPushPlayPending = false;
                    continuePushPlay();
                }
                return;
            }
            try {
                String json = new JSONTokener(value).nextValue().toString();
                JSONObject obj = new JSONObject(json);
                String pic = obj.optString("pic");
                storePushStreamFields(obj);
                if (!pic.isEmpty()) mPushPagePic = pic;
                if (!storePushProbeLines(obj, json) && mPushPlayPending) {
                    // 重探回来是空表：旧表是上一页的，不能留给续跑用。
                    mPushProbeLines = "";
                }
                updatePushButton();
            } catch (Throwable ignored) {
            }
            if (mPushPlayPending) {
                mPushPlayPending = false;
                continuePushPlay();
            }
        });
    }

    /** push v17 F2：把一次 PROBE_JS 结果里的线路/子帖表写回 mPushProbeLines（probePageInfo 与点推现跑共用）。
     * lines.length()>0 才存（去重改名后重序列化，否则存原文）；有子帖无分集表时也存；空表不覆盖旧表。返回是否存了表。 */
    private boolean storePushProbeLines(JSONObject obj, String json) {
        try {
            JSONArray lines = obj.optJSONArray("lines");
            JSONArray subEps = obj.optJSONArray("subEps");
            boolean hasSub = subEps != null && subEps.length() > 0;
            if (lines != null && lines.length() > 0) {
                boolean renamed = dedupePushLineNames(lines);
                mPushProbeLines = renamed ? obj.toString() : json;
                DebugLogStore.add(LogCategory.PLAY, "WebPlay", "push 多线探针 线路=" + lines.length(), false);
                String diag = obj.optString("diag");
                if (!diag.isEmpty()) DebugLogStore.add(LogCategory.PLAY, "WebPlay", "push 探针诊断 " + diag, false);
                return true;
            } else if (hasSub) {
                // push v12：无分集表但有子帖表时也存下来，供点推时走子页解析层。
                mPushProbeLines = json;
                DebugLogStore.add(LogCategory.PLAY, "WebPlay", "push 子页探针 子帖=" + subEps.length(), false);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // push v3：探针补跑——选集表可能是 JS 后渲染的，页完那一刻还没进 DOM，探针跑空表按钮就不亮。
    // 页完后 0.5/1.5/3 秒各补跑一次，只在还没探到可用线路时跑，探到就停，最多 3 次；页切走或 Activity 销毁时不跑。
    // push v17 F3：补跑停条件只看表（probeLinesUsable/hasSubEps）——嗅流只决定按钮亮不亮，不决定表补不补跑。
    private void schedulePushProbeRetry() {
        cancelPushProbeRetry();
        if (probeLinesUsable() || hasSubEps()) return;
        if (mBinding == null || mBinding.web == null) return;
        mPushProbeRetryCount = 0;
        mPushProbeRetryPageUrl = mPushPageUrl;
        mPushProbeRetry = () -> {
            if (mBinding == null || mBinding.web == null) return;
            if (!mPushProbeRetryPageUrl.equals(mPushPageUrl)) return;
            if (probeLinesUsable() || hasSubEps()) return;
            probePageInfo();
            mPushProbeRetryCount++;
            if (mPushProbeRetryCount < 3) {
                mBinding.web.postDelayed(mPushProbeRetry, mPushProbeRetryCount == 1 ? 1000 : 1500);
            }
        };
        mBinding.web.postDelayed(mPushProbeRetry, 500);
    }

    private void cancelPushProbeRetry() {
        if (mPushProbeRetry != null && mBinding != null && mBinding.web != null) {
            mBinding.web.removeCallbacks(mPushProbeRetry);
        }
        mPushProbeRetry = null;
    }

    /** push v1：线路名去重——重名续号（极速蓝光→极速蓝光2）。改名返回 true（调用方需重新序列化）。 */
    private static boolean dedupePushLineNames(JSONArray lines) {
        HashMap<String, Integer> seen = new HashMap<>();
        HashSet<String> used = new HashSet<>();
        boolean changed = false;
        try {
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.getJSONObject(i);
                String name = line.optString("name");
                if (name.isEmpty()) continue;
                if (used.add(name)) {
                    seen.put(name, 1);
                    continue;
                }
                int n = seen.containsKey(name) ? seen.get(name) + 1 : 2;
                String candidate = name + n;
                while (!used.add(candidate)) {
                    n++;
                    candidate = name + n;
                }
                seen.put(name, n);
                line.put("name", candidate);
                changed = true;
            }
        } catch (Throwable ignored) {
        }
        return changed;
    }

    /** push v1：mPushProbeLines 是否够格走多线落地（≥2 条线，或单线 ≥2 集）。 */
    private boolean probeLinesUsable() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return false;
            JSONArray lines = new JSONObject(mPushProbeLines).optJSONArray("lines");
            if (lines == null || lines.length() == 0) return false;
            if (lines.length() >= 2) return true;
            JSONArray eps = lines.getJSONObject(0).optJSONArray("episodes");
            return eps != null && eps.length() >= 2;
        } catch (Throwable e) {
            return false;
        }
    }

    /** push v9/v6：探针带回的当前集真集名（curEpName），单片模式的集名首选。 */
    private String probeCurEpName() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return "";
            return new JSONObject(mPushProbeLines).optString("curEpName", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** push v16：探针是否认出通用线路表（genericLines>0，即至少一条线来自 tab+按钮认法）。 */
    private boolean probeGenericLines() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return false;
            return new JSONObject(mPushProbeLines).optInt("genericLines", 0) > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** push v16：探针是否认出线路 tab 或选集按钮（tabsFound/episodesFound>0），即使还没拼成可用线路表。 */
    private boolean probeHasGenericTable() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return false;
            JSONObject probe = new JSONObject(mPushProbeLines);
            return probe.optInt("tabsFound", 0) > 0 || probe.optInt("episodesFound", 0) > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** push v12：探针带回的子帖表（subEps）是否可用（无 .dplayer 的文章页才有）。 */
    private boolean hasSubEps() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return false;
            JSONArray sub = new JSONObject(mPushProbeLines).optJSONArray("subEps");
            return sub != null && sub.length() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** push v12：探针带回的主片流（多视频单集 / 单片声明地址）是否可用。 */
    private boolean hasPushMainStream() {
        return !TextUtils.isEmpty(mPushMainStream);
    }

    /** push v15：探针判定的本页主播放器是否存在（见 PROBE_JS hasMainPlayer）。 */
    private boolean hasMainPlayer() {
        return mPushHasMainPlayer;
    }

    /** push v12：探针带回的子帖表 JSON（供子页解析层用）。 */
    private String probeSubEpsJson() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return "";
            JSONArray sub = new JSONObject(mPushProbeLines).optJSONArray("subEps");
            return sub == null ? "" : sub.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** push v8：120 秒内嗅到过流（单片模式的判定口径，与 continuePushPlay 取流窗口一致）。 */
    private boolean hasFreshPushSniff() {
        // push v14：只认本页 120 秒内嗅到的流——嗅流页不是当前页时一律不算新鲜。
        return !TextUtils.isEmpty(mPushLastSniff) && !TextUtils.isEmpty(mPushSniffPageUrl) && mPushSniffPageUrl.equals(mPushPageUrl) && System.currentTimeMillis() - mPushSniffAt < 120000;
    }

    /**
     * push v8：嗅探放宽——流地址的 .m3u8/.mp4 后缀可能在问号后的参数里
     * （麦田 NB 真流 getM3u8?name=…&url=NBY-xxx.m3u8，路径是 /nby/m3u8/getM3u8）。
     * 口径：① 路径末尾是 .m3u8/.mp4（原口径）；② 参数里带 .m3u8/.mp4 文件名，
     * 后跟串尾、& 或 #（通用，不认域名/站点）。只记录、不拦截；真假由播放前
     * 探活 / 解析兜底（解析失败不放死链，见 v5 报错重取）。
     */
    private static boolean isPushStreamUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String low = url.toLowerCase(Locale.US);
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) return true;
        return hasMediaSuffix(low, ".m3u8") || hasMediaSuffix(low, ".mp4");
    }

    /** 后缀后跟串尾、& 或 # 才算文件名（避免 art.php?url=<令牌> 这类无后缀地址误判）。 */
    private static boolean hasMediaSuffix(String low, String suffix) {
        int i = low.indexOf(suffix);
        while (i >= 0) {
            int after = i + suffix.length();
            if (after >= low.length() || low.charAt(after) == '&' || low.charAt(after) == '#') return true;
            i = low.indexOf(suffix, after);
        }
        return false;
    }

    /** push v1：MacCMS 播放页且探到可用线路表时亮起「播放视频」悬浮钮，否则隐藏。 */
    private void updatePushButton() {
        if (mBinding.pushPlay == null) return;
        // push v8：按钮亮的条件放宽——(a) MacCMS 播放页且探针有多线表（原行为），或
        // (b) 120 秒内嗅到过流（单片模式：hsck/MissAV 类单片，无线路表也推当前一部）。
        // URL 门（isMacCmsPlayPage）不拦 (b)——MissAV 播放页 /cn/mxgs-1453 无 .html、无 3 段数字。
        // push v12：(c) 探针主片流可用（多视频单集/单片声明地址）或 (d) 有子帖表可解析时也亮起——
        // 51吃瓜类文章页不是 MacCMS 播放页、也不会嗅到流，旧条件永远不亮。
        // push v15：嗅流单亮须有本页主播放器——列表页卡片预告/广告窗的嗅流再新鲜也不亮钮。
        // push v16：通用线路表（gaze 类非 MacCMS 播放页）也亮钮；认出 tab/集按钮且有主播放器时也亮钮
        // （单集真标签页在播起来之前还没有嗅流，但表与播放器都已认出）。
        boolean show = (isMacCmsPlayPage(mPushPageUrl) && probeLinesUsable()) || (probeGenericLines() && probeLinesUsable()) || (hasFreshPushSniff() && hasMainPlayer()) || hasPushMainStream() || hasSubEps() || (probeHasGenericTable() && hasMainPlayer());
        mBinding.pushPlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** push v1：悬浮钮点击——多线探针够格时取当前集直达流（页面 video src 优先，120 秒内嗅探次之），转原生播放器；无流则留在网页。 */
    private void playNative() {
        // push v8：单片模式（无线路表但 120 秒内嗅到过流）也放行，推当前一部；单片跳过重探针（无表可探）。
        boolean multi = probeLinesUsable();
        boolean single = !multi && ((hasFreshPushSniff() && hasMainPlayer()) || hasPushMainStream() || (probeHasGenericTable() && hasMainPlayer()));
        boolean sub = !multi && !single && hasSubEps();
        if (!multi && !single && !sub) {
            Notify.show("当前页面未识别到线路/集数");
            return;
        }
        if (sub) {
            resolveSubPagesAndPlay();
            return;
        }
        if (single) {
            continuePushPlay();
            return;
        }
        // push v2：探针是旧页的（用户已切集/切页但重探还没跑），强制重探、探完再续跑。
        if (!mPushProbePageUrl.equals(mPushPageUrl)) {
            mPushPlayPending = true;
            probePageInfo();
            return;
        }
        continuePushPlay();
    }

    /** push v12：子页解析后推——探针只有 subEps（无 lines/mainStream）时，并行取子页拼完整分集，
     * 再按正常分集流程推（首集直达流 + 全部集进原生播放器）。子页全取不到就留在网页，不假装成功。 */
    private void resolveSubPagesAndPlay() {
        String subJson = probeSubEpsJson();
        if (subJson.isEmpty()) {
            Notify.show("当前页面未识别到线路/集数");
            return;
        }
        Notify.show("正在解析子页视频…");
        // push v14：记下解析起点的页——解析回来时页已切走就丢弃，不把旧页子集写进新页。
        final String subPageUrl = mPushPageUrl;
        HttpEpisodeResolver.resolveSubPages(mPushPageUrl, subJson, new HttpEpisodeResolver.SubPagesCallback() {
            @Override
            public void onResolved(String episodesJson) {
                if (mBinding == null || mBinding.web == null || !subPageUrl.equals(mPushPageUrl)) return;
                try {
                    JSONArray eps = new JSONArray(episodesJson);
                    if (eps.length() == 0) {
                        Notify.show("未检测到可提取的视频地址");
                        return;
                    }
                    String rawTitle = mBinding.web.getTitle();
                    if (rawTitle == null) rawTitle = "";
                    String lineName = BrowserMeta.cleanTitle(rawTitle);
                    if (lineName.isEmpty()) lineName = mTitle;
                    if (lineName.isEmpty()) lineName = "视频";
                    JSONArray lineEps = new JSONArray();
                    String firstUrl = "";
                    String firstName = "";
                    for (int i = 0; i < eps.length(); i++) {
                        JSONObject ep = eps.getJSONObject(i);
                        JSONObject item = new JSONObject();
                        item.put("name", ep.optString("name"));
                        item.put("url", ep.optString("url"));
                        item.put("cur", i == 0 ? 1 : 0);
                        lineEps.put(item);
                        if (i == 0) {
                            firstUrl = ep.optString("url");
                            firstName = ep.optString("name");
                        }
                    }
                    JSONObject line = new JSONObject();
                    line.put("sid", "dp");
                    line.put("name", lineName);
                    line.put("cur", 1);
                    line.put("episodes", lineEps);
                    JSONArray lines = new JSONArray();
                    lines.put(line);
                    JSONObject probe = new JSONObject(mPushProbeLines);
                    probe.put("lines", lines);
                    probe.put("curEpName", firstName);
                    probe.put("mainStream", firstUrl);
                    probe.put("subEps", new JSONArray());
                    mPushProbeLines = probe.toString();
                    mPushMainStream = firstUrl;
                    startProbedPlay(firstUrl);
                } catch (Throwable ignored) {
                    Notify.show("未检测到可提取的视频地址");
                }
            }

            @Override
            public void onFailed() {
                Notify.show("未检测到可提取的视频地址");
            }
        });
    }

    /** push v11：把 PROBE_JS 带回的主片流/广告黑名单/片头广告标志存下来（probePageInfo 与点推时现跑共用）。 */
    private void storePushStreamFields(JSONObject obj) {
        if (obj == null) return;
        try {
            String ms = obj.optString("mainStream", "");
            mPushMainStream = ms == null ? "" : ms;
            java.util.Set<String> ads = new java.util.HashSet<>();
            JSONArray arr = obj.optJSONArray("adSrcs");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String u = arr.optString(i);
                    if (u != null && !u.isEmpty()) ads.add(u);
                }
            }
            mPushAdSrcs = java.util.Collections.unmodifiableSet(ads);
            mPushAdPlaying = obj.optInt("adPlaying", 0) == 1;
            mPushHasMainPlayer = obj.optInt("hasMainPlayer", 0) == 1;
        } catch (Throwable ignored) {
        }
    }

    /** push v11：解析 PROBE_JS 的 evaluateJavascript 回值（JSON 字符串外面包一层引号）。 */
    private JSONObject parseProbeJson(String value) {
        if (value == null) return null;
        try {
            String json = new JSONTokener(value).nextValue().toString();
            return new JSONObject(json);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** push v2：探针就绪后的续跑——取流顺位见方法内注释（探针 mainStream 优先），转原生播放器；无流则留在网页。 */
    private void continuePushPlay() {
        // push v8：单片模式（无线路表但 120 秒内嗅到过流）也放行。
        // push v14：探针已带回本页主片流时也放行——playNative 单片模式本就认 hasPushMainStream，
        // 入口门必须一致，否则 v14 按页隔离后、无本页嗅流的单 .dplayer 页会被误拒（jsdom 两推复现实锤）。
        if (!probeLinesUsable() && !(hasFreshPushSniff() && hasMainPlayer()) && !hasPushMainStream() && !(probeHasGenericTable() && hasMainPlayer())) {
            Notify.show("当前页面未识别到线路/集数");
            return;
        }
        if (mBinding == null || mBinding.web == null) return;
        // push v11：点推那一刻现跑 PROBE_JS（主片声明地址 + 广告黑名单 + 片头广告标志现取现用，
        // 不吃页完那次探针的旧值——片头广告是按秒变的），再取 VIDEO_PROBE 的主元素直链。
        // 取流顺位（不用时长）：① 探针 mainStream → ② 主元素 http 直链（非 blob、非广告播放中、
        // 不在黑名单、过 isPushStreamUrl 门）→ ③ 已过滤的 mPushLastSniff（本页 120 秒内，v14 跨页不认）。
        // push v22/v18（hohoj）：探针 mainStream 末尾加 frameStream()——顶层取不到主片时，下钻播放器位置上、无广告标记、
        // 同源可读的 iframe，取其主 video 直链或该帧唯一 .m3u8 作主片（跨域广告小窗读不到，天然排除），压过 ③ 嗅流。
        mBinding.web.evaluateJavascript(PROBE_JS, pvalue -> {
            JSONObject pobj = parseProbeJson(pvalue);
            if (pobj != null) {
                storePushStreamFields(pobj);
                // push v17 F2：点推现跑同时把本次线路表写回 mPushProbeLines——表与流一起现取现用，
                // 晚渲染站的表在点推时仍能赶上。页锚定：只在探针跑的仍是当前页时写，旧页表不写回新页。
                if (mPushProbePageUrl.equals(mPushPageUrl)) storePushProbeLines(pobj, pobj.toString());
            } else {
                mPushMainStream = "";
                mPushAdSrcs = java.util.Collections.emptySet();
                mPushAdPlaying = false;
            }
            mBinding.web.evaluateJavascript(VIDEO_PROBE, value -> {
                String src = value == null ? "" : value.replace("\"", "").trim();
                String stream = "";
                if (!TextUtils.isEmpty(mPushMainStream) && isPushStreamUrl(mPushMainStream)) stream = mPushMainStream;
                else if (!TextUtils.isEmpty(src) && !"null".equals(src) && src.startsWith("http") && isPushStreamUrl(src) && !mPushAdPlaying && !mPushAdSrcs.contains(src)) stream = src;
                else if (hasFreshPushSniff() && !mPushAdSrcs.contains(mPushLastSniff)) stream = mPushLastSniff;
                if (!stream.isEmpty()) {
                    startProbedPlay(stream);
                    return;
                }
                Notify.show("未检测到可提取的视频地址");
            });
        });
    }

    /** push v1：MacCMS 播放页转播放——当前集直达流 + 全部线路表进原生播放器。 */
    /** push v18 (c)：浏览器内点集解流的回交回调（主线程回调）。 */
    public interface BrowserResolveCallback {
        void onStream(String url, Map<String, String> headers);
        void onFailed();
    }

    /** push v18 (c)：浏览器内点集解流的超时——15 秒（report16 建议值）。 */
    private static final long BROWSER_RESOLVE_TIMEOUT_MS = 15000;

    /** push v19 (B)：静音当前页全部 video 元素（页面兜底导航前后用；不点按钮、不返回值）。 */
    private static final String MUTE_ALL_VIDEOS_JS = "(function(){try{var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){try{vs[i].muted=true;vs[i].volume=0;}catch(e){}}return 'muted';}catch(e){return 'error';}})()";

    /**
     * push v18 (c)：请后台栈里的现有浏览器点 data-id 按钮解流。返回 false 表示浏览器已关
     * （无实例/已销毁），调用方直接回落现有 toast；返回 true 表示已接单，结果经回调回交
     * （按钮找不到/15 秒超时走 onFailed）。不新建 WebView，只用用户自己打开的可见浏览器。
     * epId 只放行 [A-Za-z0-9_-]（站方 row.id 形），拼进 JS 选择器前不再转义。
     */
    public static boolean requestBrowserResolve(String epId, BrowserResolveCallback callback) {
        if (callback == null || TextUtils.isEmpty(epId) || !epId.matches("[A-Za-z0-9_\\-]+")) return false;
        WebPlayActivity host = sBrowserInstance == null ? null : sBrowserInstance.get();
        if (host == null || host.isFinishing() || host.isDestroyed() || host.mBinding == null || host.mBinding.web == null) return false;
        host.runOnUiThread(() -> host.startBrowserResolve(epId, callback));
        return true;
    }

    /**
     * push v19 (B)：解析失败兜底——请后台栈里的现有浏览器导航到指定播放页解流。
     * 复用 (c) 的接单/超时/嗅流回交机器，区别只有一步：不点按钮，改为导航到 pageUrl。
     * 返回 false 表示浏览器已关（无实例/已销毁），调用方直接回落现有 toast；返回 true
     * 表示已接单，结果经回调回交（15 秒超时走 onFailed）。不新建 WebView，只用用户自己打开的可见浏览器。
     * pageUrl 只放行 http:// / https:// 开头（不套 (c) 的 epId 正则，那是给 data-id 的）。
     */
    public static boolean requestBrowserPageResolve(String pageUrl, BrowserResolveCallback callback) {
        if (callback == null || TextUtils.isEmpty(pageUrl) || (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://"))) return false;
        WebPlayActivity host = sBrowserInstance == null ? null : sBrowserInstance.get();
        if (host == null || host.isFinishing() || host.isDestroyed() || host.mBinding == null || host.mBinding.web == null) return false;
        host.runOnUiThread(() -> host.startBrowserPageResolve(pageUrl, callback));
        return true;
    }

    /** push v18 (c)：取消一单待解请求（播放器销毁时调用；identity 比对，只取消自己那单，不回调）。 */
    public static void cancelBrowserResolve(BrowserResolveCallback callback) {
        if (callback == null) return;
        WebPlayActivity host = sBrowserInstance == null ? null : sBrowserInstance.get();
        if (host == null) return;
        host.runOnUiThread(() -> {
            if (host.mBrowserResolveCallback == callback) host.clearBrowserResolveState();
        });
    }

    /** push v18 (c)：在后台浏览器里点 data-id 按钮解流（主线程）。只点当前面板已渲染的按钮；跨线未加载面板的集点不到、直接失败（原型口径）。 */
    private void startBrowserResolve(String epId, BrowserResolveCallback callback) {
        // 上一单未完先静默取消（只清状态、不回调旧单——旧单的播放器已切走别的集，Layer 1 守卫本就会丢弃它）。
        clearBrowserResolveState();
        mBrowserResolveCallback = callback;
        mBrowserResolveKnownStream = mPushLastSniff == null ? "" : mPushLastSniff;
        mBrowserResolveAt = System.currentTimeMillis();
        // 后台 WebView 处于 onPause 节流态：点按后的站方 fetch/WASM/媒体加载未必跑得起来，
        // 先恢复计时器，解完（或超时/失败）再按活动状态恢复暂停；不新建 WebView。
        try {
            mBinding.web.onResume();
            mBrowserResolveWebResumed = true;
        } catch (Throwable ignored) {
        }
        // 先静音全部 video 元素再点——点按会切掉浏览器自己的当前播放，静音避免后台出声。
        String js = "(function(){try{var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){try{vs[i].muted=true;vs[i].volume=0;}catch(e){}}var b=document.querySelector('button[data-id=\""
                + epId + "\"]');if(!b)return 'not-found';b.click();return 'clicked';}catch(e){return 'error';}})()";
        mBrowserResolveTimeout = () -> {
            BrowserResolveCallback cb = mBrowserResolveCallback;
            clearBrowserResolveState();
            if (cb != null) cb.onFailed();
        };
        mBrowserResolveHandler.postDelayed(mBrowserResolveTimeout, BROWSER_RESOLVE_TIMEOUT_MS);
        try {
            mBinding.web.evaluateJavascript(js, value -> {
                if (mBrowserResolveCallback != callback) return;
                String r = value == null ? "" : value.replace("\"", "");
                if ("not-found".equals(r) || "error".equals(r)) {
                    BrowserResolveCallback cb = mBrowserResolveCallback;
                    clearBrowserResolveState();
                    if (cb != null) cb.onFailed();
                }
                // 'clicked'：等嗅探抓流（15 秒超时兜底）。
            });
        } catch (Throwable e) {
            BrowserResolveCallback cb = mBrowserResolveCallback;
            clearBrowserResolveState();
            if (cb != null) cb.onFailed();
        }
    }

    /** push v19 (B)：在后台浏览器里导航到指定播放页解流（主线程）。复用 (c) 的接单状态
     *（已知流/起算时刻/超时/嗅流回交），区别：不点按钮，先静音当前页全部 video 再 loadUrl；
     * 导航后新页自动播放也要静音——onPageFinished 里待解单未完时再静音一次，另有 2 秒延迟
     * 补静音（video 元素由站方 JS 后建时兜底）。导航会切掉浏览器自己的当前播放，与 (c) 同款副作用。
     * maybeDeliverBrowserResolve 不用改：它只有时间守卫（mPushSniffAt < mBrowserResolveAt 拦旧流），
     * 没有页面守卫；导航开始时 onPageStarted 把 mPushSniffAt 清零，新页嗅到的流时间戳在起算时刻之后，正常回交。 */
    private void startBrowserPageResolve(String pageUrl, BrowserResolveCallback callback) {
        clearBrowserResolveState();
        mBrowserResolveCallback = callback;
        mBrowserResolvePageMode = true;
        mBrowserResolveKnownStream = mPushLastSniff == null ? "" : mPushLastSniff;
        mBrowserResolveAt = System.currentTimeMillis();
        // 后台 WebView 处于 onPause 节流态：导航后站方 fetch/JS/媒体加载未必跑得起来，
        // 先恢复计时器，解完（或超时/失败）再按活动状态恢复暂停；不新建 WebView。
        try {
            mBinding.web.onResume();
            mBrowserResolveWebResumed = true;
        } catch (Throwable ignored) {
        }
        // 先静音当前页全部 video 再导航——导航会切掉浏览器自己的当前播放，静音避免后台出声。
        try {
            mBinding.web.evaluateJavascript(MUTE_ALL_VIDEOS_JS, null);
        } catch (Throwable ignored) {
        }
        mBrowserResolveTimeout = () -> {
            BrowserResolveCallback cb = mBrowserResolveCallback;
            clearBrowserResolveState();
            if (cb != null) cb.onFailed();
        };
        mBrowserResolveHandler.postDelayed(mBrowserResolveTimeout, BROWSER_RESOLVE_TIMEOUT_MS);
        // 导航后补静音：站方播放器由 JS 后建 video 元素时，onPageFinished 那次未必赶得上，
        // 2 秒后再评一次（仍在待解才执行；clearBrowserResolveState 会撤掉它）。
        mBrowserResolveMute = () -> {
            if (mBrowserResolveCallback != callback) return;
            try {
                if (mBinding != null && mBinding.web != null) mBinding.web.evaluateJavascript(MUTE_ALL_VIDEOS_JS, null);
            } catch (Throwable ignored) {
            }
        };
        mBrowserResolveHandler.postDelayed(mBrowserResolveMute, 2000);
        try {
            mBinding.web.loadUrl(pageUrl);
        } catch (Throwable e) {
            BrowserResolveCallback cb = mBrowserResolveCallback;
            clearBrowserResolveState();
            if (cb != null) cb.onFailed();
        }
    }

    /** push v18 (c)：只清待解单状态，不回调（调用方在清状态前后自行决定是否回调）。 */
    private void clearBrowserResolveState() {
        if (mBrowserResolveTimeout != null) {
            mBrowserResolveHandler.removeCallbacks(mBrowserResolveTimeout);
            mBrowserResolveTimeout = null;
        }
        // push v19 (B)：撤掉页面兜底的延迟补静音（(c) 路从不设它，这里恒为 null，不影响 (c)）。
        if (mBrowserResolveMute != null) {
            mBrowserResolveHandler.removeCallbacks(mBrowserResolveMute);
            mBrowserResolveMute = null;
        }
        // push v25 (B)：撤掉页面兜底的嗅流等待，复位页面模式（(c) 路从不设，恒为初值）。
        if (mBrowserResolveForce != null) {
            mBrowserResolveHandler.removeCallbacks(mBrowserResolveForce);
            mBrowserResolveForce = null;
        }
        mBrowserResolvePageMode = false;
        mBrowserResolvePageStarted = false;
        mBrowserResolvePageDone = false;
        mBrowserResolveSniff = "";
        mBrowserResolveCallback = null;
        mBrowserResolveKnownStream = "";
        if (mBrowserResolveWebResumed) {
            mBrowserResolveWebResumed = false;
            // 只有活动仍在后台时才恢复暂停；用户已回到浏览器时不动（onResume 已恢复计时器）。
            if (!mActivityResumed) {
                try {
                    if (mBinding != null && mBinding.web != null) mBinding.web.onPause();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * push v18 (c)：嗅到新流时，若有待解单且该流不是点按前已知的旧流，回交给等待中的播放器。
     * 后台线程调用（shouldInterceptRequest 嗅探分支），回调贴回主线程；广告黑名单流在嗅探
     * 分支入口已滤掉，不会进这里。片头广告流若不在黑名单里仍可能被当作正片交回——原型已知风险，待真机验证。
     */
    private void maybeDeliverBrowserResolve(String url) {
        BrowserResolveCallback cb = mBrowserResolveCallback;
        if (cb == null) return;
        if (TextUtils.isEmpty(url) || url.equals(mBrowserResolveKnownStream)) return;
        if (mPushSniffAt < mBrowserResolveAt) return;
        Map<String, String> headers = pushPlayHeaders();
        runOnUiThread(() -> {
            if (mBrowserResolveCallback != cb) return;
            // push v25 (B)：页面兜底模式不再「导航后第一条新嗅流立刻交回」——同站广告帧（missav2 /herebyad4）
            // 的 mp4 会抢先被交出去。改为记下候选嗅流，交给 tryDeliverPageResolve 按「探针主片 > 嗅流」定。
            // (c) 点按钮模式照旧立刻交回（点集瞬间探针可能还读到旧集主片，会播错集）。
            if (mBrowserResolvePageMode) {
                if (!mBrowserResolvePageStarted) return;
                mBrowserResolveSniff = url;
                tryDeliverPageResolve(false);
                if (!mBrowserResolvePageDone && mBrowserResolveForce == null) {
                    mBrowserResolveForce = () -> {
                        mBrowserResolveForce = null;
                        if (mBrowserResolveCallback != cb) return;
                        tryDeliverPageResolve(true);
                    };
                    mBrowserResolveHandler.postDelayed(mBrowserResolveForce, BROWSER_RESOLVE_SNIFF_WAIT_MS);
                }
                return;
            }
            clearBrowserResolveState();
            cb.onStream(url, headers);
        });
    }

    /**
     * push v25 (B)：页面兜底模式的回交判定（主线程）。现跑 PROBE_JS：探针 mainStream（主播放器声明地址，
     * 按结构认，不用时长）可用且不在广告黑名单 → 交主片；否则在目标页已落地或嗅流等待到点（force）时，
     * 交候选嗅流（仍排除探针广告黑名单）；都没有就继续等（15 秒超时照旧走 onFailed）。
     */
    private void tryDeliverPageResolve(boolean force) {
        BrowserResolveCallback cb = mBrowserResolveCallback;
        if (cb == null || !mBrowserResolvePageMode || mBinding == null || mBinding.web == null) return;
        try {
            mBinding.web.evaluateJavascript(PROBE_JS, value -> {
                if (mBrowserResolveCallback != cb) return;
                JSONObject pobj = parseProbeJson(value);
                if (pobj != null) storePushStreamFields(pobj);
                String main = mPushMainStream;
                if (!TextUtils.isEmpty(main) && isPushStreamUrl(main) && !mPushAdSrcs.contains(main)) {
                    Map<String, String> h = pushPlayHeaders();
                    clearBrowserResolveState();
                    cb.onStream(main, h);
                    return;
                }
                String sniff = mBrowserResolveSniff;
                if (!TextUtils.isEmpty(sniff) && !mPushAdSrcs.contains(sniff) && (mBrowserResolvePageDone || force)) {
                    Map<String, String> h = pushPlayHeaders();
                    clearBrowserResolveState();
                    cb.onStream(sniff, h);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void startProbedPlay(String stream) {
        String rawTitle = mBinding.web.getTitle();
        if (rawTitle == null) rawTitle = "";
        String title = BrowserMeta.cleanTitle(rawTitle);
        if (title.isEmpty()) title = mTitle;
        String flag = "";
        try {
            JSONArray lines = new JSONObject(mPushProbeLines).optJSONArray("lines");
            if (lines != null && lines.length() > 0) {
                flag = lines.getJSONObject(0).optString("name");
                for (int i = 0; i < lines.length(); i++) {
                    if (lines.getJSONObject(i).optInt("cur", 0) == 1) {
                        flag = lines.getJSONObject(i).optString("name");
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (flag.isEmpty()) flag = BrowserMeta.siteName(rawTitle, mTitle);
        String episode = BrowserMeta.episodeName(mPushPageUrl, rawTitle);
        // push v9/v6：单片模式（无线路表）时集名不用网址序号——探针带回的真集名
        // （curEpName）优先，标题里的「第X集」次之，都没有显示「正片」，不显示「第0集」/「第1集」。
        // push v16（report11 §5）：单片模式不再用标题里的「第X集」——标题是站方模板文案，
        // 不是探针认出的当前集；探针带不回真标签时显示「正片」，不再显示假「第X集」。
        if (!probeLinesUsable()) {
            String probeEp = probeCurEpName();
            if (!probeEp.isEmpty()) episode = probeEp;
            else episode = "正片";
        }
        Map<String, String> headers = pushPlayHeaders();
        // push v2：探针带的 origin/vid/sid/ep（当前线路 sid + 当前集号），供 HttpEpisodeResolver 回落用；缺失时从页 URL 解析。
        String origin = "";
        String vid = "";
        String sid = "";
        String nid = "";
        try {
            JSONObject probe = new JSONObject(mPushProbeLines);
            origin = probe.optString("origin");
            vid = probe.optString("vid");
            sid = probe.optString("sid");
            int ep = probe.optInt("ep", -1);
            if (ep >= 0) nid = String.valueOf(ep);
        } catch (Throwable ignored) {
        }
        if (origin.isEmpty() || vid.isEmpty() || sid.isEmpty() || nid.isEmpty()) {
            try {
                Matcher pm = Pattern.compile("(\\d+)-(\\d+)-(\\d+)\\.html").matcher(mPushPageUrl);
                if (pm.find()) {
                    if (vid.isEmpty()) vid = pm.group(1);
                    if (sid.isEmpty()) sid = pm.group(2);
                    if (nid.isEmpty()) nid = pm.group(3);
                }
            } catch (Throwable ignored) {
            }
            if (origin.isEmpty() && !mPushPageUrl.isEmpty()) {
                try {
                    Uri pageUri = Uri.parse(mPushPageUrl);
                    if (pageUri.getScheme() != null && pageUri.getHost() != null) origin = pageUri.getScheme() + "://" + pageUri.getHost();
                } catch (Throwable ignored) {
                }
            }
        }
        // push v33 M1：简介/年份/地区随推送带过去（本页读到的才带）；简介去掉片名后不足 10 字（只是标题+站名）就不带。
        String metaContent = "";
        String metaYear = "";
        String metaArea = "";
        String pushPic = mPushPagePic;
        if (mPushMetaPageUrl.equals(mPushPageUrl)) {
            metaContent = mPushPageContent;
            String rest = metaContent;
            if (!TextUtils.isEmpty(title)) rest = rest.replace(title, "");
            if (rest.replaceAll("[\\s\\p{Punct}，。、：；！？…\\-|_]+", "").length() < 10) metaContent = "";
            metaYear = mPushPageYear;
            metaArea = mPushPageArea;
            if (TextUtils.isEmpty(pushPic)) pushPic = mPushPageOgPic;
        }
        VideoActivity.startWithHeaders(this, stream, headers, title, flag, episode, null, mPushPageUrl, mPushProbeLines, pushPic, origin, vid, sid, nid, metaContent, metaYear, metaArea);
    }

    /**
     * push v15：转播放用的请求头——嗅到的 Referer 必须与当前页同 host 才用；
     * 预告/广告窗跨域 iframe 的 Referer 是广告域，带上会被 MissAV 媒体 CDN 以 403 拒绝
     * （空 Referer 放行）。不同 host 就丢弃整份嗅头、现场重建（Referer=当前页、
     * User-Agent=Setting.getUa()，不带嗅到的 Cookie）。
     */
    private Map<String, String> pushPlayHeaders() {
        Map<String, String> sniffed = mPushSniffedHeaders;
        if (sniffed != null) {
            String referer = pushHeaderValue(sniffed, "Referer");
            if (TextUtils.isEmpty(referer) || pushSameHost(referer, mPushPageUrl)) return sniffed;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (!mPushPageUrl.isEmpty()) headers.put("Referer", mPushPageUrl);
        String ua = Setting.getUa();
        if (!TextUtils.isEmpty(ua)) headers.put("User-Agent", ua);
        return headers;
    }

    private static String pushHeaderValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    /** 两地址 host 相同才 true；任一 host 取不到按不同处理（嗅头丢弃、现场重建）。 */
    private static boolean pushSameHost(String a, String b) {
        String ha = null;
        String hb = null;
        try {
            ha = Uri.parse(a).getHost();
        } catch (Exception ignored) {
        }
        try {
            hb = Uri.parse(b).getHost();
        } catch (Exception ignored) {
        }
        return ha != null && hb != null && ha.equalsIgnoreCase(hb);
    }

    /** push v1：嗅探时把该请求的 Referer/UA + 页面 Cookie 记下来，供转播放时带头。后台线程调用，只读请求对象与 Cookie，不碰 WebView 方法。 */
    private void capturePushSniffHeaders(WebResourceRequest request, String url) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> reqHeaders = request.getRequestHeaders();
        if (reqHeaders != null) {
            for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                if (entry.getKey().equalsIgnoreCase("Referer") || entry.getKey().equalsIgnoreCase("User-Agent"))
                    headers.put(entry.getKey(), entry.getValue());
            }
        }
        if (!hasPushHeader(headers, "Referer") && !mPushPageUrl.isEmpty()) headers.put("Referer", mPushPageUrl);
        if (!hasPushHeader(headers, "User-Agent")) {
            String ua = Setting.getUa();
            if (!TextUtils.isEmpty(ua)) headers.put("User-Agent", ua);
        }
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        } catch (Throwable ignored) {
        }
        mPushSniffedHeaders = headers;
    }

    private static boolean hasPushHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) if (key.equalsIgnoreCase(name)) return true;
        return false;
    }

    @Override
    protected void onBackInvoked() {
        if (mCustomView != null) mChromeClient.onHideCustomView();
        else if (!mBarsVisible) showBars();
        else if (mBinding.web.canGoBack()) mBinding.web.goBack();
        else finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mActivityResumed = true;
        mBinding.web.onResume();
    }

    @Override
    protected void onPause() {
        mActivityResumed = false;
        mBinding.web.onPause();
        // v56：切后台/切站前把 Cookie 落盘，验证状态不因进程回收而丢。
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // push v18 (c)：浏览器销毁时待解单直接失败（播放器等不到流时回落现有 toast，不无限等）。
        BrowserResolveCallback pendingResolve = mBrowserResolveCallback;
        clearBrowserResolveState();
        if (sBrowserInstance != null && sBrowserInstance.get() == this) sBrowserInstance = null;
        if (pendingResolve != null) pendingResolve.onFailed();
        cancelPushProbeRetry();
        cancelPushProbePreloadPoll();
        if (mBinding != null) {
            if (mBlankRecheck != null) mBinding.web.removeCallbacks(mBlankRecheck);
            mBinding.web.loadUrl("about:blank");
            mBinding.web.destroy();
        }
        super.onDestroy();
    }
}

