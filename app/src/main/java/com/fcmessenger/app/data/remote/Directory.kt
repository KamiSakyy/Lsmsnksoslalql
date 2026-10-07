package com.fcmessenger.app.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

data class RemoteUser(val uid: String, val name: String, val pub: String)

/**
 * Справочник в Firestore. Используется редко:
 * регистрация, добавление контакта, обновление токена.
 * Сама переписка идёт ТОЛЬКО через FCM.
 */
class Directory {
    private val db: FirebaseFirestore = Firebase.firestore

    suspend fun publishMe(uid: String, name: String, username: String, token: String?, pub: String) {
        db.collection("users").document(uid).set(
            mapOf(
                "name" to name,
                "username" to username.lowercase(),
                "token" to (token ?: ""),
                "pub" to pub,
                "ts" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun updateToken(uid: String, token: String) {
        db.collection("users").document(uid)
            .update(mapOf("token" to token, "ts" to System.currentTimeMillis()))
            .await()
    }

    suspend fun getUser(uid: String): RemoteUser? {
        val d = db.collection("users").document(uid).get().await()
        if (!d.exists()) return null
        return RemoteUser(
            uid = uid,
            name = d.getString("name") ?: "?",
            pub = d.getString("pub") ?: return null
        )
    }

    /** username -> uid */
    suspend fun resolveUsername(username: String): String? {
        val d = db.collection("usernames").document(username.lowercase()).get().await()
        return d.getString("uid")
    }

    /** Занять username. false — уже занят. */
    suspend fun claimUsername(username: String, uid: String): Boolean {
        val ref = db.collection("usernames").document(username.lowercase())
        return try {
            db.runTransaction { tx ->
                if (tx.get(ref).exists()) throw IllegalStateException("taken")
                tx.set(ref, mapOf("uid" to uid))
            }.await()
            true
        } catch (e: Exception) {
            false
        }
    }
}
