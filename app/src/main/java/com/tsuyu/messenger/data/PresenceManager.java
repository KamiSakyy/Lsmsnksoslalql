package com.tsuyu.messenger.data;

import android.content.Context;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public final class PresenceManager {
    private final Context context;
    private final FirebaseDatabase database;
    private DatabaseReference connectedRef;
    private ValueEventListener connectedListener;
    private String uid;

    public PresenceManager(Context context, FirebaseDatabase database) {
        this.context = context.getApplicationContext();
        this.database = database;
    }

    public synchronized boolean isGhostMode() {
        return uid != null && context.getSharedPreferences("tsuyu_settings", Context.MODE_PRIVATE).getBoolean("ghost_" + uid, false);
    }

    public synchronized void setGhostMode(String accountUid, boolean enabled) {
        uid = accountUid;
        context.getSharedPreferences("tsuyu_settings", Context.MODE_PRIVATE).edit().putBoolean("ghost_" + accountUid, enabled).apply();
        if (enabled) {
            DatabaseReference presence = database.getReference("users").child(accountUid).child("presence");
            Map<String, Object> offlineOnly = new HashMap<>();
            offlineOnly.put("online", false);
            presence.onDisconnect().cancel();
            presence.updateChildren(offlineOnly);
            stopListenerOnly();
        } else {
            start(accountUid);
        }
    }

    public synchronized void start(String accountUid) {
        stopListenerOnly();
        uid = accountUid;
        if (isGhostMode()) return;
        connectedRef = database.getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                if (!Boolean.TRUE.equals(snapshot.getValue(Boolean.class)) || uid == null || isGhostMode()) return;
                DatabaseReference presence = database.getReference("users").child(uid).child("presence");
                Map<String, Object> offline = new HashMap<>();
                offline.put("online", false);
                offline.put("lastSeen", ServerValue.TIMESTAMP);
                presence.onDisconnect().updateChildren(offline);
                Map<String, Object> online = new HashMap<>();
                online.put("online", true);
                online.put("lastSeen", ServerValue.TIMESTAMP);
                presence.updateChildren(online);
            }

            @Override
            public void onCancelled(com.google.firebase.database.DatabaseError error) { }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    public synchronized void signOut() {
        String oldUid = uid;
        boolean ghost = isGhostMode();
        stopListenerOnly();
        uid = null;
        if (oldUid != null) {
            Map<String, Object> offline = new HashMap<>();
            offline.put("online", false);
            if (!ghost) offline.put("lastSeen", ServerValue.TIMESTAMP);
            database.getReference("users").child(oldUid).child("presence").updateChildren(offline);
        }
    }

    private void stopListenerOnly() {
        if (connectedRef != null && connectedListener != null) connectedRef.removeEventListener(connectedListener);
        connectedRef = null;
        connectedListener = null;
    }
}
