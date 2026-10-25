package lll.bridge.termux;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务: 提供系统级能力, 无需 MediaProjection / 无需 root。
 *  - takeScreenshot(): Android 11+ 专用截图 API, 无授权弹窗
 *  - dispatchGesture(): 模拟点击/滑动
 *  - getRootInActiveWindow(): 导出界面树 (Layout 渲染)
 */
public class BridgeA11yService extends AccessibilityService {
    private static volatile BridgeA11yService INSTANCE;

    @Override public void onServiceConnected() { INSTANCE = this; }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* 仅按需拉取, 不常驻监听 */ }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() { INSTANCE = null; super.onDestroy(); }

    static boolean ready() { return INSTANCE != null; }

    /** 截图, 图像编码为 PNG base64。执行线程需非主线程(内部会等待)。 */
    static JSONObject screenshot() throws Exception {
        final BridgeA11yService svc = INSTANCE;
        if (svc == null) return Dispatcher.err("accessibility service not enabled");
        if (Build.VERSION.SDK_INT < 30) return Dispatcher.err("requires Android 11+");
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] out = new Bitmap[1];
        final int[] err = new int[1];
        Executor ex = command -> new Thread(command).start();
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, ex,
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) {
                        HardwareBuffer hb = r.getHardwareBuffer();
                        try {
                            out[0] = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace()).copy(
                                    Bitmap.Config.ARGB_8888, false);
                        } catch (Exception e) {
                            err[0] = -1;
                        } finally {
                            try { hb.close(); } catch (Exception ignored) {}
                            latch.countDown();
                        }
                    }
                    @Override public void onFailure(int errorCode) {
                        err[0] = errorCode;
                        latch.countDown();
                    }
                });
        if (!latch.await(8, TimeUnit.SECONDS)) return Dispatcher.err("screenshot timeout");
        if (out[0] == null) return Dispatcher.err("screenshot failed, code=" + err[0]);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out[0].compress(Bitmap.CompressFormat.PNG, 100, bo);
        out[0].recycle();
        return Dispatcher.okPut("png_b64", android.util.Base64.encodeToString(
                bo.toByteArray(), android.util.Base64.NO_WRAP));
    }

    /** 模拟点击。 */
    static boolean tap(float x, float y) throws Exception {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatch(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build());
    }

    static boolean swipe(float x1, float y1, float x2, float y2, long ms) throws Exception {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return dispatch(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(60, ms))).build());
    }

    private static boolean dispatch(GestureDescription g) throws Exception {
        final BridgeA11yService svc = INSTANCE;
        if (svc == null) throw new IllegalStateException("accessibility service not enabled");
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        svc.dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok[0] = true; latch.countDown(); }
            @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
        }, null);
        latch.await(5, TimeUnit.SECONDS);
        return ok[0];
    }

    /** 导出当前窗口 UI 树(可用于"Layout 渲染"分析/自动化定位)。 */
    static JSONArray dumpTree(int maxDepth) {
        BridgeA11yService svc = INSTANCE;
        JSONArray out = new JSONArray();
        if (svc == null) return out;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return out;
        walk(root, 0, maxDepth, out);
        return out;
    }

    private static void walk(AccessibilityNodeInfo n, int depth, int maxDepth, JSONArray out) {
        if (depth > maxDepth || n == null) return;
        JSONObject o = new JSONObject();
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            o.put("depth", depth)
             .put("cls", String.valueOf(n.getClassName()))
             .put("text", n.getText() == null ? "" : n.getText().toString())
             .put("desc", n.getContentDescription() == null ? "" : n.getContentDescription().toString())
             .put("id", n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName())
             .put("clickable", n.isClickable())
             .put("scrollable", n.isScrollable())
             .put("bounds", r.left + "," + r.top + "," + r.right + "," + r.bottom);
            out.put(o);
        } catch (Exception ignored) {}
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, maxDepth, out);
    }
}
