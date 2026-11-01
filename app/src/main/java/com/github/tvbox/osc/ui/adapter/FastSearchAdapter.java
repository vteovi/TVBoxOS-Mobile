package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

/**
 * 搜索结果适配器。
 *
 * <p>统一使用海报卡片（{@link R.layout#item_search_card}）：海报铺满卡片，名称与「来源 · 备注」
 * 压在底部渐变上。卡片比例由 {@code RatioShadowLayout} 按「横版海报」设置自动切换
 * （竖版 2:3 / 横版 16:9），列数由 {@code Utils.getPosterSpanCount()} 按屏宽决定，与首页保持一致。
 */
public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    public FastSearchAdapter() {
        super(R.layout.item_search_card, new ArrayList<>());
    }

    private static String sourceName(String sourceKey) {
        SourceBean source = ApiConfig.get().getSource(sourceKey);
        if (source != null && !TextUtils.isEmpty(source.getName())) {
            return source.getName();
        }
        return sourceKey == null ? "" : sourceKey;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvSite, sourceName(item.sourceKey));
        boolean hasNote = item.note != null && !item.note.isEmpty();
        helper.setVisible(R.id.tvNote, hasNote);
        if (hasNote) {
            helper.setText(R.id.tvNote, item.note);
        }
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // 卡片已按比例定高，直接 centerCrop 铺满即可；按固定像素裁剪会把海报切变形
        Picasso.get().cancelRequest(ivThumb);
        ivThumb.setImageDrawable(null);
        if (TextUtils.isEmpty(item.pic)) {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        } else {
            Picasso.get()
                    .load(DefaultConfig.checkReplaceProxy(item.pic.trim()))
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
        }
    }
}
