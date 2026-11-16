package com.muse.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.muse.android.tv.App;
import com.muse.android.tv.Constant;
import com.muse.android.tv.api.loader.BaseLoader;
import com.muse.android.tv.db.AppDatabase;
import com.muse.android.tv.gson.ExtAdapter;
import com.muse.android.tv.gson.HeaderAdapter;
import com.muse.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Entity
public class Site implements Parcelable {

    // 源名中的分组标记形如 "[PY]" "[JS]"，用于源选择弹窗的动态筛选标签
    private static final Pattern GROUP_PATTERN = Pattern.compile("\\[([^\\]]+)]");

    // 源名中的写法标记形如 "(PY)" "(XBPQ)" "(JS)"，用于按源写法筛选
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\(([^)]+)\\)");

    @NonNull
    @PrimaryKey
    @SerializedName("key")
    private String key;

    @Ignore
    @SerializedName("name")
    private String name;

    @Ignore
    @SerializedName("api")
    private String api;

    @Ignore
    @SerializedName("ext")
    @JsonAdapter(ExtAdapter.class)
    private String ext;

    @Ignore
    @SerializedName("jar")
    private String jar;

    @Ignore
    @SerializedName("click")
    private String click;

    @Ignore
    @SerializedName("playUrl")
    private String playUrl;

    @Ignore
    @SerializedName("webPlay")
    private Integer webPlay;

    @Ignore
    @SerializedName("webPage")
    private String webPage;

    @Ignore
    @SerializedName("主页url")
    private String homePageUrl;

    @Ignore
    @SerializedName("type")
    private Integer type;

    @Ignore
    @SerializedName("hide")
    private Integer hide;

    @Ignore
    @SerializedName("indexs")
    private Integer indexs;

    @Ignore
    @SerializedName("timeout")
    private Integer timeout;

    @SerializedName("searchable")
    private Integer searchable;

    @SerializedName("changeable")
    private Integer changeable;

    @Ignore
    @SerializedName("quickSearch")
    private Integer quickSearch;

    @Ignore
    @SerializedName("danmaku")
    private Integer danmaku;

    @Ignore
    @SerializedName("categories")
    private List<String> categories;

    @Ignore
    @SerializedName("header")
    @JsonAdapter(HeaderAdapter.class)
    private Map<String, String> header;

    @Ignore
    @SerializedName("style")
    private Style style;

    @Ignore
    private boolean selected;

    public Site() {
    }

    protected Site(Parcel in) {
        this.key = in.readString();
        this.name = in.readString();
        this.api = in.readString();
        this.ext = in.readString();
        this.jar = in.readString();
        this.click = in.readString();
        this.playUrl = in.readString();
        this.type = (Integer) in.readValue(Integer.class.getClassLoader());
        this.indexs = (Integer) in.readValue(Integer.class.getClassLoader());
        this.timeout = (Integer) in.readValue(Integer.class.getClassLoader());
        this.searchable = (Integer) in.readValue(Integer.class.getClassLoader());
        this.changeable = (Integer) in.readValue(Integer.class.getClassLoader());
        this.categories = in.createStringArrayList();
        this.style = in.readParcelable(Style.class.getClassLoader());
        this.selected = in.readByte() != 0;
    }

    public static Site objectFrom(JsonElement element, String spider) {
        try {
            Site site = App.gson().fromJson(element, Site.class);
            if (site.getJar().isEmpty()) site.setJar(spider);
            site.setApi(UrlUtil.convert(site.getApi()));
            site.setExt(UrlUtil.convert(site.getExt()));
            return site.trans();
        } catch (Exception e) {
            return new Site();
        }
    }

    public static Site get(String key, String name) {
        Site site = new Site();
        site.setKey(key);
        site.setName(name);
        return site;
    }

    public static List<Site> findAll() {
        return AppDatabase.get().getSiteDao().findAll();
    }

    public String getKey() {
        return TextUtils.isEmpty(key) ? "" : key;
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApi() {
        return TextUtils.isEmpty(api) ? "" : api;
    }

    public void setApi(String api) {
        this.api = api;
    }

    public String getExt() {
        return TextUtils.isEmpty(ext) ? "" : ext;
    }

    public void setExt(String ext) {
        this.ext = ext.trim();
    }

    public String getJar() {
        return TextUtils.isEmpty(jar) ? "" : jar;
    }

    public void setJar(String jar) {
        this.jar = jar;
    }

    public String getClick() {
        return TextUtils.isEmpty(click) ? "" : click;
    }

    public String getPlayUrl() {
        return TextUtils.isEmpty(playUrl) ? "" : playUrl;
    }

    public Integer getWebPlay() {
        return webPlay == null ? 0 : webPlay;
    }

    /** 站点声明 {@code "webPlay": 1}：剧集一律走网页播放（WebPlayActivity），不解析不进原生播放器。 */
    public boolean isWebPlay() {
        return getWebPlay() == 1;
    }

    /** 站点条目顶层 {@code "webPage": "https://.../{id}..."}：裸 id 的播放页模板，优先于规则文件里的 webPage。 */
    public String getWebPage() {
        return TextUtils.isEmpty(webPage) ? "" : webPage;
    }

    /** 站点条目顶层 {@code "主页url": "https://..."}：源里写死的前端网址，浏览器入口/网页播放按钮认主页时最优先。 */
    public String getHomePageUrl() {
        return TextUtils.isEmpty(homePageUrl) ? "" : homePageUrl;
    }

    /**
     * v67：是不是 v61 网页源条目 —— 只有 key/name/主页url，没有 api/ext。
     * 普通源即使写了顶层「主页url」也有 api，所以不会误判。
     */
    public boolean isWebSource() {
        return getApi().isEmpty() && getExt().isEmpty() && !getHomePageUrl().isEmpty();
    }

    public Integer getType() {
        return type == null ? 0 : type;
    }

    public Integer getHide() {
        return hide == null ? 0 : hide;
    }

    public Integer getIndexs() {
        return indexs == null ? 0 : indexs;
    }

    public long getTimeout() {
        return timeout == null ? Constant.TIMEOUT_PLAY : TimeUnit.SECONDS.toMillis(Math.max(timeout, 1));
    }

    public Integer getSearchable() {
        return searchable == null ? 1 : searchable;
    }

    public Integer getChangeable() {
        return changeable == null ? 1 : changeable;
    }

    public Integer getQuickSearch() {
        return quickSearch == null ? 1 : quickSearch;
    }

    public Integer getDanmaku() {
        return danmaku == null ? 1 : danmaku;
    }

    public List<String> getCategories() {
        return categories == null ? Collections.emptyList() : categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public Map<String, String> getHeader() {
        return header == null ? new HashMap<>() : header;
    }

    public Style getStyle() {
        return style;
    }

    public Style getStyle(Style style) {
        return getStyle() != null ? getStyle() : style != null ? style : Style.rect();
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setSelected(Site item) {
        this.selected = item.equals(this);
    }

    /**
     * 从源名中提取分组标记（如 "[PY]"），用于源选择弹窗的动态筛选标签。
     */
    public List<String> getGroups() {
        List<String> groups = new ArrayList<>();
        Matcher matcher = GROUP_PATTERN.matcher(getName());
        while (matcher.find()) groups.add("[" + matcher.group(1) + "]");
        return groups;
    }

    /**
     * 判断本源是否属于指定分组标记。
     */
    public boolean inGroup(String group) {
        return TextUtils.isEmpty(group) || getGroups().contains(group);
    }

    /**
     * 汇总全部可见源的去重分组标记（保持出现顺序）。
     */
    public static List<String> getGroups(List<Site> items) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Site item : items) {
            if (item == null || item.isHide()) continue;
            result.addAll(item.getGroups());
        }
        return new ArrayList<>(result);
    }

    /**
     * 从源名中提取写法标记（如 "(PY)"、"(XBPQ)"、"(JS)"），用于按源写法筛选；
     * 纯数字标记（重名序号）不计入写法。
     */
    public List<String> getTypes() {
        List<String> types = new ArrayList<>();
        Matcher matcher = TYPE_PATTERN.matcher(getName());
        while (matcher.find()) {
            String value = matcher.group(1);
            if (TextUtils.isEmpty(value) || value.matches("\\d+")) continue;
            types.add("(" + value + ")");
        }
        return types;
    }

    /**
     * 判断本源是否属于指定写法标记。
     */
    public boolean inType(String type) {
        return TextUtils.isEmpty(type) || getTypes().contains(type);
    }

    /**
     * 汇总全部可见源的去重写法标记（保持出现顺序）。
     */
    public static List<String> getTypes(List<Site> items) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Site item : items) {
            if (item == null || item.isHide()) continue;
            result.addAll(item.getTypes());
        }
        return new ArrayList<>(result);
    }

    public boolean isHide() {
        return getHide() == 1;
    }

    public boolean isIndex() {
        return getIndexs() == 1;
    }

    public boolean isSearchable() {
        return getSearchable() == 1;
    }

    public void setSearchable(Integer searchable) {
        this.searchable = searchable;
    }

    public Site setSearchable(boolean searchable) {
        if (getSearchable() != 0) setSearchable(searchable ? 1 : 2);
        return this;
    }

    public boolean isChangeable() {
        return getChangeable() == 1;
    }

    public void setChangeable(Integer changeable) {
        this.changeable = changeable;
    }

    public Site setChangeable(boolean changeable) {
        if (getChangeable() != 0) setChangeable(changeable ? 1 : 2);
        return this;
    }

    public boolean isQuickSearch() {
        return getQuickSearch() == 1;
    }

    public boolean isEmpty() {
        return getKey().isEmpty() && getName().isEmpty();
    }

    public Site fetchExt() {
        if (!getExt().startsWith("http")) return this;
        String extend = OkHttp.string(getExt());
        if (!extend.isEmpty()) setExt(extend);
        return this;
    }

    public Site trans() {
        if (Trans.pass()) return this;
        this.name = Trans.s2t(name);
        setCategories(getCategories().stream().map(Trans::s2t).toList());
        return this;
    }

    public Site sync(Site item) {
        if (item == null) return this;
        if (getChangeable() != 0) setChangeable(Math.max(1, item.getChangeable()));
        if (getSearchable() != 0) setSearchable(Math.max(1, item.getSearchable()));
        return this;
    }

    public Site recent() {
        BaseLoader.get().setRecent(getKey(), getApi(), getJar());
        return this;
    }

    public Spider spider() {
        return BaseLoader.get().getSpider(getKey(), getApi(), getExt(), getJar());
    }

    public void save() {
        AppDatabase.get().getSiteDao().insertOrUpdate(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Site it)) return false;
        return Objects.equals(getKey(), it.getKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.key);
        dest.writeString(this.name);
        dest.writeString(this.api);
        dest.writeString(this.ext);
        dest.writeString(this.jar);
        dest.writeString(this.click);
        dest.writeString(this.playUrl);
        dest.writeValue(this.type);
        dest.writeValue(this.indexs);
        dest.writeValue(this.timeout);
        dest.writeValue(this.searchable);
        dest.writeValue(this.changeable);
        dest.writeStringList(this.categories);
        dest.writeParcelable(this.style, flags);
        dest.writeByte(this.selected ? (byte) 1 : (byte) 0);
    }

    public static final Creator<Site> CREATOR = new Creator<>() {
        @Override
        public Site createFromParcel(Parcel source) {
            return new Site(source);
        }

        @Override
        public Site[] newArray(int size) {
            return new Site[size];
        }
    };
}



