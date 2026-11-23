package android.car.hardware.power;

import java.util.concurrent.Executor;

/** Stand-in: one fixed policy, all components on; listeners are never called. */
public final class CarPowerManager {
    private static final CarPowerPolicy DEFAULT = new CarPowerPolicy();

    public interface CarPowerPolicyListener {
        void onPolicyChanged(CarPowerPolicy appliedPolicy, CarPowerPolicy accumulatedPolicy);
    }

    public CarPowerManager() {
    }

    public CarPowerPolicy getCurrentPowerPolicy() {
        return DEFAULT;
    }

    public void addPowerPolicyListener(Executor executor, CarPowerPolicyFilter filter, CarPowerPolicyListener listener) {
    }
}
