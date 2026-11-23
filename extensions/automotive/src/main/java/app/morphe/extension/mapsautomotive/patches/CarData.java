package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.view.View;

/**
 * Car data for Maps' own dead reckoning on a head unit that is not Android Automotive.
 * <p>
 * Gyro: Maps' inertial input only takes an uncalibrated gyro (type 16) or the automotive yaw-only
 * gyro, and without one it registers no inertial sensor at all. DiLink has only the plain gyro
 * (type 4). With the gyro switch on when Maps starts, Maps gets the type-4 gyro where it asks for
 * type 16 and its events are reported as type 16 (with only 3 values Maps stores no bias, right
 * for an already calibrated gyro). Turned off while Maps runs, the gyro events are reported as type
 * 4 again, which Maps' inertial input ignores; turned on, they flow again only if the gyro was
 * picked at start.
 * <p>
 * Speed: with the master switch carApi on, Maps' car-service setup believes the device is Android
 * Automotive ({@link #carServiceFeature}), so it creates its speed listener; values come from
 * the BYD vehicle SDK through {@link CarBridge}. The speed switch stops or resumes them at once.
 * <p>
 * Settings row "Car data (dead reckoning)" for gyro and speed (immediate). The shell receiver
 * sets all three: {@code am broadcast -a app.morphe.mapsautomotive.CAR_DATA -n <pkg>/<this>$ShellReceiver
 * --ez gyro true|false --ez speed true|false --ez carApi true|false}; without extras it reports.
 * carApi applies at the next start of Maps.
 */
public final class CarData {
    public static final String TAG = "MorpheCarData";
    public static final String ACTION = "app.morphe.mapsautomotive.CAR_DATA";
    static final String PREFS = "morphe_car_data";
    static final String KEY_GYRO = "gyro";
    static final String KEY_SPEED = "speed";
    static final String KEY_CAR_API = "carApi";
    private static final String AUTOMOTIVE_FEATURE = "android.hardware.type.automotive";
    private static final int TYPE_GYROSCOPE = 4;
    private static final int TYPE_GYROSCOPE_UNCALIBRATED = 16;
    private static final long GYRO_LOG_MS = 60_000L;

    private static final java.util.concurrent.atomic.AtomicLong gyroEvents = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong gyroDropped = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long gyroLogAt;
    private static volatile Boolean gyro, speed;
    /** Whether Maps' inertial input got the type-4 gyro when it was created. */
    private static volatile boolean gyroPicked;
    private static volatile boolean featureLogged;

    private CarData() {
    }

    static SharedPreferences prefs(Context context) {
        try {
            Context c = context != null ? context.getApplicationContext()
                    : (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The app's SharedPreferences file of that name, or null before the app is up. */
    static SharedPreferences prefsNamed(String name) {
        try {
            Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return c == null ? null : c.getSharedPreferences(name, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean flag(String key, boolean fallback) {
        SharedPreferences p = prefs(null);
        return p == null ? fallback : p.getBoolean(key, fallback);
    }

    static boolean flag(String key) {
        return flag(key, false);
    }

    static boolean gyroEnabled() {
        Boolean g = gyro;
        if (g == null) {
            g = flag(KEY_GYRO, true);
            gyro = g;
        }
        return g;
    }

    /** The car speed reaches Maps (default on; only matters with carApi). */
    static boolean speedEnabled() {
        Boolean s = speed;
        if (s == null) {
            s = flag(KEY_SPEED, true);
            speed = s;
        }
        return s;
    }

    private static void set(Context context, String key, boolean value) {
        SharedPreferences p = prefs(context);
        if (p != null) p.edit().putBoolean(key, value).apply();
        if (KEY_GYRO.equals(key)) gyro = value;
        if (KEY_SPEED.equals(key)) speed = value;
        ReportLog.i(TAG, key + " -> " + value + (KEY_GYRO.equals(key) && value && !gyroPicked
                ? " (the gyro was not picked when Maps started: flows after the next start)" : ""));
    }

    /** Injection point: Maps' inertial input asks for its gyro (getDefaultSensor). */
    public static Sensor defaultGyro(SensorManager manager, int type) {
        Sensor sensor = manager.getDefaultSensor(type);
        if (sensor == null && type == TYPE_GYROSCOPE_UNCALIBRATED && gyroEnabled()) {
            sensor = manager.getDefaultSensor(TYPE_GYROSCOPE);
            gyroPicked = sensor != null;
            ReportLog.i(TAG, "inertial gyro: " + (sensor == null ? "none" : sensor.getName() + " (type 4 as 16)"));
        }
        return sensor;
    }

    /** Injection point: the sensor type of an event or flush in Maps' inertial input. */
    public static int gyroType(int type) {
        if (type != TYPE_GYROSCOPE || !gyroPicked) return type;
        boolean on = gyroEnabled();
        (on ? gyroEvents : gyroDropped).incrementAndGet();
        // Once a minute: how many gyro events reached Maps' inertial input (~100/s) or were held back.
        long now = android.os.SystemClock.elapsedRealtime();
        if (gyroLogAt == 0L) {
            gyroLogAt = now;
            ReportLog.i(TAG, "gyro events " + (on ? "reach" : "held back from") + " Maps' inertial input");
        } else if (now - gyroLogAt >= GYRO_LOG_MS) {
            ReportLog.i(TAG, "gyro events last " + ((now - gyroLogAt) / 1000) + " s: to Maps " + gyroEvents.getAndSet(0)
                    + ", held back " + gyroDropped.getAndSet(0));
            gyroLogAt = now;
        }
        // Type 4 is not a type Maps' inertial input reads, so a held-back event is ignored there.
        return on ? TYPE_GYROSCOPE_UNCALIBRATED : TYPE_GYROSCOPE;
    }

    /**
     * Injection point: the automotive-feature check in front of Maps' car-service setup (the speed
     * listener among it). True while carApi is on; otherwise Maps' own answer.
     */
    public static boolean carServiceFeature(boolean original) {
        boolean on = original || CarBridge.enabled();
        if (!featureLogged) {
            featureLogged = true;
            ReportLog.i(TAG, "car-service setup: automotive feature " + original + " -> " + on);
        }
        return on;
    }

    static String state() {
        return "gyro=" + gyroEnabled() + " (picked at start: " + gyroPicked + ") speed=" + speedEnabled()
                + " carApi=" + flag(KEY_CAR_API, true) + " (carApi applies at the next start of Maps)";
    }

    // ---- Settings row ----

    /** Injection point: the row title. */
    public static String getSettingsTitle(Context context) {
        return "Car data (dead reckoning)";
    }

    /** Injection point: the row subtitle, the current state. */
    public static String getSettingsSummary(Context context) {
        if (!CarBridge.enabled()) return "Off (switch carApi on with adb, then restart Maps)";
        String g = gyroEnabled() ? (gyroPicked ? "on" : "on after restart") : "off";
        return "Gyro " + g + " · Car speed " + (speedEnabled() ? "on" : "off");
    }

    /** Injection point: the row's click action: a dialog with the two switches, applied at once. */
    public static Runnable getSettingsAction(Context context, View container) {
        return () -> {
            Activity activity = UiDensityPatch.findActivity(context);
            if (activity == null && container != null) activity = UiDensityPatch.findActivity(container.getContext());
            if (activity == null || activity.isFinishing()) {
                ReportLog.w(TAG, "No activity for the car data dialog: " + context);
                return;
            }
            Activity host = activity;
            String[] labels = {"Gyro (head unit)", "Car speed"};
            boolean[] checked = {gyroEnabled(), speedEnabled()};
            new AlertDialog.Builder(host)
                    .setTitle(getSettingsTitle(host))
                    .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                            set(host, which == 0 ? KEY_GYRO : KEY_SPEED, isChecked))
                    .setPositiveButton("OK", null)
                    .show();
        };
    }

    /** For the shell (adb), which holds android.permission.DUMP. */
    public static final class ShellReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION.equals(intent.getAction())) return;
            if (intent.hasExtra(KEY_GYRO)) set(context, KEY_GYRO, intent.getBooleanExtra(KEY_GYRO, false));
            if (intent.hasExtra(KEY_SPEED)) set(context, KEY_SPEED, intent.getBooleanExtra(KEY_SPEED, true));
            if (intent.hasExtra(KEY_CAR_API)) set(context, KEY_CAR_API, intent.getBooleanExtra(KEY_CAR_API, false));
            String result = state();
            ReportLog.i(TAG, "settings -> " + result);
            setResultData(result);
        }
    }
}
