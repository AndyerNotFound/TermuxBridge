package lll.bridge.termux;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/** 应用启动 / 深链 / 关联启动 */
final class CmdsApps {
    private CmdsApps() {}

    private static String label(PackageManager pm, ApplicationInfo ai) {
        try { return String.valueOf(pm.getApplicationLabel(ai)); } catch (Exception e) { return ai.packageName; }
    }

    static void register(Map<String, Dispatcher.Cmd> r) {
        // 可启动的应用列表
        r.put("app.list", (ctx, p) -> {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            String q = p.optString("query", "").toLowerCase();
            JSONArray arr = new JSONArray();
            for (ResolveInfo ri : ris) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                String lb = label(pm, ai);
                if (!q.isEmpty() && !lb.toLowerCase().contains(q)
                        && !ai.packageName.toLowerCase().contains(q)) continue;
                arr.put(new JSONObject().put("pkg", ai.packageName).put("label", lb)
                        .put("activity", ri.activityInfo.name));
            }
            return Dispatcher.okPut("apps", arr).put("count", arr.length());
        });

        // 启动应用(可带 URI/ extras)
        r.put("app.launch", (ctx, p) -> {
            String pkg = p.optString("pkg", "");
            if (pkg.isEmpty()) return Dispatcher.err("missing pkg");
            PackageManager pm = ctx.getPackageManager();
            Intent i;
            String uri = p.optString("uri", "");
            String act = p.optString("activity", "");
            if (!uri.isEmpty()) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
            } else if (!act.isEmpty()) {
                i = new Intent(Intent.ACTION_MAIN).setClassName(pkg, act);
            } else {
                i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) return Dispatcher.err("no launch intent: " + pkg);
            }
            JSONObject ex = p.optJSONObject("extras");
            if (ex != null) {
                Bundle b = new Bundle();
                for (java.util.Iterator<String> it = ex.keys(); it.hasNext(); ) {
                    String k = it.next();
                    b.putString(k, ex.optString(k));
                }
                i.putExtras(b);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); return Dispatcher.ok().put("pkg", pkg); }
            catch (Exception e) { return Dispatcher.err("启动失败(可能被关联启动限制): " + e.getClass().getSimpleName()); }
        });

        // 应用详情页(用于打开"关联启动/自启动"开关)
        r.put("app.settings", (ctx, p) -> {
            String pkg = p.optString("pkg", ctx.getPackageName());
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); return Dispatcher.ok(); }
            catch (Exception e) { return Dispatcher.err(e.getClass().getSimpleName()); }
        });

        // 应用信息
        r.put("app.info", (ctx, p) -> {
            String pkg = p.optString("pkg", "");
            if (pkg.isEmpty()) return Dispatcher.err("missing pkg");
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            JSONObject o = new JSONObject()
                    .put("pkg", pkg)
                    .put("label", label(pm, ai))
                    .put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("enabled", ai.enabled)
                    .put("uid", ai.uid)
                    .put("target_sdk", ai.targetSdkVersion)
                    .put("version", pm.getPackageInfo(pkg, 0).versionName);
            return Dispatcher.okPut("app", o);
        });

        // 打开链接: 可指定包名(优先用某 App 打开)
        r.put("open.with", (ctx, p) -> {
            String uri = p.optString("uri", "");
            if (uri.isEmpty()) return Dispatcher.err("missing uri");
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            String pkg = p.optString("pkg", "");
            if (!pkg.isEmpty()) i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); return Dispatcher.ok().put("pkg", pkg); }
            catch (Exception e) { return Dispatcher.err("无匹配应用: " + pkg); }
        });
    }
}
