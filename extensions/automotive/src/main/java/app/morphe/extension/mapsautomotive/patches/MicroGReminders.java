package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.settings.SharedSettings;

/**
 * Settings row for MicroG-RE's reminders when Maps starts: "MicroG-RE needs a setting" (not on the battery whitelist)
 * and "not running in the background". They are on by default. The row turns the existing
 * GMS_CORE_BATTERY_OPTIMIZATION_DIALOG setting on or off; the reminders' own "Don't show again" turns it off too.
 * Useful where the whitelist command can't be run (no reachable ADB, e.g. DiLink 5) and Maps works without it.
 */
public final class MicroGReminders {
    private static final String TAG = "MorpheMicroGReminders";

    private MicroGReminders() {
    }

    static boolean on() {
        return SharedSettings.GMS_CORE_BATTERY_OPTIMIZATION_DIALOG.get();
    }

    /** Injection point: the row title. */
    public static String getSettingsTitle(Context context) {
        return UiDensityPatch.isThai(context) ? "การแจ้งเตือน MicroG-RE" : "MicroG-RE reminders";
    }

    /** Injection point: the row subtitle, the state. */
    public static String getSettingsSummary(Context context) {
        return summary(UiDensityPatch.isThai(context), on());
    }

    private static String summary(boolean thai, boolean on) {
        return on ? (thai ? "แสดงตอนเปิด Maps" : "Shown when Maps starts") : (thai ? "ปิด" : "Off");
    }

    /** Injection point: the row's click action. */
    public static Runnable getSettingsAction(Context context, View container) {
        return () -> show(context, container);
    }

    private static void show(Context context, View container) {
        Activity activity = UiDensityPatch.findActivity(context);
        if (activity == null && container != null) activity = UiDensityPatch.findActivity(container.getContext());
        if (activity == null || activity.isFinishing()) {
            ReportLog.w(TAG, "No activity for the MicroG-RE reminders setting: " + context);
            return;
        }
        Activity host = activity;
        boolean thai = UiDensityPatch.isThai(host);
        boolean before = on();
        String[] labels = {
                thai ? "แสดง (ค่าเริ่มต้น) · เมื่อ MicroG-RE ไม่อยู่ใน whitelist แบตหรือไม่ได้ทำงานเบื้องหลัง"
                        : "Show (default) · when MicroG-RE is not on the battery whitelist or not running",
                thai ? "ไม่แสดง · Maps ไม่เตือนเรื่องนี้ตอนเริ่ม" : "Don't show · Maps starts without these reminders",
        };
        new M3.Builder(host)
                .setTitle(getSettingsTitle(host))
                .setSingleChoiceItems(labels, before ? 0 : 1, (dialog, which) -> {
                    dialog.dismiss();
                    boolean after = which == 0;
                    if (after == before) return;
                    SharedSettings.GMS_CORE_BATTERY_OPTIMIZATION_DIALOG.save(after);
                    ReportLog.i(TAG, "MicroG-RE reminders " + (after ? "on" : "off") + " in Settings");
                    // Maps builds the row once per visit: its subtitle is changed in place.
                    TextView row = findText(container != null ? container : host.getWindow().getDecorView(),
                            summary(thai, before));
                    if (row != null) row.setText(summary(thai, after));
                })
                .setNegativeButton(thai ? "ยกเลิก" : "Cancel", null)
                .show();
    }

    /** The first TextView under [view] showing exactly [text]. */
    private static TextView findText(View view, String text) {
        if (view instanceof TextView) {
            CharSequence shown = ((TextView) view).getText();
            return shown != null && text.contentEquals(shown) ? (TextView) view : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
