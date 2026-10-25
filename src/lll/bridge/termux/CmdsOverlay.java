package lll.bridge.termux;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
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

    private static double pct(int p) {
        long[] a = new long[128];
        int n = 0;
        for (int i = 0; i < 128; i++) if (OverlayManager.FRAME_GAPS[i] > 0) a[n++] = OverlayManager.FRAME_GAPS[i];
        if (n == 0) return 0;
        java.util.Arrays.sort(a, 0, n);
        return a[Math.min(n - 1, n * p / 100)] / 1e6;
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

        r.put("overlay.events", (ctx, p) -> {
            // 兼容旧行为: 不传参 = 读后清空; 传 since 则保留(可重复读)
            boolean clear = p.has("clear") ? p.optBoolean("clear", true) : !p.has("since");
            JSONArray ev = OverlayManager.read(p.optLong("since", 0), clear);
            return Dispatcher.okPut("events", ev).put("last_seq", OverlayManager.lastSeq());
        });

        r.put("overlay.values", (ctx, p) -> Dispatcher.okPut("values",
                OverlayManager.values(p.optString("id", ""))));

        r.put("overlay.list", (ctx, p) -> Dispatcher.okPut("windows", OverlayManager.ids()));

        r.put("overlay.bounds", (ctx, p) ->
                onMain(ctx, () -> Dispatcher.okPut("windows", OverlayManager.bounds())));

        r.put("overlay.tree", (ctx, p) ->
                onMain(ctx, () -> Dispatcher.okPut("nodes", OverlayManager.tree(p.optString("id", "")))));

        r.put("overlay.stats", (ctx, p) -> {
            long ms = android.os.SystemClock.uptimeMillis() - OverlayManager.STAT_T0;
            long c = OverlayManager.COMMITS.get(), m = OverlayManager.MOVES.get();
            return Dispatcher.ok()
                    .put("uptime_ms", ms)
                    .put("moves", m)
                    .put("commits", c)
                    .put("moves_per_s", ms > 0 ? Math.round(m * 1000.0 / ms) : 0)
                    .put("commits_per_s", ms > 0 ? Math.round(c * 1000.0 / ms) : 0)
                    .put("avg_commit_ms", c > 0 ? OverlayManager.COMMIT_NS.get() / 1e6 / c : 0)
                    .put("max_commit_ms", OverlayManager.MAX_NS.get() / 1e6)
                    .put("frame_gap_p50_ms", pct(50))
                    .put("frame_gap_p95_ms", pct(95))
                    .put("fps_est", pct(50) > 0 ? Math.round(1000 / pct(50)) : 0);
        });

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
