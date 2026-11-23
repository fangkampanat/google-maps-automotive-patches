package android.car.navigation;

import android.os.Bundle;

/** Stand-in: navigation state for an instrument cluster; nothing receives it on DiLink (v1). */
public final class CarNavigationStatusManager {
    public CarNavigationStatusManager() {
    }

    public void sendEvent(int eventType, Bundle bundle) {
    }
}
