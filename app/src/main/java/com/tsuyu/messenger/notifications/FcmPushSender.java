package com.tsuyu.messenger.notifications;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.tsuyu.messenger.FirebaseBootstrap;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Device-to-device push with no server in between.
 *
 * <p>Configuration comes from the repository itself, not from a service: the FCM credential is
 * {@code meowmessenger-firebase-adminsdk-fbsvc-b9f8998716.json} from the {@code main} branch,
 * packaged byte for byte as {@code assets/fcm_sender_key.json} (the workflow rejects
 * {@code *firebase-adminsdk*.json} inside {@code app/}, hence the copy under a shipped name).
 *
 * <p>When this phone commits an outgoing message to the Realtime Database it signs a short
 * lived OAuth2 token with the bundled service account, reads the recipient's device tokens
 * from {@code users/{uid}/fcmTokens} and posts an FCM HTTP v1 message. The receiving phone
 * only receives {@code {chatId, senderUid, messageId}} — the words themselves still travel
 * as ciphertext through RTDB and are decrypted on the recipient's device before the
 * notification is drawn.
 */
public final class FcmPushSender {
    private static final String ASSET = "fcm_sender_key.json";
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String FCM_ENDPOINT = "https://fcm.googleapis.com/v1/projects/%s/messages:send";
    /** One push per burst of messages: the newest id always wins. */
    private static final long BURST_DELAY_MS = 600L;
    /** Still meaningful on the lock screen hours later, but never after a week. */
    private static final String TTL = "86400s";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static volatile JSONObject serviceAccount;
    private static volatile String accessToken;
    private static volatile long accessTokenExpiresAt;

    private FcmPushSender() { }

    /** Coalesces rapid-fire sends and pushes the newest message of each chat. */
    public static void pushMessage(Context context, String chatId, String senderUid,
                                   String recipientUid, String messageId) {
        if (context == null || chatId == null || recipientUid == null || messageId == null) return;
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            Pending burst = pending.get(chatId);
            if (burst == null) {
                burst = new Pending(chatId);
                pending.put(chatId, burst);
            }
            burst.senderUid = senderUid;
            burst.recipientUid = recipientUid;
            burst.messageId = messageId;
            main.removeCallbacks(burst.fire);
            main.postDelayed(burst.fire, BURST_DELAY_MS);
        }
    }

    private static final class Pending {
        final String chatId;
        volatile String senderUid;
        volatile String recipientUid;
        volatile String messageId;
        final Runnable fire;

        Pending(String chatId) {
            this.chatId = chatId;
            this.fire = () -> {
                String sender;
                String recipient;
                String id;
                synchronized (LOCK) {
                    pending.remove(chatId);
                    sender = senderUid;
                    recipient = recipientUid;
                    id = messageId;
                }
                executor.execute(() -> deliver(sender, recipient, id));
            };
        }
    }

    /** Blocking worker: mint a token, read the peer's devices and post one FCM message to each. */
    private static void deliver(String senderUid, String recipientUid, String messageId) {
        try {
            JSONObject account = serviceAccount();
            String token = accessToken(account);
            String projectId = account.optString("project_id", "");
            if (projectId.isEmpty()) return;
            String senderName = displayName(token, senderUid);
            List<String> devices = deviceTokens(token, recipientUid);
            for (String device : devices) {
                int code = post(projectId, token, device, recipientUid, senderUid, messageId, senderName);
                if (code == 404 || code == 410) {
                    // The install is gone (app uninstalled or data cleared): forget its token.
                    deleteToken(token, recipientUid, device);
                }
            }
        } catch (Exception ignored) {
            // A push is best effort: the message itself is already safely stored in RTDB.
        }
    }

    // ------------------------------------------------------------------
    // FCM HTTP v1
    // ------------------------------------------------------------------

    private static int post(String projectId, String accessToken, String device, String recipientUid,
                            String senderUid, String messageId, String senderName) throws Exception {
        JSONObject data = new JSONObject();
        data.put("chatId", com.tsuyu.messenger.data.ChatRepository.chatId(senderUid, recipientUid));
        data.put("senderUid", senderUid);
        data.put("messageId", messageId);
        if (senderName != null && !senderName.isEmpty()) data.put("senderName", senderName);

        JSONObject android = new JSONObject();
        // High priority wakes a backgrounded app so the notification appears at once.
        android.put("priority", "HIGH");
        android.put("ttl", TTL);

        JSONObject message = new JSONObject();
        message.put("token", device);
        message.put("data", data);
        message.put("android", android);

        JSONObject body = new JSONObject();
        body.put("message", message);

        HttpURLConnection connection = open(String.format(FCM_ENDPOINT, projectId), "POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        write(connection, body.toString().getBytes(StandardCharsets.UTF_8));
        int code = connection.getResponseCode();
        drain(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        return code;
    }

    // ------------------------------------------------------------------
    // Realtime Database reads with the admin token (rules would hide another user's tokens)
    // ------------------------------------------------------------------

    private static String displayName(String accessToken, String uid) {
        try {
            Object value = readJson(String.format("%s/users/%s/profile/displayName.json?access_token=%s",
                    FirebaseBootstrap.databaseUrl(), escape(uid), escape(accessToken)));
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> deviceTokens(String accessToken, String uid) {
        List<String> devices = new ArrayList<>();
        try {
            Object value = readJson(String.format("%s/users/%s/fcmTokens.json?access_token=%s",
                    FirebaseBootstrap.databaseUrl(), escape(uid), escape(accessToken)));
            if (!(value instanceof JSONObject)) return devices;
            Iterator<String> keys = ((JSONObject) value).keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key != null && !key.trim().isEmpty()) devices.add(key.trim());
            }
        } catch (Exception ignored) { }
        return devices;
    }

    private static void deleteToken(String accessToken, String uid, String device) {
        try {
            String url = String.format("%s/users/%s/fcmTokens/%s.json?access_token=%s",
                    FirebaseBootstrap.databaseUrl(), escape(uid), escape(device), escape(accessToken));
            HttpURLConnection connection = open(url, "DELETE");
            check(connection);
            connection.disconnect();
        } catch (Exception ignored) { }
    }

    /** RTDB REST answers with the raw JSON of the node: an object, a string or null. */
    private static Object readJson(String url) throws Exception {
        HttpURLConnection connection = open(url, "GET");
        int code = connection.getResponseCode();
        String body = read(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        if (code >= 400) return null;
        if (body == null || body.trim().isEmpty() || "null".equals(body.trim())) return null;
        return new org.json.JSONTokener(body).nextValue();
    }

    // ------------------------------------------------------------------
    // OAuth2: a self-signed JWT exchanged for an access token
    // ------------------------------------------------------------------

    private static String accessToken(JSONObject account) throws Exception {
        String cached = accessToken;
        if (cached != null && System.currentTimeMillis() < accessTokenExpiresAt) return cached;
        String tokenUri = account.optString("token_uri", "https://oauth2.googleapis.com/token");
        String clientEmail = account.optString("client_email", "");
        PrivateKey key = privateKey(account.optString("private_key", ""));
        long now = System.currentTimeMillis() / 1000L;
        JSONObject header = new JSONObject().put("alg", "RS256").put("typ", "JWT");
        JSONObject claims = new JSONObject()
                .put("iss", clientEmail)
                .put("scope", SCOPE)
                .put("aud", tokenUri)
                .put("iat", now)
                .put("exp", now + 3600L);
        String unsigned = base64Url(header.toString().getBytes(StandardCharsets.UTF_8))
                + "." + base64Url(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(unsigned.getBytes(StandardCharsets.UTF_8));
        String assertion = unsigned + "." + base64Url(signer.sign());

        String form = "grant_type=" + escape("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + escape(assertion);
        HttpURLConnection connection = open(tokenUri, "POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        write(connection, form.getBytes(StandardCharsets.UTF_8));
        int code = connection.getResponseCode();
        String body = read(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        if (code >= 400 || body == null) throw new IllegalStateException("Не удалось получить токен push.");
        JSONObject response = new JSONObject(body);
        String token = response.optString("access_token", null);
        if (token == null || token.isEmpty()) throw new IllegalStateException("Пустой токен push.");
        long expiresIn = response.optLong("expires_in", 3600L);
        accessToken = token;
        // Refresh a couple of minutes early so a push is never signed with a dying token.
        accessTokenExpiresAt = System.currentTimeMillis() + Math.max(60L, expiresIn - 120L) * 1000L;
        return token;
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(body, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static JSONObject serviceAccount() throws Exception {
        JSONObject cached = serviceAccount;
        if (cached != null) return cached;
        synchronized (FcmPushSender.class) {
            if (serviceAccount == null) {
                Context context = com.google.firebase.FirebaseApp.getInstance().getApplicationContext();
                try (InputStream input = context.getAssets().open(ASSET)) {
                    serviceAccount = new JSONObject(read(input));
                }
            }
            return serviceAccount;
        }
    }

    // ------------------------------------------------------------------
    // Small HTTP helpers
    // ------------------------------------------------------------------

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setDoInput(true);
        return connection;
    }

    private static void write(HttpURLConnection connection, byte[] body) throws Exception {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try (java.io.OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
    }

    private static void check(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        drain(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return null;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
        input.close();
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private static void drain(InputStream input) {
        if (input == null) return;
        try { read(input); } catch (Exception ignored) { }
    }

    private static String escape(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception impossible) {
            return value == null ? "" : value;
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
