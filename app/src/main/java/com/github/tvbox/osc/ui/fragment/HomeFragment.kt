package com.github.tvbox.osc.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import com.angcyo.tablayout.delegate.ViewPager1Delegate.Companion.install
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseLazyFragment
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.MovieSort.SortData
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.FragmentHomeBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.activity.SubscriptionActivity
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.LastViewedDialog
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.lxj.xpopup.XPopup
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : BaseVbFragment<FragmentHomeBinding>() {

    /**
     * 提供给主页返回操作
     */
    val tabIndex: Int
        get() = mBinding.tabLayout.currentItemIndex

    /**
     * 提供给主页返回操作
     */
    val allFragments: List<BaseLazyFragment>
        get() = fragments

    private var sourceViewModel: SourceViewModel? = null
    private val fragments: MutableList<BaseLazyFragment> = ArrayList()
    private val mHandler = Handler()

    /**
     * 顶部tabs分类集合,用于渲染tab页,每个tab对应fragment内的数据
     */
    private var mSortDataList: List<SortData> = ArrayList()
    private var dataInitOk = false
    private var jarInitOk = false

    /**
     * 分类结果兜底: 分类接口超时/卡死时, 至少把首页渲染出来(只剩"主页"栏),
     * 避免整页空白、"分栏一个都没有"。
     */
    private val sortTimeoutTask = Runnable {
        if (mSortDataList.isEmpty()) {
            sourceViewModel?.sortResult?.postValue(null)
        }
    }

    var errorTipDialog: TipDialog? = null

    /**
     * true: 配置变更重载
     * false: 全部重载(api变更、重启app等)
     */
    var onlyConfigChanged = false

    override fun init() {
        ControlManager.get().startServer()
        mBinding.nameContainer.setOnClickListener {
            if (dataInitOk && jarInitOk) {
                showSiteSwitch()
            } else {
                ToastUtils.showShort("数据源未加载，长按刷新或切换订阅")
            }
        }
        mBinding.nameContainer.setOnLongClickListener {
            refreshHomeSources()
            true
        }
        mBinding.search.setOnClickListener {
            jumpActivity(FastSearchActivity::class.java)
        }
        setLoadSir(mBinding.contentLayout)
        initViewModel()
        initData()
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel?.sortResult?.observe(this) { absXml: AbsSortXml? ->
            mHandler.removeCallbacks(sortTimeoutTask)
            showSuccess()
            val oldSortIds = mSortDataList.map { it.id }
            val newSortList =
                if (absXml?.classes != null && absXml.classes.sortList != null) {
                    DefaultConfig.adjustSort(
                        ApiConfig.get().homeSourceBean.key,
                        absXml.classes.sortList,
                        true
                    )
                } else {
                    DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true)
                }
            // 缓存分栏列表，下次打开立即渲染
            try {
                val cacheKey = "sort_cache_" + ApiConfig.get().homeSourceBean.key
                Hawk.put(cacheKey, com.blankj.utilcode.util.GsonUtils.toJson(newSortList))
                if (absXml?.videoList != null) {
                    Hawk.put(cacheKey + "_rec", com.blankj.utilcode.util.GsonUtils.toJson(absXml.videoList))
                }
            } catch (ignore: Exception) {}

            val newSortIds = newSortList.map { it.id }
            val alreadyRendered = fragments.isNotEmpty()
            // 分类"集合"一致就认为结构没变：顺序不同也不重建。
            // 重建会清空全部分栏（Fragment 重建、列表回到顶部并闪一下），而用户此刻
            // 很可能正在某个分栏里浏览——纯粹的排序差别不值得这个代价。
            val sameIdSet = oldSortIds.size == newSortIds.size && oldSortIds.toSet() == newSortIds.toSet()
            val keepFragments = alreadyRendered && sameIdSet
            if (!keepFragments) {
                mSortDataList = newSortList
            } else if (oldSortIds == newSortIds) {
                // 顺序也一致：安全地换成最新对象（分类名、筛选项等可能有更新）
                mSortDataList = newSortList
            }
            if (!keepFragments) {
                // 记住用户原来停在哪一栏：分类集合变化会重建整个 ViewPager，
                // 不还原的话正在浏览的分类会突然跳回第一栏（连带列表重新加载、闪一下）。
                // 注意 mSortDataList 这时已经被换成新列表了，要用旧的 id 列表去查
                val tabIndex = mBinding.tabLayout.currentItemIndex
                val closedHome = Hawk.get(HawkConfig.HOME_REC, 0) == 2
                mRestoreTabId = oldSortIds.getOrNull(if (closedHome) tabIndex + 1 else tabIndex)
                initViewPager(absXml)
            } else {
                // 分栏结构没变不重建：只把最新的站点推荐数据存给主页 UserFragment，
                // 不主动 refresh —— refresh 会 setNewData 整表替换，用户此刻很可能正在
                // 主页栏里浏览（它默认就是第一栏），列表会闪一下、位置也会被拽走。
                // 新推荐会在下次重建分栏 / 重新进入时自然生效。
                if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && absXml?.videoList != null && absXml.videoList.isNotEmpty()) {
                    for (f in fragments) {
                        if (f is com.github.tvbox.osc.ui.fragment.UserFragment) {
                            f.setHomeSourceRec(absXml.videoList)
                            break
                        }
                    }
                }
            }
        }
    }

    private fun initData() {
        val mainActivity = mActivity as MainActivity
        onlyConfigChanged = mainActivity.useCacheConfig

        val home = ApiConfig.get().homeSourceBean
        if (home != null && !home.name.isNullOrEmpty()) {
            mBinding.tvName.text = home.name
            mBinding.tvName.postDelayed({ mBinding.tvName.isSelected = true }, 2000)
        }

        // 已经渲染过分栏时不要再切到 loading 页：LoadSir 会把整块 contentLayout
        // （连同 ViewPager 里所有列表）从布局树上摘下来，正在看的列表会闪一下并回顶部
        if (fragments.isEmpty()) showLoading()
        when{
            dataInitOk && jarInitOk -> {
                //正常初始化会先加载,最终到这,此时数据有以下几种情况
                // 1. api/jar/spider等均加载完,正常显示数据。2. 缺失spider(存疑?)/api配置有问题同样加载(最后空布局 或 只有豆瓣首页)

                // 先读本地缓存的分栏列表，立即渲染，不转圈；后台再拉新数据刷新
                val cacheKey = "sort_cache_" + ApiConfig.get().homeSourceBean.key
                val cacheJson = Hawk.get(cacheKey, "")
                if (!cacheJson.isEmpty() && mSortDataList.isEmpty()) {
                    try {
                        val cachedList: ArrayList<SortData>? = com.blankj.utilcode.util.GsonUtils.fromJson(
                            cacheJson,
                            object : com.google.gson.reflect.TypeToken<ArrayList<SortData>>() {}.type
                        )
                        if (cachedList != null && cachedList.isNotEmpty()) {
                            mSortDataList = cachedList
                            // 读缓存的主页推荐数据
                            val recJson = Hawk.get(cacheKey + "_rec", "")
                            val recList: ArrayList<com.github.tvbox.osc.bean.Movie.Video>? = if (recJson.isEmpty()) null else
                                com.blankj.utilcode.util.GsonUtils.fromJson(
                                    recJson,
                                    object : com.google.gson.reflect.TypeToken<ArrayList<com.github.tvbox.osc.bean.Movie.Video>>() {}.type
                                )
                            val fakeXml = AbsSortXml()
                            if (recList != null) fakeXml.videoList = recList
                            showSuccess()
                            initViewPager(fakeXml)
                        }
                    } catch (ignore: Exception) {}
                }

                sourceViewModel?.getSort(ApiConfig.get().homeSourceBean.key)
                // 分类还没渲染出来时上一道保险, 超时就直接渲染"主页"栏
                if (mSortDataList.isEmpty()) {
                    mHandler.removeCallbacks(sortTimeoutTask)
                    mHandler.postDelayed(sortTimeoutTask, 20000L)
                }
            }
            dataInitOk && !jarInitOk -> {
                loadJar()
            }
            else -> {
                loadConfig()
            }
        }
    }

    private fun loadConfig(){
        ApiConfig.get().loadConfig(onlyConfigChanged, object : LoadConfigCallback {

            override fun retry() {
                mHandler.post { initData() }
            }

            override fun success() {
                dataInitOk = true
                if (ApiConfig.get().spider.isEmpty()) {
                    jarInitOk = true
                }
                mHandler.postDelayed({ initData() }, 50)
            }

            override fun error(msg: String) {
                if (msg.equals("-1", ignoreCase = true)) {
                    mHandler.post {
                        dataInitOk = true
                        jarInitOk = true
                        initData()
                    }
                } else {
                    showTipDialog(msg)
                }
            }
        }, activity)
    }

    private fun loadJar(){
        if (!ApiConfig.get().spider.isNullOrEmpty()) {
            ApiConfig.get().loadJar(
                onlyConfigChanged,
                ApiConfig.get().spider,
                object : LoadConfigCallback {
                    override fun success() {
                        jarInitOk = true
                        mHandler.postDelayed({
                            if (!onlyConfigChanged) {
                                queryHistory()
                            }
                            initData()
                        }, 50)
                    }

                    override fun retry() {}
                    override fun error(msg: String) {
                        jarInitOk = true
                        mHandler.post {
                            ToastUtils.showShort("更新订阅失败")
                            initData()
                        }
                    }
                })
        }
    }

    private fun showTipDialog(msg: String) {
        if (errorTipDialog == null) {
            errorTipDialog =
                TipDialog(requireActivity(), msg, "重试", "取消", object : TipDialog.OnListener {
                    override fun left() {
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun right() {
                        dataInitOk = true
                        jarInitOk = true
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun cancel() {
                        dataInitOk = true
                        jarInitOk = true
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun onTitleClick() {
                        errorTipDialog?.hide()
                        jumpActivity(SubscriptionActivity::class.java)
                        requireActivity().overridePendingTransition(0, 0)
                    }
                })
        }
        if (!errorTipDialog!!.isShowing) errorTipDialog!!.show()
    }

    private fun getTabTextView(text: String): TextView {
        val textView = TextView(mContext)
        textView.text = text
        textView.gravity = Gravity.CENTER
        textView.setPadding(
            ConvertUtils.dp2px(20f),
            ConvertUtils.dp2px(10f),
            ConvertUtils.dp2px(5f),
            ConvertUtils.dp2px(10f)
        )
        return textView
    }

    /** 重建分栏后要还原到哪一栏（按分类 id 记，分类增删也找得到） */
    private var mRestoreTabId: String? = null

    private fun initViewPager(absXml: AbsSortXml?) {
        val restoreTabId = mRestoreTabId
        mRestoreTabId = null
        if (mSortDataList.isNotEmpty()) {
            mBinding.tabLayout.removeAllViews()
            fragments.clear()
            for (data in mSortDataList) {
                mBinding.tabLayout.addView(getTabTextView(data.name))
                if (data.id == "my0") { //tab是主页,添加主页fragment 根据设置项显示豆瓣热门/站点推荐(每个源不一样)/历史记录
                    if (Hawk.get(
                            HawkConfig.HOME_REC,
                            0
                        ) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size > 0
                    ) { //站点推荐
                        fragments.add(UserFragment.newInstance(absXml.videoList))
                    } else { //豆瓣热门/历史记录
                        fragments.add(UserFragment.newInstance(null))
                    }
                } else { //来自源的分类
                    fragments.add(GridFragment.newInstance(data))
                }
            }
            if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) { //关闭主页
                mBinding.tabLayout.removeViewAt(0)
                fragments.removeAt(0)
            }

            //重新渲染vp
            mBinding.mViewPager.adapter =
                object : FragmentStatePagerAdapter(getChildFragmentManager()) {
                    override fun getItem(position: Int): Fragment {
                        return fragments[position]
                    }

                    override fun getCount(): Int {
                        return fragments.size
                    }
                }
            //tab和vp绑定
            install(mBinding.mViewPager, mBinding.tabLayout, true)

            // 分栏被重建时回到用户原来那一栏（id 还在的话）
            if (!restoreTabId.isNullOrEmpty()) {
                var index = mSortDataList.indexOfFirst { it.id == restoreTabId }
                // 「关闭主页」时第一栏已被移除，索引整体前移一位
                if (index > 0 && Hawk.get(HawkConfig.HOME_REC, 0) == 2) index -= 1
                if (index in fragments.indices) {
                    mBinding.mViewPager.setCurrentItem(index, false)
                }
            }
        }
    }

    /**
     * 提供给主页返回操作
     */
    fun scrollToFirstTab(): Boolean {
        return if (mBinding.tabLayout.currentItemIndex != 0) {
            mBinding.mViewPager.setCurrentItem(0, false)
            true
        } else {
            false
        }
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacksAndMessages(null)
    }

    private fun showSiteSwitch() {
        val sites = ApiConfig.get().sourceBeanList
        if (sites.size > 0) {
            val dialog = SelectDialog<SourceBean>(requireActivity())
            val tvRecyclerView = dialog.findViewById<TvRecyclerView>(R.id.list)
            tvRecyclerView.setLayoutManager(V7GridLayoutManager(dialog.context, 2))
            dialog.setTip("请选择首页数据源")
            dialog.setAdapter(object : SelectDialogInterface<SourceBean?> {
                override fun click(value: SourceBean?, pos: Int) {
                    ApiConfig.get().setSourceBean(value)
                    refreshHomeSources()
                }

                override fun getDisplay(source: SourceBean?): String {
                    return if (source == null) "" else source.name
                }
            }, object : DiffUtil.ItemCallback<SourceBean>() {
                override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                    return oldItem === newItem
                }

                override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean): Boolean {
                    return oldItem.key.contentEquals(newItem.key)
                }
            }, sites, sites.indexOf(ApiConfig.get().homeSourceBean))
            dialog.show()
        } else {
            ToastUtils.showLong("暂无可用数据源")
        }
    }

    private fun refreshHomeSources() {
        val intent = Intent(App.getInstance(), MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val bundle = Bundle()
        bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ControlManager.get().stopServer()
    }

    private fun queryHistory() {
        lifecycleScope.launch {
            val vodInfoList = withContext(Dispatchers.IO) {
                val allVodRecord = RoomDataManger.getAllVodRecord(100)
                val vodInfoList: MutableList<VodInfo?> = ArrayList()
                for (vodInfo in allVodRecord) {
                    if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) vodInfo.note =
                        vodInfo.playNote
                    vodInfoList.add(vodInfo)
                }
                vodInfoList
            }

            // 查询完成后更新UI
            if (vodInfoList.isNotEmpty() && vodInfoList[0] != null) {
                XPopup.Builder(context)
                    .hasShadowBg(false)
                    .isDestroyOnDismiss(true)
                    .isCenterHorizontal(true)
                    .isTouchThrough(true)
                    .offsetY(ScreenUtils.getAppScreenHeight() - 360)
                    .asCustom(LastViewedDialog(requireContext(), vodInfoList[0]))
                    .show()
                    .delayDismiss(4000)
            }
        }
    }
}
