package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.databinding.ActivityNovelBinding;
import com.muse.android.tv.ui.base.BaseActivity;

import org.json.JSONObject;

import java.util.ArrayList;

// Ported from the original MuseTV 5.6.3 TV build (Liu's reference APK, 2026-10-24):
// remote-first novel reader - DPAD scroll/page, OK shows UI, MENU cycles text size,
// long-press OK = night mode, long-press MENU = auto scroll.
public class NovelActivity extends BaseActivity {

    private static final long LONG_PRESS_TIMEOUT = 3000;
    public static final int REQUEST_NOVEL = 1002;

    private ActivityNovelBinding mBinding;
    private ArrayList<String> mEpisodeNames;
    private ArrayList<String> mEpisodeUrls;
    private int mEpisodePosition;
    private Runnable mMenuLongPressTask;
    private Runnable mOkLongPressTask;
    private String mTitle = "";
    private String mContent = "";
    private float mTextSize = 20.0f;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mAutoScrolling = false;
    private int mAutoScrollSpeed = 3;
    private boolean mNightMode = false;
    private boolean mMenuLongFired = false;
    private boolean mOkLongFired = false;

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
        Intent intent = new Intent(activity, NovelActivity.class);
        intent.putExtra("title", title);
        intent.putExtra("novelUrl", novelUrl);
        intent.putExtra("episodeNames", episodeNames);
        intent.putExtra("episodeUrls", episodeUrls);
        intent.putExtra("episodePosition", episodePosition);
        activity.startActivityForResult(intent, REQUEST_NOVEL);
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
        mEpisodePosition = getIntent().getIntExtra("episodePosition", 0);
        if (mEpisodeNames == null) mEpisodeNames = new ArrayList<>();
        if (mEpisodeUrls == null) mEpisodeUrls = new ArrayList<>();
        if (mTitle == null) mTitle = "";
        parseNovel(novelUrl);
        mNightMode = getPrefs().getBoolean("night_mode", false);
        applyNightMode();
        mBinding.titleText.setText(mTitle);
        mBinding.contentText.setText(mContent);
        mBinding.contentText.setTextSize(mTextSize);
        updateProgress();
        updateChapterButtons();
        restoreProgress();
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
        Intent intent = new Intent();
        intent.putExtra("episode_position", position);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void showEpisodeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("目录")
                .setSingleChoiceItems(mEpisodeNames.toArray(new String[0]), mEpisodePosition, (dialog, which) -> {
                    dialog.dismiss();
                    gotoEpisode(which);
                })
                .setNegativeButton("取消", null)
                .show();
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
        getPrefs().edit().putInt("progress_" + mTitle.hashCode(), getProgress()).apply();
    }

    private void restoreProgress() {
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

    private void toggleUI() {
        boolean visible = mBinding.titleBar.getVisibility() == View.VISIBLE;
        mBinding.titleBar.setVisibility(visible ? View.GONE : View.VISIBLE);
        mBinding.hintText.setVisibility(visible ? View.GONE : View.VISIBLE);
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
        if (event.getRepeatCount() > 0) return true;
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
                if (mAutoScrolling) stopAutoScroll();
                else toggleUI();
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
    protected void onPause() {
        super.onPause();
        saveProgress();
        stopAutoScroll();
        mHandler.removeCallbacks(mMenuLongPressTask);
        mHandler.removeCallbacks(mOkLongPressTask);
    }
}
