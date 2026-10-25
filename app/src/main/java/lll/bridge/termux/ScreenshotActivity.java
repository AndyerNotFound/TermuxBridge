package lll.bridge.termux;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 透明 Activity: 请求 MediaProjection 授权并立即截屏一次。
 * 结果(路径/错误)写入 Prefs, 由 TCP shot.poll 读取。 */
public class ScreenshotActivity extends Activity {
    private static final int REQ = 4001;
    private MediaProjectionManager mpm;
    private int lastResult;
    private Intent lastData;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { Prefs.setShot(this, null, "no MediaProjectionManager"); finish(); return; }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) { finish(); return; }
        if (res != RESULT_OK) { Prefs.setShot(this, null, "user denied"); finish(); return; }
        lastResult = res;
        lastData = data;
        // Android 14+: MediaProjection 使用需 FGS 先行。服务已在运行(specialUse), 升级类型含 mediaProjection
        try {
            startForegroundService(new Intent(this, BridgeService.class)
                    .setAction(BridgeService.ACTION_UPGRADE_FGS));
        } catch (Exception e) {
            Prefs.setShot(this, null, "fgs failed: " + e.getMessage());
            finish();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> captureNow(lastData), 400);
    }

    private void captureNow(Intent data) {
        MediaProjection mp = null;
        VirtualDisplay vd = null;
        ImageReader reader = null;
        try {
            mp = mpm.getMediaProjection(lastResult, data);
            if (mp == null) { Prefs.setShot(this, null, "getMediaProjection null"); finish(); return; }
            // Android 14+: 必须先注册回调, 才能 createVirtualDisplay
            mp.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {}
            }, new Handler(Looper.getMainLooper()));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            final Object lock = new Object();
            final Image[] box = new Image[1];
            // 必须先挂监听器, 再创建 VirtualDisplay, 否则首帧可能丢失
            reader.setOnImageAvailableListener(r -> {
                Image im = null;
                try {
                    im = r.acquireLatestImage();
                    if (im != null) {
                        synchronized (lock) { if (box[0] == null) { box[0] = im; lock.notifyAll(); } }
                    }
                } catch (Exception ignored) {}
            }, new Handler(Looper.getMainLooper()));
            vd = mp.createVirtualDisplay("shot", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    reader.getSurface(), null, null);
            synchronized (lock) { lock.wait(8000); }
            Image img = box[0];
            if (img == null) { Prefs.setShot(this, null, "no frame within 3s"); finish(); return; }
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int px = pl.getPixelStride(), rs = pl.getRowStride();
            Bitmap bmp = Bitmap.createBitmap(rs / px, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            img.close();
            File dir = new File(getExternalFilesDir(null), "shots");
            dir.mkdirs();
            File f = new File(dir, "shot_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fo = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            }
            Prefs.setShot(this, f.getAbsolutePath(), null);
        } catch (Exception e) {
            Prefs.setShot(this, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (vd != null) try { vd.release(); } catch (Exception ignored) {}
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            if (mp != null) try { mp.stop(); } catch (Exception ignored) {}
            finish();
        }
    }
}
