package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>() {

    companion object {
        const val EXTRA_START_DESTINATION = "main_start_destination"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }

    private val fragments = listOf(HomeFragment(), MyFragment())
    private lateinit var bottomNavigation: BottomNavigationView
    var useCacheConfig = false
    private var exitTime = 0L

    override fun init() {
        // 原 SplashActivity 的职责迁移到此处: 标记正常启动, 避免 BaseActivity.onCreate 触发 relaunchApp。
        App.getInstance().isNormalStart = true

        // 原 SplashActivity 在 Android13+ 请求通知权限, 现移到首页入口处理。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !XXPermissions.isGranted(this, POST_NOTIFICATIONS_PERMISSION)
        ) {
            XXPermissions.with(this)
                .permission(POST_NOTIFICATIONS_PERMISSION)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: List<String>, all: Boolean) {
                    }

                    override fun onDenied(permissions: List<String>, never: Boolean) {
                    }
                })
        }

        useCacheConfig = intent.extras?.getBoolean(IntentKey.CACHE_CONFIG_CHANGED, false) ?: false

        mBinding.vp.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size

            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        // 关闭"首页<->我的"整页左右滑动切换:
        // 否则在首页内滑动分栏时会被外层ViewPager2抢走手势, 一滑就跳到"我的"。
        // 关掉后, 横向滑动交回 HomeFragment 内部的分栏(ViewPager)处理;
        // 切到"我的"仍可通过底部导航按钮, 且底部导航的 setCurrentItem 不受影响。
        mBinding.vp.isUserInputEnabled = false

        bottomNavigation = findViewById(R.id.bottom_nav)
        bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.navigation_home -> {
                    mBinding.vp.setCurrentItem(0, false)
                    true
                }
                R.id.navigation_dashboard -> {
                    mBinding.vp.setCurrentItem(1, false)
                    true
                }
                R.id.navigation_live -> {
                    jumpActivity(LiveActivity::class.java)
                    false
                }
                R.id.navigation_subscription -> {
                    jumpActivity(SubscriptionActivity::class.java)
                    false
                }
                else -> false
            }
        }
        mBinding.vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNavigation.selectedItemId = if (position == 0) {
                    R.id.navigation_home
                } else {
                    R.id.navigation_dashboard
                }
            }
        })
        openDestination(intent.getIntExtra(EXTRA_START_DESTINATION, R.id.navigation_home))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDestination(intent.getIntExtra(EXTRA_START_DESTINATION, R.id.navigation_home))
    }

    override fun onResume() {
        super.onResume()
        // 小米/红米平板: 播放页留下的竖屏锁定请求会让 MIUI 在横屏下把整个应用切成
        // "竖屏内容兼容显示"(居中小窗+两侧黑边), 且返回首页后不自动恢复。
        // 回到首页时复位为系统自适应方向, 兜底清除任何遗留的固定方向请求。
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun openDestination(destination: Int) {
        when (destination) {
            R.id.navigation_dashboard -> bottomNavigation.selectedItemId = R.id.navigation_dashboard
            R.id.navigation_live -> jumpActivity(LiveActivity::class.java)
            R.id.navigation_subscription -> jumpActivity(SubscriptionActivity::class.java)
            else -> bottomNavigation.selectedItemId = R.id.navigation_home
        }
    }

    override fun onBackPressed() {
        if (mBinding.vp.currentItem == 1) {
            mBinding.vp.currentItem = 0
            return
        }
        val homeFragment = fragments[0] as HomeFragment
        if (!homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[homeFragment.tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtils.showShort("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}
