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

    /**
     * 背景遮罩浓度。0 表示不加遮罩，此时也不会加 FLAG_DIM_BEHIND。
     *
     * 为什么要在意这个：FLAG_DIM_BEHIND 的遮罩层是铺满**整个屏幕**的（含状态栏区域），
     * 系统据此认为状态栏也被这个弹窗盖住了，于是把状态栏的外观（图标明暗）算到弹窗头上。
     * 弹窗的主题是 Theme.Dialog（深色），默认外观就是"白图标"，而浅色模式下状态栏是白底，
     * 结果就是白字白底看不清。不需要遮罩的弹窗把它设为 0，直接不参与这件事。
     */
    protected float dimAmountBehind = 0.5f;

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
        lp.dimAmount = dimAmountBehind;
        getWindow().setAttributes(lp);
        getWindow().setWindowAnimations(R.style.BottomDialogAnimation); // Set the animation style
        if (dimAmountBehind > 0f) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
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

    @Override
    public void dismiss() {
        super.dismiss();
        // 兜底：部分机型关掉弹窗后不会立刻把状态栏外观还给 Activity
        Activity activity = findHostActivity(getContext());
        if (activity != null && !activity.isFinishing() && activity.getWindow() != null) {
            setLightStatusBars(activity.getWindow(), resolveLightStatusBars(activity.getWindow()));
        }
    }

    /**
     * 让状态栏外观继续由宿主 Activity 决定。
     *
     * 弹窗（CustomDialogStyle）是"全屏 + 半透明 + 带遮罩"的窗口，系统容易把它当成拥有状态栏
     * 区域的窗口：此时状态栏的**图标明暗**取弹窗自己的 appearance——弹窗主题是 Theme.Dialog
     * （深色），默认就是白图标，于是浅色模式下白底白字看不清。深色模式本来就该白字，所以只有
     * 浅色主题下看得出来。
     *
     * 做法是四件事一起上（任何一条单独都可能被某个 ROM 忽略）：
     * 1. 清掉 FLAG_FULLSCREEN，别以全屏窗口身份抢状态栏；
     * 2. 状态栏底色照抄宿主，避免弹窗用自己主题的默认色刷一层；
     * 3. 图标明暗按宿主的实际设置，在 View / WindowInsetsController 两条通道上都写一遍；
     * 4. 顺手把宿主也按同样的值再写一遍——个别 ROM 会在弹窗出现时把 Activity 的
     *    状态栏外观重算一次（重算结果就是白图标）。
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

        boolean lightBars = resolveLightStatusBars(hostWindow);
        setLightStatusBars(window, lightBars);
        if (hostWindow != null) {
            setLightStatusBars(hostWindow, lightBars);
        }
    }

    /** 把"深色/白色图标"同时写进两条通道 */
    private void setLightStatusBars(Window window, boolean lightBars) {
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (decor == null) {
            return;
        }
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
     * 宿主现在是不是"浅色状态栏"（即需要深色图标）。
     *
     * 不能只看 App 主题：详情页、直播页的状态栏是黑的。
     * 也不能只读 window 的 appearance：ImmersionBar 可能走的是 View 那一路，
     * 而 WindowCompat 读的是 Window 自己记录的值，可能读回来是 false。
     * 这里先按状态栏底色亮度判断，再依次尝试两条 appearance 通道，最后才退回 App 主题。
     */
    private boolean resolveLightStatusBars(Window hostWindow) {
        if (hostWindow == null) {
            // 拿不到宿主（极少见）才退回 App 主题
            return !Utils.isDarkTheme();
        }
        int barColor = hostWindow.getStatusBarColor();
        if (Color.alpha(barColor) == 0xFF) {
            return isColorLight(barColor);
        }
        View hostDecor = hostWindow.getDecorView();
        if (hostDecor != null) {
            WindowInsetsControllerCompat hostController = WindowCompat.getInsetsController(hostWindow, hostDecor);
            if (hostController != null && hostController.isAppearanceLightStatusBars()) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && (hostDecor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0) {
                return true;
            }
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
}
