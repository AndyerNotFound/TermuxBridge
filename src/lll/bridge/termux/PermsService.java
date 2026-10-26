package lll.bridge.termux;

import android.content.Context;

/**
 * Shizuku UserService: instantiated by the Shizuku server in its own process
 * (shell uid, or root when Shizuku runs as root). run() therefore executes
 * with shell privileges - the same identity rish uses.
 *
 * Extending the AIDL Stub keeps plain Binder semantics; the server wraps and
 * publishes it via bindUserService.
 */
public class PermsService extends IPermsHelper.Stub {

    @SuppressWarnings("unused")
    public PermsService(Context base) {
        // base is provided by the server; exec needs nothing from it
    }

    @Override
    public String run(String[] cmd) {
        try {
            Process pr = new ProcessBuilder()
                    .command(cmd)
                    .redirectErrorStream(true)
                    .start();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = pr.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return "exit=" + pr.waitFor() + "\n" + bo.toString("UTF-8");
        } catch (Throwable t) {
            return "exit=-1\n" + t;
        }
    }

    /** called by the Shizuku server on unbind(remove=true) */
    @SuppressWarnings("unused")
    public void destroy() {
        System.exit(0);
    }
}
