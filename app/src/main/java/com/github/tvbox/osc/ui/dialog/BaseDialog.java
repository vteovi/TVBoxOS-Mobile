package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.github.tvbox.osc.R;

import xyz.doikki.videoplayer.util.CutoutUtil;

public class BaseDialog extends Dialog {


    public BaseDialog(@NonNull Context context) {
        super(context, R.style.CustomDialogStyle);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    public BaseDialog(Context context, int customDialogStyle) {
        super(context, customDialogStyle);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CutoutUtil.adaptCutoutAboveAndroidP(this, true);//设置刘海
        super.onCreate(savedInstanceState);


        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(getWindow().getAttributes());
        lp.gravity = Gravity.BOTTOM | Gravity.START | Gravity.END;
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.dimAmount = 0.5f;
        getWindow().setAttributes(lp);
        getWindow().setWindowAnimations(R.style.BottomDialogAnimation); // Set the animation style
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // 见 syncStatusBarWithHost()：弹窗不该以"全屏窗口"的身份接管状态栏，
        // 在 attach 之前就去掉，避免弹窗刚出现时状态栏图标先闪一下白色
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    public void show() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        super.show();
        //hideSysBar();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        syncStatusBarWithHost();
    }

    /**
     * 让状态栏外观继续由宿主 Activity 决定。
     *
     * CustomDialogStyle 里带 windowFullscreen=true，弹窗窗口因此拿到 FLAG_FULLSCREEN，
     * 部分系统会把它当成"拥有状态栏的窗口"，状态栏图标改用弹窗自己的外观设置：弹窗没有像
     * BaseActivity 那样调 statusBarDarkFont，系统就退回默认的浅色图标。于是浅色模式下
     * 状态栏本该是深色文字，打开弹窗后却变成白字看不清（深色模式本来就是白的，看不出来）。
     *
     * 这里做两件事：去掉 FLAG_FULLSCREEN（状态栏归 Activity），并把宿主的实际外观同步过来，
     * 兼容个别仍然按弹窗窗口计算外观的 ROM。宿主的深浅不能只看 App 主题——详情页、直播页
     * 是黑色状态栏，首页才跟随主题。
     */
    private void syncStatusBarWithHost() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Activity activity = findHostActivity(getContext());
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) {
            return;
        }
        Window hostWindow = activity.getWindow();
        WindowInsetsControllerCompat host = WindowCompat.getInsetsController(hostWindow, hostWindow.getDecorView());
        WindowInsetsControllerCompat self = WindowCompat.getInsetsController(window, window.getDecorView());
        if (host == null || self == null) {
            return;
        }
        self.setAppearanceLightStatusBars(host.isAppearanceLightStatusBars());
    }

    private Activity findHostActivity(Context context) {
        Context ctx = context;
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private void hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }
}
