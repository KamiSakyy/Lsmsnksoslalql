package com.noir.p2pchat.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Local-only conversation and message index. Message content never passes through Firebase. */
public final class MessageStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "noir_chat.db";
    private static final int DB_VERSION = 3;
    private final MessageContentCipher contentCipher = new MessageContentCipher();

    public MessageStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts (uid TEXT PRIMARY KEY, added_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, peer_uid TEXT NOT NULL, sender_uid TEXT NOT NULL, " +
                "kind TEXT NOT NULL, body TEXT NOT NULL, mime TEXT, file_path TEXT, created_at INTEGER NOT NULL, " +
                "outgoing INTEGER NOT NULL, status TEXT NOT NULL, transfer_size INTEGER NOT NULL DEFAULT 0, " +
                "transfer_offset INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX messages_peer_created ON messages(peer_uid, created_at)");
        db.execSQL("CREATE INDEX messages_pending ON messages(peer_uid, outgoing, status, created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) encryptExistingMessageContent(db);
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN transfer_offset INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void encryptExistingMessageContent(SQLiteDatabase db) {
        ArrayList<String> messageIds = new ArrayList<>();
        ArrayList<String> encryptedBodies = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT id,body FROM messages", null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                messageIds.add(id);
                encryptedBodies.add(contentCipher.encrypt(id, "body", cursor.getString(1)));
            }
        }
        for (int i = 0; i < messageIds.size(); i++) {
            ContentValues values = new ContentValues();
            values.put("body", encryptedBodies.get(i));
            db.update("messages", values, "id=?", new String[]{messageIds.get(i)});
        }
    }

    public synchronized void addContact(String uid) {
        ContentValues values = new ContentValues();
        values.put("uid", uid);
        values.put("added_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("contacts", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized boolean isContact(String uid) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM contacts WHERE uid=? LIMIT 1", new String[]{uid})) {
            return cursor.moveToFirst();
        }
    }

    public synchronized List<String> getContacts() {
        ArrayList<String> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT uid FROM contacts ORDER BY added_at DESC", null)) {
            while (cursor.moveToNext()) items.add(cursor.getString(0));
        }
        return items;
    }

    public synchronized Message insertMessage(Message message) {
        ContentValues values = valuesFor(message);
        getWritableDatabase().insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return getMessage(message.id);
    }

    public synchronized void updateStatus(String id, String status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        getWritableDatabase().update("messages", values, "id=?", new String[]{id});
    }

    public synchronized void markSent(String id) {
        ContentValues values = new ContentValues();
        values.put("status", "sent");
        getWritableDatabase().update("messages", values, "id=? AND status='pending'", new String[]{id});
    }

    public synchronized void updateFilePath(String id, File file, String status) {
        ContentValues values = new ContentValues();
        values.put("file_path", file.getAbsolutePath());
        values.put("status", status);
        getWritableDatabase().update("messages", values, "id=?", new String[]{id});
    }

    public synchronized void updateTransferOffset(String id, long offset) {
        ContentValues values = new ContentValues();
        values.put("transfer_offset", Math.max(0L, offset));
        getWritableDatabase().update("messages", values, "id=?", new String[]{id});
    }

    public synchronized List<Message> getMessages(String peerUid, int limit) {
        ArrayList<Message> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,peer_uid,sender_uid,kind,body,mime,file_path,created_at,outgoing,status,transfer_size,transfer_offset " +
                        "FROM messages WHERE peer_uid=? ORDER BY created_at DESC LIMIT ?",
                new String[]{peerUid, Integer.toString(limit)})) {
            while (cursor.moveToNext()) items.add(readMessage(cursor, false));
        }
        java.util.Collections.reverse(items);
        return items;
    }

    public synchronized List<Message> getPending(String peerUid, int limit) {
        ArrayList<Message> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,peer_uid,sender_uid,kind,body,mime,file_path,created_at,outgoing,status,transfer_size,transfer_offset " +
                        "FROM messages WHERE peer_uid=? AND outgoing=1 AND status='pending' " +
                        "ORDER BY created_at ASC LIMIT ?",
                new String[]{peerUid, Integer.toString(limit)})) {
            while (cursor.moveToNext()) items.add(readMessage(cursor, true));
        }
        return items;
    }

    public synchronized Message getMessage(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,peer_uid,sender_uid,kind,body,mime,file_path,created_at,outgoing,status,transfer_size,transfer_offset " +
                        "FROM messages WHERE id=? LIMIT 1", new String[]{id})) {
            return cursor.moveToFirst() ? readMessage(cursor, false) : null;
        }
    }

    private ContentValues valuesFor(Message message) {
        ContentValues values = new ContentValues();
        values.put("id", message.id);
        values.put("peer_uid", message.peerUid);
        values.put("sender_uid", message.senderUid);
        values.put("kind", message.kind);
        values.put("body", contentCipher.encrypt(message.id, "body", message.body == null ? "" : message.body));
        values.put("mime", message.mime);
        values.put("file_path", message.filePath);
        values.put("created_at", message.createdAt);
        values.put("outgoing", message.outgoing ? 1 : 0);
        values.put("status", message.status);
        values.put("transfer_size", message.transferSize);
        values.put("transfer_offset", message.transferOffset);
        return values;
    }

    private Message readMessage(Cursor c, boolean failOnContentError) {
        String id = c.getString(0);
        String body = decryptLocalField(id, "body", c.getString(4), failOnContentError);
        String mime = c.isNull(5) ? null : c.getString(5);
        return new Message(id, c.getString(1), c.getString(2), c.getString(3), body,
                mime, c.isNull(6) ? null : c.getString(6), c.getLong(7),
                c.getInt(8) != 0, c.getString(9), c.getLong(10), c.getLong(11));
    }

    private String decryptLocalField(String messageId, String field, String value, boolean failOnError) {
        try {
            return contentCipher.decrypt(messageId, field, value);
        } catch (IllegalStateException e) {
            android.util.Log.e("NoirMessageStore", "Local message content is unavailable", e);
            if (failOnError) throw e;
            return "[локальное содержимое недоступно]";
        }
    }

    public static final class Message {
        public final String id;
        public final String peerUid;
        public final String senderUid;
        public final String kind;
        public final String body;
        public final String mime;
        public final String filePath;
        public final long createdAt;
        public final boolean outgoing;
        public final String status;
        public final long transferSize;
        public final long transferOffset;

        public Message(String id, String peerUid, String senderUid, String kind, String body, String mime,
                       String filePath, long createdAt, boolean outgoing, String status, long transferSize) {
            this(id, peerUid, senderUid, kind, body, mime, filePath, createdAt, outgoing, status, transferSize, 0L);
        }

        public Message(String id, String peerUid, String senderUid, String kind, String body, String mime,
                       String filePath, long createdAt, boolean outgoing, String status, long transferSize,
                       long transferOffset) {
            this.id = id;
            this.peerUid = peerUid;
            this.senderUid = senderUid;
            this.kind = kind;
            this.body = body == null ? "" : body;
            this.mime = mime;
            this.filePath = filePath;
            this.createdAt = createdAt;
            this.outgoing = outgoing;
            this.status = status;
            this.transferSize = transferSize;
            this.transferOffset = Math.max(0L, transferOffset);
        }
    }
}
