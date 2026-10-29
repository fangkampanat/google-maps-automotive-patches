package app.morphe.extension.mapsautomotive.patches;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The problem report's files in this app's private storage (files/morphe_reports), removed with the
 * app: the installation id, copies of crash logs, queued report bodies and the list of reports.
 * Crash copies are written by any process; the list and the queue are used by the main process.
 */
final class ReportStore {
    static final int MAX_QUEUED = 5, MAX_CRASHES = 3, DEFAULT_MAX_BYTES = 512 * 1024;
    static final long DAY_MS = 24L * 60 * 60 * 1000, QUEUE_MAX_AGE_MS = 7 * DAY_MS, CRASH_MAX_AGE_MS = 14 * DAY_MS;
    static final String QUEUED = "queued", SENT = "sent", FAILED = "failed", EXPIRED = "expired";
    private static final String PREFERENCES = "morphe_reports", KEY_INSTALL_ID = "install_id", KEY_MAX_BYTES = "max_bytes",
            KEY_CRASH_SEEN = "crash_seen", KEY_CRASH_TOASTED = "crash_toasted";
    private static final String LIST = "reports.json", QUEUE = "queue", CRASH = "crash";

    private ReportStore() {
    }

    /** One report in the list: queued, sent (with its ID), failed (with the server's code) or expired. */
    static final class Entry {
        String key, state, error, id, deleteToken, receivedAt, expiresAt;
        long created, next;
        int attempts, bytes;
        boolean shrunk;
        /** The newest crash file in the report, marked seen once the report is queued. */
        String crash;

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("key", key).put("state", state).put("created", created).put("next", next).put("attempts", attempts)
                    .put("bytes", bytes).put("shrunk", shrunk);
            json.putOpt("error", error).putOpt("id", id).putOpt("delete_token", deleteToken).putOpt("received_at", receivedAt)
                    .putOpt("expires_at", expiresAt).putOpt("crash", crash);
            return json;
        }

        static Entry of(JSONObject json) {
            Entry entry = new Entry();
            entry.key = json.optString("key");
            entry.state = json.optString("state");
            entry.created = json.optLong("created");
            entry.next = json.optLong("next");
            entry.attempts = json.optInt("attempts");
            entry.bytes = json.optInt("bytes");
            entry.shrunk = json.optBoolean("shrunk");
            entry.error = text(json, "error");
            entry.id = text(json, "id");
            entry.deleteToken = text(json, "delete_token");
            entry.receivedAt = text(json, "received_at");
            entry.expiresAt = text(json, "expires_at");
            entry.crash = text(json, "crash");
            return entry;
        }

        private static String text(JSONObject json, String name) {
            return json.isNull(name) ? null : json.optString(name, null);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static File root(Context context) {
        return new File(context.getFilesDir(), "morphe_reports");
    }

    /** A random UUID for this installation, made on first use; no hardware identifier. */
    static synchronized String installId(Context context) {
        SharedPreferences preferences = preferences(context);
        String id = preferences.getString(KEY_INSTALL_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            preferences.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        return id;
    }

    /** The body limit the server last named in a 413, else 512 KiB. */
    static int maxBytes(Context context) {
        int value = preferences(context).getInt(KEY_MAX_BYTES, DEFAULT_MAX_BYTES);
        return value > 0 ? value : DEFAULT_MAX_BYTES;
    }

    static void setMaxBytes(Context context, int value) {
        if (value > 0) preferences(context).edit().putInt(KEY_MAX_BYTES, value).apply();
    }

    // Crash copies. The public crash file in Documents can be read back only while this installation
    // owns it, so the report reads its own copy.

    /** From the crashing thread: a copy of the crash log, then the newest three of the last 14 days are kept. */
    static void saveCrash(Context context, String name, byte[] contents) {
        File dir = new File(root(context), CRASH);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try {
            writeFile(new File(dir, name), contents);
        } catch (IOException ignored) {
            // The public crash file is still written.
        }
        crashes(context);
    }

    /** The kept crash copies, newest first; older and surplus copies are deleted. */
    static List<File> crashes(Context context) {
        File[] files = new File(root(context), CRASH).listFiles(file -> file.isFile() && file.getName().startsWith("crash_"));
        List<File> kept = new ArrayList<>();
        if (files == null) return kept;
        // The names end with the UTC time, so they sort by time.
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        long oldest = System.currentTimeMillis() - CRASH_MAX_AGE_MS;
        for (File file : files) {
            if (kept.size() < MAX_CRASHES && file.lastModified() >= oldest) kept.add(file);
            else //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
        return kept;
    }

    /** The newest crash copy not yet in a queued report, or null. */
    static String pendingCrash(Context context) {
        List<File> crashes = crashes(context);
        if (crashes.isEmpty()) return null;
        String newest = crashes.get(0).getName();
        return newest.equals(preferences(context).getString(KEY_CRASH_SEEN, null)) ? null : newest;
    }

    static void markCrashSeen(Context context, String name) {
        if (name != null) preferences(context).edit().putString(KEY_CRASH_SEEN, name).apply();
    }

    /** True once per new crash: whether the start after it shows its toast. */
    static boolean takeCrashToast(Context context) {
        String crash = pendingCrash(context);
        SharedPreferences preferences = preferences(context);
        if (crash == null || crash.equals(preferences.getString(KEY_CRASH_TOASTED, null))) return false;
        preferences.edit().putString(KEY_CRASH_TOASTED, crash).apply();
        return true;
    }

    // The report list and the queue.

    /** The reports, newest first. Sent reports past their expiry and old finished ones are dropped. */
    static synchronized List<Entry> entries(Context context) {
        List<Entry> entries = new ArrayList<>();
        File file = new File(root(context), LIST);
        if (!file.isFile()) return entries;
        boolean changed = false;
        try {
            JSONArray array = new JSONArray(new String(readFile(file), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            for (int i = 0; i < array.length(); i++) {
                Entry entry = Entry.of(array.getJSONObject(i));
                boolean expired = SENT.equals(entry.state) ? ReportBuilder.parseUtc(entry.expiresAt) < now
                        : !QUEUED.equals(entry.state) && now - entry.created > CRASH_MAX_AGE_MS;
                if (expired || entry.key.isEmpty()) {
                    deleteBody(context, entry.key);
                    changed = true;
                } else {
                    entries.add(entry);
                }
            }
        } catch (IOException | JSONException error) {
            ReportLog.w(ReportPatch.TAG, "report list unreadable, starting empty", error);
            changed = true;
        }
        if (changed) save(context, entries);
        return entries;
    }

    static synchronized int queuedCount(Context context) {
        int count = 0;
        for (Entry entry : entries(context)) if (QUEUED.equals(entry.state)) count++;
        return count;
    }

    /** Queues the exact bytes the user agreed to send. Null when the queue is full or cannot be written. */
    static synchronized Entry enqueue(Context context, byte[] body, String crash) {
        List<Entry> entries = entries(context);
        int queued = 0;
        for (Entry entry : entries) if (QUEUED.equals(entry.state)) queued++;
        if (queued >= MAX_QUEUED) return null;
        Entry entry = new Entry();
        entry.key = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        entry.state = QUEUED;
        entry.created = System.currentTimeMillis();
        entry.bytes = body.length;
        entry.crash = crash;
        try {
            File dir = new File(root(context), QUEUE);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no queue folder");
            writeFile(bodyFile(context, entry.key), body);
        } catch (IOException error) {
            ReportLog.w(ReportPatch.TAG, "report not queued", error);
            return null;
        }
        entries.add(0, entry);
        save(context, entries);
        return entry;
    }

    static synchronized byte[] body(Context context, String key) {
        try {
            return readFile(bodyFile(context, key));
        } catch (IOException error) {
            return null;
        }
    }

    static synchronized boolean replaceBody(Context context, Entry entry, byte[] body) {
        try {
            writeFile(bodyFile(context, entry.key), body);
            entry.bytes = body.length;
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    /** Stores the entry's new state; a report that is no longer queued loses its body. */
    static synchronized void update(Context context, Entry changed) {
        List<Entry> entries = entries(context);
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).key.equals(changed.key)) continue;
            entries.set(i, changed);
            if (!QUEUED.equals(changed.state)) deleteBody(context, changed.key);
            save(context, entries);
            return;
        }
    }

    static synchronized void remove(Context context, String key) {
        List<Entry> entries = entries(context);
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).key.equals(key)) continue;
            entries.remove(i);
            deleteBody(context, key);
            save(context, entries);
            return;
        }
    }

    private static void save(Context context, List<Entry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) array.put(entry.toJson());
            File dir = root(context);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no report folder");
            // Written whole, then renamed over the list.
            File temporary = new File(dir, LIST + ".tmp");
            writeFile(temporary, array.toString().getBytes(StandardCharsets.UTF_8));
            if (!temporary.renameTo(new File(dir, LIST))) throw new IOException("report list not replaced");
        } catch (IOException | JSONException error) {
            ReportLog.w(ReportPatch.TAG, "report list not saved", error);
        }
    }

    private static File bodyFile(Context context, String key) {
        return new File(new File(root(context), QUEUE), key + ".json");
    }

    private static void deleteBody(Context context, String key) {
        //noinspection ResultOfMethodCallIgnored
        bodyFile(context, key).delete();
    }

    private static void writeFile(File file, byte[] contents) throws IOException {
        try (OutputStream out = new FileOutputStream(file, false)) {
            out.write(contents);
        }
    }

    static byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] contents = new byte[(int) file.length()];
            int offset = 0;
            while (offset < contents.length) {
                int read = in.read(contents, offset, contents.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return offset == contents.length ? contents : Arrays.copyOf(contents, offset);
        }
    }
}
