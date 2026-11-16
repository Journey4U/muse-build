package com.muse.android.tv.ui.novel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
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
 * 「左右」翻页模式的真卷页视图（#58，Liu 拍板要真卷页仿真，不用横向滑动凑数）。
 *
 * 模型（折线折叠 / perpendicular-bisector fold）：
 * 当前页是一张整页位图，手指抓住它靠近的一角（左角=上一页方向，右角=下一页方向）拖动；
 * 折线 = 该角的「原位」与手指位置的垂直平分线。折线靠角一侧的页面区域被掀起，
 * 绕折线镜像翻到另一侧，露出其背面（正文镜像 + 纸色罩 + 渐变阴影），折线另一侧仍是平整的当前页，
 * 折线扫过的区域下方露出目标页（下一页 / 上一页）。松手过半 → 卷点动画扫出屏幕外并提交翻页；
 * 不过半 → 卷点动画弹回角原位。按钮翻页 = 同一动画由程序驱动卷点自角边扫向对侧。
 *
 * 手势（#87，Liu 定规；与平移视图 PageSlideView 同一套）：按下先不抓页，位移超过 4dp
 * 才按滑动方向锁定翻页方向（横向主导才翻页：左滑下一页、右滑上一页，任意位置起始；
 * 竖直主导本次不翻页），到头方向不抓页；起始点落在左右边缘带（系统返回/退出手势区，
 * 宽度读系统 gesture insets、兜底 24dp）的手势全程不归本视图（不抓页、不发拖动回调）。
 *
 * 分页：整章文本（含章标题 span）用与滚动模式完全相同的画笔/行距建一份 StaticLayout，
 * 按可视高度逐行切页；每页按需渲染成整屏位图（小缓存），绘制时按页顶偏移裁切绘制。
 * 零第三方依赖，只用 Canvas/Bitmap/Path/Matrix 平台 API。
 */
public class PageCurlView extends View {

    public interface Listener {
        /** 当前页变化（翻页提交 / 跳转 / 重新分页后回调用），Activity 据此同步进度条。 */
        void onPageChanged(int pageIndex, int pageCount);

        /** 用户开始触摸正文（#65 起仅作通知：触碰正文不再停自动阅读，停自动只靠「自动阅读」按钮）。 */
        void onUserTouch();

        /** 用户点按正文（每下抬起时回调，带按下点坐标）：单击切换/双击合并由 Activity 统一判定，点按不中断自动阅读。 */
        void onTap(float x, float y);

        /** 用户拖动卷页、本次手势位移首次超过 4dp 时回调（每手势一次）：Activity 据此隐藏功能框（#85；#86 阈值 8dp→4dp：拇指横向滑动行程短于纵向）。 */
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

    // ===== 卷页状态 =====
    private boolean curling;          // 正在拖动或动画中（需要按卷页模型绘制）
    private boolean curlForward = true;
    private boolean touchActive;      // 手指正按下中（拖动中途的重分页不得打断手势）
    private VelocityTracker velocityTracker;
    private float cornerX, cornerY;   // 被抓一角的原位
    private float touchX, touchY;     // 卷点（手指 / 动画驱动点）
    private ValueAnimator animator;
    private float downX, downY;
    private long downTime;
    private boolean edgeStart;        // 本次手势起始点落在左右边缘带（系统退出手势区）：全程不归阅读器
    private int dirLock;              // 方向锁定：0=未锁定，1=下一页，-1=上一页，2=竖直主导（本次不翻页）
    private boolean moved;
    private float density = 1f;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final Matrix curlMatrix = new Matrix();

    public PageCurlView(Context context) {
        super(context);
        init(context);
    }

    public PageCurlView(Context context, AttributeSet attrs) {
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

    /** 与滚动模式 ScrollView 的 padding 保持一致，保证两模式页边距观感相同。 */
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
        curling = false;
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
        // 保留卷曲跟随状态，touchX/touchY 由后续 MOVE 继续驱动；无触摸时照常复位。
        if (!touchActive) curling = false;
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

    // ===== 分页 =====

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

    // ===== 绘制 =====

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
        double cornerDist = Math.hypot(touchX - cornerX, touchY - cornerY);
        if (!curling || cornerDist < 2) {
            canvas.drawBitmap(cur, 0, 0, bitmapPaint);
            return;
        }
        int targetIndex = curlForward ? current + 1 : current - 1;
        Bitmap base = pageBitmap(targetIndex);
        if (base == null) {
            canvas.drawBitmap(cur, 0, 0, bitmapPaint);
            return;
        }
        float w = getWidth();
        float h = getHeight();
        // 折线：角原位 c 与卷点 a 的垂直平分线；n 为 c→a 的单位法向，m 为中点
        float nx = touchX - cornerX;
        float ny = touchY - cornerY;
        float len = (float) Math.hypot(nx, ny);
        nx /= len;
        ny /= len;
        float mx = (cornerX + touchX) / 2f;
        float my = (cornerY + touchY) / 2f;
        float[][] rect = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        List<float[]> polyCorner = clipHalfPlane(rect, mx, my, nx, ny, true);
        List<float[]> polyFlat = clipHalfPlane(rect, mx, my, nx, ny, false);
        if (polyCorner.size() < 3 || polyFlat.size() < 3) {
            canvas.drawBitmap(cur, 0, 0, bitmapPaint);
            return;
        }
        // 1) 底层：目标页（折线扫过后将露出的页）
        canvas.drawBitmap(base, 0, 0, bitmapPaint);
        // 2) 当前页未掀起的部分（折线平整侧）
        canvas.save();
        setPath(clipPath, polyFlat);
        canvas.clipPath(clipPath);
        canvas.drawBitmap(cur, 0, 0, bitmapPaint);
        canvas.restore();
        // 折线段端点（polyFlat 与 polyCorner 的公共边）
        float[] s1 = null, s2 = null;
        for (float[] p : polyFlat) {
            if (Math.abs((p[0] - mx) * nx + (p[1] - my) * ny) < 1.0f) {
                if (s1 == null) s1 = p;
                else s2 = p;
            }
        }
        // 3) 掀起的页背面：角侧区域绕折线镜像，画当前页位图的镜像 + 纸色罩 + 渐变
        List<float[]> polyFlap = new ArrayList<>();
        for (float[] p : polyCorner) polyFlap.add(reflect(p, mx, my, nx, ny));
        canvas.save();
        setPath(clipPath, polyFlap);
        canvas.clipPath(clipPath);
        buildReflectionMatrix(mx, my, nx, ny);
        canvas.drawBitmap(cur, curlMatrix, bitmapPaint);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(null);
        fillPaint.setColor(bgColor);
        fillPaint.setAlpha(150);
        canvas.drawRect(0, 0, w, h, fillPaint);
        float shadeDist = Math.max(1f, len);
        LinearGradient shade = new LinearGradient(mx, my, mx + nx * shadeDist, my + ny * shadeDist,
                0x00000000, 0x2E000000, Shader.TileMode.CLAMP);
        fillPaint.setShader(shade);
        fillPaint.setAlpha(255);
        canvas.drawRect(0, 0, w, h, fillPaint);
        fillPaint.setShader(null);
        canvas.restore();
        // 4) 折线在平整侧投下的柔影
        if (s1 != null && s2 != null) {
            float sw = 28f * density;
            Path shadowPath = new Path();
            shadowPath.moveTo(s1[0], s1[1]);
            shadowPath.lineTo(s2[0], s2[1]);
            shadowPath.lineTo(s2[0] + nx * sw, s2[1] + ny * sw);
            shadowPath.lineTo(s1[0] + nx * sw, s1[1] + ny * sw);
            shadowPath.close();
            canvas.save();
            setPath(clipPath, polyFlat);
            canvas.clipPath(clipPath);
            LinearGradient gradient = new LinearGradient(mx, my, mx + nx * sw, my + ny * sw,
                    0x47000000, 0x00000000, Shader.TileMode.CLAMP);
            fillPaint.setShader(gradient);
            fillPaint.setAlpha(255);
            canvas.drawPath(shadowPath, fillPaint);
            fillPaint.setShader(null);
            canvas.restore();
        }
    }

    /** Sutherland–Hodgman：用折线半平面裁剪矩形。keepCorner=true 保留角一侧（点积 ≤ 0）。 */
    private List<float[]> clipHalfPlane(float[][] rect, float mx, float my, float nx, float ny, boolean keepCorner) {
        List<float[]> out = new ArrayList<>();
        int count = rect.length;
        for (int i = 0; i < count; i++) {
            float[] curP = rect[i];
            float[] nextP = rect[(i + 1) % count];
            float dCur = (curP[0] - mx) * nx + (curP[1] - my) * ny;
            float dNext = (nextP[0] - mx) * nx + (nextP[1] - my) * ny;
            boolean inCur = keepCorner ? dCur <= 0 : dCur >= 0;
            boolean inNext = keepCorner ? dNext <= 0 : dNext >= 0;
            if (inCur) out.add(curP);
            if (inCur != inNext) {
                float t = dCur / (dCur - dNext);
                out.add(new float[]{curP[0] + (nextP[0] - curP[0]) * t, curP[1] + (nextP[1] - curP[1]) * t});
            }
        }
        return out;
    }

    private float[] reflect(float[] p, float mx, float my, float nx, float ny) {
        float dx = p[0] - mx;
        float dy = p[1] - my;
        float dot = dx * nx + dy * ny;
        return new float[]{p[0] - 2 * dot * nx, p[1] - 2 * dot * ny};
    }

    /** 绕折线的镜像矩阵：d 为折线方向（n 的垂向），R = 2ddᵀ − I。 */
    private void buildReflectionMatrix(float mx, float my, float nx, float ny) {
        float dx = -ny;
        float dy = nx;
        float r00 = 2 * dx * dx - 1;
        float r01 = 2 * dx * dy;
        float r10 = 2 * dx * dy;
        float r11 = 2 * dy * dy - 1;
        curlMatrix.setValues(new float[]{
                r00, r01, mx - r00 * mx - r01 * my,
                r10, r11, my - r10 * mx - r11 * my,
                0, 0, 1});
    }

    private void setPath(Path path, List<float[]> poly) {
        path.reset();
        if (poly.isEmpty()) return;
        path.moveTo(poly.get(0)[0], poly.get(0)[1]);
        for (int i = 1; i < poly.size(); i++) path.lineTo(poly.get(i)[0], poly.get(i)[1]);
        path.close();
    }

    // ===== 翻页动画 =====

    /** 按钮/自动阅读驱动：下一页（卷点自右角扫向左侧屏幕外）。 */
    public boolean turnNextAnimated() {
        if (animator != null && animator.isRunning()) return false;
        if (pages.isEmpty() || current >= pages.size() - 1) return false;
        beginCurl(true, getWidth() - 2, getHeight() - 2);
        animateCurlTo(-getWidth() * 1.05f, getHeight() * 0.8f, true, current + 1);
        return true;
    }

    /** 按钮/自动阅读驱动：上一页（卷点自左角扫向右侧屏幕外）。 */
    public boolean turnPrevAnimated() {
        if (animator != null && animator.isRunning()) return false;
        if (pages.isEmpty() || current <= 0) return false;
        beginCurl(false, 2, getHeight() - 2);
        animateCurlTo(getWidth() * 2.05f, getHeight() * 0.8f, true, current - 1);
        return true;
    }

    private void beginCurl(boolean forward, float startX, float startY) {
        curlForward = forward;
        cornerX = forward ? getWidth() : 0;
        cornerY = startY >= getHeight() / 2f ? getHeight() : 0;
        touchX = startX;
        touchY = startY;
        curling = true;
    }

    private void animateCurlTo(float toX, float toY, boolean commit, int targetPage) {
        cancelAnimation();
        final float fromX = touchX;
        final float fromY = touchY;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(320);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            touchX = fromX + (toX - fromX) * f;
            touchY = fromY + (toY - fromY) * f;
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                curling = false;
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

    // ===== 触摸：方向按滑动锁定（任意位置起滑） + 边缘排除 + 双击呼出功能框 =====

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
        float h = getHeight();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cancelAnimation();
                curling = false;
                moved = false;
                dirLock = 0;
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                touchActive = true;
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                // 边缘排除：起始点落在左右边缘带的，本次手势全程不归阅读器——
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
                        if (dirLock == 1 && current < pages.size() - 1) beginCurl(true, downX, downY);
                        else if (dirLock == -1 && current > 0) beginCurl(false, downX, downY);
                    } else {
                        dirLock = 2;
                    }
                }
                if (!curling) return true;
                touchX = clamp(event.getX(), -w * 0.3f, w * 1.3f);
                touchY = clamp(event.getY(), -h * 0.3f, h * 1.3f);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (velocityTracker != null) velocityTracker.addMovement(event);
                if (curling && moved) {
                    // 提交判定（#89 与平移翻页 #88 参数对齐）：从按下点位移过四分之一屏宽
                    // 即翻页；或甩动速度达标（与卷曲方向一致）也翻页——短行程快甩同样生效。
                    boolean fling = false;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        float vx = velocityTracker.getXVelocity();
                        float minFling = 400 * density;
                        fling = curlForward && vx < -minFling || !curlForward && vx > minFling;
                    }
                    boolean commit = (curlForward ? touchX - downX < -w * 0.25f : touchX - downX > w * 0.25f) || fling;
                    if (commit) {
                        float toX = curlForward ? -w * 1.05f : w * 2.05f;
                        animateCurlTo(toX, touchY, true, curlForward ? current + 1 : current - 1);
                    } else {
                        animateCurlTo(cornerX, cornerY, false, current);
                    }
                } else if (!moved && System.currentTimeMillis() - downTime < 400) {
                    // 点按（#65 定案：屏幕单击不翻页，翻页只在功能键上）：新手势下点按从不曾
                    // 抓页，无需回弹；每下都上报，单击切换/双击合并由 Activity 的 onBodyTap 统一判定
                    if (listener != null) listener.onTap(downX, downY);
                }
                edgeStart = false;
                touchActive = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (curling) animateCurlTo(cornerX, cornerY, false, current);
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
