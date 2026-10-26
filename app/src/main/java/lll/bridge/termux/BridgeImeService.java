package lll.bridge.termux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
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
 * TermuxBridge IME: fcitx5-style keyboard over the Termux-side rime daemon
 * (rime-daemon, 127.0.0.1:8023, see pkg/rime/). Layout v2: toolbar (mode /
 * schema / collapse) + qwerty with shift + symbol layer (?123) + candidate
 * bar. Falls back to direct unicode input when the daemon is offline.
 */
public class BridgeImeService extends InputMethodService {
    static volatile BridgeImeService INSTANCE = null;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler();
    private LinearLayout candBar;
    private LinearLayout candRow;
    private TextView preeditView;
    private LinearLayout kbRoot;
    private TextView modeKey, shiftKey;
    private boolean asciiMode = false;
    private boolean shifted = false;
    private boolean symbols = false;
    private int symPage = 0;
    private boolean engineUp = true;
    private long sentSeq = -1;

    private static final int BG = 0xFF16181C;
    private static final int KEY_BG = 0xFF26292F;
    private static final int KEY_BG_DARK = 0xFF1B1E23;
    private static final int KEY_FG = 0xFFE8EAED;
    private static final int KEY_FG_DIM = 0xFF9AA0A6;
    private static final int CAND_FG = 0xFFD0D4D8;
    private static final int KEY_H = 46;     // dp, fcitx5-like key height
    private static final int KEY_GAP = 4;    // dp

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
            int bottom;
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

        kbRoot = new LinearLayout(this);
        kbRoot.setOrientation(LinearLayout.VERTICAL);
        root.addView(kbRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        candBar = buildCandBar();
        kbRoot.addView(candBar);
        kbRoot.addView(buildToolbar());
        kbRoot.addView(buildMainArea());
        applyMode();
        return root;
    }

    // ---- candidate bar ----

    private LinearLayout buildCandBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setVisibility(View.GONE);
        preeditView = new TextView(this);
        preeditView.setTextColor(KEY_FG_DIM);
        preeditView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int p = dp(10);
        preeditView.setPadding(dp(14), p, dp(14), 0);
        bar.addView(preeditView);
        HorizontalScrollView hsc = new HorizontalScrollView(this);
        hsc.setHorizontalScrollBarEnabled(false);
        hsc.setFillViewport(true);
        candRow = new LinearLayout(this);
        candRow.setOrientation(LinearLayout.HORIZONTAL);
        hsc.addView(candRow);
        bar.addView(hsc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    // ---- toolbar: [中/英] [方案] ...... [?123/返回] ----

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(6);
        bar.setPadding(dp(6), p, dp(6), p);

        TextView sch = toolKey(getString(R.string.ime_pick_schema), v -> pickSchema(v), 0);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
        sl.width = dp(58);
        bar.addView(sch, sl);

        TextView collapse = toolKey("⌄", v -> requestHideSelf(0), 1f);
        collapse.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.addView(collapse, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return bar;
    }

    private TextView toolKey(String label, View.OnClickListener l, float weight) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(KEY_FG);
        tv.setGravity(Gravity.CENTER);
        int p = dp(8);
        tv.setPadding(p, p / 2, p, p / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(KEY_BG_DARK);
        bg.setCornerRadius(dp(8));
        tv.setBackground(bg);
        tv.setOnClickListener(l);
        return tv;
    }

    // ---- main area: qwerty / symbols + bottom row ----

    private View buildMainArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        int side = dp(3);
        area.setPadding(side, 0, side, side);
        area.addView(buildQwerty());
        return area;
    }

    private LinearLayout keyRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(KEY_H));
        lp.setMargins(0, dp(KEY_GAP), 0, 0);
        r.setLayoutParams(lp);
        return r;
    }

    /** fcitx5 AlphabetKey: main char on tap, alt char on swipe-up (per-letter map) */
    private static final String[][] QWERTY = {
        {"q1", "w2", "e3", "r4", "t5", "y6", "u7", "i8", "o9", "p0"},
        {"a@", "s*", "d+", "f-", "g=", "h/", "j#", "k(", "l)"},
        {"z'", "x:", "c\"", "v?", "b!", "n~", "m\\"}
    };

    private View buildQwerty() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int ri = 0; ri < QWERTY.length; ri++) {
            LinearLayout r = keyRow();
            if (ri == 2) {
                shiftKey = cap("", 1.5f, false);
                shiftKey.setOnClickListener(v -> tapShift());
                r.addView(shiftKey, lp(1.5f));
            }
            for (String pair : QWERTY[ri]) {
                r.addView(altKey(pair.charAt(0), pair.charAt(1)), lp(1f));
            }
            if (ri == 2) {
                TextView bk = backspaceKey(1.5f);
                r.addView(bk, lp(1.5f));
            }
            grid.addView(r);
        }
        grid.addView(bottomRow());
        return grid;
    }

    private View buildSymbols() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"@", "#", "\u00a5", "%", "&", "*", "-", "+", "=", "/"},
            {"[SYM2]", "(", ")", ":", ";", "'", "\"", "?", "!", "⌫"},
        };
        for (String[] row : rows) {
            LinearLayout r = keyRow();
            for (String k : row) {
                if (k.equals("⌫")) {
                    r.addView(backspaceKey(1.5f), lp(1.5f));
                } else if (k.equals("[SYM2]")) {
                    TextView t = cap("中文", 1.5f, true);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    t.setOnClickListener(v -> { symPage = 1; rebuildMain(); });
                    r.addView(t, lp(1.5f));
                } else {
                    TextView t = cap(k, 1f, true);
                    t.setOnClickListener(v -> onKey(k.charAt(0)));
                    r.addView(t, lp(1f));
                }
            }
            grid.addView(r);
        }
        grid.addView(bottomRow());
        return grid;
    }

    private View buildSymZh() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {
            {"\uff0c", "\u3002", "\uff1f", "\uff01", "\uff1a", "\uff1b", "\u3001", "\u2026\u2026", "\u2014\u2014", "\u00b7"},
            {"\u300c", "\u300d", "\uff08", "\uff09", "\u300a", "\u300b", "\u3010", "\u3011", "[", "]"},
            {"[SYM1]", "{", "}", "\u201c", "\u201d", "\u2018", "\u2019", "~", "@", "⌫"},
        };
        for (String[] row : rows) {
            LinearLayout r = keyRow();
            for (String k : row) {
                if (k.equals("⌫")) {
                    r.addView(backspaceKey(1.5f), lp(1.5f));
                } else if (k.equals("[SYM1]")) {
                    TextView t = cap("?123", 1.5f, true);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    t.setOnClickListener(v -> { symPage = 0; rebuildMain(); });
                    r.addView(t, lp(1.5f));
                } else {
                    TextView t = cap(k, 1f, true);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    t.setOnClickListener(v -> onKey(k.charAt(0)));
                    r.addView(t, lp(1f));
                }
            }
            grid.addView(r);
        }
        grid.addView(bottomRow());
        return grid;
    }

    private LinearLayout bottomRow() {
        LinearLayout r = keyRow();
        modeKey = cap("", 1.5f, false);
        modeKey.setOnClickListener(v -> toggleAscii());
        modeKey.setOnLongClickListener(v -> { pickSchema(v); return true; });
        r.addView(modeKey, lp(1.5f));
        TextView sy = cap(symbols ? (symPage == 0 ? "abc" : "?123") : "?123", 1.5f, true);
        sy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sy.setOnClickListener(v -> {
            if (!symbols) { symbols = true; symPage = 0; }
            else if (symPage == 0) { symPage = 1; }
            else { symbols = false; }
            rebuildMain();
        });
        r.addView(sy, lp(1.5f));
        TextView sp = cap(getString(R.string.ime_key_space), 5f, true);
        sp.setOnClickListener(v -> onKey(' '));
        r.addView(sp, lp(5f));
        TextView dot = cap(".", 1f, true);
        dot.setOnClickListener(v -> onKey('.'));
        r.addView(dot, lp(1f));
        TextView en = cap("⏎", 1.5f, true);
        en.setOnClickListener(v -> enter());
        r.addView(en, lp(1.5f));
        return r;
    }

    /** letter key with top-right alt label; tap = letter, swipe up = alt */
    private View altKey(char main, char alt) {
        FrameLayout w = new FrameLayout(this);
        TextView m = cap(String.valueOf(main), 1f, true);
        w.addView(m, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView a = new TextView(this);
        a.setText(String.valueOf(alt));
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        a.setTextColor(KEY_FG_DIM);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        alp.setMargins(0, dp(4), dp(6), 0);
        w.addView(a, alp);
        w.setTag(m);
        w.setOnTouchListener(newSwipe(w, main, alt));
        return w;
    }

    /** fcitx5-style gesture: tap fires main, swipe up (>= 22dp) fires alt */
    private View.OnTouchListener newSwipe(View w, final char main, final char alt) {
        final float[] downY = {0};
        final boolean[] fired = {false};
        return (v, ev) -> {
            TextView m = (TextView) w.getTag();
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY[0] = ev.getY();
                    fired[0] = false;
                    v.setPressed(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!fired[0] && downY[0] - ev.getY() > dp(22)) {
                        fired[0] = true;
                        v.setPressed(false);
                        onKey(alt);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (!fired[0]) {
                        boolean up = shifted && !symbols && Character.isLetter(main);
                        onKey(up ? Character.toUpperCase(main) : main);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
            }
            return false;
        };
    }

    /** backspace with long-press auto-repeat; rime deletes composition, local deletes text */
    private TextView backspaceKey(float weight) {
        TextView bk = cap("⌫", weight, true);
        final Runnable[] tick = {null};
        final Handler h = new Handler();
        tick[0] = new Runnable() {
            @Override public void run() { doBackspace(); h.postDelayed(this, 60); }
        };
        bk.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    doBackspace();
                    h.postDelayed(tick[0], 380);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    h.removeCallbacks(tick[0]);
                    return true;
            }
            return false;
        });
        return bk;
    }

    private void doBackspace() {
        if (!asciiMode && engineUp && !RimeBridge.preedit.isEmpty()) {
            send(jq("op", "key", "key", 0xff08), this::backspace);
        } else {
            backspace();
        }
    }

    /** shift: tap = once (auto release), tap again within 300ms = lock */
    private long lastShiftTap = 0;
    private boolean shiftLock = false;

    private void tapShift() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastShiftTap < 300) {
            shiftLock = true;
            shifted = true;
        } else if (shiftLock) {
            shiftLock = false;
            shifted = false;
        } else {
            shifted = !shifted;
        }
        lastShiftTap = now;
        paintShift();
    }

    private void rebuildMain() {
        LinearLayout area = (LinearLayout) kbRoot.getChildAt(kbRoot.getChildCount() - 1);
        area.removeAllViews();
        area.addView(symbols ? (symPage == 0 ? buildSymbols() : buildSymZh()) : buildQwerty());
        paintShift();
        applyMode();
    }

    // ---- key cap visuals ----

    private TextView cap(String label, float weight, boolean shaped) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 2 ? 14 : 18);
        tv.setTextColor(KEY_FG);
        tv.setGravity(Gravity.CENTER);
        tv.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(shaped ? KEY_BG : KEY_BG_DARK);
        bg.setCornerRadius(dp(8));
        tv.setBackground(bg);
        if (shaped) {
            tv.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                    null, bg));
        }
        return tv;
    }

    private LinearLayout.LayoutParams lp(float weight) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        l.setMargins(dp(KEY_GAP), 0, 0, 0);
        return l;
    }

    private void paintShift() {
        if (shiftKey == null) return;
        shiftKey.setText(shiftLock ? "\u21ea" : (shifted ? "\u21e7" : "\u21e7"));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(shifted ? (shiftLock ? 0xFF4A5A8A : 0xFF3A4048) : KEY_BG_DARK);
        bg.setCornerRadius(dp(8));
        shiftKey.setBackground(bg);
        if (!symbols) {
            LinearLayout area = (LinearLayout) kbRoot.getChildAt(kbRoot.getChildCount() - 1);
            LinearLayout grid = (LinearLayout) area.getChildAt(0);
            int[][] ranges = {{0, 10}, {0, 9}, {1, 9}};
            for (int ri = 0; ri < 3; ri++) {
                LinearLayout row = (LinearLayout) grid.getChildAt(ri);
                for (int ci = ranges[ri][0], li = 0; ci < ranges[ri][1]; ci++, li++) {
                    FrameLayout w = (FrameLayout) row.getChildAt(ci);
                    TextView m = (TextView) w.getTag();
                    String base = String.valueOf(QWERTY[ri][li].charAt(0));
                    m.setText(shifted ? base.toUpperCase() : base);
                }
            }
        }
        if (shifted && !shiftLock) {
            main.postDelayed(() -> { if (shifted && !shiftLock) { shifted = false; paintShift(); } }, 1200);
        }
    }

    // ---- bridge protocol (unchanged semantics) ----

    private void onKey(char c) {
        if (c == 0xff0d) { enter(); return; }
        char out = c;
        if (!asciiMode && engineUp) {
            long k = (c == 0xff08) ? 0xff08 : out;
            send(jq("op", "key", "key", k), () -> fallback(out));
            return;
        }
        fallback(out);
    }

    /** JSONObject.put throws a checked exception; build request maps through varargs instead */
    private static JSONObject jq(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try { o.put((String) kv[i], kv[i + 1]); }
            catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        }
        return o;
    }

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

    private void fallback(char c) {
        if (c == 0xff08) { backspace(); return; }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(String.valueOf(c), 1);
    }

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
        if (RimeBridge.page > 0) candRow.addView(pageBtn("\u25c2", false));
        if (!RimeBridge.lastPage) candRow.addView(pageBtn("\u25b8", true));
    }

    private TextView pageBtn(String arrow, final boolean next) {
        TextView tv = new TextView(this);
        tv.setText(arrow);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(KEY_FG_DIM);
        tv.setGravity(Gravity.CENTER);
        int p = dp(12);
        tv.setPadding(p, p / 2, p, p / 2);
        tv.setOnClickListener(v -> send(jq("op", "page", "dir", next ? 1 : 0), () -> {}));
        return tv;
    }

    private TextView cand(String label, int idx) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(CAND_FG);
        int p = dp(10);
        tv.setPadding(p, p / 2, p, p / 2);
        if (idx == RimeBridge.highlight) {
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextColor(0xFF8AB4F8);
        }
        if (idx >= 0) tv.setOnClickListener(v ->
                send(jq("op", "select", "index", idx), () -> {}));
        return tv;
    }

    private void refreshOffline() {
        if (candBar != null) candBar.setVisibility(View.GONE);
    }

    // ---- mode / schema ----

    private void applyMode() {
        if (modeKey != null) {
            modeKey.setText(asciiMode ? getString(R.string.ime_key_en) : getString(R.string.ime_key_cn));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(asciiMode ? KEY_BG_DARK : 0xFF2D4A5E);
            bg.setCornerRadius(dp(8));
            modeKey.setBackground(bg);
        }
    }

    private void toggleAscii() {
        asciiMode = !asciiMode;
        applyMode();
        if (engineUp) {
            send(jq("op", "option", "name", "ascii_mode", "value", asciiMode ? 1 : 0), () -> {});
        }
    }

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
                    android.view.MenuItem mi = pm.getMenu().add(name);
                    mi.setCheckable(true);
                    mi.setChecked(id.equals(current));
                    mi.setOnMenuItemClickListener(item -> {
                        send(jq("op", "schema", "id", id), () -> {});
                        return true;
                    });
                }
                pm.show();
            });
        });
    }

    // ---- direct actions ----

    private void backspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else ic.deleteSurroundingText(1, 0);
    }

    private void enter() {
        if (!asciiMode && engineUp && !RimeBridge.preedit.isEmpty()) {
            send(jq("op", "key", "key", 0xff0d), this::enter);
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
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
