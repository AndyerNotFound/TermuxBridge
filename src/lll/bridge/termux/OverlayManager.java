package lll.bridge.termux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义悬浮窗: JSON 描述布局 → 动态构建 View 树 → WindowManager 叠加。
 * 节点类型: col/row/text/button/input/progress/spacer/divider
 * 事件(点击)入队, 由 overlay.events 轮询取走; 输入框内容由 overlay.values 读取。
 */
public final class OverlayManager {
    private static final Map<String, Entry> WINDOWS = new LinkedHashMap<>();
    private static final ArrayDeque<JSONObject> EVENTS = new ArrayDeque<>();

    private OverlayManager() {}

    static class Entry {
        View root;
        WindowManager.LayoutParams lp;
        JSONObject layout;
        Map<String, EditText> inputs = new LinkedHashMap<>();
    }

    static boolean granted(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static int dp(Context c, double v) {
        return Math.round((float) (v * c.getResources().getDisplayMetrics().density));
    }

    private static void pushEvent(JSONObject e) {
        synchronized (EVENTS) {
            EVENTS.addLast(e);
            while (EVENTS.size() > 200) EVENTS.removeFirst();
        }
    }

    static JSONArray drain() {
        JSONArray a = new JSONArray();
        synchronized (EVENTS) {
            while (!EVENTS.isEmpty()) a.put(EVENTS.removeFirst());
        }
        return a;
    }

    static JSONObject show(Context ctx, JSONObject req) throws Exception {
        if (!granted(ctx)) return Dispatcher.err("需要悬浮窗权限");
        final String id = req.optString("id", "w" + System.currentTimeMillis());
        Entry e = WINDOWS.get(id);
        if (e != null) remove(ctx, id);

        Entry entry = new Entry();
        View root = build(ctx, req.optJSONObject("view") != null
                ? req.optJSONObject("view") : req, id, entry);
        if (root == null) return Dispatcher.err("empty view");

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (req.optBoolean("focus", false)) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (!req.optBoolean("touch", true)) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.gravity = gravity(req.optString("gravity", "top|start"));
        lp.x = dp(ctx, req.optDouble("x", 0));
        lp.y = dp(ctx, req.optDouble("y", 80));
        lp.width = req.optInt("w", WindowManager.LayoutParams.WRAP_CONTENT);
        if (lp.width == WindowManager.LayoutParams.WRAP_CONTENT
                && !req.has("w") && hasFillNode(req.optJSONObject("view"))) {
            lp.width = dp(ctx, 300);   // 含 divider/spacer 的卡片: 默认 300dp
        }
        lp.height = req.optInt("h", WindowManager.LayoutParams.WRAP_CONTENT);

        entry.root = root;
        entry.lp = lp;
        entry.layout = req;
        wm.addView(root, lp);
        WINDOWS.put(id, entry);

        return Dispatcher.ok()
                .put("id", id)
                .put("w", root.getMeasuredWidth())
                .put("h", root.getMeasuredHeight());
    }

    static void remove(Context ctx, String id) {
        Entry e = WINDOWS.remove(id);
        if (e == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(e.root);
        } catch (Exception ignored) {}
    }

    static void removeAll(Context ctx) {
        for (String id : new java.util.ArrayList<>(WINDOWS.keySet())) remove(ctx, id);
    }

    static JSONObject values(String id) {
        Entry e = WINDOWS.get(id);
        JSONObject o = new JSONObject();
        if (e == null) return o;
        try {
            for (Map.Entry<String, EditText> kv : e.inputs.entrySet())
                o.put(kv.getKey(), kv.getValue().getText().toString());
        } catch (Exception ignored) {}
        return o;
    }

    static JSONArray ids() {
        JSONArray a = new JSONArray();
        for (String s : WINDOWS.keySet()) a.put(s);
        return a;
    }

    /** 各窗口在屏幕上的实际位置/尺寸(须主线程调用) */
    static JSONArray bounds() {
        JSONArray a = new JSONArray();
        int[] loc = new int[2];
        for (Map.Entry<String, Entry> e : WINDOWS.entrySet()) {
            View v = e.getValue().root;
            v.getLocationOnScreen(loc);
            JSONObject o = new JSONObject();
            try {
                o.put("id", e.getKey())
                 .put("x", loc[0]).put("y", loc[1])
                 .put("w", v.getWidth()).put("h", v.getHeight());
            } catch (Exception ignored) {}
            a.put(o);
        }
        return a;
    }

    /** divider/spacer 这类无固有宽度的节点会把 WRAP 容器撑满, 需给容器兜底宽度 */
    private static boolean hasFillNode(JSONObject n) {
        if (n == null) return false;
        String t = n.optString("type", "");
        if ("divider".equals(t) || "spacer".equals(t)) return !n.has("w");
        JSONArray ch = n.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) {
            JSONObject c = ch.optJSONObject(i);
            if (c != null && hasFillNode(c)) return true;
        }
        return false;
    }

    // ---------- 视图构建 ----------
    private static View build(Context ctx, JSONObject n, String wid, Entry entry) {
        String type = n.optString("type", "text");
        switch (type) {
            case "col":
            case "row": {
                LinearLayout ll = new LinearLayout(ctx);
                ll.setOrientation("row".equals(type) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                common(ll, n, ctx);
                JSONArray ch = n.optJSONArray("children");
                if (ch != null) {
                    for (int i = 0; i < ch.length(); i++) {
                        JSONObject c = ch.optJSONObject(i);
                        if (c == null) continue;
                        View cv = build(ctx, c, wid, entry);
                        if (cv == null) continue;
                        String ct = c.optString("type", "");
                        int cw, chh;
                        if ("divider".equals(ct) || "spacer".equals(ct)) {
                            // 无固有尺寸的节点必须给定值, 否则会撑满父容器
                            cw = c.has("w") ? dim(ctx, c, "w", 0) : ViewGroup.LayoutParams.MATCH_PARENT;
                            chh = c.has("h") ? dim(ctx, c, "h", 0)
                                    : dp(ctx, "divider".equals(ct) ? 1 : 10);
                        } else {
                            cw = dim(ctx, c, "w", ViewGroup.LayoutParams.WRAP_CONTENT);
                            chh = dim(ctx, c, "h", ViewGroup.LayoutParams.WRAP_CONTENT);
                        }
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, chh);
                        int m = dp(ctx, c.optDouble("m", 0));
                        lp.setMargins(m, m, m, m);
                        if (c.optDouble("weight", 0) > 0) lp.weight = (float) c.optDouble("weight");
                        ll.addView(cv, lp);
                    }
                }
                return ll;
            }
            case "button": {
                Button b = new Button(ctx);
                b.setText(n.optString("text", "OK"));
                b.setAllCaps(false);
                b.setTextColor(n.optString("color", "").isEmpty()
                        ? Color.WHITE : Color.parseColor(n.optString("color")));
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 14));
                if (n.optString("bg", "").isEmpty()) {
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(0x33FFFFFF);
                    g.setCornerRadius(dp(ctx, 10));
                    b.setBackground(g);
                }
                common(b, n, ctx);
                final String nid = n.optString("id", "");
                final String label = n.optString("text", "");
                b.setOnClickListener(v -> {
                    JSONObject ev = new JSONObject();
                    try {
                        ev.put("type", "click").put("window", wid)
                          .put("id", nid).put("text", label)
                          .put("ts", System.currentTimeMillis());
                    } catch (Exception ignored) {}
                    pushEvent(ev);
                });
                return b;
            }
            case "input": {
                EditText et = new EditText(ctx);
                et.setHint(n.optString("hint", ""));
                et.setText(n.optString("text", ""));
                et.setTextColor(n.optString("color", "").isEmpty()
                        ? Color.BLACK : Color.parseColor(n.optString("color")));
                et.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 14));
                et.setInputType(n.optBoolean("multiline", false)
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        : InputType.TYPE_CLASS_TEXT);
                common(et, n, ctx);
                String nid = n.optString("id", "input" + entry.inputs.size());
                entry.inputs.put(nid, et);
                return et;
            }
            case "progress": {
                ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(n.optInt("max", 100));
                pb.setProgress(n.optInt("value", 0));
                common(pb, n, ctx);
                return pb;
            }
            case "spacer": {
                View v = new View(ctx);
                common(v, n, ctx);
                return v;
            }
            case "divider": {
                View v = new View(ctx);
                v.setBackgroundColor(n.optString("color", "").isEmpty()
                        ? 0x33000000 : Color.parseColor(n.optString("color")));
                v.setMinimumHeight(Math.max(1, dp(ctx, n.optDouble("h", 1))));
                return v;
            }
            case "text":
            default: {
                TextView tv = new TextView(ctx);
                tv.setText(n.optString("text", ""));
                tv.setTextColor(Color.parseColor(n.optString("color", "#FFFFFF")));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 14));
                String align = n.optString("align", "");
                if ("center".equals(align)) tv.setGravity(Gravity.CENTER);
                else if ("end".equals(align)) tv.setGravity(Gravity.END);
                common(tv, n, ctx);
                return tv;
            }
        }
    }

    private static int dim(Context ctx, JSONObject n, String key, int def) {
        if (!n.has(key)) return def;
        String s = String.valueOf(n.opt(key));
        if ("wrap".equals(s)) return ViewGroup.LayoutParams.WRAP_CONTENT;
        if ("match".equals(s)) return ViewGroup.LayoutParams.MATCH_PARENT;
        return dp(ctx, n.optDouble(key, 0));
    }

    private static void common(View v, JSONObject n, Context ctx) {
        int pad = dp(ctx, n.optDouble("pad", 0));
        if (pad > 0) v.setPadding(pad, pad, pad, pad);
        if (n.has("alpha")) v.setAlpha((float) n.optDouble("alpha", 1));
        String bg = n.optString("bg", "");
        int radius = dp(ctx, n.optDouble("radius", 0));
        if (!bg.isEmpty() || radius > 0) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(bg.isEmpty() ? Color.TRANSPARENT : Color.parseColor(bg));
            if (radius > 0) g.setCornerRadius(radius);
            v.setBackground(g);
        }
    }

    private static int gravity(String s) {
        int g = 0;
        for (String p : s.split("\\|")) {
            switch (p.trim()) {
                case "top":    g |= Gravity.TOP; break;
                case "bottom": g |= Gravity.BOTTOM; break;
                case "start":
                case "left":   g |= Gravity.START; break;
                case "end":
                case "right":  g |= Gravity.END; break;
                case "center": g |= Gravity.CENTER; break;
                default: break;
            }
        }
        return g == 0 ? Gravity.TOP | Gravity.START : g;
    }
}
