package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;

/**
 * 「重置软件」说明弹窗：先讲清楚会清掉什么，再让用户确认。
 *
 * 真正的二次确认（"上次一次机会"的强提示）由调用方在 {@link OnActionListener#onReset()}
 * 里再弹一次，避免误触把数据清了。
 */
public class ResetAppDialog extends BaseDialog {

    public interface OnActionListener {
        void onReset();
    }

    public ResetAppDialog(@NonNull Context context, final OnActionListener listener) {
        super(context);
        setContentView(R.layout.dialog_reset_app);
        View btnCancel = findViewById(R.id.btnCancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
        }
        View btnReset = findViewById(R.id.btnReset);
        if (btnReset != null) {
            btnReset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (listener != null) {
                        listener.onReset();
                    }
                }
            });
        }
    }
}
