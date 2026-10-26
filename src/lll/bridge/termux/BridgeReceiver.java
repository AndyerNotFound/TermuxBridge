package lll.bridge.termux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Request channel (v2.0.1):
 *
 *   am broadcast -n lll.bridge.termux/.BridgeReceiver -a lll.bridge.termux.CMD \
 *       --es json '{"cmd":"battery"}' --es res 127.0.0.1:41234
 *
 * The receiver dispatches the JSON command and writes the one-line JSON response
 * to the loopback listener named by `res`. Without `res` the request is
 * fire-and-forget and the response only goes to logcat (`TBridge`).
 *
 * An explicit component is required: implicit broadcasts to a background app are
 * dropped by background-execution policy ("skipped by policy at enqueue").
 *
 * Exposure control: the receiver is exported (Termux cannot hold a signature
 * permission this app defines), so callers are gated by sender uid on API 34+
 * (Termux, shell, root, self) and by the optional token checked in
 * {@link Dispatcher}. Set a token in the app if other apps on the device are not
 * trusted.
 */
public class BridgeReceiver extends BroadcastReceiver {
    /** request action; the CLI and the python client both use it */
    public static final String ACTION_CMD = "lll.bridge.termux.CMD";
    /** notification action button -> run one bridge command */
    public static final String ACTION_RUN = "lll.bridge.termux.RUN";

    private static volatile int termuxUid = -1;

    @Override
    public void onReceive(final Context ctx, final Intent intent) {
        final String json = intent.getStringExtra("json");
        if (json == null) return;
        final String res = intent.getStringExtra("res");
        final boolean isRun = ACTION_RUN.equals(intent.getAction());
        final boolean hasCallback = res != null && !res.isEmpty();

        if (Prefs.panicked(ctx)) {
            deliver(ctx, res, "{\"ok\":false,\"error\":\"panicked - restart service to recover\"}");
            return;
        }
        if (!senderAllowed(ctx)) {
            deliver(ctx, res, "{\"ok\":false,\"error\":\"sender uid not allowed\"}");
            return;
        }

        // Dispatch off the main thread; goAsync keeps the process alive up to ~10 s,
        // which is enough for every synchronous command (async ones are polled).
        final PendingResult pending = goAsync();
        new Thread(() -> {
            String resp;
            try {
                resp = Dispatcher.dispatch(ctx, json, !hasCallback);
            } catch (Throwable t) {
                resp = "{\"ok\":false,\"error\":\"dispatch: " + t + "\"}";
            }
            deliver(ctx, res, resp);
            try { pending.finish(); } catch (Throwable ignored) {}
        }, "tbridge-dispatch").start();
    }

    /** Write the response to the caller's loopback listener and to logcat. */
    private void deliver(Context ctx, String res, String resp) {
        android.util.Log.i("TBridge", "RESP " + resp);
        if (res == null || res.isEmpty()) return;
        int idx = res.lastIndexOf(':');
        if (idx <= 0) return;
        String host = res.substring(0, idx);
        int port;
        try { port = Integer.parseInt(res.substring(idx + 1)); } catch (Exception e) { return; }
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) return;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            OutputStream out = s.getOutputStream();
            out.write((resp + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            android.util.Log.e("TBridge", "callback " + res + ": " + e);
        }
    }

    private boolean senderAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return true;    // sender uid not exposed; token still applies
        int uid;
        try { uid = getSentFromUid(); } catch (Throwable t) { return true; }
        if (uid < 0) return true;
        if (uid == android.os.Process.myUid()) return true;
        if (uid == 0 || uid == 2000) return true;       // root / shell (rish)
        return uid == termuxUid(ctx);
    }

    private static int termuxUid(Context ctx) {
        if (termuxUid < 0) {
            try { termuxUid = ctx.getPackageManager().getPackageUid("com.termux", 0); }
            catch (Exception e) { termuxUid = -2; }      // Termux not installed
        }
        return termuxUid;
    }
}
