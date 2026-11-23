package android.car.hardware.power;

/** Stand-in. */
public final class CarPowerPolicyFilter {
    CarPowerPolicyFilter() {
    }

    public static final class Builder {
        public Builder() {
        }

        public Builder setComponents(int... components) {
            return this;
        }

        public CarPowerPolicyFilter build() {
            return new CarPowerPolicyFilter();
        }
    }
}
