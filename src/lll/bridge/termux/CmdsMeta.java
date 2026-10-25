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
