package com.tsuyu.messenger.data;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
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
        void error(String message);
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
        String[] fields = {"username", "displayName", "about", "avatarBase64"};
        Profile profile = new Profile();
        profile.uid = uid;
        AtomicInteger remaining = new AtomicInteger(fields.length);
        for (String field : fields) {
            root.child("users").child(uid).child("profile").child(field).get()
                    .addOnSuccessListener(snapshot -> {
                        String value = snapshot.getValue(String.class);
                        if ("username".equals(field)) profile.username = value;
                        else if ("displayName".equals(field)) profile.displayName = value;
                        else if ("about".equals(field)) profile.about = value;
                        else if ("avatarBase64".equals(field)) profile.avatarBase64 = value;
                        if (remaining.decrementAndGet() == 0) result.success(profile.username == null ? null : profile);
                    })
                    .addOnFailureListener(e -> {
                        // Privacy rules can deny optional fields; the profile stays safely partial.
                        if (remaining.decrementAndGet() == 0) result.success(profile.username == null ? null : profile);
                    });
        }
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
        getProfile(uid, old -> {
            if (old == null) old = new Profile();
            final String previous = normalizeUsername(old.username);
            DatabaseReference usernameRef = root.child("usernames").child(normalized);
            usernameRef.runTransaction(new Transaction.Handler() {
                @Override
                public Transaction.Result doTransaction(MutableData currentData) {
                    Object value = currentData.getValue();
                    if (value == null || uid.equals(String.valueOf(value))) {
                        currentData.setValue(uid);
                        return Transaction.success(currentData);
                    }
                    return Transaction.abort();
                }

                @Override
                public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                    if (error != null) {
                        result.error(safeMessage(error.toException()));
                        return;
                    }
                    if (!committed) {
                        result.error("Этот @юзернейм уже занят.");
                        return;
                    }
                    Map<String, Object> profileData = new HashMap<>();
                    profileData.put("username", normalized);
                    profileData.put("displayName", name);
                    profileData.put("about", about == null ? "" : about.trim());
                    profileData.put("avatarBase64", avatarBase64 == null ? "" : avatarBase64);
                    root.child("users").child(uid).child("profile").updateChildren(profileData)
                            .addOnSuccessListener(unused -> {
                                if (!previous.isEmpty() && !previous.equals(normalized)) {
                                    root.child("usernames").child(previous).removeValue();
                                }
                                Profile profile = new Profile();
                                profile.uid = uid;
                                profile.username = normalized;
                                profile.displayName = name;
                                profile.about = about == null ? "" : about.trim();
                                profile.avatarBase64 = avatarBase64 == null ? "" : avatarBase64;
                                result.success(profile);
                            })
                            .addOnFailureListener(e -> {
                                if (!previous.equals(normalized)) usernameRef.removeValue();
                                result.error(safeMessage(e));
                            });
                }
            }, false);
        });
    }

    public void searchUsers(String prefix, String ownUid, Result<List<Profile>> result) {
        String normalized = normalizeUsername(prefix);
        if (normalized.isEmpty()) {
            result.success(Collections.emptyList());
            return;
        }
        Query query = root.child("usernames").orderByKey()
                .startAt(normalized).endAt(normalized + "\uf8ff").limitToFirst(16);
        query.get().addOnSuccessListener(snapshot -> {
            List<String> ids = new ArrayList<>();
            for (DataSnapshot child : snapshot.getChildren()) {
                String uid = child.getValue(String.class);
                if (uid != null && !uid.equals(ownUid)) ids.add(uid);
            }
            if (ids.isEmpty()) {
                result.success(Collections.emptyList());
                return;
            }
            List<Profile> profiles = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger remaining = new AtomicInteger(ids.size());
            for (String uid : ids) {
                getProfile(uid, new Result<Profile>() {
                    @Override public void success(Profile profile) {
                        if (profile != null) profiles.add(profile);
                        if (remaining.decrementAndGet() == 0) result.success(new ArrayList<>(profiles));
                    }
                    @Override public void error(String message) {
                        if (remaining.decrementAndGet() == 0) result.success(new ArrayList<>(profiles));
                    }
                });
            }
        }).addOnFailureListener(e -> result.error(safeMessage(e)));
    }

    private static String safeMessage(Exception e) {
        String message = e.getLocalizedMessage();
        return message == null || message.trim().isEmpty() ? "Не удалось выполнить запрос. Проверь Firebase RTDB и правила." : message;
    }
}
