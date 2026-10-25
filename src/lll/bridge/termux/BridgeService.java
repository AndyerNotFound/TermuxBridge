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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 前台服务: 本地 TCP JSON 服务。一连接一请求: 一行 JSON 入, 一行 JSON 出。
 * 常驻服务避免每命令冷启动; 仅监听 127.0.0.1。 */
public class BridgeService extends Service {
    public static final String ACTION_START = "lll.bridge.START";
    public static final String ACTION_STOP = "lll.bridge.STOP";
    public static final String ACTION_UPGRADE_FGS = "lll.bridge.UPGRADE_FGS";
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
        nm.createNotificationChannel(new NotificationChannel(ch, "TermuxBridge 服务",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("TermuxBridge 运行中")
                .setContentText("127.0.0.1:" + Prefs.port(this))
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
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_UPGRADE_FGS.equals(a) && Build.VERSION.SDK_INT >= 34) {
            // MediaProjection 前必须: 服务以 mediaProjection 类型运行(截图授权已由 Activity 取得)
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
        private volatile ServerSocket server;
        private volatile int boundPort;

        @Override
        public void run() {
            try {
                int port = Prefs.port(BridgeService.this);
                server = new ServerSocket();
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                boundPort = port;
                while (true) {
                    Socket s = server.accept();
                    new Handler(s).start();
                }
            } catch (Exception e) {
                android.util.Log.e("TBridge", "server died: " + e);
            }
        }
        void shutdown() {
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }
    }

    private class Handler extends Thread {
        private final Socket sock;
        Handler(Socket s) { sock = s; }

        @Override
        public void run() {
            try {
                sock.setSoTimeout(30000);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
                String req = in.readLine();
                if (req == null || req.trim().isEmpty()) return;
                // 只接受来自本机的连接(127.0.0.1 绑定已保证)
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
