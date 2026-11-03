package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.BounceInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.LogUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import java.util.List;
import java.util.Stack;
import android.view.ViewGroup;
import android.widget.Toast;

import org.greenrobot.eventbus.EventBus;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private RecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private boolean mLoading = false; // 防止分页请求重复/竞争
    private boolean isTop = true;
    private View focusedView = null;
    private class GridInfo{
        public String sortID="";
        public RecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public View focusedView= null;
    }
    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && this.sortData == null) {
            //activity销毁再进入,会直接恢复fragment,从而直接getList,导致sortData为空闪退
            this.sortData = GsonUtils.fromJson(savedInstanceState.getString("sortDataJson"), MovieSort.SortData.class);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sortDataJson", GsonUtils.toJson(sortData));
    }

    /** 清空当前分类的筛选条件（换分类/返回上级时用） */
    private void clearFilterSelect() {
        if (sortData != null && sortData.filterSelect != null) {
            sortData.filterSelect.clear();
        }
    }

    private void changeView(String id, Boolean isFolder){
        // 换了分类后筛选项可能完全不同，旧条件不能带过去，
        // 否则请求会带上新分类不认识的参数，看起来就是"筛选不生效"
        clearFilterSelect();
        if(isFolder){
            this.sortData.flag ="1"; // 修改sortData.flag
        }else {
            this.sortData.flag =null; // 修改sortData.flag
        }
        initView();
        this.sortData.id =id; // 修改sortData.id为新的ID
        initViewModel();
        initData();
    }
    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag(){
        System.out.println(sortData);
        return (sortData == null || sortData.flag == null || sortData.flag.length() ==0 ) ?  '0' : sortData.flag.charAt(0);
    }
    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch(){  return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }
    // 保存当前页面
    private void saveCurrentView(){
        if(this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }
    // 丢弃当前页面，将页面还原成上一个保存的页面
    public boolean restoreView(){
        if(mGrids.empty()) return false;
        this.showSuccess();
        ((ViewGroup) mGridView.getParent()).removeView(this.mGridView); // 重父窗口移除当前控件
        GridInfo info = mGrids.pop();// 还原上次保存的控件
        this.sortData.id = info.sortID;
        // 返回上一级时同样不带旧分类的筛选条件
        clearFilterSelect();
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
//        if(this.focusedView != null){ this.focusedView.requestFocus(); }
        if(mGridView != null) mGridView.requestFocus();
        return true;
    }
    // 更改当前页面
    private void createView(){
        this.saveCurrentView(); // 保存当前页面
        if(mGridView == null){ // 从layout中拿view
            mGridView = findViewById(R.id.mGridView);
        }else{ // 复制当前view
            TvRecyclerView v3 = new TvRecyclerView(this.mContext);
            v3.setSpacingWithMargins(10,10);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            ((ViewGroup) mGridView.getParent()).addView(v3);
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        // 大屏一屏可见十几张卡片：适度加大 item 缓存（默认 2 个不够用），
        // 但不宜过大——缓存里每个 view 都带着一张已解码的海报，太多会推高内存触发频繁 GC。
        int spanCount = Utils.getPosterSpanCount(this.mContext);
        mGridView.setItemViewCacheSize(Math.min(Math.max(8, spanCount * 3), 15));
        // 加载更多时一批新卡片若同时做淡入动画，会一次性掉帧（滚到底“卡一下”）；列表直接出现即可
        mGridView.setItemAnimator(null);
        gridAdapter = new GridAdapter();
        this.page =1;
        this.maxPage =1;
        this.isLoad = false;
    }

    /**
     * 统一的网格 LayoutManager 创建。
     *
     * <p>不启用 setInitialPrefetchItemCount：预取会在滚动时额外绑定好几页卡片并发起图片请求，
     * 解码瞬时洪峰反而更容易掉帧；这里保持默认行为。
     */
    private void applyGridLayoutManager() {
        if (mGridView == null) return;
        int spanCount = Utils.getPosterSpanCount(this.mContext);
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, spanCount));
    }

    private void initView() {
        this.createView();
        mGridView.setAdapter(gridAdapter);
        applyGridLayoutManager();

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                if (mLoading) return; // 上一页还在加载中，跳过
                gridAdapter.setEnableLoadMore(true);
                mLoading = true;
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
                    if(("12".indexOf(getUITag()) != -1) && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        changeView(video.id,video.tag.equals("folder"));
                    }
                    else if(homeSourceBean.isQuickSearch() && Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) && enableFastSearch()){
                        jumpActivity(FastSearchActivity.class, bundle);
                    }else{
                        if(TextUtils.isEmpty(video.id) || video.id.startsWith("msearch:")){
                            jumpActivity(FastSearchActivity.class, bundle);
//                            jumpActivity(SearchActivity.class, bundle);
                        }else {
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }

                }
            }
        });
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());

        findViewById(R.id.btn_filter).setOnClickListener(view -> showFilter());
        setLoadSir2(mGridView);
    }

    /** 标记配置(横竖屏)是否发生了变化，用于 onFragmentResume 时判断是否需要重建 LayoutManager */
    private boolean mConfigChanged = false;

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mConfigChanged = true;
        // 已创建的分栏直接按新配置重建 LayoutManager，保证所有已加载分栏列数立即更新；
        // 未创建的分栏会在首次 initView() 时用新配置创建。
        if (mGridView != null) {
            applyGridLayoutManager();
        }
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        // 兜底：如果配置变化时本分栏还没创建/没收到回调，可见时按新配置重建；
        // 普通从详情页返回不重建，保留滚动位置。
        if (mConfigChanged) {
            mConfigChanged = false;
            if (mGridView != null) {
                applyGridLayoutManager();
            }
        }
        // 从其他分栏切回来时，兜底重置加载状态，防止之前的请求回调丢失导致永久卡住
        if (mLoading) {
            mLoading = false;
            if (gridAdapter != null) {
                gridAdapter.loadMoreComplete();
                gridAdapter.setEnableLoadMore(true);
            }
        }
    }

    private void initViewModel() {
        if(sourceViewModel != null) { return;}
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
//                if(mGridView != null) mGridView.requestFocus();
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        // 如果已经从本地缓存显示过数据，后台返回时更新缓存文件；
                        // 同时静默替换列表数据，避免缓存旧数据和第二页新数据重叠导致重复
                        boolean shownFromCache = isLoad && gridAdapter.getData().size() > 0;
                        try {
                            Hawk.put(cacheKey(), GsonUtils.toJson(absXml.movie.videoList));
                        } catch (Exception ignore) {}
                        if (shownFromCache) {
                            // 用后台最新数据替换，避免旧缓存和第二页重叠
                            gridAdapter.setNewData(absXml.movie.videoList);
                        } else {
                            showSuccess();
                            isLoad = true;
                            gridAdapter.setNewData(absXml.movie.videoList);
                        }
                    } else {
                        // 加载更多：按影片 id 去重，避免分页边界数据重叠导致重复显示
                        List<Movie.Video> existing = gridAdapter.getData();
                        java.util.Set<String> seen = new java.util.HashSet<>();
                        for (Movie.Video v : existing) {
                            if (v != null && !TextUtils.isEmpty(v.id)) seen.add(v.id);
                        }
                        List<Movie.Video> fresh = new java.util.ArrayList<>();
                        for (Movie.Video v : absXml.movie.videoList) {
                            if (v != null && !TextUtils.isEmpty(v.id) && seen.add(v.id)) {
                                fresh.add(v);
                            }
                        }
                        if (!fresh.isEmpty()) gridAdapter.addData(fresh);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;

                    if (page > maxPage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                        if(page>2)Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if(page == 1){
                        showEmpty();
                    }else{
                        Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                        gridAdapter.loadMoreEnd();
                    }
                    gridAdapter.setEnableLoadMore(false);
                }
                mLoading = false;
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    private void initData() {
        initData(false);
    }

    /**
     * @param skipCache 是否跳过本地缓存直接请求。
     *                  筛选条件变化时必须跳过：缓存里是上一次（可能是无筛选）的结果，
     *                  先铺旧数据会让用户以为筛选没生效。
     */
    private void initData(boolean skipCache) {
        SourceBean homeBean = ApiConfig.get().getHomeSourceBean();
        if (homeBean == null || homeBean.getApi() == null) {// 系统杀死app恢复缓存的fragment后会直接getList,此时首页api都未加载完
            showEmpty();
            return;
        }
        isLoad = false;
        scrollTop();

        // 第一页先读本地缓存，立即显示上次内容，后台再发网络请求刷新
        if (page == 1 && !skipCache) {
            try {
                String cacheKey = cacheKey();
                String cacheJson = Hawk.get(cacheKey, "");
                if (!cacheJson.isEmpty()) {
                    List<Movie.Video> cached = GsonUtils.fromJson(cacheJson,
                            new TypeToken<List<Movie.Video>>(){}.getType());
                    if (cached != null && !cached.isEmpty()) {
                        showSuccess();
                        isLoad = true;
                        gridAdapter.setNewData(cached);
                    }
                }
            } catch (Exception ignore) {}
        }
        // 没有缓存数据时才显示加载动画，有缓存则后台静默刷新
        if (page == 1 && !isLoad) {
            showLoading();
        }
        mLoading = true;
        sourceViewModel.getList(sortData, page);
    }

    private String cacheKey() {
        try {
            SourceBean homeBean = ApiConfig.get().getHomeSourceBean();
            String sourceKey = homeBean == null ? "" : homeBean.getKey();
            StringBuilder key = new StringBuilder("grid_cache_").append(sourceKey)
                    .append('_').append(sortData != null ? sortData.id : "");
            // 筛选条件参与区分：否则筛选结果会被写进"无筛选"的缓存，
            // 下次进来（或筛选请求失败时）就用错误的数据铺列表
            if (sortData != null && sortData.filterSelect != null && !sortData.filterSelect.isEmpty()) {
                key.append('_').append(new java.util.TreeMap<>(sortData.filterSelect).toString().hashCode());
            }
            return key.toString();
        } catch (Exception e) {
            return "grid_cache_unknown";
        }
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        mGridView.scrollToPosition(0);
    }

    public void showFilter() {
        if (sortData == null || sortData.filters == null || sortData.filters.isEmpty()) {
            Toast.makeText(getContext(), "当前分类不支持筛选", Toast.LENGTH_SHORT).show();
            return;
        }
        if (gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
            gridFilterDialog.setOnDismiss(new GridFilterDialog.Callback() {
                @Override
                public void change() {
                    // 筛选条件已写入 sortData.filterSelect，重新拉第一页
                    applyFilter();
                }
            });
        }
        // 每次打开都按当前分类重建选项：既保证换了分类后选项不串，
        // 也能按 filterSelect 还原上一次的选中高亮
        gridFilterDialog.setData(sortData);
        gridFilterDialog.show();
    }

    /**
     * 筛选条件变化后重新加载。
     *
     * 这里必须清空旧列表并跳过本地缓存：
     * 缓存用的是筛选前的数据，先铺上去会让人以为"点了筛选没反应"。
     */
    private void applyFilter() {
        if (sortData == null) return;
        page = 1;
        maxPage = 1;
        isLoad = false;
        mLoading = false;
        if (gridAdapter != null) {
            gridAdapter.setNewData(new java.util.ArrayList<Movie.Video>());
            gridAdapter.setEnableLoadMore(true);
        }
        showLoading();
        initData(true);
    }
}
