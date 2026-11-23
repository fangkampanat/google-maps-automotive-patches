package android.car;

/**
 * Stand-in: nothing about the car is claimed in v1 (empty strings, 0), the fuel type is electric.
 * EV connector types: the same as the BYD battery patch gives Maps ("MENNEKES,COMBO_2"), so both
 * answers agree; none while that patch does not feed Maps.
 */
public final class CarInfoManager {
    CarInfoManager() {
    }

    public String getManufacturer() {
        return "";
    }

    public String getModel() {
        return "";
    }

    public String getModelYear() {
        return "";
    }

    public int getModelYearInInteger() {
        return 0;
    }

    public int[] getFuelTypes() {
        return new int[]{10};   // FUEL_TYPE_ELECTRIC
    }

    public int[] getEvConnectorTypes() {
        return app.morphe.extension.mapsautomotive.patches.CarBridge.evConnectorTypes();
    }
}
