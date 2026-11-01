package com.github.tvbox.osc.ui.adapter;

import android.view.View;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 筛选项适配器。
 *
 * 列表里显示的是 filter.values 的 key（名称），真正提交给数据源的是 values 的 value，
 * 所以必须额外持有与 data 一一对应的值列表，才能判断"当前哪一项被选中"。
 *
 * 选中态由数据（selectedValue）驱动，点击后 notifyDataSetChanged 刷新，
 * 不再依赖"记住上一次点击的 View"，重开对话框时也能正确还原高亮。
 */
public class GridFilterKVAdapter extends BaseQuickAdapter<String, BaseViewHolder> {

    /** 与 data 一一对应的筛选值（提交给数据源的 value） */
    private final List<String> keys;

    /** 当前选中的筛选值，null 表示未选 */
    private String selectedValue;

    public GridFilterKVAdapter(List<String> keys, String selectedValue) {
        super(R.layout.item_grid_filter_value, new ArrayList<>());
        this.keys = keys;
        this.selectedValue = selectedValue;
    }

    public void setSelectedValue(String selectedValue) {
        this.selectedValue = selectedValue;
        notifyDataSetChanged();
    }

    public String getSelectedValue() {
        return selectedValue;
    }

    @Override
    protected void convert(BaseViewHolder helper, String item) {
        helper.setText(R.id.filterValue, item);
        View sl = helper.getView(R.id.sl);
        if (sl == null) return;
        int position = helper.getLayoutPosition();
        String value = (keys != null && position >= 0 && position < keys.size()) ? keys.get(position) : null;
        sl.setSelected(value != null && value.equals(selectedValue));
    }
}
