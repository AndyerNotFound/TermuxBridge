package lll.bridge.termux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service: lifecycle holder for the bridge (ongoing notification,
 * START_STICKY, panic flag, media-projection FGS upgrade).
 *
 * v2.0.1 transport: a request arrives as an explicit broadcast addressed to
 * {@link BridgeReceiver}
 *   am broadcast -n lll.bridge.termux/.BridgeReceiver \
 *                -a lll.bridge.termux.CMD --es json '{"cmd":"..."}' \
 *                --es res 127.0.0.1:PORT
 * and the response is written back by this app to the loopback TCP listener the
 * caller owns (`res`, ephemeral port). The caller never has to accept an inbound
 * connection, and this app never listens on a socket.
 *
 * Why the v2.0.0 abstract unix socket was dropped: with SELinux Enforcing,
 * connect() from Termux (untrusted_app_27) to this app (untrusted_app) is denied
 * with EACCES even while the server is listening (verified on device
 * 2026-10-27). Broadcasts travel over binder and loopback TCP is not
 * domain-gated, so this pair works for every caller: Termux, shell, root.
 */
public class BridgeService extends Service {
    public static final String ACTION_START = "lll.bridge.START";
    public static final String ACTION_STOP = "lll.bridge.STOP";
    public static final String ACTION_UPGRADE_FGS = "lll.bridge.UPGRADE_FGS";

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground();
    }

    private Notification buildNotification() {
        String ch = "bridge";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(ch, getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.notif_service_title))
                .setContentText("broadcast: " + BridgeReceiver.ACTION_CMD)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void startForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(a)) Prefs.setPanicked(this, false);   // explicit start lifts the brake
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_UPGRADE_FGS.equals(a) && Build.VERSION.SDK_INT >= 34) {
            try {
                Notification n = buildNotification();
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } catch (Exception e) {
                android.util.Log.e("TBridge", "fgs upgrade: " + e);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
