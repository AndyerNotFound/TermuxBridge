package lll.bridge.termux;

import android.content.Context;
import android.util.TypedValue;

import java.util.HashMap;
import java.util.Map;

/**
 * MD3 color token resolution for overlay nodes. A color value that names a
 * Material 3 token ("primary", "onSurface", ...) resolves against the app
 * theme: on API 31+ the platform dynamic-color framework fills those from
 * the wallpaper (Material You), on API 28-30 the Material Components
 * fallback values apply. Any other value falls back to Util.color parsing.
 */
final class MdcTheme {
    private static final Map<String, Integer> TOKENS = new HashMap<>();
    static {
        TOKENS.put("primary", R.attr.tbColorPrimary);
        TOKENS.put("onPrimary", R.attr.tbColorOnPrimary);
        TOKENS.put("primaryContainer", R.attr.tbColorPrimaryContainer);
        TOKENS.put("onPrimaryContainer", R.attr.tbColorOnPrimaryContainer);
        TOKENS.put("secondary", R.attr.tbColorSecondary);
        TOKENS.put("onSecondary", R.attr.tbColorOnSecondary);
        TOKENS.put("secondaryContainer", R.attr.tbColorSecondaryContainer);
        TOKENS.put("onSecondaryContainer", R.attr.tbColorOnSecondaryContainer);
        TOKENS.put("tertiary", R.attr.tbColorTertiary);
        TOKENS.put("onTertiary", R.attr.tbColorOnTertiary);
        TOKENS.put("surface", R.attr.tbColorSurface);
        TOKENS.put("onSurface", R.attr.tbColorOnSurface);
        TOKENS.put("surfaceVariant", R.attr.tbColorSurfaceVariant);
        TOKENS.put("onSurfaceVariant", R.attr.tbColorOnSurfaceVariant);
        TOKENS.put("background", android.R.attr.colorBackground);
        TOKENS.put("onBackground", R.attr.tbColorOnBackground);
        TOKENS.put("error", R.attr.tbColorError);
        TOKENS.put("onError", R.attr.tbColorOnError);
        TOKENS.put("outline", R.attr.tbColorOutline);
    }

    private MdcTheme() {}

    /** Resolve a color spec: empty -> def; token name -> theme attribute; else hex. */
    static int resolve(Context ctx, String spec, int def) {
        if (spec == null || spec.isEmpty()) return def;
        Integer attr = TOKENS.get(spec);
        if (attr != null) {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(attr, tv, true)) return tv.data;
            return def;
        }
        return Util.color(spec, def);
    }
}
