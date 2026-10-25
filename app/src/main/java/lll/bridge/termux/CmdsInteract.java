package lll.bridge.termux;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/** 交互类: 分享/打开URL/拨号/发短信。 */
final class CmdsInteract {
    private CmdsInteract() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        // share: 文本分享 或 文件分享(base64 data 写入 cache 后经 BridgeFilesProvider)
        r.put("share", (ctx, p) -> {
            String text = p.optString("text", "");
            String data = p.optString("data", "");
            String file = p.optString("file", "");
            Intent i = new Intent(Intent.ACTION_SEND);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!data.isEmpty()) {
                String name = p.optString("filename", "share.bin");
                String mime = p.optString("mime", "application/octet-stream");
                File dir = new File(ctx.getCacheDir(), "share");
                dir.mkdirs();
                File f = new File(dir, System.currentTimeMillis() + "_" + name);
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(Base64.decode(data, Base64.DEFAULT));
                }
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://lll.bridge.termux/files/"
                        + f.getName()));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (!file.isEmpty()) {
                File f = new File(file);
                if (!f.exists() || !f.canRead()) return Dispatcher.err("file not readable by app: " + file);
                i.setType(p.optString("mime", "application/octet-stream"));
                // 只能分享本应用可读文件; Termux 私有目录不可读, 请用 data(base64)
                return Dispatcher.err("cross-uid file not readable; use base64 'data' instead");
            } else if (!text.isEmpty()) {
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
            } else {
                return Dispatcher.err("need text or data(base64)");
            }
            ctx.startActivity(Intent.createChooser(i, p.optString("title", "分享"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return Dispatcher.ok();
        });

        r.put("open", (ctx, p) -> {
            String url = p.optString("url", "");
            if (url.isEmpty()) return Dispatcher.err("missing url");
            if (!url.contains("://")) url = "https://" + url;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return Dispatcher.ok();
        });

        r.put("dial", (ctx, p) -> {
            String tel = p.optString("tel", p.optString("phone", ""));
            if (tel.isEmpty()) return Dispatcher.err("missing tel");
            ctx.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return Dispatcher.ok();
        });

        r.put("sms", (ctx, p) -> {
            String tel = p.optString("tel", p.optString("phone", ""));
            String body = p.optString("text", "");
            if (tel.isEmpty()) return Dispatcher.err("missing tel");
            Uri u = body.isEmpty() ? Uri.parse("smsto:" + tel)
                    : Uri.parse("smsto:" + tel + "?body=" + Uri.encode(body));
            ctx.startActivity(new Intent(Intent.ACTION_SENDTO, u)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return Dispatcher.ok();
        });
    }
}
