package com.tsuyu.messenger.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.R;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatMessage;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class TsuyuMessagingService extends FirebaseMessagingService {
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
        if (!NotificationSounds.isEnabled(this)) return;
        FirebaseApp app;
        try {
            app = FirebaseApp.getInstance();
        } catch (IllegalStateException e) {
            return;
        }
        if (FirebaseAuth.getInstance(app).getCurrentUser() == null) return;
        Map<String, String> data = remoteMessage.getData();
        String chatId = data.get("chatId");
        String peerUid = data.get("senderUid");
        String messageId = data.get("messageId");
        if (chatId != null && chatId.equals(getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getString("active_chat", ""))) return;
        if (chatId != null && messageId != null && !NotificationSounds.claimNotification(this, chatId, messageId)) return;
        String title = "Tsuyu";
        String body = "Новое зашифрованное сообщение";
        Bitmap avatar = null;
        if (chatId != null && peerUid != null && messageId != null) {
            try {
                FirebaseDatabase database = FirebaseDatabase.getInstance(app);
                com.google.firebase.database.DatabaseReference profileRef = database.getReference("users").child(peerUid).child("profile");
                Profile profile = new Profile();
                profile.username = Tasks.await(profileRef.child("username").get(), 6, TimeUnit.SECONDS).getValue(String.class);
                profile.displayName = readOptionalProfileField(profileRef, "displayName");
                profile.avatarBase64 = readOptionalProfileField(profileRef, "avatarBase64");
                if (profile.username != null) {
                    title = profile.displayNameOrUsername();
                    if (profile.avatarBase64 != null && !profile.avatarBase64.isEmpty()) {
                        byte[] avatarBytes = Base64.decode(profile.avatarBase64, Base64.NO_WRAP);
                        avatar = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
                    }
                }
                String localUid = FirebaseAuth.getInstance(app).getCurrentUser().getUid();
                DataSnapshot messageSnapshot = Tasks.await(database.getReference("chats").child(chatId)
                        .child("messages").child(messageId).get(), 6, TimeUnit.SECONDS);
                ChatMessage message = messageSnapshot.getValue(ChatMessage.class);
                if (message != null && message.cipher != null) {
                    SignalE2ee crypto = new SignalE2ee(this, localUid);
                    byte[] plain = crypto.decrypt(peerUid, message.signalType, message.cipher);
                    JSONObject payload = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                    String kind = payload.optString("kind", "text");
                    if ("text".equals(kind)) body = payload.optString("text", "Новое сообщение");
                    else if ("image".equals(kind)) body = "Фото";
                    else if ("video".equals(kind)) body = "Видео";
                    else if ("voice".equals(kind)) body = "Голосовое сообщение";
                    else if ("audio".equals(kind)) body = "Аудиофайл";
                    else body = "Новое сообщение";
                }
            } catch (Exception ignored) {
                // Never fall back to server-provided plaintext for a failed decryption.
                body = "Новое зашифрованное сообщение";
            }
        }
        showNotification(title, body, avatar, chatId, peerUid);
    }

    private String readOptionalProfileField(com.google.firebase.database.DatabaseReference profileRef, String field) {
        try { return Tasks.await(profileRef.child(field).get(), 6, TimeUnit.SECONDS).getValue(String.class); }
        catch (Exception deniedOrUnavailable) { return null; }
    }

    private void showNotification(String title, String body, Bitmap avatar, String chatId, String peerUid) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (chatId != null) open.putExtra("open_chat_id", chatId);
        if (peerUid != null) open.putExtra("open_peer_uid", peerUid);
        int id = chatId == null ? (int) System.currentTimeMillis() : chatId.hashCode();
        int commonFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, id, open, commonFlags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationSounds.channelId(this))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(false);
        if (avatar != null) builder.setLargeIcon(avatar);
        if (chatId != null && peerUid != null) {
            Intent reply = new Intent(this, NotificationReplyReceiver.class)
                    .putExtra("chatId", chatId)
                    .putExtra("peerUid", peerUid);
            int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent replyIntent = PendingIntent.getBroadcast(this, id ^ 0x52504c59, reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | mutable);
            RemoteInput remoteInput = new RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT)
                    .setLabel("Ответить…")
                    .build();
            builder.addAction(new NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send, "Ответить", replyIntent)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .build());
        }
        getSystemService(android.app.NotificationManager.class).notify(id, builder.build());
    }
}
