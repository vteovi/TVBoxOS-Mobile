package com.github.catvod.crawler;

import android.content.Context;

import com.github.tvbox.osc.util.OkGoHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;

public abstract class Spider {

    public void init(Context context) throws Exception {}

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap < String, String > extend) throws Exception {
        return "";
    }

    public String detailContent(List < String > ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List < String > vipFlags) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxyLocal(Map < String, String > params) throws Exception {
        return null;
    }

    /**
     * 新版 catvod 的 jar 爬虫统一用 proxy(Map) 这个命名。宿主调用链是 jar 里的
     * com.github.catvod.spider.Proxy 静态入口 -> 具体 spider 实例的 proxy(...)，
     * 基类缺这个方法时运行期会抛 NoSuchMethodError，表现为走代理的源点播放直接黑屏。
     */
    public Object[] proxy(Map < String, String > params) throws Exception {
        return proxyLocal(params);
    }

    public String action(String action) throws Exception {
        return "{}";
    }

    public void cancelByTag() {

    }

    public void destroy() {}

    public static Dns safeDns() {
        return OkGoHelper.dnsOverHttps;
    }
}
