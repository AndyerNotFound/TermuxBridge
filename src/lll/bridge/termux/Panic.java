package lll.bridge.termux;

import android.content.Context;
import android.content.Intent;

/**
 * panic.stop - hard brake for the whole bridge.
 *
 * Cuts every active capability in one shot, regardless of feature toggles:
 *   overlay windows removed, a11y disabled via disableSelf() (the official
 *   self-revocation API; the system persists it in enabled_accessibility_
 *   services), notification listener unbound, TCP listener closed, foreground
 *   service stopped, START_STICKY broken (Panicked flag), boot auto-start
 *   gated by the same flag.
 *
 * Not covered by design: the IME (BridgeImeService) stays available - it is
 * the user's keyboard; MediaProjection grants are single-shot and die with
 * the capture, nothing persists; recording sessions are short-lived.
 *
 * Recovery: start the service again (clears Panicked) or the Shizuku
 * one-shot grant (re-enables a11y at settings level).
 */
final class Panic {
    private Panic() {}

    /** idempotent, any thread; every step swallows its own failures */
    static void stopAll(Context ctx) {
        // 1. overlay windows (main thread, fire-and-forget)
        Util.main(ctx, () -> OverlayManager.removeAll(ctx));

        // 2. a11y: official self-revocation (persists in secure settings)
        try { BridgeA11yService.selfStop(); } catch (Throwable ignored) {}

        // 3. notification listener: unbind now
        try {
            if (BridgeNotifListener.INSTANCE != null)
                BridgeNotifListener.INSTANCE.requestUnbind();
        } catch (Throwable ignored) {}

        // 4. break START_STICKY + boot autostart, then stop the service LAST
        //    (stopping closes the TCP listener with it)
        Prefs.setPanicked(ctx, true);
        Intent it = new Intent(ctx, BridgeService.class);
        it.setAction(BridgeService.ACTION_STOP);
        try { ctx.startService(it); } catch (Throwable ignored) {}
    }
}
