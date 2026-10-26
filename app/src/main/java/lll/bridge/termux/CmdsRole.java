package lll.bridge.termux;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统角色(默认电话/短信/桌面等) */
final class CmdsRole {
    private CmdsRole() {}

    private static Map<String, String> roles() {
        Map<String, String> m = new LinkedHashMap<>();
        if (Build.VERSION.SDK_INT < 29) return m;
        m.put("dialer", RoleManager.ROLE_DIALER);
        m.put("sms", RoleManager.ROLE_SMS);
        m.put("home", RoleManager.ROLE_HOME);
        m.put("browser", RoleManager.ROLE_BROWSER);
        m.put("assistant", RoleManager.ROLE_ASSISTANT);
        m.put("call_screening", RoleManager.ROLE_CALL_SCREENING);
        m.put("emergency", RoleManager.ROLE_EMERGENCY);
        return m;
    }

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("role.list", (ctx, p) -> {
            if (Build.VERSION.SDK_INT < 29) return Dispatcher.err("requires Android 10+");
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : roles().entrySet()) {
                arr.put(new JSONObject()
                        .put("key", e.getKey())
                        .put("available", rm.isRoleAvailable(e.getValue()))
                        .put("held", rm.isRoleHeld(e.getValue())));
            }
            return Dispatcher.okPut("roles", arr);
        });

        r.put("role.request", (ctx, p) -> {
            if (Build.VERSION.SDK_INT < 29) return Dispatcher.err("requires Android 10+");
            String key = p.optString("role", "");
            String role = roles().get(key);
            if (role == null) return Dispatcher.err("unknown role: " + key);
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            if (!rm.isRoleAvailable(role)) return Dispatcher.err("role not supported by the system");
            if (rm.isRoleHeld(role)) return Dispatcher.ok().put("already", true);
            // 后台直接 startActivity 会被系统拦截: 先拉起自己的 Activity, 由前台发起请求
            Intent i = new Intent(ctx, MainActivity.class)
                    .putExtra("role", key)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                ctx.startActivity(i);
                return Dispatcher.ok().put("requested", true)
                        .put("note", "foreground activity started with the role request; becoming the default dialer/SMS app additionally requires that app's UI and permissions");
            } catch (Exception e) {
                return Dispatcher.err("cannot show dialog: " + e.getClass().getSimpleName());
            }
        });
    }
}
