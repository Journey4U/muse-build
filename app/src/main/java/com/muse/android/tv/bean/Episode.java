package com.muse.android.tv.bean;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.muse.android.tv.impl.Diffable;
import com.muse.android.tv.utils.Util;
import com.github.catvod.utils.Trans;
import com.google.gson.annotations.SerializedName;

import java.util.Objects;

public class Episode implements Parcelable, Diffable<Episode> {

    @SerializedName("name")
    private String name;
    @SerializedName("desc")
    private String desc;
    @SerializedName("url")
    private String url;

    /** push v2：push 交接会话元数据（该集播放页地址），不进 Parcelable；每次交接由探针 JSON 重建。 */
    private String pageUrl;

    /** push v18 (b)：push 交接会话元数据（探针带的无 href 按钮式选集标识 data-id/data-path，如 gaze），不进 Parcelable；每次交接由探针 JSON 重建。 */
    private String browserEpId;
    private String browserEpPath;

    private int index;
    private int number;
    private boolean selected;

    private Episode(String name, String desc, String url) {
        this.number = Util.getNumber(name);
        this.name = name;
        this.desc = desc;
        this.url = url;
    }

    public Episode() {
    }

    protected Episode(Parcel in) {
        this.name = in.readString();
        this.desc = in.readString();
        this.url = in.readString();
        this.number = in.readInt();
        this.selected = in.readByte() != 0;
    }

    public static Episode create(String name, String url) {
        return new Episode(name, "", url).trans();
    }

    public static Episode create(String name, String desc, String url) {
        return new Episode(name, desc, url).trans();
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDesc() {
        return TextUtils.isEmpty(desc) ? "" : desc;
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    /** push v2：解析成功后把直链写回该集（原地改 url，不重建 Episode）。 */
    public void setUrl(String url) {
        this.url = url;
    }

    /** push v2：该集播放页地址（push 交接会话元数据，不进 Parcelable）。 */
    public String getPageUrl() {
        return TextUtils.isEmpty(pageUrl) ? "" : pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    /** push v18 (b)：该集的按钮式选集标识 data-id（无 href 按钮式选集才有，如 gaze；其余站为空）。 */
    public String getBrowserEpId() {
        return TextUtils.isEmpty(browserEpId) ? "" : browserEpId;
    }

    public void setBrowserEpId(String browserEpId) {
        this.browserEpId = browserEpId;
    }

    /** push v18 (b)：该集的按钮式选集标识 data-path（与 data-id 同源，仅作备用标识）。 */
    public String getBrowserEpPath() {
        return TextUtils.isEmpty(browserEpPath) ? "" : browserEpPath;
    }

    public void setBrowserEpPath(String browserEpPath) {
        this.browserEpPath = browserEpPath;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getNumber() {
        return number;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void deselect() {
        setSelected(false);
    }

    public int getScore(String name, int number) {
        if (getName().equalsIgnoreCase(name)) return 100;
        if (number != -1 && getNumber() == number) return 80;
        if (number == -1 && name.length() >= 2 && getName().toLowerCase().contains(name.toLowerCase())) return 70;
        if (number == -1 && getName().length() >= 2 && name.toLowerCase().contains(getName().toLowerCase())) return 60;
        return 0;
    }

    public boolean matchesName(Episode other) {
        if (other == null) return false;
        return getName().equalsIgnoreCase(other.getName());
    }

    public Episode trans() {
        if (Trans.pass()) return this;
        this.name = Trans.s2t(name);
        this.desc = Trans.s2t(desc);
        return this;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Episode it)) return false;
        return Objects.equals(getName(), it.getName()) && Objects.equals(getUrl(), it.getUrl());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getUrl());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeString(this.desc);
        dest.writeString(this.url);
        dest.writeInt(this.number);
        dest.writeByte(this.selected ? (byte) 1 : (byte) 0);
    }

    @Override
    public boolean isSameItem(Episode other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Episode other) {
        return getUrl().equals(other.getUrl()) && getDesc().equals(other.getDesc());
    }

    public record Rule(Episode episode, int score) {

        public boolean find() {
            return score > 0;
        }
    }

    public static final Creator<Episode> CREATOR = new Creator<>() {
        @Override
        public Episode createFromParcel(Parcel source) {
            return new Episode(source);
        }

        @Override
        public Episode[] newArray(int size) {
            return new Episode[size];
        }
    };
}
