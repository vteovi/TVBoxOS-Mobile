package com.github.tvbox.osc.base;

import android.text.TextUtils;

import android.os.Looper;

import androidx.multidex.MultiDexApplication;

import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.MainActivity;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.Utils;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;

    public boolean isNormalStart;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance()
                .setExcludeFontScale(true)
                .setCustomFragment(true)
                .getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        FileUtils.cleanPlayerCache();
        initCrashConfig();
        Utils.initTheme();
        // 启动前清理上次可能残留的损坏原生库(远程 spider 下载失败产生的 XML 错误页)
        cleanCorruptNativeLibs();
        // 加载订阅源
        loadSubscriptions();
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);

        putDefault(HawkConfig.HOME_REC, 0);                  //推荐: 0=豆瓣热播, 1=站点推荐
        putDefault(HawkConfig.PLAY_TYPE, 2);                 //播放器: 0=系统, 1=IJK, 2=Exo
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE,2);           //后台播放: 0 关闭,1 开启,2 画中画
        putDefault(HawkConfig.DOH_URL, 0);                   //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.HISTORY_NUM, 2);                //历史记录数量: 0=30, 1=50, 2=70
        putDefaultApi();
    }

    private void putDefaultApi() {
        String[] apis = getResources().getStringArray(R.array.api);
        String[] apiNames = getResources().getStringArray(R.array.api_name);
        if (apis.length == 0) {
            return;
        }

        List<Subscription> subscriptions = Hawk.get(HawkConfig.SUBSCRIPTIONS, new ArrayList<Subscription>());
        boolean changed = false;
        for (int i = 0; i < apis.length; i++) {
            String api = apis[i];
            String apiName = i < apiNames.length && !TextUtils.isEmpty(apiNames[i])
                    ? apiNames[i]
                    : "内置订阅";
            if (TextUtils.isEmpty(api)) {
                continue;
            }

            Subscription matched = null;
            for (Subscription subscription : subscriptions) {
                if (api.equals(subscription.getUrl())) {
                    matched = subscription;
                    break;
                }
            }
            if (matched == null) {
                subscriptions.add(new Subscription(apiName, api).setBuiltIn(true));
                changed = true;
            } else if (!matched.isBuiltIn()) {
                matched.setBuiltIn(true);
                changed = true;
            }
        }

        if (!Hawk.contains(HawkConfig.API_URL) && !subscriptions.isEmpty()) {
            Subscription defaultSubscription = subscriptions.get(0);
            defaultSubscription.setChecked(true);
            Hawk.put(HawkConfig.API_URL, defaultSubscription.getUrl());
            changed = true;
        }
        if (changed || !Hawk.contains(HawkConfig.SUBSCRIPTIONS)) {
            Hawk.put(HawkConfig.SUBSCRIPTIONS, subscriptions);
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.load();
    }

    private void putDefault(String key, Object value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    private void loadSubscriptions() {
        // 加载订阅源的实现
        // 例如，从本地文件或远程服务器加载订阅源
        // 并将其存储到Hawk中
    }

    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(instance.getExternalCacheDir().getAbsolutePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    private void initCrashConfig(){
        //配置全局异常崩溃操作
        CaocConfig.Builder.create()
                .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT) //背景模式,开启沉浸式
                .enabled(true) //是否启动全局异常捕获
                .showErrorDetails(true) //是否显示错误详细信息
                .showRestartButton(true) //是否显示重启按钮
                .trackActivities(true) //是否跟踪Activity
                .minTimeBetweenCrashesMs(2000) //崩溃的间隔时间(毫秒)
                .errorDrawable(R.drawable.app_icon) //错误图标
                .restartActivity(MainActivity.class) //重新启动后的activity
                .apply();
        // 兜底过滤: catvod 蜘蛛的 GoProxy 等原生库是运行时从远程下载的 .so,
        // 当下载地址失效/被拦截时, 下载到的是 XML 错误页而非 ELF,
        // 加载即抛 UnsatisfiedLinkError: bad ELF magic: 3c3f786d。
        // 这是订阅源/网络问题, 与 App 本身无关, 不应弹报错页或闪退。
        // 这里仅清理损坏的 so 文件并静默忽略, 其余不依赖该库的源可正常使用。
        final Thread.UncaughtExceptionHandler delegate = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                // 仅在后台线程上静默忽略损坏原生库的崩溃; 主线程仍交给 Caoc 正常处理,
                // 避免主线程被"静默放死"而触发系统级崩溃。
                boolean mainThread = t != null && Looper.getMainLooper().getThread() == t;
                if (!mainThread && isCorruptNativeLibError(e)) {
                    LOG.e("忽略原生库下载损坏导致的崩溃(bad ELF magic), 不影响其它功能: " + e.getMessage());
                    cleanCorruptNativeLibs();
                    return;
                }
                if (delegate != null) {
                    delegate.uncaughtException(t, e);
                }
            }
        });
    }

    /**
     * 是否为"原生库下载损坏"导致的非致命崩溃:
     * 表现为 UnsatisfiedLinkError, 错误信息包含 bad ELF magic / libwexproxy / GoProxy 等。
     */
    private boolean isCorruptNativeLibError(Throwable e) {
        Throwable th = e;
        while (th != null) {
            String msg = th.getMessage();
            if (msg != null && (msg.contains("bad ELF magic")
                    || msg.contains("has bad ELF")
                    || msg.contains("libwexproxy")
                    || msg.contains("GoProxy"))) {
                return true;
            }
            th = th.getCause();
        }
        return false;
    }

    /**
     * 清理 files/TV 下损坏的原生库: 读取文件头, 凡是未以 ELF 魔数(7F 45 4C 46)开头的
     * 隐藏 .so 文件都视为下载失败时残留的 XML/错误页, 直接删除, 便于网络恢复后重新下载。
     * 注意只删损坏文件, 不会误删真正有效的 .so。
     */
    private void cleanCorruptNativeLibs() {
        try {
            File tvDir = new File(getFilesDir(), "TV");
            if (!tvDir.isDirectory()) {
                return;
            }
            File[] files = tvDir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith(".lib") && !isElfFile(f)) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 判断文件是否为合法 ELF 二进制(以 7F 'E' 'L' 'F' 开头) */
    private boolean isElfFile(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] head = new byte[4];
            int n = in.read(head);
            if (n < 4) {
                return false;
            }
            return head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
        } catch (Throwable ignored) {
            return false;
        }
    }
}
