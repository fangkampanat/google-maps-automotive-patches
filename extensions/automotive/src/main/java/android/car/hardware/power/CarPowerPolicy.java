package android.car.hardware.power;

/** Stand-in: the "default" policy with every component enabled. */
public final class CarPowerPolicy {
    CarPowerPolicy() {
    }

    public String getPolicyId() {
        return "default";
    }

    public boolean isComponentEnabled(int component) {
        return true;
    }
}
