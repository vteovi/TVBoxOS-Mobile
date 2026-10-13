package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.MD5;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    public GridAdapter() {
        super(R.layout.item_grid, new ArrayList<>());

    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

        // 按 2:3 (宽:高) 动态调整 item 高度，保证手机/横屏/平板下都是标准海报比例
        final View itemView = helper.itemView;
        applyPosterRatio(itemView);

        TextView tvYear = helper.getView(R.id.tvYear);
        if (item.year <= 0) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            tvYear.setVisibility(View.VISIBLE);
        }

        if (TextUtils.isEmpty(item.note)) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setVisible(R.id.tvNote, true);
            helper.setText(R.id.tvNote, item.note);
        }
        helper.setText(R.id.tvName, item.name);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
            item.pic=item.pic.trim();
            Picasso.get()
                    .load(DefaultConfig.checkReplaceProxy(item.pic))
                    .placeholder(R.drawable.img_loading_placeholder)
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }

    private void applyPosterRatio(final View itemView) {
        if (itemView.getWidth() > 0) {
            int h = itemView.getWidth() * 3 / 2;
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            if (lp != null && lp.height != h) {
                lp.height = h;
                itemView.setLayoutParams(lp);
            }
        } else {
            // 首次绑定时宽度尚未测量，等首次 layout 完成后再设一次
            itemView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int oldL, int oldT, int oldR, int oldB) {
                    if (r - l > 0) {
                        v.removeOnLayoutChangeListener(this);
                        int h = (r - l) * 3 / 2;
                        ViewGroup.LayoutParams lp = v.getLayoutParams();
                        if (lp != null && lp.height != h) {
                            lp.height = h;
                            v.setLayoutParams(lp);
                        }
                    }
                }
            });
        }
    }
}