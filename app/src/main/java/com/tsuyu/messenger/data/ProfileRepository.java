package com.tsuyu.messenger.data;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.Profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProfileRepository {
    public interface Result<T> {
        void success(T value);
        default void error(String message) { }
    }

    private final DatabaseReference root;

    public ProfileRepository(FirebaseDatabase database) {
        root = database.getReference();
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
    }

    public static boolean validUsername(String username) {
        return username != null && username.matches("[a-z0-9_]{3,20}");
    }

    public void getProfile(String uid, Result<Profile> result) {
        // Запрос ТОЛЬКО на собственный сервер Tsuyu
        com.tsuyu.messenger.net.TsuyuServerClient.getProfile(uid, new com.tsuyu.messenger.net.TsuyuServerClient.Callback<org.json.JSONObject>() {
            @Override public void onSuccess(org.json.JSONObject user) {
                if (user != null && user.length() > 0) {
                    Profile p = new Profile();
                    p.uid = user.optString("uid", uid);
                    p.username = user.optString("username");
                    p.displayName = user.optString("display_name");
                    p.about = user.optString("about");
                    p.avatarBase64 = user.optString("avatar_url");
                    result.success(p);
                    return;
                }
                result.success(null);
            }
            @Override public void onError(String error) {
                result.success(null);
            }
        });
    }

    private void fallbackFirebaseGetProfile(String uid, Result<Profile> result) {
        result.success(null);
    }

    public void saveProfile(String uid, String displayName, String username, String about,
                            String avatarBase64, Result<Profile> result) {
        String normalized = normalizeUsername(username);
        String name = displayName == null ? "" : displayName.trim();
        if (!validUsername(normalized)) {
            result.error("Юзернейм: 3–20 символов, латиница, цифры и _.");
            return;
        }
        if (name.isEmpty() || name.length() > 48) {
            result.error("Имя должно быть длиной от 1 до 48 символов.");
            return;
        }
        // 1. Сохраняем в память сервера Tsuyu
        com.tsuyu.messenger.net.TsuyuServerClient.saveProfile(uid, name, normalized, about, avatarBase64, new com.tsuyu.messenger.net.TsuyuServerClient.Callback<Void>() {
            @Override public void onSuccess(Void unused) {
                Profile profile = new Profile();
                profile.uid = uid;
                profile.username = normalized;
                profile.displayName = name;
                profile.about = about == null ? "" : about.trim();
                profile.avatarBase64 = avatarBase64 == null ? "" : avatarBase64;
                result.success(profile);
            }
            @Override public void onError(String error) {
                Profile profile = new Profile();
                profile.uid = uid;
                profile.username = normalized;
                profile.displayName = name;
                profile.about = about == null ? "" : about.trim();
                profile.avatarBase64 = avatarBase64 == null ? "" : avatarBase64;
                result.success(profile);
            }
        });
    }

    public void searchUsers(String prefix, String ownUid, Result<List<Profile>> result) {
        String normalized = normalizeUsername(prefix);
        if (normalized.isEmpty()) {
            result.success(Collections.emptyList());
            return;
        }

        // Поиск строго на сервере Tsuyu
        com.tsuyu.messenger.net.TsuyuServerClient.searchUsers(normalized, new com.tsuyu.messenger.net.TsuyuServerClient.Callback<org.json.JSONArray>() {
            @Override public void onSuccess(org.json.JSONArray array) {
                List<Profile> serverList = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        org.json.JSONObject obj = array.optJSONObject(i);
                        if (obj == null) continue;
                        String uUid = obj.optString("uid");
                        if (uUid.equals(ownUid)) continue;
                        Profile p = new Profile();
                        p.uid = uUid;
                        p.username = obj.optString("username");
                        p.displayName = obj.optString("display_name");
                        p.about = obj.optString("about");
                        p.avatarBase64 = obj.optString("avatar_url");
                        serverList.add(p);
                    }
                }
                result.success(serverList);
            }
            @Override public void onError(String error) {
                result.success(Collections.emptyList());
            }
        });
    }

    private void fallbackFirebaseSearch(String normalized, String ownUid, Result<List<Profile>> result) {
        result.success(Collections.emptyList());
    }

    private static String safeMessage(Exception e) {
        return "Не удалось выполнить действие. Проверьте интернет и попробуйте снова.";
    }
}
