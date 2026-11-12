package com.dsh.inputfix;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;

/**
 * 一个透明、无内容、几秒后自动消失的 Activity。
 *
 * 存在的唯一理由：showInputMethodPicker() 从后台 Application context 调用
 * 不会真的弹出来，必须有一个"前台 Activity"身份。
 *
 * 它不显示任何文字 —— 这点很关键，否则无障碍的 tapByText 会点到它自己身上，
 * 而不是系统选择器里的目标项。
 */
public class PickerActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
            getWindow().setDimAmount(0f);
        } catch (Throwable ignored) {
        }

        android.util.Log.i("InputFixPicker", "onCreate, will close at +30s");

        // 让它有一帧前台身份，再弹选择器
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showInputMethodPicker();
                        android.util.Log.i("InputFixPicker", "showInputMethodPicker called");
                    }
                } catch (Throwable t) {
                    android.util.Log.e("InputFixPicker", "show failed: " + t);
                }
            }
        }, 250);

        // 给用户足够的时间看清并点选。30 秒，点完选择器会自己消失
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.util.Log.i("InputFixPicker", "auto finishing");
                try {
                    finish();
                } catch (Throwable ignored) {
                }
            }
        }, 30000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        android.util.Log.i("InputFixPicker", "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        android.util.Log.i("InputFixPicker", "onStop  ← 可能在这里被系统关掉");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
