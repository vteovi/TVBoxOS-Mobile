package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.DefaultConfig;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    /**
     * 图片请求统一 tag：滑动时用来整组暂停/恢复解码请求，避免快速滑动时解码洪峰导致掉帧。
     */
    public static final Object PICASSO_TAG = "grid_adapter_poster";

    /**
     * 记录 ImageView 当前已绑定的图片地址，复用到同一个地址时直接跳过请求，
     * 省掉重复的取消/占位/解码，滑动更顺。
     */
    private static final int TAG_PIC_URL = R.id.ivThumb;

    public GridAdapter() {
        super(R.layout.item_grid, new ArrayList<>());

    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

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
        if (!TextUtils.isEmpty(item.pic)) {
            item.pic = item.pic.trim();
            String pic = DefaultConfig.checkReplaceProxy(item.pic);
            // 复用池里同一个 View 再次绑同一张图时无需重新请求
            if (!pic.equals(ivThumb.getTag(TAG_PIC_URL))) {
                ivThumb.setTag(TAG_PIC_URL, pic);
                // 先占位，避免复用时短暂显示上一个位置的海报
                ivThumb.setImageResource(R.drawable.img_loading_placeholder);
                //由于部分电视机使用glide报错
                Picasso.get()
                        .load(pic)
                        .tag(PICASSO_TAG)
                        // 按 View 实际显示尺寸解码：不写 resize 时 Picasso 会按原图整张解码，
                        // 大屏一屏十几张高分辨率海报，滑动时解码 + 上传显存会明显掉帧
                        .fit()
                        .centerCrop()
                        .error(R.drawable.img_loading_placeholder)
                        .into(ivThumb);
            }
        } else {
            ivThumb.setTag(TAG_PIC_URL, null);
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }
}
