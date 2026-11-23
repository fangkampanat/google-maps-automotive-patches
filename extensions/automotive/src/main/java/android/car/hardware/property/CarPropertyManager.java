package android.car.hardware.property;

import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;

import app.morphe.extension.mapsautomotive.patches.CarBridge;

/**
 * Stand-in that answers from {@link CarBridge} (BYD vehicle SDK). Only properties
 * the bridge knows are offered. Rules that Maps' call sites depend on (26.10.120003.E): an
 * unsupported {@code getProperty} returns null (one site only catches SecurityException), an
 * unsupported {@code getIntProperty} throws IllegalArgumentException (every site catches it).
 * Callbacks run on the main thread.
 */
public final class CarPropertyManager {
    public interface CarPropertyEventCallback {
        void onChangeEvent(CarPropertyValue value);

        void onErrorEvent(int propertyId, int areaId);
    }

    public CarPropertyManager() {
    }

    public CarPropertyConfig getCarPropertyConfig(int propertyId) {
        return CarBridge.config(propertyId);
    }

    public boolean isPropertyAvailable(int propertyId, int areaId) {
        CarPropertyValue v = CarBridge.get().latest(propertyId);
        return v != null && v.getStatus() == CarPropertyValue.STATUS_AVAILABLE;
    }

    public CarPropertyValue getProperty(int propertyId, int areaId) {
        if (CarBridge.config(propertyId) == null) return null;
        return CarBridge.get().latest(propertyId);
    }

    @SuppressWarnings("unchecked")
    public <E> CarPropertyValue<E> getProperty(Class<E> type, int propertyId, int areaId) {
        return (CarPropertyValue<E>) getProperty(propertyId, areaId);
    }

    public int getIntProperty(int propertyId, int areaId) {
        CarPropertyValue v = getProperty(propertyId, areaId);
        if (v == null || !(v.getValue() instanceof Number)) {
            throw new IllegalArgumentException("Property 0x" + Integer.toHexString(propertyId) + " not available");
        }
        return ((Number) v.getValue()).intValue();
    }

    public int[] getIntArrayProperty(int propertyId, int areaId) {
        throw new IllegalArgumentException("Property 0x" + Integer.toHexString(propertyId) + " not available");
    }

    public boolean registerCallback(CarPropertyEventCallback callback, int propertyId, float rate) {
        return CarBridge.get().subscribe(callback, propertyId, rate);
    }

    public boolean subscribePropertyEvents(int propertyId, float rate, CarPropertyEventCallback callback) {
        return CarBridge.get().subscribe(callback, propertyId, rate);
    }

    public void unregisterCallback(CarPropertyEventCallback callback) {
        CarBridge.get().unsubscribe(callback, -1);
    }

    public void unregisterCallback(CarPropertyEventCallback callback, int propertyId) {
        CarBridge.get().unsubscribe(callback, propertyId);
    }

    public void unsubscribePropertyEvents(CarPropertyEventCallback callback) {
        CarBridge.get().unsubscribe(callback, -1);
    }
}
