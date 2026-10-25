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
        TOKENS.put("primary", com.google.android.material.R.attr.colorPrimary);
        TOKENS.put("onPrimary", com.google.android.material.R.attr.colorOnPrimary);
        TOKENS.put("primaryContainer", com.google.android.material.R.attr.colorPrimaryContainer);
        TOKENS.put("onPrimaryContainer", com.google.android.material.R.attr.colorOnPrimaryContainer);
        TOKENS.put("secondary", com.google.android.material.R.attr.colorSecondary);
        TOKENS.put("onSecondary", com.google.android.material.R.attr.colorOnSecondary);
        TOKENS.put("secondaryContainer", com.google.android.material.R.attr.colorSecondaryContainer);
        TOKENS.put("onSecondaryContainer", com.google.android.material.R.attr.colorOnSecondaryContainer);
        TOKENS.put("tertiary", com.google.android.material.R.attr.colorTertiary);
        TOKENS.put("onTertiary", com.google.android.material.R.attr.colorOnTertiary);
        TOKENS.put("surface", com.google.android.material.R.attr.colorSurface);
        TOKENS.put("onSurface", com.google.android.material.R.attr.colorOnSurface);
        TOKENS.put("surfaceVariant", com.google.android.material.R.attr.colorSurfaceVariant);
        TOKENS.put("onSurfaceVariant", com.google.android.material.R.attr.colorOnSurfaceVariant);
        TOKENS.put("background", android.R.attr.colorBackground);
        TOKENS.put("onBackground", com.google.android.material.R.attr.colorOnBackground);
        TOKENS.put("error", com.google.android.material.R.attr.colorError);
        TOKENS.put("onError", com.google.android.material.R.attr.colorOnError);
        TOKENS.put("outline", com.google.android.material.R.attr.colorOutline);
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
