package lll.bridge.termux;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

/**
 * 悬浮窗触控识别: 单击 / 双击 / 三击 / 长按 / 滑动。
 * 单击在 280ms 静默后发出(用于区分双击), 长按阈值 500ms。
 */
final class GestureController implements View.OnTouchListener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private final String win, id, label;
    private final float slop, swipe;
    private int taps = 0;
    private boolean longFired = false;
    private boolean dragging = false;
    private long downTime = 0, lastEmit = 0;
    private float downX, downY;

    private final Runnable flush = () -> {
        int n = taps; taps = 0;
        if (n <= 0) return;
        emit(n == 1 ? "tap" : n == 2 ? "double" : "triple");
    };

    private final Runnable longPress = () -> {
        longFired = true; taps = 0;
        emit("long");
    };

    GestureController(Context ctx, String win, String id, String label) {
        this.win = win; this.id = id; this.label = label;
        float d = ctx.getResources().getDisplayMetrics().density;
        this.slop = 20 * d;
        this.swipe = 56 * d;
    }

    private void emit(String gesture) { emit(gesture, 0, 0, 0); }

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
            e.put("text", label);
            e.put("ts", System.currentTimeMillis());
            OverlayManager.pushEvent(e);
        } catch (Exception ignored) {}
    }

    @Override public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                longFired = false; dragging = false;
                downTime = SystemClock.uptimeMillis();
                lastEmit = 0;
                v.setPressed(true);
                v.setAlpha(0.55f);          // 视觉反馈: 手指是否真的到了
                android.util.Log.d("TBridgeTouch", "DOWN win=" + win + " id=" + id
                        + " flags=" + Integer.toHexString(e.getFlags())
                        + " x=" + (int) e.getX() + " y=" + (int) e.getY());
                h.postDelayed(longPress, 500);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float mx = e.getX() - downX, my = e.getY() - downY;
                float dist = (float) Math.hypot(mx, my);
                if (dist > slop) {
                    h.removeCallbacks(longPress);
                    long now = SystemClock.uptimeMillis();
                    if (!dragging) {
                        dragging = true;
                        taps = 0;
                        h.removeCallbacks(flush);
                        emit("drag:start", mx, my, now - downTime);
                        lastEmit = now;
                    } else if (now - lastEmit > 40) {
                        lastEmit = now;
                        emit("drag:move", mx, my, now - downTime);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                h.removeCallbacks(longPress);
                v.setPressed(false);
                v.setAlpha(1f);
                android.util.Log.d("TBridgeTouch", "UP win=" + win + " id=" + id
                        + " flags=" + Integer.toHexString(e.getFlags()));
                float dx = e.getX() - downX, dy = e.getY() - downY;
                long dur = SystemClock.uptimeMillis() - downTime;
                if (dragging) {
                    dragging = false;
                    String dir = Math.abs(dx) > Math.abs(dy)
                            ? (dx > 0 ? "right" : "left")
                            : (dy > 0 ? "down" : "up");
                    if (dur < 250 && Math.hypot(dx, dy) > swipe) emit("swipe:" + dir, dx, dy, dur);
                    else emit("drag:end", dx, dy, dur);
                    return true;
                }
                if (Math.hypot(dx, dy) > swipe) {
                    taps = 0;
                    h.removeCallbacks(flush);
                    String dir = Math.abs(dx) > Math.abs(dy)
                            ? (dx > 0 ? "right" : "left")
                            : (dy > 0 ? "down" : "up");
                    emit("swipe:" + dir, dx, dy, dur);
                    return true;
                }
                if (longFired) return true;
                taps++;
                h.removeCallbacks(flush);
                h.postDelayed(flush, 280);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                v.setPressed(false);
                v.setAlpha(1f);
                android.util.Log.d("TBridgeTouch", "CANCEL win=" + win + " id=" + id
                        + " flags=" + Integer.toHexString(e.getFlags()));
                return true;
        }
        return false;
    }
}
