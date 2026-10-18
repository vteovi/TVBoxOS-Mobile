package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.SizeUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.BackupManager;
import com.github.tvbox.osc.util.LanSyncManager;

import java.io.File;
import java.util.List;

/**
 * 局域网设备同步: 一台"发送配置"、另一台"接收配置", 在同一 WiFi 下即可完成配置搬家。
 * 同步内容与"数据备份还原"一致(收藏 / 历史 / 设置)。
 */
public class DeviceSyncDialog extends BaseDialog {

    private final Context mContext;
    private final LanSyncManager mLanSync = new LanSyncManager();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private TextView tvStatus;
    private TextView btnSend;
    private TextView btnReceive;
    private LinearLayout llDevices;
    private LinearLayout llManual;
    private EditText etIp;
    private EditText etPort;
    private boolean sending = false;   // 是否正在"发送配置"

    public DeviceSyncDialog(@NonNull Context context) {
        super(context);
        mContext = context;
        setContentView(R.layout.dialog_device_sync);

        tvStatus = findViewById(R.id.tvStatus);
        btnSend = findViewById(R.id.btnSend);
        btnReceive = findViewById(R.id.btnReceive);
        llDevices = findViewById(R.id.llDevices);
        llManual = findViewById(R.id.llManual);
        etIp = findViewById(R.id.etIp);
        etPort = findViewById(R.id.etPort);
        TextView btnManual = findViewById(R.id.btnManual);

        btnSend.setOnClickListener(v -> toggleSend());
        btnReceive.setOnClickListener(v -> startDiscover());
        btnManual.setOnClickListener(v -> connectManual());
    }

    // ---------------- 发送配置 ----------------

    private void toggleSend() {
        if (sending) {
            stopSend();
            return;
        }
        tvStatus.setText("正在启动服务…");
        mLanSync.startServer(mContext, new LanSyncManager.ServerListener() {
            @Override
            public void onStarted(String addr) {
                sending = true;
                btnSend.setText("停止发送");
                btnReceive.setVisibility(View.GONE);
                llManual.setVisibility(View.GONE);
                llDevices.removeAllViews();
                tvStatus.setText("等待对方接收\n本机地址：" + addr
                        + "\n（在另一台设备上点「接收配置」即可）");
            }

            @Override
            public void onSent(String remoteIp) {
                tvStatus.setText("已发送给 " + remoteIp + "\n可以点「停止发送」结束。");
            }

            @Override
            public void onError(String msg) {
                sending = false;
                btnSend.setText("发送配置");
                btnReceive.setVisibility(View.VISIBLE);
                tvStatus.setText("启动失败：" + msg + "\n请检查两台设备是否在同一 WiFi。");
            }
        });
    }

    private void stopSend() {
        mLanSync.stopServer();
        sending = false;
        btnSend.setText("发送配置");
        btnReceive.setVisibility(View.VISIBLE);
        tvStatus.setText("已停止发送。");
    }

    // ---------------- 接收配置 ----------------

    private void startDiscover() {
        if (sending) {
            ToastUtils.showShort("当前正在发送配置");
            return;
        }
        llDevices.removeAllViews();
        llManual.setVisibility(View.GONE);
        tvStatus.setText("正在扫描同一 WiFi 下的设备（自动探测子网）…");
        btnReceive.setEnabled(false);

        mLanSync.discover(mContext, 8000, new LanSyncManager.DiscoverListener() {
            @Override
            public void onResult(List<LanSyncManager.Device> devices) {
                btnReceive.setEnabled(true);
                if (devices.isEmpty()) {
                    tvStatus.setText("没有自动发现设备。\n请确认对方已点「发送配置」，或直接填对方地址连接。");
                    llManual.setVisibility(View.VISIBLE);
                    return;
                }
                tvStatus.setText("发现 " + devices.size() + " 台设备，点击要接收的那一台：");
                for (LanSyncManager.Device d : devices) {
                    llDevices.addView(buildDeviceItem(d));
                }
                llManual.setVisibility(View.VISIBLE);
            }
        });
    }

    private View buildDeviceItem(LanSyncManager.Device device) {
        TextView item = new TextView(mContext);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = SizeUtils.dp2px(8);
        item.setLayoutParams(lp);
        item.setText(device.name + "\n" + device.ip + ":" + device.port);
        item.setGravity(android.view.Gravity.CENTER);
        item.setTextSize(14);
        item.setTextColor(mContext.getResources().getColor(R.color.text_foreground));
        item.setBackgroundResource(R.drawable.bg_r_common_stroke_primary);
        item.setPadding(SizeUtils.dp2px(12), SizeUtils.dp2px(10), SizeUtils.dp2px(12), SizeUtils.dp2px(10));
        item.setFocusable(true);
        item.setOnClickListener(v -> fetchFrom(device.ip, device.port));
        return item;
    }

    private void connectManual() {
        String ip = etIp.getText() == null ? "" : etIp.getText().toString().trim();
        if (ip.isEmpty()) {
            ToastUtils.showShort("请输入对方 IP");
            return;
        }
        // 端口默认 8099(与发送端首选端口一致), 只有对方端口不同时才改
        int port = 8099;
        String portText = etPort.getText() == null ? "" : etPort.getText().toString().trim();
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                ToastUtils.showShort("端口格式不对");
                return;
            }
        }
        fetchFrom(ip, port);
    }

    /** 从指定地址下载配置并导入 */
    private void fetchFrom(String ip, int port) {
        tvStatus.setText("正在从 " + ip + " 接收配置…");
        btnReceive.setEnabled(false);
        btnSend.setEnabled(false);
        new Thread(() -> {
            File out = new File(mContext.getCacheDir(), "tvbox_lan_restore.json");
            boolean ok = false;
            String err = null;
            try {
                if (LanSyncManager.downloadConfig(ip, port, out)) {
                    ok = BackupManager.importBackup(out);
                    if (!ok) {
                        err = "文件格式错误";
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                err = e.getMessage();
            } finally {
                out.delete();
            }
            final boolean success = ok;
            final String error = err;
            mMain.post(() -> {
                btnReceive.setEnabled(true);
                btnSend.setEnabled(true);
                if (success) {
                    ToastUtils.showShort("同步成功,即将重启应用!");
                    tvStatus.setText("接收成功，即将重启应用…");
                    mMain.postDelayed(() -> AppUtils.relaunchApp(true), 2000);
                } else {
                    tvStatus.setText("接收失败：" + (error == null ? "无法连接" : error)
                            + "\n请确认对方仍停在「发送配置」界面。");
                }
            });
        }).start();
    }

    @Override
    public void dismiss() {
        mLanSync.stopServer();
        super.dismiss();
    }
}
