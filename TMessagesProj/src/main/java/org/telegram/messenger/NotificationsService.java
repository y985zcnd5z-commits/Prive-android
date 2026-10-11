/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * Prive: runs as a real FOREGROUND service (keep-alive connection) so Android doesn't kill the MTProto
 * connection when there is no FCM push; also drives the Website Connect notification poll.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class NotificationsService extends Service {

    private static final String KEEPALIVE_CHANNEL = "prive_keepalive";
    private static final int KEEPALIVE_ID = 0x5B0001;

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
        ApplicationLoader.postInitApplication();
        org.telegram.prive.PriveWcNotifier.ensureChannel(this);
        org.telegram.prive.PriveWcNotifier.start();
    }

    private void goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(KEEPALIVE_CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(KEEPALIVE_CHANNEL, "Background connection", NotificationManager.IMPORTANCE_MIN);
                    ch.setDescription("Keeps Prive connected so messages arrive instantly. You can hide this notification in the system settings.");
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            Intent open = new Intent(this, org.telegram.ui.LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Notification n = new NotificationCompat.Builder(this, KEEPALIVE_CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setColor(0xFFC9A86A)
                    .setContentTitle("Prive is connected")
                    .setContentText("Messages arrive instantly")
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(KEEPALIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(KEEPALIVE_ID, n);
            }
        } catch (Throwable e) {
            FileLog.e(e); // e.g. ForegroundServiceStartNotAllowedException: keep running as a plain service
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        org.telegram.prive.PriveWcNotifier.stop();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", org.telegram.prive.PriveConfig.keepAliveDefault())) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
