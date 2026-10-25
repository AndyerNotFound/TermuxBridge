package lll.bridge.termux;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * JSON 命令分发: {"cmd":"...", ...params}
 * 返回 {"ok":true,...} 或 {"ok":false,"error":"..."} */
public final class Dispatcher {
    public interface Cmd { JSONObject run(Context ctx, JSONObject p) throws Exception; }
    private static final Map<String, Cmd> REG = new HashMap<>();

    static {
        CmdsMeta.register(REG);
        CmdsOverlay.register(REG);
        CmdsCore.register(REG);
        CmdsSensors.register(REG);
        CmdsVoice.register(REG);
        CmdsMedia.register(REG);
        CmdsInteract.register(REG);
    }

    private Dispatcher() {}

    public static String dispatch(Context ctx, String json, boolean fromBroadcast) {
        JSONObject r;
        try {
            JSONObject p = new JSONObject(json);
            // token 校验(广播通道必须, TCP 可选)
            String tk = Prefs.token(ctx);
            String got = p.optString("token", "");
            if (!tk.isEmpty() && !tk.equals(got)) {
                r = err("invalid token");
            } else {
                String cmd = p.getString("cmd");
                r = dispatchCmd(ctx, cmd, p, fromBroadcast);
            }
        } catch (org.json.JSONException je) {
            r = err("bad json: " + je.getMessage());
        } catch (Exception e) {
            r = err(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return r.toString();
    }

    public static JSONObject dispatchCmd(Context ctx, String cmd, JSONObject p, boolean fromBroadcast) {
        try {
            Cmd c = REG.get(cmd);
            if (c == null) return err("unknown cmd: " + cmd);
            // 功能开关(命令→模块 key 归一化)
            String fk = featureKey(cmd);
            if (fk != null && !Prefs.enabled(ctx, fk)) return err("feature disabled: " + fk);
            // 广播通道只允许无 UI 依赖的命令
            if (fromBroadcast && UI_CMDS.contains(cmd))
                return err("cmd '" + cmd + "' requires TCP channel (needs UI/poll), use tcp");
            return c.run(ctx, p);
        } catch (Exception e) {
            return err(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 命令 → 模块开关 key 归一化 */
    static String featureKey(String cmd) {
        if (cmd.startsWith("sensor.")) return "sensors";
        if (cmd.startsWith("clip.")) return "clipboard";
        if (cmd.startsWith("stt.")) return "stt";
        if (cmd.startsWith("shot.")) return "screenshot";
        if ("record".equals(cmd)) return "stt";
        if ("tap".equals(cmd) || "swipe".equals(cmd) || "ui".equals(cmd) || "a11y".equals(cmd)) return "touch";
        if ("share".equals(cmd) || "open".equals(cmd) || "dial".equals(cmd) || "sms".equals(cmd)) return "share";
        return cmd;
    }

    /** 需要界面焦点/授权弹窗 → 必须走 TCP 轮询模式的命令 */
    public static final java.util.Set<String> UI_CMDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "clip.read", "shot.poll", "stt.poll", "record"));

    public static JSONObject ok() { try { return new JSONObject().put("ok", true); }
        catch (Exception e) { return new JSONObject(); } }
    public static JSONObject okPut(String k, Object v) {
        try { return new JSONObject().put("ok", true).put(k, v); }
        catch (Exception e) { return new JSONObject(); }
    }
    public static JSONObject err(String m) {
        try { JSONObject o = new JSONObject(); o.put("ok", false); o.put("error", m); return o; }
        catch (Exception e) { return new JSONObject(); }
    }
}
