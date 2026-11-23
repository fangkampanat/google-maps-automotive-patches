package android.car.hardware;

/** Stand-in so Maps' map-building module loads; Car never hands it out. */
public final class CarSensorManager {
    public interface OnSensorChangedListener {
        void onSensorChanged(CarSensorEvent event);
    }

    CarSensorManager() {
    }

    public boolean registerListener(OnSensorChangedListener listener, int sensorType, int rate) {
        return false;
    }

    public void unregisterListener(OnSensorChangedListener listener, int sensorType) {
    }
}
