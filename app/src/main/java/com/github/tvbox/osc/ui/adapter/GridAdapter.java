package com.github.tvbox.osc.ui.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.Utils;
import com.orhanobut.hawk.Hawk;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    /**
     * 记录 ImageView 当前已绑定的图片地址，复用到同一个地址时直接跳过请求。
     */
    private static final int TAG_PIC_URL = R.id.ivThumb;

    /** 海报解码尺寸（按屏幕宽度与列数算，首次计算后缓存） */
    private static int sDecodeW;
    private static int sDecodeH;

    public GridAdapter() {
        super(R.layout.item_grid, new ArrayList<>());

    }

    /**
     * 计算海报解码尺寸。
     *
     * <p>注意这里用 {@code resize} 而不是 {@code fit()}：{@code fit()} 依赖 ImageView 的实时尺寸，
     * 在 RecyclerView 里复用 item 时会反复触发布局与重新请求，滚动时表现为画面抖动；
     * 而完全不指定尺寸又会按原图整张解码，大屏一屏十几张高分辨率海报会明显掉帧。
     * 用「屏宽 / 列数」估算卡片宽度并显式 resize，是两者之间最稳的做法。
     */
    private static void ensureDecodeSize(Context context) {
        if (sDecodeW > 0 && sDecodeH > 0) return;
        int span = Math.max(1, Utils.getPosterSpanCount(context));
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int w = Math.max(1, screenW / span);
        boolean landscape = Hawk.get(HawkConfig.LANDSCAPE_CARD, false);
        sDecodeW = w;
        sDecodeH = landscape ? Math.max(1, w * 9 / 16) : Math.max(1, w * 3 / 2);
    }

    /**
     * 设置里改了海报尺寸 / 横版比例后调用，让解码尺寸按新布局重新计算。
     */
    public static void resetDecodeSize() {
        sDecodeW = 0;
        sDecodeH = 0;
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
                ensureDecodeSize(ivThumb.getContext());
                //由于部分电视机使用glide报错
                Picasso.get()
                        .load(pic)
                        // 按估算的显示尺寸解码，避免整张高分辨率原图解码占内存；
                        // onlyScaleDown 很关键：原图本来就比显示尺寸小时保持原样，
                        // 否则 resize 会把小图放大解码（大屏少列数时单张能涨到 4MB），反而更吃内存
                        .resize(sDecodeW, sDecodeH)
                        .onlyScaleDown()
                        .centerCrop()
                        // 关掉 Picasso 默认的 200ms 淡入：一屏十几张海报同时做 alpha 混合动画，
                        // 滑动时每帧都要额外合成，正是“图片刚加载出来那一刻掉帧”的来源之一
                        .noFade()
                        .placeholder(R.drawable.img_loading_placeholder)
                        .error(R.drawable.img_loading_placeholder)
                        .into(ivThumb);
            }
        } else {
            ivThumb.setTag(TAG_PIC_URL, null);
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }
}
