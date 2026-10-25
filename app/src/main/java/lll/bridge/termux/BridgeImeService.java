package lll.bridge.termux;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Termux 输入法: 一个最小可用的键盘 + 从 Termux 直接注入文本的通道。
 * 键盘只做兜底; 主要用途是 ime.commit —— 在 Termux 里算好字符串, 一次性打进别的 App。
 */
public class BridgeImeService extends InputMethodService {
    static volatile BridgeImeService INSTANCE = null;

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
    }

    @Override public void onDestroy() {
        INSTANCE = null;
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF12161B);
        int p = dp(4);
        root.setPadding(p, p, p, p);

        String[] rows = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (String row : rows) {
            LinearLayout r = new LinearLayout(this);
            for (char c : row.toCharArray()) {
                final String s = String.valueOf(c);
                r.addView(key(s, v -> commit(s), 1f));
            }
            root.addView(r);
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.addView(key(",", v -> commit(","), 1f));
        bottom.addView(key("空格", v -> commit(" "), 3f));
        bottom.addView(key("⌫", v -> backspace(), 1.2f));
        bottom.addView(key("⏎", v -> enter(), 1.2f));
        root.addView(bottom);
        return root;
    }

    private TextView key(String label, View.OnClickListener onClick, float weight) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTextColor(0xFFE8EAED);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(10), 0, dp(10));
        tv.setBackgroundColor(0xFF1E242B);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        int m = dp(2);
        lp.setMargins(m, m, m, m);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(onClick);
        return tv;
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    private void backspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else ic.deleteSurroundingText(1, 0);
    }

    private void enter() {
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

    // ---- 供桥调用 ----

    static JSONObject status(Context ctx) throws Exception {
        // targetSdk 36 读不到 Settings.Secure.enabled_input_methods, 改用 IMM 查询
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
