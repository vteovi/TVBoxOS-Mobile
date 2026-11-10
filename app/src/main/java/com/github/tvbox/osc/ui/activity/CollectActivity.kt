package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.databinding.ActivityCollectBinding
import com.github.tvbox.osc.ui.fragment.RecordListFragment
import com.github.tvbox.osc.util.Utils
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.tabs.TabLayoutMediator
import com.lxj.xpopup.XPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectActivity : BaseVbActivity<ActivityCollectBinding>() {

    private val tabs = listOf("收藏", "历史记录")
    private val fragments = listOf(
        RecordListFragment.newInstance(RecordListFragment.TYPE_COLLECT),
        RecordListFragment.newInstance(RecordListFragment.TYPE_HISTORY)
    )
    private var destinationAfterFinish: Int? = null

    /** 两个 Tab 对应的条目数，-1 表示还没查出来（先只显示标题） */
    private val counts = IntArray(2) { -1 }

    override fun init() {
        bindTabCounts()
        mBinding.viewPager!!.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        TabLayoutMediator(mBinding.tabLayout!!, mBinding.viewPager!!) { tab, position ->
            tab.text = tabText(position)
        }.attach()

        // ViewPager2 是懒创建：没滑到的那个分栏 Fragment 还没 init()，它的条数回调也就不会来，
        // 所以「历史记录」在滑过去之前一直光秃秃没有数字。这里进页面就先各查一次。
        preloadCounts()

        mBinding.titleBar.rightView.setOnClickListener {
            val pos = mBinding.viewPager!!.currentItem
            val title = if (pos == 0) "收藏" else "历史记录"
            XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("提示", "确定清空$title?") {
                    showLoadingDialog()
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (pos == 0) RoomDataManger.deleteVodCollectAll()
                        else RoomDataManger.deleteVodRecordAll()
                        withContext(Dispatchers.Main) {
                            dismissLoadingDialog()
                            fragments[pos].loadData()
                        }
                    }
                }.show()
        }

        initBottomNavigation()
    }

    /**
     * 接上两个列表的条数回调：列表首次加载完成、删除单条、清空之后都会回传，
     * 这里把数字写到对应 Tab 的标题上（如「收藏 12」「历史记录 35」）。
     */
    private fun bindTabCounts() {
        fragments.forEachIndexed { index, fragment ->
            fragment.setOnCountChangedListener { count ->
                counts[index] = count
                mBinding.tabLayout?.getTabAt(index)?.text = tabText(index)
            }
        }
    }

    /**
     * 预先查一次两个列表的条数，让「还没滑过去」的分栏也能立刻显示数字。
     *
     * 口径必须和列表一致：收藏 = `getAllVodCollect().size`，
     * 历史 = `getAllVodRecord(HISTORY_LIMIT).size`（该方法会先按 `HISTORY_NUM` 裁剪再取条数，
     * 不能图省事用 DAO 的 count，否则会和列表实际条数对不上）。
     * 已经由列表回传过真实条数的，不要用预查值覆盖。
     */
    private fun preloadCounts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val collectCount = RoomDataManger.getAllVodCollect().size
            val historyCount = RoomDataManger.getAllVodRecord(RecordListFragment.HISTORY_LIMIT).size
            withContext(Dispatchers.Main) {
                if (counts[0] < 0) counts[0] = collectCount
                if (counts[1] < 0) counts[1] = historyCount
                for (i in tabs.indices) {
                    mBinding.tabLayout?.getTabAt(i)?.text = tabText(i)
                }
            }
        }
    }

    private fun tabText(position: Int): String {
        val count = counts[position]
        return if (count < 0) tabs[position] else "${tabs[position]} $count"
    }

    private fun initBottomNavigation() {
        val bottomNavigation = findViewById<NavigationBarView>(R.id.bottom_nav)
        bottomNavigation.selectedItemId = R.id.navigation_collect
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_collect -> true
                R.id.navigation_home,
                R.id.navigation_dashboard -> {
                    destinationAfterFinish = item.itemId
                    finish()
                    false
                }
                R.id.navigation_subscription -> {
                    startActivity(Intent(this, SubscriptionActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    false
                }
                R.id.navigation_live -> {
                    startActivity(Intent(this, LiveActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    false
                }
                R.id.navigation_setting -> {
                    startActivity(Intent(this, SettingActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    false
                }
                else -> false
            }
        }
    }

    override fun finish() {
        val destination = destinationAfterFinish
        if (destination != null) {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra(MainActivity.EXTRA_START_DESTINATION, destination)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
        super.finish()
    }
}
