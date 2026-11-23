package android.car.hardware.property;

/** Stand-in; never thrown by the stand-in. */
public class PropertyAccessDeniedSecurityException extends SecurityException {
    public PropertyAccessDeniedSecurityException(String message) {
        super(message);
    }
}
