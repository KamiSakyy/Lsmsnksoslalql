package com.tsuyu.messenger.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.app.PendingIntent;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.LocalMessageCache;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatMessage;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Optional RTDB listener kept alive as a user-visible remote-messaging foreground service. */
public final class TsuyuSyncService extends Service {
    public static final String EXTRA_UID = "uid";
    public static final String ACTION_STOP = "com.tsuyu.messenger.STOP_SYNC";
    private static final String SERVICE_CHANNEL = "tsuyu_background_connection";
    private static final int SERVICE_NOTIFICATION_ID = 4401;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Query> queries = new HashMap<>();
    private final Map<String, ChildEventListener> listeners = new HashMap<>();
    private final Set<String> initialized = new HashSet<>();
    private ValueEventListener chatsListener;
    private DatabaseReference chatsIndex;
    private String uid;
    private FirebaseDatabase database;

    @Override public void onCreate() {
        super.onCreate();
        ensureServiceChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().remove("sync_uid").apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        android.content.SharedPreferences settings = getSharedPreferences("tsuyu_settings", MODE_PRIVATE);
        uid = intent == null ? settings.getString("sync_uid", null) : intent.getStringExtra(EXTRA_UID);
        if (uid == null || uid.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        settings.edit().putString("sync_uid", uid).apply();
        FirebaseApp app;
        try { app = FirebaseApp.getInstance(); }
        catch (IllegalStateException e) { stopSelf(); return START_NOT_STICKY; }
        FirebaseAuth auth = FirebaseAuth.getInstance(app);
        if (auth.getCurrentUser() == null || !uid.equals(auth.getCurrentUser().getUid())) { stopSelf(); return START_NOT_STICKY; }
        database = FirebaseDatabase.getInstance(app);
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification());
        attachChatIndex();
        return START_STICKY;
    }

    private void attachChatIndex() {
        chatsIndex = database.getReference("userChats").child(uid);
        chatsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                Set<String> current = new HashSet<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String id = child.getKey();
                    if (id != null) { current.add(id); if (!listeners.containsKey(id)) attachLatest(id); }
                }
                for (String existing : new HashSet<>(listeners.keySet())) if (!current.contains(existing)) detachChat(existing);
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        chatsIndex.addValueEventListener(chatsListener);
    }

    private void attachLatest(String chatId) {
        Query query = database.getReference("chats").child(chatId).child("messages").orderByKey().limitToLast(1);
        query.get().addOnSuccessListener(snapshot -> {
            String baseline = null;
            for (DataSnapshot child : snapshot.getChildren()) baseline = child.getKey();
            final String initialId = baseline;
            ChildEventListener listener = new ChildEventListener() {
                @Override public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                    String messageId = snapshot.getKey();
                    if (messageId == null) return;
                    if (!initialized.contains(chatId)) {
                        initialized.add(chatId);
                        if (messageId.equals(initialId)) return;
                    }
                    if (messageId.equals(initialId)) return;
                    ChatMessage message = snapshot.getValue(ChatMessage.class);
                    if (message == null || uid.equals(message.senderUid)) return;
                    notifyIncoming(chatId, messageId, message);
                }
                @Override public void onChildChanged(DataSnapshot snapshot, String previousChildName) { }
                @Override public void onChildRemoved(DataSnapshot snapshot) { }
                @Override public void onChildMoved(DataSnapshot snapshot, String previousChildName) { }
                @Override public void onCancelled(DatabaseError error) { }
            };
            queries.put(chatId, query);
            listeners.put(chatId, listener);
            query.addChildEventListener(listener);
            if (initialId != null) initialized.add(chatId);
        });
    }

    private void notifyIncoming(String chatId, String messageId, ChatMessage message) {
        String active = getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getString("active_chat", "");
        if (chatId.equals(active)) return;
        if (!NotificationSounds.claimNotification(this, chatId, messageId)) return;
        worker.execute(() -> renderIncoming(chatId, messageId, message, 0));
    }

    private void renderIncoming(String chatId, String messageId, ChatMessage message, int attempt) {
        String peerUid = message.senderUid;
        String title = "Tsuyu";
        Bitmap avatar = null;
        String body = NotificationCenter.placeholder(this);
        long when = message.sentAt;
        try {
            com.google.firebase.database.DatabaseReference profileRef = database.getReference("users").child(peerUid).child("profile");
            Profile profile = new Profile();
            profile.username = Tasks.await(profileRef.child("username").get(), 8, TimeUnit.SECONDS).getValue(String.class);
            profile.displayName = readOptionalProfileField(profileRef, "displayName");
            profile.avatarBase64 = readOptionalProfileField(profileRef, "avatarBase64");
            if (profile.username != null || profile.displayName != null) title = profile.displayNameOrUsername();
            if (profile.avatarBase64 != null && !profile.avatarBase64.isEmpty()) {
                byte[] bytes = Base64.decode(profile.avatarBase64, Base64.NO_WRAP);
                avatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
            SignalE2ee crypto = new SignalE2ee(this, uid);
            byte[] plain = crypto.decrypt(peerUid, message.signalType, message.cipher);
            String payload = new String(plain, StandardCharsets.UTF_8);
            new LocalMessageCache(this, uid).put(chatId, messageId, payload);
            body = NotificationCenter.describePayload(this, payload);
        } catch (Exception e) {
            // Never show ciphertext: retry shortly, the session may still be syncing.
            if (attempt < 2) {
                final int nextAttempt = attempt + 1;
                worker.execute(() -> {
                    try { Thread.sleep(4000L * nextAttempt); } catch (InterruptedException ignored) { return; }
                    renderIncoming(chatId, messageId, message, nextAttempt);
                });
                return;
            }
        }
        if (when <= 0) when = System.currentTimeMillis();
        boolean decrypted = !body.equals(NotificationCenter.placeholder(this));
        if (decrypted || attempt >= 2) {
            NotificationCenter.post(this, chatId, peerUid, title, avatar, body, when, messageId, true);
            return;
        }
        NotificationCenter.post(this, chatId, peerUid, title, avatar, NotificationCenter.decrypting(this), when, messageId, true);
    }

    private String readOptionalProfileField(com.google.firebase.database.DatabaseReference profileRef, String field) {
        try { return Tasks.await(profileRef.child(field).get(), 8, TimeUnit.SECONDS).getValue(String.class); }
        catch (Exception deniedOrUnavailable) { return null; }
    }

    private Notification serviceNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 4401, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new NotificationCompat.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("Tsuyu")
                .setContentText("Уведомления о новых сообщениях включены")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pending)
                .build();
    }

    private void ensureServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(SERVICE_CHANNEL, "Новые сообщения", NotificationManager.IMPORTANCE_MIN);
                channel.setDescription("Помогает получать новые сообщения, пока приложение закрыто");
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void detachChat(String chatId) {
        Query query = queries.remove(chatId);
        ChildEventListener listener = listeners.remove(chatId);
        if (query != null && listener != null) query.removeEventListener(listener);
        initialized.remove(chatId);
    }

    @Override public void onDestroy() {
        if (chatsIndex != null && chatsListener != null) chatsIndex.removeEventListener(chatsListener);
        for (String chatId : new HashSet<>(listeners.keySet())) detachChat(chatId);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
