package android.car.drivingstate;

/** Stand-in: never any restriction (the driver's own head unit decides, not an AAOS policy). */
public final class CarUxRestrictions {
    CarUxRestrictions() {
    }

    public int getActiveRestrictions() {
        return 0;
    }

    public boolean isRequiresDistractionOptimization() {
        return false;
    }

    public int getMaxContentDepth() {
        return Integer.MAX_VALUE;
    }

    public int getMaxCumulativeContentItems() {
        return Integer.MAX_VALUE;
    }

    public int getMaxRestrictedStringLength() {
        return Integer.MAX_VALUE;
    }
}
