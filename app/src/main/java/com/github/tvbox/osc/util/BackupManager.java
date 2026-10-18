package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.cache.VodRecord;
import com.github.tvbox.osc.data.AppDataManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据备份/恢复：导出 JSON 文件
 * 包含：收藏、历史、关键设置（订阅源、直播、播放器配置等）
 */
public class BackupManager {

    /**
     * Hawk 2.0.1 内部默认 SharedPreferences 文件名(常量 STORAGE_TAG_DO_NOT_CHANGE = "Hawk2")。
     * 之前误用 "Hawk": 导出读到的是不存在的文件(读不到任何设置), 导入又写进该文件,
     * 而 Hawk 实际读写 "Hawk2" → 设置项(API_URL/HOME_API 等)同步后不生效, 首页仍是接收端旧源。
     */
    private static final String HAWK_PREF = "Hawk2";

    // 要备份的 Hawk key 列表
    private static final String[] BACKUP_KEYS = {
            HawkConfig.API_URL,
            HawkConfig.LIVE_URL,
            HawkConfig.EPG_URL,
            HawkConfig.SUBSCRIPTIONS,
            HawkConfig.LIVE_HISTORY,
            HawkConfig.EPG_HISTORY,
            HawkConfig.HOME_API,
            HawkConfig.DEFAULT_PARSE,
            HawkConfig.IJK_CODEC,
            HawkConfig.PLAY_TYPE,
            HawkConfig.PLAY_RENDER,
            HawkConfig.PLAY_SCALE,
            HawkConfig.PLAY_TIME_STEP,
            HawkConfig.DOH_URL,
            HawkConfig.HOME_REC,
            HawkConfig.HISTORY_NUM,
            HawkConfig.LIVE_CHANNEL,
            HawkConfig.LIVE_CHANNEL_REVERSE,
            HawkConfig.LIVE_CROSS_GROUP,
            HawkConfig.LIVE_CONNECT_TIMEOUT,
            HawkConfig.LIVE_SHOW_NET_SPEED,
            HawkConfig.LIVE_SHOW_TIME,
            HawkConfig.FAST_SEARCH_MODE,
            HawkConfig.SUBTITLE_TEXT_SIZE,
            HawkConfig.SUBTITLE_TIME_DELAY,
            HawkConfig.SOURCES_FOR_SEARCH,
            HawkConfig.REMOTE_TVBOX,
            HawkConfig.IJK_CACHE_PLAY,
            HawkConfig.PRIVATE_BROWSING,
            HawkConfig.THEME_TAG,
            HawkConfig.BACKGROUND_PLAY_TYPE,
            HawkConfig.VIDEO_PURIFY,
            HawkConfig.VIDEO_SPEED,
            HawkConfig.WALLPAPER_URL,
            HawkConfig.WALLPAPER_HISTORY,
            HawkConfig.HISTORY_SEARCH,
            HawkConfig.POSTER_SIZE,
            HawkConfig.LANDSCAPE_CARD,
            HawkConfig.SHOW_PREVIEW,
            HawkConfig.DEBUG_OPEN
    };

    /**
     * 导出备份到 JSON 文件
     */
    public static boolean exportBackup(File outFile) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("app", "TVBoxOS-Mobile");
            root.addProperty("exportTime", System.currentTimeMillis());

            // 1. 收藏列表
            List<VodCollect> collects = AppDataManager.get().getVodCollectDao().getAll();
            JsonArray collectArr = new JsonArray();
            for (VodCollect c : collects) {
                JsonObject obj = new JsonObject();
                obj.addProperty("sourceKey", c.sourceKey);
                obj.addProperty("vodId", c.vodId);
                obj.addProperty("name", c.name);
                obj.addProperty("pic", c.pic);
                obj.addProperty("updateTime", c.updateTime);
                collectArr.add(obj);
            }
            root.add("collects", collectArr);

            // 2. 历史记录
            List<VodRecord> records = AppDataManager.get().getVodRecordDao().getAll(100000);
            JsonArray recordArr = new JsonArray();
            for (VodRecord r : records) {
                JsonObject obj = new JsonObject();
                obj.addProperty("sourceKey", r.sourceKey);
                obj.addProperty("vodId", r.vodId);
                obj.addProperty("updateTime", r.updateTime);
                obj.addProperty("dataJson", r.dataJson);
                recordArr.add(obj);
            }
            root.add("records", recordArr);

            // 3. Hawk 设置
            JsonObject settings = new JsonObject();
            // 本地(文件/ content)配置内容池: key=原 url, value=base64 文件内容。
            // 设备间同步/换机时, 仅 URL 字符串无法跨设备使用(指向发送端私有路径), 必须把文件内容一起带走, 接收端据此重建私有文件。
            // 覆盖: 订阅源(SUBSCRIPTIONS)、当前影视源(API_URL)、直播源(LIVE_URL) 等本地文件型配置。
            JsonObject localFiles = null;
            SharedPreferences sp = App.getInstance().getSharedPreferences(HAWK_PREF, Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            for (String key : BACKUP_KEYS) {
                if (key.equals(HawkConfig.API_URL) || key.equals(HawkConfig.LIVE_URL)) {
                    // 本地文件型配置(影视源/直播源): sp 里存的是 Hawk 的 "type#value" 包装串,
                    // 直接拿来判断 isLocalUrl 会误判, 必须用 Hawk API 取真实 url 值再判断与打包。
                    if (Hawk.contains(key)) {
                        try {
                            String real = Hawk.get(key, "");
                            settings.addProperty(key, real);
                            if (isLocalUrl(real)) {
                                byte[] data = readLocalBytes(real);
                                if (data != null) {
                                    if (localFiles == null) {
                                        localFiles = new JsonObject();
                                    }
                                    localFiles.addProperty(real, Base64.encodeToString(data, Base64.NO_WRAP));
                                }
                            }
                        } catch (Exception ignore) {
                        }
                    }
                    continue;
                }
                if (key.equals(HawkConfig.SUBSCRIPTIONS)) {
                    // 订阅源是 List<Subscription>: 用 Hawk 取出真实对象后再 Gson 序列化为
                    // "字符串"写入(不要直接 add JsonArray, 否则导入端 getAsString() 会抛异常而静默失败)。
                    try {
                        ArrayList<Subscription> subs = Hawk.get(HawkConfig.SUBSCRIPTIONS, new ArrayList<Subscription>());
                        settings.addProperty(HawkConfig.SUBSCRIPTIONS, new Gson().toJson(subs));
                        // 把本地订阅文件内容一同打包
                        for (Subscription sub : subs) {
                            if (isLocalUrl(sub.getUrl())) {
                                byte[] data = readLocalBytes(sub.getUrl());
                                if (data != null) {
                                    if (localFiles == null) {
                                        localFiles = new JsonObject();
                                    }
                                    localFiles.addProperty(sub.getUrl(), Base64.encodeToString(data, Base64.NO_WRAP));
                                }
                            }
                        }
                    } catch (Exception ignore) {
                    }
                    continue;
                }
                if (all.containsKey(key)) {
                    Object val = all.get(key);
                    // 其余设置项: 原样搬运 Hawk 的序列化串(内含 type#value 类型信息),
                    // 还原时写回同一文件即可无损恢复, 复杂对象(List/Set 等)也无需知道具体类型。
                    if (val instanceof String) {
                        settings.addProperty(key, (String) val);
                    } else if (val instanceof Integer) {
                        settings.addProperty(key, (Integer) val);
                    } else if (val instanceof Long) {
                        settings.addProperty(key, (Long) val);
                    } else if (val instanceof Boolean) {
                        settings.addProperty(key, (Boolean) val);
                    } else if (val instanceof Float) {
                        settings.addProperty(key, (Float) val);
                    }
                }
            }
            root.add("settings", settings);
            if (localFiles != null) {
                root.add("localFiles", localFiles);
            }

            // 写文件
            FileWriter writer = new FileWriter(outFile);
            writer.write(root.toString());
            writer.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从 JSON 文件恢复
     */
    public static boolean importBackup(File inFile) {
        try {
            FileReader reader = new FileReader(inFile);
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();

            // 1. 恢复收藏
            if (root.has("collects")) {
                JsonArray collectArr = root.getAsJsonArray("collects");
                // 先清空
                AppDataManager.get().getVodCollectDao().deleteAll();
                for (int i = 0; i < collectArr.size(); i++) {
                    JsonObject obj = collectArr.get(i).getAsJsonObject();
                    VodCollect c = new VodCollect();
                    c.sourceKey = obj.has("sourceKey") ? obj.get("sourceKey").getAsString() : "";
                    c.vodId = obj.has("vodId") ? obj.get("vodId").getAsString() : "";
                    c.name = obj.has("name") ? obj.get("name").getAsString() : "";
                    c.pic = obj.has("pic") ? obj.get("pic").getAsString() : "";
                    c.updateTime = obj.has("updateTime") ? obj.get("updateTime").getAsLong() : 0;
                    AppDataManager.get().getVodCollectDao().insert(c);
                }
            }

            // 2. 恢复历史
            if (root.has("records")) {
                JsonArray recordArr = root.getAsJsonArray("records");
                AppDataManager.get().getVodRecordDao().deleteAll();
                for (int i = 0; i < recordArr.size(); i++) {
                    JsonObject obj = recordArr.get(i).getAsJsonObject();
                    VodRecord r = new VodRecord();
                    r.sourceKey = obj.has("sourceKey") ? obj.get("sourceKey").getAsString() : "";
                    r.vodId = obj.has("vodId") ? obj.get("vodId").getAsString() : "";
                    r.updateTime = obj.has("updateTime") ? obj.get("updateTime").getAsLong() : 0;
                    r.dataJson = obj.has("dataJson") ? obj.get("dataJson").getAsString() : "";
                    AppDataManager.get().getVodRecordDao().insert(r);
                }
            }

            // 3. 恢复设置
            if (root.has("settings")) {
                JsonObject settings = root.getAsJsonObject("settings");
                SharedPreferences sp = App.getInstance().getSharedPreferences(HAWK_PREF, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sp.edit();
                // 本地文件内容池(跨设备重建用): key=原本地 url, value=Base64 文件内容
                JsonObject lf = root.has("localFiles") ? root.getAsJsonObject("localFiles") : null;
                for (String key : BACKUP_KEYS) {
                    if (key.equals(HawkConfig.API_URL) || key.equals(HawkConfig.LIVE_URL)) {
                        // 本地文件型配置(影视源/直播源): 导出的是真实 url, 需据打包内容在接收端
                        // 重建文件后用 Hawk.put 写回"真实值"(走 editor 会绕过 Hawk 序列化导致读不出来)。
                        if (settings.has(key)) {
                            try {
                                String raw = settings.get(key).getAsString();
                                raw = restoreLocalFile(raw, lf, "localsources");
                                Hawk.put(key, raw);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        continue;
                    }
                    if (key.equals(HawkConfig.SUBSCRIPTIONS)) {
                        // 订阅源是 List<Subscription> 复杂对象: 必须走 Hawk.put 才能同时写回
                        // "值"与"类型标记", 否则 Hawk 反序列化会失败/退化成 LinkedTreeMap, 订阅管理读不到
                        continue;
                    }
                    if (settings.has(key)) {
                        try {
                            com.google.gson.JsonElement el = settings.get(key);
                            // 其余设置项: 原样写回 Hawk 的序列化串(内含 type#value 类型信息),
                            // 复杂对象(List/Set 等)也能无损还原, 无需知道具体类型。
                            if (el.isJsonPrimitive()) {
                                editor.putString(key, el.getAsString());
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                // 单独恢复订阅源列表(值 + 类型标记一起写)。
                // 兼容两种形态: 旧备份/其它实现可能把订阅存为 JSON 数组, 这里都能解析。
                if (settings.has(HawkConfig.SUBSCRIPTIONS)) {
                    try {
                        com.google.gson.JsonElement subEl = settings.get(HawkConfig.SUBSCRIPTIONS);
                        java.lang.reflect.Type type = new TypeToken<ArrayList<Subscription>>() {
                        }.getType();
                        ArrayList<Subscription> subs = null;
                        if (subEl.isJsonArray()) {
                            subs = new Gson().fromJson(subEl, type);
                        } else if (subEl.isJsonPrimitive()) {
                            subs = new Gson().fromJson(subEl.getAsString(), type);
                        }
                        if (subs != null) {
                            // 本地订阅文件: 用打包进备份的内容在私有目录重建文件, 并重写 URL,
                            // 否则同步/换机后订阅指向发送端私有路径(文件不存在)→ 加载报"无法读取本地配置文件"。
                            for (Subscription sub : subs) {
                                sub.setUrl(restoreLocalFile(sub.getUrl(), lf, "subscriptions"));
                            }
                            Hawk.put(HawkConfig.SUBSCRIPTIONS, subs);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                // 用 commit()(同步落盘)而非 apply(): 导入后会立即重启 App,
                // 异步 apply 可能还没写盘进程就被杀, 导致设置丢失/首页仍是旧源。
                editor.commit();
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ============================ 本地订阅文件辅助 ============================

    private static boolean isLocalUrl(String url) {
        return url != null && (url.startsWith("file://") || url.startsWith("content://"));
    }

    /** 读取本地(私有文件 / content)订阅的内容字节; 读不到返回 null。 */
    private static byte[] readLocalBytes(String url) {
        if (url == null) {
            return null;
        }
        InputStream in = null;
        try {
            if (url.startsWith("file://")) {
                in = new FileInputStream(Uri.parse(url).getPath());
            } else {
                in = App.getInstance().getContentResolver().openInputStream(Uri.parse(url));
            }
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /** 由订阅原 url 派生稳定的私有文件名(跨设备一致, 且不重名)。 */
    private static String localFileName(String url) {
        return md5(url) + ".json";
    }

    /**
     * 若原 url 是本地(file:// / content://)配置文件且备份里带有其内容, 则在接收端私有目录重建文件,
     * 返回重写后的本机 url; 否则原样返回。用于跨设备同步/还原影视源 / 直播源 / 订阅等本地文件型配置。
     * 单条失败 try/catch 跳过, 不影响其余配置。
     */
    private static String restoreLocalFile(String origUrl, JsonObject localFiles, String subDir) {
        if (origUrl == null || localFiles == null || !isLocalUrl(origUrl) || !localFiles.has(origUrl)) {
            return origUrl;
        }
        try {
            byte[] data = Base64.decode(localFiles.get(origUrl).getAsString(), Base64.NO_WRAP);
            File dir = new File(App.getInstance().getFilesDir(), subDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File target = new File(dir, localFileName(origUrl));
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(data);
            fos.close();
            return Uri.fromFile(target).toString();
        } catch (Exception e) {
            e.printStackTrace();
            return origUrl;
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }
}
