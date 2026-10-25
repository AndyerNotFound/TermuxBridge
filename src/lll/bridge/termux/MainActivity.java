package lll.bridge.termux;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
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

        final Context ctx = this;

        // 端口设置
        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText portEt = new EditText(this);
        portEt.setText(String.valueOf(Prefs.port(this)));
        portEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portEt.setHint("端口");
        portRow.addView(portEt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button portBtn = new Button(this);
        portBtn.setText("保存端口");
        portBtn.setOnClickListener(v -> {
            int p = 0;
            try { p = Integer.parseInt(portEt.getText().toString().trim()); } catch (Exception ignored) {}
            if (p < 1024 || p > 65535) { toast("端口需在 1024-65535"); return; }
            Prefs.setPort(this, p);
            toast("端口已保存: " + p + ", 重启服务生效");
            restartService();
        });
        portRow.addView(portBtn);
        root.addView(portRow);

        // token 设置
        LinearLayout tokRow = new LinearLayout(this);
        final EditText tokEt = new EditText(this);
        tokEt.setText(Prefs.token(this));
        tokEt.setHint("访问 token(可空 = 不校验)");
        tokRow.addView(tokEt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button tokBtn = new Button(this);
        tokBtn.setText("保存token");
        tokBtn.setOnClickListener(v -> {
            Prefs.setToken(this, tokEt.getText().toString().trim());
            toast("token 已保存");
        });
        tokRow.addView(tokBtn);
        root.addView(tokRow);

        // 权限按钮
        Button permBtn = new Button(this);
        permBtn.setText("申请权限(位置/录音/通知)");
        permBtn.setOnClickListener(v -> requestPerms());
        root.addView(permBtn);

        Button writeBtn = new Button(this);
        writeBtn.setText("授予 系统设置修改(亮度)");
        writeBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(writeBtn);

        Button ovBtn = new Button(this);
        ovBtn.setText("授予悬浮窗权限");
        ovBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(ovBtn);

        // 服务控制
        Button startBtn = new Button(this);
        startBtn.setText("启动服务");
        startBtn.setOnClickListener(v -> {
            startForegroundService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_START));
            toast("服务已请求启动");
        });
        root.addView(startBtn);
        Button stopBtn = new Button(this);
        stopBtn.setText("停止服务");
        stopBtn.setOnClickListener(v -> {
            startService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_STOP));
            toast("服务已请求停止");
        });
        root.addView(stopBtn);

        // 功能开关
        TextView ft = new TextView(this);
        ft.setText("功能模块 (全部默认启用)");
        ft.setPadding(0, 40, 0, 8);
        root.addView(ft);
        Map<String, String> names = new LinkedHashMap<>();
        names.put("clipboard", "剪贴板读写");
        names.put("notify", "通知栏");
        names.put("toast", "Toast 弹窗");
        names.put("beep", "提示音");
        names.put("torch", "手电筒");
        names.put("bright", "屏幕亮度");
        names.put("volume", "音量");
        names.put("battery", "电池状态");
        names.put("net", "网络状态");
        names.put("location", "GPS 定位");
        names.put("sensors", "传感器");
        names.put("tts", "TTS 朗读");
        names.put("stt", "语音识别");
        names.put("share", "分享/打开/拨号/短信");
        names.put("screenshot", "截图");
        for (Map.Entry<String, String> e : names.entrySet()) {
            String f = e.getKey();
            if ("sensors".equals(f)) {
                // sensors 覆盖 sensor.list / sensor.read 两个命令前缀
            }
            final String key = f;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(this);
            label.setText(e.getValue());
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Switch sw = new Switch(this);
            sw.setChecked(Prefs.enabled(this, f));
            sw.setOnCheckedChangeListener((btn, isChecked) -> {
                Prefs.setEnabled(this, key, isChecked);
                toast(e.getValue() + " 已" + (isChecked ? "启用" : "停用"));
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
        if (need.isEmpty()) { toast("权限已齐"); return; }
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
