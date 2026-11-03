package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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
 * 搜索结果适配器，支持两种显示模式（由搜索页右上角的切换按钮控制，选择会记住）：
 *
 * <ul>
 *     <li><b>卡片模式</b>（{@link R.layout#item_search_card}）：海报铺满卡片，名称与「来源 · 备注」
 *     压在底部渐变上。卡片比例由 {@code RatioShadowLayout} 按「横版海报」设置自动切换
 *     （竖版 2:3 / 横版 16:9），列数由 {@code Utils.getPosterSpanCount()} 按屏宽决定，与首页一致。</li>
 *     <li><b>列表模式</b>（{@link R.layout#item_search}）：左侧缩略图 + 右侧名称/来源/备注的横向条目，
 *     单列显示。缩略图比例同样跟随「横版海报」设置：竖版 80x120dp(2:3)、横版 160x90dp(16:9)。</li>
 * </ul>
 *
 * <p>两种布局共用 {@code ivThumb / tvName / tvSite / tvNote} 这组 id，所以 convert 里只需要
 * 按当前模式区分图片的裁切方式与尺寸。
 */
public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    /** 卡片模式 */
    public static final int TYPE_CARD = 0;
    /** 列表模式 */
    public static final int TYPE_LIST = 1;

    /** 列表模式缩略图尺寸（dp）：竖版 2:3 */
    private static final int LIST_THUMB_W_PORTRAIT = 80;
    private static final int LIST_THUMB_H_PORTRAIT = 120;
    /** 列表模式缩略图尺寸（dp）：横版 16:9 */
    private static final int LIST_THUMB_W_LANDSCAPE = 160;
    private static final int LIST_THUMB_H_LANDSCAPE = 90;

    private boolean cardMode;

    /**
     * 是否在列表模式里用横版（16:9）缩略图。
     *
     * 与卡片模式共用同一个设置项 {@link HawkConfig#LANDSCAPE_CARD}，保证两种显示方式下
     * 海报比例一致。搜索页每次进入都会重建适配器，所以构造时读一次即可。
     */
    private final boolean landscapeCard;

    public FastSearchAdapter(boolean cardMode) {
        // 传 0 表示不使用单一布局，具体布局在 onCreateDefViewHolder 里按 viewType 决定
        super(0, new ArrayList<>());
        this.cardMode = cardMode;
        this.landscapeCard = Hawk.get(HawkConfig.LANDSCAPE_CARD, false);
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

    /** 列表模式缩略图宽度（dp） */
    private int listThumbWidthDp() {
        return landscapeCard ? LIST_THUMB_W_LANDSCAPE : LIST_THUMB_W_PORTRAIT;
    }

    /** 列表模式缩略图高度（dp） */
    private int listThumbHeightDp() {
        return landscapeCard ? LIST_THUMB_H_LANDSCAPE : LIST_THUMB_H_PORTRAIT;
    }

    /**
     * 列表模式：把缩略图与文字区的留白调整到当前比例。
     *
     * 横版缩略图只有 90dp 高，沿用竖版的 12dp 上下留白 + 20dp 的备注上间距会把条目撑得比图片高很多，
     * 所以横版时收紧文字区留白，让图片与文字同高对齐。
     */
    private void applyListMetrics(BaseViewHolder helper) {
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        int w = AutoSizeUtils.dp2px(mContext, listThumbWidthDp());
        int h = AutoSizeUtils.dp2px(mContext, listThumbHeightDp());
        ViewGroup.LayoutParams lp = ivThumb.getLayoutParams();
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w;
            lp.height = h;
            ivThumb.setLayoutParams(lp);
        }

        View textBox = helper.getView(R.id.llText);
        int padH = AutoSizeUtils.dp2px(mContext, 8);
        int padV = AutoSizeUtils.dp2px(mContext, landscapeCard ? 2 : 12);
        if (textBox.getPaddingTop() != padV || textBox.getPaddingLeft() != padH) {
            textBox.setPadding(padH, padV, padH, padV);
        }

        TextView tvNote = helper.getView(R.id.tvNote);
        ViewGroup.LayoutParams noteLp = tvNote.getLayoutParams();
        if (noteLp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) noteLp;
            int top = AutoSizeUtils.dp2px(mContext, landscapeCard ? 6 : 20);
            if (mlp.topMargin != top) {
                mlp.topMargin = top;
                tvNote.setLayoutParams(mlp);
            }
        }
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
        if (isCardMode()) {
            // 卡片已按比例定高，直接 centerCrop 铺满即可；按固定像素裁剪会把海报切变形
            ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (TextUtils.isEmpty(item.pic)) {
                ivThumb.setImageResource(R.drawable.img_loading_placeholder);
                return;
            }
            String url = DefaultConfig.checkReplaceProxy(item.pic.trim());
            Picasso.get()
                    .load(url)
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb);
            return;
        }

        // 列表模式：缩略图尺寸跟随「横版海报」设置，裁切并加圆角（与旧版搜索结果样式一致）
        applyListMetrics(helper);
        ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (TextUtils.isEmpty(item.pic)) {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
            return;
        }
        String url = DefaultConfig.checkReplaceProxy(item.pic.trim());
        int w = AutoSizeUtils.dp2px(mContext, listThumbWidthDp());
        int h = AutoSizeUtils.dp2px(mContext, listThumbHeightDp());
        // 缓存 key 带上尺寸：切换「横版海报」后同样一部片子的缩略图不会被旧比例的位图命中
        String cacheKey = MD5.string2MD5(url + "@" + w + "x" + h);
        Picasso.get()
                .load(url)
                .transform(new RoundTransformation(cacheKey)
                        .centerCorp(true)
                        .override(w, h)
                        .roundRadius(AutoSizeUtils.dp2px(mContext, landscapeCard ? 14 : 20),
                                RoundTransformation.RoundType.ALL))
                .error(R.drawable.img_loading_placeholder)
                .into(ivThumb);
    }
}
