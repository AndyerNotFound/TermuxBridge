package lll.bridge.termux;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/** 通知监听相关命令 */
final class CmdsListen {
    private CmdsListen() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("notif.status", (ctx, p) -> {
            boolean access = false;
            try {
                access = Settings.Secure.getString(ctx.getContentResolver(),
                        "enabled_notification_listeners") != null
                        && Settings.Secure.getString(ctx.getContentResolver(),
                        "enabled_notification_listeners").contains(ctx.getPackageName());
            } catch (Throwable ignored) {}
            return Dispatcher.ok()
                    .put("connected", BridgeNotifListener.connected)
                    .put("access", access);
        });

        r.put("notif.recent", (ctx, p) ->
                Dispatcher.okPut("notifications", BridgeNotifListener.recent(p.optInt("limit", 20))));

        r.put("notif.clear", (ctx, p) -> {
            BridgeNotifListener.clear();
            return Dispatcher.ok();
        });

        // 关闭某条通知(需要监听权限)
        r.put("notif.dismiss", (ctx, p) -> {
            String key = p.optString("key", "");
            if (key.isEmpty()) return Dispatcher.err("missing key");
            BridgeNotifListener svc = BridgeNotifListener.INSTANCE;
            if (svc == null) return Dispatcher.err("listener not connected (grant notification access first)");
            try { svc.cancelNotification(key); return Dispatcher.ok(); }
            catch (Throwable t) { return Dispatcher.err("cannot dismiss: " + t.getClass().getSimpleName()); }
        });

        r.put("notif.perm", (ctx, p) -> {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); return Dispatcher.ok(); }
            catch (Exception e) { return Dispatcher.err(e.getClass().getSimpleName()); }
        });
    }
}
