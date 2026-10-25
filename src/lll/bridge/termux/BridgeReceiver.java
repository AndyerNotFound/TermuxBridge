package lll.bridge.termux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 备用广播通道: am broadcast -a lll.bridge.termux.CMD --es json '{...}'
 *  仅支持无 UI 依赖命令; 结果写入日志(logcat -s TBridge)。 */
public class BridgeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String json = intent.getStringExtra("json");
        if (json == null) return;
        String resp = Dispatcher.dispatch(ctx, json, true);
        android.util.Log.i("TBridge", "RESP " + resp);
    }
}
