package lll.bridge.termux;

import android.content.Context;

import java.util.Map;

/** 桌面组件: 刷新 / 查询 */
final class CmdsWidget {
    private CmdsWidget() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("widget.update", (ctx, p) -> {
            BridgeWidgetProvider.updateAll(ctx);
            return Dispatcher.ok().put("instances", BridgeWidgetProvider.ids(ctx).length);
        });
        r.put("widget.pin", (ctx, p) -> {
            if (android.os.Build.VERSION.SDK_INT < 26) return Dispatcher.err("requires Android 8+");
            android.appwidget.AppWidgetManager mgr = android.appwidget.AppWidgetManager.getInstance(ctx);
            if (!mgr.isRequestPinAppWidgetSupported()) return Dispatcher.err("launcher does not support pinning widgets");
            boolean ok = mgr.requestPinAppWidget(
                    new android.content.ComponentName(ctx, BridgeWidgetProvider.class), null, null);
            return Dispatcher.ok().put("requested", ok);
        });

        r.put("widget.info", (ctx, p) ->
                Dispatcher.ok().put("instances", BridgeWidgetProvider.ids(ctx).length)
                        .put("layout", "widget"));
    }
}
