package android.car.vms;

/** Stand-in (Vehicle Map Service is off on DiLink). */
public final class VmsLayer {
    private final int type, subtype, version;

    public VmsLayer(int type, int subtype, int version) {
        this.type = type;
        this.subtype = subtype;
        this.version = version;
    }

    public int getType() {
        return type;
    }

    public int getSubtype() {
        return subtype;
    }

    public int getVersion() {
        return version;
    }
}
