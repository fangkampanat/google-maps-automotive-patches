package android.car;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.mapsautomotive.patches.CarBridge;

/**
 * Stand-in for Android Automotive's {@code android.car.Car} on DiLink, which has no car service.
 * Only what Maps Car calls. Car data comes from the BYD vehicle SDK through
 * {@link CarBridge}; without the "carApi" switch {@link #createCar} returns null, which is what Maps
 * gets on DiLink without this stand-in.
 */
public final class Car {
    private final Context context;
    private final ServiceConnection connection;
    private volatile boolean connected;
    private volatile boolean connecting;
    private Object property, uxRestriction, appFocus, info, power, navigation;

    private Car(Context context, ServiceConnection connection) {
        this.context = context;
        this.connection = connection;
    }

    public static Car createCar(Context context) {
        if (!CarBridge.enabled()) return null;
        Car car = new Car(context, null);
        car.connected = true;   // like AAOS's createCar(Context): returns a connected car
        CarBridge.get().start();
        return car;
    }

    public static Car createCar(Context context, ServiceConnection connection) {
        if (!CarBridge.enabled()) return null;
        return new Car(context, connection);
    }

    public void connect() {
        if (connected || connecting) return;
        connecting = true;
        CarBridge.get().start();
        new Handler(Looper.getMainLooper()).post(() -> {
            connecting = false;
            connected = true;
            if (connection != null) {
                connection.onServiceConnected(new ComponentName("android.car", "CarService"), null);
            }
        });
    }

    public void disconnect() {
        boolean was = connected;
        connected = false;
        connecting = false;
        if (was && connection != null) {
            connection.onServiceDisconnected(new ComponentName("android.car", "CarService"));
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return connecting;
    }

    /** The managers Maps uses. Map building (sensor, VMS) stays off, as on DiLink without this. */
    public synchronized Object getCarManager(String name) {
        if (name == null) return null;
        switch (name) {
            case "property":
                if (property == null) property = new android.car.hardware.property.CarPropertyManager();
                return property;
            case "uxrestriction":
                if (uxRestriction == null) uxRestriction = new android.car.drivingstate.CarUxRestrictionsManager();
                return uxRestriction;
            case "app_focus":
                if (appFocus == null) appFocus = new CarAppFocusManager();
                return appFocus;
            case "info":
                if (info == null) info = new CarInfoManager();
                return info;
            case "power":
                if (power == null) power = new android.car.hardware.power.CarPowerManager();
                return power;
            case "car_navigation_service":
                if (navigation == null) navigation = new android.car.navigation.CarNavigationStatusManager();
                return navigation;
            default:
                return null;
        }
    }
}
