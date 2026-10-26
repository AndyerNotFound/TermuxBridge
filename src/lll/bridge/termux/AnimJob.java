package lll.bridge.termux;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONObject;

/** D-batch: in-bridge window animation. One command starts a frame loop on the
 *  bridge side (Choreographer-driven), replacing per-frame IPC from Termux.
 *  Params: {id, to:{x,y,w,h(dp ok),radius,alpha}, ms=250, ease:"out"|"linear"|"in"}
 *  Coordinates: x/y are window px offsets (like overlay.update), w/h dp or px
 *  follows the same dim() rules as overlay nodes. */
public final class AnimJob {
    private static final java.util.Map<String, AnimJob> LIVE = new java.util.LinkedHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final OverlayManager.Entry e;
    private final JSONObject to;
    private final long ms;
    private final int easeMode;          // 0 linear, 1 out(quad), 2 in(quad)
    private final float x0, y0, w0, h0, a0, r0;
    private final boolean mx, my, mw, mh, ma, mr;
    private long t0;
    private boolean cancelled = false;

    private AnimJob(OverlayManager.Entry e, JSONObject to, long ms, String ease) {
        this.e = e; this.to = to; this.ms = Math.max(0, ms);
        this.easeMode = "linear".equals(ease) ? 0 : "in".equals(ease) ? 2 : 1;
        WindowManager.LayoutParams lp = e.lp;
        this.x0 = lp.x; this.y0 = lp.y;
        this.w0 = lp.width; this.h0 = lp.height;
        this.a0 = e.root.getAlpha();
        this.r0 = -1f;                    // radius read from background on first frame
        this.mx = to.has("x"); this.my = to.has("y");
        this.mw = to.has("w"); this.mh = to.has("h");
        this.ma = to.has("alpha");
        this.mr = to.has("radius");
    }

    public static boolean start(OverlayManager.Entry e, JSONObject p) {
        JSONObject to = p.optJSONObject("to");
        if (to == null) return false;
        String id = p.optString("id", "");
        AnimJob prev = LIVE.get(id);
        if (prev != null) prev.cancel();
        final AnimJob job = new AnimJob(e, to, p.optLong("ms", 250), p.optString("ease", "out"));
        LIVE.put(id, job);
        job.t0 = SystemClock.uptimeMillis();
        // Choreographer + view mutation need the main looper; commands arrive on
        // a TCP dispatcher thread, so the whole loop runs posted on MAIN
        MAIN.post(job::tick);
        return true;
    }

    private void tick() {
        if (cancelled) return;
        Choreographer.getInstance().postFrameCallback(frameTime -> {
            if (cancelled) return;
            long dt = SystemClock.uptimeMillis() - t0;
            float k = ms <= 0 ? 1f : Math.min(1f, dt / (float) ms);
            frame(k);
            if (k < 1f) tick();
            else LIVE.remove(e.layout == null ? "" : windowId());
        });
    }

    private String windowId() {
        for (java.util.Map.Entry<String, OverlayManager.Entry> en : OverlayManager.allEntries().entrySet())
            if (en.getValue() == e) return en.getKey();
        return "";
    }

    private float ease(float k) {
        switch (easeMode) {
            case 0: return k;
            case 2: return k * k;
            default: return 1f - (1f - k) * (1f - k);
        }
    }

    private void frame(float kin) {
        final float k = ease(kin);
        final WindowManager.LayoutParams lp = e.lp;
        final float dens = e.root.getResources().getDisplayMetrics().density;
        // protocol units are dp (same as overlay.show/update); lp wants px
        if (mw) lp.width  = Math.max(1, Math.round(lerp(w0, dimToPx(to.optDouble("w", 0), dens, w0), k)));
        if (mh) lp.height = Math.max(1, Math.round(lerp(h0, dimToPx(to.optDouble("h", 0), dens, h0), k)));
        if (mx) lp.x = Math.round(lerp(x0, dpToPx(to.optDouble("x", x0 / dens), dens), k));
        if (my) lp.y = Math.round(lerp(y0, dpToPx(to.optDouble("y", y0 / dens), dens), k));
        // alpha/radius are view properties — only x/y/w/h need the (expensive,
        // cross-process) window relayout transaction; sending one per frame for
        // a pure fade starves it down to 2-3 visible frames on a 60Hz panel
        if (mx || my || mw || mh) {
            try { e.wm.updateViewLayout(e.root, lp); } catch (Exception ignored) {}
        }
        if (ma) e.root.setAlpha(lerp(a0, (float) to.optDouble("alpha", a0), k));
        if (mr) OverlayManager.setWindowRadius(e.root, lerp(r0 < 0 ? currentRadius(e.root) : r0,
                dpToPx(to.optDouble("radius", 0), dens), k));
    }

    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }
    private static float dpToPx(double dp, float dens) { return (float) (dp * dens); }
    private static float dimToPx(double v, float dens, float fallback) {
        if (v <= 0) return fallback;
        return (float) (v * dens);
    }
    static float toPx(double dp, float dens) { return (float) (dp * dens); }
    private static float currentRadius(View root) {
        android.graphics.drawable.Drawable bg = root.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable)
            return ((android.graphics.drawable.GradientDrawable) bg).getCornerRadius();
        return 0f;
    }

    public void cancel() { cancelled = true; }
    public static void cancelAll() { for (AnimJob j : LIVE.values()) j.cancel(); LIVE.clear(); }
}
