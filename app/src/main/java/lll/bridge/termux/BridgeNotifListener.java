package lll.bridge.termux;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 通知监听: 缓存最近的通知, 供 notif.recent 读取 */
public class BridgeNotifListener extends NotificationListenerService {
    private static final int CAP = 80;
    private static final ArrayDeque<JSONObject> RECENT = new ArrayDeque<>();
    static volatile boolean connected = false;
    static volatile BridgeNotifListener INSTANCE = null;

    @Override public void onListenerConnected() { connected = true; INSTANCE = this; }

    @Override public void onListenerDisconnected() { connected = false; INSTANCE = null; }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        JSONObject o = toJson(sbn);
        if (o == null) return;
        synchronized (RECENT) {
            // 同 key 覆盖
            for (java.util.Iterator<JSONObject> it = RECENT.iterator(); it.hasNext(); ) {
                if (o.optString("key").equals(it.next().optString("key"))) it.remove();
            }
            RECENT.addFirst(o);
            while (RECENT.size() > CAP) RECENT.removeLast();
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        String key = sbn.getKey();
        synchronized (RECENT) {
            for (java.util.Iterator<JSONObject> it = RECENT.iterator(); it.hasNext(); ) {
                if (key.equals(it.next().optString("key"))) it.remove();
            }
        }
    }

    private static JSONObject toJson(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) return null;
            Bundle ex = n.extras;
            JSONObject o = new JSONObject();
            o.put("key", sbn.getKey());
            o.put("pkg", sbn.getPackageName());
            o.put("id", sbn.getId());
            o.put("time", sbn.getPostTime());
            o.put("ongoing", (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            o.put("clearable", sbn.isClearable());
            o.put("channel", n.getChannelId());
            o.put("category", n.category == null ? "" : n.category);
            o.put("title", str(ex.getCharSequence(Notification.EXTRA_TITLE)));
            o.put("text", str(ex.getCharSequence(Notification.EXTRA_TEXT)));
            o.put("bigtext", str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)));
            if (ex.containsKey(Notification.EXTRA_PROGRESS_MAX)) {
                o.put("progress", ex.getInt(Notification.EXTRA_PROGRESS, 0));
                o.put("progress_max", ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0));
                o.put("progress_indeterminate", ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false));
            }
            JSONArray acts = new JSONArray();
            if (n.actions != null) for (Notification.Action a : n.actions)
                acts.put(str(a.title));
            o.put("actions", acts);
            return o;
        } catch (Exception e) { return null; }
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString(); }

    static JSONArray recent(int limit) {
        JSONArray a = new JSONArray();
        synchronized (RECENT) {
            int i = 0;
            for (JSONObject o : RECENT) { if (i++ >= limit) break; a.put(o); }
        }
        return a;
    }

    static void clear() { synchronized (RECENT) { RECENT.clear(); } }

    static List<String> keys() {
        List<String> l = new ArrayList<>();
        synchronized (RECENT) { for (JSONObject o : RECENT) l.add(o.optString("key")); }
        return l;
    }
}
