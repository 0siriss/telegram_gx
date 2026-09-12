package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.telegram.ui.LaunchActivity;

/**
 * TGX: holds the process at foreground priority while the UI is backgrounded so
 * ConnectionsManager's native network layer isn't paused (see ApplicationLoaderImpl.onPause).
 * Only meaningful because real FCM push is unavailable for this fork's signing certificate
 * (see project notes on Firebase Installations FIS_AUTH_ERROR) -- this substitutes a live
 * MTProto socket for push wake-up, at the cost of battery.
 */
public class ConnectionKeepAliveService extends Service {

    private static final int NOTIFICATION_ID = 7;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ConnectionKeepAliveService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, ConnectionKeepAliveService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Intent contentIntent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
            contentIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    ApplicationLoader.applicationContext, 0, contentIntent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationsController.checkOtherNotificationsChannel();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            builder.setSmallIcon(R.drawable.notification);
            builder.setContentTitle(LocaleController.getString(R.string.AppName));
            builder.setContentText("Соединение поддерживается для мгновенных уведомлений");
            builder.setContentIntent(pendingIntent);
            builder.setOngoing(true);
            builder.setPriority(NotificationCompat.PRIORITY_MIN);
            builder.setShowWhen(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, builder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
    }
}
