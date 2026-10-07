package com.tsuyu.messenger.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.tsuyu.messenger.crypto.SignalE2ee;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-to-one RTDB repository. Plaintext bodies are never written to RTDB. */
public final class ChatRepository {
    public interface Result<T> {
        void success(T value);
        void error(String message);
    }

    private final FirebaseDatabase database;
    private final DatabaseReference root;
    private final ExecutorService cryptoExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ChatRepository(FirebaseDatabase database) {
        this.database = database;
        this.root = database.getReference();
    }

    public static String chatId(String uidA, String uidB) {
        try {
            TreeSet<String> ids = new TreeSet<>();
            ids.add(uidA);
            ids.add(uidB);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((ids.first() + ":" + ids.last()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void openOrCreate(String myUid, String peerUid, Result<String> result) {
        if (myUid == null || peerUid == null || myUid.equals(peerUid)) {
            result.error("Нельзя открыть чат с этим аккаунтом.");
            return;
        }
        String id = chatId(myUid, peerUid);
        DatabaseReference myIndex = root.child("userChats").child(myUid).child(id);
        myIndex.get().addOnSuccessListener(snapshot -> {
            if (snapshot.exists()) {
                result.success(id);
                return;
            }
            Map<String, Object> members = new HashMap<>();
            members.put(myUid, true);
            members.put(peerUid, true);
            root.child("chats").child(id).child("members").setValue(members)
                    .addOnSuccessListener(unused -> writeNewChatIndex(id, myUid, peerUid, result))
                    .addOnFailureListener(error -> {
                        // Another device may have created this deterministic chat concurrently.
                        root.child("chats").child(id).child("members").get()
                                .addOnSuccessListener(existing -> {
                                    if (existing.child(myUid).getValue(Boolean.class) != null
                                            && Boolean.TRUE.equals(existing.child(myUid).getValue(Boolean.class))) {
                                        writeNewChatIndex(id, myUid, peerUid, result);
                                    } else {
                                        result.error(safeMessage(error));
                                    }
                                }).addOnFailureListener(readError -> result.error(safeMessage(readError)));
                    });
        }).addOnFailureListener(error -> result.error(safeMessage(error)));
    }

    private void writeNewChatIndex(String id, String myUid, String peerUid, Result<String> result) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("createdAt", ServerValue.TIMESTAMP);
        meta.put("lastAt", 0L);
        root.child("chats").child(id).child("meta").updateChildren(meta)
                .addOnSuccessListener(unused -> {
                    Map<String, Object> index = new HashMap<>();
                    index.put("userChats/" + myUid + "/" + id, true);
                    index.put("userChats/" + peerUid + "/" + id, true);
                    root.updateChildren(index).addOnSuccessListener(done -> result.success(id))
                            .addOnFailureListener(e -> result.error(safeMessage(e)));
                }).addOnFailureListener(e -> result.error(safeMessage(e)));
    }

    private void ensurePeerMapping(String chatId, String senderUid, String recipientUid) throws Exception {
        DatabaseReference mapping = root.child("chats").child(chatId).child("meta").child("peerByUid").child(senderUid);
        DataSnapshot snapshot = Tasks.await(mapping.get());
        String existing = snapshot.getValue(String.class);
        if (existing == null) Tasks.await(mapping.setValue(recipientUid));
        else if (!recipientUid.equals(existing)) throw new SecurityException("The chat peer mapping does not match this conversation.");
    }

    public void sendText(String chatId, String senderUid, String recipientUid,
                         SignalE2ee crypto, String text, String replyTo, Result<String> result) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("version", 1);
            payload.put("kind", "text");
            payload.put("text", text);
            if (replyTo != null) payload.put("replyTo", replyTo);
            sendPayload(chatId, senderUid, recipientUid, crypto,
                    payload.toString().getBytes(StandardCharsets.UTF_8), result);
        } catch (Exception e) {
            result.error("Не удалось подготовить сообщение.");
        }
    }

    public void sendPayload(String chatId, String senderUid, String recipientUid,
                            SignalE2ee crypto, byte[] payload, Result<String> result) {
        cryptoExecutor.execute(() -> {
            try {
                ensurePeerMapping(chatId, senderUid, recipientUid);
                DataSnapshot keySnapshot = Tasks.await(root.child("users").child(recipientUid)
                        .child("keys").child("1").get());
                @SuppressWarnings("unchecked")
                Map<String, Object> publicBundle = (Map<String, Object>) keySnapshot.getValue();
                if (publicBundle == null) throw new IllegalStateException("У собеседника ещё нет публичного ключа.");
                SignalE2ee.Encrypted encrypted = crypto.encrypt(recipientUid, publicBundle, payload);
                String messageId = root.child("chats").child(chatId).child("messages").push().getKey();
                if (messageId == null) throw new IllegalStateException("Не удалось создать id сообщения.");
                Map<String, Object> envelope = new HashMap<>();
                envelope.put("senderUid", senderUid);
                envelope.put("cipher", encrypted.cipher);
                envelope.put("signalType", encrypted.signalType);
                envelope.put("sentAt", ServerValue.TIMESTAMP);
                envelope.put("edited", false);
                Map<String, Object> changes = new HashMap<>();
                changes.put("chats/" + chatId + "/messages/" + messageId, envelope);
                changes.put("chats/" + chatId + "/meta/lastMessage", envelope);
                changes.put("chats/" + chatId + "/meta/lastMessageId", messageId);
                changes.put("chats/" + chatId + "/meta/lastAt", ServerValue.TIMESTAMP);
                Tasks.await(root.updateChildren(changes));
                mainHandler.post(() -> result.success(messageId));
            } catch (Exception e) {
                mainHandler.post(() -> result.error(safeMessage(e)));
            }
        });
    }

    public void editText(String chatId, String messageId, String senderUid, String peerUid,
                         SignalE2ee crypto, String text, Result<Void> result) {
        cryptoExecutor.execute(() -> {
            try {
                ensurePeerMapping(chatId, senderUid, peerUid);
                JSONObject payload = new JSONObject();
                payload.put("version", 1);
                payload.put("kind", "text");
                payload.put("text", text);
                DataSnapshot keySnapshot = Tasks.await(root.child("users").child(peerUid).child("keys").child("1").get());
                @SuppressWarnings("unchecked") Map<String, Object> bundle = (Map<String, Object>) keySnapshot.getValue();
                SignalE2ee.Encrypted encrypted = crypto.encrypt(peerUid, bundle, payload.toString().getBytes(StandardCharsets.UTF_8));
                Map<String, Object> changes = new HashMap<>();
                changes.put("chats/" + chatId + "/messages/" + messageId + "/cipher", encrypted.cipher);
                changes.put("chats/" + chatId + "/messages/" + messageId + "/signalType", encrypted.signalType);
                changes.put("chats/" + chatId + "/messages/" + messageId + "/edited", true);
                Tasks.await(root.updateChildren(changes));
                mainHandler.post(() -> result.success(null));
            } catch (Exception e) {
                mainHandler.post(() -> result.error(safeMessage(e)));
            }
        });
    }

    public void deleteMessage(String chatId, String messageId, Result<Void> result) {
        root.child("chats").child(chatId).child("messages").child(messageId).removeValue()
                .addOnSuccessListener(unused -> result.success(null))
                .addOnFailureListener(e -> result.error(safeMessage(e)));
    }

    public void setReaction(String chatId, String messageId, String uid, boolean enabled) {
        root.child("chats").child(chatId).child("reactions").child(messageId).child(uid)
                .setValue(enabled ? "heart" : null);
    }

    public void setTyping(String chatId, String uid, boolean typing) {
        if (!typing) {
            root.child("chats").child(chatId).child("typing").child(uid).removeValue();
            return;
        }
        Map<String, Object> state = new HashMap<>();
        state.put("typing", true);
        state.put("at", ServerValue.TIMESTAMP);
        root.child("chats").child(chatId).child("typing").child(uid).setValue(state);
    }

    public void markRead(String chatId, String uid) {
        root.child("chats").child(chatId).child("receipts").child(uid).setValue(ServerValue.TIMESTAMP);
    }

    public DatabaseReference messages(String chatId) {
        return root.child("chats").child(chatId).child("messages");
    }

    public DatabaseReference chatMeta(String chatId) {
        return root.child("chats").child(chatId).child("meta");
    }

    public DatabaseReference typing(String chatId) {
        return root.child("chats").child(chatId).child("typing");
    }

    public DatabaseReference reactions(String chatId, String messageId) {
        return root.child("chats").child(chatId).child("reactions").child(messageId);
    }

    public DatabaseReference userChats(String uid) {
        return root.child("userChats").child(uid);
    }

    public DatabaseReference presence(String uid) {
        return root.child("users").child(uid).child("presence");
    }

    public DatabaseReference receipts(String chatId) {
        return root.child("chats").child(chatId).child("receipts");
    }

    public DatabaseReference root() {
        return root;
    }

    private static String safeMessage(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String text = cause.getLocalizedMessage();
        return text == null || text.trim().isEmpty()
                ? "Сбой RTDB/шифрования. Проверьте подключение и правила database.rules.json."
                : text;
    }
}
