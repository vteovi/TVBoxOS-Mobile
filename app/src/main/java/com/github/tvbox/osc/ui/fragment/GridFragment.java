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
import androidx.recyclerview.widget.GridLayoutManager;
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
        // 分栏滑走较远时会被 FragmentStatePagerAdapter 销毁、返回时复用同一实例重建，
        // 但此时 init() 不会再执行，必须在这里把列表订阅补挂回去（首次创建时 sourceViewModel 还是 null，会跳过）。
        bindListResult();
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
        // 加大复用池：快速滑动时新出现的卡片优先从池里取（只需 bind，不用 inflate），
        // 默认池只有 5 个，一屏十几张卡片时会频繁新建 view 造成掉帧
        try {
            mGridView.getRecycledViewPool()
                    .setMaxRecycledViews(0, Math.min(Math.max(8, spanCount * 2), 14));
        } catch (Throwable ignore) {
        }
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
                requestList();
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
                    // 影片所属数据源已经不存在（切过订阅/本地配置，列表上还是旧数据）：
                    // 进详情只会拿到一片空白，直接用标题走聚合搜索，去其它源里找同名影片
                    boolean sourceAlive = !TextUtils.isEmpty(video.sourceKey)
                            && ApiConfig.get().getSource(video.sourceKey) != null;
                    if (("12".indexOf(getUITag()) != -1)
                            && ("folder".equals(video.tag) || "cover".equals(video.tag))) {
                        focusedView = view;
                        changeView(video.id, "folder".equals(video.tag));
                    } else if (!sourceAlive
                            || (homeSourceBean.isQuickSearch() && Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) && enableFastSearch())
                            || TextUtils.isEmpty(video.id) || video.id.startsWith("msearch:")) {
                        jumpActivity(FastSearchActivity.class, bundle);
                    } else {
                        jumpActivity(DetailActivity.class, bundle);
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
        // 分栏被销毁重建后 view 可能重新走了一遍创建流程，这里确保列表仍然绑着 adapter/LayoutManager
        if (mGridView != null && gridAdapter != null) {
            if (mGridView.getAdapter() != gridAdapter) {
                mGridView.setAdapter(gridAdapter);
            }
            if (mGridView.getLayoutManager() == null) {
                applyGridLayoutManager();
            }
        }
        // 从其他分栏切回来时，兜底恢复加载状态：
        //  - 请求还在路上（或分栏被销毁期间回来了，等重建后由 LiveData 补投）→ 继续等，别打断
        //  - 已经超时（回调丢失）→ 复位，让用户还能继续上滑加载
        // 已到底（page > maxPage）时不要把"加载更多"重新打开，否则会白请求一个越界页
        boolean requestLost = mWaitingList
                && android.os.SystemClock.elapsedRealtime() - mListReqAt > 20000;
        if (requestLost || (mLoading && !mWaitingList)) {
            mWaitingList = false;
            mLoading = false;
            if (mGridView != null) mGridView.removeCallbacks(mListTimeoutRunnable);
            resetLoadMoreState();
        }
        // 分栏重新可见时给自动补页一次新的额度：重建过 view 后列表可能又变成"滑不动"
        if (mAutoFillCount >= 3) mAutoFillCount = 0;
        // 分栏切回来时补一次「是否满屏」检查：旋屏后列数变化也可能又变成滑不动
        scheduleAutoFill();
    }

    /** 按当前页码恢复"加载更多"的可用状态（到底了就保持关闭） */
    private void resetLoadMoreState() {
        if (gridAdapter == null) return;
        if (page > maxPage) {
            gridAdapter.loadMoreEnd();
            gridAdapter.setEnableLoadMore(false);
        } else {
            gridAdapter.loadMoreComplete();
            gridAdapter.setEnableLoadMore(true);
        }
    }

    /**
     * 已发出列表请求、正在等回调。
     *
     * <p>首页分栏用 FragmentStatePagerAdapter(offscreenPageLimit 默认 1)：滑过好几个分栏后，
     * 前面的分栏会被销毁、返回时复用同一个 Fragment 实例重建视图。重建后 LiveData 在新订阅者
     * 注册时会<b>重放最后一次值</b>。重放值如果被当成新的一页处理，会 page++ 越过 maxPage
     * 直接 setEnableLoadMore(false)；如果重放的是上一次的失败结果(null)，还会弹"没有更多了"
     * 并永久关掉加载更多——表现就是"加载到一定程度后上滑再也不加载，只能重启"。
     * 用"有没有在等回调"来判定：没在等就是重放，直接丢弃。
     */
    private boolean mWaitingList = false;
    /** 本次列表请求的发起时刻，用于判断回调是否已经丢失 */
    private long mListReqAt = 0;

    /** 列表结果订阅：必须是字段，Fragment 被销毁重建后要能重新注册同一个 observer */
    private final Observer<AbsXml> mListObserver = new Observer<AbsXml>() {
        @Override
        public void onChanged(AbsXml absXml) {
            if (!mWaitingList) return; // LiveData 重放/迟到回调，丢弃
            mWaitingList = false;
            if (mGridView != null) mGridView.removeCallbacks(mListTimeoutRunnable);
            handleListResult(absXml);
        }
    };

    /**
     * 重新挂上列表订阅。
     *
     * <p>Fragment 生命周期被销毁时，observe(this) 注册的观察者会被自动移除；
     * 而复用实例重建时 init() 不会再执行（mIsFirstVisible 已是 false），
     * 所以必须在每次 onCreate 时补挂一次，否则所有 getList 响应都无人接收，
     * 加载更多会永久卡死（只能重启 App）。
     */
    private void bindListResult() {
        if (sourceViewModel == null) return;
        sourceViewModel.listResult.observe(this, mListObserver);
    }

    private void initViewModel() {
        if(sourceViewModel != null) { return;}
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        bindListResult();
    }

    /** 发起一次当前分类的列表请求（page 由调用方维护），统一登记"正在等回调" */
    private void requestList() {
        if (sourceViewModel == null) return;
        mWaitingList = true;
        mListReqAt = android.os.SystemClock.elapsedRealtime();
        mLoading = true;
        scheduleListTimeout();
        sourceViewModel.getList(sortData, page);
    }

    /**
     * 请求超时兜底：网络超时是 10s，20s 还没回调说明这次回调丢了（请求被取消/异常分支漏发）。
     * 不复位的话"加载更多"会永远停在 loading，用户怎么上滑都不再加载，只能重启 App。
     */
    private void scheduleListTimeout() {
        if (mGridView == null) return;
        mGridView.removeCallbacks(mListTimeoutRunnable);
        mGridView.postDelayed(mListTimeoutRunnable, 20000);
    }

    private final Runnable mListTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mWaitingList) return;
            if (android.os.SystemClock.elapsedRealtime() - mListReqAt < 20000) {
                if (mGridView != null) mGridView.postDelayed(this, 20000);
                return;
            }
            mWaitingList = false;
            mLoading = false;
            resetLoadMoreState();
            scheduleAutoFill();
        }
    };

    private void handleListResult(AbsXml absXml) {
        // 自动补页是后台行为，不该弹「没有更多了」打扰用户
        boolean autoFill = mAutoFilling;
        mAutoFilling = false;
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            if (page == 1) {
                // 如果已经从本地缓存显示过数据，后台返回时更新缓存文件；
                // 同时静默替换列表数据，避免缓存旧数据和第二页新数据重叠导致重复
                boolean shownFromCache = isLoad && gridAdapter.getData().size() > 0;
                try {
                    Hawk.put(cacheKey(), GsonUtils.toJson(absXml.movie.videoList));
                } catch (Exception ignore) {}
                if (shownFromCache) {
                    // 内容没变就别整表替换：setNewData 会让 RecyclerView 重新布局并
                    // 丢掉当前浏览位置，表现就是正在滑动的列表“内容突然往下掉一截”。
                    if (!sameVideoIds(gridAdapter.getData(), absXml.movie.videoList)) {
                        replaceDataKeepingScroll(absXml.movie.videoList);
                    }
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
                if(page>2 && !autoFill)Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
            } else {
                gridAdapter.loadMoreComplete();
                gridAdapter.setEnableLoadMore(true);
            }
        } else {
            if(page == 1){
                showEmpty();
            }else{
                if (!autoFill) {
                    Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                }
                gridAdapter.loadMoreEnd();
            }
            gridAdapter.setEnableLoadMore(false);
        }
        mLoading = false;
        scheduleAutoFill();
    }

    /** 连续自动补页的次数上限：避免「源本身每页只给一两条」时把整个目录都拉下来 */
    private int mAutoFillCount = 0;
    /** 当前这次请求是否是自动补页发起的（用于抑制额外的提示） */
    private boolean mAutoFilling = false;

    private final Runnable mAutoFillRunnable = new Runnable() {
        @Override
        public void run() {
            autoFillViewport();
        }
    };

    /** 数据落盘后检查一下是否满屏（延后一点，等 RecyclerView 完成这一轮布局） */
    private void scheduleAutoFill() {
        if (mGridView == null) return;
        mGridView.removeCallbacks(mAutoFillRunnable);
        mGridView.postDelayed(mAutoFillRunnable, 90);
    }

    /**
     * 大屏上「一页数据」经常还填不满一屏：这时候列表没有可滚动余量，
     * 上拉加载（滚动到最后一个才触发）永远触发不了，用户看到的就是「滑不动」，
     * 手指滑动只会出现过卷拉伸效果（内容被拽下去一截又弹回来）。
     * 这里在列表仍不能滚动时自动补下一页，直到能滚起来为止。
     */
    private void autoFillViewport() {
        if (mGridView == null || gridAdapter == null || sortData == null) return;
        if (mLoading || !isLoad) return;
        if (gridAdapter.getData().isEmpty()) return;
        if (page > maxPage) return;
        if (mAutoFillCount >= 3) return;
        if (mGridView.canScrollVertically(1) || mGridView.canScrollVertically(-1)) return;
        mAutoFillCount++;
        mAutoFilling = true;
        requestList();
    }

    /** 两个列表的影片 id（顺序也一致）完全相同，用于跳过无意义的整表刷新 */
    private static boolean sameVideoIds(List<Movie.Video> a, List<Movie.Video> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Movie.Video x = a.get(i), y = b.get(i);
            if (x == null || y == null) return false;
            if (!TextUtils.equals(x.id, y.id)) return false;
        }
        return true;
    }

    /**
     * 整表替换但保持当前浏览位置：后台刷新返回时用户可能已经在滑动，
     * 直接 setNewData 会让 RecyclerView 重新布局并把列表拽回顶部。
     */
    private void replaceDataKeepingScroll(List<Movie.Video> data) {
        int firstPos = -1, offset = 0;
        RecyclerView.LayoutManager lm = mGridView.getLayoutManager();
        if (lm instanceof GridLayoutManager && mGridView.getChildCount() > 0) {
            firstPos = ((GridLayoutManager) lm).findFirstVisibleItemPosition();
            View first = lm.findViewByPosition(firstPos);
            if (first != null) offset = first.getTop() - mGridView.getPaddingTop();
        }
        gridAdapter.setNewData(data);
        if (firstPos > 0 && lm instanceof GridLayoutManager && !data.isEmpty()) {
            ((GridLayoutManager) lm).scrollToPositionWithOffset(
                    Math.min(firstPos, data.size() - 1), offset);
        }
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
        mAutoFillCount = 0;
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
        requestList();
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
