package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.data.AppDataManager;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「重置软件」：把本地数据清成刚安装完的样子，等价于系统设置里的「清除应用数据」，
 * 只是不用真的退出到系统设置去操作。
 *
 * <p>为什么需要它：覆盖安装时（尤其是跨多个版本升级），SharedPreferences、数据库、
 * 缓存文件会带着旧版本的残留状态过来。有些"莫名其妙"的问题——首页加载不出来、
 * 切换订阅后源列表异常、播放器行为怪异——其实清一次数据就好了。以前只能去系统设置里
 * 手动清除数据，现在软件内就能做。
 *
 * <p>清除范围：
 * <ul>
 *   <li>Hawk（SharedPreferences {@code Hawk2}）：所有设置项、订阅列表、播放/直播偏好</li>
 *   <li>Room 数据库：观看历史、收藏、搜索缓存</li>
 *   <li>filesDir：配置缓存（{@code <md5>} 与 {@code .meta}）、{@code csp_*.jar} 插件、
 *       本地导入的订阅/直播源</li>
 *   <li>cacheDir：海报图片、dex 优化产物</li>
 * </ul>
 *
 * <p>不动的：外置存储里的东西（用户导出的备份文件等），以及 App 自身。
 * 清除完成后由调用方重启进程，避免内存里还留着旧状态。
 */
public final class AppResetHelper {

    public interface Callback {
        /**
         * 在主线程回调。
         *
         * @param success 是否全部清除成功
         * @param message 给用户看的说明（失败时包含具体哪部分没清掉）
         */
        void onFinished(boolean success, String message);
    }

    private AppResetHelper() {
    }

    public static void resetAll(Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> failed = new ArrayList<>();

                // 1. 设置、订阅、播放偏好
                try {
                    Hawk.deleteAll();
                } catch (Throwable th) {
                    th.printStackTrace();
                    failed.add("设置项");
                }

                // 2. 观看历史 / 收藏 / 搜索缓存
                try {
                    // Room 的 clearAllTables() 不允许在主线程调用，这里本身就是子线程
                    AppDataManager.get().clearAllTables();
                } catch (Throwable th) {
                    th.printStackTrace();
                    failed.add("历史与收藏");
                }

                // 3. 私有目录（配置缓存、插件 jar、本地导入的配置）
                // 4. 缓存目录（海报图片、dex 优化产物）
                File filesDir = null;
                File cacheDir = null;
                try {
                    filesDir = App.getInstance().getFilesDir();
                    cacheDir = App.getInstance().getCacheDir();
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                if (!clearDir(filesDir)) {
                    failed.add("配置缓存");
                }
                if (!clearDir(cacheDir)) {
                    failed.add("图片缓存");
                }

                boolean success = failed.isEmpty();
                final String message;
                if (success) {
                    message = "已重置";
                } else {
                    StringBuilder sb = new StringBuilder("以下数据未能完全清除：");
                    for (int i = 0; i < failed.size(); i++) {
                        if (i > 0) {
                            sb.append("、");
                        }
                        sb.append(failed.get(i));
                    }
                    message = sb.toString();
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onFinished(success, message);
                        }
                    }
                });
            }
        }, "app-reset").start();
    }

    /**
     * 清空目录内容，保留目录本身（有些代码假定目录存在）。
     *
     * @return 是否全部删除成功
     */
    private static boolean clearDir(File dir) {
        if (dir == null || !dir.exists()) {
            return true;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return true;
        }
        boolean ok = true;
        for (File child : children) {
            if (!deleteRecursively(child)) {
                ok = false;
            }
        }
        return ok;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }
}
