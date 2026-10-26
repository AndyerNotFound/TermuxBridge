package lll.bridge.termux;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 桌面小组件: 显示电量/端口/刷新时间, 点击刷新 */
public class BridgeWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "lll.bridge.termux.WIDGET_REFRESH";

    @Override public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews rv = build(ctx);
        for (int id : ids) mgr.updateAppWidget(id, rv);
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) updateAll(ctx);
    }

    static int[] ids(Context ctx) {
        return AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(new ComponentName(ctx, BridgeWidgetProvider.class));
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        RemoteViews rv = build(ctx);
        for (int id : ids(ctx)) mgr.updateAppWidget(id, rv);
    }

    private static RemoteViews build(Context ctx) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        int pct = -1;
        try {
            BatteryManager bm = ctx.getSystemService(BatteryManager.class);
            pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Throwable ignored) {}

        String body = (pct >= 0 ? pct + "%" : "—")
                + " · @" + BridgeService.SOCKET_NAME
                + "\n" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        rv.setTextViewText(R.id.w_body, body);

        Intent i = new Intent(ctx, BridgeWidgetProvider.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.w_body, pi);
        rv.setOnClickPendingIntent(R.id.w_hint, pi);
        return rv;
    }
}
