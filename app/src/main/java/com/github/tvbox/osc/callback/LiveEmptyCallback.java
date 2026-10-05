package com.github.tvbox.osc.callback;

import com.github.tvbox.osc.R;
import com.kingja.loadsir.callback.Callback;

/**
 * 直播页专属空布局：无可用直播频道时在页面内显示文字（替代原 XPopup 弹窗）。
 */
public class LiveEmptyCallback extends Callback {
    @Override
    protected int onCreateView() {
        return R.layout.live_empty_layout;
    }
}
