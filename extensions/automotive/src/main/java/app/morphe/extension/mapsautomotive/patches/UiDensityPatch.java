package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;

import app.morphe.extension.shared.Utils;

/**
 * App-only display density, chosen in Maps' Settings (row "UI density").
 * <p>
 * The AAOS layout expects windows of 930 dp or more. In split screen on the DiLink 3 head unit
 * (240 dpi) Maps gets 635 dp. A lower density for this app only makes its UI smaller and its
 * windows wider in dp, without changing other apps or the system.
 * <p>
 * The choice is stored in this app's preferences and read when the Application and each activity
 * attach their base context, so a new choice applies after the app restarts.
 */
@SuppressWarnings("unused")
public final class UiDensityPatch {
    private static final String TAG = "MorpheUiDensity";
    private static final String PREFERENCES = "morphe_ui_density";
    private static final String KEY_DENSITY_DPI = "density_dpi";
    /** Without a stored choice: 200 dpi, chosen on the Dolphin after trying 160 and 180. */
    private static final int DEFAULT_DENSITY_DPI = 200;
    /** 0 keeps the system density. */
    private static final int[] CHOICES = {0, 200, 180, 160};
    private static boolean logged;

    private UiDensityPatch() {
    }

    /** The stored choice, 200 when nothing is stored, 0 for the system density. */
    static int getTargetDensityDpi(Context context) {
        try {
            int value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getInt(KEY_DENSITY_DPI, DEFAULT_DENSITY_DPI);
            for (int choice : CHOICES) {
                if (choice == value) return value;
            }
            ReportLog.w(TAG, "Ignoring unknown stored density " + value);
        } catch (RuntimeException exception) {
            ReportLog.w(TAG, "Could not read the UI density choice", exception);
        }
        return DEFAULT_DENSITY_DPI;
    }

    /** For the problem report: the stored choice, 0 for the system density, null when nothing is stored. */
    static Integer storedDensityDpi(Context context) {
        try {
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            return preferences.contains(KEY_DENSITY_DPI) ? preferences.getInt(KEY_DENSITY_DPI, DEFAULT_DENSITY_DPI) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Injection point: attachBaseContext of the Application and of AppCompatActivity.
     * <p>
     * The override sets densityDpi only. The framework keeps such a delta when it updates the
     * configuration (MapsActivity handles screen-size changes, such as entering split screen,
     * without being recreated). Screen sizes in dp are left to the system, so they stay current.
     */
    public static Context wrap(Context base) {
        if (base == null) return null;
        int target = getTargetDensityDpi(base);
        if (target <= 0) return base;
        try {
            int system = base.getResources().getConfiguration().densityDpi;
            if (system == target) return base;
            Configuration override = new Configuration();
            override.densityDpi = target;
            Context wrapped = base.createConfigurationContext(override);
            if (!logged) {
                logged = true;
                ReportLog.i(TAG, "UI density " + target + " dpi (system " + system + " dpi)");
            }
            return wrapped;
        } catch (RuntimeException exception) {
            ReportLog.w(TAG, "Could not apply UI density " + target, exception);
            return base;
        }
    }

    // Settings row. The patch adds it to Maps' Settings list, before About & help.

    /** Injection point: the row title. */
    public static String getSettingsTitle(Context context) {
        return isThai(context) ? "ขนาดการแสดงผล (UI density)" : "UI density";
    }

    /** Injection point: the row subtitle, the current choice. */
    public static String getSettingsSummary(Context context) {
        return describedLabel(context, getTargetDensityDpi(context));
    }

    /**
     * Injection point: the row's click action. The container view of the Settings screen, if the
     * patch found one, gives the activity when the given context is not one.
     */
    public static Runnable getSettingsAction(Context context, View container) {
        return () -> showChooser(context, container);
    }

    private static void showChooser(Context context, View container) {
        Activity activity = findActivity(context);
        if (activity == null && container != null) activity = findActivity(container.getContext());
        if (activity == null || activity.isFinishing()) {
            ReportLog.w(TAG, "No activity for the UI density chooser: " + context);
            return;
        }
        Activity host = activity;
        int current = getTargetDensityDpi(host);
        String[] labels = new String[CHOICES.length];
        int checked = 0;
        for (int index = 0; index < CHOICES.length; index++) {
            labels[index] = describedLabel(host, CHOICES[index]);
            if (CHOICES[index] == current) checked = index;
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

    // The density is applied when contexts are created, so the app restarts to use a new choice.
    private static void confirmRestart(Activity activity, int densityDpi) {
        boolean thai = isThai(activity);
        String label = choiceLabel(activity, densityDpi);
        new AlertDialog.Builder(activity)
                .setTitle(getSettingsTitle(activity))
                .setMessage(thai
                        ? "Maps จะเริ่มใหม่เพื่อใช้ " + label + " การนำทางที่กำลังทำงานจะหยุด"
                        : "Maps restarts to use " + label + ". Active navigation stops.")
                .setPositiveButton(thai ? "เริ่มใหม่" : "Restart", (dialog, which) -> {
                    // commit, not apply: the process exits right after.
                    boolean saved = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_DENSITY_DPI, densityDpi).commit();
                    ReportLog.i(TAG, "UI density set to " + densityDpi + " (saved " + saved + "), restarting");
                    Utils.restartApp(activity);
                })
                .setNegativeButton(thai ? "ยกเลิก" : "Cancel", null)
                .show();
    }

    private static String choiceLabel(Context context, int densityDpi) {
        if (densityDpi > 0) return densityDpi + " dpi";
        int system = Resources.getSystem().getConfiguration().densityDpi;
        return (isThai(context) ? "ตามระบบ (" : "System (") + system + " dpi)";
    }

    /** The label with what the choice does, for the chooser and the Settings row. */
    private static String describedLabel(Context context, int densityDpi) {
        boolean thai = isThai(context);
        int dpi = densityDpi > 0 ? densityDpi : Resources.getSystem().getConfiguration().densityDpi;
        String hint = densityDpi == DEFAULT_DENSITY_DPI ? (thai ? "ค่าเริ่มต้น" : "default")
                : dpi > DEFAULT_DENSITY_DPI ? (thai ? "ใหญ่ขึ้น การ์ดอาจล้นเมื่อแบ่งจอ" : "larger, cards may not fit in split screen")
                : dpi == CHOICES[CHOICES.length - 1] ? (thai ? "เล็กที่สุด" : "smallest")
                : dpi < DEFAULT_DENSITY_DPI ? (thai ? "เล็กลง" : "smaller") : null;
        return hint == null ? choiceLabel(context, densityDpi) : choiceLabel(context, densityDpi) + " · " + hint;
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
