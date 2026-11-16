package com.muse.android.tv.playback.vod;

import com.muse.android.tv.bean.Site;

import java.util.List;

public interface VodDataSource {

    void detailContent(String key, String id);

    void playerContent(VodPlayRequest request);

    void preloadContent(VodPlayRequest request);

    void searchContent(List<Site> sites, String keyword, boolean quick);
}
