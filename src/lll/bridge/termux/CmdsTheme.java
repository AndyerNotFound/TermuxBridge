package lll.bridge.termux;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.Build;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.Map;

/** 外观: Material You 取色 / 壁纸取色 / 模糊能力 */
final class CmdsTheme {
    private static final String[] PALETTE = {
            "system_accent1_50", "system_accent1_100", "system_accent1_200", "system_accent1_500",
            "system_accent1_700", "system_accent1_900",
            "system_accent2_100", "system_accent2_500", "system_accent2_700",
            "system_accent3_100", "system_accent3_500", "system_accent3_700",
            "system_neutral1_100", "system_neutral1_500", "system_neutral1_900",
            "system_neutral2_100", "system_neutral2_500", "system_neutral2_900"
    };

    private CmdsTheme() {}

    private static String hex(int c) {
        return String.format("#%08X", c);
    }

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("theme.colors", (ctx, p) -> {
            JSONObject o = new JSONObject();
            for (String n : PALETTE) {
                int id = ctx.getResources().getIdentifier(n, "color", "android");
                if (id != 0) o.put(n, hex(ctx.getColor(id)));
            }
            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                if (Build.VERSION.SDK_INT >= 27) {
                    android.app.WallpaperColors wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                    if (wc != null) {
                        if (wc.getPrimaryColor() != null) o.put("wallpaper_primary", hex(wc.getPrimaryColor().toArgb()));
                        if (wc.getSecondaryColor() != null) o.put("wallpaper_secondary", hex(wc.getSecondaryColor().toArgb()));
                        if (wc.getTertiaryColor() != null) o.put("wallpaper_tertiary", hex(wc.getTertiaryColor().toArgb()));
                    }
                }
            } catch (Throwable ignored) {}
            return Dispatcher.okPut("colors", o);
        });

        // Resolved MD3 token values (hex) from the app theme; API 31+ = Material You
        r.put("theme.tokens", (ctx, p) ->
                Dispatcher.okPut("tokens", OverlayManager.tokens(ctx)));

        // 模糊能力: 跨窗口模糊开关 / 是否支持 / SDK
        r.put("theme.blur", (ctx, p) -> {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            boolean enabled = false;
            if (Build.VERSION.SDK_INT >= 31) {
                try { enabled = wm.isCrossWindowBlurEnabled(); } catch (Throwable ignored) {}
            }
            return Dispatcher.ok()
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("cross_window_blur_enabled", enabled)
                    .put("supported", Build.VERSION.SDK_INT >= 31)
                    .put("prop", prop("ro.surface_flinger.supports_background_blur"));
        });
    }

    @SuppressWarnings("PrivateApi")
    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) { return ""; }
    }
}
