package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.DefaultConfig;
import com.google.android.material.button.MaterialButton;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BottomPopupView;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AboutDialog extends BottomPopupView {

    /** 项目地址（GitHub 账号已由 kukuqi666 更名为 vteovi，旧地址只是跳转） */
    private static final String PROJECT_URL = "https://github.com/vteovi/TVBoxOS-Mobile";
    /** 更新清单：与 PROJECT_URL 同一仓库的 main 分支 */
    private static final String RAW_UPDATE_JSON =
            "https://raw.githubusercontent.com/vteovi/TVBoxOS-Mobile/main/update.json";

    /** GitHub 访问加速，按顺序回退；最后再直连一次 */
    private static final String ACCEL_PRIMARY = "https://gh.xxooo.cf/";
    private static final String ACCEL_FALLBACK = "https://gh-proxy.com/";

    private static final String TAG_CHECK = "about_check_update";
    private static final String TAG_DOWNLOAD = "about_download_update";

    /** 小于 1MB 的"安装包"通常是错误页/占位文件，直接判失败 */
    private static final long MIN_APK_SIZE = 1024L * 1024L;

    private LinearLayout downloadProgressLayout;
    private ProgressBar downloadProgressBar;
    private TextView downloadProgressText;
    private MaterialButton checkUpdateButton;

    private Context appContext;
    private boolean checkingUpdate;
    private boolean downloadingUpdate;

    /** 本次下载的候选地址（加速 -> 备用加速 -> 直连）与进度 */
    private String[] downloadUrls;
    private int downloadIndex;
    private String pendingVersion;
    /** 失败重试时记住上次的参数 */
    private String retryApkUrl;
    private String retryVersion;

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_about;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        appContext = getContext().getApplicationContext();
        findViewById(R.id.iv_close).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
            }
        });
        ((TextView) findViewById(R.id.tv_about_version))
                .setText("Version " + DefaultConfig.getAppVersionName(getContext()));

        TextView projectUrl = findViewById(R.id.tv_project_url);
        if (projectUrl != null) {
            projectUrl.setText(PROJECT_URL);
            projectUrl.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    openProjectPage();
                }
            });
        }

        downloadProgressLayout = findViewById(R.id.layout_update_progress);
        downloadProgressBar = findViewById(R.id.pb_update_progress);
        downloadProgressText = findViewById(R.id.tv_update_progress);
        checkUpdateButton = findViewById(R.id.btn_check_update);
        checkUpdateButton.setText("检查更新");
        checkUpdateButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                checkForUpdate();
            }
        });
    }

    @Override
    protected void onDismiss() {
        super.onDismiss();
        // 弹窗关掉就别再让请求回调改 UI 了
        OkGo.getInstance().cancelTag(TAG_CHECK);
        OkGo.getInstance().cancelTag(TAG_DOWNLOAD);
        checkingUpdate = false;
        downloadingUpdate = false;
    }

    private void openProjectPage() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Throwable throwable) {
            ToastUtils.showShort("没有可用的浏览器打开项目地址");
        }
    }

    // ---- update check ----

    private void checkForUpdate() {
        if (checkingUpdate || downloadingUpdate) {
            return;
        }
        checkingUpdate = true;
        OkGo.getInstance().cancelTag(TAG_CHECK);
        setCheckButtonBusy(true, "正在检查更新…");
        fetchManifest(0);
    }

    /** 更新清单有三个地址：加速 -> 备用加速 -> 直连，任意一个失败就换下一个 */
    private static String[] manifestCandidates() {
        return new String[]{
                ACCEL_PRIMARY + RAW_UPDATE_JSON,
                ACCEL_FALLBACK + RAW_UPDATE_JSON,
                RAW_UPDATE_JSON
        };
    }

    private void fetchManifest(final int index) {
        final String[] candidates = manifestCandidates();
        if (index >= candidates.length) {
            checkingUpdate = false;
            setCheckButtonBusy(false, null);
            showRetryDialog("检查更新失败", "无法获取更新信息，请检查网络连接后重试。", new Runnable() {
                @Override
                public void run() {
                    checkForUpdate();
                }
            });
            return;
        }
        OkGo.<String>get(candidates[index])
                .headers("User-Agent", "TVBox-Mobile")
                .tag(TAG_CHECK)
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() == null) {
                            throw new IllegalStateException("更新信息为空");
                        }
                        return response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            applyManifest(response.body());
                        } catch (Throwable throwable) {
                            // 这一路拿到的内容不完整或被加速站劫持，换下一个地址
                            fetchManifest(index + 1);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        fetchManifest(index + 1);
                    }
                });
    }

    private void applyManifest(String body) {
        JsonObject manifest = JsonParser.parseString(body).getAsJsonObject();
        final String remoteVersion = normalizeVersion(requiredString(manifest, "version"));
        final String apkUrl = pickApkUrl(manifest);
        String changelog = optString(manifest, "changelog");
        if (TextUtils.isEmpty(changelog)) {
            changelog = optString(manifest, "notes");
        }

        checkingUpdate = false;
        setCheckButtonBusy(false, null);

        String currentVersion = normalizeVersion(DefaultConfig.getAppVersionName(getContext()));
        if (compareVersion(remoteVersion, currentVersion) > 0) {
            showUpdateConfirm(remoteVersion, apkUrl, changelog, currentVersion);
        } else {
            ToastUtils.showShort("当前已是最新版本（v" + currentVersion + "）");
        }
    }

    private void showUpdateConfirm(final String remoteVersion, final String apkUrl,
                                   String changelog, String currentVersion) {
        StringBuilder content = new StringBuilder();
        content.append("当前版本：v").append(currentVersion).append('\n');
        content.append("最新版本：v").append(remoteVersion);
        if (!TextUtils.isEmpty(changelog)) {
            content.append("\n\n更新内容：\n").append(changelog);
        }
        content.append("\n\n是否下载并安装？");
        new XPopup.Builder(getContext())
                .asConfirm("发现新版本", content.toString(), "立即更新", "稍后",
                        new com.lxj.xpopup.interfaces.OnConfirmListener() {
                            @Override
                            public void onConfirm() {
                                downloadAndInstall(apkUrl, remoteVersion);
                            }
                        }, null, false)
                .show();
    }

    /** 32 位设备优先取 armv7a 包；清单里没有该字段时退回 apk_url */
    private static String pickApkUrl(JsonObject manifest) {
        String url = requiredString(manifest, "apk_url");
        if (is32BitOnlyDevice()) {
            String armv7a = optString(manifest, "apk_url_armv7a");
            if (!TextUtils.isEmpty(armv7a)) {
                return armv7a;
            }
        }
        return url;
    }

    private static boolean is32BitOnlyDevice() {
        String[] abis64 = Build.SUPPORTED_64_BIT_ABIS;
        return abis64 == null || abis64.length == 0;
    }

    // ---- download ----

    private void downloadAndInstall(String apkUrl, String version) {
        retryApkUrl = apkUrl;
        retryVersion = version;
        pendingVersion = version;

        String rawUrl = stripAccel(apkUrl);
        List<String> urls = new ArrayList<>();
        if (isGithubUrl(rawUrl)) {
            urls.add(ACCEL_PRIMARY + rawUrl);
            urls.add(ACCEL_FALLBACK + rawUrl);
        }
        urls.add(rawUrl);
        downloadUrls = urls.toArray(new String[0]);
        downloadIndex = 0;

        downloadingUpdate = true;
        setCheckButtonBusy(true, "正在下载更新…");
        if (downloadProgressLayout != null) {
            downloadProgressLayout.setVisibility(VISIBLE);
        }
        if (downloadProgressBar != null) {
            downloadProgressBar.setIndeterminate(true);
            downloadProgressBar.setProgress(0);
        }
        if (downloadProgressText != null) {
            downloadProgressText.setText("正在连接下载服务器…");
        }
        startDownload();
    }

    private void startDownload() {
        if (!downloadingUpdate) {
            return;
        }
        if (downloadUrls == null || downloadIndex >= downloadUrls.length) {
            onDownloadFailed();
            return;
        }
        final String url = downloadUrls[downloadIndex];
        OkGo.<File>get(url)
                .headers("User-Agent", "TVBox-Mobile")
                .tag(TAG_DOWNLOAD)
                .execute(new AbsCallback<File>() {
                    @Override
                    public File convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() == null) {
                            throw new IllegalStateException("更新包为空");
                        }
                        long total = response.body().contentLength();
                        if (total > 0 && total < MIN_APK_SIZE) {
                            throw new IllegalStateException("更新包大小异常");
                        }
                        File directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (directory == null) {
                            directory = appContext.getCacheDir();
                        }
                        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
                            throw new IllegalStateException("无法访问下载目录");
                        }
                        File apk = new File(directory,
                                "TVBox-Mobile-v" + (pendingVersion == null ? "new" : pendingVersion) + ".apk");
                        if (apk.exists() && !apk.delete()) {
                            throw new IllegalStateException("无法覆盖旧安装包");
                        }
                        long downloaded = 0;
                        int head0 = -1;
                        int head1 = -1;
                        byte[] buffer = new byte[8192];
                        try (InputStream input = response.body().byteStream();
                             FileOutputStream output = new FileOutputStream(apk)) {
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                if (head0 < 0) {
                                    head0 = buffer[0] & 0xff;
                                }
                                if (head1 < 0 && downloaded + read >= 2) {
                                    head1 = buffer[(int) (1 - downloaded)] & 0xff;
                                }
                                output.write(buffer, 0, read);
                                downloaded += read;
                                showDownloadProgress(downloaded, total);
                            }
                            output.flush();
                        } catch (Throwable throwable) {
                            if (apk.exists()) {
                                apk.delete();
                            }
                            throw throwable;
                        }
                        // "PK" 头 + 最小体积，挡住加速站返回的错误页
                        if (downloaded < MIN_APK_SIZE || head0 != 'P' || head1 != 'K') {
                            if (apk.exists()) {
                                apk.delete();
                            }
                            throw new IllegalStateException("更新包格式无效");
                        }
                        return apk;
                    }

                    @Override
                    public void onSuccess(Response<File> response) {
                        File apk = response.body();
                        if (apk == null || !apk.exists() || !validateApk(apk)) {
                            downloadIndex++;
                            startDownload();
                            return;
                        }
                        downloadingUpdate = false;
                        installApk(apk);
                    }

                    @Override
                    public void onError(Response<File> response) {
                        downloadIndex++;
                        startDownload();
                    }
                });
    }

    /** 校验下载到的包确实是本应用、且版本与清单一致，避免装上错误/被替换的包 */
    private boolean validateApk(File apk) {
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (info == null) {
                return false;
            }
            if (!appContext.getPackageName().equals(info.packageName)) {
                return false;
            }
            if (!TextUtils.isEmpty(pendingVersion) && !TextUtils.isEmpty(info.versionName)) {
                return compareVersion(info.versionName, pendingVersion) == 0;
            }
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void onDownloadFailed() {
        resetDownloadProgress();
        showRetryDialog("下载失败", "安装包下载失败，请检查网络后重试。", new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(retryApkUrl)) {
                    downloadAndInstall(retryApkUrl, retryVersion);
                }
            }
        });
    }

    // ---- progress UI ----

    private void showDownloadProgress(final long downloaded, final long total) {
        if (!downloadingUpdate || downloadProgressText == null) {
            return;
        }
        downloadProgressText.post(new Runnable() {
            @Override
            public void run() {
                if (!downloadingUpdate || downloadProgressText == null) {
                    return;
                }
                downloadProgressLayout.setVisibility(VISIBLE);
                if (total > 0) {
                    int percent = (int) Math.min(100, downloaded * 100 / total);
                    downloadProgressBar.setIndeterminate(false);
                    downloadProgressBar.setProgress(percent);
                    downloadProgressText.setText("正在下载 "
                            + Formatter.formatFileSize(appContext, downloaded)
                            + " / " + Formatter.formatFileSize(appContext, total)
                            + "（" + percent + "%）");
                } else {
                    downloadProgressBar.setIndeterminate(true);
                    downloadProgressText.setText("正在下载 "
                            + Formatter.formatFileSize(appContext, downloaded));
                }
            }
        });
    }

    private void resetDownloadProgress() {
        downloadingUpdate = false;
        downloadUrls = null;
        downloadIndex = 0;
        if (downloadProgressLayout != null) {
            downloadProgressLayout.setVisibility(GONE);
        }
        setCheckButtonBusy(false, null);
    }

    private void showDownloadComplete(final File apk) {
        downloadingUpdate = false;
        setCheckButtonBusy(false, null);
        if (downloadProgressText == null) {
            return;
        }
        downloadProgressText.post(new Runnable() {
            @Override
            public void run() {
                if (downloadProgressLayout == null) {
                    return;
                }
                downloadProgressLayout.setVisibility(VISIBLE);
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setProgress(100);
                downloadProgressText.setText("下载完成（"
                        + Formatter.formatFileSize(appContext, apk.length())
                        + "），正在打开安装程序");
            }
        });
    }

    private void setCheckButtonBusy(boolean busy, String text) {
        if (checkUpdateButton == null) {
            return;
        }
        checkUpdateButton.setEnabled(!busy);
        checkUpdateButton.setText(busy && !TextUtils.isEmpty(text) ? text : "检查更新");
    }

    // ---- install ----

    private void installApk(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !appContext.getPackageManager().canRequestPackageInstalls()) {
            ToastUtils.showLong("请先允许「安装未知应用」，然后重新检查更新");
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + appContext.getPackageName()));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(settings);
            } catch (Throwable ignored) {
                // 部分定制系统没有这个设置页，忽略即可
            }
            resetDownloadProgress();
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(appContext,
                    appContext.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            showDownloadComplete(apk);
        } catch (Throwable throwable) {
            ToastUtils.showLong("无法打开安装程序：" + throwable.getMessage());
            resetDownloadProgress();
        }
    }

    // ---- dialogs & url helpers ----

    private void showRetryDialog(String title, String content, final Runnable retry) {
        Context context = getContext();
        if (context == null || isDismiss()) {
            ToastUtils.showLong(content);
            return;
        }
        new XPopup.Builder(context)
                .asConfirm(title, content, "重试", "取消", new com.lxj.xpopup.interfaces.OnConfirmListener() {
                    @Override
                    public void onConfirm() {
                        if (retry != null) {
                            retry.run();
                        }
                    }
                }, null, false)
                .show();
    }

    private static boolean isGithubUrl(String url) {
        return url != null && url.startsWith("https://github.com/");
    }

    /** 去掉已有加速前缀，拿到原始 GitHub 地址 */
    private static String stripAccel(String url) {
        if (url == null) {
            return null;
        }
        String value = url;
        while (true) {
            if (value.startsWith(ACCEL_PRIMARY)) {
                value = value.substring(ACCEL_PRIMARY.length());
            } else if (value.startsWith(ACCEL_FALLBACK)) {
                value = value.substring(ACCEL_FALLBACK.length());
            } else {
                return value;
            }
        }
    }

    // ---- version utilities ----

    private static String requiredString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IllegalStateException("缺少更新字段: " + key);
        }
        String val = obj.get(key).getAsString();
        if (val == null || val.trim().isEmpty()) {
            throw new IllegalStateException("更新字段为空: " + key);
        }
        return val.trim();
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            String val = obj.get(key).getAsString();
            return val == null ? "" : val.trim();
        } catch (Throwable throwable) {
            return "";
        }
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        return version.trim().replaceFirst("^[vV]", "");
    }

    private static int compareVersion(String first, String second) {
        String[] left = normalizeVersion(first).split("\\.");
        String[] right = normalizeVersion(second).split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < left.length ? parseVersionPart(left[i]) : 0;
            int rightPart = i < right.length ? parseVersionPart(right[i]) : 0;
            if (leftPart != rightPart) {
                return leftPart > rightPart ? 1 : -1;
            }
        }
        return 0;
    }

    private static int parseVersionPart(String part) {
        String digits = part.replaceAll("[^0-9].*", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
