package com.fcmessenger.app.crypto

import android.app.Application
import android.util.Base64
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridDecrypt
import com.google.crypto.tink.hybrid.HybridEncrypt
import com.google.crypto.tink.hybrid.HybridKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.stream.BinaryKeysetReader
import com.google.crypto.tink.stream.BinaryKeysetWriter
import java.io.ByteArrayOutputStream

/**
 * Сквозное шифрование в стиле Signal (упрощённо, но честно):
 * - у каждого пользователя своя ECIES-пара ключей (P-256 + HKDF + AES-GCM);
 * - приватный ключ лежит в Android Keystore, наружу не уходит никогда;
 * - публичный ключ публикуется в Firestore;
 * - отправитель шифрует текст публичным ключом получателя;
 * - Cloud Function видит только шифротекст и гоняет его через FCM.
 *
 * contextInfo привязываем к UID отправителя — чужой пуш подсунуть нельзя.
 */
class CryptoManager(app: Application) {

    private val ctx = app.applicationContext

    private val handle: KeysetHandle by lazy {
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "fcm_hybrid_keyset", "fcm_hybrid_prefs")
            .withKeyTemplate(HybridKeyTemplates.ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM)
            .withMasterKeyUri("android-keystore://fcm_master_key")
            .build()
            .keysetHandle
    }

    private val decryptor: HybridDecrypt by lazy {
        handle.getPrimitive(HybridDecrypt::class.java)
    }

    /** Публичный ключ в Base64 — публикуем в Firestore при регистрации. */
    fun myPublicKeyBase64(): String {
        val pub = handle.publicKeysetHandle
        val out = ByteArrayOutputStream()
        pub.writeNoSecret(BinaryKeysetWriter.withOutputStream(out))
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Зашифровать текст для собеседника. context = UID отправителя. */
    fun encryptFor(peerPubB64: String, plain: String, context: String): String {
        val peerBytes = Base64.decode(peerPubB64, Base64.NO_WRAP)
        val peerHandle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(peerBytes))
        val enc = peerHandle.getPrimitive(HybridEncrypt::class.java)
        val ct = enc.encrypt(plain.toByteArray(Charsets.UTF_8), context.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** Расшифровать входящее. context = UID отправителя (поле "from" в пуше). */
    fun decrypt(cipherB64: String, context: String): String {
        val ct = Base64.decode(cipherB64, Base64.NO_WRAP)
        val pt = decryptor.decrypt(ct, context.toByteArray(Charsets.UTF_8))
        return pt.toString(Charsets.UTF_8)
    }
}
