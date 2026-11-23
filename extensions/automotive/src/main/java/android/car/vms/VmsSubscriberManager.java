package android.car.vms;

import java.util.concurrent.Executor;

/** Stand-in; Car never hands it out. */
public final class VmsSubscriberManager {
    public interface VmsSubscriberClientCallback {
        void onVmsMessageReceived(VmsLayer layer, byte[] payload);

        void onLayersAvailabilityChanged(VmsAvailableLayers availableLayers);
    }

    VmsSubscriberManager() {
    }

    public void setVmsSubscriberClientCallback(Executor executor, VmsSubscriberClientCallback callback) {
    }

    public VmsAvailableLayers getAvailableLayers() {
        return new VmsAvailableLayers();
    }

    public byte[] getPublisherInfo(int publisherId) {
        return new byte[0];
    }

    public void startMonitoring() {
    }

    public void stopMonitoring() {
    }

    public void subscribe(VmsLayer layer) {
    }

    public void unsubscribe(VmsLayer layer) {
    }
}
