package com.fcmessenger.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

object Status {
    const val QUEUED = "QUEUED"       // ждёт сети (0-режим / офлайн)
    const val SENDING = "SENDING"     // отправляется
    const val SENT = "SENT"           // relay принял (✓)
    const val DELIVERED = "DELIVERED" // дошло до устройства (✓✓)
    const val READ = "READ"           // прочитано (синие ✓✓)
    const val RECEIVED = "RECEIVED"   // входящее
    const val FAILED = "FAILED"       // ошибка, будет ретрай
}

/** Сообщение. Чат 1-1: peerUid = UID собеседника = id чата. */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val peerUid: String,
    val outgoing: Boolean,
    val text: String,
    val ts: Long,
    val status: String
)

/** Диалог в списке чатов. */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val peerUid: String,
    val peerName: String,
    val lastText: String = "",
    val lastTs: Long = 0L,
    val unread: Int = 0
)

/** Кеш собеседников: UID -> имя + публичный ключ.
 *  В 0-режиме отправка идёт ТОЛЬКО по этому кешу, без запросов в сеть. */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val uid: String,
    val name: String,
    val pub: String
)
