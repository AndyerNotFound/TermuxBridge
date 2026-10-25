package lll.bridge.termux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

final class Util {
    private Util() {}

    /** 颜色解析: 允许省略 '#' */
    static int color(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        if (!s.startsWith("#")) s = "#" + s;
        try { return android.graphics.Color.parseColor(s); }
        catch (Exception e) { return def; }
    }

    /** 在主线程执行并等待结果(最多 timeout 毫秒) */
    static boolean mainSync(Context ctx, java.util.function.Supplier<Boolean> task, long timeoutMs) {
        final boolean[] out = {false};
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        new Handler(ctx.getMainLooper()).post(() -> {
            try { out[0] = Boolean.TRUE.equals(task.get()); }
            catch (Throwable ignored) {}
            finally { l.countDown(); }
        });
        try { l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) {}
        return out[0];
    }

    static void main(Context ctx, Runnable r) {
        new Handler(ctx.getMainLooper()).post(r);
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
