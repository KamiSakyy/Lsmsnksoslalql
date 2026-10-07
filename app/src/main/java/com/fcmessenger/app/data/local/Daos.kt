package com.fcmessenger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerUid = :peer ORDER BY ts ASC")
    fun byPeer(peer: String): Flow<List<MessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: MessageEntity)

    @Query("UPDATE messages SET status = :st WHERE id = :id")
    suspend fun setStatus(id: String, st: String)

    @Query("UPDATE messages SET status = 'READ' WHERE peerUid = :peer AND outgoing = 1 AND status != 'READ'")
    suspend fun markOutgoingRead(peer: String)

    @Query("SELECT * FROM messages WHERE outgoing = 1 AND status IN ('QUEUED','FAILED') ORDER BY ts ASC LIMIT 50")
    suspend fun queued(): List<MessageEntity>
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY lastTs DESC")
    fun all(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE peerUid = :peer")
    suspend fun get(peer: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: ChatEntity)

    @Query("UPDATE chats SET lastText = :t, lastTs = :ts WHERE peerUid = :peer")
    suspend fun touch(peer: String, t: String, ts: Long)

    @Query("UPDATE chats SET unread = unread + 1 WHERE peerUid = :peer")
    suspend fun incUnread(peer: String)

    @Query("UPDATE chats SET unread = 0 WHERE peerUid = :peer")
    suspend fun clearUnread(peer: String)

    @Query("UPDATE chats SET peerName = :n WHERE peerUid = :peer")
    suspend fun rename(peer: String, n: String)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers WHERE uid = :uid")
    suspend fun get(uid: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PeerEntity)
}
