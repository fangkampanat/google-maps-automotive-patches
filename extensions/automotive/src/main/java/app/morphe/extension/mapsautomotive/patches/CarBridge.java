package app.morphe.extension.mapsautomotive.patches;

import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Car properties (speed, gear) behind the android.car stand-in, from the BYD vehicle SDK inside Maps
 * ({@link BydCarSource}), sampled at {@link #SAMPLE_HZ} Hz on the first subscription.
 * <p>
 * The properties the stand-in offers are fixed ({@link #CONFIGS}), so Maps' subscriptions succeed
 * before the first value. Without the SDK (another car, other firmware) nothing is delivered and Maps
 * works as without car data. On only with CarData's "carApi" switch (read when Maps starts).
 */
public final class CarBridge {
    private static final String TAG = "MorpheCarBridge";
    private static final long STATS_MS = 60_000L;
    /** BYD SDK sampling rate; Maps subscribes to the speed at up to 10 Hz. */
    private static final int SAMPLE_HZ = 10;
    /** A speed older than this (no valid reading) is reported unavailable. */
    private static final long STALE_MS = 2_000L;

    static final int PERF_VEHICLE_SPEED = 291504647;
    static final int GEAR_SELECTION = 289408000;
    private static final Map<Integer, CarPropertyConfig<?>> CONFIGS = new HashMap<>();

    static {
        CONFIGS.put(PERF_VEHICLE_SPEED, new CarPropertyConfig<>(PERF_VEHICLE_SPEED, Float.class, 1f, 10f));
        CONFIGS.put(GEAR_SELECTION, new CarPropertyConfig<>(GEAR_SELECTION, Integer.class, 0f, 0f));
    }

    private static volatile Boolean enabled;
    private static final CarBridge INSTANCE = new CarBridge();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Set<CarPropertyManager.CarPropertyEventCallback>> callbacks = new ConcurrentHashMap<>();
    private final Map<Integer, CarPropertyValue<?>> latest = new ConcurrentHashMap<>();
    private volatile boolean started;
    // Per property: values received, passed to Maps, held back (not available, or speed switched off), last value.
    private final Map<Integer, long[]> counts = new ConcurrentHashMap<>();
    private final Map<Integer, Object> lastValue = new ConcurrentHashMap<>();
    private volatile long statsAt = android.os.SystemClock.elapsedRealtime();

    private CarBridge() {
    }

    public static CarBridge get() {
        return INSTANCE;
    }

    /**
     * CarData's "carApi" switch (default on), read once per process; on only where the BYD SDK gives a speed, so
     * on any other head unit Maps' car-service setup stays as without this patch.
     */
    public static boolean enabled() {
        Boolean e = enabled;
        if (e == null) {
            boolean wanted = CarData.flag(CarData.KEY_CAR_API, true);
            String status = "switched off";
            if (wanted) {
                Context app = BydCarSource.application();
                status = app == null ? "no application context" : new BydCarSource().init(app);
            }
            e = wanted && status.startsWith("ok");
            enabled = e;
            ReportLog.i(TAG, "car data (speed, gear) " + (e ? "on" : "off") + " (BYD SDK: " + status + ")");
        }
        return e;
    }

    /** For the android.car stand-ins: a line in Maps' persistent log. */
    public static void logFocus(String text) {
        ReportLog.i(TAG, text);
    }

    public static CarPropertyConfig<?> config(int propertyId) {
        return CONFIGS.get(propertyId);
    }

    /** The BYD battery patch's connector types ("MENNEKES,COMBO_2") as EvConnectorType values. */
    public static int[] evConnectorTypes() {
        return BydBatteryPatch.isActive() ? new int[]{2, 5} : new int[0];
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(this::loop, "CarBridge");
        t.setDaemon(true);
        t.start();
    }

    public CarPropertyValue<?> latest(int propertyId) {
        return latest.get(propertyId);
    }

    public boolean subscribe(CarPropertyManager.CarPropertyEventCallback callback, int propertyId, float rate) {
        if (callback == null || config(propertyId) == null) return false;
        callbacks.computeIfAbsent(propertyId, k -> new CopyOnWriteArraySet<>()).add(callback);
        ReportLog.i(TAG, "subscribe " + name(propertyId) + " rate=" + rate + " by " + callback.getClass().getName()
                + " (" + callbacks.get(propertyId).size() + " callback(s))");
        start();
        CarPropertyValue<?> v = latest.get(propertyId);
        if (v != null) main.post(() -> callback.onChangeEvent(v));
        return true;
    }

    /** propertyId -1 = every property of this callback. */
    public void unsubscribe(CarPropertyManager.CarPropertyEventCallback callback, int propertyId) {
        for (Map.Entry<Integer, Set<CarPropertyManager.CarPropertyEventCallback>> e : callbacks.entrySet()) {
            if (propertyId != -1 && e.getKey() != propertyId) continue;
            if (e.getValue().remove(callback)) ReportLog.i(TAG, "unsubscribe " + name(e.getKey()) + " by " + callback.getClass().getName());
        }
    }

    private void loop() {
        BydCarSource byd = new BydCarSource();
        Context app = BydCarSource.application();
        String status = app == null ? "no application context" : byd.init(app);
        if (status.startsWith("ok")) {
            ReportLog.i(TAG, "car data source: BYD SDK, " + SAMPLE_HZ + " Hz, " + status);
            sampleBydForever(byd);
            return;
        }
        ReportLog.w(TAG, "no car data: BYD SDK not usable (" + status + ")");
    }

    /** Speed (m/s, negative in reverse, as AAOS) and gear from the BYD SDK, on this thread. */
    private void sampleBydForever(BydCarSource byd) {
        long periodMs = 1000L / SAMPLE_HZ;
        long goodAtMs = 0;
        float speedMs = 0f;
        int gear = 0;
        int lastGearStatus = -1;
        while (true) {
            long nowMs = android.os.SystemClock.elapsedRealtime();
            int g = byd.readGear();
            int gearStatus = g != 0 ? CarPropertyValue.STATUS_AVAILABLE : CarPropertyValue.STATUS_UNAVAILABLE;
            // Gear is on-change: deliver when it or its availability changes.
            if (g != 0 && g != gear || gearStatus != lastGearStatus) {
                if (g != 0) gear = g;
                lastGearStatus = gearStatus;
                deliver(GEAR_SELECTION, gearStatus, BydCarSource.nowNanos(), gear == 0 ? null : gear);
            }
            double kmh = byd.readSpeedKmh();
            if (!Double.isNaN(kmh)) {
                float ms = (float) (kmh / 3.6);
                speedMs = gear == BydCarSource.GEAR_REVERSE ? -ms : ms;
                goodAtMs = nowMs;
            }
            boolean fresh = goodAtMs > 0 && nowMs - goodAtMs <= STALE_MS;
            deliver(PERF_VEHICLE_SPEED, fresh ? CarPropertyValue.STATUS_AVAILABLE : CarPropertyValue.STATUS_UNAVAILABLE,
                    BydCarSource.nowNanos(), goodAtMs > 0 ? speedMs : null);
            long sleep = periodMs - (android.os.SystemClock.elapsedRealtime() - nowMs);
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** One value to Maps' callbacks. */
    private void deliver(int id, int status, long ts, Object value) {
        try {
            CarPropertyValue<?> v = new CarPropertyValue<>(id, 0, status, ts, value);
            long[] c = counts.computeIfAbsent(id, k -> new long[4]);
            c[0]++;
            // c[3]: how often the value changed, i.e. how fresh the SDK's readings really are.
            if (value != null && !value.equals(lastValue.get(id))) c[3]++;
            if (value != null) lastValue.put(id, value);
            // CarData's speed switch (settings row): off = Maps gets no speed at all, at once.
            if (id == PERF_VEHICLE_SPEED && !CarData.speedEnabled()) {
                latest.remove(id);
                c[2]++;
                maybeLogStats();
                return;
            }
            latest.put(id, v);
            if (value == null || status != CarPropertyValue.STATUS_AVAILABLE) c[2]++;
            else if (callbacks.get(id) != null && !callbacks.get(id).isEmpty()) c[1]++;
            maybeLogStats();
            // Maps reads the value without checking the status (gjp: getValue() as Float), and a stale
            // speed keeps its last value with "unavailable". Such a value is kept for getProperty but
            // never pushed to callbacks.
            if (value == null || status != CarPropertyValue.STATUS_AVAILABLE) return;
            Set<CarPropertyManager.CarPropertyEventCallback> set = callbacks.get(id);
            if (set == null || set.isEmpty()) return;
            main.post(() -> {
                for (CarPropertyManager.CarPropertyEventCallback cb : set) {
                    try {
                        cb.onChangeEvent(v);
                    } catch (Throwable t) {
                        ReportLog.w(TAG, "callback for " + name(id) + " failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            ReportLog.w(TAG, "deliver " + name(id) + " failed: " + t);
        }
    }

    /** Once a minute: per property what the SDK gave and what reached Maps. */
    private void maybeLogStats() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - statsAt < STATS_MS) return;
        statsAt = now;
        StringBuilder b = new StringBuilder("last minute:");
        for (Map.Entry<Integer, long[]> e : counts.entrySet()) {
            long[] c = e.getValue();
            Set<CarPropertyManager.CarPropertyEventCallback> set = callbacks.get(e.getKey());
            b.append(' ').append(name(e.getKey())).append(" received=").append(c[0]).append(" changed=").append(c[3])
                    .append(" toMaps=").append(c[1]).append(" unavailable=").append(c[2]).append(" last=").append(lastValue.get(e.getKey()))
                    .append(" callbacks=").append(set == null ? 0 : set.size()).append(';');
            c[0] = c[1] = c[2] = c[3] = 0;
        }
        ReportLog.i(TAG, b.toString());
    }

    private static String name(int propertyId) {
        switch (propertyId) {
            case PERF_VEHICLE_SPEED: return "speed";
            case GEAR_SELECTION: return "gear";
            default: return "0x" + Integer.toHexString(propertyId);
        }
    }
}
