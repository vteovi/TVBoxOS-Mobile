package com.github.catvod.crawler;

import android.content.Context;
import android.os.Build;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;
import okhttp3.Response;

public class JarLoader {
    private ConcurrentHashMap<String, BaseDexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private volatile String recentJarKey = "";
    /** 类加载器整表替换的锁：加载可以并发触发，但真正替换必须串行 */
    private final Object loadLock = new Object();

    /** 加载序号：并发加载时只有"最新发起"的那一次允许落地，避免旧任务晚到覆盖新配置 */
    private final java.util.concurrent.atomic.AtomicLong issuedSeq =
            new java.util.concurrent.atomic.AtomicLong();
    private long committedSeq = 0;

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        return load(cache, "main");
    }

    /**
     * 按指定 key 注册类加载器。
     *
     * <p>之前不论加载的是哪个 jar，都固定注册成 "main"。而 {@link #getSpider} 在源带 jar 时
     * 算出来的 key 是 {@code MD5.string2MD5(jarUrl)}，永远命中不了这条已注册的记录，
     * 于是退回到 {@link #loadJarInternal} 用完全不同的文件名重新下载一遍同一个 jar。
     *
     * <p>老机型/弱网下这一遍下载会超过首页分栏 15 秒的超时，超时后线程被打断，
     * 写一半的 jar 留在磁盘上，下次启动校验不过再下 —— 恶性循环，
     * 表现就是首页永远只有「主页」一个标签，下面什么都取不到。
     *
     * <p>用 jarKey 注册后，getSpider 直接命中内存里已经加载好的类加载器，不再重复下载。
     */
    /**
     * 加载（替换）当前生效的 jar。
     *
     * <p>这里是「整表替换」：spiders / proxyMethods / classLoaders 会全部清空再重建。
     * 现在 jar 下载和加载可能并发（切源快、上一份还没加载完就切下一份），
     * 两个加载交错执行会把对方刚注册好的类加载器清掉，
     * 表现就是「刚加载成功，取数据又变成空」。所以这一段必须串行。
     *
     * <p>只锁 dex 加载，不锁网络下载：下载仍在各自线程里并行，只是排队等这一步。
     */
    public boolean load(String cache, String key) {
        return load(cache, key, 0L);
    }

    /** 由调用方在"发起一次订阅加载"时取一个递增序号，用来识别过期的加载结果 */
    public long issueLoadSeq() {
        return issuedSeq.incrementAndGet();
    }

    /**
     * @param seq 加载序号，0 表示无条件落地；非 0 时，若已经有更新的加载落地过，
     *            本次结果直接忽略（不清表、不注册），避免切源时旧任务把新配置顶掉。
     */
    public boolean load(String cache, String key, long seq) {
        synchronized (loadLock) {
            if (seq > 0 && seq < committedSeq) {
                System.out.println("自定义爬虫代码加载已过期，忽略: " + seq + " < " + committedSeq);
                return true;
            }
            if (seq > 0) committedSeq = seq;
            spiders.clear();
            recentJarKey = key;
            proxyMethods.clear();
            classLoaders.clear();
            boolean ok = loadClassLoader(cache, key);
            if (ok && !"main".equals(key)) {
                // 源的 jar 字段为空时 getSpider 会走 "main" 分支，这里一并注册，两边都能命中
                BaseDexClassLoader loader = classLoaders.get(key);
                if (loader != null) {
                    classLoaders.put("main", loader);
                }
            }
            return ok;
        }
    }

    private boolean loadClassLoader(String jar, String key) {
        // 与 load() 共用同一把锁（可重入），保证类加载器表的读写不会交错
        synchronized (loadLock) {
            return doLoadClassLoader(jar, key);
        }
    }

    private boolean doLoadClassLoader(String jar, String key) {
        boolean success = false;
        try {
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            BaseDexClassLoader classLoader = createClassLoader(jar, cacheDir);
            // 先把本地代理端口写进 jar 里的 com.github.catvod.Proxy(默认 -1)。
            // 这一步漏了, spider 拼出的 m3u8 代理地址就是 http://127.0.0.1:-1/proxy?...,
            // 播放器连不上, 表现是"能搜到剧但点了播不了"。
            bindProxyPort(classLoader);

            // 第一步: 只要能把 Init 类加载出来, 就说明 jar/dex 本身是完好的, 订阅即视为可用。
            // 这是判断"订阅能不能用"的唯一标准。
            // 原实现必须在下面的 Init.init() 也成功后才算成功, 导致 Init 里任何一个非致命
            // 错误(最典型的是 GoProxy 等原生库运行时下载失败)都会把**整份订阅**判死:
            // 类加载器不注册 -> 所有源取不到 -> 首页只剩"主页"并弹"更新订阅失败"。
            Class classInit = null;
            // make force wait here, some device async dex load
            int count = 0;
            do {
                try {
                    classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                if (classInit == null) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                }
                count++;
            } while (classInit == null && count < 50);

            if (classInit == null) {
                // dex 真的加载不出来, 此时才判定订阅失败。
                // 注意等待窗口: 原本只等 5 x 200ms = 1 秒, 老机型上几 MB 的 jar
                // 走 dex2oat/verify 经常要好几秒, 1 秒内拿不到 Init 类就被判死,
                // 整份订阅直接不可用(首页只剩"主页"一个标签)。这里放宽到 10 秒。
                return false;
            }

            success = true;
            classLoaders.put(key, classLoader);
            System.out.println("自定义爬虫代码加载成功!");

            // 第二步: 调用 Init.init()。失败只代表依赖它的个别源不可用, 不影响整份订阅。
            try {
                Method method = classInit.getMethod("init", Context.class);
                method.invoke(null, App.getInstance());
            } catch (Throwable th) {
                System.out.println("自定义爬虫 Init 初始化失败(仅影响依赖它的源, 订阅仍可用): " + th);
                th.printStackTrace();
            }
            // Init 里可能重新初始化了 Proxy, 再写一次端口(幂等)
            bindProxyPort(classLoader);

            // 第三步: Proxy 为可选能力, 没有也不影响其它源
            try {
                Class proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                Method mth = proxy.getMethod("proxy", Map.class);
                proxyMethods.put(key, mth);
            } catch (Throwable th) {

            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    /**
     * 把宿主本地服务的真实端口写进 {@code com.github.catvod.Proxy}。
     * 宿主持有同名类时 loadClass 命中的就是宿主那份，jar 自带一份时也能写中，
     * 两种情况都能避免 spider 拼出 {@code 127.0.0.1:-1} 的地址。
     */
    private void bindProxyPort(BaseDexClassLoader loader) {
        try {
            Class<?> clazz = loader.loadClass("com.github.catvod.Proxy");
            clazz.getMethod("set", int.class).invoke(null, com.github.tvbox.osc.server.RemoteServer.serverPort);
        } catch (Throwable ignored) {
        }
    }

    /** Android 14(API 34) 起, 系统禁止 DexClassLoader 加载"可写目录"里的 dex。 */
    private static final int SDK_ANDROID_14 = 34;

    /**
     * 构建加载 csp.jar 的类加载器。
     *
     * <p>Android 14 以下: 沿用传统 DexClassLoader。它在加载 jar 时会在 DexPathList 里建好 ZipFile,
     * 所以 {@code getResourceAsStream("assets/ftyguard_*.so")} 这类"读 jar 内资源"的调用是通的。
     * csp.jar 里的 {@code com.github.catvod.spider.DexNative} 正是靠这条路径取出自带的 native 库,
     * 破坏它就会连带弄挂所有继承 BaseSpiderGuard 的源(首页分类集体消失、只剩"主页")。
     *
     * <p>Android 14 及以上: 只允许从内存加载 dex(InMemoryDexClassLoader, final 不可继承),
     * 那就把 {@link JarResourceClassLoader} 挂成它的 parent, 用"父加载器补资源"的方式
     * 同时兼顾 dex 与 jar 内资源两种访问。
     */
    public static BaseDexClassLoader createClassLoader(String jar, File optDir) throws Throwable {
        if (Build.VERSION.SDK_INT >= SDK_ANDROID_14) {
            try {
                ByteBuffer[] buffers = readDexBuffers(jar);
                if (buffers != null && buffers.length > 0) {
                    // 关键: 用"能从 jar 里读资源"的加载器当 parent。
                    // InMemoryDexClassLoader 自己找不到 jar 里的 assets/*.so,
                    // 但 ClassLoader 的资源查找会逐级向上问父加载器, 于是能命中。
                    ClassLoader resourceParent =
                            new JarResourceClassLoader(new File(jar), App.getInstance().getClassLoader());
                    return new InMemoryDexClassLoader(buffers, resourceParent);
                }
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        try {
            new File(jar).setReadOnly();
        } catch (Throwable ignored) {
        }
        return new DexClassLoader(jar, optDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
    }

    /**
     * 从 jar/zip(或裸 dex) 中读出所有 classes*.dex, 转成直接内存 ByteBuffer。
     */
    private static ByteBuffer[] readDexBuffers(String jarPath) throws Throwable {
        File file = new File(jarPath);
        byte[] head = new byte[4];
        FileInputStream fis = new FileInputStream(file);
        int n = fis.read(head);
        fis.close();
        // 裸 dex 文件, 魔数为 "dex\n"
        if (n == 4 && head[0] == 'd' && head[1] == 'e' && head[2] == 'x' && head[3] == '\n') {
            return new ByteBuffer[]{toDirectBuffer(readAll(file))};
        }
        ZipFile zip = new ZipFile(file);
        try {
            ArrayList<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".dex") && name.indexOf('/') < 0) {
                    names.add(name);
                }
            }
            Collections.sort(names);
            if (names.isEmpty()) {
                return null;
            }
            ByteBuffer[] buffers = new ByteBuffer[names.size()];
            for (int i = 0; i < names.size(); i++) {
                buffers[i] = toDirectBuffer(readAll(zip.getInputStream(zip.getEntry(names.get(i)))));
            }
            return buffers;
        } finally {
            zip.close();
        }
    }

    private static ByteBuffer toDirectBuffer(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private static byte[] readAll(InputStream is) throws Throwable {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        } finally {
            is.close();
        }
    }

    private static byte[] readAll(File file) throws Throwable {
        return readAll(new FileInputStream(file));
    }

    private BaseDexClassLoader loadJarInternal(String jar, String md5, String key) {
        if (classLoaders.contains(key))
            return classLoaders.get(key);
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + key + ".jar");
        if (!cache.exists()) {
            // ApiConfig.loadJar 把 jar 落在 csp_<md5>.jar，这里原本只认 <md5>.jar，
            // 同一个 jar 会被再下载一遍。能对上就直接复用，省掉一次几 MB 的下载
            File prefixed = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp_" + key + ".jar");
            if (prefixed.exists() && (md5.isEmpty() || MD5.getFileMd5(prefixed).equalsIgnoreCase(md5))) {
                cache = prefixed;
            }
        }
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                loadClassLoader(cache.getAbsolutePath(), key);
                return classLoaders.get(key);
            }
        }
        try {
            Response response = OkGo.<File>get(jar).execute();
            InputStream is = response.body().byteStream();
            // 同样先写临时文件再替换：这条路径是同步下载（源自带 jar 时才走），
            // 中途失败不能把上一份能用的 jar 弄坏
            File tmp = new File(cache.getAbsolutePath() + ".tmp");
            if (tmp.exists()) tmp.delete();
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buffer = new byte[2048];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    os.write(buffer, 0, length);
                }
            } finally {
                try {
                    is.close();
                    os.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (cache.exists()) cache.delete();
            if (!tmp.renameTo(cache)) {
                // 极少数情况下 rename 失败，退化成直接用临时文件加载
                loadClassLoader(tmp.getAbsolutePath(), key);
                return classLoaders.get(key);
            }
            loadClassLoader(cache.getAbsolutePath(), key);
            return classLoaders.get(key);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        String clsKey = cls.replace("csp_", "");
        String jarUrl = "";
        String jarMd5 = "";
        String jarKey = "";
        if (jar.isEmpty()) {
            jarKey = "main";
        } else {
            String[] urls = jar.split(";md5;");
            jarUrl = urls[0];
            jarKey = MD5.string2MD5(jarUrl);
            jarMd5 = urls.length > 1 ? urls[1].trim() : "";
        }
        recentJarKey = jarKey;
        if (spiders.containsKey(key))
            return spiders.get(key);
        BaseDexClassLoader classLoader = null;
        if (jarKey.equals("main"))
            classLoader = classLoaders.get("main");
        else {
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
        }
        if (classLoader == null)
            return new SpiderNull();
        try {
            Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
            sp.init(App.getInstance(), ext);
//            if (!jar.isEmpty()) {
//                sp.homeContent(false); // 增加此行 应该可以解决部分写的有问题源的历史记录问题 但会增加这个源的首次加载时间 不需要可以已删掉
//            }
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            BaseDexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            BaseDexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    /**
     * /proxy 的分发入口。
     *
     * <p>订阅里每个源可能各带一份 jar, jar 里的 {@code com.github.catvod.spider.Proxy}
     * 是按 jar 注册的; 而 /proxy 请求过来时只认 {@link #recentJarKey}。
     * 搜索是并发的、详情页也可能先跑过别的源, recentJarKey 很容易指向另一份 jar,
     * 于是 proxyMethods.get(recentJarKey) 拿到的是错的那份(或者 null) ->
     * /proxy 返回空 -> 播放器报 Exo 2004。这里在 recent 失手时把已注册的 jar 都试一遍。</p>
     */
    public Object[] proxyInvoke(Map params) {
        Object[] rs = invokeProxy(proxyMethods.get(recentJarKey), params);
        if (rs != null) return rs;
        for (java.util.Map.Entry<String, Method> entry : proxyMethods.entrySet()) {
            if (entry.getKey().equals(recentJarKey)) continue;
            rs = invokeProxy(entry.getValue(), params);
            if (rs != null) return rs;
        }
        return null;
    }

    private Object[] invokeProxy(Method proxyFun, Map params) {
        try {
            if (proxyFun == null) return null;
            return (Object[]) proxyFun.invoke(null, params);
        } catch (Throwable th) {

        }
        return null;
    }
}
