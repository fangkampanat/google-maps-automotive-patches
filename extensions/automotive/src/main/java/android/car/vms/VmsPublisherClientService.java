package android.car.vms;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Stand-in base for Maps' VMS publisher service; nothing binds to it on DiLink. */
public abstract class VmsPublisherClientService extends Service {
    public VmsPublisherClientService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    protected void onVmsPublisherServiceReady() {
    }

    public void onVmsSubscriptionChange(VmsSubscriptionState subscriptionState) {
    }
}
