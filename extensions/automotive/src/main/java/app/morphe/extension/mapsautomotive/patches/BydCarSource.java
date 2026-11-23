package app.morphe.extension.mapsautomotive.patches;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Car speed and gear straight from the BYD vehicle SDK inside Maps, for {@link CarBridge}: no other app needed.
 * <p>
 * Speed: BYDAutoSpeedDevice.getCurrentSpeed(), km/h (reported to stay km/h with the
 * cluster in miles). Gear: BYDAutoGearboxDevice.getGearboxAutoModeType(), 1=P 2=R 3=N 4=D 5=M 6=S. Read-only getters
 * only. As in {@link BydEnergyReader}, a process-local wrapper answers only the SDK's own permission checks for
 * these two devices; it does not grant Android platform permissions.
 */
final class BydCarSource {
    private static final String SPEED_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice";
    private static final String GEARBOX_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice";
    // Accepted SDK permission checks: these devices' own permissions, matched by prefix.
    private static final String[] ALLOWED_PREFIXES = {
            "android.permission.BYDAUTO_SPEED",
            "android.permission.BYDAUTO_GEARBOX",
    };
    /** AAOS VehicleGear values. */
    static final int GEAR_NEUTRAL = 1, GEAR_REVERSE = 2, GEAR_PARK = 4, GEAR_DRIVE = 8;

    private Object speedDevice;
    private Method speedGetter;
    private Object gearboxDevice;
    private Method gearGetter;
    private String gearStatus = "not tried";

    /** "ok" when the speed reads, else why not. The gear is optional (without it reverse is not signed). */
    String init(Context context) {
        try {
            Context scoped = new ReadContext(context.getApplicationContext());
            Class<?> speedClass = Class.forName(SPEED_CLASS);
            speedDevice = speedClass.getMethod("getInstance", Context.class).invoke(null, scoped);
            if (speedDevice == null) return "speed device null";
            speedGetter = speedClass.getMethod("getCurrentSpeed");
            double first = readSpeedKmh();
            if (Double.isNaN(first)) {
                speedDevice = null;
                return "speed device gives no reading";
            }
            try {
                Class<?> gearClass = Class.forName(GEARBOX_CLASS);
                gearboxDevice = gearClass.getMethod("getInstance", Context.class).invoke(null, scoped);
                gearGetter = gearClass.getMethod("getGearboxAutoModeType");
                gearStatus = "ok, now " + readGear();
            } catch (Throwable e) {
                gearboxDevice = null;
                gearStatus = "unavailable (" + describe(e) + ")";
            }
            return "ok (speed now " + first + " km/h; gear " + gearStatus + ")";
        } catch (Throwable e) {
            speedDevice = null;
            return "error " + describe(e);
        }
    }

    /** km/h, or NaN without a valid reading. */
    double readSpeedKmh() {
        if (speedDevice == null) return Double.NaN;
        try {
            Object v = speedGetter.invoke(speedDevice);
            if (!(v instanceof Number)) return Double.NaN;
            double kmh = ((Number) v).doubleValue();
            // Negative values are SDK error sentinels.
            return kmh >= 0 && kmh <= 400 ? kmh : Double.NaN;
        } catch (Throwable e) {
            return Double.NaN;
        }
    }

    /** AAOS VehicleGear, or 0 when unknown. */
    int readGear() {
        if (gearboxDevice == null) return 0;
        try {
            Object v = gearGetter.invoke(gearboxDevice);
            if (!(v instanceof Number)) return 0;
            switch (((Number) v).intValue()) {
                case 1: return GEAR_PARK;
                case 2: return GEAR_REVERSE;
                case 3: return GEAR_NEUTRAL;
                case 4:
                case 5:
                case 6: return GEAR_DRIVE;
                default: return 0;
            }
        } catch (Throwable e) {
            return 0;
        }
    }

    /** The application, for a source started outside an activity. */
    static Context application() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            return (Context) thread.getMethod("currentApplication").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String describe(Throwable e) {
        Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static final class ReadContext extends ContextWrapper {
        ReadContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            if (permission != null) {
                for (String prefix : ALLOWED_PREFIXES) {
                    if (permission.startsWith(prefix)) return;
                }
            }
            super.enforceCallingOrSelfPermission(permission, message);
        }
    }

    static long nowNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }
}
