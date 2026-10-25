package lll.bridge.termux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Map;

/** 消息/剪贴板/信息/硬件控制类命令。 */
final class CmdsCore {
    private CmdsCore() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("toast", (ctx, p) -> {
            final String msg = p.optString("text", "");
            if (msg.isEmpty()) return Dispatcher.err("missing text");
            Util.main(ctx, () -> Toast.makeText(ctx, msg,
                    "long".equals(p.optString("duration", "short")) ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
            return Dispatcher.ok();
        });


        r.put("vibrate", (ctx, p) -> {
            Vibrator v = ctx.getSystemService(Vibrator.class);
            if (v == null) return Dispatcher.err("no vibrator");
            long ms = (long) p.optDouble("ms", 300);
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            return Dispatcher.ok();
        });

        r.put("beep", (ctx, p) -> {
            int tone;
            switch (p.optString("type", "beep")) {
                case "success": tone = ToneGenerator.TONE_PROP_ACK; break;
                case "error":   tone = ToneGenerator.TONE_PROP_NACK; break;
                default:        tone = ToneGenerator.TONE_PROP_BEEP; break;
            }
            final int t = tone;
            final int vol = Util.clamp(p.optInt("volume", 80), 0, 100);
            final int ms = p.optInt("ms", 300);
            Util.main(ctx, () -> {
                try {
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, vol);
                    tg.startTone(t, ms);
                    // 音结束后释放; 简化: 延时关闭交给 GC(CtoneGenerator native 泄漏可接受, 低频操作)
                } catch (Exception ignored) {}
            });
            return Dispatcher.ok();
        });

        // ===== 剪贴板 =====
        r.put("clip.read", (ctx, p) -> {
            String t = new ClipBoard(ctx).getNow();
            if (t != null) return Dispatcher.okPut("text", t);
            String cache = Prefs.clipCache(ctx, 30000);
            if (cache != null) return Dispatcher.okPut("text", cache).put("source", "cache");
            return Dispatcher.err("clipboard blocked (no focus) or empty; try 'clip.catch'");
        });
        r.put("clip.write", (ctx, p) -> {
            new ClipBoard(ctx).set(p.optString("text", ""));
            return Dispatcher.ok();
        });
        r.put("clip.catch", (ctx, p) -> {
            // 广播不能启动 activity, 交由 TCP 通道处理时提示
            return Dispatcher.err("clip.catch must be invoked via TCP poll flow (see ClipCatchActivity)");
        });

        // ===== 信息 =====
        r.put("battery", (ctx, p) -> {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return Dispatcher.err("no battery data");
            int level = i.getIntExtra("level", -1), scale = i.getIntExtra("scale", -1);
            int status = i.getIntExtra("status", -1), plugged = i.getIntExtra("plugged", 0);
            String st = plugged > 0 && status == BatteryManager.BATTERY_STATUS_FULL ? "full"
                    : plugged > 0 ? "charging"
                    : status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "discharging" : "unknown";
            return Dispatcher.ok()
                    .put("level", scale > 0 ? Math.round(100f * level / scale) : -1)
                    .put("charging", plugged > 0)
                    .put("status", st)
                    .put("temp", i.getIntExtra("temperature", 0) / 10f)
                    .put("voltage", i.getIntExtra("voltage", 0) / 1000f);
        });

        r.put("net", (ctx, p) -> {
            android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
            android.net.Network net = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = net == null ? null : cm.getNetworkCapabilities(net);
            boolean online = nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            boolean metered = cm != null && cm.isActiveNetworkMetered();
            return Dispatcher.ok()
                    .put("online", online)
                    .put("metered", metered)
                    .put("wifi", nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                    .put("cellular", nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))
                    .put("vpn", nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN));
        });

        // ===== 硬件: 亮度/音量/手电筒 =====
        r.put("bright", (ctx, p) -> {
            String action = p.optString("action", "read");
            if ("write".equals(action)) {
                if (!Settings.System.canWrite(ctx)) return Dispatcher.err("need WRITE_SETTINGS grant");
                int v = Util.clamp(p.optInt("value", 128), 1, 255);
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
            }
            int cur = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            return Dispatcher.okPut("value", cur);
        });

        r.put("volume", (ctx, p) -> {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            int s = streamCode(p.optString("stream", "notification"));
            int max = am.getStreamMaxVolume(s);
            int cur = am.getStreamVolume(s);
            if ("write".equals(p.optString("action", "read"))) {
                am.setStreamVolume(s, Util.clamp(p.optInt("value", cur), 0, max), 0);
                cur = am.getStreamVolume(s);
            }
            return Dispatcher.ok().put("value", cur).put("max", max);
        });

        r.put("torch", (ctx, p) -> {
            CameraManager cam = ctx.getSystemService(CameraManager.class);
            String id = null;
            for (String c : cam.getCameraIdList()) {
                if (cam.getCameraCharacteristics(c)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    if (Boolean.TRUE.equals(cam.getCameraCharacteristics(c)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                        id = c;
                        break;
                    }
                }
            }
            if (id == null) return Dispatcher.err("no flash unit");
            boolean on = !"off".equals(p.optString("action", "on"));
            cam.setTorchMode(id, on);
            return Dispatcher.okPut("torch", on);
        });
    }

    private static int streamCode(String s) {
        switch (s) {
            case "ring":       return AudioManager.STREAM_RING;
            case "music":      return AudioManager.STREAM_MUSIC;
            case "alarm":      return AudioManager.STREAM_ALARM;
            case "system":     return AudioManager.STREAM_SYSTEM;
            case "voicecall":  return AudioManager.STREAM_VOICE_CALL;
            case "notification": default: return AudioManager.STREAM_NOTIFICATION;
        }
    }
}
