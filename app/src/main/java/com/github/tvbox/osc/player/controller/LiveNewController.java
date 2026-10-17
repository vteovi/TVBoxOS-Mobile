package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.MotionEvent;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

/**
 * 横竖切换新版直播控制器
 */

public class LiveNewController extends BaseController {
    /** 是否允许"旋转到横屏就自动全屏"。横屏直接进入直播页时为 false, 避免一进页面就全屏 */
    private boolean mRotateToFullScreenEnabled = true;

    public void setRotateToFullScreenEnabled(boolean enabled) {
        mRotateToFullScreenEnabled = enabled;
    }

    private int minFlingDistance = 100;             //最小识别距离
    private int minFlingVelocity = 10;              //最小识别速度

    public LiveNewController(@NotNull Context context) {
        super(context);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_live_control_view;
    }

    public interface LiveControlListener {
        void setting();

        void playStateChanged(int playState);

        void changeSource(int direction);
    }

    private LiveNewController.LiveControlListener listener = null;

    public void setListener(LiveNewController.LiveControlListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        listener.playStateChanged(playState);
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (e1.getX() - e2.getX() > minFlingDistance && Math.abs(velocityX) > minFlingVelocity) {
            listener.changeSource(-1);          //左滑
        } else if (e2.getX() - e1.getX() > minFlingDistance && Math.abs(velocityX) > minFlingVelocity) {
            listener.changeSource(1);           //右滑
        } else if (e1.getY() - e2.getY() > minFlingDistance && Math.abs(velocityY) > minFlingVelocity) {
        } else if (e2.getY() - e1.getY() > minFlingDistance && Math.abs(velocityY) > minFlingVelocity) {
        }
        return false;
    }

    @Override
    protected void onOrientationLandscape(Activity activity) {
        if (!mRotateToFullScreenEnabled && !mControlWrapper.isFullScreen()) {
            // 本来就是横屏进入的: 不自动全屏, 也不锁定横屏, 交给系统跟随物理方向(允许转竖屏)
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            return;
        }
        super.onOrientationLandscape(activity);
    }

    @Override
    protected void onOrientationReverseLandscape(Activity activity) {
        if (!mRotateToFullScreenEnabled && !mControlWrapper.isFullScreen()) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            return;
        }
        super.onOrientationReverseLandscape(activity);
    }

    /**
     * 退出全屏后不再强制竖屏(平板上会突然翻成竖屏, 手机上横握也会被硬转),
     * 改为跟随系统物理方向, 由方向监听自行决定是否回竖屏。
     */
    @Override
    protected boolean stopFullScreen() {
        if (mActivity == null || mActivity.isFinishing()) return false;
        mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        mControlWrapper.stopFullScreen();
        return true;
    }

    @Override
    public boolean onBackPressed() {
        if (mControlWrapper.isFullScreen()) {
            return stopFullScreen();
        }
        return super.onBackPressed();
    }

    /**
     * 双击切换全屏。
     * 基类实现是 mControlWrapper.toggleFullScreen(mActivity): 退出时会
     * setRequestedOrientation(PORTRAIT) —— 平板上横屏双击退出全屏会被硬翻成竖屏
     * (和已修好的 stopFullScreen 是两条不同的路径)。这里改为:
     *   退出 —— 走自己的 stopFullScreen(), 方向交还系统(UNSPECIFIED), 保持当前朝向;
     *   进入 —— 只有当前确实竖屏时才主动转横屏(用 SENSOR_LANDSCAPE 保留 180 度旋转,
     *           之后用户竖握仍会被方向监听识别并退出全屏, 不会被困在横屏)。
     */
    @Override
    protected void toggleFullScreen() {
        if (mControlWrapper.isFullScreen()) {
            stopFullScreen();
            return;
        }
        if (mActivity != null && !mActivity.isFinishing()) {
            boolean portrait = mActivity.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_PORTRAIT;
            if (portrait) {
                mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
        }
        mControlWrapper.startFullScreen();
    }

    /**
     * 双击直播画面切换全屏（直播页已关闭双击播放，故双击统一用于全屏切换）
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (isInPlaybackState()) {
            toggleFullScreen();
        }
        return true;
    }
}
