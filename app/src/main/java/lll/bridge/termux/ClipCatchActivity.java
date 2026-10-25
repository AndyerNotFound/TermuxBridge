package lll.bridge.termux;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** 透明 Activity: 拿到窗口焦点后读写剪贴板(Android 10+ 后台访问受限)。
 *  启动方式:
 *   读: am start -n lll.bridge.termux/.ClipCatchActivity
 *   写: am start -n lll.bridge.termux/.ClipCatchActivity --es write "文本" */
public class ClipCatchActivity extends Activity {
    private boolean done = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 3000);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || done) return;
        done = true;
        try {
            ClipBoard cb = new ClipBoard(this);
            Intent it = getIntent();
            String write = it == null ? null : it.getStringExtra("write");
            if (write != null) {
                cb.set(write);
                Prefs.setClipCache(this, write);
            } else {
                String t = cb.getNow();
                if (t != null) Prefs.setClipCache(this, t);
            }
        } catch (Exception ignored) {}
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 120);
    }
}
