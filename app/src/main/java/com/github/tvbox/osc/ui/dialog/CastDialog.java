package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.CastDevice;
import com.github.tvbox.osc.util.CastManager;
import com.blankj.utilcode.util.ToastUtils;
import com.lxj.xpopup.core.CenterPopupView;

import java.util.ArrayList;
import java.util.List;

/**
 * 投屏设备选择弹窗
 * 复用 layout/dialog_cast.xml: 标题 + 设备列表(RecyclerView) + 关闭/刷新按钮
 * 采用 XPopup 的 CenterPopupView 承载(与项目内其它弹窗一致), 由 XPopup 自动负责
 * 居中、宽度约束(屏幕宽减边距)与背景, 避免原生 Dialog 默认 WRAP_CONTENT 把布局压塌。
 */
public class CastDialog extends CenterPopupView {
    private final String mUrl;
    private final String mTitle;
    private final long mPosition;
    private final List<CastDevice> mDevices = new ArrayList<>();
    private final CastAdapter mAdapter = new CastAdapter();
    private RecyclerView mRv;
    private TextView mTip;
    private boolean mDiscovering = false;

    public CastDialog(@NonNull Context context, String url, String title, long position) {
        super(context);
        this.mUrl = url;
        this.mTitle = title;
        this.mPosition = position;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_cast;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mRv = findViewById(R.id.rv);
        mTip = findViewById(R.id.tip);
        mRv.setLayoutManager(new LinearLayoutManager(getContext()));
        mRv.setAdapter(mAdapter);

        findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());
        findViewById(R.id.btn_confirm).setOnClickListener(v -> startDiscovery());

        startDiscovery();
    }

    private void startDiscovery() {
        if (mDiscovering) return;
        mDiscovering = true;
        mDevices.clear();
        mAdapter.notifyDataSetChanged();
        updateEmptyState();
        CastManager.getInstance().discover(new CastManager.DiscoveryListener() {
            @Override
            public void onDevices(List<CastDevice> devices) {
                mDevices.clear();
                mDevices.addAll(devices);
                mAdapter.notifyDataSetChanged();
                updateEmptyState();
            }

            @Override
            public void onDiscoveryFinished(List<CastDevice> devices) {
                mDiscovering = false;
                mDevices.clear();
                mDevices.addAll(devices);
                mAdapter.notifyDataSetChanged();
                updateEmptyState();
            }
        });
    }

    private void updateEmptyState() {
        if (mDevices.isEmpty()) {
            mTip.setVisibility(View.VISIBLE);
            mTip.setText(mDiscovering ? "搜索中..." : "未找到可投屏设备");
            mRv.setVisibility(View.GONE);
        } else {
            mTip.setVisibility(View.GONE);
            mRv.setVisibility(View.VISIBLE);
        }
    }

    private void castTo(CastDevice device) {
        CastManager.getInstance().cast(device, mUrl, mTitle, mPosition, new CastManager.CastListener() {
            @Override
            public void onResult(boolean success, String deviceName) {
                if (success) {
                    ToastUtils.showShort("正在投屏到 " + deviceName);
                    dismiss();
                } else {
                    ToastUtils.showShort("投屏失败：" + deviceName);
                }
            }
        });
    }

    private class CastAdapter extends RecyclerView.Adapter<CastAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_title, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final CastDevice d = mDevices.get(pos);
            h.title.setText(d.name);
            h.itemView.setOnClickListener(v -> castTo(d));
        }

        @Override
        public int getItemCount() {
            return mDevices.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.title);
            }
        }
    }
}
