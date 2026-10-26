package lll.bridge.termux;

import android.content.Context;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TermuxBridge IME: fcitx5-style keyboard. The engine (librime + wanxiang
 * dicts) lives in a native daemon inside Termux (rime-daemon, 127.0.0.1:8023);
 * this IME is a thin keyboard over it: composition state comes back as a
 * snapshot per keypress (RimeBridge), candidates render in a scrollable bar.
 * Falls back to direct unicode input when the daemon is offline.
 */
public class BridgeImeService extends InputMethodService {
    static volatile BridgeImeService INSTANCE = null;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler();
    private LinearLayout candBar;
    private LinearLayout candRow;
    private TextView preeditView;
    private LinearLayout kb;
    private TextView cnKey;
    private boolean asciiMode = false;
    private boolean engineUp = true;
    private long sentSeq = -1;
    private static final int BG = 0xFF12161B;
    private static final int KEY_BG = 0xFF1E242B;
    private static final int KEY_FG = 0xFFE8EAED;
    private static final int CAND_FG = 0xFFD0D4D8;

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
    }

    @Override public void onDestroy() {
        INSTANCE = null;
        io.shutdown();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** edge-to-edge: the keyboard paints its own bottom inset (navbar / gesture area) */
    @Override public View onCreateInputView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                bottom = Math.max(
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                        insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom);
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(0, 0, 0, bottom);
            return insets;
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // candidate bar: preedit line + scrollable candidate row
        candBar = new LinearLayout(this);
        candBar.setOrientation(LinearLayout.VERTICAL);
        candBar.setVisibility(View.GONE);
        preeditView = new TextView(this);
        preeditView.setTextColor(KEY_FG);
        preeditView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int pp = dp(8);
        preeditView.setPadding(pp, pp, pp, 0);
        candBar.addView(preeditView);
        HorizontalScrollView hsc = new HorizontalScrollView(this);
        hsc.setHorizontalScrollBarEnabled(false);
        candRow = new LinearLayout(this);
        candRow.setOrientation(LinearLayout.HORIZONTAL);
        hsc.addView(candRow);
        candBar.addView(hsc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // keyboard grid
        kb = new LinearLayout(this);
        kb.setOrientation(LinearLayout.VERTICAL);
        String[] rows = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (String row : rows) {
            LinearLayout r = new LinearLayout(this);
            for (char c : row.toCharArray()) {
                final String s = String.valueOf(c);
                r.addView(key(s, v -> onKey(s.charAt(0)), 1f));
            }
            kb.addView(r);
        }
        LinearLayout bottom = new LinearLayout(this);
        cnKey = key("", v -> toggleAscii(), 1.2f);
        cnKey.setOnLongClickListener(v -> { pickSchema(v); return true; });
        bottom.addView(cnKey);
        bottom.addView(key(",", v -> onKey(','), 1f));
        bottom.addView(key(getString(R.string.ime_key_space), v -> onKey(' '), 3f));
        bottom.addView(key("⌫", v -> onKey((char) 0xff08), 1.2f));
        bottom.addView(key("⏎", v -> onKey((char) 0xff0d), 1.2f));
        kb.addView(bottom);

        col.addView(candBar);
        col.addView(kb);
        paintToggle();
        return root;
    }

    private TextView key(String label, View.OnClickListener onClick, float weight) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTextColor(KEY_FG);
        tv.setGravity(Gravity.CENTER);
        int p = dp(10);
        tv.setPadding(0, p, 0, p);
        tv.setBackgroundColor(KEY_BG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        int m = dp(2);
        lp.setMargins(m, m, m, m);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(onClick);
        return tv;
    }

    // ---- key routing: engine (rime) in CN mode, direct in EN mode / fallback ----

    private void onKey(char c) {
        if (c == 0xff0d) { enter(); return; }
        if (!asciiMode && engineUp) {
            long k = (c == 0xff08) ? 0xff08 : c;
            send(new JSONObject().put("op", "key").put("key", k), () -> fallback(c));
            return;
        }
        fallback(c);
    }

    /**
     * One daemon round-trip. Socket runs on the io executor; everything that
     * touches InputConnection or views runs on the main thread. If the engine
     * did not process the key (e.g. space with empty composition) or is gone,
     * {@code fallback} types the key directly.
     */
    private void send(JSONObject req, Runnable fallback) {
        io.execute(() -> {
            JSONObject rsp = null;
            try {
                rsp = RimeBridge.request(req);
                RimeBridge.apply(rsp);
            } catch (Exception e) {
                rsp = null;
            }
            final JSONObject frsp = rsp;
            main.post(() -> {
                if (frsp == null) {
                    engineUp = false;
                    refreshOffline();
                    fallback.run();
                    return;
                }
                String committed = RimeBridge.committed;
                if (!committed.isEmpty()) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.commitText(committed, 1);
                }
                if (!frsp.optBoolean("processed", true)) fallback.run();
                refresh();
            });
        });
    }

    /** type a key without the engine; backspace keeps its selection-aware path */
    private void fallback(char c) {
        if (c == 0xff08) { backspace(); return; }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(String.valueOf(c), 1);
    }

    /** engine offline: candidate bar hidden, keyboard stays usable */
    private void refreshOffline() {
        if (candBar != null) candBar.setVisibility(View.GONE);
    }

    /** redraw candidate bar from the latest snapshot (main thread) */
    private void refresh() {
        if (candBar == null) return;
        if (RimeBridge.lastSeq == sentSeq) return;
        sentSeq = RimeBridge.lastSeq;
        boolean show = !RimeBridge.preedit.isEmpty() || RimeBridge.candText.length > 0;
        candBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        preeditView.setVisibility(RimeBridge.preedit.isEmpty() ? View.GONE : View.VISIBLE);
        preeditView.setText(RimeBridge.preedit);
        candRow.removeAllViews();
        String[] t = RimeBridge.candText;
        String[] c = RimeBridge.candComment;
        if (t.length == 0 && !RimeBridge.preview.isEmpty()) {
            candRow.addView(cand(RimeBridge.preview, -1));
        }
        for (int i = 0; i < t.length; i++) {
            String label = (i + 1) + " " + t[i] + (c[i].isEmpty() ? "" : "  " + c[i]);
            candRow.addView(cand(label, i));
        }
    }

    private TextView cand(String label, int idx) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTextColor(CAND_FG);
        int p = dp(10);
        tv.setPadding(p, p / 2, p, p / 2);
        if (idx == RimeBridge.highlight) tv.setTypeface(null, Typeface.BOLD);
        if (idx >= 0) tv.setOnClickListener(v ->
                send(new JSONObject().put("op", "select").put("index", idx), () -> {}));
        return tv;
    }

    private void paintToggle() {
        if (cnKey != null) cnKey.setText(asciiMode ? getString(R.string.ime_key_en) : getString(R.string.ime_key_cn));
    }

    private void toggleAscii() {
        asciiMode = !asciiMode;
        paintToggle();
        if (engineUp) {
            send(new JSONObject()
                    .put("op", "option").put("name", "ascii_mode").put("value", asciiMode ? 1 : 0), () -> {});
        }
    }

    /** long-press CN/EN: choose schema (wanxiang ships several) */
    private void pickSchema(View anchor) {
        if (!engineUp) return;
        io.execute(() -> {
            JSONObject rsp;
            try { rsp = RimeBridge.request(new JSONObject().put("op", "schemas")); }
            catch (Exception e) { return; }
            org.json.JSONArray list = rsp.optJSONArray("schemas");
            if (list == null || list.length() == 0) return;
            String current = rsp.optString("current", "");
            main.post(() -> {
                PopupMenu pm = new PopupMenu(this, anchor);
                for (int i = 0; i < list.length(); i++) {
                    org.json.JSONObject e = list.optJSONObject(i);
                    if (e == null) continue;
                    String id = e.optString("id", "");
                    String name = e.optString("name", id);
                    pm.getMenu().add(name);
                    pm.getMenu().getItem(pm.getMenu().size() - 1).setCheckable(true);
                    pm.getMenu().getItem(pm.getMenu().size() - 1).setChecked(id.equals(current));
                    int at = pm.getMenu().size() - 1;
                    pm.getMenu().getItem(at).setOnMenuItemClickListener(item -> {
                        send(new JSONObject().put("op", "schema").put("id", id), () -> {});
                        return true;
                    });
                }
                pm.show();
            });
        });
    }

    // ---- direct fallback (EN mode / engine offline) ----

    private void direct(char c) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(String.valueOf(c), 1);
    }

    private void backspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else ic.deleteSurroundingText(1, 0);
    }

    private void enter() {
        // engine composing? let rime handle the return key (commit raw / select)
        if (!asciiMode && engineUp && !RimeBridge.preedit.isEmpty()) {
            send(new JSONObject().put("op", "key").put("key", 0xff0d), this::enter);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_ENTER));
        }
    }

    // ---- 供桥调用 (bridge commands unchanged) ----

    static JSONObject status(Context ctx) throws Exception {
        boolean enabled = false;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (android.view.inputmethod.InputMethodInfo imi : imm.getEnabledInputMethodList())
                if (ctx.getPackageName().equals(imi.getPackageName())) enabled = true;
        } catch (Throwable ignored) {}
        String current = "";
        try {
            current = Settings.Secure.getString(ctx.getContentResolver(), "default_input_method");
        } catch (Throwable ignored) {}
        BridgeImeService s = INSTANCE;
        boolean conn = s != null && s.getCurrentInputConnection() != null;
        return Dispatcher.ok()
                .put("enabled", enabled)
                .put("selected", s != null)     // 服务被创建即说明已选中
                .put("connected", conn)
                .put("current", current == null ? "" : current);
    }

    /** 把文本提交到当前焦点输入框 */
    static boolean commitText(String text) {
        BridgeImeService s = INSTANCE;
        if (s == null) return false;
        InputConnection ic = s.getCurrentInputConnection();
        if (ic == null) return false;
        ic.commitText(text, 1);
        return true;
    }
}
