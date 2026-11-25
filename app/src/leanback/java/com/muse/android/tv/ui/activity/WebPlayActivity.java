package com.muse.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
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

import java.lang.ref.WeakReference;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.api.config.RuleConfig;
import com.muse.android.tv.api.loader.PushMultiRes;
import com.muse.android.tv.api.loader.BrowserMeta;
import com.muse.android.tv.api.loader.HttpEpisodeResolver;
import com.muse.android.tv.bean.Episode;
import com.muse.android.tv.databinding.ActivityWebPlayBinding;
import com.muse.android.tv.setting.Setting;
import com.muse.android.tv.ui.adapter.EpisodeAdapter;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.dialog.EpisodeDialog;
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
 *
 * <p>电视版（leanback）：与手机版逐字一致，仅三处适配——强制横屏、顶栏返回/刷新可遥控（WebView 不抢焦点）、选集弹窗改用电视版 EpisodeDialog（手机版的 EpisodeGridDialog 只在 mobile 源集里有）。</p>
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
    // v21 (P0): isChallenge 扩为整页挑战检测——标题含「请稍候/Just a moment」或 HTML 含 challenges.cloudflare.com/cf-challenge 时整页跳过 CLEAN_JS；
    // div 循环加 isChallenge 守卫；isModal/isOpaqueModal/isBigOverlay 内部加 isChallenge 守卫。
    private static final String[] CLEAN_JS_PARTS = {
        "(function(){if(window.__museWebClean)return;try{var __museCT=(document.title||'').toLowerCase();if(__museCT.in",
        "dexOf('请稍候')>=0||__museCT.indexOf('just a moment')>=0)return;var __museCH=document.documentElement.outerHTML.s",
        "lice(0,200000).toLowerCase();if(__museCH.indexOf('challenges.cloudflare.com')>=0||__museCH.indexOf('cf-challen",
        "ge')>=0)return;}catch(e){}window.__museWebClean=1;try{var __museSt=document.createElement('style');__museSt.te",
        "xtContent='.ad-item,.ec-ad,.ads_w,.ads_close_btn,.adspop,.gkktXd,.ec-ad-fixed,.ads_w.slide-wap,.ec-ad.ads_w.sl",
        "ide-wap,#AdBottomLeader,#AdBottomRight,#AdPopUp,#AdRectangleBanner,#AdSlot_megabanner,#Adbanner,#AdsBannerTop,",
        "#AdsBottomContainer,#Ads_google_bottom_wide,#AdsenseBottom,#Banner728x90,#ad--article-bottom-wrapper,#ad-Super",
        "banner,#ad-banner-1,#ad-banner-atf,#ad-banner-bottom,#ad-banner-btf,#ad-banner-desktop,#ad-banner-image,#ad-ba",
        "nner-placement,#ad-banner-top,#ad-banner-wrap,#ad-banner_atf-label,#ad-billboard-bottom,#ad-block-bottom,#ad-b",
        "ottom,#ad-bottom-banner,#ad-bottom-fixed,#ad-bottom-right-container,#ad-bottom-wrapper,#ad-box-bottom,#ad-cont",
        "ainer-banner,#ad-container-overlay,#ad-desktop-bottom,#ad-fixed-bottom,#ad-fullbanner-btf,#ad-fullbanner-outer",
        ",#ad-large-banner-top,#ad-leaderboard-bottom,#ad-leaderboard_bottom,#ad-main-bottom,#ad-overlay,#ad-performanc",
        "eFullbanner1,#ad-popup,#ad-popup-home,#ad-popup-int,#ad-small-banner,#ad-spot-bottom,#ad-sticky-footer-contain",
        "er,#ad-top-banner,#ad-unit-right-bottom-160-600,#ad-unit-top-banner,#adBanner1,#adBanner336x280,#adBannerBotto",
        "m,#adBannerHeader,#adBannerSpacer,#adBannerTable,#adBannerTop,#adBlockBanner,#adBlockOverlay,#adBottom,#adModa",
        "l,#adMpuBottom,#adPopup,#adSlot-leaderBottom,#adSpaceBottom,#adSuperbanner,#ad_Banner,#ad_Bottom,#ad_banner,#a",
        "d_banner_1,#ad_banner_468x60,#ad_banner_728x90,#ad_banner_bot,#ad_banner_top,#ad_banners,#ad_bottom,#ad_cont_s",
        "uperbanner,#ad_div_bottom,#ad_from_bottom,#ad_fullbanner,#ad_middle_bottom,#ad_overlay,#ad_overlayer,#ad_popup",
        "_background,#ad_popup_wrapper,#ad_rect_bottom,#ad_slot_bottom,#ad_sticky_wrap,#ad_superbanner,#ad_topBanner,#a",
        "d_top_banner,#ad_topbanner,#adb_bottom,#adbanner-container,#adbanner1,#adbannerbox,#adbannerdiv,#adbannerleft,",
        "#adbannerright,#adbannerwidget,#adblockbottom,#adbottom,#adbottomleft,#adbottomright,#adbox--page_bottom_ad,#a",
        "dbox-topbanner,#adfloat,#admpubottom,#admpubottom2,#adpositionbottom,#adrightbottom,#ads-banner,#ads-banner-to",
        "p,#ads-bottom,#ads-right-bottom,#ads-superBanner,#ads728bottom,#adsBottom,#adsMobileFixed,#adsTopMobileFixed,#",
        "ads_banner,#ads_banner1,#ads_banner_header,#ads_bottom,#ads_box_bottom,#ads_center_banner,#ads_in_modal,#ads_l",
        "ayout_bottom,#ads_leaderbottom,#ads_overlay,#ads_popup,#ads_superbanner1,#ads_superbanner2,#ads_top_banner,#ad",
        "s_topbanner,#adsbottom,#adsense-bottom,#adsense-container-bottom,#adsense_banner_top,#adsense_bottom_ad,#adsen",
        "se_overlay,#adsense_r_side_sticky_container,#adslot-sticky-headerbar,#adspace-bottom,#adspot-bottom,#adunit-ar",
        "ticle-bottom,#adv-Bottom,#adv-banner,#adv-banner-r,#adv-sticky-1,#adv-sticky-2,#advBoxBottom,#adv_BoxBottom,#a",
        "dv_overlay,#adv_overlay_content,#advert-banner,#advert-banner-container,#advert-banner-wrap,#advert-banner2,#a",
        "dvert-top-banner,#advertBanner,#advertOverlay,#advert_banner,#advertiseBanner,#advertisement-bottom,#advertise",
        "ment_banner,#advertisements_bottom,#advertising-banner,#advertising__banner__content,#advt_bottom,#adzbanner,#",
        "adzonebanner,#after_bottom_ad,#article-bottom-ad,#articleBottom-ads,#article_ad_bottom,#article_banner_ad,#ban",
        "ner-300x250,#banner-468x60,#banner-728,#banner-728x90,#banner-ad,#banner-ad-container,#banner-ad-large,#banner",
        "-ads,#banner-advert,#banner-lg-ad,#banner-native-ad,#banner-skyscraper,#banner300x250,#banner468,#banner468x60",
        ",#banner728,#banner728x90,#banner_ad_bottom,#banner_ad_footer,#banner_ad_module,#banner_ad_placeholder,#banner",
        "_ad_top,#banner_ads,#banner_adsense,#banner_adv,#banner_advertisement,#banner_adverts,#banner_content_ad,#bann",
        "er_sedo,#banner_slot,#banner_spacer,#banner_topad,#banner_videoad,#banner_wrapper_top,#block-adsense-banner-ar",
        "ticle-bottom,#block-adsense-banner-channel-bottom,#bottom-ad,#bottom-ad-1,#bottom-ad-area,#bottom-ad-banner,#b",
        "ottom-ad-container,#bottom-ad-leaderboard,#bottom-ad-slot,#bottom-ad-tray,#bottom-ad-wrapper,#bottom-ads,#bott",
        "om-ads-bar,#bottom-ads-container,#bottom-not-ads,#bottom-side-ad,#bottom-sponsor-add,#bottom_ad,#bottom_ad_728",
        ",#bottom_ad_area,#bottom_ad_box,#bottom_ad_region,#bottom_ad_unit,#bottom_ad_wrapper,#bottom_ads,#bottom_banne",
        "r_ad,#bottom_fixed_ad_overlay,#bottom_leader_ad,#bottom_sponsor_ads,#bottom_sponsored_links,#bottom_text_ad,#c",
        "ontent_bottom_ad,#content_bottom_ads,#desktop_middle_ad_fixed,#desktop_top_ad_fixed,#dfp-ad-bottom-wrapper,#df",
        "p-ad-floating,#dfp-banner,#dfp-banner-popup,#dfp-top-banner,#div-ad-bottom,#div-ad-top_banner,#div_ad_float,#d",
        "nn_ad_banner,#dsk-banner-ad-a,#dsk-banner-ad-b,#dsk-banner-ad-c,#dsk-banner-ad-d,#em_ad_superbanner,#fixed-ad,",
        "#floating-ad-wrapper,#floating-ads,#floating-advert,#floating_ad,#floating_ad_container,#floating_ads_bottom_t",
        "extcss_container,#footer-banner-ad,#full_banner_ad,#global-banner-ad,#google-ads-bottom,#google-ads-bottom-con",
        "tainer,#gpt-ad-banner,#gpt-ad-side-bottom,#header-ad-bottom,#header-banner-728-90,#header-banner-ad,#header-ba",
        "nner-ad-wrapper,#header_ad_banner,#header_bottom_ad,#home-banner-ad,#home_bottom_ad,#horizontal-banner-ad,#ind",
        "ex-bottom-advert,#large-bottom-leaderboard-ad,#leaderboard-bottom-ad,#leaderboard_bottom_ad,#left-bottom-ad,#l",
        "eftBanner-ad,#left_float_ad,#lg-banner-ad,#long-bottom-ad-wrapper,#lower-ad-banner,#m-banner-bannerAd,#medrec_",
        "bottom_ad,#medrectangle_banner,#modal-ad,#mpu_banner,#overlay-ad-bg,#overlay_ad,#popup_ad_wrapper,#post-bottom",
        "-ads,#rail-bottom-ad,#redirect-ad-modal,#right_bottom_ad,#section-ad-bottom,#show-sticky-ad,#side-banner-ad,#s",
        "ite_bottom_ad_div,#sm-banner-ad,#sp-adv-banner-top,#sponsor_bottom,#sponsored_link_bottom,#sticky-ad,#sticky-a",
        "d-bottom,#sticky-ad-container,#sticky-ad-header,#sticky-ads,#sticky-ads-top,#sticky-custom-ads,#sticky-footer-",
        "ad,#sticky-footer-ads,#sticky-left-ad,#sticky-rail-ad,#sticky_sidebar_ads,#story-bottom-ad,#top-ad-banner,#top",
        "-banner-ad,#top-banner-ad-browser,#topBanner-ad,#top_ad_banner,#top_banner_ads,#topbanner_ad,#topbanner_sponso",
        "r,#video_overlay_ad,#zeus_top-banner,.AdBottomPage,.Ads-sticky,.AdsBottom,.AdsBoxBottom,.AdzerkBanner,.Banner3",
        "00x250,.Banner468X60,._bottom_ad_wrapper,.ac-banner-ad,.ad--banner,.ad--banner2,.ad--bottom,.ad--bottom-label,",
        ".ad--bottommpu,.ad--inArticleBanner,.ad--overlayer,.ad--sticky,.ad-728-banner,.ad-SuperBanner,.ad-adlink-botto",
        "m,.ad-banner-2,.ad-banner-250x600,.ad-banner-300,.ad-banner-300x250,.ad-banner-5,.ad-banner-6,.ad-banner-728x9",
        "0,.ad-banner-bottom-container,.ad-banner-box,.ad-banner-btf,.ad-banner-container,.ad-banner-content,.ad-banner",
        "-full-wrapper,.ad-banner-header,.ad-banner-image,.ad-banner-inlisting,.ad-banner-leaderboard,.ad-banner-placeh",
        "older,.ad-banner-single,.ad-banner-smaller,.ad-banner-static,.ad-banner-top,.ad-banner-top-wrapper,.ad-banner-",
        "wrapper,.ad-banners,.ad-bigbanner,.ad-bigbox-fixed,.ad-block-banner-container,.ad-block-bottom,.ad-block-stick",
        "y-ad,.ad-bottom,.ad-bottom-container,.ad-bottom-right-container,.ad-bottom728x90,.ad-bottomLeft,.ad-bottomlead",
        "er,.ad-bottomline,.ad-boxbottom,.ad-boxsticky,.ad-component-fullbanner2,.ad-container-banner-top,.ad-container",
        "-bottom,.ad-container__sticky-wrapper,.ad-fixed,.ad-float,.ad-floating,.ad-fullbanner,.ad-fullbanner-btf-conta",
        "iner,.ad-fullbannernohieght,.ad-home-bottom,.ad-leader-bottom,.ad-m-banner,.ad-mobile--sticky,.ad-mobile-banne",
        "r,.ad-mpu-bottom,.ad-mpufixed,.ad-overlay,.ad-popup,.ad-popup-content,.ad-rectangle-banner,.ad-relatedbottom,.",
        "ad-single-bottom,.ad-slot--mpu-banner-ad,.ad-slot--top-banner-ad,.ad-slot-banner,.ad-space-topbanner,.ad-stick",
        "y,.ad-sticky-banner,.ad-sticky-bottom,.ad-sticky-container,.ad-sticky-slot,.ad-sticky-wrapper,.ad-stickyhero,.",
        "ad-stickyhero--standard,.ad-stickyhero-enable-mobile,.ad-superbanner,.ad-superbanner-node,.ad-top-banner,.ad-t",
        "opbanner,.ad-wide-bottom,.ad-wrapper-sticky,.ad1-float,.ad1_bottom,.ad2-float,.ad300banner,.adArticleBanner,.a",
        "dBanner300x250,.adBanner728x90,.adBlockBottom,.adFullbanner,.adHeaderAdbanner,.adInnerLeftBottom,.adMrginBotto",
        "m,.adOverlay,.adRectangleBanner,.adTopFloat,.ad__sticky,.ad_banner,.ad_banner2,.ad_banner_2,.ad_banner_250x250",
        ",.ad_banner_468,.ad_banner_728,.ad_banner_728x90_inner,.ad_banner_border,.ad_banner_div,.ad_big_banner,.ad_bot",
        "banner,.ad_bottom,.ad_bottom_728,.ad_bottom_leaderboard,.ad_bottom_left,.ad_bottom_mpu,.ad_bottom_space,.ad_ce",
        "nter_bottom,.ad_container_bottom,.ad_content_bottom,.ad_custombanner,.ad_div_banner,.ad_float,.ad_floating_box",
        ",.ad_footer_super_banner,.ad_leader_bottom,.ad_megabanner,.ad_middle_banner,.ad_overlays,.ad_placeholder_d_sti",
        "cky,.ad_side_rectangle_banner,.ad_top_banner,.ad_wrapper_fixed,.adace-adi-popup-wrapper,.adalert-overlayer,.ad",
        "banner-300-250,.adbanner-bottom,.adbanner1,.adbannerbox,.adbannerright,.adbannertop,.adblock-bottom,.adblock-p",
        "opup,.adblock__banner,.adborderbottom,.adbottom,.adbottomright,.adbox-topbanner,.adboxTopBanner,.adbox_bottom,",
        ".adbutler-top-banner,.adbutler_top_banner,.adform__topbanner,.adfoxly-overlay,.adframe_banner,.adguru-modal-po",
        "pup,.adpopup,.adriverBanner,.adrotate_top_banner,.ads--bottom-spacing,.ads-articlebottom,.ads-banner,.ads-bann",
        "er-bottom,.ads-banner-js,.ads-banner-middle,.ads-banner-spacing,.ads-banner-top,.ads-banner-top-right,.ads-bin",
        "g-bottom,.ads-block-bottom-wrap,.ads-bottom,.ads-bottom-block,.ads-bottom-center,.ads-bottom-content,.ads-bott",
        "om-left,.ads-bottom-right,.ads-google-bottom,.ads-horizontal-banner,.ads-relatedbottom,.ads-sticky,.ads-superb",
        "anner,.ads-top-fixed,.adsBanner,.adsBannerLink,.adsBottom,.adsFixed,.adsSticky,.adsTopBanner,.ads_banner,.ads_",
        "header_bottom,.ads_top_banner,.ads_topbanner,.adsbanner1,.adsbanner2,.adsbottom,.adsbottombox,.adsense-float,.",
        "adsense-overlay,.adsense-sticky-slide,.adsenseBannerArea,.adsense_bottom,.adsensefloat,.adsfloat,.adsfloatpane",
        "l,.adslot-banner,.adslot_popup,.adspace_bottom,.adtech-banner,.adv-banner,.adv-bottom,.adv-leaderboard-banner,",
        ".adv-top-banner,.advBottom,.advBottomHome,.adv_banner,.adv_banner_hor,.advads-sticky,.advert--banner-wrap,.adv",
        "ert--sticky,.advert-article-bottom,.advert-banner,.advert-banner-container,.advert-banner-holder,.advert-banne",
        "rad,.advert-bottom,.advert-overlay,.advert-sticky-wrapper,.advert-wingbanner-left,.advert-wingbanner-right,.ad",
        "vert1Banner,.advertBanner,.advertBottom,.advertFullBanner,.advertSuperBanner,.advert__fullbanner,.advert_banne",
        "r,.advert_banners,.advert_main_bottom,.advertisement-banner,.advertisement-bottom,.advertisementBanner,.advert",
        "ising-banner,.advertising-fixed,.advertising-top-banner,.advertisingBanner,.advertising_banner,.advertising_bo",
        "ttom_box,.advertisment-banner,.advertizing-banner,.adverts--banner,.adviewDFPBanner,.advt-banner-3,.adzbanner,",
        ".after_content_banner_advert,.anzeige_banner,.article-ad-bottom,.articleBottom-ads,.article_ads_banner,.articl",
        "e_bottom-ads,.article_bottom_ad,.banner--ad,.banner-125,.banner-300,.banner-300-100,.banner-300-250,.banner-30",
        "0x250,.banner-300x600,.banner-320-100,.banner-468,.banner-468-60,.banner-468x60,.banner-728,.banner-728x90,.ba",
        "nner-ad-below,.banner-ad-block,.banner-ad-bottom-fixed,.banner-ad-container,.banner-ad-contianer,.banner-ad-fo",
        "oter,.banner-ad-image,.banner-ad-inner,.banner-ad-label,.banner-ad-large,.banner-ad-pos,.banner-ad-row,.banner",
        "-ad-skeleton-box,.banner-ad-space,.banner-ad-wrap,.banner-ad-wrapper,.banner-ad2,.banner-ads-right,.banner-ads",
        "-sidebar,.banner-adsense,.banner-adv,.banner-advert,.banner-advert-wrapper,.banner-advertisement,.banner-adver",
        "tising,.banner-adverts,.banner-asd__title,.banner-buysellads,.banner-img > .pbl,.banner-sponsorship,.banner-to",
        "p-ads,.banner120x600,.banner160,.banner160x600,.banner200x200,.banner300,.banner300x250,.banner336,.banner336x",
        "280,.banner350,.banner468,.banner728,.banner728-ad,.banner728-container,.banner728x90,.banner_160x600,.banner_",
        "240x400,.banner_250x250,.banner_300_250,.banner_300x250,.banner_300x600,.banner_468_60,.banner_468x60,.banner_",
        "728_90,.banner_ad-728x90,.banner_ad_300x250,.banner_ad_728x90,.banner_ad_container,.banner_ad_footer,.banner_a",
        "d_full,.banner_ad_leaderboard,.banner_ad_link,.banner_ad_wrapper,.banner_ads1,.banner_reklam,.banner_reklam2,.",
        "banner_slot,.bannergroup-ads,.banners_ad,.block-ads-bottom,.block-advertisement-banner-block,.bottom-ad,.botto",
        "m-ad--bigbox,.bottom-ad-banner,.bottom-ad-box,.bottom-ad-container,.bottom-ad-desktop,.bottom-ad-large,.bottom",
        "-ad-placeholder,.bottom-ad-wrapper,.bottom-ad-zone,.bottom-ad2,.bottom-ads,.bottom-ads-container,.bottom-ads-s",
        "ticky,.bottom-ads-wrapper,.bottom-banner-ad,.bottom-fixed-ad,.bottom-left-ad,.bottom-mobile-ad,.bottom-mpu-ad,",
        ".bottom-post-ad-space,.bottom-post-ads,.bottom-right-advert,.bottom-side-advertisement,.bottom_ad,.bottom_ad_b",
        "lock,.bottom_ad_placeholder,.bottom_ad_responsive,.bottom_ads,.bottom_banner_ad,.bottom_banner_advert_text,.bo",
        "ttom_bar_ads,.bottom_left_advert,.bottom_right_ad,.bottom_side_ad,.bottom_sponsor,.bottom_sticky_ad,.box-fixed",
        "-ads,.brn-ads-sticky-wrapper,.c-ad--adStickyContainer,.c-advert-superbanner,.c-advertising__banner-area,.cb-ad",
        "-banner,.chapter-bottom-ads,.container-banner-ads,.container-bottom-ad,.content-ads-bottom,.content-bottom-ad,",
        ".ct-bottom-ads,.custom-advert-banner,.custom-sticky-ad-container,.custom_banner_ad,.dac__banner__wrapper,.desk",
        "top-ad-banner,.dfp-banner,.dfp-banner-slot,.dfp_ad_content_bottom,.entry-bottom-ad,.entry-bottom-ads,.first-ba",
        "nner-ad,.fixed-ad-aside,.fixed-ad-bottom,.fixed-ads,.fixed-bottom-ad,.fixed-sidebar-ad,.fixed_ad,.fixed_advert",
        "_banner,.floated-ad,.floated_right_ad,.floating-ads,.floating-advert,.follower-ad-bottom,.footer-banner-ad,.fo",
        "oter-banner-ads,.footer-floating-ad,.footer_bottom_ad,.fullbanner_ad,.gallery-ad-overlay,.gnm-banner-ad,.googl",
        "e-ads-bottom,.google-bottom-ads,.gpt-leaderboard-banner,.gpt-mpu-banner,.guide__row--fixed-ad,.has-fixed-botto",
        "m-ad,.head-banner468,.header-ad-banner,.header-banner-ad,.header-banner-ads,.header-banner-advertising,.home-s",
        "ticky-ad,.home_ad_bottom,.homepage-banner-ad,.inline-banner-ad,.is-sticky-ad,.js-ad-banner-container,.js-ad-lo",
        "ader-bottom,.js-ad-unit-bottom,.js-dfp-ad-bottom,.js-no-sticky-ad,.js-overlay_ad,.js-sticky-ad,.l-bottom-ads,.",
        "leaderboard-ad-fixed,.leaderboard_banner_ad,.m_banner_ads,.mc_floating_ad,.mid-article-banner-ad,.mid_banner_a",
        "d,.mj-floating-ad-wrapper,.moads-top-banner,.mob-hero-banner-ad-wrap,.mobile-fixed-ad,.mobile_ad_banner,.modal",
        "-ad,.mpu-top-left-banner,.news_vibrant_ads_banner,.ng-ad-banner,.o-ad-banner-top,.oio-banner-zone,.overlay-ad,",
        ".overlay-ad-container,.overlay-ads,.overlay-box-ad,.overlay_ad,.p-ad-dfp-banner,.page-bottom-fixed-ads,.player",
        "-ad-overlay,.pm-ads-banner,.pm-banner-ad,.profile_ad_bottom,.region-ad-bottom-leaderboard,.region-banner-ad,.s",
        "how-sticky-ad,.sidebar-banner-ad,.sidebar-bottom-ad,.sidebar-sticky--ad,.single-bottom-ads,.single-post-bottom",
        "-ads,.single_bottom_ad,.site-bottom-ad-slot,.sponsor-bottom,.sponsor-popup,.sponsor_popup,.sponsored-container",
        "-bottom,.sticky-ad,.sticky-ad-bottom,.sticky-ad-container,.sticky-ad-footer,.sticky-ad-header,.sticky-ad-wrapp",
        "er,.sticky-ads,.sticky-ads-container,.sticky-ads-content,.sticky-advert-widget,.sticky-bottom-ad,.sticky-foote",
        "r-ad,.sticky-footer-ad-container,.sticky-navbar-ad-container,.sticky-rail-ad-container,.sticky-side-ad,.sticky",
        "-sidebar-ad,.sticky-top-ad-wrap,.sticky_ad_sidebar,.sticky_ad_wrapper,.sticky_ads,.top-ad-sticky,.top-ads-bott",
        "om-bar,.top-adsense-banner,.top-banner-468,.top-banner-ad,.top-banner-ad-container,.top-banner-ad-wrapper,.top",
        "-banner-add,.top-banner-ads,.top-banner-advert,.top-fixed-ad,.top_ad_banner,.tpd-banner-ad-container,.tpd-bann",
        "er-desktop,.vi-sticky-ad,.video-ad-bottom,.vjs-ad-overlay,.vodl-ad__bigsizebanner,.wp_bannerize_banner_box,.wp",
        "x_bannerize_banner_box,.ww_ads_banner_wrapper,.boxOverContent__banner,.RC-AD-BOX-BOTTOM,.RC-AD-TOP-BANNER,.pop",
        "up-box-ads,.amp-sticky-ad-custom,.amp-sticky-ads,#mobile-swipe-banner,#banner_pos1_ddb_0,#banner_pos2_ddb_0,#b",
        "anner_pos3_ddb_0,#banner_pos4_ddb_0,.taboola-banner{display:none!important}';(document.head||document.document",
        "Element).appendChild(__museSt);}catch(e){}var host=location.hostname;var __museAdSet=new Set(\"005ad66f1d.com,1",
        "0bet.com,152media.com,18porn.sex,18teensex.tv,19sex.live,1adda2699c.com,1av.to,1betandgonow.com,1gbjadpsq.de,1",
        "pop.online,1push.io,206ads.com,20tracks.com,21f5f01ad3.com,21sexturycash.com,22523e8ad1.com,22betpartners.com,",
        "22bettracking.online,22media.world,24affiliates.com,24click.top,24x7adservice.com,27bad22e4a.com,2mdn.net,2sma",
        "rttracker.com,2track.info,300daytravel.com,360ads.com,365av.net,3c1d2ad9d8.com,3d-porn.co,3mads.com,3myad.com,",
        "492tracker.net,4ad4ba0610.com,4affiliate.net,4dsbanner.net,4kporn.xxx,4pornhd.com,4rabettraff.com,4tube.com,4w",
        "network.com,567bets10.com,56ad42c3a8.com,570dad652f.com,5advertise.com,62ad.com,70ad2eae1e.com,777av.cc,840wit",
        "hnetworks.net,888media.net,888promos.com,8c56c86ead.com,8d1uavww.xyz,8f7cd5dfad.com,8sfdladng.xyz,900bets10.co",
        "m,901radius.net,930networks.net,99ad29e111.com,99ddb6ad48.com,a-ads.com,aa783adfeb.com,aaddcount.com,aanetwork",
        ".vn,aavvsaqmktwmu.website,aaxads.com,abadit5rckb.com,abadit5rckd.com,abateadversity.com,abethow.com,abnad.net,",
        "aboveaverageheavenly.com,abovemyselfsadness.com,abroadsuicidaleliminated.com,absoluporn.com,absolutionthornlea",
        "der.com,academywristadroit.com,acam-2.com,acceptableredheadcaviar.com,acceptancecrowdadvertising.com,accomplis",
        "hedpersuadedprinter.com,accoybrava.com,ace-adserver.com,acebet.com,aceporntube.com,achievablecpmrevenue.com,ac",
        "hievementseeingladder.com,acmexxx.com,acrossexterminatorfoggy.com,activejavgg124.fun,ad-adapex.io,ad-arrow.com",
        ",ad-back.net,ad-balancer.net,ad-block-offer.com,ad-cheers.com,ad-delivery.net,ad-flow.com,ad-free.info,ad-m.as",
        "ia,ad-mapps.com,ad-maven.com,ad-mixr.com,ad-nex.com,ad-opt.site,ad-recommend.com,ad-score.com,ad-server.co.za,",
        "ad-srv.net,ad-stir.com,ad-tech.ru,ad-vortex.com,ad.gt,ad.guru,ad.page,ad.plus,ad.style,ad1n.net,ad20.net,ad239",
        "1c451.com,ad2adnetwork.biz,ad2bitcoin.com,ad2f8c6f8e.com,ad2the.net,ad4.com.cn,ad4989.co.kr,ad4game.com,ad62c9",
        "88a3.com,ad69.com,ad6media.fr,ad999.biz,adacado.com,adactioner.com,adacts.com,adadvisor.net,adagora.com,adahum",
        "anhear.com,adalh-zcq.com,adalliance.io,adalso.com,adamantundergone.com,adamatic.co,adandhub.com,adangle.online",
        ",adaos-ads.net,adapex.io,adappi.co,adaptationprodigalevil.com,adaptdivinity.com,adaptedestimatedcaress.com,ada",
        "ptedmercifulinterruption.com,adapterwith1024.net,adapthopperpriest.com,adaptive5000.net,adaptunemployed.com,ad",
        "around.net,adarutoad.com,adasgsts.cc,adatrix.com,adbakbhknpczi.site,adbard.net,adbasket.net,adbeacon.com,adbes",
        "tnet.com,adbetclickin.pink,adbetnetwork.com,adbfkaoxvm.in,adbit.biz,adbits.online,adblade.com,adblck.com,adblo",
        "ck-360.com,adblock-guru.com,adblock-one-protection.com,adblock-tester.com,adblock-zen.com,adblockanalytics.com",
        ",adblocker-sentinel.net,adblockermax.com,adblockeromega.com,adblockerprofessional.com,adblockersentinel.com,ad",
        "blockstream.com,adblockstrtape.link,adblockstrtech.link,adbmi.com,adboost.it,adbooth.com,adbox.lv,adbpage.com,",
        "adbrite.com,adbro.me,adbuddiz.com,adbuff.com,adbull.com,adbutler-fermion.com,adbutler.com,adbwublzlyxvg.online",
        ",adbyss.com,adca.st,adcalm.com,adcannyxml.com,adcash.com,adcdnx.com,adcell.com,adcell.de,adcharriot.com,adclea",
        "nerpage.com,adclerks.com,adclick.pk,adclicka.com,adclickad.com,adclickafrica.com,adclickbyte.com,adclickmedia.",
        "com,adclickppc.com,adclicks.io,adcloud.net,adcolo.com,adcolony.com,adconscious.com,adcontext.pl,adcovery.com,a",
        "dcrax.com,addefenderplus.info,addictedpledgeabridge.com,addictrelive.com,addin1.name,addinginstancesroadmap.co",
        "m,addiply.com,additionalcasualcabinet.com,additionalheritagenose.com,additionalmedia.com,additionmillionaireim",
        "pending.com,addizhi.top,addkt.com,addoer.com,addonsmash.com,addoor.net,addotnet.com,addressimpertinenceclockwi",
        "se.com,addroid.com,addroplet.com,addthief.com,addynamo.net,adecn.com,adehhurslxxmh.space,adelement.com,adelphi",
        "c.net,ademails.com,adenc.co.kr,adengage.com,adentifi.com,adenza.dev,adeoplonrprkm.site,adespresso.com,adevppl.",
        "com,adewpxnzsnvib.site,adex.media,adexchangeclear.com,adexchangecloud.com,adexchangedirect.com,adexchangegate.",
        "com,adexchangeguru.com,adexchangemachine.com,adexchangeprediction.com,adexchangerapid.com,adexchangetracker.co",
        "m,adexcite.com,adexmedias.com,adextrem.com,adf01.net,adfgetlink.net,adfinity.pro,adfinix.com,adflyer.media,adf",
        "oc.us,adfootprints.com,adforcast.com,adforgames.com,adforge.io,adform.net,adfox.cam,adfpoint.com,adfrontiers.c",
        "om,adfusion.com,adfyre.co,adgage.es,adgatemedia.com,adgear.com,adgebra.co.in,adgebra.in,adgitize.com,adglare.n",
        "et,adglidepro.com,adgoi.com,adgorithms.com,adgrid.io,adgroups.com,adgrx.com,adhash.com,adhaven.com,adhealers.c",
        "om,adherebarrowdomesticated.com,adherepreserveshrimp.com,adhese.be,adhese.com,adhese.net,adhigh.net,adhitzads.",
        "com,adhoc2.net,adhouse.pro,adhub.digital,adhub.media,adhunt.net,adikteev.com,adimpact.com,adinc.co.kr,adinc.kr",
        ",adinch.com,adingo.jp,adinplay.com,adintend.com,adinterax.com,adinvigorate.com,adipolo.com,adipolosolutions.co",
        "m,adiquity.com,adireland.com,adireto.com,adisfy.com,adisn.com,adit-media.com,adition.com,aditize.com,aditms.me",
        ",aditsafeweb.com,aditude.io,adjacenthairshag.com,adjal.com,adjector.com,adjkulzooirq.com,adjoininglearning.com",
        ",adjs.media,adjug.com,adjuggler.com,adjuggler.net,adjungle.com,adjust.com,adjustmentthesilver.com,adjux.com,ad",
        "k2x.com,adkaora.space,adkernel.com,adklimages.com,adklip.com,adkmbc.com,adknowledge.com,adkonekt.com,adkora.co",
        "m,adkova.com,adlane.info,adlatch.com,adlayer.net,adlegend.com,adligature.com,adlightning.com,adlink.net,adlive",
        ".io,adlogists.com,adlook.me,adloop.co,adlooxtracking.com,adlpartner.com,adlserq.com,adltserv.com,adlux.com,adl",
        "zmqouddtim.space,admaaodfwkcuj.store,admachina.com,adman.gr,admana.net,admanmedia.com,admaru.com,admasters.med",
        "ia,admatic.com.tr,admatic.de,admax.network,admaxim.com,admcmscyyhzem.online,admd.ink,admdoqipetokp.space,adme-",
        "net.com,adme2.click,admedia.com,admediaportal.com,admediatex.net,admedit.net,admedo.com,admeerkat.com,admeking",
        ".com,admeridianads.com,admetricspro.com,admicro.vn,admidainsight.com,administerembodiment.com,administersurlyu",
        "nbelievable.com,admirableinstance.com,admirecallpalpate.com,admiredjumper.com,admiregarrisonnetwork.com,admiss",
        "ibleblinkbesiege.com,admissibleconference.com,admission.net,admissiondemeanourusage.com,admitad-connect.com,ad",
        "mitad.com,admittanceskirtanthony.com,admittedhi.com,admixer.md,admixer.net,admjmp.com,admmjpbxebrfu.space,admo",
        "ai.com,admob-cn.com,admob.com,admobe.com,admolo.com,admonishconcentrate.com,admonishfistgipsy.com,admonishment",
        "fastest.com,admonishmenttub.com,admost.com,admothreewallent.com,admpire.com,admulti.com,admunvlvrcsaf.online,a",
        "dnade.net,adnami.io,adnami2.io,adnanny.com,adncdn.net,adnet.biz,adnet.com,adnet.de,adnet.lt,adnet.ru,adnetwork",
        "performance.com,adnetwrk.com,adnext.fr,adnext.pl,adngin.com,adnico.jp,adnigma.com,adnimation.com,adnimo.com,ad",
        "nitro.pro,adnium.com,adnmore.co.kr,adnotebook.com,adnow.com,adnuntius.com,adnxs-simple.com,adnxs.com,adnxs.net",
        ",adobe.com,adocean.pl,adodishonourcrutch.com,adokutrtb.com,adolcgailvycg.site,adomic.com,adomik.com,adonnews.c",
        "om,adonweb.ru,adop.co,adoperator.com,adoperatorx.com,adopexchange.com,adopsboost.com,adopstar.uk,adoptim.com,a",
        "doptum.net,adorableoil.pro,adorableold.com,adorableorchid.com,adorablequestion.pro,adorarama.com,adorika.com,a",
        "dornmadeup.com,adornmenttaunt.com,adosia.com,adotic.com,adotmob.com,adoto.net,adotone.com,adotube.com,adpacks.",
        "com,adparlor.com,adpass.co.uk,adpatrof.com,adpay.com,adpays.net,adpeepshosted.com,adperfect.com,adperium.com,a",
        "dpicmedia.net,adpinion.com,adpionier.de,adplsr.com,adplugg.com,adplxmd.com,adpmbexo.com,adpmbexoxvid.com,adpmb",
        "global.com,adpmbtf.com,adpmbtj.com,adpmbts.com,adpnut.com,adpod.in,adpone.com,adpool.bet,adpopblzuma.in,adport",
        ".io,adport.net,adpredictive.com,adpresenter.de,adprime.com,adprnvjzubygw.online,adpushup.com,adqsqmeyixjaf.onl",
        "ine,adquake.com,adquery.io,adquire.com,adqva.com,adqxchmsttoyz.online,adreactor.com,adreadytractions.com,adrea",
        "lclick.com,adrecord.com,adrecover.com,adrecreate.com,adrenalinemanipulativeact.com,adrenovate.com,adrent.net,a",
        "dresellers.com,adrevenuerescue.com,adrevolver.com,adrianpeachdesign.com,adrifttenderly.com,adright.co,adrino.i",
        "o,adrise.de,adro.co,adrocket.com,adroitham.com,adroitirresponsibleshoes.com,adroitswarmcultural.com,adrokt.com",
        ",adroll.com,adrta.com,adrunnr.com,ads-abc.com,ads-adv.top,ads-bitcoin.com,ads-blocker.app,ads-blocker.pro,ads-",
        "pixiv.net,ads-tracking.com,ads-twitter.com,ads.cc,ads1-adnow.com,ads2550.bid,ads2ads.net,ads3-adnow.com,ads4g.",
        "pl,ads4media.online,ads4trk.com,ads5-adnow.com,ads6-adnow.com,ads7-adnow.com,adsafeprotected.com,adsafety.net,",
        "adsame.com,adsappier.com,adsarcade.com,adsb4trk.com,adsbar.online,adsbetnet.com,adsblockersentinel.info,adsboo",
        "kie.com,adsboosters.xyz,adsbreak.com,adsbtrk.com,adsbuddy.net,adscale.de,adscampaign.net,adschill.com,adscienc",
        "eltd.com,adsco.re,adscoreip.com,adscout.io,adscreendirect.com,adsdk.com,adsdot.ph,adsenix.com,adsensecamp.com,",
        "adsensecustomsearchads.com,adserv.mobi,adserve.com,adserve.ph,adserverplus.com,adserverpub.com,adservicemedia.",
        "dk,adservingfactory.com,adservon.com,adservrs.com,adsession.com,adsessionserv.com,adsexse.com,adsfac.net,adsfa",
        "st.com,adsforallmedia.com,adsgateway.net,adshack.com,adshnk.com,adshopping.com,adshostnet.com,adshot.de,adsidu",
        "ous.com,adsight.nl,adsinstant.com,adsinteractive.com,adsjqaobchetb.space,adskape.ru,adskeeper.co.uk,adskeeper.",
        "com,adskpak.com,adslidango.com,adslivecorp.com,adsloom.com,adslop.link,adslot.com,adslots.com,adslowezlzadr.sp",
        "ace,adsmarch.online,adsmarket.com,adsmart.hk,adsmediabox.com,adsnative.com,adsninja.ca,adsniper.ru,adsoftware.",
        "top,adsolutely.com,adsonar.com,adsparc.net,adspdbl.com,adspector.io,adspeed.com,adspeed.net,adspirit.de,adspla",
        "y.in,adsplex.com,adspop.me,adspredictiv.com,adspruce.com,adspygl.xyz,adspyglass.com,adsquash.info,adsquasher.c",
        "om,adsquasher.pro,adsquirrel.ai,adsratcarp.com,adsreference.com,adsring.com,adsrv.net,adsrv.wtf,adsrv4k.com,ad",
        "srvmedia.com,adsrvr.org,adstargeting.com,adstargets.com,adstean.com,adsterra.com,adsterraserver.com,adstik.cli",
        "ck,adstock.pro,adstoo.com,adstook.com,adstub.net,adstudio.cloud,adstuna.com,adsturn.com,adsupply.com,adsupplya",
        "ds.com,adsupplyads.net,adsupplyssl.com,adsurve.com,adsvert.com,adsvolum.com,adsvolume.com,adswam.com,adswizz.c",
        "om,adsxtits.com,adsxtits.pro,adsxyz.com,adt328.com,adt545.net,adt567.net,adt574.com,adt598.com,adtags.mobi,adt",
        "aily.com,adtaily.pl,adtarget.biz,adtarget.com.tr,adtarget.market,adtdp.com,adtear.com,adtech.de,adtechium.com,",
        "adtechjp.com,adtechus.com,adtegrity.net,adtelligent.com,adteractive.com,adtheyhaveset.org,adthrive.com,adtival",
        ".network,adtlgc.com,adtng.com,adtoma.com,adtonement.com,adtonos.com,adtoox.com,adtotal.pl,adtpix.com,adtrace.o",
        "rg,adtraction.com,adtrafficquality.google,adtrcdn.io,adtrhogpvnwkt.space,adtrieval.com,adtrix.com,adtrue.com,a",
        "dtrue24.com,adtumxjckljej.space,adult-sex-games.com,adult.xyz,adult3dcomics.com,adultadvertising.net,adultadwo",
        "rld.com,adultasianporn.com,adultcamchatfree.com,adultcamfree.com,adultcamliveweb.com,adultdeepfakes.cam,adultf",
        "ilmdatabase.com,adultforce.com,adultfriendfinder.com,adultgameexchange.com,adultimate.net,adultlinkexchange.co",
        "m,adultsense.com,adultsense.net,adultsense.org,adultsjuniorfling.com,adultswim.com,adunblocker.com,adup-tech.c",
        "om,adv-adserver.com,advally.com,advancedadblocker.pro,advancedsystemrepairpro.com,advancingtheirs.com,advansea",
        "ds.com,advantageroughplay.com,advard.com,advcash.com,adventori.com,adventurefeeds.com,adventurehannahmechanic.",
        "com,adventurerforewordpatio.com,adventurouscomprehendhold.com,adverge.ai,adversal.com,adversaldisplay.com,adve",
        "rsalservers.com,adverserve.net,advertica.com,adverticum.net,advertipros.com,advertise.com,advertiserubyproximi",
        "ty.com,advertiserurl.com,advertiseserve.com,advertisespace.com,advertising-cdn.com,advertising365.com,advertis",
        "ingiq.com,advertisoo.com,advertjunction.com,advertlets.com,advertnative.com,advertone.ru,advertserve.com,adver",
        "tsource.co.uk,advertur.ru,advfeeds.com,adviad.com,adviceglossaryeminence.com,advisesaltsarmless.com,advisorthr",
        "owbible.com,adviva.net,advmaker.ru,advmd.com,advmedialtd.com,advombat.ru,advon.net,advotionhot.com,advozcevf.c",
        "om,advp1.com,advp2.com,advp3.com,advpx.com,advpy.com,advpz.com,advservert.com,advsnx.net,advtise.net,adw9.com,",
        "adwalte.info,adway.org,adwebster.com,adworldmedia.com,adworldmedia.net,adwx6vcj.com,adx-t.com,adx.io,adx.ws,ad",
        "x1.com,adxadserv.com,adxapi.online,adxawkejwjdfh.site,adxbid.info,adxfire.in,adxfire.net,adxhand1.name,adxion.",
        "com,adxnexus.com,adxpansion.com,adxpartner.com,adxplay.com,adxpose.com,adxpremium.com,adxpremium.services,adxp",
        "ub.com,adxscope.com,adxsource.com,adxsrver.com,adxvsipicgwop.site,adxxx.biz,adxxxxxmost.co,adyoulike.com,adysi",
        "s.com,adywrlubfplrh.site,adz2you.xyz,adzbazar.com,adzblockersentinel.net,adzcivwjgktmz.site,adzen.ai,adzerk.ne",
        "t,adziff.com,adzilla.meme,adzilla1.name,adzivijnyizgp.space,adzmedia.com,adzmob.com,adzouk.com,adzpier.com,adz",
        "s.com,adzs.nl,aehjiuheifpop.online,aemediatraffic.com,afdads.com,aff-track.net,aff1xstavka.com,affbuzzads.com,",
        "affidavitheadfirstonward.com,affiliate-b.com,affiliate-robot.com,affiliate-wg.com,affiliateboutiquenetwork.com",
        ",affiliateedge.com,affiliateer.com,affiliatefuel.com,affiliatefuture.com,affiliategroove.com,affiliatelounge.c",
        "om,affiliatemembership.com,affiliates.systems,affiliatesensor.com,affiliatesleague.com,affiliatestonybet.com,a",
        "ffiliatewindow.com,affstrack.com,afftrack.com,afftrackr.com,afiadwcsvntpn.space,afishamedia.net,afrikad.com,af",
        "rsportsbetting.com,afterdownload.com,afterdownloads.com,afyads.com,agavstdlunmyb.website,agendadejection.com,a",
        "ggravatingchicken.pro,aggravatingoil.com,agravicsensual.cyou,agvmufccuavus.online,ahadsply.com,aheadpains.com,",
        "ahejadicyivel.site,aim4media.com,aipmedia.com,airlessstereoadvised.com,ak-tracker.com,akavita.com,aknadnauhsmg",
        "u.space,al-adtech.com,alarmsportsnetwork.com,alexatracker.com,alienateparadise.com,alipromo.com,alleliteads.co",
        "m,alleviatepracticableaddicted.com,allmediadesk.com,allosponsor.com,allpornovids.com,alludesexptl.cyou,almight",
        "ypush.com,alpha-affiliates.com,alphaporno.com,alterassumeaggravate.com,alternads.info,alternatewavyrid.com,alt",
        "ernativecpmgate.com,alturaadvertising.com,alumniheadfirstshamrock.com,amateurcouplewebcam.com,amateurporn.net,",
        "amazing-dating.com,amazon-adsystem.com,americanacademyofthermology.org,amesgraduatel.xyz,amididsavola.com,ampe",
        "rwave.net,amsacpcugjc.in,amuletsourcemadden.com,amusedradish.com,anacams.com,analsexstars.com,anastasiasaffili",
        "ate.com,ancestorheadquarters.com,aneadtwrlpqob.online,anguishaddition.com,angularpoppyrobbing.com,anjavjkadvun",
        ".in,anonymousads.com,antiadblock.info,antiadblocksystems.com,antique-average.com,antonythwartlead.com,anyadx.l",
        "ive,anyclip-media.com,anymedia.lv,anyporn.com,anysex.com,aorzbyyrhlavn.site,apbrumavwr.in,apex-ad.com,apophish",
        "oggism.cfd,appendad.com,appetitecamisolereawake.com,apromoweb.com,aqua-adserver.com,arabyads.com,arbattadr.in,",
        "arcadebannerexchange.org,arcadechain.com,arcadiablankdenying.com,archipelagokennelleadership.com,arid-leadersh",
        "ip.pro,arkadiumhosted.com,aromadenotes.cyou,arsesexuded.cfd,arxoplagziavr.site,asaunmavgitw.com,ascendeummedia",
        ".com,ascentlitigateunsaved.com,asdasdad.net,asgadmp.cc,asgadsts.cc,asgclick.com,asgclickpp.com,asian-xxx-video",
        "s.com,asiangfsex.com,askdomainad.com,asktiava.com,asterouhavenev.org,asthmanotchcave.com,athyimemediat.org,atu",
        "be.xxx,auavaxrfgyyrv.online,audiblyacademyadaptation.com,audience2media.com,august15download.com,aurescadrache",
        "n.com,auroraveil.bid,authorizedimmediate.com,auto-ads6.site,autoads.asia,automatad.com,autotrader.ca,autotrade",
        "r.co.za,auyydavghsuox.site,avads.co.uk,avads.live,avalancheofnews.com,avalanchetremorunfilled.com,avariciousno",
        "body.pro,avatrade.io,avatraffic.com,avazu.net,avazutracking.net,avbsqhunnykee.site,avcafzoz.in,avdludlmpseoe.s",
        "pace,avebeentriflin.com,aveherald.com,avelpwnzhdoyx.site,avenidageranic.cyou,avenuenineteenthendorsement.com,a",
        "verage-movie.com,average-statement.com,averinierne.cyou,avestastupendfreit.cyou,avevumywadmrw.website,avfay.co",
        "m,avfbqhwuowien.online,avfdikaldj.in,avgle.com,avgony.xyz,avhlvyyhjhvli.site,avhtaapxml.com,avhxlgougslod.spac",
        "e,aviatedmidair.com,avifwlqraveqt.online,avigatejungle.click,avilionrewrote.shop,avimdxzekvlhe.space,avjecheyb",
        "dtqj.space,avjlllfpqvloi.website,avkauywgzdxlx.online,avkmgwykmovxx.site,avkszbsmzamaj.site,avlmy.com,avmakrav",
        "zqfvj.space,avmmsekplixsk.space,avmviiih.com,avn.com,avnhqqqoljvgf.space,avnqsnekvmpzu.site,avnwqayeafuve.webs",
        "ite,avocams.com,avouchlawsrethink.com,avouchuramil.com,avoutergubbins.qpon,avowdanielungrave.cyou,avowingoxali",
        "teglancer.qpon,avpjihtkjsnfe.space,avplayer.com,avqjufpki.in,avqovczcoanp.com,avsfppmelmkzr.online,avswajharwh",
        "e.in,avszjedalkciq.space,avthelkp.net,avtrrdtzmnmsv.website,avvpsstgoheny.online,avxsduffdiewn.space,avxskbxkm",
        "tawx.space,avylshidkaggoj.com,avypcwqqhuz.com,avytybblrvweh.space,avzaqmaoryehd.website,avzmhurdchosj.space,av",
        "zwgvitrfmsbg.com,avzxcwkpckxyz.space,awakengoaveserasion.cyou,awmradivlamtw.online,awrsfadi.in,axabpelvjfqav.s",
        "ite,axiaffiliates.com,axoxktmvnhtav.site,axupfpniadauk.site,ayads.co,ayadsdyieahbn.space,ayxbetuxafa.com,azadi",
        "fy.com,azoogleads.com,aztadtvrog.in,b677b1ad62.com,babelmedia.global,babesinporn.com,babyboomboomads.com,backb",
        "eatmedia.com,badgegirdle.com,badgertulip.com,badjocks.com,badjojo.com,badlandlispyippee.com,badsllcvgillm.spac",
        "e,badwtokg.com,bagdigravy.com,balzadicyema.com,bancadeltempoidea.org,banner-iframe.com,banneradsday.com,banner",
        "bank.ru,bannerboo.com,bannerbridge.net,bannerconnect.com,bannerconnect.net,bannerdealer.com,bannerflow.com,ban",
        "nerflow.net,bannerignition.co.za,banneroctober.store,banners5html2.com,bannersmall.com,bannersnack.com,banners",
        "nack.net,bannerweb.com,baseporno.com,bavaroylocker.cyou,bavbfmokxwqtn.space,bavdxafbegkvr.space,bavggygpconuq.",
        "site,bavoozimaxiev.website,bc48fa9adc.com,bcaadnibyntzm.space,bckbhadz.com,bcwhnizcbethy.website,bdavjbhjpzjmt",
        ".space,beaconadnetwork.com,beavertron.com,bebraveknob.com,becausepop.com,beead.net,beginsrainbowcamomile.com,b",
        "ehave.com,behavepassword.com,belavoplay.com,beleavenbg.com,bellamylavatory.com,benefit-ads.com,bentadaptation.",
        "com,beringmedia.com,bestadload.com,bestadsforyou.com,bestcontenttrade.top,bestcpmnetwork.com,bestfunnyads.com,",
        "bestonlinecasino.club,bestrevenuenetwork.com,bet3000partners.com,bet365.com,bet365affiliates.com,betads.xyz,be",
        "tafot.com,betahit.click,betaoutinnovations.net,betclever.com,beterrakionan.com,betfair.com,betforakiea.com,bet",
        "force.io,betgorebysson.club,betmasquerainchan.com,betmenaff.com,betoga.com,betotodilea.com,betpartners.it,betp",
        "lays.com,betpupitarr.com,betr-for-tod.com,betrad.com,betrapcb.cfd,betrayalbadger.com,betrayedjoltmethod.com,be",
        "tshucklean.com,betsson.com,bettentacruela.com,betteradsystem.com,betterbonnet.pro,bettercollective.rocks,bette",
        "rdatabase.pro,betterdomino.com,bettingcurly.com,bettingoffer.xyz,bettingpartners.com,bettingpremier.com,bettra",
        "ff.com,between-legs.com,betweenbrr.com,betweendigital.com,betweenprimarily.com,betwinner1.com,betzillopartners",
        ".com,betzone2000.com,beutavpysjojf.site,bexkpopzujdus.space,bf-ad.net,bgwsktadxvgjv.website,bhabhiporn.pro,bha",
        "vkiyrxbifv.space,bhwgdvpavsfzw.site,biadglomdzyys.online,bicyclebusgrave.work,bidclickmedia.com,bidderads.com,",
        "biemedia.com,bigboxads.com,biodegradablegrand.com,biqadnwzega.in,bistredchavercaption.qpon,bit-ad.com,bitadexc",
        "hange.com,bitcoadz.io,bitmedia.io,bitonclick.com,bitspush.io,bizonads-ssp.com,bizzclick.com,bjuojadykz.com,bkg",
        "wadxzovl.com,bkjblyuukoavf.site,blacksexmatch.com,bladdersdisarmaware.com,bladderssewing.com,bladeamendable.co",
        "m,bladedescendant.com,bladespillowcase.com,bladswetis.com,blamads.com,blarcamxooysi.store,blaze-media.com,blin",
        "kedlanentablelanentableunavailable.com,blinksbobblepadding.com,blismedia.com,block-ad.com,blockadsnot.com,bloc",
        "kchain-ads.com,blogads.com,blogherads.com,blue-ribbonmacadamizeprovide.com,bluekai.com,bluepromocode.com,bluer",
        "unningshadow.com,bluetoad.com,blwadijbvbttx.space,bmfads.com,bmmads.com,bngmadjd.de,bngpop.com,boffoadsfeeds.c",
        "om,bonad.io,bongacams.com,bongacams10.com,bongacams11.com,bongacams7.com,bongacams8.com,bookadil.com,bookbanne",
        "rshop.com,bookofsex.com,boomads.com,boostads.net,boreaddingobserve.com,boskodating.com,bounceads.net,bovinetim",
        "ingunsaddle.com,bovmkbuiaddbb.website,boxlikepavers.com,bqszugpxadzlg.store,brainlyads.com,brave.com,bravefork",
        ".com,braverybreezebinding.com,braveryriftraining.com,bravoteens.com,bravotrk.com,bravotube.net,brazzersnetwork",
        ".com,breadmljlfdwx.site,bridgetrack.com,brigadedelegatesandbox.com,brightadnetwork.com,britainbetrayalbands.co",
        "m,broadensilkslush.com,broadlyjukeboxunrevised.com,broadsheetspikesnick.com,broadsidedaisysanta.com,broadstree",
        "tads.com,brodownloads.site,broggleceburbetire.qpon,brothypopuli.com,bruceleadx1.com,brxqlwrzcpcvx.website,bsad",
        "cmesynzyb.store,bskuxhcaduumi.online,btagmedia.com,btccasinoexpert.com,bthtcgwaniavh.website,btpnav.com,btpopo",
        "ogrnqvj.site,btprmnav.com,bttrack.com,btxxxnav.com,bucketsofbanners.com,budadymskizcs.online,buffetaccommodati",
        "ng.com,bukvwavcmonjv.space,burstyflavia.com,businessclick.com,butwehaveino.com,buyadvupfor24.com,buysellads.co",
        "m,buysellads.net,buzzadexchange.com,buzzadnetwork.com,buzzonclick.com,buzzparadise.com,bwqfyjaynjpad.site,bwwu",
        "veplotadr.site,byumhwhapayad.space,byxxxporn.com,bzltavhalapzv.online,bztzpzqpltlad.site,c21media.net,c4tracki",
        "ng01.com,ca.ijknabd.top,cachuadirked.top,cactusheadroomscaling.com,cadencebenefit.com,cadsecs.com,cadsimz.com,",
        "cadskiz.com,cadwgsofoyxky.site,calombotubelet.com,cam-content.com,cam-lolita.net,cam4.com,cam4flat.com,cam4tra",
        "cking.com,camailwhickerruddles.cyou,camaligraia.cyou,cambaddies.com,cambay.tv,cambioturkish.com,camcaps.ac,cam",
        "caps.io,camcaps.to,camcrush.com,camdough.com,camduty.com,camelusnakulabiotite.cyou,cameoedexcuse.com,camghosts",
        ".com,camhub.cc,camiocw.com,camliveshow.xyz,cammpaign.com,camomilegentlemennotable.com,camonade.com,camonster.c",
        "om,camonster.io,camouflagemm.com,campaignlook.com,campanaoutgrin.cyou,campartner.com,campenosine.qpon,campfire",
        "croutondecorator.com,camph.org,camplacecash.com,camptwined.com,cams.com,cams.gratis,camsaim.com,camschat.net,c",
        "amsense.com,camshq.info,camsitecash.com,camsoda.com,camsoda1.com,camspacelive.com,camsplanetlive.com,camterest",
        ".com,camvideos.tv,camzap.com,candyaversion.com,candynetwork.ai,cantorspaver.com,caravanexhale.com,carbonads.co",
        "m,carbonads.net,carpalsgeneuradiiamb.qpon,cartoonporn.to,casalemedia.com,cash-ads.com,casinobonusdeals.io,casi",
        "nohacksforyou.com,casinousagevacant.com,casiyouaffiliates.com,casserolerehabilitationgradual.com,casumoaffilia",
        "tes.com,catchtheclick.com,caveletganoine.qpon,cavergalere.com,cavernsinaite.com,cavewrap.care,cavuscecilydvorn",
        "ik.cfd,cavvfjxabrffj.website,cavzgoqiozmyc.space,cbhadfiwcdgsl.space,ccjzuavqrh.com,cdn-adtech.com,cdn-adtrue.",
        "com,cdn4ads.com,cdnads.com,cdymqigrkwzav.site,ceasedaggravation.com,celeb-ads.com,celebjihad.com,celebjihad.li",
        "ve,cellaradorn.com,cerxxxwjtxebr.site,cfivfadtlr.com,cgpjpvvcadzrp.store,chabadokor.com,chainads.io,chalazatav",
        "erns.com,chandrabinduad.com,changedadroitharassment.com,charltonmedia.com,cheemsporn.com,chiatlaadovus.website",
        ",chihuahuadoorstoppond.com,chikiporn.com,chipleader.com,chpadblock.com,chymiadolors.com,chzhqwirsxmad.online,c",
        "ibleclick.com,cieapkjfmavqd.site,cinemadeck.com,circaeavamper.com,circlejavgg124.fun,citadelexampleruckus.com,",
        "citadelpathstatue.com,citizenshadowrequires.com,citrusad.com,city-ads.de,cityadspix.com,cjgimadfnuztn.online,c",
        "jlmffavyoprm.site,ckiqxrqwnavqt.website,ckwouavsimupd.space,cladupius.com,clash-media.com,clavetriple.cyou,cla",
        "vuvirameses.com,clbanners16.com,cleanmediaads.com,clearadnetwork.com,clearonclick.com,cleavebullydevaluation.c",
        "om,cleverads.vn,clicadu.com,click-cdn.com,click-for-preview.com,click4.pro,clickable.com,clickad.pl,clickadill",
        "a.com,clickadsource.com,clickadu.com,clickadu.io,clickadu.net,clickagy.com,clickaine.com,clickallow.net,clickb",
        "ank.net,clickbet88.com,clickblitzo.com,clickbooth.com,clickboothlnk.com,clickcash.com,clickcdn.co,clickcertain",
        ".com,clickco.net,clickdaly.com,clickdescentchristmas.com,clickexperts.net,clickfilter.co,clickfunnels.com,clic",
        "kfuse.com,clickinc.com,clickintext.com,clickintext.net,clickiocdn.com,clickmagick.com,clickmediahub.com,clickm",
        "etertracking.com,clickmi.net,clickmobad.net,clickmon.co.kr,clicknano.com,clicknerd.com,clicknplay.to,clickopop",
        "1000.com,clickoutcare.io,clickoutnetwork.care,clickpapa.com,clickpoint.com,clickppcbuzz.com,clickprotects.com,",
        "clickpunch.icu,clickpupbit.com,clicks4tc.com,clicksgear.com,clicksor.com,clicksor.net,clickterra.net,clickthru",
        "host.com,clickthruserver.com,clicktimes.bid,clicktoupdservice.online,clicktraceclick.com,clicktripz.com,clickt",
        "rixredirects.com,clickupto.com,clickwhore.com,clickwinks.com,clickwork7secure.com,clickxchange.com,clinicadent",
        "almariangeleslazcano.com,clivetadds.com,clladss.com,clobberprocurertightwad.com,clockwiseleaderfilament.com,cl",
        "oudbet.com,cloudcinemaheavily.com,cloudtrack-camp.com,clrqbqtonradt.online,clusiavacuo.qpon,cmadserver.de,cmfa",
        "ds.com,cnptuyxjmdave.site,cocambgwyeeym.website,cockpitadministration.com,codeonclick.com,coffeavirify.com,coi",
        "nad.in,coinad.media,coinads.online,coinadster.com,coinmedia.co,collectbladders.com,comediandesired.com,comfort",
        "click.co.uk,comicad.net,commiseratebeamingbarricade.com,commonwealthgroovemadam.com,compassionatespreadinquire",
        ".com,complexnetwork.pro,comradegoodsfloor.com,concealtraveller.com,conciergeradialsupernova.com,condescendinga",
        "veragerain.com,conditioneavesdroppingbarter.com,conduit-banners.com,connectad.io,connectingadjusthospitality.c",
        "om,conscientiouscabbageadrift.com,constantlyauthoritativegradually.com,containingstripesadmonish.com,contempla",
        "tepushed.com,contemplatewaryheadquarter.com,content-loader.com,contentcave.co.kr,contentclick.co.uk,contextads",
        ".live,contextuads.com,continue-download.com,contributorshaveangry.com,controlslaverystuffing.com,conxxx.pro,co",
        "okpad-ads.com,cooladblocker.info,coolerads.com,coolestinstead.com,cooperativepop.com,coordinatedwave.pro,corea",
        "dness.com,cosponsorgarnetmorphing.com,counciladvertising.net,coursejavgg124.fun,courthousecadenceconsiderable.",
        "com,couveaverral.cyou,covivado.club,coxmavefzvnqwl.com,cpaclickz.com,cpalabtracking.com,cpalead.com,cpcmart.co",
        "m,cpcstar.com,cpm.biz,cpm20.com,cpmadvisors.com,cpmbugaboo.com,cpmclktrk.online,cpmleader.com,cpmmedia.net,cpm",
        "profitablenetwork.com,cpmrevenuegate.com,cpmroi.com,cpmspace.com,cpmstar.com,cpmterra.com,cpmtree.com,cpuzspop",
        "etlsg.space,cpvadvertise.com,cq.jqygnbfq.top,cqrksadeerzsp.space,cqvxrmwcwyvad.site,craaledcamistutu.qpon,crad",
        "aygo.com,crakbanner.com,crakmedia.com,crampleadspace.com,craniavestas.com,crannyadministervolunteer.com,craves",
        "gage.cyou,crazyporn.xxx,crbetfcijuhtw.site,creativeformatsnetwork.com,crihbeppscadw.site,crispads.com,criteo.c",
        "om,criteo.net,crockadilla.com,crocotube.com,crowderleavens.cyou,crowdsubsequentadmire.com,crpdadadgogyh.site,c",
        "rtracklink.com,cruhtravbsuio.space,crwdcntrl.net,crypto-ads.net,cryptobeneluxbanner.care,cryptocoinsad.com,css",
        "-load.com,cstwpush.com,csxbezadpeazu.site,ctebiooadmfty.space,ctm-media.com,cuisineenvoyadvertise.com,cuoieadr",
        "raxle.space,cupis-lady-dreampartner.com,cursecrookadvantage.com,customads.co,cvyadqizofrsw.space,cylindrical-w",
        "ave.com,czgacpadsspuj.space,czhavjzsxkcwa.space,czhjjavbyhuun.online,czuadkhcudkl.com,czuoszwava.in,d58ad0184a",
        ".com,da-ads.com,daadpefptsdqv.online,dacqfadozkvie.site,dadaejghzcnaq.site,dadmivxze.in,dadqcuxqdnecx.space,da",
        "ds-banging-teens.com,dadsats.com,dadsimz.com,dadsoks.com,daftporn.com,dailyporn.club,dakafxgnqcpmm.website,dal",
        "lavel.com,damagedbehaved.com,dantaradiohd.net,dargopastinadogsled.cyou,dark-reader.com,darknessporn.com,date4s",
        "ex.pro,datedeskads.com,datetrackservice.com,dating-amour-club.com,dating-honey-girls.com,dating-honeygirles.co",
        "m,dating-masters-haven.com,dating-service.net,dating2cloud.org,datingadvicefree.com,datingamateurs.com,datinge",
        "ro.com,datinggold.com,datinghoneygirls.com,datingmeetnet.com,datingprudethimble.com,datingshall.life,datingssp",
        "ads.com,datingstyle.top,datingtoday.top,datingtopgirls.com,datingtorrid.top,datingwithmee.net,datoporn.com,dav",
        "incisgold.com,davipkybnzfji.site,davtwymjlx.com,davynetriumf.com,dbdhnavwbvhep.website,dboccfzxlavyp.online,dc",
        "raddock.uk,dcssptrack.com,ddownload.com,de2bdad928.com,dead-put.com,deadelegantannotation.com,deadlinescreechv",
        "isit.com,deadlyfeasible.com,decknetwork.net,decorateadverb.com,dedating.online,dedicatedmedia.com,dedicatednet",
        "works.com,deductgreedyheadroom.com,deeavouter.com,deepxtube.com,deltabravo.net,demandingoverdriveunthread.com,",
        "demdex.net,deofleadyetca.org,dependantcarefullyheavenly.com,dependentadequatemyth.com,depleteheadphoneshoe.com",
        ",deployads.com,depriverippledecadence.com,depwwdntgavyc.website,despoteavesdropblazing.com,detectbravecomfy.co",
        "m,developermedia.com,dextralravi.cyou,dgafgadsgkjg.top,dggazqmcpmtwc.website,dgzykxavrvekj.site,dhovqdnulcadz.",
        "site,digipathmedia.com,digitaladvertisingalliance.org,digitalmediapp.com,digitalmediaworld.tv,digitalpush.org,",
        "diningfreshmansavage.com,dinsbetrlsiqc.space,dinzfafad.in,directaclick.com,directadvert.ru,directcpmfwr.com,di",
        "rectnavbt.com,disableadblock.com,discoveredcowardlymadden.com,discretioningratitudeadvisedly.com,disgustingmad",
        ".com,disneyadvertising.com,disparagewardenbetrayal.com,displaycontentnetwork.com,displaynetworkcontent.com,dis",
        "playnetworkprofit.com,disqusads.com,distractiontradingamass.com,dixyporn.com,djncmafidfcpm.online,dmzadtwonmlf",
        "n.online,dnavexch.com,dobbyporn.com,dollyspreadingbeer.com,domainadvertising.com,domesticatednavigationgrip.co",
        "m,domipush.com,doodlesunshinecamp.com,dopaleads.com,doseofporn.com,doubleadserve.com,doubleclick-cn.net,double",
        "click.com,doubleclick.net,down-paradise.com,download-file.org,download-performance.com,downloadboutique.com,do",
        "wnloaddecidedly.com,downloadoperagx.com,downloadyt.com,downstairspoppy.com,dpfwqzjkavjmb.site,dporn.com,dqhepo",
        "cpcwspj.space,dradvice.in,dragstergibletshaded.com,draviteinosine.com,dreadedrevisablevest.com,dreadfullyemula",
        "teconservation.com,dreadfullynormallyinsist.com,dreadfulrulingextension.com,dreamteamaffiliates.com,droghvadut",
        "sov.space,dropdeadgems.org,dropkickmedia.com,droughtpavidsoths.cyou,drsmediaexchange.com,drtuber.com,dsadghrth",
        "ysdfadwr3sdffsdaghedsa2gf.xyz,dtadnetwork.com,dtyamiadjnapm.space,dtzvavrnbzemd.space,duchesspleasurelollipop.",
        "com,dulvxkusxdcpc.online,dunderaffiliates.com,dunhilltraveldeals.com,dupsavour.com,duregqgfvravp.space,duxlpoa",
        "vm.in,dvwrcpmeeozgq.site,dvzycytrxbxav.space,dwlladiupirrc.site,dymqtojvadily.site,dynad.net,dz4ad.com,dziorhn",
        "av.in,dzubavstal.com,e7e0addf05.com,ea-tube.com,eadeuifduvres.site,eadsrv.com,eadziukwqzeeb.space,earatikvavrs",
        "a.site,earbossysavvy.com,earringmavin.cyou,easy-ads.com,easy-dating.org,easyad.com,easyadsapp.com,easyporn.xxx",
        ",eavakddcdofik.site,eavesdropbogusye.com,eazyleads.com,ebannertraffic.com,ebayadservices.com,ebetoni.com,ebfcl",
        "hdkkmeav.store,eboglvqdtadmz.space,ecknbonfiftad.website,eclick.vn,ecxufxavldnpd.site,ed49f13aad.com,edcuaqnhi",
        "oavg.com,editorovertimeradiation.com,edshookherhead.org,edzusegqavqik.store,eekjdpreeghad.com,effectivecpmcont",
        "ent.com,effectivecpmgate.com,effectivecpmnetwork.com,effectivecpmrate.com,effectivegatecpm.com,effectiveratecp",
        "m.com,eforebuthehadsta.org,efrwdjavjkiqw.online,egadvertising.com,egotisticexcavateplywood.com,egyihjgwfzadc.s",
        "ite,ehaveagoldenbed.org,ehszycpcxrskg.website,ejadhobthckao.site,ekvhgfruqyadw.site,eladove.com,elasticad.net,",
        "elatedcamera.com,eleavers.com,elephant-ads.com,elliottwave.com,ellipticaltrack.com,elntlrutxdavp.online,elogia",
        "do.info,elsespeakingbetraying.com,elutessheavednude.cyou,embraveanderun.com,emediate.dk,emergencyadditions.com",
        ",eminentcleaveproduces.com,emjmeadlsjrho.online,emqyssutavqxc.online,enbpnlaiatvav.space,encoresweaved.com,eng",
        "ineladleworn.com,enporn.org,entertainenslave.com,eociugytvzhad.website,eoievavdtucqs.online,eonsmedia.com,epbr",
        "wsksexzem.site,ephesiansavvyinto.com,epicclicks.net,epicgameads.com,epidermispushupvariable.com,eporner.com,ep",
        "xniaadddb.com,eqads.com,eradisciplinemomentary.com,ergonomicparadeupstroke.com,erhlyvjhetcad.online,ero-advert",
        "ising.com,eroadvertising.com,erotikdating.com,errandchavish.cfd,eruptstarapinbetrail.cfd,erwknxujxxxbh.space,e",
        "sawbynaavjmi.online,escalatenetwork.com,eskarsunleavesmokier.cyou,essayads.com,estrelabetpartners.com,ethereum",
        "ads.com,ethicalads.io,etjrkgaqrkfad.space,eudgjelqtadcq.website,euro4ads.de,euros4click.de,evadereprimand.com,",
        "evadesickness.com,evasiuywadnox.space,everesttech.net,everydayporn.co,evmdamxavzsgk.website,evolvemediallc.com",
        ",ewesmedia.com,exactshaver.shop,excavatedefeatedshudder.com,excavatenearbywand.com,exchange4media.com,excitead",
        ".com,exjzuoukavbnv.space,exnesstrack.com,exoclick.com,exoticads.com,explorads.com,extensions-media.com,extensi",
        "vetravelled.com,externalfavlink.com,extinguishbethpasta.com,extqoqykkcpmo.space,extremescatporn.com,eyeofporn.",
        "com,eyeshadowkeynote.com,eyeviewads.com,eyqsrrwmvhavp.website,ezadblocker.com,ezdownloadpro.info,ezvthdrknbfad",
        ".space,f30fpad7.xyz,f85fd721ad.com,fa85cadfb0.com,faceporn.com,fadegranted.com,fadexplosion.com,fadppvjumphyh.",
        "online,fadsipz.com,fadskis.com,fadslimz.com,faithfullyreadiness.com,fallinmatches-paradises.com,fam-ad.com,fam",
        "ilyporner.com,fancytravelingrid.com,fantasticbet.com,fapnado.com,fapnado.xxx,fastclick.net,fastnativead.com,fa",
        "stwavehilltop.com,fattenspopulum.com,favigirningnonly.cfd,favmaqyqgcirx.space,favorite-mine.com,favoriteinside",
        ".pro,favosevergery.cfd,favousshakha.cfd,favusraband.com,favy.cam,fbetlixgee.eu,fbmedia-ckl.com,fcqrzagadhlvn.o",
        "nline,fdadmvbteroqf.space,feadrope.net,feckfulmisaver.com,feed-ads.com,feedbackadmiralfortnight.com,feedyourhe",
        "admag.com,fellowearnwave.com,fenacheaverage.com,ferntravelleddeduct.com,festaporno.com,festtube.com,feudalbetr",
        "ayalcast.com,ffnetbeteeguu.site,fgvowyavmmcau.site,fiddlestructureparadise.com,file-upload.net,filestube.com,f",
        "ilingcarnivorousexclaim.com,financeads.net,financialfadinglobster.com,financialgobetweendancer.com,findbetterr",
        "esults.com,findcouponspromos.com,finddatinglocally.com,fiorewonyhade.online,fireworkadservices.com,fireworkads",
        "ervices1.com,fireworkswad.com,firstclass-download.com,firstload.de,fishermanwithavenge.com,fixavcha.in,fixionm",
        "edia.com,fkdeavpxkjuoa.space,flagads.net,flairadscpc.com,flashadtools.com,flat-ads.com,flaviar.com,flavidwarwo",
        "lf.cfd,flavoryjehus.com,flirtclick-matches.com,flower-ads.com,fluavildemist.com,fluemenavid.qpon,fluxads.com,f",
        "lyads1.com,flyingadvert.com,flymyads.com,fmsads.com,footballtradedirectory.com,footztube.com,forbesresistread.",
        "com,foremedia.net,forestysavory.qpon,forfrogadiertor.com,forgavedisciplinetolerance.com,forgivenbeavervague.co",
        "m,forkconnectinghead.com,foxpush.io,fpadserver.com,fqadcwyr.com,fractionsadly.com,fracturebroadcastsaw.com,fre",
        "akads.com,free-datings.com,freeadblockerbrowser.com,freeadultcomix.com,freecamsexposed.com,freedomadnetwork.co",
        "m,freefromads.com,freefromads.pro,freelivesex.tv,freelygreatestscammer.com,freeonlinegayporn.com,freepopnews.s",
        "kin,freeporn8.com,freepornxxx.mobi,freepublicporn.com,freesexcam365.com,freeuseporn.com,freewebcams.com,freshf",
        "orestroad.com,freshpops.net,frivol-ads.com,frnaqbeavtdvm.website,fryhzcadnypnn.online,fs-loader.com,fuck-datin",
        "g.com,fuckbookdating.com,fufrbtjgadkncr.com,funnedravage.com,furiousexpansion.com,fusetracking.com,fusionads.n",
        "et,fusterquaver.com,futureads.io,fxavdiifzfjbw.website,fxgzkkroeavcj.space,fxrokhvydhadu.website,fxxavsxmuovyd",
        ".website,fyauuswqwavou.website,fytbadthewihr.store,gacurad9559.net,gadgetfortunatelysunny.com,gadgets360.com,g",
        "adlt.nl,gadmagckfy.com,gadnsohyud.com,gadsabs.com,gadsatz.com,gadslimz.com,gadspms.com,gadspmz.com,gadssystems",
        ".com,galaxypush.com,galleries-pornstar.com,gambadudman.com,game-clicks.com,gameads.io,gamer-network.net,gamers",
        "ad.com,gaming-adult.com,gamingadlt.com,gamingadult.com,gandrad.org,gapwadfcmeilz.space,gasolineimproveabroad.c",
        "om,gatetotrustednetwork.com,gaveastound.com,gavedeliverknee.com,gavnuifk.xyz,gavotsalleleu.com,gay4porn.com,ga",
        "yadnetwork.com,gayadpros.com,gayporntube.com,gazeflavour.com,gbads.net,gbcamahjbcsff.site,gcpcvtdsdztbz.websit",
        "e,gcpczbniuuuoo.site,gdsavcfiitlxs.online,gearcambridge.com,geckad.com,geeunlessarcade.com,genesismedia.com,ge",
        "niad.net,geniusonclick.com,geoaddicted.net,georgianacaravanscoop.com,germaniavid.com,getflowads.net,getjad.io,",
        "getmyads.com,getpopunder.com,gettubetv.com,gfadvpljfwagd.online,gfesbjnadvxrb.online,ggbet-online.net,ggbetapk",
        ".com,ggbetpromo.com,ghepxcvmadltj.space,giantaffiliates.com,gibedencave.cfd,giftornado.com,girls-romancehaven.",
        "com,gitads.dev,gjkrwpafad.com,gkvfndkdzavrv.online,glade-static.online,gladlydotselfevident.com,gladlythattoad",
        "y.com,glaoxxxppcvwz.site,glkadcvafpqhe.online,global-adblocker.com,globaladblocker.com,globaladblocker.net,glo",
        "baladmedia.com,globaladmedia.net,globaladsales.com,globaladv.net,globtube.com,gloporn.com,glsfreeads.com,gmads",
        ".net,gmmads.com,gmpavcpqcdhxu.space,gnawpush.com,gnopfoaddfxto.website,gnxnfkdgadbkf.online,go-g3t-push.net,go",
        "2bdmbet.com,go2media.org,goads.pro,goadserver.com,goclicknext.com,godaddy.com,godlesscourieradmirable.com,gofd",
        "tdxdtzkav.site,good-ads-online.com,goodadvert.ru,googleads-cn.com,googleadservices-cn.com,googleadservices.com",
        ",googleadsserving.cn,googlesyndication.com,googlevads-cn.com,gotibetho.pro,gotrackier.com,gotrackier.io,gourme",
        "tads.com,gpsecureads.com,gqavlsyfgmkeb.online,graciamediaweb.com,gradualtelevision.com,graduateamazingly.com,g",
        "raduateangeles.com,graduatedfreezing.com,graduatedoutside.com,graduationpaltryherbal.com,gradwcntoprvn.online,",
        "granddadfindsponderous.com,grasshoppergainfulexcavator.com,gravegrandmother.com,gravelsemesterflourish.com,gra",
        "velspeorianstime.cyou,gravelyjovialelemental.com,gravenesswomanlesszestfully.com,gravenpicked.com,graveunivers",
        "alapologies.com,gravicjibbs.com,gravitationtaxpayer.com,greenadblocker.com,greenads.org,gregalemedials.qpon,gr",
        "ievecamouflage.com,grimytradition.pro,gripdownload.co,gripping-bread.com,groovinads.com,gross-ladder.pro,grvme",
        "dia.com,guacosravaka.com,guadscfwaweae.site,gunzblazingpromo.com,gupload.xyz,guvrwavupliw.in,gvcaffiliates.com",
        ",gwalaissexruj.site,gwdkavydmyuf.com,gwhbladbytxd.com,gyoqtcogunavk.site,h12-media.com,haadqzvaencuq.online,ha",
        "dronid.net,hadsanz.com,hadsecz.com,hadsokz.com,haffnetworkmm.com,hairdressergraduate.com,halogennetwork.com,ha",
        "rborjavgg124.fun,hardbritlads.com,harrenmedianetwork.com,hasaeblmtcadr.website,hattedraved.com,hauledquavergua",
        "rdian.com,haveameet.com,havefiliate.com,havelberme.qpon,havenclick.com,havenyl.xyz,haverdentata.cfd,havetohave",
        ".com,havingunderwear.com,havpcbrpaftjv.online,havstpqgejqbw.site,hazelstrictlyheadless.com,hcjzvzvavb.com,hddr",
        "fyoifiuad.website,hdkbxadnbxvua.online,hdopxenavimwu.online,hdporn24.org,hdpornfree.xxx,hdpornmax.com,hdpornph",
        "otos.com,hdtube.porn,hduolaveybzmo.site,headacheaim.com,headbidder.net,headerbidding.ai,headerlift.com,headlin",
        "esplus.com,headphonedilution.com,headquartersfallclassify.com,healthtrader.com,heardspadedime.com,heateradviso",
        "raplenty.com,heathertravelledpast.com,heathsleavenlinaloe.cfd,heavenfull.com,heavengabbykatcina.cyou,heavenly-",
        "holiday.com,heavenmedia.com,heavenomitzoom.com,heavenspecificallygait.com,heavenstyler.cyou,heavepacepallor.co",
        "m,heavilyaflareknifes.qpon,heimalesssinpad.com,heldsadness.com,heliumads.com,hellporno.com,helpfulavenue.com,h",
        "erexxxtube.com,hetadinh.com,hetlmedia.com,hfblxccnycadt.website,hhbetevelthyg.site,hicpm10.com,highcpmcreative",
        "format.com,highcpmgate.com,highcpmrevenuegate.com,highcpmrevenuenetwork.com,highperformancecpm.com,highperform",
        "ancecpmgate.com,highperformancecpmnetwork.com,highprofitnetwork.com,highratecpm.com,highrevenuecpm.com,highrev",
        "enuecpmnetwork.com,highrevenuenetwork.com,highwaycpmrevenue.com,hilltopads.com,hilltopads.net,hiltonbett.com,h",
        "imediads.com,hiprofitnetworks.com,hitcpm.com,hityyzyxbadim.online,hjvewzbtkbetn.online,hmadkixrebk.in,hmdypadv",
        "colcw.space,hnbanvxtkavun.site,hnntube.com,hntixkyrwyiav.website,hnzjwtcajadzi.space,hoadaphagoar.net,hoasvvty",
        "avmbn.store,hockeyhavoc.com,hodlwvavzwt.com,hodnjqyavomuj.site,hollow-population.pro,holyfamilyradio.org,home-",
        "xxx-videos.com,homejavlive.com,homemade.xxx,honestbroadcasting.com,honestinfuriatemedia.com,hooliganmedia.com,",
        "hopcleftirradiate.com,horse-racing-affiliate-program.co.uk,hostadsmarkets.com,hostave.net,hostave2.net,hostave",
        "3.net,hostave4.net,hot-bbw-tube.com,hot-sex-tube.com,hotadultvids.com,housejomadkc.com,houtoucavus.cfd,hpadjpt",
        "cyyafh.space,hpdipavuplwoe.website,hqporn.su,hqpornstream.com,hraayzvvjlead.site,hrlvadtkfqils.space,hrrlwaavr",
        "ttks.online,hryaavcnugcpp.site,hsefhnuodtav.com,html-load.cc,html-load.com,huavdntfzhprm.online,hueads.com,hue",
        "adsxml.com,huluads.info,hunterlead.com,hurlmedia.design,hustlereadcoffeehouse.com,hvwoeavjjemhq.site,hwepopmhh",
        "sz.com,hwpnocpctu.com,hyenadata.com,hyhavaflmeguk.site,hyperbanner.net,hyperpromote.com,iadbsntna.com,iadnsamq",
        "heemb.space,iaeaadfleusbk.site,iajlxlnkkocav.space,ianoubcdoavip.website,iasbetaffiliates.com,iavlxljzdclmz.sp",
        "ace,iavszerhxigkh.space,ibannerexchange.com,ibrapush.com,iceporn.com,iclickcdn.com,icyporno.com,idealmedia.io,",
        "idevaffiliate.com,idhoyoadekuzf.website,idreammedia.com,ieecfjcwadvrz.online,ieladhfhoiclv.online,ifadnfhgaozl",
        "o.store,igoraodadkwlk.space,iiasyavgjqwat.site,iionads.com,ijiyhavkjzvi.com,ijpopvca.com,ikqyazeadzqvo.space,i",
        "leuadlrdnpow.space,ilxilmadwxfah.space,imageadvantage.net,imbuemadeup.com,imediaaudiences.com,img-load.com,img",
        "adult.com,imgkdetaddxre.space,imiclick.org,immediateequivalentstunning.com,immediatelydoghead.com,impact-ad.jp",
        ",impactradius-go.com,impactradius.com,imperfect-dead.pro,imprasavariac.com,impressexaltsculptor.com,imyanmarad",
        "s.com,in-appadvertising.com,in-page-push.com,in-page-push.net,inadequatebuoythin.com,inadequateconsolation.com",
        ",inadmissibleeyewitness.com,indiadesire.com,indianbannerexchange.com,indieclick.com,indigestiontreatmentgradua",
        "ted.com,indlyment-stuador.com,indofad.com,indoleads.com,indyadvertising.com,inexpedientlads.com,infectiousmedi",
        "a.com,infinite-ads.com,infinityads.com,influads.com,infobetting.com,informedcleave.com,inheritedgeneralrailroa",
        "d.com,inhousead.net,initiallybrigadebookstore.com,inkingabave.cfd,inktad.com,inliersilicamvalyl.cyou,inpage-pu",
        "sh.net,inpagepush.com,inporn.com,inscribenaiveavoid.com,insightadz.com,insightspendadvancement.com,insitepromo",
        "tion.com,inskinad.com,inskinmedia.com,instantbannercreator.com,instantpaydaynetwork.com,insteadempty.com,inste",
        "adjell.com,insteadmodification.com,instinctradiatorrook.com,insurads.com,intellibanners.com,intelligenceadx.co",
        "m,intellipopup.com,interactiveads.ai,interadv.net,interclick.com,intermediaryquay.com,intermediategillsevent.c",
        "om,internationalsexguide.nl,interworksmedia.co.kr,intimidatingsideway.com,intopicmedia.com,intporn.com,intrapr",
        "omotion.com,intravert.co,invadedisheartentrail.com,invadermonks.com,inviziads.com,inxxx.com,ioadserve.com,iohg",
        "ttbethmaj.online,iohsoavvddvmo.store,iomaduexiqtho.space,iononetravoy.com,ip-adress.com,ipornia.com,ipromote.c",
        "om,ipvhqdchweavs.site,irbnydavspxxt.space,irfgithdpoadi.site,ironendeavour.com,irontube.net,irradiatestartle.c",
        "om,iseawave.com,itsmillionlead.com,iuzvusqotkhad.online,ivstracker.net,iyqtpifqeoavq.site,iyrrtosjcravm.online",
        ",izavjtqqznucm.site,jaavnacsdw.com,jacaminfley.com,jacamitympana.qpon,jackpotave.com,jackpotcitycasino.com,jad",
        "center.com,jaded-actor.com,jaded-wing.com,jadedsingle.com,jadedtwist.com,jadehlbswdlqw.online,jadeinvitelawsui",
        "t.com,jadjkuofzwad.com,jadkuwwtyp.in,jads.cc,jads.co,jadwidal.com,jagisjbavpwdj.space,jamanetwork.com,jangonet",
        "work.com,jarvispopsu.com,jav-bukkake.net,jav.sb,java8.xyz,javascriptcdnlive.com,javbucks.com,javdock.com,javen",
        "glish.net,javfor.tv,javgg.eu,javgg.me,javhd-trk.com,javhd.com,javhdporn.live,javhub.net,javideo.net,javkagiitf",
        "kuw.site,javlibrary.com,javmg.com,javmix.tv,javmost.com,javpornclub.com,javpwyyvcoorg.website,javslon.com,javx",
        "xx.me,javynow.com,javyvkzwwsf.com,jbckwavvvsugv.space,jdfhwxboadwvg.online,jdrfaqinvrcpm.space,jeetyetmedia.co",
        "m,jegiopad.top,jegjuitgvavuz.space,jespwdvojaavj.online,jetavbemsmnur.space,jewellerybroadlyisabella.com,jewis",
        "hadgroup.com,jewishcontentnetwork.com,jfoazkavnozuo.online,jghadikzlvvnr.space,jiawavgaksdbq.store,jicamasmisc",
        "rop.qpon,jilioadvrvpwm.site,jitterendeavour.com,jjqlzrxadgi.com,jmadyknjxonua.website,jmkladoihjrsa.space,jnqb",
        "kpsymoads.site,jolrfeqxyavvj.site,jovialcadence.com,joxaviri.com,jpadxrydoiorz.online,jpavgtzkbjpwi.site,jpbph",
        "raoemadf.online,jpkwoyveladnt.online,jradumbwgmggr.space,jsadapi.com,jsfeedadsget.com,jtiavpaxso.in,jtxlzsuxad",
        "axe.site,jubileebread.com,jubnaadserve.com,juddersexcusedligure.qpon,juiceadv.com,juicyads.com,juicyads.me,jum",
        "boaffiliates.com,junbi-tracker.com,junkyadexchange.com,junmediadclikrmdi.com,justfullporn.net,justtube.net,jwr",
        "toravx.com,jxavjloeqgrbz.space,jxcuocswkavpi.site,jxvhcvhdeaadf.site,jyjioxtadobac.website,jyusupfxadswy.site,",
        "jzhbethwfhnlq.space,kaadqpoorrhsy.site,kadam.ru,kadime.cc,kadmkyie.com,kagocvncavfqi.store,kappavowal.com,kava",
        "nga.ru,kavblcvs.com,kavcapfpjbhpn.space,kavekduhupvky.space,kavtqkbsyrpzt.site,kawai-jav.com,kbcjugyneadmk.onl",
        "ine,kbwfirbugcpmk.site,kcpcmdepbvyjq.website,kdlgpcradvcmi.space,kebbyizzyavicula.cyou,kfgxuiyutbwav.space,kfp",
        "uejiakadbw.site,khiclickrkafw.online,kidsinstancesfavor.com,kinkadservercdn.com,kiomthavsruuk.online,kjetdxtca",
        "mmhc.com,kjkavoimtxart.online,kjmrldarxadmw.online,kkczadzgl.com,kllastroad.com,klvadvvrbusee.website,kneestol",
        "erableadulthood.com,kniravkrmejus.online,knwjmjujhadoo.site,koalaglade.com,kohadfzjolyfy.website,konraddiscrim",
        "ination.com,konradissuesimperial.com,konradsheriff.com,konradterriblesect.com,kotqbebetyoeed.com,kpopsingers.c",
        "om,krudavmbgnwrc.website,krzkasckoqvad.site,ktiebyesqavuz.online,kudtapgad.in,kunvertads.com,kvcmgvhnbadzq.onl",
        "ine,kvjthavimymgk.online,kvsadman.com,kvylquisaduxr.store,kwgavfflgjpop.site,kwintravetoer.cyou,kxgadtlettueg.",
        "website,kzzbdsattavdw.space,labadena.com,labisexecute.com,ladbrokesaffiliates.com.au,ladeykyxsaosi.site,ladgri",
        "tperformed.com,ladhlbetxnjlm.space,ladiesmatchesfinder.com,ladsabs.com,ladsans.com,ladsecz.com,ladsips.com,lad",
        "sipz.com,ladskiz.com,ladyboygoo.com,ladysabryna-xxiqlkt.work,lalapush.com,lampshadescreen.com,landscaperedress",
        "ave.com,lanxtkacpucpc.site,laptopprivacyado.com,lavatorydownybasket.com,lavatoryhitschoolmaster.com,lavender64",
        "369.com,lavenderzenith.com,lavishboot.com,lavnkios.in,layer-ad.org,layingframingcomrade.com,lblscpmfxuzqq.spac",
        "e,lbyarvladuyzy.space,lcpavtrwvavnq.website,ldqkyedvfepop.online,leadadvert.info,leadfamly.com,leadgainz.com,l",
        "eadimmensely.com,leading-back.com,leadmediapartners.com,leadnote.me,leadsecnow.com,leadsleap.net,leadzu.com,le",
        "aveundo.com,leavingaileen.com,leavingneedyengaged.com,leithstaving.cfd,lemonadds.com,leonbetvouum.com,leopardf",
        "avorite.com,leplvcbadrmgb.space,letsporn.com,lfavcwgydohba.store,lfstmedia.com,lgsmartad.com,lhkmedia.in,lhvav",
        "sdsnhxgz.website,liadm.com,libertystmedia.com,lifeporn.net,lifepromo.biz,lightmeadowwalk.com,likeads.com,likel",
        "ihoodcheerleaderdefender.com,likexxxtube.com,limetreeloadherself.com,linkadvdirect.com,linkonclick.com,linksex",
        ".club,linuxtracker.org,litavvcc.in,literallycircumscribetome.com,litterarenadisembroildisembroil.com,liveadexc",
        "hanger.com,liveadoptimizer.com,livecam.com,livecams.com,livepromotools.com,livesexasian.com,livesexbar.com,liv",
        "esportmedia.eu,liveuniversenetwork.com,livexxx.me,livyncxvleiad.website,lkadhbifkzvtt.online,lkqagavhhaqrp.spa",
        "ce,lkseavjkzupfw.space,llqmbetbgjhyt.website,lmavfuplmvhbq.space,lmueckvzozqav.website,load-res-static.lol,loa",
        "d-static-res.xyz,loadedbummer.com,loadercdn.com,loadingscripts.com,loadtime.org,lobefintinklecleaved.cyou,lobo",
        "click.com,localedgemedia.com,locksadvancedrelease.com,lodgeastronautadore.com,logsaddiction.com,lokiporn.com,l",
        "oohaiturigavik.net,loppetickierbavoso.cyou,lotadversaryvolatile.com,lotongmamonadowses.qpon,lotritereavows.cfd",
        ",lotteryaffiliates.com,loupesstavedtriene.cyou,love-banner.com,love4porn.com,lovecam.com.br,loveflirt-haven.co",
        "m,lpmediastorage.com,lrvpquoavttir.site,lscsexsnjacwr.site,ltnavwfpsffpi.space,lucidmedia.com,luckyads.pro,luc",
        "kyforbet.com,luggedzabeta.com,lumbersavians.cyou,lunacyumpireunleave.cfd,lunatictoxicambiguity.com,lunlyqvavjk",
        "fc.website,lustytube.net,luxadv.com,luxupadva.com,lvwytobyemady.online,lwadm.com,lwgadm.com,lwuntajpwadku.onli",
        "ne,lwxmapxavophf.online,lxadqdidnsnzd.online,lxoukuwkzvada.online,lyconery-readset.com,lzudmwytadqrg.site,m2tr",
        "ack.co,mad-consist.com,madadsmedia.com,madamconducted.com,madcheddar.net,madcpms.com,maddenwiped.com,madeupice",
        ".com,madeupregular.pro,madeupteamspupil.com,madeupvigourpoll.com,madmanbiological.com,madnesscoupon.com,madqwc",
        "bhobeca.space,madratesforall.com,madrogueindulge.com,madsabs.com,madsans.com,madsecs.com,madserving.com,madsli",
        "mz.com,madsone.com,madspmz.com,madurird.com,mafroad.com,magicads.nl,magnetisemedia.com,maideneyeshadow.com,mai",
        "nadcenter.com,mainadv.com,majorads.site,maleficscamper.qpon,malisonrepope.com,mangadex.org,mangadistrict.com,m",
        "angarawad.biz,mangoads.net,manipulativeslaves.com,manorflounderheady.com,mantisadnetwork.com,manureads.com,man",
        "ysex.com,manysex.tube,mappilaaverrer.com,marimedia.com,marinadelifestyle.com,marinadeworriesdurable.com,market",
        "javgg124.fun,markswebcams.com,marmaladestrikingcolourless.com,marrergraves.com,marsads.com,martpleadescended.c",
        "om,masqueradeegoismcurtain.com,masqueradejournalism.com,masqueradepatheticburden.com,mathads.com,mathtag.com,m",
        "aturexxx.vip,mavq.net,mavrtracktor.com,maxiadv.com,maxonclick.com,maxvaluead.com,mbidadm.com,mbsspads.com,mcba",
        "dljmbhdni.online,mcepadnkovcy.in,mdroadmmbxrlj.space,me4track.com,meadow-assets.click,meadow-static.xyz,media-",
        "412.com,media-amazon.com,media-click.ru,media-general.com,media-sapiens.com,media.net,media6degrees.com,mediaa",
        "lpha.com,mediacandy.ai,mediacpm.pl,mediad2.jp,mediaf.media,mediaffiliation.com,mediaforce.com,mediaforge.com,m",
        "ediafuse.com,mediahell.world,mediamixer.co.kr,mediapalmtree.com,mediaplex.com,mediasama.com,mediatarget.com,me",
        "diatemple.net,mediative.ca,mediative.com,mediatradecraft.com,mediatrias.com,mediaupdate.co.za,mediavine.com,me",
        "diaxchange.co,medleyads.com,medyanetads.com,meetradar.com,meetsexygirls.org,megaad.nz,megadeliveryn.com,megaff",
        "iliates.com,megatube.xxx,mepuisgsxadha.site,mercenaryslaveryplays.com,mergedlava.com,messyadvance.com,metavert",
        "ising.com,mfadsrvr.com,mfeibqadbbzaz.site,mfyavsmiha.com,mgladthereisso.com,mgtckhufqjlad.store,mharavczumra.i",
        "n,microad.jp,microad.net,microadinc.com,midas-network.com,midnighttraditionally.com,midpopedge.com,migrationoc",
        "tavian.com,mihmadovjkpi.in,mileporn.com,milftube.mobi,millennialmedia.com,mimosaavior.top,minklemonade.com,min",
        "utemedia-prebid.com,minutemediaservices.com,miraclethisaddicted.com,mixadvert.com,mk-ads.com,mkadujfywhxis.web",
        "site,mkatakavcaxm.in,mktbqkzdzadrg.site,mladedda.net,mlnadvertising.com,mmadsgadget.com,mmdcokadaklfg.website,",
        "mnetads.com,mng-ads.com,moatads.com,mobileoffers-dld-download.com,mobiletracking.ru,mobipromote.com,mochicader",
        "mis.cyou,modelsgonebad.com,modulepush.com,mojoaffiliates.com,momentummedia.com.au,monadplug.com,monetixads.com",
        ",monthsexpressive.com,moonads.net,moonrocketaffiliates.com,mootermedia.com,mortypush.com,motaflavine.com,mothe",
        "rlyglad.com,moustacheoverloadwar.com,movad.de,movad.net,movcpm.com,moviead55.ru,moviemediahub.com,moviesex.org",
        ",mpanythathaveresultet.info,mpocadpbnevwa.site,mpsuadv.ru,mqdownload.com,mqedledjjjdad.website,mqqhfyfavftyk.c",
        "om,mrgrekeroad.com,mrporngeek.com,msads.net,msgpbwbfradny.website,mtdutavlkpzzz.site,mtwataveihajz.space,muggu",
        "rscamden.click,multiwall-ads.shop,muniadazzled.qpon,mutenessdollyheadlong.com,mvmrcygtoadgz.online,mxhkadwedfl",
        "sr.online,mxpopad.com,my-promo7.com,myadcash.com,myadultimpressions.com,myaffiliates.com,myaudioads.com,myavli",
        "ve.com,mybestcpm.com,mybetterdatings.com,mybrowseraddon.com,mycams.com,mycasinoaccounts.com,mydatingstheladys-",
        "club.com,mydatingswomanonlines.com,mydatinguniverse.com,myeasetrack.com,myemailtracking.com,mykocam.com,mypopa",
        "ds.com,myprecisionads.com,myreadingmanga.info,mysexgames.com,mysexjourney.com,mysticaladoptionocean.com,mythad",
        ".com,mywebclick.net,mywovcjadrtcb.online,mzafanowhdcpc.space,nababroadbirch.com,nadese.xyz,nads.io,nadwkwzfjnx",
        "zw.site,naiadexports.com,nailadversary.com,naivewidespreadbody.com,nameads.com,nanoadexchange.com,narrowveneer",
        "ingsavoury.com,nasbhavuovwzi.space,nasssmedia.com,nativclick.com,native-adserver.com,nativeadmatch.com,nativea",
        "ds.com,nativeadsfeed.com,navaidaosmic.top,navajospoule.com,navalrememberedcarve.com,naviemytotgbg.online,navig",
        "atingnautical.xyz,navtlkdihvagi.online,navwmaakbo.com,navxhefspincz.space,navyhalfclinical.com,navyrecentuproo",
        "t.com,navyrecognition.com,navzkokpufm.in,nawpush.com,naxadrug.com,nbadgnaqqqrxx.website,nbdysfxadsfji.website,",
        "ndfhqtezbkavl.online,ndhadbeensoa.org,ndqpopziznuuq.space,neads.delivery,neatmadeiraairborne.com,necessarilymo",
        "squitoheadphones.com,neglectsquad.com,neladyhe.com,neptuntrack.com,netavenir.com,netcpms.com,netmedia.hu,netso",
        "lads.com,networkad.net,networkhubcontrol.com,networking444.net,networkmanag.com,networks1000.net,neudesicmedia",
        "group.com,newjulads.com,newmayads.com,newoctads.com,newormedia.com,newosnotadprx.space,news-headlines.co,newsa",
        "dsppush.com,newsatads.com,newscadence.com,newsmaxfeednetwork.com,newsunads.com,newtab-media.com,newthuads.com,",
        "newtueads.com,nextclick.pl,nextmillmedia.com,nexxxt.biz,ngadverts.com,nglmedia.com,ngunmeumeklav.online,nguswf",
        "avipgqm.website,ngvyktsuuyadu.online,nhatsadwafljs.site,nhbavbqogc.com,nhiaoadcolahl.website,nhzylwcpmcchb.spa",
        "ce,nikkiexxxads.com,nkyavipvkfxgo.online,noaaduliugvul.online,noavoahevugrib.com,nobblespush.cyou,nomadsbrand.",
        "com,nomadsfit.com,noncommittalsupposedlybroadcast.com,nonsenseaggravation.com,noosebanghad.com,norfolkbroads.c",
        "om,northdownload.com,notifpushnext.net,novadune.com,novemberadventures.name,novibet.partners,nowfavourable.com",
        ",npcad.com,nrowumdcamr.in,nsfwadds.com,nsmartad.com,ntepadkozezji.online,ntkgkyalavlcs.space,ntvpwpush.com,nut",
        "entertainintermediate.com,nutterguava.cyou,nvfigpopnczvp.online,nwnxyivttoaad.site,nxxxpmyejmudz.space,nylons-",
        "porno.com,nylonsporno.com,nythingamglad.com,nzavmqooblmpl.site,nzme-ads.co.nz,nznoxxx.com,nzwecadkryhup.site,o",
        "adehibut.xyz,oagbadcuwzruq.site,oavgofyxwyryj.online,oavqowdsenguy.online,oavsgdmtbealk.website,oavurognaurd.n",
        "et,oavyctdvbrlpz.space,obliquescallionexcavator.com,oboxads.com,obsoletepaddlevehicular.com,obstinacysexist.co",
        "m,ocadnxsghpseo.space,ocoopdcpmqxpy.site,octopuspop.com,odnwadzre.in,oeubadryzvwyk.space,ofbdaktxxxldn.space,o",
        "fcamerupta.com,offergate-games-download1.com,offerstrack.net,ogavjmydvhvqe.com,oglalavoodoo.world,ogzavfebqsdm",
        "y.online,ohlladamnak.in,ohmycams.com,oilcampshift.qpon,oirdwcadrzrqj.online,oivpkegadjuqc.site,oizfzrjavugyq.s",
        "pace,ojtuqqiohqadx.online,ojxtxmaivsavq.site,okadgpkptxakv.website,okrqmavp.com,oktavvavg.in,olayomad.com,oldg",
        "amesdownload.com,omegaadblock.net,omegadblocker.com,omni-ads.com,omtrdc.net,onad.eu,onalskillsexkce.org,onclck",
        "pop.com,onclickads.net,onclickalgo.com,onclickclear.com,onclickgenius.com,onclickmax.com,onclickmega.com,oncli",
        "ckperformance.com,onclickprediction.com,onclickpredictiv.com,onclickpulse.com,onclicksuper.com,onclicktop.com,",
        "onhercam.com,online-adnetwork.com,onlineporno.fun,onlinepromogift.com,onlinepromousa.com,onlyalad.net,onnetwor",
        "k.tv,onsetblouselampshade.com,ontnkqwlnimav.online,onxgaladrj.in,ooadrhsqiofhv.space,oohkbadeeaaem.site,ooozxx",
        "adrsnpo.space,oouutjpavsxfv.site,openadserving.com,openadultdirectory.com,openrtb-banner.com,openx.net,oplmbxd",
        "yndadi.website,opptmzpops.com,opsqbrqhqcamb.online,optad360.io,optad360.net,optiads.org,optidownloader.com,oqm",
        "iadfkmlcwz.website,orangeads.fr,orangeclickmedia.com,orfrsexlxozvc.site,orgyxxxhub.com,origintube.com,orqlzuoq",
        "gad.com,osahsadctgzfa.site,osamadada.com,oscineabettal.cfd,oshacampelis.com,osiaffiliate.com,ospreymedialp.com",
        ",otaavlxnmqwyi.online,otoadom.com,ottadvisors.com,ottavvrdokror.website,ouiiadyzaieqx.space,ourtecads.com,outb",
        "rain.com,outbrain.net,outrageoussquad.com,outrightphiladelphia.com,outstandingfrailabroad.com,overloadenjoy.co",
        "m,overnightabroadcraftsman.com,ovgadhtcyvak.in,ovpniyavjvob.com,oxado.com,oxtracking.com,oxzmasxuadysh.space,o",
        "zadzoddxxxqs.online,ozonemedia.com,p-advg.com,packlenseavesdrop.com,paddleweekendnudge.com,padgntohmxigt.site,",
        "padgzdbyelrca.online,padinventoryvile.com,padsabs.com,padsans.com,padsatz.com,padsims.com,padsimz.com,padskis.",
        "com,padslims.com,padspms.com,pakbanners.com,pantrycornyunload.com,pantyhosepornstars.com,papayads.net,paradera",
        "scaldefinitely.com,paradise8.com,paradizeconstruction.com,paradocs.ru,parlouroutlayfavor.com,partner-ads.com,p",
        "artycasino.com,pas-rahav.com,patesihobbet.com,patwyavweuxzx.space,paulgravitynumeral.com,pavagedisawa.cfd,pavi",
        "liongiddy.com,paviortummels.com,pavljbnheqajx.space,pavramzdvprsi.online,paxmedia.net,pay-click.ru,payloadz.co",
        "m,payvclick.com,pblcpush.com,pboadsgyalldd.site,pc-ads.com,pcpcg47ycvwxzyuq8fkwu.cfd,pcyqadvwrqsmc.online,peac",
        "efulmeadowwalk.com,peadeplete.com,peafzfaghradr.com,peakclick.com,peavyegeria.cyou,pebadipetto.cfd,pebblemedia",
        ".be,pedxbqnadnebr.space,peltishpushful.com,pennynetwork.com,pepperjamnetwork.com,perfecttoolmedia.com,performa",
        "nceadexchange.com,performanceonclick.com,performancetrustednetwork.com,perimeteravertendless.com,perimeterrave",
        "nousdelusional.com,perkcanada.com,perkyabroad.pro,peugyiwmgvadr.space,peukasrsihavele.org,pgammedia.com,pgmedi",
        "aserve.com,pgzmbkdavgilh.online,phmtcfsfjtcpm.space,phrfushpadbco.website,phuakravmo.in,pilotstracking.com,pip",
        "-pip-pop.com,pipaffiliates.com,pitdjtfxcamyx.site,pityneedsdads.com,pixellitomedia.com,pjyibsavpisod.space,pla",
        "jwaduivmqg.space,planetwin365affiliate.com,playrainbet.com,pleasedmad.com,pleasetrack.com,plenomedia.com,pllsa",
        "drp.net,plowmenepoptic.com,ployimmediately.com,plucheavennel.com,plungeflavour.com,pnspihrxaduyk.site,poavoabe",
        ".net,poetizesuaver.cfd,pointclicktrack.com,polishleadennineteen.com,polisnetwork.io,polluxnetwork.com,polyad.n",
        "et,poorlyadjustvisual.com,pop5sjhspear.com,popads.media,popads.net,popander.com,popblockergold.com,popblockerg",
        "old.info,popbounty.com,popbutler.com,popcash.net,popcornvod.com,popcpm.com,popdemission.com,popedomwresterfing",
        "ian.qpon,poperblocker.com,popexhale.com,popgoldblocker.info,pophandler.net,popiemarsala.cfd,popinacelektrameer",
        "ed.cyou,popjanuary.shop,poplardealtcarrying.com,poplartenth.com,popmansion.com,popmog.com,popmonetizer.com,pop",
        "monetizer.net,popmyads.com,poppastabour.com,popped.biz,poppyrimpace.com,poppysol.com,poprtb.com,popsads.net,po",
        "pteen.pro,popular-tea.com,popularslant.com,populationeverydaygale.com,populumframmit.com,popunder.bid,popunder",
        "js.com,popunderoctober.store,popunderstar.com,popunderz.com,popupblockergold.com,popupblockermax.app,popupclic",
        "k.ru,popupdomination.com,popupgoldblocker.net,popupsblocker.org,popviking.com,popxperts.com,popxyz.com,porchad",
        "vertise.com,porn-star.com,porn.com,porn.fr,porn300.com,porn99.net,pornalin.com,pornattitude.com,pornav.net,por",
        "nconversions.com,porndeals.com,porndig.com,porndoe.com,porndroids.com,porndude.link,pornearn.com,pornerbros.co",
        "m,pornforrelax.com,porngals4.com,porngames.adult,porngames.tv,porngamespass.com,pornglee.com,porngo.tube,porng",
        "ray.com,pornhat.com,pornhb.me,pornicom.com,pornid.xxx,pornimg.xyz,pornintellect.com,pornj.com,pornjam.com,porn",
        "jv.com,pornkings.com,pornktube.tv,pornleep.com,pornmix.org,porno666.com,pornoegg.com,pornogramxxx.com,pornohan",
        "s.net,pornoheat.com,pornone.com,pornpapa.com,pornpics.com,pornpics.de,pornpoppy.com,pornrabbit.com,pornsex.roc",
        "ks,pornstargold.com,porntez.com,porntrack.com,porntry.com,pornv.xxx,pornve.com,pornvids.fr,pornxbox.com,pornxp",
        ".com,pornxp.net,pornyhd.com,positivenetwork.pro,postaffiliatepro.com,powerad.ai,poweradblocker.com,powferads.c",
        "om,ppuqlcyadikqe.website,pqnnvegqkyadt.space,preciousadequate.com,precisionclick.com,preconyklavernbombax.cfd,",
        "predestinationicebergpredicament.com,predictivadnetwork.com,predictivadvertising.com,predominantleadingsprout.",
        "com,prefixbesidesbetray.com,premiumads.net,presscllad.com,presselhavedartoic.cfd,prestigecasino.com,pretrackin",
        "gs.com,prettyroad.biz,prettytube.net,preventtributebroad.com,prickly-tradition.com,pridehickintimidating.com,p",
        "rimaryads.com,prizeadultery.com,prmtracking.com,pro-adblocker.com,pro-advert.de,proadscdn.com,processedlizarda",
        "djoining.com,producedpigsforehead.com,profitablecpmgate.com,profitablecpmrate.com,profitablecpmratenetwork.com",
        ",profitabledisplaynetwork.com,profitablegatecpm.com,profitableratecpmnetwork.com,profitabletrustednetwork.com,",
        "programmesheadstoneconsult.com,projectjav.com,promo-bc.com,promo-reklama.ru,promo4partners.com,promobenef.com,",
        "promocionesweb.com,promokrot.com,promoteconditionerkeyboard.com,promotools.biz,promowebstar.com,propadsviews.c",
        "om,propeller-tracking.com,propellerads.com,propellerads.tech,propellerclick.com,propellerpops.com,propositionv",
        "owelladen.com,proprietordauntaddition.com,prospershave.com,protectioncleave.com,proudradish.com,prplad.com,prp",
        "lads.com,prpops.com,prtrackings.com,prwave.info,pryzmads.com,pseavibrsijgx.site,psjadixqzliqg.online,psxzadkhs",
        "aevp.space,psychologylosttraveling.com,ptwebcams.com,pubadx.one,pubfuture-ad.com,pubjzoxav.com,publicidad.net,",
        "publpush.com,pubmatic.com,puffnetwork.com,puinnjsexnlbo.space,pulpyads.com,pulseadnetwork.com,pulseonclick.com",
        ",pumavobis.cyou,pumpbead.com,punctuationbandycontradict.com,puporn.com,pureadexchange.com,purpleads.io,push-no",
        "tifications.top,push-sdk.com,push-sdk.net,push-sense.com,push.house,push1000.com,push1000.top,push1001.com,pus",
        "h1005.com,pushabl.com,pushadvert.bid,pushaffiliate.net,pushatomic.com,pushbizapi.com,pushcampaign.club,pushcen",
        "tric.com,pushclk.com,pushdom.co,pushdomains.biz,pushelp.pro,pushflow.net,pushflow.org,pushimg.com,pushingtilin",
        "g.com,pushinpage.com,pushking.net,pushlapush.com,pushmaster-in.xyz,pushmenews.com,pushmine.com,pushmobilenews.",
        "com,pushnami.com,pushnews.org,pushno.com,pushnotice.xyz,pushpong.net,pushprofit.net,pushrase.com,pushserve.xyz",
        ",pushtorm.net,pushub.net,pushwelcome.com,pushy.ai,pushyimmaculateinestimable.com,pvdnugjwyrkad.website,pvhlewe",
        "xcpcqq.online,pvuznrtadztxu.space,pxlcrykadtsby.site,pxuxlswadjlfu.space,pydsexwwnwiuj.space,pzmbetafgealw.onl",
        "ine,q1media.com,qaeadnlcctjvh.online,qbiavgzkhyqji.space,qbkofavetnrgr.site,qcxavdkqorwri.space,qdemrnocqeadj.",
        "site,qetsjadxfjtzq.online,qgxbluhsgad.com,qhbetzsrzzslg.site,qhngmav.com,qjwmihzmadpsm.space,qkosavpevkjzc.sit",
        "e,qoclick.com,qoqqgkzavy.com,qpadmalav.in,qpnicavmjtytk.site,qpnippagdkadi.online,qqdrbmdbcavgm.space,qsgjcqiq",
        "haiad.online,qtnobmxbgavob.space,quackgraduation.com,quadrinhoseroticos.net,qualiclicks.com,qualitydating.top,",
        "quanta-wave.com,quantserve.com,quantumadblocker.com,quantumads.com,quickads.net,quickloadbinpack.click,quillad",
        "ot.org,qunskbadgzcdx.site,quwtzyadstt.com,qwxolavxykodb.site,qxdownload.com,qxgloyadavqvf.space,qxpxrbettkskb.",
        "space,qyeavpkdevzzc.site,qyexjolwpv6wvgbet5wp.cfd,rabbetget.cfd,rabbitporno.com,racebets.com,radarconsultation",
        ".com,radcffbpaqwyr.space,radiancelongitude.com,radiancerelaysacred.com,radiancethedevice.com,radiantack.com,ra",
        "diantmedia.net,radiantspacecraftbell.com,radiantvalerelieve.com,radiodarn.com,radioonlinehd.net,radioreference",
        ".com,radiusfellowship.com,radiusix.net,radiusmarketing.com,ragfishcleptavgases.cfd,raggedtwinheadmaster.com,ra",
        "kutenadvertising.io,ramppopulateresponse.com,rapidadexchange.com,ratedeaddisk.com,rationalizeinadequatecomedia",
        "n.com,rationalizeoverwhelmendeavour.com,raveigryeggfj.website,ravenfarm.tech,ravenousamuse.com,ravktufmpgula.s",
        "ite,ravm.tv,rbpksavddouil.store,rcamagqknhmx.com,rcygxjvmjadds.site,rdrbunaadhpyn.site,reactivebetting.com,rea",
        "diong.net,readme.ru,readpeak.com,readserv.com,readycelebrationleaf.com,realcfadsblog.com,realclick.co.kr,realg",
        "fporn.com,realxavounow.com,reasonable-bet.com,recamnjggepku.site,receiptcadenceresurrection.com,recipeaddingav",
        "enue.com,recipientpopulationregret.com,reclaimvanillademure.com,recordedheavinessexperience.com,rectadesc.cfd,",
        "recyclehorridleading.com,redads.com,rediads.com,redirect-ads.com,redpineapplemedia.com,redtube.com,redtube.fm,",
        "reedsoutpop.com,refbanners.com,refbanners.website,referenceswarmerbread.com,regionads.ru,regionalavouchcreepin",
        "g.com,regularseatradio.com,rehearsejuniorloaded.com,reliablebanners.com,rememberineffectiveadvisedly.com,remet",
        "althecladayment.cfd,rencontreadultere.club,rentfavourablecashmere.com,resacaduende.com,resentlocallycamomile.c",
        "om,resentmentsadflew.com,resteddiabetepocket.com,restorepushed.com,retaingravely.com,revealingplacingpopped.co",
        "m,revenuecpmgate.com,revenuecpmnetwork.com,revenuenetwork.com,revenuenetworkcpm.com,revillusiveheavily.com,rev",
        "imedia.com,revive-adserver.net,revopush.com,revupads.com,rewardsaffiliates.com,rexadvert.xyz,rexpush.club,rfwm",
        "fadnncajl.site,rhinoaffiliates.com,rhombusads.com,rhubarbambassadorweep.com,richads.com,richwebmedia.com,rider",
        "adjective.com,ridgepilotavalanche.com,risingshadewhite.com,rivinadinomic.qpon,rmqgscbitoadp.site,rmyhibtadohj.",
        "com,roadcontagion.com,roadwaspfang.com,robeheadlong.com,rocketmedia24.com,rockmostbet.com,rocoads.com,rodesqua",
        "d.com,rogersmedia.com,rokymedia.com,rollbackavenge.com,roobet.com,roobetaffiliates.com,rootzaffiliates.com,rot",
        "atingadult.com,roxyaffiliates.com,rpyztjadsbonh.store,rqerokcafoadv.space,rtb-media.me,rtbpop.com,rtbreachads.",
        "com,rteomxavwvnol.site,rubanners.com,rubiconproject.com,rummyaffiliates.com,ruscams.com,ryscxxxelrsdw.space,ry",
        "tadlqujhwsz.site,rzedmincpjcam.website,s-adzone.com,sabavision.com,saczvfpaeadxv.space,sadbaguette.com,sadbasi",
        "ndinner.com,saduvqpcfykcj.online,sadvvowejqfet.site,safelinktracker.com,sagaciousexceptingrate.com,sagsajijqpa",
        "vd.space,saladattic.com,salbraddrepilly.com,salvador24.com,samsungads.com,sandracismaboveaverage.com,sandwichc",
        "onscientiousroadside.com,saravanacyetic.qpon,satisfactorybroadformidable.com,savageavowmidland.com,savamea.org",
        ",save-editor.com,savefiles.com,saveit.us,saveterracehangnail.com,saveunstable.com,savinesyohimbe.cfd,savinist.",
        "com,saviourcrimpycrimpyhem.com,savourbiggergait.com,savttuegxztst.site,saymedia.com,sbaffiliates.com,sbicrwatv",
        "navf.online,sbsbetlinfevn.site,scamblefeedman.com,scamforgottenbyte.com,scammerfail.com,scammerrecorder.com,sc",
        "arlet-clicks.info,scatxxxporn.com,scholarshipexplosivepopular.com,sciouspavlovpundum.qpon,scorchads.com,scorec",
        "ardresearch.com,scowpoppanasals.com,scqcigoladk.com,sdadarotfnhoy.website,sdk4push.com,sdomxqcaeuadm.site,sdtz",
        "adpayrgsc.space,seafoodclickwaited.com,seaofads.com,searchschoolsnetwork.com,searingcavelet.rest,seatrackingdo",
        "main.com,secondhandbrave.pro,secretbehindporn.com,secthatlead.com,secureaddisplay.com,secureleadsforever.com,s",
        "ecureleadsrn.com,sedatingnews.com,seizureblade.com,selectad.com,semperinshave.rest,sendwebpush.com,sensible-ad",
        "s.com,sensitiveclick.com,seriolavangloe.com,servedby-buysellads.com,servedbyadbutler.com,servemeads.com,server",
        "4ads.com,set-push.net,setravieso.com,setupad.com,setupad.net,sewboldlyavow.com,sex-and-flirt.com,sex-chat.me,s",
        "ex-techniques-and-positions.com,sex3.com,sexad.net,sexcelebrity.net,sexclic.com,sexcooldating.com,sexdatecash.",
        "com,sexei.net,sexemulator.com,sexfamilysim.net,sexfg.com,sexfinder.com,sexflirtbook.com,sexgangsters.com,sexib",
        "a.com,sexier.com,sexintheuk.com,sexlist.com,sexmature.fun,sexmoney.com,sexmotors.com,sexmy.site,sexpin.net,sex",
        "playcam.com,sexsearch.com,sexstories69.com,sextadate.net,sextf.com,sextracker.com,sextubebox.com,sextubepromo.",
        "com,sextubeweb.com,sextvx.com,sexuhot.com,sexvertise.com,sexvid.pro,sexvid.xxx,sexy-ch.com,sexycams.com,sexypo",
        "rnvideo.net,sf-ads.io,shadowybalance.com,shadysetting.pro,sharemedia.rs,sharktube.net,shauladubhe.com,shauladu",
        "bhe.top,shaversoutjumphobnobs.cyou,shavesfoilermissus.cfd,shavingwitchcraft.com,shipmentdoorwaydecade.com,shoo",
        "gloonetwork.com,shopmonthtravel.com,short-wave.info,showcasead.com,showmeyouradsnow.com,shubadubadlskjfkf.com,",
        "siavtcwmgehxp.online,sibrdiarmuvav.site,sierrakonradreorganize.com,siftoctaveuntimed.qpon,signaturewoundedgrad",
        "uation.com,silesiadeuces.com,sillorangesladen.com,silverleafroad.com,simple-affiliate.com,sinadolcoralla.cfd,s",
        "incerelydiscreetcomedian.com,sindonpavis.cyou,singlesexdates.com,sizednavahoduncify.cyou,sjadkbbvcmdfd.website",
        ",sjobmfxcchbad.online,sjzieadynfqtl.online,skeetads.com,skidwaverorigin.com,skipvideoads.com,skirlsravager.com",
        ",skyadsmart.com,skymedia.co.uk,skynetworkcdn.com,slaveguna.cfd,slavesthy.com,slavingaint.com,slippersexaminati",
        "on.com,slthavjtoxjoi.site,smac-ad.com,smadex.com,smart1adserver.com,smartadserver.com,smartadtags.com,smartcli",
        "ck.net,smartcpatrack.com,smartyads.com,smashpops.com,smentbrads.info,smwadnuydogrh.space,snack-media.com,snaey",
        "jnavvojo.online,snathsextends.com,snbets.com,sneakyadministration.com,snipersex.com,snlaavecjmpja.online,socal",
        "ledpushedcollege.com,socialsex.biz,socialsex.com,socialsexnetwork.net,sofinpushpile.com,softforestmeadow.com,s",
        "oftonicads.com,softpopads.com,soldierreproduceadmiration.com,solicitorvalvemade.com,solocpm.com,solutionsadult",
        "es.com,somehowluxuriousreader.com,sophiaaccoladewest.com,sorbetbungler.com,sordidsicknessfaded.com,sousefulhea",
        "d.com,soxnhocmadklc.site,spadeharassrefusal.com,spaderonium.com,spadsync.com,spanpromo-link.com,sparrowaboveav",
        "eragedefence.com,sparta-tracking.xyz,special-promotions.online,specificmedia.com,speckencampment.com,speechcle",
        "opatraadmiral.com,speedingdeadlyplays.com,speedycomediandecember.com,sphinxtube.com,spmediacdn.com,spokeoaffil",
        "iates.com,sponsorinserttimeout.com,sponsorpay.com,sponsorshipprickly.com,sportradar.com,sportradarserving.com,",
        "spotidownloader.com,sppopups.com,spreadingsinew.com,spreadpreferencetelevision.com,sprintrade.com,spry-glad.pr",
        "o,srvupads.com,srzwbetxwdkpt.online,sseadlhhrusrj.website,ssqcqaavrncxt.online,stackadapt.com,stagepopkek.com,",
        "staggeredravehospitality.com,star-advertising.com,star-clicks.com,star-theladyfinds.com,stargamesaffiliate.com",
        ",starmobmedia.com,startwebpromo.com,statcamp.net,statsforads.com,staturebanner.com,stavizokhbhe.in,steadyglobe",
        ".com,steadyhelmet.com,sterilecontradiction.com,stickyadstv.com,stiffenshave.com,stimtavy.net,stinkyloadeddocto",
        "r.com,stop-adblocker.info,stopads-now.com,stopadzblock.net,stopblockads.com,storader.com,storage-ad.com,store4",
        "porn.com,straightenedphiladelphiarequire.com,streamjav.top,stringroadway.com,stripsaver.com,striptube.net,stud",
        "ads.com,studiotrackmusic.com,sukaporn.com,sunglassesexpensive.com,sunmedia.tv,sunmediaads.com,sunporno.com,sun",
        "shineadditions.com,superadexchange.com,superonclick.com,superxxxfree.com,supposedlymadeup.com,supremeadblocker",
        ".com,suptube.com,surpavdggpjar.site,surplusaltarmadness.com,swak-adguard.pro,swarmpush.com,swatowbetokenganam.",
        "cfd,sweetmedia.org,swesomepop.com,swiftladle.com,swimmingnarcotictravel.com,swmlidloaetad.site,swwpush.com,syg",
        "vwadaxjesn.space,synsads.com,syrupimmediate.com,systemads.net,systemleadb.com,taadd.com,taboola.com,taboola.ne",
        "t,tacxemkinqtav.space,taddpxmmooi.com,tadodpfikom.in,tafdiywfnavss.site,tahrqfbavxmxf.site,takeads.com,tanager",
        "poppel.rest,tantoporno.com,tapingferocitymadeira.com,tapjoyads.com,taroads.com,taversroomthy.com,taversserang.",
        "cyou,tavgvouyakozy.website,tavlomikyionr.website,tavryuhcshdsr.website,tcpcharms.com,teads-xo.com,teads.tv,tea",
        "dumiccdoct.website,technologies2radar.net,technoratimedia.com,technoshadows.com,techporn.ph,tectrixsnavvle.com",
        ",teenporno.xxx,teensexgfs.com,tehseelaves.com,tejxthyxavhfb.site,teneralavarice.cfd,termagewaving.com,terrapus",
        "h.com,text-link-ads.com,tgtmedia.com,thavxaiwvxtja.website,the-adult-company.com,the-binary-trader.biz,theadga",
        "teway.com,theadsparks.com,thebetterdealz.com,thecoreadv.com,thelotter-affiliates.com,themediaplanets.com,thems",
        "elvespresentationfavourable.com,theplayadvisor.com,theporndude.com,thepornstar.com,thesisadornpathetic.com,the",
        "socialsexnetwork.com,thingsmadenavy.com,third-tracking.com,thorpeslav.com,thoughtleadr.com,thrad.ai,thrads.ai,",
        "threadpassivetrespass.com,thriftytraveler.com,thrixxx.com,thtrsihwdznav.site,thunderhead.com,tiatmuadwuhwy.onl",
        "ine,tidycamel.com,tiedhamadjoin.com,tihmjppaavrmz.site,tiladlolbtu.in,tiltgardenheadlight.com,tinyadz.com,tita",
        "nads1.com,titanads2.com,titanads3.com,titanads4.com,tjcfeyegncavs.online,tjesbxadovyot.site,tjiyavxmtihln.spac",
        "e,tkpopd.cfd,tkpopp.com,tktube.com,tlavideo.com,tlrzavbrosrsv.space,tlucfqhtavxyx.space,tluihoeadocjs.store,tl",
        "vmedia.com,tnnfecqadqzck.online,toboads.com,toibjavyncxnc.site,tokelaunavet.cyou,tokenads.com,tomladvert.com,t",
        "omorrowlemonade.com,tonemedia.com,tonicheaviness.com,topadsservices.com,topadvert.ru,topbetfast.com,topclickgu",
        "ru.com,topcpmcreativeformat.com,topdealad.com,topdisplaynetwork.com,topprofitablecpm.com,toprevenuecpmnetwork.",
        "com,topsexcams.club,topsexmovs.com,toptopleads.com,toroadvertising.com,tossoffads.com,totaladblock.com,totalad",
        "performance.com,totalav.com,totymanglave.com,tpdads.com,tpmedia-reactads.com,tpopfgvlqytlm.online,tprfavksbqzm",
        "r.online,tqqavzfshkdsh.website,track-campaing.club,track-victoriadates.com,track4ref.com,trackad.cz,trackcherr",
        "y.com,trackcitg.com,trackclicks00.com,tracker-2.com,tracker-tds.info,trackerflw.com,trackerrr.com,tracki.click",
        ",tracking-aws-5.com,tracking-zy.com,trackingmembers.com,trackingtraffo.com,trackmundo.com,tracks20.com,tracksf",
        "aster.com,trackspeeder.com,trackstracker.com,tracktds.com,tracktilldeath.club,trackuity.com,trackvoluum.com,tr",
        "ackwilltrk.com,trackzd.com,tradbypass.com,tradeadexchange.com,tradedoubler.com,tradeexpert.net,tradehouse.medi",
        "a,tradepub.com,tradertimerz.media,tradetracker.net,traditionalboycottflung.com,traditionalmusic.co.uk,traditio",
        "nblindness.com,tradplusad.com,traffic-media.co.uk,trafficad-biz.com,trafficadbar.com,trafficmediaserver.com,tr",
        "ailerheavendirectness.com,trainedwave.pro,trapdoortissuepush.com,travelandtourworld.com,travelaudience.com,tra",
        "vellingcrushslit.com,travellingpresidentding.com,travelscream.com,travelsplayboy.com,traveltop.org,traversesev",
        "en.com,travidia.com,tremormedia.com,trimreaddrunken.com,tripcarriagewaving.com,tripsthorpelemonade.com,triumph",
        "antthingsreading.com,tronads.io,trustedcpmrevenue.com,trustphiladelphiacompelling.com,ts-tracker.com,tsadcebak",
        "ufrd.site,tsmodelstube.com,tspops.com,tsuccwavchpvh.website,tsztqlwravxkl.site,ttfwotjetavmk.website,ttzmedia.",
        "com,tube-sexs.com,tubeadnetwork.com,tubeadv.com,tubeadvertising.eu,tubecorp.com,tubecorporate.com,tubecup.net,",
        "tubeelite.com,tubemov.com,tubeoffline.com,tubepork.com,tuberel.com,tubetruck.com,tubeultra.com,tubev.sex,tubew",
        "olf.com,tubexazimebeze.com,tubexo.tv,turboadv.com,turnstileunavailablesite.com,tvewiifmavniu.website,twads.gg,",
        "tweakpersuadelowest.com,twinpinenetwork.com,twiselaveria.com,twistads.com,twittad.com,txgtadnmmwwte.space,txxx",
        ".com,typhulaverging.cfd,uadllyad.com,uads.cc,uaeapcmjbmzad.space,uaqiwadxmsniz.online,uavefhtrbkbhq.online,uav",
        "nfdezxzote.site,ubercpm.com,ubgvqakukavmm.space,ublockpop.com,ucaavjcweklhn.space,ucnwhhtaadaqp.site,udsqbadxj",
        "vzanh.com,ueleensadcyxh.site,ufauolqhhcpmd.space,ufidxaduyqqkq.site,ufirmirylfavb.online,ugadceetawiae.site,ug",
        "azuogyeeadt.online,uhuvadkubxjkv.website,uitopadxdy.com,uiuhawavotxsv.space,ukaadmlcfluko.site,ukaviayvjfydp.o",
        "nline,ukcampsite.co.uk,ulbruiqadlmaa.online,ulpradvpeaiqs.store,ultimedia.com,umljflgaddcdk.space,unavoidables",
        "tates.com,uncadyaewiajd.online,uncededtravoy.com,uncertainadministration.com,underclick.ru,undergroundportugue",
        "seheadache.com,unibet.co.uk,unibet.com,unidentifiedscammertravel.com,universalads.com,universityroadsidecircum",
        "stantial.com,unloadonly.com,unripe-head.pro,unrulymedia.com,unsettledradiator.com,unshaveparaphs.com,untimely-",
        "promotion.com,unwavedmalarky.cyou,unweavederiv.com,uoutube.com,upbeatmeadow.com,uploadcloud.pro,uploaded.net,u",
        "ploadgig.com,upornia.com,uprivaladserver.net,uptopopunder.com,upush.co,upymgrtnacpco.site,urdatingassistant.co",
        "m,urgayporn.com,urzdxiiugisad.site,usasexcams.com,usavmhnwphcmm.online,userimmediate.com,usesponsorarrange.com",
        ",ussmtrack.com,ust-ad.com,usuxodfefavbm.space,utavbvaervidi.site,utm-campaign.com,utrocaadrboym.space,uttereda",
        "disappo.com,utubepwhml.com,uubvkadmkvnys.space,uuwjrmvavkgrs.site,uvvsyeadlyrgb.space,uwzwqadqkcjgw.site,uyqwp",
        "nspgadkq.site,uyriroadwtdwu.space,uywfpprhavyog.site,uzpuaveerqjei.site,v8eaezd.cc,vaguemad.com,valemedia.net,",
        "validclick.com,valuad.cloud,valuead.com,valueclick.cc,valueclick.com,valueclick.net,valueclickmedia.com,variab",
        "ilityaviationmutiny.com,vatanclick.ir,vavuwetus.com,vazijiavfyebf.site,vcmedia.com,vdeztemladdeb.online,vdhvld",
        "ghccavh.site,vdxsvladcihnm.online,venturead.com,verbsponsor.com,verdecasino-offers.com,verdepromo.info,vertism",
        "edia.co.uk,vespymedia.com,vexilladuper.cyou,vfreecams.com,vhojdkvcadavb.space,viadata.store,viads.com,viads.ne",
        "t,vid-adblocker.com,vidcpm.com,video-adblocker.com,videoadstech.org,videocampaign.co,videosprofitnetwork.com,v",
        "iewablemedia.net,viiadaof.com,viibslav.com,vikablavflhe.com,vikiporn.com,villainous-track.pro,vipads.live,vipa",
        "vkacrfujf.online,viptube.com,viradisazo.qpon,viralmediatech.com,virtuedeadlock.com,visariomedia.com,viscidvare",
        "usebetitle.cfd,visiads.com,vjzlvloavqarr.space,vkgtrack.com,vkipfmadahrwh.site,vkoxgtewzadqi.site,vkyieskdlavj",
        "t.space,vlogerads.com,vlwqyhgnslfad.space,vmpdmfacpctto.space,vnakjojlazcpm.online,voeqzuuvnwpad.website,voirp",
        "fhavircs.online,votepercussivepoplar.com,vowsavageskim.com,vpvlxuooad.com,vqkusfxadcdll.website,vradfvwfmwvaw.",
        "space,vrbcauaviehga.space,vrgbaevvadiwn.site,vrizead.com,vrstnadgljwiy.space,vrtzads.com,vsavbpsnxdrgt.online,",
        "vsexshop.ru,vtdoadqxkv.com,vtube.to,vucxfzsavoxdd.online,vuevgiavjbplr.space,vuiebktuavkcc.online,vulkanbet.me",
        ",vulnerableimmigrateaboveaverage.com,vvautxavycmwh.space,vvladxqdlpihl.online,vwzusobugmrad.space,vxxgxicadjti",
        "a.space,vxyqwespdaadz.site,w00tmedia.net,wadecarbohydrate.com,waexhoxdqigad.space,wafmedia6.com,wagsad.com,wai",
        "ledshave.com,walkeralacrityfavorite.com,wangheebehav.com,wanlyavower.com,warashi-asian-pornstars.fr,warilyclic",
        "kframes.com,warmmeadowroad.com,washedadvance.com,washoffwaviercyanite.cfd,watchadsfree.com,watchcpm.com,wavecd",
        "n.de,wavedsuspect.click,waveetiquette.com,waveletiricize.cfd,wavermiddayboundary.com,waviesdaywork.cyou,waving",
        "auradignity.com,wavtcdqwbuhiw.space,waxapushlite.com,wayanatomyunavailable.com,wayftertheyhadgo.org,wbamedia.c",
        "om,wcgbetyplakno.website,wclpvybetahei.site,wdahqumfpnmav.space,wdczlavbweeby.online,wdxuugjqeavzv.space,weara",
        "dmiration.com,weaveradrenaline.com,weaversprinkle.com,weaveworkingheaviness.com,web-adblocker.com,web3ads.net,",
        "webads.co.nz,webads.nl,webadserver.net,webbed-leadership.com,webcampromo.com,webcampromotions.com,webcams.com,",
        "webcamtests.com,webclickengine.com,webclickmanager.com,webpushcloud.info,websitepromoserver.com,webtradehub.co",
        "m,weekheadlong.com,weforads.com,wellmadesurvey.pro,wellworn-lead.com,wetrackgames.com,wfbcfadgivxqd.space,wfiu",
        "tburadoho.online,wfnetwork.com,wgfqayocqadzp.space,whaleads.com,wheebsadree.com,wheegnehoad.net,wheelchairavoi",
        "d.com,wheelchairbewilderedwaver.com,whirlclick.com,whoadhoxtojg.com,whopping-click.com,whorledebbets.cyou,whwd",
        "xzadairoq.website,widespreadcomponent.com,wifescamara.click,wigetmedia.com,williamhillcasino.com,windfallleave",
        "brawl.com,winneradequateablebodied.com,winneradsmedia.com,wisjevyyjdjad.site,withdrawtrade.com,wktmzgeadgmje.c",
        "om,wlkokwriavlw.com,wmmediacorp.com,wnt-s0me-push.net,wnvqeizwbpads.online,woffxxx.com,womxwsnxadxtl.space,won",
        "deradblock.com,wootmedia.net,workdeadlinededicate.com,worldsbestcams.com,worried-advantage.com,worthylighterav",
        "ert.com,wowgwmezvkuav.site,wpadmngr.com,wpiadbxpy.in,wpnetwork.eu,wpush.org,wpushorg.com,wpushsdk.com,wqbeavbp",
        "ztwsm.website,wravogram.cfd,wretchedadvantage.com,wsknwakvkeiad.site,wtg-ads.com,wtpiylrmavdfp.space,wtqhxggxn",
        "gfad.site,wtzqznypmadeo.store,wujtefbetb.com,wunsofiubphad.space,wwads.cn,wwwadcntr.com,wwwpromoter.com,wwwxxx",
        ".uno,wzkqcamv.com,xad.com,xadcentral.com,xadpagwro.com,xadsmart.com,xapads.com,xavhiszxjspce.website,xavions.s",
        "hop,xbcpcn.com,xbhcyadhm.com,xbhoylaadarbq.website,xcelsiusadserver.com,xcpmfvlfcdltd.online,xcrrdxmdadebs.sit",
        "e,xglvldsblpkad.online,xgwhfyaavzpyp.space,xhamstercams.com,xinbzybuosadk.website,xjav.tube,xjoavfrckohwo.onli",
        "ne,xjsgturadzcgo.online,xkavrcwjklaitf.com,xldadwsosaqiq.site,xlovecam.com,xmayktavadugm.site,xmladserver.com,",
        "xmtrading.com,xnxxporn.video,xnxxxnxx.site,xoaveubcbdpld.com,xogogowebcams.com,xrff-adguard.pro,xrqcamarbnhxg.",
        "website,xslbuoadsfsrf.space,xsqltdwuccamf.space,xtendmedia.com,xuytfncamofxt.site,xvidxxx.com,xwkvojadxcnyd.sp",
        "ace,xxbifrkzadx.com,xxx-porn-tube.com,xxxbannerswap.com,xxxblackbook.com,xxxbox.me,xxxbp.tv,xxxbule.com,xxxdes",
        "sert.com,xxxex.com,xxxfetish24.com,xxxhd.icu,xxxiijmp.com,xxxijmp.com,xxxivjmp.com,xxxmatch.com,xxxoh.com,xxxp",
        "ornhd.pro,xxxshake.com,xxxviiijmp.com,xxxviijmp.com,xxxvijmp.com,xxxvjmp.com,xxxvogue.net,xxxwap.pro,xxxwebtra",
        "ffic.com,xxxxsx.com,xxxzbkhpjxzry.store,yadmarmizuogr.website,yads.tech,yadsunkzjtzlb.space,yavecqiztteba.site",
        ",yavli.com,yavsmataemray.online,yavurxepklnzm.space,yazatadoolysups.cfd,ybb-network.com,ybcnevrcnyadv.site,ydj",
        "adyzuepuvf.site,ydwekhadydlnz.space,yekavcxlkbejc.online,yesporn.cam,yespornpleasexxx.com,yetterslave.com,yeys",
        "wxtehnave.site,yieldads.com,yieldlove-ad-serving.net,yjjvzmruicyav.online,ykdqqskuziadx.site,ykveifvdzadrl.onl",
        "ine,yoads.net,yoc-adserver.com,yogadeprive.com,yopuademtfkwb.space,you-porn.com,youhavequited.com,youlamedia.c",
        "om,youporn.com,youradexchange.com,yourquickads.com,youtube.com,yowiespopian.com,yoxfjudadp.com,yoyadsdom.com,y",
        "pcjgbavpkray.site,yporn.tv,ypztdrxavedah.space,yqepkjcpcxnay.space,yqopmxeyoaveu.online,yrrnadvdbvdow.space,yu",
        "intbradshed.com,yukclick.me,yumenetworks.com,yummyadvertiseexploded.com,yuppads.com,yuppyads.com,yurekbkmhoadl",
        ".net,yuwseujcjavak.space,yvmads.com,ywiicadcuvilo.online,yx-ads6.com,yxaasylsdjado.site,yxrlvfirqiave.website,",
        "yyecdiehcvadf.site,yyxnoegkqkavu.space,zadlclqnequke.site,zaimads.com,zanox-affiliate.de,zarpop.com,zavcmzahut",
        "mqo.site,zavirand.com,zavnnfijuylnu.site,zavunmknjlpwl.space,zavylwpyuijzb.website,zbkrmwrzftiav.site,zbporn.c",
        "om,zcbetcczfvylq.site,zcfpfbavrffxm.site,zeads.com,zealshaving.com,zebracave.com,zedporn.com,zenomedia.com,zer",
        "ads.com,zesty-lead.pro,zetadeo.com,zeusadx.com,zextzccadpr.com,zfctrack.net,zgaxadxsycppa.website,zgijtonvzgad",
        "k.online,zhyhadnrqzpcd.site,ziffdavis.com,zimoccaengrave.shop,zimvlqwrtadmi.space,zipcrypticbroadsheet.com,zix",
        "xsovtavprk.online,zkdwtdzlcadfu.site,zkvsavwzcbfaj.online,zkxweadue.com,zlavxbjuyekwo.site,zlzwhrhkavos.xyz,zo",
        "tqhsyqbetkg.online,zoxlzvrqeqadk.site,zplxavnqigfrp.site,zpuavdcswqnml.online,zpxsadurflhs.com,zrbezdaddiahk.s",
        "pace,zrdubetejajen.website,zrygvvxyadvro.site,zsfbadlhjmpow.site,zsnweuiavdmzs.online,zteeavqibjsgj.space,ztra",
        "ck.online,zumidxxxwcjob.site,zunoptjciavj.in,zuzaghbkajav.in,zvwjrwav.com,zvzpopazdasxh.website,zwkfzyjtadeuz.",
        "space,zxavziahazmyb.online,zyadpzcdswdre.com,zypenetwork.com,zytfycpmnlzio.online,eab.elbphzgw.cc,demjpgtf.zvp",
        "xyyql.cc,api2.jpfviwgkz.cc,xxycw341.com,vnhgr465.com,caow.us,583.cc,9521.com,2726.com,8319.com,9212.app,322.cc",
        ",384.tv,4477.tv,177.gg,5651.com,275.cc,391.cc,837.tv,1x4g6f2.com,aizhantj.com,0cy5aps.com,ax8rylb.com,7xkpxuw.",
        "com,4gl3g.z.com,mehqk5u.com,smun7ro.com,67x8epk.com,kvkluj8.com,lw1su4n.com,hcxftcl.com,nf65hwr.com,3dzmgtc.co",
        "m,dkepyys.com,hv7hpkk.com,tq.qwehkg.top,ta.dqgjla.top,tz.erghsasq.top,magsrv.com,forkedspare.com,trafficjunky.net,trafficjunky.com,toplineads.com,dzhjmp.com,mayzaent.com,greentrafsa.com,gasipedibexide.com,doppiocdn.com,strpst.com,xxxjmp.com,fozekikikiminu.com,snaptrckr.fun,es344.com,rallytrck.website,storagexhd.com,erosaiapp.to,attacheatableoverlabor.com,ethnicexpressions.org\".split(','));function isAdDomain(h){if(!",
        "h)return false;h=h.toLowerCase();if(__museAdSet.has(h))return true;var i=h.indexOf('.');while(i>=0){h=h.slice(",
        "i+1);if(__museAdSet.has(h))return true;i=h.indexOf('.');}return false;}function isAdUrl(u){try{var x=new URL(u",
        ",location.href);return !!x.hostname&&isAdDomain(x.hostname);}catch(e){return false;}}function isContent(el){if",
        "(!el||!el.querySelector)return false;if(el.querySelector('a[href*=\"/voddetail/\"],a[href*=\"/vodplay/\"],a[href*=",
        "\"/video/\"],a[href*=\"/player/\"],a[href*=\"/v5/\"]'))return true;if(el.querySelector('#go_btn,#time_badge,#read_zo",
        "ne'))return true;return false;}function foreign(u){try{var x=new URL(u,location.href);return !!x.hostname&&x.h",
        "ostname!==host;}catch(e){return false;}}function isRealSite(){var as=document.querySelectorAll(\'a[href]\');var n=0;for(var i=0;i<as.length;i++){var h=as[i].getAttribute(\'href\')||\'\';if(foreign(h))continue;var p=\'\';try{p=new URL(h,location.href).pathname;}catch(e){continue;}if(p.indexOf(\'/vodtype/\')===0||p.indexOf(\'/voddetail/\')===0||p.indexOf(\'/vodplay/\')===0){if(++n>=2)return true;}}return false;}function isPlaceholderHref(h){if(!h)return true;h=String(h).trim().toLowerCase();return h===\'\'||h.charAt(0)===\'#\'||h.indexOf(\'javascript:\')===0;}function inSameHostLink(el){var a=el&&el.closest?el.closest('a'):null;",
        "return !!(a&&a.href&&!foreign(a.href));}function isOpaqueImg(s){return s&&(s.indexOf('blob:')===0||s.indexOf('",
        "data:')===0);}function txt(el){return (el.innerText||'').replace(/\\s+/g,'');}function ageIdc(el){return ((el.i",
        "d||'')+' '+((el.className&&el.className.baseVal!==undefined)?el.className.baseVal:(el.className||''))).toLower",
        "Case();}function isAgeGate(el){if(!el)return false;if(/agedisclaimer|age[-_]?verif|age[-_]?verification|age[-_",
        "]?gate|age[-_]?check|over18|under18|age[-_]?confirm/.test(ageIdc(el)))return true;var t=(el.innerText||'');if(",
        "/这是个成人网站|我年满\\s*18\\s*岁|i\\s*am\\s*18|i'm\\s*18|verify your age|age verification/i.test(t))return true;if(t.length<",
        "=120&&t.indexOf('18')>=0&&/(成人|年满|\\bage\\b|adult|over 18|under 18)/i.test(t))return true;return false;}\nfunctio",
        "n isChallenge(el){if(!el)return false;try{var __ct=(document.title||'').toLowerCase();if(__ct.indexOf('请稍候')>=",
        "0||__ct.indexOf('just a moment')>=0)return true;}catch(e){}if(el.tagName==='IFRAME'){var s0=el.src||(el.getAtt",
        "ribute?el.getAttribute('src'):'')||'';if(s0&&(s0.indexOf('cloudflare')>=0||s0.indexOf('hcaptcha')>=0||s0.index",
        "Of('recaptcha')>=0))return true;return false;}if(!el.querySelectorAll)return false;var frs=el.querySelectorAll",
        "('iframe');for(var i=0;i<frs.length;i++){var f=frs[i].src||(frs[i].getAttribute?frs[i].getAttribute('src'):'')",
        "||'';if(f&&(f.indexOf('cloudflare')>=0||f.indexOf('hcaptcha')>=0||f.indexOf('recaptcha')>=0))return true;}retu",
        "rn false;}function inAgeGate(el){if(!el)return false;if(isAgeGate(el))return true;var n=el.parentElement,p=0;w",
        "hile(n&&p<6){if(/agedisclaimer|age[-_]?verif|age[-_]?verification|age[-_]?gate|age[-_]?check|over18|under18|ag",
        "e[-_]?confirm/.test(ageIdc(n)))return true;n=n.parentElement;p++;}return false;}function kill(el){if(el&&el.pa",
        "rentNode)el.parentNode.removeChild(el);}function collapse(el){var p=el,i=0;while(p&&i<3&&p!==document.body){if",
        "(txt(p).length===0&&!p.querySelector('img,video,iframe,form,input,button,select,textarea')){var n=p.parentElem",
        "ent;kill(p);p=n;i++;}else break;}}function collapseCard(a){var p=a,i=0;while(p&&i<3&&p!==document.body){var links=p.querySelectorAll(\'a[href]\');var hasReal=false;for(var k=0;k<links.length;k++){var h=links[k].getAttribute(\'href\')||\'\';if(!h)continue;if(isPlaceholderHref(h))continue;if(!foreign(links[k].href)){hasReal=true;break;}}if(hasReal)break;if(p.querySelector(\'img,video,iframe\'))break;if(txt(p).length>200)break;var n=p.parentElement;kill(p);p=n;i++;}}function killLoaderPadding(){var ss=document.querySelectorAll('style');for(v",
        "ar i=0;i<ss.length;i++){var st=ss[i];var id=st.id||'';if(id.slice(-9)!=='_style_id')continue;var t=(st.textCon",
        "tent||'').replace(/\\s/g,'');if(t.indexOf('padding-bottom:100px')>=0&&st.parentNode)st.parentNode.removeChild(s",
        "t);}}function mainLink(card){var as=card.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var a=as[i];",
        "if(txt(a).length>=4)return a;}return as.length?as[0]:null;}\nfunction hideMixedForeignCards(){var lists=documen",
        "t.querySelectorAll('ul,ol');for(var li=0;li<lists.length;li++){var list=lists[li];if(!list.isConnected)continu",
        "e;if(list.closest('nav,header,footer'))continue;if(isAgeGate(list))continue;var cards=[];var kids=list.childre",
        "n;for(var k=0;k<kids.length;k++){var c=kids[k];if(c.nodeType!==1)continue;if(!c.querySelector('a[href]'))conti",
        "nue;cards.push(c);}if(cards.length<8)continue;var same=0,total=0;for(var m=0;m<cards.length;m++){var a=mainLin",
        "k(cards[m]);if(!a)continue;var href=a.getAttribute('href')||'';if(!href)continue;total++;if(!foreign(href))sam",
        "e++;}if(total<8)continue;if(same<=total*0.5)continue;for(var n=0;n<cards.length;n++){var a2=mainLink(cards[n])",
        ";if(!a2||!a2.isConnected)continue;var h2=a2.getAttribute('href')||'';if(!h2)continue;if(foreign(h2))kill(cards",
        "[n]);}}}\nfunction hideNoidDiv(){var n=document.getElementById('noiddiv');if(!n||!n.isConnected||n===document.b",
        "ody)return;if(n.querySelector&&n.querySelector('video'))return;n.style.display='none';}\nfunction hideHeadingPr",
        "omoLinks(){var hs=document.querySelectorAll('h1 a[href],h2 a[href],h3 a[href],h4 a[href],h5 a[href],h6 a[href]",
        "');for(var i=0;i<hs.length;i++){var a=hs[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;var href=a.get",
        "Attribute('href')||'';if(!href)continue;if(foreign(href))a.style.display='none';}}\nfunction noteHide(rule,el){",
        "try{if(!window.__museHideLog)window.__museHideLog=[];if(window.__museHideLog.length<20){var cn=(el.className&&",
        "el.className.baseVal!==undefined)?el.className.baseVal:(el.className||'');window.__museHideLog.push(rule+':'+(",
        "el.tagName||'')+'.'+String(cn).slice(0,30));}}catch(e){}}\n/*push v30 R32-1*/function lfReg(h){h=String(h||'').toLowerCase().replace(/^www\\./,'');var p=h.split('.');return p.length>2?p.slice(-2).join('.'):h;}function killLinkFarms(){var hr=lfReg(location.hostname);function same(u){try{var x=new URL(u,location.href);return lfReg(x.hostname)===hr;}catch(e){return true;}}function real(a){var h=(a.getAttribute('href')||'').trim().toLowerCase();return !!h&&h.charAt(0)!=='#'&&h.indexOf('javascript:')!==0;}var al=document.querySelectorAll('a[href]');var tot=0,ss=0;for(var i=0;i<al.length;i++){if(!real(al[i]))continue;tot++;if(same(al[i].href))ss++;}if(ss<2||ss<tot*0.1)return;var cs=document.querySelectorAll('p,div,ul,ol,footer,nav,section,td,li,dl');var hit=[];for(var j=0;j<cs.length;j++){var e=cs[j];if(e.getAttribute('data-muse-lf'))continue;if(e.querySelector('[data-muse-lf]'))continue;var as=e.querySelectorAll('a[href]');var n=0,sameN=0,hs={},hn=0;for(var k=0;k<as.length;k++){var a=as[k];if(!real(a))continue;n++;if(same(a.href)){sameN++;break;}var rh='';try{rh=lfReg(new URL(a.href,location.href).hostname);}catch(x){rh='';}if(rh&&!hs[rh]){hs[rh]=1;hn++;}}if(sameN>0||n<8||hn<8)continue;if(e.querySelector('img,picture,video,audio,iframe,canvas,form,input,button,select,textarea,svg'))continue;var c=e.cloneNode(true);var rm=c.querySelectorAll('a,script,style');for(var q=0;q<rm.length;q++)if(rm[q].parentNode)rm[q].parentNode.removeChild(rm[q]);var rest=(c.textContent||'').replace(/[\\s|｜/、，,·•:：\\-–—]+/g,'');if(rest.length>12)continue;hit.push(e);}for(var m=0;m<hit.length;m++){var h0=hit[m];var deeper=false;for(var o=0;o<hit.length;o++){if(o!==m&&h0!==hit[o]&&h0.contains(hit[o])){deeper=true;break;}}if(deeper)continue;h0.setAttribute('data-muse-lf','1');try{h0.style.setProperty('display','none','important');}catch(x){}try{noteHide('lf',h0);}catch(x){}}}/*push v30 R32-2*/function brandOf(h){var l=lfReg(h).split('.')[0]||'';return l.replace(/[0-9]+/g,'');}function sameBrand(u){try{var x=new URL(u,location.href);var a=brandOf(x.hostname),b=brandOf(location.hostname);return a.length>=3&&a===b;}catch(e){return false;}}/*push v28 F1*/function adFrameDoc(d){try{if(!d||!d.body)return false;var loc='';try{loc=String(d.location&&d.location.href||'');}catch(e){}if(/cdn-cgi\\/challenge|captcha|turnstile|challenges\\.cloudflare/i.test(loc))return false;if(d.querySelector('[class*=captcha],[id*=captcha],.cf-turnstile,[class*=turnstile],[id*=challenge]'))return false;var site='';try{site=String(d.location.hostname||'').toLowerCase().replace(/^www\\./,'');}catch(e){}var ad=0,els=d.querySelectorAll('script[src],iframe[src]');for(var i=0;i<els.length;i++){var s=els[i].getAttribute('src')||'';if(/^\\s*\\/\\//.test(s))s='https:'+s.trim();var m=/^\\s*https?:\\/\\/([^\\/?#:]+)/i.exec(s);if(m&&isAdDomain(m[1].toLowerCase()))ad++;}if(!ad)return false;var as=d.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){var h=(as[j].getAttribute('href')||'').trim();if(!h||h.charAt(0)==='#'||/^javascript:/i.test(h))continue;var hm=/^\\s*(?:https?:)?\\/\\/([^\\/?#:]+)/i.exec(h);if(!hm)return false;var hh=hm[1].toLowerCase().replace(/^www\\./,'');if(site&&(hh===site||hh.slice(-site.length-1)==='.'+site||site.slice(-hh.length-1)==='.'+hh))return false;}if(d.querySelector('video,audio,form,input,button,textarea,select'))return false;var c=d.body.cloneNode(true);var rm=c.querySelectorAll('script,style,noscript,template');for(var k=0;k<rm.length;k++)if(rm[k].parentNode)rm[k].parentNode.removeChild(rm[k]);if((c.textContent||'').replace(/\\s+/g,'').length>0)return false;return true;}catch(e){return false;}}/*push v29 R31-2*/function f1Judge(f){if(f.__museF1)return;var d=null;try{d=f.contentDocument;}catch(e){d=null;}if(!d){f.__museF1=1;return;}var lh='';try{lh=String(d.location.href||'');}catch(e){}if(!lh||lh==='about:blank')return;var rs=d.readyState;if(rs!=='interactive'&&rs!=='complete')return;if(rs==='interactive'){if(f.__museF1I===lh)return;f.__museF1I=lh;}if(adFrameDoc(d)){f.__museF1=1;try{f.style.setProperty('display','none','important');}catch(e){}try{f.src='about:blank';}catch(e){}return;}if(rs==='complete')f.__museF1=1;}function f1Hook(f){if(f.__museF1H)return;f.__museF1H=1;try{f.addEventListener('load',function(){try{f1Judge(f);}catch(e){}});}catch(e){}var raf=window.requestAnimationFrame?function(cb){window.requestAnimationFrame(cb);}:function(cb){setTimeout(cb,16);};var n=0;(function poll(){if(f.__museF1||!f.isConnected||n++>1800)return;var d=null;try{d=f.contentDocument;}catch(e){d=null;}if(!d){f.__museF1=1;return;}var lh='';try{lh=String(d.location.href||'');}catch(e){}if(lh&&lh!=='about:blank'&&d.readyState!=='loading'){try{f1Judge(f);}catch(e){}if(f.__museF1||f.__museF1I)return;}raf(poll);})();}function killAdFrames(){var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){var f=fs[i];if(f.__museF1)continue;f1Hook(f);f1Judge(f);}}/*push v29 R31-3*/function f3Style(){try{var st=document.getElementById('__museF3Style');if(st&&st.isConnected)return;st=document.createElement('style');st.id='__museF3Style';st.textContent='[data-cl-spot],[data-muse-f3]{display:none!important}';(document.head||document.documentElement).appendChild(st);}catch(e){}}function pureAdWrap(p){var c=p.cloneNode(true);var rm=c.querySelectorAll('[data-cl-spot],script,style,noscript,template,[data-muse-f3]');for(var k=0;k<rm.length;k++)if(rm[k].parentNode)rm[k].parentNode.removeChild(rm[k]);if((c.textContent||'').replace(/\\s+/g,'').length>0)return false;if(c.querySelector('a[href],img,picture,svg,video,audio,iframe,canvas,embed,object,form,input,button,textarea,select'))return false;return true;}function killClSpots(){f3Style();var ss=document.querySelectorAll('[data-cl-spot]');for(var i=0;i<ss.length;i++){var s=ss[i];if(s.getAttribute('data-muse-f3'))continue;s.setAttribute('data-muse-f3','1');var p=s.parentElement;while(p&&p!==document.body&&p!==document.documentElement){if(!pureAdWrap(p))break;p.setAttribute('data-muse-f3','1');p=p.parentElement;}}}/*push v39 P1*/function isRemoveAdsBtn(c){if(!c)return false;var ns=[c];var d=c.querySelectorAll?c.querySelectorAll('*'):[];for(var i=0;i<d.length&&i<30;i++)ns.push(d[i]);for(var j=0;j<ns.length;j++){var n=ns[j];var cl=String((n.className&&n.className.baseVal!==undefined)?n.className.baseVal:(n.className||''));var dh=(n.getAttribute&&(n.getAttribute('data-href')||n.getAttribute('href')))||'';if(/adsremove|removebutton|remove-?cta/i.test(cl)||/premium_?signup/i.test(dh))return txt(c).length<=20;}return false;}function insShellOk(p,ins){if(!p||p===document.body||p===document.documentElement)return false;if(isChallenge(p)||inAgeGate(p))return false;if(p.querySelector('form,input,select,textarea'))return false;var as=p.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var a=as[i];if(ins.contains(a))continue;var h=a.getAttribute('href')||'';if(isPlaceholderHref(h))continue;if(!foreign(a.href)){var pa='';try{pa=new URL(a.href,location.href).pathname;}catch(e){}if(pa.indexOf('/_xa/')!==0)return false;}}var vs=p.querySelectorAll('video');for(var v=0;v<vs.length;v++){if(!ins.contains(vs[v]))return false;}var cs=p.children;for(var k=0;k<cs.length;k++){var c=cs[k];if(c===ins||c.contains(ins))continue;if(isRemoveAdsBtn(c))continue;if(txt(c).length===0&&!c.querySelector('img,video,iframe,canvas,picture'))continue;return false;}return true;}function killInsAds(){var ins=document.querySelectorAll('ins');for(var i=0;i<ins.length;i++){var el=ins[i];if(!el.isConnected)continue;var cn=String(el.className||'');if(!/(^|\\s)adsby/i.test(cn))continue;if(isChallenge(el)||inAgeGate(el))continue;var top=el,p=el.parentElement;for(var d=0;d<3&&p;d++){if(!insShellOk(p,el))break;top=p;p=p.parentElement;}noteHide('insAd',top);kill(top);}}/*push v39 P2*/function isPaidMark(a){var mk=(a.getAttribute('data-event')||'')+' '+String(a.className||'');return /paid_?tabs?/i.test(mk);}function isPaidLink(a){if(isPaidMark(a))return true;var h=a.getAttribute('href')||'';if(isPlaceholderHref(h))return false;return foreign(a.href)&&isAdUrl(a.href);}function paidBox(p){if(!p||p===document.body||p===document.documentElement)return false;if(isChallenge(p)||inAgeGate(p))return false;if(p.querySelector('form,input,select,textarea,video,iframe'))return false;var ls=p.querySelectorAll('a[href]');var n=0;for(var i=0;i<ls.length;i++){var a=ls[i];if(isPlaceholderHref(a.getAttribute('href')||''))continue;if(!isPaidLink(a))return false;n++;}if(n<2)return false;return txt(p).length<=200;}function killPaidLinks(){var as=document.querySelectorAll('a[href]');var hits=[];for(var i=0;i<as.length;i++){var a=as[i];if(!a.isConnected)continue;if(isChallenge(a)||inAgeGate(a))continue;if(isPaidLink(a))hits.push(a);}for(var j=0;j<hits.length;j++){var b=hits[j];if(!b.isConnected)continue;var top=b,p=b.parentElement;for(var d=0;d<3&&p;d++){if(!paidBox(p))break;top=p;p=p.parentElement;}if(top===b&&!isPaidMark(b))continue;noteHide(top===b?'paidLink':'paidBox',top);kill(top);}}/*push v39 M1*/function fixedAnc(el){var n=el,d=0;while(n&&n!==document.body&&n!==document.documentElement&&d<6){var ps='';try{ps=getComputedStyle(n).position;}catch(e){}if(ps==='fixed'||ps==='sticky')return n;n=n.parentElement;d++;}return null;}function adFloatShellOk(b){if(isChallenge(b)||inAgeGate(b))return false;if(b.querySelector('form,input,select,textarea'))return false;var ls=b.querySelectorAll('a[href]');for(var i=0;i<ls.length;i++){if(isPlaceholderHref(ls[i].getAttribute('href')||''))continue;if(!foreign(ls[i].href))return false;}var ms=b.querySelectorAll('img,iframe,video,source');for(var j=0;j<ms.length;j++){var s=ms[j].getAttribute('src')||'';if(!s||s.indexOf('data:')===0||s.indexOf('blob:')===0)continue;if(!isAdUrl(s))return false;}return true;}function killAdFloatShells(){var es=document.querySelectorAll('a[href],img[src],iframe[src],video[src],source[src]');var seen=[];for(var i=0;i<es.length;i++){var e=es[i];if(!e.isConnected)continue;var u=e.tagName==='A'?e.href:(e.getAttribute('src')||'');if(!u||!isAdUrl(u))continue;var b=fixedAnc(e);if(!b||seen.indexOf(b)>=0)continue;seen.push(b);if(!adFloatShellOk(b))continue;noteHide('adFloatShell',b);kill(b);}}/*push v40 S1*/function bkSrcBlank(f){var s=(f.getAttribute('src')||'').trim();return !s||/^about:blank/i.test(s)||/^javascript:/i.test(s);}function bkHostOf(s){s=String(s||'').trim();if(/^\\/\\//.test(s))s='https:'+s;var m=/^https?:\\/\\/([^\\/?#:]+)/i.exec(s);return m?m[1].toLowerCase():'';}function bkSameSite(hh){var site=String(host||'').toLowerCase().replace(/^www\\./,'');hh=String(hh||'').toLowerCase().replace(/^www\\./,'');if(!hh||!site)return false;return hh===site||hh.slice(-site.length-1)==='.'+site||site.slice(-hh.length-1)==='.'+hh;}function bkAdDoc(d){try{if(!d||!d.body)return null;if(String(d.designMode||'').toLowerCase()==='on'||d.body.isContentEditable)return null;if(d.querySelector('[class*=captcha],[id*=captcha],.cf-turnstile,[class*=turnstile],[id*=challenge],[contenteditable]'))return null;if(d.querySelector('video,audio,form,input,textarea,select'))return null;var fl=0,first='';var as=d.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var h=(as[i].getAttribute('href')||'').trim();if(isPlaceholderHref(h))continue;var hh=bkHostOf(h);if(!hh)return null;if(bkSameSite(hh))return null;fl++;if(!first)first=hh;}var af=0,of=0;var fs=d.querySelectorAll('iframe');for(var j=0;j<fs.length;j++){var fh=bkHostOf(fs[j].getAttribute('src')||'');if(!fh)continue;if(bkSameSite(fh))return null;if(isAdDomain(fh)){af++;if(!first)first=fh;continue;}var al=String(fs[j].getAttribute('allow')||'').toLowerCase();if(fs[j].hasAttribute('allowfullscreen')||fs[j].hasAttribute('webkitallowfullscreen')||al.indexOf('fullscreen')>=0)return null;of++;if(!first)first=fh;}var asc=0;var ss=d.querySelectorAll('script[src]');for(var k=0;k<ss.length;k++){var sh=bkHostOf(ss[k].getAttribute('src')||'');if(sh&&isAdDomain(sh)){asc++;if(!first)first=sh;}}if(fl>0)return {why:'links'+fl,to:first};if(af>0)return {why:'adframe',to:first};if(of>0)return {why:'frame',to:first};if(asc>0)return {why:'adscript',to:first};return null;}catch(e){return null;}}function bkLog(r){try{window.__museBKN=(window.__museBKN||0)+1;if(window.__museBKN>20)return;if(window.museClickGuard&&window.museClickGuard.mark)window.museClickGuard.mark(String(r.to||''),String(location.href||''),String(r.why||''),'adslot');}catch(e){}}function bkSiteLink(p){var as=p.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var h=as[i].getAttribute('href')||'';if(isPlaceholderHref(h))continue;if(!foreign(as[i].href))return true;}return false;}function bkNoText(p){try{var c=p.cloneNode(true);var rm=c.querySelectorAll('script,style,noscript,template');for(var k=0;k<rm.length;k++)if(rm[k].parentNode)rm[k].parentNode.removeChild(rm[k]);return (c.textContent||'').replace(/\\s+/g,'').length===0;}catch(e){return false;}}function bkKill(f,r){var p=f.parentElement;noteHide('blankAdFrame',f);bkLog(r);kill(f);for(var i=0;i<2&&p&&p!==document.body&&p!==document.documentElement;i++){if(isChallenge(p)||inAgeGate(p))break;var n=p.parentElement;if(txt(p).length===0&&bkNoText(p)&&!p.querySelector('img,video,audio,iframe,canvas,picture,svg,form,input,select,textarea,button')&&!bkSiteLink(p)){kill(p);p=n;}else break;}}function bkCheck(f){if(!f||f.__museBK||!f.isConnected)return;if(!bkSrcBlank(f))return;if(isChallenge(f)||inAgeGate(f)){f.__museBK=1;return;}var d=null;try{d=f.contentDocument;}catch(e){d=null;}if(!d)return;var r=bkAdDoc(d);if(r){f.__museBK=1;bkKill(f,r);}}function bkHook(f){if(!f||f.__museBKH)return;f.__museBKH=1;try{f.addEventListener('load',function(){try{bkCheck(f);}catch(e){}});}catch(e){}var ts=[300,1000,2500,5000,10000];for(var i=0;i<ts.length;i++)setTimeout(function(){try{bkCheck(f);}catch(e){}},ts[i]);try{var d=f.contentDocument;if(d&&window.MutationObserver){var mo=new MutationObserver(function(){try{bkCheck(f);if(f.__museBK||!f.isConnected)mo.disconnect();}catch(e){}});mo.observe(d.documentElement||d,{childList:true,subtree:true,attributes:true});setTimeout(function(){try{mo.disconnect();}catch(e){}},30000);}}catch(e){}}function killBlankAdFrames(){var fs=document.getElementsByTagName('iframe');var a=[];for(var i=0;i<fs.length;i++)a.push(fs[i]);for(var j=0;j<a.length;j++){var f=a[j];if(f.__museBK||!bkSrcBlank(f))continue;bkHook(f);bkCheck(f);}}/*push v41 S2*/function sspCss(){try{var st=document.getElementById('__museSspCss');if(st&&st.isConnected)return;st=document.createElement('style');st.id='__museSspCss';st.textContent='.ssp-layout:not(.__museSspKeep),[data-ssp-slot-key]:not(.__museSspKeep),.ssp-overlay-popup:not(.__museSspKeep),.modal.ad-dialog:not(.__museSspKeep),#tip_modal.dx-modal:not(.__museSspKeep){display:none!important}';(document.head||document.documentElement).appendChild(st);}catch(e){}}function sspKeep(el){try{el.classList.add('__museSspKeep');var ds=el.querySelectorAll('[data-ssp-slot-key],.ssp-layout,.ssp-overlay-popup');for(var i=0;i<ds.length;i++)ds[i].classList.add('__museSspKeep');}catch(e){}}function sspGuard(el){try{if(isChallenge(el)||inAgeGate(el))return true;if(el.querySelector('video,audio,form,input,textarea,select,[class*=captcha],[id*=captcha],.cf-turnstile,[class*=turnstile],[contenteditable]'))return true;}catch(e){}return false;}function sspLog(why,el){try{window.__museSSN=(window.__museSSN||0)+1;if(window.__museSSN>20)return;var k='';try{k=el.getAttribute('data-ssp-slot-key')||el.getAttribute('data-ssp-layout-role')||el.id||'';if(!k){var s=el.querySelector('[data-ssp-slot-key]');if(s)k=s.getAttribute('data-ssp-slot-key')||'';}if(!k)k=String(el.className||'').slice(0,40);}catch(x){}if(window.museClickGuard&&window.museClickGuard.mark)window.museClickGuard.mark(String(k||''),String(location.href||''),String(why||''),'sspslot');}catch(e){}}function sspTipModal(m){try{if(!m||!m.classList||!m.classList.contains('modal'))return false;if(m.id==='tip_modal'&&m.classList.contains('dx-modal'))return true;var t=m.querySelector('.modal-title');return !!(t&&/秒后将会自动关闭/.test(t.textContent||''));}catch(e){return false;}}function sspModalOpen(){try{var ms=document.querySelectorAll('.modal');for(var i=0;i<ms.length;i++){var m=ms[i];if(m.classList.contains('in')||m.classList.contains('show'))return true;var ds='';try{ds=getComputedStyle(m).display;}catch(e){ds=m.style.display||'';}if(ds&&ds!=='none')return true;}}catch(e){}return false;}function sspUnlock(){try{if(sspModalOpen())return;var bd=document.querySelectorAll('.modal-backdrop');for(var i=0;i<bd.length;i++)kill(bd[i]);var b=document.body;if(b&&!document.querySelector('.dx-drawer--open')){if(b.classList)b.classList.remove('modal-open');if(b.style){b.style.overflow='';b.style.paddingRight='';}}}catch(e){}}function killSspSlots(){sspCss();var n=0,mk=0;var q=[];try{q=document.querySelectorAll('.ssp-layout,[data-ssp-layout-role],[data-ssp-slot-key],.ssp-overlay-popup,.ssp-overlay-popup-slot,.modal.ad-dialog');}catch(e){q=[];}var a=[];for(var i=0;i<q.length;i++)a.push(q[i]);for(var j=0;j<a.length;j++){var el=a[j];if(!el.isConnected)continue;var t=null;try{t=el.closest('.modal.ad-dialog,.ssp-overlay-popup')||el.closest('.ssp-layout');}catch(e){t=null;}if(!t)t=el;if(t===document.body||t===document.documentElement)continue;if(t.__museSSP)continue;t.__museSSP=1;if(sspGuard(t)){sspKeep(t);continue;}var isM=!!(t.classList&&t.classList.contains('modal'));noteHide(isM?'sspPopup':'sspSlot',t);sspLog(isM?'popup':'slot',t);kill(t);n++;if(isM)mk++;}var ms=[];try{ms=document.querySelectorAll('.modal');}catch(e){ms=[];}var b=[];for(var k=0;k<ms.length;k++)b.push(ms[k]);for(var m=0;m<b.length;m++){var md=b[m];if(!md.isConnected||md.__museTip)continue;if(!sspTipModal(md))continue;md.__museTip=1;if(md.querySelector('form,input,textarea,select')||isChallenge(md)){sspKeep(md);continue;}noteHide('tipModal',md);sspLog('tipModal',md);kill(md);n++;mk++;}if(mk){window.__museSSPDone=1;sspUnlock();}return n;}function sweep(){try{killAdFrames();}catch(e){}try{killClSpots();}catch(e){}try{killLinkFarms();}catch(e){}try{killInsAds();}catch(e){}try{killPaidLinks();}catch(e){}try{killAdFloatShells();}catch(e){}try{killBlankAdFrames();}catch(e){}try{killSspSlots();}catch(e){}killLoaderPadding();killPreRoll();p",
        "runeExtraVideos();",
        "/*v77*/if(window.__museJumpDone||isRealSite()){var __museFAs=document.querySelectorAll('a[href]');for(var __museFI=0;__museFI<__museFAs.length;__museFI++){var __museFA=__museFAs[__museFI];if(!__museFA.isConnected)continue;if(inAgeGate(__museFA))continue;if(isContent(__museFA))continue;if(!foreign(__museFA.href))continue;/*push v30 R32-2*/if(sameBrand(__museFA.href))continue;var __museFP=__museFA.parentElement;kill(__museFA);collapseCard(__museFP);}var __museVAs=document.querySelectorAll('a[href^=\"javascript:void(0)\"]');for(var __museVI=0;__museVI<__museVAs.length;__museVI++){var __museVA=__museVAs[__museVI];if(!__museVA.isConnected)continue;if(inAgeGate(__museVA))continue;if(!__museVA.querySelector('img'))continue;var __museVP=__museVA.parentElement;kill(__museVA);collapseCard(__museVP);}}",
        "var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var a=as[i];if(!a.i",
        "sConnected)continue;if(inAgeGate(a))continue;if(!isAdUrl(a.href))continue;var rel=(a.getAttribute('rel')||'').",
        "toLowerCase();var spon=rel.indexOf('sponsored')>=0;var hasImg=!!a.querySelector('img');var hasBg=false;if(!spo",
        "n){var dv2=a.querySelectorAll('div');for(var q=0;q<dv2.length;q++){var de=dv2[q];if(de.getAttribute('x-bg-load",
        "er-url')||de.getAttribute('data-bg')){hasBg=true;break;}var dc=getComputedStyle(de);if((dc.backgroundImage||''",
        ").indexOf('url(')>=0){hasBg=true;break;}}}if(!spon&&!hasImg&&!hasBg)continue;if(!spon&&hasImg&&!hasBg&&txt(a).",
        "length>8)continue;var p=a.parentElement;kill(a);collapse(p);}var bas=document.querySelectorAll('a[href]');for(",
        "var i=0;i<bas.length;i++){var a=bas[i];if(!a.isConnected)continue;if(inAgeGate(a))continue;if(isAdUrl(a.href))",
        "continue;if(isBannerAd(a)){var p=a.parentElement;kill(a);collapse(p);}}var imgs=document.querySelectorAll('img",
        "');for(var j=0;j<imgs.length;j++){var im=imgs[j];if(!im.isConnected)continue;if(inAgeGate(im))continue;if(im.c",
        "losest('a'))continue;var s=im.currentSrc||im.src||im.getAttribute('data-original')||'';if(s&&isAdUrl(s)){var q",
        "=im.parentElement;var w2=im.parentElement,hid2=false;while(w2&&w2!==document.body){var csw2=getComputedStyle(w",
        "2);if(csw2.position==='fixed'||csw2.position==='absolute'){var rw2=w2.getBoundingClientRect();if(rw2.width>=wi",
        "ndow.innerWidth*0.6&&rw2.height>=window.innerHeight*0.35){w2.style.display='none';hid2=true;}else if(rw2.width",
        ">=window.innerWidth*0.4&&rw2.bottom>=window.innerHeight-2&&rw2.top>=window.innerHeight*0.5&&rw2.height>=60){w2",
        ".style.display='none';hid2=true;}break;}w2=w2.parentElement;}if(!hid2){kill(im);collapse(q);}continue;}if(s&&i",
        "sOpaqueImg(s)){if(im.__museLb)continue;if(recentTap()&&window.__museTapImg){im.__museLb=1;continue;}var cs2=ge",
        "tComputedStyle(im);if(cs2.position==='fixed'||cs2.position==='absolute'){var r2=im.getBoundingClientRect();if(",
        "r2.width>=window.innerWidth*0.4||r2.height>=window.innerHeight*0.5){var w=im.parentElement,hid=false;while(w&&",
        "w!==document.body){var csw=getComputedStyle(w);if(csw.position==='fixed'||csw.position==='absolute'){var rw=w.",
        "getBoundingClientRect();if(rw.width>=window.innerWidth*0.4||rw.height>=window.innerHeight*0.5){w.style.display",
        "='none';hid=true;}break;}w=w.parentElement;}if(!hid)kill(im);}}}}var dvs=document.querySelectorAll('div');for(",
        "var k=0;k<dvs.length;k++){var d=dvs[k];if(!d.isConnected)continue;if(inAgeGate(d))continue;if(inSameHostLink(d",
        "))continue;if(isContent(d))continue;if(d.querySelector&&d.querySelector('video'))continue;if(isClickTrap(d)){k",
        "ill(d);continue;}if(isChallenge(d))continue;if(isModal(d)||isOpaqueModal(d)||isBigOverlay(d))d.style.display='",
        "none';}var all=document.querySelectorAll('div,section,aside,ins,iframe');for(var m=0;m<all.length;m++){var el=",
        "all[m];if(!el.isConnected)continue;if(inAgeGate(el))continue;if(isChallenge(el))continue;if(inSameHostLink(el)",
        ")continue;if(isContent(el))continue;if(el.querySelector&&el.querySelector('video'))continue;var hr=null;if(isA",
        "dIframe(el))hr='isAdIframe';else if(isFixedBottomIframe(el))hr='isFixedBottomIframe';else if(isBottomBar(el))h",
        "r='isBottomBar';else if(isBottomAd(el))hr='isBottomAd';else if(isBottomFlowAd(el))hr='isBottomFlowAd';else if(",
        "isFloatAd(el))hr='isFloatAd';else if(isAdLinkGrid(el))hr='isAdLinkGrid';else if(isFixedBottomAd(el))hr='isFixe",
        "dBottomAd';else if(isGenericBottomBanner(el))hr='isGenericBottomBanner';else if(isFixedBottomImageCardRow(el))hr='isFixedBottomImageCardRow';else if(isFixedBottomImageAd(el))hr='i",
        "sFixedBottomImageAd';else if(isGenericMidBanner(el))hr='isGenericMidBanner';else if(isFixedTopBanner(el))hr='i",
        "sFixedTopBanner';else if(isBottomPornAd(el))hr='isBottomPornAd';if(hr){noteHide(hr,el);el.style.display='none'",
        ";}}var bare=document.querySelectorAll('img,video');for(var bm=0;bm<bare.length;bm++){var be=bare[bm];if(!be.is",
        "Connected)continue;if(isFixedBottomBareMedia(be)){noteHide('isFixedBottomBareMedia',be);be.style.display='none",
        "';}}hideSpriteBarGroup();var links=document.querySelectorAll('a[href]');for(var q=0;q<links.length;q++){var lk",
        "=",
        "links[q];if(!lk.isCon",
        "nected)continue;if(inAgeGate(lk))continue;if(isAdImageLink(lk)||isBottomPornAd(lk)){lk.style.display='none';co",
        "ntinue;}if(foreign(lk.href)||isAdUrl(lk.href)){var fbr=fixedBottomAdRoot(lk);if(fbr){noteHide('isFixedBottomAd",
        "',fbr);fbr.style.display='none';}}}var cbs=document.querySelectorAll('[class*=\"close\"],[id*=\"close\"]');for(var",
        " n=0;n<cbs.length;n++){var cb=cbs[n];if(!isAdClose((cb.className&&cb.className.baseVal!==undefined?cb.classNam",
        "e.baseVal:cb.className||'')+' '+(cb.id||'')))continue;var slot=cb.closest('div,section,aside,ins');if(slot&&sl",
        "ot!==document.body&&!slot.querySelector('video'))slot.style.display='none';}hideMixedForeignCards();hideNoidDi",
        "v();hideHeadingPromoLinks();}function isNavMenu(el){if(!el||!el.querySelectorAll)return false;if(el.tagName===",
        "'NAV')return true;if(el.closest){try{var nv=el.closest('nav,[role=\"navigation\"]');if(nv)return true;}catch(e){",
        "}}var as=el.querySelectorAll('a[href]');if(as.length<3)return false;var same=0;for(var i=0;i<as.length;i++){if",
        "(!foreign(as[i].href))same++;}return same>=3&&same/as.length>0.6;}\nfunction isModal(el){if(!el||el===document.",
        "body)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isNavMenu(el))return false;",
        "if(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.posi",
        "tion!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.6||r.height<win",
        "dow.innerHeight*0.35)return false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){if(isAdUr",
        "l(as[i].href))return true;}var ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s=ims[j].curre",
        "ntSrc||ims[j].src||'';if(s&&isAdUrl(s))return true;}return false;}function isBottomBar(el){if(!el||el===docume",
        "nt.body)return false;if(el.querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.position!=",
        "='fixed'&&cs.position!=='sticky'&&cs.position!=='absolute')return false;var r=el.getBoundingClientRect();if(r.",
        "width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHe",
        "ight*0.5)return false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){if(isAdUrl(as[i].href",
        "))return true;}var ims=el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s=ims[j].currentSrc||ims[j",
        "].src||'';if(s&&isAdUrl(s))return true;}var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){va",
        "r f=frs[k].src||'';if(f&&isAdUrl(f))return true;}if(el.tagName==='IFRAME'){var s2=el.src||'';if(s2&&isAdUrl(s2",
        "))return true;}return false;}function isBottomAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))",
        "return false;if(el.querySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'",
        "&&cs.position!=='sticky'&&cs.position!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<wi",
        "ndow.innerWidth*0.4)return false;if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5",
        ")return false;if(r.height<60&&r.height<window.innerHeight*0.08)return false;if(el.tagName==='IFRAME')return tr",
        "ue;var im=el.querySelector('img');var vd=el.querySelector('video');var bg=cs.backgroundImage||'';var bgF=false",
        ";if(bg.indexOf('url(')>=0){var bm=bg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(bm&&bm[1]&&isAdUrl(bm[1]))bgF=true",
        ";}var fl=false;var fas=el.querySelectorAll('a[href]');for(var fi=0;fi<fas.length;fi++){if(isAdUrl(fas[fi].href",
        ")){fl=true;break;}}if(!im&&!vd&&!bgF&&!fl)return false;if(cs.position==='absolute'){var fa=false;var abs=el.qu",
        "erySelectorAll('a[href]');for(var ai=0;ai<abs.length;ai++){if(isAdUrl(abs[ai].href)){fa=true;break;}}var fsrc=",
        "'';if(im)fsrc=im.currentSrc||im.src||'';if(!vd&&!fa&&!(fsrc&&isAdUrl(fsrc)))return false;}return true;}functio",
        "n pruneExtraVideos(){var players=document.querySelectorAll('div.dplayer');for(var i=0;i<players.length;i++){va",
        "r d=players[i];if(!d.isConnected)continue;var vs=d.querySelectorAll('video');if(vs.length<2)continue;var keep=",
        "null;for(var k=0;k<vs.length;k++){if(!vs[k].classList.contains('ads_video')){keep=vs[k];break;}}if(!keep)keep=",
        "vs[0];for(var j=0;j<vs.length;j++){var av=vs[j];if(av===keep)continue;var wp=av.parentElement;if(av.parentNode",
        ")av.parentNode.removeChild(av);if(wp&&wp!==d&&wp.children.length===0&&wp.parentNode)wp.parentNode.removeChild(",
        "wp);}}}function isBottomFlowAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(el",
        ".querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position==='fixed'||cs.position=",
        "=='sticky')return false;var r=el.getBoundingClientRect();if(r.width>=window.innerWidth*0.9&&r.height>=window.i",
        "nnerHeight*0.9)return false;var _ims0=el.querySelectorAll('img');if(_ims0.length>=10)return false;var _cx=wind",
        "ow.innerWidth/2,_cy=window.innerHeight/2;if(r.left<=_cx&&_cx<=r.right&&r.top<=_cy&&_cy<=r.bottom)return false;",
        "var docBot=r.top+(window.scrollY||0)+r.height;var dh=document.documentElement.scrollHeight||0;if(docBot<dh-400",
        ")return false;if(r.width<window.innerWidth*0.5||r.height<80)return false;var cn=((el.className&&el.className.b",
        "aseVal!==undefined)?el.className.baseVal:(el.className||''))+' '+(el.id||'');if(/footer|copyright/i.test(cn))r",
        "eturn false;var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].s",
        "rc||'';if(isOpaqueImg(s))return true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){var a",
        "=as[j];if(isAdUrl(a.href)&&a.querySelector('img'))return true;}for(var j2=0;j2<as.length;j2++){var a2=as[j2];i",
        "f(foreign(a2.href)){var im2=a2.querySelector('img');if(im2)return true;}}var fbg=cs.backgroundImage||'';if(fbg",
        ".indexOf('url(')>=0){var fbm=fbg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(fbm&&fbm[1]&&isAdUrl(fbm[1]))return tr",
        "ue;}return false;}function isBottomPornAd(el){if(!el||el===document.body)return false;if(inAgeGate(el))return ",
        "false;if(isContent(el))return false;var cs=getComputedStyle(el);var r=el.getBoundingClientRect();var isFixed=c",
        "s.position==='fixed'||cs.position==='sticky'||cs.position==='absolute';if(isFixed){if(r.bottom<window.innerHei",
        "ght-2)return false;if(r.top<window.innerHeight*0.5)return false;}else{if(r.top<window.innerHeight*0.7)return f",
        "alse;}if(r.width<window.innerWidth*0.4)return false;if(r.height<40||r.height>300)return false;var t=(el.innerT",
        "ext||'');if(!/(约炮|兼职上门|国产自拍|性感少妇|裸聊)/.test(t))return false;if(el.querySelector('img,video'))return true;var bg",
        "=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)return true;var kids=el.querySelectorAll('*');for(var k=0;k<k",
        "ids.length;k++){var kb=getComputedStyle(kids[k]).backgroundImage||'';if(kb.indexOf('url(')>=0)return true;}ret",
        "urn false;}\nfunction isAdImageLink(el){if(!el||el.tagName!=='A')return false;if(inAgeGate(el))return false;if(",
        "isContent(el))return false;var href=el.getAttribute('href')||'';if(!href)return false;if(!foreign(href)&&!isAd",
        "Url(href))return false;var txt=(el.innerText||'').trim();if(txt.length>8)return false;var cs=getComputedStyle(",
        "el);var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)return true;var im=el.querySelector('img');if(im){v",
        "ar ir=im.getBoundingClientRect();if(ir.width>=100&&ir.height>=60)return true;var src=im.currentSrc||im.src||''",
        ";if(src&&(foreign(src)||isAdUrl(src)))return true;}return false;}\nfunction isAdIframe(el){if(!el||el.tagName!=",
        "='IFRAME')return false;if(isAgeGate(el))return false;if(inAgeGate(el))return false;var src=el.src||el.getAttri",
        "bute('src')||'';if(!src)return false;var h='';try{h=new URL(src,location.href).hostname.toLowerCase();}catch(e",
        "){}if(h.indexOf('cloudflare')>=0||h.indexOf('hcaptcha')>=0||h.indexOf('recaptcha')>=0)return false;var r=el.ge",
        "tBoundingClientRect();if(r.width<100||r.height<40)return false;var ar=r.width/Math.max(1,r.height);if(r.width>",
        "=300&&r.height>=150&&ar>1.5&&ar<1.9)return false;if(el.hasAttribute&&el.hasAttribute('allowfullscreen')&&r.wid",
        "th>=300&&r.height>=150)return false;if(isAdUrl(src))return true;if(ar>=3)return true;if(!foreign(src)&&!isAdUr",
        "l(src))return false;if(ar>=1.0&&ar<=1.4&&r.width>=250&&r.height>=200)return true;return false;}function isFixe",
        "dBottomIframe(el){if(!el||el.tagName!=='IFRAME')return false;if(isAgeGate(el))return false;if(isContent(el))re",
        "turn false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='stic",
        "ky')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<wi",
        "ndow.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60||r.height>250)retu",
        "rn false;var ar=r.width/Math.max(1,r.height);if(r.width>=300&&r.height>=150&&ar>1.5&&ar<1.9)return false;if(el",
        ".hasAttribute&&el.hasAttribute('allowfullscreen')&&r.width>=300&&r.height>=150)return false;var src=el.src||el",
        ".getAttribute('src')||'';var h='';try{h=new URL(src,location.href).hostname.toLowerCase();}catch(e){}if(h.inde",
        "xOf('cloudflare')>=0||h.indexOf('hcaptcha')>=0||h.indexOf('recaptcha')>=0)return false;if(src&&(foreign(src)||",
        "isAdUrl(src)))return true;if(!src)return true;if(ar>=3)return true;return false;}\nfunction fixedBottomAdRoot(e",
        "l){if(!el)return null;if(isAgeGate(el))return null;if(inAgeGate(el))return null;var cur=el,depth=0;while(cur&&",
        "cur!==document.body&&depth<4){if(isFixedBottomAd(cur))return cur;cur=cur.parentElement;depth++;}return null;}\n",
        "function isFixedBottomAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(isConten",
        "t(el))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position",
        "!=='sticky')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.b",
        "ottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60||r.height>",
        "250)return false;var hasForeign=false;if(el.tagName==='IFRAME'){var fs=el.src||'';if(fs&&(foreign(fs)||isAdUrl",
        "(fs)))hasForeign=true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j].hre",
        "f)){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){v",
        "ar s=ims[i].currentSrc||ims[i].src||'';if(s&&(foreign(s)||isAdUrl(s))){hasForeign=true;break;}}}if(!hasForeign",
        "){var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&(foreign(f)||",
        "isAdUrl(f))){hasForeign=true;break;}}}if(!hasForeign){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0){",
        "var bm=bg.match(/url\\(['\"]?([^'\")]+)['\"]?\\)/);if(bm&&bm[1]&&(foreign(bm[1])||isAdUrl(bm[1])))hasForeign=true;}",
        "}if(!hasForeign)return false;var hasVisual=false;if(el.tagName==='IFRAME')hasVisual=true;else if(el.querySelec",
        "tor('img'))hasVisual=true;if(!hasVisual){var bg2=cs.backgroundImage||'';if(bg2.indexOf('url(')>=0)hasVisual=tr",
        "ue;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getComputedStyle",
        "(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el.querySelect",
        "or('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nfunction isFixedBottomImageAd(el){if(!el|",
        "|el===document.body)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el",
        "))return false;if(isNavMenu(el))return false;if(el.querySelector&&el.querySelector('video'))return false;var c",
        "s=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')return false",
        ";var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<window.innerHeight",
        "-2)return false;if(r.top<window.innerHeight*0.5)return false;if(r.height<60)return false;var has",
        "Visual=false;if(el.tagName==='IMG'||el.tagName==='IFRAME')hasVisual=true;else if(el.querySelector&&el.querySel",
        "ector('img,iframe'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)hasVi",
        "sual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k=0;k<kids.length;k++){var kb=getComputedS",
        "tyle(kids[k]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual)return false",
        ";return true;}\nfunction isFixedBottomBareMedia(el){if(!el||el===document.body)return false;if(el.tagName!=='IM",
        "G'&&el.tagName!=='VIDEO')return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isConte",
        "nt(el))return false;if(isNavMenu(el))return false;if(el.closest){try{if(el.closest('.dplayer'))return false;}c",
        "atch(e){}}var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='stick",
        "y')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.bottom<win",
        "dow.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;var s='';try{s=el.currentSrc||el.s",
        "rc||'';}catch(e){s=el.src||'';}var cross=false;if(s&&(foreign(s)||isAdUrl(s)))cross=true;var click=false;if(el",
        ".getAttribute){try{if(el.getAttribute('onclick'))click=true;}catch(e2){}}if(!click){try{if(el.onclick)click=tr",
        "ue;}catch(e3){}}if(!cross&&!click)return false;return true;}\nfunction isImageCard(c){if(!c||c.nodeType!==1)ret",
        "urn false;if(c.tagName==='IMG')return false;va",
        "r hasImg=false;var ch=c.children;for(var i=0;i<ch.length;i++){if(ch[i].tagName==='IMG'){hasImg=true;break;}}if",
        "(!hasImg){var b=getComputedStyle(c).backgroundImage||'';if(b.indexOf('url(')>=0)hasImg=true;}if(!hasImg)return",
        " false;return txt(c).length<=16;}\nfunction isFixedBottomImageCardRow(el){if(!el||el===document.body)return fal",
        "se;if(isAgeGate(el)||inAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return false;",
        "if(el.tagName==='NAV')return false;if(el.closest){try{if(el.closest('nav,[role=\"navigation\"]'))return false;}c",
        "atch(e){}}var isClose=function(n){if(!n||n.nodeType!==1)return false;var t=((n.textContent||'')+(n.value||''))",
        ".replace(/\\s+/g,'');if(t==='\\u00d7'||t==='\\u2715'||t==='\\u2716'||t==='\\u2573'||t==='x'||t==='X'||t==='\\u5173\\u",
        "95ed')return true;var s=((n.id||'')+' '+((n.className&&n.className.baseVal!==undefined)?n.className.baseVal:(n",
        ".className||''))+' '+(n.getAttribute?n.getAttribute('aria-label')||'':'')).toLowerCase();if(s.indexOf('close')",
        ">=0||s.indexOf('dismiss')>=0)return true;return false;};if(el.querySelector&&el.querySelector('video,.dplayer'",
        "))return false;if(el.querySelector){if(el.querySelector('form,select,textarea'))return false;var bs=el.querySe",
        "lectorAll('button,input');for(var bi=0;bi<bs.length;bi++){if(bs[bi].tagName==='INPUT'&&(bs[bi].type||'').toLow",
        "erCase()==='hidden')continue;if(!isClose(bs[bi]))return false;}}var an=el,depth=0,found=false;while(an&&an!==d",
        "ocument.body&&depth<=2){var cs=getComputedStyle(an);if(cs.position==='fixed'||cs.position==='absolute'||cs.pos",
        "ition==='sticky'){found=true;break;}an=an.parentElement;depth++;}if(!found)return false;var r=an.getBoundingCl",
        "ientRect();if(r.width<window.innerWidth*0.02||r.height<window.innerHeight*0.01)return false;if(r.bottom<window",
        ".innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;var as=el.querySelectorAll('a[href]')",
        ";for(var i=0;i<as.length;i++){var h=as[i].getAttribute('href')||'';if(isPlaceholderHref(h))continue;if(!foreig",
        "n(as[i].href))return false;}var cards=0;var kids=el.children;for(var k=0;k<kids.length;k++){if(kids[k].tagName",
        "==='BUTTON'||kids[k].tagName==='INPUT')continue;if(isClose(kids[k]))continue;if(isImageCard(kids[k])){cards++;",
        "continue;}var gcs=kids[k].children;for(var g=0;g<gcs.length;g++){if(gcs[g].tagName==='BUTTON'||gcs[g].tagName=",
        "=='INPUT')continue;if(isClose(gcs[g]))continue;if(isImageCard(gcs[g]))cards++;}}if(cards<2)return false;return",
        " true;}\nfunction hideSpriteBarGroup(){var hidden=0;var isSpriteLike=function(el){if(!el||el.nodeType!==1||el==",
        "=document.body)return false;var tag=el.tagName;if(tag==='IMG'||tag==='VIDEO'||tag==='BUTTON'||tag==='INPUT'||t",
        "ag==='FORM'||tag==='SELECT'||tag==='TEXTAREA'||tag==='A'||tag==='IFRAME'||tag==='SCRIPT'||tag==='STYLE'||tag==",
        "='NAV')return false;try{if(isContent(el))return false;if(isAgeGate(el)||inAgeGate(el))return false;if(isChalle",
        "nge(el))return false;if(el.closest){var nv=el.closest('nav,[role=\"navigation\"]');if(nv)return false;}}catch(e)",
        "{return false;}if(txt(el).length>0)return false;if(el.children&&el.children.length>0)return false;if(el.queryS",
        "elector&&el.querySelector('a[href],img,video,button,input,form,select,textarea,iframe'))return false;var cs;tr",
        "y{cs=getComputedStyle(el);}catch(e2){return false;}if(!cs)return false;var pos=cs.position;if(pos!=='fixed'&&p",
        "os!=='absolute'&&pos!=='sticky')return false;var bg=cs.backgroundImage||'';if(bg.indexOf('url(')<0)return fals",
        "e;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.02||r.height<window.innerHeight*0.01)return ",
        "false;return true;};var isSeed=function(el){if(!isSpriteLike(el))return false;var r=el.getBoundingClientRect()",
        ";if(r.bottom<window.innerHeight-2)return false;if(r.top<window.innerHeight*0.5)return false;return true;};var ",
        "fire=function(el,type){try{el.dispatchEvent(new Event(type));return;}catch(e){}try{var ev=document.createEvent",
        "('Event');ev.initEvent(type,false,false);el.dispatchEvent(ev);}catch(e2){}};var removeSpriteStyle=function(tag",
        "){var low=tag.toLowerCase();var styles=document.querySelectorAll('style');for(var s=0;s<styles.length;s++){var",
        " st=styles[s];var tx=st.textContent||'';if(tx.length>2000)continue;var lx=tx.toLowerCase();if(lx.indexOf(low)<",
        "0)continue;if(lx.indexOf('background-image')<0)continue;if(lx.indexOf('url(')<0)continue;if(st.parentNode)st.p",
        "arentNode.removeChild(st);}};try{var body=document.body;if(!body)return 0;var kids=body.children;if(!kids||kid",
        "s.length===0)return 0;var groups={};var order=[];var i,j,k;for(i=0;i<kids.length;i++){var t=kids[i].tagName;if",
        "(!groups[t]){groups[t]=[];order.push(t);}groups[t].push(kids[i]);}for(var g=0;g<order.length;g++){var tagG=ord",
        "er[g];var members=groups[tagG];var likes=[];var seeds=0;var top=1000000000;for(j=0;j<members.length;j++){var m",
        "=members[j];if(isSpriteLike(m)){likes.push(m);var rr=m.getBoundingClientRect();if(rr.top<top)top=rr.top;if(isS",
        "eed(m))seeds++;}}if(seeds<4||likes.length<4)continue;var barH=window.innerHeight-top;var closeEl=null;var tol=",
        "window.innerHeight*0.05;for(k=0;k<kids.length;k++){var c=kids[k];if(c.tagName===tagG)continue;if(groups[c.tagN",
        "ame].length!==1)continue;var ct=c.tagName;if(ct==='IMG'||ct==='VIDEO'||ct==='BUTTON'||ct==='INPUT'||ct==='FORM",
        "'||ct==='SELECT'||ct==='TEXTAREA'||ct==='A'||ct==='IFRAME'||ct==='SCRIPT'||ct==='STYLE'||ct==='NAV')continue;i",
        "f(txt(c).length>0)continue;if(c.children&&c.children.length>0)continue;if(c.querySelector&&c.querySelector('a[",
        "href],img,video,button,input,form,select,textarea,iframe'))continue;var bad=false;try{if(isContent(c)||isAgeGa",
        "te(c)||inAgeGate(c)||isChallenge(c))bad=true;}catch(e3){bad=true;}if(bad)continue;var cs2;try{cs2=getComputedS",
        "tyle(c);}catch(e4){continue;}if(!cs2)continue;var pos2=cs2.position;if(pos2!=='fixed'&&pos2!=='absolute'&&pos2",
        "!=='sticky')continue;var bg2=cs2.backgroundImage||'';if(bg2.indexOf('url(')<0)continue;var r2=c.getBoundingCli",
        "entRect();if(r2.width<=0||r2.height<=0)continue;if(r2.bottom>=window.innerHeight-2)continue;if(r2.bottom<top-t",
        "ol||r2.bottom>top+tol)continue;closeEl=c;break;}if(closeEl){fire(closeEl,'touchstart');if(closeEl.isConnected)",
        "fire(closeEl,'click');}for(j=0;j<likes.length;j++){likes[j].style.display='none';hidden++;}if(likes.length>0)n",
        "oteHide('hideSpriteBarGroup',likes[0]);removeSpriteStyle(tagG);var pb=body.style.paddingBottom||'';if(pb){var ",
        "n=parseInt(pb,10);if(!isNaN(n)&&Math.abs(n-barH)<=2)body.style.paddingBottom='';}}}catch(e){}return hidden;}\nf",
        "unction isGenericBottomBanner(el){if(!el||el===document.body)return false;if(isAgeGate(el))ret",
        "urn false;if(isChallenge(el))return false;if(isContent(el))return false;if(isNavMenu(el))return false;if(el.qu",
        "erySelector&&el.querySelector('video'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.p",
        "osition!=='absolute'&&cs.position!=='sticky')return false;var r=el.getBoundingClientRect();if(r.width<window.i",
        "nnerWidth*0.4)return false;if(r.bottom<window.innerHeight-60)return false;if(r.top<window.innerHeight*0.5)retu",
        "rn false;if(r.height<60||r.height>250)return false;var hasForeign=false;if(el.tagName==='IFRAME'){var fs=el.sr",
        "c||'';if(fs&&foreign(fs))hasForeign=true;}var as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){i",
        "f(foreign(as[j].href)){hasForeign=true;break;}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;",
        "i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';if(s&&foreign(s)){hasForeign=true;break;}}}if(!hasFo",
        "reign){var frs=el.querySelectorAll('iframe');for(var k=0;k<frs.length;k++){var f=frs[k].src||'';if(f&&foreign(",
        "f)){hasForeign=true;break;}}}if(!hasForeign)return false;var hasVisual=false;if(el.tagName==='IFRAME')hasVisua",
        "l=true;else if(el.querySelector('img'))hasVisual=true;if(!hasVisual){var bg2=cs.backgroundImage||'';if(bg2.ind",
        "exOf('url(')>=0)hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k",
        "2++){var kb=getComputedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}i",
        "f(!hasVisual&&el.querySelector(\'iframe\'))hasVisual=true;if(!hasVisual)return false;return true;}\n",
        "var __museSameHostStats=null;function sameHostLinkStats(){if(__museSameHostStats)return __museSameHostStats;va",
        "r as=document.querySelectorAll('a[href]');var same=0,total=0;for(var i=0;i<as.length;i++){var h=as[i].getAttri",
        "bute('href')||'';if(isPlaceholderHref(h))continue;total++;if(!foreign(as[i].href))same++;}__museSameHostStats=",
        "{same:same,total:total};return __museSameHostStats;}\n",
        "function isGe",
        "nericMidBanner(el){if(!el||el===document.body)return false;",
        "var __st=sameHostLinkStats();if(__st.same<2||__st.same*10<__st.total)return false;",
        "if(isAgeGate(el))return false;if(isChallenge(el))re",
        "turn false;if(isContent(el))return false;if(isNavMenu(el))return false;if(el.querySelector&&el.querySelector(\'",
        "video\'))return false;if(el.querySelector&&el.querySelector(\'iframe\'))return false;var cs=getComputedStyle(el);",
        "if(cs.position===\'fixed\'||cs.position===\'sticky\')return false;var r=el.getBoundingClientRect();if(r.width<wind",
        "ow.innerWidth*0.02||r.height<window.innerHeight*0.01)return false;var pe=el.parentElement;if(!pe)return false;",
        "var pr=pe.getBoundingClientRect();if(r.width<pr.width*0.8)return false;var gp=pe.parentElement;if(gp){var gr=g",
        "p.getBoundingClientRect();if(pr.width<gr.width*0.5)return false;}var hasForeign=false;var as=el.querySelectorA",
        "ll(\'a[href]\');for(var j=0;j<as.length;j++){var h=as[j].getAttribute(\'href\')||\'\';if(isPlaceholderHref(h))contin",
        "ue;if(foreign(as[j].href))hasForeign=true;else return false;}if(!hasForeign)return false;var hasVisual=false;i",
        "f(el.querySelector(\'img\'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||\'\';if(bg.indexOf(\'url(\')>=0",
        ")hasVisual=true;}if(!hasVisual){var kids=el.querySelectorAll(\'*\');for(var k2=0;k2<kids.length;k2++){var kb=get",
        "ComputedStyle(kids[k2]).backgroundImage||\'\';if(kb.indexOf(\'url(\')>=0){hasVisual=true;break;}}}if(!hasVisual)re",
        "turn false;return true;}\nfunction isFixedTopBanner(el){if(!el||el===document.body)return false;i",
        "f(isAgeGate(el))return false;if(isChallenge(el))return false;if(isContent(el))return false;if(isNavMenu(el))re",
        "turn false;if(el.querySelector&&el.querySelector('video'))return false;var cs=getComputedStyle(el);if(cs.posit",
        "ion!=='fixed')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r",
        ".bottom>=window.innerHeight-60)return false;if(r.height<60||r.height>250)return false;var hasForeign=false;var",
        " as=el.querySelectorAll('a[href]');for(var j=0;j<as.length;j++){if(foreign(as[j].href)){hasForeign=true;break;",
        "}}if(!hasForeign){var ims=el.querySelectorAll('img');for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||im",
        "s[i].src||'';if(s&&foreign(s)){hasForeign=true;break;}}}if(!hasForeign)return false;var hasVisual=false;if(el.",
        "querySelector('img'))hasVisual=true;if(!hasVisual){var bg=cs.backgroundImage||'';if(bg.indexOf('url(')>=0)hasV",
        "isual=true;}if(!hasVisual){var kids=el.querySelectorAll('*');for(var k2=0;k2<kids.length;k2++){var kb=getCompu",
        "tedStyle(kids[k2]).backgroundImage||'';if(kb.indexOf('url(')>=0){hasVisual=true;break;}}}if(!hasVisual&&el.que",
        "rySelector('iframe'))hasVisual=true;if(!hasVisual)return false;return true;}\nfunction isAdLinkGrid(el){if(!el|",
        "|el===document.body)return false;if(isAgeGate(el))return false;if(isContent(el))return false;if(el.querySelect",
        "or('video,iframe'))return false;if(el.querySelector('p'))return false;var as=el.querySelectorAll('a[href]');if",
        "(as.length<8)return false;var total=0,foreignCount=0,shortText=0;for(var i=0;i<as.length;i++){var a=as[i];var ",
        "href=a.getAttribute('href')||'';if(!href)continue;total++;if(foreign(href))foreignCount++;var t=txt(a);if(t.le",
        "ngth>0&&t.length<=6)shortText++;}if(total<8)return false;if(foreignCount<total*0.7)return false;if(shortText<t",
        "otal*0.7)return false;return true;}\nfunction isBannerAd(a){if(!a||a===document.body)return false;if(inAgeGate(",
        "a))return false;if(isContent(a))return false;var href=a.getAttribute('href')||'';if(!href)return false;if(!for",
        "eign(href))return false;var im=a.querySelector('img');if(!im)return false;if(txt(a).length>8)return false;var ",
        "r=im.getBoundingClientRect();if(r.width<window.innerWidth*0.4)return false;if(r.height<20)return false;return ",
        "true;}\nfunction isFloatAd(el){if(!el||el===document.body)return false;if(isAgeGate(el))return false;if(el.quer",
        "ySelector('.dplayer'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolu",
        "te')return false;var r=el.getBoundingClientRect();if(r.width>=window.innerWidth*0.6)return false;if(r.height>=",
        "window.innerHeight*0.4)return false;if(r.width<20||r.height<20)return false;if(r.bottom<window.innerHeight-120",
        ")return false;if(r.top<window.innerHeight*0.4)return false;var hasClose=false;var cbs=el.querySelectorAll('[cl",
        "ass*=\"close\"],[id*=\"close\"]');if(cbs.length>0)hasClose=true;if(!hasClose){var t0=txt(el);if(t0.indexOf('\\u00d7",
        "')>=0||t0.indexOf('\\u2715')>=0||t0.indexOf('\\u2573')>=0)hasClose=true;}if(!hasClose)return false;var hasCarous",
        "el=false;var t=txt(el);if(/\\d+\\s*\\/\\s*\\d+/.test(t))hasCarousel=true;if(!hasCarousel){var pg=el.querySelectorAl",
        "l('[class*=\"pagination\"],[class*=\"swiper-pagination\"],[class*=\"dot\"],[class*=\"dots\"],[class*=\"indicator\"]');if",
        "(pg.length>0)hasCarousel=true;}var hasForeign=false;var as=el.querySelectorAll('a[href]');for(var i=0;i<as.len",
        "gth;i++){var h=as[i].href||'';if(h&&(foreign(h)||isAdUrl(h))){hasForeign=true;break;}}if(!hasForeign){var ims=",
        "el.querySelectorAll('img');for(var j=0;j<ims.length;j++){var s2=ims[j].currentSrc||ims[j].src||'';if(s2&&(fore",
        "ign(s2)||isAdUrl(s2))){hasForeign=true;break;}}}if(!hasForeign&&el.tagName==='A'){var h0=el.href||'';if(h0&&(f",
        "oreign(h0)||isAdUrl(h0)))hasForeign=true;}if(hasForeign||hasCarousel)return true;return false;}function isAdCl",
        "ose(cls){var t=(' '+cls+' ').toLowerCase().split(/[\\s_-]+/);var a=false,c=false;for(var i=0;i<t.length;i++){if",
        "(t[i]==='ad'||t[i]==='ads')a=true;if(t[i].indexOf('close')>=0)c=true;}return a&&c;}window.__museLastTap=0;wind",
        "ow.__museTapImg=false;document.addEventListener('click',function(e){window.__museLastTap=Date.now();var t=e?e.",
        "target:null;var ti=false;if(t&&t.closest){if(t.closest('img'))ti=true;else{var a=t.closest('a');if(a&&a.queryS",
        "elector('img'))ti=true;}}window.__museTapImg=ti;},true);function recentTap(){return (Date.now()-(window.__muse",
        "LastTap||0))<1500;}function isClickTrap(el){if(!el||el===document.body)return false;if(el.querySelector('video",
        ",iframe,img'))return false;if(txt(el).length>0)return false;var cs=getComputedStyle(el);if(cs.position!=='fixe",
        "d')return false;var op=parseFloat(cs.opacity);if(!(op<0.05))return false;var r=el.getBoundingClientRect();if(r",
        ".width<window.innerWidth*0.02&&r.height<window.innerHeight*0.02)return false;return true;}function isOpaqueMod",
        "al(el){if(!el||el===document.body)return false;if(el.__museLb)return false;if(isAgeGate(el))return false;if(is",
        "Challenge(el))return false;if(isNavMenu(el))return false;if(el.querySelector('video,iframe'))return false;var ",
        "cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')return false;var r=el.getBoundingCl",
        "ientRect();if(r.width<window.innerWidth*0.4&&r.height<window.innerHeight*0.5)return false;var ims=el.querySele",
        "ctorAll('img');var hasOpaque=false;for(var i=0;i<ims.length;i++){var s=ims[i].currentSrc||ims[i].src||'';if(is",
        "OpaqueImg(s)){hasOpaque=true;break;}}if(!hasOpaque)return false;if(recentTap()&&window.__museTapImg){el.__muse",
        "Lb=1;return false;}return true;}function isBigOverlay(el){if(!el||el===document.body)return false;if(el.__muse",
        "Lb)return false;if(isAgeGate(el))return false;if(isChallenge(el))return false;if(isNavMenu(el))return false;if",
        "(el.querySelector('video,iframe'))return false;var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.positi",
        "on!=='absolute')return false;var r=el.getBoundingClientRect();if(r.width<window.innerWidth*0.8||r.height<windo",
        "w.innerHeight*0.7)return false;if(!el.querySelector('img'))return false;if(recentTap()&&window.__museTapImg){e",
        "l.__museLb=1;return false;}return true;}function preRollBox(cd){var n=cd;while(n&&n!==document.body){if(n.quer",
        "ySelector&&n.querySelector('video'))return n;n=n.parentElement;}return cd.parentElement||cd;}function adOverla",
        "y(cd,box){if(!box||!cd)return null;var v=box.querySelector('video');if(!v)return null;var cur=cd;while(cur.par",
        "entElement&&cur.parentElement!==box){cur=cur.parentElement;}if(cur===cd)return null;if(cur.contains&&cur.conta",
        "ins(v))return null;return cur;}function clickNeutral(el){if(!el)return;var oo=window.open;try{window.open=func",
        "tion(){return null;};el.click();}catch(e){}try{window.open=oo;}catch(e){}}function killPreRollOne(cd){if(!cd||",
        "!cd.isConnected)return;var box=preRollBox(cd);if(!box)return;var ov=adOverlay(cd,box);var scope=ov||box;var im",
        "g=scope.querySelector('.dplayer-pre-img');if(!img){var ims=scope.querySelectorAll('img'),best=null,ba=0;for(va",
        "r i=0;i<ims.length;i++){var r=ims[i].getBoundingClientRect(),a=r.width*r.height;if(a>ba){ba=a;best=ims[i];}}im",
        "g=best;}if(img)clickNeutral(img);var cd2=cd,box2=box,ov2=ov,img2=img;setTimeout(function(){if(!cd2.isConnected",
        ")return;if(ov2&&ov2.parentNode){ov2.parentNode.removeChild(ov2);}else{var ns=box2.querySelectorAll('.dplayer-p",
        "re-bg,.dplayer-pre-img,.dplayer-pre-countdown');for(var k=0;k<ns.length;k++){if(ns[k].parentNode)ns[k].parentN",
        "ode.removeChild(ns[k]);}var vs=box2.querySelectorAll('video');for(var vi=1;vi<vs.length;vi++){var av=vs[vi];va",
        "r wp=av.parentElement;if(av.parentNode)av.parentNode.removeChild(av);if(wp&&wp!==box2&&wp.children.length===0&",
        "&wp.parentNode)wp.parentNode.removeChild(wp);}if(img2&&img2.parentNode){var ip=img2.parentElement;img2.parentN",
        "ode.removeChild(img2);if(ip&&ip!==box2&&ip.children.length===0&&ip.parentNode)ip.parentNode.removeChild(ip);}i",
        "f(cd2.parentNode)cd2.parentNode.removeChild(cd2);}box2.classList.remove('dplayer-pre-playing');box2.classList.",
        "remove('dplayer-pre-clickable');var v=box2.querySelector('video');if(v){try{v.play();}catch(e){}}},200);}funct",
        "ion killPreRoll(){var cds=document.querySelectorAll('.dplayer-pre-countdown');for(var i=0;i<cds.length;i++){ki",
        "llPreRollOne(cds[i]);}var els=document.querySelectorAll('div,span,button');for(var j=0;j<els.length;j++){var e",
        "l2=els[j];if(!el2.isConnected)continue;if(el2.querySelector('video,iframe'))continue;var t=txt(el2);if(t.lengt",
        "h>16)continue;if(/^(广告)?(\\d{1,2}:\\d{2}|\\d+s?|\\d+秒)后(自动)?(关闭|关)$/.test(t)){killPreRollOne(el2);}else if(/^(跳过广告",
        "|SkipAd)$/i.test(t)){var h=el2.tagName==='A'?(el2.getAttribute('href')||''):'';if(el2.tagName!=='A'||h.indexOf",
        "('javascript:')===0)clickNeutral(el2);}}}/*push v40 S1*/try{if(window.MutationObserver&&document.documentElement&&!window.__museBKOb){window.__museBKOb=new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var ns=ms[i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;var l=n.tagName==='IFRAME'?[n]:(n.getElementsByTagName?n.getElementsByTagName('iframe'):[]);for(var k=0;k<l.length;k++){var f=l[k];if(f.__museBK||!bkSrcBlank(f))continue;try{bkHook(f);bkCheck(f);}catch(e){}}}}});window.__museBKOb.observe(document.documentElement,{childList:true,subtree:true});}}catch(e){}/*push v41 S2*/try{if(window.MutationObserver&&document.documentElement&&!window.__museSSPOb){window.__museSSPOb=new MutationObserver(function(ms){var hit=false,bd=false;for(var i=0;i<ms.length;i++){var ns=ms[i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;var c=String(n.className||'');if(c.indexOf('modal-backdrop')>=0)bd=true;if(c.indexOf('ssp-')>=0||c.indexOf('ad-dialog')>=0||n.id==='tip_modal'||(n.hasAttribute&&(n.hasAttribute('data-ssp-slot-key')||n.hasAttribute('data-ssp-layout-role')))){hit=true;continue;}try{if(n.querySelector&&n.querySelector('.ssp-layout,[data-ssp-slot-key],[data-ssp-layout-role],.ad-dialog,#tip_modal'))hit=true;}catch(e){}}}if(hit){try{killSspSlots();}catch(e){}}if(bd&&window.__museSSPDone){setTimeout(function(){try{sspUnlock();}catch(e){}},600);}});window.__museSSPOb.observe(document.documentElement,{childList:true,subtree:true});}}catch(e){}function run(){",
        "__museSameHostStats=null;",
        "if(document.fullscreenElement)return;try{sweep();}catc",
        "h(e){}}run();setTimeout(run,1200);setTimeout(run,3500);var sweepTimer=setInterval(run,5000);setTimeout(functio",
        "n(){try{clearInterval(sweepTimer);}catch(e){}},60000);if(document.addEventListener){document.addEventListener",
        "('fullscreenchange',function(){if(!document.fullscreenElement){var vs=document.querySelectorAll('video');for(v",
        "ar i=0;i<vs.length;i++){var v=vs[i];if(v.style&&v.style.display==='none')v.style.display='';var p=v.parentElem",
        "ent;while(p&&p!==document.body){if(p.style&&p.style.display==='none')p.style.display='';p=p.parentElement;}}se",
        "tTimeout(function(){run();},300);}});}if(window.addEventListener){window.addEventListener('pageshow',function(",
        "){run();});}if(document.addEventListener){document.addEventListener('visibilitychange',function(){if(!document",
        ".hidden)run();});}var pending=null;var ob=new MutationObserver(function(){if(pending)return;pending=setTimeout",
        "(function(){pending=null;run();},100);});if(document.documentElement)ob.observe(document.documentElement,{chil",
        "dList:true,subtree:true,attributes:true,attributeFilter:['style']});setTimeout(function(){try{ob.disconnect();",
        "}catch(e){}},300000);var preOb=new MutationObserver(function(muts){for(var i=0;i<muts.length;i++){var ns=muts[",
        "i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;var cd=null;if(n.classLi",
        "st&&n.classList.contains('dplayer-pre-countdown'))cd=n;else if(n.querySelector)cd=n.querySelector('.dplayer-pr",
        "e-countdown');if(cd){killPreRollOne(cd);return;}}}});if(document.documentElement)preOb.observe(document.docume",
        "ntElement,{childList:true,subtree:true});setTimeout(function(){try{preOb.disconnect();}catch(e){}},600000);/*push v29 R31-2*/var f1Ob=new MutationObserver(function(muts){for(var i=0;i<muts.length;i++){var ns=muts[i].addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(!n||n.nodeType!==1)continue;if(n.hasAttribute&&n.hasAttribute('data-cl-spot')){try{killClSpots();}catch(e){}}var l=n.tagName==='IFRAME'?[n]:(n.querySelectorAll?n.querySelectorAll('iframe'):[]);for(var k=0;k<l.length;k++){try{f1Hook(l[k]);f1Judge(l[k]);}catch(e){}}}}});if(document.documentElement)f1Ob.observe(document.documentElement,{childList:true,subtree:true});setTimeout(function(){try{f1Ob.disconnect();}catch(e){}},600000);})(",
        ");",
        // push v49/v33（123av 弹窗修法 2）：全屏透明点击陷阱（固定/绝对定位盖满视口、无文字图片视频表单验证、指向外站或 # 加开窗）→ 移除并盯重插。
        "(function(){try{if(window.__museTrapOn)return;var __t0=(document.title||'').toLowerCase();if(__t0.indexOf('请稍候')>=0||__t0.indexOf('just a moment')>=0)return;window.__museTrapOn=1;function reg(h){var p=String(h||'').toLowerCase().split('.');return p.length>=2?p.slice(-2).join('.'):String(h||'').toLowerCase();}function foreignHref(h){try{var u=new URL(h,location.href);if(u.protocol!=='http:'&&u.protocol!=='https:')return false;return reg(u.hostname)!==reg(location.hostname);}catch(e){return false;}}function placeholder(h){h=String(h||'').trim().toLowerCase();return h===''||h.charAt(0)==='#'||h.indexOf('javascript:')===0;}function opensWindow(a){if(String(a.getAttribute('target')||'').toLowerCase()==='_blank')return true;if(typeof a.onclick==='function')return true;if(a.getAttribute('onclick'))return true;return false;}function guarded(el){var n=el,d=0;while(n&&n.nodeType===1&&d<8){var c=(typeof n.className==='string')?n.className:((n.className&&n.className.baseVal)||'');var id=((n.id||'')+' '+c).toLowerCase();if(/agedisclaimer|age[-_]?verif|age[-_]?gate|age[-_]?check|over18|under18|age[-_]?confirm|challenge|captcha|turnstile|cf-/.test(id))return true;if(n!==document.body&&n!==document.documentElement&&n.querySelector&&n.querySelector('video,iframe'))return true;n=n.parentElement;d++;}return false;}function blankEl(el){if((el.textContent||'').trim()!=='')return false;if(el.querySelector('img,video,iframe,svg,canvas,picture,object,embed,form,input,button,select,textarea,label'))return false;var cs=getComputedStyle(el);if(cs.backgroundImage&&cs.backgroundImage!=='none')return false;return true;}function coversViewport(el){var cs=getComputedStyle(el);if(cs.position!=='fixed'&&cs.position!=='absolute')return false;if(cs.display==='none'||cs.visibility==='hidden'||cs.pointerEvents==='none')return false;var r=el.getBoundingClientRect();var de=document.documentElement;var W=(de&&de.clientWidth)||window.innerWidth;var H=(de&&de.clientHeight)||window.innerHeight;if(!(W>0&&H>0))return false;return r.left<=1&&r.top<=1&&r.right>=W-1&&r.bottom>=H-1;}function trapWhy(a){if(!a||a.tagName!=='A'||!a.isConnected)return '';if(guarded(a))return '';if(!coversViewport(a))return '';if(!blankEl(a))return '';var h=a.getAttribute('href')||'';if(foreignHref(h))return '全屏透明外站链接';if(placeholder(h)&&opensWindow(a))return '全屏透明开窗链接';return '';}function note(a,why){try{window.__museTrapN=(window.__museTrapN||0)+1;if(window.__museTrapN>20)return;if(window.museClickGuard&&window.museClickGuard.mark)window.museClickGuard.mark(String(a.getAttribute('href')||''),String(location.href||''),why,'trap');}catch(e){}}function zap(a){var why=trapWhy(a);if(!why)return false;var p=a.parentElement;note(a,why);if(a.parentNode)a.parentNode.removeChild(a);try{if(p&&p!==document.body&&p!==document.documentElement&&p.children.length===0&&blankEl(p)&&coversViewport(p)&&!guarded(p))p.parentNode.removeChild(p);}catch(e){}return true;}function sweep(root){try{var l=(root&&root.tagName==='A')?[root]:((root||document).querySelectorAll?(root||document).querySelectorAll('a'):[]);for(var i=0;i<l.length;i++){try{zap(l[i]);}catch(e){}}}catch(e){}}sweep(document);setTimeout(function(){sweep(document);},1000);setTimeout(function(){sweep(document);},3000);if(window.MutationObserver&&document.documentElement){var ob=new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type==='attributes'){if(m.target&&m.target.tagName==='A')sweep(m.target);continue;}var ns=m.addedNodes;for(var j=0;j<ns.length;j++){var n=ns[j];if(n&&n.nodeType===1)sweep(n);}}});ob.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','href','target','class']});setTimeout(function(){try{ob.disconnect();}catch(e){}},600000);}}catch(e){}})();"
    };
    // 1080 视口：电视浏览器按固定宽度布局，桌面站导航不再裹成多行（吃瓜/黑料/大赛上框占比大）。
    // push tv v32：宽度改读设置「浏览器缩放」（1920/1600/1280，默认 1600），页面提交时现读。
    private static String viewportJs() {
        return VIEWPORT_JS_TPL.replace("__W__", String.valueOf(Setting.getWebViewport()));
    }

    private static final String VIEWPORT_JS_TPL = "(function(){try{var ms=document.getElementsByTagName('meta');var m=null;for(var i=0;i<ms.length;i++){if(ms[i].getAttribute('name')==='viewport'){m=ms[i];break;}}if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.head.appendChild(m);}m.setAttribute('content','width=__W__');}catch(e){}})();";
    private static final String CLEAN_JS;
    static {
        StringBuilder sb = new StringBuilder();
        for (String p : CLEAN_JS_PARTS) sb.append(p);
        CLEAN_JS = sb.toString();
    }
    // push v1: fuzi 多线探针（MacCMS 播放页全部线路 + 每线选集表）与页面 video 直取探针——逐字取自 fuzi BrowserActivity，不转录。
                            private static final String PROBE_JS = "(function(){function txt(el){return el?(el.innerText||'').trim():'';}function cleanName(t){return (t||'').replace(/[\\uE000-\\uF8FF]/g,'').replace(/\\s+/g,' ').trim();}function tabText(el){if(!el)return "
        + "'';var parts=[];var ns=el.childNodes;for(var i=0;i<ns.length;i++){var n=ns[i];if(n.nodeType===3)parts.push(n.textContent||'');else if(n.nodeType===1){var tg=n.tagName;var cl=n.getAttribute('class')||'';if(tg==='I'||/badge|num|count/i.test(cl))continue;var "
        + "ct=(n.innerText||n.textContent||'').trim();if(ct==='广告'||/^广告?\\d+\\s*集$/.test(ct))continue;parts.push(n.innerText||n.textContent||'');}}return cleanName(parts.join(' '));}function cleanCand(L){var c=L.cand||'';if(!c)return "
        + "c;var cnt=L.order.length;var m=c.match(/^(.*?)[-_\\s·]*?(\\d+)$/);if(m&&parseInt(m[2],10)===cnt&&m[1]&&m[1].trim())c=m[1].trim();return c.replace(/[-_\\s·]+$/,'');}function picOf(){try{var m=document.querySelector('meta[property=\"og:image\"],meta[name=\"twitter:image\"]');if(m&&m.content){try{return "
        + "new URL(m.content,location.href).href}catch(e){return m.content}}var ps=['.vodpic img','.detail-pic img','.poster img','[class*=\"vodpic\"] img','[class*=\"poster\"] img','.detail .pic img','.video-info "
        + ".pic img','.stui-content__thumb img','.myui-content__thumb img','.vod-detail img','.detail-box img','.play-pic img','[class*=\"thumb\"] img','[class*=\"cover\"] img','.thumb img','.cover img'];for(var "
        + "i=0;i<ps.length;i++){var im=document.querySelector(ps[i]);if(!im)continue;var u=im.getAttribute('data-src')||im.getAttribute('data-original')||im.getAttribute('src')||'';if(u&&u.indexOf('data:')!==0){try{return "
        + "new URL(u,location.href).href}catch(e){return u}}}var imgs=document.images;for(var g=0;g<imgs.length&&g<6;g++){var gi=imgs[g];var gu=gi.getAttribute('data-src')||gi.getAttribute('src')||'';if(!gu||gu.indexOf('data:')===0)continue;var "
        + "okN=(gi.naturalWidth||0)>=400&&(gi.naturalHeight||0)>=150;var okC=(gi.clientWidth||0)>=300&&(gi.clientHeight||0)>=140;if(!okN&&!okC)continue;var ga=gi.closest?gi.closest('a'):null;if(ga){var gh=ga.getAttribute('href')||'';if(/^https?:\\/\\//i.test(gh)&&gh.indexOf(location.host)<0)continue;}try{return "
        + "new URL(gu,location.href).href}catch(e){return gu}}var bs=['.this-pic-bj','[class*=\"pic-bj\"]','[class*=\"backdrop\"]'];for(var j=0;j<bs.length;j++){var be=document.querySelector(bs[j]);if(!be)continue;var "
        + "bg='';try{bg=getComputedStyle(be).backgroundImage||''}catch(e){}var bm=/url\\([\"']?([^\"')]+)[\"']?\\)/.exec(bg);if(bm&&bm[1]&&bm[1].indexOf('data:')!==0){try{return new URL(bm[1],location.href).href}catch(e){return "
        + "bm[1]}}}}catch(e){}return '';}function epName(t){var m=t.match(/第\\s*\\d+\\s*[集期话回]|\\d+\\s*[集期]/);if(m)return m[0].replace(/\\s+/g,'');if(/^\\d{1,3}$/.test(t))return '第'+parseInt(t,10)+'集';var dm=t.match(/^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})[日]?$/);if(dm)return "
        + "dm[1]+'-'+('0'+dm[2]).slice(-2)+'-'+('0'+dm[3]).slice(-2);if(/^\\d{8}$/.test(t))return t.slice(0,4)+'-'+t.slice(4,6)+'-'+t.slice(6,8);return '';}var STOP=/上一|下一|预告|播放|详情|首页|返回|收藏|分享|评论|清空|倒序|正序|排序|切换|展开|收起|更多|全部|点赞|纠错|下载|下集|上集/;function "
        + "stopWord(t){return STOP.test(t)||/^(线路|来源)\\d{0,2}$/.test(t);}function isSourceTab(t){return /^播放源\\d{1,2}$/.test(t);}var pm=location.pathname.match(/(\\d+)-(\\d+)-(\\d+)\\.html/);var vid=pm?pm[1]:'',sid=pm?pm[2]:'',curEp=pm?parseInt(pm[3],10):-1;if(!vid){var "
        + "dv=location.pathname.match(/\\/(?:voddetail|vodplay|movie|detail|view|watch)\\/(\\d+)/);if(dv)vid=dv[1];}var lines={},lineOrder=[];function lineOf(s){if(!lines[s]){lines[s]={sid:s,cand:'',candRaw:'',raws:[],eps:{},order:[],singles:[]};lineOrder.push(lines[s]);}return "
        + "lines[s];}function put(s,aep,name,url){var L=lineOf(s);var old=L.eps[aep];if(old){if(!old.clean&&name.clean){old.name=name.text;old.clean=true;old.url=url;}return;}var en={name:name.text,url:url,ep:aep,cur:(sid!==''&&s===sid&&aep===curEp),clean:name.clean};L.eps[aep]=en;L.order.push(en);}function "
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
        + "bk=0;bk<tabNames.length;bk++){var nmB=tabNames[bk]||'';if(!nmB)continue;if(!tabFor[bSids[bk]])tabFor[bSids[bk]]=nmB;}break;}var out=[];for(var z=0;z<sorted.length&&z<8;z++){var L=sorted[z];L.order.sort(function(x,y){return "
        + "x.ep-y.ep;});L.cand=cleanCand(L);var tn=tabFor[L.sid]||'';var nm=tn||((sid!==''&&L.sid===sid&&activeName)?activeName:(L.cand||('线路'+(out.length+1))));var eps=[];for(var w=0;w<L.order.length&&w<300;w++){var "
        + "o=L.order[w];eps.push({name:o.name,url:o.url,cur:o.cur?1:0});}out.push({sid:L.sid,name:nm,cur:(sid!==''&&L.sid===sid)?1:0,episodes:eps});}function ownHidden(el){if(!el)return true;try{if(el.hidden)return "
        + "true;}catch(e){}try{var s=el.getAttribute?el.getAttribute('style')||'':'';if(/display\\s*:\\s*none/i.test(s)||/visibility\\s*:\\s*hidden/i.test(s))return true;}catch(e){}try{var c=el.getAttribute?el.getAttribute('class')||'':'';if(/(^|[\\s_\\-])(displayNone|display-none|hidden|hide|invisible|collapsed)([\\s_\\-]|$)/i.test(c))return "
        + "true;}catch(e){}return false;}function epNumOf(t){var m=/(\\d+)/.exec(t||'');if(!m)return -1;try{return parseInt(m[1],10);}catch(e){return -1;}}function btnUrlOf(el){try{if(el.tagName==='A'){var h=el.getAttribute('href')||'';if(h)return "
        + "absUrl(h);}}catch(e){}return '';}function isCurBtn(el){try{var c=el.getAttribute('class')||'';if(/(^|\\s)(active|current|cur|selected|on|yH)(\\s|$)/.test(c))return true;if(el.getAttribute('aria-current'))return "
        + "true;if(el.getAttribute('aria-selected')==='true')return true;}catch(e){}return false;}function isCurTab(el){try{var c=el.getAttribute('class')||'';if(/(^|\\s)(active|current|cur|selected|on)(\\s|$)/.test(c))return "
        + "true;if(el.getAttribute('aria-selected')==='true')return true;}catch(e){}return false;}function tabPanelOf(tab){var tgt='';try{tgt=tab.getAttribute('aria-controls')||tab.getAttribute('data-target')||'';}catch(e){}if(!tgt){try{var "
        + "h=tab.getAttribute('href')||'';if(h&&h.charAt(0)==='#')tgt=h;}catch(e){}}if(tgt){try{var id=tgt.charAt(0)==='#'?tgt.slice(1):tgt;var p=document.getElementById(id);if(p)return p;}catch(e){}}try{var par=tab.parentElement;if(!par)return "
        + "null;var sibs=par.querySelectorAll('[role=\"tab\"]');var idx=-1;for(var k=0;k<sibs.length;k++){if(sibs[k]===tab){idx=k;break;}}if(idx<0)return null;var anc=par;for(var d=0;d<4&&anc;d++){var panels=anc.querySelectorAll('[role=\"tabpanel\"]');if(panels.length===sibs.length)return "
        + "panels[idx];anc=anc.parentElement;}}catch(e){}return null;}function panelEps(panel){var eps=[];var singles=[];var seen={};try{var btns=panel.querySelectorAll('button,a');for(var b=0;b<btns.length;b++){var "
        + "btn=btns[b];if(ownHidden(btn))continue;try{if(btn.getAttribute('role')==='tab'||btn.getAttribute('data-toggle')==='tab')continue;}catch(e){}if(hasAdMark(btn))continue;var t=cleanName(txt(btn));if(!t||t.length>20)continue;var "
        + "bd={id:'',mid:'',path:''};try{bd.id=btn.getAttribute('data-id')||'';bd.mid=btn.getAttribute('data-mid')||'';bd.path=btn.getAttribute('data-path')||'';}catch(e){}var nm=epName(t);if(nm){if(seen[nm])continue;seen[nm]=1;eps.push({name:nm,url:btnUrlOf(btn),cur:isCurBtn(btn)?1:0,id:bd.id,mid:bd.mid,path:bd.path,_i:b});}else{if(stopWord(t))continue;if(/^[【\\[]/.test(t))continue;singles.push({name:t,url:btnUrlOf(btn),cur:isCurBtn(btn)?1:0,id:bd.id,mid:bd.mid,path:bd.path,_i:b});}}if(!eps.length&&singles.length===1){eps.push(singles[0]);}}catch(e){}eps.sort(function(x,y){var "
        + "nx=epNumOf(x.name),ny=epNumOf(y.name);if(nx>=0&&ny>=0&&nx!==ny)return nx-ny;return x._i-y._i;});var out=[];for(var k=0;k<eps.length;k++)out.push({name:eps[k].name,url:eps[k].url,cur:eps[k].cur,id:eps[k].id||'',mid:eps[k].mid||'',path:eps[k].path||''});return "
        + "out;}function panelCurEp(eps){for(var i=0;i<eps.length;i++){if(eps[i].cur)return eps[i].name;}if(eps.length===1)return eps[0].name;return '';}function genericTabLines(){var res={lines:[],curEpName:'',tabsFound:0,epsFound:0,tabsPending:0,preloadTabs:[],preloadActive:-1};try{var "
        + "tabs=[];function addTab(el){if(!el)return;for(var q=0;q<tabs.length;q++){if(tabs[q]===el)return;}tabs.push(el);}var i,j;for(i=0;i<tabEls.length;i++)addTab(tabEls[i]);var rts=document.querySelectorAll('[role=\"tab\"],[data-toggle=\"tab\"]');for(i=0;i<rts.length;i++){if(!ownHidden(rts[i]))addTab(rts[i]);}res.tabsFound=tabs.length;try{window.__musePreloadTabs=tabs;window.__musePreloadClick=function(k){var "
        + "t=(window.__musePreloadTabs||[])[k];if(!t)return false;try{t.click();return true;}catch(e){return false;}};window.__musePreloadReady=function(k){var t=(window.__musePreloadTabs||[])[k];if(!t)return false;var "
        + "p=tabPanelOf(t);if(!p)return false;try{return panelEps(p).length>0;}catch(e){return false;}};}catch(e){}if(tabs.length<2)return res;var firstName='';var curLineEp='';for(i=0;i<tabs.length;i++){var tab=tabs[i];var "
        + "name=tabText(tab);if(!name||name.length>24||epName(name))continue;if(stopWord(name)&&!isSourceTab(name))continue;if(!firstName)firstName=name;var panel=tabPanelOf(tab);if(!panel)continue;if(isCurTab(tab)&&res.preloadActive<0)res.preloadActive=i;var "
        + "eps=panelEps(panel);if(!eps.length){try{if(!panel.querySelectorAll('button,a').length)res.preloadTabs.push(i);}catch(e){}continue;}var cep=panelCurEp(eps);for(j=0;j<eps.length;j++)res.epsFound++;res.lines.push({sid:'g'+i,name:name,cur:isCurTab(tab)?1:0,episodes:eps});if(isCurTab(tab)&&cep)curLineEp=cep;}if(res.lines.length){if(curLineEp)res.curEpName=curLineEp;else{for(i=0;i<res.lines.length;i++){var "
        + "ce=panelCurEp(res.lines[i].episodes);if(ce){res.curEpName=ce;break;}}}return res;}res.tabsPending=1;if(firstName){var cand=[];var seenU={};var tabParents={};for(i=0;i<tabs.length;i++){try{var p=tabs[i].parentElement;if(p)tabParents[p.tagName+'|'+(p.getAttribute('class')||'')+'|'+(p.getAttribute('id')||'')]=1;}catch(e){}}var "
        + "links=document.querySelectorAll('a[href]');for(i=0;i<links.length;i++){var a=links[i];var h=a.href||'';var m=h.match(/(\\d+)-(\\d+)-(\\d+)\\.html($|\\?)/);if(!m)continue;if(vid&&m[1]!==vid)continue;var "
        + "par=null;try{par=a.parentElement;}catch(e){}var pk='';try{pk=par?par.tagName+'|'+(par.getAttribute('class')||'')+'|'+(par.getAttribute('id')||''):'';}catch(e){}if(tabParents[pk])continue;if(hasAdMark(a)||inRecCtx(a))continue;try{if(a.closest&&(a.closest('header')||a.closest('nav')||a.closest('footer')))continue;}catch(e){}var "
        + "t2=cleanName(txt(a));if(!t2||t2.length>20||stopWord(t2))continue;if(seenU[h])continue;seenU[h]=1;cand.push({name:epName(t2)||t2,url:h});}if(cand.length===1){var activeName='';for(i=0;i<tabs.length;i++){var "
        + "nm2=tabText(tabs[i]);if(nm2&&!epName(nm2)&&!(stopWord(nm2)&&!isSourceTab(nm2))){if(isCurTab(tabs[i])){activeName=nm2;break;}if(!activeName)activeName=nm2;}}var ln=activeName||firstName;res.lines.push({sid:'g0',name:ln,cur:1,episodes:[{name:cand[0].name,url:cand[0].url,cur:1}]});res.epsFound=1;res.curEpName=cand[0].name;res.tabsPending=0;}}}catch(e){}return "
        + "res;}function articleBody(){try{var a=document.querySelector('.post-content[itemprop=\"articleBody\"]');if(a)return a;return document.querySelector('.post-content');}catch(e){return null;}}function inRemovedZone(el){try{var "
        + "n=el;while(n&&n!==document.body){if(n.matches&&n.matches('.post-near,.content-copyright,.hot-news-section,.tags,.content-tabs,.txt-apps,.horizontal-banner,.article-ads-btn,.btn-app,.line,blockquote,table'))return "
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
        + "out2;}catch(e){return [];}}var curEpName='';for(var ce=0;ce<out.length;ce++){var epsC=out[ce].episodes;for(var cw=0;cw<epsC.length;cw++){if(epsC[cw].cur){curEpName=epsC[cw].name;break;}}if(curEpName)break;}var "
        + "dp=null;try{dp=dplayerEpisodes();}catch(e){dp=null;}if(dp&&dp.line)out.push(dp.line);var subs=[];try{subs=subEpsList();}catch(e){subs=[];}var gen=null;try{gen=genericTabLines();}catch(e){gen=null;}if(gen&&gen.lines&&gen.lines.length&&out.length===0&&!dp){for(var "
        + "gi2=0;gi2<gen.lines.length;gi2++)out.push(gen.lines[gi2]);if(!curEpName&&gen.curEpName)curEpName=gen.curEpName;}else if(gen&&gen.lines&&gen.lines.length&&out.length>0){for(var gi3=0;gi3<gen.lines.length;gi3++){var "
        + "gl=gen.lines[gi3];if(!gl.episodes||!gl.episodes.length)continue;var mm=(gl.episodes[0].url||'').match(/-(\\d+)-(\\d+)\\.html/);if(!mm)continue;var gsid=mm[1];for(var oi=0;oi<out.length;oi++){if(out[oi].sid===gsid&&/^线路\\d+$/.test(out[oi].name||'')){out[oi].name=gl.name;}}}}var "
        + "diag='';if((sorted.length>1&&tabNames.length!==sorted.length)||tabRaw.length){var dc=[];for(var d1=0;d1<sorted.length;d1++)dc.push(sorted[d1].sid+':'+(sorted[d1].candRaw||'-'));diag='tabs='+tabNames.length+' "
        + "srcA='+document.querySelectorAll('[class*=\"source\"] a').length+' srcLi='+document.querySelectorAll('[class*=\"source\"] li').length+' cand='+dc.join('|');var dr=[];for(var d2=0;d2<sorted.length;d2++){var "
        + "Lr=sorted[d2];if(!Lr.candRaw&&Lr.raws.length)dr.push(Lr.sid+'='+Lr.raws.join('~'));}if(dr.length)diag+=(diag?' ':'')+'raw='+dr.join('|');if(tabRaw.length)diag+=(diag?' ':'')+'tabRaw='+tabRaw.join('|');if(tabSel>=0)diag+=(diag?' "
        + "':'')+'tsel='+tabSel;if(tabNames.length!==sorted.length){var hs=[];var cs=document.querySelectorAll('ul,ol,div');for(var hi=0;hi<cs.length&&hi<80;hi++){var c0=cs[hi];var ch=c0.children;if(!ch||ch.length<2||ch.length>8)continue;var "
        + "ts2=[];var bad=false;for(var hj=0;hj<ch.length;hj++){var tt=tabText(ch[hj]);if(!tt||tt.length>12||epName(tt)){bad=true;break;}ts2.push(tt);}if(bad||ts2.length<2)continue;hs.push(((c0.getAttribute('class')||c0.tagName)||'').slice(0,30)+':'+ts2.slice(0,3).join('~'));if(hs.length>=3)break;}if(hs.length)diag+=(diag?' "
        + "':'')+'hunt='+hs.join('|');}}function iframeVideo(){try{var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){try{var d=fs[i].contentDocument;if(!d)continue;var v=d.querySelector('video');if(!v)continue;var "
        + "s=v.currentSrc||v.src||'';if(s&&/\\.m3u8|\\.mp4/i.test(s))return s;}catch(e){}}return '';}catch(e){return '';}}function isStreamUrl(u){if(!u)return false;var l=(''+u).toLowerCase();var q=l.indexOf('?');var "
        + "p=q>0?l.substring(0,q):l;if(p.slice(-5)==='.m3u8'||p.slice(-4)==='.mp4')return true;var i=l.indexOf('.m3u8');while(i>=0){var a=i+5;if(a>=l.length||l.charAt(a)==='&'||l.charAt(a)==='#')return true;i=l.indexOf('.m3u8',a);}i=l.indexOf('.mp4');while(i>=0){var "
        + "a2=i+4;if(a2>=l.length||l.charAt(a2)==='&'||l.charAt(a2)==='#')return true;i=l.indexOf('.mp4',a2);}return false;}function absUrl(u){if(!u)return '';try{return new URL(u,location.href).href;}catch(e){return "
        + "u;}}function vidSrc(v){var s='';try{s=v.currentSrc||v.src||'';}catch(e){}if(s===location.href)s='';if(s&&/^https?:/i.test(s))return s;if(v.querySelector){try{var qs=v.querySelectorAll('source');for(var "
        + "i=0;i<qs.length;i++){var q=qs[i].currentSrc||qs[i].src||'';if(q&&q!==location.href&&/^https?:/i.test(q))return q;}}catch(e){}}return s||'';}function vidSrcAny(v){var s='';try{s=v.currentSrc||v.src||'';}catch(e){}if(s===location.href)s='';if(s)return "
        + "s;if(v.querySelector){try{var q=v.querySelector('source');if(q){var qs2=q.currentSrc||q.src||'';if(qs2&&qs2!==location.href)s=qs2;}}catch(e){}}return s||'';}function ctxStr(el){return ((el.getAttribute('class')||'')+' "
        + "'+(el.getAttribute('id')||''));}function hasAdMark(el){var n=el;while(n&&n!==document.body){if(/(^|[\\s_\\-])(ad|ads|advert|advertisement|banner|sponsor|sponsored|promotion)([\\s_\\-]|$)/i.test(ctxStr(n)))return "
        + "true;n=n.parentElement;}return false;}function inRecCtx(el){var n=el;while(n&&n!==document.body){if(/(^|[\\s_\\-])(recommend|recommended|related|thumbnail|thumbnails|preview|previews|more-videos|sidebar|also-watch)([\\s_\\-]|$)/i.test(ctxStr(n)))return "
        + "true;n=n.parentElement;}return false;}function isGifPrev(v){var c=v.getAttribute('class')||'';if(/gifVideo|lazyVideo/i.test(c))return true;if(v.hasAttribute&&v.hasAttribute('data-mp4'))return true;return "
        + "false;}function elHidden(el){if(!el)return true;if(el.hidden)return true;if(/(^|[\\s_\\-])(displayNone|display-none|hidden|hide|invisible|collapsed)([\\s_\\-]|$)/i.test(ctxStr(el)))return true;var s=el.getAttribute?el.getAttribute('style')||'':'';if(/display\\s*:\\s*none/i.test(s)||/visibility\\s*:\\s*hidden/i.test(s))return "
        + "true;try{var cs=getComputedStyle(el);if(cs&&(cs.display==='none'||cs.visibility==='hidden'))return true;}catch(e){}if(el.tagName==='VIDEO'){var w=el.getAttribute('width'),h=el.getAttribute('height');if((w!==null&&parseInt(w,10)===0)||(h!==null&&parseInt(h,10)===0))return "
        + "true;}return false;}function vidArea(v){try{var r=v.getBoundingClientRect();if(r&&r.width>0&&r.height>0)return r.width*r.height;}catch(e){}var w=parseInt(v.getAttribute('width')||'0',10),h=parseInt(v.getAttribute('height')||'0',10);if(w>0&&h>0)return "
        + "w*h;try{var a=v.videoWidth||0,b=v.videoHeight||0;if(a>0&&b>0)return a*b;}catch(e){}return 0;}function mainVideo(){var vs=document.querySelectorAll('video');var best=null,bs=-1;for(var i=0;i<vs.length;i++){var "
        + "v=vs[i];if(isGifPrev(v)||hasAdMark(v)||inRecCtx(v)||elHidden(v))continue;var s=vidArea(v);if(s>bs){bs=s;best=v;}}return best;}function mainVideoSrc(){var mv=mainVideo();if(!mv)return '';var s=vidSrc(mv);if(!s)return "
        + "'';if(/^blob:/i.test(s))return '';if(/^https?:/i.test(s))return s;return absUrl(s);}function playerScope(mv){if(!mv)return document;var sc=mv.parentElement||document;var n=mv.parentElement;for(var d=0;d<4&&n;d++){if(/(^|[\\s_\\-])player([\\s_\\-]|$)/i.test(ctxStr(n)))sc=n;n=n.parentElement;}return "
        + "sc||document;}function adRollUi(mv){var n=mv.parentElement;for(var d=0;d<6&&n&&n!==document.body;d++){var c=ctxStr(n);if(/(^|[\\s_\\-])(adrollrunning|adroll-running|preroll-playing|vast-playing|ad-showing|ima-ad-showing)([\\s_\\-]|$)/i.test(c))return true;var ui=n.querySelectorAll('[class],[id]');for(var i=0;i<ui.length&&i<400;i++){var s=ctxStr(ui[i]);if(/adroll(skip|timeelapsed|container|link)|(^|[\\s_\\-])(skip-?ad|ad-?skip|vast-?skip)([\\s_\\-]|$)/i.test(s)&&!elHidden(ui[i]))return true;}n=n.parentElement;}return false;}function adPlaying(){try{var mv=mainVideo();if(!mv)return false;if(adRollUi(mv))return true;var sc=playerScope(mv);var sk=sc.querySelectorAll('[class*=skip],[id*=skip]');for(var i=0;i<sk.length;i++){if(!elHidden(sk[i]))return "
        + "true;}var els=sc.querySelectorAll('button,a,div,span');for(var j=0;j<els.length;j++){var e=els[j];if(e.children&&e.children.length>0)continue;var t=txt(e);if(t&&t.length<40&&/^(skip|跳过)/i.test(t))return "
        + "true;}var ovs=sc.querySelectorAll('[class*=preroll],[class*=vast],[id*=preroll],[id*=vast]');for(var k=0;k<ovs.length;k++){if(!elHidden(ovs[k]))return true;}return false;}catch(e){return false;}}function "
        + "adSrcs(){var out=[],seen={};function add(u){if(!u)return;u=absUrl(u);if(!/^https?:/i.test(u))return;if(!isStreamUrl(u))return;if(seen[u])return;seen[u]=1;out.push(u);}try{var vs=document.querySelectorAll('video');var "
        + "ms0='';try{var _mv=mainVideo();if(_mv){var _s=vidSrc(_mv);if(_s)ms0=absUrl(_s);}}catch(e){}for(var i=0;i<vs.length;i++){var v=vs[i];var dm=v.getAttribute('data-mp4');if(dm)add(dm);var ds=v.getAttribute('data-src');if(ds){var "
        + "a1=absUrl(ds);if(a1!==ms0)add(a1);}var du=v.getAttribute('data-url');if(du){var a2=absUrl(du);if(a2!==ms0)add(a2);}if(isGifPrev(v)||hasAdMark(v)||inRecCtx(v)||(v.loop&&v.muted))add(vidSrcAny(v));}if(adPlaying()){var "
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
        + "found[0];return '';}catch(e){return '';}}function frameStream(){try{var AD=/(^|[\\s_\\-])(ad|ads|advert|advertisement|banner|sponsor|sponsored|promotion)([\\s_\\-]|$)/i;var PL=/(^|[\\s_\\-])(player|playbox|dplayer)([\\s_\\-]|$)/i;var RC=/(^|[\\s_\\-])(recommend|recommended|related|thumbnail|thumbnails|preview|previews|more-videos|sidebar|also-watch)([\\s_\\-]|$)/i;function cx(el){return ((el.getAttribute&&el.getAttribute('class'))||'')+' '+((el.getAttribute&&el.getAttribute('id'))||'');}function up(el,re){var d=el.ownerDocument,n=el;while(n&&n!==d.body&&n!==d.documentElement){if(re.test(cx(n)))return true;n=n.parentElement;}return false;}var fr=document.querySelectorAll('iframe');for(var k=0;k<fr.length;k++){var f=fr[k];if(up(f,AD)||!up(f,PL))continue;var d=null,w=null;try{d=f.contentDocument;w=f.contentWindow;}catch(e){}if(!d||!d.body)continue;var vs=d.querySelectorAll('video'),best=null,bs=-1;for(var i=0;i<vs.length;i++){var v=vs[i];if(up(v,AD)||up(v,RC)||(v.loop&&v.muted))continue;var r=v.getBoundingClientRect(),a=r.width*r.height;if(a>bs){bs=a;best=v;}}if(best){var s='';try{s=best.currentSrc||best.getAttribute('src')||'';}catch(e){}if(/^https?:/i.test(s)&&isStreamUrl(s))return s;}try{var es=w.performance.getEntriesByType('resource'),fl=[];for(var j=0;j<es.length;j++){var u=es[j].name||'';if(/\\.m3u8($|[?&#])/i.test(u)&&fl.indexOf(u)<0)fl.push(u);}if(fl.length===1)return fl[0];}catch(e){}}return '';}catch(e){return '';}}function mainStream(){try{var md0=mediaDefDefault();if(md0)return md0;var mv=mainVideo();if(mv&&!adPlaying()){var s=vidSrc(mv);if(s&&/^https?:/i.test(s)&&isStreamUrl(s))return s;}try{var c=window.player_aaaa||window.player_data||window.player_config||window.MacPlayer;if(c&&c.url&&isStreamUrl(c.url))return "
        + "absUrl(c.url);}catch(e){}var md=mediaDefDefault();if(md)return md;var pk=packedSource();if(pk)return pk;var pf=perfM3u8();if(pf)return pf;var fsr=frameStream();if(fsr)return fsr;return '';}catch(e){return '';}}function mediaDefAll(){var out=[];try{var ss=document.querySelectorAll('script');for(var i=0;i<ss.length;i++){var t=ss[i].textContent||'';var k=t.indexOf('mediaDefinitions');if(k<0)continue;var a=t.indexOf('[',k);if(a<0||a-k>40)continue;var dep=0,q='',e=-1;for(var p=a;p<t.length;p++){var ch=t.charAt(p);if(q){if(ch==='\\\\'){p++;continue;}if(ch===q)q='';continue;}if(ch==='\"'||ch===\"'\"){q=ch;continue;}if(ch==='[')dep++;else if(ch===']'){dep--;if(dep===0){e=p;break;}}}if(e<0)continue;var seg=t.substring(a,e+1);var objs=[],od=0,oq='',os=-1;for(var c=0;c<seg.length;c++){var cc=seg.charAt(c);if(oq){if(cc==='\\\\'){c++;continue;}if(cc===oq)oq='';continue;}if(cc==='\"'||cc===\"'\"){oq=cc;continue;}if(cc==='{'){if(od===0)os=c;od++;}else if(cc==='}'){od--;if(od===0&&os>=0){objs.push(seg.substring(os,c+1));os=-1;}}}for(var oi=0;oi<objs.length&&out.length<8;oi++){var o=objs[oi];var m=/\"videoUrl\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"/.exec(o);if(!m)continue;var u=m[1].replace(/\\\\\\//g,'/');if(!/^https?:/i.test(u))continue;if(/\"remote\"\\s*:\\s*true/.test(o))continue;var fm=/\"format\"\\s*:\\s*\"([^\"]*)\"/.exec(o);var isH=fm?fm[1].toLowerCase()==='hls':/\\.m3u8/i.test(u);if(!isH)continue;var hm=/\"height\"\\s*:\\s*\"?(\\d+)/.exec(o)||/\"quality\"\\s*:\\s*\"?(\\d+)/.exec(o);var dq=/\"defaultQuality\"\\s*:\\s*true/.test(o)?1:0;var dup=false;for(var j=0;j<out.length;j++)if(out[j].u===u)dup=true;if(!dup)out.push({u:u,h:hm?parseInt(hm[1],10):0,d:dq});}if(out.length)return out;}}catch(e){}return out;}function declMasterSrc(){try{var s=packedSource();return (s&&/\\.m3u8/i.test(s))?s:'';}catch(e){return '';}}function hasMainPlayer(){try{var "
        + "mv=mainVideo();if(mv){var s='';try{s=mv.currentSrc||'';}catch(e){}if(!s){try{s=mv.getAttribute('src')||'';}catch(e){}}if(s===location.href)s='';if(s)return true;try{if(mv.paused===false)return true;}catch(e){}}}catch(e){}try{if(document.querySelector('video-player,[data-playback-player]'))return "
        + "true;}catch(e){}try{if(mainVideoSrc())return true;}catch(e){}try{var c=window.player_aaaa;if(c)return true;}catch(e){}try{if(mediaDefDefault())return true;}catch(e){}try{if(packedSource())return true;}catch(e){}try{if(dp&&dp.line)return "
        + "true;}catch(e){}try{if(out&&out.length)return true;}catch(e){}try{if(subs&&subs.length)return true;}catch(e){}try{var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){var s='';try{s=fs[i].getAttribute('src')||fs[i].src||'';}catch(e2){}if(s&&(/\\/static\\/player\\//i.test(s)||/dplayer\\.html/i.test(s)||/artplayer\\.html/i.test(s)||/ckplayer\\.html/i.test(s)||/art\\.php/i.test(s)))return "
        + "true;}}catch(e){}return false;}function playerCfg(){try{var c=window.player_aaaa||window.player_data||window.player_config||window.MacPlayer;if(!c)return '';return JSON.stringify(c);}catch(e){return '';}}return "
        + "JSON.stringify({lines:out,diag:diag,pic:picOf(),player:playerCfg(),curEpName:(dp&&dp.curName)||curEpName,iframeVideo:iframeVideo(),origin:location.origin,vid:vid,sid:sid,ep:curEp,mainStream:(dp&&dp.curUrl)||mainStream(),mainVideo:mainVideoSrc(),adSrcs:adSrcs(),hlsDefs:mediaDefAll(),declMaster:declMasterSrc(),adPlaying:adPlaying()?1:0,subEps:subs,hasMainPlayer:hasMainPlayer()?1:0,tabsFound:(gen?gen.tabsFound:0),epsFound:(gen?gen.epsFound:0),tabsPending:(gen?gen.tabsPending:0),preloadTabs:(gen?gen.preloadTabs:[]),preloadActive:(gen?gen.preloadActive:-1),tabLines:((sorted.length===0&&!dp)?1:0)});})()";
    // push v16：gaze 懒加载面板预加载——点 tab / 判面板出集的两段小 JS（%d 是探针
    // __musePreloadTabs 里的下标；助手未就绪或序号越界时返回 '0'，调用方按超时处理）。
    private static final String PRELOAD_CLICK_JS = "(function(){try{return window.__musePreloadClick&&window.__musePreloadClick(%d)?'1':'0';}catch(e){return '0';}})()";
    private static final String PRELOAD_READY_JS = "(function(){try{return window.__musePreloadReady&&window.__musePreloadReady(%d)?'1':'0';}catch(e){return '0';}})()";
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
    // TV push v4 fix 3(a)：点推时现扫——同源 iframe 里的 video.currentSrc（dplayer/ArtPlayer
    // 在 iframe 里播；电视原生 HLS 时 .m3u8 不走 shouldInterceptRequest，嗅探抓不到）。
    // 只读一层 iframe，跨域取 contentDocument 会抛，由每层 try/catch 吞掉；只回流地址
    // （含 .m3u8/.mp4），Java 侧再过 isPushStreamUrl 精确门。探针跑时播放器可能还没挂载，
    // 所以不在 PROBE_JS 里只读一次，而是在点推那一刻现扫。
    private static final String IFRAME_VIDEO_PROBE =
            "(function(){try{var fs=document.querySelectorAll('iframe');"
                    + "for(var i=0;i<fs.length;i++){try{var d=fs[i].contentDocument;if(!d)continue;"
                    + "var v=d.querySelector('video');if(!v)continue;"
                    + "var s=v.currentSrc||v.src||'';if(s&&/\\.m3u8|\\.mp4/i.test(s))return s;}catch(e){}}"
                    + "return ''}catch(e){return ''}})()";
    // push v1: 浏览器转原生播放状态（嗅探到的直链 + 多线探针结果 + 当前页信息）。
    private volatile String mPushLastSniff;
    private volatile long mPushSniffAt;
    // push v10：嗅流所属页（嗅到流时记 mPushPageUrl；切页时清，与流同生死，免得旧页流串到新页）。
    private volatile String mPushSniffPageUrl = "";
    // push v8：探针带回的主片流（声明地址）+ 广告流黑名单 + 片头广告播放中（PROBE_JS 新字段；切页时清）。
    private volatile String mPushMainStream = "";
    /** push v43/v25：探针带回的站方各档 hls（JSON 数组 [{u,h,d}]）与页面声明的主清单——推送多画质升级用（PushMultiRes）。 */
    private volatile String mPushHlsDefs = "";
    private volatile String mPushDeclMaster = "";
    private volatile java.util.Set<String> mPushAdSrcs = java.util.Collections.emptySet();
    private volatile boolean mPushAdPlaying = false;
    // push v11 B2：探针判定本页有主播放器（主视频直链 / player_aaaa / packed source / .dplayer / 有线路表 / 有子帖表 / 播放器型 iframe 任一成立）；切页时清，不许留旧页的 true。
    private volatile boolean mPushHasMainPlayer = false;
    private volatile Map<String, String> mPushSniffedHeaders;
    private volatile String mPushProbeLines = "";
    // push v9：子页解析进行中守卫（同页重复点推只解析一次；切页时清）。
    private volatile boolean mPushSubResolving = false;
    private volatile String mPushSubResolveUrl = "";
    // TV push v4 fix 1：探针 player.url（player_aaaa 直链等），探针无 player 时清空；取流时过 isPushStreamUrl 门。
    private volatile String mPushProbePlayerUrl = "";
    // TV push v4 fix 3(b)：探针在同源 iframe 里见到的视频流 + 时间戳（120 秒窗口，与 hasFreshPushSniff 同口径）。
    private volatile String mPushIframeVideo = "";
    private volatile long mPushIframeVideoAt;
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
    // push v3：探针补跑状态（选集表可能是 JS 后渲染的，页完那一刻还没进 DOM）。
    private Runnable mPushProbeRetry;
    private int mPushProbeRetryCount;
    private String mPushProbeRetryPageUrl = "";
    private ActivityWebPlayBinding mBinding;
    private WebResourceResponse mEmpty;
    // 1080 视口：本页是否已注过视口宽度（onPageStarted 重置，onPageCommitVisible 只注一次）。
    private boolean mViewportInjected;
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
    /** push v49/v33（123av 弹窗修法 1）：已按结构核实是「跳转页」的 host（站内深链少、不是真实站）。v61 带手势放行只在
     * mJumpHost == 当前 host 且已核实时才生效——入口按 host 兜底默认出来的 mJumpHost 不再凭空享有放行；核实为真实站则清 mJumpHost。 */
    private String mJumpPageHost = null;
    /** push v49/v33：本页「拦外站跳转」日志计数（每页上限 20 行，新页清零）。 */
    private int mAdJumpLogN = 0;
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
        // v75：不传 jumpHost 的入口也按入口 URL 的 host 算——主页浏览器入口（HomePageFragment.openBrowser）
        // 走这条重载，之前传 "" 导致 mJumpHost 为空，v61/v74 永远不生效，发布站/中间页跳转全被拦。
        String host = "";
        try {
            if (!TextUtils.isEmpty(url)) host = Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        start(activity, title, url, header, names, ids, position, key, flag, click, flags, host == null ? "" : host);
    }

    /**
     * 纯网页源进站主页（Liu 2026-11-23 方案 A）：主页入口专用——与上面 11 参 start 同路（jumpHost 按入口 host），
     * 只多带 homeEntry，顶栏才显示「切源」。蜘蛛源 VideoActivity 的网页播放入口不走这里，不显示切源。
     */
    public static void startHome(Activity activity, String title, String url, String key, String click) {
        String host = "";
        try {
            if (!TextUtils.isEmpty(url)) host = Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        start(activity, title, url, null, null, null, 0, key, "", click, null, host == null ? "" : host, true);
    }

    /** v61：带跳转页 host 的重载——主页是发布站/跳转页时，把其 host 传进来放行该页上的人工点按跳转。 */
    public static void start(Activity activity, String title, String url, Map<String, String> header, ArrayList<String> names, ArrayList<String> ids, int position, String key, String flag, String click, ArrayList<String> flags, String jumpHost) {
        start(activity, title, url, header, names, ids, position, key, flag, click, flags, jumpHost, false);
    }

    private static void start(Activity activity, String title, String url, Map<String, String> header, ArrayList<String> names, ArrayList<String> ids, int position, String key, String flag, String click, ArrayList<String> flags, String jumpHost, boolean homeEntry) {
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
        intent.putExtra("homeEntry", homeEntry);
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
        // 电视版：强制横屏（manifest 里也锁了 landscape，双保险）。
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
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
        mJumpPageHost = null;
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
        // 纯网页源进站主页：主页入口打开的浏览器顶栏显示「切源」（回首页并打开源列表）；其他入口不显示。
        mBinding.switchSite.setVisibility(intent.getBooleanExtra("homeEntry", false) ? View.VISIBLE : View.GONE);
        // 电视版：进页先把焦点落在顶栏返回键上，遥控器不用先找焦点；WebView 不抢焦点（布局里 focusable=false）。
        mBinding.back.requestFocus();
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
        // push v14 (c)：登记现存浏览器，供播放器点集时请求后台点按钮；onDestroy 注销。
        sLiveBrowser = new WeakReference<>(this);
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
        // 纯网页源进站主页：顶栏「切源」chip（遥控器可聚焦）= 关浏览器回首页并打开切源对话框（Cookie 落盘照走 onPause）。
        mBinding.switchSite.setOnClickListener(view -> {
            HomeActivity.requestSiteDialog();
            finish();
        });
        // TV push v5：顶栏「推送」钮（由右下角悬浮移入顶栏）——布局里 focusable，遥控器 D-pad 可聚焦操作；MacCMS 播放页探到可用线路表或 120 秒内嗅到过流时才亮起，点后转原生播放器。
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
        // 电视卡顿优化（2026-11-16）：WebView 用硬件层，合成走 GPU，减轻 32 位电视 CPU 负担。
        mBinding.web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
                // 广告加载器（混淆 js 拉的远程广告脚本，域名按日期轮换）：按子串拦，通用、不只麦田。
                // push v39（P站贴片）：MGP 播放器的贴片配置走同站 /_xa/ads?…preroll_type=json，按「路径 + 贴片专用参数」拦，
                // 播放器拿不到配置就走自带失败分支直接起播正片；广告片本身（evtubescms.phncdn.com/pre_videos/）作第二道，
                // 只拦这一主机 + 路径（正片在 em-h.phncdn.com，不整拦 phncdn）。不看时长。
                if (isPrerollAdRequest(request.getUrl())) {
                    if (mBlockedHosts.add("preroll:" + host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked preroll ad url=" + request.getUrl(), false);
                    return mEmpty;
                }
                if (isAdLoaderUrl(request.getUrl().toString())) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked ad-loader url=" + request.getUrl(), false);
                    return mEmpty;
                }
                // 奇优（只在奇优站内）：gdw.js 是奇优的广告加载器（写 #noiddiv + WebSocket 远程代码注底部横幅），
                // xs2.js 是另一路广告注入器（往列表插「小说」推荐卡）。只拦文件名子串，不带 query 串；
                // 不进全局黑名单，只在奇优站内拦，避免误伤别的站。
                if (isQiyouSite() && isQiyouAdScript(reqUrl)) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked qiyou ad script url=" + request.getUrl(), false);
                    return mEmpty;
                }
                // v53 奇优：/static/jquery.js 是底部横幅的 WebSocket 通道（解析阶段同步开 WS，早于 CLEAN_JS 注入；
                // WS 握手不走 shouldInterceptRequest，只能拦加载器本身）。完整路径匹配：只拦 path 恰好是
                // /static/jquery.js 的请求；页面真 jQuery 走 CDN（path 不是这条），不误伤。
                // 根上拦（2026-11-16）：不 gate isQiyouSite()——新奇优域名（如 wdbay.com）key/title 里没有 qiyou/奇优，
                // isQiyouSite() 认不出，门一关加载器就漏过去、WS 就开了、条幅就出来了；WS 域名轮换拦不住，按 path 拦才管用。
                if ("/static/jquery.js".equals(request.getUrl().getPath())) {
                    if (mBlockedHosts.add(host)) DebugLogStore.add(LogCategory.AD, "WebPlay", "blocked qiyou static jquery url=" + request.getUrl(), false);
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
                // push v8：探针黑名单里的广告流不记为候选（只不记、不拦截；浏览器里广告照常放）。
                if (isPushStreamUrl(reqUrl) && !mPushAdSrcs.contains(reqUrl) && !reqUrl.equals(mPushLastSniff)) {
                    mPushLastSniff = reqUrl;
                    mPushSniffAt = System.currentTimeMillis();
                    mPushSniffPageUrl = mPushPageUrl;
                    capturePushSniffHeaders(request, reqUrl);
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
                    // v61：发布站/跳转页上的人工点按放行——主页本身就是发布站/防丢url 或 [工具:重定向] 跳转页时，
                    // 用户在该页上点按进真实站是正常操作（有些发布站不自动跳，必须人工点）。只放行「当前页就是跳转页」
                    // 且带手势的点按；目标是已知广告域名时照样拦。一旦落到真实站，currentHost 变了，这里不再生效，
                    // 防广告劫持的规矩照旧。
                    if (request.hasGesture() && !TextUtils.isEmpty(mJumpHost) && jumpPageVerified(currentHost)
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
                    // push v49/v33：带手势的外站主框跳转被拦，记广告拦截日志。
                    if (request.hasGesture()) logAdJump("主框", request.getUrl().toString());
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
                mAdJumpLogN = 0;
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
                mViewportInjected = false;
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
                // push v16：切页即清预加载单次标记（新页重探后可再预加载）并撤掉未跑完的预加载轮询。
                mPushPreloadAttempted = false;
                if (mPushPreloadPoll != null) {
                    if (mBinding != null && mBinding.web != null) mBinding.web.removeCallbacks(mPushPreloadPoll);
                    mPushPreloadPoll = null;
                }
                // TV push v4：player.url 与 iframe 视频流也是本页的，切页同清，免得旧页流串到新页。
                mPushProbePlayerUrl = "";
                mPushIframeVideo = "";
                mPushMainStream = "";
                mPushHlsDefs = "";
                mPushDeclMaster = "";
                mPushAdSrcs = java.util.Collections.emptySet();
                mPushAdPlaying = false;
                mPushHasMainPlayer = false;
                mPushSubResolving = false;
                mPushSubResolveUrl = "";
                // push v10：嗅流与嗅流头是旧页的，切页同清——否则 120 秒内点推会拿旧页流顶上（占位）。
                mPushLastSniff = null;
                mPushSniffAt = 0;
                mPushSniffPageUrl = "";
                mPushSniffedHeaders = null;
                cancelPushProbeRetry();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (url == null || url.equals("about:blank")) return;
                // 1080 视口：页面提交时定一次视口宽度（设置值）；onPageFinished 会因子 frame 多次触发，反复注会多次重排、肉眼可见多次缩放。
                if (!mViewportInjected) {
                    mViewportInjected = true;
                    view.evaluateJavascript(viewportJs(), null);
                }
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
                // push v25 (B)（电视 v19 适配）：兜底导航的目标页加载完成——之后才允许在无主片时交嗅流。
                if (mBrowserClickPageResolve && mBrowserClickCallback != null && url.equals(mBrowserClickPage)) mBrowserClickPageDone = true;
                evaluate(getScript(url), 0);
                // v97：不再立即显示——等 CLEAN_JS 回调再显示，否则分类页加载快会抢在首扫前闪出横幅墙（同 onPageCommitVisible）。
                evalCleanJs(view);
                // v77：直接进站也算“跳转完成”——入口页加载完，若是真实站（含 ≥2 站内正片链接），清 mJumpHost 激活 v76 规则；发布站页无站内正片链接，不触发。
                // push v49/v33：不再只认苹果 CMS 路径——按结构核实入口页（站内深链多 = 真实站 → 清 mJumpHost；少 = 跳转页 → 才享有 v61 放行）。
                checkJumpPage(view, url, false);
                // v56：把 Cookie 落盘——cf_clearance 只留在内存里时进程一死验证就丢；flush 后重进、刷新仍有效。
                CookieManager.getInstance().flush();
                // v100：空白页回滚——被拦的广告跳转有时会把当前页卸成空文档（标题空、无视频、无图），
                // 点什么都没反应。检出空白就回滚到入口地址一次（mAnchorUrl），避免死在空白页。
                view.evaluateJavascript("(function(){try{return ((document.title||'').trim()===''&&(document.body?document.body.innerText.trim():'')===''&&document.images.length===0)?'1':'0';}catch(e){return '0';}})()", blankVal -> {
                    if ("1".equals(blankVal) && !mBlankRecovered && !TextUtils.isEmpty(mAnchorUrl) && !mAnchorUrl.equals(url)) {
                        mBlankRecovered = true;
                        DebugLogStore.add("WebPlay", "blank page, reload anchor url=" + mAnchorUrl);
                        view.loadUrl(mAnchorUrl);
                    }
                });
                // push v1：页完探一次多线表（MacCMS 播放页才够格亮起播放按钮）。
                mPushPageUrl = url;
                probePageInfo();
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
                        } else {
                            // push v49/v33：拦下的外站新窗口（window.open / target=_blank）记广告拦截日志。
                            logAdJump("新窗口", url + " 手势=" + isUserGesture);
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

    /** 当前站是不是奇优：站 key 含 qiyou 或站名含「奇优」。 */
    private boolean isQiyouSite() {
        if (!TextUtils.isEmpty(mKey) && mKey.toLowerCase(Locale.US).contains("qiyou")) return true;
        if (!TextUtils.isEmpty(mTitle) && mTitle.contains("奇优")) return true;
        return false;
    }

    /** 奇优广告脚本：gdw.js（广告加载器）/ xs2.js（小说推荐卡注入器），只拦文件名，不带 query 串。 */
    private boolean isQiyouAdScript(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("gdw.js") || u.contains("xs2.js");
    }

    /** push v39：P站系（MGP 播放器）贴片广告请求——同站 /_xa/ads 带 preroll_type= 的贴片配置，或 evtubescms.phncdn.com/pre_videos/ 广告片。 */
    private static boolean isPrerollAdRequest(Uri u) {
        if (u == null) return false;
        String path = u.getPath();
        if (path == null) return false;
        String p = path.toLowerCase(Locale.US);
        if (p.equals("/_xa/ads")) {
            String q = u.getEncodedQuery();
            return q != null && q.toLowerCase(Locale.US).contains("preroll_type=");
        }
        String h = u.getHost();
        return h != null && h.toLowerCase(Locale.US).equals("evtubescms.phncdn.com") && p.startsWith("/pre_videos/");
    }

    /** 广告加载器（混淆 js 拉的远程广告脚本，域名按日期轮换）：按子串拦，通用、不只麦田。 */
    private boolean isAdLoaderUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase(Locale.US);
        if (u.contains("douytmvbf")) return true; // v3 PLMM 底飘：d.douytmvbf.site 是底条广告脚本加载器(见 jump1_reinvest/report.md §2.2)；域名固定、后缀随机，按子串拦。
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
            // push v40 S1：页内无地址广告框（同源 iframe 装外站广告）整框移除——记一行广告拦截日志，不动点按记录。
            if ("adslot".equals(kind)) {
                DebugLogStore.add(LogCategory.AD, "WebPlay", "push v40 页内广告框整框移除 依据=" + attrs + " 指向=" + href + " 页=" + docUrl, false);
                return;
            }
            // push v41 S2：站方自家 SSP 广告位 / 弹框广告整块拿掉——记一行广告拦截日志，不动点按记录。
            if ("sspslot".equals(kind)) {
                DebugLogStore.add(LogCategory.AD, "WebPlay", "push v41 站方广告位整块藏 类型=" + attrs + " 位=" + href + " 页=" + docUrl, false);
                return;
            }
            // push v49/v33：全屏透明点击陷阱移除——记一行广告拦截日志，不动点按记录。
            if ("trap".equals(kind)) {
                DebugLogStore.add(LogCategory.AD, "WebPlay", "push v49 透明点击陷阱移除 依据=" + attrs + " 指向=" + href + " 页=" + docUrl, false);
                return;
            }
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

    /** v73:新窗口请求(target="_blank" / window.open)放行判断,与 shouldOverrideUrlLoading 同一套规则。
     * 只放行合法跳转:发布页人工点按到非广告域名、同站、中转站;其余一律拦(自动跨域跳转、/pop、广告域名)。
     * 注意:自动跨域跳转(无手势、非中转、无线链)在这里也是拦——不要把它改成放行。 */
    /** push v49/v33：跳转页结构判定脚本——数站内链接（同主域、去重的非根路径）与外站链接；苹果 CMS 正片路径 ≥2，
     * 或站内深链 ≥8 且站内链接不少于外站链接 → 真实站（real）；否则跳转页（jump）。不看尺寸、不看时长。 */
    private static final String JUMP_PAGE_JS = "(function(){try{var host=location.hostname||'';function reg(h){var p=String(h||'').toLowerCase().split('.');return p.length>=2?p.slice(-2).join('.'):String(h||'').toLowerCase();}var hr=reg(host);var as=document.querySelectorAll('a[href]');var same=0,foreign=0,cms=0,np=0,seen={};for(var i=0;i<as.length;i++){var h=(as[i].getAttribute('href')||'').trim();if(!h||h.charAt(0)==='#'||/^(javascript|mailto|tel):/i.test(h))continue;var u;try{u=new URL(h,location.href);}catch(e){continue;}if(u.protocol!=='http:'&&u.protocol!=='https:')continue;if(reg(u.hostname)!==hr){foreign++;continue;}same++;var p=u.pathname||'/';if(p.indexOf('/vodtype/')===0||p.indexOf('/voddetail/')===0||p.indexOf('/vodplay/')===0)cms++;if(p!=='/'&&!seen[p]){seen[p]=1;np++;}}var real=cms>=2||(np>=8&&same>=foreign);return (real?'real':'jump')+':'+same+'/'+foreign+'/'+np+'/'+cms;}catch(e){return 'err';}})()";

    /** push v49/v33：v61 带手势放行的前提——当前页 host 已核实是跳转页。 */
    private boolean jumpPageVerified(String currentHost) {
        return mJumpPageHost != null && !TextUtils.isEmpty(currentHost) && mJumpPageHost.equalsIgnoreCase(currentHost);
    }

    /** push v49/v33：页面加载完核实入口页是不是跳转页（取代 v77 只认苹果 CMS 路径的判定）：真实站 → 清 mJumpHost（v61/v74 不再放行）；
     * 跳转页 → 记 mJumpPageHost，3 秒后再核一次（SPA 页面渲染后链接才出来）。 */
    private void checkJumpPage(WebView view, String url, boolean recheck) {
        if (view == null || TextUtils.isEmpty(url) || TextUtils.isEmpty(mJumpHost)) return;
        String curHost = null;
        try {
            curHost = Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(curHost) || !curHost.equalsIgnoreCase(mJumpHost)) return;
        final String host = curHost;
        view.evaluateJavascript(JUMP_PAGE_JS, value -> {
            String v = value == null ? "" : value.replace("\"", "");
            if (TextUtils.isEmpty(mJumpHost) || !host.equalsIgnoreCase(mJumpHost)) return;
            if (!clickNoHash(url).equals(clickNoHash(view.getUrl()))) return;
            if (v.startsWith("real")) {
                mJumpHost = "";
                mJumpUsed = false;
                mJumpOriginHost = null;
                mJumpPageHost = null;
                DebugLogStore.add(LogCategory.AD, "WebPlay", "push v49 入口页判为真实站，关闭跳转页放行 统计=" + v + " 页=" + url, false);
            } else if (v.startsWith("jump")) {
                mJumpPageHost = host;
                if (!recheck) view.postDelayed(() -> checkJumpPage(view, url, true), 3000);
            }
        });
    }

    /** push v49/v33：拦下外站跳转记一行「广告拦截」日志（每页上限 20 行）。 */
    private void logAdJump(String where, String target) {
        if (mAdJumpLogN >= 20) return;
        mAdJumpLogN++;
        DebugLogStore.add(LogCategory.AD, "WebPlay", "push v49 拦外站跳转(" + where + ") 去=" + target, false);
    }

    /** push v49/v33（123av Referer 修法 A）：把候选请求头登记给 PushMultiRes——①原样嗅到的头 ②Referer=当前页 ③不带 Referer，
     * 相同的去重。交给播放器的初始头（validatedPushHeaders 的判定）不动；PushMultiRes 取清单时按序试，选中的那套再换给播放器。 */
    private void offerPushHeaderSets(String stream) {
        if (TextUtils.isEmpty(stream)) return;
        try {
            List<Map<String, String>> sets = new ArrayList<>();
            Map<String, String> raw = mPushSniffedHeaders;
            Map<String, String> base = new LinkedHashMap<>();
            if (raw != null) base.putAll(raw);
            if (raw != null && !raw.isEmpty()) sets.add(new LinkedHashMap<>(raw));
            Map<String, String> page = new LinkedHashMap<>(base);
            pushHdrRemove(page, "Referer");
            if (!mPushPageUrl.isEmpty()) page.put("Referer", mPushPageUrl);
            if (TextUtils.isEmpty(pushHdrGet(page, "User-Agent"))) {
                String ua = Setting.getUa();
                if (!TextUtils.isEmpty(ua)) page.put("User-Agent", ua);
            }
            pushHdrAddUnique(sets, page);
            Map<String, String> none = new LinkedHashMap<>(page);
            pushHdrRemove(none, "Referer");
            pushHdrAddUnique(sets, none);
            PushMultiRes.offerHeaders(stream, sets);
        } catch (Throwable ignored) {
        }
    }

    private static String pushHdrGet(Map<String, String> h, String name) {
        for (Map.Entry<String, String> e : h.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private static void pushHdrRemove(Map<String, String> h, String name) {
        java.util.Iterator<Map.Entry<String, String>> it = h.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) it.remove();
        }
    }

    private static void pushHdrAddUnique(List<Map<String, String>> sets, Map<String, String> h) {
        for (Map<String, String> s : sets) if (s.equals(h)) return;
        sets.add(h);
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
        if (hasGesture && !TextUtils.isEmpty(mJumpHost) && jumpPageVerified(currentHost)
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
        EpisodeDialog.create().episodes(episodes).position(mPosition).show(this);
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

    /** push v1：多线探针——探当前播放页全部线路与选集表，存 mPushProbeLines；够格才亮播放按钮。后台线程进来时先 post 到主线程（evaluateJavascript 必须在主线程调）。 */
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


    private void probePageInfo() {
        if (mBinding.web == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mBinding.web.post(this::probePageInfo);
            return;
        }
        // push v2：记下本次探针跑在哪一页——回调时页面可能已切走，回调里再记就锚错页。
        mPushProbePageUrl = mPushPageUrl;
        // push v33 M1：顺带读一次本页简介/年份/地区/og:image（只读，按页锚定）。
        collectPushPageMeta();
        mBinding.web.evaluateJavascript(PROBE_JS, value -> {
            // push v10：迟到回调（起飞后已切页）直接丢弃——旧页探针结果不许写回新页；pending 属于旧页，一并清掉。
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
                JSONArray lines = obj.optJSONArray("lines");
                String pic = obj.optString("pic");
                storePushStreamFields(obj);
                if (!pic.isEmpty()) mPushPagePic = pic;
                // TV push v4 fix 1：探针已把 player_aaaa/player_data 整个 JSON 带回来（playerCfg），
                // Java 侧此前从没读过 player 字段。取 url 存下来（相对地址按 origin 补全），
                // 探针无 player 时清空；取流时过 isPushStreamUrl 门（NB 的令牌不是 URL，必被门拦住）。
                String playerJson = obj.optString("player");
                if (playerJson.isEmpty()) {
                    mPushProbePlayerUrl = "";
                } else {
                    try {
                        String pu = new JSONObject(playerJson).optString("url");
                        if (!pu.isEmpty() && !pu.startsWith("http") && pu.startsWith("/")) {
                            try {
                                pu = new java.net.URL(new java.net.URL(obj.optString("origin")), pu).toString();
                            } catch (Throwable ignored) {
                                pu = "";
                            }
                        }
                        mPushProbePlayerUrl = pu;
                    } catch (Throwable ignored) {
                        mPushProbePlayerUrl = "";
                    }
                }
                // TV push v4 fix 3(b)：探针在同源 iframe 里见到的视频流（hsck/dplayer 类）。
                // 只在探针给了过门地址时刷新时间戳；探针没见到时不清旧值（新鲜度只由 120 秒窗口管，
                // 与 mPushLastSniff 同口径）；切页时清（见 onPageStarted）。
                String iv = obj.optString("iframeVideo");
                if (!iv.isEmpty() && isPushStreamUrl(iv)) {
                    mPushIframeVideo = iv;
                    mPushIframeVideoAt = System.currentTimeMillis();
                }
                JSONArray subEps = obj.optJSONArray("subEps");
                boolean hasSub = subEps != null && subEps.length() > 0;
                if (lines != null && lines.length() > 0) {
                    boolean renamed = dedupePushLineNames(lines);
                    mPushProbeLines = renamed ? obj.toString() : json;
                    DebugLogStore.add(LogCategory.PLAY, "WebPlay", "push 多线探针 线路=" + lines.length(), false);
                    String diag = obj.optString("diag");
                    if (!diag.isEmpty()) DebugLogStore.add(LogCategory.PLAY, "WebPlay", "push 探针诊断 " + diag, false);
                } else if (hasSub) {
                    // push v9：无分集表但有子帖表时也存下来，供点推时走子页解析层。
                    mPushProbeLines = json;
                    DebugLogStore.add(LogCategory.PLAY, "WebPlay", "push 子页探针 子帖=" + subEps.length(), false);
                } else if (obj.optInt("tabsPending", 0) == 1) {
                    // push v12：tab 已现形、选集尚未渲染（如 gaze /play_source_group 待回）时也存下来，
                    // 补跑据 tabsPending 续跑 5 秒档；无表无流，亮钮/放行门都不认它。
                    mPushProbeLines = json;
                } else if (mPushPlayPending) {
                    // 重探回来是空表：旧表是上一页的，不能留给续跑用。
                    mPushProbeLines = "";
                }
                updatePushButton();
            } catch (Throwable ignored) {
            }
            if (mPushPlayPending) {
                mPushPlayPending = false;
                if (hasSubEps() && !probeLinesUsable()) resolveSubPagesAndPlay();
                else continuePushPlay();
            }
        });
    }

    // push v3：探针补跑——选集表可能是 JS 后渲染的，页完那一刻还没进 DOM，探针跑空表按钮就不亮。
    // 页完后 0.5/1.5/3 秒各补跑一次，只在还没探到可用线路时跑，探到就停，最多 3 次；页切走或 Activity 销毁时不跑。
    // push v13 F3：停条件只看表（probeLinesUsable() || hasSubEps()），嗅流状态不再压制补跑。
    // TV push v4 fix 3(b)：hsck 类播放器 iframe 在倒计时+点按后才挂载（页完 6 秒后），快补跑
    // （0.5/1.5/3 秒）跑完时 iframe 还不存在。按钮未亮且是 MacCMS 播放页时，每 5 秒再补跑，
    // 最多到第 14 次（约 60 秒），亮灯/切页/销毁即停——有界，绝不无界跑。
    private void schedulePushProbeRetry() {
        cancelPushProbeRetry();
        // push v13 F3: retry stops on the table only (probeLinesUsable() || hasSubEps());
        // a sniffed stream only says the player is playing, not that the table is in the
        // DOM -- sniff state no longer suppresses the table retry.
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
            } else if (mPushProbeRetryCount < 14 && (isMacCmsPlayPage(mPushProbeRetryPageUrl) || hasProbeTabsPending())) {
                mBinding.web.postDelayed(mPushProbeRetry, 5000);
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

    /** push v12：探针是否带回了线路/选集表（不要求够 multi 格；枫叶 1 线 1 集也算有表）。 */
    private boolean hasProbeTable() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return false;
            JSONArray lines = new JSONObject(mPushProbeLines).optJSONArray("lines");
            return lines != null && lines.length() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** push v12：探针是否见到了线路 tab、但选集尚未渲染（gaze /play_source_group 待回）——补跑续跑信号。 */
    private boolean hasProbeTabsPending() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return false;
            return new JSONObject(mPushProbeLines).optInt("tabsPending", 0) == 1;
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

    /** push v9：探针带回的子帖表（subEps）是否可用（无 .dplayer 的文章页才有）。 */
    private boolean hasSubEps() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return false;
            JSONArray sub = new JSONObject(mPushProbeLines).optJSONArray("subEps");
            return sub != null && sub.length() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** push v9：探针带回的主片流（多视频单集 / 单片声明地址）是否可用。 */
    private boolean hasPushMainStream() {
        return !TextUtils.isEmpty(mPushMainStream);
    }

    /** push v9：探针带回的子帖表 JSON（供子页解析层用）。 */
    private String probeSubEpsJson() {
        try {
            if (TextUtils.isEmpty(mPushProbeLines)) return "";
            JSONArray sub = new JSONObject(mPushProbeLines).optJSONArray("subEps");
            return sub == null ? "" : sub.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** push v9：子页解析后推——探针只有 subEps（无 lines/mainStream）时，并行取子页拼完整分集，
     * 再按正常分集流程推（首集直达流 + 全部集进原生播放器）。子页全取不到就留在网页，不假装成功。
     * 同页重复点推只解析一次（电视遥控连按守卫，手机无此守卫）。 */
    private void resolveSubPagesAndPlay() {
        if (mPushSubResolving && mPushPageUrl.equals(mPushSubResolveUrl)) {
            Notify.show("正在解析子页视频…");
            return;
        }
        String subJson = probeSubEpsJson();
        if (subJson.isEmpty()) {
            Notify.show("当前页面未识别到线路/集数");
            return;
        }
        mPushSubResolving = true;
        mPushSubResolveUrl = mPushPageUrl;
        final String pageUrl = mPushPageUrl;
        Notify.show("正在解析子页视频…");
        HttpEpisodeResolver.resolveSubPages(pageUrl, subJson, new HttpEpisodeResolver.SubPagesCallback() {
            @Override
            public void onResolved(String episodesJson) {
                mPushSubResolving = false;
                if (!pageUrl.equals(mPushPageUrl)) return;
                if (mBinding == null || mBinding.web == null) return;
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
                mPushSubResolving = false;
                if (!pageUrl.equals(mPushPageUrl)) return;
                Notify.show("未检测到可提取的视频地址");
            }
        });
    }

    /** push v8：120 秒内嗅到过流（单片模式的判定口径，与 continuePushPlay 取流窗口一致）。
     * push v10：嗅流只在嗅到它的那一页有效——跨页不认，只看 120 秒会拿旧页流顶上。 */
    private boolean hasFreshPushSniff() {
        return !TextUtils.isEmpty(mPushLastSniff) && System.currentTimeMillis() - mPushSniffAt < 120000
                && mPushSniffPageUrl.equals(mPushPageUrl);
    }

    /** push v11 B2：探针判定本页有主播放器（见 mPushHasMainPlayer 注释）。嗅流点亮按钮/放行续跑须同时满足此门——
     * 列表页只有卡片预告/广告窗的嗅流、没有主播放器，钮不再亮。 */
    private boolean hasMainPlayer() {
        return mPushHasMainPlayer;
    }

    /** TV push v4 fix 3(b)：120 秒内探针在同源 iframe 里见过视频流（电视原生 HLS 下嗅探抓不到时，按钮亮灯的第三条路）。 */
    private boolean hasFreshIframeVideo() {
        return !TextUtils.isEmpty(mPushIframeVideo) && System.currentTimeMillis() - mPushIframeVideoAt < 120000;
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

    /** push v1：MacCMS 播放页且探到可用线路表时亮起顶栏「推送」钮，否则隐藏。 */
    private void updatePushButton() {
        if (mBinding.pushPlay == null) return;
        // push v8：按钮亮的条件放宽——(a) MacCMS 播放页且探针有多线表（原行为），或
        // (b) 120 秒内嗅到过流（单片模式：hsck/MissAV 类单片，无线路表也推当前一部）。
        // URL 门（isMacCmsPlayPage）不拦 (b)——MissAV 播放页 /cn/mxgs-1453 无 .html、无 3 段数字。
        // TV push v4 fix 3(b)：(c) 120 秒内探针在同源 iframe 里见过视频流（hsck 类：无选集表、
        // 电视原生 HLS 又嗅不到流，按钮否则永不亮）。
        // push v9：(c) 探针主片流可用（多视频单集/单片声明地址）或 (d) 有子帖表可解析时也亮起——
        // 51吃瓜类文章页不是 MacCMS 播放页、也不会嗅到流，旧条件永远不亮。
        boolean show = (isMacCmsPlayPage(mPushPageUrl) && probeLinesUsable()) || (hasFreshPushSniff() && hasMainPlayer()) || hasFreshIframeVideo() || hasPushMainStream() || hasSubEps()
                // push v12：探针认出线路/选集表且本页有主播放器时也亮钮（gaze 非 MacCMS 地址、枫叶单集 1 线 1 集
                // 都不在原有亮灯条件里；列表页无表、无主播放器，仍不亮）。
                || (hasProbeTable() && hasMainPlayer());
        mBinding.pushPlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** push v1：顶栏「推送」钮点击——多线探针够格时取当前集直达流（页面 video src 优先，120 秒内嗅探次之），转原生播放器；无流则留在网页。 */
    private void playNative() {
        // push v8：单片模式（无线路表但 120 秒内嗅到过流）也放行，推当前一部；单片跳过重探针（无表可探）。
        // TV push v4 fix 3(b)：单片模式第三条路——探针在同源 iframe 里见过视频流（hsck 类）。
        // push v9：单片第四条路——探针主片流可用（多视频单集/单片声明地址）；子帖页走子页解析。
        boolean multi = probeLinesUsable();
        boolean single = !multi && ((hasFreshPushSniff() && hasMainPlayer()) || hasFreshIframeVideo() || hasPushMainStream());
        boolean sub = !multi && !single && hasSubEps();
        // push v12：探针认出线路 tab/选集按钮（有表但不够 multi 格，如枫叶单集、gaze 未嗅流时）
        // 也不弹 toast，走单片续跑取当前集流；取不到流时续跑里报「未检测到可提取的视频地址」。
        boolean tabbed = !multi && !single && !sub && hasProbeTable();
        if (!multi && !single && !sub && !tabbed) {
            Notify.show("当前页面未识别到线路/集数");
            return;
        }
        if (sub) {
            resolveSubPagesAndPlay();
            return;
        }
        if (single || tabbed) {
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

    /** push v2：探针就绪后的续跑——取当前集直达流（页面 video src 优先，120 秒内嗅探次之），转原生播放器；无流则留在网页。 */
    /** push v8：把 PROBE_JS 带回的主片流/广告黑名单/片头广告标志存下来（probePageInfo 与点推时现跑共用）。 */
    private void storePushStreamFields(JSONObject obj) {
        if (obj == null) return;
        try {
            String ms = obj.optString("mainStream", "");
            mPushMainStream = ms == null ? "" : ms;
            JSONArray hd = obj.optJSONArray("hlsDefs");
            mPushHlsDefs = hd == null ? "" : hd.toString();
            mPushDeclMaster = obj.optString("declMaster", "");
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

    /** push v8：解析 PROBE_JS 的 evaluateJavascript 回值（JSON 字符串外面包一层引号）。 */
    private JSONObject parseProbeJson(String value) {
        if (value == null) return null;
        try {
            String json = new JSONTokener(value).nextValue().toString();
            return new JSONObject(json);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void continuePushPlay() {
        continuePushPlay(true);
    }

    private void continuePushPlay(boolean allowPreload) {
        // push v8：单片模式（无线路表但 120 秒内嗅到过流）也放行。
        // TV push v4 fix 3(b)：单片模式第三条路——探针在同源 iframe 里见过视频流。
        if (!probeLinesUsable() && !(hasFreshPushSniff() && hasMainPlayer()) && !hasFreshIframeVideo() && !hasPushMainStream() && !hasProbeTable()) {
            Notify.show("当前页面未识别到线路/集数");
            return;
        }
        if (mBinding == null || mBinding.web == null) return;
        // push v8：点推那一刻现跑 PROBE_JS（主片声明地址 + 广告黑名单 + 片头广告标志现取现用），
        // 再取 VIDEO_PROBE 的主元素直链。取流顺位（不用时长）：① 探针 mainStream →
        // ② 主元素 http 直链（非 blob、非广告播放中、不在黑名单、过 isPushStreamUrl 门）→
        // ③ 已过滤的 mPushLastSniff → ④ TV 旧兜底：现扫 iframe 视频 / 探针 iframeVideo / player.url。
        // push v22/v18（hohoj）：探针 mainStream 末尾加 frameStream()——顶层取不到主片时，下钻播放器位置上、无广告标记、
        // 同源可读的 iframe，取其主 video 直链或该帧唯一 .m3u8 作主片（跨域广告小窗读不到，天然排除），压过 ③ 嗅流。
        // push v13 F2: point-push re-run also writes the fresh table back (the table is a
        // page-load snapshot, the stream is a point-push live value; a late-rendered table
        // can still make it in time). Only a non-empty lines array is stored; an empty
        // table never overwrites the old one. Page-anchored like probePageInfo: a late
        // callback from an old page must not write its table back to the new page.
        final String probePage = mPushPageUrl;
        mBinding.web.evaluateJavascript(PROBE_JS, pvalue -> {
            JSONObject pobj = parseProbeJson(pvalue);
            if (pobj != null) {
                storePushStreamFields(pobj);
                if (probePage.equals(mPushPageUrl)) {
                    JSONArray probeLines = pobj.optJSONArray("lines");
                    if (probeLines != null && probeLines.length() > 0) {
                        dedupePushLineNames(probeLines);
                        mPushProbeLines = pobj.toString();
                        DebugLogStore.add(LogCategory.PLAY, "WebPlay", "push 点推重探 线路=" + probeLines.length(), false);
                    }
                }
                // push v16：gaze 懒加载面板预加载——探针发现 tab+按钮式线路有未加载面板时
                // 先逐个点开 tab 等面板出集、点回原 tab，再重探收全表；预加载跑起来即 return，
                // 续跑由预加载完成时重进 continuePushPlay(false) 接手（不再二次预加载）。
                if (allowPreload && startPushPreloadIfNeeded(pobj, probePage)) return;
            }
            else {
                mPushMainStream = "";
                mPushHlsDefs = "";
                mPushDeclMaster = "";
                mPushAdSrcs = java.util.Collections.emptySet();
                mPushAdPlaying = false;
                mPushHasMainPlayer = false;
            }
            mBinding.web.evaluateJavascript(VIDEO_PROBE, value -> {
                String src = value == null ? "" : value.replace("\"", "").trim();
                String stream = "";
                if (!TextUtils.isEmpty(mPushMainStream) && isPushStreamUrl(mPushMainStream)) stream = mPushMainStream;
                else if (!TextUtils.isEmpty(src) && !"null".equals(src) && src.startsWith("http") && isPushStreamUrl(src) && !mPushAdPlaying && !mPushAdSrcs.contains(src)) stream = src;
                else if (!TextUtils.isEmpty(mPushLastSniff) && System.currentTimeMillis() - mPushSniffAt < 120000 && !mPushAdSrcs.contains(mPushLastSniff) && mPushSniffPageUrl.equals(mPushPageUrl)) stream = mPushLastSniff;
                if (!stream.isEmpty()) {
                    startProbedPlay(stream);
                    return;
                }
                // TV push v4 fix 3(a)：顶层无 video 时，点推那一刻现扫同源 iframe 里的 video
                // （播放器挂载晚，探针跑时可能还没进 DOM；电视原生 HLS 下 .m3u8 不走嗅探）。
                mBinding.web.evaluateJavascript(IFRAME_VIDEO_PROBE, ivalue -> {
                    String isrc = ivalue == null ? "" : ivalue.replace("\"", "").trim();
                    String stream2 = "";
                    if (!TextUtils.isEmpty(isrc) && !"null".equals(isrc) && isPushStreamUrl(isrc)) stream2 = isrc;
                    else if (!TextUtils.isEmpty(mPushIframeVideo) && System.currentTimeMillis() - mPushIframeVideoAt < 120000 && isPushStreamUrl(mPushIframeVideo)) stream2 = mPushIframeVideo;
                    // TV push v4 fix 1：探针 player.url 兜底（player_aaaa 直链；NB 的令牌不是 URL，过不了 isPushStreamUrl 门）。
                    else if (!TextUtils.isEmpty(mPushProbePlayerUrl) && isPushStreamUrl(mPushProbePlayerUrl)) stream2 = mPushProbePlayerUrl;
                    if (!stream2.isEmpty()) {
                        startProbedPlay(stream2);
                        return;
                    }
                    Notify.show("未检测到可提取的视频地址");
                });
            });
        });
    }

    // ===== push v14 (c)：浏览器内点集解流（原型）=====
    // 播放器点 gaze 式按钮集（有 data-id、无 url）时，不走 HTTP 解析器：让留在后台栈里
    // 的现有浏览器点一下该集按钮，站方自己的 JS 解出流，现有嗅探（mPushLastSniff）抓到
    // 后交回播放器。不新建任何 WebView（不用用户自己打开的可见浏览器之外的 WebView），
    // 但会切掉浏览器自己的当前播放（Liu 已知情并点头）。生死点：后台 WebView 的媒体加载
    // 可能被节流，点按钮后嗅探器未必抓得到流——本次只做原型，交 Liu 真机验证。
    /** push v14 (c)：浏览器内点集解流的回交（与 HttpEpisodeResolver.Callback 同形）。 */
    public interface BrowserEpisodeCallback {
        void onResolved(String url, Map<String, String> headers);
        void onFailed();
    }

    // ===== push v16：gaze 懒加载面板预加载（点推时驱动，复用 (c) 同款 300ms 轮询写法）=====
    // 探针是同步 IIFE，等不了站方异步加载（/play_source_group 回包），故点 tab 与轮询
    // 必须走 Java 侧：点开 tab → 轮询 __musePreloadReady → 出集或单 tab 4 秒超时即下一
    // tab；总上限 10 秒；收完点回原 tab（恢复用户视角）再重探收全表。MacCMS/dplayer 页
    // tabLines==0 不进；面板已全加载时 preloadTabs 为空，不点任何 tab。
    private boolean startPushPreloadIfNeeded(JSONObject pobj, String probePage) {
        if (mPushPreloadAttempted) return false;
        if (pobj == null || pobj.optInt("tabLines", 0) != 1) return false;
        JSONArray tabs = pobj.optJSONArray("preloadTabs");
        if (tabs == null || tabs.length() == 0) return false;
        if (!probePage.equals(mPushPageUrl)) return false;
        if (mBinding == null || mBinding.web == null) return false;
        int[] idx = new int[tabs.length()];
        for (int i = 0; i < tabs.length(); i++) idx[i] = tabs.optInt(i, -1);
        if (idx.length == 0 || idx[0] < 0) return false;
        mPushPreloadAttempted = true;
        mPushPreloadTabs = idx;
        mPushPreloadPos = 0;
        mPushPreloadActive = pobj.optInt("preloadActive", -1);
        mPushPreloadPage = probePage;
        mPushPreloadDeadline = SystemClock.uptimeMillis() + 10000;
        clickPreloadTab(0);
        return true;
    }

    private void clickPreloadTab(int pos) {
        if (mBinding == null || mBinding.web == null || !mPushPreloadPage.equals(mPushPageUrl)) {
            finishPushPreload();
            return;
        }
        mPushPreloadTabDeadline = SystemClock.uptimeMillis() + 4000;
        mBinding.web.evaluateJavascript(String.format(PRELOAD_CLICK_JS, mPushPreloadTabs[pos]), v -> schedulePushPreloadPoll());
    }

    private void schedulePushPreloadPoll() {
        if (mPushPreloadPoll != null && mBinding != null && mBinding.web != null) mBinding.web.removeCallbacks(mPushPreloadPoll);
        mPushPreloadPoll = this::pollPushPreload;
        if (mBinding != null && mBinding.web != null) mBinding.web.postDelayed(mPushPreloadPoll, 300);
    }

    private void pollPushPreload() {
        if (mBinding == null || mBinding.web == null || !mPushPreloadPage.equals(mPushPageUrl)) {
            finishPushPreload();
            return;
        }
        if (SystemClock.uptimeMillis() > mPushPreloadDeadline) {
            finishPushPreload();
            return;
        }
        mBinding.web.evaluateJavascript(String.format(PRELOAD_READY_JS, mPushPreloadTabs[mPushPreloadPos]), v -> {
            boolean ready = "1".equals(v == null ? "" : v.replace("\"", "").trim());
            if (ready || SystemClock.uptimeMillis() > mPushPreloadTabDeadline) {
                mPushPreloadPos++;
                if (mPushPreloadPos >= mPushPreloadTabs.length) finishPushPreload();
                else clickPreloadTab(mPushPreloadPos);
                return;
            }
            schedulePushPreloadPoll();
        });
    }

    private void finishPushPreload() {
        if (mPushPreloadPoll != null && mBinding != null && mBinding.web != null) mBinding.web.removeCallbacks(mPushPreloadPoll);
        mPushPreloadPoll = null;
        // 点回原来选中的 tab（恢复用户视角；无原 tab 或页已切走时跳过）。
        if (mPushPreloadActive >= 0 && mBinding != null && mBinding.web != null && mPushPreloadPage.equals(mPushPageUrl)) {
            mBinding.web.evaluateJavascript(String.format(PRELOAD_CLICK_JS, mPushPreloadActive), v -> {
            });
        }
        // 重探一次收全表，再走正常点推续跑（allowPreload=false，防循环）；页已切走/
        // 浏览器已关时不续跑。
        if (mBinding != null && mBinding.web != null && mPushPreloadPage.equals(mPushPageUrl)) {
            continuePushPlay(false);
        }
    }

    // push v14 (c)：后台栈里现存浏览器的弱引用（onCreate 登记、onDestroy 注销；用户关掉
    // 浏览器后引用失效，点集直接回落 toast，不重建浏览器）。
    private static volatile WeakReference<WebPlayActivity> sLiveBrowser;

    // push v14 (c)：一次点集请求的进行中状态（同一时刻只许一单；新单静默丢弃旧单——
    // 旧单的播放器回调有 isSelected 守卫，绝不回调旧单，免得旧集 toast/进度乱冒）。
    private BrowserEpisodeCallback mBrowserClickCallback;
    private int mBrowserClickSeq;
    private Runnable mBrowserClickPoll;
    private long mBrowserClickDeadline;
    private String mBrowserClickBaselineSniff = "";
    private long mBrowserClickBaselineAt;
    private String mBrowserClickBaselineMain = "";
    private String mBrowserClickCandidate = "";
    private long mBrowserClickCandidateAt;
    private String mBrowserClickPage = "";
    private String mBrowserClickLine = "";
    // push v15 (e1)：兜底导航模式标记——true 表示本次待解单是“导航到播放页”而不是
    // “点按钮”；mBrowserClickLanded 表示导航已落到目标页（mPushPageUrl == mBrowserClickPage）。
    private boolean mBrowserClickPageResolve;
    private boolean mBrowserClickLanded;
    // push v25 (B)（电视 v19 适配）：兜底导航的目标页已 onPageFinished——落页后探针主片优先，
    // 嗅流只在无主片时用：须目标页已完成加载，或嗅流候选已等满 BROWSER_CLICK_SNIFF_WAIT_MS 仍无主片。
    private boolean mBrowserClickPageDone;
    private long mBrowserClickSniffSince;
    private static final long BROWSER_CLICK_SNIFF_WAIT_MS = 3000;
    // push v16：gaze 懒加载面板预加载——只在点推那一刻跑（后台探针不跑，不扰用户浏览）：
    // 探针报 preloadTabs（tab+按钮式线路里面板未加载的 tab 下标，见 PROBE_JS 的
    // __musePreloadTabs）时逐个点开 tab 等面板出选集按钮，收完点回原 tab 再重探；
    // mPushPreloadAttempted 是单次标记（一次点推最多预加载一轮，防重探循环），切页复位。
    private Runnable mPushPreloadPoll;
    private long mPushPreloadDeadline;
    private long mPushPreloadTabDeadline;
    private int[] mPushPreloadTabs;
    private int mPushPreloadPos;
    private int mPushPreloadActive = -1;
    private String mPushPreloadPage = "";
    private boolean mPushPreloadAttempted;

    /**
     * push v14 (c)：播放器请求后台浏览器点一集（data-id）。浏览器已关/已销毁时返回 false，
     * 调用方直接回落现有 toast；返回 true 表示请求已接下（等待态由调用方显示）。
     */
    public static boolean requestBrowserEpisodeClick(String episodeId, String lineName, String episodeName, BrowserEpisodeCallback callback) {
        if (episodeId == null || episodeId.isEmpty() || callback == null) return false;
        WebPlayActivity act = sLiveBrowser == null ? null : sLiveBrowser.get();
        if (act == null) return false;
        return act.startBrowserEpisodeClick(episodeId, lineName, episodeName, callback);
    }

    /**
     * push v15 (e1)：解析失败兜底——播放器请求后台浏览器直接导航到某集播放页。
     * 复用 v14 (c) 的同一套机器（seq、15 秒 deadline、500ms 轮询、首见候选多等一轮、
     * 页切守卫、单 pending、回调），区别只有三处：① 不点按钮，改为 loadUrl(pageUrl)
     * 直接导航到该集播放页；② 不做线 tab 预点（直接导航已进正确线路）；③ 页切守卫
     * 的 mBrowserClickPage 置为目标 pageUrl（不是当前 mPushPageUrl）。浏览器已关/
     * 已销毁时返回 false，调用方直接回落现有 toast；返回 true 表示请求已接下。
     * 静音与 (c) 一致：电视 (c) 路无静音代码（仅探针 JS 把 v.loop&&v.muted 当广告
     * 信号），故 (e1) 不另加；后台浏览器在兜底全程与 (c) 同样处于 paused 状态。
     */
    public static boolean requestBrowserPageResolve(String pageUrl, String episodeName, BrowserEpisodeCallback callback) {
        if (pageUrl == null || !(pageUrl.startsWith("http://") || pageUrl.startsWith("https://")) || callback == null) return false;
        WebPlayActivity act = sLiveBrowser == null ? null : sLiveBrowser.get();
        if (act == null) return false;
        return act.startBrowserPageResolve(pageUrl, episodeName, callback);
    }

    private boolean startBrowserPageResolve(String pageUrl, String episodeName, BrowserEpisodeCallback callback) {
        if (mBinding == null || mBinding.web == null) return false;
        cancelBrowserEpisodeClick();
        mBrowserClickSeq++;
        final int seq = mBrowserClickSeq;
        mBrowserClickCallback = callback;
        mBrowserClickPage = pageUrl;
        mBrowserClickLine = "";
        mBrowserClickPageResolve = true;
        mBrowserClickLanded = false;
        mBrowserClickPageDone = false;
        mBrowserClickSniffSince = 0;
        mBrowserClickBaselineSniff = mPushLastSniff == null ? "" : mPushLastSniff;
        mBrowserClickBaselineAt = mPushSniffAt;
        mBrowserClickBaselineMain = mPushMainStream == null ? "" : mPushMainStream;
        mBrowserClickCandidate = "";
        // push v15 (e1)：与 (c) 同款超时 15 秒（report18 建议值），到时无流即回落 toast。
        mBrowserClickDeadline = SystemClock.uptimeMillis() + 15000;
        mBinding.web.post(() -> {
            if (seq != mBrowserClickSeq || mBrowserClickCallback == null) return;
            if (mBinding == null || mBinding.web == null) {
                finishBrowserEpisodeClick(seq, false, null, null);
                return;
            }
            mBinding.web.loadUrl(pageUrl);
            scheduleBrowserClickPoll(seq);
        });
        return true;
    }

    private boolean startBrowserEpisodeClick(String episodeId, String lineName, String episodeName, BrowserEpisodeCallback callback) {
        if (mBinding == null || mBinding.web == null) return false;
        cancelBrowserEpisodeClick();
        mBrowserClickSeq++;
        final int seq = mBrowserClickSeq;
        mBrowserClickCallback = callback;
        mBrowserClickPage = mPushPageUrl;
        mBrowserClickLine = lineName == null ? "" : lineName;
        mBrowserClickBaselineSniff = mPushLastSniff == null ? "" : mPushLastSniff;
        mBrowserClickBaselineAt = mPushSniffAt;
        mBrowserClickBaselineMain = mPushMainStream == null ? "" : mPushMainStream;
        mBrowserClickCandidate = "";
        // push v14 (c)：超时 15 秒（report16 建议值），到时无流即回落 toast。
        mBrowserClickDeadline = SystemClock.uptimeMillis() + 15000;
        mBinding.web.post(() -> runBrowserEpisodeClickJs(episodeId, seq, false));
        return true;
    }

    private static String escapeBrowserClickId(String id) {
        if (id == null) return "";
        return id.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void runBrowserEpisodeClickJs(String episodeId, int seq, boolean tabTried) {
        if (seq != mBrowserClickSeq || mBrowserClickCallback == null) return;
        if (mBinding == null || mBinding.web == null) {
            finishBrowserEpisodeClick(seq, false, null, null);
            return;
        }
        String js = "(function(){try{var b=document.querySelector('button[data-id=\""
                + escapeBrowserClickId(episodeId) + "\"]');if(!b)return 'missing';b.click();return 'ok';}catch(e){return 'err';}})()";
        mBinding.web.evaluateJavascript(js, value -> {
            if (seq != mBrowserClickSeq || mBrowserClickCallback == null) return;
            String r = value == null ? "" : value.replace("\"", "").trim();
            if ("ok".equals(r)) {
                scheduleBrowserClickPoll(seq);
                return;
            }
            // 按钮不在 DOM 里：目标集可能在未加载的线面板里（站方懒加载）——先点对应线
            // tab，等面板加载后再点一次；tab 也找不到时直接失败。
            if (!tabTried && !mBrowserClickLine.isEmpty()) {
                tryBrowserClickLineTab(episodeId, seq);
                return;
            }
            finishBrowserEpisodeClick(seq, false, null, null);
        });
    }

    private void tryBrowserClickLineTab(String episodeId, int seq) {
        if (mBinding == null || mBinding.web == null) {
            finishBrowserEpisodeClick(seq, false, null, null);
            return;
        }
        String line = mBrowserClickLine.replace("\\", "\\\\").replace("'", "\\'");
        String js = "(function(){try{var ts=document.querySelectorAll('[role=\"tab\"],[data-toggle=\"tab\"]');"
                + "for(var i=0;i<ts.length;i++){var t=(ts[i].innerText||ts[i].textContent||'').trim();"
                + "if(t==='" + line + "'){ts[i].click();return 'ok';}}return 'missing';}catch(e){return 'err';}})()";
        mBinding.web.evaluateJavascript(js, value -> {
            if (seq != mBrowserClickSeq || mBrowserClickCallback == null) return;
            String r = value == null ? "" : value.replace("\"", "").trim();
            if (!"ok".equals(r)) {
                finishBrowserEpisodeClick(seq, false, null, null);
                return;
            }
            // 站方切线后懒加载面板（loadSourceGroup），等 1.5 秒再点该集按钮。
            mBinding.web.postDelayed(() -> runBrowserEpisodeClickJs(episodeId, seq, true), 1500);
        });
    }

    private void scheduleBrowserClickPoll(int seq) {
        if (mBrowserClickPoll != null && mBinding != null && mBinding.web != null) mBinding.web.removeCallbacks(mBrowserClickPoll);
        mBrowserClickPoll = () -> pollBrowserEpisodeClick(seq);
        if (mBinding != null && mBinding.web != null) mBinding.web.postDelayed(mBrowserClickPoll, 500);
    }

    private void pollBrowserEpisodeClick(int seq) {
        if (seq != mBrowserClickSeq || mBrowserClickCallback == null) return;
        if (mBinding == null || mBinding.web == null) {
            finishBrowserEpisodeClick(seq, false, null, null);
            return;
        }
        if (mBrowserClickPageResolve) {
            // push v15 (e1)：兜底是导航不是点按钮——页切守卫只在落到目标页之后才生效。
            // 导航发出后、onPageStarted 更新 mPushPageUrl 之前仍是旧页，直接判失败会误杀；
            // 落到目标页后用户/站方再切页才作废；一直落不到目标页则等到 15 秒超时。
            if (mPushPageUrl.equals(mBrowserClickPage)) {
                mBrowserClickLanded = true;
            } else if (mBrowserClickLanded) {
                finishBrowserEpisodeClick(seq, false, null, null);
                return;
            }
        } else if (!mBrowserClickPage.equals(mPushPageUrl)) {
            // 点集后用户在浏览器里切了页：本次请求作废。
            finishBrowserEpisodeClick(seq, false, null, null);
            return;
        }
        String sniff = mPushLastSniff == null ? "" : mPushLastSniff;
        boolean freshSniff = !sniff.isEmpty() && !sniff.equals(mBrowserClickBaselineSniff)
                && mPushSniffAt > mBrowserClickBaselineAt && isPushStreamUrl(sniff)
                // push v15 (e1)：嗅流须是目标页嗅到的——导航前旧页刚嗅到的新分段不是目标集的流。
                && (!mBrowserClickPageResolve || mPushSniffPageUrl.equals(mBrowserClickPage));
        String main = mPushMainStream == null ? "" : mPushMainStream;
        boolean freshMain = !main.isEmpty() && !main.equals(mBrowserClickBaselineMain) && isPushStreamUrl(main);
        // push v15 (e1)：导航落到目标页之前不认任何候选——旧页残留的流（导航前刚嗅到的
        // 新分段）不是目标集的流，认了会播错集；落页后再走与 (c) 同款的首见多等一轮。
        // push v25 (B)（电视 v19 适配）：兜底导航模式（mBrowserClickPageResolve）落页后主片优先——探针主片
        //（主播放器声明地址，按结构认、不按时长）可用且不在广告黑名单就交主片；没有主片时，嗅流须等目标页
        // 加载完成（onPageFinished）或嗅流已等满 3 秒才交（同站广告帧的流可能抢在主片前嗅到）。
        // (c) 点按钮模式照旧嗅流优先（点集瞬间探针可能还读到旧集主片，会播错集）。
        boolean pageMainOk = mBrowserClickPageResolve && freshMain && !mPushAdSrcs.contains(main);
        if (mBrowserClickPageResolve && mBrowserClickLanded && !pageMainOk && freshSniff) {
            if (mBrowserClickSniffSince == 0) mBrowserClickSniffSince = SystemClock.uptimeMillis();
        }
        boolean pageSniffOk = freshSniff && (mBrowserClickPageDone
                || (mBrowserClickSniffSince > 0 && SystemClock.uptimeMillis() - mBrowserClickSniffSince >= BROWSER_CLICK_SNIFF_WAIT_MS));
        boolean haveCand = mBrowserClickPageResolve ? (mBrowserClickLanded && (pageMainOk || pageSniffOk)) : (freshSniff || freshMain);
        if (haveCand) {
            String cand = mBrowserClickPageResolve ? (pageMainOk ? main : sniff) : (freshSniff ? sniff : main);
            // 首见先记下候选、再等一轮（500ms）：嗅到流会触发 probePageInfo 刷新广告
            // 黑名单/片头广告标志，拿刷新后的状态再判，免得把片头广告流交回去。
            if (!cand.equals(mBrowserClickCandidate)) {
                mBrowserClickCandidate = cand;
                mBrowserClickCandidateAt = SystemClock.uptimeMillis();
            } else if (SystemClock.uptimeMillis() - mBrowserClickCandidateAt >= 500) {
                if (!mPushAdSrcs.contains(cand) && !mPushAdPlaying) {
                    offerPushHeaderSets(cand);
                    finishBrowserEpisodeClick(seq, true, cand, validatedPushHeaders());
                    return;
                }
                // 候选是广告（或正放片头广告）：丢弃它、继续等别的流；把当前值记为新
                // 基线，同一条地址不再二次候选。
                mBrowserClickBaselineSniff = sniff;
                mBrowserClickBaselineMain = main;
                mBrowserClickCandidate = "";
                mBrowserClickSniffSince = 0;
            }
        }
        if (SystemClock.uptimeMillis() > mBrowserClickDeadline) {
            finishBrowserEpisodeClick(seq, false, null, null);
            return;
        }
        if (mBinding != null && mBinding.web != null) mBinding.web.postDelayed(mBrowserClickPoll, 500);
    }

    private void finishBrowserEpisodeClick(int seq, boolean ok, String url, Map<String, String> headers) {
        if (seq != mBrowserClickSeq) return;
        BrowserEpisodeCallback cb = mBrowserClickCallback;
        cancelBrowserEpisodeClick();
        if (cb == null) return;
        if (ok) cb.onResolved(url, headers);
        else cb.onFailed();
    }

    private void cancelBrowserEpisodeClick() {
        if (mBrowserClickPoll != null && mBinding != null && mBinding.web != null) {
            mBinding.web.removeCallbacks(mBrowserClickPoll);
        }
        mBrowserClickPoll = null;
        mBrowserClickCallback = null;
        mBrowserClickCandidate = "";
        // push v15 (e1)：清掉兜底模式标记，下一单 (c)/(e1) 各自重设，不带旧模式。
        mBrowserClickPageResolve = false;
        mBrowserClickLanded = false;
        mBrowserClickPageDone = false;
        mBrowserClickSniffSince = 0;
    }

    // push v16：电视站名回落（线路名）——与 BrowserMeta.siteName 同款逻辑，但 '-' 后跟数字时
    // 不断段（FWAY-104、SNOS-334 是编号的一部分，不切成 104/334；口径与
    // BrowserMeta.cutSuffix 的 A3 规则一致）。共用 BrowserMeta.siteName 是手机电视共用代码
    // （手机 WebPlayActivity 同款调用），改它会连手机一起改，故电视侧本地复刻一份，
    // 手机行为零变化。BOILERPLATE 与 BrowserMeta 的同名常量同值。
    private static final String SITE_NAME_BOILERPLATE = "(高清完整版|完整版|高清版|免费在线观看|在线观看|在线播放|高清在线|免费观看|全集观看|免费在线|在线免费)";
    private static String siteNameKeepNumber(String rawTitle, String fallback) {
        if (rawTitle != null) {
            String best = "";
            for (String segment : rawTitle.split("-(?!\\d)|[_｜|—–]")) {
                String s = segment.replaceAll(SITE_NAME_BOILERPLATE, "").trim();
                if (s.length() < 2) continue;
                if (s.contains("影院") || s.contains("影视") || s.contains("影城") || s.contains("剧场") || s.contains("视频")) return s;
                if (s.length() <= 8) best = s;
            }
            if (!best.isEmpty()) return best;
        }
        return fallback == null ? "" : fallback.trim();
    }

    /** push v1：MacCMS 播放页转播放——当前集直达流 + 全部线路表进原生播放器。 */
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
        if (flag.isEmpty()) flag = siteNameKeepNumber(rawTitle, mTitle);
        String episode = BrowserMeta.episodeName(mPushPageUrl, rawTitle);
        // push v12：真标签优先——探针带回 curEpName（HD中字/第33集）时一律用它，盖掉标题/网址序号；
        // 单片模式去掉标题取集（report11 §5 补丁）：标题里的「第X集」是假集名（如枫叶播放页标题
        // 含第1集而真标签是 HD中字），无真标签显示「正片」，永不显示「第0集」/「第1集」。
        String probeEp = probeCurEpName();
        if (!probeEp.isEmpty()) episode = probeEp;
        else if (!probeLinesUsable()) episode = "正片";
        Map<String, String> headers = validatedPushHeaders();
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
        // push v43/v25：登记本页站方各档/声明主清单，播放器落地前 PushMultiRes 升级为多画质（不成照原地址）。
        PushMultiRes.offer(stream, mPushHlsDefs, mPushDeclMaster);
        // push v49/v33：登记候选请求头（原样嗅到 → Referer=当前页 → 不带 Referer），PushMultiRes 取清单时按序试。
        offerPushHeaderSets(stream);
        VideoActivity.startWithHeaders(this, stream, headers, title, flag, episode, null, mPushPageUrl, mPushProbeLines, pushPic, origin, vid, sid, nid, metaContent, metaYear, metaArea);
    }

    /** push v11 A1：嗅头里的 Referer 若是别的域（正片没点播过时最后嗅到的只能是预告/广告窗的流，
     * 广告在跨域 iframe 内，Referer 是广告域），整份嗅头不可信，丢弃重建——MissAV 媒体 CDN
     * 空 Referer 放行、错 Referer 403。Referer 为空不算错，保持原样。 */
    private Map<String, String> validatedPushHeaders() {
        Map<String, String> headers = mPushSniffedHeaders;
        String ref = headers == null ? null : getPushHeader(headers, "Referer");
        if (!TextUtils.isEmpty(ref)) {
            String refHost = hostOfUrl(ref);
            String pageHost = hostOfUrl(mPushPageUrl);
            if (refHost != null && pageHost != null && !refHost.equalsIgnoreCase(pageHost)) {
                headers = null;
            }
        }
        if (headers == null) {
            headers = new LinkedHashMap<>();
            if (!mPushPageUrl.isEmpty()) headers.put("Referer", mPushPageUrl);
            String ua = Setting.getUa();
            if (!TextUtils.isEmpty(ua)) headers.put("User-Agent", ua);
        }
        return headers;
    }

    private static String getPushHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static String hostOfUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            return Uri.parse(url).getHost();
        } catch (Throwable ignored) {
            return null;
        }
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
        mBinding.web.onResume();
    }

    @Override
    protected void onPause() {
        mBinding.web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // push v14 (c)：销毁时取消进行中的点集请求、注销现存浏览器（回调不再回交）。
        cancelBrowserEpisodeClick();
        // push v16：销毁时撤掉未跑完的预加载轮询（回调不再续跑）。
        if (mPushPreloadPoll != null && mBinding != null && mBinding.web != null) mBinding.web.removeCallbacks(mPushPreloadPoll);
        mPushPreloadPoll = null;
        if (sLiveBrowser != null && sLiveBrowser.get() == this) sLiveBrowser = null;
        cancelPushProbeRetry();
        if (mBinding != null) {
            mBinding.web.loadUrl("about:blank");
            mBinding.web.destroy();
        }
        super.onDestroy();
    }
}



