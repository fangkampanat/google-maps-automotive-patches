package android.car.hardware;

/** Stand-in; never delivered (no sensor manager is handed out). */
public class CarSensorEvent {
    public int sensorType;
    public long timestamp;
    public float[] floatValues;
    public long[] longValues;

    public CarSensorEvent() {
    }
}
