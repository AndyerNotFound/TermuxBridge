package lll.bridge.termux;

import android.content.Context;
import android.os.Handler;
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

    private void emit(String gesture) {
        try {
            JSONObject e = new JSONObject();
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
                longFired = false;
                v.setPressed(true);
                h.postDelayed(longPress, 500);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > slop)
                    h.removeCallbacks(longPress);
                return true;
            case MotionEvent.ACTION_UP: {
                h.removeCallbacks(longPress);
                v.setPressed(false);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.hypot(dx, dy) > swipe) {
                    taps = 0;
                    h.removeCallbacks(flush);
                    String dir = Math.abs(dx) > Math.abs(dy)
                            ? (dx > 0 ? "right" : "left")
                            : (dy > 0 ? "down" : "up");
                    emit("swipe:" + dir);
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
                return true;
        }
        return false;
    }
}
