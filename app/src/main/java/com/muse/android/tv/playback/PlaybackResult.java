package com.muse.android.tv.playback;

import com.muse.android.tv.bean.Result;

public record PlaybackResult<T>(T request, Result result) {
}
