package com.github.tvbox.osc.util;

import android.net.Uri;
import android.util.Base64;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.cache.VodCollectDao;
import com.github.tvbox.osc.cache.VodRecord;
import com.github.tvbox.osc.cache.VodRecordDao;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据备份/恢复：导出 JSON 文件
 * 包含：收藏、历史、关键设置（订阅源、直播、播放器配置等）
 */
public class BackupManager {

    /**
     * 导入方式：
     * REPLACE = 清空本机收藏/历史，订阅列表与全部设置一律覆盖（文件备份还原的语义，换机/重置后用）
     * MERGE   = 并集合并，本机已有记录全部保留：
     *           收藏/历史按 (sourceKey, vodId) 去重、取 updateTime 较新的一条；
     *           订阅源列表按 URL（本地文件按内容）去重，只追加发送端独有的条目；
     *           当前生效的影视源 / 首页源 / 直播源本机已有值就保持不变。
     *           播放器、主题等其余设置仍以发送端为准（局域网设备同步默认用这个）
     */
    public enum ImportMode {
        REPLACE,
        MERGE
    }

    /** 最近一次导入的结果说明，供 UI 展示（REPLACE 时为空串）；后台线程写、主线程读 */
    private static volatile String lastImportSummary = "";

    public static String getLastImportSummary() {
        return lastImportSummary;
    }

    /**
     * 备忘: Hawk 2.0.1 内部默认 SharedPreferences 文件名(常量 STORAGE_TAG_DO_NOT_CHANGE = "Hawk2")。
     * 曾误用 "Hawk" 直接读写 sp → 导出读不到设置、导入写错文件, API_URL/HOME_API 等全部不生效。
     * 现在备份/还原一律走 Hawk API(get/put), 不再直接访问 sp, 故该常量仅作 adb 调试备忘。
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
            HawkConfig.SEARCH_CARD_MODE,
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
            // 复杂对象(非基础类型)设置的类型登记表: key=设置项, value=具体类名(如 java.util.ArrayList),
            // 导入端据此用 Gson 还原成正确类型; 不记录类型的话 Gson 只能还原成 LinkedTreeMap。
            JsonObject complexTypes = null;
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
                // 其余设置项: 一律用 Hawk API 取"真实值"再导出(不搬运 Hawk 的 type#value 序列化串,
                // 那种做法实测同步后设置不生效)。基础类型直接写, 复杂对象 Gson 序列化并登记类型名。
                if (!Hawk.contains(key)) {
                    continue;
                }
                try {
                    Object val = Hawk.get(key);
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
                    } else if (val instanceof Double) {
                        settings.addProperty(key, (Double) val);
                    } else if (val != null) {
                        settings.addProperty(key, new Gson().toJson(val));
                        if (complexTypes == null) {
                            complexTypes = new JsonObject();
                        }
                        complexTypes.addProperty(key, val.getClass().getName());
                    }
                } catch (Exception ignore) {
                }
            }
            root.add("settings", settings);
            if (complexTypes != null) {
                root.add("complexTypes", complexTypes);
            }
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
     * 从 JSON 文件恢复（默认覆盖，供「数据备份还原」使用）
     */
    public static boolean importBackup(File inFile) {
        return importBackup(inFile, ImportMode.REPLACE);
    }

    /**
     * 从 JSON 文件恢复，可指定收藏/历史的导入方式。
     *
     * @param mode REPLACE=清空本机后写入；MERGE=与本机数据取并集
     */
    public static boolean importBackup(File inFile, ImportMode mode) {
        lastImportSummary = "";
        try {
            FileReader reader = new FileReader(inFile);
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();

            int[] collectStat = new int[]{0, 0}; // {新增, 更新}
            int[] recordStat = new int[]{0, 0};
            int[] subStat = new int[]{0, 0}; // {新增订阅数, 去重条数}

            // 1. 恢复收藏
            if (root.has("collects")) {
                JsonArray collectArr = root.getAsJsonArray("collects");
                if (mode == ImportMode.MERGE) {
                    collectStat = mergeCollects(collectArr);
                } else {
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
            }

            // 2. 恢复历史
            if (root.has("records")) {
                JsonArray recordArr = root.getAsJsonArray("records");
                if (mode == ImportMode.MERGE) {
                    recordStat = mergeRecords(recordArr);
                } else {
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
            }

            // 3. 恢复设置
            if (root.has("settings")) {
                JsonObject settings = root.getAsJsonObject("settings");
                // 本地文件内容池(跨设备重建用): key=原本地 url, value=Base64 文件内容
                JsonObject lf = root.has("localFiles") ? root.getAsJsonObject("localFiles") : null;
                // 复杂对象类型登记表(导出端写入)
                JsonObject complexTypes = root.has("complexTypes") ? root.getAsJsonObject("complexTypes") : null;
                for (String key : BACKUP_KEYS) {
                    // 首页站点跟随当前影视源：合并模式下当前源保持本机不变，HOME_API 也必须一起保留，
                    // 否则会被写成发送端配置里的站点 key，而本机配置中可能根本没有这个站点。
                    if (mode == ImportMode.MERGE && key.equals(HawkConfig.HOME_API)) {
                        try {
                            String curHome = Hawk.get(HawkConfig.HOME_API, "");
                            if (curHome != null && !curHome.isEmpty()) {
                                continue;
                            }
                        } catch (Exception ignore) {
                        }
                    }
                    if (key.equals(HawkConfig.API_URL) || key.equals(HawkConfig.LIVE_URL)) {
                        // 本地文件型配置(影视源/直播源): 导出的是真实 url, 需据打包内容在接收端
                        // 重建文件后用 Hawk.put 写回"真实值"(走 editor 会绕过 Hawk 序列化导致读不出来)。
                        // 合并模式: 本机已经在用的影视/直播源不能换成发送端的，否则订阅列表里选中的条目
                        // 与实际加载的配置不一致（首页会莫名换源）。本机没配过才采用发送端的值。
                        if (mode == ImportMode.MERGE) {
                            try {
                                String cur = Hawk.get(key, "");
                                if (cur != null && !cur.trim().isEmpty()) {
                                    continue;
                                }
                            } catch (Exception ignore) {
                            }
                        }
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
                            if (!el.isJsonPrimitive()) {
                                continue;
                            }
                            com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
                            // 复杂对象: 按导出端登记的类型名用 Gson 还原, 再交给 Hawk 存(保证类型标记正确)
                            if (complexTypes != null && complexTypes.has(key)) {
                                try {
                                    Class<?> c = Class.forName(complexTypes.get(key).getAsString());
                                    Object obj = new Gson().fromJson(p.getAsString(), c);
                                    if (obj != null) {
                                        Hawk.put(key, obj);
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                continue;
                            }
                            // 基础类型: 一律用 Hawk.put 写回"真实值"(绕过 Hawk 序列化直接写 sp 会读不出来)
                            if (p.isBoolean()) {
                                Hawk.put(key, p.getAsBoolean());
                            } else if (p.isNumber()) {
                                // 与接收端当前值的类型保持一致, 避免 int/long/float 之间串类型
                                Object cur = Hawk.contains(key) ? Hawk.get(key) : null;
                                if (cur instanceof Long) {
                                    Hawk.put(key, p.getAsLong());
                                } else if (cur instanceof Float) {
                                    Hawk.put(key, (float) p.getAsFloat());
                                } else if (cur instanceof Double) {
                                    Hawk.put(key, p.getAsDouble());
                                } else {
                                    Hawk.put(key, p.getAsInt());
                                }
                            } else {
                                Hawk.put(key, p.getAsString());
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
                            if (mode == ImportMode.MERGE) {
                                // 并集合并: 本机订阅一条不删，只追加发送端独有的条目；
                                // 本地文件订阅在追加时才重建，避免为重复条目写出一堆无用文件。
                                subStat = mergeSubscriptions(subs, lf);
                            } else {
                                // 本地订阅文件: 用打包进备份的内容在私有目录重建文件, 并重写 URL,
                                // 否则同步/换机后订阅指向发送端私有路径(文件不存在)→ 加载报"无法读取本地配置文件"。
                                for (Subscription sub : subs) {
                                    sub.setUrl(restoreLocalFile(sub.getUrl(), lf, "subscriptions"));
                                }
                                // 覆盖导入同样去掉完全重复的条目（发送端列表自己可能带着历史重复）
                                Hawk.put(HawkConfig.SUBSCRIPTIONS, dedupSubscriptions(subs));
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                // 所有设置项都走 Hawk.put, 而 Hawk 内部用的是 editor.commit()(同步落盘),
                // 所以导入后立即 relaunchApp 重启也不会丢设置(不能用异步 apply)。
            }

            if (mode == ImportMode.MERGE) {
                // 两边历史合并后可能超过设置里的条数上限，按 updateTime 保留最新的 N 条，
                // 避免数据库里堆积永远显示不出来的旧记录
                trimHistoryToLimit();
                lastImportSummary = "收藏新增 " + collectStat[0] + " 条、更新 " + collectStat[1] + " 条\n"
                        + "历史新增 " + recordStat[0] + " 条、更新 " + recordStat[1] + " 条\n"
                        + "订阅新增 " + subStat[0] + " 项"
                        + (subStat[1] > 0 ? "、去重 " + subStat[1] + " 项" : "") + "\n"
                        + "本机订阅源与原有记录已全部保留";
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ============================ 合并导入（局域网设备同步） ============================

    /** 收藏/历史的去重键：同一数据源下的同一部影片才算重复 */
    private static String recordKey(String sourceKey, String vodId) {
        return (sourceKey == null ? "" : sourceKey) + "\u0000" + (vodId == null ? "" : vodId);
    }

    private static String jsonStr(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static long jsonLong(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 合并收藏：本机独有的全部保留；两边都有的按 updateTime 取较新的一条。
     * 用 Map 先按 (sourceKey, vodId) 建索引，避免表上没有唯一约束时插出重复海报。
     *
     * @return {新增条数, 更新条数}
     */
    private static int[] mergeCollects(JsonArray arr) {
        VodCollectDao dao = AppDataManager.get().getVodCollectDao();
        Map<String, VodCollect> local = new HashMap<>();
        List<VodCollect> all = dao.getAll();
        if (all != null) {
            for (VodCollect c : all) {
                local.put(recordKey(c.sourceKey, c.vodId), c);
            }
        }
        int added = 0;
        int updated = 0;
        for (int i = 0; i < arr.size(); i++) {
            try {
                JsonObject obj = arr.get(i).getAsJsonObject();
                String sk = jsonStr(obj, "sourceKey");
                String vid = jsonStr(obj, "vodId");
                if (vid.isEmpty()) continue; // 缺影片 id 无法去重，跳过脏数据
                long time = jsonLong(obj, "updateTime");
                String k = recordKey(sk, vid);
                VodCollect exist = local.get(k);
                if (exist == null) {
                    VodCollect c = new VodCollect();
                    c.sourceKey = sk;
                    c.vodId = vid;
                    c.name = jsonStr(obj, "name");
                    c.pic = jsonStr(obj, "pic");
                    c.updateTime = time;
                    long rowId = dao.insert(c);
                    c.setId((int) rowId); // 备份里若出现重复 key，后续 update 才能定位主键
                    local.put(k, c);
                    added++;
                } else if (time > exist.updateTime) {
                    String name = jsonStr(obj, "name");
                    String pic = jsonStr(obj, "pic");
                    // 外来字段为空时别把本机已有的名称/海报洗掉
                    if (!name.isEmpty()) exist.name = name;
                    if (!pic.isEmpty()) exist.pic = pic;
                    exist.updateTime = time;
                    dao.update(exist);
                    updated++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return new int[]{added, updated};
    }

    /**
     * 合并历史记录：同一部影片取 updateTime 较新的一条（播放进度、看到第几集都在 dataJson 里）。
     *
     * @return {新增条数, 更新条数}
     */
    private static int[] mergeRecords(JsonArray arr) {
        VodRecordDao dao = AppDataManager.get().getVodRecordDao();
        Map<String, VodRecord> local = new HashMap<>();
        List<VodRecord> all = dao.getAll(100000);
        if (all != null) {
            for (VodRecord r : all) {
                local.put(recordKey(r.sourceKey, r.vodId), r);
            }
        }
        int added = 0;
        int updated = 0;
        for (int i = 0; i < arr.size(); i++) {
            try {
                JsonObject obj = arr.get(i).getAsJsonObject();
                String sk = jsonStr(obj, "sourceKey");
                String vid = jsonStr(obj, "vodId");
                String data = jsonStr(obj, "dataJson");
                if (vid.isEmpty() || data.isEmpty()) continue; // 空播放信息插进去也是废条目
                long time = jsonLong(obj, "updateTime");
                String k = recordKey(sk, vid);
                VodRecord exist = local.get(k);
                if (exist == null) {
                    VodRecord r = new VodRecord();
                    r.sourceKey = sk;
                    r.vodId = vid;
                    r.updateTime = time;
                    r.dataJson = data;
                    long rowId = dao.insert(r);
                    r.setId((int) rowId);
                    local.put(k, r);
                    added++;
                } else if (time > exist.updateTime) {
                    exist.updateTime = time;
                    exist.dataJson = data;
                    dao.update(exist);
                    updated++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return new int[]{added, updated};
    }

    /** 合并后按设置里的「历史记录条数」上限裁剪，保留最新的 N 条 */
    private static void trimHistoryToLimit() {
        try {
            VodRecordDao dao = AppDataManager.get().getVodRecordDao();
            int hisNum = HistoryHelper.getHisNum(Hawk.get(HawkConfig.HISTORY_NUM, 0));
            if (dao.getCount() > hisNum) {
                dao.reserver(hisNum);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ============================ 合并订阅源（含去重） ============================

    /**
     * 覆盖导入时的列表内部去重：只删完全重复的条目，顺序与其余内容保持不变。
     */
    private static ArrayList<Subscription> dedupSubscriptions(ArrayList<Subscription> subs) {
        ArrayList<Subscription> out = new ArrayList<>();
        if (subs == null) {
            return out;
        }
        Map<String, Integer> index = new HashMap<>();
        for (Subscription s : subs) {
            if (s == null) {
                continue;
            }
            List<String> keys = subscriptionKeys(s.getUrl(), null);
            if (keys.isEmpty()) {
                out.add(s);
                continue;
            }
            Integer hit = firstHit(index, keys);
            if (hit == null) {
                out.add(s);
                registerKeys(index, keys, out.size() - 1);
            } else if (subscriptionRank(s) > subscriptionRank(out.get(hit))) {
                out.set(hit, s);
                registerKeys(index, keys, hit);
            }
        }
        return out;
    }

    /**
     * 并集合并订阅源：本机已有的订阅全部保留，只把发送端独有的追加进来；同时全表去重。
     * 去重覆盖三种情况：本机列表内部已有的重复、两端之间同一条订阅、发送端列表自身带重复。
     * 去重键：在线订阅用归一化 URL（与订阅管理页的 subscriptionKey 同规则）；
     * 本地文件订阅额外用文件内容 MD5 —— 两台设备的 file:///data/data/... 私有路径不同，
     * 只比地址会把同一个 Json 订阅重复加进来。
     * 重复时优先保留内置 / 当前选中的那条；追加进来的条目标记为未选中、不置顶。
     *
     * @return {新增订阅数, 去重条数}
     */
    private static int[] mergeSubscriptions(ArrayList<Subscription> incoming, JsonObject localFiles) {
        int[] stat = new int[]{0, 0};
        ArrayList<Subscription> stored;
        try {
            ArrayList<Subscription> s = Hawk.get(HawkConfig.SUBSCRIPTIONS, new ArrayList<Subscription>());
            stored = s == null ? new ArrayList<Subscription>() : new ArrayList<>(s);
        } catch (Exception e) {
            stored = new ArrayList<>();
        }

        ArrayList<Subscription> merged = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (Subscription s : stored) {
            if (s == null) {
                continue;
            }
            List<String> keys = subscriptionKeys(s.getUrl(), null);
            if (keys.isEmpty()) {
                merged.add(s); // 空地址不擅自删用户数据
                continue;
            }
            Integer hit = firstHit(index, keys);
            if (hit == null) {
                merged.add(s);
                registerKeys(index, keys, merged.size() - 1);
            } else {
                stat[1]++;
                if (subscriptionRank(s) > subscriptionRank(merged.get(hit))) {
                    merged.set(hit, s);
                    registerKeys(index, keys, hit);
                }
            }
        }

        if (incoming != null) {
            for (Subscription sub : incoming) {
                if (sub == null) {
                    continue;
                }
                String origUrl = sub.getUrl();
                if (origUrl == null || origUrl.trim().isEmpty()) {
                    continue;
                }
                // 先用发送端备份里打包的内容判重；命中就不重建文件，避免写出无用副本
                List<String> preKeys = subscriptionKeys(origUrl, localFiles);
                if (preKeys.isEmpty() || firstHit(index, preKeys) != null) {
                    if (!preKeys.isEmpty()) {
                        stat[1]++;
                    }
                    continue;
                }
                String newUrl = restoreLocalFile(origUrl, localFiles, "subscriptions");
                // 重写到本机路径后再查一次：之前同步过同一发送端时，两端路径已经一致了
                List<String> postKeys = subscriptionKeys(newUrl, null);
                if (firstHit(index, postKeys) != null) {
                    stat[1]++;
                    continue;
                }
                sub.setUrl(newUrl);
                sub.setChecked(false);  // 本机当前选中的订阅不能被顶掉
                sub.setTop(false);
                sub.setBuiltIn(false);
                merged.add(sub);
                int idx = merged.size() - 1;
                registerKeys(index, preKeys, idx);
                registerKeys(index, postKeys, idx);
                stat[0]++;
            }
        }

        if (stat[0] > 0 || stat[1] > 0) {
            Hawk.put(HawkConfig.SUBSCRIPTIONS, merged);
        }
        return stat;
    }

    /** 一条订阅的所有候选去重键：内容键(仅本地文件) + 归一化 URL 键 */
    private static List<String> subscriptionKeys(String url, JsonObject localFiles) {
        List<String> keys = new ArrayList<>();
        if (url == null || url.trim().isEmpty()) {
            return keys;
        }
        if (isLocalUrl(url)) {
            byte[] data = null;
            if (localFiles != null && localFiles.has(url)) {
                try {
                    data = Base64.decode(localFiles.get(url).getAsString(), Base64.NO_WRAP);
                } catch (Exception ignore) {
                }
            }
            if (data == null) {
                data = readLocalBytes(url);
            }
            if (data != null && data.length > 0) {
                keys.add("C#" + bytesMd5(data));
            }
        }
        String uk = subscriptionKey(url);
        if (!uk.isEmpty()) {
            keys.add("U#" + uk);
        }
        return keys;
    }

    private static Integer firstHit(Map<String, Integer> index, List<String> keys) {
        for (String k : keys) {
            Integer hit = index.get(k);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static void registerKeys(Map<String, Integer> index, List<String> keys, int idx) {
        for (String k : keys) {
            index.put(k, idx);
        }
    }

    /** 重复时谁更该留下：当前选中的 > 内置的 > 普通条目 */
    private static int subscriptionRank(Subscription s) {
        return (s.isChecked() ? 2 : 0) + (s.isBuiltIn() ? 1 : 0);
    }

    /**
     * 归一化订阅地址，规则与 SubscriptionActivity.subscriptionKey 保持一致：
     * http(s) 的 scheme/host 统一小写、去掉末尾斜杠、保留端口与查询参数；本地文件 / clan:// 原样比较。
     */
    private static String subscriptionKey(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        if (value.isEmpty()) {
            return value;
        }
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return value;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return value;
            }
            String path = uri.getEncodedPath();
            if (path == null) {
                path = "";
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            int port = uri.getPort();
            String query = uri.getEncodedQuery();
            return scheme + "://" + host.toLowerCase(Locale.ROOT)
                    + (port == -1 ? "" : ":" + port) + path
                    + (query == null ? "" : "?" + query);
        } catch (Exception e) {
            return value;
        }
    }

    private static String bytesMd5(byte[] data) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Arrays.hashCode(data));
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
