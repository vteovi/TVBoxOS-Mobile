package com.github.catvod;

/**
 * 本地代理端口持有者。
 *
 * <p>新版 spider jar（以及 fongmi 系 catvod）会把 m3u8 分片请求绕回宿主：它们调用
 * {@code com.github.catvod.Proxy.getUrl(true)} 拼出
 * {@code http://127.0.0.1:<port>/proxy?do=m3u8&url=...}，再由宿主的 /proxy 转发。</p>
 *
 * <p>问题在于这个类在 jar 里的默认端口是 <b>-1</b>，宿主如果不主动 {@link #set(int)}，
 * 拼出来的地址就是 {@code http://127.0.0.1:-1/proxy?...}。播放器去连一个不存在的端口，
 * Exo 会报 {@code HTTP 状态码异常(2004)}，IJK 会报 {@code -1004/IO 错误}，表现就是
 * "能搜到剧、点了播不了，换哪个内核都一样"。</p>
 *
 * <p>所以宿主在本地服务启动成功后必须把真实端口写进来（见 ControlManager#startServer）。</p>
 */
public class Proxy {

    private static int port = -1;

    public static void set(int port) {
        Proxy.port = port;
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl(boolean local) {
        String host = "127.0.0.1";
        if (!local) {
            try {
                host = com.github.tvbox.osc.util.LocalIPAddress.getIP(com.github.tvbox.osc.base.App.getInstance());
            } catch (Throwable ignored) {
            }
        }
        return "http://" + host + ":" + getPort() + "/proxy";
    }
}
