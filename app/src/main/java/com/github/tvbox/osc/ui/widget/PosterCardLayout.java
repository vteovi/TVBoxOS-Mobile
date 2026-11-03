package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

/**
 * 首页 / 搜索结果的海报卡片根布局：按比例定高 + GPU 圆角裁切。
 *
 * <p>为什么不用原来的 {@code ShadowLayout}：它的 {@code dispatchDraw()} 里每次绘制都会
 * {@code new Path()} + {@code Canvas.clipPath()}（软件裁剪）。一屏十几张卡片、每帧都要
 * 重建 Path 再做软件裁剪，快速滑动时是实打实的掉帧来源。
 *
 * <p>卡片其实并不需要阴影（item_grid 从未配置 shadow 属性），ShadowLayout 在这里只承担
 * 「圆角裁切」。改用 {@link #setClipToOutline(boolean)} + {@link ViewOutlineProvider}：
 * 圆角由 RenderThread 在 GPU 上裁切，几乎零成本，视觉与 clipPath 一致。
 */
public class PosterCardLayout extends FrameLayout {

    /**
     * 缓存「横版海报」开关：onMeasure 在滑动时会被高频调用，每次都 Hawk.get 会走
     * SharedPreferences + 反序列化，大屏一屏十几个卡片时是明显的掉帧来源。
     */
    private static volatile Boolean sLandscapeCard = null;

    private final float cornerRadius;

    /**
     * 设置里切换「横版海报」后调用，让下一次 measure 重新读取最新设置。
     */
    public static void invalidateRatioCache() {
        sLandscapeCard = Hawk.get(HawkConfig.LANDSCAPE_CARD, false);
    }

    public PosterCardLayout(Context context) {
        this(context, null);
    }

    public PosterCardLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PosterCardLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float radius = 0f;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                    attrs, R.styleable.PosterCardLayout, defStyleAttr, 0);
            radius = a.getDimension(R.styleable.PosterCardLayout_pcCornerRadius, 0f);
            a.recycle();
        }
        cornerRadius = radius;
        if (cornerRadius > 0f) {
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w <= 0 || h <= 0) {
                        // 尺寸未定时不提供 outline，系统会视为「无裁剪」，避免卡片被裁空
                        return;
                    }
                    // 半径不能超过短边一半，否则 outline 无效
                    float r = Math.min(cornerRadius, Math.min(w, h) / 2f);
                    outline.setRoundRect(0, 0, w, h, r);
                }
            });
            // 图片内容本身已覆盖整块区域，不需要额外背景
            setWillNotDraw(true);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        if (widthSize > 0 && widthMode != MeasureSpec.UNSPECIFIED) {
            int height;
            if (useLandscapeRatio()) {
                // 横版 16:9：高 = 宽 * 9 / 16
                height = widthSize * 9 / 16;
            } else {
                // 竖版 2:3：高 = 宽 * 3 / 2
                height = widthSize * 3 / 2;
            }
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private boolean useLandscapeRatio() {
        Boolean cached = sLandscapeCard;
        if (cached == null) {
            cached = Hawk.get(HawkConfig.LANDSCAPE_CARD, false);
            sLandscapeCard = cached;
        }
        return cached;
    }
}
