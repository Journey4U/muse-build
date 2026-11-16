package com.muse.android.tv.ui.novel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「平移」翻页模式的整页平移视图（#87 新增，Liu 定规：与仿真卷页并列的第三种翻页模式）。
 *
 * 模型：整页是刚性平面，拖动时当前页位图跟随手指水平平移，相邻页位图并排滑入；
 * 松手滑过半屏 → 平移动画滑出整屏并提交翻页，未过半 → 回弹归位。
 * 按钮/自动阅读翻页 = 同一平移动画由程序驱动。
 *
 * 交互与 {@link PageCurlView} 完全对齐（同一套手势规矩）：
 * 按下先不抓页，位移超过 4dp 才按滑动方向锁定翻页方向（横向主导才翻页：左滑下一页、
 * 右滑上一页，任意位置起始；竖直主导本次不翻页），到头方向不抓页；位移超 4dp 即回调
 * onUserDrag（Activity 据此藏功能框）；点按不翻页，双击只呼出功能框；
 * 起始点落在左右边缘带（系统返回/退出手势区）的手势全程不归本视图。
 *
 * 分页与位图缓存与 PageCurlView 同一套：整章文本用与滚动模式相同的画笔/行距建
 * StaticLayout，按可视高度逐行切页，每页按需渲染成整屏位图（小缓存）。
 * 零第三方依赖，只用 Canvas/Bitmap 平台 API。
 */
public class PageSlideView extends View {

    public interface Listener {
        /** 当前页变化（翻页提交 / 跳转 / 重新分页后回调），Activity 据此同步进度条。 */
        void onPageChanged(int pageIndex, int pageCount);

        /** 用户开始触摸正文（仅作通知：触碰正文不停自动阅读，停自动只靠「自动阅读」按钮）。 */
        void onUserTouch();

        /** 用户点按正文（每下抬起时回调，带按下点坐标）：单击切换/双击合并由 Activity 统一判定，点按不中断自动阅读。 */
        void onTap(float x, float y);

        /** 用户拖动翻页、本次手势位移首次超过 4dp 时回调（每手势一次）：Activity 据此隐藏功能框。 */
        void onUserDrag();
    }

    private static class Page {
        int start; // 本页起始字符偏移
        int end;   // 本页结束字符偏移（不含）
        int top;   // 本页首行在整版布局中的 y
    }

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private float lineMult = 1.0f;
    private float lineAdd = 0f;
    private CharSequence text;
    private StaticLayout layout;
    private int hlStart = -1;
    private int hlEnd = -1;
    private int hlColor;
    private final Paint hlPaint = new Paint();
    private final List<Page> pages = new ArrayList<>();
    private int current;
    private int bgColor = Color.WHITE;
    private int insetL, insetT, insetR, insetB;
    private Listener listener;

    private final Map<Integer, Bitmap> bitmapCache = new HashMap<>();

    // ===== 平移状态 =====
    private float slideOffset;        // 当前页绘制 x 偏移（负=正滑向下一页，正=正滑向上一页）
    private boolean sliding;          // 本次手势已抓页跟随中
    private boolean touchActive;      // 手指正按下中（拖动中途的重分页不得打断手势）
    private VelocityTracker velocityTracker;
    private ValueAnimator animator;
    private float downX, downY;
    private long downTime;
    private boolean edgeStart;        // 本次手势起始点落在左右边缘带：全程不归阅读器
    private int dirLock;              // 方向锁定：0=未锁定，1=下一页，-1=上一页，2=竖直主导（本次不翻页）
    private boolean moved;
    private float density = 1f;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public PageSlideView(Context context) {
        super(context);
        init(context);
    }

    public PageSlideView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPageBackground(int color) {
        if (bgColor == color) return;
        bgColor = color;
        clearBitmaps();
        invalidate();
    }

    /** 与滚动模式 ScrollView 的 padding 保持一致，保证各模式页边距观感相同。 */
    public void setInsets(int l, int t, int r, int b) {
        if (insetL == l && insetT == t && insetR == r && insetB == b) return;
        insetL = l;
        insetT = t;
        insetR = r;
        insetB = b;
        repaginate();
    }

    /**
     * 设置整章文本并重新分页。
     *
     * @param sourcePaint 正文 TextView 的画笔（字号 px / 字体 / 字色 / 字间距与滚动模式一致）
     */
    public void setContent(CharSequence content, TextPaint sourcePaint, float spacingMult, float spacingAdd) {
        cancelAnimation();
        sliding = false;
        slideOffset = 0;
        text = content;
        hlStart = -1;
        hlEnd = -1;
        paint.set(sourcePaint);
        paint.setLetterSpacing(sourcePaint.getLetterSpacing());
        paint.setAntiAlias(true);
        lineMult = spacingMult;
        lineAdd = spacingAdd;
        current = 0;
        buildPages();
        notifyPageChanged();
        invalidate();
    }

    /** 按当前字符位置重新分页（字号/行距/边距/尺寸变化时调用）。 */
    public void repaginate() {
        int keep = getCurrentCharOffset();
        cancelAnimation();
        // 拖动中途的重分页（如藏功能框引发布局变高触发 onSizeChanged）不得打断手势：
        // 保留跟随状态，slideOffset 由后续 MOVE 按 dx 继续驱动；无触摸时照常复位。
        if (!touchActive) {
            sliding = false;
            slideOffset = 0;
        }
        buildPages();
        gotoCharOffsetInternal(keep);
        invalidate();
    }

    public int getPageCount() {
        return pages.size();
    }

    public int getCurrentPage() {
        return current;
    }

    public int getTotalChars() {
        return text == null ? 0 : text.length();
    }

    public int getCurrentCharOffset() {
        if (pages.isEmpty()) return 0;
        return pages.get(Math.max(0, Math.min(current, pages.size() - 1))).start;
    }

    public void gotoCharOffset(int offset) {
        gotoCharOffsetInternal(offset);
        invalidate();
    }

    private void gotoCharOffsetInternal(int offset) {
        if (pages.isEmpty()) return;
        int target = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).start <= offset) target = i;
            else break;
        }
        if (target != current) {
            current = target;
            notifyPageChanged();
        }
    }

    // ===== 分页（与 PageCurlView 同一套，模式间页界严格一致） =====

    private void buildPages() {
        pages.clear();
        clearBitmaps();
        layout = null;
        if (text == null || text.length() == 0 || getWidth() <= 0 || getHeight() <= 0) return;
        int w = Math.max(1, getWidth() - insetL - insetR);
        int h = Math.max(1, getHeight() - insetT - insetB);
        layout = new StaticLayout(text, paint, w, Layout.Alignment.ALIGN_NORMAL, lineMult, lineAdd, true);
        int lines = layout.getLineCount();
        if (lines <= 0) {
            Page only = new Page();
            only.start = 0;
            only.end = text.length();
            only.top = 0;
            pages.add(only);
            return;
        }
        int pageStartLine = 0;
        int pageTop = layout.getLineTop(0);
        for (int i = 1; i < lines; i++) {
            // 第 i 行底边超出页高、且本页已至少有一行时，在第 i 行前断页
            if (layout.getLineBottom(i) - pageTop > h && i > pageStartLine) {
                Page page = new Page();
                page.start = layout.getLineStart(pageStartLine);
                page.end = layout.getLineStart(i);
                page.top = pageTop;
                pages.add(page);
                pageStartLine = i;
                pageTop = layout.getLineTop(i);
            }
        }
        Page last = new Page();
        last.start = layout.getLineStart(pageStartLine);
        last.end = text.length();
        last.top = pageTop;
        pages.add(last);
        if (current >= pages.size()) current = pages.size() - 1;
        if (current < 0) current = 0;
    }

    private void clearBitmaps() {
        for (Bitmap bitmap : bitmapCache.values()) bitmap.recycle();
        bitmapCache.clear();
    }

    /** 朗读段高亮：在与正文相同的坐标变换里、画正文之前，按行垫底色。 */
    private void drawHighlight(Canvas canvas) {
        if (layout == null || layout.getText() == null || hlStart < 0 || hlEnd <= hlStart || hlColor == 0) return;
        int max = layout.getText().length();
        if (max == 0) return;
        int ls = layout.getLineForOffset(Math.min(hlStart, max - 1));
        int le = layout.getLineForOffset(Math.min(hlEnd - 1, max - 1));
        hlPaint.setColor(hlColor);
        for (int li = ls; li <= le; li++) {
            canvas.drawRect(layout.getLineLeft(li), layout.getLineTop(li), layout.getLineRight(li), layout.getLineBottom(li), hlPaint);
        }
    }

    /** 设置朗读高亮范围（正文坐标，含章名偏移）；start<0 清除。范围变化时整页位图重画。 */
    public void setTtsHighlight(int start, int end, int color) {
        if (start == hlStart && end == hlEnd && color == hlColor) return;
        hlStart = start;
        hlEnd = end;
        hlColor = color;
        clearBitmaps();
        invalidate();
    }

    private Bitmap pageBitmap(int index) {
        if (index < 0 || index >= pages.size() || getWidth() <= 0 || getHeight() <= 0) return null;
        Bitmap cached = bitmapCache.get(index);
        if (cached != null) return cached;
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(bgColor);
        if (layout != null) {
            Page page = pages.get(index);
            canvas.save();
            canvas.clipRect(insetL, insetT, getWidth() - insetR, getHeight() - insetB);
            canvas.translate(insetL, insetT - page.top);
            drawHighlight(canvas);
            layout.draw(canvas);
            canvas.restore();
        }
        if (bitmapCache.size() >= 7) {
            // 缓存超限：回收离当前页最远的一张
            int farthest = -1;
            int farthestDist = -1;
            for (Integer key : bitmapCache.keySet()) {
                int dist = Math.abs(key - current);
                if (dist > farthestDist) {
                    farthestDist = dist;
                    farthest = key;
                }
            }
            if (farthest >= 0) bitmapCache.remove(farthest).recycle();
        }
        bitmapCache.put(index, bitmap);
        return bitmap;
    }

    // ===== 绘制：当前页按 slideOffset 平移，相邻页并排 =====

    @Override
    protected void onDraw(Canvas canvas) {
        if (pages.isEmpty() || getWidth() <= 0) {
            canvas.drawColor(bgColor);
            return;
        }
        Bitmap cur = pageBitmap(current);
        if (cur == null) {
            canvas.drawColor(bgColor);
            return;
        }
        float w = getWidth();
        if (slideOffset < 0) {
            Bitmap next = pageBitmap(current + 1);
            if (next != null) canvas.drawBitmap(next, slideOffset + w, 0, bitmapPaint);
            canvas.drawBitmap(cur, slideOffset, 0, bitmapPaint);
        } else if (slideOffset > 0) {
            Bitmap prev = pageBitmap(current - 1);
            if (prev != null) canvas.drawBitmap(prev, slideOffset - w, 0, bitmapPaint);
            canvas.drawBitmap(cur, slideOffset, 0, bitmapPaint);
        } else {
            canvas.drawBitmap(cur, 0, 0, bitmapPaint);
        }
    }

    // ===== 翻页动画 =====

    /** 按钮/自动阅读驱动：下一页（整页自右向左平移滑出）。 */
    public boolean turnNextAnimated() {
        if (animator != null && animator.isRunning()) return false;
        if (pages.isEmpty() || current >= pages.size() - 1) return false;
        slideOffset = 0;
        animateSlideTo(-getWidth(), true, current + 1);
        return true;
    }

    /** 按钮/自动阅读驱动：上一页（整页自左向右平移滑出）。 */
    public boolean turnPrevAnimated() {
        if (animator != null && animator.isRunning()) return false;
        if (pages.isEmpty() || current <= 0) return false;
        slideOffset = 0;
        animateSlideTo(getWidth(), true, current - 1);
        return true;
    }

    private void animateSlideTo(float to, boolean commit, int targetPage) {
        cancelAnimation();
        final float from = slideOffset;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(320);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            slideOffset = from + (to - from) * f;
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                slideOffset = 0;
                if (commit && targetPage >= 0 && targetPage < pages.size()) {
                    current = targetPage;
                    notifyPageChanged();
                }
                invalidate();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                animator = null;
            }
        });
        animator.start();
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void notifyPageChanged() {
        if (listener != null) listener.onPageChanged(current, pages.size());
    }

    // ===== 触摸：与 PageCurlView 同一套手势规矩（方向按滑动锁定 + 边缘排除 + 双击呼出） =====

    /** 左右边缘排除带宽度（px）：系统手势区实际宽度（WindowInsets.systemGestureInsets），读不到兜底 24dp，并钳制不超过屏宽四分之一。 */
    private float edgeExcludePx() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.view.WindowInsets insets = getRootWindowInsets();
            if (insets != null) {
                android.graphics.Insets g = insets.getSystemGestureInsets();
                int edge = Math.max(g.left, g.right);
                if (edge > 0) return Math.min(edge, getWidth() / 4f);
            }
        }
        return 24 * density;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (pages.isEmpty()) return false;
        float w = getWidth();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cancelAnimation();
                sliding = false;
                slideOffset = 0;
                moved = false;
                dirLock = 0;
                touchActive = true;
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                // 边缘排除（与卷页一致）：起始点落在左右边缘带的，本次手势全程不归阅读器——
                // 不抓页、不发拖动回调（不藏框），整个让给系统返回/退出手势，避免冲突导致退不出。
                float edge = edgeExcludePx();
                edgeStart = downX < edge || downX > w - edge;
                if (!edgeStart && listener != null) listener.onUserTouch();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (edgeStart) return true;
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!moved && Math.hypot(dx, dy) > 4 * density) {
                    moved = true;
                    if (listener != null) listener.onUserDrag();
                    // 方向按滑动方向锁定、不按按下位置（桌面式）：横向主导才定翻页方向，
                    // 竖直主导本次不翻页；目标方向到头（第一页右滑 / 最后一页左滑）不抓页。
                    if (Math.abs(dx) >= Math.abs(dy)) {
                        dirLock = dx < 0 ? 1 : -1;
                        if (dirLock == 1 && current < pages.size() - 1) sliding = true;
                        else if (dirLock == -1 && current > 0) sliding = true;
                    } else {
                        dirLock = 2;
                    }
                }
                if (!sliding) return true;
                // 跟随手指平移：只在已锁定方向一侧活动（下一页方向 offset∈[-w,0]，上一页方向∈[0,w]）
                slideOffset = dirLock == 1 ? clamp(dx, -w, 0) : clamp(dx, 0, w);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (velocityTracker != null) velocityTracker.addMovement(event);
                if (sliding && moved) {
                    // 提交判定（#88 灵敏度修复）：平移过四分之一屏宽即翻页；或甩动速度
                    // 达标（与锁定方向一致）也翻页——短行程快甩同样生效，与桌面手感一致。
                    boolean fling = false;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        float vx = velocityTracker.getXVelocity();
                        float minFling = 400 * density;
                        fling = dirLock == 1 && vx < -minFling || dirLock == -1 && vx > minFling;
                    }
                    if (Math.abs(slideOffset) > w * 0.25f || fling) {
                        animateSlideTo(dirLock == 1 ? -w : w, true, dirLock == 1 ? current + 1 : current - 1);
                    } else {
                        animateSlideTo(0, false, current);
                    }
                } else if (!moved && System.currentTimeMillis() - downTime < 400) {
                    // 点按（与卷页一致：屏幕单击不翻页）：每下都上报，单击切换/双击合并由 Activity 的 onBodyTap 统一判定
                    if (listener != null) listener.onTap(downX, downY);
                }
                sliding = false;
                edgeStart = false;
                touchActive = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (sliding) animateSlideTo(0, false, current);
                sliding = false;
                edgeStart = false;
                touchActive = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                return true;
            }
            default:
                return true;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if ((w != oldw || h != oldh) && text != null) repaginate();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimation();
        clearBitmaps();
        super.onDetachedFromWindow();
    }
}
