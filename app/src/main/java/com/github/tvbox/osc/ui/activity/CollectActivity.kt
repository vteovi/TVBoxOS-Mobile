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

    override fun init() {
        mBinding.viewPager!!.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        TabLayoutMediator(mBinding.tabLayout!!, mBinding.viewPager!!) { tab, position ->
            tab.text = tabs[position]
        }.attach()

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
                            (fragments[pos] as RecordListFragment).loadData()
                        }
                    }
                }.show()
        }

        initBottomNavigation()
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
