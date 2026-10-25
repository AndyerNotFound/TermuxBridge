package lll.bridge.termux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/** 通知: 渠道管理 / 进度 / Live Update(Android 16) / 动作按钮 */
final class CmdsNotify {
    private CmdsNotify() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("notify", CmdsNotify::post);

        r.put("notify.cancel", (ctx, p) -> {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (p.optBoolean("all", false)) { nm.cancelAll(); return Dispatcher.ok(); }
            int id = p.optInt("id", -1);
            if (id < 0) return Dispatcher.err("missing id");
            nm.cancel(id);
            return Dispatcher.ok();
        });

        r.put("notify.channels", (ctx, p) -> {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            JSONArray arr = new JSONArray();
            for (NotificationChannel c : nm.getNotificationChannels()) {
                arr.put(new JSONObject()
                        .put("id", c.getId())
                        .put("name", String.valueOf(c.getName()))
                        .put("importance", c.getImportance())
                        .put("sound", c.getSound() != null)
                        .put("vibration", c.shouldVibrate())
                        .put("description", c.getDescription() == null ? "" : c.getDescription().toString()));
            }
            return Dispatcher.okPut("channels", arr);
        });

        r.put("notify.channel", (ctx, p) -> {
            String id = p.optString("id", "");
            if (id.isEmpty()) return Dispatcher.err("missing id");
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            boolean existed = nm.getNotificationChannel(id) != null;
            NotificationChannel c = new NotificationChannel(id,
                    p.optString("name", "Bridge:" + id),
                    clamp(p.optInt("importance", NotificationManager.IMPORTANCE_DEFAULT)));
            if (p.has("description")) c.setDescription(p.optString("description"));
            if (p.has("sound")) {
                if (p.optBoolean("sound", true))
                    c.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                            new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());
                else c.setSound(null, null);
            }
            if (p.has("vibration")) {
                if (p.optBoolean("vibration", true)) c.enableVibration(true);
                else c.enableVibration(false);
            }
            nm.createNotificationChannel(c);
            return Dispatcher.ok().put("id", id).put("created", !existed);
        });
    }

    private static int clamp(int imp) {
        if (imp < NotificationManager.IMPORTANCE_MIN) imp = NotificationManager.IMPORTANCE_MIN;
        if (imp > NotificationManager.IMPORTANCE_MAX) imp = NotificationManager.IMPORTANCE_MAX;
        return imp;
    }

    private static JSONObject post(Context ctx, JSONObject p) throws Exception {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        String chId = p.optString("channel", "bridge");
        boolean created = false;
        if (nm.getNotificationChannel(chId) == null) {
            NotificationChannel c = new NotificationChannel(chId,
                    p.optString("channel_name", "Bridge"),
                    clamp(p.optInt("importance", NotificationManager.IMPORTANCE_DEFAULT)));
            if (p.has("channel_desc")) c.setDescription(p.optString("channel_desc"));
            nm.createNotificationChannel(c);
            created = true;
        }

        Notification.Builder b = new Notification.Builder(ctx, chId)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(p.optString("title", "TermuxBridge"))
                .setContentText(p.optString("text", ""))
                .setAutoCancel(!p.optBoolean("ongoing", false))
                .setOngoing(p.optBoolean("ongoing", false));

        if (p.has("bigtext")) b.setStyle(new Notification.BigTextStyle().bigText(p.optString("bigtext")));
        if (p.has("color")) b.setColor(Util.color(p.optString("color"), 0));
        if (p.has("timeout")) b.setTimeoutAfter(p.optLong("timeout"));
        if (p.optBoolean("onlyalertonce", false)) b.setOnlyAlertOnce(true);

        boolean live = false;
        JSONObject prog = p.optJSONObject("progress");
        if (prog != null) {
            int value = prog.optInt("value", 0);
            int max = Math.max(1, prog.optInt("max", 100));
            boolean indet = prog.optBoolean("indeterminate", false);
            b.setProgress(max, value, indet);
            if (Build.VERSION.SDK_INT >= 36 && prog.optBoolean("live", false)) {
                Notification.ProgressStyle ps = new Notification.ProgressStyle()
                        .setProgress(value)
                        .setProgressIndeterminate(indet)
                        .setStyledByProgress(prog.optBoolean("styled", false));
                JSONArray segs = prog.optJSONArray("segments");
                if (segs != null) for (int i = 0; i < segs.length(); i++) {
                    String col = segs.optString(i, "");
                    if (!col.isEmpty()) ps.addProgressSegment(
                            new Notification.ProgressStyle.Segment(Util.color(col, 0xFF6750A4)));
                }
                b.setStyle(ps);
                b.setCategory(p.optString("category", Notification.CATEGORY_PROGRESS));
                // 推广为 Live Update: 反射调用, 老版本构建无此方法
                try {
                    Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class)
                            .invoke(b, true);
                    live = true;
                } catch (Throwable ignored) { live = false; }
            }
        }

        JSONArray acts = p.optJSONArray("actions");
        if (acts != null) for (int i = 0; i < acts.length(); i++) {
            JSONObject a = acts.optJSONObject(i);
            if (a == null) continue;
            Intent bi = new Intent(ctx, BridgeReceiver.class)
                    .setAction(BridgeReceiver.ACTION_RUN)
                    .putExtra("json", a.optString("json", "{\"cmd\":\"ping\"}"));
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 2000 + i, bi,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_send),
                    a.optString("label", "OK"), pi).build());
        }

        int id = p.optInt("id", (int) (System.currentTimeMillis() & 0x7fffffff));
        nm.notify(id, b.build());

        JSONObject res = Dispatcher.ok().put("id", id).put("channel_created", created).put("live", live);
        if (Build.VERSION.SDK_INT >= 36) res.put("can_promote", nm.canPostPromotedNotifications());
        return res;
    }
}
