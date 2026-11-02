package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.MD5;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 搜索结果适配器，支持两种显示模式（由搜索页右上角的切换按钮控制，选择会记住）：
 *
 * <ul>
 *     <li><b>卡片模式</b>（{@link R.layout#item_search_card}）：海报铺满卡片，名称与「来源 · 备注」
 *     压在底部渐变上。卡片比例由 {@code RatioShadowLayout} 按「横版海报」设置自动切换
 *     （竖版 2:3 / 横版 16:9），列数由 {@code Utils.getPosterSpanCount()} 按屏宽决定，与首页一致。</li>
 *     <li><b>列表模式</b>（{@link R.layout#item_search}）：左侧缩略图 + 右侧名称/来源/备注的横向条目，
 *     单列显示。</li>
 * </ul>
 *
 * <p>两种布局共用 {@code ivThumb / tvName / tvSite / tvNote} 这组 id，所以 convert 里只需要
 * 按当前模式区分图片的裁切方式。
 */
public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    /** 卡片模式 */
    public static final int TYPE_CARD = 0;
    /** 列表模式 */
    public static final int TYPE_LIST = 1;

    private boolean cardMode;

    public FastSearchAdapter(boolean cardMode) {
        // 传 0 表示不使用单一布局，具体布局在 onCreateDefViewHolder 里按 viewType 决定
        super(0, new ArrayList<>());
        this.cardMode = cardMode;
    }

    public boolean isCardMode() {
        return cardMode;
    }

    /**
     * 切换显示模式。
     *
     * 两种模式的 item 布局不同，必须走 notifyDataSetChanged 让 RecyclerView 按新的 viewType
     * 重新创建 ViewHolder，否则会拿旧布局的 holder 去绑定新布局。
     */
    public void setCardMode(boolean cardMode) {
        if (this.cardMode == cardMode) {
            return;
        }
        this.cardMode = cardMode;
        notifyDataSetChanged();
    }

    @Override
    protected int getDefItemViewType(int position) {
        // 注意是 getDefItemViewType 而不是 getItemViewType：后者已被库用来处理 header/footer，
        // 覆盖它会把这套逻辑一起干掉
        return cardMode ? TYPE_CARD : TYPE_LIST;
    }

    @Override
    protected BaseViewHolder onCreateDefViewHolder(ViewGroup parent, int viewType) {
        int layoutId = viewType == TYPE_CARD ? R.layout.item_search_card : R.layout.item_search;
        return createBaseViewHolder(parent, layoutId);
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
        Picasso.get().cancelRequest(ivThumb);
        ivThumb.setImageDrawable(null);
        if (TextUtils.isEmpty(item.pic)) {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
            return;
        }
        String url = DefaultConfig.checkReplaceProxy(item.pic.trim());
        if (isCardMode()) {
            // 卡片已按比例定高，直接 centerCrop 铺满即可；按固定像素裁剪会把海报切变形
            ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Picasso.get()
                    .load(url)
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
        } else {
            // 列表模式：左侧缩略图，裁切并加圆角（与旧版搜索结果样式一致）
            Picasso.get()
                    .load(url)
                    .transform(new RoundTransformation(MD5.string2MD5(url))
                            .centerCorp(true)
                            .override(AutoSizeUtils.dp2px(mContext, 110), AutoSizeUtils.dp2px(mContext, 160))
                            .roundRadius(AutoSizeUtils.dp2px(mContext, 20), RoundTransformation.RoundType.ALL))
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
        }
    }
}
