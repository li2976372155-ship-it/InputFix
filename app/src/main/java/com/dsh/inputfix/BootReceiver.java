package com.dsh.inputfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机 / 装更新后自动把桥拉起来。
 *
 * IME 只会在"系统决定绑定输入法"时才活，开机到第一次输入之间有空窗；
 * 这个接收器补上那一段。
 *
 * 注意：HyperOS 会拦 BOOT_COMPLETED，需要手动在
 * 设置 → 应用管理 → InputFix → 自启动 里放行。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent svc = new Intent(context, FixService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Throwable t) {
            android.util.Log.e("InputFixBoot", "start service failed: " + t);
        }

        // 双保险：直接在当前进程里起，不依赖服务能否拉起来
        try {
            Api.start();
            FileBridge.start();
        } catch (Throwable t) {
            android.util.Log.e("InputFixBoot", "start bridge failed: " + t);
        }
    }
}
