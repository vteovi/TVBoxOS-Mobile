package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;

import com.github.tvbox.osc.util.HawkConfig;
import com.lihang.ShadowLayout;
import com.orhanobut.hawk.Hawk;

/**
 * 首页海报卡片根布局：按比例自动计算高度。
 *
 * 默认 2:3 竖版海报（宽:高 = 2:3）。若开启"横屏横版海报"开关且当前处于横屏，
 * 则使用 16:9 横版比例。在 onMeasure 阶段直接根据宽度定高度，避免 inflate 后
 * 先按 XML 默认高度绘制、再纠正导致的"海报弹高"闪烁。
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
            if (isLandscapeCard()) {
                // 横版 16:9：宽:高 = 16:9 → 高 = 宽 * 9 / 16
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

    private boolean isLandscapeCard() {
        boolean enabled = Hawk.get(HawkConfig.LANDSCAPE_CARD, false);
        if (!enabled) return false;
        Configuration cfg = getContext().getResources().getConfiguration();
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
}
