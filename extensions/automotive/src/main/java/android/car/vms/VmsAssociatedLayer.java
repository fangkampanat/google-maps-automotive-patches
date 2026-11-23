package android.car.vms;

import java.util.Collections;
import java.util.Set;

/** Stand-in. */
public final class VmsAssociatedLayer {
    private final VmsLayer layer;

    public VmsAssociatedLayer(VmsLayer layer, Set<Integer> publisherIds) {
        this.layer = layer;
    }

    public VmsLayer getVmsLayer() {
        return layer;
    }

    public Set<Integer> getPublisherIds() {
        return Collections.emptySet();
    }
}
