package lll.bridge.termux;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;

import java.util.ArrayList;

/** 透明 Activity: 调起系统语音识别, 结果存 Prefs, 由 TCP stt.poll 读取。 */
public class SttActivity extends Activity {
    private static final int REQ = 4002;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent i = new Intent("android.speech.action.RECOGNIZE_SPEECH")
                    .putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form")
                    .putExtra("android.speech.extra.LANGUAGE", "zh-CN")
                    .putExtra("android.speech.extra.MAX_RESULTS", 1);
            startActivityForResult(i, REQ);
        } catch (Exception e) {
            saveErr("no speech recognizer installed: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) { finish(); return; }
        if (res != RESULT_OK || data == null) { saveErr("cancelled or failed"); return; }
        try {
            ArrayList<String> list = data.getStringArrayListExtra("android.speech.extra.RESULTS");
            if (list == null || list.isEmpty()) { saveErr("empty results"); return; }
            Prefs.sp(this).edit()
                    .putString("stt", list.get(0))
                    .putString("stterr", (String) null)
                    .putLong("sttts", System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            saveErr(e.getMessage());
        }
        finish();
    }

    private void saveErr(String m) {
        Prefs.sp(this).edit()
                .putString("stterr", m == null ? "error" : m)
                .putString("stt", null)
                .putLong("sttts", System.currentTimeMillis())
                .apply();
        finish();
    }
}
