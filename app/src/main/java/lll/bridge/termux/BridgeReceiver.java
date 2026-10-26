package lll.bridge.termux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 备用广播通道: am broadcast -a lll.bridge.termux.CMD --es json '{...}'
 *  仅支持无 UI 依赖命令; 结果写入日志(logcat -s TBridge)。 */
public class BridgeReceiver extends BroadcastReceiver {
    /** 通知动作按钮 → 执行一条桥命令 */
    public static final String ACTION_RUN = "lll.bridge.termux.RUN";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // panic brake: the whole channel is cut until the service restarts
        if (Prefs.panicked(ctx)) { android.util.Log.i("TBridge", "RESP {\"ok\":false,\"error\":\"panicked - restart service to recover\"}"); return; }
        if (ACTION_RUN.equals(intent.getAction())) {
            String j = intent.getStringExtra("json");
            if (j != null) android.util.Log.i("TBridge", "ACTION " + j + " -> " + Dispatcher.dispatch(ctx, j, false));
            return;
        }
        String json = intent.getStringExtra("json");
        if (json == null) return;
        String resp = Dispatcher.dispatch(ctx, json, true);
        android.util.Log.i("TBridge", "RESP " + resp);
    }
}
