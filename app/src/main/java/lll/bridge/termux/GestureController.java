package lll.bridge.termux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

/**
 * 悬浮窗触控识别。所有阈值可按节点配置:
 *   tap_ms(多击窗口, 默认280) hold_ms(长按, 默认500) long_hold(二段长按, 默认关)
 *   slop(进入拖动的位移dp, 默认20) swipe(滑动判定dp, 默认56)
 * 事件: tap(click) / double / triple / long / long_hold / drag:start,move,end / swipe:up|down|left|right
 * 窗口移动交给 Mover, 与手势共存(不再二选一)。
 */
final class GestureController implements View.OnTouchListener {
    interface Mover { void by(float dx, float dy); }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final String win, id, label;
    private final float slop, swipe;
    private final long tapMs, holdMs, hold2Ms;

    private Mover mover;
    private int taps = 0;
    private boolean longFired = false, hold2Fired = false, dragging = false;
    private long downTime = 0, lastEmit = 0;
    private float downX, downY, lastRawX, lastRawY;
    private int activeId = -1;

    private final Runnable flush = () -> {
        int n = taps; taps = 0;
        if (n <= 0) return;
        emit(n == 1 ? "tap" : n == 2 ? "double" : "triple");
    };

    private final Runnable longPress = () -> {
        longFired = true;
        emit("long");
    };

    private final Runnable longHoldR = () -> {
        hold2Fired = true;
        emit("long_hold");
    };

    GestureController(Context ctx, String win, String id, String label, JSONObject o) {
        this.win = win; this.id = id; this.label = label;
        if (o == null) o = new JSONObject();
        float d = ctx.getResources().getDisplayMetrics().density;
        this.slop = (float) (o.optDouble("slop", 20) * d);
        this.swipe = (float) (o.optDouble("swipe", 56) * d);
        this.tapMs = (long) o.optDouble("tap_ms", 280);
        this.holdMs = (long) o.optDouble("hold_ms", 500);
        this.hold2Ms = (long) o.optDouble("long_hold", 0);
    }

    /** 拖动时移动窗口 */
    void setMover(Mover m) { mover = m; }

    private void emit(String gesture) { emit(gesture, 0, 0, 0); }

    private int dev = -1;

    private void emit(String gesture, float dx, float dy, long ms) {
        try {
            JSONObject e = new JSONObject();
            if (gesture.startsWith("drag") || gesture.startsWith("swipe")) {
                e.put("dx", Math.round(dx)).put("dy", Math.round(dy)).put("ms", ms);
            }
            e.put("type", "tap".equals(gesture) ? "click" : "gesture");
            e.put("gesture", gesture);
            e.put("window", win);
            e.put("id", id);
            e.put("text", label == null ? "" : label);
            e.put("dev", dev);
            e.put("ts", System.currentTimeMillis());
            OverlayManager.pushEvent(e);
        } catch (Exception ignored) {}
    }

    private void cancelTimers() {
        h.removeCallbacks(longPress);
        h.removeCallbacks(longHoldR);
    }

    private void reset(View v) {
        cancelTimers();
        try { v.getRootView().setLayerType(View.LAYER_TYPE_NONE, null); }
        catch (Throwable ignored) {}
        v.setPressed(false);
        v.setAlpha(1f);
        dragging = false;
        longFired = false;
        hold2Fired = false;
        activeId = -1;
    }

    @Override public boolean onTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (e.getPointerCount() > 1 && action != MotionEvent.ACTION_UP) {
            // 多指不参与判定: 清状态, 免得半路留悬空拖动
            if (dragging) emit("drag:end", 0, 0, SystemClock.uptimeMillis() - downTime);
            taps = 0; h.removeCallbacks(flush);
            reset(v);
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                lastRawX = e.getRawX(); lastRawY = e.getRawY();
                downTime = SystemClock.uptimeMillis();
                lastEmit = 0;
                dragging = false; longFired = false; hold2Fired = false;
                activeId = e.getPointerId(0);
                dev = e.getDeviceId();
                v.setPressed(true);
                v.setAlpha(0.55f);
                if (OverlayManager.TOUCH_LOG) {
                    android.util.Log.d("TBridgeTouch", "DOWN win=" + win + " id=" + id
                            + " dev=" + e.getDeviceId()
                            + " src=0x" + Integer.toHexString(e.getSource())
                            + " tool=" + e.getToolType(0)
                            + " flags=" + Integer.toHexString(e.getFlags())
                            + " x=" + (int) e.getX() + " y=" + (int) e.getY());
                }
                h.postDelayed(longPress, holdMs);
                if (hold2Ms > 0) h.postDelayed(longHoldR, hold2Ms);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (activeId >= 0 && e.findPointerIndex(activeId) < 0) return true;
                float mx = e.getX() - downX, my = e.getY() - downY;
                float dist = (float) Math.hypot(mx, my);
                if (dist <= slop) return true;
                long now = SystemClock.uptimeMillis();
                OverlayManager.MOVES.incrementAndGet();
                if (!dragging) {
                    dragging = true;
                    try { v.getRootView().setLayerType(View.LAYER_TYPE_HARDWARE, null); }
                    catch (Throwable ignored) {}
                    taps = 0;
                    h.removeCallbacks(flush);
                    cancelTimers();                    // 拖动中不再触发长按
                    emit("drag:start", mx, my, now - downTime);
                    lastEmit = now;
                } else if (now - lastEmit > 40) {
                    lastEmit = now;
                    emit("drag:move", mx, my, now - downTime);
                }
                if (mover != null) {
                    float rx = e.getRawX(), ry = e.getRawY();
                    mover.by(rx - lastRawX, ry - lastRawY);
                    lastRawX = rx; lastRawY = ry;
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                cancelTimers();
                v.setPressed(false);
                v.setAlpha(1f);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                long dur = SystemClock.uptimeMillis() - downTime;
                String dir = Math.abs(dx) > Math.abs(dy)
                        ? (dx > 0 ? "right" : "left")
                        : (dy > 0 ? "down" : "up");
                if (OverlayManager.TOUCH_LOG) {
                    android.util.Log.d("TBridgeTouch", "UP win=" + win + " id=" + id
                            + " dur=" + dur + " dist=" + (int) Math.hypot(dx, dy));
                }
                if (dragging) {
                    dragging = false;
                    try { v.getRootView().setLayerType(View.LAYER_TYPE_NONE, null); }
                    catch (Throwable ignored) {}
                    if (dur < 250 && Math.hypot(dx, dy) > swipe) {
                        emit("drag:cancel", dx, dy, dur);
                        emit("swipe:" + dir, dx, dy, dur);
                    } else {
                        emit("drag:end", dx, dy, dur);
                    }
                    activeId = -1;
                    return true;
                }
                if (longFired || hold2Fired) { activeId = -1; return true; }
                if (Math.hypot(dx, dy) > swipe) {       // 没有 MOVE 的快速滑动
                    taps = 0;
                    h.removeCallbacks(flush);
                    emit("swipe:" + dir, dx, dy, dur);
                    activeId = -1;
                    return true;
                }
                activeId = -1;
                taps++;
                h.removeCallbacks(flush);
                h.postDelayed(flush, tapMs);
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                long dur = SystemClock.uptimeMillis() - downTime;
                boolean wasDrag = dragging;
                reset(v);
                if (wasDrag) emit("drag:end", dx, dy, dur);   // 不再留悬空拖动
                if (OverlayManager.TOUCH_LOG) {
                    android.util.Log.d("TBridgeTouch", "CANCEL win=" + win + " id=" + id);
                }
                return true;
            }
        }
        return false;
    }
}
