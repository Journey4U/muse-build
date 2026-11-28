package com.muse.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.ui.custom.RemoteJump;
import com.muse.android.tv.databinding.DialogMpvConfBinding;
import com.muse.android.tv.event.ServerEvent;
import com.muse.android.tv.player.mpv.MpvConfigFile;
import com.muse.android.tv.server.Server;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.QRCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MpvConfDialog extends BaseAlertDialog {

    private DialogMpvConfBinding binding;

    public static void show(FragmentActivity activity) {
        new MpvConfDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogMpvConfBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        setText(MpvConfigFile.read());
        binding.code.setImageBitmap(QRCode.getBitmap(Server.get().getAddress(4), 200, 0));
    }

    @Override
    protected void initEvent() {
        // 遥控器：输入框光标在最前按左 / 在最后按右，直达底部按钮层最左 / 最右（光标在中间照常移光标）；
        // 底部按钮层左右只在层内移动、到头停住，上键回输入框
        binding.text.setNextFocusLeftId(R.id.positive);
        binding.text.setNextFocusRightId(R.id.negative);
        RemoteJump.bindRow(() -> binding.text.requestFocus(), null, binding.positive, binding.negative);
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(this::onNegative);
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) binding.positive.performClick();
            return true;
        });
    }

    private void setText(String text) {
        binding.text.setText(text);
        binding.text.setSelection(TextUtils.isEmpty(text) ? 0 : text.length());
    }

    private void onPositive(View view) {
        if (MpvConfigFile.write(binding.text.getText().toString())) dismiss();
        else Notify.show(R.string.player_mpv_conf_save_failed);
    }

    private void onNegative(View view) {
        dismiss();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING) return;
        setText(event.text());
        binding.positive.performClick();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
        // 返回逐层退：焦点在底部按钮上按返回先回输入框，不关弹窗；焦点在输入框里按返回照原样关闭
        RemoteJump.backToContent(getDialog(), () -> binding.text.requestFocus(), binding.positive, binding.negative);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }
}
