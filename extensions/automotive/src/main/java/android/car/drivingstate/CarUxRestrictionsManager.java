package android.car.drivingstate;

/** Stand-in: always unrestricted; the listener is kept but never called. */
public final class CarUxRestrictionsManager {
    private static final CarUxRestrictions NONE = new CarUxRestrictions();

    public interface OnUxRestrictionsChangedListener {
        void onUxRestrictionsChanged(CarUxRestrictions restrictionInfo);
    }

    public CarUxRestrictionsManager() {
    }

    public CarUxRestrictions getCurrentCarUxRestrictions() {
        return NONE;
    }

    public void registerListener(OnUxRestrictionsChangedListener listener) {
    }

    public void unregisterListener() {
    }
}
