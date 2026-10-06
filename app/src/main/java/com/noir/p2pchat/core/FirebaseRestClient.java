package com.noir.p2pchat.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/** Small Firebase Authentication + Realtime Database REST client; no embedded admin credentials. */
public final class FirebaseRestClient {
    private static final String TAG = "NoirFirebase";
    private static final long REFRESH_EARLY_MS = 60_000L;
    private final String databaseUrl;
    private final String apiKey;
    private final TokenStore tokenStore;

    public FirebaseRestClient(Context context) {
        this(context, FirebaseConfig.DATABASE_URL, FirebaseConfig.API_KEY);
    }

    FirebaseRestClient(Context context, String databaseUrl, String apiKey) {
        this.databaseUrl = databaseUrl.endsWith("/")
                ? databaseUrl.substring(0, databaseUrl.length() - 1) : databaseUrl;
        this.apiKey = apiKey;
        this.tokenStore = new TokenStore(context.getApplicationContext());
    }

    public synchronized String ensureIdToken() throws IOException {
        TokenStore.Session session = tokenStore.load();
        if (session != null && session.expiresAtMillis > System.currentTimeMillis() + REFRESH_EARLY_MS) {
            return session.idToken;
        }
        if (session != null && session.refreshToken != null && !session.refreshToken.isEmpty()) {
            try {
                JSONObject response = requestJson(
                        "https://securetoken.googleapis.com/v1/token?key=" + encode(apiKey),
                        "POST",
                        "grant_type=refresh_token&refresh_token=" + encode(session.refreshToken),
                        "application/x-www-form-urlencoded",
                        false);
                String idToken = response.getString("id_token");
                String refreshToken = response.optString("refresh_token", session.refreshToken);
                String uid = response.optString("user_id", session.uid);
                long expiresAt = System.currentTimeMillis() + response.optLong("expires_in", 3600L) * 1000L;
                tokenStore.save(new TokenStore.Session(idToken, refreshToken, uid, expiresAt));
                return idToken;
            } catch (Exception e) {
                Log.w(TAG, "Firebase token refresh failed; creating a new anonymous session", e);
                tokenStore.clear();
            }
        }
        try {
            JSONObject response = requestJson(
                    "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + encode(apiKey),
                    "POST",
                    new JSONObject().put("returnSecureToken", true).toString(),
                    "application/json; charset=utf-8",
                    false);
            String idToken = response.getString("idToken");
            String refreshToken = response.getString("refreshToken");
            String uid = response.getString("localId");
            long expiresAt = System.currentTimeMillis() + response.optLong("expiresIn", 3600L) * 1000L;
            tokenStore.save(new TokenStore.Session(idToken, refreshToken, uid, expiresAt));
            return idToken;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Firebase anonymous sign-in failed: " + e.getMessage(), e);
        }
    }

    public String currentUid() {
        TokenStore.Session session = tokenStore.load();
        return session == null ? null : session.uid;
    }

    public JSONObject get(String path) throws IOException {
        return requestJson(databaseUrl + jsonPath(path) + authQuery(), "GET", null, null, true);
    }

    public JSONObject put(String path, JSONObject value) throws IOException {
        return requestJson(databaseUrl + jsonPath(path) + authQuery(), "PUT", value.toString(),
                "application/json; charset=utf-8", true);
    }

    public JSONObject patch(String path, JSONObject value) throws IOException {
        return requestJson(databaseUrl + jsonPath(path) + authQuery(), "PATCH", value.toString(),
                "application/json; charset=utf-8", true);
    }

    public JSONObject post(String path, JSONObject value) throws IOException {
        return requestJson(databaseUrl + jsonPath(path) + authQuery(), "POST", value.toString(),
                "application/json; charset=utf-8", true);
    }

    public void delete(String path) throws IOException {
        requestJson(databaseUrl + jsonPath(path) + authQuery(), "DELETE", null, null, true);
    }

    public StreamHandle stream(String path, StreamListener listener) {
        return new StreamHandle(path, listener);
    }

    private String authQuery() throws IOException {
        return "?auth=" + encode(ensureIdToken());
    }

    private static String jsonPath(String path) {
        String clean = path == null ? "" : path;
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return "/" + clean + ".json";
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static JSONObject requestJson(String endpoint, String method, String body,
                                          String contentType, boolean useAuth) throws IOException {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            InputStream source = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = source == null ? "" : readAll(source);
            if (code < 200 || code >= 300) {
                String detail = response;
                try {
                    JSONObject error = new JSONObject(response).optJSONObject("error");
                    if (error != null) detail = error.optString("message", response);
                } catch (JSONException ignored) { }
                throw new IOException("Firebase HTTP " + code + ": " + detail);
            }
            if (response.isEmpty() || "null".equals(response)) return null;
            try {
                return new JSONObject(response);
            } catch (JSONException invalidJson) {
                throw new IOException("Firebase returned invalid JSON", invalidJson);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream in = input;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) result.append(buffer, 0, count);
            return result.toString();
        }
    }

    public interface StreamListener {
        void onEvent(String event, JSONObject payload);
        void onStreamError(Exception error);
    }

    public final class StreamHandle implements AutoCloseable, Runnable {
        private final String path;
        private final StreamListener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile HttpsURLConnection connection;
        private volatile InputStream stream;
        private final Thread thread;

        private StreamHandle(String path, StreamListener listener) {
            this.path = path;
            this.listener = listener;
            this.thread = new Thread(this, "rtdb-stream-" + path.replace('/', '-'));
            this.thread.setDaemon(true);
            this.thread.start();
        }

        @Override
        public void run() {
            long backoff = 1_000L;
            while (!closed.get()) {
                try {
                    connectAndRead();
                    backoff = 1_000L;
                } catch (Exception e) {
                    if (!closed.get()) {
                        listener.onStreamError(e);
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        backoff = Math.min(backoff * 2, 30_000L);
                    }
                }
            }
        }

        private void connectAndRead() throws Exception {
            HttpsURLConnection active = (HttpsURLConnection) new URL(databaseUrl + jsonPath(path) + authQuery()).openConnection();
            connection = active;
            active.setRequestMethod("GET");
            active.setConnectTimeout(15_000);
            active.setReadTimeout(0);
            active.setInstanceFollowRedirects(true);
            active.setRequestProperty("Accept", "text/event-stream");
            active.setRequestProperty("Cache-Control", "no-cache");
            int code = active.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream error = active.getErrorStream();
                String detail = error == null ? "" : readAll(error);
                throw new IOException("Realtime stream HTTP " + code + ": " + detail);
            }
            stream = active.getInputStream();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String event = "message";
                StringBuilder data = new StringBuilder();
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatch(event, data.toString());
                        event = "message";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    }
                }
            } finally {
                stream = null;
                connection = null;
                active.disconnect();
            }
            if (!closed.get()) throw new IOException("Realtime stream closed by server");
        }

        private void dispatch(String event, String rawData) {
            if (rawData == null || rawData.isEmpty() || "keep-alive".equals(event)) return;
            try {
                JSONObject payload = new JSONObject(rawData);
                if ("cancel".equals(event)) {
                    listener.onStreamError(new IOException("Firebase denied this realtime stream: " + rawData));
                    return;
                }
                listener.onEvent(event, payload);
            } catch (JSONException e) {
                listener.onStreamError(new IOException("Invalid Firebase stream event", e));
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            InputStream activeStream = stream;
            if (activeStream != null) {
                try { activeStream.close(); } catch (IOException ignored) { }
            }
            HttpsURLConnection active = connection;
            if (active != null) active.disconnect();
            thread.interrupt();
        }
    }
}
