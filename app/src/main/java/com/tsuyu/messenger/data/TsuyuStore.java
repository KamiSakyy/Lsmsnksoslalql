package com.tsuyu.messenger.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.tsuyu.messenger.Profile;
import com.tsuyu.messenger.crypto.LocalVault;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline-first local database.
 *
 * Every read path that paints the UI can be answered from here without touching the
 * network: the chat list, the last preview of each conversation, the visible page of
 * messages, pending outgoing messages and cached peer profiles. Message payloads are
 * stored encrypted with a Keystore-backed key ({@link LocalVault}), so the database
 * file never contains readable message text.
 */
public final class TsuyuStore extends SQLiteOpenHelper {
    public static final int STATE_PENDING = 0;
    public static final int STATE_SENT = 1;
    public static final int STATE_FAILED = 2;
    public static final int STATE_READ = 3;

    private static final String DB_NAME = "tsuyu-offline.db";
    private static final int DB_VERSION = 1;

    /** One queued outgoing message that still has to reach the network. */
    public static final class OutboxItem {
        public String clientId;
        public String chatId;
        public String peerUid;
        public String payload;
        public long createdAt;
        public int attempts;
    }

    private final String account;
    private final LocalVault vault;

    public TsuyuStore(Context context, String accountUid, LocalVault vault) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.account = accountUid == null ? "" : accountUid;
        this.vault = vault;
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats ("
                + "account TEXT NOT NULL, chat_id TEXT NOT NULL, peer_uid TEXT,"
                + " peer_name TEXT, peer_username TEXT, peer_avatar TEXT, peer_about TEXT,"
                + " last_at INTEGER NOT NULL DEFAULT 0, last_text TEXT, last_kind TEXT,"
                + " last_sender TEXT, last_state INTEGER NOT NULL DEFAULT 1,"
                + " last_read INTEGER NOT NULL DEFAULT 0, last_read_id TEXT,"
                + " counted_id TEXT, counted_at INTEGER NOT NULL DEFAULT 0,"
                + " unread INTEGER NOT NULL DEFAULT 0,"
                + " pinned INTEGER NOT NULL DEFAULT 0, online INTEGER NOT NULL DEFAULT 0,"
                + " last_seen INTEGER NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (account, chat_id))");
        db.execSQL("CREATE INDEX idx_chats_order ON chats (account, pinned DESC, last_at DESC)");
        db.execSQL("CREATE TABLE messages ("
                + "account TEXT NOT NULL, chat_id TEXT NOT NULL, message_id TEXT NOT NULL,"
                + " sender_uid TEXT, cipher TEXT, signal_type INTEGER NOT NULL DEFAULT 0,"
                + " sent_at INTEGER NOT NULL DEFAULT 0, edited INTEGER NOT NULL DEFAULT 0,"
                + " client_id TEXT, payload TEXT, state INTEGER NOT NULL DEFAULT 1,"
                + " reactions INTEGER NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (account, message_id))");
        db.execSQL("CREATE INDEX idx_messages_page ON messages (account, chat_id, sent_at)");
        db.execSQL("CREATE TABLE outbox ("
                + "account TEXT NOT NULL, client_id TEXT NOT NULL, chat_id TEXT NOT NULL,"
                + " peer_uid TEXT, payload TEXT, created_at INTEGER NOT NULL DEFAULT 0,"
                + " attempts INTEGER NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (account, client_id))");
        db.execSQL("CREATE TABLE profiles ("
                + "account TEXT NOT NULL, uid TEXT NOT NULL, display_name TEXT, username TEXT,"
                + " avatar TEXT, about TEXT, updated_at INTEGER NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (account, uid))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS chats");
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("DROP TABLE IF EXISTS outbox");
        db.execSQL("DROP TABLE IF EXISTS profiles");
        onCreate(db);
    }

    // ---------------------------------------------------------------- chats

    public synchronized void upsertChat(ChatSummary summary) {
        if (summary == null || summary.chatId == null) return;
        Profile peer = summary.peer == null ? new Profile() : summary.peer;
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("chat_id", summary.chatId);
        values.put("peer_uid", summary.peerUid);
        values.put("peer_name", peer.displayName);
        values.put("peer_username", peer.username);
        values.put("peer_avatar", peer.avatarBase64);
        values.put("peer_about", peer.about);
        if (summary.lastAt > 0) values.put("last_at", summary.lastAt);
        if (summary.lastPreview != null) {
            values.put("last_text", summary.lastPreview);
            values.put("last_kind", summary.lastKind);
        }
        if (summary.lastMessage != null) {
            values.put("last_sender", summary.lastMessage.senderUid);
            values.put("last_state", stateOf(summary.lastMessage));
        }
        getWritableDatabase().insertWithOnConflict("chats", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues updates = new ContentValues(values);
        updates.remove("account");
        updates.remove("chat_id");
        // Only overwrite identity columns when the caller actually knows them.
        if (peer.displayName == null) updates.remove("peer_name");
        if (peer.username == null) updates.remove("peer_username");
        if (peer.avatarBase64 == null) updates.remove("peer_avatar");
        if (peer.about == null) updates.remove("peer_about");
        if (summary.lastPreview == null) {
            updates.remove("last_text");
            updates.remove("last_kind");
        }
        if (summary.lastAt <= 0) updates.remove("last_at");
        if (summary.lastMessage == null) {
            updates.remove("last_sender");
            updates.remove("last_state");
        }
        getWritableDatabase().update("chats", updates, "account = ? AND chat_id = ?", new String[]{account, summary.chatId});
    }

    public synchronized void updatePreview(String chatId, String preview, String kind, long lastAt, String senderUid, int state) {
        if (chatId == null) return;
        ContentValues values = new ContentValues();
        if (preview != null) {
            values.put("last_text", preview);
            values.put("last_kind", kind);
        }
        if (lastAt > 0) values.put("last_at", lastAt);
        if (senderUid != null) values.put("last_sender", senderUid);
        values.put("last_state", state);
        int updated = getWritableDatabase().update("chats", values, "account = ? AND chat_id = ?", new String[]{account, chatId});
        if (updated == 0) {
            ContentValues insert = new ContentValues(values);
            insert.put("account", account);
            insert.put("chat_id", chatId);
            getWritableDatabase().insertWithOnConflict("chats", null, insert, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    public synchronized void setPresence(String chatId, boolean online, long lastSeen) {
        ContentValues values = new ContentValues();
        values.put("online", online ? 1 : 0);
        if (lastSeen > 0) values.put("last_seen", lastSeen);
        getWritableDatabase().update("chats", values, "account = ? AND chat_id = ?", new String[]{account, chatId});
    }

    public synchronized void setUnread(String chatId, int unread) {
        ContentValues values = new ContentValues();
        values.put("unread", Math.max(0, unread));
        getWritableDatabase().update("chats", values, "account = ? AND chat_id = ?", new String[]{account, chatId});
    }

    public synchronized int incrementUnread(String chatId) {
        if (chatId == null) return 0;
        int next = unread(chatId) + 1;
        setUnread(chatId, next);
        return next;
    }

    /** Opening a conversation clears its badge and moves the read watermark forward. */
    public synchronized void markChatOpened(String chatId, String messageId, long sentAt) {
        if (chatId == null) return;
        ContentValues values = new ContentValues();
        values.put("unread", 0);
        if (sentAt > 0) values.put("last_read", sentAt);
        if (messageId != null) values.put("last_read_id", messageId);
        if (sentAt > 0) values.put("counted_at", sentAt);
        if (messageId != null) values.put("counted_id", messageId);
        if (getWritableDatabase().update("chats", values, "account = ? AND chat_id = ?",
                new String[]{account, chatId}) == 0) {
            ContentValues insert = new ContentValues(values);
            insert.put("account", account);
            insert.put("chat_id", chatId);
            getWritableDatabase().insertWithOnConflict("chats", null, insert, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    /**
     * Raises the unread counter for one incoming message. The (sent_at, message_id)
     * watermark makes this idempotent: replaying the same snapshot never double counts.
     */
    public synchronized void countIncoming(String chatId, String messageId, long sentAt) {
        if (chatId == null || sentAt <= 0) return;
        long countedAt = 0L;
        String countedId = null;
        int current = 0;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT counted_at, counted_id, unread FROM chats WHERE account = ? AND chat_id = ?",
                new String[]{account, chatId})) {
            if (!cursor.moveToFirst()) return;
            countedAt = cursor.getLong(0);
            countedId = cursor.getString(1);
            current = cursor.getInt(2);
        }
        boolean newer = sentAt > countedAt
                || (sentAt == countedAt && messageId != null && (countedId == null || messageId.compareTo(countedId) > 0));
        if (!newer) return;
        ContentValues values = new ContentValues();
        values.put("unread", current + 1);
        values.put("counted_at", sentAt);
        if (messageId != null) values.put("counted_id", messageId);
        getWritableDatabase().update("chats", values, "account = ? AND chat_id = ?", new String[]{account, chatId});
    }

    public synchronized int unread(String chatId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT unread FROM chats WHERE account = ? AND chat_id = ?", new String[]{account, chatId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized long chatLastAt(String chatId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT last_at FROM chats WHERE account = ? AND chat_id = ?", new String[]{account, chatId})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    public synchronized void setPinned(String chatId, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("chats", values, "account = ? AND chat_id = ?", new String[]{account, chatId});
    }

    public synchronized void removeChat(String chatId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("chats", "account = ? AND chat_id = ?", new String[]{account, chatId});
        db.delete("messages", "account = ? AND chat_id = ?", new String[]{account, chatId});
        db.delete("outbox", "account = ? AND chat_id = ?", new String[]{account, chatId});
    }

    public synchronized List<ChatSummary> listChats() {
        List<ChatSummary> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT chat_id, peer_uid, peer_name, peer_username, peer_avatar, peer_about,"
                        + " last_at, last_text, last_kind, last_sender, last_state, last_read,"
                        + " unread, pinned, online, last_seen FROM chats"
                        + " WHERE account = ? ORDER BY pinned DESC, last_at DESC LIMIT 200", new String[]{account})) {
            while (cursor.moveToNext()) result.add(readSummary(cursor));
        }
        return result;
    }

    public synchronized ChatSummary getChat(String chatId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT chat_id, peer_uid, peer_name, peer_username, peer_avatar, peer_about,"
                        + " last_at, last_text, last_kind, last_sender, last_state, last_read,"
                        + " unread, pinned, online, last_seen FROM chats"
                        + " WHERE account = ? AND chat_id = ?", new String[]{account, chatId})) {
            return cursor.moveToFirst() ? readSummary(cursor) : null;
        }
    }

    private ChatSummary readSummary(Cursor cursor) {
        Profile peer = new Profile();
        peer.uid = cursor.getString(1);
        peer.displayName = cursor.getString(2);
        peer.username = cursor.getString(3);
        peer.avatarBase64 = cursor.getString(4);
        peer.about = cursor.getString(5);
        ChatSummary summary = new ChatSummary(cursor.getString(0), peer.uid, peer, cursor.getLong(6), null);
        summary.lastPreview = cursor.getString(7);
        summary.lastKind = cursor.getString(8);
        summary.lastSenderUid = cursor.getString(9);
        summary.lastState = cursor.getInt(10);
        summary.peerReadAt = cursor.getLong(11);
        summary.unread = cursor.getInt(12);
        summary.pinned = cursor.getInt(13) == 1;
        summary.online = cursor.getInt(14) == 1;
        summary.lastSeen = cursor.getLong(15);
        return summary;
    }

    // ------------------------------------------------------------- messages

    public synchronized void upsertMessage(String chatId, ChatMessage message, String payload, int state) {
        if (message == null || message.id == null) return;
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("chat_id", chatId);
        values.put("message_id", message.id);
        values.put("sender_uid", message.senderUid);
        values.put("cipher", message.cipher);
        values.put("signal_type", message.signalType);
        values.put("sent_at", message.sentAt);
        values.put("edited", message.edited ? 1 : 0);
        values.put("client_id", message.clientId);
        if (payload != null) values.put("payload", seal(payload));
        values.put("state", state);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues updates = new ContentValues(values);
        updates.remove("account");
        updates.remove("message_id");
        if (payload == null) updates.remove("payload");
        if (message.cipher == null) updates.remove("cipher");
        db.update("messages", updates, "account = ? AND message_id = ?", new String[]{account, message.id});
    }

    public synchronized void updatePayload(String messageId, String payload) {
        if (payload == null || messageId == null) return;
        ContentValues values = new ContentValues();
        values.put("payload", seal(payload));
        getWritableDatabase().update("messages", values, "account = ? AND message_id = ?", new String[]{account, messageId});
    }

    public synchronized void updateState(String messageId, int state) {
        if (messageId == null) return;
        ContentValues values = new ContentValues();
        values.put("state", state);
        getWritableDatabase().update("messages", values, "account = ? AND message_id = ?", new String[]{account, messageId});
    }

    public synchronized void promoteLocalMessage(String localId, String serverId, String payloadJson) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("message_id", serverId);
        values.put("state", STATE_SENT);
        db.update("messages", values, "account = ? AND message_id = ?", new String[]{account, localId});
        if (payloadJson != null) updatePayload(serverId, payloadJson);
        db.delete("messages", "account = ? AND message_id = ?", new String[]{account, localId});
    }

    public synchronized void deleteMessage(String messageId) {
        getWritableDatabase().delete("messages", "account = ? AND message_id = ?", new String[]{account, messageId});
    }

    public synchronized void clearMessages(String chatId) {
        getWritableDatabase().delete("messages", "account = ? AND chat_id = ?", new String[]{account, chatId});
        ContentValues values = new ContentValues();
        values.put("last_text", (String) null);
        values.put("last_kind", (String) null);
        values.put("unread", 0);
        getWritableDatabase().update("chats", values, "account = ? AND chat_id = ?", new String[]{account, chatId});
    }

    public synchronized String payload(String messageId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT payload FROM messages WHERE account = ? AND message_id = ?",
                new String[]{account, messageId})) {
            if (!cursor.moveToFirst()) return null;
            return open(cursor.getString(0));
        }
    }

    /** Last {@code limit} messages of a chat in chronological order, payloads included. */
    public synchronized List<ChatMessage> messages(String chatId, int limit) {
        List<ChatMessage> page = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT message_id, sender_uid, cipher, signal_type, sent_at, edited, client_id, payload, state, reactions"
                        + " FROM messages WHERE account = ? AND chat_id = ? ORDER BY sent_at DESC, message_id DESC LIMIT ?",
                new String[]{account, chatId, Integer.toString(Math.max(1, limit))})) {
            while (cursor.moveToNext()) page.add(readMessage(chatId, cursor));
        }
        java.util.Collections.reverse(page);
        return page;
    }

    public synchronized List<ChatMessage> pendingMessages(String chatId) {
        List<ChatMessage> page = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT message_id, sender_uid, cipher, signal_type, sent_at, edited, client_id, payload, state, reactions"
                        + " FROM messages WHERE account = ? AND chat_id = ? AND state IN (0, 2)"
                        + " ORDER BY sent_at ASC LIMIT 100",
                new String[]{account, chatId})) {
            while (cursor.moveToNext()) page.add(readMessage(chatId, cursor));
        }
        return page;
    }

    private ChatMessage readMessage(String chatId, Cursor cursor) {
        ChatMessage message = new ChatMessage();
        message.id = cursor.getString(0);
        message.senderUid = cursor.getString(1);
        message.cipher = cursor.getString(2);
        message.signalType = cursor.getInt(3);
        message.sentAt = cursor.getLong(4);
        message.edited = cursor.getInt(5) == 1;
        message.clientId = cursor.getString(6);
        String payload = open(cursor.getString(7));
        int state = cursor.getInt(8);
        message.localChatId = chatId;
        message.localPayload = payload;
        message.pending = state == STATE_PENDING;
        message.failed = state == STATE_FAILED;
        message.read = state == STATE_READ;
        return message;
    }

    public synchronized void setReactionCount(String messageId, int count) {
        ContentValues values = new ContentValues();
        values.put("reactions", count);
        getWritableDatabase().update("messages", values, "account = ? AND message_id = ?", new String[]{account, messageId});
    }

    // --------------------------------------------------------------- outbox

    public synchronized void enqueue(String clientId, String chatId, String peerUid, String payloadJson) {
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("client_id", clientId);
        values.put("chat_id", chatId);
        values.put("peer_uid", peerUid);
        values.put("payload", payloadJson);
        values.put("created_at", System.currentTimeMillis());
        values.put("attempts", 0);
        getWritableDatabase().insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void dequeue(String clientId) {
        getWritableDatabase().delete("outbox", "account = ? AND client_id = ?", new String[]{account, clientId});
    }

    public synchronized void noteAttempt(String clientId) {
        getWritableDatabase().execSQL("UPDATE outbox SET attempts = attempts + 1 WHERE account = ? AND client_id = ?",
                new Object[]{account, clientId});
    }

    public synchronized boolean isQueued(String clientId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM outbox WHERE account = ? AND client_id = ?", new String[]{account, clientId})) {
            return cursor.moveToFirst();
        }
    }

    public synchronized List<OutboxItem> outbox() {
        List<OutboxItem> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT client_id, chat_id, peer_uid, payload, created_at, attempts FROM outbox"
                        + " WHERE account = ? ORDER BY created_at ASC LIMIT 200", new String[]{account})) {
            while (cursor.moveToNext()) {
                OutboxItem item = new OutboxItem();
                item.clientId = cursor.getString(0);
                item.chatId = cursor.getString(1);
                item.peerUid = cursor.getString(2);
                item.payload = cursor.getString(3);
                item.createdAt = cursor.getLong(4);
                item.attempts = cursor.getInt(5);
                result.add(item);
            }
        }
        return result;
    }

    // ------------------------------------------------------------- profiles

    public synchronized void putProfile(Profile profile) {
        if (profile == null || profile.uid == null) return;
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("uid", profile.uid);
        values.put("display_name", profile.displayName);
        values.put("username", profile.username);
        values.put("avatar", profile.avatarBase64);
        values.put("about", profile.about);
        values.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("profiles", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues updates = new ContentValues(values);
        updates.remove("account");
        updates.remove("uid");
        db.update("profiles", updates, "account = ? AND uid = ?", new String[]{account, profile.uid});
    }

    public synchronized Profile getProfile(String uid) {
        if (uid == null) return null;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT display_name, username, avatar, about FROM profiles WHERE account = ? AND uid = ?",
                new String[]{account, uid})) {
            if (!cursor.moveToFirst()) return null;
            Profile profile = new Profile();
            profile.uid = uid;
            profile.displayName = cursor.getString(0);
            profile.username = cursor.getString(1);
            profile.avatarBase64 = cursor.getString(2);
            profile.about = cursor.getString(3);
            return profile;
        }
    }

    public synchronized int messageCount(String chatId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM messages WHERE account = ? AND chat_id = ?", new String[]{account, chatId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int stateOf(ChatMessage message) {
        if (message == null) return STATE_SENT;
        if (message.failed) return STATE_FAILED;
        if (message.pending) return STATE_PENDING;
        if (message.read) return STATE_READ;
        return STATE_SENT;
    }

    private String seal(String payload) {
        if (vault == null) return null;
        try { return vault.encrypt(payload); }
        catch (Exception ignored) { return null; }
    }

    private String open(String sealed) {
        if (vault == null || sealed == null) return null;
        try { return vault.decrypt(sealed); }
        catch (Exception ignored) { return null; }
    }
}
