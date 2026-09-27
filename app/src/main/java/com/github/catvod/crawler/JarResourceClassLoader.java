package com.github.catvod.crawler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 只负责"从 jar 里读资源"的类加载器, <b>不参与类加载</b>(findClass 一律抛 ClassNotFoundException)。
 *
 * <p>用途: Android 14(API 34) 及以上只能用 {@code InMemoryDexClassLoader} 加载 csp.jar 里的 dex
 * (系统禁止 DexClassLoader 加载可写目录里的 dex), 而它是 <b>final</b> 类、无法继承,
 * 且从内存加载 dex 时 DexPathList 里没有 jar 路径, 导致
 * {@code getResourceAsStream("assets/ftyguard_v8.so")} 一律返回 null。
 *
 * <p>而 csp.jar 里的 {@code com.github.catvod.spider.DexNative} 偏偏要靠这个调用把自带的
 * native 库取出来 {@code System.load()}。取不到就会: <br>
 * DexNative 类初始化失败 → {@code Init.init()} 抛异常 → 所有继承 BaseSpiderGuard 的源
 * (如 DouDouGuard) 全部退化成 SpiderNull → <b>首页分类集体消失, 只剩"主页"</b>。
 *
 * <p>解决办法: 把本类作为 {@code InMemoryDexClassLoader} 的 <b>parent</b>。
 * ClassLoader 的资源查找是"先自身、再逐级问父加载器", 所以 dex 加载器的
 * {@code getResource/getResourceAsStream} 会落到这里, 从原始 jar 的 zip 条目读出来即可。
 */
public class JarResourceClassLoader extends ClassLoader {

    private final File jarFile;

    public JarResourceClassLoader(File jarFile, ClassLoader parent) {
        super(parent);
        this.jarFile = jarFile;
    }

    /** 本类只提供资源, 类加载交给子加载器(内存 dex 加载器)完成。 */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream in = openFromJar(name);
        if (in != null) return in;
        return super.getResourceAsStream(name);
    }

    @Override
    public URL getResource(String name) {
        if (jarFile != null && name != null && jarFile.exists()) {
            try {
                return new URL("jar:" + jarFile.toURI().toURL() + "!/" + name);
            } catch (Throwable ignored) {
            }
        }
        return super.getResource(name);
    }

    /**
     * 从 jar 的 zip 条目读取内容。
     * 内容一次性读进内存后立即关闭 ZipFile, 避免长期占用文件句柄(条目都是几十 KB 的 so)。
     */
    private InputStream openFromJar(String name) {
        if (jarFile == null || name == null || !jarFile.exists()) return null;
        ZipFile zip = null;
        try {
            zip = new ZipFile(jarFile);
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            long size = entry.getSize();
            ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 ? (int) size : 8192);
            InputStream in = zip.getInputStream(entry);
            try {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } finally {
                in.close();
            }
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Throwable th) {
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
