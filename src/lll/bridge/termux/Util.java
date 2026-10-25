package lll.bridge.termux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

final class Util {
    private Util() {}

    static void main(Context ctx, Runnable r) {
        new Handler(ctx.getMainLooper()).post(r);
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
