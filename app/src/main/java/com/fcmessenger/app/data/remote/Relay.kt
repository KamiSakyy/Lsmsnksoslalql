package com.fcmessenger.app.data.remote

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

/**
 * Отправка через Cloud Function sendMessage.
 * Это единственный исходящий запрос при отправке (~1 КБ, только на Google).
 * Функция находит свежий FCM-токен получателя и кладёт
 * всё сообщение целиком внутрь FCM data-пуша.
 */
class Relay {
    private val fn: FirebaseFunctions = Firebase.functions

    suspend fun send(toUid: String, kind: String, id: String, ts: Long, ct: String = ""): String {
        val res = fn.getHttpsCallable("sendMessage")
            .call(
                hashMapOf(
                    "to" to toUid,
                    "kind" to kind, // msg | ack | read | typing
                    "id" to id,
                    "ts" to ts,
                    "ct" to ct
                )
            )
            .await()
        @Suppress("UNCHECKED_CAST")
        val map = res.data as? Map<String, Any?>
        return map?.get("fcmId") as? String ?: ""
    }
}
