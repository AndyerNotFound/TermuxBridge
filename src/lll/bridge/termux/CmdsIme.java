package lll.bridge.termux;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;

import java.util.Map;

import org.json.JSONObject;

/** 输入法: 状态 / 注入文本 / 唤起 */
final class CmdsIme {
    private CmdsIme() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("ime.status", (ctx, p) -> BridgeImeService.status(ctx));

        // rime engine passthrough (engine runs in Termux: tbridge-rime start)
        r.put("ime.rime", (ctx, p) -> {
            String op = p.optString("op", "status");
            if (op.equals("status"))
                return Dispatcher.ok().put("engine", RimeBridge.available() ? "up" : "down");
            try {
                JSONObject q = new JSONObject().put("op", op);
                for (String k : new String[]{"key", "mask", "index", "dir", "value"})
                    if (p.has(k)) q.put(k, p.get(k));
                for (String k : new String[]{"name", "id"})
                    if (p.has(k)) q.put(k, p.getString(k));
                org.json.JSONObject rsp = RimeBridge.request(q);
                if (!rsp.optBoolean("ok")) return Dispatcher.err(rsp.optString("error", "rime error"));
                return Dispatcher.ok().put("rime", rsp);
            } catch (Exception e) {
                return Dispatcher.err("rime daemon unreachable (start it in Termux: tbridge-rime start)");
            }
        });

        r.put("ime.commit", (ctx, p) -> {
            String text = p.optString("text", "");
            if (text.isEmpty()) return Dispatcher.err("missing text");
            boolean ok = Util.mainSync(ctx, () -> BridgeImeService.commitText(text), 3000);
            return ok ? Dispatcher.ok().put("committed", text.length())
                      : Dispatcher.err("IME not connected (select the TermuxBridge IME and focus an input field first)");
        });

        r.put("ime.show", (ctx, p) -> {
            Util.main(ctx, () -> {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                BridgeImeService s = BridgeImeService.INSTANCE;
                if (s != null) imm.showSoftInput(s.getWindow().getWindow().getDecorView(), 0);
            });
            return Dispatcher.ok();
        });

        r.put("ime.hide", (ctx, p) -> {
            Util.main(ctx, () -> {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                BridgeImeService s = BridgeImeService.INSTANCE;
                if (s != null) imm.hideSoftInputFromWindow(s.getWindow().getWindow().getDecorView().getWindowToken(), 0);
            });
            return Dispatcher.ok();
        });

        // 打开输入法选择器 / 设置页
        r.put("ime.pick", (ctx, p) -> {
            Intent i;
            if (p.optBoolean("settings", false)) {
                i = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            } else {
                i = new Intent(Intent.ACTION_MAIN);
                i.setClassName("com.android.settings", "com.android.settings.Settings$InputMethodAndLanguageSettingsActivity");
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); return Dispatcher.ok(); }
            catch (Exception e) { return Dispatcher.err(e.getClass().getSimpleName()); }
        });
    }
}
