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

    static void main(Context ctx, Runnable r) {
        new Handler(ctx.getMainLooper()).post(r);
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
