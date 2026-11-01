package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
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
import com.github.tvbox.osc.util.Utils;

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
        // 有的 ROM 在窗口首帧绘制完成后才重算状态栏外观，补一次
        getWindow().getDecorView().post(this::syncStatusBarWithHost);
    }

    /**
     * 让状态栏外观继续由宿主 Activity 决定。
     *
     * CustomDialogStyle 带 windowFullscreen=true，弹窗窗口因此按"全屏窗口"处理，系统会
     * 把它当成拥有状态栏区域的窗口：此时状态栏的**背景色**取弹窗自己的 statusBarColor
     * （默认偏暗），**图标明暗**取弹窗自己的 appearance（弹窗没设过，默认按深色底算）。
     * 于是浅色模式下本该深色文字的白色状态栏，被换成了深底+白字；深色模式本来就该白字，
     * 所以只有浅色主题下看得出来。
     *
     * 三件事一起做才稳：清全屏标记（别抢状态栏）、状态栏底色照抄宿主（弹窗和首页/详情页
     * 用同一个底色，不会被弹窗主题的默认色刷一层）、图标明暗按宿主的实际情况同步。
     * 宿主的深浅不能看 App 主题——详情页、直播页是黑色状态栏，首页才跟随主题。
     */
    protected void syncStatusBarWithHost() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 加上这个标记后系统才会用我们给的 statusBarColor（否则按默认深色刷一层）
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        Activity activity = findHostActivity(getContext());
        Window hostWindow = (activity != null && !activity.isFinishing()) ? activity.getWindow() : null;

        // 状态栏底色照抄宿主：宿主透明就透明（露出顶栏），宿主黑色就黑色（详情页/直播页）
        window.setStatusBarColor(hostWindow != null ? hostWindow.getStatusBarColor() : Color.TRANSPARENT);

        boolean lightBars = resolveLightStatusBar(hostWindow);
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 老通道：部分 ROM / 低版本只认 systemUiVisibility
            int visibility = decor.getSystemUiVisibility();
            visibility &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
            if (lightBars) {
                visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(visibility);
        }
        // 新通道：Android 11+ 的正式接口
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        if (controller != null) {
            controller.setAppearanceLightStatusBars(lightBars);
        }
    }

    /**
     * 判断宿主现在是不是"浅色状态栏"（即需要深色图标）。
     *
     * 不能只看 App 主题：详情页、直播页的状态栏是黑的。
     * 也不能只读 window 的 appearance：ImmersionBar 走的是 View 那一路，
     * 而 WindowCompat 读的是 Window 自己记录的值，可能读回来是 false（于是图标又被设成白色，
     * 表现就是"改了没变化"）。这里先按状态栏底色亮度判断，再依次尝试两条 appearance 通道。
     */
    private boolean resolveLightStatusBar(Window hostWindow) {
        if (hostWindow == null) {
            // 拿不到宿主（极少见）才退回 App 主题
            return !Utils.isDarkTheme();
        }
        int barColor = hostWindow.getStatusBarColor();
        if (Color.alpha(barColor) == 0xFF) {
            return isColorLight(barColor);
        }
        View hostDecor = hostWindow.getDecorView();
        WindowInsetsControllerCompat hostController = WindowCompat.getInsetsController(hostWindow, hostDecor);
        if (hostController != null && hostController.isAppearanceLightStatusBars()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && (hostDecor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0) {
            return true;
        }
        return !Utils.isDarkTheme();
    }

    private boolean isColorLight(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255d;
        return luminance > 0.6;
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
