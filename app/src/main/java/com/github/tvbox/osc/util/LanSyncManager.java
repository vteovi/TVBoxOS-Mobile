package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.base.App;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网设备同步: 同一 WiFi 下, 一台设备"发送配置", 另一台"接收配置"。
 *
 * 传输内容与"数据备份还原"完全一致(收藏 / 历史 / 设置), 复用 BackupManager 导出的 JSON。
 *
 * 发现: UDP 广播(端口 45127), 发送端每 1.5 秒广播一次 "TVBOX_LAN_SYNC|设备名|http端口",
 *      接收端监听几秒收集在线设备; 也支持手动填 IP 兜底(有些路由器会拦截广播)。
 * 传输: 发送端起一个极简 HTTP 服务(仅 GET /config 与 /ping), 接收端用 HttpURLConnection 下载。
 *
 * 依赖的权限(清单里已有): INTERNET / ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE
 */
public class LanSyncManager {

    public static final int UDP_PORT = 45127;
    private static final String MAGIC = "TVBOX_LAN_SYNC";
    private static final int[] HTTP_PORT_CANDIDATES = {8099, 8100, 8101, 8102, 8103};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ---------- 发送端 ----------
    private ServerSocket serverSocket;
    private volatile boolean serverRunning = false;
    private volatile boolean beaconRunning = false;
    private byte[] payload = null;      // 导出的配置内容
    private int serverPort = 0;
    private ExecutorService httpPool;
    private Thread beaconThread;
    private WifiManager.MulticastLock multicastLock;

    // ---------- 接收端 ----------
    private DatagramSocket discoverSocket;
    private volatile boolean discovering = false;

    /** 发现到的设备 */
    public static class Device {
        public String name;
        public String ip;
        public int port;

        public Device(String name, String ip, int port) {
            this.name = name;
            this.ip = ip;
            this.port = port;
        }

        @Override
        public String toString() {
            return name + "  (" + ip + ")";
        }
    }

    public interface ServerListener {
        /** 服务已启动, addr 形如 192.168.1.5:8099 */
        void onStarted(String addr);

        /** 有设备下载完成 */
        void onSent(String remoteIp);

        void onError(String msg);
    }

    public interface DiscoverListener {
        void onResult(List<Device> devices);
    }

    // ============================ 发送端 ============================

    /**
     * 启动"发送配置"服务: 导出本机配置并起 HTTP 服务 + 广播自己的存在。
     */
    public void startServer(final Context context, final ServerListener listener) {
        if (serverRunning) {
            return;
        }
        new Thread(() -> {
            File tmp = null;
            try {
                // 每次启动都重新导出, 保证发出去的是最新的配置
                tmp = new File(context.getCacheDir(), "tvbox_lan_sync.json");
                if (!BackupManager.exportBackup(tmp)) {
                    post(() -> listener.onError("导出配置失败"));
                    return;
                }
                byte[] data = readAll(tmp);
                if (data == null || data.length == 0) {
                    post(() -> listener.onError("配置内容为空"));
                    return;
                }
                payload = data;

                ServerSocket ss = null;
                int port = 0;
                for (int candidate : HTTP_PORT_CANDIDATES) {
                    try {
                        ss = new ServerSocket();
                        ss.setReuseAddress(true);
                        ss.bind(new InetSocketAddress(candidate));
                        port = candidate;
                        break;
                    } catch (IOException ignore) {
                        if (ss != null) {
                            try {
                                ss.close();
                            } catch (IOException ignored2) {
                            }
                        }
                        ss = null;
                    }
                }
                if (ss == null) {
                    post(() -> listener.onError("端口被占用, 无法启动服务"));
                    return;
                }
                serverSocket = ss;
                serverPort = port;
                serverRunning = true;
                httpPool = Executors.newCachedThreadPool();

                String addr = getLocalIp() + ":" + port;
                post(() -> listener.onStarted(addr));

                startBeacon(context);
                acceptLoop(listener);
            } catch (Exception e) {
                e.printStackTrace();
                post(() -> listener.onError("启动失败: " + e.getMessage()));
            } finally {
                if (tmp != null) {
                    tmp.delete();
                }
            }
        }).start();
    }

    private void acceptLoop(ServerListener listener) {
        while (serverRunning && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                httpPool.execute(() -> handleHttp(socket, listener));
            } catch (IOException e) {
                if (serverRunning) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 极简 HTTP: 只认 GET /config(返回配置 JSON) 与 GET /ping(探活), 其它一律 404。
     */
    private void handleHttp(Socket socket, ServerListener listener) {
        String remote = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "";
        try {
            socket.setSoTimeout(8000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line = reader.readLine();
            // 读到空行, 把请求头读完, 否则有些客户端会认为连接异常
            while (true) {
                String h = reader.readLine();
                if (h == null || h.length() == 0) {
                    break;
                }
            }
            OutputStream os = socket.getOutputStream();
            DataOutputStream out = new DataOutputStream(os);
            if (line != null && line.contains("/ping")) {
                byte[] body = "ok".getBytes("UTF-8");
                out.writeBytes("HTTP/1.1 200 OK\r\n");
                out.writeBytes("Content-Type: text/plain; charset=utf-8\r\n");
                out.writeBytes("Content-Length: " + body.length + "\r\n");
                out.writeBytes("Connection: close\r\n\r\n");
                out.write(body);
                out.flush();
            } else if (line != null && line.contains("/config") && payload != null) {
                out.writeBytes("HTTP/1.1 200 OK\r\n");
                out.writeBytes("Content-Type: application/json; charset=utf-8\r\n");
                out.writeBytes("Content-Length: " + payload.length + "\r\n");
                out.writeBytes("Connection: close\r\n\r\n");
                out.write(payload);
                out.flush();
                final String ip = remote;
                post(() -> listener.onSent(ip));
            } else {
                out.writeBytes("HTTP/1.1 404 Not Found\r\n");
                out.writeBytes("Content-Length: 0\r\n");
                out.writeBytes("Connection: close\r\n\r\n");
                out.flush();
            }
            out.close();
            os.close();
        } catch (Exception e) {
            // 探活失败等不影响服务
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 广播自己的存在, 让接收端能自动发现 */
    private void startBeacon(Context context) {
        beaconRunning = true;
        final String msg = MAGIC + "|" + getDeviceName() + "|" + serverPort;
        beaconThread = new Thread(() -> {
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket();
                ds.setBroadcast(true);
                byte[] buf = msg.getBytes("UTF-8");
                String localIp = getLocalIp();
                ArrayList<String> targets = new ArrayList<>();
                targets.add("255.255.255.255");
                if (localIp != null && localIp.lastIndexOf('.') > 0) {
                    targets.add(localIp.substring(0, localIp.lastIndexOf('.') + 1) + "255");
                }
                while (beaconRunning) {
                    for (String target : targets) {
                        try {
                            DatagramPacket packet = new DatagramPacket(buf, buf.length,
                                    InetAddress.getByName(target), UDP_PORT);
                            ds.send(packet);
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (ds != null) {
                    ds.close();
                }
            }
        });
        beaconThread.start();
    }

    public void stopServer() {
        serverRunning = false;
        beaconRunning = false;
        if (beaconThread != null) {
            beaconThread.interrupt();
            beaconThread = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (httpPool != null) {
            httpPool.shutdownNow();
            httpPool = null;
        }
        payload = null;
        serverPort = 0;
    }

    public boolean isServerRunning() {
        return serverRunning;
    }

    // ============================ 接收端 ============================

    /**
     * 扫描局域网内正在"发送配置"的设备。回调在主线程。
     */
    public void discover(Context context, final int timeoutMs, final DiscoverListener listener) {
        if (discovering) {
            return;
        }
        discovering = true;
        acquireMulticastLock(context);
        new Thread(() -> {
            final List<Device> devices = new ArrayList<>();
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.setSoTimeout(500);
                ds.bind(new InetSocketAddress(UDP_PORT));
                long end = System.currentTimeMillis() + timeoutMs;
                byte[] buf = new byte[1024];
                String selfIp = getLocalIp();
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        ds.receive(packet);
                        String text = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                        Device d = parseBeacon(text);
                        if (d == null) {
                            continue;
                        }
                        // 跳过本机
                        if (selfIp != null && selfIp.equals(d.ip)) {
                            continue;
                        }
                        boolean exists = false;
                        for (Device cur : devices) {
                            if (cur.ip.equals(d.ip) && cur.port == d.port) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            devices.add(d);
                        }
                    } catch (IOException timeout) {
                        // 单次超时, 继续等
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (ds != null) {
                    ds.close();
                }
                discovering = false;
                releaseMulticastLock();
                post(() -> listener.onResult(devices));
            }
        }).start();
    }

    private Device parseBeacon(String text) {
        if (text == null || !text.startsWith(MAGIC + "|")) {
            return null;
        }
        String[] parts = text.split("\\|");
        if (parts.length < 4) {
            return null;
        }
        try {
            return new Device(parts[1], parts[2], Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从指定设备下载配置到 outFile(需在工作线程调用)。
     */
    public static boolean downloadConfig(String ip, int port, File outFile) throws IOException {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + "/config");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("设备返回 " + code);
            }
            is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            byte[] data = bos.toByteArray();
            if (data.length == 0) {
                throw new IOException("下载内容为空");
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            fos.write(data);
            fos.flush();
            fos.close();
            return true;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ============================ 工具 ============================

    private void acquireMulticastLock(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("tvbox-lan-sync");
                multicastLock.setReferenceCounted(false);
            }
            multicastLock.acquire();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void post(Runnable r) {
        if (r != null) {
            mainHandler.post(r);
        }
    }

    /** 本机局域网 IPv4, 取不到返回 127.0.0.1 */
    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) {
                return "127.0.0.1";
            }
            for (NetworkInterface nif : Collections.list(en)) {
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress()
                            && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    public static String getDeviceName() {
        String model = Build.MODEL;
        String ip = getLocalIp();
        String tail = ip.lastIndexOf('.') > 0 ? ip.substring(ip.lastIndexOf('.') + 1) : "";
        return String.format(Locale.getDefault(), "%s-%s", model, tail);
    }

    private static byte[] readAll(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            fis.close();
            return bos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** 取出 App 上下文(方便调用方) */
    public static Context appContext() {
        return App.getInstance();
    }
}
