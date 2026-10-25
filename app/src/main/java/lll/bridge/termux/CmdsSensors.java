package lll.bridge.termux;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 定位与传感器。 */
final class CmdsSensors {
    private CmdsSensors() {}

    static void register(Map<String, Dispatcher.Cmd> r) {
        r.put("location", (ctx, p) -> {
            if (!hasPerm(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    && !hasPerm(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                return Dispatcher.err("no location permission");
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            if (lm == null) return Dispatcher.err("no LocationManager");
            Location last = null;
            String best = null;
            // 取所有 provider 的最后已知位置中最新者
            for (String prov : lm.getAllProviders()) {
                try {
                    Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (last == null || l.getTime() > last.getTime())) { last = l; best = prov; }
                } catch (SecurityException ignored) {}
            }
            if (last == null) {
                // 无缓存位置: 主动请求一次 (GPS/网络), 最多等 8 秒
                final Object lock = new Object();
                final Location[] got = new Location[1];
                LocationListener li = new LocationListener() {
                    public void onLocationChanged(Location l) {
                        synchronized (lock) { if (got[0] == null) { got[0] = l; lock.notifyAll(); } }
                    }
                    public void onStatusChanged(String p, int s, Bundle b) {}
                    public void onProviderEnabled(String p) {}
                    public void onProviderDisabled(String p) {}
                };
                String prov = null;
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) prov = LocationManager.GPS_PROVIDER;
                else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) prov = LocationManager.NETWORK_PROVIDER;
                if (prov == null) return Dispatcher.err("location services disabled");
                try {
                    lm.requestLocationUpdates(prov, 0, 0, li, Looper.getMainLooper());
                    synchronized (lock) { lock.wait(8000); }
                } catch (Exception ignored) {
                } finally {
                    try { lm.removeUpdates(li); } catch (Exception ignored) {}
                }
                last = got[0];
                best = prov;
                if (last == null) return Dispatcher.err("no fix within 8s; GPS may be indoors");
            }
            return Dispatcher.ok()
                    .put("lat", last.getLatitude()).put("lon", last.getLongitude())
                    .put("accuracy", last.getAccuracy()).put("provider", best == null ? "fused" : best)
                    .put("altitude", last.hasAltitude() ? last.getAltitude() : JSONObject.NULL)
                    .put("speed", last.hasSpeed() ? last.getSpeed() : JSONObject.NULL)
                    .put("bearing", last.hasBearing() ? last.getBearing() : JSONObject.NULL)
                    .put("time", last.getTime());
        });

        r.put("sensor.list", (ctx, p) -> {
            SensorManager sm = ctx.getSystemService(SensorManager.class);
            JSONArray a = new JSONArray();
            if (sm != null) {
                for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL))
                    a.put(new JSONObject().put("name", s.getName()).put("type", s.getType())
                            .put("str", sensorName(s.getType())).put("vendor", s.getVendor()));
            }
            return Dispatcher.okPut("sensors", a);
        });

        r.put("sensor.read", (ctx, p) -> {
            SensorManager sm = ctx.getSystemService(SensorManager.class);
            int type = sensorType(p.optString("sensor", "accelerometer"));
            Sensor sn = sm == null ? null : sm.getDefaultSensor(type);
            if (sn == null) return Dispatcher.err("sensor not available: " + p.optString("sensor"));
            final Object lock = new Object();
            final float[][] box = new float[1][];
            SensorEventListener l = new SensorEventListener() {
                public void onSensorChanged(SensorEvent e) {
                    synchronized (lock) { box[0] = e.values.clone(); lock.notifyAll(); }
                }
                public void onAccuracyChanged(Sensor s, int a) {}
            };
            sm.registerListener(l, sn, SensorManager.SENSOR_DELAY_UI);
            synchronized (lock) {
                lock.wait(2000);
            }
            sm.unregisterListener(l);
            if (box[0] == null) return Dispatcher.err("no sensor event within 2s");
            JSONArray vals = new JSONArray();
            for (float f : box[0]) vals.put(round(f));
            return Dispatcher.okPut("values", vals);
        });
    }

    private static boolean hasPerm(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
    private static float round(float f) { return Math.round(f * 100f) / 100f; }

    private static String sensorName(int t) {
        switch (t) {
            case Sensor.TYPE_ACCELEROMETER: return "accelerometer";
            case Sensor.TYPE_GYROSCOPE: return "gyroscope";
            case Sensor.TYPE_MAGNETIC_FIELD: return "magnetic";
            case Sensor.TYPE_LIGHT: return "light";
            case Sensor.TYPE_PROXIMITY: return "proximity";
            case Sensor.TYPE_PRESSURE: return "pressure";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "humidity";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "temperature";
            case Sensor.TYPE_GRAVITY: return "gravity";
            default: return "type_" + t;
        }
    }

    static int sensorType(String n) {
        switch (n) {
            case "accelerometer": return Sensor.TYPE_ACCELEROMETER;
            case "gyroscope": return Sensor.TYPE_GYROSCOPE;
            case "magnetic": return Sensor.TYPE_MAGNETIC_FIELD;
            case "light": return Sensor.TYPE_LIGHT;
            case "proximity": return Sensor.TYPE_PROXIMITY;
            case "pressure": return Sensor.TYPE_PRESSURE;
            default: return Sensor.TYPE_ACCELEROMETER;
        }
    }
}
