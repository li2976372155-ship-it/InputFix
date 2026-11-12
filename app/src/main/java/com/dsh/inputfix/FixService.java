package com.dsh.inputfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 常驻服务：把 127.0.0.1:8791 的 API 挂住。
 *
 * 没有它的话，API 只在打开主界面时存在 —— 一旦 App 退到后台被清理，
 * dsh-go 那边 curl 就是 Connection refused。
 *
 * START_STICKY + 前台通知 = 只要用户开过一次，就一直活着。
 */
public class FixService extends Service {

    private static final String CHANNEL_ID = "inputfix_bridge";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.dsh.inputfix.START";

    @Override
    public void onCreate() {
        super.onCreate();
        Api.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        Api.start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // 不主动 stop API：system 重启进程时会重新 onCreate
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "InputFix 输入桥", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("本地 127.0.0.1:8791 输入桥");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("InputFix 已就绪")
                .setContentText("127.0.0.1:8791 · 输入通道待命")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true);
        return b.build();
    }
}
