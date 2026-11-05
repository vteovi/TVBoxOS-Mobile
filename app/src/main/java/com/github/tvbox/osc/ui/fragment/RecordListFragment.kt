package com.github.tvbox.osc.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.cache.VodCollect
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.databinding.FragmentRecordListBinding
import com.github.tvbox.osc.ui.adapter.CollectAdapter
import com.github.tvbox.osc.ui.adapter.HistoryAdapter
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用列表 Fragment，type=0 收藏，type=1 历史记录
 */
class RecordListFragment : BaseVbFragment<FragmentRecordListBinding>() {

    private var collectAdapter: CollectAdapter? = null
    private var historyAdapter: HistoryAdapter? = null
    private var type = 0

    /** 列表数据已加载完成（区分「还没查」和「查到了 0 条」） */
    private var loaded = false

    /** 条数变化回调，交给宿主页面刷新 Tab 上的数量 */
    private var onCountChanged: ((Int) -> Unit)? = null

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
        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.layoutManager = GridLayoutManager(mActivity, Utils.getPosterSpanCount(mActivity))

        if (type == TYPE_COLLECT) {
            collectAdapter = CollectAdapter()
            mBinding.mGridView.adapter = collectAdapter
            collectAdapter!!.onItemClickListener = BaseQuickAdapter.OnItemClickListener { _, view, position ->
                FastClickCheckUtil.check(view)
                val item = collectAdapter!!.data[position]
                if (ApiConfig.get().getSource(item.sourceKey) != null) {
                    val bundle = Bundle()
                    bundle.putString("id", item.vodId)
                    bundle.putString("sourceKey", item.sourceKey)
                    jumpActivity(DetailActivity::class.java, bundle)
                } else {
                    val intent = android.content.Intent(mContext, FastSearchActivity::class.java)
                    intent.putExtra("title", item.name)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                }
            }
            collectAdapter!!.onItemLongClickListener = BaseQuickAdapter.OnItemLongClickListener { _, view, position ->
                FastClickCheckUtil.check(view)
                val item = collectAdapter!!.data[position]
                collectAdapter!!.remove(position)
                RoomDataManger.deleteVodCollect(item.id)
                notifyCount()
                true
            }
        } else {
            historyAdapter = HistoryAdapter()
            mBinding.mGridView.adapter = historyAdapter
            historyAdapter!!.onItemClickListener = BaseQuickAdapter.OnItemClickListener { _, view, position ->
                FastClickCheckUtil.check(view)
                val vodInfo = historyAdapter!!.data[position]
                val bundle = Bundle()
                bundle.putString("id", vodInfo.id)
                bundle.putString("sourceKey", vodInfo.sourceKey)
                jumpActivity(DetailActivity::class.java, bundle)
            }
            historyAdapter!!.onItemLongClickListener = BaseQuickAdapter.OnItemLongClickListener { _, view, position ->
                FastClickCheckUtil.check(view)
                val vodInfo = historyAdapter!!.data[position]
                historyAdapter!!.remove(position)
                RoomDataManger.deleteVodRecord(vodInfo.sourceKey, vodInfo)
                notifyCount()
                true
            }
        }

        loadData()
    }

    /**
     * 设置条数回调。如果数据已经加载过，会立刻回传一次当前条数，
     * 避免宿主设置回调的时机晚于本次加载而拿不到数量。
     */
    fun setOnCountChangedListener(listener: (Int) -> Unit) {
        onCountChanged = listener
        if (loaded) notifyCount()
    }

    private fun notifyCount() {
        val count = when (type) {
            TYPE_COLLECT -> collectAdapter?.data?.size
            else -> historyAdapter?.data?.size
        }
        if (count != null) onCountChanged?.invoke(count)
    }

    fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (type == TYPE_COLLECT) {
                val list = RoomDataManger.getAllVodCollect()
                withContext(Dispatchers.Main) {
                    collectAdapter?.setNewData(list)
                    loaded = true
                    notifyCount()
                }
            } else {
                val records = RoomDataManger.getAllVodRecord(200)
                for (vodInfo in records) {
                    if (!vodInfo.playNote.isNullOrEmpty()) vodInfo.note = vodInfo.playNote
                }
                withContext(Dispatchers.Main) {
                    historyAdapter?.setNewData(records)
                    loaded = true
                    notifyCount()
                }
            }
        }
    }
}
