package lll.bridge.termux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

/** D-batch: data-driven arc/ring gauge node (drawArc), for floating-ball gauges.
 *  Node spec: {"type":"ring", "segs":[{"id":"a","value":30,"color":"primary"},
 *  {"id":"b","value":20,"color":"#FF00FF00"}], "stroke":8(dp), "start":-90,
 *  "gap":4(deg between segments), "track":"#33888888", "max":100}
 *  In-place update: overlay.update with the same node re-reads segs. */
public final class RingNode extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final java.util.LinkedHashMap<String, float[]> segs = new java.util.LinkedHashMap<>();
    private int trackColor = 0x33888888;
    private float strokePx = 8f, startDeg = -90f, gapDeg = 0f;

    public RingNode(Context c) { super(c); }

    /** read spec from a node JSON; returns true when the drawing data changed */
    public boolean applySpec(JSONObject n, Context ctx) {
        java.util.LinkedHashMap<String, float[]> next = new java.util.LinkedHashMap<>();
        JSONArray arr = n.optJSONArray("segs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                float v = (float) s.optDouble("value", 0);
                int col = MdcTheme.resolve(ctx, s.optString("color", ""), 0xFFD0BCFF);
                next.put(s.optString("id", "s" + i), new float[]{v, col});
            }
        }
        float st = ctx.getResources().getDisplayMetrics().density
                * (float) n.optDouble("stroke", 8);
        float sd = (float) n.optDouble("start", -90);
        float gd = (float) n.optDouble("gap", 0);
        int tc = Util.color(n.optString("track", "#33888888"), 0x33888888);
        String key = specKey(next, st, sd, gd, tc);
        if (key.equals(lastKey)) return false;
        segs.clear(); segs.putAll(next);
        strokePx = st; startDeg = sd; gapDeg = gd; trackColor = tc;
        lastKey = key;
        return true;
    }

    private String lastKey = "";
    private static String specKey(java.util.LinkedHashMap<String, float[]> m,
                                  float st, float sd, float gd, int tc) {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, float[]> e : m.entrySet())
            b.append(e.getKey()).append(':').append(e.getValue()[0]).append(',')
             .append(Integer.toHexString((int) e.getValue()[1])).append(';');
        b.append(st).append(',').append(sd).append(',').append(gd).append(',').append(tc);
        return b.toString();
    }

    @Override protected void onDraw(Canvas c) {
        final float w = getWidth(), h = getHeight();
        final float half = Math.min(w, h) / 2f;
        if (half <= 0) return;
        box.set(w/2f-half, h/2f-half, w/2f+half, h/2f+half);
        box.inset(strokePx/2f, strokePx/2f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeWidth(strokePx);
        p.setColor(trackColor);
        c.drawArc(box, startDeg, 360f, false, p);
        float sum = 0f;
        for (float[] s : segs.values()) sum += s[0];
        final int nSeg = segs.size();
        float pos = 0f; int i = 0;
        for (java.util.Map.Entry<String, float[]> e : segs.entrySet()) {
            float frac = sum > 0 ? e.getValue()[0] / sum : 0f;
            float sweep = frac * (360f - gapDeg * Math.max(0, nSeg - 1));
            if (sweep >= 0.5f) {
                p.setColor((int) e.getValue()[1]);
                c.drawArc(box, startDeg + pos + gapDeg * i, sweep, false, p);
            }
            pos += sweep;
            i++;
        }
    }
}
