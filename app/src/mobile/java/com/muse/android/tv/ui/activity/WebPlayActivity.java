package com.muse.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
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
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String[] CLEAN_JS_PARTS = {
        "(function(){if(window.__museWebClean)return;window.__museWebClean=1;try{var __museHost=(location.hostname||'')",
        ".toLowerCase();if(__museHost.indexOf('qiyou')>=0){if(window.WebSocket){var __museWS=window.WebSocket;window.We",
        "bSocket=function(u,p){var s=String(u==null?'':u);var xh='';try{xh=new URL(s,location.href).hostname;}catch(e){",
        "}if(xh&&xh.toLowerCase()!==__museHost){window.__museAdWs=(window.__museAdWs||0)+1;var d=null;try{d=p===undefin",
        "ed?new __museWS('wss://127.0.0.1:9/'):new __museWS('wss://127.0.0.1:9/',p);}catch(e){d=null;}if(d){try{d.close",
        "();}catch(e){}}if(!d){d={readyState:3,close:function(){},send:function(){throw new Error('muse: ws blocked');}",
        ",addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return false;}};}retu",
        "rn d;}return p===undefined?new __museWS(s):new __museWS(s,p);};window.WebSocket.prototype=__museWS.prototype;w",
        "indow.WebSocket.CONNECTING=__museWS.CONNECTING;window.WebSocket.OPEN=__museWS.OPEN;window.WebSocket.CLOSING=__",
        "museWS.CLOSING;window.WebSocket.CLOSED=__museWS.CLOSED;}if(window.history&&window.history.pushState){var __mus",
        "ePS=window.history.pushState.bind(window.history);window.history.pushState=function(s,t,u){if(arguments.length",
        ">=3){var uu=u===undefined?'':String(u);if(uu===''||uu==='#'){window.__museAdBack=(window.__museAdBack||0)+1;re",
        "turn;}}if(arguments.length>=3)return __musePS(s,t,u);return __musePS(s,t);};}}}catch(e){}try{var __museSt=docu",
        "ment.createElement('style');__museSt.textContent='.ad-item,.ec-ad,.ads_w,.ads_close_btn,.adspop,.gkktXd,.ec-ad",
        "-fixed,.ads_w.slide-wap,.ec-ad.ads_w.slide-wap,#AdBottomLeader,#AdBottomRight,#AdPopUp,#AdRectangleBanner,#AdS",
        "lot_megabanner,#Adbanner,#AdsBannerTop,#AdsBottomContainer,#Ads_google_bottom_wide,#AdsenseBottom,#Banner728x9",
        "0,#ad--article-bottom-wrapper,#ad-Superbanner,#ad-banner-1,#ad-banner-atf,#ad-banner-bottom,#ad-banner-btf,#ad",
        "-banner-desktop,#ad-banner-image,#ad-banner-placement,#ad-banner-top,#ad-banner-wrap,#ad-banner_atf-label,#ad-",
        "billboard-bottom,#ad-block-bottom,#ad-bottom,#ad-bottom-banner,#ad-bottom-fixed,#ad-bottom-right-container,#ad",
        "-bottom-wrapper,#ad-box-bottom,#ad-container-banner,#ad-container-overlay,#ad-desktop-bottom,#ad-fixed-bottom,",
        "#ad-fullbanner-btf,#ad-fullbanner-outer,#ad-large-banner-top,#ad-leaderboard-bottom,#ad-leaderboard_bottom,#ad",
        "-main-bottom,#ad-overlay,#ad-performanceFullbanner1,#ad-popup,#ad-popup-home,#ad-popup-int,#ad-small-banner,#a",
        "d-spot-bottom,#ad-sticky-footer-container,#ad-top-banner,#ad-unit-right-bottom-160-600,#ad-unit-top-banner,#ad",
        "Banner1,#adBanner336x280,#adBannerBottom,#adBannerHeader,#adBannerSpacer,#adBannerTable,#adBannerTop,#adBlockB",
        "anner,#adBlockOverlay,#adBottom,#adModal,#adMpuBottom,#adPopup,#adSlot-leaderBottom,#adSpaceBottom,#adSuperban",
        "ner,#ad_Banner,#ad_Bottom,#ad_banner,#ad_banner_1,#ad_banner_468x60,#ad_banner_728x90,#ad_banner_bot,#ad_banne",
        "r_top,#ad_banners,#ad_bottom,#ad_cont_superbanner,#ad_div_bottom,#ad_from_bottom,#ad_fullbanner,#ad_middle_bot",
        "tom,#ad_overlay,#ad_overlayer,#ad_popup_background,#ad_popup_wrapper,#ad_rect_bottom,#ad_slot_bottom,#ad_stick",
        "y_wrap,#ad_superbanner,#ad_topBanner,#ad_top_banner,#ad_topbanner,#adb_bottom,#adbanner-container,#adbanner1,#",
        "adbannerbox,#adbannerdiv,#adbannerleft,#adbannerright,#adbannerwidget,#adblockbottom,#adbottom,#adbottomleft,#",
        "adbottomright,#adbox--page_bottom_ad,#adbox-topbanner,#adfloat,#admpubottom,#admpubottom2,#adpositionbottom,#a",
        "drightbottom,#ads-banner,#ads-banner-top,#ads-bottom,#ads-right-bottom,#ads-superBanner,#ads728bottom,#adsBott",
        "om,#adsMobileFixed,#adsTopMobileFixed,#ads_banner,#ads_banner1,#ads_banner_header,#ads_bottom,#ads_box_bottom,",
        "#ads_center_banner,#ads_in_modal,#ads_layout_bottom,#ads_leaderbottom,#ads_overlay,#ads_popup,#ads_superbanner",
        "1,#ads_superbanner2,#ads_top_banner,#ads_topbanner,#adsbottom,#adsense-bottom,#adsense-container-bottom,#adsen",
        "se_banner_top,#adsense_bottom_ad,#adsense_overlay,#adsense_r_side_sticky_container,#adslot-sticky-headerbar,#a",
        "dspace-bottom,#adspot-bottom,#adunit-article-bottom,#adv-Bottom,#adv-banner,#adv-banner-r,#adv-sticky-1,#adv-s",
        "ticky-2,#advBoxBottom,#adv_BoxBottom,#adv_overlay,#adv_overlay_content,#advert-banner,#advert-banner-container",
        ",#advert-banner-wrap,#advert-banner2,#advert-top-banner,#advertBanner,#advertOverlay,#advert_banner,#advertise",
        "Banner,#advertisement-bottom,#advertisement_banner,#advertisements_bottom,#advertising-banner,#advertising__ba",
        "nner__content,#advt_bottom,#adzbanner,#adzonebanner,#after_bottom_ad,#article-bottom-ad,#articleBottom-ads,#ar",
        "ticle_ad_bottom,#article_banner_ad,#banner-300x250,#banner-468x60,#banner-728,#banner-728x90,#banner-ad,#banne",
        "r-ad-container,#banner-ad-large,#banner-ads,#banner-advert,#banner-lg-ad,#banner-native-ad,#banner-skyscraper,",
        "#banner300x250,#banner468,#banner468x60,#banner728,#banner728x90,#banner_ad_bottom,#banner_ad_footer,#banner_a",
        "d_module,#banner_ad_placeholder,#banner_ad_top,#banner_ads,#banner_adsense,#banner_adv,#banner_advertisement,#",
        "banner_adverts,#banner_content_ad,#banner_sedo,#banner_slot,#banner_spacer,#banner_topad,#banner_videoad,#bann",
        "er_wrapper_top,#block-adsense-banner-article-bottom,#block-adsense-banner-channel-bottom,#bottom-ad,#bottom-ad",
        "-1,#bottom-ad-area,#bottom-ad-banner,#bottom-ad-container,#bottom-ad-leaderboard,#bottom-ad-slot,#bottom-ad-tr",
        "ay,#bottom-ad-wrapper,#bottom-ads,#bottom-ads-bar,#bottom-ads-container,#bottom-not-ads,#bottom-side-ad,#botto",
        "m-sponsor-add,#bottom_ad,#bottom_ad_728,#bottom_ad_area,#bottom_ad_box,#bottom_ad_region,#bottom_ad_unit,#bott",
        "om_ad_wrapper,#bottom_ads,#bottom_banner_ad,#bottom_fixed_ad_overlay,#bottom_leader_ad,#bottom_sponsor_ads,#bo",
        "ttom_sponsored_links,#bottom_text_ad,#content_bottom_ad,#content_bottom_ads,#desktop_middle_ad_fixed,#desktop_",
        "top_ad_fixed,#dfp-ad-bottom-wrapper,#dfp-ad-floating,#dfp-banner,#dfp-banner-popup,#dfp-top-banner,#div-ad-bot",
        "tom,#div-ad-top_banner,#div_ad_float,#dnn_ad_banner,#dsk-banner-ad-a,#dsk-banner-ad-b,#dsk-banner-ad-c,#dsk-ba",
        "nner-ad-d,#em_ad_superbanner,#fixed-ad,#floating-ad-wrapper,#floating-ads,#floating-advert,#floating_ad,#float",
        "ing_ad_container,#floating_ads_bottom_textcss_container,#footer-banner-ad,#full_banner_ad,#global-banner-ad,#g",
        "oogle-ads-bottom,#google-ads-bottom-container,#gpt-ad-banner,#gpt-ad-side-bottom,#header-ad-bottom,#header-ban",
        "ner-728-90,#header-banner-ad,#header-banner-ad-wrapper,#header_ad_banner,#header_bottom_ad,#home-banner-ad,#ho",
        "me_bottom_ad,#horizontal-banner-ad,#index-bottom-advert,#large-bottom-leaderboard-ad,#leaderboard-bottom-ad,#l",
        "eaderboard_bottom_ad,#left-bottom-ad,#leftBanner-ad,#left_float_ad,#lg-banner-ad,#long-bottom-ad-wrapper,#lowe",
        "r-ad-banner,#m-banner-bannerAd,#medrec_bottom_ad,#medrectangle_banner,#modal-ad,#mpu_banner,#overlay-ad-bg,#ov",
        "erlay_ad,#popup_ad_wrapper,#post-bottom-ads,#rail-bottom-ad,#redirect-ad-modal,#right_bottom_ad,#section-ad-bo",
        "ttom,#show-sticky-ad,#side-banner-ad,#site_bottom_ad_div,#sm-banner-ad,#sp-adv-banner-top,#sponsor_bottom,#spo",
        "nsored_link_bottom,#sticky-ad,#sticky-ad-bottom,#sticky-ad-container,#sticky-ad-header,#sticky-ads,#sticky-ads",
        "-top,#sticky-custom-ads,#sticky-footer-ad,#sticky-footer-ads,#sticky-left-ad,#sticky-rail-ad,#sticky_sidebar_a",
        "ds,#story-bottom-ad,#top-ad-banner,#top-banner-ad,#top-banner-ad-browser,#topBanner-ad,#top_ad_banner,#top_ban",
        "ner_ads,#topbanner_ad,#topbanner_sponsor,#video_overlay_ad,#zeus_top-banner,.AdBottomPage,.Ads-sticky,.AdsBott",
        "om,.AdsBoxBottom,.AdzerkBanner,.Banner300x250,.Banner468X60,._bottom_ad_wrapper,.ac-banner-ad,.ad--banner,.ad-",
        "-banner2,.ad--bottom,.ad--bottom-label,.ad--bottommpu,.ad--inArticleBanner,.ad--overlayer,.ad--sticky,.ad-728-",
        "banner,.ad-SuperBanner,.ad-adlink-bottom,.ad-banner-2,.ad-banner-250x600,.ad-banner-300,.ad-banner-300x250,.ad",
        "-banner-5,.ad-banner-6,.ad-banner-728x90,.ad-banner-bottom-container,.ad-banner-box,.ad-banner-btf,.ad-banner-",
        "container,.ad-banner-content,.ad-banner-full-wrapper,.ad-banner-header,.ad-banner-image,.ad-banner-inlisting,.",
        "ad-banner-leaderboard,.ad-banner-placeholder,.ad-banner-single,.ad-banner-smaller,.ad-banner-static,.ad-banner",
        "-top,.ad-banner-top-wrapper,.ad-banner-wrapper,.ad-banners,.ad-bigbanner,.ad-bigbox-fixed,.ad-block-banner-con",
        "tainer,.ad-block-bottom,.ad-block-sticky-ad,.ad-bottom,.ad-bottom-container,.ad-bottom-right-container,.ad-bot",
        "tom728x90,.ad-bottomLeft,.ad-bottomleader,.ad-bottomline,.ad-boxbottom,.ad-boxsticky,.ad-component-fullbanner2",
        ",.ad-container-banner-top,.ad-container-bottom,.ad-container__sticky-wrapper,.ad-fixed,.ad-float,.ad-floating,",
        ".ad-fullbanner,.ad-fullbanner-btf-container,.ad-fullbannernohieght,.ad-home-bottom,.ad-leader-bottom,.ad-m-ban",
        "ner,.ad-mobile--sticky,.ad-mobile-banner,.ad-mpu-bottom,.ad-mpufixed,.ad-overlay,.ad-popup,.ad-popup-content,.",
        "ad-rectangle-banner,.ad-relatedbottom,.ad-single-bottom,.ad-slot--mpu-banner-ad,.ad-slot--top-banner-ad,.ad-sl",
        "ot-banner,.ad-space-topbanner,.ad-sticky,.ad-sticky-banner,.ad-sticky-bottom,.ad-sticky-container,.ad-sticky-s",
        "lot,.ad-sticky-wrapper,.ad-stickyhero,.ad-stickyhero--standard,.ad-stickyhero-enable-mobile,.ad-superbanner,.a",
        "d-superbanner-node,.ad-top-banner,.ad-topbanner,.ad-wide-bottom,.ad-wrapper-sticky,.ad1-float,.ad1_bottom,.ad2",
        "-float,.ad300banner,.adArticleBanner,.adBanner300x250,.adBanner728x90,.adBlockBottom,.adFullbanner,.adHeaderAd",
        "banner,.adInnerLeftBottom,.adMrginBottom,.adOverlay,.adRectangleBanner,.adTopFloat,.ad__sticky,.ad_banner,.ad_",
        "banner2,.ad_banner_2,.ad_banner_250x250,.ad_banner_468,.ad_banner_728,.ad_banner_728x90_inner,.ad_banner_borde",
        "r,.ad_banner_div,.ad_big_banner,.ad_botbanner,.ad_bottom,.ad_bottom_728,.ad_bottom_leaderboard,.ad_bottom_left",
        ",.ad_bottom_mpu,.ad_bottom_space,.ad_center_bottom,.ad_container_bottom,.ad_content_bottom,.ad_custombanner,.a",
        "d_div_banner,.ad_float,.ad_floating_box,.ad_footer_super_banner,.ad_leader_bottom,.ad_megabanner,.ad_middle_ba",
        "nner,.ad_overlays,.ad_placeholder_d_sticky,.ad_side_rectangle_banner,.ad_top_banner,.ad_wrapper_fixed,.adace-a",
        "di-popup-wrapper,.adalert-overlayer,.adbanner-300-250,.adbanner-bottom,.adbanner1,.adbannerbox,.adbannerright,",
        ".adbannertop,.adblock-bottom,.adblock-popup,.adblock__banner,.adborderbottom,.adbottom,.adbottomright,.adbox-t",
        "opbanner,.adboxTopBanner,.adbox_bottom,.adbutler-top-banner,.adbutler_top_banner,.adform__topbanner,.adfoxly-o",
        "verlay,.adframe_banner,.adguru-modal-popup,.adpopup,.adriverBanner,.adrotate_top_banner,.ads--bottom-spacing,.",
        "ads-articlebottom,.ads-banner,.ads-banner-bottom,.ads-banner-js,.ads-banner-middle,.ads-banner-spacing,.ads-ba",
        "nner-top,.ads-banner-top-right,.ads-bing-bottom,.ads-block-bottom-wrap,.ads-bottom,.ads-bottom-block,.ads-bott",
        "om-center,.ads-bottom-content,.ads-bottom-left,.ads-bottom-right,.ads-google-bottom,.ads-horizontal-banner,.ad",
        "s-relatedbottom,.ads-sticky,.ads-superbanner,.ads-top-fixed,.adsBanner,.adsBannerLink,.adsBottom,.adsFixed,.ad",
        "sSticky,.adsTopBanner,.ads_banner,.ads_header_bottom,.ads_top_banner,.ads_topbanner,.adsbanner1,.adsbanner2,.a",
        "dsbottom,.adsbottombox,.adsense-float,.adsense-overlay,.adsense-sticky-slide,.adsenseBannerArea,.adsense_botto",
        "m,.adsensefloat,.adsfloat,.adsfloatpanel,.adslot-banner,.adslot_popup,.adspace_bottom,.adtech-banner,.adv-bann",
        "er,.adv-bottom,.adv-leaderboard-banner,.adv-top-banner,.advBottom,.advBottomHome,.adv_banner,.adv_banner_hor,.",
        "advads-sticky,.advert--banner-wrap,.advert--sticky,.advert-article-bottom,.advert-banner,.advert-banner-contai",
        "ner,.advert-banner-holder,.advert-bannerad,.advert-bottom,.advert-overlay,.advert-sticky-wrapper,.advert-wingb",
        "anner-left,.advert-wingbanner-right,.advert1Banner,.advertBanner,.advertBottom,.advertFullBanner,.advertSuperB",
        "anner,.advert__fullbanner,.advert_banner,.advert_banners,.advert_main_bottom,.advertisement-banner,.advertisem",
        "ent-bottom,.advertisementBanner,.advertising-banner,.advertising-fixed,.advertising-top-banner,.advertisingBan",
        "ner,.advertising_banner,.advertising_bottom_box,.advertisment-banner,.advertizing-banner,.adverts--banner,.adv",
        "iewDFPBanner,.advt-banner-3,.adzbanner,.after_content_banner_advert,.anzeige_banner,.article-ad-bottom,.articl",
        "eBottom-ads,.article_ads_banner,.article_bottom-ads,.article_bottom_ad,.banner--ad,.banner-125,.banner-300,.ba",
        "nner-300-100,.banner-300-250,.banner-300x250,.banner-300x600,.banner-320-100,.banner-468,.banner-468-60,.banne",
        "r-468x60,.banner-728,.banner-728x90,.banner-ad-below,.banner-ad-block,.banner-ad-bottom-fixed,.banner-ad-conta",
        "iner,.banner-ad-contianer,.banner-ad-footer,.banner-ad-image,.banner-ad-inner,.banner-ad-label,.banner-ad-larg",
        "e,.banner-ad-pos,.banner-ad-row,.banner-ad-skeleton-box,.banner-ad-space,.banner-ad-wrap,.banner-ad-wrapper,.b",
        "anner-ad2,.banner-ads-right,.banner-ads-sidebar,.banner-adsense,.banner-adv,.banner-advert,.banner-advert-wrap",
        "per,.banner-advertisement,.banner-advertising,.banner-adverts,.banner-asd__title,.banner-buysellads,.banner-im",
        "g > .pbl,.banner-sponsorship,.banner-top-ads,.banner120x600,.banner160,.banner160x600,.banner200x200,.banner30",
        "0,.banner300x250,.banner336,.banner336x280,.banner350,.banner468,.banner728,.banner728-ad,.banner728-container",
        ",.banner728x90,.banner_160x600,.banner_240x400,.banner_250x250,.banner_300_250,.banner_300x250,.banner_300x600",
        ",.banner_468_60,.banner_468x60,.banner_728_90,.banner_ad-728x90,.banner_ad_300x250,.banner_ad_728x90,.banner_a",
        "d_container,.banner_ad_footer,.banner_ad_full,.banner_ad_leaderboard,.banner_ad_link,.banner_ad_wrapper,.banne",
        "r_ads1,.banner_reklam,.banner_reklam2,.banner_slot,.bannergroup-ads,.banners_ad,.block-ads-bottom,.block-adver",
        "tisement-banner-block,.bottom-ad,.bottom-ad--bigbox,.bottom-ad-banner,.bottom-ad-box,.bottom-ad-container,.bot",
        "tom-ad-desktop,.bottom-ad-large,.bottom-ad-placeholder,.bottom-ad-wrapper,.bottom-ad-zone,.bottom-ad2,.bottom-",
        "ads,.bottom-ads-container,.bottom-ads-sticky,.bottom-ads-wrapper,.bottom-banner-ad,.bottom-fixed-ad,.bottom-le",
        "ft-ad,.bottom-mobile-ad,.bottom-mpu-ad,.bottom-post-ad-space,.bottom-post-ads,.bottom-right-advert,.bottom-sid",
        "e-advertisement,.bottom_ad,.bottom_ad_block,.bottom_ad_placeholder,.bottom_ad_responsive,.bottom_ads,.bottom_b",
        "anner_ad,.bottom_banner_advert_text,.bottom_bar_ads,.bottom_left_advert,.bottom_right_ad,.bottom_side_ad,.bott",
        "om_sponsor,.bottom_sticky_ad,.box-fixed-ads,.brn-ads-sticky-wrapper,.c-ad--adStickyContainer,.c-advert-superba",
        "nner,.c-advertising__banner-area,.cb-ad-banner,.chapter-bottom-ads,.container-banner-ads,.container-bottom-ad,",
        ".content-ads-bottom,.content-bottom-ad,.ct-bottom-ads,.custom-advert-banner,.custom-sticky-ad-container,.custo",
        "m_banner_ad,.dac__banner__wrapper,.desktop-ad-banner,.dfp-banner,.dfp-banner-slot,.dfp_ad_content_bottom,.entr",
        "y-bottom-ad,.entry-bottom-ads,.first-banner-ad,.fixed-ad-aside,.fixed-ad-bottom,.fixed-ads,.fixed-bottom-ad,.f",
        "ixed-sidebar-ad,.fixed_ad,.fixed_advert_banner,.floated-ad,.floated_right_ad,.floating-ads,.floating-advert,.f",
        "ollower-ad-bottom,.footer-banner-ad,.footer-banner-ads,.footer-floating-ad,.footer_bottom_ad,.fullbanner_ad,.g",
        "allery-ad-overlay,.gnm-banner-ad,.google-ads-bottom,.google-bottom-ads,.gpt-leaderboard-banner,.gpt-mpu-banner",
        ",.guide__row--fixed-ad,.has-fixed-bottom-ad,.head-banner468,.header-ad-banner,.header-banner-ad,.header-banner",
        "-ads,.header-banner-advertising,.home-sticky-ad,.home_ad_bottom,.homepage-banner-ad,.inline-banner-ad,.is-stic",
        "ky-ad,.js-ad-banner-container,.js-ad-loader-bottom,.js-ad-unit-bottom,.js-dfp-ad-bottom,.js-no-sticky-ad,.js-o",
        "verlay_ad,.js-sticky-ad,.l-bottom-ads,.leaderboard-ad-fixed,.leaderboard_banner_ad,.m_banner_ads,.mc_floating_",
        "ad,.mid-article-banner-ad,.mid_banner_ad,.mj-floating-ad-wrapper,.moads-top-banner,.mob-hero-banner-ad-wrap,.m",
        "obile-fixed-ad,.mobile_ad_banner,.modal-ad,.mpu-top-left-banner,.news_vibrant_ads_banner,.ng-ad-banner,.o-ad-b",
        "anner-top,.oio-banner-zone,.overlay-ad,.overlay-ad-container,.overlay-ads,.overlay-box-ad,.overlay_ad,.p-ad-df",
        "p-banner,.page-bottom-fixed-ads,.player-ad-overlay,.pm-ads-banner,.pm-banner-ad,.profile_ad_bottom,.region-ad-",
        "bottom-leaderboard,.region-banner-ad,.show-sticky-ad,.sidebar-banner-ad,.sidebar-bottom-ad,.sidebar-sticky--ad",
        ",.single-bottom-ads,.single-post-bottom-ads,.single_bottom_ad,.site-bottom-ad-slot,.sponsor-bottom,.sponsor-po",
        "pup,.sponsor_popup,.sponsored-container-bottom,.sticky-ad,.sticky-ad-bottom,.sticky-ad-container,.sticky-ad-fo",
        "oter,.sticky-ad-header,.sticky-ad-wrapper,.sticky-ads,.sticky-ads-container,.sticky-ads-content,.sticky-advert",
        "-widget,.sticky-bottom-ad,.sticky-footer-ad,.sticky-footer-ad-container,.sticky-navbar-ad-container,.sticky-ra",
        "il-ad-container,.sticky-side-ad,.sticky-sidebar-ad,.sticky-top-ad-wrap,.sticky_ad_sidebar,.sticky_ad_wrapper,.",
        "sticky_ads,.top-ad-sticky,.top-ads-bottom-bar,.top-adsense-banner,.top-banner-468,.top-banner-ad,.top-banner-a",
        "d-container,.top-banner-ad-wrapper,.top-banner-add,.top-banner-ads,.top-banner-advert,.top-fixed-ad,.top_ad_ba",
        "nner,.tpd-banner-ad-container,.tpd-banner-desktop,.vi-sticky-ad,.video-ad-bottom,.vjs-ad-overlay,.vodl-ad__big",
        "sizebanner,.wp_bannerize_banner_box,.wpx_bannerize_banner_box,.ww_ads_banner_wrapper,.boxOverContent__banner,.",
        "RC-AD-BOX-BOTTOM,.RC-AD-TOP-BANNER,.popup-box-ads,.amp-sticky-ad-custom,.amp-sticky-ads,#mobile-swipe-banner,#",
        "banner_pos1_ddb_0,#banner_pos2_ddb_0,#banner_pos3_ddb_0,#banner_pos4_ddb_0,.taboola-banner{display:none!import",
        "ant}';(document.head||document.documentElement).appendChild(__museSt);}catch(e){}var host=location.hostname;va",
        "r __museAdSet=new Set(\"005ad66f1d.com,10bet.com,152media.com,18porn.sex,18teensex.tv,19sex.live,1adda2699c.com",
        ",1av.to,1betandgonow.com,1gbjadpsq.de,1pop.online,1push.io,206ads.com,20tracks.com,21f5f01ad3.com,21sexturycas",
        "h.com,22523e8ad1.com,22betpartners.com,22bettracking.online,22media.world,24affiliates.com,24click.top,24x7ads",
        "ervice.com,27bad22e4a.com,2mdn.net,2smarttracker.com,2track.info,300daytravel.com,360ads.com,365av.net,3c1d2ad",
        "9d8.com,3d-porn.co,3mads.com,3myad.com,492tracker.net,4ad4ba0610.com,4affiliate.net,4dsbanner.net,4kporn.xxx,4",
        "pornhd.com,4rabettraff.com,4tube.com,4wnetwork.com,567bets10.com,56ad42c3a8.com,570dad652f.com,5advertise.com,",
        "62ad.com,70ad2eae1e.com,777av.cc,840withnetworks.net,888media.net,888promos.com,8c56c86ead.com,8d1uavww.xyz,8f",
        "7cd5dfad.com,8sfdladng.xyz,900bets10.com,901radius.net,930networks.net,99ad29e111.com,99ddb6ad48.com,a-ads.com",
        ",aa783adfeb.com,aaddcount.com,aanetwork.vn,aavvsaqmktwmu.website,aaxads.com,abadit5rckb.com,abadit5rckd.com,ab",
        "ateadversity.com,abethow.com,abnad.net,aboveaverageheavenly.com,abovemyselfsadness.com,abroadsuicidaleliminate",
        "d.com,absoluporn.com,absolutionthornleader.com,academywristadroit.com,acam-2.com,acceptableredheadcaviar.com,a",
        "cceptancecrowdadvertising.com,accomplishedpersuadedprinter.com,accoybrava.com,ace-adserver.com,acebet.com,acep",
        "orntube.com,achievablecpmrevenue.com,achievementseeingladder.com,acmexxx.com,acrossexterminatorfoggy.com,activ",
        "ejavgg124.fun,ad-adapex.io,ad-arrow.com,ad-back.net,ad-balancer.net,ad-block-offer.com,ad-cheers.com,ad-delive",
        "ry.net,ad-flow.com,ad-free.info,ad-m.asia,ad-mapps.com,ad-maven.com,ad-mixr.com,ad-nex.com,ad-opt.site,ad-reco",
        "mmend.com,ad-score.com,ad-server.co.za,ad-srv.net,ad-stir.com,ad-tech.ru,ad-vortex.com,ad.gt,ad.guru,ad.page,a",
        "d.plus,ad.style,ad1n.net,ad20.net,ad2391c451.com,ad2adnetwork.biz,ad2bitcoin.com,ad2f8c6f8e.com,ad2the.net,ad4",
        ".com.cn,ad4989.co.kr,ad4game.com,ad62c988a3.com,ad69.com,ad6media.fr,ad999.biz,adacado.com,adactioner.com,adac",
        "ts.com,adadvisor.net,adagora.com,adahumanhear.com,adalh-zcq.com,adalliance.io,adalso.com,adamantundergone.com,",
        "adamatic.co,adandhub.com,adangle.online,adaos-ads.net,adapex.io,adappi.co,adaptationprodigalevil.com,adaptdivi",
        "nity.com,adaptedestimatedcaress.com,adaptedmercifulinterruption.com,adapterwith1024.net,adapthopperpriest.com,",
        "adaptive5000.net,adaptunemployed.com,adaround.net,adarutoad.com,adasgsts.cc,adatrix.com,adbakbhknpczi.site,adb",
        "ard.net,adbasket.net,adbeacon.com,adbestnet.com,adbetclickin.pink,adbetnetwork.com,adbfkaoxvm.in,adbit.biz,adb",
        "its.online,adblade.com,adblck.com,adblock-360.com,adblock-guru.com,adblock-one-protection.com,adblock-tester.c",
        "om,adblock-zen.com,adblockanalytics.com,adblocker-sentinel.net,adblockermax.com,adblockeromega.com,adblockerpr",
        "ofessional.com,adblockersentinel.com,adblockstream.com,adblockstrtape.link,adblockstrtech.link,adbmi.com,adboo",
        "st.it,adbooth.com,adbox.lv,adbpage.com,adbrite.com,adbro.me,adbuddiz.com,adbuff.com,adbull.com,adbutler-fermio",
        "n.com,adbutler.com,adbwublzlyxvg.online,adbyss.com,adca.st,adcalm.com,adcannyxml.com,adcash.com,adcdnx.com,adc",
        "ell.com,adcell.de,adcharriot.com,adcleanerpage.com,adclerks.com,adclick.pk,adclicka.com,adclickad.com,adclicka",
        "frica.com,adclickbyte.com,adclickmedia.com,adclickppc.com,adclicks.io,adcloud.net,adcolo.com,adcolony.com,adco",
        "nscious.com,adcontext.pl,adcovery.com,adcrax.com,addefenderplus.info,addictedpledgeabridge.com,addictrelive.co",
        "m,addin1.name,addinginstancesroadmap.com,addiply.com,additionalcasualcabinet.com,additionalheritagenose.com,ad",
        "ditionalmedia.com,additionmillionaireimpending.com,addizhi.top,addkt.com,addoer.com,addonsmash.com,addoor.net,",
        "addotnet.com,addressimpertinenceclockwise.com,addroid.com,addroplet.com,addthief.com,addynamo.net,adecn.com,ad",
        "ehhurslxxmh.space,adelement.com,adelphic.net,ademails.com,adenc.co.kr,adengage.com,adentifi.com,adenza.dev,ade",
        "oplonrprkm.site,adespresso.com,adevppl.com,adewpxnzsnvib.site,adex.media,adexchangeclear.com,adexchangecloud.c",
        "om,adexchangedirect.com,adexchangegate.com,adexchangeguru.com,adexchangemachine.com,adexchangeprediction.com,a",
        "dexchangerapid.com,adexchangetracker.com,adexcite.com,adexmedias.com,adextrem.com,adf01.net,adfgetlink.net,adf",
        "inity.pro,adfinix.com,adflyer.media,adfoc.us,adfootprints.com,adforcast.com,adforgames.com,adforge.io,adform.n",
        "et,adfox.cam,adfpoint.com,adfrontiers.com,adfusion.com,adfyre.co,adgage.es,adgatemedia.com,adgear.com,adgebra.",
        "co.in,adgebra.in,adgitize.com,adglare.net,adglidepro.com,adgoi.com,adgorithms.com,adgrid.io,adgroups.com,adgrx",
        ".com,adhash.com,adhaven.com,adhealers.com,adherebarrowdomesticated.com,adherepreserveshrimp.com,adhese.be,adhe",
        "se.com,adhese.net,adhigh.net,adhitzads.com,adhoc2.net,adhouse.pro,adhub.digital,adhub.media,adhunt.net,adiktee",
        "v.com,adimpact.com,adinc.co.kr,adinc.kr,adinch.com,adingo.jp,adinplay.com,adintend.com,adinterax.com,adinvigor",
        "ate.com,adipolo.com,adipolosolutions.com,adiquity.com,adireland.com,adireto.com,adisfy.com,adisn.com,adit-medi",
        "a.com,adition.com,aditize.com,aditms.me,aditsafeweb.com,aditude.io,adjacenthairshag.com,adjal.com,adjector.com",
        ",adjkulzooirq.com,adjoininglearning.com,adjs.media,adjug.com,adjuggler.com,adjuggler.net,adjungle.com,adjust.c",
        "om,adjustmentthesilver.com,adjux.com,adk2x.com,adkaora.space,adkernel.com,adklimages.com,adklip.com,adkmbc.com",
        ",adknowledge.com,adkonekt.com,adkora.com,adkova.com,adlane.info,adlatch.com,adlayer.net,adlegend.com,adligatur",
        "e.com,adlightning.com,adlink.net,adlive.io,adlogists.com,adlook.me,adloop.co,adlooxtracking.com,adlpartner.com",
        ",adlserq.com,adltserv.com,adlux.com,adlzmqouddtim.space,admaaodfwkcuj.store,admachina.com,adman.gr,admana.net,",
        "admanmedia.com,admaru.com,admasters.media,admatic.com.tr,admatic.de,admax.network,admaxim.com,admcmscyyhzem.on",
        "line,admd.ink,admdoqipetokp.space,adme-net.com,adme2.click,admedia.com,admediaportal.com,admediatex.net,admedi",
        "t.net,admedo.com,admeerkat.com,admeking.com,admeridianads.com,admetricspro.com,admicro.vn,admidainsight.com,ad",
        "ministerembodiment.com,administersurlyunbelievable.com,admirableinstance.com,admirecallpalpate.com,admiredjump",
        "er.com,admiregarrisonnetwork.com,admissibleblinkbesiege.com,admissibleconference.com,admission.net,admissionde",
        "meanourusage.com,admitad-connect.com,admitad.com,admittanceskirtanthony.com,admittedhi.com,admixer.md,admixer.",
        "net,admjmp.com,admmjpbxebrfu.space,admoai.com,admob-cn.com,admob.com,admobe.com,admolo.com,admonishconcentrate",
        ".com,admonishfistgipsy.com,admonishmentfastest.com,admonishmenttub.com,admost.com,admothreewallent.com,admpire",
        ".com,admulti.com,admunvlvrcsaf.online,adnade.net,adnami.io,adnami2.io,adnanny.com,adncdn.net,adnet.biz,adnet.c",
        "om,adnet.de,adnet.lt,adnet.ru,adnetworkperformance.com,adnetwrk.com,adnext.fr,adnext.pl,adngin.com,adnico.jp,a",
        "dnigma.com,adnimation.com,adnimo.com,adnitro.pro,adnium.com,adnmore.co.kr,adnotebook.com,adnow.com,adnuntius.c",
        "om,adnxs-simple.com,adnxs.com,adnxs.net,adobe.com,adocean.pl,adodishonourcrutch.com,adokutrtb.com,adolcgailvyc",
        "g.site,adomic.com,adomik.com,adonnews.com,adonweb.ru,adop.co,adoperator.com,adoperatorx.com,adopexchange.com,a",
        "dopsboost.com,adopstar.uk,adoptim.com,adoptum.net,adorableoil.pro,adorableold.com,adorableorchid.com,adorableq",
        "uestion.pro,adorarama.com,adorika.com,adornmadeup.com,adornmenttaunt.com,adosia.com,adotic.com,adotmob.com,ado",
        "to.net,adotone.com,adotube.com,adpacks.com,adparlor.com,adpass.co.uk,adpatrof.com,adpay.com,adpays.net,adpeeps",
        "hosted.com,adperfect.com,adperium.com,adpicmedia.net,adpinion.com,adpionier.de,adplsr.com,adplugg.com,adplxmd.",
        "com,adpmbexo.com,adpmbexoxvid.com,adpmbglobal.com,adpmbtf.com,adpmbtj.com,adpmbts.com,adpnut.com,adpod.in,adpo",
        "ne.com,adpool.bet,adpopblzuma.in,adport.io,adport.net,adpredictive.com,adpresenter.de,adprime.com,adprnvjzubyg",
        "w.online,adpushup.com,adqsqmeyixjaf.online,adquake.com,adquery.io,adquire.com,adqva.com,adqxchmsttoyz.online,a",
        "dreactor.com,adreadytractions.com,adrealclick.com,adrecord.com,adrecover.com,adrecreate.com,adrenalinemanipula",
        "tiveact.com,adrenovate.com,adrent.net,adresellers.com,adrevenuerescue.com,adrevolver.com,adrianpeachdesign.com",
        ",adrifttenderly.com,adright.co,adrino.io,adrise.de,adro.co,adrocket.com,adroitham.com,adroitirresponsibleshoes",
        ".com,adroitswarmcultural.com,adrokt.com,adroll.com,adrta.com,adrunnr.com,ads-abc.com,ads-adv.top,ads-bitcoin.c",
        "om,ads-blocker.app,ads-blocker.pro,ads-pixiv.net,ads-tracking.com,ads-twitter.com,ads.cc,ads1-adnow.com,ads255",
        "0.bid,ads2ads.net,ads3-adnow.com,ads4g.pl,ads4media.online,ads4trk.com,ads5-adnow.com,ads6-adnow.com,ads7-adno",
        "w.com,adsafeprotected.com,adsafety.net,adsame.com,adsappier.com,adsarcade.com,adsb4trk.com,adsbar.online,adsbe",
        "tnet.com,adsblockersentinel.info,adsbookie.com,adsboosters.xyz,adsbreak.com,adsbtrk.com,adsbuddy.net,adscale.d",
        "e,adscampaign.net,adschill.com,adscienceltd.com,adsco.re,adscoreip.com,adscout.io,adscreendirect.com,adsdk.com",
        ",adsdot.ph,adsenix.com,adsensecamp.com,adsensecustomsearchads.com,adserv.mobi,adserve.com,adserve.ph,adserverp",
        "lus.com,adserverpub.com,adservicemedia.dk,adservingfactory.com,adservon.com,adservrs.com,adsession.com,adsessi",
        "onserv.com,adsexse.com,adsfac.net,adsfast.com,adsforallmedia.com,adsgateway.net,adshack.com,adshnk.com,adshopp",
        "ing.com,adshostnet.com,adshot.de,adsiduous.com,adsight.nl,adsinstant.com,adsinteractive.com,adsjqaobchetb.spac",
        "e,adskape.ru,adskeeper.co.uk,adskeeper.com,adskpak.com,adslidango.com,adslivecorp.com,adsloom.com,adslop.link,",
        "adslot.com,adslots.com,adslowezlzadr.space,adsmarch.online,adsmarket.com,adsmart.hk,adsmediabox.com,adsnative.",
        "com,adsninja.ca,adsniper.ru,adsoftware.top,adsolutely.com,adsonar.com,adsparc.net,adspdbl.com,adspector.io,ads",
        "peed.com,adspeed.net,adspirit.de,adsplay.in,adsplex.com,adspop.me,adspredictiv.com,adspruce.com,adspygl.xyz,ad",
        "spyglass.com,adsquash.info,adsquasher.com,adsquasher.pro,adsquirrel.ai,adsratcarp.com,adsreference.com,adsring",
        ".com,adsrv.net,adsrv.wtf,adsrv4k.com,adsrvmedia.com,adsrvr.org,adstargeting.com,adstargets.com,adstean.com,ads",
        "terra.com,adsterraserver.com,adstik.click,adstock.pro,adstoo.com,adstook.com,adstub.net,adstudio.cloud,adstuna",
        ".com,adsturn.com,adsupply.com,adsupplyads.com,adsupplyads.net,adsupplyssl.com,adsurve.com,adsvert.com,adsvolum",
        ".com,adsvolume.com,adswam.com,adswizz.com,adsxtits.com,adsxtits.pro,adsxyz.com,adt328.com,adt545.net,adt567.ne",
        "t,adt574.com,adt598.com,adtags.mobi,adtaily.com,adtaily.pl,adtarget.biz,adtarget.com.tr,adtarget.market,adtdp.",
        "com,adtear.com,adtech.de,adtechium.com,adtechjp.com,adtechus.com,adtegrity.net,adtelligent.com,adteractive.com",
        ",adtheyhaveset.org,adthrive.com,adtival.network,adtlgc.com,adtng.com,adtoma.com,adtonement.com,adtonos.com,adt",
        "oox.com,adtotal.pl,adtpix.com,adtrace.org,adtraction.com,adtrafficquality.google,adtrcdn.io,adtrhogpvnwkt.spac",
        "e,adtrieval.com,adtrix.com,adtrue.com,adtrue24.com,adtumxjckljej.space,adult-sex-games.com,adult.xyz,adult3dco",
        "mics.com,adultadvertising.net,adultadworld.com,adultasianporn.com,adultcamchatfree.com,adultcamfree.com,adultc",
        "amliveweb.com,adultdeepfakes.cam,adultfilmdatabase.com,adultforce.com,adultfriendfinder.com,adultgameexchange.",
        "com,adultimate.net,adultlinkexchange.com,adultsense.com,adultsense.net,adultsense.org,adultsjuniorfling.com,ad",
        "ultswim.com,adunblocker.com,adup-tech.com,adv-adserver.com,advally.com,advancedadblocker.pro,advancedsystemrep",
        "airpro.com,advancingtheirs.com,advanseads.com,advantageroughplay.com,advard.com,advcash.com,adventori.com,adve",
        "nturefeeds.com,adventurehannahmechanic.com,adventurerforewordpatio.com,adventurouscomprehendhold.com,adverge.a",
        "i,adversal.com,adversaldisplay.com,adversalservers.com,adverserve.net,advertica.com,adverticum.net,advertipros",
        ".com,advertise.com,advertiserubyproximity.com,advertiserurl.com,advertiseserve.com,advertisespace.com,advertis",
        "ing-cdn.com,advertising365.com,advertisingiq.com,advertisoo.com,advertjunction.com,advertlets.com,advertnative",
        ".com,advertone.ru,advertserve.com,advertsource.co.uk,advertur.ru,advfeeds.com,adviad.com,adviceglossaryeminenc",
        "e.com,advisesaltsarmless.com,advisorthrowbible.com,adviva.net,advmaker.ru,advmd.com,advmedialtd.com,advombat.r",
        "u,advon.net,advotionhot.com,advozcevf.com,advp1.com,advp2.com,advp3.com,advpx.com,advpy.com,advpz.com,advserve",
        "rt.com,advsnx.net,advtise.net,adw9.com,adwalte.info,adway.org,adwebster.com,adworldmedia.com,adworldmedia.net,",
        "adwx6vcj.com,adx-t.com,adx.io,adx.ws,adx1.com,adxadserv.com,adxapi.online,adxawkejwjdfh.site,adxbid.info,adxfi",
        "re.in,adxfire.net,adxhand1.name,adxion.com,adxnexus.com,adxpansion.com,adxpartner.com,adxplay.com,adxpose.com,",
        "adxpremium.com,adxpremium.services,adxpub.com,adxscope.com,adxsource.com,adxsrver.com,adxvsipicgwop.site,adxxx",
        ".biz,adxxxxxmost.co,adyoulike.com,adysis.com,adywrlubfplrh.site,adz2you.xyz,adzbazar.com,adzblockersentinel.ne",
        "t,adzcivwjgktmz.site,adzen.ai,adzerk.net,adziff.com,adzilla.meme,adzilla1.name,adzivijnyizgp.space,adzmedia.co",
        "m,adzmob.com,adzouk.com,adzpier.com,adzs.com,adzs.nl,aehjiuheifpop.online,aemediatraffic.com,afdads.com,aff-tr",
        "ack.net,aff1xstavka.com,affbuzzads.com,affidavitheadfirstonward.com,affiliate-b.com,affiliate-robot.com,affili",
        "ate-wg.com,affiliateboutiquenetwork.com,affiliateedge.com,affiliateer.com,affiliatefuel.com,affiliatefuture.co",
        "m,affiliategroove.com,affiliatelounge.com,affiliatemembership.com,affiliates.systems,affiliatesensor.com,affil",
        "iatesleague.com,affiliatestonybet.com,affiliatewindow.com,affstrack.com,afftrack.com,afftrackr.com,afiadwcsvnt",
        "pn.space,afishamedia.net,afrikad.com,afrsportsbetting.com,afterdownload.com,afterdownloads.com,afyads.com,agav",
        "stdlunmyb.website,agendadejection.com,aggravatingchicken.pro,aggravatingoil.com,agravicsensual.cyou,agvmufccua",
        "vus.online,ahadsply.com,aheadpains.com,ahejadicyivel.site,aim4media.com,aipmedia.com,airlessstereoadvised.com,",
        "ak-tracker.com,akavita.com,aknadnauhsmgu.space,al-adtech.com,alarmsportsnetwork.com,alexatracker.com,alienatep",
        "aradise.com,alipromo.com,alleliteads.com,alleviatepracticableaddicted.com,allmediadesk.com,allosponsor.com,all",
        "pornovids.com,alludesexptl.cyou,almightypush.com,alpha-affiliates.com,alphaporno.com,alterassumeaggravate.com,",
        "alternads.info,alternatewavyrid.com,alternativecpmgate.com,alturaadvertising.com,alumniheadfirstshamrock.com,a",
        "mateurcouplewebcam.com,amateurporn.net,amazing-dating.com,amazon-adsystem.com,americanacademyofthermology.org,",
        "amesgraduatel.xyz,amididsavola.com,amperwave.net,amsacpcugjc.in,amuletsourcemadden.com,amusedradish.com,anacam",
        "s.com,analsexstars.com,anastasiasaffiliate.com,ancestorheadquarters.com,aneadtwrlpqob.online,anguishaddition.c",
        "om,angularpoppyrobbing.com,anjavjkadvun.in,anonymousads.com,antiadblock.info,antiadblocksystems.com,antique-av",
        "erage.com,antonythwartlead.com,anyadx.live,anyclip-media.com,anymedia.lv,anyporn.com,anysex.com,aorzbyyrhlavn.",
        "site,apbrumavwr.in,apex-ad.com,apophishoggism.cfd,appendad.com,appetitecamisolereawake.com,apromoweb.com,aqua-",
        "adserver.com,arabyads.com,arbattadr.in,arcadebannerexchange.org,arcadechain.com,arcadiablankdenying.com,archip",
        "elagokennelleadership.com,arid-leadership.pro,arkadiumhosted.com,aromadenotes.cyou,arsesexuded.cfd,arxoplagzia",
        "vr.site,asaunmavgitw.com,ascendeummedia.com,ascentlitigateunsaved.com,asdasdad.net,asgadmp.cc,asgadsts.cc,asgc",
        "lick.com,asgclickpp.com,asian-xxx-videos.com,asiangfsex.com,askdomainad.com,asktiava.com,asterouhavenev.org,as",
        "thmanotchcave.com,athyimemediat.org,atube.xxx,auavaxrfgyyrv.online,audiblyacademyadaptation.com,audience2media",
        ".com,august15download.com,aurescadrachen.com,auroraveil.bid,authorizedimmediate.com,auto-ads6.site,autoads.asi",
        "a,automatad.com,autotrader.ca,autotrader.co.za,auyydavghsuox.site,avads.co.uk,avads.live,avalancheofnews.com,a",
        "valanchetremorunfilled.com,avariciousnobody.pro,avatrade.io,avatraffic.com,avazu.net,avazutracking.net,avbsqhu",
        "nnykee.site,avcafzoz.in,avdludlmpseoe.space,avebeentriflin.com,aveherald.com,avelpwnzhdoyx.site,avenidageranic",
        ".cyou,avenuenineteenthendorsement.com,average-movie.com,average-statement.com,averinierne.cyou,avestastupendfr",
        "eit.cyou,avevumywadmrw.website,avfay.com,avfbqhwuowien.online,avfdikaldj.in,avgle.com,avgony.xyz,avhlvyyhjhvli",
        ".site,avhtaapxml.com,avhxlgougslod.space,aviatedmidair.com,avifwlqraveqt.online,avigatejungle.click,avilionrew",
        "rote.shop,avimdxzekvlhe.space,avjecheybdtqj.space,avjlllfpqvloi.website,avkauywgzdxlx.online,avkmgwykmovxx.sit",
        "e,avkszbsmzamaj.site,avlmy.com,avmakravzqfvj.space,avmmsekplixsk.space,avmviiih.com,avn.com,avnhqqqoljvgf.spac",
        "e,avnqsnekvmpzu.site,avnwqayeafuve.website,avocams.com,avouchlawsrethink.com,avouchuramil.com,avoutergubbins.q",
        "pon,avowdanielungrave.cyou,avowingoxaliteglancer.qpon,avpjihtkjsnfe.space,avplayer.com,avqjufpki.in,avqovczcoa",
        "np.com,avsfppmelmkzr.online,avswajharwhe.in,avszjedalkciq.space,avthelkp.net,avtrrdtzmnmsv.website,avvpsstgohe",
        "ny.online,avxsduffdiewn.space,avxskbxkmtawx.space,avylshidkaggoj.com,avypcwqqhuz.com,avytybblrvweh.space,avzaq",
        "maoryehd.website,avzmhurdchosj.space,avzwgvitrfmsbg.com,avzxcwkpckxyz.space,awakengoaveserasion.cyou,awmradivl",
        "amtw.online,awrsfadi.in,axabpelvjfqav.site,axiaffiliates.com,axoxktmvnhtav.site,axupfpniadauk.site,ayads.co,ay",
        "adsdyieahbn.space,ayxbetuxafa.com,azadify.com,azoogleads.com,aztadtvrog.in,b677b1ad62.com,babelmedia.global,ba",
        "besinporn.com,babyboomboomads.com,backbeatmedia.com,badgegirdle.com,badgertulip.com,badjocks.com,badjojo.com,b",
        "adlandlispyippee.com,badsllcvgillm.space,badwtokg.com,bagdigravy.com,balzadicyema.com,bancadeltempoidea.org,ba",
        "nner-iframe.com,banneradsday.com,bannerbank.ru,bannerboo.com,bannerbridge.net,bannerconnect.com,bannerconnect.",
        "net,bannerdealer.com,bannerflow.com,bannerflow.net,bannerignition.co.za,banneroctober.store,banners5html2.com,",
        "bannersmall.com,bannersnack.com,bannersnack.net,bannerweb.com,baseporno.com,bavaroylocker.cyou,bavbfmokxwqtn.s",
        "pace,bavdxafbegkvr.space,bavggygpconuq.site,bavoozimaxiev.website,bc48fa9adc.com,bcaadnibyntzm.space,bckbhadz.",
        "com,bcwhnizcbethy.website,bdavjbhjpzjmt.space,beaconadnetwork.com,beavertron.com,bebraveknob.com,becausepop.co",
        "m,beead.net,beginsrainbowcamomile.com,behave.com,behavepassword.com,belavoplay.com,beleavenbg.com,bellamylavat",
        "ory.com,benefit-ads.com,bentadaptation.com,beringmedia.com,bestadload.com,bestadsforyou.com,bestcontenttrade.t",
        "op,bestcpmnetwork.com,bestfunnyads.com,bestonlinecasino.club,bestrevenuenetwork.com,bet3000partners.com,bet365",
        ".com,bet365affiliates.com,betads.xyz,betafot.com,betahit.click,betaoutinnovations.net,betclever.com,beterrakio",
        "nan.com,betfair.com,betforakiea.com,betforce.io,betgorebysson.club,betmasquerainchan.com,betmenaff.com,betoga.",
        "com,betotodilea.com,betpartners.it,betplays.com,betpupitarr.com,betr-for-tod.com,betrad.com,betrapcb.cfd,betra",
        "yalbadger.com,betrayedjoltmethod.com,betshucklean.com,betsson.com,bettentacruela.com,betteradsystem.com,better",
        "bonnet.pro,bettercollective.rocks,betterdatabase.pro,betterdomino.com,bettingcurly.com,bettingoffer.xyz,bettin",
        "gpartners.com,bettingpremier.com,bettraff.com,between-legs.com,betweenbrr.com,betweendigital.com,betweenprimar",
        "ily.com,betwinner1.com,betzillopartners.com,betzone2000.com,beutavpysjojf.site,bexkpopzujdus.space,bf-ad.net,b",
        "gwsktadxvgjv.website,bhabhiporn.pro,bhavkiyrxbifv.space,bhwgdvpavsfzw.site,biadglomdzyys.online,bicyclebusgrav",
        "e.work,bidclickmedia.com,bidderads.com,biemedia.com,bigboxads.com,biodegradablegrand.com,biqadnwzega.in,bistre",
        "dchavercaption.qpon,bit-ad.com,bitadexchange.com,bitcoadz.io,bitmedia.io,bitonclick.com,bitspush.io,bizonads-s",
        "sp.com,bizzclick.com,bjuojadykz.com,bkgwadxzovl.com,bkjblyuukoavf.site,blacksexmatch.com,bladdersdisarmaware.c",
        "om,bladderssewing.com,bladeamendable.com,bladedescendant.com,bladespillowcase.com,bladswetis.com,blamads.com,b",
        "larcamxooysi.store,blaze-media.com,blinkedlanentablelanentableunavailable.com,blinksbobblepadding.com,blismedi",
        "a.com,block-ad.com,blockadsnot.com,blockchain-ads.com,blogads.com,blogherads.com,blue-ribbonmacadamizeprovide.",
        "com,bluekai.com,bluepromocode.com,bluerunningshadow.com,bluetoad.com,blwadijbvbttx.space,bmfads.com,bmmads.com",
        ",bngmadjd.de,bngpop.com,boffoadsfeeds.com,bonad.io,bongacams.com,bongacams10.com,bongacams11.com,bongacams7.co",
        "m,bongacams8.com,bookadil.com,bookbannershop.com,bookofsex.com,boomads.com,boostads.net,boreaddingobserve.com,",
        "boskodating.com,bounceads.net,bovinetimingunsaddle.com,bovmkbuiaddbb.website,boxlikepavers.com,bqszugpxadzlg.s",
        "tore,brainlyads.com,brave.com,bravefork.com,braverybreezebinding.com,braveryriftraining.com,bravoteens.com,bra",
        "votrk.com,bravotube.net,brazzersnetwork.com,breadmljlfdwx.site,bridgetrack.com,brigadedelegatesandbox.com,brig",
        "htadnetwork.com,britainbetrayalbands.com,broadensilkslush.com,broadlyjukeboxunrevised.com,broadsheetspikesnick",
        ".com,broadsidedaisysanta.com,broadstreetads.com,brodownloads.site,broggleceburbetire.qpon,brothypopuli.com,bru",
        "celeadx1.com,brxqlwrzcpcvx.website,bsadcmesynzyb.store,bskuxhcaduumi.online,btagmedia.com,btccasinoexpert.com,",
        "bthtcgwaniavh.website,btpnav.com,btpopoogrnqvj.site,btprmnav.com,bttrack.com,btxxxnav.com,bucketsofbanners.com",
        ",budadymskizcs.online,buffetaccommodating.com,bukvwavcmonjv.space,burstyflavia.com,businessclick.com,butwehave",
        "ino.com,buyadvupfor24.com,buysellads.com,buysellads.net,buzzadexchange.com,buzzadnetwork.com,buzzonclick.com,b",
        "uzzparadise.com,bwqfyjaynjpad.site,bwwuveplotadr.site,byumhwhapayad.space,byxxxporn.com,bzltavhalapzv.online,b",
        "ztzpzqpltlad.site,c21media.net,c4tracking01.com,ca.ijknabd.top,cachuadirked.top,cactusheadroomscaling.com,cade",
        "ncebenefit.com,cadsecs.com,cadsimz.com,cadskiz.com,cadwgsofoyxky.site,calombotubelet.com,cam-content.com,cam-l",
        "olita.net,cam4.com,cam4flat.com,cam4tracking.com,camailwhickerruddles.cyou,camaligraia.cyou,cambaddies.com,cam",
        "bay.tv,cambioturkish.com,camcaps.ac,camcaps.io,camcaps.to,camcrush.com,camdough.com,camduty.com,camelusnakulab",
        "iotite.cyou,cameoedexcuse.com,camghosts.com,camhub.cc,camiocw.com,camliveshow.xyz,cammpaign.com,camomilegentle",
        "mennotable.com,camonade.com,camonster.com,camonster.io,camouflagemm.com,campaignlook.com,campanaoutgrin.cyou,c",
        "ampartner.com,campenosine.qpon,campfirecroutondecorator.com,camph.org,camplacecash.com,camptwined.com,cams.com",
        ",cams.gratis,camsaim.com,camschat.net,camsense.com,camshq.info,camsitecash.com,camsoda.com,camsoda1.com,camspa",
        "celive.com,camsplanetlive.com,camterest.com,camvideos.tv,camzap.com,candyaversion.com,candynetwork.ai,cantorsp",
        "aver.com,caravanexhale.com,carbonads.com,carbonads.net,carpalsgeneuradiiamb.qpon,cartoonporn.to,casalemedia.co",
        "m,cash-ads.com,casinobonusdeals.io,casinohacksforyou.com,casinousagevacant.com,casiyouaffiliates.com,casserole",
        "rehabilitationgradual.com,casumoaffiliates.com,catchtheclick.com,caveletganoine.qpon,cavergalere.com,cavernsin",
        "aite.com,cavewrap.care,cavuscecilydvornik.cfd,cavvfjxabrffj.website,cavzgoqiozmyc.space,cbhadfiwcdgsl.space,cc",
        "jzuavqrh.com,cdn-adtech.com,cdn-adtrue.com,cdn4ads.com,cdnads.com,cdymqigrkwzav.site,ceasedaggravation.com,cel",
        "eb-ads.com,celebjihad.com,celebjihad.live,cellaradorn.com,cerxxxwjtxebr.site,cfivfadtlr.com,cgpjpvvcadzrp.stor",
        "e,chabadokor.com,chainads.io,chalazataverns.com,chandrabinduad.com,changedadroitharassment.com,charltonmedia.c",
        "om,cheemsporn.com,chiatlaadovus.website,chihuahuadoorstoppond.com,chikiporn.com,chipleader.com,chpadblock.com,",
        "chymiadolors.com,chzhqwirsxmad.online,cibleclick.com,cieapkjfmavqd.site,cinemadeck.com,circaeavamper.com,circl",
        "ejavgg124.fun,citadelexampleruckus.com,citadelpathstatue.com,citizenshadowrequires.com,citrusad.com,city-ads.d",
        "e,cityadspix.com,cjgimadfnuztn.online,cjlmffavyoprm.site,ckiqxrqwnavqt.website,ckwouavsimupd.space,cladupius.c",
        "om,clash-media.com,clavetriple.cyou,clavuvirameses.com,clbanners16.com,cleanmediaads.com,clearadnetwork.com,cl",
        "earonclick.com,cleavebullydevaluation.com,cleverads.vn,clicadu.com,click-cdn.com,click-for-preview.com,click4.",
        "pro,clickable.com,clickad.pl,clickadilla.com,clickadsource.com,clickadu.com,clickadu.io,clickadu.net,clickagy.",
        "com,clickaine.com,clickallow.net,clickbank.net,clickbet88.com,clickblitzo.com,clickbooth.com,clickboothlnk.com",
        ",clickcash.com,clickcdn.co,clickcertain.com,clickco.net,clickdaly.com,clickdescentchristmas.com,clickexperts.n",
        "et,clickfilter.co,clickfunnels.com,clickfuse.com,clickinc.com,clickintext.com,clickintext.net,clickiocdn.com,c",
        "lickmagick.com,clickmediahub.com,clickmetertracking.com,clickmi.net,clickmobad.net,clickmon.co.kr,clicknano.co",
        "m,clicknerd.com,clicknplay.to,clickopop1000.com,clickoutcare.io,clickoutnetwork.care,clickpapa.com,clickpoint.",
        "com,clickppcbuzz.com,clickprotects.com,clickpunch.icu,clickpupbit.com,clicks4tc.com,clicksgear.com,clicksor.co",
        "m,clicksor.net,clickterra.net,clickthruhost.com,clickthruserver.com,clicktimes.bid,clicktoupdservice.online,cl",
        "icktraceclick.com,clicktripz.com,clicktrixredirects.com,clickupto.com,clickwhore.com,clickwinks.com,clickwork7",
        "secure.com,clickxchange.com,clinicadentalmariangeleslazcano.com,clivetadds.com,clladss.com,clobberprocurertigh",
        "twad.com,clockwiseleaderfilament.com,cloudbet.com,cloudcinemaheavily.com,cloudtrack-camp.com,clrqbqtonradt.onl",
        "ine,clusiavacuo.qpon,cmadserver.de,cmfads.com,cnptuyxjmdave.site,cocambgwyeeym.website,cockpitadministration.c",
        "om,codeonclick.com,coffeavirify.com,coinad.in,coinad.media,coinads.online,coinadster.com,coinmedia.co,collectb",
        "ladders.com,comediandesired.com,comfortclick.co.uk,comicad.net,commiseratebeamingbarricade.com,commonwealthgro",
        "ovemadam.com,compassionatespreadinquire.com,complexnetwork.pro,comradegoodsfloor.com,concealtraveller.com,conc",
        "iergeradialsupernova.com,condescendingaveragerain.com,conditioneavesdroppingbarter.com,conduit-banners.com,con",
        "nectad.io,connectingadjusthospitality.com,conscientiouscabbageadrift.com,constantlyauthoritativegradually.com,",
        "containingstripesadmonish.com,contemplatepushed.com,contemplatewaryheadquarter.com,content-loader.com,contentc",
        "ave.co.kr,contentclick.co.uk,contextads.live,contextuads.com,continue-download.com,contributorshaveangry.com,c",
        "ontrolslaverystuffing.com,conxxx.pro,cookpad-ads.com,cooladblocker.info,coolerads.com,coolestinstead.com,coope",
        "rativepop.com,coordinatedwave.pro,coreadness.com,cosponsorgarnetmorphing.com,counciladvertising.net,coursejavg",
        "g124.fun,courthousecadenceconsiderable.com,couveaverral.cyou,covivado.club,coxmavefzvnqwl.com,cpaclickz.com,cp",
        "alabtracking.com,cpalead.com,cpcmart.com,cpcstar.com,cpm.biz,cpm20.com,cpmadvisors.com,cpmbugaboo.com,cpmclktr",
        "k.online,cpmleader.com,cpmmedia.net,cpmprofitablenetwork.com,cpmrevenuegate.com,cpmroi.com,cpmspace.com,cpmsta",
        "r.com,cpmterra.com,cpmtree.com,cpuzspopetlsg.space,cpvadvertise.com,cq.jqygnbfq.top,cqrksadeerzsp.space,cqvxrm",
        "wcwyvad.site,craaledcamistutu.qpon,cradaygo.com,crakbanner.com,crakmedia.com,crampleadspace.com,craniavestas.c",
        "om,crannyadministervolunteer.com,cravesgage.cyou,crazyporn.xxx,crbetfcijuhtw.site,creativeformatsnetwork.com,c",
        "rihbeppscadw.site,crispads.com,criteo.com,criteo.net,crockadilla.com,crocotube.com,crowderleavens.cyou,crowdsu",
        "bsequentadmire.com,crpdadadgogyh.site,crtracklink.com,cruhtravbsuio.space,crwdcntrl.net,crypto-ads.net,cryptob",
        "eneluxbanner.care,cryptocoinsad.com,css-load.com,cstwpush.com,csxbezadpeazu.site,ctebiooadmfty.space,ctm-media",
        ".com,cuisineenvoyadvertise.com,cuoieadrraxle.space,cupis-lady-dreampartner.com,cursecrookadvantage.com,customa",
        "ds.co,cvyadqizofrsw.space,cylindrical-wave.com,czgacpadsspuj.space,czhavjzsxkcwa.space,czhjjavbyhuun.online,cz",
        "uadkhcudkl.com,czuoszwava.in,d58ad0184a.com,da-ads.com,daadpefptsdqv.online,dacqfadozkvie.site,dadaejghzcnaq.s",
        "ite,dadmivxze.in,dadqcuxqdnecx.space,dads-banging-teens.com,dadsats.com,dadsimz.com,dadsoks.com,daftporn.com,d",
        "ailyporn.club,dakafxgnqcpmm.website,dallavel.com,damagedbehaved.com,dantaradiohd.net,dargopastinadogsled.cyou,",
        "dark-reader.com,darknessporn.com,date4sex.pro,datedeskads.com,datetrackservice.com,dating-amour-club.com,datin",
        "g-honey-girls.com,dating-honeygirles.com,dating-masters-haven.com,dating-service.net,dating2cloud.org,datingad",
        "vicefree.com,datingamateurs.com,datingero.com,datinggold.com,datinghoneygirls.com,datingmeetnet.com,datingprud",
        "ethimble.com,datingshall.life,datingsspads.com,datingstyle.top,datingtoday.top,datingtopgirls.com,datingtorrid",
        ".top,datingwithmee.net,datoporn.com,davincisgold.com,davipkybnzfji.site,davtwymjlx.com,davynetriumf.com,dbdhna",
        "vwbvhep.website,dboccfzxlavyp.online,dcraddock.uk,dcssptrack.com,ddownload.com,de2bdad928.com,dead-put.com,dea",
        "delegantannotation.com,deadlinescreechvisit.com,deadlyfeasible.com,decknetwork.net,decorateadverb.com,dedating",
        ".online,dedicatedmedia.com,dedicatednetworks.com,deductgreedyheadroom.com,deeavouter.com,deepxtube.com,deltabr",
        "avo.net,demandingoverdriveunthread.com,demdex.net,deofleadyetca.org,dependantcarefullyheavenly.com,dependentad",
        "equatemyth.com,depleteheadphoneshoe.com,deployads.com,depriverippledecadence.com,depwwdntgavyc.website,despote",
        "avesdropblazing.com,detectbravecomfy.com,developermedia.com,dextralravi.cyou,dgafgadsgkjg.top,dggazqmcpmtwc.we",
        "bsite,dgzykxavrvekj.site,dhovqdnulcadz.site,digipathmedia.com,digitaladvertisingalliance.org,digitalmediapp.co",
        "m,digitalmediaworld.tv,digitalpush.org,diningfreshmansavage.com,dinsbetrlsiqc.space,dinzfafad.in,directaclick.",
        "com,directadvert.ru,directcpmfwr.com,directnavbt.com,disableadblock.com,discoveredcowardlymadden.com,discretio",
        "ningratitudeadvisedly.com,disgustingmad.com,disneyadvertising.com,disparagewardenbetrayal.com,displaycontentne",
        "twork.com,displaynetworkcontent.com,displaynetworkprofit.com,disqusads.com,distractiontradingamass.com,dixypor",
        "n.com,djncmafidfcpm.online,dmzadtwonmlfn.online,dnavexch.com,dobbyporn.com,dollyspreadingbeer.com,domainadvert",
        "ising.com,domesticatednavigationgrip.com,domipush.com,doodlesunshinecamp.com,dopaleads.com,doseofporn.com,doub",
        "leadserve.com,doubleclick-cn.net,doubleclick.com,doubleclick.net,down-paradise.com,download-file.org,download-",
        "performance.com,downloadboutique.com,downloaddecidedly.com,downloadoperagx.com,downloadyt.com,downstairspoppy.",
        "com,dpfwqzjkavjmb.site,dporn.com,dqhepocpcwspj.space,dradvice.in,dragstergibletshaded.com,draviteinosine.com,d",
        "readedrevisablevest.com,dreadfullyemulateconservation.com,dreadfullynormallyinsist.com,dreadfulrulingextension",
        ".com,dreamteamaffiliates.com,droghvadutsov.space,dropdeadgems.org,dropkickmedia.com,droughtpavidsoths.cyou,drs",
        "mediaexchange.com,drtuber.com,dsadghrthysdfadwr3sdffsdaghedsa2gf.xyz,dtadnetwork.com,dtyamiadjnapm.space,dtzva",
        "vrnbzemd.space,duchesspleasurelollipop.com,dulvxkusxdcpc.online,dunderaffiliates.com,dunhilltraveldeals.com,du",
        "psavour.com,duregqgfvravp.space,duxlpoavm.in,dvwrcpmeeozgq.site,dvzycytrxbxav.space,dwlladiupirrc.site,dymqtoj",
        "vadily.site,dynad.net,dz4ad.com,dziorhnav.in,dzubavstal.com,e7e0addf05.com,ea-tube.com,eadeuifduvres.site,eads",
        "rv.com,eadziukwqzeeb.space,earatikvavrsa.site,earbossysavvy.com,earringmavin.cyou,easy-ads.com,easy-dating.org",
        ",easyad.com,easyadsapp.com,easyporn.xxx,eavakddcdofik.site,eavesdropbogusye.com,eazyleads.com,ebannertraffic.c",
        "om,ebayadservices.com,ebetoni.com,ebfclhdkkmeav.store,eboglvqdtadmz.space,ecknbonfiftad.website,eclick.vn,ecxu",
        "fxavldnpd.site,ed49f13aad.com,edcuaqnhioavg.com,editorovertimeradiation.com,edshookherhead.org,edzusegqavqik.s",
        "tore,eekjdpreeghad.com,effectivecpmcontent.com,effectivecpmgate.com,effectivecpmnetwork.com,effectivecpmrate.c",
        "om,effectivegatecpm.com,effectiveratecpm.com,eforebuthehadsta.org,efrwdjavjkiqw.online,egadvertising.com,egoti",
        "sticexcavateplywood.com,egyihjgwfzadc.site,ehaveagoldenbed.org,ehszycpcxrskg.website,ejadhobthckao.site,ekvhgf",
        "ruqyadw.site,eladove.com,elasticad.net,elatedcamera.com,eleavers.com,elephant-ads.com,elliottwave.com,elliptic",
        "altrack.com,elntlrutxdavp.online,elogiado.info,elsespeakingbetraying.com,elutessheavednude.cyou,embraveanderun",
        ".com,emediate.dk,emergencyadditions.com,eminentcleaveproduces.com,emjmeadlsjrho.online,emqyssutavqxc.online,en",
        "bpnlaiatvav.space,encoresweaved.com,engineladleworn.com,enporn.org,entertainenslave.com,eociugytvzhad.website,",
        "eoievavdtucqs.online,eonsmedia.com,epbrwsksexzem.site,ephesiansavvyinto.com,epicclicks.net,epicgameads.com,epi",
        "dermispushupvariable.com,eporner.com,epxniaadddb.com,eqads.com,eradisciplinemomentary.com,ergonomicparadeupstr",
        "oke.com,erhlyvjhetcad.online,ero-advertising.com,eroadvertising.com,erotikdating.com,errandchavish.cfd,eruptst",
        "arapinbetrail.cfd,erwknxujxxxbh.space,esawbynaavjmi.online,escalatenetwork.com,eskarsunleavesmokier.cyou,essay",
        "ads.com,estrelabetpartners.com,ethereumads.com,ethicalads.io,etjrkgaqrkfad.space,eudgjelqtadcq.website,euro4ad",
        "s.de,euros4click.de,evadereprimand.com,evadesickness.com,evasiuywadnox.space,everesttech.net,everydayporn.co,e",
        "vmdamxavzsgk.website,evolvemediallc.com,ewesmedia.com,exactshaver.shop,excavatedefeatedshudder.com,excavatenea",
        "rbywand.com,exchange4media.com,excitead.com,exjzuoukavbnv.space,exnesstrack.com,exoclick.com,exoticads.com,exp",
        "lorads.com,extensions-media.com,extensivetravelled.com,externalfavlink.com,extinguishbethpasta.com,extqoqykkcp",
        "mo.space,extremescatporn.com,eyeofporn.com,eyeshadowkeynote.com,eyeviewads.com,eyqsrrwmvhavp.website,ezadblock",
        "er.com,ezdownloadpro.info,ezvthdrknbfad.space,f30fpad7.xyz,f85fd721ad.com,fa85cadfb0.com,faceporn.com,fadegran",
        "ted.com,fadexplosion.com,fadppvjumphyh.online,fadsipz.com,fadskis.com,fadslimz.com,faithfullyreadiness.com,fal",
        "linmatches-paradises.com,fam-ad.com,familyporner.com,fancytravelingrid.com,fantasticbet.com,fapnado.com,fapnad",
        "o.xxx,fastclick.net,fastnativead.com,fastwavehilltop.com,fattenspopulum.com,favigirningnonly.cfd,favmaqyqgcirx",
        ".space,favorite-mine.com,favoriteinside.pro,favosevergery.cfd,favousshakha.cfd,favusraband.com,favy.cam,fbetli",
        "xgee.eu,fbmedia-ckl.com,fcqrzagadhlvn.online,fdadmvbteroqf.space,feadrope.net,feckfulmisaver.com,feed-ads.com,",
        "feedbackadmiralfortnight.com,feedyourheadmag.com,fellowearnwave.com,fenacheaverage.com,ferntravelleddeduct.com",
        ",festaporno.com,festtube.com,feudalbetrayalcast.com,ffnetbeteeguu.site,fgvowyavmmcau.site,fiddlestructureparad",
        "ise.com,file-upload.net,filestube.com,filingcarnivorousexclaim.com,financeads.net,financialfadinglobster.com,f",
        "inancialgobetweendancer.com,findbetterresults.com,findcouponspromos.com,finddatinglocally.com,fiorewonyhade.on",
        "line,fireworkadservices.com,fireworkadservices1.com,fireworkswad.com,firstclass-download.com,firstload.de,fish",
        "ermanwithavenge.com,fixavcha.in,fixionmedia.com,fkdeavpxkjuoa.space,flagads.net,flairadscpc.com,flashadtools.c",
        "om,flat-ads.com,flaviar.com,flavidwarwolf.cfd,flavoryjehus.com,flirtclick-matches.com,flower-ads.com,fluavilde",
        "mist.com,fluemenavid.qpon,fluxads.com,flyads1.com,flyingadvert.com,flymyads.com,fmsads.com,footballtradedirect",
        "ory.com,footztube.com,forbesresistread.com,foremedia.net,forestysavory.qpon,forfrogadiertor.com,forgavediscipl",
        "inetolerance.com,forgivenbeavervague.com,forkconnectinghead.com,foxpush.io,fpadserver.com,fqadcwyr.com,fractio",
        "nsadly.com,fracturebroadcastsaw.com,freakads.com,free-datings.com,freeadblockerbrowser.com,freeadultcomix.com,",
        "freecamsexposed.com,freedomadnetwork.com,freefromads.com,freefromads.pro,freelivesex.tv,freelygreatestscammer.",
        "com,freeonlinegayporn.com,freepopnews.skin,freeporn8.com,freepornxxx.mobi,freepublicporn.com,freesexcam365.com",
        ",freeuseporn.com,freewebcams.com,freshforestroad.com,freshpops.net,frivol-ads.com,frnaqbeavtdvm.website,fryhzc",
        "adnypnn.online,fs-loader.com,fuck-dating.com,fuckbookdating.com,fufrbtjgadkncr.com,funnedravage.com,furiousexp",
        "ansion.com,fusetracking.com,fusionads.net,fusterquaver.com,futureads.io,fxavdiifzfjbw.website,fxgzkkroeavcj.sp",
        "ace,fxrokhvydhadu.website,fxxavsxmuovyd.website,fyauuswqwavou.website,fytbadthewihr.store,gacurad9559.net,gadg",
        "etfortunatelysunny.com,gadgets360.com,gadlt.nl,gadmagckfy.com,gadnsohyud.com,gadsabs.com,gadsatz.com,gadslimz.",
        "com,gadspms.com,gadspmz.com,gadssystems.com,galaxypush.com,galleries-pornstar.com,gambadudman.com,game-clicks.",
        "com,gameads.io,gamer-network.net,gamersad.com,gaming-adult.com,gamingadlt.com,gamingadult.com,gandrad.org,gapw",
        "adfcmeilz.space,gasolineimproveabroad.com,gatetotrustednetwork.com,gaveastound.com,gavedeliverknee.com,gavnuif",
        "k.xyz,gavotsalleleu.com,gay4porn.com,gayadnetwork.com,gayadpros.com,gayporntube.com,gazeflavour.com,gbads.net,",
        "gbcamahjbcsff.site,gcpcvtdsdztbz.website,gcpczbniuuuoo.site,gdsavcfiitlxs.online,gearcambridge.com,geckad.com,",
        "geeunlessarcade.com,genesismedia.com,geniad.net,geniusonclick.com,geoaddicted.net,georgianacaravanscoop.com,ge",
        "rmaniavid.com,getflowads.net,getjad.io,getmyads.com,getpopunder.com,gettubetv.com,gfadvpljfwagd.online,gfesbjn",
        "advxrb.online,ggbet-online.net,ggbetapk.com,ggbetpromo.com,ghepxcvmadltj.space,giantaffiliates.com,gibedencave",
        ".cfd,giftornado.com,girls-romancehaven.com,gitads.dev,gjkrwpafad.com,gkvfndkdzavrv.online,glade-static.online,",
        "gladlydotselfevident.com,gladlythattoady.com,glaoxxxppcvwz.site,glkadcvafpqhe.online,global-adblocker.com,glob",
        "aladblocker.com,globaladblocker.net,globaladmedia.com,globaladmedia.net,globaladsales.com,globaladv.net,globtu",
        "be.com,gloporn.com,glsfreeads.com,gmads.net,gmmads.com,gmpavcpqcdhxu.space,gnawpush.com,gnopfoaddfxto.website,",
        "gnxnfkdgadbkf.online,go-g3t-push.net,go2bdmbet.com,go2media.org,goads.pro,goadserver.com,goclicknext.com,godad",
        "dy.com,godlesscourieradmirable.com,gofdtdxdtzkav.site,good-ads-online.com,goodadvert.ru,googleads-cn.com,googl",
        "eadservices-cn.com,googleadservices.com,googleadsserving.cn,googlesyndication.com,googlevads-cn.com,gotibetho.",
        "pro,gotrackier.com,gotrackier.io,gourmetads.com,gpsecureads.com,gqavlsyfgmkeb.online,graciamediaweb.com,gradua",
        "ltelevision.com,graduateamazingly.com,graduateangeles.com,graduatedfreezing.com,graduatedoutside.com,graduatio",
        "npaltryherbal.com,gradwcntoprvn.online,granddadfindsponderous.com,grasshoppergainfulexcavator.com,gravegrandmo",
        "ther.com,gravelsemesterflourish.com,gravelspeorianstime.cyou,gravelyjovialelemental.com,gravenesswomanlesszest",
        "fully.com,gravenpicked.com,graveuniversalapologies.com,gravicjibbs.com,gravitationtaxpayer.com,greenadblocker.",
        "com,greenads.org,gregalemedials.qpon,grievecamouflage.com,grimytradition.pro,gripdownload.co,gripping-bread.co",
        "m,groovinads.com,gross-ladder.pro,grvmedia.com,guacosravaka.com,guadscfwaweae.site,gunzblazingpromo.com,guploa",
        "d.xyz,guvrwavupliw.in,gvcaffiliates.com,gwalaissexruj.site,gwdkavydmyuf.com,gwhbladbytxd.com,gyoqtcogunavk.sit",
        "e,h12-media.com,haadqzvaencuq.online,hadronid.net,hadsanz.com,hadsecz.com,hadsokz.com,haffnetworkmm.com,hairdr",
        "essergraduate.com,halogennetwork.com,harborjavgg124.fun,hardbritlads.com,harrenmedianetwork.com,hasaeblmtcadr.",
        "website,hattedraved.com,hauledquaverguardian.com,haveameet.com,havefiliate.com,havelberme.qpon,havenclick.com,",
        "havenyl.xyz,haverdentata.cfd,havetohave.com,havingunderwear.com,havpcbrpaftjv.online,havstpqgejqbw.site,hazels",
        "trictlyheadless.com,hcjzvzvavb.com,hddrfyoifiuad.website,hdkbxadnbxvua.online,hdopxenavimwu.online,hdporn24.or",
        "g,hdpornfree.xxx,hdpornmax.com,hdpornphotos.com,hdtube.porn,hduolaveybzmo.site,headacheaim.com,headbidder.net,",
        "headerbidding.ai,headerlift.com,headlinesplus.com,headphonedilution.com,headquartersfallclassify.com,healthtra",
        "der.com,heardspadedime.com,heateradvisoraplenty.com,heathertravelledpast.com,heathsleavenlinaloe.cfd,heavenful",
        "l.com,heavengabbykatcina.cyou,heavenly-holiday.com,heavenmedia.com,heavenomitzoom.com,heavenspecificallygait.c",
        "om,heavenstyler.cyou,heavepacepallor.com,heavilyaflareknifes.qpon,heimalesssinpad.com,heldsadness.com,heliumad",
        "s.com,hellporno.com,helpfulavenue.com,herexxxtube.com,hetadinh.com,hetlmedia.com,hfblxccnycadt.website,hhbetev",
        "elthyg.site,hicpm10.com,highcpmcreativeformat.com,highcpmgate.com,highcpmrevenuegate.com,highcpmrevenuenetwork",
        ".com,highperformancecpm.com,highperformancecpmgate.com,highperformancecpmnetwork.com,highprofitnetwork.com,hig",
        "hratecpm.com,highrevenuecpm.com,highrevenuecpmnetwork.com,highrevenuenetwork.com,highwaycpmrevenue.com,hilltop",
        "ads.com,hilltopads.net,hiltonbett.com,himediads.com,hiprofitnetworks.com,hitcpm.com,hityyzyxbadim.online,hjvew",
        "zbtkbetn.online,hmadkixrebk.in,hmdypadvcolcw.space,hnbanvxtkavun.site,hnntube.com,hntixkyrwyiav.website,hnzjwt",
        "cajadzi.space,hoadaphagoar.net,hoasvvtyavmbn.store,hockeyhavoc.com,hodlwvavzwt.com,hodnjqyavomuj.site,hollow-p",
        "opulation.pro,holyfamilyradio.org,home-xxx-videos.com,homejavlive.com,homemade.xxx,honestbroadcasting.com,hone",
        "stinfuriatemedia.com,hooliganmedia.com,hopcleftirradiate.com,horse-racing-affiliate-program.co.uk,hostadsmarke",
        "ts.com,hostave.net,hostave2.net,hostave3.net,hostave4.net,hot-bbw-tube.com,hot-sex-tube.com,hotadultvids.com,h",
        "ousejomadkc.com,houtoucavus.cfd,hpadjptcyyafh.space,hpdipavuplwoe.website,hqporn.su,hqpornstream.com,hraayzvvj",
        "lead.site,hrlvadtkfqils.space,hrrlwaavrttks.online,hryaavcnugcpp.site,hsefhnuodtav.com,html-load.cc,html-load.",
        "com,huavdntfzhprm.online,hueads.com,hueadsxml.com,huluads.info,hunterlead.com,hurlmedia.design,hustlereadcoffe",
        "ehouse.com,hvwoeavjjemhq.site,hwepopmhhsz.com,hwpnocpctu.com,hyenadata.com,hyhavaflmeguk.site,hyperbanner.net,",
        "hyperpromote.com,iadbsntna.com,iadnsamqheemb.space,iaeaadfleusbk.site,iajlxlnkkocav.space,ianoubcdoavip.websit",
        "e,iasbetaffiliates.com,iavlxljzdclmz.space,iavszerhxigkh.space,ibannerexchange.com,ibrapush.com,iceporn.com,ic",
        "lickcdn.com,icyporno.com,idealmedia.io,idevaffiliate.com,idhoyoadekuzf.website,idreammedia.com,ieecfjcwadvrz.o",
        "nline,ieladhfhoiclv.online,ifadnfhgaozlo.store,igoraodadkwlk.space,iiasyavgjqwat.site,iionads.com,ijiyhavkjzvi",
        ".com,ijpopvca.com,ikqyazeadzqvo.space,ileuadlrdnpow.space,ilxilmadwxfah.space,imageadvantage.net,imbuemadeup.c",
        "om,imediaaudiences.com,img-load.com,imgadult.com,imgkdetaddxre.space,imiclick.org,immediateequivalentstunning.",
        "com,immediatelydoghead.com,impact-ad.jp,impactradius-go.com,impactradius.com,imperfect-dead.pro,imprasavariac.",
        "com,impressexaltsculptor.com,imyanmarads.com,in-appadvertising.com,in-page-push.com,in-page-push.net,inadequat",
        "ebuoythin.com,inadequateconsolation.com,inadmissibleeyewitness.com,indiadesire.com,indianbannerexchange.com,in",
        "dieclick.com,indigestiontreatmentgraduated.com,indlyment-stuador.com,indofad.com,indoleads.com,indyadvertising",
        ".com,inexpedientlads.com,infectiousmedia.com,infinite-ads.com,infinityads.com,influads.com,infobetting.com,inf",
        "ormedcleave.com,inheritedgeneralrailroad.com,inhousead.net,initiallybrigadebookstore.com,inkingabave.cfd,inkta",
        "d.com,inliersilicamvalyl.cyou,inpage-push.net,inpagepush.com,inporn.com,inscribenaiveavoid.com,insightadz.com,",
        "insightspendadvancement.com,insitepromotion.com,inskinad.com,inskinmedia.com,instantbannercreator.com,instantp",
        "aydaynetwork.com,insteadempty.com,insteadjell.com,insteadmodification.com,instinctradiatorrook.com,insurads.co",
        "m,intellibanners.com,intelligenceadx.com,intellipopup.com,interactiveads.ai,interadv.net,interclick.com,interm",
        "ediaryquay.com,intermediategillsevent.com,internationalsexguide.nl,interworksmedia.co.kr,intimidatingsideway.c",
        "om,intopicmedia.com,intporn.com,intrapromotion.com,intravert.co,invadedisheartentrail.com,invadermonks.com,inv",
        "iziads.com,inxxx.com,ioadserve.com,iohgttbethmaj.online,iohsoavvddvmo.store,iomaduexiqtho.space,iononetravoy.c",
        "om,ip-adress.com,ipornia.com,ipromote.com,ipvhqdchweavs.site,irbnydavspxxt.space,irfgithdpoadi.site,ironendeav",
        "our.com,irontube.net,irradiatestartle.com,iseawave.com,itsmillionlead.com,iuzvusqotkhad.online,ivstracker.net,",
        "iyqtpifqeoavq.site,iyrrtosjcravm.online,izavjtqqznucm.site,jaavnacsdw.com,jacaminfley.com,jacamitympana.qpon,j",
        "ackpotave.com,jackpotcitycasino.com,jadcenter.com,jaded-actor.com,jaded-wing.com,jadedsingle.com,jadedtwist.co",
        "m,jadehlbswdlqw.online,jadeinvitelawsuit.com,jadjkuofzwad.com,jadkuwwtyp.in,jads.cc,jads.co,jadwidal.com,jagis",
        "jbavpwdj.space,jamanetwork.com,jangonetwork.com,jarvispopsu.com,jav-bukkake.net,jav.sb,java8.xyz,javascriptcdn",
        "live.com,javbucks.com,javdock.com,javenglish.net,javfor.tv,javgg.eu,javgg.me,javhd-trk.com,javhd.com,javhdporn",
        ".live,javhub.net,javideo.net,javkagiitfkuw.site,javlibrary.com,javmg.com,javmix.tv,javmost.com,javpornclub.com",
        ",javpwyyvcoorg.website,javslon.com,javxxx.me,javynow.com,javyvkzwwsf.com,jbckwavvvsugv.space,jdfhwxboadwvg.onl",
        "ine,jdrfaqinvrcpm.space,jeetyetmedia.com,jegiopad.top,jegjuitgvavuz.space,jespwdvojaavj.online,jetavbemsmnur.s",
        "pace,jewellerybroadlyisabella.com,jewishadgroup.com,jewishcontentnetwork.com,jfoazkavnozuo.online,jghadikzlvvn",
        "r.space,jiawavgaksdbq.store,jicamasmiscrop.qpon,jilioadvrvpwm.site,jitterendeavour.com,jjqlzrxadgi.com,jmadykn",
        "jxonua.website,jmkladoihjrsa.space,jnqbkpsymoads.site,jolrfeqxyavvj.site,jovialcadence.com,joxaviri.com,jpadxr",
        "ydoiorz.online,jpavgtzkbjpwi.site,jpbphraoemadf.online,jpkwoyveladnt.online,jradumbwgmggr.space,jsadapi.com,js",
        "feedadsget.com,jtiavpaxso.in,jtxlzsuxadaxe.site,jubileebread.com,jubnaadserve.com,juddersexcusedligure.qpon,ju",
        "iceadv.com,juicyads.com,juicyads.me,jumboaffiliates.com,junbi-tracker.com,junkyadexchange.com,junmediadclikrmd",
        "i.com,justfullporn.net,justtube.net,jwrtoravx.com,jxavjloeqgrbz.space,jxcuocswkavpi.site,jxvhcvhdeaadf.site,jy",
        "jioxtadobac.website,jyusupfxadswy.site,jzhbethwfhnlq.space,kaadqpoorrhsy.site,kadam.ru,kadime.cc,kadmkyie.com,",
        "kagocvncavfqi.store,kappavowal.com,kavanga.ru,kavblcvs.com,kavcapfpjbhpn.space,kavekduhupvky.space,kavtqkbsyrp",
        "zt.site,kawai-jav.com,kbcjugyneadmk.online,kbwfirbugcpmk.site,kcpcmdepbvyjq.website,kdlgpcradvcmi.space,kebbyi",
        "zzyavicula.cyou,kfgxuiyutbwav.space,kfpuejiakadbw.site,khiclickrkafw.online,kidsinstancesfavor.com,kinkadserve",
        "rcdn.com,kiomthavsruuk.online,kjetdxtcammhc.com,kjkavoimtxart.online,kjmrldarxadmw.online,kkczadzgl.com,kllast",
        "road.com,klvadvvrbusee.website,kneestolerableadulthood.com,kniravkrmejus.online,knwjmjujhadoo.site,koalaglade.",
        "com,kohadfzjolyfy.website,konraddiscrimination.com,konradissuesimperial.com,konradsheriff.com,konradterriblese",
        "ct.com,kotqbebetyoeed.com,kpopsingers.com,krudavmbgnwrc.website,krzkasckoqvad.site,ktiebyesqavuz.online,kudtap",
        "gad.in,kunvertads.com,kvcmgvhnbadzq.online,kvjthavimymgk.online,kvsadman.com,kvylquisaduxr.store,kwgavfflgjpop",
        ".site,kwintravetoer.cyou,kxgadtlettueg.website,kzzbdsattavdw.space,labadena.com,labisexecute.com,ladbrokesaffi",
        "liates.com.au,ladeykyxsaosi.site,ladgritperformed.com,ladhlbetxnjlm.space,ladiesmatchesfinder.com,ladsabs.com,",
        "ladsans.com,ladsecz.com,ladsips.com,ladsipz.com,ladskiz.com,ladyboygoo.com,ladysabryna-xxiqlkt.work,lalapush.c",
        "om,lampshadescreen.com,landscaperedressave.com,lanxtkacpucpc.site,laptopprivacyado.com,lavatorydownybasket.com",
        ",lavatoryhitschoolmaster.com,lavender64369.com,lavenderzenith.com,lavishboot.com,lavnkios.in,layer-ad.org,layi",
        "ngframingcomrade.com,lblscpmfxuzqq.space,lbyarvladuyzy.space,lcpavtrwvavnq.website,ldqkyedvfepop.online,leadad",
        "vert.info,leadfamly.com,leadgainz.com,leadimmensely.com,leading-back.com,leadmediapartners.com,leadnote.me,lea",
        "dsecnow.com,leadsleap.net,leadzu.com,leaveundo.com,leavingaileen.com,leavingneedyengaged.com,leithstaving.cfd,",
        "lemonadds.com,leonbetvouum.com,leopardfavorite.com,leplvcbadrmgb.space,letsporn.com,lfavcwgydohba.store,lfstme",
        "dia.com,lgsmartad.com,lhkmedia.in,lhvavsdsnhxgz.website,liadm.com,libertystmedia.com,lifeporn.net,lifepromo.bi",
        "z,lightmeadowwalk.com,likeads.com,likelihoodcheerleaderdefender.com,likexxxtube.com,limetreeloadherself.com,li",
        "nkadvdirect.com,linkonclick.com,linksex.club,linuxtracker.org,litavvcc.in,literallycircumscribetome.com,litter",
        "arenadisembroildisembroil.com,liveadexchanger.com,liveadoptimizer.com,livecam.com,livecams.com,livepromotools.",
        "com,livesexasian.com,livesexbar.com,livesportmedia.eu,liveuniversenetwork.com,livexxx.me,livyncxvleiad.website",
        ",lkadhbifkzvtt.online,lkqagavhhaqrp.space,lkseavjkzupfw.space,llqmbetbgjhyt.website,lmavfuplmvhbq.space,lmueck",
        "vzozqav.website,load-res-static.lol,load-static-res.xyz,loadedbummer.com,loadercdn.com,loadingscripts.com,load",
        "time.org,lobefintinklecleaved.cyou,loboclick.com,localedgemedia.com,locksadvancedrelease.com,lodgeastronautado",
        "re.com,logsaddiction.com,lokiporn.com,loohaiturigavik.net,loppetickierbavoso.cyou,lotadversaryvolatile.com,lot",
        "ongmamonadowses.qpon,lotritereavows.cfd,lotteryaffiliates.com,loupesstavedtriene.cyou,love-banner.com,love4por",
        "n.com,lovecam.com.br,loveflirt-haven.com,lpmediastorage.com,lrvpquoavttir.site,lscsexsnjacwr.site,ltnavwfpsffp",
        "i.space,lucidmedia.com,luckyads.pro,luckyforbet.com,luggedzabeta.com,lumbersavians.cyou,lunacyumpireunleave.cf",
        "d,lunatictoxicambiguity.com,lunlyqvavjkfc.website,lustytube.net,luxadv.com,luxupadva.com,lvwytobyemady.online,",
        "lwadm.com,lwgadm.com,lwuntajpwadku.online,lwxmapxavophf.online,lxadqdidnsnzd.online,lxoukuwkzvada.online,lycon",
        "ery-readset.com,lzudmwytadqrg.site,m2track.co,mad-consist.com,madadsmedia.com,madamconducted.com,madcheddar.ne",
        "t,madcpms.com,maddenwiped.com,madeupice.com,madeupregular.pro,madeupteamspupil.com,madeupvigourpoll.com,madman",
        "biological.com,madnesscoupon.com,madqwcbhobeca.space,madratesforall.com,madrogueindulge.com,madsabs.com,madsan",
        "s.com,madsecs.com,madserving.com,madslimz.com,madsone.com,madspmz.com,madurird.com,mafroad.com,magicads.nl,mag",
        "netisemedia.com,maideneyeshadow.com,mainadcenter.com,mainadv.com,majorads.site,maleficscamper.qpon,malisonrepo",
        "pe.com,mangadex.org,mangadistrict.com,mangarawad.biz,mangoads.net,manipulativeslaves.com,manorflounderheady.co",
        "m,mantisadnetwork.com,manureads.com,manysex.com,manysex.tube,mappilaaverrer.com,marimedia.com,marinadelifestyl",
        "e.com,marinadeworriesdurable.com,marketjavgg124.fun,markswebcams.com,marmaladestrikingcolourless.com,marrergra",
        "ves.com,marsads.com,martpleadescended.com,masqueradeegoismcurtain.com,masqueradejournalism.com,masqueradepathe",
        "ticburden.com,mathads.com,mathtag.com,maturexxx.vip,mavq.net,mavrtracktor.com,maxiadv.com,maxonclick.com,maxva",
        "luead.com,mbidadm.com,mbsspads.com,mcbadljmbhdni.online,mcepadnkovcy.in,mdroadmmbxrlj.space,me4track.com,meado",
        "w-assets.click,meadow-static.xyz,media-412.com,media-amazon.com,media-click.ru,media-general.com,media-sapiens",
        ".com,media.net,media6degrees.com,mediaalpha.com,mediacandy.ai,mediacpm.pl,mediad2.jp,mediaf.media,mediaffiliat",
        "ion.com,mediaforce.com,mediaforge.com,mediafuse.com,mediahell.world,mediamixer.co.kr,mediapalmtree.com,mediapl",
        "ex.com,mediasama.com,mediatarget.com,mediatemple.net,mediative.ca,mediative.com,mediatradecraft.com,mediatrias",
        ".com,mediaupdate.co.za,mediavine.com,mediaxchange.co,medleyads.com,medyanetads.com,meetradar.com,meetsexygirls",
        ".org,megaad.nz,megadeliveryn.com,megaffiliates.com,megatube.xxx,mepuisgsxadha.site,mercenaryslaveryplays.com,m",
        "ergedlava.com,messyadvance.com,metavertising.com,mfadsrvr.com,mfeibqadbbzaz.site,mfyavsmiha.com,mgladthereisso",
        ".com,mgtckhufqjlad.store,mharavczumra.in,microad.jp,microad.net,microadinc.com,midas-network.com,midnighttradi",
        "tionally.com,midpopedge.com,migrationoctavian.com,mihmadovjkpi.in,mileporn.com,milftube.mobi,millennialmedia.c",
        "om,mimosaavior.top,minklemonade.com,minutemedia-prebid.com,minutemediaservices.com,miraclethisaddicted.com,mix",
        "advert.com,mk-ads.com,mkadujfywhxis.website,mkatakavcaxm.in,mktbqkzdzadrg.site,mladedda.net,mlnadvertising.com",
        ",mmadsgadget.com,mmdcokadaklfg.website,mnetads.com,mng-ads.com,moatads.com,mobileoffers-dld-download.com,mobil",
        "etracking.ru,mobipromote.com,mochicadermis.cyou,modelsgonebad.com,modulepush.com,mojoaffiliates.com,momentumme",
        "dia.com.au,monadplug.com,monetixads.com,monthsexpressive.com,moonads.net,moonrocketaffiliates.com,mootermedia.",
        "com,mortypush.com,motaflavine.com,motherlyglad.com,moustacheoverloadwar.com,movad.de,movad.net,movcpm.com,movi",
        "ead55.ru,moviemediahub.com,moviesex.org,mpanythathaveresultet.info,mpocadpbnevwa.site,mpsuadv.ru,mqdownload.co",
        "m,mqedledjjjdad.website,mqqhfyfavftyk.com,mrgrekeroad.com,mrporngeek.com,msads.net,msgpbwbfradny.website,mtdut",
        "avlkpzzz.site,mtwataveihajz.space,muggurscamden.click,multiwall-ads.shop,muniadazzled.qpon,mutenessdollyheadlo",
        "ng.com,mvmrcygtoadgz.online,mxhkadwedflsr.online,mxpopad.com,my-promo7.com,myadcash.com,myadultimpressions.com",
        ",myaffiliates.com,myaudioads.com,myavlive.com,mybestcpm.com,mybetterdatings.com,mybrowseraddon.com,mycams.com,",
        "mycasinoaccounts.com,mydatingstheladys-club.com,mydatingswomanonlines.com,mydatinguniverse.com,myeasetrack.com",
        ",myemailtracking.com,mykocam.com,mypopads.com,myprecisionads.com,myreadingmanga.info,mysexgames.com,mysexjourn",
        "ey.com,mysticaladoptionocean.com,mythad.com,mywebclick.net,mywovcjadrtcb.online,mzafanowhdcpc.space,nababroadb",
        "irch.com,nadese.xyz,nads.io,nadwkwzfjnxzw.site,naiadexports.com,nailadversary.com,naivewidespreadbody.com,name",
        "ads.com,nanoadexchange.com,narrowveneeringsavoury.com,nasbhavuovwzi.space,nasssmedia.com,nativclick.com,native",
        "-adserver.com,nativeadmatch.com,nativeads.com,nativeadsfeed.com,navaidaosmic.top,navajospoule.com,navalremembe",
        "redcarve.com,naviemytotgbg.online,navigatingnautical.xyz,navtlkdihvagi.online,navwmaakbo.com,navxhefspincz.spa",
        "ce,navyhalfclinical.com,navyrecentuproot.com,navyrecognition.com,navzkokpufm.in,nawpush.com,naxadrug.com,nbadg",
        "naqqqrxx.website,nbdysfxadsfji.website,ndfhqtezbkavl.online,ndhadbeensoa.org,ndqpopziznuuq.space,neads.deliver",
        "y,neatmadeiraairborne.com,necessarilymosquitoheadphones.com,neglectsquad.com,neladyhe.com,neptuntrack.com,neta",
        "venir.com,netcpms.com,netmedia.hu,netsolads.com,networkad.net,networkhubcontrol.com,networking444.net,networkm",
        "anag.com,networks1000.net,neudesicmediagroup.com,newjulads.com,newmayads.com,newoctads.com,newormedia.com,newo",
        "snotadprx.space,news-headlines.co,newsadsppush.com,newsatads.com,newscadence.com,newsmaxfeednetwork.com,newsun",
        "ads.com,newtab-media.com,newthuads.com,newtueads.com,nextclick.pl,nextmillmedia.com,nexxxt.biz,ngadverts.com,n",
        "glmedia.com,ngunmeumeklav.online,nguswfavipgqm.website,ngvyktsuuyadu.online,nhatsadwafljs.site,nhbavbqogc.com,",
        "nhiaoadcolahl.website,nhzylwcpmcchb.space,nikkiexxxads.com,nkyavipvkfxgo.online,noaaduliugvul.online,noavoahev",
        "ugrib.com,nobblespush.cyou,nomadsbrand.com,nomadsfit.com,noncommittalsupposedlybroadcast.com,nonsenseaggravati",
        "on.com,noosebanghad.com,norfolkbroads.com,northdownload.com,notifpushnext.net,novadune.com,novemberadventures.",
        "name,novibet.partners,nowfavourable.com,npcad.com,nrowumdcamr.in,nsfwadds.com,nsmartad.com,ntepadkozezji.onlin",
        "e,ntkgkyalavlcs.space,ntvpwpush.com,nutentertainintermediate.com,nutterguava.cyou,nvfigpopnczvp.online,nwnxyiv",
        "ttoaad.site,nxxxpmyejmudz.space,nylons-porno.com,nylonsporno.com,nythingamglad.com,nzavmqooblmpl.site,nzme-ads",
        ".co.nz,nznoxxx.com,nzwecadkryhup.site,oadehibut.xyz,oagbadcuwzruq.site,oavgofyxwyryj.online,oavqowdsenguy.onli",
        "ne,oavsgdmtbealk.website,oavurognaurd.net,oavyctdvbrlpz.space,obliquescallionexcavator.com,oboxads.com,obsolet",
        "epaddlevehicular.com,obstinacysexist.com,ocadnxsghpseo.space,ocoopdcpmqxpy.site,octopuspop.com,odnwadzre.in,oe",
        "ubadryzvwyk.space,ofbdaktxxxldn.space,ofcamerupta.com,offergate-games-download1.com,offerstrack.net,ogavjmydvh",
        "vqe.com,oglalavoodoo.world,ogzavfebqsdmy.online,ohlladamnak.in,ohmycams.com,oilcampshift.qpon,oirdwcadrzrqj.on",
        "line,oivpkegadjuqc.site,oizfzrjavugyq.space,ojtuqqiohqadx.online,ojxtxmaivsavq.site,okadgpkptxakv.website,okrq",
        "mavp.com,oktavvavg.in,olayomad.com,oldgamesdownload.com,omegaadblock.net,omegadblocker.com,omni-ads.com,omtrdc",
        ".net,onad.eu,onalskillsexkce.org,onclckpop.com,onclickads.net,onclickalgo.com,onclickclear.com,onclickgenius.c",
        "om,onclickmax.com,onclickmega.com,onclickperformance.com,onclickprediction.com,onclickpredictiv.com,onclickpul",
        "se.com,onclicksuper.com,onclicktop.com,onhercam.com,online-adnetwork.com,onlineporno.fun,onlinepromogift.com,o",
        "nlinepromousa.com,onlyalad.net,onnetwork.tv,onsetblouselampshade.com,ontnkqwlnimav.online,onxgaladrj.in,ooadrh",
        "sqiofhv.space,oohkbadeeaaem.site,ooozxxadrsnpo.space,oouutjpavsxfv.site,openadserving.com,openadultdirectory.c",
        "om,openrtb-banner.com,openx.net,oplmbxdyndadi.website,opptmzpops.com,opsqbrqhqcamb.online,optad360.io,optad360",
        ".net,optiads.org,optidownloader.com,oqmiadfkmlcwz.website,orangeads.fr,orangeclickmedia.com,orfrsexlxozvc.site",
        ",orgyxxxhub.com,origintube.com,orqlzuoqgad.com,osahsadctgzfa.site,osamadada.com,oscineabettal.cfd,oshacampelis",
        ".com,osiaffiliate.com,ospreymedialp.com,otaavlxnmqwyi.online,otoadom.com,ottadvisors.com,ottavvrdokror.website",
        ",ouiiadyzaieqx.space,ourtecads.com,outbrain.com,outbrain.net,outrageoussquad.com,outrightphiladelphia.com,outs",
        "tandingfrailabroad.com,overloadenjoy.com,overnightabroadcraftsman.com,ovgadhtcyvak.in,ovpniyavjvob.com,oxado.c",
        "om,oxtracking.com,oxzmasxuadysh.space,ozadzoddxxxqs.online,ozonemedia.com,p-advg.com,packlenseavesdrop.com,pad",
        "dleweekendnudge.com,padgntohmxigt.site,padgzdbyelrca.online,padinventoryvile.com,padsabs.com,padsans.com,padsa",
        "tz.com,padsims.com,padsimz.com,padskis.com,padslims.com,padspms.com,pakbanners.com,pantrycornyunload.com,panty",
        "hosepornstars.com,papayads.net,paraderascaldefinitely.com,paradise8.com,paradizeconstruction.com,paradocs.ru,p",
        "arlouroutlayfavor.com,partner-ads.com,partycasino.com,pas-rahav.com,patesihobbet.com,patwyavweuxzx.space,paulg",
        "ravitynumeral.com,pavagedisawa.cfd,paviliongiddy.com,paviortummels.com,pavljbnheqajx.space,pavramzdvprsi.onlin",
        "e,paxmedia.net,pay-click.ru,payloadz.com,payvclick.com,pblcpush.com,pboadsgyalldd.site,pc-ads.com,pcpcg47ycvwx",
        "zyuq8fkwu.cfd,pcyqadvwrqsmc.online,peacefulmeadowwalk.com,peadeplete.com,peafzfaghradr.com,peakclick.com,peavy",
        "egeria.cyou,pebadipetto.cfd,pebblemedia.be,pedxbqnadnebr.space,peltishpushful.com,pennynetwork.com,pepperjamne",
        "twork.com,perfecttoolmedia.com,performanceadexchange.com,performanceonclick.com,performancetrustednetwork.com,",
        "perimeteravertendless.com,perimeterravenousdelusional.com,perkcanada.com,perkyabroad.pro,peugyiwmgvadr.space,p",
        "eukasrsihavele.org,pgammedia.com,pgmediaserve.com,pgzmbkdavgilh.online,phmtcfsfjtcpm.space,phrfushpadbco.websi",
        "te,phuakravmo.in,pilotstracking.com,pip-pip-pop.com,pipaffiliates.com,pitdjtfxcamyx.site,pityneedsdads.com,pix",
        "ellitomedia.com,pjyibsavpisod.space,plajwaduivmqg.space,planetwin365affiliate.com,playrainbet.com,pleasedmad.c",
        "om,pleasetrack.com,plenomedia.com,pllsadrp.net,plowmenepoptic.com,ployimmediately.com,plucheavennel.com,plunge",
        "flavour.com,pnspihrxaduyk.site,poavoabe.net,poetizesuaver.cfd,pointclicktrack.com,polishleadennineteen.com,pol",
        "isnetwork.io,polluxnetwork.com,polyad.net,poorlyadjustvisual.com,pop5sjhspear.com,popads.media,popads.net,popa",
        "nder.com,popblockergold.com,popblockergold.info,popbounty.com,popbutler.com,popcash.net,popcornvod.com,popcpm.",
        "com,popdemission.com,popedomwresterfingian.qpon,poperblocker.com,popexhale.com,popgoldblocker.info,pophandler.",
        "net,popiemarsala.cfd,popinacelektrameered.cyou,popjanuary.shop,poplardealtcarrying.com,poplartenth.com,popmans",
        "ion.com,popmog.com,popmonetizer.com,popmonetizer.net,popmyads.com,poppastabour.com,popped.biz,poppyrimpace.com",
        ",poppysol.com,poprtb.com,popsads.net,popteen.pro,popular-tea.com,popularslant.com,populationeverydaygale.com,p",
        "opulumframmit.com,popunder.bid,popunderjs.com,popunderoctober.store,popunderstar.com,popunderz.com,popupblocke",
        "rgold.com,popupblockermax.app,popupclick.ru,popupdomination.com,popupgoldblocker.net,popupsblocker.org,popviki",
        "ng.com,popxperts.com,popxyz.com,porchadvertise.com,porn-star.com,porn.com,porn.fr,porn300.com,porn99.net,porna",
        "lin.com,pornattitude.com,pornav.net,pornconversions.com,porndeals.com,porndig.com,porndoe.com,porndroids.com,p",
        "orndude.link,pornearn.com,pornerbros.com,pornforrelax.com,porngals4.com,porngames.adult,porngames.tv,porngames",
        "pass.com,pornglee.com,porngo.tube,porngray.com,pornhat.com,pornhb.me,pornicom.com,pornid.xxx,pornimg.xyz,porni",
        "ntellect.com,pornj.com,pornjam.com,pornjv.com,pornkings.com,pornktube.tv,pornleep.com,pornmix.org,porno666.com",
        ",pornoegg.com,pornogramxxx.com,pornohans.net,pornoheat.com,pornone.com,pornpapa.com,pornpics.com,pornpics.de,p",
        "ornpoppy.com,pornrabbit.com,pornsex.rocks,pornstargold.com,porntez.com,porntrack.com,porntry.com,pornv.xxx,por",
        "nve.com,pornvids.fr,pornxbox.com,pornxp.com,pornxp.net,pornyhd.com,positivenetwork.pro,postaffiliatepro.com,po",
        "werad.ai,poweradblocker.com,powferads.com,ppuqlcyadikqe.website,pqnnvegqkyadt.space,preciousadequate.com,preci",
        "sionclick.com,preconyklavernbombax.cfd,predestinationicebergpredicament.com,predictivadnetwork.com,predictivad",
        "vertising.com,predominantleadingsprout.com,prefixbesidesbetray.com,premiumads.net,presscllad.com,presselhaveda",
        "rtoic.cfd,prestigecasino.com,pretrackings.com,prettyroad.biz,prettytube.net,preventtributebroad.com,prickly-tr",
        "adition.com,pridehickintimidating.com,primaryads.com,prizeadultery.com,prmtracking.com,pro-adblocker.com,pro-a",
        "dvert.de,proadscdn.com,processedlizardadjoining.com,producedpigsforehead.com,profitablecpmgate.com,profitablec",
        "pmrate.com,profitablecpmratenetwork.com,profitabledisplaynetwork.com,profitablegatecpm.com,profitableratecpmne",
        "twork.com,profitabletrustednetwork.com,programmesheadstoneconsult.com,projectjav.com,promo-bc.com,promo-reklam",
        "a.ru,promo4partners.com,promobenef.com,promocionesweb.com,promokrot.com,promoteconditionerkeyboard.com,promoto",
        "ols.biz,promowebstar.com,propadsviews.com,propeller-tracking.com,propellerads.com,propellerads.tech,propellerc",
        "lick.com,propellerpops.com,propositionvowelladen.com,proprietordauntaddition.com,prospershave.com,protectioncl",
        "eave.com,proudradish.com,prplad.com,prplads.com,prpops.com,prtrackings.com,prwave.info,pryzmads.com,pseavibrsi",
        "jgx.site,psjadixqzliqg.online,psxzadkhsaevp.space,psychologylosttraveling.com,ptwebcams.com,pubadx.one,pubfutu",
        "re-ad.com,pubjzoxav.com,publicidad.net,publpush.com,pubmatic.com,puffnetwork.com,puinnjsexnlbo.space,pulpyads.",
        "com,pulseadnetwork.com,pulseonclick.com,pumavobis.cyou,pumpbead.com,punctuationbandycontradict.com,puporn.com,",
        "pureadexchange.com,purpleads.io,push-notifications.top,push-sdk.com,push-sdk.net,push-sense.com,push.house,pus",
        "h1000.com,push1000.top,push1001.com,push1005.com,pushabl.com,pushadvert.bid,pushaffiliate.net,pushatomic.com,p",
        "ushbizapi.com,pushcampaign.club,pushcentric.com,pushclk.com,pushdom.co,pushdomains.biz,pushelp.pro,pushflow.ne",
        "t,pushflow.org,pushimg.com,pushingtiling.com,pushinpage.com,pushking.net,pushlapush.com,pushmaster-in.xyz,push",
        "menews.com,pushmine.com,pushmobilenews.com,pushnami.com,pushnews.org,pushno.com,pushnotice.xyz,pushpong.net,pu",
        "shprofit.net,pushrase.com,pushserve.xyz,pushtorm.net,pushub.net,pushwelcome.com,pushy.ai,pushyimmaculateinesti",
        "mable.com,pvdnugjwyrkad.website,pvhlewexcpcqq.online,pvuznrtadztxu.space,pxlcrykadtsby.site,pxuxlswadjlfu.spac",
        "e,pydsexwwnwiuj.space,pzmbetafgealw.online,q1media.com,qaeadnlcctjvh.online,qbiavgzkhyqji.space,qbkofavetnrgr.",
        "site,qcxavdkqorwri.space,qdemrnocqeadj.site,qetsjadxfjtzq.online,qgxbluhsgad.com,qhbetzsrzzslg.site,qhngmav.co",
        "m,qjwmihzmadpsm.space,qkosavpevkjzc.site,qoclick.com,qoqqgkzavy.com,qpadmalav.in,qpnicavmjtytk.site,qpnippagdk",
        "adi.online,qqdrbmdbcavgm.space,qsgjcqiqhaiad.online,qtnobmxbgavob.space,quackgraduation.com,quadrinhoseroticos",
        ".net,qualiclicks.com,qualitydating.top,quanta-wave.com,quantserve.com,quantumadblocker.com,quantumads.com,quic",
        "kads.net,quickloadbinpack.click,quilladot.org,qunskbadgzcdx.site,quwtzyadstt.com,qwxolavxykodb.site,qxdownload",
        ".com,qxgloyadavqvf.space,qxpxrbettkskb.space,qyeavpkdevzzc.site,qyexjolwpv6wvgbet5wp.cfd,rabbetget.cfd,rabbitp",
        "orno.com,racebets.com,radarconsultation.com,radcffbpaqwyr.space,radiancelongitude.com,radiancerelaysacred.com,",
        "radiancethedevice.com,radiantack.com,radiantmedia.net,radiantspacecraftbell.com,radiantvalerelieve.com,radioda",
        "rn.com,radioonlinehd.net,radioreference.com,radiusfellowship.com,radiusix.net,radiusmarketing.com,ragfishclept",
        "avgases.cfd,raggedtwinheadmaster.com,rakutenadvertising.io,ramppopulateresponse.com,rapidadexchange.com,ratede",
        "addisk.com,rationalizeinadequatecomedian.com,rationalizeoverwhelmendeavour.com,raveigryeggfj.website,ravenfarm",
        ".tech,ravenousamuse.com,ravktufmpgula.site,ravm.tv,rbpksavddouil.store,rcamagqknhmx.com,rcygxjvmjadds.site,rdr",
        "bunaadhpyn.site,reactivebetting.com,readiong.net,readme.ru,readpeak.com,readserv.com,readycelebrationleaf.com,",
        "realcfadsblog.com,realclick.co.kr,realgfporn.com,realxavounow.com,reasonable-bet.com,recamnjggepku.site,receip",
        "tcadenceresurrection.com,recipeaddingavenue.com,recipientpopulationregret.com,reclaimvanillademure.com,recorde",
        "dheavinessexperience.com,rectadesc.cfd,recyclehorridleading.com,redads.com,rediads.com,redirect-ads.com,redpin",
        "eapplemedia.com,redtube.com,redtube.fm,reedsoutpop.com,refbanners.com,refbanners.website,referenceswarmerbread",
        ".com,regionads.ru,regionalavouchcreeping.com,regularseatradio.com,rehearsejuniorloaded.com,reliablebanners.com",
        ",rememberineffectiveadvisedly.com,remetalthecladayment.cfd,rencontreadultere.club,rentfavourablecashmere.com,r",
        "esacaduende.com,resentlocallycamomile.com,resentmentsadflew.com,resteddiabetepocket.com,restorepushed.com,reta",
        "ingravely.com,revealingplacingpopped.com,revenuecpmgate.com,revenuecpmnetwork.com,revenuenetwork.com,revenuene",
        "tworkcpm.com,revillusiveheavily.com,revimedia.com,revive-adserver.net,revopush.com,revupads.com,rewardsaffilia",
        "tes.com,rexadvert.xyz,rexpush.club,rfwmfadnncajl.site,rhinoaffiliates.com,rhombusads.com,rhubarbambassadorweep",
        ".com,richads.com,richwebmedia.com,rideradjective.com,ridgepilotavalanche.com,risingshadewhite.com,rivinadinomi",
        "c.qpon,rmqgscbitoadp.site,rmyhibtadohj.com,roadcontagion.com,roadwaspfang.com,robeheadlong.com,rocketmedia24.c",
        "om,rockmostbet.com,rocoads.com,rodesquad.com,rogersmedia.com,rokymedia.com,rollbackavenge.com,roobet.com,roobe",
        "taffiliates.com,rootzaffiliates.com,rotatingadult.com,roxyaffiliates.com,rpyztjadsbonh.store,rqerokcafoadv.spa",
        "ce,rtb-media.me,rtbpop.com,rtbreachads.com,rteomxavwvnol.site,rubanners.com,rubiconproject.com,rummyaffiliates",
        ".com,ruscams.com,ryscxxxelrsdw.space,rytadlqujhwsz.site,rzedmincpjcam.website,s-adzone.com,sabavision.com,sacz",
        "vfpaeadxv.space,sadbaguette.com,sadbasindinner.com,saduvqpcfykcj.online,sadvvowejqfet.site,safelinktracker.com",
        ",sagaciousexceptingrate.com,sagsajijqpavd.space,saladattic.com,salbraddrepilly.com,salvador24.com,samsungads.c",
        "om,sandracismaboveaverage.com,sandwichconscientiousroadside.com,saravanacyetic.qpon,satisfactorybroadformidabl",
        "e.com,savageavowmidland.com,savamea.org,save-editor.com,savefiles.com,saveit.us,saveterracehangnail.com,saveun",
        "stable.com,savinesyohimbe.cfd,savinist.com,saviourcrimpycrimpyhem.com,savourbiggergait.com,savttuegxztst.site,",
        "saymedia.com,sbaffiliates.com,sbicrwatvnavf.online,sbsbetlinfevn.site,scamblefeedman.com,scamforgottenbyte.com",
        ",scammerfail.com,scammerrecorder.com,scarlet-clicks.info,scatxxxporn.com,scholarshipexplosivepopular.com,sciou",
        "spavlovpundum.qpon,scorchads.com,scorecardresearch.com,scowpoppanasals.com,scqcigoladk.com,sdadarotfnhoy.websi",
        "te,sdk4push.com,sdomxqcaeuadm.site,sdtzadpayrgsc.space,seafoodclickwaited.com,seaofads.com,searchschoolsnetwor",
        "k.com,searingcavelet.rest,seatrackingdomain.com,secondhandbrave.pro,secretbehindporn.com,secthatlead.com,secur",
        "eaddisplay.com,secureleadsforever.com,secureleadsrn.com,sedatingnews.com,seizureblade.com,selectad.com,semperi",
        "nshave.rest,sendwebpush.com,sensible-ads.com,sensitiveclick.com,seriolavangloe.com,servedby-buysellads.com,ser",
        "vedbyadbutler.com,servemeads.com,server4ads.com,set-push.net,setravieso.com,setupad.com,setupad.net,sewboldlya",
        "vow.com,sex-and-flirt.com,sex-chat.me,sex-techniques-and-positions.com,sex3.com,sexad.net,sexcelebrity.net,sex",
        "clic.com,sexcooldating.com,sexdatecash.com,sexei.net,sexemulator.com,sexfamilysim.net,sexfg.com,sexfinder.com,",
        "sexflirtbook.com,sexgangsters.com,sexiba.com,sexier.com,sexintheuk.com,sexlist.com,sexmature.fun,sexmoney.com,",
        "sexmotors.com,sexmy.site,sexpin.net,sexplaycam.com,sexsearch.com,sexstories69.com,sextadate.net,sextf.com,sext",
        "racker.com,sextubebox.com,sextubepromo.com,sextubeweb.com,sextvx.com,sexuhot.com,sexvertise.com,sexvid.pro,sex",
        "vid.xxx,sexy-ch.com,sexycams.com,sexypornvideo.net,sf-ads.io,shadowybalance.com,shadysetting.pro,sharemedia.rs",
        ",sharktube.net,shauladubhe.com,shauladubhe.top,shaversoutjumphobnobs.cyou,shavesfoilermissus.cfd,shavingwitchc",
        "raft.com,shipmentdoorwaydecade.com,shoogloonetwork.com,shopmonthtravel.com,short-wave.info,showcasead.com,show",
        "meyouradsnow.com,shubadubadlskjfkf.com,siavtcwmgehxp.online,sibrdiarmuvav.site,sierrakonradreorganize.com,sift",
        "octaveuntimed.qpon,signaturewoundedgraduation.com,silesiadeuces.com,sillorangesladen.com,silverleafroad.com,si",
        "mple-affiliate.com,sinadolcoralla.cfd,sincerelydiscreetcomedian.com,sindonpavis.cyou,singlesexdates.com,sizedn",
        "avahoduncify.cyou,sjadkbbvcmdfd.website,sjobmfxcchbad.online,sjzieadynfqtl.online,skeetads.com,skidwaverorigin",
        ".com,skipvideoads.com,skirlsravager.com,skyadsmart.com,skymedia.co.uk,skynetworkcdn.com,slaveguna.cfd,slavesth",
        "y.com,slavingaint.com,slippersexamination.com,slthavjtoxjoi.site,smac-ad.com,smadex.com,smart1adserver.com,sma",
        "rtadserver.com,smartadtags.com,smartclick.net,smartcpatrack.com,smartyads.com,smashpops.com,smentbrads.info,sm",
        "wadnuydogrh.space,snack-media.com,snaeyjnavvojo.online,snathsextends.com,snbets.com,sneakyadministration.com,s",
        "nipersex.com,snlaavecjmpja.online,socalledpushedcollege.com,socialsex.biz,socialsex.com,socialsexnetwork.net,s",
        "ofinpushpile.com,softforestmeadow.com,softonicads.com,softpopads.com,soldierreproduceadmiration.com,solicitorv",
        "alvemade.com,solocpm.com,solutionsadultes.com,somehowluxuriousreader.com,sophiaaccoladewest.com,sorbetbungler.",
        "com,sordidsicknessfaded.com,sousefulhead.com,soxnhocmadklc.site,spadeharassrefusal.com,spaderonium.com,spadsyn",
        "c.com,spanpromo-link.com,sparrowaboveaveragedefence.com,sparta-tracking.xyz,special-promotions.online,specific",
        "media.com,speckencampment.com,speechcleopatraadmiral.com,speedingdeadlyplays.com,speedycomediandecember.com,sp",
        "hinxtube.com,spmediacdn.com,spokeoaffiliates.com,sponsorinserttimeout.com,sponsorpay.com,sponsorshipprickly.co",
        "m,sportradar.com,sportradarserving.com,spotidownloader.com,sppopups.com,spreadingsinew.com,spreadpreferencetel",
        "evision.com,sprintrade.com,spry-glad.pro,srvupads.com,srzwbetxwdkpt.online,sseadlhhrusrj.website,ssqcqaavrncxt",
        ".online,stackadapt.com,stagepopkek.com,staggeredravehospitality.com,star-advertising.com,star-clicks.com,star-",
        "theladyfinds.com,stargamesaffiliate.com,starmobmedia.com,startwebpromo.com,statcamp.net,statsforads.com,statur",
        "ebanner.com,stavizokhbhe.in,steadyglobe.com,steadyhelmet.com,sterilecontradiction.com,stickyadstv.com,stiffens",
        "have.com,stimtavy.net,stinkyloadeddoctor.com,stop-adblocker.info,stopads-now.com,stopadzblock.net,stopblockads",
        ".com,storader.com,storage-ad.com,store4porn.com,straightenedphiladelphiarequire.com,streamjav.top,stringroadwa",
        "y.com,stripsaver.com,striptube.net,studads.com,studiotrackmusic.com,sukaporn.com,sunglassesexpensive.com,sunme",
        "dia.tv,sunmediaads.com,sunporno.com,sunshineadditions.com,superadexchange.com,superonclick.com,superxxxfree.co",
        "m,supposedlymadeup.com,supremeadblocker.com,suptube.com,surpavdggpjar.site,surplusaltarmadness.com,swak-adguar",
        "d.pro,swarmpush.com,swatowbetokenganam.cfd,sweetmedia.org,swesomepop.com,swiftladle.com,swimmingnarcotictravel",
        ".com,swmlidloaetad.site,swwpush.com,sygvwadaxjesn.space,synsads.com,syrupimmediate.com,systemads.net,systemlea",
        "db.com,taadd.com,taboola.com,taboola.net,tacxemkinqtav.space,taddpxmmooi.com,tadodpfikom.in,tafdiywfnavss.site",
        ",tahrqfbavxmxf.site,takeads.com,tanagerpoppel.rest,tantoporno.com,tapingferocitymadeira.com,tapjoyads.com,taro",
        "ads.com,taversroomthy.com,taversserang.cyou,tavgvouyakozy.website,tavlomikyionr.website,tavryuhcshdsr.website,",
        "tcpcharms.com,teads-xo.com,teads.tv,teadumiccdoct.website,technologies2radar.net,technoratimedia.com,technosha",
        "dows.com,techporn.ph,tectrixsnavvle.com,teenporno.xxx,teensexgfs.com,tehseelaves.com,tejxthyxavhfb.site,tenera",
        "lavarice.cfd,termagewaving.com,terrapush.com,text-link-ads.com,tgtmedia.com,thavxaiwvxtja.website,the-adult-co",
        "mpany.com,the-binary-trader.biz,theadgateway.com,theadsparks.com,thebetterdealz.com,thecoreadv.com,thelotter-a",
        "ffiliates.com,themediaplanets.com,themselvespresentationfavourable.com,theplayadvisor.com,theporndude.com,thep",
        "ornstar.com,thesisadornpathetic.com,thesocialsexnetwork.com,thingsmadenavy.com,third-tracking.com,thorpeslav.c",
        "om,thoughtleadr.com,thrad.ai,thrads.ai,threadpassivetrespass.com,thriftytraveler.com,thrixxx.com,thtrsihwdznav",
        ".site,thunderhead.com,tiatmuadwuhwy.online,tidycamel.com,tiedhamadjoin.com,tihmjppaavrmz.site,tiladlolbtu.in,t",
        "iltgardenheadlight.com,tinyadz.com,titanads1.com,titanads2.com,titanads3.com,titanads4.com,tjcfeyegncavs.onlin",
        "e,tjesbxadovyot.site,tjiyavxmtihln.space,tkpopd.cfd,tkpopp.com,tktube.com,tlavideo.com,tlrzavbrosrsv.space,tlu",
        "cfqhtavxyx.space,tluihoeadocjs.store,tlvmedia.com,tnnfecqadqzck.online,toboads.com,toibjavyncxnc.site,tokelaun",
        "avet.cyou,tokenads.com,tomladvert.com,tomorrowlemonade.com,tonemedia.com,tonicheaviness.com,topadsservices.com",
        ",topadvert.ru,topbetfast.com,topclickguru.com,topcpmcreativeformat.com,topdealad.com,topdisplaynetwork.com,top",
        "profitablecpm.com,toprevenuecpmnetwork.com,topsexcams.club,topsexmovs.com,toptopleads.com,toroadvertising.com,",
        "tossoffads.com,totaladblock.com,totaladperformance.com,totalav.com,totymanglave.com,tpdads.com,tpmedia-reactad",
        "s.com,tpopfgvlqytlm.online,tprfavksbqzmr.online,tqqavzfshkdsh.website,track-campaing.club,track-victoriadates.",
        "com,track4ref.com,trackad.cz,trackcherry.com,trackcitg.com,trackclicks00.com,tracker-2.com,tracker-tds.info,tr",
        "ackerflw.com,trackerrr.com,tracki.click,tracking-aws-5.com,tracking-zy.com,trackingmembers.com,trackingtraffo.",
        "com,trackmundo.com,tracks20.com,tracksfaster.com,trackspeeder.com,trackstracker.com,tracktds.com,tracktilldeat",
        "h.club,trackuity.com,trackvoluum.com,trackwilltrk.com,trackzd.com,tradbypass.com,tradeadexchange.com,tradedoub",
        "ler.com,tradeexpert.net,tradehouse.media,tradepub.com,tradertimerz.media,tradetracker.net,traditionalboycottfl",
        "ung.com,traditionalmusic.co.uk,traditionblindness.com,tradplusad.com,traffic-media.co.uk,trafficad-biz.com,tra",
        "fficadbar.com,trafficmediaserver.com,trailerheavendirectness.com,trainedwave.pro,trapdoortissuepush.com,travel",
        "andtourworld.com,travelaudience.com,travellingcrushslit.com,travellingpresidentding.com,travelscream.com,trave",
        "lsplayboy.com,traveltop.org,traverseseven.com,travidia.com,tremormedia.com,trimreaddrunken.com,tripcarriagewav",
        "ing.com,tripsthorpelemonade.com,triumphantthingsreading.com,tronads.io,trustedcpmrevenue.com,trustphiladelphia",
        "compelling.com,ts-tracker.com,tsadcebakufrd.site,tsmodelstube.com,tspops.com,tsuccwavchpvh.website,tsztqlwravx",
        "kl.site,ttfwotjetavmk.website,ttzmedia.com,tube-sexs.com,tubeadnetwork.com,tubeadv.com,tubeadvertising.eu,tube",
        "corp.com,tubecorporate.com,tubecup.net,tubeelite.com,tubemov.com,tubeoffline.com,tubepork.com,tuberel.com,tube",
        "truck.com,tubeultra.com,tubev.sex,tubewolf.com,tubexazimebeze.com,tubexo.tv,turboadv.com,turnstileunavailables",
        "ite.com,tvewiifmavniu.website,twads.gg,tweakpersuadelowest.com,twinpinenetwork.com,twiselaveria.com,twistads.c",
        "om,twittad.com,txgtadnmmwwte.space,txxx.com,typhulaverging.cfd,uadllyad.com,uads.cc,uaeapcmjbmzad.space,uaqiwa",
        "dxmsniz.online,uavefhtrbkbhq.online,uavnfdezxzote.site,ubercpm.com,ubgvqakukavmm.space,ublockpop.com,ucaavjcwe",
        "klhn.space,ucnwhhtaadaqp.site,udsqbadxjvzanh.com,ueleensadcyxh.site,ufauolqhhcpmd.space,ufidxaduyqqkq.site,ufi",
        "rmirylfavb.online,ugadceetawiae.site,ugazuogyeeadt.online,uhuvadkubxjkv.website,uitopadxdy.com,uiuhawavotxsv.s",
        "pace,ukaadmlcfluko.site,ukaviayvjfydp.online,ukcampsite.co.uk,ulbruiqadlmaa.online,ulpradvpeaiqs.store,ultimed",
        "ia.com,umljflgaddcdk.space,unavoidablestates.com,uncadyaewiajd.online,uncededtravoy.com,uncertainadministratio",
        "n.com,underclick.ru,undergroundportugueseheadache.com,unibet.co.uk,unibet.com,unidentifiedscammertravel.com,un",
        "iversalads.com,universityroadsidecircumstantial.com,unloadonly.com,unripe-head.pro,unrulymedia.com,unsettledra",
        "diator.com,unshaveparaphs.com,untimely-promotion.com,unwavedmalarky.cyou,unweavederiv.com,uoutube.com,upbeatme",
        "adow.com,uploadcloud.pro,uploaded.net,uploadgig.com,upornia.com,uprivaladserver.net,uptopopunder.com,upush.co,",
        "upymgrtnacpco.site,urdatingassistant.com,urgayporn.com,urzdxiiugisad.site,usasexcams.com,usavmhnwphcmm.online,",
        "userimmediate.com,usesponsorarrange.com,ussmtrack.com,ust-ad.com,usuxodfefavbm.space,utavbvaervidi.site,utm-ca",
        "mpaign.com,utrocaadrboym.space,utteredadisappo.com,utubepwhml.com,uubvkadmkvnys.space,uuwjrmvavkgrs.site,uvvsy",
        "eadlyrgb.space,uwzwqadqkcjgw.site,uyqwpnspgadkq.site,uyriroadwtdwu.space,uywfpprhavyog.site,uzpuaveerqjei.site",
        ",v8eaezd.cc,vaguemad.com,valemedia.net,validclick.com,valuad.cloud,valuead.com,valueclick.cc,valueclick.com,va",
        "lueclick.net,valueclickmedia.com,variabilityaviationmutiny.com,vatanclick.ir,vavuwetus.com,vazijiavfyebf.site,",
        "vcmedia.com,vdeztemladdeb.online,vdhvldghccavh.site,vdxsvladcihnm.online,venturead.com,verbsponsor.com,verdeca",
        "sino-offers.com,verdepromo.info,vertismedia.co.uk,vespymedia.com,vexilladuper.cyou,vfreecams.com,vhojdkvcadavb",
        ".space,viadata.store,viads.com,viads.net,vid-adblocker.com,vidcpm.com,video-adblocker.com,videoadstech.org,vid",
        "eocampaign.co,videosprofitnetwork.com,viewablemedia.net,viiadaof.com,viibslav.com,vikablavflhe.com,vikiporn.co",
        "m,villainous-track.pro,vipads.live,vipavkacrfujf.online,viptube.com,viradisazo.qpon,viralmediatech.com,virtued",
        "eadlock.com,visariomedia.com,viscidvareusebetitle.cfd,visiads.com,vjzlvloavqarr.space,vkgtrack.com,vkipfmadahr",
        "wh.site,vkoxgtewzadqi.site,vkyieskdlavjt.space,vlogerads.com,vlwqyhgnslfad.space,vmpdmfacpctto.space,vnakjojla",
        "zcpm.online,voeqzuuvnwpad.website,voirpfhavircs.online,votepercussivepoplar.com,vowsavageskim.com,vpvlxuooad.c",
        "om,vqkusfxadcdll.website,vradfvwfmwvaw.space,vrbcauaviehga.space,vrgbaevvadiwn.site,vrizead.com,vrstnadgljwiy.",
        "space,vrtzads.com,vsavbpsnxdrgt.online,vsexshop.ru,vtdoadqxkv.com,vtube.to,vucxfzsavoxdd.online,vuevgiavjbplr.",
        "space,vuiebktuavkcc.online,vulkanbet.me,vulnerableimmigrateaboveaverage.com,vvautxavycmwh.space,vvladxqdlpihl.",
        "online,vwzusobugmrad.space,vxxgxicadjtia.space,vxyqwespdaadz.site,w00tmedia.net,wadecarbohydrate.com,waexhoxdq",
        "igad.space,wafmedia6.com,wagsad.com,wailedshave.com,walkeralacrityfavorite.com,wangheebehav.com,wanlyavower.co",
        "m,warashi-asian-pornstars.fr,warilyclickframes.com,warmmeadowroad.com,washedadvance.com,washoffwaviercyanite.c",
        "fd,watchadsfree.com,watchcpm.com,wavecdn.de,wavedsuspect.click,waveetiquette.com,waveletiricize.cfd,wavermidda",
        "yboundary.com,waviesdaywork.cyou,wavingauradignity.com,wavtcdqwbuhiw.space,waxapushlite.com,wayanatomyunavaila",
        "ble.com,wayftertheyhadgo.org,wbamedia.com,wcgbetyplakno.website,wclpvybetahei.site,wdahqumfpnmav.space,wdczlav",
        "bweeby.online,wdxuugjqeavzv.space,wearadmiration.com,weaveradrenaline.com,weaversprinkle.com,weaveworkingheavi",
        "ness.com,web-adblocker.com,web3ads.net,webads.co.nz,webads.nl,webadserver.net,webbed-leadership.com,webcamprom",
        "o.com,webcampromotions.com,webcams.com,webcamtests.com,webclickengine.com,webclickmanager.com,webpushcloud.inf",
        "o,websitepromoserver.com,webtradehub.com,weekheadlong.com,weforads.com,wellmadesurvey.pro,wellworn-lead.com,we",
        "trackgames.com,wfbcfadgivxqd.space,wfiutburadoho.online,wfnetwork.com,wgfqayocqadzp.space,whaleads.com,wheebsa",
        "dree.com,wheegnehoad.net,wheelchairavoid.com,wheelchairbewilderedwaver.com,whirlclick.com,whoadhoxtojg.com,who",
        "pping-click.com,whorledebbets.cyou,whwdxzadairoq.website,widespreadcomponent.com,wifescamara.click,wigetmedia.",
        "com,williamhillcasino.com,windfallleavebrawl.com,winneradequateablebodied.com,winneradsmedia.com,wisjevyyjdjad",
        ".site,withdrawtrade.com,wktmzgeadgmje.com,wlkokwriavlw.com,wmmediacorp.com,wnt-s0me-push.net,wnvqeizwbpads.onl",
        "ine,woffxxx.com,womxwsnxadxtl.space,wonderadblock.com,wootmedia.net,workdeadlinededicate.com,worldsbestcams.co",
        "m,worried-advantage.com,worthylighteravert.com,wowgwmezvkuav.site,wpadmngr.com,wpiadbxpy.in,wpnetwork.eu,wpush",
        ".org,wpushorg.com,wpushsdk.com,wqbeavbpztwsm.website,wravogram.cfd,wretchedadvantage.com,wsknwakvkeiad.site,wt",
        "g-ads.com,wtpiylrmavdfp.space,wtqhxggxngfad.site,wtzqznypmadeo.store,wujtefbetb.com,wunsofiubphad.space,wwads.",
        "cn,wwwadcntr.com,wwwpromoter.com,wwwxxx.uno,wzkqcamv.com,xad.com,xadcentral.com,xadpagwro.com,xadsmart.com,xap",
        "ads.com,xavhiszxjspce.website,xavions.shop,xbcpcn.com,xbhcyadhm.com,xbhoylaadarbq.website,xcelsiusadserver.com",
        ",xcpmfvlfcdltd.online,xcrrdxmdadebs.site,xglvldsblpkad.online,xgwhfyaavzpyp.space,xhamstercams.com,xinbzybuosa",
        "dk.website,xjav.tube,xjoavfrckohwo.online,xjsgturadzcgo.online,xkavrcwjklaitf.com,xldadwsosaqiq.site,xlovecam.",
        "com,xmayktavadugm.site,xmladserver.com,xmtrading.com,xnxxporn.video,xnxxxnxx.site,xoaveubcbdpld.com,xogogowebc",
        "ams.com,xrff-adguard.pro,xrqcamarbnhxg.website,xslbuoadsfsrf.space,xsqltdwuccamf.space,xtendmedia.com,xuytfnca",
        "mofxt.site,xvidxxx.com,xwkvojadxcnyd.space,xxbifrkzadx.com,xxx-porn-tube.com,xxxbannerswap.com,xxxblackbook.co",
        "m,xxxbox.me,xxxbp.tv,xxxbule.com,xxxdessert.com,xxxex.com,xxxfetish24.com,xxxhd.icu,xxxiijmp.com,xxxijmp.com,x",
        "xxivjmp.com,xxxmatch.com,xxxoh.com,xxxpornhd.pro,xxxshake.com,xxxviiijmp.com,xxxviijmp.com,xxxvijmp.com,xxxvjm",
        "p.com,xxxvogue.net,xxxwap.pro,xxxwebtraffic.com,xxxxsx.com,xxxzbkhpjxzry.store,yadmarmizuogr.website,yads.tech",
        ",yadsunkzjtzlb.space,yavecqiztteba.site,yavli.com,yavsmataemray.online,yavurxepklnzm.space,yazatadoolysups.cfd",
        ",ybb-network.com,ybcnevrcnyadv.site,ydjadyzuepuvf.site,ydwekhadydlnz.space,yekavcxlkbejc.online,yesporn.cam,ye",
        "spornpleasexxx.com,yetterslave.com,yeyswxtehnave.site,yieldads.com,yieldlove-ad-serving.net,yjjvzmruicyav.onli",
        "ne,ykdqqskuziadx.site,ykveifvdzadrl.online,yoads.net,yoc-adserver.com,yogadeprive.com,yopuademtfkwb.space,you-",
        "porn.com,youhavequited.com,youlamedia.com,youporn.com,youradexchange.com,yourquickads.com,youtube.com,yowiespo",
        "pian.com,yoxfjudadp.com,yoyadsdom.com,ypcjgbavpkray.site,yporn.tv,ypztdrxavedah.space,yqepkjcpcxnay.space,yqop",
        "mxeyoaveu.online,yrrnadvdbvdow.space,yuintbradshed.com,yukclick.me,yumenetworks.com,yummyadvertiseexploded.com",
        ",yuppads.com,yuppyads.com,yurekbkmhoadl.net,yuwseujcjavak.space,yvmads.com,ywiicadcuvilo.online,yx-ads6.com,yx",
        "aasylsdjado.site,yxrlvfirqiave.website,yyecdiehcvadf.site,yyxnoegkqkavu.space,zadlclqnequke.site,zaimads.com,z",
        "anox-affiliate.de,zarpop.com,zavcmzahutmqo.site,zavirand.com,zavnnfijuylnu.site,zavunmknjlpwl.space,zavylwpyui",
        "jzb.website,zbkrmwrzftiav.site,zbporn.com,zcbetcczfvylq.site,zcfpfbavrffxm.site,zeads.com,zealshaving.com,zebr",
        "acave.com,zedporn.com,zenomedia.com,zerads.com,zesty-lead.pro,zetadeo.com,zeusadx.com,zextzccadpr.com,zfctrack",
        ".net,zgaxadxsycppa.website,zgijtonvzgadk.online,zhyhadnrqzpcd.site,ziffdavis.com,zimoccaengrave.shop,zimvlqwrt",
        "admi.space,zipcrypticbroadsheet.com,zixxsovtavprk.online,zkdwtdzlcadfu.site,zkvsavwzcbfaj.online,zkxweadue.com",
        ",zlavxbjuyekwo.site,zlzwhrhkavos.xyz,zotqhsyqbetkg.online,zoxlzvrqeqadk.site,zplxavnqigfrp.site,zpuavdcswqnml.",
        "online,zpxsadurflhs.com,zrbezdaddiahk.space,zrdubetejajen.website,zrygvvxyadvro.site,zsfbadlhjmpow.site,zsnweu",
        "iavdmzs.online,zteeavqibjsgj.space,ztrack.online,zumidxxxwcjob.site,zunoptjciavj.in,zuzaghbkajav.in,zvwjrwav.c",
        "om,zvzpopazdasxh.website,zwkfzyjtadeuz.space,zxavziahazmyb.online,zyadpzcdswdre.com,zypenetwork.com,zytfycpmnl",
        "zio.online,eab.elbphzgw.cc,demjpgtf.zvpxyyql.cc,api2.jpfviwgkz.cc,xxycw341.com,vnhgr465.com,caow.us,583.cc,952",
        "1.com,2726.com,8319.com,9212.app,322.cc,384.tv,4477.tv,177.gg,5651.com,275.cc,391.cc,837.tv,1x4g6f2.com,aizhan",
        "tj.com,0cy5aps.com,ax8rylb.com,7xkpxuw.com,4gl3g.z.com,mehqk5u.com,smun7ro.com,67x8epk.com,kvkluj8.com,lw1su4n",
        ".com,hcxftcl.com,nf65hwr.com,3dzmgtc.com,dkepyys.com,hv7hpkk.com,tq.qwehkg.top,ta.dqgjla.top,tz.erghsasq.top\".",
        "split(','));function isAdDomain(h){if(!h)return false;h=h.toLowerCase();if(__museAdSet.has(h))return true;var ",
        "i=h.indexOf('.');while(i>=0){h=h.slice(i+1);if(__museAdSet.has(h))return true;i=h.indexOf('.');}return false;}",
        "function isAdUrl(u){try{var x=new URL(u,location.href);return !!x.hostname&&isAdDomain(x.hostname);}catch(e){r",
        "eturn false;}}function isContent(el){if(!el||!el.querySelector)return false;if(el.querySelector('a[href*=\"/vod",
        "detail/\"],a[href*=\"/vodplay/\"],a[href*=\"/video/\"],a[href*=\"/player/\"],a[href*=\"/v5/\"]'))return true;if(el.quer",
        "ySelector('#go_btn,#time_badge,#read_zone'))return true;return false;}function foreign(u){try{var x=new URL(u,",
        "location.href);return !!x.hostname&&x.hostname!==host;}catch(e){return false;}}function isPlaceholderHref(h){i",
        "f(!h)return true;h=String(h).trim().toLowerCase();return h===''||h.charAt(0)==='#'||h.indexOf('javascript:')==",
        "=0;}function inSameHostLink(el){var a=el&&el.closest?el.closest('a'):null;return !!(a&&a.href&&!foreign(a.href",
        "));}function isOpaqueImg(s){return s&&(s.indexOf('blob:')===0||s.indexOf('data:')===0);}function txt(el){retur",
        "n (el.innerText||'').replace(/\\s+/g,'');}function ageIdc(el){return ((el.id||'')+' '+((el.className&&el.classN",
        "ame.baseVal!==undefined)?el.className.baseVal:(el.className||''))).toLowerCase();}function isAgeGate(el){if(!e",
        "l)return false;if(/agedisclaimer|age[-_]?verif|age[-_]?verification|age[-_]?gate|age[-_]?check|over18|under18|",
        "age[-_]?confirm/.test(ageIdc(el)))return true;var t=(el.innerText||'');if(/这是个成人网站|我年满\\s*18\\s*岁|i\\s*am\\s*18|i'",
        "m\\s*18|verify your age|age verification/i.test(t))return true;if(t.length<=120&&t.indexOf('18')>=0&&/(成人|年满|\\b",
        "age\\b|adult|over 18|under 18)/i.test(t))return true;return false;}\nfunction isChallenge(el){if(!el)return fals",
        "e;if(el.tagName==='IFRAME'){var s0=el.src||(el.getAttribute?el.getAttribute('src'):'')||'';if(s0&&(s0.indexOf(",
        "'cloudflare')>=0||s0.indexOf('hcaptcha')>=0||s0.indexOf('recaptcha')>=0))return true;return false;}if(!el.quer",
        "ySelectorAll)return false;var frs=el.querySelectorAll('iframe');for(var i=0;i<frs.length;i++){var f=frs[i].src",
        "||(frs[i].getAttribute?frs[i].getAttribute('src'):'')||'';if(f&&(f.indexOf('cloudflare')>=0||f.indexOf('hcaptc",
        "ha')>=0||f.indexOf('recaptcha')>=0))return true;}return false;}function inAgeGate(el){if(!el)return false;if(i",
        "sAgeGate(el))return true;var n=el.parentElement,p=0;while(n&&p<6){if(/agedisclaimer|age[-_]?verif|age[-_]?veri",
        "fication|age[-_]?gate|age[-_]?check|over18|under18|age[-_]?confirm/.test(ageIdc(n)))return true;n=n.parentElem",
        "ent;p++;}return false;}function kill(el){if(el&&el.parentNode)el.parentNode.removeChild(el);}function collapse",
        "(el){var p=el,i=0;while(p&&i<3&&p!==document.body){if(txt(p).length===0&&!p.querySelector('img,video,iframe,fo",
        "rm,input,button,select,textarea')){var n=p.parentElement;kill(p);p=n;i++;}else break;}}function killLoaderPadd",
        "ing(){var ss=document.querySelectorAll('style');for(var i=0;i<ss.length;i++){var st=ss[i];var id=st.id||'';if(",
        "id.slice(-9)!=='_style_id')continue;var t=(st.textContent||'').replace(/\\s/g,'');if(t.indexOf('padding-bottom:",
        "100px')>=0&&st.parentNode)st.parentNode.removeChild(st);}}function mainLink(card){var as=card.querySelectorAll",
        "('a[href]');for(var i=0;i<as.length;i++){var a=as[i];if(isPlaceholderHref(a.getAttribute('href')))continue;if(",
        "txt(a).length>=4)return a;}return as.length?as[0]:null;}\nfunction hideMixedForeignCards(){var lists=document.q",
        "uerySelectorAll('ul,ol');for(var li=0;li<lists.length;li++){var list=lists[li];if(!list.isConnected)continue;i",
        "f(list.closest('nav,header,footer'))continue;if(isAgeGate(list))continue;var cards=[];var kids=list.children;f",
        "or(var k=0;k<kids.length;k++){var c=kids[k];if(c.nodeType!==1)continue;if(!c.querySelector('a[href]'))continue",
        ";cards.push(c);}if(cards.length<8)continue;var same=0,total=0;for(var m=0;m<cards.length;m++){var a=mainLink(c",
        "ards[m]);if(!a)continue;var href=a.getAttribute('href')||'';if(isPlaceholderHref(href))continue;total++;if(!fo",
        "reign(href))same++;}if(total<8)continue;if(same<=total*0.5)continue;for(var n=0;n<cards.length;n++){var a2=mai",
        "nLink(cards[n]);if(!a2||!a2.isConnected)continue;var h2=a2.getAttribute('href')||'';if(!h2)continue;if(foreign",
        "(h2))kill(cards[n]);}}}\nfunction hideNoidDiv(){var n=document.getElementById('noiddiv');if(!n||!n.isConnected|",
        "|n===document.body)return;if(n.querySelector&&n.querySelector('video'))return;n.style.display='none';}\nfunctio",
        "n hideHeadingPromoLinks(){var hs=document.querySelectorAll('h1 a[href],h2 a[href],h3 a[href],h4 a[href],h5 a[h",
        "ref],h6 a[href]');for(var i=0;i<hs.length;i++){var a=hs[i];if(!a.isConnected)continue;if(inAgeGate(a))continue",
        ";var href=a.getAttribute('href')||'';if(!href)continue;if(foreign(href))a.style.display='none';}}\nfunction not",
        "eHide(rule,el){try{if(!window.__museHideLog)window.__museHideLog=[];if(window.__museHideLog.length<20){var cn=",
        "(el.className&&el.className.baseVal!==undefined)?el.className.baseVal:(el.className||'');window.__museHideLog.",
        "push(rule+':'+(el.tagName||'')+'.'+String(cn).slice(0,30));}}catch(e){}}\nfunction sweep(){killLoaderPadding();",
        "killPreRoll();pruneExtraVideos();var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var ",
        "a=as[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;if(!isAdUrl(a.href))continue;var rel=(a.getAttribu",
        "te('rel')||'').toLowerCase();var spon=rel.indexOf('sponsored')>=0;var hasImg=!!a.querySelector('img');var hasB",
        "g=false;if(!spon){var dv2=a.querySelectorAll('div');for(var q=0;q<dv2.length;q++){var de=dv2[q];if(de.getAttri",
        "bute('x-bg-loader-url')||de.getAttribute('data-bg')){hasBg=true;break;}var dc=getComputedStyle(de);if((dc.back",
        "groundImage||'').indexOf('url(')>=0){hasBg=true;break;}}}if(!spon&&!hasImg&&!hasBg)continue;if(!spon&&hasImg&&",
        "!hasBg&&txt(a).length>8)continue;var p=a.parentElement;kill(a);collapse(p);}var bas=document.querySelectorAll(",
        "'a[href]');for(var i=0;i<bas.length;i++){var a=bas[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;if(i",
        "sAdUrl(a.href))continue;if(isBannerAd(a)){var p=a.parentElement;kill(a);collapse(p);}}var imgs=document.queryS",
        "electorAll('img');for(var j=0;j<imgs.length;j++){var im=imgs[j];if(!im.isConnected)continue;if(inAgeGate(im))c",
        "ontinue;if(im.closest('a'))continue;var s=im.currentSrc||im.src||im.getAttribute('data-original')||'';if(s&&is",
        "AdUrl(s)){var q=im.parentElement;var w2=im.parentElement,hid2=false;while(w2&&w2!==document.body){var csw2=get",
        "ComputedStyle(w2);if(csw2.position==='fixed'||csw2.position==='absolute'){var rw2=w2.getBoundingClientRect();i",
        "f(rw2.width>=window.innerWidth*0.6&&rw2.height>=window.innerHeight*0.35){w2.style.display='none';hid2=true;}el",
        "se if(rw2.width>=window.innerWidth*0.4&&rw2.bottom>=window.innerHeight-2&&rw2.top>=window.innerHeight*0.5&&rw2",
        ".height>=60){w2.style.display='none';hid2=true;}break;}w2=w2.parentElement;}if(!hid2){kill(im);collapse(q);}co",
        "ntinue;}if(s&&isOpaqueImg(s)){if(im.__museLb)continue;if(recentTap()&&window.__museTapImg){im.__museLb=1;conti",
        "nue;}var cs2=getComputedStyle(im);if(cs2.position==='fixed'||cs2.position==='absolute'){var r2=im.getBoundingC",
        "lientRect();if(r2.width>=window.innerWidth*0.4||r2.height>=window.innerHeight*0.5){var w=im.parentElement,hid=",
        "false;while(w&&w!==document.body){var csw=getComputedStyle(w);if(csw.position==='fixed'||csw.position==='absol",
        "ute'){var rw=w.getBoundingClientRect();if(rw.width>=window.innerWidth*0.4||rw.height>=window.innerHeight*0.5){",
        "w.style.display='none';hid=true;}break;}w=w.parentElement;}if(!hid)kill(im);}}}}var dvs=document.querySelector",
        "All('div');for(var k=0;k<dvs.length;k++){var d=dvs[k];if(!d.isConnected)continue;if(inAgeGate(d))continue;if(i",
        "nSameHostLink(d))continue;if(isContent(d))continue;if(d.querySelector&&d.querySelector('video'))continue;if(is",
        "ClickTrap(d)){kill(d);continue;}if(isModal(d)||isOpaqueModal(d)||isBigOverlay(d))d.style.display='none';}var a",
        "ll=document.querySelectorAll('div,section,aside,ins,iframe');for(var m=0;m<all.length;m++){var el=all[m];if(!e",
        "l.isConnected)continue;if(inAgeGate(el))continue;if(isChallenge(el))continue;if(inSameHostLink(el))continue;if",
        "(isContent(el))continue;if(el.querySelector&&el.querySelector('video'))continue;var hr=null;if(isAdIframe(el))",
        "hr='isAdIframe';else if(isFixedBottomIframe(el))hr='isFixedBottomIframe';else if(isBottomBar(el))hr='isBottomB",
        "ar';else if(isBottomAd(el))hr='isBottomAd';else if(isBottomFlowAd(el))hr='isBottomFlowAd';else if(isFloatAd(el",
        "))hr='isFloatAd';else if(isAdLinkGrid(el))hr='isAdLinkGrid';else if(isFixedBottomAd(el))hr='isFixedBottomAd';e",
        "lse if(isGenericBottomBanner(el))hr='isGenericBottomBanner';else if(isFixedBottomImageAd(el))hr='isFixedBottom",
        "ImageAd';else if(isGenericMidBanner(el))hr='isGenericMidBanner';else if(isFixedTopBanner(el))hr='isFixedTopBan",
        "ner';else if(isBottomPornAd(el))hr='isBottomPornAd';if(hr){noteHide(hr,el);el.style.display='none';}}var links",
        "=document.querySelectorAll('a[href]');for(var q=0;q<links.length;q++){var lk=links[q];if(!lk.isConnected)conti",
        "nue;if(inAgeGate(lk))continue;if(isAdImageLink(lk)||isBottomPornAd(lk)){lk.style.display='none';continue;}if(f",
        "oreign(lk.href)||isAdUrl(lk.href)){var fbr=fixedBottomAdRoot(lk);if(fbr){noteHide('isFixedBottomAd',fbr);fbr.s",
        "tyle.display='none';}}}var cbs=document.querySelectorAll('[class*=\"close\"],[id*=\"close\"]');for(var n=0;n<cbs.l",
        "ength;n++){var cb=cbs[n];if(!isAdClose((cb.className&&cb.className.baseVal!==undefined?cb.className.baseVal:cb",
        ".className||'')+' '+(cb.id||'')))continue;var slot=cb.closest('div,section,aside,ins');if(slot&&slot!==documen",
        "t.body&&!slot.querySelector('video'))slot.style.display='none';}hideMixedForeignCards();hideNoidDiv();hideHead",
        "ingPromoLinks();}function isNavMenu(el){if(!el||!el.querySelectorAll)return false;if(el.tagName==='NAV')return",
        " true;if(el.closest){try{var nv=el.closest('nav,[role=\"navigation\"]');if(nv)return true;}catch(e){}}var as=el.",
        "querySelectorAll('a[href]');if(as.length<3)return false;var same=0;for(var i=0;i<as.length;i++){if(!foreign(as",
        "[i].href))same++;}return same>=3&&same/as.length>0.6;}\nfunction isModal(el){if(!el||el===document.body)return ",
        "false;if(isAgeGate(el))return false;if(isNavMenu(el))return false;if(el.querySelector('video,iframe'))return f",
        "alse;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')return false;var r=el.getB",
        "oundingClientRect();if(r.width<window.innerWidth*0.6||r.height<window.innerHeight*0.35)return false;var as=el.",
        "querySelectorAll('a[href]');for(var i=0;i<as.length;i++){if(isAdUrl(as[i].href))return true;}var ims=el.queryS",
        "electorAll('img');for(var j=0;j<ims.length;j++){var s=ims[j].currentSrc||ims[j].src||'';if(s&&isAdUrl(s))retur",
        "n true;}return false;}function isBottomBar(el){if(!el||el===document.body)return false;if(el.querySelector('.d",
        "player'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='sticky'&&cs.positio",
        "n!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(",
        "r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;var as=el.querySelecto",
        "rAll('a[href]');for(var i=0;i<as.length;i++){if(isAdUrl(as[i].href))return true;}var ims=el.querySelectorAll('",
        "img');for(var j=0;j<ims.length;j++){var s=ims[j].currentSrc||ims[j].src||'';if(s&&isAdUrl(s))return true;}var ",
        "frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&isAdUrl(f))return t",
        "rue;}if(el.tagName==='IFRAME'){var s2=el.src||'';if(s2&&isAdUrl(s2))return true;}return false;}function isBott",
        "omAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(el.querySelector('.dplayer')",
        ")return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='sticky'&&cs.position!=='abs",
        "olute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom",
        "<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60&&r.height<windo",
        "w.innerHeight*0.08)return false;if(el.tagName==='IFRAME')return true;var im=el.querySelector('img');var vd=el.",
        "querySelector('video');var bg=cs.backgroundImage||'';var bgF=false;if(bg.indexOf('url(')>=0){var bm=bg.match(/",
        "url\\(['\"]?([^'\")]+)['\"]?\\)/);if(bm&&bm[1]&&isAdUrl(bm[1]))bgF=true;}var fl=false;var fas=el.querySelectorAll('",
        "a[href]');for(var fi=0;fi<fas.length;fi++){if(isAdUrl(fas[fi].href)){fl=true;break;}}if(!im&&!vd&&!bgF&&!fl)re",
        "turn false;if(cs.position==='absolute'){var fa=false;var abs=el.querySelectorAll('a[href]');for(var ai=0;ai<ab",
        "s.length;ai++){if(isAdUrl(abs[ai].href)){fa=true;break;}}var fsrc='';if(im)fsrc=im.currentSrc||im.src||'';if(!",
        "vd&&!fa&&!(fsrc&&isAdUrl(fsrc)))return false;}return true;}function pruneExtraVideos(){var players=document.qu",
        "erySelectorAll('div.dplayer');for(var i=0;i<players.length;i++){var d=players[i];if(!d.isConnected)continue;va",
        "r vs=d.querySelectorAll('video');if(vs.length<2)continue;var keep=null;for(var k=0;k<vs.length;k++){if(!vs[k].",
        "classList.contains('ads_video')){keep=vs[k];break;}}if(!keep)keep=vs[0];for(var j=0;j<vs.length;j++){var av=vs",
        "[j];if(av===keep)continue;var wp=av.parentElement;if(av.parentNode)av.parentNode.removeChild(av);if(wp&&wp!==d",
        "&&wp.children.length===0&&wp.parentNode)wp.parentNode.removeChild(wp);}}}function isBottomFlowAd(el){if(!el||e",
        "l===document.body)return false;if(isAgeGate(el))return false;if(el.querySelector('video,iframe'))return false;",
        "var cs=getComputedStyle(el);if(cs.position==='fixed'||cs.position==='sticky')return false;var r=el.getBounding",
        "ClientRect();if(r.width>=window.innerWidth*0.9&&r.height>=window.innerHeight*0.9)return false;var _ims0=el.que",
        "rySelectorAll('img');if(_ims0.length>=10)return false;var _cx=window.innerWidth/2,_cy=window.innerHeight/2;if(",
        "r.left<=_cx&&_cx<=r.right&&r.top<=_cy&&_cy<=r.bottom)return false;var docBot=r.top+(window.scrollY||0)+r.heigh",
        "t;var dh=document.documentElement.scrollHeight||0;if(docBot<dh-400)return false;if(r.width<window.innerWidth*0",
        ".5||r.height<80)return false;var cn=((el.className&&el.className.baseVal!==undefined)?el.className.baseVal:(el",
        ".className||''))+' '+(el.id||'');if(/footer|copyright/i.test(cn))return false;var ims=el.querySelectorAll('img",
        "');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';if(isOpaqueImg(s))return true;}var as",
        "=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){var a=as[j];if(isAdUrl(a.href)&&a.querySelector('",
        "img'))return true;}for(var j2=0;j2<as.length;j2++){var a2=as[j2];if(foreign(a2.href)){var im2=a2.querySelector",
        "('img');if(im2)return true;}}var fbg=cs.backgroundImage||'';if(fbg.indexOf('url(')>=0){var fbm=fbg.match(/url\\",
        "(['\"]?([^'\")]+)['\"]?\\)/);if(fbm&&fbm[1]&&isAdUrl(fbm[1]))return true;}return false;}function isBottomPornAd(el",
        "){if(!el||el===document.body)return false;if(inAgeGate(el))return false;if(isContent(el))return false;var cs=g",
        "etComputedStyle(el);var r=el.getBoundingClientRect();var isFixed=cs.position==='fixed'||cs.position==='sticky'",
        "||cs.position==='absolute';if(isFixed){if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeig",
        "ht*0.5)return false;}else{if(r.top<window.innerHeight*0.7)return false;}if(r.width<window.innerWidth*0.4)retur",
        "n false;if(r.height<40||r.height>300)return false;var t=(el.innerText||'');if(!/(约炮|兼职上门|国产自拍|性感少妇|裸聊)/.test(t",
        "))return false;if(el.querySelector('img,video'))return true;var bg=cs.backgroundImage||'';if(bg.indexOf('url('",
        ")>=0)return true;var kids=el.querySelectorAll('*');for(var k=0;k<kids.length;k++){var kb=getComputedStyle(kids",
        "[k]).backgroundImage||'';if(kb.indexOf('url(')>=0)return true;}return false;}\nfunction isAdImageLink(el){if(!e",
        "l||el.tagName!=='A')return false;if(inAgeGate(el))return false;if(isContent(el))return false;var href=el.getAt",
        "tribute('href')||'';if(!href)return false;if(!foreign(href)&&!isAdUrl(href))return false;var txt=(el.innerText",
        "||'').trim();if(txt.length>8)return false;var cs=getComputedStyle(el);var bg=cs.backgroundImage||'';if(bg.inde",
        "xOf('url(')>=0)return true;var im=el.querySelector('img');if(im){var ir=im.getBoundingClientRect();if(ir.width",
        ">=100&&ir.height>=60)return true;var src=im.currentSrc||im.src||'';if(src&&(foreign(src)||isAdUrl(src)))return",
        " true;}return false;}\nfunction isAdIframe(el){if(!el||el.tagName!=='IFRAME')return false;if(isAgeGate(el))retu",
        "rn false;if(inAgeGate(el))return false;var src=el.src||el.getAttribute('src')||'';if(!src)return false;var h='",
        "';try{h=new URL(src,location.href).hostname.toLowerCase();}catch(e){}if(h.indexOf('cloudflare')>=0||h.indexOf(",
        "'hcaptcha')>=0||h.indexOf('recaptcha')>=0)return false;var r=el.getBoundingClientRect();if(r.width<100||r.heig",
        "ht<40)return false;var ar=r.width/Math.max(1,r.height);if(r.width>=300&&r.height>=150&&ar>1.5&&ar<1.9)return f",
        "alse;if(el.hasAttribute&&el.hasAttribute('allowfullscreen')&&r.width>=300&&r.height>=150)return false;if(isAdU",
        "rl(src))return true;if(ar>=3)return true;if(!foreign(src)&&!isAdUrl(src))return false;if(ar>=1.0&&ar<=1.4&&r.w",
        "idth>=250&&r.height>=200)return true;return false;}function isFixedBottomIframe(el){if(!el||el.tagName!=='IFRA",
        "ME')return false;if(isAgeGate(el))return false;if(isContent(el))return false;var cs=getComputedStyle(el);if(cs",
        ".position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')return false;var r=el.getBoundingClientR",
        "ect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<win",
        "dow.innerHeight*0.5)return false;if(r.height<60||r.height>250)return false;var ar=r.width/Math.max(1,r.height)",
        ";if(r.width>=300&&r.height>=150&&ar>1.5&&ar<1.9)return false;if(el.hasAttribute&&el.hasAttribute('allowfullscr",
        "een')&&r.width>=300&&r.height>=150)return false;var src=el.src||el.getAttribute('src')||'';var h='';try{h=new ",
        "URL(src,location.href).hostname.toLowerCase();}catch(e){}if(h.indexOf('cloudflare')>=0||h.indexOf('hcaptcha')>",
        "=0||h.indexOf('recaptcha')>=0)return false;if(src&&(foreign(src)||isAdUrl(src)))return true;if(!src)return tru",
        "e;if(ar>=3)return true;return false;}\nfunction fixedBottomAdRoot(el){if(!el)return null;if(isAgeGate(el))retur",
        "n null;if(inAgeGate(el))return null;var cur=el,depth=0;while(cur&&cur!==document.body&&depth<4){if(isFixedBott",
        "omAd(cur))return cur;cur=cur.parentElement;depth++;}return null;}\nfunction isFixedBottomAd(el){if(!el||el===do",
        "cument.body)return false;if(isAgeGate(el))return false;if(isContent(el))return false;var cs=getComputedStyle(e",
        "l);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')return false;var r=el.getBoundin",
        "gClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r",
        ".top<window.innerHeight*0.5)return false;if(r.height<60||r.height>250)return false;var hasForeign=false;if(el.",
        "tagName==='IFRAME'){var fs=el.src||'';if(fs&&(foreign(fs)||isAdUrl(fs)))hasForeign=true;}var as=el.querySelect",
        "orAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j].href)){hasForeign=true;break;}}if(!hasForeign){",
        "var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';if(s&",
        "&(foreign(s)||isAdUrl(s))){hasForeign=true;break;}}}if(!hasForeign){var frs=el.querySelectorAll('iframe');for(",
        "var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&(foreign(f)||isAdUrl(f))){hasForeign=true;break;}}}if(!ha",
        "sForeign){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0){var bm=bg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/",
        ");if(bm&&bm[1]&&(foreign(bm[1])||isAdUrl(bm[1])))hasForeign=true;}}if(!hasForeign)return false;var hasVisual=f",
        "alse;if(el.tagName==='IFRAME')hasVisual=true;else if(el.querySelector('img'))hasVisual=true;if(!hasVisual){var",
        " bg2=cs.backgroundImage||'';if(bg2.indexOf('url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.querySelector",
        "All('*');for(var k2=0;k2<kids.length;k2++){var kb=getComputedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf",
        "('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el.querySelector('iframe'))hasVisual=true;if(!hasVisual)re",
        "turn false;return true;}\nfunction isFixedBottomImageAd(el){if(!el||el===document.body)return false;if(isAgeGat",
        "e(el))return false;if(isChallenge(el))return false;if(isContent(el))return false;if(isNavMenu(el))return false",
        ";if(el.querySelector&&el.querySelector('video'))return false;var cs=getComputedStyle(el);if(cs.position!=='fix",
        "ed'&&cs.position!=='absolute'&&cs.position!=='sticky')return false;var r=el.getBoundingClientRect();if(r.width",
        "<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*",
        "0.5)return false;if(r.height<60||r.height>250)return false;var hasVisual=false;if(el.tagName==='IMG'||el.tagNa",
        "me==='IFRAME')hasVisual=true;else if(el.querySelector&&el.querySelector('img,iframe'))hasVisual=true;if(!hasVi",
        "sual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.queryS",
        "electorAll('*');for(var k=0;k<kids.length;k++){var kb=getComputedStyle(kids[k]).backgroundImage||'';if(kb.inde",
        "xOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual)return false;return true;}\nfunction isGenericBottomBanne",
        "r(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if",
        "(isContent(el))return false;if(isNavMenu(el))return false;if(el.querySelector&&el.querySelector('video'))retur",
        "n false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky'",
        ")return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<windo",
        "w.innerHeight-60)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60||r.height>250)return",
        " false;var hasForeign=false;if(el.tagName==='IFRAME'){var fs=el.src||'';if(fs&&foreign(fs))hasForeign=true;}va",
        "r as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j].href)){hasForeign=true;break",
        ";}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||i",
        "ms[i].src||'';if(s&&foreign(s)){hasForeign=true;break;}}}if(!hasForeign){var frs=el.querySelectorAll('iframe')",
        ";for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&foreign(f)){hasForeign=true;break;}}}if(!hasForeign)",
        "return false;var hasVisual=false;if(el.tagName==='IFRAME')hasVisual=true;else if(el.querySelector('img'))hasVi",
        "sual=true;if(!hasVisual){var bg2=cs.backgroundImage||'';if(bg2.indexOf('url(')>=0)hasVisual=true;}if(!hasVisua",
        "l){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getComputedStyle(kids[k2]).backg",
        "roundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el.querySelector('iframe'))has",
        "Visual=true;if(!hasVisual)return false;return true;}\nfunction isGenericMidBanner(el){if(!el||el===document.bod",
        "y)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return false;if(",
        "isNavMenu(el))return false;if(el.querySelector&&el.querySelector('video'))return false;if(el.querySelector&&el",
        ".querySelector('iframe'))return false;var cs=getComputedStyle(el);if(cs.position==='fixed')return false;var r=",
        "el.getBoundingClientRect();if(r.width<window.innerWidth*0.8)return false;if(r.height<60||r.height>250)return f",
        "alse;var hasForeign=false;var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j].",
        "href)){hasForeign=true;break;}}if(!hasForeign)return false;var hasVisual=false;if(el.querySelector('img'))hasV",
        "isual=true;if(!hasVisual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)hasVisual=true;}if(!hasVisual",
        "){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getComputedStyle(kids[k2]).backgr",
        "oundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual)return false;return true;}\nfunct",
        "ion isFixedTopBanner(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(isChallenge(",
        "el))return false;if(isContent(el))return false;if(isNavMenu(el))return false;if(el.querySelector&&el.querySele",
        "ctor('video'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed')return false;var r=el.getBound",
        "ingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom>=window.innerHeight-60)return false;",
        "if(r.height<60||r.height>250)return false;var hasForeign=false;var as=el.querySelectorAll('a[href]');for(var j",
        "=0;j<as.length;j++){if(foreign(as[j].href)){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAl",
        "l('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';if(s&&foreign(s)){hasForeign=tr",
        "ue;break;}}}if(!hasForeign)return false;var hasVisual=false;if(el.querySelector('img'))hasVisual=true;if(!hasV",
        "isual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.query",
        "SelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getComputedStyle(kids[k2]).backgroundImage||'';if(kb",
        ".indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el.querySelector('iframe'))hasVisual=true;if(!hasV",
        "isual)return false;return true;}\nfunction isAdLinkGrid(el){if(!el||el===document.body)return false;if(isAgeGat",
        "e(el))return false;if(isContent(el))return false;if(el.querySelector('video,iframe'))return false;if(el.queryS",
        "elector('p'))return false;var as=el.querySelectorAll('a[href]');if(as.length<8)return false;var total=0,foreig",
        "nCount=0,shortText=0;for(var i=0;i<as.length;i++){var a=as[i];var href=a.getAttribute('href')||'';if(!href)con",
        "tinue;total++;if(foreign(href))foreignCount++;var t=txt(a);if(t.length>0&&t.length<=6)shortText++;}if(total<8)",
        "return false;if(foreignCount<total*0.7)return false;if(shortText<total*0.7)return false;return true;}\nfunction",
        " isBannerAd(a){if(!a||a===document.body)return false;if(inAgeGate(a))return false;if(isContent(a))return false",
        ";var href=a.getAttribute('href')||'';if(!href)return false;if(!foreign(href))return false;var im=a.querySelect",
        "or('img');if(!im)return false;if(txt(a).length>8)return false;var r=im.getBoundingClientRect();if(r.width<wind",
        "ow.innerWidth*0.4)return false;if(r.height<20)return false;return true;}\nfunction isFloatAd(el){if(!el||el===d",
        "ocument.body)return false;if(isAgeGate(el))return false;if(el.querySelector('.dplayer'))return false;var cs=ge",
        "tComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')return false;var r=el.getBoundingClientR",
        "ect();if(r.width>=window.innerWidth*0.6)return false;if(r.height>=window.innerHeight*0.4)return false;if(r.wid",
        "th<20||r.height<20)return false;if(r.bottom<window.innerHeight-120)return false;if(r.top<window.innerHeight*0.",
        "4)return false;var hasClose=false;var cbs=el.querySelectorAll('[class*=\"close\"],[id*=\"close\"]');if(cbs.length>",
        "0)hasClose=true;if(!hasClose){var t0=txt(el);if(t0.indexOf('\\u00d7')>=0||t0.indexOf('\\u2715')>=0||t0.indexOf('",
        "\\u2573')>=0)hasClose=true;}if(!hasClose)return false;var hasCarousel=false;var t=txt(el);if(/\\d+\\s*\\/\\s*\\d+/.t",
        "est(t))hasCarousel=true;if(!hasCarousel){var pg=el.querySelectorAll('[class*=\"pagination\"],[class*=\"swiper-pag",
        "ination\"],[class*=\"dot\"],[class*=\"dots\"],[class*=\"indicator\"]');if(pg.length>0)hasCarousel=true;}var hasForeig",
        "n=false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var h=as[i].href||'';if(h&&(foreign",
        "(h)||isAdUrl(h))){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var j=0;j<ims",
        ".length;j++){var s2=ims[j].currentSrc||ims[j].src||'';if(s2&&(foreign(s2)||isAdUrl(s2))){hasForeign=true;break",
        ";}}}if(!hasForeign&&el.tagName==='A'){var h0=el.href||'';if(h0&&(foreign(h0)||isAdUrl(h0)))hasForeign=true;}if",
        "(hasForeign||hasCarousel)return true;return false;}function isAdClose(cls){var t=(' '+cls+' ').toLowerCase().s",
        "plit(/[\\s_-]+/);var a=false,c=false;for(var i=0;i<t.length;i++){if(t[i]==='ad'||t[i]==='ads')a=true;if(t[i].in",
        "dexOf('close')>=0)c=true;}return a&&c;}window.__museLastTap=0;window.__museTapImg=false;document.addEventListe",
        "ner('click',function(e){window.__museLastTap=Date.now();var t=e?e.target:null;var ti=false;if(t&&t.closest){if",
        "(t.closest('img'))ti=true;else{var a=t.closest('a');if(a&&a.querySelector('img'))ti=true;}}window.__museTapImg",
        "=ti;},true);function recentTap(){return (Date.now()-(window.__museLastTap||0))<1500;}function isClickTrap(el){",
        "if(!el||el===document.body)return false;if(el.querySelector('video,iframe,img'))return false;if(txt(el).length",
        ">0)return false;var cs=getComputedStyle(el);if(cs.position!=='fixed')return false;var op=parseFloat(cs.opacity",
        ");if(!(op<0.05))return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.02&&r.height<wind",
        "ow.innerHeight*0.02)return false;return true;}function isOpaqueModal(el){if(!el||el===document.body)return fal",
        "se;if(el.__museLb)return false;if(isAgeGate(el))return false;if(isNavMenu(el))return false;if(el.querySelector",
        "('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')r",
        "eturn false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4&&r.height<window.innerHeight*0.5",
        ")return false;var ims=el.querySelectorAll('img');var hasOpaque=false;for(var i=0;i<ims.length;i++){var s=ims[i",
        "].currentSrc||ims[i].src||'';if(isOpaqueImg(s)){hasOpaque=true;break;}}if(!hasOpaque)return false;if(recentTap",
        "()&&window.__museTapImg){el.__museLb=1;return false;}return true;}function isBigOverlay(el){if(!el||el===docum",
        "ent.body)return false;if(el.__museLb)return false;if(isAgeGate(el))return false;if(isNavMenu(el))return false;",
        "if(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.posi",
        "tion!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.8||r.height<win",
        "dow.innerHeight*0.7)return false;if(!el.querySelector('img'))return false;if(recentTap()&&window.__museTapImg)",
        "{el.__museLb=1;return false;}return true;}function preRollBox(cd){var n=cd;while(n&&n!==document.body){if(n.qu",
        "erySelector&&n.querySelector('video'))return n;n=n.parentElement;}return cd.parentElement||cd;}function adOver",
        "lay(cd,box){if(!box||!cd)return null;var v=box.querySelector('video');if(!v)return null;var cur=cd;while(cur.p",
        "arentElement&&cur.parentElement!==box){cur=cur.parentElement;}if(cur===cd)return null;if(cur.contains&&cur.con",
        "tains(v))return null;return cur;}function clickNeutral(el){if(!el)return;var oo=window.open;try{window.open=fu",
        "nction(){return null;};el.click();}catch(e){}try{window.open=oo;}catch(e){}}function killPreRollOne(cd){if(!cd",
        "||!cd.isConnected)return;var box=preRollBox(cd);if(!box)return;var ov=adOverlay(cd,box);var scope=ov||box;var ",
        "img=scope.querySelector('.dplayer-pre-img');if(!img){var ims=scope.querySelectorAll('img'),best=null,ba=0;for(",
        "var i=0;i<ims.length;i++){var r=ims[i].getBoundingClientRect(),a=r.width*r.height;if(a>ba){ba=a;best=ims[i];}}",
        "img=best;}if(img)clickNeutral(img);var cd2=cd,box2=box,ov2=ov,img2=img;setTimeout(function(){if(!cd2.isConnect",
        "ed)return;if(ov2&&ov2.parentNode){ov2.parentNode.removeChild(ov2);}else{var ns=box2.querySelectorAll('.dplayer",
        "-pre-bg,.dplayer-pre-img,.dplayer-pre-countdown');for(var k=0;k<ns.length;k++){if(ns[k].parentNode)ns[k].paren",
        "tNode.removeChild(ns[k]);}var vs=box2.querySelectorAll('video');for(var vi=1;vi<vs.length;vi++){var av=vs[vi];",
        "var wp=av.parentElement;if(av.parentNode)av.parentNode.removeChild(av);if(wp&&wp!==box2&&wp.children.length===",
        "0&&wp.parentNode)wp.parentNode.removeChild(wp);}if(img2&&img2.parentNode){var ip=img2.parentElement;img2.paren",
        "tNode.removeChild(img2);if(ip&&ip!==box2&&ip.children.length===0&&ip.parentNode)ip.parentNode.removeChild(ip);",
        "}if(cd2.parentNode)cd2.parentNode.removeChild(cd2);}box2.classList.remove('dplayer-pre-playing');box2.classLis",
        "t.remove('dplayer-pre-clickable');var v=box2.querySelector('video');if(v){try{v.play();}catch(e){}}},200);}fun",
        "ction killPreRoll(){var cds=document.querySelectorAll('.dplayer-pre-countdown');for(var i=0;i<cds.length;i++){",
        "killPreRollOne(cds[i]);}var els=document.querySelectorAll('div,span,button');for(var j=0;j<els.length;j++){var",
        " el2=els[j];if(!el2.isConnected)continue;if(el2.querySelector('video,iframe'))continue;var t=txt(el2);if(t.len",
        "gth>16)continue;if(/^(广告)?(\\d{1,2}:\\d{2}|\\d+s?|\\d+秒)后(自动)?(关闭|关)$/.test(t)){killPreRollOne(el2);}else if(/^(跳过",
        "广告|SkipAd)$/i.test(t)){var h=el2.tagName==='A'?(el2.getAttribute('href')||''):'';if(el2.tagName!=='A'||h.index",
        "Of('javascript:')===0)clickNeutral(el2);}}}function run(){if(document.fullscreenElement)return;try{sweep();}ca",
        "tch(e){}}run();setTimeout(run,1200);setTimeout(run,3500);var sweepTimer=setInterval(run,2000);setTimeout(funct",
        "ion(){try{clearInterval(sweepTimer);}catch(e){}},300000);if(document.addEventListener){document.addEventListen",
        "er('fullscreenchange',function(){if(!document.fullscreenElement){var vs=document.querySelectorAll('video');for",
        "(var i=0;i<vs.length;i++){var v=vs[i];if(v.style&&v.style.display==='none')v.style.display='';var p=v.parentEl",
        "ement;while(p&&p!==document.body){if(p.style&&p.style.display==='none')p.style.display='';p=p.parentElement;}}",
        "setTimeout(function(){run();},300);}});}if(window.addEventListener){window.addEventListener('pageshow',functio",
        "n(){run();});}if(document.addEventListener){document.addEventListener('visibilitychange',function(){if(!docume",
        "nt.hidden)run();});}var pending=null;var ob=new MutationObserver(function(){if(pending)return;pending=setTimeo",
        "ut(function(){pending=null;run();},100);});if(document.documentElement)ob.observe(document.documentElement,{ch",
        "ildList:true,subtree:true,attributes:true,attributeFilter:['style']});setTimeout(function(){try{ob.disconnect(",
        ");}catch(e){}},300000);var preOb=new MutationObserver(function(muts){for(var i=0;i<muts.length;i++){var ns=mut",
        "s[i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;var cd=null;if(n.class",
        "List&&n.classList.contains('dplayer-pre-countdown'))cd=n;else if(n.querySelector)cd=n.querySelector('.dplayer-",
        "pre-countdown');if(cd){killPreRollOne(cd);return;}}}});if(document.documentElement)preOb.observe(document.docu",
        "mentElement,{childList:true,subtree:true});setTimeout(function(){try{preOb.disconnect();}catch(e){}},600000);}",
        ")();",
    };
    private static final String CLEAN_JS;
    static {
        StringBuilder sb = new StringBuilder();
        for (String p : CLEAN_JS_PARTS) sb.append(p);
        CLEAN_JS = sb.toString();
    }
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
    private boolean mBlankRecovered = false;
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
        // v105：切源 = 全新浏览器——新开一个站的 webview 时先清掉 webview 缓存（上一个站的页面/资源不留着，避免缓存里的旧站串进来）。
        mBinding.web.clearCache(true);
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
        setting.setSupportMultipleWindows(false);
        setting.setJavaScriptCanOpenWindowsAutomatically(false);
        setting.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        setting.setUserAgentString(Setting.getUa());
        mBinding.web.setWebViewClient(webViewClient());
        mChromeClient = webChromeClient();
        mBinding.web.setWebChromeClient(mChromeClient);
    }

    private WebViewClient webViewClient() {
        return new WebViewClient() {
            // v93 跳转链路：放行一次中转跳转后，记下起跳站与中转站 + 10 秒窗口——中转站的 302 跟进
            // 也会进 shouldOverrideUrlLoading（目标不带当前站地址）；同一条链的无手势跟进放行。
            // 点按劫持带手势，到不了这里；窗口一过或页面落地即失效。
            private String mJumpAnchorHost = null;
            private String mJumpRelayHost = null;
            private long mJumpChainUntil = 0;

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
                        if (mBlockedHosts.add(host)) DebugLogStore.add("WebPlay", "blocked qiyou ad-loader url=" + request.getUrl());
                        return mEmpty;
                    }
                }
                // 广告加载器（混淆 js 拉的远程广告脚本，域名按日期轮换）：按子串拦，通用、不只麦田。
                if (isAdLoaderUrl(request.getUrl().toString())) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add("WebPlay", "blocked ad-loader url=" + request.getUrl());
                    return mEmpty;
                }
                // 底部广告轮询脚本（如 Mirages 主题 bottom_ad_poll.js）：只拉广告 feed，不承载正片，直接拦。
                String path = request.getUrl().getPath();
                if (path != null && path.toLowerCase(Locale.US).contains("ad_poll")) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add("WebPlay", "blocked ad_poll script=" + request.getUrl());
                    return mEmpty;
                }
                if (!TextUtils.isEmpty(host) && isAd(host)) {
                    // 视频流地址永不拦截：广告是图片/脚本/iframe，流本身不是广告。
                    // 有的站视频走轮换域名，域名单误伤时播放器只剩 0:00 空壳。
                    if (isMediaUrl(request.getUrl().getPath())) return super.shouldInterceptRequest(view, request);
                    if (mBlockedHosts.add(host)) DebugLogStore.add("WebPlay", "blocked host=" + host + " url=" + request.getUrl());
                    return mEmpty;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
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

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (url == null || url.equals("about:blank")) return;
                // v96：先藏 webview，等 CLEAN_JS 首扫跑完再显示——hsck 首页顶部 58 条赌博横幅在首扫前会闪约 0.25 秒。
                mBlankRecovered = false;
                view.setVisibility(View.INVISIBLE);
                if (mForceShowWeb != null) view.removeCallbacks(mForceShowWeb);
                mForceShowWeb = () -> view.setVisibility(View.VISIBLE);
                view.postDelayed(mForceShowWeb, 5000);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (url == null || url.equals("about:blank")) return;
                view.evaluateJavascript(CLEAN_JS, value -> view.setVisibility(View.VISIBLE));
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
                view.evaluateJavascript(CLEAN_JS, value -> view.setVisibility(View.VISIBLE));
                // v100：空白页回滚——被拦的广告跳转有时会把当前页卸成空文档（标题空、无视频、无图），
                // 点什么都没反应。检出空白就回滚到入口地址一次（mAnchorUrl），避免死在空白页。
                view.evaluateJavascript("(function(){try{return ((document.title||'').trim()===''&&(document.body?document.body.innerText.trim():'')===''&&document.images.length===0)?'1':'0';}catch(e){return '0';}})()", blankVal -> {
                    if ("1".equals(blankVal) && !mBlankRecovered && !TextUtils.isEmpty(mAnchorUrl) && !mAnchorUrl.equals(url)) {
                        mBlankRecovered = true;
                        DebugLogStore.add("WebPlay", "blank page, reload anchor url=" + mAnchorUrl);
                        view.loadUrl(mAnchorUrl);
                    }
                });
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
                    DebugLogStore.add("WebPlay", "page error url=" + errUrl + " code=" + error.getErrorCode() + " desc=" + error.getDescription());
                } else if (mResErrUrls.size() < 20 && mResErrUrls.add(errUrl)) {
                    DebugLogStore.add("WebPlay", "res error url=" + errUrl + " code=" + error.getErrorCode());
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
                        DebugLogStore.add("WebPlay", "console error " + msg + " @" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
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

    private boolean isAd(String host) {
        if (AdDomains.isAdDomain(host)) return true;
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
        mBinding.web.onResume();
    }

    @Override
    protected void onPause() {
        mBinding.web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mBinding != null) {
            mBinding.web.loadUrl("about:blank");
            mBinding.web.destroy();
        }
        super.onDestroy();
    }
}
