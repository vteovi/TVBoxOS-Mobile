package com.github.tvbox.osc.api;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    private static ApiConfig instance;
    private LinkedHashMap<String, SourceBean> sourceBeanList;
    private SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private List<LiveChannelGroup> liveChannelGroupList;
    private List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private List<IJKCode> ijkCodes;
    private String spider = null;
    public String wallpaper = "";

    private SourceBean emptyHome = new SourceBean();

    /** 每个订阅配置各自记住"上次选中的首页站点"的前缀（key = 前缀 + md5(配置地址)） */
    private static final String HOME_API_OF_PREFIX = "home_api_of_";
    /** 当前正在解析的配置地址，用于按配置分组保存首页站点 */
    private String mCurApiUrl = "";

    private JarLoader jarLoader = new JarLoader();
    private JsLoader jsLoader = new JsLoader();

    private String userAgent = "okhttp/3.15";

    private String requestAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    private ApiConfig() {
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if(matcher.find()){
                content=content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            }else if (configKey !=null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            }
            else{
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    private static byte[] getImgJar(String body){
        Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if(matcher.find()){
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    /**
     * 把订阅地址拆成「实际请求地址」和「解密 key」。
     *
     * <p>支持 {@code ;pk;密钥}、{@code clan...} 短链，以及不带协议的地址。
     * 顺带修掉一个旧问题：不带协议的地址原来拼成 {@code "http://"} + 空串，
     * 请求必然失败（{@code http://example.com/api.json} 这类地址用不了）。
     *
     * @return [configUrl, configKey]，configKey 可能为 null
     */
    private String[] resolveConfigUrl(String apiUrl) {
        String tempKey = null, configUrl = "";
        String pk = ";pk;";
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            tempKey = a[1];
            if (apiUrl.startsWith("clan")) {
                configUrl = clanToAddress(a[0]);
            } else if (apiUrl.startsWith("http")) {
                configUrl = a[0];
            } else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + apiUrl;
        } else {
            configUrl = apiUrl;
        }
        return new String[]{configUrl, tempKey};
    }

    /**
     * 预取某个订阅的配置和它依赖的爬虫 jar，只写本地缓存，不解析、不动当前界面用的站点列表。
     *
     * <p>用户在订阅页选中另一个订阅时调用：等他从订阅页回到首页，配置和 jar 都已经在本地，
     * 首页只需要解析缓存 + 加载 dex，不用再等一次几 MB 的下载。这是「切了订阅还要慢几秒」的主因。
     */
    public void prefetchConfig(final String apiUrl) {
        if (TextUtils.isEmpty(apiUrl)) return;
        if (apiUrl.startsWith("assets://") || apiUrl.startsWith("content://") || apiUrl.startsWith("file://")) {
            return; // 本地/内置配置，没有下载成本
        }
        // 调用方是订阅列表的点击回调（主线程）：磁盘读写、md5、json 解析一律挪到后台
        jarLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                doPrefetchConfig(apiUrl);
            }
        });
    }

    private void doPrefetchConfig(String apiUrl) {
        final String cacheName = MD5.encode(apiUrl);
        final File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + cacheName);
        final File meta = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + cacheName + ".meta");
        if (cache.exists() && cache.length() > 0) {
            // 配置已经缓存过（以前用过这个订阅），那就只预热它依赖的 jar
            prefetchSpiderJar(readTextFile(cache));
            return;
        }
        String[] resolved = resolveConfigUrl(apiUrl);
        final String configUrl = resolved[0];
        final String configKey = resolved[1];
        final String apiUrlFinal = apiUrl;
        OkGo.<String>get(configUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            String json = response.body();
                            if (TextUtils.isEmpty(json)) return;
                            writeConfigCache(cache, json);
                            writeCacheMeta(meta, MD5.string2MD5(json), System.currentTimeMillis());
                            prefetchSpiderJar(json);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        // 预取失败无所谓：回到首页后正式加载会再试一次
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        String result = "";
                        if (response.body() != null) {
                            result = FindResult(response.body().string(), configKey);
                        }
                        if (apiUrlFinal.startsWith("clan")) {
                            result = clanContentFix(clanToAddress(apiUrlFinal), result);
                        }
                        return fixContentPath(apiUrlFinal, result);
                    }
                });
    }

    /**
     * 预下载配置里声明的爬虫 jar（只下不下加载）。
     *
     * <p>文件路径与 {@link #loadJar} 完全一致（{@code csp_<md5(地址)>.jar}），
     * 所以回到首页时加载这一步直接命中缓存，省掉一次几 MB 的下载。
     * 下载同样先落临时文件再改名，成功与否都不影响正式流程。
     */
    private void prefetchSpiderJar(String configJson) {
        if (TextUtils.isEmpty(configJson)) return;
        try {
            JsonObject info = new Gson().fromJson(configJson, JsonObject.class);
            if (info == null) return;
            String spiderStr = DefaultConfig.safeJsonString(info, "spider", "");
            if (TextUtils.isEmpty(spiderStr)) return;
            String jarUrl = spiderStr.split(";md5;")[0].trim();
            if (!jarUrl.startsWith("http")) return; // img+/空地址交给正式加载逻辑处理
            final File jar = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp_" + MD5.encode(jarUrl) + ".jar");
            if (jar.exists() && jar.length() > 0) return;
            final File tmp = new File(jar.getAbsolutePath() + ".prefetch.tmp");
            OkGo.<File>get(jarUrl)
                    .headers("User-Agent", userAgent)
                    .headers("Accept", requestAccept)
                    .execute(new AbsCallback<File>() {
                        @Override
                        public File convertResponse(okhttp3.Response response) throws Throwable {
                            File dir = jar.getParentFile();
                            if (dir != null && !dir.exists()) dir.mkdirs();
                            if (tmp.exists()) tmp.delete();
                            FileOutputStream fos = new FileOutputStream(tmp);
                            try {
                                fos.write(response.body().bytes());
                                fos.flush();
                            } finally {
                                try {
                                    fos.close();
                                } catch (Throwable ignore) {
                                }
                            }
                            return tmp;
                        }

                        @Override
                        public void onSuccess(Response<File> response) {
                            File f = response.body();
                            if (f == null || !f.exists() || f.length() <= 0) return;
                            if (jar.exists()) jar.delete();
                            if (!f.renameTo(jar)) f.delete();
                        }

                        @Override
                        public void onError(Response<File> response) {
                            super.onError(response);
                            if (tmp.exists()) tmp.delete();
                        }
                    });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 读一个小的文本文件（订阅配置），失败返回空串 */
    private static String readTextFile(File f) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable th) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * 切换订阅后立刻作废旧订阅在内存里的状态。
     *
     * <p>首页是「先拿内存里的站点列表渲染、再拉新数据」的：订阅换了但新配置还没解析完的这几秒里，
     * {@link #getHomeSourceBean()} 和 {@link #getSourceBeanList()} 还是上一个订阅的，
     * 于是首页标签、站点推荐、聚合搜索全部在用旧源——用户看到的就是「切了订阅但还是旧内容」。
     *
     * <p>清空之后首页会走「配置还没好」的分支（转圈/加载中），不会再拿旧源的数据冒充新源。
     */
    public synchronized void resetForSwitch() {
        mCurApiUrl = "";
        if (sourceBeanList != null) sourceBeanList.clear();
        mHomeSource = null;
        spider = "";
        wallpaper = "";
    }

    /** 当前内存里的站点列表是哪个订阅解析出来的；用于判断是否需要重新加载 */
    public String getLoadedApiUrl() {
        return mCurApiUrl == null ? "" : mCurApiUrl;
    }

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        String apiUrl = Hawk.get(HawkConfig.API_URL, "");
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        String cacheName = MD5.encode(apiUrl);
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + cacheName);
        File meta = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + cacheName + ".meta");
        // 冷启动也先读本地缓存立即回调，让 UI 快速显示；后台继续下载新配置刷新
        String cachedHash = readCacheMetaHash(meta);
        // cacheHit 需要被匿名内部类 AbsCallback 捕获，必须是事实 final；
        // 故用一个可变本地 cacheHitRaw 承载赋值，再转成 final 的 cacheHit
        boolean cacheHitRaw = false;
        if (cache.exists()) {
            try {
                parseJson(apiUrl, cache);
                cacheHitRaw = true;
                // 即时从本地缓存秒出，fromCache=true 表示本次未走网络
                callback.success(true);
            } catch (Throwable th) {
                th.printStackTrace();
                // 缓存损坏（写到一半被杀/写入被中断）：删掉坏缓存与元数据，下次走网络重新拉取自愈
                cache.delete();
                deleteCacheMeta(meta);
            }
        }
        final boolean cacheHit = cacheHitRaw;
        if (useCache && cacheHit) {
            // 设置页等场景：仅用缓存渲染，不再请求网络
            return;
        }
        if (apiUrl.startsWith("assets://")) {
            try {
                String json = readAssetConfig(apiUrl.substring("assets://".length()));
                parseJson(apiUrl, json);
                writeConfigCache(cache, json);
                writeCacheMeta(meta, MD5.string2MD5(json), System.currentTimeMillis());
                callback.success(false);
            } catch (Throwable th) {
                th.printStackTrace();
                callback.error("加载内置配置失败");
            }
            return;
        }
        if (apiUrl.startsWith("content://") || apiUrl.startsWith("file://")) {
            try {
                String json = readContentConfig(Uri.parse(apiUrl));
                parseJson(apiUrl, json);
                writeConfigCache(cache, json);
                writeCacheMeta(meta, MD5.string2MD5(json), System.currentTimeMillis());
                callback.success(false);
            } catch (Throwable th) {
                th.printStackTrace();
                callback.error("无法读取本地配置文件");
            }
            return;
        }
        String[] resolved = resolveConfigUrl(apiUrl);
        String configUrl = resolved[0];
        String configKey = resolved[1];
        OkGo.<String>get(configUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            String json = response.body();
                            String hash = MD5.string2MD5(json);
                            // 内容没变：跳过重新解析，不打扰 UI（cacheHit 时尤其重要，避免无谓闪烁）
                            if (cacheHit && hash != null && hash.equals(cachedHash)) {
                                writeCacheMeta(meta, hash, System.currentTimeMillis());
                                return;
                            }
                            parseJson(apiUrl, json);
                            try {
                                writeConfigCache(cache, json);
                                writeCacheMeta(meta, hash, System.currentTimeMillis());
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                            // cacheHit 为真时已用缓存秒出，这里仅在内容确有变化时才刷新 UI；
                            // 否则（首拉/缓存缺失）正常回调成功
                            callback.success(false);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            if (!cacheHit) {
                                callback.error("解析配置失败");
                            }
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        if (cacheHit) {
                            // 已用本地缓存快速回调过，网络失败不影响 UI
                            return;
                        }
                        if (cache.exists()) {
                            try {
                                parseJson(apiUrl, cache);
                                callback.success(false);
                                return;
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                        }
                        callback.error("拉取配置失败\n" + (response.getException() != null ? response.getException().getMessage() : ""));
                    }

                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        String result = "";
                        if (response.body() == null) {
                            result = "";
                        } else {
                            result = FindResult(response.body().string(), configKey);
                        }

                        if (apiUrl.startsWith("clan")) {
                            result = clanContentFix(clanToAddress(apiUrl), result);
                        }
                        //假相對路徑
                        result = fixContentPath(apiUrl,result);
                        return result;
                    }
                });
    }

    private String readCacheMetaHash(File meta) {
        if (meta == null || !meta.exists()) return null;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(meta), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject obj = new JSONObject(sb.toString());
            return obj.optString("hash", null);
        } catch (Throwable th) {
            return null;
        }
    }

    private void writeCacheMeta(File meta, String hash, long time) {
        if (meta == null || hash == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("hash", hash);
            obj.put("time", time);
            File p = meta.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            try (FileOutputStream out = new FileOutputStream(meta)) {
                out.write(obj.toString().getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void deleteCacheMeta(File meta) {
        if (meta != null && meta.exists()) meta.delete();
    }


    /**
     * jar 下载与 dex 加载专用线程池。
     *
     * <p>原来是 newSingleThreadExecutor：首页 initData 每次都会走一遍 loadJar
     * （切源、重试、从后台回前台都会触发），而一次加载在老机型上要等十秒量级的 dex 优化，
     * 后来的调用只能排在同一条队列里干等，表现出来就是「一直卡在加载中不动」。
     *
     * <p>改成 3 个线程后，不同 jar 的下载互不排队，某一次卡住也只占一个线程。
     * dex 加载本身仍然串行（见 JarLoader.load 的锁），因为类加载器是整表替换，
     * 但这一步不涉及网络，不会拖慢下载。
     */
    private static final ExecutorService jarLoadExecutor = Executors.newFixedThreadPool(3,
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "jar-loader");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });

    /**
     * 正在进行的 jar 加载任务，按 spider 配置去重。
     *
     * <p>同一份配置同时只跑一次：重复的调用挂在同一个任务上等结果，
     * 不会再排一条新的下载/加载任务，避免慢速设备上任务越堆越多、越堆越慢。
     */
    private final ConcurrentHashMap<String, JarLoadTask> jarLoadTasks = new ConcurrentHashMap<>();

    /** 一次 jar 加载任务的等待者集合，结果只派发一次 */
    private static final class JarLoadTask {
        final ArrayList<LoadConfigCallback> waiters = new ArrayList<>();
        boolean done = false;

        synchronized boolean add(LoadConfigCallback cb) {
            if (done) return false;
            waiters.add(cb);
            return true;
        }

        synchronized ArrayList<LoadConfigCallback> finish() {
            done = true;
            ArrayList<LoadConfigCallback> copy = new ArrayList<>(waiters);
            waiters.clear();
            return copy;
        }
    }

    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        // 必须离开主线程：DexClassLoader 首次加载几 MB 的 jar 要等 dex 优化，
        // 放在主线程会直接卡住首页甚至 ANR，而之前为了防 ANR 把等待窗口压到 1 秒，
        // 反而让慢速机型被误判成"订阅不可用"（首页只剩「主页」一个标签）。
        final String taskKey = spider == null ? "" : spider;
        JarLoadTask task;
        while (true) {
            JarLoadTask cur = jarLoadTasks.get(taskKey);
            if (cur == null) {
                JarLoadTask fresh = new JarLoadTask();
                fresh.waiters.add(callback);
                JarLoadTask prev = jarLoadTasks.putIfAbsent(taskKey, fresh);
                if (prev == null) {
                    task = fresh;
                    break;
                }
                cur = prev;
            }
            if (cur.add(callback)) {
                // 同一份配置已经在下载/加载了，直接等它的结果，不再重复排队
                return;
            }
            // 上一个任务刚结束、还没来得及从表里摘掉，清掉后重来一次
            jarLoadTasks.remove(taskKey, cur);
        }
        submitJarLoad(taskKey, task, useCache, spider);
    }

    private void submitJarLoad(final String taskKey, final JarLoadTask task,
                               final boolean useCache, final String spider) {
        // 取一个递增序号：切源很快时可能有两份 jar 同时在加载，
        // 只有后发起的那次允许落地，先发起但晚完成的旧结果会被 JarLoader 丢弃，
        // 不会把用户刚切过去的源又顶掉
        final long loadSeq = jarLoader.issueLoadSeq();
        jarLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doLoadJar(useCache, spider, loadSeq, new LoadConfigCallback() {
                        @Override
                        public void success() {
                            dispatchJarResult(taskKey, task, 1, "");
                        }

                        @Override
                        public void retry() {
                            dispatchJarResult(taskKey, task, 2, "");
                        }

                        @Override
                        public void error(String msg) {
                            dispatchJarResult(taskKey, task, 3, msg);
                        }
                    });
                } catch (Throwable th) {
                    th.printStackTrace();
                    // 兜一个结果：否则首页会永远停在加载中，看起来就是一片空白
                    dispatchJarResult(taskKey, task, 3, "");
                }
            }
        });
    }

    /**
     * 把加载结果派发给所有等待者。
     *
     * <p>jdk 的 jar 缓存命中（useCache）时，多个等待者可能同时挂上来，
     * 这里统一派发一次，避免各自起一份加载任务。
     */
    private void dispatchJarResult(String taskKey, JarLoadTask task, int type, String msg) {
        ArrayList<LoadConfigCallback> waiters = task.finish();
        jarLoadTasks.remove(taskKey, task);
        for (LoadConfigCallback cb : waiters) {
            try {
                if (type == 1) {
                    cb.success();
                } else if (type == 2) {
                    cb.retry();
                } else {
                    cb.error(msg);
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    private void doLoadJar(boolean useCache, String spider, final long loadSeq,
                           LoadConfigCallback callback) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        // 必须和 JarLoader.getSpider 里算 jarKey 的方式一致(同样用 MD5.string2MD5(jarUrl))，
        // 这样这里加载好的类加载器能被 getSpider 直接命中，不会被当成"没加载过"而重下一遍
        final String jarKey = jarUrl.isEmpty() ? "main" : MD5.string2MD5(jarUrl);
        // 按 jar 地址分源缓存: 每个订阅源的 jar 各存一份, 切换订阅源时直接命中本地缓存秒加载,
        // 不再共用单一 csp.jar 导致来回切源时互相覆盖、每次都要重新下载几 MB 的 jar。
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp_" + MD5.encode(jarUrl) + ".jar");
        // 旧版全局缓存文件, 仅用于升级后的一次性迁移, 避免升级后第一次切源要重新下载
        File legacyCache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp.jar");

        if (!cache.exists() && legacyCache.exists()
                && (useCache || md5.isEmpty() || MD5.getFileMd5(legacyCache).equalsIgnoreCase(md5))) {
            // 旧缓存内容与该源匹配时改名迁移过来, 省一次下载
            legacyCache.renameTo(cache);
        }

        if (!md5.isEmpty() || useCache) {
            if (cache.exists() && (useCache || MD5.getFileMd5(cache).equalsIgnoreCase(md5))) {
                if (jarLoader.load(cache.getAbsolutePath(), jarKey, loadSeq)) {
                    callback.success();
                    return;
                }
                // 能走到这里说明 md5 已经校验通过，文件本身是完好的，
                // 加载失败只可能是 dex 优化太慢或运行时异常，此时删掉缓存重新下载毫无意义，
                // 反而把好文件弄没了、下次继续失败。只有 useCache 路径（没做 md5 校验）
                // 才怀疑文件损坏并删掉重下。
                if (!useCache) {
                    callback.error("");
                    return;
                }
                cache.delete();
            }
        }

        boolean isJarInImg = jarUrl.startsWith("img+");
        jarUrl = jarUrl.replace("img+", "");
        final String jarUrlLog = jarUrl;
        final boolean jarInImg = isJarInImg;
        OkGo.<File>get(jarUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<File>() {

            @Override
            public File convertResponse(okhttp3.Response response) throws Throwable {
                File cacheDir = cache.getParentFile();
                if (!cacheDir.exists())
                    cacheDir.mkdirs();
                // 先写临时文件，下载完整后再替换正式缓存。
                // 之前是直接覆盖 csp_xxx.jar：下载到一半断网、被超时打断或进程被杀，
                // 就会把上一份能用的 jar 换成一个残缺文件，下次启动校验不过再重下，
                // 于是永远卡在"下载中"这一步。写临时文件后，旧 jar 在下载期间始终完好。
                File tmp = new File(cache.getAbsolutePath() + ".tmp");
                if (tmp.exists())
                    tmp.delete();
                FileOutputStream fos = new FileOutputStream(tmp);
                try {
                    if (jarInImg) {
                        String respData = response.body().string();
                        byte[] imgJar = getImgJar(respData);
                        fos.write(imgJar);
                    } else {
                        fos.write(response.body().bytes());
                    }
                    fos.flush();
                } finally {
                    try {
                        fos.close();
                    } catch (Throwable ignore) {
                    }
                }
                return tmp;
            }

            @Override
            public void onSuccess(Response<File> response) {
                final File tmp = response.body();
                // 校验一下内容：错误页、半截文件在这一步挡掉，不让它污染缓存
                if (tmp == null || !tmp.exists() || !looksLikeJar(tmp)) {
                    if (tmp != null && tmp.exists())
                        tmp.delete();
                    System.out.println("csp.jar 下载内容异常, 保留旧缓存: " + jarUrlLog);
                    loadJarFileAsync(cache, jarKey, loadSeq, callback);
                    return;
                }
                if (cache.exists() && !cache.delete()) {
                    System.out.println("csp.jar 旧缓存删除失败: " + cache.getAbsolutePath());
                }
                final File target = tmp.renameTo(cache) ? cache : tmp;
                // dex 加载（老机型首次优化要好几秒）不能占着 OkGo 的下载回调线程：
                // 下载线程要尽快空出来接后面的请求，加载交给 jar 线程池排队
                jarLoadExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (jarLoader.load(target.getAbsolutePath(), jarKey, loadSeq)) {
                            callback.success();
                        } else {
                            // 便于定位: 若是错误页而非 jar, 这里的大小/文件头会很明显
                            System.out.println("csp.jar 加载失败: " + target.getAbsolutePath()
                                    + " 大小=" + target.length() + " 来源=" + jarUrlLog);
                            callback.error("");
                        }
                    }
                });
            }

            @Override
            public void onError(Response<File> response) {
                super.onError(response);
                System.out.println("csp.jar 下载失败: " + jarUrlLog + " -> "
                        + (response.getException() != null ? response.getException().getMessage() : ""));
                // 下载失败时若本地还留着上次可用的 jar(比如网络抖动), 先加载旧缓存兜底,
                // 不让首页空转; 后续重新进首页/冷启动时会再校验更新。
                loadJarFileAsync(cache, jarKey, loadSeq, callback);
            }
        });
    }

    /** 在 jar 线程池里加载一个已经落盘的 jar，失败就回调 error，绝不在回调线程里做阻塞加载 */
    private void loadJarFileAsync(final File file, final String jarKey, final long loadSeq,
                                  final LoadConfigCallback callback) {
        jarLoadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (file.exists() && jarLoader.load(file.getAbsolutePath(), jarKey, loadSeq)) {
                    callback.success();
                } else {
                    callback.error("");
                }
            }
        });
    }

    /**
     * 大致判断下载到的内容是 jar/dex 而不是错误页。
     *
     * <p>源站抽风时经常返回一段 HTML 或空内容，长度也有几 KB，md5 校验又没配置，
     * 直接拿去加载就会失败并把缓存弄坏。这里只看文件头：zip(PK) 或裸 dex(dex\n)。
     */
    private static boolean looksLikeJar(File f) {
        if (f.length() < 256L) return false;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] head = new byte[4];
            if (in.read(head) < 4) return false;
            boolean zip = head[0] == 'P' && head[1] == 'K';
            boolean dex = head[0] == 'd' && head[1] == 'e' && head[2] == 'x';
            return zip || dex;
        } catch (Throwable th) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        System.out.println("从本地缓存加载" + f.getAbsolutePath());
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseJson(apiUrl, sb.toString());
    }

    private String readAssetConfig(String assetPath) throws Throwable {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                App.getInstance().getAssets().open(assetPath), "UTF-8"));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append('\n');
        }
        reader.close();
        return content.toString();
    }

    private String readContentConfig(Uri uri) throws Throwable {
        InputStream input;
        if ("file".equals(uri.getScheme())) {
            input = new FileInputStream(uri.getPath());
        } else {
            input = App.getInstance().getContentResolver().openInputStream(uri);
        }
        if (input == null) {
            throw new IllegalStateException("Unable to open local config");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append('\n');
                }
                return content.toString();
            }
        }

    private void writeConfigCache(File cache, String json) throws Throwable {
        File cacheDir = cache.getParentFile();
        if (!cacheDir.exists())
            cacheDir.mkdirs();
        try (FileOutputStream output = new FileOutputStream(cache)) {
            output.write(json.getBytes("UTF-8"));
            output.flush();
        }
    }

    private void parseJson(String apiUrl, String jsonStr) {
        mCurApiUrl = apiUrl == null ? "" : apiUrl;
        JsonObject infoJson = new Gson().fromJson(jsonStr, JsonObject.class);
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "");
        // 远端站点源
        SourceBean firstSite = null;
        if (sourceBeanList!= null)
            sourceBeanList.clear();
        for (JsonElement opt : infoJson.get("sites").getAsJsonArray()) {
            JsonObject obj = (JsonObject) opt;
            SourceBean sb = new SourceBean();
            String siteKey = obj.get("key").getAsString().trim();
            sb.setKey(siteKey);
            sb.setName(obj.get("name").getAsString().trim());
            sb.setType(obj.get("type").getAsInt());
            sb.setApi(obj.get("api").getAsString().trim());
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            if(obj.has("ext") && (obj.get("ext").isJsonObject() || obj.get("ext").isJsonArray())){
                sb.setExt(obj.get("ext").toString());
            }else {
                sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            }
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            if (firstSite == null)
                firstSite = sb;
            sourceBeanList.put(siteKey, sb);
        }
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            // 首页站点按"订阅配置"分别记忆：HOME_API 是全局唯一值，切到本地配置时会被改成本地站点，
            // 再切回在线配置就找不到该 key，于是退回 firstSite —— 用户原来选的站点被悄悄换掉，
            // 表现就是"点海报的跳转行为变了"（不再进聚合搜索，而是进详情且没有内容）。
            String home = homeApiOf(mCurApiUrl);
            if (TextUtils.isEmpty(home)) home = Hawk.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null)
                setSourceBean(firstSite);
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        if(infoJson.has("parses")){
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = obj.has("ext") ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = Hawk.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }
        // 直播源
        liveChannelGroupList.clear();           //修复从后台切换重复加载频道列表
        String liveURL = Hawk.get(HawkConfig.LIVE_URL, "");
        //String epgURL  = Hawk.get(HawkConfig.EPG_URL, "");

        String liveURL_final = null;
        try {
            if (infoJson.has("lives") && infoJson.get("lives").getAsJsonArray() != null) {
                JsonObject livesOBJ = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                String lives = livesOBJ.toString();
                int index = lives.indexOf("proxy://");
                if (index != -1) {
                    int endIndex = lives.lastIndexOf("\"");
                    String url = lives.substring(index, endIndex);
                    url = DefaultConfig.checkReplaceProxy(url);

                    //clan
                    String extUrl = Uri.parse(url).getQueryParameter("ext");
                    if (extUrl != null && !extUrl.isEmpty()) {
                        String extUrlFix;
                        if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                            extUrlFix = extUrl;
                        } else {
                            extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                        }
                        if (extUrlFix.startsWith("clan://")) {
                            extUrlFix = clanContentFix(clanToAddress(apiUrl), extUrlFix);
                        }

                        // takagen99: Capture Live URL into Config
                        System.out.println("Live URL :" + extUrlFix);
                        putLiveHistory(extUrlFix);
                        // Overwrite with Live URL from Settings
                        if (!StringUtils.isBlank(liveURL)) {
                            extUrlFix = liveURL;
                        }

                        // Final Live URL
                        liveURL_final = extUrlFix;

//                    // Encoding the Live URL
//                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
//                    url = url.replace(extUrl, extUrlFix);
                    }

                    // takagen99 : Getting EPG URL from File Config & put into Settings
                    if (livesOBJ.has("epg")) {
                        String epg = livesOBJ.get("epg").getAsString();
                        System.out.println("EPG URL :" + epg);
                        //putEPGHistory(epg);
                        // Overwrite with EPG URL from Settings
                        //if (StringUtils.isBlank(epgURL)) {
                            Hawk.put(HawkConfig.EPG_URL, epg);
//                        } else {
//                            Hawk.put(HawkConfig.EPG_URL, epgURL);
//                        }
                    }

//                // Populate Live Channel Listing
//                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
//                liveChannelGroup.setGroupName(url);
//                liveChannelGroupList.add(liveChannelGroup);

                } else {

                    // if FongMi Live URL Formatting exists
                    if (!lives.contains("type")) {
                        loadLives(infoJson.get("lives").getAsJsonArray());
                    } else {
                        JsonObject fengMiLives = null;
                        for (JsonElement liveElement : infoJson.get("lives").getAsJsonArray()) {
                            if (!liveElement.isJsonObject()) {
                                continue;
                            }
                            JsonObject candidate = liveElement.getAsJsonObject();
                            if (!candidate.has("type") || !"0".equals(candidate.get("type").getAsString())
                                    || !candidate.has("url")) {
                                continue;
                            }
                            String candidateUrl = candidate.get("url").getAsString();
                            if (!isUnsupportedLocalLiveProxy(candidateUrl)) {
                                fengMiLives = candidate;
                                break;
                            }
                        }
                        if (fengMiLives != null) {
                            String url = fengMiLives.get("url").getAsString();

                            // takagen99 : Getting EPG URL from File Config & put into Settings
                            if (fengMiLives.has("epg")) {
                                String epg = fengMiLives.get("epg").getAsString();
                                System.out.println("EPG URL :" + epg);
                                //putEPGHistory(epg);
                                // Overwrite with EPG URL from Settings
                                //if (StringUtils.isBlank(epgURL)) {
                                    Hawk.put(HawkConfig.EPG_URL, epg);
//                                } else {
//                                    Hawk.put(HawkConfig.EPG_URL, epgURL);
//                                }
                            }

                            if (url.startsWith("http")) {
                                // takagen99: Capture Live URL into Settings
                                System.out.println("Live URL :" + url);
                                putLiveHistory(url);
                                // Overwrite with Live URL from Settings
                                if (!StringUtils.isBlank(liveURL)) {
                                    url = liveURL;
                                }

                                // Final Live URL
                                liveURL_final = url;

//                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                            }
                        }
                    }
                }

                // takagen99: Load Live Channel from settings URL (WIP)
                if (StringUtils.isBlank(liveURL_final)) {
                    liveURL_final = liveURL;
                }
                if (!StringUtils.isBlank(liveURL_final)) {
                    liveURL_final = Base64.encodeToString(liveURL_final.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                    liveURL_final = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + liveURL_final;
                    LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
                    liveChannelGroup.setGroupName(liveURL_final);
                    liveChannelGroupList.add(liveChannelGroup);
                }
            }


        } catch (Throwable th) {
            th.printStackTrace();
        }


        //video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        rule.add(one.getAsString());
                    }

                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                    }
                }
            }
        }

        String defaultIJKADS="{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson=new Gson().fromJson(defaultIJKADS, JsonObject.class);
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
            //追加的广告拦截
            if(infoJson.has("ads")){
                for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                    if(!AdBlocker.hasHost(host.getAsString())){
                        AdBlocker.addAdHost(host.getAsString());
                    }
                }
            }
        }
        // IJK解码配置
        if(ijkCodes==null){
            ijkCodes = new ArrayList<>();
            boolean foundOldSelect = false;
            String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
            JsonArray ijkJsonArray = infoJson.has("ijk")?infoJson.get("ijk").getAsJsonArray():defaultJson.get("ijk").getAsJsonArray();
            for (JsonElement opt : ijkJsonArray) {
                JsonObject obj = (JsonObject) opt;
                String name = obj.get("group").getAsString();
                LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
                for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                    JsonObject cObj = (JsonObject) cfg;
                    String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                    String val = cObj.get("value").getAsString();
                    baseOpt.put(key, val);
                }
                IJKCode codec = new IJKCode();
                codec.setName(name);
                codec.setOption(baseOpt);
                if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true);
                    ijkCodec = name;
                    foundOldSelect = true;
                } else {
                    codec.selected(false);
                }
                ijkCodes.add(codec);
            }
            if (!foundOldSelect && ijkCodes.size() > 0) {
                ijkCodes.get(0).selected(true);
            }
        }
    }

    private boolean isUnsupportedLocalLiveProxy(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                return false;
            }
            String action = uri.getQueryParameter("do");
            return "/proxy".equals(uri.getPath()) && !TextUtils.isEmpty(action) && !"live".equals(action);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void putLiveHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
            if (!liveHistory.contains(url))
                liveHistory.add(0, url);
            if (liveHistory.size() > 20)
                liveHistory.remove(20);
            Hawk.put(HawkConfig.LIVE_HISTORY, liveHistory);
        }
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelIndex(channelIndex++);
                liveChannelItem.setChannelNum(++channelNum);
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + Integer.toString(sourceIndex));
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                liveChannelGroup.getLiveChannels().add(liveChannelItem);
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    public String getSpider() {
        return spider;
    }

    public Spider getCSP(SourceBean sourceBean) {
        boolean js = sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?");
        if (js) return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
    }

    public Object[] proxyLocal(Map param) {
        return jarLoader.proxyInvoke(param);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        // fromCache=true 表示本次数据来自本地缓存（未走网络），false 表示来自网络拉取
        default void success(boolean fromCache) {
            success();
        }

        void success();

        void retry();

        void error(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key))
            return null;
        return sourceBeanList.get(key);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        Hawk.put(HawkConfig.HOME_API, sourceBean.getKey());
        // 同时按当前订阅配置记一份，切走再切回来能恢复用户选过的站点
        if (!TextUtils.isEmpty(mCurApiUrl)) {
            try {
                Hawk.put(HOME_API_OF_PREFIX + MD5.encode(mCurApiUrl), sourceBean.getKey());
            } catch (Throwable ignore) {
            }
        }
    }

    /** 取出某个订阅配置上次选中的首页站点 key（没有则返回空） */
    private String homeApiOf(String apiUrl) {
        if (TextUtils.isEmpty(apiUrl)) return "";
        try {
            return Hawk.get(HOME_API_OF_PREFIX + MD5.encode(apiUrl), "");
        } catch (Throwable th) {
            return "";
        }
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        Hawk.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    /**
     * 避免离线(订阅未配置成功时),parseJson未调用,ijkCodes未初始化报空指针
     * @return
     */
    private List<IJKCode> offlineGetIjkCodes() {

        String defaultIJKADS = "{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson = new Gson().fromJson(defaultIJKADS, JsonObject.class);

        List<IJKCode> ijkCodes = new ArrayList<>();
        boolean foundOldSelect = false;
        String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
        JsonArray ijkJsonArray = defaultJson.get("ijk").getAsJsonArray();
        for (JsonElement opt : ijkJsonArray) {
            JsonObject obj = (JsonObject) opt;
            String name = obj.get("group").getAsString();
            LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
            for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                JsonObject cObj = (JsonObject) cfg;
                String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                String val = cObj.get("value").getAsString();
                baseOpt.put(key, val);
            }
            IJKCode codec = new IJKCode();
            codec.setName(name);
            codec.setOption(baseOpt);
            if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                codec.selected(true);
                ijkCodec = name;
                foundOldSelect = true;
            } else {
                codec.selected(false);
            }
            ijkCodes.add(codec);
        }
        if (!foundOldSelect && ijkCodes.size() > 0) {
            ijkCodes.get(0).selected(true);
        }
        return ijkCodes;
    }

    /**
     * 订阅成功还是用拉取的ijk解码配置,未拉取成功时用默认的
     * @return
     */
    public List<IJKCode> getIjkCodes() {
        return ijkCodes==null?offlineGetIjkCodes():ijkCodes;
    }

    public IJKCode getCurrentIJKCode() {
        String codeName = Hawk.get(HawkConfig.IJK_CODEC, "");
        return getIJKCodec(codeName);
    }

    public IJKCode getIJKCodec(String name) {
        for (IJKCode code : getIjkCodes()) {
            if (code.getName().equals(name))
                return code;
        }
        return ijkCodes.get(0);
    }

    String clanToAddress(String lanLink) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./")) {
            if(!url.startsWith("http") && !url.startsWith("clan://")){
                url = "http://" + url;
            }
            if(url.startsWith("clan://"))url=clanToAddress(url);
            content = content.replace("./", url.substring(0,url.lastIndexOf("/") + 1));
        }
        return content;
    }
}
