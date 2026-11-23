package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Intent;
import android.view.Display;

/**
 * Send to car keeps the trip where Maps is (patch "Send to car on Maps' display").
 * <p>
 * Maps' sent-trip helper starts the trip's screen as a new task with launch display 0, the main display. With
 * Maps in a launcher's frame (a virtual display of the launcher) that pulled Maps out of the frame onto the main
 * display, full screen. When the last MapsActivity is on another display:
 * <ul>
 * <li>Maps may start screens there: the trip starts there;</li>
 * <li>it may not (a launcher's private display; starting there throws SecurityException, seen 2026-11-23 with
 * Kinex): no launch display is set, so Android reuses Maps' task where it is.</li>
 * </ul>
 * Display 0 stays when Maps is on the main display or no MapsActivity is known. Never throws.
 */
@SuppressWarnings("unused")
public final class SendToCarDisplay {
    private static final String TAG = "MorpheSendToCar";

    private SendToCarDisplay() {
    }

    /** Injection point: replaces ActivityOptions.setLaunchDisplayId(displayId) in the sent-trip helper's start. */
    public static ActivityOptions launchDisplay(ActivityOptions options, int displayId) {
        int chosen = displayId;
        String why = "as Maps asked";
        try {
            Activity maps = SendToCarPush.mapsActivity();
            Display display = maps == null || maps.isFinishing() ? null : ApiCompat.getDisplay(maps);
            int current = display == null ? -1 : display.getDisplayId();
            if (displayId == Display.DEFAULT_DISPLAY && current > Display.DEFAULT_DISPLAY) {
                ActivityManager manager = (ActivityManager) maps.getSystemService(Activity.ACTIVITY_SERVICE);
                Intent probe = new Intent(maps, maps.getClass()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (manager != null && manager.isActivityStartAllowedOnDisplay(maps, current, probe)) {
                    chosen = current;
                    why = "Maps is on display " + current + " and may start screens there";
                } else {
                    chosen = Display.INVALID_DISPLAY;
                    why = "Maps is on display " + current + ", which does not allow Maps to start screens; no display set";
                }
            } else {
                why = "as Maps asked; Maps is on " + (current < 0 ? "no known display" : "display " + current);
            }
        } catch (Throwable error) {
            chosen = Display.INVALID_DISPLAY;
            why = "check failed (" + error + "); no display set";
        }
        ReportLog.i(TAG, "trip screen launch display " + chosen + " (" + why + ")");
        try {
            return options.setLaunchDisplayId(chosen);
        } catch (Throwable error) {
            ReportLog.w(TAG, "setLaunchDisplayId(" + chosen + ") failed, options unchanged", error);
            return options;
        }
    }
}
