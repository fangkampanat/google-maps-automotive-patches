package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Floating Maps window (patch "Floating Maps window", switch "floating_maps").
 * <p>
 * During guidance, while none of Maps' screens is in front (BYD's camera, another app full screen, Home), a small
 * window over the other app shows Google's compact guidance screen: LimitedMapsActivity, which Maps declares for a
 * launcher's map card (next turn, map, arrival). The card runs on a private virtual display that Maps Car owns, and the
 * window shows that display's picture.
 * <ul>
 * <li>The card is started once per guidance while a Maps screen is in front with the input focus. Starting an activity
 * on another display can move the input focus there; if Maps loses it, Maps' task is moved to the front again, so no
 * other app is left without the focus.</li>
 * <li>The display has no surface until the window shows. The system stops the card while its display is off, so it
 * draws nothing in the background.</li>
 * <li>Guidance: Maps' own navigation notification (category navigation, from a foreground service).</li>
 * <li>The window starts at the bottom right, 640×360 px, 24 px from the edges. Dragging moves it, its top-left corner
 * resizes it, and both are saved. Taps do not reach the map. The X hides it until a Maps screen is in front again.</li>
 * <li>It needs "Display over other apps" (SYSTEM_ALERT_WINDOW, which the patch declares), granted on the system's page
 * from the Settings row. The row also turns the window off.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class FloatingMaps {
    private static final String TAG = "MorpheFloatingMaps";
    /** Must match the activity the patch checks in the manifest. */
    static final String CARD_ACTIVITY = "com.google.android.apps.gmm.car.embedded.activity.LimitedMapsActivity";
    private static final String PREFERENCES = "morphe_floating_maps", KEY_ON = "on", KEY_RIGHT = "right", KEY_BOTTOM = "bottom",
            KEY_WIDTH = "width", KEY_HEIGHT = "height";
    static final int DEFAULT_WIDTH = 640, DEFAULT_HEIGHT = 360, MARGIN = 24, MIN_WIDTH = 320, MIN_HEIGHT = 180;
    private static final int CARD_DPI = 160;
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY and the hidden DESTROY_CONTENT_ON_REMOVAL (1 << 8). */
    private static final int DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | 1 << 8;
    /** Notification.CATEGORY_NAVIGATION (an API 29 constant). */
    private static final String NAVIGATION = "navigation";
    private static final long WATCH_MS = 2000, GUIDING_WATCH_MS = 5000, COVER_DELAY_MS = 700, FOCUS_CHECK_MS = 600,
            RELEASE_TIMEOUT_MS = 3000, CARD_START_MS = 10_000;
    private static final int MAX_START_FAILURES = 3;

    // Main thread only.
    private static Application application;
    private static Handler main;
    private static boolean installed, guiding, dismissed, cardRequested, noCardLogged;
    private static int startFailures;
    private static long cardRequestedAt;
    /** Maps' screens that are started (visible), the card excepted. */
    private static final Set<Activity> inFront = Collections.newSetFromMap(new WeakHashMap<>());
    private static WeakReference<Activity> card;
    /** The card's display; [retiring] is the previous one, released once its card is gone. */
    private static VirtualDisplay display, retiring;
    private static View window;
    private static WindowManager.LayoutParams windowParams;
    private static SurfaceView picture;

    // For the problem report, read from another thread.
    private static volatile boolean reportGuiding, reportCard, reportShowing;
    private static volatile int cardStarts, shown, closedByX, focusRestores;
    private static volatile String lastError;

    private static final Runnable watcher = FloatingMaps::watch;
    private static final Runnable coverCheck = FloatingMaps::watch;

    private FloatingMaps() {
    }

    /** Injection point: start of MapsActivity's onCreate. */
    public static void install(Activity activity) {
        try {
            if (installed || !SafeMode.on(activity, SafeMode.FLOATING_MAPS)) return;
            installed = true;
            application = activity.getApplication();
            main = new Handler(Looper.getMainLooper());
            application.registerActivityLifecycleCallbacks(new Lifecycle());
            ReportLog.i(TAG, "Floating Maps window " + (userOn(activity) ? "on" : "off in Settings")
                    + ", display over other apps " + (Settings.canDrawOverlays(activity) ? "allowed" : "not allowed"));
        } catch (Throwable error) {
            note("not installed", error);
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            if (isOurCard(activity)) {
                card = new WeakReference<>(activity);
                reportCard = true;
                ReportLog.i(TAG, "card created on display " + displayId(activity));
            }
        }

        @Override
        public void onActivityStarted(Activity activity) {
            if (isOurCard(activity)) return;
            inFront.add(activity);
            mapsInFront();
        }

        @Override
        public void onActivityStopped(Activity activity) {
            if (isOurCard(activity)) return;
            inFront.remove(activity);
            if (inFront.isEmpty()) {
                main.removeCallbacks(coverCheck);
                main.postDelayed(coverCheck, COVER_DELAY_MS);
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            Activity current = card != null ? card.get() : null;
            if (current == activity) {
                card = null;
                reportCard = false;
                // A relaunch for a configuration change: the new card registers itself on the same display.
                if (activity.isChangingConfigurations()) return;
                cardRequested = false;
                ReportLog.i(TAG, "card closed");
                hideWindow("card closed");
                releaseRetiring();
                return;
            }
            inFront.remove(activity);
        }

        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    }

    // The card this class started: LimitedMapsActivity on our display (or the one being released). On another display
    // (a launcher's), it is a Maps screen like the others.
    private static boolean isOurCard(Activity activity) {
        if (card != null && card.get() == activity) return true;
        if (!CARD_ACTIVITY.equals(activity.getClass().getName())) return false;
        int shownOn = displayId(activity);
        return shownOn >= 0 && (shownOn == idOf(display) || shownOn == idOf(retiring));
    }

    private static int idOf(VirtualDisplay virtual) {
        Display shown = virtual != null ? virtual.getDisplay() : null;
        return shown != null ? shown.getDisplayId() : -2;
    }

    private static int displayId(Activity activity) {
        try {
            Display shown = activity.getWindowManager().getDefaultDisplay();
            return shown != null ? shown.getDisplayId() : -1;
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private static Activity card() {
        Activity current = card != null ? card.get() : null;
        return current == null || current.isDestroyed() ? null : current;
    }

    private static void mapsInFront() {
        main.removeCallbacks(coverCheck);
        dismissed = false;
        hideWindow("Maps in front");
        watch();
    }

    // Runs while a Maps screen is in front (to see guidance start), during guidance and while a card exists.
    private static void watch() {
        main.removeCallbacks(watcher);
        try {
            if (!userOn(application) || !Settings.canDrawOverlays(application)) {
                tearDown("off or not allowed");
                return;
            }
            boolean now = guidanceOn();
            if (now != guiding) {
                guiding = now;
                reportGuiding = now;
                startFailures = 0;
                ReportLog.i(TAG, "guidance " + (now ? "on" : "off"));
                if (!now) tearDown("guidance ended");
            }
            boolean front = !inFront.isEmpty();
            if (cardRequested && card() == null && SystemClock.uptimeMillis() - cardRequestedAt > CARD_START_MS) {
                // The system dropped the start: try again, a few times per guidance.
                cardRequested = false;
                startFailures++;
                note("card did not start (" + startFailures + ")", new IllegalStateException("no card after " + CARD_START_MS + " ms"));
            }
            if (guiding) {
                if (front) {
                    if (card() == null && !cardRequested) startCard();
                } else if (!dismissed) {
                    showWindow();
                }
            }
            if (front || guiding || display != null) main.postDelayed(watcher, guiding ? GUIDING_WATCH_MS : WATCH_MS);
        } catch (Throwable error) {
            note("watch failed", error);
        }
    }

    private static boolean guidanceOn() {
        NotificationManager notifications = (NotificationManager) application.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications == null) return false;
        for (StatusBarNotification posted : notifications.getActiveNotifications()) {
            Notification notification = posted.getNotification();
            if (notification != null && NAVIGATION.equals(notification.category)
                    && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                return true;
            }
        }
        return false;
    }

    // A Maps screen in front that has the input focus, or null.
    private static Activity focusedMaps() {
        for (Activity activity : inFront) {
            if (!activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus()) return activity;
        }
        return null;
    }

    private static void startCard() {
        if (startFailures >= MAX_START_FAILURES) return;
        Activity maps = focusedMaps();
        if (maps == null) return;
        try {
            if (display == null) {
                Point size = savedSize(application);
                DisplayManager displays = (DisplayManager) application.getSystemService(Context.DISPLAY_SERVICE);
                // No surface yet: the display is off and the card stopped until the window shows.
                display = displays.createVirtualDisplay("Maps Car floating window", size.x, size.y, CARD_DPI, null, DISPLAY_FLAGS);
            }
            Display target = display.getDisplay();
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName(application.getPackageName(), CARD_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(target.getDisplayId());
            maps.startActivity(intent, options.toBundle());
            cardRequested = true;
            cardRequestedAt = SystemClock.uptimeMillis();
            cardStarts++;
            noCardLogged = false;
            ReportLog.i(TAG, "card started on display " + target.getDisplayId() + " (" + CARD_DPI + " dpi)");
            WeakReference<Activity> starter = new WeakReference<>(maps);
            main.postDelayed(() -> keepFocus(starter.get()), FOCUS_CHECK_MS);
        } catch (Throwable error) {
            startFailures++;
            note("card not started (" + startFailures + ")", error);
        }
    }

    // After the card's start: if Maps lost the input focus to the card's display, Maps' task to the front again.
    private static void keepFocus(Activity maps) {
        try {
            if (maps == null || maps.isDestroyed() || !inFront.contains(maps)) return;
            if (maps.hasWindowFocus()) {
                ReportLog.i(TAG, "Maps kept the input focus");
                return;
            }
            ActivityManager activities = (ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE);
            activities.moveTaskToFront(maps.getTaskId(), 0);
            focusRestores++;
            ReportLog.i(TAG, "the card's display took the input focus: Maps' task moved to the front again");
            main.postDelayed(() -> ReportLog.i(TAG, "Maps has the input focus: " + (!maps.isDestroyed() && maps.hasWindowFocus())),
                    FOCUS_CHECK_MS);
        } catch (Throwable error) {
            note("input focus not restored", error);
        }
    }

    private static void showWindow() {
        if (window != null) return;
        if (card() == null || display == null) {
            if (!noCardLogged) ReportLog.i(TAG, "Maps covered during guidance, no card yet (Maps was not in front with the focus)");
            noCardLogged = true;
            return;
        }
        try {
            WindowManager windows = (WindowManager) application.getSystemService(Context.WINDOW_SERVICE);
            Point screen = screenSize(windows);
            Spot spot = Spot.load(application, screen);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(spot.width, spot.height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            params.x = spot.right;
            params.y = spot.bottom;
            params.setTitle("Maps Car floating window");

            Context context = application;
            float density = context.getResources().getDisplayMetrics().density;
            int button = Math.round(40 * density);
            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(Color.BLACK);
            SurfaceView view = new SurfaceView(context);
            view.getHolder().setFixedSize(spot.width, spot.height);
            view.getHolder().addCallback(new Picture());
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(new Border(context, density), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Corner close = new Corner(context, true);
            close.setOnClickListener(clicked -> {
                dismissed = true;
                closedByX++;
                hideWindow("X");
            });
            root.addView(close, new FrameLayout.LayoutParams(button, button, Gravity.TOP | Gravity.RIGHT));
            Corner grip = new Corner(context, false);
            grip.setOnTouchListener(new Drag(windows, screen, true));
            root.addView(grip, new FrameLayout.LayoutParams(button, button, Gravity.TOP | Gravity.LEFT));
            root.setOnTouchListener(new Drag(windows, screen, false));

            windows.addView(root, params);
            window = root;
            windowParams = params;
            picture = view;
            reportShowing = true;
            shown++;
            ReportLog.i(TAG, "window shown: " + spot);
            ReportBuilder.saveSnapshot(application, true);
        } catch (Throwable error) {
            window = null;
            picture = null;
            note("window not shown", error);
        }
    }

    private static void hideWindow(String reason) {
        View current = window;
        if (current == null) return;
        window = null;
        picture = null;
        reportShowing = false;
        try {
            ((WindowManager) application.getSystemService(Context.WINDOW_SERVICE)).removeView(current);
        } catch (Throwable error) {
            note("window not removed", error);
        }
        detach();
        ReportLog.i(TAG, "window hidden (" + reason + ")");
    }

    // The window goes, the card is finished and its display released once the card is gone (or after a timeout); a
    // new guidance gets a new display.
    private static void tearDown(String reason) {
        hideWindow(reason);
        cardRequested = false;
        if (display == null) return;
        releaseRetiring();
        retiring = display;
        display = null;
        Activity current = card();
        if (current != null) {
            try {
                current.finishAndRemoveTask();
            } catch (Throwable error) {
                note("card not finished", error);
            }
            main.postDelayed(FloatingMaps::releaseRetiring, RELEASE_TIMEOUT_MS);
        } else {
            releaseRetiring();
        }
    }

    // The display flag destroys whatever is still on it, so nothing moves to the main display.
    private static void releaseRetiring() {
        VirtualDisplay current = retiring;
        if (current == null) return;
        retiring = null;
        try {
            current.release();
            ReportLog.i(TAG, "display released");
        } catch (Throwable error) {
            note("display not released", error);
        }
    }

    private static void attach(SurfaceHolder holder) {
        VirtualDisplay current = display;
        if (current != null) current.setSurface(holder.getSurface());
    }

    private static void detach() {
        VirtualDisplay current = display;
        try {
            if (current != null) current.setSurface(null);
        } catch (Throwable error) {
            note("picture not detached", error);
        }
    }

    /** The window's surface becomes the display's: the card wakes up and draws into it, and stops when it goes. */
    private static final class Picture implements SurfaceHolder.Callback {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            attach(holder);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            VirtualDisplay current = display;
            if (current == null || width <= 0 || height <= 0) return;
            current.resize(width, height, CARD_DPI);
            attach(holder);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            detach();
        }
    }

    /** Moves the window (the body) or resizes it from the top-left corner (the grip); the bottom-right corner stays. */
    private static final class Drag implements View.OnTouchListener {
        private final WindowManager windows;
        private final Point screen;
        private final boolean resize;
        private final int slop;
        private float downX, downY;
        private int startRight, startBottom, startWidth, startHeight;
        private boolean moved;

        Drag(WindowManager windows, Point screen, boolean resize) {
            this.windows = windows;
            this.screen = screen;
            this.resize = resize;
            this.slop = Math.round(8 * application.getResources().getDisplayMetrics().density);
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            WindowManager.LayoutParams params = windowParams;
            View current = window;
            if (params == null || current == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startRight = params.x;
                    startBottom = params.y;
                    startWidth = params.width;
                    startHeight = params.height;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(event.getRawX() - downX), dy = Math.round(event.getRawY() - downY);
                    if (!moved && Math.abs(dx) + Math.abs(dy) < slop) return true;
                    moved = true;
                    if (resize) {
                        params.width = Spot.clamp(startWidth - dx, MIN_WIDTH, screen.x - params.x);
                        params.height = Spot.clamp(startHeight - dy, MIN_HEIGHT, screen.y - params.y);
                    } else {
                        params.x = Spot.clamp(startRight - dx, 0, screen.x - params.width);
                        params.y = Spot.clamp(startBottom - dy, 0, screen.y - params.height);
                    }
                    windows.updateViewLayout(current, params);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        Spot spot = new Spot(params.x, params.y, params.width, params.height);
                        spot.save(application);
                        // The card is laid out again for the new size once, at the end.
                        SurfaceView shownPicture = picture;
                        if (resize && shownPicture != null) shownPicture.getHolder().setFixedSize(params.width, params.height);
                        ReportLog.i(TAG, (resize ? "window resized: " : "window moved: ") + spot);
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    /** The window's place: offsets from the right and bottom edges, and its size, in px. */
    static final class Spot {
        final int right, bottom, width, height;

        Spot(int right, int bottom, int width, int height) {
            this.right = right;
            this.bottom = bottom;
            this.width = width;
            this.height = height;
        }

        /** The saved place on a screen of [screen] px, or the default: bottom right, 640×360, 24 px from the edges. */
        static Spot load(Context context, Point screen) {
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            return fit(preferences.getInt(KEY_RIGHT, MARGIN), preferences.getInt(KEY_BOTTOM, MARGIN),
                    preferences.getInt(KEY_WIDTH, DEFAULT_WIDTH), preferences.getInt(KEY_HEIGHT, DEFAULT_HEIGHT), screen.x, screen.y);
        }

        /** Kept on a screen of [screenWidth]×[screenHeight]. Plain Java. */
        static Spot fit(int right, int bottom, int width, int height, int screenWidth, int screenHeight) {
            int w = clamp(width, MIN_WIDTH, Math.max(MIN_WIDTH, screenWidth));
            int h = clamp(height, MIN_HEIGHT, Math.max(MIN_HEIGHT, screenHeight));
            return new Spot(clamp(right, 0, Math.max(0, screenWidth - w)), clamp(bottom, 0, Math.max(0, screenHeight - h)), w, h);
        }

        static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        void save(Context context) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_RIGHT, right).putInt(KEY_BOTTOM, bottom).putInt(KEY_WIDTH, width).putInt(KEY_HEIGHT, height).apply();
        }

        @Override
        public String toString() {
            return width + "x" + height + ", right " + right + ", bottom " + bottom;
        }
    }

    private static Point savedSize(Context context) {
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Spot spot = Spot.load(context, screenSize(windows));
        return new Point(spot.width, spot.height);
    }

    private static Point screenSize(WindowManager windows) {
        Point size = new Point();
        windows.getDefaultDisplay().getRealSize(size);
        return size;
    }

    /** A thin frame around the picture. */
    private static final class Border extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        Border(Context context, float density) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2, Math.round(density * 1.5f)));
            paint.setColor(0x99FFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = paint.getStrokeWidth() / 2;
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRect(bounds, paint);
        }
    }

    /** The X (close) at the top right, or the resize grip at the top left. */
    private static final class Corner extends View {
        private final boolean close;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);

        Corner(Context context, boolean close) {
            super(context);
            this.close = close;
            fill.setColor(0xB3000000);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeWidth(Math.max(2, Math.round(2.5f * context.getResources().getDisplayMetrics().density)));
            setContentDescription(close ? "Close" : "Resize");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2;
            canvas.drawCircle(w / 2, h / 2, r * 0.8f, fill);
            float a = r * 0.35f;
            if (close) {
                canvas.drawLine(w / 2 - a, h / 2 - a, w / 2 + a, h / 2 + a, line);
                canvas.drawLine(w / 2 + a, h / 2 - a, w / 2 - a, h / 2 + a, line);
            } else {
                // Two diagonal strokes pointing to the top-left corner.
                canvas.drawLine(w / 2 - a, h / 2 + a * 0.2f, w / 2 + a * 0.2f, h / 2 - a, line);
                canvas.drawLine(w / 2 - a * 0.1f, h / 2 + a, w / 2 + a, h / 2 - a * 0.1f, line);
            }
        }
    }

    private static void note(String what, Throwable error) {
        lastError = what + ": " + error;
        ReportLog.w(TAG, what, error);
    }

    // The owner's choice, on unless turned off in Settings.

    static boolean userOn(Context context) {
        try {
            return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
        } catch (RuntimeException error) {
            return true;
        }
    }

    private static void setUserOn(Activity host, boolean on) {
        host.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
        ReportLog.i(TAG, "Floating Maps window turned " + (on ? "on" : "off") + " in Settings");
        if (!installed) return;
        if (on) watch();
        else tearDown("turned off");
    }

    private static void resetSpot(Activity host) {
        host.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove(KEY_RIGHT).remove(KEY_BOTTOM).remove(KEY_WIDTH).remove(KEY_HEIGHT).apply();
        ReportLog.i(TAG, "window place reset");
    }

    // Settings row. The patch adds it to Maps' Settings list.

    /** Injection point: the row title. */
    public static String getSettingsTitle(Context context) {
        return UiDensityPatch.isThai(context) ? "หน้าต่าง Maps ลอย (Floating Maps window)" : "Floating Maps window";
    }

    /** Injection point: the row subtitle, the state. */
    public static String getSettingsSummary(Context context) {
        boolean thai = UiDensityPatch.isThai(context);
        if (!SafeMode.on(context, SafeMode.FLOATING_MAPS)) {
            return (thai ? "ปิดชั่วคราว · สวิตช์ใน " : "Off for now · switch in ") + ReportPatch.reportPlace(context, thai);
        }
        if (!userOn(context)) return thai ? "ปิด" : "Off";
        if (!Settings.canDrawOverlays(context)) {
            return thai ? "เปิด · ยังไม่ได้อนุญาตให้แสดงทับแอปอื่น" : "On · not allowed to display over other apps yet";
        }
        return thai ? "เปิด · ขึ้นระหว่างนำทางเมื่อหน้าจออื่นบัง Maps" : "On · shows during guidance when another screen covers Maps";
    }

    /** Injection point: the row's click action. */
    public static Runnable getSettingsAction(Context context, View container) {
        return () -> showSettings(context, container);
    }

    private static void showSettings(Context context, View container) {
        Activity activity = UiDensityPatch.findActivity(context);
        if (activity == null && container != null) activity = UiDensityPatch.findActivity(container.getContext());
        if (activity == null || activity.isFinishing()) {
            ReportLog.w(TAG, "No activity for the floating window settings: " + context);
            return;
        }
        Activity host = activity;
        boolean thai = UiDensityPatch.isThai(host);
        boolean on = userOn(host), allowed = Settings.canDrawOverlays(host), switched = SafeMode.on(host, SafeMode.FLOATING_MAPS);
        Intent page = overlayPage(host);
        boolean hasPage = page.resolveActivity(host.getPackageManager()) != null;
        String command = "adb shell appops set " + host.getPackageName() + " SYSTEM_ALERT_WINDOW allow";
        StringBuilder message = new StringBuilder(thai
                ? "ระหว่างนำทาง ถ้ากล้องรอบคัน แอปอื่นแบบเต็มจอ หรือหน้า Home บัง Maps จะมีหน้าต่างเล็กที่มุมขวาล่าง"
                + " แสดงจุดเลี้ยวถัดไป แผนที่ และเวลาถึง ลากเพื่อย้าย ลากมุมซ้ายบนเพื่อปรับขนาด"
                + " และกด X เพื่อซ่อนจนกว่าจะกลับมาที่ Maps"
                : "During guidance, when the camera, another full-screen app or Home covers Maps, a small window at the"
                + " bottom right shows the next turn, the map and the arrival time. Drag it to move it, drag its top-left"
                + " corner to resize it, and tap X to hide it until you are back in Maps.");
        message.append("\n\n");
        if (!switched) {
            message.append(thai ? "ตอนนี้ปิดอยู่ในสวิตช์ของ " : "It is switched off in ").append(ReportPatch.reportPlace(host, thai))
                    .append(thai ? "" : ".");
        } else if (allowed) {
            message.append(thai ? "อนุญาตให้แสดงทับแอปอื่นแล้ว" : "Display over other apps is allowed.");
        } else if (hasPage) {
            message.append(thai ? "ต้องอนุญาตให้แสดงทับแอปอื่นก่อน กด อนุญาต แล้วเปิดสวิตช์ของ Maps"
                    : "It needs \"Display over other apps\". Tap Allow, then turn it on for Maps.");
        } else {
            message.append(thai ? "ต้องอนุญาตให้แสดงทับแอปอื่นก่อน สั่งครั้งเดียวผ่าน ADB:\n"
                    : "It needs \"Display over other apps\". Run this once over ADB:\n").append(command);
        }
        String toggle = on ? (thai ? "ปิดหน้าต่างลอย" : "Turn off") : (thai ? "เปิดหน้าต่างลอย" : "Turn on");
        AlertDialog.Builder builder = new AlertDialog.Builder(host)
                .setTitle(getSettingsTitle(host))
                .setMessage(message.toString())
                .setNegativeButton(thai ? "ยกเลิก" : "Cancel", null);
        if (switched && !allowed && hasPage) {
            builder.setPositiveButton(thai ? "อนุญาต" : "Allow", (dialog, which) -> {
                try {
                    host.startActivity(page);
                    ReportLog.i(TAG, "display over other apps page opened");
                } catch (RuntimeException error) {
                    note("display over other apps page not opened", error);
                }
            });
            builder.setNeutralButton(toggle, (dialog, which) -> setUserOn(host, !on));
        } else {
            builder.setPositiveButton(toggle, (dialog, which) -> setUserOn(host, !on));
            builder.setNeutralButton(thai ? "ตำแหน่งเริ่มต้น" : "Reset place", (dialog, which) -> resetSpot(host));
        }
        builder.show();
    }

    private static Intent overlayPage(Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
    }

    /** For the problem report (main process). */
    static JSONObject report(Context context) throws JSONException {
        String error = lastError;
        Spot spot;
        try {
            spot = Spot.load(context, screenSize((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)));
        } catch (RuntimeException failure) {
            spot = null;
        }
        return new JSONObject()
                .put("on", userOn(context))
                .put("allowed", Settings.canDrawOverlays(context))
                .put("guidance", reportGuiding)
                .put("card", reportCard)
                .put("showing", reportShowing)
                .put("card_starts", cardStarts)
                .put("shown", shown)
                .put("closed_by_x", closedByX)
                .put("focus_restores", focusRestores)
                .put("place", spot != null ? spot.toString() : JSONObject.NULL)
                .put("last_error", error != null ? error : JSONObject.NULL);
    }
}
