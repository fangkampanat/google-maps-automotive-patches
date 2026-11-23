package android.car.vms;

import java.util.Collections;
import java.util.Set;

/** Stand-in. */
public final class VmsSubscriptionState {
    public VmsSubscriptionState() {
    }

    public Set<VmsLayer> getLayers() {
        return Collections.emptySet();
    }

    public int getSequenceNumber() {
        return 0;
    }
}
