package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ClipboardUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.databinding.FragmentMyBinding;
import com.github.tvbox.osc.ui.activity.LiveActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.dialog.AboutDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.interfaces.OnInputConfirmListener;

import java.util.Arrays;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class MyFragment extends BaseVbFragment<FragmentMyBinding> {

    @Override
    protected void init() {
        mBinding.tvVersion.setText("v"+ AppUtils.getAppVersionName());

        mBinding.addrPlay.setOnClickListener(v ->{
            new XPopup.Builder(getContext())
                    .asInputConfirm("点播", "", isPush(ClipboardUtils.getText().toString())?ClipboardUtils.getText():"", "影视地址", text -> {
                        if (!TextUtils.isEmpty(text)){
                            Intent newIntent = new Intent(mContext, DetailActivity.class);
                            newIntent.putExtra("id", text);
                            newIntent.putExtra("sourceKey", "push_agent");
                            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(newIntent);
                        }
                    }, null, R.layout.dialog_input).show();
        });
        mBinding.tvSetting.setOnClickListener(v -> jumpActivity(SettingActivity.class));

        mBinding.tvHistory.setOnClickListener(v -> jumpActivity(HistoryActivity.class));

        mBinding.tvLive.setOnClickListener(v -> jumpActivity(LiveActivity.class));

        mBinding.llAbout.setOnClickListener(v -> {
            new XPopup.Builder(mActivity)
                    .asCustom(new AboutDialog(mActivity))
                    .show();
        });
    }

    private boolean isPush(String text) {
        return !TextUtils.isEmpty(text) && Arrays.asList("smb", "http", "https", "thunder", "magnet", "ed2k", "mitv", "jianpian").contains(Uri.parse(text).getScheme());
    }

}
