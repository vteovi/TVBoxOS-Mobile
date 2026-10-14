package com.github.tvbox.osc.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.cache.VodCollect
import com.github.tvbox.osc.databinding.FragmentRecordListBinding
import com.github.tvbox.osc.ui.adapter.HistoryAdapter
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用列表 Fragment，type=0 收藏，type=1 历史记录
 */
class RecordListFragment : BaseVbFragment<FragmentRecordListBinding>() {

    private var adapter: HistoryAdapter? = null
    private var type = 0

    companion object {
        const val TYPE_COLLECT = 0
        const val TYPE_HISTORY = 1
        fun newInstance(type: Int): RecordListFragment {
            val f = RecordListFragment()
            f.arguments = Bundle().apply { putInt("type", type) }
            return f
        }
    }

    override fun init() {
        type = arguments?.getInt("type", TYPE_HISTORY) ?: TYPE_HISTORY
        adapter = HistoryAdapter()
        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.layoutManager = GridLayoutManager(mActivity, Utils.getPosterSpanCount(mActivity))
        mBinding.mGridView.adapter = adapter

        adapter!!.onItemClickListener = BaseQuickAdapter.OnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val vodInfo = adapter!!.data[position]
            val bundle = Bundle()
            bundle.putString("id", vodInfo.id)
            bundle.putString("sourceKey", vodInfo.sourceKey)
            jumpActivity(DetailActivity::class.java, bundle)
        }

        adapter!!.onItemLongClickListener = BaseQuickAdapter.OnItemLongClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val vodInfo = adapter!!.data[position]
            adapter!!.remove(position)
            when (type) {
                TYPE_COLLECT -> RoomDataManger.deleteVodCollect(vodInfo.sourceKey, vodInfo)
                TYPE_HISTORY -> RoomDataManger.deleteVodRecord(vodInfo.sourceKey, vodInfo)
            }
            true
        }

        loadData()
    }

    fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list: MutableList<VodInfo> = ArrayList()
            when (type) {
                TYPE_COLLECT -> {
                    val collects: List<VodCollect> = RoomDataManger.getAllVodCollect()
                    for (c in collects) {
                        val v = VodInfo()
                        v.id = c.vodId
                        v.sourceKey = c.sourceKey
                        v.name = c.name
                        v.pic = c.pic
                        list.add(v)
                    }
                }
                else -> {
                    val records = RoomDataManger.getAllVodRecord(200)
                    for (vodInfo in records) {
                        if (!vodInfo.playNote.isNullOrEmpty()) vodInfo.note = vodInfo.playNote
                        list.add(vodInfo)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                adapter?.setNewData(list)
            }
        }
    }
}
