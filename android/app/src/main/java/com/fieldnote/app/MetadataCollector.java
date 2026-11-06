package com.fieldnote.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;

/**
 * Best-effort metadata snapshots, never a prerequisite for saving a recording.
 * Activity owners permit live, while-in-use location. Service owners keep only
 * ordinary sensor observation active; they do not request background location.
 */
public final class MetadataCollector {
    private static final Object LOCK = new Object();
    private static final Map<Context, Boolean> OWNERS = new WeakHashMap<>();
    private static final Map<Integer, Reading> READINGS = new HashMap<>();
    private static final long SENSOR_STALE_MS = 10_000L;
    private static final long LOCATION_STALE_MS = 120_000L;
    private static final int[] TYPES = {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE,
        Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_AMBIENT_TEMPERATURE,
        Sensor.TYPE_PROXIMITY, Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION
    };

    private static Context application;
    private static Handler handler;
    private static SensorManager sensorManager;
    private static LocationManager locationManager;
    private static final List<Sensor> INVENTORY = new ArrayList<>();
    private static boolean inventoryKnown;
    private static boolean sensorsRegistered;
    private static boolean locationRegistered;
    private static Location latestLocation;
    private static String sensorProblem;
    private static String locationProblem;

    private MetadataCollector() { }

    /** Idempotent for each owner; pair with stop using the same Context instance. */
    public static void start(Context owner) {
        if (owner == null) return;
        try {
            synchronized (LOCK) {
                application = owner.getApplicationContext();
                if (application == null) application = owner;
                OWNERS.put(owner, Boolean.TRUE);
                if (handler == null) {
                    HandlerThread thread = new HandlerThread("FieldnoteMetadata");
                    thread.start();
                    handler = new Handler(thread.getLooper());
                }
                handler.post(MetadataCollector::reconfigure);
            }
        } catch (RuntimeException ignored) {
            // Capture must remain usable if a sensor service cannot be started.
        }
    }

    /** Stops this owner's observation; other Activity/service owners stay active. */
    public static void stop(Context owner) {
        if (owner == null) return;
        try {
            synchronized (LOCK) {
                OWNERS.remove(owner);
                if (handler != null) handler.post(MetadataCollector::reconfigure);
            }
        } catch (RuntimeException ignored) { }
    }

    private static boolean enabled(Context context) {
        try {
            return context != null && context.getSharedPreferences("fieldnote", Context.MODE_PRIVATE)
                    .getBoolean("metadata_enabled", true);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // All listener registration happens on the collector thread. Never wait for
    // a new reading on a recorder or camera thread.
    private static void reconfigure() {
        final Context app;
        final boolean hasOwners;
        final boolean hasVisibleActivity;
        synchronized (LOCK) {
            app = application;
            hasOwners = !OWNERS.isEmpty();
            boolean visible = false;
            for (Context owner : OWNERS.keySet()) {
                if (owner instanceof Activity) { visible = true; break; }
            }
            hasVisibleActivity = visible;
        }
        if (app == null) return;
        final boolean collect = hasOwners && enabled(app);
        try {
            if (sensorManager == null) sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null && !inventoryKnown) {
                List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
                synchronized (LOCK) {
                    INVENTORY.clear();
                    INVENTORY.addAll(all);
                    inventoryKnown = true;
                }
            }
            if (collect && !sensorsRegistered && sensorManager != null) {
                // Mark before individual registrations so any partial failure
                // is still cleaned up when the last owner stops.
                sensorsRegistered = true;
                for (int type : TYPES) {
                    Sensor sensor = sensorManager.getDefaultSensor(type);
                    if (sensor != null) sensorManager.registerListener(SENSOR_LISTENER, sensor,
                            SensorManager.SENSOR_DELAY_NORMAL, handler);
                }
                synchronized (LOCK) { sensorProblem = null; }
            } else if (!collect && sensorsRegistered && sensorManager != null) {
                sensorManager.unregisterListener(SENSOR_LISTENER);
                sensorsRegistered = false;
            }
        } catch (RuntimeException problem) {
            synchronized (LOCK) { sensorProblem = problem instanceof SecurityException ? "permission_denied" : "unavailable"; }
        }

        try {
            if (locationManager == null) locationManager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;
            // Re-evaluate permission each time an Activity returns or a setting changes.
            if (locationRegistered) {
                locationManager.removeUpdates(LOCATION_LISTENER);
                locationRegistered = false;
            }
            if (collect && hasVisibleActivity && hasLocationPermission(app)) {
                for (String provider : locationManager.getProviders(true)) {
                    try {
                        locationManager.requestLocationUpdates(provider, 2_000L, 0f,
                                LOCATION_LISTENER, handler.getLooper());
                        locationRegistered = true;
                    } catch (SecurityException denied) {
                        synchronized (LOCK) { locationProblem = "permission_denied"; }
                    } catch (RuntimeException unsupported) {
                        synchronized (LOCK) { locationProblem = "unavailable"; }
                    }
                }
                if (locationRegistered) synchronized (LOCK) { locationProblem = null; }
            }
        } catch (RuntimeException problem) {
            synchronized (LOCK) { locationProblem = problem instanceof SecurityException ? "permission_denied" : "unavailable"; }
        }
    }

    private static final SensorEventListener SENSOR_LISTENER = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event == null || event.sensor == null || event.values == null) return;
            synchronized (LOCK) {
                READINGS.put(event.sensor.getType(), new Reading(event.sensor.getName(),
                        event.timestamp, event.accuracy, event.values.clone()));
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private static final LocationListener LOCATION_LISTENER = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            if (location == null) return;
            synchronized (LOCK) {
                if (newer(location, latestLocation)) latestLocation = new Location(location);
            }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    /**
     * Returns immediately with existing observations. Missing fields are marked,
     * not synthesized. No permission dialog, network request, or wait for a fix.
     */
    public static JSONObject snapshot(Context context) {
        JSONObject result = new JSONObject();
        final long wallMs = System.currentTimeMillis();
        final long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        put(result, "observedAt", Instant.ofEpochMilli(wallMs).toString());
        put(result, "elapsedRealtimeNanos", elapsedNanos);
        try {
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime local = Instant.ofEpochMilli(wallMs).atZone(zone);
            JSONObject timezone = new JSONObject();
            put(timezone, "name", zone.getId());
            put(timezone, "utcOffsetMinutes", local.getOffset().getTotalSeconds() / 60);
            put(timezone, "localDateTime", local.toOffsetDateTime().toString());
            put(result, "timezone", timezone);
        } catch (RuntimeException ignored) {
            JSONObject timezone = new JSONObject();
            put(timezone, "name", TimeZone.getDefault().getID());
            put(timezone, "utcOffsetMinutes", TimeZone.getDefault().getOffset(wallMs) / 60_000);
            put(result, "timezone", timezone);
        }

        try {
            Context app = context == null ? null : context.getApplicationContext();
            if (app == null) app = context;
            if (!enabled(app)) {
                put(result, "optionalMetadata", status(app == null ? "unavailable" : "disabled"));
                put(result, "location", status(app == null ? "unavailable" : "disabled"));
                put(result, "sensors", status(app == null ? "unavailable" : "disabled"));
                put(result, "device", status(app == null ? "unavailable" : "disabled"));
                return result;
            }
            put(result, "optionalMetadata", status("enabled"));
            put(result, "device", device(app, context));
            put(result, "battery", battery(app));
            put(result, "storage", storage(app));
            put(result, "location", location(app, wallMs, elapsedNanos));
            put(result, "sensors", sensors(wallMs, elapsedNanos));
        } catch (RuntimeException problem) {
            put(result, "collectionStatus", "partially_unavailable");
            put(result, "failureType", problem.getClass().getSimpleName());
        }
        return result;
    }

    private static JSONObject device(Context app, Context owner) {
        JSONObject result = status("available");
        put(result, "manufacturer", Build.MANUFACTURER);
        put(result, "model", Build.MODEL);
        put(result, "brand", Build.BRAND);
        put(result, "hardware", Build.HARDWARE);
        put(result, "androidRelease", Build.VERSION.RELEASE);
        put(result, "sdkInt", Build.VERSION.SDK_INT);
        put(result, "securityPatch", Build.VERSION.SECURITY_PATCH);
        put(result, "supportedAbis", new JSONArray(java.util.Arrays.asList(Build.SUPPORTED_ABIS)));
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            put(result, "appVersion", info.versionName);
            put(result, "appVersionCode", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            put(result, "appVersionStatus", "unavailable");
        }
        try {
            WindowManager windows = (WindowManager) (owner == null ? app : owner).getSystemService(Context.WINDOW_SERVICE);
            Display display = windows == null ? null : windows.getDefaultDisplay();
            if (display != null) {
                int rotation = display.getRotation();
                int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180
                        : rotation == Surface.ROTATION_270 ? 270 : 0;
                put(result, "displayRotationDegrees", degrees);
                put(result, "displayRotationReference", "device-natural-orientation");
            } else put(result, "displayRotationStatus", "unavailable");
        } catch (RuntimeException ignored) {
            put(result, "displayRotationStatus", "unavailable");
        }
        return result;
    }

    private static JSONObject battery(Context app) {
        try {
            Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return status("unavailable");
            JSONObject result = status("available");
            put(result, "source", "android-sticky-battery-broadcast");
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) put(result, "levelPercent", 100.0 * level / scale);
            if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
                put(result, "temperatureCelsius", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
            if (battery.hasExtra(BatteryManager.EXTRA_VOLTAGE))
                put(result, "voltageMillivolts", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
            int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            put(result, "state", state == BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                    : state == BatteryManager.BATTERY_STATUS_FULL ? "full"
                    : state == BatteryManager.BATTERY_STATUS_DISCHARGING ? "discharging"
                    : state == BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "not_charging" : "unknown");
            put(result, "pluggedSourceFlags", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
            return result;
        } catch (RuntimeException ignored) { return status("unavailable"); }
    }

    private static JSONObject storage(Context app) {
        try {
            StatFs stats = new StatFs(app.getFilesDir().getAbsolutePath());
            JSONObject result = status("available");
            put(result, "scope", "internal-staging-filesystem-not-selected-project-provider");
            put(result, "availableBytes", stats.getAvailableBytes());
            put(result, "totalBytes", stats.getTotalBytes());
            return result;
        } catch (RuntimeException ignored) { return status("unavailable"); }
    }

    private static boolean hasLocationPermission(Context app) {
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean newer(Location candidate, Location previous) {
        if (previous == null) return true;
        if (candidate.getElapsedRealtimeNanos() > 0 && previous.getElapsedRealtimeNanos() > 0)
            return candidate.getElapsedRealtimeNanos() > previous.getElapsedRealtimeNanos();
        return candidate.getTime() > previous.getTime();
    }

    private static JSONObject location(Context app, long wallMs, long elapsedNanos) {
        if (!hasLocationPermission(app)) return status("permission_denied");
        final boolean precisePermission = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        Location best;
        String problem;
        synchronized (LOCK) {
            // Re-query Android after a precision downgrade instead of returning
            // a precise fix cached while fine permission was still granted.
            best = !precisePermission || latestLocation == null ? null : new Location(latestLocation);
            problem = locationProblem;
        }
        JSONArray enabledProviders = new JSONArray();
        try {
            LocationManager manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (manager != null) {
                for (String provider : manager.getProviders(true)) enabledProviders.put(provider);
                for (String provider : manager.getAllProviders()) {
                    try {
                        Location known = manager.getLastKnownLocation(provider);
                        if (known != null && newer(known, best)) best = known;
                    } catch (SecurityException ignored) { problem = "permission_denied"; }
                    catch (RuntimeException ignored) { problem = "unavailable"; }
                }
            }
        } catch (SecurityException denied) {
            problem = "permission_denied";
        } catch (RuntimeException unavailable) { problem = "unavailable"; }

        JSONObject result = status(best == null ? (problem == null ? "unavailable" : problem) : "available");
        put(result, "grantedPrecision", precisePermission ? "fine" : "coarse");
        put(result, "enabledProviders", enabledProviders);
        put(result, "freshnessThresholdMs", LOCATION_STALE_MS);
        put(result, "source", "latest-cached-or-last-known-fix");
        if (best == null) {
            put(result, "reason", enabledProviders.length() == 0 ? "no_enabled_location_provider" : "no_fix_received");
            return result;
        }
        long ageMs;
        if (best.getElapsedRealtimeNanos() > 0) {
            ageMs = (elapsedNanos - best.getElapsedRealtimeNanos()) / 1_000_000L;
            put(result, "ageSource", "elapsed-realtime");
        } else {
            ageMs = wallMs - best.getTime();
            put(result, "ageSource", "wall-clock-fallback");
        }
        put(result, "status", ageMs < 0 ? "clock_anomaly" : ageMs > LOCATION_STALE_MS ? "stale" : "available");
        put(result, "ageMs", ageMs < 0 ? JSONObject.NULL : ageMs);
        put(result, "fixAt", Instant.ofEpochMilli(best.getTime()).toString());
        put(result, "elapsedRealtimeNanos", best.getElapsedRealtimeNanos());
        put(result, "provider", best.getProvider());
        put(result, "latitudeDegrees", best.getLatitude());
        put(result, "longitudeDegrees", best.getLongitude());
        if (best.hasAccuracy()) put(result, "horizontalAccuracyMeters", best.getAccuracy());
        if (best.hasAltitude()) {
            put(result, "altitudeMetersWgs84", best.getAltitude());
            if (best.hasVerticalAccuracy()) put(result, "verticalAccuracyMeters", best.getVerticalAccuracyMeters());
        }
        if (best.hasSpeed()) {
            put(result, "speedMetersPerSecond", best.getSpeed());
            if (best.hasSpeedAccuracy()) put(result, "speedAccuracyMetersPerSecond", best.getSpeedAccuracyMetersPerSecond());
        }
        if (best.hasBearing()) {
            put(result, "bearingDegrees", best.getBearing());
            if (best.hasBearingAccuracy()) put(result, "bearingAccuracyDegrees", best.getBearingAccuracyDegrees());
        }
        put(result, "mock", Build.VERSION.SDK_INT >= 31 ? best.isMock() : best.isFromMockProvider());
        return result;
    }

    private static JSONObject sensors(long wallMs, long elapsedNanos) {
        final List<Sensor> inventory;
        final Map<Integer, Reading> readings;
        final boolean known;
        final String problem;
        synchronized (LOCK) {
            inventory = new ArrayList<>(INVENTORY);
            readings = new HashMap<>(READINGS);
            known = inventoryKnown;
            problem = sensorProblem;
        }
        JSONObject result = status(problem != null ? problem : known ? "available" : "not_started");
        put(result, "sampling", "snapshot-of-latest-events-not-a-continuous-data-log");
        put(result, "freshnessThresholdMs", SENSOR_STALE_MS);
        JSONArray devices = new JSONArray();
        for (Sensor sensor : inventory) {
            JSONObject descriptor = new JSONObject();
            put(descriptor, "type", sensor.getType());
            put(descriptor, "typeName", sensor.getStringType());
            put(descriptor, "name", sensor.getName());
            put(descriptor, "vendor", sensor.getVendor());
            put(descriptor, "version", sensor.getVersion());
            put(descriptor, "maximumRange", sensor.getMaximumRange());
            put(descriptor, "resolution", sensor.getResolution());
            put(descriptor, "powerMilliamp", sensor.getPower());
            put(descriptor, "minimumDelayMicroseconds", sensor.getMinDelay());
            put(descriptor, "wakeUp", sensor.isWakeUpSensor());
            devices.put(descriptor);
        }
        put(result, "inventory", devices);
        JSONArray observations = new JSONArray();
        for (int type : TYPES) {
            JSONObject observation = new JSONObject();
            put(observation, "type", type);
            put(observation, "name", sensorName(type));
            put(observation, "unit", sensorUnit(type));
            Reading reading = readings.get(type);
            if (reading == null) {
                boolean present = false;
                for (Sensor sensor : inventory) if (sensor.getType() == type) { present = true; break; }
                put(observation, "status", !known ? "not_started" : present ? "no_reading_received" : "unavailable");
            } else {
                long ageMs = (elapsedNanos - reading.timestamp) / 1_000_000L;
                put(observation, "status", ageMs < 0 ? "clock_anomaly" : ageMs > SENSOR_STALE_MS ? "stale" : "available");
                put(observation, "sensorName", reading.name);
                put(observation, "observedElapsedRealtimeNanos", reading.timestamp);
                put(observation, "ageMs", ageMs < 0 ? JSONObject.NULL : ageMs);
                if (ageMs >= 0) put(observation, "estimatedObservedAt", Instant.ofEpochMilli(wallMs - ageMs).toString());
                put(observation, "wallTimeSource", "derived-from-elapsed-time-at-snapshot");
                put(observation, "accuracy", accuracyName(reading.accuracy));
                JSONArray values = new JSONArray();
                for (float value : reading.values) values.put(Float.isFinite(value) ? value : JSONObject.NULL);
                put(observation, "values", values);
                if (type == Sensor.TYPE_ROTATION_VECTOR)
                    put(observation, "valueLayout", "x*sin(theta/2), y*sin(theta/2), z*sin(theta/2), optional cos(theta/2), optional headingAccuracyRadians");
                else if (reading.values.length >= 3)
                    put(observation, "axisConvention", "Android device-natural axes: x right, y top, z out of display; not remapped with screen rotation");
            }
            observations.put(observation);
        }
        put(result, "observations", observations);
        return result;
    }

    private static String accuracyName(int value) {
        switch (value) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH: return "high";
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM: return "medium";
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW: return "low";
            case SensorManager.SENSOR_STATUS_UNRELIABLE: return "unreliable";
            case SensorManager.SENSOR_STATUS_NO_CONTACT: return "no_contact";
            default: return "unknown";
        }
    }

    private static String sensorName(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: return "accelerometer";
            case Sensor.TYPE_GYROSCOPE: return "gyroscope";
            case Sensor.TYPE_MAGNETIC_FIELD: return "magnetic_field";
            case Sensor.TYPE_LIGHT: return "ambient_light";
            case Sensor.TYPE_PRESSURE: return "ambient_pressure";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "relative_humidity";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "ambient_temperature";
            case Sensor.TYPE_PROXIMITY: return "proximity";
            case Sensor.TYPE_ROTATION_VECTOR: return "rotation_vector";
            case Sensor.TYPE_GRAVITY: return "gravity";
            case Sensor.TYPE_LINEAR_ACCELERATION: return "linear_acceleration";
            default: return "unknown";
        }
    }

    private static String sensorUnit(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_LINEAR_ACCELERATION: return "m/s^2";
            case Sensor.TYPE_GYROSCOPE: return "rad/s";
            case Sensor.TYPE_MAGNETIC_FIELD: return "microtesla";
            case Sensor.TYPE_LIGHT: return "lux";
            case Sensor.TYPE_PRESSURE: return "hPa";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "percent";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "degrees Celsius";
            case Sensor.TYPE_PROXIMITY: return "cm";
            case Sensor.TYPE_ROTATION_VECTOR: return "dimensionless vector; optional heading accuracy in radians";
            default: return "unknown";
        }
    }

    private static JSONObject status(String name) {
        JSONObject result = new JSONObject();
        put(result, "status", name);
        return result;
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            if (value instanceof Double && !Double.isFinite((Double) value)) value = JSONObject.NULL;
            if (value instanceof Float && !Float.isFinite((Float) value)) value = JSONObject.NULL;
            target.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException ignored) { }
    }

    private static final class Reading {
        final String name;
        final long timestamp;
        final int accuracy;
        final float[] values;
        Reading(String name, long timestamp, int accuracy, float[] values) {
            this.name = name;
            this.timestamp = timestamp;
            this.accuracy = accuracy;
            this.values = values;
        }
    }
}
