package com.tsuyu.messenger.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Высокопроизводительный 2026 HTTP/REST и WebSocket клиент для прямого подключения
 * к серверу Tsuyu (Railway, Docker, VPS).
 */
public final class TsuyuServerClient {
    private static final String TAG = "TsuyuServerClient";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static String serverBaseUrl = "http://10.0.2.2:8080";
    private static String authToken = com.tsuyu.messenger.util.Settings.getString("server_auth_token", "");

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static void setServerUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        String clean = url.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://" + clean;
        }
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        serverBaseUrl = clean;
        Log.i(TAG, "Установлен адрес сервера: " + serverBaseUrl);
    }

    public static void register(String email, String password, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);
                JSONObject resp = postJson("/api/auth/register", body);
                String token = resp.optString("token");
                if (!token.isEmpty()) setAuthToken(token);
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                Log.e(TAG, "Ошибка регистрации на сервере", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void login(String email, String password, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("password", password);
                JSONObject resp = postJson("/api/auth/login", body);
                String token = resp.optString("token");
                if (!token.isEmpty()) setAuthToken(token);
                mainHandler.post(() -> callback.onSuccess(resp));
            } catch (Exception e) {
                Log.e(TAG, "Ошибка входа на сервере", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void getProfile(String uid, Callback<JSONObject> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/profile?uid=" + uid);
                JSONObject user = resp.optJSONObject("user");
                mainHandler.post(() -> callback.onSuccess(user != null ? user : new JSONObject()));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void saveProfile(String uid, String displayName, String username, String about, String avatarUrl, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("display_name", displayName);
                body.put("username", username);
                body.put("about", about);
                if (avatarUrl != null) body.put("avatar_url", avatarUrl);
                String path = "/api/profile";
                if (uid != null && !uid.isEmpty()) {
                    path += "?uid=" + uid;
                }
                postJson(path, body);
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void saveProfile(String displayName, String username, String about, String avatarUrl, Callback<Void> callback) {
        saveProfile(null, displayName, username, about, avatarUrl, callback);
    }

    public static void saveProfile(String displayName, String username, String about, Callback<Void> callback) {
        saveProfile(displayName, username, about, null, callback);
    }

    public static void searchUsers(String query, Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"));
                JSONArray arr = resp.optJSONArray("users");
                mainHandler.post(() -> callback.onSuccess(arr != null ? arr : new JSONArray()));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static String getServerUrl() {
        return serverBaseUrl;
    }

    public static void setAuthToken(String token) {
        authToken = token == null ? "" : token;
        com.tsuyu.messenger.util.Settings.putString("server_auth_token", authToken);
    }

    public static String getAuthToken() {
        return authToken;
    }

    public static void sendMessage(String chatId, String senderUid, String recipientUid,
                                   String payload, String cipher, int signalType, String kind, String msgId,
                                   Callback<String> callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("chat_id", chatId);
                body.put("sender_uid", senderUid);
                body.put("recipient_uid", recipientUid);
                body.put("payload", payload);
                if (cipher != null) body.put("cipher", cipher);
                body.put("signalType", signalType);
                body.put("kind", kind);
                if (msgId != null) body.put("id", msgId);

                JSONObject resp = postJson("/api/messages/send", body);
                String id = resp.optString("messageId", msgId);
                mainHandler.post(() -> callback.onSuccess(id));
            } catch (Exception e) {
                Log.e(TAG, "Ошибка отправки на сервер", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void fetchMessages(String peerUid, Callback<JSONArray> callback) {
        executor.execute(() -> {
            try {
                JSONObject resp = getJson("/api/messages?peer_uid=" + peerUid);
                JSONArray msgs = resp.optJSONArray("messages");
                mainHandler.post(() -> callback.onSuccess(msgs == null ? new JSONArray() : msgs));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        URL url = new URL(serverBaseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        if (!authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);

        byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        if (code < 200 || code >= 300) {
            String err = sb.toString();
            try {
                JSONObject errObj = new JSONObject(err);
                throw new Exception(errObj.optString("error", "HTTP " + code));
            } catch (Exception ignored) {
                throw new Exception("Ошибка сервера HTTP " + code);
            }
        }

        return new JSONObject(sb.toString());
    }

    public static JSONObject getJson(String endpoint) throws Exception {
        URL url = new URL(serverBaseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        if (!authToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + sb.toString());
        }

        return new JSONObject(sb.toString());
    }
}
