package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;

public class GridFilterDialog extends BaseDialog {
    private LinearLayout filterRoot;
    private MovieSort.SortData mSortData;

    public GridFilterDialog(@NonNull @NotNull Context context) {
        super(context);
        // 这个弹窗本来就不加背景遮罩（dimAmount=0）。显式告诉基类"不要遮罩"，
        // 基类就不会加 FLAG_DIM_BEHIND——那层遮罩铺满整个屏幕，会把状态栏也算成被弹窗盖住，
        // 导致浅色模式下弹窗期间状态栏图标变白、看不清。
        dimAmountBehind = 0f;

        setCancelable(true);
        setContentView(R.layout.dialog_grid_filter);
        filterRoot = findViewById(R.id.filterRoot);
        findViewById(R.id.btn_reset).setOnClickListener(view -> {
            if (mSortData == null) {
                return;
            }
            if (mSortData.filterSelect == null) {
                mSortData.filterSelect = new HashMap<>();
            }
            // 用 clear() 而不是换新 Map：外部（GridFragment）持有同一个 SortData 引用，
            // 换对象会让后续请求读到的仍是旧 Map，表现为"重置/筛选不生效"
            mSortData.filterSelect.clear();
            selectChange = true;
            setData(mSortData);
        });

        findViewById(R.id.btn_confirm).setOnClickListener(view -> dismiss());
    }

    public interface Callback {
        void change();
    }

    public void setOnDismiss(Callback callback) {
        setOnDismissListener(dialogInterface -> {
            if (selectChange) {
                callback.change();
            }
        });
    }

    /**
     * 按传入的分类重建筛选选项。
     *
     * 每次打开对话框都会调用，保证选项与当前分类一致，
     * 并按照 filterSelect 里已保存的条件还原选中高亮。
     */
    public void setData(MovieSort.SortData sortData) {
        if (sortData == null) {
            return;
        }
        mSortData = sortData;
        if (sortData.filterSelect == null) {
            sortData.filterSelect = new HashMap<>();
        }
        filterRoot.removeAllViews();
        if (sortData.filters == null) {
            return;
        }
        for (MovieSort.SortFilter filter : sortData.filters) {
            if (filter == null || filter.values == null || filter.values.isEmpty()) {
                continue;
            }
            View line = LayoutInflater.from(getContext()).inflate(R.layout.item_grid_filter, filterRoot, false);
            RecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));

            // values 的 key 是显示名称，value 才是提交给数据源的筛选值
            final String filterKey = filter.key;
            final ArrayList<String> names = new ArrayList<>(filter.values.keySet());
            final ArrayList<String> keys = new ArrayList<>(filter.values.values());
            final GridFilterKVAdapter filterKVAdapter =
                    new GridFilterKVAdapter(keys, sortData.filterSelect.get(filterKey));

            filterKVAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    if (position < 0 || position >= keys.size()) {
                        return;
                    }
                    selectChange = true;
                    String value = keys.get(position);
                    String current = mSortData.filterSelect.get(filterKey);
                    if (current != null && current.equals(value)) {
                        // 再次点击已选中项 = 取消该条件
                        mSortData.filterSelect.remove(filterKey);
                        filterKVAdapter.setSelectedValue(null);
                    } else {
                        mSortData.filterSelect.put(filterKey, value);
                        filterKVAdapter.setSelectedValue(value);
                    }
                }
            });
            filterKVAdapter.setNewData(names);
            gridView.setAdapter(filterKVAdapter);
            filterRoot.addView(line);
        }
    }

    private boolean selectChange = false;

    @Override
    public void show() {
        selectChange = false;
        // 窗口参数要在 show() 之前设好：show() 里基类会按 dimAmountBehind 决定加不加遮罩，
        // 事后再改属性的话，窗口已经带着遮罩加进来过一帧了。
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.gravity = Gravity.BOTTOM;
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        layoutParams.dimAmount = 0f;
        layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        getWindow().setAttributes(layoutParams);
        getWindow().getDecorView().setPadding(0, 0, 0, 0);

        super.show();
        // 上面重新设置过窗口属性，状态栏外观在这里再同步一次
        syncStatusBarWithHost();
    }
}
