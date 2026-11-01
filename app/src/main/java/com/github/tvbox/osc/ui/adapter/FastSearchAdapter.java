package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.orhanobut.hawk.Hawk;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 搜索结果适配器。
 *
 * <p>默认是「左图右文」的列表条目（图按竖版裁剪）；设置里开启「横版海报」后改用
 * {@link R.layout#item_search_landscape} 的 16:9 卡片，与首页 / 收藏 / 历史的横版卡片保持一致。
 * 布局在构造时按设置决定，convert 里用同一个标记分支，避免布局与渲染方式不匹配。
 */
public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    private final boolean landscapeCard;

    public FastSearchAdapter() {
        super(landscapeCard() ? R.layout.item_search_landscape : R.layout.item_search, new ArrayList<>());
        this.landscapeCard = landscapeCard();
    }

    private static boolean landscapeCard() {
        return Hawk.get(HawkConfig.LANDSCAPE_CARD, false);
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
        if (landscapeCard) {
            // 卡片本身就是 16:9，直接 centerCrop 铺满即可，不再按固定像素裁剪，否则横图会被切变形
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
        } else if (!TextUtils.isEmpty(item.pic)) {
            Picasso.get()
                    .load(item.pic)
                    .transform(new RoundTransformation(MD5.string2MD5(item.pic + "position=" + helper.getLayoutPosition()))
                            .centerCorp(true)
                            .override(AutoSizeUtils.dp2px(mContext, 110), AutoSizeUtils.dp2px(mContext, 160))
                            .roundRadius(AutoSizeUtils.dp2px(mContext, 20), RoundTransformation.RoundType.ALL))
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }
}
