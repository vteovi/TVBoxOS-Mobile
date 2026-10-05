package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.R
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.cache.VodCollect
import com.github.tvbox.osc.databinding.ActivityCollectBinding
import com.github.tvbox.osc.ui.adapter.CollectAdapter
import com.google.android.material.navigation.NavigationBarView
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectActivity : BaseVbActivity<ActivityCollectBinding>() {

    private var collectAdapter  = CollectAdapter()
    private var destinationAfterFinish: Int? = null

    override fun init() {
        initView()
        initData()
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

    private fun initView() {
        setLoadSir(mBinding.mGridView)

        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.setLayoutManager(GridLayoutManager(this, Utils.getPosterSpanCount(this)))
        mBinding.mGridView.setAdapter(collectAdapter)
        mBinding.titleBar.rightView.setOnClickListener {
            XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("提示", "确定清空?") {
                    showLoadingDialog()
                    lifecycleScope.launch(Dispatchers.IO){
                        RoomDataManger.deleteVodCollectAll()
                        withContext(Dispatchers.Main){
                            dismissLoadingDialog()
                            collectAdapter.setNewData(ArrayList())
                            mBinding.topTip.visibility = View.GONE
                            showEmpty()
                        }
                    }
                }.show()
        }
        collectAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                val vodInfo = collectAdapter.data[position]
                if (vodInfo != null) {
                    collectAdapter.remove(position)
                    RoomDataManger.deleteVodCollect(vodInfo.id)
                }
                if (collectAdapter.data.isEmpty()) {
                    mBinding.topTip.visibility = View.GONE
                }
                true
            }
        collectAdapter.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { adapter, view, position ->
                FastClickCheckUtil.check(view)
                val vodInfo = collectAdapter.data[position]
                if (vodInfo != null) {
                    if (ApiConfig.get().getSource(vodInfo.sourceKey) != null) {
                        val bundle = Bundle()
                        bundle.putString("id", vodInfo.vodId)
                        bundle.putString("sourceKey", vodInfo.sourceKey)
                        jumpActivity(DetailActivity::class.java, bundle)
                    } else {
//                            Intent newIntent = new Intent(mContext, SearchActivity.class);
                        val newIntent = Intent(mContext, FastSearchActivity::class.java)
                        newIntent.putExtra("title", vodInfo.name)
                        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(newIntent)
                    }
                }
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (mBinding.mGridView.layoutManager as? GridLayoutManager)?.spanCount = Utils.getPosterSpanCount(this)
    }

    private fun initData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allVodRecord = RoomDataManger.getAllVodCollect()
            val vodInfoList: MutableList<VodCollect> = ArrayList()
            for (vodInfo in allVodRecord) {
                vodInfoList.add(vodInfo)
            }
            withContext(Dispatchers.Main) {
                collectAdapter.setNewData(vodInfoList)
                if (vodInfoList.isNotEmpty()) {
                    showSuccess()
                    mBinding.topTip.visibility = View.VISIBLE
                }else{
                    showEmpty()
                }
            }
        }
    }
}
