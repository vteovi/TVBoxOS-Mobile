package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * 海报专用 ImageView：拦截「替换图片」引起的 requestLayout 冒泡。
 *
 * <p>{@code ImageView.setImageDrawable/setImageBitmap/setImageResource} 内部都会调用
 * {@code requestLayout()}，哪怕自身宽高是由父布局决定的（match_parent）也照样向上冒泡，
 * 最终让 RecyclerView 在下一帧做一次**整屏** measure/layout（所有可见 item 重新测量）。
 *
 * <p>滑动时海报是异步加载完成的，这次整屏布局恰好插在滑动的某一帧里，表现就是
 * 「图片刚加载出来那一刻掉帧」，而已加载过的内容回看时（内存缓存同步命中、布局在 bind
 * 期间一起完成）反而顺滑。
 *
 * <p>这里在 setImage* 调用期间忽略 requestLayout（只重绘）：替换图片不会改变 ImageView
 * 的尺寸，无需重新布局。宽或高为 WRAP_CONTENT 时不拦截，避免影响自适应场景。
 */
public class PosterImageView extends ImageView {

    private boolean suppressLayoutRequest;

    public PosterImageView(Context context) {
        super(context);
    }

    public PosterImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public PosterImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        suppressLayoutRequest = true;
        try {
            super.setImageDrawable(drawable);
        } finally {
            suppressLayoutRequest = false;
        }
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        suppressLayoutRequest = true;
        try {
            super.setImageBitmap(bm);
        } finally {
            suppressLayoutRequest = false;
        }
    }

    @Override
    public void setImageResource(int resId) {
        suppressLayoutRequest = true;
        try {
            super.setImageResource(resId);
        } finally {
            suppressLayoutRequest = false;
        }
    }

    @Override
    public void requestLayout() {
        if (suppressLayoutRequest && sizeFixedByParent()) {
            // 尺寸不受图片影响，只重绘即可
            invalidate();
            return;
        }
        super.requestLayout();
    }

    /**
     * 宽高都不是 WRAP_CONTENT 时，说明尺寸由父布局或固定值决定，替换图片不会改变尺寸。
     */
    private boolean sizeFixedByParent() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        return lp == null
                || (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT
                && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
