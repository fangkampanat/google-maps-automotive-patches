package android.car;

/**
 * Stand-in: focus requests are always granted. Like the real CarAppFocusManager, the owner callback
 * hears onAppFocusOwnershipGranted afterwards (asynchronously): Maps' navigation focus controller only
 * marks itself as having focus from that callback, and navigation start waits for it ("Laster inn"
 * forever, then "Noe gikk galt", 2026-11-20). Focus listeners are never called (nobody else owns focus).
 */
public final class CarAppFocusManager {
    public static final int APP_FOCUS_TYPE_NAVIGATION = 1;
    public static final int APP_FOCUS_REQUEST_SUCCEEDED = 1;

    public interface OnAppFocusChangedListener {
        void onAppFocusChanged(int appType, boolean active);
    }

    public interface OnAppFocusOwnershipCallback {
        void onAppFocusOwnershipLost(int appType);

        default void onAppFocusOwnershipGranted(int appType) {
        }
    }

    CarAppFocusManager() {
    }

    public void addFocusListener(OnAppFocusChangedListener listener, int appType) {
    }

    public void removeFocusListener(OnAppFocusChangedListener listener, int appType) {
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    public int requestAppFocus(int appType, OnAppFocusOwnershipCallback callback) {
        if (callback != null) {
            MAIN.post(() -> {
                try {
                    callback.onAppFocusOwnershipGranted(appType);
                    app.morphe.extension.mapsautomotive.patches.CarBridge.logFocus("app focus " + appType + " granted to " + callback.getClass().getName());
                } catch (Throwable t) {
                    app.morphe.extension.mapsautomotive.patches.CarBridge.logFocus("app focus " + appType + " callback failed: " + t);
                }
            });
        }
        return APP_FOCUS_REQUEST_SUCCEEDED;
    }

    public boolean isOwningFocus(OnAppFocusOwnershipCallback callback, int appType) {
        return true;
    }

    public void abandonAppFocus(OnAppFocusOwnershipCallback callback, int appType) {
    }
}
