package com.github.tvbox.osc.ui.activity

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.databinding.ActivityHistoryBinding
import com.github.tvbox.osc.ui.fragment.RecordListFragment
import com.github.tvbox.osc.util.Utils
import com.google.android.material.tabs.TabLayoutMediator
import com.lxj.xpopup.XPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : BaseVbActivity<ActivityHistoryBinding>() {

    private val tabs = listOf("收藏", "历史")
    private val fragments = listOf(
        RecordListFragment.newInstance(RecordListFragment.TYPE_COLLECT),
        RecordListFragment.newInstance(RecordListFragment.TYPE_HISTORY)
    )

    override fun init() {
        mBinding.viewPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        TabLayoutMediator(mBinding.tabLayout, mBinding.viewPager) { tab, position ->
            tab.text = tabs[position]
        }.attach()

        mBinding.titleBar.rightView.setOnClickListener {
            val pos = mBinding.viewPager.currentItem
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
    }
}
