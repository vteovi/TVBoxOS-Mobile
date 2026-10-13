package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import com.lihang.ShadowLayout;

/**
 * 首页海报卡片根布局：按 2:3 (宽:高) 比例自动计算高度。
 *
 * 在 onMeasure 阶段直接根据宽度定高度，避免 inflate 后先按 XML 默认高度
 * 绘制、再由 OnLayoutChangeListener 纠正导致的"海报弹高"闪烁。
 * 手机竖屏/横屏、平板、大屏下宽度变化时都会自动按比例走。
 */
public class RatioShadowLayout extends ShadowLayout {

    public RatioShadowLayout(Context context) {
        super(context);
    }

    public RatioShadowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioShadowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        // 只要拿到了有限宽度（EXACTLY 列宽，或 AT_MOST 上限），就按 2:3 定高，
        // 避免第一次测量走默认分支渲染成错误高度后再跳变。
        if (widthSize > 0 && widthMode != MeasureSpec.UNSPECIFIED) {
            int height = widthSize * 3 / 2;
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
