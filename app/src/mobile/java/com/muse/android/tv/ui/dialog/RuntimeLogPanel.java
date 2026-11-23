package com.muse.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.catvod.Proxy;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.LogCategory;
import com.muse.android.tv.R;
import com.muse.android.tv.utils.Notify;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Runtime log panel (original FongMi style: count + timestamped lines).
 * Data source is the same DebugLogStore that feeds the web debug page
 * (http://127.0.0.1:9978/debug/logs).
 *
 * #48 semantics (defined by Liu, superseding the #47 system overlay):
 * the panel floats INSIDE the app only - no WindowManager overlay, no
 * SYSTEM_ALERT_WINDOW permission, no permission detour. Its FrameLayout
 * container is inflated once and carries two children, the expanded
 * panel and a 52dp bubble; it is attached as the topmost child of the
 * foreground Activity's decorView, and an ActivityLifecycleCallbacks
 * registration re-parents it synchronously (detach then attach inside
 * the resume callback) whenever another Activity comes to the front, so
 * the bubble and the panel follow the user across every in-app screen.
 * All geometry lives in decorView coordinates via
 * FrameLayout.LayoutParams (leftMargin/topMargin = position,
 * width/height = size): the header drags the panel, the bottom-right
 * handle resizes it, Minimize collapses it into the bubble, and tapping
 * the bubble restores the full panel (drag / resize / search / buttons).
 *
 * #49: entries carry a LogCategory assigned at capture time; the chip
 * row under the count line filters on that field (stacked with the
 * keyword filter). Function buttons are a single capsule row and the
 * resize handle is a corner badge on the container, so the list gets
 * all the remaining height.
 */
public class RuntimeLogPanel {

    private static final int STATE_HIDDEN = 0;
    private static final int STATE_EXPANDED = 1;
    private static final int STATE_BUBBLE = 2;

    private static RuntimeLogPanel current;

    private Activity activity;
    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LogAdapter adapter = new LogAdapter();
    private final List<DebugLogStore.Entry> raw = new ArrayList<>();

    // Views of the bound panel tree.
    private View header;
    private View searchBar;
    private EditText searchInput;
    private TextView countText;
    private TextView emptyText;
    private RecyclerView listView;

    // In-app floating host: one container, attached to the foreground
    // Activity's decorView; the only moves it ever makes are between
    // decorViews (synchronously, inside lifecycle callbacks).
    private FrameLayout container;
    private View panelView;
    private View resizeView;
    private TextView bubble;
    private FrameLayout.LayoutParams lp;
    private int state = STATE_HIDDEN;
    private int expW, expH, expX, expY;
    private int bubX = -1, bubY = -1;
    private boolean lifecycleRegistered;

    private String keyword = "";
    // Active category filter; null shows everything ("全部").
    private LogCategory category;
    // 「报错」是筛选不是类：选中时只看带报错标记的条目（条目仍留在各自类里）。
    private boolean errorsOnly;
    private final TextView[] chips = new TextView[8];
    // Order per Liu 2026-11-23: 全部｜影片｜播放｜广告拦截｜报错｜文件加载｜蜘蛛｜运行 (index 4 = 报错 filter).
    private static final int ERROR_CHIP = 4;
    private final LogCategory[] chipCategories = {null, LogCategory.MEDIA, LogCategory.PLAY, LogCategory.AD, null, LogCategory.FILE, LogCategory.SPIDER, LogCategory.RUN};
    private long lastVersion = -1;
    private boolean firstLoad = true;
    private boolean polling;
    private boolean closed;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            if (state == STATE_EXPANDED && DebugLogStore.version() != lastVersion) reload(false);
            if (polling) handler.postDelayed(this, 1000);
        }
    };

    private final Application.ActivityLifecycleCallbacks lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(@NonNull Activity a, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity a) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity a) {
            if (closed || state == STATE_HIDDEN) return;
            // Follow the foreground Activity: attachTo detaches from the
            // previous decorView and attaches to this one synchronously,
            // so the panel/bubble is present on every in-app screen.
            attachTo(a);
        }

        @Override
        public void onActivityPaused(@NonNull Activity a) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity a) {
            if (closed) return;
            // The host is gone: detach now; the next resumed Activity
            // re-attaches via onActivityResumed.
            detachFrom(a);
            if (activity == a) activity = null;
        }
    };

    public static void show(Activity activity) {
        RuntimeLogPanel panel = current;
        if (panel != null && !panel.closed) {
            panel.activity = activity;
            panel.bringToFront();
            return;
        }
        panel = new RuntimeLogPanel(activity);
        current = panel;
        panel.open();
    }

    private RuntimeLogPanel(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
    }

    private void bringToFront() {
        attachTo(activity);
        if (closed) return;
        if (state == STATE_BUBBLE) expand();
        else if (state == STATE_EXPANDED) reload(false);
        else open();
    }

    private void open() {
        if (closed || state != STATE_HIDDEN) return;
        registerLifecycle();
        if (container == null) buildContainer();
        panelView.setVisibility(View.VISIBLE);
        resizeView.setVisibility(View.VISIBLE);
        bubble.setVisibility(View.GONE);
        lp.width = expW;
        lp.height = expH;
        lp.leftMargin = expX;
        lp.topMargin = expY;
        clampExpanded();
        state = STATE_EXPANDED;
        attachTo(activity);
        if (closed) return; // attach failed hard and closed the panel
        startPolling();
        reload(false);
    }

    // ------------------------------------------------------------ in-app host

    private void buildContainer() {
        container = (FrameLayout) LayoutInflater.from(activity).inflate(R.layout.overlay_log_panel, null);
        panelView = container.findViewById(R.id.logPanel);
        bubble = container.findViewById(R.id.logBubble);
        // The resize handle is a corner badge on the container itself, not
        // a layout row inside the panel, so it costs no content height.
        resizeView = container.findViewById(R.id.logResize);
        resizeView.setOnTouchListener(new ResizeListener());
        bindViews(panelView);
        bubble.setOnTouchListener(new BubbleTouchListener());
        int dw = decorWidth();
        int dh = decorHeight();
        expW = Math.min(Math.max((int) (dw * 0.92f), dp(280)), dw);
        expH = Math.min(Math.max((int) (dh * 0.55f), dp(200)), dh);
        expX = (dw - expW) / 2;
        expY = (int) (dh * 0.15f);
        lp = new FrameLayout.LayoutParams(expW, expH);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = expX;
        lp.topMargin = expY;
    }

    /**
     * Make the container the topmost child of the given Activity's
     * decorView. Detach-from-old and attach-to-new happen synchronously
     * in this call. Returns false only when there is no usable host yet
     * (the next resumed Activity will retry via the lifecycle callback).
     */
    private boolean attachTo(Activity a) {
        if (container == null || closed) return false;
        if (a == null || a.isFinishing()) return isAttached();
        activity = a;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (container.getParent() == decor) {
            clampCurrent();
            applyLayout();
            container.bringToFront();
            return true;
        }
        detach();
        clampCurrent();
        try {
            decor.addView(container, lp);
        } catch (Exception e) {
            // Never let a host failure crash the app; there is no other
            // form to fall back to, so report and close.
            Notify.show(R.string.log_float_failed);
            close();
            return false;
        }
        container.bringToFront();
        return true;
    }

    private void detach() {
        if (container == null) return;
        ViewParent parent = container.getParent();
        if (parent instanceof ViewGroup) {
            try {
                ((ViewGroup) parent).removeView(container);
            } catch (Exception ignored) {
            }
        }
    }

    private void detachFrom(Activity a) {
        if (container == null || a == null) return;
        if (container.getParent() == a.getWindow().getDecorView()) detach();
    }

    private boolean isAttached() {
        return container != null && container.getParent() != null;
    }

    private void applyLayout() {
        if (container == null || lp == null) return;
        try {
            container.setLayoutParams(lp);
        } catch (Exception ignored) {
        }
    }

    private void toBubble() {
        if (state != STATE_EXPANDED) return;
        expW = lp.width;
        expH = lp.height;
        expX = lp.leftMargin;
        expY = lp.topMargin;
        hideKeyboard();
        panelView.setVisibility(View.GONE);
        resizeView.setVisibility(View.GONE);
        bubble.setVisibility(View.VISIBLE);
        int size = dp(52);
        if (bubX < 0) {
            bubX = decorWidth() - size - dp(12);
            bubY = (int) (decorHeight() * 0.3f);
        }
        lp.width = size;
        lp.height = size;
        lp.leftMargin = bubX;
        lp.topMargin = bubY;
        clampBubble();
        applyLayout();
        state = STATE_BUBBLE;
        stopPolling();
    }

    private void expand() {
        if (state != STATE_BUBBLE) return;
        bubX = lp.leftMargin;
        bubY = lp.topMargin;
        bubble.setVisibility(View.GONE);
        panelView.setVisibility(View.VISIBLE);
        resizeView.setVisibility(View.VISIBLE);
        lp.width = expW;
        lp.height = expH;
        lp.leftMargin = expX;
        lp.topMargin = expY;
        clampExpanded();
        applyLayout();
        state = STATE_EXPANDED;
        startPolling();
        reload(false);
    }

    private void clampCurrent() {
        if (state == STATE_BUBBLE) clampBubble();
        else clampExpanded();
    }

    private void clampExpanded() {
        int dw = decorWidth();
        int dh = decorHeight();
        lp.width = clamp(lp.width, Math.min(dp(280), dw), dw);
        lp.height = clamp(lp.height, Math.min(dp(200), dh), dh);
        lp.leftMargin = clamp(lp.leftMargin, 0, Math.max(0, dw - lp.width));
        lp.topMargin = clamp(lp.topMargin, 0, Math.max(0, dh - lp.height));
    }

    private void clampBubble() {
        int size = dp(52);
        lp.width = size;
        lp.height = size;
        lp.leftMargin = clamp(lp.leftMargin, 0, Math.max(0, decorWidth() - size));
        lp.topMargin = clamp(lp.topMargin, 0, Math.max(0, decorHeight() - size));
    }

    private void close() {
        if (closed) return;
        closed = true;
        stopPolling();
        hideKeyboard();
        detach();
        unregisterLifecycle();
        state = STATE_HIDDEN;
        if (current == this) current = null;
    }

    // ------------------------------------------------------------ view binding

    private void bindViews(View root) {
        header = root.findViewById(R.id.logHeader);
        searchBar = root.findViewById(R.id.logSearchBar);
        searchInput = root.findViewById(R.id.logSearchInput);
        countText = root.findViewById(R.id.logCount);
        emptyText = root.findViewById(R.id.logEmpty);
        listView = root.findViewById(R.id.logList);
        listView.setLayoutManager(new LinearLayoutManager(activity));
        listView.setAdapter(adapter);
        root.findViewById(R.id.logSearch).setOnClickListener(v -> toggleSearch());
        root.findViewById(R.id.logMinimize).setOnClickListener(v -> toBubble());
        root.findViewById(R.id.logClose).setOnClickListener(v -> close());
        root.findViewById(R.id.logRefresh).setOnClickListener(v -> reload(false));
        root.findViewById(R.id.logCopy).setOnClickListener(v -> onCopy());
        root.findViewById(R.id.logClear).setOnClickListener(v -> {
            DebugLogStore.clear();
            reload(true);
        });
        root.findViewById(R.id.logJump).setOnClickListener(v -> onJump());
        root.findViewById(R.id.logExport).setOnClickListener(v -> onExport());
        root.findViewById(R.id.logCopyAddress).setOnClickListener(v -> onCopyAddress());
        // The header drags the panel around the host decorView; the
        // resize handle itself is bound in buildContainer.
        header.setOnTouchListener(new DragListener());
        // Category filter chips (capsule row under the count line). The
        // active category stacks with the keyword filter in applyFilter.
        chips[0] = root.findViewById(R.id.logCatAll);
        chips[1] = root.findViewById(R.id.logCatMedia);
        chips[2] = root.findViewById(R.id.logCatPlay);
        chips[3] = root.findViewById(R.id.logCatAd);
        chips[4] = root.findViewById(R.id.logCatError);
        chips[5] = root.findViewById(R.id.logCatFile);
        chips[6] = root.findViewById(R.id.logCatSpider);
        chips[7] = root.findViewById(R.id.logCatRun);
        for (int i = 0; i < chips.length; i++) {
            final LogCategory value = chipCategories[i];
            final boolean errors = i == ERROR_CHIP;
            chips[i].setOnClickListener(v -> setCategory(value, errors));
        }
        updateChips();
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String value = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                if (!value.equals(keyword)) {
                    keyword = value;
                    applyFilter(true);
                }
            }
        });
    }

    // ------------------------------------------------------------ data

    private void setCategory(LogCategory value, boolean errors) {
        if (category == value && errorsOnly == errors) return;
        category = value;
        errorsOnly = errors;
        updateChips();
        applyFilter(false);
    }

    private void updateChips() {
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] != null) chips[i].setSelected(errorsOnly ? i == ERROR_CHIP : (i != ERROR_CHIP && chipCategories[i] == category));
        }
    }

    private void reload(boolean scrollTop) {
        raw.clear();
        raw.addAll(DebugLogStore.entries());
        lastVersion = DebugLogStore.version();
        applyFilter(scrollTop);
    }

    private void applyFilter(boolean scrollTop) {
        boolean searching = !keyword.isEmpty();
        boolean filtering = searching || category != null || errorsOnly;
        // While a filter is active, polling refreshes must re-apply the
        // current keyword/category (reload does via raw+fields) and must
        // not yank the list to the bottom.
        boolean stick = firstLoad || (!filtering && isAtBottom());
        List<String> filtered = new ArrayList<>();
        for (DebugLogStore.Entry entry : raw) {
            if (category != null && entry.category() != category) continue;
            if (errorsOnly && !LogCategory.isError(entry.line())) continue;
            if (searching && !entry.line().toLowerCase(Locale.ROOT).contains(keyword)) continue;
            filtered.add(entry.line());
        }
        adapter.setItems(filtered);
        adapter.setHighlight(keyword);
        if (filtering) countText.setText(appContext.getString(R.string.log_count_search, filtered.size(), raw.size()));
        else countText.setText(appContext.getString(R.string.log_count, filtered.size()));
        boolean empty = filtered.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) emptyText.setText(DebugLogStore.isEnabled() ? R.string.log_empty : R.string.log_disabled_hint);
        if (!empty) {
            if (scrollTop) listView.scrollToPosition(0);
            else if (stick) listView.scrollToPosition(filtered.size() - 1);
        }
        firstLoad = false;
    }

    private boolean isAtBottom() {
        LinearLayoutManager lm = (LinearLayoutManager) listView.getLayoutManager();
        if (lm == null || adapter.getItemCount() == 0) return true;
        return lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 2;
    }

    private String fullText() {
        if (!raw.isEmpty()) {
            // Stored lines already carry their "[分类]" marker, so the
            // exported/copied text shows the same categories as the panel.
            List<String> lines = new ArrayList<>(raw.size());
            for (DebugLogStore.Entry entry : raw) lines.add(entry.line());
            return TextUtils.join("\n", lines);
        }
        return DebugLogStore.text();
    }

    private static String logUrl() {
        int port = Proxy.getPort();
        if (port <= 0) port = 9978;
        return "http://127.0.0.1:" + port + "/debug/logs";
    }

    // ------------------------------------------------------------ actions

    private void toggleSearch() {
        boolean show = searchBar.getVisibility() != View.VISIBLE;
        searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            // Post the focus request: the search bar has just become visible
            // and the host may not be input-ready in the same frame, which
            // made the keyboard / typing unreliable.
            searchInput.post(() -> {
                if (closed) return;
                searchInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) appContext.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            });
        } else {
            searchInput.setText("");
            keyword = "";
            applyFilter(true);
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) appContext.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && searchInput != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private void onCopy() {
        ClipboardManager cm = (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("musetv-log", fullText()));
        Notify.show(R.string.log_copied);
    }

    private void onCopyAddress() {
        ClipboardManager cm = (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("musetv-log-url", logUrl()));
        Notify.show(R.string.log_address_copied);
    }

    private void onJump() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(logUrl()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Exception e) {
            Notify.show(R.string.log_no_browser);
        }
    }

    private void onExport() {
        String text = fullText();
        new Thread(() -> {
            try {
                // 导出到设备的 Download 文件夹（旧位置是应用私有目录，不好找）。
                File dir = null;
                if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                }
                if (dir == null) {
                    File base = appContext.getExternalFilesDir(null);
                    dir = base != null ? new File(base, "logs") : new File(appContext.getFilesDir(), "logs");
                }
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs failed");
                String name = "MuseTV-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
                File out = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
                handler.post(() -> Notify.show(appContext.getString(R.string.log_exported, out.getAbsolutePath())));
            } catch (Exception e) {
                handler.post(() -> Notify.show(R.string.log_export_failed));
            }
        }).start();
    }

    // ------------------------------------------------------------ lifecycle plumbing

    private void registerLifecycle() {
        if (lifecycleRegistered) return;
        lifecycleRegistered = true;
        ((Application) appContext).registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    private void unregisterLifecycle() {
        if (!lifecycleRegistered) return;
        lifecycleRegistered = false;
        try {
            ((Application) appContext).unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
        } catch (Exception ignored) {
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.postDelayed(tick, 1000);
    }

    private void stopPolling() {
        polling = false;
        handler.removeCallbacks(tick);
    }

    // ------------------------------------------------------------ touch: bubble / drag / resize

    private class BubbleTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (closed || state != STATE_BUBBLE) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.leftMargin;
                    startY = lp.topMargin;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                    lp.leftMargin = clamp(startX + dx, 0, Math.max(0, decorWidth() - lp.width));
                    lp.topMargin = clamp(startY + dy, 0, Math.max(0, decorHeight() - lp.height));
                    applyLayout();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    bubX = lp.leftMargin;
                    bubY = lp.topMargin;
                    if (!moved && event.getAction() == MotionEvent.ACTION_UP) expand();
                    return true;
            }
            return false;
        }
    }

    private class DragListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (closed || state != STATE_EXPANDED) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.leftMargin;
                    startY = lp.topMargin;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    // Consume so the MOVE stream stays with us; header buttons
                    // are children and still receive their own touches first.
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // Keep the whole panel inside the decorView so it (and
                    // its resize handle) can always be grabbed again.
                    lp.leftMargin = clamp(startX + (int) (event.getRawX() - downX), 0, Math.max(0, decorWidth() - lp.width));
                    lp.topMargin = clamp(startY + (int) (event.getRawY() - downY), 0, Math.max(0, decorHeight() - lp.height));
                    applyLayout();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    expX = lp.leftMargin;
                    expY = lp.topMargin;
                    return true;
            }
            return false;
        }
    }

    private class ResizeListener implements View.OnTouchListener {
        private float downX, downY;
        private int startW, startH;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (closed || state != STATE_EXPANDED) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startW = lp.width;
                    startH = lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // The handle sits at the panel's bottom-right corner, so
                    // never grow past the decorView edge or it becomes
                    // unreachable; only the LayoutParams change, no other
                    // view is resized from under the layout pass.
                    int maxW = Math.max(1, decorWidth() - lp.leftMargin);
                    int maxH = Math.max(1, decorHeight() - lp.topMargin);
                    lp.width = clamp(startW + (int) (event.getRawX() - downX), Math.min(dp(280), maxW), maxW);
                    lp.height = clamp(startH + (int) (event.getRawY() - downY), Math.min(dp(200), maxH), maxH);
                    applyLayout();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    expW = lp.width;
                    expH = lp.height;
                    return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------ helpers

    private int dp(int value) {
        return (int) (value * appContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private int decorWidth() {
        if (activity != null) {
            int w = activity.getWindow().getDecorView().getWidth();
            if (w > 0) return w;
        }
        return appContext.getResources().getDisplayMetrics().widthPixels;
    }

    private int decorHeight() {
        if (activity != null) {
            int h = activity.getWindow().getDecorView().getHeight();
            if (h > 0) return h;
        }
        return appContext.getResources().getDisplayMetrics().heightPixels;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------ adapter

    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {

        private List<String> items = new ArrayList<>();
        private String highlight = "";

        void setItems(List<String> data) {
            items = data;
            notifyDataSetChanged();
        }

        void setHighlight(String value) {
            if (!value.equals(highlight)) {
                highlight = value;
                notifyDataSetChanged();
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setTextSize(11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(dp(16), dp(3), dp(16), dp(3));
            tv.setTextColor(0xFF333333);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String line = items.get(position);
            if (highlight.isEmpty()) {
                holder.text.setText(line);
                return;
            }
            SpannableString spannable = new SpannableString(line);
            String lower = line.toLowerCase(Locale.ROOT);
            int from = 0;
            int index;
            while ((index = lower.indexOf(highlight, from)) >= 0) {
                spannable.setSpan(new BackgroundColorSpan(0xFFFFEB3B), index, index + highlight.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = index + highlight.length();
            }
            holder.text.setText(spannable);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(View itemView) {
                super(itemView);
                text = (TextView) itemView;
            }
        }
    }
}

