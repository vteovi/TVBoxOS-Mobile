package com.github.tvbox.osc.util;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 宿主的本地转发代理（兜底实现）。
 *
 * <p>spider jar 里的 {@code com.github.catvod.spider.Proxy} 会处理
 * {@code /proxy?do=m3u8&url=...}，但实际跑起来经常拿不到它：多 jar 订阅下
 * {@code recentJarKey} 会被搜索/详情阶段的其他源改掉，或者这个 jar 干脆没带
 * Proxy 类，结果 /proxy 返回空、播放器收到 500/403，Exo 统一报 2004。</p>
 *
 * <p>所以这里做一层纯转发：直接把 url 抓回来，m3u8 顺手把分片地址重写成
 * 本地代理地址（否则播放器会按 127.0.0.1 去解析相对路径）。只要目标站能直连，
 * 这条链路就能播，不依赖 jar 里的任何代理实现。</p>
 */
public class LocalProxy {

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** 只转发这些请求头，避免把 Host / Accept-Encoding / Range 之类污染到目标站 */
    private static final String[] FORWARD = {
            "user-agent", "referer", "cookie", "origin", "authorization", "accept", "accept-language"
    };

    private static volatile OkHttpClient client;

    private static OkHttpClient client() {
        if (client == null) {
            synchronized (LocalProxy.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return client;
    }

    public static Object[] proxy(Map<String, String> params) {
        if (params == null) return null;
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return null;
        try {
            Map<String, String> headers = collectHeaders(params);
            Request.Builder builder = new Request.Builder().url(url).get();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
            Response response = client().newCall(builder.build()).execute();
            String mime = response.header("Content-Type", "");
            if (mime != null && mime.toLowerCase(Locale.ROOT).contains("text/html")) {
                // 不是媒体地址（有些 spider 会让代理去拉一个网页），交回 spider 处理
                response.close();
                return null;
            }
            if (response.code() >= 400) {
                // 目标站拒绝(需要 spider 自己的鉴权头/签名)时交回 spider, 别盖掉它本来能成功的请求
                response.close();
                return null;
            }
            ResponseBody body = response.body();
            byte[] bytes = body == null ? new byte[0] : body.bytes();
            if (isPlaylist(url, mime, bytes)) {
                bytes = rewritePlaylist(bytes, url);
                if (TextUtils.isEmpty(mime)) mime = "application/vnd.apple.mpegurl";
            }
            if (TextUtils.isEmpty(mime)) mime = "application/octet-stream";
            return new Object[]{response.code(), mime, new ByteArrayInputStream(bytes)};
        } catch (Throwable th) {
            LOG.e("LocalProxy", th);
        }
        return null;
    }

    /** 结果是否可以直接交给播放器（2xx / 3xx） */
    public static boolean isOk(Object[] rs) {
        if (rs == null || rs.length < 3) return false;
        try {
            Object code = rs[0];
            int c;
            if (code instanceof Number) c = ((Number) code).intValue();
            else c = Integer.parseInt(String.valueOf(code).trim());
            return c >= 200 && c < 400;
        } catch (Throwable th) {
            return false;
        }
    }

    private static Map<String, String> collectHeaders(Map<String, String> params) {
        Map<String, String> out = new HashMap<>();
        String raw = params.get("request-headers");
        if (!TextUtils.isEmpty(raw)) {
            try {
                JSONObject json = new JSONObject(raw);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (allowed(key)) out.put(canonical(key), json.optString(key, ""));
                }
            } catch (Throwable ignored) {
            }
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            String key = e.getKey();
            if (key == null || !allowed(key)) continue;
            String name = canonical(key);
            if (!out.containsKey(name)) out.put(name, e.getValue());
        }
        return out;
    }

    private static boolean allowed(String key) {
        if (TextUtils.isEmpty(key)) return false;
        String k = key.toLowerCase(Locale.ROOT);
        for (String permit : FORWARD) {
            if (permit.equals(k)) return true;
        }
        return false;
    }

    private static String canonical(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        switch (k) {
            case "user-agent":
                return "User-Agent";
            case "referer":
                return "Referer";
            case "cookie":
                return "Cookie";
            case "origin":
                return "Origin";
            case "authorization":
                return "Authorization";
            case "accept":
                return "Accept";
            case "accept-language":
                return "Accept-Language";
            default:
                return key;
        }
    }

    private static boolean isPlaylist(String url, String mime, byte[] bytes) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("mpegurl")) return true;
        String path = url.toLowerCase(Locale.ROOT);
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true;
        if (bytes != null && bytes.length >= 7) {
            String head = new String(bytes, 0, 7, java.nio.charset.StandardCharsets.UTF_8);
            return "#EXTM3U".equalsIgnoreCase(head);
        }
        return false;
    }

    private static byte[] rewritePlaylist(byte[] bytes, String base) {
        try {
            String text = new String(bytes, "UTF-8");
            StringBuilder sb = new StringBuilder(text.length() + 512);
            String[] lines = text.split("\n", -1);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    sb.append("\r\n");
                } else if (line.startsWith("#")) {
                    sb.append(rewriteTag(line, base)).append("\r\n");
                } else {
                    sb.append(proxyUrl(resolve(base, line))).append("\r\n");
                }
            }
            return sb.toString().getBytes("UTF-8");
        } catch (Throwable th) {
            LOG.e("LocalProxy", th);
        }
        return bytes;
    }

    /** 处理 #EXT-X-KEY / #EXT-X-MAP / #EXT-X-MEDIA 里的 URI="..." */
    private static String rewriteTag(String line, String base) {
        if (line.indexOf("URI=") < 0) return line;
        try {
            Matcher m = URI_ATTR.matcher(line);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String abs = resolve(base, m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + proxyUrl(abs) + "\""));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable th) {
            return line;
        }
    }

    private static String resolve(String base, String line) {
        try {
            return new URL(new URL(base), line).toString();
        } catch (Throwable th) {
            return line;
        }
    }

    private static String proxyUrl(String abs) {
        try {
            return com.github.catvod.Proxy.getUrl(true) + "?do=m3u8&url=" + URLEncoder.encode(abs, "UTF-8");
        } catch (Throwable th) {
            return abs;
        }
    }
}
