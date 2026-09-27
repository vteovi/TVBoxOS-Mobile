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

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        spiders.clear();
        recentJarKey = "main";
        proxyMethods.clear();
        classLoaders.clear();
        return loadClassLoader(cache, "main");
    }

    private boolean loadClassLoader(String jar, String key) {
        boolean success = false;
        try {
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            BaseDexClassLoader classLoader = createClassLoader(jar, cacheDir);

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
            } while (classInit == null && count < 5);

            if (classInit == null) {
                // dex 真的加载不出来, 此时才判定订阅失败
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
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                loadClassLoader(cache.getAbsolutePath(), key);
                return classLoaders.get(key);
            }
        }
        try {
            Response response = OkGo.<File>get(jar).execute();
            InputStream is = response.body().byteStream();
            OutputStream os = new FileOutputStream(cache);
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

    public Object[] proxyInvoke(Map params) {
        try {
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {

        }
        return null;
    }
}
