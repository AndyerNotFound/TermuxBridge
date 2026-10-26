package lll.bridge.termux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && !Prefs.panicked(ctx)) {
            try {
                ctx.startForegroundService(new Intent(ctx, BridgeService.class)
                        .setAction(BridgeService.ACTION_START));
            } catch (Exception ignored) {}
        }
    }
}
