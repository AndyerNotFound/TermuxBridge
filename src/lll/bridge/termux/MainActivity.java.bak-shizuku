package lll.bridge.termux;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    private static final int REQ_PERMS = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        View content = buildUi();
        setContentView(content);
        applyInsets(content);
    }

    /** targetSdk 36 enforces edge-to-edge; consume systemBars/cutout/ime insets manually */
    private void applyInsets(View v) {
        final int basePad = 40;
        if (Build.VERSION.SDK_INT >= 30) {
            v.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets b = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout()
                                | android.view.WindowInsets.Type.ime());
                view.setPadding(basePad + b.left, basePad + b.top,
                        basePad + b.right, basePad + b.bottom);
                return insets;
            });
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 60);

        TextView title = new TextView(this);
        title.setText("TermuxBridge");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        // port setting
        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText portEt = new EditText(this);
        portEt.setText(String.valueOf(Prefs.port(this)));
        portEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portEt.setHint(getString(R.string.main_hint_port));
        portRow.addView(portEt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton portBtn = new MaterialButton(this);
        portBtn.setText(getString(R.string.main_btn_save_port));
        portBtn.setOnClickListener(v -> {
            int p = 0;
            try { p = Integer.parseInt(portEt.getText().toString().trim()); } catch (Exception ignored) {}
            if (p < 1024 || p > 65535) { toast(getString(R.string.main_toast_port_range)); return; }
            Prefs.setPort(this, p);
            toast(getString(R.string.main_toast_port_saved, String.valueOf(p)));
            restartService();
        });
        portRow.addView(portBtn);
        root.addView(portRow);

        // token setting
        LinearLayout tokRow = new LinearLayout(this);
        final EditText tokEt = new EditText(this);
        tokEt.setText(Prefs.token(this));
        tokEt.setHint(getString(R.string.main_hint_token));
        tokRow.addView(tokEt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton tokBtn = new MaterialButton(this);
        tokBtn.setText(getString(R.string.main_btn_save_token));
        tokBtn.setOnClickListener(v -> {
            Prefs.setToken(this, tokEt.getText().toString().trim());
            toast(getString(R.string.main_toast_token_saved));
        });
        tokRow.addView(tokBtn);
        root.addView(tokRow);

        // permission buttons
        MaterialButton permBtn = new MaterialButton(this);
        permBtn.setText(getString(R.string.main_btn_request_perms));
        permBtn.setOnClickListener(v -> requestPerms());
        root.addView(permBtn);

        MaterialButton writeBtn = new MaterialButton(this);
        writeBtn.setText(getString(R.string.main_btn_write_settings));
        writeBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(writeBtn);

        MaterialButton ovBtn = new MaterialButton(this);
        ovBtn.setText(getString(R.string.main_btn_overlay_perm));
        ovBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(ovBtn);

        MaterialButton batBtn = new MaterialButton(this);
        batBtn.setText(getString(R.string.main_btn_battery));
        batBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.main_toast_unsupported), Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(batBtn);

        // IME test field
        final EditText imeTest = new EditText(this);
        imeTest.setHint(getString(R.string.main_hint_ime_test));
        imeTest.setSingleLine(true);
        root.addView(imeTest);

        // service control
        MaterialButton startBtn = new MaterialButton(this);
        startBtn.setText(getString(R.string.main_btn_start));
        startBtn.setOnClickListener(v -> {
            startForegroundService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_START));
            toast(getString(R.string.main_toast_start_requested));
        });
        root.addView(startBtn);
        MaterialButton stopBtn = new MaterialButton(this);
        stopBtn.setText(getString(R.string.main_btn_stop));
        stopBtn.setOnClickListener(v -> {
            startService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_STOP));
            toast(getString(R.string.main_toast_stop_requested));
        });
        root.addView(stopBtn);

        // rime engine status (checked off the main thread)
        TextView rimeTv = new TextView(this);
        rimeTv.setTextSize(13);
        root.addView(rimeTv);
        new Thread(() -> {
            boolean up = RimeBridge.available();
            runOnUiThread(() -> rimeTv.setText(getString(R.string.main_rime_engine) + ": "
                    + getString(up ? R.string.main_rime_up : R.string.main_rime_down)));
        }).start();

        // feature toggles
        TextView ft = new TextView(this);
        ft.setText(getString(R.string.main_section_features));
        ft.setPadding(0, 40, 0, 8);
        root.addView(ft);
        Map<String, String> names = new LinkedHashMap<>();
        names.put("clipboard", getString(R.string.feat_clipboard));
        names.put("notify", getString(R.string.feat_notify));
        names.put("toast", getString(R.string.feat_toast));
        names.put("beep", getString(R.string.feat_beep));
        names.put("torch", getString(R.string.feat_torch));
        names.put("bright", getString(R.string.feat_bright));
        names.put("volume", getString(R.string.feat_volume));
        names.put("battery", getString(R.string.feat_battery));
        names.put("net", getString(R.string.feat_net));
        names.put("location", getString(R.string.feat_location));
        names.put("sensors", getString(R.string.feat_sensors));
        names.put("tts", getString(R.string.feat_tts));
        names.put("stt", getString(R.string.feat_stt));
        names.put("share", getString(R.string.feat_share));
        names.put("screenshot", getString(R.string.feat_screenshot));
        for (Map.Entry<String, String> e : names.entrySet()) {
            final String key = e.getKey();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(this);
            label.setText(e.getValue());
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            SwitchMaterial sw = new SwitchMaterial(this);
            sw.setChecked(Prefs.enabled(this, key));
            sw.setOnCheckedChangeListener((btn, isChecked) -> {
                Prefs.setEnabled(this, key, isChecked);
                toast(getString(isChecked ? R.string.feat_state_enabled : R.string.feat_state_disabled, e.getValue()));
            });
            row.addView(sw);
            root.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        return sv;
    }

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) { toast(getString(R.string.main_toast_perms_granted)); return; }
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private void restartService() {
        startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
        new android.os.Handler(getMainLooper()).postDelayed(() ->
                startForegroundService(new Intent(this, BridgeService.class)
                        .setAction(BridgeService.ACTION_START)), 800);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}