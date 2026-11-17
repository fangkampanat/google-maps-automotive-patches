package app.morphe.extension.mapsautomotive.patches;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;

/**
 * The car's gear from the BYD SDK's gearbox device, for {@link FloatingMaps}. Named read-only getter
 * (getGearboxAutoModeType) by reflection; P and R come from the device's own constants. The wrapper answers only the
 * SDK's gearbox read checks and grants no Android platform permission. No setter and no listener.
 */
final class BydGear {
    static final int UNKNOWN = 0, PARK = 1, REVERSE = 2, OTHER = 3;
    private static final String DEVICE_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice";
    private static final String[] ALLOWED = {"android.permission.BYDAUTO_GEARBOX_GET", "android.permission.BYDAUTO_GEARBOX_COMMON"};
    /** The gear values the SDK reports: P 1 to M 6 on DiLink 3; anything else is no reading. */
    private static final int LOWEST = 1, HIGHEST = 6;

    private Object device;
    private Method getter;
    private int park = 1, reverse = 2;
    private String status = "not read";

    /** Returns a short status for the log and the report. Never throws. */
    String init(Context context) {
        try {
            Class<?> type = Class.forName(DEVICE_CLASS);
            park = type.getField("GEARBOX_AUTO_MODE_P").getInt(null);
            reverse = type.getField("GEARBOX_AUTO_MODE_R").getInt(null);
            if (park == reverse) return status = "gear_values_equal";
            getter = type.getMethod("getGearboxAutoModeType");
            device = type.getMethod("getInstance", Context.class)
                    .invoke(null, new ReadContext(context.getApplicationContext()));
            return status = device == null ? "gearbox_null" : "ok";
        } catch (Throwable error) {
            device = null;
            return status = "unavailable " + BydEnergyReader.describe(error);
        }
    }

    boolean ready() {
        return device != null && getter != null;
    }

    String status() {
        return status;
    }

    /** {@link #PARK}, {@link #REVERSE}, {@link #OTHER} or {@link #UNKNOWN}. Never throws. */
    int read() {
        if (!ready()) return UNKNOWN;
        try {
            Object value = getter.invoke(device);
            return value instanceof Integer ? classify((Integer) value, park, reverse) : UNKNOWN;
        } catch (Throwable error) {
            return UNKNOWN;
        }
    }

    /** Plain Java. */
    static int classify(int raw, int park, int reverse) {
        if (raw < LOWEST || raw > HIGHEST) return UNKNOWN;
        return raw == park ? PARK : raw == reverse ? REVERSE : OTHER;
    }

    static String name(int gear) {
        switch (gear) {
            case PARK:
                return "P";
            case REVERSE:
                return "R";
            case OTHER:
                return "driving";
            default:
                return "unknown";
        }
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
            if (!allowed(permission)) super.enforceCallingOrSelfPermission(permission, message);
        }

        @Override
        public void enforcePermission(String permission, int pid, int uid, String message) {
            if (!allowed(permission)) super.enforcePermission(permission, pid, uid, message);
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return allowed(permission) ? PackageManager.PERMISSION_GRANTED : super.checkCallingOrSelfPermission(permission);
        }

        @Override
        public int checkSelfPermission(String permission) {
            return allowed(permission) ? PackageManager.PERMISSION_GRANTED : super.checkSelfPermission(permission);
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return allowed(permission) ? PackageManager.PERMISSION_GRANTED : super.checkPermission(permission, pid, uid);
        }

        private static boolean allowed(String permission) {
            for (String accepted : ALLOWED) {
                if (accepted.equals(permission)) return true;
            }
            return false;
        }
    }
}
