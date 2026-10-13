package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import com.github.tvbox.osc.util.HawkConfig;
import com.lihang.ShadowLayout;
import com.orhanobut.hawk.Hawk;

/**
 * 首页海报卡片根布局：按比例自动计算高度。
 *
 * 默认 2:3 竖版海报（宽:高 = 2:3）。若开启"横屏海报"开关，则全局使用
 * 16:9 横版比例（竖屏/横屏都生效）。在 onMeasure 阶段直接根据宽度定高度，
 * 避免 inflate 后先按 XML 默认高度绘制、再纠正导致的"海报弹高"闪烁。
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
        return Hawk.get(HawkConfig.LANDSCAPE_CARD, false);
    }
}
