package lll.bridge.termux;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku-backed one-shot permission fixer.
 *
 * Flow: Shizuku binder -> (first use) Shizuku permission dialog -> bind a
 * UserService (shell uid) -> pm grant / appops set / settings put -> unbind.
 *
 * Everything here degrades gracefully: no Shizuku installed/running simply
 * yields a report with available=false; callers fall back to manual UIs.
 */
final class ShizukuPerms {
    private ShizukuPerms() {}

    static final String[] RUNTIME_PERMS = {
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
    };

    interface Listener { void done(String summary); }

    /** non-blocking: kicks the binder request + bind + fix, reports via listener */
    static void fixAsync(android.content.Context ctx, Listener cb) {
        final String self = ctx.getPackageName();
        if (!Shizuku.pingBinder()) {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                boolean ran = false;
                @Override public void onBinderReceived() {
                    if (ran) return;
                    ran = true;
                    runFix(self, cb);
                }
            });
            // if still nothing after 8s the server is not running
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> { if (!Shizuku.pingBinder()) cb.done("shizuku not running"); }, 8000);
        } else {
            runFix(self, cb);
        }
    }

    private static void runFix(String self, Listener cb) {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                    @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) bindAndFix(self, cb);
                        else cb.done("shizuku permission denied");
                    }
                });
                Shizuku.requestPermission(19129);
                return;
            }
            bindAndFix(self, cb);
        } catch (Throwable t) {
            cb.done("shizuku error: " + t);
        }
    }

    private static void bindAndFix(String self, Listener cb) {
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(self, PermsService.class.getName()))
                    .daemon(false).version(1).tag("tbridge-perms");
            ServiceConnection conn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName n, IBinder b) {
                    IPermsHelper svc = IPermsHelper.Stub.asInterface(b);
                    StringBuilder sb = new StringBuilder();
                    try {
                        int uid = Shizuku.getUid();
                        sb.append("shizuku uid=").append(uid).append("\n");
                        for (String perm : RUNTIME_PERMS)
                            sb.append(exec(svc, "pm", "grant", self, perm));
                        sb.append(exec(svc, "appops", "set", self,
                                "android:write_settings", "allow"));
                        sb.append(exec(svc, "appops", "set", self,
                                "SYSTEM_ALERT_WINDOW", "allow"));
                        sb.append(exec(svc, "cmd", "notification", "allow_listener",
                                self + "/lll.bridge.termux.BridgeNotifListener"));
                        sb.append(exec(svc, "settings", "put", "secure",
                                "enabled_accessibility_services",
                                "lll.bridge.termux/lll.bridge.termux.BridgeA11yService"));
                        sb.append(exec(svc, "settings", "put", "secure",
                                "accessibility_enabled", "1"));
                    } catch (Throwable t) {
                        sb.append("error: ").append(t).append('\n');
                    }
                    try { Shizuku.unbindUserService(args, null, true); } catch (Throwable ignored) {}
                    cb.done(sb.toString());
                }
                @Override public void onServiceDisconnected(ComponentName n) {}
            };
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            cb.done("bind failed: " + t);
        }
    }

    private static String exec(IPermsHelper svc, String... cmd) {
        try {
            String r = svc.run(cmd);
            String first = r.contains("\n") ? r.substring(0, r.indexOf('\n')) : r;
            return cmd[0] + " " + cmd[1] + ": " + first + "\n";
        } catch (Throwable t) {
            return cmd[0] + " failed: " + t + "\n";
        }
    }

    /** quick availability probe for UI text (never throws) */
    static boolean available() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }
}
