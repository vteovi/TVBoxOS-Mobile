package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.CastDevice;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 轻量级 DLNA / UPnP 投屏管理器(零第三方依赖)
 * 通过 SSDP 发现局域网内支持 AVTransport 的媒体渲染设备,
 * 并使用标准 SOAP 调用 AVTransport 服务完成 SetAVTransportURI / Play / Stop / Seek 控制。
 */
public class CastManager {
    private static final String TAG = "CastManager";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String ST_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final int DISCOVER_TIMEOUT = 4000;

    private static final CastManager sInstance = new CastManager();
    private WifiManager.MulticastLock mMulticastLock;
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();

    public static CastManager getInstance() {
        return sInstance;
    }

    public interface DiscoveryListener {
        /** 发现过程中设备列表发生变化(增量回调, 运行于主线程) */
        void onDevices(List<CastDevice> devices);

        /** 搜索结束(运行于主线程) */
        void onDiscoveryFinished(List<CastDevice> devices);
    }

    public interface CastListener {
        void onResult(boolean success, String deviceName);
    }

    // ===================== 设备发现 =====================

    public void discover(final DiscoveryListener listener) {
        mExecutor.execute(() -> {
            List<CastDevice> devices = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            DatagramSocket socket = null;
            try {
                acquireMulticast();
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(0));
                socket.setSoTimeout(DISCOVER_TIMEOUT);

                String search = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 3\r\n"
                        + "ST: " + ST_AVTRANSPORT + "\r\n\r\n";
                byte[] sendData = search.getBytes(StandardCharsets.UTF_8);
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length,
                        InetAddress.getByName(SSDP_ADDR), SSDP_PORT);
                // 发送两次, 提高被发现概率
                socket.send(sendPacket);
                socket.send(sendPacket);

                byte[] buf = new byte[2048];
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < DISCOVER_TIMEOUT) {
                    try {
                        DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
                        socket.receive(receivePacket);
                        String response = new String(receivePacket.getData(), 0,
                                receivePacket.getLength(), StandardCharsets.UTF_8);
                        String location = parseHeader(response, "LOCATION");
                        if (!TextUtils.isEmpty(location) && seen.add(location)) {
                            CastDevice device = parseDevice(location);
                            if (device != null && !devices.contains(device)) {
                                devices.add(device);
                                postDevices(listener, new ArrayList<>(devices), false);
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        break;
                    } catch (Throwable e) {
                        Log.e(TAG, "receive ssdp error", e);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "discover error", e);
            } finally {
                if (socket != null) socket.close();
                releaseMulticast();
            }
            postDevices(listener, new ArrayList<>(devices), true);
        });
    }

    private void acquireMulticast() {
        try {
            if (mMulticastLock == null || !mMulticastLock.isHeld()) {
                WifiManager wm = (WifiManager) App.getInstance().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    mMulticastLock = wm.createMulticastLock("CastMulticastLock");
                    mMulticastLock.setReferenceCounted(false);
                    mMulticastLock.acquire();
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "acquireMulticast error", e);
        }
    }

    private void releaseMulticast() {
        try {
            if (mMulticastLock != null && mMulticastLock.isHeld()) {
                mMulticastLock.release();
            }
        } catch (Throwable ignore) {
        }
    }

    private static String parseHeader(String response, String name) {
        if (TextUtils.isEmpty(response)) return null;
        String prefix = name.toUpperCase(Locale.US) + ":";
        String[] lines = response.split("\r\n");
        for (String line : lines) {
            if (line.toUpperCase(Locale.US).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private CastDevice parseDevice(String location) {
        try {
            String xml = httpGet(location);
            if (TextUtils.isEmpty(xml)) return null;

            URI uri = new URI(location);
            String baseUrl = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() != -1 ? ":" + uri.getPort() : "");

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (Throwable ignore) {
            }
            DocumentBuilder db = factory.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();

            CastDevice device = new CastDevice();
            device.descriptionUrl = location;
            device.baseUrl = baseUrl;
            device.name = firstText(doc, "friendlyName");
            if (TextUtils.isEmpty(device.name)) device.name = firstText(doc, "modelName");
            device.modelName = firstText(doc, "modelName");
            device.uuid = firstText(doc, "UDN");

            NodeList services = doc.getElementsByTagName("service");
            for (int i = 0; i < services.getLength(); i++) {
                Element svc = (Element) services.item(i);
                String type = firstText(svc, "serviceType");
                if (!TextUtils.isEmpty(type) && type.contains("AVTransport")) {
                    device.controlUrl = absoluteUrl(baseUrl, firstText(svc, "controlURL"));
                    device.eventUrl = absoluteUrl(baseUrl, firstText(svc, "eventSubURL"));
                    break;
                }
            }
            if (TextUtils.isEmpty(device.controlUrl)) return null;
            if (TextUtils.isEmpty(device.name)) device.name = baseUrl;
            return device;
        } catch (Throwable e) {
            Log.e(TAG, "parseDevice error: " + location, e);
            return null;
        }
    }

    // ===================== 投屏控制 =====================

    public void cast(CastDevice device, String url, String title, long startPositionMs, final CastListener listener) {
        if (device == null || TextUtils.isEmpty(device.controlUrl) || TextUtils.isEmpty(url)) {
            postCastResult(listener, false, device == null ? "" : device.name);
            return;
        }
        mExecutor.execute(() -> {
            boolean ok = false;
            try {
                String metadata = buildMetadata(url, title);
                String setUriBody = "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID>"
                        + "<CurrentURI>" + escapeXml(url) + "</CurrentURI>"
                        + "<CurrentURIMetadata>" + escapeXml(metadata) + "</CurrentURIMetadata>"
                        + "</u:SetAVTransportURI>";
                int r1 = sendSoap(device.controlUrl, "SetAVTransportURI", setUriBody);

                String playBody = "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>";
                int r2 = sendSoap(device.controlUrl, "Play", playBody);

                ok = isSuccess(r1) && isSuccess(r2);
                if (ok && startPositionMs > 0) {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ignore) {
                    }
                    seek(device, startPositionMs);
                }
            } catch (Throwable e) {
                Log.e(TAG, "cast error", e);
            }
            postCastResult(listener, ok, device.name);
        });
    }

    public void stop(CastDevice device, final CastListener listener) {
        if (device == null || TextUtils.isEmpty(device.controlUrl)) {
            postCastResult(listener, false, device == null ? "" : device.name);
            return;
        }
        mExecutor.execute(() -> {
            String body = "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                    + "<InstanceID>0</InstanceID></u:Stop>";
                int r = sendSoap(device.controlUrl, "Stop", body);
            postCastResult(listener, isSuccess(r), device.name);
        });
    }

    public void seek(CastDevice device, long positionMs) {
        if (device == null || TextUtils.isEmpty(device.controlUrl)) return;
        mExecutor.execute(() -> {
            try {
                String target = formatTime(positionMs / 1000);
                String body = "<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                        + "<InstanceID>0</InstanceID>"
                        + "<Unit>REL_TIME</Unit>"
                        + "<Target>" + target + "</Target>"
                        + "</u:Seek>";
                sendSoap(device.controlUrl, "Seek", body);
            } catch (Throwable e) {
                Log.e(TAG, "seek error", e);
            }
        });
    }

    // ===================== 底层 HTTP / SOAP =====================

    private int sendSoap(String controlUrl, String action, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(controlUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION",
                    "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"");
            String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body>" + body + "</s:Body></s:Envelope>";
            byte[] data = envelope.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            return conn.getResponseCode();
        } catch (IOException e) {
            Log.e(TAG, "sendSoap " + action + " error", e);
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString();
        } catch (Throwable e) {
            Log.e(TAG, "httpGet error: " + urlStr, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ===================== 工具方法 =====================

    private static String firstText(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        if (list.getLength() > 0 && list.item(0).getTextContent() != null) {
            return list.item(0).getTextContent().trim();
        }
        return "";
    }

    private static String firstText(Element el, String tag) {
        NodeList list = el.getElementsByTagName(tag);
        if (list.getLength() > 0 && list.item(0).getTextContent() != null) {
            return list.item(0).getTextContent().trim();
        }
        return "";
    }

    private static String absoluteUrl(String baseUrl, String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("/")) return baseUrl + path;
        return baseUrl + "/" + path;
    }

    private static String buildMetadata(String url, String title) {
        if (TextUtils.isEmpty(title)) title = "TVBox";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"0\">"
                + "<dc:title>" + escapeXml(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:video/mp4:*\">" + escapeXml(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String formatTime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    private void postDevices(final DiscoveryListener listener, final List<CastDevice> devices, final boolean finished) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (finished) listener.onDiscoveryFinished(devices);
            else listener.onDevices(devices);
        });
    }

    private void postCastResult(final CastListener listener, final boolean ok, final String name) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> listener.onResult(ok, name));
    }
}
