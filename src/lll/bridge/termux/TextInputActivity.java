package lll.bridge.termux;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Transparent full-screen activity that collects text for overlay textinput
 * fields. Overlay windows never take focus (that would swallow system back and
 * other IME interactions), so a tap on an overlay field opens this activity:
 * a real focused window where the IME, back navigation and focus behave exactly
 * like a normal app. The result is written back through
 * OverlayManager.completeTextInput(window, nodeId, text).
 *
 * Extras: window, node, title, preset
 */
public class TextInputActivity extends Activity {
    private String win;
    private String node;
    private TextInputEditText field;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent it = getIntent();
        win = it.getStringExtra("window");
        node = it.getStringExtra("node");
        String title = it.getStringExtra("title");
        String preset = it.getStringExtra("preset");

        float d = getResources().getDisplayMetrics().density;

        // the UP of the tap that opened us lands here a few ms later, so
        // outside-tap-to-cancel ignores anything inside a 300 ms grace window
        final long openAt = android.os.SystemClock.uptimeMillis();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7FDFE);
        bg.setCornerRadius(24 * d);
        card.setBackground(bg);
        card.setElevation(8 * d);
        card.setPadding(dp(20 * d), dp(18 * d), dp(20 * d), dp(12 * d));

        TextView tv = new TextView(this);
        tv.setText(title != null && !title.isEmpty() ? title : getString(R.string.editor_title_default));
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(0xFF171D1D);
        card.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextInputLayout til = new TextInputLayout(this);
        til.setHint(getString(R.string.editor_hint));
        field = new TextInputEditText(til.getContext());
        field.setText(preset == null ? "" : preset);
        field.setSelection(field.getText() == null ? 0 : field.getText().length());
        field.setTextSize(15);
        field.setTextColor(0xFF171D1D);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, actionId, ev) -> {
            submit();
            return true;
        });
        til.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8 * d);
        card.addView(til, tlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Button cancel = new Button(this);
        cancel.setText(getString(R.string.editor_btn_cancel));
        cancel.setTextColor(0xFF006972);
        cancel.setOnClickListener(v -> finishWith(null));
        Button ok = new Button(this);
        ok.setText(getString(R.string.editor_btn_ok));
        ok.setTextColor(0xFF006972);
        ok.setOnClickListener(v -> submit());
        row.addView(cancel);
        row.addView(ok);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4 * d);
        card.addView(row, rlp);

        card.setOnClickListener(v -> { /* swallow: taps on the card never cancel */ });

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(Math.min(300 * d, getResources().getDisplayMetrics().widthPixels * 0.9f)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        root.addView(card, clp);

        // taps on the card are consumed by its children; anything that reaches
        // the root is by definition outside the card
        root.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                if (ev.getEventTime() <= openAt + 300) return false;   // launching gesture
                return true;
            }
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) finishWith(null);
            return true;
        });

        setContentView(root);

        // Android 13+ routes back through OnBackInvokedDispatcher: the
        // deprecated onBackPressed() override is never called there
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> finishWith(null));
        }

        // resize (not pan) so the IME never covers the card
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        field.postDelayed(() -> {
            field.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    private int dp(float v) {
        return Math.round(v);
    }

    private void submit() {
        String t = field.getText() == null ? "" : field.getText().toString();
        finishWith(t);
    }

    private void finishWith(String text) {
        OverlayManager.completeTextInput(win, node, text);
        finish();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        OverlayManager.releaseEditor();
    }

    @Override
    public void onBackPressed() {
        finishWith(null);
    }
}
