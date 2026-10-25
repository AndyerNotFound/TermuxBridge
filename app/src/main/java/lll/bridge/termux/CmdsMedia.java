package lll.bridge.termux;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

/** 截图/STT 结果轮询 + 录音(PCM→WAV base64)。 */
final class CmdsMedia {
    private CmdsMedia() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        // screenshot / stt: 由 am start 的透明 Activity 执行, 结果存 Prefs, 这里轮询
        r.put("shot.poll", (ctx, p) -> {
            long ts = Prefs.shotTs(ctx);
            if (ts == 0) return Dispatcher.err("no screenshot yet; run: am start -n lll.bridge.termux/.ScreenshotActivity");
            long age = System.currentTimeMillis() - ts;
            if (age > 120000) return Dispatcher.err("screenshot expired (>120s), retake");
            android.content.SharedPreferences sp = Prefs.sp(ctx);
            String err = sp.getString("shoterr", null);
            if (err != null) return Dispatcher.err("screenshot failed: " + err);
            String path = sp.getString("shot", null);
            if (path == null) return Dispatcher.err("screenshot pending, poll again");
            File f = new File(path);
            if (!f.exists()) return Dispatcher.err("screenshot file missing");
            try (FileInputStream fi = new FileInputStream(f)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
                JSONObject o = Dispatcher.okPut("png_b64", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                if (p.optBoolean("delete", true)) f.delete();
                return o;
            }
        });

        // 无障碍能力: 无授权弹窗截图 / 模拟触控 / UI 树
        r.put("shot", (ctx, p) -> {
            if (!BridgeA11yService.ready())
                return Dispatcher.err("无障碍服务未启用。请到 系统设置 > 无障碍 > TermuxBridge 打开,"
                        + " 或运行: tb a11y-on");
            return BridgeA11yService.screenshot();
        });

        r.put("tap", (ctx, p) -> {
            float x = (float) p.optDouble("x", -1), y = (float) p.optDouble("y", -1);
            if (x < 0 || y < 0) return Dispatcher.err("need x,y");
            return BridgeA11yService.tap(x, y) ? Dispatcher.ok() : Dispatcher.err("gesture cancelled");
        });

        r.put("swipe", (ctx, p) -> {
            float x1 = (float) p.optDouble("x1", -1), y1 = (float) p.optDouble("y1", -1);
            float x2 = (float) p.optDouble("x2", -1), y2 = (float) p.optDouble("y2", -1);
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return Dispatcher.err("need x1,y1,x2,y2");
            int ms = p.optInt("ms", 300);
            return BridgeA11yService.swipe(x1, y1, x2, y2, ms) ? Dispatcher.ok() : Dispatcher.err("gesture cancelled");
        });

        r.put("ui", (ctx, p) -> {
            if (!BridgeA11yService.ready()) return Dispatcher.err("accessibility service not enabled");
            return Dispatcher.okPut("nodes", BridgeA11yService.dumpTree(p.optInt("depth", 12)));
        });

        r.put("text.set", (ctx, p) -> {
            if (!BridgeA11yService.ready()) return Dispatcher.err("accessibility service not enabled");
            String text = p.optString("text", "");
            boolean ok = BridgeA11yService.setText(text);
            if (!ok) return Dispatcher.err("没有可写的输入框(先点一下目标框)");
            return Dispatcher.ok().put("len", text.length()).put("via", "a11y");
        });

        r.put("text.debug", (ctx, p) -> Dispatcher.okPut("diag", BridgeA11yService.diagnose()));

        r.put("text.get", (ctx, p) -> {
            if (!BridgeA11yService.ready()) return Dispatcher.err("accessibility service not enabled");
            String t = BridgeA11yService.getFocusedText();
            return t == null ? Dispatcher.err("没有焦点输入框") : Dispatcher.okPut("text", t);
        });

        r.put("a11y", (ctx, p) -> Dispatcher.ok()
                .put("enabled", BridgeA11yService.ready()));

        r.put("stt.poll", (ctx, p) -> {
            android.content.SharedPreferences sp = Prefs.sp(ctx);
            long ts = sp.getLong("sttts", 0);
            if (ts == 0) return Dispatcher.err("no stt yet; run: am start -n lll.bridge.termux/.SttActivity");
            if (System.currentTimeMillis() - ts > 300000) return Dispatcher.err("stt expired (>300s), retry");
            String err = sp.getString("stterr", null);
            if (err != null) return Dispatcher.err("stt failed: " + err);
            String text = sp.getString("stt", null);
            if (text == null) return Dispatcher.err("stt pending, poll again");
            JSONObject o = Dispatcher.okPut("text", text);
            sp.edit().remove("stt").remove("sttts").apply();
            return o;
        });

        // record: PCM 录音 → WAV → base64 (无需 Activity, 直接 FGS mic)
        r.put("record", (ctx, p) -> {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED)
                return Dispatcher.err("no RECORD_AUDIO permission");
            int ms = p.optInt("ms", 2000);
            if (ms < 200 || ms > 60000) return Dispatcher.err("ms must be 200..60000");
            int rate = p.optInt("rate", 16000);
            int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return Dispatcher.err("AudioRecord init failed");
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED)
                    return Dispatcher.err("AudioRecord not initialized (mic busy?)");
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                rec.startRecording();
                long deadline = System.currentTimeMillis() + ms;
                while (System.currentTimeMillis() < deadline) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) pcm.write(buf, 0, n);
                }
                rec.stop();
                byte[] raw = pcm.toByteArray();
                if (raw.length == 0) return Dispatcher.err("recorded silence/empty");
                // PCM → WAV
                int channels = 1, bits = 16;
                byte[] header = new byte[44];
                int dataLen = raw.length;
                writeInt(header, 0, 0x52494646); // RIFF
                writeInt(header, 4, 36 + dataLen);
                writeInt(header, 8, 0x57415645); // WAVE
                writeInt(header, 12, 0x666D7420); // fmt
                writeInt(header, 16, 16);
                writeShort(header, 20, (short) 1);
                writeShort(header, 22, (short) channels);
                writeInt(header, 24, rate);
                writeInt(header, 28, rate * channels * bits / 8);
                writeShort(header, 32, (short) (channels * bits / 8));
                writeShort(header, 34, (short) bits);
                writeInt(header, 36, 0x64617461); // data
                writeInt(header, 40, dataLen);
                ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataLen);
                wav.write(header);
                wav.write(raw);
                return Dispatcher.okPut("wav_b64",
                        Base64.encodeToString(wav.toByteArray(), Base64.NO_WRAP))
                        .put("ms", ms).put("rate", rate);
            } finally {
                if (rec != null) try { rec.release(); } catch (Exception ignored) {}
            }
        });
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off+1] = (byte)(v>>8); b[off+2] = (byte)(v>>16); b[off+3] = (byte)(v>>24);
    }
    private static void writeShort(byte[] b, int off, short v) {
        b[off] = (byte) v; b[off+1] = (byte)(v>>8);
    }
}
