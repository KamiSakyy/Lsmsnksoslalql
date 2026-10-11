package com.tsuyu.messenger.notifications;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.call.CallActivity;
import com.tsuyu.messenger.call.CallEngine;
import com.tsuyu.messenger.call.CallNotifier;
import com.tsuyu.messenger.call.CallSignal;
import com.tsuyu.messenger.crypto.LocalMessageCache;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Turns a push into a decrypted, person-shaped notification: avatar, name, time, text. */
public final class TsuyuMessagingService extends FirebaseMessagingService {
    private static final int MAX_ATTEMPTS = 3;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationSounds.ensureChannel(this);
    }

    @Override
    public void onNewToken(String token) {
        try {
            FirebaseMessagingSetup.saveToken(FirebaseApp.getInstance(), token);
        } catch (IllegalStateException ignored) {
            // Token will be uploaded after the next successful sign-in.
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Map<String, String> incoming = remoteMessage.getData();
        String type = incoming == null ? null : incoming.get("type");
        // Звонки важнее тишины: даже при выключенных звуках уведомлений вызов должен дойти.
        if (CallSignal.TYPE_CALL.equals(type)) {
            handleIncomingCall(incoming);
            return;
        }
        if (CallSignal.TYPE_END.equals(type)) {
            if (CallEngine.busy()) CallEngine.get().finish();
            CallNotifier.clear(this);
            return;
        }
        if (!NotificationSounds.isEnabled(this)) return;
        FirebaseApp app;
        try {
            app = FirebaseApp.getInstance();
        } catch (IllegalStateException e) {
            return;
        }
        FirebaseAuth auth = FirebaseAuth.getInstance(app);
        String uid = auth.getCurrentUser() == null ? null : auth.getCurrentUser().getUid();
        if (uid == null) return;
        Map<String, String> data = remoteMessage.getData();
        String chatId = data.get("chatId");
        String peerUid = data.get("senderUid");
        String messageId = data.get("messageId");
        if (chatId != null && chatId.equals(getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getString("active_chat", ""))) return;
        if (chatId == null || peerUid == null || messageId == null) {
            NotificationCenter.post(this, chatId == null ? "tsuyu" : chatId, peerUid,
                    getString(R.string.app_name), null, getString(R.string.notification_new),
                    remoteMessage.getSentTime(), null, true);
            return;
        }
        if (!NotificationSounds.claimNotification(this, chatId, messageId)) return;
        final FirebaseDatabase database = FirebaseDatabase.getInstance(app);
        final String accountUid = uid;
        long sent = remoteMessage.getSentTime() > 0 ? remoteMessage.getSentTime() : System.currentTimeMillis();
        // Something appears instantly; the decrypted wording replaces it a moment later.
        NotificationCenter.post(this, chatId, peerUid,
                data.get("senderName") == null ? getString(R.string.app_name) : data.get("senderName"),
                null, NotificationCenter.decrypting(this), sent, messageId, true);
        com.tsuyu.messenger.util.Background.io()
                .execute(() -> deliver(database, accountUid, chatId, peerUid, messageId, 0));
    }

    /**
     * Входящий вызов: сначала уведомление во весь экран (оно сработает и на заблокированном
     * устройстве), а если приложение уже открыто — сразу экран звонка.
     */
    private void handleIncomingCall(Map<String, String> data) {
        String chatId = data.get("chatId");
        String callId = data.get("callId");
        String peerUid = data.get("senderUid");
        if (chatId == null || callId == null || peerUid == null) return;
        if (CallEngine.busy()) {
            // Уже говорим с кем-то другим — не перебиваем разговор.
            return;
        }
        boolean video = "1".equals(data.get("video"));
        String name = data.get("senderName");
        CallNotifier.incoming(this, chatId, callId, peerUid, name, video);
        if (MainActivity.visible) {
            try {
                startActivity(CallActivity.incomingIntent(this, chatId, callId, peerUid, name, video));
            } catch (Exception ignored) { }
        }
    }

    /**
     * Resolves the sender, decrypts the payload and posts the notification. A failed
     * decryption retries with a delay because the session material may still be syncing;
     * until then the notification shows a lock placeholder, never ciphertext.
     */
    private void deliver(FirebaseDatabase database, String uid, String chatId, String peerUid,
                         String messageId, int attempt) {
        String title = getString(R.string.app_name);
        Bitmap avatar = null;
        String text = NotificationCenter.placeholder(this);
        long when = 0L;
        try {
            DatabaseReference profileRef = database.getReference("users").child(peerUid).child("profile");
            Profile profile = new Profile();
            profile.username = readField(profileRef, "username");
            profile.displayName = readField(profileRef, "displayName");
            profile.avatarBase64 = readField(profileRef, "avatarBase64");
            if (profile.username != null || profile.displayName != null) title = profile.displayNameOrUsername();
            avatar = decodeAvatar(profile.avatarBase64);
            ChatMessage message = Tasks.await(database.getReference("chats").child(chatId)
                    .child("messages").child(messageId).get(), 8, TimeUnit.SECONDS).getValue(ChatMessage.class);
            if (message != null) {
                when = message.sentAt;
                String payload = decryptPayload(uid, peerUid, message);
                if (payload != null) {
                    text = NotificationCenter.describePayload(this, payload);
                    new LocalMessageCache(this, uid).put(chatId, messageId, payload);
                }
            }
        } catch (Exception ignored) {
            // Offline or a still missing session: keep the placeholder and retry below.
        }
        if (when <= 0) when = System.currentTimeMillis();
        boolean decrypted = !text.equals(NotificationCenter.placeholder(this));
        if (!decrypted && attempt + 1 < MAX_ATTEMPTS) {
            // The session material may still be syncing: retry, then update the same notification.
            final int nextAttempt = attempt + 1;
            com.tsuyu.messenger.util.Background.postDelayed(() ->
                            com.tsuyu.messenger.util.Background.io().execute(
                                    () -> deliver(database, uid, chatId, peerUid, messageId, nextAttempt)),
                    4000L * nextAttempt);
            return;
        }
        NotificationCenter.post(this, chatId, peerUid, title, avatar, text, when, messageId, true);
    }

    /** Empty answer means "not decryptable yet"; the caller retries and keeps the placeholder. */
    private String decryptPayload(String uid, String peerUid, ChatMessage message) {
        if (message == null || message.cipher == null) return null;
        try {
            SignalE2ee crypto = new SignalE2ee(this, uid);
            byte[] plain = crypto.decrypt(peerUid, message.signalType, message.cipher);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception notReady) {
            return null;
        }
    }

    private Bitmap decodeAvatar(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readField(DatabaseReference profileRef, String field) {
        try { return Tasks.await(profileRef.child(field).get(), 8, TimeUnit.SECONDS).getValue(String.class); }
        catch (Exception deniedOrUnavailable) { return null; }
    }
}
