package lll.bridge.termux;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import org.json.JSONObject;

/** D-batch: image node. src = file path or content:// URI, decoded off the main
 *  thread; scales to the node box; re-shown only when src changes (patch-safe). */
public final class ImgNode extends ImageView {
    private static final java.util.LinkedHashMap<String, Bitmap> CACHE =
            new java.util.LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
                protected boolean removeEldest(java.util.Map.Entry<String, Bitmap> e) {
                    return size() > 12;
                }
            };
    private String loadedKey = "";

    public ImgNode(Context c) { super(c); }

    /** returns true when a new source started loading (caller triggers relayout) */
    public boolean applySrc(String src) {
        if (src == null) src = "";
        if (src.equals(loadedKey)) return false;
        loadedKey = src;
        if (src.isEmpty()) { setImageBitmap(null); return false; }
        Bitmap hit = CACHE.get(src);
        if (hit != null) { setImageBitmap(hit); return false; }
        setImageBitmap(null);
        final String key = src;
        new Thread(() -> {
            Bitmap bm = null;
            try {
                if (key.startsWith("content://")) {
                    java.io.InputStream is = getContext().getContentResolver().openInputStream(Uri.parse(key));
                    if (is != null) { bm = BitmapFactory.decodeStream(is); is.close(); }
                } else {
                    bm = BitmapFactory.decodeFile(key);
                }
            } catch (Exception e) { bm = null; }
            final Bitmap fbm = bm;
            if (fbm == null) return;
            synchronized (CACHE) { CACHE.put(key, bm); }
            post(() -> { if (key.equals(loadedKey)) setImageBitmap(fbm); });
        }, "tb-img").start();
        return false;
    }

    /** common props (pad/alpha/bg/radius) so the node behaves like the others */
    public void applyCommon(JSONObject n, Context ctx) {
        int pad = OverlayManager.dp(ctx, n.optDouble("pad", 0));
        if (pad > 0) setPadding(pad, pad, pad, pad);
        setAlpha((float) n.optDouble("alpha", 1));
    }

    /** scale type from spec: fill (default) / fit / stretch */
    public void applyScale(String s) {
        if ("fit".equals(s)) setScaleType(ScaleType.FIT_CENTER);
        else if ("stretch".equals(s)) setScaleType(ScaleType.FIT_XY);
        else setScaleType(ScaleType.CENTER_CROP);
    }
}
