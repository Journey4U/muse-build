package com.muse.android.tv.ui.custom;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.android.tv.utils.ResUtil;

public class SpaceItemDecoration extends RecyclerView.ItemDecoration {

    private final int spacing;
    private final int spanCount;
    private final int verticalSpacing;

    public SpaceItemDecoration(int spacing) {
        this(-1, spacing);
    }

    public SpaceItemDecoration(int spanCount, int spacing) {
        this(spanCount, spacing, -1);
    }

    /** verticalSpacing >= 0 时，行间距单独用它（dp），横向间距仍用 spacing；旧构造默认 -1 = 与 spacing 相同。 */
    public SpaceItemDecoration(int spanCount, int spacing, int verticalSpacing) {
        this.spanCount = spanCount;
        this.spacing = ResUtil.dp2px(spacing);
        this.verticalSpacing = verticalSpacing < 0 ? -1 : ResUtil.dp2px(verticalSpacing);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position >= 0 && spanCount == -1) {
            outRect.left = position == 0 ? 0 : spacing / 2;
            outRect.right = position == parent.getAdapter().getItemCount() - 1 ? 0 : spacing / 2;
        } else if (position >= 0 && spanCount > 0) {
            int column = position % spanCount;
            outRect.left = column * spacing / spanCount;
            outRect.right = spacing - (column + 1) * spacing / spanCount;
            if (position >= spanCount) outRect.top = verticalSpacing >= 0 ? verticalSpacing : spacing;
        } else {
            outRect.left = 0;
            outRect.right = 0;
            outRect.top = 0;
            outRect.bottom = 0;
        }
    }
}