package com.dsh.inputfix;

import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 一个"没有键盘"的输入法。
 *
 * 它存在的唯一理由：只有 IME 身份才能拿到真正的 InputConnection。
 */
public class FixIme extends InputMethodService {

    private static volatile FixIme sInst = null;

    /**
     * 切回上一个输入法（通常是用户日常用的那个）。
     *
     * 关键：这是 InputMethodService 的公开 API，**不需要任何权限**，
     * 也不经过系统选择器，因此不受 dispatchGesture 被拦截的影响。
     * 前提只有一个：InputFix 当前就是激活的输入法（fill 时必然成立）。
     */
    public static boolean switchBack() {
        FixIme i = sInst;
        if (i == null) return false;
        try {
            return i.switchToPreviousInputMethod();
        } catch (Throwable t) {
            android.util.Log.e("InputFixIme", "switchBack failed: " + t);
            return false;
        }
    }

    /**
     * 输入法服务由系统绑定，优先级远高于普通前台服务 —— 进程被杀后
     * 系统会重新拉起来，所以把 API 和文件桥挂在这里最不容易断。
     */
    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        Api.start();
        FileBridge.start();
        android.util.Log.i("InputFixIme", "onCreate: api+bridge started");
    }

    @Override
    public void onDestroy() {
        if (sInst == this) sInst = null;
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public View onCreateInputView() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#1A1A1A"));
        bar.setPadding(dp(24), dp(8), dp(8), dp(8));
        bar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView label = new TextView(this);
        label.setText("InputFix · 通道已接管");
        label.setTextColor(Color.parseColor("#8AE28A"));
        label.setTextSize(11f);
        bar.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button back = new Button(this);
        back.setText("切回键盘");
        back.setTextSize(11f);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSwitchBackClick();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(96), dp(36)));

        return bar;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        Bridge.onInputStart();
        InputConnection c = getCurrentInputConnection();
        android.util.Log.i("InputFixIme", "onStartInput editor=" + attribute
                + " conn=" + (c != null) + " restarting=" + restarting);
        Bridge.onImeAttached(c, attribute);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        InputConnection c = getCurrentInputConnection();
        android.util.Log.i("InputFixIme", "onStartInputView conn=" + (c != null));
        Bridge.onImeAttached(c, info);
    }

    @Override
    public void onFinishInput() {
        android.util.Log.i("InputFixIme", "onFinishInput");
        Bridge.onImeDetached();
        super.onFinishInput();
    }

    @Override
    public void onUnbindInput() {
        Bridge.onImeDetached();
        super.onUnbindInput();
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    /** 面板上「切回键盘」按钮：切不回就弹选择器让用户自己选。 */
    private void onSwitchBackClick() {
        try {
            if (!switchToPreviousInputMethod()) {
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showInputMethodPicker();
            }
        } catch (Throwable ignored) {
        }
    }
}
