package lll.bridge.termux;

import org.json.JSONArray;
import org.json.JSONObject;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client for the Termux-side rime-daemon (abstract unix socket
 * "lll.bridge.termux.rime", one line-JSON request -> one line-JSON response,
 * same framing as the main bridge).
 *
 * The app cannot link librime (Termux libs live outside the app sandbox),
 * so the engine runs as a native daemon in Termux and the IME talks to it
 * over the local socket. Every mutating op returns a full context snapshot;
 * apply() copies it into volatile fields the IME reads on the main thread.
 * All methods block - call from a background thread only.
 */
public final class RimeBridge {
    public static final String SOCKET_NAME = "lll.bridge.termux.rime";
    private static final int CONNECT_MS = 400;
    private static final int READ_MS = 1200;

    // latest snapshot (single writer: io executor; reader: main thread)
    public static volatile boolean processed = true;
    public static volatile String preedit = "";
    public static volatile String preview = "";
    public static volatile String committed = "";
    public static volatile int highlight = 0;
    public static volatile int page = 0;
    public static volatile boolean lastPage = true;
    public static volatile String[] candText = new String[0];
    public static volatile String[] candComment = new String[0];
    public static volatile long lastSeq = 0;
    private static final AtomicLong SEQ = new AtomicLong();

    private RimeBridge() {}

    public static JSONObject request(JSONObject req) throws Exception {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            OutputStream os = s.getOutputStream();
            os.write((req.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) throw new java.io.IOException("empty response");
            return new JSONObject(line);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** copy a response snapshot into the volatile fields; returns committed text */
    public static String apply(JSONObject o) {
        processed = o.optBoolean("processed", true);
        preedit = o.optString("preedit", "");
        preview = o.optString("preview", "");
        committed = o.optString("committed", "");
        highlight = o.optInt("highlight", 0);
        page = o.optInt("page", 0);
        lastPage = o.optBoolean("last", true);
        JSONArray arr = o.optJSONArray("cands");
        int n = arr == null ? 0 : arr.length();
        String[] t = new String[n];
        String[] c = new String[n];
        for (int i = 0; i < n; i++) {
            JSONObject e = arr.optJSONObject(i);
            t[i] = e == null ? "" : e.optString("t", "");
            c[i] = e == null ? "" : e.optString("c", "");
        }
        candText = t;
        candComment = c;
        lastSeq = SEQ.incrementAndGet();
        return committed;
    }

    public static boolean available() {
        try {
            return request(new JSONObject().put("op", "ping")).optBoolean("ok");
        } catch (Exception e) {
            return false;
        }
    }
}
