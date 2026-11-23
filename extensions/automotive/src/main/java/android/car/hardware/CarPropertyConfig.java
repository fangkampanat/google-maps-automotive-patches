package android.car.hardware;

/** Stand-in: a global (area 0) property with its value type and sample rates. */
public final class CarPropertyConfig<T> {
    private final int propertyId;
    private final Class<T> type;
    private final float minRate, maxRate;

    public CarPropertyConfig(int propertyId, Class<T> type, float minRate, float maxRate) {
        this.propertyId = propertyId;
        this.type = type;
        this.minRate = minRate;
        this.maxRate = maxRate;
    }

    public int getPropertyId() {
        return propertyId;
    }

    public Class<T> getPropertyType() {
        return type;
    }

    public int[] getAreaIds() {
        return new int[]{0};
    }

    public int getAreaType() {
        return 0;
    }

    public float getMinSampleRate() {
        return minRate;
    }

    public float getMaxSampleRate() {
        return maxRate;
    }
}
