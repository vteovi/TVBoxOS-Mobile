package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.R;

public class BackupDialog extends BaseDialog {

    public interface OnActionListener {
        void onBackup();

        void onRestore();
    }

    public BackupDialog(@NonNull Context context, @Nullable OnActionListener listener) {
        super(context);
        setContentView(R.layout.dialog_backup);
        findViewById(R.id.backupNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onBackup();
                dismiss();
            }
        });
        findViewById(R.id.restoreBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onRestore();
                dismiss();
            }
        });
    }
}
