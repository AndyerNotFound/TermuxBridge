package lll.bridge.termux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Foreground service: local JSON server on an abstract-namespace unix socket.
 * One connection per request: one JSON line in, one JSON line out.
 *
 * v2.0.0 breaking change: the old 127.0.0.1:8021 TCP listener is gone.
 * Same wire format, new transport - clients must speak AF_UNIX
 * (Node path "\0lll.bridge.termux", Android LocalSocket, Python "\0"+name).
 *
 * Abstract socket names are reachable by every app on the device, so peers
 * are gated by uid (the local-socket analogue of the old port+token pair):
 * the app itself, Termux, shell (2000) and root (0).
 */
public class BridgeService extends Service {
    public static final String ACTION_START = "lll.bridge.START";
    public static final String ACTION_STOP = "lll.bridge.STOP";
    public static final String ACTION_UPGRADE_FGS = "lll.bridge.UPGRADE_FGS";
    /** abstract unix socket name (leading NUL implied by the namespace) */
    public static final String SOCKET_NAME = "lll.bridge.termux";
    private static volatile int termuxUid = -1;
    private ServerThread thread;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground();
        thread = new ServerThread();
        thread.start();
    }

    private Notification buildNotification() {
        String ch = "bridge";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(ch, getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.notif_service_title))
                .setContentText("unix:@" + SOCKET_NAME)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        return n;
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
        if (thread != null) thread.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private class ServerThread extends Thread {
        private volatile LocalServerSocket server;

        @Override
        public void run() {
            try {
                server = new LocalServerSocket(SOCKET_NAME);   // abstract namespace
                while (true) {
                    if (Prefs.panicked(BridgeService.this)) {   // brake: no new connections
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                    LocalSocket s = server.accept();
                    if (!allowed(s)) {
                        android.util.Log.w("TBridge", "uid rejected: " + peerUid(s));
                        try { s.close(); } catch (Exception ignored) {}
                        continue;
                    }
                    new Handler(s).start();
                }
            } catch (Exception e) {
                android.util.Log.e("TBridge", "server died: " + e);
            }
        }

        /** uid gate: the abstract name is not a lock, the peer uid is. */
        private boolean allowed(LocalSocket s) {
            int uid = peerUid(s);
            if (uid < 0) return false;
            if (uid == android.os.Process.myUid()) return true;   // self (IME, widget)
            if (uid == 0 || uid == 2000) return true;             // root / shell (rish)
            return uid == termuxUid();
        }

        private int termuxUid() {
            if (termuxUid < 0) {
                try { termuxUid = getPackageManager().getPackageUid("com.termux", 0); }
                catch (Exception e) { termuxUid = -2; }           // Termux not installed
            }
            return termuxUid;
        }

        void shutdown() {
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }
    }

    private static int peerUid(LocalSocket s) {
        try { return s.getPeerCredentials().getUid(); }
        catch (Exception e) { return -1; }
    }

    private class Handler extends Thread {
        private final LocalSocket sock;
        Handler(LocalSocket s) { sock = s; }

        @Override
        public void run() {
            try {
                sock.setSoTimeout(30000);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
                String req = in.readLine();
                if (req == null || req.trim().isEmpty()) return;
                // peer already passed the uid gate in the accept loop
                String resp = Dispatcher.dispatch(BridgeService.this, req, false);
                OutputStream out = sock.getOutputStream();
                out.write((resp + "\n").getBytes("UTF-8"));
                out.flush();
            } catch (Exception e) {
                android.util.Log.e("TBridge", "handler: " + e);
            } finally {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
    }
}
