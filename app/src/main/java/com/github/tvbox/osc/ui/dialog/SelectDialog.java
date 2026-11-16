package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class SelectDialog<T> extends BaseDialog {

    /** 手机上的默认宽度 */
    private static final int NARROW_WIDTH_DP = 330;
    /** 宽屏模式的宽度上下限（dp）与占屏比 */
    private static final int WIDE_MIN_WIDTH_DP = 460;
    private static final int WIDE_MAX_WIDTH_DP = 720;
    private static final float WIDE_WIDTH_RATIO = 0.62f;
    /** 列表区高度上限（dp）与占屏比；窄屏沿用布局里的 dp_410 */
    private static final int WIDE_MAX_LIST_HEIGHT_DP = 760;
    private static final float WIDE_LIST_HEIGHT_RATIO = 0.62f;
    /** 每一列的目标宽度（dp），据此换算列数 */
    private static final int TARGET_COLUMN_WIDTH_DP = 160;

    private boolean wide = false;

    public SelectDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_select);
    }

    public SelectDialog(@NonNull @NotNull Context context, int resId) {
        super(context);
        setContentView(resId);
    }

    /**
     * 宽屏模式：对话框变宽、列表区变高、列数随宽度增加。
     * 手机竖屏保持原来的 330dp / 2 列，不受影响。
     */
    public SelectDialog<T> setWide(boolean wide) {
        this.wide = wide;
        return this;
    }

    /** 当前宽度下建议的列数，调用方在 setLayoutManager 时用 */
    public int getSpanCount() {
        int widthDp = ConvertUtils.px2dp(resolveWidthPx());
        return Math.max(2, widthDp / TARGET_COLUMN_WIDTH_DP);
    }

    private int resolveWidthPx() {
        if (!wide) {
            return ConvertUtils.dp2px(NARROW_WIDTH_DP);
        }
        int ratioWidth = (int) (ScreenUtils.getScreenWidth() * WIDE_WIDTH_RATIO);
        int max = ConvertUtils.dp2px(WIDE_MAX_WIDTH_DP);
        int min = ConvertUtils.dp2px(WIDE_MIN_WIDTH_DP);
        return Math.max(min, Math.min(ratioWidth, max));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(getWindow().getAttributes());
        lp.gravity = Gravity.CENTER;
        lp.width = resolveWidthPx();
        getWindow().setAttributes(lp);
        getWindow().setWindowAnimations(R.style.DialogFadeAnimation); // Set the animation style
        findViewById(R.id.iv_close).setOnClickListener(view -> dismiss());

        if (wide) {
            applyWideListHeight();
        }
    }

    /**
     * 宽屏下把列表区上限从固定的 410dp 提到约六成屏高，避免平板上一屏只能看几项。
     */
    private void applyWideListHeight() {
        android.view.View list = findViewById(R.id.list);
        if (list == null) {
            return;
        }
        ViewGroup.LayoutParams raw = list.getLayoutParams();
        if (!(raw instanceof ConstraintLayout.LayoutParams)) {
            return;
        }
        int ratioHeight = (int) (ScreenUtils.getScreenHeight() * WIDE_LIST_HEIGHT_RATIO);
        int max = ConvertUtils.dp2px(WIDE_MAX_LIST_HEIGHT_DP);
        ConstraintLayout.LayoutParams clp = (ConstraintLayout.LayoutParams) raw;
        clp.matchConstraintMaxHeight = Math.min(ratioHeight, max);
        list.setLayoutParams(clp);
    }

    public void setTip(String tip) {
        ((TextView) findViewById(R.id.title)).setText(tip);
    }

    public void setAdapter(SelectDialogAdapter.SelectDialogInterface<T> sourceBeanSelectDialogInterface, DiffUtil.ItemCallback<T> sourceBeanItemCallback, List<T> data, int select) {
        SelectDialogAdapter<T> adapter = new SelectDialogAdapter(sourceBeanSelectDialogInterface, sourceBeanItemCallback);
        List<T> items = data == null ? Collections.emptyList() : data;
        int itemCount = items.size();
        int selectedPosition = itemCount == 0 ? RecyclerView.NO_POSITION : Math.max(0, Math.min(select, itemCount - 1));
        adapter.setData(items, selectedPosition);
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        tvRecyclerView.setAdapter(adapter);
        if (selectedPosition == RecyclerView.NO_POSITION) {
            return;
        }
        tvRecyclerView.setSelectedPosition(selectedPosition);
        tvRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                if (tvRecyclerView.getAdapter() != adapter || adapter.getItemCount() <= selectedPosition) {
                    return;
                }
                tvRecyclerView.smoothScrollToPosition(selectedPosition);
                tvRecyclerView.setSelectionWithSmooth(selectedPosition);
            }
        });
    }
}
