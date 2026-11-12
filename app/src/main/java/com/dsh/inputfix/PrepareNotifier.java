package com.dsh.inputfix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 「要填了」提醒。
 *
 * Agent 调 prepare 时，如果不是当前输入法，就弹这条通知。
 * 用户点一下 → PickerActivity 弹出系统输入法选择器 → 选 InputFix 即可。
 *
 * 这样用户不用自己想起来"该切输入法了"，是工具主动叫他。
 */
final class PrepareNotifier {

    private static final String CHANNEL = "inputfix_prepare";
    private static final int ID = 2001;

    private PrepareNotifier() {
    }

    static boolean show(Context ctx, String reason) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;

            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "InputFix 待命提醒", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("有程序要用 InputFix 往输入框里写字时提醒你切换");
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }

            // 点通知 → 弹输入法选择器
            Intent it = new Intent(ctx, PickerActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentTitle("需要切换输入法")
                    .setContentText(reason + " · 点此切换")
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(reason + "\n\n点一下 → 选择「InputFix」→ 完成后会自动切回搜狗。"))
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentIntent(pi)
                    .build();

            nm.notify(ID, n);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("InputFix", "notify failed: " + t);
            return false;
        }
    }

    static void clear(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {
        }
    }
}
