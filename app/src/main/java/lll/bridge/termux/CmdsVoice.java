package lll.bridge.termux;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** TTS 朗读。 */
final class CmdsVoice {
    private static TextToSpeech tts;
    private static boolean inited = false;
    private static volatile int initStatus = -999;

    private CmdsVoice() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("tts", (ctx, p) -> {
            final String text = p.optString("text", "");
            if (text.isEmpty()) return Dispatcher.err("missing text");
            final String lang = p.optString("lang", "zh-CN");
            final float pitch = (float) p.optDouble("pitch", 1.0);
            final float rate = (float) p.optDouble("rate", 1.0);

            synchronized (CmdsVoice.class) {
                if (!inited || tts == null) {
                    final Object lock = new Object();
                    final boolean[] okFlag = new boolean[1];
                    // TextToSpeech 必须在有 Looper 的线程创建(主线程)
                    Util.main(ctx, () -> {
                        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
                            initStatus = status;
                            okFlag[0] = status == TextToSpeech.SUCCESS;
                            synchronized (lock) { lock.notifyAll(); }
                        });
                        synchronized (lock) { lock.notifyAll(); }
                    });
                    long deadline = System.currentTimeMillis() + 20000;
                    while ((tts == null || !okFlag[0]) && System.currentTimeMillis() < deadline) {
                        synchronized (lock) { lock.wait(200); }
                    }
                    if (tts == null || !okFlag[0])
                        return Dispatcher.err("tts init failed/timeout (status="
                                + (tts == null ? "null" : String.valueOf(initStatus))
                                + ", engine=" + (tts == null ? "?" : String.valueOf(tts.getDefaultEngine())) + ")");
                    inited = true;
                }
                tts.setLanguage(Locale.forLanguageTag(lang));
                tts.setPitch(pitch);
                tts.setSpeechRate(rate);
                int rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bridge");
                if (rc != TextToSpeech.SUCCESS) return Dispatcher.err("tts speak rc=" + rc);
            }
            return Dispatcher.ok();
        });
    }
}
