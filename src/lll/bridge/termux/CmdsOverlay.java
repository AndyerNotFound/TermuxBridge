package lll.bridge.termux;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 悬浮窗命令: show / update / hide / events / values / perm / list */
final class CmdsOverlay {
    private CmdsOverlay() {}

    /** 所有 View 操作必须在主线程执行 */
    private static JSONObject onMain(Context ctx, Callable<JSONObject> c) throws Exception {
        final Object[] box = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Util.main(ctx, () -> {
            try { box[0] = c.call(); }
            catch (Exception e) { box[0] = Dispatcher.err(e.getClass().getSimpleName() + ": " + e.getMessage()); }
            finally { latch.countDown(); }
        });
        if (!latch.await(6, TimeUnit.SECONDS)) return Dispatcher.err("overlay timeout");
        return box[0] instanceof JSONObject ? (JSONObject) box[0] : Dispatcher.err("overlay failed");
    }

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("overlay.show", (ctx, p) -> {
            if (!OverlayManager.granted(ctx)) return Dispatcher.err(
                    "需要悬浮窗权限: 设置 > 特殊应用权限 > 显示在其他应用上层");
            return onMain(ctx, () -> OverlayManager.show(ctx, p));
        });

        r.put("overlay.update", (ctx, p) -> {
            if (!OverlayManager.granted(ctx)) return Dispatcher.err("需要悬浮窗权限");
            return onMain(ctx, () -> OverlayManager.update(ctx, p));
        });

        r.put("overlay.hide", (ctx, p) -> {
            final String id = p.optString("id", "");
            return onMain(ctx, () -> {
                if (id.isEmpty() || "all".equals(id)) OverlayManager.removeAll(ctx);
                else OverlayManager.remove(ctx, id);
                return Dispatcher.ok();
            });
        });

        r.put("overlay.events", (ctx, p) -> Dispatcher.okPut("events", OverlayManager.drain()));

        r.put("overlay.values", (ctx, p) -> Dispatcher.okPut("values",
                OverlayManager.values(p.optString("id", ""))));

        r.put("overlay.list", (ctx, p) -> Dispatcher.okPut("windows", OverlayManager.ids()));

        r.put("overlay.bounds", (ctx, p) ->
                onMain(ctx, () -> Dispatcher.okPut("windows", OverlayManager.bounds())));

        r.put("overlay.tree", (ctx, p) ->
                onMain(ctx, () -> Dispatcher.okPut("nodes", OverlayManager.tree(p.optString("id", "")))));

        r.put("overlay.perm", (ctx, p) -> {
            boolean ok = OverlayManager.granted(ctx);
            if (!ok && p.optBoolean("request", false)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
            return Dispatcher.okPut("granted", ok)
                    .put("hint", "设置 > 特殊应用权限 > 显示在其他应用上层");
        });
    }
}
