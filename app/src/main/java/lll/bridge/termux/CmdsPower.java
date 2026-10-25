package lll.bridge.termux;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Map;

/** 电源管理: 电池优化豁免 / 省电模式 */
final class CmdsPower {
    private CmdsPower() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("power.status", (ctx, p) -> {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean ignoring = Build.VERSION.SDK_INT < 23
                    || pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            return Dispatcher.okPut("ignoring", ignoring)
                    .put("save_mode", pm.isPowerSaveMode())
                    .put("interactive", pm.isInteractive())
                    .put("doze_idle", Build.VERSION.SDK_INT >= 23 && pm.isDeviceIdleMode());
        });

        // 弹系统授权框(需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        r.put("power.request", (ctx, p) -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                return Dispatcher.ok().put("shown", true);
            } catch (Exception e) {
                return Dispatcher.err("无法弹窗(后台启动受限): " + e.getClass().getSimpleName());
            }
        });

        // 打开系统的电池优化列表 / 应用详情
        r.put("power.settings", (ctx, p) -> {
            Intent i;
            if ("app".equals(p.optString("target", ""))) {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                return Dispatcher.ok();
            } catch (Exception e) {
                return Dispatcher.err("" + e.getClass().getSimpleName());
            }
        });
    }
}
