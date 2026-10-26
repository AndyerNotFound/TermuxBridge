package lll.bridge.termux;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/** 相册/视频列表(MediaStore) */
final class CmdsGallery {
    private CmdsGallery() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("media.list", (ctx, p) -> {
            boolean video = "video".equals(p.optString("kind", "image"));
            int limit = Math.max(1, p.optInt("limit", 20));
            Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
                    MediaStore.MediaColumns.DATA};
            JSONArray arr = new JSONArray();
            try (Cursor c = ctx.getContentResolver().query(base, proj, null, null,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (c == null) return Dispatcher.err("query failed");
                int n = 0;
                while (c.moveToNext() && n < limit) {
                    long id = c.getLong(0);
                    JSONObject o = new JSONObject()
                            .put("id", id)
                            .put("name", c.getString(1))
                            .put("size", c.getLong(2))
                            .put("date", c.getLong(3) * 1000)
                            .put("w", c.getInt(4))
                            .put("h", c.getInt(5))
                            .put("uri", ContentUris.withAppendedId(base, id).toString());
                    String path = c.getString(6);
                    if (path != null) o.put("path", path);
                    arr.put(o);
                    n++;
                }
            } catch (SecurityException se) {
                return Dispatcher.err("gallery permission required: pm grant lll.bridge.termux android.permission.READ_MEDIA_IMAGES");
            }
            return Dispatcher.okPut("items", arr).put("count", arr.length()).put("kind", video ? "video" : "image");
        });
    }
}
