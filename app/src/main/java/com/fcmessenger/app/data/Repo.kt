package com.fcmessenger.app.data

import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fcmessenger.app.crypto.CryptoManager
import com.fcmessenger.app.data.local.AppDatabase
import com.fcmessenger.app.data.local.ChatEntity
import com.fcmessenger.app.data.local.MessageEntity
import com.fcmessenger.app.data.local.PeerEntity
import com.fcmessenger.app.data.local.Status
import com.fcmessenger.app.data.remote.Directory
import com.fcmessenger.app.data.remote.Relay
import com.fcmessenger.app.fcm.Notifications
import com.fcmessenger.app.work.RetryWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.ktx.messaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Мозг мессенджера. Вся переписка — через FCM data-сообщения:
 * msg (текст) / ack (доставлено) / read (прочитано) / typing (печатает).
 */
class Repo(
    private val app: Application,
    private val prefs: Prefs,
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val dir: Directory,
    private val relay: Relay,
    private val notifications: Notifications
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val auth: FirebaseAuth = Firebase.auth

    val profile = prefs.profile
    val zeroMode = prefs.zeroMode

    private val _typing = MutableStateFlow<Set<String>>(emptySet())
    val typing: StateFlow<Set<String>> = _typing.asStateFlow()
    private val typingJobs = mutableMapOf<String, Job>()
    private val lastTypingSent = mutableMapOf<String, Long>()

    private val _openChat = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openChat: SharedFlow<String> = _openChat.asSharedFlow()
    fun requestOpen(peer: String) {
        _openChat.tryEmit(peer)
    }

    fun chats() = db.chatDao().all()
    fun messages(peer: String) = db.messageDao().byPeer(peer)

    suspend fun myUid(): String {
        prefs.cachedUid()?.let { return it }
        val u = auth.currentUser ?: auth.signInAnonymously().await().user!!
        prefs.setUid(u.uid)
        return u.uid
    }

    suspend fun setZeroMode(b: Boolean) = prefs.setZeroMode(b)

    // ---------- регистрация ----------

    suspend fun register(name: String, username: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val clean = username.trim().lowercase().filter { it.isLetterOrDigit() || it == '_' }
                require(clean.length in 3..24) { "Ник: 3–24 символа (латиница, цифры, _)" }
                require(name.trim().length in 1..40) { "Введи имя" }
                val uid = myUid()
                if (!dir.claimUsername(clean, uid)) {
                    return@withContext Result.failure(IllegalStateException("Ник уже занят"))
                }
                val token = try {
                    Firebase.messaging.token.await()
                } catch (e: Exception) {
                    null
                }
                dir.publishMe(uid, name.trim(), clean, token, crypto.myPublicKeyBase64())
                prefs.setProfile(uid, name.trim(), clean)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun onTokenRefresh(token: String) {
        val uid = prefs.cachedUid() ?: return
        runCatching { dir.updateToken(uid, token) }
    }

    // ---------- контакты ----------

    suspend fun addContact(username: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val clean = username.trim().lowercase().removePrefix("@")
                val uid = dir.resolveUsername(clean)
                    ?: return@withContext Result.failure(IllegalStateException("Не найден"))
                require(uid != myUid()) { "Это же ты :)" }
                val peer = peerFor(uid)
                ensureChat(uid, peer.name)
                Result.success(uid)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---------- отправка ----------

    suspend fun sendText(peerUid: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val uid = myUid()
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        db.messageDao().insert(MessageEntity(id, peerUid, true, body, ts, Status.SENDING))
        db.chatDao().touch(peerUid, body, ts)
        scope.launch {
            try {
                val peer = peerFor(peerUid)
                val ct = crypto.encryptFor(peer.pub, body, uid)
                relay.send(peerUid, "msg", id, ts, ct)
                db.messageDao().setStatus(id, Status.SENT)
            } catch (e: Exception) {
                db.messageDao().setStatus(id, Status.QUEUED)
                scheduleRetry()
            }
        }
    }

    /** Доотправка очереди (вызывается воркером при появлении сети). */
    suspend fun retryQueued() {
        val uid = myUid()
        for (m in db.messageDao().queued()) {
            try {
                db.messageDao().setStatus(m.id, Status.SENDING)
                val peer = peerFor(m.peerUid)
                val ct = crypto.encryptFor(peer.pub, m.text, uid)
                relay.send(m.peerUid, "msg", m.id, m.ts, ct)
                db.messageDao().setStatus(m.id, Status.SENT)
            } catch (e: Exception) {
                db.messageDao().setStatus(m.id, Status.QUEUED)
            }
        }
    }

    fun scheduleRetry() {
        val req = OneTimeWorkRequestBuilder<RetryWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app)
            .enqueueUniqueWork("retry-send", ExistingWorkPolicy.REPLACE, req)
    }

    // ---------- приём пушей (FcmService) ----------

    suspend fun onPushReceived(data: Map<String, String>) {
        val kind = data["kind"] ?: return
        val from = data["from"] ?: return
        when (kind) {
            "msg" -> {
                val id = data["id"] ?: return
                // дедуп: FCM иногда дублирует
                if (db.messageDao().exists(id)) {
                    sendAck(from, id)
                    return
                }
                val text = try {
                    crypto.decrypt(data["ct"] ?: return, from)
                } catch (e: Exception) {
                    return // чужой/битый шифротекст — игнор
                }
                val ts = data["ts"]?.toLongOrNull() ?: System.currentTimeMillis()
                db.messageDao().insert(MessageEntity(id, from, false, text, ts, Status.RECEIVED))
                ensureChat(from)
                db.chatDao().touch(from, text, ts)
                db.chatDao().incUnread(from)
                notifications.showMessage(from, peerName(from), text)
                sendAck(from, id)
            }
            "ack" -> {
                val id = data["id"] ?: return
                db.messageDao().setStatus(id, Status.DELIVERED)
            }
            "read" -> {
                db.messageDao().markOutgoingRead(from)
            }
            "typing" -> flashTyping(from)
        }
    }

    private fun sendAck(to: String, id: String) {
        scope.launch {
            runCatching { relay.send(to, "ack", id, System.currentTimeMillis()) }
        }
    }

    fun sendTyping(peer: String) {
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[peer] ?: 0) < 3000) return
        lastTypingSent[peer] = now
        scope.launch {
            runCatching { relay.send(peer, "typing", UUID.randomUUID().toString(), now) }
        }
    }

    private fun flashTyping(from: String) {
        typingJobs[from]?.cancel()
        _typing.update { it + from }
        typingJobs[from] = scope.launch {
            delay(5000)
            _typing.update { it - from }
        }
    }

    fun markRead(peer: String) {
        scope.launch {
            db.chatDao().clearUnread(peer)
            notifications.cancel(peer)
            runCatching { relay.send(peer, "read", UUID.randomUUID().toString(), System.currentTimeMillis()) }
        }
    }

    // ---------- внутреннее ----------

    /** Собеседник: сначала кеш, потом сеть (в 0-режиме — только кеш). */
    private suspend fun peerFor(uid: String): PeerEntity {
        db.peerDao().get(uid)?.let { return it }
        if (prefs.zeroMode.first()) throw IllegalStateException("0-режим: нет в кеше")
        val ru = dir.getUser(uid) ?: throw IllegalStateException("Пользователь не найден")
        val p = PeerEntity(uid, ru.name, ru.pub)
        db.peerDao().upsert(p)
        return p
    }

    private suspend fun ensureChat(peerUid: String, nameHint: String? = null) {
        if (db.chatDao().get(peerUid) == null) {
            val nm = nameHint
                ?: db.peerDao().get(peerUid)?.name
                ?: runCatching { dir.getUser(peerUid)?.name }.getOrNull()
                ?: peerUid.take(8)
            db.chatDao().insert(ChatEntity(peerUid, nm))
        }
    }

    private suspend fun peerName(uid: String): String =
        db.chatDao().get(uid)?.peerName
            ?: db.peerDao().get(uid)?.name
            ?: uid.take(8)
}
