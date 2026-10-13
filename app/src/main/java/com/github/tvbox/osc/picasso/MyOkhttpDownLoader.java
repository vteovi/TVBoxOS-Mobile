/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * governing permissions and limitations under the License.
 */
package com.github.tvbox.osc.picasso;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.squareup.picasso.Downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.security.MessageDigest;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A {@link Downloader} which uses OkHttp to download images.
 * 带本地文件缓存：按 URL MD5 落盘，命中直接读文件，不重复走网络。
 */
public final class MyOkhttpDownLoader implements Downloader {
    @VisibleForTesting
    final Call.Factory client;
    private final Cache cache;
    private final File cacheDir;
    private boolean sharedClient = true;

    public MyOkhttpDownLoader(OkHttpClient client) {
        this.client = client;
        this.cache = client.cache();
        this.cacheDir = null;
    }

    public MyOkhttpDownLoader(OkHttpClient client, Context context) {
        this.client = client;
        this.cache = client.cache();
        File dir = new File(context.getCacheDir(), "picasso");
        if (!dir.exists()) dir.mkdirs();
        this.cacheDir = dir;
    }

    public MyOkhttpDownLoader(Call.Factory client) {
        this.client = client;
        this.cache = null;
        this.cacheDir = null;
    }

    @NonNull
    @Override
    public Response load(@NonNull Request request) throws IOException {
        String url = request.url().toString();
        String header = null;
        String cookie = null;
        String ua = null;
        String referer = null;

        if (url.contains("@Headers=")){
            header = url.split("@Headers=")[1].split("@")[0];
            header = URLDecoder.decode(header, "UTF-8");
        }
        if (url.contains("@Cookie=")) cookie = url.split("@Cookie=")[1].split("@")[0];
        if (url.contains("@User-Agent=")) ua = url.split("@User-Agent=")[1].split("@")[0];
        if (url.contains("@Referer=")) referer = url.split("@Referer=")[1].split("@")[0];

        url = url.split("@")[0];

        // 本地文件缓存命中：直接返回文件字节
        if (cacheDir != null) {
            File file = new File(cacheDir, md5(url));
            if (file.exists() && file.length() > 0) {
                byte[] bytes = readFile(file);
                if (bytes != null && bytes.length > 0) {
                    ResponseBody body = ResponseBody.create(MediaType.parse("image/*"), bytes);
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK (file cache)")
                            .body(body)
                            .build();
                }
            }
        }

        Request.Builder mRequestBuilder = request.newBuilder().url(url);
        if(!TextUtils.isEmpty(header)) {
            JsonObject jsonInfo = new Gson().fromJson(header, JsonObject.class);
            for (String key : jsonInfo.keySet()) {
                String val = jsonInfo.get(key).getAsString();
                mRequestBuilder.addHeader(key.toUpperCase(), removeDuplicateSlashes(val));
            }
        } else {
            if(!TextUtils.isEmpty(cookie)) {
                mRequestBuilder.addHeader("Cookie", cookie);
            }
            if(!TextUtils.isEmpty(ua)){
                mRequestBuilder.addHeader("User-Agent", ua);
            } else {
                String mobile_UA = "Dalvik/2.1.0 (Linux; U; Android 13; M2102J2SC Build/TKQ1.220829.002)";
                mRequestBuilder.addHeader("User-Agent", mobile_UA);
            }
            if(!TextUtils.isEmpty(referer)){
                mRequestBuilder.addHeader("Referer", referer);
            }
        }

        Response response = client.newCall(mRequestBuilder.build()).execute();

        // 网络响应成功，写入本地文件缓存
        if (cacheDir != null && response.isSuccessful() && response.body() != null) {
            try {
                byte[] bytes = response.body().bytes();
                if (bytes.length > 0) {
                    File file = new File(cacheDir, md5(url));
                    File tmp = new File(cacheDir, md5(url) + ".tmp");
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        fos.write(bytes);
                    }
                    tmp.renameTo(file);
                    ResponseBody newBody = ResponseBody.create(
                            response.body().contentType(), bytes);
                    return response.newBuilder().body(newBody).build();
                }
            } catch (Exception ignore) {
            }
        }
        return response;
    }

    private static byte[] readFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            if (read == buf.length) return buf;
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static String removeDuplicateSlashes(String paramValue) {
        return paramValue.replaceAll("//", "/");
    }

    @Override
    public void shutdown() {
        if (!sharedClient && cache != null) {
            try {
                cache.close();
            } catch (IOException ignored) {
            }
        }
    }
}
