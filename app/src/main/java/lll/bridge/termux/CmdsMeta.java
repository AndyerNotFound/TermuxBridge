package lll.bridge.termux;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/** 元命令: 连通性/版本/功能开关查询与控制。 */
final class CmdsMeta {
    private CmdsMeta() {}

    private static String versionName(android.content.Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) { return "?"; }
    }

    private static String modeName(int m) {
        return m == BridgeA11yService.M_NONE ? "none"
                : m == BridgeA11yService.M_BOTH ? "both"
                : m == BridgeA11yService.M_CONTENT ? "content" : "window";
    }

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("ping", (ctx, p) -> Dispatcher.ok()
                .put("version", versionName(ctx))
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("port", Prefs.port(ctx)));

        r.put("features", (ctx, p) -> {
            JSONObject o = Dispatcher.ok();
            for (String f : Prefs.FEATURES) o.put(f, Prefs.enabled(ctx, f));
            return o;
        });

        r.put("acc.events", (ctx, p) -> {
            String m2 = p.optString("mode", "");
            if (!m2.isEmpty()) {
                int mode = "none".equals(m2) ? BridgeA11yService.M_NONE
                        : "content".equals(m2) ? BridgeA11yService.M_CONTENT
                        : "both".equals(m2) ? BridgeA11yService.M_BOTH
                        : BridgeA11yService.M_WINDOW;
                BridgeA11yService.applyMode(mode);
            }
            return Dispatcher.ok()
                    .put("mode", modeName(BridgeA11yService.mode()))
                    .put("enabled", BridgeA11yService.ready());
        });

        r.put("acc.stats", (ctx, p) -> {
            long ms = android.os.SystemClock.uptimeMillis() - BridgeA11yService.EV_T0;
            return Dispatcher.ok()
                    .put("mode", modeName(BridgeA11yService.mode()))
                    .put("uptime_ms", ms)
                    .put("window", BridgeA11yService.EV_WIN.get())
                    .put("content", BridgeA11yService.EV_CONTENT.get())
                    .put("other", BridgeA11yService.EV_OTHER.get())
                    .put("window_per_min", ms > 0 ? Math.round(BridgeA11yService.EV_WIN.get() * 60000.0 / ms) : 0)
                    .put("content_per_min", ms > 0 ? Math.round(BridgeA11yService.EV_CONTENT.get() * 60000.0 / ms) : 0);
        });

        r.put("feature", (ctx, p) -> {
            String f = p.optString("name", "");
            if (f.isEmpty()) return Dispatcher.err("missing name");
            boolean on = p.optBoolean("enable", true);
            Prefs.setEnabled(ctx, f, on);
            return Dispatcher.okPut(f, on);
        });

        r.put("proto", (ctx, p) -> {
            JSONArray cmds = new JSONArray();
            for (String c : new String[]{
                    "ping", "features", "feature", "toast", "notify", "vibrate", "beep",
                    "clip.read", "clip.write", "battery", "net", "bright", "volume", "torch",
                    "location", "sensor.list", "sensor.read", "tts", "record",
                    "share", "open", "dial", "sms", "shot.poll", "stt.poll"}) cmds.put(c);
            return Dispatcher.okPut("commands", cmds);
        });
    }
}
