package android.car.hardware;

/** Stand-in: one property value as Maps reads it. Timestamp = elapsedRealtimeNanos of the sample. */
public final class CarPropertyValue<T> {
    public static final int STATUS_AVAILABLE = 0;
    public static final int STATUS_UNAVAILABLE = 1;
    public static final int STATUS_ERROR = 2;

    private final int propertyId, areaId, status;
    private final long timestamp;
    private final T value;

    public CarPropertyValue(int propertyId, int areaId, int status, long timestamp, T value) {
        this.propertyId = propertyId;
        this.areaId = areaId;
        this.status = status;
        this.timestamp = timestamp;
        this.value = value;
    }

    public int getPropertyId() {
        return propertyId;
    }

    public int getAreaId() {
        return areaId;
    }

    public int getStatus() {
        return status;
    }

    public int getPropertyStatus() {
        return status;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public T getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "CarPropertyValue{id=" + propertyId + ", status=" + status + ", ts=" + timestamp + ", value=" + value + "}";
    }
}
