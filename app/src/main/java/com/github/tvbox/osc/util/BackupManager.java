package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.SharedPreferences;

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

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据备份/恢复：导出 JSON 文件
 * 包含：收藏、历史、关键设置（订阅源、直播、播放器配置等）
 */
public class BackupManager {

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
            SharedPreferences sp = App.getInstance().getSharedPreferences("Hawk", Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            for (String key : BACKUP_KEYS) {
                if (key.equals(HawkConfig.SUBSCRIPTIONS)) {
                    // 订阅源是 List<Subscription>: 用 Hawk 取出真实对象后再 Gson 序列化,
                    // 不依赖 SharedPreferences 原始字符串格式, 保证导出的是合法的订阅列表 JSON
                    try {
                        ArrayList<Subscription> subs = Hawk.get(HawkConfig.SUBSCRIPTIONS, new ArrayList<Subscription>());
                        settings.add(HawkConfig.SUBSCRIPTIONS, JsonParser.parseString(new Gson().toJson(subs)));
                    } catch (Exception ignore) {
                    }
                    continue;
                }
                if (all.containsKey(key)) {
                    Object val = all.get(key);
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
                SharedPreferences sp = App.getInstance().getSharedPreferences("Hawk", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sp.edit();
                Map<String, ?> all = sp.getAll();
                for (String key : BACKUP_KEYS) {
                    if (key.equals(HawkConfig.SUBSCRIPTIONS)) {
                        // 订阅源是 List<Subscription> 复杂对象: 必须走 Hawk.put 才能同时写回
                        // "值"与"类型标记", 否则 Hawk 反序列化会失败/退化成 LinkedTreeMap, 订阅管理读不到
                        continue;
                    }
                    if (settings.has(key)) {
                        try {
                            com.google.gson.JsonElement el = settings.get(key);
                            if (el.isJsonPrimitive()) {
                                com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
                                Object orig = all.get(key);
                                if (p.isBoolean()) {
                                    editor.putBoolean(key, p.getAsBoolean());
                                } else if (p.isNumber()) {
                                    if (orig instanceof Integer) {
                                        editor.putInt(key, p.getAsInt());
                                    } else if (orig instanceof Long) {
                                        editor.putLong(key, p.getAsLong());
                                    } else if (orig instanceof Float) {
                                        editor.putFloat(key, p.getAsFloat());
                                    } else {
                                        editor.putString(key, p.getAsString());
                                    }
                                } else {
                                    editor.putString(key, p.getAsString());
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                // 单独恢复订阅源列表(值 + 类型标记一起写)
                if (settings.has(HawkConfig.SUBSCRIPTIONS)) {
                    try {
                        String subsJson = settings.get(HawkConfig.SUBSCRIPTIONS).getAsString();
                        java.lang.reflect.Type type = new TypeToken<ArrayList<Subscription>>() {
                        }.getType();
                        ArrayList<Subscription> subs = new Gson().fromJson(subsJson, type);
                        if (subs != null) {
                            Hawk.put(HawkConfig.SUBSCRIPTIONS, subs);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                editor.apply();
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
