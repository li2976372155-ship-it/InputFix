package com.dsh.inputfix;

import android.app.Application;
import android.content.Context;

/**
 * 任何组件（Activity / IME / Service / Accessibility）被拉起时都会先走这里，
 * 所以 API 和文件桥一定能起来。
 */
public class FixApplication extends Application {

    private static volatile FixApplication sInstance = null;

    public static Context ctx() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Api.start();
        // /sdcard/Download/InputFix/inbox —— proot 网络隔离时的唯一通路
        FileBridge.start();
    }
}
