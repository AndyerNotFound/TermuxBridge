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
        Map<View, GestureController> gc = new java.util.IdentityHashMap<>();
        boolean trace;
    }

    static boolean granted(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static int dp(Context c, double v) {
        return Math.round((float) (v * c.getResources().getDisplayMetrics().density));
    }

    static void pushEvent(JSONObject e) {
        synchronized (EVENTS) {
            try { e.put("seq", ++SEQ); } catch (Exception ignored) {}
            EVENTS.addLast(e);
            while (EVENTS.size() > 500) EVENTS.removeFirst();
            EVENTS.notifyAll();          // 唤醒长轮询
        }
    }

    private static long SEQ = 0;

    static long lastSeq() { synchronized (EVENTS) { return SEQ; } }

    /**
     * 读取事件。since>0 时只返回该序号之后的事件; 默认不清空(可重复读),
     * clear=true 才丢弃已读部分。避免多个读者(daemon + 人)互相抢事件。
     */
    static JSONArray read(long since, boolean clear) { return read(since, clear, 0); }

    /** waitMs>0 时长轮询: 没有新事件就挂起等, 省掉 daemon 空转轮询 */
    static JSONArray read(long since, boolean clear, long waitMs) {
        JSONArray a = new JSONArray();
        synchronized (EVENTS) {
            if (waitMs > 0) {
                long deadline = android.os.SystemClock.uptimeMillis() + waitMs;
                while (SEQ <= since) {
                    long left = deadline - android.os.SystemClock.uptimeMillis();
                    if (left <= 0) break;
                    try { EVENTS.wait(left); } catch (InterruptedException ie) { break; }
                }
            }
            long last = 0;
            for (JSONObject o : EVENTS) {
                long sq = o.optLong("seq", 0);
                if (sq > since) { a.put(o); last = sq; }
            }
            if (clear) {
                while (!EVENTS.isEmpty() && EVENTS.peekFirst().optLong("seq", 0) <= last)
                    EVENTS.removeFirst();
            }
        }
        return a;
    }

    /** 兼容旧行为: 读后清空 */
    static JSONArray drain() { return read(0, true); }

    static JSONObject show(Context ctx, JSONObject req) throws Exception {
        long t0 = System.nanoTime();
        if (!granted(ctx)) return Dispatcher.err("需要悬浮窗权限");
        final String id = req.optString("id", "w" + System.currentTimeMillis());
        Entry e = WINDOWS.get(id);
        if (e != null) remove(ctx, id);

        Entry entry = new Entry();
        View root = build(ctx, req.optJSONObject("view") != null
                ? req.optJSONObject("view") : req, id, entry);
        if (root == null) return Dispatcher.err("empty view");

        if (!req.optString("id", "").isEmpty()) root.setTag(req.optString("id"));
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
        int blur = req.optInt("blur", 0);
        if (blur > 0 && Build.VERSION.SDK_INT >= 31) {
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(dp(ctx, blur));
        }
        if (req.has("hz")) lp.preferredRefreshRate = (float) req.optDouble("hz", 120);
        lp.gravity = gravity(req.optString("gravity", "top|start"));
        lp.x = dp(ctx, req.optDouble("x", 0));
        lp.y = dp(ctx, req.optDouble("y", 80));
        // x/y/w/h 统一按 dp
        lp.width = winDim(ctx, req, "w", -2);
        if (lp.width == WindowManager.LayoutParams.WRAP_CONTENT
                && !req.has("w") && hasFillNode(req.optJSONObject("view"))) {
            lp.width = dp(ctx, 300);
        }
        lp.height = winDim(ctx, req, "h", -2);

        entry.trace = req.optBoolean("trace", false);
        // 根节点自己有 on 时不能挂别的 OnTouchListener, 否则会顶掉 GestureController
        JSONObject rootNode = req.optJSONObject("view") != null ? req.optJSONObject("view") : req;
        boolean rootOwns = rootNode.optJSONObject("on") != null;
        if (rootOwns) {
            // 手势已在 build() 里挂到根上; 拖动窗口由同一个控制器驱动, 不再二选一
            if (req.optBoolean("drag_window", false)) {
                GestureController rgc = entry.gc.get(root);
                if (rgc != null) rgc.setMover(makeMover(ctx, root, lp, wm));
            }
        } else if (req.optBoolean("drag_window", false)) {
            attachWindowDrag(ctx, root, lp, wm, id, entry.trace);
        } else {
            // 兜底: 没有子节点消费时也能看到事件是否到达窗口
            root.setOnTouchListener((v, ev) -> { logTouch(id, ev, entry.trace); return false; });
        }

        entry.root = root;
        entry.lp = lp;
        entry.layout = req;
        if (TOUCH_LOG) android.util.Log.d("TBridgeTouch", "ADD id=" + id + " flags=0x"
                + Integer.toHexString(lp.flags) + " " + lp.width + "x" + lp.height);
        wm.addView(root, lp);
        WINDOWS.put(id, entry);

        return Dispatcher.ok()
                .put("id", id)
                .put("in_place", false)
                .put("ms", (System.nanoTime() - t0) / 1_000_000)
                .put("w", root.getMeasuredWidth())
                .put("h", root.getMeasuredHeight());
    }

    /** 按住窗口空白处拖动整个悬浮窗 */
    private static void attachWindowDrag(Context ctx, View root,
                                         WindowManager.LayoutParams lp, WindowManager wm,
                                         String id, boolean trace) {
        final float[] last = new float[2];
        final GestureController.Mover[] moverRef = new GestureController.Mover[1];
        root.setOnTouchListener((v, ev) -> {
            logTouch(id, ev, trace);
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    last[0] = ev.getRawX(); last[1] = ev.getRawY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - last[0], dy = ev.getRawY() - last[1];
                    if (Math.abs(dx) + Math.abs(dy) < 1) return true;
                    if (moverRef[0] == null) moverRef[0] = makeMover(ctx, root, lp, wm);
                    moverRef[0].by(dx, dy);
                    last[0] = ev.getRawX(); last[1] = ev.getRawY();
                    return true;
                }
            }
            return false;
        });
    }

    /** 窗口级触摸日志: 与节点级(GestureController)分开, 用于判断事件有没有到达窗口 */
    /** 触摸日志开关(默认开, 排查完可关) */
    public static volatile boolean TOUCH_LOG = true;

    static void logTouch(String id, android.view.MotionEvent ev, boolean trace) {
        try {
            int i = ev.getActionIndex();
            if (i < 0 || i >= ev.getPointerCount()) i = 0;
            if (trace) {
                org.json.JSONObject o = new org.json.JSONObject();
                String a = android.view.MotionEvent.actionToString(ev.getActionMasked());
                o.put("type", "win:" + a.replace("ACTION_", "").toLowerCase(java.util.Locale.ROOT));
                o.put("id", id);
                o.put("dev", ev.getDeviceId());
                o.put("src", "0x" + Integer.toHexString(ev.getSource()));
                o.put("tool", ev.getToolType(i));
                o.put("p", ev.getPointerCount());
                o.put("x", (int) ev.getRawX());
                o.put("y", (int) ev.getRawY());
                pushEvent(o);
            }
            String tag = "WIN-" + id;
            android.util.Log.d("TBridgeTouch", tag
                    + " a=" + android.view.MotionEvent.actionToString(ev.getActionMasked())
                    + " dev=" + ev.getDeviceId()
                    + " src=0x" + Integer.toHexString(ev.getSource())
                    + " tool=" + ev.getToolType(i)
                    + " p=" + ev.getPointerCount()
                    + " fl=0x" + Integer.toHexString(ev.getFlags())
                    + " at=" + (int) ev.getRawX() + "," + (int) ev.getRawY());
        } catch (Throwable ignored) {}
    }

    /** 限制在可视区内(悬浮窗坐标原点已在状态栏下方, 故下界为 0) */
    private static void clamp(WindowManager.LayoutParams lp, View root, WindowManager wm) {
        int w = root.getWidth() > 0 ? root.getWidth()
                : (lp.width > 0 ? lp.width : 0);
        int h = root.getHeight() > 0 ? root.getHeight()
                : (lp.height > 0 ? lp.height : 0);
        android.graphics.Rect b;
        if (Build.VERSION.SDK_INT >= 30) b = wm.getCurrentWindowMetrics().getBounds();
        else b = new android.graphics.Rect(0, 0,
                root.getResources().getDisplayMetrics().widthPixels,
                root.getResources().getDisplayMetrics().heightPixels);
        int maxX = Math.max(0, b.width() - w);
        int maxY = Math.max(0, b.height() - h - 140);
        lp.x = Math.max(0, Math.min(lp.x, maxX));
        lp.y = Math.max(0, Math.min(lp.y, maxY));
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

    /** 视图树快照(调试): 每节点 id/类名/屏幕坐标 */
    static JSONArray tree(String win) {
        JSONArray out = new JSONArray();
        Entry e = WINDOWS.get(win);
        if (e == null) return out;
        int[] loc = new int[2];
        walk(e.root, out, loc, 0);
        return out;
    }

    private static void walk(View v, JSONArray out, int[] loc, int depth) {
        if (v == null || depth > 12) return;
        v.getLocationOnScreen(loc);
        JSONObject o = new JSONObject();
        try {
            o.put("depth", depth)
             .put("cls", v.getClass().getSimpleName())
             .put("id", v.getTag() instanceof String ? v.getTag() : "")
             .put("x", loc[0]).put("y", loc[1])
             .put("w", v.getWidth()).put("h", v.getHeight())
             .put("clickable", v.isClickable())
             .put("touchable", v.isEnabled());
        } catch (Exception ignored) {}
        out.put(o);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), out, loc, depth + 1);
        }
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

    private static GestureController newGC(Context ctx, String wid, JSONObject n, Entry entry) {
        GestureController c = new GestureController(ctx, wid, n.optString("id", ""),
                n.optString("text", ""), n);
        entry.gc.put(null, c);   // 占位, 真实 View 在 attach 时写入
        return c;
    }

    private static void attachGC(View v, GestureController c, Entry entry) {
        v.setOnTouchListener(c);
        entry.gc.put(v, c);
    }

    static final java.util.concurrent.atomic.AtomicLong MOVES =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong COMMITS =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong COMMIT_NS =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong MAX_NS =
            new java.util.concurrent.atomic.AtomicLong();
    static volatile long STAT_T0 = android.os.SystemClock.uptimeMillis();
    static final long[] FRAME_GAPS = new long[128];
    static int FRAME_IDX = 0;
    static long LAST_COMMIT_NS = 0;

    /** 拖动窗口: 累积位移, 每帧只提交一次, 屏幕尺寸只取一次(避免 Binder 抖动) */
    private static GestureController.Mover makeMover(Context ctx, View root,
                                                     WindowManager.LayoutParams lp, WindowManager wm) {
        final float[] pend = new float[2];
        final boolean[] sched = {false};
        final android.graphics.Rect scr;
        if (Build.VERSION.SDK_INT >= 30) {
            scr = wm.getCurrentWindowMetrics().getBounds();
        } else {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            scr = new android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
        final int bottomPad = dp(ctx, 44);
        return (dx, dy) -> {
            pend[0] += dx; pend[1] += dy;
            if (sched[0]) return;
            sched[0] = true;
            root.postOnAnimation(() -> {
                sched[0] = false;
                int mx = Math.round(pend[0]), my = Math.round(pend[1]);
                pend[0] -= mx; pend[1] -= my;
                if (mx == 0 && my == 0) return;
                lp.x += mx; lp.y += my;
                int w = root.getWidth(), h = root.getHeight();
                lp.x = Math.max(0, Math.min(lp.x, Math.max(0, scr.width() - w)));
                lp.y = Math.max(0, Math.min(lp.y, Math.max(0, scr.height() - h - bottomPad)));
                long t0 = System.nanoTime();
                if (LAST_COMMIT_NS != 0) { FRAME_GAPS[FRAME_IDX++ % FRAME_GAPS.length] = t0 - LAST_COMMIT_NS; }
                LAST_COMMIT_NS = t0;
                try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
                long dt = System.nanoTime() - t0;
                COMMITS.incrementAndGet();
                COMMIT_NS.addAndGet(dt);
                MAX_NS.accumulateAndGet(dt, Math::max);
            });
        };
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
                        if (!c.optString("id", "").isEmpty()) cv.setTag(c.optString("id"));
                        ll.addView(cv, childLp(ctx, c));
                    }
                }
                if (n.optJSONObject("on") != null) {
                    attachGC(ll, newGC(ctx, wid, n, entry), entry);
                    ll.setClickable(true);
                }
                return ll;
            }
            case "button": {
                Button b = new Button(ctx);
                b.setText(n.optString("text", "OK"));
                b.setAllCaps(false);
                b.setTextColor(n.optString("color", "").isEmpty()
                        ? Color.WHITE : Util.color(n.optString("color"), Color.WHITE));
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
                attachGC(b, newGC(ctx, wid, n, entry), entry);
                b.setClickable(true);
                return b;
            }
            case "input": {
                EditText et = new EditText(ctx);
                et.setHint(n.optString("hint", ""));
                et.setText(n.optString("text", ""));
                et.setTextColor(n.optString("color", "").isEmpty()
                        ? Color.BLACK : Util.color(n.optString("color"), Color.WHITE));
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
                        ? 0x33000000 : Util.color(n.optString("color"), Color.WHITE));
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
                // 只有声明了 on 的节点才接手势; 否则让触摸冒泡给父容器
                if (!n.optString("id", "").isEmpty() && n.optJSONObject("on") != null)
                    attachGC(tv, newGC(ctx, wid, n, entry), entry);
                return tv;
            }
        }
    }

    /** 子节点布局参数(divider/spacer 必须给确定尺寸) */
    private static LinearLayout.LayoutParams childLp(Context ctx, JSONObject c) {
        String ct = c.optString("type", "");
        int cw, chh;
        if ("divider".equals(ct) || "spacer".equals(ct)) {
            cw = c.has("w") ? dim(ctx, c, "w", 0) : ViewGroup.LayoutParams.MATCH_PARENT;
            chh = c.has("h") ? dim(ctx, c, "h", 0) : dp(ctx, "divider".equals(ct) ? 1 : 10);
        } else {
            cw = dim(ctx, c, "w", ViewGroup.LayoutParams.WRAP_CONTENT);
            chh = dim(ctx, c, "h", ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, chh);
        if (c.has("gravity")) lp.gravity = gravity(c.optString("gravity"));
        int m = dp(ctx, c.optDouble("m", 0));
        lp.setMargins(m, m, m, m);
        if (c.optDouble("weight", 0) > 0) lp.weight = (float) c.optDouble("weight");
        return lp;
    }

    /** 就地刷新属性(text/颜色/进度/内边距/背景), 不重建视图 */
    static boolean patch(View v, JSONObject n, Context ctx, String wid, Entry entry) {
        String type = n.optString("type", "text");
        switch (type) {
            case "col":
            case "row": {
                if (!(v instanceof LinearLayout)) return false;
                LinearLayout ll = (LinearLayout) v;
                int want = "row".equals(type) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
                if (ll.getOrientation() != want) return false;
                common(ll, n, ctx);
                JSONArray ch = n.optJSONArray("children");
                int wantCount = ch == null ? 0 : ch.length();
                while (ll.getChildCount() > wantCount) ll.removeViewAt(ll.getChildCount() - 1);
                for (int i = 0; i < wantCount; i++) {
                    JSONObject c = ch.optJSONObject(i);
                    if (c == null) continue;
                    if (i < ll.getChildCount()) {
                        View child = ll.getChildAt(i);
                        boolean same = patch(child, c, ctx, wid, entry);
                        if (!same) {
                            ll.removeViewAt(i);
                            View nv = build(ctx, c, wid, entry);
                            if (nv != null) ll.addView(nv, i, childLp(ctx, c));
                        } else {
                            child.setLayoutParams(childLp(ctx, c));
                        }
                    } else {
                        View nv = build(ctx, c, wid, entry);
                        if (nv != null) ll.addView(nv, childLp(ctx, c));
                    }
                }
                return true;
            }
            case "button": {
                if (!(v instanceof Button)) return false;
                Button b = (Button) v;
                String label = n.optString("text", "OK");
                if (!label.contentEquals(b.getText())) {
                    b.setText(label);
                    attachGC(b, newGC(ctx, wid, n, entry), entry);
                }
                b.setTextColor(n.optString("color", "").isEmpty()
                        ? Color.WHITE : Util.color(n.optString("color"), Color.WHITE));
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 14));
                common(b, n, ctx);
                return true;
            }
            case "input": {
                if (!(v instanceof EditText)) return false;
                EditText et = (EditText) v;
                et.setHint(n.optString("hint", ""));
                String t = n.optString("text", "");
                if (!t.contentEquals(et.getText())) et.setText(t);
                common(et, n, ctx);
                entry.inputs.put(n.optString("id", "input" + entry.inputs.size()), et);
                return true;
            }
            case "progress": {
                if (!(v instanceof ProgressBar)) return false;
                ProgressBar pb = (ProgressBar) v;
                pb.setMax(n.optInt("max", 100));
                pb.setProgress(n.optInt("value", 0));
                common(pb, n, ctx);
                return true;
            }
            case "divider": {
                if (!(v instanceof TextView) && v.getClass() != View.class) return false;
                v.setBackgroundColor(n.optString("color", "").isEmpty()
                        ? 0x33000000 : Util.color(n.optString("color"), 0x33000000));
                v.setMinimumHeight(Math.max(1, dp(ctx, n.optDouble("h", 1))));
                return true;
            }
            case "spacer": {
                if (v.getClass() != View.class) return false;
                common(v, n, ctx);
                return true;
            }
            case "text":
            default: {
                if (!(v instanceof TextView) || v instanceof Button || v instanceof EditText) return false;
                TextView tv = (TextView) v;
                String t = n.optString("text", "");
                if (!t.contentEquals(tv.getText())) tv.setText(t);
                tv.setTextColor(Util.color(n.optString("color", ""), Color.WHITE));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 14));
                String align = n.optString("align", "");
                tv.setGravity("center".equals(align) ? Gravity.CENTER
                        : "end".equals(align) ? Gravity.END : Gravity.START | Gravity.TOP);
                common(tv, n, ctx);
                return true;
            }
        }
    }

    /** 原地更新: 同 id 窗口内 diff + patch, 不 remove/add 窗口 */
    static JSONObject update(Context ctx, JSONObject req) throws Exception {
        String id = req.optString("id", "");
        Entry e = WINDOWS.get(id);
        if (e == null) return Dispatcher.err("no such window: " + id);
        JSONObject node = req.optJSONObject("view") != null ? req.optJSONObject("view") : req;
        if (node == null) return Dispatcher.err("empty view");

        long t0 = System.nanoTime();
        e.inputs.clear();
        boolean inPlace = patch(e.root, node, ctx, id, e);
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (!inPlace) {
            View nr = build(ctx, node, id, e);
            if (nr == null) return Dispatcher.err("empty view");
            wm.removeView(e.root);
            e.root = nr;
            wm.addView(nr, e.lp);
        }

        // 窗口级参数(x/y/w/h/blur)可选更新
        boolean moved = false;
        if (req.has("x")) { e.lp.x = dp(ctx, req.optDouble("x", 0)); moved = true; }
        if (req.has("y")) { e.lp.y = dp(ctx, req.optDouble("y", 0)); moved = true; }
        if (req.has("w")) { e.lp.width = winDim(ctx, req, "w", -2); moved = true; }
        if (req.has("h")) { e.lp.height = winDim(ctx, req, "h", -2); moved = true; }
        if (req.has("gravity")) { e.lp.gravity = gravity(req.optString("gravity", "top|start")); moved = true; }
        int blur = req.optInt("blur", -1);
        if (blur >= 0 && Build.VERSION.SDK_INT >= 31) {
            if (blur == 0) { e.lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND; e.lp.setBlurBehindRadius(0); }
            else { e.lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND; e.lp.setBlurBehindRadius(dp(ctx, blur)); }
            moved = true;
        }
        if (moved) { clamp(e.lp, e.root, wm); wm.updateViewLayout(e.root, e.lp); }

        e.layout = req;
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return Dispatcher.ok().put("id", id).put("in_place", inPlace).put("ms", ms);
    }

    /** 窗口级尺寸: 数字按 dp, 或 wrap/match */
    private static int winDim(Context ctx, JSONObject req, String key, int def) {
        if (!req.has(key)) return def;
        String v = String.valueOf(req.opt(key));
        if ("wrap".equals(v)) return WindowManager.LayoutParams.WRAP_CONTENT;
        if ("match".equals(v)) return WindowManager.LayoutParams.MATCH_PARENT;
        return dp(ctx, req.optDouble(key, 0));
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
            g.setColor(bg.isEmpty() ? Color.TRANSPARENT : Util.color(bg, 0xFF000000));
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
