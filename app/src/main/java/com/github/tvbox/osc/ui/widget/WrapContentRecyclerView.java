package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 放在 ScrollView / 可滚动父容器里的 RecyclerView（高度 wrap_content 用）。
 *
 * <p>普通 RecyclerView 拿到 AT_MOST 的高度约束时，只会布局到"填满这个高度"为止，
 * 加上自身又被关掉了滚动，多出来的条目就被裁掉了 —— 表现就是"选集只显示了一部分，
 * 点全部才看得到全部"。
 *
 * <p>这里统一按 UNSPECIFIED 交给 LayoutManager 测量，让它把自己完全撑开，
 * 滚动交给外层 ScrollView。
 */
public class WrapContentRecyclerView extends RecyclerView {

    public WrapContentRecyclerView(Context context) {
        super(context);
    }

    public WrapContentRecyclerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WrapContentRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }
}
