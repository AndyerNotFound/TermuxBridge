package lll.bridge.termux;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/** 剪贴板读写封装。 */
final class ClipBoard {
    private final ClipboardManager cm;
    ClipBoard(Context ctx) { cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE); }

    String getNow() {
        ClipData d = cm != null ? cm.getPrimaryClip() : null;
        if (d == null || d.getItemCount() == 0) return null;
        CharSequence t = d.getItemAt(0).coerceToText(null);
        return t == null ? null : t.toString();
    }

    void set(String s) {
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("bridge", s == null ? "" : s));
    }
}
