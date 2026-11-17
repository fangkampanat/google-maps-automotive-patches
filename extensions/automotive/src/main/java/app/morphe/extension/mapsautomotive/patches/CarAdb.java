package app.morphe.extension.mapsautomotive.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;

import app.morphe.extension.shared.Utils;

/**
 * MicroG-RE on the battery whitelist from the car itself (the README's install step 1), through the car's own ADB.
 * <p>
 * Owners turn on the head unit's wireless ADB before they install Maps Car, so adbd listens on port 5555. On BYD
 * DiLink 3 the system's battery screens do not exist, and only ADB can whitelist MicroG-RE. When the port answers,
 * the BYD MicroG-RE dialog offers "Do it now": this client connects to 127.0.0.1:5555 and runs one fixed command,
 * {@code dumpsys deviceidle whitelist +<MicroG-RE>}, then closes. The result is read back with
 * PowerManager.isIgnoringBatteryOptimizations and kept for the problem report.
 * <p>
 * The key is Maps Car's own, made once in the Android Keystore, which never lets it out of the head unit's keystore.
 * The client signs adbd's token with it first; when adbd does not trust it yet, it sends the public key and Android
 * asks "Allow USB debugging?". The owner ticks "Always allow", so later runs need no answer. Android 10's adbd gives
 * Allow to one waiting connection, and another ADB client that stays connected (a computer after the install) takes
 * it; while Android asks, the client therefore tries a new connection signed with the key every 3 s, which adbd lets in
 * as soon as Always allow has stored the key. Without the keystore, a key in memory is used for this run only.
 */
public final class CarAdb {
    private static final String TAG = "MorpheGmsCore";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int PROBE_TIMEOUT_MS = 400, CONNECT_TIMEOUT_MS = 2000, ALLOW_TIMEOUT_MS = 60_000, COMMAND_TIMEOUT_MS = 15_000,
            RECONNECT_EVERY_MS = 3000, POLL_MS = 250;
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    /** The first protocol version: every message carries a checksum, which adbd accepts in any version. */
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096, MAX_MESSAGE = 1024 * 1024, MAX_OUTPUT = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int KEY_BITS = 2048, LOCAL_ID = 1;
    private static final String KEY_ALIAS = "morphe_car_adb", KEYSTORE = "AndroidKeyStore";
    /** adbd checks a signature as PKCS#1 v1.5 over SHA-1 with the token as the digest: this DigestInfo goes first. */
    private static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14};
    private static final String RESULT_FILE = "morphe_gms_whitelist.json";

    private static volatile boolean running, canceled;
    private static volatile Socket socket, retry;

    private CarAdb() {
    }

    /** The one command this client runs. Plain Java. */
    static String command(String gmsPackage) {
        return "dumpsys deviceidle whitelist +" + gmsPackage;
    }

    /** Whether the car's adbd answers on 127.0.0.1:5555. Blocks for up to 0.4 s: not on the main thread. */
    public static boolean portOpen() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, PORT), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    /**
     * From the BYD MicroG-RE dialog's "Do it now": a waiting dialog, the connection and the command off the main
     * thread, then a dialog with the result ([adbCommand] is shown again when it did not work).
     */
    public static void whitelist(Activity activity, String gmsPackage, String adbCommand) {
        if (running || activity.isFinishing() || activity.isDestroyed()) return;
        boolean thai = UiDensityPatch.isThai(activity);
        AlertDialog waiting;
        try {
            waiting = new AlertDialog.Builder(activity)
                    .setTitle(thai ? "ตั้งค่า MicroG-RE" : "MicroG-RE setting")
                    .setMessage(thai
                            ? "ถ้า Android ถามเรื่องการแก้ไขข้อบกพร่องผ่าน USB ให้ติ๊ก อนุญาตจากคอมพิวเตอร์เครื่องนี้เสมอ"
                            + " แล้วกด อนุญาต ครั้งต่อไป Maps จะทำได้เองโดยไม่ต้องถามอีก Maps รอได้ถึง 1 นาที"
                            : "If Android asks \"Allow USB debugging?\", tick \"Always allow from this computer\" and tap Allow."
                            + " Next time Maps can do this without asking. Maps waits up to 1 minute.")
                    .setCancelable(false)
                    .setNegativeButton(thai ? "ยกเลิก" : "Cancel", (dialog, which) -> cancel())
                    .show();
        } catch (RuntimeException error) {
            ReportLog.w(TAG, "MicroG-RE whitelist not started", error);
            return;
        }
        running = true;
        canceled = false;
        Context app = activity.getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            Attempt attempt = run(app, gmsPackage);
            new Handler(Looper.getMainLooper()).post(() -> {
                running = false;
                try {
                    if (waiting.isShowing()) waiting.dismiss();
                } catch (RuntimeException ignored) {
                    // The activity went away.
                }
                showResult(activity, attempt, adbCommand);
            });
        });
    }

    private static void cancel() {
        canceled = true;
        close(socket);
        close(retry);
    }

    /**
     * One attempt's outcome, for the dialog, the log and the report. [result] names the step reached ("key",
     * "connection", "no_answer" while Android asks, "command"), then "added" or "not_added"; "canceled" when the owner
     * stopped it. [auth]: how adbd let the client in ("trusted_key", "allow", "allow_reconnect", "no_auth"); [key]:
     * "keystore" or "memory".
     */
    static final class Attempt {
        String result = "key";
        String output = "";
        String auth = "";
        String key = "";
        int reconnects;
        boolean whitelisted;
        final long at = System.currentTimeMillis();
    }

    private static Attempt run(Context context, String gmsPackage) {
        Attempt attempt = new Attempt();
        String command = command(gmsPackage);
        try {
            ReportLog.i(TAG, "MicroG-RE whitelist over the car's ADB: connecting to " + HOST + ":" + PORT);
            Key key = key();
            attempt.key = key.kind;
            attempt.output = exec(key, command, attempt);
            attempt.whitelisted = whitelisted(context, gmsPackage);
            attempt.result = attempt.whitelisted ? "added" : "not_added";
        } catch (IOException | RuntimeException | GeneralSecurityException error) {
            if (canceled) attempt.result = "canceled";
            ReportLog.w(TAG, "MicroG-RE whitelist over the car's ADB stopped at " + attempt.result, error);
        } finally {
            socket = null;
            retry = null;
        }
        // Read back even after an error: Allow may have come late.
        if (!attempt.whitelisted && whitelisted(context, gmsPackage)) {
            attempt.whitelisted = true;
            attempt.result = "added";
        }
        ReportLog.i(TAG, "MicroG-RE whitelist over the car's ADB: " + attempt.result
                + (attempt.output.isEmpty() ? "" : ", output \"" + oneLine(attempt.output) + "\"")
                + ", whitelisted " + attempt.whitelisted + ", key " + attempt.key
                + (attempt.auth.isEmpty() ? "" : ", signed in by " + attempt.auth)
                + (attempt.reconnects == 0 ? "" : ", " + attempt.reconnects + " reconnects"));
        save(context, attempt);
        return attempt;
    }

    private static boolean whitelisted(Context context, String gmsPackage) {
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null && power.isIgnoringBatteryOptimizations(gmsPackage);
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** The client's key: its private half and the public key adbd gets. */
    static final class Key {
        final PrivateKey privateKey;
        final RSAPublicKey publicKey;
        final String kind;

        Key(PrivateKey privateKey, RSAPublicKey publicKey, String kind) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.kind = kind;
        }
    }

    // Maps Car's key in the Android Keystore, made on first use; a key in memory when the keystore fails.
    private static Key key() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            if (!store.containsAlias(KEY_ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE);
                generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4))
                        .setDigests(KeyProperties.DIGEST_NONE)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build());
                generator.generateKeyPair();
                ReportLog.i(TAG, "MicroG-RE whitelist: Maps' ADB key made in the keystore");
            }
            PrivateKey privateKey = (PrivateKey) store.getKey(KEY_ALIAS, null);
            Certificate certificate = store.getCertificate(KEY_ALIAS);
            if (privateKey == null || certificate == null || !(certificate.getPublicKey() instanceof RSAPublicKey)) {
                throw new GeneralSecurityException("keystore key incomplete");
            }
            RSAPublicKey publicKey = (RSAPublicKey) certificate.getPublicKey();
            if (publicKey.getModulus().bitLength() != KEY_BITS) throw new GeneralSecurityException("keystore key not 2048-bit");
            return new Key(privateKey, publicKey, "keystore");
        } catch (Exception error) {
            ReportLog.w(TAG, "MicroG-RE whitelist: no keystore key, a key in memory for this run", error);
            return memoryKey();
        }
    }

    static Key memoryKey() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
        KeyPair pair = generator.generateKeyPair();
        return new Key(pair.getPrivate(), (RSAPublicKey) pair.getPublic(), "memory");
    }

    /** adbd's token signed as adb signs it: PKCS#1 v1.5 with SHA-1's DigestInfo, the token as the digest. */
    static byte[] sign(Key key, byte[] token) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(key.privateKey);
        signature.update(SHA1_DIGEST_INFO);
        signature.update(token);
        return signature.sign();
    }

    // The ADB session: sign in, open one shell stream, read it. [attempt].result names the step reached when one fails.
    private static String exec(Key key, String command, Attempt attempt) throws IOException, GeneralSecurityException {
        Socket connection = signIn(key, attempt);
        // Cancel closes it during the command too.
        socket = connection;
        try {
            attempt.result = "command";
            connection.setSoTimeout(COMMAND_TIMEOUT_MS);
            OutputStream out = connection.getOutputStream();
            DataInputStream in = new DataInputStream(connection.getInputStream());
            write(out, A_OPEN, LOCAL_ID, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int remote;
            while (true) {
                Message message = read(in);
                if (message.arg1 != LOCAL_ID) continue;
                if (message.command == A_OKAY) {
                    remote = message.arg0;
                } else if (message.command == A_WRTE) {
                    remote = message.arg0;
                    if (output.size() < MAX_OUTPUT) output.write(message.data, 0, Math.min(message.data.length, MAX_OUTPUT - output.size()));
                    write(out, A_OKAY, LOCAL_ID, remote, null);
                } else if (message.command == A_CLSE) {
                    write(out, A_CLSE, LOCAL_ID, message.arg0, null);
                    break;
                }
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            close(connection);
            socket = null;
        }
    }

    /**
     * A connection adbd has let in: at once when it trusts the key; else after the public key, by Allow on this
     * connection, or (with Always allow ticked) on a new connection signed with the key, tried every 3 s while Android
     * asks.
     */
    static Socket signIn(Key key, Attempt attempt) throws IOException, GeneralSecurityException {
        attempt.result = "connection";
        // Canceled while the key was being made: no socket existed yet, so nothing closed one.
        if (canceled) throw new IOException("canceled");
        Socket first = open();
        socket = first;
        try {
            attempt.result = "no_answer";
            DataInputStream in = new DataInputStream(first.getInputStream());
            OutputStream out = first.getOutputStream();
            long deadline = System.currentTimeMillis() + ALLOW_TIMEOUT_MS;
            boolean signed = false, asking = false, open = true;
            long nextTry = 0;
            while (true) {
                if (canceled) throw new IOException("canceled");
                long now = System.currentTimeMillis();
                if (now > deadline) throw new SocketTimeoutException("no Allow");
                if (open) {
                    Message message;
                    try {
                        message = poll(first, in, POLL_MS);
                    } catch (EOFException closed) {
                        if (!asking) {
                            attempt.result = "connection";
                            throw closed;
                        }
                        // adbd closed the asking connection: only the signed connections are left.
                        open = false;
                        message = null;
                    }
                    if (message != null && message.command == A_CNXN) {
                        attempt.auth = asking ? "allow" : signed ? "trusted_key" : "no_auth";
                        socket = null;
                        Socket done = first;
                        first = null;
                        return done;
                    }
                    if (message != null && message.command == A_AUTH && message.arg0 == AUTH_TOKEN) {
                        if (!signed) {
                            write(out, A_AUTH, AUTH_SIGNATURE, 0, sign(key, message.data));
                            signed = true;
                        } else if (!asking) {
                            // Not trusted yet: the public key, and Android asks the owner.
                            write(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload(key.publicKey));
                            asking = true;
                            nextTry = now + RECONNECT_EVERY_MS;
                            ReportLog.i(TAG, "MicroG-RE whitelist over the car's ADB: waiting for Allow");
                        }
                    }
                } else {
                    sleep(POLL_MS);
                }
                if (asking && System.currentTimeMillis() >= nextTry) {
                    nextTry = System.currentTimeMillis() + RECONNECT_EVERY_MS;
                    attempt.reconnects++;
                    Socket trusted = signedConnection(key);
                    if (trusted != null) {
                        attempt.auth = "allow_reconnect";
                        return trusted;
                    }
                }
            }
        } finally {
            close(first);
            socket = null;
        }
    }

    // A new connection signed with the key: returned when adbd trusts the key, else closed and null. It never sends the
    // public key, so Android does not ask again.
    private static Socket signedConnection(Key key) {
        Socket connection = null;
        try {
            if (canceled) return null;
            connection = open();
            retry = connection;
            connection.setSoTimeout(CONNECT_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(connection.getInputStream());
            OutputStream out = connection.getOutputStream();
            boolean signed = false;
            while (true) {
                Message message = read(in);
                if (message.command == A_CNXN) {
                    Socket done = connection;
                    connection = null;
                    return done;
                }
                if (message.command == A_AUTH && message.arg0 == AUTH_TOKEN) {
                    if (signed) return null;
                    write(out, A_AUTH, AUTH_SIGNATURE, 0, sign(key, message.data));
                    signed = true;
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException error) {
            return null;
        } finally {
            close(connection);
            retry = null;
        }
    }

    // Connected, with the client's CNXN sent.
    private static Socket open() throws IOException {
        Socket connection = new Socket();
        try {
            connection.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            connection.setSoTimeout(CONNECT_TIMEOUT_MS);
            write(connection.getOutputStream(), A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
            return connection;
        } catch (IOException | RuntimeException error) {
            close(connection);
            throw error;
        }
    }

    // The next message, or null when none begins within [waitMs]; the rest of a message that has begun is read in full.
    private static Message poll(Socket connection, DataInputStream in, int waitMs) throws IOException {
        connection.setSoTimeout(waitMs);
        int first;
        try {
            first = in.read();
        } catch (SocketTimeoutException quiet) {
            return null;
        }
        if (first < 0) throw new EOFException("closed");
        connection.setSoTimeout(CONNECT_TIMEOUT_MS);
        return read(in, first);
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static void close(Socket connection) {
        try {
            if (connection != null) connection.close();
        } catch (IOException ignored) {
            // Closed.
        }
    }

    /** One ADB message. */
    static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    static void write(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(header(command, arg0, arg1, data));
        if (data != null && data.length > 0) out.write(data);
        out.flush();
    }

    /** The 24-byte header: command, two arguments, length, byte sum and the command's complement, little-endian. */
    static byte[] header(int command, int arg0, int arg1, byte[] data) {
        int sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data != null ? data.length : 0).putInt(sum).putInt(~command)
                .array();
    }

    static Message read(InputStream stream) throws IOException {
        DataInputStream in = stream instanceof DataInputStream ? (DataInputStream) stream : new DataInputStream(stream);
        int first = in.read();
        if (first < 0) throw new EOFException("closed");
        return read(in, first);
    }

    // A message whose first header byte has been read.
    private static Message read(DataInputStream in, int first) throws IOException {
        byte[] header = new byte[24];
        header[0] = (byte) first;
        in.readFully(header, 1, 23);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message message = new Message();
        message.command = buffer.getInt();
        message.arg0 = buffer.getInt();
        message.arg1 = buffer.getInt();
        int length = buffer.getInt();
        buffer.getInt();
        if (buffer.getInt() != ~message.command) throw new IOException("not an ADB message");
        if (length < 0 || length > MAX_MESSAGE) throw new IOException("ADB message of " + length + " bytes");
        message.data = new byte[length];
        in.readFully(message.data);
        return message;
    }

    /**
     * The public key as adbd reads it: Android's RSAPublicKey structure (word count, -1/n[0] mod 2^32, the modulus
     * and R^2 mod n as little-endian 32-bit words, the exponent) in Base64, a space, a name and a NUL.
     */
    static byte[] publicKeyPayload(RSAPublicKey key) {
        BigInteger modulus = key.getModulus();
        int words = KEY_BITS / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(modulus.mod(r32).modInverse(r32)).mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(modulus);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + words * 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(words).putInt(n0inv.intValue());
        putWords(buffer, modulus, words);
        putWords(buffer, rr, words);
        buffer.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(buffer.array(), Base64.NO_WRAP) + " maps-car@head-unit\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer buffer, BigInteger value, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) buffer.putInt(value.shiftRight(32 * i).and(mask).intValue());
    }

    private static String oneLine(String text) {
        String line = text.replace('\n', ' ').replace('\r', ' ').trim();
        return line.length() <= 200 ? line : line.substring(0, 199) + "…";
    }

    private static void showResult(Activity activity, Attempt attempt, String adbCommand) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        boolean thai = UiDensityPatch.isThai(activity);
        String message;
        if (attempt.whitelisted) {
            message = thai ? "เรียบร้อย MicroG-RE อยู่ใน whitelist แบตแล้ว" : "Done. MicroG-RE is on the battery whitelist.";
        } else {
            String reason;
            switch (attempt.result) {
                case "canceled":
                    reason = thai ? "คุณยกเลิกแล้ว" : "You canceled it.";
                    break;
                case "no_answer":
                    reason = thai
                            ? "Android ไม่ให้ Maps เข้าภายใน 1 นาที ถ้ามีคอมพิวเตอร์ต่อ ADB ของรถอยู่ ให้ตัดการเชื่อมต่อ"
                            + " หรือติ๊ก อนุญาตจากคอมพิวเตอร์เครื่องนี้เสมอ แล้วลองใหม่"
                            : "Android did not let Maps in within 1 minute. If a computer is connected to the car's ADB,"
                            + " disconnect it, or tick \"Always allow from this computer\", then try again.";
                    break;
                case "connection":
                    reason = thai ? "ADB ของรถไม่ตอบ" : "The car's ADB did not answer.";
                    break;
                default:
                    reason = thai ? "คำสั่งไม่สำเร็จ" : "The command did not work.";
            }
            message = (thai ? "MicroG-RE ยังไม่อยู่ใน whitelist แบต " : "MicroG-RE is not on the battery whitelist yet. ") + reason
                    + (thai ? " ลองอีกครั้งตอนเปิดแอปครั้งหน้า หรือสั่งจากคอมพิวเตอร์:\n"
                    : " Try again at the next start, or run this from a computer:\n")
                    + adbCommand;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(thai ? "ตั้งค่า MicroG-RE" : "MicroG-RE setting")
                    .setMessage(message)
                    .setPositiveButton(thai ? "ตกลง" : "OK", null)
                    .show();
        } catch (RuntimeException error) {
            ReportLog.w(TAG, "MicroG-RE whitelist result not shown", error);
        }
    }

    // The last attempt, for the problem report from any process: a small file written whole.
    private static void save(Context context, Attempt attempt) {
        try {
            JSONObject json = new JSONObject()
                    .put("result", attempt.result)
                    .put("whitelisted", attempt.whitelisted)
                    .put("at", attempt.at)
                    .put("key", attempt.key)
                    .put("auth", attempt.auth)
                    .put("reconnects", attempt.reconnects)
                    .put("output", oneLine(attempt.output));
            File file = new File(context.getFilesDir(), RESULT_FILE), temporary = new File(context.getFilesDir(), RESULT_FILE + ".tmp");
            try (OutputStream out = new FileOutputStream(temporary, false)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(file)) ReportLog.w(TAG, "MicroG-RE whitelist result not saved");
        } catch (IOException | JSONException | RuntimeException error) {
            ReportLog.w(TAG, "MicroG-RE whitelist result not saved", error);
        }
    }

    /** The problem report's provider.whitelist_from_car: the last attempt, or null. */
    static Object report(Context context) {
        try {
            File file = new File(context.getFilesDir(), RESULT_FILE);
            if (!file.isFile()) return JSONObject.NULL;
            JSONObject saved = new JSONObject(new String(ReportStore.readFile(file), StandardCharsets.UTF_8));
            return new JSONObject()
                    .put("result", saved.optString("result", "error"))
                    .put("whitelisted", saved.optBoolean("whitelisted"))
                    .put("at", ReportBuilder.formatUtc(saved.optLong("at")))
                    .put("key", saved.optString("key", ""))
                    .put("auth", saved.optString("auth", ""))
                    .put("reconnects", saved.optInt("reconnects"))
                    .put("output", saved.optString("output", ""));
        } catch (IOException | JSONException | RuntimeException error) {
            return JSONObject.NULL;
        }
    }
}
