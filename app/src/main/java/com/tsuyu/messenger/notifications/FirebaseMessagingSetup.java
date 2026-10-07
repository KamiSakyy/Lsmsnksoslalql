package com.tsuyu.messenger.notifications;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;

public final class FirebaseMessagingSetup {
    private FirebaseMessagingSetup() {}

    public static void registerTokenListener(FirebaseApp app) {
        FirebaseMessaging.getInstance(app).getToken().addOnSuccessListener(token -> {
            if (token != null) saveToken(app, token);
        });
        FirebaseAuth.getInstance(app).addAuthStateListener(auth -> {
            if (auth.getCurrentUser() != null) {
                FirebaseMessaging.getInstance(app).getToken().addOnSuccessListener(token -> {
                    if (token != null) saveToken(app, token);
                });
            }
        });
    }

    public static void saveToken(FirebaseApp app, String token) {
        if (token == null || token.isEmpty()) return;
        if (FirebaseAuth.getInstance(app).getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance(app).getCurrentUser().getUid();
        FirebaseDatabase.getInstance(app).getReference("users").child(uid)
                .child("fcmTokens").child(token).setValue(true);
    }
}
