package com.screenoff.tile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 关屏期间常驻的前台服务。
 *
 * 关屏时 framework 仍认为屏幕亮着，应用进程可能仍被 cached app freezer 归为 cached
 * 并冻结；一旦被冻结，音量键无障碍拦截、磁贴状态刷新、以及应用侧恢复逻辑都会失效。
 * 前台服务把进程提升到 foreground-service 优先级，freezer 不会冻结它。
 *
 * 本服务只负责“保活”，不持唤醒锁（唤醒锁 + root 内核 wakelock 由 ScreenController 负责）。
 * 通知里带一个「恢复屏幕」按钮，即使触摸被禁用也能从通知栏恢复。
 */
public class KeepAliveService extends Service {
    public static final String ACTION_START = "com.screenoff.tile.action.KEEPALIVE_START";
    public static final String ACTION_STOP = "com.screenoff.tile.action.KEEPALIVE_STOP";

    private static final String CHANNEL_ID = "screenoff_keepalive";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            // turnScreenOn 会在主线程执行 su，放到后台线程避免 ANR
            new Thread(() -> {
                new ScreenController(getApplicationContext()).turnScreenOn();
                stopSelf();
            }, "restore-from-notif").start();
            return START_NOT_STICKY;
        }
        // startForegroundService 后必须尽快 startForeground，onCreate 已处理
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription(getString(R.string.keepalive_channel_desc));
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent content = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(
                this, 1,
                new Intent(this, KeepAliveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.keepalive_title))
                .setContentText(getString(R.string.keepalive_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(0, getString(R.string.keepalive_restore), stop)
                .build();
    }
}
