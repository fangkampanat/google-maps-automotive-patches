package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.shared.Utils;

/**
 * Driver position configuration (Left-Hand Drive vs. Right-Hand Drive).
 * <p>
 * AAOS Google Maps determines driver seating to place turn-by-turn cards, search, and controls
 * on the side closest to the steering wheel. On BYD DiLink, AOSP CarInfoManager is absent,
 * causing Maps to default to LHD (left-hand drive).
 * <p>
 * This patch auto-detects RHD from BYD system properties (e.g. persist.sys.vehicle_rudder_info = 2)
 * and allows users to manually choose Auto, RHD, or LHD from Maps' Settings.
 */
@SuppressWarnings("unused")
public final class DriverPositionPatch {
    private static final String TAG = "MorpheDriverPosition";
    private static final String PREFERENCES = "morphe_driver_position";
    private static final String KEY_MODE = "position_mode";

    public static final int MODE_AUTO = 0;
    public static final int MODE_RHD = 1;
    public static final int MODE_LHD = 2;

    private static final int[] CHOICES = {MODE_AUTO, MODE_RHD, MODE_LHD};

    /** ISO country codes where traffic drives on the left (RHD vehicles). */
    private static final Set<String> RHD_COUNTRIES = new HashSet<>(Arrays.asList(
            "TH", "GB", "UK", "JP", "AU", "NZ", "SG", "MY", "HK", "ID", "IN", "ZA", "IE", "CY", "MT", "KE"
    ));

    /** BYD phone/radio country codes for RHD markets. */
    private static final Set<String> RHD_COUNTRY_CODES = new HashSet<>(Arrays.asList(
            "66", "44", "81", "61", "64", "65", "60", "852", "62", "91", "27", "353"
    ));

    private DriverPositionPatch() {
    }

    /**
     * Hooked in vehicle info methods returning driver position enum (e.g. Lgsk;->b).
     *
     * @return true if the layout should be Right-Hand Drive (RHD), false for Left-Hand Drive (LHD).
     */
    public static boolean isRightHandDrive() {
        Context context = Utils.getContext();
        if (context != null && SafeMode.included(SafeMode.DRIVER_POSITION) && !SafeMode.on(context, SafeMode.DRIVER_POSITION)) {
            return false;
        }

        int mode = getStoredMode(context);
        boolean result;
        if (mode == MODE_RHD) {
            result = true;
        } else if (mode == MODE_LHD) {
            result = false;
        } else {
            result = detectIsRhd(context);
        }
        android.util.Log.i(TAG, "isRightHandDrive -> " + result + " (mode=" + mode + ")");
        return result;
    }

    public static int getStoredMode(Context context) {
        if (context == null) return MODE_AUTO;
        try {
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            return preferences.getInt(KEY_MODE, MODE_AUTO);
        } catch (RuntimeException exception) {
            ReportLog.w(TAG, "Could not read stored driver position mode", exception);
            return MODE_AUTO;
        }
    }

    /**
     * Auto-detects whether the current vehicle is Right-Hand Drive (RHD).
     */
    public static boolean detectIsRhd(Context context) {
        // 1. Check BYD rudder property: 1 = Left rudder (LHD), 2 = Right rudder (RHD)
        String rudder = getSystemProperty("persist.sys.vehicle_rudder_info");
        if ("2".equals(rudder)) return true;
        if ("1".equals(rudder)) return false;

        // 2. Check general Android / aftermarket head unit properties
        String seat = getSystemProperty("ro.boot.hardware.hu_driver_seat");
        if ("right".equalsIgnoreCase(seat) || "rhd".equalsIgnoreCase(seat)) return true;
        if ("left".equalsIgnoreCase(seat) || "lhd".equalsIgnoreCase(seat)) return false;

        String propSeat = getSystemProperty("persist.sys.driver_seat");
        if ("right".equalsIgnoreCase(propSeat) || "rhd".equalsIgnoreCase(propSeat)) return true;
        if ("left".equalsIgnoreCase(propSeat) || "lhd".equalsIgnoreCase(propSeat)) return false;

        // 3. Check BYD country code
        String countryCode = getSystemProperty("persist.sys.byd.countrycode");
        if (countryCode != null && RHD_COUNTRY_CODES.contains(countryCode.trim())) return true;

        String vendorCountryCode = getSystemProperty("persist.vendor.radio.byd.countrycode");
        if (vendorCountryCode != null && RHD_COUNTRY_CODES.contains(vendorCountryCode.trim())) return true;

        // 4. Check system locale
        if (context != null) {
            try {
                String country = context.getResources().getConfiguration().getLocales().get(0).getCountry();
                if (country != null && RHD_COUNTRIES.contains(country.toUpperCase())) return true;
            } catch (RuntimeException ignored) {
            }
        }

        return false;
    }

    private static String getSystemProperty(String key) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getMethod("get", String.class, String.class);
            return (String) method.invoke(null, key, "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Injection point: Settings row title. */
    public static String getSettingsTitle(Context context) {
        return isThai(context) ? "ตำแหน่งพวงมาลัย (Driving position)" : "Driving position";
    }

    /** Injection point: Settings row subtitle / summary. */
    public static String getSettingsSummary(Context context) {
        if (SafeMode.included(SafeMode.DRIVER_POSITION) && !SafeMode.on(context, SafeMode.DRIVER_POSITION)) {
            return isThai(context) ? "ใช้ค่าเริ่มต้นของรถ · สวิตช์ใน Report Maps Car" : "Default vehicle layout · switch in Report Maps Car";
        }

        boolean thai = isThai(context);
        int mode = getStoredMode(context);
        if (mode == MODE_RHD) {
            return thai ? "พวงมาลัยขวา (RHD)" : "Right-hand drive (RHD)";
        } else if (mode == MODE_LHD) {
            return thai ? "พวงมาลัยซ้าย (LHD)" : "Left-hand drive (LHD)";
        } else {
            boolean detectedRhd = detectIsRhd(context);
            if (thai) {
                return "อัตโนมัติ (ตรวจพบ: " + (detectedRhd ? "พวงมาลัยขวา" : "พวงมาลัยซ้าย") + ")";
            } else {
                return "Auto-detect (Detected: " + (detectedRhd ? "Right-hand drive" : "Left-hand drive") + ")";
            }
        }
    }

    /** Injection point: Settings row click action. */
    public static Runnable getSettingsAction(Context context, View container) {
        return () -> showChooser(context, container);
    }

    private static void showChooser(Context context, View container) {
        Activity activity = findActivity(context);
        if (activity == null && container != null) activity = findActivity(container.getContext());
        if (activity == null || activity.isFinishing()) {
            ReportLog.w(TAG, "No activity for the driving position chooser: " + context);
            return;
        }

        Activity host = activity;
        int current = getStoredMode(host);
        String[] labels = new String[CHOICES.length];
        int checked = 0;
        for (int i = 0; i < CHOICES.length; i++) {
            labels[i] = choiceLabel(host, CHOICES[i]);
            if (CHOICES[i] == current) checked = i;
        }

        new AlertDialog.Builder(host)
                .setTitle(getSettingsTitle(host))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (CHOICES[which] != current) confirmRestart(host, CHOICES[which]);
                })
                .setNegativeButton(isThai(host) ? "ยกเลิก" : "Cancel", null)
                .show();
    }

    private static void confirmRestart(Activity activity, int chosenMode) {
        boolean thai = isThai(activity);
        String label = choiceLabel(activity, chosenMode);
        new AlertDialog.Builder(activity)
                .setTitle(getSettingsTitle(activity))
                .setMessage(thai
                        ? "Maps จะเริ่มใหม่เพื่อใช้ " + label + " การนำทางที่กำลังทำงานจะหยุด"
                        : "Maps restarts to use " + label + ". Active navigation stops.")
                .setPositiveButton(thai ? "เริ่มใหม่" : "Restart", (dialog, which) -> {
                    boolean saved = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_MODE, chosenMode).commit();
                    ReportLog.i(TAG, "Driving position mode set to " + chosenMode + " (saved " + saved + "), restarting");
                    Utils.restartApp(activity);
                })
                .setNegativeButton(thai ? "ยกเลิก" : "Cancel", null)
                .show();
    }

    private static String choiceLabel(Context context, int mode) {
        boolean thai = isThai(context);
        if (mode == MODE_RHD) {
            return thai ? "พวงมาลัยขวา (RHD)" : "Right-hand drive (RHD)";
        } else if (mode == MODE_LHD) {
            return thai ? "พวงมาลัยซ้าย (LHD)" : "Left-hand drive (LHD)";
        } else {
            boolean detectedRhd = detectIsRhd(context);
            if (thai) {
                return "อัตโนมัติ (ตรวจพบ: " + (detectedRhd ? "พวงมาลัยขวา" : "พวงมาลัยซ้าย") + ")";
            } else {
                return "Auto-detect (Detected: " + (detectedRhd ? "Right-hand drive" : "Left-hand drive") + ")";
            }
        }
    }

    static boolean isThai(Context context) {
        try {
            return "th".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
