package lll.bridge.termux;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String[] FEATURES = {
        "clipboard", "notify", "toast", "vibrate", "beep", "torch",
        "brightness", "volume", "battery", "net", "location", "sensors",
        "tts", "stt", "share", "screenshot", "touch"
    };

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c, String f) {
        return sp(c).getBoolean("feat." + f, true);
    }
    public static void setEnabled(Context c, String f, boolean v) {
        sp(c).edit().putBoolean("feat." + f, v).apply();
    }
    public static int port(Context c) { return sp(c).getInt("port", 8021); }
    public static void setPort(Context c, int p) { sp(c).edit().putInt("port", p).apply(); }
    public static String token(Context c) { return sp(c).getString("token", ""); }
    public static void setToken(Context c, String t) { sp(c).edit().putString("token", t).apply(); }

    // 剪贴板焦点捕获回传缓存(ClipCatchActivity 写入)
    public static void setClipCache(Context c, String s) {
        sp(c).edit().putString("clipcache", s).putLong("clipts", System.currentTimeMillis()).apply();
    }
    public static String clipCache(Context c, long maxAgeMs) {
        long ts = sp(c).getLong("clipts", 0);
        if (System.currentTimeMillis() - ts > maxAgeMs) return null;
        return sp(c).getString("clipcache", null);
    }
    // 截图结果回传(ScreenshotActivity 流程写入, TCP poll 读取)
    public static void setShot(Context c, String path, String error) {
        sp(c).edit().putString("shot", path).putString("shoterr", error)
             .putLong("shotts", System.currentTimeMillis()).apply();
    }
    public static long shotTs(Context c) { return sp(c).getLong("shotts", 0); }
}
